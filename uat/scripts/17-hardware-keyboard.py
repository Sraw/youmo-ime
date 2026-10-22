"""uat/17-hardware-keyboard.md (实体键盘): on the emulator only (`uat.py run --devices 5554`), whose
USB keyboard is uat/tools/emu-keyboard.sh's uinput one (never on redroid: there it is the host's).
"按 X" is a key of that keyboard (Device.kb). The .md is the source of truth."""
import re
import time

from uat import PKG, Blocked, Fail, add_im, case, check, choose, home, script

MANUAL_NO_CURSOR = ("skip:要一个不告诉输入法光标位置的应用（终端、游戏、自绘编辑器）；模拟器上没有，"
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


def fpre(s):
    """the floating window's code (pinyin) line"""
    t = [n.text for n in floating(s) if n.text and not n.clickable]
    return t[0] if t else ""


def button(s):
    return s.find(desc=BUTTON, where="ime")


def flash(d, want, timeout=2.0):
    """wait for the input method's name by the cursor; returns it, else what was seen"""
    end, seen = time.time() + timeout, []
    while time.time() < end:
        t = [n.text for n in floating(d.screen()) if n.text and not n.clickable]
        seen += [x for x in t if x not in seen]
        if want in t:
            return want
        time.sleep(0.1)
    return seen


def esc_flash(d):
    """the name by the cursor gone: a key it ignores"""
    time.sleep(1.2)


def field_text(d):
    return d.field_text() or ""


def back_on_screen(d, timeout=6):
    d.wait(lambda s: soft(s), timeout, what="the touch keyboard")


def tap_button(d):
    s = d.screen()
    b = button(s)
    if not b:
        raise Fail(f"光标旁没有键盘图标：{[(n.text, n.desc) for n in floating(s)]}")
    d.tap(b.c, 1.0)
    back_on_screen(d)


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


@case("HK-02", base="B0-实体", mutates="learn", needs=EMU)
def hk02(d):
    typ(d, "nihao ")
    t1 = field_text(d)
    check(t1 == "你好", f"n i h a o 空格后框里「{t1}」")
    typ(d, "shi")
    c = fcands(d.screen())
    if len(c) < 3:
        raise Fail(f"shi 的候选窗 {c}")
    key(d, "3")
    t = field_text(d)
    check(t == "你好" + c[2], f"按 3（第 3 个是「{c[2]}」），框里「{t}」")
    return f"「你好」，shi 第 3 个「{c[2]}」→「{t}」"


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


@case("HK-04", base="B0-实体", needs=EMU)
def hk04(d):
    typ(d, "abc")
    key(d, "ENTER")
    t1 = field_text(d)
    typ(d, "xyz")
    key(d, "ESC")
    t = field_text(d)
    check(t1 == "abc", f"回车后框里「{t1}」")
    check(t == "abc", f"Esc 后框里「{t}」")
    return f"回车「{t1}」；Esc 后「{t}」"


@case("HK-05", base="B0-实体", needs=EMU)
def hk05(d):
    typ(d, "nihao")
    key(d, "BACKSPACE")
    p = fpre(d.screen())
    key(d, "ESC")
    check(p.replace(" ", "").replace("'", "") == "niha", f"退格后小窗是「{p}」")
    return f"「{p}」"


def page_check(d, down, up):
    typ(d, "shi")
    p1 = fcands(d.screen())
    key(d, down)
    p2 = fcands(d.screen())
    key(d, up)
    p3 = fcands(d.screen())
    check(p2 and p2 != p1, f"{down} 没翻页：{p1[:4]} → {p2[:4]}")
    check(p3 == p1, f"{up} 没翻回第一页：{p1[:4]} → {p3[:4]}")
    return p1, p2


@case("HK-06", base="B0-实体", needs=EMU)
def hk06(d):
    p1, p2 = page_check(d, "EQUAL", "MINUS")
    key(d, "ESC")
    key(d, "MINUS", "EQUAL")
    t = field_text(d)
    check(t == "-=", f"没在打字时按 - =，框里「{t}」")
    return f"第一页 {p1[:3]}，= 后 {p2[:3]}，- 回来；框里「{t}」"


@case("HK-07", base="B0-实体", mutates="learn", needs=EMU)
def hk07(d):
    typ(d, "jintian ")
    key(d, "MINUS")
    t = field_text(d)
    check(t == "今天-", f"框里「{t}」")
    return f"「{t}」"


@case("HK-08", base="B0-实体", needs=EMU)
def hk08(d):
    p1, p2 = page_check(d, "PAGEDOWN", "PAGEUP")
    key(d, "ESC")
    return f"第一页 {p1[:3]}，PageDown 后 {p2[:3]}，PageUp 回来"


@case("HK-09", base="B0-实体", mutates="learn", needs=EMU)
def hk09(d):
    typ(d, "a")   # the touch keyboard away first, so the name shows by the cursor
    key(d, "ESC")
    key(d, "SHIFT", after=0)
    f1 = flash(d, "En")
    typ(d, "hi ")
    t1 = field_text(d)
    esc_flash(d)
    key(d, "SHIFT", after=0)
    f2 = flash(d, "拼")
    typ(d, "ni ")
    t = field_text(d)
    check(f1 == "En", f"第一次 Shift 后光标旁是 {f1}")
    check(t1 == "hi ", f"英文里 h i 空格，框里「{t1}」")
    check(f2 == "拼", f"第二次 Shift 后光标旁是 {f2}")
    check(t == "hi 你", f"框里「{t}」")
    return f"闪 En；「{t1}」；闪 拼；「{t}」"


@case("HK-10", base="B0-实体", needs=EMU)
def hk10(d):
    typ(d, "ni")
    key(d, "SHIFT", after=0)
    f = flash(d, "En")
    t = field_text(d)
    check(t == "ni", f"框里「{t}」")
    check(f == "En", f"光标旁是 {f}")
    return f"「{t}」，闪 En"


@case("HK-11", base="B0-实体", needs=EMU)
def hk11(d):
    typ(d, "a")
    key(d, "ESC")
    key(d, "SHIFT", after=0)
    f = flash(d, "En")
    if f != "En":
        raise Blocked(f"Shift 没切到英文：{f}")
    esc_flash(d)   # else the En flash may still be up and read as the code row
    combo(d, "SHIFT", "A")
    key(d, "B")
    t = field_text(d)
    s = d.screen()
    check(t == "Ab", f"框里「{t}」")
    check(not fpre(s) and not fcands(s), f"像是又切回了拼音：小窗 `{fpre(s)}` {fcands(s)[:3]}")
    return f"「{t}」，仍是英文"


@case("HK-12", base="B0-实体", mutates="learn", needs=EMU)
def hk12(d):
    typ(d, "a")
    key(d, "ESC")
    combo(d, "CTRL", "SPACE", after=0)
    f1 = flash(d, "En")
    typ(d, "ok")
    t1 = field_text(d)
    esc_flash(d)
    combo(d, "CTRL", "SPACE", after=0)
    f2 = flash(d, "拼")
    typ(d, "ni ")
    t = field_text(d)
    check(f1 == "En" and t1 == "ok", f"第一次 Ctrl+空格 后 {f1}，o k 后框里「{t1}」")
    check(f2 == "拼" and t == "ok你", f"第二次后 {f2}，框里「{t}」")
    return f"En「{t1}」；拼「{t}」"


@case("HK-30", base="B0-实体", needs="skip:要分屏（上面测试页、下面设置）再点回上面的窗口；模拟器上 adb 摆不出分屏，手动做")
def hk30(d):
    pass


def add_then_page(d, *names):
    for n in names:
        add_im(d, n)
    d.open_page("text")


@case("HK-13", base="B0-实体", mutates=True, needs=EMU)
def hk13(d):
    add_then_page(d, "五笔")
    d.switch_im("五笔")
    typ(d, "w")
    c0 = fcands(d.screen())
    check(c0 and "人" in c0[:3], f"按 w 的候选不是五笔的：{c0[:5]}")
    key(d, "BACKSPACE")
    key(d, "SHIFT", after=0)
    f1 = flash(d, "En")
    esc_flash(d)
    key(d, "SHIFT", after=0)
    f2 = flash(d, "五")
    typ(d, "q")
    c = fcands(d.screen())
    key(d, "ESC")
    check(f1 == "En", f"第一次 Shift 后 {f1}")
    check(f2 == "五", f"第二次 Shift 后 {f2}（不是五笔）")
    check("我" in c, f"q 的候选 {c[:6]}")
    return f"w {c0[:3]}；En；五；q {c[:3]}"


@case("HK-14", base="B0-实体", mutates=True, needs=EMU)
def hk14(d):
    add_then_page(d, "五笔", "九键拼音", "手写")
    typ(d, "a")
    key(d, "ESC")
    seen = []
    for _ in range(4):
        combo(d, "CTRL", "SHIFT", after=0)
        time.sleep(0.4)
        t = [n.text for n in floating(d.screen()) if n.text and not n.clickable]
        seen.append(t[0] if t else "?")
        esc_flash(d)
    check(seen == ["En", "五", "拼", "En"], f"依次是 {seen}")
    return " → ".join(seen)


@case("HK-15", base="B0-实体", needs=EMU)
def hk15(d):
    typ(d, "a")
    key(d, "ESC")
    combo(d, "SHIFT", "CTRL", after=0)
    f = flash(d, "En")
    check(f == "En", f"Shift+Ctrl 后 {f}")
    return "切到 En"


@case("HK-16", base="B0-实体", mutates="learn", needs=EMU)
def hk16(d):
    typ(d, "nihao ")
    if field_text(d) != "你好":
        raise Blocked(f"打不出「你好」：「{field_text(d)}」")
    combo(d, "CTRL", "A")
    combo(d, "CTRL", "C")
    key(d, "RIGHT")
    combo(d, "CTRL", "V", after=0.8)
    t = field_text(d)
    check(t == "你好你好", f"框里「{t}」")
    typ(d, "ni")
    p = fpre(d.screen())
    key(d, "ESC")
    check(p == "ni", f"之后打 ni 小窗是「{p}」，输入法像是切了")
    return f"「{t}」，仍是拼音"


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


def hand_off(d, name, label):
    add_then_page(d, name)
    d.switch_im(label)


@case("HK-18", base="B0-实体", mutates=True, needs=EMU)
def hk18(d):
    hand_off(d, "手写", "手写")
    if not d.screen().find(desc="手写板", where="ime"):
        raise Blocked("切到手写后没有书写板")
    typ(d, "n")
    s = d.screen()
    p = fpre(s)
    typ(d, "i ")
    s = d.screen()
    t = field_text(d)
    check(not soft(s), "按实体键盘后屏幕键盘没收起")
    check(p == "n", f"按 n 后小窗是「{p}」，不是拼音")
    check(t == "你", f"框里「{t}」")
    return f"按 n 小窗 `{p}`；「{t}」"


@case("HK-19", base="B0-实体", mutates=True, needs=EMU)
def hk19(d):
    hand_off(d, "手写", "手写")
    typ(d, "ni ")
    key(d, "ESC")
    tap_button(d)
    s = d.screen()
    check(s.find(desc="手写板", where="ime"), f"回来的不是书写板：{d.im_label(s)}")
    return "回来的是手写书写板"


@case("HK-20", base="B0-实体", mutates=True, needs=EMU)
def hk20(d):
    hand_off(d, "九键拼音", "九键")
    typ(d, "2026hao ")
    t = field_text(d)
    key(d, "ESC")
    tap_button(d)
    s = d.screen()
    lab = d.im_label(s)
    check(t == "2026好", f"框里「{t}」")
    check("九键" in lab, f"回来的是「{lab}」，不是九键")
    return f"「{t}」；回来的是 {lab}"


@case("HK-21", base="B0-实体", page="chat", mutates=True, needs=EMU)
def hk21(d):
    hand_off(d, "手写", "手写")
    d.open_page("chat")
    d.switch_im("手写")
    hw = script("09-handwriting")
    hw.write(d, "人")
    t0 = field_text(d)
    key(d, "ENTER", after=0.8)
    s = d.screen()
    t1 = d.field_text(s) or ""
    log1 = next((n.text for n in s.app() if n.rid == "log"), "") or ""
    key(d, "ENTER", after=0.8)
    s = d.screen()
    t2 = d.field_text(s) or ""
    logs = [n.text for n in s.app() if n.text and "人" in n.text and not n.cls.endswith("EditText")]
    check(t0 == "人", f"写完「人」框里「{t0}」")
    check(t1 == "人" and "人" not in log1, f"第一次回车后框里「{t1}」，已发「{log1}」")
    check(t2 == "" and logs, f"第二次回车后框里「{t2}」，发出的 {logs}")
    return f"写「{t0}」；回车「{t1}」未发；再回车发出 {logs[:1]}，框清空"


@case("HK-22", base="B0-实体", mutates=True, needs=EMU)
def hk22(d):
    hand_off(d, "手写", "手写")
    typ(d, "ni ")
    combo(d, "CTRL", "SHIFT", after=0)
    time.sleep(0.4)
    t = [n.text for n in floating(d.screen()) if n.text and not n.clickable]
    key(d, "ESC")
    tap_button(d)
    lab = d.im_label()
    check("手写" not in lab and not d.screen().find(desc="手写板", where="ime"), f"回来的是手写：{lab}")
    return f"Ctrl+Shift 后 {t[:1]}；回来的是 {lab}"


@case("HK-23", base="B0-实体", needs=EMU)
def hk23(d):
    typ(d, "a")
    key(d, "ESC")
    key(d, "SHIFT", after=0)
    if flash(d, "En") != "En":
        raise Blocked("Shift 没切到英文")
    d.clear()
    typ(d, "teh")
    s = d.screen()
    c = fcands(s)
    typ(d, " ")
    t = field_text(d)
    check(t == "teh ", f"框里「{t}」")
    check(not c, f"有候选窗 {c[:4]}")
    return f"「{t}」，没有候选窗"


@case("HK-24", base="B0-实体", page="password", needs=EMU)
def hk24(d):
    typ(d, "abc123")
    s = d.screen()
    t = d.field_text(s) or ""
    check(len(t) == 6, f"框里「{t}」")
    check(not fcands(s) and not fpre(s), f"有候选窗：`{fpre(s)}` {fcands(s)[:3]}")
    return f"框里 {len(t)} 个字符（「{t}」），没有候选窗"


def hw_settings(d, title, option=None, on=None):
    home(d, "实体键盘")
    if option is not None:
        choose(d, title, option)
    else:
        d.switch(title, on)


@case("HK-25", base="B0-设置", mutates=True, needs=EMU)
def hk25(d):
    emu_only(d)
    hw_settings(d, "显示候选窗口", "禁用")
    d.kb("up")
    time.sleep(1)
    d.open_page("text")
    typ(d, "ni")
    s = d.screen()
    k, c = soft(s), d.candidates(s)
    typ(d, " ")
    t = field_text(d)
    check(k, "屏幕键盘收起了")
    check("你" in c[:3], f"候选栏 {c[:5]}")
    check(t == "你", f"框里「{t}」")
    return f"屏幕键盘在，候选栏 {c[:3]}；「{t}」"


@case("HK-25a", base="B0-设置", mutates=True, needs=EMU)
def hk25a(d):
    emu_only(d)
    others = [x for x in d.sh("ime list -s").split() if x and PKG not in x]
    hw_settings(d, "实体键盘快捷键", on=False)
    d.kb("up")
    time.sleep(1)
    try:
        for x in others:   # Ctrl+Space would go to the next system input method
            d.sh(f"ime disable {x}")
        d.open_page("text")
        typ(d, "a")
        key(d, "ESC")
        names = []
        for k in (("key", "SHIFT"), ("combo", "CTRL", "SHIFT"), ("combo", "CTRL", "SPACE")):
            d.kb(*k)
            time.sleep(0.4)
            names += [n.text for n in floating(d.screen()) if n.text and not n.clickable]
            esc_flash(d)
        typ(d, "ni ")
        t = field_text(d)
        check(not names, f"开关关着，光标旁闪了 {names}")
        check(t == "你", f"框里「{t}」")
        add_im(d, "五笔")
        d.open_page("text")
        typ(d, "a")
        key(d, "ESC")
        combo(d, "CTRL", "SPACE")
        combo(d, "CTRL", "SHIFT")
        typ(d, "ni")
        p = fpre(d.screen())
        key(d, "ESC")
        check(p == "ni", f"加了五笔后按 Ctrl+空格、Ctrl+Shift，再打 ni 小窗是「{p}」")
        hw_settings(d, "实体键盘快捷键", on=True)
        d.open_page("text")
        typ(d, "a")
        key(d, "ESC")
        key(d, "SHIFT", after=0)
        f = flash(d, "En")
        check(f == "En", f"打开开关后 Shift 是 {f}")
    finally:
        for x in others:
            d.sh(f"ime enable {x}")
    return f"关着：不闪，「{t}」，五笔也不切；打开后 Shift 到 En"


@case("HK-26", base="B0-设置", mutates=True, needs=EMU)
def hk26(d):
    emu_only(d)
    hw_settings(d, "候选词列表方向", "纵向")
    d.kb("up")
    time.sleep(1)
    d.open_page("text")
    typ(d, "shi")
    ns = fcand_nodes(d.screen())
    key(d, "ESC")
    if len(ns) < 3:
        raise Fail(f"候选窗里 {len(ns)} 个候选")
    xs, ys = [n.b[0] for n in ns[:4]], [n.b[1] for n in ns[:4]]
    check(max(xs) - min(xs) < 20 and ys == sorted(ys) and ys[1] > ys[0] + 20,
          f"候选不是竖排：{[n.b for n in ns[:4]]}")
    return f"竖排 {len(ns)} 个"


@case("HK-27", base="B0-实体", mutates=True, needs=EMU)
def hk27(d):
    typ(d, "ni ")
    key(d, "ESC")
    if soft(d.screen()):
        raise Blocked("按 n i 空格后屏幕键盘没收起")
    d.kb("down")
    ok = True
    try:
        back_on_screen(d, 6)
    except Blocked:
        ok = False
    check(ok, "拔掉键盘后屏幕键盘没自己回来")
    d.type("hao")
    d.cands()
    d.space()
    t = field_text(d)
    check(t == "你好", f"框里「{t}」")
    return f"拔掉后屏幕键盘回来；「{t}」"


@case("HK-28", base="B0-横屏", mutates=True, needs=EMU)
def hk28(d):
    emu_only(d)
    d.kb("up")
    time.sleep(1.5)
    d.open_page("text")
    typ(d, "ni")
    s = d.screen()
    k, c = soft(s), fcands(s)
    typ(d, " ")
    key(d, "ESC")
    tap_button(d)
    s = d.screen()
    split = bool(s.find(desc="合并键盘", where="ime")) or len([n for n in s.ime() if n.text == "g" or n.text == "G"]) >= 2
    check(not k and "你" in c[:3], f"打字时屏幕键盘{'没收起' if k else '收起'}，小窗 {c[:4]}")
    check(split, "回来的不是分体键盘")
    return f"收起，小窗 {c[:3]}；回来的是分体键盘"


case("HK-33", base="B0-实体", needs="skip:要一个输入框在屏幕最顶上的应用（候选窗贴屏幕顶）；测试页的框不够高，手动跑")(lambda d: None)
