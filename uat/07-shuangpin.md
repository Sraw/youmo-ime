# 07 双拼

区域代号 `SP`。前提里的 **B0-双拼**：B0，然后在设置的 **输入法** 里添加"双拼"，在 **输入法 → 双拼** 里把 **方案** 选成"自然码"（新装默认是小鹤，见 SP-27；下面的用例按自然码的键写），回测试页点「普通文本」框，点地球键直到空格键上写着"双拼"。

### SP-01 [P0] 自然码方案打字
- **前提**：B0-双拼。
- **操作**：点 n i h k（自然码：ni = n i，hao = h k），点空格。
- **预期**：框里是「你好」。
- **JVM**：`OtherInputMethodsUatTest.shuangpinZiranma`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### SP-02 [P1] 换成小鹤方案
- **前提**：B0-双拼，在双拼设置里 **方案** 选"小鹤"。
- **操作**：回测试页点 n i h c（小鹤：hao = h c），点空格。
- **预期**：框里是「你好」。
- **JVM**：`ShuangpinUatTest.xiaohe`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### SP-03 [P1] 微软方案的分号是韵母键
- **前提**：B0-双拼，方案选"微软"。
- **操作**：看 l 右边；点 x，点 l 右边的 `;` 键（打字状态下作韵母 ing），看候选；删光，点 `;`；删光，点 n i，再点 `;`。
- **预期**：微软方案时 l 右边多一个 `;` 键；点它被当作韵母，候选里有「性」「星」等读 xing 的字，不打出分号。删光后在空框点 `;`，框里是全角「；」；点 n i 再点 `;`，第一候选（「你」这类）先上屏，后面跟「；」（`;` 不能作韵母的地方就是标点键，见 docs/pinyin.md「双拼」）。有 `;` 键时 A 和 L 与同排别的键一样宽，字在键中间（截图看）。（分体时的情形见 LS-29。）
- **JVM**：`GroupedKeyboardUatTest.microsoftsSemicolonKeyBesideL`（微软方案 l 右边有 ; 键，A、L、; 和 S 一样宽，点它送 ;，金图 shuangpin_microsoft）、`HostCasesUatTest.microsoftsSemicolonIsAFinal`（uatTest：x; 出性、星，空框「；」，ni; 先上第一候选再「；」）；设备上不跑。

### SP-04 [P1] 零声母音节
- **前提**：B0-双拼（自然码）。
- **操作**：点 a j（自然码 an = a j），看候选。
- **预期**：候选里有「安」。
- **JVM**：`ShuangpinUatTest.aSyllableWithNoInitial`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### SP-05 [P1] 不成音节的两个键按简拼读
- **前提**：B0-双拼（自然码）。
- **操作**：点 f x（自然码里 x 是 ie，f 后面接 ie 不成音节）；点 ⌫ 删光，再点 v g。
- **预期**：f x 的拼音行是 `f x`，候选第一页有「发现」或「方向」；v g 读成音节 zheng（连起来成音节，不按简拼读），见 docs/pinyin.md「双拼」。
- **JVM**：`ShuangpinUatTest.twoKeysNoSyllableAreInitials`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### SP-07 [P1] 全拼里成的词，双拼里也打得出，顺序各学各的
<!-- learning: U-104 D32 -->
- **前提**：B0-双拼（自然码）。先切回拼音，按 PY-34 让「汪小明」成为你的词。
- **操作**：切到双拼，点 w h x c m y（自然码：wang=wh xiao=xc ming=my）。
- **预期**：第一个是「王小明」，「汪小明」紧跟其后（双拼里还没用过，按"你的词"排在没用过的词库词前面；全拼里学到的第一个不带到双拼）。
- **JVM**：`ShuangpinUatTest.aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### SP-09 [P1] 每种键盘都能滑：双拼、五笔、拼音的九键
- **前提**：B0-设置，在 **通用输入** 里打开 **滑行输入**（默认关）；在 **输入法** 里添加"双拼""五笔"，在 **输入法 → 拼音** 里把 **键盘** 选成「九键」；回测试页点「普通文本」框。
- **操作**：
  1. 点地球键切到双拼（自然码）：手指按在 n 上，不抬手依次划过 i、h、k，在 k 上抬起；看候选，删光。
  2. 切到五笔：手指按在 w 上，不抬手依次划过 q、i、y，在 y 上抬起；看候选，删光。
  3. 切到拼音（九键）：手指按在 6 键上，不抬手依次划过 4、2、6（ni 的 6-4，hao 的 4-2-6，同一个键上连着的字母不用离开），在 6 上抬起；看候选。
- **预期**：每种键盘划的时候都画出手指走过的线。1. 双拼：拼音是 `ni hao`，候选里有「你好」。2. 五笔：编码像敲出来的 `wqiy` 一样，候选第一个是「你」。3. 九键：拼音行里是按字母读出的拼音（例如 `ni hao`），不会再被读成同一串数字的其他拼音；候选里有「你好」。
- **JVM**：`ShuangpinUatTest.everyKeyboardGlides`（三种键盘滑出的编码和候选，真数据）、`GlideUatTest.shuangpinWubiAndTheNineKeysGlidesAreDrawn`（三种键盘划的时候画出线，Roborazzi 金图）；设备上不跑。

### SP-13b [P1] 双拼里完整打过两次的词，打一半时排到第三
<!-- learning: U-20 D32 -->
- **前提**：B0-双拼（自然码）。点 f g h，看前五个里没有「奉化」，点 ⌫ 删光。
- **操作**：打「他」，点 f g h w 选「奉化」，点 **。**；再做一次；打「他」，点 f g h。
- **预期**：「奉化」在前三。
- **JVM**：`ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard`（:lib:ime-eval:uatTest，真数据）；设备上不跑。

### SP-13c [P1] 全拼里完整打过的词不带到双拼
<!-- learning: U-20 D32 -->
- **前提**：B0-双拼（自然码）。
- **操作**：切到拼音（全拼），打「他」，点 f e n g h u a 选「奉化」，点 **。**；再做一次；切回双拼，打「他」，点 f g h。
- **预期**：前五个里没有「奉化」。
- **JVM**：`ShuangpinUatTest.aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard`（后半）（:lib:ime-eval:uatTest，真数据）；设备上不跑。

## 18 键和九键双拼

前提 **B0-18键** / **B0-九键双拼**：B0，在 **输入法** 里添加"双拼"，在 **输入法 → 双拼** 里把 **键盘** 选成「18 键」/「九键」，回测试页点「普通文本」框，换到双拼（README「基线状态」）。下面「点 WE」指点键面是 WE 的那个键；18 键是 `Q WE RT Y U IO P` / `A SD FG H JK L` / `Z XC V BN M`（微软是 L;）。

### SP-14 [P0] 18 键用小鹤打整句，音节条
- **前提**：B0-18键，**方案** 改成"小鹤"。
- **操作**：先看键盘；点 WE IO（wo），看编码行右边；接着点 M FG JK BN RT M Q U IO IO FG JK（小鹤：wo mf jb tm qu ii fj），看候选，选「我们今天去吃饭」。
- **预期**：键盘是上面那样三行，键面大写，没有中/英、没有 ⇧。点 WE IO 后编码行右边有音节条，其中有 wo。整句打完，第一页有「我们今天去吃饭」，选了框里就是它，没有私用区的字符。
- **JVM**：`ShuangpinUatTest.theEighteenKeysInXiaoheTypeASentenceAndOfferTheSyllables`（音节条有 wo，整句在第一页、上屏无私用区字符）、`ShuangpinKeysUatTest.theEighteenKeysAreThreeRowsOfCapitalsAndTheCodeRowOffersTheSyllables`（三行大写键面、无中/英和 ⇧，编码行右边音节条有 wo，金图）；设备上不跑。

### SP-15 [P0] 九键双拼用自然码打整句，左列
- **前提**：B0-九键双拼（自然码）。
- **操作**：先看键盘；点 9 6（wo），看左边那一列；接着点 6 3 5 6 8 6 7 8 4 4 3 5（自然码：wo mf jn tm qu ii fj），看候选，选「我们今天去吃饭」。
- **预期**：九宫格，2 键到 9 键是 ABC…WXYZ，5 键只有 JKL（每个方案都一样；微软的 `;` 在 1 键上，1 键角标是 `1;`，见 SP-24），没有中/英键，编码行上没有音节条。点 9 6 后左边那一列列出音节，其中有 wo。第一页有「我们今天去吃饭」，选了框里就是它，没有私用区字符（键盘内部的码）。
- **JVM**：`ShuangpinUatTest.theNineKeysInZiranmaTypeASentenceAndOfferTheSyllables`（音节有 wo，整句在第一页、上屏无私用区字符）、`ShuangpinKeysUatTest.theNineKeysHaveThePhonesLettersAndTheColumnOffersTheSyllables`（ABC…WXYZ、JKL，无中/英，左列列出 wo、编码行无音节条，金图）；设备上不跑。

### SP-16 [P1] 微软 18 键的 L; 键
- **前提**：B0-18键，**方案** 改成"微软"。
- **操作**：看 L 那个键；点 M L;（微软：ming = m ;），看候选；清空，点 L; WE（le），看候选。
- **预期**：键面写着 L 和 `;`（中文里可以是「；」），18 键上没有单独的分号键。M L; 的第一页有「明」或「名」；L; WE 的第一页有「了」。
- **JVM**：`ShuangpinUatTest.microsoftsIngIsTheLSemicolonKey`（m; 有明/名，le 有了）、`ShuangpinKeysUatTest.theEighteenKeysHaveLAndSemicolonOnOneKeyAndNoSemicolonKey`（键面 L;，没有单独的分号键）；设备上不跑。

### SP-17 [P1] 18 键和全键盘双拼分开学
<!-- learning: spk: 和 sp: 各学各的；你的词共用 -->
- **前提**：B0-18键（自然码）。
- **操作**：点 U IO（shi），选第一个以外的一个字 W，点 **。**；再来一次；第三次点 U IO 看候选；回 **输入法 → 双拼** 把 **键盘** 改成「全键盘」，回测试页点 u i 看候选。
- **预期**：18 键上第三次时 W 是首选；全键盘上 u i 的首选不是 W（没学之前的首选）。
- **JVM**：`ShuangpinUatTest.theEighteenKeysLearnApartFromTheFullKeyboard`（18 键学到的首选，全键盘 ui 首选不变）；设备上不跑。

### SP-18 [P1] 18 键打字中回车、换走，上屏的是编码行上的读音
- **前提**：B0-18键。
- **操作**：点 XC IO，看编码行，点回车；清空框，点 BN IO H，看编码行，点 🌐。
- **预期**：两次框里都是当时编码行上的读音（字母，去掉空格和 `'`），没有私用区的字符。18 键上每个键都读得出音节，所以这条只查读音上屏、不漏码；读不出时按每键第一个字母上屏由 JVM 测。
- **JVM**：`ShuangpinUatTest.enterOrASwitchCommitsTheReadingShownInLetters`（回车、换走都上屏编码行读音的字母，真数据）；设备上不跑。

### SP-19 [P1] 已学条目里的双拼 18 键
- **前提**：B0-18键（自然码）。点 U IO，长按第五个候选选 **固定首位**。
- **操作**：打开 **用户词库** 的"固定的词"。再到 **输入法 → 双拼** 把 **键盘** 改成「全键盘」，回测试页换到双拼打 u i，长按第五个候选选 **固定首位**，再看"固定的词"。再把 **键盘** 改成「九键」，打 8 4（自然码 u i），长按第五个候选选 **固定首位**，再看"固定的词"。
- **预期**：18 键那一条写着「双拼 18 键 · 自然码」，按键显示成 `u io`，不是看不见或看不懂的字符；全键盘那一条写着「双拼 · 自然码」；九键那一条写着「双拼九键 · 自然码」，按键显示成九键上的字母（`tuv ghi`）。
- **JVM**：`WordsUatTest.pinsOnTheEighteenKeysAndTheFullListedUnderTheirKinds`（:lib:ime-eval:uatTest，真数据：18 键 U IO 和全键盘 u i 各固定一个，记成各自键盘和自然码的码）、`SettingsPagesUatTest.aPinOnTheEighteenKeysSaysSoWithItsSchemeAndLetters`（"固定的词"里写「双拼 18 键 · 自然码」按键 `u io`，和「双拼 · 自然码」）、`LearnedItemsFragmentTest.eachKeyboardsKindIsNamed`、`.aGroupedKeyboardsKeysAreShownAsTheirLetters`（双拼九键的条目标「双拼九键」，按键是九键上的字母）；设备上不跑。

### SP-20 [P1] 18 键没有中/英，用 🌐 到英文再回来
- **前提**：B0-18键。
- **操作**：看底排；一直点 🌐 直到英文，看键盘和底排；再一直点 🌐 直到回到双拼。
- **预期**：18 键底排没有中/英；英文是全键盘，底排也没有中/英；回到双拼时又是 18 键。
- **JVM**：`GroupedKeyboardUatTest.theGlobeGoesToTheEnglishKeyboardAndBack`（18 键没有中/英，🌐 到英文全键盘、回来又是 18 键）、`GroupedKeyboardTest.theBottomRowHasNoChineseEnglishKeyAndTheLanguageSwitchFollowsItsSetting`；设备上不跑。

### SP-21 [P1] 网址、邮箱框里的 18 键和九键双拼
- **前提**：B0-18键。
- **操作**：打开测试页的「网址」框，换到双拼，点 BN IO，点 **.com**；打开「邮箱」框，点 BN IO，点 **@**。再把 **键盘** 改成「九键」，在「网址」框点 6 4，看左列；点空格上屏，再点左列的 **.com**。
- **预期**：18 键框里是字母加 `.com` / `@`。九键双拼打字时左列是音节（有 ni），上屏后左列换成地址键，点 .com 后框里是上屏的字加 `.com`。都没有私用区字符。18 键底排没有中/英，空格至少占键盘宽的 15%（`button_space` 节点的宽度除以 `keyboard_view` 的宽度）。
- **JVM**：`GroupedKeyboard18Test.theSpaceIsAtLeast15PercentOfTheRowInEveryField`、`GroupedKeyboardUatTest.theEighteenAndNineKeysInAnAddressField`、`KeyboardHalvesUatTest.inAnAddressFieldShuangpinsGroupedKeysGoInAsLettersOrTheWordTyped`（18 键网址/邮箱框无中/英、BN IO 发两个键、.com/@ 原样进框；九键双拼打字时左列有 ni 无 .com，上屏后有 .com、点了框里是字加 .com，无私用区字符（键盘半边）；引擎把 18 键读成字母、九键双拼 ni 上屏汉字（引擎半边））；设备上不跑。

### SP-22 [P1] 九键双拼的 1 键
- **前提**：B0-九键双拼。
- **操作**：点 6 4，点 1，点 4 3，看编码行；清空；什么都没打时点 1。
- **预期**：打字时 1 分开两个音节，编码行里有 `'`；没打字时 1 打出标点（框里多了一个标点符号）。
- **JVM**：`ShuangpinUatTest.onTheNineKeysOneSeparatesTheSyllablesWhileTyping`（打字时编码行有 '）、`ShuangpinKeysUatTest.theNineKeysOneIsPunctuationWithNothingTypedTheSeparatorWhileTyping`（没打字时 1 上屏「，」，打字时送分隔符）；设备上不跑。

### SP-23 [P1] 微软 18 键长按 L; 选 ;，点声母定下声母
- **前提**：B0-18键，**方案** 改成"微软"。
- **操作**：点 BN IO，长按 L; 选「；」（开着中文标点，长按里的 ; 显示成「；」）；清空框，点 XC IO，在音节条上点 `c`，看候选。
- **预期**：第一次框里是「你」之类的字加「；」，不是 `ni；`。第二次候选都是以 c 开头的音节（词、次、错…），没有「西」，音节条上是 ci、cuo 这类。
- **JVM**：`ShuangpinUatTest.microsoftsScreenSemicolonIsAMarkAndAnInitialTakenKeepsItsSyllables`（你；不是 ni；，点 c 后候选没有西、前几个候选读 ci/cuo（词、次、错）、音节条全是 c 开头）；设备上不跑。

### SP-24 [P1] 九键双拼的长按、按键字符和 123
- **前提**：B0-九键双拼，**方案** 改成"微软"；在 **输入法 → 双拼 → 九键 → 按键长按** 里把 2 键的符号改成 `~`（九键键盘的设置，拼音和双拼的九键共用）。
- **操作**：回测试页点「普通文本」框，长按 5（JKL）看弹出的一行；长按 2 看上面的符号；清空，点 6 键，量编码行，看编码行上有没有音节条；点 **123**，点音节条上的第一个音节；点 **ABC** 回来，清空，换到拼音（全键盘）打 ni，量编码行。
- **预期**：5 键只有 JKL，弹出的一行是 J K L、5、j k l 和 5 键的符号，没有「；」「：」；微软的 `;`（ing）在 1 键上，1 键角标是 `1;`，打字时声母后面按 1 是 ing；2 键的符号是改过的 `~`（显示成中文标点的「～」，点它打出的也是），和拼音的九键一样。九键双拼的编码行 32dp；字母页上音节在左边那一列，编码行上没有音节条；打字中点 123，编码行上有音节条，点一个音节拼音行就跟着改。拼音全键盘的编码行 18dp。
- **JVM**：`GroupedKeyboardUatTest.nineKeyShuangpinsLongPressesCodeRowAndTheNumbersStrip`、`KeyboardHalvesUatTest.aSyllableTakenOnTheNumbersPageGoesIntoTheCode`（长按 5 有 J K L，没有 `;` `:`；2 键改成 ~ 后长按有「～」、点了发 ~（拼音的九键同）；编码行 32dp，字母页音节在左列不在编码行，123 页有音节条、点了发音节键；拼音全键盘 18dp（键盘半边）；123 页选的音节进编码行（引擎半边）；~ 变成全角「～」是 fcitx 中文标点的，JVM 只断言发出 ~）；设备上不跑。

### SP-25 [P1] 双拼划一竖筛候选，接着打字笔画就没了
- **前提**：B0-双拼，另外在 **通用输入** 里打开 **笔画筛选**。
- **操作**：打 xi，看首选；在键盘上划一竖，看编码行和候选；再点 u。
- **预期**：首选「系」。划一竖后编码行是 `xi 丨`，首选「吸」，「曦」在第一页，「系」「西」不见了。点 u 后编码行是 `xi u`，笔画没了，u 照常是下一个字的声母 sh（候选里有「系数」）。18 键、九键双拼上一样（这里只在全键盘上测；18 键、九键双拼打 xi 的键码再划一竖由 JVM 测，候选顺序不比；分组键盘上发笔画见 KB-42）。
- **JVM**：`StrokeFilterUatTest.shuangpinXiThenADownStroke`（uatTest，真数据，自然码）、`StrokeFilterUatTest.shuangpinXiOnTheGroupedKeysThenADownStroke`（18 键、九键双拼：吸、曦在，系、西不在）；设备上不跑。

### SP-26 [P1] 双拼 14 键；微软没有
- **前提**：B0-18键，**键盘** 改成「14 键」。
- **操作**：看键盘；点 BN 键，量编码行；打 ni hao（自然码 n i h k：点 BN UI GH JK）看候选。然后把 **方案** 改成"微软"，不收起键盘，看键盘和 **输入法 → 双拼 → 键盘**；再把方案改回自然码。
- **预期**：三行键：`QW ER TY UI OP` / `AS DF GH JK L` / `分词 ZX CV BN M ⌫`（和拼音 14 键同一套分组），编码行 32dp，右边有音节条，候选第一页有「你好」。方案一改成微软，键盘马上换成 18 键（微软的 ; 是韵母，14 键上没有；离 14 键最近的是 18 键），**键盘** 的选项里没有「14 键」，选中的是「18 键」，设置也存成 18 键；换回自然码仍是 18 键。
- **JVM**：`ImKeyboardsUatTest.shuangpinsFourteenKeysSendTheirOwnCodesAndAreNoneUnderMicrosoft`（键面是 G14 的分组，BN 发双拼 14 键自己的码，编码行 32dp；真的换方案（EngineBridge.configure）后是 18 键、设置存成 18 键；图）、`InputMethodSettingsScreenTest.theFourteenKeysUnderMicrosoftShowAsTheEighteen`（设置页的 **键盘** 选中的是「18 键」）、`ChineseModulesHostTest`（换方案时双拼的键盘重新告诉）、`ImKeyboardsTest`（ime-core：只有微软没有 14 键，退到 18 键）；准确率见 dev/m2/i1-report.md（ime-eval `shuangpin --keys 14`）；设备上不跑。

### SP-27 [P0] 新装的双拼方案是小鹤，只有三个方案
- **前提**：B0（刚重置，没改过双拼的设置），在 **输入法** 里添加"双拼"。
- **操作**：打开 **输入法 → 双拼**，看 **方案** 和它的选项；回测试页点「普通文本」框，换到双拼，点 n i h c（小鹤：hao = h c），点空格；重启手机后再看 **方案**。
- **预期**：**方案** 是"小鹤"，选项只有自然码、微软、小鹤三个（没有紫光、智能ABC、中文之星、拼音加加、国标）；框里是「你好」；键盘上方没有提示；重启后仍是小鹤。
- **JVM**：`KotlinHostSchemeTest.aNewInstallsShuangpinIsXiaohe`（新装第一次启动是小鹤，即选项的默认值，不写配置、不提示，重启后仍是小鹤）、`SchemeMigrationTest`（ime-core：默认值是小鹤、升级、三个方案各自怎么读）、`SettingsUatTest.theShuangpinSchemesAreTheThreeKept`（方案的选项只有三个）；设备上不跑。

### SP-28 [P1] 小鹤、自然码的 18 键和九键没有分号
- **前提**：B0-18键，**方案** 是"小鹤"（再试一次"自然码"）；然后 **键盘** 改成「九键」再看一次。
- **操作**：看 L 那个键（九键看 5 键），长按它。
- **预期**：18 键的键面只写 L，九键的 5 键只写 JKL、1 键角标只写 `1`，都不写 `;`；长按一行里没有 `;` 和 `:`（九键 2–9 键自己设的符号照旧）。把方案改成"微软"，不收起键盘，18 键马上变成 L;，九键 1 键角标马上变成 `1;`（5 键仍是 JKL）。
- **JVM**：`ShuangpinKeysUatTest.theEighteenKeysUnderASchemeWithoutTheSemicolonShowLAlone`（小鹤键面是 L，没有 `;`）、`ShuangpinKeysUatTest.theNineKeysUnderASchemeWithoutTheSemicolonShowJklAlone`（5 键是 JKL，长按没有 `;` `:`；小鹤、自然码 1 键角标是 `1`，新画法和旧 View 都查；换成微软、重新开始输入后就是 `1;`）、`KeyboardWindowTest`（小鹤、自然码的 18 键和九键是没有分号的那个，微软是带分号的）、`GroupedKeyboard18Test.pinyinsEighteenKeysShowLWithoutTheSemicolon`（不带分号时长按没有 `;` `:`，码不变）；设备上不跑。

