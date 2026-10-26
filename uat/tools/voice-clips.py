"""The spoken phrases of uat/12-voice.md as WAVs, for 12-voice.py on the emulator (its microphone is
the host's PulseAudio source that uat.py plays them into). Made on this machine, offline: Kokoro
v1.1-zh through sherpa-onnx (the models of ~/voice-eval, VOICE_EVAL to move it), each checked by the
X-ASR model of the same place so a clip that the recognizer cannot follow is not taken for the app's
fault. A cough is filtered noise; `room` 3 s of pink noise at some -60 dBFS (VO-09: digital silence
shows no gain). `<name>-q44` is the clip 44 dB down, speech said softly (VO-24). Out:
dev/uat-out/voice-clips/<name>.wav, 16 kHz mono, and beside each spoken one <name>.json, where its
speech starts and ends (`speech_start_s`, `speech_end_s`, as voice-eval's tailquiet/tq.py finds the
end): VO-23 lifts the finger relative to that end.

    python3 uat/tools/voice-clips.py
"""
import json
import os
import sys

import numpy as np
import sherpa_onnx as so
import soundfile as sf
from scipy.signal import butter, lfilter, resample_poly

EVAL = os.environ.get("VOICE_EVAL", os.path.expanduser("~/voice-eval"))
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                   "dev", "uat-out", "voice-clips")
RATE = 16000
VOICE = 50   # one of Kokoro v1.1-zh's Chinese voices (3 to 102)
QUIET = {"zouba": 44, "nihao": 44}   # name: dB down, as <name>-q<dB> (tailquiet's cu2: the old flow hears nothing)

PHRASES = {   # name: (what Kokoro is given, what X-ASR alone should hear, None: not checked)
    "jintian": ("今天天气很好", "今天天气很好"),
    "nihao": ("你好", "你好"),
    "zouba": ("我们走吧", "我们走吧"),
    "2002": ("二零零二年", "二零零二年"),
    "57": ("百分之五十七", "百分之五十七"),
    "shifen": ("这个电影十分有趣", "这个电影十分有趣"),
    # Kokoro's lexicon has no "iPhone"
    "iphone": ("我想买一个 I phone", "我想买一个iphone"),
    # a user word the recognizer alone mishears (蓝镜花园): heard only as the user's hotword
    "lanjing": ("我住在澜境花园", None),
    # VO-19..21: a name the recognizer alone hears as 王小明 or the like
    "wangxiaoming": ("汪小明", None),
    "wangmingtian": ("汪小明明天来", None),
}


def tts():
    d = f"{EVAL}/models/kokoro-multi-lang-v1_1"
    return so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(kokoro=so.OfflineTtsKokoroModelConfig(
        model=f"{d}/model.onnx", voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt",
        lexicon=f"{d}/lexicon-us-en.txt,{d}/lexicon-zh.txt", data_dir=f"{d}/espeak-ng-data",
        dict_dir=f"{d}/dict"), num_threads=2)))


def asr():
    d = f"{EVAL}/models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
    return so.OfflineRecognizer.from_transducer(
        encoder=f"{d}/encoder-epoch-99-avg-1.int8.onnx", decoder=f"{d}/decoder-epoch-99-avg-1.onnx",
        joiner=f"{d}/joiner-epoch-99-avg-1.int8.onnx", tokens=f"{d}/tokens.txt", num_threads=2)


def pad(s, before=0.4, after=0.8):
    return np.concatenate([np.zeros(int(before * RATE), np.float32), s, np.zeros(int(after * RATE), np.float32)])


def speech_span(s):
    """where the speech is, in seconds: 10 ms frames over a threshold, as tailquiet/tq.py's speech_end"""
    f = s[:len(s) // 160 * 160].reshape(-1, 160)
    e = 10 * np.log10((f.astype(np.float64) ** 2).mean(1) + 1e-12)
    thr = min(max(np.percentile(e, 10) + 12, e.max() - 35), e.max() - 3)
    on = np.nonzero(e > thr)[0]
    return on[0] * 160 / RATE, (on[-1] + 1) * 160 / RATE


def write_span(name, s):
    start, end = speech_span(s)
    with open(f"{OUT}/{name}.json", "w") as f:
        json.dump({"speech_start_s": round(float(start), 3), "speech_end_s": round(float(end), 3)}, f)


def room():
    """pink noise (1/f, by filtering white in the spectrum), 3 s at -60 dBFS RMS"""
    rng = np.random.default_rng(11)
    n = 3 * RATE
    spec = np.fft.rfft(rng.standard_normal(n))
    spec[1:] /= np.sqrt(np.arange(1, len(spec)))
    spec[0] = 0
    x = np.fft.irfft(spec, n)
    return (x / np.sqrt(np.mean(x ** 2)) * 10 ** (-60 / 20)).astype(np.float32)


def cough():
    rng = np.random.default_rng(7)
    n = int(0.35 * RATE)
    t = np.arange(n) / RATE
    env = np.minimum(1, t / 0.02) * np.exp(-t / 0.08)
    b, a = butter(2, [300 / (RATE / 2), 2500 / (RATE / 2)], btype="band")
    return (lfilter(b, a, rng.standard_normal(n)) * env * 0.5).astype(np.float32)


def main():
    os.makedirs(OUT, exist_ok=True)
    t, r = tts(), asr()
    bad = 0
    for name, (text, want) in PHRASES.items():
        a = t.generate(text, sid=VOICE, speed=1.0)
        s = np.array(a.samples, dtype=np.float32)
        if a.sample_rate != RATE:
            s = resample_poly(s, RATE, a.sample_rate).astype(np.float32)
        s = pad(s * (0.6 / max(1e-6, float(np.abs(s).max()))))
        sf.write(f"{OUT}/{name}.wav", s, RATE)
        write_span(name, s)
        if name in QUIET:
            # the same speech softly: where it is stays where it was found at full level
            sf.write(f"{OUT}/{name}-q{QUIET[name]}.wav", (s * 10 ** (-QUIET[name] / 20)).astype(np.float32), RATE)
            write_span(f"{name}-q{QUIET[name]}", s)
        st = r.create_stream()
        st.accept_waveform(RATE, s)
        r.decode_stream(st)
        heard = st.result.text
        ok = want is None or "".join(ch for ch in heard.lower() if ch.isalnum()) == want
        bad += not ok
        print(f"{'ok ' if ok else 'BAD'} {name}: said {text} / X-ASR heard {heard}")
    sf.write(f"{OUT}/cough.wav", pad(cough()), RATE)
    print("cough.wav: filtered noise")
    sf.write(f"{OUT}/room.wav", room(), RATE)
    print("room.wav: pink noise, -60 dBFS")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
