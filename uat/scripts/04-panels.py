"""uat/04-panels.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import Blocked, case, check


case("PAN-01", needs="jvm:KeyboardUatTest.numberPadAndBack")(lambda d: None)


case("PAN-02", needs="jvm:PanelUatTest.numberAndPhoneFieldsOpenTheNumberPad")(lambda d: None)


case("PAN-03", needs="jvm:PanelUatTest.numberPadSymbolsKeyOpensThePanel")(lambda d: None)


case("PAN-04", needs="jvm:PanelUatTest.symbolPanelTypesAndCloses")(lambda d: None)


case("PAN-05", needs="jvm:PanelUatTest.lockedPanelStaysForSeveral")(lambda d: None)


case("PAN-07", needs="jvm:PanelUatTest.recentHasTheLastSymbolFirst")(lambda d: None)


case("PAN-08", needs="jvm:PanelUatTest.panelsNumbersKeyAndBack")(lambda d: None)


case("PAN-09", needs="jvm:PanelUatTest.emojiPanelTabsAndTyping")(lambda d: None)


case("PAN-10", needs="jvm:PanelUatTest.theDefaultSkinTone")(lambda d: None)


case("PAN-11", needs="jvm:PanelUatTest.emojiPanelBackToLetters")(lambda d: None)


case("PAN-12", needs="jvm:PanelUatTest.emojiSearchByPinyin")(lambda d: None)


case("PAN-14", needs="jvm:PanelUatTest.numberPadSpaceSlidesTheCursor")(lambda d: None)


case("PAN-15", needs="jvm:PanelUatTest.ownFieldsKeepNoRecentEmoji")(lambda d: None)


case("PAN-16", needs="jvm:FieldPadUatTest.dateAndTimeFieldsGetTheirSeparators")(lambda d: None)


case("PAN-17", needs="jvm:FieldPadUatTest.phoneFieldsGetPlusStarAndHash")(lambda d: None)


case("PAN-18", needs="jvm:FieldPadUatTest.pinFieldsGetDigitsOnly")(lambda d: None)


case("PAN-19", needs="jvm:FieldPadUatTest.decimalAndSignedFieldsKeepTheDotAndTheMinus")(lambda d: None)


case("PAN-20", needs="jvm:FieldPadUatTest.leavingANumberFieldGoesBackToTheLetters")(lambda d: None)


case("PAN-21", needs="jvm:PanelUatTest.aSymbolHeldShowsItsBubble")(lambda d: None)
case("PAN-24", needs="jvm:SmallBoardsSceneGesturesTest.thePickersFullStopShowsWhatItTypes")(lambda d: None)


def ime(d, w, after=0.6):
    """a node of the keyboard by its text or description"""
    s = d.screen()
    n = s.find(text=w, where="ime") or s.find(desc=w, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{w}」")
    d.tap(n.c, after)
    return n


def ime_texts(d):
    return [n.text or n.desc for n in d.screen().ime() if n.text or n.desc]


@case("PAN-22", visual=True)
def pan22(d):
    ime(d, "表情")
    ime(d, "按拼音搜索表情")
    for c in "xiao":
        ime(d, c, after=0.3)
    time.sleep(0.6)
    d.shot("kb", "表情搜索打 xiao")
    t = ime_texts(d)
    check("😄" in t, f"搜索行 {t[:12]}")
    ime(d, "返回")
    check("按拼音搜索表情" in ime_texts(d), "返回后不在表情列表")
    return "xiao 出 😄，返回回到表情列表"


@case("PAN-23", visual=True)
def pan23(d):
    ime(d, "符")
    time.sleep(0.6)
    d.shot("kb", "符号面板的格子")
    ime(d, "，")
    t = d.field_text() or ""
    check(t.endswith("，"), f"框里「{t}」")
    # the emoji's cells: colour emoji, centred in their cells (S6 review)
    # the symbol panel went back to the letters with the ，: the bar's 表情 from there
    ime(d, "表情")
    time.sleep(0.6)
    d.shot("kb", "表情面板的格子")
    # the 颜文字's: a long one whole, not cut at its cell's sides (rules B §8a)
    ime(d, ":-)")
    time.sleep(0.6)
    long = "(｡>﹏<｡)ﾉﾞ✧*。"
    for _ in range(12):
        s = d.screen()
        n = s.find(desc=long, where="ime")
        if n:
            break
        # up a third of the panel, at its middle: no screen size assumed
        w = s.find(rid="input_window", where="ime")
        if not w:
            raise Blocked("颜文字面板不在")
        x, y0, y1 = (w.b[0] + w.b[2]) // 2, w.b[3] - (w.b[3] - w.b[1]) // 6, w.b[1] + (w.b[3] - w.b[1]) // 2
        d.swipe((x, y0), (x, y1), 300, 0.6)
    check(n is not None, f"颜文字里找不到 {long}")
    d.shot("kb", "颜文字长字")
    return f"框里「{t}」，表情和颜文字格子见图"
