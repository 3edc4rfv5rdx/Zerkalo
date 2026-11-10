#!/usr/bin/env bash
#
# Run the JVM unit tests and print a text summary per class.
#
#   All tests:      ./06-Test.sh
#   One class:      ./06-Test.sh --tests 'xx.project.SomeTest'
#   Matching set:   ./06-Test.sh --tests 'xx.project.*FormatTest'
#
set -uo pipefail
cd "$(dirname "$0")" || exit 1
. ./98-common

RESULTS="$APP_MODULE/build/test-results/testDebugUnitTest"

# Last run's XML is cleared before this one starts. A build that does not compile
# writes no results at all, and the summary below would otherwise read the previous
# run's files and report a clean pass over code that never ran. The same holds for
# --tests: without this, a filtered run summarises every class the last full run
# left behind.
rm -rf "$RESULTS"

# Don't abort on failing tests — the summary below is exactly what we want to
# see then.
./gradlew testDebugUnitTest --rerun-tasks "$@" || status=$?

echo
RESULTS="$RESULTS" \
    REPORT="$APP_MODULE/build/reports/tests/testDebugUnitTest/index.html" \
    python3 - <<'PY'
import glob, os, xml.etree.ElementTree as ET
files = sorted(glob.glob(os.path.join(os.environ['RESULTS'], '*.xml')))
if not files:
    # Not a pass: nothing ran. The exit code below carries that out to the caller.
    print("No test results — the run failed before writing any.")
    raise SystemExit(1)
tot = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
broken = []
for path in files:
    r = ET.parse(path).getroot()
    n = {k: int(r.get(k, 0)) for k in tot}
    for k in tot:
        tot[k] += n[k]
    mark = 'OK  ' if n['failures'] == n['errors'] == 0 else 'FAIL'
    print(f"{mark} {r.get('name')}: tests={n['tests']} failures={n['failures']} "
          f"errors={n['errors']} skipped={n['skipped']}")
    # The failing tests themselves, so the run says what broke without opening the
    # HTML report.
    for case in r.findall('testcase'):
        for bad in list(case.findall('failure')) + list(case.findall('error')):
            broken.append((case.get('classname'), case.get('name'),
                           (bad.get('message') or '').strip().splitlines()))
if broken:
    print("\nFailures:")
    for cls, name, msg in broken:
        print(f"\n  {cls.split('.')[-1]}.{name}")
        for line in msg[:6]:
            print(f"      {line}")
print(f"\nTotal: tests={tot['tests']} failures={tot['failures']} "
      f"errors={tot['errors']} skipped={tot['skipped']}")
print(f"HTML: {os.environ['REPORT']}")
PY

# A summary that could not be produced is a failure of its own: Gradle can end
# green having run nothing at all, and that must not read as tests passing.
summary=$?

pause 3
exit "${status:-$summary}"
