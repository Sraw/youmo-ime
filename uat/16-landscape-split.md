# 16 横屏与分体键盘

区域代号 `LS`。设备横过来宽度至少 600 dp（多数手机横屏是 700–930 dp；可以用 `adb shell wm size` 和 `wm density` 算：宽 px ÷ (dpi / 160)）。

## 分体

### LS-01 [P0] 横屏自动分体
- **前提**：B0-横屏。
- **操作**：看键盘。
- **预期**：字母键分成左右两半，每半每行 5 个键：左 `q w e r t` / `a s d f g` / `⇧ z x c v`，右 `y u i o p` / `g h j k l` / `v b n m ⌫`；中间留空；G、V 两边各一个；底排的空格横跨中间。两半的键一样宽，各自从屏幕边缘排到中间的空白。
- **JVM**：`LandscapeUatTest.landscapeSplitsTheLetters`（851×393 dp：两边各 5 键、G V 两边都有、键一样宽、空格跨中间；图）；设备上不跑，横屏打字看 LS-03。

### LS-03 [P0] 分体时左右两半都能打字
- **前提**：B0-横屏。
- **操作**：用左半边点 w，右半边点 o，右半边点 m，左半边点 e，右半边点 n，点空格。
- **预期**：框里是「我们」（或 `women` 的首选）。
- **JVM**：`LandscapeUatTest.bothHalvesType`（w、e 在左半，o、m、n 在右半，依次送 w o m e n 和空格）；设备上留作冒烟（真引擎把 women 出成「我们」，两半的触摸走真窗口）。

### LS-04 [P1] 两边的 G、V 一样
- **前提**：B0-横屏，切到英文。
- **操作**：点左半边的 g；点右半边的 g；点左半边的 v；点右半边的 v。
- **预期**：候选最左是「ggvv」（或改正后的词，右边紧挨着原样 ggvv）：两边的 g、v 打出的字母相同。
- **JVM**：`LandscapeUatTest.bothGsAndVsTypeTheSame`（英文、两边的 g 发出同一个键、两边的 v 也是；候选由引擎出）；设备上不跑。

### LS-05 [P1] 分体时滑动和长按照常
- **前提**：B0-横屏。
- **操作**：在右半边的 o 上向上滑；长按左半边的 q 选 `Q`。
- **预期**：框里是「9Q」。
- **JVM**：`LandscapeUatTest.swipeAndLongPressOnTheHalves`（右半边 o 上滑发出 9、左半边长按 q 选 Q 发出 Q；上屏由引擎做）；设备上不跑。

### LS-09 [P1] 改设置马上生效
- **前提**：B0-横屏，键盘是分体的。
- **操作**：展开工具栏点 **…** → **主题**（进到外观），**大小与样式 → 横屏键盘样式** 选"普通"，返回测试页点「普通文本」框。
- **预期**：键盘不再分体，不用重启应用。
- **JVM**：`LandscapeUatTest.splitOffTakesAtOnce`（横屏键盘样式改"普通"后回到框里不再分体，G 只有一个）；设备上不跑。

### LS-10 [P1] 设置页的预览跟着分体
- **前提**：B0-设置，设备横屏。
- **操作**：打开 **外观**，看上面的键盘预览；再进 **大小与样式** 看预览。
- **预期**：预览是分体的，样子和测试页里横屏的键盘一样（键的宽窄比例一致，不是挤在一起的细长键）；把"横屏键盘样式"改成"普通"后预览马上变成不分体。
- **JVM**：`LandscapeUatTest.thePreviewSplitsToo`（横屏外观页的预览分体（两个 g）、改"普通"马上不分体；图）；设备上不跑。

### LS-13 [P1] 网址框的分体键盘底排
- **前提**：B0-横屏，点「网址」框。
- **操作**：看底排，点 **.com**。
- **预期**：底排有 `/`、`.`、`.com`，空格横跨中间；框里是「.com」。
- **JVM**：`LandscapeUatTest.addressFieldSplitBottomRow`（网址框分体底排有 / . .com、空格跨中间，点 .com 上屏）；设备上不跑。

## 分体时其他面板靠边

### LS-14 [P1] 数字键盘靠右，带工具栏
- **前提**：B0-横屏（分体时其他面板为默认"靠右"）。
- **操作**：点 **123**。
- **预期**：数字键盘缩成单手宽（大约竖屏手机的宽度），靠屏幕右边；它上面的工具栏也跟着缩到同样宽度、同样靠右；左边是空白，空白中间有一个指向左的箭头。
- **JVM**：`LandscapeUatTest.numbersAtTheRightWithTheirBar`（数字键盘单手宽靠右、工具栏同宽靠右、左边空白里有"移到左边"；图）；设备上不跑。

### LS-15 [P1] 点箭头换到另一边
- **前提**：B0-横屏，点了 **123**，数字键盘靠右。
- **操作**：点左边空白里的箭头；点 **返回** 回字母；转成竖屏再转回横屏；点 **123**；再到 **外观 → 大小与样式** 看 **分体时其他面板**。
- **预期**：数字键盘和工具栏移到左边，箭头出现在右边空白里并指向右；回字母、转屏以后再点 **123**，数字键盘还在左边；**分体时其他面板** 变成"靠左"。
- **JVM**：`LandscapeUatTest.theArrowMovesItAcrossAndItStays`（点箭头移到左边、箭头换到右边；回字母、重建键盘（转屏）后数字键盘仍在左；设置值变成"靠左"）；设备上不跑。

### LS-16 [P1] 回到字母键盘又铺满并分体
- **前提**：B0-横屏，点了 **123**。
- **操作**：点 **返回**。
- **预期**：回到分体的字母键盘，工具栏铺满整个宽度，没有箭头。
- **JVM**：`LandscapeUatTest.lettersAgainFullAndSplit`（回字母后分体、工具栏铺满、没有箭头）；设备上不跑。

### LS-17 [P1] 符号面板靠边
- **前提**：B0-横屏。
- **操作**：点 **符**；点一个符号。
- **预期**：符号面板靠右、单手宽，分类列在左边，符号排成几列能点到；点了符号后框里出现它，回到分体字母键盘。
- **JVM**：`LandscapeUatTest.symbolsAtTheSide`（符号面板靠右单手宽，点一个符号上屏并回到分体字母；图）；设备上不跑。

### LS-18 [P1] 表情面板靠边
- **前提**：B0-横屏。
- **操作**：点工具栏的笑脸。
- **预期**：表情面板靠右、单手宽，能上下滑、点表情能打出。
- **JVM**：`LandscapeUatTest.emojiAtTheSide`（表情面板靠右单手宽，点表情上屏）；设备上不跑。

### LS-19 [P1] 剪贴板面板靠边
- **前提**：B0-横屏，复制过一段文字。
- **操作**：展开工具栏，点剪贴板。
- **预期**：剪贴板面板靠右、单手宽，点条目能粘贴，粘贴后回到分体键盘。
- **JVM**：`LandscapeUatTest.clipboardAtTheSide`（剪贴板面板靠右单手宽，点条目粘贴后回到分体键盘）；设备上不跑。

### LS-20 [P1] 九键靠边
- **前提**：B0-横屏，在 **输入法 → 拼音** 里把 **键盘** 选成「九键」。
- **操作**：点 6 4 4 2 6，点空格。
- **预期**：九键靠右、单手宽，工具栏 / 候选栏也同宽靠右；框里是「你好」。
- **JVM**：`LandscapeUatTest.nineKeysAtTheSide`（九键靠右单手宽，点 6 4 4 2 6 发出 5 个键，候选栏同宽靠右；「你好」由引擎出（T9 的 A 类）；图）；设备上不跑。


### LS-22 [P1] 选"铺满"
- **前提**：B0-设置，**分体时其他面板** 选"铺满"；转横屏，回测试页点「普通文本」框。
- **操作**：点 **123**。
- **预期**：数字键盘铺满整个宽度，没有箭头。
- **JVM**：`LandscapeUatTest.fullWidthTakesTheWholeWidth`（"铺满"时数字键盘铺满、没有箭头）；设备上不跑。

### LS-23 [P1] 两种样式都是普通时"分体时其他面板"不可用
- **前提**：B0-设置，**竖屏键盘样式**、**横屏键盘样式** 都选"普通"。
- **操作**：看 **分体时其他面板**。
- **预期**：这一项是灰的，点不了。
- **JVM**：`LandscapeUatTest.dockGreyedWhenSplitIsOff`（两种样式都普通时"分体时其他面板"不可用）；设备上不跑。

### LS-24 [P1] 竖屏时面板照旧铺满
- **前提**：B0（竖屏，竖屏键盘样式为默认的普通）。
- **操作**：点 **123**；再点 **符**。
- **预期**：都铺满宽度，没有箭头。
- **JVM**：`LandscapeUatTest.portraitPanelsFull`（竖屏数字键盘、符号面板铺满、没有箭头）；设备上不跑。

### LS-24a [P1] 竖屏键盘样式选分体时面板铺满
- **前提**：B0-设置，**竖屏键盘样式** 选"分体"，**分体时其他面板** 为"靠右"；竖屏手机回测试页点「普通文本」框。
- **操作**：点 **123**；再点 **符**。
- **预期**：字母键盘分体，但数字键盘和符号面板都铺满宽度，没有箭头。
- **JVM**：`LandscapeUatTest.portraitAlwaysSplitPanelsFull`（竖屏键盘样式分体时字母分体，数字键盘、符号面板铺满、没有箭头）；设备上不跑。

### LS-25 [P1] 靠边时数字框直接出靠边的数字键盘
- **前提**：B0-横屏。
- **操作**：点「数字」框。
- **预期**：弹出的数字键盘靠右、单手宽，工具栏同宽。
- **JVM**：`LandscapeUatTest.numberFieldDocked`（横屏数字框直接出靠右的数字键盘、工具栏同宽）；设备上不跑。

## 横屏其他

### LS-27 [P1] 横屏高度
- **前提**：B0-设置，**键盘高度** 选"很高"。
- **操作**：转横屏，回测试页点「普通文本」框。
- **预期**：键盘上方给应用留出至少 120 dp（输入法窗口里栏的上边到窗口顶），按键被压矮，但每行不矮于 36 dp。（高度随设置变由 ST-10 管；底线的算法由 `KeyboardHeightCapTest` 钉住，各种机型的数见 LS-47–51。）
- **JVM**：`LandscapeUatTest.tallestLeavesTheApp120dp`（"很高"时栏上方至少 120 dp、键区至少 4 × 36 dp；图）；设备上不跑。

### LS-28 [P1] 横屏时打字照常出候选
- **前提**：B0-横屏。
- **操作**：点 n i h a o。
- **预期**：候选栏横跨整个宽度，第一个是「你好」。
- **JVM**：`LandscapeUatTest.candidatesAcrossTheWidth`（假候选横跨整个宽度、第一个是「你好」）；设备上不跑。

### LS-29 [P1] 分体时双拼没有 `;` 键，ing 用 L 的长按
- **前提**：B0-横屏，确认是分体键盘；输入法切到双拼，方案选"微软"。
- **操作**：看右半边第二行 L 的右边；点 x，长按 l，在弹出里选 `;`，看候选。
- **预期**：分体时没有单独的 `;` 键，L 和这一行其他键加宽补满，行尾没有空隙；长按 l 选 `;` 被当作韵母 ing，候选里有「性」「星」等读 xing 的字，不打出分号。
- **JVM**：`LandscapeUatTest.splitShuangpinHasNoSemicolonKey`（微软双拼分体时没有 ; 键、L 的右边和上一排对齐、长按 l 的弹出里有分号；分号当 ing 由引擎管（SP 的 A 类））；设备上不跑。

### LS-30 [P1] 14 键在横屏、分体、单手、悬浮下
- **前提**：B0-横屏。**输入法 → 拼音** 的「键盘」改成「14 键」，回测试页点「普通文本」框。
- **操作**：看键盘；点 BN UI 看编码行和音节条；转回竖屏，打开单手模式，点 BN UI；关掉单手，打开悬浮键盘，点 BN UI；最后在 **外观** 看主题页的键盘预览，量它的键区高度，和竖屏时停在底部的真键盘比。
- **预期**：横屏分体时 14 键整块靠一边，一个拇指宽（和九键一样），编码行和音节条跟着它；单手、悬浮下也一样，编码行和音节条随键盘一起放。主题页键盘预览的键区和真键盘的键区一样高（相差不超过 3dp；预览画的是全键盘，按 18dp 的编码行算键盘高度的上限，竖屏碰不到上限）。
- **JVM**：`LandscapeUatTest.theFourteenKeysAtTheSideWithTheirCodeRow`（横屏分体 14 键靠一边不到半宽，编码行和音节条在它上方，金图 keys14_side）、`LandscapeUatTest.theFourteenKeysOneHandedFloatingAndAsTheThemesPreviewHasThem`（单手、悬浮时编码行和音节条随键盘；主题页预览键区和真键盘差不超过 3dp）；设备上不跑。

### LS-31 [P1] 双拼 18 键在横屏下
- **前提**：B0-横屏。添加"双拼"，**输入法 → 双拼** 的「键盘」改成「18 键」，回测试页点「普通文本」框，换到双拼。
- **操作**：看键盘；点 BN IO 看编码行和音节条。
- **预期**：横屏分体时 18 键和 14 键一样整块靠一边，三行齐全，编码行和音节条跟着它；点 BN IO 后音节条里有 ni。
- **JVM**：`LandscapeUatTest.theEighteenKeysAtTheSideAsTheFourteen`（18 键靠一边、三行齐全，编码行和音节条跟着，音节条有 ni，金图 keys18_side）；设备上不跑。

### LS-32 [P1] 横屏：词卡左右两列，从词卡点开的字卡也是两列；悬浮键盘一列
- **前提**：B0-横屏，「普通文本」框。
- **操作**：打 yinhang，长按「银行」，点读音行，点「行」；换悬浮键盘再做一遍。
- **预期**：停靠时词卡左边是整词和字行，右边是上屏、复制；点开的字卡也是两列，「‹ 银行」在左列顶上。横屏展开后菜单项在卡下面，往下滑；菜单的滚动条一直显示（不淡出）。悬浮键盘上都是一列。
- **JVM**：app `CandidateMenuUatTest.inLandscapeTheWordCardIsTwoColumnsWithLinesUnderIt`（图 `word_card_land`）、`CandidateMenuUatTest.onTheFloatingKeyboardTheWordCardIsOneColumn`；ime-core `CardNavTest`、`CharCardLayoutTest`；设备上不跑。

## 宽屏算横屏（折叠屏、平板竖拿）

屏幕宽 600 dp 以上，横拿竖拿都算横屏。没有折叠屏时，用 `adb shell wm size 1767x2208` 加 `wm density 420`（673×841 dp，展开竖拿）、`wm size 1082x2340`（412 dp 宽，外屏）模拟，测完 `wm size reset; wm density reset`；有折叠屏模拟器（`7.6in Foldable`、`pixel_fold`）时用 `adb emu fold` / `adb emu unfold`。

### LS-33 [P1] 折叠屏展开竖拿按横屏排
- **前提**：B0，展开竖拿（673×841 dp）；**键盘高度** 跟随主题（内置主题是标准）。
- **操作**：回测试页点「普通文本」框，看键盘；转成横拿再看。
- **预期**：竖拿时键盘按横屏排：字母分体，间距是横屏的；高度和横拿一样（标准档键区 4 × 56 dp，键盘约 312 dp 含导航条），不是竖屏的 30%（约 252 dp）。
- **JVM**：`WideScreenUatTest.anUnfoldedFoldableUprightIsLaidOutTurned`（673×841 dp 竖拿：横屏、大屏、键区 4 × 56 dp、主题 `@landscape`、自动分体、设置页的一对屏两个都是横屏）、`WideScreenUatTest.theUnfoldedFoldableTurnedIsAsHigh`（841×673 dp 横拿：同样 4 × 56 dp）、`WideScreenUatTest.theExpandedCandidatesTakeTheTurnedColumnsUpright`（展开候选的列数取横屏那个设置）；设备上看一次（`wm size 1767x2208` + `wm density 420`）。

### LS-34 [P1] 折叠屏外屏、手机竖拿不变
- **前提**：B0，外屏（412 dp 宽）或普通手机竖拿。
- **操作**：回测试页点「普通文本」框，看键盘。
- **预期**：和 0.6.0 一样：竖屏的高度（标准档屏高 30%）、间距，字母不分体。
- **JVM**：`WideScreenUatTest.theOuterScreenIsAPhoneUprightAsBefore`（412×891 dp：不是横屏、屏高 30%、没有 `@landscape`、不分体）、`WideScreenUatTest.theOuterScreensExpandedCandidatesTakeTheUprightColumns`（展开候选的列数取竖屏那个设置）；设备上不跑。

### LS-35 [P1] 打开键盘时折叠、展开马上重排
- **前提**：折叠屏模拟器（或用 `wm size` 在 412 dp 宽和 673 dp 宽之间换），B0，外屏。
- **操作**：点「普通文本」框，打 nihao 不选；展开；再折叠。
- **预期**：展开后键盘马上按横屏排（分体、横屏的高度），折叠后回到竖屏的样子；打到一半的拼音照转屏的规则处理（清掉、不上屏），不卡、不留旧大小的键盘。
- **设备**：需要折叠屏模拟器或手工换 `wm size`，没写成脚本。

### LS-36 [P2] 平板竖拿算横屏，横屏键盘样式没设过时分体
- **前提**：B0，平板竖拿（或模拟器竖屏 600 dp 以上，比如 `wm size 1600x2560` 加 `wm density 320`，800×1280 dp）；**横屏键盘样式** 没设过（屏宽 ≥ 600 dp 时分体）。
- **操作**：点「普通文本」框，看键盘。
- **预期**：字母分体；高度和平板横拿一样（标准档键区 4 × 56 dp）。宽 599 dp 的竖屏不算横屏、不分体，600 dp 就算。
- **JVM**：`WideScreenUatTest.aTabletUprightIsTurnedAndSplits`（800×1280 dp：横屏、分体、键区 4 × 56 dp）、`WideScreenUatTest.justNarrowerIsUpright`（599 dp：竖屏、不分体，设置页的屏和键盘的一致）、`WideScreenUatTest.sixHundredWideIsTurned`（600 dp：横屏、分体）；设备上不跑。

### LS-37 [P2] 设置页按这块屏的样子画
- **前提**：折叠屏展开竖拿（673×841 dp）。
- **操作**：打开 **按键 → 按键字符和快捷键**，看键帽和「画不下」的灰字；打开 **外观**，把设置和另一个应用分屏，看主题预览。
- **预期**：键帽按横屏的键画（和展开时真键盘一样，上滑、下滑图标选「底部」的画在角上）；两种拿法画得不一样时灰字说「竖拿…，横拿…」。分屏时主题预览按整块屏的键盘画，不按设置页那半块窗口。
- **JVM**：`WideScreenUatTest.theSettingsPreviewInHalfASplitScreenIsTheDisplays`（展开竖拿、设置页窗口只有 336 dp 宽时，预览里的键按显示屏画：横屏、分体，候选栏是横屏的高度，宽是显示屏的）；屏的一对由 `KeyboardScreensTest.eachScreenIsLandscapeAsWideAsItIs` 钉住。设备上用 `wm size` 看一次。

### LS-38 [P2] 展开竖拿：键角和字卡和手机横拿一样
- **前提**：B0，展开竖拿；**上滑图标位置** 选「底部」。
- **操作**：看字母键；打 `unvzi`（拼音 u 拆字：女子），长按候选「好」看字卡。
- **预期**：上滑的小字画在角上（右上），不画在字母下面；字卡分两栏（读音、例词在左，部首、拆字、笔顺在右），读音显示 3 行后可以滚动。
- **JVM**：`WideScreenUatTest.theLowKeysMarksAndTheCardAsTurned`（673 dp 竖拿时场景是横屏，「底部」改到右上；字卡两栏、读音 3 行）；设备上不跑。

## 竖屏、横屏键盘样式

### LS-39 [P1] 默认：竖屏普通、横屏分体
- **前提**：B0（竖屏、横屏键盘样式都没设过），手机横屏宽 ≥ 600 dp。
- **操作**：竖拿点「普通文本」框看键盘；转到横屏再看；再转回竖屏。
- **预期**：竖拿是普通的整块键盘；横拿分体（G 两边都有）；转回竖屏又是整块。和 0.6.0 默认的样子一样。
- **JVM**：`KeyboardStyleUatTest.notSetTurnedSplits`（没设过、851 dp 宽横屏：分体）、`KeyboardStyleUatTest.notSetUprightIsWhole`（竖屏：整块铺满）；设备上不跑。

### LS-40 [P1] 横屏悬浮、竖屏停靠，各记各的
- **前提**：B0-设置，**竖屏键盘样式** 选"普通"，**横屏键盘样式** 选"悬浮"。
- **操作**：竖拿点「普通文本」框；转到横屏；再转回竖屏；重开手机后再横拿一次。
- **预期**：竖拿停在底部、铺满；横过来是悬浮键盘（用横屏那份大小、位置），不分体；竖回来又停在底部。重开后照旧。在竖屏把竖屏样式改成单手、悬浮，横屏那边的键盘不重建、照旧分体。
- **JVM**：`KeyboardStyleUatTest.turnedFloatingUprightDockedEachItsOwn`（横屏悬浮、不分体）、`KeyboardStyleUatTest.uprightDockedWhereTurnedFloats`（竖屏铺满）、`KeyboardStyleUatTest.theOtherShapesStyleMakesNothingAnew`（改另一个方向的样式，键盘不重建）；重开手机设备上不跑（样式存在设置里，和别的设置一样）。

### LS-41 [P1] 横屏单手：靠一边，换边，退出回到分体
- **前提**：B0-设置，**横屏键盘样式** 先选"分体"，再选"单手"。
- **操作**：横拿点「普通文本」框；点旁边空白里的箭头；再点斜箭头；转到竖屏看。
- **预期**：横拿时整个键盘约 420 dp 宽靠一边（第一次靠右），不分体；箭头换到另一边；斜箭头读「退出单手模式」，点了回到分体；竖屏照旧是普通的整块。
- **JVM**：`KeyboardStyleUatTest.turnedOneHandedAtTheSideRememberedAndBackToSplit`（横屏单手靠记住的左边、不分体，点「退出单手模式」重建后分体，横屏样式存成分体）；设备上不跑。

### LS-42 [P1] 键盘上切换只改当时那个方向
- **前提**：B0-横屏（横屏样式没设过、分体）。
- **操作**：在「普通文本」打 ni 不选，展开工具栏点 **⋯ → 悬浮键盘**；打开 **设置 → 外观 → 大小与样式** 看两项；回来把悬浮键盘拖回底部；再开悬浮，去系统设置强行停止输入法，回来点框，拖回底部。
- **预期**：变成悬浮时 ni 和候选还在；设置页「横屏键盘样式」是悬浮，「竖屏键盘样式」还是普通；拖回底部回到分体；强行停止后再拖回底部，也回到分体。
- **JVM**：`KeyboardStyleUatTest.floatingFromThePanelIsTheTurnedOnesAndDocksBackToSplit`（⋯ 里点悬浮只写横屏、竖屏没动，打的拼音不结束，拖回底部是分体）、`KeyboardStyleUatTest.afterATurnTheTurnedShapeIsTheOneWrittenAndMadeAnewOnce`（竖屏开、转到横屏后点悬浮写的是横屏，只重建一次）、`KeyboardStyleUatTest.turnedBeforeTheViewIsMadeAnewThePanelWritesTheBuiltShape`（屏已转、视图还没重建时 ⋯ 里点单手，写的是建视图时的竖屏）；强行停止设备上手工（底下那层和样式存在同一处，`KeyboardStyleMigrationAppTest`、`KeyboardStylesTest.theKeyboardsToggleWritesItsShapeOnlyBothKeys` 读写都断言了）。

### LS-43 [P2] 横屏分体时也能单手，打拼音时切换不丢
- **前提**：B0-横屏（分体）。
- **操作**：打 ni 不选；展开工具栏点 **⋯ → 单手模式**；再点一次。
- **预期**：变成单手（约 420 dp 靠一边，不分体），ni 和候选都还在；再点回到分体，ni 还在；竖屏样式不变。
- **JVM**：`KeyboardStyleUatTest.splitToOneHandedAndBackKeepsWhatIsTyped`（分体 → 单手 → 分体，各重建一次，没有 `focusOutIn`/`reset`，只写横屏）；设备上不跑。

### LS-44 [P2] 折叠屏、平板：内屏两个方向都用横屏键盘样式
- **前提**：折叠屏模拟器或 `wm size` 换成 673×841 dp；**竖屏键盘样式** 选"普通"，**横屏键盘样式** 选"悬浮"。
- **操作**：展开竖拿、展开横拿各点一次框；折叠（或换回 412 dp 宽）再点。
- **预期**：展开时两个方向都是悬浮；折起来（外屏）是普通。
- **设备**：需要折叠屏模拟器或手工换 `wm size`，没写成脚本；展开竖拿读横屏那项由 `KeyboardStylesTest.aWideScreenUprightReadsTheLandscapeOne` 钉住。

### LS-45 [P2] 悬浮键盘正滑回底部时转屏
- **前提**：B0，竖屏开着悬浮键盘（竖屏样式悬浮），横屏样式悬浮。
- **操作**：按住拖动条拖回底部，松手后立刻转屏；转回来。
- **预期**：横过来仍是悬浮（横屏那项不变）；竖回来是停在底部的普通（拖回底部改的是竖屏那项）。不崩、不留两块键盘。
- **JVM**：`KeyboardStyleUatTest.slidingDownAsTheScreenTurnsDocksTheShapeItWasMadeOn`（点放回底部、滑下时转屏：竖屏存成普通，横屏仍悬浮）、`KeyboardStylesTest.aViewMadeOnTheOtherShapeWritesThatOne`；设备上不跑。

### LS-46 [P2] 宽不到 600 dp 的横屏没设过时不分体
- **前提**：模拟器 `wm size 1280x720` 加 `wm density 360`（横屏约 569×320 dp），或 Android 14 以下带三个按钮侧边导航栏的手机横拿；横屏样式没设过。
- **操作**：横拿点框；打开 **设置 → 外观 → 大小与样式** 看 **横屏键盘样式**；选"分体"，回来再点框。
- **预期**：没设过时是普通的整块键盘；设置页显示"普通"；选了分体后分体。
- **JVM**：`KeyboardStylesTest.notSetTurnedUnder600IsNormal`（592 dp 宽横屏没设过：普通）、`KeyboardStylesTest.notSetTurnedFrom600Splits`（640 dp：分体，设置页照实际显示，什么都不存）；设备上不跑。

## 横屏高度（按每行高度，给应用留底线）

横屏键区是 4 行 ×「每行高度」：手机五档 34/41/48/54/59 dp，屏幕短边 600 dp 以上（平板、折叠屏内屏）48/52/56/60/64 dp；键盘上方至少给应用留 120 dp，键被压时每行最矮 36 dp（按布局实际行数），36 dp 优先。量法：`adb shell dumpsys window windows` 里输入法窗口和应用窗口的高，按键的高看截图（`uiautomator dump` 不列画布上的键）。

### LS-47 [P1] 常见手机横屏、标准档：三键导航和手势导航
- **前提**：常见手机（1080×2340@440，393×851 dp），B0；宿主是 webview_shell 打开 uat 的测试页 `fields.html`。
- **操作**：横屏，点 textarea；三键导航、手势导航各看一次；手势导航时打 `nihao` 不选。
- **预期**：三键导航时键盘约 232 dp、每行约 48 dp、应用窗口约 137 dp（网页 innerHeight 约 65）；手势导航时键盘约 249 dp、每行约 40 dp、应用窗口至少 120 dp（网页至少 48）。打拼音时浮在上方的编码行盖住应用最下面 18 dp，输入框所在那一行仍看得见。
- **JVM**：ime-core `KeyboardHeightCapTest.whereTheFloorBindsTheKeysGiveWay`（可用 321 dp → 键区 161）、app `ImeRoomViewTest.theAppKeeps120dpUnderEachBackground`、`ImeRoomViewTest.theBarsInsetsComingWorkTheKeysOutAgain`（导航条的高后到时按键重算，差 1 dp 以内不重算）；设备上的数是 2026-11-14 在模拟器上量的（API 29/33/34/36，见 061-landscape-height-impl-report），不在每次 UAT 里跑。

### LS-48 [P1] 横屏「很高」
- **前提**：同 LS-47，**键盘高度** 选"很高"。
- **操作**：三键导航、手势导航各看一次。
- **预期**：三键导航每行约 52 dp、应用至少 120 dp；手势导航每行约 40 dp、应用至少 120 dp（"很高"和"标准"一样高：都碰到底线）。
- **JVM**：`LandscapeUatTest.tallestLeavesTheApp120dp`；设备上的数是 2026-11-14 在模拟器上量的（API 29/33/34/36，见 061-landscape-height-impl-report），不在每次 UAT 里跑。

### LS-49 [P1] 折叠屏展开、平板：四个方向一样高
- **前提**：折叠屏展开（`wm size 1767x2208` + `wm density 420`）、平板（`wm size 1600x2560` + `wm density 320`），B0，标准档。
- **操作**：横拿、竖拿各点一次「普通文本」框；**横屏键盘样式** 选普通，再选单手，各看一次；测完 `wm size reset; wm density reset`。
- **预期**：标准档键盘约 312 dp（含导航条），每行约 56 dp，四种方向一样高；选普通、单手时也一样高。
- **JVM**：`WideScreenUatTest.anUnfoldedFoldableUprightIsLaidOutTurned`、`WideScreenUatTest.theUnfoldedFoldableTurnedIsAsHigh`、`WideScreenUatTest.aTabletUprightIsTurnedAndSplits`、`KeyboardAreaTest.aTabletAShortSideOver600IsLargeWhicheverWayHeld`（601×962 dp 平板减掉导航条后仍是大屏）；设备上的数是 2026-11-14 在模拟器上量的（API 29/33/34/36，见 061-landscape-height-impl-report），不在每次 UAT 里跑。

### LS-50 [P2] 小手机横屏：每行最矮 36 dp
- **前提**：`wm size 720x1280` + `wm density 320`（360×640 dp），手势导航，B0。
- **操作**：横屏点「普通文本」框；把 **编码行** 改成「嵌在键盘里」并换成拼音 14 键再看；换成注音再看；再在 Chrome 打开一个全屏网页视频页的输入框（状态栏隐藏）看；测完 `wm size reset; wm density reset`。
- **预期**：每行 36 dp、字正常大小、应用约 104 dp；编码行嵌在键盘里、14 键时每行仍 36、应用约 72 dp；注音每行 36（5 行）、应用约 68 dp；全屏应用里每行约 38、键盘约 240 dp、应用约 120 dp。
- **JVM**：`KeyboardHeightCapTest.theRowsWinOverTheFloor`、`ImeRoomViewTest.aSmallPhonesZhuyinKeepsItsFiveRows`（注音 5 行 × 36 dp，比 4 行的拼音多占应用）、`ImeRoomTest.flushAndFullScreenAndASideBar`（全屏时不减状态栏）、`LowLandscapeScreenshotTest`（每行 36 dp、28 dp 时符号、表情、剪贴板、语音、展开候选、数字键盘和注音的截图）；设备上的数是 2026-11-14 在模拟器上量的（API 29/33/34/36，见 061-landscape-height-impl-report），不在每次 UAT 里跑。

### LS-51 [P2] 五档依次变高，切样式、转屏不跳
- **前提**：大手机（`wm size 1344x2992` + `wm density 480`，448×997 dp），三键导航，B0。
- **操作**：横屏，**键盘高度** 依次选五档；标准档时在普通、分体、单手之间切换；转屏 20 次（每次回到横屏）。
- **预期**：五档每行依次约 34/41/48/54/59 dp；切样式时高度不变、不跳动；转屏时键盘不先矮后高（不往上滑一截）。
- **JVM**：ime-core `KeyboardHeightLevelsTest.turnedALevelIsARowOfTheTable`、`KeyboardSizesTest.turnedTheRowIsTheSameOnEveryScreenOfAKind`；转屏看设备。
