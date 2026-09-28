#!/usr/bin/env bash
# Downloads the offline speech engine and the Lessac voice into the project.
# GitHub runs this automatically; run it yourself only if you build in Android Studio.
set -euo pipefail
SHERPA=1.13.8
mkdir -p app/libs app/src/main/assets/piper
curl -fsSL -o app/libs/sherpa-onnx-$SHERPA.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA/sherpa-onnx-$SHERPA.aar
curl -fsSL https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2 | tar xj
rm -rf app/src/main/assets/piper/espeak-ng-data
mv vits-piper-en_US-lessac-medium/en_US-lessac-medium.onnx app/src/main/assets/piper/model.onnx
mv vits-piper-en_US-lessac-medium/tokens.txt app/src/main/assets/piper/tokens.txt
mv vits-piper-en_US-lessac-medium/espeak-ng-data app/src/main/assets/piper/espeak-ng-data
echo "Lessac" > app/src/main/assets/piper/voice.txt
rm -rf vits-piper-en_US-lessac-medium
echo "Voice ready."
