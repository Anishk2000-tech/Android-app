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
if grep -aq "ANR in com.workdeck.\(omc\|craftww\)" ci-out/logcat-stream.txt 2>/dev/null; then
  echo "FAIL  [solo] a single-app edition stopped responding (ANR)" | tee -a ci-out/summary.txt
  grep -aA8 "ANR in com.workdeck.\(omc\|craftww\)" ci-out/logcat-stream.txt | head -30
  fail=1
else
  echo "PASS  [solo] the single-app editions never stopped responding" | tee -a ci-out/summary.txt
fi
# Any of our apps stopped responding (also WorkDeck, earlier on this emulator): show what
# its main thread was doing (the emulator image allows adb root).
if grep -aq "ANR in com.workdeck" ci-out/logcat-stream.txt 2>/dev/null; then
  echo "--- main thread when a WorkDeck app stopped responding:"
  timeout 30 adb root >/dev/null 2>&1; sleep 4; timeout 60 adb wait-for-device
  for f in $(timeout 20 adb shell ls -t /data/anr 2>/dev/null | tr -d '\r' | head -4); do
    timeout 20 adb shell cat "/data/anr/$f" 2>/dev/null | tr -d '\r' \
      | awk '/^Cmd line:/{p=($0 ~ /com\.workdeck/)} p && /^"(main|RenderThread|workdeck-stage-frames)"/{m=1; n=0} m && /^$/{m=0} m && n<26{print; n++}'
  done | head -160 | tee "$OUT/anr-main-thread.txt"
fi
exit $fail
