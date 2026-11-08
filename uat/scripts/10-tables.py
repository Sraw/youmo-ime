"""uat/10-tables.md (B0-五笔 and the other tables). The .md is the source of truth; each function
follows its case's 前提/操作/预期."""
import statistics
import time

from uat import (Blocked, add_im, block_candidate, case, check, script)


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


case("TBL-12", needs="jvm:TablesSettingsUatTest.aPinyinWordOnATablesOnlyCodeSaysWhichWord、TablesUatTest.aPinyinWordTakesNoTablesOnlyCode")(lambda d: None)


case("TBL-14", needs="jvm:TablesUatTest.noOrderByUseOnANewInstall")(lambda d: None)


case("TBL-15", needs="jvm:TablesUatTest.orderByUseOnPinyinUntouchedThenTheTablesOrderBack")(lambda d: None)


case("TBL-16", needs="jvm:TablesUatTest.aTablesCommitPredicts")(lambda d: None)


case("TBL-18", needs="jvm:TablesUatTest.erbiBracketLooksUpByPinyin")(lambda d: None)


case("TBL-19", needs="jvm:OtherInputMethodsUatTest.cangjie")(lambda d: None)


case("TBL-45", needs="jvm:TablesUatTest.cangjieAndErbiStarIsAWildcard")(lambda d: None)


case("TBL-21", needs="jvm:TablesUatTest.ziranmaTable")(lambda d: None)


case("TBL-22", needs="jvm:OtherInputMethodsUatTest.wubiAndPinyinMixed")(lambda d: None)


case("TBL-24", needs="jvm:HostCasesUatTest.aCodeMistypedInTheMiddleIsFixedWhereTheRowIsTapped")(lambda d: None)


case("TBL-12b", needs="jvm:TablesUatTest.importedWordsAfterTheTablesOwnNotOnItsOnlyCode")(lambda d: None)


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


case("TBL-43", needs="release:要 v0.4.0 装好学过词后导出的 .zip，再重置装这一版恢复；发版时手动跑")(lambda d: None)


case("TBL-12d", needs="release:v0.3.2 正式版里并入雾凇拼音、加五笔，再覆盖安装这一版正式版，签名要一致")(lambda d: None)


case("TBL-36", needs="jvm:TablesUatTest.aBlockInATableIsForThatCodeListedAndRestored")(lambda d: None)


case("TBL-46", needs="jvm:TablesUatTest.cangjiesCodeIsShownInItsRoots")(lambda d: None)


case("TBL-47", needs="jvm:TablesUatTest.theLookUpKeyLooksUpByPinyinTillPressedAgain")(lambda d: None)


case("TBL-48", needs="jvm:TablesKeysUatTest.returnSaysConfirmWhileATableHasACode")(lambda d: None)


case("TBL-49", needs="jvm:TablesKeysUatTest.wubiKeysShowNamesOrAllRoots")(lambda d: None)


case("TBL-50", needs="jvm:TablesKeysUatTest.erbisSemicolonHasAKey")(lambda d: None)


case("TBL-51", needs="jvm:TablesKeysUatTest.wubisAllRootsAreItsNamesAtCompact、TableRootsTest.atCompactAllTheRootsAreShownAsTheirNames")(lambda d: None)


@case("TBL-52", base="B0-五笔", mutates="learn")
def tbl52(d):
    d.type("zni")
    c = d.cands()
    c = [x.split()[0] for x in c if x]
    check("你" in c, f"z n i 的候选 {c[:6]}")
    menu = block_candidate(d, 1 + c.index("你"), "五笔反查 z n i，长按「你」的菜单")
    check("固定首位" not in menu, "反查的菜单里有固定首位")
    d.clear()
    d.type("zni")
    again = [x.split()[0] for x in d.cands() if x]
    d.clear()
    check("你" not in again, f"屏蔽后 z n i 还有「你」：{again[:6]}")
    return f"屏蔽前 {c[:4]}，屏蔽后 {again[:4]}（设置里恢复由 JVM 管）"
