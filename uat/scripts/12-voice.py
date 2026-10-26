"""uat/12-voice.md (语音输入, the voice build): on the emulator only (`uat.py run --devices 5554
--flavor voice`), whose microphone is the host's PulseAudio source; what is "said" is a clip of
uat/tools/voice-clips.py played into it (Device.say). The .md is the source of truth."""
import json
import os
import re
import subprocess
import time

from uat import CLIPS, OUT, PKG, Blocked, Fail, case, check, script

LISTEN, HEARD_NOTHING, PREPARING, SILENT = "正在听…", "没有听清", "准备中…", "麦克风没有声音"
EV = "emu,voice:语音只在模拟器上的语音版跑（--devices 5554 --flavor voice），redroid 没有麦克风输入"


def emu_only(d):
    if not d.emu:
        raise Blocked("没有麦克风输入：语音只在模拟器上跑（--devices 5554 --flavor voice）")


def anywhere(s, text):
    return next((n for n in s.nodes if text in (n.text or "") or text in (n.desc or "")), None)


def plain(t):
    """the field's text less the punctuation the recognizer may end with"""
    return (t or "").rstrip("。.，,！!？? ")


def quiet(d, timeout=6):
    """nothing of the last utterance on screen (识别中…, 没有听清): a press while 没有听清 shows
    is not taken, and no device case means to press then"""
    end = time.time() + timeout
    while time.time() < end:
        s = d.screen()
        if not anywhere(s, HEARD_NOTHING) and not anywhere(s, "识别中"):
            return
        time.sleep(0.2)


# the emulator's touchscreen, written to with sendevent (adb shell is root there): a native binary
# starts in milliseconds where `input` starts an app_process, ±300 ms on a loaded host
# The device, its axes' top and the screen's size are read from the emulator once (another AVD or
# skin has others), not assumed
_touch = {}


def touch_screen(d):
    """(device, x max, y max, width, height) of the emulator's first multi-touch screen"""
    if not _touch:
        dev, axes = None, {}
        for line in d.sh("getevent -pl").splitlines():
            m = re.match(r"add device \d+: (\S+)", line)
            if m:
                if dev and "x" in axes and "y" in axes:
                    break
                dev, axes = m.group(1), {}
            for name, key in (("ABS_MT_POSITION_X", "x"), ("ABS_MT_POSITION_Y", "y")):
                m = re.search(name + r"\s*:.*max (\d+)", line)
                if m:
                    axes[key] = int(m.group(1))
        if not (dev and "x" in axes and "y" in axes):
            raise Blocked("getevent -pl 里没有多点触摸屏")
        w, h = map(int, re.search(r"(\d+)x(\d+)", d.sh("wm size")).groups())
        _touch.update(dev=dev, x=axes["x"], y=axes["y"], w=w, h=h)
    t = _touch
    return t["dev"], t["x"], t["y"], t["w"], t["h"]


def raw_down(d, x, y):
    """the shell line putting a finger down at (x, y) on the touchscreen, through sendevent"""
    dev, xm, ym, w, h = touch_screen(d)
    X, Y = x * xm // w, y * ym // h
    return (f"sendevent {dev} 3 47 0; sendevent {dev} 3 57 7; sendevent {dev} 3 53 {X}; "
            f"sendevent {dev} 3 54 {Y}; sendevent {dev} 3 58 50; sendevent {dev} 0 0 0")


def raw_up(d):
    dev = touch_screen(d)[0]
    return f"sendevent {dev} 3 57 4294967295; sendevent {dev} 0 0 0"


class HalBroke(Blocked):
    """the emulator's sound card failing (pcm_readi failed): the try says nothing of the app; a
    case that does not retry is blocked by it"""

    def __init__(self):
        super().__init__("模拟器声卡出错（pcm_readi failed），准备中…等不到声音")


def hal_errors(d):
    return int(d.sh("logcat -d | grep -c 'pcm_readi failed'").strip() or 0)


def hold_space(d, timeout=60, wait_quiet=True, raw=False):
    """finger down on the space key until the hold shows (准备中… or 正在听…; the first use after a
    reset loads the models); returns the space key's centre. raw: through sendevent, to be lifted with raw_up"""
    emu_only(d)
    if wait_quiet:
        quiet(d)
    x, y = d.keys(refresh=True)["space"]
    if raw:
        d.sh(raw_down(d, x, y))
    else:
        d.touch(("down", x, y))
    start = time.time()

    def lift():
        if raw:
            d.sh(raw_up(d))
        else:
            d.touch(("up", x, y))

    def listening(s):
        # nothing shown past 5 s with the sound card failing: the app's start waits on the HAL
        # (rare; the hal_errors check after the wait is the one that mostly catches it)
        if time.time() - start > 5 and hal_errors(d):
            raise HalBroke()
        # 准备中… is held too: the emulator's microphone reads exact zeroes till a clip plays, so
        # 正在听 comes with the clip (VO-26)
        return anywhere(s, LISTEN) or anywhere(s, PREPARING) or anywhere(s, SILENT)

    try:
        d.wait(listening, timeout, step=0.2, what=LISTEN)
    except HalBroke:
        lift()
        settle(d)
        raise
    except Blocked:
        s = d.screen()
        d.shot("full", f"长按空格 {timeout} s，没有「{LISTEN}」")
        lift()
        raise Fail(f"长按空格 {timeout} s 没出现「{LISTEN}」「{PREPARING}」「{SILENT}」中任何一个：屏幕上 {[n.text for n in s.nodes if n.text][:8]}，"
                   f"{d.top_activity()[-40:]}")
    return x, y


def dictate(d, clip):
    """hold space, say the clip, let go once it is heard; returns (what showed while listening and
    after the lift, field)"""
    x, y = hold_space(d)
    time.sleep(0.3)
    # what shows before anything is said (the keys, the status line): not a recognized text
    before = {n.text for n in d.screen().nodes if n.text and n.pkg == PKG}
    p = d.say(clip, wait=False)
    shown = []   # what the screen showed while it was said and just after
    end = time.time() + 30
    while time.time() < end and p.poll() is None:
        shown += [n.text for n in d.screen().nodes if n.text and n.pkg == PKG and n.text not in shown]
        time.sleep(0.2)
    # the last stretch's silence (1.2 s: the VAD closes it), then up to 4 s for its text to show: on
    # a loaded host the recognizer takes longer than the silence, and VO-02 then saw only 正在听…
    heard = len(shown)
    end = time.time() + 4
    settled = time.time() + 1.2
    texts, steady = None, time.time()
    while time.time() < end:
        s = d.screen()
        shown += [n.text for n in s.nodes if n.text and n.pkg == PKG and n.text not in shown]
        if time.time() >= settled and len(shown) > heard:
            break
        # the final text the same as a live one already in shown: nothing new would ever come, and
        # the status line says 正在听… while the finger is down, so the screen unchanged for 1 s is the
        # cue, but only with a recognized text on it: with none yet the recognizer may still be on the
        # last stretch, and lifting then is the early lift the 4 s are for
        now = [n.text for n in s.nodes if n.text and n.pkg == PKG]
        recognized = any(t not in before and not any(k in t for k in (LISTEN, PREPARING, SILENT, HEARD_NOTHING)) for t in now)
        if now != texts:
            texts, steady = now, time.time()
        elif recognized and time.time() >= settled and time.time() - steady >= 1:
            break
        time.sleep(0.2)
    d.touch(("up", x, y))
    # 没有听清 shows for 1.2 s after the lift (VoiceHoldSession.MESSAGE_MS): caught while settling
    t = settle(d, seen=shown)
    return shown, t


def settle(d, timeout=15, seen=None):
    """the field once 识别中… is over; what the app showed meanwhile is added to [seen]"""
    end = time.time() + timeout
    while time.time() < end:
        s = d.screen()
        if seen is not None:
            seen += [n.text for n in s.nodes if n.text and n.pkg == PKG and n.text not in seen]
        if not anywhere(s, "识别中") and not anywhere(s, LISTEN):
            break
        time.sleep(0.3)
    time.sleep(0.5)
    return d.field_text() or ""


def host_calm(timeout=30):
    """wait (at most [timeout] s) till the host's 1-minute load is under its core count: with the
    host saturated (other checkouts' Gradle) the emulator's vCPUs starve, the guest's audio HAL logs
    "pcm_readi was late delivering frames, inserting … silence" for every 16 ms and the app hears
    digital silence (envPeak -120 dBFS, no segment) though the clip played into uat-mic"""
    end = time.time() + timeout
    while time.time() < end and os.getloadavg()[0] >= os.cpu_count():
        time.sleep(5)


def retried(d, clip, ok, before=""):
    """dictate until ok(field) holds, three times at most: the emulator's microphone drops frames
    when the host is busy (the HAL's "late delivering frames, inserting … silence"). What a miss
    added after before is taken back between tries. Returns (shown, field, tries, misses)"""
    misses, shown, t = [], [], before
    hal = False   # the last try lost to the sound card: the result is the emulator's, blocked
    for n in range(1, 4):
        host_calm()
        d.sh("logcat -c")
        hal = True
        try:
            shown, t = dictate(d, clip)
        except HalBroke:   # a try lost to the emulator's sound card, as a dropped frame's
            misses.append("（模拟器声卡出错）")
            time.sleep(5)
            continue
        if hal_errors(d) and not ok(t):   # it failed after 准备中… had shown: not the app's miss either
            misses.append(f"（模拟器声卡出错）{t[len(before):]}")
            if len(t) > len(before):
                d.bksp(len(t) - len(before))
                t = before
            time.sleep(5)
            continue
        hal = False
        if ok(t) or not t.startswith(before):
            return shown, t, n, misses
        misses.append(t[len(before):])
        if len(t) > len(before):
            d.bksp(len(t) - len(before))
    if hal:
        raise Blocked(f"3 次里最后一次模拟器声卡出错（pcm_readi failed），不算 App 的：{misses}")
    return shown, t, 3, misses


RATE = 16000
# how long after the speech's start the VAD's "voice speech started" line comes (its minimum speech
# and a window or two); a first guess, the tries' own offsets move the lift after it
LIFT_CUE_LAG = 0.35
# the HAL's "late delivering frames" lines a try may have and still count (a good try has 0 to 7)
HAL_LATE_MOST = 20
# whether the build logs the cue (a debug build of the voice tail; not R15): set from the first try
cued = False
NOTHING_HEARD = "nothing heard"   # lift_early's offset when the debug log has no speech segment
# how far the VAD's segment.start falls before the speech's real start: sherpa puts the start at the
# window it fired in less two windows and the minimum speech (the design's offset formula, + 1024)
VAD_START_LAG = 1024


def span(clip):
    """the clip's speech start and end, seconds from its start (voice-clips.py's <clip>.json)"""
    f = os.path.join(CLIPS, f"{clip}.json")
    if not os.path.exists(f):
        raise Blocked(f"没有 {f}：重跑 uat/tools/voice-clips.py")
    with open(f) as j:
        v = json.load(j)
    return v["speech_start_s"], v["speech_end_s"]


def capture_log(d):
    """the debug build's one line at the microphone's close (VoiceListener): numbers, None if absent"""
    line = d.sh("logcat -d | grep 'voice capture:' | tail -1").strip()
    if not line:
        return None
    v = dict(re.findall(r"(\w+(?:\.\w+)?)=(\S+)", line))
    num = lambda k: None if v.get(k) in (None, "null") else float(v[k])
    got = {k: num(k) for k in ("liftFrame", "stopAt", "read", "segment.start", "segments", "envPeak", "peakGainDb")}
    # the emulator's sound card failing mid-capture ("pcm_readi failed with 'cannot read/write stream
    # data: I/O error'"): the HAL hands the app silence from there on, which can leave a speech
    # segment of a syllable or two and an empty text. The emulator's, not the app's
    got["halErrors"] = hal_errors(d)
    # and running late: past a few, the silence it pads with shifts the record's frames against the
    # speech, so the lift's offset is not where the finger was (seen: +83, -716 ms around -450)
    got["halLate"] = int(d.sh("logcat -d | grep -c 'late delivering frames'").strip() or 0)
    return got


def tries_note(n, misses):
    return "" if n == 1 else f"（第 {n} 次；之前听成 {misses}）"


# also on the JVM (VoiceHoldUatTest.theFirstHoldAsksForTheMicrophone); the system dialog and the way back
# to the field after 允许 are the framework's, which Robolectric does not run
@case("VO-01", base="B0", mutates=True, needs=EV)
def vo01(d):
    emu_only(d)
    d.sh(f"pm revoke {PKG} android.permission.RECORD_AUDIO")
    x, y = d.keys(refresh=True)["space"]
    d.touch(("down", x, y), ("sleep", 1.2), ("up", x, y))
    allow = d.wait(lambda s: next((n for n in s.nodes if n.text in ("仅在使用该应用时允许", "使用时允许", "允许")),
                                  None), 10, what="the permission dialog")
    d.tap(allow.c, 1.5)
    s = d.screen()
    check(d.field(s) and s.find(rid="keyboard_view", where="ime"), f"允许后没回到输入框：{d.top_activity()}")
    granted = "granted=true" in d.sh(f"dumpsys package {PKG} | grep -m1 'RECORD_AUDIO: granted'")
    check(granted, "允许后麦克风权限仍没给")
    _, t, n, miss = retried(d, "nihao", lambda t: plain(t) == "你好")
    check(plain(t) == "你好", f"再长按空格说「你好」，框里「{t}」{tries_note(n, miss)}")
    return f"请求权限后允许，回到框里；再说「你好」→「{t}」{tries_note(n, miss)}"


@case("VO-02", base="B0-语音", mutates="learn", needs=EV)
def vo02(d):
    shown, t, n, miss = retried(d, "jintian", lambda t: plain(t) == "今天天气很好")
    live = [x for x in shown if "今天" in x]
    check(plain(t) == "今天天气很好", f"松开后框里「{t}」{tries_note(n, miss)}")
    check(live, f"说的时候屏幕上没显示听到的内容：{shown[:8]}")
    return f"听的时候显示 {live[:2]}；框里「{t}」{tries_note(n, miss)}"


case("VO-03", needs="jvm:VoiceHoldUatTest.slidingUpDropsIt")(lambda d: None)


case("VO-04", needs="jvm:VoiceHoldUatTest.thePanelsMicrophoneTypesWhatIsSaid")(lambda d: None)


case("VO-05", needs="jvm:VoiceHoldUatTest.slidingOffThePanelsMicrophoneDropsIt")(lambda d: None)


case("VO-06", needs="jvm:VoiceHoldUatTest.numbersAreWrittenInDigits")(lambda d: None)


case("VO-07", needs="jvm:VoiceHoldUatTest.wordsWithNumeralsAreLeftAlone")(lambda d: None)


case("VO-08", needs="jvm:VoiceHoldUatTest.englishInChineseIsTypedAsWritten")(lambda d: None)


# also on the JVM (VoiceHoldUatTest.aCoughTypesNothing); this one, the room's own noise through the gain:
# digital silence shows no gain, so real noise is played
@case("VO-09", base="B0-语音", mutates="learn", needs=EV)
def vo09(d):
    emu_only(d)
    lost = []
    for _ in range(4):
        host_calm()
        d.sh("logcat -c")   # this try's line only, not one an earlier case left
        try:
            shown, t = dictate(d, "room")
        except HalBroke:
            lost.append("准备中…时声卡出错")
            time.sleep(5)   # its failures come in runs: let it settle
            continue
        log = capture_log(d)
        # with the debug log, the noise must have reached the app at its level (-60 dBFS played)
        # through a working sound card: digital silence (-120) or a failed HAL leaves the field
        # empty too and says nothing of the gain
        if log and log["halErrors"]:
            lost.append("声卡出错")
        elif log and log["envPeak"] is not None and not -70 <= log["envPeak"] <= -50:
            lost.append(f"env {log['envPeak']} dBFS")
        else:
            break
        if plain(t):
            d.bksp(len(t))
        time.sleep(5)
    else:
        raise Blocked(f"4 次噪声都没按电平送到 App：{lost}")
    check(plain(t) == "", f"只播 3 s 底噪（约 -60 dBFS），框里「{t}」")
    check(HEARD_NOTHING in shown, f"只播底噪，松开后没显示「{HEARD_NOTHING}」：{shown[:8]}")
    gain = f"，env 峰值 {log['envPeak']} dBFS 处增益 {log['peakGainDb']:+.1f} dB" if log and log["peakGainDb"] is not None else ""
    return f"底噪 3 s：不上屏，显示「{HEARD_NOTHING}」{gain}" + (f"（之前不算：{lost}）" if lost else "")


# also on the JVM (VoiceUatTest.aBlockedWordIsNotTypedFromVoice), which checks the string handed to the
# recognizer; this one, that the stream with searchBlocked changes what is heard
@case("VO-10", base="B0-设置", mutates=True, needs=EV)
def vo10(d):
    emu_only(d)
    script("13-words").block_word(d, "天气")
    d.open_page("text")
    _, t, n, miss = retried(d, "jintian", lambda t: plain(t).startswith("今天") and plain(t).endswith("很好"))
    check(plain(t).startswith("今天") and plain(t).endswith("很好"), f"没听全：「{t}」{tries_note(n, miss)}")
    check("天气" not in t, f"屏蔽「天气」后说「今天天气很好」，框里「{t}」")
    return f"框里「{t}」{tries_note(n, miss)}"


case("VO-11", needs="jvm:VoiceKeyboardUatTest.aPasswordFieldHasNoVoice")(lambda d: None)


case("VO-12", needs="jvm:VoiceHoldUatTest.onlyTheFieldItWasSaidForGetsIt")(lambda d: None)


case("VO-13", needs="jvm:VoiceHoldUatTest.holdingTheSpaceForThePickerDoesNotTalk")(lambda d: None)


# also on the JVM (VoiceUatTest.aWordAddedByHandIsListenedForHardest); this one, that the stream's hotwords
# change what is heard
@case("VO-14", base="B0-设置", mutates=True, needs=EV)
def vo14(d):
    emu_only(d)
    script("13-words").add_word(d, "澜境花园")
    d.open_page("text")
    _, t, n, miss = retried(d, "lanjing", lambda t: "澜境花园" in t)
    check("澜境花园" in t, f"加了用户词「澜境花园」后说「我住在澜境花园」，框里「{t}」{tries_note(n, miss)}")
    return f"框里「{t}」{tries_note(n, miss)}"


case("VO-15", needs="jvm:SettingsPagesUatTest.theVoiceModelsDataIsCredited")(lambda d: None)


case("VO-16", needs="jvm:VoiceHoldUatTest.holdingAgainWhileDidntCatchThatShowsListens")(lambda d: None)


case("VO-17", needs="jvm:VoiceUatTest.onlyTheWordAddedIsListenedFor")(lambda d: None)


case("VO-18", needs="jvm:VoiceUatTest.anImportedWordIsNotListenedForTillAddedByHand")(lambda d: None)


case("VO-19", needs="jvm:VoiceHoldUatTest.whatIsSaidIsContextNotLearned")(lambda d: None)


case("VO-20", needs="jvm:VoiceUatTest.aNameTypedTwiceIsListenedFor")(lambda d: None)


case("VO-21", needs="jvm:VoiceUatTest.aWordDeletedOrBlockedIsNotListenedFor")(lambda d: None)


case("VO-22", needs="jvm:VoiceUatTest.habitsOffListensForNoneOfTheirs")(lambda d: None)


case("VO-27", needs="jvm:VoiceHoldUatTest.aSilentMicrophoneIsSaidAndListeningComesWithTheFirstSound")(lambda d: None)


def input_lag(d):
    """how long the lift takes to send (two sendevents), less adb's own round trip: VO-23's lift is
    sent that much earlier"""
    def took(cmd):
        t = time.time()
        d.sh(cmd)
        return time.time() - t
    # a bare SYN_REPORT: the start-up of the same binary, no finger moved
    dev = touch_screen(d)[0]
    syn = f"sendevent {dev} 0 0 0; sendevent {dev} 0 0 0"
    return max(0.0, min(took(syn) for _ in range(3)) - min(took("true") for _ in range(3)))


def keep_log(d, clip, why):
    """the whole logcat of a try that typed nothing though speech was heard, for its why"""
    f = os.path.join(OUT, "voice-logs", f"{clip}-{time.strftime('%H%M%S')}-{why}.txt")
    os.makedirs(os.path.dirname(f), exist_ok=True)
    with open(f, "w") as o:
        o.write(d.sh("logcat -d"))
    return f


def lift_early(d, clip, before_s):
    """hold space, play the clip and lift [before_s] before its speech ends; returns (field, offset ms
    of the lift from the speech's end as the debug log puts it or None (a build without the log: R15),
    whether timestamped). With the debug log the lift is timed on the device from the app's own
    "voice speech started" line (the VAD's, LIFT_CUE_LAG after the speech's start): the clip's way
    from the host's player into the guest varies by ±500 ms, the cue's by tens. Without the log
    (R15) from the moment the player starts, as before"""
    global cued
    start, end = span(clip)
    d.sh("logcat -c")
    try:
        hold_space(d, raw=True)
    except HalBroke:
        return d.field_text() or "", NOTHING_HEARD, False
    time.sleep(0.3)
    if cued:
        # 450 ms, not before_s: that one is the host-timed lift's, moved by its misses
        wait = max(0.0, end - start - LIFT_CUE_LAG - 0.45)
        cmd = f"logcat -m 1 -e 'voice speech started' >/dev/null; sleep {wait:.3f}; {raw_up(d)}"
    else:
        cmd = f"sleep {max(0.0, end - before_s):.3f}; {raw_up(d)}"
    up = subprocess.Popen(["adb", "-s", d.serial, "shell", cmd])
    p = d.say(clip, wait=False)
    try:
        up.wait(end + 5)
    except subprocess.TimeoutExpired:   # no cue: nothing was heard; the finger lifted all the same
        up.kill()
        d.sh(raw_up(d))
    p.wait(30)
    cued = cued or bool(d.sh("logcat -d | grep -c 'voice speech started'").strip() not in ("", "0"))
    t = settle(d)
    log = capture_log(d)
    if not log or log["segment.start"] is None or log["stopAt"] is None:
        return t, None, False
    if log["segment.start"] < 0 or log["halErrors"] or log["halLate"] > HAL_LATE_MOST:   # no speech, or the sound card failed: the clip never arrived whole
        return t, NOTHING_HEARD, False
    # split in two: segment.start is the second one's, and the offset below would be off by the
    # first one's length (a build without the count: None, taken as one)
    if (log["segments"] or 1) > 1:
        return t, NOTHING_HEARD, False
    if not plain(t):
        keep_log(d, clip, "empty")
    # no timestamp: the frames read when the capture thread saw the lift, which adds the capture
    # path's latency to the offset
    lift = log["liftFrame"] if log["liftFrame"] is not None else log["stopAt"] - 0.3 * RATE
    speech_end = log["segment.start"] + VAD_START_LAG + (end - start) * RATE
    return t, (lift - speech_end) * 1000 / RATE, log["liftFrame"] is not None


def verdict(clip, results):
    """three counted tries, judged by what was typed alone, so the same script runs on a build
    without the debug log (R15, the red run) and with it: 3 of 3 right passes; 2 or more wrong is
    the design's red; one wrong is neither and fails too"""
    wrong = sum(not ok for ok, _ in results)
    notes = "、".join(n for _, n in results)
    if wrong:
        raise Fail(f"{clip}：3 次里 {wrong} 次不对（{'红：≥ 2 次失败' if wrong >= 2 else '不稳定'}）：{notes}")
    return f"{clip}：3/3 对：{notes}"


def counted_tries(d, clip, attempt, most=5):
    """[attempt]() → (ok, note, counted) until three are counted, [most] attempts at most"""
    results, every = [], []
    for _ in range(most):
        host_calm()
        before = d.field_text() or ""
        ok, note, counted, t = attempt(before)
        every.append(note)
        if counted:
            results.append((ok, note))
            if len(results) == 3:
                return results
        if len(t) > len(before):   # taken back either way: the next try starts from the same text
            d.bksp(len(t) - len(before))
    raise Blocked(f"{clip}：{most} 次里只有 {len(results)} 次算数：{every}")


@case("VO-23", base="B0-语音", mutates="learn", needs=EV)
def vo23(d):
    emu_only(d)
    out = []
    for clip, want in (("jintian", "今天天气很好"), ("zouba", "我们走吧")):
        before_s = 0.45 + input_lag(d)

        def attempt(before):
            nonlocal before_s
            t, off, stamped = lift_early(d, clip, before_s)
            got = t[len(before):] if t.startswith(before) else t
            ok = plain(got) == want
            if off is NOTHING_HEARD:   # the emulator's HAL failed: says nothing about the tail
                time.sleep(5)   # its failures come in runs: let it settle
                return ok, f"没收到声音、声卡出错或语音被切成两段「{got}」（不算）", False, t
            if off is None:   # no log (R15): the time cannot be checked, the text is the result
                return ok, f"「{got}」", True, t
            note = f"{off:+.0f} ms{'' if stamped else '（无时间戳）'}「{got}」"
            if -500 <= off <= -400:
                return ok, note, True, t
            # off the window: not counted. Timed from the host's player (no cue: R15's first try)
            # the next lift moves by half what this one missed by: `input`'s start and the clip's
            # way in vary by ±300 ms there, wider than the 100 ms window. Timed from the cue it
            # stays: those land within ±10 ms of each other (-466, -477, -483, -474), so a try off
            # the window is one the emulator's audio upset, and moving after it chased that noise
            if not cued:
                before_s += (off + 450) / 2000
            return ok, note + "（不在区间，不算）", False, t

        out.append(verdict(clip, counted_tries(d, clip, attempt, most=10)))
    return "；".join(out)


@case("VO-24", base="B0-语音", mutates="learn", needs=EV)
def vo24(d):
    emu_only(d)
    out = []
    for clip, want in (("zouba-q44", "我们走吧"), ("nihao-q44", "你好")):
        def attempt(before):
            d.sh("logcat -c")
            try:
                _, t = dictate(d, clip)
            except HalBroke:
                time.sleep(5)   # its failures come in runs: let it settle
                return False, "准备中…时模拟器声卡出错（不算）", False, d.field_text() or ""
            got = t[len(before):] if t.startswith(before) else t
            ok = plain(got) == want
            log = capture_log(d)
            if not log or log["envPeak"] is None:   # no log (R15): the text is the result
                return ok, f"「{got}」", True, t
            env, gain = log["envPeak"], log["peakGainDb"]
            note = f"env {env} dBFS、{gain:+.1f} dB「{got}」"
            if log["halErrors"]:   # the emulator's sound card failed mid-capture
                time.sleep(5)   # its failures come in runs: let it settle
                return ok, note + "（模拟器声卡出错，不算）", False, t
            # the level in the guest off: not counted. The clip's own peak lands at -55 ± 1 dBFS
            # (measured -55.3 to -54.3), so the top of the window has 5 dB of room; the gain is
            # what says it was quiet speech
            if -65 <= env <= -50 and gain >= 15:
                return ok, note, True, t
            return ok, note + "（电平不对，不算）", False, t

        out.append(verdict(clip, counted_tries(d, clip, attempt, most=10)))
    return "；".join(out)


# the rule itself on the JVM (VoiceHoldUatTest.pressingAgainEndsTheTailAndCountsForNothing); on the
# device a smoke: the first sentence typed, and where the press fell written down
@case("VO-25", base="B0-语音", mutates="learn", needs=EV)
def vo25(d):
    emu_only(d)
    d.sh("logcat -c")
    x, y = hold_space(d)
    time.sleep(0.3)
    d.say("nihao")
    time.sleep(1.2)
    before = d.field_text() or ""
    # lifted and pressed again at once, the second press held as long as a long press
    d.touch(("up", x, y), ("sleep", 0.05), ("down", x, y), ("sleep", 1.0), ("up", x, y))
    t = settle(d)
    got = t[len(before):] if t.startswith(before) else t
    check(plain(got) == "你好", f"松开后马上再按住，上屏「{got}」，要「你好」")
    log = capture_log(d) or {}
    read, stop = log.get("read"), log.get("stopAt")
    when = "不明（没有调试日志）" if read is None or stop is None else "续录中（停在 stopAt 之前）" if read < stop else "识别中"
    return f"上屏「{got}」；再按落在{when}（read={read}, stopAt={stop}）"


# the rule on the JVM (VoiceHoldUatTest, VoiceOnsetTest); on the device, that 正在听 waits for the
# microphone's first sample not zero, and how long that took (the debug log's fromStartMs)
@case("VO-26", base="B0-语音", mutates="learn", needs=EV)
def vo26(d):
    emu_only(d)
    broke = 0
    for _ in range(4):
        r = vo26_try(d)
        if r is not None:
            return r + (f"（之前 {broke} 次模拟器声卡出错，不算）" if broke else "")
        broke += 1
        time.sleep(5)   # its failures come in runs: let it settle
    raise Blocked(f"4 次都是模拟器声卡出错（pcm_readi failed）")


def vo26_try(d):
    """one hold: 准备中… till the clip's first sound, then 正在听…, and the text; None if the
    emulator's sound card failed. The emulator's microphone reads exact zeroes while nothing plays
    (a phone's never does), so there 正在听 must wait for the clip"""
    quiet(d)
    d.sh("logcat -c")
    try:
        hold_space(d, raw=True)
    except HalBroke:
        return None
    s = d.screen()
    # the emulator reads zeroes till the clip: 2 s of them say 麦克风没有声音 (VO-27), still before 正在听
    first = LISTEN if anywhere(s, LISTEN) else PREPARING
    before = d.field_text() or ""
    early = d.sh("logcat -d | grep -c 'voice ready'").strip() not in ("", "0")
    p = d.say("nihao", wait=False)
    seen, end = False, time.time() + 10
    while time.time() < end and not seen:
        seen = bool(anywhere(d.screen(), LISTEN))
        time.sleep(0.1)
    p.wait(30)
    time.sleep(1.2)
    d.sh(raw_up(d))
    t = settle(d)
    got = t[len(before):] if t.startswith(before) else t
    if hal_errors(d):
        if len(t) > len(before):
            d.bksp(len(t) - len(before))
        return None
    ready = d.sh("logcat -d | grep -o 'voice ready: fromStartMs=[0-9]*' | tail -1").strip()
    # the rule: 正在听 never before the first sample not zero. The emulator's microphone mostly reads
    # exact zeroes till the clip plays, but not always (a HAL's padding): either order of the two
    # is fine, 正在听 without the log is not
    check(first == PREPARING or early, f"按下后第一眼「{first}」，却没有 voice ready 日志：正在听亮得比麦克风早")
    check(seen and ready, f"播「你好」时{'' if seen else '没'}出现「{LISTEN}」，{ready or '没有 voice ready 日志'}")
    check(plain(got) == "你好", f"说「你好」，框里「{got}」")
    how = f"按下「{PREPARING}」，片段一响变「{LISTEN}」" if first == PREPARING else f"按下时麦克风已有声音，直接「{LISTEN}」"
    return f"{how}（{ready.split(': ')[1]}，从开麦算）；框里「{got}」"


# the rule on the JVM (VoiceTailEnterTest, VoiceHoldUatTest); on the device a smoke as VO-25's: in
# the chat box, the panel's ↵ right after the lift sends the sentence, not an empty box before it
@case("VO-28", base="B0-语音", mutates="learn", needs=EV, page="chat")
def vo28(d):
    emu_only(d)
    lost = []
    for _ in range(3):
        r = vo28_try(d)
        if r is not None:
            return r + (f"（之前 {len(lost)} 次模拟器声卡出错，不算）" if lost else "")
        lost.append("声卡出错")
        d.open_page("chat")   # a fresh box and log for the next try
        time.sleep(5)    # its failures come in runs: let it settle
    raise Blocked("3 次都是模拟器声卡出错（pcm_readi failed）")


def vo28_try(d):
    """one sentence said and ↵ right after the lift; None if the emulator's sound card failed"""
    host_calm()
    quiet(d)
    d.sh("logcat -c")
    s = d.screen()
    voice = s.find(desc="语音输入", where="ime")
    if not voice:
        raise Blocked("工具栏上没有「语音输入」（工具栏收起了？）")
    d.tap(voice.c, 0.8)
    s = d.screen()
    mic, enter = s.find(desc="按住 说话", where="ime"), s.find(desc="回车", where="ime")
    check(mic and enter, "语音面板上没找到话筒或 ↵")
    x, y = mic.c
    d.touch(("down", x, y))
    d.say("zouba")
    time.sleep(0.3)
    ex, ey = enter.c
    # lifted, and ↵ at once: inside the 300 ms tail
    d.touch(("up", x, y), ("sleep", 0.05), ("down", ex, ey), ("sleep", 0.05), ("up", ex, ey))
    time.sleep(3)
    s = d.screen()
    box = d.field_text(s) or ""
    # the page's log skips an empty send, so an empty ↵ before the sentence shows as the sentence
    # left in the box, not as a line of its own
    sent = sum((n.text or "").count("我们走吧") for n in s.app() if n.cls != "EditText")
    if sent == 0 and hal_errors(d):
        return None
    check(sent == 1 and not plain(box), f"聊天记录里「我们走吧」{sent} 次，聊天框「{box}」")
    return "发出「我们走吧」一次，聊天框已清空"
