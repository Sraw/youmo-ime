"""uat/04-panels.md. The .md is the source of truth; each function follows its case's 前提/操作/预期."""
from uat import GRID_BOTTOM, GRID_TOP, PANEL_BOTTOM, PANEL_TOP, Blocked, case, emoji_tab_xy

CATS = ["最近使用", "笑脸", "人物", "动物与自然", "食物与饮料", "旅行与地点", "活动", "物品", "符号", "旗帜"]


def ime_tap(d, text=None, desc=None, after=0.6):
    s = d.screen()
    n = s.find(text=text, where="ime") if text else s.find(desc=desc, where="ime")
    if not n:
        raise Blocked(f"键盘上没有「{text or desc}」")
    d.tap(n.c, after)
    return n


def sym_category(d, name):
    """the 符 panel: scroll the category column to name and tap it"""
    for _ in range(6):
        s = d.screen()
        m = d.visible(name, s, where="ime")
        if m:
            d.tap(m.c, 0.8)
            return
        col = s.find(text="常用", where="ime") or s.find(text="中文", where="ime")
        x = col.c[0] if col else 97
        d.swipe((x, 1950), (x, 1650), 300)
    for _ in range(6):   # and back up
        s = d.screen()
        m = d.visible(name, s, where="ime")
        if m:
            d.tap(m.c, 0.8)
            return
        d.swipe((97, 1650), (97, 1950), 300)
    raise Blocked(f"符号面板里找不到分类「{name}」")


def sym_tap(d, char):
    for _ in range(8):
        s = d.screen()
        n = d.visible(char, s, where="ime")
        if n and n.c[0] > 200:
            d.tap(n.c, 0.6)
            return
        d.swipe((640, 2000), (640, 1700), 300)
    raise Blocked(f"这一类里找不到「{char}」")


def selected_category(d):
    """the highlighted category: the one drawn differently is not in the tree; read the page's first
    symbols instead"""
    s = d.screen()
    return [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
            if n.text and n.c[0] > 200 and PANEL_TOP < n.c[1] < PANEL_BOTTOM][:6]


def emoji_tab(d, i):
    d.tap(emoji_tab_xy(i), 0.8)
    s = d.screen()
    hdr = next((n.text for n in sorted(s.ime(), key=lambda n: n.b[1]) if n.text in CATS), "")
    return hdr


def emoji_find(d, chars, pages=30):
    """slow swipes (no fling) down the grid until one of chars shows"""
    chars = (chars,) if isinstance(chars, str) else chars
    for _ in range(pages):
        s = d.screen()
        n = next((n for n in s.ime() if n.text in chars and GRID_TOP < n.c[1] < GRID_BOTTOM), None)
        if n:
            return n
        d.swipe((540, 2000), (540, 1640), 900, after=0.3)
    return None


case("PAN-01", needs="jvm:KeyboardUatTest.numberPadAndBack")(lambda d: None)


case("PAN-02", needs="jvm:PanelUatTest.numberAndPhoneFieldsOpenTheNumberPad")(lambda d: None)


case("PAN-03", needs="jvm:PanelUatTest.numberPadSymbolsKeyOpensThePanel")(lambda d: None)


case("PAN-04", needs="jvm:PanelUatTest.symbolPanelTypesAndCloses")(lambda d: None)


case("PAN-05", needs="jvm:PanelUatTest.lockedPanelStaysForSeveral")(lambda d: None)


case("PAN-07", needs="jvm:PanelUatTest.recentHasTheLastSymbolFirst")(lambda d: None)


case("PAN-08", needs="jvm:PanelUatTest.panelsNumbersKeyAndBack")(lambda d: None)


case("PAN-09", needs="jvm:PanelUatTest.emojiPanelTabsAndTyping")(lambda d: None)


case("PAN-10", needs="jvm:PanelUatTest.theDefaultSkinTone")(lambda d: None)


case("PAN-11", needs="jvm:PanelUatTest.emojiPanelBackToLetters")(lambda d: None)


case("PAN-12", needs="jvm:PanelUatTest.emojiSearchByPinyin")(lambda d: None)


case("PAN-14", needs="jvm:PanelUatTest.numberPadSpaceSlidesTheCursor")(lambda d: None)


def recent_emoji(d):
    emoji_tab(d, 0)
    s = d.screen()
    return [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
            if n.text and 1620 < n.c[1] < 2060 and n.text not in CATS]


case("PAN-15", needs="jvm:PanelUatTest.ownFieldsKeepNoRecentEmoji")(lambda d: None)
