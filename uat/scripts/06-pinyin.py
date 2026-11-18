"""uat/06-pinyin.md: the B0 cases. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import (PKG, Blocked, block_candidate, case, check, choose, first, grouped_keys, keys_at, open_url, script)


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
case("PY-134", needs="jvm:BarUatTest.theCrossWithThePredictionsDropsThem")(lambda d: None)


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


case("PY-50", needs="jvm:PinyinUatTest.pinyinGlide、GlideUatTest.pinyinsGlideIsDrawnAsTheFingerGoes、GlideSessionTest.readingsAndLookupsAreTheSessionsShiftedPastTheOthers")(lambda d: None)


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


# ---------------------------------------------------------------- 导入的分组键盘 (061-custom-layout L4)


def import_grouped(d, name, title):
    """uat/fixtures/layouts/<name>.layout imported through 外观 → 布局 (14-settings' helpers); its 导入 tapped,
    the 现在就用在 question that follows left open; the confirm dialog's texts returned"""
    st = script("14-settings")
    st.push_layout(d, name)
    st.import_layout(d, name)
    check(d.wait_text(f"导入布局 {title}？", timeout=8, where="all"), f"没出导入确认框：{d.screen().texts('all')[:10]}")
    told = d.screen().texts("all")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    return told


@case("PY-153", mutates=True, visual=True)
def py153(d):
    told = import_grouped(d, "uat-k17-1.0", "UAT 17 键")
    held = [t for t in told if "是 18 键的键" in t]
    check(not held, f"确认框提醒了 App 的键：{held}")
    check(d.wait_text("现在就用在 拼音 上吗？", timeout=6, where="all"), f"没只问拼音：{d.screen().texts('all')[:10]}")
    d.tap_text("用", where="all", scroll=False, after=2.0)
    d.open_page("text")
    k = d.keys(refresh=True)
    check("hjk" in k and "jk" not in k and "h" not in k, f"键盘上不是 17 键（有 HJK，没有 H、JK）：{sorted(k)}")
    d.shot("ime", "拼音用导入的 17 键：第 2 行 A SD FG HJK L")
    # each letter where it sits on its key, as a user aims (按落点加权: 061-pending-decisions L4): n i h a o
    grouped_keys(d, "bn io hjk a io", at=[0.75, 0.25, 1 / 6, 0.5, 0.75])
    c = d.cands()
    check(c[:1] == ["你好"], f"BN IO HJK A IO（点在 n i h a o 那边）的候选 {c[:8]}")
    d.pick("你好")
    text = d.field_text()
    d.clear()
    check(text == "你好", f"框里是 {text!r}")
    # HJK a key of its own name: its long press its own letters', not the app's H's (L6 verifier R-S1/R-N1)
    st = script("14-settings")
    st.slots_page(d)
    st.slots_tab(d, "UAT 17 键")
    st.key_dialog(d, "HJK")
    st.dialog_card(d, "长按菜单")
    items = [n.desc for n in d.screen().app() if n.clickable and n.desc and len(n.desc) == 1]
    d.shot("full", "按键字符和快捷键的 UAT 17 键：HJK 的长按菜单有 h j k")
    d.tap_text("取消", where="all", scroll=False, after=1.0)
    check(all(x in items for x in "hjk"), f"HJK 的长按菜单没有 h、j、k：{items}")
    return f"只问拼音、没提醒；17 键画出来；候选 {c[:3]}；点「你好」上屏；HJK 长按 {items}"


case("PY-154", needs="jvm:NineKeyLettersCustomTest.theBuiltInGroupsInAnotherOrderAreThatKeyboardAndItsRecord、KeyboardCustomTest.theBuiltInGroupsRearrangedAreTheBuiltInKeyboardOnEitherInputMethod、ImKeyboardsTest.aLayoutOfOnesOwnIsShownAsTheBuiltInOneItIsTypedAs、GroupedKeyboardOwnGroupsTest.theEighteenKeysRearrangedAreDrawnAsWrittenAndTypedAsTheEighteenKeys")(lambda d: None)


case("PY-155", needs="jvm:OwnKeysTurnTest.otherGroupsWaitForTheirFirstCodeAndCommitThen、OwnKeysTurnChainTest.anotherGroupingShownWhileTypingTakesOverAtItsFirstKey、KotlinHostOwnKeysTest.otherGroupsWhileTypingCommitTheCodeAtTheirFirstCode、KotlinHostOwnKeysTest.aBackspaceAfterOtherGroupsAreToldTakesFromTheCodeTyped")(lambda d: None)


case("PY-156", needs="manual:导入 uat-k17-tall-1.0.layout 用在拼音上，FG、BN 点在靠 HJK 的边上和正中各打一次 a 看候选；"
                     "NeighbourSlipTest.theKeysAroundAKeyAreThoseBesideItAndThoseAboveAndBelowOverlappingIt 只查几何")(lambda d: None)


# ---------------------------------------------------------------- 14 键 (B0-14键)


case("PY-84", needs="jvm:GroupedKeyboardUatTest.theFourteenKeysAsIFlytekLaysThemOut")(lambda d: None)


case("PY-85", needs="jvm:Keys14UatTest.atTheKeysCentresTheFirstIsTheModelsAndYourIsOnThePage")(lambda d: None)


case("PY-86", needs="jvm:GroupedKeyboardUatTest.theCodeRowsSyllablesBesideTheCodeTheirWidthsAndTheirCap、KeyboardHalvesUatTest.aSyllablePickedLeadsTheCodeAndBackspaceGivesItBack")(lambda d: None)


case("PY-87", needs="jvm:GroupedKeyboardUatTest.theSeparatorWithNothingTypedIsPairedQuotes、Keys14UatTest.theSeparatorWithNothingTypedIsAQuoteAndWhileTypingSplitsXian")(lambda d: None)


case("PY-88", needs="jvm:GroupedKeyboardUatTest.aSwipeUpTypesTheCornerAndWhileTypingPicksThatCandidate、KeyboardHalvesUatTest.aCornersDigitTypesItselfOrPicksThatCandidateWhileTyping")(lambda d: None)


case("PY-89", needs="jvm:GroupedKeyboardUatTest.aLooseKeyAtTheLeftReachesItsFirstLetterAtTheEdge、GroupedKeyboardUatTest.theLongPressIsTheGroupsLettersAroundTheCornerWithTheLettersSymbolsAbove、KeyboardHalvesUatTest.aLetterOfTheLongPressWhileTypingFollowsTheFirstCandidate")(lambda d: None)


def edge_hold(d, xy, dx, note=None):
    """a long press on xy, the popup shot (note), the finger moved dx px across and lifted: the text added"""
    before = d.field_text()
    x, y = xy
    d.touch(("down", x, y), ("sleep", 0.9), ("move", x, y))
    if note:
        d.shot("kb", note)
    steps = [("move", x + dx * i // 4, y) for i in range(1, 5)]
    d.touch(*steps, ("up", x + dx, y))
    time.sleep(0.5)
    return d.field_text()[len(before):]


@case("PY-89b", base="B0-14键", visual=True)
def py89b(d):
    k = d.keys(refresh=True)
    p = d.dpx()
    # the keyboard's right edge, as rotated: the screen's (it spans it)
    width = d.need_keyboard().b[2]
    left = edge_hold(d, k["l"], -22 * p, "长按最右的 L：l L 0，角标在最右")
    edge = edge_hold(d, k["l"], width - 1 - k["l"][0])
    as_ = edge_hold(d, k["as"], 22 * p, None)
    check(left == "L", f"L 往左一挪打出 {left!r}")
    check(edge == "0", f"L 往右到屏幕边打出 {edge!r}（应是角标 0）")
    check(as_ == "A", f"AS 往右一挪打出 {as_!r}")
    return f"L←{left} L→边{edge} AS→{as_}"


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
case("PY-135", needs="jvm:StrokeFilterUatTest.keyiThenAHengPutsTwoCharactersAloneRightAfterTheFirst")(lambda d: None)
case("PY-136", needs="jvm:StrokeFilterTest.aMovedCandidatesOffersPinBlockReasonAndCardAreOfItsOwnPlace、StrokeFilterTest.theOrderIsMadeAgainAtEachStrokeAndBackspaceAndAPageTurnGoesOnFromIt")(lambda d: None)


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


case("PY-111", needs="jvm:CandidateHintsUatTest、EnginesReadingsTest.offShowsNoneButTheHintsAboveTheCandidates、CandidateReadingsTest.aHintIsOneReadingACharacterWithItsTones、CandidateBarHeightTest.aHintsReadingFillsTheRoomBetweenItsBounds、EnginesReadingsTest.aPolyphoneFoundByItsPartsOrStrokesIsReadOnceAbove、CandidateReadingsTest.aHintOfACharactersSeveralReadingsIsTheOneItHasThere、PolyphoneReadingUatTest.aPolyphoneIsReadOnceAboveAsItsWordHasIt、GridReadingWidthTest.aWordWithReadingsIsMeasuredAsItIsDrawnInEachStyle")(lambda d: None)


@case("PY-118", base="B0-设置", mutates=True, visual=True)
def py118(d):
    """the readings are drawn, not in the bar's accessibility: the machine checks the characters are
    there, the shots show each polyphone's one reading above it and nothing beside it"""
    d.goto("通用输入")
    choose(d, "候选词注音", "拼音")
    d.open_page("text")
    seen = []
    # uz: the characters whose first stroke is 折, as in the user's screenshot (也 对 好 了 能)
    for keys, chars, note in (("uz", ("了", "能"), "了 上方 le、能 上方 néng，和 也 yě、好 hǎo 一样；右边没有 le/liao、neng/nai"),
                              ("uchichu", ("行",), "拆出的 行 上方 xíng，右边没有 xing/hang")):
        d.type(keys)
        c = d.cands(want=chars[-1])
        for char in chars:
            check(char in c, f"{keys}：候选里没有「{char}」：{c[:8]}")
        d.shot("kb", note)
        seen += chars
        d.clear()
    d.type("yinuchichu")
    d.cands()
    d.pick("银")   # past the bar's sentences (一怒斥出…): pick looks through the expanded list too
    c = d.cands(want="行")
    check("行" in c, f"选了银以后候选里没有「行」：{c[:8]}")
    d.shot("kb", "银 之后的 行 上方 háng")
    return "、".join(seen) + "、银+行 都在候选里，读音看图"


case("PY-112", needs="jvm:ZhuyinShortcutsUatTest.dachensKeysTakeTheFullKeyboardsShortcutsByPlace、ZhuyinKeyboardTest.theSymbolKeysAreNamedForTheirShortcuts")(lambda d: None)


case("PY-113", needs="jvm:EnginesReadingsTest.eachCandidateIsReadAsTyped、EnginesReadingsTest.aCharacterOfTwoReadingsIsReadAsTypedThroughFullPinyinsWrappers、StrokeFilterTest.readingsAndLookupsOfAKeptCandidateAreOfItsOwnPlace")(lambda d: None)


@case("PY-114", mutates=True)   # a word blocked stays blocked: the next case needs a reset
def py114(d):
    d.type("azhi")
    c = d.cands()
    check("啊之" in c, f"a z h i 的候选 {c[:8]}")
    block_candidate(d, "啊之", "长按「啊之」的菜单")
    d.clear()
    d.type("azhi")
    again = d.cands()
    check("啊之" not in again, f"屏蔽后 a z h i 还有「啊之」：{again[:8]}")
    d.clear()
    d.type("a")
    a = d.cands()
    d.clear()
    d.type("zhi")
    z = d.cands()
    d.clear()
    check("啊" in a and "之" in z, f"a {a[:6]}，zhi {z[:6]}")
    return f"屏蔽前 {c[:4]}，屏蔽后 {again[:4]}；a 有「啊」、zhi 有「之」（设置里恢复由 JVM 管）"


@case("PY-115", mutates=True)
def py115(d):
    d.type("dianzan")
    c = d.cands()
    emoji = "👍"
    if emoji not in c:
        raise Blocked(f"dianzan 的候选里没有 👍：{c[:8]}")
    block_candidate(d, emoji, f"长按表情「{emoji}」的菜单")
    d.clear()
    d.type("dianzan")
    after = d.cands()
    check(emoji not in after, f"屏蔽后 dianzan 还有「{emoji}」：{after[:8]}")
    d.clear()
    # review H1: kept over a restart of the input method (the line read back from words.blocked)
    restart_ime(d)
    d.type("dianzan")
    restarted = d.cands(timeout=30)
    check(emoji not in restarted, f"重启输入法后 dianzan 又有「{emoji}」：{restarted[:8]}")
    d.clear()
    d.type("uhs")
    u = d.cands()
    check(d.preedit().startswith("u"), f"打了 uhs，编码行是「{d.preedit()}」")
    check(u, "打了 uhs 没有候选")
    ch = u[0].split()[0]
    menu = block_candidate(d, u[0], f"长按笔画查字「{ch}」的菜单")
    check(any("这串码" in t for t in menu), "笔画查字的确认没写「打这串码不再出现」")
    d.clear()
    d.type("uhs")
    u2 = [x.split()[0] for x in d.cands() if x]
    d.clear()
    check(ch not in u2, f"屏蔽后 uhs 还有「{ch}」：{u2[:8]}")
    # a polyphone by its parts (彳亍 行, shown xíng): blocked in that reading only (A5)
    d.type("uchichu")
    c = d.cands(want="行")
    check("行" in c, f"uchichu 的候选里没有「行」：{c[:8]}")
    block_candidate(d, "行", "长按拆字出的多音字「行」的菜单")
    d.clear()
    left = {}
    for keys in ("uchichu", "xing", "hang"):
        d.type(keys)
        left[keys] = [x.split()[0] for x in d.cands() if x]
        d.clear()
    check("行" not in left["uchichu"], f"屏蔽后 uchichu 还有「行」：{left['uchichu'][:8]}")
    check("行" not in left["xing"], f"屏蔽后 xing 还有「行」：{left['xing'][:8]}")
    check("行" in left["hang"], f"屏蔽 xíng 后 hang 没有「行」：{left['hang'][:8]}")
    return f"表情「{emoji}」屏蔽后 dianzan {after[:4]}，重启输入法后 {restarted[:4]}；uhs「{ch}」屏蔽后 {u2[:4]}；拆字「行」屏蔽后 xing {left['xing'][:3]}、hang {left['hang'][:3]}（单音字拆字、滑行的其他读法由 JVM 管）"


# the input method's process killed and brought back, as a phone's restart would: what was learned read from its files
def restart_ime(d):
    pid = d.sh(f"pidof {PKG}").strip()
    if not pid:
        raise Blocked("输入法进程没在跑")
    d.root(f"kill -9 {pid}")
    time.sleep(1.0)
    open_url(d, "text")
    for _ in range(30):
        if d.screen().find(rid="keyboard_view", where="ime") and "不可用" not in d.im_label():
            return
        time.sleep(1.0)
    raise Blocked("重启后键盘没起来")


@case("PY-116", mutates=True)
def py116(d):
    tb = script("05-toolbar")
    # the keyboard up, the bar showing its tools: nothing typed
    d.type("a")
    d.clear()
    trad = tb.status_toggle(d, "简体")
    try:
        d.type("guojia")
        c = d.cands(timeout=30)
        check("國家" in c, f"繁體下 guojia 的候选 {c[:6]}")
        menu = block_candidate(d, "國家", "繁體下长按「國家」的菜单")
        check(any("屏蔽" in t for t in menu), f"繁體候选的菜单里没有屏蔽：{menu}")
        d.clear()
        d.type("guojia")
        after = d.cands()
        d.clear()
        # the list may have two that show as 國家 (国家, and the dictionary's own traditional 國家): both go
        check("國家" not in after, f"屏蔽后繁體下 guojia 还出「國家」：{c[:6]} → {after[:6]}")
    finally:
        d.clear()
        tb.status_toggle(d, trad)
    d.type("guojia")
    simp = d.cands()
    d.clear()
    check("国家" not in simp, f"屏蔽「國家」后简体下 guojia 还有「国家」：{simp[:6]}")
    return f"切到「{trad}」：长按「國家」菜单 {menu}；屏蔽后繁體 {after[:4]}，简体 {simp[:4]}"


case("PY-117", needs="jvm:WordListsTest.aBlockedLatinWordReadsBackAsItsLetters、EnginesUserWordsTest.aLatinWordBlockedInPinyinStaysBlockedAfterARestartTillRestored")(lambda d: None)
case("PY-119", needs="jvm:FastTypingTest.fourteenKeysTwoFingersOnOneKeyTypeEachItsHalf")(lambda d: None)
case("PY-120", needs="jvm:EnginesStrokeLookupChainTest.withoutAKeyPassedOnWhatWasCommittedIsTheContext、"
     "EnginesStrokeLookupChainTest.enterPassedOnToTheAppDropsWhatWasCommittedBefore")(lambda d: None)


@case("PY-121", base="B0-设置", mutates=True, visual=True)
def py121(d):
    """the readings are drawn, not in the bar's accessibility: the machine finds the rare characters of
    chun in the expanded list, the shots show their toneless chun (none in zhuyin) and each row's
    characters on one line; and the floating window's emoji and word in English beside candidates read
    (its JVM test lays out a Han character alone, R25r5 F8001)"""
    rare = ("䏝", "䏰", "䄝")
    d.goto("通用输入")
    choose(d, "候选词注音", "拼音")
    d.open_page("text")
    found = []
    for style in ("拼音", "注音符号"):
        if style == "注音符号":
            d.clear()
            # the test page is in front: settings first, or goto finds no 通用输入 (R25 device pass)
            d.open_settings()
            d.goto("通用输入")
            choose(d, "候选词注音", "注音符号")
            d.open_page("text")
        d.type("chun")
        d.cands()
        b = d.screen().find(desc="展开候选词列表", where="ime")
        if not b:
            raise Blocked("chun 的候选栏没有「展开候选词列表」")
        d.tap(b.c, 0.6)
        found = []
        for _ in range(15):
            s = d.screen()
            grid = s.find(rid="expanded_candidate_view", where="ime")
            texts = [n.text.split(" ", 1)[0] for n in (grid.walk() if grid else []) if n.cls == "TextView" and n.text]
            found = [c for c in rare if c in texts]
            if found:
                break
            nxt = s.find(desc="更多候选词", where="ime")
            if not nxt or not nxt.enabled:
                break
            d.tap(nxt.c, 0.5)
        check(found, f"{style}：chun 的展开候选里没翻到 {'、'.join(rare)}")
        d.shot("kb", f"{style}：{'、'.join(found)} " + ("上方是不带声调的 chun，别的字带调" if style == "拼音" else "不标注音")
               + "；每一行的字底边在一条线上")
        if style == "拼音":
            d.clear()
            d.type("haha")
            d.cands()
            d.shot("kb", "拼音：表情这种没注音的候选和有注音的字底边对齐")
            d.clear()
            # the window by the cursor, on the emulator's USB keyboard (the next case's baseline unplugs it
            # if this one stops before the down); a shot of the top half, where the test page's field is
            d.kb("up")
            try:
                time.sleep(3)
                for keys, what in (("haha", "表情"), ("iphone", "「iPhone」这种英文")):
                    d.kb("type", keys)
                    time.sleep(1)
                    d.shot("top", f"拼音、悬浮候选窗：{what}和有注音的候选一样高、底边对齐")
                    d.kb("key", "ESC")
                    time.sleep(0.5)
            finally:
                d.kb("down")
                time.sleep(3)
    return f"翻到 {'、'.join(found)}，读音和对齐看图"


case("PY-121a", needs="manual:youmo29（API 29）上测试页打不开（WebView shell 要 API 34+）：在输入测试台 types 页的 text 框接 emu-keyboard.sh 的实体键盘手工跑，看悬浮候选窗的图")(lambda d: None)


@case("PY-122", mutates=True)
def py122(d):
    lc = script("18-lifecycle")
    lc.testbed_in_field(d, "types", "text")
    d.type("nihao")
    d.cands()
    f = next(x for x in d.screen().app() if x.rid == "text" and x.pkg == lc.TESTBED)
    # inside 「ni hao」: taps from the field's left edge on till the field's cursor is in the code, not at its ends
    at = None
    for i in range(1, 30):
        d.tap((f.b[0] + i * int(6 * d.dpx()), (f.b[1] + f.b[3]) // 2), 0.6)
        st = lc.tb(d, "text")
        if 0 < st.get("selStart", 0) < 6:
            at = st["selStart"]
            break
    if at is None:
        raise Blocked("点不到编码里面")
    st = lc.tb(d, "text")
    d.type("a")
    d.cands()
    st1 = lc.tb(d, "text")
    d.space()
    time.sleep(0.6)
    t2 = lc.tb(d, "text").get("text")
    check(st.get("text") == "ni hao" and (st.get("compStart"), st.get("compEnd")) == (0, 6), f"点进编码后框「{st.get('text')}」composing {st.get('compStart')}–{st.get('compEnd')}")
    t1 = st1.get("text") or ""
    check(t1.replace(" ", "") == ("nihao"[:sum(c != " " for c in "ni hao"[:at])] + "a" + "nihao"[sum(c != " " for c in "ni hao"[:at]):]), f"在第 {at} 位点 a 后框「{t1}」")
    check(t2 and not any(c.isascii() and c.isalpha() for c in t2), f"空格后框里「{t2}」")
    return f"光标点到第 {at} 位，「{t1}」→「{t2}」"


case("PY-123", needs="jvm:CodeInFieldUatTest.aSelectionMadeInTheCodeLeavesItAsTextAndTheEngineReset")(lambda d: None)
case("PY-125", needs="jvm:EnginesWordCardTest、CharCardTest.theReadingACandidateHasItInFirst、WordCardUatTest.theExamplesReadAsTheDesignHasThem")(lambda d: None)
case("PY-126", needs="jvm:CandidateMenuUatTest.aHeldCharacterOpensToItsFullCard、EnginesWordCardTest")(lambda d: None)
case("PY-127", needs="jvm:EnginesWordCardTest、WordCardUatTest.theCardReadsAsTheBarOnTheChatWordsAndTheMisreadings")(lambda d: None)
case("PY-128", needs="jvm:WordCardTest、EnginesWordCardTest、CandidateMenuUatTest.noReadingLineWhereTheEngineHasNoCard")(lambda d: None)
case("PY-129", needs="jvm:EnginesWordCardTest.aPredictionHasItsLineAsTheBarReadsIt、CandidateMenuUatTest.aPredictionHasItsReadingLineOverItsLines")(lambda d: None)
case("PY-130", needs="jvm:EnginesWordCardTest、WordCardUatTest.theExamplesReadAsTheDesignHasThem")(lambda d: None)
case("PY-131", needs="jvm:EnginesWordCardTest.traditionalOutputHasItsCardItsCharactersAsShown、EnginesHandwritingReadingsTest.traditionalOutputsWrittenCharacterHasItsCardAsShown")(lambda d: None)
case("PY-132", needs="jvm:CandidateMenuUatTest.withTheCodeInTheFieldTheCardLeavesItAsItIs")(lambda d: None)
case("PY-133", needs="jvm:CandidateMenuUatTest.aLongCandidateHasItsReadingAloneWrapped、EnginesWordCardTest、CardNavTest")(lambda d: None)


@case("PY-124", base="B0", visual=True)
def py124(d):
    """the reading line over the menu, the word card it opens, a character's card and the way back
    (061-word-card.md); the views' children are hidden from accessibility, so each step is found by
    its description, as TalkBack reads it"""
    d.open_page("text")
    d.type("yinhang")
    menu = d.long_pick("银行")
    d.shot("full", "长按「银行」：最上面一行是读音")
    s = d.screen()
    line = s.find(desc="银行，yín háng", where="all")
    check(line is not None, f"没有读音行：{[t for t in menu if len(t) < 16][:12]}")
    check(all(x in menu for x in ("屏蔽", "固定首位")), f"菜单项变了：{[t for t in menu if len(t) < 16][:12]}")
    d.tap(line.c, after=0.8)
    s = d.screen()
    d.shot("full", "点读音行：词卡")
    check(s.find(desc="银行，读音 yín háng", where="all") is not None, "点开后没有词卡")
    row = next((n for n in s.nodes if n.desc.startswith("行，háng")), None)
    check(row is not None, f"词卡里没有「行」那一行：{[n.desc for n in s.nodes if n.desc][:12]}")
    d.tap(row.c, after=0.8)
    s = d.screen()
    d.shot("full", "点「行」：字卡，返回行，只有复制")
    back = s.find(desc="返回 银行", where="all")
    check(back is not None, "字卡上没有返回行")
    texts = s.texts(where="all")
    check("复制「行」" in texts and "上屏" not in texts, f"字卡的按钮：{[t for t in texts if len(t) < 8][:12]}")
    d.tap(back.c, after=0.8)
    s = d.screen()
    check(s.find(desc="银行，读音 yín háng", where="all") is not None, "返回后不是词卡")
    d.tap_text("上屏", where="all", scroll=False, after=0.8)
    field = d.field_text()
    check(field == "银行", f"上屏后框里「{field}」")
    return "读音行、词卡、字卡、返回、上屏"


# ---------------------------------------------------------------- 按偏的键按落点读 (061-neighbour-correction)


@case("PY-137")
def py137(d):
    # the same nihso twice, only where s is tapped differing: at a fifth of its width, toward a (hso spells nothing),
    # read as nihao, the code row the spelling read; in the middle, no a read (全拼's floor), as typed. A place lost
    # (read as no place) reads nihao both times, so the middle's tells it reached the engine (review R26 F8433)
    keys_at(d, "nihso", [None, None, None, (0.2, 0.5), None])
    slip = d.cands()
    code = d.preedit()
    d.shot("kb", "s 点在靠 a 的边上打 nihso：首选「你好」，编码行 ni hao")
    d.clear()
    keys_at(d, "nihso", [None] * 5)
    mid = d.cands()
    mid_code = d.preedit()
    d.shot("kb", "nihso 每个键点在正中：编码行没有 hao（如 ni h so），首选不是「你好」")
    d.clear()
    check(slip[:1] == ["你好"] and code == "ni hao", f"s 靠 a 点的 nihso：首选 {slip[:4]}，编码行「{code}」")
    # how the unread hso is split in the code row is the engine's to choose (no JVM test holds it): only no hao is checked
    check("hao" not in mid_code and first(mid) != "你好", f"正中点的 nihso：编码行「{mid_code}」，候选 {mid[:6]}")
    return f"靠边 {slip[:1]}「{code}」；正中 {mid[:3]}「{mid_code}」"


case("PY-138", needs="jvm:NeighbourSlipTest.consonantsAloneReadAsTypedWhereTappedInTheMiddle")(lambda d: None)


case("PY-139", needs="jvm:PinyinSessionSlipTest.withNoPlaceAWordReadThroughASlipIsLearnedUnderTheKeyMeantToo、NeighbourSlipTest.withNoPlaceALegalSlipIsNotRead、PinyinUatTest.pinyinTypedWrongStillGivesTheWord")(lambda d: None)


case("PY-140", needs="jvm:PinyinSessionSlipTest.aLegalSlipPickedIsLearnedUnderTheKeysMeantNotThoseTyped、PinyinSessionSlipTest.aLegalSlipPickedRaisesNothingUnderTheKeysTyped")(lambda d: None)


case("PY-141", needs="jvm:NeighbourSlipTest.theFourteenKeysReadAKeyAsTheOneNextToItAndWeighItsLetters、PinyinSessionSlipTest.onTheFourteenKeysTheKeyMeantIsLearnedAsItsCode、GroupedKeyboardTapTest.aTapCarriesWhereItFellAndTheKeysAroundIt")(lambda d: None)


case("PY-142", needs="jvm:NeighbourSlipTest.theEighteenKeysReadAKeyAsTheOneNextToIt、GroupedKeyboardTapTest.everyGroupedKeyboardsTapCarriesTheKeysAroundItAsTheEngineReadsThem、NeighbourTouchTest.aGroupKeysTapGoesWithTheGroupsAroundItAsTheEngineReadsThem")(lambda d: None)


case("PY-150", needs="jvm:NeighbourSlipTest.dachenReadsASymbolAsTheOneNextToIt、PinyinSessionSlipTest.onDachenTheSymbolMeantIsLearnedAsItsCode、EnginesTest.shuangpinsEighteenKeysAndDachenReadASlipUnlessSlipsAreOff、NeighbourTouchTest.dachensSymbolKeysGoWithTheSymbolKeysAround")(lambda d: None)
case("PY-151", needs="jvm:EnginesZhuyinSlipTest.zhuyinsNineKeysReadASlipUnlessSlipsAreOff、NeighbourSlipTest.zhuyinsNineKeysReadAGroupAsTheOneNextToIt、NeighbourTouchTest.zhuyinsNineGroupKeysGoWithTheGroupKeysAround")(lambda d: None)


case("PY-152", needs="jvm:GroupedKeyboard18Test.pinyinsTapGoesWithWhereItFellAndShuangpinsIsTheCodeAlone、EnginesImKeyboardsTest.pinyinsEighteenKeysTapsGoWithWhereTheyFell、KeyboardTapsTest.aPinyinEighteenKeysTapComesBackAsItsG18CodeAndPlace、NeighbourSlipTest.theEighteenKeysWeighTheLettersOfTheKeySlippedToByWhereTheTapFell")(lambda d: None)


@case("PY-144", base="B0", visual=True)
def py144(d):
    """a word card's character and the way back change the card in the same window (FB4: it went blank
    and grew again from the candidate on each tap): its left, right and the edge on the candidate bar where
    they were, the other edge alone following the card, never over the bar; upright (over the bar) and turned
    (under it); the frames themselves are the JVM test's and the recordings'"""
    upright = py144_steps(d, "竖屏")
    d.rotate(1)
    try:
        turned = py144_steps(d, "横屏")
    finally:
        d.rotate(0)
    return f"竖屏 {upright}；横屏 {turned}"


def py144_steps(d, how):
    d.open_page("text")
    d.type("yinhang")
    d.long_pick("银行")
    line = d.screen().find(desc="银行，yín háng", where="all")
    check(line is not None, f"{how}：没有读音行")
    d.tap(line.c, after=0.8)

    # the window's left, right and the edge on the candidate bar, and its side of the bar: the lines under the
    # card scroll with it where the card is taller than the room (a learned word's four lines under 行's card,
    # seen; turned, off the window), so the window is found from the card's head
    def placed(s, what):
        head = next((n for n in s.nodes if n.desc.startswith(("银行，读音", "返回 银行"))), None)
        check(head is not None, f"{how}{what}：弹窗里没有卡")
        root = head
        while root.parent is not None:
            root = root.parent
        bar = s.find(rid="candidate_view", where="ime")
        check(bar is not None, f"{how}{what}：没有候选栏")
        over = root.b[3] <= bar.b[1]
        check(over or root.b[1] >= bar.b[3],
              f"{how}{what}：弹窗压在候选栏上（弹窗 {root.b}，候选栏 {bar.b}）")
        return root.b[0], root.b[2], ("上", root.b[3]) if over else ("下", root.b[1])

    s = d.screen()
    word = placed(s, "词卡")
    row = next((n for n in s.nodes if n.desc.startswith("行，háng")), None)
    check(row is not None, f"{how}：词卡里没有「行」那一行")
    d.tap(row.c, after=0.8)
    s = d.screen()
    check(s.find(desc="返回 银行", where="all") is not None, f"{how}：点「行」后不是字卡")
    char = placed(s, "字卡")
    d.shot("full", f"{how}点「行」：字卡，弹窗的左右和贴着候选栏的那条边没动")
    check(char == word, f"{how}换成字卡后弹窗动了（左、右、贴候选栏的边）：{word} → {char}")
    d.tap(s.find(desc="返回 银行", where="all").c, after=0.8)
    s = d.screen()
    check(s.find(desc="银行，读音 yín háng", where="all") is not None, f"{how}：返回后不是词卡")
    back = placed(s, "返回的词卡")
    check(back == word, f"{how}返回词卡后弹窗动了（左、右、贴候选栏的边）：{word} → {back}")
    d.back(0.6)
    side, edge = word[2]
    return f"弹窗在候选栏{side}，左 {word[0]}、右 {word[1]}、贴候选栏的边 {edge}，三次一样"


@case("PY-143")
def py143(d):
    # t tapped at 85% of its width, toward y (still on t): tama spells as typed, so 全拼 (floor -1.75) reads no yama;
    # in the middle neither. nihso with s at 12% of its width, toward a (hso spells nothing): read as nihao, its cost
    # about -0.7 on a key 36-40 dp wide, over the floor
    ya = ("亚麻", "鸭毛", "压埋")
    keys_at(d, "tama", [(0.85, 0.5), None, None, None])
    edge = d.all_cands()
    d.shot("kb", "t 点在靠 y 的边上打 tama：候选栏和展开后的第一页没有「亚麻」这类 ya ma 的词")
    d.clear()
    keys_at(d, "tama", [None] * 4)
    mid = d.all_cands()
    d.clear()
    keys_at(d, "nihso", [None, None, None, (0.12, 0.5), None])
    slip = d.cands()
    d.shot("kb", "s 点在靠 a 的边上打 nihso：首选「你好」")
    d.clear()
    check(not any(w in edge for w in ya), f"t 靠 y 点的 tama，候选里有 ya ma 的词（全拼不改读拼得成的）：{edge[:14]}")
    check(not any(w in mid for w in ya), f"四个键都点在正中的 tama，候选里有 ya ma 的词：{mid[:14]}")
    check(slip[:1] == ["你好"], f"s 靠 a 点的 nihso，首选不是「你好」：{slip[:6]}")
    return f"tama 靠边 {[w for w in edge if w in ya]}；正中 {mid[:6]}；nihso 靠边首选 {slip[:1]}"


case("PY-145", needs="jvm:StrokePageUatTest.aStrokeDrawnOnThePageIsSentAndThePageStaysAndScrollsNot")(lambda d: None)


case("PY-146", needs="jvm:StrokePageUatTest.aTapPicksADriftOrAStrokeLiftedShortPicksNothing、StrokePageUatTest.aLongPressStillOpensTheCandidatesMenu")(lambda d: None)


case("PY-147", needs="jvm:StrokePageUatTest.theFirstThreePagesDrawingSayHowTheyTurn、StrokePageUatTest.theHintGoesAsAStrokeIsDrawnAndIsCountedOnlyOnceSaid")(lambda d: None)


case("PY-148", needs="jvm:StrokePageUatTest.whereTheKeysDrawNoStrokeThePageScrollsAsBefore、StrokePageUatTest.aMouseDragOrATrackpadSwipeDrawsNone")(lambda d: None)


case("PY-149", needs="jvm:StrokePageUatTest.aStrokeLeavingNoCandidateKeepsThePageAndItsBackspaceTakesItBack")(lambda d: None)
