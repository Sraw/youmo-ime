#!/usr/bin/env python3
"""Overlapping taps on an emulator's touchscreen (MT protocol B through `sendevent`), which
`adb shell input` cannot do: each finger its own slot, the next one down before the last lifts.

    mt_taps.py -s emulator-5590 "d0 540 1900; w40; d1 600 1900; w30; u0; w30; u1"

Ops, `;`-separated: `d<slot> <x> <y>` a finger down at screen pixels, `m<slot> <x> <y>` it moved,
`u<slot>` it lifted, `w<ms>` a pause. The whole sequence runs in one `adb shell`, so the gaps are
the device's own (sendevent itself takes a few ms an event). The touchscreen is the first
`virtio_input_multi_touch` device (`getevent -lp`), its axes 0..32767 over the screen.
"""
import argparse
import re
import subprocess

EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
BTN_TOUCH = 330
ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 47, 53, 54, 57
NO_ID = 4294967295  # -1 as sendevent takes it: the slot's finger lifted


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, check=True).stdout


def touchscreen(serial):
    dev = None
    for line in adb(serial, "shell", "getevent", "-p").splitlines():
        m = re.match(r"add device \d+: (\S+)", line)
        if m:
            dev = m.group(1)
        elif "virtio_input_multi_touch" in line and dev:
            return dev
    raise SystemExit("no virtio multi-touch device")


def screen(serial):
    out = adb(serial, "shell", "wm", "size")
    w, h = re.findall(r"(\d+)x(\d+)", out)[-1]
    return int(w), int(h)


def script(ops, dev, size):
    w, h = size
    down = set()
    lines = []

    def ev(t, c, v):
        lines.append(f"sendevent {dev} {t} {c} {v}")

    for op in [o.strip() for o in ops.split(";") if o.strip()]:
        kind, rest = op[0], op[1:].split()
        if kind == "w":
            lines.append(f"sleep {int(rest[0]) / 1000:.3f}")
            continue
        slot = int(rest[0])
        ev(EV_ABS, ABS_MT_SLOT, slot)
        if kind in "dm":
            x, y = int(rest[1]), int(rest[2])
            if kind == "d":
                ev(EV_ABS, ABS_MT_TRACKING_ID, 100 + slot)
            ev(EV_ABS, ABS_MT_POSITION_X, x * 32767 // (w - 1))
            ev(EV_ABS, ABS_MT_POSITION_Y, y * 32767 // (h - 1))
            if kind == "d":
                if not down:
                    ev(EV_KEY, BTN_TOUCH, 1)
                down.add(slot)
        elif kind == "u":
            ev(EV_ABS, ABS_MT_TRACKING_ID, NO_ID)
            down.discard(slot)
            if not down:
                ev(EV_KEY, BTN_TOUCH, 0)
        else:
            raise SystemExit(f"unknown op {op}")
        ev(EV_SYN, 0, 0)
    return "; ".join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-s", "--serial", required=True)
    p.add_argument("ops")
    a = p.parse_args()
    adb(a.serial, "shell", script(a.ops, touchscreen(a.serial), screen(a.serial)))


if __name__ == "__main__":
    main()
