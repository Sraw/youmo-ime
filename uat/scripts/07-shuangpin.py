"""uat/07-shuangpin.md (B0-双拼). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import case, check, choose, gear, keys_at


case("SP-01", needs="jvm:OtherInputMethodsUatTest.shuangpinZiranma")(lambda d: None)


case("SP-02", needs="jvm:ShuangpinUatTest.xiaohe")(lambda d: None)


case("SP-03", needs="jvm:GroupedKeyboardUatTest.microsoftsSemicolonKeyBesideL、HostCasesUatTest.microsoftsSemicolonIsAFinal")(lambda d: None)


case("SP-04", needs="jvm:ShuangpinUatTest.aSyllableWithNoInitial")(lambda d: None)


case("SP-05", needs="jvm:ShuangpinUatTest.twoKeysNoSyllableAreInitials")(lambda d: None)


case("SP-07", needs="jvm:ShuangpinUatTest.aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn")(lambda d: None)


case("SP-09", needs="jvm:ShuangpinUatTest.everyKeyboardGlides、GlideUatTest.shuangpinWubiAndTheNineKeysGlidesAreDrawn")(lambda d: None)


case("SP-13b", needs="jvm:ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard")(lambda d: None)


case("SP-13c", needs="jvm:ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard")(lambda d: None)


@case("SP-30", base="B0-双拼", mutates="learn")
def sp30(d):
    # only the words typed before are read by a key alone: each in whole syllables once first
    d.type("ufmeuihb")
    d.pick("什么时候")
    d.clear()
    d.type("ufmuh")
    c = d.all_cands()
    d.shot("kb", "自然码 ufmuh（全码打过一次以后）：候选栏或展开后的第一页有「什么时候」")
    check("什么时候" in c, f"ufmuh 的候选栏和展开后的第一页都没有「什么时候」：{c[:14]}")
    d.pick("什么时候")
    t = d.field_text()
    check(t == "什么时候", f"点「什么时候」后框里是「{t}」")
    d.clear()
    d.type("yivizl")
    d.pick("一直在")
    d.clear()
    d.type("yivz")
    c2 = d.all_cands()
    d.shot("kb", "自然码 yivz：候选栏或展开后的第一页有「一直在」")
    check("一直在" in c2, f"yivz 的候选栏和展开后的第一页都没有「一直在」：{c2[:14]}")
    return f"框里是「{t}」；yivz 候选 {c2[:8]}"


case("SP-31", needs="jvm:ShuangpinUatTest.twoKeysThatMakeASyllableStayThatSyllableFirst、ShuangpinUatTest.wholeSyllablesReadAsBeforeTheAbbreviations")(lambda d: None)


case("SP-32", needs="jvm:ShuangpinUatTest.aWordTypedByItsInitialsOnly")(lambda d: None)


case("SP-33", needs="jvm:ShuangpinUatTest.aWordTypedPartlyByInitialsIsLearnedUnderThoseKeys")(lambda d: None)


case("SP-34", needs="jvm:ShuangpinUatTest.aWordUsedInFullIsNotRaisedUnderAPairItsInitialsSpell")(lambda d: None)


case("SP-35", needs="jvm:ShuangpinUatTest.aWordAddedByHandIsNotPutInUnderAPairItsInitialsSpell")(lambda d: None)


case("SP-36", needs="jvm:ShuangpinUatTest.whatIsPinnedOrPickedUnderACodeReadWholeIsRankedThere")(lambda d: None)


case("SP-43", needs="jvm:ShuangpinUatTest.aWordAddedByHandIsReadByAKeyAloneAtOnce、PinyinSessionAbbrevTest.aWordAddedByHandIsReadByAKeyAlone")(lambda d: None)


case("SP-44", needs="jvm:PinyinSessionAbbrevTest.aWordNotTypedForThirtyTypingDaysIsReadByWholeSyllablesOnlyAgain、PinyinSessionAbbrevTest.aWordPinnedIsReadByAKeyAloneThoughNotTypedForThirtyTypingDays、PinyinSessionAbbrevTest.aWordPickedUnderAKeyReadAloneIsTypedThatDay、HabitCountsTest.theNodesToAWordTypedAreThoseOfEachReadingItWasTypedAt")(lambda d: None)


case("SP-45", needs="jvm:PinyinDecoderTypedTest.aKeyReadAloneReadsNoMergedOrImportedWordNeverTyped、PinyinDecoderTypedTest.aKeyReadAloneReadsOnlyTheWordsTypedOrChosen")(lambda d: None)


case("SP-46", needs="jvm:ShuangpinUatTest.withTheHabitsOffNoKeyIsReadAlone、PinyinSessionAbbrevTest.withNoHabitsAKeyIsNotReadAlone")(lambda d: None)


# ---------------------------------------------------------------- 18 键 and 九键双拼 (B0-18键, B0-九键双拼)


case("SP-14", needs="jvm:ShuangpinUatTest.theEighteenKeysInXiaoheTypeASentenceAndOfferTheSyllables、ShuangpinKeysUatTest.theEighteenKeysAreThreeRowsOfCapitalsAndTheCodeRowOffersTheSyllables")(lambda d: None)


case("SP-15", needs="jvm:ShuangpinUatTest.theNineKeysInZiranmaTypeASentenceAndOfferTheSyllables、ShuangpinKeysUatTest.theNineKeysHaveThePhonesLettersAndTheColumnOffersTheSyllables")(lambda d: None)


case("SP-16", needs="jvm:ShuangpinUatTest.microsoftsIngIsTheLSemicolonKey、ShuangpinKeysUatTest.theEighteenKeysHaveLAndSemicolonOnOneKeyAndNoSemicolonKey")(lambda d: None)


case("SP-17", needs="jvm:ShuangpinUatTest.theEighteenKeysLearnApartFromTheFullKeyboard")(lambda d: None)


case("SP-18", needs="jvm:ShuangpinUatTest.enterOrASwitchCommitsTheReadingShownInLetters")(lambda d: None)


case("SP-19", needs="jvm:SettingsPagesUatTest.aPinOnTheEighteenKeysSaysSoWithItsSchemeAndLetters、WordsUatTest.pinsOnTheEighteenKeysAndTheFullListedUnderTheirKinds、LearnedItemsFragmentTest.eachKeyboardsKindIsNamed、LearnedItemsFragmentTest.aGroupedKeyboardsKeysAreShownAsTheirLetters")(lambda d: None)


case("SP-20", needs="jvm:GroupedKeyboardUatTest.theGlobeGoesToTheEnglishKeyboardAndBack、GroupedKeyboardTest.theBottomRowHasNoChineseEnglishKeyAndTheLanguageSwitchFollowsItsSetting")(lambda d: None)


case("SP-21", needs="jvm:GroupedKeyboard18Test.theSpaceIsAtLeast15PercentOfTheRowInEveryField、GroupedKeyboardUatTest.theEighteenAndNineKeysInAnAddressField、KeyboardHalvesUatTest.inAnAddressFieldShuangpinsGroupedKeysGoInAsLettersOrTheWordTyped")(lambda d: None)


case("SP-22", needs="jvm:ShuangpinUatTest.onTheNineKeysOneSeparatesTheSyllablesWhileTyping、ShuangpinKeysUatTest.theNineKeysOneIsPunctuationWithNothingTypedTheSeparatorWhileTyping")(lambda d: None)


case("SP-24", needs="jvm:GroupedKeyboardUatTest.aNineKeyAtTheRightReachesItsLastLetterOnScreen、GroupedKeyboardUatTest.nineKeyShuangpinsLongPressesCodeRowAndTheNumbersStrip、KeyboardHalvesUatTest.aSyllableTakenOnTheNumbersPageGoesIntoTheCode")(lambda d: None)


case("SP-23", needs="jvm:ShuangpinUatTest.microsoftsScreenSemicolonIsAMarkAndAnInitialTakenKeepsItsSyllables")(lambda d: None)


case("SP-25", needs="jvm:StrokeFilterUatTest.shuangpinXiThenADownStroke、StrokeFilterUatTest.shuangpinXiOnTheGroupedKeysThenADownStroke")(lambda d: None)
case("SP-37", needs="jvm:StrokeFilterUatTest.keyiThenAHengPutsTwoCharactersAloneRightAfterTheFirst")(lambda d: None)


case("SP-26", needs="jvm:ImKeyboardsUatTest.shuangpinsFourteenKeysSendTheirOwnCodesAndAreNoneUnderMicrosoft、InputMethodSettingsScreenTest.theFourteenKeysUnderMicrosoftShowAsTheEighteen")(lambda d: None)


case("SP-27", needs="jvm:KotlinHostSchemeTest.aNewInstallsShuangpinIsXiaohe")(lambda d: None)


case("SP-28", needs="jvm:ShuangpinKeysUatTest.theEighteenKeysUnderASchemeWithoutTheSemicolonShowLAlone、ShuangpinKeysUatTest.theNineKeysUnderASchemeWithoutTheSemicolonShowJklAlone、KeyboardWindowTest、GroupedKeyboard18Test.pinyinsEighteenKeysShowLWithoutTheSemicolon")(lambda d: None)
case("SP-29", needs="jvm:EnginesWordCardTest")(lambda d: None)


# ---------------------------------------------------------------- 按偏的键按落点读 (061-neighbour-correction)


case("SP-38", needs="jvm:NeighbourSlipTest.shuangpinReadsTwoKeysWithOneSlippedWhereTapped、NeighbourSlipTest.eachShuangpinSchemeReadsTheKeyTappedTowards、NeighbourSlipTest.aSlipCostsByHowFarTheTapIsFromTheKeyMeant")(lambda d: None)


case("SP-39", needs="jvm:NeighbourSlipTest.eachShuangpinSchemeReadsTheKeyTappedTowards、NeighbourSlipTest.aSlipCostsByHowFarTheTapIsFromTheKeyMeant")(lambda d: None)


case("SP-40", needs="jvm:NeighbourSlipTest.eachShuangpinSchemeReadsTheKeyTappedTowards、NeighbourSlipTest.aSlipCostsByHowFarTheTapIsFromTheKeyMeant")(lambda d: None)


case("SP-41", needs="jvm:ShuangpinUatTest.initialsInTheMiddleReadTheWordsTypedWhereverTapped、PinyinSessionAbbrevSlipTest.aWordTypedIsReadByItsInitialsBeforeASlipOfThem、NeighbourSlipTest.consonantsAloneReadAsTypedWhereTappedInTheMiddle")(lambda d: None)


case("SP-42", needs="jvm:PinyinSessionSlipTest.shuangpinsCodeShowsTheSyllableAsMeant")(lambda d: None)


case("SP-48", needs="jvm:NeighbourSlipTest.shuangpinsEighteenAndFourteenKeysReadAGroupAsTheOneNextToIt、EnginesTest.shuangpinsEighteenKeysAndDachenReadASlipUnlessSlipsAreOff、PinyinSessionSlipTest.onShuangpinsEighteenKeysTheKeyMeantIsLearnedAsItsCode、GroupedKeyboardTapTest.everyGroupedKeyboardsTapCarriesTheKeysAroundItAsTheEngineReadsThem、NeighbourTouchTest.aGroupKeysTapGoesWithTheGroupsAroundItAsTheEngineReadsThem")(lambda d: None)


case("SP-49", needs="jvm:NeighbourSlipTest.shuangpinsEighteenAndFourteenKeysReadAGroupAsTheOneNextToIt、GroupedKeyboardTapTest.everyGroupedKeyboardsTapCarriesTheKeysAroundItAsTheEngineReadsThem、NeighbourTouchTest.aGroupKeysTapGoesWithTheGroupsAroundItAsTheEngineReadsThem")(lambda d: None)


case("SP-50", needs="jvm:NeighbourSlipTest.shuangpinsNineKeysReadADigitAsTheOneItWasTappedTowards、T9ShuangpinKeyboardTest.aDigitKeysTapGoesWithWhereItFellAndTheKeysAroundItByTheirCodes、NeighbourTouchTest.shuangpinsNineKeysDigitsGoWithTheirCodesAround")(lambda d: None)


@case("SP-47", base="B0-双拼", mutates=True)
def sp47(d):
    # 小鹤 (q is iu, tiu no syllable): q tapped at its bottom right corner, toward a (whose middle is under q's right
    # edge), is ta, its code row so; in the middle t q (the two initials)
    gear(d, "双拼")
    choose(d, "双拼方案", "小鹤")
    d.open_page("text")
    d.switch_im("双拼")
    keys_at(d, "tq", [None, (0.85, 0.95)])
    edge = d.preedit()
    c = d.cands()
    d.shot("kb", "小鹤 t、q 靠 a 点：编码行是 ta，首选「他」")
    d.clear()
    keys_at(d, "tq", [None, None])
    mid = d.preedit()
    d.clear()
    check(edge.replace(" ", "") == "ta", f"q 靠 a 点的 t q，编码行是「{edge}」，候选 {c[:6]}")
    check(mid.replace(" ", "") != "ta", f"两个键都点在正中的 t q，编码行是「{mid}」")
    return f"靠边「{edge}」{c[:3]}；正中「{mid}」"
