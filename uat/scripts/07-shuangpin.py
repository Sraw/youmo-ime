"""uat/07-shuangpin.md (B0-双拼). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import case, check, choose, gear, grouped_keys, keys_at, script


@case("SP-30", base="B0-双拼", mutates="learn")
def sp30(d):
    # only the words typed before are read by a key alone: each in whole syllables once first
    d.type("ufmeuihb")
    d.pick("什么时候")
    d.clear()
    d.type("ufmuh")
    c = d.all_cands()
    d.shot("kb", "自然码 ufmuh（全码打过一次以后）：第一个候选是「什么时候」")
    check(c[:1] == ["什么时候"], f"ufmuh 的第一个候选不是「什么时候」：{c[:14]}")
    d.pick("什么时候")
    t = d.field_text()
    check(t == "什么时候", f"点「什么时候」后框里是「{t}」")
    d.clear()
    d.type("yivizl")
    d.pick("一直在")
    d.clear()
    d.type("yivz")
    c2 = d.all_cands()
    d.shot("kb", "自然码 yivz：候选栏或展开后的第一页有「一直在」")
    check("一直在" in c2, f"yivz 的候选栏和展开后的第一页都没有「一直在」：{c2[:14]}")
    return f"框里是「{t}」；yivz 候选 {c2[:8]}"




@case("SP-47", base="B0-双拼", mutates=True)
def sp47(d):
    # 小鹤: q tapped at its bottom right corner, toward a, is ta; in the middle t q
    gear(d, "双拼")
    choose(d, "双拼方案", "小鹤")
    d.open_page("text")
    d.switch_im("双拼")
    keys_at(d, "tq", [None, (0.85, 0.95)])
    edge = d.preedit()
    c = d.cands()
    d.shot("kb", "小鹤 t、q 靠 a 点：编码行是 ta，首选「他」")
    d.clear()
    keys_at(d, "tq", [None, None])
    mid = d.preedit()
    d.clear()
    check(edge.replace(" ", "") == "ta", f"q 靠 a 点的 t q，编码行是「{edge}」，候选 {c[:6]}")
    check(mid.replace(" ", "") != "ta", f"两个键都点在正中的 t q，编码行是「{mid}」")
    return f"靠边「{edge}」{c[:3]}；正中「{mid}」"


@case("SP-57", base="B0-双拼", mutates="learn")
def sp57(d):
    # 自然码 ky: y tapped by its right edge reads ku too, so 可以 comes among the first 10, not right after the first
    d.type("keyi")
    d.pick("可以")
    d.clear()
    keys_at(d, "ky", [None, (0.95, 0.5)])
    # two pages: the slip's own reading, ku, is ranked by its score, maybe past the first
    edge = d.all_cands(pages=2)
    d.shot("kb", "自然码 k、y 靠 u 点（打过「可以」）：第一页有「可以」")
    d.clear()
    keys_at(d, "ky", [None, None])
    mid = d.all_cands()
    d.clear()
    check("可以" in edge[:10], f"y 靠 u 点的 k y，前 10 个没有「可以」：{edge[:12]}")
    # 苦 alone is ku's only: the slip's reading kept beside the word typed, not dropped for it
    check("苦" in edge, f"y 靠 u 点的 k y，候选里没有 ku 的「苦」：{edge[:24]}")
    check(mid[1:2] == ["可以"], f"两个键都点在正中的 k y，「可以」不是第二：{mid[:6]}")
    return f"靠边 {edge[:6]}（有「苦」）；正中 {mid[:3]}"


@case("SP-51", base="B0-18键", mutates=True, visual=True)
def sp51(d):
    # the 18 keys with Q and WE swapped are the 18 keys, 双拼's too, drawn as the layout has them
    script("06-pinyin").import_grouped(d, "uat-k18-swapped-1.0", "UAT 换位 18 键")
    check(d.wait_text("现在就用在", timeout=6, where="all", contains=True), f"没问用在哪：{d.screen().texts('all')[:10]}")
    for im in ("拼音", "双拼"):
        check(d.screen().find(text=im, where="all"), f"「现在就用在」里没有{im}")
    d.tap_text("双拼", where="all", scroll=False, after=0.5)
    d.tap_text("确定", where="all", scroll=False, after=2.0)
    d.open_page("text")
    d.switch_im("双拼")
    k = d.keys(refresh=True)
    check("we" in k and "q" in k and k["we"][0] < k["q"][0], f"WE 不在 Q 左边：we {k.get('we')} q {k.get('q')}")
    d.shot("ime", "双拼用换位的 18 键：第 1 行 WE 在 Q 左边")
    # 自然码 ni hk
    grouped_keys(d, "bn io h jk")
    c = d.cands()
    check("你好" in c[:5], f"BN IO H JK 的候选 {c[:8]}")
    d.pick("你好")
    text = d.field_text()
    d.clear()
    check(text == "你好", f"框里是 {text!r}")
    gear(d, "双拼")
    check(d.wait_text("UAT 换位 18 键（自定义）", timeout=5, contains=True), f"双拼的「键盘」没写 UAT 换位 18 键（自定义）：{d.screen().texts()[:16]}")
    return f"拼音、双拼都能用；WE 在 Q 左边；候选 {c[:3]}；点「你好」上屏；键盘写 UAT 换位 18 键（自定义）"


def keys_shown_on(d):
    # 编码行显示双拼按键 on, back on the test page in 双拼
    gear(d, "双拼")
    d.switch("编码行显示双拼按键", True)
    d.open_page("text")
    d.switch_im("双拼")


@case("SP-60", base="B0-18键", mutates=True)
def sp60(d):
    # off first: the pinyin, as before
    grouped_keys(d, "bn io h jk")
    off = d.preedit()
    d.clear()
    keys_shown_on(d)
    grouped_keys(d, "bn io h jk")
    row, c = d.preedit(), d.cands()
    d.shot("kb", "18 键自然码 BN IO H JK，开了编码行显示双拼按键：编码行 ni hk")
    d.enter()
    entered = d.field_text()
    d.clear()
    grouped_keys(d, "bn io h jk")
    d.pick("你")
    rest = d.preedit()
    d.clear()
    # zhong taken shows as vs: a tap between v and s goes before it, so a key there leaves zhong whole
    grouped_keys(d, "v sd fg io")
    d.syllable_tap("zhong")
    taken = d.preedit()
    spans, y = d.preedit_glyphs()
    d.tap(((spans[0][1] + spans[1][0]) // 2, y), 0.6)
    grouped_keys(d, "bn")
    tapped = d.preedit()
    d.shot("kb", "点定 zhong 后点 v、s 中间再按 BN：编码行末尾仍是 vs'go")
    d.clear()
    gear(d, "双拼")
    choose(d, "键盘", "全键盘")
    d.open_page("text")
    d.switch_im("双拼")
    d.type("nihk")
    full = d.preedit()
    d.clear()
    d.type("vsg")
    abbrev = d.preedit()
    d.clear()
    check(off == "ni hao", f"关着时编码行是「{off}」")
    check(row == "ni hk", f"18 键 BN IO H JK 的编码行是「{row}」")
    check("你好" in c[:5], f"BN IO H JK 的候选 {c[:8]}")
    check(entered == "nihk", f"回车后框里是「{entered}」")
    check(rest == "你hk", f"点「你」后编码行是「{rest}」")
    check(taken == "vs'go", f"点定 zhong 后编码行是「{taken}」")
    check(tapped.endswith("vs'go"), f"点 v、s 中间再按 BN，编码行是「{tapped}」")
    check(full == "ni hk", f"全键盘 nihk 的编码行是「{full}」")
    check(abbrev == "vs g", f"全键盘 vsg 的编码行是「{abbrev}」")
    return f"关「{off}」；18 键「{row}」，回车「{entered}」，点你「{rest}」，点定 zhong「{taken}」→「{tapped}」；全键盘「{full}」「{abbrev}」"


@case("SP-61", base="B0-18键", mutates=True)
def sp61(d):
    gear(d, "双拼")
    d.switch("编码行显示双拼按键", True)
    script("18-lifecycle").in_field(d)
    d.switch_im("双拼")
    grouped_keys(d, "bn io h jk")
    d.cands()
    shown = d.field_text()
    d.shot("full", "编码行在输入框里，18 键 BN IO H JK：框里 ni hk")
    d.pick("你好")
    t = d.field_text()
    d.clear()
    check(shown == "ni hk", f"框里是「{shown}」")
    check(t == "你好", f"点「你好」后框里是「{t}」")
    return f"框里「{shown}」，点「你好」后「{t}」"
