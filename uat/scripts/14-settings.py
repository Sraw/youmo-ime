"""uat/14-settings.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import shutil
import subprocess
import tempfile
import time

from uat import (PKG, Blocked, add_im, case, check, choose, gear, has, home, grouped_keys, pinyin_keyboard, pinyin_keyboard_value,
                 script, shuangpin_keyboard)

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


# ---------------------------------------------------------------- 首页

HOME = ["输入法", "通用输入", "词库与短语", "外观", "按键", "声音与振动", "剪贴板与表情", "实体键盘",
        "备份与恢复", "界面语言"]
# ST-33's tour: every input method with a settings page of its own, added once
IMS = ["九键拼音", "手写", "双拼"]
TABLES = ["五笔", "二笔", "仓颉", "自然码", "五笔拼音"]


case("ST-01", needs="jvm:SettingsUatTest.theHomePage")(lambda d: None)


case("ST-02", needs="jvm:SettingsMenuUatTest.searchingFindsFuzzyPinyinAndGoesThere")(lambda d: None)


case("ST-03", needs="jvm:SettingsMenuUatTest.helpOpensTheManual")(lambda d: None)


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
    for x, last in (("滑行输入", "不适用：手写、拼音 14 键、双拼 18 键、九键双拼。"), ("表情候选", "不适用：手写。"),
                    ("纠正错拼和按偏的键", "不适用：九键拼音、拼音 14 键、九键双拼。")):
        check(summ.get(x, "").splitlines()[-1:] == [last], f"{x} 的说明最后一句：{summ.get(x)!r}")
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
    check(uv is not None and any(x.strip().rstrip("。") == "不适用：九键拼音、九键双拼" or [y.rstrip("。") for y in x.splitlines()[-1:]] == ["不适用：九键拼音、九键双拼"] for x in texts[uv:uv + 3]), f"模糊音 {texts[:20]}")
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


case("ST-34", needs="jvm:TablesUatTest.predictionOffHoldsForTablesToo")(lambda d: None)


# ---------------------------------------------------------------- 外观


case("ST-05", needs="jvm:SettingsUatTest.aDarkThemeTurnsPreviewAndKeyboardDark")(lambda d: None)


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


case("ST-22", needs="jvm:SettingsEffectUatTest.charactersAddedThenTheDefaultBack, KotlinHostKeyboardTest.aCircledLetterLongPressedOnTheFourteenKeysIsTypedAsText, KotlinHostKeyboardTest.aParenthesisedDigitLongPressedOnShuangpinsNineKeysIsTypedAsText, PopupOverridesFragmentTest$Dialog.aKeyCodeAloneIsRefusedAndTheDialogStays")(lambda d: None)


# ---------------------------------------------------------------- 其他

# also on the JVM (SettingsMenuUatTest.theLanguagesOfferedAreFour), which checks the choice and the
# locale; this one, that the keyboard's own text (EngineNotices and all) follows it and typing still works
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
    if not sp:
        raise Blocked("键盘上没有空格键（button_space）")
    descs = [n.desc for n in s.ime() if n.desc]
    cjk_kb = [x for x in descs + [sp.desc] if any("一" <= ch <= "鿿" for ch in x)]
    check(not cjk_kb, f"键盘上还有中文：{cjk_kb}")
    d.type("nihao")
    d.cands()
    d.space()
    t = d.field_text()
    check(t == "你好", f"打出「{t}」")
    d.hide_keyboard()
    # back by its English title (the row may be below the fold: goto scrolls); language_follow_system
    home(d, "App language")
    # the page is rebuilt for the new locale: its choices can come a few seconds after the title
    end, n = time.time() + 6, None
    while not n and time.time() < end:
        n = d.screen().find(text="Same as the phone")
        if not n:
            time.sleep(0.5)
    if not n:
        raise Blocked("界面语言里没有「Same as the phone」，没能改回跟随系统")
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


@case("ST-28", base="B0-设置", mutates=True)
def st28(d):
    set_comma(d, "、")
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


case("ST-31", needs="jvm:SettingsMenuUatTest.aboutListsVersionsSourceAndLicenses")(lambda d: None)


def switch_state(d, title):
    r = d.row(title)
    w = [n for n in r.walk() if n.cls in SWITCHES] if r else []
    return w[0].checked if w else None


# ---------------------------------------------------------------- 拼音的键盘

NOT_14 = "拼音 14 键"


@case("ST-40", base="B0-设置", mutates=True)
def st40(d):
    v = pinyin_keyboard_value(d)
    t = d.all_texts()
    ok = [x for x in t if NOT_14 in x]
    check(v == "全键盘", f"「键盘」默认是「{v}」")
    check(any("实体键盘仍按全拼打" in x for x in t), f"「键盘」的说明 {t[:16]}")
    check("拆字" in t and "中英混输" in t and len(ok) >= 2, f"拼音页的不适用 {ok}")
    home(d, "通用输入")
    t2 = d.all_texts()
    ok2 = [x for x in t2 if NOT_14 in x]
    check("纠正错拼和按偏的键" in t2 and "滑行输入" in t2 and len(ok2) >= 2, f"通用输入的不适用 {ok2}")
    pinyin_keyboard(d, "14 键")
    d.open_page("text")
    k = d.keys(refresh=True)
    check("qw" in k and "bn" in k, f"回到框里不是 14 键：{sorted(k)[:12]}")
    return f"默认「{v}」；不适用 {len(ok)}+{len(ok2)} 行；马上是 14 键"


def code_row_dp(d):
    s = d.screen()
    row = d.code_row(s)
    if not row:
        raise Blocked("没有编码行")
    return round((row.b[3] - row.b[1]) / d.dpx())


def kb_h(d):
    """the whole input view's height above the keys' bottom: from the composing row's top (its row is
    there, 18 or 32 dp, typing or not: the height must not jump while typing) to the keyboard's bottom. Not the
    IME window's top: that window spans the screen, so its top is the same whatever the board"""
    b = kb_box(d)
    row = d.code_row()
    if not row:
        raise Blocked("没有编码行（没打字时这一行也该在）")
    return b[3] - row.b[1]


@case("ST-41", base="B0-设置", mutates=True, visual=True)
def st41(d):
    add_im(d, "五笔")
    add_im(d, "九键拼音")
    add_im(d, "双拼")
    d.open_page("text")
    d.switch_im("拼音")   # the last added is the one on
    d.type("ni")
    d.cands()
    full = code_row_dp(d)
    d.clear()
    h_full = kb_h(d)
    d.hide_keyboard()
    pinyin_keyboard(d, "14 键")
    d.open_page("text")
    grouped_keys(d, "bn ui")
    d.cands()
    k14 = code_row_dp(d)
    d.clear()
    h = {"拼音": kb_h(d)}
    rows = {"拼音": code_row_dp(d)}
    for im in ("英文", "五笔", "九键"):
        d.switch_im(im)
        h[im] = kb_h(d)
        rows[im] = code_row_dp(d)
    d.shot("kb", "14 键时换到九键：编码行变回 18dp，键盘总高和全键盘一样")
    d.switch_im("拼音")
    h["拼音回来"] = kb_h(d)
    d.hide_keyboard()
    pinyin_keyboard(d, "全键盘")
    d.open_page("text")
    d.type("ni")
    d.cands()
    back = code_row_dp(d)
    d.clear()
    # 双拼's 18 keys: 32 dp there, 18 on English and 五笔 after them
    d.hide_keyboard()
    shuangpin_keyboard(d, "18 键")
    d.open_page("text")
    d.switch_im("双拼")
    grouped_keys(d, "bn io")
    d.cands()
    d.clear()
    rows18 = {"双拼": code_row_dp(d)}
    for im in ("英文", "五笔"):
        d.switch_im(im)
        rows18[im] = code_row_dp(d)
    d.hide_keyboard()
    shuangpin_keyboard(d, "全键盘")
    check(full == 18, f"全键盘时编码行 {full}dp")
    check(k14 == 32, f"14 键时编码行 {k14}dp")
    check(rows == {"拼音": 32, "英文": 18, "五笔": 18, "九键": 18}, f"14 键时各输入法的编码行 {rows}")
    # the keys keep their height: the 14 keys' board is the code row's 14 dp taller, the others as 全键盘's
    grew = (h["拼音"] - h_full) / d.dpx()
    check(abs(grew - 14) <= 2, f"14 键比全键盘高 {grew:.0f}dp（应为 14）")
    others = [h[im] for im in ("英文", "五笔", "九键")]
    check(max(abs(x - h_full) for x in others) <= 3, f"其他输入法的键盘总高 {h}，全键盘 {h_full}")
    check(abs(h["拼音回来"] - h["拼音"]) <= 3, f"换回拼音的总高 {h}")
    check(back == 18, f"改回全键盘后编码行 {back}dp")
    check(rows18 == {"双拼": 32, "英文": 18, "五笔": 18}, f"双拼 18 键时各输入法的编码行 {rows18}")
    return f"{full} → {k14} → {back} dp；编码行 {rows}；总高 {h}（全键盘 {h_full}）；18 键 {rows18}"


@case("ST-42", base="B0-设置", mutates=True)
def st42(d):
    add_im(d, "双拼")
    gear(d, "双拼")
    d.scroll_top()
    v = d.summary("键盘")
    if v is None:
        raise Blocked("双拼页没有「键盘」")
    t = d.all_texts()
    check(v.split("\n")[0] == "全键盘", f"「键盘」默认是「{v}」")
    check(any("重码" in x and "实体键盘" in x for x in t), f"「键盘」的说明 {t[:16]}")
    shuangpin_keyboard(d, "18 键")
    d.open_page("text")
    d.switch_im("双拼")
    k = d.keys(refresh=True)
    check("we" in k and "io" in k, f"回到框里不是 18 键：{sorted(k)[:16]}")
    grouped_keys(d, "bn io")
    d.cands()
    k18, sy = code_row_dp(d), d.syllables()
    d.clear()
    d.hide_keyboard()
    shuangpin_keyboard(d, "九键")
    d.open_page("text")
    d.switch_im("双拼")
    k = d.keys(refresh=True)
    check(all(c in k for c in "23456789"), f"回到框里不是九键：{sorted(k)[:16]}")
    d.taps([k["6"], k["4"]], gap=0.35, after=0.6)
    d.cands()
    k9, sy9 = code_row_dp(d), d.syllables()
    d.clear()
    d.hide_keyboard()
    shuangpin_keyboard(d, "全键盘")
    home(d, "通用输入")
    glide = d.summary("滑行输入") or ""
    check(k18 == 32 and sy, f"18 键时编码行 {k18}dp，音节条 {sy}")
    check(k9 == 32 and not sy9, f"九键时编码行 {k9}dp，音节条 {sy9}")
    check("双拼 18 键" in glide and "九键双拼" in glide, f"滑行输入的说明「{glide}」")
    return f"默认「{v.split(chr(10))[0]}」；18 键 {k18}dp {sy[:3]}；九键 {k9}dp"
