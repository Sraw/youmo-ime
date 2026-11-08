"""uat/03-t9.md (B0-九键). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

import time

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


def zhuyin_hold(d, xy):
    """a long press on xy: the popup's texts while the finger is down, then lifted where it went down"""
    d.touch(("down", *xy), ("sleep", 0.9))
    texts = d.screen().texts(where="all")
    d.touch(("up", *xy))
    time.sleep(0.5)
    return texts


@case("T9-60", base="B0-注音九键", mutates="learn")
def t960(d):
    k = d.keys(refresh=True)
    s = d.screen()
    kb = d.need_keyboard()
    tones = sorted((n for n in kb.walk() if n.clickable and n.texts() and n.texts()[0] in "ˉˊˇˋ"), key=lambda n: n.c[1])
    check([n.texts()[0] for n in tones] == list("ˉˊˇˋ"), f"左列 {[n.texts() for n in tones]}")
    slot = k.get("，")
    # the right column found by its scene ids (a build from before 060-render-p2 S8a may have none: fails plainly)
    if not any(n.key for n in kb.walk()):
        check(False, "T9-60 找不到场景键 id（键盘不是引擎画的）")
    # ? ! ~ by their places: each face as punctuation maps it (060-render-p2 §5.3)
    face = {n.key: n.texts()[0] for n in kb.walk() if n.key in ("r2c5", "r3c5", "r4c5") and n.texts()}
    check(slot is not None and len(face) == 3, f"键 {sorted(k)[:40]}，右列 {face}")
    # a tone with nothing typed types nothing
    d.tap(k["ˉ"], after=0.6)
    check(d.preedit() == "" and d.field_text() == "", f"没打字时点 ˉ：码行「{d.preedit()}」，框里「{d.field_text()}」")
    # what each shows is what it types (the user's 「显示和输出一致」): ？ ！ ～ with Chinese punctuation
    for key in ("r2c5", "r3c5", "r4c5"):
        before = d.field_text() or ""
        d.tap(k[key], after=0.6)
        got = d.field_text() or ""
        check(got == before + face[key], f"点 {key}「{face[key]}」：框里「{before}」变成「{got}」")
    d.bksp(3)
    check((d.field_text() or "") == "", f"删掉 ？！～ 后框里「{d.field_text()}」")
    d.taps([k[x] for x in ("ㄇㄋㄜ", "ㄔㄘㄣㄧ", "ˇ", "ㄏㄒㄠㄡ", "ㄏㄒㄠㄡ", "ˇ")], gap=0.35, after=0.6)
    c = d.cands()
    s = d.screen()
    code, strip = d.preedit(s), d.syllables(s)
    faces = [n.texts()[0] for n in d.need_keyboard().walk() if n.clickable and n.texts()]
    check(code == "ㄋㄧˇ ㄏㄠˇ", f"码行「{code}」")
    check(first(c) == "你好", f"候选 {c[:4]}")
    check("分词" in faces and face["r4c5"] in faces, f"打字时的键 {faces}")
    check(strip[:1] == ["ㄋㄧ"] and "ㄇㄧ" in strip and not any(ch.isascii() and ch.isalpha() for x in strip for ch in x), f"音节条 {strip}")
    d.syllable_tap("ㄇㄧ", s)
    taken = d.preedit()
    check(taken.startswith("ㄇㄧ"), f"点 ㄇㄧ 后码行「{taken}」")
    d.bksp(8)
    d.taps([k[x] for x in ("ㄇㄋㄜ", "ㄔㄘㄣㄧ")] + [slot] + [k["ㄏㄒㄠㄡ"]] * 2, gap=0.35, after=0.6)
    c2 = d.cands()
    code2 = d.preedit()
    check(code2 == "ㄋㄧ'ㄏㄠ" and first(c2) == "你好", f"不打声调：码行「{code2}」，候选 {c2[:4]}")
    d.tap(k[","], after=0.6)
    got = d.expect_field("你好，")
    faces = [n.texts()[0] for n in d.need_keyboard().walk() if n.clickable and n.texts()]
    check("分词" not in faces, f"上屏后的键 {faces}")
    group = zhuyin_hold(d, k["ㄇㄋㄜ"])
    check(all(x in group for x in "ㄇㄋㄜ"), f"长按 ㄇㄋㄜ：{group}")
    d.bksp(2)
    tone = zhuyin_hold(d, k["ˉ"])
    check("˙" in tone, f"长按 ˉ：{tone}")
    # ㄓ is zhi and zh's initial alike: once on the bar, taken as the initial a final goes on from
    d.tap(k["ㄓㄗㄢㄦ"], after=0.6)
    s = d.screen()
    zh = d.syllables(s)
    check(zh.count("ㄓ") == 1, f"音节条 {zh}")
    d.syllable_tap("ㄓ", s)
    d.taps([k["ㄕㄙㄤㄨ"], k["ㄖㄥㄩ"]], gap=0.35, after=0.6)
    c3 = d.cands()
    code3 = d.preedit()
    check(code3 == "ㄓㄨㄥ" and first(c3) == "中", f"点 ㄓ 再打 ㄨㄥ：码行「{code3}」，候选 {c3[:4]}")
    d.bksp(4)
    return f"码行 {code}，音节条 {strip[:4]}，点 ㄇㄧ 后 {taken}；不打声调 {code2}，框里「{got}」；ㄓ 条 {zh[:6]}，{code3}"


case("T9-61", needs="jvm:ZhuyinShortcutsUatTest.zhuyinNinesGroupsHaveNoneUntilOneIsSet、ZhuyinShortcutsUatTest.thePageHasZhuyinNinesTabBeforeDachens、ZhuyinKeyboardTest.theSymbolKeysAreNamedForTheirShortcuts、KeyShortcutPagesTest.zhuyinNinesGroupsAreNamedByTheirPlaceThreeARow")(lambda d: None)
