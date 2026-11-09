#!/usr/bin/env bash
# Device test of the single-app editions (OMC/CRAFTWW Teams/Outlook) - runs after
# emulator-test.sh on the same emulator (WorkDeck stays installed: they must coexist).
#   $1 = directory with the Gradle debug APKs of :solo (any layout below it)
set -uo pipefail
DIR="$1"
OUT=ci-out/solo
mkdir -p "$OUT"
echo
echo "=================== Single-app editions: install side by side"
fail=0
for v in omc-teams omc-outlook craftww-teams craftww-outlook; do
  apk="$(find "$DIR" -name "*$v*debug*.apk" | head -1)"
  if [ -z "$apk" ]; then echo "FAIL  [solo] $v APK not built" | tee -a ci-out/summary.txt; fail=1; continue; fi
  echo "installing $apk"
  timeout 180 adb install -r -g "$apk" | tail -1
done
adb logcat -c
echo
echo "=================== Single-app editions: checks"
timeout 900 node tests/android/solo-test.js "$OUT" || fail=1
timeout 60 adb logcat -d > "$OUT/logcat.txt"
grep -E "WorkDeck/(Call|Main|Accounts|Tab)" "$OUT/logcat.txt" | tail -60
if grep -aE "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "Process: com.workdeck.\(omc\|craftww\)"; then
  echo "FAIL  [solo] an app crashed" | tee -a ci-out/summary.txt
  grep -aE "FATAL EXCEPTION" -A25 "$OUT/logcat.txt" | head -40
  fail=1
else
  echo "PASS  [solo] no crash in the single-app editions" | tee -a ci-out/summary.txt
fi
exit $fail
