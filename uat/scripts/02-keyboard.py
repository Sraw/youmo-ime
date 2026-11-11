"""uat/02-keyboard.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import case, check, choose, has, home


@case("KB-01")
def kb01(d):
    d.type("ni")
    c = d.cands(want="你")
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(not t, f"框里是「{t}」")
    check(p == "ni", f"拼音行 `{p}`")
    check(has(c, "你") and not s.find(desc="展开工具栏", where="ime"), f"候选 {c[:5]}")
    return f"`{p}`，候选 {c[:4]}"


case("KB-02", needs="jvm:KeysUatTest.englishLettersTypeThemselvesASpaceAfter")(lambda d: None)


case("KB-03", needs="jvm:KeyboardUatTest.swipingALetterUpTypesItsCorner")(lambda d: None)


case("KB-04", needs="jvm:KeyboardUatTest.aLongPressLetGoTypesTheCorner")(lambda d: None)


case("KB-05", needs="jvm:KeyboardUatTest.aLongPressesChoicesTypeWhereTheFingerIs")(lambda d: None)


case("KB-06", needs="jvm:KeyboardUatTest.cornersShowChinesePunctuationWhileChinese")(lambda d: None)


case("KB-07", needs="jvm:KeysUatTest.shiftCapitalisesTheNextLetter")(lambda d: None)


case("KB-08", needs="jvm:KeyboardUatTest.shiftTwiceLocksCaps")(lambda d: None)


case("KB-09", needs="jvm:KeyboardUatTest.backspaceOnce")(lambda d: None)


case("KB-10", needs="jvm:KeyboardFieldsUatTest.backspaceTakesAnEmojiWithItsSkinToneWhole")(lambda d: None)


case("KB-11", needs="jvm:KeyboardUatTest.holdingBackspaceRepeatsFasterTillLetGo")(lambda d: None)


case("KB-12", needs="jvm:KeyboardUatTest.swipingBackspaceLeftSelectsAndDeletes")(lambda d: None)


case("KB-13", needs="jvm:KeyboardUatTest.swipingBackspaceWhileTypingClearsThePinyin")(lambda d: None)


case("KB-14", needs="jvm:HostCasesUatTest.commaAndFullStopAfterChineseAreChinese")(lambda d: None)


case("KB-15", needs="jvm:HostCasesUatTest.aCommaAfterADigitIsChinese")(lambda d: None)


case("KB-16", needs="jvm:KeyboardUatTest.holdingTheCommaOffersTwoRows")(lambda d: None)


case("KB-17", needs="jvm:KeyboardUatTest.spaceSlidesMoveTheCursor")(lambda d: None)


case("KB-18", needs="jvm:KeysUatTest.spaceCommitsTheFirstCandidate")(lambda d: None)


case("KB-19", needs="jvm:KeysUatTest.returnCommitsTheLettersAsTyped")(lambda d: None)


case("KB-20", needs="jvm:KeyboardUatTest.searchFieldsReturnSearches")(lambda d: None)


case("KB-21", needs="jvm:KeyboardUatTest.nextFieldsReturnGoesOn")(lambda d: None)


case("KB-22", needs="jvm:KeyboardUatTest.addressFieldHasSlashDotAndDotCom")(lambda d: None)


case("KB-23", needs="jvm:KeyboardUatTest.holdingDotComOffersMore")(lambda d: None)


case("KB-24", needs="jvm:KeysUatTest.anEmailFieldsLettersBeforeAtAreCommittedAsTyped")(lambda d: None)


case("KB-25", needs="jvm:HostCasesUatTest.anAddressFieldTypesDigitsAndMarksAsTheyAre")(lambda d: None)


case("KB-26", needs="jvm:PairsUatTest.aBracketComesInPairsTheCursorBetween")(lambda d: None)


case("KB-27", needs="jvm:PairsUatTest.theClosingBracketStepsOverTheOneThere")(lambda d: None)


case("KB-28", needs="jvm:PairsUatTest.backspaceRightAfterDeletesThePair")(lambda d: None)


case("KB-30", needs="jvm:KeyboardUatTest.aKeyHeldShowsItLarger")(lambda d: None)


@case("KB-31", base="B0-设置", mutates=True, visual=True)
def kb31(d):
    # the reserved row is the embedded code row's; the default (floating, ST-48) has none
    home(d, "外观")
    d.tap_text("大小与样式", scroll=False, after=1.0)
    choose(d, "编码行", "嵌在键盘里")
    d.open_page("chat")

    def where(s):
        f = d.field(s)
        kb = s.find(rid="keyboard_view", where="ime")
        send = next((n for n in s.app() if n.text in ("发送", "Send") or n.desc == "发送"), None)
        return (f.b if f else None, kb.b[1] if kb else None, send.b if send else None)
    w0 = where(d.screen())
    d.shot("full", "没打字时")
    d.type("nihao")
    d.cands()
    s = d.screen()
    w1, p = where(s), d.preedit(s)
    d.shot("full", "打 nihao 时")
    d.bksp(5)
    time.sleep(0.5)
    w2 = where(d.screen())
    check(p == "ni hao", f"拼音行 `{p}`")
    check(w0 == w1 == w2, f"位置变了：没打字 {w0}，打字 {w1}，删光 {w2}")
    check(w0[0] and w0[1] and w0[0][3] <= w0[1], f"聊天框 {w0[0]} 被挡住（键盘顶 {w0[1]}）")
    return f"聊天框 {w0[0]}、键盘顶 {w0[1]} 始终不动；拼音行 `{p}`"


# ---------------------------------------------------------------- 滑行

case("KB-32", needs="jvm:KeyboardUatTest.glideIsOffByDefault")(lambda d: None)


case("KB-33", needs="jvm:HostCasesUatTest.aGlideGoesInWithItsSpaceItsWordChangedOrErasedWholeAMarkTakesTheSpace、KeyboardFieldsUatTest.aGlideDrawsTheFingersTrail")(lambda d: None)


case("KB-35", needs="jvm:KeyboardUatTest.tapsShortSwipesAndHoldsAreNoGlide")(lambda d: None)


case("KB-36", needs="jvm:KeyboardFieldsUatTest.noGlideInAPasswordAnAddressOrAnEmail")(lambda d: None)


case("KB-38", needs="jvm:KeyboardUatTest.holdingTheSpaceSetToNothingIsOneSpace")(lambda d: None)


case("KB-39", needs="jvm:KeyboardUatTest.shiftChangesAllThreeRows")(lambda d: None)


case("KB-41", needs="jvm:KeyboardUatTest.aFirstMoveSidewaysAfterSlippingOffKeepsThePopup")(lambda d: None)


case("KB-42", needs="jvm:StrokeUatTest.aStrokeDrawnWhilePinyinIsTypedIsSentAsOneTheKeysNone、StrokeUatTest.theNineAndFourteenKeysSendAStrokeToo、StrokeUatTest.aStrokeStartsInTheSplitGapTooATapOrAGlideThereDoNothing、StrokeUatTest.aSecondFingerOnALetterWhileOneRestsInTheGapTypesIt、StrokeUatTest.aStrokeFromTheGapCancelledSendsNothingAndLeavesNoTrail、StrokeUatTest.noOtherLayoutHasAGap、StrokeUatTest.theSweepFindsTheSplitGap")(lambda d: None)


case("KB-43", needs="jvm:StrokeUatTest.withNothingTypedGlideChosenOrEnglishNoStrokeIsSent")(lambda d: None)


case("KB-44", needs="jvm:AddressUatTest.anEmptyEmailFieldOffersTheSuffixesTheCursorStaysBeforeTheAt")(lambda d: None)


case("KB-45", needs="jvm:AddressUatTest.aNameTypedGetsTheSuffixTheCursorAtTheEnd")(lambda d: None)


case("KB-46", needs="jvm:AddressUatTest.afterTheAtTheLettersTypedFilter")(lambda d: None)


case("KB-47", needs="jvm:AddressUatTest.aUrlFieldGetsItsStartsThenTheSuffixes")(lambda d: None)


case("KB-48", needs="jvm:AddressUatTest.theUsersOwnListsAreOffered")(lambda d: None)


case("KB-49", needs="jvm:AddressUatTest.addressesTypedAreRememberedAndTheirSuffixesComeFirst")(lambda d: None)


case("KB-50", needs="jvm:AddressUatTest.incognitoFieldsAndNoHabitsLearnNothing")(lambda d: None)


case("KB-51", needs="jvm:AddressUatTest.aRestartedFieldOffersNothingOfTheOneBefore")(lambda d: None)


case("KB-52", needs="jvm:AddressUatTest.halfAnAddressIsNotLearnedTillTheFieldIsLeft")(lambda d: None)


case("KB-53", needs="jvm:AddressUatTest.anAddressTheAppFilledInIsNotLearnedWhateverIsSelected")(lambda d: None)


case("KB-54", needs="jvm:AddressUatTest.aSuffixHalfTypedIsFinished")(lambda d: None)


case("KB-55", needs="jvm:AddressUatTest.beforeTheFirstUnlockOrInALongTextNothingIsLearned")(lambda d: None)


case("KB-56", needs="jvm:AddressUatTest.fieldsThatShareANameAreToldApartByTheirText")(lambda d: None)


case("KB-57", needs="jvm:AddressUatTest.theLastFieldIsLearnedAsTheKeyboardGoesNotWhatTheSettingsForgot")(lambda d: None)


case("KB-58", needs="jvm:AddressUatTest.onlyTheAddressAddedToWhatTheFieldStartedWithIsLearned")(lambda d: None)


case("KB-59", needs="jvm:AddressUatTest.aUrlFieldsStartingHostIsNotCountedAddedOnesAre")(lambda d: None)


case("KB-60", needs="jvm:HostCoreContractTest.an app unbound clears the panel its focus out dropped before no context is active")(lambda d: None)


case("KB-61", needs="jvm:BaseKeyboardTest.aLettersSwipeDownIsItsShortcutAndUpItsFirst")(lambda d: None)


case("KB-62", needs="jvm:BaseKeyboardTest.theSpaceUpIsTheEditMenuAndTheBackspaceUpAndDownClearAndRestore、EditPlanTest、EditPlanTest.aShortReadClearsWhatWasGivenAndPutsBackJustThatAtTheCursor、EditPlanTest.aClearAtTheLimitWithTheCursorReportedAheadKeepsWhatIsAfterTheCursor、EditPlanTest.aShortReadClearIsNotPutBackOnceSomethingWasTypedBeforeTheCursor、HostCoreEnginesTest.what is typed is cleared without a commit and typed again、HostCoreEnginesTest.what is typed after a syllable taken off the nine keys' bar is never typed again、EnginesTest.aSyllableTakenOffTheBarIsAPick、SwipePopupScreenshotTest.theEditMenusCellsAreReadByTheirLabels")(lambda d: None)


case("KB-63", needs="jvm:BaseKeyboardTest.aKeysOwnTextShortcutIsWhatItsSwipeRuns")(lambda d: None)


case("KB-64", needs="jvm:DeletionRunTest")(lambda d: None)


case("KB-65", needs="jvm:SwipePopupScreenshotTest.theBubbleSaysTheShortcutFitted、GroupedKeyboardTest.aSwipesShortcutShowsAsItIsNamedNotInTheLettersCase、PreviewLabelTest")(lambda d: None)


case("KB-66", needs="jvm:KeyMarksUatTest.eachWhereItsSettingSays、KeyMarksUatTest.onePlaceStoredInBothTheSymbolYields、KeyMarkPositionSettingsTest.theTwoTogetherTheOthersPlaceGreyedOut、KeyMarksUatTest.theFirstOpenIsAsAfterAnySettingChanges、KeyMarksUatTest.aLongPressTurnedOffPlacesTheMarksAsAKeyboardMadeWithIt、KeyMarksScreenshotTest.everyPlaceOnTheKeyboards、KeyMarksTest、KeyMarkYieldTest")(lambda d: None)


case("KB-68", needs="jvm:LetterCaseScreenshotTest、LetterCaseRuleTest")(lambda d: None)


case("KB-67", needs="jvm:KeyMarksEverywhereUatTest.noKeyDrawsTwoThingsOverEachOtherUpright、KeyMarksEverywhereUatTest.noKeyDrawsTwoThingsOverEachOtherTurned、KeyMarkYieldTest")(lambda d: None)


case("KB-69", needs="jvm:KeyShortcutsHostTest.swipeSelectSelectsFromTheCursorAsTheFingerMovesSideways、KeyShortcutsHostTest.swipeSelectCommitsWhatIsTypedFirst、KeyShortcutsHostTest.swipeSelectStepsOverAnEmojiWhole、SwipeSelectionTest、SwipeStepsTest")(lambda d: None)


def three_lines(d):
    """the testbed's 多行 field holding aaa, bbb, ccc on three lines, the cursor at the end"""
    from uat import TESTBED
    d.sh(f"am force-stop {TESTBED}")
    d.testbed("types")
    n = d.wait(lambda s: next((x for x in s.app() if x.rid == "multiline" and x.pkg == TESTBED), None), 15,
               what="the testbed's 多行 field")
    d.tap(n.c, 1.5)
    d.page = None
    d.host = None
    d._keys = None
    for i, w in enumerate(("aaa", "bbb", "ccc")):
        d.type(w)
        d.enter()          # the letters as typed
        if i < 2:
            d.enter()      # a new line
    time.sleep(0.5)
    st = d.testbed_state()["multiline"]
    t = st["text"]
    check(t == "aaa\nbbb\nccc", f"框里是 {t!r}")
    return t


@case("KB-70", visual=True)
def kb70(d):
    t = three_lines(d)
    line = lambda: t.count("\n", 0, d.testbed_state()["multiline"]["selStart"])
    x, y = d.keys(refresh=True)["space"]
    p = d.dpx()
    seen = []
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 16 * p, y), ("move", x - 25 * p, y), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    d.touch(("move", x - 25 * p, y - 16 * p), ("move", x - 25 * p, y - 32 * p), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    d.touch(("move", x - 25 * p, y - 48 * p), ("move", x - 25 * p, y - 62 * p), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    d.shot("app", "横滑后上挪两行")
    d.touch(("move", x - 25 * p, y - 20 * p), ("up", x - 25 * p, y - 20 * p), ("sleep", 0.3))
    seen.append((d.testbed_state()["multiline"]["selStart"], line()))
    lines = [s[1] for s in seen]
    check(seen[0][0] < 11 and lines == [2, 1, 0, 1], f"光标 {seen}")
    check(d.testbed_state()["multiline"]["text"] == t, "没有打出字")
    return f"光标（位置, 行）{seen}"


@case("KB-71", visual=True)
def kb71(d):
    t = three_lines(d)
    ml = lambda: d.testbed_state()["multiline"]
    x, y = d.keys(refresh=True)["space"]
    p = d.dpx()
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 16 * p, y), ("move", x - 25 * p, y), ("sleep", 0.3))
    for dy in range(30, 181, 30):
        d.touch(("move", x - 25 * p, y - dy * p))
    d.touch(("sleep", 0.3))
    top = ml()
    d.shot("app", "上挪过头")
    for dy in range(150, -21, -30):
        d.touch(("move", x - 25 * p, y - dy * p))
    d.touch(("up", x - 25 * p, y + 20 * p), ("sleep", 0.3))
    end = ml()
    check(top["focused"] and top["selStart"] == 0, f"上挪过头：{top['selStart']} focused={top['focused']}")
    check(end["focused"] and end["selStart"] == len(t), f"下挪过头：{end['selStart']} focused={end['focused']}")
    t = three_lines(d)
    first = t.index("\n")
    x, y = d.keys(refresh=True)["space"]
    # one 60 dp move: two of the space's 28 dp line steps in one sample, a rush
    d.touch(("down", x, y), ("move", x - 8 * p, y), ("move", x - 16 * p, y), ("move", x - 25 * p, y), ("sleep", 0.3),
            ("move", x - 25 * p, y - 60 * p), ("up", x - 25 * p, y - 60 * p), ("sleep", 0.3))
    two = ml()
    d.shot("app", "快挪两行")
    check(two["focused"] and 0 < two["selStart"] <= first, f"快挪两行：{two['selStart']}（第一行是 1..{first}）")
    return f"上到 {top['selStart']}，下到 {end['selStart']}，焦点一直在多行框"
