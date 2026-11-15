# 18 转屏、切应用、换输入框

区域代号 `LC`。这些是平时最容易出怪事的时刻：键盘状态要么保持，要么干净地重来，不能半截。

### LC-01 [P0] 打拼音时转屏
- **前提**：B0，点 n i h a o（还没上屏）。
- **操作**：把设备转成横屏；如果键盘收起了，点「普通文本」框；点 h a o 空格。
- **预期**：转屏前框里一直是空的（拼音在候选栏上方）；转屏后框里还是空的，不留一串字母。Chrome 转屏时会自己收起键盘（Gboard 也一样），点框后弹出的是横屏分体键盘；最后框里是「好」。
- **JVM**：`StuckBarUatTest.theScreenTurnedMidPinyinLeavesNothing`（转屏后拼音栏、候选栏都空，引擎重置过，再打照常出候选）；设备上照跑。

### LC-02 [P1] 转屏后选区还跟着
- **前提**：B0，点「长文本」框，在文本编辑面板里用 **选择** 选中几个字。
- **操作**：转成横屏；点 **复制**；转回竖屏，在「普通文本」框粘贴。
- **预期**：转屏后选区还在；粘贴出来的是选中的那几个字。

### LC-03 [P0] 打拼音时切到别的应用再回来
- **前提**：B0，点 n i（还没上屏）。
- **操作**：按系统的"最近任务"切到别的应用，再切回 Chrome，点「普通文本」框；点 h a o 空格。
- **预期**：框里没有留下「ni」；键盘上没有残留的拼音和候选；最后框里是「好」。
- **JVM**：`StuckBarUatTest.pinyinLeftForAnotherAppAndBackLeavesNothing`（收起键盘、离开输入框再回来，拼音栏、候选栏都空，再打照常出候选）；设备上照跑。

### LC-04 [P0] 打拼音时点别的输入框
- **前提**：B0，点 n i（还没上屏）。
- **操作**：点「句子」框。
- **预期**：「普通文本」框里是空的，没有留下「ni」；「句子」框里也没有字，候选栏上方没有残留的拼音，能正常打字。
- **JVM**：`HostCasesUatTest.anotherFieldTappedWhilePinyinIsTypedLeavesNothing`（换框后原框空、拼音行空，新框照常打字）；设备上不跑。

### LC-05 [P1] 换到数字框再回来，键盘回到字母
- **前提**：B0。
- **操作**：点「数字」框（出数字键盘）；再点「普通文本」框。
- **预期**：「普通文本」框里是字母键盘，不是数字键盘。
- **JVM**：`LifecycleUatTest.aNumberFieldThenTextIsLettersAgain`（数字框后回普通文本框是字母键盘）；设备上不跑。

### LC-06 [P1] 在符号面板时换输入框
- **前提**：B0，点 **符** 打开符号面板（面板左下的锁没锁上，这是默认）。
- **操作**：点「句子」框。
- **预期**：键盘回到字母键盘。
- **JVM**：`LifecycleUatTest.anotherFieldFromTheSymbolsIsLetters`（符号面板没锁时换框回到字母）；设备上不跑。

### LC-07 [P1] 收起再弹出，保持当前输入法
- **前提**：B0，点地球键切到英文。
- **操作**：点 ⌄ 收起键盘；再点「普通文本」框。
- **预期**：还是英文。
- **JVM**：`LifecycleUatTest.hiddenAndShownAgainIsStillTheInputMethod`（切到英文、⌄ 收起再点框弹出：没有切输入法，仍是英文字母键盘）；设备上不跑。

### LC-08 [P1] 锁屏解锁
- **前提**：B0，点 n i（还没上屏）。
- **操作**：按电源键锁屏，再解锁回到 Chrome，点「普通文本」框。
- **预期**：键盘正常，能接着打字。

### LC-09 [P1] 系统深色模式来回切换时键盘在显示
- **前提**：B0，**跟随系统深色模式** 开着（默认）。
- **操作**：键盘显示时，从通知栏快捷开关（或系统设置）打开深色模式，看键盘，打 n i h a o 空格；再关掉深色模式，看键盘，打 n i h a o 空格。
- **预期**：深色模式时键盘换成"深色模式主题"，浅色时换回"浅色模式主题"；每次切换键盘都不会消失不回来，都能正常打出「你好」。
- **JVM**：`LifecycleUatTest.systemDarkModeFlippedWhileShown`（系统深浅色切换时键盘换成对应主题，照样能打字）；设备上不跑。

### LC-10 [P1] 重启引擎时键盘在显示
- **前提**：B0-设置，**⋮ → 开发者**。
- **操作**：点 **重启 Fcitx 实例**；马上回测试页点「普通文本」框，打 n i h a o 空格。
- **预期**：键盘弹出；引擎准备好后打出「你好」（准备期间按的键不会造成崩溃）。

### LC-11 [P0] 应用被系统杀掉后
- **前提**：B0。
- **操作**：以 root 杀掉输入法进程（debug 包的进程名是 `io.github.sraw.youmo.debug`，发布包是 `io.github.sraw.youmo`；root 过的手机用 `adb shell su 0 sh -c 'kill -9 $(pidof io.github.sraw.youmo.debug)'`（Magisk 的 su 也接受 `su -c '…'`），模拟器上 `adb root` 后直接 `adb shell` 执行；手机没有 root 就记为 **阻塞**；系统回收内存时就是这样杀的。不要用 `am force-stop`：AOSP 在当前输入法被强行停止时会改用另一个已启用的输入法）；点「普通文本」框；打 n i h a o 空格。
- **预期**：键盘重新弹出，能打出「你好」；设置和学到的词都还在。

### LC-13 [P0] 键盘上改的设置马上保存，立刻杀掉也不丢
- **前提**：B0。
- **操作**：展开工具栏点 **⋯**，点 **简体** 切到繁體，再点 **中文标点** 切到英文标点；回到键盘后马上（一秒内）以 root 杀掉输入法进程（同 LC-11 的做法）；点「普通文本」框，打 g u o j i a 空格，再打 `,`；看 **⋯**。
- **预期**：键盘重新弹出；框里是「國家」和英文逗号 `,`；**⋯** 里还是繁體、英文标点。

### LC-14 [P1] 打拼音时改「键盘」设置
- **前提**：B0，点 n i（还没上屏）。
- **操作**：在不收起键盘的情况下把拼音的「键盘」从全键盘改成 14 键（设备上走不到：去设置要离开输入框，离开时拼音已经清掉，所以只在 JVM 上直接改设置）；再改回全键盘，点 h a o。
- **预期**：改设置时还没选的拼音直接清掉、不上屏：框里是空的，拼音栏和候选栏都空着；之后打 h a o，拼音栏是「hao」，候选跟着出来，没有「ni」的残留。
- **JVM**：`StuckBarUatTest.aKeyboardSettingChangedMidPinyinDropsIt`；设备上不跑。

### LC-15 [P1] 打拼音时换主题
- **前提**：B0，点 n i（还没上屏）。
- **操作**：键盘还在时换主题（系统深色模式跟着切也一样会重建键盘）；点 h a o。
- **预期**：同 LC-14：拼音清掉、不上屏，新主题的键盘上拼音栏和候选栏都空着；之后打的照常出候选。
- **JVM**：`StuckBarUatTest.aThemeChangedMidPinyinDropsIt`；设备上不跑（当初在 redroid 上看到：切深色模式时测试宿主自己会重建，输入框重新开始，走的是换框的路）。

### LC-16 [P1] 系统重建键盘视图时拼音还在
- **前提**：B0，点 n i（还没上屏）。
- **操作**：系统让输入法重建视图（`onCreateInputView`，没有改任何设置）。
- **预期**：新键盘上拼音栏还是「ni」，候选栏照样有候选，不是只剩拼音栏、候选栏空着。
- **JVM**：`StuckBarUatTest.aViewMadeAnewMidPinyinShowsTheEnginesState`；设备上不跑（没有办法从外面触发）。

### LC-17 [P1] 英文打到一半时换主题
- **前提**：B0，切到英文，点 h e（「he」带下划线在框里，还没上屏）。
- **操作**：键盘还在时换主题（JVM 上直接改，同 LC-15）；再点 x。
- **预期**：「he」照原样上屏留在框里（不删、不改正），下划线没了；之后的 x 是新的一个词，框里是「hex」，下划线只在 x 下面。
- **JVM**：`StuckBarUatTest.aThemeChangedMidEnglishWordCommitsIt`；设备上不跑（同 LC-15）。

### LC-18 [P1] 打拼音时在悬浮和停靠之间切换
- **前提**：B0，点 n i（还没上屏）。
- **操作**：拖着把手把键盘拖成悬浮，再拖回底部停靠。
- **预期**：两次键盘重建后拼音栏都还是「ni」，候选栏照样有「你好」，框里是空的；不算改设置，拼音没有被清掉。
- **JVM**：`StuckBarUatTest.floatingAndDockedMidPinyinKeepIt`（直接改悬浮开关，拖把手改的就是它）；设备上不跑。


### LC-20 [P2] 键盘一直没启动完时重启实例或换界面语言：提示可能没重启成
<!-- R25r2 F7883 -->
- **前提**：B0-设置，键盘的实例一直停在启动中（设备上很难造出：要它启动时卡住 10 秒以上，比如很慢的机器刚开机时；造不出记为阻塞）。
- **操作**：**⋮ → 开发者**，点 **重启 Fcitx 实例**，确定，等提示；再在 **界面语言** 里换一种语言，等提示。
- **预期**：两处都提示「键盘还在启动，可能没有重启成。请稍后再试」，不说「完成」（说「可能」：等不到启动完时没有重启，或者已经重启了、新的实例还在启动，两种都是这一句）；换语言时界面照常换成新的语言（输入法和设置页的名字等键盘下次启动才换）；稍后键盘启动完，再点 **重启 Fcitx 实例** 照常提示完成（LC-10）。
- **JVM**：`HostRestartTest.aHostStuckStartingIsWaitedForNoLongerThanTheWaitAndSaidNotUp`（`HostDaemon` 等不到启动完时不重启、返回 false）；`DeveloperUatTest.aRestartGivenUpOnAHostStillStartingSaysItMayNotHaveRestarted`、`AppLanguageUatTest.aLanguageChangedWhileTheHostStaysStartingSaysItMayNotHaveRestarted`（两处在它返回 false 时的那一句，测试接缝 `HostDaemon.testRestarted`）；`DeveloperUatTest.restartedTheInstanceSaysDone`（启动完了照常提示完成）。设备上造不出一直停在启动中，不跑。

## 编码行在输入框里（B-IF）

总规矩：输入法证明不了是自己写的字一个都不删；证明不了就留在框里成为普通文字。

### LC-21 [P0] 输入框里打拼音：选字、收键盘都不留编码
- **前提**：B-IF。
- **操作**：在「普通文本」框点 n i h a o，看框和键盘上方；点空格；再点 n i，收起键盘。
- **预期**：打字时框里是带下划线的「ni hao」，键盘上方没有拼音条；空格后框里是「你好」；收起键盘后框里还是「你好」，没有留下「ni」。
- **JVM**：`CodeInFieldUatTest.theCodeIsTheFieldsComposingTextTillAWordIsChosenAndGoesAsTheKeyboardDoes`；设备上照跑。

### LC-22 [P0] 选中文字后打拼音，选中的文字不丢
- **前提**：B-IF，输入测试台 `ime` 页的 `preselected` 框（「前面选中的后面」，预先选中「选中」）。
- **操作**：点 n i，看框和键盘上方；退格上滑清除；再点 n i，点框里别的位置；收起键盘。
- **预期**：打字时框里的字和选区都没变，拼音在键盘上方的小条里；清除后选区还在；点别处后、收起键盘后框里还是「前面选中的后面」。
- **JVM**：`CodeInFieldUatTest.aSelectionAsTheCodeStartsKeepsTheCodeOnTheStripAndTheSelectionWhole`；设备上照跑。

### LC-23 [P1] 输入框里打拼音时点另一个框（已知会留下）
- **前提**：B-IF，测试页打开全部的框（`fields.html`），在「普通文本」框点 n i。
- **操作**：点「句子」框，点 h a o 空格。
- **预期**：「句子」框里是「好」；「普通文本」框里可能留下「ni」成为普通文字（应用在输入法知道之前就把它定成了文字，输入法不删），框里别的字一个不少。
- **JVM**：`CodeInFieldUatTest.aConnectionTheAppClosesFirstIsSentNothingAndTheNextFieldTakesItsOwnCode`（应用先关掉连接、输入法才收到结束输入：旧框留下「ni」，什么都不发给它；新框的编码照常）。

### LC-24 [P1] 输入框里打拼音时转屏
- **前提**：B-IF，在「普通文本」框点 n i h a o。
- **操作**：转成横屏，键盘收起了就点框；点 h a o 空格；停约 1 秒再转回竖屏。
- **预期**：框里最后是「好」或者「ni hao好」（输入法自己重启输入时证明后拿走；应用重建了框就留下），不会少字、不会多出别的字母；转屏后打字正常。转屏时网页丢了焦点的那几次（键盘几乎盖满网页，API 36 模拟器上 5 次里约 3 次）：空格后框里没有「好」，键盘上方那一行写着「没写进去：好，点一下输入框」；点一下框、再点这一行，「好」进框，这一行消失（LC-36）。
- **JVM**：`CodeInFieldUatTest.theScreenTurnedTakesTheCodeAwayProvenOurs`（输入法自己的重启：拿走）；`CodeInFieldUatTest.aScreenTheAppMakesAnewHasTheCodeLeftAsTextInTheNewField`（应用重建界面，先关旧连接，新框带着旧文字开始输入：「ni」留下，之后的拼音接着进新框）；`CommitKeptUatTest.theCodeInTheFieldWhenTheFieldDropsItsWritesAfterATurnIsKeptAndWrittenByATapOnItsLine`（转屏后框收了写入却不照办：编码改到小条，上屏的字留在键盘上，点那一行写进去）。

### LC-25 [P1] 输入框里打拼音时切走再回来、切换输入法
- **前提**：B-IF，在「普通文本」框点 n i。
- **操作**：按"最近任务"切到设置再切回来，点框；点 n i，再用输入法选择器换到别的输入法再换回来。
- **预期**：框里没有多出别的字；拼音如果留下，是「ni」原样成为普通文字；回来后打字正常。

### LC-26 [P1] 应用自己重启输入的框：留下这一次的编码，之后在小条里
- **前提**：B-IF，输入测试台 `behavior` 页的 `settext_echo` 框。
- **操作**：点 n，看框；再点 n i h a o，点空格。
- **预期**：第一个 n 留在框里成为普通文字（应用重启了输入，输入法不删；重启照旧清掉引擎里这一次的编码，键盘上没有它）；再打的拼音在键盘上方的小条里，框里不再出现拼音；空格后框里是「n你好」，别的字一个不少。
- **JVM**：`CodeInFieldUatTest.anAppsOwnRestartLeavesTheCodeAsTextAndFloatsItFromThen`；设备上照跑。

### LC-27 [P1] 会改写文字的框：改过的那段留下，之后在小条里
- **前提**：B-IF，输入测试台 `behavior` 页的 `upper` 框。
- **操作**：点 n i h a o，点空格。
- **预期**：框里先出现「N」（应用改成了大写，输入法不删），之后的拼音在键盘上方的小条里；空格后框里是「N你好」。
- **JVM**：`CodeInFieldUatTest.anAppThatRewritesTheCodeHasItLeftAndFloatsTheRest`；设备上照跑。

### LC-28 [P1] 读不到光标前文字的框：拼音一直在小条里
- **前提**：B-IF，输入测试台 `behavior` 页的 `null_before` 框。
- **操作**：点 n i h a o，看框；点空格。
- **预期**：框里始终没有拼音，拼音在键盘上方的小条里；空格后框里是「你好」。
- **JVM**：`CodeInFieldUatTest.aFieldThatWillNotBeReadNeverHasTheCode`；设备上照跑。

### LC-29 [P1] 限长的框：放得下的字母留下，之后在小条里
- **前提**：B-IF，输入测试台 `ime` 页的 `max10` 框（最多 10 个字）。
- **操作**：点 n i h a o z h o n g g u o，看框和键盘上方。
- **预期**：框里是应用收下的前 10 个字符「ni hao zho」（编码照显示的样子写进框，音节间有空格；应用截掉了后面的，输入法不删，成为普通文字）；拼音「ni hao zhong guo」整个在键盘上方的小条里，接着打完、选字和「浮在键盘上方」时一样。
- **JVM**：`CodeInFieldUatTest.aFieldThatCutsTheCodeShortKeepsWhatItTookAndFloatsTheRest`；设备上照跑。

### LC-30 [P1] 应用清掉 composing 的框：拼音拿走，在小条里
- **前提**：B-IF，输入测试台 `behavior` 页的 `finish_composing` 框，再换 `finish_composing_async` 框。
- **操作**：各点 n i h a o（快打），看框和键盘上方；点空格。
- **预期**：打字时框里最多闪一下第一个字母，拼音在键盘上方的小条里；空格后框里是「你好」。`finish_composing_async` 框里最多多留一个字母（如「nni」，之后拼音在小条里，空格后「nni你好」），框里原有的字一个不少。留下的是上一次写进去的整段编码的一份：这个框每次改动都清，所以是一个字母；停顿后才清的应用（防抖）会留下停顿前打的整段（如「ABnihniha」）。应用异步去掉 composing 时 `EditText` 不报告，输入法只在上一次写入还没报告时（Android 13 起）读编辑器的 composing 区来发现（API 36 模拟器实测）。
- **JVM**：`CodeInFieldUatTest.aFieldThatClearsTheComposingTextHasTheCodeTakenAwayAndFloated`（应用清除时有报告的三种：同步清、post 清、清得比下一键晚，都不留字母；清除不报告，像设备上的 `EditText`（harness 的 `spanAloneUnreported`）：留下「nni」，和设备一致；以及报告没赶上下一键：SDK 36 读 composing 区不留、SDK 29 留一个）；设备上照跑（API 36 模拟器）。

### LC-31 [P1] 终端（TYPE_NULL）：拼音一直在小条里
- **前提**：B-IF，输入测试台 `behavior` 页的 `terminal` 框。
- **操作**：点 n i h a o，看框和键盘上方；点空格。
- **预期**：框里始终没有拼音，拼音在键盘上方的小条里；空格后框里是「你好」。
- **JVM**：`CodeInFieldUatTest.aTerminalNeverHasTheCode`；设备上照跑。

### LC-32 [P1] 不设 id 的两个框之间换框
- **前提**：B-IF，输入测试台 `behavior` 页的 `no_id_a` 框。
- **操作**：点 n i；点 `no_id_b` 框，点 h a o 空格。
- **预期**：`no_id_b` 里是「好」；`no_id_a` 里没有多出别的字，拼音如果留下是「ni」原样成为普通文字（同 LC-23）。
- **JVM**：`CodeInFieldUatTest.anotherFieldTappedLeavesTheFirstWholeAndTheNextTakesItsOwnCode`（输入法先知道换了框：拿走）；设备上照跑。

### LC-33 [P2] 网页里会改写文字的框
- **前提**：B-IF，输入测试台 `webview` 页的 `w_rewrite` 框（JS 在 input 事件里转大写）。
- **操作**：点 n i h a o，点空格。
- **预期**：同 LC-27：被改成大写的那段留下，之后拼音在键盘上方的小条里；最后框里是留下的大写字母加「你好」，别的字一个不少。

### LC-34 [P1] 发送后 setText("") 的聊天框：发出去后拼音照样进框
- **前提**：B-IF，输入测试台 `behavior` 页的 `chat_settext` 框（发送后 `setText("")`，框架自己让输入法重来：Android 10 直接重启，30 起延后，34 起 `invalidateInput`）。
- **操作**：点 n i h a o、空格，点回车（发送）；再点 n i，看框和键盘上方；点空格。
- **预期**：发送前框里是「你好」，发送后框是空的；再打的「ni」带着下划线在框里，键盘上方没有小条；空格后框里是「你」，没有上一次的字母留下。
- **JVM**：没有。这条看的是各版本框架自己让输入法重来的方式，harness 只有一种 `restart()`；发送时框里已经没有编码，编码在框里时应用重启输入见 LC-26。设备上跑（API 36 模拟器；Android 10 用 youmo29）。

### LC-35 [P1] 手写写了一半时转屏、换主题：写好的上屏，手写还开着
- **前提**：B0-手写，写「中」，停笔，不选（「中」在编码处）。
- **操作**：转屏（或换一个主题）；再写「人」。
- **预期**：「中」上屏留在框里（键盘重建时写好的上屏，DECISIONS A2）；回来后手写还开着，写的「人」是新的一个字。
- **JVM**：`HostCasesUatTest.aCharacterNotCommittedIsPutInWhenTheKeyboardGoes`（键盘走时写好的上屏）、`KeyHandwritingUatTest.theKeyboardHiddenAndShownAgainStillWrites`（键盘重建后还写得上）；设备上不跑。

### LC-36 [P1] 输入框收了字却没写进去：字留在键盘上，点一下写进去
- **前提**：B0-文本框；一个收下输入法的写入却不照办的框（网页的框在它的 WebView 丢了焦点、键盘还开着时就是这样：转屏时键盘几乎盖满网页，见 LC-24；设备上要靠转屏碰，造不出记为阻塞）。编码行三种样式都一样。
- **操作**：点 n i 空格，再点 h a o 空格，看键盘上方；点一下框，再点键盘上方那一行。
- **预期**：框里没有字时，候选上方的编码行写着「没写进去：你好，点一下输入框」（两次上屏的字按顺序都留着；很长的只显示末尾 24 个字，前面是「…」），读屏念出这一句；框恢复后点这一行，「你好」写在光标处，只写一次，这一行消失；框还没恢复时点它，字照旧留着；刚又上屏了别的字、还在等回报时点它，等那次判完再写，按打字的顺序。留着的字在这几种情况下不再留：接着打别的字并且写进去了、换到另一个输入框、键盘收起。转屏、应用在同一个框里重新开始输入（点了网页的框）时还留着。照常收字的框，不管光标回报得多晚，都不出现这一行（上屏后马上删掉、发送清空、应用清空并重新开始输入也不出现）；密码框、应用要求不记录的框、没有类型的框（终端）不检查，也不显示。实体键盘打的字（空格选词）也一样留着。
- **JVM**：`CommitKeptUatTest.withTheCodeOnTheStripOrInTheBarWhatADeafFieldDroppedIsKeptTheSame`（浮动、嵌在键盘里两种编码行：两个词都留着，点那一行写进去）；`CommitKeptUatTest.aFieldThatTakesTheTextIsNeverSaidToHaveDroppedItHoweverLateItReports`（马上回报、回报晚于等待、编码在框里且回报晚：都不出现）；`CommitKeptUatTest.whatIsKeptGoesWithTheKeyboardAnotherFieldOrSomethingElseTypedAndStaysThroughATurnAndARestart`（各种放掉和留着的情况、密码框）；`CommitKeptUatTest.aCommitMadeJustBeforeTheWaitForAnEarlierOneIsUpWaitsItsOwnTime`（前一次的定时器不判后一次，换了框也一样）；`CommitKeptUatTest.aCommitFollowedByADeletionASendOrInputStartedAnewIsNotSaidToBeLostInAFieldThatReportsLate`（回报晚的框里上屏后删掉、发送、清空重开：不出现）；`CommitKeptUatTest.theKeptLineTappedWhileAnotherCommitWaitsWritesBothInTheOrderTyped`（等待中点那一行：按顺序写进去）；`CommitKeptUatTest.theKeptLineTappedWhileACommitWaitsThatThenReportsIsStillWritten`（等待中点了，那次上屏照常回报：留着的照样写进去）；`CommitKeptUatTest.theKeptLineAskedForIsWrittenWhenTheWaitEndsHoweverItEnds`（读回来写进去了、按了退格、post 前又上屏了一次、输入已结束：都写，按打字顺序，不写进已结束的连接）；`CommitKeptUatTest.theKeptLineGoesBeforeAuxBelowWhichKeepsItsFormatting`；`CommitReadAfterKeyTest.theUpOfTheSpaceThatChoseAWordHandedBackBeforeTheFieldReportsLeavesTheCommitJudged`（实体键盘：选词空格的抬起在回报前交回，照样判、照样留）；ime-core 的 `CommitCheckTest`（判定本身：改写文字的框、读不到的框、一排同样的字、打到一半的英文词）。设备上的那一种见 LC-24。
