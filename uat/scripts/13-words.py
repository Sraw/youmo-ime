"""uat/13-words.md (词库与短语). The .md is the source of truth; each function follows its case's
前提/操作/预期. Files for the import cases are pushed from uat/fixtures to 下载 and picked in the
system file picker."""
import base64
import gzip
import hashlib
import itertools
import os
import random
import re
import shutil
import statistics
import tempfile
import time
import zipfile

from uat import Blocked, Fail, PKG, add_im, case, check, first, home, menu_tap, reset, script, sym_char, in_picker, new_files

FIXTURES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fixtures")
DOWNLOAD = "/sdcard/Download"
DICTS = f"/sdcard/Android/data/{PKG}/files/data/pinyin/dictionaries"

# how to type each word with pinyin: one piece per composition
PIECES = {"蓝鲸花园": [("lanjinghuayuan", "蓝鲸花园")], "幽默": [("youmo", "幽默")],
          # not the engine's own first for lan jing hua/gong yuan (蓝鲸花园, 蓝鲸公园)
          "澜境花园": [("lan", "澜"), ("jing", "境"), ("huayuan", "花园")],
          "澜境公园": [("lan", "澜"), ("jing", "境"), ("gongyuan", "公园")],
          "蓝鲸公园": [("lanjing", "蓝鲸"), ("gongyuan", "公园")], "时间": [("shijian", "时间")],
          "天气": [("tianqi", "天气")], "鲸园": [("jing", "鲸"), ("yuan", "园")],
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
    """digits and spaces on the number keyboard (123), then back to the letters (ABC)"""
    d.press("123", after=0.8)
    k = d.keys(refresh=True)
    # keys() names them lower-cased, the space key "space"
    miss = [ch for ch in digits.replace(" ", "") if ch not in k] + [x for x in ("space", "abc") if x not in k]
    if miss:
        raise Blocked(f"数字键盘上没有 {miss}：{sorted(k)[:20]}")
    d.taps([k["space" if ch == " " else ch] for ch in digits], gap=0.12, after=0.5)
    d.tap(k["abc"], 0.8)
    d._keys = None


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


def progress(d):
    """what the progress dialogs and bars said while d's cases waited ("正在分析格式… 45%" and the
    like): per device, as `run --devices` runs one thread each; a case reading it empties it first"""
    if not hasattr(d, "progress"):
        d.progress = []
    return d.progress


def progress_seen(d, x):
    """the screen's texts with a percent in them, kept in progress(d) (once each)"""
    seen = progress(d)
    for t in x.texts():
        if "%" in t and t not in seen:
            seen.append(t)


def messages(s):
    """a dialog's message texts"""
    return [m.text for m in s.nodes if m.rid == "message"]


def show_hidden(d):
    """the picker's ⋮ → 显示隐藏的文件: DocumentsUI leaves out names starting with "." otherwise.
    Already shown, the menu has 隐藏… instead and is closed untouched"""
    more = d.screen().find(desc="更多选项", where="all")
    if not more:
        raise Blocked("文件选择里没有 ⋮")
    d.tap(more.c, 1.0)
    n = d.screen().find(text="显示隐藏的文件", where="all")
    if n:
        d.tap(n.c, 1.5)
    else:
        d.back()


def import_file(d, name, target=None):
    """词库与短语 → 导入词库 → name; returns the dialog's choice before any change, then picks
    target (a prefix of the option) and 导入词库. The toast/dialog after it is returned too.
    A dialog with no choice (a backup's restore, a file refused before the choice, 已有名为…)
    returns (None, its Screen) whatever target is: messages(s) has its text."""
    dict_page(d)
    d.tap_text("导入词库", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    # a package the picker has not served yet opens in 最近 (the release build after the debug one
    # had used it): go to 下载 through the roots drawer
    if d.screen().find(text="近期的文件"):
        d.tap((77, 148), 1.0)
        d.tap_text("下载", scroll=False, after=2.0)
    if name.startswith("."):
        show_hidden(d)
    # the exports of earlier runs pile up in 下载 (a grid of two columns): enough scrolls to pass them
    for _ in range(12):
        n = d.screen().find(text=name)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {name}")
    d.tap(n.c, 1.0)
    # the whole file is read before the dialog (D6): a large one takes seconds; one refused shows
    # 格式不支持 instead, its message returned with no choice
    s = d.wait(lambda x: progress_seen(d, x) or (x if any(m.cls == "CheckedTextView" or m.rid == "message" for m in x.nodes) else None),
               timeout=180, step=1.0, what="并入哪个词库或格式提示")
    opts = [n for n in s.nodes if n.cls == "CheckedTextView"]
    if not opts:
        return None, s
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
    # a base import returns once the merged file is compiled: the loading dialog up till then
    d.wait(lambda x: progress_seen(d, x) or not x.find(contains="正在导入"), timeout=240, step=1.0, what="导入完")
    for _ in range(12):
        s = d.screen()
        msgs += [n.text for n in s.nodes if n.cls == "android.widget.Toast$TN" or n.pkg == "com.android.systemui"
                 and n.text and "：" not in n.text[:3]] + [n.text for n in s.nodes if n.rid == "message"]
        time.sleep(0.4)
    return chosen, msgs


def dict_page(d):
    """词库与短语, its sections shown: the page lists the directory off the main thread, and a first
    frame of only its title came while a large 0.3.1 text was being converted"""
    home(d, "词库与短语")
    d.wait(lambda x: "基础词库" in x.texts() or None, timeout=15, step=0.3, what="词库页的基础词库一节")


def dictionaries(d):
    """the 基础词库 section's rows, below 内置词库, up to the 导入 header"""
    dict_page(d)
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

@case("WD-02", base="B0-设置", mutates=True)
def wd02(d):
    add_word(d, "幽默")
    rows = list_texts(d)
    row = [r for r in rows if "幽默" in r or "you mo" in r]
    check(any("you mo" in r for r in rows), f"列表 {rows[:6]}")
    return f"{row}"


@case("WD-03", base="B0-设置", mutates=True)
def wd03(d):
    d.step("加词")
    user_lexicon(d)
    n0 = count_of(d, "手动添加的词")
    add_word(d, "澜境花园", "lan jing hua yuan")
    rows0 = list_texts(d)
    d.back()
    n1 = count_of(d, "手动添加的词")
    check(any(r.startswith("澜境花园") for r in rows0), f"列表 {rows0[:6]}")
    check(n0 == "0 个词" and n1 == "1 个词", f"词数 {n0} → {n1}")
    add_word(d, "鲸喵屿", "jing miao yu")
    d.step("打加的词")
    c0 = page_cands(d, "lanjinghuayuan")
    d.clear()
    d.type("jingmiaoyu")
    j = words(d.cands())
    d.clear()
    d.type("wozhuzaijingmiaoyu")
    js = words(d.cands())
    d.clear()
    check(first(c0) == "澜境花园", f"lanjinghuayuan {c0[:3]}")
    check(j[:1] == ["鲸喵屿"], f"jingmiaoyu {j[:4]}")
    check(js[:1] == ["我住在鲸喵屿"], f"整句 {js[:3]}")
    d.step("编辑")
    user_lexicon(d, "手动添加的词")
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
    check(any(r.startswith("澜境公园") for r in rows1), f"编辑后 {rows1[:6]}")
    check(first(c1) == "澜境公园", f"编辑后候选 {c1[:3]}")
    d.step("删除")
    user_lexicon(d, "手动添加的词")
    item_menu(d, "澜境公园", "删除")
    time.sleep(0.5)
    s = d.screen()
    conf = next((n for n in s.nodes if n.rid == "button1"), None)
    if conf:
        d.tap(conf.c, 1.0)
    rows2 = list_texts(d)
    c = page_cands(d, "lanjinggongyuan")
    check(not any("澜境公园" in r for r in rows2), f"删除后 {rows2[:6]}")
    check(first(c) != "澜境公园", f"删除后候选 {c[:3]}")
    return f"词数 {n0} → {n1}；鲸喵屿 第一、整句 {js[:1]}；编辑后第一个是它；删除后候选 {c[:2]}"


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
        d.tap_text("确定", where="all", scroll=False, after=0.3)
        in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=0.3)
    new = new_files(d, DOWNLOAD, before)
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
    warn = " ".join(n.text for n in d.screen().nodes if n.rid == "message")
    d.tap_text("确定", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"另存没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=0.3)
    new = new_files(d, DOWNLOAD, before)
    if not new:
        raise Blocked("另存后下载里没有新文件")
    today = d.sh("date +%Y%m%d").strip()
    check("都能拷走" in warn, f"另存前的提示「{warn}」")
    # the picker adds " (n)" when Download has that name already (an earlier run's copy)
    check(any(re.fullmatch(rf"youmo-learning-{today}( \(\d+\))?\.fxlb", f) for f in new),
          f"另存的文件 {new}（今天 {today}）")
    reset(d, "B0-设置", "设置")
    user_lexicon(d, "学习备份")
    d.tap_text("从备份恢复", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(12):
        n = d.screen().find(text=new[0])
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {new[0]}")
    d.tap(n.c, 0.3)
    d.wait_text("确定", 8)
    d.tap_text("确定", after=3.0)
    home(d, "词库与短语", "导入的词")
    texts = d.screen().texts()
    row = next((t for t in texts if "big-words.txt" in t), "")
    check(row, f"恢复后导入的词里没有 big-words.txt：{texts[:6]}")
    check("1001" in " ".join(texts), f"那一批不是 1001 个词：{texts[:6]}")
    return f"另存 {new[0]}（先提示能拷走），重置后恢复；导入的词 {texts[:3]}"


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


@case("WD-13", base="B0-设置", mutates=True)
def wd13(d):
    import_dict_words(d)
    on = dict_files(d)
    d.step("关闭")
    menu = dict_row_menu(d, "关闭")
    off = dict_files(d)
    opts = [x for x in menu if x in ("关闭", "删除", "打开", "编辑", "导出", "重命名")]
    check(sorted(opts) == ["关闭", "删除"], f"菜单 {opts}")
    c1 = words(page_cands(d, "kemiaotangpu"))
    d.clear()
    check("氪喵糖铺" not in c1, f"关闭后第一页 {c1}")
    d.step("删除")
    dict_row_menu(d, "删除")
    rows = dictionaries(d)
    gone = dict_files(d)
    c = words(page_cands(d, "kemiaotangpu"))
    check("dict-words.txt.gz" in on and "dict-words.txt" not in on, f"导入后目录 {on}")
    check("dict-words.txt.gz.disable" in off, f"关闭后目录 {off}")
    check(not any(f.startswith("dict-words") for f in gone), f"删除后目录 {gone}")
    check(not any("dict-words" in r for r in rows), f"删除后 {rows}")
    check("氪喵糖铺" not in c, f"删除后第一页 {c}")
    return f"菜单 {opts}；关闭后第一页 {c1[:3]}；删除后没有；第一页 {c[:3]}"


@case("WD-14b", base="B0-设置", mutates=True)
def wd14b(d):
    d.step("导入")
    push(d, "extra.dict")
    before = dictionaries(d)
    chosen, msgs = import_file(d, "extra.dict", "用户词库")
    d.back()
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    d.back()
    after = dictionaries(d)
    check(chosen and chosen.startswith("基础词库"), f"一开始选的是「{chosen}」")
    check(imported_count(msgs) == 2, f"提示 {msgs[:3]}")
    check(any("extra.dict" in t for t in batches), f"导入的词 {batches[:5]}")
    check(after == before, f"基础词库 {before} → {after}")
    d.step("点两次成你的词")
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
    d.clear()
    user_lexicon(d)
    t = d.all_texts()
    d.tap_text("自动学到的词", after=1.2)
    learned = list_texts(d)
    # behind the dictionary's own the first time, second after one pick (two make it first)
    check(first(c1) != "琮璟" and c2[1:2] == ["琮璟"], f"第一次 {c1[:4]}，第二次 {c2[:3]}")
    check(first(c3) == "琮璟", f"点过两次后 {c3[:3]}")
    check(any("琮璟" in r for r in learned) and "我的词" not in t, f"自动学到的词 {learned[:4]}；页 {t[:12]}")
    d.step("撤掉这批")
    asked, gone = drop_batch(d, "extra.dict")
    d.back()
    x = _cands(d, "xiaoqin")
    c = _cands(d, "congjing")
    check("还不是你的 1 个词" in asked and "extra.dict" in asked, f"确认框 {asked[:80]}")
    check(any("撤掉了 1 个" in s for s in gone), f"撤掉后 {gone[:6]}")
    check("骁骎" not in x and "琮璟" in c, f"xiaoqin {x[:5]}，congjing {c[:4]}")
    d.step("再导入、撤销")
    # the same list imports again (it was undone, 琮璟 is yours), then 撤销 on the bar brings it back
    _, msgs = import_file(d, "extra.dict", "用户词库")
    d.back()
    drop_batch(d, "extra.dict")
    d.tap_text("撤销", where="all", scroll=False, after=1.0)
    # the page itself: with its one batch dropped it may have closed back to 词库与短语
    home(d, "词库与短语", "导入的词")
    back_in = d.screen().texts()
    # all of them, as WD-23 read: an imported word not yet yours ranks below the dictionary's
    d.open_page("text")
    d.switch_im("拼音")
    d.type("xiaoqin")
    x2 = words(d.all_cands())
    d.clear()
    check(imported_count(msgs) == 1, f"再导入一次 {msgs[:3]}")
    check(any("extra.dict" in s for s in back_in), f"撤销后导入的词 {back_in[:5]}")
    check("骁骎" in x2, f"撤销后 xiaoqin {x2[:5]}")
    return f"第二次 {c2[:2]}；第三次 {c3[:2]}；撤掉后 xiaoqin {x[:3]}、congjing {c[:2]}；撤销后 xiaoqin {x2[:3]}"


def title_of(s):
    """the confirm dialog's title: a custom one (two lines, the format under the question) has no alertTitle id"""
    return next((n.text for n in s.nodes if n.rid == "alertTitle"), "") or next((n.text for n in s.nodes if n.text and n.text.startswith("并入哪个词库")), "")


def java_heap(d):
    """the App Summary's Java Heap, in KB, as dumpsys meminfo has it, after a GC: unswept garbage
    from background work (a table coding frost) read 130 MB, then 60 MB a second later. SIGUSR1
    makes ART collect"""
    pid = d.sh(f"pidof {PKG}").strip()
    if pid:
        d.root(f"kill -10 {pid}")
        time.sleep(1.0)
    m = re.search(r"Java Heap:\s+(\d+)", d.sh(f"dumpsys meminfo {PKG}"))
    return int(m.group(1)) if m else None


def heaps(d, n=5):
    out = []
    for _ in range(n):
        out.append(java_heap(d))
        time.sleep(1.0)
    return out


# how far the Java heap may rise over a case's own reading with no large dictionary: frost held
# whole on the heap was ~150 MB more (the OOM this guards), compiled and mapped it is a few MB
HEAP_RISE_KB = 64 << 10


def heap_base(d):
    """the keyboard up on the test page, a few keys typed, its heaps: a case's reading to compare with"""
    page_cands(d, "nihao")
    d.clear()
    return heaps(d)


def heap_rise(base, now):
    """the rise of now's median over base's, in KB, the first reading of each left out (the first
    dumpsys meminfo after a while reads high); None if a reading is missing"""
    if None in base[1:] or None in now[1:]:
        return None
    return statistics.median(now[1:]) - statistics.median(base[1:])


# the one release the cases' counts (1914461 个词, 176 行读不懂) come from: uat/README.md
FROST_SHA256 = "86473190c505691c7f1ef7b87345a16b555c75fea30527f472a151b6eb1ad41f"
_frost_ok = {}


def file_sizes(d):
    """the dictionaries directory's names and sizes in bytes"""
    out = {}
    for ln in d.sh(f"ls -l {DICTS}").splitlines():
        f = ln.split()
        if len(f) >= 8 and f[4].isdigit():
            out[f[-1]] = int(f[4])
    return out


def dict_files(d):
    """the dictionaries directory's names"""
    return d.sh(f"ls {DICTS}").split()


def frost(d):
    """rime-frost's frost_dict_for_fcitx5.txt, its path in UAT_FROST (89 MB: not among the fixtures)"""
    path = os.environ.get("UAT_FROST")
    if not path or not os.path.isfile(path):
        raise Blocked("UAT_FROST 没指向 frost_dict_for_fcitx5.txt")
    if path not in _frost_ok:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
        _frost_ok[path] = h.hexdigest()
    if _frost_ok[path] != FROST_SHA256:
        raise Blocked(f"UAT_FROST 不是用例对应的那一份（SHA-256 {_frost_ok[path][:12]}…，见 uat/README.md）")
    return path


def abazhou(d):
    page_cands(d, "abazhou")
    c = words(d.all_cands())
    d.clear()
    return c


@case("WD-14d", base="B0-设置", mutates=True)
def wd14d(d):
    src = frost(d)
    d.open_page("text")
    d.switch_im("拼音")
    base = heap_base(d)
    d.adb("push", src, f"{DOWNLOAD}/frost_dict_for_fcitx5.txt", timeout=300)
    seen = progress(d)
    seen.clear()
    t0 = time.time()
    chosen, s = import_file(d, "frost_dict_for_fcitx5.txt")
    read = time.time() - t0
    title = title_of(s)
    d.back()
    _, msgs = import_file(d, "frost_dict_for_fcitx5.txt", "基础词库")
    rows = dictionaries(d)
    d.open_page("text")
    d.switch_im("拼音")
    heap = heaps(d)
    rise = heap_rise(base, heap)
    c = abazhou(d)
    g = page_cands(d, "gennishuo")
    d.clear()
    sizes = file_sizes(d)
    check(rise is not None and rise < HEAP_RISE_KB, f"Java 堆 {base} → {heap} KB")
    check("跟你说" in words(g)[:3], f"gennishuo 的候选 {words(g)[:6]}")
    check(15 << 20 < sizes.get("frost_dict_for_fcitx5.txt.gz", 0) < 32 << 20
          and 40 << 20 < sizes.get("merged.ymd", 0) < 60 << 20
          and not any(n.endswith((".txt", ".tmp")) for n in sizes), f"词库目录 {sizes}")
    check(any("正在分析格式" in t for t in seen) and any("正在整理词条" in t for t in seen), f"进度 {seen[:8]}")
    check("拼音用 ' 分开" in title and "第三列是概率" in title and "1914461 个词" in title, f"标题 {title!r}")
    check(any("frost_dict_for_fcitx5" in r for r in rows), f"基础词库一节 {rows}")
    check("阿坝州" in c, f"abazhou 的候选 {c[:6]}")
    return f"目录 {sizes}；读 {read:.0f} s；标题 {title.splitlines()[-1]!r}；进度 {seen[:2]}…{seen[-1:]}；Java 堆 {base} → {heap} KB；abazhou {c[:3]}；gennishuo {words(g)[:3]}"


@case("WD-14e", base="B0-设置", mutates=True)
def wd14e(d):
    push(d, "rime-words.dict.yaml")
    chosen, s = import_file(d, "rime-words.dict.yaml")
    title = title_of(s)
    d.back()
    import_file(d, "rime-words.dict.yaml", "基础词库")
    rows = dictionaries(d)
    page_cands(d, "kemiaoxinggang")
    c = words(d.all_cands())
    d.clear()
    check(chosen == "基础词库（推荐）", f"一开始选的是「{chosen}」")
    check("Rime 词库 · 拼音用空格分开 · 第三列是词频 · 3 个词" in title, f"标题 {title!r}")
    check(any("rime-words" in r for r in rows), f"基础词库一节 {rows}")
    check("氪喵星港" in c, f"候选 {c[:6]}")
    return f"标题 {title.splitlines()[-1]!r}；候选 {c[:3]}"


@case("WD-14f", base="B0-设置", mutates=True)
def wd14f(d):
    push(d, "mixed-words.txt", "initials-words.txt", "gbk-words.txt")
    before = dictionaries(d)
    mixed, why_mixed = import_file(d, "mixed-words.txt", "基础词库")
    d.back()
    initials, why_initials = import_file(d, "initials-words.txt", "基础词库")
    d.back()
    after = dictionaries(d)
    _, s = import_file(d, "gbk-words.txt")
    title = title_of(s)
    d.back()
    # still offered to the user dictionary, read line by line; the base refuses it when picked
    check(mixed is not None and any("格式不支持：文件里混有不同格式的行" in m for m in why_mixed),
          f"mixed：选择「{mixed}」 {[m for m in why_mixed if '格式' in m][:2]}")
    check(initials is not None and any("格式不支持：只有声母，无法确定读音" in m for m in why_initials),
          f"initials：选择「{initials}」 {[m for m in why_initials if '格式' in m][:2]}")
    check(after == before, f"词库页 {before} → {after}")
    check("GBK 编码" in title and "2 个词" in title, f"gbk 的标题 {title!r}")
    said = [m for m in why_mixed + why_initials if "格式不支持" in m]
    return f"{said}；gbk {title.splitlines()[-1]!r}"


@case("WD-14g", base="B0-设置", mutates=True)
def wd14g(d):
    c = list(zip("啊吧擦大饿发嘎哈鸡卡", "a ba ca da e fa ga ha ji ka".split()))
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, "user-100k.txt")
        with open(local, "w") as f:
            for w in itertools.islice(itertools.product(c, repeat=6), 100001):
                f.write("".join(x[0] for x in w) + " " + "'".join(x[1] for x in w) + "\n")
        d.adb("push", local, f"{DOWNLOAD}/user-100k.txt", timeout=120)
    _, msgs = import_file(d, "user-100k.txt", "用户词库")
    d.back()
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    d.back()
    check(any("这个词库太大，请并入基础词库" in m for m in msgs), f"提示 {msgs[:3]}")
    check(not any("user-100k" in t for t in batches), f"导入的词 {batches[:5]}")
    return f"提示 {[m for m in msgs if '太大' in m][:1]}"


@case("WD-14h", base="B0-设置", mutates=True)
def wd14h(d):
    src = frost(d)
    dicts = f"/sdcard/Android/data/{PKG}/files/data/pinyin/dictionaries"
    d.open_page("text")
    d.switch_im("拼音")
    base = heap_base(d)
    d.sh(f"am force-stop {PKG}")
    # the compile's log line counted from here only
    d.sh("logcat -c")
    d.sh(f"mkdir -p {dicts}; rm -f {dicts}/merged.ymd")
    # no touch to an old mtime any more: a .txt converted is an upgrade by itself (dict-size §4 S3)
    d.adb("push", src, f"{dicts}/frost.txt", timeout=300)
    # one 0.3.1 kept turned off: listed off, not compiled
    with tempfile.TemporaryDirectory() as tmp:
        off = os.path.join(tmp, "old.txt.disable")
        with open(off, "w") as f:
            f.write("氪喵星港 ke'miao'xing'gang 0.0\n")
        d.adb("push", off, f"{dicts}/old.txt.disable")
    d.open_page("text")
    d.switch_im("拼音")
    early = page_cands(d, "nihao")
    d.clear()
    # the idle bar says how far the upgrade is, typing going on meanwhile; then the notice. A
    # release build compiles frost in ~20 s, done (the 2 s notice gone) before a slow dump looks:
    # what was caught is reported, the end state is what is checked (the file, the compile's log, its words)
    d.open_page("text")
    caught = []

    def look(x):
        for t in x.texts(where="all"):
            if ("正在升级词库格式" in t or t == "词库格式已升级") and t not in caught:
                caught.append(t)
        names = d.sh(f"ls {dicts}").split()
        return ("merged.ymd" in names and "frost.txt" not in names) or None

    # its own name, not merged.ymd.tmp being written, after the conversion; then the notice's 2 s
    d.wait(look, timeout=300, step=0.5, what="merged.ymd")
    end = time.time() + 3
    while time.time() < end and "词库格式已升级" not in caught:
        look(d.screen())
    log = d.sh("logcat -d | grep 'merged dictionaries:'")
    compiled = re.search(r"merged dictionaries: (\d+) words of 1 files", log)
    bar = next((t for t in caught if "正在升级" in t), None)
    said = "词库格式已升级" in caught or None
    files = d.sh(f"ls {dicts}").split()
    time.sleep(3)
    heap = heaps(d)
    rise = heap_rise(base, heap)
    c = abazhou(d)
    g = page_cands(d, "gennishuo")
    d.clear()
    rows = dictionaries(d)
    k = page_cands(d, "kemiaoxinggang")
    d.clear()
    check("氪喵星港" not in words(k), f"停用的词库进了候选 {words(k)[:4]}")
    check(any("已关闭" in r for r in rows), f"停用的词库没列成已关闭 {rows}")
    check(rise is not None and rise < HEAP_RISE_KB, f"Java 堆 {base} → {heap} KB")
    check(compiled and int(compiled.group(1)) > 1_900_000, f"日志里没有合成 frost 的记录：{log[-300:]}")
    check("跟你说" in words(g)[:3], f"gennishuo 的候选 {words(g)[:6]}")
    check(early, "刚弹出的键盘打 nihao 没有候选")
    check({"frost.txt.gz", "old.txt.gz.disable", "merged.ymd"} <= set(files)
          and not any(f.endswith((".txt", ".txt.disable", ".tmp")) for f in files), f"词库目录 {files}")
    check("阿坝州" in c, f"abazhou 的候选 {c[:6]}")
    check(any("frost" in r for r in rows), f"基础词库一节 {rows}")
    return f"合成前 nihao {words(early)[:2]}；空闲栏 {bar!r}；提示 {'看到' if said else '没赶上'}；合成 {compiled.group(1) if compiled else None} 词；目录 {files}；Java 堆 {base} → {heap} KB；abazhou {c[:3]}；gennishuo {words(g)[:3]}"


@case("WD-14i", base="B0-设置", mutates=True)
def wd14i(d):
    """frost imported and cancelled while it compiles: nothing imported, nothing left over"""
    d.adb("push", frost(d), f"{DOWNLOAD}/frost_dict_for_fcitx5.txt", timeout=300)
    dicts = f"/sdcard/Android/data/{PKG}/files/data/pinyin/dictionaries"
    push(d, "rime-words.dict.yaml")
    import_file(d, "rime-words.dict.yaml", "基础词库")
    d.back()
    before = dictionaries(d)
    files_before = d.sh(f"ls -l {dicts}")
    chosen, s = import_file(d, "frost_dict_for_fcitx5.txt")
    d.tap(next(n for n in s.nodes if n.text.startswith("基础词库")).c, 0.5)
    d.tap(next(n for n in d.screen().nodes if n.text == "导入词库" and n.cls == "Button").c, 0.5)
    at = d.wait(lambda x: x.find(contains="正在整理词条"), timeout=60, step=0.5, what="编译进度")
    d.tap_text("取消", scroll=False, after=0.5)
    d.wait(lambda x: not x.find(contains="正在导入"), timeout=60, step=0.5, what="取消后对话框关掉")
    time.sleep(2)
    # again, cancelled at once, while the file is still being saved before its compile (review M1)
    chosen, s = import_file(d, "frost_dict_for_fcitx5.txt")
    d.tap(next(n for n in s.nodes if n.text.startswith("基础词库")).c, 0.5)
    d.tap(next(n for n in d.screen().nodes if n.text == "导入词库" and n.cls == "Button").c, 0.1)
    # the import's own dialog, not the closing target dialog whose negative button is 取消 too
    s2 = d.wait(lambda x: x if x.find(contains="正在导入") and x.find(text="取消") else None,
                timeout=30, step=0.2, what="导入对话框")
    early_said = [t for t in s2.texts() if "%" in t or "正在" in t]
    d.tap(s2.find(text="取消").c, 0.5)
    d.wait(lambda x: not x.find(contains="正在导入"), timeout=120, step=0.5, what="早取消后对话框关掉")
    time.sleep(2)
    after = dictionaries(d)
    files_after = d.sh(f"ls -l {dicts}")
    # the app's own cache, which the shell user cannot list: as root on the device's own container
    # (or the emulator); -a so that an empty listing means it was not read
    cache = d.root(f"ls -a /data/data/{PKG}/cache")
    if not cache.strip():
        raise Blocked("读不到应用的缓存目录")
    c = page_cands(d, "kemiaoxinggang")
    d.clear()
    check(after == before, f"词库页 {before} → {after}")
    check(files_after == files_before, f"词库目录变了：{files_before} → {files_after}")
    check("import" not in cache, f"缓存里还有 {cache}")
    check("氪喵星港" in words(c), f"原来的词库还在用：{words(c)[:4]}")
    return f"取消时 {at.text!r}；早取消时 {early_said}；词库页 {after}"


def rime_off_on(d):
    """rime-words.dict.yaml in the base, then the page's act(row, action) and what 正在更新 it caught"""
    push(d, "rime-words.dict.yaml")
    import_file(d, "rime-words.dict.yaml", "基础词库")
    d.back()
    seen = []

    def act(row, action):
        home(d, "词库与短语")
        d.tap_text(row)
        d.tap_text(action, scroll=False, after=0.2)
        if action == "删除":
            d.tap_text("确定", scroll=False, after=0.2)
        # a 3-word file compiles before the page is read: 正在更新 reported when caught, the end state checked
        seen.extend(t for t in d.screen().texts() if "正在更新" in t)
        time.sleep(2)

    return act, seen


@case("WD-14j", base="B0-设置", mutates=True)
def wd14j(d):
    """a dictionary turned off, on, deleted: the page says 正在更新 till the new file is in use"""
    act, seen = rime_off_on(d)
    act("rime-words", "关闭")
    off = dictionaries(d)
    f_off = dict_files(d)
    c_off = page_cands(d, "kemiaoxinggang")
    d.clear()
    act("rime-words", "开启")
    f_on = dict_files(d)
    c_on = page_cands(d, "kemiaoxinggang")
    d.clear()
    act("rime-words", "删除")
    gone = dictionaries(d)
    f_gone = dict_files(d)
    c_gone = page_cands(d, "kemiaoxinggang")
    d.clear()
    check("rime-words.txt.gz.disable" in f_off and "rime-words.txt.gz" in f_on
          and not any(f.startswith("rime-words") for f in f_gone), f"目录 {f_off} → {f_on} → {f_gone}")
    check(any("已关闭" in r for r in off), f"关闭后 {off}")
    check("氪喵星港" not in words(c_off), f"关闭后仍有 {words(c_off)[:4]}")
    check("氪喵星港" in words(c_on), f"开启后没有 {words(c_on)[:4]}")
    check(not any("rime-words" in r for r in gone) and "氪喵星港" not in words(c_gone), f"删除后 {gone} {words(c_gone)[:4]}")
    return f"正在更新 {seen[:2]}；关闭 {off}；删除后 {gone}"


@case("WD-14j2", base="B0-设置", mutates=True)
def wd14j2(d):
    """review F3430: another turned off while frost's compile runs, not told it failed; frost's 1.9M
    words take long enough to catch 正在更新 N%"""
    src = frost(d)
    rime_off_on(d)
    d.adb("push", src, f"{DOWNLOAD}/frost_dict_for_fcitx5.txt", timeout=300)
    import_file(d, "frost_dict_for_fcitx5.txt", "基础词库")
    d.back()
    home(d, "词库与短语")
    # turned off, what is left (rime-words' 3 words) compiles at once; turned on again, frost's
    # 1.9M words compile long enough to act during it
    d.tap_text("frost_dict_for_fcitx5")
    d.tap_text("关闭", scroll=False, after=3.0)
    d.tap_text("frost_dict_for_fcitx5")
    d.tap_text("开启", scroll=False, after=0.2)
    during, said, shown = [], [], []
    for _ in range(120):
        texts = d.screen().texts()
        shown.append(texts)
        during = [t for t in texts if re.search(r"正在更新 \d+%", t)]
        if during:
            break
        time.sleep(0.5)
    if not during:
        raise Blocked(f"frost 开启后没看到「正在更新 N%」：{shown[0]} … {shown[-1]}")
    d.tap_text("rime-words")
    d.tap_text("关闭", scroll=False, after=0.2)
    for _ in range(300):
        texts = d.screen().texts()
        said.extend(t for t in texts if "没能更新" in t)
        if not any("正在更新" in t for t in texts):
            break
        time.sleep(1)
    both = dictionaries(d)
    frost_row = next((i for i, r in enumerate(both) if "frost_dict_for_fcitx5" in r), None)
    rime_row = next((i for i, r in enumerate(both) if "rime-words" in r), None)
    # the row's own status text, right below its name (review F3450: "3" anywhere in three rows passed)
    status = lambda i: both[i + 1] if i is not None and i + 1 < len(both) else ""
    check(not said, f"合成中关闭另一个词库弹了 {said[:2]}")
    check("已开启" in status(frost_row) and "已关闭" in status(rime_row), f"两个词库的状态 {both}")
    check("1914461" in status(frost_row).replace(",", "") and re.search(r"(^|\D)3 个词", status(rime_row)),
          f"两个词库的词数 {both}")
    return f"合成中 {during[:1]} 再关闭 → {both}"


def drop_batch(d, name):
    """导入的词 → the row of [name] → 撤掉这批: the dialog's text, then what the screen shows after (the Snackbar)."""
    home(d, "词库与短语", "导入的词")
    d.tap_text(name, contains=True, after=1.0)
    asked = " ".join(d.screen().texts())
    d.tap_text("撤掉这批", where="all", scroll=False, after=0.6)
    return asked, d.screen().texts()


case("WD-14r", needs="release:v0.3.2 正式版里并入雾凇拼音，再覆盖安装这一版正式版，签名要一致")(lambda d: None)
case("WD-14v", needs="release:WD-14r 之后装回 v0.3.2 正式版（adb install -r -d），再装回这一版正式版")(lambda d: None)


def plant_031(d, src, *extra):
    """0.3.1's way of keeping dictionaries: frost as frost.txt, no merged.ymd; extra (name, line)"""
    d.sh(f"am force-stop {PKG}")
    d.sh("logcat -c")
    d.sh(f"mkdir -p {DICTS}; rm -f {DICTS}/merged.ymd")
    d.adb("push", src, f"{DICTS}/frost.txt", timeout=300)
    with tempfile.TemporaryDirectory() as tmp:
        for name, line in extra:
            path = os.path.join(tmp, name)
            with open(path, "w") as f:
                f.write(line + "\n")
            d.adb("push", path, f"{DICTS}/{name}")


# the padding after frost in slow_frost, MB: picked so its conversion lasts tens of seconds on release
SLOW_PAD_MB = 500


def slow_frost(d, pad_mb=SLOW_PAD_MB):
    """frost with pad_mb of '#' comment lines of random letters after it: a release build gzips frost
    alone in under a second, too soon to act while it converts; comments are skipped by the compile
    (DictionaryCompiler) and not counted as words, but deflate on them is slow, so the conversion
    lasts tens of seconds (staged, not raced). Made once per run in the host's temp dir"""
    src = frost(d)
    path = os.path.join(tempfile.gettempdir(), f"uat-frost-slow-{_frost_ok[src][:12]}-{pad_mb}.txt")
    if not os.path.isfile(path):
        rnd = random.Random(14)
        with open(path + ".part", "wb") as out, open(src, "rb") as f:
            shutil.copyfileobj(f, out, 1 << 20)
            line = 1 << 10
            block = []
            for _ in range(pad_mb * 1024):
                block.append(b"#" + base64.b64encode(rnd.randbytes(line * 3 // 4))[:line - 2] + b"\n")
                if len(block) == 1024:
                    out.write(b"".join(block))
                    block = []
            out.write(b"".join(block))
        os.replace(path + ".part", path)
    return path


def upgrading(d, timeout=60):
    """waits for the idle bar's 正在升级词库格式, the text page's keyboard up"""
    d.open_page("text")
    d.switch_im("拼音")
    return d.wait(lambda x: next((t for t in x.texts(where="all") if "正在升级词库格式" in t), None),
                  timeout=timeout, step=0.2, what="正在升级词库格式")


def converting(bar):
    """the idle bar's percent still in the conversion's share (CONVERT_SHARE, the first 30%)"""
    m = re.search(r"(\d+)%", bar or "")
    return m is not None and int(m.group(1)) < 30


def upgraded(d, timeout=300):
    """the directory once the conversion and the compile are done: merged.ymd, no .txt, no tmp"""
    def done(_):
        names = dict_files(d)
        return names if "merged.ymd" in names and not any(
            n.endswith((".txt", ".txt.disable", ".tmp")) for n in names) else None
    return d.wait(done, timeout=timeout, step=1.0, what="转换、合成完")


@case("WD-14s", base="B0-设置", mutates=True)
def wd14s(d):
    """killed while 0.3.1's frost.txt is converted: the next start converts it again and ends whole"""
    plant_031(d, frost(d))
    bar = upgrading(d)
    if not converting(bar):
        raise Blocked(f"看到时已过了转换：{bar!r}")
    d.sh(f"am force-stop {PKG}")
    killed = dict_files(d)
    if "frost.txt" not in killed:
        raise Blocked(f"停下时已经转换完了：{killed}")
    again = upgrading(d)
    files = upgraded(d)
    c = abazhou(d)
    rows = dictionaries(d)
    check("frost.txt.gz" in files, f"词库目录 {files}")
    check("阿坝州" in c, f"abazhou 的候选 {c[:6]}")
    check(sum("frost" in r for r in rows) == 1, f"词库页 {rows}")
    return f"停下时 {bar!r}，目录 {killed}；再开 {again!r}；目录 {files}；abazhou {c[:3]}"


@case("WD-14s2", base="B0-设置", mutates=True)
def wd14s2(d):
    """F3658: killed after frost.txt's conversion committed, before the compile ended: the next start
    still says 正在升级词库格式 (.merged.upgrading), not a silent 正在更新"""
    plant_031(d, frost(d))
    bar = upgrading(d)
    # the directory, not the bar, says the conversion committed: the bar reaches 30% when the text is
    # read, before its gzip is synced and renamed; the compile after lasts ~20 s on release, so a
    # look at the directory every few tenths lands in it
    d.wait(lambda _: (lambda n: "frost.txt.gz" in n and "frost.txt" not in n and "merged.ymd" not in n)(
        d.sh(f"ls -a {DICTS}").split()) or None, timeout=180, step=0.1, what="frost.txt.gz、合成没完")
    d.sh(f"am force-stop {PKG}")
    killed = d.sh(f"ls -a {DICTS}").split()
    if "frost.txt" in killed or "frost.txt.gz" not in killed or "merged.ymd" in killed:
        raise Blocked(f"停下时不在转换之后、合成完之前：{killed}")
    d.open_page("text")
    d.switch_im("拼音")
    caught = []

    def look(x):
        for t in x.texts(where="all"):
            if ("正在升级词库格式" in t or "正在更新" in t or t == "词库格式已升级") and t not in caught:
                caught.append(t)
        return "merged.ymd" in dict_files(d) or None

    d.wait(look, timeout=300, step=0.3, what="merged.ymd")
    end = time.time() + 3
    while time.time() < end and "词库格式已升级" not in caught:
        look(d.screen())
    files = dict_files(d)
    hidden = d.sh(f"ls -a {DICTS}").split()
    c = abazhou(d)
    # F3672: the marker is what tells the next start it is an upgrade; without it a pass could be the bar misread
    check(".merged.upgrading" in killed, f"强杀后没有 .merged.upgrading：{killed}")
    check(any("正在升级词库格式" in t for t in caught) and not any("正在更新" in t for t in caught),
          f"再开后的空闲栏 {caught}")
    check("词库格式已升级" in caught, f"没提示词库格式已升级：{caught}")
    check(".merged.upgrading" not in hidden and "frost.txt.gz" in files, f"词库目录 {hidden}")
    check("阿坝州" in c, f"abazhou 的候选 {c[:6]}")
    return f"停下时 {bar!r}，目录 {killed}；再开 {caught}；目录 {hidden}"


@case("WD-14t", base="B0-设置", mutates=True)
def wd14t(d):
    """design RS1: rows the page listed as 0.3.1's .txt, deleted and turned off while converted"""
    # the page's open starts the conversion, and the text page is a dozen seconds away and back: twice the padding
    plant_031(d, slow_frost(d, 2 * SLOW_PAD_MB), ("small.txt", "氪喵星港 ke'miao'xing'gang 0.0"))
    home(d, "词库与短语")
    listed = dictionaries(d)
    if not any("small" in r for r in listed):
        raise Blocked(f"词库页没列出 small：{listed}")
    bar = upgrading(d)
    if not converting(bar):
        raise Blocked(f"看到时已过了转换：{bar!r}")
    dict_page(d)
    # F3612: the rows must still be the .txt ones the page listed, else RS1's by-name path is not hit
    before = dict_files(d)
    if "frost.txt" not in before:
        raise Blocked(f"点之前 frost 已转换完：{before}")
    d.tap_text("small")
    d.tap_text("删除", scroll=False, after=0.2)
    d.tap_text("确定", scroll=False, after=0.2)
    d.tap_text("frost")
    d.tap_text("关闭", scroll=False, after=0.2)
    said = []
    # F3615: the section says 正在升级词库格式 during UPGRADE (正在更新 after a toggle's own compile);
    # the directory settles too: no .txt left to convert, no tmp
    for _ in range(180):
        texts = d.screen().texts()
        said.extend(t for t in texts if "没能更新" in t)
        files = dict_files(d)
        if not any("正在更新" in t or "正在升级词库格式" in t for t in texts) and not any(
                f.endswith((".txt", ".txt.disable", ".tmp")) for f in files):
            break
        time.sleep(1)
    else:
        raise Fail(f"三分钟没做完：目录 {files}")
    rows = dictionaries(d)
    files = dict_files(d)
    k = page_cands(d, "kemiaoxinggang")
    d.clear()
    i = next((i for i, r in enumerate(rows) if r == "frost"), None)
    check(not said, f"弹了 {said[:2]}")
    check(not any("small" in r for r in rows), f"删除后词库页 {rows}")
    check(i is not None and i + 1 < len(rows) and "已关闭" in rows[i + 1], f"frost 的状态 {rows}")
    check("frost.txt.gz.disable" in files and not any(
        f.startswith("small") or f in ("frost.txt", "frost.txt.gz") for f in files), f"词库目录 {files}")
    check("氪喵星港" not in words(k), f"删除的词还在 {words(k)[:4]}")
    # F3644: frost's gzip kept the old text's time; turned on again with every other dictionary off it
    # is an update (正在更新), never told as an upgrade
    home(d, "词库与短语")
    d.tap_text("frost")
    d.tap_text("开启", scroll=False, after=0.2)
    told = []
    for _ in range(360):
        texts = d.screen().texts(where="all")
        told.extend(t for t in texts if ("正在更新" in t or "升级" in t) and t not in told)
        if told and not any("正在更新" in t or "正在升级词库格式" in t for t in texts):
            break
        time.sleep(0.5)
    else:
        raise Fail(f"开启 frost 三分钟没合好：{told}")
    # F3683: 词库格式已升级 is told when the compile ends, so it can land just after the progress text goes
    for _ in range(4):
        time.sleep(0.5)
        told.extend(t for t in d.screen().texts(where="all") if "升级" in t and t not in told)
    check(any("正在更新" in t for t in told) and not any("升级" in t for t in told), f"开启 frost 时 {told}")
    return f"点之前目录 {before}；词库页 {rows}；目录 {files}；再开启 {told}"


@case("WD-14w", base="B0-设置", mutates=True)
def wd14w(d):
    """design RS2: an import while 0.3.1's frost.txt is converted waits for it (正在转换) and ends"""
    plant_031(d, slow_frost(d))
    push(d, "dict-words.txt")
    bar = upgrading(d)
    if not converting(bar):
        raise Blocked(f"看到时已过了转换：{bar!r}")
    _, s = import_file(d, "dict-words.txt")
    o = next((n for n in s.nodes if n.cls == "CheckedTextView" and n.text.startswith("基础词库")), None)
    if not o:
        raise Blocked(f"并入哪个词库里没有基础词库：{[n.text[:12] for n in s.nodes if n.text][:8]}")
    d.tap(o.c, 0.5)
    # F3657: the commit must land while frost.txt is still being converted, else RS2's wait is not hit
    before = dict_files(d)
    if "frost.txt" not in before:
        raise Blocked(f"点导入词库之前 frost 已转换完：{before}")
    btn = next(n for n in d.screen().nodes if n.text == "导入词库" and n.cls == "Button")
    d.tap(btn.c, 0.3)
    # F3673: the conversion may end between the look above and the tap: then no 正在转换 is owed
    after = dict_files(d)
    stages, said = [], []
    for _ in range(600):
        texts = d.screen().texts(where="all")
        for t in texts:
            st = re.sub(r"\s*\d+%$", "", t)
            if st.startswith("正在") and st not in stages:
                stages.append(st)
            if "没能更新" in t:
                said.append(t)
        if not any("正在导入" in t for t in texts):
            break
        time.sleep(0.5)
    else:
        raise Fail(f"导入五分钟没完：{stages}")
    if not any(s.startswith("正在转换") for s in stages) and "frost.txt" not in after:
        raise Blocked(f"点导入词库时 frost 已转换完：{after}")
    files = upgraded(d)
    k = kemiaotangpu(d)
    c = abazhou(d)
    rows = dictionaries(d)
    check(not said, f"弹了 {said[:2]}")
    # F3659: the dialog said it waited for the conversion
    check(any(s.startswith("正在转换") for s in stages), f"对话框没显示正在转换：{stages}")
    check("氪喵糖铺" in k, f"kemiaotangpu {k[:4]}")
    check("阿坝州" in c, f"abazhou 的候选 {c[:6]}")
    check(any("frost" in r for r in rows) and any("dict-words" in r for r in rows), f"词库页 {rows}")
    check({"frost.txt.gz", "dict-words.txt.gz"} <= set(files), f"词库目录 {files}")
    return f"开始 {bar!r}；对话框 {stages}；目录 {files}"

@case("WD-14t2", base="B0-设置", mutates=True)
def wd14t2(d):
    """F3693 (F3660, F3674, F3675): a converted text whose delete was refused, noted in .merged.left:
    the next compile only deletes it and drops the note; not converted again, not told as an upgrade.
    The refusal itself cannot be made on redroid; the state it leaves is written here by hand.
    F3695: a start with nothing changed does not compile (F3687, F3707: the note stays); the page's open
    retries it; planted again with the page open, frost turned off and on (F3704, F3705: a toggle beside
    a left text, not converted, no 升级) makes the compile, waited out (F3706)"""
    plant_031(d, frost(d))
    upgrading(d)
    upgraded(d)
    d.sh(f"am force-stop {PKG}")
    text = f"{DICTS}/frost.txt"

    def plant():
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "frost.txt")
            with open(path, "w") as f:
                f.write("氪喵星港 ke'miao'xing'gang 0.0\n")
            d.adb("push", path, text)
        # a whole second, so File.lastModified() is that second's ms and the leftKey written below matches
        sec = int(time.time()) - 60
        d.sh(f"touch -d @{sec} {text}")
        if ".000000000" not in d.sh(f"stat -c %y {text}") or d.sh(f"stat -c %Y {text}").strip() != str(sec):
            raise Blocked(f"改不了 frost.txt 的时间：{d.sh(f'stat {text}')}")
        size = d.sh(f"stat -c %s {text}").strip()
        # MergedDictionary.leftKey: the enabled text's name, length, mtime in ms, tab-separated, one per line
        d.sh(f"printf 'frost.txt\\t{size}\\t{sec}000\\n' > {DICTS}/.merged.left")
        noted = d.sh(f"cat {DICTS}/.merged.left").strip()
        if noted != f"frost.txt\t{size}\t{sec}000":
            raise Blocked(f".merged.left 写得不对：{noted!r}")

    def listed():
        return d.sh(f"ls -a {DICTS}").split()

    plant()
    d.open_page("text")
    d.switch_im("拼音")
    caught = []

    def look(x):
        texts = x.texts(where="all")
        caught.extend(t for t in texts
                      if ("正在升级词库格式" in t or t == "词库格式已升级") and t not in caught)
        return texts

    end = time.time() + 2
    while time.time() < end:
        look(d.screen())
    # F3707: a start with nothing changed does not compile, so the note and its text are still there
    there = listed()
    check(".merged.left" in there and "frost.txt" in there, f"没改动的启动后目录 {there}")
    # the page's open retries the left text; let it end before planting again
    home(d, "词库与短语")
    d.wait(lambda x: (look(x), ".merged.left" not in listed())[1] or None,
           timeout=120, step=0.3, what="词库页的重试删掉 .merged.left")
    # F3705: planted again while the page stays open, so the toggle runs beside a left text (F3704, F3686)
    plant()
    d.tap_text("frost")
    d.tap_text("关闭", scroll=False, after=1.0)
    look(d.screen())
    d.tap_text("frost")
    # the turn-off's compile is running: its menu can come up later than a dump right after the tap
    d.wait(lambda x: x.find(text="开启") or None, timeout=5, step=0.2, what="frost 的菜单")
    d.tap_text("开启", scroll=False, after=0.3)
    # F3706: turned on, frost's whole compile runs again (half a minute on release); wait it out like WD-14t
    seen = False
    for _ in range(360):
        texts = look(d.screen())
        busy = any("正在更新" in t or "正在升级词库格式" in t for t in texts)
        seen = seen or busy
        # only once its progress was seen: the turn-off compile already dropped the note, and a dump taken
        # before this compile shows anything would end the wait at once (review)
        if seen and not busy:
            break
        time.sleep(0.5)
    else:
        raise Fail(f"开启 frost 三分钟没合好：{caught}")
    # F3683: 词库格式已升级 is told when the compile ends, so it can land just after the progress text goes
    end = time.time() + 2
    while time.time() < end:
        look(d.screen())
    rows = dictionaries(d)
    i = next((i for i, r in enumerate(rows) if "frost" in r), None)
    status = rows[i + 1] if i is not None and i + 1 < len(rows) else ""
    hidden = d.sh(f"ls -a {DICTS}").split()
    c = abazhou(d)
    k = words(page_cands(d, "kemiaoxinggang"))
    d.clear()
    check(not caught, f"提示了升级：{caught}")
    check("frost.txt" not in hidden and "frost.txt.gz" in hidden and ".merged.left" not in hidden,
          f"词库目录 {hidden}")
    check(status and "已关闭" not in status, f"词库页 {rows}")
    check("阿坝州" in c, f"abazhou 的候选 {c[:6]}")
    check("氪喵星港" not in k, f"留下的文本又被转换了：{k[:4]}")
    return f"目录 {hidden}；frost 行 {status!r}；abazhou {c[:3]}；kemiaoxinggang {k[:2]}"


@case("WD-14x", base="B0-设置", mutates=True)
def wd14x(d):
    """F3646: a base dictionary's .txt.gz cut short is left out and named on its row; the others still
    compile; deleted and imported again, it is back"""
    import_dict_words(d)
    push(d, "rime-words.dict.yaml")
    import_file(d, "rime-words.dict.yaml", "基础词库")
    d.sh(f"am force-stop {PKG}")
    gz = f"{DICTS}/dict-words.txt.gz"
    # the first 30 bytes: a gzip header with its stream cut off (EOFException), as a copy broken off
    d.sh(f"head -c 30 {gz} > {gz}.cut && mv {gz}.cut {gz}")
    if d.sh(f"stat -c %s {gz}").strip() != "30":
        raise Blocked(f"截不短 {gz}：{d.sh(f'ls -l {DICTS}')}")
    d.open_page("text")
    d.switch_im("拼音")
    # F3663: 氪喵星港 is in the merged.ymd from before the kill, so it says nothing of the recompile;
    # .merged.unreadable is written only by the compile that skipped the cut file
    d.wait(lambda _: ".merged.unreadable" in d.sh(f"ls -a {DICTS}").split() or None,
           timeout=120, step=1.0, what=".merged.unreadable")
    said, k = [], []
    for _ in range(40):
        said.extend(t for t in d.screen().texts(where="all") if "没能更新" in t)
        k = words(page_cands(d, "kemiaoxinggang"))
        d.clear()
        if "氪喵星港" in k:
            break
        time.sleep(3)
    rows = dictionaries(d)
    i = next((i for i, r in enumerate(rows) if "dict-words" in r), None)
    status = rows[i + 1] if i is not None and i + 1 < len(rows) else ""
    t = words(page_cands(d, "kemiaotangpu"))
    d.clear()
    dict_row_menu(d, "删除")
    import_dict_words(d)
    again = kemiaotangpu(d)
    rows2 = dictionaries(d)
    j = next((j for j, r in enumerate(rows2) if "dict-words" in r), None)
    status2 = rows2[j + 1] if j is not None and j + 1 < len(rows2) else ""
    check(not said, f"弹了 {said[:2]}")
    check("氪喵星港" in k, f"别的词库的词没打出来：{k[:4]}")
    check("读不了，没有并入：删掉后重新导入" in status, f"坏词库的行 {rows}")
    check("氪喵糖铺" not in t, f"坏词库的词还在 {t[:4]}")
    check("氪喵糖铺" in again and "读不了" not in status2 and sum("dict-words" in r for r in rows2) == 1,
          f"重新导入后 {again[:4]}，词库页 {rows2}")
    return f"截短后 dict-words 行 {status!r}，kemiaoxinggang {k[:2]}；重新导入后 {status2!r}，kemiaotangpu {again[:2]}"


def row_status(rows, name):
    i = next((i for i, r in enumerate(rows) if r == name), None)
    return rows[i + 1] if i is not None and i + 1 < len(rows) else ""


@case("WD-14x2", base="B0-设置", mutates=True)
def wd14x2(d):
    """F3661 (F3648): a base dictionary's gzip cut in the middle: the lines before the cut are merged
    and the row says 文件损坏，并入了 N 个词, not 读不了"""
    push(d, "big-words.txt")
    import_file(d, "big-words.txt", "基础词库")
    if not any("big-words" in r for r in dictionaries(d)):
        raise Blocked(f"前提没到：基础词库一节 {dictionaries(d)}")
    d.sh(f"am force-stop {PKG}")
    gz = f"{DICTS}/big-words.txt.gz"
    size = int(d.sh(f"stat -c %s {gz}").strip() or 0)
    # two thirds: the first lines' deflate blocks whole, the stream cut off after them
    d.sh(f"head -c {size * 2 // 3} {gz} > {gz}.cut && mv {gz}.cut {gz}")
    if int(d.sh(f"stat -c %s {gz}").strip() or 0) != size * 2 // 3:
        raise Blocked(f"截不短 {gz}：{d.sh(f'ls -l {DICTS}')}")
    d.open_page("text")
    d.switch_im("拼音")
    d.wait(lambda _: ".merged.unreadable" in d.sh(f"ls -a {DICTS}").split() or None,
           timeout=120, step=1.0, what=".merged.unreadable")
    # F3681: .merged.unreadable is written before the engine reloads on the fcitx thread, so the
    # first keys may still reach the old reader; retry as WD-14x does
    j, x = [], []
    for _ in range(40):
        j = words(page_cands(d, "jingmiaoyu"))
        d.clear()
        # F3682: 橘星屿 is big-words' last word, past the cut: gone only if the cut file was recompiled
        x = words(page_cands(d, "juxingyu"))
        d.clear()
        if "鲸喵屿" in j and "橘星屿" not in x:
            break
        time.sleep(3)
    rows = dictionaries(d)
    status = row_status(rows, "big-words")
    m = re.search(r"文件损坏，并入了 ([\d,]+) 个词，可能不全：删掉后重新导入", status)
    n = int(m.group(1).replace(",", "")) if m else 0
    check(m is not None and 0 < n < 1001, f"截断的词库的行 {rows}")
    check("鲸喵屿" in j, f"截断前的词没打出来：{j[:4]}")
    check("橘星屿" not in x, f"截断后的词还在（没重新合）：{x[:4]}")
    return f"截到 {size * 2 // 3}/{size} 字节，行 {status!r}，jingmiaoyu {j[:2]}，juxingyu {x[:2]}"


@case("WD-14y", base="B0-设置", mutates=True)
def wd14y(d):
    """F3662 (F3645): a 0.3.1 text whose gzip cannot be written is kept and its row says 没转换成…,
    not 读不了; once it can be, opening the page again converts it. A full disk cannot be made safely
    on redroid: a non-empty directory stands where the gzip is written (sweep's delete leaves it,
    the write fails with an IOException as a full disk's does)"""
    d.sh(f"am force-stop {PKG}")
    d.sh(f"mkdir -p {DICTS}; rm -f {DICTS}/merged.ymd")
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "small.txt")
        with open(path, "w") as f:
            f.write("氪喵星港 ke'miao'xing'gang 0.0\n")
        d.adb("push", path, f"{DICTS}/small.txt")
    block = f"{DICTS}/small.txt.gz.convert.tmp"
    d.sh(f"mkdir -p {block}/full && touch {block}/full/x")
    d.open_page("text")
    d.switch_im("拼音")
    d.wait(lambda _: ".merged.unwritten" in d.sh(f"ls -a {DICTS}").split() or None,
           timeout=120, step=1.0, what=".merged.unwritten")
    k = words(page_cands(d, "kemiaoxinggang"))
    d.clear()
    # the page's open retries and fails again: the row is listed again once that compile ends
    status = ""
    for _ in range(30):
        status = row_status(dictionaries(d), "small")
        if "没转换成" in status:
            break
        time.sleep(2)
    kept = dict_files(d)
    d.sh(f"rm -r {block}")
    d.open_page("text")
    home(d, "词库与短语")
    files = d.wait(lambda _: (lambda f: f if "small.txt.gz" in f and "small.txt" not in f else None)(dict_files(d)),
                   timeout=120, step=1.0, what="small.txt.gz")
    again = row_status(dictionaries(d), "small")
    k2 = words(page_cands(d, "kemiaoxinggang"))
    d.clear()
    check("没转换成（手机空间不够？）：腾出空间后重新打开这一页" in status, f"没转换成的行 {status!r}")
    check("small.txt" in kept, f"没转换成时原文本不在了：{kept}")
    check("氪喵星港" not in k, f"没转换成的词进了候选 {k[:4]}")
    check("没转换成" not in again and "读不了" not in again, f"腾出空间再开后 {again!r}")
    check("氪喵星港" in k2, f"腾出空间再开后 kemiaoxinggang {k2[:4]}")
    return f"行 {status!r}，目录 {kept}；腾出后 {again!r}，目录 {files}"



def old_backup(d):
    """备份全部 made with dict-words in the base, rewritten as 0.3.2 wrote it (the .txt plain) and
    put in 下载 as youmo_032.zip"""
    backup = script("15-privacy-backup")
    _, new, _ = backup.backup(d)
    if not new:
        raise Blocked("备份全部没在下载里留下文件")
    gz = "external/data/pinyin/dictionaries/dict-words.txt.gz"
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, new[0]), os.path.join(tmp, "youmo_032.zip")
        d.adb("pull", f"{DOWNLOAD}/{new[0]}", src, timeout=120)
        with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            if gz not in zin.namelist():
                raise Blocked(f"备份里没有 {gz}：{[n for n in zin.namelist() if 'dictionaries' in n]}")
            for e in zin.infolist():
                data = zin.read(e)
                if e.filename == gz:
                    zout.writestr(gz[:-3], gzip.decompress(data))
                else:
                    zout.writestr(e, data)
        d.adb("push", dst, f"{DOWNLOAD}/youmo_032.zip")
    return "youmo_032.zip"


def kemiaotangpu(d, tries=20):
    """the restored dictionary's word, typed until its compile is done"""
    k = []
    for _ in range(tries):
        k = words(page_cands(d, "kemiaotangpu"))
        d.clear()
        if "氪喵糖铺" in k:
            break
        time.sleep(3)
    return k


@case("WD-14u", base="B0-设置", mutates=True)
def wd14u(d):
    """design RM1: a 0.3.2 backup on a fresh install, its .txt converted and compiled without the 词库 page"""
    import_dict_words(d)
    name = old_backup(d)
    reset(d, "B0-设置", "设置")
    script("15-privacy-backup").restore(d, name)
    d.open_page("text")
    d.switch_im("拼音")
    k = kemiaotangpu(d)
    files = dict_files(d)
    rows = dictionaries(d)
    i = rows.index("dict-words") if "dict-words" in rows else -1
    check("氪喵糖铺" in k, f"kemiaotangpu {k[:4]}")
    check("dict-words.txt.gz" in files and "merged.ymd" in files and "dict-words.txt" not in files, f"词库目录 {files}")
    check(i >= 0 and i + 1 < len(rows) and "已开启" in rows[i + 1] and "个词" in rows[i + 1], f"基础词库一节 {rows}")
    return f"kemiaotangpu {k[:2]}；目录 {files}；词库页 {rows[i:i + 2]}"


@case("WD-14k", base="B0-设置", mutates=True)
def wd14k(d):
    """a third column of log probabilities: the larger first, not the file's order"""
    push(d, "log-words.txt")
    _, s = import_file(d, "log-words.txt")
    title = title_of(s)
    d.back()
    import_file(d, "log-words.txt", "基础词库")
    page_cands(d, "kemiaotangguo")
    c = words(d.all_cands())
    d.clear()
    at = [c.index(w) if w in c else 99 for w in ("氪喵堂果", "氪喵糖果")]
    check("第三列是对数概率" in title and "4 个词" in title, f"标题 {title!r}")
    check(at[0] < at[1] < 99, f"kemiaotangguo 的候选 {c[:6]}")
    return f"标题 {title.splitlines()[-1]!r}；候选 {c[:3]}"


@case("WD-14l", base="B0-设置", mutates=True)
def wd14l(d):
    """a binary file refused before the choice; pinyin first and run together read"""
    push(d, "binary-words.bin", "pinyin-first.txt")
    before = dictionaries(d)
    chosen, s = import_file(d, "binary-words.bin")
    why = messages(s) if chosen is None else []
    d.back()
    after = dictionaries(d)
    _, s = import_file(d, "pinyin-first.txt")
    title = title_of(s)
    d.back()
    import_file(d, "pinyin-first.txt", "基础词库")
    rows = dictionaries(d)
    page_cands(d, "kemiaoyu")
    c = words(d.all_cands())
    d.clear()
    check(chosen is None and any("格式不支持：这不是文本文件" in m for m in why), f"binary：选择「{chosen}」 {why[:2]}")
    check(after == before, f"词库页 {before} → {after}")
    check("拼音在前" in title and "拼音连写" in title and "3 个词" in title, f"标题 {title!r}")
    check(any("pinyin-first" in r for r in rows), f"基础词库一节 {rows}")
    check("氪喵屿" in c, f"kemiaoyu 的候选 {c[:6]}")
    return f"{why[:1]}；标题 {title.splitlines()[-1]!r}；候选 {c[:3]}"


@case("WD-14m", base="B0-设置", mutates=True)
def wd14m(d):
    """a file named like a base dictionary already there: asked first, before it is read"""
    import_dict_words(d)
    d.back()
    # the same name, five words the base lacks: dict-words.txt's own are known through it and none would import
    d.adb("push", os.path.join(FIXTURES, "same-name", "dict-words.txt"), f"{DOWNLOAD}/dict-words.txt")
    seen = progress(d)
    seen.clear()
    chosen, s = import_file(d, "dict-words.txt")
    asked = messages(s) if chosen is None else []
    read_before = list(seen)
    btn = s.find(text="并入用户词库")
    if not btn:
        raise Blocked(f"没有「并入用户词库」：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(btn.c, 0.5)
    done = d.wait(lambda x: next((m for m in messages(x) if "导入完成" in m), None) or
                  next((t for t in x.texts(where="all") if "导入完成" in t), None), timeout=120, step=0.5, what="导入完成")
    d.back()
    rows = dictionaries(d)
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    d.back()
    push(d, "dict-words.txt")
    check(chosen is None and any("基础词库里已有名为「dict-words」的词库。改为并入用户词库吗？" in m for m in asked),
          f"选文件后：选择「{chosen}」 {asked[:2]}")
    check(not read_before, f"问之前就读了文件：{read_before[:3]}")
    check("导入 5 个词" in done, f"提示 {done!r}")
    check(any("dict-words.txt" in t for t in batches), f"导入的词 {batches[:5]}")
    check(sum("dict-words" in r for r in rows) == 1, f"基础词库一节 {rows}")
    return f"问「{asked[:1]}」；{done!r}"


@case("WD-14n", base="B0-设置", mutates=True)
def wd14n(d):
    """Rime's percentages read (as no weight), not lines unread"""
    push(d, "rime-percent.dict.yaml")
    chosen, s = import_file(d, "rime-percent.dict.yaml")
    title = title_of(s)
    d.back()
    import_file(d, "rime-percent.dict.yaml", "基础词库")
    rows = dictionaries(d)
    page_cands(d, "kemiaodenggang")
    c = words(d.all_cands())
    d.clear()
    check(chosen is not None and "Rime 词库" in title and "3 个词" in title and "读不懂" not in title, f"选择「{chosen}」标题 {title!r}")
    check(any("rime-percent" in r for r in rows), f"基础词库一节 {rows}")
    check("氪喵灯港" in c, f"候选 {c[:6]}")
    return f"标题 {title.splitlines()[-1]!r}；候选 {c[:3]}"


@case("WD-14o", base="B0-设置", mutates=True)
def wd14o(d):
    """the user dictionary's 100k cap counts the imported words already there plus this file's"""
    push(d, "big-words.txt")
    c = list(zip("啊吧擦大饿发嘎哈鸡卡", "a ba ca da e fa ga ha ji ka".split()))
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, "user-99k.txt")
        with open(local, "w") as f:
            for w in itertools.islice(itertools.product(c, repeat=6), 99500):
                f.write("".join(x[0] for x in w) + " " + "'".join(x[1] for x in w) + "\n")
        d.adb("push", local, f"{DOWNLOAD}/user-99k.txt", timeout=120)
    _, first = import_file(d, "big-words.txt", "用户词库")
    d.back()
    _, over = import_file(d, "user-99k.txt", "用户词库")
    d.back()
    home(d, "词库与短语", "导入的词")
    batches = d.screen().texts()
    d.back()
    drop_batch(d, "big-words.txt")
    d.back()
    _, again = import_file(d, "user-99k.txt", "用户词库")
    d.back()
    check(any("导入 1001 个词" in m for m in first), f"big-words {first[:2]}")
    check(any("用户词库里导入的词已近上限" in m for m in over), f"超过时 {over[:2]}")
    check(not any("user-99k" in t for t in batches), f"导入的词 {batches[:5]}")
    check(any("导入 99500 个词" in m for m in again), f"撤掉后 {again[:2]}")
    return f"{over[:1]}；撤掉后 {again[:1]}"


@case("WD-14p", base="B0-设置", mutates=True)
def wd14p(d):
    """UTF-8 traditional words go into the base; the same as Big5 (read as GBK) are refused"""
    push(d, "trad-words.txt", "trad-big5.txt")
    import_file(d, "trad-words.txt", "基础词库")
    rows = dictionaries(d)
    page_cands(d, "kemiaodengta")
    c = words(d.all_cands())
    d.clear()
    _, why = import_file(d, "trad-big5.txt", "基础词库")
    d.back()
    after = dictionaries(d)
    check(any("trad-words" in r for r in rows), f"基础词库一节 {rows}")
    check("氪喵燈塔" in c, f"kemiaodengta 的候选 {c[:6]}")
    check(any("格式不支持：字和读音大多对不上" in m for m in why), f"Big5 {why[:2]}")
    check(not any("trad-big5" in r for r in after), f"基础词库一节 {after}")
    return f"候选 {c[:3]}；Big5 {[m for m in why if '格式' in m][:1]}"


@case("WD-14q", base="B0-设置", mutates=True)
def wd14q(d):
    """text-only lines: their own item in the title; too many and the base refuses the file"""
    push(d, "no-pinyin-some.txt", "no-pinyin-most.txt")
    _, s = import_file(d, "no-pinyin-some.txt")
    title = title_of(s)
    d.back()
    before = dictionaries(d)
    _, why = import_file(d, "no-pinyin-most.txt", "基础词库")
    d.back()
    after = dictionaries(d)
    check("60 个词" in title and "1 个词没有拼音（只能并入用户词库）" in title, f"标题 {title!r}")
    check(any("格式不支持：有些行没有拼音" in m for m in why), f"no-pinyin-most {why[:2]}")
    check(after == before, f"词库页 {before} → {after}")
    return f"标题 {title.splitlines()[-1]!r}；{[m for m in why if '格式' in m][:1]}"


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


@case("WD-18", base="B0-设置", mutates=True)
def wd18(d):
    d.step("加 dz")
    dz(d)
    c0 = words(page_cands(d, "dz"))
    check(c0[:1] == ["北京市海淀区"], f"候选 {c0[:3]}")
    if len(c0) < 2:
        raise Blocked(f"dz 只有 {c0}")
    d.step("选两次第二个")
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
    d.step("编辑")
    d.hide_keyboard()
    home(d, "词库与短语", "自定义短语")
    e = open_row("北京市海淀区")
    focus(d, e[2])
    clear_field(d, 2)
    type_cn(d, "上海市")
    d.hide_keyboard()
    ok(d)
    c1 = page_cands(d, "dz")
    check(first(c1) == "上海市", f"改后 {c1[:3]}")
    d.step("删除")
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
    check("上海市" not in c2 and "北京市海淀区" not in c2, f"删除后 {c2[:4]}")
    return f"dz {c0[:2]}；W=「{w}」两次后 {c[:2]}；改后 {c1[:2]}；删除后 {c2[:3]}"


@case("WD-19", base="B0-设置", mutates=True)
def wd19(d):
    dz(d)
    add_im(d, "九键拼音")
    add_im(d, "双拼")
    d.open_page("text")
    d.switch_im("九键")
    for k in ("3", "9"):
        d.press(k)
    t9 = words(d.all_cands())
    d.clear()
    d.switch_im("双拼")
    d.type("dz")
    sp = words(d.all_cands())
    d.clear()
    check("北京市海淀区" not in t9, f"九键 39 {t9[:6]}")
    check("北京市海淀区" in sp, f"双拼 dz {sp[:6]}")
    return f"九键 {t9[:4]}；双拼 {sp[:4]}"


# ---------------------------------------------------------------- the user lexicon's pages

def marks(d):
    """WD-21's 前提: PY-34 (汪小明 your word), the fifth of shi pinned (固定首位), PY-29 (是 blocked);
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


@case("WD-33", base="B0-设置", mutates=True)
def wd33(d):
    add_phrase(d, "nihao", [("cn", "你好")], order="2")
    add_phrase(d, "nihao", [("cn", "您好呀")], order="3")
    c = words(page_cands(d, "nihao"))
    d.clear()
    check(c.count("你好") == 1, f"nihao {c[:5]}：「你好」出现 {c.count('你好')} 次")
    check(c[2:3] == ["您好呀"], f"nihao {c[:5]}：「您好呀」不是第三个")
    return f"nihao {c[:4]}"


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
    cc = _wd25_setup(d, cun=True)
    d.step("不勾全部清空")
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
    d.step("勾上全部清空")
    msg = _clear_learned(d, True)
    w = _cands(d, "wangxiaoming")
    d.type("shi")
    s2 = words(d.all_cands())
    d.clear()
    d.switch_im("五笔")
    d.type("fghy")
    f = words(d.all_cands())
    d.clear()
    check("屏蔽的 2 个词会重新出现" in msg, f"确认框「{msg[:200]}」")
    # ticked, the message lists what goes; what stays is the custom phrases alone
    kept = msg.split("留下：")[-1]
    check("回到刚装好，清掉" in msg and "自定义短语" in kept and "固定" not in kept, f"勾上后的确认框「{msg[:200]}」")
    check(first(w) == "王小明" and "汪小明" not in w, f"wangxiaoming {w[:4]}")
    check("是" in s2 and first(s2) != cc, f"shi {s2[:5]}（C=「{cc}」）")
    check("寸" in f, f"fghy {f[:5]}")
    return f"不勾：他 dl {dl[:2]}、wxm {x[:3]}、shi {s[:3]}；勾上：wangxiaoming {w[:2]}、shi {s2[:3]}、fghy {f[:3]}"



@case("WD-30", base="B0-设置", mutates=True)
def wd30(d):
    d.step("加两个词")
    add_word(d, "澜境花园", "lan jing hua yuan")
    add_word(d, "幽默")
    d.back()
    n0 = count_of(d, "手动添加的词")
    d.tap_text("手动添加的词", after=1.0)
    d.step("搜索")
    r1 = search(d, "lan")
    r2 = search(d, "幽")
    has1 = [x for x in r1 if "澜境花园" in x or "幽默" in x]
    has2 = [x for x in r2 if "澜境花园" in x or "幽默" in x]
    check(has1 and all("澜境花园" in x for x in has1), f"搜 lan：{r1[:6]}")
    check(has2 and all("幽默" in x for x in has2), f"搜 幽：{r2[:6]}")
    d.step("多选删除")
    # back to the whole list: the page again, the search box empty
    d.back()
    d.tap_text("手动添加的词", after=1.0)
    s = d.screen()
    rows = [n for n in s.app() if n.text.startswith(("澜境花园", "幽默"))]
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
    s = d.screen()
    asked = " ".join(n.text for n in s.nodes if n.rid == "message")
    conf = next((n for n in s.nodes if n.rid == "button1"), None)
    if not conf:
        raise Fail(f"删除前没有确认框：{s.texts()[:6]}")
    if n0 != "2 个词":
        # an unmet premise, not a broken delete: the list must hold these two only for it to end empty
        raise Blocked(f"删之前词数 {n0}，不止这两个，删后空不空看不出")
    d.tap(conf.c, 0.8)
    after = d.screen().texts(where="all")
    check("2" in asked, f"确认框「{asked}」")
    check(not any(r.startswith(("澜境花园", "幽默")) for r in after), f"删除后 {after[:6]}")
    check(any("已删掉 2 个" in t for t in after), f"删后的提示 {after[-6:]}")
    d.step("撤销")
    d.tap_text("撤销", where="all", scroll=False, after=1.2)
    back_rows = list_texts(d)
    d.back()
    n1 = count_of(d, "手动添加的词")
    c = page_cands(d, "lanjinghuayuan")
    d.clear()
    check(any(r.startswith("澜境花园") for r in back_rows) and any(r.startswith("幽默") for r in back_rows),
          f"撤销后 {back_rows[:6]}")
    check(n1 == n0, f"词数 {n0} → {n1}")
    check(first(c) == "澜境花园", f"lanjinghuayuan {c[:3]}")
    return f"搜索对；删后提示、撤销后词数 {n1}；lanjinghuayuan {c[:2]}"

