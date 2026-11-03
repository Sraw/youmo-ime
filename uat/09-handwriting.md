# 09 手写

区域代号 `HW`。前提里的 **B0-手写**：B0，然后在设置的 **输入法** 里添加"手写"，回测试页点「普通文本」框，点地球键直到键盘变成书写板。

"写「X」"指用手指在书写板上按正常笔顺、正常大小写一个工整的 X，写完停笔。

### HW-01 [P0] 手写键盘的样子
- **前提**：B0-手写。
- **操作**：看键盘。
- **预期**：大部分是书写板；右边是 ⌫、"，"、"。"、注；下面一排是符、123、空格、地球键、回车。
- **JVM**：`HandwritingUatTest.theHandwritingKeyboard`（书写板占大半、右边 ⌫ ， 。 注、下排 符 123 等；图）；设备上不跑。

### HW-02 [P0] 写一个字，候选是这个字的几种可能
- **前提**：B0-手写。
- **操作**：写「人」，停笔约 1 秒。
- **预期**：写的过程中候选栏就出现候选；停笔后书写板清空；框里出现带下划线的「人」（还没上屏），候选栏里有「人」。
- **JVM**：`HandwritingUatTest.whileWritingTheReadingIsOfferedAndThePauseWipesThePad`、`HostCasesUatTest.aCharacterWrittenIsComposingInTheFieldWithItsReadings`（写的过程中读出的墨迹就交给引擎（识别器模型换成固定读法），停笔后书写板清空；墨迹喂给引擎后框里组字「人」（未上屏），候选有「人」）；设备上不跑。

### HW-03 [P0] 空格上屏整句
- **前提**：B0-手写。
- **操作**：写「大」，停笔；写「人」，停笔；点空格。
- **预期**：框里是「大人」，没有下划线。
- **JVM**：`HandwritingEngineUatTest.spaceCommitsTheSentenceWritten`（写两字空格上屏）；设备上留作冒烟（书写板触摸到识别的胶水）。

### HW-04 [P1] 后面写的字改正前面认错的
- **前提**：B0-手写。这个用例要一个识别拿不准的「谈」，手写不容易照着做出来：`uat/scripts` 里的手写脚本用固定笔画（hw_strokes.py）复现；人工测试时如果单写出来的不是「淡」，换个写法再试，三次都做不出来记为 **跳过**，不算失败。
- **操作**：写一个想写「谈」、左边却写得像三点水的字（第一笔一点，第二笔从第二点连下来向右上提：讠和氵之间），停笔，记下框里显示的字和候选；再写「话」，停笔。
- **预期**：单写时框里是「淡」、候选第二个是「谈」；写完「话」后框里那句是「谈话」（前面认成的「淡」被改过来，见 docs/other-input-methods.md「手写」）。
- **JVM**：`HandwritingEngineUatTest.aCharacterWrittenAfterCorrectsTheOneBefore`（:lib:ime-eval:uatTest，真数据）（单写读成「淡」、第二是「谈」，写完「话」是「谈话」（识别给的读法照设备记下的写入））；设备上不跑。

### HW-05 [P1] 点候选上屏以它结尾的那一句
- **前提**：B0-手写。
- **操作**：写「大」，停笔；写「人」，停笔；在候选里点第二个候选 C。
- **预期**：框里是候选 C 代表的那一句：两个字，以 C 结尾（前一个字也可能因此读成别的字，不一定还是「大」），没有下划线。
- **JVM**：`HandwritingEngineUatTest.aCandidatePickedPutsInTheSentenceEndingWithIt`（:lib:ime-eval:uatTest，真数据）（点第二候选上屏两个字、以它结尾）；设备上不跑。

### HW-06 [P1] 退格撤掉最后一笔
- **前提**：B0-手写。
- **操作**：在书写板上写「十」的第一笔"一"，不等停笔马上点 ⌫。
- **预期**：书写板上的那一笔消失，候选清空。
- **JVM**：`HandwritingUatTest.backspaceTakesTheLastStrokeBack`（写一笔马上 ⌫：板上没有笔画，不发给引擎）；设备上不跑。

### HW-07 [P1] 退格去掉最后一个字，再退格删前面的字
- **前提**：B0-手写，「普通文本」框里已有「好」（用别的输入法打的），光标在末尾。
- **操作**：写「人」，停笔；点 ⌫；再点 ⌫。
- **预期**：第一次退格去掉还没上屏的「人」，框里剩「好」；第二次退格删掉「好」，框里为空。
- **JVM**：`HandwritingEngineUatTest.backspaceTakesTheUnwrittenCharacterThenTheText`（:lib:ime-eval:uatTest，真数据）（第一次退格剩「好」，第二次为空）；设备上不跑。

### HW-08 [P1] 回车和标点先上屏整句
- **前提**：B0-手写。
- **操作**：写「人」，停笔；点右边的 **。**。
- **预期**：框里是「人。」。
- **JVM**：`HandwritingEngineUatTest.punctuationPutsTheSentenceInFirst`（:lib:ime-eval:uatTest，真数据）（「人。」）；设备上不跑。

### HW-09 [P1] 收起键盘时没上屏的字留在框里
- **前提**：B0-手写。
- **操作**：写「人」，停笔；点工具栏的 ⌄ 收起键盘（或点别的输入框）。
- **预期**：「人」留在「普通文本」框里，没有下划线。
- **JVM**：`HostCasesUatTest.aCharacterNotCommittedStaysInTheFieldWhenTheKeyboardGoes`（收起键盘后「人」留在框里、不再组字）；设备上不跑。

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
- **前提**：B0-设置，添加手写。
- **操作**：手写设置里 **停笔多久算写完一个字** 选"很短（0.3 秒）"，回测试页切到手写，写「人」，停笔约 0.5 秒；回手写设置改选"很长（1.5 秒）"，回测试页清空框，写「十」的一横，停笔约 1 秒，再写一竖，然后停笔。
- **预期**：很短时 0.5 秒内书写板就清空，「人」进入框里；很长时一横之后 1 秒内书写板不清空，写完一竖停笔后认出「十」。
- **JVM**：`HandwritingUatTest.aShortAndALongPause`（很短时 0.5 秒内写完；很长时 1 秒还在板上，写完一竖停笔后写完。认出什么字是识别模型的事）；设备上不跑。

### HW-14 [P1] 点选第一个以外的字，下次排前；连着点两次排第一
<!-- learning: U-29 D9 -->
- **前提**：B0-手写。
- **操作**：写「人」，停笔，记下前三个候选（如「人、入、八」）；点第二个「入」上屏；再写「人」，看候选，点「入」；再写「人」，看候选。
- **预期**：第二次写，「入」排第二、第一个仍是「人」；第三次「入」排第一（同样三个候选，连着点了两次）。
- **JVM**：`HandwritingEngineUatTest.aCharacterPickedRanksUpTwiceFirst`（:lib:ime-eval:uatTest，真数据）（点一次「入」排第二，两次排第一）；设备上不跑。

### HW-16 [P1] 密码框里换成英文键盘
- **前提**：B0-手写。
- **操作**：点「密码」框。
- **预期**：键盘换成英文全键盘（不是书写板），上面一排数字；再点「普通文本」框，回到书写板。
- **JVM**：`HandwritingUatTest.aPasswordIsTheEnglishKeyboard`（引擎换成英文后是全键盘、上面一排数字，没有书写板；回普通框是书写板）；设备上不跑。

### HW-19 [P1] 手写的候选长按：没有固定首位；屏蔽能撤销，忘掉当场重排
<!-- learning: U-82 C18 F2738 F2697 -->
- **前提**：B0-手写。
- **操作**：写「人」，记下候选顺序，点候选里的「入」上屏；再写「人」，长按候选里的「入」，选 **屏蔽**、确认；不重写，马上点键盘上方提示里的 **撤销**；再长按「入」选 **忘掉学到的**；删光，再写「人」。
- **预期**：菜单里有 **忘掉学到的**（学到过才有）和 **屏蔽**，没有 **固定首位**。屏蔽后候选里没有「入」，提示在键盘上方，不盖任何候选；点撤销后不用重写，候选里马上又有「入」；忘掉以后候选当场回到第一次写「人」时的顺序，再写「人」也一样。
- **JVM**：`KeyboardHalvesUatTest.handwritingsMenuHasNoPinItsBlockUndoneAndForgettingReordersAtOnce`（uatTest，真数据：菜单有忘掉学到的和屏蔽、没有固定首位；屏蔽后没有「入」，撤销后不重写就回来；忘掉后当场回到第一次的顺序，重写也一样）、`HandwritingUatTest.handwritingsMenuHasNoPinAndItsNoticeIsOverTheCandidates`（菜单的字、提示条在候选栏上方、撤销送 Undo）；设备上不跑。

### HW-25 [P1] 在框里点一下没上屏的字：原样上屏
- **前提**：B0-手写。
- **操作**：写「人」，再写「大」（两个字都还带下划线，没上屏）；在输入框里「人」的前面点一下；再写「十」。
- **预期**：「人大」原样上屏，没有下划线；候选栏和书写板清空；光标回到「人大」后面（上屏的字等于正在组的字时，光标放到它的末尾，0.3.x 也这样）；「十」从新的一句开始写，「人大」不变（框里是「人大十」，只有「十」带下划线）。上屏是 fcitx 点组字文字时的默认做法；光标放到末尾是本应用 `EditingSession.commitText` 的做法。
- **JVM**：`HostCasesUatTest.aTapOnCharactersNotInPutsThemInAsTheyAre`（点在未上屏的字前：原样上屏、光标在后、再写另起）；设备上不跑。

### HW-26 [P1] 简繁切换时没上屏的手写字当场变繁体
- **前提**：B0-手写，繁体关。
- **操作**：写「国」（还带下划线，没上屏），在这时打开 **繁体**，再按空格。设备上没有这时能走的入口：候选栏上正是「国、图、固」，栏左边没有 ›（只在空闲栏上有），⋯ 打不开，实体键会切到拼音；所以本条只在 JVM 上跑，直接切简繁。
- **预期**：切换的一刻，不用再写，框里没上屏的字就显示成「國」；按空格上屏的是「國」。
- **JVM**：`HandwritingEngineUatTest.aCharacterNotYetInIsShownTraditionalAtTheToggle`（:lib:ime-eval:uatTest，真数据）（切繁体时组字文字重发成「國」，空格上屏「國」）；设备上不跑。

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
- **JVM**：`CandidateReadingsUatTest.handwritingPortrait`（竖屏，拼音和注音、20 和 28sp、浅色和深色；图）、`CandidateReadingsUatTest.handwritingLandscape`（横屏；图）、`ReadingSpanTest`（拼音缩到下限再加宽，注音一列一个）、`EnginesHandwritingReadingsTest.aWrittenCharacterShowsItsReadingsTheWordsFirst`（ime-core，测试数据：行 xíng/háng…，银之后 háng 在前）、`HandwritingEngineUatTest.theCandidatesLeaveOutACharactersOwnNeutralTone`（App 的数据：候选上 头 只有 tóu、和 没有 huo，字卡里有）；设备上留作冒烟。

### HW-28 [P1] 「注」键：点一下开关，写好的字不上屏
- **前提**：B0-手写，**候选词注音** 关闭。
- **操作**：写「银」「行」（还带下划线）；点右边最下面的「注」；再点一次；长按「注」，选 **注音符号**；写一笔还没停笔时点「注」。
- **预期**：第一次点后候选栏变高、候选标上拼音，「注」变成强调色，框里「银行」还带下划线（没上屏，不用重写）；再点一次关掉，栏变回原高；长按选注音符号后标注音；设置里的 **候选词注音** 跟着变。写到一半点「注」时先把这个字写完再切换，也不上屏。读屏读「候选词注音，已开」「候选词注音，已关」。
- **JVM**：`HandwritingUatTest.theReadingKeyTurnsTheReadingsOnAndWhatIsWrittenStays`（点「注」设成拼音、不 focusOutIn 不上屏、组字留着、栏变高、读屏文字；图）、`HandwritingKeyboardTest.theReadingKeyTurnsTheReadingsOnAsLastUsedAndOff`、`HandwritingKeyboardTest.itsLongPressSetsEachStyle`、`HandwritingKeyboardTest.aTapMidCharacterFinishesItFirstAndCommitsNothing`、`EnginesHandwritingReadingsTest.turnedOnWhatIsWrittenStaysAndIsReadAgain`（ime-core：只改注音时写好的字留着并重标）；设备上留作冒烟。

### HW-29 [P1] 长按手写的字出字卡
- **前提**：B0-手写。
- **操作**：写「银」「行」，长按候选里的「行」；竖屏、横屏、悬浮键盘各看一次。全拼里打 `ushuigong` 长按「江」；打 `upps` 长按「行」。
- **预期**：菜单最上面是字卡：大的「行」；读音每个一行，háng 在前（和候选栏同一个顺序），每行有拼音、注音符号、最多两个例词（只取词典里的词，没有「荣和」「去呢」「弦数」这种两个词拼成的）；部首「行」、6 画、拆字「彳 亍（chì chù）」、笔顺（大陆规范笔顺，五种笔形：丿丿丨一一丨）。字卡里有 **上屏**、**复制**，再下面是原来的 **忘掉学到的** / **屏蔽**。竖屏和悬浮是一列，横屏左右两列（上屏、复制在右列）。长按「和」：读音多于四个，用手指在读音上往上滑能滑动，最后是轻声 huo（候选栏里不标）。拆字、笔画查字的字也有字卡；直接打拼音出的字没有。读屏把字卡读成一句。
- **JVM**：`HandwritingUatTest.aHeldCharactersCardThenItsButtonsAndTheMenu`（字卡内容、读屏一句、按钮、菜单行；图）、`HandwritingUatTest.theCardInLandscapeIsTwoColumns`（图）、`HandwritingUatTest.theCardOnTheFloatingKeyboardIsOneColumn`（图）、`HandwritingUatTest.aCardLeavesOutWhatIsNotKnownAndScrollsItsReadings`（乾没有拆字、笔顺两行；和的六个读音，在菜单列表里拖动时滚的是读音；图）、`CharReadingsTest.theCardListsTheDictionariesNeutralTonesTooAfterTheRest`、`EnginesHandwritingReadingsTest.aCardOnTheSecondPageIsOfItsCharacter`（分页候选窗第二页的字卡）、`EnginesHandwritingReadingsTest.theCardOfAWrittenCharacterOrOneLookedUp`（ime-core：手写、拆字、笔画查字有字卡，打出的没有）、`HandwritingEngineUatTest.aCardsWordsAreADictionarys`（App 的数据：和、呢、数的例词都是词典里的）、`HandwritingEngineUatTest.aCardsStrokeOrderIsTheMainlands`（行、出、必、我、数的笔顺是大陆的）；设备上留作冒烟。

### HW-30 [P1] 字卡的复制不上屏，上屏和点候选一样
- **前提**：B0-手写。
- **操作**：写「银」「行」，长按「行」，点 **复制**；在别的地方粘贴；再长按「行」，点 **上屏**。
- **预期**：复制后菜单关上，剪贴板里只有「行」，框里「银行」还带下划线，什么都没上屏；点上屏和直接点候选「行」一样，上屏「银行」。
- **JVM**：`HandwritingUatTest.copyPutsTheCharacterAloneOnTheClipboardAndCommitIsATap`（剪贴板「行」、不 select 不发键、组字留着；上屏是 select）；设备上留作冒烟。
