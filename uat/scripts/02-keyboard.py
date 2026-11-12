"""uat/02-keyboard.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import case, check, choose, has, home, script


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


# ---------------------------------------------------------------- 一个键一份长按（060-keys-unify）


case("KB-73", needs="jvm:KeysEverywhereUatTest.commaIsOneKey")(lambda d: None)


case("KB-74", needs="jvm:KeysEverywhereUatTest.commaEditedOnceEverywhere")(lambda d: None)


case("KB-75", needs="jvm:KeysEverywhereUatTest.commaFirstDrawnAndTyped、KeysEverywhereUatTest.heldAndLetGoTypesTheFirst、KeysEverywhereUatTest.heldAndLetGoTypesTheFirstOnANarrowKeyboard")(lambda d: None)


case("KB-76", needs="jvm:KeysEverywhereUatTest.nineKeysOneThroughThePunctuationEverywhereAndSharedOnceEdited、KeysEverywhereUatTest.nineKeysOnesTapCyclesThePunctuationAsItMapsInBothLanguages")(lambda d: None)


case("KB-77", needs="jvm:KeysEverywhereUatTest.nineKeysFiveSymbolsSharedLettersByInputMethod")(lambda d: None)


case("KB-78", needs="jvm:KeysEverywhereUatTest.zhuyinNinesToneAndSlot、KeysEverywhereUatTest.zhuyinNinesToneOff")(lambda d: None)


case("KB-79", needs="jvm:KeysEverywhereUatTest.fourteenKeysQwFirstDrawnAndTyped")(lambda d: None)


case("KB-82", needs="jvm:PanelKeysUatTest.aLayerOf32OnTheNarrowestOneHanded、PanelKeysUatTest.aLayerOf32Landscape")(lambda d: None)


case("KB-83", needs="jvm:DisplayIsTypedUatTest.everyKeyShowsWhatItTypes、DisplayIsTypedUatTest.toggledWithTheKeyboardUp")(lambda d: None)


def held(d, key):
    """[key] (a node of the keyboard) held: the texts shown the while, outside the keyboard's own keys; let go
    where it went down (its first typed, if any)"""
    kb = d.need_keyboard()
    on_keys = {t for n in kb.walk() if n.clickable for t in n.texts()}
    d.touch(("down", *key.c), ("sleep", 0.9))
    shown = [n.text for n in d.screen().nodes if n.text and not n.clickable and n.text not in on_keys]
    d.touch(("up", *key.c))
    time.sleep(0.5)
    return shown


def face(d, *texts):
    """the keyboard's key whose face is one of [texts]"""
    kb = d.need_keyboard()
    n = next((n for n in kb.walk() if n.clickable and n.texts() and n.texts()[0] in texts), None)
    check(n is not None, f"键盘上没有 {texts[0]}")
    return n


@case("KB-80", base="B0-注音全键盘", mutates=True, visual=True)
def kb80(d):
    st = script("14-settings")
    d.open_page("text")
    d.clear()
    b = face(d, "ㄅ")
    before = held(d, b)
    check("1" not in before and b.desc in ("", "ㄅ"), f"改之前 ㄅ 长按弹出 {before}，读作「{b.desc}」")
    d.clear()
    st.slots_tab(d, "注音全键盘")
    st.key_dialog(d, "ㄅ")
    st.dialog_add(d, "1")
    st.dialog_way(d, "上滑", "长按首选")
    st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    b = face(d, "ㄅ")
    d.shot("kb", "ㄅ 的键角画 1")
    corner = b.desc
    after = held(d, b)
    let_go = d.field_text()
    d.clear()
    d.swipe(b.c, (b.c[0], b.c[1] - 130), 150, after=0.8)
    up = d.field_text()
    check("1" in after, f"改之后 ㄅ 长按弹出 {after}")
    check(corner.endswith("1"), f"ㄅ 的键角读作「{corner}」")
    check(let_go == "1" and up == "1", f"长按松手打出「{let_go}」，上滑打出「{up}」")
    return f"改前长按无弹窗；改后弹出 1、键角 1，松手「{let_go}」，上滑「{up}」"


@case("KB-81", mutates=True, visual=True)
def kb81(d):
    st = script("14-settings")
    st.slots_tab(d, "功能键")
    st.key_dialog(d, "，键")
    st.dialog_pick(d, '"')
    d.tap_text("设为首选", scroll=False, after=0.5)
    st.dialog_way(d, "上滑", "长按首选")
    st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    c = face(d, "，", ",")
    corner = c.desc
    # swiped first: the engine's “ ” alternation is its own state, which a clear does not reset (a hold let go
    # first would type “ and leave ” next)
    d.swipe(c.c, (c.c[0], c.c[1] - 130), 150, after=1.0)
    one = d.field_text()
    d.swipe(c.c, (c.c[0], c.c[1] - 130), 150, after=1.0)
    two = d.field_text()
    d.clear()
    d.touch(("down", *c.c), ("sleep", 0.9))
    d.shot("kb", "长按 ，：“ 带框；键角 “")
    shown = [n.text for n in d.screen().nodes if n.text in ("“", '"')]
    d.touch(("up", *c.c))
    time.sleep(0.5)
    d.clear()
    check("“" in shown and '"' not in shown, f"长按弹窗里是 {shown}")
    check(corner.endswith("“"), f"， 的键角读作「{corner}」")
    # 成对符号 (KB-26) on by default: the first “ comes with its ”, the second (the engine's ”) steps over it
    check(one == "“”" and two == "“”", f"上滑一次「{one}」，两次「{two}」")
    return f"弹窗 {shown}、键角「{corner}」；上滑一次「{one}」，两次「{two}」"


@case("KB-72", mutates=True, visual=True)
def kb72(d):
    st = script("14-settings")
    tb = script("05-toolbar")
    d.sh("wm density 480")
    time.sleep(1.5)
    for key in ("j", "k"):
        st.slots_tab(d, "全键盘")
        st.key_dialog(d, key)
        st.dialog_text(d, "下滑", "xianzaizenmeyangle", "现在怎么样了")
        st.dialog_ok(d)
    d.open_page("text")
    d.clear()
    d.shot("kb", "下滑图标默认左上：j、k 画「现在…」「现…」或两条横线的图标，别的键不缩小")
    j = d.keys(refresh=True)["j"]
    d.swipe(j, (j[0], j[1] + 130), 150, after=0.8)
    typed = d.field_text()
    d.clear()
    home(d, "按键")
    choose(d, "下滑图标位置", "底部")
    d.open_page("text")
    d.shot("kb", "下滑图标在底部：j、k")
    st.appearance_tab(d, "大小与样式")
    choose(d, "键盘高度", "紧凑")
    d.open_page("text")
    d.shot("kb", "紧凑：j、k")
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    d.shot("kb", "紧凑、单手：j、k")
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    tb.floating(d)
    d.shot("kb", "紧凑、悬浮：j、k")
    st.slots_tab(d, "全键盘")
    st.key_dialog(d, "a")
    st.dialog_text(d, "下滑", "nihao", "你好")
    st.dialog_ok(d)
    home(d, "按键")
    choose(d, "上滑图标位置", "左下")
    d.open_page("text")
    d.clear()
    d.shot("kb", "a：上滑左下、下滑底部，碰上时省的是 @，你好照画")
    a = d.keys(refresh=True)["a"]
    d.swipe(a, (a[0], a[1] + 130), 150, after=0.8)
    hello = d.field_text()
    check(typed == "现在怎么样了", f"j 下滑打出「{typed}」")
    check(hello == "你好", f"a 下滑打出「{hello}」")
    return f"j 下滑「{typed}」，a 下滑「{hello}」"
