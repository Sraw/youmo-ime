"""uat/03-t9.md (B0-九键). The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import (PKG, Blocked, add_im, case, check, first, flick, gear, glide_on, home, glide_shot, has, popup,
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


@case("T9-01", base="B0-九键", visual=True)
def t901(d):
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    labels = [[m.text for m in n.walk() if m.text] for n in kb.walk() if n.clickable]
    flat = [x for l in labels for x in l]
    d.shot("kb", "九键")
    col = column(d, s)
    want = ["，。？！", "1", "ABC", "2", "DEF", "3", "GHI", "4", "JKL", "5", "MNO", "6",
            "PQRS", "7", "TUV", "8", "WXYZ", "9", "重输", "0", "符", "123"]
    miss = [w for w in want if w not in flat]
    check(not miss, f"键盘上没有 {miss}")
    check(col[:4] == ["，", "。", "？", "！"], f"左边一列 {col}")
    check(s.find(rid="button_backspace", where="ime") and s.find(rid="button_lang", where="ime")
          and s.find(rid="button_return", where="ime") and d.key_node("中/英", s), "没有 ⌫、中/英、地球键或回车")
    return f"左列 {col[:5]}；1–9、⌫、重输、0、符、123、空格、中/英、地球键、回车都在"


@case("T9-02", base="B0-九键", mutates="learn")
def t902(d):
    t9(d, "64426")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(first(c) == "你好", f"候选 {c[:4]}")
    check(t == "你好", f"框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


@case("T9-03", base="B0-九键")
def t903(d):
    t9(d, "64")
    d.cands()
    col = column(d)
    check("ni" in col and "mi" in col and "o" in col, f"左边一列 {col}")
    col_tap(d, "mi")
    s = d.screen()
    c, p = d.candidates(s), d.preedit(s)
    check(p == "mi", f"拼音行 `{p}`")
    check(has(c, "米") or has(c, "密"), f"候选 {c[:6]}")
    check(not has(c, "你"), f"选 mi 后候选里还有「你」：{c[:6]}")
    return f"左列 {col[:4]}；选 mi 后 `{p}` {c[:4]}"


@case("T9-04", base="B0-九键")
def t904(d):
    t9(d, "64")
    c0 = d.cands()
    column(d)
    col_tap(d, "mi")
    d.bksp()
    time.sleep(0.4)
    s = d.screen()
    c, col = d.candidates(s), column(d, s)
    check("ni" in col and "mi" in col, f"退格后左列 {col}")
    check(c[:3] == c0[:3], f"退格后候选 {c[:4]}（没选音节时 {c0[:4]}）")
    return f"退格后左列 {col[:3]}，候选 {c[:3]}"


@case("T9-05", base="B0-九键")
def t905(d):
    t9(d, "94")
    t9(d, "1")
    t9(d, "26")
    c = d.all_cands()
    p = d.preedit()
    check("'" in p, f"拼音行 `{p}`")
    check(has(c, "西安"), f"候选 {c[:8]}")
    return f"`{p}`，候选 {c[:4]}"


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


@case("T9-06", base="B0-设置", mutates=True)
def t906(d):
    multi_tap(d, "中")
    k = d.keys(refresh=True)["1"]
    d.taps([k, k], gap=0.15, after=0.8)
    t = d.field_text()
    check(t == "。", f"框里「{t}」")
    return f"「{t}」"


@case("T9-07", base="B0-九键")
def t907(d):
    k = d.keys(refresh=True)["1"]
    d.taps([k, k], gap=0.15, after=0.8)
    t = d.field_text()
    check(t == "，，", f"框里「{t}」")
    return f"「{t}」"


@case("T9-08", base="B0-设置", mutates=True)
def t908(d):
    multi_tap(d, "中")
    k = d.keys(refresh=True)
    d.taps([k["4"], k["4"]], gap=0.12, after=0.3)
    t9(d, "26")
    c = d.all_cands()
    hit = [x for x in c[:12] if x.split(" ")[0] in ("还", "汉", "含", "寒", "喊", "韩", "憨", "函", "汗")]
    check(hit, f"候选 {c[:10]}（拼音行 `{d.preedit()}`）")
    return f"`{d.preedit()}`，候选 {c[:5]}"


@case("T9-09", base="B0-九键", mutates="learn")
def t909(d):
    items, xy = popup(d, "5")
    _, row = symbol_rows(items, "5")
    order = [n.text for n in sorted((n for n in items if n.text.lower() in "jkl5"), key=lambda n: n.c[0])]
    tgt = next((n for n in items if n.text == "L"), None)
    if not tgt:
        popup_away(d, xy)
        raise Blocked(f"长按 5 弹出 {[n.text for n in items]}")
    popup_slide(d, xy, tgt)
    t1, p1 = d.field_text() or "", d.preedit()
    t9(d, "64")
    c = [x.split(" ")[0] for x in d.all_cands()]
    popup_pick(d, "5", "l")
    t2, p2 = d.field_text() or "", d.preedit()
    check(order == ["J", "K", "L", "5", "j", "k", "l"], f"弹出一行 {order}")
    check(t1 == "L" and not p1, f"选 L：框里「{t1}」，拼音行 `{p1}`")
    check(c and t2 == f"L{c[0]}l" and not p2, f"打字中选 l：框里「{t2}」，拼音行 `{p2}`，候选 {c[:3]}")
    return f"弹出 {order}；「{t1}」→「{t2}」"


@case("T9-10", base="B0-九键")
def t910(d):
    flick(d, "8")
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(t == "8" and not p, f"框里「{t}」，拼音行 `{p}`")
    return "「8」"


@case("T9-11", base="B0-九键")
def t911(d):
    t9(d, "64426")
    d.cands()
    d.press("重输", after=0.6)
    s = d.screen()
    t, p, col = d.field_text(s), d.preedit(s), column(d, s)
    check(not t and not p, f"框里「{t}」，拼音行 `{p}`")
    check(col[:2] == ["，", "。"], f"左列 {col}")
    return "清空，左列回到标点"


@case("T9-12", base="B0-九键")
def t912(d):
    t9(d, "94")
    p1, c = d.preedit(), d.all_cands(pages=3)
    d.clear()
    t9(d, "94664486")
    p2, c2 = d.preedit(), d.cands()
    check(not has(c, "中国"), f"94（`{p1}`）的前三页有「中国」：{c[:10]}")
    check(first(c2) == "中国", f"按全 94664486（`{p2}`）：{c2[:4]}")
    d.clear()
    t9(d, "25")
    p3, c3 = d.preedit(), d.all_cands(pages=3)
    check(has(c3, "北京"), f"25（`{p3}`）拼不成音节，候选没有「北京」：{c3[:8]}")
    return f"94 `{p1}` {c[:3]}；按全 `{p2}` {c2[:2]}；25 `{p3}` 有北京"


@case("T9-13", base="B0-设置", mutates=True)
def t913(d):
    add_im(d, "九键拼音")
    gear(d, "九键拼音")
    d.tap_text("按键字母", after=1.2)
    d.tap_text("PQRS, 7", where="all", after=1.2)
    s = d.screen()
    f = next((n for n in s.nodes if n.cls == "EditText"), None)
    if not f:
        raise Blocked("7 键的字母对话框没有输入框")
    d.tap((f.b[2] - 20, f.c[1]), 1.0)
    d.bksp(6)
    d.switch_im("拼音")
    d.type("prs")
    d.enter()
    s = d.screen()
    f = next((n for n in s.nodes if n.cls == "EditText"), None)
    if not f or f.text != "prs":
        raise Blocked(f"输入框里是「{f.text if f else None}」")
    d.hide_keyboard()
    d.tap_text("确定", where="all", scroll=False, after=1.0)
    d.open_page("text")
    d.switch_im("九键")
    items, xy = popup(d, "7")
    d.touch(("up", *xy))
    texts = [n.text.lower() for n in items]
    check("q" not in texts and {"p", "r", "s"} <= set(texts), f"长按 7 弹出 {texts}")
    return f"长按 7 弹出 {texts}"


@case("T9-14", base="B0-设置", mutates=True)
def t914(d):
    add_im(d, "九键拼音")
    gear(d, "九键拼音")
    t = d.all_texts()
    rows = ["通用输入设置", "连按选字母", "九键英文打法", "按键字母", "按键长按"]
    titles = [x for x in t if x in rows]
    check(titles == rows, f"九键页 {t[:14]}")
    check(any("只有这一种读法" in x for x in t), f"连按选字母的说明 {t[:14]}")
    check(any(x.endswith("总是连按") or x.endswith("总是连按。") for x in t), f"九键英文打法的说明 {t[:14]}")
    # a password field switches to English on the full keyboard: never 九键英文's 连按
    check(not any("连按" in x and "密码" in x for x in t), f"九键英文打法的说明还写密码 {t[:14]}")
    d.scroll_top()
    d.tap_text("通用输入设置", after=1.2)
    s = d.screen()
    row = None
    for _ in range(6):
        s = d.screen()
        row = s.find(text="纠正错拼和按偏的键")
        if row:
            break
        d.scroll(s)
    summ = " ".join(row.parent.texts()) if row and row.parent else ""
    check("不适用：九键拼音。" in summ, f"纠正错拼的说明：{summ[-60:]}")
    d.tap_text("模糊音", after=1.0)
    s = d.screen()
    texts = s.texts(where="all")
    uv = next((i for i, x in enumerate(texts) if "u ↔ v" in x or "u↔v" in x), None)
    check(uv is not None and any("不适用：九键拼音" in x for x in texts[uv:uv + 3]), f"模糊音 {texts[:20]}")
    box = [n for n in s.nodes if n.cls in ("CheckBox", "CheckedTextView") and n.enabled]
    check(box, "模糊音里的勾选框不能勾")
    return "九键页五项；纠正错拼和 u↔v 都写着不适用：九键拼音"


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


@case("T9-16", base="B0-设置", mutates=True, visual=True)
def t916(d):
    glide_on(d, "九键", add="九键拼音")
    k = d.keys(refresh=True)
    pts = [k[c] for c in "6426"]
    glide_shot(d, pts, "划 6-4-2-6，抬手前")
    s = d.screen()
    p = d.preedit(s)
    c = d.all_cands()
    check(p and not any(ch.isdigit() for ch in p), f"拼音行 `{p}`（应是字母读出的拼音）")
    check(has(c, "你好"), f"`{p}` 候选 {c[:8]}")
    return f"`{p}` {c[:3]}"


@case("T9-17", base="B0-设置", mutates=True)
def t917(d):
    glide_on(d, "九键", add="九键拼音")
    flick(d, "5", dy=130)
    time.sleep(0.4)
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    check(t == "5" and not d.preedit(s), f"框里「{t}」，候选 {c[:3]}，拼音行 `{d.preedit(s)}`")
    return f"框里「{t}」，候选 {c[:2]}"



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
    # 4 4 slowly, two keys: 广告 picked, learned
    d.taps([k["4"], k["4"]], gap=1.2, after=0.6)
    c = d.cands(want="广告")
    check(has(c, "广告"), f"慢点 4 4 候选 {c[:8]}")
    d.pick("广告")
    d.clear()
    got = []
    for gap in (0.12, 0.3, 0.45):
        d.taps([k["4"], k["4"]], gap=gap, after=0.5)
        got.append((gap, d.preedit(), [x.split(" ")[0] for x in d.all_cands()[:4]]))
        d.clear()
    # a quick second press is h itself, not two keys (gg/hh 简拼, 广告), the learned 广告 too
    bad = [g for g in got if g[1].replace("'", "").replace(" ", "") != "h" or "广告" in g[2]]
    check(not bad, f"快速连按 4 两下不是 h：{bad}")
    return "; ".join(f"{g}s `{p}` {c}" for g, p, c in got)


@case("T9-24", base="B0-九键")
def t924(d):
    k = d.keys(refresh=True)
    d.taps([k["4"], k["4"]], gap=0.15, after=0.6)
    p = d.preedit()
    c = d.all_cands()
    # 连按 off by default: two keys, read by the model
    check(p.replace("'", "").replace(" ", "") != "h", f"拼音行 `{p}`")
    check(any(has(c, w) for w in ("哈哈", "广告", "个个", "好好")), f"候选 {c[:8]}")
    return f"`{p}` {c[:4]}"


@case("T9-20", base="B0-九键", mutates="learn")
def t920(d):
    t9(d, "4242")
    c = d.cands(want="😃")
    k = next((i for i, x in enumerate(c) if x == "😃"), None)
    check(c and c[0] == "哈哈", f"第一个 {c[:3]}")
    check(k is not None and k >= 2, f"候选 {c[:6]}")
    d.pick("😃")
    t = d.field_text()
    check(t == "😃", f"框里「{t}」")
    return f"{c[:k + 1]} →「{t}」"



@case("T9-21", base="B0-九键")
def t921(d):
    iso = d.sh("date +%Y-%m-%d").strip()
    t9(d, "7474")
    d.cands()
    c = d.all_cands()
    k = next((i for i, x in enumerate(c) if x == iso), None)
    check(c and c[0] == "日期", f"第一个 {c[:3]}")
    check(k is not None and k >= 2, f"候选 {c[:7]}")
    return f"{c[:k + 1]}"


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


@case("T9-25", base="B0-九键", mutates="learn")
def t925(d):
    t9(d, "64")
    d.pick("你")
    d.tap(d.screen().find(rid="button_backspace", where="ime").c, 0.6)
    t9(d, "7474")
    c = [x.split(" ")[0] for x in d.cands(want="日期")]
    # 你 erased is no context: 你脾气 would put 脾气 before 日期
    check(c[:1] == ["日期"], f"候选 {c[:6]}")
    return f"候选 {c[:4]}"


@case("T9-26", base="B0-设置", mutates=True)
def t926(d):
    multi_tap(d, "中")
    k = d.keys(refresh=True)
    d.taps([k["7"]], after=0.6)
    want = (d.preedit(), d.all_cands()[:6])
    d.taps([k["4"], k["4"]], gap=0.15, after=0.6)
    mid = d.preedit()
    d.tap(d.screen().find(rid="button_backspace", where="ime").c, 0.6)
    got = (d.preedit(), d.all_cands()[:6])
    # Backspace takes the multi-tap letter whole, back to 7 alone
    check(got == want, f"连按 `{mid}` 退格后 {got}，只点 7 时 {want}")
    return f"`{mid}` → ⌫ → `{got[0]}` {got[1][:3]}"


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


@case("T9-28", base="B0-设置", mutates=True)
def t928(d):
    def page():
        gear(d, "九键拼音")
        d.tap_text("按键长按", after=1.2)

    def long_press_5():
        d.open_page("text")
        d.switch_im("九键")
        items, xy = popup(d, "5")
        popup_away(d, xy)
        time.sleep(0.4)
        return symbol_rows(items, "5")

    add_im(d, "九键拼音")
    page()
    d.tap_text("# ( ), 5", where="all", after=1.2)
    s = d.screen()
    f = next((n for n in s.nodes if n.cls == "EditText"), None)
    if not f:
        raise Blocked("5 键的长按符号对话框没有输入框")
    d.tap((f.b[2] - 20, f.c[1]), 1.0)
    d.bksp(8)
    d.hide_keyboard()
    f = next((n for n in d.screen().nodes if n.cls == "EditText"), None)
    if f and f.text:
        raise Blocked(f"输入框没清空：「{f.text}」")
    d.tap_text("确定", where="all", scroll=False, after=1.0)
    # emptied, the key shows its digit alone
    shown = d.screen().find(desc="5", where="app")
    check(shown, "清空后页面上 5 键不是空的")
    cleared = long_press_5()
    check(not cleared[0] and {"5", "j", "k", "l"} <= set(cleared[1]), f"清空后长按 5：上面 {cleared[0]}，下面 {cleared[1]}")
    page()
    d.tap(shown.c, 1.2)
    d.tap_text("重置", where="all", scroll=False, after=1.0)
    back = long_press_5()
    check({"#", "（", "）"} <= set(back[0]) or {"#", "(", ")"} <= set(back[0]), f"重置后长按 5 上面一行 {back[0]}")
    return f"清空后长按 5 {cleared}；重置后 {back}"


def set_long_press(d, typed):
    """on 按键长按: the 1 key's symbols set to typed; the toast's lines while it shows"""
    d.tap_text(", 1", where="all", contains=True, after=1.2)
    f = next((n for n in d.screen().nodes if n.cls == "EditText"), None)
    if not f:
        raise Blocked("1 键的长按符号对话框没有输入框")
    d.tap((f.b[2] - 20, f.c[1]), 1.0)
    d.bksp(40)
    # 全拼 by its own name: switch_im("拼音") stops at 九键拼音 too
    for _ in range(6):
        label = d.im_label()
        if "拼音" in label and "九键" not in label:
            break
        d.press("globe", after=0.8)
    for i, part in enumerate(typed.split(" ")):
        if i:
            d.type(" ")
        d.type(part)
        if part.isalpha():
            d.enter()   # the letters as they are, not their pinyin
    f = next((n for n in d.screen().nodes if n.cls == "EditText"), None)
    want = typed.replace(",", "，").replace(".", "。")
    if not f or f.text != want:
        raise Blocked(f"输入框里是「{f.text if f else None}」，要的是「{want}」")
    d.hide_keyboard()
    d.tap_text("确定", where="all", scroll=False, after=0.3)
    # the toast, while it shows: not in the UI dump, a window of its own whose text dumpsys does
    # not give, so only that one showed; the key's label, checked after, says it was this one
    return [l.strip() for l in d.sh("dumpsys window windows").splitlines() if "Toast" in l][:2]


@case("T9-29", base="B0-设置", mutates=True)
def t929(d):
    def page():
        gear(d, "九键拼音")
        d.tap_text("按键长按", after=1.2)

    def set_one(typed):
        return set_long_press(d, typed)

    add_im(d, "九键拼音")
    d.open_page("text")
    d.switch_im("九键")
    popup_pick(d, "1", ".")
    t = d.field_text()
    check(t == ".", f"长按 1 选 . 后框里「{t}」")
    page()
    toast = set_one("a")
    check(toast, "只填 a 后没有提示")
    # the key's description: its symbols as drawn, then its digit
    label = next((n.desc for n in d.screen().app() if n.desc.endswith(", 1")), "")
    check("…" in label, f"只填 a 后 1 键成了「{label}」")
    set_one(", .")
    d.open_page("text")
    d.switch_im("九键")
    items, xy = popup(d, "1")
    popup_away(d, xy)
    texts = sorted(n.text for n in items)
    check(texts == sorted(["，", "。"]), f"改后长按 1 弹出 {texts}")
    return f"「{t}」；只填 a 后提示 {toast[:1]}，1 键「{label}」；改后 {texts}"


def to_english(d):
    """九键拼音 -> 九键英文: its 中/英 key"""
    n = d.key_node("中/英")
    if not n:
        raise Blocked("九键上没有 中/英 键")
    d.tap(n.c, 1.0)
    d._keys = None


def lang_ink(d):
    """the 中/英 key's label measured on screen: (ink height, ink strength) of its left and right
    thirds. The current language is drawn big, the other smaller and fainter (spans the dump does
    not show, so pixels)"""
    n = d.key_node("中/英")
    if not n:
        raise Blocked("九键上没有 中/英 键")
    x0, y0, x1, y1 = n.b
    im = d.png().crop((x0 + 4, y0 + 4, x1 - 4, y1 - 4))
    w, h = im.size
    px = im.load()
    from collections import Counter
    bg = Counter(px[i, j] for i in range(0, w, 3) for j in range(0, h, 3)).most_common(1)[0][0]
    ink = {(i, j): sum(abs(a - b) for a, b in zip(px[i, j], bg)) for i in range(w) for j in range(h)}
    ink = {k: v for k, v in ink.items() if v > 90}
    if not ink:
        raise Blocked("中/英 键上看不到字")
    xs = [i for i, _ in ink]
    lo, hi = min(xs), max(xs)
    third = (hi - lo) / 3

    def part(a, b):
        pts = [(k, v) for k, v in ink.items() if a <= k[0] <= b]
        ys = [k[1] for k, _ in pts]
        return (max(ys) - min(ys) + 1 if ys else 0), (max(v for _, v in pts) if pts else 0)
    return part(lo, lo + third), part(hi - third, hi)


def bottom_row(d):
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    return [([m.text for m in n.walk() if m.text] or [""])[0] for n in kb.walk()
            if n.clickable and n.c[1] > kb.b[3] - 150]


@case("T9-30", base="B0-九键", mutates="learn")
def t930(d):
    row = bottom_row(d)
    check("中/英" in row, f"底排 {row}")
    zh = lang_ink(d)
    d.shot((0, d.key_node("中/英").b[1] - 20, 1080, d.key_node("中/英").b[3] + 20), "九键拼音：「中」大")
    to_english(d)
    en = lang_ink(d)
    d.shot((0, d.key_node("中/英").b[1] - 20, 1080, d.key_node("中/英").b[3] + 20), "九键英文：「英」大")
    check(zh[0][0] > zh[1][0] * 1.15 and zh[0][1] > zh[1][1], f"九键拼音时 中/英（左、右的高和浓）{zh}")
    check(en[1][0] > en[0][0] * 1.15 and en[1][1] > en[0][1], f"九键英文时 中/英（左、右的高和浓）{en}")
    s = d.screen()
    caps = s.find(rid="button_caps", where="ime")
    texts = [n.text for n in s.ime() if n.text]
    d.shot("kb", "九键英文")
    check(caps, "没有 ⇧ 键")
    check("ghi" in texts, f"键上没有小写的 ghi：{texts[:20]}")
    check("英文" in d.im_label(), f"空格是「{d.im_label()}」")
    t9(d, "4663")
    c = [x.split(" ")[0] for x in d.all_cands()]
    # English's word is composed in the field itself, not over the candidates
    p = d.field_text() or ""
    check({"good", "home", "gone"} <= set(c[:8]), f"候选 {c[:8]}")
    check(c and p == c[0], f"框里打着「{p}」，第一个候选 {c[:1]}")
    d.press("space", after=0.6)
    t = d.field_text() or ""
    check(c and t == c[0] + " ", f"框里「{t}」")
    to_english(d)
    back = d.im_label()
    check("九键" in back and d.key_node("重输") is not None, f"再点 中/英：{back}")
    return f"中/英 拼音 {zh}，英文 {en}；候选 {c[:4]}；「{t}」；回到 {back}"


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


@case("T9-34", base="B0-九键", page="email", mutates="learn")
def t934(d):
    row = bottom_row(d)
    globe = d.screen().find(rid="button_lang", where="ime")
    to_english(d)
    row_en = bottom_row(d)
    globe_en = d.screen().find(rid="button_lang", where="ime")
    k = d.keys(refresh=True)
    d.taps([k["4"]] * 2, gap=0.12, after=1.0)
    c = d.cands(timeout=1)
    for label in ("@", ".com"):
        n = d.key_node(label)
        if not n:
            raise Blocked(f"键盘上没有「{label}」键：{row}")
        d.tap(n.c, 0.5)
    t = d.field_text() or ""
    check("@" in row and "." in row and ".com" in row and "中/英" in row and "/" not in row, f"底排 {row}")
    check(globe is None, "邮箱框里还有地球键")
    # the space bar names the IM (九键/英文): every other key the same
    check([x for x in row_en if x != "英文"] == [x for x in row if x not in ("九键", "九键拼音")], f"切到九键英文后底排 {row_en}，九键拼音时 {row}")
    check(globe_en is None, "切到九键英文后邮箱框里有地球键")
    check(not c, f"邮箱框里有候选 {c[:4]}")
    check(t == "h@.com", f"框里「{t}」")
    return f"底排 {row}；「{t}」"


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
    for end in ("enter", ","):
        t9(d, "4663")
        c = [x.split(" ")[0] for x in d.all_cands()]
        if not c:
            raise Blocked("4663 没有候选")
        if end == "enter":
            d.press("enter", after=0.6)
        else:
            col_tap(d, ",")
        t, p = d.field_text() or "", d.preedit()
        # English's space after a word, put behind the mark
        want = c[0] + ("" if end == "enter" else ", ")
        check(t == want and not p, f"{end}：框里「{t}」，拼音行 `{p}`，要「{want}」")
        out.append(t)
        d.clear()
    return f"{out}"


@case("T9-37", base="B0-九键", mutates=True)
def t937(d):
    to_english(d)
    out = []
    for turn in (1, 0):
        d.rotate(turn)
        time.sleep(1.5)
        d._keys = None
        s = d.screen()
        caps = s.find(rid="button_caps", where="ime")
        k = d.keys(refresh=True)
        label = d.im_label()
        check(caps and "4" in k and "q" not in k and "英文" in label, f"{'横屏' if turn else '竖屏'}：空格「{label}」，⇧ {bool(caps)}，键 {sorted(k)[:14]}")
        out.append(label)
    return f"转屏后 {out}"


@case("T9-38", base="B0-九键", page="url")
def t938(d):
    row = bottom_row(d)
    globe = d.screen().find(rid="button_lang", where="ime")
    for label in ("/", ".com"):
        n = d.key_node(label)
        if not n:
            raise Blocked(f"键盘上没有「{label}」键：{row}")
        d.tap(n.c, 0.5)
    t = d.field_text() or ""
    check("/" in row and "." in row and ".com" in row and "中/英" in row and "@" not in row, f"底排 {row}")
    check(globe is None, "网址框里还有地球键")
    check(t == "/.com", f"框里「{t}」")
    return f"底排 {row}；「{t}」"


def word(d):
    """the candidates' words, the ranks' suffixes dropped"""
    return [x.split(" ")[0] for x in d.all_cands()]


@case("T9-39", base="B0-九键", mutates="learn")
def t939(d):
    to_english(d)
    t9(d, "95959")
    c, f = word(d), d.field_text() or ""
    d.press("space", after=0.6)
    t = d.field_text() or ""
    check(f == "wjwjw", f"95959 框里打着「{f}」（候选 {c[:4]}）")
    check(t == "wjwjw ", f"空格后「{t}」")
    check(not any(ch.isdigit() for ch in t), f"打出了数字「{t}」")
    return f"打着「{f}」，候选 {c[:3]}；空格后「{t}」"


def en_letter(d, digit, letter):
    """a long press on a digit key in 九键英文, the letter picked from its row"""
    popup_pick(d, digit, letter)
    time.sleep(0.3)


@case("T9-40", base="B0-九键", mutates="learn")
def t940(d):
    to_english(d)
    for digit, letter in (("9", "z"), ("6", "o"), ("7", "r"), ("2", "b"), ("9", "x")):
        en_letter(d, digit, letter)
    f = d.field_text() or ""
    d.press("space", after=0.6)
    t = d.field_text() or ""
    d.clear()
    t9(d, "96729")
    c = word(d)
    check(f == "zorbx", f"长按五个字母后框里打着「{f}」")
    check(t == "zorbx ", f"空格后「{t}」")
    check("zorbx" in c[:8], f"再按 96729，候选 {c[:8]}")
    d.clear()
    for digit, letter in (("4", "h"), ("3", "e"), ("5", "l")):
        en_letter(d, digit, letter)
    f2 = d.field_text() or ""
    d.press("space", after=0.6)
    t2 = d.field_text() or ""
    check(t2 == "hel ", f"长按定下 hel（打着「{f2}」）空格后「{t2}」")
    return f"打着「{f}」，上屏「{t}」；再按 96729 候选 {c[:4]}；hel →「{t2}」"


@case("T9-41", base="B0-九键", mutates="learn")
def t941(d):
    to_english(d)
    t9(d, "4663")
    c = word(d)
    if not c:
        raise Blocked("4663 没有候选")
    t9(d, "0")
    t, p = d.field_text() or "", d.preedit()
    check(t == c[0] + "0" and not p, f"框里「{t}」，拼音行 `{p}`，要「{c[0]}0」")
    return f"「{t}」"


@case("T9-42", base="B0-九键", mutates="learn")
def t942(d):
    to_english(d)
    t9(d, "4355")
    want = word(d)
    d.clear()
    t9(d, "43556")
    before = d.field_text() or ""
    d.tap(d.screen().find(rid="button_backspace", where="ime").c, 0.6)
    f, c = d.field_text() or "", word(d)
    check(want and f == want[0], f"43556 ⌫ 后打着「{f}」，只按 4355 时第一个是 {want[:1]}")
    check(c[:3] == want[:3], f"⌫ 后候选 {c[:4]}，4355 的 {want[:4]}")
    return f"「{before}」→ ⌫ →「{f}」"


@case("T9-43", base="B0-九键", mutates="learn")
def t943(d):
    to_english(d)
    t9(d, "4663")
    c = word(d)
    if not c:
        raise Blocked("4663 没有候选")
    popup_pick(d, "4", "4")
    time.sleep(0.4)
    t, p = d.field_text() or "", d.preedit()
    check(t == c[0] + "4" and not p, f"框里「{t}」，拼音行 `{p}`，要「{c[0]}4」")
    return f"「{t}」"


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


@case("T9-45", base="B0-九键", mutates="learn")
def t945(d):
    # a password field switches to English on the full keyboard, from 九键 too (keyboard.md); 九键英文 not reached there
    d.open_page("password")
    d._keys = None
    label = d.im_label()
    k = d.keys(refresh=True)
    check("英文" in label, f"密码框里空格「{label}」")
    check("q" in k and d.key_node("中/英") is None, f"密码框里不是全键盘：{sorted(k)[:12]}")
    d.tap(k["q"], 0.8)
    t = d.field_text() or ""
    c = d.cands(timeout=1)
    check(len(t) == 1 and not c, f"密码框里「{t}」，候选 {c[:4]}")
    d.open_page("text")
    d._keys = None
    back = d.im_label()
    check("九键" in back, f"回到普通文本框：{back}")
    return f"密码框「{label}」全键盘，「{t}」；回到 {back}"


@case("T9-46", base="B0-九键", mutates="learn")
def t946(d):
    t9(d, "64")
    to_english(d)
    t1, p1 = d.field_text() or "", d.preedit()
    caps = d.screen().find(rid="button_caps", where="ime")
    d.clear()
    t9(d, "4663")
    c = word(d)
    to_english(d)
    t2, p2 = d.field_text() or "", d.preedit()
    back = d.im_label()
    check(t1 == "ni" and not p1, f"拼音 64 时点 中/英：框里「{t1}」，拼音行 `{p1}`")
    check(caps, "点 中/英 后没有 ⇧（不是九键英文）")
    check(c and t2 == c[0] and not p2, f"英文 4663 时点 中/英：框里「{t2}」，拼音行 `{p2}`（候选 {c[:3]}）")
    check("九键" in back, f"回到「{back}」")
    return f"拼音→英文「{t1}」；英文→拼音「{t2}」（{back}）"


@case("T9-47", base="B0-设置", mutates=True)
def t947(d):
    add_im(d, "九键拼音")
    gear(d, "九键拼音")
    d.tap_text("按键长按", after=1.2)
    set_long_press(d, ", .")
    d.open_page("text")
    d.switch_im("九键")
    to_english(d)
    items, xy = popup(d, "1")
    texts = [n.text for n in items]
    tgt = next((n for n in items if n.text == "("), None)
    if not tgt:
        popup_away(d, xy)
        raise Blocked(f"长按 1 弹出 {texts}")
    popup_slide(d, xy, tgt)
    t = d.field_text() or ""
    want = [":", ";", '"', "(", ")", "@", "&", "/", "*", "~"]
    miss = [x for x in want if x not in texts]
    full = [x for x in texts if any(0xFF00 <= ord(ch) <= 0xFFEF or ch in "“”" for ch in x)]
    check(not miss, f"长按 1 少了 {miss}：{texts}")
    check(not full, f"长按 1 有全角 {full}")
    check("，" not in texts and "。" not in texts, f"九键拼音改的 1 键长按跑到九键英文：{texts}")
    check(t == "(", f"选 ( 后框里「{t}」")
    return f"弹出 {texts}；「{t}」"


@case("T9-48", base="B0-九键", mutates="learn")
def t948(d):
    out = []
    for key in ("9", "7"):
        d.clear()
        items, (x, y) = popup(d, key)
        row = [n for n in items if n.text.lower() in "pqrswxyz" + key]
        order = [n.text for n in sorted(row, key=lambda n: n.c[0])]
        d.touch(("move", x + 12, y - 8), ("sleep", 0.15), ("up", x + 12, y - 8))
        time.sleep(0.5)
        t1 = d.field_text() or ""
        d.clear()
        items, (x, y) = popup(d, key)
        on = next((n for n in items if n.text.lower() in "pqrswxyz" + key and n.b[0] <= x <= n.b[2]), None)
        if not on:
            popup_away(d, (x, y))
            raise Blocked(f"长按 {key}：手指正上方没有弹出的字，弹出 {[n.text for n in items]}")
        ty = on.c[1]
        d.touch(("move", x, (y + ty) // 2), ("sleep", 0.05), ("move", x, ty), ("sleep", 0.15), ("up", x, ty))
        time.sleep(0.5)
        t2 = d.field_text() or ""
        want = ["W", "X", "Y", "Z", "9", "w", "x", "y", "z"] if key == "9" else ["P", "Q", "R", "S", "7", "p", "q", "r", "s"]
        check(order == want, f"长按 {key} 弹出一行 {order}")
        check(t1 == key, f"长按 {key} 在键上挪一下松手：框里「{t1}」")
        check(t2 == on.text, f"长按 {key} 笔直往上滑到「{on.text}」松手：框里「{t2}」")
        out.append(f"{key}: 挪一下「{t1}」，上滑「{t2}」")
    return "；".join(out)


@case("T9-49", base="B0-九键", page="nosuggest", mutates="learn")
def t949(d):
    to_english(d)
    k = d.keys(refresh=True)
    d.taps([k["4"], k["shift"], k["4"]], gap=0.15, after=1.0)
    t = d.field_text() or ""
    check(t == "gG", f"4 ⇧ 4 后框里「{t}」")
    return f"「{t}」"


def remove_im(d, name):
    """settings 输入法 → 编辑, name ticked, 删除"""
    home(d, "输入法")
    d.tap_text("编辑", scroll=False, after=1.0)
    s = d.screen()
    n = s.find(text=name)
    box = next((k for k in n.parent.kids if k.cls == "CheckBox"), None) if n and n.parent else None
    if not box:
        raise Blocked(f"编辑时「{name}」一行没有勾选框")
    d.tap(box.c, 0.8)
    d.tap_text("删除", scroll=False, after=1.2)
    ok = next((k for k in d.screen().nodes if k.text in ("确定", "删除") and k.cls == "Button"), None)
    if ok:
        d.tap(ok.c, 1.0)
    if d.screen().find(text="编辑") is None:
        d.back(1.0)
    if d.screen().find(text=name):
        raise Blocked(f"删不掉「{name}」")


@case("T9-50", base="B0-设置", mutates=True)
def t950(d):
    add_im(d, "九键拼音")
    remove_im(d, "英文")
    d.open_page("text")
    d.switch_im("九键")
    to_english(d)
    s = d.screen()
    l1, caps1, redo1 = d.im_label(s), s.find(rid="button_caps", where="ime"), d.key_node("重输", s)
    add_im(d, "英文")
    d.open_page("text")
    d.switch_im("九键")
    to_english(d)
    s = d.screen()
    l2, caps2 = d.im_label(s), s.find(rid="button_caps", where="ime")
    d.press("globe", after=1.0)
    d._keys = None
    l3 = d.im_label()
    k = d.keys(refresh=True)
    check("九键" in l1 and not caps1 and redo1, f"没有英文时点 中/英：空格「{l1}」，⇧ {bool(caps1)}，重输 {bool(redo1)}")
    check("英文" in l2 and caps2, f"加回英文后点 中/英：空格「{l2}」，⇧ {bool(caps2)}")
    check("英文" in l3 and "q" in k and d.key_node("中/英") is None, f"九键英文点地球键：「{l3}」，键 {sorted(k)[:12]}")
    return f"没有英文「{l1}」；加回后「{l2}」；地球键「{l3}」全键盘"


def picker(d, name):
    """the globe's long press: the system's input method picker, name tapped"""
    d.hold(d.keys(refresh=True)["globe"], 900)
    time.sleep(1.0)
    s = d.screen()
    n = next((n for n in s.nodes if n.text == name), None)
    if not n:
        d.back(0.8)
        raise Blocked(f"选择框里没有「{name}」：{s.texts(where='all')[:12]}")
    d.tap(n.c, 1.2)
    d._keys = None


@case("T9-51", base="B0-九键", mutates=True)
def t951(d):
    to_english(d)
    picker(d, "九键拼音")
    l1, redo = d.im_label(), d.key_node("重输")
    picker(d, "英文")
    l2, k = d.im_label(), d.keys(refresh=True)
    check("九键" in l1 and redo, f"选九键拼音后：「{l1}」，重输 {bool(redo)}")
    check("英文" in l2 and "q" in k and d.key_node("中/英") is None, f"选英文后：「{l2}」，键 {sorted(k)[:12]}")
    return f"「{l1}」→「{l2}」全键盘"


@case("T9-52", base="B0-设置", mutates=True)
def t952(d):
    add_im(d, "九键拼音")
    gear(d, "九键拼音")
    d.tap_text("按键字母", after=1.2)
    d.tap_text("DEF, 3", where="all", after=1.2)
    f = next((n for n in d.screen().nodes if n.cls == "EditText"), None)
    if not f:
        raise Blocked("3 键的字母对话框没有输入框")
    d.tap((f.b[2] - 20, f.c[1]), 1.0)
    d.bksp(6)
    d.hide_keyboard()
    f = next((n for n in d.screen().nodes if n.cls == "EditText"), None)
    if f and f.text:
        raise Blocked(f"输入框没清空：「{f.text}」")
    d.tap_text("确定", where="all", scroll=False, after=1.0)
    d.open_page("text")
    d.switch_im("九键")
    items, xy = popup(d, "3")
    popup_away(d, xy)
    time.sleep(0.4)
    digit = next((n for n in items if n.text == "3"), None)
    if not digit:
        raise Blocked(f"长按 3 弹出 {[n.text for n in items]}")
    rows = {}
    for n in items:
        if n.c[1] < digit.b[1]:
            rows.setdefault(n.c[1] // 20, []).append(n.text)
    sizes = [len(r) for _, r in sorted(rows.items())]
    total = sum(sizes)
    check(total and max(sizes) == min(total, 5), f"长按 3 的符号每行 {sizes}：{list(rows.values())}")
    return f"符号每行 {sizes}"


@case("T9-53", base="B0-设置", mutates=True)
def t953(d):
    add_im(d, "九键拼音")
    remove_im(d, "英文")
    add_im(d, "英文")
    home(d, "按键")
    d.choose("空格键长按行为", "轮换输入法")
    d.open_page("text")
    d.switch_im("九键")
    to_english(d)
    d.press("符", after=1.0)
    s = d.screen()
    sp = next((n for n in s.ime() if n.clickable
               and (n.desc == "空格" or any((m.text or "").startswith("空格") for m in n.walk()))), None)
    # the picker's own space, found, or the case proves nothing about the picker's round
    xy = sp.c if sp else None
    if not xy:
        raise Blocked(f"符号键盘上找不到空格：{s.texts(where='ime')[:20]}")
    d.hold(xy, 900)
    time.sleep(1.0)
    s = d.screen()
    back = s.find(text="返回", where="ime")
    if not back:
        raise Blocked(f"长按空格后符号键盘上没有「返回」：{s.texts(where='ime')[:20]}")
    d.tap(back.c, 1.0)
    d._keys = None
    l, k = d.im_label(), d.keys(refresh=True)
    check("英文" in l and "q" in k and d.key_node("中/英") is None, f"返回后：「{l}」，键 {sorted(k)[:12]}")
    return f"返回后「{l}」全键盘"
