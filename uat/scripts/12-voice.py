"""uat/12-voice.md (语音输入, with the voice pack: uat.py run imports it unless --no-voice). What is "said" is a clip of
uat/tools/voice-clips.py read by the debug build in place of the microphone (Device.say, the
clip microphone: VoiceClipMic). The .md is the source of truth."""
import json
import os
import re
import time

from uat import CLIPS, OUT, PKG, Blocked, Fail, case, check, script

LISTEN, HEARD_NOTHING, PREPARING, SILENT = "正在听…", "没有听清", "准备中…", "麦克风没有声音"
EV = "voice:语音要导入语音包和 debug 包的片段麦克风（uat.py run 默认导入；--no-voice 时不跑）"


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


# the emulator's touchscreen written with sendevent (root there): a native binary starts far faster than `input`
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


def hold_space(d, timeout=60, wait_quiet=True, raw=False):
    """finger down on the space key until the hold shows (准备中… or 正在听…; the first use after a
    reset loads the models); returns the space key's centre. raw: through sendevent, to be lifted with raw_up"""
    if wait_quiet:
        quiet(d)
    d.clip_mic()
    x, y = d.keys(refresh=True)["space"]
    if raw:
        d.sh(raw_down(d, x, y))
    else:
        d.touch(("down", x, y))

    def lift():
        if raw:
            d.sh(raw_up(d))
        else:
            d.touch(("up", x, y))

    def listening(s):
        # 准备中… is held too: the models may still be loading
        return anywhere(s, LISTEN) or anywhere(s, PREPARING) or anywhere(s, SILENT)

    try:
        d.wait(listening, timeout, step=0.2, what=LISTEN)
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
    try:
        while time.time() < end and p.poll() is None:
            shown += [n.text for n in d.screen().nodes if n.text and n.pkg == PKG and n.text not in shown]
            time.sleep(0.2)
    finally:
        p.kill()   # done or not, nothing left streaming into the next case
    # the last segment's silence, then up to 4 s for its text: on a loaded host recognition outlasts the silence
    heard = len(shown)
    end = time.time() + 4
    settled = time.time() + 1.2
    texts, steady = None, time.time()
    while time.time() < end:
        s = d.screen()
        shown += [n.text for n in s.nodes if n.text and n.pkg == PKG and n.text not in shown]
        if time.time() >= settled and len(shown) > heard:
            break
        # the screen unchanged for 1 s with recognized text on it is the cue; with none yet the recognizer may still be working
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


RATE = 16000
NOTHING_HEARD = "nothing heard"   # lift_early's offset when the debug log has no speech segment


def span(clip):
    """the clip's speech start and end, seconds from its start (voice-clips.py's <clip>.json)"""
    f = os.path.join(CLIPS, f"{clip}.json")
    if not os.path.exists(f):
        raise Blocked(f"没有 {f}：重跑 uat/tools/voice-clips.py")
    with open(f) as j:
        v = json.load(j)
    return v["speech_start_s"], v["speech_end_s"]


def capture_log(d):
    """the debug build's lines at the microphone's close and the recognizer's end (VoiceListener): numbers, None if absent"""
    line = d.sh("logcat -d | grep 'voice capture:' | tail -1").strip()
    if not line:
        return None
    line += " " + d.sh("logcat -d | grep 'voice segments:' | tail -1").strip()
    v = dict(re.findall(r"(\w+(?:\.\w+)?)=(\S+)", line))
    num = lambda k: None if v.get(k) in (None, "null") else float(v[k])
    return {k: num(k) for k in ("liftFrame", "stopAt", "read", "speechStart", "segments", "envPeak", "peakGainDb")}


# also on the JVM; the system dialog and the way back to the field are the framework's, which Robolectric does not run
@case("VO-01", base="B0", mutates=True, needs=EV)
def vo01(d):
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
    _, t = dictate(d, "nihao")
    check(plain(t) == "你好", f"再长按空格说「你好」，框里「{t}」")
    return f"请求权限后允许，回到框里；再说「你好」→「{t}」"


case("VO-37", needs="manual:系统权限对话框的拒绝、关掉、转屏、不再询问，手工跑")(lambda d: None)


@case("VO-02", base="B0-语音", mutates="learn", needs=EV)
def vo02(d):
    shown, t = dictate(d, "jintian")
    live = [x for x in shown if "今天" in x]
    check(plain(t) == "今天天气很好", f"松开后框里「{t}」")
    check(live, f"说的时候屏幕上没显示听到的内容：{shown[:8]}")
    return f"听的时候显示 {live[:2]}；框里「{t}」"


def tap_mic(d):
    """the toolbar's microphone tapped: the mask over the keys; returns its hint (the mask has no
    button: tapping it, or the microphone, ends the talk)"""
    quiet(d)
    d.clip_mic()
    voice = d.screen().find(desc="语音输入", where="ime")
    if not voice:
        raise Blocked("工具栏上没有「语音输入」（工具栏收起了？）")
    d.tap(voice.c, 0.8)
    hint = d.screen().find(text="点这里或麦克风 结束", where="ime")
    check(hint, "点麦克风后没有盖层（没找到它的提示）")
    return hint


def mask_gone(d, timeout=15):
    """the field once the mask is gone (what was heard recognized and typed)"""
    try:
        d.wait(lambda s: not s.find(text="点这里或麦克风 结束", where="ime"), timeout, step=0.3, what="盖层收起")
    except Blocked:
        raise Fail(f"结束后 {timeout} s 盖层还在")
    time.sleep(0.5)
    return d.field_text() or ""


# also on the JVM; here the real recognizer and InputConnection, stopped by the microphone and then by the mask
@case("VO-04", base="B0-语音", mutates="learn", needs=EV)
def vo04(d):
    tap_mic(d)
    s = d.screen()
    check(not anywhere(s, "松开"), "点麦克风后出现了长按的语音卡片")
    d.say("zouba")
    time.sleep(1.0)
    voice = d.screen().find(desc="语音输入", where="ime")
    check(voice, "说着时工具栏上没有麦克风（盖层盖住了工具栏？）")
    d.tap(voice.c, 0.1)
    t1 = mask_gone(d)
    check(plain(t1) == "我们走吧", f"点麦克风说「我们走吧」再点麦克风，框里「{t1}」")
    hint = tap_mic(d)
    d.say("nihao")
    time.sleep(1.0)
    d.tap(hint.c, 0.1)
    t2 = mask_gone(d)
    check(t2.startswith(t1) and plain(t2[len(t1):]).lstrip("，,。. ") == "你好", f"再点麦克风说「你好」点盖层，框里「{t2}」")
    check(d.screen().find(rid="keyboard_view", where="ime"), "结束后键盘没回来")
    return f"点麦克风结束「{t1}」；点盖层结束「{t2}」"


# also on the JVM; here the room's own noise through the gain, as digital silence shows no gain
@case("VO-09", base="B0-语音", mutates="learn", needs=EV)
def vo09(d):
    d.sh("logcat -c")   # this try's line only, not one an earlier case left
    # the room all through the hold, lifted at 6 s while it plays: no floor after it for the recognizer to end on
    x, y = hold_space(d)
    p = None
    shown = []
    try:
        p = d.say("room-long", wait=False)
        end = time.time() + 6
        while time.time() < end:
            shown += [n.text for n in d.screen().nodes if n.text and n.pkg == PKG and n.text not in shown]
            time.sleep(0.2)
        d.touch(("up", x, y))
        t = settle(d, seen=shown)
    finally:
        if p:
            p.kill()
    log = capture_log(d)
    # with the debug log, the noise must have reached the app at its level (-60 dBFS played)
    if log and log["envPeak"] is not None:
        check(-70 <= log["envPeak"] <= -50, f"底噪没按电平送到 App：env {log['envPeak']} dBFS")
    check(plain(t) == "", f"按住 6 s 一直播底噪（约 -60 dBFS），框里「{t}」")
    check(HEARD_NOTHING in shown, f"只播底噪，松开后没显示「{HEARD_NOTHING}」：{shown[:8]}")
    gain = f"，env 峰值 {log['envPeak']} dBFS 处增益 {log['peakGainDb']:+.1f} dB" if log and log["peakGainDb"] is not None else ""
    return f"底噪贯穿按住 6 s：不上屏，显示「{HEARD_NOTHING}」{gain}"


# also on the JVM, which checks the string handed over; here, that searchBlocked changes what is heard
@case("VO-10", base="B0-设置", mutates=True, needs=EV)
def vo10(d):
    script("13-words").block_word(d, "天气")
    d.open_page("text")
    _, t = dictate(d, "jintian")
    check(plain(t).startswith("今天") and plain(t).endswith("很好"), f"没听全：「{t}」")
    check("天气" not in t, f"屏蔽「天气」后说「今天天气很好」，框里「{t}」")
    return f"框里「{t}」"


# also on the JVM; here, that the stream's hotwords change what is heard
@case("VO-14", base="B0-设置", mutates=True, needs=EV)
def vo14(d):
    script("13-words").add_word(d, "澜境花园")
    d.open_page("text")
    _, t = dictate(d, "lanjing")
    check("澜境花园" in t, f"加了用户词「澜境花园」后说「我住在澜境花园」，框里「{t}」")
    return f"框里「{t}」"


# a hotword begun (酒店式公寓) but not finished at a segment's end adds nothing; no JVM test, the native recognizer's
@case("VO-56", base="B0-设置", mutates=True, needs=EV)
def vo56(d):
    script("13-words").add_word(d, "酒店式公寓")
    d.open_page("text")
    _, t = dictate(d, "jiujian")
    check("九键" in t and not t.startswith("酒店"), f"加了用户词「酒店式公寓」后说「九键输入法」，框里「{t}」")
    return f"框里「{t}」"


# also on the JVM (VoiceHomophonesTest); this one, the real recognizer's 徐静 made the user's 徐婧
@case("VO-38", base="B0-设置", mutates=True, needs=EV)
def vo38(d):
    script("13-words").add_word(d, "徐婧")
    # 攻势 the user's too, so the common 公式 heard is one that could be replaced and is not
    script("13-words").add_word(d, "攻势")
    d.open_page("text")
    out = []
    for clip, want, wrong in (("xujing", "徐婧", "徐静"), ("xujing3", "许静", "徐婧"), ("gongshi", "公式", "攻势")):
        d.clear()
        shown, t = dictate(d, clip)
        check(want in t and wrong not in t, f"说「{clip}」，框里「{t}」，要有「{want}」、没有「{wrong}」")
        if clip == "xujing":
            check(not any("徐静" in s for s in shown if "徐婧" not in s and "一下徐" in s), f"说的时候显示过「徐静」：{shown}")
        out.append(t)
    return "框里 " + " / ".join(f"「{t}」" for t in out)


# 徐静 of a talk deleted to the end and 徐婧 typed again in pinyin: asked 「让语音记住『徐婧』？」
def retyped_asks(d, between=None):
    d.clear()
    _, t = dictate(d, "xujing")
    check("徐静" in t, f"说「麻烦你去找一下徐婧」，框里「{t}」，没有「徐静」可改")
    if between:
        between(d)
    d.bksp(len(t) - t.index("徐静"))
    d.type("xujing")
    try:
        d.pick("徐婧")
    except Fail:
        d.pick("徐")
        d.pick("婧")
    ask = "让语音记住『徐婧』？"
    check(d.wait_text(ask, timeout=5, where="all"), f"重打「徐婧」后没问「{ask}」，框里「{d.field_text()}」")
    return t


# also on the JVM; here the glue: a real talk told to the engine, a real pinyin commit, the notice's 记住 adding the word
@case("VO-41", base="B0-语音", mutates=True, needs=EV)
def vo41(d):
    d.open_page("text")
    t = retyped_asks(d)
    d.tap_text("记住", where="all", scroll=False, after=1.0)
    check(d.wait_text("语音记住了『徐婧』", timeout=3, where="all"), "点记住后没说「语音记住了『徐婧』」")
    bar = d.candidates()
    check(not bar, f"点记住后候选栏还留着旧的联想 {bar}")
    script("13-words").user_lexicon(d, "手动添加的词")
    check(d.wait_text("徐婧", timeout=5), "手动添加的词里没有「徐婧」")
    return f"说出「{t}」，删到句末重打「徐婧」后问了，点记住加进手动添加的词，候选栏清空"


CHROME = "com.android.chrome"


# in Chrome where the emulator has it, else the test page's WebView browser
@case("VO-50", base="B0-语音", mutates=True, needs=EV)
def vo50(d):
    # the test page first: served and reversed to the device (ensure_fixtures), the keyboard up
    d.open_page("text")
    chrome = "package:" in d.sh(f"pm path {CHROME}")
    try:
        return vo50_in(d, chrome)
    finally:
        if chrome:
            # Chrome's debug app and command line undone: the snapshot's later cases and runs see it as it was
            d.sh("am clear-debug-app")
            d.sh("rm -f /data/local/tmp/chrome-command-line")
            d.sh(f"am force-stop {CHROME}")


def vo50_in(d, chrome):
    if not chrome:
        where = "这台没有 Chrome：测试页的 WebView 浏览器"
    else:
        d.sh(f"am set-debug-app --persistent {CHROME}")
        d.sh("echo 'chrome --disable-fre --no-default-browser-check --no-first-run' > /data/local/tmp/chrome-command-line")
        d.sh(f"am force-stop {CHROME}")
        d.reload_n += 1
        d.sh(f"am start -a android.intent.action.VIEW -d 'http://127.0.0.1:8000/fields.html?r={d.reload_n}#text' -p {CHROME}")

        # its offer of notifications (a dialog over the page, the first run's) turned down
        def ready(s):
            no = next((n for n in s.app() if n.pkg == CHROME and n.rid == "negative_button"), None)
            if no:
                d.tap(no.c, 1.0)
                return None
            return next((n for n in s.app() if "EditText" in n.cls and n.pkg == CHROME
                         and n.rid not in ("url_bar", "search_box_text")), None)
        d.tap(d.wait(ready, 30, what="Chrome's page").c, 1.0)
        where = "Chrome"
    t = retyped_asks(d)
    return f"{where}：说出「{t}」，重打「徐婧」后问了（微信、备忘录要在 6T 上跑，待做）"


case("VO-42", needs="jvm:VoiceAskUatTest.notTappedNothingIsAdded、"
     "VoiceEditsTest.asked once in a window, again in the next (Q6)")(lambda d: None)


case("VO-43", needs="jvm:VoiceEditsTest.what is not asked about、VoiceEditsTest.the word to ask about、"
     "VoiceEditsTest.typed by what is no word typed, the window ends (Q5)、"
     "EnginesVoiceEditsTest.nothing is followed where nothing would be learned")(lambda d: None)


case("VO-44", needs="jvm:EnginesVoiceEditsTest.asked again at the place, the first word added gives way (review M4)、"
     "EnginesVoiceEditsTest.a word that does not add leaves the first be (review S8)、"
     "VoiceEditsTest.asked again at the same place, the word it adds takes the first's place (review M4)")(lambda d: None)


case("VO-46", needs="jvm:VoiceAskUatTest.aFieldThatLearnsNothingIsNotFollowed、"
     "EnginesVoiceEditsTest.nothing is followed where nothing would be learned")(lambda d: None)


def nothing_said(d):
    """the space held 2 s with nothing said, let go: nothing goes in"""
    before = d.field_text() or ""
    x, y = hold_space(d)
    time.sleep(2)
    d.touch(("up", x, y))
    t = settle(d)
    check(t == before, f"按住空格什么都没说，框里从「{before}」变成「{t}」")


# also on the JVM; here a real talk that hears nothing between voice's text and its edit
@case("VO-47", base="B0-语音", mutates=True, needs=EV)
def vo47(d):
    d.open_page("text")
    t = retyped_asks(d, between=nothing_said)
    return f"说出「{t}」，再按住空格什么都没说（没上屏），之后删到句末重打「徐婧」照样问"


case("VO-49", needs="jvm:VoiceEditsTest.edits made other ways than Backspace at the end、"
     "VoiceEditsTest.the window ends where what happened is not sure、VoiceEditsTest.an emoji's Backspace is not counted")(lambda d: None)


case("VO-51", needs="jvm:VoiceAskUatTest.howToHaveAWordRememberedIsSaidOnce、"
     "VoiceEditsTest.one character typed again is told once how to have it remembered (Q3)、"
     "EnginesVoiceEditsTest.the once-only hint is not looked for once said")(lambda d: None)


case("VO-52", needs="jvm:VoiceEditsTest.asked once in a window, again in the next (Q6)、"
     "EnginesVoiceEditsTest.undone, what typing the word learned stays (review S3)")(lambda d: None)


@case("VO-54", base="B0-设置", mutates=True, needs=EV)
def vo54(d):
    words = script("13-words")
    words.add_word(d, "徐竫")
    d.open_page("text")
    d.clear()
    _, before = dictate(d, "xujing")
    check("徐竫" in before, f"屏蔽前框里「{before}」，要有你的「徐竫」")
    d.clear()
    words.rare_switch(d, True)
    words.rare_page(d)
    s = d.wait(lambda s: words.rare_box(s, "三级字") and s, timeout=8, step=0.3, what="三级字的组头")
    d.tap(words.rare_box(s, "三级字").c, 0.8)
    d.wait(lambda s: "全部屏蔽" in (words.rare_box(s, "三级字") or words.NO_BOX).desc, timeout=8, step=0.3, what="三级字整组屏蔽")
    d.back(1.0)
    d.open_page("text")
    d.clear()
    _, after = dictate(d, "xujing")
    check("竫" not in after and after, f"屏蔽三级字后框里「{after}」")
    return f"屏蔽前「{before}」，后「{after}」"


# the lift to the text: the encoder's tail (TAIL_PADDING) decoded and polished; the lift at the speech's end
@case("VO-55", base="B0-语音", mutates="learn", needs=EV)
def vo55(d):
    out = []
    for clip, want in (("jintian", "今天天气很好"), ("zouba", "我们走吧")):
        before_s = input_lag(d)
        took = []

        def attempt(before):
            nonlocal before_s
            t, off = lift_early(d, clip, before_s)
            got = t[len(before):] if t.startswith(before) else t
            ok = plain(got) == want
            if off is NOTHING_HEARD:
                return ok, f"没听到语音或语音被切成两段「{got}」（不算）", False, t
            ms = re.search(r"stopToSaidMs=(-?\d+)", d.sh("logcat -d | grep 'voice timing:' | tail -1"))
            check(ms and int(ms.group(1)) >= 0, "没有调试日志的 voice timing 一行")
            note = f"{off:+.0f} ms 松开，{ms.group(1)} ms 出字「{got}」"
            if -100 <= off <= 100:
                took.append(int(ms.group(1)))
                return ok, note, True, t
            before_s += off / 2000
            return ok, note + "（不在区间，不算）", False, t

        out.append(verdict(clip, counted_tries(d, clip, attempt, most=10)))
        median = sorted(took)[len(took) // 2]
        # a loose bar: the same emulator's medians vary twice over; the phone's is the gate
        check(median <= 1500, f"{clip}：松开到出字的中位数 {median} ms，超过 1500 ms（字对只是慢：只开一台模拟器重跑）：{took}")
    return "；".join(out)


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
    of the lift from the speech's end as the debug log puts it). The lift is
    timed on the device from the clip microphone's "voice clip started" line: the clip's start as
    its frames are read"""
    _, end = span(clip)
    d.sh("logcat -c")
    hold_space(d, raw=True)
    time.sleep(0.3)
    # the logcat wait begun in the same shell before the queueing, or it could miss the line; no line in end + 5 s: lifted all the same
    cmd = (f"timeout {end + 5:.0f} logcat -m 1 -e 'voice clip started: {clip}.wav' >/dev/null; "
           f"sleep {max(0.0, end - before_s):.3f}; {raw_up(d)}")
    p = d.say(clip, wait=False, alongside=cmd)
    try:
        p.wait(30)
    finally:
        p.kill()
    t = settle(d)
    log = capture_log(d)
    check(log and log["stopAt"] is not None, f"没有调试日志的 voice capture 一行：{log}")
    if log["speechStart"] is None:   # no speech heard: the offset cannot be measured
        return t, NOTHING_HEARD
    # split at an endpoint: a pause in the clip, the speech's end then not the clip's
    if (log["segments"] or 1) > 1:
        return t, NOTHING_HEARD
    if not plain(t):
        keep_log(d, clip, "empty")
    # the clip microphone gives no timestamp: the frames read when the capture thread saw the lift
    check(log["liftFrame"] is None, f"片段麦克风下不该有时间戳：liftFrame={log['liftFrame']}")
    lift = log["stopAt"] - 0.3 * RATE
    # the speech's end from the clip's start frame, not from speechStart, as the first token varies between tries
    at = re.search(rf"voice clip started: {clip}\.wav frame=(\d+)", d.sh("logcat -d | grep 'voice clip started'"))
    check(at, "没有片段麦克风的 voice clip started 一行")
    speech_end = int(at.group(1)) + end * RATE
    return t, (lift - speech_end) * 1000 / RATE


def verdict(clip, results):
    """three counted tries, judged by what was typed: 3 of 3 right passes; 2 or more wrong is the
    design's red; one wrong is neither and fails too. Needs the debug build's clip microphone: R15
    has none, so it cannot run there"""
    wrong = sum(not ok for ok, _ in results)
    notes = "、".join(n for _, n in results)
    if wrong:
        raise Fail(f"{clip}：3 次里 {wrong} 次不对（{'红：≥ 2 次失败' if wrong >= 2 else '不稳定'}）：{notes}")
    return f"{clip}：3/3 对：{notes}"


def counted_tries(d, clip, attempt, most=5):
    """[attempt]() → (ok, note, counted) until three are counted, [most] attempts at most"""
    results, every = [], []
    for _ in range(most):
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
    out = []
    for clip, want in (("jintian", "今天天气很好"), ("zouba", "我们走吧")):
        before_s = 0.45 + input_lag(d)

        def attempt(before):
            nonlocal before_s
            t, off = lift_early(d, clip, before_s)
            got = t[len(before):] if t.startswith(before) else t
            ok = plain(got) == want
            if off is NOTHING_HEARD:   # no speech segment, or two: says nothing about the tail
                return ok, f"没听到语音或语音被切成两段「{got}」（不算）", False, t
            note = f"{off:+.0f} ms「{got}」"
            if -500 <= off <= -400:
                return ok, note, True, t
            # off the window: not counted; the next lift moves by half what this one missed by
            before_s += (off + 450) / 2000
            return ok, note + "（不在区间，不算）", False, t

        out.append(verdict(clip, counted_tries(d, clip, attempt, most=10)))
    return "；".join(out)


@case("VO-24", base="B0-语音", mutates="learn", needs=EV)
def vo24(d):
    return soft_speech(d, (("zouba-q44", "我们走吧"), ("nihao-q44", "你好")))


# the same in the room's noise, all through the clip: VoiceQuiet must not take soft speech for the room
@case("VO-32", base="B0-语音", mutates="learn", needs=EV)
def vo32(d):
    return soft_speech(d, (("zouba-q44-room", "我们走吧"), ("nihao-q44-room", "你好")))


# a lone English word at a normal level: VoiceQuiet's short Latin phrase, typed (silero hears it)
@case("VO-33", base="B0-语音", mutates="learn", needs=EV)
def vo33(d):
    ok = lambda t: plain(t).lower() in ("ok", "okay")
    _, t = dictate(d, "ok")
    check(ok(t), f"松开后框里「{t}」")
    return f"框里「{t}」"


def soft_speech(d, clips):
    out = []
    for clip, want in clips:
        def attempt(before):
            d.sh("logcat -c")
            _, t = dictate(d, clip)
            got = t[len(before):] if t.startswith(before) else t
            ok = plain(got) == want
            log = capture_log(d)
            check(log and log["envPeak"] is not None, f"没有调试日志的 voice capture 一行：{log}")
            env, gain = log["envPeak"], log["peakGainDb"]
            note = f"env {env} dBFS、{gain:+.1f} dB「{got}」"
            # the level in the app off: not counted; the gain is what says it was quiet speech
            if -65 <= env <= -50 and gain >= 15:
                return ok, note, True, t
            return ok, note + "（电平不对，不算）", False, t

        out.append(verdict(clip, counted_tries(d, clip, attempt, most=10)))
    return "；".join(out)


# the rule itself on the JVM; on the device a smoke: the first sentence typed, and where the press fell written down
@case("VO-25", base="B0-语音", mutates="learn", needs=EV)
def vo25(d):
    d.sh("logcat -c")
    # read before the hold: what is heard while it is held is in the field already, as composing
    before = d.field_text() or ""
    x, y = hold_space(d)
    time.sleep(0.3)
    d.say("nihao")
    time.sleep(1.2)
    # lifted and pressed again at once, the second press held as long as a long press
    d.touch(("up", x, y), ("sleep", 0.05), ("down", x, y), ("sleep", 1.0), ("up", x, y))
    t = settle(d)
    got = t[len(before):] if t.startswith(before) else t
    check(plain(got) == "你好", f"松开后马上再按住，上屏「{got}」，要「你好」（之前框里「{before}」）")
    log = capture_log(d) or {}
    read, stop = log.get("read"), log.get("stopAt")
    when = "不明（没有调试日志）" if read is None or stop is None else "续录中（停在 stopAt 之前）" if read < stop else "识别中"
    return f"之前框里「{before}」，上屏「{got}」；再按落在{when}（read={read}, stopAt={stop}）"


# the rule on the JVM; on the device, that 正在听 waits for the microphone's first nonzero sample, and how long that took
@case("VO-26", base="B0-语音", mutates="learn", needs=EV)
def vo26(d):
    """one hold: 准备中… till the microphone's first sound, then 正在听…, and the text. The clip
    microphone hisses from the start (a phone's microphone never reads exact zeroes for long), so
    正在听 mostly shows at once here; the wait for the first sound is the phone's to see"""
    quiet(d)
    d.sh("logcat -c")
    hold_space(d, raw=True)
    s = d.screen()
    first = LISTEN if anywhere(s, LISTEN) else PREPARING
    before = d.field_text() or ""
    early = d.sh("logcat -d | grep -c 'voice ready'").strip() not in ("", "0")
    p = d.say("nihao", wait=False)
    try:
        seen, end = False, time.time() + 10
        while time.time() < end and not seen:
            seen = bool(anywhere(d.screen(), LISTEN))
            time.sleep(0.1)
        p.wait(30)
    finally:
        p.kill()
    time.sleep(1.2)
    d.sh(raw_up(d))
    t = settle(d)
    got = t[len(before):] if t.startswith(before) else t
    ready = d.sh("logcat -d | grep -o 'voice ready: fromStartMs=[0-9]*' | tail -1").strip()
    # 正在听 never before the first nonzero sample: either order of the two is fine, 正在听 without the log is not
    check(first == PREPARING or early, f"按下后第一眼「{first}」，却没有 voice ready 日志：正在听亮得比麦克风早")
    check(seen and ready, f"播「你好」时{'' if seen else '没'}出现「{LISTEN}」，{ready or '没有 voice ready 日志'}")
    check(plain(got) == "你好", f"说「你好」，框里「{got}」")
    how = f"按下「{PREPARING}」，片段一响变「{LISTEN}」" if first == PREPARING else f"按下时麦克风已有声音，直接「{LISTEN}」"
    return f"{how}（{ready.split(': ')[1]}，从开麦算）；框里「{got}」"


# the rule on the JVM; on the device a smoke: the mask tapped to stop after the clip, then Enter sends the sentence
@case("VO-28", base="B0-语音", mutates="learn", needs=EV, page="chat")
def vo28(d):
    """one sentence said, stopped on the mask, then the keyboard's Enter"""
    quiet(d)
    d.sh("logcat -c")
    s = d.screen()
    d.clip_mic()
    voice = s.find(desc="语音输入", where="ime")
    if not voice:
        raise Blocked("工具栏上没有「语音输入」（工具栏收起了？）")
    # tapped, not held: the mask over the keys, its hint on it and no button
    d.tap(voice.c, 0.8)
    s = d.screen()
    hint = s.find(text="点这里或麦克风 结束", where="ime")
    check(hint, "点麦克风后没有盖层（没找到它的提示）")
    # the keyboard's Enter under it is named as the field's (the chat's: 发送); none on the mask itself
    enters = [n for n in s.ime() if n.desc in ("发送", "回车")]
    check(len(enters) == 1, f"点麦克风后有 {len(enters)} 个回车（盖层上不该有）")
    enter = enters[0]
    d.say("zouba")
    time.sleep(0.3)
    # stopped on the mask (its hint), then the tail and the rest recognized, the mask gone
    d.tap(hint.c, 0.1)
    mask_gone(d)   # Enter tapped while the mask is up lands on the mask
    ex, ey = enter.c
    d.touch(("down", ex, ey), ("sleep", 0.05), ("up", ex, ey))
    time.sleep(2)
    # the page's log skips an empty send, so an empty ↵ before the sentence shows as the sentence left in the box
    def sent_in(s):
        return sum((n.text or "").count("我们走吧") for n in s.app() if n.cls != "EditText")
    # a long-running Dumper's UiAutomation keeps the WebView's old node for the log: read again anew
    s = d.screen()
    if not sent_in(s) and d.start_dumper():
        s = d.screen()
    box = d.field_text(s) or ""
    sent = sent_in(s)
    check(sent == 1 and not plain(box), f"聊天记录里「我们走吧」{sent} 次，聊天框「{box}」")
    return "发出「我们走吧」一次，聊天框已清空"


case("VO-39", needs="jvm:VoiceFailureUatTest.eachFailureSaysItsOwnMessageAndWhatWasHeardIsTyped、"
     "VoiceListenerMicrophoneTest.aReadThrowingIsTheMicrophonesFailure、"
     "VoiceListenerMicrophoneTest.aReadThrowingOnceLiftedIsNoFailure")(lambda d: None)
case("VO-40", needs="manual:系统的麦克风使用指示要在 Android 12 起的设备上看（root 杀进程：root 过的真机或 API 31 起的模拟器）；"
     "关麦克风本身由 JVM 的 VoiceListenerMicrophoneTest.closedWhileTheModelsLoadTheMicrophoneIsStopped 断言")(lambda d: None)


case("VO-53", needs="manual:语音待定文字在框里时打拼音：说话和点键要同时，手工看")(lambda d: None)
