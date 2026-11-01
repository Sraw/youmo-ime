"""uat/16-landscape-split.md (B0-横屏). The .md is the source of truth; each function follows its
case's 前提/操作/预期. redroid's display does not turn: landscape is the display resized to
2340x1080 (851 dp wide, Device.rotate), which the app sees as a landscape screen."""
from uat import Blocked, case, check


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


def size(box):
    """(width, height) of 14-settings' keys_box / preview_box"""
    return box[2] - box[0], box[3] - box[1]


case("LS-30", needs="jvm:LandscapeUatTest.theFourteenKeysAtTheSideWithTheirCodeRow、LandscapeUatTest.theFourteenKeysOneHandedFloatingAndAsTheThemesPreviewHasThem")(lambda d: None)


case("LS-31", needs="jvm:LandscapeUatTest.theEighteenKeysAtTheSideAsTheFourteen")(lambda d: None)
