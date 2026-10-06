#!/usr/bin/env bash
# sherpa-onnx-offline, our sherpa-onnx built for this machine: to evaluate the recognizer off the
# device, on the same code as the app (its JSON output carries the n-best). CMake as the Android
# SDK ships it; onnxruntime for Linux downloaded by upstream's CMake, SHA-256 checked.
# Usage: lib/sherpa-onnx/host-eval.sh; then build/host-eval/bin/sherpa-onnx-offline --help
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cmake_bin=$(ls -d "${ANDROID_HOME:-$HOME/android_sdk}"/cmake/*/bin | sort -V | tail -1)
export PATH=$cmake_bin:$PATH
work=$here/build/host-eval
cmake -G Ninja -S "$here/src/main/cpp" -B "$work" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DSHERPA_ONNX_ENABLE_BINARY=ON -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_CHECK=OFF \
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF -DSHERPA_ONNX_ENABLE_JNI=OFF \
  -DSHERPA_ONNX_ENABLE_C_API=OFF >/dev/null
# it decodes all the files it is given at once: a few at a time, or it runs out of memory
cmake --build "$work" --target sherpa-onnx-offline -j"$(( $(nproc) / 2 ))"
