"""uat/01-setup.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import IME, PKG, Blocked, case, check, import_packs, in_picker, install


@case("SET-01", base="B0", mutates=True)
def set01(d):
    # 前提: the version under test reinstalled (the installed apk, copied off the device first),
    # another keyboard the current one
    path = d.sh(f"pm path {PKG}").strip().replace("package:", "").splitlines()[0]
    copied = d.sh(f"cp {path} /data/local/tmp/uat-youmo.apk && cmp {path} /data/local/tmp/uat-youmo.apk && echo same")
    if "same" not in copied:
        raise Blocked(f"复制安装包失败：{copied.strip()[:120]}")
    d.sh(f"pm uninstall {PKG}")
    out = d.sh("pm install -r /data/local/tmp/uat-youmo.apk")
    if "Success" not in out:
        install(d, d.voice)   # the host's APK back and its packs: the rest of the run needs them
        raise Blocked(f"重装失败：{out.strip()}")
    d.sh("ime set com.android.inputmethod.latin/.LatinIME")
    d.sh(f"am start -W -n {PKG}/org.fcitx.fcitx5.android.ui.main.MainActivity")
    time.sleep(1.5)
    s = d.screen()
    t1 = s.texts()
    check(any("启用输入法" in t for t in t1), f"向导第一步是 {t1[:8]}")
    # formerly PR-29: the restore offer is a line under step 1, not a dialog, and opens the file picker
    d.step("从备份恢复一行")
    row = s.find(text="以前用过？从备份恢复")
    check(row, f"向导第一步没有「以前用过？从备份恢复」：{t1[:8]}")
    check(not any(n.rid == "button1" for n in s.nodes), "向导第一步弹了对话框")
    d.tap(row.c, 0.5)
    in_picker(d)
    top = d.top_activity()
    check("documentsui" in top, f"点了以后是 {top}，不是文件选择器")
    d.back(1.5)
    d.step("启用输入法")
    s = d.screen()
    t1 = s.texts()
    check(any("启用输入法" in t for t in t1), f"从文件选择器返回后是 {t1[:8]}")
    btn = next((n for n in s.app() if n.cls == "Button"), None)
    if not btn:
        raise Blocked(f"向导第一步没有按钮：{t1[:8]}")
    d.tap(btn.c, 2.0)
    # the system's 屏幕键盘 page: the switch on our row (the row's text opens our own settings),
    # then the warning's 确定
    s = d.screen()
    sws = [n for n in s.nodes if n.cls == "Switch"]
    ours = next((n for n in s.nodes if n.text.startswith("幽默")), None)
    sw = next((w for w in sws if ours and w.b[1] >= ours.b[1] - 60), sws[-1] if sws else None)
    if not sw:
        raise Blocked(f"系统屏幕键盘页没有开关：{s.texts(where='all')[:10]}")
    d.tap(sw.c, 1.2)
    for _ in range(3):
        s = d.screen()
        ok = next((n for n in s.nodes if n.text in ("确定", "OK") and n.clickable), None)
        if not ok:
            break
        d.tap(ok.c, 1.0)
    d.back(1.5)
    s = d.screen()
    nxt = s.find(text="下一步")
    if not nxt:
        raise Blocked(f"回到向导第一步后 {s.texts(where='all')[:8]}")
    d.tap(nxt.c, 1.2)
    s = d.screen()
    t2 = s.texts()
    check(any("选择" in t and "默认输入法" in t for t in t2) and s.find(text="选择输入法"),
          f"向导第二步是 {t2[:8]}")
    d.tap(s.find(text="选择输入法").c, 1.5)
    s = d.screen()
    pick = [n for n in s.nodes if n.text.startswith("幽默")]
    if not pick:
        raise Blocked(f"选择输入法里没有幽默输入法：{s.texts(where='all')[:10]}")
    d.tap(pick[-1].c, 2.0)
    done = next((n for n in d.screen().app() if n.text == "完成" and n.cls == "Button"), None)
    if done:
        d.tap(done.c, 1.5)
    s = d.screen()
    check(d.at_home(s), f"两步完成后是 {s.texts()[:8]}")
    cur = d.sh("settings get secure default_input_method").strip()
    check(cur == IME, f"系统当前输入法是 {cur}")
    d.open_page("text")
    # a fresh install has no resource packs: the keyboard asks for the text pack, then types once
    # it is imported (as through the 资源包 page: the debug build's import, the same checks)
    d.step("没有文字包时键盘上提示导入")
    d.wait(lambda x: x.find(contains="需要导入文字包", where="ime"), 20, what="键盘上的「需要导入文字包」")
    import_packs(d, d.voice)
    # then it loads its data: the space key reads （不可用） for a while
    end = time.time() + 60
    while "拼音" not in d.im_label() and time.time() < end:
        time.sleep(1.0)
    lab = d.im_label()
    check("拼音" in lab, f"空格键上是「{lab}」")
    return "向导两步，完成后是设置首页；键盘先提示导入文字包，导入后是幽默输入法（拼音）"


case("SET-02", needs="jvm:SettingsMenuUatTest.enabledAndSelectedTheAppOpensOnItsHomePage")(lambda d: None)


case("SET-03", needs="jvm:SettingsPagesUatTest.theInputMethodsListed")(lambda d: None)


case("SET-05", needs="jvm:ImeSwitchUatTest.draggedFirstTheGlobeKeyGoesInTheNewOrder")(lambda d: None)


case("SET-06", needs="jvm:ImeSwitchUatTest.aDeletedInputMethodIsNoLongerGoneRoundTo")(lambda d: None)


case("SET-08", needs="jvm:ImeSwitchUatTest.theGlobeKeyGoesRoundAndTheSpaceSays")(lambda d: None)


@case("SET-09", base="B0", mutates=True)
def set09(d):
    g = d.keys(refresh=True)["globe"]
    d.hold(g, 900)
    time.sleep(1.0)
    s = d.screen()
    t = s.texts(where="all")
    check(any("选择输入法" in x for x in t), f"长按后屏幕上 {t[:10]}")
    # Android 14's picker lists each enabled input method's subtypes: ours as 拼音, 英文
    i = next(i for i, x in enumerate(t) if "选择输入法" in x)
    items = t[i + 1:i + 6]
    # the other: AOSP's keyboard on redroid, Gboard on the emulator's google_apis image
    check("拼音" in items and any("Android" in x or "键盘" in x or "Gboard" in x for x in items), f"选择框里 {items}")
    d.back(0.8)
    return f"系统选择输入法框：{items[:4]}"


case("SET-11", needs="manual:要上游 Fcitx5 for Android 导出的备份")(lambda d: None)


case("SET-12", needs="jvm:SettingsPagesUatTest.addingAnInputMethodOffersNoNineKeysAndWubiFirst")(lambda d: None)


case("SET-13", needs="jvm:KotlinHostTest.zeroFoursNineKeysPinyinBecomesPinyinsKeyboardAndTheUserIsToldOnce")(lambda d: None)


case("SET-14", needs="jvm:KotlinHostSchemeTest.anUpgradeKeepsItsScheme")(lambda d: None)


case("SET-15", needs="jvm:KotlinHostSchemeTest.aDroppedSchemeIsXiaoheAndTheUserIsToldOnce")(lambda d: None)
