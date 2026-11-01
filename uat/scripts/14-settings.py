"""uat/14-settings.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import shutil
import subprocess
import tempfile
import time

from uat import (PKG, Blocked, case, check, choose, home, script)


def kb_box(d, s=None):
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    if not kb:
        raise Blocked("键盘没弹出")
    return kb.b


def keys_box(d, s=None):
    """the union of the keys' bounds (what the user sees as the keys)"""
    s = s or d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    ks = [n.b for n in (kb.walk() if kb else []) if n.clickable]
    if not ks:
        raise Blocked("键盘没弹出")
    return (min(b[0] for b in ks), min(b[1] for b in ks), max(b[2] for b in ks), max(b[3] for b in ks))


def preview_box(d, s=None):
    """the keyboard preview on the 外观 page: the union of its keys (named ones, and the letters)"""
    s = s or d.screen()
    ks = [n.b for n in s.app() if n.rid in ("button_caps", "button_space", "button_return", "button_backspace")]
    ks += [n.row().b for n in s.app() if n.cls == "TextView" and len(n.text) == 1 and n.text in "qp"
           and ks and n.b[1] < min(b[1] for b in ks) and n.row().parent is not None]
    if not ks:
        raise Blocked("外观页没有预览")
    return (min(b[0] for b in ks), min(b[1] for b in ks), max(b[2] for b in ks), max(b[3] for b in ks))


def appearance_tab(d, tab):
    home(d, "外观")
    d.tap_text(tab, scroll=False, after=1.0)


# ---------------------------------------------------------------- 首页


case("ST-01", needs="jvm:SettingsUatTest.theHomePage")(lambda d: None)


case("ST-02", needs="jvm:SettingsMenuUatTest.searchingFindsFuzzyPinyinAndGoesThere")(lambda d: None)


case("ST-03", needs="jvm:SettingsMenuUatTest.helpOpensTheManual")(lambda d: None)


# ---------------------------------------------------------------- 通用输入


case("ST-32", needs="jvm:SettingsUatTest.anInputMethodsPageAndTheHardwareKeyboardsSavedLeaveTheSharedOptions、PinyinUatTest.fuzzyZZh")(lambda d: None)


case("ST-33", needs="jvm:SettingsUatTest.pagesFromTopToBottom、SettingsUatTest.everyInputMethodsPageAndTheSharedOnes、SettingsUatTest.theHomePage")(lambda d: None)


case("ST-34", needs="jvm:TablesUatTest.predictionOffHoldsForTablesToo")(lambda d: None)


# ---------------------------------------------------------------- 外观


case("ST-05", needs="jvm:SettingsUatTest.aDarkThemeTurnsPreviewAndKeyboardDark")(lambda d: None)


def push_picture(d):
    """a picture in the gallery: a striped PNG in Pictures, scanned"""
    from PIL import Image, ImageDraw
    p = os.path.join(tempfile.gettempdir(), f"uat-theme-{d.port}.png")
    im = Image.new("RGB", (1080, 900), (250, 120, 30))
    dr = ImageDraw.Draw(im)
    for i in range(0, 1080, 120):
        dr.rectangle((i, 0, i + 60, 900), fill=(30, 90, 220))
    im.save(p)
    d.adb("push", p, "/sdcard/Pictures/uat-theme.png")
    d.sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/uat-theme.png")
    time.sleep(1)


def add_image_theme(d):
    push_picture(d)
    appearance_tab(d, "主题")
    d.tap_text("新增主题", after=1.2)
    d.tap_text("选择图片…", contains=True, scroll=False, after=2.5)
    # the system's picker: the picture by its name, or the only picture
    s = d.screen()
    for label in ("uat-theme.png", "uat-theme"):
        n = d.visible(label, s, contains=True)
        if n:
            d.tap(n.c, 2.5)
            break
    else:
        imgs = [n for n in s.app() if n.pkg != PKG and n.clickable and n.cls in ("ImageView", "FrameLayout", "View")
                and n.b[3] - n.b[1] > 150 and n.b[1] > 300]
        if not imgs:
            raise Blocked(f"系统选图界面里找不到图片：{d.top_activity()} {s.texts()[:10]}")
        d.tap(imgs[0].c, 2.5)
    s = d.screen()
    # a crop step, if any, then the theme editor
    for label in ("裁剪", "完成", "确定", "Crop", "Done"):
        n = s.find(text=label) or s.find(desc=label)
        if n and n.pkg == PKG and "亮度" not in "".join(s.texts()):
            d.tap(n.c, 2.0)
            s = d.screen()
            break
    texts = s.texts()
    if not any("亮度" in t for t in texts):
        raise Blocked(f"选图后没到主题编辑（无亮度）：{d.top_activity()} {texts[:10]}")
    bar = next((n for n in s.app() if n.cls == "SeekBar"), None)
    if bar:
        d.tap((bar.b[0] + (bar.b[2] - bar.b[0]) * 3 // 4, (bar.b[1] + bar.b[3]) // 2), 0.6)
    for label in ("保存", "完成", "确定"):
        n = s.find(text=label) or s.find(desc=label)
        if n:
            d.tap(n.c, 2.0)
            break
    else:
        raise Blocked(f"主题编辑里没有保存：{texts[:12]}")


def theme_items(d, s=None):
    s = s or d.screen()
    return [n for n in s.app() if n.cls == "ViewGroup" and 300 < n.b[2] - n.b[0] < 400
            and 200 < n.b[3] - n.b[1] < 300 and "新增主题" not in n.texts()]


def custom_item(d, s=None):
    """a theme of one's own: the item with the edit button (a clickable image in its corner)"""
    for n in theme_items(d, s):
        if any(k.cls == "ImageView" and k.clickable for k in n.walk()):
            return n
    return None


def kb_picture(d, rows=1.0):
    """how much of the keyboard (its top [rows] part) is the picture's orange and blue"""
    x0, y0, x1, y1 = kb_box(d)
    im = d.png().crop((x0, y0, x1, y0 + int((y1 - y0) * rows))).convert("RGB").resize((60, 30))
    px = list(im.getdata())
    return (sum(1 for r, g, b in px if r > g + 40 and r > b + 60), sum(1 for r, g, b in px if b > r + 80 and b > g + 80))


def export_custom(d):
    """long-press one's own theme, save it in 下载; returns the new file's name"""
    before = set(d.sh("ls /sdcard/Download/ 2>/dev/null").split())
    appearance_tab(d, "主题")
    item = custom_item(d)
    if not item:
        raise Blocked("列表里没有自己加的主题")
    d.hold(item.c, 1000)
    time.sleep(2.0)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"长按后没出保存界面：{d.top_activity()}")
    s = d.screen()
    if not s.find(text="下载"):
        raise Blocked(f"保存界面不在下载：{s.texts()[:8]}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = set(d.sh("ls /sdcard/Download/ 2>/dev/null").split()) - before
    return sorted(new)


def delete_custom(d):
    appearance_tab(d, "主题")
    item = custom_item(d)
    pen = next(k for k in item.walk() if k.cls == "ImageView" and k.clickable)
    d.tap(pen.c, 2.0)
    d.tap_text("删除", scroll=False, after=1.5)
    d.tap_text("确定", scroll=False, after=2.0)


@case("ST-09", base="B0-设置", mutates=True)
def st09(d):
    d.step("新增")
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统夜间模式", False)
    # the default to come back to, seen now: with 跟随 off it is the normal-mode theme's default
    # (PixelDark), not the light one (lum > 150) 跟随 on would give
    d.open_page("text")
    lum_default = d.lum(kb_box(d))
    add_image_theme(d)
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "主题列表里没有新加的主题")
    d.tap(item.c, 1.5)
    d.open_page("text")
    o, b = kb_picture(d)
    check(o > 40 and b > 40, f"点了它，键盘背景不是那张图（橙 {o} 蓝 {b} / 1800）")
    d.step("导出")
    files = export_custom(d)
    check(files, "导出后下载里没有新文件")
    d.step("删除")
    delete_custom(d)
    appearance_tab(d, "主题")
    check(not custom_item(d), "删除后列表里还有它")
    d.open_page("text")
    # above the bottom row: the release build's default (Pixel) has a blue return key there
    o0, b0 = kb_picture(d, rows=0.75)
    lum = d.lum(kb_box(d))
    check(o0 < 10 and b0 < 10 and abs(lum - lum_default) < 25,
          f"键盘不是默认主题（图的橙 {o0} 蓝 {b0}，亮度 {lum:.0f}，加主题前 {lum_default:.0f}）")
    d.step("导入")
    appearance_tab(d, "新增主题")
    d.tap_text("导入文件…", contains=True, scroll=False, after=2.5)
    s = d.screen()
    n = next((k for k in s.app() if k.text == files[0]), None)
    if not n:
        raise Blocked(f"文件选择里没有 {files[0]}：{s.texts()[:8]}")
    d.tap(n.c, 3.0)
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.open_page("text")
    o, b = kb_picture(d)
    check(o > 40 and b > 40, f"选用导入的主题后键盘背景不是那张图（橙 {o} 蓝 {b} / 1800）")
    return f"新增、选用是那张图；导出 {files[0]}；删除后回到默认（亮度 {lum:.0f}）；导入后选用是那张图"


case("ST-35", needs="jvm:SettingsMenuUatTest.aBuiltInThemeCopied")(lambda d: None)


case("ST-36", needs="jvm:SettingsMenuUatTest.followingTheSystemAThemeTappedAsks")(lambda d: None)


case("ST-10", needs="jvm:SettingsEffectUatTest.keyboardHeightFollowsTheSetting")(lambda d: None)


case("ST-11", needs="jvm:SettingsEffectUatTest.sideAndBottomMarginsNarrowAndLiftTheKeys")(lambda d: None)


@case("ST-39", base="B0-设置", mutates=True)
def st39(d):
    def preview_gap():
        # the blank under the preview's keys: the nav bar's height is left out of it when 贴底 (F3553)
        s = d.screen()
        pv = preview_box(d, s)
        below = [n.b[1] for n in s.app() if n.b[1] >= pv[3] - 1 and n.b[0] < pv[2] and n.b[2] > pv[0]]
        d.shot((pv[0], pv[1], pv[2], min(below, default=pv[3]) + 10), "预览下方")
        return min(below) - pv[3] if below else None

    d.open_page("text")
    k0 = keys_box(d)
    appearance_tab(d, "大小与样式")
    g0 = preview_gap()
    choose(d, "键盘底部边距", "贴底")
    g1 = preview_gap()
    d.open_page("text")
    k1 = keys_box(d)
    appearance_tab(d, "大小与样式")
    choose(d, "键盘底部边距", "无")
    g2 = preview_gap()
    d.open_page("text")
    k2 = keys_box(d)
    check(k1[3] > k0[3] + 10, f"贴底没下移：键区底 {k0[3]} → {k1[3]}")
    check(abs(k2[3] - k0[3]) <= 2, f"选回无没回原位：键区底 {k0[3]} → {k2[3]}")
    check(g0 is not None and g1 is not None and g1 < g0 - 10, f"贴底时预览下方的空白没少：{g0} → {g1}")
    check(g2 is not None and abs(g2 - g0) <= 2, f"选回无预览下方的空白没回来：{g0} → {g2}")
    return f"键区底 无 {k0[3]} → 贴底 {k1[3]} → 无 {k2[3]}；预览下空白 {g0} → {g1} → {g2}"


case("ST-12", needs="jvm:SettingsEffectUatTest.keyBorderSpacingAndCorners")(lambda d: None)


case("ST-13", needs="jvm:SettingsEffectUatTest.punctuationPositionNoneThenBottom")(lambda d: None)


# ---------------------------------------------------------------- 按键

case("ST-14", needs="jvm:SettingsEffectUatTest.noPopupOnKeyPress")(lambda d: None)


case("ST-15", needs="jvm:SettingsEffectUatTest.swipeUpThenDisabled")(lambda d: None)


case("ST-16", needs="jvm:SettingsEffectUatTest.aLongerLongPressDelay")(lambda d: None)


case("ST-17", needs="jvm:SettingsEffectUatTest.holdingTheSpaceGoesToTheNextInputMethod")(lambda d: None)


case("ST-18", needs="jvm:SettingsEffectUatTest.spaceSwipeOffLeavesTheCursor")(lambda d: None)


case("ST-19", needs="jvm:SettingsEffectUatTest.bracketsUnpairedWhenOff")(lambda d: None)


case("ST-20", needs="jvm:SettingsEffectUatTest.noLanguageKeyAWiderSpace")(lambda d: None)


case("ST-21", needs="jvm:SettingsEffectUatTest.aCharacterMadeFirstIsTheCornerAndTheSwipe")(lambda d: None)


case("ST-22", needs="jvm:SettingsEffectUatTest.charactersAddedThenTheDefaultBack、KotlinHostKeyboardTest.aCircledLetterLongPressedOnTheFourteenKeysIsTypedAsText、KotlinHostKeyboardTest.aParenthesisedDigitLongPressedOnShuangpinsNineKeysIsTypedAsText、PopupOverridesFragmentTest$Dialog.aKeyCodeAloneIsRefusedAndTheDialogStays")(lambda d: None)


# ---------------------------------------------------------------- 其他

case("ST-25", needs="jvm:SettingsMenuUatTest.theLanguagesOfferedAreFour、AppLanguageUatTest.englishPickedTheKeyboardsOwnWordsAreEnglish")(lambda d: None)


def record_change(d, y0, y1, act):
    """screenrecord the band y0..y1 while act() runs; returns (in-between frames, frames): a change
    drawn at once has none, an animated one several"""
    tmp = tempfile.mkdtemp(prefix="uat-rec-")
    rec = subprocess.Popen(["adb", "-s", d.serial, "shell", "screenrecord", "--time-limit", "3",
                            "/sdcard/uat-rec.mp4"])
    time.sleep(1.0)
    act()
    rec.wait(timeout=20)
    d.adb("pull", "/sdcard/uat-rec.mp4", os.path.join(tmp, "r.mp4"))
    subprocess.run(["ffmpeg", "-loglevel", "quiet", "-i", os.path.join(tmp, "r.mp4"), "-vsync", "0",
                    "-vf", f"crop=1080:{y1 - y0}:0:{y0},scale=216:-1", os.path.join(tmp, "f%04d.png")])
    from PIL import Image, ImageChops
    frames = [Image.open(os.path.join(tmp, f)).convert("L") for f in sorted(os.listdir(tmp)) if f.endswith(".png")]
    shutil.rmtree(tmp, ignore_errors=True)
    if len(frames) < 2:
        raise Blocked(f"录屏只有 {len(frames)} 帧")

    def diff(a, b):
        h = ImageChops.difference(a, b).histogram()
        return sum(i * c for i, c in enumerate(h)) / (a.width * a.height)
    ds = [diff(f, frames[0]) for f in frames]
    top = max(ds)
    if top < 1:
        raise Blocked("录屏里这一块没变")
    # the coming in only: the frames before the first one most of the way there (what follows, a notice
    # going or the copy's own overlay, is not the change measured). From a tenth: the panels' 120 ms
    # (Motion.Medium since R13) leaves two frames between, the first ~13% of the way (2.8 of 21.2)
    there = next(i for i, x in enumerate(ds) if x >= 0.85 * top)
    # only the run right before it: noise earlier in the recording is not the coming in (F3723)
    n = 0
    while n < there and ds[there - 1 - n] > 0.1 * top:
        n += 1
    return n, len(frames)


def record_toolbar(d):
    """the bar while the toolbar opens (then closed again)"""
    b = d.screen().find(desc="展开工具栏", where="ime")
    if not b:
        raise Blocked("没有展开工具栏的按钮")
    r = record_change(d, b.b[1], b.b[3], lambda: d.tap(b.c, 1.0))
    d.tap_text("隐藏工具栏", where="ime", scroll=False, after=0.5)
    return r


def record_panel(d):
    """the keyboard's area while the clipboard panel replaces the keys (then ← back to them)"""
    d.expand_toolbar()
    s = d.screen()
    clip = s.find(desc="剪贴板", where="ime")
    kb = s.find(rid="keyboard_view", where="ime")
    if not clip or not kb:
        raise Blocked(f"工具栏上没有剪贴板图标：{[n.desc for n in s.ime() if n.desc][:10]}")
    r = record_change(d, kb.b[1], kb.b[3], lambda: d.tap(clip.c, 1.0))
    back = d.screen().find(desc="返回键盘", where="ime")
    if back:
        d.tap(back.c, 0.8)
    return r


@case("ST-26", base="B0-设置", mutates=True, visual=True)
def st26(d):
    # recorded with Android's animator scale at 5: the panel's 120 ms leaves about two frames between at
    # 1, at the edge of what is recorded (one on some runs); five times as long it is plainly animated
    d.sh("settings put global animator_duration_scale 5")
    try:
        d.open_page("text")
        on, n_on = record_toolbar(d)
        pon, _ = record_panel(d)
    finally:
        d.sh("settings put global animator_duration_scale 1")
    appearance_tab(d, "大小与样式")
    d.switch("禁用动画", True)
    d.open_page("text")
    off, n_off = record_toolbar(d)
    poff, _ = record_panel(d)
    if on < 2:
        raise Blocked(f"开着动画时也只录到 {on} 个过渡帧（共 {n_on} 帧），分不出来")
    if pon < 2:
        raise Blocked(f"开着动画时剪贴板面板也只录到 {pon} 个过渡帧，分不出来")
    check(off <= 1, f"禁用动画后工具栏仍有 {off} 个过渡帧（开着时 {on}）")
    check(poff <= 1, f"禁用动画后剪贴板面板仍有 {poff} 个过渡帧（开着时 {pon}）")
    # the setting reaches the notice too: copied, it is in place and opaque at once. Asserted on the JVM
    # (UndoChipTest.withAnimationsOffItIsInPlaceAndOpaqueAtOnce): a recording cannot tell it, the
    # notice's first frame being three quarters of the way in already; here a look
    d.step("禁用动画时的提示")
    script("05-toolbar").copy_word(d, "公园")
    d.shot("full", "禁用动画，复制后的提示")
    # Android's own 移除动画 does the same with the setting off
    d.step("系统移除动画")
    appearance_tab(d, "大小与样式")
    d.switch("禁用动画", False)
    d.sh("settings put global animator_duration_scale 0")
    try:
        d.open_page("text")
        sys0, _ = record_toolbar(d)
        script("05-toolbar").copy_word(d, "公园")
        d.shot("full", "系统移除动画，复制后的提示")
    finally:
        d.sh("settings put global animator_duration_scale 1")
    check(sys0 <= 1, f"系统移除动画后工具栏仍有 {sys0} 个过渡帧")
    return (f"过渡帧：工具栏 开着 {on}、禁用后 {off}、系统移除动画 {sys0}；剪贴板面板 开着 {pon}、禁用后 {poff}；"
            "复制的提示看图")


def set_comma(d, mark):
    """中文标点 → , set to mark, typed by its corner swipe (、 is m's corner character)"""
    corner = {"、": "m"}[mark]
    home(d, "通用输入", "中文标点")
    s = d.screen()
    n = next((n for n in s.app() if n.text == ","), None)
    if not n:
        raise Blocked("中文标点里没有 ,")
    d.tap(n.row().c, 1.2)
    s = d.screen()
    eds = [n for n in s.app() if "Edit" in n.cls]
    if len(eds) < 2:
        raise Blocked(f"编辑框 {[(e.text) for e in eds]}")
    d.tap(eds[1].c, 0.8)
    d.clear()
    m = d.keys()[corner]
    d.swipe(m, (m[0], m[1] + 130), 150)
    t = d.field_text()
    if t != mark:
        raise Blocked(f"改成「{mark}」没成功：框里「{t}」")
    d.hide_keyboard()
    d.tap_text("确定", scroll=False, after=1.0)


case("ST-28", needs="jvm:PunctuationMapUatTest.theCommaMappedToAnotherMarkTypesIt")(lambda d: None)


@case("ST-29", base="B0-设置", mutates=True)
def st29(d):
    home(d, "更多选项")
    d.goto("开发者", "实时日志")
    time.sleep(1.5)
    s = d.screen()
    lines = [n.text for n in s.app() if n.text[:2] in ("V/", "D/", "I/", "W/", "E/") or "beginning of" in n.text]
    exp = s.find(desc="导出") or s.find(text="导出")
    check(len(lines) >= 3, f"日志页 {s.texts()[:6]}")
    check(exp, "日志页没有导出")
    d.back()
    # the inspector is a switch here; with it on, the keyboard's … area has an info button that
    # shows the field's EditorInfo (the case's "回来看")
    d.switch("编辑器信息 (EditorInfo) 检查器", True)
    d.open_page("url")
    d.expand_toolbar()
    d.tap_text("状态区域", where="ime", scroll=False)
    d.tap_text("编辑器信息 (EditorInfo) 检查器", where="ime", scroll=False, after=1.0)
    alltext = ""
    for _ in range(8):
        s = d.screen()
        alltext += " " + " ".join(n.text for n in s.ime() if n.text)
        if "TYPE_TEXT_VARIATION_URI" in alltext:
            break
        kb = s.find(rid="input_window", where="ime")
        y0, y1 = (kb.b[1], kb.b[3]) if kb else (1546, 2208)
        d.swipe((540, y1 - 80), (540, y0 + 80), 400)
    i = alltext.find("inputType")
    check("TYPE_TEXT_VARIATION_URI" in alltext, f"检查器里没有 TYPE_TEXT_VARIATION_URI：{alltext[i:i + 120] if i >= 0 else alltext[-160:]}")
    return f"日志 {len(lines)} 行、有导出；检查器显示 TYPE_TEXT_VARIATION_URI（在键盘 … 里，不在设置页）"


case("ST-31", needs="jvm:SettingsMenuUatTest.aboutListsVersionsSourceAndLicenses")(lambda d: None)


# ---------------------------------------------------------------- 拼音的键盘


case("ST-40", needs="jvm:SettingsUatTest.pinyinsKeyboardOnItsPageAndTheSharedOptionsSayingFourteenKeys、KeyboardChoiceUatTest.theFourteenKeysChosenAreThereAtTheNextFocus")(lambda d: None)


case("ST-41", needs="jvm:GroupedKeyboardUatTest.theCodeRowIsTallOnlyInPinyinOnTheFourteenKeys")(lambda d: None)


case("ST-42", needs="jvm:SettingsUatTest.shuangpinsKeyboardUnderItsScheme、KeyboardChoiceUatTest.shuangpinsEighteenThenNineKeysChosenAreThereAtTheNextFocus")(lambda d: None)


case("ST-43", needs="jvm:AppPrefsTest.glideTypingCarriesOverAsItIs")(lambda d: None)


case("ST-44", needs="jvm:AddressSettingsUatTest.theAddressPageItsListsAndRememberedAddresses")(lambda d: None)


case("ST-45", needs="jvm:SettingsUatTest.theNineKeysSettingsAreASectionOfEachKeyboardsPage")(lambda d: None)

case("ST-46", needs="jvm:SettingsUatTest.glideTypingAndStrokeFilterTurnEachOtherOff")(lambda d: None)

case("ST-47", needs="jvm:SettingsMenuUatTest.searchingFindsTheNineKeysAndTheAddressRows")(lambda d: None)
