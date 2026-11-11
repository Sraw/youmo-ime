# 主题格式 4.0 参考

这一页写给自己做主题的人：一个 4.0 主题包里有什么，`theme.xml` 怎么写，每个属性取什么值，输入法收不收。
大多数人用网页上的主题制作页就够了，制作页导出的就是这种包；想手写或者改别人的主题时再看这一页。

> 这页属性表、保留名单和系统颜色表里的名字由一个测试和输入法的代码对齐：代码多认一个属性、少认一个属性，测试都会失败，所以表里的名字就是这一版认的。取值范围和限制没有测试核对，以这一版的实际行为为准。

## 主题包

主题包是一个 `.theme` 文件（其实是 zip），在主题页的 **新增主题 → 导入主题文件** 里选它。包里只能有这些：

| 位置 | 内容 |
|---|---|
| `theme.xml` | 主题本身，必须在 zip 的最上层（整个文件夹打包进去的会被拒，提示要放到最上层） |
| 最上层的其它文件 | `theme.xml` 用文件名提到的图片、字体、图标、按键音、预览图。没被提到的文件不会保存（导入时直接丢掉） |
| `layouts/*.xml` | 主题自带的键盘布局，按布局的 id 起名（`qwerty.xml` 替换 26 键）。只能放文件名普通的 `.xml`，不能再有文件夹，否则整个包被拒；用不上的布局会在导入确认里列出来 |

除 `layouts/` 以外的文件夹（Mac 打包带的 `__MACOSX/` 除外，会被忽略）都会让整个包被拒。

导入前整包检查一遍，任何一项不过就不导入，并告诉你哪一行有什么问题（最多列前 5 条）：

- `theme.xml` 读不出来，或 `<meta min-app>` 比这个输入法新；
- Shift、退格、空格、回车四个键在任何一种场景、状态下被做成「空白」：字和图标都隐藏、键面又不是图片；
- 设了 `fill` 或 `hidden` 的键样式里用到的场景（或状态）组合起来超过 **512** 种，没法逐一检查，直接拒；
- 提到的文件不在包里，或超过下面「限制」一节的大小；
- 主题名和内置主题、0.5 版的旧主题名重名，以 `.` 开头，或以 `#light`、`#dark` 结尾。

只提醒、不拒绝的：这一版还不认的名字（见「保留名单」），以及颜色 `keyText` 和 `key` 的对比度低于 3:1（浅色、深色各查一次）。

同名的包再导入一次会替换旧的；同名的 3.0 主题也会被它替换。

### 自带布局里的长按和滑动

0.6.0 起每个键的长按列表、长按首选（键角小字）、上滑和下滑都由输入法按键的名字（`shortcut=`）统一给出，用户在 **按键 → 按键字符和快捷键** 里改。主题自带布局里和这些有关的写法这样处理：

| 写法 | 怎么处理 |
|---|---|
| `shortcut="名字"` | 键的名字，设置里按它存用户的改动。字符键写 `布局/键`（`g18/we`），功能键写 `fn/名字`；只写 `comma`、`backspace` 这类不带 `/` 的名字时当作 `fn/comma`、`fn/backspace`。换了主题后，旧主题里有、新主题里没有的名字，设置照样留着，只是不显示、不起作用，换回来又有。 |
| 字符键的 `swipe-up="x"`、`alt="x"` | 照旧能读，是**这个主题给的默认长按首选**：`x` 是一个字时打出它，`keypad:N` 和数字键盘的 N 一样（打字时选第 N 个候选），`action:…` 做这个动作。它不在这个键的长按列表里时加到符号最前面。用户在设置里改过这个键的首选时，用用户的。 |
| `<popup>` 里的 `<item send="…">` | 0.6.0 起生效：选这一项做 `send` 写的事，不再只打出它的字。用户能把它挪位置、设为首选、删掉，不能新加这种项。 |
| 功能键上的 `<popup>`、`long-press`、`swipe-up` | 不起作用：一个功能键（`fn/…`）在所有键盘上都一样，长按、上滑、下滑只听输入法自带的和用户在功能键页设的。 |
| 主题自带的分组键（如 `g18/we`） | 和内置的 14/18 键一样：一行是组里的字母和角标，角标用这个键的 `swipe-up`，符号是内置的。 |

<!-- verify after S6: 设计 §3.4/§5 与评审都没定：alt="{alt:x}" 绑定删掉后旧主题包里这样写的键读音怎么样；键盘级 popups="off" 在主题布局里是否还认 -->
内置布局不再写 `swipe-up`、`alt`，功能键也不写 `<popup>`、`long-press`、`swipe-up`；网页主题制作的示例 `k17-motion/layouts/k18.xml` 仍保留旧写法，作为旧主题包能照常导入的样例。

## theme.xml 的结构

```xml
<theme xmlns="https://youmo-ime/theme/4" name="我的主题" variants="light dark" version="4.0">
  <meta author="某人" version="1.2" license="CC-BY-4.0" min-app="0.6.0" preview="preview.png"/>
  <colors>
    <color name="key" value="#ffffff"/>
    …（必需的 24 个颜色，见下）
  </colors>
  <colors variant="dark">
    <color name="key" value="#303030"/>
  </colors>
  <picture src="photo.jpg" brightness="80%" blur="4dp">
    <cut orientation="portrait" file="photo-portrait.jpg"/>
  </picture>
  <font name="hand" src="hand.ttf"/>
  <style select="key" fill="@key" radius="6dp" color="@keyText"/>
  <style select="key:pressed" overlay="@keyPressHighlight"/>
  <icon name="my-enter" src="enter.svg"/>
  <sounds>
    <sound key="letter" src="tap.wav"/>
    <sound key="space" src="space.ogg"/>
  </sounds>
</theme>
```

子元素必须按这个顺序出现：`meta`、`colors`、`picture`、`font`、`style`、`icon`、`sounds`。

### `<theme>`

| 属性 | 必需 | 取值 |
|---|---|---|
| `xmlns` | 是 | 固定为 `https://youmo-ime/theme/4` |
| `name` | 是 | 1–64 个字符 |
| `variants` | 是 | `light`、`dark` 或 `light dark`（两种都有，跟随系统深浅色）。旧写法 `dark="true/false"` 也认，但不能和 `variants` 同时写 |
| `version` | 是 | `4.x`，如 `4.0`。只认 4.x；`5.0` 这样的新格式导入会被拒绝 |

### `<meta>`（可选，最多一个）

`author`、`version`、`license` 各 1–64 个字符；`min-app` 是最低输入法版本（如 `0.6.0`，更旧的输入法拒绝导入；只比数字部分，`0.6.0-beta1` 这样的测试版算 0.6.0；自己从源码编的、版本名带 `-<提交数>-g<哈希>` 或 `-untagged` 的开发包不检查）；`preview` 是导入时显示的预览图，`.png` 或 `.webp`。

### `<colors>`

一个 `<colors>` 是必需的；有深色版时再加一个 `<colors variant="dark">`，只写和浅色不同的颜色。每个 `<color name value>` 定义一个颜色，最多 200 个；名字以字母开头，字母、数字、`_`、`-`，最长 32 个字符。

必须定义的 24 个颜色（输入法自己的界面按这些名字取色）：
`background` `bar` `keyboard` `key` `keyText` `candidateText` `candidateLabel` `candidateComment` `altKey` `altKeyText` `accentKey` `accentKeyText` `keyPressHighlight` `keyShadow` `popupBackground` `popupText` `spaceBar` `divider` `clipboardEntry` `genericActiveBackground` `genericActiveForeground` `keyBorder` `keyHint` `toolbarIcon`。

颜色的写法（在 `<color value>` 和样式属性里一样）：

| 写法 | 意思 |
|---|---|
| `#rrggbb`、`#aarrggbb` | 直接给颜色 |
| `@name` | 引用 `<colors>` 里的颜色 |
| `transparent` | 透明 |
| `darker(c, n%)`、`lighter(c, n%)` | 往黑、往白靠 n% |
| `alpha(c, n%)` | 把透明度设成 n% |
| `mix(a, b, n%)` | 从 a 往 b 走 n% |
| `system(名字, c)` | 系统的动态取色（Material You），名字见下面的「系统颜色」；系统给不了时（旧 Android、网页预览）用 c。名字不在表里的，整个颜色算错 |

函数可以嵌套，最多 8 层；一个颜色最长 256 个字符；`darker`、`lighter`、`alpha`、`mix` 的 n 是 0–100 的整数。

#### 系统颜色

`system()` 认这些名字：

| 名字 | 是什么 |
|---|---|
| `primary` | Material 角色：主色 |
| `on-primary` | 主色上的字 |
| `secondary-container` | 次要容器 |
| `surface-container` | 表面容器 |
| `surface-container-high` | 表面容器（高） |
| `surface-container-highest` | 表面容器（最高） |
| `surface-bright` | 亮表面 |
| `on-surface` | 表面上的字 |
| `on-surface-variant` | 表面上的次要字 |
| `accent1-N` | Android 调色板 accent1 |
| `accent2-N` | Android 调色板 accent2 |
| `accent3-N` | Android 调色板 accent3 |
| `neutral1-N` | Android 调色板 neutral1 |
| `neutral2-N` | Android 调色板 neutral2 |

N 取：`0` `10` `50` `100` `200` `300` `400` `500` `600` `700` `800` `900` `1000`（如 `accent1-500`）。

### `<picture>`（可选，最多一个）

键盘背景图。`src` 是原图，`brightness` 0%–100%（默认 100%），`blur` 0–25dp（默认 0）。里面至少一个 `<cut orientation="portrait|landscape" file="…"/>`，是竖屏、横屏实际用的裁剪图（`rect`、`rotation="0|90|180|270"` 记录裁剪的位置，制作页再编辑时用）。`animated="true"`：`src` 是动画 WebP，键盘按居中裁剪播放（`<cut>` 只用作静态图）。限制：文件不超过 4 MB、长边不超过 720 像素（解码随像素数增加，1080 宽的动画在中档手机上要占满约一个 CPU 核；键盘按居中裁剪放大铺满）、不超过 300 帧、每帧至少 50 ms（最快 20 帧/秒）；超过任何一项，或不是动画 WebP，就显示静态的 `<cut>` 图，导入时给出提示（导入只查文件头：文件头对、画面却解不出来的，也显示静态图，但导入时不提示）。动态背景上 `blur` 不生效；按键、候选栏等透出背景的地方只按亮度压暗，不比背景更亮、不做模糊（导入时会提示），文字建议加 `stroke` 或 `text-shadow` 保证看得清。只在「键盘动效」开着、省电模式和「禁用动画」都关着时播放，键盘收起或屏幕关掉时也停。

### `<font>`、`<icon>`

`<font name src>` 定义一个字体，最多 4 个，样式里用 `font="名字"` 引用；`system` 是系统字体，不用定义。
`<icon name src>` 定义一个图标，最多 200 个，名字是小写字母、数字和 `-`，文件只能是 SVG（限制见文末）。

- **替换自带图标**：名字和输入法自带的图标相同时，键盘上凡是画这个图标的地方都换成主题的：键上的图标、角标、Shift 的三种状态、回车随动作变的图标、按键气泡、长按弹出和键盘里画的菜单弹出层。工具栏、剪贴板和编辑这些面板、系统样式的长按菜单不换。每个状态、每种动作是一个名字，只换写了的那个：`shift` 只换没按下时的 Shift，单次和锁定是 `shift-once`、`shift-locked`；`enter` 只换换行时的回车。自带图标的名字：

  | 用在 | 名字 |
  |---|---|
  | 回车（随输入框要的动作） | `enter`（换行）、`go`、`search`、`send`、`next`、`done`、`previous` |
  | 键 | `backspace`、`language`、`space`、`shift`、`shift-once`、`shift-locked`、`number-pad`、`lock`、`lock-open`、`emoji`、`voice`、`arrow-upward`、`arrow-downward` |
  | 快捷键的命令 | `select-all`、`content-copy`、`content-cut`、`content-paste`、`undo`、`redo`、`delete-sweep`、`restore`、`field-start`、`field-end`、`cursor-move`、`clipboard`、`quick-input`、`emoji`、`voice`、`trad-simp`、`choose-keyboard`、`hide-keyboard`、`swipe-select` |
- **新名字**：不是自带的名字，可以在 `icon(名字)`（`space-content`、`return-icon-*`）和主题自带布局的 `icon=` 里用。一个 `<icon>` 既不是自带的名字、又没被任何 `icon()` 用到时给警告「不会画」（只被主题布局用到的也会收到这条警告）。
- **颜色**：路径没写 `fill`（自己的或从 `<g>`、`<svg>` 继承的）或写 `currentColor` 时，用部件的 `icon-color` 着色——注意浏览器里没写 fill 是黑色，这里不是。写了 `#颜色` 的路径保留这个颜色。SVG 的颜色按 CSS 的写法读：`#rgb`、`#rgba`、`#rrggbb`、`#rrggbbaa`，**透明度在最后**，和主题里 `#aarrggbb` 的写法相反。`icon-color` 的透明度乘到所有路径上；`icon-color` 是 `transparent` 时整个图标不画。
- **透明度**：`opacity`、`fill-opacity` 乘到每条路径上。`<g opacity>` 是分别乘到组里的每条路径，重叠的地方会比浏览器里看到的深（浏览器先合成整个组再变透明）；网页预览和键盘上画的一样。
- **大小和位置**：图标总是画在自带图标的位置和大小上（24dp 见方），不跟 SVG 的 `width`、`height` 走。`viewBox`（没有就用 `width`/`height`，再没有就是 24×24）按比例缩放、居中放进去，不会拉伸（即 SVG 的 `xMidYMid meet`）。
- **必须画得出东西**：至少一条路径真的会画：`fill` 不是 `none`、透明度不是 0、不只有 `M`（移动）。否则导入时报「nothing to draw」。面积为零的路径（比如 `M0 0L1 0Z`）不检查，算作会画。
- 路径支持 SVG 的全部命令（`M L H V C S Q T A Z` 和小写的相对写法），包括弧和连写的弧标志（`a1 1 0 011 1`）。
- 导入后文件坏了、超过 64 KB 或不见了：画同名的自带图标（没有自带的就不画）。

### `<style>`

`<style select="选择器" 属性="值" …/>`：选中的部件用这些属性。一个样式至少设一个属性。输入法最多认 4100 个样式（单独设置了 400 个键的 3.0 主题转出来有这么多），网页制作页也一样，App 导出的 3.0 主题都能在制作页打开。

### `<sounds>`（可选，最多一个）

主题自带的按键音。里面只能放 `<sound key src>`，最多 16 个，可以一个都没有；不分浅色、深色。`key` 是按键的类别，用选择器里的词：

| `key` | 哪些键 |
|---|---|
| `letter` | 字母、数字、符号等普通键；九宫格的键、拼音音节栏 |
| `function` | 功能键（Shift、切换键盘等，`kind` 为 `function` 的键） |
| `space` | 空格 |
| `enter` | 回车 |
| `backspace` | 退格（编辑面板里的删除也算） |

同一个 `key` 写两次是错误。主题没给的类别用这个主题的 `letter`；`letter` 也没给，就放系统的按键音。
不认识的 `key`（以后的新类别）和 `<sounds>`、`<sound>` 上不认识的属性只提醒、不报错；这样的 `<sound>` 的文件照样检查、保存和导出。

声音文件的要求（三种格式一样）：

- 格式是 **Ogg Vorbis**（`.ogg`）、**WAV**（`.wav`，只收 8 位或 16 位 PCM）或 **MP3**（`.mp3`，MPEG-1/2/2.5 Layer III）；Ogg Opus、浮点或 24 位的 WAV、其它编码都会被拒。扩展名必须和内容一致（比如内容是 WAV 的 `.mp3` 会被拒）。
- 单个文件不超过 **100 KB**，时长不超过 **1 秒**，1 或 2 个声道，采样率 8000–48000 Hz。
- Ogg 只能有一条音频流；时长按最后一页的位置算。WAV 的时长按 `data` 的长度算。
- MP3 的时长**逐帧数出来**，不看 Xing/Info/VBRI 标签里写的帧数（这些标签所在的那一帧也算进时长，约多 26 ms）。开头的 ID3v2 标签可以有，后面必须紧接着第一帧；帧后面只能是 ID3v1 或 APEv2 标签，有别的数据就拒。所有帧的版本和采样率必须相同，不收 free format 的码率。
- MP3 开头有约 25 ms 的编码空白，想按下就响用 WAV 或 Ogg。

时长只按文件头算；解码后的声音实际有多长还受文件大小和系统对单个声音的内存上限约束，解不出来的放系统声。
声音在 **设置 → 声音与振动** 里开着「按键音」、「按键音音色」选「跟随主题」时才放（见 [settings.md](settings.md)）。

## 选择器

和 CSS 的写法、优先级规则一样：`select="key.function, key.space"` 逗号分隔，满足任一个就选中；一个选择器里的条件要同时满足。

```
[@场景 …] 部件[.类][[role=角色]][:状态] [部位]
```

**部件**

| 部件 | 是什么 |
|---|---|
| `keyboard` | 整个键盘底板（也管键盘尺寸，见属性表） |
| `key` | 键 |
| `popup`、`preview`、`menu` | 长按弹出的候选、按下时的气泡、长按菜单 |
| `panel` | 剪贴板、符号等原生面板的底 |
| `bar`、`candidate`、`candidates-divider`、`candidates-panel` | 候选栏的底、一个候选、候选间的分隔线、展开的候选面板 |
| `preedit`、`toolbar` | 正在输入的编码、工具栏按钮（`:selected` 是开着的开关） |
| `frame`、`handle`、`side-button`、`split-gap`、`navbar` | 窗口外框、悬浮键盘的拖动条、单手模式侧边按钮、分体键盘中缝、导航栏 |
| `clipboard`、`picker`、`editing`、`voice` | 剪贴板、符号表情、文本编辑、语音面板 |

每个部件只认一部分属性；设了它不认的属性会有提醒，不生效。

**选中某个键**

| 写法 | 选中 |
|---|---|
| `key` | 所有键 |
| `qwerty/q` | 布局 qwerty 里名为 q 的键（`\` 转义特殊字符：`number/\*` 是星号键，`zhuyin/\,` 是逗号键） |
| `qwerty/*` | 布局 qwerty 的所有键 |
| `*/enter` | 每个布局里角色是 enter 的键 |
| `key.function` | 种类是 function 的键（种类：`letter`、`function`、`space`、`enter`；布局里自己写的 `class=`，如 `alt-text`，也能这样选） |
| `key[role=shift]` | 角色是 shift 的键 |

**状态**：`:pressed`、`:locked`、`:shifted`、`:disabled`、`:selected`、`:on`、`:first`（第一个候选）。

**部位**：键的 `label`（主字）、`mark`、`shortcut`（快捷图标，功能键的滑动提示也按它着色；`hidden` 只藏字符键上的）、`root`（字根）、`icon`、`hint`（只用 `hidden`：藏起功能键的滑动提示，如空格的光标、⌫ 的清除）、`badge`；候选的 `label`（序号）、`comment`；`picker categories`（符号面板的分类栏）、`voice mic`（语音面板的话筒）。

**场景**（写在最前，`@` 开头）：`@compact`、`@landscape`、`@floating`、`@split`、`@one-handed`、`@dark`、`@flat`（平铺按键样式）；
`@im=<输入方式>`（`pinyin`、`shuangpin`、`english`、`table`、`handwriting`，或码表名 `wubi`、`cangjie`、`ziranma`、`erbi`、`wubipinyin`）；
`@field=password|url|number|email`；`@keyboard=<布局 id>`（如 `@keyboard=nine`）。

**优先级**：键名像 CSS 的 id；类、角色、状态、场景、`qwerty/*` 各算一个 class；`*/角色` 算两个（比 `.enter` 这种种类高）；部件和部位算 element。一样高时后写的赢。

## 属性

尺寸用 `dp`（`0` 可以不带单位），字号用 `sp`。「颜色」指上面的颜色写法。

| 属性 | 取值 |
|---|---|
| `fill` | 颜色；`linear(角度deg, 颜色 位置%, …)`（2–8 个色标）；`image(文件, 左 上 右 下[, Nx][, 颜色])`（九宫格图片，四个数是源图的边距像素，`Nx` 1–8 是每 dp 几像素，颜色是图片没加载时的底色）；`picture` 或 `picture(模糊dp, 亮度%[, 颜色])`（透出键盘背景图，模糊 0–25dp，亮度 0–100%；动态背景上模糊不生效，亮度不超过背景的） |
| `overlay` | 颜色，盖在键面上（按下高亮常用） |
| `shape` | `rect`、`bar`、`circle`、`bubble`（气泡专用）或 `path(…)`（SVG 路径 M L H V C Q Z，坐标 0–1，最多 256 个点） |
| `radius` | 一个或四个 dp（左上、右上、右下、左下），最大 24dp；或 `50%`（胶囊、圆） |
| `border` | `宽度 颜色`，宽度 0–3dp；或 `none` |
| `shadow` | `x y 模糊 颜色`，x、y ±16dp，模糊 0–16dp；或 `none` |
| `inset` | 键与格子边的距离，1、2 或 4 个 dp（上 右 下 左，同 CSS），每个最大 24dp |
| `color` | 颜色：字的颜色。导航栏还可以写 `follow`（跟随键盘底色） |
| `font` | `system` 或 `<font>` 的名字 |
| `weight` | `300`、`400`、`700` |
| `scale` | 字的缩放，85%–130% |
| `size` | 字号如 `14sp`（数和单位间不空格，最大 48sp）；气泡的大小 100%–160% |
| `hidden` | `true`、`false`：隐藏这个部位（如键的 `mark`、`hint`） |
| `icon-color` | 颜色：图标的颜色 |
| `press` | 按下效果：`tint`、`ripple`、`face` |
| `padding` | 候选的内边距，写法同 `inset` |
| `align` | 键上主字的位置：`center`、`top-left`、`top-center`（写在 `label` 部位上）。`top-left` 离左边 4dp，键有圆角时再往里缩到圆弧以内（圆角 50% 的圆键也不出圈） |
| `letter-spacing` | 字距，-0.1em–0.5em |
| `stroke` | 字的描边：`宽度 颜色`，宽度 0–3dp |
| `text-shadow` | 字的阴影，写法同 `shadow` |
| `font-han` | 汉字用的字体，写法同 `font` |
| `font-latin` | 拉丁字母用的字体，写法同 `font` |
| `space-content` | 空格键上显示什么：`ime-name`（输入法名，默认）、`none`、`text(文字)`（最多 32 字）、`icon(图标名)`（写在 `label` 部位上；图标名是自带的，或这个主题的 `<icon>` 定义的，下同） |
| `inner-shadow` | 键面的内阴影，写法同 `shadow` |
| `depth` | 键的厚度，0–8dp（键面抬高，下面露出一条边） |
| `depth-color` | 颜色：厚度那条边的颜色 |
| `tail` | `true`、`false`：气泡有没有尾巴 |
| `offset` | 气泡上下偏移，±24dp |
| `gap` | 候选之间的距离，0–24dp |
| `label-style` | 候选序号：`none`、`number`、`dot` |
| `underline` | 正在输入的编码下划线，写法同 `border` |
| `cursor-color` | 颜色：编码里光标的颜色 |
| `blur` | 语音面板毛玻璃的模糊，0–25dp |
| `ink-color` | 颜色：手写笔迹 |
| `ink-width` | 手写笔迹粗细，1–8dp |
| `grid-color` | 颜色：手写田字格 |
| `grid` | `true`、`false`：显示田字格 |
| `transition` | 键变到这个状态时的过渡：`时长ms 缓动`，如 `150ms ease-out`；时长 0–600ms，缓动 `linear`、`ease`、`ease-in`、`ease-out`、`ease-in-out`；写在 `popup`、`preview` 上是它消失时淡出的时长和缓动（`menu` 上不生效，会提醒）；写在 `key:pressed` 上只管按下时的缩放和下沉，最长 80ms（按下的样子总是立刻出现） |
| `press-scale` | 按下时键面缩放（`key:pressed`），85%–110% |
| `press-sink` | 按下时键面下沉（`key:pressed`），0–4dp |
| `effect` | 按下时的特效（`key:pressed`）：`none`；`ripple-image(文件, 半径%, 时长ms)`，从手指按下处长大、裁在键面里并淡出的图片，半径是键面半条对角线的 10%–200%；`particles(文件, 个数, 散开dp, 时长ms)`，从按下处飞散并淡出的图片，1–12 个，散开 0–64dp（每 dp 画图片的 3 像素）；时长 1–600ms。打字快时也每次播放 |
| `appear` | 弹出层（`popup`）、气泡（`preview`）、菜单（`menu`）出现的动画：`fade\|scale\|rise 时长ms`，淡入、从键中间放大或向上升起，时长 0–250ms |
| `return-icon-send` | 回车键在「发送」时显示的：`icon(图标名)` 或 `text(文字)`（写在 `icon` 部位上，下同） |
| `return-icon-search` | 回车键在「搜索」时显示的 |
| `return-icon-go` | 回车键在「前往」时显示的 |
| `return-icon-next` | 回车键在「下一项」时显示的 |
| `return-icon-done` | 回车键在「完成」时显示的 |
| `return-icon-newline` | 回车键在「换行」时显示的 |

**尺寸**（属性名和保留名单里的相同，但只在下面这些部件上生效）：

- `keyboard` 上（不能加场景、状态）：`height` 屏幕高度的 20%–90%，`side-padding` 0–300dp，`bottom-padding` 0–100dp；写两个值是「竖屏 横屏」。主题给了尺寸时，设置里的键盘高度、边距跟随主题。
- `handle` 上：`width` 0–160dp、`height` 0–24dp，拖动条的大小。
- `bar` 上：`height` 80%–140%（相对候选字号决定的高度）。

## 保留名单

下面这些是 0.6.0 后续阶段要做的名字。现在写了会被读取、检查格式，然后忽略并提醒；格式不对仍然算错误。这样给新版做的主题在这一版也能导入。

| 名字 | 将来用在 | 格式 |
|---|---|---|
| `height` | 除上面三个部件外 | 如 `4dp` 或 `40%`，一个或两个 |
| `width` | 除 `handle` 外 | 如 `4dp` |
| `side-padding` | 除 `keyboard` 外 | 如 `4dp` 或 `40%`，一个或两个 |
| `bottom-padding` | 除 `keyboard` 外 | 如 `4dp` 或 `40%`，一个或两个 |
| `sticker` | 键上的贴纸层 | `none` 或文件名 |

## 限制

| 什么 | 上限 |
|---|---|
| 整个包 | 300 个文件，单个文件 32 MB，解压后合计 64 MB |
| `theme.xml` | 1 MB；样式 4100 个，颜色 200 个，字体 4 个，图标 200 个 |
| 背景图（原图和裁剪图） | 边长 4096 像素 |
| 键面图片（`image()`） | 边长 1024 像素，单张 2 MB |
| 特效图片（`effect` 里的文件） | `.png` 或 `.webp`，128 KB，边长 256 像素 |
| 预览图 | `.png` 或 `.webp`，512 KB，边长 2048 像素 |
| 按键音 | `.ogg`（Vorbis）、`.wav`（8/16 位 PCM）或 `.mp3`，单个 100 KB、1 秒、1–2 声道、8000–48000 Hz；`<sound>` 最多 16 个（见 `<sounds>`） |
| 字体 | 能被系统加载；大小只受单个文件 32 MB 的限制 |
| 图标 | SVG，64 KB，只能有 `<svg>`、`<g>`、`<path>` 和纯色填充（`fill`、`fill-rule`、`fill-opacity`、`opacity`）；`fill` 只能是 `#` 颜色（CSS 顺序，透明度在最后）、`none` 或 `currentColor`；最多 256 条路径、路径里合计 20000 个数（弧标志各算一个）；至少一条路径会画出东西。脚本、链接、`style`、渐变、描边、文字、变换都会被拒 |
| `layouts/` | 16 个布局，每个 64 KB |
| 手机上保存的主题包 | 100 个 |
| 场景组合 | 设了 `fill`/`hidden` 的键样式里用到的场景（或状态）组合超过 512 种时拒绝导入（见上） |

文件名不能带路径（`/`、`\`、`:` 等），不能全是点。

## 3.0 主题怎么转

0.5 版做的 3.0 主题（`.json` 加图片的 zip）照样能导入，导入后仍是 3.0 主题，不会改动。
输入法画它时，把它的外观换算成一个 4.0 主题：24 个颜色原样成为 `<colors>`，形状、键缝、按下效果写成 `key` 的样式（键缝是 `key`、`@landscape key` 的 `inset`，键盘高度紧凑时的 `@compact key`、`@compact @landscape key` 上下缩到四成；内置主题的文件也有这两条），四种键各成一个类（`key.function` 等），单键的样式按键名选中（`qwerty/q`、`*/enter`）。

在主题列表里 **导出** 一个 3.0 主题，得到的就是这样换算出的 4.0 主题包（动态取色主题导出当时的颜色），可以拿去网页制作页接着改。背景原图边长超过 4096 像素的，用竖屏裁剪图当原图，保证导出的包能再导入。

## 别的输入法的皮肤怎么转

App 的 **导入主题文件** 也收别的输入法的皮肤和主题：百度输入法的皮肤（安卓的 `.bds`、iOS 的 `.bdi`，经典引擎的那种）、搜狗输入法手机版的皮肤（`.ssf`，没加密的那种）、同文输入法（Trime）的主题（`.trime.yaml` 或 `.yaml`，单个文件，或连同 `backgrounds/`、`fonts/` 打成的 `.zip`，从 GitHub 下载的整个仓库也行）、FlorisBoard 的主题扩展（`.flex`，或装着一个 `.flex` 的 `.zip`）。导入时先在手机上把皮肤转成一个 4.0 主题包，再按上面的规则检查，和导入 `.theme` 一样先显示预览、名字和作者，点 **导入** 才装上。转出来的主题：

- 名字和作者用皮肤自己写的（皮肤没写名字就用文件名），不加后缀；同名的主题会被替换，和导入 `.theme` 一样。
- 键盘和候选栏的背景合成一张主题图（`<picture>`），候选栏在上面那一条；半透明的背景铺在它自己的平均色上。
- 字母键、功能键、空格、回车、退格、Shift 的底图转成按键图片（`image(…)`，九宫格拉伸，四周透明的边裁掉，按下的图有就用，没有就按主题的按下效果），字和图标的颜色用皮肤的；只写了某个字母的（搜狗的 `Key_Q`）按键名选中（`qwerty/q`）。按键之间的缝从皮肤的按键位置和图片算出，写成 `key` 的 `inset`。
- 24 个颜色从皮肤的背景、按键和文字颜色取，App 自己的面板（剪贴板、菜单、编辑面板）用它们画。
- 浅色、深色两版：百度皮肤的 `light/`、`dark/`，搜狗皮肤里覆盖中午的时段是浅色版，下一个时段是深色版；同文主题用 `style/color_scheme` 选的配色和它的 `dark_scheme`（选的是深色配色时是它的 `light_scheme`）；FlorisBoard 扩展里第一个浅色主题和第一个深色主题（没写 `isNight` 的按背景深浅判断）。主题只有一张主题图，用浅色版的；深色版用它背景的颜色。
- 搜狗皮肤：键盘底图写在 `[Keyboard] BG_IMAGE`（文件名或段名，六个数的拉伸区也认）时，候选栏的图叠在它上面；按键底图是 `transparent.png`（只有按下的图）时按键不画底，露出键盘图；按键的 `ICON=transparent.png`（字画在底图里）时不再叠 App 的字和图标；`keys.ini` 里带后缀的段名（`Key_G_L`）也认；预览用 `phoneTheme.ini` 的 `preview_square`。
- 同文主题：颜色是 `0xRRGGBB`/`0xAARRGGBB`、别的颜色的名字或 `backgrounds/` 里的图片（`.9.png` 按它的黑边拉伸）；没写的颜色按同文自己的回退表取；按键圆角（`round_corner`）、按键间距（`horizontal_gap`、`vertical_gap`）、`fonts/` 里的按键字体也转过来。`preset_keyboards` 里的键盘布局不读。
- FlorisBoard 主题：样式表里的 `window`、`keyboard`、`smartbar`、`key`（`:pressed`，按键码 10 回车、32 空格、负数的功能键）、`key-popup-box`、`candidates-row-item`，`@defines` 的变量，圆角（`rounded-corner`）、`margin`，背景图和扩展里带的字体。
- 按键音：转成主题的按键音（AIFF 转成 WAV，长的截到 1 秒，大的降成单声道、降采样到 100 KB 以内）。
- 键缝：皮肤的键缝；紧凑高度下上下的缝和 App 自带主题一样缩到四成。

转不过来的，导入时在「以下没有转过来」里列出：皮肤里按键的位置和大小（主题用 App 自己的布局）、盖在键盘上的装饰图、动画、单个按键的字号和字体、工具栏和候选栏的图标、画成图片的字母（只取了颜色）、不合要求的按键音、深色版的背景图、白天和夜间以外的时段、读不出、过大或文件里没有的图片、主题从别的文件引用的设置（同文的 `__include`、`__patch`）、文件里其他的主题和配色、App 自己画的面板（剪贴板、表情、单手和悬浮模式）的样子。

这些会被拒绝并说明原因：不是 zip 或损坏了；不是能转换的皮肤（`.zip` 里不是皮肤时提示改名成 `.theme`，见下）；搜狗电脑版皮肤（加密的，或只有候选窗没有键盘）；皮肤文件超过 64 MB、单独的同文 `.yaml` 超过 1 MB、超过 5000 个文件、有文件解压后超过 32 MB 或合计超过 128 MB（zip 里套的 .flex 和外层一起算）、压缩比大得反常（zip 炸弹）；有加密或用读不了的方式压缩的文件；有文件名指向皮肤以外的位置（`../`）；找不到键盘。图片超过 1200 多万像素的不解码，当作读不出。转出来的主题包再不合上面的规则，也照样拒绝。

这些暂不支持：百度的 `.bda`、搜狗的 `.sxi`、电脑版输入法的皮肤、AnySoftKeyboard 的主题（装在 APK 里）。

fcitx5-android 原版导出的主题（`.zip`，里面一个 `<名字>.json` 和主题图）改名成 `.theme` 就能导入，和本输入法 0.5 导出的一样；直接选 `.zip` 会提示改名。

转换只读皮肤里的配置和图片，不运行里面的任何东西，也不把皮肤里的文件名当路径写到手机上。网页制作页暂时不能导入皮肤（以后再加）。
