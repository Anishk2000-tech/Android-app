#!/usr/bin/env bash
# Real-device smoke + feature test for WorkDeck on an Android emulator (GitHub Actions).
#   $1 = debuggable APK (Gradle debug build)   $2 = optional release APK to smoke-test
set -uo pipefail
DEBUG_APK="$1"
RELEASE_APK="${2:-}"
PKG=com.workdeck.app
OUT=ci-out
mkdir -p "$OUT"
fail=0
step() { echo; echo "=================== $*"; alive; }
check() {
  if eval "$2" >/dev/null 2>&1; then echo "PASS  $1" | tee -a "$OUT/summary.txt"; else echo "FAIL  $1" | tee -a "$OUT/summary.txt"; fail=1; fi
}
# Screenshots: full PNG kept as artifact, small thumbnail printed later by a separate CI step.
shot() { timeout 30 adb exec-out screencap -p > "$OUT/$1.png" && python3 scripts/ci/thumb.py "$OUT/$1.png" "$1" > "$OUT/$1.thumb" || true; }
# If the emulator itself dies, report what we have and stop (instead of waiting forever).
alive() {
  if ! timeout 15 adb get-state >/dev/null 2>&1; then
    echo "!!! EMULATOR IS GONE (the emulator process died, not the app) - last logcat lines:"
    grep -E "WorkDeck/|FATAL|AndroidRuntime|lowmemorykiller|chromium" "$OUT/logcat-stream.txt" 2>/dev/null | tail -60
    tail -30 "$OUT/logcat-stream.txt" 2>/dev/null
    echo "ABORTED: emulator process died" >> "$OUT/summary.txt"
    echo "EMULATOR TEST: ABORTED (emulator crashed)"
    exit 3
  fi
}
mem() { timeout 10 adb shell "cat /proc/meminfo | head -3" 2>/dev/null | tr -s ' ' | tr '\n' ' '; echo; }
texts() { python3 scripts/ci/ui.py texts > "$OUT/$1.txt"; echo "--- screen text ($1):"; head -40 "$OUT/$1.txt"; }

run_setup_flow() {
  adb shell am start -W -n "$PKG/.ui.MainActivity"
  sleep 6
  shot "$1-setup"; texts "$1-setup"
  check "[$1] setup screen is shown on first launch" "grep -q 'Welcome to WorkDeck' $OUT/$1-setup.txt"
  python3 scripts/ci/ui.py tap "Start WorkDeck" 15
  echo "waiting for Teams / Outlook to load..."
  for i in 1 2 3 4; do sleep 10; alive; echo "  t+${i}0s mem: $(mem)"; done
  shot "$1-main"; texts "$1-main"
  check "[$1] main screen shows Teams and Outlook tabs" "grep -q 'Show Teams' $OUT/$1-main.txt && grep -q 'Show Outlook' $OUT/$1-main.txt"
  adb shell dumpsys activity services "$PKG" > "$OUT/$1-services.txt"
  grep -E "ServiceRecord|isForeground|foregroundServiceType|types=" "$OUT/$1-services.txt" | head -8
  check "[$1] keep-alive foreground service is running" "grep -q 'isForeground=true' $OUT/$1-services.txt"
}

adb shell settings put global heads_up_notifications_enabled 1
adb logcat -c
# Keep a running copy of logcat on the host so it survives an emulator crash.
(adb logcat -v time > "$OUT/logcat-stream.txt" 2>&1 &)

step "Install debug build (all runtime permissions granted with -g)"
adb install -r -g "$DEBUG_APK"
adb shell dumpsys deviceidle whitelist +"$PKG"
run_setup_flow debug

step "WebView feature checks over DevTools protocol"
timeout 420 node tests/android/cdp-test.js "$OUT" || fail=1

step "Background survival: home screen for 60s"
adb shell input keyevent KEYCODE_HOME
sleep 60
check "process still alive after 60s in background" "adb shell pidof $PKG"
adb shell dumpsys activity services "$PKG" > "$OUT/bg-services.txt"
check "foreground service still running in background" "grep -q 'isForeground=true' $OUT/bg-services.txt"

step "Swipe away from Recents, then reopen: same page must still be in memory"
node tests/android/cdp-eval.js "window.__ciMarker = 4242" > /dev/null
adb shell am start -W -n "$PKG/.ui.MainActivity" > /dev/null
sleep 3
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 3
shot "recents"
adb shell input swipe 540 1250 540 80 180
sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell dumpsys activity recents | grep -c "$PKG" > "$OUT/recents-count.txt" || true
echo "WorkDeck tasks left in Recents: $(cat $OUT/recents-count.txt)"
sleep 20
check "process survives being swiped away from Recents" "adb shell pidof $PKG"
adb shell dumpsys activity services "$PKG" > "$OUT/recents-services.txt"
check "foreground service survives the swipe" "grep -q 'isForeground=true' $OUT/recents-services.txt"
adb shell am start -W -n "$PKG/.ui.MainActivity"
sleep 5
shot "after-recents"; texts "after-recents"
check "reopening shows the main screen again (no re-setup)" "grep -q 'Show Teams' $OUT/after-recents.txt"
MARK=$(node tests/android/cdp-eval.js "window.__ciMarker")
echo "marker after reopen: $MARK"
check "Teams page was kept alive in memory (not reloaded)" "[ \"$MARK\" = 4242 ]"

step "Crash check (logcat)"
timeout 60 adb logcat -d > "$OUT/logcat-debug.txt"
grep -E "WorkDeck/|E AndroidRuntime" "$OUT/logcat-debug.txt" | tail -60
# Only WorkDeck's own process counts (other emulator apps crash now and then).
crash_of_ours() { grep -A3 "FATAL EXCEPTION" "$1" | grep -q "Process: $PKG"; }
check "no WorkDeck crash in debug run" "! crash_of_ours $OUT/logcat-debug.txt"
grep -A3 "FATAL EXCEPTION" "$OUT/logcat-debug.txt" | grep "Process:" | sort | uniq -c | sed 's/^/  crashed process: /' | tee -a "$OUT/summary.txt"
grep -A30 "Process: $PKG" "$OUT/logcat-debug.txt" | grep "AndroidRuntime" | head -25 | tee -a "$OUT/summary.txt"

if [ -n "$RELEASE_APK" ] && [ -f "$RELEASE_APK" ]; then
  step "Smoke-test the delivered release APK ($RELEASE_APK)"
  adb uninstall "$PKG" >/dev/null 2>&1
  timeout 20 adb logcat -c
  adb install -r -g "$RELEASE_APK"
  adb shell dumpsys deviceidle whitelist +"$PKG"
  run_setup_flow release
  timeout 60 adb logcat -d > "$OUT/logcat-release.txt"
  grep -E "WorkDeck/|E AndroidRuntime" "$OUT/logcat-release.txt" | tail -30
  check "no WorkDeck crash in release run" "! crash_of_ours $OUT/logcat-release.txt"
  grep -A30 "Process: $PKG" "$OUT/logcat-release.txt" | grep "AndroidRuntime" | head -25 | tee -a "$OUT/summary.txt"
fi

echo
[ "$fail" = 0 ] && echo "EMULATOR TEST: ALL PASSED" || echo "EMULATOR TEST: SOME CHECKS FAILED"
grep -hE "WorkDeck/" "$OUT"/logcat-*.txt 2>/dev/null | grep -vE "thumb" | sort -u | tail -40 > "$OUT/workdeck-log.txt"
exit "$fail"
