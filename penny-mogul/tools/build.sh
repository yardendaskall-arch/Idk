#!/usr/bin/env bash
# Builds a signed, installable APK without the Android SDK / Gradle.
#
#   web/ (HTML+JS game)  ->  assets/www
#   android/java         ->  javac -> dx -> classes.dex
#   android/res + manifest -> aapt
#   zipalign + apksigner (v1+v2+v3)
#
# Needs (Debian/Ubuntu):  sudo apt-get install aapt apksigner zipalign \
#                              dalvik-exchange android-sdk-platform-23 openjdk-17-jdk-headless
# Output: dist/PennyMogul.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/build"
DIST="$ROOT/dist"

ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
DX="${DX:-/usr/lib/android-sdk/build-tools/debian/dx}"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-pennymogul}"

VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
MIN_SDK=24
TARGET_SDK=34

for t in javac aapt zipalign apksigner keytool; do
  command -v "$t" >/dev/null || { echo "missing tool: $t (see header of this script)"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR"; exit 1; }
[ -x "$DX" ] || { echo "missing $DX"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/assets" "$DIST"

echo "==> assets"
cp -r "$ROOT/web" "$BUILD/assets/www"
find "$BUILD/assets/www" -name '*.map' -delete

echo "==> javac"
javac -source 8 -target 8 -Xlint:-options -Xlint:deprecation \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" \
  $(find "$ROOT/android/java" -name '*.java')

echo "==> dx"
"$DX" --dex --min-sdk-version=$MIN_SDK --output="$BUILD/classes.dex" "$BUILD/classes"

echo "==> aapt"
# -0 arsc: keep resources.arsc stored (required for targetSdk >= 30)
aapt package -f \
  -M "$ROOT/android/AndroidManifest.xml" \
  -S "$ROOT/android/res" \
  -A "$BUILD/assets" \
  -I "$ANDROID_JAR" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -0 arsc \
  -F "$BUILD/unaligned.apk"
(cd "$BUILD" && aapt add -f unaligned.apk classes.dex >/dev/null)

echo "==> zipalign"
zipalign -f -p 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"
zipalign -c -p 4 "$BUILD/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "==> creating debug keystore at $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 36500 \
    -dname "CN=Penny Mogul Debug, O=Penny Mogul, C=US"
fi

echo "==> apksigner"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --min-sdk-version $MIN_SDK \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$DIST/PennyMogul.apk" "$BUILD/aligned.apk"
rm -f "$DIST/PennyMogul.apk.idsig"

echo "==> verify"
apksigner verify --verbose --min-sdk-version $MIN_SDK "$DIST/PennyMogul.apk" | sed 's/^/    /'
aapt dump badging "$DIST/PennyMogul.apk" | grep -E "^(package|sdkVersion|targetSdkVersion|application-label:|launchable)" | sed 's/^/    /'
ls -la "$DIST/PennyMogul.apk"
