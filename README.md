# jenkins-atom-junit-report

The [junit](https://plugins.jenkins.io/junit/) plugin (≈226k installations) as an **atom**: read the XML your build
already produced, summarise it, publish a report on the build page.

* `pattern` — workspace-relative glob (`**/surefire-reports/*.xml`, `**/test-results/**/*.xml`)
* outputs — `total`, `failed`, `errors`, `skipped`, `durationSeconds` (the key is the variable name: `$total` …)
* `report` — HTML written to `out/junit/index.html`, archived and embedded in the build page
* `failOnFailure` — off by default: publishing results should not decide the build, the tests already did

Handles both `<testsuite>` and `<testsuites>` shapes, refuses DOCTYPEs (a report is data, not a document), and caps
the failure table at 200 rows.

**What it does not do**: Jenkins' own test-result trend page (job sidebar, chart across builds). That needs the job
UI host, not another atom.

## Build and smoke test

```bash
./build.sh             # → dist/junit-report/{junit-report.jar,task.json}, SDK inside
./tools/smoke.sh       # two XML shapes, failure table, failOnFailure, missing reports
```
