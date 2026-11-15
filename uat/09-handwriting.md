# 09 手写

区域代号 `HW`。0.6.1 起手写不再是一个输入法，是工具栏上的 **手写** 开关：打开后在当前输入法键盘的字母区上写字（061-handwriting-toolbar）。
前提里的 **B0-手写**：B0，点「普通文本」框，点工具栏的 **手写**（新装时就钉在常驻区；按钮亮起，字母变淡）。

"写「X」"指用手指在字母区（q 到 m 三排，Shift 也算）按正常笔顺写一个占满这三排的工整的 X，写完停笔。

### HW-01 [P0] 手写开着时键盘的样子
- **前提**：B0-手写。
- **操作**：看键盘。
- **预期**：还是拼音全键盘，没有书写板：字母键和 Shift 上的字变淡（约三分之一深），没有格子、没有书写板底色；⌫、空格、123、符、地球键、回车照常；工具栏的 **手写** 亮着。
- **JVM**：`HandwritingUatTest.theKeyboardWithHandwritingOn`（字母和 ⌫、123 都在，字母变淡，工具栏的手写读作已勾选；图）；设备上不跑。

### HW-02 [P0] 写一个字，候选是这个字的几种可能
- **前提**：B0-手写。
- **操作**：写「人」，停笔约 1 秒。
- **预期**：写的过程中候选栏就出现候选；停笔后键上的笔迹淡出；「人」显示在拼音编码显示的地方（还没上屏，框里没有），候选栏里有「人」。
- **JVM**：`HandwritingUatTest.whileWritingTheReadingIsOfferedAndThePauseWipesThePad`、`HostCasesUatTest.aCharacterWrittenIsShownAsTypedWithItsReadings`（写的过程中读出的墨迹就交给引擎（识别器模型换成固定读法），停笔后笔迹清掉；墨迹喂给拼音后编码处是「人」、没有上屏，候选有「人」）；设备上不跑。

### HW-03 [P0] 空格上屏整句
- **前提**：B0-手写。
- **操作**：写「大」，停笔；写「人」，停笔；点空格。
- **预期**：框里是「大人」。
- **JVM**：`HandwritingEngineUatTest.spaceCommitsTheSentenceWritten`（写两字空格上屏）；设备上留作冒烟（字母区触摸到识别的胶水）。

### HW-04 [P1] 后面写的字改正前面认错的
- **前提**：B0-手写。这个用例要一个识别拿不准的「谈」，手写不容易照着做出来：`uat/scripts` 里的手写脚本用固定笔画（hw_strokes.py）复现；人工测试时如果单写出来的不是「淡」，换个写法再试，三次都做不出来记为 **跳过**，不算失败。
- **操作**：写一个想写「谈」、左边却写得像三点水的字（第一笔一点，第二笔从第二点连下来向右上提：讠和氵之间），停笔，记下框里显示的字和候选；再写「话」，停笔。
- **预期**：单写时框里是「淡」、候选第二个是「谈」；写完「话」后框里那句是「谈话」（前面认成的「淡」被改过来，见 docs/other-input-methods.md「手写」）。
- **JVM**：`HandwritingEngineUatTest.aCharacterWrittenAfterCorrectsTheOneBefore`（:lib:ime-eval:uatTest，真数据）（单写读成「淡」、第二是「谈」，写完「话」是「谈话」（识别给的读法照设备记下的写入））；设备上不跑。

### HW-05 [P1] 点候选上屏以它结尾的那一句
- **前提**：B0-手写。
- **操作**：写「大」，停笔；写「人」，停笔；在候选里点第二个候选 C。
- **预期**：框里是候选 C 代表的那一句：两个字，以 C 结尾（前一个字也可能因此读成别的字，不一定还是「大」）。
- **JVM**：`HandwritingEngineUatTest.aCandidatePickedPutsInTheSentenceEndingWithIt`（:lib:ime-eval:uatTest，真数据）（点第二候选上屏两个字、以它结尾）；设备上不跑。

### HW-06 [P1] 退格撤掉最后一笔
- **前提**：B0-手写。
- **操作**：在字母区写「十」的第一笔"一"，不等停笔马上点 ⌫。
- **预期**：键上那一笔的笔迹消失，候选清空。
- **JVM**：`HandwritingUatTest.backspaceTakesTheLastStrokeBack`（写一笔马上 ⌫：没有笔画了，不发给引擎）；设备上不跑。

### HW-07 [P1] 退格去掉最后一个字，再退格删前面的字
- **前提**：B0-手写，「普通文本」框里已有「好」（先关掉手写用拼音打的），光标在末尾。
- **操作**：写「人」，停笔；点 ⌫；再点 ⌫。
- **预期**：第一次退格去掉还没上屏的「人」，框里剩「好」；第二次退格删掉「好」，框里为空。
- **JVM**：`HandwritingEngineUatTest.backspaceTakesTheUnwrittenCharacterThenTheText`（:lib:ime-eval:uatTest，真数据）（第一次退格剩「好」，第二次为空）；设备上不跑。

### HW-08 [P1] 回车和标点先上屏整句
- **前提**：B0-手写。
- **操作**：写「人」，停笔；点 **。** 键（长按 **，** 选也行）。
- **预期**：框里是「人。」。
- **JVM**：`HandwritingEngineUatTest.punctuationPutsTheSentenceInFirst`（:lib:ime-eval:uatTest，真数据）（「人。」）；设备上不跑。

### HW-09 [P1] 收起键盘时没上屏的字留在框里
- **前提**：B0-手写。
- **操作**：写「人」，停笔；点工具栏的 ⌄ 收起键盘（或点别的输入框）。
- **预期**：「人」上屏留在「普通文本」框里（没写完的字收起时上屏，DECISIONS A2；拼音打了一半的编码不上屏）。
- **JVM**：`HostCasesUatTest.aCharacterNotCommittedIsPutInWhenTheKeyboardGoes`（收起键盘后「人」上屏、编码处清空；拼音打的 ni 收起时不上屏）；设备上不跑。

### HW-10 [P1] 上屏后联想下一个词
- **前提**：B0-手写。
- **操作**：写「我」，停笔；点空格；看候选栏；点第一个联想词。
- **预期**：上屏后候选栏给出联想（如「们」「的」）；点了那个词上屏。
- **JVM**：`HandwritingEngineUatTest.aSpaceThenPredictions`（:lib:ime-eval:uatTest，真数据）（空格上屏「我」后有联想，点第一个上屏）；设备上不跑。

### HW-11 [P1] 按回车上屏时不联想
- **前提**：B0-手写，点「句子」框。
- **操作**：写「我」，停笔；点回车。
- **预期**：「我」上屏；候选栏没有联想。
- **JVM**：`HandwritingEngineUatTest.returnPutsInWithoutPredictions`（:lib:ime-eval:uatTest，真数据）（回车上屏「我」，没有联想）；设备上不跑。

### HW-12 [P1] 停笔时间调短、调长
- **前提**：B0-设置。
- **操作**：**通用输入** 的 **手写** 小节里 **停笔多久算写完一个字** 选"很短（0.3 秒）"，回测试页打开手写，写「人」，停笔约 0.5 秒；回设置改选"很长（1.5 秒）"，回测试页清空框，写「十」的一横，停笔约 1 秒，再写一竖，然后停笔。
- **预期**：很短时 0.5 秒内笔迹就淡出，「人」写完；很长时一横之后 1 秒内笔迹还在，写完一竖停笔后认出「十」。
- **JVM**：`HandwritingUatTest.aShortAndALongPause`（很短时 0.5 秒内写完；很长时 1 秒还在板上，写完一竖停笔后写完。认出什么字是识别模型的事）；设备上不跑。

### HW-14 [P1] 点选第一个以外的字，下次排前；连着点两次排第一
<!-- learning: U-29 D9 -->
- **前提**：B0-手写。
- **操作**：写「人」，停笔，记下前三个候选（如「人、入、八」）；点第二个「入」上屏；再写「人」，看候选，点「入」；再写「人」，看候选。
- **预期**：第二次写，「入」排第二、第一个仍是「人」；第三次「入」排第一（同样三个候选，连着点了两次）。
- **JVM**：`HandwritingEngineUatTest.aCharacterPickedRanksUpTwiceFirst`（:lib:ime-eval:uatTest，真数据）（点一次「入」排第二，两次排第一）；设备上不跑。

### HW-16 [P1] 密码框里没有手写按钮，出来后接着写
- **前提**：B0-手写。
- **操作**：点「密码」框，点几个字母；再点「普通文本」框，写「人」。
- **预期**：密码框里是英文全键盘，字母不变淡、点了就打出，工具栏上没有 **手写**；回到「普通文本」框，**手写** 还亮着，字母又变淡，写得出「人」。
- **JVM**：`HandwritingUatTest.aPasswordIsTypedOnAndHandwritingComesBackAfter`（换成英文后没有手写按钮、不写；回普通框按钮亮着、一笔写得上）；设备上不跑。

### HW-19 [P1] 手写的候选长按：没有固定首位；屏蔽能撤销，忘掉当场重排
<!-- learning: U-82 C18 F2738 F2697 -->
- **前提**：B0-手写。
- **操作**：写「人」，记下候选顺序，点候选里的「入」上屏；再写「人」，长按候选里的「入」，选 **屏蔽**、确认；不重写，马上点键盘上方提示里的 **撤销**；再长按「入」选 **忘掉学到的**；删光，再写「人」。
- **预期**：菜单里有 **忘掉学到的**（学到过才有）和 **屏蔽**，没有 **固定首位**。屏蔽后候选里没有「入」，提示在键盘上方，不盖任何候选；点撤销后不用重写，候选里马上又有「入」；忘掉以后候选当场回到第一次写「人」时的顺序，再写「人」也一样。
- **JVM**：`KeyboardHalvesUatTest.handwritingsMenuHasNoPinItsBlockUndoneAndForgettingReordersAtOnce`（uatTest，真数据：菜单有忘掉学到的和屏蔽、没有固定首位；屏蔽后没有「入」，撤销后不重写就回来；忘掉后当场回到第一次的顺序，重写也一样）、`HandwritingUatTest.handwritingsMenuHasNoPinAndItsNoticeIsOverTheCandidates`（菜单的字、提示条在候选栏上方、撤销送 Undo）；设备上不跑。

### HW-25 [P1] 在框里点一下没上屏的字：原样上屏
- **前提**：B0-手写。
- **操作**：写「人」，再写「大」（两个字都还在编码处，没上屏）；在输入框里点一下；再写「十」。
- **预期**：「人大」原样上屏到点的新位置（不是写的时候的位置）；候选栏和笔迹清空；光标在「人大」后面；「十」从新的一句开始写，「人大」不变（框里是「人大」，「十」在编码处）。
- **JVM**：`HostCasesUatTest.aTapInTheFieldPutsTheWrittenInAsItIs`（点框：原样上屏「人大」、编码处清空、光标在后）、`HostCasesUatTest.aTapElsewhereInTheFieldPutsTheWrittenInAtTheNewCursor`（点到框里别处：上屏到新光标处）；设备上不跑。

### HW-26 [P1] 简繁切换时没上屏的手写字当场变繁体
- **前提**：B0-手写，繁体关。
- **操作**：写「国」（还在编码处，没上屏），在这时打开 **繁体**，再按空格。设备上没有这时能走的入口：候选栏上正是「国、图、固」，栏左边没有 ›（只在空闲栏上有），⋯ 打不开，实体键会先把写好的上屏；所以本条只在 JVM 上跑，直接切简繁。
- **预期**：切换的一刻，不用再写，没上屏的字就显示成「國」；按空格上屏的是「國」。手写跟着所在输入法（拼音）的简繁。
- **JVM**：`HandwritingEngineUatTest.aCharacterNotYetInIsShownTraditionalAtTheToggle`（:lib:ime-eval:uatTest，真数据）（切繁体时编码重发成「國」，空格上屏「國」）；设备上不跑。

### HW-23 [P1] 删掉的不算用过
<!-- learning: U-03 -->
- **前提**：B0-手写。
- **操作**：写「人」，记下候选顺序，点第三个上屏，马上点 ⌫ 删掉；再写「人」，看候选。
- **预期**：顺序和第一次一样（删掉的那次不算用过）。
- **JVM**：`HandwritingEngineUatTest.aCharacterDeletedRightAwayIsNotUsed`（:lib:ime-eval:uatTest，真数据）（点第三个又删掉，再写顺序不变）；设备上不跑。

### HW-27 [P1] 候选词注音打开时，手写的多音字标全部读音
- **前提**：B0-手写，**候选词注音** 选拼音。
- **操作**：写「行」，看候选栏；删光，写「银」再写「行」；把候选词注音改成注音符号，再写「行」。候选字号 20 和 28 各看一次，横屏再看一次。
- **预期**：「行」上方标 xíng/háng…（前两个读音，「/」隔开，还有就加「…」）；写了「银」以后「行」标 háng/xíng…；只有一个读音的字（街）照旧一个；拼音放不下时字变小但不小于 7sp，再放不下候选变宽；注音符号是一个读音一竖列并排，还有更多时后面有「⋮」。栏高和只标一个读音时一样。
- **JVM**：`EnginesHandwritingReadingsTest.turnedOnWhatIsWrittenStaysAndIsReadAgain`（ime-core：写着字时打开注音，写好的字留着并重标）、`CandidateReadingsUatTest.handwritingPortrait`（竖屏，拼音和注音、20 和 28sp、浅色和深色；图）、`CandidateReadingsUatTest.handwritingLandscape`（横屏；图）、`ReadingSpanTest`（拼音缩到下限再加宽，注音一列一个）、`EnginesHandwritingReadingsTest.aWrittenCharacterShowsItsReadingsTheWordsFirst`（ime-core，测试数据：行 xíng/háng…，银之后 háng 在前）、`HandwritingEngineUatTest.theCandidatesLeaveOutACharactersOwnNeutralTone`（App 的数据：候选上 头 只有 tóu、和 没有 huo，字卡里有）；设备上不跑。

### HW-29 [P1] 长按手写的字出字卡
- **前提**：B0-手写。
- **操作**：写「银」「行」，长按候选里的「行」；竖屏、横屏、悬浮键盘各看一次。全拼里打 `ushuigong` 长按「江」；打 `upps` 长按「行」。
- **预期**：菜单最上面是字卡：大的「行」；读音每个一行，háng 在前（和候选栏同一个顺序），每行有拼音、注音符号、最多两个例词（只取词典里的词，没有「荣和」「去呢」「弦数」这种两个词拼成的）；部首「行」、6 画、拆字「彳 亍（chì chù）」、笔顺（大陆规范笔顺，每笔按具体笔形：丿丿丨一一亅；读屏读出「撇、撇、竖、横、横、竖钩」）。字卡里有 **上屏**、**复制**，再下面是原来的 **忘掉学到的** / **屏蔽**。竖屏和悬浮是一列，横屏左右两列（上屏、复制在右列）。长按「和」：读音多于四个，用手指在读音上往上滑能滑动，最后是轻声 huo（候选栏里不标）。拆字、笔画查字的字也有字卡；直接打拼音出的字没有。读屏把字卡读成一句。
- **JVM**：`HandwritingUatTest.aHeldCharactersCardThenItsButtonsAndTheMenu`（字卡内容、读屏一句、按钮、菜单行；图）、`HandwritingUatTest.theCardInLandscapeIsTwoColumns`（图）、`HandwritingUatTest.theCardOnTheFloatingKeyboardIsOneColumn`（图）、`HandwritingUatTest.aCardLeavesOutWhatIsNotKnownAndScrollsItsReadings`（乾没有拆字、笔顺两行；和的六个读音，在菜单列表里拖动时滚的是读音；图）、`CharReadingsTest.theCardListsTheDictionariesNeutralTonesTooAfterTheRest`、`EnginesHandwritingReadingsTest.aCardOnTheSecondPageIsOfItsCharacter`（分页候选窗第二页的字卡）、`EnginesHandwritingReadingsTest.theCardOfAWrittenCharacterOrOneLookedUp`（ime-core：手写、拆字、笔画查字有字卡，打出的没有）、`HandwritingEngineUatTest.aCardsWordsAreADictionarys`（App 的数据：和、呢、数的例词都是词典里的）、`HandwritingEngineUatTest.aCardsStrokeOrderIsTheMainlands`（行、出、必、我、数的笔顺是大陆的）、`HandwritingEngineUatTest.aCardsTurningStrokesEachTheirShape`（口 己 乙 九 马 冖 等的折笔）、`HandwritingEngineUatTest.theStandardsExamplesEachTheirTurningStroke`（《GB13000.1 字符集汉字折笔规范》折笔笔形表的例字，每个都有它那种折笔）；设备上不跑。

### HW-30 [P1] 字卡的复制不上屏，上屏和点候选一样
- **前提**：B0-手写。
- **操作**：写「银」「行」，长按「行」，点 **复制**；在别的地方粘贴；再长按「行」，点 **上屏**。
- **预期**：复制后菜单关上，剪贴板里只有「行」，「银行」还在编码处，什么都没上屏；点上屏和直接点候选「行」一样，上屏「银行」。
- **JVM**：`HandwritingUatTest.copyPutsTheCharacterAloneOnTheClipboardAndCommitIsATap`（剪贴板「行」、不 select 不发键、组字留着；上屏是 select）；设备上不跑。

### HW-31 [P0] 连写几个字，最后写的字能从菜单屏蔽
<!-- learning: U-82 -->
- **前提**：B0-手写。
- **操作**：写「天」，长按候选里的「天」看菜单；再接着写「人」（不上屏），长按候选里的「人」看菜单，选 **屏蔽**，确认；然后在 **屏蔽的词** 里恢复「人」，再写一遍「人」。
- **预期**：两次菜单里都有 **屏蔽**（屏蔽的是候选上显示的那个字：连写时候选栏显示的是最后一段写的字的几种可能，屏蔽的就是它；前面的字在它是最后一个时一样能长按屏蔽）；屏蔽后候选里不再有「人」，最后那段换成别的读法，候选不空；屏蔽的词里列着「人」；恢复后又写得出「人」。
- **JVM**：`HandwritingEngineUatTest.theLastCharacterWrittenIsBlockedFromTheKeyboard`（:lib:ime-eval:uatTest，真数据：写「天」「人」，屏蔽「人」后「天人」这个词也不再把「人」带出来）、`HandwritingSessionTest`「a long press on what may follow or on a candidate forgets what was learned of it or blocks it」「a character blocked then unblocked comes back when what is written is read again」。设备上跑过（2026-11-08，emulator-5574）。

## 写在键上

0.6.0 的 **在键上划 → 键盘手写** 和手写输入法合成了工具栏的 **手写**（061-handwriting-toolbar）。下面的前提都是 B0-手写。

### HW-32 [P0] 在字母键上写字，空格上屏；短划也是一笔
- **前提**：B0-手写。
- **操作**：什么都没打时写「人」，停笔约 1 秒；看候选栏；点空格。再在 w 上往下短划一下，停笔约 1 秒。
- **预期**：写的时候键上画出笔迹，没有按键预览、没有打出字母；停笔后笔迹淡出，候选里有「人」；点空格后框里是「人」。w 上的短划当成一笔（写字），不打出角标「2」。
- **JVM**：`KeyHandwritingUatTest.aStrokeOnTheLettersIsReadAsTheInputMethodsHandwritingNoKeyPressed`（笔画读出来交给引擎、没发按键、停笔后片段写完）、`KeyHandwritingUatTest.aShortSwipeIsAStrokeNotTheKeysCorner`（短划不打角标）、`InkOverTest.anInkIsWrittenItsCandidatesPickedAndWhatWasPutInToldPinyin`（ime-core）；设备上跑写字和空格。

### HW-34 [P1] 写字中途点一下是一笔，⌫ 撤一笔，空格先写完再上屏
- **前提**：B0-手写，**通用输入 → 手写** 里停笔时间选最长。
- **操作**：写「大」的前两笔，马上点 ⌫；再写那一笔；点空格。
- **预期**：⌫ 撤掉最后一笔（键上的笔迹少一笔），没有删框里的字；空格把这个字写完并上屏「大」。
- **JVM**：`KeyHandwritingUatTest.whileWritingATapIsAStrokeBackspaceTakesOneBackAndSpaceCommits`；设备上不跑（adb 的触摸间隔掐不准停笔时间）。

### HW-36 [P1] 九键、14 键、五笔、注音上也能写
- **前提**：B0-手写，**输入法 → 拼音** 的 **键盘** 分别选九键、14 键；添加「五笔」「注音」。
- **操作**：在拼音九键、14 键、五笔、注音（大千和注音九键）的键上各写「人」。
- **预期**：和全键盘一样写出「人」。
- **JVM**：`KeyHandwritingUatTest.theOtherKeyboardsAreWrittenOnToo`（九键、五笔、14 键、注音大千、注音九键各一行）；设备上不跑。

### HW-37 [P1] 网址、邮箱框照写；密码框、英文、快速输入、触摸浏览时不写
- **前提**：B0-手写。
- **操作**：在「网址」「邮箱」框里各写「人」；在「密码」框里在键上划一道；换到英文在键上划一道；打开 TalkBack 后在拼音键盘上划一道。
- **预期**：网址、邮箱框里和普通框一样写得出（工具栏的 **手写** 亮着）。密码框、英文、TalkBack 触摸浏览时字母不变淡，不出笔迹、不写字，键照原来的点按和划动处理；英文里按键直接打出，不等识别。
- **JVM**：`KeyHandwritingUatTest.urlAndEmailFieldsAreWrittenOn`、`KeyHandwritingUatTest.notInAPasswordFieldNorEnglishNorQuickInputNorWhileTalkBackExplores`、`KeyHandwritingUatTest.englishKeysGoStraightOnTheRecognizerNotMade`；设备上不跑。

## 工具栏的手写开关

### HW-40 [P1] 手写的字照旧直接是完整字卡；手写之后的联想词是读音行
- **前提**：B0-手写。
- **操作**：写「行」，长按候选「行」；点上屏后长按一个联想词。
- **预期**：手写的字长按第一行直接是完整字卡（和以前一样）；联想词长按第一行是读音行，点开是词卡。
- **JVM**：app `HandwritingUatTest.aHeldCharactersCardThenItsButtonsAndTheMenu`（图不变）；ime-core `EnginesHandwritingReadingsTest`（写的字 Looked，联想词 Word）；设备上不跑。

### HW-41 [P0] 点工具栏「手写」开、关
- **前提**：B0。
- **操作**：点「普通文本」框，点工具栏的 **手写**；在 26 键字母上写「人」，停笔，点空格；再点工具栏的 **手写**，点 q。
- **预期**：点后按钮亮起，字母变淡；写出「人」，空格后框里是「人」。再点按钮熄灭，字母恢复，点 q 打出 q（编码处是 q）。
- **JVM**：`HandwritingUatTest.theToolbarsButtonTurnsHandwritingOffAndOn`（点了关：按钮不再勾选、字母不淡；再点：开）、`KeyHandwritingUatTest.aStrokeOnTheLettersIsReadAsTheInputMethodsHandwritingNoKeyPressed`、`KeyHandwritingUatTest.turnedOffTheLettersAreTyped`；设备上也跑（截图给人看淡掉的字母）。

### HW-42 [P0] 点一下字母区是一个点，接着写成「主」
- **前提**：B0-手写。
- **操作**：在字母区上半点一下（「主」的点），马上接着写「主」其余的笔画，停笔。
- **预期**：点的那一下不打出字母，键上留一个点的笔迹；停笔后候选里有「主」。
- **JVM**：`KeyHandwritingUatTest.whileWritingATapIsAStrokeBackspaceTakesOneBackAndSpaceCommits`（写着字时点一下是一笔）；设备上也跑。

### HW-43 [P1] 手写开着时其它键照常；Shift 是字母区
- **前提**：B0-手写。
- **操作**：依次点 ⌫、空格、**，**、123（再返回）、符（再返回）、地球键；在 Shift 上起笔写一横；从字母区起笔划过空格。
- **预期**：这些键照常（123、符号面板打开，地球键换输入法）；Shift 上起笔是一笔、不切大小写；从字母区划过空格是一笔，空格不打。
- **JVM**：`KeyHandwritingUatTest.shiftIsWrittenOn`（Shift 上起笔写字、不发 Shift）；设备上不跑。

### HW-44 [P1] 别的键盘也能写；英文没有手写按钮，切回来还开着
- **前提**：B0-手写。
- **操作**：在拼音九键、14 键、18 键、注音九键、大千、五笔上各写「人」；切到英文看工具栏；切回拼音写「人」。
- **预期**：每种键盘都写得出「人」；英文里工具栏没有 **手写**，字母照打；切回拼音 **手写** 还亮着，写得出。
- **JVM**：`KeyHandwritingUatTest.theOtherKeyboardsAreWrittenOnToo`、`KeyHandwritingUatTest.anotherInputMethodAndBackTheToggleIsKept`、`HandwritingModeTest.offeredInEveryChineseInputMethodNotEnglishNorAPassword`（ime-core）；设备上不跑。

### HW-45 [P1] 写着字时候选栏上的手写按钮
- **前提**：B0-手写。
- **操作**：写「人」，看候选栏右端；点 ⌄ 左边亮着的 **手写**。
- **预期**：写着字（有候选）时候选栏右端 ⌄ 左边有亮着的 **手写**，读屏读成开关、已开；点了手写关掉，按钮不见；写好的「人」留着，不替你上屏（还在编码处，点候选或空格上屏）；字母恢复；这时点 q，「人」先上屏，再打出 q 的编码。
- **JVM**：`HandwritingUatTest.theCandidateBarsButtonIsLitWhileHandwritingIsOnAndTurnsItOff`（候选栏的手写按钮显示、可勾选且已勾选，点了关、按钮不见；不是开关的按钮不读成开关）；设备上也跑。

### HW-46 [P1] 拼音打着字时打开手写：第一笔先上屏第一个候选
- **前提**：B0。
- **操作**：打 ni，在还没上屏时打开手写，在字母区写「人」，停笔。设备上打着字时工具栏换成了候选栏，没有能走的入口；本条只在 JVM 上跑。
- **预期**：第一笔落下时先上屏「你」（ni 的第一个候选），再写「人」。
- **JVM**：`InkOverTest.aFirstStrokeWhilePinyinHasSomethingTypedPutsItsFirstCandidateInThenWrites`、`EnginesInkOverTest.aStrokeWhilePinyinIsTypedPutsItsFirstCandidateInThenIsWritten`（ime-core）；设备上不跑。

### HW-47 [P1] 换面板、换输入法后手写还开着
- **前提**：B0-手写。
- **操作**：点 123 再返回；点符再返回；地球键换到英文再换回拼音；每次之后写「人」。
- **预期**：每次回到拼音字母键盘，**手写** 还亮着，写得出「人」。
- **JVM**：`KeyHandwritingUatTest.anotherInputMethodAndBackTheToggleIsKept`（123 再返回、换英文再换回，都还写得上）；设备上不跑。

### HW-48 [P1] 收起键盘再打开，手写还开着
- **前提**：B0-手写。
- **操作**：点工具栏的 ⌄ 收起键盘；再点「普通文本」框；写「人」。
- **预期**：键盘回来时 **手写** 还亮着，字母淡着，写得出「人」（开关记在设置里，换框、收起、重启都不变）。
- **JVM**：`KeyHandwritingUatTest.theKeyboardHiddenAndShownAgainStillWrites`（收起再开、键盘重建，都还写得上）；设备上也跑。

### HW-49 [P1] 快速输入里字母照打，退出后接着写
- **前提**：B0-手写。
- **操作**：长按符（或按快速输入的入口）进快速输入，点几个字母；退出快速输入，写「人」。
- **预期**：快速输入里字母不变淡，点了就打；退出后字母又变淡，写得出「人」。
- **JVM**：`KeyHandwritingUatTest.notInAPasswordFieldNorEnglishNorQuickInputNorWhileTalkBackExplores`（快速输入一行）、`KeyHandwritingUatTest.outOfQuickInputTheLettersAreWrittenOnAgain`；设备上不跑。

### HW-50 [P1] 手写开着时滑行输入、笔画筛选、上下滑都不起
- **前提**：B0-设置，**通用输入 → 在键上划** 选滑行输入；回测试页打开手写。
- **操作**：从 t 划到 p；关掉手写，再从 t 划到 p。再把在键上划改成笔画筛选、打开手写，打 ni 后在键上划一横；在 w 上往上划一下。
- **预期**：手写开着时划过去是写字（出笔迹），不出滑行的词；关掉后照旧是滑行输入。笔画筛选、上下滑的角标也一样：手写开着时都是写字的笔画。
- **JVM**：`KeyHandwritingUatTest.glideTypingWaitsWhileHandwritingIsOn`（开着划是写字、不送滑行；关了送滑行）、`KeyHandwritingUatTest.aShortSwipeIsAStrokeNotTheKeysCorner`（短划不打角标）；设备上不跑。

### HW-51 [P1] 拼音上手写的多音字标全部读音，长按是完整字卡
- **前提**：B0-手写，**候选词注音** 选拼音。
- **操作**：写「行」，看候选；长按候选里的「行」；关掉手写，打 xing，看「行」的读音。
- **预期**：手写的「行」标 xíng/háng…（全部读音，0.6.0 手写在拼音键盘上写时只标一个，0.6.1 修好）；长按是完整字卡，菜单里没有 **固定首位**。打 xing 出来的「行」只标 xíng。
- **JVM**：`EnginesHandwritingReadingsTest.aWrittenCharacterShowsItsReadingsTheWordsFirst`、`EnginesHandwritingReadingsTest.aCharacterPinyinLooksUpAfterWritingShowsOneReading`、`EnginesInkOverTest.pinyinsOwnCandidatesOfferPinningAfterWritingHandwritingsDoNot`（ime-core）；设备上不跑。

### HW-52 [P2] 横屏分体：中间空隙也能起笔
- **前提**：B0-横屏，打开手写（分体键盘）。
- **操作**：从两半中间的空隙起笔写「人」。
- **预期**：从空隙起的笔照样是一笔，写得出「人」，不打字母。
- **JVM**：`KeyHandwritingUatTest.aStrokeStartsInTheSplitGap`；设备上不跑。

### HW-53 [P2] 拇指搁在键盘上，另一根手指照样写
- **前提**：B0-手写。
- **操作**：① 写字的手指在 t 上落下开始写「十」的第一笔，拇指随后按到 m 上不抬，写完两笔，停笔。② 拇指先按住空格不抬，另一根手指写「十」的两笔，停笔。
- **预期**：两次都是两笔都写上、候选里有「十」；拇指那里没有笔迹，不打出 m、也不打空格；拇指抬起不算一笔。（拇指先搁在字母区上时，那一下本身就是一笔，另一根手指不算：和 0.6.0 的书写板一样。）
- **JVM**：`KeyHandwritingUatTest.aFingerRestingThroughSeveralStrokesIsNoInk`、`KeyHandwritingUatTest.aThumbHoldingSpaceLetsTheLettersBeWrittenOn`、`GlideTouchTest.handwritingWithAThumbRestingEachDownOnTheLettersWritesAgain`（ime-core）；设备上不跑（模拟器打不出多指）。
