#!/usr/bin/env bash
# Runs an evaluation set through the engine the debug APK ships (the own engine, since libime
# left the app) on a connected device or emulator, via EngineEvalRunner, and pulls the result, ready for
#   ./gradlew :lib:ime-eval:run --args="score data/<set>.tsv <result.tsv> [baseline/<...>.tsv]"
# Needs an APK build with the NDK (it installs the debug APK); INSTALL=0 runs on the APKs installed by a
# run before, for several sets in a row.
#   lib/ime-eval/run-on-device.sh [set=pinyin] [ime=engine-pinyin] [out-dir=build/eval]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SET=${1:-pinyin}
IME=${2:-engine-pinyin}
PKG=${FCITX_PKG:-io.github.sraw.youmo.debug}
OUT=${3:-$HERE/build/eval}

cd "$REPO"
[ "${INSTALL:-1}" = 0 ] || ./gradlew :app:installTextDebug :app:installTextDebugAndroidTest --offline -q
# the runner starts its own engine in the app's process; if fcitx were the active keyboard, its
# service would hold a second engine there, on the same data files. So another enabled keyboard
# takes over for the run, and the user's is put back after it
keyboard() { adb shell settings get secure default_input_method | tr -d '\r'; }
KEYBOARD=$(keyboard)
if [[ $KEYBOARD == "$PKG/"* ]]; then
    restore() {
        local status=$?
        adb shell ime set "$KEYBOARD" >/dev/null 2>&1 || true
        # by what is set, not by adb's status: adb shell passes on no exit status before Android 7.0
        [[ $(keyboard) == "$KEYBOARD" ]] || echo "the keyboard was not put back: adb shell ime set $KEYBOARD" >&2
        exit "$status"
    }
    trap restore EXIT
    OTHER=$(adb shell ime list -s | tr -d '\r' | grep -v -m 1 "^$PKG/" || true)
    [ -z "$OTHER" ] || adb shell ime set "$OTHER" >/dev/null 2>&1 || true
    if [[ $(keyboard) == "$PKG/"* ]]; then
        echo "$PKG is still the keyboard: enable another (adb shell ime enable <id>) and run again" >&2
        exit 1
    fi
fi
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
