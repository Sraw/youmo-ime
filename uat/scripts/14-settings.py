"""uat/14-settings.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import shutil
import subprocess
import tempfile
import time

from uat import PKG, Blocked, Fail, add_im, case, check, choose, gear, has, home, popup_pick, script

SWITCHES = ("Switch", "SwitchCompat", "SwitchMaterial", "CheckBox")


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


def key_nodes(d, label, s=None):
    """a key's label nodes (the letter, then its corner's)"""
    n = d.key_node(label, s)
    return [m for m in n.walk() if m.text] if n else []


# ---------------------------------------------------------------- 首页

HOME = ["输入法", "通用输入", "词库与短语", "外观", "按键", "声音与振动", "剪贴板与表情", "实体键盘",
        "备份与恢复", "界面语言"]
# ST-33's tour: every input method with a settings page of its own, added once
IMS = ["九键拼音", "手写", "双拼"]
TABLES = ["五笔", "二笔", "仓颉", "自然码", "五笔拼音"]


@case("ST-01", base="B0-设置")
def st01(d):
    rows = d.pref_rows()
    titles = [r[0] for r in rows]
    s = d.screen()
    check(not any(r[2] for r in rows), f"有分组标题：{[r[0] for r in rows if r[2]]}")
    check(titles == HOME, f"入口是 {titles}")
    nosum = [r[0] for r in rows if not r[1]]
    check(not nosum, f"没有说明：{nosum}")
    icons = [x for x in ("搜索设置", "使用说明", "更多选项") if not s.find(desc=x)]
    check(not icons, f"右上角缺 {icons}")
    return "十个入口依次正确、都有说明；🔍 ? ⋮ 都在"


@case("ST-02", base="B0-设置", mutates="learn")
def st02(d):
    d.tap_text("搜索设置", scroll=False)
    f = d.field()
    if not f:
        raise Blocked("搜索框没有焦点")
    d.tap(f.c, 0.8)
    d.type("mohuyin")
    d.cands(want="模糊音")
    d.pick("模糊音")
    time.sleep(0.8)
    s = d.screen()
    res = [n for n in s.app() if n.text == "模糊音" and "Edit" not in n.cls]
    check(res, f"结果 {s.texts()[:8]}")
    where = [n.text for n in s.app() if n.text.startswith("通用输入")]
    check(where, f"结果没写在通用输入下：{s.texts()[:8]}")
    d.tap(res[0].c, 2.5)
    s = d.screen()
    title = [n.text for n in s.app() if n.parent and n.parent.rid == "toolbar" and n.text]
    check(title and title[0] == "通用输入", f"打开的是 {title}")
    check(d.visible("模糊音", s), "通用输入打开了，但模糊音那一项不在屏幕上")
    return f"结果「模糊音」（{where[0].splitlines()[0]}），点后到通用输入并看得到模糊音"


@case("ST-03", base="B0-设置")
def st03(d):
    d.tap_text("使用说明", scroll=False, after=3.0)
    top = d.top_activity()
    s = d.screen()
    url = next((n.text for n in s.app() if n.rid == "url_field"), "")
    check("webview_shell" in top or "browser" in top.lower(), f"前台是 {top}")
    check("github.com" in url, f"地址 {url}")
    return f"浏览器打开 {url}"


# ---------------------------------------------------------------- 通用输入

@case("ST-32", base="B0-设置", mutates=True)
def st32(d):
    home(d, "通用输入")
    d.tap_text("模糊音", after=1.0)
    d.tap_text("z ↔ zh", scroll=False)
    d.tap_text("确定", scroll=False, after=1.0)
    choose(d, "性能档位", "低档")
    gear(d, "拼音")
    d.switch("中英混输", False)
    home(d, "实体键盘")
    choose(d, "每页候选数", "5")
    home(d, "通用输入")
    # read off the whole page: a longer summary above (滑行输入) can push it below the fold
    perf = next((sm for t, sm, _ in d.pref_rows() if t == "性能档位"), None) or ""
    d.tap_text("模糊音", after=1.0)
    s = d.screen()
    z = next((n for n in s.app() if n.text.startswith("z ↔ zh")), None)
    zon = bool(z and z.checked)
    d.tap_text("取消", scroll=False)
    check(zon, "z↔zh 没勾着")
    check(perf.startswith("低档"), f"性能档位是「{perf}」")
    d.open_page("text")
    d.type("zongguo")
    c = d.cands()
    check(has(c, "中国"), f"zongguo 候选 {c}")
    return f"z↔zh 勾着，档位「{perf.splitlines()[0]}」，zongguo {c[:3]}"


@case("ST-33", base="B0-设置", mutates=True)
def st33(d):
    d.step("加输入法")
    for im in IMS + TABLES:
        add_im(d, im)

    d.step("词库与短语")
    home(d, "词库与短语")
    t = d.all_texts()
    lead = next((x for x in t if x.startswith("用户词库的词排得靠前")), None)
    order = [x for x in t if x in ("用户词库", "基础词库", "导入", "短语", "新词")]
    check(lead and "还没选过的，可能排在很常用的同音词后面" in lead, f"最上面 {t[1:2]}")
    check([x for i, x in enumerate(order) if i == 0 or x != order[i - 1]] == ["用户词库", "基础词库", "导入", "短语"],
          f"分组 {order}")
    check("新词" not in t, "有新词分组")
    for x in ("内置词库", "常用词和近几年的新词，随应用更新 · 只读", "导入词库", "自定义短语"):
        check(x in t, f"没有「{x}」")

    d.step("用户词库")
    home(d, "词库与短语", "用户词库")
    on = {x: switch_state(d, x) for x in ("用我的输入习惯", "自动备份")}
    texts = d.all_texts()
    check(on == {"用我的输入习惯": True, "自动备份": True}, f"页顶开关 {on}")
    miss = [x for x in ("清空学到的", "学习备份", "英文词库", "导出") if x not in texts]
    check(not miss, f"用户词库页缺 {miss}：{texts[:20]}")
    d.tap_text("学习备份", after=1.2)
    bk = d.all_texts()
    miss = [x for x in ("从备份恢复", "另存一份到看得见的文件夹", "退回恢复前") if x not in bk]
    check(not miss, f"学习备份里缺 {miss}：{bk[:8]}")
    d.back()
    d.tap_text("英文词库", after=1.2)
    here = d.screen().texts()
    script("08-english").en_settings(d)
    d.tap_text("英文词库", after=1.2)
    there = d.screen().texts()
    check(here == there, f"两处英文词库不是同一页：{here[:5]} / {there[:5]}")

    d.step("通用输入")
    home(d, "通用输入")
    rows = d.pref_rows()
    cats = [r[0] for r in rows if r[2]]
    check(cats == ["所有输入法", "中文输入法", "拼音（全拼、双拼、九键）"], f"分组 {cats}")
    summ = {r[0]: r[1] for r in rows}
    for x in ("滑行输入", "表情候选"):
        check(summ.get(x, "").splitlines()[-1:] == ["不适用：手写。"], f"{x} 的说明最后一句：{summ.get(x)!r}")
    x = "纠正错拼和按偏的键"
    check(summ.get(x, "").splitlines()[-1:] == ["不适用：九键拼音。"], f"{x} 的说明最后一句：{summ.get(x)!r}")
    sec, inside = [], False
    for r in rows:
        if r[2]:
            inside = r[0] == "中文输入法"
        elif inside:
            sec.append(r)
    bad = [r[0] for r in sec if "不适用：英文" in r[1]]
    dt = next((r for r in sec if r[0] == "自带的日期时间"), None)
    check(dt and dt[1].splitlines()[-1:] == ["不适用：手写。"], f"中文输入法一节里的自带的日期时间：{dt}")
    check(not bad, f"中文输入法一节里有不适用：英文：{bad}")
    d.scroll_top()
    d.tap_text("模糊音", after=1.0)
    s = d.screen()
    texts = s.texts(where="all")
    uv = next((i for i, x in enumerate(texts) if "u ↔ v" in x or "u↔v" in x), None)
    check(uv is not None and any("不适用：九键拼音" in x for x in texts[uv:uv + 3]), f"模糊音 {texts[:20]}")
    check([n for n in s.nodes if n.cls in ("CheckBox", "CheckedTextView") and n.enabled], "模糊音里的勾选框不能勾")
    d.tap_text("取消", scroll=False)

    d.step("拼音")
    gear(d, "拼音")
    t = d.all_texts()
    check(t and "通用输入设置" in t[:4], f"最上面是 {t[:4]}")
    check("中英混输" in t, f"没有中英混输：{t[:20]}")
    bad = [x for x in ("每页候选数", "联想下一个词", "性能档位", "纠正错拼和按偏的键", "模糊音") if x in t]
    check(not bad, f"拼音页里有 {bad}")
    d.scroll_top()
    d.tap_text("通用输入设置", after=1.2)
    check(d.screen().find(text="通用输入"), f"点通用输入设置后是 {d.screen().texts()[:4]}")

    d.step("九键拼音")
    gear(d, "九键拼音")
    t = d.all_texts()
    want = ["通用输入设置", "连按选字母", "九键英文打法", "按键字母", "按键长按"]
    check([x for x in t if x in want] == want, f"九键页 {t[:14]}")
    check(any("只有这一种读法" in x for x in t), f"连按选字母的说明 {t[:14]}")
    check(any(x.rstrip("。").endswith("总是连按") for x in t), f"九键英文打法的说明 {t[:14]}")
    # a password field switches to English on the full keyboard: never 九键英文's 连按
    check(not any("连按" in x and "密码" in x for x in t), f"九键英文打法的说明还写密码 {t[:14]}")

    d.step("手写")
    gear(d, "手写")
    t = d.all_texts()
    check(t and "通用输入设置" in t[:4], f"最上面 {t[:4]}")
    check("停笔多久算写完一个字" in t, f"没有停笔：{t[:12]}")
    bad = [x for x in ("每页候选数", "联想下一个词", "性能档位") if x in t]
    check(not bad, f"手写页里有 {bad}")

    d.step("双拼")
    gear(d, "双拼")
    t = d.all_texts()
    check("方案" in t and "中英混输" not in t, f"双拼设置 {t[:12]}")

    sw = {}
    for im in TABLES:
        d.step(im)
        gear(d, im)
        t = d.all_texts()
        d.scroll_top()
        sw[im] = {x: switch_state(d, x) if x in t else None
                  for x in ("只有一个候选时直接上屏", "显示编码提示", "调频")}
        if any(v is None and x in t for x, v in sw[im].items()):
            d.scroll()   # a switch below the fold
            sw[im].update({x: switch_state(d, x) for x, v in sw[im].items() if v is None and x in t})
        check("恢复码表顺序" in t, f"{im} 没有恢复码表顺序：{t[:12]}")
        check(sw[im]["调频"] is False, f"{im} 的调频 {sw[im]['调频']}")
        # 自动造词's two are gone (U-28 C46)
        gone = [x for x in ("最长造多少字的词", "造出的词何时记住") if x in t]
        check(not gone, f"{im} 还有 {gone}")
        if im == "五笔":
            check(t and "通用输入设置" in t[:4], f"五笔页最上面 {t[:4]}")
            check(sw[im]["只有一个候选时直接上屏"] and sw[im]["显示编码提示"], f"五笔 {sw[im]}")
    check(sw["仓颉"]["显示编码提示"] is False, f"仓颉 {sw['仓颉']}")

    d.step("实体键盘")
    home(d, "实体键盘")
    rows = d.pref_rows()
    want = [("显示候选窗口", "根据输入设备而定"), ("实体键盘快捷键", None), ("候选词列表方向", "自动"),
            ("浮动候选窗字号", "20 sp"), ("每页候选数", "7")]
    got = {r[0]: r[1] for r in rows if not r[2]}
    titles = [r[0] for r in rows if not r[2]]
    for x, v in want:
        check(x in got, f"没有「{x}」：{titles}")
        if v:
            check(got[x].splitlines()[0] == v, f"「{x}」是「{got[x]}」")
    check(titles[-1] == "每页候选数", f"最后一项是 {titles[-1]}")
    check("所有输入法都用" in got["每页候选数"], f"每页候选数说明「{got['每页候选数']}」")
    check(switch_state(d, "实体键盘快捷键"), "实体键盘快捷键不是开的")

    d.step("首页")
    d.open_settings()
    titles = [r[0] for r in d.pref_rows()]
    check(titles == HOME, f"首页入口 {titles}")
    return f"词库与短语、用户词库、通用输入、{len(IMS) + 1 + len(TABLES)} 个输入法页、实体键盘都对；首页 {len(titles)} 项"


@case("ST-34", base="B0-设置", mutates=True)
def st34(d):
    # 前提 B0-五笔: 输入法 里添加"五笔"，切到五笔
    home(d, "输入法")
    d.tap_text("添加", scroll=False, after=1.2)
    d.tap_text("五笔", after=1.2)
    s = d.screen()
    if s.find(text="确定"):
        d.tap_text("确定", scroll=False, after=1.0)
    s = d.screen()
    if not (s.find(text="五笔") or s.find(contains="五笔")):
        raise Blocked(f"添加五笔后输入法页 {s.texts()[:8]}")
    home(d, "通用输入")
    d.switch("联想下一个词", False)
    d.open_page("text")
    for _ in range(4):
        sp = d.screen().find(rid="button_space", where="ime")
        if sp and "五笔" in sp.desc:
            break
        d.press("globe")
    else:
        raise Blocked("地球键切不到五笔")
    d.type("khlg")
    time.sleep(0.6)
    s = d.screen()
    t, p = d.field_text(s), d.preedit(s)
    if t != "中国":
        c = d.candidates(s)
        if p and has(c, "中国"):
            d.pick("中国")
            t = d.field_text()
    if t != "中国":
        raise Blocked(f"khlg 没打出中国：框里「{t}」")
    s = d.screen()
    c = d.candidates(s)
    check(not c, f"上屏后栏上有联想 {c}")
    return "「中国」上屏，栏上没有联想"


# ---------------------------------------------------------------- 外观

def dark(lum):
    return lum < 90


def darkest_theme(d, s=None, top=None, pool=None):
    """the darkest theme in view (of [pool], the app's nodes by default), below [top] (the
    preview's bottom by default): built-in themes are told apart by their look, not by a name"""
    s = s or d.screen()
    top = preview_box(d, s)[3] + 100 if top is None else top
    items = [n for n in (pool if pool is not None else s.app()) if n.cls == "ViewGroup" and "新增主题" not in n.texts() and
             n.b[2] - n.b[0] > 250 and 200 < n.b[3] - n.b[1] < 300 and top < n.b[1] and n.b[3] < 2200]
    if not items:
        raise Blocked("主题列表里找不到主题")
    im = d.png()
    return min(items, key=lambda n: d.lum(n.b, im))


@case("ST-05", base="B0-设置", mutates=True)
def st05(d):
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统夜间模式", False)
    appearance_tab(d, "主题")
    s = d.screen()
    before = d.lum(preview_box(d, s))
    d.tap(darkest_theme(d, s).c, 1.2)
    after = d.lum(preview_box(d))
    check(dark(after), f"预览亮度 {before:.0f} → {after:.0f}，没变深")
    d.open_page("text")
    kl = d.lum(kb_box(d))
    check(dark(kl), f"键盘亮度 {kl:.0f}，不是深色")
    return f"预览亮度 {before:.0f} → {after:.0f}；键盘 {kl:.0f}"


IMG = None


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
    # (PixelDark), not the light one ST-08 saw with 跟随 on
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


@case("ST-35", base="B0-设置", mutates=True)
def st35(d):
    appearance_tab(d, "大小与样式")
    d.switch("跟随系统夜间模式", False)
    appearance_tab(d, "主题")
    check(not custom_item(d), "开始前列表里就有自己的主题")
    d.tap_text("新增主题", after=1.2)
    d.tap_text("复制内置主题…", contains=True, scroll=False, after=1.5)
    s = d.screen()
    title = d.visible("复制内置主题", s, where="all")
    if not title:
        raise Blocked(f"没出内置主题列表：{s.texts(where='all')[:8]}")
    # the dialog's own items, not the theme list behind it
    root = title
    while root.parent is not None:
        root = root.parent
    d.tap(darkest_theme(d, s, top=title.b[3], pool=list(root.walk())).c, 1.5)
    appearance_tab(d, "主题")
    item = custom_item(d)
    check(item, "复制后列表里没有可编辑的主题")
    d.tap(item.c, 1.5)
    d.open_page("text")
    kl = d.lum(kb_box(d))
    check(dark(kl), f"选用复制的深色主题后键盘亮度 {kl:.0f}")
    return f"复制出可编辑的主题；选用后键盘亮度 {kl:.0f}"


@case("ST-36", base="B0-设置", mutates=True)
def st36(d):
    appearance_tab(d, "主题")
    s = d.screen()
    item = darkest_theme(d, s)
    d.tap(item.c, 1.2)
    s = d.screen()
    msg = d.visible("不能手动更换主题", s, where="all", contains=True)
    check(msg, f"点主题没出提示：{s.texts(where='all')[:8]}")
    d.tap_text("确定", where="all", scroll=False, after=1.0)
    d.open_page("text")
    k1 = d.lum(kb_box(d))
    check(not dark(k1), f"点「确定」后主题换了（键盘亮度 {k1:.0f}）")
    appearance_tab(d, "主题")
    d.tap(darkest_theme(d).c, 1.2)
    d.tap_text("禁用它", where="all", scroll=False, after=1.5)
    d.open_page("text")
    k2 = d.lum(kb_box(d))
    check(dark(k2), f"点「禁用它」后没换成深色主题（键盘亮度 {k2:.0f}）")
    appearance_tab(d, "大小与样式")
    check(not d.switch("跟随系统夜间模式", False), "点「禁用它」后跟随系统夜间模式还开着")
    return f"确定：主题不变（{k1:.0f}）；禁用它：跟随关了，换成深色（{k2:.0f}）"


@case("ST-10", base="B0-设置", mutates=True)
def st10(d):
    hs = {}
    for opt in ("矮", "很高"):
        appearance_tab(d, "大小与样式")
        choose(d, "键盘高度", opt)
        appearance_tab(d, "大小与样式")
        pv = preview_box(d)
        d.open_page("text")
        kb = keys_box(d)
        hs[opt] = (pv[3] - pv[1], kb[3] - kb[1])
    (p1, k1), (p2, k2) = hs["矮"], hs["很高"]
    check(p2 > p1, f"预览高度 矮 {p1} 很高 {p2}")
    check(k2 > k1 * 1.15, f"键盘高度 矮 {k1} 很高 {k2}")
    return f"键盘 矮 {k1}px → 很高 {k2}px；预览 {p1} → {p2}"


@case("ST-11", base="B0-设置", mutates=True)
def st11(d):
    d.open_page("text")
    s = d.screen()
    k0, w0 = keys_box(d, s), d.key_node("q", s).b
    appearance_tab(d, "大小与样式")
    choose(d, "键盘两侧边距", "宽")
    choose(d, "键盘底部边距", "多")
    d.open_page("text")
    s = d.screen()
    k1, w1 = keys_box(d, s), d.key_node("q", s).b
    check(k1[0] > k0[0] + 10 and k1[2] < k0[2] - 10, f"左右没多出空白：键区 x {k0[0]}..{k0[2]} → {k1[0]}..{k1[2]}")
    check(k1[3] < k0[3] - 10, f"下面没多出空白：键区底 {k0[3]} → {k1[3]}")
    check(w1[2] - w1[0] < w0[2] - w0[0], f"键没变窄：q 宽 {w0[2] - w0[0]} → {w1[2] - w1[0]}")
    return f"键区 x {k0[0]}..{k0[2]} → {k1[0]}..{k1[2]}，底 {k0[3]} → {k1[3]}，q 宽 {w0[2] - w0[0]} → {w1[2] - w1[0]}"


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


@case("ST-12", base="B0-设置", mutates=True, visual=True)
def st12(d):
    appearance_tab(d, "大小与样式")
    pv = preview_box(d)
    box = (pv[0] - 10, pv[1] - 10, pv[2] + 10, pv[3] + 10)
    d.shot(box, "预览：默认")
    d.switch("启用按键边框", False)
    d.shot(box, "关掉按键边框")
    d.switch("启用按键边框", True)
    choose(d, "按键间距", "宽松")
    d.shot(box, "边框开，间距宽松")
    choose(d, "按键圆角", "直角")
    d.shot(box, "圆角：直角")
    return "看图：平铺 / 缝隙变大 / 没有圆角"


@case("ST-13", base="B0-设置", mutates=True)
def st13(d):
    appearance_tab(d, "大小与样式")
    choose(d, "标点位置", "无")
    d.open_page("text")
    s = d.screen()
    corners = {k: [m.text for m in key_nodes(d, k, s)][1:] for k in "qasz"}
    check(not any(corners.values()), f"键角还有小字 {corners}")
    q = d.keys(refresh=True)["q"]
    d.swipe(q, (q[0], q[1] + 130), 150)
    t = d.field_text()
    check(t == "1", f"下滑 q 打出「{t}」")
    appearance_tab(d, "大小与样式")
    choose(d, "标点位置", "底部")
    d.open_page("text")
    s = d.screen()
    tx = key_nodes(d, "q", s)
    check(len(tx) >= 2 and tx[1].b[1] > tx[0].b[1], f"底部时 q 键 {[(m.text, m.b) for m in tx]}")
    return f"无：键角没有小字，下滑 q 打出「{t}」；底部：小字「{tx[1].text}」在字母下方"


# ---------------------------------------------------------------- 按键

@case("ST-14", base="B0-设置", mutates=True)
def st14(d):
    home(d, "按键")
    d.switch("按键时弹出显示字符", False)
    d.open_page("text")
    s = d.screen()
    a = d.key_node("a", s)
    x, y = a.c
    d.touch(("down", x, y), ("sleep", 0.25))
    s = d.screen()
    d.touch(("up", x, y))
    pop = [n for n in s.ime() if n.text in ("a", "A") and n.b[3] <= a.b[1] + 10]
    check(not pop, f"按住时上方有 {[(n.text, n.b) for n in pop]}")
    return "按住 a 时上方没有弹出字符"


@case("ST-15", base="B0-设置", mutates=True)
def st15(d):
    home(d, "按键")
    choose(d, "划动输入标点符号及数字", "上划")
    d.open_page("text")
    w = d.keys()["w"]
    d.swipe(w, (w[0], w[1] - 130), 150)
    t1 = d.field_text()
    d.swipe(w, (w[0], w[1] + 130), 150)
    s = d.screen()
    t2, p = d.field_text(s), d.preedit(s)
    check(t1 == "2", f"上滑打出「{t1}」")
    if not (t2 == "2" and p == "w"):
        d.clear()
        d.swipe(w, (w[0], w[1] + 70), 150)
        p70 = d.preedit()
        raise Fail(f"下滑（和上滑一样 130 px）后框里「{t2}」、打字状态 `{p}`，没当作点了 w；只滑 70 px 时 `{p70}`")
    d.clear()
    home(d, "按键")
    choose(d, "划动输入标点符号及数字", "禁用")
    d.open_page("text")
    w = d.keys(refresh=True)["w"]
    out = []
    for dy in (-200, 200):   # past the key's edge either way
        d.swipe(w, (w[0], w[1] + dy), 150)
        s = d.screen()
        out.append((d.field_text(s) or "", d.preedit(s)))
        d.clear()
    check(out == [("", ""), ("", "")], f"禁用后上下滑出键外：{out}（框里, 打字状态）")
    return f"上划：上滑「{t1}」，下滑当作 w（`{p}`）；禁用：滑出键外什么也不打"


@case("ST-16", base="B0-设置", mutates=True)
def st16(d):
    home(d, "按键")
    choose(d, "长按延迟", "长")
    d.open_page("text")
    s = d.screen()
    q = d.key_node("q", s)
    x, y = q.c
    d.touch(("down", x, y), ("sleep", 0.3), ("up", x, y))
    time.sleep(0.4)
    p = d.preedit()
    check(p == "q", f"按 0.3 秒后打字状态 `{p}`")
    d.clear()
    d.touch(("down", x, y), ("sleep", 0.6))
    s = d.screen()
    pop = [n.text for n in s.ime() if n.text and n.b[3] <= q.b[1] + 10 and n.b[1] > q.b[1] - 600]
    d.touch(("move", x + 1, y), ("sleep", 0.05), ("move", x, y + 300), ("up", x, y + 300))
    check(len(pop) >= 2, f"按 0.6 秒没弹出长按字符：{pop}")
    return f"0.3 秒打出 q；0.6 秒弹出 {pop[:6]}"


@case("ST-17", base="B0-设置", mutates=True)
def st17(d):
    home(d, "按键")
    choose(d, "空格键长按行为", "轮换输入法")
    d.open_page("text")
    s = d.screen()
    sp = s.find(rid="button_space", where="ime")
    before = sp.desc
    d.hold(sp.c, 1000)
    time.sleep(0.6)
    after = d.screen().find(rid="button_space", where="ime").desc
    check("英文" in after or "English" in after, f"空格键 {before} → {after}")
    return f"空格键「{before}」→「{after}」"


@case("ST-18", base="B0-设置", mutates=True)
def st18(d):
    home(d, "按键")
    d.switch("划动空格键以移动光标", False)
    d.open_page("long")
    f = d.field()
    if not f:
        raise Blocked("长文本框没有焦点")
    d.tap((f.b[2] - 20, f.b[3] - 15), 0.6)   # after the last line: the cursor at the end
    d.type("a")
    d.space()
    t0 = d.field_text()
    if not t0.endswith("啊"):
        raise Blocked(f"光标不在末尾：打 a 空格后「{t0[-12:]}」")
    sp = d.keys()["space"]
    d.swipe(sp, (sp[0] - 300, sp[1]), 500)
    d.type("a")
    d.space()
    t1 = d.field_text()
    ok = t1 in (t0 + "啊", t0 + " 啊")
    check(ok, f"滑后再打 a 空格：「…{t1[-14:]}」（之前「…{t0[-10:]}」）")
    return f"左滑后光标还在末尾：「…{t1[-6:]}」"


@case("ST-19", base="B0-设置", mutates=True)
def st19(d):
    home(d, "通用输入")
    d.switch("括号和引号成对输入，光标停在中间", False)
    d.open_page("text")
    popup_pick(d, "k", "（")
    t = d.field_text()
    check(t == "（", f"框里是「{t}」")
    return f"框里是「{t}」"


@case("ST-20", base="B0-设置", mutates=True)
def st20(d):
    d.open_page("text")
    w0 = d.screen().find(rid="button_space", where="ime").b
    home(d, "按键")
    d.switch("显示语言切换键", False)
    d.open_page("text")
    s = d.screen()
    g = s.find(rid="button_lang", where="ime")
    w1 = s.find(rid="button_space", where="ime").b
    check(not g, "底排还有地球键")
    check(w1[2] - w1[0] > w0[2] - w0[0], f"空格宽 {w0[2] - w0[0]} → {w1[2] - w1[0]}")
    return f"没有地球键，空格宽 {w0[2] - w0[0]} → {w1[2] - w1[0]}"


def key_editor(d, key):
    home(d, "按键", "按键字符")
    s = d.screen()
    k = next((n for n in s.app() if n.text == key and n.row() is not None), None)
    if not k:
        raise Blocked(f"按键字符页没有 {key} 键")
    d.tap(k.row().c, 1.2)


@case("ST-21", base="B0-设置", mutates=True)
def st21(d):
    key_editor(d, "q")
    d.tap_text("Q", scroll=False)
    d.tap_text("设为首选", scroll=False)
    d.tap_text("确定", scroll=False, after=1.0)
    d.open_page("text")
    s = d.screen()
    tx = [m.text for m in key_nodes(d, "q", s)]
    check(tx[1:2] == ["Q"], f"q 键上是 {tx}")
    q = d.keys(refresh=True)["q"]
    d.swipe(q, (q[0], q[1] + 130), 150)
    t = d.field_text()
    check(t == "Q", f"下滑 q 打出「{t}」")
    return f"q 键角 Q，下滑打出「{t}」"


def symbol(d, char):
    """the 符 panel's 数学 page: tap char"""
    s = d.screen()
    for _ in range(6):
        m = d.visible("数学", s, where="ime")
        if m:
            d.tap(m.c, 0.8)
            break
        col = s.find(text="常用", where="ime")
        x = col.c[0] if col else 97
        d.swipe((x, 1950), (x, 1600), 300)
        s = d.screen()
    else:
        raise Blocked("符号面板里找不到数学")
    for _ in range(8):
        s = d.screen()
        n = d.visible(char, s, where="ime")
        if n:
            d.tap(n.c, 0.5)
            return
        d.swipe((540, 2000), (540, 1650), 300)
    raise Blocked(f"数学里找不到 {char}")


@case("ST-22", base="B0-设置", mutates=True)
def st22(d):
    key_editor(d, "a")
    s = d.screen()
    e = next((n for n in s.app() if "Edit" in n.cls), None)
    if not e:
        raise Blocked("没有添加字符的输入框")
    d.tap(e.c, 1.0)
    d.press("符")
    symbol(d, "α")
    if d.screen().find(rid="keyboard_view", where="ime"):
        d.space()
        d.press("符")
    else:
        d.tap_text("空格", where="ime", scroll=False)
    symbol(d, "β")
    t = d.field_text()
    if t != "α β":
        raise Blocked(f"添加框里是「{t}」")
    d.hide_keyboard()
    d.tap_text("添加", scroll=False)
    d.tap_text("确定", scroll=False, after=1.0)
    d.open_page("text")

    def popup():
        k = d.key_node("a")
        x, y = k.c
        d.touch(("down", x, y), ("sleep", 0.9))
        s = d.screen()
        d.touch(("move", x + 1, y), ("sleep", 0.05), ("move", x, y + 300), ("up", x, y + 300))
        return [n.text for n in s.ime() if n.text and n.b[3] <= k.b[1] + 10 and n.b[1] > k.b[1] - 700]
    p1 = popup()
    check("α" in p1 and "β" in p1, f"长按 a 弹出 {p1}")
    key_editor(d, "a")
    for label in ("重置", "恢复默认", "默认"):
        s = d.screen()
        n = s.find(text=label)
        if n:
            d.tap(n.c, 1.0)
            break
    else:
        # the editor has no restore button: the key's row in the list has it (long-press)
        d.back()
        s = d.screen()
        k = next((n for n in s.app() if n.text == "a"), None)
        d.hold(k.row().c, 900)
        time.sleep(0.8)
        s = d.screen()
        n = s.find(contains="恢复默认") or s.find(contains="默认")
        if not n:
            raise Blocked(f"找不到对 a 恢复默认的地方：{s.texts()[:12]}")
        d.tap(n.c, 1.0)
    s = d.screen()
    ok = s.find(text="确定")
    if ok:
        d.tap(ok.c, 1.0)
    d.open_page("text")
    p2 = popup()
    check("α" not in p2 and "β" not in p2, f"恢复后长按 a 仍有 {p2}")
    return f"加后 {p1[:5]}…；恢复后 {p2[:5]}…"


# ---------------------------------------------------------------- 其他

@case("ST-25", base="B0-设置", mutates=True)
def st25(d):
    home(d, "界面语言")
    d.tap_text("English", scroll=False, after=2.5)
    d.open_settings()
    s = d.screen()
    titles = [n.text for n in s.app() if n.rid == "title"]
    cjk = [t for t in titles if any("一" <= ch <= "鿿" for ch in t)]
    check(titles and not cjk, f"设置页 {titles[:6]}")
    d.open_page("text")
    s = d.screen()
    sp = s.find(rid="button_space", where="ime")
    descs = [n.desc for n in s.ime() if n.desc]
    cjk_kb = [x for x in descs + [sp.desc] if any("一" <= ch <= "鿿" for ch in x)]
    check(not cjk_kb, f"键盘上还有中文：{cjk_kb}")
    d.type("nihao")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"打出「{t}」")
    d.hide_keyboard()
    d.open_settings()
    d.tap_text(titles[-1] if titles else "Language", after=1.0)
    s = d.screen()
    n = s.find(contains="ollow") or s.find(contains="ystem")
    if n:
        d.tap(n.c, 2.0)
    return f"设置 {titles[:3]}；空格「{sp.desc}」；打出「{t}」"


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


def record_copy(d, word="公园"):
    """the keyboard's bar (and the band above it a hint may slide through) while 复制 is tapped on the
    edit panel, as 05-toolbar's copy_word does it: the hint is recorded as it comes in, not
    screenshotted a second and more later, when any animation has long ended (F3548)"""
    tbs = script("05-toolbar")
    d.open_page("long")
    f = d.field()
    d.tap((f.b[0] + 18, f.b[1] + 55), 0.6)
    tbs.edit_panel(d)
    s = d.screen()
    right = s.find(desc="向右移动光标", where="ime") or s.find(text="向右移动光标", where="ime")
    sel = s.find(desc="选择", where="ime") or s.find(text="选择", where="ime")
    cp = s.find(desc="复制", where="ime") or s.find(text="复制", where="ime")
    ns = [n for n in s.ime() if n.b[3] > n.b[1]]
    if not (right and sel and cp and ns):
        raise Blocked(f"编辑面板上缺按钮：{[n.desc or n.text for n in s.ime() if n.desc or n.text][:10]}")
    i = tbs.LONG.index(word)
    if i:
        d.taps([right.c] * i, gap=0.05, after=0.3)
    d.tap(sel.c, 0.3)
    d.taps([right.c] * len(word), gap=0.05, after=0.3)
    # the notice sits above the window (said above the keys, UndoChipPlacement.top; below it or over
    # the bar only with the window at the screen's top, never so when docked): the band above the
    # window down to the bar's bottom, not the panel's buttons below it (their press feedback) nor
    # the screen's bottom (the system's clipboard overlay), which would count as frames of their own
    back = s.find(desc="返回键盘", where="ime")
    # the window-wide containers start at the status bar's bottom and are left out (as LS-27):
    # a band from there would be the whole screen, the notice too small a part of it to count
    outer = min(n.b[1] for n in ns)
    top = min((n.b[1] for n in ns if n.b[1] > outer), default=outer)
    bottom = back.b[3] if back else max(n.b[3] for n in ns)
    r = record_change(d, max(0, top - 250), bottom, lambda: d.tap(cp.c, 1.0))
    tbs.ime(d, desc="把光标移动到结尾", after=0.3)
    tbs.back_to_keys(d)
    return r


@case("ST-26", base="B0-设置", mutates=True, visual=True)
def st26(d):
    d.open_page("text")
    on, n_on = record_toolbar(d)
    pon, _ = record_panel(d)
    con, _ = record_copy(d)
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
    # the setting reaches the notice too: copied, it is in place and opaque at once
    d.step("禁用动画时的提示")
    if con < 2:
        raise Blocked(f"开着动画时复制的提示也只录到 {con} 个过渡帧，分不出来")
    coff, _ = record_copy(d)
    d.shot("full", "禁用动画，复制后的提示")
    check(coff <= 1, f"禁用动画后复制的提示仍有 {coff} 个过渡帧（开着时 {con}）")
    # Android's own 移除动画 does the same with the setting off
    d.step("系统移除动画")
    appearance_tab(d, "大小与样式")
    d.switch("禁用动画", False)
    d.sh("settings put global animator_duration_scale 0")
    try:
        d.open_page("text")
        sys0, _ = record_toolbar(d)
        csys, _ = record_copy(d)
        d.shot("full", "系统移除动画，复制后的提示")
    finally:
        d.sh("settings put global animator_duration_scale 1")
    check(sys0 <= 1, f"系统移除动画后工具栏仍有 {sys0} 个过渡帧")
    check(csys <= 1, f"系统移除动画后复制的提示仍有 {csys} 个过渡帧")
    return (f"过渡帧：工具栏 开着 {on}、禁用后 {off}、系统移除动画 {sys0}；剪贴板面板 开着 {pon}、禁用后 {poff}；"
            f"复制的提示 开着 {con}、禁用后 {coff}、系统移除动画 {csys}")


@case("ST-28", base="B0-设置", mutates=True)
def st28(d):
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
    m = d.keys()["m"]
    d.swipe(m, (m[0], m[1] + 130), 150)     # 、 is m's corner character
    t = d.field_text()
    if t != "、":
        raise Blocked(f"改成「、」没成功：框里「{t}」")
    d.hide_keyboard()
    d.tap_text("确定", scroll=False, after=1.0)
    d.open_page("text")
    d.press(",")
    t = d.field_text()
    check(t == "、", f"点底排的，打出「{t}」")
    return f"打出「{t}」"


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


@case("ST-31", base="B0-设置")
def st31(d):
    home(d, "更多选项", "关于")
    texts = d.all_texts()
    want = ["版本", "提交时间", "基础数据", "源代码", "隐私政策", "许可"]
    miss = [w for w in want if w not in texts]
    check(not miss, f"关于页缺 {miss}")
    ver = texts[texts.index("当前版本") + 1] if "当前版本" in texts else "?"
    d.tap_text("第三方库许可证", after=2.0)
    s = d.screen()
    libs = [n.text for n in s.app() if n.text and n.rid != "title" and len(n.text) > 3]
    check(len(libs) >= 5, f"许可证页 {libs[:6]}")
    return f"版本 {ver}；许可证页 {len(libs)} 行，如 {libs[1][:20]}"


def switch_state(d, title):
    r = d.row(title)
    w = [n for n in r.walk() if n.cls in SWITCHES] if r else []
    return w[0].checked if w else None
