# 用户验收测试（UAT）

这里的每一条测试都站在用户的角度：**在给定的情况下，用户做给定的操作，应该看到给定的结果**。
它们只在真实的 Android 设备上跑（真机，或者 Android 模拟器），不靠单元测试替代：单元测试证明逻辑对，
这里证明用户用起来对。

每条测试都是独立的：前提里写清从哪个状态开始、自己要先做什么准备，不依赖别的测试先跑过，
也不在乎别的测试跑没跑、留下了什么。可以只挑一条跑，也可以任意顺序跑。

## 文件

| 文件 | 范围 |
|---|---|
| [01-setup.md](01-setup.md) | 安装、向导、添加和排列输入法、切换输入法 |
| [02-keyboard.md](02-keyboard.md) | 全键盘的每个键、长按、滑动、网址邮箱框、括号成对、滑行输入 |
| [03-t9.md](03-t9.md) | 九键键盘和九键拼音 |
| [04-panels.md](04-panels.md) | 数字键盘、符号面板、表情面板、表情拼音搜索 |
| [05-toolbar.md](05-toolbar.md) | 工具栏、文本编辑、剪贴板、状态面板、单手模式、悬浮键盘、计算结果 |
| [06-pinyin.md](06-pinyin.md) | 全拼：整句、简拼、纠错、模糊音、中英混输、笔画、联想、长按候选、学词、拆字、表情候选、自带日期时间、滑行 |
| [07-shuangpin.md](07-shuangpin.md) | 双拼 |
| [08-english.md](08-english.md) | 英文 |
| [09-handwriting.md](09-handwriting.md) | 手写 |
| [10-tables.md](10-tables.md) | 五笔、二笔、仓颉、自然码、五笔拼音 |
| [11-quick-unicode.md](11-quick-unicode.md) | 快速输入、Unicode 输入 |
| [12-voice.md](12-voice.md) | 语音输入（语音版） |
| [13-words.md](13-words.md) | 词库页、用户词库、屏蔽词、导入导出、导入词库、自定义短语 |
| [14-settings.md](14-settings.md) | 设置首页、通用输入、外观、按键、声音与振动、剪贴板与表情、实体键盘、界面语言、开发者、关于 |
| [15-privacy-backup.md](15-privacy-backup.md) | 隐私与权限、密码框和无痕、备份与恢复 |
| [16-landscape-split.md](16-landscape-split.md) | 横屏、分体键盘、分体时其他面板靠边 |
| [17-hardware-keyboard.md](17-hardware-keyboard.md) | 实体键盘 |
| [18-lifecycle.md](18-lifecycle.md) | 转屏、切换应用、换输入框、引擎重启这些时刻 |
| [fixtures/](fixtures/) | 测试页 `fields.html` 和导入用的词表 |

用户文档（[`docs/`](../docs/README.md)）说应用该怎样，这里的预期就照它写；两者不一致时，先弄清哪边错了，
改对的那边，不要只改测试迁就现状。

## 一条测试的格式

```
### PY-01 [P0] 空格上屏整句首选
- **前提**：B0。
- **操作**：在「普通文本」框依次点 n i h a o，再点空格。
- **预期**：框里是「你好」。
```

- **编号**：`<区域>-<序号>`，区域见各文件开头；编号定下就不改，删掉的测试编号不再复用。
- **级别**：`[P0]` 是每次发版都要过的主要路径（冒烟时只跑 P0）；`[P1]` 是其余的；`[P2]` 是少见的细节（外观、少用的选项），发版时也跟着功能区跑。发正式版时跑全部 P0，
  加上上一版以来改过的功能区（上面文件表的一行）里的全部用例，结果记在 `runs/v<版本>.md`，有失败或阻塞不发版。
- **前提**：从哪个基线开始（见下），加上这条测试自己的准备。准备步骤也是测试的一部分：做不到就记"阻塞"，
  不算通过。
- **操作**：用户做的事，一步一步写到能照着做。「点」是手指点屏幕键盘，「按」是按实体键盘，
  「长按」是按住约 1 秒。
- **预期**：用户看得到的结果。"框里是「X」"指整个输入框的内容正好是 X（不多不少）。

## 基线状态

**B0（默认状态，竖屏）**
1. 设备系统语言为简体中文，竖屏，没有接实体键盘。
2. 装的是要测的那一版文字版（语音的测试用语音版），并且是"重置"后的状态（见下）：默认设置、
   默认的两个输入法（拼音、英文，拼音在前）、没有学到或添加的词、剪贴板历史为空。
3. 幽默输入法已启用并设为当前键盘。
4. 浏览器里打开了测试页（见下），点了「普通文本」框，屏幕键盘已弹出，当前输入法是拼音，键盘上方是工具栏。

**B0-横屏**：B0，再把设备转成横屏（关掉自动旋转时，用系统的旋转按钮或 `adb shell settings put system user_rotation 1`），
重新点一下「普通文本」框。

**B0-设置**：B0 的第 1–3 步，然后打开"幽默输入法"应用，停在设置首页。

前提里写"B0，另外……"的，先到 B0，再做"另外"的部分。

### 重置

把应用恢复成刚装好的样子（不用卸载重装）：

```sh
adb shell pm clear io.github.sraw.youmo.debug   # 测发布包时去掉 .debug，下同
adb shell ime enable io.github.sraw.youmo.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService
adb shell ime set io.github.sraw.youmo.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService
```

`pm clear` 删掉应用的全部数据（设置、词、剪贴板、主题）。之后第一次弹出键盘时引擎要准备数据，
等候选能出来再开始。测"第一次打开"向导的那几条要真正卸载重装，前提里会写。

### 测试页

[`fixtures/fields.html`](fixtures/fields.html) 有测试用的各种输入框，用例里用「」里的名字指它们：
「普通文本」「句子」「聊天」「密码」「网址」「邮箱」「数字」「电话」「搜索」「下一项」「不要提示」「长文本」。
它们的 id 依次是 `text` `sentence` `chat` `password` `url` `email` `number` `tel` `search` `next` `nosuggest` `long`；
地址后加 `#<id>`（例如 `fields.html#chat`）只显示那一个框，放在页面最上面（「聊天」框在最下面，见下）；在已经打开测试页的标签里改 `#<id>` 也马上生效。

在电脑上起一个本地网页服务，转发到手机，用手机的 Chrome 打开：

```sh
python3 -m http.server 8000 --bind 127.0.0.1 -d uat/fixtures &
adb reverse tcp:8000 tcp:8000
adb shell am start -a android.intent.action.VIEW -d http://127.0.0.1:8000/fields.html
```

网页服务只在本机的 127.0.0.1 上（`adb reverse` 用的就是它），局域网里别的机器访问不到；测完在同一个终端里用 `kill %1` 停掉它（`jobs` 能看到它的编号）。

「聊天」框按回车或点"发送"后会把内容记进它的记录并清空，像聊天应用发出一条消息；`fields.html#chat` 时它和"发送"按钮贴在页面底部，键盘弹出后紧贴键盘上沿，像聊天应用的输入栏。
「搜索」框按回车会执行搜索：搜的词写在框下面，框失去焦点，键盘收起。
无痕模式的测试在 Chrome 的无痕标签页里打开同一个地址。

## 设备

| 测试 | 设备 |
|---|---|
| 大部分 | 真机（arm64）或 Android 模拟器（x86_64 包）。发版前至少在一台真机上跑一遍 P0 |
| 实体键盘（17） | 真机接蓝牙或 USB 键盘；或者 Android 模拟器加一个 uinput 键盘（见 [17-hardware-keyboard.md](17-hardware-keyboard.md) 开头）。**不要在 redroid 里造虚拟键盘**：redroid 和宿主机共用内核，造出来的键盘宿主机也会收到 |
| 横屏与分体（16） | 屏幕横过来宽度不少于 600 dp 的手机或平板，模拟器也行 |
| 语音（12） | 真机，有麦克风，装语音版 |
| 振动、按键音 | 真机 |

redroid 里有浏览器"WebView Browser Tester"（`org.chromium.webview_shell`），测试页在它里面打开（`am start -a android.intent.action.VIEW -d <地址> -n org.chromium.webview_shell/.WebViewBrowserActivity`）。

## 自动跑（redroid）

`uat/tools/uat.py` 在 redroid 上照用例操作、读回结果；用例脚本在 `uat/scripts/<文件名>.py`，一个 .md 一个，
**.md 仍是准绳**：脚本照前提/操作/预期做，做不到就记"阻塞"并写原因，不放宽预期。每个 .md 都有脚本；
12（语音）、17（实体键盘）在 redroid 上整份记"跳过"（用例的 `needs="emu:…"`，不先重置；语音版才有的用例在文字版上
也记"跳过"，`needs="voice:…"`），它们在模拟器上跑（见下面"自动跑（模拟器）"）；其余各份里 redroid 做不到的几条也是（分屏、无痕标签页、
密码管理器、振动和按键音、语音版安装、发版升级）。横屏是把屏幕改成 2340×1080（851 dp，`Device.rotate`），
redroid 的显示不会真的转。手写用 `scripts/hw_strokes.py` 里的笔画在书写板上画字。装包时比较设备上的 APK
和 `app/build/outputs` 里最新的那个（同一个版本号会重新构建），不同就重装。

```sh
BUILD_ABI=x86_64 ./gradlew :app:assembleTextDebug   # 没有 debug 包时（运行器只要 x86_64）
uat/tools/uat.py up                         # 第二台 youmo-redroid2（127.0.0.1:5557）；第一台是 youmo-redroid（5556）
uat/tools/uat.py run [--areas 14,06] [--ids PY-01,ST-02] [--devices 5556,5557]
uat/tools/uat.py mark <run> <编号> 通过|失败 [看到了什么]     # 看过拼图后
uat/tools/uat.py down                       # 用完关掉第二台
```

测发布包（正式版发版前）：先 `BUILD_ABI=x86_64 ./gradlew :app:assembleTextRelease`（要签名密钥，没有就只出
`-unsigned.apk`，装不上），再在命令前加 `UAT_BUILD=release`：装 `io.github.sraw.youmo`，设备上的 debug 包先卸掉
（两个都在时系统的输入法列表有两个幽默输入法）。

## 自动跑（模拟器）

实体键盘（17）和语音（12）在模拟器上跑：`uat.py emu-up` 起无界面模拟器（端口 5554，Xvfb；语音要宿主机
PulseAudio 的虚拟音源，语音片段先用 `uat/tools/voice-clips.py` 合成），跑完 `uat.py emu-down`。模拟器单独跑，
不和 redroid 同一次（`run` 会拒绝）。`--flavor` 要和装的包一致：运行器把它传给用例，文字版上语音用例记"跳过"。

```sh
BUILD_ABI=x86_64 ./gradlew :app:assembleVoiceDebug   # 语音版
uat/tools/uat.py emu-up
uat/tools/uat.py run --devices 5554 --areas 17                  # 实体键盘，文字版
uat/tools/uat.py run --devices 5554 --flavor voice --areas 12   # 语音
uat/tools/uat.py emu-down
```

- **分组**：同一基线（和同一页面）的用例是一组，基线只到一次。组里先跑不改状态的，再跑只因上屏而学词的，
  最后跑改设置、改词的；改过状态的用例之后、下一条之前才重置（`pm clear` 再到基线）。要"没学过词"的用例
  （`clean=True`）前面若有上屏过的，也先重置。
- **机器判断**：框里的字、候选、拼音行（光标画成 `|`）、设置页的文字和开关、弹出的字符、键的位置大小、
  键盘亮度，都从界面树（每台设备上常驻的 `Dumper.java`，一次约 0.1 s）和截图像素读回来判断。
- **拼图**：只有预期要靠眼睛看的用例（`visual=True`）截图，裁到相关区域、缩小，每组拼成一张
  `dev/uat-out/<run>/sheet-<组>.png`，每格写编号和预期原文。这些用例记为"待看图"，看过后用 `mark` 改。
- **两台并行**：两台设备各取一组；没组可取的设备把别组末尾要重置的用例分走一半。结果自动写进
  `uat/runs/<日期>-redroid-<版本>.md`。
- 设备上只用 `adb shell input`、uiautomator 接口、am/pm/ime/settings；**不造 uinput 设备**。

### 用代理看拼图

要让代理看拼图时，为了让各代理共用提示缓存：每个代理的**启动提示一字不差**，共同的说明放在一个文件里先读
（[`tools/sheet-review.md`](tools/sheet-review.md)），只有最后一行写各自的拼图路径；同一批用同一种代理。
例如：

```
读 uat/tools/sheet-review.md 并照做。
拼图：dev/uat-out/<run>/sheet-B0-text.png
```

`uat.py` 自己的输出也只给数量和路径（每条一行 `<标记> <编号> <结果>`，最后两行汇总），不打印界面树，
免得调用它的代理每一轮都重读一大段。

## 记录结果

一次测试跑完，在 `uat/runs/` 下记一个文件 `<日期>-<设备>-<版本>.md`：每条一行，编号、结果
（通过 / 失败 / 阻塞 / 跳过）、失败时看到了什么。测试定义文件里不记结果。
