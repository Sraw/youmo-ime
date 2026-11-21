"""uat/02-keyboard.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import Blocked, case, check, choose, has, home, script


@case("KB-01")
def kb01(d):
    d.type("ni")
    c = d.cands(want="你")
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(not t, f"框里是「{t}」")
    check(p == "ni", f"拼音行 `{p}`")
    check(has(c, "你") and not s.find(desc="展开工具栏", where="ime"), f"候选 {c[:5]}")
    return f"`{p}`，候选 {c[:4]}"


@case("KB-112")
def kb112(d):
    d.open_page("text")
    d.clear()
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    d.expect_field("你好")
    d.type("nihao")
    d.cands(want="你好")
    bksp = d.keys(refresh=True)["bksp"]
    # 4 s: the pinyin gone in well under a second, then held on past the stop; letting go deletes nothing either
    d.hold(bksp, 4000)
    d.idle()
    s = d.screen()
    p, t = d.preedit(s), d.field_text(s)
    check(not p, f"按住 4 秒后编码行还有「{p}」")
    check(t == "你好", f"打拼音时按住退格（删完后接着按住再松手），框里成了「{t}」")
    d.hold(bksp, 2000)
    d.expect_field("", msg="没在打字时按住退格，框里还剩「{got}」")
    return "第一次按住 4 秒：删光拼音、停住后再松手，框里留着「你好」；第二次按住删空"


@case("KB-31", base="B0-设置", mutates=True, visual=True)
def kb31(d):
    # the reserved row is the embedded code row's; the default (floating, ST-48) has none
    home(d, "外观")
    d.tap_text("大小与样式", scroll=False, after=1.0)
    choose(d, "编码行", "嵌在键盘里")
    d.open_page("chat")

    def where(s):
        f = d.field(s)
        kb = s.find(rid="keyboard_view", where="ime")
        send = next((n for n in s.app() if n.text in ("发送", "Send") or n.desc == "发送"), None)
        return (f.b if f else None, kb.b[1] if kb else None, send.b if send else None)
    w0 = where(d.screen())
    d.shot("full", "没打字时")
    d.type("nihao")
    d.cands()
    s = d.screen()
    w1, p = where(s), d.preedit(s)
    d.shot("full", "打 nihao 时")
    d.bksp(5)
    time.sleep(0.5)
    w2 = where(d.screen())
    check(p == "ni hao", f"拼音行 `{p}`")
    check(w0 == w1 == w2, f"位置变了：没打字 {w0}，打字 {w1}，删光 {w2}")
    check(w0[0] and w0[1] and w0[0][3] <= w0[1], f"聊天框 {w0[0]} 被挡住（键盘顶 {w0[1]}）")
    return f"聊天框 {w0[0]}、键盘顶 {w0[1]} 始终不动；拼音行 `{p}`"




case("KB-33", needs="jvm:HostCasesUatTest.aGlideGoesInWithItsSpaceItsWordChangedOrErasedWholeAMarkTakesTheSpace、KeyboardFieldsUatTest.aGlideDrawsTheFingersTrail、"
     "TextSceneWiringTest.aGlideReadsEachLetterWhereTheKeySendingItIsWhateverItsId")(lambda d: None)


@case("KB-106", visual=True)
def kb106(d):
    d.open_page("text")
    d.clear()
    check("shift" in d.keys(refresh=True) and "分词" not in d.keys(), "没打字时 ⇧ 的位置不是 ⇧")
    d.type("xi", after=0.6)
    k = d.keys(refresh=True)
    check("分词" in k and "shift" not in k, f"打 xi 后 ⇧ 的位置没变成分词：{sorted(k)}")
    d.shot("kb", "打 xi 时 ⇧ 的位置是分词")
    d.tap(k["分词"], after=0.4)
    d.type("an", after=0.8)
    pre = d.preedit()
    check(pre.replace(" ", "'") == "xi'an", f"xi 分词 an 的编码行是「{pre}」，不是 xi'an")
    d.pick("西安")
    d.expect_field("西安")
    check("shift" in d.keys(refresh=True), "上屏后没变回 ⇧")
    return f"编码行「{pre}」，上屏「西安」"


@case("KB-104", mutates=True)
def kb104(d):
    home(d, "通用输入")
    choose(d, "在键上划", "笔画筛选")
    d.open_page("text")
    d.clear()
    dp = d.dpx()
    d.type("hao", after=0.6)
    q = d.keys(refresh=True)["q"]
    # 15dp sideways in 150ms: short of 笔画筛选灵敏度 (中, 24dp), the q key's tap
    d.swipe(q, (q[0] + int(15 * dp), q[1]), 150, after=0.8)
    short = d.preedit()
    d.clear()
    d.type("hao", after=0.6)
    q = d.keys(refresh=True)["q"]
    # 40dp: past it, a 横
    d.swipe(q, (q[0] + int(40 * dp), q[1]), 150, after=0.8)
    long = d.preedit()
    d.clear()
    check(short.replace(" ", "").replace("'", "") == "haoq", f"打 hao 后在 q 上横滑 15dp，编码行是「{short}」，不是 haoq")
    check(long.endswith("一") and "q" not in long, f"打 hao 后在 q 上横划 40dp，编码行是「{long}」，末尾不是一笔横")
    return f"15dp：「{short}」；40dp：「{long}」"


case("KB-105", needs="jvm:SwipePopupScreenshotTest.uprightOnTheCanvas、SwipePopupScreenshotTest.compactOnTheCanvas、SwipePopupScreenshotTest.floatingOnTheCanvas、"
     "SwipePopupScreenshotTest.turnedOnTheCanvas、KeyShortcutsScreenshotTest.theEditMenuOverTheSpace、BuiltinThemeEquivalenceTest.theMenusCellInFocusStandsOutInEveryBuiltInTheme、"
     "PopupsTest.aMenusCellInFocusIsLargerAndReturnsColourOneDisabledFaded、PopupsTest.aMenusCellInFocusThatIsDisabledKeepsReturnsColourFaded")(lambda d: None)


def testbed_field(d, rid):
    """the testbed's types page, its field [rid] tapped: the keyboard up on it"""
    from uat import TESTBED
    d.sh(f"am force-stop {TESTBED}")
    d.testbed("types")
    def field(s):
        n = next((x for x in s.app() if x.rid == rid and x.pkg == TESTBED), None)
        # the date and later fields are below the first screen
        if not n and any(x.pkg == TESTBED for x in s.app()):
            d.scroll(s)
        return n
    n = d.wait(field, 15, what=f"the testbed's {rid} field")
    d.tap(n.c, 1.5)
    d.page = None
    d.host = None
    d._keys = None


@case("KB-85", mutates=True, visual=True)
def kb85(d):
    st = script("14-settings")
    st.slots_tab(d, "全键盘")
    st.key_dialog(d, "q")
    st.dialog_way(d, "上滑", "命令")
    if not d.screen().find(text="滑选", where="all"):
        d.tap_text("换", scroll=False, after=0.8)
    d.tap_text("滑选", where="all", scroll=False, after=0.6)
    st.dialog_ok(d)
    d.step("多行框折成几行")
    testbed_field(d, "multiline")
    # one paragraph, no newline: the field wraps it into screen lines (a chunk's enter commits its letters)
    for _ in range(24):
        d.type("abcdefghij")
        d.enter()
    time.sleep(0.5)
    ml = lambda: d.testbed_state()["multiline"]
    t = ml()["text"]
    check(len(t) == 240 and "\n" not in t, f"框里是 {t!r}")
    p = d.dpx()
    # the cursor into the middle by the space slide (KB-70): sideways, then four screen lines up
    x, y = d.keys(refresh=True)["space"]
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 25 * p, y),
            *[("move", x - 25 * p, y - dy * p) for dy in range(16, 65, 16)], ("up", x - 25 * p, y - 64 * p), ("sleep", 0.3))
    c0 = ml()["selStart"]
    if not 40 < c0 < 200:
        raise Blocked(f"空格滑动没把光标放到中间：{c0}")
    d.shot("app", "光标在中间一行")
    d.step("滑选上下挪")
    qx, qy = d.keys(refresh=True)["q"]
    sel = lambda: (ml()["selStart"], ml()["selEnd"])
    free = lambda a, b: a if b == anchor else b
    # 42 dp up: past the 36 dp swipe threshold; 30 dp fell short
    d.touch(("down", qx, qy), ("move", qx, qy - 21 * p), ("move", qx, qy - 42 * p), ("sleep", 0.2),
            ("move", qx - 10 * p, qy - 42 * p), ("sleep", 0.3))
    s1 = sel()
    check(s1 in ((c0 - 1, c0), (c0, c0 - 1)), f"往左挪一个字后选区 {s1}，起点 {c0}")
    anchor = c0
    for i in range(1, 4):
        d.touch(("move", qx - 10 * p, qy - 42 * p - i * 28 * p), ("sleep", 0.3))
    s2 = sel()
    f2 = free(*s2)
    d.shot("app", "滑选上挪三行：选区跨四行，起点不动")
    # three screen lines: some 15 to 80 letters a line, never the paragraph's start (a logical line up)
    check(anchor in s2 and 45 <= anchor - f2 <= 240 and f2 > 0, f"上挪三行后选区 {s2}，起点 {anchor}")
    for i in range(2, -3, -1):
        d.touch(("move", qx - 10 * p, qy - 42 * p - i * 28 * p), ("sleep", 0.3))
    s3 = sel()
    f3 = free(*s3)
    check(anchor in s3 and 30 <= f3 - anchor <= 160, f"往下挪回五行后选区 {s3}，起点 {anchor}")
    d.touch(("move", qx - 20 * p, qy - 42 * p + 2 * 28 * p), ("sleep", 0.3))
    s4 = sel()
    check(anchor in s4 and free(*s4) == f3 - 1, f"再往左挪一个字后选区 {s4}，之前 {s3}")
    d.touch(("up", qx - 20 * p, qy - 42 * p + 2 * 28 * p), ("sleep", 0.5))
    after = ml()
    check(after["text"] == t and (after["selStart"], after["selEnd"]) == s4, f"松手后框里 {after['text'][:20]!r}…，选区 {(after['selStart'], after['selEnd'])}")
    d.step("单行框")
    testbed_field(d, "text")
    d.type("hello")
    d.enter()
    time.sleep(0.3)
    one = lambda: d.testbed_state()["text"]
    qx, qy = d.keys(refresh=True)["q"]
    d.touch(("down", qx, qy), ("move", qx, qy - 21 * p), ("move", qx, qy - 42 * p), ("sleep", 0.2),
            ("move", qx - 10 * p, qy - 42 * p), ("sleep", 0.3), ("move", qx - 10 * p, qy - 42 * p - 50 * p), ("sleep", 0.3),
            ("up", qx - 10 * p, qy - 42 * p - 50 * p), ("sleep", 0.3))
    o = one()
    check(o["text"] == "hello" and (o["selStart"], o["selEnd"]) == (4, 5), f"单行框：{o['text']!r} 选区 {(o['selStart'], o['selEnd'])}，应只选中 o")
    return f"起点 {anchor}：上挪三行到 {f2}，下挪到 {f3}，左挪到 {free(*s4)}"


def three_lines(d):
    """the testbed's 多行 field holding aaa, bbb, ccc on three lines, the cursor at the end"""
    from uat import TESTBED
    d.sh(f"am force-stop {TESTBED}")
    d.testbed("types")
    n = d.wait(lambda s: next((x for x in s.app() if x.rid == "multiline" and x.pkg == TESTBED), None), 15,
               what="the testbed's 多行 field")
    d.tap(n.c, 1.5)
    d.page = None
    d.host = None
    d._keys = None
    for i, w in enumerate(("aaa", "bbb", "ccc")):
        d.type(w)
        d.enter()          # the letters as typed
        if i < 2:
            d.enter()      # a new line
    time.sleep(0.5)
    st = d.testbed_state()["multiline"]
    t = st["text"]
    check(t == "aaa\nbbb\nccc", f"框里是 {t!r}")
    return t


@case("KB-70", visual=True)
def kb70(d):
    t = three_lines(d)
    line = lambda: t.count("\n", 0, d.testbed_state()["multiline"]["selStart"])
    x, y = d.keys(refresh=True)["space"]
    p = d.dpx()
    seen = []
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 16 * p, y), ("move", x - 25 * p, y), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    d.touch(("move", x - 25 * p, y - 16 * p), ("move", x - 25 * p, y - 32 * p), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    d.touch(("move", x - 25 * p, y - 48 * p), ("move", x - 25 * p, y - 62 * p), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    d.shot("app", "横滑后上挪两行")
    d.touch(("move", x - 25 * p, y - 20 * p), ("up", x - 25 * p, y - 20 * p), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    lines = [s[1] for s in seen]
    check(seen[0][0] < 11 and lines == [2, 1, 0, 1], f"光标 {seen}")
    check(d.testbed_state()["multiline"]["text"] == t, "没有打出字")
    return f"光标（位置, 行）{seen}"


@case("KB-71", visual=True)
def kb71(d):
    t = three_lines(d)
    ml = lambda: d.testbed_state()["multiline"]
    x, y = d.keys(refresh=True)["space"]
    p = d.dpx()
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 16 * p, y), ("move", x - 25 * p, y), ("sleep", 0.3))
    for dy in range(30, 181, 30):
        d.touch(("move", x - 25 * p, y - dy * p))
    d.touch(("sleep", 0.3))
    top = ml()
    d.shot("app", "上挪过头")
    for dy in range(150, -21, -30):
        d.touch(("move", x - 25 * p, y - dy * p))
    d.touch(("up", x - 25 * p, y + 20 * p), ("sleep", 0.3))
    end = ml()
    check(top["focused"] and top["selStart"] == 0, f"上挪过头：{top['selStart']} focused={top['focused']}")
    check(end["focused"] and end["selStart"] == len(t), f"下挪过头：{end['selStart']} focused={end['focused']}")
    t = three_lines(d)
    first = t.index("\n")
    x, y = d.keys(refresh=True)["space"]
    # one 60 dp move: two of the space's 28 dp line steps in one sample, a rush
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 16 * p, y), ("move", x - 25 * p, y), ("sleep", 0.3),
            ("move", x - 25 * p, y - 60 * p), ("up", x - 25 * p, y - 60 * p), ("sleep", 0.3))
    two = ml()
    d.shot("app", "快挪两行")
    check(two["focused"] and 0 < two["selStart"] <= first, f"快挪两行：{two['selStart']}（第一行是 1..{first}）")
    return f"上到 {top['selStart']}，下到 {end['selStart']}，焦点一直在多行框"




case("KB-84", needs="manual:18 键 L 的弹出在微软双拼和拼音下各看一次；规则由 KeyResolverTest.eighteenLsColonOnlyOnASchemeTypingSemicolon 查")(lambda d: None)


@case("KB-80", base="B0-注音全键盘", mutates=True, visual=True)
def kb80(d):
    st = script("14-settings")
    d.open_page("text")
    d.clear()
    b = d.face("ㄅ")
    before = d.held(b)
    check("1" not in before and b.desc in ("", "ㄅ"), f"改之前 ㄅ 长按弹出 {before}，读作「{b.desc}」")
    d.clear()
    st.slots_tab(d, "注音全键盘")
    st.key_dialog(d, "ㄅ")
    st.dialog_add_digits(d, "1")
    st.dialog_way(d, "上滑", "长按首选")
    st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    b = d.face("ㄅ")
    d.shot("kb", "ㄅ 的键角画 1")
    corner = b.desc
    after = d.held(b)
    let_go = d.field_text()
    d.clear()
    d.swipe(b.c, (b.c[0], b.c[1] - 130), 150, after=0.8)
    up = d.field_text()
    check("1" in after, f"改之后 ㄅ 长按弹出 {after}")
    check(corner.endswith("1"), f"ㄅ 的键角读作「{corner}」")
    check(let_go == "1" and up == "1", f"长按松手打出「{let_go}」，上滑打出「{up}」")
    return f"改前长按无弹窗；改后弹出 1、键角 1，松手「{let_go}」，上滑「{up}」"


@case("KB-81", mutates=True, visual=True)
def kb81(d):
    st = script("14-settings")
    st.slots_tab(d, "功能键")
    st.key_dialog(d, "，键")
    st.dialog_pick(d, '"')
    d.tap_text("设为首选", scroll=False, after=0.5)
    st.dialog_way(d, "上滑", "长按首选")
    st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    c = d.face("，", ",")
    corner = c.desc
    # swiped first: the engine's “ ” alternation is its own state, which a clear does not reset
    d.swipe(c.c, (c.c[0], c.c[1] - 130), 150, after=1.0)
    one = d.field_text()
    d.swipe(c.c, (c.c[0], c.c[1] - 130), 150, after=1.0)
    two = d.field_text()
    d.clear()
    d.touch(("down", *c.c), ("sleep", 0.9))
    d.shot("kb", "长按 ，：“ 带框；键角 “")
    shown = [n.text for n in d.screen().nodes if n.text in ("“", '"')]
    d.touch(("up", *c.c))
    time.sleep(0.5)
    d.clear()
    check("“" in shown and '"' not in shown, f"长按弹窗里是 {shown}")
    check(corner.endswith("“"), f"， 的键角读作「{corner}」")
    # 成对符号 (KB-26) on by default: the first “ comes with its ”, the second (the engine's ”) steps over it
    check(one == "“”" and two == "“”", f"上滑一次「{one}」，两次「{two}」")
    return f"弹窗 {shown}、键角「{corner}」；上滑一次「{one}」，两次「{two}」"


@case("KB-72", mutates=True, visual=True)
def kb72(d):
    st = script("14-settings")
    tb = script("05-toolbar")
    d.sh("wm density 480")
    time.sleep(1.5)
    for key in ("j", "k"):
        st.slots_tab(d, "全键盘")
        st.key_dialog(d, key)
        st.dialog_text(d, "下滑", "xianzaizenmeyangle", "现在怎么样了")
        st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    d.shot("kb", "下滑图标默认左上：j、k 画「现在…」「现…」或两条横线的图标，别的键不缩小")
    j = d.keys(refresh=True)["j"]
    d.swipe(j, (j[0], j[1] + 130), 150, after=0.8)
    typed = d.field_text()
    d.clear()
    home(d, "按键")
    choose(d, "下滑图标位置", "底部")
    d.open_page("text")
    d.shot("kb", "下滑图标在底部：j、k")
    st.appearance_tab(d, "大小与样式")
    choose(d, "键盘高度", "紧凑")
    d.open_page("text")
    d.shot("kb", "紧凑：j、k")
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    d.shot("kb", "紧凑、单手：j、k")
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    tb.floating(d)
    d.shot("kb", "紧凑、悬浮：j、k")
    st.slots_tab(d, "全键盘")
    st.key_dialog(d, "a")
    st.dialog_text(d, "下滑", "nihao", "你好")
    st.dialog_ok(d)
    home(d, "按键")
    choose(d, "上滑图标位置", "左下")
    d.open_page("text")
    d.clear()
    d.shot("kb", "a：上滑左下、下滑底部，碰上时省的是 @，你好照画")
    a = d.keys(refresh=True)["a"]
    d.swipe(a, (a[0], a[1] + 130), 150, after=0.8)
    hello = d.field_text()
    check(typed == "现在怎么样了", f"j 下滑打出「{typed}」")
    check(hello == "你好", f"a 下滑打出「{hello}」")
    return f"j 下滑「{typed}」，a 下滑「{hello}」"


@case("KB-87", base="B0-设置", mutates=True, visual=True)
def kb87(d):
    st = script("14-settings")
    long = "13895801474"
    st.slots_tab(d, "全键盘")
    st.key_dialog(d, "q")
    st.dialog_add_digits(d, long)
    st.dialog_pick(d, long)
    d.tap_text("设为首选", scroll=False, after=0.4)
    st.dialog_ok(d)
    d.shot("app", "页面上 q 的键角画 13…")
    d.open_page("text")
    d.clear()
    q = d.face("q", "Q")
    d.shot("kb", "q 的键角画 13…，不伸出键外")
    # the node gives the corner whole (as said and typed), not as drawn: 「13…」 is checked on the screenshot
    corner = q.alt or q.desc
    d.swipe(q.c, (q.c[0], q.c[1] - 130), 150, after=0.8)
    up = d.field_text()
    check(long in corner, f"q 的键角读作「{corner}」，应读整串 {long}")
    check(up == long, f"上滑 q 打出「{up}」")
    return f"键角「{corner}」，上滑「{up}」"


case("KB-88", needs="manual:输入测试台 null_before 框里退格上滑清除、下滑恢复，光标留在原处；还没写成脚本，在模拟器上手工跑")(lambda d: None)


def board_keys(d):
    """the canvas's keys on the screen by their scene id (Dumper's key=): {id: (bounds, text or description)}"""
    out = {}
    for n in d.screen().ime():
        if n.key and n.key not in out:
            out[n.key] = (n.b, n.text or n.desc)
    return out


def tap_key(d, id, after=0.6):
    """a canvas key by its scene id"""
    k = board_keys(d).get(id)
    if not k:
        raise Blocked(f"键盘上没有 {id}")
    b = k[0]
    d.tap(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2), after)


@case("KB-90", base="B0-九键", visual=True)
def kb90(d):
    ids = [str(i) for i in range(1, 10)] + ["backspace", "enter", "symbols", "numbers"]
    nine = board_keys(d)
    if any(i not in nine for i in ids):
        raise Blocked(f"九键上缺 {[i for i in ids if i not in nine]}")
    d.shot("kb", "九键")
    tap_key(d, "numbers")
    pad = board_keys(d)
    d.shot("kb", "九键点 123 后的数字键盘")
    pairs = [(i, i) for i in ids[:-1]] + [("back", "numbers")]
    moved = [f"{p}{pad.get(p, (None,))[0]}≠{nine[t][0]}" for p, t in pairs if pad.get(p, (None,))[0] != nine[t][0]]
    check(not moved, f"不在九键的位置：{moved}")
    check(pad["symbols"][1] == "符" and pad["back"][1] == "返回", f"符、返回 写的是「{pad['symbols'][1]}」「{pad['back'][1]}」")
    tap_key(d, "back")
    again = board_keys(d)
    check(all(again.get(i, (None,))[0] == nine[i][0] for i in ids), "返回 后九键的键挪了")
    return "1–9、⌫、回车、符 原位，返回 在 123 的位置；返回 后九键不变"


@case("KB-91", visual=True)
def kb91(d):
    tap_key(d, "symbols")
    keys = board_keys(d)
    row = ["back", "numbers", "lock", "space", "backspace", "enter"]
    order = sorted((k for k in row if k in keys), key=lambda k: keys[k][0][0])
    d.shot("kb", "符号面板")
    check(order == row, f"底排 {order}")
    check(keys["numbers"][1] == "123" and keys["back"][1] == "返回", f"返回、123 写的是「{keys['back'][1]}」「{keys['numbers'][1]}」")
    tap_key(d, "numbers")
    pad = board_keys(d)
    check("=" in pad and "back" in pad, f"123 后不是数字键盘：{sorted(pad)[:8]}")
    tap_key(d, "symbols")
    check("lock" in board_keys(d), "数字键盘的 符 没打开符号面板")
    tap_key(d, "back")
    check("=" in board_keys(d), "符号面板的 返回 没回到数字键盘")
    return "返回、123、锁…；123 进数字键盘，符 进面板，返回 回到数字键盘"


@case("KB-92", page="tel", visual=True)
def kb92(d):
    pad = board_keys(d)
    d.shot("kb", "电话框的数字键盘")
    check(pad.get("back", (None, ""))[1] == "返回", f"电话框数字键盘的返回键写「{pad.get('back', (None, ''))[1]}」")
    tap_key(d, "back")
    letters = board_keys(d)
    check("numbers" in letters and "back" not in letters, "返回 后不是字母键盘")
    s = d.screen()
    emoji = s.find(text="表情", where="ime") or s.find(desc="表情", where="ime")
    if not emoji:
        raise Blocked("工具栏上没有表情")
    d.tap(emoji.c, 0.8)
    said = {"表情": board_keys(d).get("back", (None, ""))[1]}
    d.shot("kb", "表情面板")
    tap_key(d, "emoticon")
    said["颜文字"] = board_keys(d).get("back", (None, ""))[1]
    d.shot("kb", "颜文字面板")
    check(all(v == "返回" for v in said.values()), f"返回键写的是 {said}")
    return "电话框数字键盘、表情、颜文字的返回键都写「返回」"


def hold_pick(d, xy, text, shot=None):
    """[xy] held: its long press's cell reading [text] picked, the popup's texts as they were. The focus follows the
    finger's way from where the popup opened, a step each 0.6 of a cell (PopupFocus), not the cell under it: the
    finger moved from the cell in focus by the cells between them, at that rate, and lifted"""
    x, y = xy
    d.touch(("down", x, y), ("sleep", 0.9), ("move", x, y))
    s = d.screen()
    if shot:
        d.shot("full", shot)
    cells = [n for n in s.nodes if n.text and not n.clickable and n.b[3] <= y]
    texts = s.texts(where="all")
    # the popup's own: the one in focus and the one reading [text] nearest it
    focused = next((n for n in cells if n.selected), None)
    cell = min((n for n in cells if n.text == text), default=None,
               key=lambda n: (n.c[0] - (focused or n).c[0]) ** 2 + (n.c[1] - (focused or n).c[1]) ** 2)
    if not cell or not focused:
        d.touch(("up", x, y))
        raise Blocked(f"长按弹出里没有「{text}」或没有选中的格：{texts[-16:]}")
    w, h = focused.b[2] - focused.b[0], focused.b[3] - focused.b[1]
    cols = round((cell.c[0] - focused.c[0]) / w)
    rows = round((cell.c[1] - focused.c[1]) / h)
    tx, ty = x + int(cols * 0.6 * w), y + int(rows * 0.6 * h)
    d.touch(("move", (x + tx) // 2, (y + ty) // 2), ("move", tx, ty), ("sleep", 0.2), ("up", tx, ty))
    time.sleep(0.5)
    return texts


def checked_box(d, text):
    """the first check box reading [text] (the one by the field: the ways' come after it) ticked"""
    s = d.screen()
    box = min((n for n in s.app() if n.text == text and n.cls.endswith("CheckBox")), key=lambda n: n.b[1], default=None)
    if not box:
        raise Blocked(f"对话框里没有勾选框「{text}」")
    d.tap(box.c, 0.5)


def row_toggle(d, text):
    """the button row's toggle reading [text] (not the check box by the field)"""
    n = next((n for n in d.screen().app() if n.text == text and n.clickable and not n.cls.endswith("CheckBox")), None)
    if not n:
        raise Blocked(f"按钮排里没有「{text}」")
    return n


@case("KB-93", base="B0-设置", mutates=True, visual=True)
def kb93(d):
    st = script("14-settings")
    st.slots_tab(d, "全键盘")
    st.key_dialog(d, "q")
    checked_box(d, "使用变量")
    d.tap_text("插入变量", scroll=False, after=0.8)
    d.tap_text("${weekday_cn}", contains=True, after=0.6)
    s = d.screen()
    now = next((n.text for n in s.app() if n.text.startswith("现在打出：")), None)
    check(now, f"框下没有「现在打出：」：{s.texts()[-12:]}")
    value = now.removeprefix("现在打出：")
    d.tap_text("添加", scroll=False, after=0.6)
    d.shot("app", "q 的对话框：加了一项「变量」")
    marked = any(n.desc == f"{value}, 变量" or n.desc == "${weekday_cn}, 变量" for n in d.screen().app())
    st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    q = d.face("q", "Q")
    shown = hold_pick(d, q.c, value, shot=f"长按 q：弹出里是今天的「{value}」")
    got = d.expect_field(value, msg="选了打出「{got}」，应打出 " + value)
    check(marked, "对话框里那一项读屏没念「…，变量」")
    check("${weekday_cn}" not in shown, "弹出里画着 ${weekday_cn}，不是它的值")
    return f"预览「{now}」，弹出有「{value}」，选了打出「{got}」"


case("KB-96", needs="manual:键盘开着等到下一分钟再选带变量的项，打出新时间；JVM 见 KeyResolverMarksTest.outputFillsInAgainAsPicked、"
                     "TextVariablesUatTest.rowsCountedInANewMinuteLeaveTheCatchUpToDrawIt")(lambda d: None)


def comma_as_is(d):
    """，键's `.` marked 不转中文标点 on the settings page"""
    st = script("14-settings")
    st.slots_tab(d, "功能键")
    st.key_dialog(d, "，键")
    st.dialog_pick(d, ".")
    t = row_toggle(d, "不转中文标点")
    d.tap(t.c, 0.5)
    n = row_toggle(d, "不转中文标点")
    # its name the words, on or off its state (read 「不转中文标点，已开」)
    said = f"{n.desc}，{'已开' if n.checked else '已关'}"
    d.shot("app", "，键的对话框：`.` 开了不转中文标点，键帽下写「原样」")
    st.dialog_ok(d)
    return said


@case("KB-98", base="B0-设置", mutates=True, visual=True)
def kb98(d):
    said = comma_as_is(d)
    d.open_page("text")
    d.clear()
    comma = board_keys(d).get("comma")
    if not comma:
        raise Blocked(f"键盘上没有，键：{sorted(board_keys(d))[:20]}")
    b = comma[0]
    xy = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    shown = hold_pick(d, xy, ".", shot="长按，键：`.` 画成 `.`，别的照旧是中文标点")
    got = d.expect_field(".", msg="选 `.` 打出「{got}」，应是 `.`")
    d.clear()
    d.type("ni")
    first = (d.cands() or [""])[0]
    hold_pick(d, xy, ".")
    then = d.expect_field(first + ".", msg=f"打着 ni 选 `.` 打出「{{got}}」，应是「{first}.」")
    check(said == "不转中文标点，已开", f"按钮排的开关读作「{said}」")
    return f"开关读作「{said}」；弹出 {[t for t in shown if t in ('.', '。', '，', '？', '！')]}；打出「{got}」，打着 ni「{then}」"


def q_up_command(d, command, note, way="上滑"):
    """q's [way] set to [command] from the commands' list, the list shot where it shows"""
    st = script("14-settings")
    st.slots_tab(d, "全键盘")
    st.key_dialog(d, "q")
    st.dialog_way(d, way, "命令")
    s = d.screen()
    change = [n for n in s.app() if n.text == "换"]
    if len(change) != 1:
        raise Blocked(f"{way}卡片里的「换」不是一个：{s.texts()[-16:]}")
    d.tap(change[0].c, 0.8)
    for _ in range(4):
        if any(n.text == command for n in d.screen().app()):
            break
        st.dialog_scroll(d)
    s = d.wait(lambda s: s if any(n.text == command for n in s.app()) else None, 5, what=f"命令列表里的{command}")
    listed = [n.text for n in s.app() if n.text]
    d.shot("app", note)
    d.tap(next(n for n in s.app() if n.text == command).c, 0.6)
    st.dialog_ok(d)
    return listed


def im_name(d):
    return d.im_label().split("，", 1)[-1]


@case("KB-113", mutates=True, visual=True)
def kb113(d):
    home(d, "按键")
    d.tap_text("显示语言切换键", after=0.8)
    listed = q_up_command(d, "切输入法", "命令列表：切键盘后面是切输入法、选输入法")
    at = listed.index("切键盘") if "切键盘" in listed else -1
    check(at >= 0 and listed[at + 1:at + 3] == ["切输入法", "选输入法"], f"命令列表 {listed}")
    d.open_page("text")
    d.clear()
    k = d.keys(refresh=True)
    d.shot("kb", "没有地球键，q 右上角是地球图标")
    check("globe" not in k and "lang" not in k, f"底排还有地球键：{sorted(k)}")
    was = im_name(d)
    q = k["q"]
    p = d.dpx()
    d.swipe(q, (q[0], q[1] - 60 * p), 150, after=1.0)
    one = im_name(d)
    d.shot("kb", "q 上滑一次")
    d.swipe(q, (q[0], q[1] - 60 * p), 150, after=1.0)
    two = im_name(d)
    typed = d.field_text() or ""
    check(was == "拼音" and one == "英文" and two == "拼音", f"上滑前「{was}」，一次「{one}」，两次「{two}」")
    check(typed == "", f"框里打出了「{typed}」")
    return f"无地球键；上滑 {was} → {one} → {two}"


def im_menu_held(d, dx_dp=0.0, back=False):
    """q slid up past the threshold and held, then [dx_dp] sideways (or [back] down onto q): the menu's
    texts as the screen reads them, and the lift's point"""
    q = d.keys(refresh=True)["q"]
    p = d.dpx()
    top = q[1] - 60 * p
    d.touch(("down", *q), *[("move", q[0], q[1] - dy * p) for dy in (15, 30, 45, 60)], ("sleep", 0.2),
            ("move", q[0] + dx_dp * p, top), ("sleep", 0.3))
    if back:
        d.touch(("move", q[0], q[1]), ("sleep", 0.2))
    return d.screen(), ((q[0], q[1]) if back else (q[0] + dx_dp * p, top))


def menu_cells(s):
    names = ("拼音", "英文", "双拼", "更多…")
    return [n.text for n in sorted(s.nodes, key=lambda n: n.c[0]) if n.text in names and not n.key and not n.desc.startswith("空格")]


@case("KB-114", mutates=True, visual=True)
def kb114(d):
    from uat import add_im
    add_im(d, "双拼")
    q_up_command(d, "选输入法", "命令列表：选输入法")
    d.open_page("text")
    d.clear()
    d.switch_im("拼音")
    s, at = im_menu_held(d)
    cells = menu_cells(s)
    d.shot("full", "q 上滑不松手：输入法菜单，拼音灰、手指对着下一个")
    d.touch(("up", *at))
    time.sleep(1.0)
    first = im_name(d)
    check(cells[:1] == ["拼音"] and cells[-1:] == ["更多…"] and len(cells) == 4, f"菜单格子 {cells}")
    check(first == cells[1], f"直接松开换到「{first}」，菜单 {cells}")

    d.switch_im("拼音")
    s, at = im_menu_held(d, dx_dp=60)
    d.shot("full", "右挪一格")
    d.touch(("up", *at))
    time.sleep(1.0)
    moved = im_name(d)
    check(moved == cells[2], f"右挪一格松开换到「{moved}」，菜单 {cells}")

    s, at = im_menu_held(d, back=True)
    d.touch(("up", *at))
    time.sleep(1.0)
    kept, typed = im_name(d), d.field_text() or ""
    check(kept == moved and typed == "", f"滑回键上松开后是「{kept}」，框里「{typed}」")

    s, at = im_menu_held(d, dx_dp=400)
    d.touch(("up", *at))
    time.sleep(1.0)
    s = d.screen()
    picker = s.find(text="选择输入法", where="all")
    d.shot("full", "挪到更多…松开：输入法选择框")
    check(picker is not None, f"更多… 松开后没有输入法选择框：{s.texts('all')[:10]}")
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(0.8)

    tb = script("05-toolbar")
    d.open_page("text")
    tb.floating(d)
    s, at = im_menu_held(d)
    floating_cells = menu_cells(s)
    d.shot("full", "悬浮键盘：输入法菜单在键盘朝屏幕中间那侧")
    d.touch(("up", *at))
    time.sleep(1.0)
    after = im_name(d)
    # a row as wide as the floating keyboard: fewer cells, the current one first and 更多… last
    check(floating_cells[:1] == [kept] and floating_cells[-1:] == ["更多…"] and after == floating_cells[1], f"悬浮时菜单 {floating_cells}，松开后「{after}」")
    return f"菜单 {cells}；松开 {first}，右挪 {moved}，滑回不变，更多… 弹出选择框；悬浮 {floating_cells} → {after}"


def down_menu(d, *moves):
    """q slid down to where a swipe opens its menu (45 dp: the 36 dp threshold passed), then each (dx, dy) in dp
    from q's middle in one jump, as a quick finger: the screen at the end and the last point"""
    q = d.keys(refresh=True)["q"]
    p = d.dpx()
    at = lambda dx, dy: (q[0] + dx * p, q[1] + dy * p)
    d.touch(("down", *q), *[("move", *at(0, dy)) for dy in (15, 30, 45)], ("sleep", 0.3),
            *[step for m in moves for step in (("move", *at(*m)), ("sleep", 0.25))])
    return d.screen(), at(*moves[-1]) if moves else at(0, 45)


@case("KB-115", mutates=True, visual=True)
def kb115(d):
    from uat import add_im
    add_im(d, "双拼")
    # the edit menu first: the commands' list opens at the one set, 编辑菜单 above 选输入法
    q_up_command(d, "编辑菜单", "命令列表：编辑菜单", way="下滑")
    d.open_page("text")
    d.clear()
    edit = ("全选", "剪切", "复制", "粘贴", "撤销")
    s, at = down_menu(d, (0, 160))
    d.shot("full", "下滑出编辑菜单，接着往下走：菜单还在")
    on = [n.text for n in s.nodes if n.text in edit and not n.key]
    d.touch(("up", *at))
    time.sleep(0.8)
    s, at = down_menu(d, (0, 0))
    d.shot("full", "下滑出编辑菜单，一下滑回 q 上：菜单收起")
    off = [n.text for n in s.nodes if n.text in edit and not n.key]
    d.touch(("up", *at))
    time.sleep(0.8)
    check(len(on) == 5 and not off, f"编辑菜单往下走 {on}，滑回 q 上 {off}")

    q_up_command(d, "选输入法", "命令列表：选输入法", way="下滑")
    d.open_page("text")
    d.clear()
    d.switch_im("拼音")
    s, at = down_menu(d)
    cells = menu_cells(s)
    d.touch(("up", *at))
    time.sleep(1.0)
    first = im_name(d)
    check(len(cells) == 4 and first == cells[1], f"下滑直接松开换到「{first}」，菜单 {cells}")

    d.switch_im("拼音")
    s, at = down_menu(d, (0, 80), (0, 160))
    d.shot("full", "下滑出输入法菜单后手指接着往下走：菜单还在")
    kept_open = len(menu_cells(s)) == 4
    d.touch(("up", *at))
    time.sleep(1.0)
    drift = im_name(d)
    check(kept_open and drift == cells[1], f"接着往下走后菜单 {menu_cells(s)}，松开换到「{drift}」")

    d.switch_im("拼音")
    s, at = down_menu(d, (60, 80))
    d.shot("full", "下滑后右挪一格")
    d.touch(("up", *at))
    time.sleep(1.0)
    side = im_name(d)
    check(side == cells[2], f"右挪一格松开换到「{side}」，菜单 {cells}")

    s, at = down_menu(d, (0, 0))
    d.shot("full", "下滑出输入法菜单后一下滑回 q 上：菜单收起")
    gone = not menu_cells(s)
    d.touch(("up", *at))
    time.sleep(1.0)
    back, typed = im_name(d), d.field_text() or ""
    check(gone and back == side and typed == "", f"滑回键上：菜单 {menu_cells(s)}，松开后「{back}」，框里「{typed}」")
    return f"编辑菜单往下走 {len(on)} 格还在、滑回收起；下滑菜单 {cells}：松开 {first}，往下走仍 {drift}，右挪 {side}，滑回不变"
