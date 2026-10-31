#!/usr/bin/env bash
# Runs WorkDeck's automated checks:
#   1. JVM logic tests (URL routing, call heuristics)  - needs: javac/java, ANDROID_JAR, LAMBDA_STUBS
#   2. Injected-script tests in Chromium (Playwright)  - needs: node + playwright
# Usage: scripts/run-tests.sh   (expects scripts/build-offline.sh to have run, for compiled classes)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${LAMBDA_STUBS:?set LAMBDA_STUBS}"
OUT="$ROOT/build/tests"
rm -rf "$OUT" && mkdir -p "$OUT"
CLASSES="$ROOT/build/offline/classes"
[ -d "$CLASSES" ] || { echo "Run scripts/build-offline.sh first"; exit 1; }

echo "== JVM logic tests"
javac -nowarn -encoding UTF-8 -Xlint:none -source 8 -target 8 -bootclasspath "$LAMBDA_STUBS:$ANDROID_JAR" \
    -cp "$CLASSES" -d "$OUT" "$ROOT/tests/jvm/LogicTest.java" 2>&1 | grep -v -E '^(warning|Note|Picked up)' || true
java -cp "$OUT:$CLASSES" LogicTest

echo "== Injected-script tests (Chromium)"
NODE_PATH="${NODE_PATH:-$(npm root -g)}" node "$ROOT/tests/js/inject.test.js"
