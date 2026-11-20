"""uat/16-landscape-split.md (B0-横屏). The .md is the source of truth; each function follows its
case's 前提/操作/预期. Landscape is the display resized to (as on redroid, whose display did not turn)
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
            # a canvas key's node has its label as text and "w，2" as words: the label, as a view's text was
            out.append((n.text or n.desc or ([m.text for m in n.walk() if m.text] or [""])[0], n.b))
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




@case("LS-03", base="B0-横屏", mutates="learn")
def ls03(d):
    for k, side in (("w", "L"), ("o", "R"), ("m", "R"), ("e", "L"), ("n", "R")):
        tap_side(d, k, side)
    c = d.cands()
    d.space()
    t = d.field_text()
    check(t == "我们" or t == (c[0].split(" ")[0] if c else None), f"框里「{t}」（候选 {c[:2]}）")
    return f"「{t}」"




def size(box):
    """(width, height) of 14-settings' keys_box / preview_box"""
    return box[2] - box[0], box[3] - box[1]



case("LS-33", needs="jvm:WideScreenUatTest.anUnfoldedFoldableUprightIsLaidOutTurned、WideScreenUatTest.theUnfoldedFoldableTurnedIsAsHigh、"
     "WideScreenUatTest.theExpandedCandidatesTakeTheTurnedColumnsUpright")(lambda d: None)
case("LS-35", needs="manual:折叠屏模拟器上 adb emu fold/unfold（或 wm size 在 412 和 673 dp 宽之间换）时键盘马上重排；没写成脚本")(lambda d: None)
case("LS-44", needs="manual:折叠屏模拟器或 wm size 673×841 dp：展开两个方向都用横屏键盘样式，外屏用竖屏的；没写成脚本")(lambda d: None)
case("LS-47", needs="jvm:ImeRoomViewTest.theAppKeeps120dpUnderTheBar、KeyboardHeightCapTest.whereTheFloorBindsTheKeysGiveWay、"
     "ImeRoomViewTest.theBarsInsetsComingWorkTheKeysOutAgain")(lambda d: None)
case("LS-49", needs="jvm:WideScreenUatTest.anUnfoldedFoldableUprightIsLaidOutTurned、WideScreenUatTest.theUnfoldedFoldableTurnedIsAsHigh、"
     "WideScreenUatTest.aTabletUprightIsTurnedAndSplits、KeyboardAreaTest.aTabletAShortSideOver600IsLargeWhicheverWayHeld、"
     "KeyboardScreensTest.aLargeScreenIsTheDisplaysShortSideNotTheWindows")(lambda d: None)
case("LS-50", needs="jvm:KeyboardHeightCapTest.theRowsWinOverTheFloor、ImeRoomViewTest.aSmallPhonesZhuyinKeepsItsFiveRows、"
     "ImeRoomTest.flushAndFullScreenAndASideBar、LowLandscapeScreenshotTest.thePanelsAtTheFloor、LowLandscapeScreenshotTest.zhuyinsFiveRowsOf36")(lambda d: None)
case("LS-51", needs="manual:五档每行的高看截图，转屏 20 次看键盘不先矮后高；2026-11-14 在 API 33/36 模拟器上量过（061-landscape-height-impl-report）")(lambda d: None)


case("LS-53", needs="jvm:SettingsCardsLayoutTest.onItsSide、KeyShortcutsPageScreenshotTest.landscape、KeyShortcutsPageScreenshotTest.theLargestFont、"
               "RareCharsCardsUatTest.theCardsAtNight、RareCharsCardsUatTest.theCardsInTheLargestFont、RareCharsCardsUatTest.theCardsOnTheSide")(lambda d: None)
