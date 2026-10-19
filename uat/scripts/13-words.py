"""uat/13-words.md (词库与短语). The .md is the source of truth; each function follows its case's
前提/操作/预期. Files for the import cases are pushed from uat/fixtures to 下载 and picked in the
system file picker."""
import os
import time

from uat import Blocked, Fail, PKG, case, check, first, flick, home, reset, sym_char

FIXTURES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fixtures")
DOWNLOAD = "/sdcard/Download"
DIGIT_KEYS = dict(zip("1234567890", "qwertyuiop"))

# how to type each word with pinyin: one piece per composition
PIECES = {"蓝鲸花园": [("lanjinghuayuan", "蓝鲸花园")], "幽默": [("youmo", "幽默")],
          # not the engine's own first for lan jing hua/gong yuan (蓝鲸花园, 蓝鲸公园)
          "澜境花园": [("lan", "澜"), ("jing", "境"), ("huayuan", "花园")],
          "澜境公园": [("lan", "澜"), ("jing", "境"), ("gongyuan", "公园")],
          "蓝鲸公园": [("lanjing", "蓝鲸"), ("gongyuan", "公园")], "时间": [("shijian", "时间")],
          "天气": [("tianqi", "天气")],
          "北京市海淀区": [("beijingshi", "北京市"), ("haidianqu", "海淀区")], "张三": [("zhangsan", "张三")],
          "上海市": [("shanghaishi", "上海市")], "年": [("nian", "年")], "月": [("yue", "月")],
          "日": [("ri", "日")]}


def words(cs):
    return [c.split(" ")[0] for c in cs]


def push(d, *names):
    for n in names:
        d.adb("push", os.path.join(FIXTURES, n), f"{DOWNLOAD}/{n}")


def edits(d, s=None):
    s = s or d.screen()
    return [n for n in s.nodes if n.cls == "EditText"]


def focus(d, e):
    d.tap((e.b[2] - 25, e.c[1]), 1.0)   # the right end: the cursor after any text
    d._keys = None


def clear_field(d, i):
    """empty dialog field i by backspacing its text (Device.clear reads the test page's field)"""
    n = len(edits(d)[i].text)
    if n:
        d.press("bksp", n + 1, after=0.3)
    if edits(d)[i].text:
        raise Blocked(f"清不掉输入框：「{edits(d)[i].text}」")


def type_cn(d, word):
    for code, w in PIECES[word]:
        d.type(code)
        d.cands()
        d.pick(w)


def type_letters(d, text):
    """raw letters (and spaces) with the pinyin keyboard: enter commits the letters as typed"""
    for i, part in enumerate(text.split(" ")):
        if i:
            d.space()
        if part:
            d.type(part)
            d.enter()


def type_digits(d, digits):
    """corner digits (down-swipes on q..p); a swipe that came out as a letter is taken back"""
    for ch in digits:
        if ch == " ":
            d.space()
            continue
        for _ in range(3):
            before = d.field_text() or ""
            flick(d, DIGIT_KEYS[ch])
            time.sleep(0.5)
            if not d.preedit() and (d.field_text() or "") == before + ch:
                break
            while d.preedit():
                d.bksp()
            if (d.field_text() or "") != before:
                d.bksp()
        else:
            raise Blocked(f"打不出数字 {ch}")


def ok(d):
    """the dialog's 确定, until the dialog is gone (the keyboard hiding moves it)"""
    for _ in range(4):
        time.sleep(0.5)
        s = d.screen()
        b = next((n for n in s.nodes if n.rid == "button1"), None)
        if not b:
            return
        d.tap(b.c, 1.2)
    raise Blocked(f"对话框关不掉：{[n.text for n in d.screen().nodes if n.text and n.pkg == PKG][:8]}")


def fab(d):
    s = d.screen()
    n = next((n for n in s.app() if n.cls.endswith("ActionButton")), None)
    if not n:
        raise Blocked(f"没有 + 按钮：{s.texts()[:6]}")
    d.tap(n.c, 1.2)


def user_lexicon(d, page=None):
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", *([page] if page else []))


def add_word(d, word, pinyin=None):
    """手动添加的词 → +: the word, and its pinyin (left empty when None)"""
    user_lexicon(d, "手动添加的词")
    fab(d)
    e = edits(d)
    focus(d, e[0])
    type_cn(d, word)
    if pinyin:
        focus(d, edits(d)[1])
        type_letters(d, pinyin)
    d.hide_keyboard()
    ok(d)


def block_word(d, word):
    user_lexicon(d, "屏蔽的词")
    fab(d)
    focus(d, edits(d)[0])
    type_cn(d, word)
    d.hide_keyboard()
    ok(d)


def item_menu(d, text, option):
    """tap the row showing text, then the menu's option"""
    s = d.screen()
    n = next((n for n in s.app() if n.text.startswith(text)), None)
    if not n:
        raise Blocked(f"列表里没有「{text}」：{s.texts()[:10]}")
    d.tap(n.row().c if n.row() else n.c, 1.0)
    s = d.screen()
    m = next((m for m in s.nodes if m.text == option), None)
    if not m:
        raise Blocked(f"点「{text}」后没有「{option}」：{[x.text for x in s.nodes if x.text][:12]}")
    d.tap(m.c, 1.2)
    return [x.text for x in s.nodes if x.text]


def list_texts(d):
    return [t for t in d.screen().texts() if t not in ("转到上一层级",)]


def count_of(d, title):
    t = d.screen().texts()
    return t[t.index(title) + 1] if title in t else None


def page_cands(d, code, page="text"):
    d.open_page(page)
    d.switch_im("拼音")
    d.type(code)
    return d.cands()


def import_file(d, name, target=None):
    """词库与短语 → 导入词库 → name; returns the dialog's choice before any change, then picks
    target (a prefix of the option) and 导入词库. The toast/dialog after it is returned too."""
    home(d, "词库与短语")
    d.tap_text("导入词库", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(4):
        n = d.screen().find(text=name)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {name}")
    d.tap(n.c, 2.5)
    s = d.screen()
    opts = [n for n in s.nodes if n.cls == "CheckedTextView"]
    chosen = next((o.text.split("\n")[0] for o in opts if o.checked), None)
    if target is None:
        return chosen, s
    o = next((o for o in opts if o.text.startswith(target)), None)
    if not o:
        raise Blocked(f"并入哪个词库里没有「{target}」：{[x.text[:12] for x in opts]}")
    d.tap(o.c, 0.5)
    btn = next(n for n in d.screen().nodes if n.text == "导入词库" and n.cls == "Button")
    d.tap(btn.c, 0.3)
    msgs = []
    for _ in range(12):
        s = d.screen()
        msgs += [n.text for n in s.nodes if n.cls == "android.widget.Toast$TN" or n.pkg == "com.android.systemui"
                 and n.text and "：" not in n.text[:3]] + [n.text for n in s.nodes if n.rid == "message"]
        time.sleep(0.4)
    return chosen, msgs


def dictionaries(d):
    """the 基础词库 section's rows, below 内置词库, up to the 导入 header"""
    home(d, "词库与短语")
    t = d.all_texts()
    if "基础词库" not in t or "导入" not in t:
        return t
    return t[t.index("基础词库") + 1:t.index("导入")]


# ---------------------------------------------------------------- user lexicon

@case("WD-00", base="B0-设置")
def wd00(d):
    home(d, "词库与短语")
    t = d.all_texts()
    lead = next((x for x in t if x.startswith("用户词库的词排得靠前")), None)
    order = [x for x in t if x in ("用户词库", "基础词库", "导入", "短语", "新词")]
    check(lead and "还没选过的，可能排在很常用的同音词后面" in lead, f"最上面 {t[1:2]}")
    check(order[:5] == ["用户词库", "用户词库", "基础词库", "导入", "短语"] or
          [x for i, x in enumerate(order) if i == 0 or x != order[i - 1]] == ["用户词库", "基础词库", "导入", "短语"],
          f"分组 {order}")
    check("新词" not in t, "有新词分组")
    for x in ("内置词库", "常用词和近几年的新词，随应用更新 · 只读", "导入词库", "自定义短语"):
        check(x in t, f"没有「{x}」")
    return f"分组 {order}"


@case("WD-01", base="B0-设置", mutates=True)
def wd01(d):
    user_lexicon(d)
    n0 = count_of(d, "手动添加的词")
    add_word(d, "澜境花园", "lan jing hua yuan")
    rows = list_texts(d)
    d.back()
    n1 = count_of(d, "手动添加的词")
    c = page_cands(d, "lanjinghuayuan")
    check(any(r.startswith("澜境花园") for r in rows), f"列表 {rows[:6]}")
    check(n0 == "0 个词" and n1 == "1 个词", f"词数 {n0} → {n1}")
    check(first(c) == "澜境花园", f"候选 {c[:3]}")
    return f"词数 {n0} → {n1}；候选 {c[:2]}"


@case("WD-02", base="B0-设置", mutates=True)
def wd02(d):
    add_word(d, "幽默")
    rows = list_texts(d)
    row = [r for r in rows if "幽默" in r or "you mo" in r]
    check(any("you mo" in r for r in rows), f"列表 {rows[:6]}")
    return f"{row}"


@case("WD-03", base="B0-设置", mutates=True)
def wd03(d):
    add_word(d, "澜境花园", "lan jing hua yuan")
    item_menu(d, "澜境花园", "编辑")
    e = edits(d)
    focus(d, e[0])
    clear_field(d, 0)
    type_cn(d, "澜境公园")
    focus(d, edits(d)[1])
    clear_field(d, 1)
    type_letters(d, "lan jing gong yuan")
    d.hide_keyboard()
    ok(d)
    rows1 = list_texts(d)
    c1 = page_cands(d, "lanjinggongyuan")
    d.clear()
    user_lexicon(d, "手动添加的词")
    item_menu(d, "澜境公园", "删除")
    time.sleep(0.5)
    s = d.screen()
    conf = next((n for n in s.nodes if n.rid == "button1"), None)
    if conf:
        d.tap(conf.c, 1.0)
    rows2 = list_texts(d)
    c = page_cands(d, "lanjinggongyuan")
    check(any(r.startswith("澜境公园") for r in rows1), f"编辑后 {rows1[:6]}")
    check(first(c1) == "澜境公园", f"编辑后候选 {c1[:3]}")
    check(not any("澜境公园" in r for r in rows2), f"删除后 {rows2[:6]}")
    check(first(c) != "澜境公园", f"删除后候选 {c[:3]}")
    return f"编辑后有、第一个是它；删除后没有，候选 {c[:2]}"


def search(d, typed):
    # the search box is the list's top field (its hint, 词或拼音, is not in the dump)
    e = edits(d)
    if not e:
        raise Blocked("列表上面没有搜索框")
    d.tap((e[0].b[0] + 80, e[0].c[1]), 1.0)   # not the right end: the box's clear button is there
    d._keys = None
    if e[0].text:
        d.press("bksp", 30, after=0.3)   # the cursor may sit mid-text: from the end too
        d.tap((e[0].b[2] - 200, e[0].c[1]), 0.5)
        d.press("bksp", 30, after=0.3)
    if typed.isascii():
        type_letters(d, typed)
    else:
        d.type("you")
        d.cands()
        d.pick(typed)
    time.sleep(0.8)
    d.hide_keyboard()
    return [t for t in list_texts(d) if t != typed]


@case("WD-04", base="B0-设置", mutates=True)
def wd04(d):
    add_word(d, "蓝鲸花园", "lan jing hua yuan")
    add_word(d, "幽默")
    r1 = search(d, "lan")
    r2 = search(d, "幽")
    has1 = [x for x in r1 if "蓝鲸花园" in x or "幽默" in x]
    has2 = [x for x in r2 if "蓝鲸花园" in x or "幽默" in x]
    check(has1 and all("蓝鲸花园" in x for x in has1), f"搜 lan：{r1[:6]}")
    check(has2 and all("幽默" in x for x in has2), f"搜 幽：{r2[:6]}")
    return f"lan {has1}；幽 {has2}"


@case("WD-05", base="B0-设置", mutates=True)
def wd05(d):
    add_word(d, "蓝鲸花园", "lan jing hua yuan")
    add_word(d, "幽默")
    s = d.screen()
    rows = [n for n in s.app() if n.text.startswith(("蓝鲸花园", "幽默"))]
    if len(rows) < 2:
        raise Blocked(f"列表 {s.texts()[:6]}")
    d.hold(rows[0].c, 900)
    time.sleep(0.6)
    d.tap(rows[1].c, 0.6)
    s = d.screen()
    dl = s.find(desc="删除") or s.find(text="删除")
    if not dl:
        raise Blocked(f"多选后没有删除：{[x.desc or x.text for x in s.app() if x.desc or x.text][:10]}")
    d.tap(dl.c, 1.0)
    conf = next((n for n in d.screen().nodes if n.rid == "button1"), None)
    if conf:
        d.tap(conf.c, 1.0)
    time.sleep(1.0)
    rows2 = list_texts(d)
    d.back()
    n = count_of(d, "手动添加的词")
    check(not any(r.startswith(("蓝鲸花园", "幽默")) for r in rows2), f"删除后 {rows2[:6]}")
    check(n == "0 个词", f"词数 {n}")
    return f"都删了，{n}"


@case("WD-06", base="B0-设置", mutates=True)
def wd06(d):
    block_word(d, "时间")
    rows = list_texts(d)
    c = words(page_cands(d, "shijian"))
    check(any("时间" in r for r in rows), f"屏蔽列表 {rows[:5]}")
    check("时间" not in c, f"候选 {c[:6]}")
    return f"候选 {c[:4]}"


@case("WD-07", mutates=True)
def wd07(d):
    d.type("shijian")
    d.cands()
    menu = d.long_pick("时间")
    n = next((n for n in d.screen().nodes if "不再显示" in n.text), None)
    if not n:
        raise Blocked(f"长按「时间」的菜单 {menu}")
    d.tap(n.c, 1.0)
    d.clear()
    user_lexicon(d, "屏蔽的词")
    rows1 = list_texts(d)
    item_menu(d, "时间", "恢复显示")
    time.sleep(0.6)
    rows2 = list_texts(d)
    c = words(page_cands(d, "shijian"))
    check(any("时间" in r for r in rows1), f"屏蔽列表 {rows1[:5]}")
    check(not any("时间" in r for r in rows2), f"恢复后 {rows2[:5]}")
    check("时间" in c, f"候选 {c[:6]}")
    return f"恢复后候选 {c[:3]}"


@case("WD-08", mutates=True)
def wd08(d):
    d.type("zhegeshiyanhennan")
    c0 = d.cands()
    try:
        for w in ("这个", "誓言", "很难"):
            d.cands()
            d.pick(w)
    except Exception as e:
        raise Blocked(f"前提（PY-34 的步骤）没到：{e}")
    time.sleep(0.5)
    if d.field_text() != "这个誓言很难":
        raise Blocked(f"前提没到：框里「{d.field_text()}」（开始 {c0[:3]}）")
    d.clear()
    user_lexicon(d, "自动学到的词")
    item_menu(d, "这个誓言很难", "忘掉")
    rows = list_texts(d)
    c = page_cands(d, "zhegeshiyanhennan")
    check(not any("这个誓言很难" in r for r in rows), f"列表 {rows[:5]}")
    check(first(c) != "这个誓言很难", f"候选 {c[:3]}")
    return f"候选 {c[:2]}"


@case("WD-09", base="B0-设置", mutates=True)
def wd09(d):
    push(d, "userwords.txt")
    chosen, msgs = import_file(d, "userwords.txt", "用户词库")
    user_lexicon(d, "手动添加的词")
    added = list_texts(d)
    d.back()
    d.tap_text("屏蔽的词", after=1.0)
    blocked = list_texts(d)
    check(any("幽默测试" in r for r in added) and any("蓝鲸花园" in r for r in added), f"添加的词 {added[:6]}")
    check(any("马上就好" in r for r in blocked), f"屏蔽的词 {blocked[:5]}")
    check(not any(BIG_HINT in m for m in msgs), f"3 个词也提示改放基础词库：{msgs[:3]}")
    return f"添加 {len([r for r in added if '幽默测试' in r or '蓝鲸花园' in r])} 个，屏蔽有马上就好"


BIG_HINT = "用户词库放你自己常用的词"


@case("WD-09a", base="B0-设置", mutates=True)
def wd09a(d):
    push(d, "big-words.txt")
    _, msgs = import_file(d, "big-words.txt", "用户词库")
    hint = next((m for m in msgs if BIG_HINT in m), None)
    check(hint and "1001" in hint, f"1001 个词并入用户词库没提示：{msgs[:3]}")
    s = d.screen()
    texts = [n.text for n in s.nodes if n.text]
    check("词很多，放进用户词库？" in texts and "仍放用户词库" in texts and "取消" in texts, f"提示框 {texts[:8]}")
    b = s.find(text="改放基础词库")
    if not b:
        raise Blocked(f"提示里没有「改放基础词库」：{texts[:8]}")
    d.tap(b.c, 4.0)
    rows = dictionaries(d)
    check(any("big-words" in r for r in rows), f"改放后基础词库一节 {rows}")
    user_lexicon(d, "手动添加的词")
    added = list_texts(d)
    check(not any("鲸喵屿" in r for r in added), f"手动添加的词里有「鲸喵屿」：{added[:4]}")
    return f"提示「{hint[:20]}…」；基础词库 {[r for r in rows if 'big-words' in r]}"


@case("WD-09b", base="B0-设置", mutates=True)
def wd09b(d):
    push(d, "big-words.txt", "big-words.list")
    _, msgs = import_file(d, "big-words.list", "用户词库")
    texts = [n.text for n in d.screen().nodes if n.text]
    check(any(BIG_HINT in m for m in msgs), f".list 1001 个词没提示：{msgs[:3]}")
    check("改放基础词库" not in texts and "仍放用户词库" in texts, f".list 的提示按钮 {texts[:8]}")
    cancel = d.screen().find(text="取消")
    if not cancel:
        raise Blocked(f".list 的提示里没有「取消」：{texts[:8]}")
    d.tap(cancel.c, 1.0)
    _, msgs = import_file(d, "big-words.txt", "用户词库")
    b = d.screen().find(text="仍放用户词库")
    if not b:
        raise Blocked(f"提示里没有「仍放用户词库」：{msgs[:3]}")
    d.tap(b.c, 6.0)
    d.back()
    rows = dictionaries(d)
    check(not any("big-words" in r for r in rows), f"仍放用户词库后基础词库一节 {rows}")
    user_lexicon(d, "手动添加的词")
    added = list_texts(d)
    check(any("鲸喵屿" in r for r in added) and any("共 1001 个" in r for r in added), f"手动添加的词 {added[:4]}")
    return f".list 无改放按钮；.txt 仍放后添加的词 {added[:2]}"


@case("WD-10", base="B0-设置", mutates=True)
def wd10(d):
    # a learned word too (WD-08's): it comes back learned, not among the added
    d.open_page("text")
    d.switch_im("拼音")
    d.type("zhegeshiyanhennan")
    try:
        for w in ("这个", "誓言", "很难"):
            d.cands()
            d.pick(w)
    except Exception as e:
        raise Blocked(f"前提（PY-34 的步骤）没到：{e}")
    time.sleep(0.5)
    if d.field_text() != "这个誓言很难":
        raise Blocked(f"前提没到：框里「{d.field_text()}」")
    d.clear()
    add_word(d, "蓝鲸花园", "lan jing hua yuan")
    block_word(d, "时间")
    user_lexicon(d)
    t = d.all_texts()
    files = t[t.index("文件") + 1:] if "文件" in t else []
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("导出", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("导出后下载里没有新文件")
    body = d.sh(f"cat '{DOWNLOAD}/{new[0]}'")
    reset(d, "B0-设置", "设置")
    chosen, s = import_file(d, new[0])
    opt = next(o for o in s.nodes if o.cls == "CheckedTextView" and o.text.startswith("用户词库"))
    d.tap(opt.c, 0.5)
    d.tap(next(n for n in d.screen().nodes if n.text == "导入词库" and n.cls == "Button").c, 3.0)
    user_lexicon(d, "手动添加的词")
    added = list_texts(d)
    d.back()
    d.tap_text("屏蔽的词", after=1.0)
    blocked = list_texts(d)
    d.back()
    d.tap_text("自动学到的词", after=1.0)
    learned = list_texts(d)
    hint = [x for x in files if "导入词库" in x]
    check(files[:1] == ["导出"] and hint, f"文件一节 {files[:3]}")
    check("蓝鲸花园" in body and "!时间" in body, f"导出的文件 {body[:80]!r}")
    check(chosen == "用户词库", f"对话框选的是「{chosen}」")
    check(any("蓝鲸花园" in r for r in added) and any("时间" in r for r in blocked), f"添加 {added[:4]}，屏蔽 {blocked[:4]}")
    check("~ 这个誓言很难" in body, f"导出的文件里学到的词没有 ~ 标记 {body[:120]!r}")
    check(any("这个誓言很难" in r for r in learned) and not any("这个誓言很难" in r for r in added), f"学到的 {learned[:4]}，添加 {added[:4]}")
    # the learned word types as before (PY-34's second half)
    c = words(page_cands(d, "zhegeshiyanhennan"))
    check(c[:1] == ["这个誓言很难"], f"导回后 zhegeshiyanhennan 的候选 {c[:3]}")
    return f"导出 {new[0]}；导回后添加的、学到的、屏蔽的词各在原处；整句首选「{c[0] if c else ''}」"


@case("WD-10a", base="B0-设置", mutates=True)
def wd10a(d):
    push(d, "big-words.txt")
    import_file(d, "big-words.txt", "用户词库")
    b = d.screen().find(text="仍放用户词库")
    if not b:
        raise Blocked("没出「仍放用户词库」")
    d.tap(b.c, 6.0)
    d.back()
    user_lexicon(d)
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("导出", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("导出后下载里没有新文件")
    reset(d, "B0-设置", "设置")
    chosen, msgs = import_file(d, new[0], "用户词库")
    check(not any(BIG_HINT in m for m in msgs), f"导回自己导出的 1001 个词也提示了：{msgs[:2]}")
    check(any("导入完成" in m for m in msgs), f"导回后没出导入结果：{msgs[:3]}")
    texts = d.screen().texts()
    check("词很多，放进用户词库？" not in texts and "仍放用户词库" not in texts, f"导回时的对话框 {texts[:6]}")
    d.back()
    user_lexicon(d, "手动添加的词")
    t = list_texts(d)
    check(any("共 1001 个" in x for x in t), f"手动添加的词 {t[:4]}")
    return f"导回 {new[0]}（默认选「{chosen}」）没提示；{next((x for x in t if '共' in x), '')}"


@case("WD-10b", base="B0-设置", mutates=True)
def wd10b(d):
    d.open_page("text")
    d.switch_im("拼音")
    d.type("zhegeshiyanhennan")
    try:
        for w in ("这个", "誓言", "很难"):
            d.cands()
            d.pick(w)
    except Exception as e:
        raise Blocked(f"前提（PY-34 的步骤）没到：{e}")
    time.sleep(0.5)
    if d.field_text() != "这个誓言很难":
        raise Blocked(f"前提没到：框里「{d.field_text()}」")
    d.clear()
    d.hide_keyboard()
    user_lexicon(d)
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("导出", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("导出后下载里没有新文件")
    reset(d, "B0-设置", "设置")
    import_file(d, new[0], "基础词库")
    rows = dictionaries(d)
    c = words(page_cands(d, "zhegeshiyanhennan"))
    check(c[:1] == ["这个誓言很难"], f"导出并入基础词库后 zhegeshiyanhennan 的候选 {c[:3]}（基础词库 {rows}）")
    return f"基础词库 {rows[-1:]}；整句首选「{c[0]}」"


@case("WD-10c", base="B0-设置", mutates=True)
def wd10c(d):
    push(d, "old-export.txt")
    chosen, msgs = import_file(d, "old-export.txt", "用户词库")
    check(chosen == "用户词库", f"并入哪个词库默认选的是「{chosen}」")
    check(not any(BIG_HINT in m for m in msgs), f"旧版本的导出也提示了：{msgs[:2]}")
    done = next((m for m in msgs if "导入完成" in m), None)
    check(done and "添加 3000 个词" in done and "屏蔽 1 个" in done, f"导入结果 {msgs[:3]}")
    d.back()
    user_lexicon(d, "手动添加的词")
    t = list_texts(d)
    check(any("共 3000 个" in x for x in t), f"手动添加的词 {t[:4]}")
    return f"「{done}」；{next((x for x in t if '共' in x), '')}"


# ---------------------------------------------------------------- imported dictionaries

def import_dict_words(d):
    push(d, "dict-words.txt")
    import_file(d, "dict-words.txt", "基础词库")
    rows = dictionaries(d)
    if not any("dict-words" in r for r in rows):
        raise Blocked(f"前提没到：基础词库一节 {rows}")
    return rows


@case("WD-11", base="B0-设置", mutates=True)
def wd11(d):
    rows = import_dict_words(d)
    page_cands(d, "kemiaotangpu")
    c = words(d.all_cands())
    check(any("dict-words" in r for r in rows) and rows.index(next(r for r in rows if "dict-words" in r)) > 0,
          f"基础词库一节 {rows}")
    check("氪喵糖铺" in c, f"候选 {c[:6]}")
    return f"基础词库 {rows[:3]}；候选 {c[:3]}"


def dict_row_menu(d, option):
    home(d, "词库与短语")
    s = d.screen()
    n = next((n for n in s.app() if "dict-words" in n.text), None)
    if not n:
        raise Blocked("词库页没有 dict-words")
    d.tap(n.row().c if n.row() else n.c, 1.0)
    s = d.screen()
    m = next((m for m in s.nodes if m.text == option), None)
    if not m:
        raise Blocked(f"菜单里没有「{option}」：{[x.text for x in s.nodes if x.text][:10]}")
    d.tap(m.c, 1.2)
    conf = next((n for n in d.screen().nodes if n.rid == "button1"), None)
    if conf and option == "删除":
        d.tap(conf.c, 1.2)
    return [x.text for x in s.nodes if x.text and x.pkg == PKG]


@case("WD-12", base="B0-设置", mutates=True)
def wd12(d):
    import_dict_words(d)
    dict_row_menu(d, "关闭")
    c = words(page_cands(d, "kemiaotangpu"))
    check("氪喵糖铺" not in c, f"第一页 {c}")
    return f"第一页 {c[:3]}"


@case("WD-13", base="B0-设置", mutates=True)
def wd13(d):
    import_dict_words(d)
    menu = dict_row_menu(d, "删除")
    rows = dictionaries(d)
    c = words(page_cands(d, "kemiaotangpu"))
    opts = [x for x in menu if x in ("关闭", "删除", "打开", "编辑", "导出", "重命名")]
    check(sorted(opts) == ["关闭", "删除"], f"菜单 {opts}")
    check(not any("dict-words" in r for r in rows), f"删除后 {rows}")
    check("氪喵糖铺" not in c, f"第一页 {c}")
    return f"菜单 {opts}；删除后没有；第一页 {c[:3]}"


@case("WD-13a", base="B0-设置", mutates=True)
def wd13a(d):
    import_dict_words(d)
    dirp = f"/sdcard/Android/data/{PKG}/files/data/pinyin/dictionaries"
    d.sh(f"cd {dirp} && ls *.txt | sed 's/.txt$//' > .new-words")
    marker = d.sh(f"cat {dirp}/.new-words").strip()
    if not marker:
        raise Blocked(f"写不出 .new-words：{d.sh(f'ls -a {dirp}')[:100]}")
    d.sh(f"am force-stop {PKG}")
    time.sleep(1.0)
    d.sh(f"ime enable {PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService; "
         f"ime set {PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
    d.open_settings()
    home(d, "词库与短语")
    t = d.all_texts()
    rows = t[t.index("基础词库") + 1:t.index("导入")] if "基础词库" in t and "导入" in t else t
    heads = [x for x in t if x in ("新词", "导入到新词")]
    c = words(page_cands(d, "kemiaotangpu"))
    left = d.sh(f"ls -a {dirp}").split()
    check(any("dict-words" in r for r in rows), f"基础词库一节 {rows}")
    check(not heads, f"有别的分组 {heads}")
    check("氪喵糖铺" in c, f"候选 {c[:6]}")
    check(".new-words" not in left, f".new-words 还在：{left}")
    return f"在基础词库；候选 {c[:3]}；.new-words 删了"


@case("WD-14", base="B0-设置", mutates=True)
def wd14(d):
    d.sh(f"rm -f {DOWNLOAD}/empty.txt; touch {DOWNLOAD}/empty.txt")
    before = dictionaries(d)
    chosen, msgs = import_file(d, "empty.txt", "基础词库")
    after = dictionaries(d)
    why = [m for m in msgs if m]
    check(why, "没有提示")
    check(after == before, f"词库页 {before} → {after}")
    return f"提示 {why[:1]}"


@case("WD-14a", base="B0-设置", mutates=True)
def wd14a(d):
    push(d, "words.csv")
    before = dictionaries(d)
    chosen, msgs = import_file(d, "words.csv", "基础词库")
    after = dictionaries(d)
    check(chosen == "用户词库", f"一开始选的是「{chosen}」")
    check(any("词典类型无效" in m for m in msgs), f"提示 {msgs[:3]}")
    check(after == before, f"词库页 {before} → {after}")
    return f"一开始「{chosen}」；提示 {[m for m in msgs if '无效' in m][:1]}"


@case("WD-14b", base="B0-设置", mutates=True)
def wd14b(d):
    push(d, "extra.dict")
    before = dictionaries(d)
    chosen, msgs = import_file(d, "extra.dict", "用户词库")
    user_lexicon(d, "手动添加的词")
    added = list_texts(d)
    after = dictionaries(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("congjing")
    c1 = words(d.cands())
    d.pick("琮璟")
    d.clear()
    d.type("congjing")
    c2 = words(d.cands())
    check(chosen and chosen.startswith("基础词库"), f"一开始选的是「{chosen}」")
    check(any("2" in m for m in msgs), f"提示 {msgs[:3]}")
    check(any("骁骎" in r for r in added) and any("琮璟" in r for r in added), f"添加的词 {added[:5]}")
    check(after == before, f"基础词库 {before} → {after}")
    check("琮璟" in c1 and c2[:1] == ["琮璟"], f"第一次 {c1[:4]}，第二次 {c2[:3]}")
    return f"一开始「{chosen}」；{[m for m in msgs if '2' in m][:1]}；第二次 {c2[:2]}"


# ---------------------------------------------------------------- phrases

def add_phrase(d, key, parts, order="1"):
    """自定义短语 → +: 输入码 key, 候选位置 order, 短语 typed from parts: ("cn", word) |
    ("raw", letters) | ("digits", text) | ("sym", ch) | ("nl",)"""
    d.hide_keyboard()
    home(d, "词库与短语", "自定义短语")
    fab(d)
    e = edits(d)
    focus(d, e[0])
    type_letters(d, key)
    focus(d, edits(d)[1])
    clear_field(d, 1)
    for ch in order:
        d.tap(d.key_node(ch).c, 0.3)
    focus(d, edits(d)[2])
    for p in parts:
        if p[0] == "cn":
            type_cn(d, p[1])
        elif p[0] == "raw":
            type_letters(d, p[1])
        elif p[0] == "digits":
            type_digits(d, p[1])
        elif p[0] == "sym":
            sym_char(d, p[1])
        elif p[0] == "nl":
            d.enter()
    got = [n.text for n in edits(d)]
    d.hide_keyboard()
    ok(d)
    return got


def dz(d):
    got = add_phrase(d, "dz", [("cn", "北京市海淀区")])
    if got[2:] != ["北京市海淀区"]:
        raise Blocked(f"短语框里打出 {got}")


@case("WD-15", base="B0-设置", mutates=True)
def wd15(d):
    dz(d)
    c = page_cands(d, "dz")
    check(first(c) == "北京市海淀区", f"候选 {c[:3]}")
    return f"候选 {c[:2]}"


@case("WD-16", base="B0-设置", mutates=True)
def wd16(d):
    got = add_phrase(d, "qm", [("cn", "张三"), ("nl",), ("digits", "138 0000 0000")])
    if got[2:] != ["张三\n138 0000 0000"]:
        raise Blocked(f"短语框里打出 {got}")
    page_cands(d, "qm", page="sentence")
    d.space()
    t = d.field_text()
    check(t == "张三\n138 0000 0000", f"框里 {t!r}")
    return f"{t!r}"


@case("WD-17", base="B0-设置", mutates=True)
def wd17(d):
    parts = [("sym", "#"), ("sym", "$"), ("raw", "year"), ("cn", "年"), ("sym", "$"), ("raw", "month"),
             ("cn", "月"), ("sym", "$"), ("raw", "day"), ("cn", "日")]
    got = add_phrase(d, "rq", parts)
    if got[2:] != ["#$year年$month月$day日"]:
        raise Blocked(f"短语框里打出 {got}")
    page_cands(d, "rq")
    d.space()
    t = d.field_text()
    lt = time.localtime()
    want = f"{lt.tm_year}年{lt.tm_mon}月{lt.tm_mday}日"
    dev = d.sh("date +%Y-%-m-%-d").strip().split("-")
    want_dev = f"{dev[0]}年{dev[1]}月{dev[2]}日" if len(dev) == 3 else want
    check(t in (want, want_dev), f"框里「{t}」，今天 {want_dev}")
    return f"「{t}」"


@case("WD-18", base="B0-设置", mutates=True)
def wd18(d):
    dz(d)
    time.sleep(3.0)   # the 已添加 snackbar over the list goes first

    def open_row(text):
        s = d.screen()
        n = next((n for n in s.app() if text in n.text), None)
        if not n:
            raise Blocked(f"短语列表里没有「{text}」：{s.texts()[:6]}")
        d.tap(n.c, 1.2)   # the row itself (docs/words.md: 点一条可以编辑或删除)
        e = edits(d)
        if len(e) < 3:
            raise Fail(f"点「{text}」那一行没出编辑框：{[x.text for x in d.screen().nodes if x.text][:8]}")
        return e
    e = open_row("北京市海淀区")
    focus(d, e[2])
    clear_field(d, 2)
    type_cn(d, "上海市")
    d.hide_keyboard()
    ok(d)
    c1 = page_cands(d, "dz")
    d.hide_keyboard()
    home(d, "词库与短语", "自定义短语")
    open_row("上海市")
    d.hide_keyboard()
    s = d.screen()
    dl = next((n for n in s.nodes if n.text == "删除" and n.pkg == PKG), None)
    if not dl:
        raise Fail(f"编辑框里没有删除：{[x.text for x in s.nodes if x.text and x.pkg == PKG][:8]}")
    d.tap(dl.c, 1.0)
    conf = next((n for n in d.screen().nodes if n.rid == "button1"), None)
    if conf:
        d.tap(conf.c, 1.0)
    time.sleep(3.0)
    c2 = words(page_cands(d, "dz"))
    check(first(c1) == "上海市", f"改后 {c1[:3]}")
    check("上海市" not in c2 and "北京市海淀区" not in c2, f"删除后 {c2[:4]}")
    return f"点行编辑、删除；改后 {c1[:2]}；删除后 {c2[:3]}"


@case("WD-19", base="B0-设置", mutates=True)
def wd19(d):
    from uat import add_im
    dz(d)
    add_im(d, "九键拼音")
    d.open_page("text")
    d.switch_im("九键")
    for k in ("3", "9"):
        d.press(k)
    c = words(d.all_cands())
    check("北京市海淀区" not in c, f"候选 {c[:6]}")
    return f"候选 {c[:4]}"


@case("WD-20", base="B0-设置", mutates=True)
def wd20(d):
    from uat import add_im
    dz(d)
    add_im(d, "双拼")
    d.open_page("text")
    d.switch_im("双拼")
    d.type("dz")
    c = words(d.all_cands())
    check("北京市海淀区" in c, f"候选 {c[:6]}")
    return f"候选 {c[:4]}"
