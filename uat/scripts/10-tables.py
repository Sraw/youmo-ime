"""uat/10-tables.md (B0-五笔 and the other tables). The .md is the source of truth; each function
follows its case's 前提/操作/预期."""
import re
import statistics
import time

from uat import PKG, Blocked, add_im, case, script, check, first, gear, glide_shot, has, home, menu_tap, popup, popup_slide


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
    # typed a char at a time, no word made (the old 自动造词 is gone): qglf's four codes commit 昏天黑地
    check(t == "昏天黑地" and not has(c, "鲸园"), f"候选 {c[:4]}，框里「{t}」")
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
    # D8: qglf is 昏天黑地's alone, a word from outside the table does not come in to stop it
    d.type("qglf")
    time.sleep(0.4)
    c, t = d.candidates(), d.field_text()
    check(t == "昏天黑地" and not has(c, "鲸园"), f"候选 {c[:4]}，框里「{t}」")
    d.clear()
    d.switch_im("拼音")
    d.type("jingyuan")
    p = d.cands()
    check(has(p, "鲸园"), f"拼音 jingyuan 的候选 {p[:4]}")
    d.clear()
    w = script("13-words")
    w.user_lexicon(d, "手动添加的词")
    row = next((r for r in w.list_texts(d) if "五笔" in r and "同码" in r), None)
    check(row and "昏天黑地" in row, f"鲸园那一行没写「五笔：与 昏天黑地 同码」：{w.list_texts(d)[:10]}")
    return f"qglf →「{t}」；jingyuan {p[:3]}；「{row}」"


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
    # twice, as the .md: once never puts 寸 first even with 调频 on (TBL-15), so it could not tell
    for _ in range(2):
        d.pick("寸")
        d.clear()
        d.type("fghy")
        c = words(d.cands())
    # tuning is off on a new install: the table's order stays
    check(c0[:3] == ["雨", "寸", "圷"] and c[:3] == c0[:3], f"开始 {c0[:3]}，之后 {c[:3]}")
    return f"{c0[:3]} → {c[:3]}"


@case("TBL-15", base="B0-设置", mutates=True)
def tbl15(d):
    table_settings(d, "五笔")
    d.switch("调频", True)
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
    # 不再联想 straight away, as the .md (and HW-10a): a forget first could take B out by itself
    menu = d.long_pick(two)
    has_block = any(x == "屏蔽" for x in menu)
    n = next((n for n in d.screen().nodes if n.text == "不再联想"), None)
    check(n, f"长按菜单里没有不再联想：{menu[:8]}")
    d.tap(n.c, 0.8)
    d.clear()
    zhongguo()
    c3 = words(d.all_cands())
    check(has_block, f"长按菜单 {menu[:8]}")
    check(two not in c3, f"不再联想后联想里还有「{two}」：{c3[:6]}")
    return f"「{two}」：菜单有不再联想、屏蔽；之后 {c3[:4]}"


@case("TBL-17", base="B0-设置", mutates=True)
def tbl17(d):
    # TBL-15's tuning on, so 寸 learns first under fghy in 五笔
    table_settings(d, "五笔")
    d.switch("调频", True)
    # cun's order before the table learns: what it must still be after
    on_page(d, "拼音")
    d.type("cun")
    c0 = words(d.cands())
    d.clear()
    d.switch_im("五笔")
    for _ in range(2):
        d.type("fghy")
        d.cands()
        d.pick("寸")
        d.clear()
    d.type("fghy")
    f = words(d.cands())
    d.clear()
    check(first(f) == "寸", f"前提没到：五笔 fghy {f[:3]}")
    d.switch_im("拼音")
    d.type("cun")
    c = words(d.cands())
    check(first(c) == "村", f"拼音 cun {c[:5]}")
    check("寸" not in c0 or ("寸" in c and c.index("寸") == c0.index("寸")), f"拼音 cun 学前 {c0[:5]}，学后 {c[:5]}")
    return f"学前 {c0[:4]}，学后 {c[:4]}"


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
    for title in ("显示编码提示", "调频"):
        r = d.row(title)
        w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial")] if r else []
        out[title] = w[0].checked if w else None
    check(out == {"显示编码提示": False, "调频": False}, f"{out}")
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
    for title in ("只有一个候选时直接上屏", "显示编码提示", "调频"):
        r = d.row(title) or (d.scroll() and d.row(title))
        w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial")] if r else []
        sw[title] = w[0].checked if w else None
    check(t and "通用输入设置" in t[:4], f"最上面 {t[:4]}")
    check(sw == {"只有一个候选时直接上屏": True, "显示编码提示": True, "调频": False}, f"开关 {sw}")
    # 自动造词's two are gone (U-28 C46)
    gone = [x for x in ("最长造多少字的词", "造出的词何时记住") if x in t]
    check(not gone, f"还有 {gone}")
    check("恢复码表顺序" in t, f"没有恢复码表顺序：{t[:12]}")
    return f"{sw}"


@case("TBL-30", base="B0-设置", mutates=True, visual=True)
def tbl30(d):
    table_settings(d, "五笔")
    d.switch("调频", True)
    on_page(d, "五笔")
    for _ in range(2):
        d.type("fghy")
        d.cands()
        d.pick("寸")
        d.clear()
    d.type("fghy")
    c0 = words(d.cands())
    if c0[:1] != ["寸"]:
        raise Blocked(f"调频后「寸」没排第一：{c0[:3]}")
    d.clear()
    gear(d, "五笔")
    d.tap_text("恢复码表顺序")
    ok = next((n for n in d.screen().nodes if n.rid == "button1"), None)
    if not ok:
        raise Blocked("恢复码表顺序没有确认框")
    d.tap(ok.c, 0.6)
    # the toast is in no UI dump (03-t9's note): 已恢复码表顺序 is read off the picture
    d.shot("full", "确认后的提示（应为\"已恢复码表顺序\"）")
    on_page(d, "五笔")
    d.type("fghy")
    c = words(d.cands())
    check(c[:3] == ["雨", "寸", "圷"], f"恢复前 {c0[:3]}，之后 {c[:3]}")
    return f"{c0[:3]} → {c[:3]}"


@case("TBL-34", base="B0-设置", mutates=True)
def tbl34(d):
    add_im(d, "五笔")
    # each start after the first table use: none may take this install for an older build's (review C M1)
    for _ in range(3):
        on_page(d, "五笔")
        d.type("fghy")
        d.cands()
        d.pick("寸")
        d.clear()
        d.hide_keyboard()
        d.sh(f"am force-stop {PKG}")
        time.sleep(1.0)
    gear(d, "五笔")
    r = d.row("调频") or (d.scroll() and d.row("调频"))
    w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial")] if r else []
    if not w:
        raise Blocked("五笔设置页没有调频开关")
    check(not w[0].checked, "重启几次后调频开了")
    return "调频仍关"


@case("TBL-37", base="B0-设置", mutates=True)
def tbl37(d):
    table_settings(d, "五笔")
    d.switch("调频", True)
    on_page(d, "五笔")
    d.type("fghy")
    d.cands()
    d.pick("寸")
    d.type(".")
    d.type("fghy")
    slip = words(d.cands())[0]
    d.space()
    d.bksp()
    d.type("fghy")
    d.cands()
    d.pick("寸")
    d.type(".")
    d.type("fghy")
    c = words(d.cands())
    d.clear()
    check(c[:1] == ["寸"], f"「{slip}」上屏又删掉以后，两次选「寸」：候选 {c[:3]}")
    return f"fghy {c[:3]}"


@case("TBL-38", base="B0-五笔", mutates="learn")
def tbl38(d):
    d.type("fghy")
    c0 = words(d.cands())
    d.pick("圷")
    d.bksp()
    d.type("fghy")
    c1 = words(d.cands())
    texts = d.long_pick("圷")
    if "为什么排在这" not in texts:
        raise Blocked(f"长按「圷」，菜单 {texts}")
    menu_tap(d, "为什么排在这")
    why = d.all_texts()
    d.back()
    d.clear()
    check(not any("用过" in t for t in why), f"为什么排在这：{why[:6]}")
    check(c1[:3] == c0[:3], f"顺序：之前 {c0[:3]}，之后 {c1[:3]}")
    return f"{c1[:3]}；{[t for t in why if '顺序' in t or '用过' in t][:2]}"


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
    # the pinyin pick does not change the table's order (TBL-12a)
    check(order_ok(c2) and c2.index(back) > c2.index(other), f"拼音里打过「{back}」后 jbys 的候选 {c2[:8]}（之前 {c[:8]}）")
    return f"jbys：{c[:4]}；拼音里打过「{back}」后 {c2[:4]}"


@case("TBL-12b", base="B0-设置", mutates=True)
def tbl12b(d):
    words13 = script("13-words")
    words13.push(d, "dict-words.txt")
    _, msgs = words13.import_file(d, "dict-words.txt", "用户词库")
    if not words13.imported_count(msgs):
        raise Blocked(f"前提没到：{msgs[:3]}")
    d.back()
    add_im(d, "五笔")
    on_page(d, "五笔")
    time.sleep(5.0)   # the imported words get their codes in the background
    d.type("jbys")
    time.sleep(0.4)
    c = words(d.all_cands())
    d.clear()
    at = [c.index(w) if w in c else 99 for w in ("临阵磨枪", "电子计算机", "量子计算机", "量子文化村")]
    check(max(at) < 99 and max(at[:2]) < min(at[2:]), f"jbys 的候选 {c[:8]}（码表的词该在两个导入的词前面）")
    # D8: rkoq is 打嘴炮's alone, the imported 氪喵糖铺 of the same code does not come in to stop it
    d.type("rkoq")
    time.sleep(0.4)
    c2, t = d.candidates(), d.field_text()
    check(t == "打嘴炮" and not has(c2, "氪喵糖铺"), f"rkoq 的候选 {c2[:4]}，框里「{t}」")
    return f"jbys：{c[:5]}；rkoq →「{t}」"


@case("TBL-12c", base="B0-设置", mutates=True)
def tbl12c(d):
    """frost in the base dictionary, coded for 五笔 through the merged file: typing stays, the heap too"""
    words13 = script("13-words")
    src = words13.frost(d)
    d.open_page("text")
    d.switch_im("拼音")
    base = words13.heap_base(d)
    d.adb("push", src, "/sdcard/Download/frost_dict_for_fcitx5.txt", timeout=300)
    words13.import_file(d, "frost_dict_for_fcitx5.txt", "基础词库")
    if not any("frost_dict_for_fcitx5" in r for r in words13.dictionaries(d)):
        raise Blocked("前提没到：frost 没并入基础词库")
    add_im(d, "五笔")
    on_page(d, "五笔")
    time.sleep(30.0)   # its words get their codes in the background
    d.type("jbys")
    time.sleep(0.4)
    c = words(d.all_cands())
    d.clear()
    more = []
    for code in ("gg", "wq"):
        d.type(code)
        time.sleep(0.4)
        more.append(words(d.candidates())[:3])
        d.clear()
    # coding frost for 五笔 runs on past the 30 s above and holds ~100 MB live while it does (read
    # after a GC); the case is about what stays, so wait for it to finish before the readings
    # two low readings in a row: one alone can fall between two batches of the coding
    peak, low = 0, 0
    for _ in range(30):
        h = words13.java_heap(d)
        peak = max(peak, h or 0)
        # no reading is not a low one
        low = low + 1 if h is not None and h - statistics.median(base[1:]) < words13.HEAP_RISE_KB // 2 else 0
        if low == 2:
            break
        time.sleep(3.0)
    heap = words13.heaps(d)
    rise = words13.heap_rise(base, heap)
    # the table's own three first, in whatever order; then frost's
    own = [c.index(w) if w in c else 99 for w in ("临阵磨枪", "电子文档", "电子计算机")]
    check(sorted(own) == [0, 1, 2], f"jbys 的候选 {c[:8]}（码表自己的词该在最前）")
    check(all(more), f"gg、wq 的候选 {more}")
    check(rise is not None and rise < words13.HEAP_RISE_KB, f"Java 堆 {base} → {heap} KB")
    return f"jbys：{c[:5]}；gg、wq {more}；Java 堆 {base} → {heap} KB（编码时最高 {peak} KB）"


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
    check(not any("忘掉学到的" in x for x in m1), f"没选过的「{w}」长按菜单 {m1[:6]}")
    # tuning off: a table's own word learned nothing (TBL-16b), still no 忘掉学到的 nor 删除
    check(not any(x in ("忘掉学到的", "删除") for x in m1 + m2), f"选过一次后「{w}」长按菜单 {m2[:6]}")
    check("固定首位" in m2 and "屏蔽" in m2, f"选过一次后「{w}」长按菜单 {m2[:6]}")
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


@case("TBL-35", base="B0-五笔", mutates=True)
def tbl35(d):
    d.type("fghy")
    f0 = words(d.cands())
    d.clear()
    for _ in range(2):
        d.type("zjingyuan")
        d.cands(want="鲸")
        d.pick("鲸")
        d.cands(want="园")
        d.pick("园")
        d.press(".")
    t = d.field_text()
    if t != "鲸园。鲸园。":
        raise Blocked(f"框里「{t}」")
    d.clear()
    d.type("fghy")
    f1 = words(d.cands())
    d.clear()
    d.switch_im("拼音")
    d.type("jingyuan")
    c = words(d.cands())
    d.clear()
    check(c[:1] == ["鲸园"], f"拼音 jingyuan {c[:4]}")
    check(f1[:3] == f0[:3], f"五笔 fghy {f0[:3]} → {f1[:3]}")
    return f"jingyuan {c[:3]}；fghy {f1[:3]}"


@case("TBL-39", base="B0-五笔", mutates=True)
def tbl39(d):
    gear(d, "五笔")
    d.switch("调频", True)
    on_page(d, "五笔")

    def fghy():
        d.type("fghy")
        return words(d.cands())

    def pick(w):
        d.pick(w)
        d.press(".")
    fghy()
    pick("寸")
    fghy()
    menu = d.long_pick("寸")
    if "固定首位" not in menu:
        raise Blocked(f"长按「寸」的菜单 {menu[:8]}")
    menu_tap(d, "固定首位")
    # read, not typed again: f over the pinned fghy would commit 寸, a second pick in a row, its first
    c0 = words(d.cands())
    if c0[:2] != ["寸", "雨"]:
        raise Blocked(f"固定后 {c0[:3]}")
    pick("雨")
    fghy()
    menu = d.long_pick("寸")
    if "取消固定" not in menu:
        raise Blocked(f"长按「寸」的菜单 {menu[:8]}")
    menu_tap(d, "取消固定")
    pick("寸")
    c1 = fghy()
    pick("寸")
    c2 = fghy()
    d.clear()
    check(c1[:1] == ["雨"], f"取消固定后选过一次「寸」：{c1[:3]}")
    check(c2[:1] == ["寸"], f"再选一次后 {c2[:3]}")
    return f"{c1[:3]} → {c2[:3]}"


def block_fghy_cun(d):
    """TBL-36's block: 寸 under fghy only"""
    d.type("fghy")
    d.cands()
    d.long_pick("寸")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()


@case("TBL-40", base="B0-设置", mutates=True)
def tbl40(d):
    add_im(d, "五笔")
    home(d, "通用输入")
    d.switch("滑行输入", True)
    on_page(d, "五笔")
    k = d.keys(refresh=True)
    # glided once before the block: a glide result cached across it would still offer 寸
    glide_shot(d, [k[c] for c in "fghy"], "划 f-g-h-y，屏蔽前")
    s = d.screen()
    p0, c0 = d.preedit(s), words(d.all_cands())
    d.clear()
    if "寸" not in c0:
        raise Blocked(f"屏蔽前划 fghy：`{p0}` {c0[:6]}")
    block_fghy_cun(d)
    glide_shot(d, [k[c] for c in "fghy"], "划 f-g-h-y，屏蔽后")
    s = d.screen()
    p, c = d.preedit(s), words(d.all_cands())
    d.clear()
    check(p == "fghy", f"划完编码 `{p}`")
    check("寸" not in c and "雨" in c, f"屏蔽「寸」后划 fghy：{c[:6]}")
    return f"屏蔽前 {c0[:4]}；屏蔽后 `{p}` {c[:4]}"


case("TBL-43", needs="skip:要 v0.3.0 装好学过词后导出的 .zip，再重置装这一版恢复；发版时手动跑")(lambda d: None)


@case("TBL-44", base="B0-五笔", mutates=True)
def tbl44(d):
    gear(d, "五笔")
    d.switch("调频", True)
    on_page(d, "五笔")
    d.type("fghy")
    d.cands(want="寸")
    d.pick("寸")
    d.press(",")
    # only the comma handed out goes: 寸's pick before it still counts
    d.bksp(1)
    d.press(".")
    d.type("fghy")
    d.cands(want="寸")
    d.pick("寸")
    d.press(".")
    d.type("fghy")
    c = words(d.cands())
    t = d.field_text() or ""
    d.clear()
    check(c[:1] == ["寸"], f"选两次「寸」（第一次后的逗号删掉）后 fghy {c[:3]}；框里「{t}」")
    return f"框里「{t}」；fghy {c[:3]}"


def zhongguo(d):
    d.type("khlg")
    time.sleep(0.5)
    if not (d.field_text() or "").endswith("中国"):
        d.space()
    return words(d.candidates())


@case("TBL-41", base="B0-五笔", mutates="learn")
def tbl41(d):
    c0 = zhongguo(d)
    if len(c0) < 2:
        raise Blocked(f"联想 {c0[:4]}")
    d.pick(c0[1])
    d.bksp(len(c0[1]))
    d.clear()
    c1 = zhongguo(d)
    d.clear()
    check(c1[:4] == c0[:4], f"点「{c0[1]}」又删掉：之前 {c0[:4]}，之后 {c1[:4]}")
    return f"{c0[:4]} → {c1[:4]}"


@case("TBL-42", base="B0-五笔", mutates="learn")
def tbl42(d):
    def lookup():
        d.type("zjingyuan")
        d.cands(want="鲸")
        d.pick("鲸")
        d.cands(want="园")
        d.pick("园")
    lookup()
    d.bksp(2)
    t = d.field_text() or ""
    if t:
        raise Blocked(f"删掉后框里「{t}」")
    lookup()
    d.press(".")
    d.clear()
    d.switch_im("拼音")
    d.type("jingyuan")
    c = words(d.cands())
    d.clear()
    check(c[:1] != ["鲸园"], f"反查上屏一次删掉、再上屏一次后，拼音 jingyuan {c[:4]}")
    return f"jingyuan {c[:3]}"


@case("TBL-36", base="B0-五笔", mutates=True)
def tbl36(d):
    d.type("fghy")
    d.cands()
    d.long_pick("寸")
    menu_tap(d, "屏蔽")
    scope = next((t for t in d.screen().texts(where="all") if "这串码" in t), "")
    menu_tap(d, "确定屏蔽")
    d.clear()
    d.type("fghy")
    c = words(d.all_cands())
    d.clear()
    d.switch_im("拼音")
    d.type("cun")
    p = words(d.all_cands())
    d.clear()
    w = script("13-words")
    w.user_lexicon(d, "屏蔽的词")
    top = d.all_texts()
    check(scope == "打这串码不再出现「寸」；别的编码、别的输入法照旧。", f"范围「{scope}」")
    check("寸" not in c, f"fghy {c[:5]}")
    check("寸" in p, f"cun {p[:6]}")
    check(any("码表里按码屏蔽的" in t and "1 个" in t for t in top), f"屏蔽的词页 {top[:5]}")
    return f"「{scope}」；fghy {c[:3]}；cun {p[:3]}"
