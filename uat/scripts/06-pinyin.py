"""uat/06-pinyin.md: the B0 cases. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import subprocess
import time

from uat import (Blocked, Fail, case, check, first, flick, glide_on, glide_shot, has, home, grouped_keys,
                 has_keyboard_code, menu_tap, space_share,
                 pinyin_keyboard, popup, popup_away, popup_slide, script)


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


case("PY-03", needs="jvm:PinyinUatTest.theFirstHalfPickedTheRestStillOffered")(lambda d: None)


case("PY-04", needs="jvm:PinyinUatTest.anotherCandidateCommitsThatOne")(lambda d: None)


case("PY-05", needs="jvm:PinyinUatTest.backspaceDeletesTheLastLetter")(lambda d: None)


case("PY-06", needs="jvm:PinyinUatTest.anApostropheSeparatesSyllables")(lambda d: None)


case("PY-07", needs="jvm:PinyinUatTest.initials")(lambda d: None)


case("PY-10", needs="jvm:PinyinUatTest.theWordsBeforeChooseTheWord")(lambda d: None)


case("PY-11", needs="jvm:PinyinUatTest.aChatsBoxSentStillGivesTheWordsBefore")(lambda d: None)


# ---------------------------------------------------------------- 纠错

case("PY-14", needs="jvm:PinyinUatTest.pinyinTypedWrongStillGivesTheWord")(lambda d: None)


case("PY-18", needs="jvm:PinyinUatTest.aWordOftenMisreadShowsItsReading")(lambda d: None)


# ---------------------------------------------------------------- 模糊音、中英混输、笔画

case("PY-21", needs="jvm:PinyinUatTest.fuzzyOffByDefault")(lambda d: None)


case("PY-22", needs="jvm:PinyinUatTest.chineseAndEnglishMixed")(lambda d: None)


case("PY-24", needs="jvm:PinyinUatTest.uThenStrokes")(lambda d: None)


# ---------------------------------------------------------------- 联想

case("PY-26", needs="jvm:PinyinUatTest.wordsThatMayFollowAfterACommit")(lambda d: None)


case("PY-27", needs="jvm:PinyinUatTest.typingPutsTheWordsThatMayFollowAway")(lambda d: None)


# ---------------------------------------------------------------- 长按候选

case("PY-29", needs="jvm:CandidateMenuUatTest.blockingAWordSaysWhereAndAsksFirst")(lambda d: None)


case("PY-68", needs="jvm:CandidateMenuUatTest.aBlockIsTakenBackOnTheSpot")(lambda d: None)


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


case("PY-31", needs="jvm:PinyinUatTest.pinnedFirstThenUnpinned")(lambda d: None)


case("PY-32", needs="jvm:PinyinUatTest.forgettingWhatWasLearned")(lambda d: None)

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


case("PY-34", needs="jvm:PinyinUatTest.aNamePickedPieceByPieceTwiceIsAWordAndInItsInitials")(lambda d: None)


case("PY-37", needs="jvm:PinyinUatTest.vulgarWordsAreNotOnTheFirstPage")(lambda d: None)


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
    row = d.code_row()
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

case("PY-44", needs="jvm:PinyinUatTest.chaiziByTheParts")(lambda d: None)


case("PY-45", needs="jvm:PinyinUatTest.notPartsArePinyinAsEver")(lambda d: None)


case("PY-46", needs="jvm:PinyinUatTest.anEmojiCandidateIsNotLearnedFirst")(lambda d: None)


case("PY-48", needs="jvm:PinyinUatTest.theDateTimeAndWeekdayBuiltIn")(lambda d: None)


case("PY-49", needs="jvm:PinyinUatTest.aBuiltInPhraseDeletedIsOneTurnedOffInTheCustomPhrases")(lambda d: None)


# ---------------------------------------------------------------- B0-设置 cases


case("PY-12", needs="jvm:PinyinUatTest.theLowLevelStillTypesASentence")(lambda d: None)


case("PY-19", needs="jvm:PinyinUatTest.eachSwitchTurnedOffTakesItsCandidates")(lambda d: None)


case("PY-20", needs="jvm:PinyinUatTest.fuzzyZZh")(lambda d: None)


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


case("PY-52", needs="jvm:PinyinUatTest.theBuiltInDatesTurnedOff")(lambda d: None)


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


case("PY-54", needs="jvm:PinyinUatTest.afterTheSameWordsPickedOnceIsSecondTwiceFirst")(lambda d: None)


case("PY-69", needs="jvm:CandidateMenuUatTest.whyItIsThereAndTheReportCopied")(lambda d: None)


case("PY-73", needs="jvm:PinyinUatTest.textAlreadyInTheFieldIsTheWordsBefore")(lambda d: None)


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


case("PY-09", needs="jvm:PinyinUatTest.aWordTypedTwiceInFullGoesThirdUnderItsInitials、aWordTypedTwiceInFullGoesThirdUnderPartsOfItToo、aWordUsedUnderTheInitialsStaysFirstThere")(lambda d: None)


case("PY-09b", needs="jvm:PinyinUatTest.aWordCorrectedToUnderTheInitialsIsNoLowerThanSecondInFullTillItExpires")(lambda d: None)


case("PY-09c", needs="jvm:PinyinUatTest.aPinGoesFirstAndTheLiftedWordAPlaceDown")(lambda d: None)


case("PY-09d", needs="jvm:PinyinUatTest.whyItIsHereTellsTheLiftAndTheFloor")(lambda d: None)


case("PY-77b", needs="jvm:PinyinUatTest.aWordGlidedTwiceGoesThirdUnderItsInitials")(lambda d: None)


# ---------------------------------------------------------------- 14 键 (B0-14键)

# the keyboards' codes (uat.KEYBOARD_CODES) never in the user's text; nor any character of a private
# use area (a tap's place, Keyboard.TAPS; a syllable picked; INK)
def no_codes(t):
    return t is not None and not has_keyboard_code(t)


@case("PY-84", base="B0-14键", visual=True)
def py84(d):
    k = d.keys(refresh=True)
    rows = ["qw er ty ui op", "as df gh jk l", "zx cv bn m"]
    miss = [g for r in rows for g in r.split() if g not in k]
    check(not miss, f"没有键 {miss}")
    ys = [sorted({k[g][1] for g in r.split() if g in k}) for r in rows]
    check(all(len(y) == 1 for y in ys) and ys[0][0] < ys[1][0] < ys[2][0], f"三行的 y {ys}")
    corners = {g: [t for t in (x.text for x in d.key_node(g).walk()) if t and t.lower() != g]
               for r in rows for g in r.split() if g in k}
    # in Chinese ～ ！ ？ as the full keyboard's corners are (its punctuation, @ has none); ~ ! ? in English
    want = dict(zip("qw er ty ui op as df gh jk l zx cv bn m".split(), "1 2 3 4 5 6 7 8 9 0 ～ ！ @ ？".split()))
    bad = {g: c for g, c in corners.items() if want[g] not in c}
    check(not bad, f"角标不对 {bad}")
    s = d.screen()
    check(s.find(desc="分词", where="ime"), "没有分词键")
    check("shift" not in k, "有 ⇧")
    for name in ("符", "123", ",", "space", ".", "globe", "enter"):
        check(name in k or s.find(text=name, where="ime"), f"底排没有 {name}")
    check("中/英" not in k, "底排有中/英")
    d.shot("kb", "14 键：三行满宽，键面大写，角标 1–0 和 ～ ！ @ ？；底排 符 123 ， 空格 。 🌐 ↵，没有中/英")
    return f"三行 {ys}，角标对，底排齐"


@case("PY-85", base="B0-14键", mutates="learn", clean=True)
def py85(d):
    grouped_keys(d, "bn ui gh as op")
    c = d.cands()
    # 你好 on the first page, not first: the model has 不好 and 不高 above it on the same keys
    # (uat/06-pinyin.md's note); picked, it is what the field gets
    check("你好" in c, f"候选 {c[:6]} 里没有「你好」")
    d.pick("你好")
    t = d.field_text()
    check(t == "你好", f"候选 {c[:4]}，框里是「{t}」")
    return f"候选 {c[:4]}；框里是「{t}」"


@case("PY-86", base="B0-14键", mutates="learn", visual=True)
def py86(d):
    d.step("没打字")
    check(not d.syllables(), f"没打字时有音节条 {d.syllables()}")
    d.step("点 BN UI")
    grouped_keys(d, "bn ui")
    d.cands()
    s = d.screen()
    sy = d.syllables(s)
    check("ni" in sy and "bu" in sy, f"音节条 {sy}")
    strip = s.find(rid="code_row_syllables", where="ime")
    row = d.code_row(s)
    # those the strip shows whole: the last may be cut by its edge, the rest scrolled to
    btns = [n for n in strip.walk() if n.text and strip.b[0] <= n.b[0] and n.b[2] < strip.b[2]] if strip else []
    small = [n.text for n in btns if (n.b[2] - n.b[0]) / d.dpx() < 39.5 or abs(n.b[3] - n.b[1] - (row.b[3] - row.b[1])) > 3]
    check(btns and not small, f"小于 40dp 或不和编码行一样高的按钮 {small}")
    p0 = d.preedit(s)
    d.step("点 ni")
    d.syllable_tap("ni")
    s = d.screen()
    p1, c1 = d.preedit(s), d.candidates(s)
    check(p1.replace("|", "").startswith("ni"), f"点 ni 后预编辑 `{p1}`")
    d.step("退格")
    d.bksp()
    d.cands()
    s = d.screen()
    p2, sy2 = d.preedit(s), d.syllables(s)
    check(p2 == p0 and "ni" in sy2, f"退格后预编辑 `{p2}`（之前 `{p0}`），音节条 {sy2}")
    d.step("读音多的开头")
    d.clear()
    # zhuang, zhuan, zhua, zhu, zh, z, xu, …: more syllables than 60% of the row holds
    grouped_keys(d, "zx gh ui as bn gh")
    d.cands()
    s = d.screen()
    strip, text = s.find(rid="code_row_syllables", where="ime"), d.code_row(s)
    if not strip or not text:
        raise Blocked(f"没有音节条或预编辑：{strip} {text}")
    whole = strip.b[2] - text.b[0]
    share = (strip.b[2] - strip.b[0]) / whole if whole > 0 else 1
    # held at the cap (not short of it): this start has more syllables than 60% of the row
    check(0.55 <= share <= 0.61, f"音节条占编码行 {share:.0%}")
    d.step("一长串")
    # more than the text part holds (40% of the row): zhuang bu hao kuo still fits, so twice nihao more
    grouped_keys(d, "bn ui gh as op jk ui op bn ui gh as op bn ui gh as op")
    d.cands()
    spans, _ = d.preedit_glyphs()
    text = d.code_row()
    # scrolled to the cursor: ink from the left edge on (a glyph cut there, or the gap between two
    # readings: under 6dp, where an unscrolled row starts after its 8dp padding), the last near the
    # text part's right edge, inside that end padding (PreeditComponent) and the glyph's side bearing
    check(spans and spans[0][0] - text.b[0] < 6 * d.dpx(), f"预编辑没有滚动：最左的字 {spans[:1]}，编码行 {text.b}")
    check(text.b[2] - spans[-1][1] <= 12 * d.dpx(), f"预编辑最右的字 {spans[-1:]} 离 {text.b} 的右沿太远")
    d.shot("kb", "一长串时预编辑仍滚到光标：看得见最后打的读音")
    return f"{sy[:4]}；ni → `{p1}` {c1[:2]}；退格 `{p2}`；音节条占 {share:.0%}"


@case("PY-87", base="B0-14键", mutates="learn")
def py87(d):
    grouped_keys(d, "'")
    t0 = d.field_text()
    d.clear()
    grouped_keys(d, "zx ui ' as bn")
    c = d.cands()
    p = d.preedit()
    d.space()
    t = d.field_text()
    # the full keyboard's ' (Z's corner) in Chinese: its punctuation, the pair
    check(t0 == "‘’", f"没打字时点分词键，框里「{t0}」")
    check(p.replace(" ", "'").replace("|", "") == "xi'an", f"预编辑 `{p}`")
    check(first(c) == "西安" and t == "西安", f"候选 {c[:3]}，框里「{t}」")
    return f"「{t0}」；`{p}` → 「{t}」"


@case("PY-88", base="B0-14键", mutates="learn")
def py88(d):
    flick(d, "qw")
    t1 = d.field_text()
    flick(d, "bn")
    t2 = d.field_text()
    d.clear()
    grouped_keys(d, "bn ui")
    c = d.cands()
    flick(d, "er")
    t3 = d.field_text()
    check(t1 == "1" and t2 == "1@", f"滑 QW、BN 后框里「{t1}」「{t2}」")
    check(len(c) > 1 and t3 == c[1], f"打字中滑 ER：候选 {c[:3]}，框里「{t3}」")
    return f"「{t2}」；第 2 个「{t3}」"


@case("PY-89", base="B0-14键", mutates="learn")
def py89(d):
    items, xy = popup(d, "qw")
    bottom = max(n.b[3] for n in items) if items else 0
    row = [n.text for n in sorted(items, key=lambda n: n.b[0]) if abs(n.b[3] - bottom) < 10]
    above = [n.text for n in items if abs(n.b[3] - bottom) >= 10]
    popup_away(d, xy)
    check(row == ["Q", "W", "1", "q", "w"], f"最下面一行 {row}")
    # q and w long-press to their digit and capital alone on the full keyboard: nothing above
    check(not above, f"QW 上面几行 {above}")
    items, xy = popup(d, "as")
    bottom = max(n.b[3] for n in items) if items else 0
    row2 = [n.text for n in sorted(items, key=lambda n: n.b[0]) if abs(n.b[3] - bottom) < 10]
    above = [n.text for n in items if abs(n.b[3] - bottom) >= 10]
    popup_away(d, xy)
    check(row2 == ["A", "S", "6", "a", "s"], f"AS 最下面一行 {row2}")
    check("@" in above and "*" in above and not any(t.isalnum() and t.isascii() for t in above), f"AS 上面几行 {above}")
    grouped_keys(d, "bn ui")
    c = d.cands()
    items, xy = popup(d, "qw")
    tgt = next((n for n in items if n.text == "q"), None)
    if not tgt:
        popup_away(d, xy)
        raise Blocked(f"长按 QW 没有 q：{[n.text for n in items]}")
    # the finger starts on the corner, 1, not on the letter nearest it (popup_focus's guess)
    popup_slide(d, xy, tgt, items=items, key="1")
    t = d.field_text()
    check(c and t == first(c) + "q", f"候选 {c[:3]}，框里「{t}」")
    return f"{row}；框里「{t}」"


WXM = "qw as bn gh zx ui as op m ui bn gh"   # wangxiaoming on the 14 keys


@case("PY-90", base="B0-14键", mutates=True, clean=True)
def py90(d):
    glide_on(d)   # for the first glide on the full keyboard after the 14 keys' commits (step 3)
    d.step("14 键上学 shi")
    grouped_keys(d, "as gh ui")
    c0 = d.cands()
    if len(c0) < 2:
        raise Blocked(f"AS GH UI 只有 {c0}")
    w = first(c0[1:])
    for _ in range(2):
        d.clear()
        grouped_keys(d, "as gh ui")
        c = d.cands()
        if w not in c:
            raise Blocked(f"候选里没有「{w}」：{c[:6]}")
        d.pick(c.index(w) + 1)
        d.press(".")
    d.clear()
    grouped_keys(d, "as gh ui")
    c3 = d.cands()
    d.clear()
    d.step("14 键上一段段选出汪小明")
    for _ in range(2):
        grouped_keys(d, WXM)
        for piece in PIECES:
            d.cands()
            d.pick(piece)
        time.sleep(0.5)
        t = d.field_text()
        if t != SENTENCE:
            raise Blocked(f"一段段选完框里是「{t}」")
        d.clear()
    d.step("全键盘")
    d.hide_keyboard()
    pinyin_keyboard(d, "全键盘")
    d.open_page("text")
    # the engine still holds the 14 keys' pair after their commits: the first glide must be full pinyin
    d.glide("nihao")
    g = d.cands()
    d.clear()
    d.type("shi")
    c4 = d.cands()
    d.clear()
    d.type("wxm")
    c5 = d.cands()
    d.clear()
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "自动学到的词")
    t = d.all_texts()
    check(first(c3) == w, f"选两次「{w}」后 14 键的候选 {c3[:3]}")
    check(has(g[:5], "你好"), f"换全键盘后第一次划 n-i-h-a-o，候选 {g[:5]}")
    check(first(c4) != w, f"全键盘 shi 的首选 {c4[:3]}（14 键上学的「{w}」不该串过来）")
    check(has(c5, SENTENCE), f"全键盘 wxm 的第一页 {c5[:8]}")
    check(SENTENCE in t, f"「{SENTENCE}」不在自动学到的词里：{t[:12]}")
    return f"「{w}」：14 键 {c3[:2]}，全键盘 {c4[:2]}；wxm {c5[:3]}"


@case("PY-91", base="B0-14键", visual=True)
def py91(d):
    d.step("底排")
    k = d.keys(refresh=True)
    check("qw" in k and "中/英" not in k, f"14 键底排有中/英，或不是 14 键：{sorted(k)[:12]}")
    d.step("🌐 到英文")
    d.press("globe", after=0.8)
    lab = d.im_label()
    k = d.keys(refresh=True)
    # in B0 English is the one after 拼音
    check("英文" in lab, f"🌐 后是「{lab}」，应是拼音后面的英文")
    check("q" in k and "qw" not in k, f"🌐 后不是英文全键盘：{sorted(k)[:12]}")
    check("中/英" not in k, "英文全键盘上有中/英")
    d.shot("kben", "英文全键盘，底排没有中/英")
    d.step("🌐 一直换回拼音")
    for _ in range(8):
        if "拼音" in d.im_label():
            break
        d.press("globe", after=0.8)
    check("qw" in d.keys(refresh=True), f"回到「{d.im_label()}」时不是 14 键")
    d.step("密码框")
    d.open_page("password")
    k = d.keys(refresh=True)
    check("q" in k and "qw" not in k, f"密码框不是英文全键盘：{sorted(k)[:12]}")
    d.shot("kb", "密码框：英文全键盘")
    return "没有中/英；🌐 到英文、再回到 14 键；密码框全键盘"


@case("PY-92", base="B0-14键", mutates="learn")
def py92(d):
    grouped_keys(d, "ui")
    d.enter()
    t1 = d.field_text()
    d.clear()
    grouped_keys(d, "bn ui gh")
    d.cands()
    d.press("globe", after=0.8)
    t2 = d.field_text()
    check(t1 == "u", f"UI 回车后框里「{t1}」")
    check(t2 and t2.isascii() and t2.replace("'", "").isalpha() and no_codes(t2), f"🌐 后框里「{t2}」")
    return f"「{t1}」「{t2}」"


@case("PY-93", base="B0-14键", page="url")
def py93(d):
    w = space_share(d)
    grouped_keys(d, "bn ui")
    d.press(".com", after=0.6)
    t = d.field_text()
    d.open_page("email")
    grouped_keys(d, "bn ui")
    d.press("@", after=0.6)
    t2 = d.field_text()
    w2 = space_share(d)
    check("中/英" not in d.keys(refresh=True), "邮箱框的 14 键有中/英")
    check(t and t.endswith(".com") and no_codes(t) and t[:-4].isalpha(), f"网址框里「{t}」")
    check(t2 and t2.endswith("@") and no_codes(t2) and t2[:-1].isalpha(), f"邮箱框里「{t2}」")
    check(w >= 0.15 and w2 >= 0.15, f"空格占键盘宽：网址框 {w:.2f}，邮箱框 {w2:.2f}，应 ≥ 0.15")
    return f"「{t}」「{t2}」，空格 {w:.2f} / {w2:.2f}"


@case("PY-94", base="B0-14键", mutates=True)
def py94(d):
    grouped_keys(d, "as gh ui")
    c = d.cands()
    if len(c) < 5:
        raise Blocked(f"AS GH UI 只有 {c}")
    m = d.long_pick(5)
    if "固定首位" not in m:
        raise Fail(f"菜单里没有固定首位：{m[:10]}")
    menu_tap(d, "固定首位")
    d.clear()
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "固定的词")
    t = d.all_texts()
    row = next((x for x in t if "as gh ui" in x), None)
    check(row and "14 键" in row, f"固定的词 {t[:10]}")
    check(all(no_codes(x) for x in t), f"有码字符 {t[:10]}")
    return f"「{row}」"


@case("PY-95", base="B0-14键", mutates="learn")
def py95(d):
    grouped_keys(d, "er qw")
    c1 = d.cands()
    d.clear()
    grouped_keys(d, "er ui qw ui")
    c2 = d.all_cands()
    if "日期" not in c2:
        raise Blocked(f"riqi 的候选里没有「日期」：{c2[:8]}")
    # the dates follow the word among its candidates, from the third on (PY-48, docs/words.md)
    at = c2.index("日期")
    year = time.strftime("%Y")
    dates = [x for x in c2[at + 1:at + 6] if year in x]
    d.pick("日期")
    t = d.field_text()
    check(not any(year in x for x in c1), f"rq 出了日期 {c1[:4]}")
    check(dates and c2.index(dates[0]) >= 2, f"riqi 的候选 {c2[:6]}")
    check(t == "日期", f"选「日期」后框里「{t}」")
    return f"rq {c1[:3]}；riqi {c2[:4]}"


@case("PY-96", base="B0-14键")
def py96(d):
    s = d.screen()
    qw = d.key_node("qw", s)
    sep = s.find(desc="分词", where="ime")
    grouped_keys(d, "bn ui")
    d.cands()
    s = d.screen()
    strip = s.find(rid="code_row_syllables", where="ime")
    ni = next((n for n in strip.walk() if n.text == "ni"), None) if strip else None
    check(qw and qw.desc == "Q W，1", f"QW 的说明「{qw and qw.desc}」")
    check(sep is not None, "分词键没有「分词」的说明")
    check(ni and (ni.desc == "拼音 ni" or ni.row().desc == "拼音 ni"), f"ni 的说明「{ni and ni.desc}」")
    check(ni and "Button" in (ni.cls + ni.row().cls), f"ni 不是按钮：{ni and ni.cls}")
    return "QW、分词、ni 的说明都对"


@case("PY-97", base="B0-14键", mutates="learn")
def py97(d):
    grouped_keys(d, "bn ui gh as op")
    d.cands()
    p0 = d.preedit()
    d.step("先 ni 再 hao")
    d.syllable_tap("ni")
    d.syllable_tap("hao")
    p1 = d.preedit()
    d.step("退格两次")
    d.bksp()
    d.cands()
    p2, sy2 = d.preedit(), d.syllables()
    d.bksp()
    d.cands()
    p3, sy3 = d.preedit(), d.syllables()
    check("hao" in sy2 and "ni" not in sy2, f"第一次退格后 `{p2}` {sy2}")
    check("ni" in sy3 and p3 == p0, f"第二次退格后 `{p3}`（一开始 `{p0}`）{sy3}")
    d.step("点预编辑")
    spans, y = d.preedit_glyphs()
    if len(spans) < 3:
        raise Blocked(f"预编辑字形 {spans}")
    before = d.syllables()
    d.tap(((spans[-2][0] + spans[-2][1]) // 2, y), 0.6)
    s = d.screen()
    p4, sy4 = d.preedit(s), d.syllables(s)
    check(sy4 == before and "|" in p4, f"点预编辑后 `{p4}` {sy4}（之前 {before}）")
    d.step("光标在中间退格")
    d.bksp()
    d.cands()
    p5 = d.preedit()

    def bare(p):
        return p.replace("|", "").replace(" ", "").replace("'", "")
    check(len(bare(p5)) < len(bare(p4)) and "|" in p5, f"光标在中间退格后 `{p5}`（之前 `{p4}`）")
    return f"`{p1}` → `{p2}` → `{p3}`；`{p4}` → `{p5}`"


@case("PY-98", base="B0-14键", mutates=True, visual=True)
def py98(d):
    glide_on(d)
    k = d.keys(refresh=True)
    pts = [k["bn"], k["ui"], k["gh"]]
    glide_shot(d, pts, "14 键上从 BN 划过 UI 到 GH，抬手前：不应有滑行轨迹")
    s = d.screen()
    p, t = d.preedit(s), d.field_text(s)
    # a glide would read a word over three keys; a swipe on one key is its corner or the key itself
    bare = p.replace("|", "").replace(" ", "").replace("'", "")
    check(len(bare) <= 2 and no_codes(t), f"划过后预编辑 `{p}`，框里「{t}」")
    return f"预编辑 `{p}`，框里「{t}」"


# where each letter of nihao and buhao is on its key: its half's centre (a quarter or three quarters across)
ON_NIHAO = [.75, .75, .75, .25, .25]
ON_BUHAO = [.25, .25, .75, .25, .25]


def first_syllable(d):
    sy = d.syllables()
    return sy[0] if sy else None


@case("PY-100", base="B0-14键", mutates="learn", clean=True)
def py100(d):
    # GH (the third) tapped with a MOVE of 2, 1 px: a real finger's tap, which must keep its place
    grouped_keys(d, "bn ui gh as op", at=ON_NIHAO, drift=2)
    c = d.cands()
    sy = first_syllable(d)
    # a long press would have committed its letter and shown in the candidates: no separate popup check
    check(c and c[0] == "你好", f"候选 {c[:4]}（带 MOVE 的那一下要是成了长按，词也会断）")
    check(sy == "ni", f"音节条第一个 {sy}")
    return f"候选 {c[:3]}，音节条 {sy}"


@case("PY-101", base="B0-14键", mutates="learn", clean=True)
def py101(d):
    grouped_keys(d, "bn ui gh as op", at=ON_BUHAO)
    c = d.cands()
    sy = first_syllable(d)
    check(c and c[0] == "不好", f"候选 {c[:4]}")
    check(sy == "bu", f"音节条第一个 {sy}")
    return f"候选 {c[:3]}，音节条 {sy}"


@case("PY-102", base="B0-14键", mutates="learn", clean=True)
def py102(d):
    grouped_keys(d, "bn ui gh as op", at=[.5] * 5)
    c1 = d.cands()
    check(c1 and c1[0] == "不好", f"点在正中时候选 {c1[:4]}")
    d.pick("你好")
    t1 = d.field_text()
    check(t1 == "你好", f"上屏「{t1}」")
    d.clear()
    # after one pick 你好 ranks first wherever the keys land: this half checks only that typing
    # the same keys again finds the same learned word, not the tap place (PY-100 does that)
    grouped_keys(d, "bn ui gh as op", at=ON_NIHAO)
    c2 = d.cands()
    check(c2 and c2[0] == "你好", f"按 PY-100 点时候选 {c2[:4]}")
    d.clear()
    # quick input, the 符 key held: BN's right half is n, never the key's code (private use)
    k = d.key_node("符")
    d.hold(k.c, 900)
    time.sleep(0.6)
    grouped_keys(d, "bn", at=[.75])
    d.enter()
    t = d.field_text()
    check(t == "n" and no_codes(t), f"快速输入里点 BN 右半边再回车，框里「{t}」")
    return f"正中 {c1[:2]}；字母那一半 {c2[:2]}；快速输入「{t}」"
