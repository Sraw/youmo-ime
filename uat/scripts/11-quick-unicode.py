"""uat/11-quick-unicode.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import case, check, flick, has, BAR_BOTTOM


def bar_label(d, s=None):
    s = s or d.screen()
    return [n.text for n in s.ime() if n.text and n.c[1] < BAR_BOTTOM]


def quick(d):
    k = d.key_node("符")
    d.hold(k.c, 900)
    time.sleep(0.6)


def unicode_mode(d):
    k = d.key_node("123")
    d.hold(k.c, 900)
    time.sleep(0.6)


@case("QU-01")
def qu01(d):
    quick(d)
    lab = bar_label(d)
    check(any("快速输入" in x for x in lab), f"栏上 {lab}")
    return f"栏上 {lab[:3]}"


@case("QU-02", mutates="learn")
def qu02(d):
    quick(d)
    d.type("yawn")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(c and any(ch in c[0] for ch in "()（）￣"), f"候选 {c[:3]}")
    check(t == c[0].split(" ")[0], f"框里「{t}」，第一个是「{c[0] if c else ''}」")
    return f"候选 {c[:2]}，框里「{t}」"


@case("QU-03", mutates="learn")
def qu03(d):
    quick(d)
    flick(d, "x")
    d.type("smile")
    flick(d, "x")
    time.sleep(0.4)
    p = d.preedit()
    d.space()
    t = d.field_text()
    check(t == "😄", f"`{p}` 空格后框里「{t}」")
    return f"`{p}` → 「{t}」"


@case("QU-04")
def qu04(d):
    quick(d)
    flick(d, "m")
    d.type("alpha")
    c = d.cands()
    p = d.preedit()
    check(has(c, "α") or any("α" in x for x in c), f"`{p}` 候选 {c[:5]}")
    return f"`{p}` {c[:3]}"


@case("QU-05")
def qu05(d):
    quick(d)
    d.type("ya")
    d.bksp(3)
    time.sleep(0.4)
    s = d.screen()
    lab = bar_label(d, s)
    tb = d.toolbar(s)
    d.type("ni")
    c = d.cands()
    p = d.preedit()
    check(tb and not any("快速输入" in x for x in lab), f"删光后栏上 {lab}")
    check(p == "ni" and has(c, "你"), f"再打 ni：`{p}` {c[:3]}")
    return f"回到工具栏；ni → {c[:2]}"


@case("QU-06", mutates="learn")
def qu06(d):
    unicode_mode(d)
    lab = bar_label(d)
    d.type("degree")
    c = d.all_cands()
    check(any("Unicode" in x for x in lab), f"栏上 {lab}")
    check(any(x.split(" ")[0] == "°" for x in c), f"候选 {c[:6]}")
    d.pick("°")
    t = d.field_text()
    check(t == "°", f"框里「{t}」")
    return f"栏上 {lab[:2]}；「{t}」"


@case("QU-07")
def qu07(d):
    unicode_mode(d)
    d.press("shift")
    d.type("u")
    # + 4 F 6 0 are key corners (down-swipes); a pause between them, or two swipes in a row
    # come out as a plain tap
    for k in ("d", "r", "f", "y", "p"):
        if k == "f":
            d.press("shift")
            d.type("f")
        else:
            flick(d, k)
        time.sleep(0.4)
    c = d.cands()
    p = d.preedit()
    check(any(x.split(" ")[0] == "你" for x in c), f"`{p}` 候选 {c[:5]}")
    return f"`{p}` {c[:3]}"
