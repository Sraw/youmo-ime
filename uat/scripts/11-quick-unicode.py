"""uat/11-quick-unicode.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import os
import tempfile
import time

from uat import PKG, Blocked, case, check, BAR_BOTTOM


def bar_label(d, s=None):
    s = s or d.screen()
    return [n.text for n in s.ime() if n.text and n.c[1] < BAR_BOTTOM]


def quick(d):
    k = d.key_node("符")
    d.hold(k.c, 900)
    time.sleep(0.6)


@case("QU-01")
def qu01(d):
    quick(d)
    lab = bar_label(d)
    check(any("快速输入" in x for x in lab), f"栏上 {lab}")
    return f"栏上 {lab[:3]}"


case("QU-02", needs="jvm:HostCasesUatTest.aMoodWordGivesKaomoji")(lambda d: None)


case("QU-03", needs="jvm:HostCasesUatTest.anEmojiByItsName")(lambda d: None)


case("QU-04", needs="jvm:HostCasesUatTest.aLatexNameGivesItsSymbol")(lambda d: None)


case("QU-05", needs="jvm:HostCasesUatTest.backspacingQuickInputEmptyLeavesIt")(lambda d: None)


case("QU-06", needs="jvm:HostCasesUatTest.unicodeByName")(lambda d: None)


case("QU-07", needs="jvm:HostCasesUatTest.unicodeByHexCode")(lambda d: None)


case("QU-08", needs="jvm:PinyinUatTest.quickInputOffersNoEnglishWords")(lambda d: None)


case("QU-09", needs="jvm:Keys14UatTest.unicodeOnTheFourteenKeysTakesTheCodeFromCornersAndHalves")(lambda d: None)


case("QU-10", needs="jvm:QuickPhraseMineTest.nothingTypedListsMineThenAddWithNothingLit、QuickPhraseBookTest.mineInOrderOfUseThenFileOrder")(lambda d: None)


case("QU-11", needs="jvm:QuickPhraseMineTest.theNineKeysFindAKeyByItsLettersShowingDigits、QuickPhraseMineTest.the14KeysFindByTheWholeKeyAndBackspaceKeepsStep")(lambda d: None)


case("QU-12", needs="jvm:LayoutSwitchKeyTest.theNineKeysAndHandwritingsSymbolsKeyHoldsQuickInputTheirNumbersKeyOnlyTaps")(lambda d: None)


case("QU-13", needs="jvm:QuickPhraseMineTest.inAPasswordFieldMineCommitsButItsUseIsNotKept")(lambda d: None)


case("QU-14", needs="jvm:QuickPhraseMineTest.habitsOffShowNoneOfMine")(lambda d: None)


case("QU-15", needs="jvm:QuickPhraseManagerTest、QuickPhraseSettingsUatTest.anEntryAddedChangedFoundAndDeleted、QuickPhraseSettingsUatTest.aBuiltInTableTurnedOffStaysOffAndTurnsOnAgain、QuickPhraseSettingsUatTest.theImportToastCountsEveryLineSkipped、QuickPhraseSettingsUatTest.aKeyIsAsciiANoteAndAFolderAreSavedAndFolderChipsSortThePage、QuickPhraseSettingsUatTest.aSwitchTurnedTwiceWhileTheFilesAreBusyNeverShowsTheFirstTurn")(lambda d: None)


case("QU-16", needs="jvm:UserDataManagerTest.quickInputsEntriesOrderAndSwitchesRoundTripThroughABackup")(lambda d: None)


case("QU-17", needs="jvm:QuickPhraseKeyboardUatTest.aCandidatesCardCommitsCopiesEditsDeletesAndUndoes、QuickPhraseMineTest.refreshedAfterAReloadTheListIsTheNewBooks、QuickPhraseTest.aListMadeAgainKeepsItsPage、QuickPhraseTest.aListMadeAgainShorterKeepsTheLastPageAndLitIndexItStillHas、QuickPhraseManagerTest.aDeletionUndoneIsPutBackWhereItWasWithItsTimeOfUse")(lambda d: None)


case("QU-18", needs="jvm:QuickPhraseKeyboardUatTest.theClipboardSavesAnEntryAsQuickInputButNotAPasswordNorOneTooLong")(lambda d: None)


case("QU-19", needs="jvm:QuickPhraseSettingsUatTest.theEditorAndWhatWasTypedOutliveTheActivityMadeAnew、QuickPhraseSettingsUatTest.aContentTooLongIsToldAtOkAndNeverCut、QuickPhraseSettingsUatTest.theKeyboardAskingAgainOverAChangedEditorAsksFirst")(lambda d: None)


case("QU-20", needs="release:要一份 v0.5.0 正式版做的「备份全部」")(lambda d: None)


case("QU-21", needs="jvm:QuickPhraseSettingsUatTest.theKeyboardAskingAgainOverAChangedEditorAsksFirst、QuickPhraseSettingsUatTest.theKeyboardAskingBeforeARestoredEditorIsShownAsksOverItOnceItIs")(lambda d: None)


case("QU-24", needs="manual:要切到别的输入法后在后台杀掉应用进程再让系统恢复编辑框")(lambda d: None)
case("QU-22", needs="jvm:QuickPhraseTest.spaceWithNothingTypedCommitsTheEntryLitElseASpace")(lambda d: None)


case("QU-23", needs="jvm:HostCoreEnginesTest.quick input's own entry goes out as it is under traditional, a built-in one converted, neither learnt nor predicted from")(lambda d: None)


# fcitx's data is in the app's own files: root copies the file in, the app's owner and label given
QUICK_DIR = f"/data/data/{PKG}/files/host-data/data/quickphrase.d"


def put_mine(d, text):
    """[text] as the user's mine.mb, the app stopped first (the keyboard reads the file at its start)"""
    d.sh(f"am force-stop {PKG}")
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "mine.mb")
        with open(src, "w", encoding="utf-8") as f:
            f.write(text)
        d.adb("push", src, "/data/local/tmp/mine.mb", check=True)
    owner = f"$(stat -c %u:%g /data/data/{PKG})"
    out = d.root(f"mkdir -p {QUICK_DIR} && cp /data/local/tmp/mine.mb {QUICK_DIR}/mine.mb && "
                 f"chown -R {owner} /data/data/{PKG}/files/host-data && restorecon -R /data/data/{PKG}/files/host-data "
                 f"&& echo copied; rm -f /data/local/tmp/mine.mb")
    if "copied" not in out:
        raise Blocked(f"mine.mb 没有复制进去：{out.strip()[:200]}")
    d.open_page("text")


@case("QU-25", mutates=True, visual=True)
def qu25(d):
    # a file of before (version 1): the notes typed into the keys, converted as it is read
    put_mine(d, "#wz百度 www.baidu.com\n#wz新浪 www.sina.com.cn\n")
    quick(d)
    # a note makes a candidate wider: the bar holds one of the two, the list opened holds both
    s = d.screen()
    more = s.find(desc="展开候选词列表", where="ime")
    if more:
        d.tap(more.c, 0.8)
        s = d.screen()
    lab = [n.text for n in s.ime() if n.text]
    d.shot("kb", "快速输入什么都不打，候选展开：两条，旁边是注释")
    check(any("www.baidu.com" in x for x in lab) and any("www.sina.com.cn" in x for x in lab), f"候选 {lab}")
    check(any("百度" in x for x in lab) and any("新浪" in x for x in lab), f"注释没在候选旁边：{lab}")
    check(not any("#wz" in x for x in lab), f"关键词没拆开：{lab}")
    sina = next(n for n in s.ime() if n.text and "www.sina.com.cn" in n.text)
    d.tap(sina.c, 0.6)
    got = d.field_text()
    check(got == "www.sina.com.cn", f"框里是 {got!r}")
    return f"候选 {lab[:4]}，点新浪上屏 {got}"


case("QU-26", needs="jvm:QuickPhraseManagerTest.foldersAreMadeRenamedAndDeletedWithNoEntryLostAndAMoveKeepsItsTime、QuickPhraseSettingsUatTest.aKeyIsAsciiANoteAndAFolderAreSavedAndFolderChipsSortThePage、QuickPhraseFilesTest.version2RoundTripsNotesFoldersEmptyFoldersAndKeylessOnes")(lambda d: None)


case("QU-27", needs="jvm:QuickPhraseFilesTest.aFileOfBeforeHasItsKeysSplitFromTheFirstCharacterNotAsciiOnAndNoneLost、QuickPhraseManagerTest.filesOfBeforeAreReadConvertedAndWrittenAnewAtTheNextChange、QuickPhraseManagerTest.anImportOfBeforeOrAComputersIsConvertedIntoItsFoldersKeylessOnesCounted、QuickPhraseSettingsUatTest.aFileOfBeforeIsConvertedAndItsKeylessOnesAreListedToBeGivenAKey、UserDataManagerTest.quickInputOfABackupOfBeforeIsReadConvertedAndOneOfNowKeepsItsFolders")(lambda d: None)
