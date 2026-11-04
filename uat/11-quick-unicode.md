# 11 快速输入、Unicode 输入

区域代号 `QU`。

### QU-01 [P0] 长按"符"进入快速输入
- **前提**：B0。
- **操作**：长按 **符**。
- **预期**：候选栏上显示"快速输入："，等着打关键词。
- **JVM**：`BarUatTest.holdingTheSymbolsKeyOpensQuickInput`（真宿主：长按「符」出快速输入的提示）和 `HostReplayUatTest.theQuickInputPromptIsInTheDevicesLanguage`（栏上是「快速输入: 」）；设备上留作冒烟（真引擎的快速输入模式）。

### QU-02 [P1] 情绪词出颜文字
- **前提**：B0，长按 **符** 进入快速输入。
- **操作**：点 y a w n，点空格。
- **预期**：打字时候选里是颜文字（例如 (￣□￣)）；空格后框里是第一个颜文字。
- **JVM**：`HostCasesUatTest.aMoodWordGivesKaomoji`（yawn 第一个是颜文字，空格上屏它）；设备上不跑。

### QU-03 [P1] :表情名: 出 emoji
- **前提**：B0，进入快速输入。
- **操作**：打 `:smile:`（冒号从长按 x 键或符号面板以外的键角打出：x 的键角是 `:`，向下滑 x），点空格。
- **预期**：框里是「😄」。
- **JVM**：`HostCasesUatTest.anEmojiByItsName`（:smile: 空格得「😄」）；设备上不跑。

### QU-04 [P1] \LaTeX 名出符号
- **前提**：B0，进入快速输入。
- **操作**：打 `\alpha`（`\` 是 m 的键角，打中文时显示为「、」，在快速输入里打出的是 `\`），看候选。
- **预期**：候选里有「α」。
- **JVM**：`HostCasesUatTest.aLatexNameGivesItsSymbol`（\alpha 候选有「α」）；设备上不跑。

### QU-05 [P1] 退格删到头退出快速输入
- **前提**：B0，进入快速输入，打了 y a。
- **操作**：点 ⌫ 三次。
- **预期**：删光后退出快速输入，栏上回到工具栏；接着打 n i 是普通拼音。
- **JVM**：`HostCasesUatTest.backspacingQuickInputEmptyLeavesIt`（退格三下后栏上没有快速输入提示，ni 是普通拼音）；设备上不跑。

### QU-06 [P1] 长按 123 进入 Unicode 输入，按名字找字符
- **前提**：B0。
- **操作**：长按 **123**；点 d e g r e e。
- **预期**：栏上显示"Unicode："；候选里有「°」。点它，框里是「°」。
- **JVM**：`HostCasesUatTest.unicodeByName`（栏上「Unicode」，degree 候选有「°」，点它上屏）；设备上不跑。

### QU-07 [P1] 十六进制编码
- **前提**：B0，长按 **123** 进入 Unicode 输入。
- **操作**：打 `U+4F60`（U 用 ⇧ 加 u，+ 是 d 的键角），看候选。
- **预期**：候选里有「你」。
- **JVM**：`HostCasesUatTest.unicodeByHexCode`（U+4F60 候选有「你」）；设备上不跑。

### QU-08 [P1] 快速输入不给英文单词
- **前提**：B0，长按 **符** 进入快速输入。
- **操作**：点 h e l l，看候选。
- **预期**：栏上显示"快速输入"；候选里没有 hello、hell 这类英文单词（只有颜文字、emoji 或没有候选）。`:smile:` 和 `\alpha` 照旧见 QU-03、QU-04。
- **JVM**：`PinyinUatTest.quickInputOffersNoEnglishWords`（栏上是快速输入，hell 没有英文单词）；设备上不跑。

### QU-09 [P1] 14 键上的 Unicode 编码
- **前提**：B0-14键，长按 **123** 进入 Unicode 输入。
- **操作**：打 `4E2D`（14 键没有 ⇧，角标里也没有 +；Unicode 输入不带 `U+` 也认得出编码）：向下划 UI 出 4，点 ER 的左半出 e，向下划 ER 出 2，点 DF 的左半出 d；看候选，点「中」。
- **预期**：候选里有「中」，点了框里是「中」；编码里和框里都没有私用区的字（键盘内部的码）。
- **JVM**：`Keys14UatTest.unicodeOnTheFourteenKeysTakesTheCodeFromCornersAndHalves`（4E2D 出中、上屏，编码里没有私用区字符）；设备上不跑。

### QU-10 [P1] 快速输入列出自己的条目
- **前提**：设置 → 词库与短语 → 快速输入里存了 `gh` → 一个网址、`addr` → 两行地址。
- **操作**：长按「符」，什么都不打；点 addr；再长按「符」。
- **预期**：栏上依次是两条的开头（多行的带 ⏎），最后是「＋添加」，没有高亮；点了整段两行原样上屏；再进时 addr 排第一。
- **JVM**：`QuickPhraseMineTest.nothingTypedListsMineThenAddWithNothingLit`、`QuickPhraseBookTest.mineInOrderOfUseThenFileOrder`。

### QU-11 [P1] 九键 / 14 键按键找关键词
- **前提**：同 QU-10，B0-九键。
- **操作**：长按「符」，打 4、4。
- **预期**：编码显示 44，候选里先是 gh 的网址，再是内置的 ghost；空格上屏网址。改了九键字母后按新字母找。
- **JVM**：`QuickPhraseMineTest.theNineKeysFindAKeyByItsLettersShowingDigits`、`the14KeysFindByTheWholeKeyAndBackspaceKeepsStep`。

### QU-12 [P2] 九键、手写长按「符」进快速输入
- **前提**：同 QU-10；九键用 B0-九键，手写在 B0 上换到手写键盘，注音九键用 B0-注音九键，大千用 B0-注音全键盘，注音两种先在 **按键 → 按键字符和快捷键** 里给一个键设上「快速输入」快捷键。
- **操作**：九键、手写上长按「符」；注音九键、大千上从快捷键（或勾上的工具栏「快速输入」按钮）进快速输入。
- **预期**：进快速输入；手写、注音九键、大千时换成全键盘，退出后回到原来的键盘；快速输入时换了输入法，和平时换输入法一样，退出后是新输入法的键盘。
- **JVM**：`LayoutSwitchKeyTest.theNineKeysAndHandwritingsSymbolsKeyHoldsQuickInputTheirNumbersKeyOnlyTaps`；换键盘 `QuickPhraseKeyboardUatTest.handwritingLendsItsPlaceToTheFullKeyboardWhileQuickInputLasts`，快速输入时按语言键换了输入法不回手写板 `anotherInputMethodChosenDuringQuickInputKeepsItsOwnLetters`，和不在快速输入时换的一样 `anInputMethodChosenDuringQuickInputShowsWhatItShowsOutOfIt`；注音两种键盘 `theZhuyinKeyboardsLendTheirPlaceAsThePadDoes`。

### QU-13 [P1] 密码框里不记顺序
- **前提**：同 QU-10，测试页的「密码」框。
- **操作**：密码框里长按「符」，点第二条。
- **预期**：上屏；下次进来顺序没变。
- **JVM**：`QuickPhraseMineTest.inAPasswordFieldMineCommitsButItsUseIsNotKept`。

### QU-14 [P1] 借手机时不列自己的条目
- **前提**：开「借手机」。
- **操作**：长按「符」，再打 gh。
- **预期**：什么都不打时栏空；打 gh 只有内置的 ghost。
- **JVM**：`QuickPhraseMineTest.habitsOffShowNoneOfMine`。

### QU-15 [P2] 设置页加、改、删、导入导出
- **前提**：同 QU-10，**设置 → 词库与短语 → 快速输入**。
- **操作**：快速输入页里加一条、改它、点开它按「删除」再撤销；再加一条 `zz`，让页面上有三条，点开中间那条按「删除」，再撤销；导出再导入；再导入测试页 `fixtures/fields.html`（不是 `.mb`，一条也读不出；先 `adb push uat/fixtures/fields.html /sdcard/Download/` 放进手机「下载」），再导入一个自己写的 `.mb`，里面两行关键词无效：一行关键词是 31 个字母（如 `abcdefghijklmnopqrstuvwxyzabcde 内容`），一行关键词中间是全角空格（如 `a　b 内容`，a 和 b 之间是全角空格 U+3000）。（关键词里的普通空格做不出无效行：第一个普通空格就是关键词和内容的分界，`a b 内容` 读成关键词 `a`、内容 `b 内容`。）
- **预期**：每一步键盘上马上看得到；重复的拒绝（关键词只差大小写也算）；撤销的那条回到原来的位置（中间那条撤销后仍在中间，长按「符」看栏上也在原来的位置，不跑到末尾）；导入报加了几条、跳过几条重复的（和几条超过 5000 字的）；导入 `fields.html` 立刻提示「这个文件里没有快速输入：每行是关键词、空格、内容」，列表不变；那两行关键词无效的跳过，提示里另说「另跳过 2 条关键词无效（空的、有空格或太长）」；自带表的开关关了不弹回，能再打开。
- **JVM**：`QuickPhraseManagerTest`（六项，撤销放回原位并带回使用时间见 `aDeletionUndoneIsPutBackWhereItWasWithItsTimeOfUse`）；`QuickPhraseSettingsUatTest.anEntryAddedChangedFoundAndDeleted`、`aBuiltInTableTurnedOffStaysOffAndTurnsOnAgain`、`theImportToastCountsEveryLineSkipped`；文件正忙时开关连拨两次不先显示第一次 `aSwitchTurnedTwiceWhileTheFilesAreBusyNeverShowsTheFirstTurn`。

### QU-16 [P0] 条目跟着备份走
- **前提**：同 QU-10，另在快速输入页关掉一张自带表；长按「符」点一次 addr，让它排到 gh 前面（快速输入页不能拖动排序，顺序就是用的先后）。
- **操作**：备份全部，清数据后从备份恢复。
- **预期**：条目、顺序、关掉的内置表都回来。
- **JVM**：`UserDataManagerTest.quickInputsEntriesOrderAndSwitchesRoundTripThroughABackup`。

### QU-20 [P0] 恢复 0.5.1 以前的备份：先警告，自己加的条目没了，关掉的自带表重新打开
- **前提**：要一份 v0.5.0 正式版做的 **备份全部**（装 v0.5.0、点 **备份全部**，再覆盖安装要测的这一版）；同 QU-10 加好 `gh`，在快速输入页关掉一张自带表。
- **操作**：**备份与恢复 → 从备份恢复**，选那份 0.5.0 的备份，读确认框，确认；等应用重启；打开 **设置 → 词库与短语 → 快速输入**；长按「符」。
- **预期**：确认框里写着"0.5.1 以前的备份没有快速输入，自己加的条目会没了，关掉的自带表会重新打开"；重启后快速输入页和键盘上都没有 `gh`，关掉的那张自带表又开着；其余照 PR-10。这一条在发版时手动跑（模拟器脚本没有 0.5.0 的包，做不出这份备份）。

### QU-17 [P2] 候选长按的卡片
- **前提**：同 QU-10，另加一条 `zz`，三条按用的先后是 gh、addr、zz（依次长按「符」点 zz、addr、gh）。
- **操作**：快速输入里长按自己的一条，依次试「复制」「上屏」「编辑」「删除」，删了再点「撤销」；长按一条自带的。再长按「符」，长按中间的 addr 点「删除」再点「撤销」，看栏，再到快速输入页看。最后在快速输入页再加几条，让什么都不打时栏上的条目够两页、右边那页至少两条；长按「符」，翻到右边那页，长按这一页上的一条点「删除」。再到快速输入页删几条，让右边那页只剩一条；长按「符」，翻到右边那页，长按那唯一的一条点「删除」。
- **预期**：自己的有上屏、复制、编辑、删除，复制的是整段；删了同一菜单里出「已删除」「撤销」，候选栏马上少了这条，撤销后回来；自带的只有上屏、复制。addr 撤销后回到原来的位置：栏上仍是 gh、addr、zz，快速输入页也是这个顺序（不跑到末尾）。在右边那页（还剩别的条目）删了一条后栏仍停在那一页，不跳回第一页；删了右边那页唯一的一条后栏退到前一页（现在的最后一页），亮着这一页的最后一条。
- **JVM**：`QuickPhraseKeyboardUatTest.aCandidatesCardCommitsCopiesEditsDeletesAndUndoes`；列表马上刷新 `QuickPhraseMineTest.refreshedAfterAReloadTheListIsTheNewBooks`，刷新后停在原来那页 `QuickPhraseTest.aListMadeAgainKeepsItsPage`，末页删空退到前一页、亮它的最后一条 `QuickPhraseTest.aListMadeAgainShorterKeepsTheLastPageAndLitIndexItStillHas`；撤销放回原位并带回使用时间 `QuickPhraseManagerTest.aDeletionUndoneIsPutBackWhereItWasWithItsTimeOfUse`。

### QU-18 [P2] 剪贴板「存为快速输入」
- **前提**：同 QU-10；剪贴板里有一条普通文字、一条超过 5000 字的（在测试页「普通文本」框里粘贴或打出超过 5000 字的一段，全选后复制）、一条标为敏感的（放法同 PR-08 的前提）。
- **操作**：剪贴板里长按一条普通的，选「存为快速输入」；再长按一条超过 5000 字的、一条密码。
- **预期**：普通的打开快速输入页的编辑框，内容已填好；超长的提示存不成、不打开页面；密码那条没有这一项。
- **JVM**：`QuickPhraseKeyboardUatTest.theClipboardSavesAnEntryAsQuickInputButNotAPasswordNorOneTooLong`。

### QU-19 [P2] 编辑框不丢字、不截字
- **前提**：同 QU-10，快速输入页点 **添加** 打开编辑框。
- **操作**：编辑框里打几个字后转屏（或切深色）；再按返回；粘一段超过 5000 字的内容点确定。
- **预期**：转屏后编辑框还在、字还在；按返回先问要不要放弃；超长的提示「内容最多 5000 字」，不截掉、不保存。
- **JVM**：`QuickPhraseSettingsUatTest.theEditorAndWhatWasTypedOutliveTheActivityMadeAnew`、`aContentTooLongIsToldAtOkAndNeverCut`、`theKeyboardAskingAgainOverAChangedEditorAsksFirst`。

### QU-21 [P2] 编辑框改了没存时，键盘又要打开快速输入页：先问
- **前提**：同 QU-10，快速输入页点 **添加**，在编辑框里打几个字，不点确定，回到测试页（编辑框留在后台）；剪贴板里有一条普通文字。
- **操作**：剪贴板里长按那条，选「存为快速输入」；在问话里选继续编辑；再回测试页做一次，这次选「放弃」。
- **预期**：两次都先问「放弃这次的修改？」；选继续编辑时编辑框还是原来打的字；选放弃时打开的编辑框内容是剪贴板那条。
- **JVM**：`QuickPhraseSettingsUatTest.theKeyboardAskingAgainOverAChangedEditorAsksFirst`；编辑框还没恢复出来时键盘就来问，等它显示出来再问 `theKeyboardAskingBeforeARestoredEditorIsShownAsksOverItOnceItIs`；应用进程被杀后恢复的编辑框见 QU-24。

### QU-24 [P2] 编辑框改了没存、应用进程被杀后，键盘又要打开快速输入页：也先问
- **前提**：同 QU-21 的前提（编辑框里打了几个字没点确定，回到测试页）；剪贴板里有一条普通文字。
- **操作**：先在系统里切到别的输入法（设置页和键盘在同一个进程，当前输入法的进程 `am kill` 杀不掉），再 `adb shell am kill <包名>` 杀掉在后台的应用进程（或打开开发者选项「不保留活动」代替这一步）；切回本输入法；剪贴板里长按那条，选「存为快速输入」；在问话里选继续编辑。
- **预期**：恢复出来的编辑框照样先问「放弃这次的修改？」，不直接换成剪贴板那条；选继续编辑后编辑框里还是原来打的字。
- **JVM**：没有（进程被杀后系统恢复界面要真设备）；模拟器或真机上手工跑。

### QU-22 [P2] 实体键盘：快速输入什么都不打时空格上屏亮着的条目，没亮就打空格
- **前提**：同 QU-10，B0-实体。
- **操作**：按 **Super + `** 进快速输入，什么都不打，按 Tab 让第一条亮起，记下它是哪条，按空格；再按 **Super + `**，直接按空格。设备上不跑 Super + `：Android 不把 Win / ⌘ 组合键交给输入法（HK-37）；设备上让软键盘显示着，长按「符」进快速输入，其余照上面用实体键盘按；第二次先点小窗上的键盘图标让屏幕键盘回来（HK-03），再长按「符」。
- **预期**：Tab 后栏上第一条亮起（跑过 QU-10 等用例后第一条不一定是 gh，按用的先后排）；第一次框里多了亮着的那条的内容；第二次退出快速输入，框里多一个空格。
- **JVM**：`QuickPhraseTest.spaceWithNothingTypedCommitsTheEntryLitElseASpace`。

### QU-23 [P2] 繁体输出时自己加的条目原样上屏
- **前提**：同 QU-10，另加一条 `fp` → 「发票」；状态面板切到 **繁体**。
- **操作**：长按「符」，打 f p，看候选，点它。
- **预期**：候选显示「发票」（不是「發票」），上屏的也是「发票」；自己的条目不学、不联想。内置表里出中文的条目照常转成繁体（现在的内置表没有这样的条目，只在 JVM 上查）。
- **JVM**：`HostCoreEnginesTest.quick input's own entry goes out as it is under traditional, a built-in one converted, neither learnt nor predicted from`。
