"""uat/13-words.md (词库与短语). The .md is the source of truth; each function follows its case's
前提/操作/预期. Files for the import cases are pushed from uat/fixtures to 下载 and picked in the
system file picker."""
import hashlib
import os
import re
import statistics
import tempfile
import time

from uat import Blocked, Fail, PKG, case, check, first, home, script, sym_char, in_picker

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
    d.tap(n.c, 0.3)
    # the dialog's fields, not a fixed pause: on a busy host 1.2 s was too short (VO-10's
    # IndexError on edits(d)[0])
    d.wait(lambda s: edits(d, s), 10, step=0.2, what="the add dialog's fields")


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

case("WD-02", needs="jvm:SettingsUatTest.aBlankPinyinIsFilledIn")(lambda d: None)


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


case("WD-06", needs="jvm:LexiconUatTest.aWordBlockedInTheSettingsIsNoCandidate")(lambda d: None)


case("WD-07", needs="jvm:LexiconUatTest.aBlockedWordRestoredIsBack")(lambda d: None)


case("WD-08", needs="jvm:LexiconUatTest.aLearnedWordDeletedIsGone")(lambda d: None)


case("WD-09", needs="jvm:LexiconUatTest.aWordListImportedIntoTheUserDictionary")(lambda d: None)


case("WD-09b", needs="jvm:LexiconUatTest.aThousandWordsImportedIntoTheUserDictionary")(lambda d: None)


case("WD-10", needs="jvm:LexiconUatTest.anExportImportedElsewhereHasTheAddedBlockedAndLearned")(lambda d: None)


case("WD-10a", needs="jvm:LexiconUatTest.aBackupKeepsTheImportedBatch")(lambda d: None)


# ---------------------------------------------------------------- imported dictionaries

def import_dict_words(d):
    push(d, "dict-words.txt")
    import_file(d, "dict-words.txt", "基础词库")
    rows = dictionaries(d)
    if not any("dict-words" in r for r in rows):
        raise Blocked(f"前提没到：基础词库一节 {rows}")
    return rows


case("WD-11", needs="jvm:DictionaryUatTest.aTextDictionaryIntoTheBase")(lambda d: None)


case("WD-13", needs="jvm:DictionaryUatTest.turnedOffThenDeleted")(lambda d: None)


case("WD-14b", needs="jvm:LexiconUatTest.aDictFileIntoTheUserDictionaryRanksBecomesYoursDroppedAndUndone")(lambda d: None)


@case("WD-14z", base="B0-设置", mutates=True)
def wd14z(d):
    # into the user dictionary the .scel is read straight into lines (ImportFiles.Scel.read); the
    # conversion to a text dictionary is the base path's, WD-14z2
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
    check(any(re.search(r"导入\s*2\s*个词", m or "") for m in msgs), f"提示 {msgs[:3]}")
    check(any("extra.scel" in t for t in batches), f"导入的词 {batches[:5]}")
    check("琮璟" in c and c.index("琮璟") > 0, f"congjing 的候选 {c[:6]}")
    return f"提示 {msgs[:1]}；congjing {c[:4]}"


@case("WD-14z2", base="B0-设置", mutates=True)
def wd14z2(d):
    # into the base the .scel is converted to a text dictionary first (Scel.writeTextDictionary,
    # streamed, dest deleted on failure: F3793/F4217), then compiled like WD-11's
    push(d, "extra.scel")
    import_file(d, "extra.scel", "基础词库")
    rows = dictionaries(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("congjing")
    c = words(d.all_cands())
    d.clear()
    check(any("extra" in r for r in rows), f"基础词库一节 {rows}")
    check("琮璟" in c, f"congjing 的候选 {c[:6]}")
    return f"基础词库 {rows}；congjing {c[:4]}"


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


@case("WD-14d", base="B0-设置", mutates=True, heavy=True)
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


case("WD-14e", needs="jvm:DictionaryUatTest.aRimeDictionaryKnownAndImported")(lambda d: None)


case("WD-14f", needs="jvm:DictionaryUatTest.aFileNotSurelyReadIsRefusedWithWhy")(lambda d: None)


case("WD-14g", needs="jvm:DictionaryUatTest.pastAHundredThousandWordsIsNoImportForTheUser")(lambda d: None)


@case("WD-14h", base="B0-设置", mutates=True, heavy=True)
def wd14h(d):
    src = frost(d)
    dicts = DICTS
    d.open_page("text")
    d.switch_im("拼音")
    base = heap_base(d)
    d.sh(f"am force-stop {PKG}")
    # the compile's log line counted from here only
    d.sh("logcat -c")
    d.sh(f"mkdir -p {dicts}; rm -f {dicts}/merged.ymd")
    # no touch to an old mtime any more: a .txt an older version left is converted, an upgrade by
    # itself (SourceNames.unconverted)
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


case("WD-14i", needs="jvm:DictionaryUatTest.aCancelledImportChangesNothing")(lambda d: None)


case("WD-14j", needs="jvm:DictionaryUatTest.turnedOffOnAndDeleted")(lambda d: None)


case("WD-14j2", needs="jvm:DictionaryUatTest.anotherTurnedOffWhileABigOneCompiles")(lambda d: None)


case("WD-14r", needs="release:v0.3.2 正式版里并入雾凇拼音，再覆盖安装这一版正式版，签名要一致")(lambda d: None)
case("WD-14v", needs="release:WD-14r 之后装回 v0.3.2 正式版（adb install -r -d），再装回这一版正式版")(lambda d: None)


case("WD-14s", needs="jvm:DictionaryUatTest.anUpgradeKilledAsItConvertsIsFinishedAtTheNextStart")(lambda d: None)


case("WD-14s2", needs="jvm:DictionaryUatTest.anUpgradeKilledAfterItsConversionIsStillAnUpgrade")(lambda d: None)


case("WD-14t", needs="jvm:DictionaryUatTest.turnedOffAndDeletedAsTheyConvert")(lambda d: None)


case("WD-14w", needs="jvm:DictionaryUatTest.anImportWhileAnUpgradeConverts")(lambda d: None)


case("WD-14t2", needs="jvm:DictionaryUatTest.aTextLeftAfterAnUpgradeIsDeletedNotConvertedAgain")(lambda d: None)


case("WD-14x", needs="jvm:DictionaryUatTest.aDamagedFileIsSkippedTheRestMerged")(lambda d: None)


case("WD-14x2", needs="jvm:DictionaryUatTest.aFileDamagedHalfwayGivesWhatWasReadBefore")(lambda d: None)


case("WD-14y", needs="jvm:DictionaryUatTest.aTextNotConvertedForSpaceIsKeptTillThereIsRoom")(lambda d: None)


case("WD-14u", needs="jvm:BackupUatTest.a032BackupOnAFreshInstallBringsItsDictionaryWithoutThePage")(lambda d: None)


case("WD-14k", needs="jvm:DictionaryUatTest.theThirdColumnRanksAsItIsRead")(lambda d: None)


case("WD-14l", needs="jvm:DictionaryUatTest.aBinaryFileRefusedPinyinFirstRunTogetherRead")(lambda d: None)


case("WD-14m", needs="jvm:DictionaryUatTest.aNameInTheBaseAlreadyAsksForTheUser")(lambda d: None)


case("WD-14n", needs="jvm:DictionaryUatTest.rimesPercentWeightsImported")(lambda d: None)


case("WD-14o", needs="jvm:DictionaryUatTest.theUsersCapCountsWhatWasImportedBefore")(lambda d: None)


case("WD-14p", needs="jvm:DictionaryUatTest.utf8TraditionalInBig5Not")(lambda d: None)


case("WD-14q", needs="jvm:DictionaryUatTest.wordsWithoutPinyinApartTooManyNotForTheBase")(lambda d: None)


# ---------------------------------------------------------------- phrases

def add_phrase(d, key, parts, order="1"):
    """自定义短语 → +: 输入码 key, 候选位置 order, 短语 typed from parts: ("cn", word) |
    ("raw", letters) | ("sym", ch) | ("nl",)"""
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


case("WD-16", needs="jvm:LexiconUatTest.aPhraseOfTwoLines")(lambda d: None)


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


case("WD-19", needs="jvm:LexiconUatTest.phrasesNotOnTheNineKeysButInShuangpin")(lambda d: None)


# ---------------------------------------------------------------- the user lexicon's pages

def switch_on(d, title):
    r = d.row(title)
    w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial", "CheckBox")] if r else []
    return w[0].checked if w else None


case("WD-21", needs="jvm:SettingsPagesUatTest.theUsersWordsListsCountsAndDays")(lambda d: None)


case("WD-22", needs="jvm:LexiconUatTest.aPinUndoneInOneStep")(lambda d: None)


case("WD-33", needs="jvm:LexiconUatTest.aPhraseAlreadyBeforeDoesNotMoveTheNextUp")(lambda d: None)


# ---------------------------------------------------------------- R11: 清空学到的 / 全部清空

case("WD-25", needs="jvm:LexiconUatTest.clearingTheLearnedKeepsYourWordsUnlessAll")(lambda d: None)


case("WD-30", needs="jvm:SettingsPagesUatTest.searchDeleteSeveralAndTakeThemBack")(lambda d: None)

