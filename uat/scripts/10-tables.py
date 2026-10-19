"""uat/10-tables.md (B0-五笔 and the other tables). The .md is the source of truth; each function
follows its case's 前提/操作/预期."""
import re
import time

from uat import PKG, Blocked, add_im, case, script, check, first, gear, glide_shot, has, home, popup, popup_slide


def word(c):
    return c.split(" ")[0]


def words(cs):
    return [word(c) for c in cs]


def table_settings(d, name):
    add_im(d, name)
    gear(d, name)


def on_page(d, label):
    d.open_page("text")
    d.switch_im(label)


@case("TBL-01", base="B0-五笔", mutates="learn")
def tbl01(d):
    d.type("q")
    d.cands()
    d.space()
    d.type("w")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "我人", f"框里「{t}」")
    return f"「{t}」"


@case("TBL-02", base="B0-五笔", mutates="learn")
def tbl02(d):
    d.type("ftjb")
    time.sleep(0.4)
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(t == "都" and not p, f"框里「{t}」，编码行 `{p}`")
    return "「都」直接上屏"


@case("TBL-03", base="B0-五笔", mutates="learn")
def tbl03(d):
    d.type("wqiy")
    c = d.cands()
    d.type("w")
    s = d.screen()
    t1, p1 = d.field_text(s), d.preedit(s)
    d.type("q")
    d.cands()
    d.space()
    t = d.field_text()
    check(t1 == "你" and p1 == "w", f"点第五键 w 后框里「{t1}」，编码 `{p1}`（wqiy 候选 {c[:3]}）")
    check(t == "你你", f"最后「{t}」")
    return f"「{t1}」+`{p1}` → 「{t}」"


@case("TBL-04", base="B0-设置", mutates=True)
def tbl04(d):
    table_settings(d, "五笔")
    d.switch("只有一个候选时直接上屏", False)
    on_page(d, "五笔")
    d.type("ftjb")
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    d.space()
    t2 = d.field_text()
    check(not t and has(c, "都"), f"打完四码框里「{t}」，候选 {c[:3]}")
    check(t2 == "都", f"空格后「{t2}」")
    return f"留在候选 {c[:2]}；空格后「{t2}」"


@case("TBL-05", base="B0-二笔")
def tbl05(d):
    d.type("eaaa")
    s = d.screen()
    p, c, t = d.preedit(s), d.candidates(s), d.field_text(s)
    d.type("q")
    s = d.screen()
    p2, c2, t2 = d.preedit(s), d.candidates(s), d.field_text(s)
    check(p == "eaaa" and not c and not t, f"eaaa：编码 `{p}`，候选 {c[:3]}，框里「{t}」")
    check(p2 == "q" and first(c2) == "起" and not t2, f"点 q 后编码 `{p2}`，候选 {c2[:3]}，框里「{t2}」")
    return f"`{p}` 无候选 → `{p2}` {c2[:2]}"


@case("TBL-06", base="B0-五笔")
def tbl06(d):
    d.type("w")
    c = d.cands()
    # the case's 你 is an example (it is not on w's first page): the keys still to press show
    hinted = [x for x in c if " " in x]
    check(hinted, f"候选旁没有编码提示：{c[:5]}")
    return f"候选 {c[:5]}"


@case("TBL-07", base="B0-设置", mutates=True)
def tbl07(d):
    table_settings(d, "五笔")
    d.switch("显示编码提示", False)
    on_page(d, "五笔")
    d.type("w")
    c = d.cands()
    check(c and all(" " not in x for x in c), f"候选 {c[:5]}")
    return f"候选 {c[:4]}"


@case("TBL-08", base="B0-五笔")
def tbl08(d):
    d.type("zni")
    c = d.cands()
    you = next((x for x in c if word(x) == "你"), None)
    check(has(c, "你") and has(c, "泥") or has(c, "你") and len(c) > 3, f"候选 {c[:6]}")
    check(you and "wqiy" in you, f"「你」旁边是 {you}")
    return f"候选 {c[:4]}"


@case("TBL-09", base="B0-五笔")
def tbl09(d):
    d.type("wziy")
    c = d.all_cands(pages=15)
    check(has(c, "你"), f"候选 {c[:6]}")
    return f"候选 {c[:4]}"


@case("TBL-10", base="B0-五笔", mutates="learn")
def tbl10(d):
    d.type("khlg")
    time.sleep(0.4)
    s = d.screen()
    c, t = d.candidates(s), d.field_text(s)
    check(first(c) == "中国" or t == "中国", f"候选 {c[:3]}，框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


def make_jingyuan(d):
    for _ in range(3):
        d.type("qgyi")
        d.cands()
        d.pick("鲸")
        d.type("lfqv")
        time.sleep(0.4)
    t = d.field_text()
    if t != "鲸园" * 3:
        raise Blocked(f"三遍后框里「{t}」")
    d.clear()


@case("TBL-11", base="B0-五笔", mutates="learn", clean=True)
def tbl11(d):
    make_jingyuan(d)
    d.type("qglf")
    time.sleep(0.4)
    s = d.screen()
    c, t = d.candidates(s), d.field_text(s)
    check(has(c, "鲸园") and not t, f"候选 {c[:4]}，框里「{t}」")
    return f"候选 {c[:4]}"


@case("TBL-12", base="B0-设置", mutates=True)
def tbl12(d):
    home(d, "词库与短语", "用户词库", "手动添加的词")
    s = d.screen()
    add = next((n for n in s.app() if "添加" in n.desc), None)
    if not add:
        raise Blocked(f"手动添加的词没有 +：{[n.desc for n in s.app() if n.desc]}")
    d.tap(add.c, 1.0)
    s = d.screen()
    edits = [n for n in s.nodes if n.cls == "EditText"]
    if len(edits) < 1:
        raise Blocked("添加没有输入框")
    d.tap(edits[0].c, 0.8)
    d.switch_im("拼音")
    for code, w in (("jing", "鲸"), ("yuan", "园")):
        d.type(code)
        d.cands()
        d.pick(w)
    if d.field_text() != "鲸园":
        raise Blocked(f"词框里打出「{d.field_text()}」")
    # the pinyin left empty: it is filled in (jing yuan) as WD-02 shows
    d.hide_keyboard()
    for _ in range(4):   # the dialog's 确定 (button1), until the dialog is gone
        time.sleep(0.5)
        ok = next((n for n in d.screen().nodes if n.rid == "button1"), None)
        if not ok:
            break
        d.tap(ok.c, 1.2)
    s = d.screen()
    rows = s.texts()
    if any(n.cls == "EditText" for n in s.nodes if n.rid != "search") and "添加词" in rows[:2] or "鲸园" not in rows:
        raise Blocked(f"手动添加的词里没有鲸园：{rows[:8]}")
    add_im(d, "五笔")
    on_page(d, "五笔")
    d.type("qglf")
    time.sleep(0.4)
    c = d.candidates()
    check(has(c, "鲸园"), f"候选 {c[:4]}，框里「{d.field_text()}」")
    return f"候选 {c[:4]}"


@case("TBL-13", base="B0-五笔", mutates="learn")
def tbl13(d):
    d.type("g")
    c0 = d.cands()
    for _ in range(5):
        d.type("g")
        d.cands()
        d.pick(2)
    d.clear()
    d.type("g")
    c = d.cands()
    check(first(c) == first(c0) == "一", f"开始 {c0[:3]}，之后 {c[:3]}")
    return f"{c0[:2]} → {c[:2]}"


@case("TBL-14", base="B0-五笔", mutates="learn")
def tbl14(d):
    d.type("fghy")
    c0 = words(d.cands())
    d.pick("寸")
    d.clear()
    d.type("fghy")
    c = words(d.cands())
    check("寸" in c and "雨" in c and c.index("寸") < c.index("雨"), f"开始 {c0[:3]}，之后 {c[:3]}")
    return f"{c0[:3]} → {c[:3]}"


@case("TBL-15", base="B0-设置", mutates=True)
def tbl15(d):
    table_settings(d, "五笔")
    d.switch("常用的字词排前面", False)
    on_page(d, "五笔")
    d.type("fghy")
    c0 = words(d.cands())
    d.pick("寸")
    d.clear()
    d.type("fghy")
    c = words(d.cands())
    check(c[:3] == ["雨", "寸", "圷"], f"开始 {c0[:3]}，之后 {c[:3]}")
    return f"{c[:3]}"


@case("TBL-16", base="B0-五笔", mutates="learn")
def tbl16(d):
    d.type("khlg")
    time.sleep(0.5)
    if d.field_text() != "中国":
        d.space()
    c = d.candidates()
    two = next((x for x in c if len(word(x)) == 2), None)
    check(c, "上屏后没有联想")
    check(two, f"联想里没有两个字的词：{c[:6]}")
    d.pick(word(two))
    t = d.field_text()
    check(t == "中国" + word(two), f"点「{two}」后框里「{t}」")
    return f"联想 {c[:4]}；「{t}」"


@case("TBL-16a", base="B0-五笔", mutates=True)
def tbl16a(d):
    def zhongguo():
        d.type("khlg")
        time.sleep(0.5)
        if d.field_text() != "中国" and not (d.field_text() or "").endswith("中国"):
            d.space()
        return d.candidates()
    c = zhongguo()
    two = next((word(x) for x in c if len(word(x)) == 2), None)
    if not two:
        raise Blocked(f"联想里没有两个字的词：{c[:6]}")
    d.pick(two)
    d.clear()
    zhongguo()
    menu = d.long_pick(two)
    has_forget = any("忘记词汇" in x for x in menu)
    has_block = any("不再显示" in x for x in menu)
    n = next((n for n in d.screen().nodes if "忘记词汇" in n.text), None)
    if n:
        d.tap(n.c, 0.8)
    d.clear()
    c2 = zhongguo()
    if two in words(c2):
        d.long_pick(two)
        n = next((n for n in d.screen().nodes if "不再显示" in n.text), None)
        if n:
            d.tap(n.c, 0.8)
    d.clear()
    zhongguo()
    c3 = words(d.all_cands())
    check(has_forget and has_block, f"长按菜单 {menu[:8]}")
    check(two not in c3, f"不再显示后联想里还有「{two}」：{c3[:6]}")
    return f"「{two}」：菜单有忘记、不再显示；之后 {c3[:4]}"


@case("TBL-17", base="B0-五笔", mutates=True)
def tbl17(d):
    make_jingyuan(d)
    d.switch_im("拼音")
    d.type("jingyuan")
    c = d.cands()
    check(has(c, "鲸园"), f"拼音第一页 {c}")
    return f"第一页 {c[:4]}"


def add_bracket_to_p(d):
    """按键 → 按键字符 → p: add [ (typed from the 符 panel), 添加, 确定"""
    d.hide_keyboard()
    home(d, "按键", "按键字符")
    s = d.screen()
    k = next((n for n in s.app() if n.text == "p" and n.row() is not None), None)
    if not k:
        raise Blocked("按键字符页没有 p 键")
    d.tap(k.row().c, 1.2)
    e = next((n for n in d.screen().app() if "Edit" in n.cls), None)
    if not e:
        raise Blocked("没有添加字符的输入框")
    d.tap(e.c, 1.0)
    d.press("符", after=1.0)
    found = False
    for cat in ("括号", "英文"):
        n = d.visible(cat, d.screen(), where="ime")
        if n:
            d.tap(n.c, 0.6)
        for _ in range(3):
            m = next((m for m in d.screen().ime() if m.text == "[" and m.c[0] > 200), None)
            if m:
                d.tap(m.c, 0.8)
                found = True
                break
            d.swipe((640, 2000), (640, 1700), 400)
        if found:
            break
    if not found or d.field_text() != "[":
        raise Blocked(f"添加框里打不出 [：「{d.field_text()}」")
    d.hide_keyboard()
    d.tap_text("添加", scroll=False)
    d.tap_text("确定", scroll=False, after=1.0)
    d.open_page("text")
    d.switch_im("二笔")


@case("TBL-18", base="B0-二笔", mutates=True)
def tbl18(d):
    got = None
    for k in ("k", "p", "l", "o"):
        items, xy = popup(d, k)
        d.touch(("up", *xy))
        d.clear()
        if any(n.text in ("[", "【") for n in items):   # shown as 【 while typing Chinese
            got = k
            break
    if not got:   # the case: none has it, so give p one first
        add_bracket_to_p(d)
        got = "p"
    for k in ("k", "p", "l", "o"):
        items, xy = popup(d, k)
    items, xy = popup(d, got)
    tgt = next((n for n in items if n.text in ("[", "【")), None)
    if not tgt:
        d.touch(("up", *xy))
        raise Blocked(f"加过后长按 {got} 弹出 {[n.text for n in items]}")
    popup_slide(d, xy, tgt)
    d.type("ni")
    c = d.cands()
    check(has(c, "你") and any(" " in x for x in c[:3]), f"`{d.preedit()}` 候选 {c[:5]}")
    return f"长按 {got} 打 [；候选 {c[:4]}"


@case("TBL-19", base="B0-仓颉", mutates="learn")
def tbl19(d):
    d.type("onf")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你", f"框里「{t}」")
    return f"「{t}」"


@case("TBL-20", base="B0-设置", mutates=True)
def tbl20(d):
    table_settings(d, "仓颉")
    out = {}
    for title in ("显示编码提示", "常用的字词排前面"):
        r = d.row(title)
        w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial")] if r else []
        out[title] = w[0].checked if w else None
    check(out == {"显示编码提示": False, "常用的字词排前面": False}, f"{out}")
    return f"{out}"


@case("TBL-21", base="B0-自然码")
def tbl21(d):
    d.type("nihk")
    c = d.all_cands()
    check(has(c, "你好"), f"候选 {c[:6]}")
    d.clear()
    d.type("nihkwomf")
    c2 = d.cands()
    check(not has(c2, "你好我们"), f"长串出了整句 {c2[:3]}")
    return f"nihk {c[:3]}；长串 {c2[:3]}"


@case("TBL-22", base="B0-五笔拼音", mutates="learn")
def tbl22(d):
    d.type("wqiy")
    time.sleep(0.4)
    s = d.screen()
    c1, t1 = d.candidates(s), d.field_text(s)
    d.clear()
    d.type("hao")
    c2 = words(d.all_cands())
    check(has(c1, "你") or t1 == "你", f"wqiy：候选 {c1[:3]}，框里「{t1}」")
    check(("好" in c2 or "号" in c2) and "虚" in c2, f"hao 的候选 {c2[:10]}")
    return f"wqiy {c1[:2] or t1}；hao {c2[:6]}"


@case("TBL-23", base="B0-设置", mutates=True)
def tbl23(d):
    table_settings(d, "五笔")
    t = d.all_texts()
    d.scroll_top()
    sw = {}
    for title in ("只有一个候选时直接上屏", "显示编码提示", "常用的字词排前面"):
        r = d.row(title) or (d.scroll() and d.row(title))
        w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial")] if r else []
        sw[title] = w[0].checked if w else None
    lim = [x for x in t if "4" in x and "字" in x]
    when = [x for x in t if "3 次" in x]
    check(t and "通用输入设置" in t[:4], f"最上面 {t[:4]}")
    check(all(sw.values()), f"开关 {sw}")
    check("最长造多少字的词" in t and lim, f"最长造词 {lim}")
    check("造出的词何时记住" in t and when, f"何时记住 {when}")
    return f"{sw}；{lim[:1]}；{when[:1]}"


@case("TBL-24", base="B0-五笔", mutates="learn")
def tbl24(d):
    d.type("wvi")
    s = d.screen()
    p0, c0, t0 = d.preedit(s), d.candidates(s), d.field_text(s)
    spans, y = d.preedit_glyphs()
    if len(spans) != 3:
        raise Blocked(f"编码行字形 {spans}")
    d.tap(((spans[1][1] + spans[2][0]) // 2, y), 0.6)
    p1 = d.preedit()
    d.bksp()
    d.type("q")
    s = d.screen()
    p2, t2 = d.preedit(s), d.field_text(s)
    spans, y = d.preedit_glyphs()
    row = next(n for n in d.screen().ime() if n.cls == "HorizontalScrollView")
    d.tap((min(row.b[2] - 5, spans[-1][1] + 60), y), 0.6)
    d.type("y")
    s = d.screen()
    p3, c3 = d.preedit(s), d.candidates(s)
    d.space()
    t = d.field_text()
    check(p0 == "wvi" and c0 and not t0, f"wvi：`{p0}` {c0[:2]} 框里「{t0}」")
    check("|" in p1 and p1.index("|") == 2, f"点 v、i 之间后 `{p1}`")
    check(p2 == "wq|i" and not t2, f"补 q 后 `{p2}`，框里「{t2}」")
    check(p3 == "wqiy" and first(c3) == "你", f"补 y 后 `{p3}` {c3[:2]}")
    check(t == "你", f"空格后「{t}」")
    return f"`{p0}` → `{p1}` → `{p2}` → `{p3}` → 「{t}」"


@case("TBL-25", base="B0-设置", mutates=True, visual=True)
def tbl25(d):
    add_im(d, "五笔")
    home(d, "通用输入")
    d.switch("滑行输入", True)
    on_page(d, "五笔")
    k = d.keys(refresh=True)
    pts = [k[c] for c in "wqiy"]
    glide_shot(d, pts, "划 w-q-i-y，抬手前")
    s = d.screen()
    p, c, t = d.preedit(s), d.candidates(s), d.field_text(s)
    check((p == "wqiy" and first(c) == "你") or t == "你", f"`{p}` {c[:3]} 框里「{t}」")
    return f"`{p}` {c[:3]} 「{t}」"


@case("TBL-26", base="B0-五笔")
def tbl26(d):
    d.type("kwkw")
    time.sleep(0.4)
    c = d.candidates()
    emo = [x for x in c[2:] if re.match(r"^[\U0001F000-\U0001FFFF☀-➿]", x)]
    check(emo, f"候选 {c[:6]}")
    return f"候选 {c[:5]}"


@case("TBL-12a", base="B0-设置", mutates=True)
def tbl12a(d):
    script("13-words").import_dict_words(d)
    add_im(d, "五笔")
    on_page(d, "五笔")
    time.sleep(5.0)   # the imported words get their codes in the background
    own, imp = ("临阵磨枪", "电子计算机"), {"量子计算机": "liangzijisuanji", "量子文化村": "liangziwenhuacun"}

    def jbys():
        d.type("jbys")
        time.sleep(0.4)
        c = words(d.all_cands())
        d.clear()
        return c

    def order_ok(c):
        at = [c.index(w) if w in c else 99 for w in (*own, *imp)]
        return max(at) < 99 and max(at[:2]) < min(at[2:])

    c = jbys()
    check(order_ok(c), f"jbys 的候选 {c[:8]}（码表的词该在两个导入的词前面）")
    back = max(imp, key=c.index)   # typed in pinyin, the one behind moves ahead of the other
    d.switch_im("拼音")
    d.type(imp[back])
    d.cands(want=back)
    d.pick(back)
    d.clear()
    d.switch_im("五笔")
    c2 = jbys()
    other = next(w for w in imp if w != back)
    check(order_ok(c2) and c2.index(back) < c2.index(other),
          f"拼音里打过「{back}」后 jbys 的候选 {c2[:8]}（之前 {c[:6]}）")
    return f"jbys：{c[:4]}；拼音里打过「{back}」后 {c2[:4]}"


@case("TBL-16b", base="B0-五笔", mutates="learn", clean=True)
def tbl16b(d):
    d.type("aaaa")
    time.sleep(0.4)
    c = words(d.candidates())
    if len(c) < 2:
        raise Blocked(f"aaaa 的候选 {c}")
    w = c[1]
    m1 = d.long_pick(w)
    d.back()   # closes the menu, the code and the keyboard with it
    on_page(d, "五笔")
    d.type("aaaa")
    time.sleep(0.4)
    d.pick(w)
    d.clear()
    d.type("aaaa")
    time.sleep(0.4)
    m2 = d.long_pick(w)
    d.back()
    check(not any("忘记词汇" in x for x in m1), f"没选过的「{w}」长按菜单 {m1[:6]}")
    check(any("忘记词汇" in x for x in m2), f"选过一次后「{w}」长按菜单 {m2[:6]}")
    return f"「{w}」：没选过时 {m1[:4]}；选过后 {m2[:4]}"


@case("TBL-27", base="B0-五笔", mutates="learn")
def tbl27(d):
    out = []
    for code, w, e in (("kwkw", "哈哈", "😃"), ("hktf", "点赞", "👍")):
        d.type(code)
        c = d.cands(want=e)
        k = next((i for i, x in enumerate(c) if word(x) == e), None)
        check(c and word(c[0]) == w, f"{code}：第一个 {c[:3]}")
        check(k is not None and k >= 2, f"{code}：候选 {c[:6]}")
        out.append(f"{code} {words(c[:k + 1])}")
        if e == "👍":
            d.pick(c[k])
            t = d.field_text()
            check(t == "👍", f"框里「{t}」")
        else:
            d.clear()
    return "；".join(out)


# each table's code of 哈哈, 点赞 or 晚安 with 重码 (仓颉 has no words)
TABLE_EMOJI = (("五笔", "kwkw", "哈哈", "😃"), ("五笔拼音", "jqpv", "晚安", "😴"))
# a word alone on its full code commits itself: its emoji are offered first among what may follow
TABLE_LONE = (("二笔", "dazq", "点赞", "👍"), ("自然码", "haha", "哈哈", "😃"))
EMO = re.compile(r"^[\U0001F000-\U0001FFFF\u2600-\u27BF]")


def table_emoji(d, label, code, w, e):
    d.type(code)
    c = d.cands(want=e)
    k = next((i for i, x in enumerate(c) if word(x) == e), None)
    check(c and word(c[0]) == w, f"{label} {code}：第一个 {c[:3]}")
    check(k == 2, f"{label} {code}：候选 {c[:6]}")
    d.clear()
    return f"{label} {code} {words(c[:k + 1])}"


def table_lone(d, label, code, w, e):
    d.type(code)
    c = d.cands(want=e)
    t = d.field_text()
    check(t == w, f"{label} {code}：框里「{t}」")
    check(c and c[0] == e, f"{label} {code}：联想 {c[:5]}")
    d.pick(e)
    t2 = d.field_text()
    check(t2 == e, f"{label} 点 {e} 后框里「{t2}」")
    d.clear()
    return f"{label} {code} 上屏「{t}」联想 {c[:3]} → 「{t2}」"


@case("TBL-28", base="B0", mutates=True)
def tbl28(d):
    # 全拼 first: the emoji read for it, then each table switched to in the same session
    d.type("haha")
    d.cands(want="😃")
    d.clear()
    for label, *_ in TABLE_EMOJI + TABLE_LONE:
        add_im(d, label)
    d.open_page("text")
    out = []
    for label, code, w, e in TABLE_EMOJI:
        d.switch_im(label)
        out.append(table_emoji(d, label, code, w, e))
    for label, code, w, e in TABLE_LONE:
        d.switch_im(label)
        out.append(table_lone(d, label, code, w, e))
    # a prediction picked past the emoji ends their offer: no 👍 left to replace the wrong text
    d.switch_im("二笔")
    d.type("dazq")
    c = d.cands(want="👍")
    d.pick(c[1])
    t = d.field_text()
    c2 = d.cands()
    check(t == "点赞" + c[1] and "👍" not in c2, f"点联想「{c[1]}」后框里「{t}」，联想 {c2[:4]}")
    d.clear()
    out.append(f"联想点「{c[1]}」后 {c2[:3]}")
    # the app started afresh (fcitx may come back in 拼音): the table's emoji at once
    d.hide_keyboard()
    d.sh(f"am force-stop {PKG}")
    d.open_page("text")
    d.switch_im(TABLE_LONE[0][0])
    out.append("重启后 " + table_lone(d, *TABLE_LONE[0]))
    return "；".join(out)




def table_word_phrase(d, label, code, w, ok):
    """the word typed by its code: its 日期/时间 texts among its candidates, or (alone on its code,
    committed by itself) first in what may follow, one picked replacing it"""
    d.type(code)
    d.cands()
    t = d.field_text()
    c = words(d.all_cands())
    k = next((i for i, x in enumerate(c) if ok(x)), None)
    if t == w:
        check(k == 0, f"{label} {code} 上屏「{t}」，联想 {c[:5]}")
        d.pick(c[0])
        t2 = d.field_text()
        check(t2 == c[0], f"{label} 点「{c[0]}」后框里「{t2}」")
        d.clear()
        return f"{label} {code} 上屏「{t}」联想 {c[:3]} → 「{t2}」"
    check(c and c[0] == w, f"{label} {code}：第一个 {c[:3]}，框里「{t}」")
    check(k is not None and k >= 1, f"{label} {code}：候选 {c[:7]}")
    d.clear()
    return f"{label} {code} {c[:k + 1]}"


@case("TBL-29a", base="B0-设置", mutates=True)
def tbl29a(d):
    iso = d.sh("date +%Y-%m-%d").strip()
    is_date = lambda x: re.match(r"^\d{4}年\d+月\d+日$", x) or x == iso
    add_im(d, "五笔")

    def follow(on):
        d.open_settings()
        d.goto("通用输入")
        d.switch("自带的日期时间", on)
        d.open_page("text")
        d.switch_im("五笔")
        d.type("jjad")
        d.cands()
        t = d.field_text()
        c = words(d.all_cands())
        d.clear()
        d.hide_keyboard()
        if t != "日期":
            raise Blocked(f"五笔 jjad 没自己上屏「日期」：框里「{t}」")
        return c

    off, on = follow(False), follow(True)
    check(not any(is_date(x) for x in off), f"关掉后 jjad 的联想里有日期：{off[:5]}")
    check(on and is_date(on[0]), f"打开后 jjad 的联想 {on[:3]}，第一个不是今天的日期")
    return f"关：{off[:3]}；开：{on[:2]}"


@case("TBL-29", base="B0", mutates=True)
def tbl29(d):
    iso = d.sh("date +%Y-%m-%d").strip()
    is_date = lambda x: re.match(r"^\d{4}年\d+月\d+日$", x) or x == iso
    is_time = lambda x: re.match(r"^\d\d:\d\d$", x)
    add_im(d, "五笔")
    add_im(d, "二笔")
    d.open_page("text")
    out = []
    d.switch_im("五笔")
    out.append(table_word_phrase(d, "五笔", "jjad", "日期", is_date))
    out.append(table_word_phrase(d, "五笔", "jfuj", "时间", is_time))
    d.switch_im("二笔")
    out.append(table_word_phrase(d, "二笔", "rsqj", "日期", is_date))
    out.append(table_word_phrase(d, "二笔", "ssju", "时间", is_time))
    return "；".join(out)
