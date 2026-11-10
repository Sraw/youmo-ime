# 06 拼音（全拼）

区域代号 `PY`。

## 打字

### PY-01 [P0] 空格上屏整句首选
- **前提**：B0。
- **操作**：点 n i h a o，点空格。
- **预期**：框里是「你好」。
- **JVM**：`PinyinUatTest.spaceCommitsTheFirstSentence`（:lib:ime-eval:uatTest，真数据）；设备上留作冒烟（看胶水）。

### PY-02 [P0] 整句
- **前提**：B0。
- **操作**：点 w o m e n y i q i q u g o n g y u a n，点空格。
- **预期**：框里是「我们一起去公园」。
- **JVM**：`PinyinUatTest.aWholeSentence`（整句上屏，真数据）；设备上不跑。

### PY-03 [P0] 只选前半句，剩下的接着给候选
- **前提**：B0。
- **操作**：点 w o m e n q u n a l i；在候选里点「我们」（单独的词，不是整句）。
- **预期**：「我们」定下来，留在拼音行的最前面（拼音行是 `我们qu na li`，和 PY-42 的 `你hao` 一样，整句选完才进框，框里还是空的）；剩下的 `qunali` 还在打字状态，候选第一个是「去哪里」或类似的后半句。
- **JVM**：`PinyinUatTest.theFirstHalfPickedTheRestStillOffered`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-04 [P0] 点别的候选上屏那一个
- **前提**：B0。
- **操作**：点 s h i，点候选栏的第三个候选。
- **预期**：框里是那第三个字。
- **JVM**：`PinyinUatTest.anotherCandidateCommitsThatOne`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-05 [P0] 退格删最后一个字母
- **前提**：B0。
- **操作**：点 n i h a o，点 ⌫。
- **预期**：打字状态里剩 `niha`，候选跟着变。
- **JVM**：`PinyinUatTest.backspaceDeletesTheLastLetter`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-06 [P1] ' 分开音节
- **前提**：B0。
- **操作**：点 x i，在 z 键上向下滑打出 `'`，再点 a n。
- **预期**：显示 `xi'an`，候选第一个是「西安」。
- **JVM**：`PinyinUatTest.anApostropheSeparatesSyllables`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-07 [P0] 简拼
- **前提**：B0。
- **操作**：点 z g；点 ⌫ 两下；再点 w s m。
- **预期**：`zg` 的候选第一页有「中国」；`wsm` 的候选第一页有「为什么」。
- **JVM**：`PinyinUatTest.initials`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-09 [P1] 全拼打过两次的词，简拼下排到第三，第一个不变
<!-- learning: U-20 D32 -->
- **前提**：B0。先打「他」，点 f h，看候选前四个里没有「奉化」，点 ⌫ 删掉拼音。
- **操作**：打「他」，点 f e n g h u a 选「奉化」，点 **。**；再做一次；打「他」，点 f h；点 ⌫ 删掉拼音，点 f e n g h；点 ⌫ 删掉拼音，打「他」，点 s h o u j i a 选「售价」，点 **。**，再做一次，打「他」，点 s h o u j。
- **预期**：「奉化」在第三，第一个、第二个和开始时一样（docs/pinyin.md「完整拼音和简拼」）；fengh 下「奉化」也在第三；shouj 下是「手机、收集、售价、📱」（表情候选开着：表情仍出，排在提到第三的词后面，docs/pinyin.md「表情候选」）。在 `fh` 下再选两次，它第一。
- **操作**：打「他」，点 d i a n l e 选「点了」，点 **。**，再做一次；打「他」，点 d l。
- **预期**：「点了」在前三，第一个仍是「带来」。在 `zd` 下选过两次「知道」后，全拼打两次「指导」：`zd` 第一个仍是「知道」，「指导」在前三。
- **JVM**：`PinyinUatTest.aWordTypedTwiceInFullGoesThirdUnderItsInitials`、`aWordTypedTwiceInFullGoesThirdUnderPartsOfItToo`（fengh 第三、shouj 的表情排在后面）、`aWordUsedUnderTheInitialsStaysFirstThere`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-09c [P1] 有固定的词时，提上来的词往后挪一位
<!-- learning: U-20 D32 -->
- **前提**：B0。打「他」，点 f e n g h u a 选「奉化」，点 **。**；再做一次；打「他」，点 f h，看「奉化」在第三，记下第一个 A、第二个 B、第五个 C。
- **操作**：长按第五个候选 C，选 **固定首位**；点 ⌫ 删光；打「他」，点 f h。
- **预期**：前四个依次是 C、A、B、「奉化」：固定的排第一，其余各往后一位，「奉化」第四。
- **JVM**：`PinyinUatTest.aPinGoesFirstAndTheLiftedWordAPlaceDown`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-09d [P1] 「为什么排在这」写出完整拼音和简拼互相带的原因
<!-- learning: U-20 D32 U-81 -->
- **前提**：B0。
- **操作**：打「他」，点 f e n g h u a 选「奉化」，点 **。**；再做一次；打「他」，点 f h，长按「奉化」，选 **为什么排在这**；关掉；长按第一个候选，选 **为什么排在这**。点 ⌫ 删光。打「他」，点 d l，选「点了」（展开候选往后翻），点 **。**；打「他」，点 d i a n l，长按「点了」，选 **为什么排在这**。
- **预期**：`fh` 下的「奉化」写着"这种键盘上打全时常选它"，第一个候选不是这句；`dianl` 下的「点了」写着"在简拼下改选过它"。
- **JVM**：`PinyinUatTest.whyItIsHereTellsTheLiftAndTheFloor`（理由的文字）（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-09b [P1] 简拼下改选过的词，完整拼音下不低于第二
<!-- learning: U-20 D32 -->
- **前提**：B0，没有前文。点 d i a n l，看「点了」不在前两个（新设备上它在第一页之后），点 ⌫ 删光。
- **操作**：打「他」，点 d l，选「点了」；清空，点 d i a n l，看候选；删掉，点 d i a n l e。
- **预期**：两次「点了」都在前两个（`dianle` 下它新设备上本来就在前两个，那半只看没有变坏；`dianl` 那半才看得出这条规则）。过了 30 个打字日、没在 `dl` 下用过它，`dianl` 的前两个又没有它。
- **JVM**：`PinyinUatTest.aWordCorrectedToUnderTheInitialsIsNoLowerThanSecondInFullTillItExpires`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-77b [P1] 滑行打过两次的词，简拼下排到第三
<!-- learning: U-20 D32 -->
- **前提**：B0，**通用输入** 的 **在键上划** 里选 **滑行输入**。点 f h，看前五个里没有「奉化」，点 ⌫ 删光。
- **操作**：打「他」，滑 f-e-n-g-h-u-a，选「奉化」，点 **。**；再做一次；打「他」，点 f h。
- **预期**：「奉化」在前三（滑行算全拼的完整拼音）。
- **JVM**：`PinyinUatTest.aWordGlidedTwiceGoesThirdUnderItsInitials`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-10 [P0] 按上下文选词
- **前提**：B0，「普通文本」框里已有「昨天晚上下了一场」（用拼音打出来），光标在末尾。
- **操作**：点 d a x u e。
- **预期**：候选第一个是「大雪」，不是「大学」。（框里为空时打 daxue，第一个是「大学」。）
- **JVM**：`PinyinUatTest.theWordsBeforeChooseTheWord`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-11 [P1] 聊天框发出去后仍有上下文
- **前提**：B0，点「聊天」框，打出「昨天晚上下了一场」并点回车发出（框被清空）。
- **操作**：点 d a x u e。
- **预期**：候选第一个是「大雪」。
- **JVM**：`PinyinUatTest.aChatsBoxSentStillGivesTheWordsBefore`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-12 [P1] 性能档位：低档也能正常打字
- **前提**：B0-设置，**通用输入** 里 **性能档位** 选"低档"。
- **操作**：回测试页点 w o m e n y i q i q u g o n g y u a n，点空格。
- **预期**：能打出一个通顺的整句（例如「我们一起去公园」）；打字没有卡顿。
- **JVM**：`PinyinUatTest.theLowLevelStillTypesASentence`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 纠错

### PY-14 [P0] 纠错：打错的拼音照样出词
- **前提**：B0。
- **操作**：点 z h a g n（gn 当成 ng），看候选，点 ⌫ 删光；点 z h o n（ong 漏了 g），看候选，删光；点 j v（jv 当成 ju），看候选，删光；点 n i h s o（把 a 按成了旁边的 s），看候选。
- **预期**：zhagn 的候选第一页有「张」「章」；zhon 的有「中」；jv 的有「句」；nihso 的有「你好」。
- **JVM**：`PinyinUatTest.pinyinTypedWrongStillGivesTheWord`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-18 [P1] 常读错的词按错的读音打，候选上方标出正确读音
- **前提**：B0。
- **操作**：点 j i a o s e；清空，点 x x x u e z（简拼加半个字）。
- **预期**：候选里有「角色」，字的上方一字一音标着 jué sè；第二次候选里有「莘莘学子」的话，上方标着 shēn shēn xué zǐ。
- **JVM**：`PinyinUatTest.aWordOftenMisreadShowsItsReading`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-19 [P1] 开关关掉生效：纠错、中英混输、联想、表情候选、拆字
- **前提**：B0-设置。
- **操作**：每一步先到设置里关掉一个开关，再回测试页「普通文本」框打字看，删光后做下一步：
  1. **通用输入** 里关掉 **纠正错拼和按偏的键**；点 z h a g n。
  2. 拼音设置里关掉 **中英混输**；点 i p h o n e。
  3. **通用输入** 里关掉 **联想下一个词**；打 j i n t i a n 空格。
  4. **通用输入** 里关掉 **表情候选**；点 h a h a。
  5. 拼音设置里关掉 **拆字**；点 u n i u n i u n i u。
- **预期**：1. 候选第一页没有「张」「章」；2. 候选里没有「iPhone」；3. 上屏后栏上是工具栏，没有联想词；4. 候选里没有表情；5. 不出「犇」。
- **JVM**：`PinyinUatTest.eachSwitchTurnedOffTakesItsCandidates`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 模糊音、中英混输、笔画

### PY-20 [P1] 模糊音 z↔zh
- **前提**：B0-设置，**通用输入** 的 **模糊音** 里勾选 z ↔ zh。
- **操作**：回测试页点 z o n g g u o。
- **预期**：候选第一页有「中国」。
- **JVM**：`PinyinUatTest.fuzzyZZh`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-21 [P1] 模糊音默认全关
- **前提**：B0。
- **操作**：点 z o n g g u o。
- **预期**：候选第一页没有「中国」。
- **JVM**：`PinyinUatTest.fuzzyOffByDefault`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-22 [P0] 中英混输
- **前提**：B0。
- **操作**：点 w o d e i p h o n e，点空格。
- **预期**：框里是「我的iPhone」。
- **JVM**：`PinyinUatTest.chineseAndEnglishMixed`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-24 [P1] u 开头按笔画查字
- **前提**：B0。
- **操作**：点 u h s p。
- **预期**：候选里有「才」「本」「村」等，字的上方标着带声调的读音。
- **JVM**：`PinyinUatTest.uThenStrokes`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 联想

### PY-26 [P0] 上屏后联想下一个词
- **前提**：B0。
- **操作**：点 j i n t i a n，点空格；看候选栏；点联想里的第一个词。
- **预期**：「今天」上屏后候选栏接着给出联想词（例如「天气」「晚上」）；点了以后那个词上屏。
- **JVM**：`PinyinUatTest.wordsThatMayFollowAfterACommit`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-27 [P1] 直接打字联想消失
- **前提**：B0，打出「今天」，候选栏显示联想。
- **操作**：点 h。
- **预期**：联想消失，候选换成 h 的候选。
- **JVM**：`PinyinUatTest.typingPutsTheWordsThatMayFollowAway`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 长按候选

### PY-29 [P0] 屏蔽一个词
<!-- learning: U-82 -->
- **前提**：B0。
- **操作**：点 s h i，长按候选里的「是」，选 **屏蔽**，看确认框，确认；清空，再点 s h i。
- **预期**：确认框写明范围（以后打字、手写、语音、联想都不再出现「是」）；之后候选里没有「是」。（测完在 **词库与短语 → 用户词库 → 屏蔽的词** 里恢复，或重置。）
- **JVM**：`CandidateMenuUatTest.blockingAWordSaysWhereAndAsksFirst`（菜单、确认框写明范围（R.string.learning_block_everywhere）、确认后发出屏蔽；之后候选里没有「是」是引擎的事（学习的测试））；设备上不跑。

### PY-114 [P0] 屏蔽一个整句候选，组成它的字照常出
<!-- learning: U-82 -->
- **前提**：B0。
- **操作**：点 a z h i，找到几个字拼成的候选「啊之」（不是词库里的一个词），长按，看菜单；选 **屏蔽**，确认；清空，再点 a z h i；再分别点 a、z h i；然后在 **词库与短语 → 用户词库 → 屏蔽的词** 里恢复「啊之」，再点 a z h i。
- **预期**：菜单里有 **屏蔽**；屏蔽后 a z h i 下没有「啊之」，a 下有「啊」，z h i 下有「之」；屏蔽的词里列着「啊之 a zhi」；恢复后 a z h i 下又有「啊之」。双拼、九键打同样的拼音一样。
- **JVM**：`EnginesUserWordsTest.aSentenceBlockedFromTheKeyboardIsNeverOfferedAgainTillUnblocked`（菜单有屏蔽，屏蔽、撤销、再屏蔽，组成它的字照常出，存进用户目录，在设置里恢复后又出）、`aSentenceIsBlockedOnEveryPinyinKeyboard`（双拼、九键）、`aSentenceIsBlockedAtItsReadingAlone`（只屏蔽这个读音）；菜单写成的字见 PY-29。设备上跑过（2026-11-08，emulator-5574）。

### PY-115 [P1] 表情、拆字、笔画查字、滑行的其他读法也能屏蔽
<!-- learning: U-82 U-28 -->
- **前提**：B0；表情候选、拆字开着；滑行那一步先打开 **滑行输入**。
- **操作**：点 d i a n z a n，长按候选里的表情 👍；点 u n v z i，长按「好」；点 u h s 后长按查出的一个字；打开滑行，划 n-i-h-a-o，长按第二个候选（另一种读法的词）。每个都选 **屏蔽**、确认，再打一次同样的键看；表情屏蔽后重启输入法（杀掉进程再弹出键盘），再点 d i a n z a n；最后在 **屏蔽的词**（笔画查字的在页顶"码表里按码屏蔽的"里，写着"笔画查字"）里逐个恢复，再打一次。
- **预期**：四个菜单里都有 **屏蔽**；笔画查字的确认写"打这串码不再出现"，多打一笔、打拼音还出这个字；其余写"以后打字……都不再出现"。屏蔽后同样的键不再出它，表情重启输入法后也不出（👍🏻 这种码位比音节多的表情也一样），恢复后又出。
- **JVM**：`EnginesEmojiBlockTest`（码位和音节数对不上的表情：重启、设置里恢复、撤销、备份往返）、`AsideBlockUatTest.anEmojiAndACharacterByStrokesAreBlockedFromTheKeyboard`（:lib:ime-eval:uatTest，真数据，经宿主的长按动作）、`EnginesAsideBlockTest`（表情、拆字）、`EnginesStrokeBlockTest`、`EnginesGlideBlockTest`。设备上跑过（2026-11-08，emulator-5574）。

### PY-116 [P1] 繁體下长按候选能屏蔽
<!-- learning: U-82 -->
- **前提**：B0；在状态面板（⋯）里切到 **繁體**。
- **操作**：点 g u o j i a，长按「國家」，选 **屏蔽**、确认；清空再点 g u o j i a；切回 **简体** 再点一次。
- **预期**：菜单里有 **屏蔽**，能用；屏蔽后繁體下 guojia 不再出「國家」（词库里另有一个本来就是繁体的「國家」，显示一样，一起屏蔽），简体下不再出「国家」（屏蔽记的是原文「国家」）。
- **JVM**：`EnginesConvertedMenuTest`（菜单、屏蔽、撤销按引擎里的简体做，显示一样的候选一起屏蔽、删除，固定只管按的那个；宿主经 `HostCore.shownText` 把转换函数传给 `Engines.learning`）。设备上跑过（2026-11-08，emulator-5574；修之前菜单里没有屏蔽）。

### PY-31 [P1] 固定首位，再取消固定
<!-- learning: U-83 -->
- **前提**：B0。
- **操作**：点 s h i，长按第五个候选 C，选 **固定首位**；清空，再点 s h i；再在框里先打「他」，点 s h i；固定着时打开 **自定义短语** 看。然后回测试页点 s h i，长按排第一的 C，选 **取消固定**；清空，再打 s h i。
- **预期**：固定后两次 C 都排第一（固定不看前文），原来的第一个排第二，其余照原来的顺序往后挪一位；固定着时 **自定义短语** 里没有新增的一条。取消固定后 C 不再在第一位，第一个是原来的第一个（C 只用过固定前后那一次以内，不够换第一）。
- **JVM**：`PinyinUatTest.pinnedFirstThenUnpinned`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-32 [P1] 忘掉学到的
<!-- learning: U-82 U-84 -->
- **前提**：B0。打「他」，点 d l 选「点了」；接着打「他」，点 d l 选「点了」；接着打「他」，点 d l，确认第一个是「点了」。
- **操作**：长按「点了」，选 **忘掉学到的**；点 ⌫ 删掉拼音，再点 d l。
- **预期**：第一个回到「带来」，「点了」回到没选过时的位置（不在第二）。
- **JVM**：`PinyinUatTest.forgettingWhatWasLearned`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-33 [P1] 长按候选的菜单只列适用的项
<!-- learning: U-82 -->
- **前提**：B0。
- **操作**：点 s h i，长按一个从没选过的字，看菜单；点空白处关菜单，点这个字上屏；再点 s h i，长按同一个字。
- **预期**：第一次菜单里有 **屏蔽**、**固定首位**、**为什么排在这**，没有 **忘掉学到的**（还没学到什么）、**删除**（只对你的词、导入的词）和 **取消固定**；第二次多了 **忘掉学到的**。
- **JVM**：`KeyboardHalvesUatTest.theMenuListsOnlyWhatApplies`（uatTest，真数据：没选过的字菜单有屏蔽、固定首位、为什么排在这，没有忘掉学到的、删除、取消固定；选过一次后多了忘掉学到的）、`CandidateMenuUatTest.theMenuShowsTheRowsTheEngineGivesAndForgetOnceLearned`（菜单按引擎给的项写成这些字）；设备上不跑。

## 学词

### PY-34 [P0] 一段段选出的名字，上屏两次成为你的词，也进简拼
<!-- learning: U-06 U-60 U-20 U-43 -->
- **前提**：B0，输入框清空。点 w a n g x i a o m i n g，第一个是「王小明」；点 ⌫ 删光拼音，点 **。**，点 w x m，记下第一个候选，点 ⌫ 删光。
- **操作**：在候选里依次选「汪」「小明」（上屏「汪小明」），点 **。**；点 w a n g x i a o m i n g 看候选，再依次选「汪」「小明」，点 **。**；再点 w a n g x i a o m i n g 看候选；点 ⌫ 删光拼音，点 **。**，点 w x m。
- **预期**：第二次打时「汪小明」排第二（「王小明」第一）；第三次「汪小明」排第一。wxm 的第一个是它自己的词（和前提里记下的一样，不是「汪小明」「汪」「小明」），「汪小明」紧跟其后排第二。中间不要用 ⌫ 删上屏的字（马上删掉算撤回，见 PY-64）。
- **JVM**：`PinyinUatTest.aNamePickedPieceByPieceTwiceIsAWordAndInItsInitials`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-37 [P1] 粗俗词默认不在第一页、不在联想
- **前提**：B0。
- **操作**：点 c a o，看候选第一页。
- **预期**：第一页没有粗俗词（它们在后面的页里）。
- **JVM**：`PinyinUatTest.vulgarWordsAreNotOnTheFirstPage`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 改拼音

### PY-39 [P0] 点拼音那一行，在中间补一个漏打的字母
- **前提**：B0。
- **操作**：点 n i s h i w o z u i x i h u n d e（漏了 huan 的 a）；点拼音那一行里 `hun` 的 u 和 n 之间；点 a；点空格。
- **预期**：点完后拼音行里 u 和 n 之间出现一条竖线光标；补上 a 后拼音变成 `... xi huan de`，候选第一个是「你是我最喜欢的」；空格后框里是「你是我最喜欢的」。
- **JVM**：`HostCasesUatTest.aLetterMissedIsTypedWhereTheRowIsTapped`（点 hun 的 u 和 n 之间出光标，补 a 后 xi huan de、第一个「你是我最喜欢的」，空格上屏）；设备上不跑。

### PY-40 [P1] 在拼音中间退格
- **前提**：B0。
- **操作**：点 n i s h i w o z u i x i h u a n d e；点拼音行里 `shi` 的 i 后面；点 ⌫ 三下；点空格。
- **预期**：`shi` 被删掉，拼音是 `ni wo zui xi huan de`，光标在 ni 后面；框里是「你我最喜欢的」。
- **JVM**：`HostCasesUatTest.backspaceInTheMiddleOfThePinyin`（点 shi 后退格三下：拼音 ni wo zui xi huan de、光标在 ni 后，空格「你我最喜欢的」）；设备上不跑。

### PY-41 [P1] 空格键左右滑动移动拼音里的光标，长拼音跟着滚动
- **前提**：B0，**按键 → 划动空格键以移动光标** 开着（默认）；先把 **长按空格** 设为"无"（它默认是语音输入，长按后手指归语音）。
- **操作**：点一串很长的拼音，例如 w o x i a n g z h i d a o j i n t i a n w a n s h a n g w o m e n q u n a l i c h i f a n；按住空格键不松手，用约 4 秒、很慢地（每秒不到一个键宽）往左拖大约三分之一个屏宽。
- **预期**：开始拖以后（哪怕过了长按的时间才动）拼音行里的光标一格一格往前移，不弹出长按菜单，框里的光标不动；光标走到行的左边缘外时，这一行自动往右滚，光标一直看得见。用手指在拼音行上左右拖，也能滚动这一行。
- **JVM**：`KeyboardUatTest.aSlowSpaceDragMovesThePinyinCursorAndTheRowFollowsIt`（空格慢拖（过了长按时间才动）发 ←、不弹长按、框里不动，拼音行随引擎的光标滚动，手指拖行也滚；光标在拼音里怎么走是引擎的（HostCasesUatTest））；设备上不跑。

### PY-42 [P1] 已选的字前面放不了光标，退格把它退回拼音
- **前提**：B0。
- **操作**：点 n i h a o，在候选里点单字「你」（拼音行变成 `你hao`）；点拼音行里的「你」；点 ⌫。
- **预期**：点「你」后光标停在「你」后面；退格后「你」退回拼音，拼音行是 `n hao`（ni 的 i 被删了）。
- **JVM**：`HostCasesUatTest.aCharacterTakenHasNoCursorBeforeItAndBackspaceGivesItBack`（点「你」光标停在它后面，退格得 n hao）；设备上不跑。

### PY-43 [P1] 笔画查字里改笔画
- **前提**：B0。
- **操作**：点 u h p（漏了第二笔竖 s）；点拼音行里 `一` 和 `丿` 之间；点 s。
- **预期**：点完后 `一` 后面出现竖线光标；补上 s 后这一行是 `u一丨丿`，候选里有「才」「本」「村」等。
- **JVM**：`HostCasesUatTest.aStrokeMissedIsTypedWhereTheRowIsTapped`（点「一」后补 s：u一丨丿，候选有才、本、村）；设备上不跑。

## 拆字、表情候选、自带短语、滑行

### PY-44 [P1] 拆字：u 后打各部分的拼音
- **前提**：B0。
- **操作**：点 u n i u n i u n i u；看候选；点 ⌫ 删光，点 u j i n j i n j i n。
- **预期**：第一串的候选最前面是「犇」，上方标着读音；第二串最前面是「鑫」。
- **JVM**：`PinyinUatTest.chaiziByTheParts`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-45 [P1] 拆字开着时，不是部件的照常当拼音
- **前提**：B0。
- **操作**：点 u s b，看候选。
- **预期**：usb 照常当拼音/英文打，没有拆出的字。（关掉拆字由 PY-19 管。）
- **JVM**：`PinyinUatTest.notPartsArePinyinAsEver`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-46 [P1] 表情候选
- **前提**：B0。
- **操作**：点 d i a n z a n，看候选；点「👍」。
- **预期**：第三个候选起有「👍」（第一个是文字）；点后框里是「👍」；再打 dianzan，👍 没有被学成排在第一。
- **JVM**：`PinyinUatTest.anEmojiCandidateIsNotLearnedFirst`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-48 [P1] 自带的日期、时间、星期
- **前提**：B0。
- **操作**：点 r q，看候选；点空格；再点 s j 看候选，点 ⌫ 删光；点 x q 看候选。
- **预期**：rq 的第一个候选是「日期」，后面是今天的日期（如「2026年10月17日」「2026-10-17」「二〇二六年十月十七日」）；空格后框里是「日期」；sj 第二个起是现在的时间（如「09:05」「09时05分」）；xq 第二个起是「星期六」「周六」这样的今天的星期。
- **JVM**：`PinyinUatTest.theDateTimeAndWeekdayBuiltIn`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-49 [P1] 删掉一条自带短语，在自定义短语里能再打开
- **前提**：B0。
- **操作**：点 r q，长按「2026-10-17」那样的日期候选选 **从自定义词组中删除**；点 ⌫ 删光再点 r q；去设置的自定义短语里看 `rq`，打开那一条，回测试页再点 r q。
- **预期**：删后 rq 不再出这一条（另两条日期还在）；自定义短语里它是一条关闭的短语；打开后又出现。
- **JVM**：`PinyinUatTest.aBuiltInPhraseDeletedIsOneTurnedOffInTheCustomPhrases`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-50 [P1] 全拼滑行
- **前提**：B0-设置，**通用输入** 的 **在键上划** 里选 **滑行输入**（默认关），回测试页。
- **操作**：手指按在 n 上，不抬手依次划过 i、h、a、o，在 o 上抬起；看候选；点 ⌫ 删光，再划 w-o，接着划 m-e-n，看候选，删光；再划 n-i-h-a-o，点第一个候选后面那个另一种读法的词（例如「鸟」）。再把 **候选词注音** 选「拼音」，划 y-i-n-h-a-n-g，看「银行」上方。
- **预期**：划的时候画出线；抬手后拼音行是 `ni'hao`（滑行的拼音用 ' 分开，见 docs/pinyin.md），候选第一个是「你好」，后面跟着另外读法的词（如「鸟」）；划 wo 再划 men 后候选有「我们」；点另一种读法的词后框里是那个词（例如「鸟」），拼音行清空。划 yinhang 后「银行」上方是 yín háng（按划出的读音，不是 yín xíng），后面另外读法的词按它们自己的字注音。
- **JVM**：`PinyinUatTest.pinyinGlide`（滑出的拼音和候选、点另一读法，真数据）、`GlideSessionTest.readingsAndLookupsAreTheSessionsShiftedPastTheOthers`（滑行的注音按所打读音，插进来的另外读法按字）、`GlideUatTest.pinyinsGlideIsDrawnAsTheFingerGoes`（划的时候画出线，Roborazzi 金图）；设备上不跑。

### PY-52 [P1] 关掉自带的日期时间
- **前提**：B0-设置。
- **操作**：进 **通用输入** 关掉 **自带的日期时间**；回测试页点 r q 看候选，点 ⌫ 删光；点 x q 看候选，点 ⌫ 删光；打 r i q i 看候选，删光。回设置打开 **自带的日期时间**，再点 r q。
- **预期**：关掉时 rq 没有日期、xq 没有星期（这两条才说明开关起作用），riqi 有「日期」但后面没有今天的日期（按词触发的也关了；双拼、九键、码表走同一个开关）；打开后 rq 又有今天的日期（见 docs/words.md）。
- **JVM**：`PinyinUatTest.theBuiltInDatesTurnedOff`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 学习的规则

这一节每条对应 docs/pinyin.md「输入法怎样学你的词」的一条规则，用到的词在 `lib/ime-eval/data/learn-ux/` 的场景里按真实数据核过。写法：

- "打「X」""接着打「X」"：用拼音打出 X 上屏，不删前面的字；X 就是接下来的前文。
- "点 **。**"：句号后面算没有前文，用它从"没有前文"重新开始，也把前后两次上屏隔开。
- 除了 PY-64，不要用 ⌫ 删掉上屏的字：上屏后马上删掉算撤回，删掉的不学。

### PY-54 [P0] 同一个前文后面，选一次排第二，连着选两次排第一
<!-- learning: U-02 U-10 -->
- **前提**：B0。
- **操作**：打「他」，点 d l 看候选，选「点了」；接着打「他」，点 d l 看候选，选「点了」；接着打「他」，点 d l。
- **预期**：第一次第一个是「带来」；第二次是「带来、点了」（选一次只排到前面）；第三次是「点了、带来」。
- **JVM**：`PinyinUatTest.afterTheSameWordsPickedOnceIsSecondTwiceFirst`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-64 [P1] 选错了马上删，删掉的不算，重打的算改选
<!-- learning: U-03 D2 -->
- **前提**：B0。
- **操作**：打「他」；点 d l 选「带领」；点 m a 点空格（上屏「吗」）；点 ⌫ 三下删掉「带领吗」；点 d i a n l e m a 选「点了吗」，点 **。**；打「他」，点 d l 看候选，选「点了」，点 **。**；打「他」，点 d l。
- **预期**：第一次「他 dl」第一个是「带来」，「点了」在「带领」前面（「带领」被删掉不算用过，重打的「点了吗」算在 dl 下改选了一次「点了」）；再选一次「点了」后，它排第一（和重打那次连着两次）。（不用「给」后的「打了」：词库现在在「给」后面就把「打了」排第一，选它不算改选。）
- **JVM**：`HostReplayUatTest.aSentenceTypedBeforeIsNotToldAfterTheAppsOwnCommits`（整条操作；回放和设备一样首选「带来」（上屏后不告诉引擎前文，dev/design/fcitx-s3-report.md））；设备上不跑。

### PY-68 [P1] 删除、屏蔽、固定都能当场撤销
<!-- learning: U-82 -->
- **前提**：B0。
- **操作**：点 s h i，长按「是」选 **屏蔽**，确认；马上点键盘上方提示里的 **撤销**；点 ⌫ 删掉拼音，再点 s h i。再长按「是」选 **屏蔽**、确认，这次不点撤销，接着点 a。
- **预期**：选 **屏蔽** 后，**确定屏蔽** 不在刚才点的那一行（中间隔着 **取消**）；屏蔽后键盘上方（候选栏和拼音行以上，不盖候选）出现一条圆角提示"已屏蔽「是」"，右边是 **撤销**；撤销后候选里又有「是」，**屏蔽的词** 里没有它。第二次点了 a，提示马上收起（不等 6 秒）。
- **JVM**：`CandidateMenuUatTest.aBlockIsTakenBackOnTheSpot`（确定屏蔽不在屏蔽那一行（中间隔着取消）；提示「已屏蔽「是」」带撤销，撤销发出 Undo；再打字提示马上收起；图）；设备上不跑。

### PY-69 [P1] 为什么排在这
<!-- learning: U-81 D26 -->
- **前提**：B0。打「他」，点 d l 选「点了」；接着打「他」，点 d l 选「点了」；接着打「他」，点 d l，候选显示着「点了、带来」。
- **操作**：长按「点了」选 **为什么排在这**；点键盘上菜单外的地方关掉菜单（这一下只关菜单，不打字；不点键盘上面的网页空白，那一下归应用，网页会让框失去焦点），再长按「带来」选同一项；点里面的复制给开发者，到「长文本」框粘贴看。
- **预期**：「点了」写的是"在这个前文后面连着选过两次"；「带来」写的是"最近在这串键下用过 N 次"或"词库自带的顺序"；复制出的内容有 dl 的候选和次数，没有「他」这个前文的字，也没有别的词。
- **JVM**：`CandidateMenuUatTest.whyItIsThereAndTheReportCopied`（「为什么排在这」按引擎给的理由写；复制给开发者把引擎的报告放上剪贴板。报告里有什么是引擎的事）；设备上不跑。

### PY-72 [P1] 不再联想（前文是模型切出的最后一词：整词选的「你好」可能记在「好」后面）
<!-- learning: U-72 -->
- **前提**：B0。点 n i h a o 空格，记下联想里一个词 B。
- **操作**：长按 B，选 **不再联想**；点 **。**，再点 n i h a o 空格；点 **。**，点 B 的拼音看候选；打开 **用户词库** 的"屏蔽的词"，点页顶那一行。
- **预期**：「你好」后面的联想里不再有 B；打 B 的拼音时 B 照常在候选里；"屏蔽的词"页顶有"不再联想的搭配：1 个 ›"，点进去列着这一对，记在模型切出的最后一词下面：「你好」没被切开时是 你好 → B，列表写「不再跟在「你好」后面联想」；被切成 你|好 时是 好 → B，列表写「不再跟在「好」后面联想」。两种切法都算对，点一下能恢复；"不再联想的搭配"在用户词库里紧挨着"屏蔽的词"。
  因为选过又出现的联想（整词打出后选过、已掉出联想前 30 的）再关掉，手工难造，由单元测试 `PredictionOrderTest.aWordPutBackAfterTheWholeWordIsTurnedOffAfterIt` 管。
- **JVM**：`PinyinUatTest.aWordNoLongerOfferedAfterAnother`（:lib:ime-eval:uatTest，真数据：联想里没了、照常能打、记下了一对）、`SettingsPagesUatTest.aPredictionTurnedOffListedUnderBlockedAndTakenBack`（"屏蔽的词"页顶"不再联想的搭配：1 个 ›"，点进去写「不再跟在「你好」后面联想」，点一下恢复；用户词库里紧挨着"屏蔽的词"）；设备上不跑。

### PY-73 [P1] 框里原来就有的字也算前文
<!-- learning: U-50 -->
- **前提**：B0。在「句子」框打「他」，全选、复制；回「普通文本」框，长按粘贴「他」（粘贴不经过打字）。
- **操作**：点 d l 选「点了」；在框末尾再粘贴一次「他」，点 d l 选「点了」；再粘贴一次「他」，点 d l。
- **预期**：第一个是「点了」（粘贴进来的「他」和打出的一样当前文）。
- **JVM**：`PinyinUatTest.textAlreadyInTheFieldIsTheWordsBefore`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-75 [P1] 聊天框发出消息后，学习算没有前文
<!-- learning: U-53 -->
- **前提**：B0，点「聊天」框。在框里点 d l 点空格上屏「带来」，点 **。**；这样做三次（「带来」用过，后面选两次的「点了」才不会按第 3 条到处排第一，见 docs/pinyin.md「候选怎么排」）。先不发出。
- **操作**：接着打「他」，点回车，连同前面的字一起发出；点 d l 选「点了」，发出；打「他」，发出；点 d l 选「点了」，发出；回到空的「普通文本」框打「他」，点 d l。
- **预期**：「普通文本」里「他 dl」的第一个是「带来」：发出消息后接着打的不算在「他」后面（两次「点了」都学在"没有前文"下）。
- **JVM**：`HostCasesUatTest.wordsSentFromAChatBoxAreLearnedWithNothingBefore`（整条操作，他 dl 第一个「带来」）；设备上不跑。


## 14 键

前提 **B0-14键**：B0，然后在 **输入法 → 拼音** 里把 **键盘** 选成「14 键」，回测试页点「普通文本」框（README「基线状态」）。下面「点 BN」指点键面是 BN 的那个键。

### PY-84 [P0] 14 键的样子
- **前提**：B0-14键。
- **操作**：看键盘。
- **预期**：三行字母键，满宽：`QW ER TY UI OP` / `AS DF GH JK L` / `分词 ZX CV BN M ⌫`；键面是大写字母；角标上两行依次是 1–0，第三行 ZX `～`、CV `！`、BN `@`、M `？`（和全键盘的角标一样，中文里是中文标点，所以讯飞的 `~ ! @ ?` 这里是全角；`@` 没有中文标点，照旧）。底排依次是 符、123、，、空格、。、🌐、↵，没有中/英。没有 ⇧。
- **JVM**：`GroupedKeyboardUatTest.theFourteenKeysAsIFlytekLaysThemOut`（三行满宽、键面大写、角标 1–0 和中文标点下的 ～ ！ @ ？、底排 符 123 ， 空格 。 🌐 ↵、没有中/英和 ⇧，金图 keys14_chinese）；设备上不跑。

### PY-85 [P0] 14 键打一个词
- **前提**：B0-14键。
- **操作**：点 BN UI GH AS OP，看候选；点「你好」。
- **预期**：第一页有「你好」，点了框里是「你好」。
- **注**：首选不是「你好」：同一串键也读 bu'hao、bu'gao，模型给「不好」「不高」的分高（log10 −4.45、−5.06，「你好」−5.15；和全拼打 buhao、bugao、nihao 时的分一样，14 键的路没有另加减分）。点在键的正中时首选是「不好」；点在各字母那一半见 PY-100。
- **JVM**：`Keys14UatTest.atTheKeysCentresTheFirstIsTheModelsAndYourIsOnThePage`（第一页有你好、点了上屏，真数据）；设备上不跑。

### PY-86 [P0] 编码行的音节条
- **前提**：B0-14键。
- **操作**：先看编码行；点 BN UI，看编码行右边；点音节条上的 ni，看候选；点退格；清空，点 ZX GH UI AS BN GH（zhuang 这一类读音多的键开头），再接着点 BN UI GH AS OP JK UI OP BN UI GH AS OP BN UI GH AS OP（二十多个键，比编码行左边那段放得下的长）。
- **预期**：没打字时没有音节条。点 BN UI 后编码行右边出现音节按钮，有 ni、bu 等，每个按钮至少 40dp 宽、和编码行一样高（32dp）；点 ni 后音节条往后挪，预编辑以 ni 开头，候选都读 ni 开头；点退格后 ni 退回成键（预编辑回到点 ni 之前的样子），音节条又出现 ni。ZX GH UI AS BN GH 开头时音节多，音节条不超过编码行宽度的 60%（放不下的左右滑着看）；打一长串时左边的预编辑仍然滚到光标那里，看得见最后打的读音。
- **JVM**：`GroupedKeyboardUatTest.theCodeRowsSyllablesBesideTheCodeTheirWidthsAndTheirCap`、`KeyboardHalvesUatTest.aSyllablePickedLeadsTheCodeAndBackspaceGivesItBack`（没打字无音节条；按钮 ≥40dp、32dp 高、在编码行右端，点按钮发音节键；音节多时占 55–61%；长预编辑滚到光标（键盘半边）。点 ni 预编辑以 ni 开头、音节条往后挪、候选不读 bu；退格回原样、ni 又在；zhuang 开头 ≥6 个音节、第一个是 zhuang（引擎半边，App 数据））；设备上不跑。

### PY-87 [P1] 分词键
- **前提**：B0-14键。
- **操作**：没打字时点分词键；清空，点 ZX UI 分词 AS BN，看候选；点空格。
- **预期**：没打字时框里是「‘’」（和全键盘的 `'`，即 Z 的角标，在中文里打出的一样）。之后预编辑是 `xi'an`（或分开写的 xi an），候选第一个是「西安」，框里是「西安」。
- **JVM**：`GroupedKeyboardUatTest.theSeparatorWithNothingTypedIsPairedQuotes`（分词键发 `'`，引擎上屏的 ‘ 由应用配成「‘’」）、`Keys14UatTest.theSeparatorWithNothingTypedIsAQuoteAndWhileTypingSplitsXian`（:lib:ime-eval:uatTest，真数据；没打字是 ‘，打字时 xi'an 第一个是西安）；设备上不跑。

### PY-88 [P1] 滑动出角标
- **前提**：B0-14键。
- **操作**：没打字时在 QW 上往下滑；在 BN 上往下滑；清空框，点 BN UI，在 ER 上往下滑。
- **预期**：框里先后多出 `1`、`@`；打字中滑 ER 选的是第 2 个候选（框里是 BN UI 打字时候选栏的第 2 个）。
- **JVM**：`GroupedKeyboardUatTest.aSwipeDownTypesTheCornerAndWhileTypingPicksThatCandidate`（下滑 QW、BN 送小键盘 1 和 @，打字中滑 ER 送小键盘 2）、`KeyboardHalvesUatTest.aCornersDigitTypesItselfOrPicksThatCandidateWhileTyping`（uatTest，真数据：框里 1@，打字中小键盘 2 上屏第 2 个候选）；设备上不跑。

### PY-89 [P1] 长按分组键
- **前提**：B0-14键。
- **操作**：长按 QW 看弹出的一排；松开不选（往下滑走）。点 BN UI，长按 QW，滑到 q 松开。
- **预期**：最下面一行是 `Q W 1 q w`，手指一开始在 1 上；上面几行是 q、w 在全键盘上长按出的符号（不含数字、字母和角标的 1）：q、w 长按只有数字和大写，所以 QW 上面没有别的行；长按 AS 时最下面一行是 `A S 6 a s`，上面有 `@` `*`（a、s 在全键盘上长按出的符号）。打字中选 q：框里是第一个候选，后面跟着 q。
- **JVM**：`GroupedKeyboardUatTest.theLongPressIsTheGroupsLettersAroundTheCornerWithTheLettersSymbolsAbove`（QW 只有 `Q W 1 q w`，原地松开打出 1；AS 最下 `A S 6 a s`、上面 @ *；打字中从长按选字母（测试选 w，和 q 同一路）：先 reset 再上字母）、`KeyboardHalvesUatTest.aLetterOfTheLongPressWhileTypingFollowsTheFirstCandidate`（uatTest，真数据：第一候选后跟字母）；设备上不跑。

### PY-90 [P1] 14 键和全键盘分开学，学成的词两边都有
<!-- learning: qk: 和全拼各学各的；你的词共用 -->
- **前提**：B0-14键，**通用输入** 的 **在键上划** 里选 **滑行输入**。
- **操作**：
  1. 点 AS GH UI（shi），记下全键盘上 s h i 的首选应是什么（没学之前，两边首选相同）；选候选里第一个以外的一个字 W，点 **。**；再来一次；第三次点 AS GH UI，看候选。
  2. 清空，点 QW AS BN GH ZX UI AS OP M UI BN GH（wangxiaoming），在候选里依次选「汪」「小明」；清空再来一次。
  3. 回 **输入法 → 拼音** 把 **键盘** 改成「全键盘」，回测试页先划 n-i-h-a-o 一次看候选，清空；打 s h i 看候选；清空，打 w x m 看候选；打开 **用户词库** 的"自动学到的词"。
- **预期**：第三次 AS GH UI 的第一个是 W；换到全键盘后第一次滑行就画出轨迹，「你好」在前几个候选里（14 键上屏后的第一下滑行不丢）；全键盘上 shi 的第一个不是 W（14 键上学到的顺序不改全键盘）。「汪小明」成了你的词：全键盘上 wxm 的第一页有它，"自动学到的词"里有它。
- **JVM**：`Keys14UatTest.theFourteenKeysLearnApartFromTheFullKeyboardAndYourWordsAreBoths`（14 键学到的首选不串到全键盘，换键盘后第一次滑行，汪小明两边都有、在自动学到的词里，真数据）；设备上不跑。

### PY-91 [P0] 14 键没有中/英，用 🌐 到英文再回来
- **前提**：B0-14键。
- **操作**：看底排；点 🌐（切到拼音后面的那个输入法，B0 里就是英文），看键盘和底排；一直点 🌐 直到回到拼音；点「密码」框看键盘。
- **预期**：底排没有中/英；🌐 后是英文全键盘，底排也没有中/英；回到拼音时又是 14 键；密码框里是英文全键盘，不是 14 键。
- **JVM**：`GroupedKeyboardUatTest.theGlobeGoesToTheEnglishKeyboardAndBack`、`GroupedKeyboardUatTest.aPasswordOnTheFourteenKeysIsEnglishsFullKeyboard`（🌐 到英文全键盘、回来是 14 键、密码框是英文全键盘，都没有中/英）、`GroupedKeyboardTest.theBottomRowHasNoChineseEnglishKeyAndTheLanguageSwitchFollowsItsSetting`；设备上不跑。

### PY-92 [P1] 读不出来的键原样上屏是字母
- **前提**：B0-14键。
- **操作**：点 UI，点回车；清空框，点 BN UI GH，点 🌐。
- **预期**：第一次框里是 `u`；第二次换走时打的内容按编码行上的读音留下（字母）。两种情况框里都没有私用区字符（键盘内部的码）。
- **JVM**：`Keys14UatTest.keysWithNoReadingCommitAsTheirLetters`（回车上屏 u，换走留下编码行读音的字母，无私用区字符）；设备上不跑。

### PY-93 [P0] 网址框、邮箱框里用 14 键
- **前提**：B0-14键，点「网址」框。
- **操作**：点 BN UI，再点 **.com**；到「邮箱」框点 BN UI，再点 **@**。
- **预期**：网址框里是字母加 `.com`，邮箱框里是字母加 `@`，都没有私用区字符。底排没有中/英，空格至少占键盘宽的 15%（`button_space` 节点的宽度除以 `keyboard_view` 的宽度）。
- **JVM**：`GroupedKeyboardUatTest.anAddressFieldsDotComAndAtGoInAsTheyAre`（网址框 .com、邮箱框 @ 原样进框，没有私用区字符，没有中/英）、`KeyboardHalvesUatTest.inAnAddressFieldTheKeysGoInAsLettersBeforeDotComAndAt`（uatTest，真数据：BN UI 以字母上屏再跟 .com / @）、`GroupedKeyboardTest.theSpaceIsAtLeast15PercentOfTheRowInEveryField`；设备上不跑。

### PY-94 [P1] 已学条目里的 14 键
- **前提**：B0-14键。点 AS GH UI，长按第五个候选选 **固定首位**。
- **操作**：打开 **用户词库** 的"固定的词"。
- **预期**：那一条写着「14 键」，按键显示成 `as gh ui`，不是键盘内部的码（私用区字符）。
- **JVM**：`WordsUatTest.aPinOnTheFourteenKeysIsListedUnderItsKeys`（:lib:ime-eval:uatTest，真数据：AS GH UI 上固定首位，记成 14 键的码）、`SettingsPagesUatTest.aPinOnTheFourteenKeysSaysSoAndShowsItsLetters`（"固定的词"那一条写「14 键」、按键 `as gh ui`）；设备上不跑。

### PY-95 [P1] 14 键上的自带短语和自定义短语
- **前提**：B0-14键。
- **操作**：点 ER QW（rq），看候选；清空，点 ER UI QW UI（riqi），看候选，选「日期」。
- **预期**：`rq` 的候选里有今天的日期（同 PY-48 的写法）；riqi 的候选里「日期」后面（第三个候选起）跟着今天的日期，选「日期」框里是「日期」。
- **JVM**：`Keys14UatTest.theBuiltInDatesUnderRqAndRiqi`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-96 [P1] TalkBack 读 14 键
- **前提**：B0-14键，打开 TalkBack（或用「无障碍检查」读键的说明）。
- **操作**：摸 QW 键、分词键；点 BN UI 后摸音节条上的 ni。
- **预期**：QW 读「Q W，1」；分词键读「分词」；音节按钮读「拼音 ni」，是按钮。

### PY-97 [P1] 定音节的顺序
- **前提**：B0-14键。
- **操作**：点 BN UI GH AS OP；在音节条上点 ni，再点 hao；点退格两次，每次后看预编辑；点预编辑里的 hao 那几个字母，看音节条；把光标移到中间后点退格；再在音节条上点 ni，把光标移到其后编码中间点退格。
- **预期**：先定 ni、再定 hao；第一次退格退回 hao，第二次退回 ni，都退回成键。点预编辑只移光标，音节条不变。光标不在末尾时退格删的是光标前的那个键，不退音节；定了 ni 后在其后编码里退格，ni 仍定着，只删光标前的键。
- **JVM**：`Keys14UatTest.syllablesTakenGoBackLastFirstAndATapInTheCodeOnlyMovesTheCursor`（定音节顺序、退格逐个退回、点预编辑只移光标、中间退格删键、定音节后在其后退格不退音节）；设备上不跑。

### PY-98 [P1] 14 键不滑行
- **前提**：B0-14键，**通用输入** 的 **在键上划** 里选 **滑行输入**。
- **操作**：回测试页，手指不离开屏幕，从 BN 划过 UI 到 GH 再松开。
- **预期**：键盘上没有滑行的轨迹，候选不是滑行猜出的词：滑动只按一个键的滑动处理（打出 BN 的角标 `@`，或者当作点了 BN）。
- **JVM**：`GroupedKeyboardUatTest.theFourteenKeysDoNotGlide`（滑行开着，从 BN 划过 UI 到 GH：没有 offerGlide，最多一个键的动作，金图 keys14_dragged 无轨迹）；设备上不跑。

### PY-100 [P0] 点在字母那一半，先出那个字母的读音
- **前提**：B0-14键。
- **操作**：打 nihao，每个字母都点在它那一半的中心：BN 右、UI 右、GH 右、AS 左、OP 左（O 是 OP 的左字母；键宽的 3/4 或 1/4 处）；其中 GH 那一下手指按下后挪 2 个像素再抬（一下带 MOVE 的点按，`input swipe x y x+2 y+1 60`）。
- **预期**：首选是「你好」，音节条第一个是 ni；带 MOVE 的那一下没有弹出长按的字母条。
- **注**：14 键按手指落下的位置给组里的两个字母加权，键的正中两个一样。引擎这一侧另由 uatTest 的 `KeyboardHalvesUatTest` 重放。
- **JVM**：`KeyboardHalvesUatTest.tappedOnTheLettersOfNihaoTheStripStartsWithNi`（uatTest，真数据：首选你好、音节条第一个 ni）；点位到码、挪几个像素仍是点按见 `GroupedKeyboardTapTest`；设备上不跑。

### PY-101 [P1] 点在另一半，先出另一个读音
- **前提**：B0-14键。
- **操作**：打 buhao：BN 左、UI 左、GH 右、AS 左、OP 左。
- **预期**：首选是「不好」，音节条第一个是 bu。
- **JVM**：`KeyboardHalvesUatTest.tappedOnTheLettersOfBuhaoTheStripStartsWithBu`（uatTest，真数据：首选不好、音节条第一个 bu）；设备上不跑。

### PY-102 [P1] 点在哪不进学习；快速输入里点 BN 是字母
- **前提**：B0-14键。
- **操作**：五个键都点在正中，看首选；点「你好」上屏。清空，按 PY-100 的点法打，看首选；清空。然后长按「符」进快速输入，点 BN 的右半边，再按回车。
- **预期**：点在正中时首选「不好」（和 PY-85 一样）。按 PY-100 的点法打，首选「你好」。快速输入里框里是 `n`，没有私用区的字（键盘内部的码）。
- **注**：学到的那一条（`qk:` 的码）和点在哪无关，同一串键点在正中和点在字母那一半，学到的行一样；这一条由 uatTest 的 `Keys14UatTest` 核对学习记录，设备上看不到逐字节的记录。第二段是在选过一次「你好」以后打的，不管点在哪首选都是「你好」，只说明同一串键找回了学到的词，不当作点按的证据（点按由 PY-100、PY-101 管）。每条从干净的学习状态开始（`clean`）。
- **JVM**：`Keys14UatTest.whereTheKeysWereTappedIsNotLearnedAndQuickInputGetsTheLetter`（正中首选不好、字母那一半首选你好、学习记录与点位无关、快速输入里 BN 是 n）；设备上不跑。

## 笔画筛选

前提里的 **笔画筛选**：在 **通用输入** 的 **在键上划** 里选 **笔画筛选**。一笔是在键盘上从按下到抬起划的一道：横向右（或向左），竖向下，撇向左下，点（捺）向右下，折是中途拐弯的一道；向上划不算。

### PY-103 [P1] 多笔按同一个字的笔顺筛：xi 画四笔出「曦」
- **前提**：B0，另外笔画筛选。
- **操作**：打 xi，看第一页；在键盘上划一竖，看编码行和第一页；再划折、横、横，看首选；点「曦」。
- **预期**：打完 xi，第一页没有「曦」。划一竖后编码行是 `xi 丨`，「曦」到了第一页。四笔后编码行是 `xi 丨乛一一`，首选「曦」。点了「曦」上屏，编码行清空。
- **JVM**：`StrokeFilterUatTest.xiThenFourStrokesOfXi`（uatTest，真数据，笔画按键盘发的码送进去）；怎么从一道划认出是哪一笔见 `StrokeShapeTest`，键盘把划的一道发出去见 KB-42；设备上不跑。

### PY-104 [P1] 一笔「丶」把「灏」提上来；⌫ 先删笔画再删拼音；没打字时划不起作用
- **前提**：B0，另外笔画筛选。
- **操作**：什么都没打时在键盘上划一道向右下的点；打 hao，看第一页；划一点，看第一页；按 ⌫，看编码行和候选；再按 ⌫。再打 hao，划一点，看首选，点「，」。接着打 woxiangqushanghai，划一横，看首选，点「，」。
- **预期**：没打字时划了什么都不发生，框里没有字（划的一道当成普通的按键处理，不会打出私用区的字）。打 hao 第一页没有「灏」；划一点后「好」「号」不见了，「灏」在第一页。按一次 ⌫ 编码行回到 `hao`，候选和划之前一样；再按一次是 `ha`。最后划一点后点「，」：框里是筛完的首选加「，」（比如「灏，」，不是「好，」），编码行空。woxiangqushanghai 划一横后首选是一横起笔的单字（如「握」，只读了 wo）；点「，」后编码行是这个字加剩下的拼音（`握xiang qu shang hai`），候选是剩下的，框里没有多出「，」。
- **JVM**：`StrokeFilterUatTest.haoThenADotThenBackspace`（uatTest，真数据，含点「，」，只读了一部分的首选选定、剩下的留着）、`StrokeFilterTest`（ime-core：会话不读的键先上屏筛完的首选）；没打字时划一道键盘发不发码见 KB-42；设备上不跑。

### PY-105 [P1] 拼音 18 键
- **前提**：B0，在 **输入法 → 拼音** 里把 **键盘** 选成「18 键」，回测试页点「普通文本」框。
- **操作**：看键盘；点 N（BN 键）I（IO 键）H（H 键）A（A 键）O（IO 键），看编码行和候选；点「你好」。
- **预期**：三行键：`Q WE RT Y U IO P` / `A SD FG H JK L;` / `分词 Z XC V BN M ⌫`（和双拼 18 键同一套分组，拼音的 L 键只写 L，长按也没有 `;` `:`；角标和别的键一样，打中文时显示中文标点，读屏读出 L 和角标；从 L 起笔滑行照样算在键上），键面大写；编码行 32dp，右边有音节条；候选第一页有「你好」，点了框里是「你好」，没有私用区字符。双拼的键盘不受影响，仍是自己选的那个。
- **JVM**：`ImKeyboardsUatTest.pinyinsEighteenKeysShowTheGroupsAndSendTheirOwnCodes`（键面是 G18 的分组，BN 发拼音 18 键自己的码不是双拼的，编码行 32dp，双拼仍是全键盘；图）、`GroupedKeyboardTest.talkBackReadsAGroupLetterByLetterWithItsCorner`（L 键也是分组键：角标、读屏、起笔）、`EnginesImKeyboardsTest`（ime-core：拼音 18 键的码进拼音、打出你好）；准确率见 dev/m2/i1-report.md（ime-eval `t9 --keys 18`）；设备上不跑。

### PY-106 [P1] 完整打过 3 次的一段话，打出开头就接着联想剩下的
<!-- learning: U-73 -->
- **前提**：B0。
- **操作**：在空框里打 n i h a o y a q i n a i d e 空格上屏「你好呀亲爱的」，清空框，重复共 3 次；每次之后打 n i 选「你」看联想。再打 n i h a o 选「你好」看联想，点第一个。长按「好呀亲爱的」选 **不再联想**，点提示上的 **撤销**；再选一次 **不再联想**，看"不再联想的搭配"，点它恢复；最后长按它选 **删除**，再整段上屏 3 次。
- **预期**：前两次之后「你」的联想里没有「好呀亲爱的」，第三次之后它排第一；「你好」后面第一个是「呀亲爱的」，点了框里是「你好呀亲爱的」，联想换成它后面的词。不再联想后不出，撤销后回来；列表里写着"整段：哪里都不再联想"，恢复后回来；删除后不出，再整段上屏 3 次又回来。
- **JVM**：`PhraseUatTest.aWholeSentenceTypedThreeTimesGoesOnAfterItsStart`、`PhraseUatTest.aPhraseTurnedOffOrDeletedIsNoLongerOffered`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### PY-107 [P1] 分几次上屏的不算一段
<!-- learning: U-73 -->
- **前提**：B0。
- **操作**：在空框里分别选「你」「好呀」「亲爱的」上屏，清空框，共 3 次；再打 n i 选「你」。
- **预期**：联想第一个是「好呀」（一个词一个词联想，和以前一样），没有「好呀亲爱的」这样的整段。
- **JVM**：`PhraseUatTest.piecesCommittedApartAreNoPhrase`；设备上不跑。

### PY-108 [P1] 不学的框不学整段，码表里也接着联想
<!-- learning: U-73, U-87 -->
- **前提**：B0。
- **操作**：在无痕标签页（不学习的框）里整段上屏「你好呀亲爱的」3 次；回普通框整段上屏 1 次，打 n i 选「你」；再整段上屏 2 次；切到五笔，打 w q i y 选「你」。
- **预期**：普通框只打过 1 次时「你」后面没有「好呀亲爱的」（无痕框里的不算）；五笔里「你」后面第一个是「好呀亲爱的」。网址、邮箱、数字框和搜索框的不学由单元测试 `PhrasesTest` 管（这几个框的标记是外层给的）。
- **JVM**：`PhraseUatTest.noPhraseWhereNothingIsLearnedAndOneInATable`；设备上不跑。

### PY-109 [P1] 注音全键盘（大千）：打声调，空格当一声，回车上屏注音
- **前提**：B0-注音全键盘。
- **操作**：看键盘；点 ㄋ ㄧ ˇ ㄏ ㄠ ˇ，看码行和候选，点「你好」；点 ㄇ ㄚ，按空格，看码行和候选，再按空格；点 ㄉ ㄜ ˙，看候选，按空格；点 ㄅ ˇ，按回车。
- **预期**：大千排列，第一行是 ㄅ ㄉ ˇ ˋ ㄓ ˊ ˙ ㄚ ㄞ ㄢ ㄦ；码行 `ㄋㄧˇ ㄏㄠˇ`，首选「你好」；ㄇㄚ 后按空格码行是 `ㄇㄚˉ`、首选「妈」，再按空格上屏「妈」；ㄉㄜ˙ 首选「的」；回车上屏 `ㄅˇ`。框里最后是「你好妈的ㄅˇ」。
- **JVM**：`ZhuyinKeyboardTest.dachenHasEachSymbolAndToneWhereTaiwanPrintsIt`、`PinyinSessionZhuyinTest`（`dachenTypesAWordByItsSymbolsShownInZhuyin`、`dachensSpaceIsTheFirstToneThenThePick`、`enterCommitsTheZhuyinShown`）、`PinyinUatTest.aNeutralToneTypedOrNotPutsTheWordFirst`（真数据：ㄓㄨㄛㄗ、ㄉㄨㄥㄒㄧ 打 ˙、打本调、不打声调，「桌子」「东西」都排第一，大千和注音九键都是）；设备上照跑。

### PY-110 [P1] 候选词注音一律标本调，不标轻声
- **前提**：B0，**候选词注音** 选「拼音」。
- **操作**：打 zhuozi，看「桌子」上方；清空，打 dongxi，看「东西」上方；清空，打 women，看「我们」上方。把 **候选词注音** 换成「注音」，再打一遍。
- **预期**：拼音是 zhuō zǐ、dōng xī、wǒ men；注音是 ㄓㄨㄛ ㄗˇ、ㄉㄨㄥ ㄒㄧ、ㄨㄛˇ ˙ㄇㄣ。轻声的字按它的本调标（子 zǐ、西 xī），只有本身只读轻声的字（的 了 吗 呢 们 么）标轻声（用户 2026-11-02 定）。注音键盘上照台湾习惯打 ˙（ㄗ˙、ㄒㄧ˙）不算错，「桌子」「东西」照样排第一，见注音键盘那条线的 PY-109。
- **JVM**：`PinyinUatTest.aReadingIsInItsCharactersOwnTones`（真数据，拼音和注音两种）、`ToneTableTest.aWordsNeutralToneIsItsCharactersOwn`（ime-core：读音本调，输入时 ˙ 和本调都不罚分）、`CandidateReadingsUatTest.portrait`/`landscape`（拼音和注音、20 和 28sp 的图，东西 标 dōng xī）；设备上不跑。

### PY-111 [P1] 读音提示标在字上方，候选栏不变高
- **前提**：B0，**候选词注音** 是「关闭」。
- **操作**：点 j i a o s e，看候选栏；清空，点 u n v z i；清空，把 **候选字号** 依次调成最小、最大，各打一次 j i a o s e；横屏再打一次；打开悬浮候选窗再打一次。字号调回默认，竖屏打 j i a o s e 点 ⌄ 展开候选网格看「角色」那格；把 **候选词注音** 选「拼音」再看一次，候选字号最大时再看一次。**候选词注音** 仍是「拼音」，清空，打 u c h i c h u（彳 chi、亍 chu 拆「行」），看「行」。
- **预期**：「角色」字的上方标着 jué sè，字体、颜色、居中和拼音注音一样，只是字小一些；它和旁边没有提示的候选字在同一条水平线上；候选栏的高度和不打字时一样，提示出现、消失时栏不跳。拆字出的「好」上方标 hǎo。字号最小时提示不小于 7sp，可能略碰到字顶。悬浮窗里每个候选一样高。码表的编码提示仍在候选右边。展开的网格里「角色」那格放得下上方的 jué sè，读音不截断、不和相邻格重叠（注音关、开拼音、字号最大三种都是）。开着注音时拆出的「行」有两个读音，它们作为提示留在候选右边（如 `xing/hang`），上方不画读音。
- **JVM**：`CandidateHintsUatTest`（栏高、和旁边候选的基线一样、悬浮窗候选同高；浅色深色、竖屏横屏、14/20/28sp 的图）、`EnginesReadingsTest.offShowsNoneButTheHintsAboveTheCandidates`（ime-core：笔画、拆字的提示变成读音）、`CandidateReadingsTest.aHintIsOneReadingACharacterWithItsTones`、`CandidateBarHeightTest.aHintsReadingFillsTheRoomBetweenItsBounds`；两个读音的提示开着注音也留在右边 `EnginesReadingsTest.aHintThatIsNoReadingStaysWithTheReadingsOn`；展开网格的格宽按画出来的量 `GridReadingWidthTest.aWordWithReadingsIsMeasuredAsItIsDrawnInEachStyle`；设备上不跑。

### PY-112 [P1] 注音全键盘的快捷键：按键位套用全键盘的
- **前提**：B0-注音全键盘，滑动是默认（上滑快捷键），测试页输入框里有字。
- **操作**：看键角；ㄇ 上滑；ㄋ 上滑。
- **预期**：ㄇ（a 的位置）、ㄏ（c）、ㄌ（x）、ㄒ（v）、ㄈ（z）、ㄗ（y）的左上角（快捷键图标位置默认）画着全选、复制、剪切、粘贴、撤销、重做的图标，别的键没有；ㄇ 上滑全选；ㄋ 没有快捷键，上滑打出 ㄋ。**按键 → 按键字符和快捷键** 的「注音全键盘」页能改。
- **JVM**：`ZhuyinShortcutsUatTest.dachensKeysTakeTheFullKeyboardsShortcutsByPlace`、`ZhuyinKeyboardTest.theSymbolKeysAreNamedForTheirShortcuts`；设备上不跑。

### PY-113 [P1] 多音字按所打的读音注音
- **前提**：B0，**候选词注音** 选「拼音」。
- **操作**：打 h a n g，看「行」上方；清空，在 **通用输入** 的 **在键上划** 里选 **笔画筛选**，打 h a n g 再在键盘上画一撇（「行」的第一笔），「行」留在候选里，看「行」上方；清空，把 **候选词注音** 换成「注音」，再打 h a n g。
- **预期**：三次「行」上方都是 háng（注音是 ㄏㄤˊ），从不是 xíng（ㄒㄧㄥˊ）。
- **JVM**：`EnginesReadingsTest.eachCandidateIsReadAsTyped`、`EnginesReadingsTest.aCharacterOfTwoReadingsIsReadAsTypedThroughFullPinyinsWrappers`（全拼和笔画查字、笔画筛选这些包着它的都按所打读音）、`StrokeFilterTest.readingsAndLookupsOfAKeptCandidateAreOfItsOwnPlace`；设备上不跑。
