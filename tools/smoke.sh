#!/usr/bin/env bash
# Run the atom the way the runner does, against JUnit XML written here: no Jenkins, no JVM test framework.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=7 JOB_NAME="$(basename "$PWD")-job"
./build.sh >/dev/null
jar="$(cd dist/junit-report && pwd)/junit-report.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fail() { echo "smoke failed: $*" >&2; exit 1; }

run() { # run <dir>
  local dir="$1"
  ( cd "$dir" && env atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner )
}

# 1. two ways of writing the same results: <testsuite> and <testsuites>
dir="$(mktemp -d)"; mkdir -p "$dir/target/surefire-reports"
cat >"$dir/target/surefire-reports/TEST-a.xml" <<'XML'
<testsuite name="a" tests="3" failures="1" errors="0" skipped="1" time="1.250">
  <testcase classname="demo.A" name="passes"/>
  <testcase classname="demo.A" name="fails"><failure message="expected 1 but was 2">stack…</failure></testcase>
  <testcase classname="demo.A" name="skipped"><skipped/></testcase>
</testsuite>
XML
cat >"$dir/target/surefire-reports/b.xml" <<'XML'
<testsuites>
  <testsuite name="b" tests="2" failures="0" errors="1" skipped="0" time="0.500">
    <testcase classname="demo.B" name="boom"><error message="connection refused">trace…</error></testcase>
    <testcase classname="demo.B" name="ok"/>
  </testsuite>
</testsuites>
XML
cat >"$dir/input.json" <<'JSON'
{"pattern": "**/surefire-reports/*.xml", "label": "Unit tests", "outputDir": "out/junit", "failOnFailure": "false"}
JSON
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"

grep -q '"status":"success"' "$dir/output.json" || fail "not a success: $(cat "$dir/output.json")"
grep -q '"total":{"type":"string","value":"5"}' "$dir/output.json" || fail "total: $(cat "$dir/output.json")"
grep -q '"failed":{"type":"string","value":"1"}' "$dir/output.json" || fail "failed count"
grep -q '"errors":{"type":"string","value":"1"}' "$dir/output.json" || fail "error count"
grep -q '"skipped":{"type":"string","value":"1"}' "$dir/output.json" || fail "skipped count"
grep -q '"type":"report"' "$dir/output.json" || fail "report output missing"
[ -f "$dir/out/junit/index.html" ] || fail "no HTML report written"
grep -q "demo.A#fails" "$dir/out/junit/index.html" || fail "failing test not listed"
grep -q "connection refused" "$dir/out/junit/index.html" || fail "error message not listed"
grep -q "Every test passed" "$dir/out/junit/index.html" && fail "a passing report must not claim success"

# 2. failOnFailure turns failed cases into a failed atom, with the report still published
dir="$(mktemp -d)"; mkdir -p "$dir/reports"
cat >"$dir/reports/x.xml" <<'XML'
<testsuite tests="1" failures="1" errors="0" skipped="0" time="0.1">
  <testcase classname="demo.C" name="red"><failure message="nope"/></testcase>
</testsuite>
XML
cat >"$dir/input.json" <<'JSON'
{"pattern": "reports/*.xml", "label": "Unit tests", "outputDir": "out/junit", "failOnFailure": "true"}
JSON
if run "$dir" >"$dir/log.txt" 2>&1; then fail "failOnFailure=true must fail the atom"; fi
grep -q '"status":"failure"' "$dir/output.json" || fail "failure not reported: $(cat "$dir/output.json")"
grep -q '"report"' "$dir/output.json" || fail "the report must still be published"
[ -f "$dir/out/junit/index.html" ] || fail "report missing in the failing case"

# 3. no matching file is a clear error, not an empty report
dir="$(mktemp -d)"
cat >"$dir/input.json" <<'JSON'
{"pattern": "**/surefire-reports/*.xml", "label": "Unit", "outputDir": "out/junit", "failOnFailure": "false"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "missing reports must fail the atom"; fi
grep -q 'no test report matched' "$dir/output.json" || fail "message is not readable: $(cat "$dir/output.json")"

echo "smoke ok: totals, failure list, failOnFailure, missing reports"
