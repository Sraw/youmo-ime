"""uat/15-privacy-backup.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import tempfile
import time
import zipfile

BULLET = "\u2022"   # ClipboardEntry.BULLET: a sensitive clip is shown as these

from uat import BAR_BOTTOM, PKG, Blocked, Fail, add_im, case, open_host, check, choose, ensure_ime, first, has, home, menu_tap, open_url, reset, script, in_picker, new_files


def words13():
    """13-words.py's helpers (words, phrases)"""
    return script("13-words")

DOWNLOAD = "/sdcard/Download"


def perms(d):
    out = d.sh(f"dumpsys package {PKG}")
    lines = out.split("requested permissions:", 1)[1].splitlines()[1:] if "requested permissions:" in out else []
    got = []
    for ln in lines:
        ln = ln.strip()
        if not ln.startswith("android.permission.") and not ln.startswith(PKG) and "." not in ln[:12]:
            break
        got.append(ln.split(":")[0])
    return got


@case("PR-01")
def pr01(d):
    p = perms(d)
    check("android.permission.INTERNET" not in p, f"权限 {p}")
    check(p == ["android.permission.VIBRATE"], f"文字版的权限 {p}")
    return f"权限 {p}"


@case("PR-02")
def pr02(d):
    p = perms(d)
    bad = [x for x in p if any(w in x for w in ("NOTIFICATION", "CONTACTS", "LOCATION", "STORAGE", "MEDIA_", "CAMERA"))]
    check(not bad, f"有 {bad}")
    return f"只有 {p}；导入导出走系统文件选择（WD-09、PR-10）"


def password(d):
    open_url(d, "password")
    time.sleep(0.6)
    d._keys = None


@case("PR-03", mutates="learn", visual=True)
def pr03(d):
    password(d)
    lab = d.im_label()
    d.type("nihao")
    time.sleep(0.4)
    d.shot("field", "密码框打 nihao")
    for _ in range(6):   # WebView's accessibility text of a password field lags behind
        s = d.screen()
        c, t = d.candidates(s), d.field_text(s) or ""
        if len(t) >= 5:
            break
        time.sleep(0.4)
    check("英文" in lab, f"空格键「{lab}」")
    check(not c, f"有候选 {c[:3]}")
    # WebView hands the password's own text to accessibility; the dots are on the screenshot
    check(len(t) == 5, f"框里「{t}」")
    return f"空格「{lab}」，框里「{t}」"


@case("PR-04", base="B0-五笔", mutates="learn")
def pr04(d):
    password(d)
    lab1 = d.im_label()
    open_url(d, "text")
    time.sleep(0.6)
    d._keys = None
    lab2 = d.im_label()
    d.type("q")
    c = d.cands()
    check("英文" in lab1, f"密码框里「{lab1}」")
    check("五笔" in lab2, f"回到普通文本「{lab2}」")
    check(first(c) == "我", f"q 的候选 {c[:3]}")
    return f"「{lab1}」→「{lab2}」，q {c[:2]}"


@case("PR-05", mutates="learn")
def pr05(d):
    password(d)
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    row = [n for n in s.ime() if n.text in list("1234567890") and n.b[3] <= kb.b[1] + 5]
    digits = [n.text for n in sorted(row, key=lambda n: n.b[0])]
    check(digits == list("1234567890"), f"键盘上方 {digits}")
    for ch in "123":
        n = next(n for n in row if n.text == ch)
        d.tap(n.c, 0.3)
    time.sleep(0.4)
    t = d.field_text() or ""
    check(len(t) == 3, f"框里「{t}」")
    return f"数字行 {''.join(digits)}；框里 {len(t)} 个圆点"


@case("PR-07", mutates=True, clean=True)
def pr07(d):
    py = script("06-pinyin")
    d.step("不要个性化学习的框里选两次")
    open_host(d, noLearning=True)
    py.learn_sentence(d, 2)
    d.step("第三次打")
    d.type(py.SENTENCE_PY)
    c = d.cands()
    d.clear()
    check(first(c) == "王小明", f"第三次 {c[:3]}，学了")
    d.step("用户词库")
    d.hide_keyboard()
    words13().user_lexicon(d, "自动学到的词")   # the top page lists sections and counts, no words
    t = d.all_texts()
    check(py.SENTENCE not in t, f"用户词库里有「{py.SENTENCE}」")
    # the control: the same steps in the host's plain field do learn, so what kept it from learning
    # above is the flag (the host is in this app's package: were its field taken as the app's own, it
    # would learn nothing either and the check above prove nothing)
    d.step("对照：普通框里同样选两次会学")
    open_host(d)
    py.learn_sentence(d, 2)
    d.type(py.SENTENCE_PY)
    c2 = d.cands()
    d.clear()
    check(first(c2) == py.SENTENCE, f"普通框里选两次后第三次 {c2[:3]}：测试宿主的框不学，上面的不学说明不了什么")
    return f"第三次 {c[:2]}；用户词库里没有「{py.SENTENCE}」；对照：普通框里学了 {c2[:1]}"


SECRET = "mima1234"


def sensitive_clip(d):
    """a clip marked sensitive, as a password manager copies a password: from the test host"""
    open_host(d, sensitiveClip=SECRET)
    d.open_page("text")


@case("PR-08", mutates=True)
def pr08(d):
    tb = script("05-toolbar")
    sensitive_clip(d)
    d.step("剪贴板提示")
    s = d.screen()
    bar = [n.text for n in s.ime() if n.text and n.c[1] < BAR_BOTTOM]
    check(not any(SECRET in t for t in bar), f"提示是明文：{bar[:6]}")
    check(any(t and set(t) == {BULLET} for t in bar), f"栏上没有圆点的提示：{bar[:6]}")
    d.step("剪贴板面板")
    tb.clip_panel(d)
    items = [n.text for n in tb.clip_items(d)]
    tb.back_to_keys(d)
    check(not any(SECRET in t for t in items), f"面板里是明文：{items[:4]}")
    check(items and items[0].startswith(BULLET), f"面板第一条 {items[:3]}")
    return f"提示和面板都是圆点：{items[0][:12]}"


def backup(d):
    """备份全部 → 保存 in 下载; returns (the new file, any message seen)"""
    d.hide_keyboard()
    home(d, "备份与恢复")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("备份全部", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出保存界面：{d.top_activity()}")
    s = d.screen()
    name = next((n.text for n in s.app() if n.cls == "EditText"), "")
    d.tap_text("保存", where="all", scroll=False, after=0.3)
    # the toast is in no UI dump (03-t9's note): 备份成功 is read off the picture
    d.shot("full", "备份后的提示（应为备份成功）")
    msgs = []
    for _ in range(10):
        msgs += [n.text for n in d.screen().nodes if n.text and ("备份" in n.text or "成功" in n.text)]
        time.sleep(0.3)
    time.sleep(1.0)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    return name, new, msgs


def restore(d, fname, own_check=False):
    """从备份恢复 → fname; returns (the confirmation's text, restarted), after the app came back.
    Not restarted fails here (F3725), unless own_check: then the caller checks it"""
    d.hide_keyboard()
    home(d, "备份与恢复")
    d.tap_text("从备份恢复", after=1.2)
    # the warning comes first, then the file
    s = d.screen()
    warn = " ".join(n.text for n in s.nodes if n.rid == "message")
    b = next((n for n in s.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"没有确认：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(b.c, 2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(4):
        n = d.screen().find(text=fname)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {fname}")
    pid = d.sh(f"pidof {PKG}").strip()
    d.tap(n.c, 1.0)
    s = d.screen()
    b = next((n for n in s.nodes if n.rid == "button1"), None)   # a second confirmation, if any
    if b:
        d.tap(b.c, 1.0)
    # polled, not slept: the process changes, then ours is set again and the restarted page shows
    end, now = time.time() + 20, pid
    while time.time() < end:
        now = d.sh(f"pidof {PKG}").strip()
        if now and now != pid:
            break
        time.sleep(0.3)
    restarted = bool(now) and now != pid   # "" : not back at all
    ensure_ime(d)
    if not restarted:
        if own_check:
            return warn, restarted   # the caller's check says it did not restart (F3714)
        raise Fail("应用没有重启")
    # the restarted settings home itself: the new process can be up (the keyboard's service)
    # before its page is, and a tap made meanwhile is lost when the page comes over it
    try:
        d.wait(lambda s: "MainActivity" in d.top_activity() and d.at_home(s), timeout=15, what="恢复后重启的设置首页")
    except Blocked:
        s = d.screen()
        raise Blocked(f"恢复后：{d.top_activity()} {s.texts()[:8]}")
    time.sleep(1.0)
    return warn, restarted


def setup_dz_word(d):
    words13().dz(d)
    words13().add_word(d, "澜境花园", "lan jing hua yuan")
    # an imported base dictionary too: backups leave merged.ymd out, a restore merges it again
    words13().import_dict_words(d)


def per_page(d):
    """实体键盘 → 每页候选数: the option checked in its list"""
    home(d, "实体键盘")
    d.tap_text("每页候选数", after=1.0)
    v = next((n.text for n in d.screen().app() if n.checked), None)
    d.back()
    return v


@case("PR-10", base="B0-设置", mutates=True, visual=True)
def pr10(d):
    d.step("备份")
    setup_dz_word(d)
    name, new, msgs = backup(d)
    check(new and new[0].startswith("youmo_") and new[0].endswith(".zip"), f"下载里新文件 {new}（保存名 {name}）")
    d.step("重置后恢复")
    reset(d, "B0-设置", "设置")
    warn, restarted = restore(d, new[0], own_check=True)
    # checked before anything else: the rest means nothing without a restart (F3725)
    check("覆盖" in warn and "标点" in warn, f"确认前的提示「{warn[:60]}」")
    check(restarted, "应用没有重启")
    c1 = words13().page_cands(d, "dz")
    d.clear()
    d.type("lanjinghuayuan")
    c2 = d.cands()
    d.clear()
    rows = words13().dictionaries(d)
    # compiled again after the restart: a few seconds for dict-words
    k = []
    for _ in range(10):
        k = words13().words(words13().page_cands(d, "kemiaotangpu"))
        d.clear()
        if "氪喵糖铺" in k:
            break
        time.sleep(3)
    # the name and its status (已开启 · N 个词) are separate texts of one row
    i = rows.index("dict-words") if "dict-words" in rows else -1
    check(i >= 0 and i + 1 < len(rows) and "个词" in rows[i + 1], f"基础词库一节 {rows}")
    check(first(c1) == "北京市海淀区", f"dz {c1[:3]}")
    check(first(c2) == "澜境花园", f"lanjinghuayuan {c2[:3]}")
    check("氪喵糖铺" in k, f"kemiaotangpu {k[:4]}")
    d.step("改了再恢复")
    words13().add_phrase(d, "yx", [("raw", "test"), ("sym", "@"), ("raw", "example"), ("sym", "."), ("raw", "com")])
    c0 = words13().page_cands(d, "yx")
    if first(c0) != "test@example.com":
        raise Blocked(f"前提没到：yx {c0[:3]}")
    d.clear()
    d.hide_keyboard()
    home(d, "实体键盘")
    choose(d, "每页候选数", "5")
    # a punctuation changed after the backup follows it too (F3726)
    script("14-settings").st28(d)   # , → 、
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, new[0])
        d.adb("pull", f"{DOWNLOAD}/{new[0]}", local, timeout=120)
        with zipfile.ZipFile(local) as z:
            dicts = [x for x in z.namelist() if "/dictionaries/" in x and not x.endswith("/")]
    restore(d, new[0])
    d.open_page("text")
    d.press(",")
    pt = d.field_text()
    d.clear()
    n = per_page(d)
    c = [x.split(" ")[0] for x in words13().page_cands(d, "yx")]
    check(any(x.endswith("/dict-words.txt.gz") for x in dicts)
          and not any(x.endswith(("merged.ymd", ".tmp", "/dict-words.txt")) for x in dicts), f"备份里的词库 {dicts}")
    check(n == "7", f"再恢复后每页候选数「{n}」")
    check("test@example.com" not in c, f"再恢复后 yx {c[:4]}")
    check(pt == "，", f"再恢复后点底排的，打出「{pt}」")
    return f"{new[:1]}；提示 {msgs[:1] or '（看截图）'}；重启了；dz {c1[:1]}；{c2[:1]}；基础词库 {rows[:3]}；kemiaotangpu {k[:2]}；再恢复后每页 {n}，yx {c[:2]}，标点「{pt}」"


@case("PR-34", base="B0-设置", mutates=True)
def pr34(d):
    py = script("06-pinyin")
    d.open_page("text")
    d.switch_im("拼音")
    py.learn_sentence(d)
    d.type(py.SENTENCE_PY)
    c0 = d.cands()
    d.clear()
    if first(c0) != py.SENTENCE:
        raise Blocked(f"前提（PY-34）没到：{c0[:3]}")
    d.hide_keyboard()
    home(d, "备份与恢复")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("备份全部", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出保存界面：{d.top_activity()}")
    # turned while the picker is open: the settings page behind it is rebuilt before the save
    d.rotate(1)
    d.tap_text("保存", where="all", scroll=False, after=3.0)
    d.rotate(0)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("转屏后保存，下载里没有新文件")
    reset(d, "B0-设置", "设置")
    restore(d, new[0])
    d.open_page("text")
    d.switch_im("拼音")
    d.type(py.SENTENCE_PY)
    c = d.cands()
    d.clear()
    check(first(c) == py.SENTENCE, f"恢复后 {py.SENTENCE_PY} {c[:3]}")
    return f"{new[0]}；恢复后 {c[:3]}"


@case("PR-12", mutates=True)
def pr12(d):
    tb = script("05-toolbar")
    d.step("敏感的和「公园」进剪贴板")
    sensitive_clip(d)
    tb.copy_word(d, "公园")
    d.open_page("text")
    tb.clip_panel(d)
    items = [n.text for n in tb.clip_items(d)]
    tb.back_to_keys(d)
    if not (any(t.startswith("公园") for t in items) and any(t.startswith(BULLET) for t in items)):
        raise Blocked(f"前提没到：剪贴板 {items[:4]}")
    d.step("备份")
    _, new, _ = backup(d)
    if not new:
        raise Blocked("备份没有新文件")
    d.step("重置后恢复")
    reset(d, "B0-设置", "设置")
    restore(d, new[0])
    d.open_page("text")
    tb.clip_panel(d)
    after = [n.text for n in tb.clip_items(d)]
    tb.back_to_keys(d)
    check(any(t.startswith("公园") for t in after), f"恢复后没有「公园」：{after[:4]}")
    check(not any(t.startswith(BULLET) or SECRET in t for t in after), f"恢复后敏感的那条还在：{after[:4]}")
    return f"恢复后剪贴板 {after[:3]}"
case("PR-13", needs="release:要同一版本的语音版正式包，覆盖安装文字版")(lambda d: None)
@case("PR-10a", base="B0-设置", mutates=True)
def pr10a(d):
    """design §4 rule: a 0.3.2 backup's plain .txt restored over this build's .gz turned off: the
    backup's state wins, one row, no reset"""
    w = words13()
    w.import_dict_words(d)
    name = w.old_backup(d)
    w.dict_row_menu(d, "关闭")
    if "dict-words.txt.gz.disable" not in w.dict_files(d):
        raise Blocked(f"关闭后目录 {w.dict_files(d)}")
    restore(d, name)
    d.open_page("text")
    d.switch_im("拼音")
    k = w.kemiaotangpu(d)
    rows = w.dictionaries(d)
    files = w.dict_files(d)
    i = rows.index("dict-words") if "dict-words" in rows else -1
    check("氪喵糖铺" in k, f"kemiaotangpu {k[:4]}")
    check(rows.count("dict-words") == 1 and i + 1 < len(rows) and "已开启" in rows[i + 1], f"基础词库一节 {rows}")
    check([f for f in files if f.startswith("dict-words")] == ["dict-words.txt.gz"], f"词库目录 {files}")
    return f"kemiaotangpu {k[:2]}；词库页 {rows[i:i + 2]}；目录 {files}"


@case("PR-10b", base="B0-设置", mutates=True)
def pr10b(d):
    """design §4 RN3: a backup with dict-words on restored over it turned off, no reset: the newer
    (the backup's copy) wins, one row, no .gz.disable left beside the .gz"""
    w = words13()
    w.import_dict_words(d)
    _, new, _ = backup(d)
    if not new:
        raise Blocked("备份全部没在下载里留下文件")
    w.dict_row_menu(d, "关闭")
    if "dict-words.txt.gz.disable" not in w.dict_files(d):
        raise Blocked(f"关闭后目录 {w.dict_files(d)}")
    restore(d, new[0])
    d.open_page("text")
    d.switch_im("拼音")
    k = w.kemiaotangpu(d)
    rows = w.dictionaries(d)
    files = w.dict_files(d)
    i = rows.index("dict-words") if "dict-words" in rows else -1
    check("氪喵糖铺" in k, f"kemiaotangpu {k[:4]}")
    check(rows.count("dict-words") == 1 and i + 1 < len(rows) and "已开启" in rows[i + 1], f"基础词库一节 {rows}")
    check([f for f in files if f.startswith("dict-words")] == ["dict-words.txt.gz"], f"词库目录 {files}")
    return f"kemiaotangpu {k[:2]}；词库页 {rows[i:i + 2]}；目录 {files}"


case("PR-14", needs="release:上一版正式版装上再覆盖安装这一版正式版，签名要一致")(lambda d: None)
case("PR-30", needs="release:要 v0.3.0 正式版装上再覆盖安装这一版正式版")(lambda d: None)


@case("PR-15", base="B0-设置", mutates=True)
def pr15(d):
    panels, toolbar = script("04-panels"), script("05-toolbar")
    d.tap_text("搜索设置", scroll=False)
    f = d.field()
    if not f:
        raise Blocked("搜索框没有焦点")
    d.tap(f.c, 0.8)
    d.switch_im("拼音")
    d.type("nihao")
    c = [x.split(" ")[0] for x in d.cands()]
    if len(c) < 2:
        raise Blocked(f"nihao 的候选 {c}")
    d.pick(2)
    d.press("符", after=1.0)
    panels.sym_category(d, "图形")
    shown = panels.selected_category(d)
    sym = next((x for x in shown[1:] if x != "★"), None)
    if not sym:
        raise Blocked(f"图形一类 {shown}")
    panels.sym_tap(d, sym)
    d.clear()
    d.switch_im("英文")
    toolbar.numpad(d, "1+1=")
    r = toolbar.result_on_bar(d)
    d.hide_keyboard()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("nihao")
    c2 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    d.press("符", after=1.0)
    try:
        panels.sym_category(d, "最近")
        recent = panels.selected_category(d)
    except Blocked:   # no 最近 category at all: nothing was recorded
        recent = []
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "自动学到的词")
    rows = d.all_texts()
    check(not r, f"设置搜索框里打 1+1= 栏上有「{r.text if r else ''}」")
    check(c2 and c2[0] == c[0], f"回测试页 nihao 第一候选「{c2[:1]}」，原来「{c[0]}」（第二候选「{c[1]}」）")
    check(sym not in recent, f"符号面板最近里有设置里点的「{sym}」：{recent[:6]}")
    check(not any(c[1] in x for x in rows), f"自动学到的词里有「{c[1]}」：{rows[:8]}")
    return f"无算式结果；nihao 首选仍「{c2[0]}」；最近 {recent[:4]} 无「{sym}」；自动学到的词 {rows[:3]}"


@case("PR-16", base="B0-设置", mutates=True)
def pr16(d):
    d.tap_text("搜索设置", scroll=False)
    f = d.field()
    if not f:
        raise Blocked("搜索框没有焦点")
    d.tap(f.c, 0.8)
    d.switch_im("拼音")
    d.type("zai")
    c0 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    d.type("wo")
    d.pick("我")
    d.press("bksp", after=0.6)
    t = d.field_text() or ""
    if t:
        raise Blocked(f"退格后框里还有「{t}」")
    d.type("zai")
    c1 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    check(c0 and c1 and c1[0] == c0[0], f"删掉「我」后 zai 第一候选「{c1[:1]}」，空框时「{c0[:1]}」")
    return f"空框 zai {c0[:2]}；我 删掉后 {c1[:2]}"


# ---------------------------------------------------------------- 不用我的输入习惯、清掉最近

def status_entry(d, text):
    """⋯ (状态区域), then the entry named text; returns the entries' texts seen"""
    tb = script("05-toolbar")
    tb.status_panel(d)
    n = d.screen().find(text=text, where="ime")
    if not n:
        d.swipe((540, 2000), (540, 1650), 300, 0.8)
        n = d.screen().find(text=text, where="ime")
    if not n:
        raise Blocked(f"⋯ 里没有「{text}」：{d.screen().texts(where='ime')[:16]}")
    d.tap(n.c, 1.0)


def status_texts(d):
    script("05-toolbar").status_panel(d)
    t = d.screen().texts(where="ime")
    script("05-toolbar").back_to_keys(d)
    return t


def tap_emoji(d, ch="😀"):
    panels = script("04-panels")
    panels.ime_tap(d, desc="表情", after=1.0)
    n = panels.emoji_find(d, ch)
    if not n:
        raise Blocked(f"表情面板里找不到「{ch}」")
    d.tap(n.c, 0.6)
    panels.ime_tap(d, text="ABC", after=0.6)
    d._keys = None


def recent_emoji(d):
    """the emoji panel's first tab: (its header, the emoji on it)"""
    from uat import GRID_BOTTOM, GRID_TOP
    panels = script("04-panels")
    panels.ime_tap(d, desc="表情", after=1.0)
    hdr = panels.emoji_tab(d, 0)
    s = d.screen()
    got = [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
           if n.text and GRID_TOP < n.c[1] < GRID_BOTTOM and n.text not in panels.CATS]
    panels.ime_tap(d, text="ABC", after=0.6)
    d._keys = None
    return hdr, got


def ta_dl_twice(d):
    """PY-54's steps: after 他, 点了 picked twice for dl"""
    for _ in range(2):
        d.type("ta")
        d.cands(want="他")
        d.pick("他")
        d.type("dl")
        d.cands(want="点了")
        d.pick("点了")
        d.clear()


@case("PR-17", mutates=True)
def pr17(d):
    py = script("06-pinyin")
    py.learn_sentence(d)
    c0, cc = py.pin_fifth(d)
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()
    words13().dz(d)
    d.open_page("text")
    d.switch_im("拼音")
    tap_emoji(d)
    d.clear()
    tb = script("05-toolbar")
    tb.status_panel(d)
    d.tap(d.screen().find(text="不用我的输入习惯", where="ime").c, 0.1)
    # said once as it goes off (the chip above the keyboard, up 2 s), and nothing kept on the bar after
    # the chip tapped from the dump that found it: another dump first may outlast its 2 s, the tap
    # then landing on the page above the keyboard
    chip = None
    end = time.time() + 2.0
    while chip is None and time.time() < end:
        chip = d.screen().find(contains="已不用我的输入习惯，在 ⋯ 里恢复", where="all")
    said = chip is not None
    # a tap on it takes it away, and only it: the panel under it stays
    if chip:
        d.tap(chip.c, 0.3)
    s = d.screen()
    tapped_away = chip is None or not s.find(contains="已不用我的输入习惯，在 ⋯ 里恢复", where="all")
    panel_kept = bool(s.find(text="恢复我的输入习惯", where="ime"))
    tb.back_to_keys(d)
    time.sleep(2)
    kept = [t for t in d.screen().texts(where="ime") if "输入习惯" in t]
    d.type("wangxiaoming")
    w = d.cands()
    d.clear()
    d.type("shi")
    s1 = d.all_cands()
    d.clear()
    d.type("dz")
    z = d.all_cands()
    d.clear()
    hdr, _ = recent_emoji(d)
    ent = status_texts(d)
    # PR-19 folded in: the switch on 用户词库 is the same one as ⋯'s entry
    d.hide_keyboard()
    words13().user_lexicon(d)
    sw = words13().switch_on(d, "用我的输入习惯")
    d.switch("用我的输入习惯", True)
    d.open_page("text")
    d.switch_im("拼音")
    ent2 = status_texts(d)
    hdr2, rec2 = recent_emoji(d)
    check(said, "关掉时没有提示「已不用我的输入习惯，在 ⋯ 里恢复」")
    check(not kept, f"键盘上留着 {kept}")
    check(tapped_away and panel_kept, f"点提示：消失 {tapped_away}，⋯ 面板还在 {panel_kept}")
    check(first(w) == "王小明" and not has(w, "汪小明"), f"wangxiaoming {w[:4]}")
    # 是 is blocked (the blocks still apply), so "the first as before" is the first of c0 without 是
    was = first([x for x in c0 if first([x]) != "是"])
    check(first(s1) == was and first(s1) != cc and not has(s1, "是"), f"shi {s1[:5]}（原来 {c0[:3]}，C=「{cc}」）")
    check(not has(z, "北京市海淀区"), f"dz {z[:4]}")
    check(hdr != "最近使用", f"表情面板第一页是「{hdr}」")
    check("恢复我的输入习惯" in ent, f"⋯ 里 {ent[:16]}")
    check(sw is False, f"用户词库页顶「用我的输入习惯」{sw}")
    check("不用我的输入习惯" in ent2, f"打开后 ⋯ 里 {ent2[:16]}")
    check(hdr2 == "最近使用" and any(x.startswith("😀") for x in rec2), f"恢复后表情第一页「{hdr2}」{rec2[:5]}")
    return f"弹出提示、不留标记；wangxiaoming {w[:2]}；shi {s1[:2]}；dz {z[:2]}；表情第一页「{hdr}」；恢复后「{hdr2}」{rec2[:2]}"


@case("PR-17a", base="B0-设置", mutates=True)
def pr17a(d):
    """habits off: the base dictionaries stay, in pinyin and in a table; a word added by hand does not"""
    w13 = words13()
    w13.import_dict_words(d)
    add_im(d, "五笔")
    w13.add_word(d, "鲸园", "jing yuan")
    time.sleep(5.0)   # the imported words get their table codes in the background
    d.open_page("text")
    d.switch_im("拼音")
    status_entry(d, "不用我的输入习惯")
    script("05-toolbar").back_to_keys(d)
    time.sleep(2.5)   # the chip above the keyboard gone
    d.type("liangziwenhuacun")
    py = w13.words(d.all_cands())
    d.clear()
    d.type("jingyuan")
    own = w13.words(d.all_cands())
    d.clear()
    d.switch_im("五笔")
    d.type("jbys")
    time.sleep(0.4)
    wb = w13.words(d.all_cands())
    d.clear()
    d.switch_im("拼音")
    status_entry(d, "恢复我的输入习惯")
    script("05-toolbar").back_to_keys(d)
    check("量子文化村" in py, f"liangziwenhuacun {py[:5]}")
    check("鲸园" not in own, f"jingyuan {own[:5]}")
    check("量子计算机" in wb and "量子文化村" in wb, f"jbys {wb[:8]}")
    return f"拼音 {py[:2]}；五笔 jbys {wb[:5]}"


@case("PR-21", mutates=True)
def pr21(d):
    ta_dl_twice(d)
    tap_emoji(d)
    d.clear()
    c0, cc = script("06-pinyin").pin_fifth(d)
    status_entry(d, "清掉最近")
    menu_tap(d, "最近 1 小时")
    time.sleep(0.8)
    s = d.screen()
    msg = " ".join(n.text for n in s.nodes if n.rid in ("message", "alertTitle"))
    b = next((n for n in s.nodes if n.text == "清掉"), None)
    if not b:
        raise Blocked(f"没有确认框：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(b.c, 0.1)
    # the result, said above the keys (the chip, 2 s)
    cleared = script("05-toolbar").said(d, "已清掉")
    time.sleep(1.4)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("ta")
    d.cands(want="他")
    d.pick("他")
    d.type("dl")
    c = d.cands()
    d.clear()
    d.type("shi")
    s1 = d.cands()
    d.clear()
    hdr, rec = recent_emoji(d)
    check("退回恢复前" in msg and "不能" in msg, f"确认框「{msg}」")
    check(cleared, "清掉后没有提示「已清掉」")
    check(first(c) == "带来" and c[1:2] != ["点了"], f"「他 dl」{c[:4]}")
    check(first(s1) == cc, f"shi {s1[:4]}（C=「{cc}」）")
    check(hdr != "最近使用" or not any(x.startswith("😀") for x in rec), f"表情第一页「{hdr}」{rec[:6]}")
    return f"确认框写明不能退回；他 dl {c[:3]}；shi {s1[:2]}；最近 {rec[:3]}"


# ---------------------------------------------------------------- 自动备份

def backups(d):
    """用户词库 → 学习备份: the rows under 备份"""
    words13().user_lexicon(d, "学习备份")
    t = d.all_texts()
    return t[t.index("备份") + 1:] if "备份" in t else t


@case("PR-26", mutates=True, visual=True)
def pr26(d):
    ta_dl_twice(d)
    b0 = backups(d)
    if not any("这次安装" in r for r in b0):
        raise Blocked(f"前提没到：学习备份 {b0[:6]}")
    d.back()
    d.tap_text("清空学到的", after=1.0)
    s = d.screen()
    b = next((n for n in s.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"清空没有确认框：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(b.c, 2.0)
    b1 = backups(d)
    mine = [b1[i - 1] for i, r in enumerate(b1) if "这次安装" in r and i]
    old = [b0[i - 1] for i, r in enumerate(b0) if "这次安装" in r and i]
    n = d.screen().find(text=mine[0]) if mine else None
    if not n:
        raise Blocked(f"清空后学习备份 {b1[:6]}")
    d.tap(n.row().c, 1.0)
    d.tap_text("恢复", where="all", scroll=False, after=1.0)
    s = d.screen()
    ok = next((n for n in s.nodes if n.rid == "button1"), None)
    if ok:
        d.tap(ok.c, 0.6)
    # the toast is in no UI dump (03-t9's note): 已恢复 is read off the picture
    d.shot("full", "恢复后的提示（应为\"已恢复\"）")
    time.sleep(2.4)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("ta")
    d.cands(want="他")
    d.pick("他")
    d.type("dl")
    c = d.cands()
    d.clear()
    check(len(mine) == 1 and not set(mine) & set(old), f"清空后这次安装的备份 {mine}（之前 {old}）")
    check(first(c) == "带来", f"恢复后「他 dl」{c[:4]}")
    return f"之前 {old[:1]}；清空后 {mine}；恢复后 {c[:3]}"


@case("PR-27", mutates=True)
def pr27(d):
    d.type("nihao")
    d.cands()
    d.space()
    words13().user_lexicon(d)
    on = words13().switch_on(d, "自动备份")
    d.tap_text("学习备份", after=1.2)
    t = d.all_texts()
    rows = t[t.index("备份") + 1:] if "备份" in t else t
    # a row: its date, then "这次安装存的 · 第 n 个打字日"
    mine = [rows[i - 1] + " " + r for i, r in enumerate(rows) if "这次安装" in r and i]
    warn = any("卸载输入法会丢掉学到的东西和自动备份" in x and "除非另存过一份" in x for x in t)
    if not mine:
        raise Blocked(f"第一次上屏后没有备份：{t[:10]}")
    today = d.sh("date +%Y年%-m月%-d日").strip()

    def delete(confirm):
        n = d.screen().find(text=mine[0].split(" ")[0] + " " + mine[0].split(" ")[1])
        d.tap(n.row().c, 1.0)
        d.tap_text("删除", where="all", scroll=False, after=1.0)
        s = d.screen()
        msg = " ".join(x.text for x in s.nodes if x.rid in ("message", "alertTitle"))
        b = next((x for x in s.nodes if x.rid == ("button1" if confirm else "button2")), None)
        if not b:
            raise Blocked(f"删除没有确认框：{[x.text for x in s.nodes if x.text][:8]}")
        d.tap(b.c, 1.2)
        return msg
    msg = delete(False)
    kept = any(mine[0].startswith(x) and "日 " in x for x in d.all_texts())
    delete(True)
    after = d.all_texts()
    check(on is True, f"自动备份开关 {on}")
    check(today in mine[0] and "第 1 个打字日" in mine[0], f"备份「{mine[0]}」（今天 {today}）")
    check(warn, f"页里没写卸载会丢：{t[-4:]}")
    check("不能找回" in msg, f"确认框「{msg}」")
    check(kept and "还没有备份" in after, f"取消后还在 {kept}；删除后 {after[:8]}")
    return f"「{mine[0]}」；确认框「{msg}」；删除后还没有备份"


@case("PR-32", base="B0-设置", mutates=True)
def pr32(d):
    w = words13()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()
    w.user_lexicon(d, "学习备份")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("另存一份到看得见的文件夹", after=1.0)
    d.tap_text("确定", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"另存没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=0.3)
    new = new_files(d, DOWNLOAD, before)
    if not new:
        raise Blocked("另存后下载里没有新文件")
    reset(d, "B0-设置", "设置")
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "固定首位")
    d.clear()
    d.open_settings()
    w.user_lexicon(d, "学习备份")
    # slept, not polled, here: polled (picker, then its 确定) the restore came a few seconds after
    # the 固定首位 above, and 6 runs in 7 restored no 屏蔽 at all; slept, every run kept it. Likely the
    # app's (a restore close behind a pin losing the backup's block), left for the app to look at
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
    d.open_settings()
    w.user_lexicon(d, "屏蔽的词")
    blocked = w.list_texts(d)
    d.back()
    d.tap_text("固定的词", after=1.2)
    pinned = w.list_texts(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    c = [x.split(" ")[0] for x in d.all_cands()]
    d.clear()
    check("是" in blocked, f"屏蔽的词 {blocked[:5]}")
    check(not any(r.startswith("是") for r in pinned), f"固定的词 {pinned[:5]}")
    check("是" not in c, f"shi {c[:6]}")
    return f"屏蔽的词 {blocked[:3]}；固定的词 {pinned[:3]}；shi {c[:4]}"


# ---------------------------------------------------------------- R11: restore merges, 退回恢复前

def _save_copy(d):
    w = words13()
    w.user_lexicon(d, "学习备份")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("另存一份到看得见的文件夹", after=1.0)
    d.tap_text("确定", after=0.3)
    in_picker(d)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"另存没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=0.3)
    new = new_files(d, DOWNLOAD, before)
    if not new:
        raise Blocked("另存后下载里没有新文件")
    return new[0]


def _restore_from(d, fname):
    words13().user_lexicon(d, "学习备份")
    d.tap_text("从备份恢复", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(12):
        n = d.screen().find(text=fname)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {fname}")
    d.tap(n.c, 2.5)
    d.tap_text("确定", after=0.6)
    d.shot("full", "恢复后的提示（应为\"已恢复\"）")
    time.sleep(2.4)


def _typed(d, code, want=None):
    d.open_page("text")
    d.switch_im("拼音")
    d.type(code)
    c = [x.split(" ")[0] for x in d.cands(want=want)]
    d.clear()
    return c


def _pr24(d):
    """PR-25's 前提 and its restore (twice); returns (C, D)"""
    py = script("06-pinyin")
    d.open_page("text")   # B0-设置 starts on the settings
    d.switch_im("拼音")
    py.learn_sentence(d)
    c0, cc = py.pin_fifth(d)
    fname = _save_copy(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    d.cands()
    m = d.long_pick(cc)
    un = next((x for x in m if "取消" in x), None)
    if not un:
        raise Blocked(f"「{cc}」的菜单 {m[:8]}")
    menu_tap(d, un)
    d.clear()
    d.type("shi")
    c = [x.split(" ")[0] for x in d.cands()]
    dd = next(x for x in c[1:6] if x != cc)
    d.long_pick(dd)
    menu_tap(d, "固定首位")
    d.clear()
    w = words13()
    w.add_word(d, "鲸喵屿", "jing miao yu")
    _restore_from(d, fname)
    _restore_from(d, fname)
    return cc, dd


@case("PR-25", base="B0-设置", mutates=True, visual=True)
def pr25(d):
    d.step("恢复合并")
    cc, dd = _pr24(d)
    s = _typed(d, "shi")
    j = _typed(d, "jingmiaoyu", want="鲸喵屿")
    w = _typed(d, "wangxiaoming")
    check(first(s) == dd, f"恢复后 shi {s[:3]}（C=「{cc}」，D=「{dd}」）")
    check("鲸喵屿" in j, f"恢复后 jingmiaoyu {j[:3]}")
    check(first(w) == "汪小明", f"恢复后 wangxiaoming {w[:3]}")
    d.step("退回恢复前")
    d.hide_keyboard()
    words13().user_lexicon(d, "学习备份")
    d.tap_text("退回恢复前", after=1.0)
    s0 = d.screen()
    msg = " ".join(n.text for n in s0.nodes if n.rid in ("message", "alertTitle"))
    b = next((n for n in s0.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"没有确认框：{[n.text for n in s0.nodes if n.text][:8]}")
    d.tap(b.c, 0.6)
    d.shot("full", "退回后的提示（应为\"已退回恢复前\"）")
    time.sleep(2.4)
    s2 = _typed(d, "shi")
    j2 = _typed(d, "jingmiaoyu", want="鲸喵屿")
    check(all(x in msg for x in ("学到", "固定", "屏蔽")), f"确认框「{msg}」")
    check(first(s2) == dd, f"退回后 shi {s2[:3]}（D=「{dd}」）")
    check("鲸喵屿" in j2, f"退回后 jingmiaoyu {j2[:3]}")
    return f"恢复后 shi {s[:2]}，{j[:1]}，{w[:1]}；「{msg[:40]}」；退回后 shi {s2[:2]}；{j2[:1]}"
