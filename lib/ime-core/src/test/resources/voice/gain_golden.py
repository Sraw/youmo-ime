"""VoiceGainTest's golden sample: a fixed input through Gain2, the gain as voice-eval's tailquiet/tq.py
measured it (copied here so the sample can be made again from this repo), window by window. The input:
room noise, a steady -40 dBFS (the gain off both its limits: +10 dB), -20 dBFS then -45 dBFS for
6 s (the 3 dB/s fall, off its limits too), quiet speech-like bursts, a click, quiet again, three
clicks a span apart, suddenly loud, silence. Out: little-endian float32, input then output, 512-sample windows.

    python3 gain_golden.py gain-golden.bin   (needs numpy; VoiceGainTest's tolerance: 1e-5 a sample)

GAIN_MUTATE="NAME=value" (target, mx, decay, SKIP, SPAN, RANK, PEAK) builds it with that one changed:
written out instead, it must differ from gain-golden.bin by more than 1e-5, or the test cannot catch it.
"""
import os
import sys

import numpy as np

SR, W = 16000, 512


def db(x):
    return 10 * np.log10(np.mean(x.astype(np.float64) ** 2) + 1e-12)


class Gain2:
    """tq.py's Gain2, line for line (VoiceGain.kt is it in Kotlin from the first window with
    sound on: this counts SKIP from the first window even when it is all zeroes, so the two differ
    on an input that opens in digital silence)"""
    SKIP, SPAN, RANK = 3, 31, 3
    PEAK = 0.99

    def __init__(s, target, mx, decay=3.0):
        s.t, s.mx, s.dec, s.env, s.g, s.seen, s.recent = target, mx, decay * W / SR, None, 1.0, 0, []

    def __call__(s, w):
        s.seen += 1
        if s.seen > s.SKIP:
            s.recent = (s.recent + [db(w)])[-s.SPAN:]
            if len(s.recent) >= s.RANK:
                lv = sorted(s.recent)[-s.RANK]
                s.env = lv if s.env is None else max(lv, s.env - s.dec)
        g = 1.0 if s.env is None else 10 ** (min(s.mx, max(0.0, s.t - s.env)) / 20)
        p = float(np.max(np.abs(w))) if len(w) else 0.0
        if p > 0:
            g = max(1.0, min(g, s.PEAK / p))
        ramp = np.linspace(s.g, g, len(w), dtype=np.float32) if g > s.g else np.float32(g)
        s.g = g
        return np.clip(w * ramp, -1, 1).astype(np.float32)


def burst(sec, dbfs):  # a 200 Hz tone, 4 Hz on/off, at dbfs RMS while on
    t = np.arange(int(sec * SR)) / SR
    on = (np.floor(t * 8) % 2 == 0)
    return (np.sin(2 * np.pi * 200 * t) * np.sqrt(2) * 10 ** (dbfs / 20) * on).astype(np.float32)


def tone(sec, dbfs):  # steady
    t = np.arange(int(sec * SR)) / SR
    return (np.sin(2 * np.pi * 200 * t) * np.sqrt(2) * 10 ** (dbfs / 20)).astype(np.float32)


def golden(target=-30.0, mx=20.0, decay=3.0, **cls):
    rng = np.random.default_rng(20261025)
    def click():  # one window, -10 dBFS noise
        return (rng.standard_normal(W) * 10 ** (-10 / 20)).astype(np.float32)
    x = np.concatenate([
        rng.standard_normal(SR // 2).astype(np.float32) * 10 ** (-70 / 20),   # room, 0.5 s
        tone(1.5, -40),                                                        # off both limits: +10 dB
        tone(1.0, -20), tone(6.0, -45),                                        # the fall at 3 dB/s, then lifted
        burst(2.0, -55),                                                       # quiet speech
        np.r_[np.zeros(160), rng.standard_normal(320) * 10 ** (-10 / 20), np.zeros(1120)].astype(np.float32),  # a click
        burst(1.0, -55),                                                       # quiet again
        # three one-window clicks 15 windows apart: all three in a 31-window span at the third, the
        # level then theirs; a shorter span holds two at most
        click(), tone(14 * W / SR, -55), click(), tone(14 * W / SR, -55), click(), tone(1.0, -55),
        burst(0.5, -6),                                                        # suddenly loud
        np.zeros(SR // 2, np.float32),                                         # silence
    ])
    x = x[:len(x) // W * W].astype(np.float32)
    g = Gain2(target, mx, decay)
    for k, v in cls.items():
        setattr(g, k, v)
    y = np.concatenate([g(x[i:i + W]) for i in range(0, len(x), W)])
    return x, y


if __name__ == "__main__":
    kw = {}
    if os.environ.get("GAIN_MUTATE"):
        k, v = os.environ["GAIN_MUTATE"].split("=")
        kw[k] = int(v) if k in ("SKIP", "SPAN", "RANK") else float(v)
    x, y = golden(**kw)
    with open(sys.argv[1], "wb") as f:
        f.write(np.asarray(x, "<f4").tobytes())
        f.write(np.asarray(y, "<f4").tobytes())
    print(len(x) // W, "windows; peak out", float(np.abs(y).max()))
