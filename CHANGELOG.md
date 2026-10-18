# 版本日志 · Changelog

每个版本分两部分：**主要变化**是新功能和大的改进，**小改动**是用户能感觉到的修复和细节。
只记用户看得到、用得到的变化；内部重构、测试、构建的改动不列。

Each release lists its **main changes** (new features, large improvements) and its **smaller changes**
(fixes and details a user notices). Internal refactoring, tests and build changes are left out.

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
