"""uat/14-settings.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import re
import shutil
import subprocess
import tempfile
import time
import zipfile

from uat import (PKG, Blocked, Fail, case, check, choose, gear, home, in_picker, pinyin_keyboard, script, to_downloads)


def debuggable(d, what):
    """the debug build only: a release build is not debuggable, `run-as` there prints its refusal to stderr and
    nothing to stdout, so a check on its output passes or fails for nothing (0.6.1 release run: ST-143, ST-164, ST-175)"""
    import uat
    if uat.BUILD == "release":
        raise Blocked(f"正式包不能 run-as（{what}）：在 debug 包上跑")


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
case("ST-134", needs="jvm:StrokeUatTest.theSensitivityIsReadAtEachTouch")(lambda d: None)
case("ST-135", needs="jvm:InputMethodSettingsScreenTest.theStrokeRowsAreThereWhileStrokesAreDrawn")(lambda d: None)


# ---------------------------------------------------------------- 外观


case("ST-05", needs="jvm:SettingsUatTest.aDarkThemeTurnsPreviewAndKeyboardDark")(lambda d: None)


THEME_ZIP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "web", "theme-maker", "test", "testdata",
                         "from-app.theme")


def push_download(d, local_path, name):
    """[local_path] into 下载 as <name>.theme (older copies of it gone), scanned so the file picker lists it"""
    d.sh(f"rm -f /sdcard/Download/{name}*.theme")
    d.adb("push", local_path, f"/sdcard/Download/{name}.theme")
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/{name}.theme")
    time.sleep(1)


def push_theme(d):
    """a theme file (.theme, a zip inside) in 下载: the testdata theme (3.0) with a striped picture,
    drawn sharp and at full brightness so the keyboard shows its orange and blue"""
    import io
    import json
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
    push_download(d, p, "uat-theme")


def import_theme(d, name, confirm=True):
    """新增主题 → 导入主题文件 → the file [name] in the system's picker (it opens at 下载 or the recent ones);
    a 4.0 pack then asks 导入主题 <name>？, answered 导入 unless [confirm] is False (the dialog is left open)"""
    appearance_tab(d, "主题")
    d.tap_text("新增主题", after=3.0)
    s = d.screen()
    # 0.6.0's 新增主题 asks first: a file to import, or the web page to make one (M5)
    if s.find(text="导入主题文件"):
        d.tap(s.find(text="导入主题文件").c, 3.0)
        s = d.screen()
    n = next((k for k in s.app() if k.text and k.text.startswith(name)), None)
    if not n and s.find(text="下载"):
        d.tap(s.find(text="下载").c, 2.0)
        s = d.screen()
        n = next((k for k in s.app() if k.text and k.text.startswith(name)), None)
    if not n:
        raise Blocked(f"文件选择里没有 {name}：{s.texts()[:8]}")
    d.tap(n.c, 3.0)
    # a 4.0 pack (what 导出 writes since T8a) is checked and asked first: 导入主题 <name>？ 导入
    s = d.screen()
    if confirm and any(t.startswith("导入主题") and t.endswith("？") for t in s.texts("all")):
        d.tap_text("导入", where="all", scroll=False, after=3.0)


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
    d.switch("跟随系统深色模式", False)
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
    # the pushed file is a 3.0 theme: it goes straight in, only a 4.0 pack asks first
    import_theme(d, "uat-theme", confirm=False)
    # looked at once after a pause, not waited for: wait_text blocks when the text never comes, which is the pass;
    # the 4.0 dialog's title is 导入主题 <name>？ (the 新增主题 sheet's 导入主题文件 is not it)
    time.sleep(2)
    check(not any(t.startswith("导入主题") and t.endswith("？") for t in d.screen().texts("all")),
          "导入 3.0 主题弹出了确认框")
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
    d.step("取消")
    # the exported file is a 4.0 pack: it asks 导入主题 <name>？ first
    import_theme(d, files[0], confirm=False)
    check(d.wait_text("导入主题", timeout=5, where="all", contains=True), "选导出的 .theme 没有弹出导入确认框")
    d.tap_text("取消", where="all", scroll=False, after=2.0)
    appearance_tab(d, "主题")
    check(not custom_item(d), "确认框点了取消，主题列表里多了主题")
    d.step("再导入")
    import_theme(d, files[0])
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.open_page("text")
    o, b = kb_picture(d)
    check(o > 40 and b > 40, f"选用导入的主题后键盘背景不是那张图（橙 {o} 蓝 {b} / 1800）")
    return f"选 .zip 被拒；确认框取消不留；导入、选用是那张图；导出 {files[0]}；删除后回到默认（亮度 {lum:.0f}）；导入后选用是那张图"




def push_file(d, local_path, filename):
    """[local_path] into 下载 as [filename], scanned so the file picker lists it"""
    d.sh(f"rm -f '/sdcard/Download/{filename}'")
    d.adb("push", local_path, f"/sdcard/Download/{filename}")
    # adb push keeps the file's own mtime: an old one sorts it out of the picker's recent list
    d.sh(f"touch '/sdcard/Download/{filename}'")
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file:///sdcard/Download/{filename}'")
    time.sleep(1)


def baidu_skin(path, finder=False):
    """a Baidu skin made here (no third party's skin in the repository): a blue panel, letters on an orange
    face with a transparent margin (the gap between keys), named UAT 皮肤 by uat. [finder]: zipped as macOS
    Finder zips a folder (all in one top folder), with a __MACOSX/ copy of every file's ._ resource fork beside it"""
    import io
    from PIL import Image, ImageDraw

    def png(im):
        out = io.BytesIO()
        im.save(out, "PNG")
        return out.getvalue()

    face = Image.new("RGBA", (162, 180), (0, 0, 0, 0))
    ImageDraw.Draw(face).rounded_rectangle((6, 6, 155, 173), 18, fill=(250, 120, 30, 255))
    letters = "".join(f"[KEY{i}]\nVIEW_RECT={(i % 10) * 108},{i // 10 * 150},108,150\nBACK_STYLE=1\nFORE_STYLE=3\nCENTER={c}\n"
                      for i, c in enumerate("qwertyuiopasdfghjklzxcvbnm"))
    files = {
        "Info.txt": "Name=UAT 皮肤\nAuthor=uat\n",
        "demo.png": png(Image.new("RGB", (300, 200), (30, 90, 220))),
        "res/keys.png": png(face),
        "res/keys.til": "[IMG1]\nSOURCE_RECT=0,0,162,180\nINNER_RECT=30,30,102,120\n",
        "res/panel.png": png(Image.new("RGB", (108, 60), (30, 90, 220))),
        "res/panel.til": "[IMG1]\nSOURCE_RECT=0,0,108,60\n",
        "port/res/default.css": "[STYLE1]\nNM_IMG=keys,1\nHL_COLOR=cccccc\n[STYLE2]\nNM_COLOR=aabbcc\n"
                                "[STYLE3]\nNM_COLOR=222222\n[STYLE4]\nNM_IMG=panel,1\n",
        "port/gen.ini": "[PANEL]\nSIZE=1080,600\nBACK_STYLE=4\n[CAND]\nVIEW_RECT=0,0,1080,126\n",
        "port/cand1.cnd": "[CAND]\nFORE_STYLE=3\n",
        "port/py_26.ini": letters + "[KEY90]\nBACK_STYLE=2\nFORE_STYLE=3\nCENTER=F38\n",
    }
    # Finder zips the folder itself: every file under one top folder, its ._ copy under __MACOSX/ the same way
    top = "UAT skin/" if finder else ""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(top + name, data)
            if finder:
                head, _, base = (top + name).rpartition("/")
                z.writestr(f"__MACOSX/{head}/._{base}", b"\x00\x05\x16\x07" + bytes(80))


def skin_colours(d):
    """how much of the keyboard is the skin's orange (the keys) and blue (the panel)"""
    x0, y0, x1, y1 = kb_box(d)
    px = list(d.png().crop((x0, y0, x1, y1)).convert("RGB").resize((60, 30)).getdata())
    return (sum(1 for r, g, b in px if r > 200 and 90 < g < 150 and b < 70),
            sum(1 for r, g, b in px if b > 180 and r < 70 and 60 < g < 120))


def face_seams(d, label):
    """pixels on lines from the key [label]'s centre out to its face's edges (left, right, up, down) that lean from
    the face's colour toward the panel's, in runs of 1-2 px with face on both sides: a seam between two nine-slice
    pieces lets the panel show through as a thin line. Each half-line walks out until the gap round the key (4 or
    more pixels near the panel's colour) or a key and a half's pitch, so it crosses the seams near the face's edges
    whatever the key's width (退格 is one and a half letters wide). The face's colour is the line's middle, so a
    pressed (darker) face is judged alike; the panel's is the gap between g and h. A pixel off the face-panel line
    (a letter, an icon) is not a seam"""
    # at standard height only: the walk stops at a gap of 4 px or more, and 紧凑's ~1 dp vertical gap would read
    # as a seam of the next key
    k = d.keys(refresh=True)
    missing = [n for n in (label, "h", "g", "t") if n not in k]
    check(not missing, f"量键面拼缝要的键不在屏幕上：{missing}")
    cx, cy = (int(v) for v in k[label])
    px_, py_ = int(abs(k["h"][0] - k["g"][0])), int(abs(k["g"][1] - k["t"][1]))
    im = d.png().convert("RGB")
    w, h = im.size
    mid = [im.getpixel((x, cy)) for x in range(max(0, cx - px_ // 4), min(w, cx + px_ // 4))]
    face = [sorted(p[c] for p in mid)[len(mid) // 2] for c in range(3)]
    panel = im.getpixel((int((k["g"][0] + k["h"][0]) / 2), int(k["g"][1])))
    v = [panel[c] - face[c] for c in range(3)]
    vv = sum(a * a for a in v) or 1

    def toward(p):   # 0 the face, 1 the panel; None off the line between them
        dlt = [p[c] - face[c] for c in range(3)]
        t = sum(dlt[c] * v[c] for c in range(3)) / vv
        off = sum((dlt[c] - t * v[c]) ** 2 for c in range(3)) ** 0.5
        return t if off < 0.08 * vv ** 0.5 + 8 else None

    bad = []
    for dx, dy, reach in ((1, 0, px_), (-1, 0, px_), (0, 1, py_), (0, -1, py_)):
        run, gap = [], 0
        for i in range(1, int(1.5 * reach)):
            x, y = cx + dx * i, cy + dy * i
            if not (0 <= x < w and 0 <= y < h):
                break
            p = im.getpixel((x, y))
            t = toward(p)
            gap = gap + 1 if t is not None and t > 0.6 else 0
            if gap >= 4:   # the gap round the key: the face ends here
                break
            if t is not None and t > 0.12:
                run.append(p)
            else:
                if 0 < len(run) <= 2:
                    bad += run   # a thin line leaning to the panel, face on both sides: a seam
                run = []
    return bad


def real_skins(d, folder):
    """each skin in [folder] imported: one taken is shot on the 26 keys, the nine keys and the symbols, then
    deleted; one refused, its reason shot. The pictures are for a person to look at"""
    seen = []
    for f in sorted(os.listdir(folder)):
        if not f.lower().endswith((".bds", ".bdi", ".ssf", ".flex", ".zip", ".yaml")):
            continue
        d.step(f"真皮肤 {f}")
        ext = f.rsplit(".", 1)[1].lower()
        stem = f"uat-real-{len(seen)}"
        push_file(d, os.path.join(folder, f), f"{stem}.{ext}")
        import_theme(d, stem, confirm=False)
        time.sleep(2)
        s = d.screen()
        if not any(t.startswith("导入主题") and t.endswith("？") for t in s.texts("all")):
            d.shot("full", f"{f}：拒绝")
            seen.append(f"{f} 拒绝")
            d.back(after=1.0)
            d.sh(f"rm -f /sdcard/Download/{stem}.{ext}")
            continue
        d.shot("full", f"{f}：确认框")
        d.tap_text("导入", where="all", scroll=False, after=3.0)
        appearance_tab(d, "主题")
        item = custom_item(d)
        check(item, f"{f} 导入后主题列表里没有它")
        d.tap(item.c, 1.5)
        d.open_page("text")
        d.shot("kb", f"{f}：26 键")
        pinyin_keyboard(d, "九键")
        d.open_page("text")
        d.shot("kb", f"{f}：九键")
        pinyin_keyboard(d, "全键盘")
        d.open_page("text")
        s = d.screen()
        n = s.find(text="符", where="ime") or s.find(desc="符号", where="ime")
        if n:
            d.tap(n.c, 0.8)
            d.shot("kb", f"{f}：符号")
        delete_custom(d)
        d.sh(f"rm -f /sdcard/Download/{stem}.{ext}")
        seen.append(f"{f} 收下")
    return seen


@case("ST-101", base="B0-设置", mutates=True)
def st101(d):
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    p = os.path.join(tempfile.gettempdir(), f"uat-skin-{d.port}.bds")
    baidu_skin(p)
    push_file(d, p, "uat-skin.bds")
    os.remove(p)
    d.step("导入皮肤")
    import_theme(d, "uat-skin", confirm=False)
    check(d.wait_text("导入主题 UAT 皮肤", timeout=8, where="all", contains=True), "选 .bds 没有弹出导入确认框")
    s = d.screen()
    texts = " ".join(s.texts("all"))
    d.shot("full", "皮肤的确认框")
    check("uat" in texts, "确认框里没有作者 uat")
    check("从别的输入法的皮肤转换而来" in texts and "按键的位置和大小" in texts, f"确认框没列出没转过来的：{texts[:200]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.open_page("text")
    d.shot("kb", "选用转换来的皮肤")
    # held down for the shot: a pressed key darker though the skin has no pressed face (space by hand: held, it listens).
    # The shot takes about a second, past the long-press time: z's long-press bubble may show too, and what the
    # release types is cleared below. z's press bubble covers its face, so the pressed face's seams are measured on
    # 退格, which shows no bubble (held, it deletes: the field is cleared after)
    seams = face_seams(d, "g")
    check(not seams, f"g 的键面上有九宫格拼缝（偏蓝的像素 {seams[:5]}）")
    z = d.keys(refresh=True)["z"]
    d.touch(("down", z[0], z[1]))
    try:
        d.shot("kb", "按住 z：变暗（长按气泡可能也弹出）")
    finally:
        d.touch(("up", z[0], z[1]))
    bk = d.keys(refresh=True)["bksp"]
    d.touch(("down", bk[0], bk[1]))
    try:
        pressed = face_seams(d, "bksp")
    finally:
        d.touch(("up", bk[0], bk[1]))
    d.clear()
    check(not pressed, f"按住的退格键面上有九宫格拼缝（偏蓝的像素 {pressed[:5]}）")
    o, b = skin_colours(d)
    check(o > 300 and b > 40, f"键盘不是皮肤的橙键蓝底（橙 {o} 蓝 {b} / 1800）")
    d.step("紧凑高度的键缝")
    v0, h0 = key_gaps(d)
    appearance_tab(d, "大小与样式")
    choose(d, "键盘高度", "紧凑")
    d.open_page("text")
    v1, h1 = key_gaps(d)
    d.shot("kb", "皮肤：紧凑")
    gaps = f"上下/左右缝 dp：标准 {v0}/{h0}，紧凑 {v1}/{h1}"
    # about four tenths (BaiduSkin's 2 dp a side to Gap.compact's 1), a pixel's rounding either way
    check(1 <= v1 <= v0 * 0.6 and abs(h1 - h0) <= 1.6, f"紧凑时皮肤的上下缝没缩到约四成或左右缝变了：{gaps}")
    appearance_tab(d, "大小与样式")
    choose(d, "键盘高度", "标准")
    d.step("Finder 打包的 zip")
    mac = os.path.join(tempfile.gettempdir(), f"uat-skin-mac-{d.port}.zip")
    baidu_skin(mac, finder=True)
    push_file(d, mac, "uat-skin-mac.zip")
    os.remove(mac)
    import_theme(d, "uat-skin-mac", confirm=False)
    check(d.wait_text("导入主题 UAT 皮肤", timeout=8, where="all", contains=True), "带 __MACOSX/ 的 zip 没有弹出导入确认框")
    d.shot("full", "Finder 打包的 zip 的确认框")
    d.back(after=1.0)
    d.step("坏的皮肤")
    bad = os.path.join(tempfile.gettempdir(), f"uat-bad-{d.port}.ssf")
    with open(bad, "wb") as out:
        out.write(b"Skin\x03\x00" + os.urandom(2000))
    push_file(d, bad, "uat-bad.ssf")
    os.remove(bad)
    import_theme(d, "uat-bad", confirm=False)
    check(d.wait_text("这个搜狗皮肤是加密的", timeout=8, where="all", contains=True), "选加密的 .ssf 没有说原因")
    d.shot("full", "坏皮肤的提示")
    d.back(after=1.0)
    appearance_tab(d, "主题")
    check(custom_item(d) and custom_item(d).texts()[:1] == item.texts()[:1], "选了坏皮肤后主题列表变了")
    real = os.environ.get("UAT_SKINS")
    extra = real_skins(d, real) if real else []
    d.sh("rm -f /sdcard/Download/uat-skin.bds /sdcard/Download/uat-skin-mac.zip /sdcard/Download/uat-bad.ssf")
    return f"皮肤收下、确认框列出没转过来的、选用是橙键蓝底（橙 {o} 蓝 {b}）、键面没有拼缝；{gaps}；Finder 的 zip 也认；坏皮肤说是加密的" + \
        (f"；真皮肤：{'、'.join(extra)}" if extra else "")


case("ST-35", needs="jvm:SettingsMenuUatTest.themesAreImportedAndOnesOwnExportedOrDeletedFromALongPress、MonetKeyStyleTest")(lambda d: None)


case("ST-36", needs="jvm:SettingsMenuUatTest.followingTheSystemAThemeTappedTakesBothSlots、ThemeSlotsTest")(lambda d: None)


case("ST-10", needs="jvm:SettingsEffectUatTest.keyboardHeightFollowsTheSetting、KeyboardSizesTest.turnedTheRowIsTheSameOnEveryScreenOfAKind")(lambda d: None)
case("ST-141", needs="manual:横屏的主题预览和键盘比高度（常见手机和折叠屏、三键和手势导航）；预览用的可用高度由 KeyboardAreaTest.theRoomPredictedLeavesTheStatusBarAndTheInputMethodsBarOut 查")(lambda d: None)


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


case("ST-13", needs="jvm:SettingsEffectUatTest.swipeUpPositionNoneThenBottom")(lambda d: None)


# ---------------------------------------------------------------- 按键

case("ST-14", needs="jvm:SettingsEffectUatTest.noPopupOnKeyPress")(lambda d: None)


case("ST-15", needs="jvm:SettingsEffectUatTest.swipeUpThenDisabled、KeySwipeTest.bothOffLeavesTheKeyAsItWas")(lambda d: None)


case("ST-16", needs="jvm:SettingsEffectUatTest.aLongerLongPressDelay")(lambda d: None)


case("ST-17", needs="jvm:SettingsEffectUatTest.holdingTheSpaceGoesToTheNextInputMethod、SpaceLongPressNextImTest")(lambda d: None)


case("ST-18", needs="jvm:SettingsEffectUatTest.spaceSwipeOffLeavesTheCursor")(lambda d: None)


case("ST-19", needs="jvm:SettingsEffectUatTest.bracketsUnpairedWhenOff")(lambda d: None)


case("ST-20", needs="jvm:SettingsEffectUatTest.noLanguageKeyAWiderSpace")(lambda d: None)


case("ST-21", needs="jvm:SettingsEffectUatTest.aCharacterMadeFirstIsTheCornerAndTheSwipe")(lambda d: None)


case("ST-22", needs="jvm:SettingsEffectUatTest.charactersAddedThenTheDefaultBack、KotlinHostKeyboardTest.aCircledLetterLongPressedOnTheFourteenKeysIsTypedAsText、KotlinHostKeyboardTest.aParenthesisedDigitLongPressedOnShuangpinsNineKeysIsTypedAsText、PopupOverridesFragmentTest$Dialog.aKeyCodeAloneIsRefusedAndTheDialogStays、KeyCapsTest.theEnginesCapFadesItsWordsAloneAndOutlinesItsFace")(lambda d: None)


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
    script("05-toolbar").copy_word(d, "公园", at_copy=lambda: d.shot("full", "禁用动画，复制后的提示"))
    # Android's own 移除动画 does the same with the setting off
    d.step("系统移除动画")
    appearance_tab(d, "大小与样式")
    d.switch("禁用动画", False)
    d.sh("settings put global animator_duration_scale 0")
    try:
        d.open_page("text")
        sys0, _ = record_toolbar(d)
        script("05-toolbar").copy_word(d, "公园", at_copy=lambda: d.shot("full", "系统移除动画，复制后的提示"))
    finally:
        d.sh("settings put global animator_duration_scale 1")
    check(sys0 <= 1, f"系统移除动画后工具栏仍有 {sys0} 个过渡帧")
    return (f"过渡帧：工具栏 开着 {on}、禁用后 {off}、系统移除动画 {sys0}；剪贴板面板 开着 {pon}、禁用后 {poff}；"
            "复制的提示看图")


case("ST-28", needs="jvm:SettingsUatTest.everyInputMethodsPageAndTheSharedOnes")(lambda d: None)


case("ST-145", needs="jvm:HostConfigDirTest.anEditedPunctuationTableIsDeletedOnceOnTheUpgrade")(lambda d: None)


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


case("ST-43", needs="jvm:AppPrefsTest.theTwoSwitchesMoveOverToKeyDrawing")(lambda d: None)


case("ST-44", needs="jvm:AddressSettingsUatTest.theAddressPageItsListsAndRememberedAddresses")(lambda d: None)


case("ST-45", needs="jvm:SettingsUatTest.theNineKeysSettingsAreASectionOfEachKeyboardsPage")(lambda d: None)

case("ST-46", needs="jvm:SettingsUatTest.keyDrawingIsOneListOfItsChoices")(lambda d: None)

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


case("ST-56", needs="manual:主题制作网页 0.6.0 起提供；在电脑浏览器上手工跑（改名成内置主题的名字再保存）")(lambda d: None)

case("ST-76", needs="manual:主题制作网页；在电脑浏览器上手工跑（要手改包里的 theme.xml 和特效图片）")(lambda d: None)
case("ST-57", needs="manual:分屏下主题页预览和键盘同时要毛玻璃图")(lambda d: None)
case("ST-58", needs="manual:要一个平铺大圆角主题文件（从 from-app.theme 改出来，见用例），键角、边框、间距、候选栏要人眼看")(lambda d: None)


case("ST-60", needs="jvm:PacksUatTest.withoutTheTextPackTheKeyboardAsksForItAndATapOpensThePage")(lambda d: None)


case("ST-61", needs="jvm:PacksUatTest.aTextPackTooOldSaysWhichVersionIsNeeded")(lambda d: None)


case("ST-62", needs="jvm:PacksUatTest.thePageListsEachPacksStateAndWhatItIsFor")(lambda d: None)
case("ST-68", needs="manual:要另做一个同名、换了字体和背景图的主题文件，看键盘的字体")(lambda d: None)

case("ST-75", needs="manual:要在制作页做同名的单版本和两版本主题包")(lambda d: None)

case("ST-77", needs="manual:要另做一个回车键自带图片、按下纯色的 3.0 主题文件，导入后按住看")(lambda d: None)
case("ST-78", needs="manual:要在 API 31+ 上换壁纸改系统取色，看键色")(lambda d: None)
case("ST-79", needs="manual:要另做一个只给九键回车键换图片的主题包")(lambda d: None)
case("ST-80", needs="manual:主题制作网页；在电脑浏览器上手工跑（看 SVG 图标的位置）")(lambda d: None)
case("ST-81", needs="manual:主题制作网页；在电脑浏览器上手工跑（关页重开、两个标签页的草稿）")(lambda d: None)
case("ST-69", needs="manual:要真麦克风说话，用过语音后再导入语音包")(lambda d: None)
case("ST-70", needs="manual:按键字符里选中一项左移、删除，看轮廓和删掉的是哪个")(lambda d: None)
case("ST-73", needs="manual:要从制作页导出动效示例主题「薄荷（动效）」，按键的缩放、下沉、渐隐要人眼看")(lambda d: None)
case("ST-74", needs="manual:要导入 0.6.0 的薄荷 17 键旧主题包（mint-k17 测试数据），按它的分组打拼音、看固定的词")(lambda d: None)


case("ST-63", needs="release:多选文字包、语音包和一个非包文件导入，读逐文件的结果框、看下载里的原文件还在；结果框出现时转屏只一个框，点确定后再转屏、切到别的应用再回来不再弹出（手工）；结果的说法在 JVM 上：PacksUatTest.eachFilesOutcomeIsToldAndTheUserMayDeleteTheirFiles")(lambda d: None)


case("ST-67", needs="release:覆盖安装 0.5.x→0.6.0 和恢复 0.5.x 的备份，发版时的升级检查里手工跑；JVM 见 ThemePrefsMigrationTest、ThemePrefsTest、KeyboardAreaTest")(lambda d: None)


case("ST-64", needs="release:要上一版（0.5.2）的包覆盖安装、它导出的备份带学过的词；方法见 dev/design/060-asset-packs.md 的实现记录")(lambda d: None)


case("ST-65", needs="jvm:PacksUatTest.aPackDamagedSinceItsImportIsSaidSoAndImportedAgainMended")(lambda d: None)


def key_gaps(d):
    """(up and down, across) dp between the full keyboard's key faces: w over s, q beside w, read off the
    screen's pixels (a face's colour against the keyboard's between them; a key's 1 dp shadow counts as
    the gap's, within the tolerance)"""
    k = d.keys(refresh=True)
    if not all(c in k for c in "qws"):
        raise Blocked(f"全键盘上找不到 q、w、s：{sorted(k)[:12]}")
    im = d.png()
    (qx, qy), (wx, wy), (sx, sy) = k["q"], k["w"], k["s"]

    def far(a, b):
        return sum(abs(x - y) for x, y in zip(a, b)) > 24

    def gap(points):
        # from inside the first face to inside the second: the longest unbroken run that is not the first face's
        # colour (a stray line or mark on a face is a run of its own, not counted into the gap)
        face = im.getpixel(points[0])
        best = run = 0
        for p in points:
            run = run + 1 if far(im.getpixel(p), face) else 0
            best = max(best, run)
        if not best:
            raise Blocked("键面之间读不出缝")
        return best / d.dpx()
    # down a column in w's right half and s's left half (not their glyphs nor their corner marks), from below
    # w's middle to above s's
    x = (wx + sx) // 2
    up_down = gap([(x, y) for y in range(wy + (sy - wy) // 4, sy - (sy - wy) // 4)])
    # across a row below the letters, from q's right part to w's left
    y = qy + (sy - qy) // 4
    across = gap([(x2, y) for x2 in range(qx + (wx - qx) // 4, wx - (wx - qx) // 4)])
    return round(up_down, 1), round(across, 1)


@case("ST-95", base="B0-设置", mutates=True, visual=True)
def st95(d):
    d.open_page("text")
    v0, h0 = key_gaps(d)
    d.shot("kb", "标准高度")
    appearance_tab(d, "大小与样式")
    texts = d.all_texts()
    check("横向间距" in texts and "纵向间距" in texts, "按键样式里没有横向间距、纵向间距")
    check("按键间距" not in texts, "还有一项「按键间距」")
    choose(d, "键盘高度", "紧凑")
    d.open_page("text")
    v1, h1 = key_gaps(d)
    d.shot("kb", "紧凑：全键盘")
    d.step("九键")
    pinyin_keyboard(d, "九键")
    d.open_page("text")
    d.shot("kb", "紧凑：九键")
    pinyin_keyboard(d, "全键盘")
    d.step("纵向间距宽松")
    appearance_tab(d, "大小与样式")
    choose(d, "纵向间距", "宽松")
    d.open_page("text")
    v2, h2 = key_gaps(d)
    d.shot("kb", "紧凑，纵向宽松")
    d.step("横向间距宽松")
    appearance_tab(d, "大小与样式")
    choose(d, "横向间距", "宽松")
    d.open_page("text")
    v3, h3 = key_gaps(d)
    d.shot("kb", "紧凑，纵向、横向都宽松")
    seen = f"上下/左右缝 dp：标准 {v0}/{h0}，紧凑 {v1}/{h1}，纵向宽松 {v2}/{h2}，横向也宽松 {v3}/{h3}"

    def near(got, want):
        return abs(got - want) <= 1.6
    check(near(v0, 14) and near(h0, 6), f"标准高度的缝不是约 14/6：{seen}")
    check(near(v1, 6) and near(h1, 6), f"紧凑时的缝不是约 6/6：{seen}")
    check(near(v2, 8) and near(h2, 6), f"纵向宽松后的缝不是约 8/6：{seen}")
    check(near(v3, 8) and near(h3, 10), f"横向也宽松后的缝不是约 8/10：{seen}")
    return seen


@case("ST-160", base="B0-设置", mutates=True, visual=True)
def st160(d):
    appearance_tab(d, "大小与样式")
    d.tap_text("纵向间距", after=0.2, owe=None)
    box = d.wait(lambda s: s.find(rid="select_dialog_listview"), 10, step=0.15)
    shown = [n.text for n in box.walk() if n.text]
    d.back()
    levels = ["跟随主题", "很紧凑", "紧凑", "标准", "宽松"]
    check(shown == levels, f"纵向间距的档不是{levels}：{shown}")
    choose(d, "纵向间距", "很紧凑")
    choose(d, "横向间距", "紧凑")
    d.open_page("text")
    v0, h0 = key_gaps(d)
    d.shot("kb", "纵向很紧凑、横向紧凑")
    appearance_tab(d, "大小与样式")
    choose(d, "键盘高度", "紧凑")
    d.open_page("text")
    v1, h1 = key_gaps(d)
    d.shot("kb", "键盘高度紧凑")
    seen = f"上下/左右缝 dp：标准高度 {v0}/{h0}，紧凑高度 {v1}/{h1}"

    def near(got, want):
        return abs(got - want) <= 1.6
    check(near(v0, 4) and near(h0, 4), f"很紧凑的缝不是约 4/4：{seen}")
    check(near(v1, 2) and near(h1, 4), f"紧凑高度的缝不是约 2/4：{seen}")
    return seen + "（横屏手工看）"


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
            bottom = max((n.b[3] for n in s.app() if n.clickable and n.pkg == PKG), default=None)
            if bottom is None:
                raise Blocked(f"{tab}：主题页上没有可点的项：{s.texts()[:6]}")
        check(bottom <= top + 2, f"{tab}：最后一行底 {bottom} 在导航栏顶 {top} 下面")
        out.append(f"{tab} 最后一行底 {bottom} ≤ 导航栏顶 {top}")
    return "；".join(out)


SCENE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "lib", "keyboard-scene", "src", "commonMain")


def push_layouts_pack(d):
    """uat-layouts.theme in 下载: Nord as a pack of its own with a 26 keys whose q types 你好, an 18 keys of
    another name and a k14 that does not read"""
    def read(*p):
        with open(os.path.join(SCENE, *p), encoding="utf-8") as f:
            return f.read()
    def edit(text, old, new):
        # a layout that changed under the script fails here, not as a wrong count in the dialog (q lost alt= once)
        assert old in text, f"the app's layout no longer has {old!r}"
        return text.replace(old, new, 1)
    qwerty = edit(edit(read("layouts", "qwerty.xml"), 'name="26 键"', 'name="主题 26 键"'),
                  'id="q" label="{case:q}" send="q"', 'id="q" label="{case:q}" send="commit:你好"')
    k18 = edit(read("layouts", "k18.xml"), 'name="18 键"', 'name="主题 18 键"')
    layouts = {"qwerty.xml": qwerty, "k18.xml": k18, "k14.xml": "<keyboard/>"}
    p = os.path.join(tempfile.gettempdir(), f"uat-layouts-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", edit(read("themes", "nord.xml"), 'name="nord"', 'name="uat-layouts"'))
        for n, t in layouts.items():
            z.writestr("layouts/" + n, t)
    push_download(d, p, "uat-layouts")


def answer_pack(d, with_layouts=True):
    """the open 导入主题 dialog answered 导入, its 一起导入 ticked or not (061-custom-layout §11)"""
    try:   # the dialog reads the pack first (加载中)
        box = d.wait(lambda x: x.find(text="一起导入", where="all"), 15, what="确认框里的「一起导入」")
    except Blocked:
        box = None
    check(box, f"确认框里没有「一起导入」：{d.screen().texts('all')[:10]}")
    if not with_layouts:
        d.tap(box.c, 0.5)
    d.tap_text("导入", where="all", scroll=False, after=3.0)


def types_q(d):
    """the q key on the test page: what reaches the field"""
    d.open_page("text")
    d.tap(d.keys(refresh=True)["q"], 1.0)
    text = d.field_text() or ""
    d.clear()
    return text


def use_theme(d):
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)


@case("ST-72", base="B0-设置", mutates=True)
def st72(d):
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    push_layouts_pack(d)
    d.step("导入")
    import_theme(d, "uat-layouts", confirm=False)
    # its 26 and 18 keys made layouts of the user's; k14 does not read; no keyboard stood for
    for told in ("这个主题带 2 个布局：uat-layouts · 主题 26 键、uat-layouts · 主题 18 键，导入后在 外观 → 布局 里。",
                 "缺键盘必需的键，或不是已有的键盘）：k14"):
        check(d.wait_text(told, timeout=8, where="all", contains=True), f"确认框没写「{told}」：{d.screen().texts('all')[:10]}")
    check(not d.screen().find(contains="此主题包含自定义布局", where="all"), "确认框仍说这个主题顶替键盘")
    answer_pack(d)
    use_theme(d)
    d.step("不再顶替")
    check("你好" not in types_q(d), "用这个主题时 q 仍打出主题 26 键的「你好」")
    d.step("布局页")
    check(layout_card(d, "uat-layouts · 主题 18 键"), "布局页没有「uat-layouts · 主题 18 键」")
    card = layout_card(d, "uat-layouts · 主题 26 键")
    check(card, "布局页没有「uat-layouts · 主题 26 键」")
    d.tap(card.c, 1.0)
    d.tap_text("用在…", where="all", scroll=False, after=1.5)
    check(d.wait_text("「uat-layouts · 主题 26 键」用在", timeout=5, where="all"), f"没出「用在」勾选：{d.screen().texts('all')[:10]}")
    d.tap_text("拼音", where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    got = types_q(d)
    check("你好" in got, f"拼音用 uat-layouts · 主题 26 键 时 q 没打出「你好」：{got!r}")
    gear(d, "拼音")
    d.tap_text("键盘", after=1.0)
    d.wait_text("注音全键盘", timeout=10, where="all")
    opts = d.screen().texts("all")
    d.back(after=0.5)
    check(not any("（主题：" in t for t in opts), f"键盘选项里还有「（主题：…）」：{opts[:12]}")
    check("uat-layouts · 主题 26 键（自定义）" in opts, f"键盘选项里没有「uat-layouts · 主题 26 键（自定义）」：{opts[:12]}")
    return "确认框列出 2 个布局、k14 不用、勾着一起导入；主题不顶替；布局页两张卡片；用在拼音后 q 打出你好"

SOUNDS_ICONS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures", "theme-sounds-icons")


def push_icons_pack(d, sounds=False):
    """uat-sounds-icons.theme in 下载, made of uat/fixtures/theme-sounds-icons: Nord with its own backspace, enter,
    shift and a star on the space key ([sounds]: its five key sounds too; without, its <sounds> left out)"""
    with open(os.path.join(SOUNDS_ICONS, "theme.xml"), encoding="utf-8") as f:
        xml = f.read()
    names = [n for n in os.listdir(SOUNDS_ICONS) if n.endswith(".svg")]
    if sounds:
        names += [n for n in os.listdir(SOUNDS_ICONS) if n.rsplit(".", 1)[-1] in ("wav", "ogg", "mp3") and n != "short.mp3"]
    else:
        xml = re.sub(r"(?m)^[ \t]*<sounds>.*?</sounds>\n", "", xml, flags=re.S)   # the element, not the comment that names it
    p = os.path.join(tempfile.gettempdir(), f"uat-sounds-icons-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", xml)
        for n in names:
            z.write(os.path.join(SOUNDS_ICONS, n), n)
    push_download(d, p, "uat-sounds-icons")


def reds(d, c, r=60):
    """the star's half-see-through red circle: pixels within [r] of [c] far redder than they are green or blue"""
    im = d.png().crop((c[0] - r, c[1] - r // 2, c[0] + r, c[1] + r // 2)).convert("RGB")
    return sum(1 for R, G, B in im.getdata() if R > G + 50 and R > B + 50)


@case("ST-90", base="B0-设置", mutates=True, visual=True)
def st90(d):
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    push_icons_pack(d)
    d.step("导入")
    import_theme(d, "uat-sounds-icons")
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.step("看键")
    d.open_page("text")   # no auto capitals: Shift unpressed
    space = d.keys(refresh=True).get("space")
    check(space, "键盘上找不到空格键（空格上画着星、没有字，靠 rid 或「空格」描述找）")
    n = reds(d, space)
    d.shot("full", "主题的退格、未按的 Shift，空格上的星")
    check(n > 30, f"空格键上没有星的红色圆（{n} 个红点）")
    d.open_page("long")   # a textarea: Return is 换行, the theme's enter
    d.shot("full", "多行框：回车是主题的宽箭头，等比缩小居中")
    d.open_page("text")
    k = d.keys(refresh=True)
    for name, note in (("bksp", "按住退格"), ("enter", "按住回车"), ("shift", "按住 Shift")):
        if name not in k:
            continue
        x, y = k[name]
        # the finger down while the screen is taken: a pressed key's look and its bubble, if the theme gives it one
        d.sh(f"input motionevent DOWN {x} {y}; sleep 0.15; screencap -p /sdcard/uat-p.png; input motionevent UP {x} {y}")
        d.shot("kb", note + "：按下的样子（有气泡时气泡上也是主题的图标）",
               png=d.adb("exec-out", "cat", "/sdcard/uat-p.png", binary=True))
    if "shift" in k:
        x, y = k["shift"]
        # the "按住 Shift" above outlasted the long-press delay, yet its release leaves Shift 单次 (seen on the
        # emulator), not 锁定; each tap waits out the double-tap window (= the long-press delay), else it is
        # a double tap and locks
        d.shot("kb", "按住 Shift 松开后：单次，自带图标（主题没换 shift-once）")
        time.sleep(1.0)
        d.tap((x, y), 0.6)
        d.shot("kb", "再点一次：未按，主题的箭头")
        time.sleep(1.0)
        d.tap((x, y), 0.6)
        d.shot("kb", "再点一次：单次，自带图标")
        time.sleep(1.0)
        d.tap((x, y), 0.6)   # back to 未按 for what follows
    if "enter" in k:
        x, y = k["enter"]
        # Return's in-keyboard menu (Emoji): drawn by the keyboard; its icon is emoji, which the theme does not replace
        d.touch(("down", x, y), ("sleep", 0.9))
        d.shot("full", "长按回车：键盘里画的菜单（Emoji 是自带图标，主题没换 emoji），菜单用主题的颜色")
        d.touch(("up", x, y))
        time.sleep(0.5)   # the menu left open: the next page's keyboard comes up afresh
    d.open_page("search")   # Return's action is search: the theme replaced enter only
    d.shot("full", "搜索框：回车是自带的搜索图标；工具栏是自带图标")
    d.step("深色")
    d.sh("cmd uimode night yes")
    try:
        time.sleep(1.5)
        d.open_page("long")
        d.keys(refresh=True)
        d.shot("full", "系统深色：这个主题的深色版，退格、回车、Shift、星仍是主题的")
    finally:
        # back to light even when a step above fails: the next case expects it
        d.sh("cmd uimode night no")
    time.sleep(1.5)
    d.step("换回内置")
    delete_custom(d)
    d.open_page("text")
    space = d.keys(refresh=True).get("space")
    check(space, "换回内置主题后键盘上找不到空格键")
    m = reds(d, space)
    d.shot("full", "内置主题，自带图标")
    check(m < 5, f"删掉主题后空格键上还有红色（{m} 个红点）")
    return f"空格上星的红点 {n}，换回内置后 {m}"


case("ST-82", needs="release:要 0.6.0 的语音包 v2（用不上，直接说）和一个比手机上旧、用得上的签名文字包（v1）；发版时在发布包上手工跑（问框、保留、替换、转屏）")(lambda d: None)
case("ST-83", needs="manual:要切系统深色模式并点主题卡；规则由 ThemePackListingTest 查，在模拟器或真机上手工跑")(lambda d: None)
case("ST-84", needs="manual:要从 from-app.theme 改出 monokai.theme；规则由 ThemePackImportTest 查，提示在设备上手工看")(lambda d: None)
case("ST-85", needs="release:要打 v0.6.0-beta1 标签构建的包；发测试版时跑，规则由 ThemePackMinAppTest 查")(lambda d: None)
case("ST-86", needs="manual:要听各类键的声音、看 logcat 的 theme sound；包用 push_icons_pack(d, sounds=True)")(lambda d: None)
case("ST-87", needs="manual:要听系统按键音和主题声音的区别；规则由 KeySoundChoiceTest 查")(lambda d: None)
case("ST-88", needs="manual:要逐个打 bad/ 里坏声音的主题包导入；规则由 SoundFileTest 查")(lambda d: None)
case("ST-94", needs="manual:要自己打一个 height=\"15%\" 的主题包导入；规则由 keyboard-scene 的主题读取查")(lambda d: None)
case("ST-89", needs="manual:要在 youmo29（API 29）和 API 34+ 上各走一遍，看 logcat 的 theme sound … status=0、真机上听")(lambda d: None)
case("ST-91", needs="manual:要逐个打 bad/ 里坏文件的主题包导入；规则由 SoundFileTest、SvgSubsetTest、ThemePackImportTest 查")(lambda d: None)
case("ST-92", needs="manual:主题制作网页；在电脑浏览器上手工跑（图标预览、按键音试听）")(lambda d: None)
case("ST-93", needs="manual:主题制作网页；在电脑浏览器上手工跑（选动画 WebP 做背景，导入 App 看）")(lambda d: None)


@case("ST-96", base="B0-设置")
def st96(d):
    """纠错's summary on the device: keys tapped off-centre read by where they land, 双拼's full keyboard the
    ü-key slip; no 不适用 line (注音九键 reads slips too since the user's 11-16)"""
    title = "纠正错拼和按偏的键"
    d.goto("通用输入")
    r = None
    for _ in range(8):
        s = d.screen()
        r = d.row(title, s)
        if r is not None and any(n.rid == "summary" for n in r.walk()):
            break
        if not d.scroll(s):
            break
    check(r is not None, f"通用输入里没有「{title}」：{d.screen().texts()[:10]}")
    summary = " ".join(n.text for n in r.walk() if n.rid == "summary" and n.text)
    check(summary.startswith("按在键的边上、靠着旁边哪个键，也按那个键读") and "双拼全键盘还认 j、q、x、y 后的 u 按成了 ü 的键" in summary,
          f"说明不对：{summary}")
    check("不适用" not in summary, f"说明里还有不适用：{summary}")
    d.shot("full", "纠正错拼和按偏的键的说明")
    return summary


# ---------------------------------------------------------------- 按键字符和快捷键：上滑、下滑两槽

def slots_page(d):
    home(d, "按键", "按键字符和快捷键")
    d.wait(lambda s: s.find(text="翻转上下滑"), 10)


def page_key(d, label):
    """a key on the page by what a screen reader calls it first (its label)"""
    s = d.screen()
    n = next((n for n in s.app() if n.clickable and re.split("[,，]", n.desc)[0] == label), None)
    if not n:
        raise Blocked(f"页上没有 {label} 键：{s.texts()[:12]}")
    return n


def slots_tab(d, tab):
    """the 按键字符和快捷键 page on the keyboard [tab] (全键盘 … 功能键): its card tapped, all eight on the first screen"""
    slots_page(d)
    s = d.screen()
    n = next((n for n in s.app() if n.cls.endswith("RadioButton") and n.text == tab), None)
    if not n:
        raise Blocked(f"按键字符和快捷键页上没有「{tab}」卡片：{[n.text for n in s.app() if n.cls.endswith('RadioButton')]}")
    d.tap(n.c, 1.5)


def key_dialog(d, label):
    """a key of the page tapped (found by the first part of what a screen reader says of it): its dialog up"""
    s = d.screen()
    # by prefix, not page_key's split at commas: the comma key reads 「，键, .」
    n = next((n for n in s.app() if n.clickable and (n.desc == label or n.desc.startswith(label + ","))), None)
    if not n:
        raise Blocked(f"页上没有 {label} 键：{[n.desc for n in s.app() if n.clickable and n.desc][:12]}")
    d.tap(n.c, 0.5)
    # 下滑's title row: every key's dialog has it, the last of its cards (so all three are laid out), open or
    # folded, said 下滑（已关闭） where that way is off
    return d.wait(lambda s: next((h for h in dialog_heads(s) if head_name(h) == "下滑"), None), 5, what=f"{label} 的对话框")


CARDS = ("长按菜单", "上滑", "下滑")


def head_name(n):
    """which of the key dialog's cards a title row is (its node says the title, then what is set)"""
    return next((c for c in CARDS if (n.desc or n.text).startswith(c)), None)


def dialog_heads(s):
    """the key dialog's cards' title rows on screen, top to bottom: the clickable rows said 长按菜单…, 上滑…, 下滑…"""
    return sorted((n for n in s.app() if n.clickable and head_name(n)), key=lambda n: n.b[1])


def dialog_card(d, title):
    """the key dialog's card [title] (长按菜单, 上滑, 下滑) open: one is where something to tap lies between its title
    row and the next one's (the last card's: 确定), only one open at a time"""
    def opened(s, head):
        heads = dialog_heads(s)
        nxt = next((h for h in heads if h.b[1] > head.b[1]), None)
        ok = s.find(text="确定")
        end = nxt.b[1] if nxt else (ok.b[1] if ok else 10 ** 6)
        return any(n.clickable and head.b[3] <= n.b[1] < end for n in s.app() if n is not head and not head_name(n))
    for _ in range(3):
        s = d.screen()
        head = next((h for h in dialog_heads(s) if head_name(h) == title), None)
        if not head:
            dialog_scroll(d)
            continue
        if opened(s, head):
            return
        d.tap(head.c, 0.8)
        s = d.screen()
        head = next((h for h in dialog_heads(s) if head_name(h) == title), None)
        if head and opened(s, head):
            return
    raise Blocked(f"对话框里打不开「{title}」：{[h.desc or h.text for h in dialog_heads(d.screen())]}")


def dialog_add_digits(d, text):
    """添加 [text] (digits, typed on the keyboard's 123 page: adb's input text is a hardware keyboard's keys,
    which turn the soft keyboard into its small 实体键盘 bar) to the open key dialog's list"""
    dialog_card(d, "长按菜单")
    s = d.screen()
    e = next((n for n in s.app() if n.cls.endswith("EditText") and n.text in ("", "添加字符，空格分隔")), None)
    if not e:
        raise Blocked("对话框里没有添加字符的框")
    d.tap(e.c, 0.8)
    k = d.keys(refresh=True)
    if "123" not in k:
        raise Blocked(f"键盘上没有 123：{sorted(k)[:20]}")
    d.tap(k["123"], 0.8)
    d.type(text)
    add = d.screen().find(text="添加")
    if not add:
        raise Blocked("对话框里没有「添加」")
    d.tap(add.c, 0.6)
    d.sh("input keyevent 4")   # the keyboard down: 确定 under it
    time.sleep(0.8)


def dialog_pick(d, item):
    """an item of the open dialog's list selected (by what it reads out)"""
    dialog_card(d, "长按菜单")
    s = d.screen()
    n = next((n for n in s.app() if n.clickable and n.desc == item), None)
    if not n:
        raise Blocked(f"对话框的列表里没有 {item}")
    d.tap(n.c, 0.4)


def dialog_way(d, way, option):
    """上滑 or 下滑's [option] (长按首选, 命令, 空, 文字) in the open dialog: its card opened, the only one whose
    choices are on screen"""
    dialog_card(d, way)
    s = d.screen()
    opts = [n for n in s.app() if n.cls.endswith("RadioButton") and n.text.startswith(option)]
    if not opts:
        raise Blocked(f"对话框里找不到{way}的「{option}」：{[n.text for n in s.app() if n.cls.endswith('RadioButton')]}")
    d.tap(opts[0].c, 0.4)


def dialog_text(d, way, pinyin, word):
    """上滑 or 下滑 set to the text [word], typed as [pinyin] and picked from the candidates"""
    ed = dialog_text_field(d, way)
    # at its right end, and what is in it backspaced first: the box opens with the text stored, and [word]
    # replaces it
    d.tap((ed.b[2] - 12, ed.c[1]), 0.8)
    if ed.text:
        bksp = d.keys(refresh=True).get("bksp")
        if not bksp:
            raise Blocked("键盘上没有退格")
        for _ in range(len(ed.text) + 2):
            d.tap(bksp, 0.15)
    d.type(pinyin)
    c = d.cands(want=word)
    if word not in c:
        raise Blocked(f"打 {pinyin} 候选里没有 {word}：{c[:5]}")
    d.pick(word)
    d.sh("input keyevent 4")
    time.sleep(0.8)


def dialog_text_field(d, way):
    """上滑 or 下滑 set to 文字: its field, the one on screen (the other cards folded)"""
    dialog_way(d, way, "文字")
    # where it stays: the dialog grows with the card opened and centres itself again a moment later, past the
    # Dumper's quiet wait; a field read before that was tapped below itself and 好 went in ahead of the old text
    # (0.6.1 release run: ST-106, KB-72)
    eds, last = [], None
    for _ in range(10):
        eds = [n for n in d.screen().app() if n.cls.endswith("EditText")]
        at = [n.b for n in eds]
        if at == last:
            break
        last = at
        time.sleep(0.3)
    if len(eds) != 1:
        raise Blocked(f"{way}的文字框不是屏上唯一的输入框：{[n.text for n in eds]}")
    return eds[0]


def dialog_note(d, way, test):
    """the texts of [way]'s card that pass [test] (its notes are at its end): the card opened, scrolled where none shows"""
    dialog_card(d, way)
    for _ in range(3):
        got = [t for t in d.screen().texts("all") if test(t)]
        if got:
            return got
        dialog_scroll(d)
    return []


def dialog_scroll(d):
    """the open dialog's content moved up a third of the screen (its buttons stay)"""
    s = d.screen()
    ok = s.find(text="确定")
    x = (ok.c[0] if ok else 540) - 300
    y = (ok.b[1] if ok else 1900) - 200
    d.swipe((x, y), (x, y - 600), 400, after=0.8)


def dialog_ok(d):
    d.tap(d.wait(lambda s: s.find(text="确定"), 5, what="确定").c, 1.5)


@case("ST-97", base="B0-设置", mutates=True, visual=True)
def st97(d):
    slots_page(d)
    key_dialog(d, "q")
    dialog_way(d, "上滑", "空")
    ed = dialog_text_field(d, "下滑")
    d.tap(ed.c, 0.8)
    d.type("okay")
    d.enter()
    d.hide_keyboard()
    d.tap_text("确定", scroll=False, after=1.0)
    d.open_page("text")
    d.clear()
    q = d.keys(refresh=True)["q"]
    d.shot("kb", "q 左上画 ok…，没有右上的 1")
    d.swipe(q, (q[0], q[1] + 130), 150, after=0.8)
    down = d.field_text()
    check(down == "okay", f"q 下滑打出的不是 okay：「{down}」")
    d.clear()
    d.swipe(q, (q[0], q[1] - 130), 150, after=0.8)
    up = d.field_text()
    check("1" not in up, f"q 上滑是空槽，却打出了「{up}」")
    d.clear()
    slots_page(d)
    # the title bar's ↶: no text, said 撤销
    d.tap(d.wait(lambda s: next((n for n in s.app() if n.desc == "撤销" and n.clickable), None), 5, what="标题栏的 ↶").c, 1.0)
    d.open_page("text")
    d.clear()
    q = d.keys(refresh=True)["q"]
    d.swipe(q, (q[0], q[1] - 130), 150, after=0.8)
    back = d.field_text()
    check(back == "1", f"撤销后 q 上滑不是 1：「{back}」")
    return f"q 下滑「{down}」，上滑空槽「{up}」，撤销后上滑「{back}」"


@case("ST-98", base="B0-设置", mutates=True, visual=True)
def st98(d):
    slots_page(d)
    d.tap_text("翻转上下滑", scroll=False, after=1.0)
    d.open_page("text")
    d.clear()
    a = d.keys(refresh=True)["a"]
    d.shot("kb", "翻转后 a：全选仍在左上，@ 仍在右上（位置跟着换了）")
    d.swipe(a, (a[0], a[1] + 130), 150, after=0.8)
    flipped = d.field_text()
    check(flipped == "@", f"翻转后 a 下滑不是 @：「{flipped}」")
    d.clear()
    slots_page(d)
    d.tap_text("预设", scroll=False, after=1.0)
    d.tap_text("上滑长按首选，下滑快捷键", scroll=False, after=1.0)
    # asked first: every key on every keyboard goes back
    d.tap_text("确定", where="all", scroll=False, after=1.0)
    d.open_page("text")
    d.clear()
    a = d.keys(refresh=True)["a"]
    d.shot("kb", "预设后 a：@ 在右上，全选在左上")
    d.swipe(a, (a[0], a[1] - 130), 150, after=0.8)
    preset = d.field_text()
    check(preset == "@", f"预设后 a 上滑不是 @：「{preset}」")
    d.clear()
    d.step("离开页面后撤销")
    slots_page(d)
    d.tap_text("翻转上下滑", scroll=False, after=0.3)
    # left while the snackbar shows: its 撤销 still puts the step back, no crash
    d.back(after=0.5)
    check(d.wait_text("撤销", timeout=3, where="all"), "离开页面后底部提示不见了，没有撤销可点")
    d.tap_text("撤销", where="all", scroll=False, after=1.0)
    d.open_page("text")
    d.clear()
    a = d.keys(refresh=True)["a"]
    d.swipe(a, (a[0], a[1] - 130), 150, after=0.8)
    undone = d.field_text()
    check(undone == "@", f"离开页面后撤销，a 上滑不是 @：「{undone}」")
    d.clear()
    slots_page(d)
    d.shot("full", "撤销后再打开：预设（@ 在右上）")
    return f"翻转后 a 下滑「{flipped}」，预设后上滑「{preset}」，离开页面后撤销上滑「{undone}」"


case("ST-99", needs="jvm:PopupOverridesFragmentTest$Dialog.selectedKeysAreSetAtOnce")(lambda d: None)


case("ST-100", needs="jvm:PopupOverridesFragmentTest$Dialog.anotherKeyboardsLongPressIsSetByName、PopupOverridesFragmentTest$Dialog.everyKeysEditorStartsFromItsKeyboardsOwnLongPress、PopupOverridesFragmentTest$Dialog.aListBackToTheEditorsDefaultStoresNothing、PopupOverridesFragmentTest$Row.anEmptiedListIsOffItsRowGoneToo、SwipeSlotsUatTest.aGroupKeysLongPressSetByName")(lambda d: None)



case("ST-103", needs="manual:逐个点新内置主题看键盘、动态背景、按键音，重启后解锁前看简蓝；列表与不能删由 BuiltinPacksTest 查")(lambda d: None)
case("ST-102", needs="manual:要先装 0.6.0 测试版选 Nord/Deep Blue/Monokai 再覆盖安装；迁移由 ThemePrefsMigrationTest 查")(lambda d: None)


case("ST-108", needs="jvm:KeyListsUpgradeTest.v052sLongPressesMoveOverAndTheOldOnesStay、KeyListsUpgradeTest.beta2sNamedListsMoveOverToo、KeyListsUpgradeTest.aBackupImportedIsMigratedAtTheStartAfterOr0_6sKept")(lambda d: None)


case("ST-109", needs="jvm:PopupOverridesFragmentTest$Dialog.numberPage")(lambda d: None)


case("ST-110", needs="release:要先在 0.6.0-beta3 上改九键 2 和 14 键 QW 的长按再覆盖安装；升级由 KeyListsMigrationTest、KeyListsUpgradeTest.aBeta3ListIsUpgradedAtTheStart 查")(lambda d: None)


case("ST-111", needs="manual:松手的按键音和音量差要耳朵听；滑块、摘要和旧档位由 AppPrefsTest.theKeySoundVolumeIsASliderThatKeepsAnOlderLevel 查")(lambda d: None)


@case("ST-107", base="B0-设置", mutates=True)
def st107(d):
    pinyin_keyboard(d, "九键")
    rows = d.pref_rows()
    titles = [t for t, _, _ in rows]
    d.shot("full", "拼音的设置页：九键一栏只有连按选字母、按键字母")
    check("连按选字母" in titles, f"拼音页没有连按选字母：{titles}")
    held = [t for t, sm, _ in rows if "长按" in (t or "")]
    check(not held, f"拼音页有改长按的入口：{held}")
    slots_tab(d, "九键")
    key_dialog(d, "5")
    s = d.screen()
    d.shot("full", "按键字符和快捷键的九键页：5 的对话框")
    items = [n.desc for n in s.app() if n.clickable and n.desc and len(n.desc) <= 2]
    check("#" in items, f"九键 5 的对话框里没有 #：{items}")
    return f"拼音页 {len(titles)} 行无长按入口；九键页 5：{items[:10]}"


THEME_NAMES = ("动态取色", "Material", "Pixel", "AMOLED 黑", "简蓝", "橙风", "素雅", "清心", "墨灰", "液态玻璃",
               "霓虹赛博", "国风·天青", "机械键帽", "奶油", "护眼·纸色", "多巴胺")


def theme_names(d, shots=None):
    """the theme list's names top to bottom (the page scrolled through); a shot a screen with [shots]"""
    seen = []
    for i in range(14):
        s = d.screen()
        seen += [n.text for n in s.app() if n.text in THEME_NAMES + ("新增主题",) and n.text not in seen]
        if shots:
            d.shot("full", f"{shots} {i + 1}")
        if not d.scroll(s):
            break
    return seen


@case("ST-105", base="B0-设置", visual=True)
def st105(d):
    appearance_tab(d, "主题")
    names = theme_names(d, "主题列表（竖屏）")
    missing = [n for n in THEME_NAMES if n not in names]
    check(not missing, f"列表里没有 {missing}：{names}")
    d.rotate(1)
    try:
        appearance_tab(d, "主题")
        land = theme_names(d, "主题列表（横屏）")
    finally:
        d.rotate(0)
    check(not [n for n in THEME_NAMES if n not in land], f"横屏列表里少了：{[n for n in THEME_NAMES if n not in land]}")
    return f"{len(names)} 个名字；横屏 {len(land)} 个；未跑（手工）：长名字导入、增删后的编号、TalkBack、最大字号"


def pick_theme(d, name):
    appearance_tab(d, "主题")
    for _ in range(14):
        s = d.screen()
        n = s.find(text=name)
        if n:
            d.tap((n.c[0], n.b[1] - 120), 1.5)   # the card above its name
            return
        if not d.scroll(s):
            break
    raise Blocked(f"主题列表里没有 {name}")


def key_style(d, title, option):
    appearance_tab(d, "大小与样式")
    choose(d, title, option)


@case("ST-104", base="B0-设置", mutates=True, visual=True)
def st104(d):
    def gaps(note):
        d.open_page("text")
        v, h = key_gaps(d)
        d.shot("kb", f"{note}：上下 {v:.0f} dp、左右 {h:.0f} dp")
        return round(v), round(h)
    pick_theme(d, "国风·天青")
    own = gaps("天青，跟随主题")
    key_style(d, "横向间距", "宽松")
    key_style(d, "纵向间距", "紧凑")
    set_ = gaps("天青，横向宽松、纵向紧凑")
    key_style(d, "按键圆角", "直角")
    d.open_page("text")
    d.shot("kb", "天青，直角：所有键都是直角")
    key_style(d, "按键圆角", "跟随主题")
    key_style(d, "按键边框", "无边框")
    d.open_page("text")
    d.shot("kb", "天青，无边框：所有键（空格、回车在内）没有键面、描边、阴影")
    key_style(d, "按键边框", "跟随主题")
    pick_theme(d, "Material")
    material = gaps("Material，横向宽松、纵向紧凑")
    key_style(d, "横向间距", "跟随主题")
    key_style(d, "纵向间距", "跟随主题")
    pick_theme(d, "国风·天青")
    back = gaps("天青，改回跟随主题")
    pick_theme(d, "多巴胺")
    key_style(d, "按键边框", "无边框")
    d.open_page("text")
    d.shot("kb", "多巴胺，无边框：上了色的那排字母键也没有键面")
    # no machine check: key_gaps reads a face's colour against what is between, and 天青's keys are pale over a
    # picture (it reads 2–8 dp where the shots show the same seams as Material's); the shots are compared by eye
    return (f"读到的键缝（上下, 左右 dp；天青背景是图，只作参考）：天青跟随 {own}，选档 {set_}，Material 同档 {material}，改回 {back}；"
            "未跑（手工）：横屏、紧凑、霓虹赛博、机械键帽、导入的 .theme 包")


@case("ST-106", base="B0-设置", mutates=True, visual=True)
def st106(d):
    d.sh("wm density 480")
    time.sleep(1.5)
    home(d, "按键")
    choose(d, "下滑图标位置", "底部")
    appearance_tab(d, "大小与样式")
    choose(d, "键盘高度", "紧凑")
    d.open_page("text")
    tb = script("05-toolbar")
    tb.status_panel(d)
    tb.ime(d, text="单手", contains=True, after=1.2)
    slots_tab(d, "全键盘")
    key_dialog(d, "j")
    dialog_text(d, "下滑", "xianzaizenmeyangle", "现在怎么样了")
    time.sleep(1.5)
    note = dialog_note(d, "下滑", lambda t: "画不下" in t or "显示为" in t)
    d.shot("full", "j 的对话框：下滑段下说键上画成什么")
    dialog_ok(d)
    s = d.screen()
    page = [t for t in s.texts("all") if t.startswith("j 下滑")]
    d.shot("full", "页面键下面：j 下滑：……")
    d.open_page("text")
    d.shot("kb", "测试页 j 键上画的和说的一样")
    d.rotate(1)
    try:
        d.open_page("text")
        d.shot("kb", "横屏 j 键")
    finally:
        d.rotate(0)
    slots_tab(d, "全键盘")
    key_dialog(d, "j")
    dialog_text(d, "下滑", "hao", "好")
    time.sleep(1.5)
    gone = dialog_note(d, "下滑", lambda t: "画不下" in t or "显示为" in t)
    dialog_ok(d)
    check(note, "下滑段下没有说键上画成什么")
    check(page and page[0].split("：", 1)[-1] == note[0], f"页面键下面的说明 {page} 和对话框的 {note} 不同")
    check(not gone, f"改成「好」后仍说 {gone}")
    return f"对话框「{note[0]}」；页面「{page[0] if page else ''}」；改成好后不说"


@case("ST-112", base="B0-设置", mutates=True)
def st112(d):
    slots_tab(d, "全键盘")
    key_dialog(d, "q")
    s = d.screen()
    e = next((n for n in s.app() if n.cls.endswith("EditText") and n.text in ("", "添加字符，空格分隔")), None)
    if not e:
        raise Blocked("对话框里没有添加字符的框")
    d.tap(e.c, 0.8)
    kb = d.need_keyboard()
    s = d.screen()
    ok = s.find(text="确定")
    cancel = s.find(text="取消")
    d.shot("full", "键盘弹出时对话框在键盘上面，确定、取消看得到")
    check(ok and cancel, f"键盘弹出时对话框里找不到确定、取消：{s.texts()[-12:]}")
    check(ok.b[3] <= kb.b[1] and cancel.b[3] <= kb.b[1], f"确定 {ok.b}、取消 {cancel.b} 被键盘（顶 {kb.b[1]}）挡住")
    k = d.keys(refresh=True)
    if "123" not in k:
        raise Blocked(f"键盘上没有 123：{sorted(k)[:20]}")
    d.tap(k["123"], 0.8)
    d.type("7")
    d.press("enter", after=1.0)
    s = d.screen()
    field = next((n for n in s.app() if n.cls.endswith("EditText") and n.text in ("", "添加字符，空格分隔")), None)
    check(s.find(rid="keyboard_view", where="ime") is None, "点完成后键盘没收起")
    check(any(head_name(h) == "下滑" for h in dialog_heads(s)), "点完成后对话框关了")
    check(any(n.clickable and n.desc == "7" for n in s.app()), "点完成后列表里没有 7")
    check(field is not None, f"点完成后添加框没清空：{[n.text for n in s.app() if n.cls.endswith('EditText')]}")
    dialog_ok(d)
    d.open_page("text")
    d.clear()
    shown = d.held(d.face("q", "Q"))
    d.clear()
    check("7" in shown, f"长按 q 弹出 {shown}，没有 7")
    return f"确定在键盘上面；完成添加了 7 并收起键盘；长按 q 弹出 {shown[:12]}"


case("ST-113", needs="jvm:KeyShortcutsPageScreenshotTest.zhuyinNinesCapsAreOneHeightARow、ZhuyinShortcutsUatTest.zhuyinNinesCommaSaysItIsChangedOnTheFunctionKeysTab")(lambda d: None)


@case("ST-114", base="B0-设置", mutates=True)
def st114(d):
    from uat import grouped_keys
    pinyin_keyboard(d, "14 键")
    slots_tab(d, "14 键")
    key_dialog(d, "QW")
    for item in ("Q", "W", "1", "q", "w"):
        dialog_pick(d, item)
        # a delete leaves the next item selected: tapping it then unselects it, 删除 greyed
        if not any(n.text == "删除" and n.enabled for n in d.screen().app()):
            dialog_pick(d, item)
        d.tap_text("删除", scroll=False, after=0.4)
    d.shot("app", "QW 只剩符号")
    dialog_ok(d)
    d.open_page("text")
    d.clear()
    qw = d.keys(refresh=True)["qw"]
    kb = d.need_keyboard()
    on_keys = {t for n in kb.walk() if n.clickable for t in n.texts()}
    d.touch(("down", *qw), ("sleep", 0.9))
    shown = [n.text for n in d.screen().nodes if n.text and not n.clickable and n.text not in on_keys]
    d.shot("full", "长按 QW 只弹符号，没有手指下那一行")
    d.touch(("up", *qw))
    time.sleep(0.5)
    d.clear()
    check(shown and not {"Q", "W", "1", "q", "w"} & set(shown), f"长按 QW 弹出 {shown}")
    grouped_keys(d, "qw op")
    c = d.cands()
    check("我" in c[:5], f"打 wo 的候选 {c[:8]}，没有「我」")
    return f"长按 QW 弹出 {shown[:10]}；wo 候选 {c[:5]}"


case("ST-115", needs="jvm:PackLayoutRefusedTest.aLayoutTheEngineRefusesDrawsTheAppsOwnOfItsId、"
     "PackLayoutRefusedTest.theAppsOwnLayoutDrawnInsteadIsTypedAsTheBuiltInGroups、"
     "PackLayoutRefusedTest.aLayoutRefusedIsNotMadeAgainAsWhatTheKeysShowChanges、"
     "PackLayoutRefusedTest.aRefusalInUseIsKeptThroughManyOthersAndLoggedOnce、"
     "PackLayoutRefusedTest.aLayoutRefusedInOneStateIsDrawnInAnotherAtTheSameSize、"
     "PackLayoutRefusedTest.theKeysTypedOnBeforeTheLayoutDrawnChangedSendTheDrawnLayoutsCodes、"
     "PackLayoutRefusedTest.aZhuyinKeyWhoseFaceChangedSendsTheSymbolItShows")(lambda d: None)
case("ST-116", needs="manual:主题制作网页；在电脑浏览器上手工跑（只有深色版的主题从背景图取色、勾「这个主题有浅色版」）")(lambda d: None)


case("ST-117", needs="jvm:SettingsUatTest.theCodeRowsThreePlacesTheFieldOnlyOnceItsNoteIsAgreedTo")(lambda d: None)


case("ST-118", needs="jvm:FloatingCodeStripUatTest.withTheCodeInTheFieldTheStripShowsTheSyllablesAlone、FloatingCodeStripUatTest.theKeyboardIsAsHighOnEveryKeyboardAndLowerThanEmbedded")(lambda d: None)


case("ST-119", needs="jvm:DeveloperUatTest.voiceCorrectionsAskedAndRememberedAreCounted")(lambda d: None)


@case("ST-120", base="B0-设置", mutates=True, visual=True)
def st120(d):
    pinyin_keyboard(d, "九键")
    slots_tab(d, "九键")
    key_dialog(d, "3")
    dialog_way(d, "上滑", "命令")
    # its 换 (the only one on screen: 下滑's card folded) opens the commands' own list, a dialog over the key's
    s = d.screen()
    change = [n for n in s.app() if n.text == "换"]
    if len(change) != 1:
        raise Blocked(f"上滑卡片里的「换」不是一个：{s.texts()[-16:]}")
    d.tap(change[0].c, 0.8)
    # the five strokes at the list's end
    for _ in range(4):
        if any(n.text == "笔画：撇" for n in d.screen().app()):
            break
        dialog_scroll(d)
    s = d.wait(lambda s: s if any(n.text == "笔画：撇" for n in s.app()) else None, 5, what="命令列表里的笔画")
    strokes = [t for t in ("笔画：横", "笔画：竖", "笔画：撇", "笔画：点", "笔画：折") if any(n.text == t for n in s.app())]
    d.shot("app", "命令列表：五种笔画")
    check(len(strokes) == 5, f"命令列表里的笔画只有 {strokes}")
    d.tap_text("笔画：撇", scroll=False, after=0.6)
    dialog_ok(d)
    d.open_page("text")
    d.clear()
    d.shot("kb", "九键 3 键角上画着撇")
    k = d.keys(refresh=True)
    three = k["3"]
    d.taps([k[c] for c in "9264"], gap=0.35, after=0.6)
    before = d.preedit()
    d.swipe(three, (three[0], three[1] - 130), 150, after=0.8)
    typed = d.preedit()
    d.shot("kb", "打着 wang 上滑 3：编码行多一个丿")
    check("丿" in typed and "丿" not in before, f"打着拼音上滑 3，编码行由「{before}」变成「{typed}」，没多一个丿")
    check("d" not in typed.replace(before, ""), f"上滑 3 打了 DEF：「{typed}」")
    d.clear()
    d.swipe(three, (three[0], three[1] - 130), 150, after=0.8)
    empty = d.preedit() + d.field_text()
    check(empty == "", f"什么都没打时上滑 3，打出了「{empty}」")
    d.step("在键上划选笔画筛选")
    home(d, "通用输入")
    choose(d, "在键上划", "笔画筛选")
    d.open_page("text")
    d.clear()
    three = d.keys(refresh=True)["3"]
    d.swipe(three, (three[0], three[1] - 130), 150, after=0.8)
    filtered = d.preedit() + d.field_text()
    check(filtered == "", f"笔画筛选、什么都没打时上滑 3，打出了「{filtered}」")
    # the dialog says the stroke is not reached while typing now
    slots_tab(d, "九键")
    key_dialog(d, "3")
    note = "在键上划选了笔画筛选时，打着拼音滑这个键按画出的形状算，不发这一笔"
    said = bool(dialog_note(d, "上滑", lambda t: t == note))
    d.shot("app", "笔画筛选时 3 的对话框：上滑的命令下面说打字时滑不到")
    check(said, "笔画筛选时 3 的对话框里没有说打着拼音时滑不到")
    d.back(after=0.5)
    return f"命令里有 {len(strokes)} 种笔画；wang 上滑 3：「{before}」→「{typed}」；没打字上滑：「{empty}」；笔画筛选没打字上滑：「{filtered}」；对话框说明：{said}"


case("ST-125", needs="jvm:PopupMarksEditorTest.aFieldOfVariablesIsOneItemOfThemPreviewedAndTold")(lambda d: None)

case("ST-126", needs="manual:导出备份、恢复，覆盖装 0.6.0 再装回；JVM 见 DowngradeTo060Test")(lambda d: None)

case("ST-127", needs="jvm:PopupMarksEditorTest.noChinesePunctuationIsForTheMarksATableConverts")(lambda d: None)

case("ST-128", needs="jvm:BarIconsThemeTest.aThemesBarIconsAreDrawnTheOnesItDoesNotWriteTheApps、BarIconsThemeTest.aBuiltInThemeDrawsTheAppsAgain")(lambda d: None)

case("ST-129", needs="jvm:BarIconsThemeTest.aThemesUndoIsTheToolbarsAndTheShortcutsAlike")(lambda d: None)

case("ST-130", needs="jvm:BarIconsThemeTest.menuIconsNoneDrawnTurnTheAppsAsEver、BarIconsThemeTest.aThemeOfOneMenuStateDrawsThatOneTheOtherTheAppsTurnedNeitherTurned、"
               "BarIconsThemeTest.hideKeyboardsImagesAreEachPlacesOwnTillAThemeWritesIt")(lambda d: None)

case("ST-131", needs="manual:主题制作网页；在电脑浏览器上手工看候选栏预览（分组由 panels-smoke.mjs 查）")(lambda d: None)

case("ST-132", needs="jvm:BarIconsThemeTest.theStatusPanelsCellsAndGearAreTheThemes、BarIconsThemeTest.thePanelsButtonsDoingACommandsJobAreTheThemes、"
               "BarIconsThemeTest.theButtonsBesideTheKeysAreTheThemes、BarIconsThemeTest.theFloatingCandidateWindowsKeyboardButtonIsTheThemes")(lambda d: None)

case("ST-121", needs="jvm:SettingsUatTest.keyDrawingIsOneListOfItsChoices、InputMethodSettingsScreenTest.keyDrawingIsOneChoiceSummarizedByItself、"
               "InputMethodSettingsScreenTest.thePauseOnTheSharedPageIsTheAppsPreference、HandwritingUatTest.aShortAndALongPause")(lambda d: None)

case("ST-122", needs="jvm:KotlinHostHandwritingTest.handwritingIsNoInputMethodToAdd")(lambda d: None)

case("ST-123", needs="jvm:HandwritingModeTest.theThemesInkOnlyWhereItStandsOutFromTheLetters、PanelThemeTest.theInkOverTheLetters")(lambda d: None)

case("ST-124", needs="jvm:InputMethodSettingsScreenTest.aSharedOptionSaysUnderItWhatItIsNoUseTo")(lambda d: None)


def push_zhuyin_held_pack(d):
    """uat-zhuyin-held.theme in 下载: Nord as a pack of its own with the app's 注音全键盘, its ㄅ (zyfull/1) writing a
    corner ! and a swipe up typing ㄅㄅ of its own, as a 0.6.0 pack could"""
    def read(*p):
        with open(os.path.join(SCENE, *p), encoding="utf-8") as f:
            return f.read()
    zhuyin = read("layouts", "zhuyin.xml")
    assert 'shortcut="zyfull/1"' in zhuyin, "the app's 注音 no longer names ㄅ zyfull/1"
    zhuyin = zhuyin.replace('shortcut="zyfull/1"', 'shortcut="zyfull/1" alt="!" swipe-up="commit:ㄅㄅ"', 1)
    p = os.path.join(tempfile.gettempdir(), f"uat-zhuyin-held-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", read("themes", "nord.xml").replace('name="nord"', 'name="uat-zhuyin-held"', 1))
        z.writestr("layouts/zhuyin.xml", zhuyin)
    push_download(d, p, "uat-zhuyin-held")


@case("ST-133", base="B0-注音全键盘", mutates=True, visual=True)
def st133(d):
    def bo():
        d.open_page("text")
        d.clear()
        b = d.face("ㄅ")
        corner = b.desc
        d.swipe(b.c, (b.c[0], b.c[1] - 130), 150, after=0.8)
        up = d.field_text()
        d.clear()
        return corner, up
    before = bo()
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    push_zhuyin_held_pack(d)
    d.step("导入")
    import_theme(d, "uat-zhuyin-held", confirm=False)
    for told in ("此主题包含自定义布局：zhuyin", "主题自带的布局里有 2 处长按、角标、滑动的写法不起作用"):
        check(d.wait_text(told, timeout=8, where="all", contains=True), f"确认框没写「{told}」：{d.screen().texts('all')[:10]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.step("看 ㄅ")
    after = bo()
    d.open_page("text")
    d.face("ㄅ")
    d.shot("kb", "主题自带注音全键盘：ㄅ 的键角是内置的，不是 !")
    check(after[0] == before[0] and "!" not in after[0], f"ㄅ 的键角读作「{after[0]}」，内置的是「{before[0]}」")
    check(after[1] == before[1] and "ㄅㄅ" not in after[1], f"ㄅ 上滑打出「{after[1]}」，内置的是「{before[1]}」")
    return f"确认框提示 2 处不起作用；ㄅ 键角「{after[0]}」、上滑「{after[1]}」，和内置的一样"


case("ST-140", needs="jvm:KeyboardStyleSettingsUatTest.theTwoStylesInPlaceOfTheThree、KeyboardStyleSettingsUatTest.notSetShowsTheTurnedScreensOwn、KeyboardStyleSettingsUatTest.aStyleChangedFromTheKeyboardShowsAtOnce")(lambda d: None)


# ---------------------------------------------------------------- 按偏的键按落点读 (061-neighbour-correction)


case("ST-136", needs="jvm:SettingsUatTest.everyInputMethodsPageAndTheSharedOnes、NeighbourSlipTest.theNineKeysReadADigitAsTheOneItWasTappedTowards")(lambda d: None)


case("ST-137", needs="jvm:SettingsUatTest.pinyinsKeyboardOnItsPageAndTheSharedOptionsSayingFourteenKeys")(lambda d: None)


SHIFT060 = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures", "theme-shift060")


def push_shift060_pack(d, name):
    """<name>.theme in 下载: Nord as a pack of its own with 0.6.0's 26 keys (uat/fixtures/theme-shift060), its Shift
    `when="!table"` and no 分词"""
    with open(os.path.join(SCENE, "themes", "nord.xml"), encoding="utf-8") as f:
        xml = f.read()
    assert 'name="nord"' in xml
    p = os.path.join(tempfile.gettempdir(), f"{name}-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", xml.replace('name="nord"', f'name="{name}"', 1))
        z.write(os.path.join(SHIFT060, "qwerty.xml"), "layouts/qwerty.xml")
    push_download(d, p, name)


def typing_xi(d):
    """the keys' ids while xi is typed on the full keyboard, the field cleared after"""
    d.open_page("text")
    d.type("xi")
    ids = set(d.keys(refresh=True))
    d.clear()
    return ids


@case("ST-143", base="B0-设置", mutates=True)
def st143(d):
    debuggable(d, "删启动标记")
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    push_shift060_pack(d, "uat-shift060")
    d.step("导入旧包")
    import_theme(d, "uat-shift060", confirm=False)
    answer_pack(d, with_layouts=False)
    use_theme(d)
    ids = typing_xi(d)
    # since 0.6.1 (061-custom-layout §11) the pack's 26 keys stand for the app's no more: the app's, 分词 its own
    check("apostrophe" in ids and "shift" not in ids, f"导入后画的应是自带的 26 键，打 xi 时那格是分词：{sorted(ids)[:40]}")
    d.step("当作升级后第一次启动")
    out = d.sh(f"run-as {PKG} rm no_backup/theme-separator-added no_backup/theme-layouts-listed; am force-stop {PKG}")
    check("No such file" not in out, f"标记文件不在（第一次启动没写？）：{out.strip()[:120]}")
    # the start lists and switches off the main thread (seconds for a 26 keys'): 外观 → 布局 waits for it to end, the
    # input method's page does not, so it is opened after (as ST-175)
    check(layout_card(d, "uat-shift060 · 旧 26 键"), "布局页没有「uat-shift060 · 旧 26 键」")
    gear(d, "拼音")
    check(d.wait_text("uat-shift060 · 旧 26 键（自定义）", timeout=5, contains=True), f"重启后拼音没换成转出来的 26 键：{d.screen().texts()[:16]}")
    keyboard_back(d)
    ids = typing_xi(d)
    # a layout of the user's is found by its labels (分词), the app's by its scene ids too (apostrophe)
    sep = "apostrophe" if "apostrophe" in ids else "分词"
    check(sep in ids and "shift" not in ids, f"重启后打 xi 时那格不是分词：{sorted(ids)[:40]}")
    d.open_page("text")
    d.type("xi")
    d.tap(d.keys(refresh=True)[sep], 0.6)
    d.type("an")
    code = d.preedit()
    d.clear()
    check("xi'an" in code.replace(" ", ""), f"分词后编码不是 xi'an：{code!r}")
    d.step("之后导入的不改")
    push_shift060_pack(d, "uat-shift060b")
    import_theme(d, "uat-shift060b", confirm=False)
    answer_pack(d)
    pinyin_keyboard(d, "uat-shift060b · 旧 26 键（自定义）")
    ids = typing_xi(d)
    check("shift" in ids and not {"apostrophe", "分词"} & ids, f"之后导入的包的 26 键打 xi 时那格应是 ⇧：{sorted(ids)[:40]}")
    return "导入后是自带的分词；重启后拼音换成转出来的 26 键，打字时是分词（xi'an）；之后导入的那块仍是 ⇧"


# 0.6.0's theme maker example 薄荷 17 键 (a pack with its own layouts/k18.xml), keyboard-scene's test data since L8
MINT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "lib", "keyboard-scene", "src", "commonTest", "data", "mint-k17")


def push_mint_pack(d, name="uat-mint"):
    """<name>.theme in 下载: 0.6.0's 薄荷 17 键 example pack (its theme.xml and layouts/k18.xml) renamed"""
    with open(os.path.join(MINT, "theme.xml"), encoding="utf-8") as f:
        xml = f.read()
    assert 'name="薄荷 17 键"' in xml
    p = os.path.join(tempfile.gettempdir(), f"{name}-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", xml.replace('name="薄荷 17 键"', f'name="{name}"', 1))
        z.write(os.path.join(MINT, "layouts", "k18.xml"), "layouts/k18.xml")
    push_download(d, p, name)


def keyboard_back(d, tries=3):
    """the test page's keyboard after a force-stop: the system starts the input method again late (another one meanwhile)"""
    for i in range(tries):
        try:
            d.open_page("text")
            return d.keys(refresh=True)
        except Blocked:
            if i == tries - 1:
                raise
            time.sleep(5.0)


def unseen_notice(d):
    """the theme layouts' notice waiting to be said on the keyboard (KotlinHost.tellUnseen), "" none"""
    out = d.sh(f"run-as {PKG} cat shared_prefs/{PKG}_preferences.xml")
    m = re.search(r'<string name="theme_layouts_unseen">([^<]*)</string>', out)
    return m.group(1) if m else ""


@case("ST-175", base="B0-设置", mutates=True)
def st175(d):
    debuggable(d, "删启动标记")
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    pinyin_keyboard(d, "18 键")
    push_mint_pack(d)
    import_theme(d, "uat-mint", confirm=False)
    answer_pack(d, with_layouts=False)
    use_theme(d)
    d.step("当作升级后第一次启动")
    out = d.sh(f"run-as {PKG} rm no_backup/theme-layouts-listed; am force-stop {PKG}")
    check("No such file" not in out, f"标记文件不在（第一次启动没写？）：{out.strip()[:120]}")
    told = "主题「uat-mint」带的17 键已放进 外观 → 布局，拼音正在用"
    # the settings first: the app's start lists and switches off the main thread, 外观 → 布局 waits for it; a keyboard
    # asked for at once waits on the system to start the input method again (over 10 s after a force-stop on an emulator)
    d.step("布局页、拼音的键盘")
    check(layout_card(d, "uat-mint · 17 键"), "布局页没有「uat-mint · 17 键」")
    check(d.wait_text("用在：拼音", timeout=5, where="all"), f"卡片下没写「用在：拼音」：{d.screen().texts()[:20]}")
    gear(d, "拼音")
    check(d.wait_text("uat-mint · 17 键（自定义）", timeout=5, contains=True), f"拼音的「键盘」不是 uat-mint · 17 键：{d.screen().texts()[:16]}")
    d.step("键盘上的提示")
    # the notice stays 4 s (ThemeLayoutsToList.NOTICE_MS), yet a screen dump as the keyboard comes up may miss it: what
    # waits to be said is read off the settings (internal.themeLayoutsUnseen), then seen as the keyboard shows it
    check(told in unseen_notice(d), f"等着说的提示不是「{told}」：{unseen_notice(d)!r}")
    keyboard_back(d)
    d.wait(lambda x: not unseen_notice(d), 15, what="提示说过")
    d.step("再启动不再提示")
    d.sh(f"am force-stop {PKG}")
    keyboard_back(d)
    time.sleep(2.0)
    check(not d.screen().find(contains=told, where="ime") and not unseen_notice(d), "再启动又提示了一次")
    return "重启后提示一次；布局页 uat-mint · 17 键 用在：拼音；拼音的键盘是它；再启动不提示"


case("ST-176", needs="jvm:ThemeLayoutsToListTest.bothSlotsPacksOfOneBoardTheShownOnesChosenTheOthersToldOf")(lambda d: None)
case("ST-193", needs="jvm:RenamedKeysTest.aLongPressTurnedOffIsCopiedOff、RenamedKeysTest.swipesAreCopiedEachWayApart、ThemeLayoutsToListTest.theSettingsOfARenamedKeyAreCopiedUnderItsNewName")(lambda d: None)

case("ST-156", needs="jvm:SettingsMenuUatTest.searchingAChoiceOpensItsSetting、SettingsIndexTest.strokeFilterFindsFourInEachLanguage")(lambda d: None)

case("ST-151", needs="jvm:PopupOverridesFragmentTest$Cards.theKeyboardsAreEightCardsToPickNotARowToSwipe、SettingsCardsLayoutTest.smallPhonesWindow")(lambda d: None)

case("ST-152", needs="jvm:PopupOverridesFragmentTest$Cards.aKeyboardsCardSaysHowManyOfItsKeysAreChanged、PopupOverridesFragmentTest$Cards.theFunctionKeysAreTwelveCellsFourAcross、"
               "PopupOverridesFragmentTest$Cards.undoIsTheTitleBarsAndResetAllInItsMenu")(lambda d: None)

case("ST-153", needs="jvm:PopupOverridesFragmentTest$Cards.theEditorsCardsOpenOneAtATimeEachSayingWhatIsSet、PopupOverridesFragmentTest$Row.aGroupOfNoFirstIsSaidToHaveNone、SettingsCardsLayoutTest.smallPhonesWindow、FoldCardTest.a card folded over its focused field lets the focus and the keyboard go")(lambda d: None)

case("ST-154", needs="jvm:PopupOverridesFragmentTest$Cards.theEditorsCardsOpenOneAtATimeEachSayingWhatIsSet、PopupOverridesFragmentTest$Cards.nothingMovesAsAnItemIsSelectedOrATextChosen")(lambda d: None)

case("ST-155", needs="jvm:PopupOverridesFragmentTest$Cards.multiSelectsStripsChooseAKeyboardsKeysAllOrNone")(lambda d: None)

case("ST-157", needs="jvm:SettingsMenuUatTest.searchingAKeyCommandOpensTheKeysPageWithAHint")(lambda d: None)

case("ST-158", needs="jvm:SettingsMenuUatTest.searchingBySynonym")(lambda d: None)

case("ST-159", needs="jvm:SettingsMenuUatTest.strokeFilterFindsTheSameFourInTraditionalChinese、SettingsMenuUatTest.strokeFilterFindsTheSameFourInEnglish、SettingsIndexTest.strokeFilterFindsFourInEachLanguage")(lambda d: None)


def settings_search(d, code, word=None):
    """the settings search (🔍 on the home page) with what code types: the candidate word picked, or the letters as typed"""
    home(d)
    d.tap(d.wait(lambda s: s.find(desc="搜索设置"), 8, what="首页的 🔍").c, 1.0)
    field = d.wait(lambda s: next((n for n in s.app() if n.cls.endswith("EditText")), None), 8, what="搜索框")
    if not d.screen().find(rid="candidate_view", where="ime") and not d.keys(refresh=True):
        d.tap(field.c, 1.0)
    d.type(code)
    if word:
        d.pick(word)
    else:
        d.enter()
    d.hide_keyboard()


def open_result(d, title, under):
    """the result titled title whose line under it has under, tapped (the log emptied first: flash_shot reads it)"""
    def row(s):
        for t in s.app():
            if t.text == title and any(under in n.text and t.b[1] < n.c[1] < t.b[1] + 260 for n in s.app()):
                return t
    n = d.wait(row, 10, step=0.3, what=f"「{title}」（{under}）那条结果")
    d.sh("logcat -c")
    d.tap(n.c, 0)


def flash_shot(d, id, note):
    """the screen the moment the page flashes id (SearchHighlight logs 「search: flashed <id>」): the press lasts
    600 ms, past a dump of the screen; a page that never flashes it fails here"""
    end = time.time() + 15
    while "search: flashed " + id not in d.sh(f"logcat -d | grep -F 'search: flashed {id}'"):
        if time.time() > end:
            raise Fail(f"没有闪 {id}")
        time.sleep(0.05)
    d.shot("app", note, png=d.adb("exec-out", "screencap", "-p", binary=True))


@case("ST-165", base="B0-设置", visual=True)
def st165(d):
    settings_search(d, "zhuyin", "注音")
    open_result(d, "注音九键", "按键字符和快捷键")
    flash_shot(d, "view:keys/board/zy9", "搜注音点注音九键：按键字符和快捷键，注音九键的卡片按下的样子")
    s = d.wait(lambda s: sum(n.text == "注音九键" for n in s.app()) >= 2 and s, 8, step=0.3, what="选中注音九键（卡片和下面的标题）")
    check(s.find(text="翻转上下滑"), f"不在按键字符和快捷键：{s.texts()[:10]}")
    d.back(1.0)
    field = d.wait(lambda s: next((n for n in s.app() if n.cls.endswith("EditText")), None), 8, what="回到搜索页")
    d.tap(field.c, 1.0)
    d.press("bksp", 4, after=0.3)
    d.type("fanzhuan")
    d.pick("翻转")
    d.hide_keyboard()
    open_result(d, "翻转上下滑", "按键字符和快捷键")
    flash_shot(d, "view:keys/flip", "搜翻转点翻转上下滑：翻转上下滑按钮按下的样子")
    return "注音九键选中，两张注音九键的字；翻转上下滑的页"


@case("ST-166", base="B0-设置", visual=True)
def st166(d):
    groups = ("三级字", "繁体字", "其它表外字", "扩展区")
    settings_search(d, "fanti", "繁体")
    open_result(d, "繁体字", "生僻字屏蔽")
    flash_shot(d, "view:rare/group/TRAD", "搜繁体点繁体字：生僻字屏蔽的四张卡片，繁体字那张按下的样子")
    s = d.wait(lambda s: all(next((n for n in s.app() if n.desc.startswith(g + "，") or n.text == g), None) for g in groups) and s,
               30, step=0.1, what="四张组卡片")
    check(not s.find(text="全部屏蔽") or s.find(text="三级字"), "打开了繁体字的网格")
    return "四张组卡片都在"


case("ST-167", needs="jvm:SettingsMenuUatTest.anActionOfThePagesMenuSaysWhereItIs")(lambda d: None)

case("ST-168", needs="jvm:SettingsMenuUatTest.aRowOfAPageDrawnByHandIsPointedAt、SettingsPagesTest.everyTappableTextOnAHandPageIsSearched")(lambda d: None)


@case("ST-169", base="B0-设置", visual=True)
def st169(d):
    settings_search(d, "pixel")
    open_result(d, "Pixel", "外观")
    flash_shot(d, "view:theme/pixel", "搜 Pixel：外观的主题页，Pixel 那张按下的样子")
    s = d.wait(lambda s: s.find(text="Pixel") and s.find(text="大小与样式") and s, 10, step=0.1, what="外观的主题页上的 Pixel")
    return "外观的主题页，Pixel 看得见"


case("ST-194", needs="jvm:SettingsIndexTest.theTablesKeyboardIsFoundWhileALayoutOfTheUsersHasItsKeys")(lambda d: None)


# ---------------------------------------------------------------- 外观 → 布局 (061-custom-layout §4)


LAYOUTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures", "layouts")


def push_layout(d, name):
    """uat/fixtures/layouts/<name>.layout into 下载, scanned so the file picker lists it"""
    d.sh(f"rm -f /sdcard/Download/{name}*.layout")
    d.adb("push", os.path.join(LAYOUTS, f"{name}.layout"), f"/sdcard/Download/{name}.layout")
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/{name}.layout")
    time.sleep(1)


def preview_png(d):
    """the 外观 page's upper part, the preview, above the tabs"""
    tab = d.screen().find(text="主题")
    if not tab:
        raise Blocked("外观页没有页签")
    im = d.png().convert("L")
    return im.crop((0, tab.b[1] // 3, im.size[0], tab.b[1] - 4)).resize((80, 40))


def differs(a, b):
    # measured on the emulator: 14 键 against 26 键 1.4, the same board drawn twice 0.3
    return sum(abs(x - y) for x, y in zip(a.getdata(), b.getdata())) / (80 * 40) > 0.8


def import_layout(d, name):
    """布局's + → 导入布局文件 → [name] in the picker; the dialog that follows left open"""
    appearance_tab(d, "布局")
    # the + a moment after the tab (seen missing on the first case after a boot)
    plus = d.wait(lambda s: s.find(desc="新增布局"), 8, what="布局页的 +")
    d.tap(plus.c, 1.5)
    d.tap_text("导入布局文件", scroll=False, after=0.5)
    # the picker cold-started after a boot takes seconds to draw: until DocumentsUI is the top activity and shows nodes
    # of its own. Before it does the screen holds only the launcher's taskbar, whose 返回 主屏幕 最近用过 are its
    # buttons' content-desc, no picker's (L6 review S3); so only DocumentsUI's nodes are read below
    in_picker(d, timeout=15)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")

    def picker(s):
        return [k for k in s.nodes if k.pkg.endswith("documentsui")]

    def file(s):
        return next((k for k in picker(s) if k.text and k.text.startswith(name)), None)

    def titled(s, *texts):
        return next((k for k in picker(s) if k.text in texts), None)

    s = d.screen()
    n = file(s)
    if not n and titled(s, "下载", "Downloads"):
        d.tap(titled(s, "下载", "Downloads").c, 2.0)
        s = d.screen()
        n = file(s)
    # opened on DocumentsUI's 最近用过, 下载 not on screen: the drawer's ☰, as 15-privacy-backup has it
    if not n and titled(s, "最近用过", "近期的文件", "最近", "Recent"):
        to_downloads(d, s)
        s = d.screen()
        n = file(s)
    if not n:
        raise Blocked(f"文件选择里没有 {name}：{[k.text for k in picker(s) if k.text][:8]}")
    d.tap(n.c, 3.0)

def skip_use(d):
    """after 导入: 现在就用在…？ (L3, §4.2 step 5) answered 先不用"""
    try:
        d.wait_text("现在就用在", timeout=6, where="all", contains=True)
    except Blocked:
        raise Blocked(f"导入后没问现在就用在哪：{d.screen().texts('all')[:10]}")
    d.tap_text("先不用", where="all", scroll=False, after=1.5)


def layout_card(d, name):
    appearance_tab(d, "布局")
    n = d.screen().find(text=name)
    # down the list, then back up: the tab comes back where it was left, possibly past the card
    for frm, to in [((540, 1800), (540, 1200))] * 3 + [((540, 900), (540, 1500))] * 6:
        if n:
            break
        d.swipe(frm, to, 300, after=1.0)
        n = d.screen().find(text=name)
    # the line under it (用在：…) on screen too
    if n and n.c[1] > 1700:
        d.swipe((540, 1800), (540, 1200), 300, after=1.0)
        n = d.screen().find(text=name)
    return n


@case("ST-147", base="B0-设置", visual=True)
def st147(d):
    appearance_tab(d, "主题")
    s = d.screen()
    check(all(s.find(text=t) for t in ("主题", "大小与样式", "布局")), f"外观页签不是三页：{s.texts()[:10]}")
    original = preview_png(d)
    appearance_tab(d, "布局")
    s = d.screen()
    for t in ("字母键盘", "26 键", "18 键", "14 键", "九键", "数字键盘"):
        check(s.find(text=t), f"布局页没有「{t}」：{s.texts()[:16]}")
    check(s.find(desc="新增布局"), "布局页右下角没有 +")
    d.shot("app", "外观 → 布局：字母键盘段的四块自带键盘")
    d.tap(s.find(text="14 键").c, 1.5)
    fourteen = preview_png(d)
    check(differs(original, fourteen), "点 14 键后预览没变")
    d.shot("app", "点 14 键：上方预览换成 14 键")
    d.hold(s.find(text="26 键").c, 1000)
    time.sleep(1.0)
    check(not d.screen().find(text="删除"), "长按自带的布局出了菜单")
    appearance_tab(d, "主题")
    check(not differs(original, preview_png(d)), "切到主题页后预览没回到原来的键盘")
    return "三页签；布局页四块字母键盘和数字键盘；点 14 键预览换了，回主题页换回"


@case("ST-148", base="B0-设置", mutates=True, visual=True)
def st148(d):
    for n in ("uat-qwerty-1.0", "uat-qwerty-1.1"):
        push_layout(d, n)
    appearance_tab(d, "布局")
    d.tap(d.screen().find(desc="新增布局").c, 1.5)
    s = d.screen()
    check(s.find(text="导入布局文件") and s.find(contains="在网页上做布局"), f"+ 的菜单不对：{s.texts()[:6]}")
    d.back(after=1.0)
    import_layout(d, "uat-qwerty-1.0")
    for told in ("导入布局 UAT 26 键？", "UAT", "1.0", "CC0", "验收测试用", "拼音", "哈哈"):
        check(d.wait_text(told, timeout=8, where="all", contains=True), f"确认框没写「{told}」：{d.screen().texts('all')[:12]}")
    d.shot("app", "导入确认框：预览、作者、版本、能用在哪、直接打出的文字")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    skip_use(d)
    check(layout_card(d, "UAT 26 键"), "导入后列表里没有 UAT 26 键")
    import_layout(d, "uat-qwerty-1.1")
    check(d.wait_text("替换已有的『UAT 26 键』1.0 → 1.1", timeout=8, where="all", contains=True),
          f"1.1 的确认框没写替换：{d.screen().texts('all')[:12]}")
    d.tap_text("取消", where="all", scroll=False, after=2.0)
    import_layout(d, "uat-qwerty-1.1")
    d.wait_text("替换已有的", timeout=8, where="all", contains=True)
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    skip_use(d)
    # scrolled to it first: the built-in cards (L3) can put it below the fold
    check(layout_card(d, "UAT 26 键"), "替换后列表里没有 UAT 26 键")
    check(len([n for n in d.screen().app() if n.text == "UAT 26 键"]) == 1, "替换后不止一张 UAT 26 键")
    card = layout_card(d, "UAT 26 键")
    d.hold(card.c, 1000)
    time.sleep(1.0)
    d.tap_text("信息", scroll=False, after=2.0)
    check(d.wait_text("1.1", timeout=5, where="all", contains=True), f"替换后信息里不是 1.1：{d.screen().texts('all')[:12]}")
    d.back(after=1.0)
    return "确认框写全；1.1 写「替换已有的『UAT 26 键』1.0 → 1.1」，取消不换，导入后只有一张、信息是 1.1"


@case("ST-149", base="B0-设置", mutates=True)
def st149(d):
    push_layout(d, "uat-qwerty-bad")
    import_layout(d, "uat-qwerty-bad")
    check(d.wait_text("这个布局导入不了", timeout=8, where="all"), f"没说导入不了：{d.screen().texts('all')[:10]}")
    check(d.wait_text("显示的字太长：最多 16 个字符", timeout=3, where="all", contains=True), f"没列出问题：{d.screen().texts('all')[:10]}")
    d.shot("app", "不合格的布局：列出第几行什么问题")
    d.tap_text("确定", where="all", scroll=False, after=1.5)
    # a picture: not text
    d.sh("cp /system/media/bootanimation.zip /sdcard/Download/uat-not-text.layout 2>/dev/null || "
         "head -c 2048 /dev/urandom > /sdcard/Download/uat-not-text.layout")
    d.sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/uat-not-text.layout")
    time.sleep(1)
    import_layout(d, "uat-not-text")
    check(d.wait_text("这个文件不是布局", timeout=8, where="all", contains=True), f"没说不是布局：{d.screen().texts('all')[:10]}")
    d.tap_text("确定", where="all", scroll=False, after=1.5)
    debuggable(d, "看 files/layouts")
    check(d.sh(f"run-as {PKG} ls files/layouts 2>/dev/null").strip() == "", "拒绝后留下了文件")
    return "不合格的列出「显示的字太长」；不是文字的说不是布局；什么都没留下"


@case("ST-150", base="B0-设置", mutates=True)
def st150(d):
    push_layout(d, "uat-qwerty-1.1")
    import_layout(d, "uat-qwerty-1.1")
    d.wait_text("导入布局 UAT 26 键？", timeout=8, where="all")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    skip_use(d)
    card = layout_card(d, "UAT 26 键")
    check(card, "列表里没有 UAT 26 键")
    d.hold(card.c, 1000)
    time.sleep(1.0)
    s = d.screen()
    check(all(s.find(text=t) for t in ("信息", "导出", "删除")), f"长按菜单不对：{s.texts()[:6]}")
    d.tap_text("信息", scroll=False, after=2.0)
    for told in ("UAT", "1.1", "CC0", "哈哈"):
        check(d.wait_text(told, timeout=5, where="all", contains=True), f"信息里没有「{told}」")
    d.back(after=1.0)
    before = set(d.sh("ls /sdcard/Download/ 2>/dev/null").splitlines())
    d.hold(layout_card(d, "UAT 26 键").c, 1000)
    time.sleep(1.0)
    d.tap_text("导出", scroll=False, after=2.0)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"导出后没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh("ls /sdcard/Download/ 2>/dev/null").splitlines()) - before)
    check(new == ["UAT 26 键.layout"], f"导出的文件是 {new}")
    out = os.path.join(tempfile.gettempdir(), f"uat-layout-{d.port}.layout")
    d.adb("pull", "/sdcard/Download/UAT 26 键.layout", out)
    with open(out, "rb") as a, open(os.path.join(LAYOUTS, "uat-qwerty-1.1.layout"), "rb") as b:
        check(a.read() == b.read(), "导出的文件和导入的不一样")
    d.hold(layout_card(d, "UAT 26 键").c, 1000)
    time.sleep(1.0)
    d.tap_text("删除", scroll=False, after=1.5)
    check(d.wait_text("删除「UAT 26 键」吗", timeout=5, where="all", contains=True), "删除前没问")
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    check(not d.screen().find(text="UAT 26 键"), "删除后卡片还在")
    d.sh("rm -f '/sdcard/Download/UAT 26 键.layout'")
    return "菜单 信息/导出/删除；信息写全；导出 UAT 26 键.layout 和导入的一样；问一次后删掉"


# ---------------------------------------------------------------- 布局用在输入法上 (061-custom-layout §4.2–4.4, §5, L3)


def import_uat_qwerty(d, use=None):
    """uat-qwerty-1.0 imported; 现在就用在 answered with the input methods of use ticked (None: 先不用)"""
    push_layout(d, "uat-qwerty-1.0")
    import_layout(d, "uat-qwerty-1.0")
    d.wait_text("导入布局 UAT 26 键？", timeout=8, where="all")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    if not use:
        skip_use(d)
        return
    d.wait_text("现在就用在", timeout=6, where="all", contains=True)
    for im in use:
        d.tap_text(im, where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)


def types_haha(d):
    """the a key on the test page: what reaches the field (the layout's a commits 哈哈, the app's types a)"""
    d.open_page("text")
    d.tap(d.keys(refresh=True)["a"], 1.0)
    text = d.field_text() or ""
    d.clear()
    return text


@case("ST-161", base="B0-设置", mutates=True, visual=True)
def st161(d):
    d.step("导入时用在拼音上")
    import_uat_qwerty(d, use=["拼音"])
    card = layout_card(d, "UAT 26 键")
    check(card, "导入后列表里没有 UAT 26 键")
    check(d.wait_text("用在：拼音", timeout=5, where="all"), f"卡片下没写「用在：拼音」：{d.screen().texts()[:20]}")
    d.shot("app", "导入后用在拼音上：卡片下写「用在：拼音」")
    check("哈哈" in types_haha(d), "拼音用 UAT 26 键时 a 键没打出「哈哈」")
    d.shot("ime", "拼音的键盘是导入的 UAT 26 键")
    gear(d, "拼音")
    check(d.wait_text("UAT 26 键（自定义）", timeout=5, contains=True), f"拼音的「键盘」没写 UAT 26 键（自定义）：{d.screen().texts()[:16]}")
    d.step("英文没选它")
    d.open_page("text")
    d.switch_im("英文")
    d.tap(d.keys(refresh=True)["a"], 1.0)
    text = d.field_text() or ""
    d.clear()
    d.switch_im("拼音")
    check("哈哈" not in text, f"英文没选它，a 键却打出了「哈哈」：{text!r}")
    return "导入时勾拼音：卡片写用在：拼音，拼音的 a 打出哈哈，拼音的键盘写 UAT 26 键（自定义）；英文不受影响"


@case("ST-162", base="B0-设置", mutates=True)
def st162(d):
    import_uat_qwerty(d)
    card = layout_card(d, "UAT 26 键")
    check(card, "导入后列表里没有 UAT 26 键")
    # its own card's line only: the app's 数字 and 符号 cards say 用在：数字键盘 / 符号面板 (L5) and may be on screen
    s = d.screen()
    under = [n.text for n in s.app() if n.text.startswith("用在：") and abs(n.c[0] - card.c[0]) < 200 and 0 < n.c[1] - card.c[1] < 150]
    check(not under, f"没用在输入法上，卡片下却写了「用在：」：{under}")
    d.step("点卡片 → 用在… 勾拼音、英文")
    d.tap(card.c, 1.0)
    d.tap_text("用在…", where="all", scroll=False, after=1.5)
    check(d.wait_text("「UAT 26 键」用在", timeout=5, where="all"), f"没出「用在」勾选：{d.screen().texts('all')[:10]}")
    for im in ("拼音", "双拼", "英文"):
        check(d.screen().find(text=im, where="all"), f"用在… 里没有{im}")
    for im in ("拼音", "英文"):
        d.tap_text(im, where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    check(d.wait_text("用在：拼音、英文", timeout=5, where="all"), f"卡片下没写「用在：拼音、英文」：{d.screen().texts()[:20]}")
    check("哈哈" in types_haha(d), "拼音的 a 键没打出「哈哈」")
    d.step("再去掉拼音")
    card = layout_card(d, "UAT 26 键")
    d.tap(card.c, 1.0)
    d.tap_text("用在…", where="all", scroll=False, after=1.5)
    d.wait_text("「UAT 26 键」用在", timeout=5, where="all")
    d.tap_text("拼音", where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    check(d.wait_text("用在：英文", timeout=5, where="all"), f"去掉拼音后卡片下不是「用在：英文」：{d.screen().texts()[:20]}")
    check("哈哈" not in types_haha(d), "去掉拼音后拼音的 a 键仍打出「哈哈」")
    return "点卡片 用在… 勾拼音、英文后卡片写用在：拼音、英文、拼音 a 打出哈哈；去掉拼音后换回全键盘"


@case("ST-163", base="B0-设置", mutates=True)
def st163(d):
    import_uat_qwerty(d, use=["拼音"])
    card = layout_card(d, "UAT 26 键")
    check(card, "导入后列表里没有 UAT 26 键")
    d.hold(card.c, 1000)
    time.sleep(1.0)
    d.tap_text("删除", scroll=False, after=1.5)
    check(d.wait_text("拼音正在用它，删掉后拼音换回 全键盘", timeout=5, where="all", contains=True),
          f"删除确认没说拼音正在用：{d.screen().texts('all')[:10]}")
    d.shot("app", "删正在用的布局：写清拼音换回全键盘")
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    check(not d.screen().find(text="UAT 26 键"), "删除后卡片还在")
    check("哈哈" not in types_haha(d), "删掉后拼音的 a 键仍打出「哈哈」")
    gear(d, "拼音")
    check(d.wait_text("全键盘\n屏幕上打拼音", timeout=5, contains=True), f"删掉后拼音的「键盘」不是全键盘：{d.screen().texts()[:16]}")
    return "确认框写「拼音正在用它，删掉后拼音换回 全键盘」；删后拼音是全键盘，a 打 a"


@case("ST-164", base="B0-设置", mutates=True)
def st164(d):
    debuggable(d, "删布局文件")
    import_uat_qwerty(d, use=["拼音"])
    check("哈哈" in types_haha(d), "拼音的 a 键没打出「哈哈」")
    d.step("布局文件不见了")
    out = d.sh(f"run-as {PKG} rm files/layouts/uat-qwerty.xml")
    check("No such file" not in out, f"布局文件不在：{out.strip()[:120]}")
    check("哈哈" not in types_haha(d), "文件删了，拼音的 a 键仍打出「哈哈」")
    gear(d, "拼音")
    check(d.wait_text("（读不出，暂用 全键盘）", timeout=5, contains=True), f"拼音的「键盘」没说读不出：{d.screen().texts()[:16]}")
    d.shot("app", "布局文件不见：键盘一项写「读不出，暂用 全键盘」")
    return "文件不见后拼音画全键盘（a 打 a），设置写「uat-qwerty（读不出，暂用 全键盘）」，选择不改"


case("ST-191", needs="jvm:InputMethodSettingsScreenTest.aLayoutThatReadsButIsNotOfThisInputMethodIsSaidSo")(lambda d: None)
case("ST-192", needs="jvm:CustomKeyboardTest.oneBasedOnABoardOfAnotherFamilyFallsBackToItsOwnFamilys、"
                     "LayoutListFragmentTest.aCardBasedOnAnotherFamilysBoardIsPreviewedAsTheKeyboardDrawsIt、"
                     "LayoutCheckTest.aGroupedLayoutOnTheTwentySixKeysIsHeldToTheEighteenButToldOfItsOwnBase")(lambda d: None)


# ---------------------------------------------------------------- 数字键盘、符号面板用自己的布局 (061-custom-layout §7, §8, L5)


def import_panel_layout(d, name, shown, use):
    """uat/fixtures/layouts/<name>.layout imported; 现在就用作数字键盘/符号面板吗？ answered 用 or 先不用"""
    push_layout(d, name)
    import_layout(d, name)
    d.wait_text(f"导入布局 {shown}？", timeout=8, where="all")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    try:
        d.wait_text("现在就用作", timeout=6, where="all", contains=True)
    except Blocked:
        raise Blocked(f"导入后没问现在就用作：{d.screen().texts('all')[:10]}")
    d.tap_text("用" if use else "先不用", where="all", scroll=False, after=2.0)


def number_ones(d, page="text"):
    """on the test page [page]: the number keyboard's 1 key as it is labelled (壹: uat-number's), 123 tapped on a text field"""
    d.open_page(page)
    keys = d.keys(refresh=True)
    if page == "text":
        d.tap(keys["123"], 1.0)
        d.keys(refresh=True)
    # by the faces: keys() also knows a key by its scene id, and the 壹 key's id is 1
    faces = {n.texts()[0] for n in d.need_keyboard().walk() if n.clickable and n.texts()}
    return [k for k in ("1", "壹") if k in faces]


@case("ST-170", base="B0-设置", mutates=True, visual=True)
def st170(d):
    push_layout(d, "uat-number-1.0")
    import_layout(d, "uat-number-1.0")
    d.wait_text("导入布局 UAT 数字？", timeout=8, where="all")
    check(d.screen().find(contains="数字键盘：123、电话", where="all"), f"确认框没写数字键盘：123、电话：{d.screen().texts('all')[:16]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    check(d.wait_text("现在就用作数字键盘吗？", timeout=6, where="all"), f"导入后没问现在就用作数字键盘：{d.screen().texts('all')[:10]}")
    check(not d.screen().find(contains="现在就用在", where="all"), "数字键盘布局还问了用在哪些输入法")
    d.tap_text("用", where="all", scroll=False, after=2.0)
    check(layout_card(d, "UAT 数字"), "导入后列表里没有 UAT 数字")
    check(d.wait_text("用在：数字键盘", timeout=5, where="all"), f"卡片下没写「用在：数字键盘」：{d.screen().texts()[:20]}")
    d.shot("app", "导入数字键盘布局后用上：卡片下写「用在：数字键盘」")
    d.step("123、电话框用它，数字框用自带的")
    check(number_ones(d) == ["壹"], f"123 的数字键盘不是导入的：{number_ones(d)}")
    d.shot("ime", "123 的数字键盘：1 键写「壹」")
    d.tap(d.keys(refresh=True)["壹"], 1.0)
    typed = d.field_text() or ""
    d.clear()
    check("1" in typed, f"点「壹」打出的不是 1：{typed!r}")
    check(number_ones(d, "tel") == ["壹"], "电话框的数字键盘不是导入的")
    check(number_ones(d, "number") == ["1"], "数字框的数字键盘不是自带的")
    return "确认框写数字键盘：123、电话；问现在就用作数字键盘；卡片写用在：数字键盘；123 和电话框 1 键写壹（打出 1），数字框是自带的"


@case("ST-171", base="B0-设置", mutates=True)
def st171(d):
    import_panel_layout(d, "uat-number-1.0", "UAT 数字", use=False)
    card = layout_card(d, "UAT 数字")
    check(card, "导入后列表里没有 UAT 数字")
    d.step("点卡片 → 数字键盘用这个")
    d.tap(card.c, 1.0)
    d.tap_text("数字键盘用这个", where="all", scroll=False, after=2.0)
    check(d.wait_text("用在：数字键盘", timeout=5, where="all"), f"用上后卡片下没写「用在：数字键盘」：{d.screen().texts()[:20]}")
    check(number_ones(d) == ["壹"], "用上后 123 的数字键盘不是导入的")
    d.step("点自带的「数字」→ 换回")
    builtin = layout_card(d, "数字")
    check(builtin, "数字键盘段没有自带的「数字」")
    d.tap(builtin.c, 1.0)
    d.tap_text("数字键盘用这个", where="all", scroll=False, after=2.0)
    # 用在：数字键盘 back under the app's card: the one line of it, below 数字 and above UAT 数字
    # the cards two a row: the line under the app's card, in its column, and none under UAT 数字's
    builtin = layout_card(d, "数字")
    s = d.screen()
    mine = s.find(text="UAT 数字")
    under = lambda card: [n for n in s.app() if n.text == "用在：数字键盘" and card.b[0] <= n.c[0] <= card.b[2] and n.c[1] > card.c[1]]
    check(builtin and mine and under(builtin) and not under(mine),
          f"换回后「用在：数字键盘」不在自带的卡片下：{[(n.text, n.b) for n in s.app() if len(n.text) > 1][-8:]}")
    check(number_ones(d) == ["1"], "换回后 123 的数字键盘不是自带的")
    return "点 UAT 数字 → 数字键盘用这个：卡片写用在、123 1 键写壹；点自带的数字换回，用在回到自带的卡片下，1 键写 1"


@case("ST-172", base="B0-设置", mutates=True, visual=True)
def st172(d):
    def panel():
        """the symbol panel opened on the test page: its nodes (no keyboard_view: read off the input method's window),
        the categories' x against the panel's middle"""
        d.open_page("text")
        d.tap(d.keys(refresh=True)["符"], 1.5)
        s = d.screen()
        nodes = list(s.ime())
        shown = [n.text or n.desc for n in nodes if n.text or n.desc]
        cats = [n for n in nodes if n.text in ("常用", "最近")]
        check(cats, f"符号面板没有分类：{shown[:24]}")
        middle = (min(n.b[0] for n in nodes) + max(n.b[2] for n in nodes)) / 2
        return s, shown, cats[0].c[0] > middle
    # the app's panel made once before (the board then put in place of its own: L5 review S3)
    d.step("先用自带的打开一次符号面板")
    s, shown, right = panel()
    check("返回" in shown and not right, f"自带的符号面板不是分类在左、返回键写返回：{shown[:24]}")
    d.tap(s.find(text="返回", where="ime").c, 1.0)
    d.step("导入、用上 UAT 符号（分类栏在右边）")
    import_panel_layout(d, "uat-symbols-1.0", "UAT 符号", use=True)
    check(layout_card(d, "UAT 符号"), "导入后列表里没有 UAT 符号")
    check(d.wait_text("用在：符号面板", timeout=5, where="all"), f"卡片下没写「用在：符号面板」：{d.screen().texts()[:20]}")
    s, shown, right = panel()
    check("回去" in shown and "返回" not in shown, f"符号面板的返回键不是「回去」：{shown[:24]}")
    check(right, f"换板后分类栏没到右边的槽：{shown[:24]}")
    d.shot("ime", "符号面板用导入的布局：返回键写「回去」，分类在右、格子在左")
    d.tap(s.find(text="回去", where="ime").c, 1.0)
    check("q" in d.keys(refresh=True), "点「回去」没回到字母键盘")
    return "先开过自带的面板；导入后卡片写用在：符号面板；面板返回键写回去，分类栏换到右边的槽，回去回到字母键盘"


@case("ST-173", base="B0-设置", mutates=True)
def st173(d):
    import_panel_layout(d, "uat-number-1.0", "UAT 数字", use=True)
    card = layout_card(d, "UAT 数字")
    check(card, "导入后列表里没有 UAT 数字")
    d.hold(card.c, 1000)
    time.sleep(1.0)
    d.tap_text("删除", scroll=False, after=1.5)
    check(d.wait_text("数字键盘正在用它，删掉后换回内置的", timeout=5, where="all", contains=True),
          f"删除确认没说数字键盘正在用：{d.screen().texts('all')[:10]}")
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    check(not d.screen().find(text="UAT 数字"), "删除后卡片还在")
    check(number_ones(d) == ["1"], "删掉后 123 的数字键盘不是自带的")
    return "确认框写数字键盘正在用它、换回内置的；删后 123 是自带的"


# ---------------------------------------------------------------- 网页的布局示例 (061-custom-layout §12.4, W3)


EXAMPLES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "web", "theme-maker", "examples", "layouts")


def push_example(d, name):
    """the layout maker's example web/theme-maker/examples/layouts/<name>.layout into 下载, as the page has it"""
    d.sh(f"rm -f /sdcard/Download/{name}*.layout")
    d.adb("push", os.path.join(EXAMPLES, f"{name}.layout"), f"/sdcard/Download/{name}.layout")
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/{name}.layout")
    time.sleep(1)


def scene_keys(d):
    """the keyboard on screen: each key's scene id and its bounds"""
    return {n.key: n.b for n in d.need_keyboard().walk() if n.clickable and n.key}


def han(text):
    return any("一" <= c <= "鿿" for c in text or "")


def types_a_word(d, keys):
    """[keys] (centres) tapped on the test page under pinyin, then space: a candidate offered, the field's text"""
    for xy in keys:
        d.tap(xy, 0.8)
    offered = [c for c in d.candidates() if han(c)]
    check(offered, f"没有中文候选：{d.candidates()[:6]}")
    d.tap(d.keys(refresh=True)["space"], 1.0)
    text = d.field_text() or ""
    d.clear()
    return text


@case("ST-177", base="B0-设置", mutates=True, visual=True)
def st177(d):
    push_example(d, "k17")
    import_layout(d, "k17")
    check(d.wait_text("导入布局 17 键？", timeout=8, where="all"), f"没出导入确认：{d.screen().texts('all')[:10]}")
    check(d.screen().find(contains="拼音", where="all"), f"确认框没写能用在拼音：{d.screen().texts('all')[:16]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    check(d.wait_text("现在就用在 拼音 上吗？", timeout=6, where="all"), f"没直接问用在拼音：{d.screen().texts('all')[:10]}")
    d.tap_text("用", where="all", scroll=False, after=2.0)
    check(layout_card(d, "17 键"), "导入后列表里没有 17 键")
    check(d.wait_text("用在：拼音", timeout=5, where="all"), f"卡片下没写「用在：拼音」：{d.screen().texts()[:20]}")
    d.step("拼音用 17 键：第二行 A SD FG HJK L")
    d.open_page("text")
    keys = scene_keys(d)
    check("hjk" in keys and "r2c4" not in keys and "r2c5" not in keys, f"键盘不是 17 键：{sorted(keys)[:30]}")
    y = (keys["hjk"][1] + keys["hjk"][3]) / 2
    row = sorted(k for k, b in keys.items() if b[1] < y < b[3])
    check(row == ["hjk", "r2c1", "r2c2", "r2c3", "r2c6"], f"第二行不是 A SD FG HJK L：{row}")
    d.shot("ime", "拼音用网页示例 17 键：H 和 JK 并成 HJK")
    centre = lambda b: ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    text = types_a_word(d, [centre(keys["hjk"]), centre(keys["r2c1"])])
    check(han(text), f"HJK A 空格没上屏汉字：{text!r}")
    return f"确认框能用在拼音；直接问用在拼音；第二行 A SD FG HJK L；HJK A 空格上屏「{text}」"


@case("ST-178", base="B0-设置", mutates=True, visual=True)
def st178(d):
    push_example(d, "azerty")
    import_layout(d, "azerty")
    check(d.wait_text("导入布局 AZERTY？", timeout=8, where="all"), f"没出导入确认：{d.screen().texts('all')[:10]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    check(d.wait_text("现在就用在", timeout=6, where="all", contains=True), f"导入后没问现在就用在哪：{d.screen().texts('all')[:10]}")
    for im in ("拼音", "双拼", "英文", "五笔等码表"):
        check(d.screen().find(text=im, where="all"), f"现在就用在 里没有{im}")
    for im in ("拼音", "英文"):
        d.tap_text(im, where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    check(layout_card(d, "AZERTY"), "导入后列表里没有 AZERTY")
    check(d.wait_text("用在：拼音、英文", timeout=5, where="all"), f"卡片下没写「用在：拼音、英文」：{d.screen().texts()[:20]}")
    d.step("拼音：AZERTY 的三行")
    d.open_page("text")
    keys = scene_keys(d)
    def row_of(k):
        y = (keys[k][1] + keys[k][3]) / 2
        return [i for i, b in sorted(keys.items(), key=lambda kv: kv[1][0]) if b[1] < y < b[3]]
    check(row_of("a")[:10] == list("azertyuiop"), f"第一行不是 AZERTYUIOP：{row_of('a')}")
    check(row_of("q")[:10] == list("qsdfghjklm"), f"第二行不是 QSDFGHJKLM：{row_of('q')}")
    third = row_of("w")
    check(third[third.index("w"):third.index("w") + 7] == ["w", "x", "c", "v", "b", "n", "quote"], f"第三行不是 W X C V B N '：{third}")
    d.shot("ime", "拼音用网页示例 AZERTY")
    centre = lambda b: ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    text = types_a_word(d, [centre(keys["n"]), centre(keys["i"])])
    check(han(text), f"N I 空格没上屏汉字：{text!r}")
    d.step("英文：a z '")
    d.switch_im("英文")
    keys = scene_keys(d)
    for k in ("a", "z", "quote"):
        d.tap(centre(keys[k]), 0.8)
    typed = d.field_text() or ""
    d.clear()
    d.switch_im("拼音")
    check(typed.startswith("az'"), f"英文打出的不是 az'：{typed!r}")
    return f"能用在拼音、双拼、英文；三行是 AZERTYUIOP / QSDFGHJKLM / WXCVBN'；拼音 N I 上屏「{text}」；英文打出 {typed!r}"


@case("ST-179", base="B0-设置", mutates=True, visual=True)
def st179(d):
    push_example(d, "big-number")
    import_layout(d, "big-number")
    check(d.wait_text("导入布局 大按键数字键盘？", timeout=8, where="all"), f"没出导入确认：{d.screen().texts('all')[:10]}")
    check(d.screen().find(contains="数字键盘：123、只有数字、小数", where="all"), f"确认框没写管哪几种：{d.screen().texts('all')[:16]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    check(d.wait_text("现在就用作数字键盘吗？", timeout=6, where="all"), f"没问现在就用作数字键盘：{d.screen().texts('all')[:10]}")
    d.tap_text("用", where="all", scroll=False, after=2.0)
    check(layout_card(d, "大按键数字键盘"), "导入后列表里没有 大按键数字键盘")
    check(d.wait_text("用在：数字键盘", timeout=5, where="all"), f"卡片下没写「用在：数字键盘」：{d.screen().texts()[:20]}")
    d.step("123：一行四个键，回车跨两行")
    d.open_page("text")
    d.tap(d.keys(refresh=True)["123"], 1.0)
    keys = scene_keys(d)
    check("=" not in keys and "," not in keys and "space" in keys, f"123 的数字键盘不是大按键的：{sorted(keys)}")
    y = (keys["1"][1] + keys["1"][3]) / 2
    first = [k for k, b in sorted(keys.items(), key=lambda kv: kv[1][0]) if b[1] < y < b[3]]
    check(first == ["1", "2", "3", "backspace"], f"第一行不是 1 2 3 ⌫：{first}")
    tall, one = keys["enter"][3] - keys["enter"][1], keys["1"][3] - keys["1"][1]
    check(tall > 1.8 * one, f"回车没跨两行：高 {tall}，1 键高 {one}")
    d.shot("ime", "123 用网页示例大按键数字键盘：一行四个，回车跨两行")
    centre = lambda b: ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    for k in ("1", "2", "backspace", "5"):
        d.tap(centre(keys[k]), 0.6)
    typed = d.field_text() or ""
    d.clear()
    check(typed == "15", f"1 2 ⌫ 5 打出的不是 15：{typed!r}")
    d.step("小数框用它，电话框用自带的")
    d.open_page("decimal")
    keys = scene_keys(d)
    check("." in keys and "=" not in keys and "space" in keys, f"小数框的数字键盘不是大按键的：{sorted(keys)}")
    d.open_page("tel")
    keys = scene_keys(d)
    # the app's phone pad: enter one row tall
    check("enter" in keys and "1" in keys and keys["enter"][3] - keys["enter"][1] < 1.5 * (keys["1"][3] - keys["1"][1]),
          f"电话框的数字键盘不是自带的：{sorted(keys)}")
    return "确认框写 123、只有数字、小数；卡片写用在：数字键盘；123 一行四个、回车跨两行、1 2 ⌫ 5 打出 15；小数框是它，电话框是自带的"


@case("ST-180", base="B0-设置", mutates=True)
def st180(d):
    push_layout(d, "uat-split-1.0")
    import_layout(d, "uat-split-1.0")
    check(d.wait_text("导入布局 UAT 分体？", timeout=8, where="all", contains=True), f"没出导入确认：{d.screen().texts('all')[:10]}")
    for told in ("注意：", "有 8 个键，不是 9 或 10 个，分不成左右两半，只能整行缩小"):
        check(d.wait_text(told, timeout=5, where="all", contains=True), f"确认框没写「{told}」：{d.screen().texts('all')[:16]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    skip_use(d)
    check(layout_card(d, "UAT 分体"), "导入后列表里没有 UAT 分体")
    return "确认框能导入，警告第一行分体时 8 个键整行缩小；导入后列表里有 UAT 分体"


# ---------------------------------------------------------------- 布局的键名 (061-custom-layout §9, §19.4, L6)


def import_uat_names(d, use=True):
    """uat-names imported; 现在就用在 拼音 上吗？ answered 用 (or 先不用); the confirm dialog's texts returned"""
    push_layout(d, "uat-names")
    import_layout(d, "uat-names")
    d.wait_text("导入布局 UAT 键名？", timeout=8, where="all")
    told = d.screen().texts("all")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    if not use:
        skip_use(d)
        return told
    d.wait_text("现在就用在 拼音 上吗？", timeout=6, where="all")
    d.tap_text("用", where="all", scroll=False, after=2.0)
    return told


def slots_tabs(d):
    """the 按键字符和快捷键 page's keyboard cards, as they read"""
    slots_page(d)
    return [n.text for n in d.screen().app() if n.cls.endswith("RadioButton")]


@case("ST-185", base="B0-设置", mutates=True, visual=True)
def st185(d):
    import_uat_names(d)
    tabs = slots_tabs(d)
    check("UAT 键名" in tabs, f"按键字符和快捷键里没有「UAT 键名」：{tabs}")
    check(tabs.index("UAT 键名") == tabs.index("功能键") - 1, f"「UAT 键名」不在功能键前面：{tabs}")
    slots_tab(d, "UAT 键名")
    d.shot("full", "按键字符和快捷键的 UAT 键名：WE 下写共用，HJK、哈 没有")
    we, hjk, ha = page_key(d, "WE"), page_key(d, "HJK"), page_key(d, "哈")
    check(we.desc.endswith("共用"), f"WE 没写共用：{we.desc!r}")
    check(not hjk.desc.endswith("共用") and not ha.desc.endswith("共用"), f"HJK、哈 写了共用：{hjk.desc!r} {ha.desc!r}")
    key_dialog(d, "WE")
    check(d.wait_text("和「18 键」共用：这里改了，那边的同名键也跟着变", timeout=5, where="all"), f"WE 的对话框没说和 18 键共用：{d.screen().texts('all')[:8]}")
    d.tap_text("取消", where="all", scroll=False, after=1.0)
    key_dialog(d, "哈")
    dialog_card(d, "长按菜单")
    s = d.screen()
    items = [n.desc for n in s.app() if n.clickable and n.desc in ("哈哈", "嘿")]
    check(items == ["哈哈", "嘿"], f"哈 的长按菜单不是 哈哈、嘿：{items}")
    up = next((h.desc or h.text for h in dialog_heads(s) if head_name(h) == "上滑"), "")
    check("哈哈" in up, f"哈 的上滑不是长按首选 哈哈：{up!r}")
    d.shot("full", "哈 的对话框：长按菜单 哈哈（首选）、嘿")
    d.tap_text("取消", where="all", scroll=False, after=1.0)
    return f"卡片 {tabs}；WE 共用、对话框说和 18 键共用；哈 的长按 {items}，上滑「{up}」"


@case("ST-186", base="B0-设置", mutates=True)
def st186(d):
    import_uat_names(d, use=False)
    tabs = slots_tabs(d)
    check(len(tabs) == 8 and "UAT 键名" not in tabs, f"没用的布局却有卡片：{tabs}")
    card = layout_card(d, "UAT 键名")
    check(card, f"列表里没有 UAT 键名：{d.screen().texts()[:30]}")
    d.tap(card.c, 1.0)
    d.tap_text("用在…", where="all", scroll=False, after=1.5)
    d.wait_text("「UAT 键名」用在", timeout=5, where="all")
    d.tap_text("拼音", where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    used = slots_tabs(d)
    check("UAT 键名" in used, f"用在拼音上后没有卡片：{used}")
    card = layout_card(d, "UAT 键名")
    d.tap(card.c, 1.0)
    d.tap_text("用在…", where="all", scroll=False, after=1.5)
    d.wait_text("「UAT 键名」用在", timeout=5, where="all")
    d.tap_text("拼音", where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    gone = slots_tabs(d)
    check("UAT 键名" not in gone, f"去掉拼音后卡片还在：{gone}")
    return f"没用 {len(tabs)} 张；用在拼音上多出 UAT 键名；去掉后 {len(gone)} 张"


# which keyboard a function key's note names depends on how each draws it (a prefix only where they differ): held
# to the label by SwipeFates' own test, not to one screen's drawing
case("ST-187", needs="jvm:SwipeFatesTest.aLayoutOfTheUsersIsNamedByItsName")(lambda d: None)


@case("ST-188", base="B0-设置", mutates=True)
def st188(d):
    told = import_uat_names(d)
    warn = "第 14 行：g18/we 是 18 键的键，这里写的长按、角标、滑动不起作用"
    check(any(warn in t for t in told), f"确认框没提醒 WE 写的不起作用：{told[:12]}")
    check(any("哈" in t and "直接打出" in t for t in told) or any(t == "哈" for t in told), f"确认框没列出直接打出的「哈」：{told[:12]}")
    d.open_page("text")
    d.clear()
    k = d.keys(refresh=True)
    we = k.get("we") or k.get("WE")
    if not we:
        raise Blocked(f"拼音的键盘上没有 WE：{sorted(k)[:24]}")
    d.swipe(we, (we[0], we[1] + 130), 150, after=0.8)
    text = d.field_text() or ""
    d.clear()
    check("我们" not in text, f"WE 下滑打出了布局里写的「我们」：{text!r}")
    return f"确认框提醒「{warn}」；WE 下滑没打出我们（{text!r}）"


case("ST-189", needs="manual:键盘布局网页新建、合并、保存，再导入 App 打字：电脑浏览器加设备，手工跑；"
     "网页一侧 web/theme-maker 的 layout-maker.test.mjs、layout-smoke.mjs 查")(lambda d: None)


case("ST-190", needs="manual:键盘布局网页的保存检查、草稿找回、打开 .theme，主题制作网页打开 .layout：电脑浏览器上手工跑；"
     "web/theme-maker 的 layout-maker.test.mjs、layout-smoke.mjs、maker.test.mjs、panels-smoke.mjs 查")(lambda d: None)
