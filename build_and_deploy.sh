#!/bin/bash
set -e

PROJ="/Users/dexter/.gemini/antigravity/scratch/rust_headunit"
export JAVA_HOME="/Applications/Utilities/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:/Users/dexter/Library/Android/sdk/build-tools/36.0.0:$PATH"
export ANDROID_JAR="/Users/dexter/Library/Android/sdk/platforms/android-37.0/android.jar"
export ANDROID_NDK_HOME="/Users/dexter/Library/Android/sdk/ndk/26.3.11579264"
export TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
export CC_armv7_linux_androideabi="$TOOLCHAIN/bin/armv7a-linux-androideabi30-clang"
export AR_armv7_linux_androideabi="$TOOLCHAIN/bin/llvm-ar"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$CC_armv7_linux_androideabi"

echo "=== [1/5] Compiling Rust Core for armv7-linux-androideabi (Release) ==="
cd "$PROJ/rust"
cargo build --target armv7-linux-androideabi --release

mkdir -p "$PROJ/build/lib/armeabi-v7a"
cp "$PROJ/rust/target/armv7-linux-androideabi/release/libheadunit_core.so" "$PROJ/build/lib/armeabi-v7a/"

echo "=== [2/5] Compiling Java Sources (Target Java 17) ==="
rm -rf "$PROJ/build/classes" "$PROJ/build/classes.dex" "$PROJ/build/*.apk"
mkdir -p "$PROJ/build/classes"
javac --release 17 -cp "$ANDROID_JAR" -d "$PROJ/build/classes" "$PROJ"/src/com/openheadunit/rust/*.java

echo "=== [3/5] Dexing Bytecode with D8 ==="
d8 --lib "$ANDROID_JAR" --output "$PROJ/build" "$PROJ"/build/classes/com/openheadunit/rust/*.class

echo "=== [4/5] Packaging & Signing APK ==="
cd "$PROJ"
aapt2 compile --dir "$PROJ/res" -o "$PROJ/build/res.zip"
aapt2 link -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  "$PROJ/build/res.zip" \
  -o "$PROJ/build/unaligned.apk"

cd "$PROJ/build"
zip -u unaligned.apk classes.dex
zip -u -r unaligned.apk lib/

zipalign -f -p 4 unaligned.apk aligned.apk
apksigner sign --ks "$PROJ/debug.keystore" --ks-pass pass:android --out "$PROJ/rust_headunit.apk" aligned.apk

echo "=== [5/5] Deploying to Oppo A37 (9da08b05) ==="
adb -s 9da08b05 install -r "$PROJ/rust_headunit.apk"

# Pre-grant location permissions for Wi-Fi Direct on Android 11
adb -s 9da08b05 shell "pm grant com.openheadunit.rust android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true"
adb -s 9da08b05 shell "pm grant com.openheadunit.rust android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true"
adb -s 9da08b05 shell "pm grant com.openheadunit.rust android.permission.RECORD_AUDIO 2>/dev/null || true"

echo "=== Launching com.openheadunit.rust ==="
adb -s 9da08b05 shell "am force-stop com.openheadunit.rust"
adb -s 9da08b05 shell "am start -n com.openheadunit.rust/.MainActivity"

echo "=== SUCCESS: Rust Headunit Deployed and Running! ==="
