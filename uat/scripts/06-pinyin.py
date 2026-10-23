"""uat/06-pinyin.md: the B0 cases. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import datetime
import re
import subprocess
import time

from uat import PKG, Blocked, Fail, case, check, choose, first, gear, glide_on, glide_shot, has, menu_tap, script


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


@case("PY-14")
def py14(d):
    seen = []
    for code, words in (("zhagn", ("张", "章")), ("zhon", ("中",)), ("jv", ("句",)), ("nihso", ("你好",))):
        d.step(code)
        seen.append(first_page_has(code, *words)(d))
        d.clear()
    return "；".join(seen)


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
    check("屏蔽" in m, f"菜单 {m[:10]}")
    menu_tap(d, "屏蔽")
    # the scope is written out, and the confirm is another row (docs/pinyin.md)
    texts = d.screen().texts(where="all")
    scope = next((t for t in texts if "不再出现「是」" in t), "")
    check(all(w in scope for w in ("打字", "手写", "语音", "联想")), f"确认框没写明范围：{texts[:12]}")
    menu_tap(d, "确定屏蔽")
    d.clear()
    d.type("shi")
    c = d.all_cands()
    check(c and not has(c, "是"), f"之后候选 {c[:8]}")
    return f"「{scope}」；屏蔽后 {c[:5]}"


def _chip(d, word):
    """the undo chip said of [word] and the bar it is not to cover, waited for a moment"""
    end = time.time() + 3
    while time.time() < end:
        s = d.screen()
        chip = s.find(contains=f"「{word}」", where="all")
        if chip and s.find(text="撤销", where="all"):
            return s, chip
        time.sleep(0.3)
    raise Fail(f"没有「{word}」的撤销提示")


def _block_shi(d):
    d.type("shi")
    d.cands(want="是")
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    return _chip(d, "是")


@case("PY-68", mutates="learn", clean=True)
def py68(d):
    d.step("屏蔽后的提示")
    s, chip = _block_shi(d)
    bar = s.find(rid="candidate_view", where="ime")
    tops = [n.b[1] for n in s.ime() if n.cls == "HorizontalScrollView"] + ([bar.b[1]] if bar else [])
    # the composing row's own text too: its node may sit outside the scroll view (the .md says 拼音行以上)
    pre = d.preedit(s).replace(" ", "").replace("'", "")
    tops += [n.b[1] for n in s.ime() if pre and n.text and n.text.replace(" ", "").replace("'", "").startswith(pre)]
    kb_top = min(tops) if tops else None
    check(kb_top is not None and chip.row().b[3] <= kb_top, f"提示 {chip.row().b} 盖着键盘（顶 {kb_top}）")
    check(len(d.candidates(s)) >= 2, f"打着字时候选 {d.candidates(s)[:4]}")
    d.step("撤销")
    d.tap(s.find(text="撤销", where="all").c, 0.8)
    d.clear()
    d.type("shi")
    back = d.all_cands()
    check(has(back, "是"), f"撤销后 shi {back[:6]}")
    d.clear()
    d.step("不撤销，接着打字")
    _block_shi(d)
    d.type("a")
    time.sleep(0.5)
    gone = "撤销" not in d.screen().texts(where="all")
    d.clear()
    check(gone, "点了 a 提示没收起")
    return f"撤销后 {back[:3]}；打字后收起"


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
    if "固定首位" not in m:
        raise Fail(f"菜单里没有固定首位：{m[:10]}")
    menu_tap(d, "固定首位")
    d.clear()
    return c, cc


@case("PY-31", mutates=True)
def py31(d):
    d.step("固定首位")
    c0, cc = pin_fifth(d)
    d.type("shi")
    c = d.cands()
    check(first(c) == cc, f"C=「{cc}」，固定后 {c[:4]}")
    # the rest as they were, a place down; the pin is the learning record's, not a custom phrase
    rest = [x for x in c0 if x != cc][:3]
    check(c[1:4] == rest, f"其余次序 {c0[:5]} → {c[:5]}")
    d.clear()
    # a pin looks at no context: first after 他 too
    d.type("ta")
    d.cands(want="他")
    d.pick("他")
    d.type("shi")
    c2 = d.cands()
    check(first(c2) == cc, f"「他」后 shi {c2[:4]}")
    d.clear()
    # while pinned: a pin written to the phrases and dropped again at 取消固定 would pass after it
    d.step("自定义短语")
    rows = custom_phrases(d)
    check(not any(r.startswith("shi,") and r.endswith("=" + cc) for r in rows), f"自定义短语里多了 {rows[:12]}")
    d.step("取消固定")
    d.open_page("text")
    d.type("shi")
    d.cands(want=cc)
    m = d.long_pick(1)
    check("取消固定" in m, f"菜单 {m[:10]}")
    menu_tap(d, "取消固定")
    d.clear()
    d.type("shi")
    c3 = d.cands()
    check(first(c3) == first(c0), f"取消固定后 {c3[:4]}（原来 {c0[:4]}）")
    d.clear()
    return f"C=「{cc}」 固定后排第一（「他」后也是），其余次序不变；取消后 {c3[:3]}；自定义短语没动"


@case("PY-32", mutates=True, clean=True)
def py32(d):
    c0 = None
    for i in range(3):
        d.type("ta")
        d.cands(want="他")
        d.pick("他")
        d.type("dl")
        c = d.cands()
        c0 = c0 or c
        if i < 2:
            d.pick("点了")
            d.clear()
    if first(c) != "点了":
        raise Blocked(f"前提没到：「他」后 dl 选了两次「点了」仍是 {c[:4]}")
    m = d.long_pick(1)
    check("忘掉学到的" in m, f"菜单 {m[:10]}")
    menu_tap(d, "忘掉学到的")
    # the pinyin alone backspaced away, 他 kept before it: what was learned was after 他, and in an
    # empty field 带来 is first whether the forget worked or not
    for _ in range(4):
        if not d.preedit():
            break
        d.bksp()
    if d.field_text() != "他":
        raise Blocked(f"退格后框里「{d.field_text()}」，不是「他」")
    d.type("dl")
    c2 = d.cands()
    check(first(c2) == "带来" and c2[1:2] != ["点了"], f"忘掉后 dl {c2[:4]}（原来 {c0[:4]}）")
    return f"忘掉后 {c2[:4]}"

@case("PY-33", clean=True, mutates="learn")
def py33(d):
    d.type("shi")
    c = d.cands()
    w = first(c[3:])
    items = ("忘掉学到的", "删除", "取消固定", "固定首位", "屏蔽", "为什么排在这")
    m = d.long_pick(4)
    menu = [x for x in m if x in items]
    d.back()   # closes the menu, the pinyin and the keyboard with it
    # never picked: nothing learned to forget
    check(not {"忘掉学到的", "删除", "取消固定"} & set(menu), f"长按「{w}」，菜单 {menu}")
    check({"固定首位", "屏蔽", "为什么排在这"} <= set(menu), f"长按「{w}」，菜单 {menu}")
    # picked once: now something is learned of it
    d.open_page("text")
    d.type("shi")
    d.cands()
    d.pick(w)
    d.clear()
    d.type("shi")
    d.cands()
    menu2 = [x for x in d.long_pick(w) if x in items]
    d.back()
    check("忘掉学到的" in menu2, f"选过以后长按「{w}」，菜单 {menu2}")
    return f"长按「{w}」，菜单 {menu}；选过以后 {menu2}"


# ---------------------------------------------------------------- 学词

SENTENCE, SENTENCE_PY, PIECES = "汪小明", "wangxiaoming", ("汪", "小明")


def learn_sentence(d, times=2):
    """PY-34's steps: 汪小明 picked piece by piece (the first is 王小明), twice:
    a correction made twice in the same place is first (U-02)"""
    c0 = None
    for _ in range(times):
        d.type(SENTENCE_PY)
        c = d.cands()
        c0 = c0 or c
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
    # wxm's own first before any learning. Both times the field is emptied and 。 typed first, so
    # the 前文 is the same and the last step compares against it (F3716, F3729)
    d.clear()
    d.type(SENTENCE_PY)
    d.cands()
    d.clear()
    d.press(".")
    d.type("wxm")
    w0 = d.cands()
    d.clear()
    c0 = learn_sentence(d, times=1)
    d.type(SENTENCE_PY)
    once = d.cands()
    d.clear()
    # once may be a slip: not first yet
    check(first(once) != SENTENCE and once[1:2] == [SENTENCE], f"改了一次后应排第二 {once[:4]}")
    learn_sentence(d, times=1)
    d.type(SENTENCE_PY)
    c = d.cands()
    check(first(c) == SENTENCE, f"改了两次后的候选 {c[:4]}（第一次 {c0[:2]}）")
    d.step("wxm")
    d.clear()
    d.press(".")
    d.type("wxm")
    c4 = d.cands()
    d.clear()
    # the initials keep a first of their own (not the learned word or its pieces); the word comes next
    check(c4[1:2] == [SENTENCE] and first(c4) == first(w0) and first(c4) not in (SENTENCE,) + PIECES,
          f"wxm {c4[:4]}（没学过时 {w0[:3]}）")
    return f"第一次 {c0[:2]}，改一次后 {once[:2]}，改两次后 {c[:3]}；wxm {c4[:3]}"


VULGAR = ("肏", "屌", "操你", "操逼", "操蛋", "草泥马", "肏你")


@case("PY-37")
def py37(d):
    d.type("cao")
    c = d.cands()
    bad = [x for x in c if first([x]) in VULGAR]
    check(not bad, f"第一页 {c}，有粗俗词 {bad}")
    return f"第一页 {c}"


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


@case("PY-45")
def py45(d):
    d.type("usb")
    a = d.cands()
    p = d.preedit()
    check(p == "usb" and any("usb" in x.lower() for x in a), f"usb：`{p}` {a[:4]}")
    return f"usb {a[:3]}"


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


@case("PY-19", base="B0-设置", mutates=True)
def py19(d):
    """one switch at a time: settings, then the test page"""
    seen = []
    d.step("关掉纠错")
    general(d)
    d.switch("纠正错拼和按偏的键", False)
    d.open_page("text")
    d.type("zhagn")
    c = d.cands()
    check(not has(c, "张") and not has(c, "章"), f"zhagn 第一页 {c}")
    seen.append(f"zhagn {c[:3]}")
    d.clear()
    d.step("关掉中英混输")
    d.hide_keyboard()
    gear(d, "拼音")
    d.switch("中英混输", False)
    d.open_page("text")
    d.type("iphone")
    c = d.all_cands()
    check(not any("iPhone" in x for x in c), f"iphone 候选 {c[:8]}")
    seen.append(f"iphone {c[:3]}")
    d.clear()
    d.step("关掉联想")
    d.hide_keyboard()
    general(d)
    d.switch("联想下一个词", False)
    d.open_page("text")
    d.type("jintian")
    d.cands(want="今天")
    d.space()
    d.expect_field("今天")
    s = d.screen()
    c = d.candidates(s)
    check(not c and d.toolbar(s), f"上屏后栏上是 {c[:4]}")
    seen.append("上屏后是工具栏")
    d.clear()
    d.step("关掉表情候选")
    d.hide_keyboard()
    general(d)
    d.switch("表情候选", False)
    d.open_page("text")
    d.type("haha")
    c = d.all_cands()
    emo = [x for x in c if re.match(r"^[\U0001F000-\U0001FFFF\u2600-\u27BF]", x)]
    check(not emo, f"haha 候选里有表情 {emo}")
    seen.append(f"haha {c[:3]}")
    d.clear()
    d.step("关掉拆字")
    d.hide_keyboard()
    gear(d, "拼音")
    d.switch("拆字", False)
    d.open_page("text")
    d.type("uniuniuniu")
    c = d.cands()
    check(not has(c, "犇"), f"uniuniuniu {c[:4]}")
    seen.append(f"uniuniuniu {c[:3]}")
    return "；".join(seen)


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
    d.clear()
    d.step("点另一种读法")
    d.glide("nihao")
    c3 = d.cands()
    other = next((x for x in c3[1:] if first([x]) != "你好"), None)
    if first(c3) != "你好" or not other:
        raise Fail(f"再划 nihao 后候选 {c3[:4]}")
    w = d.pick(c3.index(other) + 1)
    s = d.screen()
    t, p2 = d.field_text(s), d.preedit(s)
    check(t == first([w]) and not p2, f"点「{w}」后框里「{t}」，拼音行 `{p2}`")
    return f"`{p}` {c[:3]}；wo+men {c2[:3]}；点「{w}」，框里「{t}」"


@case("PY-52", base="B0-设置", mutates=True)
def py52(d):
    # the device's day, not the host's: redroid keeps UTC, a day ahead of a Pacific evening
    wd = int(d.sh("date +%u").strip()) - 1
    xingqi = "星期" + "一二三四五六日"[wd]

    def cands(code):
        d.type(code)
        d.cands()
        c = _w(d.all_cands())
        d.clear()
        return c

    d.step("关掉")
    general(d)
    d.switch("自带的日期时间", False)
    d.open_page("text")
    d.switch_im("拼音")
    c_rq, c_xq = cands("rq"), cands("xq")
    # the word-triggered ones (日期 then today's date) go with the same switch
    iso = d.sh("date +%Y-%m-%d").strip()
    c_ri = cands("riqi")
    is_date = lambda x: re.search(r"\d{4}", x) or "〇" in x   # noqa: E731
    check(not [x for x in c_rq if is_date(x)], f"关掉时 rq 有日期 {c_rq[:5]}")
    # 星期 itself is a dictionary word for xq: the built-in one is today's 星期X
    check(xingqi not in c_xq, f"关掉时 xq 有「{xingqi}」：{c_xq[:6]}")
    check("日期" in c_ri and iso not in c_ri, f"关掉时 riqi {c_ri[:6]}")
    d.step("打开")
    d.hide_keyboard()
    general(d)
    d.switch("自带的日期时间", True)
    d.open_page("text")
    d.switch_im("拼音")
    c_rq2 = cands("rq")
    check([x for x in c_rq2 if is_date(x)], f"打开后 rq {c_rq2[:5]}")
    return f"关：rq {c_rq[:2]}，xq {c_xq[:2]}，riqi {c_ri[:2]}；开：rq {c_rq2[:2]}"


@case("PY-64", mutates="learn", clean=True)
def py64(d):
    def ta():
        d.type("ta")
        d.cands(want="他")
        d.pick("他")
    ta()
    d.type("dl")
    d.cands(want="带领")
    d.pick("带领")
    d.type("ma")
    d.cands()
    d.space()
    if d.field_text() != "他带领吗":
        raise Blocked(f"框里「{d.field_text()}」，不是「他带领吗」")
    d.bksp(3)
    d.type("dianlema")
    d.cands(want="点了吗")
    d.pick("点了吗")
    d.press(".")
    ta()
    d.type("dl")
    c1 = d.cands()
    d.pick("点了")
    d.press(".")
    ta()
    d.type("dl")
    c2 = d.cands()
    d.clear()
    i, j = (c1.index(w) if w in c1 else 99 for w in ("点了", "带领"))
    check(first(c1) == "带来" and i < j, f"第一次「他 dl」{c1[:5]}")
    check(first(c2) == "点了", f"再选一次后「他 dl」{c2[:4]}")
    return f"{c1[:4]} → {c2[:3]}"


# ---------------------------------------------------------------- R11 learning (context, runs, pins)

def _w(c):
    return [x.split(" ")[0] for x in c]


def _ta(d):
    d.type("ta")
    d.cands(want="他")
    d.pick("他")


def _ta_dl(d, pick=None):
    """他 then dl: the candidates; pick one of them (or None)"""
    _ta(d)
    d.type("dl")
    c = _w(d.cands(want=pick) if pick else d.cands())
    if pick:
        d.pick(pick)
    return c


def _dailai_used(d):
    """PY-75's 前提: 带来 committed three times by space"""
    for _ in range(3):
        d.type("dl")
        c = _w(d.cands())
        if first(c) != "带来":
            raise Blocked(f"dl 的第一个不是带来：{c[:4]}")
        d.space()
        d.press(".")


@case("PY-54", mutates="learn", clean=True)
def py54(d):
    c1 = _ta_dl(d, "点了")
    c2 = _ta_dl(d, "点了")
    c3 = _ta_dl(d)
    d.clear()
    check(first(c1) == "带来", f"第一次 {c1[:3]}")
    check(c2[:2] == ["带来", "点了"], f"选一次后 {c2[:3]}")
    check(c3[:2] == ["点了", "带来"], f"连着选两次后 {c3[:3]}")
    return f"{c1[:2]} → {c2[:2]} → {c3[:2]}"


def _why(d, word):
    """long-press word, 为什么排在这: the reason line under it"""
    m = d.long_pick(word)
    if "为什么排在这" not in m:
        raise Fail(f"长按「{word}」的菜单没有为什么排在这：{m[:10]}")
    menu_tap(d, "为什么排在这")
    return d.screen().texts(where="all")


@case("PY-69", mutates="learn", clean=True)
def py69(d):
    d.step("前提：他 dl 连选两次")
    _ta_dl(d, "点了")
    _ta_dl(d, "点了")
    c = _ta_dl(d)
    if c[:2] != ["点了", "带来"]:
        raise Blocked(f"前提没到：「他 dl」{c[:3]}")
    d.step("为什么：点了")
    m1 = _why(d, "点了")
    check("在这个前文后面连着选过两次" in m1, f"「点了」的原因 {m1[:10]}")
    d.step("为什么：带来")
    # a touch on the keyboard outside the menu: the keyboard takes it and only closes the menu
    # (BaseInputView.dispatchTouchEvent). Not the page above the keyboard, which the input method
    # does not take (onComputeInsets): there the app has it, and a web page's blank blurs the field
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    # the menu's own popup window only: the keys' labels are on screen too (F3715)
    menu = [n for n in s.nodes if n.wtype not in (None, "2") and n.pkg == PKG and n.b[3] > n.b[1]]
    inside = lambda p: any(n.b[0] <= p[0] <= n.b[2] and n.b[1] <= p[1] <= n.b[3] for n in menu)
    spots = [(kb.b[0] + 30, kb.b[3] - 30), (kb.b[2] - 30, kb.b[3] - 30)] if kb else []
    spot = next((p for p in spots if not inside(p)), None)
    if not spot:
        raise Blocked(f"键盘 {kb.b if kb else None} 上没有菜单外可点的角")
    d.tap(spot, 0.8)
    if not has(d.candidates(), "带来"):
        raise Blocked(f"点空白处后候选没了：{d.candidates()[:4]}")
    m2 = _why(d, "带来")
    r2 = next((x for x in m2 if re.fullmatch(r"最近在这串键下用过 \d+ 次", x) or x == "词库自带的顺序"), None)
    check(r2, f"「带来」的原因 {m2[:10]}")
    d.step("复制给开发者")
    menu_tap(d, "复制给开发者")
    d.clear()
    d.open_page("long")
    tb = script("05-toolbar")
    # the whole field replaced by the paste: what is in it is the report alone
    tb.edit_panel(d)
    tb.ime(d, desc="全选", after=0.4)
    tb.ime(d, desc="粘贴", after=0.8)
    rep = d.field_text() or ""
    check("dl" in rep and "点了" in rep and "带来" in rep and re.search(r"\d", rep), f"复制出的「{rep[:80]}」")
    check("他" not in rep, f"复制出的有前文「他」：「{rep[:80]}」")
    return f"点了：{m1[0] if m1 else ''}…；带来：{r2}；复制 {len(rep)} 字"


def _paste_end(d):
    """the system paste at the end of the focused field (a long press on its empty right part)"""
    f = d.field()
    if not f:
        raise Blocked("没有获得焦点的输入框，粘贴不了")
    # the cursor to the end first: a long press on a word selects it and the paste would replace it
    d.tap((f.b[2] - 40, f.c[1]), 0.4)
    d.hold((f.b[2] - 40, f.c[1]), 900)
    time.sleep(0.6)
    if not d.screen().find(text="粘贴", where="all"):
        raise Blocked("长按框的右端没有出「粘贴」")
    menu_tap(d, "粘贴")
    time.sleep(0.5)


@case("PY-73", mutates="learn", clean=True)
def py73(d):
    d.step("前提：复制「他」")
    tb = script("05-toolbar")
    d.open_page("sentence")
    _ta(d)
    d.expect_field("他", msg="「句子」框里是「{got}」")
    tb.edit_panel(d)
    tb.ime(d, desc="全选", after=0.4)
    tb.ime(d, desc="复制", after=0.6)
    tb.back_to_keys(d)
    d.open_page("text")
    want = ""
    for i in range(3):
        d.step(f"粘贴「他」第 {i + 1} 次")
        _paste_end(d)
        want += "他"
        d.expect_field(want, msg=f"粘贴后框里是「{{got}}」，不是「{want}」")
        d.type("dl")
        if i < 2:
            d.cands(want="点了")
            d.pick("点了")
            want += "点了"
    c = _w(d.cands())
    d.clear()
    check(first(c) == "点了", f"粘贴的「他」后 dl {c[:3]}")
    return f"「他 dl」{c[:3]}"


@case("PY-72", mutates=True, clean=True)
def py72(d):
    from pypinyin import lazy_pinyin
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    time.sleep(0.5)
    pred = _w(d.candidates())
    b = next((x for x in pred if len(x) >= 2 and all("一" <= ch <= "鿿" for ch in x)), None)
    if not b:
        raise Blocked(f"「你好」的联想 {pred[:6]}")
    d.long_pick(b)
    menu_tap(d, "不再联想")
    d.press(".")
    d.type("nihao")
    d.cands(want="你好")
    d.space()
    time.sleep(0.5)
    pred2 = _w(d.candidates())
    d.press(".")
    d.type("".join(lazy_pinyin(b)))
    c = _w(d.all_cands())
    d.clear()
    w = script("13-words")
    w.user_lexicon(d, "屏蔽的词")
    top = d.all_texts()
    d.tap_text("不再联想的搭配", contains=True, after=1.2)
    rows = w.list_texts(d)
    check(b not in pred2, f"B=「{b}」，之后联想 {pred2[:6]}")
    check(b in c, f"{lazy_pinyin(b)} 的候选 {c[:8]}")
    check(any("不再联想的搭配" in t and "1 个" in t for t in top), f"屏蔽的词页 {top[:6]}")
    # the word, then 「不再跟在「…」后面联想」 under it: the word before is the model's last word
    # either way the model cut 你好 is right: kept whole (你好 → B) or as 你|好 (好 → B)
    check(any(r == b and i + 1 < len(rows) and ("「好」" in rows[i + 1] or "「你好」" in rows[i + 1])
              for i, r in enumerate(rows)), f"搭配 {rows[:6]}")
    return f"B=「{b}」；联想 {pred2[:3]}；{[r for r in rows if b in r][:1]}"


def _chat_send(d):
    d.enter()
    time.sleep(0.4)


def _chat_learn(d):
    """PY-75's steps in the chat box: 他 sent, dl → 点了 sent, twice"""
    for _ in range(2):
        _ta(d)
        _chat_send(d)
        d.type("dl")
        d.cands(want="点了")
        d.pick("点了")
        _chat_send(d)
    d.open_page("text")
    c = _ta_dl(d)
    d.clear()
    check(first(c) == "带来", f"「普通文本」他 dl {c[:3]}")
    return f"他 dl {c[:3]}"


@case("PY-75", page="chat", mutates="learn", clean=True)
def py75(d):
    _dailai_used(d)
    return _chat_learn(d)
