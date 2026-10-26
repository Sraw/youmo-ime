"""uat/17-hardware-keyboard.md (实体键盘): on the emulator only (`uat.py run --devices 5554`), whose
USB keyboard is uat/tools/emu-keyboard.sh's uinput one (never on redroid: there it is the host's).
"按 X" is a key of that keyboard (Device.kb). The .md is the source of truth."""
import re
import time

from uat import (PKG, Blocked, Fail, add_im, case, check, choose, gear, grouped_keys, has_keyboard_code,
                 pinyin_keyboard, shuangpin_keyboard)

MANUAL_NO_CURSOR = ("manual:要一个不告诉输入法光标位置的应用（终端、游戏、自绘编辑器）；模拟器上没有，"
                    "测试页和系统应用都告诉光标")
BUTTON = "显示屏幕键盘"


EMU = "emu:实体键盘只在模拟器上跑（--devices 5554）：redroid 里的 uinput 键盘会到宿主机"


def emu_only(d):
    if not d.emu:
        raise Blocked("实体键盘只在模拟器上跑（--devices 5554）：redroid 里的 uinput 键盘会到宿主机")


def typ(d, text, after=0.5):
    emu_only(d)
    d.kb("type", text)
    time.sleep(after)


def key(d, *names, after=0.5):
    emu_only(d)
    d.kb("key", *names)
    time.sleep(after)


def combo(d, *names, after=0.5):
    emu_only(d)
    d.kb("combo", *names)
    time.sleep(after)


def soft(s):
    return bool(s.find(rid="keyboard_view", where="ime"))


def floating(s):
    """the window by the cursor: its nodes (no resource id, outside the touch keyboard and the
    navigation bar)"""
    kb = s.find(rid="keyboard_view", where="ime")
    nav = s.find(rid="input_method_navigation_bar_view", where="ime")
    out = []
    for n in s.ime():
        if n.rid or not (n.text or n.desc):
            continue
        if kb and n.b[1] >= kb.b[1] - 5 or nav and n.b[1] >= nav.b[1]:
            continue
        out.append(n)
    return out


def fcands(s):
    """the floating window's candidates, without their number"""
    return [re.sub(r"^\d+", "", n.text) for n in floating(s) if n.clickable and re.match(r"^\d+\S", n.text or "")]


def fcand_nodes(s):
    return [n for n in floating(s) if n.clickable and re.match(r"^\d+\S", n.text or "")]


# the temporary modes' labels are keyToLabel's ("1. ", "A-1. "), shown in the same text as the word
TEMP_LABEL = r"^(?:[CASM]-)*\d+\.\s*"


def tcand_nodes(s):
    return [n for n in floating(s) if n.clickable and re.match(TEMP_LABEL + r"\S", n.text or "")]


def tcands(s):
    """the floating window's candidates in quick input / Unicode, without their label and the comment
    after them (the phrase's key, \\alpha, the character's name: LabeledCandidateItemUi appends it)"""
    return [re.sub(r"\s+[\\:A-Za-z_\- ]+$", "", re.sub(TEMP_LABEL, "", n.text)) for n in tcand_nodes(s)]


def fpre(s):
    """the floating window's code (pinyin) line"""
    t = [n.text for n in floating(s) if n.text and not n.clickable]
    return t[0] if t else ""


def button(s):
    return s.find(desc=BUTTON, where="ime")


def field_text(d):
    return d.field_text() or ""


def back_on_screen(d, timeout=6):
    d.wait(lambda s: soft(s), timeout, what="the touch keyboard")


@case("HK-01", base="B0-实体", needs=EMU)
def hk01(d):
    typ(d, "ni")
    s = d.screen()
    check(not soft(s), "按 n i 后屏幕键盘没收起")
    c, p = fcands(s), fpre(s)
    check(p == "ni", f"小窗的拼音是「{p}」")
    check("你" in c[:3], f"小窗的候选 {c[:6]}")
    b, f = button(s), d.field(s)
    check(b, "小窗上没有键盘图标")
    right = max(n.b[2] for n in fcand_nodes(s))
    check(b.b[0] >= right - 5, f"键盘图标不在右端：图标 {b.b}，候选到 {right}")
    check(f and abs(b.b[1] - f.b[3]) < 300, f"小窗不在光标附近：小窗 {b.b}，框 {f.b if f else None}")
    return f"屏幕键盘收起；小窗 `{p}` {c[:4]}，右端键盘图标"


case("HK-02", needs="jvm:HardwareKeysUatTest.spaceCommitsTheFirstADigitPicksByNumber")(lambda d: None)


@case("HK-03", base="B0-实体", mutates="learn", needs=EMU)
def hk03(d):
    typ(d, "ni ")
    key(d, "ESC")
    s = d.screen()
    fl = floating(s)
    b, f = button(s), d.field(s)
    check(b and len(fl) == 1, f"Esc 后光标旁不只一个键盘图标：{[(n.text, n.desc) for n in fl]}")
    check(f.b[1] - 10 <= b.c[1] <= f.b[3] + 10, f"图标不和光标同一行：图标 {b.b}，框 {f.b}")
    check(b.b[0] > f.b[0] + 30, f"图标不在光标右边：图标 {b.b}，框 {f.b}")
    d.tap(b.c, 1.0)
    back_on_screen(d)
    d.type("hao")
    c = d.cands()
    check("好" in c[:4], f"屏幕键盘上打 hao，候选 {c[:5]}")
    d.pick("好")
    typ(d, "a")
    s = d.screen()
    check(not soft(s), "再按实体字母，屏幕键盘没收起")
    key(d, "ESC")
    return f"只剩图标 {b.b}（框 {f.b}）；点它屏幕键盘回来，打出「{field_text(d)}」；再按字母又收起"


@case("HK-03a", base="B0-实体", needs=MANUAL_NO_CURSOR)
def hk03a(d):
    pass


@case("HK-03b", base="B0-实体", needs=MANUAL_NO_CURSOR)
def hk03b(d):
    pass


def drag(d, b, to):
    x, y = b.c
    tx, ty = to
    steps = [("down", x, y), ("sleep", 0.15)]
    for i in range(1, 9):
        steps += [("move", x + (tx - x) * i // 8, y + (ty - y) * i // 8), ("sleep", 0.03)]
    steps += [("sleep", 0.2), ("up", tx, ty)]
    d.touch(*steps)
    time.sleep(0.8)


def lone_button(d):
    """the keyboard button alone by the cursor (typed and committed, the suggestions closed)"""
    typ(d, "ni ")
    key(d, "ESC")
    s = d.screen()
    b = button(s)
    if not b:
        raise Fail(f"光标旁没有键盘图标：{[(n.text, n.desc) for n in floating(s)]}")
    return s, b


@case("HK-03c", base="B0-实体", mutates=True, needs=EMU)
def hk03c(d):
    s, b = lone_button(d)
    f = d.field(s)
    to = (b.c[0], f.b[3] + 200)
    drag(d, b, to)
    s = d.screen()
    b1 = button(s)
    check(not soft(s), "拖图标松手后屏幕键盘弹出来了")
    check(b1 and abs(b1.c[1] - to[1]) < 40, f"图标没停在拖到的地方：{b1.b if b1 else None}，拖到 {to}")
    dx, dy = b1.c[0] - b.c[0], b1.c[1] - b.c[1]
    typ(d, "abc")
    key(d, "ENTER")
    key(d, "ESC")
    s = d.screen()
    b2 = button(s)
    moved = b2 and b2.c[0] - b.c[0]
    check(b2 and abs(b2.c[1] - b1.c[1]) < 40 and moved > 30,
          f"打字后图标没和光标保持距离：先 {b1.b}，后 {b2.b if b2 else None}")
    d.open_page("long")
    lone_button(d)
    s = d.screen()
    b3, f3 = button(s), d.field(s)
    check(b3 and f3.b[1] - 10 <= b3.c[1] <= f3.b[3] + 10, f"到「长文本」后图标没回到光标右边：{b3.b if b3 else None}，框 {f3.b}")
    return f"拖到 {b1.b}（偏移 {dx},{dy}）；打字后 {b2.b}；长文本里 {b3.b}"


@case("HK-03d", base="B0-实体", mutates=True, needs=EMU)
def hk03d(d):
    s, b = lone_button(d)
    f = d.field(s)
    to = (b.c[0], f.b[3] + 200)
    drag(d, b, to)
    b1 = button(d.screen())
    if not b1:
        raise Fail("拖过之后没有图标")
    d.tap(b1.c, 1.0)
    back_on_screen(d)
    typ(d, "a")
    key(d, "ESC")
    s = d.screen()
    b2 = button(s)
    check(not soft(s), "按实体字母后屏幕键盘没收起")
    check(b2 and abs(b2.c[1] - b1.c[1]) < 40, f"图标回到了 {b2.b if b2 else None}，不是拖过去的 {b1.b}")
    return f"拖到 {b1.b}；叫出再收起后 {b2.b}"


@case("HK-29", base="B0-实体", needs=EMU)
def hk29(d):
    emu_only(d)
    d.sh("am force-stop com.android.settings; am force-stop com.google.android.settings.intelligence; "
         "am start -W -a android.settings.SETTINGS")
    time.sleep(1.5)
    s = d.screen()
    sb = s.find(text="搜索设置") or next((n for n in s.app() if "搜索" in (n.text or n.desc)), None)
    if not sb:
        raise Blocked(f"设置首页没有搜索框：{s.texts()[:8]}")
    d.tap(sb.c, 1.5)
    e = d.wait(lambda s: next((n for n in s.app() if n.cls.endswith("EditText") and n.focused), None), 8,
               what="设置的搜索框")
    try:
        return search_box(d, e)
    finally:
        d.sh("am force-stop com.android.settings; am force-stop com.google.android.settings.intelligence")


def search_box(d, e):
    d.wait(lambda s: soft(s), 6, what="屏幕键盘")
    typ(d, "ni ")
    s = d.screen()
    t = next((n.text for n in s.app() if n.cls.endswith("EditText") and n.focused), "")
    check(not soft(s), "在设置的搜索框里按 n i 空格，屏幕键盘没收起")
    check(t == "你", f"设置的搜索框里是「{t}」")
    # no Esc first: a search box offers no 联想 to close (docs/pinyin.md), so the app takes the Esc
    # and the box loses its focus
    d.tap(e.c, 1.0)
    ok = False
    try:
        back_on_screen(d, 4)
        ok = True
    except Blocked:
        pass
    check(ok, "点设置的搜索框，屏幕键盘没回来")
    return f"按 n i 空格「{t}」，屏幕键盘收起；点框回来"


case("HK-04", needs="jvm:HardwareKeyUatTest.enterAndEscapeGoToTheEngine")(lambda d: None)


case("HK-05", needs="jvm:HardwareKeyUatTest.backspaceGoesToTheEngine")(lambda d: None)


case("HK-06", needs="jvm:HardwareKeysUatTest.minusAndEqualTurnThePagesWhileTyping")(lambda d: None)


case("HK-07", needs="jvm:HardwareKeysUatTest.minusAfterACommitIsTheMark")(lambda d: None)


case("HK-08", needs="jvm:HardwareKeysUatTest.pageDownAndPageUpTurnThePages")(lambda d: None)


case("HK-09", needs="jvm:HardwareKeyUatTest.aShiftTapSwitchesChineseAndEnglish")(lambda d: None)


case("HK-10", needs="jvm:HardwareKeyUatTest.shiftWhileTypingSwitchesToEnglish")(lambda d: None)


case("HK-11", needs="jvm:HardwareKeyUatTest.shiftHeldTypesCapitalsAndSwitchesNothing")(lambda d: None)


case("HK-12", needs="jvm:HardwareKeyUatTest.ctrlSpaceSwitchesAndTypesNoSpace")(lambda d: None)


@case("HK-30", base="B0-实体", needs="manual:要分屏（上面测试页、下面设置）再点回上面的窗口；模拟器上 adb 摆不出分屏，手动做")
def hk30(d):
    pass


case("HK-13", needs="jvm:HardwareKeyUatTest.shiftBackFromEnglishIsWubiNotPinyin")(lambda d: None)


case("HK-14", needs="jvm:HardwareKeyUatTest.ctrlShiftGoesRoundSkippingHandwritingAndNineKeys")(lambda d: None)


case("HK-15", needs="jvm:HardwareKeyUatTest.shiftThenCtrlToo")(lambda d: None)


case("HK-16", needs="jvm:HardwareKeyUatTest.ctrlCAndCtrlVAreForTheApp")(lambda d: None)


@case("HK-17", base="B0-实体", needs=EMU)
def hk17(d):
    others = [x for x in d.sh("ime list -s").split() if x and PKG not in x]
    if not others:
        raise Blocked("设备上没有别的输入法")
    pid0 = d.sh(f"pidof {PKG}").strip()
    typ(d, "a")
    key(d, "ESC")
    d.sh("logcat -c")   # only a crash from this case counts, not one left by an earlier case
    combo(d, "META", "SPACE", after=1.2)
    cur = d.sh("settings get secure default_input_method").strip()
    pid1 = d.sh(f"pidof {PKG}").strip()
    # the second grep prints the "Process: <pkg>" line: anything printed is our crash
    crash = bool(d.sh(f"logcat -d -t 300 | grep -A2 'FATAL EXCEPTION' | grep -m1 {PKG} || true").strip())
    d.sh(f"ime set {PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
    check(pid1 and pid1 == pid0 and not crash, f"按 Meta+空格 后输入法进程 {pid0} → {pid1}，崩溃 {crash}")
    return f"Meta+空格 后当前输入法 {cur.split('/')[0]}；幽默没报错"


case("HK-18", needs="jvm:HardwareKeyUatTest.handwritingTypedOnIsPinyin")(lambda d: None)


case("HK-19", needs="jvm:HardwareKeyUatTest.theTouchKeyboardBackIsHandwriting")(lambda d: None)


case("HK-20", needs="jvm:HardwareKeyUatTest.nineKeysTypedOnAndBack")(lambda d: None)


case("HK-21", needs="jvm:HardwareKeysUatTest.returnPutsTheWrittenInThenGoesToTheApp")(lambda d: None)


case("HK-22", needs="jvm:HardwareKeyUatTest.aShortcutsChoiceIsKeptWhenTheTouchKeyboardIsBack")(lambda d: None)


case("HK-23", needs="jvm:HardwareKeysUatTest.englishTypesTheKeysAsPressed")(lambda d: None)


@case("HK-24", base="B0-实体", page="password", needs=EMU)
def hk24(d):
    typ(d, "abc123")
    s = d.screen()
    t = d.field_text(s) or ""
    check(len(t) == 6, f"框里「{t}」")
    check(not fcands(s) and not fpre(s), f"有候选窗：`{fpre(s)}` {fcands(s)[:3]}")
    return f"框里 {len(t)} 个字符（「{t}」），没有候选窗"


case("HK-25", needs="jvm:HardwareKeyUatTest.noCandidatesWindowKeepsTheTouchKeyboard")(lambda d: None)


case("HK-25a", needs="jvm:HardwareKeyUatTest.shortcutsOffSwitchNothing")(lambda d: None)


case("HK-26", needs="jvm:HardwareKeyUatTest.theCandidatesWindowStandsUp")(lambda d: None)


case("HK-27", needs="jvm:HardwareKeyUatTest.unpluggedTheTouchKeyboardComesBack")(lambda d: None)


case("HK-28", needs="jvm:HardwareKeyUatTest.landscapeSplitBack")(lambda d: None)


case("HK-33", base="B0-实体", needs="manual:要一个输入框在屏幕最顶上的应用（候选窗贴屏幕顶）；测试页的框不够高，手动跑")(lambda d: None)


# ---------------------------------------------------------------- Shift+Delete (HK-31, HK-32)

# emu-keyboard.sh's key/combo release at once; holding a key down for a while (the system's key
# repeat) or releasing in a given order needs the raw events, on the same device it registered
case("HK-31", needs="jvm:HardwareKeyUatTest.shiftDeleteForgetsTheHighlightedOnceHeld")(lambda d: None)


case("HK-32", needs="jvm:HardwareKeyUatTest.aQuickShiftDeleteIsNoShiftTap")(lambda d: None)


case("HK-34", needs="jvm:HardwareKeysUatTest.thePageSizeSetIsTheWindowsPage")(lambda d: None)


def tip_seen(d, timeout=1.5, text="全角标点"):
    """the engine's tip (by default the one about full-width punctuation), waited for; its text, else None"""
    end = time.time() + timeout
    while time.time() < end:
        n = d.screen().find(contains=text, where="all")
        if n:
            return n.text
        time.sleep(0.1)
    return None


@case("HK-35", base="B0-实体", mutates=True, needs=EMU)
def hk35(d):
    typ(d, "a")   # the touch keyboard away first
    key(d, "ESC")
    d.step("Ctrl+. 后马上打字")
    # timed from the shortcut to the last look, the screen reads on either side included: the tip
    # can't be older than that, so under 1.8 s its 2 s timeout can't be what put it away (F3583)
    t0 = time.time()
    combo(d, "CTRL", "DOT", after=0)
    t1 = tip_seen(d)
    typ(d, "n", after=0.4)
    gone1 = tip_seen(d, 0.3) is None
    late = time.time() - t0
    if t1 and gone1 and late >= 1.8:
        # the 2 s timeout could have closed it by now: a pass would not show typing did
        raise Blocked(f"按下 Ctrl+. 后 {late:.1f} 秒才看完提示，2 秒超时可能已经收起它")
    key(d, "ESC")
    d.step("Ctrl+. 后不打字")
    combo(d, "CTRL", "DOT", after=0)
    t2 = tip_seen(d)
    time.sleep(2.6)   # said for 2 s (rule 5), not the 6 s a failure stays
    gone2 = tip_seen(d, 0.3) is None
    combo(d, "CTRL", "DOT", after=0)
    t3 = tip_seen(d)
    check(t1, "第一次 Ctrl+. 没有提示")
    check(gone1, f"打了 n 提示「{t1}」还在")
    check(t2 and t2 != t1, f"第二次 Ctrl+. 的提示「{t2}」（第一次「{t1}」）")
    check(gone2, f"不打字约 2.6 秒后提示「{t2}」还在")
    check(t3 == t1, f"第三次 Ctrl+. 的提示「{t3}」，和第一次「{t1}」不同")
    return f"「{t1}」→「{t2}」→「{t3}」，打字那次 {late:.1f} 秒内看完"


def hclear(d):
    """the field emptied by this keyboard's Backspace: the touch keyboard, which d.clear() uses, is away"""
    key(d, *["BACKSPACE"] * 6)


def away(d):
    """the touch keyboard put away by a key of this keyboard, nothing left typed"""
    typ(d, "a")
    key(d, "ESC")
    hclear(d)


@case("HK-36", base="B0-实体", needs=EMU)
def hk36(d):
    away(d)
    combo(d, "CTRL", "SHIFT", "F", after=0)
    t1 = tip_seen(d, text="繁")
    typ(d, "guojia")
    c1 = fcands(d.screen())
    key(d, "SPACE")
    f1 = field_text(d)
    hclear(d)
    combo(d, "CTRL", "SHIFT", "F", after=0)
    t2 = tip_seen(d, text="简")
    typ(d, "guojia ")
    f2 = field_text(d)
    check(t1, "第一次 Ctrl+Shift+F 光标旁没有繁體的提示")
    check("國家" in c1[:3], f"繁體下 guojia 的候选 {c1[:5]}")
    check(f1 == "國家", f"繁體下上屏「{f1}」")
    check(t2, "第二次 Ctrl+Shift+F 没有切回简体的提示")
    check(f2 == "国家", f"切回简体后上屏「{f2}」")
    return f"「{t1}」→「{f1}」；「{t2}」→「{f2}」"


# Android's window manager policy takes every key pressed with Meta held before an input method sees it
# ("Dropped event because policy consumed it" for Meta and the key, down and up; hk37-debug.md): fcitx's
# Super+` and Super+; triggers are kept, asserted on the JVM, and reached only where a system delivers them
@case("HK-37", base="B0-实体", needs=EMU)
def hk37(d):
    away(d)
    combo(d, "META", "GRAVE", after=0)
    t = tip_seen(d, text="快速输入")
    p = fpre(d.screen())
    f0 = field_text(d)
    typ(d, "ni ")
    f = field_text(d)
    check(not t, f"按 Super+` 光标旁出了「{t}」")
    check(not p and f0 == "", f"Super+` 后小窗「{p}」框「{f0}」")
    check(f == "你", f"之后 ni 空格 上屏「{f}」")
    return f"Super+` 后没有快速输入，小窗「{p}」框「{f0}」；再打「{f}」"


case("HK-38", needs="jvm:QuickPhraseTest.superGraveAndSuperSemicolonTriggerOnPressOnly")(lambda d: None)


@case("HK-39", base="B0-实体", needs=EMU)
def hk39(d):
    away(d)
    combo(d, "CTRL", "ALT", "SHIFT", "U", after=0)
    t = tip_seen(d, text="Unicode")
    typ(d, "degree")
    s = d.screen()
    c = tcands(s)
    nodes = tcand_nodes(s)
    labels = [n.text for n in nodes][:3]
    combo(d, "ALT", "1")
    f = field_text(d)
    hclear(d)
    typ(d, "shi")
    py = fcand_nodes(d.screen())
    hclear(d)
    check(len(py) > 1 and py[0].b[1] == py[1].b[1] and py[0].b[0] < py[1].b[0],
          f"之后拼音候选不是横排 {[n.b[:2] for n in py[:3]]}")
    check(t, "按 Ctrl+Alt+Shift+U 光标旁没有「Unicode：」")
    check(any("°" in x for x in c), f"degree 的候选 {c[:5]}")
    check(labels and all(x.startswith("A-") for x in labels), f"标号不是 Alt 加数字 {labels}")
    # 方向「自动」: the Unicode list asks for vertical, pinyin stays horizontal (F3975)
    check(len(nodes) > 1 and all(a.b[1] < b.b[1] and abs(a.b[0] - b.b[0]) < 5 for a, b in zip(nodes, nodes[1:])),
          f"Unicode 候选不是竖排 {[n.b[:2] for n in nodes[:3]]}")
    check(f and c and f in c[0], f"Alt+1 上屏「{f}」，第一个候选 {c[:1]}")
    return f"「{t}」{labels}，Alt+1 →「{f}」"


@case("HK-40", base="B0-实体", needs=EMU)
def hk40(d):
    away(d)
    combo(d, "CTRL", "SHIFT", "U")
    typ(d, "4f60 ")
    f = field_text(d)
    hclear(d)
    typ(d, "ni")
    p = fpre(d.screen())
    key(d, "ESC")
    check(f == "你", f"4f60 空格 后框里「{f}」")
    check(p == "ni", f"之后打 ni，小窗的拼音是「{p}」")
    return f"「{f}」；之后拼音 `{p}`"


@case("HK-41", base="B0-实体", needs=EMU)
def hk41(d):
    away(d)
    im0 = d.sh("settings get secure default_input_method").strip()
    shown = []
    for k in (("CTRL", "SEMI"), ("META", "SPACE")):
        combo(d, *k, after=0.8)
        shown += [n.text or n.desc for n in floating(d.screen())]
    im1 = d.sh("settings get secure default_input_method").strip()
    d.sh(f"ime set {PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
    typ(d, "ni ")
    f = field_text(d)
    # the keyboard icon by the cursor may stay; a list or an input method's name may not
    shown = [x for x in shown if x != BUTTON]
    check(not shown, f"按下后光标旁出现 {shown[:5]}")
    check(im1 == im0, f"输入法 {im0.split('/')[0]} → {im1.split('/')[0]}")
    check(f == "你", f"之后 ni 空格 上屏「{f}」")
    return f"两个快捷键后光标旁什么也没有，输入法没变；再打「{f}」"


@case("HK-42", base="B0-实体", needs=EMU)
def hk42(d):
    away(d)
    # the system takes it (as Super+`, HK-37): what is checked is that nothing reaches the pinyin
    combo(d, "META", "J", after=0)
    p = fpre(d.screen())
    f0 = field_text(d)
    typ(d, "ni ")
    f = field_text(d)
    check(not p, f"Super+J 后小窗的拼音是「{p}」")
    check(f0 == "", f"Super+J 后框里「{f0}」")
    check(f == "你", f"之后 ni 空格 上屏「{f}」")
    return f"Super+J 后小窗「{p}」框「{f0}」；再打「{f}」"


def show_touch(d, must=True):
    """the toolbar's 显示屏幕键盘, as HK-43 to HK-45 bring the touch keyboard up under a hardware one;
    not must: absent is fine (the touch keyboard already up)"""
    s = d.screen()
    b = s.find(desc=BUTTON, where="ime") or s.find(text=BUTTON, where="ime")
    if b:
        d.tap(b.c, 1.0)
    elif must:
        raise Blocked("工具栏上没有「显示屏幕键盘」")


def to_14(d):
    """拼音's 键盘 set to 14 键, back on the test page"""
    emu_only(d)
    d.hide_keyboard()
    pinyin_keyboard(d, "14 键")
    d.open_page("text")


@case("HK-43", base="B0-实体", mutates=True, needs=EMU)
def hk43(d):
    to_14(d)
    away(d)
    typ(d, "nihao")
    c = fcands(d.screen())
    key(d, "SPACE")
    f1 = field_text(d)
    hclear(d)
    typ(d, "nih")
    show_touch(d)
    grouped_keys(d, "as")
    f2 = field_text(d)
    check(c[:1] == ["你好"], f"实体键盘 nihao 的候选 {c[:4]}")
    check(f1 == "你好", f"空格上屏「{f1}」")
    check(f2 is not None and f2.startswith("nih") and not has_keyboard_code(f2), f"点屏幕上的 AS 后框里「{f2}」")
    return f"「{f1}」；中途换屏幕键盘后「{f2}」"


@case("HK-44", base="B0-实体", mutates=True, needs=EMU)
def hk44(d):
    # the Escape half is KeyboardPairTest's (.md): on the device a hardware key puts the touch keyboard
    # away whoever gets the Escape (no 联想 left to see) and Esc leaves a web field's text alone, so
    # the Escape here is a smoke and only the digit picking is checked
    to_14(d)
    show_touch(d, must=False)
    # BN UI GH AS OP is 不好 as well as 你好: each tapped on its letter's half, as a finger means it
    grouped_keys(d, "bn ui gh as op", at=[.75, .75, .75, .25, .25])
    d.space()
    f1 = field_text(d)
    pred = d.candidates(d.screen())
    key(d, "ESC")
    typ(d, "ni")
    c = fcands(d.screen())
    key(d, "2")
    f2 = field_text(d)
    check(f1 == "你好", f"14 键上屏「{f1}」")
    check(pred, f"14 键上屏后没有联想：{pred[:3]}")
    check(len(c) > 1 and f2 == f1 + c[1], f"ni 按 2：候选 {c[:3]}，框里「{f2}」")
    return f"「{f1}」；Esc（冒烟，规则在 KeyboardPairTest）；ni 2 →「{f2}」"


@case("HK-45", base="B0-实体", mutates=True, needs=EMU)
def hk45(d):
    emu_only(d)
    d.hide_keyboard()
    add_im(d, "双拼")
    gear(d, "双拼")
    choose(d, "双拼方案", "微软")
    shuangpin_keyboard(d, "18 键")
    d.open_page("text")
    d.switch_im("双拼")
    # switching by the touch keyboard's globe leaves it up: the toolbar button only when it went away
    show_touch(d, must=False)
    grouped_keys(d, "bn io")
    c = d.cands()
    key(d, "2")
    f1 = field_text(d)
    hclear(d)
    # the hardware key put the touch keyboard away: shown again from the toolbar
    show_touch(d)
    grouped_keys(d, "bn io")
    typ(d, "d;")
    f2 = field_text(d)
    p = fpre(d.screen())
    c2 = fcands(d.screen())
    hclear(d)
    check(len(c) > 1 and f1 == c[1], f"BN IO 按 2：候选 {c[:3]}，框里「{f1}」")
    check(f2 and f2.isascii() and f2.isalpha() and not has_keyboard_code(f2), f"按 d 后框里「{f2}」")
    check(c2 and ("定" in c2[:5] or "顶" in c2[:5]), f"d ; 的小窗「{p}」候选 {c2[:5]}")
    return f"「{f1}」；「{f2}」+ d; {c2[:3]}"
