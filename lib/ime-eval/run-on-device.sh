#!/usr/bin/env bash
# Runs an evaluation set through the engine the debug APK ships (the own engine, since libime
# left the app) on a connected device or emulator, via EngineEvalRunner, and pulls the result, ready for
#   ./gradlew :lib:ime-eval:run --args="score data/<set>.tsv <result.tsv> [baseline/<...>.tsv]"
# Needs the native build (it installs the debug APK).
#   lib/ime-eval/run-on-device.sh [set=pinyin] [ime=pinyin] [out-dir=build/eval]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SET=${1:-pinyin}
IME=${2:-pinyin}
PKG=${FCITX_PKG:-io.github.sraw.youmo.debug}
OUT=${3:-$HERE/build/eval}

cd "$REPO"
./gradlew :app:installOfflineTextDebug :app:installOfflineTextDebugAndroidTest --offline -q
# the runner starts its own engine in the app's process; if fcitx were the active keyboard, its
# service would hold a second engine there, on the same data files
adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME >/dev/null 2>&1 || true
REMOTE="/sdcard/Android/data/$PKG/files/eval/$SET-$IME.tsv"
# a failed run must not leave the previous result behind to be pulled as this one
adb shell rm -f "$REMOTE"
# am instrument exits 0 even when the run fails; its report is what tells
REPORT=$(adb shell am instrument -w -e class org.fcitx.fcitx5.android.EngineEvalRunner \
    -e evalSet "$SET" -e evalIme "$IME" "$PKG.test/androidx.test.runner.AndroidJUnitRunner")
if ! grep -q "^OK (1 test)" <<<"$REPORT"; then
    echo "$REPORT" >&2
    exit 1
fi
mkdir -p "$OUT"
adb pull "$REMOTE" "$OUT/" >/dev/null
echo "$OUT/$SET-$IME.tsv"
