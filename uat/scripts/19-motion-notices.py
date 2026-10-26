"""uat/19-motion-notices.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
from uat import Blocked, case, check, script

case("MN-01", needs="jvm:BarUatTest.noticeAboveKeysTapAndTimeout")(lambda d: None)


case("MN-02", needs="jvm:MotionNoticeUatTest.habitsOffAndOnEachSaidTheNewInTheOldsPlace")(lambda d: None)


case("MN-03", needs="jvm:MotionNoticeUatTest.floatingAtTheLeftTheNoticeGoesRight")(lambda d: None)


case("MN-04", needs="jvm:MotionNoticeUatTest.clipboardDeleteUndoneOnTheNoticeInEitherTheme")(lambda d: None)


case("MN-10", needs="jvm:MotionNoticeUatTest.theDockedHandleUnderOrOverTheKeysTheKeysAsTall")(lambda d: None)


case("MN-11", needs="jvm:MotionNoticeUatTest.aLongPressOnTheHandleLetsItFloatUnderTheFinger")(lambda d: None)


case("MN-12", needs="jvm:MotionNoticeUatTest.oneHandedTheHandleAsWideAsTheKeyboard")(lambda d: None)


case("MN-13", base="B0-设置", needs="manual:要 TalkBack（redroid 没有），在开得了 TalkBack 的真机或模拟器上手动做")(lambda d: None)


case("MN-20", needs="jvm:BarUatTest.candidateTextSizeChangesBarNotKeys")(lambda d: None)


@case("MN-22", base="B0-设置", mutates=True, visual=True)
def mn22(d):
    st = script("14-settings")

    def preview(label):
        s = d.screen()
        pv = st.preview_box(d, s)
        d.shot((pv[0], 0, pv[2], pv[3] + 10), label)
        return pv

    def size(right):
        # DialogSeekBarPreference: a SeekBar in a dialog, 确定 saves; tapped at its very end
        d.tap_text("候选字号", after=1.0)
        bar = next((n for n in d.screen().nodes if n.cls == "SeekBar"), None)
        if not bar:
            raise Blocked("候选字号的对话框里没有滑块")
        x = bar.b[2] - 4 if right else bar.b[0] + 4
        d.tap((x, (bar.b[1] + bar.b[3]) // 2), 0.6)
        d.tap_text("确定", scroll=False, after=1.5)

    st.appearance_tab(d, "大小与样式")
    p0 = preview("默认字号的预览")
    size(True)
    p1 = preview("28 sp 的预览")
    size(False)
    p2 = preview("14 sp 的预览")
    # the keys' top is under the preview's candidate bar: it moves with the bar's height (F4275)
    check(p1[1] > p0[1] + 4, f"28 sp 时预览候选栏没变高：按键顶 {p0[1]} → {p1[1]}")
    check(p2[1] < p0[1] - 2, f"14 sp 时预览候选栏没变矮：按键顶 {p0[1]} → {p2[1]}")
    h = [p[3] - p[1] for p in (p0, p1, p2)]
    check(max(h) - min(h) <= 2, f"预览按键行高度变了：{h}")
    return f"按键顶 {p0[1]} → 28 sp {p1[1]} → 14 sp {p2[1]}；按键高 {h}"


case("MN-23", needs="jvm:BarUatTest.candidateSpacingFollowsTextSize")(lambda d: None)


case("MN-21", needs="jvm:MotionNoticeUatTest.landscapeLargestCandidatesStillCappedAtSevenTenths")(lambda d: None)
