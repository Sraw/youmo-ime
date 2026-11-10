"""uat/15-privacy-backup.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import tempfile
import time
import zipfile

from uat import (PKG, Blocked, case, check, choose, ensure_ime, first, home, open_url,
                 reset, script, in_picker)


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


case("PR-04", needs="jvm:HostCasesUatTest.leavingAPasswordFieldGoesBackToTheInputMethodBefore")(lambda d: None)


case("PR-05", needs="jvm:ToolbarUatTest.aPasswordsBarIsTheDigitsRow")(lambda d: None)


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
    # a toast's window is not in uiautomator's dump: 备份成功 is read off the picture. Frames grabbed
    # at once, not through shot()'s settle(): waiting for a still screen outlasted the 2 s toast
    for i in range(3):
        time.sleep(0.6)
        d.shot("full", f"备份后的提示（应为备份成功）{i + 1}/3",
               png=d.adb("exec-out", "screencap", "-p", binary=True))
    msgs = []
    for _ in range(10):
        msgs += [n.text for n in d.screen().nodes if n.text and ("备份" in n.text or "成功" in n.text)]
        time.sleep(0.3)
    time.sleep(1.0)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    return name, new, msgs


def restore(d, fname, refused=None):
    """从备份恢复 → fname; returns (the confirmation's text, restarted), after the app came back.
    The caller checks restarted (F3725): a case may check the warning first (F3714).
    refused: text the import's error dialog must show (a backup this build refuses); returns (that
    dialog's text, False) once it shows over our page, with our process still the same one"""
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
    if refused:
        # the refusal itself, not a picker tapped on nothing or a crash: no restart alone proves neither
        try:
            msg = d.wait(lambda s: " ".join(n.text for n in s.nodes if n.rid == "message" and refused in (n.text or "")),
                         timeout=20, what=f"拒绝提示（{refused}）")
        except Blocked:
            # a restart instead of the refusal is the build accepting the backup: a failure, not a block
            now = d.sh(f"pidof {PKG}").strip()
            check(now == pid, f"该拒绝却没有拒绝提示，进程变了（恢复了或崩溃了）：{pid} → {now or '无'}")
            raise
        top, now = d.top_activity(), d.sh(f"pidof {PKG}").strip()
        check(now == pid, f"拒绝提示后进程变了：{pid} → {now or '无'}")
        if "documentsui" in top:
            raise Blocked(f"拒绝提示后还在文件选择：{top}")
        b = next((n for n in d.screen().nodes if n.rid == "button1"), None)
        if b:
            d.tap(b.c, 0.5)
        ensure_ime(d)
        return msg, False
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


case("PR-13", needs="release:要同一版本的正式包覆盖安装，再导入语音包")(lambda d: None)


case("PR-10a", needs="jvm:BackupUatTest.a032BackupOverAnImportOfTheNameOff")(lambda d: None)


case("PR-10b", needs="jvm:BackupUatTest.thisBuildsBackupOverItsDictionaryTurnedOff")(lambda d: None)


case("PR-14", needs="release:上一版正式版装上再覆盖安装这一版正式版，签名要一致")(lambda d: None)


case("PR-30", needs="release:要 v0.3.0 正式版装上再覆盖安装这一版正式版")(lambda d: None)


case("PR-15", needs="jvm:OwnFieldUatTest.thisAppsOwnFieldLearnsNothing、OwnFieldUatTest.thisAppsOwnFieldKeepsNoRecentSymbolAndReadsNoSum")(lambda d: None)


case("PR-16", needs="jvm:OwnFieldEngineUatTest.whatWasDeletedInTheAppsOwnFieldIsNotTheTextBefore")(lambda d: None)


# ---------------------------------------------------------------- 不用我的输入习惯、清掉最近


case("PR-17", needs="jvm:WordsUatTest.notUsingMyHabitsAsFreshlyInstalledButForWhatIsBlocked、HabitsUatTest.offTheEntrySaysToRestoreAndTheRecentEmojiAreGoneTillOn、HabitsUatTest.theSwitchOnTopOfTheUserWordsIsTheSameAsInTheStatusArea、MotionNoticeUatTest.habitsOffAndOnEachSaidTheNewInTheOldsPlace")(lambda d: None)


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


case("PR-35", needs="release:gate 3（gates.py backups，只支持 redroid；模拟器上手工做）恢复 fixtures/upgrade/v0.3.0 的备份")(lambda d: None)


case("PR-36", needs="release:gate 3（gates.py backups，只支持 redroid；模拟器上手工做）恢复 fixtures/upgrade/v0.3.1 的备份")(lambda d: None)


case("PR-37", needs="release:gate 3（gates.py backups，只支持 redroid；模拟器上手工做）恢复 fixtures/upgrade/v0.3.2 的备份")(lambda d: None)


case("PR-38", needs="release:这一版正式版的备份装回 v0.3.2 正式版恢复")(lambda d: None)


case("PR-39", needs="jvm:BackupUatTest.noDocumentsProviderForTheFilesApp")(lambda d: None)


case("PR-40", needs="jvm:BackupUatTest.aBackupBringsPinyinsAndShuangpinsKeyboards")(lambda d: None)


case("PR-41", needs="release:要 v0.4.0 正式版升级后以 root 删 Android/data；发版时手动跑")(lambda d: None)
case("PR-42", needs="jvm:KotlinHostTest.anUnreadableFileIsNeverWrittenOverAndTheUserIsTold、"
     "KotlinHostTest.filesNotSettledAreReadButNotWrittenAndTheUserIsTold、"
     "UserDataManagerTest.aBackupAndARestoreAreRefusedWhileNotSettled、"
     "PunctuationMapsTest.where the data is not settled nothing is saved、"
     "EngineBridgeTest.theKeyboardsPinIsRefusedWhereTheFileWouldBeLost、EngineBridgeTest.theKeyboardsPinIsRefusedWhereDataIsNotSettled、PhraseBookTest.aRefusedSaveTakesNoEffect、"
     "ThemeFilesManagerTest.themesNotSettledAreNotWritten")(lambda d: None)
case("PR-43", needs="release:要 v0.4.0 正式版里并入雾凇拼音，再覆盖安装这一版正式版；发版时手动跑")(lambda d: None)


case("PR-44", needs="jvm:BackupUatTest.aBackupBringsTheAddressListsAndWhatTheyLearned")(lambda d: None)


case("PR-45", needs="jvm:BackupUatTest.aLearningBackupBringsWhatTheAddressBarLearned")(lambda d: None)


case("PR-46", needs="jvm:BackupUatTest.aSettingsBackupsAddressesMergeWithThePhones")(lambda d: None)


case("PR-47", needs="jvm:KotlinHostTest.aZeroFourBackupRestoredMergesItsNineKeysPinyinAgain")(lambda d: None)


case("PR-49", needs="jvm:PrivacyUatTest.aBackupTakesAWholeSentenceUsedTwiceNotOnce")(lambda d: None)


case("PR-50", needs="jvm:UserDataManagerTest.aBackupBiggerThanTheRoomLeftStopsAsItIsUnpacked")(lambda d: None)
case("PR-51", needs="manual:要恢复备份造出同名的 3.0 主题和主题包两张卡，再分别删除")(lambda d: None)
