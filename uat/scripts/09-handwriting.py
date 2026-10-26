"""uat/09-handwriting.md (B0-手写). The .md is the source of truth; each function follows its case's
前提/操作/预期. "写「X」" is X drawn on the pad with input motionevent strokes (hw_strokes.py)."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hw_strokes import STROKES  # noqa: E402

from uat import Blocked, case, check, menu_tap  # noqa: E402


def pad(d):
    p = d.screen().find(desc="手写板", where="ime")
    if not p:
        raise Blocked("没有书写板")
    return p.b


def stroke_cmds(d, ch, strokes=None, gap=0.05):
    x0, y0, x1, y1 = pad(d)
    side = min(x1 - x0, y1 - y0) * 0.8
    ox, oy = x0 + (x1 - x0 - side) / 2, y0 + (y1 - y0 - side) / 2
    cmds = []
    for st in (strokes if strokes is not None else STROKES[ch]):
        pts = [(int(ox + side * x), int(oy + side * y)) for x, y in st]
        cmds.append("input motionevent DOWN %d %d" % pts[0])
        for (a, b), (c, e) in zip(pts, pts[1:]):
            for i in range(1, 7):
                cmds.append("input motionevent MOVE %d %d" % (a + (c - a) * i / 6, b + (e - b) * i / 6))
        cmds.append("input motionevent UP %d %d" % pts[-1])
        cmds.append(f"sleep {gap}")
    return cmds


def write(d, ch, wait=1.6):
    """write ch and stop the pen (the default pause, then a margin)"""
    d.sh("; ".join(stroke_cmds(d, ch)))
    time.sleep(wait)


def ink(d):
    """dark pixels on the pad (a stroke drawn and not yet cleared)"""
    x0, y0, x1, y1 = pad(d)
    im = d.png().convert("L").crop((x0, y0, x1, y1)).resize((120, 60))
    bg = sorted(im.getdata())[len(im.getdata()) // 2]
    return sum(1 for p in im.getdata() if abs(p - bg) > 60)


case("HW-01", needs="jvm:HandwritingUatTest.theHandwritingKeyboard")(lambda d: None)


@case("HW-02", base="B0-手写", visual=True)
def hw02(d):
    cmds = stroke_cmds(d, "人")
    d.sh("; ".join(cmds))
    mid = []
    end = time.time() + 0.6   # before the pen counts as stopped (about 0.7 s)
    while not mid and time.time() < end:
        mid = d.candidates()
    time.sleep(1.5)
    s = d.screen()
    c, t = d.candidates(s), d.field_text(s)
    left = ink(d)
    d.shot("field", "写「人」停笔后")
    check(mid, "写的过程中（刚写完、未停笔）候选栏没有候选")
    check(t == "人", f"框里「{t}」")
    check("人" in c, f"候选 {c[:5]}")
    check(left < 40, f"停笔后书写板没清空（墨迹 {left}）")
    return f"写时 {mid[:3]}，停笔后框里「{t}」（下划线看图），候选 {c[:4]}"


@case("HW-03", base="B0-手写", mutates="learn")
def hw03(d):
    write(d, "大")
    write(d, "人")
    d.space()
    t = d.field_text()
    check(t == "大人", f"框里「{t}」")
    return f"「{t}」"


case("HW-04", needs="jvm:HandwritingEngineUatTest.aCharacterWrittenAfterCorrectsTheOneBefore")(lambda d: None)


case("HW-05", needs="jvm:HandwritingEngineUatTest.aCandidatePickedPutsInTheSentenceEndingWithIt")(lambda d: None)


case("HW-06", needs="jvm:HandwritingUatTest.backspaceTakesTheLastStrokeBack")(lambda d: None)


case("HW-07", needs="jvm:HandwritingEngineUatTest.backspaceTakesTheUnwrittenCharacterThenTheText")(lambda d: None)


case("HW-08", needs="jvm:HandwritingEngineUatTest.punctuationPutsTheSentenceInFirst")(lambda d: None)


@case("HW-09", base="B0-手写", mutates="learn", visual=True)
def hw09(d):
    write(d, "人")
    for _ in range(3):
        # while 人 is composing the candidates replace the bar and its ⌄: the system back hides it
        if d.screen().find(desc="隐藏键盘", where="ime"):
            d.hide_keyboard()
        else:
            d.back()
        time.sleep(0.8)
        if not d.screen().find(rid="keyboard_view", where="ime"):
            break
    else:
        raise Blocked("键盘收不起来")
    t = d.field_text()
    d.shot("app", "收起键盘后")
    check(t == "人", f"框里「{t}」")
    return f"「{t}」留在框里（下划线看图）"


case("HW-10", needs="jvm:HandwritingEngineUatTest.aSpaceThenPredictions")(lambda d: None)


@case("HW-19", base="B0-手写", mutates="learn")
def hw19(d):
    write(d, "人")
    c = [x.split(" ")[0] for x in d.candidates()]
    c0 = c
    if "入" not in c:
        raise Blocked(f"写「人」的候选里没有「入」：{c[:6]}")
    d.pick("入")
    d.clear()
    write(d, "人")
    menu = d.long_pick("入")
    has_forget = any("忘掉学到的" in x for x in menu)
    has_block = any(x == "屏蔽" for x in menu)
    has_pin = any("置顶" in x or "固定" in x for x in menu)
    check(has_forget and has_block and not has_pin, f"长按菜单 {menu[:8]}")
    # and blocked: never written again
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    c2 = [x.split(" ")[0] for x in d.all_cands()]
    # undone without writing again: the shown candidates read anew (F2738)
    d.tap_text("撤销", where="all", scroll=False, after=0.8)
    c3 = [x.split(" ")[0] for x in d.all_cands()]
    d.long_pick("入")
    menu_tap(d, "忘掉学到的")
    c4 = [x.split(" ")[0] for x in d.candidates()]
    d.clear()
    write(d, "人")
    c5 = [x.split(" ")[0] for x in d.candidates()]
    d.clear()
    check("入" not in c2, f"屏蔽后还有「入」：{c2[:6]}")
    check("入" in c3, f"点撤销后（不重写）{c3[:6]}")
    check(c4[:3] == c0[:3] and c5[:3] == c0[:3], f"忘掉后当场 {c4[:3]}、重写 {c5[:3]}，学之前 {c0[:3]}")
    return f"长按「入」：{menu[:4]}；屏蔽后 {c2[:4]}；撤销后 {c3[:4]}；忘掉后 {c4[:3]}"


case("HW-11", needs="jvm:HandwritingEngineUatTest.returnPutsInWithoutPredictions")(lambda d: None)


case("HW-12", needs="jvm:HandwritingUatTest.aShortAndALongPause")(lambda d: None)


case("HW-14", needs="jvm:HandwritingEngineUatTest.aCharacterPickedRanksUpTwiceFirst")(lambda d: None)


case("HW-16", needs="jvm:HandwritingUatTest.aPasswordIsTheEnglishKeyboard")(lambda d: None)


@case("HW-25", base="B0-手写", mutates="learn", visual=True)
def hw25(d):
    write(d, "人")
    write(d, "大")
    t0 = d.field_text()
    if t0 != "人大":
        raise Blocked(f"前提没到：写了人、大，框里「{t0}」")
    # before the first character (as 05-toolbar's copy_range): a cursor move inside the composing
    # text commits it as it is (fcitx's defaultInvokeActionBehavior, HostCore.moveCursor)
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 1.0)
    t1 = d.field_text()
    c1 = d.cands()
    left = ink(d)
    write(d, "十")
    t2 = d.field_text()
    d.shot("app", "点过以后再写十")
    check(t1 == "人大", f"点一下后框里「{t1}」")
    check(not c1, f"点一下后候选 {c1[:3]}")
    check(left < 20, f"点一下后书写板上还有笔画（{left}）")
    # a commit equal to the composing text puts the cursor at its end (EditingSession.commitText,
    # as 0.3.x): 十 goes in after 人大
    check(t2 == "人大十", f"再写十后框里「{t2}」")
    return f"点后「{t1}」，候选 {c1[:2]}；再写「{t2}」（下划线看图）"


case("HW-23", needs="jvm:HandwritingEngineUatTest.aCharacterDeletedRightAwayIsNotUsed")(lambda d: None)
case("HW-26", needs="jvm:HandwritingEngineUatTest.aCharacterNotYetInIsShownTraditionalAtTheToggle")(lambda d: None)

