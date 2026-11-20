"""uat/08-english.md (B0-英文). The .md is the source of truth; each function follows its case's
前提/操作/预期."""

from uat import block_candidate, case, check, choose, gear, glide_on, keys_at


def en_settings(d):
    gear(d, "英文")


def sym_comma(d):
    """the , of the bottom row (English)"""
    d.press(",")


@case("EN-02", base="B0-英文", mutates="learn")
def en02(d):
    d.type("teh")
    c = d.cands()
    d.space()
    t = d.field_text()
    check(c[:2] == ["the", "teh"], f"候选 {c[:4]}")
    check(t == "the ", f"框里「{t}」")
    return f"候选 {c[:3]}，框里「{t}」"


@case("EN-19", base="B0-设置", mutates=True)
def en19(d):
    glide_on(d, "英文", "nosuggest")
    d.glide("helo")
    t1 = d.field_text()
    en_settings(d)
    d.switch("单词提示", False)
    d.open_page("text")
    d.switch_im("英文")
    d.glide("helo")
    t2 = d.field_text()
    d.clear()
    d.type("abc")
    d.glide("helo")
    t3 = d.field_text()
    d.bksp()
    t4 = d.field_text()
    d.glide("helo")
    sym_comma(d)
    s = d.screen()
    t5, c5 = d.field_text(s), d.candidates(s)
    check(t1 == "hello ", f"不要提示框里划出「{t1}」")
    check(t2 == "hello ", f"关掉单词提示后划出「{t2}」")
    check(t3 == "abc hello ", f"打 abc 再划「{t3}」")
    check(t4 == "abc", f"退格后「{t4}」")
    check(t5 == "abc hello, " and not c5, f"再划接逗号「{t5}」，候选 {c5[:3]}")
    return f"「{t1}」「{t2}」「{t3}」「{t4}」「{t5}」"


@case("EN-27", base="B0-英文", mutates=True)
def en27(d):
    d.type("th")
    c = d.cands()
    check("the" in c, f"th 的候选 {c[:6]}")
    block_candidate(d, "the", "长按英文「the」的菜单")
    d.clear()
    d.type("th")
    again = d.cands()
    d.clear()
    check("the" not in again, f"屏蔽后 th 还有 the：{again[:6]}")
    d.type("thw")
    d.cands()
    d.space()
    t = d.field_text()
    check(t != "the ", f"thw 空格后框里「{t}」")
    return f"屏蔽前 {c[:4]}，屏蔽后 {again[:4]}，thw 空格「{t}」（设置里恢复由 JVM 管）"


@case("EN-30", base="B0-英文", mutates="learn")
def en30(d):
    # a capitalised word is corrected only for a key beside: b tapped toward v reads Steve, in the middle as typed
    def typed(at):
        keys_at(d, "hi", [None, None])
        d.space()
        d.tap(d.keys(refresh=True)["shift"], 0.6)
        keys_at(d, "stebe", at)
        d.space()
        return d.field_text() or ""
    edge = typed([None, None, None, (0.05, 0.5), None])
    d.shot("kb", "b 点在靠 v 的边上打 hi Stebe、空格：上屏「hi Steve 」")
    d.clear()
    mid = typed([None] * 5)
    d.clear()
    check(edge.endswith(" Steve "), f"b 靠 v 点的 Stebe 空格后框里「{edge}」，该改成 Steve")
    check(mid.endswith(" Stebe "), f"正中点的 Stebe 空格后框里「{mid}」，该照打的留着")
    return f"靠边「{edge}」；正中「{mid}」"


@case("EN-31", base="B0-英文", mutates=True)
def en31(d):
    d.hide_keyboard()
    gear(d, "英文")
    choose(d, "键盘", "九键")
    d.open_page("text")
    d.switch_im("英文")
    d.clear()
    # when (9436) with 4 slipped onto 5's edge by 4: when; in the middle each key's first letter
    keys_at(d, "9536", [None, (0.03, 0.5), None, None])
    # English's nine keys compose in the field (no code row)
    edge = d.field_text()
    c = d.cands()
    d.shot("kb", "9、5 贴着靠 4 的左边、3、6：框里正在打的是 when，首选 when")
    d.clear()
    keys_at(d, "9536", [None] * 4)
    mid = d.field_text()
    d.clear()
    check(edge == "when" and "when" in c[:3], f"5 贴左边点的 9536：框里「{edge}」，候选 {c[:4]}")
    check(mid == "wjdm", f"四个键都点在正中的 9536：框里「{mid}」")
    return f"贴边「{edge}」{c[:3]}；正中「{mid}」"
