"""uat/06-pinyin.md: the B0 cases. The .md is the source of truth; each function follows its case's
前提/操作/预期. Cases on B0-设置 (PY-12, 13, 19, 20, 23, 28, 47, 50, 51) are not in this pilot."""
import datetime
import re
import subprocess
import time

from uat import Blocked, Fail, case, check, choose, first, gear, glide_on, glide_shot, has, home, menu_tap


def compact(p):
    return p.replace(" ", "")


# ---------------------------------------------------------------- 打字

@case("PY-01", mutates="learn")
def py01(d):
    d.type("nihao")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"框里是「{t}」")
    return f"框里是「{t}」"


@case("PY-02", mutates="learn")
def py02(d):
    d.type("womenyiqiqugongyuan")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "我们一起去公园", f"框里是「{t}」")
    return f"框里是「{t}」"


@case("PY-03", mutates="learn")
def py03(d):
    d.type("womenqunali")
    d.cands(want="我们")
    d.pick("我们")
    s = d.screen()
    t, p, c = d.field_text(s), d.preedit(s), d.candidates(s)
    check(not t, f"框里是「{t}」（应还空着）")
    check(p.replace(" ", "").replace("|", "") == "我们qunali", f"拼音行是 `{p}`")
    check(c and c[0].startswith("去") and len(first(c)) >= 3, f"候选 {c[:4]}")
    return f"拼音行 `{p}`，第一个候选「{c[0]}」"


@case("PY-04", mutates="learn")
def py04(d):
    d.type("shi")
    c = d.cands()
    check(len(c) >= 3, f"候选只有 {c}")
    d.pick(3)
    t = d.field_text()
    check(t == first(c[2:]), f"第三个候选「{c[2]}」，框里是「{t}」")
    return f"第三个「{c[2]}」，框里是「{t}」"


@case("PY-05")
def py05(d):
    d.type("nihao")
    before = d.cands()
    d.bksp()
    s = d.screen()
    p, c = d.preedit(s), d.candidates(s)
    check(compact(p) == "niha", f"打字状态是 `{p}`")
    check(c and c != before, f"候选没变：{c[:4]}")
    return f"`{p}`，候选 {before[:3]} → {c[:3]}"


@case("PY-06")
def py06(d):
    d.type("xi")
    z = d.keys()["z"]
    d.swipe(z, (z[0], z[1] + 130), 150)
    d.type("an")
    s = d.screen()
    p, c = d.preedit(s), d.candidates(s)
    check(p == "xi'an", f"显示 `{p}`")
    check(first(c) == "西安", f"第一个候选「{c[:1]}」")
    return f"`{p}`，第一个「{c[0]}」"


@case("PY-07")
def py07(d):
    d.type("zg")
    a = d.cands()
    d.bksp(2)
    d.type("wsm")
    b = d.cands()
    check(has(a, "中国"), f"zg 第一页 {a}")
    check(has(b, "为什么"), f"wsm 第一页 {b}")
    return f"zg {a[:3]}；wsm {b[:3]}"


@case("PY-08", mutates="learn")
def py08(d):
    d.type("zhongg")
    c = d.cands()
    check(has(c, "中国"), f"第一页 {c}")
    return f"第一页 {c[:4]}"


# pinyin of the words slx may offer (the case picks one not in first place and types it in full)
SLX = {"生理性": "shenglixing", "上留下": "shangliuxia", "三两下": "sanliangxia", "生理学": "shenglixue",
       "顺利性": "shunlixing", "思路线": "siluxian", "实例性": "shilixing", "上流向": "shangliuxiang"}


def learn_slx(d):
    """the 前提 of PY-09/PY-32: W not first for slx, typed in full and committed twice"""
    d.type("slx")
    c = d.cands()
    w = next((x for x in c[1:] if x in SLX and len(x) >= 2), None)
    if not w:
        raise Blocked(f"slx 第一页没有已知全拼的词：{c}")
    d.clear()
    for i in range(2):
        d.type(SLX[w])
        d.cands(want=w)
        d.pick(w)
        d.space()  # 隔开
    t = d.field_text()
    check(t.count(w) == 2, f"上屏两次后框里是「{t}」")
    d.clear()
    return c, w


@case("PY-09", mutates="learn", clean=True)
def py09(d):
    c0, w = learn_slx(d)
    d.type("slx")
    c = d.cands()
    check(first(c) == w, f"W=「{w}」，原来 {c0[:4]}，现在 {c[:4]}")
    return f"W=「{w}」：{c0[:3]} → {c[:3]}"


CONTEXT = "zuotianwanshangxialeyichang"


def type_context(d):
    d.type(CONTEXT)
    d.cands()
    d.space()
    t = d.field_text() or ""
    if t != "昨天晚上下了一场":
        raise Blocked(f"整句首选打不出前提：「{t}」")


@case("PY-10", mutates="learn", clean=True)
def py10(d):
    type_context(d)
    d.type("daxue")
    c = d.cands()
    check(first(c) == "大雪", f"候选 {c[:4]}")
    return f"候选 {c[:3]}"


@case("PY-11", page="chat", mutates="learn", clean=True)
def py11(d):
    type_context(d)
    d.enter()
    t = d.field_text()
    if t:
        raise Blocked(f"回车后框没清空：「{t}」")
    d.type("daxue")
    c = d.cands()
    check(first(c) == "大雪", f"候选 {c[:4]}")
    return f"发出后候选 {c[:3]}"


# ---------------------------------------------------------------- 纠错

def first_page_has(code, *words):
    def fn(d):
        d.type(code)
        c = d.cands()
        miss = [w for w in words if not has(c, w)]
        check(not miss, f"第一页 {c}，没有 {miss}")
        return f"第一页 {c[:5]}"
    return fn


case("PY-14")(first_page_has("zhagn", "张", "章"))
case("PY-15")(first_page_has("zhon", "中"))
case("PY-16")(first_page_has("jv", "句"))
case("PY-17")(first_page_has("nihso", "你好"))


@case("PY-18")
def py18(d):
    d.type("jiaose")
    c = d.cands()
    hit = [x for x in c if x.split(" ", 1)[0] == "角色"]
    check(hit, f"候选 {c}")
    check("jué sè" in hit[0], f"「{hit[0]}」旁没有 jué sè")
    return f"「{hit[0]}」"


# ---------------------------------------------------------------- 模糊音、中英混输、笔画

@case("PY-21")
def py21(d):
    d.type("zongguo")
    c = d.cands()
    check(c and not has(c, "中国"), f"第一页 {c}")
    return f"第一页 {c[:5]}"


@case("PY-22", mutates="learn")
def py22(d):
    d.type("wodeiphone")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "我的iPhone", f"框里是「{t}」")
    return f"框里是「{t}」"


@case("PY-24")
def py24(d):
    d.type("uhsp")
    c = d.all_cands()
    miss = [w for w in ("才", "本", "村") if not has(c, w)]
    check(not miss, f"候选 {c[:8]}，没有 {miss}")
    no_reading = [x for x in c if x.split(" ", 1)[0] in ("才", "本", "村") and " " not in x]
    check(not no_reading, f"旁边没有读音：{no_reading}")
    return f"`{d.preedit()}`，候选 {c[:4]}"


@case("PY-25")
def py25(d):
    d.type("usb")
    s = d.screen()
    c, p = d.candidates(s), d.preedit(s)
    check(p == "usb", f"打字状态 `{p}`（笔画会显示成 u 加笔画）")
    check(any("usb" in x.lower() for x in c), f"候选 {c}")
    return f"`{p}`，候选 {c[:4]}"


# ---------------------------------------------------------------- 联想

@case("PY-26", mutates="learn")
def py26(d):
    d.type("jintian")
    d.cands(want="今天")
    d.space()
    s = d.screen()
    t, c = d.field_text(s), d.candidates(s)
    check(t == "今天", f"框里是「{t}」")
    check(c, "上屏后候选栏没有联想词")
    d.pick(1)
    t2 = d.field_text()
    check(t2 == "今天" + first(c), f"点「{c[0]}」后框里是「{t2}」")
    return f"联想 {c[:4]}；点后「{t2}」"


@case("PY-27", mutates="learn")
def py27(d):
    d.type("jintian")
    d.cands(want="今天")
    d.space()
    pred = d.cands()
    if not pred:
        raise Blocked("打出「今天」后没有联想（前提）")
    d.type("h")
    s = d.screen()
    p, c = d.preedit(s), d.candidates(s)
    check(p == "h", f"打字状态 `{p}`")
    check(c and c != pred, f"候选还是联想 {c[:4]}")
    return f"联想 {pred[:3]} → h 的候选 {c[:3]}"


# ---------------------------------------------------------------- 长按候选

@case("PY-29", mutates=True)
def py29(d):
    d.type("shi")
    d.cands()
    m = d.long_pick("是")
    check("不再显示这个词" in m, f"菜单 {[x for x in m if len(x) > 3]}")
    menu_tap(d, "不再显示这个词")
    d.clear()
    d.type("shi")
    c = d.all_cands()
    check(c and not has(c, "是"), f"之后候选 {c[:8]}")
    return f"屏蔽后 {c[:5]}"


def custom_phrases(d):
    """the 自定义短语 page's rows as text"""
    d.hide_keyboard()
    d.open_settings()
    d.goto("词库与短语", "自定义短语")
    time.sleep(1.0)
    return d.all_texts()


def pin_fifth(d):
    d.type("shi")
    c = d.cands()
    if len(c) < 5:
        raise Blocked(f"shi 只有 {c}")
    cc = first(c[4:])
    m = d.long_pick(5)
    if "以自定义词组的方式置顶" not in m:
        raise Fail(f"菜单里没有置顶：{[x for x in m if len(x) > 3]}")
    menu_tap(d, "以自定义词组的方式置顶")
    d.clear()
    return c, cc


@case("PY-30", mutates=True)
def py30(d):
    c0, cc = pin_fifth(d)
    d.type("shi")
    c = d.cands()
    check(first(c) == cc, f"C=「{cc}」，置顶后 {c[:4]}")
    rows = custom_phrases(d)
    check(any(r.startswith("shi,") and r.endswith("=" + cc) for r in rows), f"自定义短语里是 {rows[:12]}")
    return f"C=「{cc}」 排第一；自定义短语 shi→{cc}"


@case("PY-31", mutates=True)
def py31(d):
    c0, cc = pin_fifth(d)
    d.type("shi")
    c = d.cands()
    if first(c) != cc:
        raise Blocked(f"前提没到：置顶后 {c[:4]}")
    m = d.long_pick(1)
    check("从自定义词组中删除" in m, f"菜单 {[x for x in m if len(x) > 3]}")
    menu_tap(d, "从自定义词组中删除")
    d.clear()
    d.type("shi")
    c2 = d.cands()
    check(first(c2) != cc, f"删除后还排第一：{c2[:4]}")
    rows = custom_phrases(d)
    check(not any(r.startswith("shi,") and r.endswith("=" + cc) for r in rows), f"自定义短语里还有：{rows[:12]}")
    return f"「{cc}」 删除后 {c2[:4]}；自定义短语里没有了"


@case("PY-32", mutates=True, clean=True)
def py32(d):
    c0, w = learn_slx(d)
    d.type("slx")
    c = d.cands()
    if first(c) != w:
        raise Blocked(f"前提没到：W=「{w}」 学了两次仍是 {c[:4]}")
    m = d.long_pick(1)
    check("忘记词汇" in m, f"菜单 {[x for x in m if len(x) > 3]}")
    menu_tap(d, "忘记词汇")
    d.clear()
    d.type("slx")
    c2 = d.cands()
    check(first(c2) != w, f"忘记后 W=「{w}」 仍第一：{c2[:4]}")
    return f"W=「{w}」：忘记后 {c2[:4]}（原来 {c0[:4]}）"


@case("PY-33", clean=True, mutates="learn")
def py33(d):
    d.type("shi")
    c = d.cands()
    m = d.long_pick(4)
    menu = [x for x in m if x in ("忘记词汇", "从自定义词组中删除", "以自定义词组的方式置顶", "不再显示这个词")]
    d.back()
    check("忘记词汇" not in menu and "从自定义词组中删除" not in menu, f"长按「{c[3]}」，菜单 {menu}")
    check("以自定义词组的方式置顶" in menu and "不再显示这个词" in menu, f"长按「{c[3]}」，菜单 {menu}")
    return f"长按「{c[3]}」，菜单 {menu}"


# ---------------------------------------------------------------- 学词

SENTENCE, SENTENCE_PY, PIECES = "这个誓言很难", "zhegeshiyanhennan", ("这个", "誓言", "很难")


def learn_sentence(d):
    """PY-34's steps: 这个誓言很难 picked piece by piece (the whole-sentence first is 这个实验很难)"""
    d.type(SENTENCE_PY)
    c0 = d.cands()
    if first(c0) == SENTENCE:
        raise Blocked(f"整句首选已经是「{SENTENCE}」，前提要求首选是别的：{c0[:3]}")
    for w in PIECES:
        d.cands()
        d.pick(w)
    time.sleep(0.5)
    t = d.field_text()
    if t != SENTENCE:
        raise Blocked(f"一段段选完框里是「{t}」")
    d.clear()
    return c0


@case("PY-34", mutates="learn", clean=True)
def py34(d):
    c0 = learn_sentence(d)
    d.type(SENTENCE_PY)
    c = d.cands()
    check(first(c) == SENTENCE, f"第二次候选 {c[:4]}（第一次 {c0[:2]}）")
    return f"第一次 {c0[:2]}，第二次 {c[:3]}"


@case("PY-36", mutates=True, clean=True)
def py36(d):
    try:
        learn_sentence(d)
    except Fail as e:
        raise Blocked(f"前提（PY-34）没到：{e}")
    d.hide_keyboard()
    d.open_settings()
    d.goto("词库与短语", "用户词库", "自动学到的词")
    rows = search_list(d, "shiyan", "誓言")
    check(any(SENTENCE in r for r in rows), f"搜「誓言」后列表 {rows[:8]}")
    return f"列表里有「{SENTENCE}」"


def search_list(d, code, word):
    """a word list page's search box (the list's top field; its right end is the clear button):
    type the word with our keyboard"""
    s = d.screen()
    f = next((n for n in s.app() if n.cls == "EditText"), None)
    if not f:
        raise Blocked(f"列表页没有搜索框：{s.texts()[:8]}")
    d.tap((f.b[0] + 80, f.c[1]), 1.0)
    d._keys = None
    d.type(code)
    d.cands(want=word)
    d.pick(word)
    time.sleep(1.0)
    d.hide_keyboard()
    return d.screen().texts()


@case("PY-35", mutates="learn", clean=True)
def py35(d):
    d.type("shi")
    c0 = d.cands()
    if len(c0) < 4:
        raise Blocked(f"shi 只有 {c0}")
    cc = first(c0[3:])
    d.pick(4)
    d.clear()
    d.type("shi")
    c = d.cands()
    pos = next((i for i, x in enumerate(c) if first([x]) == cc), 99)
    check(pos < 3, f"C=「{cc}」：{c0[:6]} → {c[:6]}")
    return f"C=「{cc}」 第 4 → 第 {pos + 1}"


VULGAR = ("肏", "屌", "操你", "操逼", "操蛋", "草泥马", "肏你")


@case("PY-37")
def py37(d):
    d.type("cao")
    c = d.cands()
    bad = [x for x in c if first([x]) in VULGAR]
    check(not bad, f"第一页 {c}，有粗俗词 {bad}")
    return f"第一页 {c}"


@case("PY-38", mutates="learn", clean=True)
def py38(d):
    d.type("cao")
    c0 = d.cands()
    # pushed down, not dropped: page through the expanded list until one shows
    allc = d.all_cands(pages=10)
    x = next((w for w in allc[len(c0):] if first([w]) in VULGAR), None)
    if not x:
        hit = [w for w in c0 if first([w]) in VULGAR]
        raise Blocked(f"后面的页里找不到粗俗词（第一页 {c0}，其中 {hit}）")
    x = first([x])
    d.pick(x)
    d.clear()
    d.type("cao")
    c = d.cands()
    check(has(c, x), f"X=「{x}」：再打 cao 第一页 {c}")
    return f"X=「{x}」：{c}"


# ---------------------------------------------------------------- 改拼音

def tap_between(d, letters, i):
    """tap the composing row between glyph i-1 and glyph i (glyphs drawn, spaces not)"""
    spans, y = d.preedit_glyphs()
    if len(spans) != len(letters):
        raise Blocked(f"拼音行里找到 {len(spans)} 个字形，应是 {len(letters)}（{letters}）")
    x = (spans[i - 1][1] + spans[i][0]) // 2 if i < len(spans) else spans[-1][1] + 4
    d.tap((x, y), 0.6)


@case("PY-39", mutates="learn")
def py39(d):
    code = "nishiwozuixihunde"
    d.type(code)
    d.cands()
    tap_between(d, code, code.index("hun") + 2)
    p0 = d.preedit()
    check("hu|n" in p0, f"点后拼音行 `{p0}`（| 是光标）")
    d.type("a")
    s = d.screen()
    p, c = d.preedit(s).replace("|", ""), d.candidates(s)
    check("xi huan de" in p, f"拼音 `{p}`")
    check(first(c) == "你是我最喜欢的", f"候选 {c[:3]}")
    d.space()
    t = d.field_text()
    check(t == "你是我最喜欢的", f"框里是「{t}」")
    return f"`{p}`，「{t}」"


@case("PY-40", mutates="learn")
def py40(d):
    code = "nishiwozuixihuande"
    d.type(code)
    d.cands()
    tap_between(d, code, code.index("shi") + 3)
    d.bksp(3)
    p = d.preedit()
    check(p.replace("|", "").split() == ["ni", "wo", "zui", "xi", "huan", "de"], f"拼音 `{p}`")
    check(p.replace(" ", "").startswith("ni|"), f"光标不在 ni 后面：`{p}`（| 是光标）")
    d.space()
    t = d.field_text()
    check(t == "你我最喜欢的", f"框里是「{t}」")
    return f"`{p}`，「{t}」"


@case("PY-41", visual=True)
def py41(d):
    # the case's example fits on one 1080 px row here; twice as long, so the row has to scroll
    d.type("woxiangzhidaojintianwanshangwomenqunalichifan" * 2)
    d.cands()
    x, y = d.keys()["space"]
    # one slow swipe in the background (separate `input motionevent` calls do not make a drag the
    # space key follows), the composing row read while it moves
    # 330 px in 4 s, a third of the width: so slow the first step comes after the long-press delay,
    # where the cursor once did not move at all (KeyGestureRecognizer.draggedPastSlop)
    sw = subprocess.Popen(["adb", "-s", d.serial, "shell", "input", "swipe", str(x), str(y),
                           str(x - 330), str(y), "4000"])
    marks, t0 = [], time.time()
    while sw.poll() is None and time.time() - t0 < 8:
        marks.append(d.preedit().replace(" ", "").find("|"))
        time.sleep(0.1)
    sw.wait()
    d.shot("kb", "空格左滑一次后")
    # on until the cursor has passed the row's left edge (one slide moves it ~11 letters)
    for _ in range(5):
        d.swipe((x, y), (x - 330, y), 2800, after=0.3)
    p_end = d.preedit()
    d.shot("kb", f"再滑五次后（| 在第 {p_end.replace(' ', '').find('|')} 个字母后）")
    marks = [m for i, m in enumerate(marks) if i == 0 or m != marks[i - 1]]
    t = d.field_text()
    check(not t, f"框里有字「{t}」")
    moved = [m for m in marks if m >= 0]
    check(len(moved) >= 2 and all(a > b for a, b in zip(moved, moved[1:])),
          f"拼音行的光标（| 的位置）没有一步步往前：{marks}")
    s = d.screen()
    row = next((n for n in s.ime() if n.cls == "HorizontalScrollView"), None)
    if row:
        d.swipe((200, (row.b[1] + row.b[3]) // 2), (900, (row.b[1] + row.b[3]) // 2), 300)
        d.shot("kb", "在拼音行上向右拖后")
    return f"光标位置 {moved}；看图：行随光标滚动、拖动能滚"


@case("PY-42", mutates="learn")
def py42(d):
    d.type("nihao")
    d.cands()
    d.pick("你")
    s = d.screen()
    hide = s.find(desc="隐藏候选词列表", where="ime")   # 你 was picked from the expanded list
    if hide:
        d.tap(hide.c, 0.5)
    p = d.preedit()
    if p.replace(" ", "") != "你hao":
        raise Blocked(f"点「你」后拼音行是 `{p}`")
    spans, y = d.preedit_glyphs()
    d.tap(((spans[0][0] + spans[0][1]) // 2, y), 0.6)
    p1 = d.preedit()
    check(p1.replace(" ", "") == "你|hao", f"点「你」后 `{p1}`（| 是光标，应在「你」后）")
    d.bksp()
    p2 = d.preedit()
    check(p2.replace("|", "") == "n hao", f"退格后拼音行 `{p2}`")
    return f"`{p}` → `{p2}`"


@case("PY-43")
def py43(d):
    d.type("uhp")
    p0 = d.preedit()
    if p0 != "u一丿":
        raise Blocked(f"拼音行 `{p0}`")
    tap_between(d, "u一丿", 2)
    p1 = d.preedit()
    check(p1 == "u一|丿", f"点后 `{p1}`（| 是光标，应在 一 后）")
    d.type("s")
    p = d.preedit().replace("|", "")
    c = d.all_cands()
    check(p == "u一丨丿", f"补上后 `{p}`")
    miss = [w for w in ("才", "本", "村") if not has(c, w)]
    check(not miss, f"候选 {c[:8]} 没有 {miss}")
    return f"`{p}`，候选 {c[:4]}"


# ---------------------------------------------------------------- 拆字、表情、自带短语

@case("PY-44")
def py44(d):
    d.type("uniuniuniu")
    a = d.cands()
    d.clear()
    d.type("ujinjinjin")
    b = d.cands()
    check(a and a[0].startswith("犇") and " " in a[0], f"uniuniuniu {a[:3]}")
    check(b and b[0].startswith("鑫"), f"ujinjinjin {b[:3]}")
    return f"{a[:2]}；{b[:2]}"


@case("PY-45", mutates=True)
def py45(d):
    d.type("usb")
    a = d.cands()
    p = d.preedit()
    check(p == "usb" and any("usb" in x.lower() for x in a), f"usb：`{p}` {a[:4]}")
    d.clear()
    d.hide_keyboard()
    d.open_settings()
    d.goto("输入法")
    s = d.screen()
    n = s.find(text="拼音")
    gear = [k for k in n.parent.kids if k.cls == "ImageButton"] if n and n.parent else []
    if not gear:
        raise Blocked("输入法页找不到拼音的齿轮")
    d.tap(gear[0].c, 1.2)
    d.switch("拆字", False)
    d.open_page("text")
    d.type("uniuniuniu")
    b = d.cands()
    check(not has(b, "犇"), f"关掉后 {b[:4]}")
    return f"usb {a[:3]}；关后 uniuniuniu {b[:3]}"


@case("PY-46", mutates="learn")
def py46(d):
    d.type("dianzan")
    c = d.cands(want="👍")
    i = next((k for k, x in enumerate(c) if x == "👍"), None)
    check(i is not None and i >= 2, f"候选 {c}")
    check(c[0] != "👍" and not re.match(r"^[\U0001F000-\U0001FFFF]", c[0]), f"第一个是 {c[0]}")
    d.pick("👍")
    t = d.field_text()
    check(t == "👍", f"框里是「{t}」")
    d.clear()
    d.type("dianzan")
    c2 = d.cands()
    check(c2 and c2[0] != "👍", f"再打 {c2[:4]}")
    return f"{c[:4]} → 「{t}」；再打 {c2[:3]}"


def device_now(d):
    out = d.sh("date +%Y-%m-%dT%H:%M:%S").strip()
    return datetime.datetime.strptime(out, "%Y-%m-%dT%H:%M:%S")


CN_DIGIT = "〇一二三四五六七八九"


def cn_num(n):
    if n < 10:
        return CN_DIGIT[n]
    if n < 20:
        return "十" + (CN_DIGIT[n % 10] if n % 10 else "")
    return CN_DIGIT[n // 10] + "十" + (CN_DIGIT[n % 10] if n % 10 else "")


@case("PY-48", mutates="learn")
def py48(d):
    now = device_now(d)
    d.type("rq")
    d.cands()
    rq = d.all_cands()
    want = [f"{now.year}年{now.month}月{now.day}日", now.strftime("%Y-%m-%d"),
            "".join(CN_DIGIT[int(x)] for x in str(now.year)) + f"年{cn_num(now.month)}月{cn_num(now.day)}日"]
    check(first(rq) == "日期", f"rq 第一个 {rq[:2]}")
    miss = [w for w in want if w not in rq[1:8]]
    check(not miss, f"rq {rq[:6]}，没有 {miss}")
    d.space()
    t = d.field_text()
    check(t == "日期", f"空格后框里是「{t}」")
    now = device_now(d)
    d.type("sj")
    sj = d.cands()
    times = {f"{(now + datetime.timedelta(minutes=k)).strftime('%H:%M')}" for k in (-1, 0, 1)}
    check(any(x in times for x in sj[1:]), f"sj {sj[:4]}，时间应近 {now:%H:%M}")
    check(any(re.fullmatch(r"\d\d时\d\d分", x) for x in sj[1:]), f"sj {sj[:4]}")
    d.bksp(2)
    d.type("xq")
    xq = d.cands()
    wd = "一二三四五六日"[now.weekday()]
    check(f"星期{wd}" in xq[1:] and f"周{wd}" in xq[1:], f"xq {xq[:4]}，今天星期{wd}")
    return f"rq {rq[:4]}；sj {sj[:3]}；xq {xq[:3]}"


@case("PY-49", mutates=True)
def py49(d):
    now = device_now(d)
    iso = now.strftime("%Y-%m-%d")
    d.type("rq")
    d.cands()
    allc = d.all_cands()
    if iso not in allc:
        raise Blocked(f"rq 没有「{iso}」：{allc[:5]}")
    m = d.long_pick(iso)
    check("从自定义词组中删除" in m, f"长按「{iso}」的菜单 {[x for x in m if len(x) > 3]}")
    menu_tap(d, "从自定义词组中删除")
    d.clear()
    d.type("rq")
    d.cands()
    after = d.all_cands()
    check(iso not in after, f"删后 rq 还有「{iso}」：{after[:5]}")
    others = [w for w in after[1:6] if "年" in w]
    check(len(others) >= 2, f"另两条日期不在了：{after[:6]}")
    d.clear()
    rows = custom_phrases(d)
    # the built-in date phrases are rows like "rq,3=#$year-$month_m…"; the deleted one is off
    check(any(r.startswith("rq,") for r in rows), f"自定义短语里没有 rq：{rows[:12]}")
    d.scroll_top()
    n = None
    for _ in range(6):
        s = d.screen()
        n = next((k for k in s.app() if k.text.startswith("rq,") and "$year-$month" in k.text), None)
        if n or not d.scroll(s):
            break
    if not n:
        raise Fail(f"自定义短语里找不到这一条：{rows[:16]}")
    r = n.parent   # the row: [handle, checkbox, text, edit]
    sw = [k for k in r.walk() if k.cls in ("Switch", "CheckBox", "SwitchCompat", "SwitchMaterial")]
    check(sw and not sw[0].checked, f"这一条不是关闭的：{[k.text for k in r.walk() if k.text]}")
    d.tap(sw[0].c, 0.8)
    d.open_page("text")
    d.type("rq")
    d.cands()
    again = d.all_cands()
    check(iso in again, f"打开后 rq {again[:5]}")
    return f"删后 {after[:4]}；打开后 {again[:4]}"


# ---------------------------------------------------------------- B0-设置 cases

def general(d):
    d.open_settings()
    d.goto("通用输入")


@case("PY-12", base="B0-设置", mutates=True)
def py12(d):
    general(d)
    choose(d, "性能档位", "低档")
    d.open_page("text")
    d.type("womenyiqiqugongyuan")
    d.cands()
    d.space()
    t = d.field_text()
    check(t and len(t) >= 6 and all("\u4e00" <= ch <= "\u9fff" for ch in t), f"框里是「{t}」")
    return f"低档打出「{t}」"


case("PY-13", base="B0-设置", needs="skip:要实体键盘（redroid 不造虚拟键盘）")(lambda d: None)


@case("PY-19", base="B0-设置", mutates=True)
def py19(d):
    general(d)
    d.switch("纠正错拼和按偏的键", False)
    d.open_page("text")
    d.type("zhagn")
    c = d.cands()
    check(not has(c, "张") and not has(c, "章"), f"第一页 {c}")
    return f"第一页 {c[:5]}"


@case("PY-20", base="B0-设置", mutates=True)
def py20(d):
    general(d)
    d.tap_text("模糊音", after=1.0)
    d.tap_text("z ↔ zh", scroll=False)
    d.tap_text("确定", scroll=False, after=1.0)
    d.open_page("text")
    d.type("zongguo")
    c = d.cands()
    check(has(c, "中国"), f"第一页 {c}")
    return f"第一页 {c[:5]}"


@case("PY-23", base="B0-设置", mutates=True)
def py23(d):
    gear(d, "拼音")
    d.switch("中英混输", False)
    d.open_page("text")
    d.type("iphone")
    c = d.all_cands()
    check(not any("iPhone" in x for x in c), f"候选 {c[:8]}")
    return f"候选 {c[:5]}"


@case("PY-28", base="B0-设置", mutates=True)
def py28(d):
    general(d)
    d.switch("联想下一个词", False)
    d.open_page("text")
    d.type("jintian")
    d.cands(want="今天")
    d.space()
    s = d.screen()
    c = d.candidates(s)
    check(d.field_text(s) == "今天", f"框里「{d.field_text(s)}」")
    check(not c and d.toolbar(s), f"上屏后栏上是 {c[:4]}")
    return "上屏后是工具栏"


@case("PY-47", base="B0-设置", mutates=True)
def py47(d):
    general(d)
    d.switch("表情候选", False)
    d.open_page("text")
    d.type("haha")
    c = d.all_cands()
    emo = [x for x in c if re.match(r"^[\U0001F000-\U0001FFFF\u2600-\u27BF]", x)]
    check(not emo, f"候选里有表情 {emo}")
    return f"候选 {c[:5]}"


@case("PY-50", base="B0-设置", mutates=True, visual=True)
def py50(d):
    glide_on(d)
    k = d.keys(refresh=True)
    pts = [k[c] for c in "nihao"]
    # the trace: shot while the finger is still down on the last key
    glide_shot(d, pts, "划 n-i-h-a-o，抬手前")
    s = d.screen()
    p, c = d.preedit(s), d.candidates(s)
    check(p == "ni'hao", f"拼音行 `{p}`")
    check(first(c) == "你好" and len(c) > 1, f"候选 {c[:4]}")
    d.clear()
    d.glide("wo")
    d.glide("men")
    c2 = d.all_cands()
    check(has(c2, "我们"), f"划 wo、men 后候选 {c2[:6]}")
    return f"`{p}` {c[:3]}；wo+men {c2[:3]}"


@case("PY-51", base="B0-设置", mutates=True)
def py51(d):
    glide_on(d)
    d.glide("nihao")
    c = d.cands()
    if first(c) != "你好" or len(c) < 2:
        raise Blocked(f"划 nihao 后候选 {c[:4]}")
    other = next((x for x in c[1:] if first([x]) != "你好"), None)
    w = d.pick(c.index(other) + 1)
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    check(t == first([w]) and not p, f"点「{w}」后框里「{t}」，拼音行 `{p}`")
    return f"点「{w}」，框里「{t}」"


def _words13():
    """13-words.py's helpers (phrases)"""
    from uat import script
    return script("13-words")


@case("PY-52", base="B0-设置", mutates=True)
def py52(d):
    import re
    w = _words13()
    today = datetime.date.today()
    zhou, xingqi = "周" + "一二三四五六日"[today.weekday()], "星期" + "一二三四五六日"[today.weekday()]
    w.PIECES.setdefault("三家", [("sanjia", "三家")])
    w.add_phrase(d, "sj", [("cn", "三家")])

    def cands(code):
        d.type(code)
        c = w.words(d.all_cands())
        d.clear()
        return c

    # 周X taken out on its own, before the switch
    w.page_cands(d, "xq")
    menu = d.long_pick(zhou)
    if "从自定义词组中删除" not in menu:
        raise Blocked(f"长按「{zhou}」的菜单 {[x for x in menu if len(x) > 3][:6]}")
    menu_tap(d, "从自定义词组中删除")
    d.clear()
    d.hide_keyboard()
    general(d)
    d.switch("自带的日期时间", False)
    d.open_page("text")
    d.switch_im("拼音")
    c_rq, c_xq, c_sj = cands("rq"), cands("xq"), cands("sj")
    # the word-triggered ones (日期 then today's date) go with the same switch
    iso = d.sh("date +%Y-%m-%d").strip()
    c_ri = cands("riqi")
    # back on: 三家 deleted, the switch on
    d.hide_keyboard()
    home(d, "词库与短语", "自定义短语")
    s = d.screen()
    n = next((n for n in s.app() if "三家" in n.text), None)
    if n:
        d.swipe((800, n.c[1]), (100, n.c[1]), 300)
        time.sleep(3.0)
    d.hide_keyboard()
    general(d)
    d.switch("自带的日期时间", True)
    d.open_page("text")
    d.switch_im("拼音")
    c_rq2, c_xq2 = cands("rq"), cands("xq")
    is_date = lambda x: re.search(r"\d{4}", x) or "〇" in x   # noqa: E731
    check(not [x for x in c_rq if is_date(x)], f"关掉时 rq 有日期 {c_rq[:5]}")
    # 星期 itself is a dictionary word for xq: the built-in one is today's 星期X
    check(xingqi not in c_xq, f"关掉时 xq 有「{xingqi}」：{c_xq[:6]}")
    check(c_sj[:1] == ["三家"] and not [x for x in c_sj if re.search(r"\d:\d|\d时", x)], f"关掉时 sj {c_sj[:4]}")
    check("日期" in c_ri and iso not in c_ri, f"关掉时 riqi {c_ri[:6]}")
    check([x for x in c_rq2 if is_date(x)], f"打开后 rq {c_rq2[:5]}")
    check(xingqi in c_xq2, f"打开后 xq 没有「{xingqi}」：{c_xq2[:6]}")
    check(zhou not in c_xq2, f"打开后单独删掉的「{zhou}」又回来了：{c_xq2[:6]}")
    return f"关：rq {c_rq[:2]}，xq {c_xq[:2]}，sj {c_sj[:2]}，riqi {c_ri[:2]}；开：rq {c_rq2[:1]}，xq 有{xingqi}、没有{zhou}"



@case("PY-53", base="B0", mutates="learn")
def py53(d):
    iso = d.sh("date +%Y-%m-%d").strip()
    d.type("riqi")
    d.cands()
    c = d.all_cands()
    k = next((i for i, x in enumerate(c) if x == iso), None)
    check(c and c[0] == "日期", f"riqi 第一个 {c[:3]}")
    check(k is not None and k >= 2, f"riqi 候选 {c[:7]}")
    d.clear()
    d.type("rq")
    d.cands()
    c2 = d.all_cands()
    check(c2.count(iso) == 1, f"rq 里 {iso} 出现 {c2.count(iso)} 次：{c2[:7]}")
    return f"riqi {c[:k + 1]}；rq {c2[:4]}"
