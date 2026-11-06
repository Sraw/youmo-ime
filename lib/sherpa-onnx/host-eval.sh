#!/usr/bin/env bash
# sherpa-onnx-offline and sherpa-onnx (streaming), our sherpa-onnx built for this machine: to
# evaluate the recognizers off the device, on the same code as the app (their JSON output carries
# the n-best). CMake as the Android
# SDK ships it; onnxruntime for Linux downloaded by upstream's CMake, SHA-256 checked, never one
# installed on this machine.
# Usage: lib/sherpa-onnx/host-eval.sh; then build/host-eval/bin/sherpa-onnx-offline (or sherpa-onnx) --help, on a few
# files at a time: it decodes all the files it is given at once, or it runs out of memory
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cmake_bin=$(ls -d "${ANDROID_HOME:-$HOME/android_sdk}"/cmake/*/bin | sort -V | tail -1)
export PATH=$cmake_bin:$PATH
work=$here/build/host-eval
cmake -G Ninja -S "$here/src/main/cpp" -B "$work" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=ON -DSHERPA_ONNX_ENABLE_CHECK=OFF -DSHERPA_ONNX_ENABLE_JNI=OFF >/dev/null
cpus=$(getconf _NPROCESSORS_ONLN)  # nproc is GNU only, not on macOS
cmake --build "$work" --target sherpa-onnx-offline sherpa-onnx -j"$(( cpus > 1 ? cpus / 2 : 1 ))"
