#!/usr/bin/env bash
# Builds and signs the WorkDeck APK WITHOUT Gradle / Android Studio, using only:
#   ANDROID_JAR   - an API 35 android.jar (platforms/android-35/android.jar)
#   AAPT2         - the aapt2 binary
#   R8_JAR        - a jar containing D8 (com.android.tools.r8.D8) and apksig
#   LAMBDA_STUBS  - core-lambda-stubs.jar (from build-tools)
#   KEYSTORE / KEY_ALIAS / WORKDECK_KS_PASS - PKCS12 signing key
# Android Studio users should simply use Gradle instead (see README).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${AAPT2:?set AAPT2}" "${R8_JAR:?set R8_JAR}" "${LAMBDA_STUBS:?set LAMBDA_STUBS}"
: "${KEYSTORE:?set KEYSTORE}" "${KEY_ALIAS:=workdeck}" "${WORKDECK_KS_PASS:?set WORKDECK_KS_PASS}"
export WORKDECK_KS_PASS

APP="$ROOT/app"
OUT="$ROOT/build/offline"
PKG="com.workdeck.app"
MIN_SDK=26
TARGET_SDK=35
VERSION_CODE="$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$APP/build.gradle" | head -1)"
VERSION_NAME="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$APP/build.gradle" | head -1)"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/tools"

echo "== aapt2: compile + link resources ($VERSION_NAME / $VERSION_CODE)"
sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">#" \
    "$APP/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$OUT/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" -A "$APP/src/main/assets" \
    --java "$OUT/gen" --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -o "$OUT/resources.apk" "$OUT/res.zip"

echo "== javac"
find "$APP/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -Xlint:-options -Xlint:unchecked -source 8 -target 8 \
    -bootclasspath "$LAMBDA_STUBS:$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$OUT/dex" @"$OUT/classes.txt"

echo "== package + sign"
javac -encoding UTF-8 -cp "$R8_JAR" -d "$OUT/tools" "$ROOT/buildtools/ApkPackager.java"
APK="$OUT/WorkDeck-$VERSION_NAME.apk"
java -cp "$R8_JAR:$OUT/tools" ApkPackager "$OUT/resources.apk" "$OUT/dex" "$APK" "$KEYSTORE" "$KEY_ALIAS" "$MIN_SDK"
echo "== done: $APK"
