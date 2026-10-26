#!/bin/bash
# Builds OpenCC ver.1.2.0 from its source tarball (SHA-256 pinned; not the vendored tree, which is
# deleted before the Kotlin host ships), with its dictionaries compiled from that release's own
# data/dictionary/*.txt. The result is the reference that golden.py runs.
#   lib/ime-eval/tools/opencc/build.sh <work dir>
# Leaves the tool at <work dir>/install/bin/opencc (printed) and the .ocd2/.json under
# <work dir>/install/share/opencc. Needs cmake, make, a C++17 compiler, python3.
set -euo pipefail
# cmake: the one on PATH, else the Android SDK's (this machine has no system cmake)
CMAKE=$(command -v cmake || ls -d "${ANDROID_HOME:-$HOME/android_sdk}"/cmake/*/bin/cmake 2>/dev/null | tail -1)
W=$(realpath -m "${1:?work dir}")
URL=https://github.com/BYVoid/OpenCC/archive/refs/tags/ver.1.2.0.tar.gz
SHA=f4f86eb25e239450d075081e08594801aa063c298d21d9f6c6aa85cd55241962
mkdir -p "$W"
T="$W/OpenCC-ver.1.2.0.tar.gz"
# into .part, then moved: an interrupted download must not stay behind as the tarball, failing every run after
[ -f "$T" ] || { curl -sfL -o "$T.part" "$URL" && mv "$T.part" "$T"; }
echo "$SHA  $T" | sha256sum -c --quiet
# likewise the source tree: unpacked beside it, then moved
if [ ! -d "$W/OpenCC-ver.1.2.0" ]; then
  rm -rf "$W/unpack" && mkdir "$W/unpack"
  tar -xzf "$T" -C "$W/unpack"
  mv "$W/unpack/OpenCC-ver.1.2.0" "$W/OpenCC-ver.1.2.0" && rmdir "$W/unpack"
fi
"$CMAKE" -S "$W/OpenCC-ver.1.2.0" -B "$W/OpenCC-ver.1.2.0/build" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$W/install" -DBUILD_SHARED_LIBS=OFF >"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
"$CMAKE" --build "$W/OpenCC-ver.1.2.0/build" --target install -j"$(nproc)" >>"$W/build.log" 2>&1 || { tail -30 "$W/build.log"; exit 1; }
ls "$W/install/share/opencc"/*.json >/dev/null
echo "$W/install/bin/opencc"
