"""uat/07-shuangpin.md (B0-双拼). The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import Blocked, Fail, add_im, case, check, choose, first, gear, glide_shot, has, home, key_texts


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


@case("SP-01", base="B0-双拼", mutates="learn")
def sp01(d):
    d.type("nihk")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"框里「{t}」")
    return f"「{t}」"


@case("SP-02", base="B0-设置", mutates=True)
def sp02(d):
    scheme(d, "小鹤")
    d.type("nihc")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"框里「{t}」（候选 {c[:3]}）")
    return f"「{t}」"


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
    scheme(d, "紫光", add=False)
    k = semi_key(d)
    if not k:
        raise Fail(f"紫光方案 l 右边没有 ; 键：l 那排 {key_texts(d, 'l', d.screen())}")
    d.type("x")
    d.tap(k.c, 0.6)
    c = d.candidates()
    check(has(c, "性") or has(c, "星") or has(c, "行") or has(c, "形"), f"紫光 x; 候选 {c[:6]}")
    d.clear()
    d.tap(k.c, 0.6)
    t = d.field_text() or ""
    check(t == "；", f"紫光空框点 ; 后框里「{t}」")
    got += f"；紫光 x; {c[:3]}，空框「{t}」"
    d.clear()
    scheme(d, "自然码", add=False)
    s = d.screen()
    check(d.key_node("l", s), "自然码的键盘上没有 l 键")
    check(not semi_key(d, s), "自然码的键盘上也有 ; 键")
    return got + "；自然码没有 ; 键"


@case("SP-04", base="B0-双拼")
def sp04(d):
    d.type("aj")
    c = d.cands()
    check(has(c, "安"), f"候选 {c[:6]}")
    return f"候选 {c[:4]}"


@case("SP-05", base="B0-双拼")
def sp05(d):
    d.type("fx")
    p1, c1 = d.preedit(), d.cands()
    d.clear()
    d.type("vg")
    p2, c2 = d.preedit(), d.cands()
    check(p1 == "f x" and (has(c1, "发现") or has(c1, "方向")), f"fx：`{p1}` {c1[:5]}")
    check(p2 == "zheng", f"vg：`{p2}` {c2[:3]}")
    return f"fx `{p1}` {c1[:3]}；vg `{p2}`"


@case("SP-06", base="B0-设置", mutates=True)
def sp06(d):
    add_im(d, "双拼")
    gear(d, "双拼")
    t = d.all_texts()
    check("方案" in t, f"双拼设置 {t[:12]}")
    check("中英混输" not in t, "双拼设置里有中英混输")
    return "有方案，没有中英混输"


@case("SP-07", base="B0-双拼", mutates=True)
def sp07(d):
    want = "这个誓言很难"
    d.switch_im("拼音")
    d.type("zhegeshiyanhennan")
    c0 = d.cands()
    if first(c0) == want:
        raise Blocked(f"整句首选已经是「{want}」：{c0[:3]}")
    try:
        for w in ("这个", "誓言", "很难"):
            d.cands()
            d.pick(w)
    except Fail as e:
        raise Blocked(f"前提（PY-34 的步骤）没到：{e}")
    time.sleep(0.5)
    if d.field_text() != want:
        raise Blocked(f"前提没到：框里「{d.field_text()}」")
    d.clear()
    d.switch_im("双拼")
    d.type("vegeuiyjhfnj")
    c = d.cands()
    check(first(c) == want, f"双拼打整串：{c[:4]}（拼音行 `{d.preedit()}`）")
    return f"{c[:3]}"


@case("SP-08", base="B0-双拼")
def sp08(d):
    d.type("sj")
    c1 = d.cands()
    d.clear()
    d.type("xq")
    c2 = d.cands()
    import re
    clock = [x for x in c1 if re.search(r"\d", x)]
    week = [x for x in c2 if "星期" in x or "周" in x]
    check(has(c1, "三") or has(c1, "散"), f"sj 的候选 {c1[:6]}")
    check(not clock, f"sj 的候选里有时间 {clock}")
    check(has(c2, "修") or has(c2, "秀"), f"xq 的候选 {c2[:6]}")
    check(not week, f"xq 的候选里有星期 {week}")
    return f"sj {c1[:3]}；xq {c2[:3]}"


@case("SP-09", base="B0-设置", mutates=True, visual=True)
def sp09(d):
    add_im(d, "双拼")
    home(d, "通用输入")
    d.switch("滑行输入", True)
    d.open_page("text")
    d.switch_im("双拼")
    k = d.keys(refresh=True)
    pts = [k[c] for c in "nihk"]
    glide_shot(d, pts, "划 n-i-h-k，抬手前")
    s = d.screen()
    p = d.preedit(s)
    c = d.all_cands()
    check(p.replace("'", " ") == "ni hao", f"拼音行 `{p}`")
    check(has(c, "你好"), f"候选 {c[:5]}")
    return f"`{p}` {c[:3]}"


def emoji_at(c, e):
    """where emoji e is among the candidates c, or None"""
    return next((k for k, x in enumerate(c) if x == e), None)


@case("SP-10", base="B0-设置", mutates=True)
def sp10(d):
    out = []
    for i, name in enumerate(["自然码", "微软", "小鹤"]):
        scheme(d, name, add=i == 0)
        for keys, e in (("haha", "😃"), ("dmzj", "👍")):
            d.type(keys)
            c = d.cands(want=e)
            k = emoji_at(c, e)
            check(k is not None and k >= 2, f"{name} {keys}：候选 {c[:6]}")
            d.clear()
            out.append(f"{name} {keys} {c[:k + 1]}")
        d.hide_keyboard()
        d.open_settings()
    return "；".join(out)


@case("SP-11", base="B0-双拼")
def sp11(d):
    import re
    iso = d.sh("date +%Y-%m-%d").strip()
    out = []
    for keys, w, ok in (("riqi", "日期", lambda x: x == iso), ("uijm", "时间", lambda x: re.match(r"^\d\d:\d\d$", x)),
                        ("xyqi", "星期", lambda x: re.match(r"^星期[一二三四五六日]$", x))):
        d.type(keys)
        d.cands()
        c = d.all_cands()
        k = next((i for i, x in enumerate(c) if ok(x)), None)
        check(c and c[0] == w, f"{keys}：第一个 {c[:3]}")
        check(k is not None and k >= 2, f"{keys}：候选 {c[:7]}")
        d.clear()
        out.append(f"{keys} {c[:k + 1]}")
    return "；".join(out)
