# 布局格式参考

`.layout` 文件是一块键盘布局：0.6.1 起可以导入 App，换掉自带的字母键盘、数字键盘或符号面板（[设置 · 布局](settings.md#布局)）。一般用[键盘布局页](layout-maker.md)做，不用手写；这一页给想看懂、手改文件的人。主题包 `layouts/` 里的布局是同一种写法（[主题格式](theme-format.md)），区别见文末。

## 文件

- 扩展名 `.layout`，内容是 UTF-8 的 XML（可以带 BOM），最大 64 KB。一个文件一块键盘，不带图片、字体、图标文件：图标只能用 App 自带的名字。
- 读得很严：不认识的元素、属性，写错的取值都是错误，App 不导入，键盘布局页在 **问题** 里列出行号。
- 限制：键最多 150 个；显示的字（`label=`）最多 16 个字符；`commit:`（点一下打出一段文字）每个最多 64 个字符，一块最多 100 个（长按列表里的每一项也算一个）；自己的键 `alt=` 打出的字也最多 64 个字符。这些字符数（还有名字的 24 个）按 UTF-16 数：emoji 和扩展区的生僻字一个算 2 个。手机上最多 100 个导入的布局。

## 一个例子

```xml
<?xml version="1.0" encoding="UTF-8"?>
<keyboard xmlns="https://youmo-ime/layout/1" id="k17" name="17 键" family="grouped" based-on="k18" columns="100">
  <meta author="某人" version="1.0" license="CC0-1.0" min-app="0.6.1" description="H 和 JK 并成一个键"/>
  <defaults kind="letter" text-size="20" width="fill"/>
  <row>
    <key id="r1c1" label="{group:q}" letters="q" shortcut="g18/q"/>   <!-- 从 18 键留下的键：按键名照旧 -->
    …
  </row>
  <row>
    …
    <key id="hjk" label="HJK" letters="hjk" swipe-up="(" shortcut="k17/hjk"/>   <!-- 新键：名字是 布局id/键id -->
  </row>
  <row>…</row>
  <include src="grouped-bottom"/>                                                       <!-- App 的底行 -->
</keyboard>
```

键盘布局页 **新建** 里的三个示例（17 键、AZERTY、大按键数字键盘）是完整的文件，源码在仓库 `web/theme-maker/examples/layouts/`。

## `<keyboard>`

| 属性 | 必需 | 说明 |
|---|---|---|
| `xmlns` | 是 | `https://youmo-ime/layout/1`。不认识的命名空间提示「这个布局是更新的格式」 |
| `id` | 是 | 布局的身份：`[a-z0-9]+(-[a-z0-9]+)*`，最多 32 个字符。不能和 App 自带的键盘、片段同名（`qwerty`、`k18`、`number`、`grouped-bottom`……），也不能是按键名的前缀 `full`、`g14`、`g18`、`t9`、`zy9`、`zyfull`、`num`、`fn`。同 id 再导入是更新 |
| `name` | 是 | 显示的名字，1–24 个字符。App 里显示「名字（自定义）」 |
| `family` | 是 | 什么键盘：`letters`（26 个字母各一个键）、`grouped`（一个键几个字母，14/18/17 键这样的）、`nine`（九键）、`number`（数字键盘）、`symbols`（符号面板） |
| `based-on` | 是 | 从哪块 App 自带的键盘改来：`qwerty`、`k14`、`k18`、`nine`、`number`、`symbols`。主题样式和默认快捷键跟它走。底稿是别族的键盘时（比如 `family="grouped"` 却基于 `qwerty`），缺键时提醒对照的、画不出时换回的是本族的键盘（分组 → 18 键）；分组键跟键形有关的默认（14/18 键的键角、18 键 L 的 `:`）也按本族的键盘（分组 → 18 键） |
| `columns` | 否 | 一行多少个单位宽（默认 10，最多 100）；键的 `width=` 按这个单位算。写 `100` 就是按百分比 |
| `on-split` | 否 | 横屏分体时：`split`（行按 `split="halves"` 分成两半）、`side`（默认，整块放在一边）、`none`（不分） |
| `preview` | 否 | `off`：按下时不弹气泡 |
| `popups` | 否 | `off`：键不长按弹出（数字键盘这样的） |
| `pads` | 只数字键盘 | 管哪几种输入框，空格分开：`full`（点 123 进的那种，各种数字框也用它：纯数字、小数、能填负数、数字密码）、`phone`（电话）、`datetime`（日期时间）。不写就是只管 `full`；没写的那几种照旧用 App 自带的。0.6.3 以前的 `digits`、`signed`、`decimal`、`signed-decimal` 照样读，算作 `full`（这些框现在用完整的数字键盘），网页编辑器提示一句 |
| `arrange` | 否 | 只能是 `rows`（默认） |

## 元素

- **`<meta>`**（可选，写就写在最前面）：`author`、`version`、`license`、`description`（各 1–64 个字符，说明最多 200）和 `min-app`（需要的最低 App 版本，如 `0.6.1`；比手机上的 App 新时拒绝导入）。键盘布局页保存时自动算 `min-app`：用了 `<meta>`、`based-on`、`pads`、行中间的跨行键，或同一个 id 写了几处、只靠所在行的 `when=` 区分的，是 0.6.1；`based-on` 是必需的，所以存下来的文件至少是 0.6.1。缺了 App 那块键盘有的键（见下面「缺键的警告」），或有键在某种情况下什么都不显示的，是 0.6.3（0.6.3 以前的 App 不收）；条件里用了 `table-lookup` 的也是 0.6.3（以前的 App 不认这个开关，整块读不了），照 App 的全键盘做的都会用到它；动作里用了 `shortcut:switch_input_method`、`shortcut:choose_input_method`、`shortcut:select_to_start` 或 `shortcut:select_to_end` 的也是 0.6.3（以前的 App 不认这几个命令，键点了没反应）；有滑动列（`<slot kind="scroll">`）的，和数字键盘上有锁键（`send="panel-lock"`）的，也是 0.6.3。
- **`<defaults>`**（可选，`<meta>` 之后第一个）：整块键的默认属性，写法同 `<key>` 的外观和动作属性。行里也可以有自己的 `<defaults>`。
- **`<row>`**：一行。`height`（行高，相对值，默认 1）、`indent`、`end-indent`（两头留空，单位同宽度）、`when=`（满足时才有这一行）、`split="halves"`（分体时分成两半：这一行要有 9 或 10 个键，`split="after"` 的键和空位不算，否则按比例缩小，检查会给警告；分成两半时每个键都按一个字母宽排，不看 `width=`，9 个键时中间那个两边各放一个）或 `split="scale"`（默认：分体时整行按比例缩小）。里面是 `<key>`、`<gap>`、`<slot>`。
- **`<key>`**：一个键，属性见下。里面可以有 `<variant when="…">`（满足条件时换掉几个属性，键不挪位置）、`<popup>`（长按列表：`<popup from="list">` 里放 `<item text="…"/>`，可选 `send=`）、`<menu>`（长按菜单：里面放 `<item label="…" do="动作"/>`）。
- **`<gap>`**：空位，什么都不画，点了没反应。`width=`、`when=`。
- **`<slot kind="…">`**：App 自己画的一块，`kind=` 必填：`nine-side`（九键左边的符号列）、`symbol-categories`、`symbol-grid`（符号面板的分类栏和符号格子）、`scroll`（滑动列，见下）。App 按 `kind=` 找位置，`id` 随便起。`width=`、`rows=`。没有某个位置时那一块不显示。
- **滑动列 `<slot kind="scroll" list="…">`**：一列能上下（或左右）滑的符号，点一项打出它。里面的内容不写在布局里，由 App 按类别给，用户在「按键字符和快捷键」的「滑动列」里改：`list=` 必填，是 `nine`（九键）、`nine-english`（九键英文）、`number`（数字）、`phone`（电话）、`datetime`（日期时间）、`url`（网址）、`email`（邮箱）之一；在网址、邮箱、电话、日期时间输入框里，App 自动换成那个框的类别。`direction=`：`vertical`（默认，竖着滑）或 `horizontal`（横着滑）；`show=`：一屏显示几项，1–8，默认 4。只能放在 `letters`、`grouped`、`nine`、`number` 的键盘上，一块最多 2 列；不能放在 `split="halves"` 的行里（那种行要 9 或 10 个键，列不知道归哪一半），也不能放在片段里。`list=`、`direction=`、`show=` 只有滑动列能写，写在别的位置上是错误。没写 `look=`、`face=` 时按功能键的底画（等于 `look="function" face="keep"`），和九键左列一样。
- **数字键盘的锁**：写一个 `role="lock"`、`send="panel-lock"` 的键就有锁（锁上后在同一个 App 里换框、收起再弹出都保持数字键盘，邮箱、网址、密码框除外，详见[键盘](keyboard.md#数字键盘)）。没写 `role="lock"` 的点了照样锁，但图标和读屏不跟着变，所以要带上。没有锁的数字键盘照样能用，不算缺键。
- **`<include src="grouped-bottom"/>`**：App 的 14/18 键底行。只能引用 App 自带的片段；App 以后改底行，这里跟着变。

## `<key>` 的属性

| 属性 | 说明 |
|---|---|
| `id` | 键的 id，1–32 个字符，不能有空格，不能以 `view:` 开头；一块键盘里不重复（各自带 `when=`、同一时刻只出现一个的除外）。`letters` 键盘的 a–z 键 id 就是字母 |
| `label` | 显示的字：文字，或一个绑定（下面） |
| `icon` | App 自带图标的名字（`backspace`、`globe`……）或图标绑定；图标键要写 `a11y=` |
| `a11y` | 读屏念什么 |
| `alt` | 键角的小字（默认的上滑首选） |
| `badge` | 键角的小图标 |
| `kind` | `letter`、`function`、`space`、`enter`：主题按它画 |
| `role` | 键的角色：`shift`、`backspace`、`space`、`enter`、`symbols`、`numbers`、`back`、`lang`、`apostrophe`、`semicolon`、`star`、`lookup`…… App 按它认键（⌫ 连按、回车随输入框变字） |
| `width` | 宽度，单位见 `columns=`；`fill` 平分这一行剩下的宽度 |
| `rows` | 往下跨几行（可以在行中间：下面几行跳过它占的那块） |
| `letters` | 分组键的字母（a–z 和 `;`，1–5 个，不重复；可以用空格分行写，如 `"qw er"`） |
| `lean` | 分组键：`on` 按点到的位置偏向组里的那个字母（滑行、纠错用），`off` 不偏 |
| `text-size`、`compact-text-scale`、`text-style`、`face`、`margin`、`class`、`sound` | 字号（dp）、紧凑时的缩放、粗细、画不画键面、键边留不留缝、主题选择器用的 class、按键音的类别 |
| `send` | 点按做什么（动作，下面） |
| `long-press`、`swipe-up`、`swipe-down`、`double-tap` | 长按、上滑、下滑、双击做什么；`swipe-axis`、`repeat`（按住连发）、`preview`（按下的气泡开关） |
| `shortcut` | 按键名，下面 |
| `mark` | `false`：不画上下滑的小图标 |
| `split` | `after`：分体时不算进左右两半，跟在前一个键后面 |
| `when` | 满足时才有这个键 |

### 显示的字

`label=` 可以写文字，也可以写一个绑定，按当前的状态显示：

- `{case:x}`：随 Shift 大小写的字母 x；`{root:x}`（码表的字根）、`{sp-hint:x}`（双拼提示）、`{alt:x}`（这个字母键现在的长按首选）。
- `{group:qw}`：分组键上的一两个字母（按「拼音键字母大小写」显示）。三个字母以上的组写文字，如 `label="HJK"`。
- `{punct:x}`：按标点转换显示的字符；`label=` 里最多四个，`alt=`、`a11y=` 里一个。
- `{ime-name}`、`{return}`、`{confirm}`、`{comma}`、`{period}`、`{table-extra}`、`{back}`（「返回」）；`{zhuyin-group:n}`、`{nine-letters:n}`。
- 图标绑定（`icon=`、`badge=`）：`{shift-icon}`、`{return-icon}`、`{hold-badge}`、`{lock-icon}`。

### 动作

`send=` 等属性的取值：

| 写法 | 做什么 |
|---|---|
| 一个字符 | 打出这个字符（给输入法当按键打） |
| `commit:文字` | 直接打出一段文字（导入时确认框里列出来） |
| `code:U+00E9` | 原样打出这个字符，不随 Shift 变 |
| `sym:名字` | 按一个功能键：`BackSpace`、`Return`、`space`、`Tab`、`Left`、`KP_Decimal`…… |
| `layout:Text`、`layout:Number`、`layout:Symbol` | 回字母键盘、到数字键盘、到符号面板 |
| `shortcut:名字` | 快捷操作：`select_all`、`copy`、`paste`、`undo`、`clipboard`、`emoji`、`voice`、`switch_input_method`、`stroke_h`……（布局动作里 `edit_menu` 是编辑面板、`choose_input_method` 是输入法选择框，滑出一排的菜单只在按键字符和快捷键里设的滑动上） |
| `keypad:N` | 小键盘的数字 N：没在打字时打出 N，正在打字时选第 N 个候选 |
| `caps`、`caps:lock` | Shift、锁定大写 |
| `lang`、`ime-picker`、`quickphrase`、`unicode`、`panel-back`、`panel-lock`、`lookup`…… | 地球键、换输入法、快速输入、Unicode、面板的返回和锁、查字 |

### 按键名

`shortcut=` 是键的名字。长按、上下滑、键角小字都按名字取，用户在 App 的 **按键 → 按键字符和快捷键** 里也按名字改：

- 和 App 自带的键同名（`full/a`、`g18/we`、`t9/2`、`num/5`、`fn/backspace`……）：长按、滑动、角标就是 App 那个键的，用户改一处两块键盘都变；布局里给这样的键写的 `alt=`、`swipe-up=`、`<popup>` 不起作用（检查给警告）；它的字母分组也要和 App 那个键的一样，写了别的字母时长按仍按 App 那个键的字母（检查给警告，要用这些字母就给它起个自己的名字）。从 App 自带的键盘改来时，留下的键保留原来的名字。
- 自己的新键写 `布局 id/键 id`（`k17/hjk`）：布局里写的长按列表、`alt=`、`swipe-up=` 等是默认值，用户在 App 里改过的以用户的为准。新名字必须带 `/`，`/` 前面不能是 App 的前缀（`full`、`g14`、`g18`、`t9`、`zy9`、`zyfull`、`num`、`fn`）。
- 不写名字的键没有长按、滑动的设置（检查给警告；底稿里同 id 的键也没名字的不警告，比如数字键盘的 `+ - * /`）。

### 条件 `when=`

空格分开的几项都满足才算；逗号加空格（`, `）分开的几组满足一组就算；`!` 取反。带值的写 `名字=值`，几个值用 `|` 分开：

- 带值（只写名字不写值表示「有任何值」）：`ime`（`pinyin`、`shuangpin`、`english`、`table`……）、`scheme`（双拼方案）、`scheme-types`（双拼方案要的键，如 `semicolon`）、`table`、`table-extra`（码表要的额外键：`semicolon`、`star`）、`field`（`url`、`email`……）、`pad`（数字键盘的那几种：`full`、`phone`、`datetime`；以前的 `digits`、`signed`、`decimal`、`signed-decimal` 照样读，但不再有这几种，写着它们的键不显示，网页编辑器提示一句）、`action`。
- `split-side=`（`left`、`right`）只在分体后的半边用，`on-split="split"` 的键盘里写它是错误。
- 开关：`roots`（码表显示字根且没按 ⇧：按着 ⇧ 时字母键打字母本身，写 `when="roots"` 的键和变体这时不显示）、`compact`、`landscape`、`split`、`floating`、`one-handed`、`voice`、`hold-voice`、`lang-key`、`composing`、`looking-up`、`table-lookup`（当前码表把拼音反查放在查字键上：二笔、仓颉、自然码等有，五笔、五笔拼音没有，0.6.3 起认）、`shifted`、`caps-lock`。

例：AZERTY 的 `;` 键 `when="ime=shuangpin scheme-types=semicolon, table-extra=semicolon"`；App 的数字键盘里只在完整的那种出现的 `=` 键 `when="pad=full"`；App 的全键盘左下角，码表里有反查时是「拼」键 `when="table table-lookup !looking-up"`，没有时是 ⇧ `when="!table !composing, table !table-lookup"`。

## 检查什么

导入和网页保存时都查一遍。只有下面这些是**错误**（导入不了、存不了），别的都是警告，照样能用。

**错误**：
- 读不了：不是这种 XML、写了不认识的元素或属性、值不对；画成图标的键没写 `a11y=`（读屏念不出）；要求的 App 版本（`min-app`）比手机上的新。
- 身份：`id`、`name`、`family`、`based-on` 不合规（见上面的属性表）；App 没有这块底稿。
- 上限：文件超过 64 KB、超过 150 个键、键上的字超过 16 个、一次打出的文字超过 64 个、`commit:` 超过 100 个。
- 排不下：在各种屏幕宽度、横竖、紧凑、分体、输入框、这块能用的输入法（双拼排自然码、小鹤、微软三种方案，码表排要 `;`、要 `*` 两种）、数字键盘的每种输入框下各排一遍，横屏还在最矮的键盘高度（144 dp）下排一遍：哪一种排不下（一行太宽、跨行的键和下面的键撞上、同一时刻两个键同 id）。
- 字母分组的样子：每键 1–5 个字母（a–z 和 `;`，一个键里不重复），最多 30 组。
- 安全：名字、`<meta>`、键的 id 和名字（`shortcut=`）或要打出的文字里有看不见的字符（控制字符、方向字符、零宽字符、换行）；字母键和空格上用了底稿同一个键没有的命令（`shortcut:`、`lang` 这类，或功能键的按键名）；`layout:` 切到 Text、Number、Symbol 以外的面板；按键名用了 App 的前缀（`full/`、`g18/` 这样）App 却没有这个键；`<include>` 引用 App 没有的片段。

**缺键的警告**：拿 App 自带的那块（底稿）比，缺了什么就说什么、会怎样，每样只说一次。缺键的布局照样导入、照样画出来，也照样能从备份恢复。
| 缺的 | 会怎样 |
|---|---|
| ⌫ | 删不了字，打拼音时打错的字母去不掉（不在打字时可以用工具栏的文本编辑面板删） |
| 回车 | 换不了行，也按不了发送、搜索 |
| 返回键（数字键盘的 `role="back"`、符号面板的 `send="panel-back"`） | 回字母键盘要收起键盘再点输入框；数字输入框里一直是这块数字键盘 |
| 地球键 | 换输入法要把 **空格键长按行为** 设成切换输入法（也没有空格时这条路也没有，两样合成一条说） |
| 空格（`letters`、`grouped`）、Shift（`letters`） | 打不出空格、大写。九键不查空格：缺了照样画，不提醒 |
| 数字键（`nine` 的 0–9，按 id 比） | 这块键盘上打不出 |
| 数字键盘的 0–9（按键打出的字比，id 随便） | 数字输入框里键盘直接是这块，打不出。只在某几种输入框下缺的，说是哪几种 |
| 字母（`letters` 的 a–z 按 id 比；`grouped` 是没在任何键上的字母，ü 用 v） | 那几个字母打不出（密码框里也用这块键盘）；分组键盘照样用自己的分组 |
| 位置 `nine-side`、`symbol-categories`、`symbol-grid` | 九键左列不显示；符号面板没有分类，或者没有符号可选 |

`scroll`（滑动列）不算缺：App 的数字键盘有一列，自己的数字键盘没有也不提醒。滑动列也不顶替 `nine-side`：九键上要左列，还是写 `nine-side`。

**别的警告**：有键在某种情况下什么都不显示；数字键盘上有键打出字母（数字输入框多半不收字母）；没有去数字键盘或符号面板的键；用了 App 自带键的名字又写了长按、角标、滑动（不起作用）；用了 App 自带键的名字、字母分组却和那个键不同（长按按 App 那个键的字母）；底稿是 App 键盘时写的 `popups="off"`（不起作用，照底稿弹出）；键宽不到整行的 1/20（10 列时是半个单位）；有 `commit:`；按当前主题会画成空白的键；没有名字的键；`<gap>` 上写了 `look=`、`class=`；数字键盘、符号面板底排左边两格不是符号键 / 数字键和返回键（和字母键盘的 **符**、**123** 同位）；完整的数字键盘缺 `-` 或 `.`（各种数字框都用它）、电话输入框的数字键盘缺 `+ * #`、日期时间的缺 `- / :`（这种输入框里打不出来；有滑动列的，列在这种框里显示的那一类的默认项也算打得出，比如 `list="nine"` 的列在完整的数字键盘上没有 `-`）；滑动列太挤（在某种排法下一项沿滑动方向不到 20 dp：减少 `show=` 或加宽）；`on-split="split"` 的键盘里 `split="halves"` 的行在某种情况下（比如某个输入法多出的键）不是 9 或 10 个键，分体时只能整行缩小。

哪些输入法能用：`letters` 给拼音、双拼、英文、码表；`nine` 给拼音、双拼、英文（九键）；`grouped` 给拼音，分组和 App 的 14 键或 18 键一样（顺序不管）时双拼也能用；`number`、`symbols` 不属于哪个输入法，按 `pads` 和面板用。

主题包里带的键盘布局导入主题时转成自定义布局，规矩和上面一样：缺键只提醒、照样转（0.6.3 起；0.6.1、0.6.2 不转缺键的那块，这样的布局写 `min-app="0.6.3"`）。主题包里替换 App 键盘的注音、注音九键、手写和分组底排片段不是自定义布局，缺了 App 那块必需的键照旧不用，画 App 自带的。

## 主题怎么画它

布局只管键的位置和做什么，样子由主题定。主题里通用的选择器（`key`、`key.letter`、`[role=enter]`、`*/enter`、键的 `class=`）照样生效；按键盘写的（`k17/hjk`、`@keyboard=k17`）按布局的 id 写。

## 和主题包里的 `layouts/` 的区别

主题包里的布局按 App 自带键盘的 id 起名（`k18.xml`），用来顶替那块键盘；`.layout` 有自己的 id，是多出来的一块，用户选了才用。写法、检查是同一套。
