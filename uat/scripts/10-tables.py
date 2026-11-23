"""uat/10-tables.md (B0-五笔 and the other tables). The .md is the source of truth; each function
follows its case's 前提/操作/预期."""
import statistics
import time

from uat import (PKG, Blocked, add_im, block_candidate, case, check, gear, home, script)


def word(c):
    return c.split(" ")[0]


def words(cs):
    return [word(c) for c in cs]


def on_page(d, label):
    d.open_page("text")
    d.switch_im(label)


@case("TBL-01", base="B0-五笔", mutates="learn")
def tbl01(d):
    d.type("q")
    d.cands()
    d.space()
    d.type("w")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "我人", f"框里「{t}」")
    return f"「{t}」"


def offer(d, want, note):
    """the notice strip's offer after the pause: its words, shot"""
    s = d.wait(lambda x: x if x.find(text="记住：", where="all") else None, timeout=5, step=0.2,
               what="提示条「记住：」")
    d.shot("full", note)
    offered = [n.text for n in s.nodes if n.pkg == PKG and n.text and n.text != "记住：" and " " in n.text]
    check(want in offered, f"提示条给出 {offered}，没有「{want}」")
    return offered


def remember(d, text):
    """tap the offered word: kept, the strip says so with 撤销"""
    n = d.screen().find(text=text, where="all")
    if not n:
        raise Blocked(f"提示条上没有「{text}」")
    d.tap(n.c, 0.4)
    return d.wait(lambda x: x if x.find(contains="记住了", where="all") else None, timeout=3, step=0.2,
                  what="提示条「记住了」")


def wang_xiao_ming(d):
    d.type("ig ih je ")
    d.expect_field("汪小明")


@case("TBL-11", base="B0-五笔", mutates="learn", clean=True)
def tbl11(d):
    wang_xiao_ming(d)
    offered = offer(d, "汪小明 iije", "逐字打出「汪小明」停一下：提示条「记住：汪小明 iije」")
    check(not any(o.startswith("小明") for o in offered), f"给出了半个词：{offered}")
    said = [t for t in remember(d, "汪小明 iije").texts(where="all") if "记住了" in t]
    d.shot("full", "点了以后：记住了『汪小明』，带撤销")
    check(any("五笔" in t and "iije" in t for t in said), f"记住后的提示 {said}")
    d.expect_field("汪小明")
    d.type(",iije")
    # not first till picked again (TBL-11d): past the bar's few, in the list
    c = words(d.all_cands())
    check("汪小明" in c, f"iije 的候选 {c[:12]}")
    return f"给出 {offered}；{said}；iije 里第 {c.index('汪小明') + 1} 个"


@case("TBL-11c", base="B0-五笔", mutates="learn", clean=True)
def tbl11c(d):
    wang_xiao_ming(d)
    offer(d, "汪小明 iije", "给出「汪小明」")
    s = remember(d, "汪小明 iije")
    undo = s.find(text="撤销", where="all")
    check(undo is not None, f"记住后的提示没有撤销：{s.texts(where='all')[:8]}")
    d.tap(undo.c, 0.8)
    d.expect_field("汪小明")
    d.clear()
    d.type("iije")
    c = words(d.cands())
    check("汪小明" not in c, f"撤销后 iije 还有「汪小明」：{c[:6]}")
    d.clear()
    d.type("qgyi")
    d.cands(want="鲸")
    d.pick("鲸")
    d.type("lfqv")
    if d.field_text() != "鲸园":
        d.space()
    d.expect_field("鲸园")
    offer(d, "鲸园 qglf", "给出「鲸园 qglf」")
    remember(d, "鲸园 qglf")
    d.clear()
    d.type("qglf")
    kept = words(d.cands(want="鲸园"))
    check("鲸园" in kept, f"记住后 qglf 的候选 {kept[:6]}")
    menu = d.long_pick("鲸园")
    d.shot("full", "长按记住的「鲸园」：有删除")
    check("删除" in menu, f"长按菜单 {[t for t in menu if len(t) < 10][:12]}")
    d.tap_text("删除", where="all", scroll=False, after=0.3)
    s = d.wait(lambda x: x if x.find(text="撤销", where="all") else None, timeout=3, step=0.2, what="删除后的撤销")
    deleted = [t for t in s.texts(where="all") if "删除" in t]
    d.shot("full", "删除后的提示条，带撤销")
    d.tap(s.find(text="撤销", where="all").c, 0.8)
    d.clear()
    d.type("qglf")
    again = words(d.cands(want="鲸园"))
    check("鲸园" in again, f"撤销删除后 qglf 的候选 {again[:6]}")
    return f"撤销后 iije {c[:3]}；删除提示 {deleted}；撤销后 qglf {again[:4]}"


@case("TBL-11g", base="B0-五笔", mutates="learn", clean=True)
def tbl11g(d):
    d.step("删回去")
    # no pause: an offer seen in the field would not be given again in the second part
    d.type("ig ih je " * 3)
    d.bksp(7)
    d.type("je ")
    d.expect_field("汪小明", msg="九个字删回两个再打「明」，框里「{got}」")
    time.sleep(2.0)
    s = d.screen()
    whole = [t for t in s.texts(where="all") if t.startswith("汪小明 ")]
    d.shot("full", "删回去以后：不给「汪小明」")
    check(not whole, f"删回去以后提示条给了 {whole}")
    d.step("九个字")
    d.clear()
    d.type("ig ih je " * 3)
    d.expect_field("汪小明" * 3)
    offered = offer(d, "汪小明 iije", "打满九个字停下：只给最后的「汪小明 iije」")
    check(all(len(word(o)) <= 3 for o in offered), f"九个字给了 {offered}")
    return f"删回去以后不给汪小明；九个字给出 {offered}"


@case("TBL-11h", base="B0-五笔", mutates="learn", clean=True)
def tbl11h(d):
    wang_xiao_ming(d)
    offer(d, "汪小明 iije", "给出「汪小明」")
    remember(d, "汪小明 iije")
    d.switch_im("拼音")
    s = d.screen()
    undo = s.find(text="撤销", where="all")
    d.shot("full", "切到拼音后：记住了的提示条还在，带撤销")
    check(undo is not None, f"切到拼音后提示条没有撤销：{s.texts(where='all')[:8]}")
    d.tap(undo.c, 0.8)
    d.expect_field("汪小明")
    d.switch_im("五笔")
    d.clear()
    d.type("iije")
    c = words(d.cands())
    check("汪小明" not in c, f"切到拼音撤销后回五笔，iije 还有「汪小明」：{c[:6]}")
    return f"切到拼音撤销，回五笔 iije {c[:3]}"


@case("TBL-11i", base="B0-设置", mutates=True, clean=True, visual=True)
def tbl11i(d):
    add_im(d, "五笔")
    d.open_settings()
    d.goto("通用输入")
    d.switch("空格上屏联想词", True)
    on_page(d, "五笔")

    def space_takes(before, note):
        time.sleep(1.0)
        shown = words(d.cands())
        d.shot("kb", note)
        if not shown:
            raise Blocked(f"「{before}」后没有联想")
        d.space()
        want = before + shown[0]
        d.expect_field(want, msg=f"空格后框里「{{got}}」，不是「{want}」（联想 {shown[:3]}）")
        return want

    d.step("记住以后")
    wang_xiao_ming(d)
    offer(d, "汪小明 iije", "给出「汪小明」")
    remember(d, "汪小明 iije")
    kept = space_takes("汪小明", "点了记住：第一个联想词画成首选的样子")
    d.step("撤销以后")
    d.clear()
    d.type("qgyi")
    d.cands(want="鲸")
    d.pick("鲸")
    d.type("lfqv")
    if d.field_text() != "鲸园":
        d.space()
    d.expect_field("鲸园")
    offer(d, "鲸园 qglf", "给出「鲸园 qglf」")
    s = remember(d, "鲸园 qglf")
    undo = s.find(text="撤销", where="all")
    check(undo is not None, f"记住后的提示没有撤销：{s.texts(where='all')[:8]}")
    d.tap(undo.c, 0.8)
    d.expect_field("鲸园")
    undone = space_takes("鲸园", "点了撤销：第一个联想词画成首选的样子")
    return f"记住后空格「{kept}」，撤销后空格「{undone}」"


@case("TBL-11d", base="B0-设置", mutates=True)
def tbl11d(d):
    add_im(d, "五笔")
    gear(d, "五笔")
    d.switch("调频", True)
    on_page(d, "五笔")
    wang_xiao_ming(d)
    offer(d, "汪小明 iije", "给出「汪小明」")
    remember(d, "汪小明 iije")
    d.type(",iije")
    once = words(d.all_cands())
    check("汪小明" in once and once[0] != "汪小明", f"记住后第一次 iije {once[:6]}")
    d.pick("汪小明")
    d.type(",iije")
    twice = words(d.cands(want="汪小明"))
    d.shot("kb", "记住后再选一次：iije 第一个是「汪小明」")
    check(twice[0] == "汪小明", f"再选一次后 iije {twice[:4]}")
    return f"iije 记住后 {once[:3]}，再选一次后 {twice[:3]}"


@case("TBL-11e", base="B0-五笔拼音", mutates="learn", clean=True)
def tbl11e(d):
    d.type("qgyi")
    d.cands(want="鲸")
    d.pick("鲸")
    d.type("yuan")
    d.cands(want="园")
    d.pick("园")
    d.expect_field("鲸园")
    offered = offer(d, "鲸园 qglf", "五笔拼音：拼音打出的「园」也算，提示条「记住：鲸园 qglf」")
    return f"给出 {offered}"


@case("TBL-11f", base="B0-二笔", mutates="learn", clean=True)
def tbl11f(d):
    d.type("wch xd ms ")
    d.expect_field("汪小明")
    offered = offer(d, "汪小明 wcxm", "二笔：提示条「记住：汪小明 wcxm」")
    return f"给出 {offered}"


@case("TBL-47a", base="B0-五笔", mutates="learn", visual=True)
def tbl47a(d):
    k = d.keys(refresh=True)
    check("shift" in k, f"五笔键盘没有 ⇧：{sorted(k)[:40]}")
    check("拼" not in k, "五笔键盘上还有「拼」")
    d.press("shift", after=0.4)
    d.shot("kb", "五笔点 ⇧：字母键上是大写字母")
    d.type("a")
    d.expect_field("A")
    d.type("ig")
    check("shift" in d.keys(refresh=True), "打着编码时 ⇧ 不见了")
    d.press("shift", after=0.4)
    d.type("a")
    got = d.expect_field("A汪A")
    return f"框里「{got}」"


@case("TBL-12c", base="B0-设置", mutates=True, heavy=True,
      needs="release,scripted:发版时用发布包跑（UAT_BUILD=release），平时不跑：要雾凇拼音，一条三四分钟（用户 2026-10-30 定）")
def tbl12c(d):
    """frost in the base dictionary, coded for 五笔 through the merged file: typing stays, the heap too"""
    words13 = script("13-words")
    src = words13.frost(d)
    d.open_page("text")
    d.switch_im("拼音")
    base = words13.heap_base(d)
    d.adb("push", src, "/sdcard/Download/frost_dict_for_fcitx5.txt", timeout=300)
    words13.import_file(d, "frost_dict_for_fcitx5.txt", "基础词库")
    if not any("frost_dict_for_fcitx5" in r for r in words13.dictionaries(d)):
        raise Blocked("前提没到：frost 没并入基础词库")
    add_im(d, "五笔")
    on_page(d, "五笔")
    time.sleep(30.0)   # its words get their codes in the background
    d.type("jbys")
    time.sleep(0.4)
    c = words(d.all_cands())
    d.clear()
    more = []
    for code in ("gg", "wq"):
        d.type(code)
        time.sleep(0.4)
        more.append(words(d.candidates())[:3])
        d.clear()
    # coding frost for 五笔 runs past the wait above: wait for two low readings in a row before the readings
    peak, low = 0, 0
    for _ in range(30):
        h = words13.java_heap(d)
        peak = max(peak, h or 0)
        # no reading is not a low one
        low = low + 1 if h is not None and h - statistics.median(base[1:]) < words13.HEAP_RISE_KB // 2 else 0
        if low == 2:
            break
        time.sleep(3.0)
    heap = words13.heaps(d)
    rise = words13.heap_rise(base, heap)
    # the table's own three first, in whatever order; then frost's
    own = [c.index(w) if w in c else 99 for w in ("临阵磨枪", "电子文档", "电子计算机")]
    check(sorted(own) == [0, 1, 2], f"jbys 的候选 {c[:8]}（码表自己的词该在最前）")
    check(all(more), f"gg、wq 的候选 {more}")
    check(rise is not None and rise < words13.HEAP_RISE_KB, f"Java 堆 {base} → {heap} KB")
    return f"jbys：{c[:5]}；gg、wq {more}；Java 堆 {base} → {heap} KB（编码时最高 {peak} KB）"


case("TBL-43", needs="release:要 v0.4.0 装好学过词后导出的 .zip，再重置装这一版恢复；发版时手动跑")(lambda d: None)


@case("TBL-52", base="B0-五笔", mutates=True)   # 你 blocked stays blocked
def tbl52(d):
    d.type("zni")
    raw = d.cands()
    c = [x.split()[0] for x in raw if x]
    check("你" in c, f"z n i 的候选 {c[:6]}")
    # the position in the list as shown (an empty item counts), not in the filtered one
    at = next(i for i, x in enumerate(raw) if x and x.split()[0] == "你")
    menu = block_candidate(d, 1 + at, "五笔反查 z n i，长按「你」的菜单")
    check("固定首位" not in menu, "反查的菜单里有固定首位")
    d.clear()
    d.type("zni")
    again = [x.split()[0] for x in d.cands() if x]
    d.clear()
    check("你" not in again, f"屏蔽后 z n i 还有「你」：{again[:6]}")
    return f"屏蔽前 {c[:4]}，屏蔽后 {again[:4]}（设置里恢复由 JVM 管）"
