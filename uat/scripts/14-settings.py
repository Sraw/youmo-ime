"""uat/14-settings.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import re
import shutil
import subprocess
import tempfile
import time
import zipfile

from uat import (PKG, Blocked, case, check, choose, home, pinyin_keyboard, script)


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


case("ST-13", needs="jvm:SettingsEffectUatTest.swipeUpPositionNoneThenBottom")(lambda d: None)


# ---------------------------------------------------------------- 按键

case("ST-14", needs="jvm:SettingsEffectUatTest.noPopupOnKeyPress")(lambda d: None)


case("ST-15", needs="jvm:SettingsEffectUatTest.swipeUpThenDisabled、KeySwipeTest.bothOffLeavesTheKeyAsItWas")(lambda d: None)


case("ST-16", needs="jvm:SettingsEffectUatTest.aLongerLongPressDelay")(lambda d: None)


case("ST-17", needs="jvm:SettingsEffectUatTest.holdingTheSpaceGoesToTheNextInputMethod")(lambda d: None)


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
    """中文标点 → , set to mark, typed by its corner swipe up (、 is m's corner character)"""
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
    d.swipe(m, (m[0], m[1] - 130), 150)
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
case("ST-73", needs="manual:要从制作页导出动效示例主题，按键的缩放、下沉、渐隐要人眼看")(lambda d: None)
case("ST-74", needs="manual:要从制作页导出 17 键示例主题，按它的分组打拼音、看固定的词")(lambda d: None)


case("ST-63", needs="release:多选文字包、语音包和一个非包文件导入，读逐文件的结果框、看下载里的原文件还在；结果框出现时转屏只一个框，点确定后再转屏、切到别的应用再回来不再弹出（手工）；结果的说法在 JVM 上：PacksUatTest.eachFilesOutcomeIsToldAndTheUserMayDeleteTheirFiles")(lambda d: None)


case("ST-67", needs="release:覆盖安装 0.5.x→0.6.0 和恢复 0.5.x 的备份，发版时的升级检查里手工跑；JVM 见 ThemePrefsMigrationTest、ThemePrefsTest、KeyboardAreaTest")(lambda d: None)


case("ST-64", needs="release:要上一版（0.5.x）的包覆盖安装；方法见 dev/design/060-asset-packs.md 的实现记录")(lambda d: None)


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
    qwerty = read("layouts", "qwerty.xml").replace('name="26 键"', 'name="主题 26 键"') \
        .replace('label="{case:q}" alt="{alt:q}" send="q"', 'label="{case:q}" alt="{alt:q}" send="commit:你好"')
    k18 = read("layouts", "k18.xml").replace('name="18 键"', 'name="主题 18 键"')
    layouts = {"qwerty.xml": qwerty, "k18.xml": k18, "k14.xml": "<keyboard/>"}
    p = os.path.join(tempfile.gettempdir(), f"uat-layouts-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", read("themes", "nord.xml").replace('name="nord"', 'name="uat-layouts"', 1))
        for n, t in layouts.items():
            z.writestr("layouts/" + n, t)
    push_download(d, p, "uat-layouts")


@case("ST-72", base="B0-设置", mutates=True)
def st72(d):
    from uat import gear
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统深色模式", False)
    push_layouts_pack(d)
    d.step("导入")
    import_theme(d, "uat-layouts", confirm=False)
    # the app's 26 keys' four (/ @ . .com) and q's; k14 does not read
    for told in ("此主题包含自定义布局：k18, qwerty（其中 5 个动作直接输入布局里写的文字）", "缺键盘必需的键，或不是已有的键盘）：k14"):
        check(d.wait_text(told, timeout=8, where="all", contains=True), f"确认框没写「{told}」：{d.screen().texts('all')[:10]}")
    d.tap_text("导入", where="all", scroll=False, after=3.0)
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "导入后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.step("打字")
    d.open_page("text")
    d.type("q")
    d.expect_field("你好", msg="点 q 没有输入布局里写的「你好」")
    d.step("切键盘")
    gear(d, "拼音")
    d.tap_text("键盘", after=1.0)
    opts = d.screen().texts("all")
    d.back(after=0.5)
    for want in ("全键盘（主题：主题 26 键）", "18 键（主题：主题 18 键）", "14 键"):
        check(want in opts, f"键盘选项里没有「{want}」：{opts[:12]}")
    out = []
    for option in ("18 键（主题：主题 18 键）", "14 键"):
        gear(d, "拼音")
        choose(d, "键盘", option)
        d.open_page("text")
        n = len(d.keys(refresh=True))
        check(n > 10, f"{option} 没弹出（{n} 个键）")
        out.append(f"{option} {n} 键")
    return "提示 5 个 commit:、k14 不用；q 打出你好；" + "、".join(out)


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


case("ST-82", needs="release:要一个比手机上旧的签名文字包（v1）；发版时在发布包上手工跑（问框、保留、替换、转屏）")(lambda d: None)
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
    """纠错's summary on the device: 双拼 only the ü-key slip, its 18 and 14 keys 不适用"""
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
    check("双拼：只认" in summary, f"说明里没有「双拼：只认」：{summary}")
    for im in ("双拼 · 18 键", "双拼 · 14 键", "双拼 · 九键"):
        check(im in summary.split("不适用")[-1], f"不适用里没有「{im}」：{summary}")
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
    """the 按键字符和快捷键 page on its tab [tab] (全键盘 … 功能键): the row of tabs scrolls sideways"""
    slots_page(d)
    for _ in range(3):
        s = d.screen()
        n = next((n for n in s.app() if n.cls.endswith("RadioButton") and n.text == tab), None)
        if n:
            d.tap(n.c, 1.5)
            return
        row = [n for n in s.app() if n.cls.endswith("RadioButton")]
        if not row:
            raise Blocked("按键字符和快捷键页上没有分页")
        y = row[0].c[1]
        d.swipe((row[-1].c[0], y), (row[0].c[0], y), 300, after=0.8)
    raise Blocked(f"按键字符和快捷键页上没有「{tab}」页")


def key_dialog(d, label):
    """a key of the page tapped (found by the first part of what a screen reader says of it): its dialog up"""
    s = d.screen()
    # by prefix, not page_key's split at commas: the comma key reads 「，键, .」
    n = next((n for n in s.app() if n.clickable and (n.desc == label or n.desc.startswith(label + ","))), None)
    if not n:
        raise Blocked(f"页上没有 {label} 键：{[n.desc for n in s.app() if n.clickable and n.desc][:12]}")
    d.tap(n.c, 0.5)
    return d.wait(lambda s: s.find(text="设为首选"), 5, what=f"{label} 的对话框")


def dialog_add(d, text):
    """添加 [text] (digits, typed on the keyboard's 123 page: adb's input text is a hardware keyboard's keys,
    which turn the soft keyboard into its small 实体键盘 bar) to the open key dialog's list"""
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
    d.tap(d.screen().find(text="添加").c, 0.6)
    d.sh("input keyevent 4")   # the keyboard down: 确定 under it
    time.sleep(0.8)


def dialog_pick(d, item):
    """an item of the open dialog's list selected (by what it reads out)"""
    s = d.screen()
    n = next((n for n in s.app() if n.clickable and n.desc == item), None)
    if not n:
        raise Blocked(f"对话框的列表里没有 {item}")
    d.tap(n.c, 0.4)


def dialog_way(d, way, option):
    """上滑 or 下滑's [option] (长按首选, 文字, 空) in the open dialog, scrolled to where it is"""
    for _ in range(3):
        s = d.screen()
        heads = sorted((n for n in s.app() if n.text in ("上滑", "下滑") and not n.clickable), key=lambda n: n.b[1])
        head = next((n for n in heads if n.text == way), None)
        if head:
            nxt = next((n for n in heads if n.b[1] > head.b[1]), None)
            opts = [n for n in s.app() if n.text.startswith(option) and n.b[1] > head.b[1] and (not nxt or n.b[1] < nxt.b[1])]
            if opts:
                d.tap(min(opts, key=lambda n: n.b[1]).c, 0.4)
                return
        dialog_scroll(d)
    raise Blocked(f"对话框里找不到{way}的「{option}」")


def dialog_text(d, way, pinyin, word):
    """上滑 or 下滑 set to the text [word], typed as [pinyin] and picked from the candidates"""
    dialog_way(d, way, "文字")
    eds = []
    for _ in range(3):
        s = d.screen()
        head = next((n for n in s.app() if n.text == way and not n.clickable), None)
        picked = next((n for n in sorted(s.app(), key=lambda n: n.b[1]) if n.text == "文字" and head and n.b[1] > head.b[1]), None)
        eds = [n for n in s.app() if n.cls.endswith("EditText") and picked and n.b[1] > picked.b[1]]
        if eds:
            break
        dialog_scroll(d)
    if not eds:
        raise Blocked(f"{way}的文字框不在屏上")
    d.tap(min(eds, key=lambda n: n.b[1]).c, 0.8)
    d.type(pinyin)
    c = d.cands(want=word)
    if word not in c:
        raise Blocked(f"打 {pinyin} 候选里没有 {word}：{c[:5]}")
    d.pick(word)
    d.sh("input keyevent 4")
    time.sleep(0.8)


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
    d.tap(page_key(d, "q").c, 0.5)
    # the dialog is taller than the screen: 上滑's 空 at the fold, 下滑's text field under it
    s = d.wait(lambda s: s if any(n.text == "空" for n in s.app()) else None, 5, what="q 的对话框")
    empties = sorted((n for n in s.app() if n.text == "空"), key=lambda n: n.b[1])
    if not empties:
        raise Blocked(f"对话框里没有上滑一段：{s.texts()[-16:]}")
    d.tap(empties[0].c, 0.4)
    e = empties[0].c
    d.swipe((e[0], e[1]), (e[0], e[1] - 600), 400, after=0.8)
    s = d.screen()
    texts = sorted((n for n in s.app() if n.text == "文字"), key=lambda n: n.b[1])
    if not texts:
        raise Blocked(f"对话框里没有下滑一段：{s.texts()[-16:]}")
    down = texts[-1]
    eds = [n for n in s.app() if "Edit" in n.cls and n.b[1] > down.b[1]]
    if not eds:
        raise Blocked(f"下滑的文字框不在屏上：{s.texts()[-16:]}")
    d.tap(min(eds, key=lambda n: n.b[1]).c, 0.8)
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
    d.tap_text("撤销", scroll=False, after=1.0)
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


case("ST-100", needs="jvm:PopupOverridesFragmentTest$Dialog.anotherKeyboardsLongPressIsSetByName、PopupOverridesFragmentTest$Dialog.everyKeysEditorStartsFromItsKeyboardsOwnLongPress、PopupOverridesFragmentTest$Dialog.aListBackToTheOwnStoresNothingAndOneEmptiedKeepsItsRow、SwipeSlotsUatTest.aGroupKeysLongPressSetByName、HandwritingSceneGesturesTest.theCommasLongPressSetByNameIsTheUsers")(lambda d: None)



case("ST-103", needs="manual:逐个点新内置主题看键盘、动态背景、按键音，重启后解锁前看简蓝；列表与不能删由 BuiltinPacksTest 查")(lambda d: None)
case("ST-102", needs="manual:要先装 0.6.0 测试版选 Nord/Deep Blue/Monokai 再覆盖安装；迁移由 ThemePrefsMigrationTest 查")(lambda d: None)


case("ST-108", needs="jvm:KeyListsUpgradeTest.v052sLongPressesMoveOverAndTheOldOnesStay、KeyListsUpgradeTest.beta2sNamedListsMoveOverToo、KeyListsUpgradeTest.aBackupImportedIsMigratedAtTheStartAfterOr0_6sKept")(lambda d: None)


case("ST-109", needs="jvm:PopupOverridesFragmentTest$Dialog.numberPage")(lambda d: None)


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
    return f"{len(names)} 个名字；横屏 {len(land)} 个"


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
    return f"读到的键缝（上下, 左右 dp；天青背景是图，只作参考）：天青跟随 {own}，选档 {set_}，Material 同档 {material}，改回 {back}"


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
    dialog_scroll(d)
    s = d.screen()
    note = [t for t in s.texts("all") if "画不下" in t or "显示为" in t]
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
    dialog_scroll(d)
    gone = [t for t in d.screen().texts("all") if "画不下" in t]
    dialog_ok(d)
    check(note, "下滑段下没有说键上画成什么")
    check(page and note and page[0].split("：", 1)[-1] == note[0], f"页面键下面的说明 {page} 和对话框的 {note} 不同")
    check(not gone, f"改成「好」后仍说 {gone}")
    return f"对话框「{note[0]}」；页面「{page[0] if page else ''}」；改成好后不说"
