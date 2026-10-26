#!/bin/sh
# Builds ./driver <out>: fcitx-config and the parts of fcitx-utils it needs, from upstream fcitx5 at
# 442edbc9 (../upstream.sh), with the native engine's option table pasted in: options.inc, the lines
# of androidengine.h from `namespace fcitx {` to `#undef ENGINE_TABLE_OPTIONS`, kept here since the
# header went with the native sources. Needs only g++ (C++20); the rest of fcitx is left unresolved
# (never called on these paths).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
lib=$("$here/../upstream.sh" fcitx5)/src/lib
out=${1:?usage: build.sh <out dir>}
mkdir -p "$out/inc/fcitx-config" "$out/inc/fcitx-utils"
for f in fcitx-config/fcitxconfig_export.h fcitx-utils/fcitxutils_export.h; do
    printf '#pragma once\n#define FCITXCONFIG_EXPORT\n#define FCITXCONFIG_DEPRECATED_EXPORT\n#define FCITXUTILS_EXPORT\n#define FCITXUTILS_DEPRECATED\n#define FCITXUTILS_DEPRECATED_EXPORT\n' > "$out/inc/$f"
done
printf '#pragma once\n#define FCITX_INSTALL_LOCALEDIR ""\n' > "$out/inc/config.h"
sed "/@OPTIONS@/r $here/options.inc" "$here/driver.cpp" > "$out/driver.cpp"
g++ -std=c++20 -O1 -w -I"$out/inc" -I"$lib" -I"$lib/fcitx-utils" -I"$lib/fcitx-config" \
    "$out/driver.cpp" "$here/i18n-stub.cpp" \
    "$lib"/fcitx-config/configuration.cpp "$lib"/fcitx-config/option.cpp "$lib"/fcitx-config/rawconfig.cpp \
    "$lib"/fcitx-config/iniparser.cpp "$lib"/fcitx-config/marshallfunction.cpp \
    "$lib"/fcitx-utils/stringutils.cpp "$lib"/fcitx-utils/fdstreambuf.cpp \
    -Wl,--unresolved-symbols=ignore-all -o "$out/driver"
