"""uat/09-handwriting.md (B0-手写). The .md is the source of truth; each function follows its case's
前提/操作/预期. "写「X」" is X drawn on the pad with input motionevent strokes (hw_strokes.py)."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hw_strokes import STROKES  # noqa: E402

from uat import Blocked, add_im, case, check, choose, gear, home, menu_tap, open_url  # noqa: E402


def pad(d):
    p = d.screen().find(desc="手写板", where="ime")
    if not p:
        raise Blocked("没有书写板")
    return p.b


def stroke_cmds(d, ch, strokes=None, gap=0.05):
    x0, y0, x1, y1 = pad(d)
    side = min(x1 - x0, y1 - y0) * 0.8
    ox, oy = x0 + (x1 - x0 - side) / 2, y0 + (y1 - y0 - side) / 2
    cmds = []
    for st in (strokes if strokes is not None else STROKES[ch]):
        pts = [(int(ox + side * x), int(oy + side * y)) for x, y in st]
        cmds.append("input motionevent DOWN %d %d" % pts[0])
        for (a, b), (c, e) in zip(pts, pts[1:]):
            for i in range(1, 7):
                cmds.append("input motionevent MOVE %d %d" % (a + (c - a) * i / 6, b + (e - b) * i / 6))
        cmds.append("input motionevent UP %d %d" % pts[-1])
        cmds.append(f"sleep {gap}")
    return cmds


def write(d, ch, wait=1.6):
    """write ch and stop the pen (the default pause, then a margin)"""
    d.sh("; ".join(stroke_cmds(d, ch)))
    time.sleep(wait)


def ink(d):
    """dark pixels on the pad (a stroke drawn and not yet cleared)"""
    x0, y0, x1, y1 = pad(d)
    im = d.png().convert("L").crop((x0, y0, x1, y1)).resize((120, 60))
    bg = sorted(im.getdata())[len(im.getdata()) // 2]
    return sum(1 for p in im.getdata() if abs(p - bg) > 60)


def hw_settings(d):
    add_im(d, "手写")
    gear(d, "手写")


@case("HW-01", base="B0-手写", visual=True)
def hw01(d):
    s = d.screen()
    p = s.find(desc="手写板", where="ime")
    kb = s.find(rid="keyboard_view", where="ime")
    d.shot("kb", "手写键盘")
    labels = [n.desc if n.desc else ([m.text for m in n.walk() if m.text] or [""])[0]
              for n in kb.walk() if n.clickable]
    right = [x for x in labels if x in ("退格", "，", "。")]
    bottom = [x for x in labels if x in ("符", "123") or x.startswith("空格") or x in ("切换语言", "前往")]
    check(p and (p.b[2] - p.b[0]) * (p.b[3] - p.b[1]) > 0.6 * (kb.b[2] - kb.b[0]) * (kb.b[3] - kb.b[1]) * 0.8,
          f"书写板 {p and p.b}，键盘 {kb.b}")
    check(len(right) == 3, f"右边 {right}")
    check(len(bottom) >= 5, f"下面一排 {bottom}")
    return f"书写板 {p.b}；右边 {right}；底排 {bottom}"


@case("HW-02", base="B0-手写", visual=True)
def hw02(d):
    cmds = stroke_cmds(d, "人")
    d.sh("; ".join(cmds))
    mid = []
    end = time.time() + 0.6   # before the pen counts as stopped (about 0.7 s)
    while not mid and time.time() < end:
        mid = d.candidates()
    time.sleep(1.5)
    s = d.screen()
    c, t = d.candidates(s), d.field_text(s)
    left = ink(d)
    d.shot("field", "写「人」停笔后")
    check(mid, "写的过程中（刚写完、未停笔）候选栏没有候选")
    check(t == "人", f"框里「{t}」")
    check("人" in c, f"候选 {c[:5]}")
    check(left < 40, f"停笔后书写板没清空（墨迹 {left}）")
    return f"写时 {mid[:3]}，停笔后框里「{t}」（下划线看图），候选 {c[:4]}"


@case("HW-03", base="B0-手写", mutates="learn")
def hw03(d):
    write(d, "大")
    write(d, "人")
    d.space()
    t = d.field_text()
    check(t == "大人", f"框里「{t}」")
    return f"「{t}」"


@case("HW-04", base="B0-手写", mutates="learn")
def hw04(d):
    write(d, "淡讠")
    t1, c1 = d.field_text(), [x.split(" ")[0] for x in d.candidates()]
    if t1 != "淡" or c1[1:2] != ["谈"]:
        raise Blocked(f"单写时框里「{t1}」、候选 {c1[:3]}：不是用例要的「淡」在前、「谈」第二")
    write(d, "话")
    t2 = d.field_text()
    check(t2 == "谈话", f"单写时「{t1}」{c1[:3]}，写完「话」后「{t2}」")
    return f"「{t1}」{c1[:2]} →「{t2}」"


@case("HW-05", base="B0-手写", mutates="learn")
def hw05(d):
    write(d, "大")
    write(d, "人")
    c = d.candidates()
    if len(c) < 2:
        raise Blocked(f"候选 {c}")
    cc = c[1]
    d.pick(2)
    t = d.field_text()
    check(t and len(t) == 2 and t.endswith(cc[-1]), f"点第二个候选「{cc}」后框里「{t}」")
    return f"C=「{cc}」：框里「{t}」"


@case("HW-06", base="B0-手写")
def hw06(d):
    st = STROKES["十"][:1]
    d.sh("; ".join(stroke_cmds(d, "十", st)))
    before = ink(d)
    d.bksp()
    time.sleep(0.3)
    after = ink(d)
    c = d.candidates()
    check(before > 40, f"写的一笔没看到（墨迹 {before}）")
    check(after < 40 and not c, f"退格后墨迹 {after}，候选 {c[:3]}")
    return f"墨迹 {before} → {after}，候选清空"


@case("HW-07", base="B0-手写", mutates="learn")
def hw07(d):
    d.switch_im("拼音")
    d.type("hao")
    d.cands(want="好")
    d.pick("好")
    if d.field_text() != "好":
        raise Blocked(f"前提没到：「{d.field_text()}」")
    d.switch_im("手写")
    write(d, "人")
    d.bksp()
    t1 = d.field_text()
    d.bksp()
    t2 = d.field_text()
    check(t1 == "好", f"第一次退格后「{t1}」")
    check(t2 == "", f"第二次退格后「{t2}」")
    return f"「{t1}」→「{t2}」"


@case("HW-08", base="B0-手写", mutates="learn")
def hw08(d):
    write(d, "人")
    k = d.key_node("。")
    if not k:
        raise Blocked("右边没有「。」键")
    d.tap(k.c, 0.6)
    t = d.field_text()
    check(t == "人。", f"框里「{t}」")
    return f"「{t}」"


@case("HW-09", base="B0-手写", mutates="learn", visual=True)
def hw09(d):
    write(d, "人")
    for _ in range(3):
        # while 人 is composing the candidates replace the bar and its ⌄: the system back hides it
        if d.screen().find(desc="隐藏键盘", where="ime"):
            d.hide_keyboard()
        else:
            d.back()
        time.sleep(0.8)
        if not d.screen().find(rid="keyboard_view", where="ime"):
            break
    else:
        raise Blocked("键盘收不起来")
    t = d.field_text()
    d.shot("app", "收起键盘后")
    check(t == "人", f"框里「{t}」")
    return f"「{t}」留在框里（下划线看图）"


@case("HW-10", base="B0-手写", mutates="learn")
def hw10(d):
    write(d, "我")
    d.space()
    time.sleep(0.5)
    c = d.candidates()
    if not c:
        check(False, "上屏后没有联想")
    w = c[0]
    d.pick(1)
    t = d.field_text()
    check(t == "我" + w.split(" ")[0], f"点联想「{w}」后框里「{t}」")
    return f"联想 {c[:4]}；「{t}」"


@case("HW-10a", base="B0-手写", mutates=True)
def hw10a(d):
    write(d, "我")
    d.space()
    time.sleep(0.5)
    c0 = d.candidates()
    if not c0:
        raise Blocked("没有联想")
    w = c0[0].split(" ")[0]
    d.pick(1)
    d.clear()
    write(d, "我")
    d.space()
    time.sleep(0.5)
    menu = d.long_pick(w)
    has_block = any(x == "屏蔽" for x in menu)
    check("不再联想" in menu and has_block, f"长按菜单 {menu[:8]}")
    n = next((n for n in d.screen().nodes if n.text == "不再联想"), None)
    if n is None:
        raise Blocked(f"菜单里找不到「不再联想」这一项：{menu[:8]}")
    d.tap(n.c, 0.8)
    d.clear()
    write(d, "我")
    d.space()
    time.sleep(0.5)
    c3 = d.all_cands()
    check(w not in [x.split(" ")[0] for x in c3], f"不再联想后联想里还有「{w}」：{c3[:6]}")
    # B still typed in pinyin: 不再联想 is the pair's alone
    try:
        from pypinyin import lazy_pinyin
    except ImportError:
        raise Blocked("没有 pypinyin，拼不出 B 的拼音（pip install pypinyin）")
    d.clear()
    d.switch_im("拼音")
    d.type("".join(lazy_pinyin(w)))
    p = [x.split(" ")[0] for x in d.all_cands()]
    check(w in p, f"拼音里打不出「{w}」：{p[:6]}")
    return f"联想「{w}」；菜单有不再联想、屏蔽；之后 {c3[:4]}；拼音里照常有"


@case("HW-19", base="B0-手写", mutates="learn")
def hw19(d):
    write(d, "人")
    c = [x.split(" ")[0] for x in d.candidates()]
    c0 = c
    if "入" not in c:
        raise Blocked(f"写「人」的候选里没有「入」：{c[:6]}")
    d.pick("入")
    d.clear()
    write(d, "人")
    menu = d.long_pick("入")
    has_forget = any("忘掉学到的" in x for x in menu)
    has_block = any(x == "屏蔽" for x in menu)
    has_pin = any("置顶" in x or "固定" in x for x in menu)
    check(has_forget and has_block and not has_pin, f"长按菜单 {menu[:8]}")
    # and blocked: never written again
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    c2 = [x.split(" ")[0] for x in d.all_cands()]
    # undone without writing again: the shown candidates read anew (F2738)
    d.tap_text("撤销", where="all", scroll=False, after=0.8)
    c3 = [x.split(" ")[0] for x in d.all_cands()]
    d.long_pick("入")
    menu_tap(d, "忘掉学到的")
    c4 = [x.split(" ")[0] for x in d.candidates()]
    d.clear()
    write(d, "人")
    c5 = [x.split(" ")[0] for x in d.candidates()]
    d.clear()
    check("入" not in c2, f"屏蔽后还有「入」：{c2[:6]}")
    check("入" in c3, f"点撤销后（不重写）{c3[:6]}")
    check(c4[:3] == c0[:3] and c5[:3] == c0[:3], f"忘掉后当场 {c4[:3]}、重写 {c5[:3]}，学之前 {c0[:3]}")
    return f"长按「入」：{menu[:4]}；屏蔽后 {c2[:4]}；撤销后 {c3[:4]}；忘掉后 {c4[:3]}"


@case("HW-11", base="B0-手写", page="sentence", mutates="learn")
def hw11(d):
    write(d, "我")
    d.enter()
    time.sleep(0.5)
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    check(t == "我", f"框里「{t!r}」")
    check(not c, f"有联想 {c[:3]}")
    return f"「{t}」，没有联想"


def pause_setting(d, option):
    hw_settings(d)
    choose(d, "停笔多久算写完一个字", option)
    d.open_page("text")
    d.switch_im("手写")


@case("HW-12", base="B0-设置", mutates=True)
def hw12(d):
    pause_setting(d, "很短")
    d.sh("; ".join(stroke_cmds(d, "人")))
    time.sleep(0.5)
    t = d.field_text()
    left = ink(d)
    check(t == "人" and left < 40, f"0.5 秒后框里「{t}」，墨迹 {left}")
    return f"0.5 秒内「{t}」"


@case("HW-13", base="B0-设置", mutates=True)
def hw13(d):
    pause_setting(d, "很长")
    st = STROKES["十"]
    d.sh("; ".join(stroke_cmds(d, "十", st[:1])))
    time.sleep(1.0)
    mid = ink(d)
    d.sh("; ".join(stroke_cmds(d, "十", st[1:])))
    time.sleep(2.0)
    t = d.field_text()
    check(mid > 40, f"一横后 1 秒书写板清空了（墨迹 {mid}）")
    check(t == "十", f"写完一竖后框里「{t}」")
    return f"一横后 1 秒墨迹 {mid}；「{t}」"



def learned(d):
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "自动学到的词")
    return d.all_texts()


@case("HW-14", base="B0-手写", mutates=True)
def hw14(d):
    # the .md's steps: 人, pick the second; again it is second; picked twice it is first
    def cands():
        write(d, "人")
        time.sleep(0.5)
        return [x.split(" ")[0] for x in d.candidates()]
    c0 = cands()
    if len(c0) < 2:
        raise Blocked(f"候选 {c0}")
    w = c0[1]
    d.pick(2)
    d.clear()
    c1 = cands()
    d.pick(w)
    d.clear()
    c2 = cands()
    d.clear()
    check(c1[:2] == [c0[0], w] and c2[:1] == [w], f"「{w}」：开始 {c0[:3]}，点一次后 {c1[:3]}，点两次后 {c2[:3]}")
    return f"「{w}」：{c0[:3]} → {c1[:3]} → {c2[:3]}"


@case("HW-15", base="B0-手写", mutates=True)
def hw15(d):
    write(d, "人")
    d.space()
    rows = learned(d)
    check(not any("人" in r for r in rows if not r.startswith(("自动学到", "还没有"))), f"自动学到的词 {rows[:10]}")
    return f"列表 {rows[:4]}"


@case("HW-16", base="B0-手写")
def hw16(d):
    open_url(d, "password")
    time.sleep(0.5)
    s = d.screen()
    k = d.keys(refresh=True)
    nums = [n.text for n in s.ime() if n.text in "1234567890" and n.text and n.c[1] < (s.find(rid="keyboard_view", where="ime").b[1])]
    check("q" in k and not s.find(desc="手写板", where="ime"), "密码框里不是英文全键盘")
    check(len(nums) >= 10, f"上面一排数字 {nums}")
    open_url(d, "text")
    time.sleep(0.5)
    check(d.screen().find(desc="手写板", where="ime"), "回到普通文本后不是书写板")
    return "密码框英文全键盘＋数字行；回来是书写板"


@case("HW-17", base="B0-设置", mutates=True)
def hw17(d):
    hw_settings(d)
    t = d.all_texts()
    check(t and "通用输入设置" in t[:4], f"最上面 {t[:4]}")
    check("停笔多久算写完一个字" in t, f"没有停笔：{t[:12]}")
    bad = [x for x in ("每页候选数", "联想下一个词", "性能档位") if x in t]
    check(not bad, f"有 {bad}")
    return f"{t[:8]}"


@case("HW-22", base="B0-手写", mutates="learn")
def hw22(d):
    # the same strokes each time (hw_strokes.py): the same three read, the same code (review F2655 NIT)
    def cands():
        write(d, "人")
        time.sleep(0.5)
        return [x.split(" ")[0] for x in d.candidates()]
    c0 = cands()
    if len(c0) < 2:
        raise Blocked(f"候选 {c0}")
    first, w = c0[0], "入"
    if w not in c0:
        raise Blocked(f"写「人」的候选里没有「入」：{c0[:6]}")
    d.pick(w)
    cands()
    d.space()
    d.bksp()
    cands()
    d.pick(w)
    c = cands()
    d.clear()
    check(c[:1] == [w], f"「{first}」上屏又删掉以后，两次点「{w}」：候选 {c[:3]}")
    return f"{c0[:3]} → {c[:3]}"


@case("HW-23", base="B0-手写", mutates="learn")
def hw23(d):
    write(d, "人")
    time.sleep(0.5)
    c0 = [x.split(" ")[0] for x in d.candidates()]
    if len(c0) < 3:
        raise Blocked(f"候选 {c0}")
    d.pick(3)
    d.bksp()
    write(d, "人")
    time.sleep(0.5)
    c1 = [x.split(" ")[0] for x in d.candidates()]
    d.clear()
    check(c1[:3] == c0[:3], f"点「{c0[2]}」又删掉：之前 {c0[:3]}，之后 {c1[:3]}")
    return f"{c0[:3]} → {c1[:3]}"


@case("HW-21", base="B0-手写", mutates=True)
def hw21(d):
    d.switch_im("拼音")
    d.type("ru")
    c0 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    d.switch_im("手写")
    # HW-14's steps: 人's second picked twice, first after
    for _ in range(2):
        write(d, "人")
        time.sleep(0.5)
        c = [x.split(" ")[0] for x in d.candidates()]
        if "入" not in c:
            raise Blocked(f"写「人」的候选 {c[:5]}")
        d.pick("入")
        d.clear()
    write(d, "人")
    time.sleep(0.5)
    c = [x.split(" ")[0] for x in d.candidates()]
    d.clear()
    if c[:1] != ["入"]:
        raise Blocked(f"前提没到：写「人」{c[:3]}")
    d.switch_im("拼音")
    d.type("ru")
    c1 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    check(c1[:1] == c0[:1], f"ru 学前 {c0[:3]}，学后 {c1[:3]}")
    return f"ru {c0[:3]} → {c1[:3]}"


@case("HW-24", base="B0-手写", mutates="learn")
def hw24(d):
    def wo():
        write(d, "我")
        d.space()
        time.sleep(0.5)
        return [x.split(" ")[0] for x in d.candidates()]
    c0 = wo()
    if len(c0) < 2:
        raise Blocked(f"联想 {c0[:4]}")
    d.pick(c0[1])
    d.bksp(len(c0[1]))
    d.clear()
    c1 = wo()
    d.clear()
    check(c1[:4] == c0[:4], f"点联想「{c0[1]}」又删掉：之前 {c0[:4]}，之后 {c1[:4]}")
    return f"{c0[:4]} → {c1[:4]}"
