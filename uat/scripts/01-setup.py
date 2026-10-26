"""uat/01-setup.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import IME, PKG, Blocked, add_im, case, check, in_picker, install


def im_rows(d):
    """settings 输入法: the input methods' names, top to bottom"""
    s = d.screen()
    return [n.text for n in sorted(s.app(), key=lambda n: n.b[1])
            if n.cls == "TextView" and n.clickable and n.parent and n.parent.cls == "ViewGroup"
            and n.b[1] > 200 and n.b[3] < 2200 and n.text]


def globe_cycle(d, n):
    names = []
    for _ in range(n):
        d.press("globe", after=0.8)
        d._keys = None
        names.append(d.im_label().replace("空格，", ""))
    return names


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
        install(d, d.flavor)   # the host's APK back: the rest of the run needs the app on the device
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
    # a fresh install loads its data first: the space key reads （不可用） for a while
    end = time.time() + 60
    while "拼音" not in d.im_label() and time.time() < end:
        time.sleep(1.0)
    lab = d.im_label()
    check("拼音" in lab, f"空格键上是「{lab}」")
    return "向导两步，完成后是设置首页；弹出的是幽默输入法（拼音）"


@case("SET-02", base="B0-设置")
def set02(d):
    d.sh("input keyevent KEYCODE_HOME")
    time.sleep(1.0)
    d.sh(f"am start -W -n {PKG}/org.fcitx.fcitx5.android.ui.main.MainActivity")
    time.sleep(1.0)
    s = d.screen()
    check(d.at_home(s), f"再次打开是 {s.texts()[:8]}")
    return "直接是设置首页"


case("SET-03", needs="jvm:SettingsPagesUatTest.theInputMethodsListed")(lambda d: None)


case("SET-04", needs="jvm:SettingsPagesUatTest.addingNineKeys")(lambda d: None)


@case("SET-05", base="B0-设置", mutates=True)
def set05(d):
    add_im(d, "五笔")
    s = d.screen()
    n = s.find(text="五笔")
    top = s.find(text="拼音")
    if not n or not top:
        raise Blocked(f"输入法页 {s.texts()[:6]}")
    hx = 85
    y0, y1 = n.c[1], top.b[1] + 10
    steps = [("down", hx, y0), ("sleep", 0.6)] + \
        [("move", hx, y0 - (y0 - y1) * i // 12) for i in range(1, 13)] + [("sleep", 0.3), ("up", hx, y1)]
    d.touch(*steps)
    time.sleep(1.0)
    r = im_rows(d)
    check(r == ["五笔", "拼音", "英文"], f"拖动后列表 {r}")
    d.open_page("text")
    time.sleep(1.0)
    start = d.im_label().replace("空格，", "")
    names = globe_cycle(d, 3)
    order = ["五笔", "拼音", "英文"]
    i = order.index(start) if start in order else -1
    expect = [order[(i + k) % 3] for k in (1, 2, 3)]
    check(i >= 0 and names == expect, f"开始「{start}」，地球键依次 {names}（应为 {expect}）")
    return f"列表 {r}；开始 {start}，地球键 {names}"


@case("SET-06", base="B0-设置", mutates=True)
def set06(d):
    add_im(d, "双拼")
    d.tap_text("编辑", scroll=False, after=1.0)
    s = d.screen()
    n = s.find(text="双拼")
    box = next((k for k in n.parent.kids if k.cls == "CheckBox"), None) if n and n.parent else None
    if not box:
        raise Blocked("编辑时双拼一行没有勾选框")
    d.tap(box.c, 0.8)
    d.tap_text("删除", scroll=False, after=1.2)
    s = d.screen()
    ok = next((k for k in s.nodes if k.text in ("确定", "删除") and k.cls == "Button"), None)
    if ok:
        d.tap(ok.c, 1.0)
    if d.screen().find(text="编辑") is None:
        d.back(1.0)
    r = im_rows(d)
    check("双拼" not in r, f"删除后列表 {r}")
    d.open_page("text")
    names = globe_cycle(d, 3)
    check(set(names) == {"拼音", "英文"}, f"地球键依次 {names}")
    return f"列表 {r}；地球键 {names}"


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
    check("拼音" in items and any("Android" in x or "键盘" in x for x in items), f"选择框里 {items}")
    d.back(0.8)
    return f"系统选择输入法框：{items[:4]}"



case("SET-11", needs="manual:要上游 Fcitx5 for Android 导出的备份")(lambda d: None)
