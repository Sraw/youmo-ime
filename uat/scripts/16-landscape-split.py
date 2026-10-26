"""uat/16-landscape-split.md (B0-横屏). The .md is the source of truth; each function follows its
case's 前提/操作/预期. redroid's display does not turn: landscape is the display resized to
2340x1080 (851 dp wide, Device.rotate), which the app sees as a landscape screen."""
from uat import Blocked, add_im, case, check, grouped_keys, home, pinyin_keyboard, script, shuangpin_keyboard


def kb_keys(d, s=None):
    """the keyboard's keys: (label, bounds) in reading order"""
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    if not kb:
        raise Blocked("没有键盘")
    out = []
    for n in kb.walk():
        if n.clickable:
            out.append((n.desc or ([m.text for m in n.walk() if m.text] or [""])[0], n.b))
    return out


def tap_side(d, label, side):
    ks = [b for lab, b in kb_keys(d) if lab == label]
    mid = screen_w(d) / 2
    ks = [b for b in ks if (b[0] < mid) == (side == "L")]
    if not ks:
        raise Blocked(f"{'左' if side == 'L' else '右'}半边没有 {label}")
    b = ks[0]
    d.tap(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2), 0.25)


def screen_w(d):
    return int(d.sh("wm size").strip().splitlines()[-1].split()[-1].split("x")[0])


# ---------------------------------------------------------------- the split letters

case("LS-01", needs="jvm:LandscapeUatTest.landscapeSplitsTheLetters")(lambda d: None)


@case("LS-03", base="B0-横屏", mutates="learn")
def ls03(d):
    for k, side in (("w", "L"), ("o", "R"), ("m", "R"), ("e", "L"), ("n", "R")):
        tap_side(d, k, side)
    c = d.cands()
    d.space()
    t = d.field_text()
    check(t == "我们" or t == (c[0].split(" ")[0] if c else None), f"框里「{t}」（候选 {c[:2]}）")
    return f"「{t}」"


case("LS-04", needs="jvm:LandscapeUatTest.bothGsAndVsTypeTheSame")(lambda d: None)


case("LS-05", needs="jvm:LandscapeUatTest.swipeAndLongPressOnTheHalves")(lambda d: None)


case("LS-09", needs="jvm:LandscapeUatTest.splitOffTakesAtOnce")(lambda d: None)


case("LS-10", needs="jvm:LandscapeUatTest.thePreviewSplitsToo")(lambda d: None)


case("LS-13", needs="jvm:LandscapeUatTest.addressFieldSplitBottomRow")(lambda d: None)


# ---------------------------------------------------------------- the other panels

case("LS-14", needs="jvm:LandscapeUatTest.numbersAtTheRightWithTheirBar")(lambda d: None)


case("LS-15", needs="jvm:LandscapeUatTest.theArrowMovesItAcrossAndItStays")(lambda d: None)


case("LS-16", needs="jvm:LandscapeUatTest.lettersAgainFullAndSplit")(lambda d: None)


case("LS-17", needs="jvm:LandscapeUatTest.symbolsAtTheSide")(lambda d: None)


case("LS-18", needs="jvm:LandscapeUatTest.emojiAtTheSide")(lambda d: None)


case("LS-19", needs="jvm:LandscapeUatTest.clipboardAtTheSide")(lambda d: None)


case("LS-20", needs="jvm:LandscapeUatTest.nineKeysAtTheSide")(lambda d: None)


case("LS-21", needs="jvm:LandscapeUatTest.handwritingAtTheSide")(lambda d: None)


case("LS-22", needs="jvm:LandscapeUatTest.fullWidthTakesTheWholeWidth")(lambda d: None)


case("LS-23", needs="jvm:LandscapeUatTest.dockGreyedWhenSplitIsOff")(lambda d: None)


case("LS-24", needs="jvm:LandscapeUatTest.portraitPanelsFull")(lambda d: None)


case("LS-24a", needs="jvm:LandscapeUatTest.portraitAlwaysSplitPanelsFull")(lambda d: None)


case("LS-25", needs="jvm:LandscapeUatTest.numberFieldDocked")(lambda d: None)


case("LS-27", needs="jvm:LandscapeUatTest.tallestIsCappedAtSevenTenths")(lambda d: None)


case("LS-28", needs="jvm:LandscapeUatTest.candidatesAcrossTheWidth")(lambda d: None)


case("LS-29", needs="jvm:LandscapeUatTest.splitShuangpinHasNoSemicolonKey")(lambda d: None)


def kb_and_row(d):
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    row, strip = d.code_row(s), s.find(rid="code_row_syllables", where="ime")
    if not kb or not row:
        raise Blocked("键盘或编码行没出来")
    return kb.b, row.b, strip.b if strip else None


def size(box):
    """(width, height) of 14-settings' keys_box / preview_box"""
    return box[2] - box[0], box[3] - box[1]


def tap_ime(d, text=None, desc=None):
    s = d.screen()
    n = (s.find(text=text, where="ime") if text else None) or (s.find(desc=desc or text, where="ime"))
    if not n:
        raise Blocked(f"键盘上没有「{text or desc}」")
    d.tap(n.c, 1.0)


def toggle_mode(d, name):
    """the toolbar's 状态区域 → name (单手模式, 悬浮键盘): on if off, off if on"""
    d.expand_toolbar()
    tap_ime(d, desc="状态区域")
    tap_ime(d, text=name)


@case("LS-30", base="B0-横屏", mutates=True, visual=True)
def ls30(d):
    d.hide_keyboard()
    pinyin_keyboard(d, "14 键")
    d.open_page("text")
    d.step("横屏分体")
    grouped_keys(d, "bn ui")
    d.cands()
    kb, row, strip = kb_and_row(d)
    check(kb[2] - kb[0] < 0.6 * 2340, f"横屏分体时 14 键 {kb} 没靠一边")
    check(strip and kb[0] - 5 <= strip[0] and strip[2] <= kb[2] + 5, f"音节条 {strip} 不在键盘 {kb} 上方")
    d.shot("kb", "横屏分体：14 键靠一边，编码行和音节条跟着")
    d.clear()
    d.rotate(0)
    d.open_page("text")
    settings = script("14-settings")
    real_w, real = size(settings.keys_box(d))   # the docked full-width board, for the preview's height below
    d.step("单手")
    toggle_mode(d, "单手模式")
    grouped_keys(d, "bn ui")
    d.cands()
    kb1, row1, strip1 = kb_and_row(d)
    check(strip1 and kb1[0] - 5 <= strip1[0] and strip1[2] <= kb1[2] + 5, f"单手：音节条 {strip1}，键盘 {kb1}")
    d.shot("kb", "单手：编码行和音节条随键盘")
    d.clear()
    toggle_mode(d, "单手模式")
    d.step("悬浮")
    toggle_mode(d, "悬浮键盘")
    grouped_keys(d, "bn ui")
    d.cands()
    kb2, row2, strip2 = kb_and_row(d)
    check(strip2 and kb2[0] - 5 <= strip2[0] and strip2[2] <= kb2[2] + 5, f"悬浮：音节条 {strip2}，键盘 {kb2}")
    d.shot("kb", "悬浮：编码行和音节条随键盘")
    d.clear()
    d.step("主题页的预览")
    d.hide_keyboard()
    home(d, "外观")
    # the page shows the preview scaled down to fit (half the width on a phone): its height is
    # compared at the real board's width, the keys' union across in both
    # the preview takes the code row's height off its keys as the real keyboard does (KeyboardHeightCap)
    pw, ph = size(settings.preview_box(d))
    pv = round(ph * real_w / pw) if pw else 0
    d.shot("full", "外观页的键盘预览：和真键盘一样高（含 40dp 编码行）")
    check(abs(real - pv) / d.dpx() <= 3, f"预览的键高 {pv}px，真键盘 {real}px")
    return f"分体 {kb}；单手 {kb1}；悬浮 {kb2}；预览 {pv}px / 真键盘 {real}px"


@case("LS-31", base="B0-横屏", mutates=True, visual=True)
def ls31(d):
    d.hide_keyboard()
    add_im(d, "双拼")
    shuangpin_keyboard(d, "18 键")
    d.open_page("text")
    d.switch_im("双拼")
    k = d.keys(refresh=True)
    miss = [g for g in "q we rt y u io p a sd fg h jk z xc v bn m".split() if g not in k]
    check(not miss, f"横屏 18 键上没有 {miss}")
    grouped_keys(d, "bn io")
    d.cands()
    kb, row, strip = kb_and_row(d)
    sy = d.syllables()
    check(kb[2] - kb[0] < 0.6 * 2340, f"横屏分体时 18 键 {kb} 没靠一边")
    check(strip and kb[0] - 5 <= strip[0] and strip[2] <= kb[2] + 5, f"音节条 {strip} 不在键盘 {kb} 上方")
    check("ni" in sy, f"音节条 {sy}")
    d.shot("kb", "横屏分体：18 键靠一边，编码行和音节条跟着")
    d.clear()
    return f"18 键 {kb}；音节条 {sy[:4]}"
