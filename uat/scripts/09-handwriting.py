"""uat/09-handwriting.md (B0-手写). The .md is the source of truth; each function follows its case's
前提/操作/预期. "写「X」" is X drawn on the pad with input motionevent strokes (hw_strokes.py)."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hw_strokes import STROKES  # noqa: E402

from uat import Blocked, block_candidate, case, check, glide_on  # noqa: E402


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


case("HW-01", needs="jvm:HandwritingUatTest.theHandwritingKeyboard")(lambda d: None)


case("HW-02", needs="jvm:HandwritingUatTest.whileWritingTheReadingIsOfferedAndThePauseWipesThePad、HostCasesUatTest.aCharacterWrittenIsComposingInTheFieldWithItsReadings")(lambda d: None)


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


case("HW-09", needs="jvm:HostCasesUatTest.aCharacterNotCommittedStaysInTheFieldWhenTheKeyboardGoes")(lambda d: None)


case("HW-10", needs="jvm:HandwritingEngineUatTest.aSpaceThenPredictions")(lambda d: None)


case("HW-19", needs="jvm:KeyboardHalvesUatTest.handwritingsMenuHasNoPinItsBlockUndoneAndForgettingReordersAtOnce、HandwritingUatTest.handwritingsMenuHasNoPinAndItsNoticeIsOverTheCandidates")(lambda d: None)


case("HW-11", needs="jvm:HandwritingEngineUatTest.returnPutsInWithoutPredictions")(lambda d: None)


case("HW-12", needs="jvm:HandwritingUatTest.aShortAndALongPause")(lambda d: None)


case("HW-14", needs="jvm:HandwritingEngineUatTest.aCharacterPickedRanksUpTwiceFirst")(lambda d: None)


case("HW-16", needs="jvm:HandwritingUatTest.aPasswordIsTheEnglishKeyboard")(lambda d: None)


case("HW-25", needs="jvm:HostCasesUatTest.aTapOnCharactersNotInPutsThemInAsTheyAre")(lambda d: None)


case("HW-23", needs="jvm:HandwritingEngineUatTest.aCharacterDeletedRightAwayIsNotUsed")(lambda d: None)


case("HW-26", needs="jvm:HandwritingEngineUatTest.aCharacterNotYetInIsShownTraditionalAtTheToggle")(lambda d: None)


case("HW-27", needs="jvm:CandidateReadingsUatTest.handwritingPortrait、CandidateReadingsUatTest.handwritingLandscape、ReadingSpanTest、HandwritingEngineUatTest.theCandidatesLeaveOutACharactersOwnNeutralTone")(lambda d: None)


case("HW-28", needs="jvm:HandwritingUatTest.theReadingKeyTurnsTheReadingsOnAndWhatIsWrittenStays、HandwritingKeyboardTest.theReadingKeyTurnsTheReadingsOnAsLastUsedAndOff")(lambda d: None)


case("HW-29", needs="jvm:HandwritingUatTest.aHeldCharactersCardThenItsButtonsAndTheMenu、HandwritingUatTest.theCardInLandscapeIsTwoColumns、HandwritingEngineUatTest.aCardsWordsAreADictionarys、HandwritingEngineUatTest.aCardsStrokeOrderIsTheMainlands、HandwritingEngineUatTest.aCardsTurningStrokesEachTheirShape、HandwritingEngineUatTest.theStandardsExamplesEachTheirTurningStroke")(lambda d: None)


case("HW-30", needs="jvm:HandwritingUatTest.copyPutsTheCharacterAloneOnTheClipboardAndCommitIsATap")(lambda d: None)


@case("HW-31", base="B0-手写", mutates=True)
def hw31(d):
    d.wait(lambda *_: d.screen().find(desc="手写板", where="ime"), 10, what="书写板")
    write(d, "天")
    first = d.long_pick("天")
    d.shot("full", "写「天」，长按「天」的菜单")
    check("屏蔽" in first, "长按「天」的菜单里没有屏蔽")
    # closed by the confirmation's 取消: back would hide the keyboard
    d.tap_text("屏蔽", where="all", scroll=False, after=0.6)
    d.tap_text("取消", where="all", scroll=False, after=0.6)
    write(d, "人")
    c = d.cands()
    check("人" in c, f"写「天人」的候选 {c[:6]}")
    block_candidate(d, "人", "接着写「人」，长按「人」的菜单")
    time.sleep(1.5)
    after = d.cands()
    d.shot("kb", "屏蔽「人」后")
    check("人" not in after, f"屏蔽后候选还有「人」：{after[:6]}")
    check(after, "屏蔽后候选空了")
    return f"「天」「人」的菜单都有屏蔽；屏蔽后候选 {after[:4]}（设置里恢复由 JVM 管）"


# ---- 键盘手写 (HW-32..37): written over the letters of the pinyin keyboard


def letters_box(d):
    """the three rows of letters, q to m: left, top, right, bottom"""
    k = d.keys(refresh=True)
    for c in "qpm":
        if c not in k:
            raise Blocked(f"键盘上找不到 {c}")
    return k["q"][0], k["q"][1], k["p"][0], k["m"][1]


def key_box(d, key):
    """a square two rows of letters tall whose top middle (a dot's place, 0.5 by 0.12) is on [key]: a character
    written under the key just tapped, as large as a person writes on the keys (design 060-kbd-handwriting-start)"""
    x0, y0, x1, y1 = letters_box(d)
    kx, ky = d.keys()[key]
    side = y1 - y0
    return kx - side / 2, ky - side * 0.12, kx + side / 2, ky + side * 0.88


def key_strokes(d, strokes, gap=0.05, box=None):
    x0, y0, x1, y1 = box or letters_box(d)
    cmds = []
    for st in strokes:
        pts = [(int(x0 + (x1 - x0) * x), int(y0 + (y1 - y0) * y)) for x, y in st]
        cmds.append("input motionevent DOWN %d %d" % pts[0])
        # three moves a segment: each input call takes ~55 ms on the emulator, and a first stroke must be half a
        # key long (KeyInkGesture.START) before the long press (300 ms): 人's first stroke is there at its second
        for (a, b), (c, e) in zip(pts, pts[1:]):
            for i in range(1, 4):
                cmds.append("input motionevent MOVE %d %d" % (a + (c - a) * i / 3, b + (e - b) * i / 3))
        cmds.append("input motionevent UP %d %d" % pts[-1])
        cmds.append(f"sleep {gap}")
    return cmds


def key_write(d, ch, wait=1.6, strokes=None):
    d.sh("; ".join(key_strokes(d, strokes if strokes is not None else STROKES[ch])))
    time.sleep(wait)


def tap_then_write(d, key, strokes):
    """a tap on [key] and [strokes] after it under the key (the stroke after a dot goes down near it), in one
    shell, within the pause; what the field and candidates then hold"""
    k = d.keys(refresh=True)[key]
    d.sh("; ".join(["input tap %d %d" % k, "sleep 0.15"] + key_strokes(d, strokes, box=key_box(d, key))))
    time.sleep(1.6)
    return d.field_text(), d.preedit(), d.cands()


@case("HW-32", base="B0-设置", mutates=True)
def hw32(d):
    glide_on(d, option="键盘手写")
    key_write(d, "人")
    c = d.cands()
    check("人" in c[:5], f"候选 {c[:5]}")
    d.space()
    t = d.field_text()
    check(t == "人", f"框里「{t}」")
    return f"「{t}」"


@case("HW-33", base="B0-设置", mutates=True)
def hw33(d):
    glide_on(d, option="键盘手写")
    # the tapped letter is pinyin's composing input, shown in the composing row, not in the field (see HW-35)
    fs = []
    for key, why in (("t", ""), ("e", "（e 是整音节，拼音点完会再排一次候选）")):
        d.step(f"点 {key} 再写六的其余三笔{why}")
        f, p, c = tap_then_write(d, key, STROKES["六"][1:])
        check(key not in p.lower() and key not in f.lower(), f"{key} 没删：组字「{p}」，框里「{f}」")
        check("六" in c[:5] or "六" in f, f"候选 {c[:5]}，框里「{f}」")
        fs.append(f)
        d.clear()
    f, fa = fs
    d.step("打着 ni 时划一横")
    d.type("ni")
    key_write(d, None, wait=1.0, strokes=[[(0.2, 0.5), (0.8, 0.5)]])
    p = d.preedit()
    check("ni" in p.replace(" ", "").replace("'", ""), f"组字「{p}」")
    return f"「{f}」，点 e 后「{fa}」，打着 ni 时「{p}」"


case("HW-34", needs="jvm:KeyHandwritingUatTest.whileWritingATapIsAStrokeBackspaceTakesOneBackAndSpaceCommits")(lambda d: None)


@case("HW-35", base="B0-设置", mutates=True)
def hw35(d):
    glide_on(d, option="键盘手写")
    key_write(d, "人")
    d.type("n")
    f = d.field_text()
    p = d.preedit()
    # pinyin's input is shown in the composing row, not in the field
    check(f == "人" and p.lower().startswith("n"), f"框里「{f}」，组字「{p}」")
    return f"「{f}」+「{p}」"


case("HW-36", needs="jvm:KeyHandwritingUatTest.theOtherKeyboardsAreWrittenOnToo")(lambda d: None)

case("HW-37", needs="jvm:KeyHandwritingUatTest.notInAPasswordUrlOrEmailFieldNorEnglishNorWhileTalkBackExplores、KeyHandwritingUatTest.englishKeysGoStraightOnTheRecognizerNotMade")(lambda d: None)


@case("HW-38", base="B0-设置", mutates=True)
def hw38(d):
    glide_on(d, option="键盘手写")
    k = d.keys(refresh=True)
    x0, y0, x1, y1 = letters_box(d)
    kw, kh = (x1 - x0) / 9, (y1 - y0) / 2
    # a drag within one input swipe: DOWN, MOVEs, UP in 80 ms, as a quick tap (tap_drift)
    d.step("没打字时带拖 0.4 键点 n")
    d.tap_drift(k["n"], dx=int(0.4 * kw), dy=0, ms=80, after=0.8)
    p = d.preedit()
    check(p.replace(" ", "").lower().startswith("n"), f"组字「{p}」（应打出 n）")
    d.clear()
    # half a key and more, right and down, inside the key and short of its swipe (36dp): a stroke if it could start
    ddx, ddy = int(0.35 * kw), int(0.4 * kh)
    d.step("点 w，马上在 o 上往右下拖半个多键")
    d.sh("input tap %d %d; input swipe %d %d %d %d 80" % (*k["w"], *k["o"], k["o"][0] + ddx, k["o"][1] + ddy))
    time.sleep(0.8)
    p2 = d.preedit()
    check(p2.replace(" ", "").replace("'", "").lower().startswith("wo"), f"组字「{p2}」（应是 wo）")
    d.clear()
    d.step("打 ni 空格，马上在 h 上往右下拖半个多键")
    d.type("ni")
    sp = d.keys()["space"]
    d.sh("input tap %d %d; input swipe %d %d %d %d 80" % (*sp, *k["h"], k["h"][0] + ddx, k["h"][1] + ddy))
    time.sleep(1.2)
    f = d.field_text()
    p3 = d.preedit()
    d.shot("kb", "空格后马上划 h")
    typed = p3.replace(" ", "").lower()[:1] or f[-1:].lower()
    check(typed == "h", f"框里「{f}」组字「{p3}」（应打出 h，不写字）")
    d.clear()
    time.sleep(1.0)
    got = []
    for ch in "一十人":
        d.step(f"停一下再写「{ch}」")
        key_write(d, ch)
        c = d.cands()
        check(ch in c[:5], f"写「{ch}」候选 {c[:5]}")
        got.append(f"{ch}:{c[:3]}")
        d.clear()
        time.sleep(1.0)
    return f"n →「{p}」，w o →「{p2}」，空格后拖 h →「{f}」「{p3}」；" + "，".join(got)


case("HW-39", needs="jvm:KeysEverywhereUatTest.handwritingsCommaAndFullStopAreEveryKeyboards、HandwritingSceneGesturesTest.commaAndFullStopSwipedAreTappedAndAStrokeOffThemIsNothing")(lambda d: None)
