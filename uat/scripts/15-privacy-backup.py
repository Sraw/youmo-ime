"""uat/15-privacy-backup.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import PKG, Blocked, add_im, case, check, first, has, home, menu_tap, open_url, reset, script


def words13():
    """13-words.py's helpers (words, phrases)"""
    return script("13-words")

DOWNLOAD = "/sdcard/Download"


def perms(d):
    out = d.sh(f"dumpsys package {PKG}")
    lines = out.split("requested permissions:", 1)[1].splitlines()[1:] if "requested permissions:" in out else []
    got = []
    for ln in lines:
        ln = ln.strip()
        if not ln.startswith("android.permission.") and not ln.startswith(PKG) and "." not in ln[:12]:
            break
        got.append(ln.split(":")[0])
    return got


@case("PR-01")
def pr01(d):
    p = perms(d)
    check("android.permission.INTERNET" not in p, f"权限 {p}")
    check(p == ["android.permission.VIBRATE"], f"文字版的权限 {p}")
    return f"权限 {p}"


@case("PR-02")
def pr02(d):
    p = perms(d)
    bad = [x for x in p if any(w in x for w in ("NOTIFICATION", "CONTACTS", "LOCATION", "STORAGE", "MEDIA_", "CAMERA"))]
    check(not bad, f"有 {bad}")
    return f"只有 {p}；导入导出走系统文件选择（WD-09、PR-09）"


def password(d):
    open_url(d, "password")
    time.sleep(0.6)
    d._keys = None


@case("PR-03", mutates="learn", visual=True)
def pr03(d):
    password(d)
    lab = d.im_label()
    d.type("nihao")
    time.sleep(0.4)
    d.shot("field", "密码框打 nihao")
    for _ in range(6):   # WebView's accessibility text of a password field lags behind
        s = d.screen()
        c, t = d.candidates(s), d.field_text(s) or ""
        if len(t) >= 5:
            break
        time.sleep(0.4)
    check("英文" in lab, f"空格键「{lab}」")
    check(not c, f"有候选 {c[:3]}")
    # WebView hands the password's own text to accessibility; the dots are on the screenshot
    check(len(t) == 5, f"框里「{t}」")
    return f"空格「{lab}」，框里「{t}」"


@case("PR-04", base="B0-五笔", mutates="learn")
def pr04(d):
    password(d)
    lab1 = d.im_label()
    open_url(d, "text")
    time.sleep(0.6)
    d._keys = None
    lab2 = d.im_label()
    d.type("q")
    c = d.cands()
    check("英文" in lab1, f"密码框里「{lab1}」")
    check("五笔" in lab2, f"回到普通文本「{lab2}」")
    check(first(c) == "我", f"q 的候选 {c[:3]}")
    return f"「{lab1}」→「{lab2}」，q {c[:2]}"


@case("PR-05", mutates="learn")
def pr05(d):
    password(d)
    s = d.screen()
    kb = s.find(rid="keyboard_view", where="ime")
    row = [n for n in s.ime() if n.text in list("1234567890") and n.b[3] <= kb.b[1] + 5]
    digits = [n.text for n in sorted(row, key=lambda n: n.b[0])]
    check(digits == list("1234567890"), f"键盘上方 {digits}")
    for ch in "123":
        n = next(n for n in row if n.text == ch)
        d.tap(n.c, 0.3)
    time.sleep(0.4)
    t = d.field_text() or ""
    check(len(t) == 3, f"框里「{t}」")
    return f"数字行 {''.join(digits)}；框里 {len(t)} 个圆点"


case("PR-07", needs="skip:要 Chrome 无痕标签页；redroid 上的 WebView 测试浏览器没有无痕模式")(lambda d: None)
case("PR-08", needs="skip:要一个会把复制的密码标成敏感的密码管理器（用例：没有记阻塞）；redroid 上没有")(lambda d: None)


def backup(d):
    """备份全部 → 保存 in 下载; returns (the new file, any message seen)"""
    d.hide_keyboard()
    home(d, "备份与恢复")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("备份全部", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出保存界面：{d.top_activity()}")
    s = d.screen()
    name = next((n.text for n in s.app() if n.cls == "EditText"), "")
    d.tap_text("保存", where="all", scroll=False, after=0.3)
    msgs = []
    for _ in range(10):
        msgs += [n.text for n in d.screen().nodes if n.text and ("备份" in n.text or "成功" in n.text)]
        time.sleep(0.3)
    time.sleep(1.0)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    return name, new, msgs


def restore(d, fname):
    """从备份恢复 → fname; returns the confirmation's text, after the app came back"""
    d.hide_keyboard()
    home(d, "备份与恢复")
    d.tap_text("从备份恢复", after=1.2)
    # the warning comes first, then the file
    s = d.screen()
    warn = " ".join(n.text for n in s.nodes if n.rid == "message")
    b = next((n for n in s.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"没有确认：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(b.c, 2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(4):
        n = d.screen().find(text=fname)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {fname}")
    pid = d.sh(f"pidof {PKG}").strip()
    d.tap(n.c, 1.0)
    s = d.screen()
    b = next((n for n in s.nodes if n.rid == "button1"), None)   # a second confirmation, if any
    if b:
        d.tap(b.c, 1.0)
    for _ in range(20):
        now = d.sh(f"pidof {PKG}").strip()
        if now and now != pid:
            break
        time.sleep(1.0)
    restarted = bool(now) and now != pid   # "" : not back at all
    time.sleep(3.0)
    d.sh(f"ime enable {PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService; "
         f"ime set {PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
    return warn, restarted


def setup_dz_word(d):
    words13().dz(d)
    words13().add_word(d, "澜境花园", "lan jing hua yuan")
    # an imported base dictionary too: backups leave merged.ymd out, a restore merges it again
    words13().import_dict_words(d)


@case("PR-09", base="B0-设置", mutates=True)
def pr09(d):
    setup_dz_word(d)
    name, new, msgs = backup(d)
    check(new and new[0].startswith("youmo_") and new[0].endswith(".zip"), f"下载里新文件 {new}（保存名 {name}）")
    return f"{new[:1]}；提示 {msgs[:1] or '（toast 没抓到）'}"


@case("PR-10", base="B0-设置", mutates=True)
def pr10(d):
    setup_dz_word(d)
    name, new, msgs = backup(d)
    if not new:
        raise Blocked("前提没到：备份没有文件")
    reset(d, "B0-设置", "设置")
    warn, restarted = restore(d, new[0])
    c1 = words13().page_cands(d, "dz")
    d.clear()
    d.type("lanjinghuayuan")
    c2 = d.cands()
    d.clear()
    rows = words13().dictionaries(d)
    # compiled again after the restart: a few seconds for dict-words
    k = []
    for _ in range(10):
        k = words13().words(words13().page_cands(d, "kemiaotangpu"))
        d.clear()
        if "氪喵糖铺" in k:
            break
        time.sleep(3)
    # the name and its status (已开启 · N 个词) are separate texts of one row
    i = rows.index("dict-words") if "dict-words" in rows else -1
    check(i >= 0 and i + 1 < len(rows) and "个词" in rows[i + 1], f"基础词库一节 {rows}")
    check("氪喵糖铺" in k, f"kemiaotangpu {k[:4]}")
    check("覆盖" in warn, f"确认前的提示「{warn[:60]}」")
    check(restarted, "应用没有重启")
    check(first(c1) == "北京市海淀区", f"dz {c1[:3]}")
    check(first(c2) == "澜境花园", f"lanjinghuayuan {c2[:3]}")
    return f"提示有覆盖；重启了；dz {c1[:1]}；{c2[:1]}；基础词库 {rows[:3]}；kemiaotangpu {k[:2]}"


@case("PR-34", base="B0-设置", mutates=True)
def pr34(d):
    py = script("06-pinyin")
    d.open_page("text")
    d.switch_im("拼音")
    py.learn_sentence(d)
    d.type(py.SENTENCE_PY)
    c0 = d.cands()
    d.clear()
    if first(c0) != py.SENTENCE:
        raise Blocked(f"前提（PY-34）没到：{c0[:3]}")
    d.hide_keyboard()
    home(d, "备份与恢复")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("备份全部", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出保存界面：{d.top_activity()}")
    # turned while the picker is open: the settings page behind it is rebuilt before the save
    d.rotate(1)
    d.tap_text("保存", where="all", scroll=False, after=3.0)
    d.rotate(0)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("转屏后保存，下载里没有新文件")
    reset(d, "B0-设置", "设置")
    restore(d, new[0])
    d.open_page("text")
    d.switch_im("拼音")
    d.type(py.SENTENCE_PY)
    c = d.cands()
    d.clear()
    check(first(c) == py.SENTENCE, f"恢复后 {py.SENTENCE_PY} {c[:3]}")
    return f"{new[0]}；恢复后 {c[:3]}"


@case("PR-11", base="B0-设置", mutates=True)
def pr11(d):
    setup_dz_word(d)
    name, new, msgs = backup(d)
    if not new:
        raise Blocked("前提没到：备份没有文件")
    words13().add_phrase(d, "yx", [("raw", "test"), ("sym", "@"), ("raw", "example"), ("sym", "."), ("raw", "com")])
    c0 = words13().page_cands(d, "yx")
    if first(c0) != "test@example.com":
        raise Blocked(f"前提没到：yx {c0[:3]}")
    d.clear()
    restore(d, new[0])
    c = [x.split(" ")[0] for x in words13().page_cands(d, "yx")]
    check("test@example.com" not in c, f"恢复后 yx {c[:4]}")
    return f"恢复后 yx {c[:3]}"


case("PR-12", needs="skip:要 PR-08 的敏感剪贴板条目（密码管理器），redroid 上没有")(lambda d: None)
case("PR-13", needs="skip:要同一版本的语音版 APK（这次只构建了文字版调试包）")(lambda d: None)
case("PR-14", needs="skip:发版时用正式版跑（上一版正式版装上再覆盖安装这一版正式版，签名要一致）")(lambda d: None)


@case("PR-15", base="B0-设置", mutates=True)
def pr15(d):
    panels, toolbar = script("04-panels"), script("05-toolbar")
    d.tap_text("搜索设置", scroll=False)
    f = d.field()
    if not f:
        raise Blocked("搜索框没有焦点")
    d.tap(f.c, 0.8)
    d.switch_im("拼音")
    d.type("nihao")
    c = [x.split(" ")[0] for x in d.cands()]
    if len(c) < 2:
        raise Blocked(f"nihao 的候选 {c}")
    d.pick(2)
    d.press("符", after=1.0)
    panels.sym_category(d, "图形")
    shown = panels.selected_category(d)
    sym = next((x for x in shown[1:] if x != "★"), None)
    if not sym:
        raise Blocked(f"图形一类 {shown}")
    panels.sym_tap(d, sym)
    d.clear()
    d.switch_im("英文")
    toolbar.numpad(d, "1+1=")
    r = toolbar.result_on_bar(d)
    d.hide_keyboard()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("nihao")
    c2 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    d.press("符", after=1.0)
    try:
        panels.sym_category(d, "最近")
        recent = panels.selected_category(d)
    except Blocked:   # no 最近 category at all: nothing was recorded
        recent = []
    d.hide_keyboard()
    home(d, "词库与短语", "用户词库", "自动学到的词")
    rows = d.all_texts()
    check(not r, f"设置搜索框里打 1+1= 栏上有「{r.text if r else ''}」")
    check(c2 and c2[0] == c[0], f"回测试页 nihao 第一候选「{c2[:1]}」，原来「{c[0]}」（第二候选「{c[1]}」）")
    check(sym not in recent, f"符号面板最近里有设置里点的「{sym}」：{recent[:6]}")
    check(not any(c[1] in x for x in rows), f"自动学到的词里有「{c[1]}」：{rows[:8]}")
    return f"无算式结果；nihao 首选仍「{c2[0]}」；最近 {recent[:4]} 无「{sym}」；自动学到的词 {rows[:3]}"


@case("PR-16", base="B0-设置", mutates=True)
def pr16(d):
    d.tap_text("搜索设置", scroll=False)
    f = d.field()
    if not f:
        raise Blocked("搜索框没有焦点")
    d.tap(f.c, 0.8)
    d.switch_im("拼音")
    d.type("zai")
    c0 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    d.type("wo")
    d.pick("我")
    d.press("bksp", after=0.6)
    t = d.field_text() or ""
    if t:
        raise Blocked(f"退格后框里还有「{t}」")
    d.type("zai")
    c1 = [x.split(" ")[0] for x in d.cands()]
    d.clear()
    check(c0 and c1 and c1[0] == c0[0], f"删掉「我」后 zai 第一候选「{c1[:1]}」，空框时「{c0[:1]}」")
    return f"空框 zai {c0[:2]}；我 删掉后 {c1[:2]}"


# ---------------------------------------------------------------- 不用我的输入习惯、清掉最近

def status_entry(d, text):
    """⋯ (状态区域), then the entry named text; returns the entries' texts seen"""
    tb = script("05-toolbar")
    tb.status_panel(d)
    n = d.screen().find(text=text, where="ime")
    if not n:
        d.swipe((540, 2000), (540, 1650), 300, 0.8)
        n = d.screen().find(text=text, where="ime")
    if not n:
        raise Blocked(f"⋯ 里没有「{text}」：{d.screen().texts(where='ime')[:16]}")
    d.tap(n.c, 1.0)


def status_texts(d):
    script("05-toolbar").status_panel(d)
    t = d.screen().texts(where="ime")
    script("05-toolbar").back_to_keys(d)
    return t


def tap_emoji(d, ch="😀"):
    panels = script("04-panels")
    panels.ime_tap(d, desc="表情", after=1.0)
    n = panels.emoji_find(d, ch)
    if not n:
        raise Blocked(f"表情面板里找不到「{ch}」")
    d.tap(n.c, 0.6)
    panels.ime_tap(d, text="ABC", after=0.6)
    d._keys = None


def recent_emoji(d):
    """the emoji panel's first tab: (its header, the emoji on it)"""
    from uat import GRID_BOTTOM, GRID_TOP
    panels = script("04-panels")
    panels.ime_tap(d, desc="表情", after=1.0)
    hdr = panels.emoji_tab(d, 0)
    s = d.screen()
    got = [n.text for n in sorted(s.ime(), key=lambda n: (n.b[1], n.b[0]))
           if n.text and GRID_TOP < n.c[1] < GRID_BOTTOM and n.text not in panels.CATS]
    panels.ime_tap(d, text="ABC", after=0.6)
    d._keys = None
    return hdr, got


def ta_dl_twice(d):
    """PY-54's steps: after 他, 点了 picked twice for dl"""
    for _ in range(2):
        d.type("ta")
        d.cands(want="他")
        d.pick("他")
        d.type("dl")
        d.cands(want="点了")
        d.pick("点了")
        d.clear()


@case("PR-17", mutates=True)
def pr17(d):
    py = script("06-pinyin")
    py.learn_sentence(d)
    c0, cc = py.pin_fifth(d)
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()
    words13().dz(d)
    d.open_page("text")
    d.switch_im("拼音")
    tap_emoji(d)
    d.clear()
    tb = script("05-toolbar")
    tb.status_panel(d)
    d.tap(d.screen().find(text="不用我的输入习惯", where="ime").c, 0.1)
    # said once as it goes off (the chip above the keyboard, up 2 s), and nothing kept on the bar after
    # the chip tapped from the dump that found it: another dump first may outlast its 2 s, the tap
    # then landing on the page above the keyboard
    chip = None
    end = time.time() + 2.0
    while chip is None and time.time() < end:
        chip = d.screen().find(contains="已不用我的输入习惯，在 ⋯ 里恢复", where="all")
    said = chip is not None
    # a tap on it takes it away, and only it: the panel under it stays
    if chip:
        d.tap(chip.c, 0.3)
    s = d.screen()
    tapped_away = chip is None or not s.find(contains="已不用我的输入习惯，在 ⋯ 里恢复", where="all")
    panel_kept = bool(s.find(text="恢复我的输入习惯", where="ime"))
    tb.back_to_keys(d)
    time.sleep(2)
    kept = [t for t in d.screen().texts(where="ime") if "输入习惯" in t]
    d.type("wangxiaoming")
    w = d.cands()
    d.clear()
    d.type("shi")
    s1 = d.all_cands()
    d.clear()
    d.type("dz")
    z = d.all_cands()
    d.clear()
    hdr, _ = recent_emoji(d)
    ent = status_texts(d)
    status_entry(d, "恢复我的输入习惯")
    script("05-toolbar").back_to_keys(d)
    hdr2, rec2 = recent_emoji(d)
    check(said, "关掉时没有提示「已不用我的输入习惯，在 ⋯ 里恢复」")
    check(not kept, f"键盘上留着 {kept}")
    check(tapped_away and panel_kept, f"点提示：消失 {tapped_away}，⋯ 面板还在 {panel_kept}")
    check(first(w) == "王小明" and not has(w, "汪小明"), f"wangxiaoming {w[:4]}")
    # 是 is blocked (the blocks still apply), so "the first as before" is the first of c0 without 是
    was = first([x for x in c0 if first([x]) != "是"])
    check(first(s1) == was and first(s1) != cc and not has(s1, "是"), f"shi {s1[:5]}（原来 {c0[:3]}，C=「{cc}」）")
    check(not has(z, "北京市海淀区"), f"dz {z[:4]}")
    check(hdr != "最近使用", f"表情面板第一页是「{hdr}」")
    check("恢复我的输入习惯" in ent, f"⋯ 里 {ent[:16]}")
    check(hdr2 == "最近使用" and any(x.startswith("😀") for x in rec2), f"恢复后表情第一页「{hdr2}」{rec2[:5]}")
    return f"弹出提示、不留标记；wangxiaoming {w[:2]}；shi {s1[:2]}；dz {z[:2]}；表情第一页「{hdr}」；恢复后「{hdr2}」{rec2[:2]}"


@case("PR-17a", base="B0-设置", mutates=True)
def pr17a(d):
    """habits off: the base dictionaries stay, in pinyin and in a table; a word added by hand does not"""
    w13 = words13()
    w13.import_dict_words(d)
    add_im(d, "五笔")
    w13.add_word(d, "鲸园", "jing yuan")
    time.sleep(5.0)   # the imported words get their table codes in the background
    d.open_page("text")
    d.switch_im("拼音")
    status_entry(d, "不用我的输入习惯")
    script("05-toolbar").back_to_keys(d)
    time.sleep(2.5)   # the chip above the keyboard gone
    d.type("liangziwenhuacun")
    py = w13.words(d.all_cands())
    d.clear()
    d.type("jingyuan")
    own = w13.words(d.all_cands())
    d.clear()
    d.switch_im("五笔")
    d.type("jbys")
    time.sleep(0.4)
    wb = w13.words(d.all_cands())
    d.clear()
    d.switch_im("拼音")
    status_entry(d, "恢复我的输入习惯")
    script("05-toolbar").back_to_keys(d)
    check("量子文化村" in py, f"liangziwenhuacun {py[:5]}")
    check("鲸园" not in own, f"jingyuan {own[:5]}")
    check("量子计算机" in wb and "量子文化村" in wb, f"jbys {wb[:8]}")
    return f"拼音 {py[:2]}；五笔 jbys {wb[:5]}"


@case("PR-21", mutates=True)
def pr21(d):
    ta_dl_twice(d)
    tap_emoji(d)
    d.clear()
    c0, cc = script("06-pinyin").pin_fifth(d)
    status_entry(d, "清掉最近")
    menu_tap(d, "最近 1 小时")
    time.sleep(0.8)
    s = d.screen()
    msg = " ".join(n.text for n in s.nodes if n.rid in ("message", "alertTitle"))
    b = next((n for n in s.nodes if n.text == "清掉"), None)
    if not b:
        raise Blocked(f"没有确认框：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(b.c, 0.1)
    # the result, said above the keys (the chip, 2 s)
    cleared = script("05-toolbar").said(d, "已清掉")
    time.sleep(1.4)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("ta")
    d.cands(want="他")
    d.pick("他")
    d.type("dl")
    c = d.cands()
    d.clear()
    d.type("shi")
    s1 = d.cands()
    d.clear()
    hdr, rec = recent_emoji(d)
    check("退回恢复前" in msg and "不能" in msg, f"确认框「{msg}」")
    check(cleared, "清掉后没有提示「已清掉」")
    check(first(c) == "带来" and c[1:2] != ["点了"], f"「他 dl」{c[:4]}")
    check(first(s1) == cc, f"shi {s1[:4]}（C=「{cc}」）")
    check(hdr != "最近使用" or not any(x.startswith("😀") for x in rec), f"表情第一页「{hdr}」{rec[:6]}")
    return f"确认框写明不能退回；他 dl {c[:3]}；shi {s1[:2]}；最近 {rec[:3]}"


@case("PR-23", mutates="learn")
def pr23(d):
    from uat import sym_char
    d.switch_im("英文")
    for i in range(2):
        d.type("abc")
        sym_char(d, "@")
        d.type("example")
        sym_char(d, ".")
        d.type("com")
        d.space()
    d.switch_im("拼音")
    for i in range(2):
        d.press("123", after=0.8)
        k = d.keys(refresh=True)
        d.taps([k[c] for c in "13800000000"], after=0.4)
        script("04-panels").ime_tap(d, text="ABC", after=0.6)
        d._keys = None
        d.space()
    t = d.field_text()
    if t.count("abc@example.com") != 2 or t.count("13800000000") != 2:
        raise Blocked(f"框里「{t}」")
    rows = []
    w = words13()
    for page in ("手动添加的词", "自动学到的词"):
        w.user_lexicon(d, page)
        rows += w.list_texts(d)
    bad = [r for r in rows if any(x in r for x in ("138", "0000", "@", "example.com", "abc@"))]
    check(not bad, f"用户词库里有 {bad[:4]}")
    return f"框里「{t}」；用户词库 {rows[:6]}"


# ---------------------------------------------------------------- 自动备份

def backups(d):
    """用户词库 → 学习备份: the rows under 备份"""
    words13().user_lexicon(d, "学习备份")
    t = d.all_texts()
    return t[t.index("备份") + 1:] if "备份" in t else t


@case("PR-26", mutates=True, visual=True)
def pr26(d):
    ta_dl_twice(d)
    b0 = backups(d)
    if not any("这次安装" in r for r in b0):
        raise Blocked(f"前提没到：学习备份 {b0[:6]}")
    d.back()
    d.tap_text("清空学到的", after=1.0)
    s = d.screen()
    b = next((n for n in s.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"清空没有确认框：{[n.text for n in s.nodes if n.text][:8]}")
    d.tap(b.c, 2.0)
    b1 = backups(d)
    mine = [b1[i - 1] for i, r in enumerate(b1) if "这次安装" in r and i]
    old = [b0[i - 1] for i, r in enumerate(b0) if "这次安装" in r and i]
    n = d.screen().find(text=mine[0]) if mine else None
    if not n:
        raise Blocked(f"清空后学习备份 {b1[:6]}")
    d.tap(n.row().c, 1.0)
    d.tap_text("恢复", where="all", scroll=False, after=1.0)
    s = d.screen()
    ok = next((n for n in s.nodes if n.rid == "button1"), None)
    if ok:
        d.tap(ok.c, 0.6)
    # the toast is in no UI dump (03-t9's note): 已恢复 is read off the picture
    d.shot("full", "恢复后的提示（应为\"已恢复\"）")
    time.sleep(2.4)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("ta")
    d.cands(want="他")
    d.pick("他")
    d.type("dl")
    c = d.cands()
    d.clear()
    check(len(mine) == 1 and not set(mine) & set(old), f"清空后这次安装的备份 {mine}（之前 {old}）")
    check(first(c) == "带来", f"恢复后「他 dl」{c[:4]}")
    return f"之前 {old[:1]}；清空后 {mine}；恢复后 {c[:3]}"


@case("PR-31", base="B0-设置", mutates=True)
def pr31(d):
    w = words13()
    w.add_word(d, "蓝鲸花园", "lan jing hua yuan")
    name = w.export_lexicon(d)
    w.cut_exports(d, name)
    w.user_lexicon(d, "手动添加的词")
    before = w.list_texts(d)
    d.back()
    d.tap_text("学习备份", after=1.2)
    d.tap_text("从备份恢复", after=2.5)
    msgs, opts, dialog = w.pick_refused(d, "cut2.fxlb")
    d.open_settings()
    w.user_lexicon(d, "手动添加的词")
    after = w.list_texts(d)
    check(any("不是本输入法的备份" in m for m in msgs), f"提示 {msgs[:3]}")
    check(not opts and not dialog, f"出了选择 {opts[:3]} / 确认框 {dialog[:1]}")
    check(after == before, f"用户词库 {before[:4]} → {after[:4]}")
    return f"cut2.fxlb「{next((m for m in msgs if '不是' in m), '')}」；没有确认框；用户词库不变"


@case("PR-27", mutates=True)
def pr27(d):
    d.type("nihao")
    d.cands()
    d.space()
    words13().user_lexicon(d)
    on = words13().switch_on(d, "自动备份")
    d.tap_text("学习备份", after=1.2)
    t = d.all_texts()
    rows = t[t.index("备份") + 1:] if "备份" in t else t
    # a row: its date, then "这次安装存的 · 第 n 个打字日"
    mine = [rows[i - 1] + " " + r for i, r in enumerate(rows) if "这次安装" in r and i]
    warn = any("卸载输入法会丢掉学到的东西和自动备份" in x and "除非另存过一份" in x for x in t)
    if not mine:
        raise Blocked(f"第一次上屏后没有备份：{t[:10]}")
    today = d.sh("date +%Y年%-m月%-d日").strip()

    def delete(confirm):
        n = d.screen().find(text=mine[0].split(" ")[0] + " " + mine[0].split(" ")[1])
        d.tap(n.row().c, 1.0)
        d.tap_text("删除", where="all", scroll=False, after=1.0)
        s = d.screen()
        msg = " ".join(x.text for x in s.nodes if x.rid in ("message", "alertTitle"))
        b = next((x for x in s.nodes if x.rid == ("button1" if confirm else "button2")), None)
        if not b:
            raise Blocked(f"删除没有确认框：{[x.text for x in s.nodes if x.text][:8]}")
        d.tap(b.c, 1.2)
        return msg
    msg = delete(False)
    kept = any(mine[0].startswith(x) and "日 " in x for x in d.all_texts())
    delete(True)
    after = d.all_texts()
    check(on is True, f"自动备份开关 {on}")
    check(today in mine[0] and "第 1 个打字日" in mine[0], f"备份「{mine[0]}」（今天 {today}）")
    check(warn, f"页里没写卸载会丢：{t[-4:]}")
    check("不能找回" in msg, f"确认框「{msg}」")
    check(kept and "还没有备份" in after, f"取消后还在 {kept}；删除后 {after[:8]}")
    return f"「{mine[0]}」；确认框「{msg}」；删除后还没有备份"


@case("PR-32", base="B0-设置", mutates=True)
def pr32(d):
    w = words13()
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "屏蔽")
    menu_tap(d, "确定屏蔽")
    d.clear()
    w.user_lexicon(d, "学习备份")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("另存一份到看得见的文件夹", after=1.0)
    d.tap_text("确定", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"另存没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("另存后下载里没有新文件")
    reset(d, "B0-设置", "设置")
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    d.cands()
    d.long_pick("是")
    menu_tap(d, "固定首位")
    d.clear()
    d.open_settings()
    w.user_lexicon(d, "学习备份")
    d.tap_text("从备份恢复", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(12):
        n = d.screen().find(text=new[0])
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {new[0]}")
    d.tap(n.c, 2.5)
    d.tap_text("确定", after=3.0)
    d.open_settings()
    w.user_lexicon(d, "屏蔽的词")
    blocked = w.list_texts(d)
    d.back()
    d.tap_text("固定的词", after=1.2)
    pinned = w.list_texts(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    c = [x.split(" ")[0] for x in d.all_cands()]
    d.clear()
    check("是" in blocked, f"屏蔽的词 {blocked[:5]}")
    check(not any(r.startswith("是") for r in pinned), f"固定的词 {pinned[:5]}")
    check("是" not in c, f"shi {c[:6]}")
    return f"屏蔽的词 {blocked[:3]}；固定的词 {pinned[:3]}；shi {c[:4]}"


@case("PR-33", base="B0-设置", mutates=True)
def pr33(d):
    w = words13()
    # a real backup's head padded past 64 MB: an all-zero file is refused by its head, never by size
    saved = _save_copy(d)
    d.sh(f"cd {DOWNLOAD} && (cat '{saved}'; head -c 68157440 /dev/zero) > big.fxlb && rm -f '{saved}'")
    d.open_settings()
    w.user_lexicon(d, "手动添加的词")
    before = w.list_texts(d)
    d.back()
    d.tap_text("学习备份", after=1.2)
    t0 = time.time()
    d.tap_text("从备份恢复", after=2.5)
    msgs, opts, dialog = w.pick_refused(d, "big.fxlb")
    secs = time.time() - t0
    d.sh(f"rm -f {DOWNLOAD}/big.fxlb")
    d.open_settings()
    w.user_lexicon(d, "手动添加的词")
    after = w.list_texts(d)
    check(any("不是本输入法的备份" in m for m in msgs), f"提示 {msgs[:3]}")
    check(not opts and not dialog, f"出了选择 {opts[:3]} / 确认框 {dialog[:1]}")
    check(after == before, f"用户词库 {before[:4]} → {after[:4]}")
    return f"big.fxlb「{next((m for m in msgs if '不是' in m), '')}」；{secs:.0f} 秒；用户词库不变"


# ---------------------------------------------------------------- R11: restore merges, 退回恢复前

def _save_copy(d):
    w = words13()
    w.user_lexicon(d, "学习备份")
    before = set(d.sh(f"ls -1 {DOWNLOAD}").splitlines())
    d.tap_text("另存一份到看得见的文件夹", after=1.0)
    d.tap_text("确定", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"另存没出保存界面：{d.top_activity()}")
    d.tap_text("保存", where="all", scroll=False, after=2.5)
    new = sorted(set(d.sh(f"ls -1 {DOWNLOAD}").splitlines()) - before)
    if not new:
        raise Blocked("另存后下载里没有新文件")
    return new[0]


def _restore_from(d, fname):
    words13().user_lexicon(d, "学习备份")
    d.tap_text("从备份恢复", after=2.5)
    if "documentsui" not in d.top_activity():
        raise Blocked(f"没出文件选择：{d.top_activity()}")
    for _ in range(12):
        n = d.screen().find(text=fname)
        if n:
            break
        d.swipe((540, 1900), (540, 900), 400)
    else:
        raise Blocked(f"下载里没有 {fname}")
    d.tap(n.c, 2.5)
    d.tap_text("确定", after=0.6)
    d.shot("full", "恢复后的提示（应为\"已恢复\"）")
    time.sleep(2.4)


def _typed(d, code, want=None):
    d.open_page("text")
    d.switch_im("拼音")
    d.type(code)
    c = [x.split(" ")[0] for x in d.cands(want=want)]
    d.clear()
    return c


def _pr24(d):
    """PR-24's 前提 and 操作 (restored twice); returns (C, D)"""
    py = script("06-pinyin")
    d.open_page("text")   # B0-设置 starts on the settings
    d.switch_im("拼音")
    py.learn_sentence(d)
    c0, cc = py.pin_fifth(d)
    fname = _save_copy(d)
    d.open_page("text")
    d.switch_im("拼音")
    d.type("shi")
    d.cands()
    m = d.long_pick(cc)
    un = next((x for x in m if "取消" in x), None)
    if not un:
        raise Blocked(f"「{cc}」的菜单 {m[:8]}")
    menu_tap(d, un)
    d.clear()
    d.type("shi")
    c = [x.split(" ")[0] for x in d.cands()]
    dd = next(x for x in c[1:6] if x != cc)
    d.long_pick(dd)
    menu_tap(d, "固定首位")
    d.clear()
    w = words13()
    w.add_word(d, "鲸喵屿", "jing miao yu")
    _restore_from(d, fname)
    _restore_from(d, fname)
    return cc, dd


@case("PR-24", base="B0-设置", mutates=True, visual=True)
def pr24(d):
    cc, dd = _pr24(d)
    s = _typed(d, "shi")
    j = _typed(d, "jingmiaoyu", want="鲸喵屿")
    w = _typed(d, "wangxiaoming")
    check(first(s) == dd, f"shi {s[:3]}（C=「{cc}」，D=「{dd}」）")
    check("鲸喵屿" in j, f"jingmiaoyu {j[:3]}")
    check(first(w) == "汪小明", f"wangxiaoming {w[:3]}")
    return f"C=「{cc}」 D=「{dd}」；shi {s[:2]}；{j[:1]}；{w[:2]}"


@case("PR-25", base="B0-设置", mutates=True)
def pr25(d):
    cc, dd = _pr24(d)
    words13().user_lexicon(d, "学习备份")
    d.tap_text("退回恢复前", after=1.0)
    s0 = d.screen()
    msg = " ".join(n.text for n in s0.nodes if n.rid in ("message", "alertTitle"))
    b = next((n for n in s0.nodes if n.rid == "button1"), None)
    if not b:
        raise Blocked(f"没有确认框：{[n.text for n in s0.nodes if n.text][:8]}")
    d.tap(b.c, 0.6)
    d.shot("full", "退回后的提示（应为\"已退回恢复前\"）")
    time.sleep(2.4)
    s = _typed(d, "shi")
    j = _typed(d, "jingmiaoyu", want="鲸喵屿")
    check(all(x in msg for x in ("学到", "固定", "屏蔽")), f"确认框「{msg}」")
    check(first(s) == dd, f"shi {s[:3]}（D=「{dd}」）")
    check("鲸喵屿" in j, f"jingmiaoyu {j[:3]}")
    return f"「{msg[:40]}」；shi {s[:2]}；{j[:1]}"
