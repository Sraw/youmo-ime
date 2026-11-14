#!/usr/bin/env bash
# A USB keyboard for the Android emulator, for uat/17-hardware-keyboard.md.
#
# Emulator only: it is a VM guest (KVM, Hypervisor.framework) with its own kernel. Never on redroid, whose container shares the
# host's kernel: a uinput keyboard made there is the host's keyboard too, and its keys reach the
# host's console (Ctrl+Alt+Del would reboot it).
#
# The emulator's own keyboard (qwerty2) is no stand-in: it is an ALPHA keyboard (sticky Shift, keys
# with no characters for an input method), which looked like bugs that a real keyboard does not have.
#
#   export ANDROID_SERIAL=emulator-5554
#   uat/tools/emu-keyboard.sh up            # adb root, register "Test USB Keyboard" (uinput; Android 10, which has none: hid)
#   uat/tools/emu-keyboard.sh type ni hao   # letters, digits, spaces and ; ' , . /, key by key
#   uat/tools/emu-keyboard.sh key SHIFT     # press and release keys in turn
#   uat/tools/emu-keyboard.sh combo CTRL SPACE   # hold the first ones, press the last
#   uat/tools/emu-keyboard.sh down          # unplug it
# declare -A and ${x^^} need bash 4: macOS's /bin/bash is 3.2, and a non-login shell (ssh, launchd)
# may not have Homebrew's first in PATH
if [ "${BASH_VERSINFO[0]}" -lt 4 ]; then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [ -x "$b" ] && exec "$b" "$0" "$@"; done
  echo "emu-keyboard.sh: needs bash 4 or newer (brew install bash)" >&2; exit 1
fi
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

# a USB HID boot keyboard's report descriptor: a byte of modifiers (usages 224-231), one reserved, six keys (0-101)
HID_BOOT_KEYBOARD=5,1,9,6,161,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,129,2,149,1,117,8,129,1,149,6,117,8,21,0,37,101,5,7,25,0,41,101,129,0,192

device() {
  adb shell dumpsys input | grep -A3 'Test USB Keyboard' | grep -o '/dev/input/event[0-9]*' | head -1 || true
}

ev() { echo "sendevent $DEV 1 $1 $2; sendevent $DEV 0 0 0;"; }

prop() { adb shell getprop "$1" | tr -d '\r'; }

# The emulator's board (ranchu, goldfish before it), marked qemu: ro.boot.qemu on newer images,
# ro.kernel.qemu on older ones. redroid is no such board, and on this machine its kernel's boot_id
# is this machine's: refused, as anything else is, since a keyboard made there could be the host's
emulator() {
  [[ "$(prop ro.boot.qemu)" == 1 || "$(prop ro.kernel.qemu)" == 1 ]] || return 1
  [[ "$(prop ro.hardware)" == ranchu || "$(prop ro.hardware)" == goldfish ]] || return 1
  local here there
  there=$(adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
  here=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
  [[ -z "$here" || "$there" != "$here" ]]
}

# fails here rather than in the $(ev ...) subshell, where exit would only end the subshell
check() { for k in "$@"; do [[ -n "${C[$k]:-}" ]] || { echo "unknown key: $k (names are in the table above)" >&2; exit 1; }; done; }

case "${1:-}" in
up)
  emulator || { echo "not an emulator: refusing" >&2; exit 1; }
  adb root >/dev/null; adb wait-for-device
  keys=$(seq -s, 1 255 | sed "s/,$//")   # BSD seq ends with the separator: invalid JSON
  # by the level, not by looking for the command: a failed adb call would read as "no uinput" and take hid's way on Android 11+
  sdk=$(prop ro.build.version.sdk)
  [[ "$sdk" =~ ^[0-9]+$ ]] || { echo "cannot read the device's API level (ro.build.version.sdk: '$sdk')" >&2; exit 1; }
  if (( sdk >= 30 )); then
    adb shell "echo '{\"id\":1,\"command\":\"register\",\"name\":\"Test USB Keyboard\",\"vid\":4660,\"pid\":22136,\"bus\":\"usb\",\"configuration\":[{\"type\":100,\"data\":[1]},{\"type\":101,\"data\":[$keys]}]}' > /data/local/tmp/kbfifo.log"
    adb shell "nohup sh -c 'tail -n +1 -f /data/local/tmp/kbfifo.log | uinput -' >/data/local/tmp/uinput.out 2>&1 &"
  else
    # Android 10 (API 29) has no uinput command (it came with 11): the same keyboard through the system's
    # hid command (uhid), a USB boot keyboard by its report descriptor (modifiers 224-231, keys 0-101).
    # The kernel gives it an event node as it does uinput's, so type, key and combo are the same sendevent
    adb shell "echo '{\"id\":1,\"command\":\"register\",\"name\":\"Test USB Keyboard\",\"vid\":4660,\"pid\":22136,\"descriptor\":[$HID_BOOT_KEYBOARD]}' > /data/local/tmp/kbfifo.log"
    adb shell "nohup sh -c 'tail -n +1 -f /data/local/tmp/kbfifo.log | hid -' >/data/local/tmp/uinput.out 2>&1 &"
  fi
  # up to 5 s: said here, not by the first type's "no keyboard" (R25 device pass: up exited 0 on API 29)
  for _ in 1 2 3 4 5 6 7 8 9 10; do DEV=$(device); [[ -n "$DEV" ]] && break; sleep 0.5; done
  [[ -n "$DEV" ]] || { echo "no keyboard came up (API $sdk): $(adb shell 'cat /data/local/tmp/uinput.out' | tr -d '\r' | head -3)" >&2; exit 1; }
  echo "$DEV"
  ;;
down)
  # hid is app_process under its class's name; it ends with its pipe as well
  adb shell "pkill -x uinput; pkill -f 'commands[.]hid[.]Hid'; pkill -f 'kbfifo[.]log'; rm -f /data/local/tmp/kbfifo.log" || true
  ;;
type|key|combo)
  DEV=$(device); [[ -n "$DEV" ]] || { echo "no keyboard: run up first" >&2; exit 1; }
  what=$1; shift; s=""
  case $what in
  type)
    text="$*"
    for ((i = 0; i < ${#text}; i++)); do
      ch=${text:i:1}
      case $ch in
      " ") k=SPACE ;; ";") k=SEMI ;; "'") k=APOS ;; ",") k=COMMA ;; ".") k=DOT ;; "/") k=SLASH ;;
      *) k=${ch^^} ;;
      esac
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
  sed -n '2,16p' "$0"; exit 1 ;;
esac
