"""uat/05-toolbar.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import BAR_BOTTOM, Blocked, case, check, home, open_url

LONG = "今天天气很好，我们一起去公园散步吧。"


def ime(d, text=None, desc=None, after=0.6, contains=False):
    """a node of the keyboard by its text or description (either matches either)"""
    s = d.screen()
    w = text or desc
    if contains:
        n = next((n for n in s.ime() if w in n.text or w in n.desc), None)
    else:
        n = s.find(text=w, where="ime") or s.find(desc=w, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{text or desc}」")
    d.tap(n.c, after)
    return n


def edit_panel(d):
    d.expand_toolbar()
    ime(d, desc="文本编辑", after=0.8)


def status_panel(d):
    d.expand_toolbar()
    ime(d, desc="状态区域", after=0.8)


def clip_panel(d):
    d.expand_toolbar()
    ime(d, desc="剪贴板", after=0.8)


def back_to_keys(d):
    ime(d, desc="返回键盘", after=0.6)
    d._keys = None


def copy_range(d, start, n):
    """in the focused field: the cursor at its very start (a tap before the first character: the
    panel's |< goes to the start of the line only), → start times, 选择, → n times, 复制"""
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 0.6)
    edit_panel(d)
    s = d.screen()
    right = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    sel = s.find(desc="选择", where="ime") or s.find(text="选择", where="ime")
    if start:
        d.taps([right.c] * start, gap=0.05, after=0.3)
    d.tap(sel.c, 0.3)
    d.taps([right.c] * n, gap=0.05, after=0.3)
    ime(d, desc="复制", after=0.6)
    ime(d, desc="把光标移动到结尾", after=0.3)
    back_to_keys(d)


def copy_word(d, word):
    """copy a word of the 长文本 field (the clipboard as 前提)"""
    d.open_page("long")
    i = LONG.index(word)
    copy_range(d, i, len(word))


def clip_items(d):
    s = d.screen()
    return [n for n in s.ime() if n.text and n.c[1] > BAR_BOTTOM and n.text not in ("试着复制些东西",)]


def clip_item(d, text):
    return next((n for n in clip_items(d) if n.text.startswith(text)), None)


def long_press(d, node):
    """long-press an item: its popup menu opens"""
    d.hold(node.c, 900)
    time.sleep(0.6)


case("TB-01", needs="jvm:BarUatTest.toolbarWhenIdleCandidatesWhileTyping")(lambda d: None)


case("TB-02", needs="jvm:ToolbarUatTest.expandingTheToolbar")(lambda d: None)


case("TB-03", needs="jvm:ToolbarUatTest.undoAndRedo")(lambda d: None)


@case("TB-04")
def tb04(d):
    ime(d, desc="隐藏键盘", after=1.0)
    gone = not d.screen().find(rid="keyboard_view", where="ime")
    f = next(n for n in d.screen().app() if n.cls == "EditText" and n.rid != "url_field")
    d.tap(f.c, 1.2)
    back = d.screen().find(rid="keyboard_view", where="ime")
    check(gone, "点 ⌄ 后键盘没收起")
    check(back, "再点框键盘没出来")
    return "收起；再点框又出来"


case("TB-05", needs="jvm:BarUatTest.expandedCandidatesGridSelects")(lambda d: None)


case("TB-06", needs="jvm:ToolbarUatTest.editingPanelMovesAndSelects")(lambda d: None)


case("TB-07", needs="jvm:ToolbarUatTest.editingPanelSelectAllCutBack")(lambda d: None)


@case("TB-08", mutates=True)
def tb08(d):
    copy_word(d, "公园")
    d.open_page("text")
    clip_panel(d)
    items = clip_items(d)
    check(items and items[0].text == "公园", f"剪贴板 {[n.text for n in items][:4]}")
    d.tap(items[0].c, 0.8)
    s = d.screen()
    t = d.field_text(s)
    check(t == "公园", f"框里「{t}」")
    check(d.key_node("q", s), "粘贴后没有回到键盘（还在剪贴板面板）")
    return f"最上面「公园」，点后框里「{t}」，回到键盘"


case("TB-09", needs="jvm:ToolbarUatTest.justCopiedOnTheBar")(lambda d: None)


case("TB-40", needs="jvm:ToolbarUatTest.justCopiedGoneAfterThirtySeconds")(lambda d: None)


case("TB-10", needs="jvm:ToolbarUatTest.aPinnedEntryOutlivesTheLimit")(lambda d: None)


case("TB-11", needs="jvm:ToolbarUatTest.editingAndDeletingAnEntry")(lambda d: None)


case("TB-33", needs="jvm:ToolbarUatTest.clearingAskedUndoneThenThePinned")(lambda d: None)


case("TB-13", needs="jvm:ClipboardUatTest.aCopiedLinkLosesItsTrackingParameters")(lambda d: None)


case("TB-14", needs="jvm:ClipboardUatTest.historyOffKeepsNothing")(lambda d: None)


def status_find(d, *texts):
    """the first of [texts] on the open status panel, scrolled to if under the fold; None if none is"""
    def find():
        s = d.screen()
        return next((n for x in texts if (n := s.find(text=x, where="ime"))), None)
    n = find()
    if not n:
        # the learning entries (U-88, U-93) push fcitx's toggles to a third row, under the fold
        d.swipe((540, 2000), (540, 1650), 300, 0.8)
        n = find()
    return n


def status_toggle(d, frm):
    status_panel(d)
    n = status_find(d, frm)
    if not n:
        raise Blocked(f"状态面板没有「{frm}」")
    d.tap(n.c, 0.8)
    s = d.screen()
    now = next((m.text for m in s.ime() if abs(m.c[0] - n.c[0]) < 30 and abs(m.c[1] - n.c[1]) < 30 and m.text), "")
    back_to_keys(d)
    return now


@case("TB-15", mutates=True)
def tb15(d):
    now = status_toggle(d, "简体")
    d.type("guojia")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "國家", f"切到「{now}」后打 guojia：「{t}」")
    return f"「{now}」：「{t}」"


@case("TB-16", mutates=True)
def tb16(d):
    now = status_toggle(d, "中文标点")
    d.press(",")
    t = d.field_text()
    check(t == ",", f"切到「{now}」后：「{t}」")
    return f"「{now}」：「{t}」"


@case("TB-17", mutates=True)
def tb17(d):
    from uat import flick
    now = status_toggle(d, "半角")
    flick(d, "q")
    t = d.field_text()
    d.switch_im("英文")
    flick(d, "q")
    t2 = d.field_text()
    check(t == "１", f"切到「{now}」后：「{t}」")
    check(t2 == "１1", f"英文再打 1：「{t2}」")
    return f"「{t}」→「{t2}」"


case("TB-18", needs="jvm:ToolbarUatTest.theStatusAreasWaysIntoTheSettings")(lambda d: None)


@case("TB-19", base="B0-英文")
def tb19(d):
    status_panel(d)
    s = d.screen()
    texts = [n.text for n in s.ime() if n.text]
    bad = [x for x in ("简体", "繁體", "繁体", "中文标点", "英文标点", "半角", "全角") if x in texts]
    check(not bad, f"英文的状态面板里有 {bad}")
    return f"状态面板 {texts}"


# ---------------------------------------------------------------- 单手、悬浮


def kbox(d, s=None):
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    return kb.b if kb else None


case("TB-20", needs="jvm:ToolbarUatTest.oneHandedToASideAcrossAndBack")(lambda d: None)


case("TB-21", needs="jvm:ToolbarUatTest.oneHandedPanelsAsNarrow")(lambda d: None)


def floating(d):
    status_panel(d)
    ime(d, text="悬浮键盘", after=1.2)


def handle(d, s=None):
    s = s or d.screen()
    dock = s.find(desc="键盘放回底部", where="ime")
    kb = s.find(rid="keyboard_view", where="ime")
    if not dock or not kb:
        raise Blocked("没有悬浮键盘的横条")
    return ((kb.b[0] + kb.b[2]) // 2, dock.c[1]), dock


def drag(d, a, b):
    steps = [("down", *a), ("sleep", 0.2)] + \
        [("move", a[0] + (b[0] - a[0]) * i // 10, a[1] + (b[1] - a[1]) * i // 10) for i in range(1, 11)] + \
        [("sleep", 0.2), ("up", *b)]
    d.touch(*steps)
    time.sleep(0.8)


@case("TB-22", page="sentence", mutates=True, visual=True)
def tb22(d):
    # the sentence field and the text field both on the page: the full page
    open_url(d, "", field=0)
    s0 = d.screen()
    f0 = [n.b for n in s0.app() if n.cls == "EditText" and n.rid != "url_field"]
    floating(d)
    s = d.screen()
    b1 = kbox(d, s)
    f1 = [n.b for n in s.app() if n.cls == "EditText" and n.rid != "url_field"]
    # the toolbar's buttons as big as docked, not squeezed by the narrower bar
    menu = s.find(desc="展开工具栏", where="ime")
    if menu:
        d.tap(menu.c, 0.8)
    s = d.screen()
    undo, more = s.find(desc="撤销", where="ime"), s.find(desc="状态区域", where="ime")
    d.shot("kb", "悬浮的工具栏和字母")
    # 28 dp at least, all in view (⋯, the way out of floating, too)
    check(undo is not None and undo.b[2] - undo.b[0] >= 72, f"悬浮时工具栏按钮 {undo.b if undo else None}，窄于 28dp")
    check(more is not None and b1 and more.b[2] <= b1[2], f"悬浮时工具栏的 ⋯ {more.b if more else None} 不在键盘里 {b1}")
    h, _ = handle(d, s)
    drag(d, h, (h[0], 500))
    s = d.screen()
    b2 = kbox(d, s)
    d.shot("full", "拖到上半部分")
    check(b1 and 0.6 < (b1[2] - b1[0]) / 1080 < 0.85, f"悬浮键盘 {b1}")
    check(f0[:2] == f1[:2], f"页面被顶起：{f0[:2]} → {f1[:2]}")
    check(b2 and b2[1] < b1[1] - 400, f"拖后键盘 {b2}（拖前 {b1}）")
    # a field outside the keyboard: the second one (句子) if it shows
    s = d.screen()
    fields = [n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"]
    free = [n for n in fields if n.b[3] < b2[1] or n.b[1] > b2[3]]
    if len(free) >= 2:
        d.tap(free[1].c, 0.8)
        check(d.field() and d.field().b == free[1].b, "点键盘外的框焦点没过去")
    a = d.key_node("a")
    x, y = a.c
    # a moment for the bubble to be drawn: the release build's frame can come after an immediate screencap
    d.sh(f"input motionevent DOWN {x} {y}; sleep 0.1; screencap -p /sdcard/uat-p.png; input motionevent UP {x} {y}")
    png = d.adb("exec-out", "cat", "/sdcard/uat-p.png", binary=True)
    d.shot((max(0, x - 300), max(0, a.b[1] - 330), min(1080, x + 300), a.b[3] + 20), "按住 a", png=png)
    return f"悬浮 {b1} → 拖到 {b2}；页面不动"


case("TB-34", needs="jvm:FloatingUatTest.theDockingZoneShownAndDroppedIn")(lambda d: None)


case("TB-36", needs="jvm:FloatingUatTest.aSecondFingerInTheZoneKeepsItFloating")(lambda d: None)


case("TB-39", needs="jvm:FloatingUatTest.atTheTopTheUndoIsBelowAndTappable")(lambda d: None)


case("TB-37", needs="jvm:FloatingUatTest.thePreviewsLettersAsLargeWhileFloating")(lambda d: None)


case("TB-35", needs="jvm:FloatingUatTest.theHandleOnTop")(lambda d: None)


@case("TB-23", mutates=True, visual=True)
def tb23(d):
    floating(d)
    h, _ = handle(d)
    drag(d, h, (330, 300))
    b1 = kbox(d)
    d.back(1.0)
    f = next(n for n in d.screen().app() if n.cls == "EditText" and n.rid != "url_field")
    d.tap(f.c, 1.2)
    b2 = kbox(d)
    d.rotate(1)
    s = d.screen()
    if not s.find(rid="keyboard_view", where="ime"):
        f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
        if f:
            d.tap(f.c, 1.2)
    b3 = kbox(d)
    d.shot("full", "横屏")
    d.rotate(0)
    s = d.screen()
    if not s.find(rid="keyboard_view", where="ime"):
        f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
        if f:
            d.tap(f.c, 1.2)
    b4 = kbox(d)
    check(b1 and b2 and abs(b1[1] - b2[1]) < 20 and abs(b1[0] - b2[0]) < 20, f"收起再出 {b1} → {b2}")
    check(b3 and b3[0] < 400 and b3[1] < 500, f"横屏时 {b3}（应在左上）")
    check(b4 and b4[0] < 300 and b4[1] < 900, f"转回竖屏 {b4}")
    return f"左上 {b1}，再出 {b2}，横屏 {b3}，竖屏 {b4}"


case("TB-24", needs="jvm:FloatingUatTest.theDockButtonPutsItBack")(lambda d: None)


# ---------------------------------------------------------------- 计算结果

def numpad(d, keys):
    d.press("123", after=0.8)
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    lab = {}
    for n in kb.walk():
        if n.clickable:
            t = [m.text for m in n.walk() if m.text]
            if t:
                lab[t[0]] = n.c
    pts = []
    for ch in keys:
        if ch not in lab:
            raise Blocked(f"数字键盘上没有「{ch}」")
        pts.append(lab[ch])
    d.taps(pts, gap=0.12, after=0.8)


def result_on_bar(d):
    s = d.screen()
    return next((n for n in s.ime() if n.text.startswith("=") and n.c[1] < BAR_BOTTOM), None)


case("TB-25", needs="jvm:ToolbarUatTest.aSumsResultOnTheBar")(lambda d: None)


@case("TB-26", base="B0-设置", mutates=True)
def tb26(d):
    home(d, "通用输入")
    d.switch("点算式结果时替换整个算式", True)
    d.open_page("text")
    d.switch_im("英文")
    numpad(d, "1+2*2=")
    r = result_on_bar(d)
    check(r and r.text.replace(" ", "") == "=5", f"栏上 {r.text if r else None}")
    d.tap(r.c, 0.8)
    t = d.field_text()
    check(t == "5", f"框里「{t}」")
    return f"框里「{t}」"


case("TB-28", needs="jvm:ToolbarUatTest.copyingNothingLeavesTheCursor")(lambda d: None)


@case("TB-29")
def tb29(d):
    d.type("ni", after=0.6)
    s = d.screen()
    b = s.find(desc="展开候选词列表", where="ime")
    if not b:
        raise Blocked("打 ni 后候选栏右边没有展开 ⌄")
    d.hold(b.c, 900)
    time.sleep(0.8)
    s = d.screen()
    check(not s.find(rid="expanded_candidate_view", where="ime"), "长按 ⌄ 展开了候选表格")
    check(not s.find(rid="keyboard_view", where="ime"), "长按 ⌄ 后键盘还在")
    # 联想 (the bar after a commit) has the same ⌄: a tap expands, a long press hides
    d.open_page("text")
    d.type("ni", after=0.6)
    d.pick("你")
    time.sleep(0.6)
    b = d.screen().find(desc="展开候选词列表", where="ime")
    if not b:
        raise Blocked("选「你」后联想栏右边没有展开 ⌄")
    d.tap(b.c, 0.8)
    s = d.screen()
    grid = s.find(rid="expanded_candidate_view", where="ime")
    check(grid, "联想时点一下 ⌄ 没展开联想表格")
    d.tap(b.c, 0.8)   # the same spot folds the table back
    b = d.screen().find(desc="展开候选词列表", where="ime")
    if not b:
        raise Blocked("收起联想表格后没有 ⌄")
    d.hold(b.c, 900)
    time.sleep(0.8)
    s = d.screen()
    check(not s.find(rid="keyboard_view", where="ime"), "联想时长按 ⌄ 后键盘还在")
    return "有候选、有联想时长按 ⌄ 都收起键盘；联想时点一下展开表格"


case("TB-30", needs="jvm:ToolbarUatTest.copyAndCutSaidNothingCopiedNotSaid")(lambda d: None)

case("TB-31", needs="jvm:ToolbarUatTest.pasteStaysInTheClipboardWhenSoSet")(lambda d: None)


case("TB-32", needs="jvm:FloatingUatTest.squareKeysASquareFloatingKeyboard")(lambda d: None)


@case("TB-41", mutates=True)
def tb41(d):
    status_toggle(d, "中文标点")
    stale = []
    for i in range(20):
        want = "英文" if i % 2 == 0 else "拼音"
        d.press("globe", after=0.8)
        d._keys = None
        name = d.im_label()
        if want not in name:
            stale.append(f"第 {i + 1} 次「{name}」")
    check(not stale, f"读到的还是上一个：{stale[:3]}（共 {len(stale)} 次）")
    return "20 次都读到新名字"

