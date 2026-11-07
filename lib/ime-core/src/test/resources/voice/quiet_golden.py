"""VoiceQuietTest's golden cases: real clips (voice-eval's sets, dev/uat-out/voice-clips) in a room's
noise, through Gain2 and silero VAD (threshold 0.2, on the gained samples, as VoiceListener feeds it)
window by window, with a segment's text as the recognizer wrote it on that input (specs/app.md, Quiet)
and whether it is typed by the rule as written here (independent of VoiceQuiet.kt). A line a case:
name, text, from, to, keeps (1/0), then the windows as power:voiced (mean square of the raw window,
%.6g; 1 voiced). Made with sherpa-onnx 2.x's VAD:

    python3 quiet_golden.py quiet-golden.tsv   (needs numpy, soundfile, sherpa_onnx; paths below)
"""
import math, os, re, sys
import numpy as np, soundfile as sf
import sherpa_onnx as so
sys.path.insert(0, os.path.dirname(__file__))
from gain_golden import Gain2

SR, W = 16000, 512
HOME = os.path.expanduser('~')
VAD = f'{HOME}/workspace/youmo-ime/.gradle/engine-downloads/voice/silero_vad.onnx'
SETS = f'{HOME}/workspace/voice-eval/sets'
CLIPS = f'{HOME}/workspace/youmo-ime/dev/uat-out/voice-clips'
LOUD, RISE, RANK, SKIP, FLOOR, SHORT = -50.0, 8.0, 3, 3, 10, 12


def room(kind, db, n, seed):
    rng = np.random.default_rng(seed)
    w = rng.standard_normal(n)
    if kind == 'pink':
        f = np.fft.rfftfreq(n, 1 / SR); x = np.fft.rfft(w); x[1:] /= np.sqrt(f[1:]); x[0] = 0; w = np.fft.irfft(x, n)
    return (w * 10 ** (db / 20) / w.std()).astype(np.float32)


def level(a):
    f = a[:len(a) // W * W].reshape(-1, W)
    return np.sort(10 * np.log10((f.astype(np.float64) ** 2).mean(1) + 1e-12))[-3]


def clip(path, L=None):
    a, rate = sf.read(path, dtype='float32')
    if a.ndim > 1: a = a.mean(1)
    if rate != SR: a = np.interp(np.arange(0, len(a), rate / SR), np.arange(len(a)), a).astype(np.float32)
    return a if L is None else a * np.float32(10 ** ((L - level(a)) / 20))


def windows(x):
    c = so.VadModelConfig(); s = c.silero_vad
    s.model = VAD; s.threshold = 0.2; s.min_silence_duration = 0.25; s.min_speech_duration = 0.25; s.window_size = W
    c.sample_rate = SR; c.num_threads = 1
    v = so.VoiceActivityDetector(c, buffer_size_in_seconds=60)
    g = Gain2(-30.0, 20.0)
    out = []
    for i in range(0, len(x) - W + 1, W):
        w = x[i:i + W]
        v.accept_waveform(g(w.copy()))
        while not v.empty(): v.pop()
        out.append((float(np.mean(w.astype(np.float64) ** 2)), v.is_speech_detected()))
    return out


def latin_short(t):
    if re.search(r'[㐀-鿿]', t): return False
    return 1 <= sum(c.isalnum() for c in t) <= SHORT


def keeps(text, ws, a, b):
    if not latin_short(text): return True
    lv = []
    seen = 0
    for p, _ in ws:
        if p > 0 and math.isfinite(p):
            seen += 1
            lv.append(10 * math.log10(p) if seen > SKIP else None)
        else:
            lv.append(None)
    nz = sorted(math.floor(d + 0.5) for d in lv if d is not None)
    fl = nz[len(nz) * FLOOR // 100] if nz else -120
    i0, i1 = a // W, min(len(ws), -(-b // W))
    seg = sorted(d for d in lv[i0:i1] if d is not None)
    voiced = any(v for _, v in ws[i0:i1])
    return voiced and len(seg) >= RANK and (seg[-RANK] >= LOUD or seg[-RANK] - fl >= RISE)


def pad(a, pre, post, kind, db, seed):
    x = np.concatenate([np.zeros(int(SR * pre), np.float32), a, np.zeros(int(SR * post), np.float32)])
    return x + room(kind, db, len(x), seed) if db is not None else x


# (name, text the recognizer wrote on it, audio)
CASES = [
    ('white-66', "I'm sorry.", pad(np.zeros(0, np.float32), 1.5, 1.5, 'white', -66, 1)),
    ('pink-60', "I'm sorry.", pad(np.zeros(0, np.float32), 1.5, 1.5, 'pink', -60, 2)),
    ('fan', "I'm sorry.", pad(clip(f'{SETS}/noise/fan-' + sorted(f for f in os.listdir(f'{SETS}/noise') if f.startswith('fan-'))[0][4:]) * np.float32(10 ** (-30 / 20)), 0.3, 0.5, 'white', -66, 3)),
    ('zeroes-h', 'H', np.zeros(SR * 3, np.float32)),
    ('loud-english', "I'm sorry.", pad(clip(f'{CLIPS}/zouba.wav'), 0.5, 0.8, 'white', -66, 4)),
    ('soft-english-over-room', "Look friends.", pad(clip(f'{SETS}/ascend/0000.wav', -50), 0.5, 0.8, 'white', -66, 5)),
    ('q44-in-room', '你好', pad(clip(f'{CLIPS}/nihao-q44.wav'), 0.5, 0.8, 'white', -60, 6)),
    ('soft-chinese', '姥姥进城二六六万十元。', pad(clip(f'{SETS}/aishell/0000.wav', -60), 0.5, 0.8, 'white', -66, 7)),
]

if __name__ == '__main__':
    with open(sys.argv[1], 'w') as f:
        for name, text, x in CASES:
            ws = windows(x.astype(np.float32))
            a, b = 0, len(ws) * W
            k = keeps(text, ws, a, b)
            f.write('\t'.join([name, text, str(a), str(b), '1' if k else '0'] + [f'{p:.6g}:{int(v)}' for p, v in ws]) + '\n')
            print(name, text, 'keeps' if k else 'drops', sum(v for _, v in ws), 'voiced of', len(ws))
