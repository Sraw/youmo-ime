"""uat/12-voice.md (语音输入, the voice build): on the emulator only (`uat.py run --devices 5554
--flavor voice`), whose microphone is the host's PulseAudio source; what is "said" is a clip of
uat/tools/voice-clips.py played into it (Device.say). The .md is the source of truth."""
import re
import time

from uat import PKG, Blocked, Fail, case, check, home, script

LISTEN, HEARD_NOTHING, CANCEL = "正在听…", "没有听清", "松开手指，取消输入"
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
    is not taken (VO-16), and only VO-16 means to press then"""
    end = time.time() + timeout
    while time.time() < end:
        s = d.screen()
        if not anywhere(s, HEARD_NOTHING) and not anywhere(s, "识别中"):
            return
        time.sleep(0.2)


def hold_space(d, timeout=60, wait_quiet=True):
    """finger down on the space key until 正在听… shows (the first use after a reset loads the
    models); returns the space key's centre"""
    emu_only(d)
    if wait_quiet:
        quiet(d)
    x, y = d.keys(refresh=True)["space"]
    d.touch(("down", x, y))
    try:
        d.wait(lambda s: anywhere(s, LISTEN), timeout, step=0.2, what=LISTEN)
    except Blocked:
        s = d.screen()
        d.shot("full", f"长按空格 {timeout} s，没有「{LISTEN}」")
        d.touch(("up", x, y))
        raise Fail(f"长按空格 {timeout} s 没出现「{LISTEN}」：屏幕上 {[n.text for n in s.nodes if n.text][:8]}，"
                   f"{d.top_activity()[-40:]}")
    return x, y


def dictate(d, clip, release=True):
    """hold space, say the clip, let go once it is heard; returns (what showed while listening, field)"""
    x, y = hold_space(d)
    time.sleep(0.3)
    p = d.say(clip, wait=False)
    shown = []   # what the screen showed while it was said and just after
    end = time.time() + 30
    while time.time() < end and p.poll() is None:
        shown += [n.text for n in d.screen().nodes if n.text and n.pkg == PKG and n.text not in shown]
        time.sleep(0.2)
    time.sleep(1.2)   # the last stretch's silence: the VAD closes it
    shown += [n.text for n in d.screen().nodes if n.text and n.pkg == PKG and n.text not in shown]
    if not release:
        return shown, (x, y)
    d.touch(("up", x, y))
    t = settle(d)
    return shown, t


def settle(d, timeout=15):
    """the field once 识别中… is over"""
    end = time.time() + timeout
    while time.time() < end:
        s = d.screen()
        if not anywhere(s, "识别中") and not anywhere(s, LISTEN):
            break
        time.sleep(0.3)
    time.sleep(0.5)
    return d.field_text() or ""


def retried(d, clip, ok, before=""):
    """dictate until ok(field) holds, three times at most: the emulator's microphone drops frames
    when the host is busy (the HAL's "late delivering frames, inserting … silence"). What a miss
    added after before is taken back between tries. Returns (shown, field, tries, misses)"""
    misses = []
    for n in range(1, 4):
        shown, t = dictate(d, clip)
        if ok(t) or not t.startswith(before):
            return shown, t, n, misses
        misses.append(t[len(before):])
        if len(t) > len(before):
            d.bksp(len(t) - len(before))
    return shown, t, 3, misses


def tries_note(n, misses):
    return "" if n == 1 else f"（第 {n} 次；之前听成 {misses}）"


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


@case("VO-03", base="B0-语音", needs=EV)
def vo03(d):
    shown, (x, y) = dictate(d, "nihao", release=False)
    if not any("你" in t or "好" in t for t in shown):
        d.touch(("up", x, y))
        raise Blocked(f"这一句没听到「你好」（模拟器麦克风丢帧），取消测不出：{shown[:6]}")
    d.touch(("move", x, y - 150), ("sleep", 0.2), ("move", x, y - 300), ("sleep", 0.4))
    s = d.screen()
    hint = bool(anywhere(s, CANCEL))
    d.touch(("up", x, y - 300))
    t = settle(d)
    check(t == "", f"上滑松开后框里「{t}」")
    check(hint, f"上滑时没显示「{CANCEL}」")
    return f"上滑时显示「{CANCEL}」；框里为空"


def open_panel(d):
    """the toolbar's microphone: the voice panel; returns the big microphone's node"""
    emu_only(d)
    s = d.screen()
    mic = s.find(desc="语音输入", where="ime")
    if not mic:
        d.expand_toolbar()
        s = d.screen()
        mic = s.find(desc="语音输入", where="ime")
    if not mic:
        raise Fail(f"工具栏上没有麦克风：{[n.desc for n in s.ime() if n.desc][:12]}")
    d.tap(mic.c, 1.0)
    s = d.screen()
    if not anywhere(s, "按住话筒说话"):
        raise Fail(f"点麦克风后没有语音面板：{[n.text for n in s.ime() if n.text][:10]}")
    return s


def panel_mic(d, s):
    """the panel's microphone (按住 说话) and the circle around it"""
    m = s.find(desc="按住 说话", where="ime")
    if not m:
        raise Blocked(f"语音面板上找不到话筒：{[n.desc for n in s.ime() if n.desc][:10]}")
    return m, (m.parent.b if m.parent else m.b)


def panel_say(d, clip):
    s = open_panel(d)
    m, _ = panel_mic(d, s)
    x, y = m.c
    d.touch(("down", x, y))
    d.wait(lambda s: anywhere(s, "松开 上屏") or anywhere(s, LISTEN), 60, step=0.2, what="松开 上屏")
    time.sleep(0.3)
    d.say(clip)
    time.sleep(1.2)
    d.touch(("up", x, y))
    return settle(d)


@case("VO-04", base="B0-语音", mutates="learn", needs=EV)
def vo04(d):
    tries = []
    for n in range(1, 4):
        t = panel_say(d, "zouba")
        tries.append(t)
        if plain(t) == "我们走吧":
            break
        d.back()
        d.open_page("text")
    check(plain(t) == "我们走吧", f"面板里说「我们走吧」，框里「{t}」（{len(tries)} 次：{tries}）")
    return f"框里「{t}」" + ("" if len(tries) == 1 else f"（第 {len(tries)} 次；之前 {tries[:-1]}）")


@case("VO-05", base="B0-语音", needs=EV)
def vo05(d):
    s = open_panel(d)
    m, ring = panel_mic(d, s)
    x, y = m.c
    d.touch(("down", x, y))
    d.wait(lambda s: anywhere(s, "松开 上屏") or anywhere(s, LISTEN), 60, step=0.2, what="松开 上屏")
    d.say("nihao")
    heard = False
    end = time.time() + 2
    while time.time() < end and not heard:
        heard = bool(anywhere(d.screen(), "你好"))
        time.sleep(0.2)
    out = ring[0] - 150
    if not heard:
        d.touch(("up", x, y))
        raise Blocked("这一句没听到「你好」（模拟器麦克风丢帧），取消测不出")
    d.touch(("move", (x + out) // 2, y), ("sleep", 0.1), ("move", out, y), ("sleep", 0.4))
    hint = bool(anywhere(d.screen(), "松开 取消"))
    d.touch(("up", out, y))
    t = settle(d)
    check(t == "", f"滑出话筒圈松开后框里「{t}」")
    check(hint, "滑出话筒圈时没显示「松开 取消」")
    return "滑出时显示「松开 取消」；框里为空"


def bare(t):
    return "".join(ch for ch in t if ch not in "。，,. ")


@case("VO-06", base="B0-语音", mutates="learn", needs=EV)
def vo06(d):
    _, t1, n1, m1 = retried(d, "2002", lambda t: bare(t) == "2002年")
    check(bare(t1) == "2002年", f"说「二零零二年」，框里「{t1}」{tries_note(n1, m1)}")
    _, t, n2, m2 = retried(d, "57", lambda t: bare(t) == "2002年57%", before=t1)
    check(bare(t) == "2002年57%", f"再说「百分之五十七」，框里「{t}」{tries_note(n2, m2)}")
    return f"框里「{t}」{tries_note(n1, m1)}{tries_note(n2, m2)}"


@case("VO-07", base="B0-语音", mutates="learn", needs=EV)
def vo07(d):
    _, t, n, miss = retried(d, "shifen", lambda t: plain(t) == "这个电影十分有趣")
    check(plain(t) == "这个电影十分有趣", f"框里「{t}」{tries_note(n, miss)}")
    return f"框里「{t}」{tries_note(n, miss)}"


@case("VO-08", base="B0-语音", mutates="learn", needs=EV)
def vo08(d):
    _, t, n, miss = retried(d, "iphone", lambda t: "我想买" in t)
    check("iPhone" in t, f"说「我想买一个 iPhone」，框里「{t}」{tries_note(n, miss)}")
    return f"框里「{t}」{tries_note(n, miss)}"


@case("VO-09", base="B0-语音", needs=EV)
def vo09(d):
    x, y = hold_space(d)
    d.say("cough")
    time.sleep(1.0)
    d.touch(("up", x, y))
    seen = None
    end = time.time() + 6
    while time.time() < end and not seen:
        seen = anywhere(d.screen(), HEARD_NOTHING)
        time.sleep(0.2)
    t = settle(d)
    check(t == "", f"只咳嗽一声，框里「{t}」")
    check(seen, f"没显示「{HEARD_NOTHING}」")
    return f"显示「{HEARD_NOTHING}」，框里为空"


@case("VO-10", base="B0-设置", mutates=True, needs=EV)
def vo10(d):
    emu_only(d)
    script("13-words").block_word(d, "天气")
    d.open_page("text")
    _, t, n, miss = retried(d, "jintian", lambda t: plain(t).startswith("今天") and plain(t).endswith("很好"))
    check(plain(t).startswith("今天") and plain(t).endswith("很好"), f"没听全：「{t}」{tries_note(n, miss)}")
    check("天气" not in t, f"屏蔽「天气」后说「今天天气很好」，框里「{t}」")
    return f"框里「{t}」{tries_note(n, miss)}"


@case("VO-11", base="B0-语音", page="password", visual=True, needs=EV)
def vo11(d):
    emu_only(d)
    x, y = d.keys(refresh=True)["space"]
    d.touch(("down", x, y), ("sleep", 1.5))
    s = d.screen()
    d.touch(("up", x, y))
    check(not anywhere(s, LISTEN), "密码框里长按空格进了语音输入")
    time.sleep(0.5)
    s = d.screen()
    mic = s.find(desc="语音输入", where="ime")
    usable = bool(mic and mic.enabled)
    check(not usable, "密码框里工具栏上有可用的麦克风")
    # a slow drag on space still moves the cursor (Voice does not take the touch where it can't
    # listen); a password field hides its text, so what shows is no space typed and no listening
    d.type("abcdef")
    n0 = len(d.field_text() or "")
    steps = ["input motionevent DOWN {} {}".format(x, y)]
    steps += [f"sleep 0.15; input motionevent MOVE {x - 20 * k} {y}" for k in range(1, 13)]
    steps.append(f"input motionevent UP {x - 240} {y}")
    d.sh("; ".join(steps))
    time.sleep(0.6)
    s = d.screen()
    n1 = len(d.field_text(s) or "")
    check(not anywhere(s, LISTEN), "慢拖空格进了语音")
    check(n1 == n0, f"慢拖空格后框里字数 {n0} → {n1}（打出了空格？）")
    # the hidden text gives no cursor position to read: the screenshot shows where it went
    d.shot("field", "慢拖空格后：光标应在 abcdef 中间（不在末尾）")
    return f"长按空格没进语音；工具栏{'有灰的' if mic else '没有'}麦克风；慢拖空格没打字、没听写；光标看图"


def touchscreen(d):
    """(event node, x max, y max, screen w, h): the first input device with multi-touch axes, and
    the display's size; Blocked when either is not found"""
    dev, axes = None, {}
    for line in d.sh("getevent -pl").splitlines():
        m = re.match(r"add device \d+: (\S+)", line)
        if m:
            if axes.keys() >= {"X", "Y"}:
                break
            dev, axes = m.group(1), {}
            continue
        m = re.search(r"ABS_MT_POSITION_([XY]).*max (\d+)", line)
        if m:
            axes[m.group(1)] = int(m.group(2))
    size = re.search(r"(\d+)x(\d+)", d.sh("wm size").splitlines()[-1] if d.sh("wm size") else "")
    if not dev or axes.keys() < {"X", "Y"} or not size:
        raise Blocked(f"找不到触摸屏或屏幕尺寸：{dev} {axes} {size}")
    return dev, axes["X"], axes["Y"], int(size.group(1)), int(size.group(2))


def mt(d, *steps):
    """fingers on the emulator's touchscreen (sendevent, root): ("down"|"move", slot, x, y),
    ("up", slot), ("sleep", s)"""
    TOUCH, mx, my, w, h = touchscreen(d)
    cmds = []
    for st in steps:
        if st[0] == "sleep":
            cmds.append(f"sleep {st[1]}")
            continue
        slot = st[1]
        cmds.append(f"sendevent {TOUCH} 3 47 {slot}")
        if st[0] == "down":
            x, y = st[2] * mx // w, st[3] * my // h
            # the device has no BTN_TOUCH: a contact is its tracking id, pressure and size
            cmds += [f"sendevent {TOUCH} 3 57 {100 + slot}", f"sendevent {TOUCH} 3 53 {x}",
                     f"sendevent {TOUCH} 3 54 {y}", f"sendevent {TOUCH} 3 58 512", f"sendevent {TOUCH} 3 48 8"]
        elif st[0] == "move":
            cmds += [f"sendevent {TOUCH} 3 53 {st[2] * mx // w}", f"sendevent {TOUCH} 3 54 {st[3] * my // h}"]
        else:
            cmds.append(f"sendevent {TOUCH} 3 57 -1")
        cmds.append(f"sendevent {TOUCH} 0 0 0")
    d.root("; ".join(cmds))


@case("VO-12", base="B0-语音", page="", needs=EV)
def vo12(d):
    emu_only(d)
    s = d.screen()
    other = next((n for n in s.app() if n.rid == "sentence"), None)
    if not other:
        raise Blocked(f"测试页上看不到「句子」框：{[n.rid for n in s.app() if n.cls == 'EditText']}")
    quiet(d)
    x, y = d.keys(refresh=True)["space"]
    mt(d, ("down", 0, x, y))
    try:
        d.wait(lambda s: anywhere(s, LISTEN), 60, step=0.2, what=LISTEN)
    except Blocked:
        mt(d, ("up", 0))
        raise Fail(f"长按空格没出现「{LISTEN}」")
    time.sleep(0.3)
    d.say("nihao")
    heard, shown = False, []
    end = time.time() + 3
    while time.time() < end and not heard:
        s = d.screen()
        heard = bool(anywhere(s, "你好"))
        shown = [n.text for n in s.nodes if n.text and len(n.text) > 1][:6]
        time.sleep(0.2)
    if not heard:
        mt(d, ("up", 0))
        raise Blocked(f"这一句没听到「你好」（模拟器麦克风丢帧），测不了换框：{shown}")
    # the other hand: a second finger taps 句子 while the first still holds the space key
    mt(d, ("down", 1, *other.c), ("sleep", 0.1), ("up", 1), ("sleep", 0.8))
    moved = next((n.focused for n in d.screen().app() if n.rid == "sentence"), False)
    mt(d, ("up", 0))
    settle(d)
    time.sleep(1.0)
    if not moved:
        raise Blocked("第二指没点到「句子」框（焦点没移过去），测不了换框")
    s = d.screen()
    t_other = next((n.text for n in s.app() if n.rid == "sentence"), "") or ""
    t_text = next((n.text for n in s.app() if n.rid == "text"), "") or ""
    check("你好" not in t_other, f"「你好」进了「句子」框：「{t_other}」")
    return f"听到「你好」时点「句子」框再松开：「句子」框「{t_other}」，「普通文本」框「{t_text}」"


@case("VO-13", base="B0-设置", mutates=True, needs=EV)
def vo13(d):
    emu_only(d)
    home(d, "按键")
    d.choose("空格键长按行为", "显示输入法选择对话框")
    d.open_page("text")
    x, y = d.keys(refresh=True)["space"]
    d.touch(("down", x, y), ("sleep", 1.5))
    s = d.screen()
    d.touch(("up", x, y))
    time.sleep(0.8)
    s2 = d.screen()
    listening = anywhere(s, LISTEN)
    picker = anywhere(s2, "选择输入法") or anywhere(s, "选择输入法")
    d.back()
    check(not listening, "改成输入法选择后长按空格仍进了语音")
    check(picker, f"没弹出系统输入法选择框：{s2.texts(where='all')[:8]}")
    return "弹出「选择输入法」，没进语音"


@case("VO-14", base="B0-设置", mutates=True, needs=EV)
def vo14(d):
    emu_only(d)
    script("13-words").add_word(d, "澜境花园")
    d.open_page("text")
    _, t, n, miss = retried(d, "lanjing", lambda t: "澜境花园" in t)
    check("澜境花园" in t, f"加了用户词「澜境花园」后说「我住在澜境花园」，框里「{t}」{tries_note(n, miss)}")
    return f"框里「{t}」{tries_note(n, miss)}"


@case("VO-15", base="B0-设置")
def vo15(d):
    home(d, "更多选项", "关于")
    d.tap_text("第三方库许可证", after=2.0)
    for _ in range(12):
        s = d.screen()
        n = next((n for n in s.app() if "LibriSpeech" in n.text), None)
        if n:
            break
        d.scroll(s)
    else:
        n = None
    if d.flavor != "voice":   # the text build has no voice model: no LibriSpeech either
        check(not n, "文字版的许可证页列着 LibriSpeech")
        return "文字版：许可证页没有 LibriSpeech"
    if not n:
        raise Fail("许可证页没有 LibriSpeech")
    row = n.row() or n.parent
    texts = [m.text for m in (row.walk() if row else [n]) if m.text]
    lic = any("CC-BY-4.0" in t or "CC BY 4.0" in t for t in texts)
    check(lic, f"LibriSpeech 那一行 {texts}")
    d.tap(n.c, 1.0)
    # the link opens in the system browser: openslr.org's page 12 is the LibriSpeech corpus (SLR12)
    try:
        s = d.wait(lambda s: s if any("SLR12" in (m.text or "") or "openslr.org/12" in (m.text or "")
                                      for m in s.nodes) else None, 15, what="openslr.org/12")
        link = [m.text for m in s.nodes if "SLR12" in (m.text or "") or "openslr.org" in (m.text or "")]
    except Blocked:
        link = []
    top = d.top_activity()
    d.back()
    d.sh("am force-stop com.android.chrome")
    check(link, f"点 LibriSpeech 没打开 openslr.org/12：{top}")
    return f"LibriSpeech {texts}；点开是 {link[:2]}"


@case("VO-16", base="B0-语音", mutates="learn", needs=EV)
def vo16(d):
    x, y = hold_space(d)
    time.sleep(0.3)
    d.say("cough")
    time.sleep(1.0)
    d.touch(("up", x, y))
    d.wait(lambda s: anywhere(s, HEARD_NOTHING), 8, step=0.1, what=HEARD_NOTHING)
    # still showing: press again at once
    d.touch(("down", x, y))
    started = False
    end = time.time() + 6
    while time.time() < end and not started:
        started = bool(anywhere(d.screen(), LISTEN))
        time.sleep(0.2)
    if not started:
        d.touch(("up", x, y))
        raise Fail(f"「{HEARD_NOTHING}」还显示着时再按住空格，6 s 没出现「{LISTEN}」（松开再按才行）")
    time.sleep(0.3)
    d.say("nihao")
    time.sleep(1.2)
    d.touch(("up", x, y))
    t = settle(d)
    check(plain(t) == "你好", f"第二次说「你好」，框里「{t}」")
    return f"第二次马上开始听，框里「{t}」"


@case("VO-17", base="B0-设置", mutates=True, needs=EV)
def vo17(d):
    emu_only(d)
    script("13-words").add_word(d, "澜境花园")
    d.open_page("text")
    out = []
    for clip, want in (("jintian", "今天天气很好"), ("nihao", "你好")):
        _, t, n, miss = retried(d, clip, lambda t, w=want: plain(t) == w)
        check(plain(t) == want, f"加了「澜境花园」后说「{want}」，框里「{t}」{tries_note(n, miss)}")
        out.append(f"「{t}」{tries_note(n, miss)}")
        d.clear()
    return "；".join(out)


def _words():
    return script("13-words")


def heard_once(d, clip, want):
    """one dictation (retried only on a drop-out: an empty or cut result), then the field emptied"""
    _, t, n, miss = retried(d, clip, lambda t: len(plain(t)) >= len(want))
    d.clear()
    return t, tries_note(n, miss)


@case("VO-18", base="B0-设置", mutates=True, needs=EV)
def vo18(d):
    emu_only(d)
    w = _words()
    w.push(d, "voice-import.txt")   # the .md's fixture, pushed
    _, msgs = w.import_file(d, "voice-import.txt", "用户词库")
    check(w.imported_count(msgs) == 1, f"导入结果 {msgs[:3]}")
    d.open_page("text")
    d.switch_im("拼音")
    t1, n1 = heard_once(d, "lanjing", "我住在澜境花园")
    check("澜境花园" not in t1, f"导入后说「我住在澜境花园」就是「{t1}」{n1}")
    w.add_word(d, "澜境花园", "lan jing hua yuan")
    d.open_page("text")
    d.switch_im("拼音")
    _, t2, n, miss = retried(d, "lanjing", lambda t: "澜境花园" in t)
    check("澜境花园" in t2, f"手动加了以后说「我住在澜境花园」，框里「{t2}」{tries_note(n, miss)}")
    return f"导入后「{t1}」{n1}；手动加了以后「{t2}」{tries_note(n, miss)}"


@case("VO-19", base="B0-语音", mutates=True, needs=EV, clean=True)
def vo19(d):
    d.switch_im("拼音")
    # the baseline after the same 前文 the dictation leaves: its text ends in 。, a sentence start,
    # which by itself moves wangxiaoming's 4th; only learning would move them further
    d.press(".")   # 。 in 拼音
    d.clear()
    d.type("wangxiaoming")
    c0 = d.cands()[:5]
    d.clear()
    heard = []
    for _ in range(2):
        _, t, n, miss = retried(d, "wangxiaoming", lambda t: len(plain(t)) >= 3)
        heard.append(t + tries_note(n, miss))
        d.clear()
    d.type("wangxiaoming")
    c1 = d.cands()[:5]
    d.clear()
    check(c1 == c0, f"说两次后拼音候选 {c1}，说之前 {c0}（听成 {heard}）")
    w = _words()
    w.user_lexicon(d)
    texts = d.all_texts()
    for page in ("自动学到的词", "手动添加的词"):
        if page in texts:
            d.tap_text(page, after=1.0)
            rows = w.list_texts(d)
            check(not any("小明" in r for r in rows), f"「{page}」里有语音新增的 {[r for r in rows if '小明' in r]}")
            d.back()
    return f"听成 {heard}；候选前后都是 {c0[:3]}；用户词库没有「小明」"


def vo20_steps(d):
    t0, n0 = heard_once(d, "wangmingtian", "汪小明明天来")
    if "汪小明" in t0:
        raise Blocked(f"前提没到：没学之前就听成「{t0}」{n0}（合成语音太清楚，X-ASR 本身听得出汪小明）")
    d.switch_im("拼音")
    _words().learn_name(d)
    _, t, n, miss = retried(d, "wangmingtian", lambda t: "汪小明" in t)
    return t0, t, tries_note(n, miss)


@case("VO-20", base="B0-语音", mutates=True, needs=EV, clean=True)
def vo20(d):
    t0, t, note = vo20_steps(d)
    check("汪小明" in t, f"拼音打两次「汪小明」后说，框里「{t}」{note}（之前「{t0}」）")
    return f"之前「{t0}」，打两次后「{t}」{note}"


@case("VO-21", base="B0-语音", mutates=True, needs=EV, clean=True)
def vo21(d):
    t0, t, note = vo20_steps(d)
    if "汪小明" not in t:
        raise Blocked(f"前提（VO-20）没到：打两次后仍是「{t}」")
    d.clear()
    w = _words()
    w.user_lexicon(d, "自动学到的词")
    row = next((n for n in d.screen().app() if n.text.startswith("汪小明")), None)
    if not row:
        raise Blocked(f"自动学到的词里没有「汪小明」：{w.list_texts(d)[:6]}")
    d.hold(row.c, 900)
    time.sleep(0.6)
    s = d.screen()
    dl = s.find(desc="删除") or s.find(text="删除")
    if not dl:
        raise Blocked(f"长按「汪小明」后没有删除：{[x.desc or x.text for x in s.app() if x.desc or x.text][:10]}")
    d.tap(dl.c, 1.0)
    conf = next((n for n in d.screen().nodes if n.rid == "button1"), None)
    if conf:
        d.tap(conf.c, 1.0)
    rows = w.list_texts(d)
    check(not any(r.startswith("汪小明") for r in rows), f"删除后 {rows[:6]}")
    d.open_page("text")
    t2, n2 = heard_once(d, "wangmingtian", "汪小明明天来")
    check("汪小明" not in t2, f"删掉「汪小明」后说，框里「{t2}」{n2}")
    return f"学后「{t}」；删掉后「{t2}」{n2}"


@case("VO-22", base="B0-语音", mutates=True, needs=EV, clean=True)
def vo22(d):
    t0, t, note = vo20_steps(d)
    if "汪小明" not in t:
        raise Blocked(f"前提（VO-20）没到：打两次后仍是「{t}」")
    d.clear()
    pr = script("15-privacy-backup")
    tb = script("05-toolbar")
    # the ⋯ panel stays up after a switch in it: back to the keys, where the space is held
    pr.status_entry(d, "不用我的输入习惯")
    tb.back_to_keys(d)
    t1, n1 = heard_once(d, "wangmingtian", "汪小明明天来")
    pr.status_entry(d, "恢复我的输入习惯")
    tb.back_to_keys(d)
    _, t2, n, miss = retried(d, "wangmingtian", lambda x: "汪小明" in x)
    d.clear()
    check("汪小明" not in t1, f"不用我的输入习惯时说，框里「{t1}」{n1}")
    check("汪小明" in t2, f"恢复后说，框里「{t2}」{tries_note(n, miss)}")
    return f"不用时「{t1}」{n1}；恢复后「{t2}」{tries_note(n, miss)}"
