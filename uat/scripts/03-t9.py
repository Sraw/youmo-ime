"""uat/03-t9.md (B0-九键). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

import statistics
import time

from uat import Blocked, case, check, choose, first, home, keys_at, script


def t9(d, digits, gap=0.35, after=0.6):
    """the nine keys by digit, a pause between taps (0.35 s: two taps on one key are 连按 only where
    a case set it on, at 快 or 中; off, the default, every tap is a key)"""
    k = d.keys(refresh=True)
    d.taps([k[c] for c in digits], gap=gap, after=after)


@case("T9-02", base="B0-九键", mutates="learn")
def t902(d):
    t9(d, "64426")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(first(c) == "你好", f"候选 {c[:4]}")
    check(t == "你好", f"框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


def zhuyin_hold(d, xy):
    """a long press on xy: the popup's texts while the finger is down, then lifted where it went down"""
    d.touch(("down", *xy), ("sleep", 0.9))
    texts = d.screen().texts(where="all")
    d.touch(("up", *xy))
    time.sleep(0.5)
    return texts


ZHUYIN = {chr(c) for c in range(0x3105, 0x312A)}


@case("T9-60b", base="B0-注音九键", visual=True)
def t960b(d):
    kb = d.need_keyboard()
    groups = [n for n in kb.walk() if n.clickable and n.texts() and len(n.texts()[0]) > 1 and set(n.texts()[0]) <= ZHUYIN]
    check(groups, "找不到注音组键")
    key = max(groups, key=lambda n: n.c[0])
    face = key.texts()[0]
    x, y = key.c
    d.touch(("down", x, y), ("sleep", 0.9), ("move", x, y))
    d.shot("kb", f"长按最右的组键 {face}：{face[0]} 在手指下，放不下时在靠边那头")
    # the popup's row is above the key held
    shown = sorted((n for n in d.screen().nodes if n.text in set(face) and n.text and not n.clickable and n.b[3] <= key.b[1]),
                   key=lambda n: n.c[0])
    d.touch(("up", x, y))
    time.sleep(0.5)
    row = [n.text for n in shown]
    first = next((n for n in shown if n.text == face[0]), None)
    # as the full keyboard's: the first over the finger where the rest fit, else at the edge's end
    check(first is not None and (first.b[0] <= x < first.b[2] or row[-1] == face[0]), f"弹出一排 {row}，{face[0]} 应在手指下或最右")
    check(all(n.b[2] <= kb.b[2] for n in shown), "弹出的字超出屏幕")
    return f"{face}: {row}"


@case("T9-60", base="B0-注音九键", mutates="learn")
def t960(d):
    k = d.keys(refresh=True)
    s = d.screen()
    kb = d.need_keyboard()
    tones = sorted((n for n in kb.walk() if n.clickable and n.texts() and n.texts()[0] in "ˉˊˇˋ"), key=lambda n: n.c[1])
    check([n.texts()[0] for n in tones] == list("ˉˊˇˋ"), f"左列 {[n.texts() for n in tones]}")
    slot = k.get("，")
    # the right column found by its scene ids
    if not any(n.key for n in kb.walk()):
        check(False, "T9-60 找不到场景键 id（键盘不是引擎画的）")
    # ? ! ~ by their places: each face as punctuation maps it
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


def held_cells(d, key):
    """[key] (a centre) held: the popup's cells (texts shown outside the keyboard's own keys, with where
    they are); let go where it went down, then nothing typed is left (cleared)"""
    kb = d.need_keyboard()
    on_keys = {t for n in kb.walk() if n.clickable for t in n.texts()}
    d.touch(("down", *key), ("sleep", 0.9))
    cells = [n for n in d.screen().nodes if n.text and not n.clickable and n.text not in on_keys]
    d.shot("full", "长按 2：手指下一行是 A B C b c（A 在手指下），a 和符号在上面，没有 2")
    d.touch(("up", *key))
    time.sleep(0.5)
    d.clear()
    return cells


def nine_two_row(cells, key, where):
    """the popup of 2 after its 2 deleted and a moved to the end: A B C b c one row, A the nearest the
    finger, a above them, no 2 anywhere"""
    got = {n.text: n for n in cells}
    row = [got.get(t) for t in ("A", "B", "C", "b", "c")]
    check(all(row), f"{where}长按 2 的弹出没有 A B C b c 全部：{[n.text for n in cells][:20]}")
    ys = [n.c[1] for n in row]
    check(max(ys) - min(ys) < 10, f"{where}A B C b c 不在一行：{ys}")
    check(min(row, key=lambda n: abs(n.c[0] - key[0])).text == "A", f"{where}手指下不是 A：{[(n.text, n.c[0]) for n in row]}，手指 x={key[0]}")
    check("a" in got and got["a"].c[1] < min(ys), f"{where}a 不在那一行上面：{[(n.text, n.c) for n in cells][:20]}")
    check("2" not in got, f"{where}删掉的 2 还在弹出里")


@case("T9-62", base="B0-设置", mutates=True, visual=True)
def t962(d):
    from uat import add_im, choose, gear, pinyin_keyboard, shuangpin_keyboard
    st = script("14-settings")
    pinyin_keyboard(d, "九键")
    add_im(d, "双拼")
    gear(d, "双拼")
    choose(d, "双拼方案", "微软")
    shuangpin_keyboard(d, "九键")
    st.slots_tab(d, "九键")
    st.key_dialog(d, "2")
    st.dialog_pick(d, "2")
    d.tap_text("删除", scroll=False, after=0.4)
    # the delete selected a, which took 2's place: to the end
    for _ in range(10):
        d.tap_text("右移", scroll=False, after=0.3)
    d.shot("app", "九键 2：删了 2，a 挪到最后，带框的首选是 A")
    d.tap_text("确定", where="all", scroll=False, after=1.0)
    d.open_page("text")
    d.switch_im("拼音")
    d.clear()
    two = d.face("ABC")
    d.shot("kb", "拼音九键 2 的键角是 A")
    check(two.alt == "A" or two.desc.endswith("A"), f"2 键的键角读作「{two.alt or two.desc}」，应是 A")
    x, y = two.c
    d.swipe((x, y), (x, y - 120), 150, after=0.6)
    idle = d.field_text() or ""
    check(idle == "A", f"没打字时上滑 2 打出「{idle}」，应是 A")
    d.clear()
    d.step("打字中上滑")
    k = d.keys(refresh=True)
    d.tap(k["6"], 0.3)
    d.tap(k["4"], 0.3)
    first_cand = (d.cands() or [""])[0]
    d.swipe((x, y), (x, y - 120), 150, after=0.6)
    typing = d.field_text() or ""
    check(first_cand and typing == first_cand + "A", f"打 64 后上滑 2 打出「{typing}」，应是首选「{first_cand}」加 A")
    d.clear()
    d.step("长按 2")
    nine_two_row(held_cells(d, (x, y)), (x, y), "拼音九键")
    d.step("双拼九键长按 2")
    d.switch_im("双拼")
    two = d.face("ABC")
    nine_two_row(held_cells(d, two.c), two.c, "双拼九键")
    return f"没打字上滑「{idle}」，打字中上滑「{typing}」，两个九键的弹出一样"




@case("T9-68", base="B0-九键")
def t968(d):
    # the second 9 tapped at 8% of its height, toward 6 above it: 96, wo, 我 first; both in the middle not
    keys_at(d, "99", [None, (0.5, 0.08)])
    c = d.cands()
    code = d.preedit()
    d.shot("kb", "9、9 靠上边缘：首选「我」，编码行是 wo")
    d.clear()
    keys_at(d, "99", [None, None])
    mid = d.preedit()
    d.clear()
    check(c[:1] == ["我"] and code.replace(" ", "") == "wo", f"第二个 9 靠 6 点：编码行「{code}」，候选 {c[:6]}")
    check(mid.replace(" ", "") != "wo", f"两个 9 都点在正中，编码行是「{mid}」")
    return f"靠边「{code}」{c[:3]}；正中「{mid}」"


@case("T9-69", base="B0-九键", mutates=True)
def t969(d):
    home(d, "通用输入")
    choose(d, "在键上划", "笔画筛选")
    d.open_page("text")
    d.clear()
    dp = d.dpx()
    t9(d, "9264")
    k = d.keys(refresh=True)
    one, three = k["1"], k["3"]
    before = d.preedit()
    # a 横 from 1 across 2 to 3, as one starting on 2
    d.swipe(one, (three[0], one[1]), 250, after=0.8)
    heng = d.preedit()
    d.shot("kb", "9264 后从 1 往右划到 3：编码行末尾是一笔横")
    d.clear()
    t9(d, "9264")
    # a 点 from 1 down and right, 40dp each way
    d.swipe(one, (one[0] + int(40 * dp), one[1] + int(40 * dp)), 200, after=0.8)
    dian = d.preedit()
    d.clear()
    t9(d, "9264")
    # straight up from 1, 40dp: a stroke read as none, dropped; 1's up slot (the first candidate) not reached
    d.swipe(one, (one[0], one[1] - int(40 * dp)), 200, after=0.8)
    up = d.preedit()
    up_typed = d.field_text() or ""
    d.clear()
    # a tap on 1 while typing: the separator as ever (T9-05's 94 1 26, xi'an)
    t9(d, "94")
    d.tap(one, 0.4)
    t9(d, "26")
    sep = d.preedit()
    typed = d.field_text() or ""
    cands = d.cands()
    d.clear()
    check(heng.endswith("一") and heng[:-1].rstrip() == before.rstrip(), f"9264（{before!r}）后从 1 划到 3，编码行「{heng}」，末尾不是一笔横")
    check(dian.endswith("丶"), f"9264 后从 1 往右下划，编码行「{dian}」，末尾不是一点")
    check(up.rstrip() == before.rstrip() and not up_typed, f"9264（{before!r}）后在 1 上往上划，编码行「{up}」、框里「{up_typed}」，上滑打出了东西")
    check(sep.replace(" ", "") == "xi'an" and "西安" in cands and not typed, f"94 1 26：编码行「{sep}」、候选 {cands[:6]}、框里「{typed}」，1 不是分词")
    return f"横：「{heng}」；点：「{dian}」；上滑：「{up}」；94 1 26：「{sep}」"


@case("T9-70", base="B0-九键", mutates="learn", visual=True)
def t970(d):
    return script("06-pinyin").latin_mix(d, t9, "9633474663", "474663", "96636546842678244326")


def text_digits(d, way, digits):
    """the open key dialog's [way] (上滑, 下滑) set to the text [digits], typed on the keyboard's 123 page"""
    st = script("14-settings")
    ed = st.dialog_text_field(d, way)
    d.tap((ed.b[2] - 12, ed.c[1]), 0.8)
    k = d.keys(refresh=True)
    for _ in range(len(ed.text) + 2 if ed.text else 0):
        d.tap(k["bksp"], 0.15)
    if "123" not in k:
        raise Blocked(f"键盘上没有 123：{sorted(k)[:20]}")
    d.tap(k["123"], 0.8)
    d.type(digits)
    d.sh("input keyevent 4")
    time.sleep(0.8)
    st.dialog_ok(d)


def corner_height(im, b):
    """the height in px of the ink in the top third of key box b's right half (its 右上 mark), against the key's
    colour there"""
    x0, y0, x1, y1 = b
    box = (x0 + (x1 - x0) // 2, y0 + 3, x1 - 3, y0 + (y1 - y0) // 3)
    g = im.crop(box).convert("L")
    w, h = g.size
    px = g.load()
    bg = statistics.mode(px[x, y] for x in range(w) for y in range(h))
    rows = [y for y in range(h) if any(abs(px[x, y] - bg) > 60 for x in range(w))]
    return rows[-1] - rows[0] + 1 if rows else 0


@case("T9-71", base="B0-九键", mutates=True, visual=True)
def t971(d):
    st = script("14-settings")

    def heights():
        d.open_page("text")
        d.clear()
        kb = d.need_keyboard()
        descs = {n.desc: n.b for n in kb.walk() if n.clickable and n.desc}
        # as a screen reader says them: 1 by its marks (「，。？！」), 2 「ABC，2」, the space 「空格，拼音」
        said = {"1": lambda t: t.startswith("，。"), "2": lambda t: t.endswith("，2"), "3": lambda t: t.endswith("，3"),
                "空格": lambda t: t.startswith("空格")}
        keys = {k: next((b for t, b in descs.items() if f(t)), None) for k, f in said.items()}
        if None in keys.values():
            raise Blocked(f"键盘上找不到 1、2、3、空格：{sorted(descs)[:20]}")
        im = d.png()
        return {k: corner_height(im, b) for k, b in keys.items()}

    st.slots_tab(d, "九键")
    st.key_dialog(d, "1")
    text_digits(d, "上滑", "1")
    st.slots_tab(d, "功能键")
    st.key_dialog(d, "空格")
    text_digits(d, "上滑", "0")
    one = heights()
    d.shot("kb", "1 上的「1」、空格上的「0」和 2、3 的键角小字一样大、一样粗")
    d.step("1 键改成「12」")
    st.slots_tab(d, "九键")
    st.key_dialog(d, "1")
    text_digits(d, "上滑", "12")
    twelve = heights()
    d.shot("kb", "1 上的「12」是快捷键文字的样子，比 2 的「2」小、细")
    check(abs(one["1"] - one["2"]) <= 1 and abs(one["空格"] - one["2"]) <= 1 and one["2"] > 0,
          f"墨迹高度 1「{one['1']}」空格「{one['空格']}」，2「{one['2']}」3「{one['3']}」")
    check(0 < twelve["1"] < twelve["2"] - 1, f"「12」墨迹高 {twelve['1']}，「2」{twelve['2']}")
    return f"墨迹高度：设「1」时 {one}；设「12」时 {twelve}"


@case("T9-72", base="B0-九键", mutates=True)
def t972(d):
    from uat import gear
    p = script("04-panels")
    d.hide_keyboard()
    d.open_settings()
    gear(d, "英文")
    choose(d, "键盘", "九键")
    d.open_page("text")
    pinyin = p.column_all(d)
    d.switch_im("英文")
    english = p.column_all(d)
    check(english == [",", ".", "?", "!", "'", "-", ":", ";", "@"], f"英文九键左列是 {english}")
    p.hold_item(d, p.item_of(d, "@"), pick="删除这一项")
    after = p.column_all(d)
    check("@" not in after, f"删了 @ 后英文左列 {after}")
    d.switch_im("拼音")
    back = p.column_all(d)
    check(back == pinyin and "@" in back, f"拼音九键左列 {back}（之前 {pinyin}）")
    return f"英文 {english} → 删 @ {after}；拼音 {back}"
