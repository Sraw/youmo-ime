"""uat/07-shuangpin.md (B0-双拼). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import case


case("SP-01", needs="jvm:OtherInputMethodsUatTest.shuangpinZiranma")(lambda d: None)


case("SP-02", needs="jvm:ShuangpinUatTest.xiaohe")(lambda d: None)


case("SP-03", needs="jvm:GroupedKeyboardUatTest.microsoftsSemicolonKeyBesideL、HostCasesUatTest.microsoftsSemicolonIsAFinal")(lambda d: None)


case("SP-04", needs="jvm:ShuangpinUatTest.aSyllableWithNoInitial")(lambda d: None)


case("SP-05", needs="jvm:ShuangpinUatTest.twoKeysNoSyllableAreInitials")(lambda d: None)


case("SP-07", needs="jvm:ShuangpinUatTest.aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn")(lambda d: None)


case("SP-09", needs="jvm:ShuangpinUatTest.everyKeyboardGlides、GlideUatTest.shuangpinWubiAndTheNineKeysGlidesAreDrawn")(lambda d: None)


case("SP-13b", needs="jvm:ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard")(lambda d: None)


case("SP-13c", needs="jvm:ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard")(lambda d: None)


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


case("SP-24", needs="jvm:GroupedKeyboardUatTest.nineKeyShuangpinsLongPressesCodeRowAndTheNumbersStrip、KeyboardHalvesUatTest.aSyllableTakenOnTheNumbersPageGoesIntoTheCode")(lambda d: None)


case("SP-23", needs="jvm:ShuangpinUatTest.microsoftsScreenSemicolonIsAMarkAndAnInitialTakenKeepsItsSyllables")(lambda d: None)


case("SP-25", needs="jvm:StrokeFilterUatTest.shuangpinXiThenADownStroke、StrokeFilterUatTest.shuangpinXiOnTheGroupedKeysThenADownStroke")(lambda d: None)


case("SP-26", needs="jvm:ImKeyboardsUatTest.shuangpinsFourteenKeysSendTheirOwnCodesAndAreNoneUnderMicrosoft、InputMethodSettingsScreenTest.theFourteenKeysUnderMicrosoftShowAsTheEighteen")(lambda d: None)


case("SP-27", needs="jvm:KotlinHostSchemeTest.aNewInstallsShuangpinIsXiaohe")(lambda d: None)
