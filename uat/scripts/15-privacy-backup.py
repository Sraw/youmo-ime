"""uat/15-privacy-backup.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import time

from uat import PKG, Blocked, case, check, first, home, open_url, reset, script


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


case("PR-06", needs="skip:要 Chrome 无痕标签页；redroid 上的 WebView 测试浏览器没有无痕模式")(lambda d: None)
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
    check("覆盖" in warn, f"确认前的提示「{warn[:60]}」")
    check(restarted, "应用没有重启")
    check(first(c1) == "北京市海淀区", f"dz {c1[:3]}")
    check(first(c2) == "澜境花园", f"lanjinghuayuan {c2[:3]}")
    return f"提示有覆盖；重启了；dz {c1[:1]}；{c2[:1]}"


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
