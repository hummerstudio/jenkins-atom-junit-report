package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JUnit report atom over XML written here: no Jenkins, no test framework.
 *
 * <p>{@code tools/smoke.sh} checked both spellings of the report ({@code <testsuite>} and {@code <testsuites>}), the
 * totals, the UNSTABLE request, the report page, {@code failOnFailure}, missing reports and an unreadable report.
 */
class JunitReportIT {

    private static final String TWO_SUITES = "target/surefire-reports/TEST-a.xml";
    private static final String SUITE_A = "<testsuite name=\"a\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"1\" time=\"1.250\">\n"
            + "  <testcase classname=\"demo.A\" name=\"passes\"/>\n"
            + "  <testcase classname=\"demo.A\" name=\"fails\"><failure message=\"expected 1 but was 2\">stack…</failure></testcase>\n"
            + "  <testcase classname=\"demo.A\" name=\"skipped\"><skipped/></testcase>\n"
            + "</testsuite>";
    private static final String SUITES_B = "<testsuites>\n"
            + "  <testsuite name=\"b\" tests=\"2\" failures=\"0\" errors=\"1\" skipped=\"0\" time=\"0.500\">\n"
            + "    <testcase classname=\"demo.B\" name=\"boom\"><error message=\"connection refused\">trace…</error></testcase>\n"
            + "    <testcase classname=\"demo.B\" name=\"ok\"/>\n"
            + "  </testsuite>\n"
            + "</testsuites>";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("both report spellings are summed, the report is published, a failure asks for UNSTABLE")
    void sumsBothSpellings() throws Exception {
        AtomResult run = atom.file(TWO_SUITES, SUITE_A)
                .file("target/surefire-reports/b.xml", SUITES_B)
                .input("{\"pattern\": \"**/surefire-reports/*.xml\", \"label\": \"Unit tests\", "
                        + "\"outputDir\": \"out/junit\", \"failOnFailure\": \"false\"}")
                .run();

        run.assertOk();
        assertEquals("5", run.field("total"));
        assertEquals("1", run.field("failed"));
        assertEquals("1", run.field("errors"));
        assertEquals("1", run.field("skipped"));
        assertEquals("index.html", run.field("report.target"), "the report output must point at the page");
        assertTrue(run.field("report.path").endsWith("out/junit"), run.field("report.path"));
        assertEquals("UNSTABLE", raw(run, "build.result"), "a failing report must ask for UNSTABLE");

        String page = run.file("out/junit/index.html");
        assertTrue(page != null, "no HTML report was written");
        assertTrue(page.contains("demo.A#fails"), "the failing test must be listed");
        assertTrue(page.contains("connection refused"), "the error message must be listed");
        assertFalse(page.contains("Every test passed"), "a failing report must not claim success");
    }

    @Test
    @DisplayName("skipMarkingUnstable takes the UNSTABLE request away")
    void skipsTheUnstableMark() throws Exception {
        AtomResult run = atom.file("reports/x.xml",
                        "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"0.1\">\n"
                                + "  <testcase classname=\"demo.D\" name=\"red\"><failure message=\"nope\"/></testcase>\n"
                                + "</testsuite>")
                .input("{\"testResults\": \"reports/*.xml\", \"skipMarkingUnstable\": \"true\"}")
                .run();

        run.assertOk();
        assertEquals("", raw(run, "build.result"), "skipMarkingUnstable must not request UNSTABLE");
    }

    @Test
    @DisplayName("allowEmptyResults turns a missing report into zeros")
    void allowsEmptyResults() throws Exception {
        AtomResult run = atom.input("{\"testResults\": \"**/nope.xml\", \"allowEmptyResults\": \"true\"}").run();

        run.assertOk();
        assertEquals("0", run.field("total"));
    }

    @Test
    @DisplayName("failOnFailure turns a failed case into a failed atom, with the report still published")
    void failsOnFailureButStillPublishes() throws Exception {
        AtomResult run = atom.file("reports/x.xml",
                        "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"0.1\">\n"
                                + "  <testcase classname=\"demo.C\" name=\"red\"><failure message=\"nope\"/></testcase>\n"
                                + "</testsuite>")
                .input("{\"pattern\": \"reports/*.xml\", \"label\": \"Unit tests\", \"outputDir\": \"out/junit\", "
                        + "\"failOnFailure\": \"true\"}")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status());
        assertFalse(run.field("report.path").isEmpty(), "the report must still be published");
        assertTrue(run.exists("out/junit/index.html"), "report missing in the failing case");
    }

    @Test
    @DisplayName("no matching report is a clear error, not an empty report")
    void refusesWhenNothingMatches() throws Exception {
        AtomResult run = atom.input("{\"pattern\": \"**/surefire-reports/*.xml\", \"label\": \"Unit\", "
                + "\"outputDir\": \"out/junit\", \"failOnFailure\": \"false\"}").run();

        run.assertRefused();
        assertTrue(run.field("message").contains("no test report matched"), run.field("message"));
    }

    @Test
    @DisplayName("a truncated report fails by default and is tolerated only when the caller opts in")
    void refusesUnreadableReports() throws Exception {
        AtomResult run = atom.file("reports/truncated.xml", "<testsuite><testcase name=\"half")
                .input("{\"pattern\": \"reports/*.xml\", \"outputDir\": \"out/junit\"}")
                .run();

        run.assertRefused();
        assertEquals("40003", run.field("errorCode"));
        assertTrue(run.field("message").contains("truncated.xml"), "the failure must name the file");

        AtomResult tolerant = atom.file("reports/truncated.xml", "<testsuite><testcase name=\"half")
                .input("{\"pattern\": \"reports/*.xml\", \"outputDir\": \"out/junit\", "
                        + "\"allowUnreadableReports\": \"true\"}")
                .run();

        tolerant.assertOk();
        assertEquals("0", tolerant.field("total"));
    }

    /** A {@code data} entry the dotted path cannot reach — {@code build.result} has a dot in its name. */
    private static String raw(AtomResult run, String key) {
        Object data = run.document().get("data");
        if (data instanceof Map<?, ?> map && map.get(key) instanceof Map<?, ?> field) {
            Object value = field.get("value");
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}
