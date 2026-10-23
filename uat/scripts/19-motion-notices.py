"""uat/19-motion-notices.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time
from uat import case, check, home, script, Blocked

W = 1080


def tb():
    return script("05-toolbar")


def chip(d, text, s=None):
    """the notice saying text: a window of its own, outside the keyboard's"""
    s = s or d.screen()
    return s.find(contains=text, where="all")


def copy_all(d):
    t = tb()
    t.edit_panel(d)
    t.ime(d, desc="全选", after=0.3)
    t.ime(d, desc="复制", after=0.25)


def size_setting(d, title, frac):
    """a slider setting of 外观 → 大小与样式 to a fraction of its range, then 确定"""
    script("14-settings").appearance_tab(d, "大小与样式")
    d.tap_text(title, after=1.0)
    s = d.screen()
    bar = next((n for n in s.app() if n.cls.endswith("SeekBar")), None)
    if not bar:
        raise Blocked(f"「{title}」没有滑块：{s.texts()[:6]}")
    x = bar.b[0] + 6 + int((bar.b[2] - bar.b[0] - 12) * frac)
    d.tap((x, (bar.b[1] + bar.b[3]) // 2), 0.5)
    value = next((t for t in d.screen().texts() if t.endswith(" sp")), None)
    d.tap_text("确定", after=1.0)
    return value


def key_h(d):
    q = d.key_node("q")
    return q.b[3] - q.b[1] if q else None


@case("MN-01", page="long", visual=True)
def mn01(d):
    kb = tb().kbox(d)
    copy_all(d)
    d.shot("full", "复制后 0.25 秒")
    s = d.screen()
    c = chip(d, "已复制", s)
    check(c, "复制后没有「已复制」")
    check(c.b[3] <= kb[1] + 4, f"提示 {c.b} 不在键盘 {kb} 上方")
    d.tap(c.c, 0.4)
    check(not chip(d, "已复制"), "点了提示没收起")
    t = tb()
    t.ime(d, desc="全选", after=0.3)
    t.ime(d, desc="复制", after=0.2)
    shown = t.said(d, "已复制", 1.0)
    time.sleep(2.6)
    gone = not chip(d, "已复制")
    t.back_to_keys(d)
    check(shown, "第二次复制没有提示")
    check(gone, "不碰它 2.6 秒后提示还在")
    return f"提示 {c.b}，键盘上沿 {kb[1]}；点了收起；2.6 秒后自己走"


@case("MN-02", mutates="learn")
def mn02(d):
    t = tb()
    t.status_panel(d)
    d.tap(d.screen().find(text="不用我的输入习惯", where="ime").c, 0.1)
    off = t.said(d, "已不用我的输入习惯，在 ⋯ 里恢复", 1.5)
    s = d.screen()
    on_item = s.find(text="恢复我的输入习惯", where="ime")
    if not on_item:
        raise Blocked(f"状态面板没有恢复的那项：{s.texts(where='ime')[:12]}")
    d.tap(on_item.c, 0.1)
    on = t.said(d, "已恢复用我的输入习惯", 1.5)
    s = d.screen()
    both = [x for x in s.texts(where="all") if "已不用我的输入习惯" in x]
    t.back_to_keys(d)
    check(off, "关的时候没有提示")
    check(on, "开回来的时候没有「已恢复用我的输入习惯」")
    check(not both, f"新提示出来后旧的还在：{both}")
    return "关：已不用…；开：已恢复…；同时只有一条"


@case("MN-03", page="long", mutates=True)
def mn03(d):
    t = tb()
    t.floating(d)
    h, _ = t.handle(d)
    t.drag(d, h, (h[0], 260))
    kb = t.kbox(d)
    copy_all(d)
    c = chip(d, "已复制")
    t.back_to_keys(d)
    check(kb and kb[1] < 600, f"没拖到顶：{kb}")
    check(c, "复制后没有「已复制」")
    check(c.b[1] >= kb[3] - 4, f"贴顶时提示 {c.b} 不在键盘 {kb} 下方")
    check(c.b[3] <= 2340, f"提示出了屏幕 {c.b}")
    # landscape, pinned to the left edge: the side toward the middle is its right
    d.step("横屏贴左边")
    d.rotate(1)
    try:
        d.open_page("long")
        h, _ = t.handle(d)
        t.drag(d, h, (max(0, h[0] - 2000), h[1]))
        kb2 = t.kbox(d)
        copy_all(d)
        c2 = chip(d, "已复制")
        d.shot("full", "横屏贴左边复制后的提示")
        t.back_to_keys(d)
    finally:
        d.rotate(0)
    check(kb2 and kb2[0] <= 10, f"横屏没拖到左边：{kb2}")
    check(c2, "横屏复制后没有「已复制」")
    check(c2.b[0] >= kb2[2] - 4, f"贴左时提示 {c2.b} 不在键盘 {kb2} 右侧")
    check(c2.b[2] <= 2340, f"提示出了屏幕 {c2.b}")
    return f"键盘 {kb}，提示 {c.b}（在下方）；横屏贴左 {kb2}，提示 {c2.b}（在右侧）"


@case("MN-04", mutates=True, visual=True)
def mn04(d):
    t = tb()
    out = []
    for night in ("no", "yes"):
        d.sh(f"cmd uimode night {night}")
        time.sleep(1.5)
        t.copy_word(d, "公园")
        d.open_page("text")
        t.clip_panel(d)
        it = t.clip_item(d, "公园")
        check(it, f"剪贴板里没有「公园」：{[n.text for n in t.clip_items(d)][:4]}")
        t.long_press(d, it)
        t.menu_tap(d, "删除")
        time.sleep(0.4)
        s = d.screen()
        undo = s.find(text="撤销", where="all")
        d.shot("full", f"删除后的提示（{'暗' if night == 'yes' else '亮'}）")
        check(undo, "删除后没有带「撤销」的提示")
        check(undo.b[3] < 2000, f"撤销在屏幕底部 {undo.b}，像是 Snackbar")
        d.tap(undo.c, 0.8)
        back = t.clip_item(d, "公园")
        check(back, "撤销后「公园」没回来")
        out.append(f"{night}: 撤销 {undo.b}")
        t.back_to_keys(d)
    d.sh("cmd uimode night no")
    # deletes while the notice shows add up in it, one 撤销 for all (a swipe and a long press)
    d.step("接着删，数目累加")
    t.copy_word(d, "散步")
    d.open_page("text")
    t.clip_panel(d)
    it = t.clip_item(d, "散步")
    check(it, f"剪贴板里没有「散步」：{[n.text for n in t.clip_items(d)][:4]}")
    d.swipe((it.b[0] + 60, it.c[1]), (it.b[2] - 20, it.c[1]), 250, after=0.6)
    one = t.said(d, "已删除 1 项", 1.5)
    it = t.clip_item(d, "公园")
    check(it, "滑掉「散步」后「公园」不在")
    t.long_press(d, it)
    t.menu_tap(d, "删除")
    two = t.said(d, "已删除 2 项", 1.5)
    undo = d.screen().find(text="撤销", where="all")
    if undo:
        d.tap(undo.c, 0.8)
    both = t.clip_item(d, "散步") and t.clip_item(d, "公园")
    t.back_to_keys(d)
    check(one, "滑掉一条后没有「已删除 1 项」")
    check(two, "提示还在时再删一条，没有累加成「已删除 2 项」")
    check(both, "撤销后两条没有都回来")
    out.append("滑删 + 长按删累加成 2 项，撤销都回来")
    return "；".join(out)


def strip_on(d, side=None):
    home(d, "外观")
    script("14-settings").appearance_tab(d, "大小与样式")
    d.switch("始终显示悬浮条", True)
    if side:
        d.choose("悬浮键盘拖动条", side)


def grip(d, s=None):
    s = s or d.screen()
    return s.find(desc="悬浮键盘", where="ime")


@case("MN-10", base="B0-设置", mutates=True, visual=True)
def mn10(d):
    d.open_page("text")
    kb0, k0 = tb().kbox(d), key_h(d)
    strip_on(d)
    d.open_page("text")
    s = d.screen()
    g, kb1, k1 = grip(d, s), tb().kbox(d, s), key_h(d)
    d.shot("full", "悬浮条在下面")
    strip_on(d, "上面")
    d.open_page("text")
    s = d.screen()
    g2, kb2 = grip(d, s), tb().kbox(d, s)
    d.shot("full", "悬浮条在上面")
    check(g, "停靠的键盘上没有悬浮条的抓手")
    check(g.c[1] > kb1[3] - 10, f"抓手 {g.b} 不在按键下面（键盘 {kb1}）")
    check(abs(k1 - k0) <= 2, f"按键高度 {k0} → {k1}")
    check(g2 and g2.c[1] < kb2[1] + 10, f"改成上面后抓手 {g2.b if g2 else None} 不在按键上面（键盘 {kb2}）")
    return f"按键高 {k0}→{k1}；抓手 下 {g.b} 上 {g2.b if g2 else None}"


@case("MN-11", base="B0-设置", mutates=True, visual=True)
def mn11(d):
    strip_on(d)
    d.open_page("text")
    g = grip(d)
    if not g:
        raise Blocked("停靠的键盘上没有悬浮条")
    x, y = g.c
    to = (x, 1100)
    d.touch(("down", x, y), ("sleep", 0.9),
            *[("move", x, y + (to[1] - y) * i // 12) for i in range(1, 13)], ("sleep", 0.3), ("up", *to))
    time.sleep(1.0)
    kb = tb().kbox(d)
    d.shot("full", "长按脱离后拖到中间")
    check(kb and kb[2] - kb[0] < W * 0.9, f"长按拖动后不是悬浮键盘：{kb}")
    check(kb and kb[1] < 1500, f"悬浮键盘没跟着手指上来：{kb}")
    h, _ = tb().handle(d)
    tb().drag(d, h, (h[0], 2350))
    s = d.screen()
    kb2, g2 = tb().kbox(d, s), grip(d, s)
    check(kb2 and kb2[2] - kb2[0] >= W - 60, f"拖到底部松手后没放回：{kb2}")
    check(g2, "放回后悬浮条没了")
    # a long press by mistake: on into the bottom zone without lifting docks it, and floating
    # next comes back where it was before (not where the finger had it)
    d.step("误长按直接拖回底部")
    x, y = g2.c
    d.touch(("down", x, y), ("sleep", 0.9), ("move", x, y - 200), ("sleep", 0.3),
            *[("move", x, y - 200 + (2350 - y + 200) * i // 10) for i in range(1, 11)], ("sleep", 0.5), ("up", x, 2350))
    time.sleep(1.0)
    kb3 = tb().kbox(d)
    tb().floating(d)
    kb4 = tb().kbox(d)
    d.shot("full", "误长按放回后再悬浮")
    check(kb3 and kb3[2] - kb3[0] >= W - 60, f"长按后不松手拖进底部松开，没放回：{kb3}")
    check(kb4 and kb and abs(kb4[0] - kb[0]) <= 10 and abs(kb4[1] - kb[1]) <= 10,
          f"再悬浮在 {kb4}，不是原来停的 {kb}")
    return f"脱离后 {kb}；放回 {kb2}；误长按放回 {kb3}，再悬浮 {kb4}"


@case("MN-12", base="B0-设置", mutates=True, visual=True)
def mn12(d):
    strip_on(d)
    d.open_page("text")
    t = tb()
    t.status_panel(d)
    t.ime(d, text="单手模式", after=1.2)
    s = d.screen()
    kb, g = t.kbox(d, s), grip(d, s)
    d.shot("full", "单手时的悬浮条")
    check(kb and kb[2] - kb[0] < W * 0.9, f"没进单手模式：{kb}")
    check(g and abs(g.c[0] - (kb[0] + kb[2]) // 2) <= 12, f"抓手 {g.b if g else None} 不在键盘 {kb} 正中")
    return f"键盘 {kb}，抓手 {g.b}"


case("MN-13", base="B0-设置", needs="manual:要 TalkBack（redroid 没有），在开得了 TalkBack 的真机或模拟器上手动做")(lambda d: None)


@case("MN-20", base="B0-设置", mutates=True, visual=True)
def mn20(d):
    out = []
    for frac, label in ((1.0, "28 sp"), (0.0, "14 sp")):
        v = size_setting(d, "候选字号", frac)
        check(v == label, f"候选字号滑到 {label} 却是 {v}")
        d.open_page("text")
        k = key_h(d)
        least = 40 * d.dpx() - 2
        if label == "14 sp":
            # the idle bar's buttons are max(bar height, 40 dp) wide (F3475): at 14 sp the bar is
            # lower than 40 dp, and they must not shrink with it (语音输入 only on the voice build)
            s0 = d.screen()
            for name in ("展开工具栏", "表情", "语音输入", "隐藏键盘"):
                b = s0.find(desc=name, where="ime")
                check(b or name == "语音输入", f"14 sp 时空闲栏没有 {name}")
                check(not b or b.b[2] - b.b[0] >= least and b.b[3] > b.b[1],
                      f"14 sp 时空闲栏 {name} {b.b if b else None} 窄于 40 dp（{least:.0f} px）")
        # the expanded toolbar's five buttons are bar height x max(bar height, 40 dp) (F3552): at
        # both ends of the size they fill the bar's height. At 28 sp a phone's width can't hold
        # five 56 dp buttons beside the idle ones, so they squeeze, by design, to no less than
        # ButtonsBarUi.MIN_SIZE (28 dp) and scroll past that (F3584)
        narrowest = least if label == "14 sp" else 28 * d.dpx() - 2
        more = d.screen().find(desc="展开工具栏", where="ime")
        check(more, f"{label} 时空闲栏没有 ›")
        d.tap(more.c, 0.6)
        s1 = d.screen()
        d.shot("kb", f"候选字号 {label}，展开工具栏")
        bar_h = more.b[3] - more.b[1]
        for name in ("撤销", "重做", "文本编辑", "剪贴板", "状态区域"):
            b = s1.find(desc=name, where="ime")
            check(b and abs((b.b[3] - b.b[1]) - bar_h) <= 2 and b.b[2] - b.b[0] >= narrowest,
                  f"{label} 时展开工具栏的 {name} {b.b if b else None} 不和栏同高（{bar_h} px）或窄于 {narrowest:.0f} px")
        hide = s1.find(desc="隐藏工具栏", where="ime")
        check(hide, f"{label} 时展开工具栏后没有收起键")
        d.tap(hide.c, 0.6)
        d.type("nihao")
        time.sleep(0.8)
        s = d.screen()
        bar = s.find(rid="candidate_view", where="ime")
        d.shot("kb", f"候选字号 {label}")
        # the ⌄ follows the size too: at 14 sp inside the bar and not cut down to a sliver
        ex = s.find(desc="展开候选词列表", where="ime")
        check(ex and bar and ex.b[1] >= bar.b[1] - 2 and ex.b[3] <= bar.b[3] + 2 and ex.b[3] - ex.b[1] >= 40,
              f"{label} 时 ⌄ {ex.b if ex else None} 被裁或出了候选栏 {bar.b if bar else None}")
        d.tap(ex.c, 0.8)
        grid = d.screen().find(rid="expanded_candidate_view", where="ime")
        cells = [n for n in grid.walk() if n.text and n.cls == "TextView"] if grid else []
        d.shot("kb", f"候选字号 {label}，展开")
        # the tallest cell: the last row is cut by the window's bottom, so the shortest is a sliver
        row = max(n.b[3] - n.b[1] for n in cells) if cells else None
        d.tap(ex.c, 0.8)   # the same spot folds the table back
        d.clear()
        # 返回键盘 on a panel's title bar follows the size as well
        tb().clip_panel(d)
        back = d.screen().find(desc="返回键盘", where="ime")
        check(back and back.b[3] - back.b[1] >= 40, f"{label} 时剪贴板面板的返回键盘 {back.b if back else None} 被裁")
        tb().back_to_keys(d)
        out.append((label, k, bar.b[3] - bar.b[1] if bar else None, row))
    (_, k28, b28, r28), (_, k14, b14, r14) = out
    check(abs(k28 - k14) <= 2, f"按键高度随字号变了：28 sp {k28}，14 sp {k14}")
    check(b28 and b14 and b28 > b14 + 30, f"候选栏高度没跟着字号：28 sp {b28}，14 sp {b14}")
    check(r28 and r14 and r28 > r14 + 15, f"展开的候选格子没跟着字号：28 sp {r28}，14 sp {r14}")
    return str(out)


@case("MN-21", base="B0-设置", mutates=True, visual=True)
def mn21(d):
    size_setting(d, "候选字号", 1.0)
    strip_on(d)
    d.choose("键盘高度", "很高")
    d.rotate(1)
    d.open_page("text")
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    ime = [n for n in s.ime() if n.b[3] > n.b[1]]
    d.shot("full", "横屏 28 sp")
    d.rotate(0)
    check(kb and ime, "横屏没有键盘")
    # as LS-27: from the docked area's topmost row to the keys' bottom; the window-wide containers left out
    outer = min(n.b[1] for n in ime)
    top = min(n.b[1] for n in ime if n.b[1] > outer)
    area, h = kb.b[3] - top, kb.b[3]
    share = area / h
    check(area <= 0.70 * h + 8, f"横屏键盘区 {area} px，超过屏高 {h} px 的七成")
    return f"键盘区占 {share:.0%}"
