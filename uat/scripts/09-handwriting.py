"""uat/09-handwriting.md (B0-手写). The .md is the source of truth; each function follows its case's
前提/操作/预期. "写「X」" is X drawn on the pad with input motionevent strokes (hw_strokes.py)."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hw_strokes import STROKES  # noqa: E402

from uat import Blocked, block_candidate, case, check  # noqa: E402


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
    return f"「天」「人」的菜单都有屏蔽；屏蔽后候选 {after[:4]}（设置里恢复由 JVM 管）"
