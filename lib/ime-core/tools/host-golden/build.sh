#!/bin/sh
# Builds the two golden harnesses from upstream fcitx5 at 442edbc9 (../upstream.sh) into $1.
# fcitx's own sources are compiled unchanged; shim/ stands in for the parts of fcitx-utils that
# need its build (StandardPaths, file descriptors, the export macros).
set -eu
out=$1
here=$(cd "$(dirname "$0")" && pwd)
src=$("$here/../upstream.sh" fcitx5)/src
utils="$src/lib/fcitx-utils/stringutils.cpp $src/lib/fcitx-utils/utf8.cpp $src/lib/fcitx-utils/cutf8.cpp"
flags="-std=c++20 -O1 -I$here/shim -I$src/lib -I$src/modules/unicode -DFCITX_GETTEXT_DOMAIN=\"fcitx5\""
mkdir -p "$out"
g++ $flags -o "$out/charselect-golden" "$here/charselect-golden.cpp" "$src/modules/unicode/charselectdata.cpp" $utils
sed -n '29,104p' "$src/modules/quickphrase/quickphraseprovider.cpp" > "$out/provider-body.inc"
g++ $flags -I"$out" -o "$out/quickphrase-golden" "$here/quickphrase-golden.cpp" $utils
