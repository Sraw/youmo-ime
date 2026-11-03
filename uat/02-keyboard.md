# 02 全键盘

区域代号 `KB`。全键盘：拼音、双拼、英文、码表共用的 26 键键盘。

### KB-01 [P0] 点字母打拼音，候选栏显示候选
- **前提**：B0。
- **操作**：点 n、i。
- **预期**：框里还是空的；`ni` 显示在候选栏上方那一行里；键盘上方的工具栏换成候选栏，第一页有"你"。
- **JVM**：`BarUatTest.lettersTypedPutTheCandidatesOnTheBar`（真宿主：点 n i 拼音行「ni」、候选栏有「你」、框里还空）和 `HostReplayUatTest.spaceCommitsTheFirstSentence`（打 nihao 有候选、空格上屏）；设备上留作冒烟（真引擎和真数据下的第一屏候选）。

### KB-02 [P0] 英文里点字母直接打出字母
- **前提**：B0，点地球键切到英文。
- **操作**：点 o、k，再点空格。
- **预期**：框里是「ok 」（单词后自动加了空格）。
- **JVM**：`KeysUatTest.englishLettersTypeThemselvesASpaceAfter`（:lib:ime-eval:uatTest，真数据）（英文 ok 空格后框里「ok 」）；设备上不跑。

### KB-03 [P0] 向下滑字母键打出键角的数字
- **前提**：B0。
- **操作**：手指按在 w 键上向下滑再松开。
- **预期**：框里是「2」，没有拼音候选。
- **JVM**：`KeyboardUatTest.swipingALetterDownTypesItsCorner`（下划 w 只向引擎发一次角标 2）；设备上不跑。

### KB-04 [P1] 长按字母键后直接松手打出键角的字符
- **前提**：B0。
- **操作**：长按 e 键，弹出字符后不移动手指直接松开。
- **预期**：框里是「3」。
- **JVM**：`KeyboardUatTest.aLongPressLetGoTypesTheCorner`（长按 e 原地松开只发角标 3）；设备上不跑。

### KB-05 [P1] 长按弹出更多字符，滑到哪个打哪个
- **前提**：B0。
- **操作**：长按 q 键，弹出一排字符后手指滑到 `Q` 上再松开。
- **预期**：弹出的字符里有 `1` 和 `Q`；框里是「Q」。
- **JVM**：`KeyboardUatTest.aLongPressesChoicesTypeWhereTheFingerIs`（长按 q 弹出 1 和 Q，滑到 Q 松开只发 Q）；设备上不跑。

### KB-06 [P1] 打中文时键角显示中文标点
- **前提**：B0。
- **操作**：看 m 键和 v 键的键角。
- **预期**：m 的键角是「、」，v 的键角是「？」（全角）。切到英文后再看，分别是 `\` 和 `?`。
- **JVM**：`KeyboardUatTest.cornersShowChinesePunctuationWhileChinese`（标点开着时 m、v 角标是、和？，英文时是 \ 和 ?）；设备上不跑。

### KB-07 [P0] Shift 让下一个字母大写
- **前提**：B0，点地球键切到英文。
- **操作**：点 ⇧，点 h，点 i。
- **预期**：候选栏最左边是「Hi」；点空格后框里是「Hi 」。
- **JVM**：`KeysUatTest.shiftCapitalisesTheNextLetter`（:lib:ime-eval:uatTest，真数据）（Hi 第一、空格后「Hi 」（⇧ 送出大写由 KeyboardUatTest KB-08 看））；设备上不跑。

### KB-08 [P1] 双击 Shift 大写锁定
- **前提**：B0，点地球键切到英文。
- **操作**：快速点两下 ⇧，点 o、k；再点一下 ⇧，点 a。
- **预期**：锁定后 ⇧ 键样子变了（表示锁定），o、k 都是大写，候选最左是「OK」；再点 ⇧ 解除锁定后打的 a 是小写。
- **JVM**：`KeyboardUatTest.shiftTwiceLocksCaps`（双击 Shift 状态为大写锁定，O K 都是大写，再按一下回到小写）；设备上不跑。

### KB-09 [P0] 退格删除一个字
- **前提**：B0，「普通文本」框里已有「你好」，光标在末尾。
- **操作**：点 ⌫ 一次。
- **预期**：框里是「你」。
- **JVM**：`KeyboardUatTest.backspaceOnce`（点一下退格只发一次 BackSpace）；设备上不跑。

### KB-10 [P1] 退格把一个表情整个删掉
- **前提**：B0，从表情面板打一个「👍🏽」（带肤色的），光标在它后面。
- **操作**：点 ⌫ 一次。
- **预期**：表情整个消失，框里为空，不留下半个字符或方框。
- **JVM**：`KeyboardFieldsUatTest.backspaceTakesAnEmojiWithItsSkinToneWhole`（带肤色的表情一次退格删光）；设备上不跑。

### KB-11 [P0] 按住退格连续删除
- **前提**：B0，在「长文本」框里把光标放在末尾。
- **操作**：按住 ⌫ 约 2 秒再松开；再按住 ⌫，按住时手指左右挪一点（不到半个键宽），约 2 秒后松开。
- **预期**：按住期间一个接一个地删，越删越快；松开就停。手指挪一点也照样连续删（只有真正横滑才变成选择，见 KB-12）。
- **JVM**：`KeyboardUatTest.holdingBackspaceRepeatsFasterTillLetGo`（按住退格连删且第二秒比第一秒多，松手即停；手指小幅移动照样连删）；设备上不跑。

### KB-12 [P1] 在退格键上向左滑选中并删除光标前的文字
- **前提**：B0，「普通文本」框里已有「今天天气很好」，光标在末尾。
- **操作**：手指按在 ⌫ 上向左慢慢滑一段，先不松手看一眼，再松手。
- **预期**：滑动时光标前的文字随滑动距离被选中（高亮）；松手后选中的部分被删掉。
- **JVM**：`KeyboardUatTest.swipingBackspaceLeftSelectsAndDeletes`（退格左滑选中光标前的字，松手删掉）；设备上不跑。

### KB-13 [P1] 打拼音时向左滑退格清空正在打的拼音
- **前提**：B0。
- **操作**：点 z h o n g g u o，然后在 ⌫ 上向左滑并松手。
- **预期**：正在打的 `zhongguo` 整个消失，框里为空，候选栏回到工具栏。
- **JVM**：`KeyboardUatTest.swipingBackspaceWhileTypingClearsThePinyin`（有拼音时退格左滑让引擎 reset 清掉拼音）；设备上不跑。

### KB-14 [P0] 打中文时逗号句号是中文标点
- **前提**：B0。
- **操作**：点 n i h a o，点空格，再点底排的 **，**，再点 **。**。
- **预期**：框里是「你好，。」。
- **JVM**：`HostCasesUatTest.commaAndFullStopAfterChineseAreChinese`（Kotlin 宿主的标点模块，「你好，。」）；设备上不跑。

### KB-15 [P1] 跟在数字后面的逗号也是中文的
- **前提**：B0。
- **操作**：向下滑 q 键打出 1，再点底排的 **，**。
- **预期**：框里是「1，」。
- **JVM**：`HostCasesUatTest.aCommaAfterADigitIsChinese`（「1，」）；设备上不跑。

### KB-16 [P1] 长按逗号弹出更多标点，挪一点换一个，两排都能选到
- **前提**：B0。
- **操作**：长按底排的 **，**，看弹出；不挪松手；清空，再长按 **，** 往右挪约 25dp 松手；清空，再长按 **，** 往上挪约 25dp 松手；清空，长按 l 键往上挪很远（超过弹出框）再松手。
- **预期**：弹出两排标点，依次是 . ? ! : ; " ' ( ) ~ 的中文形式，浮在键的上方；第一次是一开始选中的那个（「。」），往右挪一点是它右边那个，往上挪一点是它上面那个；挪过头停在最上一行，照样打出一个（不会什么都不打）。
- **JVM**：`KeyboardUatTest.holdingTheCommaOffersTwoRows`（长按逗号弹出两行标点，原地松开打出第一个，往右、往上、上移很远松开都打出一个）；设备上不跑。

### KB-17 [P0] 在空格键上左右滑移动光标
- **前提**：B0，点「长文本」框，光标在末尾。
- **操作**：手指按住空格键向左滑一段再松开；再向右滑一点。
- **预期**：向左滑时光标随手指往前移，滑得越远移得越多；向右滑往后移；不打出空格。
- **JVM**：`KeyboardUatTest.spaceSlidesMoveTheCursor`（空格左滑发几次 Left，右滑短一点发更少的 Right）；设备上不跑。

### KB-18 [P0] 有候选时空格上屏第一个候选
- **前提**：B0。
- **操作**：点 s h i j i a n，点空格。
- **预期**：框里是候选栏原来的第一个候选（例如「时间」），候选栏回到工具栏或显示联想。
- **JVM**：`KeysUatTest.spaceCommitsTheFirstCandidate`（:lib:ime-eval:uatTest，真数据）（shijian 空格上屏第一候选、没有剩下的拼音）；设备上不跑。

### KB-19 [P0] 打拼音时回车把字母原样上屏
- **前提**：B0。
- **操作**：点 a b c，点回车键。
- **预期**：框里是「abc」，没有换行。
- **JVM**：`KeysUatTest.returnCommitsTheLettersAsTyped`（:lib:ime-eval:uatTest，真数据）（abc 回车上屏「abc」）；设备上不跑。

### KB-20 [P1] 搜索框的回车键是"搜索"
- **前提**：B0，点「搜索」框。
- **操作**：看回车键的图标；点 a，点回车键（打拼音时先把字母上屏），再点一次回车键。
- **预期**：回车键是放大镜图标；第二次点回车后页面执行了搜索：「搜索」框下面出现"搜索：a"，框失去焦点，键盘收起；框里是「a」，没有换行。
- **JVM**：`KeyboardUatTest.searchFieldsReturnSearches`（搜索框回车键是搜索，按下执行搜索不换行）；设备上不跑。

### KB-21 [P1] "下一项"框的回车跳到下一个输入框
- **前提**：B0；打开一个回车是"下一项"（`IME_ACTION_NEXT`）、下面还有一个框的原生输入框，点它。redroid 上用调试包的测试宿主 `TypingHostActivity` 带 `--ez next true`（「第一项」「第二项」两个框，见 README"测试宿主"；测试页的 WebView 浏览器不理输入法的"下一项"）；真机上也可以在 Chrome 里点测试页的「下一项」框（下面是「不要提示」框）。
- **操作**：点回车键。
- **预期**：回车键图标是"下一项"箭头；点了以后焦点跳到下面的框（「第二项」，Chrome 里是「不要提示」）。
- **JVM**：`KeyboardUatTest.nextFieldsReturnGoesOn`（下一项框回车键是下一项，按下执行 NEXT）；设备上不跑。

### KB-22 [P0] 网址框里逗号句号换成 / . .com
- **前提**：B0，点「网址」框。
- **操作**：看底排空格两边的键；点 **.com**。
- **预期**：底排有 **/**、**.** 和 **.com**（没有中文逗号句号）；框里是「.com」。
- **JVM**：`KeyboardUatTest.addressFieldHasSlashDotAndDotCom`；设备上不跑。

### KB-23 [P1] 长按 .com 有 .cn .net .org
- **前提**：B0，点「网址」框。
- **操作**：长按 **.com**，滑到 `.cn` 松手。
- **预期**：弹出 `.cn`、`.net`、`.org`；框里是「.cn」。
- **JVM**：`KeyboardUatTest.holdingDotComOffersMore`（长按 .com 弹出 .cn .net .org，选 .cn 上屏）；设备上不跑。

### KB-24 [P0] 邮箱框打拼音字母再按 @，字母原样上屏
- **前提**：B0，点「邮箱」框。
- **操作**：点 x i a o m i n g，再点 **@** 键，再点 q q，点 **.com**。
- **预期**：框里是「xiaoming@qq.com」，没有出现汉字。
- **JVM**：`KeysUatTest.anEmailFieldsLettersBeforeAtAreCommittedAsTyped`（:lib:ime-eval:uatTest，真数据）（邮箱框 @ 和 .com 按 PendingInputPolicy 先把字母原样上屏：「xiaoming@qq.com」）；设备上不跑。

### KB-25 [P1] 网址框里数字和符号原样打出
- **前提**：B0，点「网址」框。
- **操作**：向下滑 w 键打 2，长按 h 键选 `/`。
- **预期**：框里是「2/」（半角）。
- **JVM**：`HostCasesUatTest.anAddressFieldTypesDigitsAndMarksAsTheyAre`（网址框打 2、/ 得「2/」）；设备上不跑。

### KB-26 [P0] 括号成对，光标停在中间
- **前提**：B0。
- **操作**：长按 k 键选 `(`（打中文时是「（」）。
- **预期**：框里是「（）」，光标在两个括号中间。
- **JVM**：`PairsUatTest.aBracketComesInPairsTheCursorBetween`（「（|）」（长按弹出的选择是 KeyboardUatTest KB-05 的））；设备上不跑。

### KB-27 [P1] 打右括号跳过已有的那个
- **前提**：B0，按 KB-26 打出「（）」，光标在中间，再打 n i 空格出「你」。
- **操作**：长按 l 键选右括号「）」。
- **预期**：框里是「（你）」，只有一个右括号，光标在最后。
- **JVM**：`PairsUatTest.theClosingBracketStepsOverTheOneThere`（「（你）|」）；设备上不跑。

### KB-28 [P1] 紧接着退格把一对一起删掉
- **前提**：B0，按 KB-26 打出「（）」，光标在中间。
- **操作**：点 ⌫。
- **预期**：框里为空。
- **JVM**：`PairsUatTest.backspaceRightAfterDeletesThePair`（退格后框里为空）；设备上不跑。

### KB-30 [P1] 按键时弹出放大的字符
- **前提**：B0。
- **操作**：按住 a 键不松手，看手指上方。
- **预期**：手指上方弹出一个放大的"a"；松手后消失。
- **JVM**：`KeyboardUatTest.aKeyHeldShowsItLarger`（按住 a 弹出放大的 a，松手收起）；设备上不跑。

### KB-31 [P1] 拼音那一行是键盘自己的，打字时不挡应用、键盘不变高
- **前提**：B0，测试页地址改成 `fields.html#chat`（只显示「聊天」框，它和"发送"按钮贴在页面底部，像聊天应用的输入栏），点「聊天」框：键盘弹出后它紧贴键盘上沿。
- **操作**：看工具栏上方；点 n i h a o；再点 ⌫ 五下删光。
- **预期**：没打字时工具栏上方留着一行空白（和工具栏同色）；打字时 `ni hao` 出现在这一行里，「聊天」框和「发送」按钮一点都没被挡住；整个过程中键盘上沿和「聊天」框的位置都不动。

## 滑行输入

前提里的 **滑行开**：B0-设置，**通用输入** 里打开 **滑行输入**（所有输入法共用这一个开关，和 **笔画筛选** 只能开一个，见 ST-46），回测试页点「普通文本」框。

### KB-32 [P1] 滑行输入默认关闭
- **前提**：B0，点地球键切到英文。
- **操作**：手指按在 h 上，不抬手依次划过 e、l、o，在 o 上抬起。
- **预期**：没有画出手指走过的线，也没有上屏一个词；看设置 **通用输入**，**滑行输入** 和 **笔画筛选** 都关着。
- **JVM**：`KeyboardUatTest.glideIsOffByDefault`（滑行默认关，从 h 滑到 o 不会滑行出词）；设备上不跑。

### KB-33 [P0] 英文划词：连同空格上屏，先上屏打着的词，换候选、退格整个删，后接标点
- **前提**：B0-英文，**通用输入** 里打开 **滑行输入**。
- **操作**：点 o k；手指按在 h 上，不抬手依次划过 e、l、o，在 o 上抬起；接着划 w-o-r-l-d；点候选栏里另一个词（例如第二个）；再划一次 h-e-l-l-o，点 ⌫；再划一次 h-e-l-l-o，打一个逗号 `,`。
- **预期**：划的时候键盘上画出手指走过的线；第一次抬手后框里是「ok hello 」（ok 像按了空格那样先上屏），候选栏是它可能的其他词；划 world 后是「ok hello world 」（中间只有一个空格）；点候选后刚上屏的 world 换成那个词；再划出 hello 后点一下 ⌫，整个 hello 连同空格删掉，前面那个词还在；最后划 hello 再打逗号，结尾是「hello, 」（空格移到逗号后面）。
- **JVM**：`HostCasesUatTest.aGlideGoesInWithItsSpaceItsWordChangedOrErasedWholeAMarkTakesTheSpace`（上屏、换词、退格、标点）+ `KeyboardFieldsUatTest.aGlideDrawsTheFingersTrail`（轨迹截图）；设备上不跑。

### KB-35 [P1] 快速点击、上下短划和长按不算滑行
- **前提**：滑行开，切到英文。
- **操作**：快速依次点 o、k；在 q 上往下短划；长按 e。
- **预期**：框里先是「ok」（逐个字母，没有画线）；短划打出 q 的角标符号；长按 e 照常弹出更多字母。
- **JVM**：`KeyboardUatTest.tapsShortSwipesAndHoldsAreNoGlide`（开了滑行，点按、角标下划、长按都照常，不当成滑行）；设备上不跑。

### KB-36 [P1] 密码、网址、邮箱框里不滑行
- **前提**：滑行开，切到英文，点「密码」框。
- **操作**：划 h-e-l-l-o；再点「网址」框、「邮箱」框各划一次。
- **预期**：三个框里都没有画线，也没有整词上屏（这几种框里不能滑是定下的规则，见 docs/settings.md「滑行输入」）。
- **JVM**：`KeyboardFieldsUatTest.noGlideInAPasswordAnAddressOrAnEmail`（密码/网址/邮箱不滑行，密码框截图）；设备上不跑。

### KB-38 [P1] 空格长按为"无"时，按住再松开是一个空格
- **前提**：B0（文字版，空格长按默认"无"），切到英文，点「普通文本」框。
- **操作**：点 a b；按住空格约 1 秒不动，再松开。
- **预期**：框里是「ab 」；没有弹出任何东西（语音版在密码框里也是这样）。
- **JVM**：`KeyboardUatTest.holdingTheSpaceSetToNothingIsOneSpace`（空格长按设为无时按住不弹东西，松手只打一个空格）；设备上不跑。

### KB-39 [P1] 键上的字母跟着 Shift 变大小写，三排都是
- **前提**：B0（全拼；L 右边的 ; 键这时藏着，但一样会碰到这个问题）。
- **操作**：看键盘上的字母；点一下 ⇧ 再看；再点一下 ⇧。
- **预期**：没开 Shift 时三排字母都是小写（包括最下面一排 z x c v b n m），开了 Shift 三排都是大写，再点回到小写。
  （0.3.0 发版前发现：加了 ; 键以后最下面一排一直显示大写。）
- **JVM**：`KeyboardUatTest.shiftChangesAllThreeRows`（三排字母关/开/关；图）；设备上不跑。

### KB-41 [P2] 等长按时已滑出键的下边，弹出后第一下往旁边挪不关掉
- **前提**：B0。
- **操作**：长按 q，等弹出时手指往下挪到键的下边外约 3dp；弹出后往右挪约 25dp（不往下）松手。
- **预期**：框里打出弹出框里的一个字符（不是空）：弹出后第一次挪动没有把弹出框关掉。25dp 不到一个弹出键宽，焦点通常还在手指下那个字符上（如「1」）。
- **JVM**：`KeyboardUatTest.aFirstMoveSidewaysAfterSlippingOffKeepsThePopup`（手指滑出 q 下沿后第一次横移，长按照样出结果）；设备上不跑。

## 笔画筛选（键盘这一半）

前提里的 **笔画开**：B0-设置，**通用输入** 里打开 **笔画筛选**，回测试页点「普通文本」框。笔画怎么筛候选见 PY-103、PY-104、T9-57、SP-25。

### KB-42 [P1] 打着拼音时在键上画一笔：发出这一笔，不打字母
- **前提**：笔画开。
- **操作**：打 hao；从 w 往右划过三个键抬起（横）；从 t 往下划两行（竖）；从 o 往左下划（撇）；从 e 往右下划（点）；从 e 往右划再往下拐（折）；再从 x 往上划两行；接着从 x（最下一行）往下划半个键、在 e 上往右下短短点一下、在 a 上往右划半个键；再点一下 q。然后在 **输入法 → 拼音** 里把 **键盘** 选成「14 键」，打 hao，从 GH 往下划两行；再把键盘选成「九键」，点 9 2 6 4，从 2 往右下划。
- **预期**：划的时候画出手指走过的线，抬手后编码行末尾依次多出 一 丨 丿 丶 乛，候选跟着筛，没有打出字母，也没有滑行出词。往上划不算一笔，编码行不变。短的那三下也各是一笔（竖、点、横），不打出角标；点一下 q 照常打出 q。14 键、九键上一样能画（14 键上竖、九键上点）。
- **JVM**：`StrokeUatTest.aStrokeDrawnWhilePinyinIsTypedIsSentAsOneTheKeysNone`（26 键短的、长的五种笔画都只发笔画键，没有滑行，向上不发，点按照旧；门槛见 `GlideGestureTest`）、`StrokeUatTest.theNineAndFourteenKeysSendAStrokeToo`（拼音九键、14 键、18 键和双拼九键各发一笔，不发别的键）；筛候选见 `StrokeFilterUatTest`；设备上不跑。

### KB-43 [P1] 没打字、开着滑行输入、快速输入、英文和码表里划，都不是笔画
- **前提**：笔画开。
- **操作**：什么都没打时从 w 往右划过三个键；在 **通用输入** 里打开 **滑行输入**（笔画筛选跟着关掉），打 hao 后同样划一次；再打开 **笔画筛选**，长按「符」进快速输入打 ab 后划一次；换到英文打 hel 后划一次；换到五笔打 wq 后划一次。
- **预期**：没打字时划了不发笔画（按键照常）。开着滑行输入时这一划是滑行，不是笔画。快速输入里打的不是拼音，划了不发笔画。开着笔画筛选时英文、五笔里既不滑行也不发笔画（英文、码表没有笔画筛选，滑行要开滑行输入）。
- **JVM**：`StrokeUatTest.withNothingTypedGlideChosenOrEnglishNoStrokeIsSent`；设备上不跑。

## 网址和邮箱建议

### KB-44 [P0] 空的邮箱框，候选栏给出邮箱后缀，光标留在 @ 前面
- **前提**：B0，没打过邮箱地址（或在 **通用输入 → 网址和邮箱** 里全部清空过）。
- **操作**：点「邮箱」框，看候选栏；点「@gmail.com」。
- **预期**：候选栏依次是 @qq.com、@163.com、@gmail.com、@outlook.com、@126.com、@foxmail.com、@icloud.com；点后框里是 `@gmail.com`，光标在 @ 前面，接着打的名字在它前面。「普通文本」框里不出现这些。
- **JVM**：`AddressUatTest.anEmptyEmailFieldOffersTheSuffixesTheCursorStaysBeforeTheAt`（空邮箱框七个后缀依次排好，点后光标在 @ 前，普通框没有）；设备上不跑。

### KB-45 [P0] 邮箱框打了名字，点后缀补在后面，光标到末尾
- **前提**：B0，点「邮箱」框。
- **操作**：打 zhang；点候选栏的「@163.com」。
- **预期**：框里是 `zhang@163.com`，光标在末尾；地址完整后候选栏不再给后缀。
- **JVM**：`AddressUatTest.aNameTypedGetsTheSuffixTheCursorAtTheEnd`（打 zhang 点 @163.com 成 zhang@163.com、光标在末尾，之后不再给）；设备上不跑。

### KB-46 [P1] 邮箱框 @ 后面打的字母筛选后缀
- **前提**：B0，点「邮箱」框。
- **操作**：打 zhang@g，看候选栏；点给出的那一项。
- **预期**：只剩「@gmail.com」；点后框里是 `zhang@gmail.com`，光标在末尾。
- **JVM**：`AddressUatTest.afterTheAtTheLettersTypedFilter`（@g 只剩 gmail.com，点后补全）；设备上不跑。

### KB-47 [P0] 网址框：空框给 https:// 和 www.，打了主机名给 .com 等后缀
- **前提**：B0，点「网址」框（空的）。
- **操作**：看候选栏，点「https://」；再打 www.example，看候选栏，点「.cn」。清空，打 天气，看候选栏。
- **预期**：空框给「https://」「www.」；点 https:// 后只剩「www.」；打了 www.example 给 .com、.cn、.com.cn、.net、.org；点后框里是 `https://www.example.cn`，光标在末尾，候选栏不再给后缀（主机名已经有后缀了）。左边一列的 .com 键照旧。打 天气 不给后缀（汉字当不了主机名）。
- **JVM**：`AddressUatTest.aUrlFieldGetsItsStartsThenTheSuffixes`（空框两项，https:// 后只剩 www.，主机名后五个后缀，点 .cn 补在光标处，之后不再给；天气 不给）；设备上不跑。

### KB-48 [P1] 候选栏用自己改过的后缀列表
- **前提**：B0-设置。
- **操作**：**通用输入 → 网址和邮箱 → 邮箱后缀** 只留 qq.com 并添加 work.cn，拖到最前；**网址后缀** 只留 .io（或添加 .io 拖到最前）；回测试页点「邮箱」框看候选栏；点「网址」框打 github 看候选栏。改完到 **邮箱后缀**、**网址后缀** 的菜单里「重置」恢复。
- **预期**：邮箱框是 @work.cn、@qq.com；网址框第一个是 .io。
- **JVM**：`AddressUatTest.theUsersOwnListsAreOffered`（自己的两个列表照顺序出现）；设备上不跑。

### KB-49 [P1] 打过的邮箱地址会记住，常用的后缀排前面
- **前提**：B0，「记住打过的邮箱地址」开着（默认）。
- **操作**：在「邮箱」框打 zhang.san@outlook.com，点「普通文本」框再点回「邮箱」框，清空，看候选栏；打 zh 看候选栏。
- **预期**：空框第一个是 zhang.san@outlook.com，后缀里 @outlook.com 排到最前；打 zh 时 zhang.san@outlook.com 在最前，点它整个地址替换 zh。关掉「记住打过的邮箱地址」后不再给记住的地址、打的地址也不记，但已记住的留着、后缀的次数照样记；再打开，zhang.san@outlook.com 又出现。网址框只记后缀（如 .org），不记网址本身。
- **JVM**：`AddressUatTest.addressesTypedAreRememberedAndTheirSuffixesComeFirst`（离开框后记下地址和后缀次数，按开头给出、替换已打的；关掉后不给、不记，已记的留着，打开又给；网址只记后缀）；设备上不跑。

### KB-50 [P1] 无痕框和「不用我的输入习惯」时什么都不记
- **前提**：B0，浏览器开无痕窗口（或用带 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的邮箱框）。
- **操作**：在无痕的邮箱框打 secret@gmail.com，离开；回普通窗口的「邮箱」框看候选栏。再在 ⋯ 菜单里关掉「用我的输入习惯」，打 li@126.com，离开再回来。
- **预期**：无痕框里照样给后缀，但 secret@gmail.com 不出现，@gmail.com 也没有排前；关掉输入习惯后候选栏只按列表顺序给后缀、不给记住的地址，li@126.com 也没记下。
- **JVM**：`AddressUatTest.incognitoFieldsAndNoHabitsLearnNothing`（无痕框照样给、什么都不记；不用输入习惯时不显示也不记）；设备上不跑。

### KB-51 [P1] 框重新开始成别的类型，栏上不留上一个的建议
- **前提**：B0，测试页 `fields.html`。
- **操作**：点「邮箱，聚焦两秒后变成电话框」，先看到后缀，等两秒它变成电话框。
- **预期**：变成电话框时栏上的邮箱后缀立刻没了，电话框里什么也不给（换成拨号盘）；再点一次这个框（又先变回邮箱框）又给后缀。
- **JVM**：`AddressUatTest.aRestartedFieldOffersNothingOfTheOneBefore`（同一个框重新开始成电话框、普通框都清掉，改回邮箱框又给）；设备上不跑。

### KB-52 [P1] 只在离开框时记，收起键盘、转屏、框重新开始都不算
- **前提**：B0，「记住打过的邮箱地址」开着，没记过地址。
- **操作**：在「邮箱」框打 zhang@qq.c，收起键盘再点回来（或转屏、或在设置里改一个会重建键盘的选项）；打完 zhang@qq.com；点「普通文本」框；回「邮箱」框清空看候选栏。
- **预期**：只记下 zhang@qq.com 一个，@qq.com 只记一次；半截的 zhang@qq.c 没有记下。
- **JVM**：`AddressUatTest.halfAnAddressIsNotLearnedTillTheFieldIsLeft`（收起再弹出、设置重建键盘、转屏、同一个框重新开始，四种都不记半截的，离开后记完整的一次）；设备上不跑。

### KB-53 [P1] 应用自己填好的地址不记，选没选中都一样
- **前提**：B0，没记过地址。
- **操作**：点测试页的「预填的邮箱」（me@qq.com，聚焦时全选），不改内容，挪一下光标，收起键盘，换到别的框；回一个空的「邮箱」框看候选栏。
- **预期**：这个框有选区时栏上不给建议；离开后没有记下 me@qq.com，@qq.com 也没有因此排得更前。
- **JVM**：`AddressUatTest.anAddressTheAppFilledInIsNotLearnedWhateverIsSelected`（预填加全选，挪光标、收起再弹出，离开后什么都没记）；设备上不跑。

### KB-54 [P1] 网址后缀打了一半，点后缀是补完不是再加一个
- **前提**：B0，点「网址」框。
- **操作**：打 baidu.c，看候选栏，点「.com」。清空，打 sina.com.c，看候选栏，点「.com.cn」。清空，打 sina.cn.c，看候选栏。
- **预期**：只给 .com、.cn、.com.cn（.net、.org 对不上）；点后框里是 `baidu.com`，不是 `baidu.c.com`，之后不再给后缀。sina.com.c 只给 .com.cn（不在 .com 后面再加 .com、.cn），点后是 `sina.com.cn`。sina.cn.c 什么都不给（打了一半的 .c 对不上能接在 .cn 后面的后缀，也不另给整段后缀）。
- **JVM**：`AddressUatTest.aSuffixHalfTypedIsFinished`（baidu.c 只给三个，点 .com 成 baidu.com；sina.com.c 只给 .com.cn，点后成 sina.com.cn；sina.cn.c 不给）；设备上不跑。

### KB-55 [P2] 首次解锁前、超长的内容里什么都不记
- **前提**：B0；手机重启后还没解锁过（直接启动模式）。
- **操作**：锁屏界面能打字的邮箱框里打 zhang@163.com，换到别的框；解锁后在一个已有 300 多个字的邮箱框末尾打 zhang，看候选栏，打完 zhang@163.com，换到别的框。
- **预期**：解锁前打的没有记下；超长的框里不给建议，离开后也没记。
- **JVM**：`AddressUatTest.beforeTheFirstUnlockOrInALongTextNothingIsLearned`（直接启动模式下不记；光标前超过 256 个字时不给、不记）；设备上不跑。

### KB-56 [P1] 网页里共用一个名字的几个框照样分开记
- **前提**：B0，没记过地址；一个网页或 Compose 界面，两个邮箱框（第二个应用填好了 old@x.com），再一个邮箱框和一个空的邮箱框（收件人、抄送）。
- **操作**：在第一个框打 me@qq.com，点第二个框，不改，点别的框；在收件人框打 a@163.com，点抄送框不打，点主题框；回空邮箱框看候选栏。
- **预期**：记下 me@qq.com 和 a@163.com；old@x.com 没记。
- **JVM**：`AddressUatTest.fieldsThatShareANameAreToldApartByTheirText`（同一个名字、内容不同就是另一个框：预填的不记，换到空的抄送框时记下收件人的）；设备上不跑。

### KB-57 [P1] 换输入法时记下最后的框，全部清空后不再记清空前打的
- **前提**：B0，没记过地址。
- **操作**：在邮箱框打 zhang@qq.com，收起键盘，到 **通用输入 → 网址和邮箱** 点「全部清空」，回到这个框再换到别的框；然后在邮箱框打 li@163.com，切换到别的输入法；再切回来看记住的地址。
- **预期**：zhang@qq.com 没有记回来；li@163.com 记下了。
- **JVM**：`AddressUatTest.theLastFieldIsLearnedAsTheKeyboardGoesNotWhatTheSettingsForgot`（清空后回到同一个框，原来的内容不算改过；服务销毁时记下最后的框）；设备上不跑。

### KB-58 [P1] 回复全部时只记自己加的地址
- **前提**：B0，没记过地址。
- **操作**：点测试页的「预填的邮箱」（me@qq.com），把光标挪到末尾，打 `, li@163.com`，换到别的框；回一个空的「邮箱」框看候选栏。
- **预期**：记下 li@163.com，候选栏头一个是它；me@qq.com 没有记，@qq.com 也没有因此排得更前（@163.com 记了一次）。
- **JVM**：`AddressUatTest.onlyTheAddressAddedToWhatTheFieldStartedWithIsLearned`（预填 me@qq.com 再加 li@163.com，离开后只记 li@163.com，@163.com 计一次、@qq.com 不计）；`AddressBookTest.theAddressesAFieldStartedWithAreNotLearned`（ime-core）；设备上不跑。

### KB-59 [P1] 网址框里预填的网址不计后缀，自己打的才计
- **前提**：B0，没记过地址。
- **操作**：点测试页的「.org 的链接」（预填 https://www.python.org/doc?a=1），把光标挪到末尾，打 `&b=2`，换到别的框；点「网址」框，清空，打 github 看候选栏。清空，打 x.org，换到别的框；再回「网址」框，清空，打 github 看候选栏。
- **预期**：第一次 github 后面第一个仍是 .com（预填网址的 .org 没有计）；自己打过 x.org 之后，第一个是 .org。
- **JVM**：`AddressUatTest.aUrlFieldsStartingHostIsNotCountedAddedOnesAre`（预填 .org 网址只往后加参数，离开后 .org 不计、github 后仍是 .com 在前；自己打 x.org 离开后 .org 计一次、排到最前）；`AddressBookTest.aUrlFieldLeftWithTheHostItStartedWithCountsNothing`（ime-core）；设备上不跑。

### KB-60 [P1] 打到一半从最近任务切到别的应用，回来时拼音栏和候选栏是空的
- **前提**：两个应用都开过、各有一个输入框（例如联系人的新建和搜索）。
- **操作**：在 A 的框里打 `nih`，看到候选；按最近任务键，点 B 的卡片，键盘在 B 的框里弹出。
- **预期**：拼音栏和候选栏都是空的，没有 nih；接着打 a 只出 a 的候选。
- **JVM**：`HostCoreContractTest.an app unbound clears the panel its focus out dropped, before no context is active`（ime-core：应用先解绑再换框时，清空的面板在还有活动上下文时发出）；redroid 上手动跑过一次（2026-11-02）。

### KB-61 [P1] 字母上滑是快捷键，下滑是符号
- **前提**：B0，滑动是默认（上滑快捷键，下滑符号），测试页输入框里有字。
- **操作**：a 键上滑；s 键上滑；a 键下滑。密码框里 c 键上滑。
- **预期**：a 上滑全选；s 没有快捷键，打出 s；a 下滑打出键角的 @；a、c、x、v、z、y 的键角画着快捷键图标。密码框里 c 上滑什么也不做。
- **JVM**：`BaseKeyboardTest.aLettersSwipeUpIsItsShortcutAndDownItsSymbol`；设备上的冒烟见报告（真 `EditText` 里全选、复制、粘贴、撤销）。

### KB-62 [P1] 空格上滑是编辑菜单，退格上滑清除、下滑恢复
- **前提**：B0，滑动是默认，输入框里有字。
- **操作**：空格上滑不松手，左挪一格松开；框里写 `one two three`，选中 `two` 退格上滑；光标放在 `one` 后再上滑两次；退格下滑三次；打拼音 `nihao` 时退格上滑，再下滑。
- **预期**：空格上滑出一排五格（全选、剪切、复制、粘贴、撤销），粘贴在手指上方，左挪一格松开是复制。第一次上滑删掉 `two`，接着删掉光标前的 `one`，再删掉光标后的部分，框空了；每次提示"已清除"带撤销。下滑三次按倒序放回，最后 `two` 又是选中的。打拼音时上滑只清掉拼音串，什么都不上屏；下滑拼音串回来，候选照旧。清除后打了字再下滑，提示"没有可恢复的内容"。
- **JVM**：`BaseKeyboardTest.theSpaceUpIsTheEditMenuAndTheBackspaceUpAndDownClearAndRestore`（弹出编辑菜单；退格上下滑发出清除、恢复）；清除和恢复对输入框做什么、倒序放回、改动后不放回见 ime-core `EditPlanTest`；拼音串清掉不上屏、放回同样的拼音见 `HostCoreEnginesTest`（`what is typed is cleared without a commit and typed again`）；设备上的冒烟见报告。

### KB-63 [P2] 自己设的文字快捷键
- **前提**：B0，**按键字符和快捷键** 里把 Q 的快捷键设成文字「你好」。
- **操作**：Q 键上滑（滑出键外也一样）。
- **预期**：打出「你好」；Q 的右上角是缩小的「你好」。
- **JVM**：`BaseKeyboardTest.aKeysOwnTextShortcutIsWhatItsSwipeRuns`。

