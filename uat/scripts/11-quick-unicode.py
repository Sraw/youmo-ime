"""uat/11-quick-unicode.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import case, check, BAR_BOTTOM


def bar_label(d, s=None):
    s = s or d.screen()
    return [n.text for n in s.ime() if n.text and n.c[1] < BAR_BOTTOM]


def quick(d):
    k = d.key_node("符")
    d.hold(k.c, 900)
    time.sleep(0.6)


@case("QU-01")
def qu01(d):
    quick(d)
    lab = bar_label(d)
    check(any("快速输入" in x for x in lab), f"栏上 {lab}")
    return f"栏上 {lab[:3]}"


case("QU-02", needs="jvm:HostCasesUatTest.aMoodWordGivesKaomoji")(lambda d: None)


case("QU-03", needs="jvm:HostCasesUatTest.anEmojiByItsName")(lambda d: None)


case("QU-04", needs="jvm:HostCasesUatTest.aLatexNameGivesItsSymbol")(lambda d: None)


case("QU-05", needs="jvm:HostCasesUatTest.backspacingQuickInputEmptyLeavesIt")(lambda d: None)


case("QU-06", needs="jvm:HostCasesUatTest.unicodeByName")(lambda d: None)


case("QU-07", needs="jvm:HostCasesUatTest.unicodeByHexCode")(lambda d: None)


case("QU-08", needs="jvm:PinyinUatTest.quickInputOffersNoEnglishWords")(lambda d: None)


case("QU-09", needs="jvm:Keys14UatTest.unicodeOnTheFourteenKeysTakesTheCodeFromCornersAndHalves")(lambda d: None)
