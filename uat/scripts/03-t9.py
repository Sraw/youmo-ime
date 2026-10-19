"""uat/03-t9.md (B0-九键). The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import (Blocked, add_im, case, check, first, flick, gear, glide_on, glide_shot, has, popup,
                 popup_pick, popup_slide)


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
          and s.find(rid="button_return", where="ime"), "没有 ⌫、地球键或回车")
    return f"左列 {col[:5]}；1–9、⌫、重输、0、符、123、空格、地球键、回车都在"


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


@case("T9-09", base="B0-九键")
def t909(d):
    items, xy = popup(d, "5")
    texts = [n.text for n in items]
    tgt = next((n for n in items if n.text.lower() == "l"), None)
    if not tgt:
        d.touch(("up", *xy))
        raise Blocked(f"长按 5 弹出 {texts}")
    popup_slide(d, xy, tgt)
    t9(d, "464")
    c = d.all_cands()
    p = d.preedit()
    check(has(c, "领") or has(c, "零") or has(c, "另") or has(c, "令"), f"`{p}` 候选 {c[:8]}")
    return f"弹出 {texts}；`{p}` {c[:4]}"


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
    titles = [x for x in ("通用输入设置", "连按选字母", "按键字母") if x in t]
    check(titles == ["通用输入设置", "连按选字母", "按键字母"], f"九键页 {t[:12]}")
    check(any("只有这一种读法" in x for x in t), f"连按选字母的说明 {t[:12]}")
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
    return "九键页三项；纠正错拼和 u↔v 都写着不适用：九键拼音"


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
    for how, keys, k, i in (("长按选 2", "64", "2", 1), ("滑 1", "94", "1", 0)):
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
        check(t == c[i] and not p, f"打字中{how}：框里「{t}」、拼音行 `{p}`，不是候选「{c[i]}」")
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
