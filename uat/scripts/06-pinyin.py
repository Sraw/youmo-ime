"""uat/06-pinyin.md: the B0 cases. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import (PKG, Blocked, block_candidate, case, check, choose, first, grouped_keys, keys_at, open_url, script)



@case("PY-01", mutates="learn")
def py01(d):
    d.type("nihao")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"框里是「{t}」")
    return f"框里是「{t}」"



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




def import_grouped(d, name, title):
    """uat/fixtures/layouts/<name>.layout imported through 外观 → 布局 (14-settings' helpers); its 导入 tapped,
    the 现在就用在 question that follows left open; the confirm dialog's texts returned"""
    st = script("14-settings")
    st.push_layout(d, name)
    st.import_layout(d, name)
    check(d.wait_text(f"导入布局 {title}？", timeout=8, where="all"), f"没出导入确认框：{d.screen().texts('all')[:10]}")
    told = d.screen().texts("all")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    return told


@case("PY-153", mutates=True, visual=True)
def py153(d):
    told = import_grouped(d, "uat-k17-1.0", "UAT 17 键")
    held = [t for t in told if "是 18 键的键" in t]
    check(not held, f"确认框提醒了 App 的键：{held}")
    check(d.wait_text("现在就用在 拼音 上吗？", timeout=6, where="all"), f"没只问拼音：{d.screen().texts('all')[:10]}")
    d.tap_text("用", where="all", scroll=False, after=2.0)
    d.open_page("text")
    k = d.keys(refresh=True)
    check("hjk" in k and "jk" not in k and "h" not in k, f"键盘上不是 17 键（有 HJK，没有 H、JK）：{sorted(k)}")
    d.shot("ime", "拼音用导入的 17 键：第 2 行 A SD FG HJK L")
    # each letter where it sits on its key, as a user aims: n i h a o
    grouped_keys(d, "bn io hjk a io", at=[0.75, 0.25, 1 / 6, 0.5, 0.75])
    c = d.cands()
    check(c[:1] == ["你好"], f"BN IO HJK A IO（点在 n i h a o 那边）的候选 {c[:8]}")
    d.pick("你好")
    text = d.field_text()
    d.clear()
    check(text == "你好", f"框里是 {text!r}")
    # HJK a key of its own name: its long press its own letters', not the app's H's (L6 verifier R-S1/R-N1)
    st = script("14-settings")
    st.slots_page(d)
    st.slots_tab(d, "UAT 17 键")
    st.key_dialog(d, "HJK")
    st.dialog_card(d, "长按菜单")
    items = [n.desc for n in d.screen().app() if n.clickable and n.desc and len(n.desc) == 1]
    d.shot("full", "按键字符和快捷键的 UAT 17 键：HJK 的长按菜单有 h j k")
    d.tap_text("取消", where="all", scroll=False, after=1.0)
    check(all(x in items for x in "hjk"), f"HJK 的长按菜单没有 h、j、k：{items}")
    return f"只问拼音、没提醒；17 键画出来；候选 {c[:3]}；点「你好」上屏；HJK 长按 {items}"


case("PY-156", needs="manual:导入 uat-k17-tall-1.0.layout 用在拼音上，FG、BN 点在靠 HJK 的边上和正中各打一次 a 看候选；"
                     "NeighbourSlipTest.theKeysAroundAKeyAreThoseBesideItAndThoseAboveAndBelowOverlappingIt 只查几何")(lambda d: None)




def edge_hold(d, xy, dx, note=None):
    """a long press on xy, the popup shot (note), the finger moved dx px across and lifted: the text added"""
    before = d.field_text()
    x, y = xy
    d.touch(("down", x, y), ("sleep", 0.9), ("move", x, y))
    if note:
        d.shot("kb", note)
    steps = [("move", x + dx * i // 4, y) for i in range(1, 5)]
    d.touch(*steps, ("up", x + dx, y))
    time.sleep(0.5)
    return d.field_text()[len(before):]


@case("PY-89b", base="B0-14键", visual=True)
def py89b(d):
    k = d.keys(refresh=True)
    p = d.dpx()
    # the keyboard's right edge, as rotated: the screen's (it spans it)
    width = d.need_keyboard().b[2]
    left = edge_hold(d, k["l"], -22 * p, "长按最右的 L：l L 0，角标在最右")
    edge = edge_hold(d, k["l"], width - 1 - k["l"][0])
    as_ = edge_hold(d, k["as"], 22 * p, None)
    check(left == "L", f"L 往左一挪打出 {left!r}")
    check(edge == "0", f"L 往右到屏幕边打出 {edge!r}（应是角标 0）")
    check(as_ == "A", f"AS 往右一挪打出 {as_!r}")
    return f"L←{left} L→边{edge} AS→{as_}"


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


@case("PY-109", base="B0-注音全键盘", mutates="learn")
def py109(d):
    k = d.keys(refresh=True)
    kb = d.need_keyboard()
    top = sorted((n for n in kb.walk() if n.clickable and n.texts() and n.c[1] < min(m.c[1] for m in kb.walk() if m.clickable) + 20), key=lambda n: n.c[0])
    row = "".join(n.texts()[0] for n in top)
    check(row == "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ", f"第一行 {row}")
    d.taps([k[x] for x in "ㄋㄧˇㄏㄠˇ"], gap=0.3, after=0.6)
    c = d.cands()
    code = d.preedit()
    check(code == "ㄋㄧˇ ㄏㄠˇ" and first(c) == "你好", f"码行「{code}」，候选 {c[:4]}")
    d.pick("你好")
    d.taps([k["ㄇ"], k["ㄚ"]], gap=0.3, after=0.3)
    d.space()
    c = d.cands()
    toned = d.preedit()
    check(toned == "ㄇㄚˉ" and first(c) == "妈", f"空格后码行「{toned}」，候选 {c[:4]}")
    d.space()
    d.taps([k[x] for x in "ㄉㄜ˙"], gap=0.3, after=0.6)
    c = d.cands()
    check(first(c) == "的", f"ㄉㄜ˙ 的候选 {c[:4]}")
    d.space()
    d.taps([k["ㄅ"], k["ˇ"]], gap=0.3, after=0.4)
    d.enter()
    got = d.expect_field("你好妈的ㄅˇ")
    return f"码行 {code}、{toned}，框里「{got}」"


@case("PY-118", base="B0-设置", mutates=True, visual=True)
def py118(d):
    """the readings are drawn, not in the bar's accessibility: the machine checks the characters are
    there, the shots show each polyphone's one reading above it and nothing beside it"""
    d.goto("通用输入")
    choose(d, "候选词注音", "拼音")
    d.open_page("text")
    seen = []
    # uz: the characters whose first stroke is 折, as in the user's screenshot (也 对 好 了 能)
    for keys, chars, note in (("uz", ("了", "能"), "了 上方 le、能 上方 néng，和 也 yě、好 hǎo 一样；右边没有 le/liao、neng/nai"),
                              ("uchichu", ("行",), "拆出的 行 上方 xíng，右边没有 xing/hang")):
        d.type(keys)
        c = d.cands(want=chars[-1])
        for char in chars:
            check(char in c, f"{keys}：候选里没有「{char}」：{c[:8]}")
        d.shot("kb", note)
        seen += chars
        d.clear()
    d.type("yinuchichu")
    d.cands()
    d.pick("银")   # past the bar's sentences (一怒斥出…): pick looks through the expanded list too
    c = d.cands(want="行")
    check("行" in c, f"选了银以后候选里没有「行」：{c[:8]}")
    d.shot("kb", "银 之后的 行 上方 háng")
    return "、".join(seen) + "、银+行 都在候选里，读音看图"


@case("PY-114", mutates=True)   # a word blocked stays blocked: the next case needs a reset
def py114(d):
    d.type("azhi")
    c = d.cands()
    check("啊之" in c, f"a z h i 的候选 {c[:8]}")
    block_candidate(d, "啊之", "长按「啊之」的菜单")
    d.clear()
    d.type("azhi")
    again = d.cands()
    check("啊之" not in again, f"屏蔽后 a z h i 还有「啊之」：{again[:8]}")
    d.clear()
    d.type("a")
    a = d.cands()
    d.clear()
    d.type("zhi")
    z = d.cands()
    d.clear()
    check("啊" in a and "之" in z, f"a {a[:6]}，zhi {z[:6]}")
    return f"屏蔽前 {c[:4]}，屏蔽后 {again[:4]}；a 有「啊」、zhi 有「之」（设置里恢复由 JVM 管）"


@case("PY-115", mutates=True)
def py115(d):
    d.type("dianzan")
    c = d.cands()
    emoji = "👍"
    if emoji not in c:
        raise Blocked(f"dianzan 的候选里没有 👍：{c[:8]}")
    block_candidate(d, emoji, f"长按表情「{emoji}」的菜单")
    d.clear()
    d.type("dianzan")
    after = d.cands()
    check(emoji not in after, f"屏蔽后 dianzan 还有「{emoji}」：{after[:8]}")
    d.clear()
    # kept over a restart of the input method (the line read back from words.blocked)
    restart_ime(d)
    d.type("dianzan")
    restarted = d.cands(timeout=30)
    check(emoji not in restarted, f"重启输入法后 dianzan 又有「{emoji}」：{restarted[:8]}")
    d.clear()
    d.type("uhs")
    u = d.cands()
    check(d.preedit().startswith("u"), f"打了 uhs，编码行是「{d.preedit()}」")
    check(u, "打了 uhs 没有候选")
    ch = u[0].split()[0]
    menu = block_candidate(d, u[0], f"长按笔画查字「{ch}」的菜单")
    check(any("这串码" in t for t in menu), "笔画查字的确认没写「打这串码不再出现」")
    d.clear()
    d.type("uhs")
    u2 = [x.split()[0] for x in d.cands() if x]
    d.clear()
    check(ch not in u2, f"屏蔽后 uhs 还有「{ch}」：{u2[:8]}")
    # a polyphone by its parts (彳亍 行, shown xíng): blocked in that reading only (A5)
    d.type("uchichu")
    c = d.cands(want="行")
    check("行" in c, f"uchichu 的候选里没有「行」：{c[:8]}")
    block_candidate(d, "行", "长按拆字出的多音字「行」的菜单")
    d.clear()
    left = {}
    for keys in ("uchichu", "xing", "hang"):
        d.type(keys)
        left[keys] = [x.split()[0] for x in d.cands() if x]
        d.clear()
    check("行" not in left["uchichu"], f"屏蔽后 uchichu 还有「行」：{left['uchichu'][:8]}")
    check("行" not in left["xing"], f"屏蔽后 xing 还有「行」：{left['xing'][:8]}")
    check("行" in left["hang"], f"屏蔽 xíng 后 hang 没有「行」：{left['hang'][:8]}")
    return f"表情「{emoji}」屏蔽后 dianzan {after[:4]}，重启输入法后 {restarted[:4]}；uhs「{ch}」屏蔽后 {u2[:4]}；拆字「行」屏蔽后 xing {left['xing'][:3]}、hang {left['hang'][:3]}（单音字拆字、滑行的其他读法由 JVM 管）"


# the input method's process killed and brought back, as a phone's restart would: what was learned read from its files
def restart_ime(d):
    pid = d.sh(f"pidof {PKG}").strip()
    if not pid:
        raise Blocked("输入法进程没在跑")
    d.root(f"kill -9 {pid}")
    time.sleep(1.0)
    open_url(d, "text")
    for _ in range(30):
        if d.screen().find(rid="keyboard_view", where="ime") and "不可用" not in d.im_label():
            return
        time.sleep(1.0)
    raise Blocked("重启后键盘没起来")


@case("PY-116", mutates=True)
def py116(d):
    tb = script("05-toolbar")
    # the keyboard up, the bar showing its tools: nothing typed
    d.type("a")
    d.clear()
    trad = tb.status_toggle(d, "简体")
    try:
        d.type("guojia")
        c = d.cands(timeout=30)
        check("國家" in c, f"繁體下 guojia 的候选 {c[:6]}")
        menu = block_candidate(d, "國家", "繁體下长按「國家」的菜单")
        check(any("屏蔽" in t for t in menu), f"繁體候选的菜单里没有屏蔽：{menu}")
        d.clear()
        d.type("guojia")
        after = d.cands()
        d.clear()
        # the list may have two that show as 國家 (国家, and the dictionary's own traditional 國家): both go
        check("國家" not in after, f"屏蔽后繁體下 guojia 还出「國家」：{c[:6]} → {after[:6]}")
    finally:
        d.clear()
        tb.status_toggle(d, trad)
    d.type("guojia")
    simp = d.cands()
    d.clear()
    check("国家" not in simp, f"屏蔽「國家」后简体下 guojia 还有「国家」：{simp[:6]}")
    return f"切到「{trad}」：长按「國家」菜单 {menu}；屏蔽后繁體 {after[:4]}，简体 {simp[:4]}"


case("PY-120", needs="jvm:EnginesStrokeLookupChainTest.withoutAKeyPassedOnWhatWasCommittedIsTheContext、"
     "EnginesStrokeLookupChainTest.enterPassedOnToTheAppDropsWhatWasCommittedBefore")(lambda d: None)


@case("PY-121", base="B0-设置", mutates=True, visual=True)
def py121(d):
    """the readings are drawn, not in the bar's accessibility: the machine finds the rare characters of
    chun in the expanded list, the shots show their toneless chun (none in zhuyin) and each row's
    characters on one line; and the floating window's emoji and word in English as tall as the candidates
    with readings, their bottoms aligned"""
    rare = ("䏝", "䏰", "䄝")
    d.goto("通用输入")
    choose(d, "候选词注音", "拼音")
    d.open_page("text")
    found = []
    for style in ("拼音", "注音符号"):
        if style == "注音符号":
            d.clear()
            # the test page is in front: settings first, or goto finds no 通用输入
            d.open_settings()
            d.goto("通用输入")
            choose(d, "候选词注音", "注音符号")
            d.open_page("text")
        d.type("chun")
        d.cands()
        b = d.screen().find(desc="展开候选词列表", where="ime")
        if not b:
            raise Blocked("chun 的候选栏没有「展开候选词列表」")
        d.tap(b.c, 0.6)
        found = []
        for _ in range(15):
            s = d.screen()
            grid = s.find(rid="expanded_candidate_view", where="ime")
            texts = [n.text.split(" ", 1)[0] for n in (grid.walk() if grid else []) if n.cls == "TextView" and n.text]
            found = [c for c in rare if c in texts]
            if found:
                break
            nxt = s.find(desc="更多候选词", where="ime")
            if not nxt or not nxt.enabled:
                break
            d.tap(nxt.c, 0.5)
        check(found, f"{style}：chun 的展开候选里没翻到 {'、'.join(rare)}")
        d.shot("kb", f"{style}：{'、'.join(found)} " + ("上方是不带声调的 chun，别的字带调" if style == "拼音" else "不标注音")
               + "；每一行的字底边在一条线上")
        if style == "拼音":
            d.clear()
            d.type("haha")
            d.cands()
            d.shot("kb", "拼音：表情这种没注音的候选和有注音的字底边对齐")
            d.clear()
            # the window by the cursor, on the emulator's USB keyboard; a shot of the top half
            d.kb("up")
            try:
                time.sleep(3)
                for keys, what in (("haha", "表情"), ("iphone", "「iPhone」这种英文")):
                    d.kb("type", keys)
                    time.sleep(1)
                    d.shot("top", f"拼音、悬浮候选窗：{what}和有注音的候选一样高、底边对齐")
                    d.kb("key", "ESC")
                    time.sleep(0.5)
            finally:
                d.kb("down")
                time.sleep(3)
    return f"翻到 {'、'.join(found)}，读音和对齐看图"


case("PY-121a", needs="manual:youmo29（API 29）上测试页打不开（WebView shell 要 API 34+）：在输入测试台 types 页的 text 框接 emu-keyboard.sh 的实体键盘手工跑，看悬浮候选窗的图")(lambda d: None)


@case("PY-122", mutates=True)
def py122(d):
    lc = script("18-lifecycle")
    lc.testbed_in_field(d, "types", "text")
    d.type("nihao")
    d.cands()
    f = next(x for x in d.screen().app() if x.rid == "text" and x.pkg == lc.TESTBED)
    # inside 「ni hao」: taps from the field's left edge on till the field's cursor is in the code, not at its ends
    at = None
    for i in range(1, 30):
        d.tap((f.b[0] + i * int(6 * d.dpx()), (f.b[1] + f.b[3]) // 2), 0.6)
        st = lc.tb(d, "text")
        if 0 < st.get("selStart", 0) < 6:
            at = st["selStart"]
            break
    if at is None:
        raise Blocked("点不到编码里面")
    st = lc.tb(d, "text")
    d.type("a")
    d.cands()
    st1 = lc.tb(d, "text")
    d.space()
    time.sleep(0.6)
    t2 = lc.tb(d, "text").get("text")
    check(st.get("text") == "ni hao" and (st.get("compStart"), st.get("compEnd")) == (0, 6), f"点进编码后框「{st.get('text')}」composing {st.get('compStart')}–{st.get('compEnd')}")
    t1 = st1.get("text") or ""
    check(t1.replace(" ", "") == ("nihao"[:sum(c != " " for c in "ni hao"[:at])] + "a" + "nihao"[sum(c != " " for c in "ni hao"[:at]):]), f"在第 {at} 位点 a 后框「{t1}」")
    check(t2 and not any(c.isascii() and c.isalpha() for c in t2), f"空格后框里「{t2}」")
    return f"光标点到第 {at} 位，「{t1}」→「{t2}」"


@case("PY-124", base="B0", visual=True)
def py124(d):
    """the reading line over the menu, the word card it opens, a character's card and the way back;
    the views' children are hidden from accessibility, so each step is found by its description, as
    TalkBack reads it"""
    d.open_page("text")
    d.type("yinhang")
    menu = d.long_pick("银行")
    d.shot("full", "长按「银行」：最上面一行是读音")
    s = d.screen()
    line = s.find(desc="银行，yín háng", where="all")
    check(line is not None, f"没有读音行：{[t for t in menu if len(t) < 16][:12]}")
    check(all(x in menu for x in ("屏蔽", "固定首位")), f"菜单项变了：{[t for t in menu if len(t) < 16][:12]}")
    d.tap(line.c, after=0.8)
    s = d.screen()
    d.shot("full", "点读音行：词卡")
    check(s.find(desc="银行，读音 yín háng", where="all") is not None, "点开后没有词卡")
    row = next((n for n in s.nodes if n.desc.startswith("行，háng")), None)
    check(row is not None, f"词卡里没有「行」那一行：{[n.desc for n in s.nodes if n.desc][:12]}")
    d.tap(row.c, after=0.8)
    s = d.screen()
    d.shot("full", "点「行」：字卡，返回行，只有复制")
    back = s.find(desc="返回 银行", where="all")
    check(back is not None, "字卡上没有返回行")
    texts = s.texts(where="all")
    check("复制「行」" in texts and "上屏" not in texts, f"字卡的按钮：{[t for t in texts if len(t) < 8][:12]}")
    d.tap(back.c, after=0.8)
    s = d.screen()
    check(s.find(desc="银行，读音 yín háng", where="all") is not None, "返回后不是词卡")
    d.tap_text("上屏", where="all", scroll=False, after=0.8)
    field = d.field_text()
    check(field == "银行", f"上屏后框里「{field}」")
    return "读音行、词卡、字卡、返回、上屏"




@case("PY-137")
def py137(d):
    # nihso twice, s tapped toward a (read as nihao) and in its middle (as typed): the middle's tells the place reached the engine
    keys_at(d, "nihso", [None, None, None, (0.2, 0.5), None])
    slip = d.cands()
    code = d.preedit()
    d.shot("kb", "s 点在靠 a 的边上打 nihso：首选「你好」，编码行 ni hao")
    d.clear()
    keys_at(d, "nihso", [None] * 5)
    mid = d.cands()
    mid_code = d.preedit()
    d.shot("kb", "nihso 每个键点在正中：编码行没有 hao（如 ni h so），首选不是「你好」")
    d.clear()
    check(slip[:1] == ["你好"] and code == "ni hao", f"s 靠 a 点的 nihso：首选 {slip[:4]}，编码行「{code}」")
    # how the unread hso is split in the code row is the engine's to choose (no JVM test holds it): only no hao is checked
    check("hao" not in mid_code and first(mid) != "你好", f"正中点的 nihso：编码行「{mid_code}」，候选 {mid[:6]}")
    return f"靠边 {slip[:1]}「{code}」；正中 {mid[:3]}「{mid_code}」"


@case("PY-144", base="B0", visual=True)
def py144(d):
    """a word card's character and the way back change the card in the same window (FB4: it went blank
    and grew again from the candidate on each tap): its left, right and the edge on the candidate bar where
    they were, the other edge alone following the card, never over the bar; upright (over the bar) and turned
    (under it); the frames themselves are the JVM test's and the recordings'"""
    upright = py144_steps(d, "竖屏")
    d.rotate(1)
    try:
        turned = py144_steps(d, "横屏")
    finally:
        d.rotate(0)
    return f"竖屏 {upright}；横屏 {turned}"


def py144_steps(d, how):
    d.open_page("text")
    d.type("yinhang")
    d.long_pick("银行")
    line = d.screen().find(desc="银行，yín háng", where="all")
    check(line is not None, f"{how}：没有读音行")
    d.tap(line.c, after=0.8)

    # the window found from the card's head, as lines under a tall card scroll with it
    def placed(s, what):
        head = next((n for n in s.nodes if n.desc.startswith(("银行，读音", "返回 银行"))), None)
        check(head is not None, f"{how}{what}：弹窗里没有卡")
        root = head
        while root.parent is not None:
            root = root.parent
        bar = s.find(rid="candidate_view", where="ime")
        check(bar is not None, f"{how}{what}：没有候选栏")
        over = root.b[3] <= bar.b[1]
        check(over or root.b[1] >= bar.b[3],
              f"{how}{what}：弹窗压在候选栏上（弹窗 {root.b}，候选栏 {bar.b}）")
        return root.b[0], root.b[2], ("上", root.b[3]) if over else ("下", root.b[1])

    s = d.screen()
    word = placed(s, "词卡")
    row = next((n for n in s.nodes if n.desc.startswith("行，háng")), None)
    check(row is not None, f"{how}：词卡里没有「行」那一行")
    d.tap(row.c, after=0.8)
    s = d.screen()
    check(s.find(desc="返回 银行", where="all") is not None, f"{how}：点「行」后不是字卡")
    char = placed(s, "字卡")
    d.shot("full", f"{how}点「行」：字卡，弹窗的左右和贴着候选栏的那条边没动")
    check(char == word, f"{how}换成字卡后弹窗动了（左、右、贴候选栏的边）：{word} → {char}")
    d.tap(s.find(desc="返回 银行", where="all").c, after=0.8)
    s = d.screen()
    check(s.find(desc="银行，读音 yín háng", where="all") is not None, f"{how}：返回后不是词卡")
    back = placed(s, "返回的词卡")
    check(back == word, f"{how}返回词卡后弹窗动了（左、右、贴候选栏的边）：{word} → {back}")
    d.back(0.6)
    side, edge = word[2]
    return f"弹窗在候选栏{side}，左 {word[0]}、右 {word[1]}、贴候选栏的边 {edge}，三次一样"


@case("PY-143")
def py143(d):
    # t tapped toward y still reads tama as typed (全拼's floor); nihso with s toward a reads nihao, over the floor
    ya = ("亚麻", "鸭毛", "压埋")
    keys_at(d, "tama", [(0.85, 0.5), None, None, None])
    edge = d.all_cands()
    d.shot("kb", "t 点在靠 y 的边上打 tama：候选栏和展开后的第一页没有「亚麻」这类 ya ma 的词")
    d.clear()
    keys_at(d, "tama", [None] * 4)
    mid = d.all_cands()
    d.clear()
    keys_at(d, "nihso", [None, None, None, (0.12, 0.5), None])
    slip = d.cands()
    d.shot("kb", "s 点在靠 a 的边上打 nihso：首选「你好」")
    d.clear()
    check(not any(w in edge for w in ya), f"t 靠 y 点的 tama，候选里有 ya ma 的词（全拼不改读拼得成的）：{edge[:14]}")
    check(not any(w in mid for w in ya), f"四个键都点在正中的 tama，候选里有 ya ma 的词：{mid[:14]}")
    check(slip[:1] == ["你好"], f"s 靠 a 点的 nihso，首选不是「你好」：{slip[:6]}")
    return f"tama 靠边 {[w for w in edge if w in ya]}；正中 {mid[:6]}；nihso 靠边首选 {slip[:1]}"


def latin_mix(d, tap, wodeiphone, iphone, sentence):
    """T9-70, PY-158, PY-159: tap(d, keys) types keys on this keyboard"""
    tap(d, wodeiphone)
    c = d.cands(want="我的iPhone")
    p = d.preedit()
    d.shot("ime", f"wo de iphone：编码行「{p}」，首选「{first(c)}」")
    check(first(c) == "我的iPhone", f"首选 {c[:3]}")
    check("iphone" in p, f"编码行「{p}」里英文那段应是小写 iphone")
    d.pick("我的iPhone")
    t = d.field_text()
    check(t == "我的iPhone", f"框里「{t}」")
    d.clear()
    tap(d, iphone)
    d.cands()
    p = d.preedit()
    d.enter()
    time.sleep(0.6)
    t2 = d.field_text()
    check(p == "iphone" and t2 == "iphone", f"编码行「{p}」，回车后框里「{t2}」")
    d.clear()
    tap(d, sentence)
    c3 = d.cands()
    check(first(c3) == "我们今天去吃饭", f"中文整句首选 {c3[:3]}")
    return f"首选「{first(c)}」，回车上屏「{t2}」，整句首选「{first(c3)}」"


@case("PY-158", base="B0-14键", mutates="learn", visual=True)
def py158(d):
    return latin_mix(d, grouped_keys, "qw op df er ui op gh op bn er", "ui op gh op bn er",
                     "qw op m er bn jk ui bn ty ui as bn qw ui cv gh ui df as bn")


@case("PY-159", base="B0-拼音18键", mutates="learn", visual=True)
def py159(d):
    return latin_mix(d, grouped_keys, "we io sd we io p h io bn we", "io p h io bn we",
                     "we io m we bn jk io bn rt io a bn q u xc h io fg a bn")


@case("PY-160", base="B0-设置", mutates=True, visual=True)
def py160(d):
    d.goto("通用输入")
    d.switch("空格上屏联想词", True)
    d.open_page("text")
    d.type("jintian")
    d.cands(want="今天")
    d.space()
    d.expect_field("今天")
    time.sleep(1.0)
    shown = d.cands()
    d.shot("kb", "开着：联想的第一个词画成首选的样子")
    check(shown, "「今天」后没有联想")
    d.space()
    want = "今天" + first(shown)
    d.expect_field(want, msg=f"空格后框里「{{got}}」，不是「{want}」（联想 {shown[:3]}）")
    d.space()
    d.expect_field(want + " ", msg="取过联想后的下一下空格没打出空格：「{got}」")
    d.clear()
    d.open_settings()
    d.goto("通用输入")
    d.switch("空格上屏联想词", False)
    d.open_page("text")
    d.type("jintian")
    d.cands(want="今天")
    d.space()
    d.expect_field("今天")
    time.sleep(1.0)
    d.space()
    off = d.expect_field("今天 ", msg="关着时空格没打出空格：「{got}」")
    return f"开着：「{want}」，再一下空格加空格；关着：「{off}」"


@case("PY-166", base="B0")
def py166(d):
    """a word card's rows scrolled, then a row tapped: its character's card (FB: after a scroll no row opened)"""
    d.open_page("text")
    d.type("zhonghuarenmingongheguo")
    d.long_pick("中华人民共和国")
    line = d.screen().find(desc="中华人民共和国，zhōng huá rén mín gòng hé guó", where="all")
    check(line is not None, "没有读音行")
    d.tap(line.c, after=0.8)
    s = d.screen()
    rows = [n for n in s.nodes if n.desc.startswith(("中，", "华，", "人，", "民，", "共，", "和，", "国，"))]
    check(len(rows) >= 2, f"词卡里没有字的行：{[n.desc for n in s.nodes if n.desc][:12]}")
    top, low = rows[0], rows[-1]
    for how, a, b in (("往上滑", low.c, top.c), ("往下滑", top.c, low.c)):
        d.swipe(a, b, 300, after=1.5)
        s = d.screen()
        row = next((n for n in s.nodes if n.desc.startswith("和，")), None) or \
            next((n for n in s.nodes if n.desc.startswith("华，")), None)
        check(row is not None, f"{how}后词卡里找不到行")
        d.tap(row.c, after=0.8)
        s = d.screen()
        check(s.find(desc="返回 中华人民共和国", where="all") is not None, f"{how}后点「{row.desc[:1]}」没打开字卡")
        d.tap(s.find(desc="返回 中华人民共和国", where="all").c, after=0.8)
    return "上滑、下滑后点字都打开字卡"
