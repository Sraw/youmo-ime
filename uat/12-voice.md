# 12 语音输入（语音版）

区域代号 `VO`。本文件的测试都用**语音版**，在有麦克风的真机上跑，周围安静。前提里的 **B0-语音**：B0（装的是语音版），并且已经给过麦克风权限（没给过的见 VO-01）。

自动跑（`uat/tools/uat.py run --devices 5554 --flavor voice --areas 12`，装 arm64 语音版 **debug** 包）：要说话的用例由 debug 包的「片段麦克风」听：`Device.say` 把 `uat/tools/voice-clips.py` 离线合成的语音片段（Kokoro，每段先用同一个 X-ASR 模型确认听得出；放在 `dev/uat-out/voice-clips/`，不进 git，没有时先跑这个脚本）写进 App、排进队列，App 照常打开 AudioRecord，但改读这些片段，按实时速度送，放完送很轻的底噪直到说完（详见 uat/README.md「实体键盘和语音」）。片段麦克风测不到的留给真机真人：真实麦克风和它的硬件（AudioRecord 出错、时间戳、开麦前的全零，所以 VO-26 在模拟器上通常一按就是「正在听…」）、环境噪声和距离、口音；合成的声音干净、没有口音。搬家前经宿主声卡放音时的丢帧和声卡出错，片段麦克风不会有，那些重试已经删掉：一句只说一次（VO-23、VO-24 照旧每段数 3 次）。

### VO-01 [P0] 第一次用时请求麦克风权限
- **前提**：B0，语音版刚重置，没给过麦克风权限。
- **操作**：长按空格。
- **预期**：短暂打开一个页面请求麦克风权限；点"允许"后回到原来的输入框，再长按空格可以说话。
- **JVM**：`VoiceHoldUatTest.theFirstHoldAsksForTheMicrophone`（没权限时长按空格打开请求页、不开麦；请求页要 RECORD_AUDIO；给了以后再长按就能说（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；模拟器的语音版上也跑一遍：系统的权限对话框和点允许后回到输入框是框架的，JVM 上没有。

### VO-02 [P0] 按住空格说话，松开上屏
- **前提**：B0-语音。
- **操作**：长按空格，出现"正在听…"后说"今天天气很好"，松开。
- **预期**：说的时候屏幕上先显示听到的内容；松开后框里是「今天天气很好」。
- **JVM**：`VoiceHoldUatTest.whatIsSaidShowsThenIsTyped`（部分：说的时候显示听到的、松开才上屏（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上照跑：**语音的设备冒烟**（真麦克风、VAD、X-ASR 端到端）。

### VO-03 [P1] 说话时上滑取消
- **前提**：B0-语音。
- **操作**：长按空格说"你好"，手指上滑再松开。
- **预期**：什么都不上屏，框里为空。
- **JVM**：`VoiceHoldUatTest.slidingUpDropsIt`（上滑过键盘高度的四分之一显示「松开取消」，松开什么都不上屏，也不续录（松开时不调 `finish`）（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-04 [P1] 点工具栏麦克风说话
- **前提**：B0-语音。
- **操作**：点工具栏上的麦克风，看键盘；说"我们走吧"，再点一下麦克风。再点麦克风说"你好"，点盖在键盘上的那一层（不点按钮）。
- **预期**：点了以后键盘上盖一层（工具栏还在上面，麦克风也在），显示声音条和「准备中…」→「正在听…」，没有另开语音面板；第一次框里是「我们走吧」，第二次接着是「你好」；结束后那一层淡出，键盘照常能打。悬浮键盘说着时拖它的把手移动，不结束。
- **JVM**：`VoiceTapUatTest.tappingTheMicrophoneTalksTillItIsTappedAgain`（点麦克风开始、盖层在键盘上、没有面板，再点结束上屏，可以再来一次）、`VoiceTapUatTest.tappingTheMaskStopsIt`、`VoiceTapUatTest.theMicrophoneTappedInTheTailEndsIt`（结束后的续录中再点：`cut()` 一次，不开新的）、`VoiceTapUatTest.gettingReadyShowsTillTheMicrophoneIsOn`、`VoiceTapUatTest.aSilentMicrophoneIsSaid`、`VoiceTapUatTest.floatingTheMaskGoesWithTheKeyboard`（悬浮键盘拖动、缩小后，盖层是键盘自己的子 View，在工具栏下面、盖住按键）、`VoiceTapUatTest.floatingDraggingItsHandleTalksOn`（拖把手不算点到盖层外）、`VoiceTapUatTest.startedFromAShortcutTheMicrophoneStillStopsItOnce`（从按键快捷键开始的，点麦克风只结束一次，不截掉续录）、`VoiceHoldUatTest.floatingTheCardGoesWithTheKeyboard`（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）；设备上看：盖层的位置（悬浮键盘拖动和缩放后、单手模式、横屏），说着时拖悬浮键盘的把手不结束。
- **模拟器**：点麦克风，播 `zouba`，再点麦克风，等盖层收起，框里「我们走吧」；再点麦克风，播 `nihao`，点盖层的提示文字，框里接着是「你好」；结束后还能打字。悬浮键盘、横屏、单手模式不在模拟器上跑。

### VO-05 [P1] 说话时的那一层是毛玻璃，没有 ×
- **前提**：B0-语音；再换一个图片背景的主题、悬浮键盘、横屏各做一遍。
- **操作**：点工具栏上的麦克风；再长按空格（不说话），手指上滑到取消区停一下再滑回。
- **预期**：那一层下面的键是模糊的，只看得出大致的形状和颜色，位置和键盘对得上（悬浮、横屏、图片背景、圆角都一样）；「正在听…」和提示文字清楚，不和键帽上的字叠在一起；点按说话的那一层上没有 ×，只能点麦克风或这一层结束（不想要的结束后自己删）。长按空格的卡片同样是毛玻璃，上滑到取消区变红，滑回恢复。
- **JVM**：`VoiceGlassTest`（盒式模糊的结果、缩小和放大的坐标换算、键盘画进缩小图时不画那一层自己）、`VoiceTapUatTest.theMaskHasNoEnterStoppedTheKeyboardsSendsIt`（那一层上没有按钮）（`:app:testVoiceDebugUnitTest`）；真机上照"操作"截图看。

### VO-06 [P1] 数字按习惯写
- **前提**：B0-语音。
- **操作**：按住空格说"二零零二年"，松开；再按住说"百分之五十七"，松开。
- **预期**：框里是「2002年57%」（或中间带标点）。
- **JVM**：`VoiceHoldUatTest.numbersAreWrittenInDigits`（识别出的「二零零二年」「百分之五十七」经 VoiceText/ChineseNumbers 上屏为「2002年57%」（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。听不听得出是 `~/voice-eval` 的基准。

### VO-07 [P1] 不是数字的不乱改
- **前提**：B0-语音。
- **操作**：按住空格说"这个电影十分有趣"，松开。
- **预期**：框里是「这个电影十分有趣」，"十分"没被写成 10 分。
- **JVM**：`VoiceHoldUatTest.wordsWithNumeralsAreLeftAlone`（「十分有趣」不改（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-08 [P1] 中英夹杂
- **前提**：B0-语音。
- **操作**：按住空格说"我想买一个 iPhone"，松开。
- **预期**：框里有「iPhone」（英文写法）。
- **JVM**：`VoiceHoldUatTest.englishInChineseIsTypedAsWritten`（X-ASR 写出的「我想 买 一个 iPhone」去掉中文旁的空格，英文照写（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。听不听得出英文是 `~/voice-eval` 的基准（ASCEND 中英夹杂集）。

### VO-09 [P1] 只有咳嗽或"嗯"不打字
- **前提**：B0-语音。
- **操作**：按住空格，只咳嗽一声，松开；再按住空格，什么都不说，听 3 秒房间里的底噪，松开。
- **预期**：两次都显示"没有听清"，框里为空（小声也听得出的增益不把底噪抬成字）。
- **JVM**：`VoiceHoldUatTest.aCoughTypesNothing`（识别器把噪声写成「嗯。」这类：显示「没有听清」，不上屏（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；咳嗽这一步设备上不跑。VAD 和模型对咳嗽的反应是声学的，由 `~/voice-eval` 的 noise 集（`noise_app.py`）量：识别成「咳咳咳」「哼哼哼」「Huh,」「Ah」的也不打字（`VoiceTextTest.mouthNoisesTypeNothing`）。
- **模拟器**：只跑第二步：播 `room`（3 s 粉红噪声，约 −60 dBFS；数字静音测不到增益），不上屏，抬手后显示过「没有听清」；有调试日志时 env 峰值要在 −70 到 −50 dBFS 之间。

### VO-10 [P1] 屏蔽的词不出现在语音结果里
- **前提**：B0-语音，在 **词库与短语 → 用户词库 → 屏蔽的词** 里加「天气」。
- **操作**：按住空格说"今天天气很好"，松开。
- **预期**：结果里没有「天气」这个词。
- **JVM**：`VoiceUatTest.aBlockedWordIsNotTypedFromVoice`（屏蔽「天气」：带它成词的候选被跳过，都带时屏蔽后再搜（`lib/ime-core` 的确定链））；模拟器的语音版上也跑一遍，看真的识别流（热词/屏蔽词）改了听出的结果。

### VO-11 [P0] 密码框里不能语音输入
- **前提**：B0-语音，点「密码」框。
- **操作**：长按空格；再按住空格慢慢往左拖。
- **预期**：不进入语音输入（没有"正在听…"）；工具栏上没有可用的麦克风；慢拖时光标照常往前移（空格不被语音占住）。（自动跑查没打出空格、没进入听写，并在慢拖后截一张图；密码框的字被遮住，光标移动在图上看，结果记待看图。）
- **JVM**：`VoiceKeyboardUatTest.aPasswordFieldHasNoVoice`（语音版：普通框长按空格要麦克风、栏上有麦克风；密码框里都没有，慢拖发出光标键（`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-12 [P1] 只上屏到开始说话时的输入框
- **前提**：B0-语音。
- **操作**：打开测试页的全部输入框（地址不带 `#`），在「普通文本」框长按空格说"你好"，不松手……说完前用另一只手点下面的「句子」框，再松开。
- **预期**：「你好」不会出现在「句子」框里。
- **JVM**：`VoiceHoldUatTest.onlyTheFieldItWasSaidForGetsIt`（说完前换到另一个框，松开后两个框都没有「你好」（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-13 [P1] 改掉空格长按行为
- **前提**：B0-设置（语音版），**按键 → 空格键长按行为** 改成"显示输入法选择对话框"。
- **操作**：回测试页，长按空格。
- **预期**：弹出系统输入法选择框，不进入语音。
- **JVM**：`VoiceHoldUatTest.holdingTheSpaceForThePickerDoesNotTalk`（空格长按改成输入法选择：弹出选择框，不开麦（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-14 [P1] 自己的词优先听出
<!-- learning: U-30 D29 -->
- **前提**：B0-设置（语音版），在 **词库与短语 → 用户词库** 里手动加「澜境花园」（拼音 lan jing hua yuan）。
- **操作**：回测试页，按住空格说"我住在澜境花园"，松开。
- **预期**：框里是「我住在澜境花园」（没加这个词时识别成「蓝镜花园」「兰静花园」之类的同音词）。
- **JVM**：`VoiceUatTest.aWordAddedByHandIsListenedForHardest`（手动加的词进热词、用 ADDED_SCORE（`lib/ime-core` 的确定链）；真的听出来是 `~/voice-eval` 的热词基准（hot.py））；模拟器的语音版上也跑一遍，看真的识别流（热词/屏蔽词）改了听出的结果。

### VO-15 [P1] 关于页列出语音模型用到的数据
- **前提**：B0-设置（语音版），**⋮ → 关于**。
- **操作**：点"第三方库许可证"，找 LibriSpeech。
- **预期**：列着 LibriSpeech，许可是 CC BY 4.0（CC-BY-4.0），链接到 openslr.org/12。文字版的这一页没有 LibriSpeech（文字版没有语音模型）。
- **JVM**：`SettingsPagesUatTest.theVoiceModelsDataIsCredited`（语音版列着 LibriSpeech、CC-BY-4.0、点开 openslr.org/12；文字版没有（两个 flavor 的测试任务都跑它））；设备上不跑。

### VO-17 [P1] 加了自己的词，平常的话照样听对
- **前提**：B0-设置（语音版），按 VO-14 加了「澜境花园」。
- **操作**：回测试页，按住空格说"今天天气很好"，松开；删光，再说"你好"。
- **预期**：两次分别是「今天天气很好」「你好」，没有被换成「澜境花园」里的字（加的词只帮听出它自己）。
- **JVM**：`VoiceUatTest.onlyTheWordAddedIsListenedFor`（识别器只拿到「澜境花园」一个热词，没有别的（`lib/ime-core` 的确定链；听不听得出是 `~/voice-eval` 的基准））；设备上不跑。

### VO-16 [P1] "没有听清"还显示着就再按住空格，照常开始听
- **前提**：B0-语音。
- **操作**：按住空格只咳嗽一声，松开，出现"没有听清"；它还显示着（1 秒内）就再按住空格，说"你好"，松开。
- **预期**：第二次按住马上出现"正在听…"，框里是「你好」。
- **JVM**：`VoiceHoldUatTest.holdingAgainWhileDidntCatchThatShowsListens`（「没有听清」还显示着再长按，马上「正在听…」，说的上屏（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-18 [P1] 导入的词不影响语音，手动加的词才听得出
<!-- learning: U-101 C19; 用户 2026-10-22 定：导入的词转正后算学到的，语音只给手动加的词高权重 -->
- **前提**：B0-设置（语音版）。把 `uat/fixtures/voice-import.txt`（一行 `澜境花园 lan'jing'hua'yuan`）`adb push` 到 `/sdcard/Download/`，做一个只有一个词的词表；**导入词库** 选它，并入"用户词库"（成为导入的词）。
- **操作**：回测试页，按住空格说"我住在澜境花园"，松开；删光；到 **用户词库 → 手动添加的词** 点 **+** 加「澜境花园」（拼音 lan jing hua yuan）；回测试页再说一遍。
- **预期**：第一次识别成「蓝镜花园」这类同音词（导入的词不进语音）；手动加了以后，第二次是「我住在澜境花园」。
- **JVM**：`VoiceUatTest.anImportedWordIsNotListenedForTillAddedByHand`（导入的词不是用户词，不进热词；手动加了才进，用高的权重（`lib/ime-core` 的确定链；听不听得出是 `~/voice-eval` 的基准））；设备上不跑。

### VO-19 [P1] 语音打出的字不学，只当前文
<!-- learning: U-30 -->
- **前提**：B0-语音。
- **操作**：先点 **。**，清空，点 w a n g x i a o m i n g 记下候选，清空；按住空格说"汪小明"，松开，说两次（中间点 **。**）；然后点 w a n g x i a o m i n g 看候选；打开 **用户词库**，搜"小明"。
- **预期**：用户词库里没有因语音新增的词；拼音候选的顺序和没说过、只打过一个「。」时一样（说出的话只当前文：句末的「。」本身就会让第 4 个候选变化；如果识别出的正是「汪小明」，也不成你的词）。
- **JVM**：`VoiceHoldUatTest.whatIsSaidIsContextNotLearned`（说出的话由 commitOverEngine 直接进框：引擎只复位，没有选词、加词、学习（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`））；设备上不跑。

### VO-20 [P1] 名字老被认错，用拼音打两次就听得出
<!-- learning: U-30 C50 -->
- **前提**：B0-语音。说"汪小明明天来"，松开，记下识别结果（常是「王小明」）；删光。
- **操作**：按 PY-34 用拼音把「汪小明」分段打两次；删光；再说"汪小明明天来"。
- **预期**：这次识别出「汪小明」。
- **JVM**：`VoiceUatTest.aNameTypedTwiceIsListenedFor`（打过两次的「汪小明」进热词（`lib/ime-core` 的确定链；听不听得出是 `~/voice-eval` 的基准））；设备上不跑。

### VO-21 [P1] 删除、屏蔽的词，语音里也不出
<!-- learning: U-30 U-104 -->
- **前提**：B0-语音，按 VO-20 让语音听出「汪小明」。
- **操作**：打开 **用户词库**，删掉「汪小明」；回测试页说"汪小明明天来"。
- **预期**：不再偏向「汪小明」（多半又是「王小明」）。
- **JVM**：`VoiceUatTest.aWordDeletedOrBlockedIsNotListenedFor`（删了不进热词；屏蔽的进屏蔽表，带它的识别结果被跳过（`lib/ime-core` 的确定链；听不听得出是 `~/voice-eval` 的基准））；设备上不跑。

### VO-22 [P1] 不用我的输入习惯时语音也不偏向你的词，恢复后照旧
<!-- learning: U-93 F2656 -->
- **前提**：B0-语音，按 VO-20 让语音听出「汪小明」。
- **操作**：点 **⋯ → 不用我的输入习惯**；说"汪小明明天来"；删光，点 **⋯ → 恢复我的输入习惯**，再说一遍。
- **预期**：不用时不偏向「汪小明」（多半是「王小明」）；恢复后又是「汪小明」。
- **JVM**：`VoiceUatTest.habitsOffListensForNoneOfTheirs`（不用我的输入习惯时没有用户的热词，恢复后又有（`lib/ime-core` 的确定链；听不听得出是 `~/voice-eval` 的基准））；设备上不跑。

### VO-23 [P1] 说到最后一个字就松开，不丢字
- **前提**：B0-语音。
- **操作**：长按空格说"今天天气很好"，说到"好"时就松开；再用"我们走吧"做一遍。
- **预期**：框里分别是「今天天气很好」「我们走吧」，最后一个字没丢（松开后还录 0.3 秒）。
- **JVM**：`VoiceHoldUatTest.theTailCountsFromTheLiftAndTypesOnceRecorded`（松开时 `finish` 收到这次 ACTION_UP 的时间；马上显示「识别中…」；续录完之前不上屏，之后上屏（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）），加上 `VoiceCaptureEndTest`（从松手的帧起 4800 帧、停在整窗边界、没有时间戳时从已读算、时间戳的限幅、超出缓冲的时间戳不信；松手后 1 s 没有新采样兜底；`lib/ime-core`）。
- **模拟器**：播 `jintian`、`zouba`，用 sendevent 直接写触摸屏按住空格、到时抬起，抬起定在语音结束（片段的 `<clip>.json`）前约 450 ms：有调试日志的版本从 App 的「voice speech started」一行起算；没有这行日志的版本（流式版）从片段麦克风开始放片段时的「voice clip started」一行起算，抬起仍用 sendevent。
  - **判定只看上屏的字**，所以同一个脚本能在没有调试日志的旧版（R15，先红）和新版上照跑：每段数 3 次，3 次都对才通过；≥ 2 次不对就是设计说的"红"（记为失败，结果里写"红"）；只错 1 次也记失败（"不稳定"）。
  - 有调试日志（`voice capture:` 一行）时，从它算实际偏移：`liftFrame − (segment.start + 1024 + (E − S) × 16000)`；不在 [−500, −400] ms 的这次不算，日志里没有语音段的也不算，最多 10 次；从「voice speech started」起算时几次之间差不到 ±10 ms，不再挪 UP 的时间（只有主机计时的回退才按差值的一半挪）。拿不到时间戳（`liftFrame=null`）时用 `stopAt − 4800`（采集线程看到松手时已读的帧数），偏移里多了采集通路的延迟，结果里注明"无时间戳"。没有日志的旧版每次都算，所以不查时机，红不红要看 UP 的时间准不准：旧版上的那次在 `uat/runs/` 里写明"基线，应为红"，跑出通过时这次先红不算数，要查时机后重跑。
  - 这个区间在 `~/voice-eval/tailquiet` 的 `cu2` 上验证过：不续录时两段都丢末字，续录 ≥ 200 ms 两段都对。
- **真机（发版时）**：AudioRecord 的时间戳和开麦前的全零只在真机上验，片段麦克风没有这些（片段模式下松手位置按读到的帧数算，不用 AudioRecord 的时间戳）。这条在发版的真机清单里（uat/README.md「设备」）。

### VO-24 [P1] 小声说也听得出
- **前提**：B0-语音。
- **操作**：手机放桌上约 50 cm，长按空格小声说"我们走吧"，松开。
- **预期**：框里是「我们走吧」，不是"没有听清"。
- **JVM**：`VoiceGainTest`（全部：与 tailquiet 的 `Gain2` 金样本逐样本一致；−55 dBFS 抬到约 −35，最多 +20 dB；正常音量原样不动；突然很响也不削波；咔哒之后 1 s 内回到 +15 dB 以上；开头 3 个有声的窗不算（麦克风起来前的全零不占）；短读按样本数衰减、单样本的读直接到增益；全零进全零出；`lib/ime-core`）。
- **模拟器**：播 `zouba-q44`、`nihao-q44`（衰减 44 dB），结果分别要是「我们走吧」「你好」。判定同 VO-23，只看上屏的字：每段 3 次都对才通过，≥ 2 次不对为红。有调试日志时，env 峰值要在 −65 到 −50 dBFS 之间（片段本身的峰值在 −55 ± 1）、该处增益 ≥ +15 dB，否则这次电平不对，不算，重来，最多 10 次。
- **真机（发版时）**：AudioRecord 的时间戳和开麦前的全零只在真机上验，片段麦克风没有这些（真麦克风的小声和底噪、开麦前的全零对增益的影响）。这条在发版的真机清单里（uat/README.md「设备」）。

### VO-25 [P1] 松开后马上再按
- **前提**：B0-语音。
- **操作**：长按空格说"你好"，松开后马上再按住空格约 1 秒，松开。
- **预期**：框里是「你好」；第二次按下不算（不开始新的一次听写，也不打出空格）。
- **JVM**：`VoiceHoldUatTest.pressingAgainEndsTheTailAndCountsForNothing`（续录中再按空格：`cut()` 正好一次，不再开麦，第一句照常上屏；之后再长按照常「正在听…」并上屏）（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）；点麦克风说话的续录中再点见 VO-04。
- **模拟器**：只做冒烟：第一句照常上屏；从调试日志（`read` 与 `stopAt`）看这次再按落在续录中还是识别中，写进结果。

### VO-26 [P1] 麦克风真正开了才显示「正在听」
- **前提**：B0-语音。
- **操作**：长按空格（不说话），看提示；出现「正在听…」后说"你好"，松开。（真机，手机连着电脑的 adb，Android 12+）先 `adb shell cmd sensor_privacy enable 0 microphone` 关掉麦克风，好让麦克风先开不起来（2 秒后可能显示「麦克风没有声音」，同 VO-27，照常继续）；再长按空格，手指上滑到「松开手指，取消输入」并停住，在电脑上 `adb shell cmd sensor_privacy disable 0 microphone` 打开麦克风，对着手机出点声，等一会儿再滑回空格，松开。再点工具栏的麦克风（不按住）把前一半做一遍。
- **预期**：按下后先是「准备中…」，麦克风收到第一个声音时变成「正在听…」并轻震一下；框里是「你好」。（真机）手指停在取消区时打开麦克风：空格卡片上仍是「松开手指，取消输入」，不震；手指滑回才震一下。点麦克风说话时同样先「准备中…」，收到声音才「正在听…」。
- **JVM**：`VoiceOnsetTest`（全零不算开麦；第一个非零采样所在的窗报一次；只看读到的那些；`lib/ime-core`）、`VoiceHoldUatTest.gettingReadyShowsTillTheMicrophoneIsReallyOn`、`VoiceHoldUatTest.slidingBackBeforeTheMicrophoneIsOnStillShowsGettingReady`、`VoiceTapUatTest.gettingReadyShowsTillTheMicrophoneIsOn`、`VoiceHoldUatTest.theCancelHintOutlastsTheMicrophoneComingOn`（取消区里开麦：提示不变、不震，滑回时补震一次）（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）。
- **模拟器**：片段麦克风一开就送很轻的底噪（真机的麦克风也不会长时间读到全零），所以长按空格多半直接「正在听…」：要求「正在听…」出现时已有调试日志 `voice ready`（先「准备中…」也对）；播"你好"时要是「正在听…」、有 `voice ready: fromStartMs=`（写进结果），框里是「你好」。震动和等第一个声音在真机上看。
- **真机（发版时）**：AudioRecord 的时间戳和开麦前的全零只在真机上验，片段麦克风没有这些（片段麦克风一开就送底噪，「准备中…→正在听…」等第一个声音这一步模拟器上看不到）。这条在发版的真机清单里（uat/README.md「设备」）。

### VO-27 [P2] 麦克风没有声音时说出来
- **前提**：B0-语音；手机连着电脑的 adb（Android 12+）。先 `adb shell cmd sensor_privacy enable 0 microphone` 关掉麦克风。
- **操作**：长按空格不放 3 秒；手指不松，在电脑上 `adb shell cmd sensor_privacy disable 0 microphone` 打开麦克风（从下拉栏或别的应用打开会把这次按住打断），对着手机说"你好"，松开。
- **预期**：约 2 秒后显示「麦克风没有声音」；有声音后变回「正在听…」，框里是「你好」。
- **JVM**：`VoiceOnsetTest.twoSecondsOfZeroesIsASilentMicrophoneToldOnce`（2 秒全零报一次，之后的第一个非零采样仍是开麦；`lib/ime-core`）、`VoiceHoldUatTest.aSilentMicrophoneIsSaidAndListeningComesWithTheFirstSound`、`VoiceTapUatTest.aSilentMicrophoneIsSaid`（`:app:testVoiceDebugUnitTest`）；设备上不跑：模拟器空闲时本来就读到全零，量不出"没声音"和"没开始说"的区别，真机上照"操作"跑一遍。

### VO-28 [P1] 点麦克风说话后发送
- **前提**：B0-语音。
- **操作**：打开测试页的「聊天」框（fields.html#chat，回车发送后清空），点工具栏上的 **麦克风**，说"我们走吧"，说完点那一层（或麦克风）结束，等字上屏、那一层收起，再点键盘上的回车（发送）。
- **预期**：那一层上没有任何按钮（没有回车，也没有 ×）；聊天记录里发出的是「我们走吧」，只发了一条，聊天框清空了。（长按空格的语音卡片不在此列：那里的按下只结束续录，见 VO-25。）
- **JVM**：`VoiceTapUatTest.theMaskHasNoEnterStoppedTheKeyboardsSendsIt`（那一层上没有按钮；结束、上屏、那一层收起后，键盘的回车照常发出）（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）。
- **模拟器**：只做冒烟：聊天框里点麦克风，播 `zouba`，播完点那一层结束，等那一层收起后点键盘的回车；只看聊天记录里「我们走吧」正好一条、聊天框空了。

### VO-29 [P1] 点麦克风说话，定下来的边说边上屏
- **前提**：B0-语音，多行的「普通文本」框，语音整理开着。
- **操作**：点工具栏上的麦克风，连着说几句（中间正常停顿），比如"今天天气很好 我们出去走走 晚上回来吃饭"，说的时候看框里，最后点麦克风结束。
- **预期**：说着的时候，前面已经定下来的句子变成普通文字（没有下划线），只有最后还在变的一段带下划线；结束后整段是说的话，没有重复、没有漏字，句末按输入框加不加句号（只在结束时加）。说着的时候点框里别的位置：带下划线的那段撤掉，接着说的从新位置开始，结束后不重复。在聊天框里说着时应用把框清空（发出去了）：已经说过的不会再上屏一遍。结束时整理失败（退回原文）也只补后面的，不重复。
- **JVM**：`VoiceTapUatTest.whatIsSettledIsTypedAsItIsSaid`（一段话在下一段开始前整段待定，下一段一来前一段连标点上屏，最后只补剩下的、句号只在结束时加）、`VoiceTapUatTest.polishedTheFrozenPartIsTypedAsItIsSaid`（开语音整理：冻结的部分边说边上屏）、`VoiceTapUatTest.polishedThenThePolishFailingAtTheEndOnlyWhatFollowsIsTyped`（已上屏「我到了。」，结束时原文「嗯我到了马上走」，只补「马上走」）、`VoiceTapUatTest.underEnglishWhatIsSaidIsTypedOnce`（英文输入法：结束时不把待定文字原地定成文字再上屏一遍）、`VoiceTapUatTest.theCursorMovedWhileTalkingTheTailComposesWhereItWent`、`VoiceTapUatTest.whatTheAppSentIsNotTypedAgain`（说着时框被清空，结束后框还是空的）、`VoiceTapUatTest.whatTheAppRewroteIsNotTypedAgainTheTalkGoesOn`、`VoiceLiveTest`、`VoiceTalkTest`（ime-core：只算关了的段、整理已冻结的部分、停在标点后；改写过的只补公共前缀以后的；原文按子序列对齐，只补后面的；应用拿走的不再打；整理失败后不再边说边上屏）、`EditingSessionTest.aProvisionalCompositionTheAppTookIsNotTakenAgain`（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）；真机上照"操作"跑一遍（整理冻结的时机看真的模型；微信里发送后框清空）。

### VO-30 [P1] 点麦克风说话时换输入框、收起键盘
- **前提**：B0-语音。
- **操作**：打开测试页的全部输入框（地址不带 `#`），在「普通文本」框点工具栏的麦克风说"你好"，说完马上点下面的「句子」框。再回「普通文本」框点麦克风说"再见 明天见"，说完马上按系统的返回收起键盘。
- **预期**：「你好」上屏到「普通文本」框，「句子」框里没有；「再见，明天见」也完整上屏到「普通文本」框（收起时还没识别完的部分识别完再上屏，不丢）；两次那一层都没了。（换框那一刻还没识别完的最后几个字可能丢。）收起键盘后、还没识别完就点别的框：已经听到的上屏到原来的框。网页里同一个框重新开始输入（浏览器有时会这样）时不结束，接着说；网页里换到另一个框（类型、名字或提示不同）算换框。
- **JVM**：`VoiceTapFieldUatTest.anotherFieldGetsNothingTheFirstGetsWhatWasSaid`、`VoiceTapFieldUatTest.theKeyboardHiddenStopsItAndTypesTheRestOnceRecognized`（收起：待定的撤掉，续录、识别完后剩下的直接上屏，只一次）、`VoiceTapFieldUatTest.theKeyboardHiddenThenAnotherFieldTheFirstKeepsWhatWasHeard`（收起后、识别完前换框：听到的进原来的框，新框没有）、`VoiceTapFieldUatTest.theKeyboardHiddenAfterTheStopTheRestStillComesIn`（结束后识别中收起：不再结束一次，剩下的照常上屏）、`VoiceTapFieldUatTest.theMaskThrownAwayWithTheKeysWhatWasHeardIsTyped`（键盘的 View 被换掉：听到的立刻上屏）、`VoiceTapFieldUatTest.inputRestartedInTheSameFieldTalksOn`（同一个框 restartInput：按包名、fieldId、输入类型、字段名和提示认作同一个框，接着说）、`VoiceTapTest`、`VoiceCommitTest.theSameFieldIsTheSameAppAndViewIdAcrossARestart`、`VoiceCommitTest.aWebViewsInputsAreToldApartByWhatTheySayOfThemselves`、`EditingSessionTest.inputRestartedOverAProvisionalComposition`（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）；真机上照"操作"跑一遍（微信、浏览器里的输入框）。

### VO-31 [P2] 点麦克风说话后一分钟没说话自动结束
- **前提**：B0-语音。
- **操作**：点工具栏上的麦克风，说"你好"，然后不说话，等 1 分钟多一点。
- **预期**：最后一次听到说话后约 60 秒自动结束，「你好」上屏，那一层没了。（停止后 15 秒还没识别完就放弃：把最后听到的上屏，有东西上屏过（边说边上屏的或这次补的）就提示「最后几个字可能没识别完」；什么都没听到的提示「没有听清」。）
- **JVM**：`VoiceTapFieldUatTest.aMinuteWithNothingSaidStopsIt`、`VoiceTapFieldUatTest.givenUpWhatWasHeardIsTypedAfterWhatWent`、`VoiceTapFieldUatTest.givenUpWithNothingTypedSaysNothingWasHeard`、`VoiceTapFieldUatTest.givenUpWithOnlyTheTailHeardItIsTypedAndSaidCutShort`（假识别器在 sherpa-onnx 处把识别结果交给真的 VoiceSession，其后整条链是 App 的；`:app:testVoiceDebugUnitTest`）；设备上不跑。

