#!/bin/bash
set -e

# Virtu VPN Build Script
KEYSTORE="virtu-vpn.keystore"
ALIAS="virtuvpn"
BUILD_TOOLS="/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0"
RELEASE_DIR="ui/build/outputs/apk/release"
DEBUG_DIR="ui/build/outputs/apk/debug"
OUTPUT="virtu-vpn.apk"

echo -n "🔑 Keystore password: "
read -s KS_PASS
echo ""

echo "🔨 Building release APK..."
./gradlew clean assembleRelease assembleDebug

echo "📐 Zipaligning..."
$BUILD_TOOLS/zipalign -v 4 $RELEASE_DIR/ui-release-unsigned.apk $RELEASE_DIR/virtu-vpn-aligned.apk

echo "🔑 Signing APK..."
$BUILD_TOOLS/apksigner sign --ks $KEYSTORE --ks-key-alias $ALIAS --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out $RELEASE_DIR/$OUTPUT $RELEASE_DIR/virtu-vpn-aligned.apk

echo "📦 Copying to server directory..."
cp $RELEASE_DIR/$OUTPUT $DEBUG_DIR/$OUTPUT

echo ""
echo "✅ Done! APK ready at:"
echo "   $RELEASE_DIR/$OUTPUT"
echo "   $DEBUG_DIR/$OUTPUT (server)"
echo ""
echo "📱 Download from: http://192.168.1.252:8080/$OUTPUT"
