"""uat/05-toolbar.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import BAR_BOTTOM, Blocked, case, check, open_url

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
    # below the panel's title bar (bottom 1506 on the emulator): the first row's centre, 1556, is above
    # BAR_BOTTOM, so a cut there lost it
    title = next((n for n in s.ime() if n.text == "剪贴板"), None)
    top = title.b[3] if title else BAR_BOTTOM
    return [n for n in s.ime() if n.text and n.b[1] >= top and n.text not in ("试着复制些东西",)]


def clip_item(d, text):
    return next((n for n in clip_items(d) if n.text.startswith(text)), None)


case("TB-01", needs="jvm:BarUatTest.toolbarWhenIdleCandidatesWhileTyping")(lambda d: None)


case("TB-02", needs="jvm:ToolbarUatTest.expandingTheToolbar")(lambda d: None)


case("TB-03", needs="jvm:ToolbarUatTest.undoAndRedo")(lambda d: None)


case("TB-04", needs="jvm:ToolbarUatTest.theHideButtonPutsTheKeyboardAway")(lambda d: None)


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


case("TB-42", needs="jvm:ToolbarUatTest.theToolbarButtonsPageSetsTheBar")(lambda d: None)


case("TB-43", needs="jvm:ToolbarUatTest.thePanelsTogglesOnTheBar、KeyboardPickerUatTest.theKeyboardButtonOnTheBarIsThePanelsPicker")(lambda d: None)


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


case("TB-15", needs="jvm:BarUatTest.theStatusPanelTurnsTraditionalOn")(lambda d: None)


case("TB-16", needs="jvm:BarUatTest.theStatusPanelTurnsEnglishPunctuationOn")(lambda d: None)


case("TB-17", needs="jvm:BarUatTest.theStatusPanelTurnsFullWidthOn")(lambda d: None)


case("TB-18", needs="jvm:ToolbarUatTest.theStatusAreasWaysIntoTheSettings")(lambda d: None)


case("TB-19", needs="jvm:BarUatTest.englishsStatusPanelHasNoChineseToggles")(lambda d: None)


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


case("TB-25", needs="jvm:ToolbarUatTest.aSumsResultOnTheBar")(lambda d: None)


case("TB-26", needs="jvm:KeyboardFieldsUatTest.theResultReplacesTheWholeSum")(lambda d: None)


case("TB-28", needs="jvm:ToolbarUatTest.copyingNothingLeavesTheCursor")(lambda d: None)


case("TB-29", needs="jvm:ToolbarUatTest.holdingTheCandidatesExpandButtonHides")(lambda d: None)


case("TB-30", needs="jvm:ToolbarUatTest.copyAndCutSaidNothingToCopySaid、EditingLagUatTest.copyWithTheSelectionReportLagging")(lambda d: None)

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


case("TB-44", needs="jvm:ToolbarUatTest.aPieceOfAnEntryCommittedAlone")(lambda d: None)


case("TB-45", needs="jvm:ClipboardLimitUatTest.unlimitedIsTheLimitsButtonAndLoweringAsksFirst")(lambda d: None)


case("TB-46", needs="jvm:ToolbarUatTest.piecesPickedByTapsCommittedInTapOrder")(lambda d: None)


case("TB-47", needs="jvm:KeyboardPickerUatTest.eachInputMethodsKeyboardsPickedFromTheStatusArea")(lambda d: None)


case("TB-48", needs="jvm:ClipboardSplitUatTest.aLongEntrySplitsItsStartAndADottedOneNotAtAll")(lambda d: None)


case("TB-49", needs="jvm:ClipboardSplitUatTest.piecesPickedBySweepingAcross")(lambda d: None)


case("TB-50", needs="jvm:ClipboardOrderUatTest.entriesPastedFromThePanelMoveFirstWhenItCloses、ClipboardDaoTest.anUnpinnedEntryUsedComesFirstOfTheUnpinnedAPinnedOneStays")(lambda d: None)
case("TB-52", needs="jvm:BarUatTest.theToolbarExpandedOverAClipStaysExpanded")(lambda d: None)


@case("TB-51", visual=True)
def tb51(d):
    d.type("nihao")
    time.sleep(0.6)
    ime(d, desc="展开候选词列表")
    time.sleep(0.6)
    d.shot("kb", "展开候选：右侧一列")
    before = [n.text for n in d.screen().ime() if n.text]
    ime(d, desc="更多候选词", after=0.8)
    after = [n.text for n in d.screen().ime() if n.text]
    check(before != after, f"翻页前后一样 {before[:6]}")
    d.shot("kb", "下一页")
    return f"翻页 {before[:3]} → {after[:3]}"
