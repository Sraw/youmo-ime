"""uat/08-english.md (B0-英文). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import Blocked, case, check, gear, glide_on


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


@case("EN-04", base="B0-英文", mutates="learn", clean=True)
def en04(d):
    d.type("teh")
    d.cands()
    d.space()
    # read once the input method is idle, not the moment after the key: under load the field
    # was still empty there
    t0 = d.expect_field("the ", msg="第一次空格后「{got}」")
    d.bksp()
    t1 = d.expect_field("teh", msg="退格后「{got}」")
    d.space()
    d.type("teh")
    d.cands()
    d.space()
    t2 = d.expect_field(lambda t: t.endswith("teh "), msg="最后框里「{got}」")
    d.clear()
    d.step("英文词库里恢复自动改")
    en_settings(d)
    d.tap_text("英文词库", after=1.2)
    n = d.screen().find(text="teh")
    row = n.row().texts() if n else []
    check(any("不再自动改" in x for x in row), f"英文词库里 teh 那行 {row[:4]}")
    d.tap(n.c, 0.8)
    d.tap_text("恢复自动改", where="all", scroll=False)
    d.open_page("text")
    d.switch_im("英文")
    d.clear()
    d.type("teh")
    d.cands()
    d.space()
    t3 = d.expect_field("the ", msg="恢复后 teh 空格：「{got}」")
    return f"「{t0}」→退格「{t1}」→「{t2}」；恢复后「{t3}」"


case("EN-06", needs="jvm:EnglishUatTest.aMarkTakesTheSpaceAfterIt")(lambda d: None)


case("EN-07", needs="jvm:EnglishUatTest.twoSpacesAFullStop")(lambda d: None)


case("EN-08", needs="jvm:EnglishUatTest.theNextWordPredicted")(lambda d: None)


case("EN-09", needs="jvm:EnglishCapsUatTest.aSentencesFirstLetterIsACapital")(lambda d: None)


case("EN-10", needs="jvm:EnglishCapsUatTest.anAddressFieldGetsNoCapitals")(lambda d: None)


@case("EN-11", base="B0-英文", mutates="learn", clean=True)
def en11(d):
    # 前提: a made-up word the space changes (youmo is not changed on this build: the next ones)
    w = t0 = None
    for cand in ("youmo", "blorft", "sraww", "kwazy", "fcitxy"):
        d.type(cand)
        d.cands()
        d.space()
        t0 = d.field_text()
        d.clear()
        if t0.strip() != cand:
            w = cand
            break
    if not w:
        raise Blocked("几个自造词空格都不改，找不到会被改的词")
    for _ in range(2):
        d.type(w)
        d.cands()
        d.pick(w)
        d.clear()
    d.type(w)
    d.cands()
    d.space()
    t = d.field_text()
    check(t == w + " ", f"「{w}」最后框里「{t}」（一开始被改成「{t0}」）")
    return f"「{w}」一开始改成「{t0.strip()}」，两次后「{t}」"


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


@case("EN-21", base="B0-设置", mutates=True)
def en21(d):
    glide_on(d, "英文", "sentence")
    d.clear()
    d.glide("helo")
    d.glide("london")
    d.press(".")
    d.space()
    d.glide("im")
    t = d.field_text() or ""
    check(t == "Hello London. I'm ", f"框里「{t}」")
    # mid-sentence, Shift off: still I'm; and I in a field that does not capitalise
    d.clear()
    d.type("so")
    d.space()
    d.glide("im")
    t2 = d.field_text() or ""
    check(t2 == "So I'm ", f"句中划 i-m 后框里「{t2}」")
    d.open_page("nosuggest")
    d.clear()
    d.type("ok")
    d.space()
    d.glide("im")   # a one-key glide is a tap: i-m is the shortest path that glides
    t3 = d.field_text() or ""
    check(t3 == "ok I'm ", f"不自动大写的框里划 i-m 后「{t3}」")
    # Shift by hand where nothing capitalises: the glide takes it
    d.clear()
    d.press("shift")
    d.glide("helo")
    t4 = d.field_text() or ""
    check(t4 == "Hello ", f"点 ⇧ 后划 hello：「{t4}」")
    return f"「{t}」；「{t2}」；「{t3}」；「{t4}」"


@case("EN-26", base="B0-英文", mutates="learn", clean=True)
def en26(d):
    # docs/other-input-methods.md: picking the word as typed over its correction counts as taking it back
    d.type("teh")
    c = [x.split(" ")[0] for x in d.cands(want="teh")]
    if "teh" not in c:
        raise Blocked(f"teh 的候选里没有原样的 teh：{c[:5]}")
    d.pick("teh")
    if not d.field_text().endswith(" "):   # picking adds the space itself; a second one is ". "
        d.space()
    d.type("teh")
    d.cands()
    d.space()
    t = d.field_text()
    d.clear()
    # the choice recorded where the user sees it (D11): 英文词库's 不再自动改的词
    en_settings(d)
    d.tap_text("英文词库", after=1.2)
    # a word's row says 不再自动改 (english_word_learned) beside it
    n = d.screen().find(text="teh")
    row = n.row().texts() if n else []
    check(t.endswith("teh teh "), f"点过一次原样的 teh 后，teh 空格：「{t}」")
    check(any("不再自动改" in x for x in row), f"英文词库里 teh 那行 {row[:4]}")
    return f"「{t}」；英文词库 {row[:3]}"
