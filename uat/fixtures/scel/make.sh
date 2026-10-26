#!/bin/bash
# Golden outputs of the native scel2org5 (the libscel2org5.so of an installed 幽默输入法) for every
# *.scel here: <name>.txt (what it writes with -o, the file PinyinDictManager.kt imports),
# <name>.out (its stdout and stderr) and <name>.rc (its exit status). It is called as the app calls
# it (PinyinDictManager.kt:370: scel2org5 -o <dest> <src>, no -a).
#   uat/fixtures/scel/make.sh <redroid container> <adb serial>
# Root in the container: on redroid (x86_64) the arm64 build runs under ndk_translation, whose
# linker ignores LD_LIBRARY_PATH and searches only /system/lib64/arm64 (/system/etc/ld.config.arm64.txt),
# so the app's libraries are overlaid there for the run. Use a throwaway container.
set -euo pipefail
C=${1:?container}; export ANDROID_SERIAL=${2:?serial}
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=io.github.sraw.youmo
LIB="$(adb shell pm path $PKG | sed 's/^package://; s|/base.apk||' | tr -d '\r')/lib/arm64"
T=/data/local/tmp/scel
adb shell "rm -rf $T $T-lib; mkdir -p $T $T-lib"
adb push -q "$HERE"/*.scel $T/
adb shell "cp '$LIB'/*.so $T-lib/"
docker exec "$C" sh -c "
  grep -q ' /system/lib64/arm64 overlay' /proc/mounts ||
    mount -t overlay overlay -o lowerdir=$T-lib:/system/lib64/arm64 /system/lib64/arm64
  cd $T
  for f in *.scel; do
    b=\${f%.scel}
    rm -f \$b.txt
    env -i $T-lib/libscel2org5.so -o \$b.txt \$f > \$b.out 2>&1; echo \$? > \$b.rc
  done"
for f in "$HERE"/*.scel; do
  b=$(basename "$f" .scel)
  rm -f "$HERE/$b.txt"
  adb shell "[ -f $T/$b.txt ]" && adb pull -q $T/$b.txt "$HERE/"
  adb pull -q $T/$b.out $T/$b.rc "$HERE/"
  sed -i -E 's/^([EWI])[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]+ /\1 /; s/ \(0x[0-9a-f]+\)$//' "$HERE/$b.out"   # log time, addresses
done
adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ' > "$HERE/VERSION"
