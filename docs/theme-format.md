# 主题格式 4.0 参考

这一页写给自己做主题的人：一个 4.0 主题包里有什么，`theme.xml` 怎么写，每个属性取什么值，输入法收不收。
大多数人用网页上的主题制作页就够了，制作页导出的就是这种包；想手写或者改别人的主题时再看这一页。

> 这页的属性表由一个测试和输入法的代码对齐：代码多认一个属性、少认一个属性，测试都会失败，所以表里写的就是这一版认的。

## 主题包

主题包是一个 `.theme` 文件（其实是 zip），在主题页的 **新增主题 → 导入主题文件** 里选它。包里只能有这些：

| 位置 | 内容 |
|---|---|
| `theme.xml` | 主题本身，必须在 zip 的最上层（整个文件夹打包进去的会被拒，提示要放到最上层） |
| 最上层的其它文件 | `theme.xml` 用文件名提到的图片、字体、图标、预览图。没被提到的文件不会保存（导入时直接丢掉） |
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
</theme>
```

子元素必须按这个顺序出现：`meta`、`colors`、`picture`、`font`、`style`、`icon`。

### `<theme>`

| 属性 | 必需 | 取值 |
|---|---|---|
| `xmlns` | 是 | 固定为 `https://youmo-ime/theme/4` |
| `name` | 是 | 1–64 个字符 |
| `variants` | 是 | `light`、`dark` 或 `light dark`（两种都有，跟随系统深浅色）。旧写法 `dark="true/false"` 也认，但不能和 `variants` 同时写 |
| `version` | 是 | `4.x`，如 `4.0`。只认 4.x；`5.0` 这样的新格式导入会被拒绝 |

### `<meta>`（可选，最多一个）

`author`、`version`、`license` 各 1–64 个字符；`min-app` 是最低输入法版本（如 `0.6.0`，更旧的输入法拒绝导入）；`preview` 是导入时显示的预览图，`.png` 或 `.webp`。

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

键盘背景图。`src` 是原图，`brightness` 0%–100%（默认 100%），`blur` 0–25dp（默认 0）。里面至少一个 `<cut orientation="portrait|landscape" file="…"/>`，是竖屏、横屏实际用的裁剪图（`rect`、`rotation="0|90|180|270"` 记录裁剪的位置，制作页再编辑时用）。`animated="true"` 是保留写法，见「保留名单」。

### `<font>`、`<icon>`

`<font name src>` 定义一个字体，最多 4 个，样式里用 `font="名字"` 引用；`system` 是系统字体，不用定义。
`<icon name src>` 定义一个图标，最多 200 个，名字是小写字母、数字和 `-`。这一版先检查、保存图标，键上还是画输入法自己的图标（`icon(名字)` 只认输入法自带的图标名）。

### `<style>`

`<style select="选择器" 属性="值" …/>`：选中的部件用这些属性。一个样式至少设一个属性。输入法最多认 4100 个样式（单独设置了 400 个键的 3.0 主题转出来有这么多），网页制作页也一样，App 导出的 3.0 主题都能在制作页打开。

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

**部位**：键的 `label`（主字）、`mark`、`shortcut`、`root`（字根）、`icon`、`hint`、`badge`；候选的 `label`（序号）、`comment`；`picker categories`（符号面板的分类栏）、`voice mic`（语音面板的话筒）。

**场景**（写在最前，`@` 开头）：`@compact`、`@landscape`、`@floating`、`@split`、`@one-handed`、`@dark`、`@flat`（平铺按键样式）；
`@im=<输入方式>`（`pinyin`、`shuangpin`、`english`、`table`、`handwriting`，或码表名 `wubi`、`cangjie`、`ziranma`、`erbi`、`wubipinyin`）；
`@field=password|url|number|email`；`@keyboard=<布局 id>`（如 `@keyboard=nine`）。

**优先级**：键名像 CSS 的 id；类、角色、状态、场景、`qwerty/*` 各算一个 class；`*/角色` 算两个（比 `.enter` 这种种类高）；部件和部位算 element。一样高时后写的赢。

## 属性

尺寸用 `dp`（`0` 可以不带单位），字号用 `sp`。「颜色」指上面的颜色写法。

| 属性 | 取值 |
|---|---|
| `fill` | 颜色；`linear(角度deg, 颜色 位置%, …)`（2–8 个色标）；`image(文件, 左 上 右 下[, Nx][, 颜色])`（九宫格图片，四个数是源图的边距像素，`Nx` 1–8 是每 dp 几像素，颜色是图片没加载时的底色）；`picture` 或 `picture(模糊dp, 亮度%[, 颜色])`（透出键盘背景图，模糊 0–25dp，亮度 0–100%） |
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
| `align` | 键上主字的位置：`center`、`top-left`、`top-center`（写在 `label` 部位上） |
| `letter-spacing` | 字距，-0.1em–0.5em |
| `stroke` | 字的描边：`宽度 颜色`，宽度 0–3dp |
| `text-shadow` | 字的阴影，写法同 `shadow` |
| `font-han` | 汉字用的字体，写法同 `font` |
| `font-latin` | 拉丁字母用的字体，写法同 `font` |
| `space-content` | 空格键上显示什么：`ime-name`（输入法名，默认）、`none`、`text(文字)`（最多 32 字）、`icon(图标名)`（写在 `label` 部位上） |
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
| `transition` | 键变到这个状态时的过渡：`时长ms 缓动`，如 `150ms ease-out`；时长 0–600ms，缓动 `linear`、`ease`、`ease-in`、`ease-out`、`ease-in-out`（弹出层、气泡上的还不生效，会提醒） |
| `press-scale` | 按下时键面缩放（`key:pressed`），85%–110% |
| `press-sink` | 按下时键面下沉（`key:pressed`），0–4dp |
| `return-icon-send` | 回车键在「发送」时显示的：`icon(图标名)` 或 `text(文字)`（写在 `icon` 部位上，下同） |
| `return-icon-search` | 回车键在「搜索」时显示的 |
| `return-icon-go` | 回车键在「前往」时显示的 |
| `return-icon-next` | 回车键在「下一项」时显示的 |
| `return-icon-done` | 回车键在「完成」时显示的 |
| `return-icon-newline` | 回车键在「换行」时显示的 |

**尺寸**（属性名和保留名单里的相同，但只在下面这些部件上生效）：

- `keyboard` 上（不能加场景、状态）：`height` 屏幕高度的 10%–90%，`side-padding` 0–300dp，`bottom-padding` 0–100dp；写两个值是「竖屏 横屏」。主题给了尺寸时，设置里的键盘高度、边距跟随主题。
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
| `appear` | 弹出的出现动画（`popup`、`preview`、`menu`） | `fade|scale|rise 时长ms` |
| `effect` | 按键特效（`key:pressed`） | `none`、`ripple-image(文件, 半径%, 时长ms)`、`particles(文件, 个数, 散开dp, 寿命ms)` |
| `sticker` | 键上的贴纸层 | `none` 或文件名 |

另外两个保留写法：元素 `<sounds>`、`<sound>`（按键音包），以及 `<picture animated="true">`（动态背景）。

## 限制

| 什么 | 上限 |
|---|---|
| 整个包 | 300 个文件，单个文件 32 MB，解压后合计 64 MB |
| `theme.xml` | 1 MB；样式 4100 个，颜色 200 个，字体 4 个，图标 200 个 |
| 背景图（原图和裁剪图） | 边长 4096 像素 |
| 键面图片（`image()`） | 边长 1024 像素，单张 2 MB |
| 预览图 | `.png` 或 `.webp`，512 KB，边长 2048 像素 |
| 字体 | 能被系统加载；大小只受单个文件 32 MB 的限制 |
| 图标 | SVG，64 KB，只能有 `<svg>`、`<g>`、`<path>` 和纯色填充（`fill`、`fill-rule`、`fill-opacity`、`opacity`）；`fill` 只能是 `#` 颜色、`none` 或 `currentColor`；最多 256 条路径、路径里合计 20000 个数。脚本、链接、`style`、渐变、描边、文字、变换都会被拒 |
| `layouts/` | 16 个布局，每个 64 KB |
| 手机上保存的主题包 | 100 个 |
| 场景组合 | 设了 `fill`/`hidden` 的键样式里用到的场景（或状态）组合超过 512 种时拒绝导入（见上） |

文件名不能带路径（`/`、`\`、`:` 等），不能全是点。

## 3.0 主题怎么转

0.5 版做的 3.0 主题（`.json` 加图片的 zip）照样能导入，导入后仍是 3.0 主题，不会改动。
输入法画它时，把它的外观换算成一个 4.0 主题：24 个颜色原样成为 `<colors>`，形状、键缝、按下效果写成 `key` 的样式，四种键各成一个类（`key.function` 等），单键的样式按键名选中（`qwerty/q`、`*/enter`）。

在主题列表里 **导出** 一个 3.0 主题，得到的就是这样换算出的 4.0 主题包（动态取色主题导出当时的颜色），可以拿去网页制作页接着改。背景原图边长超过 4096 像素的，用竖屏裁剪图当原图，保证导出的包能再导入。
