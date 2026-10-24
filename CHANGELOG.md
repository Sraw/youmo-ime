# 版本日志 · Changelog

每个版本分两部分：**主要变化**是新功能和大的改进，**小改动**是用户能感觉到的细节，**修复**是改掉的问题（0.3.0 起单列）。
只记用户看得到、用得到的变化；内部重构、测试、构建的改动不列。

Each release lists its **main changes** (new features, large improvements), its **smaller changes**
(details a user notices) and, from 0.3.0, its **fixes**. Internal refactoring, tests and build changes are left out.

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
