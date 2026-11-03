"""uat/06-pinyin.md: the B0 cases. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import (Blocked, case, check, first, grouped_keys)


# ---------------------------------------------------------------- 打字

@case("PY-01", mutates="learn")
def py01(d):
    d.type("nihao")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"框里是「{t}」")
    return f"框里是「{t}」"


case("PY-02", needs="jvm:PinyinUatTest.aWholeSentence")(lambda d: None)


case("PY-03", needs="jvm:PinyinUatTest.theFirstHalfPickedTheRestStillOffered")(lambda d: None)


case("PY-04", needs="jvm:PinyinUatTest.anotherCandidateCommitsThatOne")(lambda d: None)


case("PY-05", needs="jvm:PinyinUatTest.backspaceDeletesTheLastLetter")(lambda d: None)


case("PY-06", needs="jvm:PinyinUatTest.anApostropheSeparatesSyllables")(lambda d: None)


case("PY-07", needs="jvm:PinyinUatTest.initials")(lambda d: None)


case("PY-10", needs="jvm:PinyinUatTest.theWordsBeforeChooseTheWord")(lambda d: None)


case("PY-11", needs="jvm:PinyinUatTest.aChatsBoxSentStillGivesTheWordsBefore")(lambda d: None)


# ---------------------------------------------------------------- 纠错

case("PY-14", needs="jvm:PinyinUatTest.pinyinTypedWrongStillGivesTheWord")(lambda d: None)


case("PY-18", needs="jvm:PinyinUatTest.aWordOftenMisreadShowsItsReading")(lambda d: None)


# ---------------------------------------------------------------- 模糊音、中英混输、笔画

case("PY-21", needs="jvm:PinyinUatTest.fuzzyOffByDefault")(lambda d: None)


case("PY-22", needs="jvm:PinyinUatTest.chineseAndEnglishMixed")(lambda d: None)


case("PY-24", needs="jvm:PinyinUatTest.uThenStrokes")(lambda d: None)


# ---------------------------------------------------------------- 联想

case("PY-26", needs="jvm:PinyinUatTest.wordsThatMayFollowAfterACommit")(lambda d: None)


case("PY-27", needs="jvm:PinyinUatTest.typingPutsTheWordsThatMayFollowAway")(lambda d: None)


# ---------------------------------------------------------------- 长按候选

case("PY-29", needs="jvm:CandidateMenuUatTest.blockingAWordSaysWhereAndAsksFirst")(lambda d: None)


case("PY-68", needs="jvm:CandidateMenuUatTest.aBlockIsTakenBackOnTheSpot")(lambda d: None)


case("PY-31", needs="jvm:PinyinUatTest.pinnedFirstThenUnpinned")(lambda d: None)


case("PY-32", needs="jvm:PinyinUatTest.forgettingWhatWasLearned")(lambda d: None)

case("PY-33", needs="jvm:KeyboardHalvesUatTest.theMenuListsOnlyWhatApplies、CandidateMenuUatTest.theMenuShowsTheRowsTheEngineGivesAndForgetOnceLearned")(lambda d: None)


# ---------------------------------------------------------------- 学词

SENTENCE, SENTENCE_PY, PIECES = "汪小明", "wangxiaoming", ("汪", "小明")


def learn_sentence(d, times=2):
    """PY-34's steps: 汪小明 picked piece by piece (the first is 王小明), twice:
    a correction made twice in the same place is first (U-02)"""
    c0 = None
    for _ in range(times):
        d.type(SENTENCE_PY)
        c = d.cands()
        c0 = c0 or c
        if first(c0) == SENTENCE:
            raise Blocked(f"整句首选已经是「{SENTENCE}」，前提要求首选是别的：{c0[:3]}")
        for w in PIECES:
            d.cands()
            d.pick(w)
        time.sleep(0.5)
        t = d.field_text()
        if t != SENTENCE:
            raise Blocked(f"一段段选完框里是「{t}」")
        d.clear()
    return c0


case("PY-34", needs="jvm:PinyinUatTest.aNamePickedPieceByPieceTwiceIsAWordAndInItsInitials")(lambda d: None)


case("PY-37", needs="jvm:PinyinUatTest.vulgarWordsAreNotOnTheFirstPage")(lambda d: None)


# ---------------------------------------------------------------- 改拼音

case("PY-39", needs="jvm:HostCasesUatTest.aLetterMissedIsTypedWhereTheRowIsTapped")(lambda d: None)


case("PY-40", needs="jvm:HostCasesUatTest.backspaceInTheMiddleOfThePinyin")(lambda d: None)


case("PY-41", needs="jvm:KeyboardUatTest.aSlowSpaceDragMovesThePinyinCursorAndTheRowFollowsIt")(lambda d: None)


case("PY-42", needs="jvm:HostCasesUatTest.aCharacterTakenHasNoCursorBeforeItAndBackspaceGivesItBack")(lambda d: None)


case("PY-43", needs="jvm:HostCasesUatTest.aStrokeMissedIsTypedWhereTheRowIsTapped")(lambda d: None)


# ---------------------------------------------------------------- 拆字、表情、自带短语

case("PY-44", needs="jvm:PinyinUatTest.chaiziByTheParts")(lambda d: None)


case("PY-45", needs="jvm:PinyinUatTest.notPartsArePinyinAsEver")(lambda d: None)


case("PY-46", needs="jvm:PinyinUatTest.anEmojiCandidateIsNotLearnedFirst")(lambda d: None)


case("PY-48", needs="jvm:PinyinUatTest.theDateTimeAndWeekdayBuiltIn")(lambda d: None)


case("PY-49", needs="jvm:PinyinUatTest.aBuiltInPhraseDeletedIsOneTurnedOffInTheCustomPhrases")(lambda d: None)


# ---------------------------------------------------------------- B0-设置 cases


case("PY-12", needs="jvm:PinyinUatTest.theLowLevelStillTypesASentence")(lambda d: None)


case("PY-19", needs="jvm:PinyinUatTest.eachSwitchTurnedOffTakesItsCandidates")(lambda d: None)


case("PY-20", needs="jvm:PinyinUatTest.fuzzyZZh")(lambda d: None)


case("PY-50", needs="jvm:PinyinUatTest.pinyinGlide、GlideUatTest.pinyinsGlideIsDrawnAsTheFingerGoes")(lambda d: None)


case("PY-52", needs="jvm:PinyinUatTest.theBuiltInDatesTurnedOff")(lambda d: None)


case("PY-64", needs="jvm:HostReplayUatTest.aSentenceTypedBeforeIsNotToldAfterTheAppsOwnCommits")(lambda d: None)


# ---------------------------------------------------------------- R11 learning (context, runs, pins)

case("PY-54", needs="jvm:PinyinUatTest.afterTheSameWordsPickedOnceIsSecondTwiceFirst")(lambda d: None)


case("PY-69", needs="jvm:CandidateMenuUatTest.whyItIsThereAndTheReportCopied")(lambda d: None)


case("PY-73", needs="jvm:PinyinUatTest.textAlreadyInTheFieldIsTheWordsBefore")(lambda d: None)


case("PY-72", needs="jvm:PinyinUatTest.aWordNoLongerOfferedAfterAnother、SettingsPagesUatTest.aPredictionTurnedOffListedUnderBlockedAndTakenBack")(lambda d: None)


case("PY-75", needs="jvm:HostCasesUatTest.wordsSentFromAChatBoxAreLearnedWithNothingBefore")(lambda d: None)


case("PY-09", needs="jvm:PinyinUatTest.aWordTypedTwiceInFullGoesThirdUnderItsInitials、aWordTypedTwiceInFullGoesThirdUnderPartsOfItToo、aWordUsedUnderTheInitialsStaysFirstThere")(lambda d: None)


case("PY-09b", needs="jvm:PinyinUatTest.aWordCorrectedToUnderTheInitialsIsNoLowerThanSecondInFullTillItExpires")(lambda d: None)


case("PY-09c", needs="jvm:PinyinUatTest.aPinGoesFirstAndTheLiftedWordAPlaceDown")(lambda d: None)


case("PY-09d", needs="jvm:PinyinUatTest.whyItIsHereTellsTheLiftAndTheFloor")(lambda d: None)


case("PY-77b", needs="jvm:PinyinUatTest.aWordGlidedTwiceGoesThirdUnderItsInitials")(lambda d: None)


# ---------------------------------------------------------------- 14 键 (B0-14键)


case("PY-84", needs="jvm:GroupedKeyboardUatTest.theFourteenKeysAsIFlytekLaysThemOut")(lambda d: None)


case("PY-85", needs="jvm:Keys14UatTest.atTheKeysCentresTheFirstIsTheModelsAndYourIsOnThePage")(lambda d: None)


case("PY-86", needs="jvm:GroupedKeyboardUatTest.theCodeRowsSyllablesBesideTheCodeTheirWidthsAndTheirCap、KeyboardHalvesUatTest.aSyllablePickedLeadsTheCodeAndBackspaceGivesItBack")(lambda d: None)


case("PY-87", needs="jvm:GroupedKeyboardUatTest.theSeparatorWithNothingTypedIsPairedQuotes、Keys14UatTest.theSeparatorWithNothingTypedIsAQuoteAndWhileTypingSplitsXian")(lambda d: None)


case("PY-88", needs="jvm:GroupedKeyboardUatTest.aSwipeDownTypesTheCornerAndWhileTypingPicksThatCandidate、KeyboardHalvesUatTest.aCornersDigitTypesItselfOrPicksThatCandidateWhileTyping")(lambda d: None)


case("PY-89", needs="jvm:GroupedKeyboardUatTest.theLongPressIsTheGroupsLettersAroundTheCornerWithTheLettersSymbolsAbove、KeyboardHalvesUatTest.aLetterOfTheLongPressWhileTypingFollowsTheFirstCandidate")(lambda d: None)


case("PY-90", needs="jvm:Keys14UatTest.theFourteenKeysLearnApartFromTheFullKeyboardAndYourWordsAreBoths")(lambda d: None)


case("PY-91", needs="jvm:GroupedKeyboardUatTest.theGlobeGoesToTheEnglishKeyboardAndBack、GroupedKeyboardUatTest.aPasswordOnTheFourteenKeysIsEnglishsFullKeyboard、GroupedKeyboardTest.theBottomRowHasNoChineseEnglishKeyAndTheLanguageSwitchFollowsItsSetting")(lambda d: None)


case("PY-92", needs="jvm:Keys14UatTest.keysWithNoReadingCommitAsTheirLetters")(lambda d: None)


case("PY-93", needs="jvm:GroupedKeyboardUatTest.anAddressFieldsDotComAndAtGoInAsTheyAre、KeyboardHalvesUatTest.inAnAddressFieldTheKeysGoInAsLettersBeforeDotComAndAt、GroupedKeyboardTest.theSpaceIsAtLeast15PercentOfTheRowInEveryField")(lambda d: None)


case("PY-94", needs="jvm:SettingsPagesUatTest.aPinOnTheFourteenKeysSaysSoAndShowsItsLetters、WordsUatTest.aPinOnTheFourteenKeysIsListedUnderItsKeys")(lambda d: None)


case("PY-95", needs="jvm:Keys14UatTest.theBuiltInDatesUnderRqAndRiqi")(lambda d: None)


@case("PY-96", base="B0-14键")
def py96(d):
    s = d.screen()
    qw = d.key_node("qw", s)
    sep = s.find(desc="分词", where="ime")
    grouped_keys(d, "bn ui")
    d.cands()
    s = d.screen()
    strip = s.find(rid="code_row_syllables", where="ime")
    ni = next((n for n in strip.walk() if n.text == "ni"), None) if strip else None
    check(qw and qw.desc == "Q W，1", f"QW 的说明「{qw and qw.desc}」")
    check(sep is not None, "分词键没有「分词」的说明")
    check(ni and (ni.desc == "拼音 ni" or ni.row().desc == "拼音 ni"), f"ni 的说明「{ni and ni.desc}」")
    check(ni and "Button" in (ni.cls + ni.row().cls), f"ni 不是按钮：{ni and ni.cls}")
    return "QW、分词、ni 的说明都对"


case("PY-97", needs="jvm:Keys14UatTest.syllablesTakenGoBackLastFirstAndATapInTheCodeOnlyMovesTheCursor")(lambda d: None)


case("PY-98", needs="jvm:GroupedKeyboardUatTest.theFourteenKeysDoNotGlide")(lambda d: None)


case("PY-100", needs="jvm:KeyboardHalvesUatTest.tappedOnTheLettersOfNihaoTheStripStartsWithNi")(lambda d: None)


case("PY-101", needs="jvm:KeyboardHalvesUatTest.tappedOnTheLettersOfBuhaoTheStripStartsWithBu")(lambda d: None)


case("PY-102", needs="jvm:Keys14UatTest.whereTheKeysWereTappedIsNotLearnedAndQuickInputGetsTheLetter")(lambda d: None)


case("PY-103", needs="jvm:StrokeFilterUatTest.xiThenFourStrokesOfXi")(lambda d: None)


case("PY-104", needs="jvm:StrokeFilterUatTest.haoThenADotThenBackspace")(lambda d: None)


case("PY-105", needs="jvm:ImKeyboardsUatTest.pinyinsEighteenKeysShowTheGroupsAndSendTheirOwnCodes、GroupedKeyboardTest.talkBackReadsAGroupLetterByLetterWithItsCorner")(lambda d: None)


case("PY-106", needs="jvm:PhraseUatTest.aWholeSentenceTypedThreeTimesGoesOnAfterItsStart、PhraseUatTest.aPhraseTurnedOffOrDeletedIsNoLongerOffered")(lambda d: None)


case("PY-107", needs="jvm:PhraseUatTest.piecesCommittedApartAreNoPhrase")(lambda d: None)


case("PY-108", needs="jvm:PhraseUatTest.noPhraseWhereNothingIsLearnedAndOneInATable")(lambda d: None)


@case("PY-109", base="B0-注音全键盘", mutates="learn")
def py109(d):
    k = d.keys(refresh=True)
    kb = d.need_keyboard()
    top = sorted((n for n in kb.walk() if n.clickable and n.texts() and n.c[1] < min(m.c[1] for m in kb.walk() if m.clickable) + 20), key=lambda n: n.c[0])
    row = "".join(n.texts()[0] for n in top)
    check(row == "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ", f"第一行 {row}")
    d.taps([k[x] for x in "ㄋㄧˇㄏㄠˇ"], gap=0.3, after=0.6)
    c = d.cands()
    code = d.preedit()
    check(code == "ㄋㄧˇ ㄏㄠˇ" and first(c) == "你好", f"码行「{code}」，候选 {c[:4]}")
    d.pick("你好")
    d.taps([k["ㄇ"], k["ㄚ"]], gap=0.3, after=0.3)
    d.space()
    c = d.cands()
    toned = d.preedit()
    check(toned == "ㄇㄚˉ" and first(c) == "妈", f"空格后码行「{toned}」，候选 {c[:4]}")
    d.space()
    d.taps([k[x] for x in "ㄉㄜ˙"], gap=0.3, after=0.6)
    c = d.cands()
    check(first(c) == "的", f"ㄉㄜ˙ 的候选 {c[:4]}")
    d.space()
    d.taps([k["ㄅ"], k["ˇ"]], gap=0.3, after=0.4)
    d.enter()
    got = d.expect_field("你好妈的ㄅˇ")
    return f"码行 {code}、{toned}，框里「{got}」"


case("PY-110", needs="jvm:PinyinUatTest.aReadingIsInItsCharactersOwnTones、ToneTableTest.aWordsNeutralToneIsItsCharactersOwn")(lambda d: None)


case("PY-111", needs="jvm:CandidateHintsUatTest、EnginesReadingsTest.offShowsNoneButTheHintsAboveTheCandidates、CandidateReadingsTest.aHintIsOneReadingACharacterWithItsTones、CandidateBarHeightTest.aHintsReadingFillsTheRoomBetweenItsBounds")(lambda d: None)


case("PY-112", needs="jvm:ZhuyinShortcutsUatTest.dachensKeysTakeTheFullKeyboardsShortcutsByPlace、ZhuyinKeyboardTest.theSymbolKeysAreNamedForTheirShortcuts")(lambda d: None)
