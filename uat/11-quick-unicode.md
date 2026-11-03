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
- **操作**：九键、手写上长按「符」。
- **预期**：进快速输入；手写时换成全键盘，退出后回到手写板。
- **JVM**：`LayoutSwitchKeyTest.theNineKeysAndHandwritingsSymbolsKeyHoldsQuickInputTheirNumbersKeyOnlyTaps`；换键盘在设备上看。

### QU-13 [P1] 密码框里不记顺序
- **操作**：密码框里长按「符」，点第二条。
- **预期**：上屏；下次进来顺序没变。
- **JVM**：`QuickPhraseMineTest.inAPasswordFieldMineCommitsButItsUseIsNotKept`。

### QU-14 [P1] 借手机时不列自己的条目
- **前提**：开「借手机」。
- **操作**：长按「符」，再打 gh。
- **预期**：什么都不打时栏空；打 gh 只有内置的 ghost。
- **JVM**：`QuickPhraseMineTest.habitsOffShowNoneOfMine`。

### QU-15 [P2] 设置页加、改、删、导入导出
- **操作**：快速输入页里加一条、改它、左滑删再撤销；导出再导入。
- **预期**：每一步键盘上马上看得到；重复的拒绝；导入报加了几条、跳过几条重复的。
- **JVM**：`QuickPhraseManagerTest`（四项）。

### QU-16 [P0] 条目跟着备份走
- **操作**：备份全部，清数据后从备份恢复。
- **预期**：条目、顺序、关掉的内置表都回来。
- **JVM**：`UserDataManagerTest.quickInputsEntriesOrderAndSwitchesRoundTripThroughABackup`。
