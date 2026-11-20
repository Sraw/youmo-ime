"""uat/05-toolbar.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
import time

from uat import BAR_BOTTOM, TESTBED, Blocked, case, check, open_host, open_url

LONG = "今天天气很好，我们一起去公园散步吧。"


def ime(d, text=None, desc=None, after=0.6, contains=False):
    """a node of the keyboard by its text or description (either matches either)"""
    s = d.screen()
    w = text or desc
    if contains:
        n = next((n for n in s.ime() if w in n.text or w in n.desc), None)
    else:
        n = s.find(text=w, where="ime") or s.find(desc=w, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{text or desc}」")
    d.tap(n.c, after)
    return n


def edit_panel(d):
    d.expand_toolbar()
    ime(d, desc="文本编辑", after=0.8)


def status_panel(d):
    d.expand_toolbar()
    ime(d, desc="状态区域", after=0.8)


def clip_panel(d):
    d.expand_toolbar()
    ime(d, desc="剪贴板", after=0.8)


def back_to_keys(d):
    ime(d, desc="返回键盘", after=0.6)
    d._keys = None


def copy_range(d, start, n, at_copy=None):
    """in the focused field: the cursor at its very start (a tap before the first character: the
    panel's |< goes to the start of the line only), → start times, 选择, → n times, 复制. [at_copy]:
    called right after 复制, while its notice shows"""
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 0.6)
    edit_panel(d)
    s = d.screen()
    right = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    sel = s.find(desc="选择", where="ime") or s.find(text="选择", where="ime")
    if start:
        d.taps([right.c] * start, gap=0.05, after=0.3)
    d.tap(sel.c, 0.3)
    d.taps([right.c] * n, gap=0.05, after=0.3)
    ime(d, desc="复制", after=0.2 if at_copy else 0.6)
    if at_copy:
        at_copy()
    ime(d, desc="把光标移动到结尾", after=0.3)
    back_to_keys(d)


def copy_word(d, word, at_copy=None):
    """copy a word of the 长文本 field (the clipboard as 前提)"""
    d.open_page("long")
    i = LONG.index(word)
    copy_range(d, i, len(word), at_copy)


def clip_items(d):
    s = d.screen()
    # below the panel's bar (its back button: the tabs stand in its title), as the first row's centre is above BAR_BOTTOM
    title = next((n for n in s.ime() if n.desc == "返回键盘"), None)
    top = title.b[3] if title else BAR_BOTTOM
    return [n for n in s.ime() if n.text and n.b[1] >= top and n.text not in ("试着复制些东西",)]


def clip_item(d, text):
    return next((n for n in clip_items(d) if n.text.startswith(text)), None)


@case("TB-08", mutates=True)
def tb08(d):
    copy_word(d, "公园")
    d.open_page("text")
    clip_panel(d)
    items = clip_items(d)
    check(items and items[0].text == "公园", f"剪贴板 {[n.text for n in items][:4]}")
    d.tap(items[0].c, 0.8)
    s = d.screen()
    t = d.field_text(s)
    check(t == "公园", f"框里「{t}」")
    check(d.key_node("q", s), "粘贴后没有回到键盘（还在剪贴板面板）")
    return f"最上面「公园」，点后框里「{t}」，回到键盘"


@case("TB-70", mutates=True, visual=True)
def tb70(d):
    copy_word(d, "公园")
    d.open_page("text")
    shots = []
    try:
        for night in (False, True):
            if night:
                d.sh("cmd uimode night yes")
                time.sleep(1.5)
                d.open_page("text")
            clip_panel(d)
            if not night:
                item = clip_item(d, "公园")
                check(item, f"最近 {[n.text for n in clip_items(d)][:4]}")
                d.hold(item.c, 800)
                time.sleep(0.6)
                # the menu's 收藏, in a window of its own; the tab of the same name is on the bar
                s = d.screen()
                tab = next((n for n in s.ime() if n.text == "收藏" and n.b[1] < BAR_BOTTOM), None)
                pick = next((n for n in s.nodes if n.text == "收藏" and (not tab or n.b != tab.b)), None)
                check(pick, "长按菜单里没有「收藏」")
                d.tap(pick.c, 0.3)
                said = d.screen().find(text="已收藏", where="all")
                check(said, "没有提示「已收藏」")
                time.sleep(3.5)
                check(clip_item(d, "公园"), "收藏后最近里没有「公园」了")
            tab = next((n for n in d.screen().ime() if n.text == "收藏" and n.b[1] < BAR_BOTTOM), None)
            check(tab, "栏上没有「收藏」标签")
            d.tap(tab.c, 0.8)
            check(clip_item(d, "公园"), f"收藏页 {[n.text for n in clip_items(d)][:4]}")
            back = d.screen().find(desc="返回键盘", where="ime")
            d.shot((0, back.b[1], 1080, 2340) if back else "full", ("深色" if night else "浅色") + "：收藏页列出「公园」，标签「收藏」是选中的颜色，栏上没有垃圾桶")
            shots.append(night)
            if night:
                d.tap(clip_item(d, "公园").c, 0.8)
                t = d.field_text(d.screen())
                check(t == "公园", f"框里「{t}」")
            else:
                back_to_keys(d)
    finally:
        d.sh("cmd uimode night no")
    time.sleep(1.5)
    return "收藏后提示「已收藏」、最近里还在；收藏页列出它（浅色、深色各一张图），点了粘贴「公园」"


case("TB-76", needs="release:要 0.6.2 装好、置顶两条（一条敏感）后覆盖安装这一版；发版时手动跑")(lambda d: None)


def status_find(d, *texts):
    """the first of [texts] on the open status panel, scrolled to if under the fold; None if none is"""
    def find():
        s = d.screen()
        return next((n for x in texts if (n := s.find(text=x, where="ime"))), None)
    n = find()
    if not n:
        # the learning entries (U-88, U-93) push fcitx's toggles to a third row, under the fold
        d.swipe((540, 2000), (540, 1650), 300, 0.8)
        n = find()
    return n


def status_toggle(d, frm):
    status_panel(d)
    n = status_find(d, frm)
    if not n:
        raise Blocked(f"状态面板没有「{frm}」")
    d.tap(n.c, 0.8)
    s = d.screen()
    now = next((m.text for m in s.ime() if abs(m.c[0] - n.c[0]) < 30 and abs(m.c[1] - n.c[1]) < 30 and m.text), "")
    back_to_keys(d)
    return now




def kbox(d, s=None):
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    return kb.b if kb else None


def floating(d):
    status_panel(d)
    ime(d, text="悬浮键盘", after=1.2)


def handle(d, s=None):
    s = s or d.screen()
    dock = s.find(desc="键盘放回底部", where="ime")
    kb = s.find(rid="keyboard_view", where="ime")
    if not dock or not kb:
        raise Blocked("没有悬浮键盘的横条")
    return ((kb.b[0] + kb.b[2]) // 2, dock.c[1]), dock


def drag(d, a, b):
    steps = [("down", *a), ("sleep", 0.2)] + \
        [("move", a[0] + (b[0] - a[0]) * i // 10, a[1] + (b[1] - a[1]) * i // 10) for i in range(1, 11)] + \
        [("sleep", 0.2), ("up", *b)]
    d.touch(*steps)
    time.sleep(0.8)


@case("TB-22", page="sentence", mutates=True, visual=True)
def tb22(d):
    # the sentence field and the text field both on the page: the full page
    open_url(d, "", field=0)
    s0 = d.screen()
    f0 = [n.b for n in s0.app() if n.cls == "EditText" and n.rid != "url_field"]
    floating(d)
    s = d.screen()
    b1 = kbox(d, s)
    f1 = [n.b for n in s.app() if n.cls == "EditText" and n.rid != "url_field"]
    # the bar in slots a little narrower than docked, the icons as big; the toolbar's with no slot in ⋯'s panel (TB-68)
    menu = s.find(desc="展开工具栏", where="ime")
    if menu:
        d.tap(menu.c, 0.8)
    s = d.screen()
    undo, more = s.find(desc="撤销", where="ime"), s.find(desc="状态区域", where="ime")
    d.shot("kb", "悬浮的工具栏和字母")
    # 28 dp at least, all in view (⋯, the way out of floating, too)
    check(undo is not None and undo.b[2] - undo.b[0] >= 72, f"悬浮时工具栏按钮 {undo.b if undo else None}，窄于 28dp")
    check(more is not None and b1 and more.b[2] <= b1[2], f"悬浮时工具栏的 ⋯ {more.b if more else None} 不在键盘里 {b1}")
    h, _ = handle(d, s)
    drag(d, h, (h[0], 500))
    s = d.screen()
    b2 = kbox(d, s)
    d.shot("full", "拖到上半部分")
    check(b1 and 0.6 < (b1[2] - b1[0]) / 1080 < 0.85, f"悬浮键盘 {b1}")
    check(f0[:2] == f1[:2], f"页面被顶起：{f0[:2]} → {f1[:2]}")
    check(b2 and b2[1] < b1[1] - 400, f"拖后键盘 {b2}（拖前 {b1}）")
    # a field outside the keyboard: the second one (句子) if it shows
    s = d.screen()
    fields = [n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"]
    free = [n for n in fields if n.b[3] < b2[1] or n.b[1] > b2[3]]
    if len(free) >= 2:
        d.tap(free[1].c, 0.8)
        check(d.field() and d.field().b == free[1].b, "点键盘外的框焦点没过去")
    a = d.key_node("a")
    x, y = a.c
    # a moment for the bubble to be drawn: the release build's frame can come after an immediate screencap
    d.sh(f"input motionevent DOWN {x} {y}; sleep 0.1; screencap -p /sdcard/uat-p.png; input motionevent UP {x} {y}")
    png = d.adb("exec-out", "cat", "/sdcard/uat-p.png", binary=True)
    d.shot((max(0, x - 300), max(0, a.b[1] - 330), min(1080, x + 300), a.b[3] + 20), "按住 a", png=png)
    return f"悬浮 {b1} → 拖到 {b2}；页面不动"


@case("TB-23", mutates=True, visual=True)
def tb23(d):
    floating(d)
    h, _ = handle(d)
    drag(d, h, (330, 300))
    b1 = kbox(d)
    d.back(1.0)
    f = next(n for n in d.screen().app() if n.cls == "EditText" and n.rid != "url_field")
    d.tap(f.c, 1.2)
    b2 = kbox(d)
    d.rotate(1)
    s = d.screen()
    if not s.find(rid="keyboard_view", where="ime"):
        f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
        if f:
            d.tap(f.c, 1.2)
    # 横屏键盘样式 is its own: floating there at its own spot, the bottom until dragged
    if not d.screen().find(desc="键盘放回底部", where="ime"):
        floating(d)
    b3 = kbox(d)
    h, _ = handle(d)
    drag(d, h, (2000, 150))
    b3r = kbox(d)
    d.shot("full", "横屏")
    d.rotate(0)
    s = d.screen()
    if not s.find(rid="keyboard_view", where="ime"):
        f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
        if f:
            d.tap(f.c, 1.2)
    b4 = kbox(d)
    check(b1 and b2 and abs(b1[1] - b2[1]) < 20 and abs(b1[0] - b2[0]) < 20, f"收起再出 {b1} → {b2}")
    check(b3 and b3[1] > 300, f"横屏时 {b3}（没放过，应在底部，不跟竖屏的左上）")
    check(b3r and b3r[1] < 500 and b3r[2] > 1600, f"横屏拖到右上 {b3r}")
    check(b4 and b4[0] < 300 and b4[1] < 900, f"转回竖屏 {b4}（还在左上）")
    return f"左上 {b1}，再出 {b2}，横屏 {b3} → 右上 {b3r}，竖屏 {b4}"




case("TB-53", needs="manual:平面键时悬浮键盘竖屏、横屏的四角，看图")(lambda d: None)


@case("TB-41", mutates=True)
def tb41(d):
    status_toggle(d, "中文标点")
    stale = []
    for i in range(20):
        want = "英文" if i % 2 == 0 else "拼音"
        d.press("globe", after=0.8)
        d._keys = None
        name = d.im_label()
        if want not in name:
            stale.append(f"第 {i + 1} 次「{name}」")
    check(not stale, f"读到的还是上一个：{stale[:3]}（共 {len(stale)} 次）")
    return "20 次都读到新名字"


case("TB-46a", needs="manual:选中块的底色和字在浅色、深色主题下看得清，要人眼看")(lambda d: None)


@case("TB-51", visual=True)
def tb51(d):
    d.type("nihao")
    time.sleep(0.6)
    ime(d, desc="展开候选词列表")
    time.sleep(0.6)
    d.shot("kb", "展开候选：右侧一列")
    before = [n.text for n in d.screen().ime() if n.text]
    ime(d, desc="更多候选词", after=0.8)
    after = [n.text for n in d.screen().ime() if n.text]
    check(before != after, f"翻页前后一样 {before[:6]}")
    d.shot("kb", "下一页")
    return f"翻页 {before[:3]} → {after[:3]}"


# 99 characters, no newline: put on the clipboard 3000 times over by the test host
CLIP_UNIT = "今天天气很好，我们一起去公园散步吧。The quick brown fox jumps over the lazy dog. 第123号文件已经发送到邮箱，请查收。明天见，再会吧朋友们。okay!!"


def testbed_multiline(d):
    """the testbed's 多行 field, empty (the app started afresh) and tapped: the keyboard is up"""
    d.sh(f"am force-stop {TESTBED}")
    d.testbed("types")
    n = d.wait(lambda s: next((x for x in s.app() if x.rid == "multiline" and x.pkg == TESTBED), None), 15,
               what="the testbed's 多行 field")
    d.tap(n.c, 1.5)
    d.page = None
    d.host = None
    d._keys = None


def multiline_length(d, want, timeout=40):
    """the 多行 field's text once it holds want characters or has stopped growing: read from the
    testbed (the screen's text stops at 100 000 characters), the app laying it out for seconds"""
    end = time.time() + timeout
    last, same = -1, 0
    while time.time() < end:
        try:
            t = d.testbed_state()["multiline"]["text"]
        except Blocked:
            t = None   # still inside the IME's batch edit
        if t is not None:
            if len(t) >= want:
                return t
            same = same + 1 if len(t) == last else 0
            last = len(t)
            if same >= 4:
                return t
        time.sleep(1.0)
    raise Blocked(f"等了 {timeout} 秒框里还在变（{last} 字）")


@case("TB-54", mutates=True)
def tb54(d):
    times = 3000
    want = len(CLIP_UNIT) * times
    got = []
    for way in ("候选栏的建议", "剪贴板面板"):
        d.step(way)
        open_host(d, clip=CLIP_UNIT, clipTimes=times)
        testbed_multiline(d)
        if way == "候选栏的建议":
            ime(d, text=CLIP_UNIT[:6], contains=True, after=1.0)
        else:
            clip_panel(d)
            item = clip_item(d, CLIP_UNIT[:6])
            if not item:
                raise Blocked(f"剪贴板面板里没有这一条：{[n.text[:10] for n in clip_items(d)][:4]}")
            d.tap(item.c, 1.0)
        t = multiline_length(d, want)
        check(len(t) == want and t.startswith(CLIP_UNIT) and t.endswith(CLIP_UNIT),
              f"{way}：框里 {len(t)} 字，应有 {want} 字")
        got.append(len(t))
        d.shot("kb", f"{way}粘贴后")
    return f"候选栏 {got[0]} 字、面板 {got[1]} 字，都整段"


def resizer(d, s=None):
    s = s or d.screen()
    r = s.find(desc="调整悬浮键盘大小", where="ime")
    if not r:
        raise Blocked("悬浮键盘的条上没有调大小的按钮")
    return r


def kwidth(d):
    b = kbox(d)
    return b[2] - b[0] if b else 0


# floating dragged up to the screen's middle: at the bottom, where it first floats, there is no room under it to grow
def floating_up(d):
    floating(d)
    h, _ = handle(d)
    drag(d, h, (h[0], 1300))


@case("TB-58", mutates=True, visual=True)
def tb58(d):
    floating_up(d)
    b1 = kbox(d)
    r = resizer(d)
    drag(d, r.c, (r.c[0] - 120, r.c[1] + 120))
    b2 = kbox(d)
    d.shot("ime", "放大")
    r = resizer(d)
    drag(d, r.c, (r.c[0] + 200, r.c[1] - 200))
    b3 = kbox(d)
    d.shot("ime", "缩小")
    check(b1 and b2 and b2[2] - b2[0] > (b1[2] - b1[0]) + 40, f"放大 {b1} → {b2}")
    check(b2 and abs(b2[2] - b1[2]) < 6 and abs(b2[1] - b1[1]) < 6, f"右上角不动 {b1} → {b2}")
    check(b3 and b3[2] - b3[0] < (b1[2] - b1[0]) - 40, f"缩小 {b1} → {b3}")
    return f"{b1} → 放大 {b2} → 缩小 {b3}"


@case("TB-60", mutates=True, visual=True)
def tb60(d):
    def shown():
        s = d.screen()
        if not s.find(rid="keyboard_view", where="ime"):
            f = next((n for n in s.app() if n.cls == "EditText" and n.rid != "url_field"), None)
            if f:
                d.tap(f.c, 1.2)

    floating_up(d)
    r = resizer(d)
    drag(d, r.c, (r.c[0] + 120, r.c[1] - 120))
    upright = kwidth(d)
    d.rotate(1)
    shown()
    if not d.screen().find(desc="键盘放回底部", where="ime"):
        floating(d)
    turned = kwidth(d)
    d.shot("ime", "横屏")
    # half way up: rotate(1) makes every display 2340x1080, so 540 px is half its height
    h, _ = handle(d)
    drag(d, h, (h[0], 540))
    r = resizer(d)
    drag(d, r.c, (r.c[0] - 120, r.c[1] + 120))
    bigger = kwidth(d)
    d.rotate(0)
    shown()
    again = kwidth(d)
    d.rotate(1)
    shown()
    turned_again = kwidth(d)
    d.shot("ime", "横屏调大、转回竖屏再转过来")
    d.rotate(0)
    check(upright and turned and turned != upright, f"横屏 {turned}，竖屏调过的 {upright}（横屏是自己那份）")
    check(bigger > turned + 40, f"横屏调大 {turned} → {bigger}")
    check(abs(again - upright) < 6, f"横屏调大后转回竖屏 {again}，应是 {upright}")
    check(abs(turned_again - bigger) < 6, f"再转成横屏 {turned_again}，应是横屏调大的 {bigger}")
    return f"竖屏 {upright}，横屏 {turned} → 调大 {bigger}，转回竖屏 {again}，再横屏 {turned_again}"


case("TB-61", needs="jvm:FloatingUatTest.itsSizeIsKeptShownAgainAndDockedAndFloatingAgain、FloatingStoreTest.neverKeptEitherWayItIsWhere060LeftIt、"
               "FloatingKeyboardTest.neverResizedItIsAsItWas")(lambda d: None)


case("TB-64", needs="jvm:BarIconsThemeTest.aToggleOnDrawsItsIconInTheSelectedColour、BarIconsThemeTest.tradSimpsTwoStatesAreTwoNames、"
               "BarIconsThemeTest.aThemesIconNotReadYetIsNothingTillItIsNeverTheAppsFirst")(lambda d: None)
