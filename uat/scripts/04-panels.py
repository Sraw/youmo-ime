"""uat/04-panels.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import (BAR_BOTTOM, GRID_BOTTOM, GRID_TOP, PANEL_BOTTOM, PANEL_TOP, Blocked, case, check, emoji_tab_xy, home,
                 open_url)

CATS = ["最近使用", "笑脸", "人物", "动物与自然", "食物与饮料", "旅行与地点", "活动", "物品", "符号", "旗帜"]


def ime_tap(d, text=None, desc=None, after=0.6):
    s = d.screen()
    n = s.find(text=text, where="ime") if text else s.find(desc=desc, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{text or desc}」")
    d.tap(n.c, after)
    return n


def keyboard_rows(d):
    """the keys' labels row by row (a key with no text by its description)"""
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    keys = [n for n in kb.walk() if n.clickable] if kb else []
    rows = {}
    for n in keys:
        t = [m.text for m in n.walk() if m.text]
        rows.setdefault(n.c[1], []).append((n.c[0], t[0] if t else n.desc))
    return [[x for _, x in sorted(r)] for _, r in sorted(rows.items())]


def sym_category(d, name):
    """the 符 panel: scroll the category column to name and tap it"""
    for _ in range(6):
        s = d.screen()
        m = d.visible(name, s, where="ime")
        if m:
            d.tap(m.c, 0.8)
            return
        col = s.find(text="常用", where="ime") or s.find(text="中文", where="ime")
        x = col.c[0] if col else 97
        d.swipe((x, 1950), (x, 1650), 300)
    for _ in range(6):   # and back up
        s = d.screen()
        m = d.visible(name, s, where="ime")
        if m:
            d.tap(m.c, 0.8)
            return
        d.swipe((97, 1650), (97, 1950), 300)
    raise Blocked(f"符号面板里找不到分类「{name}」")


def sym_categories(d):
    seen = []
    for direction in (1, -1):
        for _ in range(6):
            s = d.screen()
            col = [n.text for n in s.ime() if n.text and n.b[2] < 200 and PANEL_TOP < n.c[1] < PANEL_BOTTOM
                   and n.text not in ("返回",)]
            new = [x for x in col if x not in seen]
            seen += new
            if not new and _ > 0:
                break
            if direction == 1:
                d.swipe((97, 1950), (97, 1650), 300)
            else:
                d.swipe((97, 1650), (97, 1950), 300)
    return seen


def sym_tap(d, char):
    for _ in range(8):
        s = d.screen()
        n = d.visible(char, s, where="ime")
        if n and n.c[0] > 200:
            d.tap(n.c, 0.6)
            return
        d.swipe((640, 2000), (640, 1700), 300)
    raise Blocked(f"这一类里找不到「{char}」")


def selected_category(d):
    """the highlighted category: the one drawn differently is not in the tree; read the page's first
    symbols instead"""
    s = d.screen()
    return [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
            if n.text and n.c[0] > 200 and PANEL_TOP < n.c[1] < PANEL_BOTTOM][:6]


def emoji_tab(d, i):
    d.tap(emoji_tab_xy(i), 0.8)
    s = d.screen()
    hdr = next((n.text for n in sorted(s.ime(), key=lambda n: n.b[1]) if n.text in CATS), "")
    return hdr


def emoji_find(d, chars, pages=30):
    """slow swipes (no fling) down the grid until one of chars shows"""
    chars = (chars,) if isinstance(chars, str) else chars
    for _ in range(pages):
        s = d.screen()
        n = next((n for n in s.ime() if n.text in chars and GRID_TOP < n.c[1] < GRID_BOTTOM), None)
        if n:
            return n
        d.swipe((540, 2000), (540, 1640), 900, after=0.3)
    return None


def search_keys(d, letters, after=0.6):
    """the emoji search's own letter keys (no keyboard_view there)"""
    s = d.screen()
    pts = []
    for ch in letters:
        n = next((n for n in s.ime() if n.text == ch and n.c[1] > 1700), None)
        if not n:
            raise Blocked(f"搜索键盘上没有 {ch}")
        pts.append(n.c)
    d.taps(pts, after=after)


def search_bksp(d, n=1):
    b = d.screen().find(rid="button_backspace", where="ime")
    d.taps([b.c] * n, after=0.5)


def search_row(d):
    s = d.screen()
    return [n for n in s.ime() if n.text and BAR_BOTTOM < n.c[1] < BAR_BOTTOM + 140 and n.text != "打拼音找表情"]


@case("PAN-01", visual=True)
def pan01(d):
    d.press("123", after=0.8)
    rows = keyboard_rows(d)
    d.shot("kb", "数字键盘")
    k = d.keys(refresh=True)
    d.taps([k["4"], k["2"]], after=0.5)
    t = d.field_text()
    ime_tap(d, text="ABC")
    q = "q" in d.keys(refresh=True)
    want = [["+", "1", "2", "3", "/"], ["-", "4", "5", "6", "空格"], ["*", "7", "8", "9", "退格"],
            ["ABC", ",", "!?#", "0", "=", ".", "前往"]]
    check(rows == want, f"数字键盘 {rows}")
    check(t == "42", f"框里「{t}」")
    check(q, "点 ABC 后不是字母键盘")
    return f"{rows}；「{t}」；回到字母"


@case("PAN-02", page="number")
def pan02(d):
    r1 = keyboard_rows(d)
    open_url(d, "tel")
    time.sleep(0.5)
    r2 = keyboard_rows(d)
    ok1 = any("0" in r for r in r1) and not any("q" in r for r in r1)
    ok2 = any("0" in r for r in r2) and not any("q" in r for r in r2)
    check(ok1, f"数字框 {r1}")
    check(ok2, f"电话框 {r2}")
    return "数字、电话框都是数字键盘"


@case("PAN-03")
def pan03(d):
    d.press("123", after=0.8)
    ime_tap(d, text="!?#", after=1.0)
    s = d.screen()
    check(s.find(text="常用", where="ime") and s.find(desc="选完符号后停留", where="ime"), "没打开符号面板")
    return "打开符号面板"


@case("PAN-04", mutates="learn", clean=True)
def pan04(d):
    d.press("符", after=1.0)
    cats = sym_categories(d)
    sym_category(d, "中文")
    sym_tap(d, "《")
    s = d.screen()
    t = d.field_text(s)
    want = ["常用", "中文", "英文", "括号", "箭头", "数学", "序号", "图形", "全角"]
    check([c for c in cats if c in want] == want, f"分类 {cats}")
    check(t == "《", f"框里「{t}」")
    check(s.find(rid="keyboard_view", where="ime") and "q" in d.keys(refresh=True), "面板没关回字母键盘")
    return f"分类 {cats}；「{t}」，回到键盘"


@case("PAN-05", mutates="learn")
def pan05(d):
    d.press("符", after=1.0)
    ime_tap(d, desc="选完符号后停留")
    sym_category(d, "箭头")
    for ch in "→←↑":
        sym_tap(d, ch)
    s = d.screen()
    t = d.field_text(s)
    check(t == "→←↑", f"框里「{t}」")
    # locked, the button offers the other way: 选完符号后返回
    check(s.find(desc="选完符号后返回", where="ime"), "点符号后面板关了")
    return f"「{t}」，面板没关"


@case("PAN-07", mutates="learn")
def pan07(d):
    d.press("符", after=1.0)
    sym_category(d, "图形")
    sym_tap(d, "★")
    d.press("符", after=1.0)
    sym_category(d, "最近")
    now = selected_category(d)
    check(now and now[0] == "★", f"最近 {now}")
    return f"最近 {now[:4]}"


@case("PAN-08")
def pan08(d):
    d.press("符", after=1.0)
    ime_tap(d, desc="数字键盘", after=0.8)
    rows = keyboard_rows(d)
    num = any("!?#" in r for r in rows)
    ime_tap(d, text="ABC", after=0.6)
    d.press("符", after=1.0)
    ime_tap(d, text="返回", after=0.8)
    q = "q" in d.keys(refresh=True)
    check(num, f"12/34 后 {rows}")
    check(q, "返回后不是字母键盘")
    return "12/34 是数字键盘；返回回到字母"


@case("PAN-09", mutates="learn", visual=True)
def pan09(d):
    ime_tap(d, desc="表情", after=1.0)
    names = [emoji_tab(d, i) for i in range(10)]
    d.shot("kb", "表情面板（旗帜）")
    emoji_tab(d, 3)
    cat = emoji_find(d, "🐱")
    if not cat:
        raise Blocked("动物与自然里找不到 🐱")
    d.tap(cat.c, 0.6)
    t = d.field_text()
    check(names[1:] == CATS[1:], f"分类标签 {names}")
    check(t == "🐱", f"框里「{t}」")
    return f"标签 {names}；「{t}」"


@case("PAN-10", base="B0-设置", mutates=True)
def pan10(d):
    home(d, "剪贴板与表情")
    d.tap_text("默认表情肤色", after=1.0)
    s = d.screen()
    opts = [n for n in s.nodes if n.cls in ("CheckedTextView", "RadioButton", "TextView") and n.clickable
            and n.b[1] > 300]
    if len(opts) < 3:
        raise Blocked(f"肤色选项 {[n.text for n in opts]}")
    tone = opts[2].text
    d.tap(opts[2].c, 1.0)
    ok = d.screen().find(text="确定")
    if ok:
        d.tap(ok.c, 1.0)
    d.open_page("text")
    ime_tap(d, desc="表情", after=1.0)
    emoji_tab(d, 2)
    th = emoji_find(d, ("👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"))
    if not th:
        raise Blocked("人物里找不到竖大拇指")
    d.tap(th.c, 0.6)
    t = d.field_text() or ""
    check(t.startswith("👍") and len(t) == 2 and t != "👍", f"选「{tone}」后打出「{t}」{[hex(ord(c)) for c in t]}")
    return f"肤色「{tone}」：「{t}」"


@case("PAN-11")
def pan11(d):
    ime_tap(d, desc="表情", after=1.0)
    ime_tap(d, text="ABC", after=0.6)
    check("q" in d.keys(refresh=True), "没回到字母键盘")
    return "回到字母键盘"


@case("PAN-12", mutates="learn", visual=True)
def pan12(d):
    ime_tap(d, desc="表情", after=1.0)
    ime_tap(d, desc="按拼音搜索表情", after=0.8)
    search_keys(d, "xiao")
    row1 = [n.text for n in search_row(d)]
    d.shot("kb", "搜 xiao")
    search_bksp(d, 4)
    search_keys(d, "dz")
    thumb = next((n for n in search_row(d) if n.text.startswith("👍")), None)
    if not thumb:
        raise Blocked(f"打 dz 后上面一排 {[n.text for n in search_row(d)]}")
    d.tap(thumb.c, 0.6)
    t = d.field_text()
    ime_tap(d, text="返回", after=0.8)
    emoji_tab(d, 0)
    s = d.screen()
    recent = [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
              if n.text and 1620 < n.c[1] < 2060 and n.text not in CATS]
    check(any(x in ("😄", "😆", "😀", "😃", "😁", "😂", "🤣", "😊", "😹") for x in row1), f"打 xiao 时上面一排 {row1}")
    check(t and t.startswith("👍"), f"框里「{t}」")
    check(recent and recent[0].startswith("👍"), f"最近使用 {recent[:5]}")
    return f"xiao {row1[:5]}；dz 出 👍，框里「{t}」；最近 {recent[:3]}"


def numpad_space_slide(d):
    """on the number keyboard: slide left on space, then type 1; returns (where the 1 landed, the
    text before, after)"""
    t0 = d.field_text() or ""
    # the cursor at the end, as 前提 says: open_page's tap past the text left it at the start now
    # and then, which read as a slide that moved it (Ctrl+End, a key the browser takes)
    d.sh("input keycombination 113 123")
    time.sleep(0.4)
    ime_tap(d, text="123", after=0.8)
    s = d.screen()
    sp = next((n for n in s.ime() if n.desc == "空格"), None)
    if not sp:
        raise Blocked("数字键盘没有空格键")
    d.swipe(sp.c, (sp.c[0] - 330, sp.c[1]), 1500, after=0.8)
    ime_tap(d, text="1", after=0.5)
    t1 = d.field_text() or ""
    i = next((k for k, (a, b) in enumerate(zip(t0, t1)) if a != b), len(t0))
    return i, t0, t1


@case("PAN-14", page="long")
def pan14(d):
    i1, t0, t1 = numpad_space_slide(d)
    check(len(t1) == len(t0) + 1, f"滑完打 1 后多了 {len(t1) - len(t0)} 个字（不该打出空格）")
    check(i1 < len(t0) - 2, f"滑空格光标没往前移：1 在 {i1}/{len(t0)}")
    return f"1 在 {i1}/{len(t0)}"


def recent_emoji(d):
    emoji_tab(d, 0)
    s = d.screen()
    return [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
            if n.text and 1620 < n.c[1] < 2060 and n.text not in CATS]


def pick_cat(d):
    emoji_tab(d, 3)
    cat = emoji_find(d, "🐱")
    if not cat:
        raise Blocked("动物与自然里找不到 🐱")
    d.tap(cat.c, 0.6)


@case("PAN-15", base="B0-设置", mutates=True)
def pan15(d):
    d.tap_text("搜索设置", scroll=False)
    f = d.field()
    if not f:
        raise Blocked("搜索框没有焦点")
    d.tap(f.c, 0.8)
    ime_tap(d, desc="表情", after=1.0)
    pick_cat(d)
    t = d.field_text() or ""
    if "🐱" not in t:
        raise Blocked(f"设置搜索框里点 🐱 后框里「{t}」")
    d.hide_keyboard()
    d.open_page("text")
    ime_tap(d, desc="表情", after=1.0)
    r1 = recent_emoji(d)
    pick_cat(d)
    ime_tap(d, text="ABC", after=0.6)
    ime_tap(d, desc="表情", after=1.0)
    r2 = recent_emoji(d)
    check(not any(x.startswith("🐱") for x in r1), f"设置搜索框里点的 🐱 进了最近使用：{r1[:5]}")
    check(any(x.startswith("🐱") for x in r2), f"测试页点的 🐱 不在最近使用：{r2[:5]}")
    return f"设置里点后最近 {r1[:3]}；测试页点后 {r2[:3]}"
