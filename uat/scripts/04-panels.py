"""uat/04-panels.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import Blocked, case, check, script


@case("PAN-30")
def pan30(d):
    # 「（」 on the device, the first page of 中文 (《 is further down; it and the others: PanelUatTest, AutoPairsTest)
    d.open_page("text")
    d.clear()
    ime(d, "符")
    ime(d, "中文")
    time.sleep(0.4)
    if "（" not in ime_texts(d):
        raise Blocked(f"「中文」里没有「（」：{ime_texts(d)[:40]}")
    ime(d, "（")
    t = d.field_text() or ""
    check(t == "（）", f"点「（」后框里「{t}」")
    d.press("bksp", after=0.5)
    t2 = d.field_text() or ""
    check(t2 == "", f"⌫ 后框里「{t2}」")
    return f"「{t}」，⌫ 后「{t2}」"


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
    # the emoji's cells: colour emoji, centred; the symbol panel went back to the letters with the ，
    ime(d, "表情")
    time.sleep(0.6)
    d.shot("kb", "表情面板的格子")
    # the 颜文字's: a long one whole, not cut at its cell's sides
    ime(d, ":-)")
    time.sleep(0.6)
    long = "(｡>﹏<｡)ﾉﾞ✧*。"
    for _ in range(12):
        s = d.screen()
        n = s.find(desc=long, where="ime")
        if n:
            break
        # from 70% to 30% of the panel's height: the grid, above the picker's bottom row
        w = s.find(rid="input_window", where="ime")
        if not w:
            raise Blocked("颜文字面板不在")
        h = w.b[3] - w.b[1]
        x, y0, y1 = (w.b[0] + w.b[2]) // 2, w.b[1] + h * 7 // 10, w.b[1] + h * 3 // 10
        d.swipe((x, y0), (x, y1), 300, 0.6)
    check(n is not None, f"颜文字里找不到 {long}")
    d.shot("kb", "颜文字长字")
    return f"框里「{t}」，表情和颜文字格子见图"


@case("PAN-35", visual=True)
def pan35(d):
    full = {"+", "-", "*", "/", ",", "=", "."}
    d.step("测试页的小数框：声明小数，什么都收")
    d.open_page("decimal")
    d.clear()
    keys = d.keys(refresh=True)
    check(full <= set(keys), f"小数框不是完整的数字键盘：{sorted(keys)}")
    d.shot("ime", "小数框：完整的数字键盘")
    for k in ("-", "5"):
        d.tap(keys[k], 0.4)
    typed = d.field_text() or ""
    check(typed == "-5", f"小数框点 - 5 打出的是 {typed!r}")
    d.step("输入测试台的带符号数字框")
    script("02-keyboard").testbed_field(d, "signed")
    keys = d.keys(refresh=True)
    check(full <= set(keys), f"带符号数字框不是完整的数字键盘：{sorted(keys)}")
    for k in ("-", "3"):
        d.tap(keys[k], 0.4)
    time.sleep(0.5)
    signed = d.testbed_state()["signed"]["text"]
    check(signed == "-3", f"带符号数字框点 - 3 打出的是 {signed!r}")
    return f"两个框都是完整的数字键盘；小数框「{typed}」，带符号数字框「{signed}」"
