"""uat/02-keyboard.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import (Blocked, GRID_BOTTOM, GRID_TOP, case, check, emoji_tab_xy, flick, glide_on,
                 glide_shot, has, open_url, popup_pick, popup_slide)


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


case("KB-03", needs="jvm:KeyboardUatTest.swipingALetterDownTypesItsCorner")(lambda d: None)


case("KB-04", needs="jvm:KeyboardUatTest.aLongPressLetGoTypesTheCorner")(lambda d: None)


case("KB-05", needs="jvm:KeyboardUatTest.aLongPressesChoicesTypeWhereTheFingerIs")(lambda d: None)


case("KB-06", needs="jvm:KeyboardUatTest.cornersShowChinesePunctuationWhileChinese")(lambda d: None)


case("KB-07", needs="jvm:KeysUatTest.shiftCapitalisesTheNextLetter")(lambda d: None)


case("KB-08", needs="jvm:KeyboardUatTest.shiftTwiceLocksCaps")(lambda d: None)


case("KB-09", needs="jvm:KeyboardUatTest.backspaceOnce")(lambda d: None)


@case("KB-10", mutates="learn")
def kb10(d):
    d.tap_text("表情", where="ime", scroll=False, after=1.0)
    d.tap(emoji_tab_xy(2), 0.8)   # the 人物 tab (icons, no text)
    thumb = None
    for _ in range(30):
        s = d.screen()
        thumb = next((n for n in s.ime() if n.text == "👍" and GRID_TOP < n.c[1] < GRID_BOTTOM), None)
        if thumb:
            break
        d.swipe((540, 2000), (540, 1640), 900, after=0.3)
    if not thumb:
        raise Blocked("人物里找不到 👍")
    d.touch(("down", *thumb.c), ("sleep", 0.9))
    s = d.screen()
    tone = next((n for n in s.ime() if n.text == "👍🏽"), None)
    if not tone:
        d.touch(("up", *thumb.c))
        raise Blocked(f"长按 👍 没有弹出 👍🏽：{[n.text for n in s.ime() if n.text.startswith('👍')]}")
    # the popup's own keys (the tones in a row above 👍): its focus is read from them, not popup()'s
    row = [n for n in s.ime() if n.text.startswith("👍") and n.b[3] <= thumb.b[1] + 10]
    popup_slide(d, thumb.c, tone, items=row, key="👍")
    t = d.field_text()
    if t != "👍🏽":
        raise Blocked(f"前提没到：框里「{t}」")
    abc = d.screen().find(text="ABC", where="ime")
    if abc:
        d.tap(abc.c, 0.8)
    d.bksp()
    t = d.field_text()
    check(t == "", f"退格后框里「{t}」（{[hex(ord(c)) for c in t or '']}）")
    return "整个表情删掉，框里为空"


case("KB-11", needs="jvm:KeyboardUatTest.holdingBackspaceRepeatsFasterTillLetGo")(lambda d: None)


case("KB-12", needs="jvm:KeyboardUatTest.swipingBackspaceLeftSelectsAndDeletes")(lambda d: None)


case("KB-13", needs="jvm:KeyboardUatTest.swipingBackspaceWhileTypingClearsThePinyin")(lambda d: None)


@case("KB-14", mutates="learn")
def kb14(d):
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    d.press(",")
    d.press(".")
    t = d.field_text()
    check(t == "你好，。", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-15")
def kb15(d):
    flick(d, "q")
    d.press(",")
    t = d.field_text()
    check(t == "1，", f"框里是「{t}」")
    return f"「{t}」"


case("KB-16", needs="jvm:KeyboardUatTest.holdingTheCommaOffersTwoRows")(lambda d: None)


case("KB-17", needs="jvm:KeyboardUatTest.spaceSlidesMoveTheCursor")(lambda d: None)


case("KB-18", needs="jvm:KeysUatTest.spaceCommitsTheFirstCandidate")(lambda d: None)


case("KB-19", needs="jvm:KeysUatTest.returnCommitsTheLettersAsTyped")(lambda d: None)


case("KB-20", needs="jvm:KeyboardUatTest.searchFieldsReturnSearches")(lambda d: None)


case("KB-21", needs="jvm:KeyboardUatTest.nextFieldsReturnGoesOn")(lambda d: None)


case("KB-22", needs="jvm:KeyboardUatTest.addressFieldHasSlashDotAndDotCom")(lambda d: None)


case("KB-23", needs="jvm:KeyboardUatTest.holdingDotComOffersMore")(lambda d: None)


case("KB-24", needs="jvm:KeysUatTest.anEmailFieldsLettersBeforeAtAreCommittedAsTyped")(lambda d: None)


@case("KB-25", page="url", mutates="learn")
def kb25(d):
    flick(d, "w")
    popup_pick(d, "h", "/")
    t = d.field_text()
    check(t == "2/", f"框里是「{t}」")
    return f"「{t}」"


case("KB-26", needs="jvm:PairsUatTest.aBracketComesInPairsTheCursorBetween")(lambda d: None)


case("KB-27", needs="jvm:PairsUatTest.theClosingBracketStepsOverTheOneThere")(lambda d: None)


case("KB-28", needs="jvm:PairsUatTest.backspaceRightAfterDeletesThePair")(lambda d: None)


case("KB-30", needs="jvm:KeyboardUatTest.aKeyHeldShowsItLarger")(lambda d: None)


@case("KB-31", page="chat", visual=True)
def kb31(d):
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


@case("KB-33", base="B0-设置", mutates=True, visual=True)
def kb33(d):
    glide_on(d, "英文")
    d.step("打着 ok 时划 hello")
    d.type("ok")
    d.cands()
    k = d.keys(refresh=True)
    pts = [k[c] for c in "helo"]
    glide_shot(d, pts, "划 h-e-l-o，抬手前")
    t1, c = d.field_text(), d.candidates()
    check(t1 == "ok hello ", f"第一次后框里「{t1}」")
    d.step("再划 world")
    d.glide("world")
    t2 = d.field_text()
    check(t2 == "ok hello world ", f"第二次后框里「{t2}」")
    d.step("换候选")
    c2 = d.cands()
    if len(c2) < 2:
        raise Blocked(f"划 world 后候选只有 {c2[:3]}")
    w = d.pick(2).split(" ")[0]
    t3 = d.field_text()
    check(t3 == f"ok hello {w} " and w != "world", f"点「{w}」后框里「{t3}」")
    d.step("划 hello 后退格")
    d.glide("helo")
    d.bksp()
    t4 = d.field_text()
    check(t4 == t3, f"再划 hello 后退格，框里「{t4}」（应回到「{t3}」）")
    d.step("划 hello 后打逗号")
    d.glide("helo")
    d.press(",")
    t5 = d.field_text()
    check(t5 == f"{t3}hello, ", f"打逗号后框里「{t5}」")
    return f"「{t1}」→「{t2}」→「{t3}」；退格删掉整个 hello；「{t5}」，候选 {c[:3]}"

case("KB-35", needs="jvm:KeyboardUatTest.tapsShortSwipesAndHoldsAreNoGlide")(lambda d: None)


@case("KB-36", base="B0-设置", mutates=True)
def kb36(d):
    glide_on(d, page="password")
    d.switch_im("英文")
    d.glide("helo")
    t1 = d.field_text() or ""
    open_url(d, "url")
    d.switch_im("英文")
    d.glide("helo")
    t2 = d.field_text() or ""
    open_url(d, "email")
    d.switch_im("英文")
    d.glide("helo")
    t3 = d.field_text() or ""
    check("hello" not in t1, f"密码框里「{t1}」")
    check("hello" not in t2, f"网址框里「{t2}」")
    check("hello" not in t3, f"邮箱框里「{t3}」")
    return f"密码框「{t1}」，网址框「{t2}」，邮箱框「{t3}」，都没有整词"


case("KB-38", needs="jvm:KeyboardUatTest.holdingTheSpaceSetToNothingIsOneSpace")(lambda d: None)


case("KB-39", needs="jvm:KeyboardUatTest.shiftChangesAllThreeRows")(lambda d: None)


case("KB-41", needs="jvm:KeyboardUatTest.aFirstMoveSidewaysAfterSlippingOffKeepsThePopup")(lambda d: None)
