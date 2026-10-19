"""uat/08-english.md (B0-英文). The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import Blocked, case, check, gear, glide_on


def en_settings(d):
    gear(d, "英文")


def sym_comma(d):
    """the , of the bottom row (English)"""
    d.press(",")


@case("EN-01", base="B0-英文")
def en01(d):
    d.type("hello")
    c = d.cands()
    check(c and c[0] == "hello", f"候选 {c[:4]}")
    check(len(c) > 1, f"右边没有别的词：{c}")
    return f"候选 {c[:4]}"


@case("EN-02", base="B0-英文", mutates="learn")
def en02(d):
    d.type("teh")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(c[:2] == ["the", "teh"], f"候选 {c[:4]}")
    check(t == "the ", f"框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


@case("EN-03", base="B0-英文", mutates="learn")
def en03(d):
    d.type("dont")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "don't ", f"框里「{t}」")
    return f"「{t}」"


@case("EN-04", base="B0-英文", mutates="learn", clean=True)
def en04(d):
    d.type("teh")
    d.cands()
    d.space()
    t0 = d.field_text()
    d.bksp()
    t1 = d.field_text()
    d.space()
    d.type("teh")
    d.cands()
    d.space()
    t2 = d.field_text()
    check(t0 == "the ", f"第一次空格后「{t0}」")
    check(t1 == "teh", f"退格后「{t1}」")
    check(t2.endswith("teh "), f"最后框里「{t2}」")
    return f"「{t0}」→退格「{t1}」→「{t2}」"


@case("EN-05", base="B0-英文", mutates="learn")
def en05(d):
    d.type("teh")
    c = d.cands()
    if c[:2] != ["the", "teh"]:
        raise Blocked(f"候选 {c[:3]}")
    d.pick(2)
    t = d.field_text()
    check(t == "teh ", f"框里「{t}」")
    return f"「{t}」"


@case("EN-06", base="B0-英文", mutates="learn")
def en06(d):
    d.type("hi")
    d.cands()
    d.space()
    d.press(".")
    t = d.field_text()
    check(t == "hi. ", f"框里「{t}」")
    return f"「{t}」"


@case("EN-07", base="B0-英文", mutates="learn")
def en07(d):
    d.type("ok")
    d.cands()
    sp = d.keys(refresh=True)["space"]
    d.taps([sp, sp], gap=0.1, after=0.6)
    t = d.field_text()
    check(t == "ok. ", f"框里「{t}」")
    return f"「{t}」"


@case("EN-08", base="B0-英文", mutates="learn")
def en08(d):
    d.type("thank")
    d.cands()
    d.space()
    time.sleep(0.5)
    c = d.candidates()
    check(c and "you" in [x.lower() for x in c[:5]], f"联想 {c[:6]}")
    return f"联想 {c[:4]}"


@case("EN-09", base="B0-英文", page="sentence", mutates="learn")
def en09(d):
    d.type("hi")
    d.cands()
    d.space()
    d.press(".")
    d.type("ok")
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    ok = t == "Hi. Ok" or (t == "Hi. " and c and c[0] == "Ok")
    check(ok, f"框里「{t}」，候选 {c[:3]}")
    return f"「{t}」 {c[:2]}"


@case("EN-10", base="B0-英文", page="url")
def en10(d):
    d.type("abc")
    time.sleep(0.4)
    s = d.screen()
    t = d.field_text(s)
    check(t == "abc", f"框里「{t}」")
    return f"「{t}」"


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


@case("EN-12", base="B0-设置", mutates=True)
def en12(d):
    en_settings(d)
    d.tap_text("英文词库", after=1.2)
    s = d.screen()
    add = s.find(desc="添加") or next((n for n in s.app() if n.text == "+" or n.desc in ("新增", "添加词")), None)
    if not add:
        raise Blocked(f"英文词库页没有 +：{s.texts()[:8]} {[n.desc for n in s.app() if n.desc]}")
    d.tap(add.c, 1.0)
    s = d.screen()
    e = next((n for n in s.nodes if n.cls == "EditText"), None)
    if not e:
        raise Blocked("添加没有输入框")
    d.tap(e.c, 0.8)
    d.switch_im("拼音")
    d.type("qwzx")
    d.enter()
    d.hide_keyboard()
    s = d.screen()
    ok = next((n for n in s.nodes if n.text in ("确定", "保存", "添加")), None)
    d.tap(ok.c, 1.0)
    d.open_page("text")
    d.switch_im("英文")
    d.type("qwz")
    c = d.cands()
    d.type("x")
    d.cands()
    d.space()
    t = d.field_text()
    check(any(x == "qwzx" for x in c), f"qwz 的候选 {c[:5]}")
    check(t == "qwzx ", f"框里「{t}」")
    return f"qwz {c[:3]}；「{t}」"


@case("EN-13", base="B0-英文", page="email")
def en13(d):
    d.type("teh")
    time.sleep(0.4)
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    check(t == "teh" and not c, f"框里「{t}」，候选 {c[:3]}")
    return f"「{t}」，没有候选"


@case("EN-14", base="B0-英文", page="nosuggest", mutates="learn")
def en14(d):
    d.type("teh")
    c = d.candidates()
    d.space()
    t = d.field_text()
    check(t == "teh " and not c, f"框里「{t}」，候选 {c[:3]}")
    return f"「{t}」"


@case("EN-15", base="B0-设置", mutates=True)
def en15(d):
    en_settings(d)
    d.switch("单词提示", False)
    d.open_page("text")
    d.switch_im("英文")
    d.type("teh")
    time.sleep(0.4)
    c = d.candidates()
    d.space()
    t = d.field_text()
    check(not c, f"有候选 {c[:3]}")
    check(t == "teh ", f"框里「{t}」")
    return f"「{t}」，没有候选"


@case("EN-16", base="B0-英文", mutates="learn")
def en16(d):
    d.type("fuk")
    c = d.cands()
    d.space()
    t = (d.field_text() or "").strip().lower()
    check(t not in ("fuck", "fucks"), f"上屏「{t}」（候选 {c[:3]}）")
    return f"上屏「{t}」"


@case("EN-17", base="B0-设置", mutates=True)
def en17(d):
    glide_on(d, "英文")
    d.type("ok")
    d.cands()
    d.glide("helo")
    t = d.field_text()
    check(t == "ok hello ", f"框里「{t}」")
    return f"「{t}」"


@case("EN-18", base="B0-设置", mutates=True)
def en18(d):
    glide_on(d, "英文")
    d.glide("helo")
    sym_comma(d)
    t = d.field_text()
    check(t == "hello, ", f"框里「{t}」")
    return f"「{t}」"


def weird(t):
    return any(ch in t for ch in "�?□") if t else True


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


@case("EN-20", base="B0-设置", mutates=True)
def en20(d):
    glide_on(d, "英文", "nosuggest")
    out = []
    # 1: Hi. then a glide
    d.press("shift")
    d.type("hi")
    d.press(".")
    d.space()
    d.glide("helo")
    out.append(d.field_text())
    d.clear()
    d.type("abc ")
    d.glide("helo")
    out.append(d.field_text())
    d.clear()
    d.type("abc")
    d.bksp()
    d.glide("helo")
    out.append(d.field_text())
    d.clear()
    d.type("hello world")
    f = d.field()
    # long-press world to select it: the words' ink on a screenshot, the last word's middle
    im = d.png().convert("L")
    x0, y0, x1, y1 = f.b
    bg = im.getpixel((x1 - 12, f.c[1]))
    cols = [any(abs(im.getpixel((x, y)) - bg) > 90 for y in range(y0 + 15, y1 - 15)) for x in range(x0 + 8, x1 - 8)]
    words, start, gap = [], None, 0
    for i, v in enumerate(cols + [False] * 30):
        if v:
            if start is None:
                start = i
            gap = 0
            end = i
        elif start is not None:
            gap += 1
            if gap > 14:
                words.append((x0 + 8 + start, x0 + 8 + end))
                start = None
    if len(words) < 2:
        raise Blocked(f"框里找不到两个词的字形：{words}")
    d.hold(((words[-1][0] + words[-1][1]) // 2, f.c[1]), 900)
    time.sleep(0.8)
    d.bksp()
    d.glide("helo")
    out.append(d.field_text())
    want = ["Hi. hello ", "abc hello ", "ab hello ", "hello hello "]   # the field: autocapitalize=off
    bad = [(w, g) for w, g in zip(want, out) if w != g]
    check(not bad, f"应为/实际 {bad}")
    return f"{out}"


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
    return f"「{t}」；「{t2}」；「{t3}」"
