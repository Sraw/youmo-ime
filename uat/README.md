# 用户验收测试（UAT）

这里的每一条测试都站在用户的角度：**在给定的情况下，用户做给定的操作，应该看到给定的结果**。
它们在 Android 设备上跑（真机、redroid 或模拟器）；预期能在 JVM 上完整断言的，搬到 Robolectric 上的
整个键盘里跑（见"在 JVM 上断言的用例"），设备上只留看胶水的冒烟。单元测试证明逻辑对，这里证明用户用起来对。

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
| [06-pinyin.md](06-pinyin.md) | 全拼：整句、简拼、纠错、模糊音、中英混输、笔画、联想、长按候选、学词、拆字、表情候选、自带日期时间、滑行；拼音 14 键 |
| [07-shuangpin.md](07-shuangpin.md) | 双拼 |
| [08-english.md](08-english.md) | 英文 |
| [09-handwriting.md](09-handwriting.md) | 手写 |
| [10-tables.md](10-tables.md) | 五笔、二笔、仓颉、自然码、五笔拼音 |
| [11-quick-unicode.md](11-quick-unicode.md) | 快速输入、Unicode 输入 |
| [12-voice.md](12-voice.md) | 语音输入（语音版） |
| [13-words.md](13-words.md) | 词库页、用户词库、屏蔽词、导入导出、导入词库、导入的词、清空学到的、自定义短语 |
| [14-settings.md](14-settings.md) | 设置首页、通用输入、外观、按键、声音与振动、剪贴板与表情、实体键盘、界面语言、开发者、关于 |
| [15-privacy-backup.md](15-privacy-backup.md) | 隐私与权限、密码框和无痕、不用我的输入习惯、清掉最近、备份与恢复（含学到的合并、退回、自动备份） |
| [16-landscape-split.md](16-landscape-split.md) | 横屏、分体键盘、分体时其他面板靠边 |
| [17-hardware-keyboard.md](17-hardware-keyboard.md) | 实体键盘 |
| [18-lifecycle.md](18-lifecycle.md) | 转屏、切换应用、换输入框、引擎重启这些时刻 |
| [19-motion-notices.md](19-motion-notices.md) | 提示条（位置、替换、配色）、始终显示悬浮条、候选字号 |
| [fixtures/](fixtures/) | 测试页 `fields.html` 和导入用的词表 |

设备上跑的大词库用例（WD-14d、14h、TBL-12c；发版时手动跑的 WD-14r、14v、TBL-12d 也要；14i、14s 等搬到 JVM 的用 fixtures 里的 big-words.txt 或小词表，不要它）要雾凇拼音（[rime-frost](https://github.com/gaboolic/rime-frost/releases)）发布的
`frost_dict_for_fcitx5.txt`，约 89 MB，不放进 fixtures：下载后用环境变量 `UAT_FROST=<路径>` 指给脚本。用例里的数
（1914461 个词、176 行读不懂）出自 SHA-256 为 `86473190c505691c7f1ef7b87345a16b555c75fea30527f472a151b6eb1ad41f`
的那一份（1914637 行），脚本先核对哈希，对不上记为 Blocked；换新版时连同用例里的数一起改。

### 量 Java 堆

每次读数前先让应用做一次垃圾回收：以 root 给它发 SIGUSR1（redroid 上 `docker exec <容器> sh -c 'kill -10 $(pidof <包名>)'`，
模拟器上 adbd 是 root，直接 `adb shell 'kill -10 $(pidof <包名>)'`），等 1 秒，再看 `adb shell dumpsys meminfo <包名>` 里 App Summary 的 Java Heap。
不先回收会读到后台工作留下的、还没回收的垃圾（实测 130 MB，一秒后 60 MB）。第一次读数常偏高，不算；比的是后 4 次的中位数。

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

**B0-14键**：B0，然后在 **输入法 → 拼音** 里把 **键盘** 选成「14 键」，回测试页点「普通文本」框。

**B0-18键**：B0，在 **输入法** 里添加"双拼"（方案是默认的自然码），然后在 **输入法 → 双拼** 里把 **键盘** 选成「18 键」，回测试页点「普通文本」框，换到双拼。

**B0-九键双拼**：同 B0-18键，**键盘** 选成「九键」。

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
「普通文本」「句子」「聊天」「密码」「网址」「邮箱」「数字」「电话」「搜索」「下一项」「不要提示」「长文本」，
还有预填好文字、给复制用的「链接」（带跟踪参数的网址）和「短词1」–「短词5」（公园、今天、天气、我们、一起）。
它们的 id 依次是 `text` `sentence` `chat` `password` `url` `email` `number` `tel` `search` `next` `nosuggest` `long`
`link` `clip1`…`clip5`（`#clips` 一起显示那五个短词框）；
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
### 测试宿主

测试页是 WebView 里的网页框，有几样它给不了：回车是"下一项"并真的跳到下一个框（WebView 不理输入法的"下一项"）、
"不要个性化学习"的框（`IME_FLAG_NO_PERSONALIZED_LEARNING`，无痕标签页的框就这样）、标成敏感的剪贴板内容
（`ClipDescription.EXTRA_IS_SENSITIVE`，密码管理器复制密码时这样标）。这些用调试包里的原生输入框
`TypingHostActivity`（`app/src/debug`，不导出，用 root 起）：

```sh
adb shell su 0 am start -n io.github.sraw.youmo.debug/org.fcitx.fcitx5.android.input.TypingHostActivity --ez next true
#   --ez next true：「第一项」（回车是下一项）和下面的「第二项」；--ez noLearning true：不要个性化学习；
#   --es sensitiveClip mima1234：打开时把这段文字标成敏感放上剪贴板
```

（redroid 上 root 是 `docker exec <容器> am start …`；脚本里是 `open_host(d, next=True)` 这样。）真机上也可以照用例里写的，
用 Chrome 的无痕标签页、真的密码管理器。KB-21、PR-07、PR-08、PR-12 原来用它，现在都在 JVM 上断言（`needs="jvm:…"`），
没有脚本再调 `open_host`；宿主留给手工在设备上看这几种框。

## 设备

| 测试 | 设备 |
|---|---|
| 大部分 | 真机（arm64）或 Android 模拟器（x86_64 包）。发版前至少在一台真机上跑一遍 P0 |
| 实体键盘（17） | 真机接蓝牙或 USB 键盘；或者 Android 模拟器加一个 uinput 键盘（见 [17-hardware-keyboard.md](17-hardware-keyboard.md) 开头）。**不要在 redroid 里造虚拟键盘**：redroid 和宿主机共用内核，造出来的键盘宿主机也会收到 |
| 横屏与分体（16） | 屏幕横过来宽度不少于 600 dp 的手机或平板，模拟器也行 |
| 语音（12） | 真机，有麦克风，装语音版 |

redroid 里有浏览器"WebView Browser Tester"（`org.chromium.webview_shell`），测试页在它里面打开（`am start -a android.intent.action.VIEW -d <地址> -n org.chromium.webview_shell/.WebViewBrowserActivity`）。

## 自动跑（redroid）

`uat/tools/uat.py` 在 redroid 上照用例操作、读回结果；用例脚本在 `uat/scripts/<文件名>.py`，一个 .md 一个，
**.md 仍是准绳**：脚本照前提/操作/预期做，做不到就记"阻塞"并写原因，不放宽预期。每条用例都要有脚本：.md 里有、
脚本里没有的编号，跑的时候记 **阻塞「没有脚本」**，不会从结果里消失。合并过的长用例在每一段前用 `d.step("…")` 起名，
失败或阻塞时结果写"「那一段」这一步：……"。

有些用例有自己的地方跑，用 `needs=` 标出来；在 redroid 上它们不跑、不先重置，结果里不算"跳过"，单列在
**「在别处跑」**一节（汇总写"另有在别处跑 N 条，不计入"）：

| `needs=` | 在哪跑 | 例子 |
|---|---|---|
| `emu:…` | 模拟器（见下面"自动跑（模拟器）"） | 17 实体键盘的 HK 用例 |
| `voice:…`（或 `emu,voice:…`） | 语音版 | 12 语音 |
| `release:…` | 发版时手动（要上一版正式包或两个版本的包） | PR-13、PR-14、PR-30、PR-35、PR-36、PR-37、PR-38、TBL-12d、TBL-33、TBL-43、WD-14r、WD-14v |
| `manual:…` | 真机或手工 | HK-03a、HK-03b、HK-30、HK-33、MN-13、SET-11 |
| `jvm:<测试>` | JVM 上的测试（`:app:testTextDebugUnitTest` 的 Robolectric，`:lib:ime-eval:uatTest` 的引擎回放，语音的 `:app:testVoiceDebugUnitTest`，`:lib:ime-core:test`），`uat.py jvm` 记录 | T9-01、PAN-09、ST-01、MN-20 |

`skip:…`／`block:…` 只留给别的原因，记"跳过"／"阻塞"。

现在共 532 条（P0 102、P1 414、P2 16）：redroid 上跑 145 条（其中 31 条在 JVM 上也断言，设备上留作冒烟或照跑），在别处跑 387 条（JVM 342、发版时手动 12、真机或手工 6、语音版 10、模拟器 17）。

横屏是把屏幕改成 2340×1080（851 dp，`Device.rotate`，等到界面按新尺寸排好），redroid 的显示不会真的转。手写用 `scripts/hw_strokes.py`
里的笔画在书写板上画字。装包时比较设备上的 APK 和 `app/build/outputs` 里最新的那个（同一个版本号会重新构建），不同就重装。

每次点、划之后只停很短（`PAUSE`：点 0.15 s、打字 0.25 s……），原来停顿（`OLD`）剩下的部分记在账上，
下一次读界面前补齐：打完字马上读框里的字，看到的和原来一样（机器忙时网页框的字会晚到），连着点、中间不读界面的就不用等。
读界面时 Dumper 还会等界面静下来（150 ms 没有变化）。进设置页等页面换了并且两次读到的一样、选列表项等对话框出来再关上、
转屏等尺寸变了、测试页的框和键盘两次读到的位置一样（窗口滑进来的半路上读到的位置不能点）、另存的文件大小不再变，
都是等到条件满足就走，不是固定秒数；脚本里写明的 `after=` 照写的停。
键盘等不到时，先看当前输入法还是不是我们的（`pm clear` 后系统有时晚一点换成 LatinIME），是就再点一下框，
还等不到就报阻塞，并写明当前输入法、进程在不在、前台是哪个界面。
怀疑失败是时机问题时，加 `UAT_SLOW=1` 用原来的长停顿再跑一次对比。
要在这份用例下跑另一个检出构建的包（红色基线，如 VO-23 在 R15 上先红），加 `UAT_APK_REPO=<那个检出的路径>`：装包时取它的 `app/build/outputs` 里最新的 APK，用例和脚本仍用这一份。
测试页的本地网页服务按第一台设备取端口（5556 → 8765，5557 → 8766，模拟器 8763）；端口被占时运行器直接退出，
停掉占用的服务或用 `UAT_HTTP_PORT=<端口>` 换一个。

```sh
BUILD_ABI=x86_64 ./gradlew :app:assembleTextDebug   # 没有 debug 包时（运行器只要 x86_64）
uat/tools/uat.py up                         # 第二台 youmo-redroid2（127.0.0.1:5557）；第一台是 youmo-redroid（5556）
uat/tools/uat.py run [--areas 14,06] [--ids PY-01,ST-02] [--devices 5556,5557]
uat/tools/uat.py mark <run> <编号> 通过|失败 [看到了什么]     # 看过拼图后
uat/tools/uat.py down                       # 用完关掉第二台
```

两台 redroid 一起跑时，`run` 先用 `docker update --cpuset-cpus` 把宿主机的核按 `--devices` 的顺序平分给它们
（12 核：0-5、6-11），这样一台上的重用例不会拖慢另一台上掐时间的用例（2026-10-23 实测：易抖的几区从 30/45 通过
升到 39/45，只等界面空闲几乎没用）。标了 `heavy=True` 的用例照旧排在最后、一次只跑一条，别的设备都闲着。
要自己分就写 `UAT_CPUSET=0-3/4-11`（每台一段，用 `/` 隔开），不想限就写 `UAT_CPUSET=off`；`up` 新建容器时
`UAT_CPUSET=0-5` 只给这一台定核。只在一台上跑时，`run` 把所有核还给它（`UAT_CPUSET=off` 则不动核）。

测发布包（正式版发版前）：先 `BUILD_ABI=x86_64 ./gradlew :app:assembleTextRelease`（要签名密钥，没有就只出
`-unsigned.apk`，装不上），再在命令前加 `UAT_BUILD=release`：装 `io.github.sraw.youmo`，设备上的 debug 包先卸掉
（两个都在时系统的输入法列表有两个幽默输入法）。脚本若调 `open_host`（现在没有用例这样做），它在那一条里临时装上
debug 包（要先有 x86_64 debug 包），只当宿主应用、把它自己的输入法停用，这条结束就卸掉，所以别的用例（SET-01 的向导）
仍只看到一个幽默输入法。正式包里不认 debug 包的测试标记，宿主框对它就是别的应用的框。

## 在 JVM 上断言的用例

有些用例的 **预期** 在 JVM 上就能完整断言：`app/src/test` 里的 `KeyboardHarness` 在 Robolectric 里造出整个键盘
（真的输入法服务和 `InputView`，引擎换成 `FakeFcitxAPI`，候选等用假事件推进来），设置页用 `MainActivity` 加同一个假引擎；
样子用 Roborazzi 截图，和 `app/src/test/screenshots/` 里提交的金图比。测试方法上标 `@Uat("<编号>")`，用例 .md 里多一行
**JVM** 写测试名；整条搬走的，脚本里是 `needs="jvm:…"`，redroid 上不跑。还在设备上留一条冒烟的（看引擎和界面之间的胶水），
.md 里写明。

```sh
./gradlew :app:testTextDebugUnitTest :app:testVoiceDebugUnitTest :lib:ime-core:test :lib:ime-eval:uatTest
                                                 # 四个都要跑：jvm 读这四处的结果；截图默认就比对（gradle.properties 的 roborazzi.test.verify）
./gradlew :app:testTextDebugUnitTest --tests '*UatTest' -Proborazzi.test.record=true -Proborazzi.test.verify=false   # 改了样子后重录
uat/tools/uat.py jvm                             # 读 JUnit 结果，按编号写 runs/<日期>-jvm-<版本>.md
```

`jvm` 读的是结果目录里现有的 XML，不看它是哪次构建留下的：少跑一个任务，那些用例就记成上一次（可能是旧提交）的结果，
不会报"没有结果"。所以在 `jvm` 前把四个任务一起跑（或先删掉 `build/test-results`）。

发版的"全部 P0 + 改过的功能区"照旧逐条对号：设备的 run 文件和 `jvm` 的 run 文件两份都要绿。

## 自动跑（模拟器）

实体键盘（17）和语音（12）在模拟器上跑：`uat.py emu-up` 起无界面模拟器（端口 5554，Xvfb；语音要宿主机
PulseAudio 的虚拟音源，语音片段先用 `uat/tools/voice-clips.py` 合成），跑完 `uat.py emu-down`。模拟器单独跑，
不和 redroid 同一次（`run` 会拒绝）。`--flavor` 要和装的包一致：运行器把它传给用例，文字版上语音用例单列为"在别处跑"。

```sh
BUILD_ABI=x86_64 ./gradlew :app:assembleVoiceDebug   # 语音版
uat/tools/uat.py emu-up
uat/tools/uat.py run --devices 5554 --areas 17                  # 实体键盘，文字版
uat/tools/uat.py run --devices 5554 --flavor voice --areas 12   # 语音
uat/tools/uat.py emu-down
```

- **分组**：同一基线（和同一页面）的用例是一组，基线只到一次。组里先跑不改状态的，再跑只因上屏而学词的，
  最后跑改设置、改词的；改过状态的用例之后、下一条之前才重置（`pm clear` 再到基线）。要"没学过词"的用例
  （`clean=True`）前面若有上屏过的，也先重置。不改状态的用例之间只做软重置（清空框、回到拼音、收起工具栏）。
  （2026-10-22 量过用快照代替 `pm clear`：杀进程再解包要 9–18 s 且越杀越慢（系统对反复崩溃的服务推迟重启），
  `pm clear` 后解包 7–18 s、有一次键盘没起来；`pm clear` 本身 B0 约 8 s，没有稳定的收益，所以不用快照。）
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
（通过 / 失败 / 阻塞 / 跳过）、失败时看到了什么；在别处跑的单列在后面。测试定义文件里不记结果。
