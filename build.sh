#!/usr/bin/env bash
# 离线命令行构建（不依赖 Gradle）：aapt2 → javac → d8 → 打包 → zipalign → apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/ks-build/jdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$HOME/ks-build/dl/bt/android-16}"
PLATFORM="${PLATFORM:-$HOME/ks-build/dl/plat/android-37.2}"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/kslite.jks}"
KS_PASS="${KS_PASS:-kslite2026}"
KS_ALIAS="${KS_ALIAS:-kslite}"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
ANDROID_JAR="$PLATFORM/android.jar"

export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_HOME

VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' src/main/AndroidManifest.xml)
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' src/main/AndroidManifest.xml)
OUT_NAME="KSLiteTimer-${VERSION_NAME}.apk"

SRC="$ROOT/src/main/java"
RES="$ROOT/src/main/res"
META="$ROOT/src/main/resources"
LIBS="$ROOT/libs"
API_JAR="$LIBS/libxposed-api-102.0.0.jar"

BUILD="$ROOT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/res"

echo "==> aapt2 compile"
"$AAPT2" compile --dir "$RES" -o "$BUILD/res" >/dev/null

echo "==> aapt2 link"
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest src/main/AndroidManifest.xml \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 37 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  $("ls" "$BUILD"/res/*.flat) \
  -o "$BUILD/base.apk"

echo "==> javac"
find "$SRC" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 \
  -source 11 -target 11 \
  -classpath "$ANDROID_JAR:$API_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "==> d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" \
  --release \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --classpath "$API_JAR" \
  --output "$BUILD/dex" \
  @"$BUILD/classes.txt" >/dev/null

echo "==> package"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
"$JAVA_HOME/bin/jar" uf "$BUILD/unsigned.apk" -C "$BUILD/dex" classes.dex
"$JAVA_HOME/bin/jar" uf "$BUILD/unsigned.apk" -C "$META" META-INF/xposed/java_init.list
"$JAVA_HOME/bin/jar" uf "$BUILD/unsigned.apk" -C "$META" META-INF/xposed/module.prop
"$JAVA_HOME/bin/jar" uf "$BUILD/unsigned.apk" -C "$META" META-INF/xposed/scope.list

echo "==> zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "==> keytool: 生成自签 keystore"
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=KSLite Timer,O=KSLite,C=CN"
fi

echo "==> apksigner"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --key-pass "pass:$KS_PASS" \
  --min-sdk-version 26 \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$BUILD/$OUT_NAME" "$BUILD/aligned.apk"

"$APKSIGNER" verify --print-certs "$BUILD/$OUT_NAME" | head -3
echo
echo "==> 产物：$BUILD/$OUT_NAME"
ls -l "$BUILD/$OUT_NAME"
