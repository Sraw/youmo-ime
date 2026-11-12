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

### KB-03 [P0] 向上滑字母键打出键角的数字
- **前提**：B0。
- **操作**：手指按在 w 键上向上滑再松开。
- **预期**：框里是「2」，没有拼音候选。
- **JVM**：`KeyboardUatTest.swipingALetterUpTypesItsCorner`（上划 w 只向引擎发一次角标 2）；设备上不跑。

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
- **操作**：向上滑 q 键打出 1，再点底排的 **，**。
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
- **前提**：B0；打开一个回车是"下一项"（`IME_ACTION_NEXT`）、下面还有一个框的原生输入框，点它。模拟器上用调试包的测试宿主 `TypingHostActivity` 带 `--ez next true`（「第一项」「第二项」两个框，见 README"测试宿主"；测试页的 WebView 浏览器不理输入法的"下一项"）；真机上也可以在 Chrome 里点测试页的「下一项」框（下面是「不要提示」框）。
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
- **操作**：向上滑 w 键打 2，长按 h 键选 `/`。
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

### KB-31 [P1] 编码行嵌在键盘里时，拼音那一行是键盘自己的，打字时不挡应用、键盘不变高
- **前提**：B0-设置，**外观 → 大小与样式 → 编码行** 改成「嵌在键盘里」（默认的「浮在键盘上方」见 ST-48：没有这一行，浮条盖在应用上），测试页地址改成 `fields.html#chat`（只显示「聊天」框，它和"发送"按钮贴在页面底部，像聊天应用的输入栏），点「聊天」框：键盘弹出后它紧贴键盘上沿。
- **操作**：看工具栏上方；点 n i h a o；再点 ⌫ 五下删光。
- **预期**：没打字时工具栏上方留着一行空白（和工具栏同色）；打字时 `ni hao` 出现在这一行里，「聊天」框和「发送」按钮一点都没被挡住；整个过程中键盘上沿和「聊天」框的位置都不动。

## 滑行输入

前提里的 **滑行开**：B0-设置，**通用输入** 的 **在键上划** 里选 **滑行输入**（所有输入法共用这一个设置，和 **笔画筛选** 只能选一个，见 ST-46），回测试页点「普通文本」框。

### KB-32 [P1] 滑行输入默认关闭
- **前提**：B0，点地球键切到英文。
- **操作**：手指按在 h 上，不抬手依次划过 e、l、o，在 o 上抬起。
- **预期**：没有画出手指走过的线，也没有上屏一个词；看设置 **通用输入**，**在键上划** 是 **关**。
- **JVM**：`KeyboardUatTest.glideIsOffByDefault`（滑行默认关，从 h 滑到 o 不会滑行出词）；设备上不跑。

### KB-33 [P0] 英文划词：连同空格上屏，先上屏打着的词，换候选、退格整个删，后接标点
- **前提**：B0-英文，在 **通用输入** 的 **在键上划** 里选 **滑行输入**。
- **操作**：点 o k；手指按在 h 上，不抬手依次划过 e、l、o，在 o 上抬起；接着划 w-o-r-l-d；点候选栏里另一个词（例如第二个）；再划一次 h-e-l-l-o，点 ⌫；再划一次 h-e-l-l-o，打一个逗号 `,`。
- **预期**：划的时候键盘上画出手指走过的线；第一次抬手后框里是「ok hello 」（ok 像按了空格那样先上屏），候选栏是它可能的其他词；划 world 后是「ok hello world 」（中间只有一个空格）；点候选后刚上屏的 world 换成那个词；再划出 hello 后点一下 ⌫，整个 hello 连同空格删掉，前面那个词还在；最后划 hello 再打逗号，结尾是「hello, 」（空格移到逗号后面）。
- **JVM**：`HostCasesUatTest.aGlideGoesInWithItsSpaceItsWordChangedOrErasedWholeAMarkTakesTheSpace`（上屏、换词、退格、标点）+ `KeyboardFieldsUatTest.aGlideDrawsTheFingersTrail`（轨迹截图）；设备上不跑。

### KB-35 [P1] 快速点击、上下短划和长按不算滑行
- **前提**：滑行开，切到英文。
- **操作**：快速依次点 o、k；在 q 上往上短划；长按 e。
- **预期**：框里先是「ok」（逐个字母，没有画线）；短划打出 q 的角标符号；长按 e 照常弹出更多字母。
- **JVM**：`KeyboardUatTest.tapsShortSwipesAndHoldsAreNoGlide`（开了滑行，点按、角标上划、长按都照常，不当成滑行）；设备上不跑。

### KB-36 [P1] 密码、网址、邮箱框里不滑行
- **前提**：滑行开，切到英文，点「密码」框。
- **操作**：划 h-e-l-l-o；再点「网址」框、「邮箱」框各划一次。
- **预期**：三个框里都没有画线，也没有整词上屏（这几种框里不能滑是定下的规则，见 docs/settings.md「滑行输入」）。
- **JVM**：`KeyboardFieldsUatTest.noGlideInAPasswordAnAddressOrAnEmail`（密码/网址/邮箱不滑行，密码框截图）；设备上不跑。

### KB-38 [P1] 空格长按为"无"时，按住再松开是一个空格
- **前提**：B0，**空格键长按行为** 设为"无"（默认是语音输入），切到英文，点「普通文本」框。
- **操作**：点 a b；按住空格约 1 秒不动，再松开。
- **预期**：框里是「ab 」；没有弹出任何东西（长按空格是语音输入时，在密码框里也是这样）。
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

前提里的 **笔画开**：B0-设置，在 **通用输入** 的 **在键上划** 里选 **笔画筛选**，回测试页点「普通文本」框。笔画怎么筛候选见 PY-103、PY-104、T9-57、SP-25。

### KB-42 [P1] 打着拼音时在键上画一笔：发出这一笔，不打字母
- **前提**：笔画开。
- **操作**：打 hao；从 w 往右划过三个键抬起（横）；从 t 往下划两行（竖）；从 o 往左下划（撇）；从 e 往右下划（点）；从 e 往右划再往下拐（折）；再从 x 往上划两行；接着从 x（最下一行）往下划半个键、在 e 上往右下短短点一下、在 a 上往右划半个键；再点一下 q。然后在 **输入法 → 拼音** 里把 **键盘** 选成「14 键」，打 hao，从 GH 往下划两行；再把键盘选成「九键」，点 9 2 6 4，从 2 往右下划。最后键盘换回全键盘，横过手机（分体），打 hao，从 T 和 Y 中间的空白处往下划两行，再在空白处点一下。
- **预期**：划的时候画出手指走过的线，抬手后编码行末尾依次多出 一 丨 丿 丶 乛，候选跟着筛，没有打出字母，也没有滑行出词。往上划不算一笔，编码行不变。短的那三下也各是一笔（竖、点、横），不打出角标；点一下 q 照常打出 q。14 键、九键上一样能画（14 键上竖、九键上点）。分体时从中间空白处起笔也是一笔（竖），点一下空白处什么也不发生。
- **JVM**：`StrokeUatTest.aStrokeDrawnWhilePinyinIsTypedIsSentAsOneTheKeysNone`（26 键短的、长的五种笔画都只发笔画键，没有滑行，向上不发，点按照旧；门槛见 `GlideGestureTest`）、`StrokeUatTest.theNineAndFourteenKeysSendAStrokeToo`（拼音九键、14 键、18 键和双拼九键各发一笔，不发别的键）、`StrokeUatTest.aStrokeStartsInTheSplitGapTooATapOrAGlideThereDoNothing`（横屏分体，从中间空白起笔发一笔，点空白不发键，开滑行输入时从空白划不滑行；空白怎么认见 `SplitGapTest`）、`aSecondFingerOnALetterWhileOneRestsInTheGapTypesIt`（一指停在空白、另一指点字母照常打字母）、`aStrokeFromTheGapCancelledSendsNothingAndLeavesNoTrail`（划到一半被取消不发、线消失）、`noOtherLayoutHasAGap` 和 `theSweepFindsTheSplitGap`（不分体的全键盘、单手、14 键、18 键、九键、注音都没有空白，分体的有）；筛候选见 `StrokeFilterUatTest`；设备上不跑。

### KB-43 [P1] 没打字、选了滑行输入、快速输入、英文和码表里划，都不是笔画
- **前提**：笔画开。
- **操作**：什么都没打时从 w 往右划过三个键；在 **通用输入** 的 **在键上划** 里选 **滑行输入**，打 hao 后同样划一次；再改选 **笔画筛选**，长按「符」进快速输入打 ab 后划一次；换到英文打 hel 后划一次；换到五笔打 wq 后划一次。
- **预期**：没打字时划了不发笔画（按键照常）。选了滑行输入时这一划是滑行，不是笔画。快速输入里打的不是拼音，划了不发笔画。选了笔画筛选时英文、五笔里既不滑行也不发笔画（英文、码表没有笔画筛选，滑行要选滑行输入）。
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
- **JVM**：`HostCoreContractTest.an app unbound clears the panel its focus out dropped before no context is active`（ime-core：应用先解绑再换框时，清空的面板在还有活动上下文时发出）；2026-11-02 还在 redroid 上时手动跑过一次（没在模拟器上重跑）。

### KB-61 [P1] 字母下滑是快捷键，上滑是长按首选
- **前提**：B0，上滑、下滑是默认（上滑长按首选，下滑快捷键），测试页输入框里有字。
- **操作**：a 键下滑；s 键下滑；a 键上滑。密码框里 c 键下滑。
- **预期**：a 下滑全选；s 没有快捷键，打出 s；a 上滑打出键角的 @；a、c、x、v、z、y 的左上角（**下滑图标位置** 默认）画着快捷键图标，键角的小字在右上（**上滑图标位置** 默认）。密码框里 c 下滑什么也不做。
- **JVM**：`BaseKeyboardTest.aLettersSwipeDownIsItsShortcutAndUpItsFirst`；设备上的冒烟见报告（真 `EditText` 里全选、复制、粘贴、撤销）。

### KB-62 [P1] 空格上滑是编辑菜单，退格上滑清除、下滑恢复
- **前提**：B0，上滑、下滑是默认，输入框里有字。
- **操作**：空格上滑不松手，左挪一格松开；框里写 `one two three`，选中 `two` 退格上滑；光标放在 `one` 后再上滑两次；退格下滑三次；打拼音 `nihao` 时退格上滑，再下滑。换到 B0-九键，打 6 4，点音节栏上第一个音节，退格上滑清除，再退格下滑。开着 TalkBack（做法同 MN-13）时空格上滑，手指在编辑菜单的格子上移动。
- **预期**：空格上滑出一排五格（全选、剪切、复制、粘贴、撤销），在键盘上方（"已复制"这类提示出现的地方，手指挡不住），粘贴对着空格；和菜单重叠的提示先收起，按键出错的提示（「词库没装上」这类）和菜单开着时新来的提示藏到菜单后、菜单收起后出现；候选栏上的提示不受影响；左挪一格松开是复制。悬浮键盘、横屏时也在键盘上方。第一次上滑删掉 `two`，接着删掉光标前的 `one`，再删掉光标后的部分，框空了；每次提示"已清除"带撤销。下滑三次按倒序放回，最后 `two` 又是选中的。打拼音时上滑只清掉拼音串，什么都不上屏；下滑拼音串回来，候选照旧。清除后打了字再下滑，提示"没有可恢复的内容"。九键在音节栏选了音节后清除的，下滑也提示"没有可恢复的内容"，拼音不回来（14、18 键的音节栏一样）。TalkBack 按格子上的字读（全选、剪切、复制、粘贴、撤销），不读 select_all 这类内部名字。
- **JVM**：`BaseKeyboardTest.theSpaceUpIsTheEditMenuAndTheBackspaceUpAndDownClearAndRestore`（弹出编辑菜单；退格上下滑发出清除、恢复）；菜单在键盘上方、提示收起、左挪一格松开复制见 `SwipePopupScreenshotTest.theEditMenuIsAboveTheKeyboardAndPicksAsBefore`，出错提示藏起再回来见 `SwipePopupScreenshotTest.aFailureIsHiddenUnderTheEditMenuAndBackAsItGoes`、`UndoChipTest.theEditMenuPutsAwayWhatWasDoneAndHidesAFailureTillItGoes`、ime-core `NoticeStateTest.theEditMenuPutsAwayOrHides`，摆放规则见 ime-core `EditPlanTest.theEditMenuGoesWhereTheNoticesGo`，竖屏、紧凑、悬浮、横屏的样子见 `SwipePopupScreenshotTest` 的 `edit_menu_*` 金样；清除和恢复对输入框做什么、倒序放回、改动后不放回见 ime-core `EditPlanTest`；只给出部分光标前文本的应用里清除只删那部分、撤销放回光标处、在光标前打了字就不放回见 `EditPlanTest.aShortReadClearsWhatWasGivenAndPutsBackJustThatAtTheCursor`、`aClearAtTheLimitWithTheCursorReportedAheadKeepsWhatIsAfterTheCursor`、`aShortReadClearIsNotPutBackOnceSomethingWasTypedBeforeTheCursor`；拼音串清掉不上屏、放回同样的拼音见 `HostCoreEnginesTest`（`what is typed is cleared without a commit and typed again`），音节栏选过音节后清除的不放回见 `HostCoreEnginesTest`（`what is typed after a syllable taken off the nine keys' bar is never typed again`）、ime-core `EnginesTest.aSyllableTakenOffTheBarIsAPick`；TalkBack 读格子的字见 `SwipePopupScreenshotTest.theEditMenusCellsAreReadByTheirLabels`；设备上的冒烟见报告。

### KB-63 [P2] 自己设的文字快捷键
- **前提**：B0，**按键字符和快捷键** 里把 Q 的下滑设成文字「你好」。
- **操作**：Q 键下滑（滑出键外也一样）。
- **预期**：打出「你好」；Q 的左上角是缩小的「你好」，右上角的 1 照旧。
- **JVM**：`BaseKeyboardTest.aKeysOwnTextShortcutIsWhatItsSwipeRuns`。

### KB-64 [P1] 连着按退格删掉的一段，下滑整段放回
- **前提**：B0，上滑、下滑是默认。
- **操作**：框里写 `hello world`，光标在末尾按三下退格，退格下滑；再按住退格删掉 `world` 和前面的空格，下滑；再按两下退格，打一个 `x`（英文键盘，或拼音打 x 后点候选），再按一下退格，下滑两次；最后全选，退格上滑清除，按一下退格（框里没字了，什么也没删），下滑。
- **预期**：三下退格删掉 `rld`，下滑一次整段放回，光标回到末尾；按住连删后下滑，`world` 连同空格整段回来；打了字以后只放回打字之后删掉的那一下（`x` 回来），再下滑提示"没有可恢复的内容"；最后的下滑放回清除掉的整段，仍是全选。按键字符和快捷键里这个命令叫「恢复刚删除的」。
- **JVM**：`DeletionRunTest`（ime-core：一段连删整段放回、光标回原处；表情、国旗、组合字符按编辑器删的放回；按住连删到开头；中间打字、挪光标、引擎自己删字就重新计，打了字后不放回；左滑选中删除并入同一段；最近一次是清除就先放回清除；应用自己清空了框、成对符号一起删掉的不放回；光标前超过读的长度时按对齐处判断）；设备上的冒烟见 `dev/design/051-fix-bksp-report.md`。

### KB-65 [P2] 滑出快捷键时键上方小框里的字
- **前提**：B0，**按键时弹出显示字符** 开着；**按键字符和快捷键** 里把 Q 的下滑设成文字「我的邮箱是 someone@example.com」，W 的下滑设成 **快速输入**，E 的下滑设成 **恢复刚删除的**。
- **操作**：A、W、E、Q 各下滑过阈值不松手，看键上方的小框；键盘高度换成紧凑、悬浮键盘、横屏各看一次；拼音键盘换成 14 键，给 QW 键的下滑设同样的文字，按 Shift 后下滑看小框；再按住 ZX 看小框（Shift 仍是刚按的一次大写，没打字）。
- **预期**：小框里是「全选」「快速输入」「恢复刚删除的」「我的邮箱是 s…」，字比键上的字母小一号，和底排「符」「123」差不多；框最宽约五个字，没有横跨半个键盘、也不盖住工具栏；紧凑、悬浮时字跟着键上的字缩小。14 键上小框里的字照设的写（「someone@…」不变成大写）；字母键自己的小框照键面的大小写（ZX 键上是 X）。
- **JVM**：`SwipePopupScreenshotTest.theBubbleSaysTheShortcutFitted`（三个键的框里的字）；`GroupedKeyboardTest.aSwipesShortcutShowsAsItIsNamedNotInTheLettersCase`（14/18 键上快捷键的字不随字母大小写变，字母键的小框照键面的大小写）；规则见 ime-core `PreviewLabelTest`；样子见 `SwipePopupScreenshotTest` 的 `select_all_*`、`quick_input_*`、`restore_*`、`text_*`、`trad_simp_en` 金样。

### KB-66 [P1] 标点和快捷键图标各在自己选的位置，两项不能选同一处，第一次打开和改过设置后一样
- **前提**：新装（B0），第一次打开键盘，上滑、下滑、上滑图标位置、下滑图标位置都是默认。
- **操作**：看 a、z、s 和退格的键角；打开 **按键**，看 **上滑图标位置** 和 **下滑图标位置** 两项；**下滑图标位置** 依次选左下、右下、底部、无，每次回来看；**上滑图标位置** 选左上、左下、底部、无，每次回来看；在 **上滑图标位置** 的列表里看快捷键占着的那一项，点它；**按键字符和快捷键** 点 **翻转上下滑** 再看；都改回默认；给 s 设一个文字快捷键再清掉；给 q 设一个文字快捷键，在 **按键字符和快捷键** 页清掉 q 的长按，回测试页看 q，再恢复 q 的长按回来看，每次收起键盘再打开对比；收起键盘再打开。键盘高度选 **紧凑**，上滑图标位置选底部再看，下滑图标位置选右上再看；上滑图标位置改回右上、下滑图标位置选底部再看。**按键字符和快捷键** 页每选一个位置也看一遍。
- **预期**：两项挨着，选项都是"右上 / 右下 / 左上 / 左下 / 底部 / 无"，默认上滑图标位置右上、下滑图标位置左上。默认：a、z 的快捷键图标在左上角，@、「，」在右上角；s 只有右上角的 *；退格上滑的清除在右上、下滑的恢复在左上，空格的编辑菜单图标在右上。下滑图标位置选左下：快捷键都在左下，退格的清除在右上、恢复在左下；选右下：快捷键在右下，退格清除右上、恢复右下；选底部：快捷键在键下边中间，退格的清除在右上、恢复在下边中间；选无：快捷键都不画，下滑照样执行。上滑图标位置选左上、左下：小字和退格的清除在那个角；选底部：在字母下面；选无：不画，上滑照样打出。一项的列表里，另一项占着的位置是灰的，后面注「下滑图标在这里」/「上滑图标在这里」，点了没反应；两项都能选无。点 **翻转上下滑** 后小字和快捷键都不动（两个图标位置跟着一起交换）。数字、符号面板的键不画键角标记。每次改完，和改成这个设置后重新打开键盘一模一样；改回默认后和第一次打开时一样。设了快捷键的 s 多一个标记。q 关掉、恢复长按时，键盘不收起看到的 q 键角和收起再打开后一样。上滑图标位置选底部：小字在字母下面，退格的清除在下边中间、恢复照下滑图标位置。紧凑时「底部」画在角上，按右上、右下、左上、左下取第一个另一个方向没占着的：上滑图标底部、下滑图标左上 → 小字和退格的清除右上；下滑图标改右上 → 小字和清除右下；下滑图标底部、上滑图标右上 → 快捷键和退格的恢复右下，上滑图标左上 → 快捷键和恢复右上；标记小一号，都画出来。设置页的键跟着一起变。
- **JVM**：`KeyMarksUatTest`（`eachWhereItsSettingSays`、`onePlaceStoredInBothTheSymbolYields`、`theFirstOpenIsAsAfterAnySettingChanges`、`eitherChangedTheKeyboardIsMadeAnew`（改两项都重建键盘）、`aLongPressTurnedOffPlacesTheMarksAsAKeyboardMadeWithIt`（键盘开着时关掉 q 的长按，标记和重开键盘后一样）、`turnedTheSymbolWhereTheNewOrientationPutsIt`、`theSwipesTurnedTheMarksStay`（翻转上下滑后小字和快捷键不动））；`KeyMarkPositionSettingsTest.theTwoTogetherTheOthersPlaceGreyedOut`（两项挨着、同一组选项、另一项的位置置灰并注明、点不了）、`onePlaceStoredInBothThenTheOtherChangedTheSummaryIsTheKeyboards`（备份里两项同位置，改另一项后摘要和键盘一致）；`AppPrefsTest.theSymbolStoredInTheShortcutsPlaceStoredWhereItReads`（这种备份下次启动时存成纠正后的位置）；ime-core `KeyMarksTest`（两个设置 6×6 × 横屏 × 紧凑 × 有无小字和快捷键的全部组合；读出时同一位置的纠正）；`KeyMarksScreenshotTest`（全键盘、九键、大千、手写每个位置的截图，全键盘紧凑）；`KeyShortcutsPageScreenshotTest.theMarksWhereTheirSettingsSay`（设置页每个位置）；设备上新装后看一遍，再切几个位置看。

### KB-67 [P1] 什么设置下键上的字、小字、快捷键图标都不叠在一起
- **前提**：任意。
- **操作**：上滑图标位置和下滑图标位置的每种能选的组合（6×6 去掉两项相同的，都选无可以，共 31 种），在全键盘、九键、14 键、18 键、双拼九键 / 14 / 18、英文九键、大千、注音九键、手写、五笔两行字根、五笔键名、数字键盘、符号页上看，键盘高度标准和紧凑、悬浮、单手、横屏都看。
- **预期**：每个键上字（或图标）、键角小字、快捷键图标两两不叠。同一类东西整个键盘一样大；碰上时：标记在自己的角里挪（不换到别的角），再字那一类整个键盘一起缩小（最多 6%），再标记那一类一起缩小（最小到 0.875）；还碰上就不画这个标记（先省默认的，后省自己设过的；各按功能键下滑的、快捷键、键角小字的次序；自己设的文字放不下先画成一个字加「…」，再不行画两条横线的图标，见 KB-72），滑动照常能打出它。什么都不省（KeyMarksEverywhereUatTest 打印 left out (0)；只差不到半个像素的不算碰上）。
- **JVM**：`KeyMarksEverywhereUatTest.noKeyDrawsTwoThingsOverEachOtherUpright`、`noKeyDrawsTwoThingsOverEachOtherTurned`（上面每种键盘的每个键 × 31 种组合 × 标准 / 紧凑 / 悬浮 / 单手 / 紧凑单手 / 横屏 / 横屏紧凑，系统字号不影响，按画出来的墨迹量，两两不相交，省掉的标记打印出来）；`KeyMarksScreenshotTest.noRoomAnotherCornerOfTheKey`（紧凑单手全键盘右下、五笔左下，悬浮大千底部的图；测试名是旧规则（换到另一个角）留下的，现在图里标记只在自己的角里挪、放不下就不画，不换角）；keyboard-scene `KeyMarkYieldTest`（让的次序）。

### KB-68 [P2] 拼音键字母大小写三档
- **前提**：拼音（全键盘）、英文都已启用。
- **操作**：**按键 → 拼音键字母大小写** 依次选总是大写、总是小写、跟随 Shift，每次回到测试页看全键盘拼音的字母、按一下 Shift 再看、按住 q 看气泡、打一个 q；切到英文看；拼音键盘换成 14 键、九键各看一遍；开 TalkBack 摸 q、QW、2。
- **预期**：不用重开键盘就变。总是大写：拼音字母大写，Shift 关着也是，气泡 Q，打出 q；总是小写：Shift 开着字母也是小写（Shift 键自己是亮的），气泡 q，打出 Q；跟随 Shift：和以前一样。14 键、九键的字母组按设置大小写（跟随 Shift 时大写）。英文怎么选都跟着 Shift。键角小字不变。TalkBack 读法三档一样。设置页 **按键字符和快捷键** 的键、主题预览跟着变。
- **JVM**：`LetterCaseScreenshotTest`（全键盘总是大写、总是小写 + Shift 开、跟随 Shift 开；14 键、九键总是小写；英文不受影响；读屏文字不变；气泡）；keyboard-scene `LetterCaseRuleTest`、`ExpandTest.letterCaseSettingCasesTheLetterFacesOnly`、`aScreenReaderHearsTheLetterAsFollowingShiftShowsIt`（这三个在 `:lib:keyboard-scene:check` 里跑，`uat.py jvm` 不记录）。

### KB-69 [P1] 快捷键「滑选」：滑到后左右挪按字选，松手留着
- **前提**：B0，上滑、下滑是默认，**按键字符和快捷键** 里把 Q 的 **上滑** 设成命令 **滑选**。
- **操作**：框里写 `hello world`，光标放在 `hello` 后面；Q 上滑过阈值不松手，往左挪约 3 个字的距离（约 30 dp），再往右挪到起点右边约 2 个字，松手。再清空框，打拼音 `nihao`（不选候选），Q 上滑不松手往左挪约 2 个字，松手。框里写 `a👨‍👩‍👧`，光标在末尾，Q 上滑不松手往左挪一个字，松手。看 Q 的键角和设置页的命令列表。
- **预期**：Q 上滑过阈值轻振一下，键上方小框是「滑选」；往左挪选中 `llo`，往右挪选区缩回、越过光标后选中 ` w`（空格和 w），松手后仍选中 ` w`，框里还是 `hello world`，没有打出 q。打着拼音时先上屏「你好」，再选中「你好」。一家人的表情一次整个选中。Q 的右上角（上滑图标位置）画着滑选的图标，不再有小字 1；命令列表里「编辑菜单」后面是「滑选」。
- **JVM**：`KeyShortcutsHostTest.swipeSelectSelectsFromTheCursorAsTheFingerMovesSideways`、`KeyShortcutsHostTest.swipeSelectCommitsWhatIsTypedFirst`、`KeyShortcutsHostTest.swipeSelectStepsOverAnEmojiWhole`（真 `KotlinHost` + 真 `EditText`）；规则（已有选中时接着选、上一次的起点、读不到框时按单位、上屏前挪的步数补上）见 ime-core `SwipeSelectionTest`，步数见 `SwipeStepsTest`；设备上 2026-11-10 在模拟器上手动看过一遍。

### KB-70 [P1] 空格左右滑动光标后，再上下挪换行
- **前提**：B0，点「多行」框（`multiline`），写三行 `aaa`、`bbb`、`ccc`（回车换行），光标在末尾。
- **操作**：手指按住空格向左滑一点，不抬手往上挪约一个键高，再往上一点，再往下挪一点后松开。另做一次：空格直接上滑。再到「短文本」单行框里横滑后上挪。
- **预期**：横滑时光标左移；往上挪光标到上一行、再上一行，往下回一行，每行轻振一下；不打出空格。直接上滑还是弹编辑菜单，光标不动。单行框里上下挪光标不动，横滑照旧。
- **JVM**：`KeyboardUatTest.spaceSlidesUpAndDownAfterSideways`、`spaceSlidesNoLine`（快速输入和单行框）、`spaceSlidesDiagonallyMovesBothWaysInOneSample`。

### KB-71 [P1] 空格竖滑到输入框最上、最下时不跳到别的框
- **前提**：同 KB-70，「多行」框下面还有「短消息」框。
- **操作**：
  1. 空格左滑一点，不抬手一直往上挪过第一行（五六行那么远）；再一直往下挪过最后一行后松开。
  2. 光标放回第三行末尾，空格左滑一点，一下快速往上划约两行远（约 60 dp）后马上松开。手动做两行常常是分开发出的，测不到快滑（两行要在 80 ms 内发出）：这一步以脚本那遍（一次采样挪 60 dp）为准，手动通过不算数。
- **预期**：
  1. 往上挪到第一行后光标到框的最前面，再挪也停在那里；往下挪到最后停在框的最后面；焦点一直在「多行」框，不会跳到「短消息」或上面的框。
  2. 光标停在第一行上（挪了两行就走两行），不少走一行停在第二行，也不直接跳到框的最前面；再往上挪一行才到框的最前面。
- **JVM**：ime-core `SpaceCursorTest.whetherALineKeyIsSent`（框首不发 Up、框尾不发 Down）、`SpaceCursorTest.aHeldLineGoesToTheFieldsEdge`（一口气挪过第一行时，压下的那一行改去框首、框尾）、app `SpaceLineEdgeTest.aRushedLineGoesToTheEdgeOnlyWhileNoneLanded`（框还没动时才去框首、框尾；已走了一行的，剩下的行照发，不少走、不跳过）。

### KB-72 [P1] 自己设的长文字放不下时画短，再不行画两条横线的图标；自己设的压过默认的
- **前提**：B0；**按键字符和快捷键** 里把 j 和 k 的下滑都设成文字「现在怎么样了」；手机宽 360 dp（模拟器 `wm density 480`）。
- **操作**：下滑图标位置依次选默认左上、底部，每次回测试页看 j、k；键盘高度选紧凑并开单手、悬浮各看一次；再把 a 的下滑设成文字「你好」，上滑图标位置选左下、下滑选底部，看 a。
- **预期**：j、k 上都有下滑的标记：放得下画「现在…」，放不下画「现…」，再放不下画一个两条横线的图标（上长下短，和别的快捷键图标一样大）；字和别的键的快捷键字一样大，别的键的标记不跟着缩小，字母不挪位置（整个键盘的字母可能小一点，最多 6%）；连这个图标都放不下才不画。a 上自己设的「你好」碰上默认的 `@` 时，省的是 `@`。滑动都照常打出全文。
- **JVM**：keyboard-scene `SwipeTextsDrawnTest`（每种键盘、每种高度、每个位置，1–3 个字，另一方向关着和开着默认标记；k 放得下时不画短；一个键画短时别的键不变）；ime-core `SwipeSlotsTest.aMarkShorterIsItsFirstCharacterAndAnEllipsis`。

### KB-73 [P0] 所有键盘的 ， 长按相同
- **前提**：B0（新装或恢复默认），中文标点。
- **操作**：依次在全键盘、14 键、18 键、注音全键盘、注音九键（底行 ， 和 ㄖㄥㄩ 右边的键）、手写上长按 ，。再切到英文标点各长按一次。
- **预期**：每个弹窗都是 `。？！：；“‘（）～`，焦点在 `。`；英文标点时都是 `. ? ! : ; " ' ( ) ~`。
- **JVM**：app `KeysEverywhereUatTest.commaIsOneKey`。

### KB-74 [P0] 改一次 ，，所有键盘都变
- **前提**：同 KB-73。
- **操作**：**设置 → 按键 → 按键字符和快捷键 → 功能键**，点 ，：删掉 `~`，添加 `…`；回到 KB-73 的各键盘长按 ，；再回功能键页点 ， 的 **恢复此键默认**，各键盘再长按一次。
- **预期**：改后每个键盘的 ， 都没有 `～`、最后一项是 `…`；恢复后都回到 KB-73 的列表。
- **JVM**：app `KeysEverywhereUatTest.commaEditedOnceEverywhere`。

### KB-75 [P0] ，。 的"长按首选"在所有键盘都画、都打
- **前提**：同 KB-73；**上滑** 开。
- **操作**：功能键页把 ， 的上滑设成 **长按首选**；在全键盘、手写、注音九键 ㄖㄥㄩ 右边的键上看 ， 的键角，各上滑一次 ，；切英文标点再看、再滑。
- **预期**：中文标点时键角画 `。`，上滑打出 `。`；英文标点时画 `.`、打出 `.`。
- **操作（续）**：功能键页把 ， 的首选设成 `！`，九键页把 5 的首选设成 `/`，全键盘页把 q 的首选设成 `Q`；分别长按 ，、九键 5、全键盘 q，不动，松手。
- **预期（续）**：弹窗一打开焦点就在首选上（`！`、`/`、`Q`，首选在行上面的符号层里或键没有行也一样）；不动松手打出首选。
- **JVM**：app `KeysEverywhereUatTest.commaFirstDrawnAndTyped`、`.heldAndLetGoTypesTheFirst`、`.heldAndLetGoTypesTheFirstOnANarrowKeyboard`。

### KB-76 [P0] 九键 1：按中英文标点转换，英文与拼音相同，改了共享
- **前提**：B0，拼音的键盘选九键，英文的键盘也选九键；九键 1 的长按没改过；中文标点开。
- **操作**：
  1. 拼音九键，不打字时长按 1，看弹窗，选 `。`。
  2. 点 6 4 后上滑 1。
  3. 切到英文九键，长按 1，选 `.`。
  4. 回拼音九键，在 **按键字符和快捷键 → 九键** 页把 1 的符号改成只剩 `.` 和 `@`；切英文九键长按 1。
  5. 连按：把「连按选字母」设为"中"，拼音九键不打字时快速连按 1 五下；关掉中文标点再按五下；切英文九键再按五下。
- **预期**：
  1. 弹窗是 `1 …… ： ； ～ 。 - @ 、 “`，上屏 `。`（按中英文标点转换）。
  2. 选中第 1 个候选。
  3. 弹窗是同一组的英文形式 `1 ^ : ; ~ . - @ \ "`，上屏 `.`。
  4. 英文九键的 1 也只剩 `1 . @`（行里的 `1` 还在）。
  5. 中文标点下键面 `，。？！`，依次打出 `，` `。` `？` `！` `，`；中文标点关和英文下键面 `,.?!`，依次是 `,` `.` `?` `!` `,`（英文不再有 `'` `-`，它们在长按里）。
- **JVM**：app `KeysEverywhereUatTest.nineKeysOneThroughThePunctuationEverywhereAndSharedOnceEdited`、`.nineKeysOnesTapCyclesThePunctuationAsItMapsInBothLanguages`。

### KB-77 [P1] 九键 5：符号共享，英文与拼音的长按相同
- **前提**：拼音、英文、双拼（小鹤）的键盘都选九键。
- **操作**：**按键字符和快捷键 → 九键** 页给 5 添加 `%`；分别在拼音、英文、双拼九键上长按 5。
- **预期**：拼音和英文完全相同：`J K L 5 j k l` 加上含 `%` 的符号；双拼是这个方案九键 5 的字母加同一份符号。
- **JVM**：app `KeysEverywhereUatTest.nineKeysFiveSymbolsSharedLettersByInputMethod`。

### KB-78 [P1] 注音九键 ㄖㄥㄩ 右边的键就是 ，：长按、滑动、分词
- **前提**：注音九键；功能键页 ， 的上滑是 **长按首选**，下滑是空。
- **操作**：不打字时长按这个键；上滑它；打两个注音符号后下滑它；再打两个注音符号后上滑它。
- **预期**：长按与底行 ， 是同一列表；不打字时上滑打出 `。`；打字中下滑（空槽）等于点这个键，就是分词；打字中上滑先上屏首选再打出 `。`。打字中键面显示「分词」，键角画 `。`。
- **JVM**：app `KeysEverywhereUatTest.zhuyinNinesToneAndSlot`、`.zhuyinNinesToneOff`。

### KB-79 [P1] 14 键 QW 的长按首选改了，按下气泡和键角一起变
- **前提**：拼音的键盘选 14 键。
- **操作**：**按键字符和快捷键 → 14 键** 页把 QW 的 `!` 设为首选；回键盘按住 QW 不放，看气泡；松开；上滑 QW。再在全键盘页改 q 的长按，回 14 键看 QW。
- **预期**：键角和按下气泡的角都显示 `！`（中文标点）；上滑打出 `！`。改全键盘 q 不影响 QW。
- **JVM**：app `KeysEverywhereUatTest.fourteenKeysQwFirstDrawnAndTyped`。

### KB-80 [P2] 注音全键盘的键加了长按后能打能画
- **前提**：注音全键盘，键都没改过。
- **操作**：长按 ㄅ；在 **按键字符和快捷键 → 注音全键盘** 页给 ㄅ 添加 `1`，上滑设成 **长按首选**；回键盘长按 ㄅ、上滑 ㄅ。
- **预期**：改之前长按没有弹窗、键上没有角标；改之后长按弹出 `1`，键角画 `1`，上滑打出 `1`。
- **JVM**：暂无（ime-core `KeyResolverTest` 管 resolver 一半），设备上跑。

### KB-81 [P2] `"` 显示 “，上屏由引擎交替
- **前提**：中文拼音，中文标点；功能键页 ， 的上滑是 **长按首选**，并把 `"` 设为首选。
- **操作**：长按 ， 看 `"` 那一项；上滑 ， 两次。
- **预期**：弹窗和键角都显示 `“`；两次上滑依次打出 `“`、`”`（引擎交替），第二次与键角显示的不同，这是已知的。
- **JVM**：暂无，设备上跑。

### KB-82 [P2] 一层 32 项的长按都在屏幕里，能选到最后一项
- **前提**：B0；**设置 → 按键 → 按键字符和快捷键 → 功能键**，点 ，，把符号加到 32 项（如 `Ａ0 Ｂ0 … Ｚ0 Ａ1 … Ｆ1`），确定。
- **操作**：1. 竖屏最窄的机型（320 dp 宽）开右手单手模式，长按 ，，不松手滑到最后一项再松手。2. 横屏同样做一次。
- **预期**：32 项全部显示，没有一项伸出屏幕；松手打出最后一项。
- **JVM**：app `PanelKeysUatTest.aLayerOf32OnTheNarrowestOneHanded`、`.aLayerOf32Landscape`。

### KB-83 [P0] 显示即所打：弹窗、角标、上滑气泡显示的就是打出的字符
- **前提**：B0；上滑、下滑都开；中文拼音，中文标点。
- **操作**：
  1. 在全键盘、14 键、18 键、九键、注音全键盘、注音九键、手写、数字键盘，以及符号面板、表情、颜文字、展开候选、表情搜索里，
     长按 ，（九键再长按 1），看弹窗各项和焦点项；看 ， 的键角；上滑 ，，看气泡和打出的字。
  2. 键盘开着，切到英文标点（点工具栏的标点状态或 Ctrl+.），不收键盘，再看 ， 的键角、再长按 ，。
  3. 切到英文输入法，在全键盘和九键上重复 1。
- **预期**：
  1. 中文标点时弹窗、焦点项、键角、上滑气泡都是全角（`。？！：；“‘（）～`，九键 1 有 `…… ： ； ～ 。 、 “`），选哪项、滑出来的就是显示的那个字。
  2. 切换后不用收键盘，键角立刻变成 `.`，弹窗变成半角 `. ? ! : ; " ' ( ) ~`。
  3. 英文下全是半角，和打出的一致。
  4. 例外：符号面板格子自己的长按（如 `$` 的 `¥ € £`）原样显示、原样上屏，不随标点变。设置里的编辑器显示存的形式（`.`），不按当前标点转换。
     `"` 显示 “，打出由引擎交替 “/”，第二次可能是 ”：允许的例外（KB-81）。
  5. 九键 1 键面：中文标点时 `，。？！`，切到英文标点后立刻变成 `,.?!`；点按打出的和键面一致（KB-76）。
- **JVM**：app `DisplayIsTypedUatTest.everyKeyShowsWhatItTypes`、`.toggledWithTheKeyboardUp`。
