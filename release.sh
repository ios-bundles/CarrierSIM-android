#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f signing.properties ]; then
    echo 'Configure signing.properties using signing.properties.example first.' >&2
    exit 1
fi
./gradlew --no-daemon :app:lintRelease :app:assembleRelease
mkdir -p build/release
cp app/build/outputs/apk/release/app-release.apk build/release/CarrierSIM-Android.apk
(cd build/release && sha256sum CarrierSIM-Android.apk > SHA256SUMS)
echo 'Release APK: build/release/CarrierSIM-Android.apk'
