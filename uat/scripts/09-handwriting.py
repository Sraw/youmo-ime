"""uat/09-handwriting.md (B0-手写: the toolbar's 手写 on). The .md is the source of truth; each function follows its
case's 前提/操作/预期. "写「X」" is X drawn over the letters, q to m, with input motionevent strokes (hw_strokes.py)."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hw_strokes import STROKES  # noqa: E402

from uat import Blocked, block_candidate, case, check, handwriting  # noqa: E402


def letters_box(d):
    """the three rows of letters, q to m: left, top, right, bottom"""
    k = d.keys(refresh=True)
    for c in "qpm":
        if c not in k:
            raise Blocked(f"键盘上找不到 {c}")
    return k["q"][0], k["q"][1], k["p"][0], k["m"][1]


def key_strokes(d, strokes, gap=0.05, box=None):
    x0, y0, x1, y1 = box or letters_box(d)
    cmds = []
    for st in strokes:
        pts = [(int(x0 + (x1 - x0) * x), int(y0 + (y1 - y0) * y)) for x, y in st]
        cmds.append("input motionevent DOWN %d %d" % pts[0])
        # three moves a segment: each input call takes ~55 ms on the emulator
        for (a, b), (c, e) in zip(pts, pts[1:]):
            for i in range(1, 4):
                cmds.append("input motionevent MOVE %d %d" % (a + (c - a) * i / 3, b + (e - b) * i / 3))
        cmds.append("input motionevent UP %d %d" % pts[-1])
        cmds.append(f"sleep {gap}")
    return cmds


def write(d, ch, wait=1.6, strokes=None):
    """write ch over the letters and stop the pen (the default pause, then a margin)"""
    d.sh("; ".join(key_strokes(d, strokes if strokes is not None else STROKES[ch])))
    time.sleep(wait)


def lit(d):
    """the 手写 button on screen (the toolbar's or the candidate bar's): whether it is lit, None when there is none"""
    b = d.screen().find(desc="手写", where="ime")
    return b.checked if b else None


case("HW-01", needs="jvm:HandwritingUatTest.theKeyboardWithHandwritingOn")(lambda d: None)


case("HW-02", needs="jvm:HandwritingUatTest.whileWritingTheReadingIsOfferedAndThePauseWipesThePad、HostCasesUatTest.aCharacterWrittenIsShownAsTypedWithItsReadings")(lambda d: None)


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


case("HW-09", needs="jvm:HostCasesUatTest.aCharacterNotCommittedIsPutInWhenTheKeyboardGoes")(lambda d: None)


case("HW-10", needs="jvm:HandwritingEngineUatTest.aSpaceThenPredictions")(lambda d: None)


case("HW-19", needs="jvm:KeyboardHalvesUatTest.handwritingsMenuHasNoPinItsBlockUndoneAndForgettingReordersAtOnce、HandwritingUatTest.handwritingsMenuHasNoPinAndItsNoticeIsOverTheCandidates")(lambda d: None)


case("HW-11", needs="jvm:HandwritingEngineUatTest.returnPutsInWithoutPredictions")(lambda d: None)


case("HW-12", needs="jvm:HandwritingUatTest.aShortAndALongPause")(lambda d: None)


case("HW-14", needs="jvm:HandwritingEngineUatTest.aCharacterPickedRanksUpTwiceFirst")(lambda d: None)


case("HW-16", needs="jvm:HandwritingUatTest.aPasswordIsTypedOnAndHandwritingComesBackAfter")(lambda d: None)


case("HW-25", needs="jvm:HostCasesUatTest.aTapInTheFieldPutsTheWrittenInAsItIs、"
              "HostCasesUatTest.aTapElsewhereInTheFieldPutsTheWrittenInAtTheNewCursor")(lambda d: None)


case("HW-23", needs="jvm:HandwritingEngineUatTest.aCharacterDeletedRightAwayIsNotUsed")(lambda d: None)


case("HW-26", needs="jvm:HandwritingEngineUatTest.aCharacterNotYetInIsShownTraditionalAtTheToggle")(lambda d: None)


case("HW-27", needs="jvm:EnginesHandwritingReadingsTest.turnedOnWhatIsWrittenStaysAndIsReadAgain、CandidateReadingsUatTest.handwritingPortrait、CandidateReadingsUatTest.handwritingLandscape、ReadingSpanTest、HandwritingEngineUatTest.theCandidatesLeaveOutACharactersOwnNeutralTone")(lambda d: None)



case("HW-29", needs="jvm:HandwritingUatTest.aHeldCharactersCardThenItsButtonsAndTheMenu、HandwritingUatTest.theCardInLandscapeIsTwoColumns、HandwritingEngineUatTest.aCardsWordsAreADictionarys、HandwritingEngineUatTest.aCardsStrokeOrderIsTheMainlands、HandwritingEngineUatTest.aCardsTurningStrokesEachTheirShape、HandwritingEngineUatTest.theStandardsExamplesEachTheirTurningStroke")(lambda d: None)


case("HW-30", needs="jvm:HandwritingUatTest.copyPutsTheCharacterAloneOnTheClipboardAndCommitIsATap")(lambda d: None)


@case("HW-31", base="B0-手写", mutates=True)
def hw31(d):
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


# ---- written over the keys (HW-32..37)


@case("HW-32", base="B0-手写", mutates="learn")
def hw32(d):
    write(d, "人")
    c = d.cands()
    check("人" in c[:5], f"候选 {c[:5]}")
    d.space()
    t = d.field_text()
    check(t == "人", f"框里「{t}」")
    return f"「{t}」（短划由 JVM 管）"


case("HW-34", needs="jvm:KeyHandwritingUatTest.whileWritingATapIsAStrokeBackspaceTakesOneBackAndSpaceCommits")(lambda d: None)

case("HW-36", needs="jvm:KeyHandwritingUatTest.theOtherKeyboardsAreWrittenOnToo")(lambda d: None)

case("HW-37", needs="jvm:KeyHandwritingUatTest.urlAndEmailFieldsAreWrittenOn、KeyHandwritingUatTest.notInAPasswordFieldNorEnglishNorQuickInputNorWhileTalkBackExplores、"
              "KeyHandwritingUatTest.englishKeysGoStraightOnTheRecognizerNotMade")(lambda d: None)


# ---- the toolbar's 手写 (HW-41..52)


@case("HW-41", base="B0", mutates="learn")
def hw41(d):
    check(lit(d) is False, f"新装时工具栏的手写：{lit(d)}（应在、没亮）")
    handwriting(d, True)
    d.shot("kb", "手写打开：按钮亮、字母淡")
    write(d, "人")
    c = d.cands()
    check("人" in c[:5], f"候选 {c[:5]}")
    d.space()
    t = d.field_text()
    check(t == "人", f"框里「{t}」")
    handwriting(d, False)
    d.press("q", after=0.6)
    p = d.preedit()
    check(p.replace(" ", "").lower().startswith("q"), f"关掉后点 q：编码「{p}」")
    return f"写「{t}」，关后打 q →「{p}」"


@case("HW-42", base="B0-手写", mutates="learn")
def hw42(d):
    x0, y0, x1, y1 = letters_box(d)
    dot = STROKES["主"][0][0]
    at = (int(x0 + (x1 - x0) * dot[0]), int(y0 + (y1 - y0) * dot[1]))
    # the letter key under the dot (t or y on most boards): the one a tap that typed would have typed
    tapped = min(((ch, c) for ch, c in d.keys().items() if len(ch) == 1 and ch.isascii() and ch.isalpha()),
                 key=lambda kc: (kc[1][0] - at[0]) ** 2 + (kc[1][1] - at[1]) ** 2)[0]
    d.sh("; ".join(["input tap %d %d" % at, "sleep 0.1"] + key_strokes(d, STROKES["主"][1:])))
    time.sleep(1.6)
    c, f, p = d.cands(), d.field_text(), d.preedit()
    check(tapped.lower() not in (p + f).lower(), f"点的那下（{tapped} 键上）打出了字母：「{p}」「{f}」")
    check("主" in c[:5], f"点一下再写「主」，候选 {c[:5]}")
    return f"候选 {c[:3]}"


case("HW-43", needs="jvm:KeyHandwritingUatTest.shiftIsWrittenOn")(lambda d: None)

case("HW-44", needs="jvm:KeyHandwritingUatTest.theOtherKeyboardsAreWrittenOnToo、KeyHandwritingUatTest.anotherInputMethodAndBackTheToggleIsKept、"
              "HandwritingModeTest.offeredInEveryChineseInputMethodNotEnglishNorAPassword")(lambda d: None)


@case("HW-45", base="B0-手写", mutates="learn")
def hw45(d):
    write(d, "人")
    c = d.cands()
    check("人" in c[:5], f"候选 {c[:5]}")
    check(lit(d) is True, f"写着字时候选栏的手写：{lit(d)}（应在、亮着）")
    d.shot("kb", "写着字：候选栏右端亮着的手写")
    b = d.screen().find(desc="手写", where="ime")
    d.tap(b.c, 0.8)
    p = d.preedit()
    check(lit(d) is None, "点了以后候选栏上还有手写按钮")
    check(p.replace(" ", "") != "", "关掉手写后写好的字没了（应留着、不替用户上屏）")
    # a letter typed with the written text still there puts it in first (InkOver.letter), then types
    d.press("q", after=0.6)
    t = d.field_text()
    q = d.preedit()
    check(t == p.replace(" ", ""), f"关掉后点 q：框里「{t}」（应是先上屏的「{p}」）")
    check(q.replace(" ", "").lower().startswith("q"), f"关掉后点 q：编码「{q}」")
    return f"关后留着「{p}」，点 q 先上屏 →「{t}」，编码「{q}」"


case("HW-46", needs="jvm:InkOverTest.aFirstStrokeWhilePinyinHasSomethingTypedPutsItsFirstCandidateInThenWrites、"
              "EnginesInkOverTest.aStrokeWhilePinyinIsTypedPutsItsFirstCandidateInThenIsWritten")(lambda d: None)

case("HW-47", needs="jvm:KeyHandwritingUatTest.anotherInputMethodAndBackTheToggleIsKept")(lambda d: None)


@case("HW-48", base="B0-手写", mutates="learn")
def hw48(d):
    d.hide_keyboard()
    time.sleep(0.8)
    d.open_page("text")
    check(lit(d) is True, f"键盘回来时工具栏的手写：{lit(d)}（应亮着）")
    write(d, "人")
    c = d.cands()
    check("人" in c[:5], f"收起再打开后写「人」，候选 {c[:5]}")
    return f"候选 {c[:3]}"


case("HW-40", needs="jvm:HandwritingUatTest.aHeldCharactersCardThenItsButtonsAndTheMenu、EnginesHandwritingReadingsTest.theCardOfAWrittenCharacterOrOneLookedUp")(lambda d: None)

case("HW-49", needs="jvm:KeyHandwritingUatTest.notInAPasswordFieldNorEnglishNorQuickInputNorWhileTalkBackExplores、"
              "KeyHandwritingUatTest.outOfQuickInputTheLettersAreWrittenOnAgain")(lambda d: None)

case("HW-50", needs="jvm:KeyHandwritingUatTest.glideTypingWaitsWhileHandwritingIsOn、KeyHandwritingUatTest.aShortSwipeIsAStrokeNotTheKeysCorner、"
              "KeyHandwritingUatTest.strokeFilteringWaitsWhileHandwritingIsOn")(lambda d: None)

case("HW-51", needs="jvm:EnginesHandwritingReadingsTest.aWrittenCharacterShowsItsReadingsTheWordsFirst、"
              "EnginesHandwritingReadingsTest.aCharacterPinyinLooksUpAfterWritingShowsOneReading、"
              "EnginesInkOverTest.pinyinsOwnCandidatesOfferPinningAfterWritingHandwritingsDoNot")(lambda d: None)

case("HW-52", needs="jvm:KeyHandwritingUatTest.aStrokeStartsInTheSplitGap")(lambda d: None)

case("HW-53", needs="jvm:KeyHandwritingUatTest.aFingerRestingThroughSeveralStrokesIsNoInk、KeyHandwritingUatTest.aThumbHoldingSpaceLetsTheLettersBeWrittenOn、"
              "KeyHandwritingUatTest.theThumbLiftedMidStrokeLeavesTheStrokeAlone、KeyHandwritingUatTest.aFingerDownAndUpWhileWritingIsNotHeard、"
              "GlideTouchTest.handwritingWithAThumbRestingEachDownOnTheLettersWritesAgain")(lambda d: None)
