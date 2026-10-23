"""uat/18-lifecycle.md. The .md is the source of truth; each function follows its case's
前提/操作/预期. A turn of the device is the display resized to landscape (Device.rotate)."""
import time

from uat import BROWSER, PKG, Blocked, case, check, has, home, open_url


def fields(d, s=None):
    s = s or d.screen()
    return [n for n in s.app() if n.cls == "EditText" and n.pkg == "org.chromium.webview_shell"
            and n.rid != "url_field"]


def tap_field(d, i=0, after=1.2):
    f = fields(d)
    if len(f) <= i:
        raise Blocked(f"页面上没有第 {i + 1} 个框")
    d.tap(f[i].c, after)
    d._keys = None


def keyboard_up(d):
    return bool(d.screen().find(rid="keyboard_view", where="ime"))


@case("LC-01", mutates=True)
def lc01(d):
    d.type("nihao")
    d.cands()
    t0 = d.field_text()
    d.rotate(1)
    s = d.screen()
    t1 = d.field_text(s) if d.field(s) else (fields(d, s)[0].text if fields(d, s) else None)
    if not keyboard_up(d):
        tap_field(d)
    d.shot("full", "横屏点框后")
    d.type("hao")
    d.cands()
    d.space()
    t = d.field_text()
    d.rotate(0)
    check(not t0, f"转屏前框里「{t0}」")
    check(not t1, f"转屏后框里「{t1}」")
    check(t == "好", f"最后框里「{t}」")
    return f"转屏前后框里都空；最后「{t}」"


@case("LC-02", page="long", mutates=True)
def lc02(d):
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 0.6)   # the very start of the text
    d.expand_toolbar()
    d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
    s = d.screen()
    sel = s.find(desc="选择", where="ime") or s.find(text="选择", where="ime")
    right = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    d.tap(sel.c, 0.3)
    d.taps([right.c] * 4, gap=0.1, after=0.4)
    d.rotate(1)
    s = d.screen()
    if not keyboard_up(d):
        raise Blocked("横屏后键盘收起了")
    # the keyboard is rebuilt for landscape and comes back on the letters; the selection is the
    # field's, so the edit panel's 复制 copies it
    cp = s.find(desc="复制", where="ime") or s.find(text="复制", where="ime")
    if not cp and keyboard_up(d):
        d.expand_toolbar()
        d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
        s = d.screen()
        cp = s.find(desc="复制", where="ime") or s.find(text="复制", where="ime")
    if not cp:
        raise Blocked(f"横屏后没有文本编辑面板：{[n.desc or n.text for n in s.ime() if n.desc or n.text][:10]}")
    d.tap(cp.c, 0.6)
    d.rotate(0)
    open_url(d, "text")
    d.expand_toolbar()
    d.tap_text("文本编辑", where="ime", scroll=False, after=0.8)
    s = d.screen()
    ps = s.find(desc="粘贴", where="ime") or s.find(text="粘贴", where="ime")
    d.tap(ps.c, 0.8)
    t = d.field_text()
    check(t == "今天天气", f"粘贴出「{t}」")
    return f"粘贴出「{t}」"


@case("LC-03", mutates="learn")
def lc03(d):
    d.type("ni")
    d.cands()
    d.sh("input keyevent KEYCODE_APP_SWITCH")
    time.sleep(1.2)
    d.sh("am start -n com.android.settings/.Settings")
    time.sleep(1.5)
    d.sh(f"am start -n {BROWSER} -f 0x20000")   # REORDER_TO_FRONT: the page as it was, like 最近任务
    time.sleep(1.5)
    s = d.screen()
    t0 = fields(d, s)[0].text if fields(d, s) else None
    tap_field(d)
    s = d.screen()
    p, c = d.preedit(s), d.candidates(s)
    d.type("hao")
    d.cands()
    d.space()
    t = d.field_text()
    check(not t0 or t0 == "", f"回来后框里「{t0}」")
    check(not p and not c, f"键盘上残留 `{p}` {c[:3]}")
    check(t == "好", f"最后「{t}」")
    return f"没有残留；「{t}」"


@case("LC-04")
def lc04(d):
    open_url(d, "")
    d.type("ni")
    d.cands()
    tap_field(d, 1)
    s = d.screen()
    f = fields(d, s)
    t0, t1, p = f[0].text, f[1].text, d.preedit(s)
    d.type("ni")
    c = d.cands()
    check(not t0, f"普通文本框里「{t0}」")
    check(not t1 and not p.replace("ni", ""), f"句子框里「{t1}」，拼音行 `{p}`")
    check(has(c, "你"), f"再打 ni 候选 {c[:3]}")
    return "两个框都没留下 ni；能正常打字"


@case("LC-05")
def lc05(d):
    open_url(d, "number")
    time.sleep(0.5)
    open_url(d, "text")
    time.sleep(0.5)
    k = d.keys(refresh=True)
    check("q" in k, f"回到普通文本后键盘 {sorted(k)[:10]}")
    return "字母键盘"


@case("LC-06")
def lc06(d):
    open_url(d, "")
    d.press("符", after=1.0)
    tap_field(d, 1)
    k = d.keys(refresh=True)
    check("q" in k, "换框后不是字母键盘")
    return "回到字母键盘"


@case("LC-07", base="B0-英文")
def lc07(d):
    d.hide_keyboard()
    time.sleep(0.6)
    tap_field(d)
    lab = d.im_label()
    check("英文" in lab, f"再弹出是「{lab}」")
    return f"「{lab}」"


@case("LC-08", mutates="learn")
def lc08(d):
    d.type("ni")
    d.cands()
    d.sh("input keyevent KEYCODE_SLEEP")
    time.sleep(1.5)
    d.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    time.sleep(1.5)
    tap_field(d)
    up = keyboard_up(d)
    d.clear()
    d.type("nihao")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(up, "解锁点框后键盘没出来")
    check(t == "你好", f"接着打出「{t}」（候选 {c[:2]}）")
    return f"键盘正常；「{t}」"


def night(d, on, l0):
    """系统深色模式 on/off with the keyboard up; returns (still up, its brightness after, the text typed).
    The brightness is polled: the theme follows once the configuration change reaches the service"""
    box = (0, 1600, 1080, 2100)
    d.sh(f"cmd uimode night {'yes' if on else 'no'}")
    end, l1 = time.time() + 5, l0
    while time.time() < end:
        # a sample counts only with the keyboard there on both sides of it: in the gap while its
        # window is rebuilt the box shows the page, light whatever the keyboard's theme
        if keyboard_up(d):
            l = d.lum(box)
            if keyboard_up(d):
                l1 = l
                if (l1 < l0 - 40) if on else (l1 > l0 + 40):
                    break
        time.sleep(0.3)
    # the keyboard's window is rebuilt for the new configuration: gone for a moment, not for good
    end, up = time.time() + 3, False
    while time.time() < end and not up:
        up = keyboard_up(d)
        if not up:
            time.sleep(0.3)
    if not up:
        tap_field(d)
        l1 = d.lum(box)
    d.shot("kb", f"切到{'深色' if on else '浅色'}后")
    d.clear()
    d.type("nihao")
    d.cands()
    d.space()
    return up, l1, d.field_text()


@case("LC-09", mutates=True, visual=True)
def lc09(d):
    l0 = d.lum((0, 1600, 1080, 2100))
    d.step("切到深色")
    up1, l1, t1 = night(d, True, l0)
    check(up1, "切深色后键盘没了（要点框才回来）")
    check(l1 < l0 - 40, f"键盘亮度 {l0:.0f} → {l1:.0f}（没换成深色）")
    check(t1 == "你好", f"深色时打出「{t1}」")
    d.step("切回浅色")
    up2, l2, t2 = night(d, False, l1)
    check(up2, "切回浅色后键盘没了（要点框才回来）")
    check(l2 > l1 + 40, f"键盘亮度 {l1:.0f} → {l2:.0f}（没换回浅色）")
    check(t2 == "你好", f"浅色时打出「{t2}」")
    return f"亮度 {l0:.0f}→{l1:.0f}→{l2:.0f}；两次都打出「你好」"


@case("LC-10", base="B0-设置", mutates=True)
def lc10(d):
    home(d, "更多选项")
    d.goto("开发者", "重启 Fcitx 实例")
    d.reload_n += 1
    d.sh(f"am start -a android.intent.action.VIEW -d 'http://127.0.0.1:8000/fields.html?r={d.reload_n}#text' -n {BROWSER}")
    time.sleep(1.0)
    tap_field(d, 0, after=0.3)
    if keyboard_up(d):
        d.type("nihao", after=0.2)
    for _ in range(30):
        if keyboard_up(d) and "不可用" not in d.im_label():
            break
        time.sleep(1.0)
    d.clear()
    d.type("nihao")
    c = d.cands(timeout=20, want="你好")
    d.space()
    t = d.field_text()
    alive = d.sh(f"pidof {PKG}").strip()
    check(alive, "应用崩了")
    check(t == "你好", f"打出「{t}」（候选 {c[:2]}）")
    return f"「{t}」"


@case("LC-11", mutates="learn")
def lc11(d):
    pid = d.sh(f"pidof {PKG}").strip()
    if not pid:
        raise Blocked("输入法进程没在跑")
    d.root(f"kill -9 {pid}")
    time.sleep(1.0)
    open_url(d, "text")
    for _ in range(30):
        if keyboard_up(d) and "不可用" not in d.im_label():
            break
        time.sleep(1.0)
    d.type("nihao")
    c = d.cands(timeout=30, want="你好")
    d.space()
    t = d.field_text()
    check(t == "你好", f"打出「{t}」（候选 {c[:2]}）")
    return f"「{t}」"

