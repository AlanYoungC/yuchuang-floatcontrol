#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="../sdk/android-37.0"
mkdir -p build/classes build/dex build/generated
"$SDK/aapt2" compile --dir res -o build/resources.zip
"$SDK/aapt2" link -o build/base.apk -I "$SDK/android.jar" --manifest AndroidManifest.xml --java build/generated build/resources.zip
find src build/generated -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 17 -target 17 -classpath "$SDK/android.jar" -d build/classes @build/sources.txt
jar cf build/app.jar -C build/classes .
"$SDK/d8" --min-api 37 --lib "$SDK/android.jar" --output build/dex build/app.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$SDK/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$SDK/apksigner" sign --ks ../experiment-signing.p12 --ks-pass pass:android --key-pass pass:android --out ../../outputs/驭窗浮控/驭窗浮控-1.8.2-API37.apk build/aligned.apk
