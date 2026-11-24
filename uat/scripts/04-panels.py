"""uat/04-panels.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import Blocked, case, check, script


@case("PAN-30")
def pan30(d):
    # 「（」 on the device, the first page of 中文 (《 is further down; it and the others: PanelUatTest, AutoPairsTest)
    d.open_page("text")
    d.clear()
    ime(d, "符")
    ime(d, "中文")
    time.sleep(0.4)
    if "（" not in ime_texts(d):
        raise Blocked(f"「中文」里没有「（」：{ime_texts(d)[:40]}")
    ime(d, "（")
    t = d.field_text() or ""
    check(t == "（）", f"点「（」后框里「{t}」")
    d.press("bksp", after=0.5)
    t2 = d.field_text() or ""
    check(t2 == "", f"⌫ 后框里「{t2}」")
    return f"「{t}」，⌫ 后「{t2}」"


def ime(d, w, after=0.6):
    """a node of the keyboard by its text or description"""
    s = d.screen()
    n = s.find(text=w, where="ime") or s.find(desc=w, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{w}」")
    d.tap(n.c, after)
    return n


def ime_texts(d):
    return [n.text or n.desc for n in d.screen().ime() if n.text or n.desc]


@case("PAN-22", visual=True)
def pan22(d):
    ime(d, "表情")
    ime(d, "按拼音搜索表情")
    for c in "xiao":
        ime(d, c, after=0.3)
    time.sleep(0.6)
    d.shot("kb", "表情搜索打 xiao")
    t = ime_texts(d)
    check("😄" in t, f"搜索行 {t[:12]}")
    ime(d, "返回")
    check("按拼音搜索表情" in ime_texts(d), "返回后不在表情列表")
    return "xiao 出 😄，返回回到表情列表"


@case("PAN-23", visual=True)
def pan23(d):
    ime(d, "符")
    time.sleep(0.6)
    d.shot("kb", "符号面板的格子")
    ime(d, "，")
    t = d.field_text() or ""
    check(t.endswith("，"), f"框里「{t}」")
    # the emoji's cells: colour emoji, centred; the symbol panel went back to the letters with the ，
    ime(d, "表情")
    time.sleep(0.6)
    d.shot("kb", "表情面板的格子")
    # the 颜文字's: a long one whole, not cut at its cell's sides
    ime(d, ":-)")
    time.sleep(0.6)
    long = "(｡>﹏<｡)ﾉﾞ✧*。"
    for _ in range(12):
        s = d.screen()
        n = s.find(desc=long, where="ime")
        if n:
            break
        # from 70% to 30% of the panel's height: the grid, above the picker's bottom row
        w = s.find(rid="input_window", where="ime")
        if not w:
            raise Blocked("颜文字面板不在")
        h = w.b[3] - w.b[1]
        x, y0, y1 = (w.b[0] + w.b[2]) // 2, w.b[1] + h * 7 // 10, w.b[1] + h * 3 // 10
        d.swipe((x, y0), (x, y1), 300, 0.6)
    check(n is not None, f"颜文字里找不到 {long}")
    d.shot("kb", "颜文字长字")
    return f"框里「{t}」，表情和颜文字格子见图"


@case("PAN-35", visual=True)
def pan35(d):
    def full_pad(what):
        keys = d.keys(refresh=True)
        col = column_texts(d)
        check({"=", "."} <= set(keys) and col[:4] == ["+", "-", "*", "/"],
              f"{what}不是完整的数字键盘：键 {sorted(keys)}，左列 {col}")
        return keys
    d.step("测试页的小数框：声明小数，什么都收")
    d.open_page("decimal")
    d.clear()
    keys = full_pad("小数框")
    d.shot("ime", "小数框：完整的数字键盘")
    column_tap(d, "-", 0.4)
    d.tap(keys["5"], 0.4)
    typed = d.field_text() or ""
    check(typed == "-5", f"小数框点 - 5 打出的是 {typed!r}")
    d.step("输入测试台的带符号数字框")
    script("02-keyboard").testbed_field(d, "signed")
    keys = full_pad("带符号数字框")
    column_tap(d, "-", 0.4)
    d.tap(keys["3"], 0.4)
    time.sleep(0.5)
    signed = d.testbed_state()["signed"]["text"]
    check(signed == "-3", f"带符号数字框点 - 3 打出的是 {signed!r}")
    return f"两个框都是完整的数字键盘；小数框「{typed}」，带符号数字框「{signed}」"


# 0.6.3 (dev/design/063-number-pad-t9): the pads' left column is a scroll slot, its items a native list over the scene's slot

def inside(b, box, slack=2):
    return box[0] - slack <= b[0] and box[1] - slack <= b[1] and b[2] <= box[2] + slack and b[3] <= box[3] + slack


def column_node(d, s=None):
    """the keyboard's scroll column (ScrollView or HorizontalScrollView inside the keys' view), or Blocked"""
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    n = next((n for n in s.ime() if n.cls in ("ScrollView", "HorizontalScrollView") and kb and inside(n.b, kb.b)), None)
    if not n:
        raise Blocked("键盘上没有滑动列")
    return n


def column(d, s=None):
    """the column's items in view, in order: [(text or what TalkBack reads, node)]"""
    v = column_node(d, s)
    return [((m.text or m.desc), m) for m in v.walk() if m.clickable and (m.text or m.desc) and m.b[2] > m.b[0] and m.b[3] > m.b[1]]


def column_texts(d, s=None):
    return [t for t, _ in column(d, s)]


def column_scroll(d, back=False):
    """the column dragged by most of its length (down the list; [back] up to its start)"""
    v = column_node(d)
    x0, y0, x1, y1 = v.b
    horizontal = v.cls == "HorizontalScrollView"
    if horizontal:
        a, b = (x0 + (x1 - x0) * 4 // 5, (y0 + y1) // 2), (x0 + (x1 - x0) // 5, (y0 + y1) // 2)
    else:
        a, b = ((x0 + x1) // 2, y0 + (y1 - y0) * 4 // 5), ((x0 + x1) // 2, y0 + (y1 - y0) // 5)
    if back:
        a, b = b, a
    d.swipe(a, b, 500, after=0.8)


def column_all(d):
    """every item of the column, scrolled through to its end and back to its start"""
    seen = []
    # an item deleted or picked down the column leaves it scrolled there
    for _ in range(4):
        column_scroll(d, back=True)
    for _ in range(8):
        now = column_texts(d)
        new = [t for t in now if t not in seen]
        if not new and seen:
            break
        seen += new
        column_scroll(d)
    for _ in range(4):
        column_scroll(d, back=True)
    return seen


def column_tap(d, text, after=0.5):
    """the column's item [text], scrolled to where it is not in view"""
    for i in range(8):
        n = next((n for t, n in column(d) if t == text), None)
        if n:
            d.tap(n.c, after)
            return n
        column_scroll(d, back=i >= 4)
    raise Blocked(f"左列里没有「{text}」：{column_texts(d)}")


def board(d, s=None):
    """which keyboard is up: 数字 (the pad: 返回 and 0), 字母 or what else"""
    s = s or d.screen()
    ids = {n.key for n in s.ime() if n.key}
    if "back" in ids and "0" in ids:
        return "数字"
    if "q" in ids or s.find(rid="button_lang", where="ime"):
        return "字母"
    return "其它：" + ",".join(sorted(ids)[:8])


def wait_board(d, want, timeout=4):
    """[want] up within [timeout], else what is"""
    end = time.time() + timeout
    got = ""
    while time.time() < end:
        try:
            d.need_keyboard(6)
        except Blocked:
            pass
        got = board(d)
        if got == want:
            return got
        time.sleep(0.4)
    return got


def lock_node(d, s=None):
    """the pad's lock is 返回 held: its node, .hold what TalkBack says a hold does (锁定 / 解锁), .state 已锁定 when locked"""
    s = s or d.screen()
    n = next((n for n in s.ime() if n.key == "back"), None)
    if not n:
        raise Blocked(f"数字键盘上没有返回：{sorted({m.key for m in s.ime() if m.key})}")
    return n


def hold_back(d, after=0.8):
    """返回 held: the pad's lock toggled"""
    d.hold(lock_node(d).c, 900)
    time.sleep(after)
    d._keys = None


def key(d, name, after=0.6):
    """a canvas key by its scene id (123's is numbers, the pad's way back back)"""
    s = d.screen()
    n = next((n for n in s.ime() if n.key == name), None) or next((n for n in s.ime() if n.clickable and n.text == name), None)
    if not n:
        raise Blocked(f"键盘上没有 {name}：{sorted({m.key for m in s.ime() if m.key})}")
    d.tap(n.c, after)
    d._keys = None
    return n


def numbers(d):
    """123 tapped: the number pad up"""
    k = d.keys(refresh=True)
    if "123" not in k:
        raise Blocked(f"键盘上没有 123：{sorted(k)[:20]}")
    d.tap(k["123"], 0.8)
    d._keys = None
    check(wait_board(d, "数字") == "数字", f"点 123 后是{board(d)}键盘")


def aligned(d):
    """the column inside the left slot beside 1/4/7: its box against the digits' rows; '' when it fits"""
    s = d.screen()
    v = column_node(d, s)
    k = {n.key: n.b for n in s.ime() if n.key}
    if not {"1", "7"} <= set(k):
        return f"找不到 1、7：{sorted(k)}"
    one, seven = k["1"], k["7"]
    gap = one[0] - v.b[2]
    probs = []
    if not 0 <= gap <= 40:
        probs.append(f"列右边 {v.b[2]} 和 1 的左边 {one[0]} 差 {gap}")
    if abs(v.b[1] - one[1]) > 40:
        probs.append(f"列顶 {v.b[1]}、1 的顶 {one[1]}")
    if abs(v.b[3] - seven[3]) > 40:
        probs.append(f"列底 {v.b[3]}、7 的底 {seven[3]}")
    return "；".join(probs)


@case("PAN-36", visual=True)
def pan36(d):
    d.open_page("text")
    d.clear()
    numbers(d)
    first = column_texts(d)
    d.shot("kb", "数字键盘：左列 + - * /，9 右边 =，底排 符 返回 0 . ↵")
    check(first[:4] == ["+", "-", "*", "/"], f"左列一开始是 {first}")
    column_scroll(d)
    d.shot("kb", "左列往下滑")
    every = column_all(d)
    check({"%", ",", ":", "(", ")", "#"} <= set(every), f"左列滑到底只有 {every}")
    column_tap(d, "%")
    column_tap(d, "(")
    t = d.field_text() or ""
    # 括号成对 is on by default: ( brings its )
    check(t == "%()", f"点 % ( 后框里「{t}」")
    d.clear()
    if board(d) != "数字":
        numbers(d)
    k = d.keys(refresh=True)
    d.taps([k["1"], k["2"]], after=0.3)
    column_tap(d, "*", after=0.3)
    d.taps([k["3"], k["="]], after=0.6)
    t2 = d.field_text() or ""
    check(t2 == "12*3=", f"打 12*3= 框里「{t2}」")
    form = pan36_form(d)
    web = pan36_web(d)
    return f"左列 {first[:4]}，滑到底 {every}；点 % ( 「{t}」；「{t2}」；{form}；{web}"


def pan36_form(d):
    """a native form: →| starts the next field's column over; the same field shown again, none between, keeps its place"""
    testbed_page(d, "form")
    tb_field(d, "form_text")
    numbers(d)
    column_scroll(d)
    was = column_texts(d)
    check(was[:1] != ["+"], f"form_text 里左列滑不动：{was}")
    next_field(d, "form_text2")
    on(d, "→| 到 form_text2", "数字")
    moved = column_texts(d)
    check(moved[:4] == ["+", "-", "*", "/"], f"→| 到 form_text2 后左列 {moved}")
    column_scroll(d)
    at = column_texts(d)
    check(at[:1] != ["+"], f"form_text2 里左列滑不动：{at}")
    d.back()
    tb_field(d, "form_text2")
    if board(d) != "数字":
        numbers(d)
    again = column_texts(d)
    check(again == at, f"收起再点 form_text2 后左列 {again}（之前 {at}）")
    return f"→| 到 form_text2 左列回到 {moved[:4]}，收起再点它停在 {again[:2]}"


def pan36_web(d):
    """a WebView with no view id (fieldId -1): restartInput keeps the column's place, another of its fields starts it over"""
    testbed_page(d, "webview")
    web_field(d, "w_text")
    numbers(d)
    column_scroll(d)
    was = column_texts(d)
    check(was[:1] != ["+"], f"网页框里左列滑不动：{was}")
    b = find_on_page(d, lambda s: next((x for x in s.app() if x.text == "restartInput（网页）"), None), "按钮 restartInput（网页）")
    d.tap(b.c, 1.2)
    d._keys = None
    if board(d) != "数字":
        numbers(d)
    now = column_texts(d)
    check(now == was, f"网页框 restartInput 后左列 {now}（之前 {was}）")
    web_field(d, "w_search")
    if board(d) != "数字":
        numbers(d)
    other = column_texts(d)
    check(other[:4] == ["+", "-", "*", "/"], f"换到 w_search 后左列 {other}")
    return f"网页框 restartInput 后左列仍是 {now[:2]}，换框回到 {other[:4]}"


@case("PAN-37", mutates="learn")
def pan37(d):
    d.open_page("text")
    d.clear()
    d.type("ni", after=0.6)
    check(d.cands(), "打 ni 没有候选")
    numbers(d)
    now = column_texts(d)
    check(now[:4] == ["+", "-", "*", "/"], f"打字中左列是 {now}")
    column_tap(d, "+", after=0.8)
    t = d.field_text() or ""
    check(len(t) >= 2 and t.endswith("+") and "一" <= t[0] <= "鿿" and not d.preedit(), f"点 + 后框里「{t}」，打字中「{d.preedit()}」")
    d.clear()
    if board(d) != "数字":
        numbers(d)
    k = d.keys(refresh=True)
    plus = next(n for t_, n in column(d) if t_ == "+")
    d.taps([k["1"], k["2"], plus.c, k["3"], k["4"]], gap=0.03, after=0.8)
    t2 = d.field_text() or ""
    check(t2 == "12+34", f"快打 12+34 框里「{t2}」")
    return f"点 + 后「{t}」；快打「{t2}」（两手同时按着在 JVM：NumberColumnTest.aFingerOnTheColumnAndOneOnAKeyEachType）"


@case("PAN-38", mutates=True, visual=True)
def pan38(d):
    tb = script("05-toolbar")
    seen = []

    def look(where):
        d.need_keyboard()
        if board(d) != "数字":
            numbers(d)
        bad = aligned(d)
        d.shot("kb", f"{where}：左列盖在它那一格上")
        check(not bad, f"{where}：{bad}")
        column_scroll(d)
        column_scroll(d, back=True)
        before = d.field_text() or ""
        column_tap(d, "-")
        after = d.field_text() or ""
        check(after == before + "-", f"{where}：点 - 后框里「{after}」（之前「{before}」）")
        seen.append(where)

    d.open_page("text")
    d.clear()
    d.rotate(1)
    try:
        d.open_page("text")
        look("横屏")
    finally:
        d.rotate(0)
    d.open_page("text")
    d.clear()
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    look("单手")
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    d.open_page("text")
    d.clear()
    tb.floating_up(d)
    look("悬浮")
    r = tb.resizer(d)
    tb.drag(d, r.c, (r.c[0] - 120, r.c[1] + 120))
    look("悬浮拖大")
    r = tb.resizer(d)
    tb.drag(d, r.c, (r.c[0] + 240, r.c[1] - 240))
    look("悬浮拖小")
    return "、".join(seen) + "：列都在它那一格上，能滚、能点（横屏默认分体，数字键盘缩成单手宽靠一边）"


# STICKY.md: the number pad kept across an app's plain fields, its lock

def testbed_page(d, page, fresh=True):
    """the testbed at [page], started afresh; not [fresh]: the running testbed turned to it, as a user goes to
    another page (a force-stop brings the browser's field up between: another app's, which lets the lock go)"""
    from uat import TESTBED
    if fresh:
        d.sh(f"am force-stop {TESTBED}")
    d.testbed(page)
    d.page = None
    d.host = None
    d._keys = None


def page_bottom(s):
    """the top of the keyboard or of the testbed's 事件记录 bar, which sits on it (higher when opened)"""
    kb = s.find(rid="keyboard_view", where="ime")
    bar = next((x for x in s.app() if x.text.startswith("事件记录")), None)
    return min(kb.b[1] if kb else 2000, bar.b[1] if bar else 1260)


def page_swipe(d, up):
    """the app's page dragged by half the room above the keyboard (its lists do not always say they scroll)"""
    bottom = page_bottom(d.screen()) - 160
    a, b = (540, bottom), (540, max(300, bottom - 600))
    d.swipe(b if up else a, a if up else b, 400, after=0.8)


def find_on_page(d, pick, what):
    """the node [pick] finds, the page dragged up to its top, then down, until it shows"""
    for i in range(14):
        s = d.screen()
        n = pick(s)
        # a node under the keyboard or the 事件记录 bar is in the tree but a tap there hits them
        if n and n.c[1] < page_bottom(s) - 40:
            return n
        page_swipe(d, up=i < 4)
    raise Blocked(f"页上找不到 {what}")


def tb_field(d, rid, after=1.2):
    """the testbed's field [rid] on the page shown, tapped (dragged to where it is off screen)"""
    from uat import TESTBED
    n = find_on_page(d, lambda s: next((x for x in s.app() if x.rid == rid and x.pkg == TESTBED), None), f"测试台的 {rid} 框")
    d.tap(n.c, after)
    d._keys = None


def focused(d):
    """the testbed's focused field by name"""
    return next((k for k, f in d.testbed_state().items() if f.get("focused")), None)


def next_field(d, want):
    """→| (the Return key of a field whose action is next): [want] focused"""
    key(d, "enter", after=1.0)
    got = focused(d)
    check(got == want, f"→| 后焦点在 {got}，不是 {want}")


def on(d, step, want, got=None):
    got = got or wait_board(d, want)
    check(got == want, f"{step}：是{got}键盘，应是{want}")
    return f"{step} {got}"


@case("PAN-39", visual=True)
def pan39(d):
    testbed_page(d, "form")
    tb_field(d, "form_text")
    numbers(d)
    seen = []
    for f in ("form_text2", "form_amount", "form_text3", "form_default"):
        next_field(d, f)
        seen.append(on(d, f, "数字"))
        if f == "form_amount":
            ids = {n.key for n in d.screen().ime() if n.key}
            check("=" in ids and "." in ids, f"金额框不是完整的数字键盘：{sorted(ids)}")
            d.shot("kb", "form_amount：完整的数字键盘")
    tb_field(d, "form_email")
    seen.append(on(d, "form_email", "字母"))
    tb_field(d, "form_note")
    seen.append(on(d, "form_note", "字母"))
    tb_field(d, "form_text2")
    seen.append(on(d, "再点回 form_text2", "字母"))
    return "；".join(seen)


@case("PAN-40")
def pan40(d):
    seen = []
    testbed_page(d, "form")
    tb_field(d, "form_text")
    numbers(d)
    key(d, "back")
    check(wait_board(d, "字母") == "字母", f"返回后是{board(d)}")
    next_field(d, "form_text2")
    seen.append(on(d, "1. 返回再 →|", "字母"))
    numbers(d)
    d.back()
    tb_field(d, "form_text2")
    seen.append(on(d, "2. 收起再点框", "字母"))
    numbers(d)
    d.open_page("text")
    seen.append(on(d, "3. 别的 App", "字母"))
    testbed_page(d, "form")
    tb_field(d, "form_amount")
    seen.append(on(d, "4. 金额框", "数字"))
    next_field(d, "form_text3")
    seen.append(on(d, "4. →| 到 form_text3", "字母"))
    testbed_page(d, "types")
    tb_field(d, "phone")
    check(wait_board(d, "数字") == "数字", f"电话框是{board(d)}")
    key(d, "back")
    numbers(d)
    tb_field(d, "text")
    seen.append(on(d, "5. 电话框返回再 123，点 text", "字母"))
    testbed_page(d, "types")
    tb_field(d, "no_learning")
    numbers(d)
    tb_field(d, "text")
    seen.append(on(d, "6. 无痕框 123，点 text", "数字"))
    return "；".join(seen)


def web_field(d, rid, after=1.2):
    """a field of the testbed's WebView page by its html id (Chrome reports it as the node's resource id)"""
    d.wait(lambda s: any(x.rid.startswith("w_") for x in s.app()), 15, what="the web page")
    n = find_on_page(d, lambda s: next((x for x in s.app() if x.rid == rid), None), f"网页的 {rid} 框")
    d.tap(n.c, after)
    d._keys = None


@case("PAN-41")
def pan41(d):
    testbed_page(d, "webview")
    web_field(d, "w_text")
    numbers(d)
    seen = []
    web_field(d, "w_noauto")
    seen.append(on(d, "w_noauto", "数字"))
    web_field(d, "w_email")
    seen.append(on(d, "w_email", "字母"))
    web_field(d, "w_text")
    seen.append(on(d, "再点回 w_text", "字母"))
    return "；".join(seen)


def lock(d):
    """返回 held: locked, its hold said 解锁 after"""
    hold_back(d)
    n = lock_node(d)
    check(n.hold == "解锁", f"长按返回后，返回的长按读「{n.hold}」")


@case("PAN-42", mutates=True, visual=True)
def pan42(d):
    seen = []
    testbed_page(d, "form")
    tb_field(d, "form_text")
    numbers(d)
    check(lock_node(d).hold == "锁定", f"没锁时返回的长按读「{lock_node(d).hold}」")
    d.shot("kb", "锁开着")
    lock(d)
    d.shot("kb", "1. 长按返回：返回右上角一把小锁")
    d.back()
    tb_field(d, "form_text2")
    seen.append(on(d, "2. 收起再点 form_text2", "数字"))
    tb_field(d, "form_email")
    seen.append(on(d, "3. form_email", "字母"))
    tb_field(d, "form_text3")
    seen.append(on(d, "3. form_text3", "数字"))
    d.step("4. 横屏")
    d.rotate(1)
    try:
        got = wait_board(d, "数字", timeout=8)
        if got != "数字":
            d.shot("full", f"4. 横屏后：{got}")
        if not d.screen().find(rid="keyboard_view", where="ime"):
            # the testbed's activity is made anew and the system does not bring the keyboard back; the lock lives in the input method
            tb_field(d, "form_text3")
            got = wait_board(d, "数字", timeout=8)
        seen.append(on(d, "4. 横屏", "数字", got))
        check(lock_node(d).hold == "解锁", f"横屏后返回的长按读「{lock_node(d).hold}」")
    finally:
        d.rotate(0)
    if not d.screen().find(rid="keyboard_view", where="ime"):
        tb_field(d, "form_text3")
    seen.append(on(d, "4. 转回", "数字"))
    check(lock_node(d).hold == "解锁", f"转回后返回的长按读「{lock_node(d).hold}」")
    hold_back(d)
    d.back()
    d.step("5. 解锁、收起、点 form_text")
    tb_field(d, "form_text")
    seen.append(on(d, "5. 解锁、收起、点 form_text", "字母"))
    numbers(d)
    lock(d)
    d.step("6. 锁、返回")
    key(d, "back")
    tb_field(d, "form_text2")
    seen.append(on(d, "6. 锁、返回、点 form_text2", "字母"))
    numbers(d)
    lock(d)
    d.step("7. 别的 App")
    d.open_page("text")
    seen.append(on(d, "7. 锁、别的 App", "字母"))
    testbed_page(d, "form")
    tb_field(d, "form_text")
    numbers(d)
    lock(d)
    d.back()
    d.sh("logcat -c")
    d.sh("input keyevent KEYCODE_HOME")
    time.sleep(2.0)
    bound = d.sh("logcat -d -s 'FcitxInputMethodService:*' '*:D' | grep -c 'onBindInput' || true").strip()
    # without a bind there the step proves nothing: the lock is let go by another app's field only
    check(int(bound or 0) >= 1, f"回桌面时没有 onBindInput（{bound}）")
    d.step("8. 回桌面再回来")
    # its single task takes any start as a new page: the form asked for again, the app's process as it was
    d.testbed("form")
    time.sleep(1.0)
    tb_field(d, "form_text")
    seen.append(on(d, "8. 锁、收起、回桌面、回来点 form_text", "数字"))
    check(lock_node(d).hold == "解锁", f"回桌面再回来返回的长按读「{lock_node(d).hold}」")
    d.back()
    d.step("9. 最近任务再返回")
    d.sh("input keyevent KEYCODE_APP_SWITCH")
    time.sleep(2.0)
    from uat import TESTBED
    # the first Overview of a fresh emulator swallows a Back while it loads: Back again till the testbed has focus
    for _ in range(4):
        d.sh("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
        if TESTBED in d.sh("dumpsys window | grep mCurrentFocus"):
            break
    tb_field(d, "form_text2")
    seen.append(on(d, "9. 收起、最近任务再返回、点 form_text2", "数字"))
    d.back()
    d.step("10. 下拉通知栏再收起")
    d.sh("cmd statusbar expand-notifications")
    time.sleep(1.5)
    d.sh("cmd statusbar collapse")
    time.sleep(1.0)
    tb_field(d, "form_text")
    seen.append(on(d, "10. 收起、通知栏、点 form_text", "数字"))
    check(lock_node(d).hold == "解锁", f"通知栏后返回的长按读「{lock_node(d).hold}」")
    key(d, "back")   # the lock outlives the case otherwise: the next one starts locked
    return "；".join(seen)


def emoji_panel(d):
    """the emoji panel opened from the number pad: the toolbar's 表情, else Return's menu (Emoji)"""
    s = d.screen()
    n = s.find(desc="表情", where="ime") or s.find(text="表情", where="ime")
    if n:
        d.tap(n.c, 0.8)
    else:
        x, y = d.keys(refresh=True)["enter"]
        d.touch(("down", x, y), ("sleep", 0.9))
        item = d.screen().find(text="Emoji", where="ime")
        if not item:
            d.touch(("up", x, y))
            raise Blocked("数字键盘上打不开表情面板：工具栏没有「表情」，长按 ⏎ 没有 Emoji")
        px, py = item.c
        d.touch(*[("move", x + (px - x) * i // 6, y + (py - y) * i // 6) for i in range(1, 7)], ("sleep", 0.15), ("up", px, py))
        time.sleep(0.8)
    d._keys = None
    check("按拼音搜索表情" in ime_texts(d), f"不是表情面板：{ime_texts(d)[:12]}")


@case("PAN-43", mutates=True)
def pan43(d):
    seen = []
    testbed_page(d, "form")
    tb_field(d, "form_text")
    numbers(d)
    lock(d)
    key(d, "symbols", after=1.0)
    ime(d, "返回", after=0.8)
    d._keys = None
    seen.append(on(d, "1. 符号面板返回", "数字"))
    check(lock_node(d).hold == "解锁", f"符号面板返回后返回的长按读「{lock_node(d).hold}」")
    testbed_page(d, "behavior", fresh=False)
    tb_field(d, "terminal")
    seen.append(on(d, "2. terminal", "字母"))
    testbed_page(d, "form", fresh=False)
    tb_field(d, "form_text")
    seen.append(on(d, "2. 回 form_text", "数字"))
    check(lock_node(d).hold == "解锁", f"经过 terminal 后返回的长按读「{lock_node(d).hold}」")
    emoji_panel(d)
    ime(d, "返回", after=0.8)
    d._keys = None
    seen.append(on(d, "3. 表情面板返回", "字母"))
    d.back()
    tb_field(d, "form_text2")
    seen.append(on(d, "3. 收起、点 form_text2", "字母"))
    return "；".join(seen)


MENU = ("移到最前", "编辑这一列…")
REMOVE = "删除"


def menu_nodes(s):
    """the column's long press menu on screen: {its item's text: node}; removing is 删除「x」, named"""
    return {n.text: n for n in s.nodes if n.text in MENU or n.text.startswith(REMOVE + "「") and n.text.endswith("」")}


def menu_pick(menu, pick):
    """[pick]'s node in [menu]: 删除 for the remove item whatever it names"""
    return next((n for t, n in menu.items() if t == pick or pick == REMOVE and t.startswith(REMOVE + "「")), None)


def hold_item(d, item, pick=None):
    """[item] (a column node) held till its menu shows and let go there: the menu's nodes, the menu left open; [pick] (删除
    for the remove item) tapped in it, the menu put away with back when it is not there"""
    x, y = item.c
    d.touch(("down", x, y), ("sleep", 0.9), ("up", x, y))
    time.sleep(0.5)
    menu = menu_nodes(d.screen())
    if pick:
        n = menu_pick(menu, pick)
        if n and n.enabled:
            d.tap(n.c, 0.8)
        else:
            d.back(0.5)
    return menu


def item_of(d, *texts):
    """the column's node showing one of [texts], scrolled to"""
    for i in range(8):
        n = next((n for t, n in column(d) if t in texts), None)
        if n:
            return n
        column_scroll(d, back=i >= 4)
    raise Blocked(f"左列里没有 {texts}：{column_texts(d)}")
