#!/usr/bin/env bash
# Builds LaptopRemote.apk from android/ without Gradle or Android Studio.
#
# Needs (Debian/Ubuntu):  sudo apt install default-jdk-headless aapt zipalign apksigner dalvik-exchange
# Plus an Android framework jar. Either set ANDROID_JAR=/path/to/android.jar (API 31+, e.g.
# $ANDROID_HOME/platforms/android-34/android.jar), or leave it unset and this script downloads
# the framework jar from Maven Central (checksum verified) into build/cache/.
#
# The APK is signed with android/debug.keystore (the standard public "android" debug key).
# That is fine for installing on your own phone, but it is not a Play Store release key.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
SRC="$ROOT/android"
OUT="$ROOT/build"
APK="$ROOT/LaptopRemote.apk"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing tool: $1  ($2)" >&2; exit 1; }; }
need javac "install a JDK, version 11 or newer"
need aapt "apt install aapt"
need zipalign "apt install zipalign"
need apksigner "apt install apksigner"
need keytool "comes with the JDK"
need curl "needed to fetch the Android framework jar"

DX=""
for c in "$(command -v dx || true)" /usr/lib/android-sdk/build-tools/debian/dx; do
  [ -n "$c" ] && [ -x "$c" ] && { DX="$c"; break; }
done
[ -n "$DX" ] || { echo "Missing tool: dx  (apt install dalvik-exchange)" >&2; exit 1; }

# ---- android.jar ---------------------------------------------------------
if [ -z "${ANDROID_JAR:-}" ]; then
  VER="14-robolectric-10818077"
  URL="https://repo1.maven.org/maven2/org/robolectric/android-all/$VER/android-all-$VER.jar"
  ANDROID_JAR="$OUT/cache/android-all-$VER.jar"
  if [ ! -f "$ANDROID_JAR" ]; then
    mkdir -p "$OUT/cache"
    echo "Downloading Android framework jar (about 130 MB, once)..."
    for attempt in 1 2 3 4 5; do
      for base in https://repo1.maven.org/maven2 https://repo.maven.apache.org/maven2; do
        U="$base/org/robolectric/android-all/$VER/android-all-$VER.jar"
        if curl -fsSL -o "$ANDROID_JAR.part" "$U" && curl -fsSL -o "$ANDROID_JAR.sha1" "$U.sha1"; then
          want="$(tr -d ' \n\r' < "$ANDROID_JAR.sha1" | cut -c1-40)"
          have="$(sha1sum "$ANDROID_JAR.part" | cut -c1-40)"
          if [ "$want" = "$have" ]; then mv "$ANDROID_JAR.part" "$ANDROID_JAR"; break 2; fi
          echo "checksum mismatch, retrying..." >&2
        fi
      done
      sleep $((attempt * 5))
    done
    [ -f "$ANDROID_JAR" ] || { echo "Could not download the framework jar. Set ANDROID_JAR instead." >&2; exit 1; }
  fi
fi
[ -f "$ANDROID_JAR" ] || { echo "ANDROID_JAR not found: $ANDROID_JAR" >&2; exit 1; }

# ---- debug keystore ------------------------------------------------------
KS="$SRC/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "Creating debug keystore..."
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

# ---- build ---------------------------------------------------------------
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/stage"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/stage"

echo "[1/6] resources + manifest"
aapt package -f -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -I "$ANDROID_JAR" \
  -J "$OUT/gen" -F "$OUT/stage/base.apk"

echo "[2/6] compile Java"
javac --release 8 -Xlint:-options -Xlint:deprecation -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$SRC/src" "$OUT/gen" -name '*.java')

echo "[3/6] convert to Dalvik bytecode"
"$DX" --dex --output="$OUT/stage/classes.dex" "$OUT/classes"

echo "[4/6] add code to the APK"
(cd "$OUT/stage" && aapt add base.apk classes.dex >/dev/null)

echo "[5/6] align"
zipalign -f -p 4 "$OUT/stage/base.apk" "$OUT/stage/aligned.apk"

echo "[6/6] sign"
apksigner sign --v4-signing-enabled false --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$APK" "$OUT/stage/aligned.apk"
apksigner verify "$APK"

echo
echo "Built: $APK ($(du -h "$APK" | cut -f1))"
aapt dump badging "$APK" | grep -E "^(package|sdkVersion|targetSdkVersion|application-label:)" || true
