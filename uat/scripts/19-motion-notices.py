"""uat/19-motion-notices.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
from uat import case

case("MN-01", needs="jvm:BarUatTest.noticeAboveKeysTapAndTimeout")(lambda d: None)


case("MN-02", needs="jvm:MotionNoticeUatTest.habitsOffAndOnEachSaidTheNewInTheOldsPlace")(lambda d: None)


case("MN-03", needs="jvm:MotionNoticeUatTest.floatingAtTheLeftTheNoticeGoesRight")(lambda d: None)


case("MN-04", needs="jvm:MotionNoticeUatTest.clipboardDeleteUndoneOnTheNoticeInEitherTheme")(lambda d: None)


case("MN-10", needs="jvm:MotionNoticeUatTest.theDockedHandleUnderOrOverTheKeysTheKeysAsTall")(lambda d: None)


case("MN-11", needs="jvm:MotionNoticeUatTest.aLongPressOnTheHandleLetsItFloatUnderTheFinger")(lambda d: None)


case("MN-12", needs="jvm:MotionNoticeUatTest.oneHandedTheHandleAsWideAsTheKeyboard")(lambda d: None)


case("MN-13", base="B0-设置", needs="manual:要 TalkBack 读屏，在模拟器（google_apis 镜像带 TalkBack）或真机上手动做")(lambda d: None)


case("MN-20", needs="jvm:BarUatTest.candidateTextSizeChangesBarNotKeys")(lambda d: None)


case("MN-22", needs="jvm:SettingsPagesUatTest.thePreviewsBarFollowsTheCandidateSizeOnThePage")(lambda d: None)


case("MN-23", needs="jvm:BarUatTest.candidateSpacingFollowsTextSize")(lambda d: None)


case("MN-21", needs="jvm:MotionNoticeUatTest.landscapeLargestCandidatesStillCappedAtSevenTenths")(lambda d: None)
