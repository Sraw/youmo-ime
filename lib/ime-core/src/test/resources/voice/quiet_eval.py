"""The table of specs/app.md (Voice, Quiet): VoiceQuiet's rule and the rules it was chosen over,
measured on the app's pipeline on the host. Each talk: speech (or none) with silence around it, in a
room's noise, fed window by window (512 samples) through Gain2 (the app's VoiceGain, tailquiet's
tq.py) to the 060 streaming recognizer with the app's endpoints (beam 16), and to silero VAD
(sherpa-onnx; on the gained samples, its speech-detected flag read each window as VoiceListener
does). Each recognizer segment is judged by each rule; the talk's text is what is kept.

Rules: base (none, d4d0981c6), r1 (077374d11: the whole talk's 3rd loudest window -50 dBFS or 8 dB
over its floor), a (rise 6 dB, 3 windows in a row, the floor without windows under -90), b2 (silero
0.2 heard any speech in the talk), h2 (VoiceQuiet now, since 9b98ecda4; first 946ccd433: a short
Latin segment typed only if voiced and its 3rd loudest window -50 dBFS or louder or 8 dB over the
floor), h3 (measured, not taken: as h2 but -50 dBFS or louder typed unvoiced too).

Sets (voice-eval's sets, VOICE_EVAL; the UAT's clips, dev/uat-out/voice-clips): sp (aishell 0-39),
ascend (0-39, Chinese with English), en (Kokoro's English phrases, voices 0-2, made by en_clips()),
q44 (VO-24's clips), pre2 (2 s of room, then sp), merge (1 s of room, then sp: speech within the
endpoint's 1.2 s of what the room was heard as), onset (speech from the first sample), and noise
alone: white, pink, zeroes, FSD50K room/household/keyboard/traffic (at their level, and 30 dB down).
L: the speech's 3rd loudest window (dBFS); R: the room's level.

    python3 quiet_eval.py run out.jsonl     (P=8 processes; some 20 minutes)
    python3 quiet_eval.py table out.jsonl

Needs numpy, soundfile, sherpa_onnx 2.x (and for en_clips the Kokoro model of voice-eval).
"""
import glob, json, math, os, re, sys, collections
import numpy as np, soundfile as sf

HOME = os.path.expanduser('~')
EVAL = os.environ.get('VOICE_EVAL', f'{HOME}/workspace/voice-eval')
sys.path.insert(0, f'{EVAL}/tailquiet')
MODELS = os.environ.get('VOICE_MODELS', f'{HOME}/workspace/models/youmo-voice-060')
VADM = os.environ.get('VOICE_VAD', f'{HOME}/workspace/youmo-ime/.gradle/engine-downloads/voice/silero_vad.onnx')
CLIPS = f'{HOME}/workspace/youmo-ime/dev/uat-out/voice-clips'
EN = os.environ.get('VOICE_QUIET_EN', os.path.join(CLIPS, 'en-short'))
SR, W = 16000, 512
TAIL = SR * 242 // 100
LOUD, RISE, SHORT = -50.0, 8.0, 12
RULES = ['base', 'r1', 'a', 'b2', 'h2', 'h3', 'h4']
# the app's hotwords (the pack's 78 thousand, score 1.5) and bpe vocabulary: they change what the room is heard as
ASSETS = os.environ.get('VOICE_ASSETS', f'{HOME}/workspace/youmo-ime/app/build/generated/voice-assets/voice')
EN_PHRASES = ["OK", "No", "Yes", "Hi", "Hello", "Thank you", "Good night", "See you", "I'm sorry", "Sounds good",
              "Why not", "Of course", "Got it", "Me too", "Nice", "Sure"]
HAN = re.compile(r'[㐀-鿿]')
PUNCT = re.compile(r'[\s。，、！？.,!?…：:；;“”"\'’]')
FILLER = re.compile(r'^[嗯呃额啊唔哦噢欸诶呀。，、！？.,!?…\s]*$')
rec = so = None


def init():
    global rec, so
    import sherpa_onnx
    so = sherpa_onnx
    rec = so.OnlineRecognizer.from_transducer(
        tokens=f'{MODELS}/tokens.txt', encoder=f'{MODELS}/encoder.ort', decoder=f'{MODELS}/decoder.ort',
        joiner=f'{MODELS}/joiner.ort', num_threads=1, sample_rate=SR, feature_dim=80, enable_endpoint_detection=True,
        decoding_method='modified_beam_search', max_active_paths=16,
        hotwords_file=f'{ASSETS}/hotwords.txt', hotwords_score=1.5, modeling_unit='bpe', bpe_vocab=f'{ASSETS}/bpe.vocab')


def vad(thr):
    c = so.VadModelConfig(); s = c.silero_vad
    s.model = VADM; s.threshold = thr; s.min_silence_duration = 0.25; s.min_speech_duration = 0.25; s.window_size = W
    c.sample_rate = SR; c.num_threads = 1
    return so.VoiceActivityDetector(c, buffer_size_in_seconds=60)


def clean(t):
    t = t.strip()
    return '' if FILLER.match(t) else t


def latin_short(t):
    return not HAN.search(t) and 1 <= sum(c.isalnum() for c in t) <= SHORT


class Talk:
    """the windows' raw levels (None: no sound or the first 3 with sound) and silero's flag"""
    def __init__(s):
        s.lv, s.voiced, s.seen = [], [], 0

    def floor(s):
        nz = sorted(math.floor(d + 0.5) for d in s.lv if d is not None)
        return nz[len(nz) * 10 // 100] if nz else -120

    def third(s, a, b):
        seg = sorted(d for d in s.lv[a // W:-(-b // W)] if d is not None)
        return seg[-3] if len(seg) >= 3 else None

    def h2(s, t, a, b):
        return not latin_short(t) or s.loud_voiced(a, b)

    def loud_voiced(s, a, b):
        l = s.third(a, b)
        return l is not None and any(s.voiced[a // W:-(-b // W)]) and (l >= LOUD or l - s.floor() >= RISE)

    def h4(s, segs):
        """measured, not taken: h3, and every segment after one typed held to the short Latin bar
        whatever its text (the room after the speech heard as more); loses a second soft sentence"""
        out, typed = [], False
        for a, b, t in segs:
            keep = s.loud_voiced(a, b) if typed or latin_short(t) else s.h3(t, a, b)
            if keep and clean(t): typed = True
            if keep: out.append(t)
        return clean(''.join(out))

    def h3(s, t, a, b):
        if not latin_short(t): return True
        l = s.third(a, b)
        return l is not None and (l >= LOUD or any(s.voiced[a // W:-(-b // W)]) and l - s.floor() >= RISE)


def run_talk(x):
    from tq import Gain2
    g = Gain2(-30.0, 20.0); v = vad(0.2); tk = Talk()
    st = rec.create_stream(); segs = []; start = 0; fed = 0
    def take():
        r = rec.get_result(st); t = r if isinstance(r, str) else r.text
        if t.strip(): segs.append((start, fed, t))
    for i in range(0, len(x) - W + 1, W):
        w = x[i:i + W].copy()
        p = float(np.mean(w.astype(np.float64) ** 2))
        if p > 0 and math.isfinite(p) and 10 * math.log10(p) >= -100: tk.seen += 1
        tk.lv.append(10 * math.log10(p) if p > 0 and math.isfinite(p) and 10 * math.log10(p) >= -100 and tk.seen > 3 else None)
        w = g(w); v.accept_waveform(w)
        while not v.empty(): v.pop()
        tk.voiced.append(v.is_speech_detected())
        st.accept_waveform(SR, w); fed += W
        while rec.is_ready(st): rec.decode_stream(st)
        if rec.is_endpoint(st):
            take(); rec.reset(st); start = fed
    st.accept_waveform(SR, np.zeros(TAIL, np.float32)); st.input_finished()
    while rec.is_ready(st): rec.decode_stream(st)
    take()
    whole = clean(''.join(t for _, _, t in segs))
    lv = [d for d in tk.lv if d is not None]
    fl = tk.floor()
    third = sorted(lv)[-3] if len(lv) >= 3 else None
    r1 = third is not None and (third >= LOUD or third - fl >= RISE)
    # a: rise 6 in 3 windows running, the floor without what is under -90
    base = [d for d in lv if d > -90] or lv
    fa = sorted(base)[len(base) * 10 // 100] if base else -120
    run = best = 0
    for d in lv:
        run = run + 1 if d >= LOUD or d - fa >= 6 else 0
        best = max(best, run)
    return dict(base=whole, r1=whole if r1 else '', a=whole if best >= 3 else '', b2=whole if any(tk.voiced) else '',
                h2=clean(''.join(t for a, b, t in segs if tk.h2(t, a, b))),
                h3=clean(''.join(t for a, b, t in segs if tk.h3(t, a, b))), h4=tk.h4(segs), segs=segs)


def ed(a, b):
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        p, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (a[i - 1] != b[j - 1]))
    return d[-1]


def norm(t): return PUNCT.sub('', t).lower()


def room(kind, db, n, rng):
    if db is None: return np.zeros(n, np.float32)
    w = rng.standard_normal(n)
    if kind == 'pink':
        f = np.fft.rfftfreq(n, 1 / SR); X = np.fft.rfft(w); X[1:] /= np.sqrt(f[1:]); X[0] = 0; w = np.fft.irfft(X, n)
    return (w * 10 ** (db / 20) / w.std()).astype(np.float32)


def level(a):
    f = a[:len(a) // W * W].reshape(-1, W)
    return np.sort(10 * np.log10((f.astype(np.float64) ** 2).mean(1) + 1e-12))[-3]


def read(path):
    a, rate = sf.read(path, dtype='float32')
    if a.ndim > 1: a = a.mean(1)
    if rate != SR: a = np.interp(np.arange(0, len(a), rate / SR), np.arange(len(a)), a).astype(np.float32)
    return a


def sparse(n):
    """the clip microphone's floor between clips: one sample of ±1 in 128"""
    x = np.zeros(n, np.float32); x[::128] = 1 / 32768; return x


def fade(a, ms=50):
    n = SR * ms // 1000; r = np.linspace(0, 1, n, dtype=np.float32); a = a.copy(); a[:n] *= r; a[-n:] *= r[::-1]; return a


def edge(a, v, k, rng):
    """repro: the clip (hard cut) between the clip mic's floor, 3 s of it after; i: −60 pink room before
    and after instead (joined at the clip's hard edges); i_fade: i with the clip faded; ii: repro with
    the clip faded"""
    if v in ('i_fade', 'ii'): a = fade(a)
    if v in ('i', 'i_fade'):
        pre = room('pink', -60, k, rng) if k else np.zeros(0, np.float32)
        return np.concatenate([pre, a, room('pink', -60, SR * 3, rng)])
    return np.concatenate([sparse(k), a, sparse(SR * 3)])


def job(j):
    rng = np.random.default_rng(j['seed'])
    a = read(j['wav']) if j.get('wav') else np.zeros(SR * 3, np.float32)
    if j.get('L') is not None: a = a * np.float32(10 ** ((j['L'] - level(a)) / 20))
    elif j.get('att'): a = a * np.float32(10 ** (-j['att'] / 20))
    if j.get('then'):   # a second sentence after a pause, at the same level
        b = read(j['then'])
        if j.get('L') is not None: b = b * np.float32(10 ** ((j['L'] - level(b)) / 20))
        a = np.concatenate([a, np.zeros(int(SR * j['gap']), np.float32), b])
    if j.get('edge'):   # VO-32's clip, k samples after the opening, its ends as the variant says
        x = edge(a, j['edge'], j['k'], rng)
    else:
        x = np.concatenate([np.zeros(int(SR * j.get('pre', 0.5)), np.float32), a, np.zeros(int(SR * j.get('post', 0.8)), np.float32)])
        x = x + room(j.get('kind', 'white'), j.get('R'), len(x), rng)
    out = run_talk(x.astype(np.float32))
    r = dict(j); r['segs'] = out.pop('segs'); r['out'] = out
    if j.get('ref') is not None:
        ref = norm(j['ref']); r['err'] = {k: ed(ref, norm(v)) for k, v in out.items()}; r['len'] = len(ref)
    return r


def en_clips():
    """Kokoro v1.1's English voices 0-2 saying EN_PHRASES, at 0.6 peak, into EN"""
    import sherpa_onnx
    d = f'{EVAL}/models/kokoro-multi-lang-v1_1'
    t = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=f'{d}/model.onnx', voices=f'{d}/voices.bin', tokens=f'{d}/tokens.txt',
        lexicon=f'{d}/lexicon-us-en.txt,{d}/lexicon-zh.txt', data_dir=f'{d}/espeak-ng-data', dict_dir=f'{d}/dict'), num_threads=4)))
    os.makedirs(EN, exist_ok=True)
    for sid in (0, 1, 2):
        for i, p in enumerate(EN_PHRASES):
            g = t.generate(p, sid=sid, speed=1.0); s = np.array(g.samples, np.float32)
            s = np.interp(np.arange(0, len(s), g.sample_rate / SR), np.arange(len(s)), s).astype(np.float32)
            sf.write(f'{EN}/{sid}-{i:02d}.wav', s * np.float32(0.6 / max(1e-6, float(abs(s).max()))), SR)


def jobs():
    js = []
    def add(**j): j['seed'] = len(js) + 1; js.append(j)
    aishell = [json.loads(l) for l in open(f'{EVAL}/sets/aishell/refs.jsonl')][:40]
    ascend = [json.loads(l) for l in open(f'{EVAL}/sets/ascend/refs.jsonl')][:40]
    if not os.path.isdir(EN): en_clips()
    for R, Ls in ((-66, (-50, -54, -58, -60, -62)), (-60, (-50, -54, -58, -60, -62))):
        for L in Ls:
            for r in aishell: add(set='sp', wav=f"{EVAL}/sets/aishell/{r['wav']}", ref=r['ref'], L=L, R=R)
    for R in (-66, -60):
        for L in (-50, -54, -58):
            for r in ascend: add(set='ascend', wav=f"{EVAL}/sets/ascend/{r['wav']}", ref=r['ref'], L=L, R=R)
        for L in (-30, -40, -50, -52, -54, -56, -58, -60):
            for sid in (0, 1, 2):
                for i, p in enumerate(EN_PHRASES): add(set='en', wav=f'{EN}/{sid}-{i:02d}.wav', ref=p, L=L, R=R)
        # VO-32's 「你好。欢迎您」: nihao-q44-room as cut hard (clips-bak) at 32 offsets, four surroundings
        if R == -60 and os.path.exists(f'{CLIPS}/hard/nihao-q44-room.wav'):
            for v in ('repro', 'i', 'i_fade', 'ii'):
                for k in range(0, 512, 16): add(set=f'edge-{v}', wav=f'{CLIPS}/hard/nihao-q44-room.wav', ref='你好', edge=v, k=k, R=None)
        # the room going on 3 s after the speech: what more it is heard as (tail; the q44 clips already in it)
        for c, ref in (('nihao-q44-room', '你好'), ('zouba-q44-room', '我们走吧'), ('nihao-q44', '你好'), ('zouba-q44', '我们走吧'), ('nihao', '你好'), ('zouba', '我们走吧'), ('jintian', '今天天气很好')):
            for _ in range(8): add(set='tail', wav=f'{CLIPS}/{c}.wav', ref=ref, R=R, post=3.0, kind='pink')
        # two soft sentences 1.5 s apart: the second its own segment
        for L in (-54, -58):
            for i in range(0, 40, 2):
                add(set='two', wav=f"{EVAL}/sets/aishell/{aishell[i]['wav']}", then=f"{EVAL}/sets/aishell/{aishell[i + 1]['wav']}",
                    ref=aishell[i]['ref'] + aishell[i + 1]['ref'], L=L, R=R, gap=1.5)
        for c, ref in (('nihao-q44', '你好'), ('zouba-q44', '我们走吧')):
            for _ in range(3): add(set='q44', wav=f'{CLIPS}/{c}.wav', ref=ref, R=R)
        for r in aishell: add(set='pre2', wav=f"{EVAL}/sets/aishell/{r['wav']}", ref=r['ref'], L=-58, R=R, pre=2.0)
        for r in aishell: add(set='merge', wav=f"{EVAL}/sets/aishell/{r['wav']}", ref=r['ref'], L=-58, R=R, pre=1.0)
        for L in (-54, -58, -60):
            for c, ref in (('nihao', '你好'), ('zouba', '我们走吧'), ('jintian', '今天天气很好')):
                add(set='onset', wav=f'{CLIPS}/{c}.wav', ref=ref, L=L, R=R, pre=0.0)
        for kind in ('white', 'pink'):
            for _ in range(8): add(set=f'noise-{kind}', kind=kind, R=R)
    for _ in range(4): add(set='noise-zero', R=None)
    for cat in ('room', 'household', 'keyboard', 'traffic'):
        for w in sorted(glob.glob(f'{EVAL}/sets/noise/{cat}-*.wav'))[:15]:
            add(set=f'fsd-{cat}', wav=w, R=-66)
            add(set=f'fsd30-{cat}', wav=w, att=30, R=-66)
    return js


def table(path):
    rs = [json.loads(l) for l in open(path)]
    grp = collections.defaultdict(list)
    for r in rs:
        grp[(r['set'], r.get('R'), r.get('L'))].append(r)
    print('| case | n | ' + ' | '.join(RULES) + ' |')
    for k in sorted(grp, key=str):
        v = grp[k]
        if v[0].get('ref') is not None:
            n = sum(r['len'] for r in v)
            cells = [f"{100 * sum(r['err'][g] for r in v) / n:.1f}% / {100 * sum(not r['out'][g] for r in v) / len(v):.0f}%" for g in RULES]
        else:
            cells = [f"{sum(bool(r['out'][g]) for r in v)}" for g in RULES]
        print(f"| {k[0]} R{k[1]} L{k[2]} | {len(v)} | " + ' | '.join(cells) + ' |')
    # merge: the room's Latin text typed ahead of the Chinese
    for R in (-66, -60):
        v = [r for r in rs if r['set'] in ('merge', 'pre2') and r['R'] == R]
        for s in ('merge', 'pre2'):
            w = [r for r in v if r['set'] == s]
            if w: print(f"{s} R{R}: Latin typed with the Chinese (h2) {sum(bool(re.search('[A-Za-z]', r['out']['h2'])) for r in w)}/{len(w)}")


if __name__ == '__main__':
    if sys.argv[1] == 'run':
        from multiprocessing import Pool
        js = jobs()
        with Pool(int(os.environ.get('P', 8)), initializer=init) as p, open(sys.argv[2], 'w') as f:
            for r in p.imap_unordered(job, js): f.write(json.dumps(r, ensure_ascii=False) + '\n'); f.flush()
    else:
        table(sys.argv[2])
