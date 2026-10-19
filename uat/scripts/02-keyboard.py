"""uat/02-keyboard.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import subprocess
import time

from uat import (Blocked, Skip, GRID_BOTTOM, GRID_TOP, case, check, emoji_tab_xy, first, flick, glide_on,
                 glide_shot, has, home, key_texts, open_url, popup, popup_pick, popup_slide)


def tap_key(d, label, after=0.5):
    """a key by the text on it (for keys the named map does not hold: / .com @ …)"""
    n = d.key_node(label)
    if not n:
        raise Blocked(f"键盘上没有「{label}」键")
    d.tap(n.c, after)


def field_len_sampler(d, secs, step=0.25):
    out = []
    end = time.time() + secs
    while time.time() < end:
        t = d.field_text() or ""
        out.append(len(t))
        time.sleep(step)
    return out


def bg_swipe(d, a, b, ms):
    """an input swipe in the background (the finger moves while we look)"""
    return subprocess.Popen(["adb", "-s", d.serial, "shell", f"input swipe {a[0]} {a[1]} {b[0]} {b[1]} {ms}"])


@case("KB-01")
def kb01(d):
    d.type("ni")
    c = d.cands(want="你")
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(not t, f"框里是「{t}」")
    check(p == "ni", f"拼音行 `{p}`")
    check(has(c, "你") and not s.find(desc="展开工具栏", where="ime"), f"候选 {c[:5]}")
    return f"`{p}`，候选 {c[:4]}"


@case("KB-02", base="B0-英文", mutates="learn")
def kb02(d):
    d.type("ok")
    d.space()
    t = d.field_text()
    check(t == "ok ", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-03")
def kb03(d):
    flick(d, "w")
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    check(t == "2" and not c, f"框里「{t}」，候选 {c[:3]}")
    return "「2」，没有候选"


@case("KB-04")
def kb04(d):
    d.hold(d.keys(refresh=True)["e"], 900)
    time.sleep(0.4)
    t = d.field_text()
    check(t == "3", f"框里是「{t}」")
    return "「3」"


@case("KB-05")
def kb05(d):
    items = popup_pick(d, "q", "Q")
    t = d.field_text()
    check("1" in items and "Q" in items, f"弹出 {items}")
    check(t == "Q", f"框里是「{t}」")
    return f"弹出 {items}，框里「{t}」"


@case("KB-06", visual=True)
def kb06(d):
    m, v = key_texts(d, "m"), key_texts(d, "v")
    d.shot("kb", "拼音时的键角")
    d.switch_im("英文")
    m2, v2 = key_texts(d, "m"), key_texts(d, "v")
    d.shot("kb", "英文时的键角")
    check(m[1:2] == ["、"] and v[1:2] == ["？"], f"拼音时 m {m} v {v}")
    check(m2[1:2] == ["\\"] and v2[1:2] == ["?"], f"英文时 m {m2} v {v2}")
    return f"拼音 m {m[1:2]} v {v[1:2]}；英文 m {m2[1:2]} v {v2[1:2]}"


@case("KB-07", base="B0-英文", mutates="learn")
def kb07(d):
    d.press("shift")
    d.type("hi")
    c = d.cands()
    check(c and c[0] == "Hi", f"候选 {c[:4]}")
    d.space()
    t = d.field_text()
    check(t == "Hi ", f"框里是「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


@case("KB-08", base="B0-英文", visual=True)
def kb08(d):
    sh = d.keys(refresh=True)["shift"]
    d.taps([sh, sh], gap=0.08, after=0.6)
    d.shot("kb", "双击 ⇧ 后")
    d.type("ok")
    c = d.cands()
    d.press("shift")
    d.type("a")
    c2 = d.cands()
    check(c and c[0] == "OK", f"锁定后候选 {c[:4]}")
    check(c2 and c2[0].endswith("a") and c2[0].startswith("OK"), f"解锁后再打 a，候选 {c2[:4]}")
    return f"锁定 {c[:2]}；解锁后 {c2[:2]}"


@case("KB-09", mutates="learn")
def kb09(d):
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    if d.field_text() != "你好":
        raise Blocked(f"前提没到：框里「{d.field_text()}」")
    d.bksp()
    t = d.field_text()
    check(t == "你", f"框里是「{t}」")
    return "「你」"


@case("KB-10", mutates="learn")
def kb10(d):
    d.tap_text("表情", where="ime", scroll=False, after=1.0)
    d.tap(emoji_tab_xy(2), 0.8)   # the 人物 tab (icons, no text)
    thumb = None
    for _ in range(30):
        s = d.screen()
        thumb = next((n for n in s.ime() if n.text == "👍" and GRID_TOP < n.c[1] < GRID_BOTTOM), None)
        if thumb:
            break
        d.swipe((540, 2000), (540, 1640), 900, after=0.3)
    if not thumb:
        raise Blocked("人物里找不到 👍")
    d.touch(("down", *thumb.c), ("sleep", 0.9))
    s = d.screen()
    tone = next((n for n in s.ime() if n.text == "👍🏽"), None)
    if not tone:
        d.touch(("up", *thumb.c))
        raise Blocked(f"长按 👍 没有弹出 👍🏽：{[n.text for n in s.ime() if n.text.startswith('👍')]}")
    popup_slide(d, thumb.c, tone)
    t = d.field_text()
    if t != "👍🏽":
        raise Blocked(f"前提没到：框里「{t}」")
    abc = d.screen().find(text="ABC", where="ime")
    if abc:
        d.tap(abc.c, 0.8)
    d.bksp()
    t = d.field_text()
    check(t == "", f"退格后框里「{t}」（{[hex(ord(c)) for c in t or '']}）")
    return "整个表情删掉，框里为空"


@case("KB-11", page="long")
def kb11(d):
    n0 = len(d.field_text() or "")
    if n0 < 20:
        raise Blocked(f"长文本只有 {n0} 字")
    b = d.keys(refresh=True)["bksp"]
    p = subprocess.Popen(["adb", "-s", d.serial, "shell",
                          f"input motionevent DOWN {b[0]} {b[1]}; sleep 2; input motionevent UP {b[0]} {b[1]}"])
    samples = field_len_sampler(d, 2.2, 0.25)
    p.wait()
    time.sleep(0.6)
    n1 = len(d.field_text() or "")
    time.sleep(0.6)
    n2 = len(d.field_text() or "")
    gone = [samples[0] - x for x in samples]
    steps = [b - a for a, b in zip(gone, gone[1:])]
    half = len(steps) // 2
    check(n0 - n1 >= 8, f"按住 2 秒只删了 {n0 - n1} 个")
    check(n1 == n2, f"松手后还在删：{n1} → {n2}")
    check(sum(steps[half:]) > sum(steps[:half]), f"后一秒没比前一秒删得多：每 0.25 s {steps}")
    # held again with the finger drifting a little (past the touch slop, under half a key): it goes on
    x, y = b
    d.sh(f"input motionevent DOWN {x} {y}; sleep 0.6; input motionevent MOVE {x + 30} {y}; sleep 0.6; "
         f"input motionevent MOVE {x - 30} {y}; sleep 0.8; input motionevent UP {x - 30} {y}")
    time.sleep(0.6)
    n3 = len(d.field_text() or "")
    check(n2 - n3 >= 6, f"按住时手指挪一点，2 秒只删了 {n2 - n3} 个")
    return f"删了 {n0 - n1} 个，每 0.25 s {steps}；松手即停；手指挪一点删了 {n2 - n3} 个"


@case("KB-12", visual=True, mutates="learn")
def kb12(d):
    d.type("jintiantianqihenhao")
    c = d.cands()
    if first(c) != "今天天气很好":
        raise Blocked(f"打不出「今天天气很好」：{c[:3]}")
    d.space()
    b = d.keys(refresh=True)["bksp"]
    x, y = b
    d.touch(("down", x, y), ("sleep", 0.15), *[("move", x - 25 * i, y) for i in range(1, 9)], ("sleep", 0.6))
    d.shot("field", "退格键上左滑，没松手")
    d.touch(("up", x - 200, y))
    time.sleep(0.6)
    t = d.field_text()
    check(t is not None and len(t) < 6 and "今天天气很好".startswith(t), f"松手后框里「{t}」")
    return f"松手后框里「{t}」"


@case("KB-13")
def kb13(d):
    d.type("zhongguo")
    d.cands()
    x, y = d.keys(refresh=True)["bksp"]
    d.swipe((x, y), (x - 250, y), 300, after=0.8)
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(not p and not t and d.toolbar(s), f"拼音行 `{p}`，框里「{t}」，候选 {d.candidates(s)[:3]}")
    return "拼音清空，回到工具栏"


@case("KB-14", mutates="learn")
def kb14(d):
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    d.press(",")
    d.press(".")
    t = d.field_text()
    check(t == "你好，。", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-15")
def kb15(d):
    flick(d, "q")
    d.press(",")
    t = d.field_text()
    check(t == "1，", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-16")
def kb16(d):
    # the popup of 6+ items wraps to two rows; the row nearer the finger was once unreachable
    items, xy = popup(d, "，")
    marks = [n for n in items if n.text not in ("，",)]
    seen = [n.text for n in sorted(marks, key=lambda n: (-n.b[1], n.b[0]))]
    d.touch(("up", *xy))
    time.sleep(0.4)
    d.clear()
    check(len({n.b[1] for n in marks}) >= 2, f"长按逗号的弹出不是两排：{seen}")
    low = max(n.b[1] for n in marks)
    got = []
    for ch in ("。", "？"):
        items, xy = popup(d, "，")
        tgt = next((n for n in items if n.text == ch), None)
        if not tgt:
            d.touch(("up", *xy))
            check(False, f"弹出里没有「{ch}」：{seen}")
        check(tgt.b[1] == low, f"「{ch}」不在下面一排：{seen}")
        popup_slide(d, xy, tgt)
        t = d.field_text()
        check(t == ch, f"滑到下面一排的「{ch}」，框里「{t}」")
        got.append(t)
        d.clear()
    items, xy = popup(d, "l")
    rows = sorted(items, key=lambda n: (n.b[1], n.b[0]))
    tgt = next((n for n in rows if n.text in (";", "；")), None)
    if not tgt:
        d.touch(("up", *xy))
        check(False, f"长按 l 的弹出里没有分号：{[n.text for n in rows]}")
    ys = sorted({n.b[1] for n in rows})
    where = "下面一排" if len(ys) > 1 and tgt.b[1] == ys[-1] else ("上面一排" if len(ys) > 1 else "唯一一排")
    popup_slide(d, xy, tgt)
    t = d.field_text()
    # typing Chinese (B0 is 拼音): the full-width one
    check(t in (";", "；"), f"滑到 l 弹出里的「{tgt.text}」（{where}），框里「{t}」（弹出 {[n.text for n in rows]}）")
    got.append(t)
    return f"逗号弹出 {seen}；l 弹出 {[n.text for n in rows]}，分号在{where}；选到 {got}"


@case("KB-17", page="long")
def kb17(d):
    t0 = d.field_text() or ""
    x, y = d.keys(refresh=True)["space"]
    p = bg_swipe(d, (x, y), (x - 320, y), 1200)
    p.wait()
    time.sleep(0.5)
    d.press(",")
    t1 = d.field_text() or ""
    # where did the new comma land: the first index where the texts differ
    i1 = next((i for i, (a, b) in enumerate(zip(t0, t1)) if a != b), len(t0))
    check(len(t1) == len(t0) + 1, f"左滑后多了 {len(t1) - len(t0)} 个字（不该打出空格）")
    check(i1 < len(t0) - 2, f"左滑后光标没往前移：逗号在 {i1}/{len(t0)}")
    p = bg_swipe(d, (x, y), (x + 120, y), 800)
    p.wait()
    time.sleep(0.5)
    d.press(",")
    t2 = d.field_text() or ""
    i2 = next((i for i, (a, b) in enumerate(zip(t1, t2)) if a != b), len(t1))
    check(i2 > i1 + 1, f"右滑后光标没往后移：第二个逗号在 {i2}，第一个在 {i1}")
    check(" " not in t2.replace(" ", "", t0.count(" ")), "打出了空格")
    return f"左滑后光标在 {i1}/{len(t0)}，右滑后在 {i2}"


@case("KB-18", mutates="learn")
def kb18(d):
    d.type("shijian")
    c = d.cands()
    d.space()
    s = d.screen()
    t = d.field_text(s)
    check(t == first(c), f"候选第一个「{first(c)}」，框里「{t}」")
    check(not d.preedit(s), f"拼音行 `{d.preedit(s)}`")
    return f"「{t}」"


@case("KB-19")
def kb19(d):
    d.type("abc")
    d.cands()
    d.enter()
    t = d.field_text()
    check(t == "abc", f"框里是「{t!r}」")
    return "「abc」，没有换行"


@case("KB-20", page="search")
def kb20(d):
    s = d.screen()
    r = s.find(rid="button_return", where="ime")
    check(r and r.desc == "搜索", f"回车键是「{r.desc if r else None}」")
    d.type("a")
    d.cands()
    d.enter()
    t = d.field_text()
    d.enter()
    time.sleep(1.0)
    s = d.screen()
    got = s.find(text="搜索：a")
    kb = s.find(rid="keyboard_view", where="ime")
    check(t == "a", f"第一次回车后框里「{t!r}」")
    check(got and not kb and not d.field(s), f"第二次回车后：结果 {bool(got)}，键盘 {bool(kb)}，焦点 {bool(d.field(s))}")
    return "回车键是搜索；出现「搜索：a」，键盘收起"


@case("KB-21", page="next")
def kb21(d):
    s = d.screen()
    r = s.find(rid="button_return", where="ime")
    f0 = d.field(s)
    check(r and r.desc == "下一项", f"回车键是「{r.desc if r else None}」")
    d.enter()
    time.sleep(0.8)
    f1 = d.field()
    if f1 and f0 and f1.b == f0.b:
        # the system's own keyboard (LatinIME) does not move it either on this browser, and a Tab
        # key does: the WebView test browser ignores the input method's "next"
        raise Blocked("回车键是下一项，但测试浏览器（WebView Browser Tester）不处理输入法的“下一项”"
                      "（系统 LatinIME 也不跳），要在 Chrome 里看")
    return "回车键是下一项；焦点跳到「不要提示」框"


@case("KB-22", page="url")
def kb22(d):
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    bottom = [([m.text for m in n.walk() if m.text] or [""])[0] for n in kb.walk()
              if n.clickable and n.c[1] > kb.b[3] - 150]
    check("/" in bottom and "." in bottom and ".com" in bottom, f"底排 {bottom}")
    check("，" not in bottom and "。" not in bottom, f"底排有中文标点：{bottom}")
    tap_key(d, ".com")
    t = d.field_text()
    check(t == ".com", f"框里是「{t}」")
    return f"底排 {bottom}；框里「{t}」"


@case("KB-23", page="url")
def kb23(d):
    items = popup_pick(d, ".com", ".cn")
    t = d.field_text()
    check({".cn", ".net", ".org"} <= set(items), f"弹出 {items}")
    check(t == ".cn", f"框里是「{t}」")
    return f"弹出 {items}；框里「{t}」"


@case("KB-24", page="email")
def kb24(d):
    d.type("xiaoming")
    tap_key(d, "@")
    d.type("qq")
    tap_key(d, ".com")
    t = d.field_text()
    check(t == "xiaoming@qq.com", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-25", page="url", mutates="learn")
def kb25(d):
    flick(d, "w")
    popup_pick(d, "h", "/")
    t = d.field_text()
    check(t == "2/", f"框里是「{t}」")
    return f"「{t}」"


def brackets(d):
    """（ from k's popup, then 你 typed: (the field after the pair, after 你), or after the pair alone
    when it is not 「（）」"""
    popup_pick(d, "k", "（")
    t = d.field_text()
    if t != "（）":
        return t, None
    d.type("ni")
    d.cands(want="你")
    d.space()
    return t, d.field_text()


@case("KB-26", mutates="learn")
def kb26(d):
    t, t2 = brackets(d)
    check(t == "（）", f"框里是「{t}」")
    check(t2 == "（你）", f"再打「你」后框里「{t2}」（光标不在中间）")
    return "「（）」，光标在中间"


@case("KB-27", mutates="learn")
def kb27(d):
    _, t = brackets(d)
    if t != "（你）":
        raise Blocked(f"前提没到：框里「{t}」")
    popup_pick(d, "l", "）")
    d.press(",")
    t2 = d.field_text()
    check(t2 == "（你），", f"选右括号再打逗号后框里「{t2}」")
    return "「（你）」，右括号跳过，光标在最后"


@case("KB-28")
def kb28(d):
    popup_pick(d, "k", "（")
    if d.field_text() != "（）":
        raise Blocked(f"前提没到：框里「{d.field_text()}」")
    d.bksp()
    t = d.field_text()
    check(t == "", f"框里是「{t}」")
    return "框里为空"


@case("KB-29", base="B0-英文")
def kb29(d):
    d.type("ab")
    d.cands()
    # 前提 only: the cursor between a and b (a key event, not the case's subject)
    d.sh("input keyevent KEYCODE_DPAD_LEFT")
    time.sleep(0.4)
    popup_pick(d, "k", "(")
    t = d.field_text()
    if t in ("ab(", "ab()"):
        raise Blocked(f"光标没移进 a、b 中间：框里「{t}」")
    check(t == "a(b", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-30", visual=True)
def kb30(d):
    # the press preview is drawn, not in the accessibility tree; a dump while the finger is down
    # takes long enough to turn the press into a long press, so the screenshot is taken in the same
    # shell right after the finger goes down
    a = d.key_node("a")
    x, y = a.c
    d.sh(f"input motionevent DOWN {x} {y}; screencap -p /sdcard/uat-p.png; input motionevent UP {x} {y}")
    png = d.adb("exec-out", "cat", "/sdcard/uat-p.png", binary=True)
    d.shot((0, max(0, a.b[1] - 330), 1080, a.b[3] + 20), "按住 a（刚按下）", png=png)
    time.sleep(0.5)
    d.shot((0, max(0, a.b[1] - 330), 1080, a.b[3] + 20), "松手后")
    return None


@case("KB-31", page="chat", visual=True)
def kb31(d):
    def where(s):
        f = d.field(s)
        kb = s.find(rid="keyboard_view", where="ime")
        send = next((n for n in s.app() if n.text in ("发送", "Send") or n.desc == "发送"), None)
        return (f.b if f else None, kb.b[1] if kb else None, send.b if send else None)
    w0 = where(d.screen())
    d.shot("full", "没打字时")
    d.type("nihao")
    d.cands()
    s = d.screen()
    w1, p = where(s), d.preedit(s)
    d.shot("full", "打 nihao 时")
    d.bksp(5)
    time.sleep(0.5)
    w2 = where(d.screen())
    check(p == "ni hao", f"拼音行 `{p}`")
    check(w0 == w1 == w2, f"位置变了：没打字 {w0}，打字 {w1}，删光 {w2}")
    check(w0[0] and w0[1] and w0[0][3] <= w0[1], f"聊天框 {w0[0]} 被挡住（键盘顶 {w0[1]}）")
    return f"聊天框 {w0[0]}、键盘顶 {w0[1]} 始终不动；拼音行 `{p}`"


# ---------------------------------------------------------------- 滑行

@case("KB-32", base="B0-英文", mutates=True)
def kb32(d):
    d.glide("helo")
    time.sleep(0.5)
    t = d.field_text() or ""
    check("hello" not in t.lower() and len(t) <= 2, f"划过后框里「{t}」")
    d.hide_keyboard()
    home(d, "通用输入")
    r = d.row("滑行输入")
    w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "SwitchMaterial")] if r else []
    check(w and not w[0].checked, "通用输入里滑行输入是开着的")
    return f"划过后框里「{t}」；滑行输入关着"


@case("KB-33", base="B0-设置", mutates=True, visual=True)
def kb33(d):
    glide_on(d, "英文")
    k = d.keys(refresh=True)
    pts = [k[c] for c in "helo"]
    glide_shot(d, pts, "划 h-e-l-o，抬手前")
    t1, c = d.field_text(), d.candidates()
    d.glide("world")
    t2 = d.field_text()
    check(t1 == "hello ", f"第一次后框里「{t1}」")
    check(t2 == "hello world ", f"第二次后框里「{t2}」")
    return f"「{t1}」→「{t2}」，候选 {c[:3]}"


@case("KB-34", base="B0-设置", mutates=True)
def kb34(d):
    glide_on(d, "英文")
    d.glide("helo")
    c = d.cands()
    if d.field_text() != "hello " or len(c) < 2:
        raise Blocked(f"前提没到：框里「{d.field_text()}」，候选 {c[:3]}")
    w = d.pick(2)
    t1 = d.field_text()
    d.glide("helo")
    d.bksp()
    t2 = d.field_text()
    check(t1.strip() == w.split(" ")[0] and "hello" not in t1, f"点「{w}」后框里「{t1}」")
    check(t2 == t1, f"再划 hello 后退格，框里「{t2}」（应回到「{t1}」）")
    return f"换成「{t1.strip()}」；退格删掉整个 hello"


@case("KB-35", base="B0-设置", mutates=True)
def kb35(d):
    glide_on(d, "英文")
    d.type("ok", after=0.6)
    t1 = d.field_text()
    d.clear()
    flick(d, "q")
    t2 = d.field_text()
    d.clear()
    items, xy = popup(d, "e")
    d.touch(("up", *xy))
    texts = [n.text for n in items]
    check(t1 in ("ok", "ok "), f"快速点 o、k 后框里「{t1}」")
    check(t2 == "1", f"q 上短划打出「{t2}」")
    check(len(texts) > 1, f"长按 e 弹出 {texts}")
    return f"「{t1}」；短划「{t2}」；长按 e 弹出 {texts[:5]}"


@case("KB-36", base="B0-设置", mutates=True)
def kb36(d):
    glide_on(d, page="password")
    d.switch_im("英文")
    d.glide("helo")
    t1 = d.field_text() or ""
    open_url(d, "url")
    d.switch_im("英文")
    d.glide("helo")
    t2 = d.field_text() or ""
    open_url(d, "email")
    d.switch_im("英文")
    d.glide("helo")
    t3 = d.field_text() or ""
    check("hello" not in t1, f"密码框里「{t1}」")
    check("hello" not in t2, f"网址框里「{t2}」")
    check("hello" not in t3, f"邮箱框里「{t3}」")
    return f"密码框「{t1}」，网址框「{t2}」，邮箱框「{t3}」，都没有整词"


@case("KB-37", base="B0-设置", mutates=True)
def kb37(d):
    glide_on(d, "英文")
    d.press("shift")
    d.glide("helo")
    t = d.field_text()
    check(t == "Hello ", f"框里是「{t}」")
    return f"「{t}」"


@case("KB-38", mutates="learn")
def kb38(d):
    if d.flavor == "voice":
        # the voice build's default for a held space is voice input, not 无
        raise Skip("语音版空格长按默认是语音输入，本条只在文字版跑")
    d.switch_im("英文")
    d.type("ab")
    x, y = d.keys(refresh=True)["space"]
    d.sh(f"input motionevent DOWN {x} {y}; sleep 1; input motionevent UP {x} {y}")
    time.sleep(0.8)
    s = d.screen()
    t = d.field_text(s) or ""
    pop = [n.text for n in s.nodes if n.text in ("选择输入法", "正在听…")]
    check(t == "ab ", f"按住空格 1 秒松开后框里「{t}」")
    check(not pop, f"弹出了 {pop}")
    return f"「{t}」，没弹出东西"
