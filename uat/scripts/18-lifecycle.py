"""uat/18-lifecycle.md. The .md is the source of truth; each function follows its case's
前提/操作/预期. A turn of the device is the display resized to landscape (Device.rotate)."""
import time

from uat import BROWSER, PKG, TESTBED, Blocked, case, check, choose, home, open_url, script


def fields(d, s=None):
    s = s or d.screen()
    return [n for n in s.app() if n.cls == "EditText" and n.pkg == "org.chromium.webview_shell"
            and n.rid != "url_field"]


def tap_field(d, i=0, after=1.2):
    f = fields(d)
    if len(f) <= i:
        raise Blocked(f"页面上没有第 {i + 1} 个框")
    d.tap(f[i].c, after)
    d._keys = None


def keyboard_up(d):
    return bool(d.screen().find(rid="keyboard_view", where="ime"))


@case("LC-01", mutates=True)
def lc01(d):
    d.type("nihao")
    d.cands()
    t0 = d.field_text()
    d.rotate(1)
    s = d.screen()
    t1 = d.field_text(s) if d.field(s) else (fields(d, s)[0].text if fields(d, s) else None)
    if not keyboard_up(d):
        tap_field(d)
    d.shot("full", "横屏点框后")
    d.type("hao")
    d.cands()
    d.space()
    t = d.field_text()
    d.rotate(0)
    check(not t0, f"转屏前框里「{t0}」")
    check(not t1, f"转屏后框里「{t1}」")
    check(t == "好", f"最后框里「{t}」")
    return f"转屏前后框里都空；最后「{t}」"


@case("LC-02", page="long", mutates=True)
def lc02(d):
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 0.6)   # the very start of the text
    d.expand_toolbar()
    d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
    s = d.screen()
    sel = s.find(desc="选择", where="ime") or s.find(text="选择", where="ime")
    right = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    d.tap(sel.c, 0.3)
    d.taps([right.c] * 4, gap=0.1, after=0.4)
    d.rotate(1)
    s = d.screen()
    if not keyboard_up(d):
        raise Blocked("横屏后键盘收起了")
    # the keyboard is rebuilt for landscape and comes back on the letters; the selection is the
    # field's, so the edit panel's 复制 copies it
    cp = s.find(desc="复制", where="ime") or s.find(text="复制", where="ime")
    if not cp and keyboard_up(d):
        d.expand_toolbar()
        d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
        s = d.screen()
        cp = s.find(desc="复制", where="ime") or s.find(text="复制", where="ime")
    if not cp:
        raise Blocked(f"横屏后没有文本编辑面板：{[n.desc or n.text for n in s.ime() if n.desc or n.text][:10]}")
    d.tap(cp.c, 0.6)
    d.rotate(0)
    open_url(d, "text")
    d.expand_toolbar()
    d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
    s = d.screen()
    ps = s.find(desc="粘贴", where="ime") or s.find(text="粘贴", where="ime")
    d.tap(ps.c, 0.8)
    t = d.field_text()
    check(t == "今天天气", f"粘贴出「{t}」")
    return f"粘贴出「{t}」"


@case("LC-03", mutates="learn")
def lc03(d):
    d.type("ni")
    d.cands()
    d.sh("input keyevent KEYCODE_APP_SWITCH")
    time.sleep(1.2)
    d.sh("am start -n com.android.settings/.Settings")
    time.sleep(1.5)
    d.sh(f"am start -n {BROWSER} -f 0x20000")   # REORDER_TO_FRONT: the page as it was, like 最近任务
    time.sleep(1.5)
    s = d.screen()
    t0 = fields(d, s)[0].text if fields(d, s) else None
    tap_field(d)
    s = d.screen()
    p, c = d.preedit(s), d.candidates(s)
    d.type("hao")
    d.cands()
    d.space()
    t = d.field_text()
    check(not t0 or t0 == "", f"回来后框里「{t0}」")
    check(not p and not c, f"键盘上残留 `{p}` {c[:3]}")
    check(t == "好", f"最后「{t}」")
    return f"没有残留；「{t}」"


case("LC-04", needs="jvm:HostCasesUatTest.anotherFieldTappedWhilePinyinIsTypedLeavesNothing")(lambda d: None)


case("LC-05", needs="jvm:LifecycleUatTest.aNumberFieldThenTextIsLettersAgain")(lambda d: None)


case("LC-06", needs="jvm:LifecycleUatTest.anotherFieldFromTheSymbolsIsLetters")(lambda d: None)


case("LC-07", needs="jvm:LifecycleUatTest.hiddenAndShownAgainIsStillTheInputMethod")(lambda d: None)


@case("LC-08", mutates="learn")
def lc08(d):
    d.type("ni")
    d.cands()
    d.sh("input keyevent KEYCODE_SLEEP")
    time.sleep(1.5)
    d.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    time.sleep(1.5)
    tap_field(d)
    up = keyboard_up(d)
    d.clear()
    d.type("nihao")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(up, "解锁点框后键盘没出来")
    check(t == "你好", f"接着打出「{t}」（候选 {c[:2]}）")
    return f"键盘正常；「{t}」"


case("LC-09", needs="jvm:LifecycleUatTest.systemDarkModeFlippedWhileShown")(lambda d: None)


@case("LC-10", base="B0-设置", mutates=True)
def lc10(d):
    home(d, "更多选项")
    d.goto("开发者", "重启 Fcitx 实例")
    d.reload_n += 1
    d.sh(f"am start -a android.intent.action.VIEW -d 'http://127.0.0.1:8000/fields.html?r={d.reload_n}#text' -n {BROWSER}")
    time.sleep(1.0)
    tap_field(d, 0, after=0.3)
    if keyboard_up(d):
        d.type("nihao", after=0.2)
    for _ in range(30):
        if keyboard_up(d) and "不可用" not in d.im_label():
            break
        time.sleep(1.0)
    d.clear()
    d.type("nihao")
    c = d.cands(timeout=20, want="你好")
    d.space()
    t = d.field_text()
    alive = d.sh(f"pidof {PKG}").strip()
    check(alive, "应用崩了")
    check(t == "你好", f"打出「{t}」（候选 {c[:2]}）")
    return f"「{t}」"


@case("LC-11", mutates="learn")
def lc11(d):
    pid = d.sh(f"pidof {PKG}").strip()
    if not pid:
        raise Blocked("输入法进程没在跑")
    d.root(f"kill -9 {pid}")
    time.sleep(1.0)
    open_url(d, "text")
    for _ in range(30):
        if keyboard_up(d) and "不可用" not in d.im_label():
            break
        time.sleep(1.0)
    d.type("nihao")
    c = d.cands(timeout=30, want="你好")
    d.space()
    t = d.field_text()
    check(t == "你好", f"打出「{t}」（候选 {c[:2]}）")
    return f"「{t}」"


@case("LC-13", mutates=True)
def lc13(d):
    tb = script("05-toolbar")
    trad = tb.status_toggle(d, "简体")
    punc = tb.status_toggle(d, "中文标点")
    # killed at once: the settings must already be on disk, not waiting for a later save
    pid = d.sh(f"pidof {PKG}").strip()
    if not pid:
        raise Blocked("输入法进程没在跑")
    d.root(f"kill -9 {pid}")
    time.sleep(1.0)
    open_url(d, "text")
    for _ in range(30):
        if keyboard_up(d) and "不可用" not in d.im_label():
            break
        time.sleep(1.0)
    d.type("guojia")
    d.cands(timeout=30, want="國家")
    d.space()
    d.type(",")
    t = d.field_text()
    check(t == "國家,", f"切到「{trad}」「{punc}」后杀掉，打出「{t}」")
    tb.status_panel(d)
    check(tb.status_find(d, "繁體", "繁体"), "⋯ 里不是繁體")
    check(tb.status_find(d, "英文标点"), "⋯ 里不是英文标点")
    tb.back_to_keys(d)
    return f"「{t}」"


# A2: a setting or a theme changed with the keyboard up and pinyin half typed; the views made anew by the system
case("LC-14", needs="jvm:StuckBarUatTest.aKeyboardSettingChangedMidPinyinDropsIt")(lambda d: None)


case("LC-15", needs="jvm:StuckBarUatTest.aThemeChangedMidPinyinDropsIt")(lambda d: None)


case("LC-16", needs="jvm:StuckBarUatTest.aViewMadeAnewMidPinyinShowsTheEnginesState")(lambda d: None)


case("LC-17", needs="jvm:StuckBarUatTest.aThemeChangedMidEnglishWordCommitsIt")(lambda d: None)


case("LC-18", needs="jvm:StuckBarUatTest.floatingAndDockedMidPinyinKeepIt")(lambda d: None)



case("LC-20", needs="jvm:DeveloperUatTest.aRestartGivenUpOnAHostStillStartingSaysItMayNotHaveRestarted,DeveloperUatTest.restartedTheInstanceSaysDone,AppLanguageUatTest.aLanguageChangedWhileTheHostStaysStartingSaysItMayNotHaveRestarted")(lambda d: None)


# ---------------------------------------------------------------- 编码行在输入框里（B-IF）


def in_field(d, page="text"):
    """B-IF: 编码行 set to 输入框里 (its note agreed to), then the test page's field tapped"""
    home(d, "外观")
    d.tap_text("大小与样式", scroll=False, after=1.0)
    choose(d, "编码行", "输入框里")
    if page is not None:   # "": the page of every field
        d.open_page(page)


def testbed_in_field(d, page, rid):
    """B-IF on the testbed's [page], its field [rid] tapped: the keyboard up on it"""
    in_field(d, page=None)
    d.sh(f"am force-stop {TESTBED}")
    d.testbed(page)
    def there(s):
        # a field with no id (no_id_a) by its contentDescription, "<name>：…"
        return next((x for x in s.app() if x.pkg == TESTBED and (x.rid == rid or x.desc.startswith(rid + "："))), None)
    d.wait(lambda s: any(x.pkg == TESTBED for x in s.app()), 15, what="the testbed")
    for _ in range(12):   # further down the page: scrolled to
        s = d.screen()
        n = there(s)
        if n or not d.scroll(s):
            break
    if not n:
        raise Blocked(f"测试台 {page} 页上找不到 {rid} 框")
    d.tap(n.c, 1.5)
    d.page = None
    d.host = None
    d._keys = None


def tb(d, rid):
    return d.testbed_state().get(rid) or {}


@case("LC-21", mutates=True)
def lc21(d):
    in_field(d)
    d.type("nihao")
    d.cands()
    s = d.screen()
    t0, row = d.field_text(s), d.preedit(s)
    d.shot("full", "输入框里打 nihao")
    d.space()
    time.sleep(0.6)
    t1 = d.field_text()
    d.type("ni")
    d.cands()
    # the system's back (the toolbar's 隐藏键盘 is under the candidates while a code is typed)
    d.back(1.0)
    up = keyboard_up(d)
    t2 = fields(d)[0].text if fields(d) else None
    check(t0 == "ni hao", f"打字时框里「{t0}」")
    check(not row, f"键盘上方还有拼音 `{row}`")
    check(t1 == "你好", f"空格后框里「{t1}」")
    check(not up, "按返回后键盘还在")
    check(t2 == "你好", f"收起键盘后框里「{t2}」")
    return f"「{t0}」→「{t1}」，收起后「{t2}」"


@case("LC-22", mutates=True)
def lc22(d):
    testbed_in_field(d, "ime", "preselected")
    # the tap put the cursor where it landed: two letters selected again with shift and the left arrow
    for _ in range(2):
        d.sh("input keycombination KEYCODE_SHIFT_LEFT KEYCODE_DPAD_LEFT")
        time.sleep(0.4)
    st = tb(d, "preselected")
    if st.get("selStart") == st.get("selEnd"):
        raise Blocked(f"框里选不出一段（选区 {st.get('selStart')}–{st.get('selEnd')}）")
    text0, sel0 = st.get("text"), (st.get("selStart"), st.get("selEnd"))
    d.type("ni")
    d.cands()
    s = d.screen()
    row = d.preedit(s)
    st1 = tb(d, "preselected")
    # backspace swiped up: the code cleared
    bk = d.keys(refresh=True)["bksp"]
    d.swipe(bk, (bk[0], bk[1] - 150), ms=250, after=0.8)
    st_clear = tb(d, "preselected")
    d.type("ni")
    d.cands()
    # a tap elsewhere in the field: its left end
    n = next((x for x in d.screen().app() if x.rid == "preselected" and x.pkg == TESTBED), None)
    if n:
        d.tap((n.b[0] + 8, n.c[1]), 1.0)
    st_tap = tb(d, "preselected")
    d.back(1.0)
    time.sleep(1.0)
    st2 = tb(d, "preselected")
    check(st1.get("text") == text0 and (st1.get("selStart"), st1.get("selEnd")) == sel0, f"打字时框「{st1.get('text')}」选区 {st1.get('selStart')}–{st1.get('selEnd')}")
    check(row == "ni", f"小条上「{row}」")
    check(st_clear.get("text") == text0 and (st_clear.get("selStart"), st_clear.get("selEnd")) == sel0,
          f"清除后框「{st_clear.get('text')}」选区 {st_clear.get('selStart')}–{st_clear.get('selEnd')}")
    check(st_tap.get("text") == text0, f"点别处后框「{st_tap.get('text')}」")
    check(st2.get("text") == text0, f"收起后框「{st2.get('text')}」")
    return f"选区 {sel0} 不动，拼音在小条「{row}」"


@case("LC-23", page="", mutates=True)
def lc23(d):
    in_field(d, page="")
    d.type("ni")
    d.cands()
    tap_field(d, 1)
    d.type("hao")
    d.cands()
    d.space()
    time.sleep(0.6)
    f = fields(d)
    t0, t1 = (f[0].text if f else None), (f[1].text if len(f) > 1 else None)
    check(t1 == "好", f"「句子」框「{t1}」")
    check(t0 in ("", "ni", None), f"「普通文本」框「{t0}」")
    return f"「普通文本」「{t0}」（留下与否按设计都可以），「句子」「{t1}」"


@case("LC-24", mutates=True)
def lc24(d):
    in_field(d)
    d.type("nihao")
    d.cands()
    d.rotate(1)
    # the keyboard laid out anew after the turn: looked for a few seconds, not once (gone for a moment, the field
    # tapped would be under it, not in the dump: 页面上没有第 1 个框)
    try:
        d.wait(lambda s: s.find(rid="keyboard_view", where="ime"), 4, step=0.3, what="the keyboard back")
    except Blocked:
        tap_field(d)
    d.type("hao")
    d.cands()
    d.space()
    # read back in portrait: in landscape the keyboard leaves the page a strip about 40 px tall, the field under the
    # keyboard unless the page happened to scroll it into that strip, and a field not shown is not in the dump
    # (None, half the runs). The focused field, else the page's first, polled: a rebuilt page's field may not be focused
    d.rotate(0)
    want = ("好", "ni hao好")
    end = time.time() + 5
    while True:
        s = d.screen()
        f = fields(d, s)
        f0 = f[0].text if f else None
        t = d.field_text(s) if d.field(s) else f0
        if t in want or time.time() > end:
            break
        time.sleep(0.3)
    check(t in want, f"最后框里「{t}」，页上第一个框「{f0}」")
    return f"最后「{t}」"


case("LC-25", needs="manual:切到别的应用再回来、用输入法选择器换输入法：留下与否随系统，设备上手工看（设计页 M3、M6）")(lambda d: None)


@case("LC-26", mutates=True)
def lc26(d):
    testbed_in_field(d, "behavior", "settext_echo")
    d.type("n")
    time.sleep(0.8)
    t0, row0 = tb(d, "settext_echo"), d.preedit()
    d.type("nihao")
    d.cands()
    row = d.preedit()
    t1 = tb(d, "settext_echo").get("text")
    d.space()
    time.sleep(0.8)
    t = tb(d, "settext_echo").get("text")
    check(t0.get("text") == "n" and t0.get("compStart") == -1, f"第一个字母后框里「{t0.get('text')}」composing {t0.get('compStart')}")
    check(not row0, f"重启后键盘上还有「{row0}」")
    check(row == "ni hao" and t1 == "n", f"再打时小条「{row}」框「{t1}」")
    check(t == "n你好", f"最后框里「{t}」")
    return f"小条「{row}」，框「{t}」"


@case("LC-27", mutates=True)
def lc27(d):
    testbed_in_field(d, "behavior", "upper")
    d.type("n")
    time.sleep(0.8)
    t0 = tb(d, "upper").get("text")
    d.type("ihao")
    d.cands()
    row = d.preedit()
    d.space()
    time.sleep(0.8)
    t = tb(d, "upper").get("text")
    check(t0 == "N", f"第一个字母后框里「{t0}」")
    check(row == "ni hao", f"小条上「{row}」")
    check(t == "N你好", f"最后框里「{t}」")
    return f"「{t0}」→ 小条「{row}」→「{t}」"


@case("LC-28", mutates=True)
def lc28(d):
    testbed_in_field(d, "behavior", "null_before")
    d.type("nihao")
    d.cands()
    t0, row = tb(d, "null_before").get("text"), d.preedit()
    d.space()
    time.sleep(0.8)
    t = tb(d, "null_before").get("text")
    check(not t0, f"打字时框里「{t0}」")
    check(row == "ni hao", f"小条上「{row}」")
    check(t == "你好", f"最后框里「{t}」")
    return f"框里一直没有拼音；「{t}」"


@case("LC-29", mutates=True)
def lc29(d):
    testbed_in_field(d, "ime", "max10")
    d.type("nihaozhongguo")
    d.cands()
    t, row = tb(d, "max10"), d.preedit()
    # the code as shown, syllables apart: its first ten characters what the field took
    check(t.get("text") == "ni hao zho" and t.get("compStart") == -1, f"框里「{t.get('text')}」composing {t.get('compStart')}")
    check(row == "ni hao zhong guo", f"小条上「{row}」")
    return f"框「{t.get('text')}」，小条「{row}」"


def clears_composing(d, rid):
    testbed_in_field(d, "behavior", rid)
    d.type("nihao")
    d.cands()
    time.sleep(0.8)
    t, row = tb(d, rid).get("text"), d.preedit()
    d.space()
    time.sleep(0.8)
    return t, row, tb(d, rid).get("text")


@case("LC-30", mutates=True)
def lc30(d):
    out = []
    for rid in ("finish_composing", "finish_composing_async"):
        t, row, end = clears_composing(d, rid)
        # the app's posted clear is not reported: the next letter goes in beside it, one letter left at most
        left = ("", "nni") if rid.endswith("async") else ("",)
        check((t or "") in left, f"{rid}：打字时框里「{t}」")
        check(row == "ni hao", f"{rid}：小条上「{row}」")
        check(end in tuple(x + "你好" for x in left), f"{rid}：最后框里「{end}」")
        out.append(f"{rid}「{end}」")
    return "，".join(out)


@case("LC-31", mutates=True)
def lc31(d):
    testbed_in_field(d, "behavior", "terminal")
    d.type("nihao")
    d.cands()
    t0, row = tb(d, "terminal").get("text"), d.preedit()
    d.space()
    time.sleep(0.8)
    t = tb(d, "terminal").get("text")
    check(not t0, f"打字时框里「{t0}」")
    check(row == "ni hao", f"小条上「{row}」")
    check(t == "你好", f"最后框里「{t}」")
    return f"框里一直没有拼音；「{t}」"


@case("LC-32", mutates=True)
def lc32(d):
    testbed_in_field(d, "behavior", "no_id_a")
    d.type("ni")
    d.cands()
    n = next((x for x in d.screen().app() if x.desc.startswith("no_id_b") and x.pkg == TESTBED), None)
    if not n:
        raise Blocked("找不到 no_id_b 框")
    d.tap(n.c, 1.5)
    d._keys = None
    d.type("hao")
    d.cands()
    d.space()
    time.sleep(0.8)
    a, b = tb(d, "no_id_a").get("text"), tb(d, "no_id_b").get("text")
    check(b == "好", f"no_id_b「{b}」")
    check(a in ("", "ni"), f"no_id_a「{a}」")
    return f"no_id_a「{a}」（留下与否按设计都可以），no_id_b「{b}」"


case("LC-33", needs="manual:测试台 webview 页的 w_rewrite 在网页下方，脚本的滚动找不到它（网页里的框不随列表滚动报告）；同 LC-27，在模拟器上手工跑")(lambda d: None)


@case("LC-34", mutates=True)
def lc34(d):
    testbed_in_field(d, "behavior", "chat_settext")
    d.type("nihao")
    d.cands()
    d.space()
    time.sleep(0.6)
    before = tb(d, "chat_settext").get("text")
    d.enter()
    # the framework's own restart after setText: deferred from API 30
    time.sleep(1.2)
    sent = tb(d, "chat_settext").get("text")
    d.type("ni")
    d.cands()
    time.sleep(0.6)
    t, row = tb(d, "chat_settext"), d.preedit()
    d.space()
    time.sleep(0.8)
    end = tb(d, "chat_settext").get("text")
    check(before == "你好", f"发送前框里「{before}」")
    check(not sent, f"发送后框里「{sent}」")
    check(t.get("text") == "ni" and t.get("compStart") == 0, f"再打时框里「{t.get('text')}」composing {t.get('compStart')}")
    check(not row, f"键盘上方还有「{row}」")
    check(end == "你", f"最后框里「{end}」")
    return f"「{before}」发出，再打「{t.get('text')}」→「{end}」"

case("LC-35", needs="jvm:HostCasesUatTest.aCharacterNotCommittedIsPutInWhenTheKeyboardGoes、KeyHandwritingUatTest.theKeyboardHiddenAndShownAgainStillWrites")(lambda d: None)
