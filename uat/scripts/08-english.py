"""uat/08-english.md (B0-英文). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import block_candidate, case, check, gear, glide_on


def en_settings(d):
    gear(d, "英文")


def sym_comma(d):
    """the , of the bottom row (English)"""
    d.press(",")


case("EN-01", needs="jvm:OtherInputMethodsUatTest.englishTheWordTypedIsFirst")(lambda d: None)


@case("EN-02", base="B0-英文", mutates="learn")
def en02(d):
    d.type("teh")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(c[:2] == ["the", "teh"], f"候选 {c[:4]}")
    check(t == "the ", f"框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


case("EN-03", needs="jvm:EnglishUatTest.theApostropheAdded")(lambda d: None)


case("EN-04", needs="jvm:OtherInputMethodsUatTest.englishBackspaceRightAfterACorrectionUndoesItForGoodUntilRestored、SettingsPagesUatTest.anEnglishWordNoLongerCorrectedIsMarkedAndCorrectedAgainOnATap")(lambda d: None)


case("EN-06", needs="jvm:EnglishUatTest.aMarkTakesTheSpaceAfterIt")(lambda d: None)


case("EN-07", needs="jvm:EnglishUatTest.twoSpacesAFullStop")(lambda d: None)


case("EN-08", needs="jvm:EnglishUatTest.theNextWordPredicted")(lambda d: None)


case("EN-09", needs="jvm:EnglishCapsUatTest.aSentencesFirstLetterIsACapital")(lambda d: None)


case("EN-10", needs="jvm:EnglishCapsUatTest.anAddressFieldGetsNoCapitals")(lambda d: None)


case("EN-11", needs="jvm:EnglishUatTest.aCorrectionTappedBackIntoAndUndoneIsNotMadeAgain")(lambda d: None)


case("EN-12", needs="jvm:EnglishUatTest.aWordAddedIsNotCorrected")(lambda d: None)


case("EN-13", needs="jvm:EnglishUatTest.anEmailFieldNeitherSuggestsNorCorrects")(lambda d: None)


case("EN-14", needs="jvm:EnglishUatTest.aFieldAskingNoSuggestionsGetsNone")(lambda d: None)


case("EN-15", needs="jvm:EnglishUatTest.suggestionsTurnedOff")(lambda d: None)


@case("EN-19", base="B0-设置", mutates=True)
def en19(d):
    glide_on(d, "英文", "nosuggest")
    d.glide("helo")
    t1 = d.field_text()
    en_settings(d)
    d.switch("单词提示", False)
    d.open_page("text")
    d.switch_im("英文")
    d.glide("helo")
    t2 = d.field_text()
    d.clear()
    d.type("abc")
    d.glide("helo")
    t3 = d.field_text()
    d.bksp()
    t4 = d.field_text()
    d.glide("helo")
    sym_comma(d)
    s = d.screen()
    t5, c5 = d.field_text(s), d.candidates(s)
    check(t1 == "hello ", f"不要提示框里划出「{t1}」")
    check(t2 == "hello ", f"关掉单词提示后划出「{t2}」")
    check(t3 == "abc hello ", f"打 abc 再划「{t3}」")
    check(t4 == "abc", f"退格后「{t4}」")
    check(t5 == "abc hello, " and not c5, f"再划接逗号「{t5}」，候选 {c5[:3]}")
    return f"「{t1}」「{t2}」「{t3}」「{t4}」「{t5}」"


case("EN-21", needs="jvm:EnglishCapsUatTest.aGlideTakesShiftWhereTheFieldAsksACapitalOrItIsPressed、EnglishUatTest.aGlideTakesShiftAWordsOwnCapitalAndI")(lambda d: None)


case("EN-26", needs="jvm:EnglishUatTest.theWordAsTypedPickedCountsAsACorrectionTakenBack、SettingsPagesUatTest.anEnglishWordNoLongerCorrectedIsMarkedAndCorrectedAgainOnATap")(lambda d: None)


@case("EN-27", base="B0-英文", mutates=True)
def en27(d):
    d.type("th")
    c = d.cands()
    check("the" in c, f"th 的候选 {c[:6]}")
    block_candidate(d, "the", "长按英文「the」的菜单")
    d.clear()
    d.type("th")
    again = d.cands()
    d.clear()
    check("the" not in again, f"屏蔽后 th 还有 the：{again[:6]}")
    d.type("thw")
    d.cands()
    d.space()
    t = d.field_text()
    check(t != "the ", f"thw 空格后框里「{t}」")
    return f"屏蔽前 {c[:4]}，屏蔽后 {again[:4]}，thw 空格「{t}」（设置里恢复由 JVM 管）"


case("EN-28", needs="jvm:EnglishBlockOffersTest")(lambda d: None)
