"""uat/20-accessibility.md. The .md is the source of truth; each function follows its case's
前提/操作/预期. A key touched under TalkBack should get the screen reader's focus; on the emulator neither `input tap` (it bypasses
touch exploration: the key is typed) nor sendevent on the virtio touchscreen (no node, the app's
own neither, ever gets the focus) explores, so the script checks the nodes (words, boxes) and the
touch is done by hand (see the .md)."""
import time

from uat import PKG, Blocked, case, check

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


@case("AX-08", base="B0-手写")
def ax08(d):
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
    return explore(d, want=["😀"])


@case("AX-10", base="B0", mutates=True)
def ax10(d):
    emoji_panel(d)
    k = d.screen().find(text=":-)", where="ime")
    if not k:
        raise Blocked("表情面板底行没有「:-)」")
    d.tap(k.c, 1.2)
    return explore(d, want=[":D"])


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

