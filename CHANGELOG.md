# 版本日志 · Changelog

每个版本分两部分：**主要变化**是新功能和大的改进，**小改动**是用户能感觉到的细节，**修复**是改掉的问题（0.3.0 起单列）。
只记用户看得到、用得到的变化；内部重构、测试、构建的改动不列。

Each release lists its **main changes** (new features, large improvements), its **smaller changes**
(details a user notices) and, from 0.3.0, its **fixes**. Internal refactoring, tests and build changes are left out.

---

## 0.6.0（YYYY-MM-DD）

从 0.5.x 直接覆盖安装，设置、学到和添加的词都在；升级后**键盘会提示导入文字包**，导入后照常打字（要用语音的再导入语音包）。装过 0.6.0 测试版的也直接覆盖安装：设置、自己改的长按和滑动都带过来，导入过的资源包不用重新导入（只导入过测试版 1 语音包的，要重新导入语音包）。从正式版退回测试版 3 不支持改长按：退回后长按全按自带的、编辑器只读，再升级回来就照旧。

Installing 0.6.0 over 0.5.x keeps settings and learned and added words; after the upgrade **the keyboard asks for the text pack**, then types as before (import the voice pack too for voice). A 0.6.0 test build (beta) is also simply installed over: settings and your own long-press and swipe lists carry over, and imported packs stay (only those who imported beta 1's voice pack must import the voice pack again). Going back from 0.6.0 to beta 3 does not support long-press edits: beta 3 then uses the built-in long presses with a read-only editor, and upgrading again brings yours back.

### 主要变化

- **资源包**：安装包里不再带词库和模型，拼音、英文、码表、句子模型、手写放在**文字包**（必须有），离线语音识别放在**语音包**（可选）。在 **设置 → 资源包 → 导入资源包** 选 `.youmopack` 文件（可以多选）；没导入文字包时键盘上会提示，点一下到这一页。资源包没有新版本时，升级只要下安装包；覆盖安装资源包还在，卸载会一起删掉。
- **语音输入重做**：边说边识别，说着的内容以带下划线的待定文字出现在输入框里。**按住空格说话**（默认，松开上屏，上滑取消）；**点麦克风说话**（点工具栏的麦克风，不用一直按着，前面定下来的部分边说边上屏）。**语音整理** 总是开着：去掉"嗯""那个"等口头禅、重复和改口，加标点，数字按习惯写（二零零二年 → 2002年，十点十五 → 10:15）。**语音句末句号**（**按键 → 语音句末句号**）默认聊天框里不加句号。说出的词和你词库里的词同音时换成你的词。声音小时自动放大，安静时不凭空打出字；说完后最后几个字出来得更快。
- **长按、上滑下滑、键角小字统一**：所有键盘（全键盘、14/18 键、九键、注音键盘、数字键盘、功能键、手写）的这些都在 **设置 → 按键 → 按键字符和快捷键** 一处改，一个键改一次，用到它的键盘都跟着变；原来 **九键 → 按键长按** 那一页并进来了，新加 **数字键盘** 页。每个键的长按列表最多 16 项，键自带的字母、数字也能移动、删除、设为首选；长按弹出时手指下就是首选。太长的长按首选、上滑下滑文字在键上画成前两个字加「…」，还放不下就画一个两条横线的图标。英文键盘的长按、滑动、键角和拼音一样。长按、键角、按下的气泡显示的都是中英文标点转换后真正打出的字符。从 0.5 升级时原来的设置自动搬过来，搬不过来或变了的见下面「长按和滑动统一后的变化」。
- **每个键的上滑、下滑**：每个键各设一个上滑和一个下滑：长按首选、快捷键、一段文字或空。新快捷键 **滑选**：滑过以后左右挪一个字一个字地选中，再上下挪按屏幕行跨行选，和空格滑动移光标一样。符号、表情、颜文字面板和展开候选上的退格、空格、回车也能上滑下滑。
- **键盘新画法**：键盘、候选栏、工具栏、编码行和各个面板都用新的引擎按主题画，改键盘设置只重画键，不再重建整个键盘。
- **主题**：在网页 [主题制作页](https://sraw.github.io/youmo-ime/theme-maker/) 上做（App 里只导入、导出、删除），能做渐变、半透明键、动画 WebP 动态背景、按键状态过渡和按下动效、每类键各自的**按键音**、自己的 **SVG 图标** 和布局；导出的主题都是 4.0 格式，旧主题导出时转成 4.0，样子不变。**12 个新内置主题**：简蓝、橙风、素雅、清心、墨灰、液态玻璃、霓虹赛博、国风·天青（后三个带动态背景）、机械键帽（带按键音）、奶油、护眼·纸色、多巴胺。主题列表每张卡片下写主题名。
- **导入别的输入法的皮肤**：**导入主题文件** 也收百度（`.bds`、`.bdi`）、搜狗手机版（`.ssf`，没加密的）的皮肤，同文（Trime）和 FlorisBoard 的主题，导入前先确认。
- **在键上划**（**通用输入 → 在键上划**，默认关，三选一）：**滑行输入**、**笔画筛选**，以及新的 **键盘手写**：没打字时直接在拼音、码表、注音的键盘上写汉字，不用换到手写输入法。
- **常用词**（**词库与短语 → 常用词**）有了自己的页面，能改、导入、导出，排在替换前面；替换项可以和原词一样（把原词排到最前）。
- **按键音音量** 改成 0–100 的滑块，最左是「系统默认」，松手时按这个音量响一声。

### 小改动

- **快速输入** 的条目可以写备注、放进目录。
- 空格横滑移过光标后再上下挪，光标走一行。
- 左右和上下的键距分成两个设置；紧凑高度下行挨得更近、键更高。
- 很长的内容分段送进输入框，不再被截断。
- 键盘边上的键长按时，弹出的那排往里滑。
- 工具栏右边多了 **麦克风**（没导入语音包时点了提示导入；密码框里没有）；空格长按默认是按住说话，导入了语音包时空格上有麦克风角标。
- 长按候选 **屏蔽** 对英文、笔画查字、滑行、手写、表情和拆字的候选也管用：英文词屏蔽后不再补全、联想，也不再改成它；整句候选整句屏蔽。
- 编辑长按列表的对话框显示在键盘上方，打了字直接点 **完成** 也会加进去；列表满了会说出来，不再悄悄丢掉。
- 新设置 **拼音键字母大小写**：中文输入法键上的字母总是小写、跟随 Shift 或总是大写。
- 九键 1：英文没打字时点 1 和拼音一样是 `, . ? !`；拼音的 1 关掉中文标点时打 `, . ? !`，键上也跟着变。
- 功能键的上滑、下滑标记各画在自己的位置，设置里会说明哪个键上画不下；退格、空格的标记跟着上滑、下滑图标位置走。
- 读屏和设置里 Shift 键叫「大小写」。
- 主题画键盘出错时当场退回内置主题；下次启动发现上次没画完，也改用内置主题并提示。

### 修复

- **修复全新安装后学到的词不保存**（0.5.x 就有）：全新安装后存学习记录的目录没建出来，学到的词只在内存里，切到别的输入法后进程被系统回收，词就全没了。现在会建出目录、照常存盘；从 0.5.x 升级的，从升级后第一次打字起正常保存（之前丢的找不回）。
- 打字很快时，手指抬起算在按下的位置，不会算到下一个键上。
- 双拼 18 键、14 键不再把用 ü 键打的音「纠错」成别的。
- 紧凑单手模式下全键盘有些键角小字（w 的 2、a 的 @）放不下就不画：现在都画得出来。
- 键角圆的主题，左上角的小字不再伸出圆角；导入的皮肤键面上不再有 1 像素的细线。
- 字比键宽、或字形超出键的位置时，缩小或挪回键里。

### 长按和滑动统一后的变化

从 0.5 升级时你原来的设置会自动搬过来，下面这些是搬不过来或者变了的：

1. 全键盘页「其它键」里给 `, . ? ! ~` 以外的键设的长按不再生效，包括符号面板里符号的长按：符号面板的格子只用内置的长按。
2. 单独给大写字母设的、和小写不一样的长按列表没有了；按 Shift 时显示小写列表的大写形式。
3. 改全键盘的 q、w 不再影响 14 键、18 键 QW 的长按：要改就在 14 键、18 键页改。
4. 一个键超过 16 项的长按，按列表顺序留前 16 项。
5. 手写键盘的 ， 长按换成和其它键盘一样的列表（没有了 `、` `…`）；手写的 。 也有长按了。注音九键 ㄖㄥㄩ 右边那个键的长按换成和逗号一样的列表（没有了 `、`，可以在功能键页的 ， 里自己加）。
6. 九键、14/18 键长按里点数字：正在打字时和数字键盘一样是选第几个候选，以前是直接打出数字。
7. 逗号、句号的上滑下滑设成「长按首选」的，现在会画出首选、滑一下就打出它；滑向空着的方向等于点一下。
8. 从 0.6.0 退回 0.5：在 0.6.0 里改的长按不会带回去；在旧版里又改了的，再升级也不会带过来（以 0.6.0 里的为准）。

### Main changes

- **Resource packs**: the APK no longer carries dictionaries and models. Pinyin, English, tables, sentence models and handwriting are in the **text pack** (required), offline voice in the **voice pack** (optional). Import `.youmopack` files in **设置 → 资源包 → 导入资源包** (several at once); without the text pack the keyboard says so, and a tap opens that page. When a release has no new pack, only the APK is needed; packs survive installing over and go with an uninstall.
- **Voice input rebuilt**: recognized as you speak, shown in the field as underlined pending text. **Hold space to talk** (default; release to commit, swipe up to cancel) or **tap the microphone** (no holding; settled parts are committed as you go). **语音整理** is always on: drops fillers, repeats and restarts, adds punctuation, writes numbers as usual (二零零二年 → 2002年, 十点十五 → 10:15). **语音句末句号** (**按键 → 语音句末句号**) leaves the full stop off in chat fields by default. A word sounding like one in your dictionary becomes yours. Quiet voices are amplified, silence types nothing, and the last words arrive sooner.
- **Long press, swipes and corner hints in one place**: for every keyboard (full, 14/18 keys, nine keys, zhuyin, number pad, function keys, handwriting) they are edited in **设置 → 按键 → 按键字符和快捷键**, once per key, and every keyboard using that key follows; the nine keys' **按键长按** page is merged in, and a **数字键盘** page is new. A key's long-press list holds at most 16 items; the key's own letters and digits move, delete and become first like any other; the popup opens with the first under the finger. A long first item, or a long swipe text, is drawn on the key as its first two characters and 「…」, or as an icon of two lines when even that does not fit. English's long press, swipes and hints are pinyin's. Long press, hints and the key bubble show what is really typed after the Chinese/English punctuation switch. Settings from 0.5 move over by themselves; what does not, or changes, is listed under "After long press and swipes were unified".
- **A swipe up and a swipe down on every key**: each one the long-press first, a shortcut, a text or nothing. A new shortcut **滑选**: after the swipe, slide sideways to select character by character, then up or down to extend by screen lines, as space's cursor slide does. Backspace, space and enter on the symbol, emoji and kaomoji panels and the expanded candidates swipe too.
- **New drawing**: the keyboard, candidate bar, toolbar, code row and panels are drawn by a new engine from the theme; a keyboard setting redraws the keys instead of rebuilding the keyboard.
- **Themes**: made on the web [theme maker](https://sraw.github.io/youmo-ime/theme-maker/) (the app imports, exports and deletes): gradients, translucent keys, animated WebP backgrounds, key state transitions and press motion, **key sounds** per kind of key, your own **SVG icons** and layouts; themes export as format 4.0, older ones converted with the same look. **12 new built-in themes**: 简蓝, 橙风, 素雅, 清心, 墨灰, 液态玻璃, 霓虹赛博, 国风·天青 (the last three animated), 机械键帽 (with key sounds), 奶油, 护眼·纸色, 多巴胺. Each card in the theme list carries its name.
- **Other keyboards' skins**: **导入主题文件** also takes Baidu (`.bds`, `.bdi`) and unencrypted Sogou mobile (`.ssf`) skins and Trime and FlorisBoard themes, after a confirmation.
- **在键上划** (**通用输入 → 在键上划**, off by default, one of three): **滑行输入**, **笔画筛选**, and the new **键盘手写**: with nothing typed, write characters straight on the pinyin, table or zhuyin keyboard, no switch to handwriting.
- **常用词** (**词库与短语 → 常用词**) has its own page to edit, import and export, ranked before replacements; a replacement may equal the word itself (to put it first).
- **按键音音量** is a 0–100 slider whose far left is 「系统默认」; letting go plays a click at that volume.

### Smaller changes

- **快速输入** entries can carry a note and go into folders.
- After space's sideways cursor slide, moving up or down moves the cursor a line.
- Horizontal and vertical key gaps are two settings; at 紧凑 rows sit closer and keys are taller.
- Very long text is sent to the field in parts, no longer cut off.
- An edge key's long-press row slides inward.
- A **microphone** on the toolbar's right (asks for the voice pack when missing; not in password fields); space's long press is hold-to-talk by default, with a microphone hint once the voice pack is in.
- Long-press **屏蔽** works on English, stroke lookup, glide, handwriting, emoji and 拆字 candidates too: a blocked English word is no longer completed, predicted or corrected to; a sentence candidate is blocked whole.
- The long-press list editor's dialog sits above the keyboard, and **完成** adds what you typed; a full list says so instead of dropping it.
- New setting **拼音键字母大小写**: letters on Chinese keyboards always lower case, following Shift, or always upper case.
- Nine keys' 1: with nothing typed in English it is `, . ? !` as in pinyin; pinyin's 1 with Chinese punctuation off types `, . ? !`, and the key shows it.
- Function keys' up and down marks each sit at their own place, and settings say which key has no room; backspace's and space's marks follow the icon position settings.
- Screen readers and settings call Shift 「大小写」.
- A theme that fails to draw falls back to a built-in theme at once, and on the next start if the last drawing never finished, with a notice.

### Fixes

- **Words learned after a fresh install were not saved** (since 0.5.x): the learning folder was never created, so learned words lived only in memory and were lost when the system reclaimed the process after switching to another input method. The folder is created now; after upgrading from 0.5.x words are saved from the first key typed (those lost before cannot be recovered).
- Fast typing: a lifted finger counts where it came down, not on the next key.
- Shuangpin's 18 and 14 keys no longer "correct" syllables typed with the ü key.
- In the compact one-handed mode some full-keyboard corner hints (w's 2, a's @) were left out for lack of room: all are drawn now.
- Top-left hints stay inside rounded corners; imported skins lose a 1-pixel line on the key faces.
- Text wider than its key, or outside it, is shrunk or moved back in.

### After long press and swipes were unified

Settings from 0.5 move over by themselves; these do not, or change:

1. Long presses set on the full keyboard's 「其它键」 for keys other than `, . ? ! ~` no longer apply, nor do those of symbol-panel symbols: the panel uses the built-in ones.
2. Separate capital-letter lists differing from the lowercase are gone; Shift shows the lowercase list in capitals.
3. Changing the full keyboard's q and w no longer changes 14/18 keys' QW: edit those on the 14/18-key pages.
4. A key's long press longer than 16 items keeps its first 16.
5. Handwriting's ， long press is the same list as other keyboards' (no `、` `…`), and its 。 has a long press now. 注音九键's key right of ㄖㄥㄩ gets the comma's list (no `、`; add it under the function keys' ，).
6. A digit picked from the nine keys' or 14/18 keys' long press while composing picks that candidate, as the number pad does; it used to type the digit.
7. A comma or full stop swipe set to the long-press first is now drawn and types it; a swipe towards an empty way is a tap.
8. Going back from 0.6.0 to 0.5 does not take long presses changed in 0.6.0; ones changed again in 0.5 are not brought back on the next upgrade (0.6.0's win).

---

## 0.5.2（2026-11-04）

0.5.1 同一天发出，替换成了 0.5.2：0.5.1 在 Android 10–12 上会崩溃（打出带读音的候选时输入法崩溃，例如读音纠正的 jiaose → 角色 jué sè；存过快速输入条目后打开 **快速输入** 管理页时应用崩溃）。装过 0.5.1 的直接覆盖安装 0.5.2。下面是相对 0.5.0 的变化。

0.5.1 went out the same day and was replaced by 0.5.2: on Android 10–12 it crashed (the keyboard on a candidate with a reading, such as a misreading's hint; the app opening the 快速输入 page once it had an entry). Install 0.5.2 over 0.5.1. Below: the changes since 0.5.0.

### 主要变化

- **滑动快捷键**：每个键（全键盘、14/18 键、九键、注音键盘、数字键盘、功能键）上滑或下滑可以执行快捷键，默认上滑是快捷键、下滑是符号，标在键上（**按键 → 滑动**、**快捷键图标位置**、**标点位置** 可以改）。**按键 → 按键字符和快捷键** 一页里改每个键的字符和快捷键。
- **编辑菜单、清除和恢复**：空格上滑弹出全选、剪切、复制、粘贴、撤销，不松手左右挪选一格；退格上滑 **清除**（正在打的、选中的或光标前的全部文字，可撤销）；退格下滑 **恢复** 最近一次清除或连着按退格删掉的一段，放回原处。
- **快速输入可以存自己的条目**：什么都不打时候选栏列出你存的，最近用过的在前，点一条整段上屏；在 **设置 → 词库与短语 → 快速输入** 里加、改、删、搜索、导入导出 `.mb`，剪贴板里长按一条也能 **存为快速输入**。长按"符"在九键和手写上也能进，工具栏可以放 **快速输入** 按钮，快捷键也能设成它。
- **候选词注音**（设置里，默认关）：在中文候选的字上标出拼音，或在右边竖排注音符号，各中文输入法、码表、手写都标，每个字标它自己的声调。
- **注音键盘**：拼音的 **键盘** 多了 **注音九键** 和 **注音全键盘**（大千），用注音符号打拼音的词库，声调可打可不打。
- **主题文件改成新格式 3.0**：主题导出、导入都是 `.theme` 文件（里面仍是 zip，只认 `.theme`）；0.5.0 及更早版本导出的 `.zip` 主题改名成 `.theme` 再导入；更早的版本导入不了 0.5.1 导出的主题。按键的圆角、边框、间距存在主题里，**大小与样式** 里也可以自己设，盖过主题（默认 **跟随主题**）；升级时原来的设置存进正在用的主题（内置主题先复制一份，提示一次）。App 里不再做主题、改主题：0.5.0 的"选择图片…""复制内置主题…"和主题上的编辑按钮没有了，只列出、使用、导入、导出和删除；做主题以后的版本再提供。已有的自己的主题都保留，照常能用。
- **手写的注键和字卡**：手写键盘右边多了 **注** 键，开关候选词注音；手写的单字标出全部读音。长按手写候选里的单字（拆字、笔画查字出的也一样）先显示**字卡**：读音和例词、部首、笔画、拆字、笔顺，下面有 **上屏** 和 **复制**。
- **联想整段话**：完整上屏过 3 次的整段话，打出它的开头就接着给出剩下的部分，各输入法和手写都有；长按可以不再联想或删除。只由中文数字组成的（电话号码、金额）不学。
- **键盘高度「紧凑」**：比「矮」更低（竖屏 20%、横屏 34%），键上的字小一号；五笔的全部字根在紧凑时显示成键名。
- **编码行浮在键盘上方**（默认）：打字时才出现一小条，键盘少一行、按键高度不变，换 14 键、18 键时键盘高度不变；悬浮键盘、单手模式时跟着键盘走。**外观** 里可以改回 **嵌在键盘里**。

### 小改动

- **读音纠正**的读音标在候选的字上方，一字一音，候选栏高度不变；拆字、笔画查字的读音也标在字上方，带声调。
- 有些框只是少学：网址、邮箱、数字框只学词，不学前后搭配；搜索框不出联想、不学联想。本输入法自己的输入框（加词、自定义短语、设置里的搜索）什么都不学。
- 从剪贴板面板粘贴的条目挪到最前（置顶的不动），不再先被条数上限删掉。
- 悬浮键盘的编码行贴在键盘左上角外面，拖到屏幕最上面放不下时换到键盘下边。
- 双拼的 18 键、九键只在微软双拼时显示 `;`。
- 按键对话框的「重置」改叫「恢复此键默认」。
- 0.5.1 以前的备份没有快速输入：恢复它会删掉自己加的快速输入条目，恢复前的确认框里会说。用更早的版本恢复这一版的备份，主题看不到，换回默认主题。

### 修复

- 从最近任务换应用后，键盘留着上一个应用打到一半的拼音和候选：现在跟着清掉。
- 换应用时新应用的输入框有时没拿到焦点，按键变成原样字母进了框：现在只结束换走的那个应用的输入。
- 分屏时接着实体键盘按 Ctrl+空格（或别的让屏幕键盘收起的键），输入法会崩溃：现在不再崩溃。
- 从上游 Fcitx5 for Android 的备份恢复后，输入法列表只剩英文，拼音、双拼和五笔等码表都没了，繁体也关了（0.5.0 起）：现在列表和繁体都恢复。上游的拼音、双拼方案、码表等设置仍不带过来，恢复后到设置里重新选。

### Main changes

- **Swipe shortcuts**: every key (full, 14/18 keys, nine keys, zhuyin, number pad, function keys) can run a shortcut on a swipe up or down; by default up is the shortcut and down the symbol, both marked on the key (**按键 → 滑动**, **快捷键图标位置**, **标点位置**). **按键 → 按键字符和快捷键** edits each key's characters and shortcuts on one page.
- **Edit menu, clear and restore**: swipe up on space for select all, cut, copy, paste and undo, picked by sliding sideways; swipe up on backspace to **清除** (what is typed, the selection or all text before the cursor, undoable); swipe down on backspace to **恢复** the last clear or run of backspaces, put back in place.
- **Quick input keeps your own entries**: with nothing typed the bar lists yours, recent first, a tap types one whole; add, edit, delete, search and import/export `.mb` in **设置 → 词库与短语 → 快速输入**, or long-press a clipboard entry for **存为快速输入**. Long-pressing 符 works on the nine keys and handwriting too; a **快速输入** toolbar button and shortcut are available.
- **候选词注音** (a setting, off by default): pinyin above each Chinese candidate's characters, or zhuyin beside them, in every Chinese input method, tables and handwriting included, each character in its own tone.
- **Zhuyin keyboards**: pinyin's **键盘** adds **注音九键** and **注音全键盘** (大千), typing the pinyin dictionary in zhuyin, tones optional.
- **Theme files are a new format, 3.0**: themes are exported and imported as `.theme` files (a zip inside; only `.theme` is imported). Rename a `.zip` theme from 0.5.0 or earlier to `.theme`, then import it. Earlier versions cannot import 0.5.1's themes. Key corners, borders and gaps are stored in the theme, and **大小与样式** can override them (default **跟随主题**); on upgrade the old settings are saved into the theme in use (a built-in one is copied first, with one notice). The app no longer makes or edits themes: 0.5.0's "选择图片…", "复制内置主题…" and the edit button are gone; it lists, applies, imports, exports and deletes them, and making themes returns in a later version. Your existing custom themes are kept and keep working.
- **Handwriting's 注 key and character card**: a **注** key on the handwriting keyboard toggles 候选词注音; handwritten characters show all their readings. Long-pressing a handwritten character (or one from `u` 拆字 or stroke lookup) opens its **card**: readings with example words, radical, stroke count, parts and stroke order, with **上屏** and **复制**.
- **Whole-phrase prediction**: a phrase committed whole 3 times is offered in full once its start is typed, in every input method and handwriting; its long press turns it off or deletes it. Phrases of Chinese numerals alone (phone numbers, amounts) are not learned.
- **Keyboard height 紧凑**: lower than 矮 (20% upright, 34% turned), with smaller key text; Wubi's full roots show as key names at 紧凑.
- **Code row floating over the keyboard** (default): a strip shown only while typing, so the keyboard is a row shorter with keys the same height and keeps its height on 14 and 18 keys; it follows the floating and one-handed keyboards. **外观** can set it back to **嵌在键盘里**.

### Smaller changes

- The **misreading hint**'s reading sits above the candidate's characters, one syllable each, the bar's height unchanged; 拆字 and stroke lookup readings sit above too, with tones.
- Some fields learn less: URL, email and number fields learn words but not what goes with what; search fields neither predict nor learn predictions. The app's own fields (adding words, custom phrases, settings search) learn nothing.
- An entry pasted from the clipboard panel moves first (pinned ones stay), no longer the first the limit drops.
- The floating keyboard's code row sits outside its top left corner, below it when dragged to the top of the screen.
- Shuangpin's 18 and nine keys show `;` only under 微软.
- The key dialog's 「重置」 is now 「恢复此键默认」.
- A backup from before 0.5.1 has no quick input: restoring it deletes your own quick input entries, as the confirmation says. Restoring this version's backup in an earlier version loses its themes and goes back to the default theme.

### Fixes

- After switching apps from recents, the keyboard kept the previous app's half-typed pinyin and candidates: they are now cleared too.
- Switching apps sometimes left the new app's field unfocused, its keys typed as plain letters: only the app switched away from has its input ended now.
- In split screen with a hardware keyboard, Ctrl+Space (or another key that hides the on-screen keyboard) crashed the input method: it no longer does.
- Restoring a backup from upstream Fcitx5 for Android left only English in the input method list, pinyin, shuangpin and the tables (Wubi and the like) gone and 繁體 off (since 0.5.0): the list and 繁體 are restored now. Upstream's pinyin, 双拼 scheme and table settings are still not carried over: choose them again in settings.

---

## 0.5.0（2026-11-02）

### 主要变化

- **笔画筛选**（设置里的开关，和 **滑行输入** 二选一，开一个自动关另一个）：打了拼音后在键盘上画一笔（横、竖、撇、点、折），只留第一个字这样起笔的候选，再画就按这个字的笔顺往下筛。
- **打字更快、占内存更少**：引擎少做重复的查找和计算，九键尤其明显；改了设置后头几个键不再变慢；学到的词多了以后每按一个键占的内存少了很多，不再越用越慢。
- **拼音、双拼、英文各一个输入法，键盘自己选**：每个输入法的设置页有 **键盘** 一项（拼音：全键盘 / 18 键 / 14 键 / 九键；双拼：全键盘 / 18 键 / 14 键 / 九键；英文：全键盘 / 九键），打字时也能在 ⋯ → **键盘** 里切换，工具栏还可以放一个 **键盘** 按钮。
  **九键拼音并入了拼音**：升级后原来用九键拼音的，拼音的键盘自动设成九键，键盘上提示一次「九键拼音已并入拼音，在 ⋯ → 键盘里切换」；两个都启用过的，按已启用列表里靠前的那个定键盘。九键的设置在拼音设置页里单独一栏「九键」。
  **双拼也有 14 键了**（微软双拼除外）。**英文的九键也在英文的「键盘」里选**：九键拼音上不再有【中/英】键，去英文统一用地球键。
- **双拼只留小鹤、微软、自然码**：紫光、智能ABC、中文之星、拼音加加、国标去掉了，用这些方案的升级后改成小鹤并提示一次；新装默认小鹤，升级时没选过方案的仍是自然码。学到的词原样保留。微软双拼照旧没有 14 键，选了 14 键时按 18 键显示。
- **工具栏按钮可以自己定**（设置 → 按键 → **工具栏按钮**）：拖动排顺序、勾选要显示的，分成展开的工具栏里和一直在栏右边的两组；⋯ 和菜单键固定不能去掉，右边放不下的按钮挪进展开的工具栏。
- **剪贴板切词**：长按一条选 **切词**，把它切成一块块（邮箱、网址、数字、英文单词、中文词），点一块选中，按点的先后编号，点 **上屏** 按这个顺序拼起来输入，**重选** 清空所选；从一块横着滑过去可以一次选中一串，像相册里选照片。返回键回到剪贴板列表。
- **剪贴板历史可以不限条数**：条数上限里多了 **无上限**；把上限调低会删掉条目时先问一句。
- **按输入框换键盘**：数字、电话、日期时间框直接打开数字键盘，旁边的键按框要的换掉；网址框、邮箱框的候选栏给出常用后缀和 **记住打过的邮箱地址**（设置 **网址和邮箱**，可以改后缀、关掉记忆、全部清空），记住的地址跟着学习备份走。
- **码表的细节**：码表多了「拼」键，按下后用拼音查字，查到的字上屏、不学字母；仓颉的键上可以显示字根、五笔的键上显示字根或键名（在各自的设置页里选）；打字时回车键显示「确定」；分体键盘上码表多出的键也在 L 后面。

### 小改动

- **读音纠正**的提示在简拼、只打了一部分或混打时也出（`xxxuez`、`xinxinxuez` 都标出「莘莘学子」的正确读音）；只给已有的候选标注，不改排序；双拼不提示。
- 九键打字时按 0，先上屏第一个候选再输入 0。
- 拼音 18 键上显示 L，键上和长按都不再有 `;`。
- 码表和英文里也能用自定义短语，最多排第二，空格照旧上屏编码或单词的第一个；内置的 rq/sj/xq（日期、时间、星期）在全拼的各种键盘上都有，双拼没有。
- 改了要重建键盘的设置（主题、候选设置等）：没选的拼音清掉不上屏；英文打到一半的词、手写还没上屏的字照样上屏留下。
- 不再转换 0.3.0 及更早版本学到的词：从这些版本升级，先升到 0.4.x，打几个字、停一下，再升到新版；直接升级的话以前学到的词不再读进来，手动添加的词、屏蔽的词和码表（五笔等）保存的词照样保留，码表的「调频」回到默认（关，到码表设置里打开），从 0.3.0 直接升级也一样。上游 Fcitx5 拼音（libime）学到的词也不再读进来。0.3.1 到 0.4.0 期间一次也没打开过的码表，以前保存的词仍在这个码表里，但不再同时加进拼音。
- 在新版里恢复 0.3.0 及更早版本导出的备份 zip 会被拒绝，并提示先在 0.4.x 里恢复、打几个字、停一下、重新备份。

### 修复

- 自定义短语在 14 键、18 键、九键和双拼九键上不起作用（内置的 rq/sj/xq 也不出）：现在按设定的位置出。
- 打到一半改键盘设置、换主题后，候选栏和拼音栏卡住或空着：现在改这类设置先清掉没选的拼音，重建的键盘接着显示引擎的候选。
- 「通用输入」的设置（联想、档位、模糊音等）、已启用的输入法和标点，在清理软件清掉 `Android/data` 或启动时外部存储不在之后悄悄回到默认：现在存在应用自己的目录里，升级后第一次启动自动搬过去，读不出来会提示、不再用默认值覆盖。

### Main changes

- **Stroke filter** (笔画筛选, a switch exclusive with 滑行输入): after typing pinyin, draw one stroke on the keyboard to keep only candidates whose first character starts with it; further strokes follow its stroke order.
- **Faster, lighter typing**: the engine repeats fewer lookups, the nine keys most of all; the first keys after a setting change no longer lag; with many learned words each key allocates far less, so typing no longer slows down as it learns.
- **One pinyin, one shuangpin and one English input method, each with a keyboard to choose**: each input method's page has **键盘** (pinyin: full / 18 keys / 14 keys / nine keys; shuangpin: full / 18 / 14 / nine keys; English: full / nine keys), switchable while typing in ⋯ → **键盘**, and the toolbar can carry a **键盘** button.
  **九键拼音 is merged into pinyin**: whoever used it gets pinyin on the nine keys and one notice on the keyboard; with both enabled, the one earlier in the enabled list decides. The nine keys' settings are a section 「九键」 on pinyin's page.
  **Shuangpin gets the 14 keys** (not 微软). **English's nine keys are chosen in English's 键盘**: pinyin's nine keys lose the 中/英 key; English is reached by the globe key.
- **Shuangpin keeps 小鹤, 微软 and 自然码**: 紫光, 智能ABC, 中文之星, 拼音加加 and 国标 are gone; their users are moved to 小鹤 with one notice. New installs start on 小鹤; an upgrade that never picked a scheme stays on 自然码. Learned words are kept. 微软 still has no 14 keys and shows the 18 keys when 14 is chosen.
- **Toolbar buttons of your own** (Settings → 按键 → **工具栏按钮**): drag to order, tick to show, in the expanded toolbar or always on the bar's right. ⋯ and the menu key stay; buttons without room move into the expanded toolbar.
- **Clipboard 切词**: long-press an entry, pick **切词** to split it into pieces (emails, URLs, numbers, words, Chinese words); tap pieces to pick them, numbered in tap order, **上屏** types them joined, **重选** clears; swipe sideways across pieces to pick a run at once, as in a photo grid. Back returns to the clipboard list.
- **Unlimited clipboard history**: the limit has a **无上限** value; lowering it asks first when entries would be deleted.
- **Keyboards by field type**: number, phone and date/time fields open a number pad with keys suited to the field; URL and email fields offer suffixes and **记住打过的邮箱地址** on the candidate bar (setting **网址和邮箱**: edit suffixes, turn memory off, clear all); remembered addresses travel in the learning backup.
- **Table details**: a 拼 key looks up characters by pinyin (the pick goes in, no letters learned); 仓颉's keys can show roots and 五笔's roots or key names (chosen on their pages); enter shows 确定 while composing; a table's extra key sits after L on the split keyboard too.

### Smaller changes

- The **misreading hint** also shows on 简拼, half-typed and mixed input (`xxxuez`, `xinxinxuez` mark 莘莘学子's reading); it only labels candidates already shown and changes no order; not in shuangpin.
- 0 on the nine keys while typing commits the first candidate, then 0.
- Pinyin's 18 keys show L, with no `;` on the key or its long press.
- Custom phrases in tables and English too, at most second, so space still commits the code's or word's first; the built-in rq/sj/xq on every full-pinyin keyboard, not in shuangpin.
- A setting that rebuilds the keyboard drops unpicked pinyin; a half-typed English word or a handwritten character not yet committed is committed and kept.
- What 0.3.0 and earlier learned is no longer converted: upgrade to 0.4.x, type a few characters and pause, then upgrade further; upgraded directly, the words learned before are not read; words added by hand, blocked words and the phrases a table (Wubi and the like) saved are kept, and a table's 调频 (order by use) goes back to its default, off (turn it on in the table's settings), straight from 0.3.0 too. What upstream Fcitx5's pinyin (libime) learned is not read either. A table never opened from 0.3.1 to 0.4.0 keeps the phrases it saved before, but no longer adds them to pinyin as well.
- A backup zip exported by 0.3.0 or earlier is refused, with a note to restore it in 0.4.x, type a few characters and pause, and back up again first.

### Fixes

- Custom phrases did nothing on the 14, 18 and nine keys and 双拼九键 (nor the built-in rq/sj/xq): they now come at their set position.
- The candidate and pinyin bars stuck or empty after changing a keyboard setting or theme mid-typing: such a change now drops unpicked pinyin, and the rebuilt keyboard shows the engine's candidates.
- 通用输入's settings (联想, 档位, fuzzy pinyin…), the enabled input methods and punctuation quietly reset after a cleaner emptied `Android/data` or a start without external storage: they now live in the app's own storage, moved on the first start, and one that cannot be read is reported, never overwritten with defaults.

---

## 0.4.0（2026-10-26）

### 主要变化

- **导入的词库占的空间少了六成**：导入的词库压缩存放，合成的文件也更紧凑。导入雾凇拼音（rime-frost）后占用从约 190 MB 降到约 75 MB，打字速度不变。
  从 0.3.1 或 0.3.2 升级后第一次启动会在后台转换一次（雾凇拼音约一两分钟），空闲栏显示「正在升级词库格式」和进度，这期间打不出导入的词。
  **装回 0.3.2 时**，并入基础词库的导入词库在 0.3.2 里看不到、打不出；文件还在，再升级回来就又有了。
- **始终显示悬浮条**（设置 → 外观，默认关）：键盘停在底部时也有那条拖动条；长按条中间的短横，键盘就变成悬浮键盘停在手指下面，不松手可以接着拖，拖回底部松手又放回去。
- **拼音 14 键**（输入法 → 拼音 → **键盘**）：两个字母一个键，键大一倍，和讯飞的「拼音双键」一样。点在键的左半或右半，优先读成那一边的字母，已经学到的顺序仍然优先；编码行右边有音节条，从左往右点音节定下来。这是初步支持，欢迎反馈。
- **双拼 18 键和九键双拼**（输入法 → 双拼 → **键盘**）：每个方案都能用分组的 18 键或九宫格；音节条上也有声母，点一个就定下这个字的声母。九键双拼重码很多，要多选几次。这是初步支持，欢迎反馈。
- **语音说到最后一个字就松开也不丢字**：松开后再录 0.3 秒；声音小时自动放大再识别。

### 小改动

- **候选字号**可调 14–28 sp：候选栏、联想和展开的候选跟着变，按键高度不变。实体键盘候选窗的字号改名为 **浮动候选窗字号**。
- **键盘底部边距**多了「贴底」：隐藏了手势条、键盘下面却还空着一条的手机，键盘可以贴到屏幕最下面。
- 复制、删词、屏蔽等的提示合成一种提示条：能撤销的停 6 秒，连着做几次时计数累加，不再一条盖一条。
- 动画都更短（最长 150 ms）；**禁用动画** 和系统的"移除动画"现在关掉键盘里的全部动画。
- 全拼打过两次、在简拼下提到第三的词，遇上表情候选时仍是第三，表情往后挪一位（全拼打过两次「售价」后，`shouj` 出「手机、收集、售价、📱」；0.3.3 是表情第三、这个词第四）。
- 快速输入（长按「符」）不再给英文单词提示；颜文字、`:表情名:` 和 `\符号名` 照旧。
- 系统「文件」应用里不再显示幽默输入法的数据目录。
- 输入法框架换成了本项目按 Fcitx5 用 Kotlin 重写的版本，安装包里不再带原生的 Fcitx5。打字、设置、标点、繁体、快速输入和 Unicode 输入照旧，
  设置文件原样沿用，升级后不用重新设置。想装回旧版本，先「备份全部」再卸载、装旧版、从备份恢复（见使用说明的常见问题）。
- 实体键盘上去掉了几个原生 Fcitx5 自带、本来就少有人用的快捷键：Ctrl+; 剪贴板历史、韩文的 Hangul_Hanja / Hangul_Romaja 键、Super+空格切换输入法组（只有一个组，原来也不起作用）。
  Ctrl+.（中英文标点）、Ctrl+Shift+F（简繁）、快速输入和 Unicode 输入的快捷键照旧。
- 语音开始时先显示「准备中…」，麦克风真正开始收音才变成「正在听…」并轻震一下；2 秒收不到声音时显示「麦克风没有声音」（多半是麦克风开关关了或别的应用在录音）。
- 语音面板上松开后马上点回车：等这句上屏后再回车，发出去的不是空消息；退格照常马上删。
- 候选之间的空隙跟着候选字号变，字小时一页放得下更多候选；单个字仍不窄于 32 dp，便于点。
- 拼音 14 键、双拼 18 键和九键双拼上没有中/英键，空格更宽；到英文按 🌐。
- 手工改配置文件才能改的几项不再生效：`config/config` 里的全局项（比如 `DefaultPageSize`），`chttrans.conf` 里的 `Engine=Native` 和自定的 OpenCC 配置（只有从上游 fcitx5-android 带来的配置才会有）。界面上能改的设置都不受影响。
- Unicode 输入（长按 123）不带 `U+` 也能按编码找字，比如 `4e2d` 出「中」（14 键上打不出 `+`）。
- **备份全部** 完成后提示「已备份到 <文件名>」。

### 修复

- 从备份恢复时，设置、自定义短语和标点现在完全以备份为准：备份之后在手机上新加的自定义短语、改过的标点会被去掉，不再和备份混在一起。
- 语音输入时咳嗽、清嗓子、喘气不再打出「咳咳」「哼哼」「Huh」。
- 按住说话时，0.3.3 在麦克风还没开始收音时就显示「正在听」，刚开口的约 1 秒会丢；现在等真正收音才提示开始说。
- 开着英文标点时，用 TalkBack 按地球键切回拼音，读到的输入法名不再在一秒多里还是「英文」。
- 在键盘上改的设置（比如切到繁體）现在马上保存，切完立刻强行停止应用也不会丢。

### Main changes

- **Imported dictionaries take 60% less space**: they are stored compressed, and the compiled file is more compact. With rime-frost
  imported, storage goes from about 190 MB to about 75 MB, with typing as fast as before. The first start after upgrading from 0.3.1 or 0.3.2
  converts them in the background (a minute or two for rime-frost); the idle bar shows 「正在升级词库格式」 and its progress, and imported
  words cannot be typed until it ends. **Going back to 0.3.2** hides dictionaries imported into the base dictionary there: the files stay,
  and they return when you upgrade again.
- **始终显示悬浮条** (Settings → 外观, off by default): the drag strip shows while the keyboard is docked too; long-press the
  handle in its middle and the keyboard floats under your finger, to drag on without lifting, and docks again when dropped at the bottom.
- **Pinyin 14 keys** (输入法 → 拼音 → **键盘**): two letters a key, keys twice as wide, like iFlytek's 拼音双键. Tapping the left or right
  half of a key favours that half's letter, while orders you have taught it still come first; a strip of syllables on the code row fixes the
  reading left to right. This is preliminary support; feedback welcome.
- **Shuangpin 18 keys and nine keys** (输入法 → 双拼 → **键盘**): every scheme works on the grouped 18 keys or the nine-key grid; the
  syllable strip also shows initials, and tapping one fixes that character's initial. Nine-key shuangpin has many clashes, so expect to pick more.
  This is preliminary support; feedback welcome.
- **Voice keeps the last word**: it records 0.3 s more after you let go, and quiet speech is boosted before recognition.

### Smaller changes

- **候选字号** (candidate text size) is adjustable, 14–28 sp: the candidate bar, predictions and the expanded list follow, keys keep their
  height. The hardware keyboard's candidate window setting is renamed **浮动候选窗字号**.
- **键盘底部边距** (bottom padding) gains 「贴底」 (flush): on phones that hide the gesture bar yet leave a strip under the keyboard, the
  keyboard can sit at the very bottom.
- Notices for copy, delete, block and the like are one kind of notice bar: one that can be undone stays 6 seconds, and repeating the
  action adds to its count instead of covering it with another.
- Animations are shorter (150 ms at most); **禁用动画** and the system's "Remove animations" now turn off every animation in the keyboard.
- A word typed twice in full and lifted to third under its initials stays third when an emoji candidate shows: the emoji moves a place down
  (售价 typed twice in full, `shouj` gives 手机, 收集, 售价, 📱; 0.3.3 put the emoji third and the word fourth).
- Quick input (long-press 「符」) no longer suggests English words; kaomoji, `:emoji-name:` and `\symbol-name` work as before.
- The system Files app no longer shows Youmo's data folder.
- The input method framework is now this project's Kotlin port of Fcitx5; the APK no longer carries native Fcitx5. Typing, settings, punctuation,
  traditional output, quick input and Unicode input work as before, and the settings files are kept as they are: nothing to set again after
  upgrading. To go back to an older version, 「备份全部」 first, then uninstall, install the old version and restore the backup (see the manual's FAQ).
- A few hardware-keyboard shortcuts native Fcitx5 brought along, rarely used, are gone: Ctrl+; (clipboard history), the Korean Hangul_Hanja /
  Hangul_Romaja keys, and Super+Space to switch input method groups (there is only one group; it did nothing before either).
  Ctrl+. (Chinese/English punctuation), Ctrl+Shift+F (simplified/traditional) and the quick input and Unicode shortcuts stay.
- Voice shows 「准备中…」 first and 「正在听…」 with a short vibration once the microphone really delivers sound; after 2 s without sound
  it shows 「麦克风没有声音」 (the microphone switch is likely off, or another app is recording).
- On the voice panel, Enter pressed right after letting go waits for the text, then acts, so a chat does not send an empty message; delete acts at once.
- Candidate spacing follows the candidate text size, so small text fits more candidates on a page; a single character stays at least 32 dp wide to tap.
- Pinyin 14 keys, shuangpin 18 keys and nine-key shuangpin have no 中/英 key, and a wider space bar; 🌐 gets to English.
- Settings that only a hand-edited config file could change no longer apply: global items in `config/config` (such as `DefaultPageSize`), and
  `Engine=Native` or a custom OpenCC profile in `chttrans.conf` (found only in configs carried over from upstream fcitx5-android). Every setting
  the app shows is unaffected.
- Unicode search (123 held) finds a code without `U+` too, `4e2d` giving 中 (the 14 keys have no `+`).
- **备份全部** says which file it wrote once done.

### Fixes

- Restoring a backup now takes settings, custom phrases and punctuation wholly from the backup: phrases added or punctuation changed on the
  phone after the backup are dropped instead of mixed in.
- In voice input, coughs, throat clearing and breathing no longer type 咳咳, 哼哼 or "Huh".
- Hold-to-talk: 0.3.3 showed 正在听 before the microphone was on, and about the first second was lost; it now says so only once sound comes in.
- With English punctuation on, TalkBack no longer reads the old input method's name for over a second after the globe key
  switches back to pinyin.
- Settings changed from the keyboard (such as switching to 繁體) are now kept even if the app is stopped right away.

---

## 0.3.3（2026-10-24）

### 修复

- 全拼打过的词，简拼下也会往前排：完整拼音打过两次的词（比如「奉化」），在简拼 `fh` 下排到第三；简拼下改选过的词，打完整拼音时至少排第二。简拼的第一个照旧按你在简拼下的选择。双拼、九键里是完整的和打一半的互相带，只在各自的键盘里。

### Fixes

- Words typed in full pinyin now move up under their abbreviation too: a word typed in full twice (奉化, say) comes third under `fh`; a word picked over the first under an abbreviation is at least second when typed in full. The first under an abbreviation still follows your picks there. In 双拼 and on the nine keys, keys typed in full and keys typed in part lift each other the same way, each keyboard on its own.

---

## 0.3.2（2026-10-23）

### 主要变化

- **导入大词库不再崩溃**：导入的词库现在在导入时整理一次，存成本应用自己的词库文件，弹出键盘时不再整个读一遍。导入 190 万词的词库（比如 rime-frost）后，
  键盘的内存占用和没导入时差不多。0.3.1 里导入过词库的，升级后第一次打开会在后台转换一次，空闲栏显示「正在升级词库格式」和进度。
- **导入前先看格式**：导入时先分析文件：编码（UTF-8、GBK、UTF-16）、表头、哪一列是词、哪一列是拼音、第三列是概率、代价还是词频。确认框里写明认出了什么；
  看不准的就说「格式不支持」和原因，不会导错。第三列按认出的含义参与排序。导入有进度条，可以取消。
- **最低系统版本改为 Android 10**：Android 6–9 的手机装不了这个版本。

### 小改动

- 几个基础词库开关、删除时会重新整理，词库页上这一行显示「正在更新」。
- 并入用户词库的词表最多 10 万词（导入过的加起来算），更大的请并入基础词库；自己导出的备份不受限。
- 关掉「用我的输入习惯」时，开着的基础词库照常出词，五笔等码表和拼音一样。

### 修复

- 修复导入很大的词库后，每次弹出键盘都内存不足、输入法反复崩溃的问题。

### Main changes

- **Large dictionaries no longer crash the keyboard**: an imported dictionary is now converted once, at import, into the app's own dictionary file, and showing
  the keyboard no longer reads it whole. With a 1.9-million-word dictionary (rime-frost, say) the keyboard uses about as much memory as without it.
  Dictionaries imported in 0.3.1 are converted once in the background on the first start after the upgrade; the idle bar shows 「正在升级词库格式」 with its progress.
- **The format is checked before an import**: the file's encoding (UTF-8, GBK, UTF-16), header, which column holds the word and which the pinyin, and whether the third
  column is a probability, a cost or a frequency. The confirm dialog says what was found; a file it cannot read with confidence is refused with the reason, never
  imported wrongly. The third column ranks words by what it was found to mean. Imports show their progress and can be cancelled.
- **Android 10 or later is now required**: phones on Android 6–9 cannot install this version.

### Smaller changes

- Turning a base dictionary on or off, or deleting one, rebuilds them; its row on the dictionary page shows 「正在更新」 meanwhile.
- A word list merged into the user dictionary may hold at most 100,000 words (all such imports together); larger ones go into the base dictionary. Your own exported backup is exempt.
- With 「用我的输入习惯」 off, the turned-on base dictionaries still offer their words, in 五笔 and the other tables as in pinyin.

### Fixes

- Fixed the keyboard running out of memory, and the input method crashing again and again, after a very large dictionary was imported.

---

## 0.3.1（2026-10-22）

### 主要变化

- **九键英文**：九键拼音的底排多了 **中/英** 键（和搜狗一样），切到九宫格的英文。按完一个词的键就出候选（`4663` 是 good、home、gone），空格上屏第一个；
  1 键打词时是撇号（don't），⇧ 在原来"重输"的位置。长按选的字母定进正在打的词，词库里没有的词这样打出来就记住；
  没有词对得上时显示每个键的第一个字母，不打出数字。九键拼音的设置里可以改成连按（像老式手机）；网址、邮箱框里总是连按。
  输入法列表里的"英文"照旧是全键盘。
- **学词更稳、更快**：输入法学你的词的方式重写了。学到的顺序更稳定，不会因为偶尔选错一次就乱掉；常用的词更快排到前面、更快成为你的词；
  置顶按输入方式分别记住。旧版本学到的东西第一次打开时自动转过来。
- **导入的词**：从别的输入法导出的词表、搜狗细胞词库（.scel）导进用户词库时，不再算你加的词，而是"导入的词"：只在一段输入的开头出现，
  排在词库同音词后面，不进整句、不进语音、不出联想，上屏两次才成为你的词；五笔等码表也能打出它们，排在码表自己的词后面。
  词库与短语 → 导入的词 里按批列出，可以整批撤掉（马上能撤销）。导入大词表时不再提示改导基础词库。
- **学习的控制**：工具栏 **⋯** 里有 **不用我的输入习惯**（借手机时键盘像刚装好，不学也不用你的记录）和 **清掉最近**（10 分钟、1 小时、今天）；
  用户词库里的 **清空学到的** 逐项写明清什么、留什么，可以选全部清空。
- **学习备份**：用户词库 → 学习备份。默认开着自动备份（第一个打字日一份，之后每 7 个打字日一份，留最近 2 份），能另存一份到看得见的文件夹；
  恢复（包括 **备份全部** 的恢复）改为和手机上的合并，恢复前自动存一份，可以 **退回恢复前**。用户词库的 **导出** 改存成 `.fxlb` 备份文件，不再导出纯文本。
- **长按候选的菜单**：改为 **删除**（你的词、导入的词）、**忘掉学到的**（词库自带的词）、**固定首位**、**屏蔽**、**不再联想**；码表里屏蔽只对这串码生效。
  硬件键盘上在高亮的候选上按 Shift+Delete 也是删除或忘掉学到的。手写和联想也从你的选取中学习。
- **码表**：去掉"自动造词"和它的两个设置，一个字一个字打的不再自动成词；"常用字词排前面"改名 **调频**，新装时关（升级前用过的码表照旧开着）。

### 小改动

- 应用界面只保留简体中文、繁体中文和英文。
- 英文：只是打过几次的词不再免于自动改正；改回过一次或手动添加的词才不再被改。
- 九键长按数字键：弹出一行「大写字母 数字 小写字母」（2 键是 `A B C 2 a b c`），选哪个直接上屏哪个；符号在上面，不再用横线隔开。
- 九键在网址、邮箱输入框里，左边那一列换成 `/`（邮箱是 `@`）、`.`、`.com` 等网址邮箱用的符号；底排和平常一样，地球键还在。
- 长按弹出的字符框变小了，九键的也和字母键一样小，浮在键的上方。手指稍微挪一点就换一个字符（不用挪到它正上方），换时轻振一下（跟随「按键振动」）；挪过头停在头尾，挪到空位选最近的；往下滑出键再松开是不选。
- 悬浮键盘的四角跟着「按键圆角」：选直角时悬浮键盘也是直角。
- 悬浮键盘的拖动条变窄了，默认放在键盘下面（设置里 **悬浮键盘拖动条** 可以改回上面）。拖到最低处，底部展开一条框并振一下，松手就放回底部；拖回去再松手照常悬浮。

### 修复

- 修复了一些学习前文（前面打的词）时的问题：输入框里原有文字后面选的词，现在也会记住和前面那个词的搭配。
- 键盘里的提示（复制、剪切、清掉最近的结果等）改为显示在键盘上方。以前用的是系统提示，Android 13 起没开通知权限时不显示。

### Main changes

- **English on the nine keys**: the nine-key pinyin layout gains a **中/英** key for nine-key English. A word's keys offer candidates (`4663` gives good, home, gone),
  space commits the first; 1 is an apostrophe inside a word, ⇧ sits where 重输 was. A letter picked by long press is fixed in the word, so a word the
  dictionary lacks is typed and remembered that way. Multi-tap is an option, and always on in address and email fields. The English input method stays a full keyboard.
- **Steadier, faster learning**: how the keyboard learns your words was rewritten. What it learned stays steady instead of shifting after one odd pick,
  the words you use come up and become yours sooner, and pins are kept per input method. What an older version learned is carried over on first start.
- **Imported words**: word lists and Sogou .scel files imported into the user dictionary become imported words, behind the dictionary's words of the same
  reading until you use them, and yours after two commits. 词库与短语 → 导入的词 lists each import, which can be taken back as a whole.
- **Learning controls**: the toolbar's **⋯** has **不用我的输入习惯** (a borrowed phone gets a fresh keyboard) and **清掉最近** (10 minutes, an hour, today);
  **清空学到的** says what it clears.
- **Learning backups**: automatic backups on by default, a copy saved where you can see it, and restores that merge with the phone, with **退回恢复前**.
  The user dictionary's export is now such a backup file (`.fxlb`).
- **Long press on a candidate**: **删除**, **忘掉学到的**, **固定首位**, **屏蔽** and **不再联想**, each with an undo. Handwriting and 联想 learn from your picks too.
- **Code tables**: 自动造词 is gone; 常用字词排前面 is now **调频**, off on new installs (kept on for tables used before the upgrade).

### Smaller changes

- The app's interface is in Simplified Chinese, Traditional Chinese and English only.
- English: a word only typed a few times is no longer spared autocorrection; one you reverted once or added yourself is.
- Nine keys: a digit key's long press shows `A B C 2 a b c`; address and email fields put `/` or `@`, `.` and `.com` in the left column.
- Long-press popups are smaller and float above the key; a small move changes the pick, with a light vibration; sliding down off the key picks nothing.
- The floating keyboard follows 按键圆角, has a slimmer handle below it by default, and docks when dropped at the bottom.

### Fixes

- Several fixes to learning the word before: a word picked after text already in the field now learns its pairing with that text.
- What the keyboard tells you (copied, cut, what 清掉最近 did) now shows above the keys. It used system toasts, which Android 13 and later hide while the app may not post notifications.

---

## 0.3.0（2026-10-19）

### 主要变化

- **滑行输入**：手指在字母上划过一个词，抬起就出候选。英文、全拼、双拼、九键和码表都能滑（手写除外），自己加的词也能滑出来，
  屏蔽的词不会出现。网址、邮箱和密码框里不能滑：这些内容不是词，滑行只会猜出错的词，密码框里也看不见滑错了什么。默认关，在「通用输入」里打开。
- **表情候选**：打出的字词有对应表情时，候选里从第三个起给出至多两个，全拼、双拼、九键、码表都有；码表编码打满直接上屏时，表情改在联想栏最前面，点了替换刚上屏的词。
  表情面板可以按拼音搜索（`xiao` 找笑、`dz` 找点赞）。
- **拆字**：不会读的字按部件打：先按 u，再打各部分的全拼，`uniuniuniu` 出「犇」，`ushuishuishui` 出「淼」。只有全拼有，可以关掉。
- **算式结果**：打完算式再打 =，候选栏上给出结果（0.1+0.2 是 0.3）；除以零、太大的数这类算不了的不给。
- **自带的日期时间**：打出「日期」「时间」「星期」时，第三个候选起给出今天的日期、现在的时间、星期几，全拼、双拼、九键、码表都有；全拼里 rq、sj、xq 也行。
  删掉的自带短语不再出现，开关在「通用输入」。
- **单手模式和悬浮键盘**：键盘可以缩到一边单手打，再打开时回到上次的那一边；悬浮键盘缩成小窗浮在应用上面，按住顶上的横条拖到哪里就停在哪里，下次打开还在。
- **设置重新整理**：首页铺平成 10 项；各输入法共用的选项（联想、表情、滑行、模糊音、标点、性能档位）都放在「通用输入」，
  每个输入法的页面只留自己的选项。词库分成「用户词库」和「基础词库」，导入只有一个入口，导入时问放进哪一个。
- **语音识别更懂口音**：识别模型为带口音的普通话做了微调。

### 小改动

- 往用户词库导入超过 1000 个词时，提示建议改放基础词库，并说明两者的区别（导入自己导出的文件不提示）。
- 导出用户词库时标出学到的词，再导入时仍算学到的词；旧的导出文件导回来算手动加的词。
- 导入的词全部进码表；导入的词在拼音里打过一次，就算你自己的词。
- 语音：手动加的词和导入的词优先识别，学到的词照顾得轻一些，屏蔽的词不照顾。
- 九键：打字时长按数字键，直接选对应序号的候选。
- 九键「连按选字母」改成严格：打开时快速连按就是第几个字母（44 是 h），关着时每按一下都是一个键；1 键连按换标点也跟着这个设置。
  **默认改为关**，没改过这个设置的用户升级后是关。
- 剪贴板里点一条粘贴后回到键盘（「剪贴板与表情」里的「粘贴后回到键盘」，默认开）。
- 有候选或联想时，长按 ⌄ 收起键盘（点一下仍是展开）。
- 文本编辑面板复制、剪切后提示「已复制」「已剪切」，复制后退出选择；隐藏的密码框里复制不提示。
- 本应用自己的输入框什么都不学。
- 手写连写多个字后选词，整段记成一个词（和拼音一样）。
- 滑行出的英文词跟点击一样大写：输入框要求才大写，专有名词保持原样；在词或标点后面滑会自动补空格，撤回滑出的词连空格一起删。
- 语音版的许可页补上 LibriSpeech 的署名。
- 帮助文档补全。
- 粗俗的词等第一页之后才出，多收了一些这类词。
- 「忘记词汇」只在有东西可忘的候选上出现。
- 点自定义短语打开编辑框，里面可以删除。

### 修复

- 九键切到数字键盘后，点数字键打出的是字母（ABC），现在打的是数字。
- 九键打字时在数字键上滑，数字接在拼音后面（「你2」），现在是选对应的候选。
- 微软双拼、紫光双拼的 ; 被当成标点打出来，现在是键。
- 横屏把键盘高度调到「很高」时会挡住输入框，现在键盘最高约占屏幕 70%。
- 拼音：上屏的字被退格删掉后，下一个词的排序还按删掉的字算（删了「你」再打 riqi，「脾气」排在「日期」前面）。
- 设置搜索：点搜索结果打开的页面不会滚到那一项。
- 有滑动符号的键，往别的方向滑时打出了不该打的字符，现在打出键本身。
- 空格长按设为「无」时，慢慢拖空格不能移动光标；会连发的键横着拖时还会触发长按。
- 逗号的长按列表不对；两行的长按弹出框里，下面一行选不到。
- 文本编辑面板的 |< >| 现在跳到输入框的开头、结尾。
- 数字键盘的空格可以左右拖动光标。
- 手动加的词如果基础词库里已经有，码表里打不出来。

### Main changes

- **Glide typing**: slide across a word's letters and lift; in English, full pinyin, shuangpin, nine keys and the code tables
  (not handwriting). Your own words glide and blocked words never come out. Glide typing is off in address, email and password fields: their text is no word (a glide could only guess a wrong one, unseen in a password). Off by default; turn it on in 通用输入.
- **Emoji among the candidates**: up to two from the third candidate on, in every pinyin layout and the code tables (after a code that commits itself, first in the next-word bar, a tap replaces the word); the emoji panel searches by pinyin.
- **拆字**: in full pinyin, u then the pinyin of each part types a character you cannot read (`uniuniuniu` gives 犇).
- **Sums**: type a sum and = and the result is offered on the bar (0.1+0.2 is 0.3); nothing for what cannot be worked out.
- **Built-in date and time**: typing 日期, 时间 or 星期 offers today's date, the time or the weekday from the third candidate on, in every pinyin layout and the code tables; rq, sj, xq in full pinyin too. A deleted one stays gone; the switch is in 通用输入.
- **One-handed and floating keyboard**: one-handed comes back at the side last used; the floating keyboard is a small window over the app that stays where you drag it.
- **Settings rearranged**: a flat home of 10 entries; options all input methods share on one 通用输入 page; two dictionaries,
  用户词库 and 基础词库, with one import entry that asks which one a file goes into.
- **Voice**: the recognizer fine-tuned for accented Mandarin.

### Smaller changes

- Importing more than 1000 words into the user dictionary suggests the base dictionary and explains the difference; an export marks learned words and restores them as learned.
- Imported words reach the code tables, and count as your own once typed in pinyin; voice favours words you added or imported, learned words less, blocked words not at all.
- Nine keys: long-press a digit key while typing to pick that candidate; 连按选字母 is strict
  (on: quick presses pick the nth letter, 44 is h; off: each press is a key, the 1 key's marks too) and **now off by default**, also for users who never set it.
- A clipboard item tapped goes back to the keyboard (粘贴后回到键盘, on by default); long-press ⌄ hides the keyboard while candidates show;
  copy and cut say so and end the selection (not in hidden password fields).
- The app's own fields learn nothing; several handwritten characters picked together are learned as a word.
- Glided English words are capitalised as tapped ones are, with a space before them after a word or punctuation;
  the voice build credits LibriSpeech; the manual is complete.
- Coarse words wait past the first page; 忘记词汇 shows only where there is something to forget; tapping a custom phrase opens it for editing, with 删除.

### Fixes

- Nine keys: on the number board the digit keys typed letters (ABC); they type digits. A digit key slid while typing added the digit to the pinyin (你2); it picks that candidate.
- 微软 and 紫光 shuangpin typed ; as a punctuation mark instead of a key.
- In landscape, keyboard height 很高 covered the field; the docked keyboard now takes at most about 70% of the screen.
- Pinyin: a character committed and then deleted with Backspace still steered the next word (你 deleted, riqi gave 脾气 before 日期).
- Settings search opened a result's page without scrolling to it.
- A key with a swipe symbol typed the wrong character when swiped another way; it types the key.
- With the space's long press set to 无, a slow drag on the space did not move the cursor; a sideways drag on a repeating key still fired its long press.
- The comma's long-press list; in a two-row popup the bottom row could not be picked.
- The edit panel's |< >| go to the start and end of the field; the number board's space drags the cursor.
- A word added by hand that the base dictionary already had could not be typed in the code tables.

---

## 0.2.0（2026-10-16）

### 主要变化

- **手写输入**：新的"手写"输入法，一次写一个字，停笔自动接着写下一个。写好的字像拼音一样按整句来读，后面写的字能把前面认错的改过来；
  用你的用户词库，上屏后给联想，有性能档位。识别模型是本项目自己训练的，全部在手机上运行。
- **横屏分体键盘**：横屏且屏幕够宽时，字母键分成左右两半，方便两个拇指打字；数字、九键、手写、符号、表情、剪贴板这些面板缩成单手宽，
  点箭头可以换到另一边。也可以在设置里关掉分体，或让其他面板铺满。
- **实体键盘**：接上蓝牙或 USB 键盘时屏幕键盘收起，拼音和候选显示在光标旁的小窗里。单按 Shift 或 Ctrl+空格切中英，Ctrl+Shift
  轮流切换输入法，`-` / `=` 翻页（快捷键可以在设置里关掉）。手写和九键里按实体键盘的字会改用拼音打。小窗上的键盘图标点一下叫回屏幕键盘，
  图标可以拖到别处；不告诉输入法光标位置的应用里，它放在右上角。
- **码表输入法（五笔、仓颉、自然码、二笔、五笔拼音）有了和拼音一样的能力**：重码按前文排（五笔的一、二级简码顺序不动，也可以关掉）、
  上屏后联想、长按联想词忘记或屏蔽、性能档位；和拼音、手写共用一个用户词库，在拼音里打过、加过的词，码表里也能按规则打出来。
- **编码行**：正在打的拼音或编码显示在候选栏上方单独的一行。点一下就能把光标放到编码中间，接着打的键插在那里、退格删前面的键；
  全拼、双拼、九键、笔画和码表都支持。

### 小改动

- 手动添加的词（包括"导入词表"导入的）从一开始就按同字的常用词排，不再要先选过一次；导入词库里的词仍要选过才提前。
- 联想里同一个词不会出现两次，联想能填满。
- 码表里单个编码自动上屏后，紧接着（1 秒内）按的空格才会被吃掉，停一下再按的空格照常打出。
- 九键关掉"连按选字母"后，1 键的标点每按一次就是一个，不再快按两下变成"。"；九键自定义字母后，两个键字母相同时气泡不再认错键。
- 网址、邮箱输入框里打数字和符号原样上屏（`xiaoming2026@` 不再变成"小明026@"）。
- 英文：括号、引号会占掉单词后自动加的空格（`above.)` 而不是 `above. )`）；开着自动配对时 `(see)` 不再多出一个括号；
  句首大写的词、`dont` 这类写法后面也能预测下一个词；手动加的英文词不会被慢慢忘掉；英文词表页不再把只打过一次的词标成"不会被纠正"。
- 英文里不再出现中文输入法的简繁、中文标点、全角开关；之前在拼音里开着全角，切到英文（包括密码框）会打出全角字母。
- 屏幕旋转后，地址栏这类输入框的光标位置不再错乱。
- 长按屏幕边上的键（比如九键的 9），弹出的候选不再跑出屏幕。
- 第一次解锁前改的"连按选字母"和英文自动大写，立刻生效。
- 安装后的引导页在设置好或你跳过之前，每次打开应用都会出现。
- 只用英文或码表时，不再在后台加载拼音数据，占用内存更少。
- 英文数据按新规则重新统计：we'll 和 well 分开记，带撇号的写法要足够常见才算一个词。
- 语音版的帮助页打开对应版本的说明；语音识别模型的解码器改为 int8，更小更快。
- 文字版只申请振动权限（构建时检查，防止依赖库带进录音权限）。

### Main changes

- **Handwriting**: a new input method, one character at a time, read as a sentence the way pinyin is, so a later character puts an
  earlier misread right; your user dictionary counts, next words are suggested. The recognizers are this project's own, run on the phone.
- **Split keyboard in landscape**: on a wide enough screen the letters split in two halves; the other boards shrink to one hand's width, at either side.
- **Physical keyboards**: candidates in a small window by the cursor; Shift alone or Ctrl+Space switches Chinese and English, Ctrl+Shift
  the input method; the window's keyboard icon brings the touch keyboard back and can be dragged away.
- **Code tables** (Wubi, Cangjie, Ziranma, Erbi, Wubi-Pinyin) do what pinyin does: candidates ordered by the text before, next-word
  suggestions, performance levels, one user dictionary shared with pinyin and handwriting.
- **Code row**: the code being typed has its own row above the candidates; tap it to put the cursor inside and edit there, in pinyin, shuangpin, nine keys, strokes and the code tables.

### Smaller changes

- Words added by hand (an imported word list too) rank as the common word of the same characters from the start.
- A next-word suggestion appears once; Wubi's lone-code space is taken only within a second; nine keys' 1 key with 连按 off types one
  mark a press, and with custom letters two keys of the same letters no longer confuse the popup; digits and symbols in URL and email fields go in as typed.
- English: brackets and quotes take the space added after a word; auto-paired brackets no longer double; words after a capitalised
  sentence start are predicted; words added by hand are kept; the English word list no longer marks a word typed once as never corrected; the Chinese toggles (full width among them) no longer reach English.
- The cursor is kept after a screen turn; an edge key's long-press popup stays on screen; settings changed before the first unlock apply at once;
  the welcome pages come back each time the app opens until set up or skipped.
- Pinyin's data is not loaded for a user who types only English or a table; English data counted again by the current rules;
  the voice build's help opens its own manual, its decoder in int8; the text build asks only for vibration.

---

## 0.1.0（2026-10-12）

第一个公开版本：完全离线的拼音、九键、双拼、五笔等码表和英文输入，自己训练的拼音和英文语言模型、两个句子模型，近年新词词库；
语音版有离线的普通话和英语语音输入。

The first public release: offline pinyin, nine keys, shuangpin, Wubi and other code tables, and English, with this project's own
language models, two sentence models and a dictionary of recent words; the voice build adds offline Mandarin and English voice input.
