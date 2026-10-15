#!/usr/bin/env bash
# A USB keyboard for the Android emulator, for uat/17-hardware-keyboard.md.
#
# Emulator only: it is a KVM guest with its own kernel. Never on redroid, whose container shares the
# host's kernel: a uinput keyboard made there is the host's keyboard too, and its keys reach the
# host's console (Ctrl+Alt+Del would reboot it).
#
# The emulator's own keyboard (qwerty2) is no stand-in: it is an ALPHA keyboard (sticky Shift, keys
# with no characters for an input method), which looked like bugs that a real keyboard does not have.
#
#   export ANDROID_SERIAL=emulator-5554
#   uat/tools/emu-keyboard.sh up            # adb root, register "Test USB Keyboard"
#   uat/tools/emu-keyboard.sh type ni hao   # letters, digits and spaces, key by key
#   uat/tools/emu-keyboard.sh key SHIFT     # press and release keys in turn
#   uat/tools/emu-keyboard.sh combo CTRL SPACE   # hold the first ones, press the last
#   uat/tools/emu-keyboard.sh down          # unplug it
set -euo pipefail

declare -A C=(
  [ESC]=1 [1]=2 [2]=3 [3]=4 [4]=5 [5]=6 [6]=7 [7]=8 [8]=9 [9]=10 [0]=11 [MINUS]=12 [EQUAL]=13
  [BACKSPACE]=14 [TAB]=15 [Q]=16 [W]=17 [E]=18 [R]=19 [T]=20 [Y]=21 [U]=22 [I]=23 [O]=24 [P]=25
  [LBRACE]=26 [RBRACE]=27 [ENTER]=28 [CTRL]=29 [A]=30 [S]=31 [D]=32 [F]=33 [G]=34 [H]=35 [J]=36
  [K]=37 [L]=38 [SEMI]=39 [APOS]=40 [GRAVE]=41 [SHIFT]=42 [BSLASH]=43 [Z]=44 [X]=45 [C]=46 [V]=47
  [B]=48 [N]=49 [M]=50 [COMMA]=51 [DOT]=52 [SLASH]=53 [RSHIFT]=54 [ALT]=56 [SPACE]=57 [CAPS]=58
  [RCTRL]=97 [RALT]=100 [HOME]=102 [UP]=103 [PGUP]=104 [LEFT]=105 [RIGHT]=106 [END]=107 [DOWN]=108
  [PGDN]=109 [PAGEUP]=104 [PAGEDOWN]=109 [DEL]=111 [META]=125
)

device() {
  adb shell dumpsys input | grep -A3 'Test USB Keyboard' | grep -o '/dev/input/event[0-9]*' | head -1 || true
}

ev() { echo "sendevent $DEV 1 $1 $2; sendevent $DEV 0 0 0;"; }

# fails here rather than in the $(ev ...) subshell, where exit would only end the subshell
check() { for k in "$@"; do [[ -n "${C[$k]:-}" ]] || { echo "unknown key: $k (names are in the table above)" >&2; exit 1; }; done; }

case "${1:-}" in
up)
  [[ "$(adb shell getprop ro.kernel.qemu)" == 1 ]] || { echo "not an emulator: refusing" >&2; exit 1; }
  adb root >/dev/null; adb wait-for-device
  keys=$(seq -s, 1 255)
  adb shell "echo '{\"id\":1,\"command\":\"register\",\"name\":\"Test USB Keyboard\",\"vid\":4660,\"pid\":22136,\"bus\":\"usb\",\"configuration\":[{\"type\":100,\"data\":[1]},{\"type\":101,\"data\":[$keys]}]}' > /data/local/tmp/kbfifo.log"
  adb shell "nohup sh -c 'tail -n +1 -f /data/local/tmp/kbfifo.log | uinput -' >/data/local/tmp/uinput.out 2>&1 &"
  sleep 2
  device
  ;;
down)
  adb shell "pkill -x uinput; pkill -f 'kbfifo[.]log'; rm -f /data/local/tmp/kbfifo.log" || true
  ;;
type|key|combo)
  DEV=$(device); [[ -n "$DEV" ]] || { echo "no keyboard: run up first" >&2; exit 1; }
  what=$1; shift; s=""
  case $what in
  type)
    text="$*"
    for ((i = 0; i < ${#text}; i++)); do
      ch=${text:i:1}
      if [[ "$ch" == " " ]]; then k=SPACE; else k=${ch^^}; fi
      check "$k"
      s+="$(ev "${C[$k]}" 1) $(ev "${C[$k]}" 0) sleep 0.08;"
    done ;;
  key)
    check "$@"
    for k in "$@"; do s+="$(ev "${C[$k]}" 1) $(ev "${C[$k]}" 0) sleep 0.12;"; done ;;
  combo)
    (( $# >= 2 )) || { echo "combo: modifiers then a key, e.g. combo CTRL SPACE" >&2; exit 1; }
    check "$@"
    mods=("${@:1:$#-1}"); last=${!#}
    for m in "${mods[@]}"; do s+="$(ev "${C[$m]}" 1)"; done
    s+="$(ev "${C[$last]}" 1) $(ev "${C[$last]}" 0)"
    for ((i = ${#mods[@]} - 1; i >= 0; i--)); do s+="$(ev "${C[${mods[i]}]}" 0)"; done ;;
  esac
  adb shell "$s"
  ;;
*)
  sed -n '2,17p' "$0"; exit 1 ;;
esac
