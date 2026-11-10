# 08 英文

区域代号 `EN`。前提里的 **B0-英文**：B0，然后点地球键切到英文（空格键上写着"英文"）。

### EN-01 [P0] 打对的词，候选最左就是它
- **前提**：B0-英文。
- **操作**：点 h e l l o。
- **预期**：候选栏最左是「hello」；右边是以 hello 开头的其他词，也可以有按偏一两个键时可能想打的词（如 yellow，见 docs/other-input-methods.md「英文」）。
- **JVM**：`OtherInputMethodsUatTest.englishTheWordTypedIsFirst`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-02 [P0] 空格自动改正拼错的词，后面加空格
- **前提**：B0-英文。
- **操作**：点 t e h，点空格。
- **预期**：打字时候选最左是「the」，紧挨着右边是你打的「teh」；点空格后框里是「the 」。
- **JVM**：`OtherInputMethodsUatTest.englishSpaceCorrectsAMisspellingASpaceAfterIt`（:lib:ime-eval:uatTest，真数据）；设备上留作冒烟（看胶水）。

### EN-03 [P1] 补撇号
- **前提**：B0-英文。
- **操作**：点 d o n t，点空格。
- **预期**：框里是「don't 」。
- **JVM**：`EnglishUatTest.theApostropheAdded`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-04 [P0] 改错了马上退格恢复原样，以后不再改；在英文词库里能恢复
<!-- learning: U-31 D11 C13 -->
- **前提**：B0-英文。
- **操作**：点 t e h，点空格（变成 the）；马上点 ⌫；再点空格；然后再打一次 t e h 空格。再打开 **输入法 → 英文 → 英文词库**，找「teh」，点它选 **恢复自动改**；回测试页清空，切到英文，打 t e h 点空格。
- **预期**：退格后框里恢复成「teh」；最后一次打 teh 空格不再被改，框里末尾是「teh 」。英文词库的列表里有「teh」，标着不再自动改；恢复后 teh 空格又变成「the」。
- **JVM**：`OtherInputMethodsUatTest.englishBackspaceRightAfterACorrectionUndoesItForGoodUntilRestored`（:lib:ime-eval:uatTest，真数据：打字和词库这一半）、`SettingsPagesUatTest.anEnglishWordNoLongerCorrectedIsMarkedAndCorrectedAgainOnATap`（英文词库里 teh 标着不再自动改，点它选恢复自动改，引擎被告知、标记没了）；设备上不跑。

### EN-06 [P1] 打标点时空格移到标点后面
- **前提**：B0-英文。
- **操作**：点 h i，点空格（得到「hi 」），点底排的 **.**（句号键）。
- **预期**：框里是「hi. 」，句号紧贴单词，空格在句号后面。
- **JVM**：`EnglishUatTest.aMarkTakesTheSpaceAfterIt`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-07 [P1] 连按两下空格打出句号
- **前提**：B0-英文。
- **操作**：点 o k，快速点两下空格。
- **预期**：框里是「ok. 」。
- **JVM**：`EnglishUatTest.twoSpacesAFullStop`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-08 [P1] 下一个词的联想
- **前提**：B0-英文。
- **操作**：点 t h a n k，点空格，看候选栏。
- **预期**：栏上给出下一个词的联想（例如「you」）。
- **JVM**：`EnglishUatTest.theNextWordPredicted`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-09 [P0] 句首自动大写（输入框要求时）
- **前提**：B0-英文，点「句子」框。
- **操作**：点 h i，点空格，点 **.**，再点 o k。
- **预期**：开头的 h 自动大写成「Hi」；句号之后的 o 也自动大写：框里是「Hi. Ok」（或候选最左是 Ok）。
- **JVM**：`EnglishCapsUatTest.aSentencesFirstLetterIsACapital`（Robolectric，KeyboardHarness）；设备上不跑。

### EN-10 [P1] 用户名、网址框不自动大写
- **前提**：B0-英文，点「网址」框。
- **操作**：点 a b c。
- **预期**：打出的是小写「abc」，Shift 没有自己亮起。
- **JVM**：`EnglishCapsUatTest.anAddressFieldGetsNoCapitals`（Robolectric，KeyboardHarness）；设备上不跑。

### EN-11 [P1] 过后点回被改的词换回原词，以后也不再改
<!-- learning: U-31 -->
- **前提**：B0-英文。
- **操作**：点 y o u m o，点空格（被改成别的词 X）；接着打 h i 空格；点回 X 这个词里，看候选，点原样的「youmo」；把光标移到末尾，再打 y o u m o 点空格。
- **预期**：光标落进 X 时候选第一个是「youmo」；换回后框里是「youmo hi 」；最后一次空格上屏的是「youmo」，没有被改。
- **JVM**：`EnglishUatTest.aCorrectionTappedBackIntoAndUndoneIsNotMadeAgain`（:lib:ime-eval:uatTest，真数据；youmo 被改后光标放到改出的词末尾、重新告诉引擎，候选第一个是 youmo，选回后再打不改）；设备上不跑。

### EN-12 [P1] 英文词库里加的词不会被改
- **前提**：B0-设置，**输入法 → 英文** 的齿轮 → **英文词库**，添加 `qwzx`。
- **操作**：回测试页切到英文，打 q w z x 空格。
- **预期**：框里是「qwzx 」，没被改；打 q w z 时候选里有 qwzx。
- **JVM**：`EnglishUatTest.aWordAddedIsNotCorrected`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-13 [P1] 网址、邮箱、密码框里不提示也不改
- **前提**：B0-英文，点「邮箱」框。
- **操作**：点 t e h。
- **预期**：t、e、h 直接上屏，候选栏没有提示。
- **JVM**：`EnglishUatTest.anEmailFieldNeitherSuggestsNorCorrects`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-14 [P1] 不要提示的输入框里不提示
- **前提**：B0-英文，点「不要提示」框。
- **操作**：点 t e h，点空格。
- **预期**：框里是「teh 」，没有提示和改正。
- **JVM**：`EnglishUatTest.aFieldAskingNoSuggestionsGetsNone`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-15 [P1] 关掉单词提示
- **前提**：B0-设置，英文设置里关掉 **单词提示**。
- **操作**：回测试页切到英文，点 t e h，点空格。
- **预期**：字母直接上屏，没有候选；框里是「teh 」。
- **JVM**：`EnglishUatTest.suggestionsTurnedOff`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### EN-19 [P1] 不要提示的输入框、关掉单词提示时也能划词
- **前提**：B0-英文，在 **在键上划** 里选 **滑行输入**，点「不要提示」框。
- **操作**：划 h-e-l-l-o。再到英文设置里关掉 **单词提示**，回测试页在普通框里划 h-e-l-l-o。然后在这个框里清空，打 abc，再划 h-e-l-l-o，接着按一下退格；再划 h-e-l-l-o，接着打 `,`。
- **预期**：前两次框里都是「hello 」，没有方块、问号之类的怪字符，也不会什么都没打上。打 abc 后划出的是「abc hello 」（有空格，不粘连）；按退格后整个「 hello 」删掉，剩「abc」；再划后打逗号，是「abc hello, 」（空格移到逗号后面），候选栏不出联想词。
- **JVM**：`KeyboardFieldsUatTest.aNoSuggestionsFieldAndSuggestionsOffStillGlide`（两种情况都交出一次划词）、`EnglishUatTest.aGlideInANoSuggestionsFieldAndWithSuggestionsOffTypesTheWord`（:lib:ime-eval:uatTest，两次都是「hello 」；关掉单词提示后打 abc 再划是「abc hello 」，退格剩「abc」，再划打逗号是「abc hello, 」）；设备上照跑（候选栏不出联想词只在设备上看）。

### EN-21 [P1] 要求大写的框里划词：句首大写，专名和 I 保持大写；手动 ⇧ 也大写
- **前提**：B0-英文，在 **在键上划** 里选 **滑行输入**，点「句子」框（这个框要求句首大写），Shift 关着。
- **操作**：清空，划 h-e-l-l-o，再划 l-o-n-d-o-n；打 `.` 和空格，划 i-m（i'm）。清空，打 `so ` 再划 i-m；到「不要提示」框（不自动大写）打 `ok ` 再划 i-m。在「不要提示」框清空，点一下 ⇧，划 h-e-l-l-o。
- **预期**：框里是「Hello London. I'm 」：句首键盘自动打开 Shift，划出的第一个词大写；London 是词本身带的大写，句中照样保留；i'm 划出来是「I'm」，和点出来的一样。句中 Shift 关着时也一样：「So I'm 」（句首的 s 照常自动大写，I'm 在句中也大写）；不自动大写的框里是「ok I'm 」，不是「i'm」。点 ⇧ 后划的是「Hello 」（Shift 开着时首字母大写）。
- **JVM**：`EnglishCapsUatTest.aGlideTakesShiftWhereTheFieldAsksACapitalOrItIsPressed`（键盘何时带 Shift）+ `EnglishUatTest.aGlideTakesShiftAWordsOwnCapitalAndI`（引擎的大写）；设备上不跑。

### EN-26 [P1] 在候选里点原样的词，算改回过一次
<!-- learning: U-31 D11 -->
- **前提**：B0-英文。
- **操作**：打 t e h，点候选里原样的「teh」上屏；再打 t e h，点空格。
- **预期**：最后一次 teh 空格不再被改，框里末尾是「teh 」（有改正时点原样的词，和上屏后退格改回一样，见 EN-04、docs/other-input-methods.md）；英文词库的"不再自动改的词"里有 teh。只是打过几次、没改回过的词照样会被改，这一条由单元测试 `EnglishUserTest.aWordTypedTwiceIsCorrectedStill` 管，设备上打不出"不经改正就上屏的 teh"。
- **JVM**：`EnglishUatTest.theWordAsTypedPickedCountsAsACorrectionTakenBack`（:lib:ime-eval:uatTest，真数据：打字和词库这一半）、`SettingsPagesUatTest.anEnglishWordNoLongerCorrectedIsMarkedAndCorrectedAgainOnATap`（英文词库里 teh 那一行写着不再自动改）；设备上不跑。


### EN-27 [P0] 屏蔽一个英文词：不再补全、不再改成它，设置里恢复
<!-- learning: U-82 -->
- **前提**：B0-英文。
- **操作**：打 t h，长按候选里的「the」，看菜单，选 **屏蔽**，确认；清空，打 t h；再打 t h w，按空格；然后在 **词库与短语 → 用户词库 → 屏蔽的词** 里恢复「the」，再打 t h w，按空格。再打 i p h o n e，长按候选「iPhone」屏蔽，看屏蔽的词里它那一行。
- **预期**：菜单里有 **屏蔽**；屏蔽后 t h 的候选里没有「the」，`thw` 空格不再改成「the 」（改成下一个最可能的词，比如 they）；屏蔽的词里列着「the」（没有拼音），那一行写「英文 · 全小写的词不分大小写屏蔽」；「iPhone」那一行写「英文 · 只屏蔽这样写的大小写」；恢复后 t h 下又有「the」，`thw` 空格又改成「the 」。屏蔽前长按英文候选、点屏蔽后，词确实被屏蔽（英文候选的长按动作以前走不到引擎：见下面 JVM）。原样打的那个候选（最左边紧挨着的 th）长按没有屏蔽。
- **JVM**：`EnglishUatTest.aWordBlockedIsNoLongerCompletedNorCorrectedTo`（:lib:ime-eval:uatTest，真数据，经宿主的长按动作）、`EnginesEnglishBlockTest`（补全、改正、滑行的其他词、撤销、存进用户目录 words.en.blocked、在设置里恢复、备份恢复）、`EnglishSessionTest.aPredictionBlockedIsNotPredictedTillUnblocked`（联想）。设备上跑过（2026-11-08，emulator-5574）。

### EN-28 [P1] 九键的首词、滑行的其他词也能屏蔽
<!-- learning: U-82 -->
- **前提**：B0-英文；九键那步切到英文九键，滑行那步在 **通用输入** 的 **在键上划** 里选 **滑行输入**。
- **操作**：九键按 8 4 3，长按候选「the」选 **屏蔽**；清空再按 8 4 3。全键盘划 h-e-l-o（上屏「hello 」），长按候选里的「help」选 **屏蔽**，再点剩下的另一个词；再划一次，长按第一个候选「hello」。
- **预期**：843 的「the」能屏蔽，之后 843 不再给「the」；「help」从候选里消失，其余还在，点的词替换掉「hello 」；滑行已上屏的那个词（第一个候选）长按没有 **屏蔽**。
- **JVM**：`EnglishBlockOffersTest`；设备上不跑。
