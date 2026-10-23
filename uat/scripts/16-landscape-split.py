"""uat/16-landscape-split.md (B0-横屏). The .md is the source of truth; each function follows its
case's 前提/操作/预期. redroid's display does not turn: landscape is the display resized to
2340x1080 (851 dp wide, Device.rotate), which the app sees as a landscape screen."""
import time

from uat import Blocked, Fail, add_im, case, check, choose, flick, home, popup, popup_pick, popup_slide, script

DPI = 440 / 160


def appearance(d, tab="大小与样式"):
    home(d, "外观")
    d.tap_text(tab, scroll=False, after=1.0)


def kb_keys(d, s=None):
    """the keyboard's keys: (label, bounds) in reading order"""
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    if not kb:
        raise Blocked("没有键盘")
    out = []
    for n in kb.walk():
        if n.clickable:
            out.append((n.desc or ([m.text for m in n.walk() if m.text] or [""])[0], n.b))
    return out


def rows(keys):
    by = {}
    for lab, b in keys:
        by.setdefault(b[1], []).append((lab, b))
    return [sorted(v, key=lambda k: k[1][0]) for _, v in sorted(by.items())]


def gap(row):
    """the widest empty stretch between neighbouring keys of a row: (start, end)"""
    best = (0, 0)
    for (_, a), (_, b) in zip(row, row[1:]):
        if b[0] - a[2] > best[1] - best[0]:
            best = (a[2], b[0])
    return best


def tap_side(d, label, side):
    ks = [b for lab, b in kb_keys(d) if lab == label]
    mid = screen_w(d) / 2
    ks = [b for b in ks if (b[0] < mid) == (side == "L")]
    if not ks:
        raise Blocked(f"{'左' if side == 'L' else '右'}半边没有 {label}")
    b = ks[0]
    d.tap(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2), 0.25)


def split_ok(d):
    r = rows(kb_keys(d))[:3]
    g = [gap(x) for x in r]
    return all(x[1] - x[0] > 200 for x in g), r, g


def panel_box(d, s=None):
    """x range of the IME's controls below the bar (the panel), and of the bar's own buttons"""
    s = s or d.screen()
    kb_top = min((n.b[1] for n in s.ime() if n.desc == "隐藏键盘"), default=0)
    bar = [n for n in s.ime() if (n.desc or n.text) and n.b[3] <= kb_top + 115 and n.b[1] >= kb_top - 5]
    body = [n for n in s.ime() if (n.desc or n.text) and n.b[1] > kb_top + 100 and not n.desc.startswith("移到")]
    bx = (min(n.b[0] for n in body), max(n.b[2] for n in body)) if body else None
    tx = (min(n.b[0] for n in bar), max(n.b[2] for n in bar)) if bar else None
    return bx, tx


def abc(d):
    n = d.screen().find(text="ABC", where="ime")
    if not n:
        raise Blocked("没有 ABC 键")
    d.tap(n.c, 1.0)
    d._keys = None


def arrow(d, s=None):
    s = s or d.screen()
    return next((n for n in s.ime() if n.desc.startswith("移到")), None)


def screen_w(d):
    return int(d.sh("wm size").strip().splitlines()[-1].split()[-1].split("x")[0])


def on_right(d, box, w=None):
    w = w or screen_w(d)
    return box and box[0] > w * 0.35 and box[1] > w * 0.9 and (box[1] - box[0]) < w * 0.5


def on_left(d, box, w=None):
    w = w or screen_w(d)
    return box and box[0] < w * 0.1 and box[1] < w * 0.65 and (box[1] - box[0]) < w * 0.5


def full(d, box, w=None):
    w = w or screen_w(d)
    return box and box[0] < w * 0.1 and box[1] > w * 0.9


# ---------------------------------------------------------------- the split letters

@case("LS-01", base="B0-横屏", visual=True)
def ls01(d):
    ok, r, g = split_ok(d)
    d.shot("kb", "横屏键盘")
    labels = [[k[0] for k in row] for row in r]
    want = [list("qwertyuiop"), list("asdfgghjkl"), ["Shift", "z", "x", "c", "v", "v", "b", "n", "m", "退格"]]
    widths = {k[1][2] - k[1][0] for row in r for k in row}
    space = next((b for lab, b in kb_keys(d) if lab.startswith("空格")), None)
    mid = screen_w(d) / 2
    check(ok, f"中间空白 {g}")
    check(labels == want, f"三行 {labels}")
    check(max(widths) - min(widths) <= 2, f"字母键宽 {sorted(widths)}")
    check(r[0][0][1][0] <= 2 and r[0][-1][1][2] >= screen_w(d) - 2, f"两半没从屏幕边缘排起 {r[0][0][1]} {r[0][-1][1]}")
    check(space and space[0] < mid < space[2], f"空格 {space}")
    return f"每半 5 列，键宽 {min(widths)} px，中间 {g[0]}，空格 {space}"


@case("LS-03", base="B0-横屏", mutates="learn")
def ls03(d):
    for k, side in (("w", "L"), ("o", "R"), ("m", "R"), ("e", "L"), ("n", "R")):
        tap_side(d, k, side)
    c = d.cands()
    d.space()
    t = d.field_text()
    check(t == "我们" or t == (c[0].split(" ")[0] if c else None), f"框里「{t}」（候选 {c[:2]}）")
    return f"「{t}」"


@case("LS-04", base="B0-横屏")
def ls04(d):
    d.switch_im("英文")
    for k, side in (("g", "L"), ("g", "R"), ("v", "L"), ("v", "R")):
        tap_side(d, k, side)
    time.sleep(0.4)
    s = d.screen()
    c, p = d.candidates(s), d.preedit(s)
    check("ggvv" in [x.split(" ")[0] for x in c[:2]] or p == "ggvv", f"候选 {c[:3]}，拼写 `{p}`")
    return f"候选 {c[:3]}"


@case("LS-05", base="B0-横屏", mutates="learn")
def ls05(d):
    flick(d, "o", dy=150)   # the keys are 116 px tall here; 110-130 px down sometimes gives nothing
    popup_pick(d, "q", "Q")
    t = d.field_text()
    check(t == "9Q", f"框里「{t}」")
    return f"「{t}」"


@case("LS-09", base="B0-横屏", mutates=True)
def ls09(d):
    for _ in range(4):
        if split_ok(d)[0]:
            break
        time.sleep(1.0)
    else:
        raise Blocked("开始就不是分体")
    d.expand_toolbar()
    d.tap(d.screen().find(desc="状态区域", where="ime").c, 0.8)   # the … button
    d.tap_text("主题", where="ime", scroll=False, after=2.0)
    d.tap_text("大小与样式", scroll=False, after=1.0)
    choose(d, "分体键盘", "关")
    d.open_page("text")
    ok, r, g = split_ok(d)
    check(not ok, f"还是分体：空白 {g}")
    return "不再分体"


@case("LS-10", base="B0-设置", mutates=True, visual=True)
def ls10(d):
    d.rotate(1)
    d.open_settings()
    home(d, "外观")
    time.sleep(1.0)
    d.shot("full", "外观页的预览（横屏）")
    d.tap_text("大小与样式", scroll=False, after=1.0)
    d.shot("full", "大小与样式的预览（横屏）")
    choose(d, "分体键盘", "关")
    if not d.screen().find(text="分体键盘"):
        d.tap_text("大小与样式", scroll=False, after=1.0)
    d.shot("full", "分体键盘改成关以后的预览")
    return "预览分体、和测试页一样、改关后不分体：看图"


@case("LS-13", base="B0-横屏", page="url")
def ls13(d):
    ks = kb_keys(d)
    bottom = rows(ks)[-1]
    labs = [k[0] for k in bottom]
    space = next((b for lab, b in bottom if lab.startswith("空格")), None)
    mid = screen_w(d) / 2
    com = next((b for lab, b in bottom if lab == ".com"), None)
    check("/" in labs and "." in labs and com, f"底排 {labs}")
    check(space and space[0] < mid < space[2], f"空格 {space}")
    d.tap(((com[0] + com[2]) // 2, (com[1] + com[3]) // 2), 0.6)
    t = d.field_text()
    check(t == ".com", f"框里「{t}」")
    return f"底排 {labs}；「{t}」"


# ---------------------------------------------------------------- the other panels

@case("LS-14", base="B0-横屏", visual=True)
def ls14(d):
    d.press("123", after=1.0)
    s = d.screen()
    bx, tx = panel_box(d, s)
    a = arrow(d, s)
    d.shot("full", "横屏数字键盘")
    w = screen_w(d)
    check(on_right(d, bx, w), f"数字键盘 {bx}")
    check(tx and abs(tx[0] - bx[0]) < 60 and tx[1] >= w - 2, f"工具栏 {tx}，键盘 {bx}")
    check(a and a.desc == "移到左边" and a.b[2] < bx[0], f"箭头 {a and (a.desc, a.b)}")
    return f"数字键盘 {bx}（{(bx[1] - bx[0]) / DPI:.0f} dp）；工具栏 {tx}；箭头 {a.desc}"


@case("LS-15", base="B0-横屏", mutates=True)
def ls15(d):
    d.step("点箭头")
    d.press("123", after=1.0)
    a = arrow(d)
    if not a:
        raise Blocked("没有箭头")
    d.tap(a.c, 1.0)
    s = d.screen()
    bx, tx = panel_box(d, s)
    a2 = arrow(d, s)
    check(on_left(d, bx) and tx and tx[0] <= 2, f"数字键盘 {bx}，工具栏 {tx}")
    check(a2 and a2.desc == "移到右边" and a2.b[0] > bx[1], f"箭头 {a2 and (a2.desc, a2.b)}")
    d.step("回字母、转屏后再点 123")
    abc(d)
    d.rotate(0)
    d.rotate(1)
    d.open_page("text")
    d.press("123", after=1.0)
    bx2, _ = panel_box(d)
    check(on_left(d, bx2), f"转屏后数字键盘 {bx2}")
    d.step("设置里的分体时其他面板")
    d.hide_keyboard()
    d.rotate(0)   # landscape's preview leaves the row under the navigation bar
    d.open_settings()
    appearance(d)
    # the row shows no summary: its choice is read from the dialog
    d.tap_text("分体时其他面板", after=1.0)
    s = d.screen()
    v = next((n.text for n in s.nodes if n.cls == "CheckedTextView" and n.checked), None)
    d.back()
    check(v and v.startswith("靠左"), f"分体时其他面板「{v}」")
    return f"移到左边 {bx}；转屏后仍在左 {bx2}；设置「{v}」"


@case("LS-16", base="B0-横屏")
def ls16(d):
    d.press("123", after=1.0)
    abc(d)
    s = d.screen()
    ok = split_ok(d)[0]
    bx, tx = panel_box(d, s)
    check(ok, "回来不是分体")
    check(full(d, tx), f"工具栏 {tx}")
    check(not arrow(d, s), "还有箭头")
    return f"分体，工具栏 {tx}"


@case("LS-17", base="B0-横屏", visual=True)
def ls17(d):
    d.press("符", after=1.0)
    s = d.screen()
    bx, tx = panel_box(d, s)
    d.shot("full", "横屏符号面板")
    cats = [n for n in s.ime() if n.text in ("常用", "中文", "英文", "数学")]
    right = max((c.b[2] for c in cats), default=0)
    sym = next((n for n in s.ime() if len(n.text) == 1 and not n.text.isalnum() and n.c[0] > right + 10
                and n.b[1] > (bx and 484 or 0)), None)
    check(on_right(d, bx), f"符号面板 {bx}")
    check(cats and sym, f"分类 {[c.text for c in cats]}，符号 {sym and sym.text}")
    ch = sym.text
    d.tap(sym.c, 0.8)
    t = d.field_text()
    ok = split_ok(d)[0]
    check(t == ch, f"框里「{t}」")
    check(ok, "点了符号后没回到分体字母键盘")
    return f"面板 {bx}；点「{ch}」→「{t}」，回到分体"


@case("LS-18", base="B0-横屏", visual=True)
def ls18(d):
    s = d.screen()
    e = s.find(desc="表情", where="ime")
    d.tap(e.c, 1.2)
    s = d.screen()
    bx, tx = panel_box(d, s)
    d.shot("full", "横屏表情面板")
    d.swipe(((bx[0] + bx[1]) // 2, 850), ((bx[0] + bx[1]) // 2, 600), 400)
    time.sleep(0.6)
    s = d.screen()
    emo = next((n for n in s.ime() if n.text and ord(n.text[0]) > 0x2000 and n.b[1] > 500 and n.c[0] > bx[0]), None)
    if not emo:
        raise Blocked("面板里没有表情")
    d.tap(emo.c, 0.8)
    t = d.field_text()
    check(on_right(d, bx), f"表情面板 {bx}")
    check(t == emo.text, f"点「{emo.text}」框里「{t}」")
    return f"面板 {bx}；「{t}」"


@case("LS-19", base="B0-横屏", page="long", visual=True)
def ls19(d):
    d.expand_toolbar()
    d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
    s = d.screen()
    sa = s.find(desc="全选", where="ime") or s.find(text="全选", where="ime")
    cp = s.find(desc="复制", where="ime") or s.find(text="复制", where="ime")
    d.tap(sa.c, 0.4)
    d.tap(cp.c, 0.6)
    d.open_page("text")
    d.expand_toolbar()
    d.tap_text("剪贴板", where="ime", scroll=False, after=1.0)
    s = d.screen()
    bx, tx = panel_box(d, s)
    d.shot("full", "横屏剪贴板面板")
    item = next((n for n in s.ime() if len(n.text) > 8), None)
    if not item:
        raise Blocked("剪贴板面板里没有条目")
    d.tap(item.c, 0.8)
    t = d.field_text()
    check(on_right(d, bx), f"剪贴板面板 {bx}")
    check(t and t[:6] == item.text[:6], f"点条目后框里「{(t or '')[:12]}」")
    return f"面板 {bx}；粘贴了「{t[:8]}…」"


@case("LS-20", base="B0-横屏", mutates=True, visual=True)
def ls20(d):
    d.rotate(0)   # the 添加 list is laid out for portrait (in landscape the row is off screen)
    add_im(d, "九键拼音")
    d.rotate(1)
    d.open_page("text")
    d.switch_im("九键")
    for k in "64426":
        d.press(k)
    s = d.screen()
    bx, tx = panel_box(d, s)
    d.shot("full", "横屏九键打 64426")
    d.cands()
    d.space()
    t = d.field_text()
    check(on_right(d, bx), f"九键 {bx}")
    check(t == "你好", f"框里「{t}」")
    return f"九键 {bx}；「{t}」"


@case("LS-21", base="B0-横屏", mutates=True, visual=True)
def ls21(d):
    d.rotate(0)   # the 添加 list is laid out for portrait (in landscape the row is off screen)
    add_im(d, "手写")
    d.rotate(1)
    d.open_page("text")
    d.switch_im("手写")
    s = d.screen()
    pad = s.find(desc="手写板", where="ime")
    if not pad:
        raise Blocked("没有书写板")
    bx, tx = panel_box(d, s)
    d.shot("full", "横屏手写")
    d.sh("; ".join(script("09-handwriting").stroke_cmds(d, "人")))   # the pad the tree names 手写板
    time.sleep(1.6)
    d.space()
    t = d.field_text()
    check(on_right(d, bx), f"手写板 {bx}")
    check(t == "人", f"框里「{t}」")
    return f"手写板 {bx}；「{t}」"


@case("LS-22", base="B0-设置", mutates=True)
def ls22(d):
    appearance(d)
    choose(d, "分体时其他面板", "铺满")
    d.rotate(1)
    d.open_page("text")
    d.press("123", after=1.0)
    s = d.screen()
    bx, tx = panel_box(d, s)
    check(full(d, bx) and not arrow(d, s), f"数字键盘 {bx}，箭头 {arrow(d, s)}")
    return f"数字键盘 {bx}"


@case("LS-23", base="B0-设置", mutates=True)
def ls23(d):
    appearance(d)
    choose(d, "分体键盘", "关")
    r = d.row("分体时其他面板")
    if not r:
        raise Blocked("没有分体时其他面板")
    en = [n.enabled for n in r.walk() if n.text == "分体时其他面板"]
    check(en and not en[0], f"分体时其他面板 enabled={en}")
    return "灰的，点不了"


@case("LS-24")
def ls24(d):
    out = {}
    for k in ("123", "符"):
        if k == "符":
            abc(d)
        d.press(k, after=1.0)
        s = d.screen()
        bx, tx = panel_box(d, s)
        out[k] = (bx, bool(arrow(d, s)))
    check(all(full(d, v[0]) and not v[1] for v in out.values()), f"{out}")
    return f"{out}"


@case("LS-24a", base="B0-设置", mutates=True)
def ls24a(d):
    appearance(d)
    choose(d, "分体键盘", "总是")
    d.open_page("text")
    ok, r, g = split_ok(d)
    split = any(x[1] - x[0] > 20 for x in g)
    out = {}
    for k in ("123", "符"):
        if k == "符":
            abc(d)
        d.press(k, after=1.0)
        s = d.screen()
        bx, tx = panel_box(d, s)
        out[k] = (bx, bool(arrow(d, s)))
    check(split, f"字母键盘没分体：{g}")
    check(all(full(d, v[0]) and not v[1] for v in out.values()), f"{out}")
    return f"分体；面板 {out}"


@case("LS-25", base="B0-横屏", page="number")
def ls25(d):
    s = d.screen()
    bx, tx = panel_box(d, s)
    check(on_right(d, bx), f"数字键盘 {bx}")
    check(tx and abs(tx[0] - bx[0]) < 60, f"工具栏 {tx}")
    return f"数字键盘 {bx}，工具栏 {tx}"


@case("LS-27", base="B0-设置", mutates=True, visual=True)
def ls27(d):
    appearance(d)
    choose(d, "键盘高度", "很高")
    d.rotate(1)
    d.open_page("text")
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    f = d.field(s)
    ime = [n for n in s.ime() if n.b[3] > n.b[1]]
    # the docked area: from its topmost row (the code row over the bar, laid out though empty)
    # to the keys' bottom, the app's height here (the navigation bar below, no bottom padding);
    # the window-wide containers start at the status bar's bottom and are left out
    outer = min(n.b[1] for n in ime)
    top, bottom = min(n.b[1] for n in ime if n.b[1] > outer), kb.b[3]
    d.shot("full", "横屏，键盘高度很高")
    d.rotate(0)
    # the cap (KeyboardHeightCap): the area, bar to bottom, at most 70% of the app's height; in a
    # 1080 px landscape the browser's own bars take ~220 px, so what is left of the page may not
    # hold the field: whether the browser scrolls it into view is the browser's, not checked
    area, h = bottom - top, bottom
    check(area <= 0.70 * h + 8, f"很高时键盘区 {area} px，超过屏高 {h} px 的七成（{int(0.7 * h)}）")
    seen = "框看得到" if f and f.b[1] < kb.b[1] - 40 else "框被挡住（网页剩的高度放不下）"
    return f"很高时键盘区 {area}/{h} px（{area * 100 // h}%），{seen}"


@case("LS-28", base="B0-横屏")
def ls28(d):
    d.type("nihao")
    c = d.cands()
    s = d.screen()
    bar = [n for n in s.ime() if n.text in c[:8]]
    xs = (min(n.b[0] for n in bar), max(n.b[2] for n in bar)) if bar else None
    check(c[:1] == ["你好"], f"候选 {c[:3]}")
    check(xs and xs[0] < 400 and xs[1] > screen_w(d) * 0.6, f"候选横跨 {xs}")
    return f"候选 {c[:3]}，横跨 {xs}"


@case("LS-29", base="B0-横屏", mutates=True)
def ls29(d):
    sp = script("07-shuangpin")
    sp.scheme(d, "微软")
    ok, r, g = split_ok(d)
    if not ok:
        raise Blocked(f"不是分体键盘：前三排的空隙 {g}")
    s = d.screen()
    check(not sp.semi_key(d, s), "分体时也有单独的 ; 键")
    w = screen_w(d)
    row = next((x for x in r if any(lab.lower() == "l" for lab, _ in x)), None)
    if not row:
        raise Blocked(f"键盘上没有 l：{[[lab for lab, _ in x] for x in r]}")
    right = [b for lab, b in row if b[0] >= w / 2]
    lb = next(b for lab, b in row if lab.lower() == "l")
    # L is the right half's last key of its row and reaches the half's right edge like the row above
    above = [b for lab, b in r[0] if b[0] >= w / 2]
    check(lb[2] == max(b[2] for b in right), f"l 不是右半边这一排的最后一个键：{[lab for lab, _ in row]}")
    check(not above or abs(lb[2] - max(b[2] for b in above)) <= 12,
          f"l 的右边 {lb[2]}，上一排右端 {max(b[2] for b in above)}：行尾有空隙")
    d.type("x")
    # labelled 「；」 while 双拼 is on, as the ; key is
    items, xy = popup(d, "l")
    menu = [n.text for n in items]
    tgt = next((n for n in items if n.text in (";", "；")), None)
    if not tgt:
        d.touch(("up", *xy))
        raise Fail(f"长按 l 的弹出里没有分号：{menu}")
    popup_slide(d, xy, tgt)
    s = d.screen()
    c, t = d.candidates(s), d.field_text(s) or ""
    check(";" not in t and "；" not in t, f"长按 l 选 ; 打出了分号：框里「{t}」（弹出 {menu}）")
    check(any(x.split(" ")[0][:1] in ("性", "星", "行", "形", "型") for x in c[:8]),
          f"候选 {c[:6]}（拼音行 `{d.preedit(s)}`）")
    return f"分体无 ; 键，l 伸到行尾；x + 长按 l 选 ; → `{d.preedit(s)}` {c[:4]}"
