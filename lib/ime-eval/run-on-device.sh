#!/usr/bin/env bash
# Runs an evaluation set through the engine the debug APK ships (the own engine, since libime
# left the app) on a connected emulator, via EngineEvalRunner, and pulls the result, ready for
#   ./gradlew :lib:ime-eval:run --args="score data/<set>.tsv <result.tsv> [baseline/<...>.tsv]"
# Needs an emulator (uat.py up; uat.py packs installs the debug APK and imports the text pack); INSTALL=0 runs
# on the APKs installed by a run before, for several sets in a row.
#   lib/ime-eval/run-on-device.sh [set=pinyin] [ime=engine-pinyin] [out-dir=build/eval]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SET=${1:-pinyin}
IME=${2:-engine-pinyin}
PKG=${FCITX_PKG:-io.github.sraw.youmo.debug}
OUT=${3:-$HERE/build/eval}

cd "$REPO"
# the runner starts its own engine in the app's process; if fcitx were the active keyboard, its
# service would hold a second engine there, on the same data files. So another enabled keyboard
# takes over for the run, and the user's is put back after it: the one before the install, which
# makes this one the keyboard (uat.py packs: ime set)
keyboard() { adb shell settings get secure default_input_method | tr -d '\r'; }
KEYBOARD=$(keyboard)
restore() {
    local status=$?
    # none chosen before (a new emulator answers "null"): nothing to put back. Else set before the install,
    # which makes this one the keyboard first: an install or an import that fails after it put back too
    if [[ -n $KEYBOARD && $KEYBOARD != null && $(keyboard) != "$KEYBOARD" ]]; then
        adb shell ime set "$KEYBOARD" >/dev/null 2>&1 || true
        # by what is set, not by adb's status: adb shell passes on no exit status before Android 7.0
        [[ $(keyboard) == "$KEYBOARD" ]] || echo "the keyboard was not put back: adb shell ime set $KEYBOARD" >&2
    fi
    exit "$status"
}
trap restore EXIT
if [ "${INSTALL:-1}" != 0 ]; then
    ./gradlew :app:assembleDebug :app:installDebugAndroidTest :app:packTextPack --offline -q
    # the APK and the text pack as a UAT run has them: uat.py installs the debug APK and imports the pack,
    # skipping one already there (the same version and the same file); one importer, one rule for "there"
    serial=$(adb get-serialno | tr -d '\r')
    [[ $serial == emulator-* ]] || { echo "$serial: uat.py packs takes an emulator; on a phone import the text pack in Settings" >&2; exit 1; }
    python3 "$REPO/uat/tools/uat.py" packs --port "${serial#emulator-}" --no-voice
fi
if [[ $(keyboard) == "$PKG/"* ]]; then
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
