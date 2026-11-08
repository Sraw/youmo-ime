"""uat/14-settings.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import re
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


THEME_ZIP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "web", "theme-maker", "test", "testdata",
                         "from-app.theme")


def push_theme(d):
    """a theme file (.theme, a zip inside) in 下载: the testdata theme (3.0) with a striped picture,
    drawn sharp and at full brightness so the keyboard shows its orange and blue"""
    import io
    import json
    import zipfile
    from PIL import Image, ImageDraw

    def striped(w, h):
        im = Image.new("RGB", (w, h), (250, 120, 30))
        dr = ImageDraw.Draw(im)
        for i in range(0, w, 120):
            dr.rectangle((i, 0, i + 60, h), fill=(30, 90, 220))
        out = io.BytesIO()
        im.save(out, "PNG")
        return out.getvalue()

    p = os.path.join(tempfile.gettempdir(), f"uat-theme-{d.port}.theme")
    with zipfile.ZipFile(THEME_ZIP) as src, zipfile.ZipFile(p, "w") as dst:
        for name in src.namelist():
            data = src.read(name)
            if name.endswith(".json"):
                t = json.loads(data)
                t["picture"]["blurDp"], t["picture"]["brightness"] = 0, 100
                t["bar"] = {"type": "picture", "blurDp": 0, "brightness": 100}
                t["keys"]["letter"]["fill"] = {"type": "picture", "blurDp": 0, "brightness": 100}
                data = json.dumps(t).encode()
            elif name.endswith("-src.png"):   # as tall as the json's crops reach (portrait's to 1800)
                data = striped(1440, 1800)
            elif name.endswith("-portrait.png"):   # its cropRect's size
                data = striped(1440, 1500)
            elif name.endswith("-landscape.png"):
                data = striped(1440, 600)
            dst.writestr(name, data)
    d.sh("rm -f /sdcard/Download/uat-theme*.theme")
    d.adb("push", p, "/sdcard/Download/uat-theme.theme")
    d.sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/uat-theme.theme")
    time.sleep(1)


def import_theme(d, name):
    """新增主题, which opens the system's picker straight away: the file [name] there (it opens at 下载 or the recent ones)"""
    appearance_tab(d, "主题")
    d.tap_text("新增主题", after=3.0)
    s = d.screen()
    n = next((k for k in s.app() if k.text and k.text.startswith(name)), None)
    if not n and s.find(text="下载"):
        d.tap(s.find(text="下载").c, 2.0)
        s = d.screen()
        n = next((k for k in s.app() if k.text and k.text.startswith(name)), None)
    if not n:
        raise Blocked(f"文件选择里没有 {name}：{s.texts()[:8]}")
    d.tap(n.c, 3.0)


def theme_items(d, s=None):
    s = s or d.screen()
    return [n for n in s.app() if n.cls == "ViewGroup" and 300 < n.b[2] - n.b[0] < 400
            and 200 < n.b[3] - n.b[1] < 300 and "新增主题" not in n.texts()]


def custom_item(d, s=None):
    """a theme of one's own: listed first, before the Monet ones, which have the icon in their top-left corner"""
    items = theme_items(d, s)
    if not items:
        return None
    first = items[0]
    # the icon, not the thumbnail's picture (an ImageView over the whole item, from the same corner)
    corner = any(k.cls == "ImageView" and abs(k.b[0] - first.b[0]) < 6 and abs(k.b[1] - first.b[1]) < 6
                 and 0 < k.b[2] - k.b[0] < (first.b[2] - first.b[0]) // 2 for k in first.walk())
    return None if corner else first


def theme_action(d, item, label):
    """long-press one's own theme, pick [label] (导出 / 删除) in its menu"""
    d.hold(item.c, 1000)
    time.sleep(1.0)
    s = d.screen()
    if not (s.find(text="导出") and s.find(text="删除")):
        raise Blocked(f"长按自己的主题没出导出、删除：{s.texts()[:8]}")
    d.tap_text(label, scroll=False, after=2.0)


def kb_picture(d, rows=1.0):
    """how much of the keyboard (its top [rows] part) is the picture's orange and blue"""
    x0, y0, x1, y1 = kb_box(d)
    im = d.png().crop((x0, y0, x1, y0 + int((y1 - y0) * rows))).convert("RGB").resize((60, 30))
    px = list(im.getdata())
    return (sum(1 for r, g, b in px if r > g + 40 and r > b + 60), sum(1 for r, g, b in px if b > r + 80 and b > g + 80))


def export_custom(d):
    """long-press one's own theme, 导出, save it in 下载; returns the new files' names"""
    before = set(d.sh("ls /sdcard/Download/ 2>/dev/null").splitlines())   # names with spaces: from-app (1).theme
    appearance_tab(d, "主题")
    item = custom_item(d)
    if not item:
        raise Blocked("列表里没有自己加的主题")
    theme_action(d, item, "导出")
    time.sleep(1.0)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出后没出保存界面：{d.top_activity()}")
    s = d.screen()
    if not s.find(text="下载"):
        raise Blocked(f"保存界面不在下载：{s.texts()[:8]}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = set(d.sh("ls /sdcard/Download/ 2>/dev/null").splitlines()) - before
    return sorted(new)


def delete_custom(d):
    appearance_tab(d, "主题")
    theme_action(d, custom_item(d), "删除")
    d.tap_text("确定", scroll=False, after=2.0)


@case("ST-09", base="B0-设置", mutates=True)
def st09(d):
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统夜间模式", False)
    # the default to come back to, seen now: with 跟随 off it is the normal-mode theme's default
    # (PixelDark), not the light one (lum > 150) 跟随 on would give
    d.open_page("text")
    lum_default = d.lum(kb_box(d))
    push_theme(d)
    d.step("拒绝 .zip")
    # the same file under a zip's name, as an earlier 0.5.1 or the page exported: only .theme is taken
    d.sh("cp /sdcard/Download/uat-theme.theme /sdcard/Download/uat-zip.zip")
    d.sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/uat-zip.zip")
    import_theme(d, "uat-zip")
    check(d.wait_text("只能导入主题文件", timeout=5, where="all", contains=True), "选 .zip 没有提示只认 .theme")
    d.back(after=1.0)
    d.sh("rm -f /sdcard/Download/uat-zip.zip")
    appearance_tab(d, "主题")
    check(not custom_item(d), "选 .zip 后主题列表里多了主题")
    d.step("导入")
    import_theme(d, "uat-theme")
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.open_page("text")
    o, b = kb_picture(d)
    check(o > 40 and b > 40, f"点了它，键盘背景不是那张图（橙 {o} 蓝 {b} / 1800）")
    d.step("导出")
    files = export_custom(d)
    check(files, "导出后下载里没有新文件")
    check(files and files[0].endswith(".theme"), f"导出的文件名不是 .theme：{files}")
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
    d.step("再导入")
    import_theme(d, files[0])
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.open_page("text")
    o, b = kb_picture(d)
    check(o > 40 and b > 40, f"选用导入的主题后键盘背景不是那张图（橙 {o} 蓝 {b} / 1800）")
    return f"选 .zip 被拒；导入、选用是那张图；导出 {files[0]}；删除后回到默认（亮度 {lum:.0f}）；导入后选用是那张图"




case("ST-35", needs="jvm:SettingsMenuUatTest.themesAreImportedAndOnesOwnExportedOrDeletedFromALongPress、MonetKeyStyleTest")(lambda d: None)


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


case("ST-15", needs="jvm:SettingsEffectUatTest.swipeUpThenDisabled、KeySwipeTest.bothOffLeavesTheKeyAsItWas")(lambda d: None)


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
    subprocess.run(["ffmpeg", "-loglevel", "quiet", "-i", os.path.join(tmp, "r.mp4"), "-fps_mode", "passthrough",
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
    def comma(s):
        return next((n for n in s.app() if n.text == ","), None)
    try:
        n = d.wait(comma, 10)   # 5 s ran out with four emulators busy at once
    except Blocked:   # seen in PR-10: left on 通用输入 after the tap on 中文标点; once more
        home(d, "通用输入", "中文标点")
        n = None
    try:
        n = n or d.wait(comma, 10)
    except Blocked:
        raise Blocked(f"中文标点里没有 ,：{d.top_activity()} {d.screen().texts()[:12]}")
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


@case("ST-48")
def st48(d):
    # the strip's touches: the IME window's touchable region holds it, and not the app's room above
    # or beside it; the rest of the case (where it is, a tap moving the cursor) is the JVM's
    d.type("nihao")
    s = d.screen()
    row = d.code_row(s)
    check(row is not None, "打字时没有编码行")
    x0, y0, x1, y1 = row.b
    region = d.ime_touchable_region()
    gap = int(20 * d.dpx())
    inside = region_has(region, (x0 + x1) // 2, (y0 + y1) // 2)
    above = region_has(region, (x0 + x1) // 2, y0 - gap)
    beside = region_has(region, x1 + gap, (y0 + y1) // 2)
    d.clear()
    after = region_has(d.ime_touchable_region(), (x0 + x1) // 2, (y0 + y1) // 2)
    # outside the keyboard: the band the keyboard takes, its full width, starts at the strip's bottom
    band = [r for r in region if r[0] <= 0 and r[2] >= x1 + gap]
    check(band and abs(min(r[1] for r in band) - y1) <= 2, f"浮条 {row.b} 不在键盘 {band} 上面")
    check(inside and not above and not beside and not after,
          f"浮条上 {inside}，上方 {above}，右边 {beside}，消失后 {after}")
    return f"浮条 {row.b}，触摸区只在浮条和键盘"


def region_has(rects, x, y):
    return any(a <= x < c and b <= y < e for a, b, c, e in rects)


case("ST-49", needs="jvm:FloatingCodeStripUatTest.theSyllablesAreInTheStripBesideTheCode、FloatingCodeStripUatTest.theKeyboardIsAsHighOnEveryKeyboardAndLowerThanEmbedded")(lambda d: None)


case("ST-52", needs="jvm:ThemeLookScreenshotTest")(lambda d: None)


case("ST-50", needs="jvm:FloatingCodeStripUatTest.oneHandedTheStripIsAtTheBoardsLeft、FloatingCodeStripUatTest.floatingTheStripIsOutsideTheKeyboardAndUnderItAtTheTop")(lambda d: None)


case("ST-51", needs="jvm:CompactKeyboardUatTest、ZhuyinShortcutsUatTest、AutoScaleTextViewTest、KeyMarkYield、KeyCapsTest、CompactKeyboardScreenshotTest")(lambda d: None)


case("ST-54", needs="jvm:ThemeFilesManagerTest.aThemeOfALaterFormatIsSkippedAndLeftAsItIs、ThemeFilesManagerTest.aThemeWithAFileMissingIsRefusedAndNotListed、ThemeFilesManagerTest.whatThisAppAndOneBefore30ExportedIsImported")(lambda d: None)


case("ST-55", needs="jvm:ThemeFilesManagerTest.aThemeWhosePictureOrFontIsGoneIsNotListedAndTheUserIsToldItIsGone")(lambda d: None)


case("ST-56", needs="manual:主题制作网页 0.6.0 起提供；在电脑浏览器上手工跑（要一个和内置主题同名的主题文件）")(lambda d: None)
case("ST-57", needs="manual:分屏下主题页预览和键盘同时要毛玻璃图")(lambda d: None)
case("ST-58", needs="manual:要一个平铺大圆角主题文件（从 from-app.theme 改出来，见用例），键角、边框、间距、候选栏要人眼看")(lambda d: None)


case("ST-60", needs="jvm:PacksUatTest.withoutTheTextPackTheKeyboardAsksForItAndATapOpensThePage")(lambda d: None)


case("ST-61", needs="jvm:PacksUatTest.aTextPackTooOldSaysWhichVersionIsNeeded")(lambda d: None)


case("ST-62", needs="jvm:PacksUatTest.thePageListsEachPacksStateAndWhatItIsFor")(lambda d: None)


case("ST-63", needs="release,scripted:发布包每次重置都经文件选择器导入文字包（import_packs_ui）；结果的说法在 JVM 上：PacksUatTest.eachFilesOutcomeIsToldAndTheUserMayDeleteTheirFiles")(lambda d: None)


case("ST-64", needs="release:要上一版（0.5.x）的包覆盖安装；方法见 dev/design/060-asset-packs.md 的实现记录")(lambda d: None)


case("ST-65", needs="jvm:PacksUatTest.aPackDamagedSinceItsImportIsSaidSoAndImportedAgainMended")(lambda d: None)


@case("ST-66", base="B0-设置")
def st66(d):
    out = []
    for tab, last in (("大小与样式", "禁用动画"), ("主题", None)):
        appearance_tab(d, tab)
        for _ in range(12):
            if not d.scroll():
                break
        s = d.screen()
        # the navigation bar's window (its nodes are not in the dump): the accessibility service's list
        nav = re.search(r"title=(?:导航栏|Navigation bar)[^\n]*?bounds=Rect\(\d+, (\d+)", d.sh("dumpsys accessibility"))
        if not nav:
            raise Blocked("没有导航栏")
        top = int(nav.group(1))
        if last:
            r = d.row(last, s)
            check(r is not None, f"滚到底看不到「{last}」")
            bottom = r.b[3]
        else:   # the theme list: its lowest item
            bottom = max(n.b[3] for n in s.app() if n.clickable and n.pkg == PKG)
        check(bottom <= top + 2, f"{tab}：最后一行底 {bottom} 在导航栏顶 {top} 下面")
        out.append(f"{tab} 最后一行底 {bottom} ≤ 导航栏顶 {top}")
    return "；".join(out)
