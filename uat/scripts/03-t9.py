"""uat/03-t9.md (B0-九键). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import case, check, first


def t9(d, digits, gap=0.35, after=0.6):
    """the nine keys by digit, a pause between taps (0.35 s: two taps on one key are 连按 only where
    a case set it on, at 快 or 中; off, the default, every tap is a key)"""
    k = d.keys(refresh=True)
    d.taps([k[c] for c in digits], gap=gap, after=after)


case("T9-01", needs="jvm:T9KeyboardScreenshotTest")(lambda d: None)


@case("T9-02", base="B0-九键", mutates="learn")
def t902(d):
    t9(d, "64426")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(first(c) == "你好", f"候选 {c[:4]}")
    check(t == "你好", f"框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


case("T9-03", needs="jvm:OtherInputMethodsUatTest.nineKeysTheSyllableColumnSettlesASyllable")(lambda d: None)


case("T9-04", needs="jvm:NineKeysUatTest.backspaceAfterASyllableGivesTheDigitsBack")(lambda d: None)


case("T9-05", needs="jvm:NineKeysUatTest.oneSeparatesSyllables")(lambda d: None)


case("T9-09", needs="jvm:T9UatTest.aLongPressedLetterGoesInAsItIs")(lambda d: None)


case("T9-10", needs="jvm:T9UatTest.aDigitKeySwipedDownTypesItsDigit")(lambda d: None)


case("T9-11", needs="jvm:T9UatTest.startOverClearsWhatIsTyped")(lambda d: None)


case("T9-12", needs="jvm:NineKeysUatTest.keysThatMakeASyllableAreRead")(lambda d: None)


case("T9-13", needs="jvm:SettingsPagesUatTest.nineKeysLettersEdited")(lambda d: None)


case("T9-15", needs="jvm:HostCasesUatTest.nineKeysAKeyMissedIsTypedWhereTheRowIsTapped")(lambda d: None)


case("T9-17", needs="jvm:T9UatTest.aShortSwipeWithGlideOnIsStillTheDigit")(lambda d: None)


case("T9-18", needs="jvm:T9UatTest.theNumberPadsDigitsAreDigitsNotTheNineKeys、NineKeysHostUatTest.theNumberPadsDigitsAreTypedOrPick")(lambda d: None)


case("T9-19", needs="jvm:NineKeysTapUatTest.withMultiTapOnOnesPunctuationCyclesAndAQuickSecondDigitIsItsLetter、NineKeysUatTest.withMultiTapAQuickSecondPressIsTheLetterLearnedWordOrNot")(lambda d: None)


case("T9-24", needs="jvm:NineKeysTapUatTest.multiTapOffByDefaultOneIsACommaEachPress、NineKeysUatTest.twoQuickPressesAreTwoKeys")(lambda d: None)


case("T9-20", needs="jvm:NineKeysUatTest.emojiOnTheNineKeys")(lambda d: None)


case("T9-22", needs="jvm:T9UatTest.aDigitSwipedDownWhileTypingPicksItsCandidate、NineKeysHostUatTest.aSwipedDigitPicksItsCandidate、NineKeysHostUatTest.theNumberPadsDigitsAreTypedOrPick")(lambda d: None)


case("T9-23", needs="jvm:T9UatTest.aLongPressedDigitWhileTyping、NineKeysHostUatTest.resetPutsTheFirstCandidateIn")(lambda d: None)


case("T9-25", needs="jvm:NineKeysUatTest.aCharacterErasedIsNoWordBefore")(lambda d: None)


case("T9-27", needs="jvm:T9UatTest.aLongPressedSymbolAfterTheCandidate、NineKeysHostUatTest.aMarkWhileTypingCommitsTheCandidateThenTheChineseMark")(lambda d: None)


case("T9-29", needs="jvm:SettingsPagesUatTest.nineKeysLongPressesEdited")(lambda d: None)


case("T9-31", needs="jvm:T9UatTest.nineKeysEnglishShiftAtASentencesStartOnceAndLocked、NineKeysUatTest.nineKeysEnglishTakesEachKeysCase")(lambda d: None)


case("T9-32", needs="jvm:NineKeysTapUatTest.englishsOneWithNothingTypedCyclesItsMarks、NineKeysUatTest.oneInAnEnglishWordIsAnApostrophe")(lambda d: None)


case("T9-33", needs="jvm:NineKeysTapUatTest.englishByMultiTapCommitsALetterAtATime、NineKeysUatTest.lettersTypedAtATimeLeaveEnglishNothingToOffer")(lambda d: None)


case("T9-34", needs="jvm:T9UatTest.anEmailFieldsColumn")(lambda d: None)


case("T9-36", needs="jvm:NineKeysTapUatTest.anEnglishWordIsEndedByEnterTheColumnZeroOrAHeldDigit、NineKeysUatTest.anEnglishWordGoesInBeforeEnterAMarkZeroOrAHeldDigit")(lambda d: None)


case("T9-37", needs="jvm:T9UatTest.nineKeysEnglishStaysOverARotation")(lambda d: None)


case("T9-38", needs="jvm:T9UatTest.anAddressFieldsColumn")(lambda d: None)


case("T9-40", needs="jvm:NineKeysUatTest.lettersPinnedSpellAWordNotInTheDictionaryAndItIsKept")(lambda d: None)


case("T9-44", needs="jvm:NineKeysTapUatTest.aFieldAskingNoSuggestionsTypesEnglishByMultiTap、NineKeysUatTest.lettersTypedAtATimeLeaveEnglishNothingToOffer")(lambda d: None)


case("T9-45", needs="jvm:T9UatTest.aPasswordFieldIsTheFullKeyboard")(lambda d: None)


case("T9-46", needs="jvm:NineKeysUatTest.switchingWhileTypingCommitsWhatIsTyped")(lambda d: None)


case("T9-48", needs="jvm:T9UatTest.theLongPressPopupIsSmall")(lambda d: None)


case("T9-49", needs="jvm:NineKeysTapUatTest.shiftEndsARunTheNextPressANewCapital")(lambda d: None)


case("T9-56b", needs="jvm:NineKeysUatTest.aWordTypedTwiceInFullDigitsGoesThirdUnderAPart")(lambda d: None)


case("T9-56c", needs="jvm:NineKeysUatTest.multiTapInFullLiftsAWordUnderPartDigits")(lambda d: None)


case("T9-56d", needs="jvm:NineKeysUatTest.digitsInFullLiftAWordUnderPartMultiTap")(lambda d: None)


case("T9-57", needs="jvm:StrokeFilterUatTest.nineKeysWangThenADot")(lambda d: None)


case("T9-58", needs="jvm:T9UatTest.theGlobeGoesToEnglishOnItsOwnKeyboard")(lambda d: None)


case("T9-59", needs="jvm:NineKeysUatTest.zeroWhileTypingCommitsTheFirstThenZero")(lambda d: None)
