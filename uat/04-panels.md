# 04 数字、符号、表情面板

区域代号 `PAN`。

### PAN-01 [P0] 123 打开数字键盘，返回 回到字母
- **前提**：B0。
- **操作**：点 **123**；点 4、2；点 **返回**。
- **预期**：数字键盘上面三行是 `1 2 3 ⌫`、`4 5 6 空格`、`7 8 9 =`，左边是和这三行一样高的一列，露出 `+ - * /`（上下滑还有别的符号，同九键左列），底排是 `符 返回 0 . ↵`（⌫ 在右上角，同九键；底排左边两格同字母键盘：符 在第一格，返回 从 123 的位置起，占 1 4 7 那一列；0 在 8 下面、. 在 9 下面，和上面的数字一样宽）；框里是「42」；点 返回 后回到 26 键字母键盘。
- **JVM**：`KeyboardUatTest.numberPadAndBack`（四行键、⌫ 在 3 右边、点 4 2 发出的键、返回 回字母；图）；设备上不跑。

### PAN-02 [P0] 数字、电话输入框直接是数字键盘
- **前提**：B0。
- **操作**：点「数字」框；再点「电话」框。
- **预期**：两次弹出的都是数字键盘，不是字母键盘。
- **JVM**：`PanelUatTest.numberAndPhoneFieldsOpenTheNumberPad`（数字框、电话框都直接是数字键盘）；设备上不跑。

### PAN-03 [P1] 数字键盘的 符 打开符号面板
- **前提**：B0，点 **123**。
- **操作**：点底排第一格的 **符**。
- **预期**：打开符号面板。
- **JVM**：`PanelUatTest.numberPadSymbolsKeyOpensThePanel`（数字键盘点 符 打开符号面板）；设备上不跑。

### PAN-04 [P0] 符号面板：点符号打出并回到键盘
- **前提**：B0。
- **操作**：点 **符**；左边点"中文"分类；点「《」。
- **预期**：符号面板左边是分类：常用、中文、英文、括号、箭头、数学、序号、图形、全角（用过符号以后，最上面多一个"最近"）；点「《」后框里是「《》」（成对，见 PAN-30），面板关掉回到字母键盘。
- **JVM**：`PanelUatTest.symbolPanelTypesAndCloses`（分类顺序、「《」上屏并回到键盘、用过后多「最近」；图）；设备上不跑。

### PAN-29 [P1] 符号面板打开时的分类；英文一栏
- **前提**：B0（没用过符号面板：清掉数据或新装）。
- **操作**：点 **符**，看左边哪个分类亮着；在「常用」里点「？」。再点 **符**；点「箭头」，点「→」；再点 **符**。最后点「英文」，看格子。
- **预期**：第一次打开亮的是「常用」；用过「？」后再打开亮的是「最近」（第一个是「？」）；点过「箭头」以后再打开就是「箭头」（记住上次看的）。再强行停止输入法（或重启手机）后点 **符**，仍是「箭头」。「英文」里没有 1–0 十个数字，第二行开头是英文的 `,` `.`。
- **JVM**：`PanelUatTest.thePanelOpensOnTheRecentlyUsedTillACategoryIsPicked`（三次打开亮的分类）、`PickerDataTest.theEnglishTabHasNoDigitsButTheEnglishCommaAndFullStop`（英文一栏没有数字、有 `,` `.`）、`SymbolPanelTest.tillACategoryIsPickedItOpensOnTheRecentlyUsedOnceThereAreAny`、`PanelUatTest.theCategoryPickedIsKeptForTheNextStart`（点过的分类重新建键盘后还在；只是重新打开不算点过；记着的分类已没有时照没点过处理）、`SymbolPanelTest.aRememberedPickOpensAsAPickMadeInThisRun`、`PickerDataTest.everySymbolCategoryIsFoundAgainByItsId`（每个分类按自己的 id 记、按 id 找回原处）；设备上不跑。

### PAN-30 [P0] 符号面板里点的括号成对，⌫ 一起删
- **前提**：B0（**按键 → 括号成对** 开着，默认）。
- **操作**：点 **符**，点"中文"分类，点「《」；看框和光标；再点 ⌫。换"括号"分类，逐个点 〖 〘 ❨ ❲ ❴ ⟨ « ‹ ❛ ❝ 「 （ 【，每点一个后点 ⌫。
- **预期**：点「《」后框里是「《》」，光标在两个中间；⌫ 把两个一起删掉，框空了。其余每个左括号、左引号也一样：成对出现、光标在中间、⌫ 一起删（和键盘上打的括号一样，KB-26）。
- **JVM**：`PanelUatTest.aBracketFromThePanelGoesInPairedAndBackspaceTakesBoth`（「《|》」、⌫ 后空）；ime-core `AutoPairsTest.theSymbolPanelsBracketsPairEach`（新加的十对每对成对、跨过、⌫ 一起删）；设备上点「中文」第一页的「（」，看「（）」、⌫ 后空（`uat.py run`）。

### PAN-31 [P1] 面板里点右括号，跨过成对补上的那个
- **前提**：B0。
- **操作**：点 **符** →"中文"→「「」；再点 **符** →"中文"→「」」。
- **预期**：第一次后框里「「」」光标在中间；第二次不再多一个「」」，光标跳到「」」后面，框里仍是「「」」。
- **JVM**：`PanelUatTest.theClosingOneFromThePanelStepsOverTheOnePutIn`；设备上不跑。

### PAN-32 [P1] 电话号码框里括号不成对
- **前提**：B0，测试页的「电话」框（数字键盘）。
- **操作**：点 `(`，再点 `)`。
- **预期**：点 `(` 后框里只有 `(`，没有补上 `)`；点 `)` 后框里是 `()`，光标在最后（拨号这类应用边打边排号码，多出来的括号碍事）。
- **JVM**：`PanelUatTest.inAPhoneNumbersFieldThePadsBracketsAreAlone`；ime-core `FieldPolicyTest.aPasswordPairsNothingAndAShownOneIsStillOne`（电话号码框不成对）；设备上不跑。

### PAN-33 [P1] 括号成对关掉、密码框里，面板的括号不成对
- **前提**：B0-设置，**按键 → 括号成对** 关掉；另在测试页的「密码」框做一次（括号成对开着）。
- **操作**：点 **符** →"中文"→「《」。
- **预期**：两种情况框里都只有「《」，没有补上「》」。
- **JVM**：`PanelUatTest.offOrInAPasswordThePanelsBracketIsAlone`；设备上不跑。

### PAN-34 [P1] 网址框里面板的 ( 和键上打的 ) 跨过
- **前提**：B0，测试页的「网址」框；全键盘 p 的长按里加上 `)`（**按键 → 按键字符和快捷键**）。
- **操作**：点 **符** →"英文"→ `(`；再长按 p 选 `)`。
- **预期**：点 `(` 后框里 `()`、光标在中间；选 `)` 后跨过补上的那个，框里仍是 `()`、光标在最后，不是 `())`（网址框里键上的 `)` 原样打出，不经输入法，照样跨过）。
- **JVM**：`PanelUatTest.inAnAddressTheKeysClosingOneStepsOverThePanels`；设备上不跑。

### PAN-05 [P1] 锁住符号面板连续选几个
- **前提**：B0，点 **符**。
- **操作**：点下面的锁；点「→」「←」「↑」（在"箭头"分类）。
- **预期**：锁上后点符号面板不关；框里是「→←↑」。
- **JVM**：`PanelUatTest.lockedPanelStaysForSeveral`（锁上后点 → ← ↑ 面板不关、框里是「→←↑」）；设备上不跑。

### PAN-07 [P1] 最近用过的符号
- **前提**：B0，点 **符**，在"图形"里点「★」。
- **操作**：再点 **符**，点"最近"分类。
- **预期**："最近"里第一个是「★」。
- **JVM**：`PanelUatTest.recentHasTheLastSymbolFirst`（点过「★」后"最近"里第一个是它）；设备上不跑。

### PAN-08 [P1] 符号面板的返回和 12/34
- **前提**：B0，点 **符**。
- **操作**：点 **12/34**；再打开符号面板，点 **返回**。
- **预期**：12/34 打开数字键盘；返回回到字母键盘。
- **JVM**：`PanelUatTest.panelsNumbersKeyAndBack`（12/34 打开数字键盘、返回回到字母）；设备上不跑。

### PAN-09 [P0] 表情面板：分类和打出表情
- **前提**：B0。
- **操作**：点工具栏右边的笑脸；点"动物与自然"标签；点「🐱」。
- **预期**：上面是分类标签（最近使用、笑脸、人物、动物与自然、食物与饮料、旅行与地点、活动、物品、符号、旗帜），列表可上下滑；框里出现「🐱」。
- **JVM**：`PanelUatTest.emojiPanelTabsAndTyping`（标签数和顺序、「动物与自然」里点 🐱 上屏；彩色表情的图）；设备上不跑。

### PAN-10 [P1] 默认肤色
- **前提**：B0-设置，**剪贴板与表情 → 默认表情肤色** 选第三种肤色。
- **操作**：回到测试页，打开表情面板，在"人物"里找"竖大拇指"并点它。
- **预期**：打出的是带那种肤色的 👍（不是黄色）。
- **JVM**：`PanelUatTest.theDefaultSkinTone`（默认肤色选第三种后人物里的 👍 带那种肤色、打出 👍🏼）；设备上不跑。

### PAN-11 [P1] 表情面板回到键盘
- **前提**：B0，打开表情面板。
- **操作**：点面板下面的 **返回**。
- **预期**：回到字母键盘。
- **JVM**：`PanelUatTest.emojiPanelBackToLetters`（表情面板 返回 回字母）；设备上不跑。

### PAN-12 [P1] 表情面板按拼音搜表情
- **前提**：B0，打开表情面板。
- **操作**：点下面的放大镜；点 x i a o；点 ⌫ 四下删光；点 d z；点上面一排里的「👍」；点 **返回**。
- **预期**：打 xiao 时上面一排列出和"笑"有关的表情（例如 😄）；打 dz 时出「👍」（点赞）；点后框里出现「👍」，它也出现在"最近使用"里；返回后回到表情列表。
- **JVM**：`PanelUatTest.emojiSearchByPinyin`（xiao 出 😄、删光后 dz 出 👍、点后上屏、最近使用第一个是它（测试用的小 emoji.data 里有这两个词）；图）；设备上不跑。

### PAN-14 [P1] 数字键盘的空格也能滑动移光标
- **前提**：B0，点「长文本」框，光标在末尾。
- **操作**：点 123；按住空格往左滑一段再松开；点 1。
- **预期**：光标往前移、不打出空格，1 打在光标挪到的地方，不在末尾。（关掉 **划动空格键以移动光标** 后光标不动，由 ST-18 管。）
- **JVM**：`PanelUatTest.numberPadSpaceSlidesTheCursor`（数字键盘空格左滑只发出 ←、不打空格，再点 1 发出 1；光标挪多远由引擎把 ← 交给框）；设备上不跑。

### PAN-15 [P1] 本应用输入框里选的表情不进"最近使用"
- **前提**：B0-设置。
- **操作**：点设置页顶上的搜索框，打开表情面板，点一个没用过的表情（例如 🐱）；回测试页「普通文本」框打开表情面板看"最近使用"；再在这里点 🐱，关掉面板再打开看"最近使用"。
- **预期**：在设置搜索框里点的 🐱 不在"最近使用"里（本应用自己的输入框不记录，见 docs/keyboard.md）；在测试页点的 🐱 在。
- **JVM**：`PanelUatTest.ownFieldsKeepNoRecentEmoji`（本应用框里点的 🐱 不进最近使用，别的应用框里点的进）；设备上不跑。

### PAN-16 [P0] 日期、时间框的数字键盘带 - / :
- **前提**：B0，一个应用里 `inputType` 为日期或时间的输入框（测试页的 date/time 在 Chrome 里弹的是它自己的选择器，不用键盘）。
- **操作**：点这个框。
- **预期**：直接是数字键盘，左边一列是 - / :，没有 + *；9 右边 = 的位置空着：什么都不画，露出键盘底色，点了没反应；0 右边照常是 `.`。
- **JVM**：`FieldPadUatTest.dateAndTimeFieldsGetTheirSeparators`（日期、时间、日期时间三种框的左列都是 - / :，有数字和 `.`，没有字母和 + * =；= 的空位点一下不发键、不打字，中间和角上同是键盘底色）；设备上不跑。

### PAN-17 [P0] 电话框的数字键盘有 + * #
- **前提**：B0。
- **操作**：点「电话」框；再点「电话（inputmode=tel）」框。
- **预期**：数字键盘左边一列先是 + * #，往下滑还有 - ( ) ,；右上角是 ⌫；9 右边 = 和 0 右边 . 的位置空着；没有 /。
- **JVM**：`FieldPadUatTest.phoneFieldsGetPlusStarAndHash`（电话框的左列是 + * # - ( ) ,，没有 / = .，两处空位点了不发键）；设备上不跑。

### PAN-18 [P1] 数字密码框是完整的数字键盘
- **前提**：B0。
- **操作**：点「数字密码」框。
- **预期**：和点 123 打开的一样，是完整的数字键盘（左列 + - * / % , : ( ) #，还有 = 和 .）；框不收的符号点了由框自己滤掉。
- **JVM**：`FieldPadUatTest.pinFieldsGetTheFullPad`；设备上不跑。

### PAN-19 [P1] 小数框、能填负数的框、整数框都是完整的数字键盘
- **前提**：B0。
- **操作**：点「小数」框、「整数」框、「数字」框，各看一眼键盘。
- **预期**：三个框都是完整的数字键盘，「.」和左列的「-」都在（框声明的类型常和它实际收的不一致，比如记账应用的金额框说是小数却收负号，所以不按类型去掉键；框不收的键由框自己滤掉）。
- **JVM**：`FieldPadUatTest.everyNumberFieldGetsTheFullPadItsMinusSent`（只说是数字、小数、带负号、两样都要的框都是完整的数字键盘，点左列的 - 发出 -）；设备上不跑。

### PAN-35 [P1] 说是小数却收负号的框能打 -，带负号的框也能
- **前提**：B0；装了输入测试台（uat/TESTBED.md）。
- **操作**：点测试页「小数（inputmode=decimal）」框（声明小数、什么都收，同记账应用的金额框），点 - 5；再到输入测试台 **普通 EditText 输入类型** 页点「带符号数字」框，点 - 3。
- **预期**：两个框都是完整的数字键盘（左边一列 + - * / 往下还有别的，9 右边 =，0 右边 .）；小数框里是「-5」，带符号数字框里是「-3」。
- **JVM**：`FieldPadUatTest.everyNumberFieldGetsTheFullPadItsMinusSent`（各种数字框都是完整的数字键盘，点 - 发出 -）；设备上照跑：真的应用框收没收到 -。

### PAN-20 [P0] 离开数字框回到原来的键盘
- **前提**：B0。
- **操作**：点「普通文本」框；点「电话」框；点回「普通文本」框。再点「电话」框，点 返回 再点 123；点「普通文本」框点 123。
- **预期**：回到普通文本框是原来的字母键盘；电话框里 返回 后再点 123 回到电话的数字键盘（有 #）；普通文本框的 123 是完整的数字键盘（有 =）。
- **JVM**：`FieldPadUatTest.leavingANumberFieldGoesBackToTheLetters`（离开数字框回到字母；框里的 123 回到这个框的数字键盘，普通框的 123 是完整的）；设备上不跑。

### PAN-21 [P1] 按住面板里的符号，有气泡
- **前提**：B0。
- **操作**：点「符」，按住第一个符号不放，看一眼再抬手。
- **预期**：按住时符号上方出现气泡（和键盘上的按键一样，由绘制引擎画）；抬手打出这个符号。
- **JVM**：`PanelUatTest.aSymbolHeldShowsItsBubble`（按住时出现引擎画的气泡，抬手打出这个符号）；设备上不跑。

### PAN-22 [P1] 表情搜索的字母键（设备）
- **前提**：B0（表情搜索的字母键由引擎画，060-render-p2 S6）。
- **操作**：点「表情」；点放大镜；点 x i a o；点 **返回**。
- **预期**：字母键三行、和键盘一样的键面；打 xiao 时上面一排有 😄；返回后回到表情列表。
- **JVM**：同 PAN-12（`PanelUatTest.emojiSearchByPinyin`）；引擎画的键上点、滑、退格的结果见 `SmallBoardsSceneGesturesTest.emojiSearch`。

### PAN-23 [P1] 符号面板的格子（设备）
- **前提**：B0（格子是各自一个单键场景，060-render-p2 S6）。
- **操作**：点「符」；点格子里的「，」；点「返回」再点「表情」；点「:-)」进颜文字，往下翻到 `(｡>﹏<｡)ﾉﾞ✧*。`。
- **预期**：格子里的符号白键、居中；点后框里出现「，」；表情格子里是彩色 emoji，在格子里居中；
  颜文字的长字整个显示，两边没有被切掉（放不下时字变小，rules B §8a）。
- **JVM**：`SmallBoardsSceneGesturesTest.symbolGrid`、`PickerSceneCellTest`。

### PAN-24 [P1] 表情、颜文字面板底排的句号、逗号跟着标点模式
- **前提**：B0，中文标点。
- **操作**：点「表情」，看底排句号键的键面，点它；点「:-)」进颜文字，看底排逗号键，点它。切到英文标点（工具栏 **⋯** 或 Ctrl+.）后再各点一次。
- **预期**：中文标点时键面是「。」「，」，框里打出「。」「，」；英文标点时键面是「.」「,」，打出「.」「,」。按下时没有气泡，不进最近使用。
- **JVM**：`SmallBoardsSceneGesturesTest.thePickersFullStopShowsWhatItTypes`、`thePickersFullStopTypesNothingBeforeTheMarksAreKnown`（`:app:testDebugUnitTest`）。（仅 JVM）首次启动、标点还不知道时点它不打字，第一次轻震一下；设备上抓不到这个时刻。

### PAN-25 [P1] 面板里的 ⌫ 空格 ⏎ 有槽、有标记
- **前提**：B0，**上滑**、**下滑** 都开，默认的槽。
- **操作**：在「普通文本」框打几个字。在符号面板、表情、颜文字、展开的候选、表情搜索（打几个字母）上看 ⌫ 的键角；各上滑 ⌫、再下滑 ⌫；在符号面板上滑空格。
- **预期**：每个面板的 ⌫ 都画清除、恢复两个图标；符号面板等上滑清空输入框、下滑恢复；表情搜索里上滑清空查询、下滑恢复查询（输入框不动）；空格上滑出编辑菜单。
- **JVM**：app `PanelKeysUatTest.slotsAndMarks`。

### PAN-26 [P2] 颜文字、表情面板的 ，。 有长按
- **前提**：B0，中文标点；打开颜文字面板。
- **操作**：长按 ，；长按 。；切到表情面板再长按 。。
- **预期**：， 与 KB-73 同一列表，。 与全键盘 。 键的长按同一列表。
- **JVM**：app `PanelKeysUatTest.punctuationLongPress`。

### PAN-27 [P1] 密码框里，面板 ⌫ 上滑设成「复制」也不复制
- **前提**：B0；上滑开；**按键字符和快捷键 → 功能键 → ⌫** 的上滑设为 **复制**。
- **操作**：在普通文本框里写「你好」，全选，打开符号面板，上滑 ⌫，粘贴到别处看。再在密码框里写「你好」，全选，打开符号面板，上滑 ⌫，粘贴。
- **预期**：普通框里复制了「你好」；密码框里不复制，剪贴板还是之前的内容。两种情况框里的字都不变。和字母键盘上的同一个上滑一样。
- **JVM**：app `PanelKeysUatTest.aPasswordIsNotCopiedFromAPanel`。

### PAN-28 [P1] 数字键盘、符号面板的动作长按和别的键盘一样
- **前提**：B0；快捷短语有至少一条。
- **操作**：1. 点数字输入框，长按数字键盘的 `符`。2. 普通文本框打开符号面板，长按 `123`。3. 在字母键盘上长按空格，记下发生了什么；再到数字键盘上长按空格。4. 长按数字键盘左列的 `+`。
- **预期**：1. 打开快捷短语。2. 打开 Unicode 输入。3. 两个键盘长按空格做的事相同。4. 弹出 移到最前、编辑这一列…、删除「+」 三项的菜单（运算符在列里，不再是键；菜单的细节见 KB-117），松手后菜单还在，不打出 `+`。
- **JVM**：app `PanelKeysUatTest.actionLongPressesEverywhere`、`.numberPadsSpaceHeldAsTheLetters`；第 4 步 `ScrollColumnMenuTest.theNumberPadsColumnHasTheSameMenu`、`.aHeldItemsMenuStaysAfterTheLiftAndMovesToTheFrontUndone`。

### PAN-36 [P0] 数字键盘左列：先露出 + - * /，往下滑有别的
- **前提**：B0。
- **操作**：在普通框点 **123**；看左列；往下滑左列；点 `%`、`(`；清空，打 `12*3=`。再到测试台 `form` 页：点 `form_text`、**123**，把左列滑下去，按 →| 到 `form_text2` 看左列；在 `form_text2` 把左列滑下去，收起键盘，再点 `form_text2`（这时是字母键盘）、点 **123** 看左列。再到测试台 `webview` 页：点 `w_text`、123，把左列滑下去，点网页上方的「restartInput（网页）」看左列；再点 `w_search` 看左列。
- **预期**：左列一开始是 `+ - * /`，往下滑有 `% , : ( ) #`；列里的符号和九键左列一个样子（字色是主题的功能键字色）；点 `%`、`(` 上屏 `%()`（括号成对默认开着，补上 `)`）；`12*3=` 照打出来（应用自己算的出计算结果）。→| 到 `form_text2` 还是数字键盘，左列回到 `+ - * /`；收起再点同一个 `form_text2`，左列停在原处（中间没到过别的框；去过别的框再回来算新的开始，回到 `+ - * /`）。网页（没设 id 的 WebView）restartInput 后左列停在原处，点 `w_search` 回到 `+ - * /`。
- **JVM**：`NumberColumnTest.theFullPadsColumnShowsTheOperatorsFirst`、`.aTapTypesAsThePadsKeysDid`、`.anotherFieldShowsTheColumnFromItsStart`、`.aNumberKeyboardKeptForLaterOpensFromItsStartInAnotherField`、`.aFieldRestartedKeepsWhereTheColumnWas`（应用的框、没设 id 的 WebView restartInput 后停在原处）、`.theSameFieldLeftAndComeBackToKeepsWhereTheColumnWas`（收起再点同一个框）、`FieldTrackTest.aFieldIsTheSameByItsEditorInfoOrItsViewAppAndTraitsOrARestartAlike`、`ScrollColumnMenuTest.theNineKeysColumnStaysPutThrough123AndBack`、`.aListEditedInPlaceKeepsItsPlace`、`.punctuationSwitchedUnderAHeldItemKeepsItsTap`（按着列里一项时切了中英标点，松手照打）；设备上照跑：真的计算器框。

### PAN-37 [P1] 拼音打字中点左列的 +，和以前的 + 键一样
- **前提**：B0-拼音。
- **操作**：打 `ni`，点 **123**；点左列的 `+`。再清空，两手交替快打 `12+34`（一手点数字，另一手点左列）。
- **预期**：打字中左列仍是符号；点 `+` 先上屏第一个候选，再打出半角 `+`（和 0.6.2 数字键盘上的 `+` 键一样）。快打时一个字都不丢，框里是「12+34」。两根手指同时按在左列上，什么也不打；一根手指正拖着左列时另一根碰到它，拖动照常。
- **JVM**：`NumberColumnTest.aPlusIsThePadsKey`、`.aFingerOnTheColumnAndOneOnAKeyEachType`、`.twoFingersOnTheColumnTypeNothing`、`.aSecondFingerOnTheColumnLeavesItsDragGoingOn`；设备上照跑（两手快打）。

### PAN-38 [P1] 横屏、单手、悬浮下左列对齐它的位置
- **前提**：B0。
- **操作**：点 **123**；分别在横屏、单手、悬浮（拖大拖小各一次）下看左列，上下滑、点一项。数字键盘不分成两半：横屏默认分体时它连同工具栏缩成单手宽靠一边，横屏这一项看的就是它。
- **预期**：左列总是正好盖在它那一格上（底色是那一格的），能滚、能点，点了打出那一项。
- **JVM**：`NumberColumnTest.theColumnSitsOnItsSlot`（列的位置就是场景里那一格）；设备上照跑。

### PAN-39 [P0] 表单里数字键盘跨框保持
- **前提**：B0；testbed `form` 页。
- **操作**：在 `form_text` 点 **123**；按 →| 依次走过 `form_text2`、`form_amount`、`form_text3`、`form_default`；手点 `form_email`；手点 `form_note`；再手点回 `form_text2`。
- **预期**：
  - `form_text2` 是数字键盘；
  - `form_amount` 是完整的数字键盘；
  - `form_text3`、`form_default` 是数字键盘；
  - `form_email` 是字母键盘；
  - `form_note` 和再点回的 `form_text2` 都是字母键盘（经过邮箱框时已经恢复原规则）。
- **JVM**：`StickyNumbersUatTest.keptAcrossPlainFields`；设备上跑。

### PAN-40 [P1] 恢复原规则的几种情况
- **前提**：B0；testbed `form` 页、`types` 页。
- **操作**：
  1. 在 `form_text` 点 123，点 **返回**，再按 →|；
  2. 点 123 后收起键盘，再点 `form_text2`；
  3. 点 123 后切到别的 App 的普通框；
  4. 直接点 `form_amount`，再按 →| 到 `form_text3`；
  5. `types` 页：在「电话」框点 返回 再点 123，然后点 `text` 框（同一页里，不换页，免得换页时键盘收起）；
  6. `types` 页：在 `no_learning` 框点 123，再点 `text` 框。
- **预期**：
  - 第 1–5 步，到下一个框都是字母键盘；
  - 第 6 步是数字键盘（无痕框也算普通框）。
- **JVM**：`StickyNumbersUatTest.backHiddenOtherAppOrFieldPadLetGo`、`.anIncognitoFieldIsPlain`；设备上跑。

### PAN-41 [P1] 网页里的框
- **前提**：B0；testbed `webview` 页。
- **操作**：在 `w_text` 点 123；点 `w_noauto`；点 `w_email`；再点回 `w_text`。
- **预期**：
  - `w_noauto` 是数字键盘；
  - `w_email` 是字母键盘；
  - 再点回的 `w_text` 是字母键盘。
- **JVM**：`StickyNumbersUatTest.aWebViewsRestartIsANewField`（`restart(inputType)`）；设备上跑。

### PAN-42 [P0] 数字键盘的锁（长按返回）
- **前提**：B0；testbed `form` 页。
- **操作**：
  1. 在 `form_text` 点 123，长按 **返回**；
  2. 收起键盘，再点 `form_text2`；
  3. 手点 `form_email`，再点 `form_text3`；
  4. 转成横屏再转回来；
  5. 再长按 **返回**，收起键盘，点 `form_text`；
  6. 点 123、长按返回，再点 **返回**，点 `form_text2`；
  7. 点 123、长按返回，切到别的 App 的普通框；
  8. 点 123、长按返回，收起键盘，回桌面，不点任何框，再回到 testbed 点 `form_text`；
  9. 收起键盘，点导航栏的最近任务（方块键），按返回回到 testbed，点 `form_text2`；
  10. 收起键盘，下拉通知栏再收起，点 `form_text`。
- **预期**：
  - 第 1 步，振动一下，仍是数字键盘（长按不回字母），返回 右上角出现一把小锁（强调色）；
  - 第 2 步是数字键盘；
  - 第 3 步：`form_email` 是字母键盘，`form_text3` 又是数字键盘；
  - 第 4 步，仍是数字键盘，锁着；
  - 第 5、6、7 步，最后都是字母键盘；
  - 第 8、9、10 步都是数字键盘，锁着：只有在别的 App 的框里弹出键盘才算换 App，桌面、最近任务、通知栏都不算（回桌面时系统把输入法绑到桌面，日志里有 `onBindInput`，锁也不解开）；
  - TalkBack 下 返回 仍读「返回」，长按的动作读「锁定」/「解锁」，锁着时状态读「已锁定」。
- **JVM**：`StickyNumbersUatTest.theLockKeepsThroughHidingAndLetterFields`（长按返回锁上、不回字母）、`.held返回IsSaidAndShown`、`CustomPanelTest.aNumberLayoutsOwnLockKeyStillLocks`（自己布局里单独的锁键：点了锁上，图标换成锁上的，读「解锁」）；设备上跑。

### PAN-43 [P1] 锁着时：符号面板返回仍锁着，表情面板返回解开，终端框是字母
- **前提**：B0；testbed `form` 页、`behavior` 页（页下面的 `terminal` 框，TYPE_NULL）。
- **操作**：
  1. 在 `form_text` 点 123、长按 **返回**，点 **符**，再点符号面板的 **返回**；
  2. 到 `behavior` 页点 `terminal` 框；再回 `form` 页点 `form_text`；
  3. 打开表情面板（工具栏的 **表情**，或长按 ⏎ 选 Emoji），点 **返回**；收起键盘，点 `form_text2`。
- **预期**：
  - 第 1 步回到数字键盘，锁还锁着（返回 角上有锁，长按读「解锁」）；
  - 第 2 步 `terminal` 是字母键盘，回到 `form_text` 又是数字键盘、锁着；
  - 第 3 步表情面板的返回回到字母键盘，`form_text2` 也是字母键盘：锁解开了。
- **JVM**：ime-core `StickyNumbersTest.theLockKeepsThroughHidingAndLetterFields`（「locked, back」：表情面板那种返回解锁；「locked, an email field's letters, a plain field」：字母的框不解锁，回到普通框又是数字）；两种面板的返回各发什么在设备上看；设备上跑。
