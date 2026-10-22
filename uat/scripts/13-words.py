"""uat/13-words.md (词库与短语). The .md is the source of truth; each function follows its case's
前提/操作/预期. Files for the import cases are pushed from uat/fixtures to 下载 and picked in the
system file picker."""
import os
import re
import time

from uat import Blocked, Fail, PKG, add_im, case, check, first, flick, home, menu_tap, reset, script, sym_char

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
          "日": [("ri", "日")], "鲸喵屿": [("jing", "鲸"), ("miao", "喵"), ("yu", "屿")],
          "你好": [("nihao", "你好")], "您好呀": [("ninhao", "您好"), ("ya", "呀")]}


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
    # the exports of earlier runs pile up in 下载 (a grid of two columns): enough scrolls to pass them
    for _ in range(12):
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


def learn_name(d):
    """PY-34's steps (06-pinyin's, the one copy): 汪小明 picked piece by piece twice, so it becomes your word"""
    try:
        script("06-pinyin").learn_sentence(d)
    except Fail as e:
        raise Blocked(f"前提（PY-34 的步骤）没到：{e}")

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
    if "屏蔽" not in menu:
        raise Blocked(f"长按「时间」的菜单 {menu[:8]}")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
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
    learn_name(d)
    user_lexicon(d, "自动学到的词")
    item_menu(d, "汪小明", "删除")
    rows = list_texts(d)
    c = page_cands(d, "wangxiaoming")
    d.clear()
    d.type("wxm")
    j = d.cands()
    check(not any("汪小明" in r for r in rows), f"列表 {rows[:5]}")
    check(first(c) == "王小明" and "汪小明" not in words(c), f"候选 {c[:5]}")
    check("汪小明" not in words(j), f"wxm 候选 {j[:5]}")
    return f"候选 {c[:2]}；wxm {j[:2]}"


@case("WD-09", base="B0-设置", mutates=True)
def wd09(d):
    push(d, "userwords.txt")
    chosen, msgs = import_file(d, "userwords.txt", "用户词库")
    done = next((m for m in msgs if "导入完成" in m), None)
    user_lexicon(d)
    d.tap_text("屏蔽的词", after=1.0)
    blocked = list_texts(d)
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    # 幽默测试 and 蓝鲸花园 imported: 蓝鲸花园 is no dictionary word, only typed as a sentence (U-101)
    check(done and "导入 2 个词" in done and "屏蔽 1 个" in done, f"导入结果 {msgs[:3]}")
    check(any("马上就好" in r for r in blocked), f"屏蔽的词 {blocked[:5]}")
    check(any("userwords.txt" in t for t in batches), f"导入的词 {batches[:6]}")
    return f"「{done}」，屏蔽有马上就好，导入的词有这一批"


def imported_count(msgs):
    """the words 导入完成 says were imported, or None"""
    m = next((re.search(r"导入 (\d+) 个词", m) for m in msgs if "导入完成" in m), None)
    return int(m.group(1)) if m else None


@case("WD-09b", base="B0-设置", mutates=True)
def wd09b(d):
    push(d, "big-words.txt")
    _, msgs = import_file(d, "big-words.txt", "用户词库")
    n = imported_count(msgs)
    # no hint to use the base any more: imported words stay out of sentences and voice
    check(n is not None and n >= 1000, f"导入结果 {msgs[:3]}")
    d.back()
    rows = dictionaries(d)
    check(not any("big-words" in r for r in rows), f"基础词库一节 {rows}")
    d.tap_text("导入的词", after=1.5)
    texts = d.screen().texts()
    check(any("big-words.txt" in t for t in texts), f"导入的词 {texts[:6]}")
    return f"导入 {n} 个；导入的词里有 big-words.txt"


@case("WD-10", base="B0-设置", mutates=True)
def wd10(d):
    # a learned word too (WD-08's): it comes back learned, not among the added
    d.open_page("text")
    d.switch_im("拼音")
    learn_name(d)
    add_word(d, "蓝鲸花园", "lan jing hua yuan")
    block_word(d, "时间")
    user_lexicon(d)
    t = d.all_texts()
    files = t[t.index("文件") + 1:] if "文件" in t else []
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("导出", after=2.5)
    # the export warns first (learning_save_copy_warning): anyone with the phone can copy the file
    if d.screen().find(text="确定", where="all"):
        d.tap_text("确定", where="all", scroll=False, after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("导出后下载里没有新文件")
    reset(d, "B0-设置", "设置")
    # a backup (WD-10): no 并入哪个词库, the restore's confirmation with its source and date
    chosen, s = import_file(d, new[0])
    confirm = [n.text for n in s.nodes if n.rid == "message"]
    d.tap_text("确定", after=3.0)
    user_lexicon(d, "手动添加的词")
    added = list_texts(d)
    d.back()
    d.tap_text("屏蔽的词", after=1.0)
    blocked = list_texts(d)
    d.back()
    d.tap_text("自动学到的词", after=1.0)
    learned = list_texts(d)
    hint = [x for x in files if "导入词库" in x]
    check("导出" in files, f"文件一节 {files[:3]}")
    check(new[0].endswith(".fxlb"), f"导出的文件名 {new[0]}")
    check(chosen is None and confirm, f"选文件后的对话框：选项「{chosen}」，说明 {confirm[:1]}")
    check(any("蓝鲸花园" in r for r in added) and any("时间" in r for r in blocked), f"添加 {added[:4]}，屏蔽 {blocked[:4]}")
    check(any("汪小明" in r for r in learned) and not any("汪小明" in r for r in added), f"学到的 {learned[:4]}，添加 {added[:4]}")
    # the learned word types as before (PY-34's second half)
    c = words(page_cands(d, "wangxiaoming"))
    check(c[:1] == ["汪小明"], f"导回后 wangxiaoming 的候选 {c[:3]}")
    return f"导出 {new[0]}；导回后添加的、学到的、屏蔽的词各在原处；整句首选「{c[0] if c else ''}」"


@case("WD-10a", base="B0-设置", mutates=True)
def wd10a(d):
    push(d, "big-words.txt")
    _, msgs = import_file(d, "big-words.txt", "用户词库")
    if not imported_count(msgs):
        raise Blocked(f"前提没到：{msgs[:3]}")
    d.back()
    user_lexicon(d, "学习备份")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("另存一份到看得见的文件夹", after=1.0)
    d.tap_text("确定", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"另存没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("另存后下载里没有新文件")
    reset(d, "B0-设置", "设置")
    user_lexicon(d, "学习备份")
    d.tap_text("从备份恢复", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(12):
        n = d.screen().find(text=new[0])
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {new[0]}")
    d.tap(n.c, 2.5)
    d.tap_text("确定", after=3.0)
    home(d, "词库与短语", "导入的词")
    texts = d.screen().texts()
    row = next((t for t in texts if "big-words.txt" in t), "")
    check(row, f"恢复后导入的词里没有 big-words.txt：{texts[:6]}")
    check("1001" in " ".join(texts), f"那一批不是 1001 个词：{texts[:6]}")
    return f"另存 {new[0]}，重置后恢复；导入的词 {texts[:3]}"


@case("WD-10c", base="B0-设置", mutates=True)
def wd10c(d):
    push(d, "old-export.txt")
    chosen, msgs = import_file(d, "old-export.txt", "用户词库")
    check(chosen == "用户词库", f"并入哪个词库默认选的是「{chosen}」")
    done = next((m for m in msgs if "导入完成" in m), None)
    check(done and "添加 3000 个" in done and "屏蔽 1 个" in done, f"导入结果 {msgs[:3]}")
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
    d.back()
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    d.back()
    after = dictionaries(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("congjing")
    c1 = words(d.cands())
    d.pick("琮璟")
    d.clear()
    d.type("congjing")
    c2 = words(d.cands())
    d.pick("琮璟")
    d.clear()
    d.type("congjing")
    c3 = words(d.cands())
    check(chosen and chosen.startswith("基础词库"), f"一开始选的是「{chosen}」")
    check(imported_count(msgs) == 2, f"提示 {msgs[:3]}")
    check(any("extra.dict" in t for t in batches), f"导入的词 {batches[:5]}")
    check(after == before, f"基础词库 {before} → {after}")
    # behind the dictionary's own the first time, second after one pick (two make it first)
    check(first(c1) != "琮璟" and c2[1:2] == ["琮璟"], f"第一次 {c1[:4]}，第二次 {c2[:3]}")
    check(first(c3) == "琮璟", f"点过两次后 {c3[:3]}")
    return f"一开始「{chosen}」；导入 {imported_count(msgs)} 个；第二次 {c2[:2]}；第三次 {c3[:2]}"


@case("WD-14c", base="B0-设置", mutates=True)
def wd14c(d):
    push(d, "extra.scel")
    _, msgs = import_file(d, "extra.scel", "用户词库")
    d.back()
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    d.back()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("congjing")
    c = words(d.all_cands())
    d.clear()
    check(imported_count(msgs) == 2, f"提示 {msgs[:3]}")
    check(any("extra.scel" in t for t in batches), f"导入的词 {batches[:5]}")
    check("琮璟" in c and c.index("琮璟") > 0, f"congjing 的候选 {c[:6]}")
    return f"导入 {imported_count(msgs)} 个；congjing {c[:4]}"


def drop_batch(d, name):
    """导入的词 → the row of [name] → 撤掉这批: the dialog's text, then what the screen shows after (the Snackbar)."""
    home(d, "词库与短语", "导入的词")
    d.tap_text(name, contains=True, after=1.0)
    asked = " ".join(d.screen().texts())
    d.tap_text("撤掉这批", where="all", scroll=False, after=0.6)
    return asked, d.screen().texts()


@case("WD-23", base="B0-设置", mutates=True)
def wd23(d):
    push(d, "extra.dict")
    _, msgs = import_file(d, "extra.dict", "用户词库")
    if imported_count(msgs) != 2:
        raise Blocked(f"前提没到：{msgs[:3]}")
    d.back()
    d.open_page("text")
    d.switch_im("拼音")
    for _ in range(2):
        d.type("congjing")
        d.cands(want="琮璟")
        d.pick("琮璟")
        d.clear()
    asked, after = drop_batch(d, "extra.dict")
    d.back()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("xiaoqin")
    x = words(d.all_cands())
    d.clear()
    d.type("congjing")
    c = words(d.all_cands())
    d.clear()
    check("还不是你的 1 个词" in asked and "extra.dict" in asked, f"确认框 {asked[:80]}")
    check(any("撤掉了 1 个" in t for t in after), f"撤掉后 {after[:6]}")
    check("骁骎" not in x and "琮璟" in c, f"xiaoqin {x[:5]}，congjing {c[:4]}")
    # again, and 撤销 on the bar this time: the same list imports again (it was undone), and comes back
    _, msgs = import_file(d, "extra.dict", "用户词库")
    d.back()
    _, after = drop_batch(d, "extra.dict")
    d.tap_text("撤销", where="all", scroll=False, after=1.0)
    d.back()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("xiaoqin")
    x2 = words(d.all_cands())
    d.clear()
    check(imported_count(msgs) == 1, f"再导入一次 {msgs[:3]}")
    check("骁骎" in x2, f"撤销后 xiaoqin {x2[:5]}")
    return f"撤掉后 xiaoqin {x[:3]}、congjing {c[:2]}；撤销后 xiaoqin {x2[:3]}"


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


# ---------------------------------------------------------------- the user lexicon's pages

def marks(d):
    """WD-21's 前提: PY-34 (汪小明 your word), PY-30 (the fifth of shi pinned), PY-29 (是 blocked);
    returns the pinned word and shi's candidates before"""
    py = script("06-pinyin")
    learn_name(d)
    c0, cc = py.pin_fifth(d)
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()
    return cc, c0


def switch_on(d, title):
    r = d.row(title)
    w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial", "CheckBox")] if r else []
    return w[0].checked if w else None


@case("WD-21", mutates=True)
def wd21(d):
    cc, _ = marks(d)
    # a second word, made yours after 汪小明: the newest first is by the day it became yours (F2992)
    for _ in range(2):
        d.type("jingmiaoyu")
        for w in "鲸喵屿":
            d.cands(want=w)
            d.pick(w)
        time.sleep(0.5)
        d.clear()
    user_lexicon(d)
    tops = {t: switch_on(d, t) for t in ("用我的输入习惯", "自动备份")}
    t = d.all_texts()
    pages = ["手动添加的词", "自动学到的词", "屏蔽的词", "不再联想的搭配", "固定的词"]
    miss = [x for x in pages if x not in t]
    d.tap_text("自动学到的词", after=1.2)
    rows = list_texts(d)
    today = d.sh("date +%Y年%-m月%-d日").strip()
    row = next((r for r in rows if r.startswith("wang xiao ming")), "")
    d.tap_text("更多选项", where="all", scroll=False, after=0.8)
    d.tap_text("最近学到的在前", where="all", scroll=False, after=1.2)
    words_now = [r for r in list_texts(d) if r in ("汪小明", "鲸喵屿") or r.startswith("共 ")]
    d.back()
    d.tap_text("固定的词", after=1.2)
    pinned = list_texts(d)
    d.back()
    d.tap_text("屏蔽的词", after=1.2)
    blocked = list_texts(d)
    check(tops == {"用我的输入习惯": True, "自动备份": True}, f"页顶开关 {tops}")
    check(not miss, f"用户词库缺 {miss}")
    check("打过 2 次" in row and today in row and not re.search(r"\d+:\d+", row), f"汪小明那行「{row}」（今天 {today}）")
    check(cc in pinned and "是" in blocked, f"固定的词 {pinned[:4]}；屏蔽的词 {blocked[:5]}")
    check(words_now[1:3] == ["鲸喵屿", "汪小明"], f"最近学到的在前后 {words_now}")
    return f"「{row}」；固定 {cc}；屏蔽 是；最近在前 {words_now}"


@case("WD-22", mutates=True)
def wd22(d):
    c0, cc = script("06-pinyin").pin_fifth(d)
    user_lexicon(d, "固定的词")
    d.tap_text(cc, after=1.0)
    s = d.screen()
    btn = next((n for n in s.nodes if n.text == "取消固定"), None)
    if not btn:
        raise Blocked(f"点「{cc}」后没有取消固定：{[n.text for n in s.nodes if n.text][:10]}")
    d.tap(btn.c, 1.2)
    rows = list_texts(d)
    c = page_cands(d, "shi")
    check(cc not in rows, f"取消后固定的词 {rows[:4]}")
    check(first(c) == first(c0), f"shi {c[:4]}（原来 {c0[:4]}）")
    return f"「{cc}」不在列表；shi {c[:3]}"


@case("WD-24", base="B0-设置", mutates=True)
def wd24(d):
    d.sh(f"echo \"汪小明 wang'xiao'ming\" > {DOWNLOAD}/name-import.txt")
    _, msgs = import_file(d, "name-import.txt", "用户词库")
    if not imported_count(msgs):
        raise Blocked(f"前提没到：{msgs[:3]}")
    d.back()
    c1 = words(page_cands(d, "wangxiaoming"))
    if "汪小明" not in c1:
        c1 = words(d.all_cands())
    for i in range(2):
        if i:
            d.type("wangxiaoming")
        d.cands()
        d.pick("汪小明")
        d.press(".")
        d.clear()
    d.type("wangxiaoming")
    c2 = words(d.cands())
    d.clear()
    user_lexicon(d)
    t = d.all_texts()
    d.tap_text("自动学到的词", after=1.2)
    learned = list_texts(d)
    check(first(c1) == "王小明" and "汪小明" in c1[1:], f"导入后 wangxiaoming {c1[:6]}")
    check(c2[:1] == ["汪小明"], f"两次后 {c2[:4]}")
    check("汪小明" in learned and "我的词" not in t, f"自动学到的词 {learned[:4]}；页 {t[:12]}")
    return f"导入后 {c1[:4]}；两次后 {c2[:3]}；自动学到的词 {learned[1:3]}"


@case("WD-28", base="B0-设置", mutates=True)
def wd28(d):
    dz(d)
    c0 = words(page_cands(d, "dz"))
    if c0[:1] != ["北京市海淀区"] or len(c0) < 2:
        raise Blocked(f"前提没到：dz {c0[:4]}")
    w = c0[1]
    d.pick(w)
    d.press(".")
    d.type("dz")
    d.cands()
    d.pick(w)
    d.press(".")
    d.type("dz")
    c = words(d.cands())
    d.clear()
    check(c[:2] == ["北京市海淀区", w], f"W=「{w}」，两次后 dz {c[:4]}")
    return f"W=「{w}」；两次后 {c[:3]}"


@case("WD-29", base="B0-设置", mutates=True)
def wd29(d):
    add_word(d, "鲸喵屿", "jing miao yu")
    c = words(page_cands(d, "jingmiaoyu"))
    d.clear()
    d.type("wozhuzaijingmiaoyu")
    c2 = words(d.cands())
    d.clear()
    check(c[:1] == ["鲸喵屿"], f"jingmiaoyu {c[:4]}")
    check(c2[:1] == ["我住在鲸喵屿"], f"整句 {c2[:3]}")
    return f"jingmiaoyu {c[:2]}；整句 {c2[:2]}"


def export_lexicon(d):
    """用户词库 → 导出 → 保存 in 下载 (WD-10's steps); returns the new file's name"""
    user_lexicon(d)
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("导出", after=2.5)
    if d.screen().find(text="确定", where="all"):
        d.tap_text("确定", where="all", scroll=False, after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("导出后下载里没有新文件")
    return new[0]


def cut_exports(d, name):
    """cut.fxlb (mid-record, 100 bytes) and cut2.fxlb (the export less its last 13 bytes: the END
    record, so cut exactly at a record's boundary) made in 下载 from the export name; sizes from stat"""
    d.sh(f"cd {DOWNLOAD}; rm -f cut.fxlb cut2.fxlb; n=$(stat -c %s '{name}'); "
         f"head -c 100 '{name}' > cut.fxlb; head -c $((n-13)) '{name}' > cut2.fxlb")
    return ["cut.fxlb", "cut2.fxlb"]


def pick_refused(d, fname):
    """in an open file picker, picks fname; returns (messages seen, choices shown, other dialog text).
    A toast's text is in no UI dump (03-t9's note): the engine's refusal logged, then the toast it
    brings (LearningRestore.from: !ok → learning_backup_failed, 不是本输入法的备份) posted by the app"""
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(12):
        n = d.screen().find(text=fname)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {fname}")
    d.sh("logcat -c")
    d.tap(n.c, 0.3)
    msgs, opts, dialog = [], [], []
    for _ in range(12):
        s = d.screen()
        msgs += [x.text for x in s.nodes if x.text and "备份" in x.text]
        opts += [x.text for x in s.nodes if x.cls == "CheckedTextView"]
        dialog += [x.text for x in s.nodes if x.rid == "message" and "不是本输入法的备份" not in x.text]
        time.sleep(0.3)
    log = d.sh("logcat -d | grep -E 'learning restore:|Toast.*pkg='")
    if "learning restore:" in log and f"pkg={PKG}" in log:
        msgs.append("不是本输入法的备份（toast；" + log.split("learning restore:")[1].splitlines()[0].strip() + "）")
    elif f"pkg={PKG}" in log and not dialog and not opts:
        # a file that isn't one by its head or size (readPicked → null) toasts learning_backup_failed
        # without the engine's log line: the app's toast is the evidence
        msgs.append("不是本输入法的备份（toast，未经引擎：文件头或大小不符）")
    return msgs, opts, dialog


@case("WD-10d", base="B0-设置", mutates=True)
def wd10d(d):
    add_word(d, "蓝鲸花园", "lan jing hua yuan")
    name = export_lexicon(d)
    files = cut_exports(d, name)
    user_lexicon(d, "手动添加的词")
    before = list_texts(d)
    d.back()
    d.back()
    seen = []
    for f in files:
        home(d, "词库与短语")
        d.tap_text("导入词库", after=2.5)
        msgs, opts, dialog = pick_refused(d, f)
        d.open_settings()
        user_lexicon(d, "手动添加的词")
        after = list_texts(d)
        d.back()
        d.back()
        check(any("不是本输入法的备份" in m for m in msgs), f"{f} 提示 {msgs[:3]}")
        check(not opts and not dialog, f"{f} 出了选择 {opts[:3]} / 确认框 {dialog[:1]}")
        check(after == before, f"{f} 用户词库 {before[:4]} → {after[:4]}")
        seen.append(f"{f}「{next((m for m in msgs if '不是' in m), '')}」")
    return "；".join(seen) + "；没有选择和确认框；用户词库不变"


@case("WD-33", base="B0-设置", mutates=True)
def wd33(d):
    add_phrase(d, "nihao", [("cn", "你好")], order="2")
    add_phrase(d, "nihao", [("cn", "您好呀")], order="3")
    c = words(page_cands(d, "nihao"))
    d.clear()
    check(c.count("你好") == 1, f"nihao {c[:5]}：「你好」出现 {c.count('你好')} 次")
    check(c[2:3] == ["您好呀"], f"nihao {c[:5]}：「您好呀」不是第三个")
    return f"nihao {c[:4]}"


case("WD-34", needs="skip:手工做不到（提示条离开导入的词页就没了）；由 ImportedWordsTest 的单元测试管")(lambda d: None)


# ---------------------------------------------------------------- R11: 清空学到的 / 全部清空

def _wd25_setup(d, cun=False):
    """WD-25's 前提: 点了 after 他 twice, 汪小明 yours, C pinned under shi, 是 blocked (and 寸 under fghy)"""
    py = script("06-pinyin")
    d.open_page("text")
    d.switch_im("拼音")
    script("15-privacy-backup").ta_dl_twice(d)
    py.learn_sentence(d)
    c0, cc = py.pin_fifth(d)
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()
    if cun:
        add_im(d, "五笔")
        d.open_page("text")
        d.switch_im("五笔")
        script("10-tables").block_fghy_cun(d)
        d.switch_im("拼音")
    return cc


def _clear_learned(d, everything):
    user_lexicon(d)
    d.tap_text("清空学到的", after=1.0)
    s = d.screen()
    if everything:
        cb = next((n for n in s.nodes if n.cls == "CheckBox" or "全部清空" in (n.text or "")), None)
        if not cb:
            raise Blocked(f"确认框没有全部清空：{[n.text for n in s.nodes if n.text][:10]}")
        d.tap(cb.c, 0.8)
        s = d.screen()
    msg = " ".join(n.text for n in s.nodes if n.text and n.rid not in ("button1", "button2"))
    b = next((n for n in s.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"没有确认：{msg[:80]}")
    d.tap(b.c, 2.0)
    return msg


def _cands(d, code, want=None):
    d.open_page("text")
    d.switch_im("拼音")
    d.type(code)
    c = words(d.cands(want=want))
    d.clear()
    return c


@case("WD-25", mutates=True)
def wd25(d):
    cc = _wd25_setup(d)
    msg = _clear_learned(d, False)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("ta")
    d.cands(want="他")
    d.pick("他")
    d.type("dl")
    dl = words(d.cands())
    d.clear()
    x = _cands(d, "wxm")
    d.type("shi")
    s = words(d.all_cands())
    d.clear()
    check(all(k in msg for k in ("自定义短语", "退回恢复前")), f"确认框「{msg[:200]}」")
    check(first(dl) == "带来", f"他 dl {dl[:3]}")
    check("汪小明" in x, f"wxm {x[:5]}")
    check(first(s) == cc and "是" not in s, f"shi {s[:5]}（C=「{cc}」）")
    return f"他 dl {dl[:2]}；wxm {x[:3]}；shi {s[:3]}"


@case("WD-26", mutates=True)
def wd26(d):
    cc = _wd25_setup(d, cun=True)
    msg = _clear_learned(d, True)
    w = _cands(d, "wangxiaoming")
    d.type("shi")
    s = words(d.all_cands())
    d.clear()
    d.switch_im("五笔")
    d.type("fghy")
    f = words(d.all_cands())
    d.clear()
    check("屏蔽的 2 个词会重新出现" in msg, f"确认框「{msg[:200]}」")
    check(first(w) == "王小明" and "汪小明" not in w, f"wangxiaoming {w[:4]}")
    check("是" in s and first(s) != cc, f"shi {s[:5]}（C=「{cc}」）")
    check("寸" in f, f"fghy {f[:5]}")
    return f"wangxiaoming {w[:2]}；shi {s[:3]}；fghy {f[:3]}"
