#!/usr/bin/env bash
# The C++ unit tests of what we changed in sherpa-onnx, run on this machine rather than a phone:
# googletest (as upstream's cmake/googletest.cmake pins it, SHA-256 checked) and the few sources
# they need, compiled with the host's g++. Usage: lib/sherpa-onnx/host-test.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
src=$here/src/main/cpp
work=$here/build/host-test
mkdir -p "$work"
cd "$work"

gtest=googletest-1.13.0
if [ ! -d $gtest ]; then
  curl -sSL -o $gtest.tar.gz https://github.com/google/googletest/archive/refs/tags/v1.13.0.tar.gz
  echo "ad7fdba11ea011c1d925b3289cf4af2c66a352e18d4c7264392fead75e919363  $gtest.tar.gz" | sha256sum -c --quiet
  tar xzf $gtest.tar.gz
fi
[ -f libgtest.a ] || {
  g++ -std=c++17 -O1 -c -I $gtest/googletest/include -I $gtest/googletest $gtest/googletest/src/gtest-all.cc -o gtest-all.o
  g++ -std=c++17 -O1 -c -I $gtest/googletest/include $gtest/googletest/src/gtest_main.cc -o gtest_main.o
  ar rcs libgtest.a gtest-all.o gtest_main.o
}

csrc=$src/sherpa-onnx/csrc
g++ -std=c++17 -O1 -I "$src" -I $gtest/googletest/include \
  "$csrc/context-graph-test.cc" "$csrc/context-graph.cc" "$csrc/log.cc" \
  libgtest.a -lpthread -o context-graph-test
./context-graph-test --gtest_filter='-ContextGraph.Benchmark'
