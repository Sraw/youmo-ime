"""uat/07-shuangpin.md (B0-双拼). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import Blocked, Fail, add_im, case, check, choose, first, gear, glide_shot, has, home, key_texts, script


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
    return got


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


@case("SP-07", base="B0-双拼", mutates=True)
def sp07(d):
    # PY-34's word, made yours in 全拼
    want = "汪小明"
    d.switch_im("拼音")
    try:
        script("06-pinyin").learn_sentence(d)
    except Fail as e:
        raise Blocked(f"前提（PY-34 的步骤）没到：{e}")
    d.switch_im("双拼")
    d.type("whxcmy")
    c = d.cands()
    check(first(c) == "王小明" and c[1:2] == [want], f"双拼 whxcmy：{c[:4]}（拼音行 `{d.preedit()}`）")
    return f"{c[:3]}"


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
