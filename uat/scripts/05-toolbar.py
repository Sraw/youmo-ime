"""uat/05-toolbar.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import re
import time

from uat import BAR_BOTTOM, Blocked, case, check, home, menu_tap, open_url, sym_char

LONG = "今天天气很好，我们一起去公园散步吧。"


def ime(d, text=None, desc=None, after=0.6, contains=False):
    """a node of the keyboard by its text or description (either matches either)"""
    s = d.screen()
    w = text or desc
    if contains:
        n = next((n for n in s.ime() if w in n.text or w in n.desc), None)
    else:
        n = s.find(text=w, where="ime") or s.find(desc=w, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{text or desc}」")
    d.tap(n.c, after)
    return n


def edit_panel(d):
    d.expand_toolbar()
    ime(d, desc="文本编辑", after=0.8)


def status_panel(d):
    d.expand_toolbar()
    ime(d, desc="状态区域", after=0.8)


def clip_panel(d):
    d.expand_toolbar()
    ime(d, desc="剪贴板", after=0.8)


def back_to_keys(d):
    ime(d, desc="返回键盘", after=0.6)
    d._keys = None


def copy_range(d, start, n):
    """in the focused field: the cursor at its very start (a tap before the first character: the
    panel's |< goes to the start of the line only), → start times, 选择, → n times, 复制"""
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 0.6)
    edit_panel(d)
    s = d.screen()
    right = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    sel = s.find(desc="选择", where="ime") or s.find(text="选择", where="ime")
    if start:
        d.taps([right.c] * start, gap=0.05, after=0.3)
    d.tap(sel.c, 0.3)
    d.taps([right.c] * n, gap=0.05, after=0.3)
    ime(d, desc="复制", after=0.6)
    ime(d, desc="把光标移动到结尾", after=0.3)
    back_to_keys(d)


def copy_word(d, word):
    """copy a word of the 长文本 field (the clipboard as 前提)"""
    d.open_page("long")
    i = LONG.index(word)
    copy_range(d, i, len(word))


def clip_items(d):
    s = d.screen()
    return [n for n in s.ime() if n.text and n.c[1] > BAR_BOTTOM and n.text not in ("试着复制些东西",)]


def clip_item(d, text):
    return next((n for n in clip_items(d) if n.text.startswith(text)), None)


def long_press(d, node):
    """long-press an item: its popup menu opens"""
    d.hold(node.c, 900)
    time.sleep(0.6)


def pin(d, text):
    """long-press the item, 置顶; the pin icon (an ImageView in the item) shows it took"""
    for _ in range(2):
        it = clip_item(d, text)
        if not it:
            raise Blocked(f"剪贴板里没有「{text}」")
        long_press(d, it)
        menu_tap(d, "置顶")
        time.sleep(0.5)
        it = clip_item(d, text)
        if it and it.parent and any(n.cls == "ImageView" for n in it.parent.walk()):
            return
    raise Blocked(f"「{text}」置顶后没有图钉")


@case("TB-01")
def tb01(d):
    s0 = d.screen()
    tb0 = d.toolbar(s0) and s0.find(desc="表情", where="ime") and s0.find(desc="隐藏键盘", where="ime")
    d.type("n")
    c = d.cands()
    d.bksp()
    time.sleep(0.4)
    tb2 = d.toolbar()
    check(tb0, "开始不是工具栏（>、笑脸、⌄）")
    check(c, "打 n 时没有候选")
    check(tb2, "删掉后没回到工具栏")
    return f"工具栏 → 候选 {c[:3]} → 工具栏"


@case("TB-02")
def tb02(d):
    d.expand_toolbar()
    s = d.screen()
    bar = [n.desc for n in sorted(s.ime(), key=lambda n: n.b[0]) if n.desc and n.c[1] < BAR_BOTTOM]
    want = ["撤销", "重做", "文本编辑", "剪贴板", "状态区域"]
    check([x for x in bar if x in want] == want, f"展开后 {bar}")
    return f"展开后 {bar}"


@case("TB-03", mutates="learn")
def tb03(d):
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    if d.field_text() != "你好":
        raise Blocked(f"前提没到：「{d.field_text()}」")
    d.expand_toolbar()
    ime(d, desc="撤销", after=0.8)
    t1 = d.field_text()
    ime(d, desc="重做", after=0.8)
    t2 = d.field_text()
    check(t1 != "你好", f"撤销后框里「{t1}」")
    check(t2 == "你好", f"重做后框里「{t2}」")
    return f"撤销「{t1}」，重做「{t2}」"


@case("TB-04")
def tb04(d):
    ime(d, desc="隐藏键盘", after=1.0)
    gone = not d.screen().find(rid="keyboard_view", where="ime")
    f = next(n for n in d.screen().app() if n.cls == "EditText" and n.rid != "url_field")
    d.tap(f.c, 1.2)
    back = d.screen().find(rid="keyboard_view", where="ime")
    check(gone, "点 ⌄ 后键盘没收起")
    check(back, "再点框键盘没出来")
    return "收起；再点框又出来"


@case("TB-05", mutates="learn")
def tb05(d):
    d.type("shi")
    d.cands()
    ime(d, desc="展开候选词列表", after=0.8)
    s = d.screen()
    grid = s.find(rid="expanded_candidate_view", where="ime")
    if not grid:
        raise Blocked("没展开成表格")
    before = [n.text for n in grid.walk() if n.text and n.cls == "TextView"]
    x0, y0, x1, y1 = grid.b
    d.swipe((540, y1 - 60), (540, y0 + 60), 500, after=0.8)
    s = d.screen()
    grid = s.find(rid="expanded_candidate_view", where="ime")
    after = [n for n in grid.walk() if n.text and n.cls == "TextView" and n.b[1] > y0 + 20]
    pick = after[len(after) // 2]
    d.tap(pick.c, 0.8)
    s = d.screen()
    t = d.field_text(s)
    check([n.text for n in after][:5] != before[:5], f"上滑后表格没翻：{before[:5]}")
    check(t == pick.text.split(" ")[0], f"点「{pick.text}」后框里「{t}」")
    check(not s.find(rid="expanded_candidate_view", where="ime"), "点后表格没关")
    return f"表格 {before[:4]}… 上滑后点「{t}」"


@case("TB-06", page="long")
def tb06(d):
    t0 = d.field_text()
    edit_panel(d)
    ime(d, desc="把光标移动到开头", after=0.3)
    ime(d, desc="选择", after=0.3)
    s = d.screen()
    r = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    d.taps([r.c] * 3, gap=0.1, after=0.4)
    ime(d, desc="复制", after=0.5)
    ime(d, desc="把光标移动到结尾", after=0.4)
    ime(d, desc="粘贴", after=0.8)
    t = d.field_text()
    check(t == t0 + "今天天", f"粘贴后末尾是「{t[-8:]}」")
    return f"末尾多了「{t[len(t0):]}」"


@case("TB-07", mutates="learn")
def tb07(d):
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    edit_panel(d)
    ime(d, desc="全选", after=0.5)
    s = d.screen()
    cut = s.find(desc="剪切", where="ime") or s.find(text="剪切", where="ime")
    if not cut:
        raise Blocked(f"全选后面板上没有剪切：{[n.desc or n.text for n in s.ime() if n.desc or n.text][:16]}")
    d.tap(cut.c, 0.6)
    t = d.field_text()
    back_to_keys(d)
    check(t == "", f"剪切后框里「{t}」")
    check("q" in d.keys(refresh=True), "← 后不是字母键盘")
    return "全选、剪切后为空，回到键盘"


@case("TB-08", mutates=True)
def tb08(d):
    copy_word(d, "公园")
    d.open_page("text")
    clip_panel(d)
    items = clip_items(d)
    check(items and items[0].text == "公园", f"剪贴板 {[n.text for n in items][:4]}")
    d.tap(items[0].c, 0.8)
    s = d.screen()
    t = d.field_text(s)
    check(t == "公园", f"框里「{t}」")
    check(d.key_node("q", s), "粘贴后没有回到键盘（还在剪贴板面板）")
    return f"最上面「公园」，点后框里「{t}」，回到键盘"


def bar_text(s, text):
    """text on the bar above the keys (the clipboard hint), not on a key"""
    kb = s.find(rid="keyboard_view", where="ime")
    top = kb.b[1] if kb else BAR_BOTTOM
    return next((n for n in s.ime() if n.text == text and n.c[1] < top), None)


@case("TB-09", mutates=True)
def tb09(d):
    copy_word(d, "散步")
    d.open_page("text")
    s = d.screen()
    hint = bar_text(s, "散步")
    check(hint, f"工具栏上没有「散步」：{[n.text or n.desc for n in s.ime() if n.c[1] < BAR_BOTTOM][:8]}")
    d.tap(hint.c, 0.8)
    t = d.field_text()
    check(t == "散步", f"点后框里「{t}」")
    d.clear()
    # again, then past the 30 s: shown at first, gone after
    copy_word(d, "散步")
    t_copy = time.time()
    d.open_page("text")
    check(bar_text(d.screen(), "散步"), "再复制一次后栏上没有「散步」")
    time.sleep(max(0, 31 - (time.time() - t_copy)))
    still = bar_text(d.screen(), "散步")
    check(not still, "复制 30 秒后提示还在")
    return "栏上显示「散步」，点后上屏；再复制一次也显示，30 秒后消失"


@case("TB-10", base="B0-设置", mutates=True)
def tb10(d):
    home(d, "剪贴板与表情")
    d.tap_text("剪贴板历史记录上限", after=1.0)
    s = d.screen()
    seek = next((n for n in s.nodes if n.cls == "SeekBar"), None)
    edit = next((n for n in s.nodes if n.cls == "EditText"), None)
    if seek:
        d.swipe((seek.b[2] - 10, seek.c[1]), (seek.b[0] - 50, seek.c[1]), 600)
    elif edit:
        # a number field, our number keys up: empty it, type 3
        d.tap((edit.b[2] - 20, edit.c[1]), 0.8)
        s = d.screen()
        bk = next((n for n in s.ime() if n.desc == "退格"), None)
        three = next((n for n in s.ime() if n.text == "3"), None)
        if not bk or not three:
            raise Blocked("上限输入框没弹出数字键盘")
        d.taps([bk.c] * 4 + [three.c], gap=0.1, after=0.5)
        d.hide_keyboard()
    ok = d.screen().find(text="确定")
    if ok:
        d.tap(ok.c, 0.8)
    limit = d.summary("剪贴板历史记录上限")
    copy_word(d, "公园")
    clip_panel(d)
    pin(d, "公园")
    back_to_keys(d)
    for w in ("今天", "天气", "我们", "一起"):
        i = LONG.index(w)
        copy_range(d, i, len(w))
    clip_panel(d)
    texts = [n.text for n in clip_items(d)]
    d.shot("kb", "复制 4 段后")
    m = re.search(r"\d+", limit or "")
    n_lim = int(m.group()) if m else None
    others = [t for t in texts if t != "公园"]
    check("公园" in texts, f"置顶的「公园」不在：{texts}")
    check(n_lim is None or len(others) <= n_lim, f"上限 {limit}，未置顶的 {others}")
    return f"上限「{limit}」；面板 {texts}"


@case("TB-11", mutates=True)
def tb11(d):
    copy_word(d, "公园")
    d.open_page("text")
    clip_panel(d)
    it = clip_item(d, "公园")
    if not it:
        raise Blocked("剪贴板里没有「公园」")
    long_press(d, it)
    menu_tap(d, "编辑")
    s = d.screen()
    e = next((n for n in s.nodes if n.cls == "EditText" and n.text.startswith("公园")), None)
    if not e:
        raise Blocked(f"编辑后是 {s.texts(where='all')[:10]}")
    d.tap((e.b[2] - 20, e.c[1]), 1.0)
    d.switch_im("拼音")
    d.type("menkou")
    d.cands(want="门口")
    d.pick("门口")
    d.hide_keyboard()
    s = d.screen()
    save = next((n for n in s.nodes if n.text in ("保存", "确定") and n.clickable), None)
    if not save:
        raise Blocked(f"编辑页没有保存：{s.texts(where='all')[:10]}")
    d.tap(save.c, 1.0)
    if "WebView" not in d.top_activity():
        d.back(1.0)
    open_url(d, "text")
    clip_panel(d)
    t1 = [n.text for n in clip_items(d)]
    it = clip_item(d, "公园门口")
    check(it, f"编辑后面板 {t1}")
    long_press(d, it)
    menu_tap(d, "删除")
    time.sleep(0.5)
    t2 = [n.text for n in clip_items(d)]
    check(not any(x.startswith("公园") for x in t2), f"删除后面板 {t2}")
    return f"编辑后 {t1[:2]}；删除后 {t2[:2]}"


@case("TB-12", mutates=True)
def tb12(d):
    copy_word(d, "公园")
    d.open_page("text")
    clip_panel(d)
    pin(d, "公园")
    back_to_keys(d)
    copy_word(d, "散步")
    d.open_page("text")
    clip_panel(d)
    s = d.screen()
    before = [n.text for n in clip_items(d)]
    trash = next((n for n in s.ime() if n.desc and ("删除" in n.desc or "清空" in n.desc)), None)
    if not trash:
        raise Blocked(f"剪贴板面板没有垃圾桶：{[n.desc for n in s.ime() if n.desc]}")
    d.tap(trash.c, 0.8)
    s = d.screen()
    ok = next((n for n in s.nodes if n.text in ("确定", "删除", "清空")), None)
    if ok:
        d.tap(ok.c, 1.8)
    texts = [n.text for n in clip_items(d) if not n.text.startswith(("已删除", "撤销"))]
    check("公园" in texts and "散步" not in texts, f"清空前 {before}，清空后 {texts}")
    return f"清空后 {texts}"


@case("TB-13", mutates=True)
def tb13(d):
    url = "https://example.com/a?id=1&utm_source=x"
    # 前提: the link copied. Typed in the 网址 field, where keys go in as they are (the 长文本
    # field would space and capitalize it as English), then 全选 + 复制 on the edit panel
    open_url(d, "url")
    for ch in url:
        if ch.isalpha():
            d.type(ch, after=0.3)
            if d.preedit():   # after "=" letters compose pinyin here: the return key puts them in as typed
                d.enter()
        elif ch == ".":
            d.press(".", after=0.3)
        elif ch == "/":
            n = d.key_node("/")
            d.tap(n.c, 0.3)
        else:
            sym_char(d, ch)
    time.sleep(0.5)
    t = d.field_text() or ""
    if t != url:
        raise Blocked(f"前提没到：网址框里「{t}」")
    edit_panel(d)
    ime(d, desc="全选", after=0.4)
    ime(d, desc="复制", after=0.6)
    back_to_keys(d)
    open_url(d, "text")
    clip_panel(d)
    texts = [n.text for n in clip_items(d)]
    back_to_keys(d)
    f = d.field()
    d.hold(f.c, 900)
    time.sleep(0.6)
    menu_tap(d, "粘贴")
    pasted = d.field_text()
    check(texts and texts[0] == "https://example.com/a?id=1", f"剪贴板面板 {texts[:2]}")
    check(pasted == url, f"系统粘贴出「{pasted}」")
    return f"面板「{texts[0] if texts else ''}」；系统粘贴「{pasted}」"


@case("TB-14", base="B0-设置", mutates=True)
def tb14(d):
    home(d, "剪贴板与表情")
    d.switch("记录剪贴板历史", False)
    copy_word(d, "公园")
    clip_panel(d)
    texts = [n.text for n in clip_items(d)]
    check("公园" not in texts, f"面板 {texts}")
    return f"面板 {texts}"


def status_toggle(d, frm):
    status_panel(d)
    n = d.screen().find(text=frm, where="ime")
    if not n:
        raise Blocked(f"状态面板没有「{frm}」")
    d.tap(n.c, 0.8)
    s = d.screen()
    now = next((m.text for m in s.ime() if abs(m.c[0] - n.c[0]) < 30 and abs(m.c[1] - n.c[1]) < 30 and m.text), "")
    back_to_keys(d)
    return now


@case("TB-15", mutates=True)
def tb15(d):
    now = status_toggle(d, "简体")
    d.type("guojia")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "國家", f"切到「{now}」后打 guojia：「{t}」")
    return f"「{now}」：「{t}」"


@case("TB-16", mutates=True)
def tb16(d):
    now = status_toggle(d, "中文标点")
    d.press(",")
    t = d.field_text()
    check(t == ",", f"切到「{now}」后：「{t}」")
    return f"「{now}」：「{t}」"


@case("TB-17", mutates=True)
def tb17(d):
    from uat import flick
    now = status_toggle(d, "半角")
    flick(d, "q")
    t = d.field_text()
    d.switch_im("英文")
    flick(d, "q")
    t2 = d.field_text()
    check(t == "１", f"切到「{now}」后：「{t}」")
    check(t2 == "１1", f"英文再打 1：「{t2}」")
    return f"「{t}」→「{t2}」"


@case("TB-18", mutates=True)
def tb18(d):
    got = []
    def back_to_page():
        for _ in range(5):   # the system back key, as the user returns to the page
            if "WebView" in d.top_activity():
                break
            d.back(0.8)
    for item, want in (("主题", "外观"), ("输入法设置", "拼音"), ("按键", "按键")):
        back_to_page()
        d.open_page("text")
        status_panel(d)
        ime(d, text=item, after=1.5)
        s = d.screen()
        title = next((n.text for n in s.app() if n.parent and n.parent.rid == "toolbar" and n.text), "")
        got.append(title)
        check(title == want, f"点「{item}」后是「{title}」")
    back_to_page()
    d.open_page("text")
    status_panel(d)
    ime(d, desc="打开输入法设置", after=1.5)
    s = d.screen()
    check(d.at_home(s), f"点齿轮后是 {s.texts()[:4]}")
    return f"{got} 和首页"


@case("TB-19", base="B0-英文")
def tb19(d):
    status_panel(d)
    s = d.screen()
    texts = [n.text for n in s.ime() if n.text]
    bad = [x for x in ("简体", "繁體", "繁体", "中文标点", "英文标点", "半角", "全角") if x in texts]
    check(not bad, f"英文的状态面板里有 {bad}")
    return f"状态面板 {texts}"


# ---------------------------------------------------------------- 单手、悬浮

def one_hand(d):
    status_panel(d)
    ime(d, text="单手模式", after=1.0)


def kbox(d, s=None):
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    return kb.b if kb else None


@case("TB-20", mutates=True, visual=True)
def tb20(d):
    W = 1080
    one_hand(d)
    s = d.screen()
    b1 = kbox(d, s)
    d.shot("kb", "单手（靠右）")
    btn = s.find(desc="移到左边", where="ime")
    check(b1 and 0.7 < (b1[2] - b1[0]) / W < 0.9 and b1[2] >= W - 5, f"单手键盘 {b1}")
    check(btn and btn.c[0] < b1[0], "左边没有按钮")
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    t = d.field_text()
    d.tap(btn.c, 1.0)
    s = d.screen()
    b2 = kbox(d, s)
    d.shot("kb", "换到左边")
    full = next((n for n in s.ime() if n.desc == "恢复全宽"), None)
    check(b2 and b2[0] <= 5 and full and full.c[0] > b2[2], f"换边后键盘 {b2}，按钮 {full and full.c}")
    d.tap(full.c, 1.0)
    b3 = kbox(d)
    check(b3 and b3[2] - b3[0] >= W - 5, f"恢复后 {b3}")
    one_hand(d)
    b4 = kbox(d)
    check(b4 and b4[0] <= 5, f"再开单手 {b4}（应回到左边）")
    check(t == "你好", f"单手时打出「{t}」")
    return f"靠右 {b1}，左 {b2}，全宽，再开 {b4}；打出「{t}」"


@case("TB-21", mutates=True, visual=True)
def tb21(d):
    one_hand(d)
    b = kbox(d)
    out = []
    for open_, name in ((lambda: d.press("符", after=1.0), "符号"),
                        (lambda: ime(d, desc="表情", after=1.0), "表情"),
                        (lambda: clip_panel(d), "剪贴板")):
        open_()
        s = d.screen()
        iw = s.find(rid="input_window", where="ime")
        out.append((name, iw.b if iw else None))
        d.shot("kb", f"单手时的{name}面板")
        back = s.find(text="返回", where="ime") or s.find(text="ABC", where="ime") or \
            s.find(desc="返回键盘", where="ime")
        if back:
            d.tap(back.c, 0.6)
        d._keys = None
    bad = [(n, bb) for n, bb in out if not bb or abs(bb[0] - b[0]) > 5 or abs(bb[2] - b[2]) > 5]
    check(not bad, f"键盘 {b}，面板 {out}")
    return f"键盘 {b}；面板 {[bb for _, bb in out]}"


def floating(d):
    status_panel(d)
    ime(d, text="悬浮键盘", after=1.2)


def handle(d, s=None):
    s = s or d.screen()
    dock = s.find(desc="键盘放回底部", where="ime")
    kb = s.find(rid="keyboard_view", where="ime")
    if not dock or not kb:
        raise Blocked("没有悬浮键盘的横条")
    return ((kb.b[0] + kb.b[2]) // 2, dock.c[1]), dock


def drag(d, a, b):
    steps = [("down", *a), ("sleep", 0.2)] + \
        [("move", a[0] + (b[0] - a[0]) * i // 10, a[1] + (b[1] - a[1]) * i // 10) for i in range(1, 11)] + \
        [("sleep", 0.2), ("up", *b)]
    d.touch(*steps)
    time.sleep(0.8)


@case("TB-22", page="sentence", mutates=True, visual=True)
def tb22(d):
    # the sentence field and the text field both on the page: the full page
    open_url(d, "", field=0)
    s0 = d.screen()
    f0 = [n.b for n in s0.app() if n.cls == "EditText" and n.rid != "url_field"]
    floating(d)
    s = d.screen()
    b1 = kbox(d, s)
    f1 = [n.b for n in s.app() if n.cls == "EditText" and n.rid != "url_field"]
    h, _ = handle(d, s)
    drag(d, h, (h[0], 500))
    s = d.screen()
    b2 = kbox(d, s)
    d.shot("full", "拖到上半部分")
    check(b1 and 0.6 < (b1[2] - b1[0]) / 1080 < 0.85, f"悬浮键盘 {b1}")
    check(f0[:2] == f1[:2], f"页面被顶起：{f0[:2]} → {f1[:2]}")
    check(b2 and b2[1] < b1[1] - 400, f"拖后键盘 {b2}（拖前 {b1}）")
    # a field outside the keyboard: the second one (句子) if it shows
    s = d.screen()
    fields = [n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"]
    free = [n for n in fields if n.b[3] < b2[1] or n.b[1] > b2[3]]
    if len(free) >= 2:
        d.tap(free[1].c, 0.8)
        check(d.field() and d.field().b == free[1].b, "点键盘外的框焦点没过去")
    a = d.key_node("a")
    x, y = a.c
    # a moment for the bubble to be drawn: the release build's frame can come after an immediate screencap
    d.sh(f"input motionevent DOWN {x} {y}; sleep 0.1; screencap -p /sdcard/uat-p.png; input motionevent UP {x} {y}")
    png = d.adb("exec-out", "cat", "/sdcard/uat-p.png", binary=True)
    d.shot((max(0, x - 300), max(0, a.b[1] - 330), min(1080, x + 300), a.b[3] + 20), "按住 a", png=png)
    return f"悬浮 {b1} → 拖到 {b2}；页面不动"


@case("TB-23", mutates=True, visual=True)
def tb23(d):
    floating(d)
    h, _ = handle(d)
    drag(d, h, (330, 300))
    b1 = kbox(d)
    d.back(1.0)
    f = next(n for n in d.screen().app() if n.cls == "EditText" and n.rid != "url_field")
    d.tap(f.c, 1.2)
    b2 = kbox(d)
    d.rotate(1)
    s = d.screen()
    if not s.find(rid="keyboard_view", where="ime"):
        f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
        if f:
            d.tap(f.c, 1.2)
    b3 = kbox(d)
    d.shot("full", "横屏")
    d.rotate(0)
    s = d.screen()
    if not s.find(rid="keyboard_view", where="ime"):
        f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
        if f:
            d.tap(f.c, 1.2)
    b4 = kbox(d)
    check(b1 and b2 and abs(b1[1] - b2[1]) < 20 and abs(b1[0] - b2[0]) < 20, f"收起再出 {b1} → {b2}")
    check(b3 and b3[0] < 400 and b3[1] < 500, f"横屏时 {b3}（应在左上）")
    check(b4 and b4[0] < 300 and b4[1] < 900, f"转回竖屏 {b4}")
    return f"左上 {b1}，再出 {b2}，横屏 {b3}，竖屏 {b4}"


@case("TB-24", mutates=True)
def tb24(d):
    floating(d)
    _, dock = handle(d)
    d.tap(dock.c, 1.0)
    b = kbox(d)
    status_panel(d)
    s = d.screen()
    fl = s.find(text="悬浮键盘", where="ime")
    on = fl and (fl.checked or fl.selected)
    back_to_keys(d)
    check(b and b[2] - b[0] >= 1075 and b[3] >= 2100, f"放回后键盘 {b}")
    check(not on, "状态面板里悬浮键盘还开着")
    return f"放回底部 {b}"


# ---------------------------------------------------------------- 计算结果

def numpad(d, keys):
    d.press("123", after=0.8)
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    lab = {}
    for n in kb.walk():
        if n.clickable:
            t = [m.text for m in n.walk() if m.text]
            if t:
                lab[t[0]] = n.c
    pts = []
    for ch in keys:
        if ch not in lab:
            raise Blocked(f"数字键盘上没有「{ch}」")
        pts.append(lab[ch])
    d.taps(pts, gap=0.12, after=0.8)


def result_on_bar(d):
    s = d.screen()
    return next((n for n in s.ime() if n.text.startswith("=") and n.c[1] < BAR_BOTTOM), None)


@case("TB-25", base="B0-英文")
def tb25(d):
    numpad(d, "12*3.5=")
    r = result_on_bar(d)
    check(r and r.text.replace(" ", "") == "=42", f"栏上 {r.text if r else None}")
    d.tap(r.c, 0.8)
    t = d.field_text()
    check(t == "12*3.5=42", f"框里「{t}」")
    return f"栏上「{r.text}」，框里「{t}」"


@case("TB-26", base="B0-设置", mutates=True)
def tb26(d):
    home(d, "通用输入")
    d.switch("点算式结果时替换整个算式", True)
    d.open_page("text")
    d.switch_im("英文")
    numpad(d, "1+2*2=")
    r = result_on_bar(d)
    check(r and r.text.replace(" ", "") == "=5", f"栏上 {r.text if r else None}")
    d.tap(r.c, 0.8)
    t = d.field_text()
    check(t == "5", f"框里「{t}」")
    return f"框里「{t}」"


@case("TB-27", base="B0-英文")
def tb27(d):
    numpad(d, "0.1+0.2=")
    r1 = result_on_bar(d)
    s = d.screen()
    sp = next((n for n in s.ime() if n.desc == "空格"), None)
    if not sp:
        raise Blocked("数字键盘没有空格键")
    d.swipe(sp.c, (sp.c[0] - 330, sp.c[1]), 2500, after=0.8)
    r2 = result_on_bar(d)
    bk = next((n for n in d.screen().ime() if n.desc == "退格"), None)
    d.taps([bk.c] * 12, gap=0.05, after=0.5)
    s = d.screen()
    lab = {([m.text for m in n.walk() if m.text] or [""])[0]: n.c
           for n in s.find(rid="keyboard_view", where="ime").walk() if n.clickable}
    d.taps([lab[c] for c in "200*15"], gap=0.12, after=0.4)
    d.tap(lab["!?#"], 1.0)
    pct = None
    for name in ("常用", "英文", "数学"):
        n = d.visible(name, d.screen(), where="ime")
        if n:
            d.tap(n.c, 0.6)
        pct = next((m for m in d.screen().ime() if m.text == "%" and m.c[0] > 200), None)
        if pct:
            break
    if not pct:
        raise Blocked("符号面板里找不到 %")
    d.tap(pct.c, 0.8)
    s = d.screen()
    if not s.find(text="=", where="ime"):
        d.press("123", after=0.8)
    s = d.screen()
    eq = next((n for n in s.find(rid="keyboard_view", where="ime").walk()
               if [m.text for m in n.walk() if m.text][:1] == ["="]), None)
    d.tap(eq.c, 0.8)
    r3 = result_on_bar(d)
    t3 = d.field_text()
    open_url(d, "password")
    numpad(d, "1+1=")
    r4 = result_on_bar(d)
    check(r1 and r1.text.replace(" ", "") == "=0.3", f"0.1+0.2= 栏上 {r1 and r1.text}")
    check(not r2, f"光标移开后栏上还有 {r2 and r2.text}")
    check(r3 and r3.text.replace(" ", "") == "=30", f"「{t3}」栏上 {r3 and r3.text}")
    check(not r4, f"密码框里栏上 {r4 and r4.text}")
    return f"=0.3；移开后无；「{t3}」{r3.text}；密码框无"


@case("TB-28", page="long")
def tb28(d):
    t0 = d.field_text() or ""
    edit_panel(d)
    ime(d, desc="把光标移动到开头", after=0.3)
    ime(d, desc="选择", after=0.3)
    ime(d, desc="复制", after=0.5)
    s = d.screen()
    r = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    if not r:
        raise Blocked("面板上没有 →")
    d.tap(r.c, 0.4)
    ime(d, desc="返回键盘", after=0.6)
    d.press(",")
    t1 = d.field_text() or ""
    i = next((k for k, (a, b) in enumerate(zip(t0, t1)) if a != b), len(t0))
    # a selection would be replaced by the comma: same length; no selection: one more character
    check(len(t1) == len(t0) + 1, f"打逗号后字数 {len(t0)} → {len(t1)}（有选区被替换了？）")
    check(i == 1, f"复制再 → 后光标在 {i}，不在第 1 个字后")
    return f"逗号落在第 {i} 位，没有选区"


@case("TB-29")
def tb29(d):
    d.type("ni", after=0.6)
    s = d.screen()
    b = s.find(desc="展开候选词列表", where="ime")
    if not b:
        raise Blocked("打 ni 后候选栏右边没有展开 ⌄")
    d.hold(b.c, 900)
    time.sleep(0.8)
    s = d.screen()
    check(not s.find(rid="expanded_candidate_view", where="ime"), "长按 ⌄ 展开了候选表格")
    check(not s.find(rid="keyboard_view", where="ime"), "长按 ⌄ 后键盘还在")
    # 联想 (the bar after a commit) has the same ⌄: a tap expands, a long press hides
    d.open_page("text")
    d.type("ni", after=0.6)
    d.pick("你")
    time.sleep(0.6)
    b = d.screen().find(desc="展开候选词列表", where="ime")
    if not b:
        raise Blocked("选「你」后联想栏右边没有展开 ⌄")
    d.tap(b.c, 0.8)
    s = d.screen()
    grid = s.find(rid="expanded_candidate_view", where="ime")
    check(grid, "联想时点一下 ⌄ 没展开联想表格")
    d.tap(b.c, 0.8)   # the same spot folds the table back
    b = d.screen().find(desc="展开候选词列表", where="ime")
    if not b:
        raise Blocked("收起联想表格后没有 ⌄")
    d.hold(b.c, 900)
    time.sleep(0.8)
    s = d.screen()
    check(not s.find(rid="keyboard_view", where="ime"), "联想时长按 ⌄ 后键盘还在")
    return "有候选、有联想时长按 ⌄ 都收起键盘；联想时点一下展开表格"


@case("TB-31", base="B0-设置", mutates=True)
def tb31(d):
    home(d, "剪贴板与表情")
    d.switch("粘贴后回到键盘", False)
    copy_word(d, "公园")
    d.open_page("text")
    clip_panel(d)
    it = clip_item(d, "公园")
    check(it, f"剪贴板 {[n.text for n in clip_items(d)][:4]}")
    d.tap(it.c, 0.8)
    s = d.screen()
    t1 = d.field_text(s)
    stay = not d.key_node("q", s) and clip_item(d, "公园")
    check(t1 == "公园", f"第一次点后框里「{t1}」")
    check(stay, "关掉后粘贴还是回到了键盘")
    d.tap(clip_item(d, "公园").c, 0.8)
    t2 = d.field_text()
    check(t2 == "公园公园", f"第二次点后框里「{t2}」")
    return f"点一次「{t1}」，面板仍在；再点「{t2}」"


@case("TB-32", base="B0-设置", mutates=True)
def tb32(d):
    def corner_is_handle():
        """whether the keyboard's very corner is the handle's colour (square) or what is under it (round)"""
        s = d.screen()
        h, dock = handle(d, s)
        kb = kbox(d, s)
        im = d.png()
        # the handle's strip is the keyboard's top: the dock button's top, the keys' left
        inside, corner = im.getpixel((h[0] - 150, h[1])), im.getpixel((kb[0] + 1, dock.b[1] + 1))
        return max(abs(a - b) for a, b in zip(inside, corner)) < 12, inside, corner
    d.open_page("text")
    floating(d)
    rounded = corner_is_handle()
    home(d, "外观")
    d.tap_text("大小与样式", scroll=False, after=1.0)
    d.switch("悬浮键盘圆角", False)
    d.open_page("text")
    square = corner_is_handle()
    check(not rounded[0], f"开着时角上是横条的颜色 {rounded[2]}（横条 {rounded[1]}）")
    check(square[0], f"关掉后角上不是横条的颜色 {square[2]}（横条 {square[1]}）")
    return f"开：角 {rounded[2]}，横条 {rounded[1]}；关：角 {square[2]}"
