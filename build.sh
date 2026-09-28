#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

GRADLE="${GRADLE_BIN:-../tools/gradle-9.5.0/bin/gradle}"
export ANDROID_HOME="${ANDROID_HOME:-$(cd ../sdk && pwd)}"
"$GRADLE" --no-daemon assembleRelease "$@"

mkdir -p ../../outputs/驭窗浮控
cp -f build/outputs/apk/release/YuChuangFloatingControl-release.apk \
    ../../outputs/驭窗浮控/驭窗浮控-1.7.15-API37.apk
printf 'APK: %s\n' "$(cd ../../outputs/驭窗浮控 && pwd)/驭窗浮控-1.7.15-API37.apk"
