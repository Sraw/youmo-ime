#!/bin/bash
# Makes (again) the AVD youmo the emulator scripts boot, in ~/.android/avd (local disk: an AVD on NFS
# is slow and the server's old dev/avd was x86_64), and its snapshot "clean":
# android-36 google_apis arm64-v8a, Pixel 5's 1080x2340 at 440 dpi, 3 GB RAM, no hardware keyboard
# (17 实体键盘 plugs uat/tools/emu-keyboard.sh's), three-button nav as on redroid (the scripts'
# coordinates), zh-CN, screen always on, no lock screen, the WebView shell (uat's test page browser).
# Takes ~1 min. Every uat.py up then boots that snapshot read-only. Needs the SDK's cmdline-tools,
# emulator and the system image: sdkmanager "system-images;android-36;google_apis;arm64-v8a" emulator
# (youmo29, below: "system-images;android-29;google_apis;arm64-v8a", and no WebView shell, which needs 34)
set -e
export ADB_MDNS=0
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
HERE=$(cd "$(dirname "$0")" && pwd)
LOG=$HERE/../../dev/uat-out/emu
mkdir -p "$LOG"
export ANDROID_SERIAL=emulator-5554
# UAT_AVD/UAT_API: another AVD beside youmo, e.g. UAT_AVD=youmo29 UAT_API=29 (Android 10, minSdk: it must keep
# working there), run then with UAT_AVD=youmo29 uat.py ...
AVD=${UAT_AVD:-youmo}
# another AVD's level said, not youmo's 36 taken under its name
if [ "$AVD" != youmo ] && [ -z "$UAT_API" ]; then echo "UAT_AVD=${AVD}：也设 UAT_API（它的 API 级别）"; exit 1; fi
API=${UAT_API:-36}
booted() { [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
wait_boot() { for i in $(seq 180); do booted && return; sleep 1; done; echo "没开机：$LOG/emulator-avd.log"; exit 1; }
# --force below deletes the AVD: not under a running instance of it (any port, read-only ones too)
for s in $(adb devices | awk '/^emulator-/ {print $1}'); do
  [ "$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] && { echo "$s 开着 ${AVD}：先 UAT_AVD=$AVD uat/tools/uat.py down --port ${s#emulator-}"; exit 1; }
done
if adb get-state >/dev/null 2>&1; then echo "emulator-5554 被别的模拟器占着"; exit 1; fi

echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" --force \
  -k "system-images;android-$API;google_apis;arm64-v8a" -d pixel_5 >/dev/null
C=$HOME/.android/avd/$AVD.avd/config.ini
sed -i '' -e 's/^hw.ramSize=.*/hw.ramSize=3072/' -e 's/^showDeviceFrame=.*/showDeviceFrame=no/' \
  -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' \
  -e 's/^hw.keyboard=.*/hw.keyboard=no/' "$C"

# the same flags as uat.py up's (a snapshot loads only under the GPU mode and devices it was saved with)
# and the offline-renderer shim uat.py's emulator_env loads for -gpu host with no display (without it
# an older image's surfaceflinger dies in GLESRenderEngine::create and the AVD never boots)
GPU=${EMU_GPU:-host}
SHIM=
if [ "$GPU" = host ] && [ "$(uname)" = Darwin ]; then
  SHIM=$HOME/.cache/youmo/liboffline-gl.dylib
  if [ ! -f "$SHIM" ] || [ "$HERE/offline-gl.m" -nt "$SHIM" ]; then
    mkdir -p "$(dirname "$SHIM")"
    clang -dynamiclib -arch arm64 -o "$SHIM.$$" "$HERE/offline-gl.m" -framework AppKit -framework OpenGL
    codesign -s - -f "$SHIM.$$" >/dev/null 2>&1; mv "$SHIM.$$" "$SHIM"
  fi
fi
DYLD_INSERT_LIBRARIES=$SHIM "$SDK/emulator/emulator" -avd "$AVD" -port 5554 -no-snapshot-load -no-window -no-audio -no-boot-anim \
  -gpu "$GPU" >"$LOG/emulator-avd.log" 2>&1 </dev/null &
wait_boot
adb root >/dev/null; adb wait-for-device; wait_boot
adb shell 'cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
  settings put system screen_off_timeout 2147483647; svc power stayon true; locksettings set-disabled true
  setprop persist.sys.locale zh-CN; setprop sys.boot_completed 0; stop; start' >/dev/null
sleep 5; wait_boot; sleep 3
# AOSP's WebView shell (webview_shell.apk beside this script, from the redroid image's AOSP build;
# pure Java: the same APK on arm64)
# (its minSdk is 34: an older image goes without, and the cases on the test page cannot run there)
if [ "$API" -ge 34 ]; then adb install -r "$HERE/webview_shell.apk" >/dev/null; else echo "API ${API}：没装 WebView shell（要 34+），测试页的用例在这台上跑不了"; fi
adb shell 'input keyevent WAKEUP; input keyevent HOME'; sleep 2
adb emu avd snapshot save clean >/dev/null
adb emu kill >/dev/null
echo "AVD $AVD 和快照 clean 做好了（${C}）"
