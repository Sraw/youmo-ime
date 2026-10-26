"""uat/03-t9.md (B0-九键). The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import (Blocked, add_im, case, check, first, flick, gear, has, popup,
                 popup_away, popup_pick, popup_slide)


def t9(d, digits, gap=0.35, after=0.6):
    """the nine keys by digit, a pause between taps (0.35 s: two taps on one key are 连按 only where
    a case set it on, at 快 or 中; off, the default, every tap is a key)"""
    k = d.keys(refresh=True)
    d.taps([k[c] for c in digits], gap=gap, after=after)


def column(d, s=None):
    """the left column: punctuation when idle, the syllables while typing"""
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    if not kb:
        return []
    space = s.find(rid="button_space", where="ime")
    y1 = space.b[1] if space else kb.b[3]
    return [n.text for n in sorted(kb.walk(), key=lambda n: n.b[1])
            if n.text and n.b[2] < 200 and kb.b[1] <= n.b[1] and n.b[3] <= y1]


def col_tap(d, text):
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    n = next((n for n in kb.walk() if n.text == text and n.b[2] < 200), None)
    if not n:
        raise Blocked(f"左边一列没有 {text}：{column(d, s)}")
    d.tap(n.c, 0.6)


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


def multi_tap(d, speed):
    """连按选字母 set to [speed] on the 九键拼音 page, from B0-设置; back on the test page in 九键"""
    add_im(d, "九键拼音")
    gear(d, "九键拼音")
    d.tap_text("连按选字母", after=1.0)
    n = next((n for n in d.screen().nodes if n.text.startswith(speed)), None)
    if not n:
        raise Blocked(f"连按选字母里没有「{speed}」")
    d.tap(n.c, 1.0)
    d.open_page("text")
    d.switch_im("九键")


case("T9-09", needs="jvm:T9UatTest.aLongPressedLetterGoesInAsItIs")(lambda d: None)


case("T9-10", needs="jvm:T9UatTest.aDigitKeySwipedDownTypesItsDigit")(lambda d: None)


case("T9-11", needs="jvm:T9UatTest.startOverClearsWhatIsTyped")(lambda d: None)


case("T9-12", needs="jvm:NineKeysUatTest.keysThatMakeASyllableAreRead")(lambda d: None)


case("T9-13", needs="jvm:SettingsPagesUatTest.nineKeysLettersEdited")(lambda d: None)


@case("T9-15", base="B0-九键", mutates="learn")
def t915(d):
    t9(d, "6426")
    d.cands()
    s = d.screen()
    row = next((n for n in s.ime() if n.cls == "HorizontalScrollView"), None)
    if not row:
        raise Blocked("没有拼音行")
    spans, y = d.preedit_glyphs()
    if len(spans) < 2:
        raise Blocked(f"拼音行字形 {spans}")
    x = (spans[0][1] + spans[1][0]) // 2
    d.tap((x, y), 0.6)
    p1 = d.preedit()
    t9(d, "4")
    s = d.screen()
    p2, c = d.preedit(s), d.candidates(s)
    d.space()
    t = d.field_text()
    check("|" in p1, f"点后拼音行 `{p1}`（没有竖线光标）")
    check(p2.replace("|", "") == "ni hao", f"补 4 后拼音行 `{p2}`")
    check(first(c) == "你好", f"候选 {c[:3]}")
    check(t == "你好", f"框里「{t}」")
    return f"`{p1}` → `{p2}` {c[:2]}，框里「{t}」"


case("T9-17", needs="jvm:T9UatTest.aShortSwipeWithGlideOnIsStillTheDigit")(lambda d: None)


def numpad_tap(d, keys):
    """123 opens the number keyboard; tap [keys] on it"""
    d.press("123", after=0.8)
    k = d.keys(refresh=True)
    miss = [ch for ch in keys if ch not in k]
    if miss:
        raise Blocked(f"数字键盘上没有 {miss}")
    d.taps([k[ch] for ch in keys], gap=0.35, after=0.6)


@case("T9-18", base="B0-九键")
def t918(d):
    numpad_tap(d, "23")
    s = d.screen()
    t1, p1 = d.field_text(s), d.preedit(s)
    check(t1 == "23" and not p1, f"数字键盘点 2 3：框里「{t1}」，拼音行 `{p1}`")
    d.clear()
    d.press("abc", after=0.8)   # the number keyboard's ABC: back to the nine keys
    t9(d, "64")
    c = d.cands()
    numpad_tap(d, "1")
    s = d.screen()
    t2, p2 = d.field_text(s), d.preedit(s)
    check(c and t2 == first(c) and not p2, f"打 64 时点数字键盘的 1：候选 {c[:3]}，框里「{t2}」，拼音行 `{p2}`")
    return f"「{t1}」；64 后点 1：「{t2}」"


@case("T9-19", base="B0-设置", mutates=True)
def t919(d):
    multi_tap(d, "中")
    k = d.keys(refresh=True)
    d.step("1 键连按换标点")
    d.taps([k["1"], k["1"]], gap=0.3, after=0.8)
    t = d.field_text()
    check(t == "。", f"快速连点 1 两下后框里「{t}」")
    d.clear()
    d.step("学广告")
    # 4 4 slowly, two keys: 广告 picked, learned
    d.taps([k["4"], k["4"]], gap=1.2, after=0.6)
    c = d.cands(want="广告")
    check(has(c, "广告"), f"慢点 4 4 候选 {c[:8]}")
    d.pick("广告")
    d.clear()
    d.step("快连 4 两下")
    d.taps([k["4"], k["4"]], gap=0.3, after=0.5)
    p, c = d.preedit(), [x.split(" ")[0] for x in d.all_cands()[:4]]
    # a quick second press is h itself, not two keys (gg/hh 简拼, 广告), the learned 广告 too
    check(p.replace("'", "").replace(" ", "") == "h" and "广告" not in c, f"快速连按 4 两下：`{p}` {c}")
    t9(d, "26")
    c2 = d.all_cands()
    hit = [x for x in c2[:12] if x.split(" ")[0] in ("还", "汉", "含", "寒", "喊", "韩", "憨", "函", "汗")]
    check(hit, f"h 后点 2 6 候选 {c2[:10]}（拼音行 `{d.preedit()}`）")
    d.clear()
    d.step("连按字母退格")
    d.taps([k["7"]], after=0.6)
    want = (d.preedit(), d.all_cands()[:6])
    d.taps([k["4"], k["4"]], gap=0.3, after=0.6)
    mid = d.preedit()
    d.tap(d.screen().find(rid="button_backspace", where="ime").c, 0.6)
    got = (d.preedit(), d.all_cands()[:6])
    # Backspace takes the multi-tap letter whole, back to 7 alone
    check(got == want, f"连按 `{mid}` 退格后 {got}，只点 7 时 {want}")
    return f"1 1「{t}」；4 4 `{p}` {c}；26 {c2[:3]}；7 `{mid}` ⌫ → `{got[0]}`"


@case("T9-24", base="B0-九键")
def t924(d):
    k = d.keys(refresh=True)
    d.taps([k["4"], k["4"]], gap=0.15, after=0.6)
    p = d.preedit()
    c = d.all_cands()
    # 连按 off by default: two keys, read by the model
    check(p.replace("'", "").replace(" ", "") != "h", f"拼音行 `{p}`")
    check(any(has(c, w) for w in ("哈哈", "广告", "个个", "好好")), f"候选 {c[:8]}")
    d.clear()
    d.taps([k["1"], k["1"]], gap=0.15, after=0.8)
    t = d.field_text()
    check(t == "，，", f"快速连点 1 两下后框里「{t}」")
    return f"`{p}` {c[:4]}；1 1「{t}」"


case("T9-20", needs="jvm:NineKeysUatTest.emojiOnTheNineKeys")(lambda d: None)


@case("T9-22", base="B0-九键", mutates="learn")
def t922(d):
    t9(d, "64")
    c = [x.split(" ")[0] for x in d.all_cands()]
    if len(c) < 2:
        raise Blocked(f"64 的候选 {c}")
    flick(d, "2")
    time.sleep(0.6)
    t, p = d.field_text() or "", d.preedit()
    check(t == c[1] and not p, f"打字中下滑 2：框里「{t}」、拼音行 `{p}`，不是第二个候选「{c[1]}」")
    d.clear()
    flick(d, "2")
    time.sleep(0.5)
    t2 = d.field_text() or ""
    check(t2 == "2", f"没在打字时下滑 2：框里「{t2}」")
    return f"64 → 下滑 2 上屏「{t}」；空框下滑 2 →「{t2}」"


@case("T9-23", base="B0-九键", mutates="learn")
def t923(d):
    out = []
    # the second half on other keys: the first half's pick teaches a word for 64
    for how, keys, k, i in (("长按选 2", "64", "2", 0), ("滑 1", "94", "1", 0)):
        t9(d, keys)
        c = [x.split(" ")[0] for x in d.all_cands()]
        if len(c) < 2:
            raise Blocked(f"{keys} 的候选 {c}")
        if k == "2":
            popup_pick(d, "2", "2")
        else:
            flick(d, "1")
        time.sleep(0.6)
        t, p = d.field_text() or "", d.preedit()
        want = c[i] + ("2" if k == "2" else "")
        check(t == want and not p, f"打字中{how}：框里「{t}」、拼音行 `{p}`，不是「{want}」")
        out.append(f"{keys} {how} →「{t}」（候选 {c[:3]}）")
        d.clear()
    return "；".join(out)


case("T9-25", needs="jvm:NineKeysUatTest.aCharacterErasedIsNoWordBefore")(lambda d: None)


def symbol_rows(items, digit):
    """the popup's items above its digit's row, and those on it"""
    row = next((n for n in items if n.text == digit), None)
    if not row:
        return [], []
    above = [n.text for n in items if n.c[1] < row.b[1]]
    on = [n.text.lower() for n in items if row.b[1] <= n.c[1] <= row.b[3]]
    return above, on


@case("T9-27", base="B0-九键", mutates="learn")
def t927(d):
    items, xy = popup(d, "2")
    popup_away(d, xy)
    time.sleep(0.4)
    t, p = d.field_text(), d.preedit()
    check(not t and not p, f"松手不选，框里却有「{t}」，拼音行 `{p}`")
    above, on = symbol_rows(items, "2")
    check({"2", "a", "b", "c"} <= set(on), f"长按 2 下面一行 {on}")
    check("@" in above and ("！" in above or "!" in above), f"长按 2 上面一行 {above}")
    d.clear()
    t9(d, "64")
    items, xy = popup(d, "2")
    tgt = next((n for n in items if n.text in ("！", "!")), None)
    if not tgt:
        popup_away(d, xy)
        raise Blocked(f"长按 2 弹出 {[n.text for n in items]}")
    popup_slide(d, xy, tgt)
    t, p = d.field_text(), d.preedit()
    check(t == "你！" and not p, f"框里「{t}」，拼音行 `{p}`")
    return f"上面一行 {above}，下面 {on}；选「{tgt.text}」后「{t}」"


case("T9-29", needs="jvm:SettingsPagesUatTest.nineKeysLongPressesEdited")(lambda d: None)


def to_english(d):
    """九键拼音 -> 九键英文: its 中/英 key"""
    n = d.key_node("中/英")
    if not n:
        raise Blocked("九键上没有 中/英 键")
    d.tap(n.c, 1.0)
    d._keys = None


case("T9-30", needs="jvm:KeyboardUatTest.nineKeysLanguageKeySwitchesToNineKeysEnglishAndBack")(lambda d: None)


def shift_png(d):
    n = d.screen().find(rid="button_caps", where="ime")
    if not n:
        raise Blocked("没有 ⇧ 键")
    return d.png().crop(n.b), n.b


@case("T9-31", base="B0-九键", page="sentence", mutates="learn", visual=True)
def t931(d):
    # ⇧'s lit state is not in the dump (no stateDescription), but the keys' letters follow it (GHI
    # lit, ghi not): read at the sentence's start and after the first word; shots for the eye
    to_english(d)
    time.sleep(0.5)
    def labels():
        return [n.text for n in d.screen().ime() if n.text.lower() == "ghi"]
    lit_labels = labels()
    box = shift_png(d)[1]
    d.shot((0, box[1] - 20, 1080, box[3] + 20), "句首：⇧ 亮着")
    out = []
    for how in ("auto", "once", "lock"):
        if how == "once":
            d.press("shift")
        elif how == "lock":
            k = d.keys(refresh=True)["shift"]
            d.taps([k, k], gap=0.12, after=0.4)
        t9(d, "43556" if how == "auto" else "46")
        d.press("space", after=0.6)
        out.append((d.field_text() or "").split(" ")[-2])
        if how == "auto":
            unlit_labels = labels()
            d.shot((0, box[1] - 20, 1080, box[3] + 20), "第一个词后：⇧ 不亮")
    check(lit_labels == ["GHI"], f"句首键上是 {lit_labels}（⇧ 没自动亮）")
    check(unlit_labels == ["ghi"], f"第一个词后键上是 {unlit_labels}（⇧ 没灭）")
    check(out[0] == "Hello", f"句首：{out[0]}")
    check(out[1][:1].isupper() and out[1][1:].islower(), f"点一下 ⇧：{out[1]}")
    check(out[2].isupper() and len(out[2]) > 1, f"锁定：{out[2]}")
    return f"{out}"


@case("T9-32", base="B0-九键", mutates="learn")
def t932(d):
    to_english(d)
    t9(d, "36618")
    c = [x.split(" ")[0] for x in d.all_cands()]
    d.press("space", after=0.6)
    t1 = d.field_text() or ""
    d.clear()
    k = d.keys(refresh=True)["1"]
    d.taps([k, k], gap=0.15, after=0.8)
    t2 = d.field_text() or ""
    check(t1 == "don't ", f"36618 空格：「{t1}」（候选 {c[:4]}）")
    check(t2 == ",", f"连按 1 两下：「{t2}」")
    return f"「{t1}」；「{t2}」"


@case("T9-33", base="B0-设置", mutates=True)
def t933(d):
    add_im(d, "九键拼音")
    gear(d, "九键拼音")
    d.choose("九键英文打法", "连按")
    d.open_page("text")
    d.switch_im("九键")
    to_english(d)
    k = d.keys(refresh=True)
    for digit, n in (("4", 2), ("3", 2), ("5", 3), ("5", 3), ("6", 3)):
        d.taps([k[digit]] * n, gap=0.12, after=1.0)
    t, c = d.field_text() or "", d.cands(timeout=1)
    check(t == "hello", f"框里「{t}」")
    check(not c, f"有候选 {c[:4]}")
    return f"「{t}」"


case("T9-34", needs="jvm:T9UatTest.anEmailFieldsColumn")(lambda d: None)


@case("T9-35", base="B0-九键")
def t935(d):
    to_english(d)
    d.press("globe", after=1.0)
    d._keys = None
    one = d.im_label()
    full1 = "q" in d.keys(refresh=True)
    d.press("globe", after=1.0)
    d._keys = None
    second = d.im_label()
    k = d.keys(refresh=True)
    check("拼音" in one and "九键" not in one and full1, f"九键英文点地球键：{one}，全键盘 {full1}")
    check("英文" in second and "q" in k and d.key_node("中/英") is None, f"再点：{second}，键 {sorted(k)[:12]}")
    return f"九键英文 → {one} → {second}（全键盘）"


@case("T9-36", base="B0-九键", mutates="learn")
def t936(d):
    to_english(d)
    out = []
    # each ending: what it does, what it leaves behind the word (English's space after a word is
    # put behind the mark)
    ends = [("enter", lambda: d.press("enter", after=0.6), ""),
            (",", lambda: col_tap(d, ","), ", "),
            ("0", lambda: t9(d, "0"), "0"),
            ("长按 4", lambda: (popup_pick(d, "4", "4"), time.sleep(0.4)), "4")]
    for end, act, suffix in ends:
        t9(d, "4663")
        c = [x.split(" ")[0] for x in d.all_cands()]
        if not c:
            raise Blocked("4663 没有候选")
        act()
        t, p = d.field_text() or "", d.preedit()
        want = c[0] + suffix
        check(t == want and not p, f"{end}：框里「{t}」，拼音行 `{p}`，要「{want}」")
        out.append(t)
        d.clear()
    return f"{out}"


case("T9-37", needs="jvm:T9UatTest.nineKeysEnglishStaysOverARotation")(lambda d: None)


case("T9-38", needs="jvm:T9UatTest.anAddressFieldsColumn")(lambda d: None)


case("T9-40", needs="jvm:NineKeysUatTest.lettersPinnedSpellAWordNotInTheDictionaryAndItIsKept")(lambda d: None)


@case("T9-44", base="B0-九键", page="nosuggest", mutates="learn")
def t944(d):
    to_english(d)
    k = d.keys(refresh=True)
    d.taps([k["4"]] * 2, gap=0.12, after=1.0)
    c = d.cands(timeout=1)
    t = d.field_text() or ""
    check(t == "h", f"不要提示框里「{t}」")
    check(not c, f"不要提示框里有候选 {c[:4]}")
    return f"「{t}」，没有候选"


case("T9-45", needs="jvm:T9UatTest.aPasswordFieldIsTheFullKeyboard")(lambda d: None)


case("T9-46", needs="jvm:NineKeysUatTest.switchingWhileTypingCommitsWhatIsTyped")(lambda d: None)


case("T9-48", needs="jvm:T9UatTest.theLongPressPopupIsSmall")(lambda d: None)


@case("T9-49", base="B0-九键", page="nosuggest", mutates="learn")
def t949(d):
    to_english(d)
    k = d.keys(refresh=True)
    d.taps([k["4"], k["shift"], k["4"]], gap=0.15, after=1.0)
    t = d.field_text() or ""
    check(t == "gG", f"4 ⇧ 4 后框里「{t}」")
    return f"「{t}」"


case("T9-56b", needs="jvm:NineKeysUatTest.aWordTypedTwiceInFullDigitsGoesThirdUnderAPart")(lambda d: None)


case("T9-56c", needs="jvm:NineKeysUatTest.multiTapInFullLiftsAWordUnderPartDigits")(lambda d: None)


case("T9-56d", needs="jvm:NineKeysUatTest.digitsInFullLiftAWordUnderPartMultiTap")(lambda d: None)
