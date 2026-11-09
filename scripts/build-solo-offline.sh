#!/usr/bin/env bash
# Builds and signs the single-app editions WITHOUT Gradle (same tools as build-offline.sh):
#   OMC Teams, OMC Outlook, CRAFTWW Teams, CRAFTWW Outlook.
# usage: build-solo-offline.sh [account app ...]   e.g.  build-solo-offline.sh omc teams
#        (no arguments: all four)
# Output: build/solo/<ACCOUNT>-<App>-<version>.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${AAPT2:?set AAPT2}" "${R8_JAR:?set R8_JAR}" "${LAMBDA_STUBS:?set LAMBDA_STUBS}"
: "${KEYSTORE:?set KEYSTORE}" "${KEY_ALIAS:=workdeck}" "${WORKDECK_KS_PASS:?set WORKDECK_KS_PASS}"
export WORKDECK_KS_PASS

APP="$ROOT/app"
SOLO="$ROOT/solo"
OUT_ALL="$ROOT/build/solo"
MIN_SDK=26
TARGET_SDK=35
VERSION_CODE="$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$SOLO/build.gradle" | head -1)"
VERSION_NAME="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$SOLO/build.gradle" | head -1)"

if [ $# -eq 0 ]; then set -- omc teams omc outlook craftww teams craftww outlook; fi
mkdir -p "$OUT_ALL"

# Shared, variant-independent steps: compile the base resources and the packager once.
WORK="$OUT_ALL/work"
rm -rf "$WORK"
mkdir -p "$WORK/tools"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$WORK/base.zip"
javac -proc:none -encoding UTF-8 -cp "$R8_JAR" -d "$WORK/tools" "$ROOT/buildtools/ApkPackager.java"

while [ $# -ge 2 ]; do
  ACCOUNT="$1"; SITE="$2"; shift 2
  VARIANT="${ACCOUNT}${SITE^}"            # e.g. omcTeams (as Gradle names the flavor combination)
  PKG="com.workdeck.$ACCOUNT.$SITE"
  NAME="${ACCOUNT^^}-${SITE^}-$VERSION_NAME"
  V="$WORK/$VARIANT"
  mkdir -p "$V/gen" "$V/classes" "$V/dex"
  echo "== $NAME ($PKG)"

  # Resources: WorkDeck's, then the app's, the account's and the combination's overrides.
  OVERLAYS=()
  for d in "$SOLO/src/$SITE/res" "$SOLO/src/$ACCOUNT/res" "$SOLO/src/$VARIANT/res"; do
    if [ -d "$d" ]; then
      z="$V/overlay-$(basename "$(dirname "$d")").zip"
      "$AAPT2" compile --dir "$d" -o "$z"
      OVERLAYS+=(-R "$z")
    fi
  done
  sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.workdeck.app\">#" \
      "$SOLO/src/main/AndroidManifest.xml" > "$V/AndroidManifest.xml"
  "$AAPT2" link -I "$ANDROID_JAR" --manifest "$V/AndroidManifest.xml" -A "$APP/src/main/assets" \
      --rename-manifest-package "$PKG" \
      --java "$V/gen" --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
      --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -o "$V/resources.apk" \
      "$WORK/base.zip" "${OVERLAYS[@]}"

  find "$APP/src/main/java" "$V/gen" -name '*.java' > "$V/sources.txt"
  javac -encoding UTF-8 -Xlint:-options -source 8 -target 8 \
      -bootclasspath "$LAMBDA_STUBS:$ANDROID_JAR" -d "$V/classes" @"$V/sources.txt"
  find "$V/classes" -name '*.class' > "$V/classes.txt"
  java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
      --output "$V/dex" @"$V/classes.txt"
  java -cp "$R8_JAR:$WORK/tools" ApkPackager "$V/resources.apk" "$V/dex" "$OUT_ALL/$NAME.apk" "$KEYSTORE" "$KEY_ALIAS" "$MIN_SDK"
  echo "== done: $OUT_ALL/$NAME.apk"
done
