"""uat/15-privacy-backup.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import tempfile
import time
import zipfile

from uat import (PKG, Blocked, add_im, case, check, choose, ensure_ime, first, gear, has, home, menu_tap, open_url,
                 pinyin_keyboard, pinyin_keyboard_value, reset, script, in_picker, shuangpin_keyboard)


def words13():
    """13-words.py's helpers (words, phrases)"""
    return script("13-words")

DOWNLOAD = "/sdcard/Download"


case("PR-01", needs="jvm:BackupUatTest.noNetwork")(lambda d: None)


case("PR-02", needs="jvm:BackupUatTest.noStorageContactsOrOtherPermissions")(lambda d: None)


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


case("PR-07", needs="jvm:PrivacyUatTest.aFieldThatAsksNotToLearnLearnsNothing")(lambda d: None)


case("PR-08", needs="jvm:ImeSwitchUatTest.aSensitiveClipIsShownAsDots")(lambda d: None)


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


def restore(d, fname):
    """从备份恢复 → fname; returns (the confirmation's text, restarted), after the app came back.
    The caller checks restarted (F3725): a case may check the warning first (F3714)"""
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
        return warn, restarted
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
    # the dialog's rows only: the page behind it, also in the dump, has a checked switch
    v = next((n.text for n in d.screen().app() if n.checked and n.cls == "CheckedTextView"), None)
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
    warn, restarted = restore(d, new[0])
    # checked before anything else: the rest means nothing without a restart (F3725)
    check("覆盖" in warn and "标点" in warn and "剪贴板" in warn, f"确认前的提示「{warn[:60]}」")
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
    script("14-settings").set_comma(d, "、")
    # copied after the backup: the restore replaces the clipboard, it does not merge (F4273)
    tb = script("05-toolbar")
    tb.copy_word(d, "公园")
    tb.clip_panel(d)
    if not tb.clip_item(d, "公园"):
        raise Blocked("前提没到：剪贴板面板里没有刚复制的「公园」")
    tb.back_to_keys(d)
    d.hide_keyboard()
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, new[0])
        d.adb("pull", f"{DOWNLOAD}/{new[0]}", local, timeout=120)
        with zipfile.ZipFile(local) as z:
            dicts = [x for x in z.namelist() if "/dictionaries/" in x and not x.endswith("/")]
    check(restore(d, new[0])[1], "应用没有重启")
    d.open_page("text")
    d.press(",")
    pt = d.field_text()
    d.clear()
    n = per_page(d)
    c = [x.split(" ")[0] for x in words13().page_cands(d, "yx")]
    d.clear()
    tb.clip_panel(d)
    clip = [x.text for x in tb.clip_items(d)]
    tb.back_to_keys(d)
    check(any(x.endswith("/dict-words.txt.gz") for x in dicts)
          and not any(x.endswith(("merged.ymd", ".tmp", "/dict-words.txt")) for x in dicts), f"备份里的词库 {dicts}")
    check(n == "7", f"再恢复后每页候选数「{n}」")
    check("test@example.com" not in c, f"再恢复后 yx {c[:4]}")
    check(pt == "，", f"再恢复后点底排的，打出「{pt}」")
    check(not any(x.startswith("公园") for x in clip), f"再恢复后剪贴板 {clip[:4]}")
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
    check(restore(d, new[0])[1], "应用没有重启")
    d.open_page("text")
    d.switch_im("拼音")
    d.type(py.SENTENCE_PY)
    c = d.cands()
    d.clear()
    check(first(c) == py.SENTENCE, f"恢复后 {py.SENTENCE_PY} {c[:3]}")
    return f"{new[0]}；恢复后 {c[:3]}"


case("PR-12", needs="jvm:BackupUatTest.aSensitiveClipboardEntryIsNotBackedUp")(lambda d: None)


case("PR-13", needs="release:要同一版本的语音版正式包，覆盖安装文字版")(lambda d: None)
case("PR-10a", needs="jvm:BackupUatTest.a032BackupOverAnImportOfTheNameOff")(lambda d: None)


case("PR-10b", needs="jvm:BackupUatTest.thisBuildsBackupOverItsDictionaryTurnedOff")(lambda d: None)


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


case("PR-16", needs="jvm:OwnFieldEngineUatTest.whatWasDeletedInTheAppsOwnFieldIsNotTheTextBefore")(lambda d: None)


# ---------------------------------------------------------------- 不用我的输入习惯、清掉最近

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
    # the habits come back with the switch: the pin, the block and the phrase (as WordsUatTest PR-17)
    d.type("shi")
    s2 = d.all_cands()
    d.clear()
    d.type("dz")
    z2 = d.all_cands()
    d.clear()
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
    check(first(s2) == cc and not has(s2, "是"), f"打开后 shi {s2[:5]}（C=「{cc}」）")
    check(first(z2) == "北京市海淀区", f"打开后 dz {z2[:4]}")
    check(hdr2 == "最近使用" and any(x.startswith("😀") for x in rec2), f"恢复后表情第一页「{hdr2}」{rec2[:5]}")
    return f"弹出提示、不留标记；wangxiaoming {w[:2]}；shi {s1[:2]}；dz {z[:2]}；表情第一页「{hdr}」；恢复后「{hdr2}」{rec2[:2]}"


case("PR-17a", needs="jvm:PrivacyUatTest.notUsingHabitsTheBaseDictionaryStillTypes")(lambda d: None)


case("PR-21", needs="jvm:PrivacyUatTest.clearingTheLastHourForgetsItsPicksNotThePins")(lambda d: None)


# ---------------------------------------------------------------- 自动备份

def backups(d):
    """用户词库 → 学习备份: the rows under 备份"""
    words13().user_lexicon(d, "学习备份")
    t = d.all_texts()
    return t[t.index("备份") + 1:] if "备份" in t else t


case("PR-26", needs="jvm:PrivacyUatTest.clearedThenRestoredFromThisInstallsBackupBringsNothingBack")(lambda d: None)


case("PR-27", needs="jvm:PrivacyUatTest.anAutomaticBackupOnTheFirstTypingDayDeleted")(lambda d: None)


case("PR-32", needs="jvm:PrivacyUatTest.aBlockInTheBackupWinsOverThePhonesPin")(lambda d: None)


# ---------------------------------------------------------------- R11: restore merges, 退回恢复前

case("PR-25", needs="jvm:PrivacyUatTest.aRestoreMergesAndIsTakenBack")(lambda d: None)



case("PR-35", needs="release:gate 3（gates.py backups）恢复 fixtures/upgrade/v0.3.0 的备份")(lambda d: None)
case("PR-36", needs="release:gate 3（gates.py backups）恢复 fixtures/upgrade/v0.3.1 的备份")(lambda d: None)
case("PR-37", needs="release:gate 3（gates.py backups）恢复 fixtures/upgrade/v0.3.2 的备份")(lambda d: None)
case("PR-38", needs="release:这一版正式版的备份装回 v0.3.2 正式版恢复")(lambda d: None)


@case("PR-39")
def pr39(d):
    out = d.sh(f"dumpsys package {PKG}")
    check("DOCUMENTS_PROVIDER" not in out and "DocumentsProvider" not in out, "dumpsys 里还有文档提供者")
    return "没有 DocumentsProvider"


@case("PR-40", base="B0-设置", mutates=True)
def pr40(d):
    pinyin_keyboard(d, "14 键")
    add_im(d, "双拼")
    shuangpin_keyboard(d, "18 键")
    _, new, _ = backup(d)
    if not new:
        raise Blocked("备份后下载里没有新文件")
    pinyin_keyboard(d, "全键盘")
    shuangpin_keyboard(d, "全键盘")
    _, restarted = restore(d, new[-1])
    check(restarted, "恢复后应用没有重启")
    v = pinyin_keyboard_value(d)
    gear(d, "双拼")
    d.scroll_top()
    v18 = (d.summary("键盘") or "").split("\n")[0]
    d.open_page("text")
    # the restarted engine tells the input method once its data is up: till then the keyboard is
    # the full one, as on any first start (seen once of five, two devices sharing the host's cores)
    before = sorted(d.keys(refresh=True))[:6]
    t0 = time.time()
    try:
        d.wait(lambda s: "qw" in d.keys(refresh=True), timeout=20, what="恢复后的 14 键")
    except Blocked:
        pass
    waited = time.time() - t0
    k = d.keys(refresh=True)
    check(v == "14 键", f"恢复后「键盘」是「{v}」")
    check("qw" in k, f"恢复后键盘不是 14 键：{d.im_label()} {sorted(k)[:12]}")
    check(v18 == "18 键", f"恢复后双拼的「键盘」是「{v18}」")
    d.switch_im("双拼")
    k18 = d.keys(refresh=True)
    check("we" in k18 and "io" in k18, f"恢复后双拼不是 18 键：{d.im_label()} {sorted(k18)[:12]}")
    return f"恢复后「{v}」「{v18}」；14 键等了 {waited:.1f} s（先是 {before}）"
