#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
if [ ! -f build/debug.keystore ]; then
    keytool -genkeypair -keystore build/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=CarrierSIM Development' -keyalg RSA -keysize 2048 -validity 10000
fi
./gradlew --no-daemon :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk build/CarrierSIM-Android-debug.apk
