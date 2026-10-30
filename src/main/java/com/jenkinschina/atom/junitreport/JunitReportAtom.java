package com.jenkinschina.atom.junitreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * junit as an atom: read the JUnit XML a build produced, summarise it, and show it on the build page.
 *
 * <p>This is the plugin's core — collect surefire/pytest/gradle style XML, count tests, publish an HTML report —
 * without the plugin. What it does not do is Jenkins' own "test result trend" page: that lives in the job sidebar,
 * which is a UI host we have not built yet.
 */
public class JunitReportAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        // the plugin calls it testResults; "pattern" stays as an alias for the earlier atom versions
        String pattern = context.getParam("testResults", context.getParam("pattern", "**/surefire-reports/*.xml"));
        String label = context.getParam("label", "Test results");
        String outDir = context.getParam("outputDir", "out/junit");
        boolean failOnFailure = context.getBooleanParam("failOnFailure", false);
        boolean allowEmptyResults = context.getBooleanParam("allowEmptyResults", false);
        boolean skipMarkingUnstable = context.getBooleanParam("skipMarkingUnstable", false);
        Path workspace = context.getWorkspace();

        List<Path> files = find(workspace, pattern);
        if (files.isEmpty()) {
            if (allowEmptyResults) {
                context.log("[junit] no report matched " + pattern + "; allowEmptyResults is on");
                return AtomResult.success()
                        .data("total", "0").data("failed", "0").data("errors", "0").data("skipped", "0");
            }
            return AtomResult.error(ErrorType.USER, 40001, "no test report matched " + pattern
                    + " under " + workspace);
        }

        Summary summary = new Summary();
        List<Case> failures = new ArrayList<>();
        for (Path file : files) {
            read(file, summary, failures);
        }

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Path html = dir.resolve("index.html");
        Files.writeString(html, render(label, pattern, files.size(), summary, failures), StandardCharsets.UTF_8);

        context.log("[junit] " + summary.total + " tests, " + summary.failed + " failed, "
                + summary.errors + " errors, " + summary.skipped + " skipped, " + files.size() + " files");

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(summary.total))
                .data("failed", String.valueOf(summary.failed))
                .data("errors", String.valueOf(summary.errors))
                .data("skipped", String.valueOf(summary.skipped))
                .data("durationSeconds", String.format(Locale.ROOT, "%.3f", summary.seconds))
                .report("report", label, dir.toString(), "index.html");

        boolean broken = summary.failed > 0 || summary.errors > 0;
        // the plugin marks the build UNSTABLE on failures; that is the default here too, and it is separate from the
        // step's own success/failure
        if (broken && !skipMarkingUnstable) {
            result.markUnstable();
        }
        if (broken && failOnFailure) {
            return AtomResult.failure(summary.failed + " failed, " + summary.errors + " errors")
                    .data("total", String.valueOf(summary.total))
                    .data("failed", String.valueOf(summary.failed))
                    .data("errors", String.valueOf(summary.errors))
                    .data("skipped", String.valueOf(summary.skipped))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    /** Glob over the workspace, newest first, capped so a runaway directory tree cannot hang a build. */
    private static List<Path> find(Path workspace, String pattern) throws IOException {
        String glob = pattern.contains("/") ? pattern : "**/" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .filter(path -> matcher.matches(workspace.relativize(path)) || matcher.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path))))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(2000)
                    .forEach(found::add);
        }
        return found;
    }

    private static void read(Path file, Summary summary, List<Case> failures) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // the report is data, not a document: no DTDs, no external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(file.toFile());
            Element root = document.getDocumentElement();
            if (root == null) {
                return;
            }

            String tests = root.getAttribute("tests");
            if (!tests.isEmpty()) {
                summary.total += toInt(tests);
                summary.failed += toInt(root.getAttribute("failures"));
                summary.errors += toInt(root.getAttribute("errors"));
                summary.skipped += toInt(root.getAttribute("skipped"));
                summary.seconds += toDouble(root.getAttribute("time"));
                collect(root, failures);
                return;
            }

            // <testsuites> wrapper: totals per suite when there are suites…
            NodeList suites = root.getElementsByTagName("testsuite");
            if (suites.getLength() == 0) {
                // …but Node's test runner (and a few others) put <testcase> straight under <testsuites>
                NodeList cases = root.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element test = (Element) cases.item(i);
                    summary.total++;
                    if (test.getElementsByTagName("failure").getLength() > 0) {
                        summary.failed++;
                    }
                    if (test.getElementsByTagName("error").getLength() > 0) {
                        summary.errors++;
                    }
                    if (test.getElementsByTagName("skipped").getLength() > 0) {
                        summary.skipped++;
                    }
                    collectOne(test, failures);
                }
                return;
            }
            for (int i = 0; i < suites.getLength(); i++) {
                Element suite = (Element) suites.item(i);
                summary.total += toInt(suite.getAttribute("tests"));
                summary.failed += toInt(suite.getAttribute("failures"));
                summary.errors += toInt(suite.getAttribute("errors"));
                summary.skipped += toInt(suite.getAttribute("skipped"));
                summary.seconds += toDouble(suite.getAttribute("time"));
                collect(suite, failures);
            }
        } catch (Exception e) {
            summary.unreadable++;
        }
    }

    /** The failing part of a single flat testcase element. */
    private static void collectOne(Element test, List<Case> failures) {
        boolean broken = test.getElementsByTagName("failure").getLength() > 0
                || test.getElementsByTagName("error").getLength() > 0;
        if (!broken) {
            return;
        }
        String message = "";
        for (String tag : List.of("failure", "error")) {
            NodeList nodes = test.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                Element element = (Element) nodes.item(0);
                message = firstLine(element.getAttribute("message").isEmpty()
                        ? element.getTextContent() : element.getAttribute("message"));
                break;
            }
        }
        if (failures.size() < 200) {
            failures.add(new Case(test.getAttribute("classname"), test.getAttribute("name"), "failure", message));
        }
    }

    /** Test cases that did not pass, with the first line of their message — enough to know where to look. */
    private static void collect(Element scope, List<Case> failures) {
        NodeList cases = scope.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
            String verdict = null;
            String message = "";
            NodeList children = test.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                if (!(children.item(c) instanceof Element child)) {
                    continue;
                }
                String tag = child.getTagName();
                if ("failure".equals(tag) || "error".equals(tag)) {
                    verdict = tag;
                    message = firstLine(child.getAttribute("message").isEmpty()
                            ? child.getTextContent()
                            : child.getAttribute("message"));
                    break;
                }
                if ("skipped".equals(tag)) {
                    verdict = "skipped";
                }
            }
            if (verdict != null && !"skipped".equals(verdict) && failures.size() < 200) {
                failures.add(new Case(test.getAttribute("classname"), test.getAttribute("name"), verdict, message));
            }
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private static String render(String label, String pattern, int files, Summary summary, List<Case> failures) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9;color:#1f2328}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 20px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:22px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:14px 18px;min-width:110px}")
                .append(".card b{display:block;font-size:24px;line-height:1.2}.card span{font-size:12px;color:#6b7280}")
                .append(".bad b{color:#b91c1c}.ok b{color:#15803d}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;border-radius:10px;overflow:hidden}")
                .append("th,td{text-align:left;padding:9px 12px;border-bottom:1px solid #eef0f3;font-size:13px;vertical-align:top}")
                .append("th{background:#fafbfc;color:#6b7280;font-weight:600}code{font-family:ui-monospace,Menlo,monospace}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1>");
        html.append("<p class=\"meta\">").append(files).append(" file(s) matched <code>")
                .append(escape(pattern)).append("</code>");
        if (summary.unreadable > 0) {
            html.append(" · ").append(summary.unreadable).append(" unreadable");
        }
        html.append("</p><div class=\"cards\">");
        card(html, "total", summary.total, false);
        card(html, "failed", summary.failed, summary.failed > 0);
        card(html, "errors", summary.errors, summary.errors > 0);
        card(html, "skipped", summary.skipped, false);
        html.append("<div class=\"card\"><b>").append(String.format(Locale.ROOT, "%.2fs", summary.seconds))
                .append("</b><span>duration</span></div></div>");

        if (failures.isEmpty()) {
            html.append("<p>Every test passed.</p>");
        } else {
            html.append("<table><tr><th>Test</th><th>Verdict</th><th>Message</th></tr>");
            for (Case item : failures) {
                html.append("<tr><td><code>").append(escape(item.className)).append('#').append(escape(item.name))
                        .append("</code></td><td>").append(escape(item.verdict)).append("</td><td>")
                        .append(escape(item.message)).append("</td></tr>");
            }
            html.append("</table>");
        }
        return html.append("</body></html>").toString();
    }

    private static void card(StringBuilder html, String label, int value, boolean bad) {
        html.append("<div class=\"card").append(bad ? " bad" : "").append("\"><b>").append(value)
                .append("</b><span>").append(label).append("</span></div>");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static int toInt(String value) {
        try {
            return value == null || value.isBlank() ? 0 : (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double toDouble(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Case(String className, String name, String verdict, String message) {}

    private static final class Summary {
        int total;
        int failed;
        int errors;
        int skipped;
        int unreadable;
        double seconds;
    }
}
