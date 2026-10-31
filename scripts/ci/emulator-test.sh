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
step() { echo; echo "=================== $*"; }
check() { if eval "$2" >/dev/null 2>&1; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
texts() { python3 scripts/ci/ui.py texts > "$OUT/$1.txt"; echo "--- screen text ($1):"; head -40 "$OUT/$1.txt"; }

run_setup_flow() {
  adb shell am start -W -n "$PKG/.ui.MainActivity"
  sleep 6
  shot "$1-setup"; texts "$1-setup"
  check "[$1] setup screen is shown on first launch" "grep -q 'Welcome to WorkDeck' $OUT/$1-setup.txt"
  python3 scripts/ci/ui.py tap "Start WorkDeck" 15
  echo "waiting for Teams / Outlook to load..."
  sleep 40
  shot "$1-main"; texts "$1-main"
  check "[$1] main screen shows Teams and Outlook tabs" "grep -q 'Show Teams' $OUT/$1-main.txt && grep -q 'Show Outlook' $OUT/$1-main.txt"
  adb shell dumpsys activity services "$PKG" > "$OUT/$1-services.txt"
  grep -E "ServiceRecord|isForeground|foregroundServiceType|types=" "$OUT/$1-services.txt" | head -8
  check "[$1] keep-alive foreground service is running" "grep -q 'isForeground=true' $OUT/$1-services.txt"
}

adb shell settings put global heads_up_notifications_enabled 1
adb logcat -c

step "Install debug build (all runtime permissions granted with -g)"
adb install -r -g "$DEBUG_APK"
adb shell dumpsys deviceidle whitelist +"$PKG"
run_setup_flow debug

step "WebView feature checks over DevTools protocol"
node tests/android/cdp-test.js "$OUT" || fail=1

step "Background survival: home screen for 60s"
adb shell input keyevent KEYCODE_HOME
sleep 60
check "process still alive after 60s in background" "adb shell pidof $PKG"
adb shell dumpsys activity services "$PKG" > "$OUT/bg-services.txt"
check "foreground service still running in background" "grep -q 'isForeground=true' $OUT/bg-services.txt"

step "Swipe away from Recents (remove task)"
TASK=$(adb shell dumpsys activity recents | grep -m1 -oE "Recent #0: Task\{[0-9a-f]+ #[0-9]+" | grep -oE "#[0-9]+$" | tr -d '#')
echo "task id: ${TASK:-none}"
if [ -n "${TASK:-}" ]; then
  adb shell am stack remove "$TASK" 2>/dev/null || adb shell cmd activity remove-task "$TASK" 2>/dev/null || true
fi
sleep 20
check "process survives removal from Recents" "adb shell pidof $PKG"
adb shell am start -W -n "$PKG/.ui.MainActivity"
sleep 5
shot "after-recents"; texts "after-recents"
check "reopening shows the main screen again (no re-setup)" "grep -q 'Show Teams' $OUT/after-recents.txt"

step "Crash check (logcat)"
adb logcat -d > "$OUT/logcat-debug.txt"
grep -E "WorkDeck/|E AndroidRuntime" "$OUT/logcat-debug.txt" | tail -60
check "no crash in debug run" "! grep -q 'FATAL EXCEPTION' $OUT/logcat-debug.txt"
grep -A25 "FATAL EXCEPTION" "$OUT/logcat-debug.txt" | head -60

if [ -n "$RELEASE_APK" ] && [ -f "$RELEASE_APK" ]; then
  step "Smoke-test the delivered release APK ($RELEASE_APK)"
  adb uninstall "$PKG" >/dev/null 2>&1
  adb logcat -c
  adb install -r -g "$RELEASE_APK"
  adb shell dumpsys deviceidle whitelist +"$PKG"
  run_setup_flow release
  adb logcat -d > "$OUT/logcat-release.txt"
  grep -E "WorkDeck/|E AndroidRuntime" "$OUT/logcat-release.txt" | tail -30
  check "no crash in release run" "! grep -q 'FATAL EXCEPTION' $OUT/logcat-release.txt"
  grep -A25 "FATAL EXCEPTION" "$OUT/logcat-release.txt" | head -60
fi

echo
[ "$fail" = 0 ] && echo "EMULATOR TEST: ALL PASSED" || echo "EMULATOR TEST: SOME CHECKS FAILED"
exit "$fail"
