#!/usr/bin/env bash
# Builds UnoQRemote.apk without Gradle.
# Needs (Ubuntu/Debian): sudo apt install openjdk-17-jdk android-sdk-platform-23 aapt zipalign apksigner dalvik-exchange
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
OUT=build
APK=../UnoQRemote.apk
KEYSTORE=debug.keystore

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=UNO Q Remote Debug" >/dev/null
fi

echo "> resources"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  --min-sdk-version 23 --target-sdk-version 34 -F "$OUT/unsigned.apk"

echo "> compile"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "> dex"
dalvik-exchange --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q unsigned.apk classes.dex)

echo "> align + sign"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/aligned.apk"
apksigner verify "$APK"
echo "Built $(cd .. && pwd)/UnoQRemote.apk"
