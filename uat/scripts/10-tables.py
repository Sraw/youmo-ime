"""uat/10-tables.md (B0-五笔 and the other tables). The .md is the source of truth; each function
follows its case's 前提/操作/预期."""
import statistics
import time

from uat import Blocked, add_im, case, script, check, first, has, home


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


case("TBL-02", needs="jvm:OtherInputMethodsUatTest.wubiFourCodesWithOneCharacterCommitIt")(lambda d: None)


case("TBL-03", needs="jvm:TablesUatTest.fullCodeThenAnotherKeyCommitsTheFirst")(lambda d: None)


case("TBL-04", needs="jvm:TablesUatTest.autoCommitOff")(lambda d: None)


case("TBL-05", needs="jvm:TablesUatTest.anEmptyCodeIsDroppedByTheNextKey")(lambda d: None)


case("TBL-06", needs="jvm:TablesUatTest.codeHintsByDefault")(lambda d: None)


case("TBL-07", needs="jvm:TablesUatTest.codeHintsOff")(lambda d: None)


case("TBL-08", needs="jvm:TablesUatTest.wubiZLooksUpByPinyin")(lambda d: None)


case("TBL-09", needs="jvm:TablesUatTest.wubiZIsAWildcard")(lambda d: None)


case("TBL-10", needs="jvm:OtherInputMethodsUatTest.wubiAWord")(lambda d: None)


case("TBL-11", needs="jvm:TablesUatTest.aCharacterAtATimeMakesNoWord")(lambda d: None)


@case("TBL-12", base="B0-设置", mutates=True)
def tbl12(d):
    home(d, "词库与短语", "用户词库", "手动添加的词")
    s = d.screen()
    add = next((n for n in s.app() if "添加" in n.desc), None)
    if not add:
        raise Blocked(f"手动添加的词没有 +：{[n.desc for n in s.app() if n.desc]}")
    d.tap(add.c, 1.0)
    s = d.screen()
    edits = [n for n in s.nodes if n.cls == "EditText"]
    if len(edits) < 1:
        raise Blocked("添加没有输入框")
    d.tap(edits[0].c, 0.8)
    d.switch_im("拼音")
    for code, w in (("jing", "鲸"), ("yuan", "园")):
        d.type(code)
        d.cands()
        d.pick(w)
    if d.field_text() != "鲸园":
        raise Blocked(f"词框里打出「{d.field_text()}」")
    # the pinyin left empty: it is filled in (jing yuan) as WD-02 shows
    d.hide_keyboard()
    for _ in range(4):   # the dialog's 确定 (button1), until the dialog is gone
        time.sleep(0.5)
        ok = next((n for n in d.screen().nodes if n.rid == "button1"), None)
        if not ok:
            break
        d.tap(ok.c, 1.2)
    s = d.screen()
    rows = s.texts()
    if any(n.cls == "EditText" for n in s.nodes if n.rid != "search") and "添加词" in rows[:2] or "鲸园" not in rows:
        raise Blocked(f"手动添加的词里没有鲸园：{rows[:8]}")
    add_im(d, "五笔")
    on_page(d, "五笔")
    # D8: qglf is 昏天黑地's alone, a word from outside the table does not come in to stop it
    d.type("qglf")
    time.sleep(0.4)
    c, t = d.candidates(), d.field_text()
    check(t == "昏天黑地" and not has(c, "鲸园"), f"候选 {c[:4]}，框里「{t}」")
    d.clear()
    d.switch_im("拼音")
    d.type("jingyuan")
    p = d.cands()
    check(has(p, "鲸园"), f"拼音 jingyuan 的候选 {p[:4]}")
    d.clear()
    w = script("13-words")
    w.user_lexicon(d, "手动添加的词")
    row = next((r for r in w.list_texts(d) if "五笔" in r and "同码" in r), None)
    check(row and "昏天黑地" in row, f"鲸园那一行没写「五笔：与 昏天黑地 同码」：{w.list_texts(d)[:10]}")
    return f"qglf →「{t}」；jingyuan {p[:3]}；「{row}」"


case("TBL-14", needs="jvm:TablesUatTest.noOrderByUseOnANewInstall")(lambda d: None)


case("TBL-15", needs="jvm:TablesUatTest.orderByUseOnPinyinUntouchedThenTheTablesOrderBack")(lambda d: None)


case("TBL-16", needs="jvm:TablesUatTest.aTablesCommitPredicts")(lambda d: None)


case("TBL-18", needs="jvm:TablesUatTest.erbiBracketLooksUpByPinyin")(lambda d: None)


case("TBL-19", needs="jvm:OtherInputMethodsUatTest.cangjie")(lambda d: None)


case("TBL-21", needs="jvm:TablesUatTest.ziranmaTable")(lambda d: None)


case("TBL-22", needs="jvm:OtherInputMethodsUatTest.wubiAndPinyinMixed")(lambda d: None)


@case("TBL-24", base="B0-五笔", mutates="learn")
def tbl24(d):
    d.type("wvi")
    s = d.screen()
    p0, c0, t0 = d.preedit(s), d.candidates(s), d.field_text(s)
    spans, y = d.preedit_glyphs()
    if len(spans) != 3:
        raise Blocked(f"编码行字形 {spans}")
    d.tap(((spans[1][1] + spans[2][0]) // 2, y), 0.6)
    p1 = d.preedit()
    d.bksp()
    d.type("q")
    s = d.screen()
    p2, t2 = d.preedit(s), d.field_text(s)
    spans, y = d.preedit_glyphs()
    row = next(n for n in d.screen().ime() if n.cls == "HorizontalScrollView")
    d.tap((min(row.b[2] - 5, spans[-1][1] + 60), y), 0.6)
    d.type("y")
    s = d.screen()
    p3, c3 = d.preedit(s), d.candidates(s)
    d.space()
    t = d.field_text()
    check(p0 == "wvi" and c0 and not t0, f"wvi：`{p0}` {c0[:2]} 框里「{t0}」")
    check("|" in p1 and p1.index("|") == 2, f"点 v、i 之间后 `{p1}`")
    check(p2 == "wq|i" and not t2, f"补 q 后 `{p2}`，框里「{t2}」")
    check(p3 == "wqiy" and first(c3) == "你", f"补 y 后 `{p3}` {c3[:2]}")
    check(t == "你", f"空格后「{t}」")
    return f"`{p0}` → `{p1}` → `{p2}` → `{p3}` → 「{t}」"


case("TBL-12b", needs="jvm:TablesUatTest.importedWordsAfterTheTablesOwnNotOnItsOnlyCode")(lambda d: None)


@case("TBL-12c", base="B0-设置", mutates=True, heavy=True)
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
    # coding frost for 五笔 runs on past the 30 s above and holds ~100 MB live while it does (read
    # after a GC); the case is about what stays, so wait for it to finish before the readings
    # two low readings in a row: one alone can fall between two batches of the coding
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


case("TBL-27", needs="jvm:TablesUatTest.wubiEmoji")(lambda d: None)


case("TBL-28", needs="jvm:TablesUatTest.everyTableHasEmojiAfterASwitchAndARestart")(lambda d: None)


case("TBL-33", needs="release:要从 v0.3.0 升级（装旧版、学过词，不卸载装这一版）；发版时手动跑")(lambda d: None)
case("TBL-43", needs="release:要 v0.3.0 装好学过词后导出的 .zip，再重置装这一版恢复；发版时手动跑")(lambda d: None)
case("TBL-12d", needs="release:v0.3.2 正式版里并入雾凇拼音、加五笔，再覆盖安装这一版正式版，签名要一致")(lambda d: None)


case("TBL-36", needs="jvm:TablesUatTest.aBlockInATableIsForThatCodeListedAndRestored")(lambda d: None)

