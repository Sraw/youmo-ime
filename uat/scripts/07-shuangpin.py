"""uat/07-shuangpin.md (B0-双拼). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

import time
from uat import (Blocked, Fail, add_im, case, check, choose, first, gear, glide_shot, has, home, key_texts, grouped_keys,
                 has_keyboard_code, menu_tap, pinyin_keyboard, popup, popup_away, popup_slide, shuangpin_keyboard, space_share)


def scheme(d, name, add=True):
    """B0-双拼 with this 方案: set in 双拼's settings, back on the page"""
    if add:
        add_im(d, "双拼")
    gear(d, "双拼")
    choose(d, "双拼方案", name)
    d.open_page("text")
    d.switch_im("双拼")


def semi_key(d, s=None):
    """the ; key right of l (labelled 「；」 while 双拼 is on), or None"""
    return d.key_node(";", s) or d.key_node("；", s)


case("SP-01", needs="jvm:OtherInputMethodsUatTest.shuangpinZiranmaByDefault")(lambda d: None)


case("SP-02", needs="jvm:ShuangpinUatTest.xiaohe")(lambda d: None)


@case("SP-03", base="B0-设置", mutates=True, visual=True)
def sp03(d):
    scheme(d, "微软")
    s = d.screen()
    k, l = semi_key(d, s), d.key_node("l", s)
    if not k:
        raise Fail(f"微软方案 l 右边没有 ; 键：l 那排 {key_texts(d, 'l', s)}")
    check(l and k.c[0] > l.c[0] and abs(k.c[1] - l.c[1]) < 20, f"; 键不在 l 右边：; {k.c}，l {l.c if l else None}")
    d.shot("kb", "微软：A、L 与同排别的键一样宽，字在键中间")
    d.type("x")
    d.tap(k.c, 0.6)
    s = d.screen()
    c, t = d.candidates(s), d.field_text(s)
    check(";" not in (t or "") and "；" not in (t or ""), f"打出了分号：框里「{t}」")
    check(has(c, "性") or has(c, "星") or has(c, "行") or has(c, "形"), f"候选 {c[:6]}（拼音行 `{d.preedit(s)}`）")
    got = f"微软：`{d.preedit(s)}` {c[:4]}"
    d.clear()
    # where ; can't be a final it is the punctuation key: 「；」, after the first candidate
    d.tap(k.c, 0.6)
    t = d.field_text() or ""
    check(t == "；", f"空框点 ; 后框里「{t}」，不是「；」")
    d.clear()
    d.type("ni")
    d.tap(k.c, 0.6)
    t = d.field_text() or ""
    check(t.endswith("；") and len(t) == 2 and ";" not in t, f"ni 后点 ; 框里「{t}」，不是「你；」这样")
    got += f"；空框「；」，ni;「{t}」"
    d.clear()
    return got


case("SP-04", needs="jvm:ShuangpinUatTest.aSyllableWithNoInitial")(lambda d: None)


case("SP-05", needs="jvm:ShuangpinUatTest.twoKeysNoSyllableAreInitials")(lambda d: None)


case("SP-07", needs="jvm:ShuangpinUatTest.aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn")(lambda d: None)


@case("SP-09", base="B0-设置", mutates=True, visual=True)
def sp09(d):
    for im in ("双拼", "五笔", "九键拼音"):
        add_im(d, im)
    home(d, "通用输入")
    d.switch("滑行输入", True)
    d.open_page("text")
    seen = []

    d.step("双拼")
    d.switch_im("双拼")
    k = d.keys(refresh=True)
    glide_shot(d, [k[c] for c in "nihk"], "双拼：划 n-i-h-k，抬手前")
    p = d.preedit()
    c = d.all_cands()
    check(p.replace("'", " ") == "ni hao", f"拼音行 `{p}`")
    check(has(c, "你好"), f"候选 {c[:5]}")
    seen.append(f"双拼 `{p}` {c[:2]}")
    d.clear()

    d.step("五笔")
    d.switch_im("五笔")
    k = d.keys(refresh=True)
    glide_shot(d, [k[c] for c in "wqiy"], "五笔：划 w-q-i-y，抬手前")
    s = d.screen()
    p, c, t = d.preedit(s), d.candidates(s), d.field_text(s)
    # a code with one candidate may commit at once: 你 in the box is the same result
    check((p == "wqiy" and first(c) == "你") or t == "你", f"`{p}` {c[:3]} 框里「{t}」")
    seen.append(f"五笔 `{p}` {c[:2]}「{t}」")
    d.clear()

    d.step("九键拼音")
    d.switch_im("九键")
    k = d.keys(refresh=True)
    glide_shot(d, [k[c] for c in "6426"], "九键：划 6-4-2-6，抬手前")
    p = d.preedit()
    c = d.all_cands()
    check(p and not any(ch.isdigit() for ch in p), f"拼音行 `{p}`（应是字母读出的拼音）")
    check(has(c, "你好"), f"`{p}` 候选 {c[:8]}")
    seen.append(f"九键 `{p}` {c[:2]}")
    return "；".join(seen)


case("SP-13b", needs="jvm:ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard")(lambda d: None)


case("SP-13c", needs="jvm:ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard")(lambda d: None)


# ---------------------------------------------------------------- 18 键 and 九键双拼 (B0-18键, B0-九键双拼)

# the grouped keyboards' codes are private use (U+F8C0–F8FB): never in the user's text
def no_codes(t):
    return t is not None and not has_keyboard_code(t)


def semi18(d):
    """the 18 keys' L; key by the name Device.keys gives it: its label may show ; as 「；」 in Chinese"""
    k = d.keys(refresh=True)
    name = next((n for n in ("l;", "l；") if n in k), None)
    if not name:
        raise Blocked(f"18 键上没有 L; 键：{sorted(k)[:24]}")
    return name


def keys18(d, groups, after=0.6):
    """tap the 18 keys by their letters ("we io"), a key a word; "l;" the L; key whatever its label"""
    if "l;" in groups.split():
        groups = " ".join(semi18(d) if g == "l;" else g for g in groups.split())
    grouped_keys(d, groups, after=after)


def nine_sp(d, digits, after=0.6):
    k = d.keys(refresh=True)
    miss = [c for c in digits if c not in k]
    if miss:
        raise Blocked(f"九键双拼上没有 {miss}")
    d.taps([k[c] for c in digits], gap=0.35, after=after)


def column(d, s=None):
    """the nine keys' left column: what is drawn left of the 2 key, above the space"""
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    two = d.keys().get("2")
    if not kb or not two:
        return []
    space = s.find(rid="button_space", where="ime")
    y1 = space.b[1] if space else kb.b[3]
    return [n.text for n in sorted(kb.walk(), key=lambda n: n.b[1])
            if n.text and n.b[2] < two[0] - 60 and kb.b[1] <= n.b[1] and n.b[3] <= y1]


ROWS18 = ["q we rt y u io p", "a sd fg h jk l;", "z xc v bn m"]
WMJ = "我们今天去吃饭"


@case("SP-14", base="B0-18键", mutates=True, visual=True)
def sp14(d):
    d.hide_keyboard()
    gear(d, "双拼")
    choose(d, "双拼方案", "小鹤")
    d.open_page("text")
    d.switch_im("双拼")
    d.step("键盘")
    k = d.keys(refresh=True)
    semi = semi18(d)
    miss = [g for r in ROWS18 for g in r.split() if (semi if g == "l;" else g) not in k]
    check(not miss, f"18 键上没有 {miss}：{sorted(k)[:24]}")
    check("shift" not in k and "中/英" not in k, f"⇧ {'shift' in k}，中/英 {'中/英' in k}")
    d.shot("kb", "18 键：Q WE RT Y U IO P / A SD FG H JK L; / Z XC V BN M，键面大写")
    d.step("点 WE IO")
    keys18(d, "we io")
    d.cands()
    sy = d.syllables()
    check("wo" in sy, f"音节条 {sy}")
    d.step("整句")
    keys18(d, "m fg jk bn rt m q u io io fg jk")
    c = d.cands()
    check(has(c, WMJ), f"第一页 {c[:8]}")
    d.pick(WMJ)
    t = d.field_text()
    check(t == WMJ and no_codes(t), f"框里是「{t}」")
    return f"音节条 {sy[:4]}；候选 {c[:3]}；「{t}」"


@case("SP-15", base="B0-九键双拼", mutates="learn", visual=True)
def sp15(d):
    d.step("键盘")
    k = d.keys(refresh=True)
    s = d.screen()
    check(all(c in k for c in "23456789"), f"不是九宫格：{sorted(k)[:20]}")
    check("中/英" not in k, "九键双拼上有中/英")
    check(s.find(text="JKL", where="ime") or s.find(text="JKL;", where="ime"), "5 键上没有 JKL")
    d.shot("kb", "九键双拼：2 ABC…9 WXYZ，没有中/英，空格宽")
    d.step("点 9 6")
    nine_sp(d, "96")
    d.cands()
    col = column(d)
    check("wo" in col, f"左列 {col}")
    check(not d.syllables(), f"编码行上有音节条 {d.syllables()}")
    d.step("整句")
    nine_sp(d, "635686784435")
    c = d.cands()
    check(has(c, WMJ), f"第一页 {c[:8]}")
    d.pick(WMJ)
    t = d.field_text()
    check(t == WMJ and no_codes(t), f"框里是「{t}」")
    return f"左列 {col[:4]}；候选 {c[:3]}；「{t}」"


@case("SP-16", base="B0-18键", mutates=True)
def sp16(d):
    d.hide_keyboard()
    gear(d, "双拼")
    choose(d, "双拼方案", "微软")
    d.open_page("text")
    d.switch_im("双拼")
    semi = semi18(d)
    k = d.keys(refresh=True)
    check(";" not in k and "；" not in k, f"18 键上另有分号键：{sorted(k)[:24]}")
    keys18(d, "m l;")
    c1 = d.cands()
    d.clear()
    keys18(d, "l; we")
    c2 = d.cands()
    d.clear()
    check("明" in c1 or "名" in c1, f"M L; 的第一页 {c1[:8]}")
    check("了" in c2, f"L; WE 的第一页 {c2[:8]}")
    return f"键 {semi}；ming {c1[:3]}；le {c2[:3]}"


@case("SP-17", base="B0-18键", mutates=True, clean=True)
def sp17(d):
    keys18(d, "u io")
    c0 = d.cands()
    if len(c0) < 2:
        raise Blocked(f"U IO 只有 {c0}")
    w = first(c0[1:])
    for _ in range(2):
        d.clear()
        keys18(d, "u io")
        c = d.cands()
        if w not in c:
            raise Blocked(f"候选里没有「{w}」：{c[:6]}")
        d.pick(c.index(w) + 1)
        d.press(".")
    d.clear()
    keys18(d, "u io")
    c3 = d.cands()
    d.clear()
    d.hide_keyboard()
    shuangpin_keyboard(d, "全键盘")
    d.open_page("text")
    d.switch_im("双拼")
    d.type("ui")
    c4 = d.cands()
    d.clear()
    check(first(c3) == w, f"选两次「{w}」后 18 键的候选 {c3[:3]}")
    check(first(c4) != w, f"全键盘 u i 的首选 {c4[:3]}（18 键上学的「{w}」不该串过来）")
    return f"「{w}」：18 键 {c3[:2]}，全键盘 {c4[:2]}"


@case("SP-18", base="B0-18键", mutates="learn")
def sp18(d):
    keys18(d, "xc io")
    d.cands()
    p1 = d.preedit()
    d.enter()
    t1 = d.field_text()
    d.clear()
    keys18(d, "bn io h")
    d.cands()
    p2 = d.preedit()
    d.press("globe", after=0.8)
    t2 = d.field_text()

    def shown(p):
        return (p or "").replace(" ", "").replace("'", "")
    check(t1 == shown(p1) and no_codes(t1), f"XC IO 编码行「{p1}」，回车后框里「{t1}」")
    check(t2 == shown(p2) and no_codes(t2), f"BN IO H 编码行「{p2}」，🌐 后框里「{t2}」")
    return f"「{t1}」「{t2}」"


@case("SP-19", base="B0-18键", mutates=True)
def sp19(d):
    keys18(d, "u io")
    c = d.cands()
    if len(c) < 5:
        raise Blocked(f"U IO 只有 {c}")
    m = d.long_pick(5)
    if "固定首位" not in m:
        raise Fail(f"菜单里没有固定首位：{m[:10]}")
    menu_tap(d, "固定首位")
    d.clear()
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "固定的词")
    t = d.all_texts()
    row = next((x for x in t if "u io" in x), None)
    check(row and "双拼 18 键 · 自然码" in row, f"固定的词 {t[:10]}")
    check(all(no_codes(x) for x in t), f"有码字符 {t[:10]}")
    # the full keyboard's pinned row names its scheme too
    shuangpin_keyboard(d, "全键盘")
    d.open_page("text")
    d.switch_im("双拼")
    d.type("ui")
    c = d.cands()
    if len(c) < 5:
        raise Blocked(f"全键盘 u i 只有 {c}")
    m = d.long_pick(5)
    if "固定首位" not in m:
        raise Fail(f"全键盘菜单里没有固定首位：{m[:10]}")
    menu_tap(d, "固定首位")
    d.clear()
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "固定的词")
    t = d.all_texts()
    full = [x for x in t if "双拼 · 自然码" in x]
    check(full, f"全键盘固定的那条没写「双拼 · 自然码」：{t[:10]}")
    return f"「{row}」；全键盘「{full[0]}」"


@case("SP-20", base="B0-18键", visual=True)
def sp20(d):
    d.switch_im("双拼")
    k0 = d.keys(refresh=True)
    check("we" in k0 and "中/英" not in k0, f"18 键底排有中/英，或不是 18 键：{sorted(k0)[:12]}")
    d.shot("kb18", "18 键，底排没有中/英")
    d.step("🌐 到下一个输入法，一直换到英文")
    for _ in range(8):
        d.press("globe", after=0.8)
        if "英文" in d.im_label():
            break
    k = d.keys(refresh=True)
    d.shot("kben", "英文全键盘，底排没有中/英")
    check("q" in k and "w" in k and "we" not in k, f"🌐 换到「{d.im_label()}」，不是英文全键盘：{sorted(k)[:12]}")
    check("中/英" not in k, "英文全键盘上有中/英")
    d.step("🌐 一直换回双拼")
    for _ in range(8):
        if "双拼" in d.im_label():
            break
        d.press("globe", after=0.8)
    k2 = d.keys(refresh=True)
    d.shot("back", "回到 18 键")
    check("we" in k2 and "io" in k2, f"回到「{d.im_label()}」时不是 18 键：{sorted(k2)[:12]}")
    return "18 键没有中/英；🌐 到英文、再回到 18 键"


@case("SP-21", base="B0-18键", mutates=True, page="url")
def sp21(d):
    d.switch_im("双拼")
    w = space_share(d)
    check("中/英" not in d.keys(refresh=True), "网址框的 18 键有中/英")
    keys18(d, "bn io")
    d.press(".com", after=0.6)
    t = d.field_text()
    d.open_page("email")
    d.switch_im("双拼")
    keys18(d, "bn io")
    d.press("@", after=0.6)
    t2 = d.field_text()
    check(t and t.endswith(".com") and no_codes(t) and t[:-4].isalpha(), f"网址框里「{t}」")
    check(t2 and t2.endswith("@") and no_codes(t2) and t2[:-1].isalpha(), f"邮箱框里「{t2}」")
    w2 = space_share(d)
    check(w >= 0.15 and w2 >= 0.15, f"18 键空格占键盘宽：网址框 {w:.2f}，邮箱框 {w2:.2f}，应 ≥ 0.15")
    d.hide_keyboard()
    shuangpin_keyboard(d, "九键")
    d.open_page("url")
    d.switch_im("双拼")
    # the nine keys' column: syllables while typing, the address keys once it is committed (T9Keyboard.labels)
    nine_sp(d, "64")
    d.cands()
    col = column(d)
    d.space()
    col2 = column(d)
    if ".com" not in col2:
        raise Fail(f"上屏后左列 {col2}，没有 .com")
    d.tap(d.screen().find(text=".com", where="ime").c, 0.6)
    t3 = d.field_text()
    check(".com" not in col and "ni" in col, f"打字时左列 {col}")
    check(t3 and t3.endswith(".com") and no_codes(t3) and len(t3) > 4, f"九键双拼网址框里「{t3}」")
    return f"「{t}」「{t2}」「{t3}」"


@case("SP-22", base="B0-九键双拼", mutates="learn")
def sp22(d):
    nine_sp(d, "641")
    nine_sp(d, "43")
    d.cands()
    p = d.preedit()
    d.clear()
    before = d.field_text() or ""
    nine_sp(d, "1")
    t = d.field_text() or ""
    check("'" in (p or ""), f"打字时 1 后编码行「{p}」")
    check(len(t) == len(before) + 1 and not t[-1:].isalnum() and no_codes(t), f"没打字时 1 后框里「{t}」")
    return f"「{p}」；「{t}」"


@case("SP-24", base="B0-九键双拼", mutates=True)
def sp24(d):
    d.hide_keyboard()
    gear(d, "双拼")
    choose(d, "双拼方案", "微软")
    add_im(d, "九键拼音")
    # 九键拼音's 按键长按: 2's symbols set to ~ (九键双拼 shares them)
    gear(d, "九键拼音")
    d.tap_text("按键长按", after=1.2)
    n = next((n for n in d.screen().app() if n.text == "2"), None)
    if not n:
        raise Blocked("按键长按里没有 2 键")
    d.tap(n.c, 1.0)
    eds = [e for e in d.screen().app() if "Edit" in e.cls]
    if not eds:
        raise Blocked("2 键的编辑框没出来")
    d.tap(eds[0].c, 0.8)
    d.clear()
    # `input text` goes through the keyboard: a Chinese IM turns ~ into the full-width ～ (as on a phone,
    # where the user would type it on 英文), so the box is typed in 英文
    d.switch_im("英文")
    d.sh("input text '~'")
    got = next((e.text for e in d.screen().app() if "Edit" in e.cls), None)
    if got != "~":
        raise Blocked(f"2 键的符号没能填成 ~：框里「{got}」")
    d.hide_keyboard()
    # the dialog slides down as the keyboard goes (y 884 -> 1295 on redroid): 确定 tapped where it
    # was hits nothing, and the symbols are never saved; tapped once it has stopped moving
    ok, last = None, None
    for _ in range(20):
        ok = next((n for n in d.screen().app() if n.text == "确定" and n.cls == "Button"), None)
        if ok and last and ok.b == last.b:
            break
        last = ok
        time.sleep(0.25)
    if not ok:
        raise Blocked("2 键的对话框里没有「确定」")
    d.tap(ok.c, 1.0)
    if any(n.text == "确定" and n.cls == "Button" for n in d.screen().app()):
        raise Blocked("点了「确定」，2 键的对话框还在")
    d.open_page("text")
    d.switch_im("双拼")
    items, xy = popup(d, "5")
    five = [n.text for n in items]
    popup_away(d, xy)
    d.clear()
    items, xy = popup(d, "2")
    two = [n.text for n in items]
    popup_away(d, xy)
    d.clear()
    letters = {x.lower() for x in five}
    # ; and : of the row are typed as punctuation (only the key's own tap is ing's code): shown as typed
    check({"j", "k", "l"} <= letters and "：" in five and five.count("；") + five.count(";") == 1, f"长按 5 弹出 {five}")
    check("~" in two or "～" in two, f"长按 2 弹出 {two}")
    # 九键双拼's own code row is 32 dp (pinyin on 全键盘, B0's default): typing, its letters leave the
    # syllables to the column; its 123 shows them on the row, tappable
    d.press("6")
    row = d.code_row()
    row_dp = round((row.b[3] - row.b[1]) / d.dpx()) if row else None
    sy_letters = d.syllables()
    d.press("123", after=0.8)
    sy = d.syllables()
    check(row_dp == 32, f"九键双拼的编码行 {row_dp}dp")
    check(not sy_letters, f"字母页上编码行有音节条 {sy_letters}（应在左边那一列）")
    check(sy, "打字中点 123，编码行上没有音节条")
    if sy:
        d.syllable_tap(sy[0])
        p = d.preedit() or ""
        check(sy[0] in p, f"点音节条上的「{sy[0]}」后拼音行 `{p}`")
    d.press("abc", after=0.8)
    d.clear()
    # 拼音 全键盘 keeps the thin row
    d.switch_im("拼音")
    d.type("ni")
    d.cands()
    prow = d.code_row()
    py_dp = round((prow.b[3] - prow.b[1]) / d.dpx()) if prow else None
    d.clear()
    check(py_dp == 18, f"拼音全键盘的编码行 {py_dp}dp")
    return f"5：{five}；2：{two}；九键双拼编码行 {row_dp}dp，123 音节条 {sy[:4]}；拼音全键盘 {py_dp}dp"


@case("SP-23", base="B0-18键", mutates=True)
def sp23(d):
    d.hide_keyboard()
    gear(d, "双拼")
    choose(d, "双拼方案", "微软")
    d.open_page("text")
    d.switch_im("双拼")
    keys18(d, "bn io")
    d.cands()
    # 中文标点 on: the popup offers ; as 「；」 (the long press maps it), which types after what is typed
    items, xy = popup(d, semi18(d), left=True)
    bottom = max(n.b[3] for n in items)
    row = sorted((n for n in items if abs(n.b[3] - bottom) < 10), key=lambda n: n.c[0])
    tgt = next((n for n in row if n.text == "；"), None)
    if len(row) != 5 or not tgt:
        popup_away(d, xy)
        raise Blocked(f"长按 L; 最下面一行 {[n.text for n in row]}")
    # the finger starts on the corner, the row's middle (as PY's QW), not on the item nearest it;
    # pressed at the key's left edge, as the slide right would leave the screen from its centre
    popup_slide(d, xy, tgt, items=items, key=row[2].text)
    t = d.field_text()
    d.clear()
    keys18(d, "xc io")
    d.cands()
    d.syllable_tap("c")
    c = d.cands()
    sy = d.syllables()
    d.clear()
    check(t and t.endswith("；") and len(t) >= 2 and not t[:-1].isascii() and no_codes(t), f"长按 L; 选「；」后框里「{t}」")
    check(c and "西" not in c[:8], f"点 c 后候选 {c[:8]}")
    check(sy and all(x.startswith("c") for x in sy), f"点 c 后音节条 {sy}")
    return f"「{t}」；c：{c[:3]} {sy[:3]}"
