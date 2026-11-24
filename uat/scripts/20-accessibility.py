"""uat/20-accessibility.md. The .md is the source of truth; each function follows its case's
前提/操作/预期. A key touched under TalkBack should get the screen reader's focus; on the emulator neither `input tap` (it bypasses
touch exploration: the key is typed) nor sendevent on the virtio touchscreen (no node, the app's
own neither, ever gets the focus) explores, so the script checks the nodes (words, boxes) and the
touch is done by hand (see the .md)."""
import time

from uat import PKG, Blocked, case, check, script

def area(s):
    """the box below the bar that holds the keys or the panel"""
    w = s.find(rid="input_window", where="ime")
    if not w:
        raise Blocked("键盘没弹出")
    return w.b


def zero(n):
    return (n.b[2] - n.b[0]) * (n.b[3] - n.b[1]) <= 0


def inside(n, box):
    return n.b[0] >= box[0] - 1 and n.b[1] >= box[1] - 1 and n.b[2] <= box[2] + 1 and n.b[3] <= box[3] + 1


def keys(s, box):
    """the touchable nodes over box (keys, cells), with their words (the app's: a candidate's menu
    is a popup outside the keyboard's window): those reaching out of it and empty ones too, so that
    explore can tell them (a node merely touching box's edge is a neighbour's, not one of these)"""
    def over(n):
        if zero(n):
            return box[0] <= n.b[0] <= box[2] and box[1] <= n.b[1] <= box[3]
        return min(n.b[2], box[2]) - max(n.b[0], box[0]) > 0 and min(n.b[3], box[3]) - max(n.b[1], box[1]) > 0
    return [n for n in s.nodes if n.pkg == PKG and n.clickable and over(n)]


def words(n):
    return n.desc or n.text or " ".join(m.text for m in n.walk() if m.text)


def overlaps(ns):
    out = []
    for i, a in enumerate(ns):
        for b in ns[i + 1:]:
            if min(a.b[2], b.b[2]) - max(a.b[0], b.b[0]) > 2 and min(a.b[3], b.b[3]) - max(a.b[1], b.b[1]) > 2:
                out.append(f"{words(a)}{a.b}×{words(b)}{b.b}")
    return out


def explore(d, box=None, skip=lambda n: False, want=None, least=4):
    """every node in box: words, boxes inside box, none overlapping"""
    s = d.screen()
    box = box or area(s)
    ns = [n for n in keys(s, box) if not skip(n)]
    check(len(ns) >= least, f"只找到 {len(ns)} 个键")
    empty = [f"{words(n)}{n.b}" for n in ns if zero(n)]
    check(not empty, f"零面积的框：{empty[:3]}")
    out = [f"{words(n)}{n.b}" for n in ns if not inside(n, box)]
    check(not out, f"框出了 {box}：{out[:3]}")
    silent = [n.b for n in ns if not words(n).strip()]
    check(not silent, f"没话可读的键：{silent}")
    bad = overlaps(ns)
    check(not bad, f"框重叠：{bad[:3]}")
    said = {words(n) for n in ns}
    for w in want or ():
        check(w in said, f"没有读「{w}」的键：{sorted(said)[:12]}")
    return f"{len(ns)} 个键，读的、框都对（摸键时的读屏焦点手动看）"


@case("AX-01", base="B0")
def ax01(d):
    return explore(d, want=["q，1", "空格，拼音", "退格"])


@case("AX-02", base="B0-14键")
def ax02(d):
    return explore(d, want=["Q W，1", "分词"])


@case("AX-03", base="B0-18键")
def ax03(d):
    return explore(d)


@case("AX-04", base="B0-九键")
def ax04(d):
    return explore(d, want=["ABC，2"])


@case("AX-05", base="B0-注音全键盘")
def ax05(d):
    return explore(d)


@case("AX-06", base="B0-注音九键")
def ax06(d):
    return explore(d)


@case("AX-07", base="B0-五笔")
def ax07(d):
    return explore(d)


def emoji_panel(d):
    s = d.screen()
    e = s.find(desc="表情", where="ime")
    if not e:
        raise Blocked("工具栏上没有表情")
    d.tap(e.c, 1.2)


@case("AX-09", base="B0", mutates=True)
def ax09(d):
    emoji_panel(d)
    return explore(d, want=["😀", "空格"])


@case("AX-10", base="B0", mutates=True)
def ax10(d):
    emoji_panel(d)
    k = d.screen().find(text=":-)", where="ime")
    if not k:
        raise Blocked("表情面板底行没有「:-)」")
    d.tap(k.c, 1.2)
    return explore(d, want=[":D", "空格"])


@case("AX-11", base="B0", mutates=True)
def ax11(d):
    d.press("符", after=1.2)
    return explore(d, want=["，"])


@case("AX-12", base="B0", mutates=True)
def ax12(d):
    d.type("nihao")
    d.cands()
    s = d.screen()
    c = s.find(text="你好", where="ime")
    if not c:
        raise Blocked(f"候选里没有「你好」：{d.candidates(s)[:6]}")
    before = {n.b for n in s.ime() if n.clickable}
    d.hold(c.c, 900)
    time.sleep(0.8)
    s = d.screen()
    menu = [n for n in s.nodes if n.pkg == PKG and n.clickable and n.b not in before and (n.text or n.desc)]
    check(len(menu) >= 2, f"长按后的菜单只有 {[words(n) for n in menu]}")
    # the screen (B0: portrait), not a box made of the menu's own nodes, which every one is inside
    w, h = map(int, d.sh("wm size").strip().splitlines()[-1].split()[-1].split("x"))
    box = (0, 0, w, h)
    return explore(d, box=box, skip=lambda n: n.b in before, want=["屏蔽"], least=2)


case("AX-17", needs="jvm:RareCharsPageUatTest.thePageHoldsBackAndLetsThrough、RareCharsTouchTest.aLongPressWithNoFingerIsNoDrag、"
              "RareCharsTouchTest.newLettersAreAtTheirTop")(lambda d: None)


case("AX-15", needs="manual:TalkBack 读框里的拼音（设计页 M16）：手工听")(lambda d: None)
case("AX-16", needs="manual:TalkBack 摸读音行、字行、返回行、上屏和复制，展开后焦点落在哪（JVM：CandidateMenuUatTest.talkBackReadsTheLineTheRowsAndTheWayBackAndIsTakenToWhatOpened 只断言节点和焦点调用）")(lambda d: None)

case("AX-18", needs="jvm:HandwritingUatTest.theCandidateBarsButtonIsLitWhileHandwritingIsOnAndTurnsItOff、"
              "ToolButtonNodeTest.aButtonNamedForItsStateIsNoCheckableNode")(lambda d: None)
case("AX-19", needs="manual:TalkBack 读竖屏、横屏键盘样式两项，单手、悬浮按当时的方向读开关，斜箭头读「退出单手模式」，双击单手后焦点在单手按钮上：真机手工听")(lambda d: None)


case("AX-21", needs="jvm:GroupCardTest.a card read body then strip, the next card after this strip、"
               "ChoiceCardTest.the body is one node read as a radio button named by the keyboard、"
               "FoldCardTest.the title row is one button saying the summary and whether it is open、"
               "RareCharsCardsUatTest.theCardsAndTheLettersAreReadOut")(lambda d: None)


@case("AX-23", base="B0-九键", mutates=True)
def ax23(d):
    # what TalkBack is offered, read off the tree: the words and the actions (TalkBack itself: by ear on a phone)
    p = script("04-panels")
    d.open_page("text")
    items = p.column(d)
    said = [t for t, _ in items]
    check(said and all(said), f"九键左列的项读不出：{said}")
    first, q = items[0][1], p.item_of(d, "？", "?")
    check("移到最前" not in first.actions and {"删除这一项", "编辑这一列"} <= set(first.actions), f"第一项的动作 {first.actions}")
    check({"删除这一项", "移到最前", "编辑这一列"} <= set(q.actions), f"? 的动作 {q.actions}")
    return f"九键左列读 {said}；第一项动作 {first.actions}，? 的 {q.actions}；动作的执行在 JVM（ScrollColumnMenuTest.talkBackDoesTheMenusThreeThings）"


@case("AX-24", base="B0")
def ax24(d):
    p = script("04-panels")
    d.open_page("text")
    p.numbers(d)
    was = p.lock_node(d)
    p.hold_back(d)
    now = p.lock_node(d)
    p.hold_back(d)
    # a node's state description is API 30's: Android 10 says none
    locked = "已锁定" if int(d.sh("getprop ro.build.version.sdk").strip() or 0) >= 30 else ""
    check((was.desc, was.hold, was.state) == ("返回", "锁定", ""), f"返回读「{was.desc}」，长按读「{was.hold}」，状态「{was.state}」")
    check((now.desc, now.hold, now.state) == ("返回", "解锁", locked), f"长按后返回读「{now.desc}」，长按读「{now.hold}」，状态「{now.state}」")
    p.key(d, "symbols", after=1.0)
    s = d.screen()
    panel = next((n.desc for n in s.ime() if n.desc in ("选完符号后停留", "选完符号后返回")), None)
    check(panel == "选完符号后停留", f"符号面板的锁读「{panel}」")
    return f"数字键盘的锁「{was}」→「{now}」，符号面板的锁「{panel}」"
