# 幽默输入法（youmo-ime）Ensemble Code Review 合并报告（两轮）

> 审查对象：https://github.com/Sraw/youmo-ime ，`main` 分支。第一轮把 `07d2778`（2026-10-06）的整个仓库当作一份完整的 diff 审查；第二轮审查之后的更新 `07d2778..5881d31`（2026-10-07，2 个 commit）。本报告合并两轮，并给出每条问题在当前 HEAD `5881d31` 上的状态。
> 本地 clone（已快进到 `5881d31`）。生成时间：2026-10-07（PT）。

## 0. 要点

- **所有条目都已对照代码验证。** 两轮合计 747 条（含 PRE-EXISTING），确认存在 722 条，确认已修复 3 条，否定 17 条（已撤回），无法验证 5 条，没有未检查的条目。其中 474 条是这次全面验证补上的（26 个 verifier，在 `5881d31` 上核对）：确认 460，否定 10，无法验证 4。
- **当前问题清单（5881d31，已去掉已修复和已撤回的条目）**：MUST FIX 7 条（按用户决定把 F2 降为文档问题后为 6 条），SHOULD FIX 267 条，NIT 440 条；另有 12 条 PRE-EXISTING，全部确认仍存在。清单里有 5 条无法验证（F641 NIT、F648 NIT、F664 NIT、F670 NIT、F694 NIT），它们依赖仓库外的 AGP、Android 框架或 CMake 的行为。
- **全面验证否定、已撤回的 10 条**：F33 [SHOULD FIX]「许可页的数据来源缺少 Common Crawl 和句子模型」；F213 [SHOULD FIX]「WordPack.validName 没有测试」；F264 [SHOULD FIX]「`toneless` 在 NFD 之后才替换 ü，ü 被转成 u」；F534 [NIT]「batches.py 的 min() 处理 NaN 与 minOf 不一致」；F540 [NIT]「CommonCrawl 的 GZIPInputStream 从不关闭」；F653 [NIT]「BaseInputView 承载了子类并不共用的状态」；F668 [NIT]「手写 SentencePiece 读取器的输出没有任何校验」；F698 [NIT]「用户词以 modified UTF-8 跨越 sherpa-onnx JNI」；F705 [NIT]「onFinishInput 的 KDoc 承诺了实际不会发生的回调」；F716 [NIT]「文件/content VIEW 过滤器带有 BROWSABLE 类别」。理由见第 6.1 节。
- **部分论断被否定、条目保留的 8 条**：F25、F38、F166、F325、F339、F371、F386、F424。其中 F38、F166 的运行时后果被否定，详见各条目下的「验证说明」和「子论断」。
- **已修复 3 条**（第二轮在 5881d31 上确认）：F4「VoiceText 的 \b 缩写合并只在 JDK 19+ 生效」；F161「Engines 层面没有九键（T9）输入法测试」；F375「EnginesTest 有未使用的导入」。
- **批次 1 已修复并推送**（三轮修复，构建和测试全部通过；2026-10-08 以 8 个提交 `9dbbd3ac..a39dc41d` 推送到 GitHub `main`，署名为仓库原有的 noreply 身份）：F1、F2、F3、F5、F6、F7、F8、F251、F730 已修复，修复审查中报出的 F751、F748、F749、F752、F765、F750、F767、F769、F772、F780、F773 也已修复；与九键模型对相关的 14 条第二轮条目随 F730 不再适用。三轮修复审查新增 F748–F783，剩下的都是 NIT 或 PRE-EXISTING。见第 8 节和 `ensemble-review-youmo-ime-fix{1,2,3}-report.md`。
- **用户决定**：F1 关闭整个云备份；F2 降为文档问题，只改 README 的表述（作者质疑后的复核结论写在 F2 条目下）；F730 去掉九键单独的一对句子模型，与全拼、双拼共用一套，与这对模型直接相关的第二轮条目（F729、F731–F734、F736–F740、F742、F743、F746、F747）大概率随之不再适用；F162 拆分 `Engines`。见第 8 节。
- **两轮审查**：第一轮整仓 @07d2778，182 个 stage，合并后 716 条非 PRE-EXISTING 条目；第二轮更新 07d2778..5881d31，7 个 stage，全部「建议修改后批准」，19 条，其中 F735 与第一轮 F55 是同一问题。

## 1. 审查说明

### 1.1 两轮审查

| 轮次 | 范围 | 规模 | 阵容 | 结论 |
|---|---|---|---|---|
| 第一轮 | 整个仓库，空树 → `07d2778`（2026-10-06） | 代码 diff 103,905 行，52 个 shard | 182 个 stage：每个 shard 2×CORRECTNESS + 1×GENERIC；PERFORMANCE、TESTING、STYLE 按 9 个模块组各 1 个 | 建议修改后批准 154、批准 11、要求修改 17 |
| 第二轮 | 更新 `07d2778..5881d31`（2 个 commit，2026-10-07） | 6 个文件，+219/−98，diff 482 行 | 标准 7 个 stage：2×CORRECTNESS、2×GENERIC、PERFORMANCE、TESTING、STYLE | 建议修改后批准 7、批准 0、要求修改 0 |

所有 stage 都用 `reviewer` agent，模型 claude-opus-5.5，两轮都额外关注 Cross-cutting Checklist 的 1（部署/发布）、2（可观测性）、4（安全/供应链）、5（数据/隐私）。

### 1.2 第二轮审查的更新

- `af16cd95` Voice: a spelled abbreviation after Chinese is joined on the phone too：把 VoiceText 里 SPELLED 正则的 `\b` 换成显式的 ASCII 前后断言，修正 Android（ICU）和 Java 17 上「订了 C E P」不合并的问题。这正是第一轮的 F4。
- `5881d31e` Nine keys weigh their readings with sentence models of their own：九键（T9）改用自己的一对句子模型 `engine/sentence-model-t9*.safetensors`（release `sentence-models-t9-20261007`），构建里没有时回退到通用模型；全拼和双拼不读它们。句子模型相关测试从 EnginesTest 移到新的 EnginesModelTest。
- 改动的文件：`app/licenses/libraries/sentence-models.json`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt`、`lib/ime-core/.../engine/host/Engines.kt`、`lib/ime-core/.../input/voice/VoiceText.kt`、`lib/ime-core/src/test/.../engine/host/EnginesModelTest.kt`（新增）、`lib/ime-core/src/test/.../engine/host/EnginesTest.kt`。

### 1.3 第一轮的规模与偏差

- 整仓 diff 705,665 行，其中 602,360 行是数据、vendored、生成文件和二进制，没有审：词典数据 446,357 行，vendored sherpa-onnx v1.13.8 共 135,044 行，eval 数据 19,617 行，以及许可证、`gradlew`、Room schema、`flake.lock`、37 个二进制文件。youmo 对 sherpa-onnx 14 个文件的补丁（S52）审了。
- 与 SOP 的偏差：PERFORMANCE、TESTING、STYLE 按模块组跑，而不是各对整个 diff 跑一次；合并由 11 个 aggregator 加 1 个跨组去重 agent 完成，共识数、级别和排序由脚本计算；验证用了 12 个 verifier，SOP 要求只用 1 个；丢弃了 10 条置信度 ≤5 的 NIT。
- 无法读取：`dev/*.md` 设计文档（代码引用了，但仓库没有跟踪），以及 commit 里的 `claude.ai` 会话链接。

### 1.4 第二轮的做法

- 按 SOP 的 `since=07d2778` 跑：diff 482 行，不分 shard，7 个 stage 全部完成。由 1 个 aggregator 合并，并按「同一代码结构、同一问题」与第一轮的条目逐条比对。第二轮的条目编号接着第一轮，从 F729 开始，第一轮的编号不变。
- 第一轮中有 61 条引用了这次改动过的文件，除去 1 条已撤回、1 条被第二轮再次指出（F55）外，其余 59 条作为 `P<k>` 交给 verifier 在 `5881d31` 上逐条复核：55 条仍存在，3 条已修复，1 条无法验证。
- 第一轮的其他条目所在文件这次没有改动，代码与 `07d2778` 完全相同，所以状态沿用第一轮，没有重新验证。
- 第二轮的验证：4 个 verifier。第二轮自己的条目中，MUST FIX、至少一半 reviewer 指出的条目、可能影响运行时的 SHOULD FIX，以及与第一轮重合的条目都验证了。SOP 要求 1 个 verifier。丢弃了 1 条置信度 ≤5 的 NIT。
- 第二轮共识数的 M = 7（全部 stage 都读了整份 diff）。第一轮多数条目的 M = 6，两轮的 N/M 不直接可比。

### 1.5 如何读共识数和状态

- 「共识 N/M」：N 是指出该问题的 stage 数，M 是读过该问题所在代码的 stage 数。只有 CORRECTNESS 和 GENERIC 是复制的 stage；PERFORMANCE、TESTING、STYLE 独有的发现天然是 1/M，不代表证据弱。
- 「验证」是对照代码核对的结果：第一、二轮已验证的条目沿用当时的结果，其余条目在全面验证中于 `5881d31` 上核对。「当前状态」是它在 `5881d31` 上的状态。

### 1.6 没有被完整读过的文件

- 第一轮：S1–S52 中每个文本文件都至少被一个 stage 完整读过，唯一缺口是二进制 `app/src/main/play/listings/en-US/graphics/icon/icon.png`。未检出的 4 个 submodule（fcitx5、fcitx5-chinese-addons、fcitx5-lua、prebuilt）、AGP/Gradle 和 detekt 内部行为、第三方 AAR manifest、ART 的 `GetStringUTFChars` 没有 stage 读过。
- 第二轮：依据 `Coverage:` 行。全部 7 个阶段都阅读了完整的 diff，因此每个变更块都被阅读过。以下文件没有被任何阶段完整阅读：  - `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt`：CORRECTNESS-1 阅读了第 40-300 行，GENERIC-1 阅读了第 40-300 行和第 365-395 行（两者都列在“已完整阅读”之下，但给出的是行范围）。CORRECTNESS-2、GENERIC-2、PERFORMANCE、STYLE 和 TESTING 只是略读了该文件。 - `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt`：GENERIC-1 阅读了第 1-200 行、第 470-560 行和第 800-925 行；PERFORMANCE 阅读了 ModelFile、模型字段、设置的 setter、`session()`、`reranker`/`refiner`/`LateRefiner` 以及 `close` 这些部分。其他五个阶段只是略读，CORRECTNESS-1 表示它没有阅读 Engines.kt 的其余部分。 - `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt`：TESTING 阅读了 diff 以及 load/pause 辅助函数。CORRECTNESS-1、CORRECTNESS-2、GENERIC-1、GENERIC-2 和 STYLE 略读了部分内容；PERFORMANCE 没有提到该文件。  至少被一个阶段完整阅读的文件：`app/licenses/libraries/sentence-models.json`（GENERIC-2、STYLE）、`VoiceText.kt`（全部 7 个阶段）、`EnginesModelTest.kt`（新

### 1.7 全面验证

- 按用户要求，在修复之前把所有还没验证过的条目都对照代码核对了一遍：第一轮 461 条（含 12 条 PRE-EXISTING），第二轮 13 条，共 474 条、792 个论断（条目及其子论断）。
- 核对在 `5881d31` 上进行，分给 26 个 `reviewer` verifier（claude-opus-5.5），每个 12–20 条，规则与 Step 4b 相同：每个子论断单独标记，带运行时影响的条目要求运行时后果也成立。SOP 要求 1 个 verifier。
- 这次 4 个 git submodule 已检出，verifier 可以读 fcitx5 等子模块的源码；仍然读不到的是仓库外的 AGP、AndroidX、Android 框架、ART 和 CMake 实现，依赖它们的论断标为无法验证。
- PRE-EXISTING 条目只判断缺陷在 5881d31 上是否存在，并用 `git blame` 说明代码来自上游 fcitx5-android 还是 youmo 自己的提交。

## 2. 本次更新（第二轮）的结果

### 2.1 第二轮的新问题

| id | 级别 | 共识 | 验证 | 与第一轮 | 位置 | 标题 |
|---|---|---|---|---|---|---|
| F729 | SHOULD FIX | 6/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:84 +4 处` | T9 模型对的关闭释放与重新读取没有测试 |
| F730 | SHOULD FIX | 4/7 | ✅ 已确认 | 用户：去掉 T9 模型对 · 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:164 +7 处` | 九键与全拼/双拼混用时堆上常驻两套句子模型 |
| F731 | SHOULD FIX | 4/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:111 +3 处` | T9 模型对让每个 APK 增大约 30 MB，代价未测量 |
| F732 | SHOULD FIX | 3/7 | ✅ 已确认 | 新增 | `lib/ime-eval/release-report.sh:46 +2 处` | 发布门禁不评测九键及其自有模型对 |
| F733 | NIT | 5/7 | ✅ 已确认 | 新增 | `app/licenses/libraries/sentence-models.json:3 +6 处` | 许可条目与 README 只写旧的句子模型发布 |
| F734 | NIT | 4/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:139 +2 处` | T9 模型文件存在但不可读时不回退到通用模型 |
| F735 | NIT | 3/7 | ✅ 已确认 | 同 F55 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:138 +3 处` | T9 回退到通用模型时没有任何日志或信号 |
| F736 | NIT | 2/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110` | EngineDataPlugin 中 T9 模型注释语句不通 |
| F737 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:137` | 缺文件分支的注释已过时 |
| F738 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:831 +1 处` | reranker 的 KDoc 仍写「over the one model」 |
| F739 | NIT | 1/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:105 +2 处` | MODELS 用嵌套 Pair 而非具名类型 |
| F740 | NIT | 1/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:102 +2 处` | RELEASES 前缀与 LM_URL、WORDS_URL 写法不一致 |
| F741 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20` | SPELLED 新正则不再把下划线和带重音字母当词字符 |
| F742 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103` | 存在 T9 文件时未检查双拼不读 T9 模型对 |
| F743 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:164 +2 处` | 成对的句子模型以四个零散字段传递（Data Clump） |
| F744 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20` | VoiceText 中字符类 [A-Za-z0-9] 写了两遍 |
| F745 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:50 +2 处` | SPELLED 修复只靠 JDK 17 的 CI 守护，ASCII 边界无测试 |
| F746 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:155 +1 处` | T9 模型文件损坏时的行为没有测试 |
| F747 | NIT | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:540` | 首次切到九键时在 fcitx 线程上加载 T9 小模型 |

### 2.2 已修复的第一轮问题

- **F4** [MUST FIX] VoiceText 的 \b 缩写合并只在 JDK 19+ 生效：已由 af16cd95 修复。lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20 现在为 `(?<![A-Za-z0-9])[A-Z](?: [A-Z](?![A-Za-z0-9]))+`，它使用显式的 ASCII 字符类，不使用 \b。经过 SPACE_BY_CJK 处理后，"订了C E P的票" 在任何 JDK 和 ICU 上都会匹配到 C E P，因此 VoiceHoldTest.kt:53 所期望的合并会发生。
- **F161** [SHOULD FIX] Engines 层面没有九键（T9）输入法测试：已由 5881d31 修复。lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103-112 和 115-124 构建了 Engines.T9 会话，输入 "64426" 并在其上执行精修。Engines.kt:537-540 处的 T9 接入也由同一个提交改动。
- **F375** [NIT] EnginesTest 有未使用的导入：该缺陷已消失，由 5881d31e 修复。该提交从 EnginesTest.kt 中删除了 `assertNotEquals`、`CompletableFuture` 和 `Future` 这三个导入（基线 :22、:32、:33），这三个名称都已不再出现在 EnginesTest.kt 中。引用的另一处位置 PinyinSessionRefineTest.kt:7（一个未使用的 `NO_WORD` 导入，本次改动未涉及）不属于本论断的范围。

### 2.3 复核后仍存在的第一轮问题（所在文件这次有改动）

| id | 级别 | 位置（第一轮） | 标题 | 复核证据 |
|---|---|---|---|---|
| F1 | MUST FIX | `app/src/main/AndroidManifest.xml:28` | Auto Backup 将学习词和剪贴板历史上传云端 | `app/src/main/AndroidManifest.xml:28-30`、`app/src/main/res/xml/data_extraction_rules.xml:3-16`、`EngineBridge.kt:69` |
| F3 | MUST FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:348` | 一次删除多个已学词时全部未被遗忘 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:356`、`UserModel.kt:141-148`、`UserModel.kt:294` |
| F34 | SHOULD FIX | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:306` | 离开页面会取消自定义短语保存后的引擎重载 | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:306-317`、`Engines.kt:862`、`EngineBridge.kt:146` |
| F35 | SHOULD FIX | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:692` | readings.tsv 读音笔误会静默删除发布词库中的词 | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:54`、`PinyinDictReader.kt:69-71`、`Main.kt:692-697` |
| F50 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:80` | 模型权重从映射的资源复制到Java堆 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:70`、`Engines.kt:846` |
| F52 | SHOULD FIX | `app/src/main/cpp/matrix-kernel.cpp:66` | 原生 int8 矩阵内核没有与 MatrixKernel.JVM 对比的测试 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:74-81`、`matrix-kernel.cpp:41-51` |
| F61 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:26` | Zstd 128 MB 输出上限与倍增扩容可耗尽堆内存 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:26`、`Zstd.kt:75`、`Zstd.kt:155-161` |
| F75 | SHOULD FIX | `lib/ime-dict-tool/engine-data.sh:84` | engine-data.sh 产出的数据与 app 构建不一致 | `lib/ime-dict-tool/engine-data.sh:83-84`、`EngineDataPlugin.kt:181-184` |
| F79 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:170` | EnginesTest 的 return@repeat 只跳过一次迭代未结束循环 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:158`、`EnginesTest.kt:166` |
| F82 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:284` | UserModel 在解码器每条弧上对 Int 装箱 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:232`、`UserModel.kt:284-285`、`PinyinDecoder.kt:160` |
| F112 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:56` | LayerPrior.of 在解码热循环中做装箱 HashMap 查找 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:56`、`LayerPrior.kt:73`、`UserModel.kt:235` |
| F120 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:99` | SharedLexiconTest 下次启动“告知”检查无法失败 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100`、`Engines.kt:628-629` |
| F122 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:343` | aTextIsForgottenHoweverItWasRead 只覆盖一种读音 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:345-347` |
| F129 | SHOULD FIX | `.github/workflows/pull_request.yml:61` | CI 每次重新下载约 400 MB 并重编引擎数据 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:122`、`VoiceDataPlugin.kt:64`、`.github/workflows/pull_request.yml:61` |
| F133 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:96` | CorruptionTest 遍历漏掉生产中的查找路径 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:48-72`、`CorruptionTest.kt:98` |
| F142 | SHOULD FIX | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:83` | 词包首次导入接受的名称，再次导入时被 importPack 拒绝 | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:84`、`PinyinDictManager.kt:52-54`、`PinyinDictionaryFragment.kt:232-233` |
| F158 | SHOULD FIX | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164` | 开启配置缓存后工具指纹失效，引擎数据不再重建 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:171`、`ToolFingerprint.kt:21` |
| F160 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:653` | forgetInTables 漏掉启动后未加载的用户导入码表 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:663`、`Engines.kt:867`、`PinyinSession.kt:69-93` |
| F162 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:68` | Engines 单个约 950 行的类承担过多职责 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:68` |
| F165 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:83` | LibimeImport.readings 遍历整棵字典树，交互路径上也如此 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:79`、`TableUser.kt:77`、`Engines.kt:617` |
| F195 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:518` | 三个拼音会话重复传入同一组命名参数（Data Clump） | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:529-546`、`PinyinSession.kt:78` |
| F206 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:346` | 删除曾被选用的自造词后仍可输入并变为 LEARNED | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:354`、`UserLog.kt:87`、`UserModel.kt:238` |
| F207 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:232` | 基础词包测试无法因其名称所述行为而失败 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:220-232`、`Engines.kt:461` |
| F212 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:15` | 九键分词测试从未按生产配置构建分词器 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:15`、`Engines.kt:538`、`T9Segmenter.kt:48-62` |
| F228 | SHOULD FIX | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:418` | 五个码表串行编译，每次在新 JVM 中重复同样工作 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:427-438`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:744-746` |
| F231 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:522` | 拼音长按“遗忘”不会传到码表 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:529-546`、`PinyinSession.kt:342-348` |
| F234 | SHOULD FIX | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:261` | “the prior learned is kept”测试从未检验先验被保留 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:235-262` |
| F238 | SHOULD FIX | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:54-55` | 用户层门禁未带 reranker、KeyHabits 与 PACK | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:54-55`、`release-report.sh:68` |
| F255 | SHOULD FIX | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:593` | 列表词变化后每张码表在下次按键时重新编码全部列表词 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:595-612`、`SharedWords.kt:46-55` |
| F269 | SHOULD FIX | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:188` | 每次进程启动后，第一个按键在 fcitx 线程上构建整个引擎 | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:66-71`、`Engines.kt:505-506`、`Safetensors.kt:79-80` |
| F300 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:930` | 删除导入码表时遗留 .told 标记 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:943`、`TableBasedInputMethod.kt:61` |
| F304 | NIT | `config/detekt/detekt.yml:48` | 注释引用仓库中不存在的 dev/ISSUES.md 等文件 | `config/detekt/detekt.yml:48`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110` |
| F320 | NIT | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:48` | WordListsTest 测试名称说应用到模型，但从未调用 applyTo | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:48` |
| F343 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:128` | WordLayers 在每次查找时而非加载时校验层值 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:128` |
| F350 | NIT | `app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:159` | FakeFcitxAPI 的 setEnabledIme 不保留传入顺序 | `app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:159`、`native-lib.cpp:173-181` |
| F406 | NIT | `README.md:51` | README「数据与许可」漏列 rime-stroke 等组件 | `README.md:50-56`、`EngineDataPlugin.kt:97-100` |
| F409 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:751` | 加载失败的导入码表每次按键都抛异常 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:761`、`app/src/main/cpp/native-lib.cpp:557-559` |
| F411 | NIT | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:70` | 测试中基于用户目录的 Engines 实例未关闭 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:70`、`EnginesTest.kt:538`、`Engines.kt:202-210` |
| F414 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:59` | VoiceRerank.notHan 的汉字定义与其他类不一致 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:59`、`VoiceText.kt:14`、`VoiceHotwords.kt:33` |
| F426 | NIT | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:194` | “不学习”测试只检查内存状态 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:182-191` |
| F437 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:113` | EngineDataPlugin.apply() 长达 100 行，下载配置重复 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:120-221`、`config/detekt/detekt.yml:27`、`VoiceDataPlugin.kt:75` |
| F438 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:221` | 共享的任务类嵌套在单个插件内 | `EngineDataPlugin.kt:229`、`VoiceDataPlugin.kt:68`、`NativeBuildTasks.kt:50` |
| F439 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:169` | `extracted[0]`/`extracted[1]` 是位置魔法下标 | `EngineDataPlugin.kt:176` |
| F440 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:261` | SHA-256 与十六进制编码辅助函数重复实现 | `EngineDataPlugin.kt:269-273`、`DataDescriptorPlugin.kt:115-116`、`EngineDataPlugin.kt:261` |
| F460 | NIT | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:685` | EnginesTest 布局问题与测试辅助代码重复 | `EnginesTest.kt:609`、`EnginesTest.kt:36-84`、`EnginesUserWordsTest.kt:24-50` |
| F461 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:159` | Engines 中 added 一名指代四种不同事物 |  |
| F525 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164` | 工具指纹每次构建至少计算两次 | `EngineDataPlugin.kt:171`、`ToolFingerprint.kt:19` |
| F545 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:22` | 码表注册信息分散在三个列表中 | `LibimeMigration.kt:22`、`Engines.kt:949`、`LibimeMigration.kt:164` |
| F555 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:213` | 编码与文本用制表符拼接成字符串键（基本类型偏执） | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:213`、`Engines.kt:943` |
| F605 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:206` | `ENGINE_DATA` 只标识了语言模型那次发布 | `EngineDataPlugin.kt:214`、`AboutFragment.kt:46-47`、`.github/ISSUE_TEMPLATE/missing_words.yaml:50` |
| F606 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:233` | 大文件下载时没有任何提示输出 | `EngineDataPlugin.kt:240-266` |
| F611 | NIT | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:313` | forEachAfter 的二元/三元词 id 未对词表大小校验 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:233-246`、`Engines.kt:118`、`UserModel.kt:343` |
| F625 | NIT | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:55` | UserScorerTest 用默认 modelWords = 0 列出模型词 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:55`、`UserModel.kt:221-226`、`Engines.kt:444` |
| F667 | NIT | `build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:126` | voice 模型在 build/ 下存了两份 | `VoiceDataPlugin.kt:73-77`、`EngineDataPlugin.kt:301-306`、`VoiceDataPlugin.kt:84-89` |
| F672 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:126` | 解压任务的 CMake 路径忽略 `cmake.dir` | `EngineDataPlugin.kt:133`、`VoiceDataPlugin.kt:75` |
| F670 | NIT | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:178` | 显式设置的输出路径可能被 AGP 覆盖 | ❓ 该论断取决于 AGP 9.3.1 的 addGeneratedSourceDirectory 是否会重置任务的 outputDir。AGP 的源码既不在仓库中，也不在本地 Gradle 缓存中，并且不存在 app/build 输出。本次改动只是移动了被引用的代码（EngineDataPlugin.kt:185、:194、:206、:217-219；VoiceDataPlugin.kt:88、:99；release-report.sh:9）。 |

F55「句子模型或笔画数据资产缺失时没有日志」也仍存在：第二轮作为 F735（T9 回退到通用模型时没有任何日志或信号）再次指出。

## 3. 汇总表（全部未撤回的问题，两轮合并）

第一轮的条目在前，第二轮的条目（F729 起）接在每个级别的后面。「当前状态」见 1.5 节。

### 3.1 MUST FIX（必须修）：8 条

| id | 共识 | 验证 | 当前状态 | 位置 | 标题 |
|---|---|---|---|---|---|
| F1 | 5/20 | ✅ 已确认 | 用户：关闭云备份 · 仍存在✓ | `app/src/main/AndroidManifest.xml:28 +7 处` | Auto Backup 将学习词和剪贴板历史上传云端 |
| F2 | 4/27 | ✅ 已确认 | 用户：降为文档问题 · 仍存在✓ | `README.md:174 +9 处` | LCCC 派生评测集与 README「不使用仅限科研数据」矛盾 |
| F3 | 3/21 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:348 +5 处` | 一次删除多个已学词时全部未被遗忘 |
| F4 | 3/9 | ✅ 已确认 | 已修复✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:18 +2 处` | VoiceText 的 \b 缩写合并只在 JDK 19+ 生效 |
| F5 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:76 +6 处` | 内置 ClearURLs 默认开启且二次解码查询串，改坏复制的链接 |
| F6 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:139 +5 处` | 主题导入信任 zip 内 JSON 的路径和名称，可在 theme/ 外写文件 |
| F7 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:78 +4 处` | 文档提供者未规范化文档 ID，可越界读写私有数据 |
| F8 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:245 +1 处` | 输入法设置页的 ListPreference 未设 key，点击即崩溃 |

### 3.2 SHOULD FIX（应该修）：268 条

| id | 共识 | 验证 | 当前状态 | 位置 | 标题 |
|---|---|---|---|---|---|
| F9 | 8/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:158 +5 处` | `--beam` 等新选项未经 `optionsValid` 校验 |
| F10 | 8/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:36 +17 处` | 精排在后台重排列表，按索引的点选与长按操作可能命中别的词 |
| F11 | 7/24 | ✅ 已确认 | 仍存在✓ | `.github/workflows/pull_request.yml:64 +10 处` | CI 从不编译或测试 voice flavor |
| F12 | 7/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/SourceException.kt:42 +6 处` | 模块内多份 fcitx 值转义实现且边界行为不一致 |
| F13 | 7/24 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:127 +7 处` | 可见密码框未被当作密码框，自动配对和语音仍生效 |
| F14 | 7/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20 +3 处` | 解压 zip 的路径穿越检查可绕过且输出流未关闭 |
| F15 | 7/17 | ✅ 已确认 | 仍存在✓ | `app/src/main/AndroidManifest.xml:5-12 +7 处` | 没有构建检查阻止依赖合入 INTERNET 网络权限 |
| F16 | 6/21 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:47 +6 处` | AutoPairs 有配对时，每输入一字都同步读取编辑器 |
| F17 | 6/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/RecentlyUsed.kt:26 +4 处` | RecentlyUsed 的 limit 不生效，最近使用列表无限增长 |
| F18 | 6/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:101 +7 处` | 导入词库时文档提供方的输入流从未关闭 |
| F19 | 6/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:104 +4 处` | PinyinRun 的精排条件与预算与应用不一致，高估「+ models」 |
| F20 | 6/9 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:233 +8 处` | 用户热词与屏蔽词在词表缺字时被写入 logcat |
| F21 | 6/12 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:38 +5 处` | 语音版许可页面漏列随 APK 分发的原生库 |
| F22 | 6/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:672 +3 处` | 上下文记忆键 packageName#fieldId 无法区分浏览器中的各个网站 |
| F23 | 5/15 | ✅ 已确认 | 仍存在✓ | `.github/workflows/unit_test.yml:80 +6 处` | CI 从不运行 `:lib:ime-eval` 的测试 |
| F24 | 5/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:106 +1 处` | TableRun 的 `cheapest` 缓存不缓存不可达词 |
| F25 | 5/24 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:94 +18 处` | 仪器测试仍使用已移除的 libime 输入法名 pinyin/wbx |
| F26 | 5/21 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:20 +4 处` | 输入法设置测试漏测 `engine-t9` 九键页面 |
| F27 | 5/14 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/xml/data_extraction_rules.xml:4 +7 处` | 备份排除规则使用了错误的 domain，实际未排除任何资产 |
| F28 | 5/20 | ✅ 已确认 | 仍存在✓ | `app/build.gradle.kts:80 +12 处` | generateLocaleConfig 将半翻译语言列入系统应用语言 |
| F29 | 5/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:143 +1 处` | ChineseNumbers 用默认 locale 格式化分钟，输出本地化数字 |
| F30 | 5/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:401 +3 处` | 调试日志记录每个 fcitx 事件的全文，包括密码框输入 |
| F31 | 5/6 | ✅ 已确认 | 仍存在✓ | `.github/workflows/nix.yml:8 +7 处` | CI 工作流缺少 permissions 限权，并信任上游 cachix 缓存 |
| F32 | 5/21 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:109 +8 处` | 文字版构建中残留的 Voice 设置值让列表显示为空 |
| F34 | 5/21 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:306 +8 处` | 离开页面会取消自定义短语保存后的引擎重载 |
| F35 | 4/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:692 +10 处` | readings.tsv 读音笔误会静默删除发布词库中的词 |
| F36 | 4/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/apply.py:80` | apply.py:80 的 f-string 需要 Python 3.12+ |
| F37 | 4/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:123 +3 处` | 光标移到位置 0 时未清除记住的配对 |
| F38 | 4/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/T9Set.kt:20 +2 处` | T9Set 与 `t9` 命令缺少单元测试 |
| F39 | 4/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionary.kt:16 +3 处` | 词库扩展名区分大小写导致无法导入，测试反而固化该缺陷 |
| F40 | 4/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemePresetTest.kt:92 +2 处` | ThemePresetTest 的颜色数量断言永远成立，KDoc 夸大了覆盖 |
| F41 | 4/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:471 +1 处` | 删除主题的菜单项标题为「保存」 |
| F42 | 4/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values-zh-rTW/strings.xml:200 +3 处` | zh-rTW 码表词库错误提示给出错误的文件扩展名 |
| F43 | 4/9 | ✅ 已确认 | 仍存在✓ | `gradle/wrapper/gradle-wrapper.properties:4 +1 处` | Gradle 发行包没有用 distributionSha256Sum 固定校验 |
| F44 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:82 +3 处` | NewWordsTest 草图测试未断言「碰撞只会多计」的上界 |
| F45 | 4/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Keyboard.kt:78 +2 处` | 用 session.reads('2') 判断九键会误伤数字编码码表 |
| F46 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntry.kt:25 +3 处` | 引号提前闭合的短语被解析为空短语条目 |
| F48 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:105 +3 处` | 码表中选过的共享词被删除后，重启又出现 |
| F49 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableText.kt:140 +1 处` | 导入检查未按引擎方式读码表，坏表能导入却无法打字 |
| F50 | 4/21 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:80 +5 处` | 模型权重从映射的资源复制到Java堆 |
| F51 | 4/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:230 +3 处` | gestureConsumed 只在开启滑动时重置，测试固定了该潜在缺陷 |
| F52 | 4/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/matrix-kernel.cpp:66 +6 处` | 原生 int8 矩阵内核没有与 MatrixKernel.JVM 对比的测试 |
| F53 | 4/21 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine_public.h:56 +4 处` | JNI 两侧的事件编号与 Offer 位仅靠注释同步 |
| F54 | 4/12 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:23 +4 处` | 测试未覆盖 provider 返回显示名的分支 |
| F55 | 4/18 | ✅ 已确认 | 仍存在（F735） | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:133 +4 处` | 句子模型或笔画数据资产缺失时没有日志 |
| F56 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:230 +2 处` | 同文本提交用 selection.current 判断，光标留在词内 |
| F57 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:77 +4 处` | WordLists 改写词表不 fsync，读失败后会覆盖原文件 |
| F58 | 4/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:38 +5 处` | PunctuationComponent 不取消旧更新，旧映射可覆盖新映射 |
| F59 | 4/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:196 +1 处` | 九键音节列每次按键都重建全部视图 |
| F60 | 4/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/play/contact-email.txt:1 +2 处` | play/ 商店元数据仍为上游 Fcitx5 的内容 |
| F61 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:26 +4 处` | Zstd 128 MB 输出上限与倍增扩容可耗尽堆内存 |
| F62 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/Strokes.kt:42` | 每个笔画键都扫描整张表并对每个匹配解码两次 |
| F63 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/engine-data.sh:50 +6 处` | 改动 REMOVE/READINGS 后不重建 pinyin.data |
| F64 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:109 +3 处` | 规则标志测试因计数守卫而通过，标志检查从未执行 |
| F65 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:107 +6 处` | 测试中的无界循环和「不会挂起」的模糊测试没有超时 |
| F66 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:109 +4 处` | 密码框精排测试检查的是已删除的云端钩子，真正的隐私承诺未断言 |
| F67 | 3/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:314 +4 处` | UserStoreTest 的压缩退避测试无法失败 |
| F68 | 3/21 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/CandidatesPagingSource.kt:26 +5 处` | 展开候选分页的结束判断差一，最后一个候选不显示 |
| F69 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/Theme.kt:105` | 解码自定义背景图时 FileInputStream 未关闭 |
| F70 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:231 +2 处` | 主题版本回退测试未真正删除 version 字段 |
| F71 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:160 +4 处` | 从弹出键盘选多字符项后一次性 Shift 未释放 |
| F72 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:73 +3 处` | 删除或编辑主题后主题列表的选中标记指错 |
| F73 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:62 +3 处` | `Locales.language` 从不是纯语言代码 |
| F74 | 3/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values/strings.xml:441 +4 处` | 备份说明未提及导出文件包含剪贴板历史（含敏感条目） |
| F75 | 3/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/engine-data.sh:84 +7 处` | engine-data.sh 产出的数据与 app 构建不一致 |
| F76 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:146 +1 处` | CommonCrawl 抓取重试与退避不打日志 |
| F77 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CountFit.kt:30 +2 处` | CountFit 退化拟合守卫依赖浮点结果恰好为零 |
| F78 | 3/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/build.gradle.kts:54 +2 处` | lexicon/misreadings.tsv 未声明为测试任务输入 |
| F79 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:170 +2 处` | EnginesTest 的 return@repeat 只跳过一次迭代未结束循环 |
| F80 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:94 +3 处` | 开启U_OU后全拼与九键把单独的u当作ou，双拼则不会 |
| F81 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:50 +3 处` | 空输入时u启动笔画查询，u开头的拉丁词无法输入 |
| F82 | 3/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:284 +7 处` | UserModel 在解码器每条弧上对 Int 装箱 |
| F83 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:86 +4 处` | AutoPairs 绕过 EditingSession 线程检查，守护测试未覆盖 |
| F84 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:58 +4 处` | 引擎候选列表不可翻页，浮动候选窗失去翻页箭头和高亮 |
| F85 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:107 +1 处` | 加载语音模型时抛出 Error 会让 ready 永远挂起 |
| F86 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:849 +7 处` | 服务中的隐私与配对判断没有单元测试，也未下沉到 ime-core |
| F87 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:126 +5 处` | EngineBridge.additions() 中的数据安全逻辑没有单元测试 |
| F88 | 3/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/ImmutableGraph.kt:55 +3 处` | bfs 的 level 是出队计数而非深度，依赖展开取决于顺序 |
| F89 | 3/18 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:424 +4 处` | vivo 兼容模式下按住说话跟踪错误手指 |
| F90 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:154 +3 处` | 密码框中长按空格无反应但仍显示麦克风角标 |
| F91 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:71` | 每次创建输入视图都预先构建 T9Keyboard |
| F92 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323 +2 处` | 字母滑动覆盖规则位于 View 中且无测试 |
| F93 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:176 +2 处` | 弹出键盘顶行空位导致绘制列与选中列错位 |
| F94 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:31 +3 处` | 系统设置中选的语言显示为「跟随系统」且无法在应用内重置 |
| F95 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:34 +5 处` | 堆转储导出在重建后可能崩溃并残留转储文件 |
| F96 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:266 +3 处` | 导出时在 IO 线程上遍历实时列表 |
| F97 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:80 +2 处` | 用应用打开的文件在返回页面后再次提示导入 |
| F98 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:154 +3 处` | 标点卡片排位与编辑规则写在 Fragment 内且无单元测试 |
| F99 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:104 +1 处` | 用户词导入整体读入内存且无大小上限 |
| F100 | 3/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ToolFingerprint.kt:20 +2 处` | ToolFingerprint 缓存键逻辑和手写解析器没有测试 |
| F101 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawlTest.kt:86 +3 处` | CommonCrawl 的 HTTP 抓取与重试策略无测试 |
| F102 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:42` | PageCleaner 字种多样性规则会丢掉所有长页面 |
| F103 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/run-on-device.sh:19` | run-on-device.sh 切换输入法失败被吞且从不恢复 |
| F104 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:34 +4 处` | chinese-addons 手抄的顶层 CMake 已与上游不同且无漂移检查 |
| F105 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:117 +1 处` | 导入失败后仍在运行的引擎继续写已被替换的日志文件 |
| F106 | 3/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/GridExpandedCandidateWindow.kt:85 +3 处` | 候选为空时点下一页会滚动到 -1，导致输入法崩溃 |
| F107 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:165 +2 处` | textAction 按键/提交判定逻辑无单元测试 |
| F108 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:96` | 密码框中选择的符号被记入最近使用 |
| F109 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:26 +2 处` | 语言区域到标签的映射没有测试 |
| F110 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:102 +2 处` | 每次编辑即保存：保存可能乱序或丢失且失败无提示 |
| F111 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ChatCounts.kt:47 +1 处` | ChatCounts 逐字建 String，既多分配又拆开 Ext-B 字 |
| F112 | 3/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:56 +3 处` | LayerPrior.of 在解码热循环中做装箱 HashMap 查找 |
| F113 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:609 +2 处` | 「拼音仅在被选中时上屏」的测试只覆盖超过码长的拼音 |
| F114 | 3/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:104 +2 处` | 导出的 MainActivity 接受任意应用发来的路由 |
| F115 | 3/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:49 +3 处` | 剪贴板编辑在活动销毁时可能丢失 |
| F116 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidfrontend/androidfrontend.cpp:484 +2 处` | setCandidatePagingMode 未判空就解引用 activeIC_ |
| F118 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:141` | 标点键输入框接受多个字符 |
| F119 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntry.kt:19 +2 处` | 快捷短语解析用 trim() 去掉了 fcitx 保留的 Unicode 空格 |
| F120 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:99 +4 处` | SharedLexiconTest 下次启动“告知”检查无法失败 |
| F121 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:14 +2 处` | Reranker测试中的模型从不读取光标前上下文 |
| F122 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:343 +2 处` | aTextIsForgottenHoweverItWasRead 只覆盖一种读音 |
| F123 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:110 +3 处` | FcitxTest 修改设备上持久化的设置和数据且不恢复 |
| F124 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:42 +11 处` | 档位列表用字面量重复偏好的键和默认值 |
| F125 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:211 +2 处` | 重命名和移动忽略 renameTo 的失败结果 |
| F126 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:303 +3 处` | 保存失败时自定义短语的修改丢失 |
| F127 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:84 +6 处` | 设置页手工复制引擎配置键与方案，未用 ime-core 常量 |
| F128 | 2/6 | ✅ 已确认 | 仍存在✓ | `gradle.properties:36 +3 处` | gradle.properties 提交了单机专用的 daemon=false |
| F129 | 2/9 | ✅ 已确认 | 仍存在✓ | `.github/workflows/pull_request.yml:61 +7 处` | CI 每次重新下载约 400 MB 并重编引擎数据 |
| F130 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:81 +1 处` | PageCleaner 垃圾词按子串匹配，误伤正常页面 |
| F131 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:174 +2 处` | NewWords 采样落空时熵为 NaN，词串仅凭 pmi 通过 |
| F132 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:291 +2 处` | table --words --pinyin 等 CLI 路径无测试 |
| F133 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:96 +4 处` | CorruptionTest 遍历漏掉生产中的查找路径 |
| F134 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:96 +2 处` | 笔画查询选字后用该单字重置了拼音会话的上下文 |
| F135 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:246 +3 处` | 防御恶意导入文件的字典树守卫无测试，模糊测试也到不了 |
| F136 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:405 +6 处` | Ranking按文本去重掩盖了「不再学习」类断言 |
| F137 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserStore.kt:61 +2 处` | 超过 64 KB 的 FORGOT 记录被静默丢弃，遗忘在重启后失效 |
| F138 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/release-report.sh:48 +1 处` | release-report 在 score 失败时退出且不写原因 |
| F139 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:220 +5 处` | greedy_search 下传入 blocked 会解引用空的 BPE 编码器 |
| F140 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:44 +1 处` | 以 URL 开头的整段文字被当作一个 URL 处理，后面的文字被删或被编码 |
| F141 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:47 +3 处` | Levels.current() 直接相加不同量纲的差值，显示错误的档位 |
| F142 | 2/18 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:83 +4 处` | 词包首次导入接受的名称，再次导入时被 importPack 拒绝 |
| F143 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:61 +10 处` | 原子替换写文件前未 fsync，且同一写法手写了多处 |
| F144 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:479 +3 处` | 直接启动存储同步遗漏用户自定义的长按与滑动字符 |
| F145 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:57 +2 处` | 包名变更后主题背景图的重定位逻辑没有测试 |
| F146 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:824 +1 处` | 意外的光标报告触发主线程同步读上下文，硬件键每键一次 IPC |
| F147 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/editing/TextEditingWindow.kt:81 +2 处` | 文本编辑面板的剪切和退格绕过 ContextMemory，已删的文字仍作上下文 |
| F148 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/core/CapabilityFlagsTest.kt:27 +4 处` | EditorInfo 到 CapabilityFlags 的映射只测试了 PIN |
| F149 | 2/18 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:165 +4 处` | 滑动与长按对同一字符走不同输入路径 |
| F150 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:96 +3 处` | 弹出网格计算与多字符触发规则没有测试 |
| F151 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/common/ProgressBarDialogIndeterminate.kt:73 +3 处` | 加载对话框的清理没有放在 finally 中 |
| F152 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222` | 词包是否替换取决于提供者报告的文件名 |
| F153 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:103 +1 处` | 码表选项的搜索结果打开的页面里没有这些选项 |
| F154 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:198 +1 处` | 用户词搜索的归一化与排序逻辑在 Fragment 中且无测试 |
| F155 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:319` | 自定义主题裁剪回调在主线程读取整张原图 |
| F156 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22 +1 处` | `Locales.picked()` 的新逻辑缺少单元测试 |
| F157 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/NaiveDustman.kt:56 +1 处` | `NaiveDustman.reset()` 不清空旧初始值，重加的条目可能丢失 |
| F158 | 2/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164 +2 处` | 开启配置缓存后工具指纹失效，引擎数据不再重建 |
| F159 | 2/6 | ✅ 已确认 | 仍存在✓ | `lexicon/readings.tsv:329 +5 处` | readings.tsv 替换掉常用读音且未进 misreadings.tsv |
| F160 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:653 +3 处` | forgetInTables 漏掉启动后未加载的用户导入码表 |
| F161 | 2/21 | ✅ 已修复（第二轮在 5881d31 上确认） | 已修复✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:362 +3 处` | Engines 层面没有九键（T9）输入法测试 |
| F162 | 2/6 | ✅ 已确认 | 用户：要拆分 · 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:68 +1 处` | Engines 单个约 950 行的类承担过多职责 |
| F163 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:446 +4 处` | 以非字母键结束查询时丢掉已输入的引导键 |
| F164 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:166 +1 处` | WordLists.split 不限词长，DP 分配可致 OOM |
| F165 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:83 +4 处` | LibimeImport.readings 遍历整棵字典树，交互路径上也如此 |
| F166 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/release-report.sh:49 +3 处` | 发布门禁按列位置解析 CLI 输出且无测试约束 |
| F167 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/native-lib.cpp:766 +2 处` | 引擎抛异常后面板被清空，但 Kotlin 会话仍保留旧组合 |
| F168 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:120 +1 处` | 语音候选的选择逻辑留在 app 中，CI 测试覆盖不到 |
| F169 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:224 +3 处` | 导入的码表打开拼音设置页，修改会覆盖拼音设置 |
| F170 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt:23 +2 处` | knownSubtypes 是普通 HashMap，被多线程读写 |
| F171 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceCategory.kt:34 +2 处` | 被 hidden {} 隐藏的设置保留旧的非默认值且无法再修改 |
| F172 | 2/15 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:45 +5 处` | 评测 runner 未检查即在同进程再启动一个 Fcitx |
| F174 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:116 +3 处` | 九键切换在主线程 runBlocking 等待 fcitx |
| F175 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:237 +4 处` | ACTION_CANCEL 被当作真实抬起分发 |
| F176 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323 +1 处` | 滑动覆盖忽略 Shift/Caps 且不释放一次性 Shift |
| F177 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:195 +3 处` | 替换词包时默认选中基础层，会把新词层的包移走 |
| F178 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222 +3 处` | 词典导入的替换/拒绝决策位于 Fragment 且无测试 |
| F179 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:73 +1 处` | 搜索索引构建不写偏好的约束缺少守护测试 |
| F180 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:41 +4 处` | fcitx 的语言环境不跟随应用内选择的语言 |
| F181 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:174 +3 处` | PinyinDataBuilder 对数百万 n-gram 下标做装箱排序 |
| F183 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:281 +1 处` | Scorer对保留步数的上限从未被测试，可能使长读法精排无结果 |
| F184 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:167 +1 处` | 剪贴板建议超时后只清标志不刷新，建议仍留在栏上 |
| F185 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/IdleUi.kt:143 +3 处` | 新增表情和语音按钮挤压工具栏，窄屏上按钮小于 40dp |
| F186 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:43 +4 处` | 页面承诺同键多卡片可供选择，但输入时不提供 |
| F187 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/Settings.kt:26 +2 处` | `getSecureSettings<String>` 遇空值抛 NPE |
| F188 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceBlocking.kt:18 +1 处` | 语音屏蔽词匹配在 VoiceText.clean 去空格之前进行 |
| F189 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/InlineSuggestionsUi.kt:113 +1 处` | 内联自动填充建议视图堆积，旧响应可能盖过新响应 |
| F190 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridView.kt:55 +3 处` | 首次测量前行高为 0，可能绑定整个列表 |
| F191 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:46` | `git describe --tags` 把数据发布 tag 当成应用版本名 |
| F192 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:65 +2 处` | ReadersTest 固化了 skipped 读音的重复计数 |
| F193 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:100 +4 处` | 新增命令须改 Main.kt 五处，未知 flag 被当成输入路径 |
| F194 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:267 +3 处` | candidates.tsv 各列按裸下标读取 |
| F195 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:518 +1 处` | 三个拼音会话重复传入同一组命名参数（Data Clump） |
| F196 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:180` | 九键「停顿未改变任何东西」测试无论代码如何都通过 |
| F197 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:47 +2 处` | 笔画测试名中「未知字按笔画数从少到多」从未被测试 |
| F198 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:134 +2 处` | 针对新版本日志记录类型的守卫实际上未被测试 |
| F199 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:132` | ChineseNumbers 把「一点半点」等成语转成时间 |
| F200 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupOverridesTest.kt:127 +1 处` | PopupOverridesTest 的去重测试没有重复输入 |
| F201 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:215` | removeOutdated 按 id 选截断点却按时间戳删除，误删限额内的条目 |
| F202 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:41 +2 处` | 设备端评测 runner 丢弃每个样本的上下文字段 |
| F203 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:253 +1 处` | 导入短语逐条添加且每条弹出一个 Snackbar |
| F204 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:157 +2 处` | 改键后卡片可能在未勾选「首选」时成为该键首选 |
| F205 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:699 +2 处` | pinyin 的 --misreadings 与双 pack 调用无测试 |
| F206 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:346` | 删除曾被选用的自造词后仍可输入并变为 LEARNED |
| F207 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:232 +1 处` | 基础词包测试无法因其名称所述行为而失败 |
| F208 | 1/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:687 +4 处` | 显示预测时actionable为false，无法对预测长按忘记或屏蔽 |
| F209 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:741` | 九键模糊读法的预编辑和回车上屏显示错误字母 |
| F210 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:71 +2 处` | 损坏的日志被静默截断，之后可能被重新导入覆盖 |
| F211 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:77 +2 处` | 导入码表的提示码与构词码输出没有测试 |
| F212 | 1/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:15 +2 处` | 九键分词测试从未按生产配置构建分词器 |
| F214 | 1/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:292 +2 处` | resolveBlock 的按词读音计数器没有测试 |
| F215 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/release-report.sh:45 +1 处` | 发布门禁关闭近邻键读取，而应用默认开启 |
| F216 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:235 +1 处` | `score --half` 可能把其他样本的结果算给本样本 |
| F217 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictLearning.kt:25` | 预测（联想）测量代码缺少测试 |
| F218 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:110 +1 处` | 「+ models」背后的重排逻辑没有测试覆盖 |
| F219 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:209 +1 处` | pending.toList() 非原子，可能丢失听写文本并泄漏识别器 |
| F220 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:325 +2 处` | 提交文字要等待一个随后被丢弃的 stream 构建 |
| F221 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:313` | close() 之后仍继续解码已排队的语音段 |
| F222 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.h:89 +3 处` | 共享选项及其默认值在三个配置结构中重复书写 |
| F223 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:125 +2 处` | 字母布局别名（Text/T9）状态逻辑无测试 |
| F224 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:67 +1 处` | 活动重建时启动 intent 被重复处理 |
| F225 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:215 +1 处` | 大写键「恢复内置列表」和重置得不到内置列表 |
| F226 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:189 +2 处` | 新建快捷短语列表的名称校验不足，可致崩溃或重复 |
| F227 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:16-24` | `runCmd` 的失败回退是死代码 |
| F228 | 1/12 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:418 +1 处` | 五个码表串行编译，每次在新 JVM 中重复同样工作 |
| F229 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:424 +1 处` | pack/words 的基础层过滤从未用分层数据测试 |
| F230 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:56 +2 处` | CommonCrawl 每个分片等全部抓取完成，线程池空闲 |
| F231 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:522 +2 处` | 拼音长按“遗忘”不会传到码表 |
| F232 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/DataFileTest.kt:131 +1 处` | DataFile 损坏测试未覆盖经 has() 读取的可选段 |
| F233 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeTrie.kt:101 +1 处` | LibimeTrie 输出的键字节受树深而非文件大小约束 |
| F234 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:261 +2 处` | “the prior learned is kept”测试从未检验先验被保留 |
| F235 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:113 +1 处` | 九键末尾的2/3/6/7被读作EXTENDED而非PARTIAL |
| F236 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:66 +2 处` | 九键上啊/呃/哦使2、3、6上的声母无法按简拼读出 |
| F237 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:535 +2 处` | 每次输入约20个可变字段在两处手工重置且不一致 |
| F238 | 1/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:54-55 +2 处` | 用户层门禁未带 reranker、KeyHabits 与 PACK |
| F239 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:63` | PinyinRun 预热在每个线程上重放整手样本三次 |
| F241 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/build.gradle.kts:29 +3 处` | 固定并校验哈希的 onnxruntime 可能被预装版本静默替换 |
| F242 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:177 +2 处` | 每个 stream 都重建相同的 78k 短语热词图 |
| F243 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/native-lib.cpp:508` | youmo 自写的 utf8FromJString 编码函数没有测试 |
| F244 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:709 +4 处` | 在同一输入框收起再弹出键盘后，引擎上下文丢失 |
| F245 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:215 +1 处` | 撤销或确认删除在挂起后才清空 pendingDeleteIds，可能丢失剪贴条目 |
| F246 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:107 +2 处` | 设备端评测在 25M 模型精排之前就读取候选，结果依赖时序 |
| F247 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:67 +1 处` | Engines 对象从不关闭，fcitx 停止后仍占着模型和文件句柄 |
| F248 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:153 +1 处` | 导入的词包和词典被一次性整个读进内存 |
| F249 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:108 +3 处` | 从旧 app 迁移数据的路径缺少端到端测试 |
| F251 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:251 +1 处` | AutoPhraseLength 对话框的说明文字挤掉了选项列表 |
| F252 | 1/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/AndroidManifest.xml:103 +1 处` | VIEW 过滤器缺少 .words，下载的词库包无法用本应用打开 |
| F253 | 1/9 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt:111 +1 处` | 缺少 baseline profile 且排除 profileinstaller |
| F254 | 1/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/apply.py:76 +1 处` | apply.py 的错位读音检查没有测试 |
| F255 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:593` | 列表词变化后每张码表在下次按键时重新编码全部列表词 |
| F256 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:371 +1 处` | 关闭学习的输入框中屏蔽与忘记仍会写入磁盘 |
| F257 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordFormat.kt:58` | 记录回放抛出非IOException时整个日志打开失败 |
| F258 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWords.kt:53 +1 处` | 共享词绕过了构建期词所受的顶屏检查 |
| F259 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:68 +1 处` | 丢弃一个选择键会把其后的键移到别的候选上 |
| F260 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:116` | 计数减半会永久删除用户自有词与 libime 导入词 |
| F261 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:48` | 用 “ 跨过 ” 后 fcitx 的引号状态反转 |
| F262 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditorTraits.kt:78 +1 处` | 方向键按一个 UTF-16 单元移动，会落进代理对中间 |
| F263 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:397` | `ksc` 每个样本新建会话，丢弃解码器的前瞻缓存 |
| F266 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:78` | 候选操作菜单比负责关闭它的事件任务活得更久 |
| F267 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:157` | 滑动删除剪贴条目时位置为 -1 导致崩溃 |
| F268 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:71 +3 处` | FcitxTest 的事件在进入 UNLIMITED 通道之前仍可能被丢弃 |
| F269 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:188 +4 处` | 每次进程启动后，第一个按键在 fcitx 线程上构建整个引擎 |
| F270 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:106 +2 处` | replaceTableDict 的改名与删除路径没有测试 |
| F271 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:265 +1 处` | 长按已关闭的键向下滑动仍可能输入该字母 |
| F272 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:74 +2 处` | 新底行移除了快捷短语和 Unicode 输入的唯一入口 |
| F273 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:55 +1 处` | getSwipeDirs 把拖动方向当作滑动方向返回 |
| F274 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SearchHighlight.kt:19 +2 处` | 搜索高亮用带 5 秒计时的全局单例传递导航参数 |
| F275 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:125` | HttpClient 默认 HTTP/2，8 个线程共享一条连接 |
| F276 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/native-lib.cpp:557` | 引擎持续失败时每次按键都重试加载并打印堆栈 |
| F277 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:49` | 模块改用包装层 project()，addon .conf 翻译可能失效 |
| F278 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:241` | 每次按住说话都先创建 VAD 会话再开麦 |
| F279 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:247` | AudioRecord 只用浮点采样，没有 16 位回退 |
| F280 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer.h:103` | 屏蔽只作用于单一 token 序列，设备测试只覆盖句首词 |
| F281 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:328 +2 处` | 退格删除空的“”后，fcitx 可能仍认为左引号未闭合 |
| F282 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseEditFragment.kt:159 +2 处` | onStop 中启动的保存与重载会随 Fragment 销毁被取消 |
| F729 | 6/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:84 +4 处` | T9 模型对的关闭释放与重新读取没有测试 |
| F730 | 4/7 | ✅ 已确认 | 用户：去掉 T9 模型对 · 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:164 +7 处` | 九键与全拼/双拼混用时堆上常驻两套句子模型 |
| F731 | 4/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:111 +3 处` | T9 模型对让每个 APK 增大约 30 MB，代价未测量 |
| F732 | 3/7 | ✅ 已确认 | 新增 | `lib/ime-eval/release-report.sh:46 +2 处` | 发布门禁不评测九键及其自有模型对 |

### 3.3 NIT（小问题）：442 条

| id | 共识 | 验证 | 当前状态 | 位置 | 标题 |
|---|---|---|---|---|---|
| F283 | 6/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:6 +3 处` | StringEscapeTest 包名与目录不一致 |
| F284 | 5/14 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values/strings.xml:236 +6 处` | 用户可见文案仍使用上游名称 fcitx5-android |
| F285 | 4/6 | ✅ 已确认 | 仍存在✓ | `gradle.properties:12 +6 处` | gradle.properties 中堆大小注释与实际值矛盾 |
| F286 | 4/12 | ✅ 已确认 | 仍存在✓ | `app/build.gradle.kts:20 +4 处` | `APPLICATION_ID_ROOT` 的注释与字段实际含义相反 |
| F287 | 4/9 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:83 +3 处` | VoiceDataPlugin 隐式依赖 engine-data 插件的应用顺序 |
| F288 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:41 +8 处` | NewWordsTest 注释与测试数据及断言矛盾 |
| F289 | 4/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:110 +5 处` | Vocabulary 的 KDoc 错放在 WordLayers 上方 |
| F290 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:31 +2 处` | SentenceRefiner.offline()是已删云端精排遗留的死代码 |
| F291 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:7 +6 处` | 拆分测试后遗留的未使用导入 |
| F292 | 4/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordPack.kt:47 +3 处` | WordPack 及其测试中有不可见的 BOM 字面量 |
| F293 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictRun.kt:45 +3 处` | PredictRun 的数字格式依赖默认 locale |
| F294 | 4/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-new-words-set.py:138 +2 处` | make-new-words-set.py 中无用的 READINGS 条目 |
| F295 | 4/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/native-lib.cpp:29 +3 处` | utf8.h 被引入却未使用，utf8FromJString 手写了编码分支 |
| F296 | 4/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values-zh-rTW/strings.xml:21 +17 处` | zh-rTW 繁体文件中混有简体字和大陆用语 |
| F297 | 4/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/drawable/ic_launcher_foreground.xml:32 +3 处` | 生成的启动图标含浮点残留和未用的 xmlns:aapt |
| F298 | 4/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values/strings.xml:405 +7 处` | 计数字符串未使用 plurals，单数时显示“1 words” |
| F299 | 4/17 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values-zh-rCN/strings.xml:288 +5 处` | zh-rCN 无障碍标签未说明操作对象（隐藏、向左移动） |
| F300 | 4/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:930 +2 处` | 删除导入码表时遗留 .told 标记 |
| F301 | 4/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:712 +2 处` | 每次显示键盘都同步查询应用语言（API 33+ 上一次 binder 调用） |
| F302 | 4/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:191 +1 处` | EditingSession 把预编辑文本写进调试日志 |
| F303 | 3/6 | ✅ 已确认 | 仍存在✓ | `.github/workflows/unit_test.yml:124 +4 处` | 测试报告上传遗漏 `lib/ime-dict-tool` |
| F304 | 3/9 | ✅ 已确认 | 仍存在✓ | `config/detekt/detekt.yml:48 +7 处` | 注释引用仓库中不存在的 dev/ISSUES.md 等文件 |
| F305 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/engine-data.sh:45 +3 处` | engine-data.sh 在 cd 到 $WORK 后相对路径失效 |
| F306 | 3/6 | ✅ 已确认 | 仍存在✓ | `lexicon/README.md:13 +4 处` | lexicon/README.md 文件表缺少 misreadings.tsv |
| F307 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:74` | MainTest 行号断言 contains("2") 形同虚设 |
| F308 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:54 +1 处` | Misreadings 中的 ü 字面量以分解形式存储 |
| F309 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/BitPackedTest.kt:24` | BitPackedTest 注释“宽度从不整除 64”有误 |
| F310 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/TextWordsTest.kt:95` | TextWordsTest 用 assert 而非 assertTrue |
| F311 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPriorTest.kt:30 +1 处` | LayerPriorTest 硬编码词 id |
| F312 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/Typo.kt:30 +4 处` | j/q/x/y把ü写作u的规则重复定义了三处 |
| F313 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:53` | Reranker注释引用了仓库中不存在的设计文档 |
| F314 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:22 +4 处` | 会话测试的共享固件分散且被重复定义，九键测试有零碎问题 |
| F315 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:578 +1 处` | 通配符不出构词条目的断言仅靠编码长度就通过 |
| F316 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:284 +5 处` | 码表测试中不起作用的步骤 |
| F317 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:109 +2 处` | KeyHabits 达上限时每次学习都把全部习惯展开为 Triple |
| F318 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:134 +2 处` | WordLists 每次调用都重新编译正则 |
| F319 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:377 +1 处` | UserStoreTest 中「无习惯构建」的读取步骤没有断言 |
| F320 | 3/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:48 +1 处` | WordListsTest 测试名称说应用到模型，但从未调用 applyTo |
| F321 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:333 +9 处` | 测试代码的断言写法与空行风格不一致 |
| F322 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:169 +3 处` | no-latin 方案下 `--neighbours` 被错误拒绝 |
| F323 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/SlipSet.kt:50 +1 处` | SlipSet 生成的样本丢失原样本的 context |
| F324 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:58 +1 处` | `theUsageNamesEveryCommand` 并未检查全部命令 |
| F325 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/PinyinRunTest.kt:12 +4 处` | PinyinRunTest 与 Main.kt 的 import 未排序 |
| F326 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:100 +1 处` | candidateFromAll 已知到末尾后仍多做一次 JNI 获取 |
| F327 | 3/15 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/po/fcitx5-android.pot:56 +4 处` | 引擎描述 Type English words in pinyin 未加入 po |
| F328 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:179 +1 处` | FcitxApplication 的 KDoc 中 adb 命令仍用旧包名 |
| F329 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/ReturnKeyDrawableComponent.kt:44 +1 处` | DEFAULT_DRAWABLE 已无人使用 |
| F330 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:229 +1 处` | cleanClipboardText 吞掉清理器的异常且不记日志 |
| F331 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/TestHarnessSmokeTest.kt:79 +1 处` | 冒烟测试并未验证 KDoc 所说的 mock final 类 |
| F332 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:383 +1 处` | SoftKeyboardTest 提前返回，吞掉恢复失败 |
| F333 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/NumberKeyboard.kt:59` | NumberKeyboard.space 声明为错误的视图类型 |
| F334 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:75 +5 处` | 注释称全局选项在「高级」中，但没有页面显示它们 |
| F335 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:132 +2 处` | 删除标点卡片无确认也无法撤销 |
| F336 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:64 +1 处` | `onKeysChangeListener` 重复刷新主题预览 |
| F337 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt:19` | Const.kt 注释引用了仓库中不存在的文档 |
| F338 | 3/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/drawable/ic_baseline_developer_mode_24.xml:1 +2 处` | 四个 drawable 已无任何引用 |
| F339 | 3/14 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/drawable/ic_baseline_lock_24.xml:7 +3 处` | 锁图标填充为黑色，与其他白色图标不一致 |
| F340 | 3/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values-ja/strings.xml:213 +1 处` | ja 的 removed_n_items 使用 %1$s 而非 %1$d |
| F341 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/editing/EditorInfoTraits.kt:14 +4 处` | baseline-app.xml 条目 ID 内嵌 KDoc，改注释即失配 |
| F342 | 3/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/latin.py:63 +1 处` | latin.py 解析 ARPA 一元行保留换行符，无匹配时崩溃 |
| F343 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:128 +2 处` | WordLayers 在每次查找时而非加载时校验层值 |
| F344 | 3/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:200 +2 处` | UserStoreTest 的先验测试手动调用 journal，未走 learn |
| F345 | 3/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:125 +2 处` | 测试把 predict(CursorRange) 的实例共享行为固定为 API |
| F346 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:211 +3 处` | 分数相同时 nbest[0] 可能与 tokens/text 不一致 |
| F347 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/decoration/GridDecoration.kt:50` | GridDecoration 用子视图下标判断末项，滚动后多画分隔线 |
| F348 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:94 +1 处` | libime 词典迁移的重名检查漏掉了 .words 词包 |
| F349 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/input/editing/KeyEventRelayTest.kt:24 +2 处` | KeyEventRelayTest 使用 mockk 而非 fake |
| F350 | 3/18 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:159 +3 处` | FakeFcitxAPI 的 setEnabledIme 不保留传入顺序 |
| F351 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt:65 +5 处` | popupOverrides 偏好被两处以不同方式解析缓存 |
| F352 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:151` | `SIZE_KEYS` 硬编码偏好键字符串 |
| F353 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/TempDir.kt:11` | 临时目录以毫秒时间戳命名，不唯一 |
| F354 | 3/12 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/TwinSeekBarPreferenceTest.kt:45 +3 处` | TwinSeekBarPreferenceTest 构造偏好的方式与生产代码不同 |
| F355 | 3/11 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/xml/input_method.xml:11 +3 处` | subtype 图标写死 @mipmap/ic_launcher |
| F356 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:77 +2 处` | Predictor 每次提交都对所有后继词 id 装箱 |
| F357 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:163 +2 处` | 记录大小上限应用不一致，且丢弃记录时不报告 |
| F358 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:165 +1 处` | 日志压缩与fsync在输入线程的提交路径上执行 |
| F359 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/po/LINGUAS:1` | LINGUAS 未列出 de 和 es，对应 .po 从不编译 |
| F360 | 3/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:142 +1 处` | 搜狗词库转换先 waitFor 再读输出，可能挂起 |
| F361 | 3/6 | ✅ 已确认 | 仍存在✓ | `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:17 +3 处` | chinese-addons 仍依赖 fcitx5-lua，但已无模块使用 |
| F362 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:282 +1 处` | applySelectionOffset 的 KDoc 已过时 |
| F363 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:62 +1 处` | FcitxInputMethodService 中有未使用的 import |
| F364 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/build.gradle.kts:62 +2 处` | voice 模型体积 ~240 MB 与 README ~180 MB 不符 |
| F365 | 2/6 | ✅ 已确认 | 仍存在✓ | `.github/workflows/unit_test.yml:9` | unit_test.yml 注释引用不存在的 publish 工作流 |
| F366 | 2/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/latin.py:81` | latin.py 输出头部写死 FineWeb 分片名 |
| F367 | 2/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/apply.py:22 +4 处` | 两个 Python 脚本各自解析 Syllables.kt 源码 |
| F368 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:112 +1 处` | NewWords 文档称按页采样邻字，实际按行 |
| F369 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:232` | --cutoffs 解析宽松，非数字字段被静默丢弃 |
| F370 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:491 +3 处` | Phrases 与 Readings 算法藏在 851 行的 Main.kt 中 |
| F371 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:39 +1 处` | PageCleanerTest 注释的汉字数 254 应为 270 |
| F372 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ExamplesTest.kt:57 +1 处` | ExamplesTest 中的 delete() 调用多余 |
| F373 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:133` | LayerPrior.parse 接受 NaN 与 Infinity |
| F374 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:28 +1 处` | StringEscapeTest 中 assertEquals 参数颠倒 |
| F375 | 2/9 | ✅ 已修复（第二轮在 5881d31 上确认） | 已修复✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:22 +3 处` | EnginesTest 有未使用的导入 |
| F376 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/PinyinSegmenterTest.kt:97 +1 处` | PinyinSegmenterTest中重复的断言 |
| F377 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:32 +1 处` | RerankerTest注释称单位为nats，实际为log10 |
| F378 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:8` | LibimeFilesTest从ZstdTest借用测试辅助函数 |
| F379 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:216 +1 处` | JSON深度与数量上限未在边界值处测试 |
| F380 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:376` | assertEquals的期望值与实际值颠倒 |
| F381 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:592` | 测试名称与自身断言矛盾：拼音条目并非排在编码之后 |
| F382 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWordsTest.kt:46 +1 处` | SharedWordsTest中过时、混乱的注释 |
| F383 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:28 +2 处` | dictionary()构建器在多个测试类中重复 |
| F384 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabitsTest.kt:86 +1 处` | KeyHabitsTest 只测超限长度，未测边界值 |
| F385 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:22 +1 处` | 共享测试夹具放在 UserModelTest 的 companion 中 |
| F386 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:115 +1 处` | AutoPairsTest 提交后未回报光标，后半段断言无法失败 |
| F387 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/CodePointsPropertyTest.kt:79 +1 处` | CodePointsPropertyTest 的良构生成器去掉了合法代理对 |
| F388 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:20` | KeyGestureRecognizerTest 辅助函数的文档注释有误 |
| F389 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearchTest.kt:30` | SettingsSearchTest 的注释误述了按键声音的匹配情况 |
| F390 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:69 +1 处` | `everySetParses` 漏掉 predict/chat.tsv |
| F391 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/host-eval.sh:17 +1 处` | host-eval.sh 在单核机器上得到 -j0 |
| F392 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:134` | 束搜索中写入 -inf 时没有越界检查 |
| F393 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt:40 +1 处` | 反向依赖查询 API 没有生产调用方 |
| F394 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PagedCandidatesUi.kt:105 +1 处` | PagedCandidatesUi 回收时未清除长按监听器 |
| F395 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/core/AddonDependencyGraphTest.kt:70` | assertEquals 的期望值与实际值颠倒 |
| F396 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:113 +6 处` | PopupPreset 中希伯来文/阿拉伯文条目重复或无效 |
| F397 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:270 +2 处` | 导出失败时临时文件 customphrase.export 残留 |
| F398 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:149 +2 处` | 词典启用/移动/删除在主线程做文件 I/O 且失败不记日志 |
| F399 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:92 +4 处` | 每次刷新都完整读取所有已导入词典 |
| F400 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/FcitxKeyPreference.kt:24 +1 处` | FcitxKeyPreference 用错误的 styleable 读取属性 |
| F401 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:302 +1 处` | `flagList` 的未用标注写死九键说明与全角括号 |
| F402 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/resources/libime/README.md:1` | app 测试中的 libime 数据是 ime-core 的重复副本 |
| F403 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/test/resources/robolectric.properties:1 +3 处` | Robolectric 配置注释误称全部测试用 SDK 35 |
| F404 | 2/12 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ToolFingerprint.kt:16 +3 处` | ToolFingerprint 中「工具不用反射」的说法不成立 |
| F405 | 2/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:83 +1 处` | 解码出的签名 keystore 以默认文件权限写入 |
| F406 | 2/9 | ✅ 已确认 | 仍存在✓ | `README.md:51 +2 处` | README「数据与许可」漏列 rime-stroke 等组件 |
| F407 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:41 +1 处` | PageCleaner 每页多次全文扫描并装箱字符 |
| F408 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:375` | words 命令忽略 --sketch-bits |
| F409 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:751 +1 处` | 加载失败的导入码表每次按键都抛异常 |
| F410 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:121 +1 处` | importedTableName 不会重命名名为 engine-t9 的导入 |
| F411 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:70 +7 处` | 测试中基于用户目录的 Engines 实例未关闭 |
| F412 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:22 +3 处` | T9Segmenter类文档的简拼规则与代码不符 |
| F413 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:176` | SentenceModel每个分母和每个位置都新建临时数组 |
| F414 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:59 +4 处` | VoiceRerank.notHan 的汉字定义与其他类不一致 |
| F415 | 2/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:211 +1 处` | 点击、滑出、再点击仍被算作双击 |
| F416 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:422` | 剪贴板超时设为 -1（永不）时，重建视图不恢复最后一条 |
| F417 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:83 +1 处` | 标点组件测试在 runTest 中嵌套 runBlocking |
| F418 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:208` | 双击判定使用墙钟时间 |
| F419 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:126 +1 处` | 九键键盘忽略 showLangSwitchKey 偏好 |
| F420 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:79 +1 处` | 「数据过期」按构建时间而非数据日期判断 |
| F421 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchFragment.kt:47` | 搜索页 `none` 视图在销毁后仍持有旧视图树 |
| F422 | 2/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/FileUtil.kt:22 +2 处` | `removeFile` 遇到悬空符号链接时不删除 |
| F423 | 2/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/drawable/bkg_inline_suggestion_dark.xml:9 +1 处` | ripple 的 <item> 上 android:shape 属性无效 |
| F424 | 2/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values/strings.xml:346 +1 处` | 多条字符串已无引用却仍在各语言中维护 |
| F425 | 2/15 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:120 +3 处` | DataFile Writer 不执行读取端的 64 KiB meta 上限 |
| F426 | 2/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:194` | “不学习”测试只检查内存状态 |
| F427 | 2/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:204 +2 处` | 屏蔽多音字的一种读音会覆盖用户实际输入的读音 |
| F428 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/matrix-kernel.cpp:45 +1 处` | 原生内核结果与评测所用 JVM 内核并非逐位一致 |
| F429 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69` | 引擎用户目录不支持 Direct Boot，与 fcitx 主目录不一致 |
| F430 | 2/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/layout/fragment_setup.xml:47 +2 处` | fragment_setup 用 android:tint，装饰图未设无障碍 |
| F431 | 2/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43` | detekt 没有覆盖 debug、testFixtures 等源集 |
| F432 | 2/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/dependency/Functions.kt:41 +1 处` | imeScope() 以裸 CoroutineScope 类型作为依赖注入的键 |
| F433 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:56 +1 处` | 设备端评测分数受设备上已学到的数据影响 |
| F434 | 2/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManagerTest.kt:26 +1 处` | 拼音词库测试共用首个 Robolectric 应用的目录 |
| F435 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:35 +1 处` | 测试字符串中直接嵌入了不可见的 U+FEFF |
| F436 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:13` | 未使用的 import `android.widget.EditText` |
| F437 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:113 +5 处` | EngineDataPlugin.apply() 长达 100 行，下载配置重复 |
| F438 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:221` | 共享的任务类嵌套在单个插件内 |
| F439 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:169` | `extracted[0]`/`extracted[1]` 是位置魔法下标 |
| F440 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:261 +4 处` | SHA-256 与十六进制编码辅助函数重复实现 |
| F441 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:70` | `signKey` 属性的 getter 带有副作用 |
| F442 | 1/12 | ✅ 已确认 | 仍存在✓ | `.github/workflows/pull_request.yml:38 +1 处` | CI 步骤名「Install Android NDK」与实际行为不符 |
| F443 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/Versions.kt:27` | `when (abi)` 重复了 `supportedABIs` |
| F444 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/launcher-icon/make_icon.py:2 +2 处` | make_icon.py 的文档字符串与实现不符 |
| F445 | 1/6 | ✅ 已确认 | 仍存在✓ | `.github/workflows/unit_test.yml:83` | core 测试失败会跳过 app 单元测试 |
| F446 | 1/6 | ✅ 已确认 | 仍存在✓ | `codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:393 +3 处` | GenScancodeMapping 有未用的解构变量和替换残留的注释 |
| F447 | 1/6 | ✅ 已确认 | 仍存在✓ | `codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenKeyMapping.kt:214 +4 处` | GenKeyMapping 生成代码中的 KDoc 已过期 |
| F448 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:60` | corrected() 会把 remove.tsv 中的词重新加回 |
| F449 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:222` | 选项值可以是下一个 flag，重复 flag 静默覆盖 |
| F450 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:188` | 调参常数的依据只在未纳入版本库的 dev/*.md 中 |
| F451 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:119` | WebTextTest 注释说三次，断言却是 4 |
| F452 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/SurpriseTest.kt:40` | SurpriseTest 注释「100 倍」有误，应约 3.2 倍 |
| F453 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:68 +1 处` | ReadersTest 使用全限定名 Syllables.id |
| F454 | 1/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/apply.py:57 +3 处` | apply.py 等处变量名无意义，check() 名不副实 |
| F455 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:76 +6 处` | 共享字符常量多处复制且已出现偏差 |
| F456 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:604 +1 处` | Main.kt:604 的三键排序挤在一行 |
| F457 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataAge.kt:8` | DataAge 引用仓库中不存在的 dev/TRAINING-PLAN.md |
| F458 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:32 +1 处` | MisreadingsTest 的小测试写法问题 |
| F459 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:157` | SharedLexiconTest 类结束括号前多一空行 |
| F460 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:685 +2 处` | EnginesTest 布局问题与测试辅助代码重复 |
| F461 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:159 +3 处` | Engines 中 added 一名指代四种不同事物 |
| F462 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:129 +2 处` | SpellingIndex为每个节点计算的completions从未被读取 |
| F463 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableOptions.kt:29 +3 处` | 文档仍以已删除的晚风、电报码码表举例 |
| F464 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:193` | TableSession.select先读排名再检查索引范围 |
| F465 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/TinyModel.kt:22` | TinyModel宽度等于LANES，矩阵核的余数循环从未运行 |
| F466 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:654` | 测试注释描述的「在下一个键上屏」路径未被执行 |
| F467 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:112 +3 处` | UserModelTest 用魔数代替私有常量 |
| F468 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:273` | UserStoreTest 中 blocker.delete() 不起作用 |
| F469 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:545` | deletingBeforeACompositionMovesIt 分区放错 |
| F470 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:102` | CursorTrackerTest 重复预测用例未能证明其主张 |
| F471 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupEditorTest.kt:55` | deleteSelectsTheNextOrNone 的名称与第二步矛盾 |
| F472 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:154 +1 处` | restore 在 UserModel 累加、在 KeyHabits 覆盖 |
| F473 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:135 +3 处` | 若干局部名遮蔽字段或参数，或含义易误解 |
| F474 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:193 +3 处` | lue/nue 修正在 WordLists 与 LibimeImport 重复 |
| F475 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:29` | AutoPairs 的嵌套类 Pair 遮蔽了 kotlin.Pair |
| F476 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:153` | LibimeImport 的条件混用 \|\| 与 && 且无括号 |
| F477 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-collision-set.py:33 +3 处` | Python 工具打开文件后未关闭 |
| F478 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:82 +2 处` | 命令定义分散在五处，新增命令需多处同步修改 |
| F479 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:257 +3 处` | 参数列表过长且存在数据泥团 |
| F480 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:83 +2 处` | `a`、`p`、`p[1]`..`p[3]` 等短名降低可读性 |
| F481 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:121 +1 处` | 两个不同函数都叫 `writeSet` |
| F482 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:30 +2 处` | Learning.kt 中局部变量 `learner` 遮蔽了同名工厂函数 |
| F483 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:55 +1 处` | TableRun 的 `Outcome` 由十个位置参数构成 |
| F484 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-dialog-sets.py:28 +8 处` | make-dialog-sets.py 复制常量并按路径加载模块 |
| F485 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-collision-set.py:105 +1 处` | make-collision-set.py 输出的表头与已提交文件不一致 |
| F486 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-collision-set.py:93 +1 处` | make-collision-set.py 不拒绝未知模式 |
| F487 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-collision-set.py:1` | 工具脚本有 shebang 却无可执行权限 |
| F488 | 1/18 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:22 +2 处` | voice 与 text flavor 的 VoiceFeature 签名不一致 |
| F489 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:133 +2 处` | youmo 补丁行超过 80 列，JNI 中重复 FindClass |
| F490 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:139 +1 处` | Offer、事件与标签的对应关系写了两遍 |
| F491 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:225 +1 处` | 在 const 方法中用 const_cast 调用 table() |
| F492 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:105 +7 处` | 同一个重抛-记录-回退代码块重复了七次 |
| F493 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:72 +1 处` | 历史音频长度未与它依赖的 VAD 上限关联 |
| F494 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:88 +1 处` | 识别器变量命名为 r |
| F495 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWindow.kt:331 +3 处` | 两个按住说话视图复制了相同的常量 |
| F496 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:12 +4 处` | voice 包内若干格式与其他文件不一致 |
| F497 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720` | 变量名 string_cls2 不表意 |
| F498 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:171` | FcitxDaemon 方法的 KDoc 过时，且读 clients 时未持锁 |
| F499 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:139 +1 处` | .new-words 中的名称读取时 trim、写入时不 trim |
| F500 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:101` | importFromInputStream 复制失败时残留临时文件 |
| F501 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:1004 +1 处` | ContextChars 与 ContextMemory.KEEP 重复定义 |
| F502 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:209` | 滑动手势的注释与代码不一致，阈值变量名互换 |
| F503 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardEntryUi.kt:48` | 置顶图标 setAlpha 作用对象易误读，setTint 未 mutate |
| F504 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/dialog/InputMethodListAdapter.kt:32` | 未使用的 setEnabled 可能通知位置 -1 |
| F505 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/dialog/SingleDividerDecoration.kt:36` | SingleDividerDecoration 每帧都解析主题属性 |
| F506 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/editing/InputConnectionEditor.kt:103` | companion object 夹在 override 方法之间 |
| F507 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:26 +1 处` | 测试中 getResourceAsStream 打开的流从未关闭 |
| F508 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionaryTypeTest.kt:37 +2 处` | 测试注释说只有文本词典有禁用形式，实际词包也有 |
| F509 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/AddonSubconfig.kt:8 +4 处` | reloadPinyinDict 等函数名暗示局部重载，实际重载全部 |
| F510 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:49 +1 处` | 循环中不断重新赋值的 var x 被惰性 filter 读取 |
| F511 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:291 +3 处` | AppPrefs 与 ThemePrefs 中若干处格式与文件其余部分不一致 |
| F512 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:94 +2 处` | PickerWindow 的 else 分支两次检查 CommitAction |
| F513 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:159 +3 处` | T9Keyboard 通过扫描子视图查找 BaseKeyboard 的行 |
| F514 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt:116` | 日志写「Skip attaching」但代码并未跳过 |
| F515 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:68 +2 处` | 长按编辑页未调用 super，标签与无障碍反馈缺失 |
| F516 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:182 +3 处` | 嵌套 Pair/Triple 代替具名类型 |
| F517 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:247 +5 处` | SAF 读写与错误处理被多处复制粘贴 |
| F518 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/KeyCaps.kt:65 +3 处` | 新 UI 代码未使用本包的视图 DSL |
| F519 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:127` | 编辑对话框标题未显示所编辑的卡片 |
| F520 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:196` | 每次绑定都遍历整个标点列表计数 |
| F521 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:192 +5 处` | ViewHolder 不持有视图字段，每次绑定都 findViewById |
| F522 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:79 +1 处` | `object Nothing` 遮蔽 kotlin.Nothing |
| F523 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/Ini.kt:117 +1 处` | IniTest 固化了无 key 时 set 重载的不对称行为 |
| F524 | 1/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml:1` | 圆形自适应图标与普通图标内容完全相同 |
| F525 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164 +2 处` | 工具指纹每次构建至少计算两次 |
| F526 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ToolFingerprint.kt:21` | 后续 jar 打开失败时已打开的 jar 不会关闭 |
| F527 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:133` | 构建期 hotword 过滤与运行时规则重复且无测试约束 |
| F528 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43` | 仅 app 使用的 flavor 目录硬编码在通用 detekt 插件中 |
| F529 | 1/6 | ✅ 已确认 | 仍存在✓ | `settings.gradle.kts:19 +2 处` | 改名后仍保留上游的项目名和缓存名 |
| F530 | 1/6 | ✅ 已确认 | 仍存在✓ | `.gitignore:60 +1 处` | .gitignore 没有忽略 keystore 文件 |
| F531 | 1/6 | ✅ 已确认 | 仍存在✓ | `config/detekt/baseline-lib-ime-core.xml:7` | baseline-lib-ime-core.xml 条目 ID 内嵌行注释 |
| F532 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/engine-data.sh:184 +1 处` | 续跑时才设置 EVAL 不会输出 probe.tsv.zst |
| F533 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/JsonStrings.kt:61` | JsonStrings 接受带正负号的畸形 \u 转义 |
| F535 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:348` | clean -o 指向输入目录时会就地覆盖原始分片 |
| F536 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Mixer.kt:72 +1 处` | 部分错误逃出 runCli，以裸堆栈结束 |
| F537 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:192 +1 处` | MainTest 对 lexicon 拒绝只检查退出码 |
| F538 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:274 +1 处` | MainTest 精确比对 ime-core 的完整错误文案 |
| F539 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Mixer.kt:88` | Mixer 在内存峰值时保留重复的概率列 |
| F541 | 1/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/latin_counts.py:41` | latin_counts.py 的工作进程数写死为 6 |
| F542 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:46` | 一音一字检查按 UTF-16 代码单元计数 |
| F543 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/KeyboardTest.kt:156` | KeyboardTest“不重复提交”检查未触达被测守卫 |
| F544 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100 +1 处` | SharedLexiconTest 忽略 delete() 的返回值 |
| F545 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:22 +2 处` | 码表注册信息分散在三个列表中 |
| F546 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:200` | toMatches依赖有序遍历却未在参数类型上约束 |
| F547 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:569 +3 处` | 拟离开当前页时测试循环会挂起而非失败 |
| F548 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:159 +1 处` | 笔画测试从同一对象读回learning，未验证传递给拼音 |
| F549 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:37 +2 处` | 笔画测试中𠀀条目的用途没有说明 |
| F550 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:58` | 辅助函数shown会写入它正在检查的存储 |
| F551 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:394` | 「整句上屏不起新词组」测试只覆盖全新会话 |
| F552 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:309` | 「自动词组长到最长编码」未被检查 |
| F553 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:427 +2 处` | FakePinyin的注释错位，picked标志从不重置 |
| F554 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Matrix.kt:11` | Matrix用四个可空字段并以q != null区分两种形态 |
| F555 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:213 +6 处` | 编码与文本用制表符拼接成字符串键（基本类型偏执） |
| F556 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:262 +1 处` | ChineseNumbers 对小时为十的「X点五十」处理不一致 |
| F557 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:58` | LibimeImportTest 的注释样例暗示了并不存在的注释规则 |
| F558 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:117` | LibimeImportTest 每个测试都重建 polyphones |
| F559 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:26 +2 处` | UserModel 承担职责过多，总量重算逻辑重复 |
| F560 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:105 +2 处` | ChineseNumbers 中的命名与布尔标志难以理解 |
| F561 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:248` | consumeSwipe 按轴分支读写平行的 X/Y 字段 |
| F562 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:25 +2 处` | ContextMemory 的 LRU 注释与代码不符 |
| F563 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:397 +1 处` | `ksc` 与 `learn` 的会话计算了无人读取的预测 |
| F564 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Tuning.kt:85` | `tune` 的网格搜索只用单线程 |
| F565 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/run-on-device.sh:16 +1 处` | run-on-device.sh 每个集合都重装两个 APK |
| F566 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:88 +2 处` | prev2/prev 拆分逻辑重复了多份 |
| F567 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-mixed-set.py:42` | 读音逻辑在三个工具中重复 |
| F568 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-chat-set.py:156` | make-chat-set.py 无参数运行时抛 IndexError |
| F569 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:333 +1 处` | EngineEvent::Other 不告诉会话按下的是哪个键 |
| F570 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:383` | 服务端预编辑没有设置光标 |
| F571 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:230 +2 处` | 屏蔽短语沿用热词语法解析 |
| F572 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:211 +1 处` | 码表输入法名称保存在两个平行数组中 |
| F573 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/matrix-kernel.cpp:38 +2 处` | fourRows 函数名硬编码了 Rows 常量的值 |
| F574 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:66` | 迁移 replace 写入或改名失败时残留 .new 文件 |
| F575 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:194 +1 处` | 每个事件都从索引 0 取候选，代价随翻页深度增长 |
| F576 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:68` | 导出的备份包含被标记为 sensitive 的剪贴板记录 |
| F577 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:55` | 选择当前显示的最近档位，不会写入该档位的值 |
| F578 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:45 +1 处` | 编辑器合并时，被编辑的短语被移到文件末尾 |
| F579 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:29` | .conf 无法解析的输入法被静默丢弃且没有日志 |
| F580 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PaginationUi.kt:37 +2 处` | 悬浮候选栏的翻页图标缺少无障碍标签 |
| F581 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/editorinfo/EditorInfoParser.kt:46` | EditorInfo 检查器未按类别过滤输入类型标志 |
| F582 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardAdapter.kt:62` | excerptText 扫描超出摘录范围，脱敏输出丢失换行 |
| F583 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/editing/EditorInfoTraits.kt:25` | privateImeOptions 用完全相等来比较 |
| F584 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceCategory.kt:23 +1 处` | 后面没有偏好项的 section 没有测试，且会被静默丢弃 |
| F585 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:237 +1 处` | 服务启动时 subtype 被重复同步多次 |
| F586 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/InputMethodNames.kt:17 +1 处` | 输入法 id 以字符串字面量重复书写 |
| F587 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69 +1 处` | Engines 构造与 Additions 使用一长串同类型的位置参数 |
| F588 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:187 +1 处` | Display 与 IdleUi.State 两个枚举一一对应 |
| F589 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:85 +4 处` | 词典存在性检查与 BOM 剥离逻辑在多处重复 |
| F590 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:430 +1 处` | vivo 兼容路径中 MotionEvent.obtain 未回收 |
| F591 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:182` | 通用手势视图通过隐藏通道写入 BaseInputView |
| F592 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132 +2 处` | Text↔T9 别名逻辑分散在三处 |
| F593 | 1/9 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:160 +2 处` | swipeText 用三态 String? 表达语义 |
| F594 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:236` | 九键空格标签与 TextKeyboard 不一致 |
| F595 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:34 +4 处` | 面板模式有两个真值来源且到处分支 |
| F596 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:22` | 许可页在主线程读取并解析 aboutlibraries JSON |
| F597 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/DialogSeekBarPreference.kt:58 +1 处` | DialogSeekBarPreference 的 step 未钳制到 1 |
| F598 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:353-354 +1 处` | 多个进度通知共用 ID 0，取消时会互相影响 |
| F599 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:158` | 已存储的 AutoPhraseLength=1 显示为「关闭」 |
| F600 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:215-221` | 嵌套的 `Section` 会被拍平到顶层 |
| F601 | 1/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values-zh-rCN/strings.xml:383 +1 处` | zh-rCN 首页分类与其中条目都叫“词库” |
| F602 | 1/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/values-es/strings.xml:114` | es 将备份译为“Exportar datos de uso”（使用数据） |
| F603 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/NativeBuildTasks.kt:50` | CMake install 任务每次构建都会运行 |
| F604 | 1/12 | ✅ 已确认 | 仍存在✓ | `.github/workflows/pull_request.yml:3 +1 处` | pull_request.yml 与 nix.yml 不取消被新推送取代的运行 |
| F605 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:206` | `ENGINE_DATA` 只标识了语言模型那次发布 |
| F606 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:233` | 大文件下载时没有任何提示输出 |
| F607 | 1/6 | ✅ 已确认 | 仍存在✓ | `lexicon/readings.tsv:246` | readings.tsv 给错别字 神祗 加了常用读音 |
| F608 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:25` | NewWords 生产用的长度 4 从未被断言 |
| F609 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:130-198` | MainTest 一个测试覆盖约 12 个场景 |
| F610 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:70` | WebTextTest 的顺序测试不可能失败 |
| F611 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:313 +1 处` | forEachAfter 的二元/三元词 id 未对词表大小校验 |
| F612 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:135 +1 处` | LibimeFiles 写出码表编码时未转义 |
| F613 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoderTest.kt:285` | PinyinDecoderTest 精确断言打分调用次数 |
| F614 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/LatinWords.kt:24` | LatinWords每次按键为每个探测长度分配子串 |
| F615 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:599` | 停顿时的精排切片可能重排九键音节列表 |
| F616 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:83 +1 处` | 非字母键提交笔画查询结果时未把该字告知拼音 |
| F617 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/PhraseRules.kt:50` | PhraseRules的used去重范围超出注释所述 |
| F618 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableDictionary.kt:38` | TableDictionary中「只有词组和拼音查询需要」的注释已失效 |
| F619 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:35 +1 处` | 九键测试名称承诺了abbreviations情形却从未测试 |
| F620 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:135` | 九键测试在注释给出确切结果处用了弱断言 |
| F621 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:85` | 压缩测试从不检查压缩是否发生，断言失败时存储不关闭 |
| F622 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:53 +1 处` | 每次按键都线性扫描所有已存词组 |
| F623 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:81 +1 处` | TableConf是第三个INI读取器 |
| F624 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:56` | 非 BMP 字母后输入 ASCII 引号时只读到半个字符 |
| F625 | 1/18 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:55 +3 处` | UserScorerTest 用默认 modelWords = 0 列出模型词 |
| F626 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/testFixtures/kotlin/org/fcitx/fcitx5/android/input/editing/FakeEditor.kt:33` | FakeEditor 在 isAvailable = false 时仍修改缓冲区 |
| F627 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:161` | forEachCount 用装箱 Pair 缓存所有词对 |
| F628 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:112` | KeyHabits 一次超出多条时的裁剪分支从未执行 |
| F629 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:251 +4 处` | ChineseNumbers 规则函数存在数据泥团与双重否定 |
| F630 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/AudioHistory.kt:12` | AudioHistory 没有校验 capacity |
| F631 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/release-report.sh:65` | release-report.sh 的 sed 模式依赖 GNU sed |
| F632 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/release-report.sh:36` | SMALL 路径含空格或 `:` 时解析出错 |
| F633 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:113` | TableRun 的 `commitsOnTo` 结果未缓存 |
| F634 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ParallelTest.kt:31` | ParallelTest 断言了内部分发顺序 |
| F635 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/androidengine/androidengine.cpp:117` | 没有可用操作的长按也会取消本次 refine |
| F636 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:32 +3 处` | OpenCC、Marisa 查找未设 REQUIRED |
| F637 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:248 +1 处` | 未捕获 AudioRecord 构造时的 SecurityException |
| F638 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/androidTestVoice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngineTest.kt:125 +1 处` | VoiceEngineTest 固定了发给模型的具体文本 |
| F639 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/host-eval.sh:16 +1 处` | host-eval.sh 中关于解码器的注释放在了构建命令上方 |
| F640 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:26` | libime 配置迁移原地单向改写，不保留原文件 |
| F641 | 1/6 | ❓ 无法验证 | 无法验证 | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:76` | 删光所有参数后，链接末尾残留 ? 或 # |
| F642 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:110` | 导入旧版偏好时忽略 renameTo 的返回值 |
| F643 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:62` | 解开重定向后，目标链接自身的跟踪参数没有被清理 |
| F644 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/CustomQuickPhrase.kt:53 +2 处` | CustomQuickPhrase 启用/禁用时忽略 renameTo 的结果 |
| F645 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:62` | 每次读取时重新定位绝对路径，而没有改存相对文件名 |
| F646 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:75` | 列表时重写主题文件会打乱按修改时间的排序 |
| F647 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:129` | take(42) 可能切断代理对，建议里出现半个 emoji |
| F648 | 1/6 | ❓ 无法验证 | 无法验证 | `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/GridPagingCandidateViewAdapter.kt:37` | measureWidth 用 getItem() 可能提前触发分页加载 |
| F649 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/input/editing/InputEditorContractTest.kt:312` | 契约测试手工复制了私有的 composingOverhang 公式 |
| F650 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/input/picker/DefaultPickerPolicyTest.kt:19` | 四行的常量策略配了五个测试 |
| F651 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/core/RawConfigTest.kt:186` | RawConfigTest 的部分用例只验证 Kotlin 语义 |
| F652 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:845 +1 处` | FcitxInputMethodService 已是大类，且继续膨胀 |
| F654 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:31 +3 处` | 新增注释的风格与周围上游代码不一致，且难以理解 |
| F655 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/SpaceLongPressBehavior.kt:16 +4 处` | 新增注释措辞难懂且引用不存在的文件 |
| F656 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:162` | 最后一个类别为空时会高亮最近标签 |
| F657 | 1/12 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/AboutFragment.kt:42 +1 处` | 提交哈希为 N/A 时关于页链接失效 |
| F658 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:118` | 「首选」复选框不反映卡片当前位置 |
| F659 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:44-58` | 手工列出的搜索条目复制他页标题且无测试关联 |
| F660 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ResponsiveThemeListView.kt:32` | 主题网格宽度变化后间距装饰仍用旧列数 |
| F661 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/utils/NostalgicSerializer.kt:24` | NostalgicSerializer 用默认 `Json` 而非调用方配置解码 |
| F662 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:17` | ContentResolverTest 只在 minSdk 上运行 |
| F663 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:92 +2 处` | 仅为计数而从引擎取出整个用户词库 |
| F664 | 1/5 | ❓ 无法验证 | 无法验证 | `app/src/debug/java/org/fcitx/fcitx5/android/input/TypingHostActivity.kt:36` | 调试用 TypingHostActivity 的监听器吞掉回车键事件 |
| F665 | 1/5 | ✅ 已确认 | 仍存在✓ | `app/src/main/res/drawable/ic_launcher_background.xml:11` | 使用 API 24 渐变的启动图标背景放在无限定 drawable/ 中 |
| F666 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:31 +1 处` | `BUILD_TIME` 每次都变，无改动的构建也不是 up to date |
| F667 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:126 +1 处` | voice 模型在 build/ 下存了两份 |
| F669 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/ToolFingerprint.kt:1-80` | 字节码可达性指纹过于敏感，可改用独立模块 |
| F670 | 1/12 | ❓ 无法验证 | 无法验证 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:178 +5 处` | 显式设置的输出路径可能被 AGP 覆盖 |
| F671 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:104 +1 处` | CopyVoiceModels 等任务缺少缓存注解 |
| F672 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:126 +2 处` | 解压任务的 CMake 路径忽略 `cmake.dir` |
| F673 | 1/6 | ✅ 已确认 | 仍存在✓ | `build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:67 +1 处` | BuildMetadataTask 用弃用 API 且时间戳不一致 |
| F674 | 1/6 | ✅ 已确认 | 仍存在✓ | `lexicon/tools/latin.py:57` | latin.py 跨拼写累加页数，同页被重复计数 |
| F675 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:651 +2 处` | mix 对 Common Crawl 分片也只取聊天类页面，与文档不符 |
| F676 | 1/12 | ✅ 已确认 | 仍存在✓ | `.github/workflows/unit_test.yml:80` | :lib:ime-dict-tool 没有覆盖率报告或下限 |
| F677 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:149 +1 处` | WebTextTest 夹具的 date 类型与 WebText 分片不同 |
| F678 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Examples.kt:49` | Examples 每个字符做装箱 Long 的查找 |
| F679 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ArpaModel.kt:105` | ArpaModel 写每个数都调用 String.format |
| F680 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:41` | WebText.accepts() 逐字线性扫描三个字符串 |
| F681 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:331` | backoff(prev, word) 把 NO_WORD 当作 <unk> |
| F682 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/StringTable.kt:43` | StringTable.startsWith 有分配，与类文档不符 |
| F683 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/BitPacked.kt:18` | BitPacked 公共构造函数依赖调用方的字节序 |
| F684 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:57 +1 处` | 放在第 0 层的词包词被当作“无词包” |
| F685 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:389` | firstWords 每键都对起点所有 arc 重新打分 |
| F686 | 1/12 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:499 +2 处` | 测试名称承诺的leave()余量上屏路径从未被执行 |
| F687 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionMisreadingTest.kt:48 +1 处` | 误读测试中「无提示」的断言可能什么都没测 |
| F688 | 1/9 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:63 +1 处` | 每个会话重复持有只读缓存，空闲时也保留 |
| F689 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ForwardedKeys.kt:59` | ForwardedKeys 用 xor 求释放的修饰位，会带上新置位 |
| F690 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearch.kt:37` | SettingsSearch 每次查询都重新小写并拼接全部条目文本 |
| F691 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:645` | 线程检查守护测试按反射数公共方法个数，过于脆弱 |
| F692 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/ime-eval/tools/make-chat-set.py:33` | make-chat-set.py 单字全局读音导致部分样本标错 |
| F693 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/cpp/native-lib.cpp:821` | androidengine addon 缺失时此处没有日志 |
| F694 | 1/6 | ❓ 无法验证 | 无法验证 | `lib/fcitx5/src/main/cpp/cmake/HookAddCustomCommand.cmake:8` | HookAddCustomCommand 遇无 COMMAND 调用会配置失败 |
| F695 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:108 +1 处` | NoModel 路径的注释夸大了它能捕获的情况 |
| F696 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:49` | letGo 在主线程释放原生模型 |
| F697 | 1/6 | ✅ 已确认 | 仍存在✓ | `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:219` | CreateStream(hotwords, blocked) 解析无主机测试 |
| F699 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:72` | 导出时 fcitx 线程可能正在追加或压缩引擎日志 |
| F700 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:166` | setIntoNew 改名失败时残留 .new-words.tmp |
| F701 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:86 +2 处` | 码表文件以词典文件名而不是输入法名命名 |
| F702 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/AutoScaleTextView.kt:202` | getBaseline() 重复计入 paddingTop |
| F703 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:459` | getResources() 缓存字段跨线程读写且未加 @Volatile |
| F704 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754` | 引号修复重置 fcitx 全部状态，丢掉上下文后不重新告知 |
| F706 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:65` | ClearURLsTest 以相对于工作目录的路径读取规则文件 |
| F707 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:40` | 每个 StatusAreaEvent 都经 JNI 重读并重新解析标点映射 |
| F708 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:342 +1 处` | 长按列表清空后滑动预览仍显示字母 |
| F709 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176` | 按住说话的密码框保护没有测试 |
| F710 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:83` | 九键占位键仍为可用且可点击状态 |
| F711 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:172` | 覆盖字母的键角在下次标点广播前显示内置符号 |
| F712 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:159` | 肤色变换在每次绑定时重复计算 |
| F713 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/input/status/StatusAreaWindow.kt:129 +1 处` | 移除「重新加载配置」后无快捷方式应用文件修改 |
| F714 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:129` | 应用语言摘要可能不更新 |
| F715 | 1/6 | ✅ 已确认 | 仍存在✓ | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:114-119 +1 处` | 用户词导入导出失败只弹 toast，不写日志 |
| F733 | 5/7 | ✅ 已确认 | 新增 | `app/licenses/libraries/sentence-models.json:3 +6 处` | 许可条目与 README 只写旧的句子模型发布 |
| F734 | 4/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:139 +2 处` | T9 模型文件存在但不可读时不回退到通用模型 |
| F735 | 3/7 | ✅ 已确认 | 同 F55 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:138 +3 处` | T9 回退到通用模型时没有任何日志或信号 |
| F736 | 2/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110` | EngineDataPlugin 中 T9 模型注释语句不通 |
| F737 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:137` | 缺文件分支的注释已过时 |
| F738 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:831 +1 处` | reranker 的 KDoc 仍写「over the one model」 |
| F739 | 1/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:105 +2 处` | MODELS 用嵌套 Pair 而非具名类型 |
| F740 | 1/7 | ✅ 已确认 | 新增 | `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:102 +2 处` | RELEASES 前缀与 LM_URL、WORDS_URL 写法不一致 |
| F741 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20` | SPELLED 新正则不再把下划线和带重音字母当词字符 |
| F742 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103` | 存在 T9 文件时未检查双拼不读 T9 模型对 |
| F743 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:164 +2 处` | 成对的句子模型以四个零散字段传递（Data Clump） |
| F744 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20` | VoiceText 中字符类 [A-Za-z0-9] 写了两遍 |
| F745 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:50 +2 处` | SPELLED 修复只靠 JDK 17 的 CI 守护，ASCII 边界无测试 |
| F746 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:155 +1 处` | T9 模型文件损坏时的行为没有测试 |
| F747 | 1/7 | ✅ 已确认 | 新增 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:540` | 首次切到九键时在 fcitx 线程上加载 T9 小模型 |

## 4. 详细问题

### 4.1 MUST FIX（必须修）

#### F1 [MUST FIX] Auto Backup 将学习词和剪贴板历史上传云端

- 来源：第一轮（整仓 @07d2778） · 共识：5/20 位 reviewer（5 MUST FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/AndroidManifest.xml:28`、`data_extraction_rules.xml:3-16`、`EngineBridge.kt:69`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`app/src/main/AndroidManifest.xml:28-30`、`app/src/main/res/xml/data_extraction_rules.xml:3-16`、`EngineBridge.kt:69`）
- 用户决定：关闭整个云备份（`android:allowBackup="false"`），而不是只把用户数据排除在备份之外。修复尚未开始。
- 位置：`app/src/main/AndroidManifest.xml:28`；另见 `app/src/main/res/xml/data_extraction_rules.xml:3-16`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69`、`app/src/main/res/xml/data_extraction_rules.xml:3`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:198`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:102`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:930`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:59`

AndroidManifest.xml 设置了 `android:allowBackup="true"`，而 data_extraction_rules.xml 的 `<cloud-backup>` 只排除 `usr`、`descriptor.json`、`licenses.json`、`README.md` 等资产副本，full_backup_content.xml（API ≤30）同样如此。
因此 Google 云备份会上传 `files/engine`（用户词库、输入日志和词频）、`databases/clbdb`（剪贴板历史，可能含密码和验证码）、shared prefs 以及 `getExternalFilesDir`（自定义短语、词典、主题）；CORRECTNESS-2@S46 还指出这些数据在卸载后仍保留并会在重装时恢复。
这与 PRIVACY.md 中数据只留在手机上、仅在用户自行导出时才写出的承诺相矛盾。
修复：设置 `allowBackup="false"`，或在 `<cloud-backup>` 和 full_backup_content.xml 中排除所有用户数据（`engine`、`clbdb` 及其 `-wal`/`-shm`、`sharedpref`、`external`）；README 所述的应用内导出仍是迁移途径。评审认为 device-transfer 可保留或需明确决定（API 31+ 上 allowBackup 不阻止设备间迁移）；CORRECTNESS-1@S46 另提出可改为在 PRIVACY.md 中披露 Android 备份。
PERFORMANCE@G9 补充：可重建的用户码表 `engine/<USER_TABLES>/<im>.table` 计入 25 MB 云配额，超额后该应用的备份（包括设置）全部不再进行。

- 提出者：CORRECTNESS-1@S46（MUST FIX，8/10）、CORRECTNESS-2@S46（MUST FIX，8/10）、GENERIC-1@S46（MUST FIX，8/10）、PERFORMANCE@G9（MUST FIX，8/10）、STYLE@G9（MUST FIX，7/10）

#### F2 [MUST FIX] LCCC 派生评测集与 README「不使用仅限科研数据」矛盾

- 来源：第一轮（整仓 @07d2778） · 共识：4/27 位 reviewer（1 MUST FIX, 2 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`README.md:174`、`lib/ime-eval/tools/make-chat-set.py:5-6`、`make-dialog-sets.py:5-6`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 用户决定：降为文档问题。发布的权重和词库都不依赖仅限科研用途的数据，这些数据只用于评测；只修改 README 那句话的表述。见下方复核。
- 位置：`README.md:174`；另见 `lib/ime-eval/tools/make-chat-set.py:5`、`lib/ime-eval/tools/make-chat-set.py:6`、`lib/ime-eval/tools/make-chat-set.py:56`、`lib/ime-eval/tools/make-dialog-sets.py:5`、`lib/ime-eval/tools/make-dialog-sets.py:6`、`lib/ime-eval/tools/make-dialog-sets.py:36`、`lib/ime-dict-tool/engine-data.sh:12`、`lib/ime-eval/release-report.sh:32`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:212-216`

lib/ime-eval/tools/make-chat-set.py 与 make-dialog-sets.py 从 LCCC 生成并提交了 data/pinyin-chat.tsv、pinyin-dialog.tsv 和 predict/chat.tsv，工具自己的 docstring 称 LCCC 为 research use，而 README.md:174 写的是未加限定的「No research-only data is used」。
这些集合不只用于测量：release-report.sh 以 chat/dialog 加权做发布门禁；GENERIC-1@S24 指出 engine-data.sh 的 `evaluate` 用它们给所发布 LM 的混合权重打分，CORRECTNESS-1@S24 指出所发布的 `Predictor.USER_WEIGHT = 0.5f` 是在 chat 集上用 `predict-learn` 选出的，因此工具中「nothing the app ships learns from LCCC」不准确。
严重度不一：GENERIC-1@S24 为 MUST FIX，CORRECTNESS-1@S24 与 CORRECTNESS-2@S24 为 SHOULD FIX，STYLE 为 NIT（认为取决于 README 该句的适用范围）。
修复意见不同：GENERIC-1@S24 建议换成 FineWeb/CC 等非 LCCC 留出集，CORRECTNESS-1@S24 建议在非 LCCC 集（如 pinyin-web.tsv）上重选权重，二者也接受如实收窄 README；CORRECTNESS-2@S24 只改 README，注明 LCCC 仅用于测量并列入 Data and licences；STYLE 建议按工具 docstring 的说法限定 README 该句。

- 作者质疑后的复核（verifier 在 5881d31 上逐条核对，原文翻译）：
  - 假设：被审查的修订版本是 5881d31e。`git log` 显示它是 HEAD，且工作树是干净的。上下文文件写的是 07d2778，比它早两个提交。下文所有行号均以 5881d31 为准。
  - F2 Verified: yes README.md:174 写着 "No research-only data is used."，但 `git ls-files` 列出了源自 LCCC 的数据集 lib/ime-eval/data/pinyin-chat.tsv、pinyin-dialog.tsv 和 predict/chat.tsv。这些数据集上的分数影响了所发布 LM 混合权重的选择（lib/ime-dict-tool/engine-data.sh:32,130-132,141；提交 c39d4ad1），也影响了 Predictor.USER_WEIGHT 的选择（Predictor.kt:212-217）。作者那个更窄的论点也成立：所发布的任何东西都不是用 LCCC 训练或构建的（见 F2.e）。
  - F2.a Verified: yes README.md:174 "- No research-only data is used." 是 "### Data and licences"（:163）下的最后一条。它前面一条写着 "- Sentence models: this project's own (Apache-2.0). A language model is first trained on FineWeb-2's Chinese pages, then distilled from Qwen3.5-9B-Base's (Apache-2.0) scores of candidate lists."（:172）和 "The files are published at [sentence-models-20261001](…)"（:173）。下一个标题是 "### Building"（:176）。这句话本身没有任何限定语；只有它所处的位置（一个讲所发布组件的小节）限定了它的范围。README.md:58 的中文版写的是 "不使用任何仅限科研用途的数据。" README 从未提到 LCCC，但它的 "| Chat | 69.5 | 73.3 |" 这一行（:149，以及 :34）是在 pinyin-chat.tsv 上测得的（提交 41df14a6："chat 69.5 -> 73.3"）。
  - F2.b Verified: yes `git ls-files lib/ime-eval/data` 包含 pinyin-chat.tsv、pinyin-dialog.tsv 和 predict/chat.tsv。它们的文件头（pinyin-chat.tsv:2-6、pinyin-dialog.tsv:2-5、predict/chat.tsv:2-5）注明来源为 "LCCC, https://github.com/thu-coai/CDial-GPT"。生成脚本的 docstring 写道：make-chat-set.py:5-6 "Makes data/pinyin-chat.tsv from LCCC-base's validation split (MIT; its README asks for research use, so nothing the app ships learns from LCCC)"，make-dialog-sets.py:5-6 "Makes two sets from LCCC-base's validation split (MIT; research use, so only measured with, nothing the app ships learns from it)"。这些工具写出的文件头重复了这一点：make-chat-set.py:56 "its README asks for research use"，make-dialog-sets.py:36 "MIT, research use: nothing the app ships learns from it"。
  - F2.c Verified: yes 在 engine-data.sh 中，evaluate()（:127-142）给 chat=pinyin-chat.tsv 的权重是 0.35，给 dialog=pinyin-dialog.tsv 的权重是 0.20，所以它的 "weighted=" 分数（:141）有 55% 来自源自 LCCC 的数据集。weight()（:151-156）按 WEIGHTS 中的每个值混合 LM，并写出 out/weights.txt，"to choose the next release's weight by"（:24-25）。
  - 这是一个离线步骤，不属于应用构建。engine-data.sh:4-5 写着 "a release-time job … never in an app build"，EngineDataPlugin.kt:46-49 写着 "no part of a build"。Gradle 只下载固定版本的结果：LM_RELEASE "engine-data-20261005-3" 及其 SHA-256（EngineDataPlugin.kt:88-90，在 :175 使用）。
  - 没有代码来选定权重。由人阅读 weights.txt，再设置默认值 `WEIGHT=${WEIGHT:-0.6}`（engine-data.sh:41）。
  - 目前发布的值就是这样选出来的。engine-data.sh:30-32 的注释写着 "mixed at 0.6 … (0.4 and 0.8 did no better)"。提交 c39d4ad1 写着 "at 0.6: the model alone scores 77.5 weighted …; 0.4 did as well and 0.8 a little worse"。在该提交中，evaluate() 的权重同样是 chat 0.35 / dialog 0.20（`git show c39d4ad1:lib/ime-dict-tool/engine-data.sh`:128-130）。
  - 该分数排除了 0.8，但 0.6 与 0.4 打平。0.6 本身来自更早的 00792165（"at weight 0.6 in place of LCCC's"）。
  - 只有这条注释和默认值表明发布版 -3 是以 0.6 混合的。它的 manifest.txt（:160）在发布包中，不在仓库中。
  - F2.d Verified: yes Predictor.kt:212-216 的 KDoc 写着 "Under `ime-eval predict-learn` on the chat set, the half typed once then predicted again: top1 13.0% learning nothing, 19.6 / 30.9 / 34.1 / 36.4% at 0.1 / 0.3 / 0.5 / 1; the other half … 13.0 / 12.7 / 12.6 / 12.5%"。第 :217 行是 `const val USER_WEIGHT = 0.5f`。这只记录在注释中；没有代码来选定该值。ime-eval Main.kt:141-142 只允许单次运行用 `--user-weight` 覆盖它。注释没有写明文件名，但仓库中的 chat 数据集是 pinyin-chat.tsv（lib/ime-eval/build.gradle.kts:20、release-report.sh:32）。提交 29a79831 写着 "half the chat set typed once, then predicted again"。
  - F2.e Verified: yes，对仓库构建的所有内容而言成立。所发布的 LM 由 mix()（engine-data.sh:98）从 lm_sc.arpa、WORDS 包、FineWeb-2 parquet 文件和清洗过的 CommonCrawl parquet 文件混合而成，别无其他来源。APK 中的 pinyin.data 和码表由该 LM、libime 的词典、words-202610 包和 lexicon/ 下的文件编译而成（EngineDataPlugin.kt:172-185）。没有任何 main source set 读取 lib/ime-eval/data；它只进入 androidTest APK 的 assets（app/build.gradle.kts:98-99）。源自 LCCC 的数据只被评测、发布把关和调参步骤读取。有两点限制：
  - 句子模型的训练代码不在仓库中（没有 dev/ 目录），所以 "trained on FineWeb-2, scored by Qwen" 只有注释作为依据（EngineDataPlugin.kt:54、app/licenses/libraries/sentence-models.json）。
  - 作者所说的 "evaluation only (measurement and release gating)" 比代码显示的范围更窄：所发布的混合权重和 USER_WEIGHT 是用源自 LCCC 的分数调出来的（F2.c、F2.d）。
  - 验证者备注
  - 用源自 LCCC 的数据训练或构建、且目前仍在发布的内容：仓库中没有。早期历史中有过：提交 2bf7dd40 曾把 LCCC 混入 LM，f0519973 之前的句子模型是用 C4 和 LCCC 训练的。提交 f0519973（2026-09-30，"Ship nothing that learned from LCCC"）移除了这两者，并一同移除了 app/licenses/libraries/lccc.json。
  - 用源自 LCCC 的分数选定的已发布参数：LM 混合权重 0.6（F2.c）和 Predictor.USER_WEIGHT 0.5（F2.d）。另有两项已发布的决定也部分依赖这些分数。预测结果不经句子模型重排序（PredictRun.kt:20-23，测量于 "this set and on `predict-set`'s of the chat set"）。九键模型在 HEAD 5881d31 被采用，依据的是五个数据集中 chat 和 dialog 的分数（见提交信息）。
  - 发布把关：release-report.sh:32 对 chat 以 0.20、对 dialog 以 0.15 把关，:68-79 用 pinyin-chat.tsv 上的 `learn` 为用户层把关。engine-data.sh 的 report.txt 分数有 55% 来自 chat 加 dialog（:130-132）。
  - 仅用于评测：README 的准确率行（:34、:149）、EngineEvalRunner 使用的 androidTest assets（app/build.gradle.kts:98-99），以及断言该文件存在的 EvalSetTest.kt:70。文档中的提及：lib/ime-eval/build.gradle.kts:20 和 engine-data.sh:11-12。
  - 不读取任何 LCCC 数据的相关代码：make-mixed-set.py:23-25 只导入 make-chat-set.py 的加载函数。字典工具的 `mix` 仍接受 LCCC 格式的 `.jsonl.gz` 输入（dicttool/Main.kt:44-46 和 :655-662、JsonStrings.kt:8、lib/ime-dict-tool/build.gradle.kts:12），但 engine-data.sh:98 没有传入任何此类输入。
  - engine-data.sh:11-12 把 pinyin-chat 和 pinyin-dialog 称为 "the held-out ones of the training data"。生成脚本从 LCCC 的验证集划分中取得它们，而仓库中没有任何东西用 LCCC 训练，所以这是措辞不严谨，而不是实际使用。
  - 无法读取：发布的二进制文件及其 manifest.txt 文件，以及 dev/TRAINING-PLAN.md（第 11.7d 和 12.10 节，在 engine-data.sh:30 被引用）。
- 提出者：GENERIC-1@S24（MUST FIX，7/10）、CORRECTNESS-1@S24（SHOULD FIX，7/10）、CORRECTNESS-2@S24（SHOULD FIX，7/10）、STYLE@G5（NIT，6/10）

#### F3 [MUST FIX] 一次删除多个已学词时全部未被遗忘

- 来源：第一轮（整仓 @07d2778） · 共识：3/21 位 reviewer（3 MUST FIX）· 置信度 9/10 · 验证：✅ 已确认（`K/engine/user/UserModel.kt:141`、`K/engine/host/Engines.kt:348`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:356`、`UserModel.kt:141-148`、`UserModel.kt:294`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:348`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:248`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserLog.kt:91`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:141`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:294`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:97`

`removeWords` 把所有选中的 LEARNED 词一次性传给 `UserModel.forget`，而 `forget` 按单个多词候选处理，只在 `words.singleOrNull()` 非空时从 trie 和 `entries` 删除；选中两个及以上时只清零计数。
结果：设置页多选删除后，这些词仍出现在 `ownWords()` 列表中且仍可输入（GENERIC-1@S8 补充：仍经 `ownTexts()` 共享给码表），日志重放后重启依旧存在，直到日志压缩。
建议：逐词调用 `user().forget(listOf(it))`（同 `forgetText`），或新增一次处理并为每个词写一条单词 FORGOT 记录的 `UserModel` 方法；并补充删除多个 LEARNED 词的测试（`aThousandWordsAreAddedAndRemovedAtOnce` 只覆盖 ADDED）。

- 跨模块组合并：G3a-01、G4-02（设置中批量遗忘自学词后，词仍在列表中且可输入）
- 提出者：CORRECTNESS-2@S8（MUST FIX，9/10）、GENERIC-1@S8（MUST FIX，9/10）、CORRECTNESS-2@S13（MUST FIX，9/10）

#### F4 [MUST FIX] VoiceText 的 \b 缩写合并只在 JDK 19+ 生效

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 MUST FIX, 2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`VoiceText.kt:15`、`VoiceText.kt:18`、`unit_test.yml:55`）
- 当前状态（5881d31）：✅ 已修复（第二轮已验证）：已由 af16cd95 修复。lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20 现在为 `(?<![A-Za-z0-9])[A-Z](?: [A-Z](?![A-Za-z0-9]))+`，它使用显式的 ASCII 字符类，不使用 \b。经过 SPACE_BY_CJK 处理后，"订了C E P的票" 在任何 JDK 和 ICU 上都会匹配到 C E P，因此 VoiceHoldTest.kt:53 所期望的合并会发生。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:18`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:53`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:50`

`VoiceText.kt:18` 的 `SPELLED` 正则依赖 `\b`；JDK 18 及以前与 Android ICU 把汉字算作单词字符，「订了C E P的票」中 了 与 C 之间没有边界，缩写不会合并（CORRECTNESS-2@S22 注明 Android 行为未在设备上验证）。
唯一覆盖它的 `VoiceHoldTest.kt:53`（TESTING@G4 记为 :50）只在 JDK 19+ 通过，而 CI 的 `unit_test.yml` 与 `flake.nix` 固定 JDK 17，所以该测试在 CI 上失败；设备上语音输入会打出带空格的缩写。两个 CORRECTNESS 阶段用独立正则在 JDK 17/21 上复现了差异，TESTING@G4 核对了 JDK 17 的 `Pattern.Bound.isWord`。
建议改用 ASCII lookaround，如 `(?<![A-Za-z0-9])[A-Z](?: [A-Z](?![A-Za-z0-9]))+`（CORRECTNESS-2@S22 的写法还排除 `_`），并新增 `VoiceTextTest` 覆盖缩写位于开头、结尾、紧邻汉字（TESTING@G4 还要求覆盖纯标点→null 与「嗯。」→null）。
CORRECTNESS-2@S22 另建议用 Gradle toolchain 固定测试 JVM。严重度不一：CORRECTNESS-2@S22 为 MUST FIX，另两票为 SHOULD FIX。

- 提出者：CORRECTNESS-1@S22（SHOULD FIX，8/10）、CORRECTNESS-2@S22（MUST FIX，9/10）、TESTING@G4（SHOULD FIX，8/10）

#### F5 [MUST FIX] 内置 ClearURLs 默认开启且二次解码查询串，改坏复制的链接

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 MUST FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:76`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:76`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:74`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:83`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:122`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:60`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:96`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:426`

内置的 ClearURLs 默认开启（`clipboard_clear_urls` 默认 true），`globalRules` 的 `urlPattern` 为 `.*`，匹配所有 http(s) 剪贴内容，即使没有删除任何参数也会重建链接；此前只有安装了剪贴板插件的用户才会走这条路径。
`uri.query`/`uri.fragment` 已经解码，`UrlQuerySanitizer.parseQuery` 再解码一次并按 `&` 拆分，`stringify` 重新编码：含 `%26`、`%2B`、`%20`、嵌套 URL 或非 ASCII 值的链接会被改写成另一个含义，剪贴板建议随后粘贴的就是改坏的链接。
修复：基于 `encodedQuery`/`encodedFragment` 自行按 `&` 拆分，只解码 key 用于规则匹配，保留的参数原样写回；未删除任何参数时返回原文；补充 `%26`、`%2B`、非 ASCII 等测试。CORRECTNESS-1@S30 还指出 `decodeURL` 循环会完全解码重定向目标。

- 提出者：CORRECTNESS-1@S30（MUST FIX，8/10）、CORRECTNESS-2@S30（MUST FIX，8/10）、GENERIC-1@S30（MUST FIX，8/10）

#### F6 [MUST FIX] 主题导入信任 zip 内 JSON 的路径和名称，可在 theme/ 外写文件

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 MUST FIX, 2 SHOULD FIX PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:139`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:139`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:146`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:23`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:62`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:65`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:112`

`ThemeFilesManager` 导入主题时，`srcFilePath`（:139）、`croppedFilePath`（:146）和 `theme.name`（:23）都来自导入的 JSON，直接拼到 `dir` 上，接受 `../`。精心构造的主题 zip 因此可以在 app 可写的位置新建文件，例如 `files/engine/tables/*`、fcitx 的 `data/inputmethod/*.conf` 或存放码表 `.conf` 与 Lua 扩展的用户数据目录；`ZipStream.extract` 的 zip-slip 检查发生在解压阶段，管不到这一步复制。
CORRECTNESS-1@S32 指出 `copyTo` 以 `overwrite=false` 调用，只会新建、不会覆盖；CORRECTNESS-2@S32 指出 TableManager 使用文档提供方报告的文件名（:62、:65、:112）也有同样问题。
修复：只取 `File(path).name`（与 `listThemes` 的重定位一致），并拒绝含 `/` 或为 `.`、`..` 的主题名。严重度不一：GENERIC-1@S32 为 MUST FIX，另两票为 PRE-EXISTING SHOULD FIX。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S32（SHOULD FIX PRE-EXISTING，7/10）、CORRECTNESS-2@S32（SHOULD FIX PRE-EXISTING，8/10）、GENERIC-1@S32（MUST FIX，8/10）

#### F7 [MUST FIX] 文档提供者未规范化文档 ID，可越界读写私有数据

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 MUST FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:78`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:78`；另见 `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:179`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:180`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:145`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:207`

FcitxDataProvider.fileFromDocId（:78）用 File(docIdPrefix, docId) 构造路径而不规范化，isChildDocument（:179/:180）只是字符串前缀判断；被用户授予 Youmo 根目录树权限的应用可请求 files/../../… 形式的 ID，以输入法自身权限读写剪贴板数据库、偏好和引擎学习数据，违背 PRIVACY.md。
createDocument、renameDocument、copyDocument 也接受含 / 或 .. 的 displayName。代码继承自上游；CORRECTNESS-1 定为 PRE-EXISTING 的 SHOULD FIX，CORRECTNESS-2 和 GENERIC-1 定为 MUST FIX。
建议：fileFromDocId 取 canonicalFile 并要求位于 baseDir 下，isChildDocument 比较规范路径并带分隔符，拒绝含分隔符或 .. 的显示名。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S39（SHOULD FIX PRE-EXISTING，7/10）、CORRECTNESS-2@S39（MUST FIX，7/10）、GENERIC-1@S39（MUST FIX，7/10）

#### F8 [MUST FIX] 输入法设置页的 ListPreference 未设 key，点击即崩溃

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 MUST FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:245-260`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/MyPreferenceFragment.kt:15-17`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/MyListPreferenceDialogFragment.kt:29`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:245`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/MyPreferenceFragment.kt:15`

`InputMethodSettings.create()` 构建的每个 `ListPreference` 都设了 `isPersistent = false` 却从未设置 `key`；点击时 `MyPreferenceFragment.onDisplayPreferenceDialog`（`modified/MyPreferenceFragment.kt:15`）以 null key 调用 `MyListPreferenceDialogFragment.newInstance`，`PreferenceDialogFragmentCompat.onCreate` 中 `findPreference(null)` 抛出 IllegalArgumentException（或 ClassCastException）。
受影响的有双拼方案、每页候选数、两个码表词组选项及英文提示大小与修饰键，规格中的「双拼（8 种方案）」无法选择；应用中其他 ListPreference 都设了 key。
建议：设 `key = item.key`（每页唯一，仍不持久化），或像 `Flags` 一样自建 AlertDialog；设 key 后 MyListPreferenceDialogFragment 的「默认」按钮因无默认值而无效，应隐藏或设为 C++ 默认值。

- 提出者：CORRECTNESS-2@S43（MUST FIX，8/10）

### 4.2 SHOULD FIX（应该修）

#### F9 [SHOULD FIX] `--beam` 等新选项未经 `optionsValid` 校验

- 来源：第一轮（整仓 @07d2778） · 共识：8/9 位 reviewer（5 SHOULD FIX, 3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:158-170`、`MainTest.kt:25-49`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:158`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:75`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:209`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:169`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:23`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:25`

c0140d6b 新增的 `--beam`、`--sentences`、`--limit` 不在 `optionsValid`（Main.kt:158）中检查，`Arguments.int`（Main.kt:75）与 `Models.limits`（Main.kt:209）在校验之后才 `toInt()`。
结果：`--beam x`、`--limit 2,` 等抛出 NumberFormatException，以退出码 1 和堆栈退出，而不是约定的 usage 退出码 2；0、负值和 `--limit 1,2,3` 不会在校验阶段被拒绝（第三段被静默忽略），CORRECTNESS-2@S24 指出 `--beam 0` 要等读完集合文件后才由 `PinyinDecoder` 的 `require` 报错；MainTest 的 `usageErrors` 也没有这些用例。
修复：在 `optionsValid` 中要求正整数、`--limit` 为 1 或 2 段（同 `threads`、`weight` 的做法），并把这些用例加入 MainTest。STYLE 另指出 Main.kt:209 的 `{ _ -> it[0] }` 遮蔽了 `it`；GENERIC-1@S24 还顺带提到 Main.kt:169 拒绝 `--scheme no-latin --neighbours on`（见 G5-20）。
严重度不一：三个 S23 阶段记为 NIT，TESTING、STYLE 与三个 S24 阶段记为 SHOULD FIX。

- 提出者：CORRECTNESS-1@S23（NIT，9/10）、CORRECTNESS-2@S23（NIT，9/10）、GENERIC-1@S23（NIT，9/10）、TESTING@G5（SHOULD FIX，9/10）、STYLE@G5（SHOULD FIX，9/10）、CORRECTNESS-1@S24（SHOULD FIX，9/10）、CORRECTNESS-2@S24（SHOULD FIX，9/10）、GENERIC-1@S24（SHOULD FIX，9/10）

#### F10 [SHOULD FIX] 精排在后台重排列表，按索引的点选与长按操作可能命中别的词

- 来源：第一轮（整仓 @07d2778） · 共识：8/15 位 reviewer（8 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:37`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:36`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:349`、`app/src/main/cpp/androidengine/androidengine.cpp:37`、`app/src/main/cpp/androidengine/androidengine.cpp:116`、`app/src/main/cpp/androidengine/androidengine.cpp:117`、`app/src/main/cpp/androidengine/androidengine.cpp:144`、`app/src/main/cpp/androidengine/androidengine.cpp:146`、`app/src/main/cpp/androidengine/androidengine.cpp:147`、`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:100`、`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:61`、`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:104`、`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:131`、`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/BaseExpandedCandidateWindow.kt:181`、`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateComponent.kt:96`、`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/BaseExpandedCandidateWindow.kt:167`、`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/PagingCandidateViewAdapter.kt:33`、`app/src/main/cpp/androidengine/androidengine.cpp:27`、`app/src/main/cpp/androidengine/androidengine.cpp:42`

Pick（EngineCandidateWord::select，androidengine.cpp:36-37）以及 Forget/Pin/Unpin/Block（triggerAction，约 line 146-147）只携带索引；而 refine 切片（最多 SLICES = 64 片，约半秒）在没有用户输入时替换列表并把重排后的句子移到首位。用户对旧列表的点击若在重排之后才到达 fcitx 线程，就会提交新列表的 B[idx]，或屏蔽、遗忘另一个词，而菜单标题显示的是原来的词。
stopRefining()（line 116-117）只在长按到达 fcitx 线程后才起保护作用，line 144 的 offers() 复查只确认该动作仍可用；GENERIC-1@S26 指出 Block 与 Forget 会改写用户数据，最需要校验。
修复（三票都保留 refine 目标）：随 Pick 和动作一起发送候选文本或快照/列表代号，与 word->text() 比对，不符则丢弃；CORRECTNESS-1@S26 另提可在候选按下时停止 refine，CORRECTNESS-2@S26 另提列表显示超过 N ms 后不再重排。

- 可能的运行时影响：用户点选的词可能被换成另一个词提交，屏蔽或遗忘操作也可能作用到另一个词，改写用户数据。
- 跨模块组合并：G6-05、G7-10、G7-34（候选长按菜单可能作用于重排后的另一个候选；点击候选按位置选择，暂停重排后可能上屏的不是所点的词）
- 提出者：CORRECTNESS-1@S26（SHOULD FIX，6/10）、CORRECTNESS-2@S26（SHOULD FIX，5/10）、GENERIC-1@S26（SHOULD FIX，5/10）、CORRECTNESS-1@S32（SHOULD FIX，6/10）、CORRECTNESS-2@S32（SHOULD FIX，6/10）、GENERIC-1@S32（SHOULD FIX，6/10）、CORRECTNESS-2@S35（SHOULD FIX，5/10）、GENERIC-1@S35（SHOULD FIX，6/10）

#### F11 [SHOULD FIX] CI 从不编译或测试 voice flavor

- 来源：第一轮（整仓 @07d2778） · 共识：7/24 位 reviewer（6 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`.github/workflows/pull_request.yml:64`、`.github/workflows/nix.yml:26`、`.github/workflows/emulator_test.yml:65`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/pull_request.yml:64`、`.github/workflows/nix.yml:26`、`.github/workflows/emulator_test.yml:65`）
- 位置：`.github/workflows/pull_request.yml:64`；另见 `.github/workflows/nix.yml:26`、`.github/workflows/emulator_test.yml:65`、`.github/workflows/emulator_test.yml:81`、`.github/workflows/unit_test.yml:82`、`.github/workflows/unit_test.yml:83`、`.github/workflows/unit_test.yml:84`、`lib/sherpa-onnx/host-test.sh:28`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:690`、`app/src/text/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:18`、`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`

所有工作流只构建或测试 `text` 变体（pull_request.yml:64、nix.yml:26、emulator_test.yml 的 `TextDebug`、unit_test.yml 的 `testTextDebugUnitTest`），因此 `app/src/voice`、带 youmo 补丁的 `:lib:sherpa-onnx` 原生构建和 `app/src/androidTestVoice` 在 CI 中从不编译。
两个 flavor 的 `VoiceFeature` 必须保持相同签名（CLAUDE.md），漂移只能在开发者本机发现；TESTING@G1 另指出 `lib/sherpa-onnx/host-test.sh` 和 `VoiceEngineTest`（唯一检查 `hotwords.txt`、`bpe.vocab` 的测试）也从未运行。
建议（各家略有不同）：在 pull_request.yml 的一个 OS 上加 `:app:assembleVoiceRelease`（带一个 `BUILD_ABI`），或至少在 unit_test.yml 加 `:app:compileVoiceDebugKotlin`、`compileVoiceDebugUnitTestKotlin` 或 `compileVoiceReleaseKotlin`。
GENERIC-1@S1 建议至少每周跑一次 `:lib:sherpa-onnx:assembleRelease`；TESTING@G1 建议加 `host-test.sh` 步骤，并把 `connectedVoiceDebugAndroidTest` 加入每周模拟器任务。

- 跨模块组合并：G1-02、G6-28、G8b-57（没有 CI 任务运行 youmo 的 sherpa-onnx 补丁测试；两个 flavor 的 VoiceFeature 签名一致性未被强制）
- 提出者：CORRECTNESS-1@S1（SHOULD FIX，9/10）、CORRECTNESS-2@S1（SHOULD FIX，8/10）、GENERIC-1@S1（SHOULD FIX，8/10）、TESTING@G1（SHOULD FIX，7/10）、TESTING@G6（SHOULD FIX，9/10）、CORRECTNESS-2@S51（NIT，8/10）、GENERIC-1@S51（SHOULD FIX，6/10）

#### F12 [SHOULD FIX] 模块内多份 fcitx 值转义实现且边界行为不一致

- 来源：第一轮（整仓 @07d2778） · 共识：7/12 位 reviewer（1 SHOULD FIX, 6 NIT）· 置信度 9/10 · 验证：✅ 已确认（`K/core/FcitxUtils.kt:23-27`、`K/engine/data/SourceException.kt:41-44`、`K/engine/phrase/CustomPhrases.kt:188-190`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/SourceException.kt:42`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/core/FcitxUtils.kt:23`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CodeTableReaderTest.kt:252`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CodeTableReaderTest.kt:260`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/phrase/CustomPhrases.kt:188`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/phrase/CustomPhrases.kt:178`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/SourceException.kt:41`

`FcitxUtils.unescapeForValue/escapeForValue`（core/FcitxUtils.kt，用于快捷短语）与 `unescapeValue/escapeValue/splitValues`（SourceException.kt:42，用于码表、自定义短语、libime 导入）重复实现 fcitx 值转义；STYLE@G3 另指出 `CustomPhrases.unquote`（CustomPhrases.kt:188）为第三份。
二者边界行为不一致：对 `""`，FcitxUtils 保留两个引号而 `unescapeValue` 返回空串；对 `"a"b"`，FcitxUtils 返回空串而另一实现原样返回。StringEscapeTest 与 CodeTableReaderTest 各固定一种实现且都称“as fcitx”，没有测试对照二者，同一 fcitx 格式被两种方式读取。
建议：只保留一份实现（STYLE@G3 建议放在 `core`，并把字符串辅助函数移出以异常命名的文件），或写明为何不同，或用一张共享用例表测试两者。严重度：STYLE@G3 为 SHOULD FIX，其余为 NIT。

- 跨模块组合并：G3a-08、G3b-07（CustomPhrases的unquote重复了unescapeValue）
- 提出者：CORRECTNESS-1@S7（NIT，8/10）、CORRECTNESS-2@S7（NIT，8/10）、GENERIC-1@S7（NIT，9/10）、CORRECTNESS-2@S15（NIT，7/10）、GENERIC-1@S15（NIT，9/10）、STYLE@G3（SHOULD FIX，9/10）、GENERIC-1@S10（NIT，8/10）

#### F13 [SHOULD FIX] 可见密码框未被当作密码框，自动配对和语音仍生效

- 来源：第一轮（整仓 @07d2778） · 共识：7/24 位 reviewer（6 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:127`、`CapabilityFlag.kt:149`、`AppPrefs.kt:117`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:127`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:338`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:442`、`app/src/main/java/org/fcitx/fcitx5/android/core/CapabilityFlag.kt:149`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWindow.kt:206`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldSession.kt:107`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:849`

`CapabilityFlags.fromEditorInfo` 把 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` 只映射为 `Sensitive`，不映射为 `Password`（CapabilityFlag.kt:149-151），而 `inPasswordField`（FcitxInputMethodService.kt:127）和 KawaiiBarComponent.kt:442 的麦克风按钮只检查 `Password`。
结果：在打开了「显示密码」的字段里，默认开启的自动配对把 `(` 变成 `()`，给密码多加一个用户没打的 `)`；语音按钮、空格长按（CommonKeyActionListener.kt:176）、VoiceWindow.kt:206 和 VoiceHoldSession.kt:107 仍允许把语音写进密码框，与代码注释「nothing said out loud goes into a password」相反。
修复：定义一个同时覆盖 Password 和 visible-password 变体的判断（几票建议放进 ime-core 写成纯函数），所有调用点共用，并加单元测试。CORRECTNESS-2@S33 建议不要直接给该变体加 `Password` 标志，以免改变 fcitx 行为；CORRECTNESS-2@S34 和 GENERIC-1@S34 提醒有些 app 只用 VISIBLE_PASSWORD 来关闭联想，是否包含要有意决定。

- 可能的运行时影响：在显示明文的密码框里，自动配对会给用户的密码多插入右括号，语音输入也能把口述内容写进密码框。
- 跨模块组合并：G7-03、G8a-09（语音门控未排除可见密码字段）
- 提出者：CORRECTNESS-1@S33（SHOULD FIX，9/10）、CORRECTNESS-2@S33（SHOULD FIX，8/10）、GENERIC-1@S33（SHOULD FIX，8/10）、CORRECTNESS-1@S34（SHOULD FIX，8/10）、CORRECTNESS-2@S34（SHOULD FIX，6/10）、GENERIC-1@S34（SHOULD FIX，6/10）、GENERIC-1@S37（NIT，6/10）

#### F14 [SHOULD FIX] 解压 zip 的路径穿越检查可绕过且输出流未关闭

- 来源：第一轮（整仓 @07d2778） · 共识：7/15 位 reviewer（3 SHOULD FIX, 1 NIT, 3 SHOULD FIX PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20-24`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:101`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:127`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:21`、`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:23-24`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:101`

`ZipStream.kt` 第 20 行用 `canonicalPath.startsWith(canonicalDest)` 判断且不带路径分隔符，`../<dest>x/f` 这类条目会落到同级目录；第 23-24 行的目录条目完全不检查，可在 `destDir` 外 `mkdir`；第 21 行 `copyTo(file.outputStream())` 从不关闭流，每个条目泄漏一个文件描述符。zip 来自用户选择的主题、码表、备份等不可信文件。
三个 S45 阶段还指出缺少父目录条目的嵌套文件会失败；CORRECTNESS-1@S45 指出 `SecurityException()` 无消息，错误对话框会显示原始堆栈；TESTING 指出没有测试。PERFORMANCE、TESTING、STYLE 标为 PRE-EXISTING（自分叉点 19596419 未改），CORRECTNESS-2@S42 只给 NIT。
建议：两类条目都检查 `canonicalPath == canonicalDest || canonicalPath.startsWith(canonicalDest + File.separator)`，调用 `parentFile.mkdirs()`，写入用 `file.outputStream().use { copyTo(it) }`，给异常加消息，并用构造的恶意条目补测试。

- 可能的运行时影响：恶意的主题、码表或备份 zip 可把文件或目录写到临时目录之外，且每次解压都会泄漏文件描述符。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-2@S42（NIT，8/10）、CORRECTNESS-1@S45（SHOULD FIX，9/10）、CORRECTNESS-2@S45（SHOULD FIX，8/10）、GENERIC-1@S45（SHOULD FIX，9/10）、PERFORMANCE@G8（SHOULD FIX PRE-EXISTING，9/10）、TESTING@G8（SHOULD FIX PRE-EXISTING，8/10）、STYLE@G8（SHOULD FIX PRE-EXISTING，8/10）

#### F15 [SHOULD FIX] 没有构建检查阻止依赖合入 INTERNET 网络权限

- 来源：第一轮（整仓 @07d2778） · 共识：7/17 位 reviewer（7 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/AndroidManifest.xml:5-15`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/AndroidManifest.xml:5-12`；另见 `app/build.gradle.kts:61`、`app/build.gradle.kts:126`、`build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt:43`、`gradle/libs.versions.toml:22`、`app/src/main/AndroidManifest.xml:14`、`app/src/main/AndroidManifest.xml:14-15`、`app/src/main/AndroidManifest.xml:10-12`

README 与 PRIVACY.md 承诺不申请网络权限，但这只在没有依赖的 manifest 合入 `android.permission.INTERNET` 时成立：主 manifest 没有针对 INTERNET 的 `tools:node="remove"`（GENERIC-1@S3 指出 AndroidManifest.xml:5-12 只移除了 AppCompat 的权限），也没有测试或任务检查合并后的 manifest。
`material`、`aboutlibraries`、`imagecropper` 等依赖的一次升级就可能悄悄让键盘获得网络访问；各票都说明该修复是在落实既定目标而非推翻它，CORRECTNESS-2@S2 因没有 `app/build` 未能核实当前合并结果。
建议：在主 manifest 加 `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`（ACCESS_NETWORK_STATE 同理），或加一个在合并后的 release manifest 含这些权限时让构建或 CI 失败的检查（GENERIC-1@S1 提到 `aapt2 dump permissions`）。

- 可能的运行时影响：依赖升级可能把 INTERNET 权限静默合入发布的 APK，使能读到全部按键的输入法获得网络访问，违背无网络承诺。
- 跨模块组合并：G1-04、G9-06（缺少防止合并清单引入 INTERNET 权限的保护）
- 提出者：CORRECTNESS-1@S1（SHOULD FIX，6/10）、GENERIC-1@S1（SHOULD FIX，6/10）、CORRECTNESS-2@S2（SHOULD FIX，6/10）、GENERIC-1@S3（SHOULD FIX，7/10）、CORRECTNESS-1@S46（SHOULD FIX，6/10）、CORRECTNESS-2@S46（SHOULD FIX，6/10）、GENERIC-1@S46（SHOULD FIX，6/10）

#### F16 [SHOULD FIX] AutoPairs 有配对时，每输入一字都同步读取编辑器

- 来源：第一轮（整仓 @07d2778） · 共识：6/21 位 reviewer（6 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:47`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:47`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:47`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:45`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:250`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:261`、`app/src/main/java/org/fcitx/fcitx5/android/input/editing/InputConnectionEditor.kt:46`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:342`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:117`

`AutoPairs.type` 在第 47 行先调用 `textAfterCursor(1)`，之后才判断字符能否闭合或开启配对；只要记有配对（`top != null`），普通字符（如在（）内输入「好」或英文字母）也要付出一次阻塞的 `getTextAfterCursor` 跨进程往返，然后在 `closer == null` 处返回 false。
`FcitxInputMethodService.commitTyped`（FcitxInputMethodService.kt:250、261）对每次提交和每个虚拟键都调用它，因此在括号内打字就是每键一次主线程往返；`ContextMemory` 与 `EditingSession.backspace` 本意正是避免这种每键读取。
各票一致建议：先计算 `closes`，在读取前加 `if (!closes && closer == null) return false`，行为不变；CORRECTNESS-1@S21 另建议在 AutoPairsTest 中断言在配对内输入「好」时不调用 `textAfterCursor`。

- 跨模块组合并：G4-03、G7-46（括号未闭合时，每个单字提交都多做一次无用的 IPC）
- 提出者：CORRECTNESS-1@S14（SHOULD FIX，9/10）、CORRECTNESS-2@S14（SHOULD FIX，9/10）、GENERIC-1@S14（SHOULD FIX，9/10）、CORRECTNESS-1@S21（SHOULD FIX，8/10）、PERFORMANCE@G4（SHOULD FIX，9/10）、CORRECTNESS-1@S33（SHOULD FIX，9/10）

#### F17 [SHOULD FIX] RecentlyUsed 的 limit 不生效，最近使用列表无限增长

- 来源：第一轮（整仓 @07d2778） · 共识：6/12 位 reviewer（6 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/RecentlyUsed.kt:26`、`lib/ime-core/.../picker/PickerSections.kt:21-33`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/RecentlyUsed.kt:26`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:94`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:57`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridView.kt:46`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridView.kt:47`

`RecentlyUsed.kt:26` 的 `limit` 只用作 `LinkedHashMap` 的初始容量；上游 de8c847d 去掉了 `removeEldestEntry`，`insert` 从不淘汰旧项。PickerGridAdapter 传入的 `columnCount * rowCount` 因此不再限制条数，PickerSections 显示整张列表，最近使用区会保留所有用过的符号，而且每次选择都把整张列表 JSON 编码后重写。
修复：在 `insert` 中以及加载之后，当 `map.size > limit` 时移除最旧的项。

- 可能的运行时影响：表情和符号的「最近使用」区不再受行列数限制，会列出所有用过的项目。
- 跨模块组合并：G7-20、G8a-20（最近使用分区没有数量上限）
- 提出者：CORRECTNESS-2@S30（SHOULD FIX，9/10）、GENERIC-1@S30（SHOULD FIX，9/10）、CORRECTNESS-1@S38（SHOULD FIX，9/10）、CORRECTNESS-2@S38（SHOULD FIX，9/10）、GENERIC-1@S38（SHOULD FIX，9/10）、PERFORMANCE@G8（SHOULD FIX，9/10）

#### F18 [SHOULD FIX] 导入词库时文档提供方的输入流从未关闭

- 来源：第一轮（整仓 @07d2778） · 共识：6/12 位 reviewer（4 SHOULD FIX, 2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:104`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:101`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:115`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:120`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:227`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:250`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:226`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:232`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:249`

`PinyinDictManager.importFromInputStream`（:101，来自上游）和新增的 `readWords`（:115-120）复制完 `stream` 后都不关闭它，调用方 PinyinDictionaryFragment（:227-229、:250-251 的 `importIntoUser`/`import`）也没有用 `use {}`，每次导入都会泄漏一个 provider 文件描述符，直到 GC 回收；`QuickPhraseManager.importFromInputStream` 则用了 `stream.use`。
修复：在被调函数内用 `stream.use { }` 包住函数体；GENERIC-1@S31 还建议在 `finally` 中删除 `tempFile`，因为复制失败时它会留在 `cacheDir`。

- 跨模块组合并：G7-28、G8a-70（导入时打开的内容流从未关闭）
- 提出者：CORRECTNESS-2@S31（NIT，8/10）、GENERIC-1@S31（SHOULD FIX，8/10）、CORRECTNESS-1@S41（SHOULD FIX，9/10）、CORRECTNESS-2@S41（NIT，8/10）、GENERIC-1@S41（SHOULD FIX，9/10）、PERFORMANCE@G8（SHOULD FIX，8/10）

#### F19 [SHOULD FIX] PinyinRun 的精排条件与预算与应用不一致，高估「+ models」

- 来源：第一轮（整仓 @07d2778） · 共识：6/6 位 reviewer（5 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:104-106`、`PinyinSession.kt:557`、`Reranker.kt:41`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:104`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:103`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:103-108`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:106`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:109`

`PinyinSession` 只在输入以完整音节结尾（`endsOnSyllable`）时调用 refiner，且最多 `refiner.slices`（64）次、每次 `REFINE_BUDGET` = 1；而 PinyinRun.kt:103-108 对每个暂停的样本（包括 pinyin.tsv 548 条中的 63 条 `abbrev` 与 28 条 `partial`）以 `Int.MAX_VALUE` 预算循环调用 `refine`。
因此 `pinyin` 命令产出的 README「+ 模型」列和 release-report 门禁计入了应用不会给出的 refiner 结果；PERFORMANCE、TESTING、STYLE 指出 `while (picked == null)` 循环无上限，refiner 一直返回 null 时会死循环，PERFORMANCE 还指出 refine 未计时；PinyinRun.kt:103 的注释（「waiting on a server」「as the session would」）已过时。
修复意见：照搬会话的 `endsOnSyllable` 条件和 `SentenceRefiner.SLICES * PinyinSession.REFINE_BUDGET` 上限，或像 `ksc`/KeystrokeRun 那样直接驱动 `PinyinSession`（两个 CORRECTNESS 阶段倾向后者）；STYLE 建议改为单次调用并把 null 视为 NONE，或按 `r.slices` 限制循环；TESTING 建议补一个返回 null 超过 `slices` 次的测试；PERFORMANCE 建议报告每次暂停的 slice 数或耗时。
附带差异：CORRECTNESS-1@S23 指出 line 109 在仅 rerank 时对完整输入再次调用 `picker.pick`；CORRECTNESS-1@S23 与 GENERIC-1@S23（confidence 6）指出 picker/refiner 拿到完整 `context`，而会话在 `TextWords.lastTwo(context)` 为空时传空字符串。

- 提出者：CORRECTNESS-1@S23（SHOULD FIX，8/10）、CORRECTNESS-2@S23（SHOULD FIX，8/10）、GENERIC-1@S23（SHOULD FIX，8/10）、PERFORMANCE@G5（SHOULD FIX，8/10）、TESTING@G5（SHOULD FIX，7/10）、STYLE@G5（NIT，8/10）

#### F20 [SHOULD FIX] 用户热词与屏蔽词在词表缺字时被写入 logcat

- 来源：第一轮（整仓 @07d2778） · 共识：6/9 位 reviewer（6 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:185`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:233`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:88`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:90`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/utils.cc:65`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/utils.cc:66`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:183`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:185`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:221`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:241`

VoiceEngine.stream() 把最多 3000 个用户词（热词与屏蔽词）传给 sherpa-onnx；只要某个词含 tokens.txt 中没有的 token，EncodeHotwords→EncodeBase（utils.cc:65-66）就用 SHERPA_ONNX_LOGE 打印该词所在行，上游 CreateStream(hotwords)（offline-recognizer-transducer-impl.h:185）还会打印整串热词，每段语音一次，与 youmo 屏蔽词路径「只记数量」的注释（line 241）相矛盾。
这些日志进入 Android logcat，应用的 utils/Logcat.kt 与 LogActivity 会展示并导出，用户常把它贴进 bug 报告；VoiceHotwords.spelled 只检查 BMP 汉字而不查词表，一个生僻字即可触发。CORRECTNESS-1@S28 另指出 EncodeBase 只保留已知 token，双字词可能退化成单字热词。
修复：在 Kotlin 侧（读一次 tokens.txt）或 C++ 侧用 symbol_table_.Contains 预先过滤含词表外字符的词，或给 EncodeBase 加静默模式，并把 line 185 的日志改为只打印数量。CORRECTNESS-1@S28、CORRECTNESS-2@S52 和 PERFORMANCE@G6 注明未核实 ssentencepiece/X-ASR 是否真会产生词表外片段。

- 可能的运行时影响：用户自己的词（最多 3000 个）会写进可在应用内查看并导出的 logcat，随 bug 报告外泄。
- 提出者：CORRECTNESS-1@S28（SHOULD FIX，5/10）、CORRECTNESS-2@S28（SHOULD FIX，7/10）、CORRECTNESS-1@S52（SHOULD FIX，7/10）、CORRECTNESS-2@S52（SHOULD FIX，6/10）、GENERIC-1@S52（SHOULD FIX，8/10）、PERFORMANCE@G6（SHOULD FIX，5/10）

#### F21 [SHOULD FIX] 语音版许可页面漏列随 APK 分发的原生库

- 来源：第一轮（整仓 @07d2778） · 共识：6/12 位 reviewer（6 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:38`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:38`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:37`、`lib/sherpa-onnx/README.md:13-14`、`lib/sherpa-onnx/src/main/cpp/CMakeLists.txt:544-567`、`lib/sherpa-onnx/build.gradle.kts:9`、`app/licenses/libraries/*.json:1`

voice flavor 的 VoiceFeature.credits() 只列出 X-ASR、sherpa-onnx 和 silero-vad；app/ 下（包括 app/licenses/libraries）没有 onnxruntime（MIT），也没有 lib/sherpa-onnx/README.md 列出的、由 CMake 下载并编译进来的 kaldi-native-fbank、kaldi-decoder、simple-sentencepiece（Apache-2.0）、eigen 和 json。
libonnxruntime.so 必须随 APK 打包识别器才能加载，而 MIT 与 Apache-2.0 都要求许可声明随二进制一起分发。
修复：为每个链接进 sherpa-onnx-jni 或随 APK 分发的库添加一个 Credit。

- 可能的运行时影响：应用内的开源许可页面缺少随 APK 分发的 onnxruntime 等原生库的许可声明。
- 子论断 F21.e 被否定：「`app/licenses` 中没有 sherpa-onnx、onnxruntime、X-ASR 模型或 silero VAD 的条目，而语音版 APK 都附带了这些组件。」→ sherpa-onnx、X-ASR 和 silero-vad 由 VoiceFeature.credits 列出致谢（app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:39-41），这些致谢显示在同一个许可证页面上（app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:28）。四者中只有 onnxruntime 没有致谢。
- 跨模块组合并：G6-11、G1-03（voice APK 内含的第三方原生库未在许可页署名）
- 提出者：CORRECTNESS-1@S28（SHOULD FIX，7/10）、CORRECTNESS-2@S28（SHOULD FIX，7/10）、CORRECTNESS-1@S1（SHOULD FIX，7/10）、CORRECTNESS-2@S1（SHOULD FIX，8/10）、GENERIC-1@S1（SHOULD FIX，7/10）、STYLE@G1（SHOULD FIX，6/10）

#### F22 [SHOULD FIX] 上下文记忆键 packageName#fieldId 无法区分浏览器中的各个网站

- 来源：第一轮（整仓 @07d2778） · 共识：6/12 位 reviewer（6 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:672`、`lib/ime-core/.../session/PinyinSession.kt:406`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:672`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:12`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:671`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:11`

`FcitxInputMethodService.kt:672` 用 `packageName#fieldId` 作为 ContextMemory 的键，但 `EditorInfo.fieldId` 是承载输入的宿主 View 的 id：Chrome/WebView 中所有网站、同一 app 中的 Compose 字段共用一个 id（或为 NO_ID/0），这与 ContextMemory.kt:12「so a browser's sites do not share one」的说法不符。
后果：30 分钟内，在网站 A 上写的文字会成为网站 B 空输入框的重排上下文；GENERIC-1@S33 还指出引擎会学到跨站点的词对。CORRECTNESS-1@S33 指出数据不会离开设备。
修复：在键中加入 `fieldName`、`hintText`、`label` 或 `inputType`，或在 `fieldId <= 0`、NO_ID 或宿主为 WebView 时不做记忆；至少改正文档。

- 可能的运行时影响：在浏览器里，一个网站中输入的文字会被当作另一个网站空输入框的上下文，影响候选并被引擎学习。
- 跨模块组合并：G7-14、G4-09（ContextMemory 的字段键过粗，浏览器各站点共享上下文）
- 提出者：CORRECTNESS-1@S33（SHOULD FIX，6/10）、CORRECTNESS-2@S33（SHOULD FIX，6/10）、GENERIC-1@S33（SHOULD FIX，6/10）、CORRECTNESS-1@S14（SHOULD FIX，6/10）、CORRECTNESS-2@S14（SHOULD FIX，6/10）、GENERIC-1@S14（SHOULD FIX，6/10）

#### F23 [SHOULD FIX] CI 从不运行 `:lib:ime-eval` 的测试

- 来源：第一轮（整仓 @07d2778） · 共识：5/15 位 reviewer（5 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`.github/workflows/unit_test.yml:80`、`.github/workflows/unit_test.yml:123-130`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/unit_test.yml:80`、`.github/workflows/unit_test.yml:123-130`）
- 位置：`.github/workflows/unit_test.yml:80`；另见 `.github/workflows/unit_test.yml:118-129`、`.github/workflows/unit_test.yml:120-129`、`lib/ime-eval/build.gradle.kts:18-19`、`.github/workflows/unit_test.yml:73`、`lib/ime-eval/build.gradle.kts:19`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:76`

`lib/ime-eval/src/test` 有 14 个测试类，只需临时文件和已提交的 `data/`，不需要 SDK 或下载，但没有任何工作流运行它们。
其中 `EvalSetTest` 是 `data/pinyin.tsv` 变更后派生集是否已重建的唯一检查（README 的准确率表依赖这些集合），其余测试驱动 ime-core API，所以破坏它们的 ime-core 改动不会被 CI 发现。
建议：在 unit_test.yml 的该步骤加 `:lib:ime-eval:test`，并把 `lib/ime-eval/build/reports/tests` 加入上传路径；CORRECTNESS-1@S1 与 GENERIC-1@S1 还要求一并补上 `lib/ime-dict-tool` 的报告路径（见 G1-19）。

- 跨模块组合并：G1-06、G5-07（CI 从不运行 `:lib:ime-eval:test`）
- 提出者：CORRECTNESS-1@S1（SHOULD FIX，8/10）、GENERIC-1@S1（SHOULD FIX，8/10）、TESTING@G1（SHOULD FIX，9/10）、TESTING@G5（SHOULD FIX，9/10）、CORRECTNESS-2@S24（SHOULD FIX，9/10）

#### F24 [SHOULD FIX] TableRun 的 `cheapest` 缓存不缓存不可达词

- 来源：第一轮（整仓 @07d2778） · 共识：5/6 位 reviewer（2 SHOULD FIX, 3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:106`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:106`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:101`

TableRun.kt:106 用 `HashMap.getOrPut` 缓存每个词的最便宜编码，但 `getOrPut` 把已存的 null 视为缺失，所以 `typeWord` 对没有编码能打出的词每次都重新计算。
这类词在每个文本的每个切分位置都被重新输入（PERFORMANCE 指出每次新建 `TableSession`、每个编码最多翻 10 页），表格评测变慢，重复的动作还计入延迟统计。
修复：计算前先检查 `containsKey`/`word in cheapest`，或存一个表示「不可达」的哨兵值。
严重度不一：三个 S23 阶段记为 NIT，PERFORMANCE 与 STYLE 记为 SHOULD FIX。

- 提出者：CORRECTNESS-1@S23（NIT，8/10）、CORRECTNESS-2@S23（NIT，9/10）、GENERIC-1@S23（NIT，8/10）、PERFORMANCE@G5（SHOULD FIX，9/10）、STYLE@G5（SHOULD FIX，9/10）

#### F25 [SHOULD FIX] 仪器测试仍使用已移除的 libime 输入法名 pinyin/wbx

- 来源：第一轮（整仓 @07d2778） · 共识：5/24 位 reviewer（5 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:172-179`、`lib/fcitx5/.../inputmethodmanager.cpp:351-353`、`FcitxTest.kt:94`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:172-179`、`lib/fcitx5/.../inputmethodmanager.cpp:351-353`、`FcitxTest.kt:94`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:94`；另见 `app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:260`、`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:210`、`app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:335`、`app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:67`、`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:37`、`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:64`、`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:92`、`lib/ime-eval/run-on-device.sh:11`、`app/src/main/cpp/androidengine/androidengine.cpp:178`、`app/src/main/cpp/androidengine/androidengine.cpp:172`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:34`、`app/src/main/cpp/CMakeLists.txt:54`、`app/src/main/cpp/androidengine/androidengine.cpp:93`、`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:196`、`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:234`、`app/src/main/cpp/androidengine/androidengine.cpp:136`、`app/src/main/cpp/androidfrontend/androidfrontend.cpp:214`、`app/src/main/cpp/androidengine/androidengine.cpp:346`

引擎现在只注册 `engine-*` 名称（androidengine.cpp:178），chinese-addons 也不再构建 libime 的 pinyin 和 table addon，但 FcitxTest（`pinyin`、`wbx`）、SoftKeyboardTest.kt:335（`PINYIN` 为 `pinyin`）以及 EngineEvalRunner.kt:37 的默认值（还有 lib/ime-eval/run-on-device.sh:11）仍使用旧名。
fcitx 会丢弃未知名称，于是 `pinyinAndWubiAreBothAvailable`（FcitxTest.kt:260）失败、`enabledIme().first {…}` 抛 NoSuchElementException、评测 runner 自己的 `currentIme` 断言（EngineEvalRunner.kt:64）失败；每周的 emulator_test.yml 作业运行这些测试，TESTING@G7 指出它是 EngineBridge 经 JNI 的唯一测试。
修复：改用 `Engines.PINYIN` 和 `engine-wubi`（或 `Engines.TABLES` 的键），同步修改 run-on-device.sh 的默认值和 FcitxTest.kt:210、EngineEvalRunner.kt:92 的 libime 措辞。TESTING@G7 还指出 SoftKeyboardTest.kt:67-70 等待的空格键标签应改用 `InputMethodNames.of(context, entry)` 构建。

- 子论断 F25.b 被否定：「插桩测试套件 FcitxTest、SoftKeyboardTest 和 EngineEvalRunner 仍以已移除的 libime 输入法 `pinyin` 和 `wbx` 为目标，因此它们的测试在每周模拟器任务中会失败或超时。」→ EngineEvalRunner 唯一的测试在每周任务中是被跳过，而不是失败：EngineEvalRunner.kt:36 处有 `assumeTrue(set != null)`，而 .github/workflows/emulator_test.yml:81 运行 connectedTextDebugAndroidTest 时没有传入 evalSet 参数。关于 FcitxTest 和 SoftKeyboardTest 的部分成立（FcitxTest.kt:94、SoftKeyboardTest.kt:67/:335）
- 跨模块组合并：G7-05、G6-27（FcitxTest 用已移除的输入法名，引擎 addon 无有效测试）
- 提出者：CORRECTNESS-1@S49（SHOULD FIX，9/10）、CORRECTNESS-2@S49（SHOULD FIX，9/10）、TESTING@G7（SHOULD FIX，8/10）、STYLE@G7（SHOULD FIX，8/10）、TESTING@G6（SHOULD FIX，8/10）

#### F26 [SHOULD FIX] 输入法设置测试漏测 `engine-t9` 九键页面

- 来源：第一轮（整仓 @07d2778） · 共识：5/21 位 reviewer（4 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:20-23`、`androidengine.cpp:178`、`InputMethodNames.kt:20`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:20-23`、`androidengine.cpp:178`、`InputMethodNames.kt:20`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:20`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:178-186`、`app/src/main/java/org/fcitx/fcitx5/android/core/InputMethodNames.kt:20`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:202`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:209-212`

`names` 列出 8 个输入法，漏掉了实际发布的 `engine-t9`（`androidengine.cpp:178-186`、`InputMethodNames.kt:20`）；它有独立分支 `pinyin(nineKeys = true)`（`InputMethodSettings.kt:202`），是唯一把 `Fuzzy/NG_GN`、`LatinWords` 和 `Flags` 的 `V_U` 标为 unused 的页面，页面存在测试与存储值属性测试都不覆盖它，测试名 `everyInputMethodOfTheAppHasAPageAndOthersNone` 名不副实。
TESTING 另指出写入 fcitx 配置的 `create()`（`InputMethodSettings.kt:209-212`：经 `getOrCreate` 的嵌套键、Flags 写入、`save()` 调用）没有测试。STYLE 只给 NIT。
建议：把 `engine-t9` 加入 `names`，断言其两个 Toggle 的 `unused != 0`、`Flags.unused` 仅含 `V_U`、`engine-pinyin` 无 unused、双拼页无 LatinWords；并基于 RawConfig 构建页面、修改一项偏好，断言 cfg 值与 `save` 被调用。

- 提出者：CORRECTNESS-1@S51（SHOULD FIX，9/10）、CORRECTNESS-2@S51（SHOULD FIX，9/10）、GENERIC-1@S51（SHOULD FIX，9/10）、TESTING@G8（SHOULD FIX，9/10）、STYLE@G8（NIT，8/10）

#### F27 [SHOULD FIX] 备份排除规则使用了错误的 domain，实际未排除任何资产

- 来源：第一轮（整仓 @07d2778） · 共识：5/14 位 reviewer（4 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt:42-47`、`data_extraction_rules.xml:4-29`、`full_backup_content.xml:3-14`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/res/xml/data_extraction_rules.xml:4`；另见 `app/src/main/res/xml/full_backup_content.xml:3-17`、`app/src/main/res/xml/data_extraction_rules.xml:4-15`、`app/src/main/res/xml/data_extraction_rules.xml:18-29`、`app/src/main/res/xml/full_backup_content.xml:3-14`、`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt:42`、`app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseManager.kt:18`、`app/src/main/res/xml/full_backup_content.xml:3`

`DataManager.dataDir` 是应用数据根目录（N+ 为 device-protected storage，即 `device_root`；API 23 为 `root`），不是 `filesDir`，因此 data_extraction_rules.xml 和 full_backup_content.xml 中 `domain="file"` 的 `usr`、`descriptor.json`、`licenses.json`、`README.md` 排除项匹配不到任何文件。
结果是可再生成的同步资产被备份和迁移，占用 25 MB 云备份配额（STYLE@G9 指出超额会跳过整个备份）；CORRECTNESS-2@S46 还指出恢复回来的 `descriptor.json` 会让 `DataManager.sync()` 认为资产已安装，而备份 tar 跳过 symlink，部分恢复不会被修复。
修复：改用 `domain="device_root"`（API 23 另加 `domain="root"`），full_backup_content.xml 同样修改；PERFORMANCE@G9 建议删除未写入任何位置的 `licenses.json` 条目，STYLE@G9 指出三份排除列表已不一致（`no_backup` 只出现在其中两份）。
PERFORMANCE@G9 将其标为 PRE-EXISTING（规则来自上游 30f0166a，改用 device-protected storage 来自 be83d111），其余评审未加此标记。

- 可能的运行时影响：同步资产被计入备份，可能占满 25 MB 云备份配额而导致整个应用的备份被跳过，恢复回来的 descriptor.json 还可能让 DataManager.sync() 不修复缺失的资产。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S46（SHOULD FIX，8/10）、CORRECTNESS-2@S46（SHOULD FIX，7/10）、GENERIC-1@S46（SHOULD FIX，9/10）、PERFORMANCE@G9（SHOULD FIX PRE-EXISTING，7/10）、STYLE@G9（SHOULD FIX，7/10）

#### F28 [SHOULD FIX] generateLocaleConfig 将半翻译语言列入系统应用语言

- 来源：第一轮（整仓 @07d2778） · 共识：5/20 位 reviewer（5 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/build.gradle.kts:80`、`AppLanguage.kt:30`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/build.gradle.kts:80`；另见 `app/src/main/res/resources.properties:3`、`app/src/main/res/values-ru/strings.xml:54`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:17`、`app/src/main/res/values-de/strings.xml:1`、`app/src/main/res/values-es/strings.xml:114`、`app/src/main/res/values-de/strings.xml:61`、`app/src/main/res/values-ja/strings.xml:119`、`app/src/main/res/values-ja/strings.xml:147`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:15-24`、`app/src/main/res/values/strings.xml:65`、`app/src/main/res/values-ja/strings.xml:64`、`app/src/main/res/values-de/strings.xml:59`

`generateLocaleConfig = true`（app/build.gradle.kts:80，配合 `resources.properties`）让 Android 13+ 的系统“应用语言”列表列出 de、es、ja、ko、ru，而它们只翻译了约 90–301 条（基准约 572–590 条），youmo 新增的字符串均未翻译。
这与 `AppLanguage.kt` 只提供完整翻译语言（en、zh-CN、zh-TW）的设计相矛盾：用户在系统中选德语等语言后界面大部分是英文，应用内选择器则显示“跟随系统”。
部分译文还是过时或错误的上游文本，例如 ru `about`“О fcitx5 для Android”、ru `reset`“Перезагрузить”、de 拼写错误“Dataei”、es:114、ja:119、ja:147，以及被 youmo 改义的 `virtual_keyboard` 仍保留旧含义。
修复方案不一：设置 `androidResources.localeFilters` 为 en、zh-rCN、zh-rTW（CORRECTNESS-1@S48 也提到 `resourceConfigurations`），删除这五个 `values-*` 目录，或（CORRECTNESS-1@S46）通过 `android:localeConfig` 提供手写的 `xml/locales_config.xml`。

- 可能的运行时影响：Android 13+ 用户在系统设置中为本应用选择德语、日语等语言后，界面大部分回退为英文，应用内的语言选项还显示为“跟随系统”。
- 子论断 F28.b 被否定：「de、es、ja、ko 和 ru 文件覆盖了 575 条字符串中的 118 到 301 条，并含有过时的上游文本，但 generateLocaleConfig 仍将它们作为应用语言提供。」→ app/src/main/res/values-ko/strings.xml:1-93（ko 有 90 条，低于所称的 118 到 301 的范围；es 有 118 条，ru 有 301 条）
- 提出者：CORRECTNESS-1@S46（SHOULD FIX，8/10）、CORRECTNESS-1@S48（SHOULD FIX，8/10）、CORRECTNESS-2@S48（SHOULD FIX，9/10）、GENERIC-1@S48（SHOULD FIX，7/10）、STYLE@G9（SHOULD FIX，7/10）

#### F29 [SHOULD FIX] ChineseNumbers 用默认 locale 格式化分钟，输出本地化数字

- 来源：第一轮（整仓 @07d2778） · 共识：5/9 位 reviewer（4 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`ChineseNumbers.kt:143`、`VoiceHold.kt:44`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:143`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbersTest.kt:80`

`ChineseNumbers.kt:143` 的 `"%02d".format(minutes)` 使用 `Locale.getDefault()`；在 ar、fa（CORRECTNESS-2@S14 还列出 bn、mr、ne）等使用非拉丁数字的系统语言下，「十点零五分」变成 `10点٠٥分`，而其他路径都用与 locale 无关的模板生成数字。CORRECTNESS-2@S22 在 JDK 17 上确认了 ar-EG 与 fa-IR 的输出。
`ChineseNumbersTest`（ChineseNumbersTest.kt:80）不覆盖 locale；CORRECTNESS-2@S22 指出 Android lint 的 DefaultLocale 检查不会检查这个纯 JVM 模块。
建议改为 `minutes.toString().padStart(2, '0')` 或 `String.format(Locale.ROOT, "%02d", minutes)`，并加一个临时把默认 locale 设为阿拉伯语、在 `finally` 中恢复的测试用例。严重度不一：CORRECTNESS-1@S14 为 NIT，其余四票为 SHOULD FIX。

- 可能的运行时影响：系统语言为阿拉伯语、波斯语等时，语音输入的时间分钟会以本地化数字写入文本（如 10点٠٥分）。
- 提出者：CORRECTNESS-1@S14（NIT，8/10）、CORRECTNESS-2@S14（SHOULD FIX，7/10）、GENERIC-1@S14（SHOULD FIX，7/10）、CORRECTNESS-2@S22（SHOULD FIX，8/10）、TESTING@G4（SHOULD FIX，8/10）

#### F30 [SHOULD FIX] 调试日志记录每个 fcitx 事件的全文，包括密码框输入

- 来源：第一轮（整仓 @07d2778） · 共识：5/12 位 reviewer（3 SHOULD FIX, 1 NIT, 1 SHOULD FIX PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:401`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:401`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Timber.kt:30`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Timber.kt:13`、`.github/ISSUE_TEMPLATE/bug_report.yaml:36`

`Fcitx.kt:401` 的 `Timber.d` 消息 `Handling $event` 通过 data class 的 `toString` 打印每个事件的全部内容：提交文本、preedit、候选和按键 unicode，密码框也不例外。debug 构建或用户开启 `verbose_log` 时，`VerboseTree` 会放行这条日志（utils/Timber.kt:13、:30）；LogActivity 可以导出日志，bug_report.yaml:36 还要求用户把日志附到公开 issue 上，这违背 PRIVACY.md 中敏感输入不留存的承诺。
修复：只记录 `event.eventType`，或对 Commit、ClientPreedit、InputPanel、Candidate（以及 Key）事件的内容做脱敏，可参照 ClearURLs 在 release 中脱敏日志的做法。PERFORMANCE@G7 另指出这条字符串在 release 中也会在每个按键时先被格式化、再被 ConciseTree 丢弃，建议只在调试日志开启时才构建。CORRECTNESS-1@S29 把它标为 PRE-EXISTING。

- 可能的运行时影响：开启详细日志或使用 debug 构建时，用户在密码等敏感输入框里打的字会写进可导出、可能被附到公开 issue 的日志。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S29（SHOULD FIX PRE-EXISTING，9/10）、CORRECTNESS-2@S29（SHOULD FIX，7/10）、GENERIC-1@S29（SHOULD FIX，8/10）、STYLE@G7（SHOULD FIX，6/10）、PERFORMANCE@G7（NIT，7/10）

#### F31 [SHOULD FIX] CI 工作流缺少 permissions 限权，并信任上游 cachix 缓存

- 来源：第一轮（整仓 @07d2778） · 共识：5/6 位 reviewer（4 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`.github/workflows/nix.yml:3-23`、`.github/workflows/pull_request.yml:1-11`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`.github/workflows/nix.yml:8`；另见 `.github/workflows/nix.yml:3`、`.github/workflows/nix.yml:17`、`.github/workflows/nix.yml:20`、`.github/workflows/nix.yml:22`、`.github/workflows/pull_request.yml:11`、`.github/workflows/unit_test.yml:28`、`.github/workflows/emulator_test.yml:18`

nix.yml 与 pull_request.yml 都没有 `permissions:` 块（unit_test.yml、emulator_test.yml 已设 `contents: read`），nix.yml 在 push 到 main 时把默认范围的 `GITHUB_TOKEN`（GENERIC-1@S1 指出还有 `CACHIX_AUTH_TOKEN`）交给仅按可移动 tag 固定的第三方 action（如 `cachix/install-nix-action@v31`）。
CORRECTNESS-1@S1、CORRECTNESS-2@S1、GENERIC-1@S1 还指出 nix.yml 把上游 fcitx5-android 的 cachix 缓存作为受信 substituter，而本项目声明与上游无关，其 token 也无法推送到该缓存。
建议：两个工作流都加 `permissions: contents: read`，第三方 action 固定到 commit SHA，改用本项目自有的缓存或去掉 cachix 步骤。
严重度不一：四票 SHOULD FIX，TESTING@G1 为 NIT；PERFORMANCE@G1 在 G1-35 中也附带指出权限未收窄。

- 提出者：CORRECTNESS-1@S1（SHOULD FIX，6/10）、CORRECTNESS-2@S1（SHOULD FIX，7/10）、GENERIC-1@S1（SHOULD FIX，6/10）、STYLE@G1（SHOULD FIX，7/10）、TESTING@G1（NIT，7/10）

#### F32 [SHOULD FIX] 文字版构建中残留的 Voice 设置值让列表显示为空

- 来源：第一轮（整仓 @07d2778） · 共识：5/21 位 reviewer（3 SHOULD FIX, 2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/text/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:26`、`app/build.gradle.kts:19`、`AppPrefs.kt:113`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:109`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:113`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`、`app/build.gradle.kts:19`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceCategory.kt:102`、`app/src/text/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:26`、`app/build.gradle.kts:66-75`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176-177`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:109-114`

从语音版导入设置后，文字版里存储的 `Voice` 仍会解码为 `Voice`，但它不在 `entryValues` 中，列表的摘要为空，也没有选中项。
修复：当 `!VoiceFeature.AVAILABLE` 时把 `Voice` 解码为 `None`。

- 可能的运行时影响：从语音版换装文字版或导入备份后，长按空格不再执行任何动作，设置页也无法显示该值。
- 跨模块组合并：G7-92、G8a-08、G8b-56（文字版沿用 Voice 设置导致长按空格静默失效；文本版中残留的 `Voice` 长按空格设置静默无效）
- 提出者：CORRECTNESS-1@S31（NIT，7/10）、GENERIC-1@S37（SHOULD FIX，7/10）、CORRECTNESS-1@S51（NIT，7/10）、CORRECTNESS-2@S51（SHOULD FIX，7/10）、GENERIC-1@S51（SHOULD FIX，7/10）

#### F34 [SHOULD FIX] 离开页面会取消自定义短语保存后的引擎重载

- 来源：第一轮（整仓 @07d2778） · 共识：5/21 位 reviewer（5 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:305`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:306-317`、`Engines.kt:862`、`EngineBridge.kt:146`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:306`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:305`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:312`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:316`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:264`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:58`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:146`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:304`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:849`

PinyinCustomPhraseFragment 在 onStop 中用 fragment 的 lifecycleScope 启动保存和 reloadPinyinCustomPhrase()，返回导航（或关闭动画、旋转）时 onDestroy 随即取消该作用域，文件已写入但引擎不重载；:312 的注释表明作者期望重载在页面消失后仍执行。
后果有分歧：CORRECTNESS-1 认为 Engines.phrases() 保留旧列表，下一次在键盘上置顶或删除会经 savePhrases → CustomPhraseManager.write 用旧列表覆盖文件，丢失编辑器的修改；CORRECTNESS-2 和 GENERIC-1 认为键盘只是在下次重载前继续使用旧短语。CORRECTNESS-2 注明此模式来自上游。
建议：像 PinyinDictionaryFragment.onStop 那样在超出页面生命周期的作用域（viewModel.fcitx.launchOnReady）中保存并重载，或用 NonCancellable；CORRECTNESS-1 还建议长期让引擎在写入前重新读取文件。

- 可能的运行时影响：编辑自定义短语后离开页面，键盘可能继续使用旧短语，甚至之后用旧列表覆盖用户的修改。
- 跨模块组合并：G8a-62、G7-24（引擎保存自定义短语时不检查文件是否已被编辑器改过）
- 提出者：CORRECTNESS-1@S41（SHOULD FIX，7/10）、CORRECTNESS-2@S41（SHOULD FIX，6/10）、GENERIC-1@S41（SHOULD FIX，6/10）、CORRECTNESS-1@S31（SHOULD FIX，6/10）、CORRECTNESS-2@S31（SHOULD FIX，6/10）

#### F35 [SHOULD FIX] readings.tsv 读音笔误会静默删除发布词库中的词

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:54`、`PinyinDictReader.kt:69-71`、`Main.kt:692-697`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:54`、`PinyinDictReader.kt:69-71`、`Main.kt:692-697`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:692`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:692-697`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:692-696`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:289`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:298`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:54`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:59`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:68`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:69`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:70`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:352`

`PinyinDictReader.read()` 会丢弃 readings.tsv（及 misreadings.tsv）所列词的全部 libime 读音，随后 `corrected()` → `add()` 遇到含未知音节的人工读音时直接跳过，只在日志里留下 skipped 计数。
Main.kt:692-697 对 `--readings` 只检查列数，不校验音节是否存在、个数是否等于字数；CORRECTNESS-1@S5 与 CORRECTNESS-2@S5 指出 misreadings 的音节同样没有存在性检查。
EngineDataPlugin 每次 app 构建都传入这些文件，所以一个笔误就让该词从发布词库消失而构建仍然通过。
建议：像 `lexicon()` 校验 add.tsv（Main.kt:289/298）那样校验这些列表，遇到未知音节或音节数与字数不符时抛出带行号的 `SourceException`（libime 自身文本仍保留跳过并计数）；GENERIC-1@S5 另建议为 readings.tsv 加类似 `theListShippedReadsAndEveryReadingIsSyllables` 的测试，CORRECTNESS-2@S5 建议加 MainTest 用例。

- 提出者：CORRECTNESS-1@S5（SHOULD FIX，8/10）、CORRECTNESS-2@S5（SHOULD FIX，9/10）、GENERIC-1@S5（SHOULD FIX，8/10）、STYLE@G2（SHOULD FIX，9/10）

#### F36 [SHOULD FIX] apply.py:80 的 f-string 需要 Python 3.12+

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lexicon/tools/apply.py:79-80`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lexicon/tools/apply.py:80`

apply.py:80 在双引号 f-string 的替换字段内再次使用双引号（`"'".join(suggestion[word])`），只有 Python 3.12+（PEP 701）接受这种写法。
在 Python 3.10/3.11（Ubuntu 22.04、Debian 12）上整个脚本在执行任何操作前就报 SyntaxError，而仓库与 CI 都没有固定 Python 版本（STYLE@G2 指出 `flake.nix` 只写了 `python3`，且 lexicon/tools 的其它脚本避开了这种写法）。
建议：先在上一行把 `"'".join(suggestion[word])` 存入局部变量，再在 f-string 中用 `{变量!r}`。
TESTING@G2 在其 apply.py 缺少测试的发现（G2-18）中也提到了此问题。

- 提出者：CORRECTNESS-1@S4（SHOULD FIX，9/10）、CORRECTNESS-2@S4（SHOULD FIX，9/10）、GENERIC-1@S4（SHOULD FIX，9/10）、STYLE@G2（SHOULD FIX，9/10）

#### F37 [SHOULD FIX] 光标移到位置 0 时未清除记住的配对

- 来源：第一轮（整仓 @07d2778） · 共识：4/15 位 reviewer（4 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`CursorRange.kt:47-48`、`EditingSession.kt:123`、`FcitxInputMethodService.kt:754`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:123`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:111`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:105`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`

无组合文本时 `composing` 是空区间 `CursorRange(0,0)`，而 `contains(0)` 对它为真，所以 `EditingSession.kt:123` 在意外收到 (0,0) 回报时不调用 `pairs.forget()`。用户点到文本开头，或应用用 `Editable.clear()`、WebView/Compose 字段清空输入框而不 `restartInput` 时都会触发。
结果 `quoteOpen` 保持为真，`FcitxInputMethodService.kt:754` 跳过 fcitx 的 `reset()`，下一个引号键打出孤立的 ”；与开头字符相同的陈旧闭合符也可能被跨过而不是插入。AutoPairsTest（约 105-113 行）点到 0 后又移回 1，由后一步完成清除，因此掩盖了此缺陷。
各票一致建议把条件改为 `selStart != selEnd || composing.isEmpty() || !composing.contains(selStart)`，并在点到 0 后立即断言配对已清除（CORRECTNESS-2@S21、GENERIC-1@S21 建议用 “ 以便观察 `quoteOpen`）。

- 可能的运行时影响：用户点到文本开头或应用清空输入框后，下一个引号键会打出孤立的 ”，陈旧的闭合符也可能被跨过而不插入。
- 提出者：CORRECTNESS-2@S14（SHOULD FIX，8/10）、CORRECTNESS-1@S21（SHOULD FIX，9/10）、CORRECTNESS-2@S21（SHOULD FIX，8/10）、GENERIC-1@S21（SHOULD FIX，8/10）

#### F38 [SHOULD FIX] T9Set 与 `t9` 命令缺少单元测试

- 来源：第一轮（整仓 @07d2778） · 共识：4/15 位 reviewer（4 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:315`、`Main.kt:322`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ShuangpinSetTest.kt:40`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:315`、`Main.kt:322`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ShuangpinSetTest.kt:40`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/T9Set.kt:20`；另见 `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:211-216`

src/test 中没有任何测试引用 `T9Set`：T9 数字转换、排除含拉丁字母或大写音节的样本、保留 context、`t9` 命令以及 `t9`/`t9-strict` 分词器选择（`abbreviations = scheme == "t9"`）都未覆盖；c0140d6b（「T9 sets keep their context」）修复的问题没有回归测试，违反 CLAUDE.md「新逻辑在同一改动中加单元测试」。
三个 S24 阶段把 `PredictLearning` 及 `predict`/`predict-learn`/`predict-set` 命令（GENERIC-1@S24 另含 `lm`）未测试归入同一条；CORRECTNESS-1@S24 指出所发布的 `Predictor.USER_WEIGHT` 正是由 `predict-learn` 选出；TESTING 把预测部分单列（见 G5-15）。
修复：仿照 ShuangpinSetTest 新增 T9SetTest（转换为数字、拉丁样本被排除、经 `runCli("t9", …)` 保留 context、在内存数据上跑 `pinyin --scheme t9`），并为 PredictLearning 及 `t9`/`predict-*` 命令补小型测试或 CLI 冒烟测试。

- ⚠️ 验证说明：代码层面的论断成立，但 verifier 否定了上面所说的运行时后果：c0140d6b 中的修复位于共享的 writeSet（lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:315）中，writeT9Set 会调用它（Main.kt:322）。同一提交修改了 lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ShuangpinSetTest.kt:40,45，断言上下文会被写入，因此该修复确实有回归测试。另一部分（T9Set、t9 命令以及 Main.kt:253 处的选择没有测试）成立。
- 子论断 F38.a 被否定：「T9Set、t9 命令以及 t9/t9-strict 切分器的选择都没有测试，而且提交 c0140d6b 修复了一个 T9 上下文 bug 却没有添加回归测试。」→ 与 F38 相同的证据：ShuangpinSetTest.kt:40,45（在 c0140d6b 中修改）检查 Main.kt:315 处共享的 writeSet 会保留上下文。
- 提出者：TESTING@G5（SHOULD FIX，9/10）、CORRECTNESS-1@S24（SHOULD FIX，9/10）、CORRECTNESS-2@S24（SHOULD FIX，8/10）、GENERIC-1@S24（SHOULD FIX，9/10）

#### F39 [SHOULD FIX] 词库扩展名区分大小写导致无法导入，测试反而固化该缺陷

- 来源：第一轮（整仓 @07d2778） · 共识：4/15 位 reviewer（3 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:210`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionary.kt:16`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionaryTypeTest.kt:49`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:210`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/dict/TextDictionary.kt:30`

`PinyinDictionary.Type.fromFileName` 区分扩展名大小写，`PinyinDictionaryFragment.kt:210` 用文档提供方给出的显示名做判断，`词库.SCEL`、`WORDS.TXT` 等合法文件会以 invalid dict 被拒绝；`PinyinDictionaryTypeTest.kt:49` 却把这一行为固化下来，而它自己的 KDoc 称这是「a limitation rather than a decision」。
修复方案有分歧：CORRECTNESS-1@S50 主张在 `fromFileName` 和 `TextDictionary` 的 init 检查（TextDictionary.kt:30）都统一大小写；CORRECTNESS-2@S50 主张只在导入路径（fragment 检查、`importFromInputStream`、`readWords` 的临时文件后缀）转小写，对已存文件保持 `fromFileName` 严格；GENERIC-1@S50 主张 `fromFileName` 不区分大小写；TESTING@G7 只建议删除该测试，或 `@Ignore` 并写明期望行为。前三票都建议修好后反转这个测试。

- 可能的运行时影响：用户导入扩展名为大写（如 `.SCEL`、`.TXT`）的合法词库时会被拒绝。
- 提出者：CORRECTNESS-1@S50（SHOULD FIX，8/10）、CORRECTNESS-2@S50（SHOULD FIX，9/10）、GENERIC-1@S50（SHOULD FIX，8/10）、TESTING@G7（NIT，8/10）

#### F40 [SHOULD FIX] ThemePresetTest 的颜色数量断言永远成立，KDoc 夸大了覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（1 SHOULD FIX, 3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemePresetTest.kt:94`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemePresetTest.kt:92`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemePresetTest.kt:32`、`app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemePresetTest.kt:94`

`colours()` 是手写的 21 项 `mapOf`，`everyPresetDefinesEveryColour`（ThemePresetTest.kt:92）检查 `size == 21`，永远为真。Theme 新增颜色而有人忘了同步修改 `colours()` 时，丢掉新颜色的 `deriveCustom*` 仍能通过所有检查，与 :32 处 KDoc 所说的「自动纳入」不符。
修复：用反射枚举 `Theme` 的 `Int` 颜色 getter（或 `Theme.Custom` 构造参数）来生成这个 map，并删除 size 断言；否则改正 KDoc，删除这个不可能失败的测试。

- 提出者：CORRECTNESS-1@S50（NIT，9/10）、CORRECTNESS-2@S50（NIT，9/10）、GENERIC-1@S50（SHOULD FIX，9/10）、TESTING@G7（NIT，9/10）

#### F41 [SHOULD FIX] 删除主题的菜单项标题为「保存」

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（1 SHOULD FIX, 3 NIT PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:471`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:476`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Menu.kt:32-34`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:471`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/FcitxKeyPreference.kt:24`

`CustomThemeActivity` 第 471 行 `menu.item(R.string.save, R.drawable.ic_baseline_delete_24, …)` 给删除按钮用了「保存」标题，且紧挨真正的保存按钮；TalkBack、长按提示和溢出菜单项（`SHOW_AS_ACTION_IF_ROOM`）都显示 Save。
三方标为 PRE-EXISTING 的 NIT，GENERIC-1 为 SHOULD FIX（说明继承自上游但未加标记）；STYLE 另指出 `FcitxKeyPreference.kt:24` 从 `DialogSeekBarPreference` styleable 读取 `FcitxKeyPreference_*`。
建议：改用已有的 `R.string.delete` 或 `R.string.delete_theme`。

- 可能的运行时影响：TalkBack、长按提示和溢出菜单把删除主题按钮显示或朗读为「保存」。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S44（NIT PRE-EXISTING，9/10）、CORRECTNESS-2@S44（NIT PRE-EXISTING，9/10）、GENERIC-1@S44（SHOULD FIX，9/10）、STYLE@G8（NIT PRE-EXISTING，9/10）

#### F42 [SHOULD FIX] zh-rTW 码表词库错误提示给出错误的文件扩展名

- 来源：第一轮（整仓 @07d2778） · 共识：4/11 位 reviewer（4 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/res/values-zh-rTW/strings.xml:200`、`TableInputMethodFragment.kt:280`、`Dictionary.kt:14-18`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/res/values-zh-rTW/strings.xml:200`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/TableInputMethodFragment.kt:280`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/TableInputMethodFragment.kt:342`、`app/src/main/res/values-zh-rTW/strings.xml:199`

values-zh-rTW/strings.xml 中 `exception_table_dict_filename` 要求文件为 `.conf 或 .conf.in`，而默认和 zh-rCN 字符串写的是 `.dict` 或 `.txt`。
繁体中文用户在 TableInputMethodFragment.kt:280 和 :342 处选错文件时，会被引导去选择错误的文件类型；STYLE@G9 认为该文本是从第 199 行复制来的。
修复：改为 `（ .dict 或 .txt ）`。

- 可能的运行时影响：繁体中文用户导入码表词库出错时，会被提示改选 .conf 或 .conf.in 文件，而应用实际要求的是 .dict 或 .txt。
- 提出者：CORRECTNESS-1@S46（SHOULD FIX，9/10）、CORRECTNESS-2@S46（SHOULD FIX，9/10）、GENERIC-1@S46（SHOULD FIX，9/10）、STYLE@G9（SHOULD FIX，9/10）

#### F43 [SHOULD FIX] Gradle 发行包没有用 distributionSha256Sum 固定校验

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（2 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`gradle/wrapper/gradle-wrapper.properties:4`、`build-logic/gradle/wrapper/gradle-wrapper.properties:4`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`gradle/wrapper/gradle-wrapper.properties:4`、`build-logic/gradle/wrapper/gradle-wrapper.properties:4`）
- 位置：`gradle/wrapper/gradle-wrapper.properties:4`；另见 `build-logic/gradle/wrapper/gradle-wrapper.properties:1`

`gradle/wrapper/gradle-wrapper.properties`（build-logic 下的同名文件是指向它的符号链接）没有 `distributionSha256Sum`，`gradle/` 下也没有 `verification-metadata.xml`；CI 的 `setup-gradle@v6` 只校验 wrapper jar，不校验发行包 zip，CORRECTNESS-2@S3 还指出 nix.yml 不经 `setup-gradle` 直接运行 `./gradlew`，连 jar 校验也没有。
README 强调其他下载都做 SHA-256 校验，而构建 APK 的工具链只靠 HTTPS；GENERIC-1@S3 补充约 60 个插件和依赖制品（含 `dev.detekt` 2.0.0-alpha.6）也未校验。CORRECTNESS-1@S3 注明该文件与上游相同。
建议：用 `./gradlew wrapper --gradle-version 9.6.1 --gradle-distribution-sha256-sum <sha>` 重新生成，或直接加 `distributionSha256Sum=`；CORRECTNESS-1@S3 与 GENERIC-1@S3 还建议用 `--write-verification-metadata sha256` 生成依赖校验元数据。
严重度不一：两票 SHOULD FIX，两票 NIT。

- 提出者：CORRECTNESS-1@S3（SHOULD FIX，8/10）、CORRECTNESS-2@S3（NIT，9/10）、GENERIC-1@S3（SHOULD FIX，8/10）、GENERIC-1@S2（NIT，7/10）

#### F44 [SHOULD FIX] NewWordsTest 草图测试未断言「碰撞只会多计」的上界

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（1 SHOULD FIX, 3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:81-83`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:262`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:82`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:82-83`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:76-82`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:83`

NewWordsTest 的 count-min sketch 测试最后一条断言 `count(keys[0]) >= 1` 只重复了循环中 i=0 已检查的下界，测试名与注释承诺的「collision only over」上界从未断言。
CORRECTNESS-2@S6 还指出整个测试套件都没检查上界或 `count` 取两行中的最小值，退化为 max() 或单行也能通过所有测试，这会让 PageCleaner 静默丢弃更多罕见行。
修复建议不一：CORRECTNESS-1@S6 与 CORRECTNESS-2@S6 建议加一个无碰撞的宽草图并断言每个计数精确（后者还要求未加入的 key 读出 0）；GENERIC-1@S6 建议在 16 格、40 个 key 的固定哈希下断言至少一个计数超过真值；TESTING@G2 建议删掉该行或断言如总添加次数这样的上界。

- 提出者：CORRECTNESS-1@S6（NIT，8/10）、CORRECTNESS-2@S6（SHOULD FIX，8/10）、GENERIC-1@S6（NIT，8/10）、TESTING@G2（NIT，9/10）

#### F45 [SHOULD FIX] 用 session.reads('2') 判断九键会误伤数字编码码表

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（2 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`K/engine/host/Keyboard.kt:78`、`K/engine/table/TableSession.kt:101-105`、`TableSession.kt:84`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Keyboard.kt:78`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Keyboard.kt:114`、`app/src/main/cpp/androidengine/androidengine.cpp:322`

Keyboard.kt:78 与 :114 用 `session.reads('2')` 判断九键会话，但用户导入的数字编码码表（如 电报码）也满足该条件（CORRECTNESS-1@S8 补充：engine-t9 上的硬件 Esc 键也走此路径，`androidengine.cpp:322` 把所有 Escape 映射为该事件）。
结果：在这类码表中未输入时 Escape 被吞掉（`handled = true`）而不到达应用；私用区字符被当作 `Action.Syllable`，`TableSession` 忽略它，字符丢失。
建议：显式传入九键标志（如 `Keyboard(session, nineKeys = im == T9)` 或 `Session.nineKeys`），或给 重输 键分配专用私用区字符而非 Escape。严重度不一：CORRECTNESS-1@S8 与 STYLE@G3 为 SHOULD FIX，另两票为 NIT。

- 可能的运行时影响：在用户导入的数字编码码表中，未输入时 Esc 键被吞掉，私用区字符被丢弃。
- 提出者：CORRECTNESS-1@S8（SHOULD FIX，7/10）、CORRECTNESS-2@S8（NIT，6/10）、GENERIC-1@S8（NIT，7/10）、STYLE@G3（SHOULD FIX，8/10）

#### F46 [SHOULD FIX] 引号提前闭合的短语被解析为空短语条目

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（2 SHOULD FIX, 2 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`K/core/FcitxUtils.kt:26`、`K/data/quickphrase/QuickPhraseEntry.kt:26`、`T/data/quickphrase/QuickPhraseEntryTest.kt:155-159`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntry.kt:25`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/core/FcitxUtils.kt:26`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntryTest.kt:104`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntryTest.kt:155`

对 `k "a"b"`，`FcitxUtils.unescapeForValue`（FcitxUtils.kt:23-27）在引号提前闭合时返回空串，`fromLine` 因此生成 `QuickPhraseEntry("k", "")`，正是测试中写明“callers must not create”的空短语条目；编辑器显示空白短语，保存时该行变为 `k ` 或在下次保存时消失。
建议：`fromLine` 在短语反转义为空时返回 null（或让 `unescapeForValue` 报告失败），并在拒绝行区域加入 `k ""`、`k "a"b"` 用例。CORRECTNESS-1@S15 还提到两套 fcitx 反转义实现不一致（见 G3a-08）。
分歧：CORRECTNESS-1@S7 与 CORRECTNESS-2@S15 标为 PRE-EXISTING NIT（代码来自上游，未改动），并称未核对 fcitx5 源码、推测 fcitx 会跳过该行；CORRECTNESS-1@S15 与 GENERIC-1@S15 标为 SHOULD FIX。

- 可能的运行时影响：用户快捷短语文件中引号提前闭合的行会变成空白短语条目，保存后该行内容被改写。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S7（NIT PRE-EXISTING，5/10）、CORRECTNESS-1@S15（SHOULD FIX，8/10）、CORRECTNESS-2@S15（NIT PRE-EXISTING，6/10）、GENERIC-1@S15（SHOULD FIX，8/10）

#### F48 [SHOULD FIX] 码表中选过的共享词被删除后，重启又出现

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（4 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:106`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:172`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:246`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:105`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:106`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:172`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:246`

在码表（如 五笔）中选中 `SharedWords`/CodedWords 提供的共享词时，`TableSession.take`（TableSession.kt:246）以 index -1 记为 PICKED，只进入 `savedPicks`；回放时第 172 行 `if (index < 0) user.keep(code, text)` 把它变成该码表的已存词组，排在码表自身条目之前。
`forgetText`（第 105-106 行）只查 `saved` 和 `autoPhrases`，不查 `savedPicks`，所以在拼音中删除（`Engines.removeWords` → `forgetInTables`）或在别处忘记后，下次启动该词又在码表中出现，破坏「一个用户词库」；CORRECTNESS-1/2 还指出删除 ADDED 词或拼音的长按忘记根本不到达码表。
建议：让 `forgetText` 同时删除以 `\t$text` 结尾的 `savedPicks` 键并记 FORGOT（CORRECTNESS-2 另建议把共享词的选择记为回放不会提升的记录类型），并加「选中 → 删除 → 重启」的测试。

- 可能的运行时影响：用户在拼音中删除或忘记的词，重启后又会作为码表输入法的候选出现。
- 提出者：CORRECTNESS-1@S12（SHOULD FIX，8/10）、CORRECTNESS-2@S12（SHOULD FIX，8/10）、GENERIC-1@S12（SHOULD FIX，8/10）、GENERIC-1@S19（SHOULD FIX，7/10）

#### F49 [SHOULD FIX] 导入检查未按引擎方式读码表，坏表能导入却无法打字

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（4 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableText.kt:140`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableDictionary.kt:19-23`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:750-766`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableText.kt:140`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CodeTableReaderTest.kt:244`

`check` 只运行 `CodeTableReader`，它不要求 键码 和 码长，也不解析 组词规则；而引擎首次使用时会构造 `TableDictionary`（缺 键码 或 码长 非正数时抛出），并由 `PhraseRules.parse` 用 `require` 解析每条规则。
因此缺 键码/码长 或规则畸形（如 `a10=…`、`e2=p11+`、`e2=p1x`）的码表能通过 `ImportedTables.install`，随后在 `Engines.addedTable` 首次加载时失败，该输入法的所有按键都交给应用、用户看到「无法读取输入法」，违背文档「宁可导入失败也不在打字时失败」；TESTING 指出现有测试只覆盖读取器错误。
建议：在 `check` 中构建 CodeTable、构造 `TableDictionary` 并运行 `codePhrases`，把 DataFormatException/IllegalArgumentException 转为 SourceException，并在 `CodeTableReaderTest.aTableIsCheckedAsTheEngineWillReadIt`（第 244 行）加这两类用例。

- 可能的运行时影响：用户导入的有缺陷码表能导入成功，但使用时报「无法读取输入法」，该输入法的按键全部失效。
- 子论断 F49.d 被否定：「`check` 不会构建 TableDictionary 或 PhraseRules，因此没有 码长 或含有 `e2=p1x` 的码表能通过导入，却会在首次使用时失败，而它的测试只覆盖读取器错误。」→ `e2=p1x` 是一条有效规则：`x` 映射到键 -3（倒数第三个键），并能通过两处 require 检查，因此包含它的码表在 lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/PhraseRules.kt:62-65 处构建 TableDictionary 时不会出错（关于没有 码长 的那一半说法属实）
- 提出者：CORRECTNESS-1@S12（SHOULD FIX，8/10）、CORRECTNESS-2@S12（SHOULD FIX，8/10）、GENERIC-1@S12（SHOULD FIX，8/10）、TESTING@G3（SHOULD FIX，8/10）

#### F50 [SHOULD FIX] 模型权重从映射的资源复制到Java堆

- 来源：第一轮（整仓 @07d2778） · 共识：4/21 位 reviewer（3 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:70`、`Engines.kt:846`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:70`、`Engines.kt:846`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:80`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:836`、`app/src/main/cpp/matrix-kernel.cpp:66`、`app/src/main/cpp/matrix-kernel.cpp:78`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:180`、`app/src/main/java/org/fcitx/fcitx5/android/core/NativeMatrixKernel.kt:11`

每个张量都被复制进堆数组：精排模型约 25 MB、逐键模型约 4.5 MB，在应用生命周期内一直占用；26 MB 的复制发生在首次 REFINE 时的 fcitx 线程上（Engines.kt:836），下一个按键要等它完成，而 `EngineBridge.asset` 已经映射了该文件。
建议：把 I8 权重保留为映射缓冲区的切片，并为 `MatrixKernel` 提供 direct-buffer 路径（`GetDirectBufferAddress`），加载只剩解析头部，权重成为可回收的页缓存；并通过 `CoreLog.d` 记录加载时间。

- 跨模块组合并：G3b-74、G6-10、G7-54（约 30 MB int8 权重被复制到 Java 堆；句子模型权重从映射的 APK 中复制进约 30 MB 的 Java 堆）
- 提出者：PERFORMANCE@G3（SHOULD FIX，6/10）、CORRECTNESS-2@S25（NIT，6/10）、PERFORMANCE@G6（SHOULD FIX，6/10）、PERFORMANCE@G7（SHOULD FIX，8/10）

#### F51 [SHOULD FIX] gestureConsumed 只在开启滑动时重置，测试固定了该潜在缺陷

- 来源：第一轮（整仓 @07d2778） · 共识：4/15 位 reviewer（2 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:224-230`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:356-363`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:224-230`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:356-363`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:230`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:224`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:356`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:259`

`KeyGestureRecognizer.resetForNextTouch`（KeyGestureRecognizer.kt:224-230）只在 `if (settings.swipe)` 内清除 `gestureConsumed`，而 `CustomGestureView` 对 Down、Up 和 Move 都会标记手势已消费；若某个监听器在不滑动的键上返回 true，该键此后不再触发点击，直到视图重建。
各票都检查了现有监听器（BaseKeyboard、KawaiiBarComponent、PickerGridAdapter 等），一致认为目前所有消费手势的监听器都在开启滑动的键上，今天不会触发。`KeyGestureRecognizerTest.kt:356` 把这一巧合固定为必需行为，修复反而会让测试报告回归。
建议把重置移到 `if (settings.swipe)` 之外（一行修改），并反转该测试，断言下一次点击有效。严重度不一：CORRECTNESS-2@S22、GENERIC-1@S22 为 SHOULD FIX，GENERIC-1@S14、CORRECTNESS-1@S22 为 NIT。

- 提出者：GENERIC-1@S14（NIT，8/10）、CORRECTNESS-1@S22（NIT，9/10）、CORRECTNESS-2@S22（SHOULD FIX，8/10）、GENERIC-1@S22（SHOULD FIX，8/10）

#### F52 [SHOULD FIX] 原生 int8 矩阵内核没有与 MatrixKernel.JVM 对比的测试

- 来源：第一轮（整仓 @07d2778） · 共识：4/15 位 reviewer（4 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:74-81`、`matrix-kernel.cpp:41-51`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:74-81`、`matrix-kernel.cpp:41-51`）
- 位置：`app/src/main/cpp/matrix-kernel.cpp:66`；另见 `app/src/main/cpp/matrix-kernel.cpp:38`、`app/src/main/cpp/matrix-kernel.cpp:51`、`app/src/main/cpp/matrix-kernel.cpp:95`、`app/src/main/cpp/native-lib.cpp:508`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:42`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:120`

设备上两个句子模型的每次 int8 乘法都走 NativeMatrixKernel（matrix-kernel.cpp 的 fourRows/dot），但 :lib:ime-core 的测试（SentenceModelTest、EnginesTest）和 lib/ime-eval 都只用 MatrixKernel.JVM，app 也没有对应的 androidTest。
剩余行（rows % 4）、剩余列（columns % 8、columns < 8）或尺寸检查出错只会悄悄降低手机上的重排序质量，所有检查仍然通过；CORRECTNESS-1@S25 还指出没有切回 JVM 内核的开关。
修复：增加 instrumented test（TESTING@G6 建议放在 text flavor，以便每周 emulator 任务运行），在 7×13、4×8、9×1027、0×0、1×1 等尺寸或随机尺寸上按相对容差比较 NativeMatrixKernel 与 MatrixKernel.JVM，并断言尺寸不符时抛 IllegalArgumentException。CORRECTNESS-2@S25 同时要求为 utf8FromJString 加一个 CJK Ext-B 往返测试（另见 G6-29）。

- 提出者：CORRECTNESS-1@S25（SHOULD FIX，7/10）、CORRECTNESS-2@S25（SHOULD FIX，8/10）、GENERIC-1@S25（SHOULD FIX，8/10）、TESTING@G6（SHOULD FIX，8/10）

#### F53 [SHOULD FIX] JNI 两侧的事件编号与 Offer 位仅靠注释同步

- 来源：第一轮（整仓 @07d2778） · 共识：4/21 位 reviewer（1 SHOULD FIX, 3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:230`、`androidengine_public.h:56-61`、`EnginesTest.kt:322`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:230`、`androidengine_public.h:56-61`、`EnginesTest.kt:322`）
- 位置：`app/src/main/cpp/androidengine/androidengine_public.h:56`；另见 `app/src/main/cpp/native-lib.cpp:792`、`app/src/main/cpp/androidengine/androidengine_public.h:37`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/Session.kt:38`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:230`

androidengine_public.h 中的 EngineEvent（0-13）和 EngineOffer 位必须与 ime-core Keyboard.kt 的 EngineEvent 常量，以及 Session.kt:38 中 enum class Offer 的声明顺序（EngineBridge.kt:230 用 1 shl ordinal 生成）保持一致；目前一致，但没有任何检查。
STYLE@G6 强调 Offer 位依赖枚举顺序：重排或插入一项会让长按菜单把 Forget、Pin、Block 等动作对应错，且没有测试会失败。
修复：给 Offer 显式位值（像 EngineEvent 那样），或加 JVM 测试固定 FORGET=1、PIN=2、UNPIN=4、BLOCK=8，或解析该头文件比对两侧编号。

- 提出者：CORRECTNESS-2@S25（NIT，7/10）、CORRECTNESS-2@S26（NIT，9/10）、TESTING@G6（NIT，6/10）、STYLE@G6（SHOULD FIX，8/10）

#### F54 [SHOULD FIX] 测试未覆盖 provider 返回显示名的分支

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（2 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:22-29`、`ContentResolver.kt:27`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:22-29`、`ContentResolver.kt:27`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:23`；另见 `app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:22`、`app/src/main/java/org/fcitx/fcitx5/android/utils/ContentResolver.kt:21`、`app/src/main/java/org/fcitx/fcitx5/android/utils/ContentResolver.kt:30`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:208`

测试只覆盖 `lastPathSegment` 回退；游标分支（`moveToFirst && !isNull(0)`）以及第三方 SAF provider 返回的 `DISPLAY_NAME`（如 `../../shared_prefs/x.xml`、`a/b`、`..`、空串或 NULL）都没有用例，而这才是真正的信任边界：名称经 `ContentResolver.kt:30` 清理后用作文件名（如 `PinyinDictionaryFragment.kt:208`）。
CORRECTNESS-2 指出空名称能通过 `ContentResolver.kt:21` 的 `isNull` 检查而不回退，结果为 null。严重度不一：GENERIC-1 与 TESTING 为 SHOULD FIX，CORRECTNESS-1/2 为 NIT。
建议：用 `Robolectric.setupContentProvider` 注册返回 `MatrixCursor` 的假 provider，断言穿越名称得到末段（`x.xml`、`x`、`b`），`..`、空串与 NULL 得到 null。

- 提出者：CORRECTNESS-1@S51（NIT，8/10）、CORRECTNESS-2@S51（NIT，8/10）、GENERIC-1@S51（SHOULD FIX，8/10）、TESTING@G8（SHOULD FIX，8/10）

#### F55 [SHOULD FIX] 句子模型或笔画数据资产缺失时没有日志

- 来源：第一轮（整仓 @07d2778） · 共识：4/18 位 reviewer（1 SHOULD FIX, 3 NIT）· 置信度 7/10 · 验证：✅ 已确认（`K/engine/host/Engines.kt:133-135`、`J/core/EngineBridge.kt:182`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:204`）
- 当前状态（5881d31）：仍存在（第二轮再次指出，见 F735）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:133`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:695`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:204`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:182`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:131-134`

Engines.kt:133（及 :695）的 `FileNotFoundException` 分支直接返回 null，不调用 `onError` 也不记日志；缺少模型的构建会退回仅解码器的准确率（README：top-1 88.5% 对 91.1%），而日志中看不出原因。CORRECTNESS-2@S8 指出 `openFd` 对压缩存储的资产也会抛该异常，但 EngineDataPlugin.kt:204 已设 `noCompress`，所以这只是防护。
建议：在该回退路径加一条日志（CORRECTNESS-1@S8 建议 INFO 级 `onError` 或日志，GENERIC-1@S8 建议 `CoreLog.d`）。

- 可能的运行时影响：若 noCompress 规则失效，用户设备上的 4M/25M 重排模型会被静默跳过，候选首选准确率下降且没有任何提示。
- 跨模块组合并：G3a-38、G1-12（重排模型依赖 noCompress，规则失效时被静默吞掉）
- 提出者：CORRECTNESS-1@S8（NIT，7/10）、CORRECTNESS-2@S8（NIT，7/10）、GENERIC-1@S8（NIT，7/10）、CORRECTNESS-2@S2（SHOULD FIX，7/10）

#### F56 [SHOULD FIX] 同文本提交用 selection.current 判断，光标留在词内

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（4 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`EditingSession.kt:230`、`CursorTracker.kt:12-17`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:230`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:154`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:158`

`EditingSession.kt:230` 在完成与组合文本相同的提交时比较 `selection.current`，而文件中其他路径都用 `latest`。若 `updateComposingText` 已为预编辑光标移动预测并发送了 `setSelection(p)`，而编辑器回报之前 fcitx 就提交了相同文本（如对原始拼音按回车），`current.start == target` 便不再发送移动，光标停在已提交词中间，之后的回报又与陈旧预测吻合，无人纠正。
`EditingSessionTest.kt:154` 把这一行为固定为预期，其 KDoc 也写明光标会停在待定位置；CORRECTNESS-2@S21 与 GENERIC-1@S21 指出 :158 注释中的方向键示例不会触发，因为 `handleArrowKey` 直接 `setSelection` 而不预测。CORRECTNESS-1@S21 注明未验证提交能否先于编辑器回报被处理。
建议改为比较 `selection.latest.start`，并把该测试反转为期望 `setSelection(2, 2)`；其余相同组合文本测试（:126、:140、:168）在此改动后仍成立。

- 可能的运行时影响：预编辑光标移动尚未得到编辑器回报时提交相同文本，光标会停在已提交词的中间。
- 提出者：GENERIC-1@S14（SHOULD FIX，7/10）、CORRECTNESS-1@S21（SHOULD FIX，7/10）、CORRECTNESS-2@S21（SHOULD FIX，7/10）、GENERIC-1@S21（SHOULD FIX，5/10）

#### F57 [SHOULD FIX] WordLists 改写词表不 fsync，读失败后会覆盖原文件

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`WordLists.kt:77`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:77`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:78`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:71`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:60`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:65`

`WordLists.write`（WordLists.kt:71-77）调用 `temp.writeText` 后直接 `renameTo`，没有先同步；f2fs 或未开 `auto_da_alloc` 的 ext4 断电后，`words.added`/`words.blocked` 可能为空或损坏，`read()` 随即静默返回空集，用户屏蔽的词重新出现，违背第 78 行注释「a whole list or the one before it」。同类的 `RecordStore.writeCounts` 在 rename 前调用 `raw.fd.sync()`，失败时还删除临时文件，而 `write()` 会留下 `$name.new`（GENERIC-1@S13）。
CORRECTNESS-2@S13 与 GENERIC-1@S13 还指出：`read()`（约 60-65 行）遇到 IOException 时从空集开始，下一次 add/block 会用只含新条目的集合覆盖整个文件，丢失此前所有添加或屏蔽的词；CORRECTNESS-1@S13 与 PERFORMANCE@G4 只提出 fsync 问题。
建议通过 `FileOutputStream` 写入、flush 并 `fd.sync()` 后再 rename，失败时删除临时文件；读失败后，在下一次读取成功前拒绝写入，或像 `RecordStore.moveAside` 那样把旧文件挪开。

- 可能的运行时影响：断电或读取失败后，用户添加或屏蔽的词表可能被清空，屏蔽的词会重新出现。
- 提出者：CORRECTNESS-1@S13（SHOULD FIX，7/10）、CORRECTNESS-2@S13（SHOULD FIX，6/10）、GENERIC-1@S13（SHOULD FIX，7/10）、PERFORMANCE@G4（SHOULD FIX，6/10）

#### F58 [SHOULD FIX] PunctuationComponent 不取消旧更新，旧映射可覆盖新映射

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（3 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`PunctuationComponent.kt:37-51`、`TextKeyboard.kt:301-312`、`KeyDefPreset.kt:35`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:38`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:37`、`app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:112`、`app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:86`、`app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:83`、`app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:290`

`updatePunctuationMapping`（PunctuationComponent.kt:32-51）每次状态更新都启动新协程，不取消上一个：`enabled == false` 分支在 Main.immediate 上同步完成，`enabled == true` 分支在 `fcitx.runOnReady` 中挂起。先「开」后「关」时，晚到的「开」结果会覆盖空映射并广播全角映射，而此时 `enabled` 已是 false。
影响的说法不同：CORRECTNESS-2@S34 认为 PopupComponent 会检查 `enabled` 不受影响，但键盘的 `onPunctuationUpdate` 键标签会过期；CORRECTNESS-2@S50 认为 `transform` 读 `mapping` 时不检查 `enabled`，会在 fcitx 标点关闭时提交中文标点。
修复：保存 Job 并在下次调用时取消（或用代际计数丢弃过期结果、在 `launch` 前捕获 `enabled`）。`FakeFcitxConnection.runOnReady` 从不挂起，现有测试抓不到这个竞态，需要增加能挂起的 fake。CORRECTNESS-2@S34 把它标为 PRE-EXISTING。

- 可能的运行时影响：关闭中文标点后，晚到的旧加载结果可能让键盘继续显示全角标点标签，甚至继续提交中文标点。
- ⚠️ 验证说明：代码层面的论断成立，但 verifier 否定了上面所说的运行时后果：竞态和过时的键标签确实存在（PunctuationComponent.kt:37-51、TextKeyboard.kt:301-312），但提交的文本从不经过该映射。按键把原始符号发送给 fcitx（KeyDefPreset.kt:35），弹出键提交的是 `keys` 而不是标签（PopupKeyboardUi.kt:225），弹出标签受 `enabled` 控制（PopupComponent.kt:138），因此不会提交任何中文标点。
- 子论断 F58.c 被否定：「没有测试覆盖相互重叠的更新；一次较慢的激活状态加载可能在其后发生的非激活状态更新之后写回非空映射，导致在 fcitx 的标点功能关闭时仍提交中文标点。」→ 确实没有测试覆盖更新相互重叠的情况（PunctuationComponentTest.kt:86-121），但过时的映射只会改变标签：提交以原始按键的形式发往 fcitx（KeyDefPreset.kt:35、PopupKeyboardUi.kt:225）
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S34（SHOULD FIX，7/10）、CORRECTNESS-2@S34（SHOULD FIX PRE-EXISTING，6/10）、CORRECTNESS-1@S50（SHOULD FIX，6/10）、CORRECTNESS-2@S50（SHOULD FIX，6/10）

#### F59 [SHOULD FIX] 九键音节列每次按键都重建全部视图

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（1 SHOULD FIX, 3 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:195`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:196`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:195`

每次 T9State 发射时 show() 会 removeAllViews，并在主线程重新创建最多 64 个 TextView（每个带 StateListDrawable、ColorDrawable 和点击 lambda），而只有 SHOWN = 4 个可见；这发生在打字路径上（PERFORMANCE）。
建议：复用现有子视图，只更新文字、可见性和点击动作，或改用小型 RecyclerView；只在数量变化时增删视图。PERFORMANCE 定为 SHOULD FIX，其余为 NIT。

- 提出者：CORRECTNESS-1@S38（NIT，7/10）、CORRECTNESS-2@S38（NIT，6/10）、GENERIC-1@S38（NIT，6/10）、PERFORMANCE@G8（SHOULD FIX，7/10）

#### F60 [SHOULD FIX] play/ 商店元数据仍为上游 Fcitx5 的内容

- 来源：第一轮（整仓 @07d2778） · 共识：4/11 位 reviewer（3 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/play/contact-email.txt:1`、`contact-website.txt:1`、`listings/zh-CN/full-description.txt:7-16`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/play/contact-email.txt:1`、`contact-website.txt:1`、`listings/zh-CN/full-description.txt:7-16`）
- 位置：`app/src/main/play/contact-email.txt:1`；另见 `build-logic/convention/src/main/kotlin/Versions.kt:20`、`app/src/main/play/listings/zh-CN/full-description.txt:11-16`

`app/src/main/play/` 仍是上游内容：标题“Fcitx5”/“小企鹅输入法”、上游的联系邮箱和网站、Youmo 已移除的功能（Anthy、Hangul、RIME 插件与插件系统、spellcheck），以及指向上游 0.1.3 说明 `114.txt` 的 `release-notes/en-US/default.txt`；CORRECTNESS-1@S46 还指出 Youmo 的 versionCode 也是 11x（Versions.kt:20）。
目前没有配置 Play publisher，也没有构建脚本读取它，但 F-Droid 和 IzzyOnDroid 读取这种 Triple-T 布局，据此生成的商店页面会与 README 的“与上游无关、请在这里而不是上游报告问题”相矛盾。
修复：为 Youmo 重写，或删除 `app/src/main/play/`。
三个 S46 阶段标为 SHOULD FIX，STYLE@G9 视其为无人读取的文件，标为 NIT。

- 提出者：CORRECTNESS-1@S46（SHOULD FIX，7/10）、CORRECTNESS-2@S46（SHOULD FIX，7/10）、GENERIC-1@S46（SHOULD FIX，7/10）、STYLE@G9（NIT，9/10）

#### F61 [SHOULD FIX] Zstd 128 MB 输出上限与倍增扩容可耗尽堆内存

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（3 SHOULD FIX, 1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`K/engine/libime/Zstd.kt:75`、`J/data/pinyin/ImportedDictionaries.kt:99-104`、`Engines.kt:167`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:26`、`Zstd.kt:75`、`Zstd.kt:155-161`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:26`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:75`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:59`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/Zstd.kt:63`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:156`

`MAX_OUTPUT` 为 128 MiB，缓冲区倍增扩容（:75），结果再复制一次（:59），峰值约为输出的 1.5-2 倍（192-256 MB），超出许多设备的默认堆上限（应用未申请 `largeHeap`，Engines.kt:156 提到有些手机只给约 200 MB）；CORRECTNESS-1@S9 另指出 raw/RLE 块未受 RFC 128 KB `Block_Maximum_Size` 限制，几百字节文件即可达到上限。
`OutOfMemoryError` 不是 `DataFormatException`，而 `ImportedDictionaries.migrate` 在启动时运行、只捕获 `IOException`/`DataFormatException` 且读完才移走文件，所以过大或构造的 `.dict` 会导致每次启动都崩溃。PERFORMANCE@G3 以 NIT 从内存开销角度指出即使帧声明了大小，缓冲区仍会超出（峰值 2-3 倍）。
建议：降低上限（libime 自带词典解压约 9 MB，GENERIC-1@S9 建议 32 MB），按帧声明的内容大小或约 1.5 倍扩容，拒绝超过 128 KB 的块，并把数组与大小一起交给 `BigEndianInput` 而不做最后一次复制。

- 可能的运行时影响：导入过大或构造的旧版 .dict 词典会使应用在每次启动时因 OOM 崩溃。
- 提出者：CORRECTNESS-1@S9（SHOULD FIX，5/10）、CORRECTNESS-2@S9（SHOULD FIX，6/10）、GENERIC-1@S9（SHOULD FIX，5/10）、PERFORMANCE@G3（NIT，6/10）

#### F62 [SHOULD FIX] 每个笔画键都扫描整张表并对每个匹配解码两次

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（1 SHOULD FIX, 3 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/Strokes.kt:42`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:88`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/Strokes.kt:42`

`find()` 每次按键都遍历 `order` 的全部条目（BMP 约 28k），对范围内每个条目调用两次 `table.text(i)`，首笔就构建并分配数千项（String 加 HashSet 槽位），`StrokeLookup.lookUp` 又经 `filterNot(blocked)` 复制一遍，而每页只显示 5 个。
建议：只解码一次存入局部变量；CORRECTNESS-1 与 PERFORMANCE 建议像 `TableSession.Ranking` 那样惰性构建、只为请求的页解码，GENERIC-1 另建议预计算每个索引的排名、只排序前缀范围。
严重度不一：PERFORMANCE 评为 SHOULD FIX，其余为 NIT。

- 提出者：CORRECTNESS-1@S12（NIT，7/10）、CORRECTNESS-2@S12（NIT，8/10）、GENERIC-1@S12（NIT，9/10）、PERFORMANCE@G3（SHOULD FIX，6/10）

#### F63 [SHOULD FIX] 改动 REMOVE/READINGS 后不重建 pinyin.data

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/engine-data.sh:50-52`、`lib/ime-dict-tool/engine-data.sh:103`、`lib/ime-dict-tool/engine-data.sh:166-167`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/engine-data.sh:50`；另见 `lib/ime-dict-tool/engine-data.sh:102`、`lib/ime-dict-tool/engine-data.sh:166-167`、`lib/ime-dict-tool/engine-data.sh:166`、`lib/ime-dict-tool/engine-data.sh:182`、`lib/ime-dict-tool/engine-data.sh:181`、`lib/ime-dict-tool/engine-data.sh:157-170`

engine-data.sh:50 的 `params` 只覆盖 CRAWL、FROM、FILES、WEIGHT 和 WORDS；在已有工作目录中改动 readings.tsv 或 remove.tsv 后重跑，`done/data`（:102）会跳过重建，words、pack、probe、report（及 examples）仍基于不含新纠正的旧 pinyin.data。
设置 LEXICON 时 manifest 步骤会重跑（:181/:182 清除 `done/manifest`），把新文件的 SHA-256 写入 manifest.txt（:157-170），使溯源记录与实际使用的输入不符。
建议：把 REMOVE/READINGS 的哈希（GENERIC-1@S4 还要求加入工具 jar 的哈希）写入 data 步骤的戳记，哈希变化时删除 `done/data` 及其后各步的标记；CORRECTNESS-1@S4 也提出直接加入 `params`。
GENERIC-1@S4 在同一发现中还提到 EVAL 后加时 `done/compress` 未清除，见 G2-37。

- 提出者：CORRECTNESS-1@S4（SHOULD FIX，9/10）、CORRECTNESS-2@S4（SHOULD FIX，9/10）、GENERIC-1@S4（SHOULD FIX，8/10）

#### F64 [SHOULD FIX] 规则标志测试因计数守卫而通过，标志检查从未执行

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`LibimeFilesTest.kt:109-117`、`LibimeFiles.kt:107`、`LibimeFiles.kt:152`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`LibimeFilesTest.kt:109-117`、`LibimeFiles.kt:107`、`LibimeFiles.kt:152`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:109`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:152`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:159`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:217`

`aRuleWithAFlagLibimeHasNotFails` 在 `writeInt(1)`（一条规则）后只剩 4 字节，`LibimeFiles` 中 `count(input, 9)` 先抛「count past the end」，`rule()` 从未被调用，所以 LibimeFiles.kt:152 的 rule flag 检查和 :159 的 rule entry flag 检查都没有任何测试；db.main.dict 没有 `[Rule]` 段，模糊测试也到不了，接受任意标志的代码仍会通过此测试。
建议：写出完整规则使计数检查通过，断言消息含「rule flag」，并为条目标志加对应用例；GENERIC-1 还建议像第 213 行那样断言消息，包括 `keysSharingATailFail`（第 217 行）。

- 提出者：CORRECTNESS-1@S17（SHOULD FIX，9/10）、CORRECTNESS-2@S17（SHOULD FIX，9/10）、GENERIC-1@S17（SHOULD FIX，9/10）

#### F65 [SHOULD FIX] 测试中的无界循环和「不会挂起」的模糊测试没有超时

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:107`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:145`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:48`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:107`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:145`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:48`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/ZstdTest.kt:148`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/ZstdTest.kt:147`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:241`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:240`

SentenceModelTest.kt:107、:145 和 RerankerTest.kt:48 的 `while (… == null)` 循环只有在 `Scorer.within` 跨调用保留进度时才会结束，而这正是被测行为，回归会让 CI 挂住而不是失败；ZstdTest 和 LibimeFilesTest 的模糊测试声称「不会挂起」却没有超时，`lib/ime-core/build.gradle.kts` 也未设测试超时。
建议：限制循环次数后 `fail()`（如 `repeat(1000)`），或加 `@Test(timeout = …)` 或 Gradle `Test.timeout`。
严重度不一：GENERIC-1 评为 SHOULD FIX，CORRECTNESS-1/2 评为 NIT。

- 提出者：CORRECTNESS-1@S17（NIT，9/10）、CORRECTNESS-2@S17（NIT，9/10）、GENERIC-1@S17（SHOULD FIX，9/10）

#### F66 [SHOULD FIX] 密码框精排测试检查的是已删除的云端钩子，真正的隐私承诺未断言

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`PinyinSessionRefineTest.kt:118`、`Reranker.kt:31`、`PinyinSession.kt:645`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinSessionRefineTest.kt:118`、`Reranker.kt:31`、`PinyinSession.kt:645`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:109`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:118`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:31`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:645`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:633`

该测试中假的「remote」refiner（第 118 行）是仓库里唯一的 `offline()` 覆盖；commit 69f2562a 删除云端 refiner 后，生产 refiner 都返回 `this`（Reranker.kt:31），`offlineRefiner`（PinyinSession.kt:645-647）不起任何作用，只有此测试在用。
真正让密码框保持私密的是 `learning` 关闭时 `textBefore()`（:633）不含 `recent`；只有精排器（reranker）的上下文有测试（`whileLearningIsOffTheRerankerIsToldNothingCommitted`），refiner 收到的 `context` 没有，README/PRIVACY.md 的承诺未被断言。
建议：改写为在 learning 开启时提交、再关闭 `learning`、输入并执行 `Refine`，断言 refiner 收到的 `context` 为空（提交后与 `Action.Context` 后都检查），同时删除主代码中的 `offline()`/`offlineRefiner`，这与只离线的目标一致。

- 提出者：CORRECTNESS-1@S18（SHOULD FIX，9/10）、CORRECTNESS-2@S18（SHOULD FIX，8/10）、GENERIC-1@S18（SHOULD FIX，9/10）

#### F67 [SHOULD FIX] UserStoreTest 的压缩退避测试无法失败

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:160`、`UserStoreTest.kt:321`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:160`、`UserStoreTest.kt:321`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:314`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:321`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:273`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:169`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:137`

测试用空的 `.compacting` 目录阻止压缩，但 `RecordStore.writeCounts` 失败时执行 `next.delete()`（RecordStore.kt:169），把这个空目录也删了；之后任何重试都会成功且不报错，所以 `errors.size == 1`（UserStoreTest.kt:321）在有无 `compacted = end`（RecordStore.kt:137）退避时都成立，也没有其他测试覆盖这条失败路径。GENERIC-1@S20 另指出失败后只追加了一条 21 字节的记录（日志到 92 字节）。
CORRECTNESS-1@S20 顺带指出第 273 行的 `blocker.delete()` 因同一原因不起作用（GENERIC-1@S20 将其单独提出，见 G4-51）。
建议用删不掉的阻塞物（在 `.compacting` 内建一个文件），在日志达到 2 倍大小前后分别统计尝试或错误次数；GENERIC-1@S20 给出具体方案：`repeat(6)`，断言 `errors.size == 1` 与 `file.length() == 8 + 21 * 6L`。

- 提出者：CORRECTNESS-1@S20（SHOULD FIX，9/10）、CORRECTNESS-2@S20（SHOULD FIX，9/10）、GENERIC-1@S20（SHOULD FIX，9/10）

#### F68 [SHOULD FIX] 展开候选分页的结束判断差一，最后一个候选不显示

- 来源：第一轮（整仓 @07d2778） · 共识：3/21 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/CandidatesPagingSource.kt:26`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/CandidatesPagingSource.kt:26`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:685`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:410`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:127`、`app/src/main/cpp/androidfrontend/androidfrontend.cpp:101`、`app/src/main/cpp/androidfrontend/androidfrontend.cpp:190`

`CandidatesPagingSource`（:26）一页加载 `[startIndex, startIndex + pageSize)`，却用 `startIndex + pageSize + 1 >= total` 判断结束；恰好剩一个候选时返回 `nextKey = null`，索引 `total - 1` 永远不会加载，用户看不到也选不了。
这是上游代码，但本项目的会话总是报告确切的 `total`（PinyinSession.kt:685、TableSession.kt:410、StrokeLookup.kt:127），长的单音节候选列表很常见，所以这种情况可以触发。
修复：CORRECTNESS-1@S35 写作 `if (startIndex + candidates.size >= total) null else startIndex + candidates.size`，另两票写作 `pageSize`；CORRECTNESS-1@S35 和 CORRECTNESS-2@S35 建议把这段 key 计算抽成纯函数并加单元测试。

- 可能的运行时影响：展开候选列表在特定长度下缺少最后一个候选，用户无法选到它。
- 提出者：CORRECTNESS-1@S35（SHOULD FIX，8/10）、CORRECTNESS-2@S35（SHOULD FIX，9/10）、GENERIC-1@S35（SHOULD FIX，8/10）

#### F69 [SHOULD FIX] 解码自定义背景图时 FileInputStream 未关闭

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 SHOULD FIX, 1 NIT PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/Theme.kt:105`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/Theme.kt:105`

`Theme.kt:105` 的 `BitmapFactory.decodeStream(cropped.inputStream())` 从不关闭流，每次构建背景 drawable（创建 InputView、切换主题、旋转屏幕、修改键盘尺寸设置）都会泄漏一个文件描述符，直到 GC 终结该流。这段代码继承自上游 fcitx5-android。
修复：`cropped.inputStream().use { BitmapFactory.decodeStream(it) }`，或改用 `BitmapFactory.decodeFile(croppedFilePath)`。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-2@S32（NIT PRE-EXISTING，9/10）、GENERIC-1@S32（SHOULD FIX，9/10）、PERFORMANCE@G7（SHOULD FIX，8/10）

#### F70 [SHOULD FIX] 主题版本回退测试未真正删除 version 字段

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:231`、`CustomThemeSerializer.kt:33`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:231`、`CustomThemeSerializer.kt:33`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/ThemeSerializationTest.kt:231`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/CustomThemeSerializer.kt:33`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/CustomThemeSerializer.kt:28`

`ThemeSerializationTest.kt:231` 中 `replace` 的目标文本带 16 个空格缩进，而 `trimIndent()` 之后 fixture 里 `version` 那一行只剩 4 个空格，所以什么都没有替换：测试解码的仍是显式写着 1.0 的文档，`CustomThemeSerializer.kt:28-34` 的 `FALLBACK_VERSION` 分支从未被执行。
修复：结构化地删除这个键（解析成 `JsonObject`，去掉 `version`，再编码），或用正则删除；至少在解码前断言 raw 中已不含 version，或与 `Fixtures.v1` 不同。

- 提出者：CORRECTNESS-1@S49（SHOULD FIX，9/10）、CORRECTNESS-2@S49（SHOULD FIX，9/10）、GENERIC-1@S49（SHOULD FIX，9/10）

#### F71 [SHOULD FIX] 从弹出键盘选多字符项后一次性 Shift 未释放

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:160`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:160`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:225`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:226`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:211`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:95`

PopupKeyboardUi.onTrigger 对超过一个码点的候选返回 CommitAction，而 TextKeyboard.onAction（:160）只对来自 Source.Popup 的 FcitxKeyAction 清除 CapsState.Once，CommitAction 落入 else -> {}。
一次性 Shift 下长按 E 选择 Émile，或选择被 shiftedOverride 转成 QU 的词后，下一个字母仍为大写；该动作的其他消费者（PickerGridAdapter.kt:211、PickerWindow.kt:95）已处理 CommitAction。
建议：在 TextKeyboard.onAction 中，Once 状态下对来自 Source.Popup 的 CommitAction 也调用 switchCapsState()。

- 可能的运行时影响：从长按弹出中选择多字符词后，下一个字母仍被大写。
- 提出者：CORRECTNESS-1@S39（SHOULD FIX，9/10）、CORRECTNESS-2@S39（SHOULD FIX，8/10）、GENERIC-1@S39（SHOULD FIX，7/10）

#### F72 [SHOULD FIX] 删除或编辑主题后主题列表的选中标记指错

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 SHOULD FIX PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:72-83`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:85-98`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListFragment.kt:67-75`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:73`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:72`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:85`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeListAdapter.kt:86`

`ThemeListAdapter.removedOffset` 计算 `(removedIndex - OFFSET - index).sign`，混用了条目索引与适配器位置：如 [A(选中),X,Y,B] 删除 B 后 `activeIndex` 指向 Y。`replaceTheme`（第 85-86 行）把条目移到最前，却没给 `[OFFSET, index + OFFSET)` 内下移的索引加 1：编辑 B 后 B 被画成选中，A 的 ViewHolder 未重绑仍留着勾选。
删除或编辑非当前主题时 ThemeManager 不触发变更，错误标记会一直留在屏幕上，可能同时出现两个勾选。CORRECTNESS-1/2 标为 PRE-EXISTING；GENERIC-1 未加标记但说明继承自上游，并把删除与编辑分成两条（此处合为一票）。
建议：删除时仅当 `index > removedIndex + OFFSET` 才减 1（相等时设 -1）；`replaceTheme` 中给该区间内的索引加 1。

- 可能的运行时影响：删除或编辑主题后，主题列表可能把错误的主题标为选中或同时显示两个勾选。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S44（SHOULD FIX PRE-EXISTING，8/10）、CORRECTNESS-2@S44（SHOULD FIX PRE-EXISTING，8/10）、GENERIC-1@S44（SHOULD FIX，9/10）

#### F73 [SHOULD FIX] `Locales.language` 从不是纯语言代码

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:47`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:61-62`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableBasedInputMethod.kt:27-29`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:62`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:47`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:68`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableBasedInputMethod.kt:28`

`languageWithCountry` 形如 `zh_HK`、不含 `:`，`substringBefore(':')` 原样返回整个字符串，因此 `TableBasedInputMethod.kt:28` 的 `Name[<lang>]` 回退永远找不到 `Name[zh]` 等键，zh-HK 用户会看到未翻译的码表名。
无地区的语言环境（如 33+ 上应用选择的 `en`）还会在第 47 行生成 `en_`、`zh_` 这类条目，两次查找都落空；CORRECTNESS-2 指出 Android 7 以下分支（第 68 行）设的是纯语言，两分支不一致。CORRECTNESS-1 说明该代码来自上游。
建议：改用 `substringBefore('_')`，country 为空时跳过 `_${country}` 条目；CORRECTNESS-1 还建议 TableBasedInputMethod 依次尝试 `fcitxLocale` 的每个条目。

- 可能的运行时影响：zh_HK 等语言环境或无地区语言的用户会看到未翻译的码表名称。
- 提出者：CORRECTNESS-1@S45（SHOULD FIX，9/10）、CORRECTNESS-2@S45（SHOULD FIX，9/10）、GENERIC-1@S45（SHOULD FIX，9/10）

#### F74 [SHOULD FIX] 备份说明未提及导出文件包含剪贴板历史（含敏感条目）

- 来源：第一轮（整仓 @07d2778） · 共识：3/11 位 reviewer（3 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/res/values/strings.xml:441`、`UserDataManager.kt:66`、`ClipboardEntry.kt:19`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/res/values/strings.xml:441`；另见 `app/src/main/res/values-zh-rCN/strings.xml:435`、`PRIVACY.md:19`、`PRIVACY.md:39`、`PRIVACY.md:17-19`

`export_user_data_summary`（values/strings.xml:441，values-zh-rCN:435）只列出设置、学习和添加的词、自定义短语和主题，但 `UserDataManager.export` 会打包整个 `databases/` 目录，其中包括剪贴板数据库 `clbdb`；来源应用标记为 sensitive 的条目（如密码）以明文存储，只在列表界面中被遮蔽。
用户可能把自以为只是设置的备份文件上传或分享出去；PRIVACY.md 和 README 都说明剪贴板随备份导出，CORRECTNESS-1@S48 和 GENERIC-1@S48 指出保留剪贴板、修正说明不违背既定目标。
修复：在 en、zh-CN、zh-TW 等所有语言的说明中写明剪贴板历史；CORRECTNESS-1@S48 和 GENERIC-1@S48 另提出可在导出时排除 `sensitive=1` 的条目。

- 可能的运行时影响：用户可能在不知情的情况下分享含明文密码等剪贴板历史的备份文件。
- 提出者：CORRECTNESS-1@S48（SHOULD FIX，9/10）、CORRECTNESS-2@S48（SHOULD FIX，8/10）、GENERIC-1@S48（SHOULD FIX，9/10）

#### F75 [SHOULD FIX] engine-data.sh 产出的数据与 app 构建不一致

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/engine-data.sh:83-84`、`EngineDataPlugin.kt:181-184`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-dict-tool/engine-data.sh:83-84`、`EngineDataPlugin.kt:181-184`）
- 位置：`lib/ime-dict-tool/engine-data.sh:84`；另见 `lib/ime-dict-tool/engine-data.sh:99`、`lib/ime-dict-tool/engine-data.sh:103`、`lib/ime-dict-tool/engine-data.sh:154`、`lib/ime-dict-tool/engine-data.sh:15`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:173-177`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:350-353`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:352-353`

engine-data.sh 的注释说纠正项「as the app build applies them」、probe「sees what a user would」，但 EngineDataPlugin.kt:173-177、350-353 还传入 `--misreadings lexicon/misreadings.tsv` 并把 `lexicon/latin.words` 编为词典层，而 `data()` 与 `weight()`（:154）都没有传。
因此驱动 batches.py「typed right alone」过滤的 probe.tsv，以及用于挑选发布混合权重的 report.txt/weights.txt，测量的不是用户拿到的引擎；GENERIC-1@S4 指出 latin 词与拼音争用同样的小写字母，STYLE@G2 指出受影响的是 141 个误读词。
建议：像 REMOVE/READINGS 一样增加 MISREADINGS（CORRECTNESS-1@S4、GENERIC-1@S4 还要求 latin pack/LATIN）变量传入 `$CORRECT` 并写入 manifest；CORRECTNESS-1@S4 也接受改正注释作为替代。STYLE@G2 只提到 `--misreadings`，未提 latin 层。

- 提出者：CORRECTNESS-1@S4（SHOULD FIX，8/10）、GENERIC-1@S4（SHOULD FIX，8/10）、STYLE@G2（SHOULD FIX，8/10）

#### F76 [SHOULD FIX] CommonCrawl 抓取重试与退避不打日志

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:129-155`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:146`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:152`

CommonCrawl.kt 的 `fetch()` 在 503/429/5xx 响应或 IOException 重试时不输出任何日志；一个文件的退避休眠可达约 15 分钟（PERFORMANCE@G2 估算 8 次暂停共 910 s，加随机部分约 23 分钟，另有每次最长 5 分钟超时）。
在长达数小时的发布任务（或租用机器）上，长时间静默看起来像卡死，503/429 限流也无从察觉。
建议：把 `log` 传入 `fetch`，每次休眠前记录路径、状态码或错误以及暂停时长；GENERIC-1@S4 也提出可在每个分片的汇总行里报告重试次数。
TESTING@G2 在其重试无测试的发现（G2-08）中也指出不打日志。

- 提出者：CORRECTNESS-2@S4（NIT，7/10）、GENERIC-1@S4（NIT，8/10）、PERFORMANCE@G2（SHOULD FIX，8/10）

#### F77 [SHOULD FIX] CountFit 退化拟合守卫依赖浮点结果恰好为零

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CountFit.kt:30`、`CountFitTest.kt:33`、`Main.kt:447`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CountFit.kt:30`、`CountFitTest.kt:33`、`Main.kt:447`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CountFit.kt:30`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/CountFitTest.kt:33`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/CountFitTest.kt:31-34`

CountFit.kt:30 的 `require(d > 0.0)` 用来拒绝只有一个计数的拟合；CountFitTest 只用 count 9，log10(10)=1.0 让 `d` 恰好为 0（GENERIC-1@S6 认为 n=2 时也会恰好抵消）。
三对及以上同计数且对数不精确时，舍入会让 `d` 成为极小正数，守卫通过并返回无意义的斜率；CORRECTNESS-2@S6 用 IEEE double 计算得 9995 个 (count, n) 组合中 3744 个 d > 0（如 count 1999、n = 100 时 d = 1.5e-10），并指出 `pack` 用该斜率给写出的每个词打分；CORRECTNESS-1@S6 认为生产拟合覆盖很多不同计数，不太可能触发。
建议：改用相对容差比较，或直接要求至少两个不同计数（GENERIC-1@S6：`pairs.map { it.first }.distinct().size >= 2`），并加入三对以上相同计数（非 9）的测试用例。

- 提出者：CORRECTNESS-1@S6（NIT，6/10）、CORRECTNESS-2@S6（SHOULD FIX，8/10）、GENERIC-1@S6（NIT，6/10）

#### F78 [SHOULD FIX] lexicon/misreadings.tsv 未声明为测试任务输入

- 来源：第一轮（整仓 @07d2778） · 共识：3/15 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/build.gradle.kts:53`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:52`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/build.gradle.kts:53`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:52`）
- 位置：`lib/ime-core/build.gradle.kts:54`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:52`、`lexicon/misreadings.tsv`

`MisreadingsTest` 以相对工作目录的路径 `../../lexicon/misreadings.tsv` 读取随包发布的误读表，校验每个读音都是已知音节且没有重复词；但 `lib/ime-core/build.gradle.kts` 既未把该文件声明为 `:lib:ime-core:test` 的输入，也未设 `workingDir`。
结果：只修改该文件后测试任务仍为 UP-TO-DATE，本地 `check` 不会重新校验，只有无构建缓存的 CI 才会发现坏行。
建议：在测试任务上加 `inputs.file(rootProject.file("lexicon/misreadings.tsv"))`（GENERIC-1@S15 建议再加 `.withPathSensitivity(PathSensitivity.RELATIVE)`），并经 `systemProperty` 传入路径代替 `../../`。

- 提出者：CORRECTNESS-1@S15（SHOULD FIX，8/10）、CORRECTNESS-2@S15（SHOULD FIX，8/10）、GENERIC-1@S15（SHOULD FIX，8/10）

#### F79 [SHOULD FIX] EnginesTest 的 return@repeat 只跳过一次迭代未结束循环

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`T/engine/host/EnginesTest.kt:170`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:158`、`EnginesTest.kt:166`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:170`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:179`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:218`

Kotlin 的 `return@repeat` 只结束当前 lambda 调用，循环继续；拟 领先后组字中的 `ni` 未被重置，后续迭代输入 `nini`、`ninini`，`PICK 1` 选中的是无关候选。
测试因此学习了无意学习的词，与“picked until it leads”注释不符；CORRECTNESS-1@S16 指出第 179 行的持久化检查也依赖这些意外选择。
建议：改用带 `break` 的 `for` 循环，或复用每次迭代都重置的 `pickUntilFirst("拟")`，或在返回前发送 RESET。严重度：CORRECTNESS-1@S16 为 SHOULD FIX，另两票为 NIT。

- 提出者：CORRECTNESS-1@S16（SHOULD FIX，8/10）、CORRECTNESS-2@S16（NIT，8/10）、GENERIC-1@S16（NIT，8/10）

#### F80 [SHOULD FIX] 开启U_OU后全拼与九键把单独的u当作ou，双拼则不会

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:94-96`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/ShuangpinSegmenter.kt:50`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:61`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:94`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:96`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/ShuangpinSegmenter.kt:50`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:61`

`ShuangpinSegmenter.kt:50` 跳过不是音节的零声母模糊韵母（「ou's partner u is none」），而 `SpellingIndex`（第 94/96 行）和 `T9Segmenter`（第 61 行）没有这一检查，于是加入 `u` → ou~ 的拼写。
后果：全拼中 `xiu` 还被读作 xi'ou（西欧，仅 -1），以 u 开头的输入（如 `uan`）多出 欧 路径，与 PinyinSegmenter 文档「单独的 u 是原始输入」相矛盾；九键中 `8` 变成 regular，末尾被读作 EXTENDED，且在 `abbreviations = false` 下声母 t 消失（`86` 他们、`89` 同学）。
建议：在两处加 `if (i.isEmpty() && Syllables.id(f) < 0) continue`（或让 `Fuzzy.appliesAfter` 对空声母返回 false），各加一个测试。
严重度不一：CORRECTNESS-1/2 评为 NIT，GENERIC-1 评为 SHOULD FIX。

- 可能的运行时影响：开启 u/ou 模糊音后，全拼会给出 西欧 这类多余读法，九键 `86`、`89` 丢失 他们、同学 的声母读法。
- 提出者：CORRECTNESS-1@S10（NIT，6/10）、CORRECTNESS-2@S10（NIT，8/10）、GENERIC-1@S10（SHOULD FIX，8/10）

#### F81 [SHOULD FIX] 空输入时u启动笔画查询，u开头的拉丁词无法输入

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:50`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:81`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/EngineSettings.kt:27`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:50`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:81`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:82`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/EngineSettings.kt:27`

`Engines` 总用 `StrokeLookup` 包装全拼且笔画数据随应用发布，`latinWords` 默认开启（EngineSettings.kt:27），`lexicon/latin.words` 有 404 个 u 开头的词（USB、UI、URL、Uber）；空输入时键入 `usb` 会启动笔画查询，`s` 变成 丨，`type()`（第 81-82 行）吞掉非笔画字母 `b`，该词无法输入，而句中（`wodeusb`）仍正常。
类文档「没有拼音以 u 开头」的前提已不成立，开头 u 的邻键纠错（y、i 的邻键）也丢失；没有设置能关闭查询，也没有测试覆盖。
建议：遇到第一个非笔画字母时结束查询，把 `u` 加已输入的键（和当前键）回放给 `pinyin`，只保留纯笔画的查询，并加 u 开头拉丁词的测试。

- 可能的运行时影响：空输入时用户无法用小写键入 USB、UI、URL 等 u 开头的拉丁词，后续字母被吞掉。
- 提出者：CORRECTNESS-1@S12（SHOULD FIX，8/10）、CORRECTNESS-2@S12（SHOULD FIX，8/10）、GENERIC-1@S12（SHOULD FIX，7/10）

#### F82 [SHOULD FIX] UserModel 在解码器每条弧上对 Int 装箱

- 来源：第一轮（整仓 @07d2778） · 共识：3/15 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:232`、`UserModel.kt:284-285`、`PinyinDecoder.kt:160`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:232`、`UserModel.kt:284-285`、`PinyinDecoder.kt:160`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:284`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:287`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:235`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:232`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:82`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:160`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/Counts.kt:8`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:190`

`blocked()`/`blockedAnyhow()`（UserModel.kt:284-287）中的 `word in blockedIds`（`HashMap<Int, Int>`）在 ART 上对大于 127 的 id 每次分配一个 Integer；`PinyinDecoder.addWords` 与 `lookAhead`（PinyinDecoder.kt:160）每次按键都对每条字典弧调用它，即使用户没有屏蔽任何词，而这正是 `Counts` 想避免的装箱。
PERFORMANCE@G4 进一步指出 `layerOf`/`score` 中的 `packLayers[id]`、`packScores[id]`（UserModel.kt:232、235）在每次 `scoreAfter` 时同样装箱，Engines.kt:190 的 `(Int) -> Int` `extra` lambda 也会装箱，大型导入词典中每个列出的词还要付出五个装箱的映射条目。
建议至少在映射为空时快速返回（如 `blockedIds.isEmpty()`），或改用原始类型集合；PERFORMANCE@G4 建议利用用户 id 从 `vocabulary.size` 起连续的特点，把 `packLayers`、`packScores`、`listed` 改为按 `id - base` 索引的 IntArray/FloatArray/BitSet。严重度不一：PERFORMANCE@G4 为 SHOULD FIX，另两票为 NIT。

- 提出者：CORRECTNESS-1@S13（NIT，8/10）、GENERIC-1@S13（NIT，7/10）、PERFORMANCE@G4（SHOULD FIX，8/10）

#### F83 [SHOULD FIX] AutoPairs 绕过 EditingSession 线程检查，守护测试未覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:86`、`EditingSessionTest.kt:645-647`、`EditingSession.kt:40`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:86`、`EditingSessionTest.kt:645-647`、`EditingSession.kt:40`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:86`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:25`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:40`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:631`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:627`

EditingSession.kt:25 声称每个修改入口都调用 `checkThread`，测试 `everyMutatingEntryPointChecksTheThread` 用反射强制这一点，但只枚举 EditingSession 自身的方法；`session.pairs` 上的 `AutoPairs.type`/`committed`/`backspace`/`forget` 改动会话持有的状态却不调用 `checkThread`，`backspace()` 还直接调用 `editor.deleteSurroundingText`，服务也直接调用 `pairs.forget()` 与 `pairs.type()`。
STYLE@G4 指出该测试因此给出虚假保证，且 EditingSession.kt:40 在构造期间把 `this` 传给 AutoPairs，使两个类互相依赖；GENERIC-1@S21 另指出反射过滤只数方法个数、不核对名称，并丢弃含 `$` 的名称，未来的 `internal` 修改方法（如 `foo$module`）会漏网。
建议把 `checkThread` 传入 AutoPairs（或经会话方法转发删除），并把 pairs 的方法加入守护测试；GENERIC-1@S21 建议与方法名列表比对；CORRECTNESS-2@S21 也接受仅收窄 KDoc 的说法。严重度不一：STYLE@G4 为 SHOULD FIX，另两票为 NIT。

- 提出者：CORRECTNESS-2@S21（NIT，8/10）、GENERIC-1@S21（NIT，7/10）、STYLE@G4（SHOULD FIX，8/10）

#### F84 [SHOULD FIX] 引擎候选列表不可翻页，浮动候选窗失去翻页箭头和高亮

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:58`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:58`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:89`、`app/src/main/cpp/androidfrontend/androidfrontend.cpp:135`、`app/src/main/cpp/androidfrontend/androidfrontend.cpp:267`、`app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt:104`

EngineCandidateList 只实现 Bulk 和 Actionable，没有实现 PageableCandidateList（从不调用 setPageable），cursorIndex() 返回 -1（androidengine.cpp:89）。因此 updateCandidatesPaged（androidfrontend.cpp:135）总是发送 hasPrev/hasNext=false，PaginationUi/PagedCandidatesUi 不显示箭头，offsetCandidatePage 不起作用，只能用 PageUp/PageDown 键翻页；libime 的 CommonCandidateList 原本具备这些。
受影响的是 README 列为特性的实体键盘浮动候选栏。GENERIC-1@S26 指出 ime-core 的 Snapshot.hasPreviousPage/hasNextPage 已存在，但 EngineBridge.Result 和 EngineSnapshot 丢掉了它们。
修复：实现 PageableCandidateList（hasPrev = first_ > 0；hasNext = first_ + shown_ < total_ 或 total_ < 0；prev/next 发送 PageUp/PageDown），并让 cursorIndex 返回 Space 会提交的候选（GENERIC-1@S26：页面非空时返回 0）。

- 可能的运行时影响：使用实体键盘的用户在浮动候选栏中无法点触翻页，也看不到高亮的候选。
- 提出者：CORRECTNESS-1@S26（SHOULD FIX，8/10）、CORRECTNESS-2@S26（SHOULD FIX，8/10）、GENERIC-1@S26（SHOULD FIX，8/10）

#### F85 [SHOULD FIX] 加载语音模型时抛出 Error 会让 ready 永远挂起

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:65-67`、`VoiceHoldSession.kt:69`、`VoiceHoldSession.kt:119-125`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:107`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:65`

VoiceListener.kt:107 处围绕 VoiceEngine.acquire 只捕获 Exception；System.loadLibrary 的 UnsatisfiedLinkError、ExceptionInInitializerError、NoClassDefFoundError 或加载约 200 MB 模型时的 OutOfMemoryError 会落到作用域处理器（line 65），它显示「麦克风无法使用」而不是 voice_no_model。
ready 既不完成也不取消，每个 stretch 任务都卡在 ready.await() 并持有其音频（每段最多约 1.3 MB / 20 s），每次尝试都会重复；CORRECTNESS-2@S28 还指出 recognizer 为 null 时 close() 直接返回。
修复：在此处捕获 Throwable（重新抛出 CancellationException），或在 finally 中于加载未成功时取消/异常完成 ready；GENERIC-1@S28 建议 ready.completeExceptionally(t) 并提示 NoModel，致命错误再重新抛出。

- 可能的运行时影响：语音模型加载抛出 Error 时语音输入永久挂起并占用内存，还给出错误的「麦克风无法使用」提示。
- ⚠️ 验证说明：代码层面的论断成立，但 verifier 否定了上面所说的运行时后果：作用域的 CoroutineExceptionHandler 会捕获该 Error 并发送 failed(NoMicrophone)（app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:65-67）。随后 VoiceHoldSession.kt:69 调用 end()（VoiceHoldSession.kt:119-125），关闭监听器并结束按住状态。因此语音输入不会卡住，被丢弃的 stretch 协程变得不可达，下一次按下会创建新的监听器。关于运行时的说法中，只有“提示信息错误”这一部分成立。
- 提出者：CORRECTNESS-1@S28（SHOULD FIX，8/10）、CORRECTNESS-2@S28（SHOULD FIX，8/10）、GENERIC-1@S28（NIT，7/10）

#### F86 [SHOULD FIX] 服务中的隐私与配对判断没有单元测试，也未下沉到 ime-core

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:849`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:849`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:845`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:854`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:672`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:338`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:127`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:755`

以下判断都内联在 Android 服务里，没有 JVM 测试：是否读取上下文（`tellTextBeforeCursor` 中敏感字段的早退，FcitxInputMethodService.kt:849）、空字段是否使用记忆的上下文（:854）、是否记忆该字段（:672）、是否配对括号（`pairing`/`commitTyped`，:338-351）、是否允许语音（:127）以及引号重置（:754-755）。一旦回退，密码框里光标前的文字就可能进入 `engineContext`。
CLAUDE.md 要求决策逻辑放在 ime-core，并在同一变更中带单元测试；GENERIC-1@S33 认为针对各种 EditorInfo 变体的测试本可以发现可见密码框的问题。
修复：把这些决策移进 ime-core 的纯策略（例如 ContextMemory/EditingSession 上接收 `sensitive` 或 `CapabilityFlags` 的方法），用 FakeEditor 或已能记录 `engineContext` 调用的 `FakeFcitxAPI` 测试：敏感字段不产生 `engineContext` 调用、不保留记忆。

- 提出者：CORRECTNESS-1@S33（SHOULD FIX，8/10）、GENERIC-1@S33（SHOULD FIX，7/10）、TESTING@G7（SHOULD FIX，7/10）

#### F87 [SHOULD FIX] EngineBridge.additions() 中的数据安全逻辑没有单元测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:126`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:126`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:146`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:147`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:144`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:193`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:230`

`additions()`（EngineBridge.kt:126）包含几条关键判断：读不到 customphrase 时 `save` 必须拒绝写入（:146-147，否则键盘上一次置顶会用一条短语覆盖用户全部短语）；词库和词包在基础层与新词层之间的划分（`intoNew`）；决定 `Engines.reload()` 是否重读词典的 `seen` 签名（:144）。CORRECTNESS-1@S29 还提到 `onEvent`（:193）中决定送给 native addon 的 `unshown`/`CHUNK` 规则。
`EngineBridge` 直接依赖 `FcitxApplication`/`appContext`，JVM 测试无法覆盖，不符合 CLAUDE.md「新逻辑在同一变更中带测试」的要求。
修复：让这些读取函数以拼音目录为参数（internal，或移进 ime-core 写成纯函数），测试读不到短语时拒绝保存、词典换层时签名改变；TESTING@G7 还建议给 `offers()` 的位序（:230，与 native addon 的约定）加一条断言。

- 提出者：CORRECTNESS-1@S29（SHOULD FIX，8/10）、CORRECTNESS-2@S29（SHOULD FIX，6/10）、TESTING@G7（SHOULD FIX，8/10）

#### F88 [SHOULD FIX] bfs 的 level 是出队计数而非深度，依赖展开取决于顺序

- 来源：第一轮（整仓 @07d2778） · 共识：3/15 位 reviewer（2 SHOULD FIX, 1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/ImmutableGraph.kt:55`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/ImmutableGraph.kt:55`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/ImmutableGraph.kt:51`、`app/src/main/java/org/fcitx/fcitx5/android/core/AddonDependencyGraph.kt:35`、`app/src/test/java/org/fcitx/fcitx5/android/core/AddonDependencyGraphTest.kt:46`

`ImmutableGraph.bfs`（ImmutableGraph.kt:51、:55）每出队一个节点就给 `level` 加 1，而不是每深入一层加 1；因此 `AddonDependencyGraph.kt:35` 的 `level == 1 && Optional` 规则只会展开第一个出队的直接依赖者的可选依赖者，是否展开取决于队列顺序。GENERIC-1@S49 的例子：core←a、core←b（必需），a←p、b←q（可选），p←x、q←y（必需），结果列出 x 而不列出 y。现有测试的图太小，覆盖不到这种情况。
修复：在队列项中携带深度，并为「传递依赖者的可选依赖者」加测试。CORRECTNESS-1@S29 把它标为 PRE-EXISTING；CORRECTNESS-1@S49 在「没有生产调用方」那条发现中也提到了这一点。

- 可能的运行时影响：插件的反向依赖列表会随遍历顺序多列或漏列依赖者。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 跨模块组合并：G7-39、G8b-50（BFS `level` 计的是出队节点数而非深度）
- 提出者：CORRECTNESS-1@S29（NIT PRE-EXISTING，8/10）、GENERIC-1@S49（SHOULD FIX，8/10）、GENERIC-1@S45（SHOULD FIX，8/10）

#### F89 [SHOULD FIX] vivo 兼容模式下按住说话跟踪错误手指

- 来源：第一轮（整仓 @07d2778） · 共识：3/18 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:424`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:424`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:168`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:183`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:56`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldOverlay.kt:43`

开启 vivoKeypressWorkaround（vivo OriginOS 低于 Android 14 时默认开启）后，BaseKeyboard.dispatchMotionEventToTarget 用单指针 MotionEvent.obtain 重建事件，pointer id 恒为 0，CustomGestureView 记录的 touchPointer 因而错误并传给 BaseInputView.longPressPointer。
VoiceHoldOverlay 用该 id 匹配真实事件流：若另一根手指已按下，抬起空格手指不结束会话、抬起另一根手指却结束，上滑取消失效，可能提交用户想取消的文字。
建议：在 TouchTarget 中保存真实 pid，或用带 PointerProperties 的 obtain 保留 id，或由 BaseKeyboard 随长按动作传递真实指针。

- 可能的运行时影响：vivo 兼容模式下多指操作时，按住说话会被另一根手指结束或无法上滑取消，可能误提交文字。
- 提出者：CORRECTNESS-1@S37（SHOULD FIX，7/10）、CORRECTNESS-2@S37（SHOULD FIX，8/10）、GENERIC-1@S37（SHOULD FIX，7/10）

#### F90 [SHOULD FIX] 密码框中长按空格无反应但仍显示麦克风角标

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:155`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:154`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:155`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:442`

语音版默认长按空格为语音，CommonKeyActionListener.kt:176 在密码框中跳过 VoiceFeature.hold 且不给提示（只有触感），而空格上的「按住说话」麦克风角标仍显示；updateSpaceBadges 只在构造和偏好变化时运行。
工具栏在同样情况下会隐藏自己的麦克风（KawaiiBarComponent.kt:442），VoiceWindow 会用 voice_no_password 说明拒绝原因。
建议：start input 时按能力标志重算角标、inPasswordField 时隐藏角标，或显示同样的提示、回退到其他长按动作。GENERIC-1 定为 SHOULD FIX，两个 CORRECTNESS 阶段定为 NIT。

- 可能的运行时影响：密码输入框中空格显示「按住说话」麦克风角标，但长按空格没有任何效果和提示。
- 提出者：CORRECTNESS-1@S37（NIT，7/10）、CORRECTNESS-2@S37（NIT，8/10）、GENERIC-1@S37（SHOULD FIX，8/10）

#### F91 [SHOULD FIX] 每次创建输入视图都预先构建 T9Keyboard

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:71`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:71`

KeyboardWindow 的 keyboards map 一次性构建所有布局，每次 InputView 重建（主题、偏好或语言变化）都会构建九键布局（约 20 个 KeyView、ScrollView 列和 9 个标点项），从不使用九键的用户也要付出这一开销；分叉点只构建两个布局。
建议：按需创建布局，例如 keyboards.getOrPut(name) { … }。PERFORMANCE 定为 SHOULD FIX，另两个阶段为 NIT。

- 提出者：CORRECTNESS-1@S37（NIT，6/10）、GENERIC-1@S37（NIT，7/10）、PERFORMANCE@G8（SHOULD FIX，8/10）

#### F92 [SHOULD FIX] 字母滑动覆盖规则位于 View 中且无测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`TextKeyboard.kt:323-326`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TextKeyboard.kt:323-326`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:165`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:265`

TextKeyboard.swipeOverride 有三种结果：无覆盖（null，使用内置滑动）、长按已关闭（空字符串，不输入）、覆盖的第一个候选；这是决策逻辑却放在 View 中，没有测试，CLAUDE.md 要求放在 ime-core 并在同一改动中附测试。
TESTING 指出混淆 null 与空字符串会静默关闭所有滑动，并要求一并测试 BaseKeyboard.textAction；CORRECTNESS-2 还指出大写时滑动输入小写候选而长按给出大写（shiftedOverride），注释未说明。
建议：提取为纯函数放在 PopupOverrides 旁（如 PopupOverrides.swipe(letter, preset)），测试所有情况。

- 提出者：CORRECTNESS-1@S38（SHOULD FIX，8/10）、CORRECTNESS-2@S38（SHOULD FIX，7/10）、TESTING@G8（SHOULD FIX，8/10）

#### F93 [SHOULD FIX] 弹出键盘顶行空位导致绘制列与选中列错位

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:177`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:176`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:96`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:177`

列数改用 ceil（:96）后，13 项的内置 e/E 弹出（以及 13、17、18、21–23 项的用户覆盖）变为 5 列 3 行，顶行留下 2–4 个空位，焦点列居中时空位分布在两端。
行布局（:176/:177）对每个空位设置一次 gravity，最后一次生效，只能处理单侧空位，于是顶行按键画在 onChangeFocus 映射的逻辑列左侧 1–2 列，手指下高亮并提交的是相邻键；GENERIC-1 指出在约 380 dp 及以上宽度的常见手机上都会出现。
建议：为每个空位添加固定尺寸的空白 View/Space 替代 gravity 写法；CORRECTNESS-2 另指出网格计算和触发规则应移入 ime-core 并测试。

- 可能的运行时影响：长按 e/E 等键弹出候选时，手指下高亮并提交的是相邻字符。
- 提出者：CORRECTNESS-1@S39（SHOULD FIX，8/10）、CORRECTNESS-2@S39（SHOULD FIX，8/10）、GENERIC-1@S39（SHOULD FIX，8/10）

#### F94 [SHOULD FIX] 系统设置中选的语言显示为「跟随系统」且无法在应用内重置

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:31`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:31`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:52`、`app/build.gradle.kts:78`、`app/build.gradle.kts:80`

app/build.gradle.kts 设置 generateLocaleConfig = true 且没有语言过滤，Android 13+ 的应用语言页还会提供 de、es、ja、ko、ru；AppLanguage.current() 把 zh、en 以外的语言都映射为空字符串。
于是主页摘要显示「跟随系统」，而该项已被预选，:52 的 which != current 判断使点击它无效，用户无法在应用内恢复跟随系统；CORRECTNESS-2 指出这也与文档注释「只列完整翻译的语言」矛盾。
建议：对未识别标签返回 -1 或单独的「其他」索引，使任意选择都生效；或把生成的 locale config 限制为 zh-CN/zh-TW/en。GENERIC-1 另建议把映射移为纯函数并测试。

- 可能的运行时影响：在系统设置里给应用选了 de、ja 等语言后，应用显示「跟随系统」且无法在应用内改回跟随系统。
- 提出者：CORRECTNESS-1@S40（SHOULD FIX，8/10）、CORRECTNESS-2@S40（SHOULD FIX，8/10）、GENERIC-1@S40（SHOULD FIX，8/10）

#### F95 [SHOULD FIX] 堆转储导出在重建后可能崩溃并残留转储文件

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:41`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:34`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:41`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:50`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:57`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:137`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/DeveloperFragment.kt:139`

DeveloperFragment 的 hprofFile 是只在点击处理器（:137）中赋值的 lateinit 字段，不跨重建保存；文件选择器打开期间活动被重建（CORRECTNESS-1 举旋转为例）或进程被杀（CORRECTNESS-2、GENERIC-1），结果回到新的 fragment，:41/:50/:57 访问它会抛 UninitializedPropertyAccessException 导致崩溃。
CORRECTNESS-1 指出转储随后残留在 cacheDir，其中包含整个堆（含输入文字和剪贴板）；三者都指出 Debug.dumpHprofData（:139）在主线程执行，大堆时可能 ANR。CORRECTNESS-1 注明代码来自上游。
建议：把文件名存入 savedInstanceState（或改为可空字段并判空），启动时清理旧的 .hprof，并在 Dispatchers.IO 上转储。

- 可能的运行时影响：导出堆转储时若活动重建或进程被回收，应用会崩溃，含输入文字的堆转储可能残留在缓存中。
- 提出者：CORRECTNESS-1@S40（SHOULD FIX，8/10）、CORRECTNESS-2@S40（SHOULD FIX，7/10）、GENERIC-1@S40（SHOULD FIX，8/10）

#### F96 [SHOULD FIX] 导出时在 IO 线程上遍历实时列表

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:266`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:29`、`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListAdapter.kt:34`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:266`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:273`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:304`、`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListAdapter.kt:33`

ui.entries 就是 DynamicListAdapter 的后备 _entries 列表，导出时 CustomPhraseManager.save 在 Dispatchers.IO 上遍历它，而主线程仍可能修改；saveConfig（:304）已使用 .toList() 快照。
GENERIC-1 指出 ConcurrentModificationException 不会被 IOException/SecurityException 处理器捕获，应用会崩溃，定为 SHOULD FIX；两个 CORRECTNESS 阶段定为 NIT，CORRECTNESS-2 另指出导出失败时临时文件 customphrase.export 残留。
建议：改用 ui.entries.toList()。

- 可能的运行时影响：导出自定义短语时若列表同时被修改，可能因并发修改异常使应用崩溃。
- 提出者：CORRECTNESS-1@S41（NIT，9/10）、CORRECTNESS-2@S41（NIT，7/10）、GENERIC-1@S41（SHOULD FIX，8/10）

#### F97 [SHOULD FIX] 用应用打开的文件在返回页面后再次提示导入

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:80`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:104`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:80`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:99`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:104`

PinyinDictionaryFragment 用 savedInstanceState == null 保证「只提示一次」，但本次新增的「我的词」链接（navigateWithAnim(SettingsRoute.UserWords)）会销毁视图而 fragment 留在返回栈；返回时 onViewCreated 的 savedInstanceState 为 null，args.uri 仍在，askLayer 再次询问。
再次导入会对普通词典报「已存在」，或把相同的词再次导入用户层。
建议：用 fragment 字段记录已处理并在 onSaveInstanceState 中保存，或在 onCreate 中处理 URI、用后清除参数。

- 可能的运行时影响：从「我的词」返回词典页后会再次弹出同一文件的导入提示，可能导致重复导入。
- 提出者：CORRECTNESS-1@S41（SHOULD FIX，8/10）、CORRECTNESS-2@S41（SHOULD FIX，8/10）、GENERIC-1@S41（SHOULD FIX，7/10）

#### F98 [SHOULD FIX] 标点卡片排位与编辑规则写在 Fragment 内且无单元测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:128-135`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:153-161`、`app/src/test/java/org/fcitx/fcitx5/android/data/punctuation/PunctuationTest.kt:33-119`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:154`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:154-162`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:150-163`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:133`

`PunctuationEditorFragment` 第 150-163 行的新增、替换、移到首位（以及第 133 行的删除）等列表编辑与卡片排位规则直接写在 Fragment 中，而键盘经 `PunctuationComponent` 直接使用每个键的第一项。
CLAUDE.md 要求决策逻辑放在纯类（ime-core）中并在同一变更中附单元测试，`PunctuationTest` 只覆盖解析与 RawConfig 往返。
建议：提取纯函数（如 `upsert(entries, index, new, makeFirst): Int`、`place(...)` 或 `apply(...)`），测试新增、同键编辑、改键（含 G8b-01 的情形）、已是首位、移到首位与删除。

- 提出者：CORRECTNESS-1@S42（SHOULD FIX，8/10）、CORRECTNESS-2@S42（SHOULD FIX，8/10）、GENERIC-1@S42（SHOULD FIX，7/10）

#### F99 [SHOULD FIX] 用户词导入整体读入内存且无大小上限

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:103-106`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:245-251`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:110-123`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:104`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:247`

`UserWordsFragment` 第 104 行对选择器返回的任意 `text/*` 文件调用 `readBytes()`，`WordLists.decode(...)` 与 `.lines()` 又复制数份；误选大文件会抛出未被 `IOException`/`SecurityException` 捕获的 `OutOfMemoryError` 导致崩溃（CORRECTNESS-2 还指出加载对话框会一直显示）。
GENERIC-1 指出 manifest 未设 `android:process`，设置界面与输入法同进程，OOM 会连带杀掉键盘，大列表还会长时间占用 fcitx 线程；`PinyinCustomPhraseFragment.kt:247`（本分片外）有相同模式。严重度不一：GENERIC-1 为 SHOULD FIX，另两方为 NIT。
建议：先检查 `OpenableColumns.SIZE` 或限制读取量（几 MB 或 20 MB），超限时提示错误，或改为按行流式读取。

- 可能的运行时影响：误选超大文本文件导入时会 OOM 崩溃，并因同进程而连带终止键盘。
- 提出者：CORRECTNESS-1@S43（NIT，7/10）、CORRECTNESS-2@S43（NIT，6/10）、GENERIC-1@S43（SHOULD FIX，8/10）

#### F100 [SHOULD FIX] ToolFingerprint 缓存键逻辑和手写解析器没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:20-79`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164-182`、`build-logic/convention/build.gradle.kts:9-18`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:20`；另见 `build-logic/convention/src/main/kotlin/ToolFingerprint.kt:20-79`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:131-195`

`ToolFingerprint.of` 手工解析 class 常量池（tag 宽度、Long/Double 占两个槽、描述符正则），决定 `compileEngineData`、`compileEngineTables` 是否重跑；若漏掉工具可达的类，过期的 `pinyin.data` 和码表数据会被当作 up to date 静默发布。
build-logic 没有测试源集，而 CLAUDE.md 要求新逻辑同时附带单元测试；STYLE@G1 与 CORRECTNESS-1@S2 指出 VoiceDataPlugin.kt 中的 `Proto`、`vocabulary`、`hotwords` 解析器同样没有测试，TESTING@G1 补充 CI 每次全新检出，只有本地和 release 构建受影响。
建议：给 `build-logic/convention` 加 JUnit 和 `src/test`，用固定的 classpath 验证可达类变化时指纹改变、不可达类变化时不变；CORRECTNESS-1@S2 另建议用小型 SentencePiece 模型 fixture 验证能解码出预期的 `piece<TAB>score` 行。

- 提出者：TESTING@G1（SHOULD FIX，6/10）、STYLE@G1（SHOULD FIX，7/10）、CORRECTNESS-1@S2（SHOULD FIX，7/10）

#### F101 [SHOULD FIX] CommonCrawl 的 HTTP 抓取与重试策略无测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:131-158`、`CommonCrawlTest.kt:86`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:131-158`、`CommonCrawlTest.kt:86`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawlTest.kt:86`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:129-158`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:129`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:158`

CommonCrawlTest 的所有 extract 测试都传本地 `base`，因此 `CommonCrawl.fetch`（:129-158）与 `passing()`（:158）从未执行；这段代码决定哪些状态重试（429、503、≥500、IOException）、404 立即失败、以及 8 次后放弃。
CORRECTNESS-2@S6 指出若 404 被误重试，发布任务中每个文件会卡约 20 分钟；CORRECTNESS-1@S6 与 CORRECTNESS-2@S6 都提到 CLAUDE.md 要求新逻辑配测试。
建议：把状态分类与暂停表抽成纯函数，或注入 fetcher/sleeper（CORRECTNESS-1@S6：让暂停可注入或把 `passing()` 设为 internal），再加测试；CORRECTNESS-1@S6 还建议用 localhost 上的 `com.sun.net.httpserver.HttpServer` 依次返回 503→200 和 404。
TESTING@G2 在同一发现中也指出重试不打日志（见 G2-07）。

- 提出者：CORRECTNESS-1@S6（SHOULD FIX，7/10）、CORRECTNESS-2@S6（SHOULD FIX，7/10）、TESTING@G2（NIT，7/10）

#### F102 [SHOULD FIX] PageCleaner 字种多样性规则会丢掉所有长页面

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:42`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:42`

PageCleaner.kt:42 在「不同字数 × 10 < 汉字数」时丢弃页面；阈值随页面长度线性增长，而不同字数次线性增长并在几千处趋平。
因此足够长的页面无论内容多丰富都会被丢（各票估算的临界长度不同：CORRECTNESS-1@S5 约 2.5–3 万汉字且说明基于假设未实测，CORRECTNESS-2@S5 约 3.5 万，GENERIC-1@S5 约 2–3 万），长文、小说章节和长论坛帖（CORRECTNESS-1@S5：正是 mix 想要的聊天类文本）会离开混合模型语料，且无任何统计报告。
建议：在有界窗口内（如前 2000 汉字，或每 2000 汉字一个窗口）计算多样性，或给阈值设上限；CORRECTNESS-2@S5 另建议改用 n-gram 重复度并让 `clean` 打印每条规则丢弃的页数，GENERIC-1@S5 建议参照 FineWeb 的重复过滤用重复 n-gram 比例。

- 提出者：CORRECTNESS-1@S5（SHOULD FIX，6/10）、CORRECTNESS-2@S5（SHOULD FIX，7/10）、GENERIC-1@S5（SHOULD FIX，7/10）

#### F103 [SHOULD FIX] run-on-device.sh 切换输入法失败被吞且从不恢复

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-eval/run-on-device.sh:19`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/run-on-device.sh:19`

run-on-device.sh:19 用 `adb shell ime set …LatinIME … || true` 把设备默认键盘强制切到 Gboard：在没有 Gboard 的设备（如 AOSP 模拟器镜像）上该命令静默失败，Youmo 仍是活动键盘，于是出现脚本注释所警告的「两个引擎共用同一数据文件」；切换后也从不恢复用户原来的键盘。
修复：先保存 `settings get secure default_input_method`，切换后若仍为 `$PKG` 则报错退出（CORRECTNESS-1@S23 也提出可 `ime disable`），并在 `trap`/退出时恢复原值。
严重度：CORRECTNESS-1@S23、CORRECTNESS-2@S23 为 SHOULD FIX，GENERIC-1@S23 为 NIT；PERFORMANCE 在 G5-31 中也提到未恢复输入法。

- 提出者：CORRECTNESS-1@S23（SHOULD FIX，7/10）、CORRECTNESS-2@S23（SHOULD FIX，7/10）、GENERIC-1@S23（NIT，6/10）

#### F104 [SHOULD FIX] chinese-addons 手抄的顶层 CMake 已与上游不同且无漂移检查

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:32`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:34`；另见 `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:34-53`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:36`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:32`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:44`

上游顶层强制需要 libime，所以 lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt 改为手工复刻其顶层设置（GNUInstallDirs、CompilerSettings、ENABLE_OPENCC、config.h 等）；三票都认为这一做法合理，但复刻品会在每次 submodule 升级时悄悄漂移：新增的 #cmakedefine、option() 或必需的 find_package 不会报错，只表现为构建或行为差异。
CORRECTNESS-2@S27 指出已出现差异：PROJECT_VERSION 是包装层的 0.1.3（影响 chttrans、punctuation、fullwidth 的 Version= 和导出的 Fcitx5ModulePunctuation 版本），REQUIRED_FCITX_VERSION 未设置；它与 CORRECTNESS-1@S27 都指出 line 32 的 OpenCC 不是 REQUIRED 而 line 44 强制 ENABLE_OPENCC ON（GENERIC-1@S27 单独报告了这一点，见 G6-50）。CORRECTNESS-1@S27 还指出丢掉了 CMAKE_FIND_PACKAGE_PREFER_CONFIG，之后的 find_package(OpenCC) 会用到 submodule 的 FindOpenCC.cmake。
修复：保留 PREFER_CONFIG、line 32 改为 REQUIRED、从 submodule 的 project() 取 PROJECT_VERSION 和 REQUIRED_FCITX_VERSION，并在注释中记录所对应的 submodule 提交（GENERIC-1@S27 给出 0d3fd040）；CORRECTNESS-2@S27 另建议在配置时比对 CMakeLists.txt 与 config.h.in 的 SHA256，不符即失败。

- 提出者：CORRECTNESS-1@S27（NIT，6/10）、CORRECTNESS-2@S27（SHOULD FIX，7/10）、GENERIC-1@S27（NIT，6/10）

#### F105 [SHOULD FIX] 导入失败后仍在运行的引擎继续写已被替换的日志文件

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:117`、`AdvancedSettingsFragment.kt:76`、`lib/ime-core/.../store/RecordStore.kt:46`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:117`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:115`

`UserDataManager.import`（:117）的注释假定导入后 app 会立即退出；但若 `copyRecursively` 已开始替换 `engine/` 下的日志后抛出异常，AdvancedSettingsFragment 会调用 `FcitxDaemon.startFcitx()`，app 继续运行。`EngineBridge.engines` 从未被关闭（`Engines.close()` 没有调用方，GENERIC-1@S30 指出 `Engines.reload()` 也不会重开 pinyin `UserStore`），其 `RecordStore` 追加流仍指向已被 unlink 的文件。
后果：之后学到的内容在重启后丢失，内存与磁盘不再一致；CORRECTNESS-2@S30 还指出下一次压缩会把旧的内存计数改名覆盖到导入的日志上。
修复：失败时也像成功路径一样退出进程，或先关闭并重新创建引擎存储；CORRECTNESS-1@S30 还建议先复制到暂存目录，再整体改名就位。

- 可能的运行时影响：导入用户数据中途失败后，之后学到的词在重启后会丢失，并可能覆盖刚导入的学习记录。
- 提出者：CORRECTNESS-1@S30（SHOULD FIX，6/10）、CORRECTNESS-2@S30（SHOULD FIX，7/10）、GENERIC-1@S30（SHOULD FIX，7/10）

#### F106 [SHOULD FIX] 候选为空时点下一页会滚动到 -1，导致输入法崩溃

- 来源：第一轮（整仓 @07d2778） · 共识：3/15 位 reviewer（3 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/GridExpandedCandidateWindow.kt:85`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/GridExpandedCandidateWindow.kt:85`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/FlexboxExpandedCandidateWindow.kt:91`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyView.kt:115`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:372`

`itemCount == 0` 时，`nextPage()` 把目标位置钳到 `itemCount - 1 = -1`，`SmoothScroller.start` 抛 `IllegalArgumentException`（Invalid target position），主线程崩溃。`pageDnBtn` 初始可用（KeyView.kt:115），只有 `onScrolled` 会禁用它，而空布局不会触发 `onScrolled`；所以在第一页从 fcitx 线程返回之前（该线程可能正忙于 25M 模型的 refine）点 ↓ 就会触发。FlexboxExpandedCandidateWindow.kt:91 是同样的代码。GENERIC-1@S35 另指出 `total` 未知（-1）的候选栏也可能触发。
修复：在 `nextPage()`/`prevPage()` 开头遇到 `itemCount == 0` 直接返回，或让 `pageDnBtn` 初始为禁用。

- 可能的运行时影响：在第一页候选加载出来之前点击下一页按钮，会让输入法崩溃。
- 提出者：CORRECTNESS-1@S35（SHOULD FIX，6/10）、CORRECTNESS-2@S35（SHOULD FIX，7/10）、GENERIC-1@S35（SHOULD FIX，6/10）

#### F107 [SHOULD FIX] textAction 按键/提交判定逻辑无单元测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:165`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:139`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:165`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:139`

BaseKeyboard.textAction 决定用户选择的滑动文字作为 FcitxKeyAction 发给 fcitx 还是作为 CommitAction 直接提交（ASCII 标点或数字、字母、多字符、非 ASCII），没有任何测试。
CLAUDE.md 要求决策逻辑放在 lib/ime-core 并在同一改动中附单元测试；CORRECTNESS-2 同时指出 KeyboardWindow.kt:132/139 的 T9 别名也未被测试。
建议：移为 ime-core 的纯函数（如 SwipeTextPolicy，放在 PendingInputPolicy 旁）并逐类测试。CORRECTNESS-1 定为 NIT，另两个阶段为 SHOULD FIX。

- 提出者：CORRECTNESS-1@S37（NIT，6/10）、CORRECTNESS-2@S37（SHOULD FIX，7/10）、GENERIC-1@S37（SHOULD FIX，7/10）

#### F108 [SHOULD FIX] 密码框中选择的符号被记入最近使用

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING, 1 NIT PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:95`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:80`、`app/src/main/java/org/fcitx/fcitx5/android/data/RecentlyUsed.kt:20`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:96`

PickerWindow 对每个 CommitAction 调用 insertRecent，不检查 service.inPasswordField；符键在密码框中也会打开符号面板，选中的符号和表情被写入 SharedPreferences（picker_recently_used）并按时间顺序显示，CORRECTNESS-2 认为这会暴露密码中的符号及其顺序。
引擎在敏感字段中不保留输入（learning = false，PRIVACY.md），上游也有同样问题；CORRECTNESS-1 和 GENERIC-1 标为 PRE-EXISTING，CORRECTNESS-2 未标并定为 SHOULD FIX。
建议：在密码或不学习字段中跳过 insertRecent。

- 可能的运行时影响：在密码框中通过符号面板输入的符号会被保存并在最近使用中按顺序显示，泄露密码字符。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S38（NIT PRE-EXISTING，8/10）、CORRECTNESS-2@S38（SHOULD FIX，7/10）、GENERIC-1@S38（SHOULD FIX PRE-EXISTING，7/10）

#### F109 [SHOULD FIX] 语言区域到标签的映射没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`AppLanguage.kt:26-36`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`AppLanguage.kt:26-36`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:26`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/AppLanguage.kt:27`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`

current() 的规则（Hant 或 TW/HK/MO → zh-TW，其他中文 → zh-CN，en → en，其余 → 跟随系统）是决策逻辑，位于 app 中且无测试；CLAUDE.md 要求放在 lib/ime-core 并在同一改动中附单元测试。
TESTING 指出一个小型表格测试就能发现与 tags 的不一致（会在语言对话框中选错项），并提到 KeyboardWindow.kt:132 的 T9 布局别名有同样的缺口。
建议：在 ime-core 中提供纯函数 (language, script, country) -> tag，测试 zh-Hant、zh-Hant-SG、zh-Hans-TW、zh-HK、zh-SG、en-GB、de、ja 等。TESTING 定为 NIT，两个 CORRECTNESS 阶段为 SHOULD FIX。

- 提出者：CORRECTNESS-1@S40（SHOULD FIX，7/10）、CORRECTNESS-2@S40（SHOULD FIX，7/10）、TESTING@G8（NIT，6/10）

#### F110 [SHOULD FIX] 每次编辑即保存：保存可能乱序或丢失且失败无提示

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:100-102`、`app/src/main/java/org/fcitx/fcitx5/android/daemon/Functions.kt:11-14`、`app/src/main/java/org/fcitx/fcitx5/android/core/FcitxLifecycle.kt:29`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:102`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:100-102`、`app/src/main/java/org/fcitx/fcitx5/android/core/FcitxLifecycle.kt:29`

每次编辑都经 `launchOnReady` 在 FcitxLifecycle 的 `Dispatchers.Default` 作用域（`FcitxLifecycle.kt:29`）单独启动协程保存；fcitx 未 READY（如重启期间）时多个保存在 `whenReady` 中等待，恢复顺序不定，较旧的快照可能最后写入并覆盖新编辑。上游只在 onStop 保存一次。
CORRECTNESS-2 与 GENERIC-1 还指出 STOPPING/`ON_STOP` 时 `job.cancelChildren()` 会静默丢弃待保存项；三方都指出保存失败不记日志、页面仍显示该编辑。GENERIC-1 认为实际发生竞争的可能性不大。
建议：用单一串行写入者（`Mutex`，或只保留最新快照的 `Channel(CONFLATED)`）保存，并记录日志或 toast 提示失败。

- 可能的运行时影响：fcitx 未就绪或页面停止时，用户最新的标点编辑可能被旧快照覆盖或丢失且没有任何提示。
- 提出者：CORRECTNESS-1@S42（SHOULD FIX，7/10）、CORRECTNESS-2@S42（SHOULD FIX，6/10）、GENERIC-1@S42（SHOULD FIX，6/10）

#### F111 [SHOULD FIX] ChatCounts 逐字建 String，既多分配又拆开 Ext-B 字

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ChatCounts.kt:47`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ChatCounts.kt:66`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:647-652`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ChatCounts.kt:47`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ChatCounts.kt:66`

ChatCounts.kt:47 的 `chars[i].toString() !in ids` 按单个 UTF-16 单元检查，并为语料中每个字符分配一个 String；PERFORMANCE@G2 另指出 :66 每个位置还要为最多 8 种长度各建子串并查 `HashMap<String, Int>`，该循环在 mix 和每个 `weight-<w>` 步骤中单线程运行。
CORRECTNESS-1@S4 与 GENERIC-1@S4 指出代理对的半个字永远不是单字词，于是含 CJK Ext-B 字（dict_extb.txt）的词永远拿不到聊天计数；PERFORMANCE@G2 只谈性能。
建议：按码点判断，或预先计算单字词的 BooleanArray/BitSet；PERFORMANCE@G2 建议仿照 ime-core 的 `WordIndex` 对 `model.words` 建开放寻址索引并配 `BooleanArray(65536)`，并说明未实测收益。

- 提出者：CORRECTNESS-1@S4（NIT，7/10）、GENERIC-1@S4（NIT，7/10）、PERFORMANCE@G2（SHOULD FIX，6/10）

#### F112 [SHOULD FIX] LayerPrior.of 在解码热循环中做装箱 HashMap 查找

- 来源：第一轮（整仓 @07d2778） · 共识：3/15 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:56`、`LayerPrior.kt:73`、`UserModel.kt:235`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:56`、`LayerPrior.kt:73`、`UserModel.kt:235`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:56`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:190`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:66`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:235`

`of()` 在每次 `scoreAfter` 中运行，`layerOf` 总是先调用 `extra(word)`；Engines.kt:190 把 `extra` 接到 `UserModel.layerOf`，后者读 `HashMap<Int, Int>`（UserModel.kt:66、:235），id 大于 127 时每次装箱，而 PinyinDecoder KDoc 说几乎全部解码时间都花在打分上（每键数千次调用）。
建议：`UserModel.list` 只给 id 不小于模型词表大小（`modelWords`）的词设包层，可对更小的 id 跳过 `extra`；或预计算从 `vocabulary.size` 起索引的 `IntArray`。严重度：GENERIC-1@S9 为 SHOULD FIX，另两票为 NIT。

- 提出者：CORRECTNESS-1@S9（NIT，6/10）、CORRECTNESS-2@S9（NIT，7/10）、GENERIC-1@S9（SHOULD FIX，6/10）

#### F113 [SHOULD FIX] 「拼音仅在被选中时上屏」的测试只覆盖超过码长的拼音

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`TableSessionTest.kt:609-619`、`TableSession.kt:161-165`、`TableOptions.kt:36`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TableSessionTest.kt:609-619`、`TableSession.kt:161-165`、`TableOptions.kt:36`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:609`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:164`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:161`

测试名称说拼音只在被选中时上屏，但 `commitsItself`（TableSession.kt:161-164）不排除拼音条目，阻止上屏的只是 `input.length <= table.maxLength`；唯一的单候选用例 `zhongguo` 长 8 超过 码长 4，而同一测试最后 `zhongx` 不经选择就上屏了 中。
因此码长以内完整拼写的单独拼音条目（如 `@ni 你`、`@ei 诶`）会自行上屏；CORRECTNESS-2 说明未核查真实 五笔拼音 数据多常遇到这种情况。
建议：加一个短拼音单候选的用例；若拼音只应在被选中时上屏，就在 `commitsItself` 中排除 `item.pinyin`，否则把测试改名为「超过码长」。严重度不一：CORRECTNESS-2 评为 SHOULD FIX，其余为 NIT。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）、CORRECTNESS-2@S19（SHOULD FIX，6/10）、GENERIC-1@S19（NIT，6/10）

#### F114 [SHOULD FIX] 导出的 MainActivity 接受任意应用发来的路由

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:104`、`app/src/main/AndroidManifest.xml:66`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Intent.kt:22`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:104`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:103`、`app/src/main/java/org/fcitx/fcitx5/android/utils/AppUtil.kt:30`

MainActivity 是导出的，显式 intent 会绕过 intent filter，任何应用都能发送携带构造的 SettingsRoute parcel 的 ACTION_RUN，打开 UI 隐藏的路由（GlobalConfig、带其提供的 RawConfig 的 ListConfig），而只有 AppUtil.kt:30 需要这条路径。
GENERIC-1 指出可用 QuickPhraseEdit(CustomQuickPhrase(file = 任意路径)) 打开应用私有文件，用户保存时会读写它，畸形 parcel 会以未捕获的 BadParcelableException 使活动崩溃；两个 CORRECTNESS 阶段认为影响仅限打开设置页、操作仍需用户确认，CORRECTNESS-1 注明代码来自上游。
建议：内部启动走非导出的 alias，或只接受 AppUtil.launchMainToDest 发送的路由，并用 runCatching 包裹 parcel 读取。GENERIC-1 定为 SHOULD FIX，两个 CORRECTNESS 阶段为 NIT。

- 可能的运行时影响：其他应用可让设置界面打开应用私有文件的快捷短语编辑器，或用畸形 parcel 使设置活动崩溃。
- 提出者：CORRECTNESS-1@S40（NIT，6/10）、CORRECTNESS-2@S40（NIT，6/10）、GENERIC-1@S40（SHOULD FIX，6/10）

#### F115 [SHOULD FIX] 剪贴板编辑在活动销毁时可能丢失

- 来源：第一轮（整仓 @07d2778） · 共识：3/11 位 reviewer（2 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:49`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:87`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:49`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:55`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:82`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt:87`

ClipboardEditActivity.finishEditing 在活动的 MainScope 中启动数据库更新（updateText）和复制（setPrimaryClip），随后调用 finish()，onDestroy（:87）会取消该作用域；若协程尚未开始或 Room 的挂起更新尚未返回，编辑和复制都会丢失（GENERIC-1 另指出 onStop :82 也会结束活动）。
建议：在超出活动生命周期的作用域（如 ClipboardManager 自身的作用域）或 NonCancellable 中执行写入。

- 可能的运行时影响：编辑剪贴板条目后保存的修改和复制可能丢失。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 跨模块组合并：G8a-56、G9-09（ClipboardEditActivity 复制按钮可能只更新条目而未复制）
- 提出者：CORRECTNESS-2@S40（SHOULD FIX，6/10）、GENERIC-1@S40（SHOULD FIX，5/10）、GENERIC-1@S47（SHOULD FIX PRE-EXISTING，6/10）

#### F116 [SHOULD FIX] setCandidatePagingMode 未判空就解引用 activeIC_

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（2 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 5/10 · 验证：✅ 已确认（`app/src/main/cpp/androidfrontend/androidfrontend.cpp:487`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/androidfrontend/androidfrontend.cpp:484`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:108`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:109`

androidfrontend.cpp:484 的 setCandidatePagingMode 直接使用 activeIC_，而其他入口都先检查 if (!activeIC_)。deactivateInputContext 会把 activeIC_ 置空，InputDeviceManager（FcitxInputMethodService.kt:108-111，evaluateOnKeyDown）在输入设备类型变化时独立投递该调用；若此时没有活动的输入上下文，fcitx 线程会解引用空指针，IME 进程以 SIGSEGV 崩溃。
三票都指出这是自 fork 点 19596419 未改动的上游代码，但只有 CORRECTNESS-1@S26 标为 PRE-EXISTING。
修复：先保存 pagingMode_，再 if (!activeIC_) return;。

- 可能的运行时影响：输入设备变化时若没有活动的输入上下文，IME 进程会因空指针崩溃。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S26（SHOULD FIX PRE-EXISTING，6/10）、CORRECTNESS-2@S26（SHOULD FIX，5/10）、GENERIC-1@S26（SHOULD FIX，4/10）

#### F118 [SHOULD FIX] 标点键输入框接受多个字符

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 5/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:140-145`、`app/src/main/cpp/androidengine/androidengine.cpp:287-290`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:141`

键字段只检查非空；`pushPunctuation` 只传入单个码点（`keySymToUnicode`），`PunctuationComponent` 按完整键标签匹配，fcitx 标点插件据信也跳过长度不为 1 的键（子模块未检出，未核实）。因此键为 `ab` 的卡片能保存并画在 36dp 键帽中（会被裁切），却永远不会触发。
严重度不一：CORRECTNESS-1 与 GENERIC-1 为 SHOULD FIX，CORRECTNESS-2 为 NIT。
建议：`codePointCount != 1` 时拒绝，并显示与空键相同的字段错误。

- 可能的运行时影响：键为多个字符的标点卡片能保存但打字时永不生效。
- 提出者：CORRECTNESS-1@S42（SHOULD FIX，5/10）、CORRECTNESS-2@S42（NIT，6/10）、GENERIC-1@S42（SHOULD FIX，5/10）

#### F119 [SHOULD FIX] 快捷短语解析用 trim() 去掉了 fcitx 保留的 Unicode 空格

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`K/data/quickphrase/QuickPhraseEntry.kt:19`、`K/engine/data/SourceException.kt:13-17`、`K/data/quickphrase/QuickPhraseData.kt:13`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntry.kt:19`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseEntryTest.kt:133`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/SourceException.kt:13`

`QuickPhraseEntry.fromLine` 用 Kotlin `trim()`（基于 `Char.isWhitespace`，含 U+3000、U+00A0），而 fcitx 只裁剪 ASCII `FCITX_WHITESPACE`（第 15 行 `WhiteSpaces` 已镜像该集合），`escapeForValue` 也不会给 U+3000 加引号。
结果：`kg 　`（输出全角空格的短语）解析为 null，`CustomQuickPhrase.loadData` 丢弃该条目，编辑器下次 `saveData` 把它从文件中删除，`QuickPhraseManager.importFromFile` 拒绝 fcitx 能读的文件；以 U+3000 结尾的短语会丢失该字符。SourceException.kt:13-17 记录了同一陷阱。
建议：改为 `line.trim { it in WhiteSpaces }`，并在 `QuickPhraseEntryTest` 往返测试中加入 U+3000 与 NBSP 用例。

- 可能的运行时影响：只含全角空格或以 U+3000 结尾的快捷短语会被丢弃或截断，编辑器保存后从用户文件中删除或改写。
- 提出者：GENERIC-1@S7（SHOULD FIX，8/10）、CORRECTNESS-1@S15（SHOULD FIX，9/10）

#### F120 [SHOULD FIX] SharedLexiconTest 下次启动“告知”检查无法失败

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100`、`Engines.kt:628-629`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100`、`Engines.kt:628-629`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:99`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:96`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:104`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:616`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:618`

第一次运行已生成 `wubi.user.told`，测试只删除 `pinyin.user` 而保留该标记，因此下次启动时 `Engines.tellPinyinOnce`（Engines.kt:616-619）跳过重新告知、不经过 `learnSaved` 路径；而解码器对 `haoni` 本就会由 好 + 你 组出 好你，所以第 96、104 行的断言无论是否学到都会通过（第 97 行才是真实检查）。
建议：同时删除 `Engines.userTable("engine-wubi") + Engines.TOLD`，并在第二段断言 `userWords()` 中 好你 为 LEARNED；TESTING@G3 还建议加反向用例：`.told` 存在时，被移出拼音的词不会被放回。

- 提出者：CORRECTNESS-2@S16（SHOULD FIX，8/10）、TESTING@G3（SHOULD FIX，9/10）

#### F121 [SHOULD FIX] Reranker测试中的模型从不读取光标前上下文

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`TinyModel.kt:23`、`SentenceModel.kt:198`、`Reranker.kt:63`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TinyModel.kt:23`、`SentenceModel.kt:198`、`Reranker.kt:63`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:14`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:17`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:200`

`TinyModel` 的窗口是 8，而 `Reranker` 总以默认的 room 24（`ROOM`）构建 `model.Scorer()`，所以 `maxContext = max(0, 8-1-24) = 0`（SentenceModel.kt:200），每次调用都丢掉上下文「的」；CORRECTNESS-1 指出 EnginesTest 也用默认 `TinyModel`，即使 `Reranker.refine` 不再传 `context`，所有测试仍通过，而 README 承诺两个模型都读光标前文本。
建议：在 RerankerTest 中用 `TinyModel(window = 32)`，并加一个上下文改变所选读法的用例。
严重度不一：CORRECTNESS-1 评为 SHOULD FIX，CORRECTNESS-2 评为 NIT。

- 提出者：CORRECTNESS-1@S17（SHOULD FIX，9/10）、CORRECTNESS-2@S17（NIT，9/10）

#### F122 [SHOULD FIX] aTextIsForgottenHoweverItWasRead 只覆盖一种读音

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:345-347`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:345-347`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:343`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:347`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:641`

该测试两次 `learn` 都用同一条目 `好吗(hao ma)` 并断言 `entriesOf(...).size == 1`，因此 `forgetText` 对每个读音逐一 `forget` 的循环从未执行；只遗忘第一个读音的回归也能通过，而这会破坏 `Engines.forgetEverywhere`（Engines.kt:641），也就是 README 承诺的长按删除。
建议用两种读音学习同一文本（CORRECTNESS-2@S20 举 hao ma 与 hao a；CORRECTNESS-1@S20 建议包含读两种音的字典词，如 行 hang/xing），断言有 2 个条目，再断言计数、trie 和 `ownTexts` 中都已移除。

- 提出者：CORRECTNESS-1@S20（SHOULD FIX，9/10）、CORRECTNESS-2@S20（SHOULD FIX，9/10）

#### F123 [SHOULD FIX] FcitxTest 修改设备上持久化的设置和数据且不恢复

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`FcitxTest.kt:94`、`native-lib.cpp:182`、`FcitxTest.kt:95-104`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`FcitxTest.kt:94`、`native-lib.cpp:182`、`FcitxTest.kt:95-104`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:110`；另见 `app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:94`、`app/src/main/cpp/native-lib.cpp:182`、`app/src/main/cpp/native-lib.cpp:201`

`setUpEngine` 会保存启用的输入法列表（native-lib.cpp:182 的 `imMgr.save()`）并写入 `ShowInputMethodInformation=false`（native-lib.cpp:201），而 `cleanup()`/`@After` 只恢复设置之后的列表，两项都没有还原；CORRECTNESS-1@S49 指出，由于输入法名的问题，保存下来的甚至是 fcitx 丢弃未知名之后的空列表。没有调用 `setCapFlags` 时学习是开着的，提交的测试文本（如 你好、你好世界）会进入设备上 app 的用户数据。SoftKeyboardTest 和 EngineEvalRunner 都会恢复自己改过的东西。
修复：在 `setup`/`@BeforeClass` 记录 `enabledIme()` 和 `getGlobalConfig()`，在 `cleanup`/`@AfterClass` 写回。

- 提出者：CORRECTNESS-1@S49（SHOULD FIX，9/10）、GENERIC-1@S49（SHOULD FIX，8/10）

#### F124 [SHOULD FIX] 档位列表用字面量重复偏好的键和默认值

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`ThemePrefs.kt:44`、`AppPrefs.kt:176-180`、`ThemePrefs.kt:124-126`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ThemePrefs.kt:44`、`AppPrefs.kt:176-180`、`ThemePrefs.kt:124-126`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:42`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:44`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:54`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:63`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:124`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:126`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:141`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:142`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:177`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:101`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:301`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:334`

ThemePrefs.kt:42-63 等处的 `levels(…)` 用字符串和整数字面量重复了 AppPrefs.Keyboard（:177-224）和 ThemePrefs 中声明的键与默认值，同样的写法还出现在 ThemePrefs.kt:124、:126、:141、:142 以及 AppPrefs.kt:101、:301、:334。
若有人修改默认值或给键改名，`Levels.current()` 会对从未改过设置的用户显示错误的档位，或悄悄写入没有人读取的键；STYLE@G7 还指出 `Level(vararg values)` 必须按位置与 `keys` 一一对应（数据泥团）。
修复：传入已经带有 `key` 和 `defaultValue` 的 `ManagedPreference.PInt`，或改用共享常量，让每个值与它的偏好绑定在一起。

- 提出者：CORRECTNESS-2@S32（NIT，8/10）、STYLE@G7（SHOULD FIX，9/10）

#### F125 [SHOULD FIX] 重命名和移动忽略 renameTo 的失败结果

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:211`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:223`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:211`；另见 `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:222`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:223`

renameDocument（:211）和 moveDocument（:222/:223）忽略 renameTo 返回的布尔值，总是返回新 ID；文件管理器显示成功，但原文件仍是旧名，下一次查询该 ID 抛 FileNotFoundException，文件看似消失。
建议：检查返回值并在失败时抛 FileNotFoundException，与 createDocument、deleteDocument 一致。

- 可能的运行时影响：通过文件管理器重命名或移动失败时仍报告成功，文件随后看似消失。
- 提出者：CORRECTNESS-2@S39（SHOULD FIX，9/10）、GENERIC-1@S39（SHOULD FIX，9/10）

#### F126 [SHOULD FIX] 保存失败时自定义短语的修改丢失

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:302`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:303`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:309`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:303`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:302`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:307`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:310`

resetDustman() 在 saveOver 之前清除 dirty；若 saveOver 抛 IOException，下一次 onStop 看到未修改而在 :302 提前返回，修改永远不会写入；:307/:308 的注释「下次保存仍会包含这次的改动」只在用户再次编辑时成立。
建议：只在 saveOver 成功后用 items 快照重置 dustman，或在 catch 中调用 dustman.forceDirty() 重新标脏。

- 可能的运行时影响：保存自定义短语时一旦发生 IO 错误，用户的修改会被静默丢弃。
- 提出者：CORRECTNESS-2@S41（SHOULD FIX，8/10）、GENERIC-1@S41（SHOULD FIX，9/10）

#### F127 [SHOULD FIX] 设置页手工复制引擎配置键与方案，未用 ime-core 常量

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:84-98`、`app/build.gradle.kts:126`、`EngineSettings.kt:45`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:84-98`、`app/build.gradle.kts:126`、`EngineSettings.kt:45`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:84`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:86`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:98`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:111-127`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:122`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:141`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:200`

`InputMethodSettings` 用字符串字面量重新列出 `PAGE_SIZES`/`numbers(3..10)`、`SHUANGPIN` 方案名（86）、`FUZZY` 键（98）、`ShuangpinProfile`/`Fuzzy/NG_GN`/`LatinWords`（111-127）、码表键（141 起）和 `of()` 中的输入法名（200），而这些已在 ime-core 的 `EngineSettings`、`TableSettings`、`Fuzzy`、`Engines` 中定义。
若 androidengine.h 或 ime-core 改名或新增方案，`raw()` 的 `getOrCreate` 会静默创建空节点，页面写入引擎不读的键、开关失效。严重度不一：STYLE 为 SHOULD FIX，CORRECTNESS-2 为 NIT。
建议：用这些常量构建（如 `EngineSettings.SCHEMES.keys`、`Fuzzy.entries`、`$FUZZY/$TYPOS`、`TableSettings.*`、`Engines.T9`），此处只保留标签；或加测试逐一对照这些常量。

- 提出者：CORRECTNESS-2@S43（NIT，8/10）、STYLE@G8（SHOULD FIX，9/10）

#### F128 [SHOULD FIX] gradle.properties 提交了单机专用的 daemon=false

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`gradle.properties:36`、`gradle.properties:13`、`gradle.properties:10`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`gradle.properties:36`、`gradle.properties:13`、`gradle.properties:10`）
- 位置：`gradle.properties:36`；另见 `gradle.properties:13`、`gradle.properties:10`、`gradle.properties:12`

`org.gradle.daemon=false` 的理由是「这台机器还要跑模拟器」，却作用于所有贡献者，每次命令行构建都启动新的 JVM、失去热 daemon；CORRECTNESS-2@S1 指出注释里写的真实原因是空闲 daemon 占着堆长达三小时。
PERFORMANCE@G1 补充：它与 `kotlin.compiler.execution.strategy=in-process`（第 13 行）合用，使 Gradle 和 Kotlin 编译器每次冷启动、无 JIT 预热和文件系统监视，拖慢本地构建以及 unit_test.yml、emulator_test.yml 中的多次调用；第 10 行 1536m 的堆低于上游的 2048m，还要容纳 Kotlin 编译器和 R8，GC 压力和 OOM 风险更高。
建议：把 `daemon=false`（PERFORMANCE@G1 还包括堆上限）移到那台机器的 `~/.gradle/gradle.properties`；PERFORMANCE@G1 建议项目文件保留至少 2g 并考虑 `org.gradle.parallel=true`，CORRECTNESS-2@S1 建议在此处改设 `org.gradle.daemon.idletimeout=600000`。

- 提出者：PERFORMANCE@G1（SHOULD FIX，7/10）、CORRECTNESS-2@S1（SHOULD FIX，8/10）

#### F129 [SHOULD FIX] CI 每次重新下载约 400 MB 并重编引擎数据

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:122`、`VoiceDataPlugin.kt:64`、`.github/workflows/pull_request.yml:61`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:122`、`VoiceDataPlugin.kt:64`、`.github/workflows/pull_request.yml:61`）
- 位置：`.github/workflows/pull_request.yml:61`；另见 `.github/workflows/pull_request.yml:64`、`.github/workflows/nix.yml:26`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:115`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:139`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:303`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:380`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:64`

下载存放在 `<root>/.gradle/engine-downloads`，`setup-gradle` 只缓存 Gradle 用户目录，也没有工作流缓存该目录，所以 pull_request.yml 的 4 个 OS、nix.yml 和 emulator_test.yml 每次推送都重新拉取 libime 源码、混合模型和句子模型，再跑约 2 分钟、5 GB 的编译。
GENERIC-1@S1 指出 GitHub release 下载失败时构建也随之失败；PERFORMANCE@G1 补充 `org.gradle.caching` 未开启，第 303、380 行的 `@CacheableTask` 不起作用，`clean` 后还会把 1 GB 的 lm.arpa 重新解压进 build/ 再编译。
建议：为 `.gradle/engine-downloads` 加 `actions/cache`，键取 EngineDataPlugin.kt 与 VoiceDataPlugin.kt 的 `hashFiles`（PERFORMANCE@G1）或 build-logic 中的 SHA-256 常量（GENERIC-1@S1）；PERFORMANCE@G1 另建议设 `org.gradle.caching=true`，并把语言模型解压到 `build/` 之外。

- 提出者：PERFORMANCE@G1（SHOULD FIX，8/10）、GENERIC-1@S1（SHOULD FIX，7/10）

#### F130 [SHOULD FIX] PageCleaner 垃圾词按子串匹配，误伤正常页面

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:41`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:41`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:81`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:41`

PageCleaner.kt:81 的 `occurrences()` 是普通 `indexOf`，所以 `av`/`AV` 会命中 have、java、save、available、JavaScript、AVG；每千汉字命中数达到阈值（CORRECTNESS-1@S5 说 3 次，GENERIC-1@S5 说 4 次，见 :41）就丢弃整页。
GENERIC-1@S5 还指出 `成人` 命中 未成年人、`久久` 命中 久久不能平静，`亚洲`、`黄色`、`欧美`、`日韩`、`彩票`、`在线观看` 本身就是日常词；中文编程页、引用英文句子的页面、亚洲杯报道等因此离开 mixed-LM 语料和新词来源，损失新词召回。
建议：仅当两侧没有 ASCII 字母时才计 av/AV（如 `(?<![A-Za-z])(?i:av)(?![A-Za-z])`），CORRECTNESS-1@S5 要求加含 java/have 的 PageCleanerTest；GENERIC-1@S5 另建议移除或降权歧义词，或要求至少两个不同的垃圾词。

- 提出者：CORRECTNESS-1@S5（SHOULD FIX，8/10）、GENERIC-1@S5（SHOULD FIX，7/10）

#### F131 [SHOULD FIX] NewWords 采样落空时熵为 NaN，词串仅凭 pmi 通过

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:174`、`Main.kt:271`、`Main.kt:262`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:174`、`Main.kt:271`、`Main.kt:262`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:174`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:215`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:271`

NewWords.kt:174 的邻字采样步长 ceil(count/40) 是对全局行计数（`add()` 调用次数）判断的；出现集中在少数几行的词串常常一次也采不到（CORRECTNESS-2@S5：count 1000 分布在 20 行时约 44% 概率），`entropy()`（:215）随之返回 NaN。
`Options.passes`（Main.kt:271）把 NaN 当作「pmi 太低未收集」，只要 pmi ≥ minPmi 就放行；对二字串而言熵是唯一实质检验，而这类集中、重复、低多样性的词串正是熵过滤要拦的，CORRECTNESS-2@S5 指出它们会进入 pack.words、examples 和 probe。
建议：按每个词串自己的出现计数采样（GENERIC-1@S5：`seen[run]++ % stride == 0`），或总是采第一次出现，或为「已收集但未采样」写一个不会通过的值或可区分的标记。

- 提出者：CORRECTNESS-2@S5（SHOULD FIX，8/10）、GENERIC-1@S5（SHOULD FIX，7/10）

#### F132 [SHOULD FIX] table --words --pinyin 等 CLI 路径无测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:749-754`、`EngineDataPlugin.kt:434-437`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:749-754`、`EngineDataPlugin.kt:434-437`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:291`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:749`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:754`

`aCodeTableIsCompiled` 运行 `table` 时不带 `--words`/`--pinyin`；Main.kt 中 `score()` 取模型分与 pack 分的最大值、`{ it in packScore }`（:754）标记新词，这段胶水决定发布码表中新词的排序（TESTING@G2：承载 README「新词计十倍且永不丢弃」的发布标准），而 ime-core 只用手写 lambda 测 `TableText.addWords`。
GENERIC-1@S6 还列出 `strokes`、`text`、`cc`（`Options.Crawl.parse`）、`pinyin --misreadings`、`examples --per-word` 都没有测试（grep src/test 找不到这些 flag），并引用 CLAUDE.md 新逻辑须配测试的要求。
建议：TESTING@G2 建议扩展该测试，使用 pack 与编译好的 pinyin.data，断言 `CodeTable.texts(code)` 的顺序以及罕见 pack 词与常用词并存；GENERIC-1@S6 建议至少加 `table --words --pinyin` 和 `cc --base <dir>` 的 CLI 测试，复用 CommonCrawlTest 的本地 crawl 布局，并覆盖 101 个文件拆成多个分片及越过较短末分片的续跑。

- 提出者：TESTING@G2（SHOULD FIX，8/10）、GENERIC-1@S6（SHOULD FIX，7/10）

#### F133 [SHOULD FIX] CorruptionTest 遍历漏掉生产中的查找路径

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:48-72`、`CorruptionTest.kt:98`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:48-72`、`CorruptionTest.kt:98`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:96`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:48`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/CorruptionTest.kt:23`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:123`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:118`

CorruptionTest 的 KDoc 要求损坏文件要么加载失败、要么经受所有查找，但 `walkPinyin` 未调用 `WordLayers.layer`、`NgramModel.forEachAfter`、`wordIndex.find`、`misreadings`，且夹具只命名一个层因而没有 WORD_LAYER 段；生产以 `verify = false` 加载 `pinyin.data`（Engines.kt:118），`WordLayers.layer` 可能在按键时抛 `DataFormatException`，`biWord`/`triWord` 也未对 `vocabularySize` 校验。
CORRECTNESS-2@S15 另指出 `walk(bytes)` 把加载与查找放在同一个 `catch (DataFormatException)` 下，即使补上这些调用，查找时抛错也会被算作通过。
建议：用 `.layers(...)` 构建夹具并遍历这些 API，只把加载放进 catch，遍历中的任何异常都应使测试失败；主代码在 WordLayers 加载时 `checkBelow`（见 G3a-27）。严重度：CORRECTNESS-2@S15 为 SHOULD FIX，CORRECTNESS-1@S15 为 NIT（认为数据随签名 APK 发布，风险低）。

- 提出者：CORRECTNESS-2@S15（SHOULD FIX，8/10）、CORRECTNESS-1@S15（NIT，7/10）

#### F134 [SHOULD FIX] 笔画查询选字后用该单字重置了拼音会话的上下文

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:96`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:173-185`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:111`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:96`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:111`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:168`

`StrokeLookup.pick`（第 94-96 行）发送 `Action.Context(text)`，Session.kt 文档说此动作「像 Reset 一样丢弃一切」；`PinyinSession.takeContext`（:168-173）发现新文本不以 `recent` 结尾，于是丢掉此前上屏的词，`recent` 只剩「一」这样的单字。
`Keyboard.context` 只在光标移动时重发宿主文本，引擎自己上屏后不会再发，所以笔画选字后精排器和 refiner 只看到一个字，而 README 说它们读光标前文本；CORRECTNESS-2 指出 StrokesTest.kt:111 把这一行为固定了下来。
建议：传入拼音的 recent 加 `text`，或新增一个追加已上屏文本的动作，并用真实 `PinyinSession` 测试下一次精排上下文为「<之前上屏>一」。

- 可能的运行时影响：用笔画查询选字后，拼音的精排和 refiner 只以该单字为上下文，后续候选排序变差。
- 提出者：CORRECTNESS-1@S12（NIT，7/10）、CORRECTNESS-2@S18（SHOULD FIX，8/10）

#### F135 [SHOULD FIX] 防御恶意导入文件的字典树守卫无测试，模糊测试也到不了

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`LibimeFilesTest.kt:246`、`LibimeTrie.kt:55`、`LibimeTrie.kt:114-120`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`LibimeFilesTest.kt:246`、`LibimeTrie.kt:55`、`LibimeTrie.kt:114-120`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:246`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeTrie.kt:67`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeTrie.kt:55`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeTrie.kt:36`

没有测试到达「siblings out of order」守卫（LibimeTrie.kt:67，防止兄弟环死循环）或 `MAX_DEPTH` 守卫（:55，防栈溢出），GENERIC-1 还指出 IndexOutOfBounds → DataFormatException 的捕获（:36-38）也未测；这些读取器解析用户选择导入的文件。
模糊测试只改动 电报码 表正文的前 4096 字节，即头部和第一棵树尾部的开头，而 `LibimeTrie.read` 把 base/check/sibling/child 数组存在尾部之后，节点数组几乎没被模糊。
建议：手工构造逆序兄弟、1025 层的链和 base 指向尾部之外的字典树，并把变异扩展到整个正文或针对节点数组。

- 提出者：CORRECTNESS-1@S17（SHOULD FIX，8/10）、GENERIC-1@S17（SHOULD FIX，8/10）

#### F136 [SHOULD FIX] Ranking按文本去重掩盖了「不再学习」类断言

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`TableSessionTest.kt:405-410`、`TableSession.kt:270`、`TableUserTest.kt:192-199`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TableSessionTest.kt:405-410`、`TableSession.kt:270`、`TableUserTest.kt:192-199`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:405`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:409`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:410`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:394`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:192`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:199`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:270`

`Ranking` 每个文本只保留一次（`seen.add(item.text)`），所以 :409 的 `[你好]`、`aPhraseCommittedWholeStartsNoPhrase`（:394）的 `[们]` 和 TableUserTest.kt:192/198/199 的 `[你们]`，在删去 `sighted` 中的 `table.contains` 守卫（TableSession.kt:270）、`learnPhrases` 中多字的 `recent.clear()` 或 `SAVED` 回放的 `index < 0` 守卫后依然通过；CORRECTNESS-2 指出回归仍会经 `savedTexts()`/`onSaved` 和多余的日志记录在别处显现。
建议：给会话传入 `TableUser` 并断言其状态，例如空码的 `user.seen` 为空、`savedTexts()` 不含 你们、`isSaved` 为假，或 `forEachRecord` 计数，像 `whatPinyinFoundIsNotLearned` 那样。

- 提出者：CORRECTNESS-1@S19（SHOULD FIX，8/10）、CORRECTNESS-2@S19（SHOULD FIX，8/10）

#### F137 [SHOULD FIX] 超过 64 KB 的 FORGOT 记录被静默丢弃，遗忘在重启后失效

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`UserModel.kt:150`、`UserStore.kt:61`、`RecordStore.kt:105`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserStore.kt:61`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:105`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserLog.kt:68`

`UserLog.forgot`（UserLog.kt:68-69）把所有词写进一条记录；在设置中一次遗忘约 3500-4000 个两字词就会超过 `MAX_RECORD`（64 KB，`1 shl 16`），`RecordStore.append`（RecordStore.kt:105）随即既不写入、也不调用 `onError`、也不压缩。
内存中的计数已清除，但下次启动重放 SENTENCE 记录时这些词又回来了，用户不会得到任何提示。
建议把 FORGOT 拆成多条记录（PERFORMANCE@G4 建议每条不超过约 1000 词），或在记录过大时调用 `store.compact()`，并通过 `onError` 报告被丢弃的记录。

- 可能的运行时影响：在设置中一次遗忘几千个词后，重启输入法这些词会重新出现，且没有任何提示。
- 提出者：CORRECTNESS-2@S13（SHOULD FIX，7/10）、PERFORMANCE@G4（SHOULD FIX，8/10）

#### F138 [SHOULD FIX] release-report 在 score 失败时退出且不写原因

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-eval/release-report.sh:48`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/release-report.sh:48`）
- 位置：`lib/ime-eval/release-report.sh:48`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Report.kt:40`

release-report.sh:48 对每个集合（包括只报告不设门禁的 context、slips、mixed、small）都传 `${BASE:+"$BASE/$name.tsv"}`；BASE 早于某个集合或运行时未带 SMALL（如缺少 small.tsv）时，`score` 抛 FileNotFoundException，脚本 `|| exit 2` 退出，report.txt 里没有任何原因，GENERIC-1@S23 还指出 stderr 也未保存。
修复：在每个 `exit 2` 前写一行原因（同 run 失败的路径）；GENERIC-1@S23 另建议只在 `[ -f "$BASE/$name.tsv" ]` 时传基线，与 `Metrics.flips` 把基线之后新增的样本视为无翻转保持一致。
CORRECTNESS-1@S23 另指出：门禁比较的是 Report.kt:40 四舍五入到 4 位小数后的 p，p 在 0.008333 与 0.00835 之间时会被读成低于 0.05/6，应比较未取整的 p。
严重度：GENERIC-1@S23 为 SHOULD FIX，CORRECTNESS-1@S23 为 NIT。

- 提出者：CORRECTNESS-1@S23（NIT，8/10）、GENERIC-1@S23（SHOULD FIX，8/10）

#### F139 [SHOULD FIX] greedy_search 下传入 blocked 会解引用空的 BPE 编码器

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:233`、`utils.cc:152`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:233`、`utils.cc:152`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:220`；另见 `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:233`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:116`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:158`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/utils.cc:139`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer.h:104`

bpe_encoder_ 只在 modified_beam_search 分支创建（offline-recognizer-transducer-impl.h:116-121、158-161）。使用 greedy_search 且建模单元为 bpe、bbpe 或 cjkchar+bpe 时，带非空 blocked 的 CreateStream 会以空的 bpe_encoder_ 调用 EncodeHotwords，utils.cc:139 的 bpe_encoder->Encode 随即崩溃；CORRECTNESS-2@S52 指出 modified_beam_search 但 bpe_vocab 为空时同样如此。这违背了 offline-recognizer.h:104「其他解码方法忽略 blocked」的承诺。
两票都指出应用配置（modified_beam_search + bpe.vocab）不会走到这条路径，只影响新 C++/Kotlin API 的其他调用方。
修复：if (blocked.empty() || config_.decoding_method != modified_beam_search) return s;，CORRECTNESS-2@S52 另加「单元需要 BPE 却没有 bpe_encoder_」的条件。

- 提出者：CORRECTNESS-2@S52（SHOULD FIX，8/10）、GENERIC-1@S52（NIT，8/10）

#### F140 [SHOULD FIX] 以 URL 开头的整段文字被当作一个 URL 处理，后面的文字被删或被编码

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:44`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:74-76`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:44`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:19`

`ClearURLs.transform`（ClearURLs.kt:43-44）只检查文本是否以 `https?://` 开头，然后把整段剪贴内容当作一个 URI 解析。在 `https://a.com/?utm_source=x 看这个` 中，后面的文字成了 `utm_source` 的值，随该参数一起被删掉；若落在保留的参数里，则被重新百分号编码（`?id=42 看` 变成 `?id=42+%E7%9C%8B`）。改坏的字符串会存进剪贴板历史并用于粘贴建议。
修复：只在去掉首尾空白后整段是单个 URL（`^https?://\S+$`）时转换，或只转换开头的 URL token、其余原样保留，并在 ClearURLsTest 中补上这个用例。CORRECTNESS-1@S30 和 GENERIC-1@S30 在二次解码那条发现（G7-01）中也提到了这一现象。

- 可能的运行时影响：复制「链接加文字」时，链接后面的文字会被删掉或被编码，剪贴板里存下并粘贴出来的是被改动过的内容。
- 提出者：CORRECTNESS-2@S30（SHOULD FIX，8/10）、CORRECTNESS-1@S50（SHOULD FIX，7/10）

#### F141 [SHOULD FIX] Levels.current() 直接相加不同量纲的差值，显示错误的档位

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:48`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:56`、`app/src/main/java/org/fcitx/fcitx5/android/data/InputFeedbacks.kt:83-89`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:47`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:48`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:290`、`app/src/main/java/org/fcitx/fcitx5/android/data/InputFeedbacks.kt:86`

`ManagedPreferenceUi.kt:47-48` 的 `current()` 直接累加各个键的 `abs()` 差值，而振动时长的范围是 0–100 ms、振幅是 0–255（AppPrefs.kt:290-302），结果由振幅主导。常见的旧设置 35/45 ms 加振幅 0（表示设备默认，InputFeedbacks.kt:86-89）会显示为「System default」，键盘却仍振动 35 ms；再选「System default」也没有作用，因为 ListPreference 不保存未变化的值（`pick` 也会提前返回）。
修复：先按各键的取值范围把差值归一化再求和；CORRECTNESS-1@S31 另提出只在完全匹配时显示档位，否则显示「Custom」。

- 可能的运行时影响：振动反馈设置页显示的档位与实际振动不符，用户选择该档位也不会改变实际设置。
- 提出者：CORRECTNESS-1@S31（SHOULD FIX，8/10）、GENERIC-1@S31（NIT，7/10）

#### F142 [SHOULD FIX] 词包首次导入接受的名称，再次导入时被 importPack 拒绝

- 来源：第一轮（整仓 @07d2778） · 共识：2/18 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:84`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:232-233`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:84`、`PinyinDictManager.kt:52-54`、`PinyinDictionaryFragment.kt:232-233`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:83`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:84`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:224`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:232`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:446`

首次导入（`importFromInputStream` → `importFromFile`）接受任何清理过的显示名，例如 `新词 2026.words`；再次导入同名词包则走 `importPack`（PinyinDictManager.kt:83-84），它要求 `WordPack.validName`（只能是 ASCII、不能有空格），于是报「invalid file name」，而在此之前 PinyinDictionaryFragment.kt:224-233 已调用 `setIntoNew` 改变了该词包所在的层。
修复：去掉 `importPack` 中的检查（目标来自已有文件，`queryFileName` 已去掉路径分隔符，包名只用作错误标签，Engines.kt:446），或在首次导入时做同样的检查。

- 可能的运行时影响：用户无法用同名文件替换此前以中文或带空格名称导入的词包，而且替换失败时该词包所在的层已被改动。
- 提出者：CORRECTNESS-1@S31（SHOULD FIX，8/10）、CORRECTNESS-2@S31（SHOULD FIX，8/10）

#### F143 [SHOULD FIX] 原子替换写文件前未 fsync，且同一写法手写了多处

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:61-62`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:166-167`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:61`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:57`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:37`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:65`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:166`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:33`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:59`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:159`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:165`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/ImportedTables.kt:20`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:66`

`CustomPhraseManager.write`（:57-61）先写 `<file>.new` 再改名覆盖原文件，但没有调用 `fd.sync()`；断电后新名字可能指向一个空文件，`load` 读出来就是没有任何短语。`EngineMigration.replace` 已经为此调用 sync，并写明了原因。同样的缺口还在 ImportedDictionaries.kt:37、:65、:166（丢失 `.new-words` 会让所有词典回到基础层）和 ImportedTables.kt:20；键盘每次置顶或删除自定义短语都会执行这段代码。
STYLE@G7 指出这种「写临时文件再改名」的代码手写了六处，持久性不一致，而且 `setIntoNew` 失败时会留下 `.tmp`。
修复：改用 `androidx.core.util.AtomicFile`，或写一个共享 helper（改名前 sync，在 `finally` 中删除临时文件），所有调用点都用它。

- 可能的运行时影响：保存自定义短语或词库分层信息时遇到断电，文件可能变成空的，导致用户短语丢失或所有词典回到基础层。
- 提出者：CORRECTNESS-2@S31（SHOULD FIX，8/10）、STYLE@G7（SHOULD FIX，8/10）

#### F144 [SHOULD FIX] 直接启动存储同步遗漏用户自定义的长按与滑动字符

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:479`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:50`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:325`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:479`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:50`、`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:76`、`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:88`

`syncToDeviceEncryptedStorage`（AppPrefs.kt:479）只复制键盘、候选、剪贴板三个类别和另外五个键，没有复制 `internal.popupOverrides`（AppPrefs.kt:50）。首次解锁前 `FcitxApplication` 用设备保护存储构建 AppPrefs（FcitxApplication.kt:76-81、:88-131），TextKeyboard 和 PopupComponent 于是回退到默认的长按/滑动字符；GENERIC-1@S31 指出，用户在锁屏上用滑动输入的密码因此会对不上。
修复：把 `internal.popupOverrides` 加进同步列表；GENERIC-1@S31 还指出 PopupOverridesFragment 不是 `ManagedPreferenceFragment`，保存时不会触发 `onStop` 同步，需要在它保存时单独同步。

- 可能的运行时影响：首次解锁前在锁屏上输入时，用户自定义的滑动和长按字符失效，可能导致解锁密码输错。
- 提出者：CORRECTNESS-1@S31（NIT，8/10）、GENERIC-1@S31（SHOULD FIX，8/10）

#### F145 [SHOULD FIX] 包名变更后主题背景图的重定位逻辑没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`ThemeFilesManager.kt:55-71`、`TableManager.kt:106-130`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ThemeFilesManager.kt:55-71`、`TableManager.kt:106-130`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:57`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:67`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:115`

`ThemeFilesManager.listThemes`（:57-75）在改包名后为旧备份重新定位背景图，这是 README「主题会一起迁移」的实现；出错时主题只会被丢弃，并留下一条警告日志（:67-69），而 app/src/test 下没有任何 ThemeFilesManager 的测试（data/theme 只有 ThemePresetTest）。CLAUDE.md 要求新逻辑在同一变更中带测试，并把决策逻辑写成纯代码。
修复：把 `(image, dir, exists) -> relocated?` 抽成纯函数，测试两张图都存在、都在 `dir` 中找到、缺一张（被丢弃）以及只保存一次等情况。CORRECTNESS-2@S32 同时指出 TableManager.replaceTableDict（:115-127）也缺测试。

- 提出者：CORRECTNESS-2@S32（SHOULD FIX，7/10）、GENERIC-1@S32（SHOULD FIX，8/10）

#### F146 [SHOULD FIX] 意外的光标报告触发主线程同步读上下文，硬件键每键一次 IPC

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`EditingSession.kt:126`、`FcitxInputMethodService.kt:825`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EditingSession.kt:126`、`FcitxInputMethodService.kt:825`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:824`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:825`

`ResetIfNotEmpty` 和 `FocusOutIn` 现在会调用 `tellTextBeforeCursor`（FcitxInputMethodService.kt:824-825），在 IME 主线程上同步执行 `getTextBeforeCursor(64)` IPC，并为每个键盘排一个 `engineContext` 任务；改动前这条路径只排一个很便宜的 reset。凡是 IME 没有预测到的光标移动都会触发：fcitx 交还的每个硬件按键（`keyEventRelay.replay`）、虚拟方向键（`MoveCursor` 不调用 `selection.predict`）以及回车键事件；编辑器较慢时（WebView、忙碌的 UI 线程），每个键都会让 IME 卡一下。
修复：按 `cursorUpdateIndex` 合并并丢弃过期的读取（像 `MovePreeditCursor` 那样），或推迟到下一次组字开始前再读；GENERIC-1@S33 另建议为重放的可打印键预测光标移动，并说明自己没有核实 native 键盘引擎是否会透传普通键。

- 提出者：CORRECTNESS-1@S33（SHOULD FIX，8/10）、GENERIC-1@S33（SHOULD FIX，6/10）

#### F147 [SHOULD FIX] 文本编辑面板的剪切和退格绕过 ContextMemory，已删的文字仍作上下文

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/editing/TextEditingWindow.kt:81`、`app/src/main/java/org/fcitx/fcitx5/android/input/editing/TextEditingWindow.kt:93`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:54`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/editing/TextEditingWindow.kt:81`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/editing/TextEditingWindow.kt:93`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:325`

键盘的退格路径（`FcitxInputMethodService.handleBackspaceKey`，约 :325）会调用 `contextMemory.cut()` 或 `deleted()`，但文本编辑面板的剪切（TextEditingWindow.kt:81，`performContextMenuAction`）和退格（:93，原始的 `sendDownUpKeyEvents`）直接操作 InputConnection，跳过了这些记账；CORRECTNESS-1@S36 还提到全选。
若面板把输入框清空，下一次未预测的光标报告让 `tellTextBeforeCursor` 看到空的 `before`，`ContextMemory.context` 就返回旧文本（`FRESH_MILLIS` 30 分钟内有效），用户刚删掉的文字成了重排模型的上下文。
修复：通过服务方法做与键盘相同的记账（有选区时调用 `cut()`，每次 DEL 调用 `deleted()`；GENERIC-1@S36 还提到粘贴替换选区的情况），并在面板中使用；CORRECTNESS-1@S36 另提出让 ContextMemory 在任何未预测的光标移动时丢弃条目。

- 可能的运行时影响：用户用文本编辑面板删掉的文字，仍会在 30 分钟内被当作上下文来影响候选。
- 提出者：CORRECTNESS-1@S36（SHOULD FIX，8/10）、GENERIC-1@S36（SHOULD FIX，8/10）

#### F148 [SHOULD FIX] EditorInfo 到 CapabilityFlags 的映射只测试了 PIN

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`CapabilityFlagsTest.kt:27-33`、`CapabilityFlag.kt:103`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`CapabilityFlagsTest.kt:27-33`、`CapabilityFlag.kt:103`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/core/CapabilityFlagsTest.kt:27`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/CapabilityFlag.kt:97`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:670`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:672`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:849`

FcitxInputMethodService.kt:670-672 和 :849 依靠 `fromEditorInfo(..).hasAny(PasswordOrSensitive)` 兑现 PRIVACY.md「密码和敏感字段不读上下文、不学习」的承诺，但 CapabilityFlagsTest 只覆盖了 `NUMBER_VARIATION_PASSWORD`，没有覆盖 `TYPE_TEXT_VARIATION_PASSWORD`、`TYPE_TEXT_VARIATION_WEB_PASSWORD`、`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` 和 `IME_FLAG_NO_PERSONALIZED_LEARNING`（无痕模式）。
修复：加一个表驱动测试，逐一断言这些输入的 `hasAny` 为真，再加一个普通文本的反例。

- 提出者：CORRECTNESS-2@S49（SHOULD FIX，8/10）、TESTING@G7（SHOULD FIX，8/10）

#### F149 [SHOULD FIX] 滑动与长按对同一字符走不同输入路径

- 来源：第一轮（整仓 @07d2778） · 共识：2/18 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:224`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:167`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:160`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:165`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:342`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:225`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:46`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:316`

提交说明称字母的滑动等于其长按列表第一项，但长按弹出把任何单个码点作为 FcitxKeyAction（Source.Popup）发送（PopupKeyboardUi.kt:225），滑动则经 BaseKeyboard.textAction 把字母和非 ASCII 作为 CommitAction 提交。
CORRECTNESS-2：滑动不消耗一次性 Shift，大写时预览显示变换大小写后的字母而实际按原样输入；GENERIC-1：覆盖为 q x … 时滑动会冲掉组合直接提交 x，长按则把 x 送入拼音组合，违背 AppPrefs.kt:46 和 TextKeyboard.kt:316「同一件事」的承诺。
建议：两条路径共用一个路由规则（GENERIC-1 建议放在 lib/ime-core 并附单元测试），或至少对齐预览。CORRECTNESS-2 定为 NIT，GENERIC-1 定为 SHOULD FIX。

- 可能的运行时影响：滑动与长按选择同一覆盖字符时输出不同：滑动直接提交并保留一次性 Shift，长按则进入拼音组合。
- 提出者：CORRECTNESS-2@S37（NIT，6/10）、GENERIC-1@S39（SHOULD FIX，8/10）

#### F150 [SHOULD FIX] 弹出网格计算与多字符触发规则没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`PopupKeyboardUi.kt:93-96`、`PopupKeyboardUi.kt:221-226`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PopupKeyboardUi.kt:93-96`、`PopupKeyboardUi.kt:221-226`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:96`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:93`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:224`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupKeyboardUi.kt:225`

提交 bd00b160 修复了 round 计算列数导致 13、17、21… 项列表末项被隐藏的问题（e 的 ə 消失），同时把 onTrigger（:225）改为候选超过一个码点时提交文本；两处都没有回归测试，而用户自定义列表（PopupOverrides）现在可以是任意长度，CLAUDE.md 要求决策逻辑放在 ime-core 并附测试。
建议：把行/列/keyOrders 计算与触发规则移入 ime-core 的 input/popup（已有 PopupOverridesTest、PopupEditorTest）；TESTING 建议对 n=1..30 断言每项都有位置并检查 Tab、ײַ、é 的路由，CORRECTNESS-1 建议测试 7、13、17、18 项且焦点在边缘和中间，并认为该测试能发现顶行错位问题。

- 提出者：CORRECTNESS-1@S39（SHOULD FIX，8/10）、TESTING@G8（SHOULD FIX，8/10）

#### F151 [SHOULD FIX] 加载对话框的清理没有放在 finally 中

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/common/ProgressBarDialogIndeterminate.kt:72`、`app/src/main/java/org/fcitx/fcitx5/android/ui/common/ProgressBarDialogIndeterminate.kt:58`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/common/ProgressBarDialogIndeterminate.kt:73`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/common/ProgressBarDialogIndeterminate.kt:72`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:265`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:237`

ProgressBarDialogIndeterminate 只在 action() 正常返回时关闭不可取消的对话框，action 抛异常或作用域被取消时对话框不会关闭。
CORRECTNESS-1：让异常逃逸的调用者（AdvancedSettingsFragment 的 queryFileName 在 try 之外）会留下卡住的对话框并泄漏窗口；CORRECTNESS-2：未捕获的异常经 lifecycleScope 使应用崩溃（UserWordListFragment.remove:265 没有 try），而有意重抛的取消（PinyinDictionaryFragment:237）会让对话框一直显示到窗口销毁。
建议：try { action() } finally { loadingJob.cancel(); loadingDialog?.dismiss() }。

- 可能的运行时影响：加载操作失败或被取消时，不可取消的加载对话框会一直卡在界面上，或应用崩溃。
- 提出者：CORRECTNESS-1@S40（SHOULD FIX，7/10）、CORRECTNESS-2@S40（SHOULD FIX，8/10）

#### F152 [SHOULD FIX] 词包是否替换取决于提供者报告的文件名

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:218`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222`

:222 的替换规则检查传入文件的名称和扩展名，而 importFromFile 按首行识别词包，并不管文件名都以 <name>.words 存储。
CORRECTNESS-2：浏览器把重复下载保存为 youmo-new (1).words 时走不替换路径，词包堆积、旧包的词仍生效；两者都指出以 .txt 保存的词包会被以「已存在」拒绝而无法替换。GENERIC-1 还指出该规则是 Fragment 中无测试的决策逻辑。
建议：按词包内容（头部 # layer:）判断是否替换，或规范化名称后提供替换；GENERIC-1 建议移入带测试的纯函数。

- 可能的运行时影响：重复下载或以 .txt 保存的词包无法正确替换，导致旧词包残留生效或导入被拒绝。
- 子论断 F152.b 被否定：「替换规则检查的是传入文件的扩展名，因此首次以 .txt 导入的词库包永远无法被替换，并且该规则没有测试。」→ 首次以 `.txt` 导入的词库包会通过其第一行被识别，并存储为 `<name>.words`（app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:59-61，app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/dict/TextDictionary.kt:19 和 :61）。之后它以 Words 类型列出，因此之后以 `.words` 命名的导入能通过 app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222 的检查并将其替换。只有以 `.txt` 命名的重新导入会被拒绝。
- 提出者：CORRECTNESS-2@S41（SHOULD FIX，5/10）、GENERIC-1@S41（SHOULD FIX，8/10）

#### F153 [SHOULD FIX] 码表选项的搜索结果打开的页面里没有这些选项

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:104-107`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/TableInputMethodFragment.kt:64-100`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:103`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:104`

`SettingsIndex` 中 `ANY_TABLE` 条目（自动上屏、提示、按使用排序、自动词组长度、保存词组等）都路由到 `SettingsRoute.TableInputMethods`，即只列出用户导入 `.conf` 码表的 `TableInputMethodFragment`（`TableManager.inputMethods()` 读 `inputMethodDir`），该页没有这些选项，也不链接五笔、仓颉、自然码、二笔、五笔拼音的选项页，而这些选项位于 `InputMethodConfig(engine-wubi, …)`。
CORRECTNESS-2 还指出显示的路径与同一路由的 `page(R.string.table_im, …, R.string.advanced)` 条目不一致。
建议：为每个内置码表（`engine-wubi`、`engine-cangjie` 等）各加一条路由到 `InputMethodConfig(name, uniqueName)` 并设标题的条目，或改为路由到 `InputMethodList`。

- 可能的运行时影响：在设置中搜索码表选项时，打开的页面里找不到该选项。
- 提出者：CORRECTNESS-1@S43（SHOULD FIX，8/10）、CORRECTNESS-2@S43（SHOULD FIX，8/10）

#### F154 [SHOULD FIX] 用户词搜索的归一化与排序逻辑在 Fragment 中且无测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:198-224`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:198-224`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:198`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:198-224`

`UserWordListFragment` 第 198-224 行的查询归一化（转小写、去空格和 `'`）、与去空格拼音比较、前缀匹配优先于子串匹配的排序都是决策逻辑。
CLAUDE.md 要求决策逻辑放在 `lib/ime-core` 并在同一变更中附单元测试，ime-core 已有同类且有测试的 `SettingsSearch`。
建议：把匹配与排序移到 ime-core 的纯函数（放在 `WordLists` 或 `SettingsSearch` 旁）并加测试；CORRECTNESS-1 还建议 Fragment 在主线程外调用它。

- 提出者：CORRECTNESS-1@S43（SHOULD FIX，8/10）、GENERIC-1@S43（SHOULD FIX，7/10）

#### F155 [SHOULD FIX] 自定义主题裁剪回调在主线程读取整张原图

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:315-320`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:418`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/CustomThemeActivity.kt:319`

`CropResult.Success` 回调在主线程执行 `contentResolver.openInputStream(srcUri).readBytes()`，会阻塞到整张图读完（10-30 MB 照片，或需先下载的云端 provider URI），可能导致 ANR；GENERIC-1 还指出缓冲一直留在内存中直到 `done()`，可能 OOM。
CORRECTNESS-2 标为 PRE-EXISTING，GENERIC-1 未加标记但说明继承自上游。
建议：在 `done()` 现有的 `withContext(Dispatchers.IO)`/`withLoadingDialog` 中读取，或把流直接复制到 `srcImageFile`，回调中只保留 `Uri`。

- 可能的运行时影响：选择大图或云端图片作为主题背景时，应用可能卡死至 ANR 或因内存不足崩溃。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-2@S44（SHOULD FIX PRE-EXISTING，7/10）、GENERIC-1@S44（SHOULD FIX，8/10）

#### F156 [SHOULD FIX] `Locales.picked()` 的新逻辑缺少单元测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22-29`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22-29`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22-29`

`picked()` 在三个来源间选择：API 33+ 的 LocaleManager、appcompat 的内存列表、以及 `appLanguage` 偏好回退；回退用于修复键盘在任何 Activity 启动前 appcompat 值为空的情况，但这一顺序没有测试。
CLAUDE.md 要求新逻辑附单元测试，app 模块已有 Robolectric 测试（`ContentResolverTest`）。
建议：增加 Robolectric 测试，覆盖 sdk 32 或 23（appcompat 为空时回退到偏好）与 sdk 33，以及空值、appcompat 已设、仅偏好三种情况。

- 提出者：CORRECTNESS-1@S45（SHOULD FIX，8/10）、CORRECTNESS-2@S45（SHOULD FIX，7/10）

#### F157 [SHOULD FIX] `NaiveDustman.reset()` 不清空旧初始值，重加的条目可能丢失

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/NaiveDustman.kt:41-56`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:283-327`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/NaiveDustman.kt:56`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:301-327`

`reset()` 调用 `initialValues.putAll(initial)` 前没有清空 map，之前 reset 的键会残留。例如删除短语 A、切到后台（`onStop` 保存为已删除并 reset）、回来再添加相同的 A：`addOrUpdate` 与残留的 `initialValues[A]` 比较相等而判定未修改，下次 `onStop` 跳过保存，A 从磁盘丢失。
受影响的有 `PinyinCustomPhraseFragment`（第 301-327 行）与 `QuickPhraseEditFragment`；CORRECTNESS-2 还列出 `QuickPhraseListFragment` 与 `TableInputMethodFragment` 共用此路径。
建议：`putAll` 前调用 `initialValues.clear()`，并添加单元测试。

- 可能的运行时影响：用户删除后又重新添加相同短语时，该短语可能不被保存而从磁盘丢失。
- 提出者：CORRECTNESS-2@S45（SHOULD FIX，8/10）、GENERIC-1@S45（SHOULD FIX，8/10）

#### F158 [SHOULD FIX] 开启配置缓存后工具指纹失效，引擎数据不再重建

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:171`、`ToolFingerprint.kt:21`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:171`、`ToolFingerprint.kt:21`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:162`、`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:69`

`toolCode` 来自普通的 `target.provider { ToolFingerprint.of(tool.files, …) }`，`classpath` 为 `@Internal`；开启 `--configuration-cache` 时 provider 在存储缓存条目时求值，此时工具 jar 尚未构建，全新检出下哈希的是空类集，结果恒定。
之后修改 dict 工具，`compileEngineData`、`compileEngineTables` 仍为 UP-TO-DATE，APK 会带着过期的 `pinyin.data` 和码表发布；第 162 行的注释承认这一风险但没有防护。GENERIC-1@S2 指出本仓库目前没有开启该模式。
建议：用一个以 `@Classpath` 接收 classpath、把指纹写入文件的小任务计算指纹，编译任务以 `@InputFile` 消费；至少在 `gradle.startParameter.isConfigurationCacheRequested` 为真时快速失败。
CORRECTNESS-2@S2 还提出长期方案：把数据格式和写入代码拆成独立模块，用 Gradle 内建的 classpath 归一化取代手写解析器（ToolFingerprint.kt:69 遇到未知常量池 tag 会让整个构建失败）。

- 提出者：CORRECTNESS-2@S2（SHOULD FIX，7/10）、GENERIC-1@S2（SHOULD FIX，7/10）

#### F159 [SHOULD FIX] readings.tsv 替换掉常用读音且未进 misreadings.tsv

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lexicon/readings.tsv:257`、`lexicon/readings.tsv:268`、`lexicon/readings.tsv:288`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lexicon/readings.tsv:329`；另见 `lexicon/readings.tsv:357`、`lexicon/readings.tsv:268`、`lexicon/readings.tsv:350`、`lexicon/readings.tsv:257`、`lexicon/readings.tsv:288`

readings.tsv 中 `重载 zhong'zai (was chong'zai)`（:329）、`鼻塞 bi'se`（:357）、`落枕 lao'zhen`（:268）、`颤栗 zhan'li`（:350）、`脖颈 bo'geng`（:257）替换了旧读音，而 PinyinDictReader 会丢弃所列词的 libime 读音。
于是输入 chongzai 或 bisai 只剩逐字组词，虫灾、比赛 会胜出；这违背 README「the reading is the one people say」的规则，也不同于 包扎、关卡、般若 把旧读音保留在 misreadings.tsv 的做法；CORRECTNESS-2@S4 还指出 `设卡 she'ka`（:288）反向丢掉了现汉读音。GENERIC-1@S4 只针对 重载 的编程义「方法重载」读 chóngzài。
建议：用「added beside」行保留旧读音（GENERIC-1@S4 举 重装 为先例），或把旧读音加入 misreadings.tsv。

- 可能的运行时影响：用户输入 chongzai、bisai 等常用读音时打不出 重载、鼻塞 等词，只能逐字组词，首选会变成 虫灾、比赛。
- 提出者：CORRECTNESS-2@S4（SHOULD FIX，7/10）、GENERIC-1@S4（NIT，6/10）

#### F160 [SHOULD FIX] forgetInTables 漏掉启动后未加载的用户导入码表

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`K/engine/host/Engines.kt:647-653`、`K/engine/table/TableSession.kt:299`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:663`、`Engines.kt:867`、`PinyinSession.kt:69-93`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:653`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:647`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:648`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:857`

`forgetInTables` 会加载磁盘上有日志的内置码表以遗忘词，但用户导入的码表只有已在 `tables` 中时才处理，而 `reload()`（每次词典或自定义短语编辑及 fcitx 重启后运行）会清空它们。
结果：在设置中或码表长按遗忘的词仍留在 `tables/<im>.user` 日志中，下次使用该码表时仍会被提供，而设置列表已不显示它。
建议：对 `addedTableFiles(im).last()` 存在的每个导入码表也执行遗忘（经 `addedTable` 复用缓存构建并跳过失败），或保留待遗忘列表在码表加载时应用。CORRECTNESS-1@S8 在同一条发现中还指出 PinyinSession 缺少 forget 钩子（见 G3a-04）。

- 可能的运行时影响：用户已遗忘的词在导入码表中仍会作为候选出现。
- 提出者：CORRECTNESS-1@S8（SHOULD FIX，7/10）、CORRECTNESS-2@S8（SHOULD FIX，7/10）

#### F161 [SHOULD FIX] Engines 层面没有九键（T9）输入法测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/21 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已修复（第二轮在 5881d31 上确认）
- 当前状态（5881d31）：✅ 已修复（第二轮已验证）：已由 5881d31 修复。lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103-112 和 115-124 构建了 Engines.T9 会话，输入 "64426" 并在其上执行精修。Engines.kt:537-540 处的 T9 接入也由同一个提交改动。
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:362`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:527`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:190`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:160`

`Engines.session(T9)` 自建 `T9Segmenter(abbreviations = false)`、`spell = true` 与习惯作用域 `engine-t9`，应用依赖它（EngineBridge.kt:190、KeyboardWindow.kt:160），但 `lib/ime-core/src/test` 中没有测试提到 `Engines.T9` 或 `engine-t9`，“sentence models loaded once”测试只覆盖 PINYIN 与 SHUANGPIN。TESTING@G3 另指出 `LatinWords=False` 设置及 `EngineSettings.SCHEMES` 八个名字中的七个（只有 `MS` 被测试解析）也未被测试。
建议：加一个 Engines 级 T9 测试（输入并选择数字、与其他拼音共享模型、经同一用户模型共享学习），并对所有 libime 方案名做表驱动解析测试。严重度：GENERIC-1@S16 为 SHOULD FIX，TESTING@G3 为 NIT。

- 提出者：GENERIC-1@S16（SHOULD FIX，7/10）、TESTING@G3（NIT，7/10）

#### F162 [SHOULD FIX] Engines 单个约 950 行的类承担过多职责

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:68`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:68`）
- 用户决定：要拆分 `Engines`。修复尚未开始。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:68`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:312`

Engines 同时负责会话工厂、资产与模型加载、用户词库（添加、屏蔽、导入、导出、遗忘）、自定义短语、内置与导入码表的构建缓存、笔画查找、拼音与码表共享词及生命周期；这些职责共享可变字段（`userModel`、`tables`、`blockedTexts`），CORRECTNESS-1@S8 认为这使遗忘缺口这类横切规则容易遗漏。STYLE@G3 指出 `sessions.clear(); keyboards.clear()` 在 312、322、357、392、486、853、877 行重复，且它能通过 detekt LargeClass（600 行代码）只是因为注释不计入。
建议：拆出 `UserLexicon` 与 `TableRegistry`/`AddedTables` 协作类，并加 `dropSessions()` 辅助函数。严重度：STYLE@G3 为 SHOULD FIX，CORRECTNESS-1@S8 为 NIT。

- 提出者：CORRECTNESS-1@S8（NIT，6/10）、STYLE@G3（SHOULD FIX，7/10）

#### F163 [SHOULD FIX] 以非字母键结束查询时丢掉已输入的引导键

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:445-446`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:459-460`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:83`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:446`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:83`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:445`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:460`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:496`

笔画查询什么也没找到时（单独的 `u`，或无匹配的笔画），随后的标点键上屏空字符串，已输入的键丢失，而回车会原样上屏；`TableSession` 的拼音查询同样如此：拼音无候选时 TableSession.kt:446 的回退上屏的原始拼音不含开头的拼音引导键（`zq` 后按 `.` 上屏 `q`，`z` 后按 `,` 什么都不上屏），回车（:460）却上屏 `zq`、`z`，且 TableSessionTest.kt:496 把丢键行为固定了下来。
建议：没找到时像 CommitRaw 那样上屏 `$KEY$input`；回退沿用第 460 行的规则（`raw == input.substring(1)` → `input`），并把测试预期改为 `zq`。
严重度不一：CORRECTNESS-1@S12 评为 NIT，CORRECTNESS-1@S19 评为 SHOULD FIX。

- 可能的运行时影响：用户在查询中按标点结束时，已输入的引导键（如 `z`、`u`）被丢弃而不上屏。
- 提出者：CORRECTNESS-1@S12（NIT，8/10）、CORRECTNESS-1@S19（SHOULD FIX，7/10）

#### F164 [SHOULD FIX] WordLists.split 不限词长，DP 分配可致 OOM

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`Engines.kt:370`、`Engines.kt:254`、`WordLists.kt:166`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:166`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:174`

`WordLists.split()`（WordLists.kt:174）分配两个 `(n+1)×(count+1)` 的 int 表，n 为拼音总长、count 为字符数，二者都直接来自用户导入的文件或 `Engines.addWord`；`UserWordsFragment.import` 不设上限，`importUserWords` 经 `runOnReady` 在 fcitx 线程运行。
CORRECTNESS-1@S13 估算一行约 3000 字、约 12k 个连写拼音字母需要约 290 MB，足以让输入法进程内存耗尽；GENERIC-1@S13 另指出内层 k 循环对同一 `(i, j)` 重复执行 `substring` 与 `Syllables.id`。
建议在 split 之前（或在 `line()`/`entry()` 中）对超过词长上限的词返回 null，例如与 `KeyHabits.MAX_TEXT` 相同的 32；GENERIC-1@S13 还建议把每个 `(i, j)` 的查找移出 k 循环。

- 可能的运行时影响：导入一行超长的用户词就可能让输入法进程内存耗尽而崩溃。
- 提出者：CORRECTNESS-1@S13（SHOULD FIX，7/10）、GENERIC-1@S13（SHOULD FIX，6/10）

#### F165 [SHOULD FIX] LibimeImport.readings 遍历整棵字典树，交互路径上也如此

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:79`、`TableUser.kt:77`、`Engines.kt:617`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:79`、`TableUser.kt:77`、`Engines.kt:617`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:83`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:607`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:630`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:262`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:581`

`readings()`（LibimeImport.kt:83）不论给多少文本，都会访问到最深文本深度为止的每个字典节点；`Engines.pinyinsOf` 为单个文本调用它，调用来自 `TableUser.save` → `learnSaved`（打字时在引擎线程上）和 `TableSession.offers` → `blockable`（每次长按）。遍历中 `char(id, 0) !in firsts` 还会对访问到的每个 CJK 词装箱一个 Character。
建议用 BooleanArray 或 BitSet 存放 `firsts` 与 `depths`；对单文本查询做记忆化，或建立从字到单字音节的惰性索引、只走相关路径；批量导入保留全量遍历。

- 跨模块组合并：G4-23、G3a-21（保存码表短语时在 fcitx 线程遍历整部拼音词典）
- 提出者：PERFORMANCE@G4（SHOULD FIX，7/10）、PERFORMANCE@G3（SHOULD FIX，7/10）

#### F166 [SHOULD FIX] 发布门禁按列位置解析 CLI 输出且无测试约束

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/KeystrokeRunTest.kt:104-108`、`release-report.sh:69-71`、`ReportTest.kt:66`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/KeystrokeRunTest.kt:104-108`、`release-report.sh:69-71`、`ReportTest.kt:66`）
- 位置：`lib/ime-eval/release-report.sh:49`；另见 `lib/ime-eval/release-report.sh:53`、`lib/ime-eval/release-report.sh:69-71`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:40`

release-report.sh 按位置读取字段：`all` 行的 `$4` 作 top1（:49），`$3 $4 $5` 作 won/lost/p（:53），`learn` 输出以 `-F'  +'` 取 `$4`（:69-71）。
若 `Report.table` 或 `KeystrokeRun.row` 在 top1 前插入一列，门禁会读到另一个百分比（如同为 `xx.x%` 的 `char` 列），`number` 检查照样通过；ReportTest 按表头名找列，不会发现；TESTING 还指出 :71 的 `$` 锚点依赖 Learning.kt:40 的分层行，而这些行也无测试。
修复：TESTING 建议加 Kotlin 测试，运行带基线的 `score` 并检查这些空白分隔字段，再在两层 fixture 上跑 `learn`；两个阶段都提出可改为在 awk 中按表头名取列，STYLE 另提议给 `score`/`learn` 加 TSV 输出模式。

- ⚠️ 验证说明：代码层面的论断成立，但 verifier 否定了上面所说的运行时后果：lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/KeystrokeRunTest.kt:104-108,135 按空格拆分 KeystrokeRun.report/row 的输出，并断言精确的单元格列表（group、n、reached、top1、KSC）。因此，在 release-report.sh:69-71 读取的 learn 行中插入一列会导致测试失败。只有 score 的 top1 列（ReportTest.kt:66 只固定了第 1-3 列）和 flips 列（ReportTest.kt:20 按表头名称读取它们）没有被固定。
- 子论断 F166.a 被否定：「发布门禁按列位置读取 score 和 learn 的输出，而没有测试检查这些位置，因此插入一列会使它依据错误的指标做出判断。」→ 与 F166 相同的证据：KeystrokeRunTest.kt:104-108 固定了 learn 行的列位置。
- 提出者：TESTING@G5（SHOULD FIX，7/10）、STYLE@G5（SHOULD FIX，7/10）

#### F167 [SHOULD FIX] 引擎抛异常后面板被清空，但 Kotlin 会话仍保留旧组合

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:766`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/native-lib.cpp:766`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:351`、`app/src/main/cpp/androidengine/androidengine.cpp:375`

EngineBridge.onEvent 抛异常时，engineFailed 清除异常并返回 handled=false 的空快照；AndroidEngine::send（androidengine.cpp:351）仍调用 show()，其中 panel.reset()（line 375）清空预编辑和候选，原始按键交给应用。Engines.onEvent 没有 try/catch，Kotlin 的 Keyboard/Session 保留半途的组合，下一次按键时旧预编辑重新出现，而前面已插入了一个原始字母。
CORRECTNESS-1@S25 还指出 ExceptionDescribe 不记录输入法名或事件。
修复：在 EngineBridge.onEvent 中捕获异常，用 Timber 记录 im 与 event，丢弃该输入法的 keyboard 和 session 并返回清空的 Result（原生侧只处理 JNI 层失败）；CORRECTNESS-2@S25 另提可在失败后，于独立的 engineFailed 保护下为该 im 发送 EngineEvent::Reset。

- 可能的运行时影响：引擎出错后先向应用提交一个原始字母，下一次按键时旧的拼音组合又重新出现，造成错误输出。
- 提出者：CORRECTNESS-1@S25（SHOULD FIX，5/10）、CORRECTNESS-2@S25（SHOULD FIX，7/10）

#### F168 [SHOULD FIX] 语音候选的选择逻辑留在 app 中，CI 测试覆盖不到

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldSession.kt:107`、`VoiceListener.kt:315`、`androidTestVoice/VoiceEngineTest.kt:123`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldSession.kt:107`、`VoiceListener.kt:315`、`androidTestVoice/VoiceEngineTest.kt:123`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:120`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldSession.kt:107`

VoiceEngine.recognize()（VoiceEngine.kt:120）是纯决策逻辑：对重排后的假设排序、在含屏蔽词的假设中选择、回退到带屏蔽的重新搜索，共有五个出口（候选少于 2 个、无屏蔽词、无可疑项、VoiceBlocking.pick、带屏蔽重搜）。唯一的测试是需要设备和约 180 MB 模型的 VoiceEngineTest，而 CLAUDE.md 要求决策逻辑放在 lib/ime-core 并配 JVM 测试（VoiceRerank、VoiceBlocking 已有 JVM 测试）。
修复：把选择逻辑移到 ime-core（输入为 nbest、scores、logProbs/屏蔽词、Language 和一个可挂起的重搜函数），用 fake 测试每个出口。TESTING@G6 还要求对 VoiceHoldSession.kt:107 的提交保护（同一字段、非密码字段，PRIVACY.md 依赖它）做同样处理。

- 提出者：CORRECTNESS-2@S28（NIT，7/10）、TESTING@G6（SHOULD FIX，7/10）

#### F169 [SHOULD FIX] 导入的码表打开拼音设置页，修改会覆盖拼音设置

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:234`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:224`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:245`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:205`、`app/src/main/cpp/androidengine/androidengine.cpp:224-235`

TableManager.importFiles 调用 useEngine()，把用户码表以其自身名称绑定到 androidengine；table() 只认识五个内置码表，所以 getConfigForInputMethod 对它返回 pinyinPage_，setConfigForInputMethod（line 245）再把该页加载进 config_，改动拼音的 PageSize、Fuzzy 等选项。用户可经 InputMethodListFragment 或 StatusAreaWindow 进入此页，而且没有任何地方写入 EngineBridge.userTable 读取的 config/table/<im>.conf。
修复：只对 engine-pinyin 和 engine-t9 返回拼音页；对未知输入法返回 nullptr 或一个由 config/table/<im>.conf 支撑的页面，并把写入导向该文件。

- 可能的运行时影响：在导入码表的设置页上修改选项会改写拼音输入法的设置，码表自身的设置却不会保存。
- 跨模块组合并：G6-15、G8b-18（导入码表的设置页显示并写入拼音的配置）
- 提出者：CORRECTNESS-2@S26（SHOULD FIX，7/10）、CORRECTNESS-1@S43（SHOULD FIX，6/10）

#### F170 [SHOULD FIX] knownSubtypes 是普通 HashMap，被多线程读写

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 SHOULD FIX PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt:49`、`app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt:64`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:295`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt:23`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt:49`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:467`

`knownSubtypes` 是普通 HashMap：`syncWith` 在 `Dispatchers.Default` 上由 `postFcitxJob`（`onCreate`、`onStartInputView`）、`launchWhenReady`（Fcitx.kt:467）和 `launchOnReady`（InputMethodListFragment）调用，先 `clear()` 再重新填充；主线程则在 `handleFcitxEvent` 中通过 `subtypeOf` 读取。
后果：在清空和重填之间读取会得到 null，IMChangeEvent 的系统 subtype 通知被丢掉；GENERIC-1@S30 还指出启动时前两个写者可能重叠，导致 map 损坏。
修复：构建新 map，通过 `@Volatile` 引用发布；GENERIC-1@S30 另建议给 `syncWith` 加 `@Synchronized`。CORRECTNESS-1@S30 把它标为 PRE-EXISTING。

- 可能的运行时影响：切换输入法时，系统显示的输入法 subtype 可能不会更新。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S30（SHOULD FIX PRE-EXISTING，7/10）、GENERIC-1@S30（SHOULD FIX，7/10）

#### F171 [SHOULD FIX] 被 hidden {} 隐藏的设置保留旧的非默认值且无法再修改

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceCategory.kt:34`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceProvider.kt:51`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:54`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceCategory.kt:34`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:54`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:420`

AppPrefs 把多个上游可见的开关和枚举移进了 `hidden {}`（ManagedPreferenceCategory.kt:34），例如 `ignore_system_cursor`（AppPrefs.kt:54）、`ignore_system_window_insets`、`keep_keyboard_letters_uppercase`、`expand_keypress_area`、`inline_suggestions`、`hide_key_config`、`clipboard_return_after_paste`（:420）、`clipboard_item_timeout`、`lang_switch_key_behavior` 和候选样式；`hidden` 的文档说明旧版本设的值会保留。
通过 README 所说的设置导出/导入带入非默认值的用户，没有任何界面能把它改回来；`Levels` 只覆盖被隐藏的整数项，开关和枚举没有覆盖。
修复：在升级或导入时一次性把没有替代界面的隐藏键重置为默认值，或提供「重置隐藏设置」操作，或在存储值不等于默认值时继续显示该项；这些做法都能保留更简洁的设置页。

- 可能的运行时影响：从旧版导入设置的用户会一直保留某些非默认行为（例如忽略系统光标），却没有界面可以关闭。
- 提出者：CORRECTNESS-2@S31（SHOULD FIX，6/10）、GENERIC-1@S31（SHOULD FIX，7/10）

#### F172 [SHOULD FIX] 评测 runner 未检查即在同进程再启动一个 Fcitx

- 来源：第一轮（整仓 @07d2778） · 共识：2/15 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`EngineEvalRunner.kt:45`、`run-on-device.sh:19`、`Fcitx.kt:224`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineEvalRunner.kt:45`、`run-on-device.sh:19`、`Fcitx.kt:224`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:45`；另见 `lib/ime-eval/run-on-device.sh:19`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:224`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:201`、`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:66`、`app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:389`

runner 依赖 run-on-device.sh:19 把设备键盘切到 Gboard，但该命令以 `|| true` 结尾，在没有 Gboard 的设备（如 AOSP 模拟器）上会静默失败；本 app 是设备键盘时，系统也会在被测进程中绑定 `FcitxInputMethodService`。`eventFlow_` 是静态的（Fcitx.kt:224），native 引擎是进程级的，而 `init` 中的「already created」守卫（Fcitx.kt:201-205）只检查新实例自己的 `lifecycleRegistry`，GENERIC-1@S49 指出它因此永远不会触发，两个引擎会共享事件和数据文件。GENERIC-1@S49 还指出 FcitxTest.kt:66 有同样的风险。
修复：像 SoftKeyboardTest（:389-394）那样用 dumpsys 检查，或通过 `uiAutomation` 读取 `default_input_method`，然后快速失败、切换走或 `assumeTrue`。

- 提出者：CORRECTNESS-2@S49（SHOULD FIX，6/10）、GENERIC-1@S49（SHOULD FIX，7/10）

#### F174 [SHOULD FIX] 九键切换在主线程 runBlocking 等待 fcitx

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`KeyboardWindow.kt:159-166`、`FcitxDaemon.kt:55-58`、`FcitxInputMethodService.kt:223-226`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`KeyboardWindow.kt:159-166`、`FcitxDaemon.kt:55-58`、`FcitxInputMethodService.kt:223-226`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:116`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:164`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:166`、`app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:55`

onImeUpdate 中新增的 Text↔T9 切换调用 attachLayout，后者用 fcitx.runImmediately（runBlocking）让主线程等待 fcitx 线程，而该线程可能正在跑解码器、reranker 或 refine。
CLAUDE.md 禁止在主线程对 FcitxAPI 使用 runBlocking，且 onImeUpdate 已拿到 ime，这次调用是多余的。
建议：把 ime 传入 attachLayout（或缓存最近一次的 ime），去掉 runImmediately。CORRECTNESS-1 定为 SHOULD FIX，CORRECTNESS-2 定为 NIT。

- 提出者：CORRECTNESS-1@S37（SHOULD FIX，7/10）、CORRECTNESS-2@S37（NIT，6/10）

#### F175 [SHOULD FIX] ACTION_CANCEL 被当作真实抬起分发

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:237`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:228`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:150`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:237`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:263`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:266`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:303`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:166`

CustomGestureView 在 ACTION_CANCEL 时分发 GestureType.Up，监听者无法区分取消与真实抬起：退格监听会删除选中文字（CORRECTNESS-2），滑动监听会输入滑动字符，弹出键盘/菜单监听会提交焦点键。
取消发生在布局或输入法切换移除视图、边缘返回手势、窗口失焦等时候；GENERIC-1 还指出 vivo 路径（releaseAllTouchTargets → cancelGestures）不发 Up，两条路径行为不一致。
建议：为 Event 加 cancelled 标志或 Cancel 手势类型（GENERIC-1 也提出把合成的 Up 标为 consumed），只在真实 Up 时执行动作。

- 可能的运行时影响：系统取消触摸（如边缘返回手势或切换布局）时会误删选中文字或误输入滑动字符、弹出键。
- 提出者：CORRECTNESS-2@S37（SHOULD FIX，7/10）、GENERIC-1@S37（SHOULD FIX，6/10）

#### F176 [SHOULD FIX] 滑动覆盖忽略 Shift/Caps 且不释放一次性 Shift

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:324`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:326`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:167`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:324`

swipeOverride 总是按小写标签查找并返回小写覆盖；Shift 下长按解析 Q 时候选是大写化的覆盖（PopupOverrides.shiftedOverride），长按第一项为 É，而滑动和键角给出 é。
字母结果成为 CommitAction，onAction 原样透传，一次性 Shift（CapsState.Once）保持开启，而内置 FcitxKeyAction 滑动会清除它。
建议：capsState != None 时按大写标签解析（与 onPopupAction 一致），并在 Once 状态下对 CommitAction 调用 switchCapsState()；CORRECTNESS-1 也接受把这一差异写进文档。CORRECTNESS-1 定为 NIT，GENERIC-1 定为 SHOULD FIX。

- 可能的运行时影响：Shift 状态下字母滑动输入小写候选，且下一个字母仍保持大写。
- 提出者：CORRECTNESS-1@S38（NIT，7/10）、GENERIC-1@S38（SHOULD FIX，7/10）

#### F177 [SHOULD FIX] 替换词包时默认选中基础层，会把新词层的包移走

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:195`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:232`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:195`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:231`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:232`

即使是替换已有词包，对话框也预选 picked = 0（BASE），随后 :231/:232 先执行 setIntoNew(name, layer == NEW)；每月重新导入 youmo-new.words 时直接点导入，会把它从新词层移到基础层。
这与 PinyinDictManager.importPack 文档「保持在用户放置的位置」矛盾；CORRECTNESS-1 还指出本页永远走不到 importPack 的「首次导入进入新词层」分支。
建议：替换时预选该包当前所在层（isIntoNew(name)）；两者还建议把 :222 的替换/拒绝规则移入 PinyinDictManager 并测试。

- 可能的运行时影响：按默认操作更新每月新词包时，词包会被从新词层移到基础层，改变其所在词库层。
- 提出者：CORRECTNESS-1@S41（SHOULD FIX，7/10）、CORRECTNESS-2@S41（SHOULD FIX，7/10）

#### F178 [SHOULD FIX] 词典导入的替换/拒绝决策位于 Fragment 且无测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`PinyinDictionaryFragment.kt:218-233`、`PinyinDictManagerTest.kt:39-61`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinDictionaryFragment.kt:218-233`、`PinyinDictManagerTest.kt:39-61`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:222`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:210`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:158`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:213`

「同名词包替换旧包、其他同名导入被拒绝」的规则以及「先设层、再替换」的顺序（:222-235）决定用户数据的去向；PinyinDictManager 的各部分有测试，但这一决策没有，CLAUDE.md 要求决策逻辑放在纯类中并附单元测试。
TESTING 指出 PunctuationEditorFragment.kt:158（移到首位）和 UserWordListFragment.kt:213（搜索时前缀匹配优先于子串）同样缺测试；STYLE 指出 Type.fromFileName(fileName) 计算了两次（:210、:222）。
建议：移入 PinyinDictManager（如 planImport(...)：Reject | Replace | New），由 PinyinDictManagerTest 做表格测试。

- 提出者：TESTING@G8（SHOULD FIX，7/10）、STYLE@G8（SHOULD FIX，7/10）

#### F179 [SHOULD FIX] 搜索索引构建不写偏好的约束缺少守护测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:70-73`、`ManagedPreferenceUi.kt:94`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:70-73`、`ManagedPreferenceUi.kt:94`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:73`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:64`

构建离屏索引使用了 `@RestrictTo` 的 `PreferenceManager(Context)` 构造函数，且只有在每个 `ManagedPreferenceUi` 都经 data store 持久化时才安全，`Nothing` data store（第 73 行附近）是唯一防线；`Levels.persistString` 已直接写 SharedPreferences，仅因 `pick()` 在值未变时不做事才无害。
若该 data store 被移除或未来某个 UI 在 `onSetInitialValue` 中写值，每次打开搜索都会把用户从未改过的默认值固定为当前值，静默且难以撤销。严重度不一：TESTING 为 SHOULD FIX，CORRECTNESS-1 为 NIT。
建议：增加 Robolectric 测试，构建索引后断言底层 SharedPreferences 未变（可复用 `ManagedPreferenceSectionsTest` 的 PlaceholderStrings），或在 `ManagedPreferenceUi` 中写明规则；TESTING 还可选地建议断言每个 `Target.title` 存在于其页面。

- 提出者：CORRECTNESS-1@S43（NIT，7/10）、TESTING@G8（SHOULD FIX，7/10）

#### F180 [SHOULD FIX] fcitx 的语言环境不跟随应用内选择的语言

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:41-63`、`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:141`、`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:160`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:41`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22`、`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:141`、`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:160`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:436`

`fcitxLocale` 由 Application 的 configuration 构建（`FcitxApplication.kt:141/160`），`Fcitx.kt:436` 只在原生启动时读取；`Locales.picked()`（第 22 行）只用于键盘资源。API 23-32 上 appcompat 不把所选语言应用到 Application，fcitx 经 gettext/.mo 翻译的字符串（配置页、输入法名、状态区动作）仍跟随手机语言。
CORRECTNESS-1 还指出 API 33+ 上这些字符串在 fcitx 重启前保持旧语言。
建议：在 `onLocaleChange` 中把非空的 `Locales.picked(appContext)` 放在 configuration 语言之前；CORRECTNESS-1 还建议 `AppLanguage.set` 改语言时重启 fcitx 或重设其语言。

- 可能的运行时影响：用户在应用内选择的语言不会用于 fcitx 自身翻译的界面文字（如输入法名、配置页）。
- 提出者：CORRECTNESS-1@S45（SHOULD FIX，7/10）、CORRECTNESS-2@S45（SHOULD FIX，6/10）

#### F181 [SHOULD FIX] PinyinDataBuilder 对数百万 n-gram 下标做装箱排序

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:174`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:183`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:174`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:183`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:174`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:183`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:83`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Quantizer.kt:74`

`(0 until n).sortedWith(compareBy({…},{…}))`（:174 与 :183）对每个下标装箱（480 万二元组，三元组更多），每次比较时选择器结果也装箱，而 Quantizer.kt:74 明确避免了同类开销；GENERIC-1@S7 还指出 `finite("$prev $word", …)`（:83）对每个 n-gram（包括有效的）都构造字符串。
只影响构建主机（README：2 分钟、5 GB），PERFORMANCE@G3 认为这可能占了其中很大部分。
建议：对打包为 `LongArray` 的键做原始类型排序，或按 prev 计数排序（id 连续）后再按 word 排各段 `IntArray`，三元组按 (context, word) 同理；错误消息改为惰性构造。严重度：PERFORMANCE@G3 为 SHOULD FIX，GENERIC-1@S7 为 NIT。

- 提出者：GENERIC-1@S7（NIT，7/10）、PERFORMANCE@G3（SHOULD FIX，6/10）

#### F183 [SHOULD FIX] Scorer对保留步数的上限从未被测试，可能使长读法精排无结果

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:281`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:326-330`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/TinyModel.kt:20-22`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:281`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:99`

没有测试传入 `limit`，所以 `kept.size > limit` 的清理（SentenceModel.kt:281）在测试中从不执行，6 MB 上限和清理后的分数都没有保护。
CORRECTNESS-2 根据读代码推断：若一次评分所需步数超过上限（25M 模型约 190，见 `KEPT_FLOATS`），上限会在之后一次带预算的调用开始时清掉进行中的步骤，每次尝试都从头开始，于是对早期就不同的长读法 refiner 永远给不出选择；TESTING 只指出缺少测试。
建议：加 `Scorer(limit = 3)`、预算 1 且所需步数更多的测试（TESTING 建议对比 `Scorer(limit = 1)` 与默认值），再决定上限是否只在上下文变化时清理，或保留正在评分读法的步骤。

- 可能的运行时影响：按 CORRECTNESS-2 的推断，较长且早期就不同的读法可能永远得不到 refiner 的精排结果。
- 提出者：CORRECTNESS-2@S17（SHOULD FIX，6/10）、TESTING@G3（NIT，6/10）

#### F184 [SHOULD FIX] 剪贴板建议超时后只清标志不刷新，建议仍留在栏上

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT PRE-EXISTING）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:166-169`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:167`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:422`

`KawaiiBarComponent.kt:167-169` 在 `delay(timeout)` 之后只把 `isClipboardFresh` 设为 false，没有调用 `evalIdleUiState()`，显示剪贴板文字的建议会在超过 `clipboardItemTimeout` 后继续留在栏上，直到别的事件重新评估栏状态。这个行为继承自上游。
修复：清标志后调用 `evalIdleUiState()`；若有意让建议继续显示，就加注释说明。CORRECTNESS-2@S34 的同一条发现还包含 -1（永不超时）时启动不恢复最后一条剪贴内容的问题（:422）。

- 可能的运行时影响：复制的内容在超过设定的超时时间后，仍显示在候选栏的剪贴板建议里。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S34（SHOULD FIX，6/10）、CORRECTNESS-2@S34（NIT PRE-EXISTING，7/10）

#### F185 [SHOULD FIX] 新增表情和语音按钮挤压工具栏，窄屏上按钮小于 40dp

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/IdleUi.kt:143-161`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/ButtonsBarUi.kt:27-28`、`app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:334`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/IdleUi.kt:143`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/IdleUi.kt:153`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/ButtonsBarUi.kt:27`、`app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:334`

新增的表情和语音按钮让空闲栏的固定按钮在语音版达到 160dp（菜单、表情、语音、隐藏），剩下的空间留给动画区；ButtonsBarUi 的五个 40dp 按钮需要 200dp，不换行的 FlexboxLayout（默认 flexShrink 为 1）在空间不够时会压缩它们。因此只有在至少 360dp 宽且没有侧边距的屏幕上才放得下；有 `sidePadding`（InputView.kt:334）、分屏或 320dp 的手机上，按钮会小于 40dp 的触控尺寸，图标可能被裁切。
修复：工具栏展开时隐藏表情按钮，或给工具栏按钮设最小宽度或溢出处理，或让工具栏可以滚动。两票严重度不同（SHOULD FIX 与 NIT）。

- 可能的运行时影响：在窄屏、分屏或有侧边距时，工具栏按钮被压到小于 40dp，图标可能被裁切，也更难点中。
- 提出者：CORRECTNESS-2@S34（SHOULD FIX，6/10）、GENERIC-1@S34（NIT，5/10）

#### F186 [SHOULD FIX] 页面承诺同键多卡片可供选择，但输入时不提供

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:43`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:152`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:195-196`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:43`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:153`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:195`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:196`、`app/src/main/cpp/androidengine/androidengine.cpp:287-290`

类文档、`punctuation_page_hint`、`punctuation_choice` 说明（第 195-196 行）和「首选」复选框都表示同一键的多张卡片会作为候选；但唯一的输入路径 `AndroidEngine::pushPunctuation`（`androidengine.cpp:287-290`）只调用 `pushPunctuationV2`，每键只提交一个映射，仓库内也无代码调用 `getPunctuationCandidates`（原由已被 youmo 替换的 pinyin/table 引擎调用），第二张卡片保存后永远不会被输入。
两位审查者都因 fcitx5-chinese-addons 子模块未检出而未能核实（置信度 4 与 6），CORRECTNESS-1 建议上机验证。
建议：在 androidengine 中接入 `getPunctuationCandidates` 候选列表；或改写页面文案说明只输入第一个标点，或拒绝同键第二张卡片并去掉「首选」复选框与 choice 说明。

- 可能的运行时影响：用户为同一键添加的额外标点卡片保存后在打字时永远不会出现，与页面说明不符。
- 提出者：CORRECTNESS-1@S42（SHOULD FIX，4/10）、CORRECTNESS-2@S42（SHOULD FIX，6/10）

#### F187 [SHOULD FIX] `getSecureSettings<String>` 遇空值抛 NPE

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/Settings.kt:24-31`、`app/src/main/java/org/fcitx/fcitx5/android/utils/InputMethodUtil.kt:36`、`app/src/main/java/org/fcitx/fcitx5/android/ui/setup/SetupPage.kt:36`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/Settings.kt:26`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Settings.kt:31`、`app/src/main/java/org/fcitx/fcitx5/android/utils/InputMethodUtil.kt:36`

`Settings.Secure.getString` 可能返回 null，在 reified `T = String` 时 `as T` 会抛出 NullPointerException（null cannot be cast to non-null type）；`InputMethodUtil.isSelected()`（第 36 行，API 34 以下）会走到这里。
GENERIC-1 指出 DEFAULT_INPUT_METHOD 未设置时会使设置向导或主界面崩溃。严重度不一：GENERIC-1 为 SHOULD FIX，CORRECTNESS-2 为 NIT。
建议：String 分支返回 `String?` 或调用方使用 `getSecureSettings<String?>`，或对 `getString(...)` 用 `?:` 回退为空字符串。

- 可能的运行时影响：DEFAULT_INPUT_METHOD 未设置时，设置向导或主界面可能崩溃。
- 提出者：CORRECTNESS-2@S45（NIT，6/10）、GENERIC-1@S45（SHOULD FIX，6/10）

#### F188 [SHOULD FIX] 语音屏蔽词匹配在 VoiceText.clean 去空格之前进行

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 SHOULD FIX）· 置信度 5/10 · 验证：✅ 已确认（`VoiceBlocking.kt:19`、`VoiceEngine.kt:140-142`、`VoiceHold.kt:44`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceBlocking.kt:18`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:140`

`VoiceEngine.recognize` 把未清理的候选 `texts` 交给 `VoiceBlocking.suspects` 和 `pick`，`VoiceHold.heard` 之后才清理文本，其中 `SPACE_BY_CJK` 会删掉汉字旁的空白。
若 X-ASR 输出「开 饭」（其注释称它像英文那样给中文加空格），该候选不被视为可疑，最后却写成「开饭」，与「blocked words never written」相悖。
建议在 `suspects` 与 `pick` 之前做同样的空白规范化，例如复用 `VoiceRerank.bare` 或抽出共享辅助函数。

- 可能的运行时影响：识别结果在汉字之间带空格时，被屏蔽的词仍可能被语音输入写进文本（屏蔽失效）。
- 跨模块组合并：G4-19、G6-17（屏蔽词检查用原始文本，实际输入的却是清洗后的文本）
- 提出者：GENERIC-1@S14（SHOULD FIX，5/10）、CORRECTNESS-2@S28（SHOULD FIX，4/10）

#### F189 [SHOULD FIX] 内联自动填充建议视图堆积，旧响应可能盖过新响应

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 SHOULD FIX, 1 NIT PRE-EXISTING）· 置信度 5/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/InlineSuggestionsUi.kt:113`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:503-514`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/InlineSuggestionsUi.kt:113`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:503`

`InlineSuggestionsUi.setScrollableViews`（:113）只往 `scrollableContentViews` 里追加，从不把旧 surface 的父级置空，只有 `clear()` 会这样做；同时 KawaiiBarComponent.kt:503-512 的两个异步 inflate 协程从不取消，较慢的旧响应（或在 `onStartInput` 的 `clear()` 之前到达的响应）可能在新响应之后才调用 `setPinnedView`/`setScrollableViews`，用过期的自动填充 chip 替换新的。
修复：在 `setScrollableViews` 开头调用 `clearScrollView()`；为每个响应保存一个 Job，在 `handleInlineSuggestions` 和 `onStartInput` 中取消上一个。GENERIC-1@S34 把它标为 PRE-EXISTING。

- 可能的运行时影响：自动填充（如密码管理器）显示在候选栏里的建议，可能是上一次请求留下的过期内容。
- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S34（SHOULD FIX，5/10）、GENERIC-1@S34（NIT PRE-EXISTING，7/10）

#### F190 [SHOULD FIX] 首次测量前行高为 0，可能绑定整个列表

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 5/10 · 验证：✅ 已确认（`PickerGridView.kt:55`、`PickerGridView.kt:74`、`PickerGridAdapter.kt:121`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PickerGridView.kt:55`、`PickerGridView.kt:74`、`PickerGridAdapter.kt:121`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridView.kt:55`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridView.kt:72`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridView.kt:73`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:121`

PickerGridView 的 rowHeight 初始为 0，PickerGridAdapter.kt:121 以该高度创建键 holder；注释说在首次确定尺寸前会有一次测量 pass 绑定键。
0 像素的行永远填不满可用空间，LayoutManager 的 fill 会在主线程创建并绑定几乎所有条目（CORRECTNESS-1 估计表情约 1,900 个 TextKeyView，GENERIC-1 估计超过 1000 个）。
建议：用非零估计行高起步（如 T9Keyboard 的 dp(40)），或在 rowHeight == 0 时让 getItemCount 返回 0。

- 提出者：CORRECTNESS-1@S38（SHOULD FIX，5/10）、GENERIC-1@S38（SHOULD FIX，4/10）

#### F191 [SHOULD FIX] `git describe --tags` 把数据发布 tag 当成应用版本名

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:46`、`AndroidAppConventionPlugin.kt:40`、`BuildMetadataPlugin.kt:68`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:46`、`AndroidAppConventionPlugin.kt:40`、`BuildMetadataPlugin.kt:68`）
- 位置：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:46`

仓库的 GitHub release 会创建 HEAD 可达的 `engine-data-*`、`words-*`、`sentence-models-*`、`test-*` tag，HEAD 上 `git describe --tags --long --always` 返回 `test-20261005-4-42-g07d27780`，在 `aa1e92ff` 上返回 `engine-data-20261005-3-0-gaa1e92ff`。
这个字符串成为关于页的版本（`Const.versionName`）、APK 名（`archivesName`）、`build-metadata.json` 和导出的 `metadata.json`，而 `Versions.baseVersionName = "0.1.3"` 从未生效。
建议：加 `--match 'v[0-9]*'`（或为每个数据 tag 前缀加 `--exclude`），并按该格式给应用发布打 tag。

- 提出者：CORRECTNESS-1@S2（SHOULD FIX，9/10）

#### F192 [SHOULD FIX] ReadersTest 固化了 skipped 读音的重复计数

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:63-66`、`PinyinDictReader.kt:70`、`Main.kt:724`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:63-66`、`PinyinDictReader.kt:70`、`Main.kt:724`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:65`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:65-66`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:724`

输入只跳过两条读音（`好 xyz` 和 `好 ni'xyz'xyz`），ReadersTest.kt:65-66 却期望 `skipped == 3`、`xyz → 3`。
原因是 `PinyinDictReader.add` 按每个未知音节的出现次数累加，含两个坏音节的读音被计两次；类文档说该 map 记录「how many readings it made us skip」，Main.kt:724 打印 `skipped ${reader.skipped} readings`，所以构建日志报告的跳过读音数多于实际。
建议：每条被跳过的读音只计一次（单独的计数器，按音节的 map 用 `unknown.toSet()`），然后测试期望 2 和 `xyz → 2`。

- 提出者：CORRECTNESS-1@S6（SHOULD FIX，9/10）

#### F193 [SHOULD FIX] 新增命令须改 Main.kt 五处，未知 flag 被当成输入路径

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:222`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:222`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:100`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:32`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:166-184`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:203`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:221`

11 个命令各自分散在 `USAGE`（:32）、`dispatch` 的 `when`（:100）、`fit`/`fitModel`/`fitCorpus` 的 `when`（:166-184）、`FLAGS`（:203）以及 23 个字段的 `Options` 上，调用处到处需要 `!!`。
漏写在 `FLAGS` 里的 flag 会被静默当作输入路径（:221），用户看到的是误导性的文件错误而不是用法说明。
建议：每个命令一份规格（名称、用法行、接受与必需的 flag、`run`），放在 `runCli` 读取的列表里，并拒绝命令不接受的 flag。

- 提出者：STYLE@G2（SHOULD FIX，9/10）

#### F194 [SHOULD FIX] candidates.tsv 各列按裸下标读取

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:268-275`、`Main.kt:424`、`lexicon/tools/batches.py:70`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:268-275`、`Main.kt:424`、`lexicon/tools/batches.py:70`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:267`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:397`、`lexicon/tools/batches.py:70`、`lexicon/tools/batches.py:82`

`words` 按 `NewWords.Candidate` 的命名字段写行（Main.kt:397），而 `Options.passes` 用 `f[1]`、`f[3]`…`f[7]` 读回，`pack` 用 `f[0]`/`f[1]`，`lexicon/tools/batches.py:70,82` 用 `r[1]`、`r[3]`-`r[5]`。
重排或插入一列会静默改变哪些候选进入发布 pack。
建议：在写入端旁加一个 `CandidateRow.parse` 并传递命名字段；可选地在两个读取端校验 `# text\tcount…` 表头行。

- 提出者：STYLE@G2（SHOULD FIX，9/10）

#### F195 [SHOULD FIX] 三个拼音会话重复传入同一组命名参数（Data Clump）

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:529-546`、`PinyinSession.kt:78`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:529-546`、`PinyinSession.kt:78`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:518`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:536`

Engines.kt:518-536 三次重复传入 `pageSize, user, prediction, prior, phraseBook, reranker, refiner, block, habits`；新增一个 `PinyinSession` 依赖须改三处，漏改一处会悄悄让某个输入法失去某项功能（屏蔽、习惯）。
建议：抽取 `pinyinSession(segmenter, spell, habitScope)`。

- 提出者：STYLE@G3（SHOULD FIX，9/10）

#### F196 [SHOULD FIX] 九键「停顿未改变任何东西」测试无论代码如何都通过

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`PinyinSessionT9Test.kt:46`、`PinyinSession.kt:557`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinSessionT9Test.kt:46`、`PinyinSession.kt:557`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:180`

`session()` 没有 refiner，所以 `Action.Refine` 在 `unrefined ?: return snapshot()` 处直接返回，不会重读，音节列表 id 不可能改变；注释描述的情形是保持顺序（返回 0）的 refiner，它会运行 `place()` 并再次调用 `nineKeys.offer()`。
建议：给该会话 `refiner = { _, _, _, _ -> 0 }`，再加一个 refiner 选另一句、预期 id 改变的用例。

- 提出者：CORRECTNESS-1@S18（SHOULD FIX，9/10）

#### F197 [SHOULD FIX] 笔画测试名中「未知字按笔画数从少到多」从未被测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`StrokesTest.kt:35`、`Strokes.kt:82`、`Strokes.kt:27-29`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`StrokesTest.kt:35`、`Strokes.kt:82`、`Strokes.kt:27-29`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:47`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:37`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:23`

固件中只剩一个模型未知的字 㐄；另一个 𠀀（第 37 行）在 BMP 之外，被 `Strokes.read` 丢弃；已知字各有分数，排序键中的笔画数部分从不决定顺序，位打包排序键这部分的 bug 不会被发现。
建议：再加一个以 `h` 开头、模型未知、笔画多于 㐄 的 BMP 字并断言 㐄 在前，并在第 23 行注释中说明 BMP 过滤（它也排除了 干 和 𠀀）。

- 提出者：CORRECTNESS-1@S18（SHOULD FIX，9/10）

#### F198 [SHOULD FIX] 针对新版本日志记录类型的守卫实际上未被测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`TableUserTest.kt:134`、`RecordFormat.kt:56-60`、`TableUser.kt:163`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TableUserTest.kt:134`、`RecordFormat.kt:56-60`、`TableUser.kt:163`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:134`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:163`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:177`

类型 9 记录的载荷 `writeInt(0x0002FFFF)` 读作格式错误的 UTF 字符串；没有 `TableUser.kt:163` 的守卫时，`readUTF` 抛 `UTFDataFormatException`，`RecordFormat.read` 捕获后跳过该记录，所以测试无论如何都通过。
真正重要的是字段为有效 UTF 的新版本记录：没有守卫时它会落入 `else ->` 分支（TableUser.kt:177），被当作 SEEN 计数回放。
建议：把类型 9 记录写成 `writeUTF("wqwu"); writeUTF("你们"); writeInt(5)`，并断言 `seen` 与选择记录不变。

- 提出者：CORRECTNESS-2@S19（SHOULD FIX，9/10）

#### F199 [SHOULD FIX] ChineseNumbers 把「一点半点」等成语转成时间

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`ChineseNumbers.kt:132`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:132`

`time()`（ChineseNumbers.kt:132）在没有时间语境时也接受「X点半」：「没有一点半点」变成「没有1点半点」，「有一点半信半疑」变成「有1点半信半疑」。类 KDoc 说只是看起来像数字的词保持不变，而 `NOT_HOUR_BEFORE` 只覆盖 这/那/哪/第/每/几。
建议当「半」后接「点」或「信」时（或小时为「一」且前面没有 `TIME_BEFORE` 词时）不视为时间，并把这些用例加入 `ChineseNumbersTest`。

- 可能的运行时影响：语音输入「没有一点半点」这类成语时会被写成「没有1点半点」。
- 提出者：CORRECTNESS-1@S14（SHOULD FIX，9/10）

#### F200 [SHOULD FIX] PopupOverridesTest 的去重测试没有重复输入

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupOverridesTest.kt:127`、`PopupOverrides.kt:44`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupOverridesTest.kt:127`、`PopupOverrides.kt:44`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupOverridesTest.kt:127`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/popup/PopupOverrides.kt:44`

`shiftDoesNotShowTheSameCandidateTwice`（PopupOverridesTest.kt:127）的输入 `q 1 Q é` 大写后为 `1 Q É`，其中没有重复；也没有其他测试触及 `PopupOverrides.kt:44` 的 `.distinct()`，删掉它所有测试仍会通过。
建议改用 `q q Q 1` 这样的输入并期望 `[Q, 1]`。

- 提出者：CORRECTNESS-2@S22（SHOULD FIX，9/10）

#### F201 [SHOULD FIX] removeOutdated 按 id 选截断点却按时间戳删除，误删限额内的条目

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:215-218`、`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/db/ClipboardDao.kt:56`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:215`

`ClipboardManager.kt:215` 的 `removeOutdated` 按 id 选出截断点，却按时间戳删除；再次复制同一文本时，`updateTime` 会把旧 id 的时间戳往后移。例：上限 3，id 1(t100)、2(t200→t450)、3(t300) 加新的 4(t500)，截断点落在 id 2(t450)，于是 id 1 和 3 被删，只剩 2 条。这是上游逻辑。
修复：按 `timestamp` 给 `unpinned` 排序，或删除最新 `limit` 条以外的那些 id。

- 可能的运行时影响：剪贴板历史会删掉本应保留在数量上限之内的条目。
- 提出者：GENERIC-1@S30（SHOULD FIX，9/10）

#### F202 [SHOULD FIX] 设备端评测 runner 丢弃每个样本的上下文字段

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`EngineEvalRunner.kt:41`、`KeystrokeRun.kt:29`、`PinyinRun.kt:68-75`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineEvalRunner.kt:41`、`KeystrokeRun.kt:29`、`PinyinRun.kt:68-75`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:41`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/KeystrokeRun.kt:29`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:68`

`EngineEvalRunner.kt:41` 用 `substringBefore('\t')` 只保留输入部分，而十个评测集中有六个带第 4 个上下文字段：pinyin-chat（821 行）、-collide（5463）、-context（77）、-dialog（590）、-new（405）和 -web（3087）。JVM runner 会应用这个上下文（KeystrokeRun.kt:29、PinyinRun.kt:68-71），设备上却在没有上下文的情况下输入，而结果被当作可比的分数。
修复：解析第 4 个字段，在 `typeAndCollect` 中 `reset()` 之后调用 `fcitx.engineContext(context)`。

- 提出者：CORRECTNESS-1@S49（SHOULD FIX，9/10）

#### F203 [SHOULD FIX] 导入短语逐条添加且每条弹出一个 Snackbar

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:250`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:253`、`app/src/main/java/org/fcitx/fcitx5/android/ui/common/BaseDynamicListUi.kt:139`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:253`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:250`

每次 ui.addItem 都会执行 BaseDynamicListUi.onItemAdded：在 Main 上 updateFAB()、Snackbar.make().show() 和 notifyItemInserted；CustomPhrases.parse（:250）也在 Main 上运行。几千条短语的文件会创建几千个 Snackbar，可能冻结数秒甚至 ANR。
建议：在 Dispatchers.Default 上解析，并给 DynamicListAdapter 增加批量插入（一次 notifyItemRangeInserted、一个 Snackbar），或在导入期间设置 suspendUndo。

- 可能的运行时影响：导入含数千条短语的文件时主线程逐条插入并弹出 Snackbar，可能冻结数秒甚至触发 ANR。
- 提出者：PERFORMANCE@G8（SHOULD FIX，9/10）

#### F204 [SHOULD FIX] 改键后卡片可能在未勾选「首选」时成为该键首选

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:154-161`、`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:42-46`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:157`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:156`、`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:42-46`

`PunctuationEditorFragment` 第 156 行先执行 `entries[index] = new`，再调用 `indexOfFirst`；若被编辑的卡片排在新键其他卡片之前，会找到卡片自身，于是即使未勾选「首选」，它也成为该键实际输入的标点，并因 `PunctuationComponent.kt:42-46` 取第一项而成为键面标签。
同一发现还指出：卡片已是首选时复选框仍默认未勾选，取消勾选也无法将其降级。
建议：改键且未勾选时把卡片移到新键最后一张卡片之后，卡片已是首选时预先勾选复选框。

- 可能的运行时影响：用户修改卡片的键后，该卡片可能未经勾选就成为该键实际输入的标点和键面标签。
- 提出者：CORRECTNESS-1@S42（SHOULD FIX，9/10）

#### F205 [SHOULD FIX] pinyin 的 --misreadings 与双 pack 调用无测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:699`、`ReadersTest.kt:72-87`、`PinyinDictReader.kt:60`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:699`、`ReadersTest.kt:72-87`、`PinyinDictReader.kt:60`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:699`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:729`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:60`

EngineDataPlugin 每次构建都运行 `pinyin --remove --readings --misreadings <dicts> <new pack> latin.words`，但 MainTest 从不传 `--misreadings`，也从不传两个 pack；Main.kt:729 是唯一写 `misread.<word>` 元数据的代码，PinyinSessionMisreadingTest 自建了一份副本，所以此处出错不会让它失败。
PinyinDictReader.kt:60 中 misreadings 条目覆盖 readings 条目的规则也未测试，它决定了同时出现在两个列表中的 3 个发布词：般若、关卡、包扎。
建议：加一个 MainTest 用例，使用与 readings 重叠的 misreadings 文件和位于不同层的两个 pack，断言 `data.meta["misread.<w>"]`、每个读音下的权重以及每个 pack 词所在的层。GENERIC-1@S6 在 G2-14 中也把 `pinyin --misreadings` 列为未测试路径。

- 提出者：TESTING@G2（SHOULD FIX，8/10）

#### F206 [SHOULD FIX] 删除曾被选用的自造词后仍可输入并变为 LEARNED

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`K/engine/host/Engines.kt:346`、`K/engine/user/UserLog.kt:84-87`、`K/engine/user/WordLists.kt:54`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:354`、`UserLog.kt:87`、`UserModel.kt:238`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:346`

用户选用过的 ADDED 词以 SENTENCE 记录写入日志；`dropUser()` 后重放日志会重新学习该词，因其已不在列表中而成为 own word。
这与 `removeWord` 文档“an added word is no longer typeable”相矛盾：用户删除的词仍可输入，并在列表中显示为 LEARNED。
建议：在 `dropUser()` 前对每个被删的 added 词调用 `user().forget(listOf(entry))`，让 FORGOT 记录跟在 SENTENCE 之后；或者修改文档。

- 可能的运行时影响：用户在设置中删除的自造词经日志重放后仍可输入，并作为已学词重新出现在列表中。
- 提出者：CORRECTNESS-2@S8（SHOULD FIX，8/10）

#### F207 [SHOULD FIX] 基础词包测试无法因其名称所述行为而失败

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:220-232`、`Engines.kt:461`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:220-232`、`Engines.kt:461`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:232`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:243`

`Engines.addDictionaries` 无论词包进入基础层还是新词层都忽略其 `# layer:` 名，所以即使词包被错误并入新层，`!contains("2026q1")` 仍为真；名称中“scored as it says”部分没有任何断言。
建议：加载词包后断言 `listOf("你", "泥", "拟")`，并断言日志中没有 `WordLayers.NEW`（"new"）的记录（`UserLog.prior` 用 `writeUTF` 写入层名，若选 泥 而非 你 推动了该层，就会出现该记录）。

- 提出者：CORRECTNESS-1@S16（SHOULD FIX，8/10）

#### F208 [SHOULD FIX] 显示预测时actionable为false，无法对预测长按忘记或屏蔽

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:687`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:289`、`app/src/main/cpp/androidengine/androidengine.cpp:63`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:687`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:289`、`app/src/main/cpp/androidengine/androidengine.cpp:63`、`app/src/main/cpp/androidengine/androidengine.cpp:112`、`app/src/main/cpp/androidengine/androidengine.cpp:145`

`actionable = !predicting && candidates.isNotEmpty()` 在显示预测时恒为 false，而原生候选列表只在 `actionable` 为真时添加操作（androidengine.cpp:63、:112、:145），所以产品中无法对预测（`predictionOffers`，:289）执行 Forget 和 Block，尽管 `aPredictionLearnedMayBeForgottenAndAnyBlocked` 测试了它们，`Session.offers` 的文档也写明适用于 `[Snapshot.actionable]` 处。
建议：改为 `actionable = candidates.isNotEmpty()`（`offers()` 在不适用处已返回空集），并翻转 `onlyWhatAUserModelLearnedIsForgotten` 中的 `assertFalse(predicted.actionable)`，在预测测试中断言 `actionable`。

- 可能的运行时影响：用户无法在预测候选上长按执行忘记或屏蔽，学到的错误预测删不掉。
- 提出者：CORRECTNESS-1@S11（SHOULD FIX，8/10）

#### F209 [SHOULD FIX] 九键模糊读法的预编辑和回车上屏显示错误字母

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:741`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:475-476`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:528`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:741`

开启模糊音时（`Engines` 把 `s.fuzzy` 传给 `T9Segmenter`），按键 9664 经 Z_ZH 读作 zhong，而 `spelling(zhong).take(4)` 得到「zhon」，`commitRaw` 随后从 `restShown` 上屏这个「zhon」；模糊韵母同理（5464 → 「jin」），模糊的半音节对按键 9-6 显示「zh」。
建议：`Kind.SYLLABLE` 边显示完整拼写，只截断 partial、extended、initial 边，并在 PinyinSessionT9Test 中加模糊用例。

- 可能的运行时影响：九键开启模糊音时，预编辑显示、回车上屏的是「zhon」「jin」这类错误字母。
- 提出者：CORRECTNESS-2@S11（SHOULD FIX，8/10）

#### F210 [SHOULD FIX] 损坏的日志被静默截断，之后可能被重新导入覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:71`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:77-79`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:201`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:71`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:75`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:77`

`RecordFormat.read` 在第一条长度或 CRC 不对的记录处停止，`open()` 随即在该处截断文件且不调用 `onError`；若坏记录是第一条，文件只剩头部，`seeding` 变为真，libime 导入会替换用户学到的全部内容（第 77-81 行）。
默认 `openAppend` 是无缓冲的 `FileOutputStream`，每条记录一次写入，撕裂的追加最多丢一条，所以被丢弃的尾部长于 `MAX_RECORD + 8` 字节意味着真实损坏而非写入时崩溃。
建议：此时截断前像 `moveAside()` 那样把文件另存，并报告给 `onError`；`moveAside()`（第 75 行）本身也应报告给 `onError`。

- 可能的运行时影响：用户日志损坏后，用户学到的词被静默截掉，甚至被 libime 导入整体覆盖。
- 提出者：GENERIC-1@S11（SHOULD FIX，8/10）

#### F211 [SHOULD FIX] 导入码表的提示码与构词码输出没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`LibimeFilesTest.kt:77-105`、`LibimeFiles.kt:110-111`、`LibimeFilesTest.kt:29`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`LibimeFilesTest.kt:77-105`、`LibimeFiles.kt:110-111`、`LibimeFilesTest.kt:29`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:77`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:126`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:139`

`aTableHeaderWithAllItHas` 把 prompt 和 construct 字典树写为空，`db.main.dict`（电报码）也没有这两者，所以 `textFirst`（LibimeFiles.kt:126-127、:139）在任何测试中都不写出一行；若那里把 code 和 text 写反，所有带提示码或构词码的导入码表都会出错而不会有测试失败。
建议：在两棵树中各加一个条目（键为 `text\x01code`），断言 `&code text` 与 `^code text` 行，最好对照 libime 写出的固件。

- 提出者：CORRECTNESS-2@S17（SHOULD FIX，8/10）

#### F212 [SHOULD FIX] 九键分词测试从未按生产配置构建分词器

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:15`、`Engines.kt:538`、`T9Segmenter.kt:48-62`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:15`、`Engines.kt:538`、`T9Segmenter.kt:48-62`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:15`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:528`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:48`

Engines.kt:528 以 `T9Segmenter(s.fuzzy, abbreviations = false)` 构建，但没有测试传入模糊集合，T9Segmenter.kt:48-64 的模糊声母与韵母表未被测试；三个测试中有两个用生产从不使用的默认 `abbreviations = true`。
建议：在 `abbreviations = false` 下加入如 `setOf(Fuzzy.Z_ZH, Fuzzy.L_N)` 的用例，断言 `9`/`94` 和 `5`/`6` 出现带 `~` 标记的匹配。

- 提出者：CORRECTNESS-2@S17（SHOULD FIX，8/10）

#### F214 [SHOULD FIX] resolveBlock 的按词读音计数器没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/15 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:280-281`、`UserModelTest.kt:292-303`、`EnginesUserWordsTest.kt:122-136`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:280-281`、`UserModelTest.kt:292-303`、`EnginesUserWordsTest.kt:122-136`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:292`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:275`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:122`

`UserModelTest.kt:292` 与 `EnginesUserWordsTest.kt:122` 只屏蔽一个词的一种读音；没有测试屏蔽多音字的两种读音（行 hang 与 xing）再解除其一，而这正是 `resolveBlock` 中 `blockedIds[id]` 计数的场景（UserModel.kt:275）。
计数器若出错，词在解除屏蔽后仍被隐藏，或在一种读音仍被屏蔽时重新出现在预测和其他输入法中（`blockedAnyhow`、`ownTexts`）。
`LibimeImportTest` 已有含 行 hang/xing 的 `polyphones` 夹具，建议在每一步后断言各节点的 `blocked(id, node)` 与 `blockedAnyhow`。

- 提出者：TESTING@G4（SHOULD FIX，8/10）

#### F215 [SHOULD FIX] 发布门禁关闭近邻键读取，而应用默认开启

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-eval/release-report.sh:45`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/release-report.sh:45`）
- 位置：`lib/ime-eval/release-report.sh:45`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:249`

Engines.kt 以 `neighbours = s.typos` 构建拼音分词器，`EngineSettings.typos` 默认为 true；而 release-report.sh 的门禁运行不传 `--neighbours on`，`segmenter()`（Main.kt:249）因此以 neighbours=false 运行。
后果：只在开启近邻键读取时才损害干净输入的数据改动能通过门禁。
修复：门禁运行传 `--neighbours on`，并让 `learn` 也支持该选项。

- 提出者：CORRECTNESS-1@S23（SHOULD FIX，8/10）

#### F216 [SHOULD FIX] `score --half` 可能把其他样本的结果算给本样本

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Metrics.kt:64-71`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Metrics.kt:64-71`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:235`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Metrics.kt:64-69`

`score` 先选出半集，`Metrics.match`（Metrics.kt:64-69）再把半集内某输入的第 n 个样本与结果文件中该输入的第 n 个结果配对；若结果文件来自未加 `--half` 的运行（如 `baseline/` 文件或文档中的 `score … [--half tune]` 用法），配到的结果可能属于另一半的样本。
半集按期望文本划分，同输入的样本常落在不同半集（`pinyin-collide.tsv` 有 1906 个重复输入，`pinyin-context.tsv` 有 19 个），top1 和 won/lost 会静默出错。
修复：按运行实际输入的样本列表匹配（在结果中写入半集或样本行号），或先在全集上匹配再过滤。

- 提出者：CORRECTNESS-2@S23（SHOULD FIX，8/10）

#### F217 [SHOULD FIX] 预测（联想）测量代码缺少测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictLearning.kt:25`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictLearning.kt:25`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictLearning.kt:25`

`PredictLearning`、`PredictRun.offers`（含 `--rerank` 排序）以及 `predict`、`predict-learn`、`predict-set` 命令都没有测试，而对应的按键 `Learning` 测量是有测试的。
修复：在 KeystrokeRunTest 的 fixture 上检查 `continuations` 只给出词内部的边界，并检查「tune, typed once」一行不低于「tune, nothing learned」。
三个 S24 阶段在 G5-05 中也报告了 PredictLearning 与 `predict-*` 命令缺少测试。

- 提出者：TESTING@G5（SHOULD FIX，8/10）

#### F218 [SHOULD FIX] 「+ models」背后的重排逻辑没有测试覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:108-109`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:108-109`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:110`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/KeystrokeRun.kt:32`

PinyinRunTest 中 `Recording` 的 `pick` 总是返回 0、`refine` 总是返回 `NONE`，所以 `sentences.add(0, sentences.removeAt(picked))`、refiner 胜过 picker、`NONE` 回落到 picker 这些路径都没有执行；KeystrokeRun.kt:32 的 `Action.Refine` 循环也未测试，因为没有测试会话带 refiner。
修复：用返回 1 的假 picker 和返回 2 或 `NONE` 的假 refiner，断言用户可见的输出 `candidates()[0]` 和 `KeystrokeRun` 的 `first`。

- 提出者：TESTING@G5（SHOULD FIX，8/10）

#### F219 [SHOULD FIX] pending.toList() 非原子，可能丢失听写文本并泄漏识别器

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:209`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:209`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:233`

Kotlin 的 toList() 先读 size 再调用 get(0)，是两次独立加锁的调用；若某个 stretch 任务在两者之间于解码线程完成，其 invokeOnCompletion { pending -= job } 清空列表，get(0) 抛 IndexOutOfBoundsException。
在 finish() 中这会让异常处理器报告 NoMicrophone，听写文本不会提交；在 close()（line 233）中会跳过 letGoNext() 和 VoiceEngine.release()，users 不再归零，约 200 MB 的识别器和预备的 stream 在进程生命周期内一直驻留。
修复：synchronized(pending) { pending.toList() }，或把 stretch 作为一个专用 Job 的子任务启动并 joinAll 其 children。

- 可能的运行时影响：听写的文字可能不被提交，或约 200 MB 的识别器一直驻留内存。
- 提出者：GENERIC-1@S28（SHOULD FIX，8/10）

#### F220 [SHOULD FIX] 提交文字要等待一个随后被丢弃的 stream 构建

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:209`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:209`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:325`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:209`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:84`

最后一段 stretch 的文本就绪后，其任务仍会构建 next；用户有自己的词时，这是一个覆盖词包 78k 短语的新热词图（≥0.1 s、约 37 MB，见 VoiceEngine.kt:84 和 VoiceHotwords 的注释）。finish()（line 209）会 join pending，所以提交要等这次构建，随后 close() 又把这个 stream 原样释放，每次按住说话都如此。
修复：stop()/close() 之后不再构建 next（用一个标志或检查 capture?.isActive），或在不加入 pending 的任务中构建它。

- 提出者：PERFORMANCE@G6（SHOULD FIX，8/10）

#### F221 [SHOULD FIX] close() 之后仍继续解码已排队的语音段

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:313`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:313`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:313`

取消（滑动取消、切换字段、隐藏键盘）时 close() 设置 closed，但采集的 finally 仍会刷新 VAD 并排队最后一段；每个排队任务都会运行完整的编码器、16 路束搜索以及 fcitx 线程上的语言调用，然后 tell 丢弃结果，任务还会构建 next。一段 20 s 的语音会白白消耗数秒 4 线程 CPU。
修复：在 serial.withLock 内若 closed 已设置，则在调用 recognize 和构建 next 之前直接返回。

- 提出者：PERFORMANCE@G6（SHOULD FIX，8/10）

#### F222 [SHOULD FIX] 共享选项及其默认值在三个配置结构中重复书写

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.h:89`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.h:89`）
- 位置：`app/src/main/cpp/androidengine/androidengine.h:89`；另见 `app/src/main/cpp/androidengine/androidengine.h:85`、`app/src/main/cpp/androidengine/androidengine.h:110`、`app/src/main/cpp/androidengine/androidengine.h:118`

PageSize（7，IntConstrain(3, 10)）、Prediction、SentenceModel、LatinWords、Fuzzy 和 ShuangpinProfile 在 AndroidEngineConfig（line 85）、EnginePinyinPage（line 110）和 EngineShuangpinPage（line 118）中各出现一次，默认值和范围每次都复制。若一处改了而另一处没改，页面显示的默认值会与引擎不同；config_ 接受而页面约束拒绝的值，在通过 setConfigForInputMethod 加载并保存该页时也可能丢失。
修复：把每个默认值和约束放进一个具名 constexpr（或一个列出共享选项的宏）供三个结构共用；键名保持扁平，因为头文件要求与 libime 兼容。

- 提出者：STYLE@G6（SHOULD FIX，8/10）

#### F223 [SHOULD FIX] 字母布局别名（Text/T9）状态逻辑无测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`KeyboardWindow.kt:125`、`KeyboardWindow.kt:132`、`KeyboardWindow.kt:139`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`KeyboardWindow.kt:125`、`KeyboardWindow.kt:132`、`KeyboardWindow.kt:139`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:125`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:141`

KeyboardWindow 中的 textLayout、:132 的 current 映射、:141 的目标映射和 onImeUpdate 中的 showing 替换构成一个位于 app 胶水代码中的小状态机。
LayoutSwitchPolicyTest 未覆盖它，也没有测试提到 T9 布局；posted switchLayout 与即时 onImeUpdate 交错、在 Number 布局时切换 IME 等情况容易回归，CLAUDE.md 要求决策逻辑放在 lib/ime-core 并附测试。
建议：让 LayoutSwitchPolicy 感知字母别名（如 lettersLayout 参数）并在那里测试。

- 提出者：CORRECTNESS-1@S37（SHOULD FIX，8/10）

#### F224 [SHOULD FIX] 活动重建时启动 intent 被重复处理

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:67`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:73`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:93`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:67`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:73`

MainActivity.onCreate 不检查 savedInstanceState 就调用 processIntent(intent)，onNewIntent（:73）也不调用 setIntent；旋转或切换应用语言（AppLanguage.set 会触发）重建活动后，ACTION_RUN 会清掉恢复的返回栈并再次打开原路由，ACTION_VIEW 会再次显示导入词典对话框。
建议：if (savedInstanceState == null) processIntent(intent)，并在 onNewIntent 中调用 setIntent(intent)。

- 可能的运行时影响：旋转屏幕或切换应用语言后，设置界面会跳回启动路由或再次弹出导入词典对话框。
- 提出者：CORRECTNESS-2@S40（SHOULD FIX，8/10）

#### F225 [SHOULD FIX] 大写键「恢复内置列表」和重置得不到内置列表

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:214`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:221`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/popup/PopupOverrides.kt:34`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:215`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:222`

PopupOverridesFragment（:215 及 :222 的 Reset）假设 current.without(label) 会恢复 preset；但当 q 有覆盖时，对 Q 调用 PopupOverrides.resolve 回退到 shiftedOverride(q)，而不是 PopupPreset[Q]。
键盘随后显示的与编辑器显示的不同，违背 PopupOverrides「编辑器所见即弹出所见」。
建议：与 current.without(label).resolve(label, preset) 比较，若与用户所设不同则存储显式覆盖。

- 可能的运行时影响：对大写字母键恢复内置长按列表后，键盘实际弹出的仍是小写覆盖的大写版本，与编辑器显示不一致。
- 提出者：CORRECTNESS-1@S41（SHOULD FIX，8/10）

#### F226 [SHOULD FIX] 新建快捷短语列表的名称校验不足，可致崩溃或重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:181-189`、`QuickPhraseManager.kt:35-38`、`DynamicListAdapter.kt:202`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:189`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/QuickPhraseManager.kt:35-38`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:242`

这是上游代码。「新建」对话框只拒绝空名称，而 `QuickPhraseManager.newEmpty`（`QuickPhraseManager.kt:35-38`）直接在 `customQuickPhraseDir` 下对 `<name>.mb` 调用 `createNewFile()`。
名称如 `a/b` 会在点击监听器中抛出 IOException 使应用崩溃；名称已存在时 `createNewFile()` 返回 false，却仍再添加一个指向同一文件的 `CustomQuickPhrase`，删除任一行都会删掉两行共用的文件。
建议：像第 242 行 `importFromUri` 那样，拒绝含 `/` 的名称和已在 `ui.entries` 中的名称。

- 可能的运行时影响：输入含 `/` 的名称会使应用崩溃，重名时删除一行会连带删除另一行的快捷短语文件。
- 提出者：GENERIC-1@S42（SHOULD FIX，8/10）

#### F227 [SHOULD FIX] `runCmd` 的失败回退是死代码

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:17-24`、`Utils.kt:13-14`、`AndroidAppConventionPlugin.kt:40`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:17-24`、`Utils.kt:13-14`、`AndroidAppConventionPlugin.kt:40`）
- 位置：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:16-24`

`providers.exec` 默认 `isIgnoreExitValue` 为 false，命令非零退出时 `result.get()` 直接抛异常，不会走到 `exitValue != 0` 的分支。
因此没有 `.git` 的检出（源码 tarball、nix store 副本）会在配置阶段失败，而不是回退到 `baseVersionName` 和 `"N/A"`；该问题继承自上游 fcitx5-android（未标 PRE-EXISTING）。
建议：在 `exec {}` 块里设 `isIgnoreExitValue = true`。

- 提出者：CORRECTNESS-1@S2（SHOULD FIX，7/10）

#### F228 [SHOULD FIX] 五个码表串行编译，每次在新 JVM 中重复同样工作

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:427-438`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:744-746`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:427-438`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:744-746`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:418`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:736`

每次 `table` 运行（lib/ime-dict-tool Main.kt:736）都映射 pinyin.data、解析词包并运行 `wordsByUse`，遍历、合并、排序约 80 万词的完整词表；这在单核上顺序执行五次，每张表还要额外一次 JVM 启动。
建议：把每张表作为 `WorkerExecutor.processIsolation` 工作项提交以并行运行，或让 `table` 接受多个输入并只计算一次 `wordsByUse`。

- 提出者：PERFORMANCE@G1（SHOULD FIX，7/10）

#### F229 [SHOULD FIX] pack/words 的基础层过滤从未用分层数据测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:374`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:374`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:424`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:374`

engine-data.sh 先用上一版 `WORDS` pack 编译 pinyin.data，再运行 `words` 和 `pack --lexicon`；Main.kt:424 的 `layers.layer(id) == 0` 过滤把人工词（分数来自早先的拟合）排除在 CountFit 之外，:374 的 `words` 也有同类基础层过滤。
所有测试的数据都只有基础层，删掉该过滤也能通过全部测试，却会改变发布的 words-* pack 中的分数。
建议：在 `newWordsAreFound…` 测试中用一个 pack 编译数据，断言 `fit:` 行不变且人工词仍是候选。

- 提出者：TESTING@G2（SHOULD FIX，7/10）

#### F230 [SHOULD FIX] CommonCrawl 每个分片等全部抓取完成，线程池空闲

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:63-73`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:63-73`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:56`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:68`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:73`

CommonCrawl.kt:56 每个分片提交 100 个抓取后等待全部完成（:68），再在调用线程上写 parquet（:73），之后才开始下一个分片的抓取。
一个文件处于 503 退避或 DuckDB 写入期间，其余 7 个线程和网络都闲置；默认运行有 30 个分片（2 个 crawl × 1500 个文件 / 100）。
建议：在写分片 n 之前先提交分片 n+1，使同一时刻最多持有两个分片的页面。

- 提出者：PERFORMANCE@G2（SHOULD FIX，7/10）

#### F231 [SHOULD FIX] 拼音长按“遗忘”不会传到码表

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`K/engine/session/PinyinSession.kt:349`、`K/engine/host/Engines.kt:522`、`K/engine/table/TableSession.kt:299`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:529-546`、`PinyinSession.kt:342-348`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:522`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:530`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:535`

拼音、T9、双拼会话（Engines.kt:522、530、535）只获得 `block = ::block` 而没有 forget 钩子，`PinyinSession.forget` 只处理拼音模型和习惯。
码表经 `onSaved` 传给拼音的短语因此仍留在该码表的 `TableUser.saved` 中并继续被提供，违反 `shared()` 的规则“what is blocked or forgotten anywhere is so here”（码表长按与设置都经 `forgetEverywhere`/`forgetInTables` 遵守该规则）。
建议：给 PinyinSession 一个调用 `forgetInTables(texts)` 的 forget 回调；用单一遗忘入口取代目前行为不一致的三条遗忘路径。CORRECTNESS-1@S8 在归入 G3a-03 的发现中也提到此问题。

- 可能的运行时影响：在拼音中长按遗忘的短语仍会在码表输入法中作为候选出现。
- 提出者：CORRECTNESS-2@S8（SHOULD FIX，7/10）

#### F232 [SHOULD FIX] DataFile 损坏测试未覆盖经 has() 读取的可选段

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:64`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:72`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:81`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:64`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:72`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:81`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/DataFileTest.kt:131`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:43`

校验和按条目中存储的 id 计算，id 被翻转仍能通过 `verify = true`；该段随后看似缺失，`section()` 会报告缺失，但 `has()` 只返回 false，而测试只经 `section()` 读段。
PinyinData.kt:43 经 `has()` 读取 WORD_LAYER，于是 meta 中的层名保留、所有词都变为 `base`，层先验在无报错的情况下丢失，与 DataFile KDoc“丢失的段会被请求它的读者发现”相矛盾。
建议：在该测试中加入 `has()` 用例；在 PinyinData 中，当 meta 命名多于一个层时强制要求 WORD_LAYER。

- 提出者：CORRECTNESS-2@S15（SHOULD FIX，7/10）

#### F233 [SHOULD FIX] LibimeTrie 输出的键字节受树深而非文件大小约束

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`K/engine/libime/LibimeTrie.kt:58`、`LibimeFiles.kt:40-50`、`J/data/pinyin/ImportedDictionaries.kt:99-104`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeTrie.kt:101`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeTrie.kt:13`

KDoc（13-16 行）称构造的文件“cannot make it walk, or keep, much more than it holds”；遍历本身有界，但深度 d 的每个终止节点都向访问者交出 d 字节的键，d 最高为 `MAX_DEPTH` = 1024，约 10 字节文件节点可变成约 1 KB 键，v2 文件经 zstd 压缩后节点数组更小。例：约 1.3 MB 节点可产生约 65 MB 键字节、约 200 MB String，引发 `OutOfMemoryError`。
`ImportedDictionaries.migrate` 只捕获 `IOException`/`DataFormatException`，且解析后才把文件移走，所以每次启动都会重复崩溃。
建议：累计已输出键字节，超过输入大小的小倍数时抛 `DataFormatException`，和/或把 `MAX_DEPTH` 降到接近最长真实键（如 256）。

- 可能的运行时影响：导入构造或异常的 libime 词典文件会使应用因 OOM 崩溃，且每次启动都重复崩溃。
- 提出者：CORRECTNESS-1@S9（SHOULD FIX，7/10）

#### F234 [SHOULD FIX] “the prior learned is kept”测试从未检验先验被保留

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:235-262`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:235-262`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:261`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:271`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:168`

重启后测试断言的顺序与单次选择前看到的相同，而仅靠用户计数也会得到该顺序；最后再选 10 次后 泥 排第一的断言同样可能只来自用户模型计数（参照第 168 行，几次选择就能让 拟 领先）。
建议：在第一个引擎关闭后检查日志中存在 `new` 先验记录，或与把词包放在基础层的运行结果对比。

- 提出者：CORRECTNESS-1@S16（SHOULD FIX，7/10）

#### F235 [SHOULD FIX] 九键末尾的2/3/6/7被读作EXTENDED而非PARTIAL

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:113`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:178`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Penalties.kt:32-39`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:113`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:120`

T9Segmenter 中 a、e、o（以及 6/7 上的 m/n/r）是单字母音节，`full` 对 2、3、6、7 非空，输入以这些数字结尾时它们开始的每个音节（ba、ca、men、shi…）都被标为 `Kind.EXTENDED`（`penalties.extended = -2`，且 `PinyinDecoder.lookAhead` 跳过 EXTENDED 边），而末尾的 INITIAL 边也被丢弃（第 120 行）。
结果：8 个数字键中有 4 个，正在输入的音节得分比其他键低 10 倍；全拼对同样状态（`wo` + `m`）读作 INITIAL（-1）并可 lookAhead，`regular` 的注释也表明 呣/嗯/儿 本不应改变数字的读法。
建议：只在长于一个字母的 regular 音节完整时才标 EXTENDED（如 `full != null && length > 1 && keys in regular`），为末尾的 2 和 6 加测试，并重跑 T9 评测集。

- 可能的运行时影响：九键输入以 2、3、6、7 结尾时，正在输入的音节被大幅压低，用户看到的候选排序变差。
- 提出者：CORRECTNESS-1@S10（SHOULD FIX，7/10）

#### F236 [SHOULD FIX] 九键上啊/呃/哦使2、3、6上的声母无法按简拼读出

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:66`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:108`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:121`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:66`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:121`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:40`

`regular` 收录所有 `split` 非空的音节，包括单字母韵母 a、e、o，所以 `whole["2"]`、`whole["3"]`、`whole["6"]` 恒为 regular 并令 `longest = 1`。
在生产使用的 `abbreviations = false` 下，第 121 行因此永远不为 b、c、d、f、m、n 加 INITIAL 边：`25`（北京，bj）和 `65`（南京，nj）只能读作 啊/哦 加半个音节；第 40 行注释说留出 呣 和 嗯 是为了让 6 保留简拼，但 哦 照样阻断所有 6，而测试只检查 `7948`。
建议：像 m/n/r 一样把单字母叹词韵母排除出 `regular`，或改正注释并加 `25`/`65` 测试固定预期行为。

- 可能的运行时影响：九键输入 `25`、`65` 等时得不到 北京、南京 这类声母简拼读法。
- 提出者：GENERIC-1@S10（SHOULD FIX，7/10）

#### F237 [SHOULD FIX] 每次输入约20个可变字段在两处手工重置且不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`PinyinSession.kt:515-530`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinSession.kt:515-530`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:535`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:515`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:538`

`clear()`（第 515 行）重置 `ownFirst`、`ownFirstKeys` 和 `firstBeforeHabit`，而 `read()` 的空输入分支（第 538-546 行）不重置，两处都不重置 `decoderFirst` 和 `restShown`；目前之所以正确，只是因为读取方有守卫（`learnPrior` 检查对象同一性）。
建议：把每次读法的状态归为一个不可变值，由 `read()` 和 `clear()` 整体替换。

- 提出者：STYLE@G3（SHOULD FIX，7/10）

#### F238 [SHOULD FIX] 用户层门禁未带 reranker、KeyHabits 与 PACK

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:54-55`、`release-report.sh:68`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:54-55`、`release-report.sh:68`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:54-55`；另见 `lib/ime-eval/release-report.sh:68`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:519-524`

Learning.kt:54-55 构建的 `PinyinSession` 只有 `user` 和 `prior`，而应用还传入 `reranker`、`refiner` 和 `habits`（Engines.kt:519-524）。
因此 release-report.sh:68 的用户层门禁（「95% first」检查）发现不了 reranker 压低已学短语或 KeyHabits 回归；设置 PACK 时，该门禁与其他门禁测的是不同的词典。
修复：让 `learn` 接受 `--rerank`、`--refine`、`--pack` 和 habits 范围，并由 release-report 传入。

- 提出者：CORRECTNESS-1@S23（SHOULD FIX，7/10）

#### F239 [SHOULD FIX] PinyinRun 预热在每个线程上重放整手样本三次

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:63`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:63`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:63`

JIT 编译结果在 JVM 内所有线程共享，但 PinyinRun.kt:63 的预热让每一手在计时前把每个样本的完整输入解码 `WARMUP_ROUNDS` 次，且每次调用都新建 reranker 和 refiner。
预热开销随集合规模增长：在 22,317 句的集合上每个样本多解码三次，而固定量的预热同样足以预热 JIT。
修复：只用有限样本（如每手前约 200 条）或固定时长预热，计时部分保持不变。

- 提出者：PERFORMANCE@G5（SHOULD FIX，7/10）

#### F241 [SHOULD FIX] 固定并校验哈希的 onnxruntime 可能被预装版本静默替换

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/CMakeLists.txt:74`、`build.gradle.kts:29`、`host-eval.sh:11`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/CMakeLists.txt:74`、`build.gradle.kts:29`、`host-eval.sh:11`）
- 位置：`lib/sherpa-onnx/build.gradle.kts:29`；另见 `lib/sherpa-onnx/src/main/cpp/CMakeLists.txt:74`、`lib/sherpa-onnx/src/main/cpp/cmake/onnxruntime.cmake:156`、`lib/sherpa-onnx/host-eval.sh:11`

上游选项 SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE 默认为 ON（CMakeLists.txt:74），cmake/onnxruntime.cmake（约 156-208 行）会优先使用 $SHERPA_ONNXRUNTIME_INCLUDE_DIR/$SHERPA_ONNXRUNTIME_LIB_DIR，而不是经 SHA-256 校验的下载；host-eval.sh（11-15 行）还会优先使用 /usr/local/lib/libonnxruntime*。
这两种情况下 APK 或评测二进制都会基于未经校验的 onnxruntime 构建，与 README.md（each SHA-256 checked）和 host-eval.sh 的注释相矛盾。
修复：在两处都传 -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF。

- 提出者：GENERIC-1@S28（SHOULD FIX，7/10）

#### F242 [SHOULD FIX] 每个 stream 都重建相同的 78k 短语热词图

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:192`、`VoiceEngine.kt:90`、`VoiceListener.kt:325`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:192`、`VoiceEngine.kt:90`、`VoiceListener.kt:325`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:177`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:90`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:143`

CreateStream(hotwords) 每次都复制词包全部已编码热词并新建一个 ContextGraph；VoiceEngine.kt:90 对每个 stretch 以及带屏蔽的重试（line 143）都调用它，而一个 listener 的 words 不会变化，所以 N 个 stretch 的一次按住会构建 N+1 个相同的图。每次构建都持有 serial 锁，下一段识别只能等待。
修复：youmo 已在修改此文件，可加一个由互斥锁保护的单条目缓存（hotwords 字符串 → ContextGraphPtr）；图已以只读方式共享（hotwords_graph_），youmo 的 completing_ 缓存也有互斥锁保护。

- 提出者：PERFORMANCE@G6（SHOULD FIX，7/10）

#### F243 [SHOULD FIX] youmo 自写的 utf8FromJString 编码函数没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:508`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/native-lib.cpp:508`）
- 位置：`app/src/main/cpp/native-lib.cpp:508`

引擎返回的每个提交串、预编辑和候选都经过 utf8FromJString（native-lib.cpp:508），但没有测试覆盖代理对分支（码表中的 CJK Ext-B）或孤立代理转 U+FFFD 的分支，尽管代码读起来是正确的。
修复：把它移到一个小头文件中，像 host-test.sh 测 context-graph 那样加一个主机编译的 gtest，覆盖合法代理对、末尾的孤立高代理、开头的孤立低代理，以及 U+7F/80/7FF/FFFF/10000 边界。CORRECTNESS-2@S25 在 G6-02 的投票中也提出了这一缺口。

- 提出者：TESTING@G6（SHOULD FIX，7/10）

#### F244 [SHOULD FIX] 在同一输入框收起再弹出键盘后，引擎上下文丢失

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:943`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:474`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:709`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:709`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:943`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:474`、`app/src/main/cpp/androidengine/androidengine.cpp:284`、`app/src/main/cpp/androidengine/androidengine.cpp:391`

`onFinishInputView` 会投递 `focusOutIn()`（FcitxInputMethodService.kt:943）；native 侧 focus out 调用 `deactivate`、focus in 调用 `activate`，两者都发送 `EngineEvent::Reset`（androidengine.cpp:284、:391），`PinyinSession` 用 `dropContext()` 处理 Reset。在同一字段再次显示键盘时只调用 `onStartInputView`、不调用 `onStartInput`，`tellTextBeforeCursor` 不会运行，第一句话在没有上下文的情况下重排；`onConfigurationChanged` 在只有 uiMode 变化时调用的 `reset()`（:474）也会这样丢失上下文。README 说两个模型都会读取光标前的文字。
修复：在 `onStartInputView` 末尾和 :474 的 reset 之后调用 `tellTextBeforeCursor { currentInputConnection?.getTextBeforeCursor(ContextChars, 0) }`。

- 可能的运行时影响：在同一输入框收起再弹出键盘（或只切换深色模式）后，第一句候选在没有前文上下文的情况下重排。
- 提出者：CORRECTNESS-2@S33（SHOULD FIX，7/10）

#### F245 [SHOULD FIX] 撤销或确认删除在挂起后才清空 pendingDeleteIds，可能丢失剪贴条目

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:215-216`、`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:229-230`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:215`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:229`

撤销（ClipboardWindow.kt:215-216）和关闭提示（:229-230）两条路径都在 `undoDelete`/`realDelete` 恢复执行之后才调用 `pendingDeleteIds.clear()`。挂起期间新发生的删除会丢掉自己的 id，它自己的 snackbar 撤销恢复不了任何内容；而且 `ClipboardDao.realDelete`（`DELETE ... WHERE deleted=1`）可能把这条新条目彻底删除。窗口很窄（一次 Room 写入），但结果是丢失一条剪贴内容。
修复：在 `launch` 之前同步地快照并清空列表，只硬删除快照中的 id（`realDelete(ids)`）。

- 可能的运行时影响：连续删除剪贴条目时，后一次删除可能无法撤销并被永久删除。
- 提出者：GENERIC-1@S36（SHOULD FIX，7/10）

#### F246 [SHOULD FIX] 设备端评测在 25M 模型精排之前就读取候选，结果依赖时序

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`EngineEvalRunner.kt:104-107`、`androidengine.cpp:27`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineEvalRunner.kt:104-107`、`androidengine.cpp:27`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:107`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:27`、`app/src/main/cpp/androidengine/androidengine.cpp:349`

androidengine.cpp:27 和 :349 在最后一个按键 30 ms 后才发送 `Refine`，而 `getCandidates` 在最后一个按键后只隔一次 `isEmpty()` 往返就读取候选（EngineEvalRunner.kt:107），所以通常跳过了 25M 重排，在慢设备上或 GC 停顿时又可能只应用了一部分；JVM 的 PinyinRun 则以 `paused = true` 计分。
修复：最后一个按键后等待超过 `PauseBeforeRefine`，直到 refine 的候选列表到达（或候选不再变化）再读取，并在 KDoc 中说明。

- 提出者：CORRECTNESS-1@S49（SHOULD FIX，7/10）

#### F247 [SHOULD FIX] Engines 对象从不关闭，fcitx 停止后仍占着模型和文件句柄

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`EngineBridge.kt:67-73`、`Fcitx.kt:543-559`、`Engines.kt:879`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineBridge.kt:67-73`、`Fcitx.kt:543-559`、`Engines.kt:879`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:67`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:543`

`EngineBridge.kt:67` 的 `made` 是与进程同寿命的 lazy 值。`Fcitx.stop()`（Fcitx.kt:543，IME 服务销毁时由 `FcitxDaemon.disconnect` 调用）不释放它，两个句子模型、用户模型、码表和打开的日志句柄都留在缓存进程里；`Engines.close()` 存在，但没有人调用。
修复：在 `dispatcher.stop()` 之后关闭并丢弃引擎（把 `made` 改成可以重置的可空值），重建本来就是惰性的。

- 提出者：PERFORMANCE@G7（SHOULD FIX，7/10）

#### F248 [SHOULD FIX] 导入的词包和词典被一次性整个读进内存

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`EngineBridge.kt:150-158`、`Engines.kt:425-446`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineBridge.kt:150-158`、`Engines.kt:425-446`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:153`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:162`

`Engines.Pack.lines` 是 `Sequence`，但 `readLines().asSequence()` 仍会整读文件；`readPacks` 在解析第一个包之前就读完所有包；EngineBridge.kt:162 又把所有词典 flat-map 成一个列表。峰值时堆中以 String 形式持有全部导入文本（大型搜狗词库可达数十 MB），叠加在模型之上。
修复：用 `sequence { file.useLines { yieldAll(it) } }` 构建序列，并让 `Additions.dictionary` 和 `newDictionary` 返回 `Sequence<String>`。

- 提出者：PERFORMANCE@G7（SHOULD FIX，7/10）

#### F249 [SHOULD FIX] 从旧 app 迁移数据的路径缺少端到端测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`UserDataManager.kt:106-108`、`ThemeFilesManager.kt:55-71`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`UserDataManager.kt:106-108`、`ThemeFilesManager.kt:55-71`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:108`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:72`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:117`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:58`

README 承诺可以从旧 app 迁移数据。`UserDataOrigin.of` 有测试，但真正搬数据的部分没有：旧偏好文件改名（UserDataManager.kt:108-111）、engine 目录的导出与导入（:72、:117，README 说这里存放学到的词），以及 `ThemeFilesManager.listThemes` 在另一个包名的绝对路径下找回背景图（ThemeFilesManager.kt:58-75）。
修复：加一个 Robolectric 往返测试（先导出，再导入到全新目录），以及一个带过期图片路径的 listThemes 用例。

- 提出者：TESTING@G7（SHOULD FIX，7/10）

#### F251 [SHOULD FIX] AutoPhraseLength 对话框的说明文字挤掉了选项列表

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:251`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:251`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:150`

AutoPhraseLength（第 150 行）设置了 `setDialogMessage(item.message)`；ListPreference 没有对话框布局，`PreferenceDialogFragmentCompat` 会调用 `builder.setMessage(...)`，而 AlertController 在有消息时不会添加单选列表，对话框只剩文字和「取消」。
该问题在 G8b-23 的 key 崩溃修复后才会出现。
建议：把说明放到偏好摘要中，或使用同时包含说明和列表的自定义对话框视图。

- 可能的运行时影响：修复 key 崩溃后，AutoPhraseLength 对话框仍只显示说明文字，用户无法选择取值。
- 提出者：CORRECTNESS-2@S43（SHOULD FIX，7/10）

#### F252 [SHOULD FIX] VIEW 过滤器缺少 .words，下载的词库包无法用本应用打开

- 来源：第一轮（整仓 @07d2778） · 共识：1/11 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/AndroidManifest.xml:89-105`、`PinyinDictionary.kt:13`、`strings.xml:567`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/AndroidManifest.xml:103`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:114`

PinyinDictionaryFragment.kt:114 引导用户在浏览器中下载词库包并像普通文件一样导入，`PinyinDictionary.Type` 也接受 `.words`，但 AndroidManifest.xml 的 VIEW intent filter 只列出 `.dict`、`.scel` 和 `.txt`。
因此点击下载好的 `youmo-new.words` 时，系统不会提供 Youmo 作为打开方式。
修复：为 `.words` 添加 `pathPattern` 和 `pathSuffix` 条目。

- 可能的运行时影响：用户点击下载好的 .words 词库包时，打开方式中不会出现本应用，无法按应用内说明直接导入。
- 提出者：CORRECTNESS-1@S46（SHOULD FIX，7/10）

#### F253 [SHOULD FIX] 缺少 baseline profile 且排除 profileinstaller

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt:111-113`、`app/build.gradle.kts:189`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt:111-113`、`app/build.gradle.kts:189`）
- 位置：`build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt:111`；另见 `app/build.gradle.kts:189`

上游在热路径是原生 libime 时移除了这两项（a4b484d7），但本 fork 每次按键运行的格解码和 4M 重排模型推理都是 Kotlin/dex 代码。
侧载安装且没有 profile 时，每次 IME 进程冷启动都以解释或 JIT 方式执行这些代码，直到 ART 后台 dexopt 完成编译。
建议：为 `lib/ime-core` 的引擎和会话类生成 baseline profile 并保留 profileinstaller；或者记录实测的首次按键延迟，证明不需要 profile。

- 提出者：PERFORMANCE@G1（SHOULD FIX，6/10）

#### F254 [SHOULD FIX] apply.py 的错位读音检查没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lexicon/tools/apply.py:75-80`、`Main.kt:284-304`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/tools/apply.py:75-80`、`Main.kt:284-304`）
- 位置：`lexicon/tools/apply.py:76`；另见 `lexicon/tools/apply.py:80`

apply.py:76 的错位检查（与建议读音共享音节不足一半的读音须带注释）是防止读音错行的唯一关卡；`pack --lexicon` 只复查音节存在且数量与字数相符，所以错位会把错误读音发进词包。
仓库没有 Python 测试，代价是一个基于临时目录的小 `unittest` 文件（重复判定、缺词、错位读音、出错时不写任何文件）加一个 CI 步骤。
该发现还指出 :80 需要 Python 3.12+，至少应把 join 移到 f-string 之外（见 G2-02）。

- 提出者：TESTING@G2（SHOULD FIX，6/10）

#### F255 [SHOULD FIX] 列表词变化后每张码表在下次按键时重新编码全部列表词

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:595-612`、`SharedWords.kt:46-55`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:595-612`、`SharedWords.kt:46-55`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:593`

只要 `model.listings` 变化或 UserModel 重建（addWord、导入、removeWords、reload），`coded()` 就重建 `CodedWords(table.dictionary, model.listedTexts())`，对每个列表词（所有导入词典和词包，可能 10^5–10^6 个）运行 `PhraseRules.encode` 及 `exactRange`/`text` 查找，并为 五笔、二笔、自然码、五笔拼音 及每张导入码表各保留一份 TreeMap。
对有大量导入词典的用户，这意味着数秒卡顿和数十 MB 堆占用。
建议：只编码新增的列表词，或按首键惰性建立索引。

- 提出者：PERFORMANCE@G3（SHOULD FIX，6/10）

#### F256 [SHOULD FIX] 关闭学习的输入框中屏蔽与忘记仍会写入磁盘

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:280`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:371`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:46`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:371`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:280`

`pinnable` 在密码框中拒绝固定，理由是「这里什么都不保留」，但 `blockable` 和 FORGET 选项（第 280 行）没有 `learning` 检查；在密码框中长按可把候选文本及其音节（接近用户所输入的内容）持久化到屏蔽词列表或一条 FORGOT 日志记录中。
需要用户主动选择该操作，暴露面较小；`TableSession.offers` 有同样缺口。
建议：像固定一样，以 `learning` 为两个选项的前提。

- 可能的运行时影响：在密码等关闭学习的输入框中，用户长按屏蔽或忘记会把接近所输入内容的文本写入磁盘。
- 提出者：CORRECTNESS-2@S11（SHOULD FIX，6/10）

#### F257 [SHOULD FIX] 记录回放抛出非IOException时整个日志打开失败

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordFormat.kt:58`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:199-204`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordFormat.kt:58`

类文档称字段读不出的记录会被跳过，但只捕获了 IOException：`UserLog.replay` 中负的 `readShort()` 让 `List(n)` 抛 IllegalArgumentException，`UserModel.learn` 和 `Entry` 也用 `require`。
于是一条 CRC 正确但内容错误的记录（文档归因于写入方 bug）会让 `RecordStore.open` 抛 RuntimeException，而 `Engines.user()` 只捕获 IOException，故每次启动都失败。
建议：逐条记录也捕获 RuntimeException，并通过 `onError` 报告跳过的记录数（目前它从未收到）。

- 可能的运行时影响：一条内容异常的用户日志记录会使用户词库在每次启动时都打不开。
- 提出者：CORRECTNESS-2@S11（SHOULD FIX，6/10）

#### F258 [SHOULD FIX] 共享词绕过了构建期词所受的顶屏检查

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWords.kt:52-53`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableText.kt:70`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:281`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWords.kt:53`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:281`

`TableText.addWords` 只在较短前缀已能通向某处的满长编码上加词（`fits`/`follows`），以保证无路可走的键仍会上屏（顶屏）；`CodedWords` 却接受任何按规则编码的词。
通过 `TableSession.leadsAnywhere`（TableSession.kt:281），在拼音中打过一次的词可能让 五笔 中原本无路的前缀继续延伸，或让编码更短的词成为某编码的首选。
建议：在 `CodedWords` 中应用同样的 `fits` 检查，或写明这一取舍。

- 可能的运行时影响：在拼音中打过的词会改变 五笔 等码表的顶屏行为和首选候选。
- 提出者：CORRECTNESS-2@S12（SHOULD FIX，6/10）

#### F259 [SHOULD FIX] 丢弃一个选择键会把其后的键移到别的候选上

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:68`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableConfTest.kt:129`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:68`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableConfTest.kt:129`

TableConf.kt:68 从按位置索引的字符串中删去非 ASCII 的选择键，所以对 `0=，,1=q`，`q` 选中第一个候选，而在 fcitx 中它选第二个；含此类键的导入 `.conf` 会在没有任何警告的情况下选错候选。
建议：有条目不可用时忽略整个列表，或保留每个键的位置，并为此加断言。

- 可能的运行时影响：导入含非 ASCII 选择键的 .conf 后，用户按选择键会选中错误的候选。
- 提出者：CORRECTNESS-1@S19（SHOULD FIX，6/10）

#### F260 [SHOULD FIX] 计数减半会永久删除用户自有词与 libime 导入词

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`UserModel.kt:116`、`UserModel.kt:160`、`LibimeImport.kt:62-66`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:116`

计数 1 依次减半为 0.5、0.25，在第三次减半时被丢弃（在 5 万上限下约再输入 7.5 万个词之后），`forEachCount` 随后在压缩中略过该词；下次重启后，用户自己造的词或 `LibimeImport.learn` 从 libime 用户词典导入的永久词就无法再打出。
这与规格中的「越用越顺手：用户词库」相悖，并让迁移来的词逐渐消失。
建议在压缩中保留自有词与导入词（设计数下限，或写零计数的 WORD 记录），并用单独的上限约束它们。

- 可能的运行时影响：用户自造或从 libime 迁移来的词在长期使用后被删除，重启后无法再输入。
- 提出者：CORRECTNESS-1@S13（SHOULD FIX，6/10）

#### F261 [SHOULD FIX] 用 “ 跨过 ” 后 fcitx 的引号状态反转

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`AutoPairs.kt:48`、`FcitxInputMethodService.kt:754`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:48`

`AutoPairs.kt:48` 的跨过逻辑用于 fcitx 被重置（如屏幕旋转）的情况：fcitx 产生 “ 并认为引号已打开，而文本显示引号已闭合。`type()` 在光标回报到达前就弹出配对，`onUpdateSelection` 中 `quoteOpen` 的前后比较都是 false，从不重置 fcitx，下一个 `"` 会打出 ”。
建议把这种情况报告给调用方（返回值或标志），让服务像用户离开引号配对时那样调用 `reset()`。

- 可能的运行时影响：跨过闭合引号之后，下一次输入引号会得到方向错误的 ”。
- 提出者：CORRECTNESS-2@S14（SHOULD FIX，6/10）

#### F262 [SHOULD FIX] 方向键按一个 UTF-16 单元移动，会落进代理对中间

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditorTraits.kt:75-79`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:371-372`、`EditorKeyPolicyTest.kt:71-75`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditorTraits.kt:78`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditorKeyPolicyTest.kt:71`

`EditorTraits.kt:78` 用 `start - 1`/`end + 1` 移动光标，在 emoji 旁会落到代理对的两半之间；`BaseInputConnection.setSelection` 不会对齐，下一次提交会拆开 emoji，之后 `deleteSurroundingTextInCodePoints` 拒绝处理这段畸形文本（见 `CodePoints`）。`EditorKeyPolicyTest.kt:71` 把移动固定为一个单元，且没有星位字符的测试。
建议让服务用 `CodePoints.lengthOfLast/First` 基于 `textBefore/AfterCursor(2)` 计算步长，或退回 `SendKey`，并加入含 👋 的测试。

- 可能的运行时影响：在 emoji 旁用方向键移动光标后再输入，会把 emoji 拆成损坏的半个代理字符。
- 提出者：GENERIC-1@S21（SHOULD FIX，6/10）

#### F263 [SHOULD FIX] `ksc` 每个样本新建会话，丢弃解码器的前瞻缓存

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:396-397`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:396-397`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:397`

Main.kt:397 为了隔离 reranker 状态，给每个样本新建 session，也就新建了 `PinyinDecoder`；其 `extensions` 前瞻缓存（`arrayOfNulls(nodeCount)`，由 `extensionsOf` 按节点只算一次，注释说每键重算代价太高）以及 lattice 和 beam 缓冲区都随之丢弃，每个样本重新分配和计算。
修复（保留样本隔离）：未指定模型时每个 worker 复用一个会话（`KeystrokeRun.type` 已发送 `Reset`/`Context`）；否则让会话在共享的解码器上接受每样本的 reranker。

- 提出者：PERFORMANCE@G5（SHOULD FIX，6/10）

#### F266 [SHOULD FIX] 候选操作菜单比负责关闭它的事件任务活得更久

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:78`、`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:126`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:78`

`BaseInputView.kt:78`：`handleEvents = false` 会取消事件收集器，这发生在 `onDetachedFromWindow`（输入视图因主题、旋转或偏好设置被替换，而 IME 窗口仍然可见）和切换到实体键盘时。这两种情况下窗口可见性都不变，非模态的 `ListPopupWindow` 仍然开着，之后的候选变化也不再关闭它，过期索引的问题（见候选长按菜单那条）便不再有时间上限。
修复：在该 setter 的 `else` 分支中调用 `candidateActionMenu?.dismiss()`。

- 可能的运行时影响：输入视图被替换后候选操作菜单仍留在屏幕上，此时选择删除或置顶可能作用于另一个词。
- 提出者：GENERIC-1@S32（SHOULD FIX，6/10）

#### F267 [SHOULD FIX] 滑动删除剪贴条目时位置为 -1 导致崩溃

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:157`、`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardAdapter.kt:134`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:157`

`ClipboardWindow.kt:157` 的 `onSwiped` 把 `bindingAdapterPosition` 直接传给 `PagingDataAdapter.getItem`。有待处理的分页更新（新剪贴内容到达、自动清理）或 holder 已被移除时，它的值是 -1，`getItem(-1)` 抛 IndexOutOfBoundsException，后面的 `?: return` 拦不住，输入法崩溃。
修复：`val pos = viewHolder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return`。

- 可能的运行时影响：剪贴板列表正在刷新时滑动删除条目，输入法可能崩溃。
- 提出者：GENERIC-1@S36（SHOULD FIX，6/10）

#### F268 [SHOULD FIX] FcitxTest 的事件在进入 UNLIMITED 通道之前仍可能被丢弃

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`Fcitx.kt:224-228`、`FcitxTest.kt:60`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Fcitx.kt:224-228`、`FcitxTest.kt:60`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:71`；另见 `app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:47`、`app/src/androidTest/java/org/fcitx/fcitx5/android/FcitxTest.kt:117`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:226`

FcitxTest.kt:47 的 KDoc 说不会丢事件，但源 flow 是 `extraBufferCapacity = 15` 加 `DROP_OLDEST`（Fcitx.kt:226），而收集器在主线程上恢复（:71）。主线程卡顿时（:117-119 自己承认会发生），排队超过 15 个事件就会丢掉最旧的，其中可能有 `CommitStringEvent`，测试便一直等到 30 s 超时。
修复：在 `Dispatchers.Default` 上收集；向 UNLIMITED 通道发送不需要主线程。

- 提出者：GENERIC-1@S49（SHOULD FIX，6/10）

#### F269 [SHOULD FIX] 每次进程启动后，第一个按键在 fcitx 线程上构建整个引擎

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:66-71`、`Engines.kt:505-506`、`Safetensors.kt:79-80`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:66-71`、`Engines.kt:505-506`、`Safetensors.kt:79-80`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:188`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:496`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:515`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:207`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:822`

没有预热：第一次 `onEvent`（EngineBridge.kt:188）运行 `Engines.session()`（Engines.kt:496、:515），它调用 `user()` 读取整个用户日志，并从文本重新解析所有导入的词典和词包（Engines.kt:207），还调用 `reranker()`（Engines.kt:822）做 4.5 MB 的复制。EngineEvalRunner 自己为此预热 10 次输入，而 IME 进程经常重启。
修复：在 `onStartInputView` 或 ReadyEvent 时投递一个任务，创建当前输入法的会话；并像 `Engines.built()` 缓存码表那样，以 `Additions.dictionaries` 为键缓存解析后的用户词典。

- 提出者：PERFORMANCE@G7（SHOULD FIX，6/10）

#### F270 [SHOULD FIX] replaceTableDict 的改名与删除路径没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:106`、`TableInputMethodFragment.kt:251`、`TableManager.kt:117-128`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:106`、`TableInputMethodFragment.kt:251`、`TableManager.kt:117-128`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:106`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:126`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:72`

替换 libime 的 `.dict` 时，代码会写出 `<name>.txt`、让 conf 指向它、再删除旧文件（TableManager.kt:126），被其他输入法码表占用的名称会被拒绝；目前只有命名 helper 有测试（TableFileNameTest）。
修复：按 PinyinDictManagerTest 的风格加 Robolectric 测试，覆盖一次替换、一次被拒绝的名称冲突（旧文件保持不变），以及 `importFiles` 失败时的清理（:72 起）。

- 提出者：TESTING@G7（SHOULD FIX，6/10）

#### F271 [SHOULD FIX] 长按已关闭的键向下滑动仍可能输入该字母

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:265`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:326`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:202`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:265`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:326`

覆盖为空时 swipeText 返回空字符串，BaseKeyboard.kt:265 返回 false（未消费），也没有其他监听者消费 Up；若手指仍在键内，KeyGestureRecognizer.onUp 执行点击，向下滑动就会输入该字母。
这违背「用户关闭长按时滑动无输出」的说明，结果取决于手指移动距离。
建议：checkY 通过且 chosen 为空字符串时消费 Up（返回 true）。

- 可能的运行时影响：对关闭了长按的字母键向下滑动时，可能意外输入该字母。
- 提出者：CORRECTNESS-1@S38（SHOULD FIX，6/10）

#### F272 [SHOULD FIX] 新底行移除了快捷短语和 Unicode 输入的唯一入口

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:74-82`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyDefPreset.kt:149`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:105-112`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:74`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:90`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyDefPreset.kt:150`

改为新底行（仿搜狗）后没有布局再实例化 QuickPhraseKey（KeyDefPreset.kt:150），QuickPhraseAction 和 UnicodeAction 无法在屏幕上触发，但设置中仍保留快捷短语编辑器；:90 的 quickphrase 变成一旦读取就抛 NPE 的 lazy 属性。
评审认为把键加回该行会违背底行的设计目标。建议：把入口放到别处（面板或长按符键），或删除编辑器、预设和这个死属性。

- 可能的运行时影响：软键盘上无法再触发快捷短语与 Unicode 输入，而设置中的快捷短语编辑器仍然存在。
- 提出者：CORRECTNESS-2@S38（SHOULD FIX，6/10）

#### F273 [SHOULD FIX] getSwipeDirs 把拖动方向当作滑动方向返回

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:53-55`、`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:70-72`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodListFragment.kt:46`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:55`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:53`

对不可删除的项，以及多选模式下的所有项，启用 enableOrder 的列表（InputMethodList、ListFragment、PinyinCustomPhrase）会得到 UP|DOWN 滑动标志；ItemTouchHelper 把从行上开始的竖向移动当作滑动，onSwiped 调用 removeItem，删除了这一分支本应保护的项。
建议：返回 0；GENERIC-1 还建议对 :53 加 NO_POSITION 保护。

- 可能的运行时影响：在输入法列表等可排序列表中竖向拖动受保护的条目，可能把它删除。
- 提出者：GENERIC-1@S40（SHOULD FIX，6/10）

#### F274 [SHOULD FIX] 搜索高亮用带 5 秒计时的全局单例传递导航参数

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SearchHighlight.kt:19-38`、`PaddingPreferenceFragment.kt:33`、`ThemeFragment.kt:97`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SearchHighlight.kt:19`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SearchHighlight.kt:53`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:97`

`SearchHighlight` 的 `title`/`at` 是进程级全局状态，按本地化标题匹配偏好（第 53 行），且只要有任何待处理高亮 `ThemeFragment.kt:97` 就会切换标签页；未找到标题的结果会保持待命，5 秒内打开的下一个页面可能误闪烁某个偏好或错误切换标签。
建议：在类型安全的 `SettingsRoute` 上增加 `highlight: String?`（偏好 key），并按 `Preference.key` 匹配。

- 可能的运行时影响：搜索结果未命中时，5 秒内打开的下一个设置页可能错误地闪烁某项设置或切换标签页。
- 提出者：STYLE@G8（SHOULD FIX，6/10）

#### F275 [SHOULD FIX] HttpClient 默认 HTTP/2，8 个线程共享一条连接

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 5/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:125`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:125`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:125`

CommonCrawl.kt:125 的 `HttpClient.newBuilder()` 没有设 `.version(...)`，默认优先 HTTP/2，JDK 客户端会把发往同一源站的所有请求放在一条 TCP 连接上。
若 data.commoncrawl.org 协商 h2（CloudFront 通常如此，该评审未核实），THREADS = 8 只得到一条连接的带宽，而要下载的是约 100 GB 的 60 MB 文件。
建议：设 `.version(HttpClient.Version.HTTP_1_1)` 并测量吞吐。

- 提出者：PERFORMANCE@G2（SHOULD FIX，5/10）

#### F276 [SHOULD FIX] 引擎持续失败时每次按键都重试加载并打印堆栈

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 5/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:559`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/native-lib.cpp:557`

engineFailed（native-lib.cpp:557）清除异常并把按键交给应用；EngineBridge.made 是 lazy(NONE)，Engines.pinyinData 也是 lazy 加载，失败后保持未初始化。因此引擎资源损坏或不可读时，每次按键都会重新执行 PinyinData.load 并通过 ExceptionDescribe 打印完整堆栈，没有退避，用户也看不到任何提示。
修复：在原生侧或 EngineBridge 中锁存第一次失败：只记录一次日志，之后报告「引擎不可用」。

- 可能的运行时影响：引擎资源损坏时每次按键都重新加载并打印完整堆栈，用户却得不到任何提示。
- 提出者：GENERIC-1@S25（SHOULD FIX，5/10）

#### F277 [SHOULD FIX] 模块改用包装层 project()，addon .conf 翻译可能失效

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 5/10 · 验证：✅ 已确认（`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:49`、`Fcitx5Macros.cmake:173`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:49`、`Fcitx5Macros.cmake:173`）
- 位置：`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:49`

原来的 add_subdirectory(fcitx5-chinese-addons) 会运行 submodule 自己的 project()；现在 PROJECT_SOURCE_DIR 变成 lib/fcitx5-chinese-addons/src/main/cpp，PROJECT_VERSION 变成包装层的 0.1.3。若 fcitx5_translate_desktop_file 的 PO_DIRECTORY 默认为 ${PROJECT_SOURCE_DIR}/po，generate-desktop-file 步骤（FcitxComponentPlugin installLibraryConfig）会让 msgfmt 指向不存在的目录，结果是构建失败，或发出的 chttrans.conf、punctuation.conf、fullwidth.conf 缺少 Name[zh_CN]/Name[zh_TW]。
评审者因 submodule 未检出而无法读该宏，置信度为 5/10。
修复：在 foreach 之前 set(PROJECT_SOURCE_DIR "${ADDONS_DIR}")，并把 PROJECT_VERSION 设为 addons 的版本，然后确认构建出的 chttrans.conf 含 Name[zh_CN]；libime 仍保持移除。

- 提出者：CORRECTNESS-1@S27（SHOULD FIX，5/10）

#### F278 [SHOULD FIX] 每次按住说话都先创建 VAD 会话再开麦

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 5/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:241`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:241`

record() 在构建并启动 AudioRecord 之前先创建 silero ONNX 会话（读取资源并初始化 ORT），因此按下后录音开始得更晚；PERFORMANCE@G6 引用 VoiceEngineTest 的说明：0.1 s 的前导音频就会改变首个音节的识别。
修复：先调用 startRecording()（1 s 的缓冲可吸收延迟）；更好的做法是与识别器一起保留一个 Vad，每次按住时调用 reset()。

- 可能的运行时影响：按下后录音起点延后，可能影响首个音节的识别结果。
- 提出者：PERFORMANCE@G6（SHOULD FIX，5/10）

#### F279 [SHOULD FIX] AudioRecord 只用浮点采样，没有 16 位回退

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 4/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:352`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:247`

AudioRecord 只以 ENCODING_PCM_FLOAT 创建；若设备的 VOICE_RECOGNITION 采集路径不支持浮点，录音器无法初始化，语音输入每次都以「麦克风无法使用」失败。
修复：失败时改用 ENCODING_PCM_16BIT 重试，并把每个窗口转换为浮点。

- 可能的运行时影响：在不支持浮点采集的设备上，语音输入每次都会失败。
- 提出者：CORRECTNESS-2@S28（SHOULD FIX，4/10）

#### F280 [SHOULD FIX] 屏蔽只作用于单一 token 序列，设备测试只覆盖句首词

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 4/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer.h:103`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer.h:103`

解码器只屏蔽 EncodeHotwords 为每个短语生成的那一串 token，而 offline-recognizer.h:103 的文档承诺结果不含任何屏蔽短语；这只有在 X-ASR 的 BPE 词表对每个汉字只有一种切分（例如没有分开的 ▁开 与 开）时才成立，评审者无法读取 tokens.txt 或 ssentencepiece 来核实。
theBlockInTheSearchTakesTheWordWhereverItIs 解码的 zh.wav 中「开放」位于句首，句中屏蔽从未被测试；VoiceEngine.recognize 返回重新搜索的 .text 时也不再复查。
修复：增加屏蔽词位于句中的设备测试，并让 recognize 用 VoiceBlocking 复查重搜结果，或屏蔽所有切分变体。

- 可能的运行时影响：若同一汉字存在多种切分，被屏蔽的词仍可能出现在语音识别结果中并被输入。
- 提出者：CORRECTNESS-1@S52（SHOULD FIX，4/10）

#### F281 [SHOULD FIX] 退格删除空的“”后，fcitx 可能仍认为左引号未闭合

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 4/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:328`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`、`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:750`

`pairs.backspace()` 丢弃记住的 ” 并删掉它，随后的光标报告与预测一致，因此 :754 的重置不会触发（它只在配对因意外的光标跳转而被遗忘时触发）。如果 fcitx 的标点 addon 在退格时不撤销自己的引号状态，下一次按引号键就会打出一个单独的 ”，这正是 :750-754 想解决的情况；评审者说明自己没有读过该 addon。
修复：比较 `pairs.backspace()` 前后的 `pairs.quoteOpen`，变为 false 时投递 `reset()`，然后重新告知上下文。

- 可能的运行时影响：删除一对空引号后再输入引号，可能打出方向错误的单个右引号。
- 提出者：CORRECTNESS-2@S33（SHOULD FIX，4/10）

#### F282 [SHOULD FIX] onStop 中启动的保存与重载会随 Fragment 销毁被取消

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 4/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseEditFragment.kt:159-170`、`QuickPhraseListFragment.kt:266-282`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseEditFragment.kt:159`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:261`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:281`

这是上游代码。`QuickPhraseEditFragment.saveConfig`（以及 `QuickPhraseListFragment.reloadQuickPhrase`，第 261 行）在 `onStop` 中于 Fragment 的 `lifecycleScope` 上启动；若紧接着 `onDestroy`（如出栈），`withContext` 返回时抛出。
于是 `setFragmentResult` 被跳过，列表不会标记为已修改、fcitx 不重新加载快捷短语；`nm.cancel(id)`（第 281 行）也被跳过，常驻通知不会消失。审查者未核实退出动画让 `onStop` 与 `onDestroy` 相隔多久（置信度 4）。
建议：在生命周期长于 Fragment 的作用域中执行，并在 `finally` 中取消通知。

- 可能的运行时影响：退出快捷短语页面时修改可能不会被 fcitx 重新加载，进度通知也可能一直残留。
- 提出者：CORRECTNESS-2@S42（SHOULD FIX，4/10）

#### F729 [SHOULD FIX] T9 模型对的关闭释放与重新读取没有测试

- 来源：第二轮（更新 07d2778..5881d31） · 共识：6/7 位 reviewer（4 SHOULD FIX, 2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:499-500`、`EnginesModelTest.kt:84-100`、`EnginesModelTest.kt:103`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:84`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:499-500`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:499`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:115`

关闭句子模型时新增的 `t9SentenceModel.drop()`/`t9RefiningModel.drop()`（Engines.kt:499-500）没有测试：`theSentenceModelsAreLoadedOnceForBothPinyinsUnlessTurnedOff`（EnginesModelTest.kt:84）只覆盖通用模型对，删掉这两行，或关后再开时 T9 不再重读，所有测试照样通过；TESTING 指出若删掉这两行，回退过的 T9 ModelFile 在关后再开时会保留旧的通用实例，而 `sentenceModel` 已加载新实例。
建议：扩展上述测试或 `theNineKeysReadTheirOwnModels…`（EnginesModelTest.kt:103），设置 `t9Model`/`t9Refining`，关后再开并在九键再次停顿后断言 `T9_SENTENCE_MODEL`、`T9_REFINING_MODEL` 被重新读取；STYLE 要求覆盖有、无 T9 文件两种情况，TESTING 还建议在 `withoutTheirOwn…`（:115）中九键之后再打 PINYIN 并断言没有多读，证明回退共享通用模型。
GENERIC-2 同时要求为 T9 自身文件损坏补用例（另见 D-17），CORRECTNESS-2 同时提到有 T9 文件时双拼未被检查（另见 D-18）。
严重度分歧：GENERIC-1、GENERIC-2、STYLE、TESTING 为 SHOULD FIX，CORRECTNESS-1、CORRECTNESS-2 为 NIT。

- 提出者：第二轮 CORRECTNESS-1（NIT，8/10）、第二轮 CORRECTNESS-2（NIT，8/10）、第二轮 GENERIC-1（SHOULD FIX，8/10）、第二轮 GENERIC-2（SHOULD FIX，9/10）、第二轮 STYLE（SHOULD FIX，8/10）、第二轮 TESTING（SHOULD FIX，8/10）

#### F730 [SHOULD FIX] 九键与全拼/双拼混用时堆上常驻两套句子模型

- 来源：第二轮（更新 07d2778..5881d31） · 共识：4/7 位 reviewer（4 SHOULD FIX）· 置信度 8/10 · 验证：✅ 已确认（`Engines.kt:128`、`Engines.kt:496-500`、`Safetensors.kt:80-81`）
- 当前状态（5881d31）：第二轮新增
- 用户决定：去掉九键单独的一对句子模型，九键与全拼、双拼共用通用模型。修复尚未开始。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:164`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:80`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Safetensors.kt:79-80`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:413`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:413-415`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:159`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:496`

改动前一套模型对服务三种拼音输入法；现在九键有自己的一对，而 `Safetensors.matrix` 把 int8 权重复制进堆上的 `ByteArray`（Safetensors.kt:79-80），每对约 30 MB。同时用过九键和 PINYIN 或 SHUANGPIN 的用户（CORRECTNESS-2、GENERIC-2：PINYIN 默认启用并排在首位，Fcitx.kt:413-415）两对都留在内存，约 61 MB，只有关掉句子模型开关时才 `drop()`（Engines.kt:496）。
GENERIC-2 与 PERFORMANCE 指出应用没有 `largeHeap`，GENERIC-2 另指出没有捕获 `OutOfMemoryError`，PERFORMANCE 另指出没有 `onTrimMemory`；代码注释（Engines.kt:159）把 100 MB 说成部分手机上应用可用内存的一半。
建议：另一布局的会话被创建或闲置时，丢弃闲置一方会话的精排 reranker 与其模型对（GENERIC-2：至少只留一个 25M 模型，T9 回退到通用文件时除外），或在低内存设备上测量峰值堆并记录；PERFORMANCE 另提出把 I8 权重保留为映射资源的 `ByteBuffer` 视图，让页面可由系统回收。
四个阶段都保留 T9 对这一目标，均为 SHOULD FIX；PERFORMANCE 注明约 61 MB 假设 T9 对与通用对结构相同，未经核实。

- 可能的运行时影响：同时使用九键和全拼或双拼的用户，进程堆上会多常驻约 30 MB 模型权重，在低内存手机上加大内存压力，可能导致 OutOfMemoryError。
- 提出者：第二轮 CORRECTNESS-2（SHOULD FIX，7/10）、第二轮 GENERIC-1（SHOULD FIX，7/10）、第二轮 GENERIC-2（SHOULD FIX，7/10）、第二轮 PERFORMANCE（SHOULD FIX，8/10）

#### F731 [SHOULD FIX] T9 模型对让每个 APK 增大约 30 MB，代价未测量

- 来源：第二轮（更新 07d2778..5881d31） · 共识：4/7 位 reviewer（2 SHOULD FIX, 2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:110-114`、`EngineDataPlugin.kt:110`、`README.md:19-20`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:111`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:212`、`README.md:19-20`

`.safetensors` 不压缩（`noCompress`，EngineDataPlugin.kt:212），新增的 T9 一对若与通用对同尺寸（README.md:19-20：4.5 MB + 25 MB），每个 APK 多出约 30 MB，从不用九键的用户也要承担；收益只是九键上 top-1 提高 0.5 到 1.3 个百分点。各阶段都没有下载发布核实尺寸。
CORRECTNESS-1/2 建议测量「T9 小模型 + 通用大模型」，若保留大部分收益就只发布 T9 小模型，并在提交说明或 README 中写明 APK 体积变化（CORRECTNESS-1 还提出让一个模型接收当前输入法信息）；GENERIC-2 建议加一个 Gradle 属性让构建跳过 T9 对；PERFORMANCE 认为移出 APK 会违背既定目标和离线承诺，只需在发布说明中写明新体积。CORRECTNESS-2 另指出让九键改回通用模型只能重新构建，没有设置项。
严重度分歧：CORRECTNESS-1、CORRECTNESS-2 为 SHOULD FIX，GENERIC-2、PERFORMANCE 为 NIT。

- 提出者：第二轮 CORRECTNESS-1（SHOULD FIX，6/10）、第二轮 CORRECTNESS-2（SHOULD FIX，6/10）、第二轮 GENERIC-2（NIT，7/10）、第二轮 PERFORMANCE（NIT，7/10）

#### F732 [SHOULD FIX] 发布门禁不评测九键及其自有模型对

- 来源：第二轮（更新 07d2778..5881d31） · 共识：3/7 位 reviewer（2 SHOULD FIX, 1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-eval/release-report.sh:45-46`、`lib/ime-dict-tool/engine-data.sh:134-135`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-eval/release-report.sh:46`；另见 `lib/ime-eval/release-report.sh:45`、`lib/ime-dict-tool/engine-data.sh:135`

`release-report.sh` 只用通用模型对评测全拼集合（`--rerank`/`--refine` 指向 `sentence-model{,-large}.safetensors`，release-report.sh:45-46），`engine-data.sh`（:135）也一样。按提交说明，T9 对是在解码器读音列表上训练的，与当前 pinyin.data、语言模型和词表耦合，之后的 `engine-data-*` 或 `words-*` 发布可能降低九键准确率而门禁照样通过；提交中的 T9 数字是在脚本之外测得的。
建议：在 release-report.sh 中加入九键集合（ime-eval 已能通过 `T9Set`/`--scheme t9-strict` 在九键上输入），用 `$MODELS` 中的 `sentence-model-t9*` 文件运行；CORRECTNESS-1 还建议在文档中注明语言模型变化后须重新检查 T9 对，TESTING 建议先加一个只报告、不设门槛的九键集合。
CORRECTNESS-2 指出基础版本也没有九键门禁，但新的耦合使缺口更大。严重度分歧：CORRECTNESS-1、CORRECTNESS-2 为 SHOULD FIX，TESTING 为 NIT。

- 提出者：第二轮 CORRECTNESS-1（SHOULD FIX，7/10）、第二轮 CORRECTNESS-2（SHOULD FIX，7/10）、第二轮 TESTING（NIT，7/10）

### 4.3 NIT（小问题）

#### F283 [NIT] StringEscapeTest 包名与目录不一致

- 来源：第一轮（整仓 @07d2778） · 共识：6/12 位 reviewer（6 NIT）· 置信度 9/10 · 验证：✅ 已确认（`T/core/StringEscapeTest.kt:6`、`config/detekt/baseline-lib-ime-core.xml:5`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:6`；另见 `config/detekt/baseline-lib-ime-core.xml:5`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:28`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt`

该文件声明 `package org.fcitx.fcitx5.android`，却位于 `core/` 下，因此 `config/detekt/baseline-lib-ime-core.xml:5` 带有一条 InvalidPackageDeclaration 基线条目。
建议：改为 `org.fcitx.fcitx5.android.core`，删除该基线条目及 `FcitxUtils` 导入。CORRECTNESS-2@S15 与 TESTING@G3 在同一条发现中还指出第 28 行 assertEquals 参数颠倒（见 G3a-47）。

- 跨模块组合并：G3a-46、G1-28（StringEscapeTest 包声明错误被写进基线而非修复）
- 提出者：CORRECTNESS-1@S15（NIT，9/10）、CORRECTNESS-2@S15（NIT，9/10）、GENERIC-1@S15（NIT，9/10）、TESTING@G3（NIT，9/10）、CORRECTNESS-1@S3（NIT，9/10）、CORRECTNESS-2@S3（NIT，9/10）

#### F284 [NIT] 用户可见文案仍使用上游名称 fcitx5-android

- 来源：第一轮（整仓 @07d2778） · 共识：5/14 位 reviewer（5 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/res/values-zh-rTW/strings.xml:236`、`values/strings.xml:236`、`values-zh-rCN/strings.xml:236`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/res/values-zh-rTW/strings.xml:236`、`values/strings.xml:236`、`values-zh-rCN/strings.xml:236`）
- 位置：`app/src/main/res/values/strings.xml:236`；另见 `app/src/main/res/values-zh-rTW/strings.xml:236`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/AdvancedSettingsFragment.kt:56`、`app/src/main/res/values-zh-rCN/strings.xml:236`、`app/src/main/res/values-ja/strings.xml:223`、`app/src/main/res/values-ru/strings.xml:230`、`app/src/main/res/values-ru/strings.xml:54`

`exception_user_data_filename`（所选文件不是 .zip 时由 AdvancedSettingsFragment.kt:56 显示）在 values、zh-rCN:236、zh-rTW:236、ja:223、ru:230 中仍写着“fcitx5-android 用户数据”；ru 的 `about`（values-ru:54）为“О fcitx5 для Android”。
应用已更名为 Youmo IME（`io.github.sraw.youmo`），备份文件现名为 `youmo_<time>.zip`，README 也说明应用独立并已更名。
修复：改用应用自己的名称或“Youmo IME 备份”，ru `about` 改为“О приложении”；CORRECTNESS-2@S46 指出所有语言都需修改。

- 提出者：CORRECTNESS-2@S46（NIT，8/10）、CORRECTNESS-1@S48（NIT，9/10）、CORRECTNESS-2@S48（NIT，9/10）、GENERIC-1@S48（NIT，9/10）、STYLE@G9（NIT，8/10）

#### F285 [NIT] gradle.properties 中堆大小注释与实际值矛盾

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 NIT）· 置信度 10/10 · 验证：✅ 已确认（`gradle.properties:10`、`gradle.properties:12`、`gradle.properties:34-36`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`gradle.properties:12`；另见 `gradle.properties:10`、`gradle.properties:36`、`.github/workflows/unit_test.yml:9`、`.github/workflows/pull_request.yml:38`、`app/proguard-rules.pro:26`、`app/build.gradle.kts:62`

第 12 行注释称堆「从 2g 提高」以容纳编译器，但第 10 行实际是更低的 `-Xmx1536m`，提交 d546ba0a 是把它从 3g 降下来的。
CORRECTNESS-1@S1、GENERIC-1@S1、STYLE@G1 在同一条发现中还指出第 36 行 `org.gradle.daemon=false` 是单机设置（见 G1-08）；CORRECTNESS-2@S1 把它与其他过期注释一起报告：unit_test.yml:9 的 `publish`（见 G1-27）、pull_request.yml:38 的「Install Android NDK」（见 G1-52）、proguard-rules.pro:26 对已生效行写的「Uncomment this」、app/build.gradle.kts:62 的 ~240 MB（见 G1-26）。
建议：修正注释，使其与值一致。GENERIC-1@S1 注明未验证 R8 加进程内 Kotlin 编译能否在 1.5 GB 内完成 release 构建。

- 提出者：CORRECTNESS-1@S1（NIT，8/10）、CORRECTNESS-2@S1（NIT，9/10）、GENERIC-1@S1（NIT，9/10）、STYLE@G1（NIT，10/10）

#### F286 [NIT] `APPLICATION_ID_ROOT` 的注释与字段实际含义相反

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/build.gradle.kts:20-21`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:8`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:11-12`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/build.gradle.kts:20-21`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:8`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:11-12`）
- 位置：`app/build.gradle.kts:20`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:8`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:25`、`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:106`、`app/build.gradle.kts:62`

注释称该字段是「这个 id 以前的值」，实际它保存的是去掉 build type 后缀（如 `.debug`）的当前 id，供 UserDataOrigin.kt 作为 `root` 使用；旧 id 是 `LEGACY_APPLICATION_ID_ROOT`（UserDataOrigin.kt:8）。
CORRECTNESS-2@S1 指出，信任注释、把字段改成旧 id 的维护者会破坏备份导入（UserDataManager.kt:106）；STYLE@G1 在同一条中还指出第 62 行的 ~240 MB 与 README 的 ~180 MB 不一致（见 G1-26）。
建议：改写为「去掉 build type 后缀的 applicationId（供 UserDataOrigin 使用）」之类的措辞。

- 提出者：CORRECTNESS-1@S1（NIT，9/10）、CORRECTNESS-2@S1（NIT，9/10）、GENERIC-1@S1（NIT，8/10）、STYLE@G1（NIT，8/10）

#### F287 [NIT] VoiceDataPlugin 隐式依赖 engine-data 插件的应用顺序

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:83`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:98`、`app/build.gradle.kts:7-8`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:83`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:98`、`app/build.gradle.kts:7-8`）
- 位置：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:83`；另见 `build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:98`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:104`、`app/build.gradle.kts:7-8`

`tasks.named("downloadEngineWords")` 只有在 `engine-data` 先于 `voice-data` 应用时才能工作；app/build.gradle.kts 目前的顺序恰好满足，但没有任何强制，调换两行会在配置期抛出不说明原因的 `UnknownTaskException`。
STYLE@G1 还指出第 98 行的 `"speech"` 重复了 app 脚本里的 dimension 名；STYLE@G1 与 CORRECTNESS-1@S2 在同一条中顺带提到 `CopyVoiceModels`（第 104 行）缺少缓存注解（见 G1-41）。
建议：先调用 `target.pluginManager.apply(EngineDataPlugin::class.java)`，或用 `pluginManager.withPlugin(...)`、带清晰信息的 `require` 包住查找；STYLE@G1 建议像 `COMPILE_TASK`、`MODEL_TASK` 一样暴露 `WORDS_TASK` 常量。

- 提出者：STYLE@G1（NIT，9/10）、CORRECTNESS-1@S2（NIT，8/10）、CORRECTNESS-2@S2（NIT，8/10）、GENERIC-1@S2（NIT，8/10）

#### F288 [NIT] NewWordsTest 注释与测试数据及断言矛盾

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:41`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:43`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:51-52`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:41`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:43`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:51`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:40`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:42`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:50`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:58`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:39`、`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:119`

NewWordsTest.kt:41（CORRECTNESS-2@S6 记为 :40）是自我纠正的草稿注释（「… no: 子 is in every 搭子」）。
:43（:42）把左右说反：搭子 之前有 5 个不同字（饭 出现两次）、之后有 6 个（含 搭子， 处的边界），注释却写成 6 和 5。
:51（:50）说 饭搭子 出现三次，实际两次，下一行也断言 2；所列邻字同样不对（CORRECTNESS-2@S6：前接 个/有 而不是 个/有/没；TESTING@G2：来 跟在 旅游搭子 而非 饭搭子 后）。
建议：改写注释使其与数据和断言一致。GENERIC-1@S6 另指出 :58 有多余空行；CORRECTNESS-1@S6 在同一发现中还列出 PageCleanerTest.kt:39 与 WebTextTest.kt:119 的错误数字（见 G2-35、G2-48）。

- 提出者：CORRECTNESS-1@S6（NIT，9/10）、CORRECTNESS-2@S6（NIT，9/10）、GENERIC-1@S6（NIT，9/10）、TESTING@G2（NIT，9/10）

#### F289 [NIT] Vocabulary 的 KDoc 错放在 WordLayers 上方

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:110`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:141`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:110`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:141`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:110`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:139`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableOptions.kt:29`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/Session.kt:132`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:138`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:53`

“Word ids to text…”文档属于 `Vocabulary`（第 139 行），却紧贴在 `WordLayers` 自身 KDoc 上方，成为悬空注释，`Vocabulary` 没有文档。STYLE@G3 的同一条发现还指出 TableOptions.kt:29、:32、Session.kt:132、TableSession.kt:138 以已删除的 晚风、电报码 表为例，DataAge.kt:8 与 Reranker.kt:53 指向仓库中不存在的 dev/*.md。
建议：把该 KDoc 移到 `class Vocabulary`。

- 提出者：CORRECTNESS-1@S7（NIT，7/10）、CORRECTNESS-2@S7（NIT，9/10）、GENERIC-1@S7（NIT，9/10）、STYLE@G3（NIT，9/10）

#### F290 [NIT] SentenceRefiner.offline()是已删云端精排遗留的死代码

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Reranker.kt:31`、`PinyinSessionRefineTest.kt:118`、`Reranker.kt:30`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:31`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:645`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:644`

没有任何生产 refiner 覆盖 `offline()`（Reranker.kt:31），只有 PinyinSessionRefineTest 覆盖；`offlineRefiner`/`refiner()`（PinyinSession.kt:644-647）及「不会发送到设备外」「密码的句子留在设备上」等注释描述的是 commit 69f2562a 已删除的离设备路径，读者可能误把它当成隐私关卡，而真正的关卡是 `textBefore()` 中的 `learning`。
建议：删除该钩子（STYLE 还建议一并删除测试 `aPasswordIsRefinedOnlyByWhatStaysOnTheDevice`），或改写注释，避免把文本离开设备的路径再引回来，与 README「不联网」承诺一致。

- 提出者：CORRECTNESS-1@S11（NIT，9/10）、CORRECTNESS-2@S11（NIT，9/10）、GENERIC-1@S11（NIT，8/10）、STYLE@G3（NIT，8/10）

#### F291 [NIT] 拆分测试后遗留的未使用导入

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinSessionRefineTest.kt:7-34`、`PinyinSessionTest.kt:16`、`PinyinSessionHabitTest.kt:20`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinSessionRefineTest.kt:7-34`、`PinyinSessionTest.kt:16`、`PinyinSessionHabitTest.kt:20`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:7`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:8`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:16`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:30`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionHabitTest.kt:20`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:6`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:28`

PinyinSessionRefineTest 从 PinyinSessionTest 复制的导入中有 18 个未使用（NO_WORD、PinyinData、PinyinDataBuilder、Fuzzy、LayerPrior、ShuangpinScheme、ShuangpinSegmenter、Syllables、Backspace、CommitRaw、Forget、Pick、PreviousPage、Select、UserModel、inTrie、assertThrows、ByteBuffer），其中 `inTrie` 无故把它与 UserModelTest 耦合；PinyinSessionTest.kt:16、:30（SentenceRefiner、assertNotEquals）和 PinyinSessionHabitTest.kt:20（assertFalse）同样未使用，detekt 基线中没有它们的条目，也不会报。
GENERIC-1 另指出 PinyinSessionTest.kt:10-11 与 RefineTest:10-11 的导入顺序有误；STYLE 在同一条中还提到 StringEscapeTest.kt:6 包名与所在目录 `core/` 不符、第 28 行 `assertEquals` 的期望值与实际值颠倒。
建议：删除这些未使用的导入。

- 提出者：CORRECTNESS-1@S18（NIT，9/10）、CORRECTNESS-2@S18（NIT，9/10）、GENERIC-1@S18（NIT，9/10）、STYLE@G3（NIT，9/10）

#### F292 [NIT] WordPack 及其测试中有不可见的 BOM 字面量

- 来源：第一轮（整仓 @07d2778） · 共识：4/9 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordPack.kt:47`、`WordLists.kt:128`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordPack.kt:47`、`WordLists.kt:128`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordPack.kt:47`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:128`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordPackTest.kt:30`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordPackTest.kt:35`

`WordPack.kt:47` 的 `removePrefix(...)` 参数里直接嵌入了 U+FEFF 字符（字节 EF BB BF），看上去像空字符串，可能被编辑器或格式化工具悄悄删掉；`WordLists.kt:128`（以及 `WordLists.line`、`ImportedDictionaries`）用的是 `\uFEFF` 转义。
`WordPackTest.kt:30` 与 `:35` 的字符串字面量里也有同样的原始 BOM（CORRECTNESS-1@S20 提出，STYLE@G4 也提到）。
建议统一改为 `\uFEFF` 转义，让读者能看出这是 BOM 用例。

- 提出者：CORRECTNESS-2@S13（NIT，9/10）、GENERIC-1@S13（NIT，9/10）、STYLE@G4（NIT，9/10）、CORRECTNESS-1@S20（NIT，9/10）

#### F293 [NIT] PredictRun 的数字格式依赖默认 locale

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictRun.kt:45`、`Report.kt:55`、`KeystrokeRun.kt:80`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictRun.kt:45`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictRun.kt:47`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictRun.kt:70`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/EvalSet.kt:50`

PredictRun.kt:45、:47 的 `"%.2f".format`、`"%.1f%%".format` 使用 JVM 默认 locale，在 de、fr、ru 等主机上输出逗号小数；模块内 Report、KeystrokeRun、TableRun、Main 都传 `Locale.ROOT`。
修复：改用 `Locale.ROOT`。CORRECTNESS-1@S23 另指出 0 个样本时输出 NaN；CORRECTNESS-2@S23 另指出 `parse`（此处 :70 与 EvalSet.kt:50）先过滤行再 `mapIndexed`，报错中的行号跳过了空行和注释行。

- 提出者：CORRECTNESS-1@S23（NIT，9/10）、CORRECTNESS-2@S23（NIT，9/10）、GENERIC-1@S23（NIT，9/10）、STYLE@G5（NIT，9/10）

#### F294 [NIT] make-new-words-set.py 中无用的 READINGS 条目

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/tools/make-new-words-set.py:138`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/tools/make-new-words-set.py:138`；另见 `lib/ime-eval/tools/make-new-words-set.py:145`、`lib/ime-eval/tools/make-new-words-set.py:198`

`READINGS` 中的「重阳」（:138）和「阳了」（:145）不在 `WORDS` 中，永远不会被用到。
CORRECTNESS-1@S24 与 GENERIC-1@S24 另指出 :198 写到 stderr 的计数 `len(seen)` 包含了被跳过的非汉字词（拴Q、city不city），数量偏大。
修复：删除这两条（或补上原本想加的词），并只统计实际输出的样本。

- 提出者：STYLE@G5（NIT，9/10）、CORRECTNESS-1@S24（NIT，8/10）、CORRECTNESS-2@S24（NIT，9/10）、GENERIC-1@S24（NIT，9/10）

#### F295 [NIT] utf8.h 被引入却未使用，utf8FromJString 手写了编码分支

- 来源：第一轮（整仓 @07d2778） · 共识：4/6 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:29`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/native-lib.cpp:29`；另见 `app/src/main/cpp/native-lib.cpp:505`、`app/src/main/cpp/native-lib.cpp:508`、`app/src/main/cpp/native-lib.cpp:525`

native-lib.cpp:29 引入了 <fcitx-utils/utf8.h>，但文件中没有任何 fcitx::utf8:: 调用；utf8FromJString（508-541 行）的四个编码分支重复了 fcitx::utf8::UCS4ToUTF8。代理对解码仍然需要，编码部分可改为 out += fcitx::utf8::UCS4ToUTF8(c)，删去约 15-20 行手写位运算，或者直接去掉该 include。
GENERIC-1@S25 另指出 505 行的注释夸大了问题：据其所知 ART 的 GetStringUTFChars 对合法代理对已输出 4 字节 UTF-8，该函数真正额外做的是把孤立代理转为 U+FFFD，注释应如实说明。STYLE@G6 注明 submodule 未检出，假定该辅助函数在固定提交中存在。

- 子论断 F295.c 无法验证：「utf8.h 未被使用，而 utf8FromJString 重新实现了其中的编码器；第 505 行的注释夸大了它所解决的问题。」→ 未使用的 include 和重复的编码器这两点属实。第 505 行的注释是否夸大了问题，取决于 ART 的 GetStringUTFChars 如何编码 U+FFFF 之后的字符，而这部分代码不在仓库中。
- 提出者：CORRECTNESS-1@S25（NIT，9/10）、CORRECTNESS-2@S25（NIT，9/10）、GENERIC-1@S25（NIT，6/10）、STYLE@G6（NIT，6/10）

#### F296 [NIT] zh-rTW 繁体文件中混有简体字和大陆用语

- 来源：第一轮（整仓 @07d2778） · 共识：4/5 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/res/values-zh-rTW/strings.xml:21`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/res/values-zh-rTW/strings.xml:21`；另见 `app/src/main/res/values-zh-rTW/strings.xml:36`、`app/src/main/res/values-zh-rTW/strings.xml:40`、`app/src/main/res/values-zh-rTW/strings.xml:42`、`app/src/main/res/values-zh-rTW/strings.xml:163`、`app/src/main/res/values-zh-rTW/strings.xml:180`、`app/src/main/res/values-zh-rTW/strings.xml:281`、`app/src/main/res/values-zh-rTW/strings.xml:303`、`app/src/main/res/values-zh-rTW/strings.xml:363`、`app/src/main/res/values-zh-rTW/strings.xml:364`、`app/src/main/res/values-zh-rTW/strings.xml:380`、`app/src/main/res/values-zh-rTW/strings.xml:381`、`app/src/main/res/values-zh-rTW/strings.xml:236`、`app/src/main/res/values-zh-rTW/strings.xml:362`、`app/src/main/res/values-zh-rTW/strings.xml:384`、`app/src/main/res/values-zh-rTW/strings.xml:395`、`app/src/main/res/values-zh-rTW/strings.xml:396`、`app/src/main/res/values-zh-rTW/strings.xml:570`

values-zh-rTW/strings.xml 中有简体字：按键振动（21）、词库（36、40、42）、横（163）、删除…吗（180）、用户（281）；其中 36、42 行是 MainActivity 打开词库文件时显示的对话框。
还有大陆用语：默認（303）、添加（363）、屏蔽（364，同一界面其他字符串用 封鎖）、剪貼板（380，其他处用 剪貼簿）、自定義（381，其他处用 自訂）；CORRECTNESS-1@S46 还指出第 236 行仍写着“fcitx5-android 使用者資料”。
修复：改为 按鍵振動、詞庫、刪除…嗎 等繁体写法，每个概念统一使用一个繁体用语。

- 提出者：CORRECTNESS-1@S46（NIT，9/10）、CORRECTNESS-2@S46（NIT，9/10）、GENERIC-1@S46（NIT，9/10）、STYLE@G9（NIT，9/10）

#### F297 [NIT] 生成的启动图标含浮点残留和未用的 xmlns:aapt

- 来源：第一轮（整仓 @07d2778） · 共识：4/5 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/res/drawable/ic_launcher_foreground.xml:32`、`make_icon.py:22`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/res/drawable/ic_launcher_foreground.xml:32`；另见 `app/src/main/res/drawable/ic_launcher_foreground.xml:4`、`app/src/main/res/drawable/ic_launcher_foreground.xml:33`、`app/src/main/res/drawable/ic_launcher_foreground_monochrome.xml:4`

`ic_launcher_foreground.xml` 的路径数据含 `M53.400000000000006,52`，来自 `app/launcher-icon/make_icon.py` 直接输出原始浮点数；渲染结果正确。
`ic_launcher_foreground.xml` 和 `ic_launcher_foreground_monochrome.xml` 还在第 4 行声明了未使用的 `xmlns:aapt`（STYLE@G9 未提此点）。
修复：在生成器中格式化坐标（如 `f"{v:.4g}"` 或 `f'{x:g}'`），只在有渐变时声明 `aapt`，然后重新生成。

- 提出者：CORRECTNESS-1@S47（NIT，9/10）、CORRECTNESS-2@S47（NIT，9/10）、GENERIC-1@S47（NIT，9/10）、STYLE@G9（NIT，9/10）

#### F298 [NIT] 计数字符串未使用 plurals，单数时显示“1 words”

- 来源：第一轮（整仓 @07d2778） · 共识：4/11 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`values/strings.xml:394`、`UserWordListFragment.kt:222`、`PinyinDictionaryFragment.kt:256`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`values/strings.xml:394`、`UserWordListFragment.kt:222`、`PinyinDictionaryFragment.kt:256`）
- 位置：`app/src/main/res/values/strings.xml:405`；另见 `app/src/main/res/values/strings.xml:394`、`app/src/main/res/values/strings.xml:400`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:252-254`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:223`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:256`、`app/src/main/res/values/strings.xml:405-407`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:97`

`confirm_delete_words`、`confirm_forget_words`、`confirm_unblock_words`（values/strings.xml:405-407）和 `word_list_count`（:394）是普通 `%1$d` 字符串，UserWordListFragment.kt 以 `words.size` 调用，选中一个词时显示“Delete 1 words?”；CORRECTNESS-1@S48 还列出 `import_words_done`（:400）。
`word_count` 和 `word_typed_times` 已使用 `<plurals>`。
修复：同样改为 `<plurals>`，中文只需 `other`。

- 提出者：CORRECTNESS-1@S48（NIT，9/10）、CORRECTNESS-2@S48（NIT，9/10）、GENERIC-1@S48（NIT，9/10）、STYLE@G9（NIT，9/10）

#### F299 [NIT] zh-rCN 无障碍标签未说明操作对象（隐藏、向左移动）

- 来源：第一轮（整仓 @07d2778） · 共识：4/17 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`values-zh-rCN/strings.xml:298`、`values-zh-rTW/strings.xml:296`、`TextEditingUi.kt:62`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`values-zh-rCN/strings.xml:298`、`values-zh-rTW/strings.xml:296`、`TextEditingUi.kt:62`）
- 位置：`app/src/main/res/values-zh-rCN/strings.xml:288`；另见 `app/src/main/res/values-zh-rCN/strings.xml:298`、`app/src/main/java/org/fcitx/fcitx5/android/input/editing/TextEditingUi.kt:62`、`app/src/main/res/values-zh-rTW/strings.xml:296`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:387`、`app/src/main/res/values/strings.xml:319`

zh-rCN 中 `move_cursor_left`（:298，TextEditingUi.kt:62 文本编辑面板左键的 TalkBack 标签）为“向左移动”，缺少“光标”，而同类标签都是“向右/上/下移动光标”；`hide_candidates_list`（:288，KawaiiBarComponent.kt:387 展开按钮的 contentDescription）只有“隐藏”，en 和 zh-TW 都写明隐藏候选词列表。
屏幕阅读器用户得到含糊的标签；STYLE@G9 还指出 values/strings.xml:319 的注释要求标签唯一且具描述性，因为 UI 自动化按标签查找控件。
修复：改为“向左移动光标”和“隐藏候选词列表”；CORRECTNESS-1@S48 只报告了 `move_cursor_left`。

- 提出者：CORRECTNESS-1@S48（NIT，9/10）、CORRECTNESS-2@S48（NIT，7/10）、GENERIC-1@S48（NIT，8/10）、STYLE@G9（NIT，8/10）

#### F300 [NIT] 删除导入码表时遗留 .told 标记

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:943`、`TableBasedInputMethod.kt:61`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:943`、`TableBasedInputMethod.kt:61`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:930`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:618`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableBasedInputMethod.kt:61`

`tellPinyinOnce`（:618）写入 `tables/<im>.user.told`，但 `addedTableFiles` 只列 `.table` 与 `.user`，`TableBasedInputMethod.kt:61` 因而留下该标记，违反 KDoc“Gone with the table, lest another of its name find them”；因同名新表的日志从空开始，目前无害。
建议：把 `"$USER_TABLES/$im.user$TOLD"` 加入该列表。

- 跨模块组合并：G3a-36、G7-81（删除码表时残留 .user.told 标记（及码表配置））
- 提出者：CORRECTNESS-1@S8（NIT，8/10）、GENERIC-1@S8（NIT，8/10）、CORRECTNESS-1@S32（NIT，7/10）、CORRECTNESS-2@S32（NIT，7/10）

#### F301 [NIT] 每次显示键盘都同步查询应用语言（API 33+ 上一次 binder 调用）

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:712`、`FcitxInputMethodService.kt:466`、`Locales.kt:23-24`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:712`、`FcitxInputMethodService.kt:466`、`Locales.kt:23-24`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:712`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:24`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Locales.kt:22`

`onStartInputView` 每次都运行 `pickedLocalesChanged()`/`Locales.picked`（FcitxInputMethodService.kt:712），在 API 33 及以上是一次同步的 `LocaleManager.applicationLocales` binder 调用，只为发现很少发生的变化。
修复：只在 `onConfigurationChanged` 报告语言变化时，或 `AppLanguage` 设置语言时重新检查；GENERIC-1@S33 认为开销很小，可用语言变化信号代替轮询。

- 跨模块组合并：G7-80、G8b-44（`picked()` 每次显示键盘都发起一次 binder 调用）
- 提出者：GENERIC-1@S33（NIT，7/10）、PERFORMANCE@G7（NIT，8/10）、CORRECTNESS-1@S45（NIT，7/10）、PERFORMANCE@G8（NIT，6/10）

#### F302 [NIT] EditingSession 把预编辑文本写进调试日志

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:191`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Timber.kt:31`、`LogActivity.kt:103`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:191`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Timber.kt:31`、`LogActivity.kt:103`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:191`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/Timber.kt:36`

`EditingSession.kt:191` 的 `CoreLog.d` 在挂有日志 sink 时（debug 构建，或开启详细日志的 release 构建）记录用户的组合文本，即正在输入的拼音；应用的日志页面可以导出 logcat。
建议只记录长度与位置，与 PRIVACY.md 一致。

- 跨模块组合并：G4-36、G8b-43（CoreLog 日志 sink 会把正在输入的文本写入 logcat）
- 提出者：GENERIC-1@S14（NIT，6/10）、PERFORMANCE@G4（NIT，6/10）、CORRECTNESS-1@S45（NIT，6/10）、CORRECTNESS-2@S45（NIT，7/10）

#### F303 [NIT] 测试报告上传遗漏 `lib/ime-dict-tool`

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`.github/workflows/unit_test.yml:123-130`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`.github/workflows/unit_test.yml:124`；另见 `.github/workflows/unit_test.yml:118`、`.github/workflows/unit_test.yml:123`、`.github/workflows/unit_test.yml:80`、`.github/workflows/unit_test.yml:9`

unit_test.yml 第 80 行运行 `:lib:ime-dict-tool:test`，但上传的产物路径不含 `lib/ime-dict-tool/build/reports/tests/` 和 `test-results/`，那里失败时只有注解，没有 HTML 报告。
STYLE@G1 还指出各 lib 模块的 detekt 报告也没有上传，并在同一条中提到第 9 行引用了不存在的 `publish` 工作流（见 G1-27）。
建议：把这两个路径（以及缺失的报告）加入上传列表。

- 提出者：TESTING@G1（NIT，9/10）、CORRECTNESS-2@S1（NIT，9/10）、STYLE@G1（NIT，9/10）

#### F304 [NIT] 注释引用仓库中不存在的 dev/ISSUES.md 等文件

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`config/detekt/detekt.yml:48`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`config/detekt/detekt.yml:48`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110`）
- 位置：`config/detekt/detekt.yml:48`；另见 `codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenKeyMapping.kt:225`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:49`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:54`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:27`、`config/detekt/detekt.yml:8`、`config/detekt/detekt.yml:20`、`config/detekt/detekt.yml:44`

detekt.yml 第 48 行称 TODO/FIXME 记录在 `dev/ISSUES.md`，但仓库不跟踪 `dev/` 目录，贡献者看不到 TODO（如 codegen 的 GenKeyMapping.kt:225）在哪里跟踪。
STYLE@G1 还指出 EngineDataPlugin.kt:49、:54 和 VoiceDataPlugin.kt:27 引用的 `dev/TRAINING-PLAN.md` 同样不在仓库中，detekt.yml 第 8、20、44 行的硬编码计数（85、76、135）会逐渐过时。
建议：改为指向 GitHub issues，或改写、删除该引用。

- 提出者：CORRECTNESS-1@S3（NIT，9/10）、CORRECTNESS-2@S3（NIT，9/10）、STYLE@G1（NIT，9/10）

#### F305 [NIT] engine-data.sh 在 cd 到 $WORK 后相对路径失效

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/engine-data.sh:45-50`、`lib/ime-dict-tool/engine-data.sh:84`、`lib/ime-dict-tool/engine-data.sh:103`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/engine-data.sh:45`；另见 `lib/ime-dict-tool/engine-data.sh:47`、`lib/ime-dict-tool/engine-data.sh:50`、`lib/ime-dict-tool/engine-data.sh:84`

engine-data.sh 在 :47 执行 `cd "$WORK"`，但头部只要求 LEXICON 与 WORDS 用绝对路径；相对的 REMOVE、READINGS（CORRECTNESS-2@S4 与 GENERIC-1@S4 还列出 WORDS、TOOL、EVAL、SETS）会在之后的步骤中找不到文件。
CORRECTNESS-2@S4 指出 `-f "$WORDS"` 检查在 cd 之前，而 :50 的 `sha256sum "$WORDS"` 在之后，失败时 params 记录空哈希，之后用正确的绝对路径重跑反而被拒；CORRECTNESS-1@S4 与 CORRECTNESS-2@S4 还指出 :84 未加引号、会被分词的 `$CORRECT` 遇到含空格路径会断开。
建议：在 cd 前用 `realpath` 把各路径转为绝对路径（CORRECTNESS-1@S4 也接受在文档中注明须用绝对路径）；GENERIC-1@S4 另建议设置 EVAL 时加 `: "${SETS:?}"`，否则未设 SETS 要到数小时后的 report 步骤才失败。

- 提出者：CORRECTNESS-1@S4（NIT，8/10）、CORRECTNESS-2@S4（NIT，8/10）、GENERIC-1@S4（NIT，9/10）

#### F306 [NIT] lexicon/README.md 文件表缺少 misreadings.tsv

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lexicon/README.md:10-16`、`lexicon/misreadings.tsv:2-4`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lexicon/README.md:13`；另见 `lexicon/README.md:15`、`lexicon/README.md:10`、`lexicon/misreadings.tsv:3`、`lexicon/misreadings.tsv:2-4`

misreadings.tsv 头部（:2-4）让读者去 lexicon/README.md 查看误读词读音的显示方式，但 README 的文件表只列 add.tsv、reject.tsv、remove.tsv、readings.tsv 和 latin.words，从未提及该文件及其格式（`word  tonal reading  misreadings,...`）。
建议：在表中为 misreadings.tsv 加一行。STYLE@G2 在其 Main.kt:604 发现（G2-66）中也附带提到此遗漏。

- 提出者：CORRECTNESS-1@S4（NIT，9/10）、CORRECTNESS-2@S4（NIT，9/10）、GENERIC-1@S4（NIT，9/10）

#### F307 [NIT] MainTest 行号断言 contains("2") 形同虚设

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:71-74`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/SourceException.kt:10-11`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:74`

MainTest.kt:74 用 `badErr.contains("2")` 检查是否报告第 2 行，但 `SourceException` 消息以 JUnit 临时目录的绝对路径（`/tmp/junit<digits>/...`）开头，几乎总含有 2，因此证明不了报告的是第 2 行。
建议：断言 `badErr.contains("bad.words:2:")`（CORRECTNESS-2@S6、GENERIC-1@S6），或像 `badSourcesExitWithTheirLocation` 那样用 `startsWith("$bad:2: ")`（CORRECTNESS-1@S6）。

- 提出者：CORRECTNESS-1@S6（NIT，8/10）、CORRECTNESS-2@S6（NIT，9/10）、GENERIC-1@S6（NIT，9/10）

#### F308 [NIT] Misreadings 中的 ü 字面量以分解形式存储

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:54`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:38`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:54`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:38`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:54`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:38`

源码中该字面量是 `u` 加 U+0308（字节 `75 CC 88`），只因先做 NFD 才能匹配；若编辑器或格式化工具把文件规范为 NFC，替换会静默失效，`toneless` 会把 nǚ 变成 `nu` 而不是 `nv`（MisreadingsTest.kt:38 的 `tonesGoAndUmlautIsV` 会发现）。
建议：写成 `"u\u0308"`，使意图可见。

- 提出者：CORRECTNESS-1@S7（NIT，9/10）、CORRECTNESS-2@S7（NIT，9/10）、GENERIC-1@S7（NIT，9/10）

#### F309 [NIT] BitPackedTest 注释“宽度从不整除 64”有误

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`T/engine/data/BitPackedTest.kt:24`、`K/engine/data/BitPacked.kt:39-41`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/BitPackedTest.kt:24`

对宽度 1、2、4、8、16，值不会跨两个字；选 67 个值只保证最后一个字不满，其他宽度仍覆盖跨字情况，测试本身正确。
建议：改写注释（如说明部分宽度会使值跨两个字）。

- 提出者：CORRECTNESS-1@S15（NIT，9/10）、CORRECTNESS-2@S15（NIT，9/10）、GENERIC-1@S15（NIT，9/10）

#### F310 [NIT] TextWordsTest 用 assert 而非 assertTrue

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`T/engine/lattice/TextWordsTest.kt:95`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/TextWordsTest.kt:95`

`assert(...)` 只在 JVM 启用断言（`-ea`）时检查；Gradle Test 任务默认开启，但关闭断言的运行器会静默跳过该检查，与套件中其他 JUnit 断言不一致。
建议：改用 `assertTrue`。

- 提出者：CORRECTNESS-1@S16（NIT，9/10）、CORRECTNESS-2@S16（NIT，9/10）、GENERIC-1@S16（NIT，9/10）

#### F311 [NIT] LayerPriorTest 硬编码词 id

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`T/engine/lattice/LayerPriorTest.kt:30-31`、`K/engine/data/PinyinDataBuilder.kt:44`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPriorTest.kt:30`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPriorTest.kt:31`

`dazi = 1`、`dazi2 = 2` 依赖 `PinyinDataBuilder` 在 `<unk>` 之后按插入顺序编号，构建器重排会悄然交换两个层；同级测试都用 `id(word)` 查找 id。
建议：改用 `id("打字")`、`id("搭子")`，GENERIC-1@S16 还建议给 `dazi2` 一个表明是 搭子 的名字。

- 提出者：CORRECTNESS-1@S16（NIT，8/10）、CORRECTNESS-2@S16（NIT，7/10）、GENERIC-1@S16（NIT，9/10）

#### F312 [NIT] j/q/x/y把ü写作u的规则重复定义了三处

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Fuzzy.kt:33`、`Typo.kt:30`、`ShuangpinSegmenter.kt:108`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/Typo.kt:30`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/Fuzzy.kt:33`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/ShuangpinSegmenter.kt:108`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:129`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:144`

同一集合分别出现在 `Fuzzy.kt:33`（内联，每次调用都新建）、`Typo.kt:30` 和 `ShuangpinSegmenter.kt:108`，将来修改规则需要三处一致地改动。
建议：提取为一个共享的 `internal val`（如放在 `SpellingIndex` 上）。STYLE 另指出 `T9Segmenter.kt:129` 用 `when` 写的数字到字母映射与第 144 行的 `KEYS` 重复，也应由单一来源派生。

- 提出者：CORRECTNESS-1@S10（NIT，8/10）、GENERIC-1@S10（NIT，9/10）、STYLE@G3（NIT，9/10）

#### F313 [NIT] Reranker注释引用了仓库中不存在的设计文档

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Reranker.kt:53`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:53`

`Reranker.kt:53` 的设计依据引用 `dev/ENGINE-DESIGN.md 2.6b`，该文件不在仓库中，维护者无法核对「加上而非替换解码器分数」的数字（−2.2 / +3~5 个 top-1 百分点）。
建议：保留注释中的数字、删掉指向，或把要点移入代码或已跟踪的文档（如指向 `lib/ime-eval`），或提交该文档。

- 提出者：CORRECTNESS-1@S11（NIT，9/10）、CORRECTNESS-2@S11（NIT，9/10）、GENERIC-1@S11（NIT，9/10）

#### F314 [NIT] 会话测试的共享固件分散且被重复定义，九键测试有零碎问题

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinSessionT9Test.kt:22`、`PinyinSessionTest.kt:36`、`PinyinSessionTest.kt:376`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:22`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:143`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:161`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:36`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:376`

`syl` 和 `sessionTestData()` 是包内共享固件，被 Habit、Phrase、Refine、Latin、Misreading 等测试使用，却定义在 PinyinSessionTest.kt:36-39，而 PinyinSessionT9Test.kt:22 又私有地重新定义了 `syl`。
九键测试第 143 行完整限定 `PinyinSegmenter` 而不导入，第 161-162 行与 182 行之前有多余空行；GENERIC-1 还指出第 9-10 行导入顺序有误，CORRECTNESS-1 在同一条中也提到 PinyinSessionTest.kt:376 的 `assertEquals` 实参颠倒。
建议：把固件移到 `SessionTestData.kt`，删除重复的 `syl`，导入 `PinyinSegmenter`，清理空行。

- 提出者：CORRECTNESS-1@S18（NIT，9/10）、CORRECTNESS-2@S18（NIT，9/10）、GENERIC-1@S18（NIT，8/10）

#### F315 [NIT] 通配符不出构词条目的断言仅靠编码长度就通过

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`TableSessionTest.kt:571`、`TableDictionary.kt:78`、`TableDictionary.kt:116`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:578`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:580`

`^abcd` 有 5 个键，`****` 只匹配 4 键编码，`*****` 超过 码长 在 `pattern.length > maxLength` 处提前返回空，所以标记条目在检查构词标记前就已被排除，两条断言都不依赖该标记。
建议：用码长以内的标记条目（如 `^abc 你`）并输入 `***` 或 `****`，让结果取决于标记。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）、CORRECTNESS-2@S19（NIT，9/10）、GENERIC-1@S19（NIT，8/10）

#### F316 [NIT] 码表测试中不起作用的步骤

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`TableSessionTest.kt:48`、`TableSession.kt:146-147`、`TableSession.kt:187-189`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:284`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:290`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:53`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:500`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:427`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWordsTest.kt:46`

键入 `wun` 时 们 已自行上屏，所以第 284 行及第 290 行的 `Select(0)` 在空输入上运行，只清空 `recent`。
CORRECTNESS-2 在同一条中还列出：TableUserTest.kt:53 的 `"vbg "` 中空格只清 `recent`；第 500 行的 `NextPage` 无效，因为 `FakePinyin` 忽略分页；第 427 行注释描述的是 `picked` 却位于 `learning` 上方；SharedWordsTest.kt:46 提到不在列表中的 好好。
建议：删除这些无效步骤，修正注释。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）、CORRECTNESS-2@S19（NIT，9/10）、GENERIC-1@S19（NIT，9/10）

#### F317 [NIT] KeyHabits 达上限时每次学习都把全部习惯展开为 Triple

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:109`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:109`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:105`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:96`

在 5000 条习惯的上限处，每次新增习惯都会把全部习惯展开成约 5000 个 `Triple` 并全量扫描以找出计数最少的一条（KeyHabits.kt:105-109）；`size`（KeyHabits.kt:96）每次也要累加所有键的映射，PERFORMANCE@G4 还指出会分配该键映射的过滤副本。
CORRECTNESS-1@S13 与 CORRECTNESS-2@S13 指出这按提交而非按键发生，代价不大。
建议维护一个运行中的计数，并用最小计数索引或批量淘汰代替每次全量展开。

- 子论断 F317.b 被否定：「达到上限时，每次提交都会在 trim 中把所有习惯展平为 Triple，并且 size 会对所有键求和。」→ “每次提交”不成立：PinyinSession.kt:258 只在 pieces.size > 1 或已存在习惯时才调用 learn；KeyHabits.kt:66 在 text == own 时于 trim 之前返回；KeyHabits.kt:106-107 在对已有 text 重新计数时提前返回（size 保持在上限）
- 提出者：CORRECTNESS-1@S13（NIT，8/10）、CORRECTNESS-2@S13（NIT，8/10）、PERFORMANCE@G4（NIT，9/10）

#### F318 [NIT] WordLists 每次调用都重新编译正则

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:134`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:134`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:158`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:164`

`line()`（WordLists.kt:134）每导入一行就编译一次正则 `\s+`，`entry()` 每次调用编译两次 `[\s']+`（158、164 行），而同一 companion 中的 `NUMBER` 已预编译；PERFORMANCE@G4 指出大规模导入会编译数十万次。
STYLE@G4 另指出 158 与 164 行重复了同一条 trim/replace/split/filter 处理链。
建议把这些正则提为 companion 常量，STYLE@G4 还建议共享这条处理链。

- 提出者：GENERIC-1@S13（NIT，9/10）、PERFORMANCE@G4（NIT，9/10）、STYLE@G4（NIT，8/10）

#### F319 [NIT] UserStoreTest 中「无习惯构建」的读取步骤没有断言

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:377`、`UserStore.kt:49-55`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:377`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:375`

注释称「a build without habits reads the rest of the log」，但此时日志里只有 HABIT 记录、模型为空，这一步只检查不抛异常（UserStoreTest.kt:375-377）。
建议在第一次会话中先学一个词或一句话，再断言无习惯打开后其计数被恢复，以证明跳过的 HABIT 记录不会截断日志；CORRECTNESS-2@S20 也接受删掉这一行。

- 提出者：CORRECTNESS-1@S20（NIT，9/10）、CORRECTNESS-2@S20（NIT，9/10）、GENERIC-1@S20（NIT，8/10）

#### F320 [NIT] WordListsTest 测试名称说应用到模型，但从未调用 applyTo

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:48`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:48`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:48`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:208`

`theListsAreKeptInTheirFilesAndAppliedToAModel`（WordListsTest.kt:48）从未调用 `WordLists.applyTo(model)`；ime-core 中没有测试直接调用 `applyTo`、`addAll` 或 `blockAll`，唯一的生产调用方是 Engines.kt:208，可能的间接覆盖在 Engines 测试中（CORRECTNESS-2@S20 与 GENERIC-1@S20 都未读这些测试）。
建议创建一个 `UserModel` 并调用 `applyTo`，断言添加的词被列出、屏蔽的词被屏蔽（如 `listedTexts()`/`inTrie` 与 `blockedAnyhow`/`blocked`），或者给测试改名。

- 提出者：CORRECTNESS-1@S20（NIT，9/10）、CORRECTNESS-2@S20（NIT，6/10）、GENERIC-1@S20（NIT，9/10）

#### F321 [NIT] 测试代码的断言写法与空行风格不一致

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachineTest.kt:26-27`、`KawaiiBarStateMachineTest.kt:27-28`、`ClipboardStateMachineTest.kt:28-29`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachineTest.kt:26-27`、`KawaiiBarStateMachineTest.kt:27-28`、`ClipboardStateMachineTest.kt:28-29`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:333`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:339`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:349`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:230`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:352`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachineTest.kt:26`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/bar/KawaiiBarStateMachineTest.kt:27`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/clipboard/ClipboardStateMachineTest.kt:28`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:407`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:63`

UserModelTest 用 `assertEquals(true/false, …)`（333、339、349 行）和 `assertTrue(!inTrie(...))`（230 行）代替 `assertTrue`/`assertFalse`，352 行右花括号前有空行；ExpandButtonStateMachineTest.kt:26、KawaiiBarStateMachineTest.kt:27、ClipboardStateMachineTest.kt:28 的类体以两个空行开头（CORRECTNESS-2@S20、GENERIC-1@S20）。
STYLE@G4 指出 UserModelTest 的同一断言问题，另指出 KeyGestureRecognizerTest.kt:407 用 `;` 串联语句并按位置传布尔参数（`onUp(1000L, false, false)`），而文件其余部分用命名参数；VoiceHoldTest.kt:63 是仓库中唯一使用反引号的测试名。
建议统一使用 `assertTrue`/`assertFalse`，删除多余空行，并统一命名参数与测试命名风格。

- 提出者：CORRECTNESS-2@S20（NIT，9/10）、GENERIC-1@S20（NIT，9/10）、STYLE@G4（NIT，9/10）

#### F322 [NIT] no-latin 方案下 `--neighbours` 被错误拒绝

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:169`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:169`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:252`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:253`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:398`

Main.kt:169 的检查要求 `scheme == null`，因此全拼方案 `no-latin` 也被拒绝，而 `segmenter()` 对它会传递 `neighbours`（:252），结果该方案下 neighbours 永远为 false。
修复：允许 `scheme == null || scheme == NO_LATIN`。STYLE 另指出两处类似的代理判断：:398 的 `spell = scheme != null` 对 no-latin 和 T9 也开启双拼拼写（只影响 preedit 和提示，不影响 KSC），:253 的 `scheme == "t9"` 硬编码了 `T9Set.SCHEMES` 中的一个成员。
GENERIC-1@S24 在 G5-02 中也提到此问题，并提出也可以加测试确认是有意拒绝。

- 提出者：CORRECTNESS-1@S23（NIT，9/10）、GENERIC-1@S23（NIT，7/10）、STYLE@G5（NIT，9/10）

#### F323 [NIT] SlipSet 生成的样本丢失原样本的 context

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/SlipSet.kt:50`、`ShuangpinSet.kt:30`、`T9Set.kt:22`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/SlipSet.kt:50`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/SlipSet.kt:75`

SlipSet.kt:50 和 :75 用 `Sample(..., sample.expected, kind)` 新建样本，把 `context` 重置为空；ShuangpinSet 和 T9Set 通过 `sample.copy(...)` 保留 context，`writeSet` 的注释也说 context 必须保留。
目前 pinyin.tsv 没有 context，所以暂无影响；CORRECTNESS-2@S23 指出若对 chat 或 web 集（821 和 3087 条 context）运行 `slips` 会丢失它们。
修复：`sample.copy(input = …, tag = kind)`。

- 提出者：CORRECTNESS-1@S23（NIT，9/10）、CORRECTNESS-2@S23（NIT，9/10）、GENERIC-1@S23（NIT，9/10）

#### F324 [NIT] `theUsageNamesEveryCommand` 并未检查全部命令

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:58`、`Main.kt:32-46`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:58`；另见 `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:55`

MainTest 的 `theUsageNamesEveryCommand` 只检查 USAGE 中 7 个命令，漏掉 `predict`、`predict-learn`、`predict-set`、`sentences`、`lm`、`t9`、`ksc`、`learn`，测试名承诺的比实际检查的多。
各阶段统计的命令总数不同：STYLE（G5-33）称 14 个，CORRECTNESS-2@S24 称 15 个，GENERIC-1@S24 称 16 个。
修复：从 `runCli` 实际分派的命令生成列表，或重命名该测试。CORRECTNESS-1@S24 在 G5-23 中也提到此缺口。

- 子论断 F324.c 被否定：「theUsageNamesEveryCommand 只检查了 16 个命令中的 7 个。」→ lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:32-46 处的 USAGE 列出了 15 个命令，而不是 16 个
- 提出者：TESTING@G5（NIT，8/10）、CORRECTNESS-2@S24（NIT，9/10）、GENERIC-1@S24（NIT，9/10）

#### F325 [NIT] PinyinRunTest 与 Main.kt 的 import 未排序

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:9-12`、`PinyinRunTest.kt:12-14`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:9-12`、`PinyinRunTest.kt:12-14`）
- 位置：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/PinyinRunTest.kt:12`；另见 `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/PinyinRunTest.kt:12-14`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:10`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:69`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:58`

PinyinRunTest.kt:12-14 中 `assertThrows`、`SourceException` 排在 `assertEquals` 之前，与其他测试文件不同；STYLE 还指出 Main.kt 中 `engine.user.WordPack` 夹在 `data` 与 `lattice` 的 import 之间、`Predictor` 排在 `Penalties` 之前，而 ime-core 的 import 是排好序的。
修复：对 import 排序。CORRECTNESS-1@S24 在同一条中另提到两处测试缺口：`EvalSetTest.everySetParses` 用非递归的 `listFiles`，没有解析 data/predict/chat.tsv（见 G5-24）；`theUsageNamesEveryCommand` 漏掉多个命令（见 G5-22）。

- ⚠️ 验证说明：部分论断被 verifier 否定：ime-core 并没有保持 import 有序：Engines.kt 先导入 T9Segmenter 再导入 Syllables，PinyinSession.kt 先导入 NgramModel.Companion.NO_WORD 再导入 Misreadings，在 07d2778 以及 TableSessionTest.kt、PinyinSessionTest.kt、PinyinSessionRefineTest.kt 和 PinyinSessionT9Test.kt 中情况相同。Main.kt:9-12 和 PinyinRunTest.kt:12-14 中的 import 顺序是乱的。
- 子论断 F325.a 被否定：「Main.kt 和 PinyinRunTest.kt 中的 import 顺序是乱的，而 ime-core 保持 import 有序。」→ 与 F325 相同的证据：lib/ime-core 下有 6 个文件的 import 未排序（例如 lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt）。
- 提出者：STYLE@G5（NIT，9/10）、CORRECTNESS-1@S24（NIT，9/10）、GENERIC-1@S24（NIT，9/10）

#### F326 [NIT] candidateFromAll 已知到末尾后仍多做一次 JNI 获取

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:100`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:100`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:99`

当一次获取返回的候选少于 count（但非空）且 idx 仍在新的 total_ 之外时，循环会再调用一次 fetch，拿到空结果后才抛出，多一次 JNI 往返。
修复：更新 total_ 后若 idx >= total_ 立即抛出，或在每轮开始时检查 total_ >= 0 && idx >= total_。

- 提出者：CORRECTNESS-1@S26（NIT，8/10）、CORRECTNESS-2@S26（NIT，9/10）、GENERIC-1@S26（NIT，9/10）

#### F327 [NIT] 引擎描述 Type English words in pinyin 未加入 po

- 来源：第一轮（整仓 @07d2778） · 共识：3/15 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.h:92`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.h:92`）
- 位置：`app/src/main/cpp/po/fcitx5-android.pot:56`；另见 `app/src/main/cpp/po/fcitx5-android.pot:57`、`app/src/main/cpp/androidengine/androidengine.h:92`、`app/src/main/cpp/androidengine/androidengine.h:115`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:127`

androidengine.h:92 和 :115 使用 D_("fcitx5-android", "Type English words in pinyin")，但 fcitx5-android.pot、zh_CN.po 和 zh_TW.po 只加入了另外三个引擎字符串，这条描述始终是英文。应用自己的 InputMethodSettings 页面（InputMethodSettings.kt:127）用的是 R.string.im_latin_words，所以只有显示原始 fcitx 描述的通用配置页（AddonConfigFragment、InputMethodConfigFragment）受影响；GENERIC-1@S27 认为目前不可见。
修复：在三个文件中加入该 msgid（CORRECTNESS-2@S27 建议 zh_CN 用「中英混输」，与 im_latin_words 一致）；GENERIC-1@S27 另提若原生翻译本就不用可去掉。

- 提出者：CORRECTNESS-2@S27（NIT，9/10）、GENERIC-1@S27（NIT，8/10）、STYLE@G6（NIT，7/10）

#### F328 [NIT] FcitxApplication 的 KDoc 中 adb 命令仍用旧包名

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:179`、`FcitxApplication.kt:172-173`、`app/build.gradle.kts:19`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:179`、`FcitxApplication.kt:172-173`、`app/build.gradle.kts:19`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:179`；另见 `app/build.gradle.kts:19`

广播 action 是 `${BuildConfig.APPLICATION_ID}.action.RESTART_FCITX_INSTANCE`，改名后就是 `io.github.sraw.youmo.action.RESTART_FCITX_INSTANCE`（app/build.gradle.kts:19），但 FcitxApplication.kt:179 的 KDoc 仍写着 `org.fcitx.fcitx5.android.action...`，照着执行没有效果。
修复：更新 KDoc 中的示例命令。

- 提出者：CORRECTNESS-1@S29（NIT，9/10）、CORRECTNESS-2@S29（NIT，9/10）、GENERIC-1@S29（NIT，9/10）

#### F329 [NIT] DEFAULT_DRAWABLE 已无人使用

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/ReturnKeyDrawableComponent.kt:44`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/ReturnKeyDrawableComponent.kt:44`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/ReturnKeyDrawableComponent.kt:43`

`ReturnKeyAppearance.Enter` 取代它之后，`ReturnKeyDrawableComponent.kt:43-44` 的 `DEFAULT_DRAWABLE` 在 app/src 中已没有任何引用，只剩它自己的声明。
修复：删除。

- 提出者：CORRECTNESS-1@S34（NIT，9/10）、CORRECTNESS-2@S34（NIT，9/10）、GENERIC-1@S34（NIT，8/10）

#### F330 [NIT] cleanClipboardText 吞掉清理器的异常且不记日志

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:229`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:229`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:61`

`ClipboardManager.kt:229` 的 `runCatching { it(text) }.getOrNull()` 静默丢弃清理器的失败，而规则加载失败会记日志（:61）；某条规则在设备上出错时不会留下任何痕迹。
修复：回退之前用 `Timber.w` 记录异常，消息里不带剪贴内容（CORRECTNESS-2@S30 建议像 ClearURLs.log 那样在 release 中不记 URL）。

- 提出者：CORRECTNESS-1@S30（NIT，8/10）、CORRECTNESS-2@S30（NIT，8/10）、GENERIC-1@S30（NIT，9/10）

#### F331 [NIT] 冒烟测试并未验证 KDoc 所说的 mock final 类

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/TestHarnessSmokeTest.kt:81`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/TestHarnessSmokeTest.kt:79`；另见 `app/src/test/java/org/fcitx/fcitx5/android/TestHarnessSmokeTest.kt:81`

TestHarnessSmokeTest.kt:79-81 的文档说它验证 MockK 能 stub final Kotlin 类，但它 mock 的是抽象 Java 类 `android.content.Context`，任何 mock 库（包括 Mockito）都能做到。
修复：mock 一个 final Kotlin 类（本地类就够），或改写注释。

- 提出者：CORRECTNESS-1@S49（NIT，9/10）、CORRECTNESS-2@S49（NIT，9/10）、GENERIC-1@S49（NIT，9/10）

#### F332 [NIT] SoftKeyboardTest 提前返回，吞掉恢复失败

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:383`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:383`；另见 `app/src/androidTest/java/org/fcitx/fcitx5/android/input/SoftKeyboardTest.kt:396`

设备键盘已经是本 app 时，SoftKeyboardTest.kt:383 的提前 `return` 跳过了 :396 的 `restoreIms.getOrThrow()`，恢复 fcitx 输入法列表的失败会被悄悄吞掉。
修复：在 `when` 之前调用 `getOrThrow()`，或调整分支结构。

- 提出者：CORRECTNESS-1@S49（NIT，7/10）、CORRECTNESS-2@S49（NIT，9/10）、GENERIC-1@S49（NIT，9/10）

#### F333 [NIT] NumberKeyboard.space 声明为错误的视图类型

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 NIT, 1 NIT PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/NumberKeyboard.kt:59`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyDefPreset.kt:283-288`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:174`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/NumberKeyboard.kt:59`

space 声明为 TextKeyView，但 MiniSpaceKey 是 Image 外观，实际视图是 ImageKeyView；目前无人读取，一旦访问会抛 ClassCastException。
建议：改为 ImageKeyView 或删除。CORRECTNESS-1 将其标为 PRE-EXISTING，另两个阶段未标。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S37（NIT PRE-EXISTING，9/10）、CORRECTNESS-2@S37（NIT，9/10）、GENERIC-1@S37（NIT，9/10）

#### F334 [NIT] 注释称全局选项在「高级」中，但没有页面显示它们

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`MainFragment.kt:74-76`、`AdvancedSettingsFragment.kt:113-114`、`SettingsRoute.kt:51`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`MainFragment.kt:74-76`、`AdvancedSettingsFragment.kt:113-114`、`SettingsRoute.kt:51`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:75`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:74`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:76`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/AdvancedSettingsFragment.kt:114`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/SettingsRoute.kt:51`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/SettingsRoute.kt:200`

MainFragment 的注释说 fcitx 全局选项位于「高级」，而 AdvancedSettingsFragment.kt:114 说明它们不显示，也没有代码导航到仍在 SettingsRoute.kt 中注册（:51、:200）的 SettingsRoute.GlobalConfig，该路由和 GlobalConfigFragment 成为死代码；评审指出使用实体键盘的用户因此无法修改 fcitx 热键。
建议：修正注释，并删除该路由和 fragment，或有意在高级页中链接它。

- 提出者：CORRECTNESS-1@S40（NIT，9/10）、CORRECTNESS-2@S40（NIT，9/10）、GENERIC-1@S40（NIT，9/10）

#### F335 [NIT] 删除标点卡片无确认也无法撤销

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:132-137`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:132`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:132-136`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:132-137`

中性按钮「删除」立即移除卡片并保存，没有确认或撤销，页面也无法恢复默认映射；被替换的 `BaseDynamicListUi` 列表界面原本支持撤销。一次误触就会永久丢失一张卡片（可能是默认映射），只能凭记忆重新输入。
建议：增加撤销 Snackbar 或确认步骤。

- 提出者：CORRECTNESS-1@S42（NIT，9/10）、CORRECTNESS-2@S42（NIT，8/10）、GENERIC-1@S42（NIT，7/10）

#### F336 [NIT] `onKeysChangeListener` 重复刷新主题预览

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:63-65`、`ThemePrefs.kt:73-160`、`ThemeManager.kt:125-131`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:63-65`、`ThemePrefs.kt:73-160`、`ThemeManager.kt:125-131`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:64`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt:125-131`

`KEY_KEYS` 中每个键都是已注册的 `ThemePrefs` 偏好，对这些键 `ThemeManager.onThemePrefsChange`（`ThemeManager.kt:125-131`）已调用 `fireChange()`，经 `onThemeChangeListener` 执行 `previewUi.setTheme`；因此每次写入都在主线程重建两次预览 `TextKeyboard`，一次按键间距档位（写 4 个键）重建 8 次。删除该监听器不影响实时预览。
建议：删除 `onKeysChangeListener`、`KEY_KEYS` 及其注册/注销调用；GENERIC-1 指出 `SIZE_KEYS` 仍需保留，因为这些键属于 `AppPrefs.keyboard`。

- 提出者：CORRECTNESS-1@S44（NIT，9/10）、CORRECTNESS-2@S44（NIT，8/10）、GENERIC-1@S44（NIT，9/10）

#### F337 [NIT] Const.kt 注释引用了仓库中不存在的文档

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt:19`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt:19`

`Const.kt` 第 19 行注释引用 `dev/TRAINING-PLAN.md 12.3`，该文件未纳入仓库。
建议：直接在注释中写明 `words-YYYYMM` 命名规则，或指向 `lexicon/README.md`。

- 提出者：CORRECTNESS-1@S45（NIT，9/10）、CORRECTNESS-2@S45（NIT，9/10）、GENERIC-1@S45（NIT，9/10）

#### F338 [NIT] 四个 drawable 已无任何引用

- 来源：第一轮（整仓 @07d2778） · 共识：3/5 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/res/drawable/ic_baseline_developer_mode_24.xml:1`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/res/drawable/ic_baseline_developer_mode_24.xml:1`；另见 `app/src/main/res/drawable/ic_baseline_tune_24.xml:1`、`app/src/main/res/drawable/ic_baseline_extension_24.xml:1`

`ic_baseline_developer_mode_24`、`ic_baseline_extension_24`、`ic_baseline_settings_backup_restore_24` 和 `ic_baseline_tune_24` 在 `app/` 和 `lib/` 中没有任何 `R.drawable.*` 或 `@drawable/*` 引用，是设置重构（c0b4fad3）、插件移除（fffa7952）或 094fc8e7 之后留下的。
评审对 APK 影响看法不一：CORRECTNESS-2@S47 认为 release 设置了 `isShrinkResources = true`，APK 不受影响；GENERIC-1@S47 认为构建没有设置 `isShrinkResources`，四个文件都会进入每个 APK。
修复：删除这四个文件。

- 子论断 F338.c 被否定：「该分片中有四个 drawable 从未被引用，并且由于没有设置 isShrinkResources，它们会被打包进每个 APK。」→ build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt:44-45（release 设置了 isMinifyEnabled 和 isShrinkResources = true，因此只有 debug APK 会保留这些 drawable）
- 提出者：CORRECTNESS-1@S47（NIT，9/10）、CORRECTNESS-2@S47（NIT，9/10）、GENERIC-1@S47（NIT，8/10）

#### F339 [NIT] 锁图标填充为黑色，与其他白色图标不一致

- 来源：第一轮（整仓 @07d2778） · 共识：3/14 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`drawable/ic_baseline_lock_24.xml:7`、`ic_baseline_lock_open_24.xml:7`、`PickerLayout.kt:55`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`drawable/ic_baseline_lock_24.xml:7`、`ic_baseline_lock_open_24.xml:7`、`PickerLayout.kt:55`）
- 位置：`app/src/main/res/drawable/ic_baseline_lock_24.xml:7`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyView.kt:440`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerLayout.kt:55`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerLayout.kt:129`

`ic_baseline_lock_24` 和 `ic_baseline_lock_open_24` 使用 `#FF000000`，同目录其他图标都用 `#ffffff`。
目前没有可见问题，因为 `ImageKeyView` 设置了 `imageTintList`（KeyView.kt:440），且该 tint 在 PickerLayout.kt:55 的 `setImageResource` 之后仍然有效；但将来若未着色使用，会在深色键盘上显示为黑色。
修复：填充改为 `#ffffff`。

- 子论断 F339.b 被否定：「这两个锁图标使用黑色填充，与该文件夹中其他所有图标都不同。」→ 锁图标并不是 drawable/ 中唯一以黑色填充的图标：ic_launcher_foreground_monochrome.xml 使用 fillColor="#000"（8 条 path），而 ic_launcher_foreground.xml 是多色的
- 子论断 F339.c 被否定：「锁图标以黑色填充，而该分片中其他所有图标都是白色。」→ 并非其他所有图标都是白色：drawable/ic_launcher_foreground.xml 填充 #F8DDB5/#3B2314/#8B5A2B，ic_launcher_foreground_monochrome.xml 填充 #000（假设该分片包含 drawable/）
- 提出者：CORRECTNESS-1@S47（NIT，9/10）、CORRECTNESS-2@S47（NIT，9/10）、GENERIC-1@S47（NIT，8/10）

#### F340 [NIT] ja 的 removed_n_items 使用 %1$s 而非 %1$d

- 来源：第一轮（整仓 @07d2778） · 共识：3/11 位 reviewer（3 NIT）· 置信度 9/10 · 验证：✅ 已确认（`values-ja/strings.xml:213`、`values/strings.xml:224`、`BaseDynamicListUi.kt:161`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`values-ja/strings.xml:213`、`values/strings.xml:224`、`BaseDynamicListUi.kt:161`）
- 位置：`app/src/main/res/values-ja/strings.xml:213`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/common/BaseDynamicListUi.kt:161`

values-ja/strings.xml:213 的 `removed_n_items` 使用 `%1$s`，默认字符串用的是 `%1$d`。
BaseDynamicListUi.kt:161 传入 Int，`%s` 仍能格式化，运行时正常，但 Android lint 会报 StringFormatMatches。
修复：改为 `%1$d`。

- 提出者：CORRECTNESS-1@S48（NIT，9/10）、CORRECTNESS-2@S48（NIT，9/10）、GENERIC-1@S48（NIT，9/10）

#### F341 [NIT] baseline-app.xml 条目 ID 内嵌 KDoc，改注释即失配

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`config/detekt/baseline-app.xml:68`、`config/detekt/baseline-lib-ime-core.xml:7`、`app/src/main/java/org/fcitx/fcitx5/android/input/editing/EditorInfoTraits.kt:10-15`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`config/detekt/baseline-app.xml:68`、`config/detekt/baseline-lib-ime-core.xml:7`、`app/src/main/java/org/fcitx/fcitx5/android/input/editing/EditorInfoTraits.kt:10-15`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/editing/EditorInfoTraits.kt:14`；另见 `config/detekt/baseline-app.xml:68`、`config/detekt/baseline-lib-ime-core.xml:7`、`config/detekt/baseline-app.xml:69`、`config/detekt/baseline-lib-ime-core.xml:5`

`DeleteSurroundingFlag` 的 `TopLevelPropertyNaming` 基线 ID 包含整段 KDoc；detekt 按字符串精确匹配基线 ID，改动注释措辞就会让条目失配，`./gradlew detekt` 会在没人改代码时让 CI 失败，修复只能重新生成基线，而 CLAUDE.md 规定基线不得增长。
CORRECTNESS-2@S3 与 GENERIC-1@S3 指出 EditorInfoTraits.kt:14 用的 `ConstPropertyName` 是 IntelliJ inspection 名，detekt 不认，该发现因此进了基线；CORRECTNESS-1@S3 在同一条中还提到 baseline-lib-ime-core.xml:7 的同类问题（见 G1-57），GENERIC-1@S3 还点名 `WildcardImport:PickerLayout.kt`（第 69 行）和 `InvalidPackageDeclaration:StringEscapeTest.kt`（见 G1-28）也可直接修复。
建议：在 EditorInfoTraits.kt:14 改用 `@Suppress("TopLevelPropertyNaming")`（GENERIC-1@S3 也提到可重命名常量），然后删除该条目。

- 提出者：CORRECTNESS-1@S3（NIT，8/10）、CORRECTNESS-2@S3（NIT，8/10）、GENERIC-1@S3（NIT，8/10）

#### F342 [NIT] latin.py 解析 ARPA 一元行保留换行符，无匹配时崩溃

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lexicon/tools/latin.py:63-64`、`lexicon/tools/latin.py:67-68`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lexicon/tools/latin.py:63`；另见 `lexicon/tools/latin.py:68`

latin.py:63 用 `line.split('\t')` 切分，无 backoff 字段的一元行会保留末尾的 `\n`，`fullmatch` 漏掉该词，使其不计入 offset 中位数。
CORRECTNESS-1@S4 与 GENERIC-1@S4 指出若一个一元都没匹配，:68 的 `statistics.median([])` 抛出裸 StatisticsError；GENERIC-1@S4 还指出 ArpaReader 接受空格分隔字段而 latin.py 只认 tab，其它工具生成的 ARPA 会得不到任何模型词。
建议：用 `line.rstrip('\n').split('\t')`（GENERIC-1@S4 建议 `line.split()`），并在 `offsets` 为空时给出明确信息后退出。

- 提出者：CORRECTNESS-1@S4（NIT，8/10）、CORRECTNESS-2@S4（NIT，6/10）、GENERIC-1@S4（NIT，6/10）

#### F343 [NIT] WordLayers 在每次查找时而非加载时校验层值

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:128`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:128`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:128`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinDataBuilder.kt:36`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:118`

`layer()` 中的逐词检查使坏的层段能正常加载，却在按键时（`LayerPrior` 为每个被打分的词调用）抛 `DataFormatException`，违背 `BitPacked.checkRuns` 文档所述规则，并给逐词路径加了分支；运行时以 `verify = false` 加载（Engines.kt:118），校验和也发现不了。CORRECTNESS-2@S7 补充 `PinyinDataBuilder.layers()`（:36）不复查在它之前加入的条目；GENERIC-1@S7 补充 `DICT_SYLLABLE` 与 `BI_WORD`/`TRI_WORD` 加载时也未做范围检查。
建议：在 `WordLayers` 的 `init` 中调用 `packed?.checkBelow("word layers", names.size)`，并去掉逐次检查。

- 提出者：CORRECTNESS-1@S7（NIT，8/10）、CORRECTNESS-2@S7（NIT，7/10）、GENERIC-1@S7（NIT，7/10）

#### F344 [NIT] UserStoreTest 的先验测试手动调用 journal，未走 learn

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:200-201`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:200-201`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:200`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:188`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPriorTest.kt:52`

测试直接调用 `prior.journal!!.changed(...)`，第 188 行的 `prior.learn(intArrayOf(), intArrayOf())` 什么也没改；它证明了 UserStore 的 journal 接线，但没有证明真实的选词（`LayerPrior.learn` → `move`）能端到端写进日志。CORRECTNESS-2@S20 指出记入的值（0.5、-0.5）与先验的内存状态（0.25、-0.75）也不一致，LayerPriorTest:52 只单独覆盖 learn → journal。
建议用会移动某层的词 id 调用 `prior.learn(picked, first)` 来驱动；GENERIC-1@S20 也接受把这一步改名为「values written through the journal come back」。

- 提出者：CORRECTNESS-1@S20（NIT，8/10）、CORRECTNESS-2@S20（NIT，8/10）、GENERIC-1@S20（NIT，8/10）

#### F345 [NIT] 测试把 predict(CursorRange) 的实例共享行为固定为 API

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:124-131`、`CursorTracker.kt:24-26`、`CursorTracker.kt:32`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:124-131`、`CursorTracker.kt:24-26`、`CursorTracker.kt:32`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:125`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorRangeTest.kt:55`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:356`

`CursorTrackerTest.kt:125`（`predictStoresTheGivenInstanceNotACopy`）要求 `predict` 保存调用方传入的 `CursorRange` 实例而非副本；而没有任何生产调用方传入区间实例，`EditingSession` 中所有 `selection.predict` 调用都用 Int 重载。
固定这一隐患会让安全的修复（在 `predict` 中复制，或把该重载设为 private）看起来像回归。TESTING@G4 把同一问题扩展到 `CursorRangeTest.kt:55`（要求 `==` 按引用比较）和 `KeyGestureRecognizerTest.kt:356`（见 G4-07）。
建议在 `predict` 中复制并断言预测不受调用方之后修改的影响，或把这些说明移进 KDoc、只断言调用方依赖的行为。

- 提出者：CORRECTNESS-1@S21（NIT，8/10）、GENERIC-1@S21（NIT，7/10）、TESTING@G4（NIT，8/10）

#### F346 [NIT] 分数相同时 nbest[0] 可能与 tokens/text 不一致

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:211`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:211`；另见 `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:209`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-decoder.h:31`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/kotlin-api/OfflineRecognizer.kt:16`

最佳假设来自 GetMostProbable（max_element，取哈希顺序中的第一个最大值），n-best 列表来自 GetTopK（partial_sort，并列顺序不确定）；offline-transducer-decoder.h:31 和 OfflineRecognizer.kt:16 的注释承诺最佳项是第一个，VoiceEngineTest 也断言 text == nbest[0]，但精确并列时两者可能选中不同假设。
修复：只调用一次 GetTopK，用其第一个元素作为 hyp，顺带省去一次假设复制或一轮遍历。

- 提出者：CORRECTNESS-1@S52（NIT，8/10）、CORRECTNESS-2@S52（NIT，8/10）、GENERIC-1@S52（NIT，7/10）

#### F347 [NIT] GridDecoration 用子视图下标判断末项，滚动后多画分隔线

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/decoration/GridDecoration.kt:50`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/decoration/GridDecoration.kt:50`

`GridDecoration.kt:50` 中的 `i` 是可见子视图的下标，不是 adapter 位置，`i == itemCount - 1` 只有在所有项都在屏幕上时才成立；网格滚动之后，最后一个候选后面会多画一条分隔线。
修复：改用 `parent.getChildAdapterPosition(view) == itemCount - 1`。

- 提出者：CORRECTNESS-1@S35（NIT，8/10）、CORRECTNESS-2@S35（NIT，8/10）、GENERIC-1@S35（NIT，8/10）

#### F348 [NIT] libime 词典迁移的重名检查漏掉了 .words 词包

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:93`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:94`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:93`

`ImportedDictionaries.migrate`（:93-94）只检查 `name.txt` 和 `name.txt.disable`（`fileName(name, it)` 默认是 `Type.Text`），已有的 `name.words` 词包会与迁移出来的 `name.txt` 并存：两本同名词典共享一个 `.new-words` 条目，删除其中一本会经 `PinyinDictManager.delete` → `setIntoNew(name, false)` 改变另一本所在的层。
修复：像 `PinyinDictManager.hasDictionary` 那样同时检查两种类型。

- 提出者：CORRECTNESS-1@S31（NIT，8/10）、CORRECTNESS-2@S31（NIT，7/10）、GENERIC-1@S31（NIT，7/10）

#### F349 [NIT] KeyEventRelayTest 使用 mockk 而非 fake

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/input/editing/KeyEventRelayTest.kt:24`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/input/editing/KeyEventRelayTest.kt:24`；另见 `app/src/test/java/org/fcitx/fcitx5/android/input/editing/KeyEventRelayTest.kt:95`、`app/src/test/java/org/fcitx/fcitx5/android/input/editing/KeyEventRelayTest.kt:79`

KeyEventRelayTest.kt:24 用 relaxed mockk 的 `InputConnection` 和模拟的 `KeyEvent`，而 CLAUDE.md 要求「fakes over mockk」；同一个包里的契约测试已经在驱动真实的 `BaseInputConnection`。CORRECTNESS-2@S50 还指出 :95 的 `unicodeChar != PICKER_DIALOG_INPUT` 断言只检验了 Robolectric 的 `KeyCharacterMap` shadow，建议只检查 `deviceId == -1`。
修复：写一个记录 `sendKeyEvent` 和 `clearMetaKeyStates` 的 `BaseInputConnection` 子类；GENERIC-1@S50 认为只有 :79 的选字器用例需要模拟 `KeyEvent`。

- 提出者：CORRECTNESS-1@S50（NIT，8/10）、CORRECTNESS-2@S50（NIT，7/10）、GENERIC-1@S50（NIT，8/10）

#### F350 [NIT] FakeFcitxAPI 的 setEnabledIme 不保留传入顺序

- 来源：第一轮（整仓 @07d2778） · 共识：3/18 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:159`、`native-lib.cpp:173-181`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:159`、`native-lib.cpp:173-181`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:159`；另见 `app/src/test/java/org/fcitx/fcitx5/android/core/FakeFcitxAPI.kt:115`、`app/src/main/cpp/native-lib.cpp:177`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:401`

真实的 fcitx 保留传入的顺序（native-lib.cpp:177-179，见 `FcitxTest.enabledImeIsWhatWasSet`），而 FakeFcitxAPI.kt:159 按 `availableIme` 的顺序返回，依赖顺序的测试会得到错误结果。CORRECTNESS-2@S49 还指出它让 `inputMethodEntryCached` 指向刚被移除的输入法；GENERIC-1@S49 还指出 `exportUserWords`（:115）缺少 `Engines.exportWords`（Engines.kt:401）写出的屏蔽词前缀 `!` 和音节之间的 `'`，并说明目前没有测试依赖这两处差异。
修复：`array.mapNotNull { n -> availableIme.find { it.uniqueName == n } }`。

- 提出者：CORRECTNESS-1@S49（NIT，8/10）、CORRECTNESS-2@S49（NIT，7/10）、GENERIC-1@S49（NIT，8/10）

#### F351 [NIT] popupOverrides 偏好被两处以不同方式解析缓存

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`PopupComponent.kt:64-75`、`TextKeyboard.kt:105-112`、`PickerPolicy.kt:41-42`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PopupComponent.kt:64-75`、`TextKeyboard.kt:105-112`、`PickerPolicy.kt:41-42`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt:65`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt:69`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:105`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerPolicy.kt:41`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/AdvancedSettingsFragment.kt:110`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:54`

PopupComponent（:65-74）每次长按重新读取并比较文本，TextKeyboard.kt:105-111 则在变更监听中解析自己的副本，用于滑动和键角字符；两者目前都能工作，但可能逐渐不一致。
建议：在依赖作用域中共享一个解析后的实例。CORRECTNESS-1 另请确认 resolve 现在也作用于 DefaultPickerPolicy 预设（PickerPolicy.kt:41），即对 . 的覆盖会改变或关闭符号选择器的 . 弹出；STYLE 另指出 zh_CN 字面量在 AdvancedSettingsFragment.kt:110 和 SettingsIndex.kt:54 重复，应使用 PunctuationEditorFragment.DEFAULT_LANG 或传 null。

- 提出者：CORRECTNESS-1@S39（NIT，7/10）、GENERIC-1@S39（NIT，8/10）、STYLE@G8（NIT，8/10）

#### F352 [NIT] `SIZE_KEYS` 硬编码偏好键字符串

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:151-155`、`AppPrefs.kt:176`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ThemeFragment.kt:151`

`ThemeFragment` 第 151 行的六个字符串复制了 `AppPrefs.Keyboard` 注册的键；目前一致，但若那边改名，实时尺寸预览会静默停止更新，编译器和测试都发现不了。
建议：用偏好对象的 `.key` 构建集合，如 `keyboard.keyboardHeightPercent.key`、`keyboard.keyboardSidePaddingLandscape.key`。

- 提出者：CORRECTNESS-1@S44（NIT，8/10）、CORRECTNESS-2@S44（NIT，8/10）、GENERIC-1@S44（NIT，8/10）

#### F353 [NIT] 临时目录以毫秒时间戳命名，不唯一

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/TempDir.kt:11`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/TempDir.kt:11`

`TempDir.kt` 第 11 行用 `currentTimeMillis()` 命名目录，同一毫秒启动的两次导入会共用目录，先结束的一方会删除另一方的文件；`mkdirs()` 结果未检查，崩溃遗留的旧目录会被复用，`extract` 的 `listFiles()` 会返回其中的旧文件。CORRECTNESS-1 说明代码来自上游。
建议：用 `File.createTempFile(...)` 后 delete+mkdir，或用随机名/UUID，并检查创建是否成功。

- ⚠️ 验证说明：部分论断被 verifier 否定：app/src/main/java/org/fcitx/fcitx5/android/utils/TempDir.kt:11（每次调用时名称都是新取的 currentTimeMillis，因此崩溃后遗留的目录只有在之后某次调用恰好得到完全相同的毫秒值时才会被重用；同一毫秒内共享目录的问题确实存在，但“陈旧目录被重用”在实际中不会发生）
- 子论断 F353.a 被否定：「临时目录以毫秒数命名，且未检查 mkdirs 的结果，因此并发导入可能共享并删除同一个目录，并且陈旧目录会被重用。」→ app/src/main/java/org/fcitx/fcitx5/android/utils/TempDir.kt:11（原因相同：陈旧目录需要完全相同的毫秒时间戳才会被重用）
- 子论断 F353.b 被否定：「以 currentTimeMillis 命名的临时目录可能被两次导入共享，并且崩溃后遗留的目录会被重用。」→ app/src/main/java/org/fcitx/fcitx5/android/utils/TempDir.kt:11（崩溃后遗留的目录不会被重用：之后的调用会产生更晚的时间戳）
- 提出者：CORRECTNESS-1@S45（NIT，8/10）、CORRECTNESS-2@S45（NIT，7/10）、GENERIC-1@S45（NIT，7/10）

#### F354 [NIT] TwinSeekBarPreferenceTest 构造偏好的方式与生产代码不同

- 来源：第一轮（整仓 @07d2778） · 共识：3/12 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/TwinSeekBarPreferenceTest.kt:32-46`、`ManagedPreferenceUi.kt:204`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/TwinSeekBarPreferenceTest.kt:32-46`、`ManagedPreferenceUi.kt:204`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/TwinSeekBarPreferenceTest.kt:45`；另见 `app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/TwinSeekBarPreferenceTest.kt:39`、`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/TwinSeekBarPreferenceTest.kt:46`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:204-205`

测试直接赋值 `default` 与 `secondaryDefault` 并总是先存好两个键，而生产代码 `ManagedPreferenceUi.TwinSeekBarInt.createUi` 调用 `setDefaultValue(default to secondaryDefault)`（`ManagedPreferenceUi.kt:204-205`）。键不存在时 androidx `dispatchSetInitialValue` 只在 `mDefaultValue != null` 时调用 `onSetInitialValue`，因此 Pair 解析与全新安装（无存储值、显示默认值）路径都未覆盖。
建议：改为 `setDefaultValue(6 to 0)`，并增加一个没有任何存储值的用例。

- 提出者：CORRECTNESS-1@S51（NIT，8/10）、CORRECTNESS-2@S51（NIT，8/10）、GENERIC-1@S51（NIT，8/10）

#### F355 [NIT] subtype 图标写死 @mipmap/ic_launcher

- 来源：第一轮（整仓 @07d2778） · 共识：3/11 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`res/xml/input_method.xml:11`、`app/build.gradle.kts:49`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`res/xml/input_method.xml:11`、`app/build.gradle.kts:49`）
- 位置：`app/src/main/res/xml/input_method.xml:11`；另见 `app/build.gradle.kts:49`、`app/build.gradle.kts:55`、`app/build.gradle.kts:49-56`

input_method.xml 的 subtype 在所有构建中使用 `@mipmap/ic_launcher`，而 manifest 和设置界面使用按构建类型通过 `resValue` 设置的 `@mipmap/app_icon`（app/build.gradle.kts:49-56）。
因此 debug 构建在系统输入法切换器中显示 release 图标，debug 与 release 同时安装时无法区分。
修复：改用 `@mipmap/app_icon`。

- 子论断 F355.b 无法验证：「子类型图标被硬编码为 @mipmap/ic_launcher，因此并排安装的 debug 和 release 版本会显示相同的子类型图标。」→ 可以并排安装（applicationIdSuffix ".debug"，AndroidAppConventionPlugin.kt:50），且两者都引用 ic_launcher，但 Android 是否以及在何处绘制子类型（subtype）图标属于无法获取的框架行为；在 API 34+ 上，已启用的子类型是 SubtypeManager 动态创建的子类型，创建时没有设置图标（SubtypeManager.kt:54-60）
- 子论断 F355.c 无法验证：「该子类型硬编码了 @mipmap/ic_launcher，因此 debug 构建在输入法切换器中显示的是 release 图标。」→ “输入法切换器会显示子类型图标”这一说法依赖于无法获取的框架 UI；API 34+ 的情况见 F355.b
- 提出者：CORRECTNESS-2@S46（NIT，8/10）、GENERIC-1@S46（NIT，7/10）、STYLE@G9（NIT，8/10）

#### F356 [NIT] Predictor 每次提交都对所有后继词 id 装箱

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 7/10 · 验证：✅ 已确认（`K/engine/lattice/Predictor.kt:77`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:77`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:76`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:81`

`typed.remove(word)` 即使 `typed` 为空，也会对每个模型后继（的 之后约 86,000 个）的 id 装箱。
建议：`typed.isEmpty()` 时跳过该调用；GENERIC-1@S9 另指出第 76 行的 `offered(word)` 在第 81 行对同一批词又运行一次，可复用第一次的结果。

- 提出者：CORRECTNESS-1@S9（NIT，7/10）、GENERIC-1@S9（NIT，7/10）、PERFORMANCE@G3（NIT，7/10）

#### F357 [NIT] 记录大小上限应用不一致，且丢弃记录时不报告

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 7/10 · 验证：✅ 已确认（`RecordStore.kt:105`、`RecordFormat.kt:40`、`RecordFormat.kt:72`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:163`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:105`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:71`

`append`（:105）丢弃超过 `MAX_RECORD` 的记录却不调用 `onError`，比较的是加帧后的大小（正文加 8 字节），而读取方只限制正文；压缩时的 `writeCounts`（:163）完全不检查上限，一条超过 64 KB 的计数记录会让之后的所有计数不可读，下次 `open` 会在那里截断其后的记录（:71）。
目前记录只有几个词长，几乎不可达；内存与磁盘也可能在无记录的情况下不一致。
建议：只在一处（`RecordFormat.record()` 或 `writeCounts()`）统一检查，并通过 `onError` 报告被拒的记录。

- 子论断 F357.b 被否定：「`writeCounts` 没有应用 `append` 所应用的 MAX_RECORD 限制，因此一条超大的习惯记录会导致之后的所有计数都无法读取。」→ 习惯记录不可能超大：KeyHabits.kt:47,63,124-125 将键的上限设为 64、文本的上限设为 32 个字符，因此一条 HABIT 记录（UserLog.kt:61-65）最多只有几百字节。
- 提出者：CORRECTNESS-1@S11（NIT，6/10）、CORRECTNESS-2@S11（NIT，7/10）、GENERIC-1@S11（NIT，6/10）

#### F358 [NIT] 日志压缩与fsync在输入线程的提交路径上执行

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 7/10 · 验证：✅ 已确认（`PinyinSession.kt:259`、`UserModel.kt:124`、`UserStore.kt:60`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:165`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:114`

`append` 越过 `compactAt` 时，在学习所在的线程（`PinyinSession.pick` 内）重写所有计数并调用 `fd.sync()`；PERFORMANCE 指出首次 seed 也会 fsync。类文档已说明这一选择，但在慢速闪存上会偶尔让一次按键卡顿。
建议：若在 trace 中可见，把压缩推迟到空闲时或移到后台 executor。

- 提出者：CORRECTNESS-2@S11（NIT，6/10）、GENERIC-1@S11（NIT，7/10）、PERFORMANCE@G3（NIT，5/10）

#### F359 [NIT] LINGUAS 未列出 de 和 es，对应 .po 从不编译

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（2 NIT, 1 NIT PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/cpp/po/LINGUAS:1`

app/src/main/cpp/po 下有 de.po 和 es.po，但 LINGUAS 未列出这两种语言；fcitx5_install_translation 和 msgfmt --desktop -d <podir> 只处理 LINGUAS 中的语言，所以它们从不被安装，也不用于 .conf 翻译（例如 es.po 中 Android Frontend、Android Keyboard 的译名到不了 addon 的 Name）。应用自带 values-de/values-es 的 Android 字符串，这些用户看到的原生字符串回退为英文。
三票都指出这是自 fork 以来未变的上游问题，但只有 CORRECTNESS-1@S27 标为 PRE-EXISTING。
修复：在 LINGUAS 中加入 de 和 es，或删除这两个文件。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S27（NIT PRE-EXISTING，8/10）、CORRECTNESS-2@S27（NIT，5/10）、GENERIC-1@S27（NIT，7/10）

#### F360 [NIT] 搜狗词库转换先 waitFor 再读输出，可能挂起

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:142`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:142`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:137`

`PinyinDictManager.sougouDictConv`（:137-142）在读取转换器的 stderr 之前就调用 `waitFor()`，而且从不读取 stdout；若 `scel2org5` 写出的内容超过管道缓冲区，导入会在加载对话框后面挂起。PERFORMANCE@G7 还指出 Process 的流从不关闭，每次转换泄漏 3 个文件描述符。这是上游代码；GENERIC-1@S31 说明自己没有核实 scel2org5 的输出量。
修复：在 `waitFor` 之前读完两个流（或使用 `redirectErrorStream(true)`），或加超时；在 finally 中关闭流并调用 `destroy()`。

- 提出者：CORRECTNESS-1@S31（NIT，7/10）、GENERIC-1@S31（NIT，5/10）、PERFORMANCE@G7（NIT，7/10）

#### F361 [NIT] chinese-addons 仍依赖 fcitx5-lua，但已无模块使用

- 来源：第一轮（整仓 @07d2778） · 共识：3/6 位 reviewer（3 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:17-21`）
- 当前状态（5881d31）：仍存在：第一轮已验证，所在文件此后没有改动
- 位置：`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:17`；另见 `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:17-21`、`lib/fcitx5-chinese-addons/build.gradle.kts:54`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:41`

只有已移除的 pinyin 输入法使用 Lua 加载器（LuaAddonLoader）；CMakeLists.txt 17-21 行的 find_package(fcitx5-lua)/Fcitx5ModuleLuaAddonLoader 以及 build.gradle.kts:54 的 :lib:fcitx5-lua 依赖在该模块中已是死代码。应用仍通过自己的依赖发布 luaaddonloader，所以移除不影响运行时；CORRECTNESS-2@S27 与 GENERIC-1@S27 注明 submodule 未检出，未能确认剩余模块都不用它。
CORRECTNESS-2@S27 另指出 line 41 重复了 line 15 的 find_package(Fcitx5Module)，而本地 Find 模块忽略 COMPONENTS。
修复：删除这些行和该 Gradle 依赖，顺带去掉一处构建顺序耦合。

- 提出者：CORRECTNESS-1@S27（NIT，6/10）、CORRECTNESS-2@S27（NIT，6/10）、GENERIC-1@S27（NIT，6/10）

#### F362 [NIT] applySelectionOffset 的 KDoc 已过时

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 10/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:282`、`AutoPairs.kt:51`、`CommonKeyActionListener.kt:143`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:282`、`AutoPairs.kt:51`、`CommonKeyActionListener.kt:143`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSession.kt:282`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:51`

`EditingSession.kt:282` 的 KDoc 仍说唯一调用方是退格滑动且只移动起点，但 `AutoPairs.type`（AutoPairs.kt:51）现在也以 `applySelectionOffset(1, 1)` 调用它来跨过闭合符。
建议更新这段注释。

- 提出者：CORRECTNESS-1@S14（NIT，10/10）、GENERIC-1@S14（NIT，9/10）

#### F363 [NIT] FcitxInputMethodService 中有未使用的 import

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 10/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:62`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:62`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:62`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:67`

FcitxInputMethodService.kt:62 的 `reloadPinyinDict` 和 :67 的 `PinyinDictManager` 在该文件中没有被引用。
修复：删除这两个 import。

- 提出者：CORRECTNESS-1@S33（NIT，10/10）、STYLE@G7（NIT，9/10）

#### F364 [NIT] voice 模型体积 ~240 MB 与 README ~180 MB 不符

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/build.gradle.kts:62`、`README.md:78`、`README.md:195`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/build.gradle.kts:62`、`README.md:78`、`README.md:195`）
- 位置：`app/build.gradle.kts:62`；另见 `README.md:78`、`README.md:195`

app/build.gradle.kts 第 62 行注释写「~240 MB of models」，README.md:78 和 README.md:195 写 ~180 MB。
建议：实测一次，两处使用同一个数字。STYLE@G1（G1-17）与 CORRECTNESS-2@S1（G1-16）在各自的其他发现中也提到了这一点。

- 提出者：CORRECTNESS-1@S1（NIT，8/10）、GENERIC-1@S1（NIT，9/10）

#### F365 [NIT] unit_test.yml 注释引用不存在的 publish 工作流

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`.github/workflows/unit_test.yml:9`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/unit_test.yml:9`）
- 位置：`.github/workflows/unit_test.yml:9`

第 9 行注释提到 `publish` 工作流，但 `.github/workflows` 下只有 emulator_test、nix、pull_request 和 unit_test。
建议：删掉注释中的 publish。STYLE@G1（G1-19）与 CORRECTNESS-2@S1（G1-16）在各自的其他发现中也顺带指出了这一点。

- 提出者：CORRECTNESS-1@S1（NIT，9/10）、GENERIC-1@S1（NIT，9/10）

#### F366 [NIT] latin.py 输出头部写死 FineWeb 分片名

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lexicon/tools/latin.py:81-82`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/tools/latin.py:81-82`）
- 位置：`lexicon/tools/latin.py:81`

latin.py:81 生成的头部总写 shards 000_00002 和 000_00003，无论实际读入的是哪个 counts 文件。
该头部是 latin.words 的 ODC-By 署名记录，在其它分片上重新生成时会记录错误来源。
建议：把分片名作为参数传入，或（GENERIC-1@S4）由 counts 文件携带。

- 提出者：CORRECTNESS-1@S4（NIT，9/10）、GENERIC-1@S4（NIT，8/10）

#### F367 [NIT] 两个 Python 脚本各自解析 Syllables.kt 源码

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lexicon/tools/apply.py:22`、`latin.py:35`、`Syllables.kt:23`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/tools/apply.py:22`、`latin.py:35`、`Syllables.kt:23`）
- 位置：`lexicon/tools/apply.py:22`；另见 `lexicon/tools/apply.py:16`、`lexicon/tools/latin.py:35`、`lexicon/tools/latin.py:33`、`lexicon/tools/latin.py:53`

apply.py（:16/:22）与 latin.py（:33/:35）都在 Syllables.kt 源码中截取 `'SPELLINGS ='` 与 `'private val spellings'` 之间的内容，重命名该私有属性会同时破坏两个工具。
STYLE@G2 还指出跳过注释的 TSV 读取写了三遍（`apply.words`/`suggested`、`batches.rows`、latin.py:53 内联）；CORRECTNESS-1@S4 还指出 latin.py 和 latin_counts.py 有 shebang 却没有可执行位（mode 100644）。
建议：共享一个辅助模块，或在 Syllables.kt 旁生成音节列表（STYLE@G2：让 ime-dict-tool 打印音节列表）。

- 提出者：CORRECTNESS-1@S4（NIT，9/10）、STYLE@G2（NIT，9/10）

#### F368 [NIT] NewWords 文档称按页采样邻字，实际按行

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:112`、`Main.kt:383`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:112`、`Main.kt:383`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:112`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:383`

NewWords.kt:112 的 `page` 计的是 `add()` 调用次数，而 Main.kt:383 对一页的每一行调用一次 `add()`，所以步长采的是每第 n 行，而不是类文档所说的每第 n 页。
建议：改文档说明按行，或改计数器（CORRECTNESS-2@S5：或传入整页）。GENERIC-1@S5 在 G2-13 的发现中也提到这一点。

- 提出者：CORRECTNESS-1@S5（NIT，9/10）、CORRECTNESS-2@S5（NIT，9/10）

#### F369 [NIT] --cutoffs 解析宽松，非数字字段被静默丢弃

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:232`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:232`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:232`

Main.kt:232 用 `mapNotNull` 解析 `--cutoffs`，`2,x,3` 被当成 2,3（GENERIC-1@S5：`2,3,` 也一样），与其它选项拒绝坏值的做法不一致。
建议：用 `toIntOrNull()` 映射，任一字段为 null 或不是数字就拒绝该值。

- 提出者：CORRECTNESS-1@S5（NIT，9/10）、GENERIC-1@S5（NIT，9/10）

#### F370 [NIT] Phrases 与 Readings 算法藏在 851 行的 Main.kt 中

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:485-630`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:485-630`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:491`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:540`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:412`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:444`

`Phrases`（Main.kt:491-529）和 `Readings`（:540-630，读音选择 DP）约 170 行私有启发式写在 CLI 文件里，而同类的 NewWords、Surprise、PageCleaner 等各有自己的文件和测试类；这两者只能通过 `runCli`/MainTest 用手写 TSV 间接测试。
STYLE@G2 还指出 `pack`（:412）在同一个行循环里用 `if (lexicon != null) … return@forEachRow`（:444）切换两种模式。
建议：把两个类移到各自文件并设为 `internal`，像 `TableWordsTest` 测 `wordsByUse` 那样直接测试；STYLE@G2 另建议把 `pack` 拆成人工词模式和阈值模式。

- 提出者：CORRECTNESS-1@S5（NIT，7/10）、STYLE@G2（NIT，9/10）

#### F371 [NIT] PageCleanerTest 注释的汉字数 254 应为 270

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:39`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:39`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:39`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:28`

PageCleanerTest.kt:39 的注释写「10 in 254 Han characters」，但每行 23+4 个汉字、共 10 行，页面实为 270 个汉字；断言本身仍成立。
GENERIC-1@S6 还指出 :28 的「once more than the pages counted」不符合该块：页脚被计两次，低于 minRepeats 3。CORRECTNESS-1@S6 在 G2-23 的发现中也提到 :39。建议修正注释中的数字。

- 子论断 F371.b 被否定：「PageCleanerTest 的注释写的是 254 个汉字，而页面实际有 270 个；第 28 行的注释没有描述它所在的代码块。」→ lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleanerTest.kt:28-31：该注释确实描述了它所在的代码块。页脚在两个页面上被计数（pages.take(2)，:30），clean 调用是第三次遇到它，而它的计数仍低于 minRepeats 3，因此它被保留，与 :31 的断言一致。只有 254 那一半成立。
- 提出者：CORRECTNESS-2@S6（NIT，9/10）、GENERIC-1@S6（NIT，9/10）

#### F372 [NIT] ExamplesTest 中的 delete() 调用多余

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ExamplesTest.kt:57`、`NewWordsTest.kt:58`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ExamplesTest.kt:57`、`NewWordsTest.kt:58`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ExamplesTest.kt:57`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:57`

ExamplesTest.kt:57 的 `File(out.path).delete()` 没有实际作用：`out` 本身已是 File，TemporaryFolder 也会自动清理。
GENERIC-1@S6 建议若本意是「用法错误不写文件」，应在 exit-2 那次运行前删除 `out` 并在之后断言 `!out.exists()`；CORRECTNESS-2@S6 另指出 NewWordsTest.kt:57 有多余空行。

- 提出者：CORRECTNESS-2@S6（NIT，9/10）、GENERIC-1@S6（NIT，8/10）

#### F373 [NIT] LayerPrior.parse 接受 NaN 与 Infinity

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:133`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:33`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:133`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:33`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:133`

`toFloatOrNull` 接受 `NaN` 与 `Infinity`，且 `parse` 在 `init` 的 `isFinite` 检查之后才设置值；拼错的评测配置会产生 NaN 分数，使解码器基于 `<`/`<=` 的 beam 排序与剪枝失去意义。GENERIC-1@S9 指出只有评测工具调用此方法。
建议：加入 `isFinite()` 检查（如 `require(value.isFinite())`）。

- 提出者：CORRECTNESS-1@S9（NIT，9/10）、GENERIC-1@S9（NIT，9/10）

#### F374 [NIT] StringEscapeTest 中 assertEquals 参数颠倒

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:28`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:28`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:28`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/core/StringEscapeTest.kt:35`

第 28 行按 (actual, expected) 传参，失败消息会把期望值与实际值标反。
建议：改为 `assertEquals(it.second, FcitxUtils.escapeForValue(it.first))`，与第 35 行的 `testUnescapeForValue` 一致。CORRECTNESS-2@S15 与 TESTING@G3 的相同表述合在其包名发现中，计入 G3a-46。

- 提出者：CORRECTNESS-1@S15（NIT，9/10）、GENERIC-1@S15（NIT，9/10）

#### F375 [NIT] EnginesTest 有未使用的导入

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已修复（第二轮在 5881d31 上确认）
- 当前状态（5881d31）：✅ 已修复（第二轮已验证）：该缺陷已消失，由 5881d31e 修复。该提交从 EnginesTest.kt 中删除了 `assertNotEquals`、`CompletableFuture` 和 `Future` 这三个导入（基线 :22、:32、:33），这三个名称都已不再出现在 EnginesTest.kt 中。引用的另一处位置 PinyinSessionRefineTest.kt:7（一个未使用的 `NO_WORD` 导入，本次改动未涉及）不属于本论断的范围。
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:22`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:32`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:33`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:7`

EnginesTest.kt 中的 `assertNotEquals`（第 22 行）、`CompletableFuture`（第 32 行）、`Future`（第 33 行）均未使用。
建议：删除这些导入；TESTING@G3 的同一条发现还列出 PinyinSessionRefineTest.kt 中拆分 PinyinSessionTest 后遗留的十余个未使用导入。

- 提出者：GENERIC-1@S16（NIT，9/10）、TESTING@G3（NIT，9/10）

#### F376 [NIT] PinyinSegmenterTest中重复的断言

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinSegmenterTest.kt:89`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinSegmenterTest.kt:89`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/PinyinSegmenterTest.kt:97`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/PinyinSegmenterTest.kt:92`

第 97 行与第 92 行在同一输入 `zhongg` 上断言完全相同的内容，而上方注释「but not where zh starts a syllable」暗示本意是另一个用例。
建议：删除该行，或改用原本想测的输入。

- 提出者：CORRECTNESS-1@S17（NIT，9/10）、GENERIC-1@S17（NIT，9/10）

#### F377 [NIT] RerankerTest注释称单位为nats，实际为log10

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`RerankerTest.kt:32-33`、`ZstdTest.kt:120-125`、`Zstd.kt:135`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`RerankerTest.kt:32-33`、`ZstdTest.kt:120-125`、`Zstd.kt:135`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/RerankerTest.kt:32`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/ZstdTest.kt:120`

注释说「both in nats」，但 `gain` 已除以 `ln(10f)`，单位是解码器的 log10，正是与 `scores` 比较的单位。
CORRECTNESS-1 另指出 `ZstdTest.theChecksumIsXxh64sLowHalf`（ZstdTest.kt:120）断言的是完整 64 位 XXH64 值，那里没有测试低半部比较。
建议：修正措辞（含测试名）。

- 提出者：CORRECTNESS-1@S17（NIT，8/10）、GENERIC-1@S17（NIT，9/10）

#### F378 [NIT] LibimeFilesTest从ZstdTest借用测试辅助函数

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`LibimeFilesTest.kt:8-9`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`LibimeFilesTest.kt:8-9`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFilesTest.kt:8`

LibimeFilesTest 从 `ZstdTest` 的伴生对象导入 `resource` 和 `sha256`，使两个测试类相互依赖。
建议：放到一个小的共享测试固件对象中，让两个测试类相互独立。

- 提出者：CORRECTNESS-1@S17（NIT，9/10）、GENERIC-1@S17（NIT，9/10）

#### F379 [NIT] JSON深度与数量上限未在边界值处测试

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`SentenceModelTest.kt:216-218`、`Json.kt:30-31`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`SentenceModelTest.kt:216-218`、`Json.kt:30-31`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:216`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModelTest.kt:218`

深度从 0 计数，`MAX_DEPTH + 1` 层嵌套会被接受；测试只检查 `MAX_DEPTH`（通过）和 `MAX_DEPTH + 2`（失败），值数量（第 218 行）也只测了超过上限的 `MAX_VALUES + 2`，任何一处的差一错误都仍能通过。
建议：测试 `MAX_DEPTH + 1` 层嵌套和恰好 `MAX_VALUES` 个值，固定两个边界。

- 提出者：CORRECTNESS-2@S17（NIT，9/10）、GENERIC-1@S17（NIT，9/10）

#### F380 [NIT] assertEquals的期望值与实际值颠倒

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:376`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:376`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:376`

`assertEquals(first.candidates.size, 1)` 把期望值和实际值传反了，失败时消息会读反。
建议：改为 `assertEquals(1, first.candidates.size)`。

- 提出者：CORRECTNESS-2@S18（NIT，9/10）、GENERIC-1@S18（NIT，9/10）

#### F381 [NIT] 测试名称与自身断言矛盾：拼音条目并非排在编码之后

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:592`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:592`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:592`

`aTablesOwnPinyinEntriesComeAfterItsCodes` 断言 `[工, 啊, 式, 蛙]`，拼音条目 啊 排在编码 式 和 蛙 之前，实际成立的只是「从不排第一」。
建议：把测试改名以符合实际行为。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）、CORRECTNESS-2@S19（NIT，9/10）

#### F382 [NIT] SharedWordsTest中过时、混乱的注释

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWordsTest.kt:46`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWordsTest.kt:46`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWordsTest.kt:46`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:427`

SharedWordsTest.kt:46 的注释提到不在测试数据中的 好好/vbvb，也没有解释 `x你`；列表中实际是编码为 vbwq 的 好你。
CORRECTNESS-1 另指出 TableSessionTest.kt:427 的注释描述的是 `picked` 而非 `learning`。
建议：重写或删除该注释。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）、GENERIC-1@S19（NIT，9/10）

#### F383 [NIT] dictionary()构建器在多个测试类中重复

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:28`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:28`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:28`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:34`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/SharedWordsTest.kt:20`

带三条 组词规则 的 `dictionary(...)` 构建器（以及 `type` 辅助函数）分别出现在 TableSessionTest.kt:34、TableUserTest.kt:28 和 SharedWordsTest.kt:20（变体）。
建议：移入模块已有的 `src/testFixtures`，避免规则集逐渐不一致。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）、CORRECTNESS-2@S19（NIT，9/10）

#### F384 [NIT] KeyHabitsTest 只测超限长度，未测边界值

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabitsTest.kt:87-89`、`KeyHabits.kt:47`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabitsTest.kt:87-89`、`KeyHabits.kt:47`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabitsTest.kt:86`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabitsTest.kt:87`

测试只用 65 字符的键和 33 字符的文本（KeyHabitsTest.kt:86-87），没有测可接受的 64 与 32；把 `KeyHabits.learn` 中的 `<=` 改成 `<` 不会被发现。
建议加一个保留 64 字符键和 32 字符文本的用例，固定 `<= MAX_KEY` 与 `> MAX_TEXT` 这两处比较。

- 提出者：CORRECTNESS-1@S20（NIT，9/10）、GENERIC-1@S20（NIT，8/10）

#### F385 [NIT] 共享测试夹具放在 UserModelTest 的 companion 中

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:22-53`、`WordPackTest.kt:8`、`WordListsTest.kt:23`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:22-53`、`WordPackTest.kt:8`、`WordListsTest.kt:23`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:22`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordListsTest.kt:23`

LibimeImportTest、UserScorerTest、UserStoreTest 和 WordPackTest 从 UserModelTest 的 companion object 导入 `data`、`model`、`entry`、`syl`、`inTrie`，WordListsTest.kt:23 又重新声明了 `entry`。
ime-core 已有 `testFixtures` 源集（存放 FakeEditor），建议把这些辅助函数移到共享的测试数据对象中。

- 提出者：GENERIC-1@S20（NIT，8/10）、STYLE@G4（NIT，9/10）

#### F386 [NIT] AutoPairsTest 提交后未回报光标，后半段断言无法失败

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:114-119`、`CursorTracker.kt:40`、`AutoPairs.kt:79`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:114-119`、`CursorTracker.kt:40`、`AutoPairs.kt:79`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:115`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairsTest.kt:112`

测试在 `type("）")` 之后从未回报编辑器光标位于 2；随后 `setSelection(1,1)` 的回报命中 `CursorTracker.consume` 的快速路径（回报等于 `current`），陈旧的预测 2 保留下来，会话与编辑器的位置不一致。
两票对后果的描述不同：CORRECTNESS-1@S21 认为 `pairs.backspace` 因位置不符直接返回，所以 `"|））"` 无论配对是否已清除都成立；GENERIC-1@S21 认为最后的退格只是靠「revealed too little」回退路径才成功。
建议在每次提交后回报 `e.selectionStart`（先报告 (2,2)），或复用 `EditingSessionTest` 的 `assertInStep`，并断言会话与编辑器一致。

- 子论断 F386.b 被否定：「驱动假编辑器时没有上报提交后的光标，因此过时的预测得以保留，最后一次退格只是借助“显示文本过少”（revealed-too-little）的回退逻辑才成功。」→ 过时的预测确实保留了下来（CursorTracker.kt:40），退格也确实走了 EditingSession.kt:421（textBeforeCursor(2) 返回 "（"，1 < 2），但如果没有该分支，EditingSession.kt:422 的 CodePoints.lengthOfLast("（", 1) 同样返回 1，因此无论哪种情况退格都会成功
- 提出者：CORRECTNESS-1@S21（NIT，9/10）、GENERIC-1@S21（NIT，8/10）

#### F387 [NIT] CodePointsPropertyTest 的良构生成器去掉了合法代理对

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/CodePointsPropertyTest.kt:79-80`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/CodePointsPropertyTest.kt:79-80`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/CodePointsPropertyTest.kt:79`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/CodePointsPropertyTest.kt:76`

`filterNot(Char::isSurrogate)` 不仅删除孤立的代理半字，也删除完整的合法代理对，剩下的代理对只有末尾追加的 👋；字母与代理对从不交错，开头和中间的代理对从未与 `codePointCount` 对照检查。
修复建议略有不同：CORRECTNESS-2@S21 建议把孤立代理替换为字母并保留合法代理对；GENERIC-1@S21 建议直接按位置在字母与 👋 之间选择来生成良构文本。

- 提出者：CORRECTNESS-2@S21（NIT，9/10）、GENERIC-1@S21（NIT，9/10）

#### F388 [NIT] KeyGestureRecognizerTest 辅助函数的文档注释有误

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:20-26`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:20-26`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:20`

辅助函数的注释说「everything enabled unless a test says otherwise」，但它的默认值只开启 `swipe`，`longPress`、`repeat`、`swipeRepeat`、`doubleTap` 都是关闭的（KeyGestureRecognizerTest.kt:20）。
建议改为「swipe enabled, everything else off unless a test says otherwise」。

- 提出者：CORRECTNESS-1@S22（NIT，9/10）、GENERIC-1@S22（NIT，9/10）

#### F389 [NIT] SettingsSearchTest 的注释误述了按键声音的匹配情况

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearchTest.kt:16`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearchTest.kt:16`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearchTest.kt:30`

注释说 按键声音 「only under it」含有 空格，但它的标题、摘要（按键时播放声音）和页面（虚拟键盘）都不含 空格，根本不匹配；断言 `[3, 2]` 本身是对的（SettingsSearchTest.kt:30）。
建议删去这个从句，或改为说明它不匹配。

- 提出者：CORRECTNESS-1@S22（NIT，9/10）、GENERIC-1@S22（NIT，9/10）

#### F390 [NIT] `everySetParses` 漏掉 predict/chat.tsv

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:68`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:68`）
- 位置：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:69`；另见 `lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/EvalSetTest.kt:68`

EvalSetTest 的 `everySetParses` 只列出 `data/*.tsv`（`listFiles` 不递归），而 data/predict/chat.tsv 格式不同，从未被测试解析，其中格式错误的行不会被发现。
修复：增加一个针对该提交文件的 `PredictRun.parse` 断言。CORRECTNESS-1@S24 在 G5-23 中也提到此缺口。

- 提出者：TESTING@G5（NIT，9/10）、GENERIC-1@S24（NIT，8/10）

#### F391 [NIT] host-eval.sh 在单核机器上得到 -j0

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/sherpa-onnx/host-eval.sh:17`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/host-eval.sh:17`）
- 位置：`lib/sherpa-onnx/host-eval.sh:17`；另见 `lib/sherpa-onnx/host-eval.sh:16`

-j$(( $(nproc) / 2 )) 在单核机器上计算为 -j0，CORRECTNESS-2@S28 指出 Ninja 把 -j0 当作不限并行。两票都指出其上方第 16 行的注释讲的是运行解码器，而不是构建。
修复：把并行数夹到至少 1（如 $(( n > 1 ? n / 2 : 1 ))），并把注释移到用法说明处。

- 子论断 F391.b 无法验证：「host-eval.sh 中的任务数在单核主机上会得到 -j0，而 Ninja 将其解读为不限制。」→ CMake 的 `cmake --build -j0` 在 Ninja 接收到之前如何处理 0（CMake/Ninja 不在仓库中）
- 提出者：CORRECTNESS-1@S28（NIT，5/10）、CORRECTNESS-2@S28（NIT，9/10）

#### F392 [NIT] 束搜索中写入 -inf 时没有越界检查

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:134`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:134`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:134`

youmo 补丁向 p_logprob[h * vocab_size + token] 写入 -inf，但短语 token id 来自 tokens.txt，行宽 vocab_size 来自 joiner；两者不一致时会写出行尾，在最后一行时写出缓冲区，造成堆越界。CORRECTNESS-2@S52 指出上游热词图只读取分数，这是补丁首次写入 logits。资源经 SHA-256 固定，风险小，属防御性修复。
修复：跳过 token >= vocab_size 的 token（if (token < vocab_size)），或在构建屏蔽图时检查一次。

- 提出者：CORRECTNESS-1@S52（NIT，6/10）、CORRECTNESS-2@S52（NIT，9/10）

#### F393 [NIT] 反向依赖查询 API 没有生产调用方

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt:40`、`Fcitx.kt:62`、`Fcitx.kt:60`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt:40`、`Fcitx.kt:62`、`Fcitx.kt:60`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/FcitxAPI.kt:40`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/AddonDependencyGraph.kt:35`

全仓库 grep 只在 `Fcitx`、`FakeFcitxAPI` 和 `AddonDependencyGraphTest` 中找到 `getAddonReverseDependencies`（FcitxAPI.kt:40），新增的 `AddonDependencyGraph` 类、它的缓存和 `addonGraph` 字段都只为测试服务。CORRECTNESS-1@S49 同时指出其中 `level == 1` 的判断误读了 `level`（bfs 传入的是出队计数）。
修复：删除这个 API，或在 KDoc 中写明预期的调用方；CORRECTNESS-1@S49 给出的另一选项是按深度判断，并为传递依赖者的可选依赖者加测试。

- 提出者：GENERIC-1@S29（NIT，9/10）、CORRECTNESS-1@S49（NIT，8/10）

#### F394 [NIT] PagedCandidatesUi 回收时未清除长按监听器

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PagedCandidatesUi.kt:105`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PagedCandidatesUi.kt:105`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PagedCandidatesUi.kt:105`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PagedCandidatesUi.kt:84`

`PagedCandidatesUi.kt:105` 的 `onViewRecycled` 清除了点击监听器，却没有清除 :84 设置的长按监听器，回收的 holder 会一直持有引用旧 `candidate`/`position` 的 lambda，直到重新绑定；GENERIC-1@S35 指出下次绑定会覆盖它，没有功能上的影响。横向 adapter 和展开窗口都会清除这两个监听器。
修复：加上 `setOnLongClickListener(null)`。

- 提出者：CORRECTNESS-1@S35（NIT，7/10）、GENERIC-1@S35（NIT，9/10）

#### F395 [NIT] assertEquals 的期望值与实际值颠倒

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/core/AddonDependencyGraphTest.kt:70`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/core/AddonDependencyGraphTest.kt:70`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/core/AddonDependencyGraphTest.kt:70`

AddonDependencyGraphTest.kt:70 的 `assertEquals` 参数顺序反了，测试失败时消息会把期望值和实际值说反。
修复：交换两个参数。

- 提出者：CORRECTNESS-2@S49（NIT，9/10）、GENERIC-1@S49（NIT，9/10）

#### F396 [NIT] PopupPreset 中希伯来文/阿拉伯文条目重复或无效

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PopupPreset.kt:11`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PopupPreset.kt:11`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:113`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:71`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:115`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:116`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:117`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:118`、`app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupPreset.kt:119`

hashMapOf 对重复键只保留最后一个值：י 丢失 ײַ，ח 丢失 ח׳；CORRECTNESS-2 还指出 צ׳ 以两个码点为键，永远匹配不到键标签，ه 映射到自身。
两者都指出 Upper case Cyrillic 与 Cyrillic 的分节注释写反了。两者引用的行号略有不同。
建议：每个键合并为一个数组，删除永远无法匹配的条目。

- 提出者：CORRECTNESS-2@S39（NIT，9/10）、GENERIC-1@S39（NIT，9/10）

#### F397 [NIT] 导出失败时临时文件 customphrase.export 残留

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinCustomPhraseFragment.kt:270-273`、`PinyinCustomPhraseFragment.kt:247`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinCustomPhraseFragment.kt:270-273`、`PinyinCustomPhraseFragment.kt:247`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:270`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:273`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:247`

导出用的临时文件 customphrase.export 含用户的短语，只在成功时删除（:273），复制失败时它留在缓存目录（STYLE 指出位于应用私有缓存）。
建议：在 finally 中删除。GENERIC-1 另指出导入用 readBytes()（:247）整体读取所选文件，没有大小上限。

- 提出者：GENERIC-1@S41（NIT，9/10）、STYLE@G8（NIT，8/10）

#### F398 [NIT] 词典启用/移动/删除在主线程做文件 I/O 且失败不记日志

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinDictionaryFragment.kt:147-153`、`PinyinDictionaryFragment.kt:173`、`TextDictionary.kt:43-50`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinDictionaryFragment.kt:147-153`、`PinyinDictionaryFragment.kt:173`、`TextDictionary.kt:43-50`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:149`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:255`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:275`

启用或停用词典会重命名文件，移动或删除会写层文件（setIntoNew/delete），都在主线程运行（:149-173）；文件虽小，仍可像 rebuild() 一样放到 Dispatchers.IO。
GENERIC-1 另指出 .isSuccess 丢弃了失败原因，只显示通用 toast，PinyinCustomPhraseFragment.kt 导入/导出的 catch（:255、:275）也只 toast 不记日志，与 saveConfig 的 Timber.w 不一致，建议在这些失败路径加 Timber.w(e, ...)。

- 提出者：CORRECTNESS-2@S41（NIT，7/10）、GENERIC-1@S41（NIT，9/10）

#### F399 [NIT] 每次刷新都完整读取所有已导入词典

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinDictionaryFragment.kt:92`、`PinyinDictManager.kt:132-134`、`PinyinDictionaryFragment.kt:149`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinDictionaryFragment.kt:92`、`PinyinDictManager.kt:132-134`、`PinyinDictionaryFragment.kt:149`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:92`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:149`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:152`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:173`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:249`

每次启用/停用、移动或删除都会重跑 wordCount，逐行读取每个词典文件；建议按文件长度和 mtime 缓存计数。
PERFORMANCE 另指出 :149、:152、:173 在主线程重命名或写文件，importIntoUser（:249）把整个转换后的词典作为一个 List<String> 放在内存中，流式处理可避免大 .scel 带来的内存峰值。

- 跨模块组合并：G8a-72、G7-131（词库列表每次重建都按词典逐一重读 .new-words，并全量读取词数）
- 提出者：PERFORMANCE@G8（NIT，7/10）、PERFORMANCE@G7（NIT，9/10）

#### F400 [NIT] FcitxKeyPreference 用错误的 styleable 读取属性

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 NIT, 1 NIT PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`FcitxKeyPreference.kt:24-26`、`attrs.xml:12`、`attrs.xml:20`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`FcitxKeyPreference.kt:24-26`、`attrs.xml:12`、`attrs.xml:20`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/FcitxKeyPreference.kt:24`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PreferenceScreenFactory.kt:201`

构造函数获取的是 R.styleable.DialogSeekBarPreference 的属性，却读取 FcitxKeyPreference_useSimpleSummaryProvider 索引，两者不匹配；目前没有影响，只因唯一调用方 PreferenceScreenFactory 传入 attrs = null（PreferenceScreenFactory.kt:201）。
建议：改用 R.styleable.FcitxKeyPreference。CORRECTNESS-2 标为 PRE-EXISTING，GENERIC-1 未标。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-2@S41（NIT PRE-EXISTING，8/10）、GENERIC-1@S41（NIT，9/10）

#### F401 [NIT] `flagList` 的未用标注写死九键说明与全角括号

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:301-302`、`values/strings.xml:453`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:301-302`、`values/strings.xml:453`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:302`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:301-303`

`Flags.unused` 设计为通用选项，但 `flagList` 总是附加 `R.string.im_not_for_t9` 并用全角括号 `（…）` 包裹，英文界面会显示 `u ↔ v（Not used on 9 keys）`。
建议：像 `Toggle.unused` 一样在 `Flags` 上携带 `@StringRes` 说明，并用本地化格式字符串组装标签。

- 提出者：GENERIC-1@S43（NIT，9/10）、STYLE@G8（NIT，9/10）

#### F402 [NIT] app 测试中的 libime 数据是 ime-core 的重复副本

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/resources/libime/README.md:1`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/resources/libime/README.md:1`）
- 位置：`app/src/test/resources/libime/README.md:1`

`extra.dict` 与 `db.main.dict`（148 KB）与 `lib/ime-core/src/test/resources/libime` 下的文件逐字节相同（SHA-256 一致），两份副本可能逐渐不一致。
建议：`app` 已依赖 ime-core 的 `testFixtures`，可把两份文件移到 `lib/ime-core/src/testFixtures/resources/libime`（`/libime/...` 类路径不变），或通过 resources `srcDir` 直接指向 ime-core 的文件。

- 提出者：CORRECTNESS-1@S51（NIT，7/10）、CORRECTNESS-2@S51（NIT，9/10）

#### F403 [NIT] Robolectric 配置注释误称全部测试用 SDK 35

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/resources/robolectric.properties:1`、`ContentResolverTest.kt:17`、`EditingSessionOverRealEditorTest.kt:248`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/resources/robolectric.properties:1`、`ContentResolverTest.kt:17`、`EditingSessionOverRealEditorTest.kt:248`）
- 位置：`app/src/test/resources/robolectric.properties:1`；另见 `app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:17`、`app/src/test/java/org/fcitx/fcitx5/android/input/editing/EditingSessionOverRealEditorTest.kt:248`、`app/src/test/java/org/fcitx/fcitx5/android/input/editing/InputEditorContractTest.kt:339`

`robolectric.properties` 第 1 行注释说每个 Robolectric 测试都在 SDK 35 运行，但 `ContentResolverTest.kt:17`、`EditingSessionOverRealEditorTest.kt:248`、`InputEditorContractTest.kt:339` 固定了 `sdk = [23]`。
建议：注释改为默认 SDK；CORRECTNESS-2 还建议在每个 SDK 23 固定处加一行原因（ContentResolverTest 未说明，推测为 minSdk）。

- 提出者：CORRECTNESS-2@S51（NIT，9/10）、GENERIC-1@S51（NIT，9/10）

#### F404 [NIT] ToolFingerprint 中「工具不用反射」的说法不成立

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:16`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:78`、`EngineDataPlugin.kt:117`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:16`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:78`、`EngineDataPlugin.kt:117`）
- 位置：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:16`；另见 `build-logic/convention/src/main/kotlin/ToolFingerprint.kt:34`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:78`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:104`

KDoc 称工具没有反射，但 `dicttool/WebText.kt:78`（GENERIC-1@S2 另指出 :104）通过使用 ServiceLoader 的 `DriverManager` 加载 DuckDB 驱动，指纹因此不包含该驱动。
两票都认为目前无害：构建只运行 `pinyin`、`table`、`strokes`，它们不会走到 `mix`。
建议：把这一说法限定到这几个子命令，避免以后的改动依赖它；GENERIC-1@S2 另建议维护一个未命中集合，因为找不到的类（JDK 类）不会加入 `seen`，会被每个引用它的类重复入队查找（第 34 行）。

- 提出者：CORRECTNESS-1@S2（NIT，8/10）、GENERIC-1@S2（NIT，8/10）

#### F405 [NIT] 解码出的签名 keystore 以默认文件权限写入

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 NIT, 1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:83-91`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:83-91`）
- 位置：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:83`；另见 `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:83-85`

从 `SIGN_KEY_BASE64` 解码的 release keystore 由 `File.createTempFile` 按 umask（通常 0644）写入 `build/`，构建期间同机其他用户可读；CORRECTNESS-2@S2 指出它会留到 JVM 退出，`org.gradle.daemon=false` 缩短了留存时间。
CORRECTNESS-1@S2 另指出严格的 `Base64.decode` 拒绝 `base64` 未加 `-w0` 时的换行输出，构建会静默产出未签名的 APK，`Base64.Mime` 则可接受。两票都注明继承自上游，但只有 CORRECTNESS-2@S2 标为 PRE-EXISTING。
建议：改用 `Files.createTempFile`（POSIX 下为 0600）或设为仅属主权限，或在构建结束钩子中删除该文件。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S2（NIT，8/10）、CORRECTNESS-2@S2（NIT PRE-EXISTING，8/10）

#### F406 [NIT] README「数据与许可」漏列 rime-stroke 等组件

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`README.md:50-56`、`EngineDataPlugin.kt:97-100`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`README.md:50-56`、`EngineDataPlugin.kt:97-100`）
- 位置：`README.md:51`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:96-99`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:96`

EngineDataPlugin.kt:96-99 为 `u` 笔画查询下载 rime-stroke（LGPL-3.0-only）并把它编译进每个 APK 的 `engine/stroke.data`，`app/licenses/libraries/rime-stroke.json` 已列出它，但 README 说词典、模型和码表都来自 LGPL-2.1-or-later 的 libime。
GENERIC-1@S2 还指出 voice 构建附带的 X-ASR（Apache-2.0）和 silero VAD（MIT）也没有出现在 README 中，只需更新 README。
建议：在 README 的中英文两部分各补一行。

- 提出者：CORRECTNESS-2@S1（NIT，8/10）、GENERIC-1@S2（NIT，7/10）

#### F407 [NIT] PageCleaner 每页多次全文扫描并装箱字符

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:40-42`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:40-42`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:41`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:42`

PageCleaner 每页对 `SPAM` 做 31 次 `indexOf` 扫描（:41），并用 `kept.toSet()` 把每个字符装箱进 LinkedHashSet（:42），GENERIC-1@S5 指出另有 `count` 一遍全文。
GENERIC-1@S5 还指出在 `StringBuilder` 上 Kotlin 的 `CharSequence.indexOf(String)` 走通用的 `regionMatches` 循环，而不是 String 的原生搜索。
建议不同：GENERIC-1@S5 建议先 `kept.toString()` 一次并在三处都用该 String；PERFORMANCE@G2 建议用 BitSet 统计不同字，并一遍扫描完所有垃圾词。

- 提出者：GENERIC-1@S5（NIT，8/10）、PERFORMANCE@G2（NIT，7/10）

#### F408 [NIT] words 命令忽略 --sketch-bits

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:375`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:375`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:375`

`--sketch-bits` 会被解析但只有 `clean` 使用；Main.kt:375 构造 NewWords 时总用默认 27 位，而 engine-data.sh 在两个 crawl 时只为 `clean` 把草图加宽到 28 位。
拥挤的草图只会高估，代价是更多词串进入精确的 LongIndex 表（内存和时间），不会漏词；PERFORMANCE@G2 另指出长度 2 到 4 的草图（27 位时各 512 MB）在 gather 阶段一直可达。
建议：把 `options.sketchBits` 传进去，或（CORRECTNESS-2@S5）对 `words` 拒绝该选项。

- 提出者：CORRECTNESS-2@S5（NIT，8/10）、PERFORMANCE@G2（NIT，6/10）

#### F409 [NIT] 加载失败的导入码表每次按键都抛异常

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:761`、`app/src/main/cpp/native-lib.cpp:557-559`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:761`、`app/src/main/cpp/native-lib.cpp:557-559`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:751`；另见 `app/src/main/cpp/native-lib.cpp:558`

首次失败后，`require(im !in failed)` 在每次按键时都抛 IllegalArgumentException，`native-lib.cpp` 中的 `engineFailed`（:558）随即调用 `ExceptionDescribe()`，logcat 每键输出一条完整堆栈（码表不会被重建）。
建议：在 `reload()` 之前，对 `failed` 中的输入法返回缓存的直通会话或快照而不抛异常，因为失败已经经 `onError` 报告过一次。

- 提出者：CORRECTNESS-1@S8（NIT，8/10）、CORRECTNESS-2@S8（NIT，8/10）

#### F410 [NIT] importedTableName 不会重命名名为 engine-t9 的导入

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:121`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:22`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:537`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:121`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:22`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:537`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:121`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:73`

该守卫只检查 `INPUT_METHODS` 的键和值，`Engines.T9` 不在其中；名为 `engine-t9` 的导入码表会被 `Engines.session` 中的 T9 分支遮蔽，其日志也位于同一个 `tables/` 命名空间。
建议：改为检查 `Engines.TABLES.keys`、`PINYIN`、`SHUANGPIN` 与 `T9`。

- 跨模块组合并：G3a-40、G7-98（导入码表的命名未保留 engine-t9，这样的码表永远不会被使用）
- 提出者：GENERIC-1@S8（NIT，8/10）、CORRECTNESS-1@S32（NIT，8/10）

#### F411 [NIT] 测试中基于用户目录的 Engines 实例未关闭

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:70`、`EnginesTest.kt:538`、`Engines.kt:202-210`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:70`、`EnginesTest.kt:538`、`Engines.kt:202-210`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:70`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:89`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:99`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:123`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:138`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:182`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:614`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:646`

EnginesUserWordsTest.kt 第 70、89、182 行（GENERIC-1@S16 另列第 99、123、138 行）的 `Engines`，以及 EnginesTest.kt 中的实例（CORRECTNESS-1@S16 记为第 646 行及 `aTableTheUserAddedIsBuiltOnceAndAgainOnlyWhenItChanges` 末行，GENERIC-1@S16 记为第 614 行）未关闭，各自保持 `RecordStore` 追加模式的 `FileOutputStream` 打开。
GENERIC-1@S16 指出写入无缓冲所以不丢数据，但会在测试 JVM 中泄漏文件描述符，且 Windows 上 `TemporaryFolder` 清理会失败。
建议：像其他测试一样用 `.use {}` 包裹。

- 提出者：CORRECTNESS-1@S16（NIT，8/10）、GENERIC-1@S16（NIT，8/10）

#### F412 [NIT] T9Segmenter类文档的简拼规则与代码不符

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:23-24`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:23-24`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:22`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:25`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:119`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:121`

KDoc 说开启 `abbreviations` 时在「没有更长音节开始或后面还有数字」处读简拼，但代码要求两者同时成立：第 119 行已跳过输入末尾和分隔符，第 121 行只在 `longest <= n` 时加 INITIAL，所以「或后面还有数字」从不放宽规则，例如开启 abbreviations 时 `6426` 得不到 INITIAL `6`。
CORRECTNESS-1 另指出第 25 行的 `[lock]` 链接不指向任何成员；CORRECTNESS-2 提到评测 `t9` 集开启 abbreviations，而生产传入 `abbreviations = false`。
建议：把文档改为「没有更长音节开始，且不在末尾」；若本意是任何数字前都读简拼，则改代码。

- 提出者：CORRECTNESS-1@S10（NIT，8/10）、CORRECTNESS-2@S10（NIT，8/10）

#### F413 [NIT] SentenceModel每个分母和每个位置都新建临时数组

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:176`、`Reranker.kt:93`、`PinyinSession.kt:802`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:176`、`Reranker.kt:93`、`PinyinSession.kt:802`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/SentenceModel.kt:176`

`logSum` 为每个 softmax 分母新建一个 `FloatArray(vocab)`（8192 词表约 32 KB），每次按键最多 `BUDGET` 次，每个精排切片再一次；PERFORMANCE 还指出 `next()` 每个位置都分配 y/qkv/hidden/att/add 临时数组。
建议：在 `Scorer` 中保留可复用的缓冲区，避免在 ART 上产生这些垃圾。

- 提出者：GENERIC-1@S11（NIT，8/10）、PERFORMANCE@G3（NIT，7/10）

#### F414 [NIT] VoiceRerank.notHan 的汉字定义与其他类不一致

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:59`、`VoiceText.kt:14`、`VoiceHotwords.kt:33`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:59`、`VoiceText.kt:14`、`VoiceHotwords.kt:33`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:59`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceRerank.kt:61`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:14`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHotwords.kt:33`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:114`

`notHan`（VoiceRerank.kt:59）只把 U+4E00–U+9FFF 算作汉字（STYLE@G4 指出这个范围用难以辨认的字面量书写），而 `VoiceHotwords`（VoiceHotwords.kt:33）和 `VoiceText`（VoiceText.kt:14）用 `\p{IsHan}`，`LibimeImport.isHan` 用 `Character.isIdeographic`；〇 和扩展 A 区字符因此算作非汉字，只在 〇 与 零 上不同的两个候选不会同时进入重排序 `candidates`。
STYLE@G4 另指出 VoiceRerank.kt:61 的 `PUNCTUATION` 集合复制自 AutoPairs.kt:114。
建议统一成一个辅助函数（CORRECTNESS-1@S14 建议用 `Character.UnicodeScript`（API 24+）或同一正则），范围写成 `\u` 转义，标点集合也共享。

- 提出者：CORRECTNESS-1@S14（NIT，8/10）、STYLE@G4（NIT，7/10）

#### F415 [NIT] 点击、滑出、再点击仍被算作双击

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:207`、`KeyGestureRecognizerTest.kt:426-434`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyDefPreset.kt:109`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:207`、`KeyGestureRecognizerTest.kt:426-434`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyDefPreset.kt:109`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:211`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizerTest.kt:426`

`maybeDoubleTap` 只在点击时更新（KeyGestureRecognizer.kt:211），`KeyGestureRecognizerTest.kt:426` 把「tap, slide off, tap」仍算双击的行为固定下来；在开启双击的键上（如 Shift 的大写锁定），两次点击之间一次中止的触摸仍会完成双击。
建议在非点击的抬起时解除 `maybeDoubleTap` 并反转该测试；GENERIC-1@S22 认为若有意保留上游行为，也可以继续固定。

- 提出者：CORRECTNESS-2@S22（NIT，8/10）、GENERIC-1@S22（NIT，7/10）

#### F416 [NIT] 剪贴板超时设为 -1（永不）时，重建视图不恢复最后一条

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（1 NIT, 1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:422`、`AppPrefs.kt:416`、`KawaiiBarComponent.kt:164-165`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:422`、`AppPrefs.kt:416`、`KawaiiBarComponent.kt:164-165`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:422`

`KawaiiBarComponent.kt:422` 的 `now - it.timestamp < clipboardTimeout` 在超时为 -1 时恒为 false，「永不超时」设置下重建 InputView 时不会显示最后一条剪贴内容；该偏好已被隐藏，只有旧版本或导入的值才会触发。
修复：`clipboardTimeout < 0 || now - it.timestamp < clipboardTimeout`，与 `launchClipboardTimeoutJob` 的处理一致。GENERIC-1@S34 把它标为 PRE-EXISTING。

- 注：部分 reviewer 把该问题标为 PRE-EXISTING，也有 reviewer 未标；按规则以未标记的判断为准。
- 提出者：CORRECTNESS-1@S34（NIT，8/10）、GENERIC-1@S34（NIT PRE-EXISTING，8/10）

#### F417 [NIT] 标点组件测试在 runTest 中嵌套 runBlocking

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:83`、`FakeFcitxAPI.kt:198-201`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:83`、`FakeFcitxAPI.kt:198-201`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:83`；另见 `app/src/test/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponentTest.kt:75`

`configure`（PunctuationComponentTest.kt:75-83）在测试线程上用 `runBlocking`（`runBlockingUnit`）包住挂起的 fake 调用，之所以能工作，只是因为 fake 从不挂起、从不调度；如果 fake 调度到 `StandardTestDispatcher`，测试线程就会死锁。
修复：把 `configure` 改成 `suspend` 函数直接调用，删除 `runBlockingUnit`。

- 提出者：CORRECTNESS-1@S50（NIT，8/10）、GENERIC-1@S50（NIT，7/10）

#### F418 [NIT] 双击判定使用墙钟时间

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`CustomGestureView.kt:208`、`KeyGestureRecognizer.kt:199-212`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`CustomGestureView.kt:208`、`KeyGestureRecognizer.kt:199-212`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:208`

新的手势识别用 System.currentTimeMillis() 计算双击窗口，系统时间变化时会跳变。
建议：改用 event.eventTime 或 SystemClock.uptimeMillis()，即重复计时器已使用的时钟。

- 提出者：CORRECTNESS-1@S37（NIT，6/10）、GENERIC-1@S37（NIT，8/10）

#### F419 [NIT] 九键键盘忽略 showLangSwitchKey 偏好

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`T9Keyboard.kt:126`、`TextKeyboard.kt:115-116`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`T9Keyboard.kt:126`、`TextKeyboard.kt:115-116`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:126`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:115`

TextKeyboard 在该偏好关闭时隐藏语言切换键（TextKeyboard.kt:115），T9Keyboard 总是显示它。
建议：注册同样的监听以遵循该偏好，或像 InputMethodSettings 灰显其他选项那样在九键下灰显该设置。

- 提出者：CORRECTNESS-1@S38（NIT，7/10）、GENERIC-1@S38（NIT，8/10）

#### F420 [NIT] 「数据过期」按构建时间而非数据日期判断

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`MainFragment.kt:79`、`ProjectExtensions.kt:54-56`、`gradle.properties:33`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`MainFragment.kt:79`、`ProjectExtensions.kt:54-56`、`gradle.properties:33`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:79`；另见 `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:55`

BUILD_TIME 是构建机在构建时的时钟（ProjectExtensions.kt:55），而数据由 ENGINE_DATA（engine-data-20261005-3）固定；之后重新构建旧版本（README 目前建议用户自行构建）在六个月内都不会提示，无论 engine-data-* 或词包有多旧。
建议：把数据自身的日期写入 BuildConfig 并据此比较。

- 提出者：CORRECTNESS-1@S40（NIT，6/10）、CORRECTNESS-2@S40（NIT，8/10）

#### F421 [NIT] 搜索页 `none` 视图在销毁后仍持有旧视图树

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchFragment.kt:47`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchFragment.kt:47`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchFragment.kt:47`

`onDestroyView` 释放了 `list`，但 `lateinit` 字段 `none` 仍引用一个 TextView，其父链就是整个旧布局（含 RecyclerView），Fragment 位于返回栈时旧布局仍留在内存中，违背第 124-125 行注释的意图。
建议：将 `none` 改为可空并在 `onDestroyView` 中置为 null。

- 提出者：CORRECTNESS-2@S43（NIT，7/10）、GENERIC-1@S43（NIT，8/10）

#### F422 [NIT] `removeFile` 遇到悬空符号链接时不删除

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/FileUtil.kt:22-23`、`DataManager.kt:78-79`、`FileUtil.kt:47`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/utils/FileUtil.kt:22-23`、`DataManager.kt:78-79`、`FileUtil.kt:47`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/FileUtil.kt:22`；另见 `app/src/main/java/org/fcitx/fcitx5/android/utils/FileUtil.kt:38`、`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt:78-79`

`FileUtil.kt` 第 22 行的 `file.exists()` 会跟随符号链接，对断开的链接提前返回而保留它，随后 `DataManager.kt:78-79` 的 `symlink()` 因 EEXIST 失败。GENERIC-1 还指出第 38 行的 `fold` 丢弃了累积结果，并认为两者目前都不会触发（未配置数据描述符符号链接）。
建议：在 `exists()` 提前返回前用 `Os.lstat`（或 `Files.exists(path, NOFOLLOW_LINKS)`、`isSymlink()`）检查，并保持 `acc && it.delete()`。

- 提出者：CORRECTNESS-2@S45（NIT，7/10）、GENERIC-1@S45（NIT，8/10）

#### F423 [NIT] ripple 的 <item> 上 android:shape 属性无效

- 来源：第一轮（整仓 @07d2778） · 共识：2/5 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`drawable/bkg_inline_suggestion_dark.xml:9`、`bkg_inline_suggestion_light.xml:9`、`data/res/values/attrs.xml:7120-7151`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`drawable/bkg_inline_suggestion_dark.xml:9`、`bkg_inline_suggestion_light.xml:9`、`data/res/values/attrs.xml:7120-7151`）
- 位置：`app/src/main/res/drawable/bkg_inline_suggestion_dark.xml:9`；另见 `app/src/main/res/drawable/bkg_inline_suggestion_light.xml:9`

`bkg_inline_suggestion_dark.xml` 和 `bkg_inline_suggestion_light.xml` 第 9 行在 ripple/layer-list 的 `<item>` 上设置了 `android:shape`，`<item>` 没有这个属性，会被忽略，形状由内部的 `<shape>` 决定。
修复：从两个文件中删除该属性；CORRECTNESS-2@S47 指出这段代码来自上游。

- 提出者：CORRECTNESS-1@S47（NIT，8/10）、CORRECTNESS-2@S47（NIT，8/10）

#### F424 [NIT] 多条字符串已无引用却仍在各语言中维护

- 来源：第一轮（整仓 @07d2778） · 共识：2/5 位 reviewer（2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`values/strings.xml:346`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`values/strings.xml:346`）
- 位置：`app/src/main/res/values/strings.xml:346`；另见 `app/src/main/res/values/strings.xml:351`

values/strings.xml 中有一批字符串（CORRECTNESS-1@S48 的正则扫描约 28 条）在 app/src、lib 或 codegen 中没有 `R.string`、`R.plurals` 或 `@string` 引用，例如 `section_toolbar_and_panels`、`section_size_and_padding`、`home_section_keyboard`、`home_section_appearance`、`custom_phrases_summary`、`my_words_summary`、`search`、`browse_user_data_dir(_summary)`、`restart_to_apply_settings`，以及上游遗留的 `disable_addon_warn_*`、`manage_table_im`、`edit_text_playground`；CORRECTNESS-2@S48 还列出 `action_forget_candidate_word`。
这些字符串在 zh-rCN 和 zh-rTW 中也都有维护，译者在为无用文本付出成本。
修复：删除它们。

- 子论断 F424.b 被否定：「11 个 youmo 新增的字符串（包括 home_section_keyboard 和 action_forget_candidate_word）在 app/ 或 lib/ 中没有任何引用，但在 zh-CN 和 zh-TW 中仍被保留。」→ 28 个中只有 8 个是 youmo 新增的（在 19596419 中不存在），而不是 11 个；action_forget_candidate_word（values/strings.xml:253）来自上游（442b715b，ACh Sulfate，2024-04-08，存在于 19596419）。“无引用但在 zh 中保留”这部分成立（例如 :351 home_section_keyboard）
- 提出者：CORRECTNESS-1@S48（NIT，8/10）、CORRECTNESS-2@S48（NIT，8/10）

#### F425 [NIT] DataFile Writer 不执行读取端的 64 KiB meta 上限

- 来源：第一轮（整仓 @07d2778） · 共识：2/15 位 reviewer（2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:120`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:104`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/CodeTable.kt:25`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:120`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:104`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/CodeTable.kt:25`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:120`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataFile.kt:104`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/DataFileTest.kt:167`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:744`

`meta()`（:104）读取时强制 `MAX_META_BYTES` 64 KiB，但 `addMeta`（:120）写入时不检查，失败只在加载时出现。GENERIC-1@S7 指出头部与规则超过 64 KiB 的用户码表能编译却无法加载，且 `Engines.built()` 中第二次 `CodeTable.load` 只捕获 `IOException`，该 `DataFormatException` 会逃逸给调用方；拼音数据因词典工具会重新加载输出（Main.kt:744）而在构建时失败，CORRECTNESS-1@S15 称拼音 meta 中的误读目前约 5 KB。
建议：在 `Writer` 写入时强制该上限，以便在出错处报告（也适用于码表）。

- 提出者：GENERIC-1@S7（NIT，7/10）、CORRECTNESS-1@S15（NIT，7/10）

#### F426 [NIT] “不学习”测试只检查内存状态

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:182-191`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:182-191`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:194`

`learning = false` 是应用标明不学习的输入框所走的路径，但 `nothingIsLearnedWhereTheAppSaysNotTo` 建了用户目录，却只检查一个候选在内存中的顺序。
建议：加磁盘检查，如日志长度等于 `UserLog.header().size`，或重新打开 `Engines(::load, dir)` 后 你 仍排第一。CORRECTNESS-2@S16 指出会话级测试已覆盖 `learning = false` 对习惯、层先验与预测的作用，这里只测接线。

- 提出者：CORRECTNESS-1@S16（NIT，7/10）、CORRECTNESS-2@S16（NIT，7/10）

#### F427 [NIT] 屏蔽多音字的一种读音会覆盖用户实际输入的读音

- 来源：第一轮（整仓 @07d2778） · 共识：2/12 位 reviewer（2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:204`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:204`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:204`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:260`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:138`

`block()`/`unblock()` 经 `known()` 调用 `id()`，UserModel.kt:204 随之把 `entries[id]` 设为被屏蔽的读音；之后 `reading(id)`（Predictor.kt:138）和下一次压缩都用 行 xing（被屏蔽）代替用户输入的 行 hang。CORRECTNESS-2@S13 指出 `list`（用户词典）和导入同样会覆盖它，而 `UserWords.reading` 的文档说返回「as the user typed it」。
修复建议不一：CORRECTNESS-1@S13 建议 block/unblock 通过 `inDictionary()` 解析字典 id，不触碰 `ids` 与 `entries`；CORRECTNESS-2@S13 建议只在 `learn` 中记录读音。

- 提出者：CORRECTNESS-1@S13（NIT，7/10）、CORRECTNESS-2@S13（NIT，6/10）

#### F428 [NIT] 原生内核结果与评测所用 JVM 内核并非逐位一致

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/cpp/matrix-kernel.cpp:45`、`MatrixKernel.kt:41`、`EngineBridge.kt:69`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/matrix-kernel.cpp:45`、`MatrixKernel.kt:41`、`EngineBridge.kt:69`）
- 位置：`app/src/main/cpp/matrix-kernel.cpp:45`；另见 `app/src/main/cpp/matrix-kernel.cpp:56`

两个内核的求和顺序不同：JVM 的 dotQ 按 (s0+s1)+(s2+s3)+… 归约，原生内核把第 k 道与第 k+4 道配对，剩余行（line 56）用单一累加器 dot；在 arm64 上 clang 默认的 -ffp-contract=on 很可能把 a += x*q 融合为 FMA，而 Java/ART 不会。差异约为 1e-6 相对量级，可能让接近并列的句子交换名次，lib/ime-eval 的准确率数字因而来自略有不同的算术。
修复：若要求一致，按 dotQ 的顺序归约并对该文件使用 -ffp-contract=off（GENERIC-1@S25 提醒先测速度代价）；否则在 MatrixKernel 中注明结果可能略有差异及预期容差。

- 提出者：CORRECTNESS-2@S25（NIT，6/10）、GENERIC-1@S25（NIT，7/10）

#### F429 [NIT] 引擎用户目录不支持 Direct Boot，与 fcitx 主目录不一致

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69`、`AppContext.kt:11-12`、`EngineBridge.kt:77-79`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69`、`AppContext.kt:11-12`、`EngineBridge.kt:77-79`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69`

`EngineBridge.kt:69` 的引擎用户目录 `File(appContext.filesDir, engine)` 始终位于凭据加密存储，而 `fcitxHome()`、`Fcitx.nativeStartup` 和迁移使用 `directBootAwareContext`，manifest 也声明了 `directBootAware=true`。解锁前存储打不开，锁屏输入时引擎只在内存中学习，用户的屏蔽词和新增词都不生效，每个存储还会记一条警告。
两票都认为这可能是有意的隐私取舍：CORRECTNESS-2@S29 建议加一行说明原因的注释，防止以后被「修」成设备保护存储；GENERIC-1@S29 建议加注释，或在 `isDirectBootMode` 时传 `userDir = null`，让内存模式变成显式行为。

- 提出者：CORRECTNESS-2@S29（NIT，6/10）、GENERIC-1@S29（NIT，7/10）

#### F430 [NIT] fragment_setup 用 android:tint，装饰图未设无障碍

- 来源：第一轮（整仓 @07d2778） · 共识：2/5 位 reviewer（2 NIT）· 置信度 7/10 · 验证：✅ 已确认（`layout/fragment_setup.xml:47`、`SetupActivity.kt:29`、`app/build.gradle.kts:134`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`layout/fragment_setup.xml:47`、`SetupActivity.kt:29`、`app/build.gradle.kts:134`）
- 位置：`app/src/main/res/layout/fragment_setup.xml:47`；另见 `app/src/main/res/layout/fragment_setup.xml:9`、`app/src/main/res/layout/fragment_setup.xml:40`

fragment_setup.xml:47 在 ImageView 上使用 `android:tint="?attr/colorPrimary"`（e4cba1ad 添加），AppCompat lint（UseAppTint）要求改用 `app:tint`；在 minSdk 23 下目前可用。
装饰性的 `app_icon`（:9）和 `done_icon`（:40）ImageView 既没有 `contentDescription` 也没有 `importantForAccessibility="no"`，TalkBack 会聚焦到它们却无内容可读。
修复：改用 `app:tint`，并给这两个装饰图片加上 `android:importantForAccessibility="no"`。

- 提出者：CORRECTNESS-1@S47（NIT，7/10）、GENERIC-1@S47（NIT，7/10）

#### F431 [NIT] detekt 没有覆盖 debug、testFixtures 等源集

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`）
- 位置：`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`

DetektConventionPlugin 只在默认根目录之外加了 `src/text/java` 和 `src/voice/java`，`app/src/debug/java` 和 `lib/ime-core/src/testFixtures/kotlin`（`FakeEditor` 等共享 fake）从未被 `detekt` 任务检查；GENERIC-1@S2 还列出 `app/src/androidTest/java` 和 `app/src/androidTestVoice/java`。
CORRECTNESS-2@S2 的结论基于 detekt 2.x 沿用 1.x 默认源目录的假设，未阅读 2.x 插件源码。
建议（GENERIC-1@S2）：补上这些目录，或收集所有存在的 `src/*/{java,kotlin}` 目录。

- 子论断 F431.b 无法验证：「detekt 从不检查 `app/src/debug/java`、`app/src/androidTest/java`、`app/src/androidTestVoice/java` 或 `lib/ime-core/src/testFixtures/kotlin`。」→ dev.detekt 2.0.0-alpha.6 的默认 `source` 列表（gradle/libs.versions.toml:20）无法获取，仓库中只能看到 DetektConventionPlugin.kt:43 没有添加这些目录
- 提出者：CORRECTNESS-2@S2（NIT，5/10）、GENERIC-1@S2（NIT，6/10）

#### F432 [NIT] imeScope() 以裸 CoroutineScope 类型作为依赖注入的键

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/dependency/Functions.kt:41`、`InputView.kt:132`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/dependency/Functions.kt:41`、`InputView.kt:132`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/dependency/Functions.kt:41`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:125`

`input/dependency/Functions.kt:41` 的 `imeScope()` 按 `CoroutineScope` 类型查找依赖。CORRECTNESS-1@S36 指出，以后若再注册一个作用域（例如 IO scope），会与视图作用域冲突或悄悄替换它，所有 `imeScope` 的使用者都会拿到错误的生命周期；CORRECTNESS-2@S36 指出查找之所以成功，只是因为 InputView.kt:125 从 `CoroutineScope(...)` 工厂推断出了静态类型，若属性以后被声明为子类型，编译能通过，但第一次注入时会在运行时失败（PunctuationComponentTest 也因此必须向上转型）。
修复：用专门的包装类型（如 `class ViewScope(s: CoroutineScope) : CoroutineScope by s`），或显式声明 `private val viewScope: CoroutineScope`。

- 提出者：CORRECTNESS-1@S36（NIT，6/10）、CORRECTNESS-2@S36（NIT，6/10）

#### F433 [NIT] 设备端评测分数受设备上已学到的数据影响

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:45`、`run-on-device.sh:17-18`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:45`、`run-on-device.sh:17-18`）
- 位置：`app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:56`；另见 `app/src/androidTest/java/org/fcitx/fcitx5/android/EngineEvalRunner.kt:107`

EngineEvalRunner 在 debug app 的真实用户存储里输入（`installTextDebug` 会保留 app 数据），设备上已学到的词和词频会让分数偏移，不同设备或不同时间的结果无法相互比较，也无法与 `:lib:ime-eval` 的基线比较。
修复：在 KDoc 中说明，或在全新的模拟器上运行。

- 提出者：CORRECTNESS-2@S49（NIT，6/10）、GENERIC-1@S49（NIT，6/10）

#### F434 [NIT] 拼音词库测试共用首个 Robolectric 应用的目录

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManagerTest.kt:26`、`PinyinDictManager.kt:20-22`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManagerTest.kt:26`、`PinyinDictManager.kt:20-22`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManagerTest.kt:26`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManagerTest.kt:29`

`PinyinDictManager.pinyinDicDir` 只在对象首次初始化时求值并调用 `mkdirs()`（PinyinDictManagerTest.kt:26），之后所有测试都写进这个目录，`@Before empty()` 只清空它；这依赖 Robolectric 不删除前一个测试的临时目录以及它复用沙箱的方式，否则后面的写入会抛 FileNotFoundException。CORRECTNESS-2@S50 还指出 :29 的注释语句不通。
修复：像 `ImportedDictionaries` 那样给 manager 加一个 `dir` 参数，或在 `empty()` 中加上 `dir.mkdirs()`。

- 提出者：CORRECTNESS-2@S50（NIT，5/10）、GENERIC-1@S50（NIT，6/10）

#### F435 [NIT] 测试字符串中直接嵌入了不可见的 U+FEFF

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 10/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:35`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:35`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:35`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:53`

去 BOM 的用例依赖一个看不见的字符，编辑器或格式化工具可能悄悄删掉它，测试仍会通过，却不再测试去 BOM。
修复：像 :53 和 PinyinDictManagerTest 那样把 U+FEFF 写成转义形式。

- 提出者：GENERIC-1@S50（NIT，10/10）

#### F436 [NIT] 未使用的 import `android.widget.EditText`

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 10/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:13`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:13`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:13`

`PunctuationEditorFragment.kt` 第 13 行导入了未使用的 `android.widget.EditText`。
建议：删除该 import。

- 提出者：CORRECTNESS-1@S42（NIT，10/10）

#### F437 [NIT] EngineDataPlugin.apply() 长达 100 行，下载配置重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:120-221`、`config/detekt/detekt.yml:27`、`VoiceDataPlugin.kt:75`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:120-221`、`config/detekt/detekt.yml:27`、`VoiceDataPlugin.kt:75`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:113`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:126`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:134`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:138`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:144`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:75`

项目 detekt 的 `LongMethod` 上限是 80，而 build-logic 不在 detekt 范围内；七个 `register<DownloadTask>` 块重复同样的 url/sha256/outputFile 三元组，`Source` 只覆盖两个 libime 压缩包，其余 URL/SHA 常量对零散存放。
CMake 路径在第 126、138 行和 VoiceDataPlugin.kt:75 各拼一次，文件名在第 134、144 行从 URL 推导。
建议：引入 `Download(url, sha256, file)` 值类型、`registerDownload()` 辅助函数和 `Project.sdkCmake` provider。

- 提出者：STYLE@G1（NIT，9/10）

#### F438 [NIT] 共享的任务类嵌套在单个插件内

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:229`、`VoiceDataPlugin.kt:68`、`NativeBuildTasks.kt:50`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:229`、`VoiceDataPlugin.kt:68`、`NativeBuildTasks.kt:50`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:221`

VoiceDataPlugin 使用 `EngineDataPlugin.DownloadTask` 和 `ExtractTask`，而这个 440 行的文件里有五个任务类；包内已有的约定是把共享任务放在独立文件里（NativeBuildTasks.kt）。
建议：把这两个任务移到如 `DownloadTasks.kt` 的文件中。

- 提出者：STYLE@G1（NIT，9/10）

#### F439 [NIT] `extracted[0]`/`extracted[1]` 是位置魔法下标

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:176`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:176`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:169`

这两个下标隐式依赖 `listOf(DICT, TABLE)` 的顺序。
建议：改用解构（`val (dictDir, tableDir) = …`），或以 `Source` 为键组织结果。

- 提出者：STYLE@G1（NIT，9/10）

#### F440 [NIT] SHA-256 与十六进制编码辅助函数重复实现

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:269-273`、`DataDescriptorPlugin.kt:115-116`、`EngineDataPlugin.kt:261`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:269-273`、`DataDescriptorPlugin.kt:115-116`、`EngineDataPlugin.kt:261`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:261`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:253`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:264`、`build-logic/convention/src/main/kotlin/DataDescriptorPlugin.kt:115`、`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:44`

`sha256Of` 与 `DataDescriptorTask.sha256`（DataDescriptorPlugin.kt:115）重复，`"%02x".format` 形式的十六进制编码出现了三次（第 253、264 行和 ToolFingerprint.kt:44）。
建议：统一为一个辅助函数，使用 `HexFormat.of().formatHex`（Gradle 9 运行在 JDK 17+ 上）。

- 提出者：STYLE@G1（NIT，9/10）

#### F441 [NIT] `signKey` 属性的 getter 带有副作用

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:68`、`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:70-88`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:68`、`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:70-88`）
- 位置：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:70`

读取 `signKey` 会把 keystore 解码到 `build/`，并缓存在一个顶层 `var` 中。
建议：改为显式函数，例如 `materializeSignKey()`。

- 提出者：STYLE@G1（NIT，9/10）

#### F442 [NIT] CI 步骤名「Install Android NDK」与实际行为不符

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`.github/workflows/pull_request.yml:38-40`、`.github/workflows/emulator_test.yml:57`、`build-logic/convention/src/main/kotlin/Versions.kt:15`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/pull_request.yml:38-40`、`.github/workflows/emulator_test.yml:57`、`build-logic/convention/src/main/kotlin/Versions.kt:15`）
- 位置：`.github/workflows/pull_request.yml:38`；另见 `.github/workflows/emulator_test.yml:57`

该步骤只安装 CMake；`cmake;3.31.6` 还在这里和 emulator_test.yml:57 硬编码，重复了 `Versions.defaultCMake`。
STYLE@G1 没有单独写出修法；CORRECTNESS-2@S1 在 G1-16 的发现中也指出了这个步骤名。

- 提出者：STYLE@G1（NIT，9/10）

#### F443 [NIT] `when (abi)` 重复了 `supportedABIs`

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/Versions.kt:23`、`build-logic/convention/src/main/kotlin/Versions.kt:27-33`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/Versions.kt:23`、`build-logic/convention/src/main/kotlin/Versions.kt:27-33`）
- 位置：`build-logic/convention/src/main/kotlin/Versions.kt:27`

新增一个 ABI 需要改两处。
建议：用一个 `mapOf(abi to id)`，并令 `supportedABIs = keys`，这样只需改一处。

- 提出者：STYLE@G1（NIT，9/10）

#### F444 [NIT] make_icon.py 的文档字符串与实现不符

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/launcher-icon/make_icon.py:2`、`app/launcher-icon/make_icon.py:43-45`、`app/launcher-icon/make_icon.py:135`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/launcher-icon/make_icon.py:2`、`app/launcher-icon/make_icon.py:43-45`、`app/launcher-icon/make_icon.py:135`）
- 位置：`app/launcher-icon/make_icon.py:2`；另见 `app/launcher-icon/make_icon.py:45`、`app/launcher-icon/make_icon.py:135`

文档字符串说每个形状只列一次，但 `MONOCHROME`（第 45 行）重新写了一遍 grin 路径和耳朵圆；第 135 行的推导式使用 `d, f, s, w` 这类名字，脚本还在 import 时就执行，没有 `main` 守卫。
STYLE@G1 没有单独写出修法。

- 提出者：STYLE@G1（NIT，9/10）

#### F445 [NIT] core 测试失败会跳过 app 单元测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`.github/workflows/unit_test.yml:83-84`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/unit_test.yml:83-84`）
- 位置：`.github/workflows/unit_test.yml:83`

「Run app unit tests」步骤没有 `if:`，ime-core 步骤失败时 app 的结果被隐藏；detekt 步骤正是为此使用了 `if: success() || failure()`。
建议：给该步骤加上同样的条件。

- 提出者：CORRECTNESS-1@S1（NIT，9/10）

#### F446 [NIT] GenScancodeMapping 有未用的解构变量和替换残留的注释

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:393`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:393`）
- 位置：`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:393`；另见 `codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:283`、`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:285-288`、`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:291`

`forEach { (scancode, androidKey) -> ... }` 中的 `androidKey` 从未使用，应写成 `(scancode, _)`；第 283、291 行（及 285-288 行附近）的 `/* */` 注释里出现 `to "",`，像是搜索替换的残留（如 `LTE to "", UMTS to ""`）。
修复只影响外观，不改变行为。GENERIC-1@S3 在 G1-59 的发现中也指出了这两点。

- 提出者：CORRECTNESS-2@S3（NIT，9/10）

#### F447 [NIT] GenKeyMapping 生成代码中的 KDoc 已过期

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenKeyMapping.kt:214`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenKeyMapping.kt:214`）
- 位置：`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenKeyMapping.kt:214`；另见 `codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenKeyMapping.kt:226`、`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:283`、`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:291`、`codegen/src/main/java/org/fcitx/fcitx5/android/codegen/GenScancodeMapping.kt:393`

第 226 行的过滤器移除了 A-Z 区间，`keyCodeToSym` 中没有重复的 `when` 标签，所以「Duplicate labels are expected」是错的，而且这句话会出现在生成的源码里。
GENERIC-1@S3 在同一条中还指出 GenScancodeMapping.kt:283、:291 的注释被 `,` 到 ` to ""` 的替换弄乱，:393 的 `androidKey` 未使用（与 G1-58 相同）。
建议：修正 KDoc；未使用的变量改为 `(scancode, _)`。

- 提出者：GENERIC-1@S3（NIT，9/10）

#### F448 [NIT] corrected() 会把 remove.tsv 中的词重新加回

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:60-61`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:60-61`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PinyinDictReader.kt:60`

PinyinDictReader.kt:60 的 `corrected()` 不检查 `removed`（它只在 `read()` 中检查），所以同时出现在 remove.tsv 和 readings.tsv 或 misreadings.tsv 中的词会被加回。
建议：在 `corrected()` 中跳过此类词，或在加载列表时拒绝这种重叠。

- 提出者：CORRECTNESS-2@S5（NIT，9/10）

#### F449 [NIT] 选项值可以是下一个 flag，重复 flag 静默覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:222`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:222`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:222`

Main.kt:222 的参数解析让 `-o --lm x` 把 `--lm` 当作输出路径、`x` 当作输入；重复出现的 flag 会静默替换先前的值。
建议：拒绝本身就在 FLAGS 中的值，并拒绝重复的 flag。

- 提出者：CORRECTNESS-2@S5（NIT，9/10）

#### F450 [NIT] 调参常数的依据只在未纳入版本库的 dev/*.md 中

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:188`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:188`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:188`

WEIGHT、MIN_PMI/MIN_ENTROPY、CountFit 斜率和 PageCleaner 阈值（Main.kt:188 等处）都引用 dev/ENGINE-DESIGN.md 或 dev/TRAINING-PLAN.md，而这些文件没有被跟踪，作者以外无人能读（rubric 1d）。
建议：把测得的数字移入已跟踪的文档，例如 lexicon/README.md。

- 提出者：CORRECTNESS-2@S5（NIT，9/10）

#### F451 [NIT] WebTextTest 注释说三次，断言却是 4

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:119-120`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:119-120`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:119`

WebTextTest.kt:119 的注释说「three times」，旁边断言 `assertEquals("4", …)`；你好 实际在 3 个页面中出现 4 次。
该发现还指出 吧/啊/吗 被计入 `# chars 14`，注释说「not counted」有误导。CORRECTNESS-1@S6 在 G2-23 的发现中也提到此处。建议修正注释。

- 提出者：CORRECTNESS-2@S6（NIT，9/10）

#### F452 [NIT] SurpriseTest 注释「100 倍」有误，应约 3.2 倍

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/SurpriseTest.kt:40-41`、`Surprise.kt:22`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/SurpriseTest.kt:40-41`、`Surprise.kt:22`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/SurpriseTest.kt:40`

SurpriseTest.kt:40 的注释说「100 times what the model says」，但 log10 下 0.5 的 surprise 是 10^0.5，约 3.2 倍。
建议修正注释。

- 提出者：CORRECTNESS-2@S6（NIT，9/10）

#### F453 [NIT] ReadersTest 使用全限定名 Syllables.id

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:68`、`SurpriseTest.kt:9`、`TableWordsTest.kt:9`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:68`、`SurpriseTest.kt:9`、`TableWordsTest.kt:9`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:68`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/ReadersTest.kt:80`

ReadersTest.kt:68（及 :80）写全限定的 `org.fcitx.fcitx5.android.engine.pinyin.Syllables.id`，而 SurpriseTest 和 TableWordsTest 都 import `Syllables`。
建议此处也 import。

- 提出者：GENERIC-1@S6（NIT，9/10）

#### F454 [NIT] apply.py 等处变量名无意义，check() 名不副实

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lexicon/tools/apply.py:57`、`lexicon/tools/batches.py:44`、`Main.kt:281`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/tools/apply.py:57`、`lexicon/tools/batches.py:44`、`Main.kt:281`）
- 位置：`lexicon/tools/apply.py:57`；另见 `lexicon/tools/apply.py:101`、`lexicon/tools/apply.py:119`、`lexicon/tools/batches.py:44`

`f_`、`a, r, u, e = check(...)`（apply.py:101）、`words_`（:119）、`a = argparse.ArgumentParser()`（batches.py:44），以及 Main.kt 中把候选行叫 `f`（`passes(f)`、`forEachRow { f -> }`），名字都不表达含义。
`check()` 实际还解析批次、构建 add/reject/unsure 列表并修改 `seen`；建议改名为 `fields`、`parser`、`parse_decisions` 等。

- 提出者：STYLE@G2（NIT，9/10）

#### F455 [NIT] 共享字符常量多处复制且已出现偏差

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PageCleaner.kt:76`、`CommonCrawl.kt:160`、`WebText.kt:138`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PageCleaner.kt:76`、`CommonCrawl.kt:160`、`WebText.kt:138`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/PageCleaner.kt:76`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:160`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:138`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:359`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:297`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/LongIndex.kt:64`、`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/NewWords.kt:267`

`'一'..'鿿'` 汉字区间出现在 CommonCrawl.kt:160、PageCleaner.kt:76、WebText.kt:138 和 Main.kt:359，而 `NewWords.isHan`（:297）还包含扩展 A；`PageCleaner.END` 缺少 `Examples.ENDS` 中的 ASCII `;`。
`GOLDEN` 同时定义在 LongIndex.kt:64 与 NewWords.kt:267，`MAX_WORD = 8` 同时出现在 ChatCounts 与 Readings 中；建议集中到一个 `TextChars`/`Hash` 持有者。

- 提出者：STYLE@G2（NIT，9/10）

#### F456 [NIT] Main.kt:604 的三键排序挤在一行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:605`、`Main.kt:8-9`、`Main.kt:22-23`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:605`、`Main.kt:8-9`、`Main.kt:22-23`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:604`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:574`

Main.kt:604 的 `compareValues(..).takeIf { it != 0 } ?: …` 链难读，而 `offer`（:574）已经用了 `compareValuesBy`；建议用小的 `Comparable`（words、weight、common）或 `compareBy`。
该发现还指出 Main.kt 与 CommonCrawl.kt 的 import 未排序，以及 lexicon/README.md 文件表遗漏 misreadings.tsv（见 G2-26）。

- 提出者：STYLE@G2（NIT，9/10）

#### F457 [NIT] DataAge 引用仓库中不存在的 dev/TRAINING-PLAN.md

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataAge.kt:8`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataAge.kt:8`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/DataAge.kt:8`

DataAge.kt:8 引用 dev/TRAINING-PLAN.md 12.3，但该文件不在仓库中；其理由（每季度更新，半年即错过两次）已写在行内。STYLE@G3 在归入 G3a-30 的发现中也提到此引用。
建议：删除失效引用，或把该文档纳入仓库。

- 提出者：CORRECTNESS-2@S7（NIT，9/10）

#### F458 [NIT] MisreadingsTest 的小测试写法问题

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:32`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:44`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:32`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:44`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:32`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/data/MisreadingsTest.kt:44`

第 32 行应使用 `assertNull` 而非 `assertEquals(null, ...)`；第 44 行局部函数 `fun error(line)` 遮蔽了标准库的 `error()`。
建议：改用 `assertNull`，并把该函数改名（如 `rejection`）。

- 提出者：GENERIC-1@S15（NIT，9/10）

#### F459 [NIT] SharedLexiconTest 类结束括号前多一空行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:157`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:157`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:157`

SharedLexiconTest.kt:157 处，类的结束大括号前有一个空行。
建议：删除该空行（评审只指出了这一格式问题）。

- 提出者：CORRECTNESS-1@S16（NIT，9/10）

#### F460 [NIT] EnginesTest 布局问题与测试辅助代码重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`EnginesTest.kt:609`、`EnginesTest.kt:36-84`、`EnginesUserWordsTest.kt:24-50`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EnginesTest.kt:609`、`EnginesTest.kt:36-84`、`EnginesUserWordsTest.kt:24-50`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:685`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesTest.kt:704`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:48`

`companion object` 位于类中部，之后还有测试（`uLooksACharacterUp…`，第 704 行）；同一个 `pinyin` 夹具、`syl` 辅助函数和 `Engines.type` 扩展被复制到 `EnginesUserWordsTest` 与 `SharedLexiconTest`；`EnginesUserWordsTest.kt:48` 用全限定名写 `java.io.FileNotFoundException`，而 `SharedLexiconTest` 使用导入。
建议：把 `companion object` 移到类末尾（评审对其余两点只列出问题，未给出具体修复）。

- 提出者：GENERIC-1@S16（NIT，9/10）

#### F461 [NIT] Engines 中 added 一名指代四种不同事物

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认
- 当前状态（5881d31）：仍存在（第二轮已验证）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:159`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:162`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:310`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:755`

`added` 同时是一个字段（第 159 行）、一个函数 `added()`（第 162 行）、一个存放新条目的局部变量（第 310 行）和一个存放 `UserTable` 的局部变量（第 755 行）。
建议：给每一处起各自的名字。

- 提出者：STYLE@G3（NIT，9/10）

#### F462 [NIT] SpellingIndex为每个节点计算的completions从未被读取

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:129`、`PinyinSegmenter.kt:49`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:129`、`PinyinSegmenter.kt:49`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:129`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:42`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:17`

`completions(node)`（第 42 行）在 main 和 test 代码中都没有调用者，但每个 `PinyinSegmenter` 构造时仍为每个字典树节点建一个 `SyllableMatches`，而 `Engines` 每次设置变更都新建一个 segmenter，另加一个查询用的。
建议：删除该数组、访问器以及类文档（第 17 行）中的 `[completions]`，或真正使用它。

- 提出者：CORRECTNESS-2@S10（NIT，9/10）

#### F463 [NIT] 文档仍以已删除的晚风、电报码码表举例

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableOptions.kt:29`、`TableSession.kt:138`、`Session.kt:132`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableOptions.kt:29`、`TableSession.kt:138`、`Session.kt:132`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableOptions.kt:29`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableOptions.kt:32`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:138`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/Session.kt:132`

TableOptions.kt:29、:32、TableSession.kt:138 和 Session.kt:132 的文档仍引用 commit d4bdf787 已删除的 晚风 和 电报码。
建议：改为引用已发布的码表，或不举例。

- 提出者：CORRECTNESS-2@S12（NIT，9/10）

#### F464 [NIT] TableSession.select先读排名再检查索引范围

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:193-194`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:193-194`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableSession.kt:193`

`ranking[page * pageSize + index]` 会在 `index !in 0 until pageSize` 拒绝该选择之前，惰性构建超出当前页的候选（例如选择键多于页大小时）。
建议：先检查索引范围。

- 提出者：GENERIC-1@S12（NIT，9/10）

#### F465 [NIT] TinyModel宽度等于LANES，矩阵核的余数循环从未运行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/TinyModel.kt:22`、`MatrixKernel.kt:25`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/TinyModel.kt:22`、`MatrixKernel.kt:25`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/rerank/TinyModel.kt:22`

TinyModel 的每个矩阵都是 8 或 32 列，宽度 8 等于 `LANES`，所以 MatrixKernel 中 `dotQ`/`dotF` 的余数循环从不执行，那里的 bug 不会暴露。
建议：改用如 12 这样的宽度来覆盖余数循环。

- 提出者：CORRECTNESS-2@S17（NIT，9/10）

#### F466 [NIT] 测试注释描述的「在下一个键上屏」路径未被执行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:654-655`、`TableSession.kt:163-167`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:654-655`、`TableSession.kt:163-167`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:654`

工 是 `aaaa` 的唯一候选，无论有无标记都会在第 4 个键自行上屏，第 5 个键什么也不上屏。
建议：在 `aaaa` 下放两个条目，以测试「在下一个键上屏」。

- 提出者：CORRECTNESS-1@S19（NIT，9/10）

#### F467 [NIT] UserModelTest 用魔数代替私有常量

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:112`、`UserModel.kt:398`、`UserScorerTest.kt:36`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:112`、`UserModel.kt:398`、`UserScorerTest.kt:36`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:112`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:114`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserModelTest.kt:236`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:36`

字面量 `1f / 22f`（UserModelTest.kt:112、114、236）和 `0.5 / 21`（UserScorerTest.kt:36）编码了 UserModel.kt 中私有的 `UNSEEN = 20f` 与 `PRIOR = 2f`；调整任一常量都会让许多测试以看不出原因的信息失败。
建议把这两个常量改为 `internal` 并在测试中引用，或在一个测试辅助函数中计算期望值。

- 提出者：GENERIC-1@S20（NIT，9/10）

#### F468 [NIT] UserStoreTest 中 blocker.delete() 不起作用

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:169`、`UserStoreTest.kt:273`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:169`、`UserStoreTest.kt:273`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserStoreTest.kt:273`

种子写入失败时生产代码已经删除了空的阻塞目录，所以 `blocker.delete()`（UserStoreTest.kt:273）没有效果，测试却读起来像阻塞物会一直保留到测试移除它；机制与 G4-10 相同（CORRECTNESS-1@S20 在 G4-10 中顺带提到此行）。
建议断言 `assertFalse(blocker.exists())` 以固定这一清理行为，或像 G4-10 那样让阻塞物无法被删除。

- 提出者：GENERIC-1@S20（NIT，9/10）

#### F469 [NIT] deletingBeforeACompositionMovesIt 分区放错

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:545`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:545`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:545`

该测试位于退格分区（EditingSessionTest.kt:545），但测试的是 `deleteSurrounding`。
建议把它移到 deleteSurrounding 分区。

- 提出者：CORRECTNESS-1@S21（NIT，9/10）

#### F470 [NIT] CursorTrackerTest 重复预测用例未能证明其主张

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:102`、`CursorTracker.kt:50`、`CursorTracker.kt:17`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:102`、`CursorTracker.kt:50`、`CursorTracker.kt:17`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/cursor/CursorTrackerTest.kt:102`

`nonConsecutiveDuplicatePredictionsAreKept`（CursorTrackerTest.kt:102）中，`consume(5)` 之后 `current` 为 5；即使队列已被清空，`latest` 也会回退到 `current`，「the later 5 is still queued」的断言照样通过。
建议先断言 `consume(6)` 为真，再在 `current` 为 6 时断言 `latest.rangeEquals(5,5)`。

- 提出者：GENERIC-1@S21（NIT，9/10）

#### F471 [NIT] deleteSelectsTheNextOrNone 的名称与第二步矛盾

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupEditorTest.kt:55`、`PopupEditor.kt:46`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupEditorTest.kt:55`、`PopupEditor.kt:46`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/popup/PopupEditorTest.kt:55`

删除最后一项后，`PopupEditor.delete` 选中的是前一项（`minOf(selected, lastIndex)`），测试也断言 `selected == 0`（PopupEditorTest.kt:55），与名称中的「Next」不符。
建议改名，如 `deleteSelectsWhatTookItsPlaceOrThePreviousOrNone`，与源码 KDoc 一致。

- 提出者：GENERIC-1@S22（NIT，9/10）

#### F472 [NIT] restore 在 UserModel 累加、在 KeyHabits 覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:77`、`UserModel.kt:154`、`UserLog.kt:89-90`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:77`、`UserModel.kt:154`、`UserLog.kt:89-90`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:154`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:72`

`UserModel.restore(entry, count)`（UserModel.kt:154）把计数累加上去，`KeyHabits.restore`（KeyHabits.kt:72）则替换计数，而 `UserLog.replay` 两者都调用；同一回放路径上同一个动词有两种含义，容易让后来者做出错误假设。
建议重命名其中一个，例如 `addCount`。

- 提出者：STYLE@G4（NIT，9/10）

#### F473 [NIT] 若干局部名遮蔽字段或参数，或含义易误解

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:135`、`UserModel.kt:142`、`KeyHabits.kt:113`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:135`、`UserModel.kt:142`、`KeyHabits.kt:113`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:135`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:113`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:92`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/bar/IdleUiPolicy.kt:41`

UserModel.kt:135 的局部 `val ids` 遮蔽了字段 `ids`，后续几行只能写 `this.ids`；KeyHabits.kt:113 的解构 `(key, text, _)` 遮蔽了 `trim` 自己的参数；KeyHabits.kt:92 的访问者 lambda 名为 `count`，与它的第三个参数同名；IdleUiPolicy.kt:41 的 `decide(i: Inputs)` 用了通常表示索引的 `i`。
建议分别改为 `forgotten`、`(k, t, _)`、`visit` 和 `inputs`。

- 提出者：STYLE@G4（NIT，9/10）

#### F474 [NIT] lue/nue 修正在 WordLists 与 LibimeImport 重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:193`、`LibimeImport.kt:197`、`WordLists.kt:158`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:193`、`LibimeImport.kt:197`、`WordLists.kt:158`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:193`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:197`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:158`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/WordLists.kt:164`

`WordLists.fixed`（WordLists.kt:193）重复了 `LibimeImport.spelling`（LibimeImport.kt:197），却缺少其中的 ü 处理，只因 WordLists.kt:158、164 事先替换了 ü 才能正常工作。
建议暴露一个 `internal` 规范化函数供两处共用，免得两个读取用户拼音的地方逐渐走样。

- 提出者：STYLE@G4（NIT，9/10）

#### F475 [NIT] AutoPairs 的嵌套类 Pair 遮蔽了 kotlin.Pair

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:29`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:29`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:29`

AutoPairs.kt:29 的嵌套类名为 `Pair`，而同一文件用 `to`（构造 `kotlin.Pair`）来定义 `PAIRS`。
建议改名为 `PutIn` 或 `OpenPair`。

- 提出者：STYLE@G4（NIT，9/10）

#### F476 [NIT] LibimeImport 的条件混用 || 与 && 且无括号

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:153`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:153`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImport.kt:153`

LibimeImport.kt:153 的条件依赖运算符优先级来求值。
建议拆成两个守卫子句。

- 提出者：STYLE@G4（NIT，9/10）

#### F477 [NIT] Python 工具打开文件后未关闭

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/tools/make-collision-set.py:33`、`make-new-words-set.py:162`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/tools/make-collision-set.py:33`、`make-new-words-set.py:162`）
- 位置：`lib/ime-eval/tools/make-collision-set.py:33`；另见 `lib/ime-eval/tools/make-collision-set.py:44`、`lib/ime-eval/tools/make-collision-set.py:106`、`lib/ime-eval/tools/make-new-words-set.py:162`

make-collision-set.py:33、:44、:106 和 make-new-words-set.py:162 用 `for line in open(...)` 读文件，依赖 CPython 的引用计数来关闭文件。
修复：像 make-chat-set.py、make-dialog-sets.py 那样使用 `with open(...) as f:`。STYLE 在 G5-40 中也提到这些位置。

- 提出者：PERFORMANCE@G5（NIT，9/10）

#### F478 [NIT] 命令定义分散在五处，新增命令需多处同步修改

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/build.gradle.kts:9-16`、`MainTest.kt:58`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/build.gradle.kts:9-16`、`MainTest.kt:58`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:82`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:94`、`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/MainTest.kt:55`

新增命令或选项需要同时修改 USAGE、带内联允许列表的 `when`（:94 列了 14 个名字）、SET_WRITERS/PREDICT_COMMANDS、`optionsValid` 和 build.gradle.kts 的用法注释；该注释已缺 t9、predict*、sentences、lm、libime，MainTest:55 也只检查 14 个命令中的 7 个。
修复：用一张 `Command(name, arity, options, run)` 表并由它生成 USAGE。STYLE 认为 G5-02 的选项未校验正是这种分散造成的。

- 提出者：STYLE@G5（NIT，9/10）

#### F479 [NIT] 参数列表过长且存在数据泥团

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:257-271`、`PinyinRun.kt:28-47`、`Main.kt:276`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:257-271`、`PinyinRun.kt:28-47`、`Main.kt:276`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:257`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:289`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:382`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:28`

`runPinyin`（Main.kt:257）有 13 个参数，`sentences`（:289）和 `ksc`（:382）各 8 个，`PinyinRun` 构造函数（PinyinRun.kt:28）有 9 个；(scheme, fuzzy, neighbours) 和 (beam, sentenceCount) 两组参数在四个命令之间一起传递。
修复：从 `Arguments` 一次解析出一个引擎选项数据类，缩短这些签名。

- 提出者：STYLE@G5（NIT，9/10）

#### F480 [NIT] `a`、`p`、`p[1]`..`p[3]` 等短名降低可读性

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:83-84`、`ExactReading.kt:32-64`、`TableRun.kt:221`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:83-84`、`ExactReading.kt:32-64`、`TableRun.kt:221`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:83`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/ExactReading.kt:32-64`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:221`

Main.kt 的调用点如 `score(p[1], p[2], p.getOrNull(3), …)` 看不出哪个参数是数据文件、集合或输出；ExactReading.kt:32-64 的 `g`、`m`、`e`、`s` 和 TableRun.kt:221 的 `o`、`e` 也是短名。
修复：ime-core 也用短名，但在命令分派处使用具名解构对读者帮助最大。

- 提出者：STYLE@G5（NIT，9/10）

#### F481 [NIT] 两个不同函数都叫 `writeSet`

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Main.kt:121`、`Main.kt:309`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:121`、`Main.kt:309`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:121`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:309`

Main.kt:121 的 `writeSet` 负责分派命令并返回退出码，Main.kt:309 的 `writeSet` 负责写文件。
修复：把前者改名为 `writeSetCommand`，与 `predictCommand` 保持一致。

- 提出者：STYLE@G5（NIT，9/10）

#### F482 [NIT] Learning.kt 中局部变量 `learner` 遮蔽了同名工厂函数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Learning.kt:30`、`KeystrokeRun.kt:20`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Learning.kt:30`、`KeystrokeRun.kt:20`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:30`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:42-43`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:49`

Learning.kt:30 的 `val learner = learner()` 保存调优后的运行，而同名工厂在 :42-43 又被调用。
修复：把局部变量改名为 `tuned`，或把工厂改名为 `newLearner()`。STYLE 另指出 :49 用一个带 `emptyList()` 的行承载自由文本说明，`row()` 会把它打印成零计数。

- 提出者：STYLE@G5（NIT，9/10）

#### F483 [NIT] TableRun 的 `Outcome` 由十个位置参数构成

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`TableRun.kt:55-58`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TableRun.kt:55-58`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:55`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:65`

TableRun.kt:55 的 `plus` 和 `ZERO` 各传十个位置参数（多为 Int），交换两个字段仍能编译通过。
修复：使用具名参数。STYLE 另指出 :65 的计时计数器是隐藏的可变状态：`type()` 会重置它们，`entries()` 不会。

- 提出者：STYLE@G5（NIT，9/10）

#### F484 [NIT] make-dialog-sets.py 复制常量并按路径加载模块

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/tools/make-dialog-sets.py:28`、`make-chat-set.py:73`、`make-dialog-sets.py:24-26`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/tools/make-dialog-sets.py:28`、`make-chat-set.py:73`、`make-dialog-sets.py:24-26`）
- 位置：`lib/ime-eval/tools/make-dialog-sets.py:28`；另见 `lib/ime-eval/tools/make-chat-set.py:73`、`lib/ime-eval/tools/make-chat-set.py:76`、`lib/ime-eval/tools/make-mixed-set.py:23`、`lib/ime-eval/tools/make-collision-set.py:33`、`lib/ime-eval/tools/make-collision-set.py:44`、`lib/ime-eval/tools/make-collision-set.py:106`、`lib/ime-eval/tools/make-new-words-set.py:162`、`lib/ime-eval/tools/make-chat-set.py:156`

make-dialog-sets.py:28 的 `KEEP_ONE_IN = 25` 和 `CONTEXT = 64` 重复了 make-chat-set.py:73、:76，而 chat/dialog 的划分依赖两边都为 25（bucket 1 对 bucket 0），应改读 `chat.KEEP_ONE_IN`、`chat.CONTEXT`；用 `importlib` 按路径加载带连字符的脚本（make-mixed-set.py:23 也是）可以改成从共享模块普通 import。
STYLE 在同一条中还提到：make-collision-set.py:33/44/106 和 make-new-words-set.py:162 调用 `open()` 未用 `with`（见 G5-30），make-chat-set.py:156 无参数时抛 IndexError 而不打印用法（见 G5-45）。

- 提出者：STYLE@G5（NIT，9/10）

#### F485 [NIT] make-collision-set.py 输出的表头与已提交文件不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`make-collision-set.py:105`、`lib/ime-eval/data/pinyin-collide.tsv:3`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`make-collision-set.py:105`、`lib/ime-eval/data/pinyin-collide.tsv:3`）
- 位置：`lib/ime-eval/tools/make-collision-set.py:105`；另见 `lib/ime-eval/data/pinyin-collide.tsv:3`

已提交的 data/pinyin-collide.tsv:3 表头经过手工编辑，多了「(the words of add.tsv as of 2026-10-05, the 2-char ones too)」，与 make-collision-set.py:105 打印的表头不同，因此该工具不能复现这个文件。
修复：让工具自己打印这行来源说明。

- 提出者：CORRECTNESS-1@S24（NIT，9/10）

#### F486 [NIT] make-collision-set.py 不拒绝未知模式

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`make-collision-set.py:97`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`make-collision-set.py:97`）
- 位置：`lib/ime-eval/tools/make-collision-set.py:93`；另见 `lib/ime-eval/tools/make-chat-set.py:156`

make-collision-set.py:93 中除 `words` 以外的任何模式都会执行 `set` 分支，拼写错误如 `word` 会静默地把 ARPA 文件当作示例文件处理。
修复：对未知模式打印用法并退出。GENERIC-1@S24 在同一条中另指出 make-chat-set.py:156 无参数时抛 IndexError（见 G5-45）。

- 提出者：GENERIC-1@S24（NIT，9/10）

#### F487 [NIT] 工具脚本有 shebang 却无可执行权限

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认）
- 位置：`lib/ime-eval/tools/make-collision-set.py:1`

make-collision-set.py、make-dialog-sets.py、make-mixed-set.py 和 make-new-words-set.py 带 shebang，但文件模式是 100644，而 make-chat-set.py 是 100755。
修复：统一 `chmod +x`，或去掉 shebang。CORRECTNESS-2@S24 在 G5-45 中也指出了这种模式不一致。

- 提出者：GENERIC-1@S24（NIT，9/10）

#### F488 [NIT] voice 与 text flavor 的 VoiceFeature 签名不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/18 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:22`、`CLAUDE.md:15-17`、`KawaiiBarComponent.kt:281`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:22`、`CLAUDE.md:15-17`、`KawaiiBarComponent.kt:281`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:22`；另见 `app/src/text/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:20`、`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:281`

voice flavor 声明 window(): InputWindow，text flavor 声明 InputWindow?，而 CLAUDE.md 要求两个 flavor 的 VoiceFeature 签名相同；KawaiiBarComponent.kt:281 使用 ?.let，在 voice 构建中产生多余安全调用的警告。
修复：两处都声明 InputWindow?。

- 提出者：GENERIC-1@S28（NIT，9/10）

#### F489 [NIT] youmo 补丁行超过 80 列，JNI 中重复 FindClass

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720`、`decoder.cc:133`、`impl.h:303`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720`、`decoder.cc:133`、`impl.h:303`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-transducer-modified-beam-search-decoder.cc:133`；另见 `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:303`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720`

offline-transducer-modified-beam-search-decoder.cc:133 和 offline-recognizer-transducer-impl.h:303 超出 vendored 代码的 80 列 cpplint 风格；jni/offline-recognizer.cc:720 再次调用 FindClass("java/lang/String")（string_cls2），可以保留第一次查找并在两个数组中复用（命名问题另见 G6-71）。

- 提出者：GENERIC-1@S52（NIT，9/10）

#### F490 [NIT] Offer、事件与标签的对应关系写了两遍

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:129-131`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:129-131`）
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:139`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:129`

candidateActions（129-132 行）把每个 EngineOffer 与其 EngineEvent 和标签配对，triggerAction 又用嵌套三元表达式重建同样的配对；新增第五个动作需要改两处，漏改第二处时该动作会静默无效。
修复：用一张 {EngineEvent, EngineOffer, text, domain} 静态表供两个方法读取，去掉三元表达式。

- 提出者：STYLE@G6（NIT，9/10）

#### F491 [NIT] 在 const 方法中用 const_cast 调用 table()

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:225`、`androidengine.h:221`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:225`、`androidengine.h:221`）
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:225`；另见 `app/src/main/cpp/androidengine/androidengine.h:221`

建议为 table() 增加 const 重载，或改为接收 const AndroidEngineConfig & 的静态辅助函数；并把 table() 与其他私有方法一起声明在 androidengine.h:221，而不是夹在数据成员之间。

- 提出者：STYLE@G6（NIT，9/10）

#### F492 [NIT] 同一个重抛-记录-回退代码块重复了七次

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:105`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:105`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:105`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:127`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:159`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:168`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:178`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:296`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:306`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:316`

105、127、159、168、178、306 和 316 行都重复 catch (CancellationException) { throw } catch (Exception) { Timber.w; fallback }，drain（line 296）还把 while、launch、withLock 和 try 嵌套了三层。
修复：用一个小的 suspend inline fun <T> orFallback(tag, fallback, block) 消除重复，并从 drain 的 lambda 中提取 recognizeStretch(samples) 以减少嵌套。

- 提出者：STYLE@G6（NIT，9/10）

#### F493 [NIT] 历史音频长度未与它依赖的 VAD 上限关联

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:72`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:72`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:72`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:180`

SAMPLE_RATE * 22 是按 maxSpeechDuration = 20f（line 180）加上停顿和前导音频推算的，但代码中没有把两者关联；若提高 VAD 上限，AudioHistory.before 会静默返回更短的前导音频。
修复：两者从同一个常量推导。

- 提出者：STYLE@G6（NIT，9/10）

#### F494 [NIT] 识别器变量命名为 r

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:143`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:143`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:88`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:143`

r: OfflineRecognizer 用于 stream、result 和 recognize，VoiceListener.drain 中也有 val r，改名为 recognizer 更清晰；recognize 中名为 stream 的参数还遮蔽了函数 stream()，所以 line 143 只能写 VoiceEngine.stream(...)。

- 提出者：STYLE@G6（NIT，9/10）

#### F495 [NIT] 两个按住说话视图复制了相同的常量

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWindow.kt:331`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWindow.kt:331`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWindow.kt:331`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWindow.kt:330`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldOverlay.kt:193`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldOverlay.kt:202`

CANCEL_RED 在 VoiceWindow.kt 和 VoiceHoldOverlay.kt:193 各定义一次，150 ms 的时长分别写成 ANIMATE_MS（line 330）和 APPEAR_MS（VoiceHoldOverlay.kt:202），已听到文本视图的设置也重复了。用一个共享样式对象可以避免两个面板逐渐不一致。

- 提出者：STYLE@G6（NIT，9/10）

#### F496 [NIT] voice 包内若干格式与其他文件不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:12`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:12`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:12`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:13`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoicePermissionActivity.kt:50`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceWaveView.kt:45`、`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldSession.kt:43`

VoiceFeature.kt 和 VoiceEngine.kt:13-14 的 import 顺序不对（android.content.Context 排在 org.* 之后）；VoicePermissionActivity.kt:50 写全了 android.content.Context，VoiceWaveView.kt:45 写全了 kotlin.math.abs，而两个文件都正常导入了其他名称；VoiceHoldSession.kt:43-44 有连续两个空行。

- 提出者：STYLE@G6（NIT，9/10）

#### F497 [NIT] 变量名 string_cls2 不表意

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:720`

建议改为 nbest_string_cls 之类的名字；youmo 代码块自成一体，改名不触及上游行，vendored 差异仍保持很小（重复 FindClass 的问题另见 G6-58）。

- 提出者：STYLE@G6（NIT，9/10）

#### F498 [NIT] FcitxDaemon 方法的 KDoc 过时，且读 clients 时未持锁

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:171`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:171`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:171`

KDoc 写着「Reuse a connection for remote service」，但远程服务已随插件一起移除，实际调用方是 FcitxApplication 和 DeveloperFragment；该方法读取 `clients` 时也没有持有 `lock`。
修复：更新文档，并在 `lock.withLock` 中读取。

- 提出者：CORRECTNESS-1@S30（NIT，9/10）

#### F499 [NIT] .new-words 中的名称读取时 trim、写入时不 trim

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:138`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:138`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:139`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:165`

`readIntoNew` 会 trim 每个名称，但 `setIntoNew`（:165）写入时不 trim：`新词 `（来自 `新词 .txt`）永远读不回「放进新词层」，含 `\n` 的名称会拆成两条；此外 `setIntoNew` 写入或改名失败时会留下 `.new-words.tmp`。
修复：两边都 trim（或拒绝这类名称），并在 `finally` 中删除临时文件。

- 提出者：CORRECTNESS-1@S31（NIT，9/10）

#### F500 [NIT] importFromInputStream 复制失败时残留临时文件

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:101`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:101`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:101`

`copyTo` 抛异常时 `cacheDir/<name>` 不会被删除，因为删除语句不在 `finally` 里。
修复：像 `readWords` 那样使用 try/finally。

- 提出者：CORRECTNESS-1@S31（NIT，9/10）

#### F501 [NIT] ContextChars 与 ContextMemory.KEEP 重复定义

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:1004`、`ContextMemory.kt:87-88`、`FcitxInputMethodService.kt:155`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:1004`、`ContextMemory.kt:87-88`、`FcitxInputMethodService.kt:155`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:1004`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:88`

ContextMemory.kt:88 把该值描述为「As much as the service reads」，两个常量可能各自漂移。
修复：用 `ContextMemory(keep = ContextChars)` 构造，让两者不会分开。

- 提出者：CORRECTNESS-1@S33（NIT，9/10）

#### F502 [NIT] 滑动手势的注释与代码不一致，阈值变量名互换

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:209`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:209`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:209`

注释说左滑显示数字行，代码（`!in -45f..45f`）对上滑或右滑也会显示；纵向判断用的是 `swipeThresholdX`、横向用的是 `swipeThresholdY`，两个值相等，所以行为不受影响。
修复：改正注释，并交换阈值的名字。

- 提出者：CORRECTNESS-1@S34（NIT，9/10）

#### F503 [NIT] 置顶图标 setAlpha 作用对象易误读，setTint 未 mutate

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardEntryUi.kt:48`、`ClipboardAdapter.kt:107`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardEntryUi.kt:48`、`ClipboardAdapter.kt:107`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardEntryUi.kt:48`

`Drawable.setAlpha` 接受 Int，所以 `apply {}` 里的 Float 调用绑定到了外层的 `ImageView.setAlpha(Float)`，效果相同，但读起来像是改了 drawable；对没有 `mutate()` 的资源 drawable 调用 `setTint`，还可能改变 `ic_baseline_push_pin_24` 的共享常量状态，而弹出菜单的图标也在用它。
修复：`drawable(...)!!.mutate()`，并在 ImageView 上显式设置 `alpha`。

- 提出者：GENERIC-1@S36（NIT，9/10）

#### F504 [NIT] 未使用的 setEnabled 可能通知位置 -1

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`InputMethodListAdapter.kt:32-37`、`InputMethodPickerDialog.kt:32`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`InputMethodListAdapter.kt:32-37`、`InputMethodPickerDialog.kt:32`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/dialog/InputMethodListAdapter.kt:32`

没有代码调用 `setEnabled`；而在 `enabledIndex == -1`（当前输入法不在列表中）时，它会调用 `notifyItemChanged(-1)`。
修复：删除它，或保护这个索引。

- 提出者：GENERIC-1@S36（NIT，9/10）

#### F505 [NIT] SingleDividerDecoration 每帧都解析主题属性

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`SingleDividerDecoration.kt:36-37`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`SingleDividerDecoration.kt:36-37`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/dialog/SingleDividerDecoration.kt:36`

`styledDimenPxSize` 在每一帧的 `onDraw` 中被调用两次。
修复：在构造时或首次绘制时解析一次。

- 提出者：GENERIC-1@S36（NIT，9/10）

#### F506 [NIT] companion object 夹在 override 方法之间

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`InputConnectionEditor.kt:103`、`KeyEventRelay.kt:81`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`InputConnectionEditor.kt:103`、`KeyEventRelay.kt:81`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/editing/InputConnectionEditor.kt:103`

InputConnectionEditor.kt:103 的 `private companion object` 位于几个 override 方法中间。
修复：像 `KeyEventRelay` 那样把它移到类的末尾。

- 提出者：GENERIC-1@S36（NIT，9/10）

#### F507 [NIT] 测试中 getResourceAsStream 打开的流从未关闭

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`ImportedDictionariesTest.kt:26`、`ImportedTablesTest.kt:39`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ImportedDictionariesTest.kt:26`、`ImportedTablesTest.kt:39`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionariesTest.kt:26`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/table/ImportedTablesTest.kt:39`

`getResourceAsStream(...)!!.readBytes()` 不关闭流，ImportedTablesTest.kt:39 也是同样的写法。
修复：改成 `.use { it.readBytes() }`。

- 提出者：CORRECTNESS-1@S50（NIT，9/10）

#### F508 [NIT] 测试注释说只有文本词典有禁用形式，实际词包也有

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PinyinDictionaryTypeTest.kt:37`、`PinyinDictionary.kt:21`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinDictionaryTypeTest.kt:37`、`PinyinDictionary.kt:21`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionaryTypeTest.kt:37`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionaryTypeTest.kt:21`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/dict/PinyinDictionary.kt:21`

`.words.disable` 同样会被识别（:21、PinyinDictionary.kt:21）。
修复：把注释改为「only text and word packs」。

- 提出者：GENERIC-1@S50（NIT，9/10）

#### F509 [NIT] reloadPinyinDict 等函数名暗示局部重载，实际重载全部

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`AddonSubconfig.kt:8`、`Fcitx.kt:71`、`EngineBridge.kt:212-219`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`AddonSubconfig.kt:8`、`Fcitx.kt:71`、`EngineBridge.kt:212-219`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/AddonSubconfig.kt:8`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/AddonSubconfig.kt:18`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:236`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:264`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:317`

`reloadPinyinDict()` 和 `reloadPinyinCustomPhrase()`（:18）都只是调用 `reloadEngine()`。
修复：在 PinyinDictionaryFragment.kt:236、:264 和 PinyinCustomPhraseFragment.kt:317 直接调用 `reloadEngine()`，然后删除这两个函数。

- 提出者：STYLE@G7（NIT，9/10）

#### F510 [NIT] 循环中不断重新赋值的 var x 被惰性 filter 读取

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`ClearURLs.kt:49`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ClearURLs.kt:49`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:49`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:51`

这段代码是否正确取决于序列是惰性的（见 :51 的注释）。
修复：把 `x` 改名为 `current`，并在循环内部检查每个 provider 是否仍然适用。

- 提出者：STYLE@G7（NIT，9/10）

#### F511 [NIT] AppPrefs 与 ThemePrefs 中若干处格式与文件其余部分不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`AppPrefs.kt:290`、`AppPrefs.kt:57-62`、`ThemePrefs.kt:178-181`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`AppPrefs.kt:290`、`AppPrefs.kt:57-62`、`ThemePrefs.kt:178-181`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:291`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:295`、`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt:57`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemePrefs.kt:177`

振幅 lambda 在错误的缩进处闭合（:295-296），`hidden { switch(` … `) }` 块（:57-73）的闭合方式与文件其余部分不同，ThemePrefs.kt:177-183 的参数没有对齐。
修复：统一格式。

- 提出者：STYLE@G7（NIT，9/10）

#### F512 [NIT] PickerWindow 的 else 分支两次检查 CommitAction

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`PickerWindow.kt:95`、`PickerWindow.kt:102`、`SymbolPanel.kt:31`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PickerWindow.kt:95`、`PickerWindow.kt:102`、`SymbolPanel.kt:31`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:94`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:98`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:185`

else 分支对 CommitAction 做了两次类型检查，应给 CommitAction 单独的分支。
:98 还重新实现了 :185 已调用的 sidePanel.shows(RECENT, …)。

- 提出者：STYLE@G8（NIT，9/10）

#### F513 [NIT] T9Keyboard 通过扫描子视图查找 BaseKeyboard 的行

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`T9Keyboard.kt:159`、`BaseKeyboard.kt:89`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`T9Keyboard.kt:159`、`BaseKeyboard.kt:89`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:159`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:89`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:195`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:63`

filterIsInstance<ConstraintLayout>() 依赖 BaseKeyboard 的内部结构；建议把 BaseKeyboard.keyRows（:89）改为 protected。
STYLE 另指出 show()（:195）每次按键重建列中所有 TextView，SIDE/RIGHT（:63-64）的命名没有说明它们决定什么尺寸。

- 提出者：STYLE@G8（NIT，9/10）

#### F514 [NIT] 日志写「Skip attaching」但代码并未跳过

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt:115-116`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt:115-116`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/wm/InputWindowManager.kt:116`

InputWindowManager 的日志说跳过附加，但代码继续执行；InputView.startInput 依赖重新附加 KeyboardWindow 来重置它，加 return 会改变行为。
建议：改写日志文字，而不是改代码。

- 提出者：GENERIC-1@S39（NIT，9/10）

#### F515 [NIT] 长按编辑页未调用 super，标签与无障碍反馈缺失

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:68-69`、`PopupOverridesFragment.kt:245-246`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/KeyCaps.kt:54`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:68-69`、`PopupOverridesFragment.kt:245-246`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/KeyCaps.kt:54`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:68`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:246`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/KeyCaps.kt:57`

onViewCreated 没有调用 super；askLabel（:246）静默丢弃含空白的标签，不提示用户。
KeyCaps.key（KeyCaps.kt:57）把 contentDescription 只设为标签，屏幕阅读器读不到长按提示和「已修改」轮廓。

- 提出者：GENERIC-1@S41（NIT，9/10）

#### F516 [NIT] 嵌套 Pair/Triple 代替具名类型

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:183-185`、`PopupOverridesFragment.kt:155-158`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:81-82`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:183-185`、`PopupOverridesFragment.kt:155-158`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:81-82`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:182`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:64`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:154`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:81`

例如 PinyinDictionaryFragment 的 Layer.BASE to (title to hint)，而 enum Layer（:64）本可持有这两个字段；PopupOverridesFragment.kt:154 的 text to (can to do)；LicensesFragment.kt:81 的 Triple 与 VoiceFeature.Credit(title, licence, url) 重复。
建议：使用小型具名类型或枚举属性。

- 提出者：STYLE@G8（NIT，9/10）

#### F517 [NIT] SAF 读写与错误处理被多处复制粘贴

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:247`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:104`、`PinyinDictionaryFragment.kt:227`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:247`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:104`、`PinyinDictionaryFragment.kt:227`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:247`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinCustomPhraseFragment.kt:272`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:104`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:129`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:227`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PinyinDictionaryFragment.kt:250`

openInputStream/openOutputStream(uri) ?: throw IOException(...) 出现 6 次（此处、:272、UserWordsFragment 104/129、PinyinDictionaryFragment 227/250，后者使用全限定的 java.io.IOException），成对的 IOException/SecurityException catch 出现 4 次。
建议：在 utils/ContentResolver.kt 中提供辅助函数。

- 提出者：STYLE@G8（NIT，9/10）

#### F518 [NIT] 新 UI 代码未使用本包的视图 DSL

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`KeyCaps.kt:65-74`、`PopupOverridesFragment.kt:85`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:86-90`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`KeyCaps.kt:65-74`、`PopupOverridesFragment.kt:85`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:86-90`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/KeyCaps.kt:65`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PopupOverridesFragment.kt:85`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:86`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchFragment.kt:73`

KeyCaps（65-74）、PopupOverridesFragment（85-239）、PunctuationEditorFragment（86-243）和 SettingsSearchFragment（73）使用原始的 LayoutParams(-1/-2, …) 和命令式 addView，而周围代码（包括同级的 UserWordListFragment）使用 splitties 的 matchParent/wrapContent/lParams。
评审未单列修复方案，隐含应改用同样的 DSL。

- 提出者：STYLE@G8（NIT，9/10）

#### F519 [NIT] 编辑对话框标题未显示所编辑的卡片

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:127`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:127`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:127`

编辑已有卡片时，对话框标题使用页面标题 `args.title`，看不出打开的是哪张卡片。
建议：标题显示该卡片的键（例如 `，`）。

- 提出者：CORRECTNESS-1@S42（NIT，9/10）

#### F520 [NIT] 每次绑定都遍历整个标点列表计数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:196`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:196`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:196`

每张卡片绑定时都执行 `entries.count { it.key == entry.key }`，`notifyDataSetChanged` 后为 O(n²)；在约 30-60 项时没有可测量的开销。
建议：每次变更时重建按键计数的 map。

- 提出者：GENERIC-1@S42（NIT，9/10）

#### F521 [NIT] ViewHolder 不持有视图字段，每次绑定都 findViewById

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:183`、`SettingsSearchFragment.kt:80-81`、`PunctuationEditorFragment.kt:134`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:183`、`SettingsSearchFragment.kt:80-81`、`PunctuationEditorFragment.kt:134`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:192`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsSearchFragment.kt:80-81`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:134`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:165`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:190`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:204`

`PunctuationEditorFragment` 的 ViewHolder 没有字段，每次绑定都执行 `findViewById`；`SettingsSearchFragment.kt:80-81` 按位置把 `getChildAt(i)` 强转为 `TextView`；表头偏移 `±1` 在第 134/165/190/204 行重复出现。
建议：沿用 `WordHolder` 模式，并像 `ThemeListAdapter.OFFSET` 一样增加 `OFFSET` 常量。

- 提出者：STYLE@G8（NIT，9/10）

#### F522 [NIT] `object Nothing` 遮蔽 kotlin.Nothing

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:79`、`SettingsIndex.kt:34`、`InputMethodSettings.kt:200-205`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:79`、`SettingsIndex.kt:34`、`InputMethodSettings.kt:200-205`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:79`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:34`

`SettingsIndex` 第 79 行的 `private object Nothing` 与 `kotlin.Nothing` 同名，建议改名为如 `DiscardingDataStore`。
同一发现还指出第 34 行的 `InputMethods` 重复列出 `InputMethodSettings.of` 的名称，新增输入法页面不会被搜索到，与类文档不符；建议由 InputMethodSettings 暴露页面列表。

- 提出者：STYLE@G8（NIT，9/10）

#### F523 [NIT] IniTest 固化了无 key 时 set 重载的不对称行为

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/utils/IniTest.kt:56`、`IniTest.kt:84`、`IniTest.kt:117`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/utils/IniTest.kt:56`、`IniTest.kt:84`、`IniTest.kt:117`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/Ini.kt:117`；另见 `app/src/test/java/org/fcitx/fcitx5/android/utils/IniTest.kt:83`

无 key 时 `get()` 返回 null、`set(str=)` 什么也不做，而 `set(raw=)` 会把节点挂到根上（`Ini.kt:117`）；这些测试会把这种不对称固定下来。
建议：若是有意为之，在 `Ini.set(raw)` 上加一行说明原因；否则先让两个 `set` 重载行为一致。

- 提出者：GENERIC-1@S51（NIT，9/10）

#### F524 [NIT] 圆形自适应图标与普通图标内容完全相同

- 来源：第一轮（整仓 @07d2778） · 共识：1/5 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`mipmap-anydpi-v26/ic_launcher_round.xml:1`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`mipmap-anydpi-v26/ic_launcher_round.xml:1`）
- 位置：`app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml:1`

`mipmap-anydpi-v26/ic_launcher_round.xml` 与 `ic_launcher.xml` 字节相同，`ic_launcher_round_debug.xml` 与 `ic_launcher_debug.xml` 也相同。
可改为别名，或让 manifest 的 `roundIcon` 指向同一个 `resValue`；对运行时没有可测的影响。

- 提出者：PERFORMANCE@G9（NIT，9/10）

#### F525 [NIT] 工具指纹每次构建至少计算两次

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:171`、`ToolFingerprint.kt:19`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:171`、`ToolFingerprint.kt:19`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:164`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:167`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:182`

`target.provider {}` 不缓存值，而这个 provider 同时供给两个 `toolCode` 输入（第 167、182 行），所以打开每个 jar、读取所有可达类（含 kotlin-stdlib）的类图遍历在每次 app 构建中至少运行两次，即使两个任务都是 up to date。
建议：缓存结果，例如在 provider 中捕获一个 `lazy`，或使用共享的 BuildService。

- 提出者：PERFORMANCE@G1（NIT，8/10）

#### F526 [NIT] 后续 jar 打开失败时已打开的 jar 不会关闭

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:21`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:21`）
- 位置：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:21`

`.map(::ZipFile)` 在 `try` 之前执行；如果后面某个 jar 打开失败（损坏或仍在写入），已经打开的 jar 不会被关闭，文件句柄在 Gradle JVM 中泄漏。
建议：在 `try` 内逐个打开 jar 并加入一个列表，由 `finally` 块统一关闭。

- 提出者：PERFORMANCE@G1（NIT，8/10）

#### F527 [NIT] 构建期 hotword 过滤与运行时规则重复且无测试约束

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:132-137`、`lib/ime-core/.../input/voice/VoiceHotwords.kt:33`、`VoiceEngine.kt:203`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:132-137`、`lib/ime-core/.../input/voice/VoiceHotwords.kt:33`、`VoiceEngine.kt:203`）
- 位置：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:133`

VoiceDataPlugin 构建期的 hotword 过滤（`\p{IsHan}{2,12}`、不含代理对）与运行时的 `VoiceHotwords.spelled`（`UnicodeScript.HAN`、长度 2..12）目前等价，但 `VoiceHotwordsTest` 只覆盖运行时那一份，任一侧改动都会静默漂移。
建议：用调用 `spelled` 的 dict-tool 子命令生成 `hotwords.txt`；`:lib:ime-dict-tool` 已依赖 ime-core，并已通过 `javaexec` 运行。

- 提出者：TESTING@G1（NIT，8/10）

#### F528 [NIT] 仅 app 使用的 flavor 目录硬编码在通用 detekt 插件中

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`）
- 位置：`build-logic/convention/src/main/kotlin/DetektConventionPlugin.kt:43`

`src/text/java` 和 `src/voice/java` 属于 app 自身的关注点，却硬编码在对所有模块生效的 DetektConventionPlugin 中。
建议：从 app/build.gradle.kts 添加这些目录，或从 AGP 的 flavor 源集推导，使约定插件保持通用。

- 提出者：STYLE@G1（NIT，8/10）

#### F529 [NIT] 改名后仍保留上游的项目名和缓存名

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`settings.gradle.kts:19`、`flake.nix:2`、`.github/workflows/nix.yml:22`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`settings.gradle.kts:19`、`flake.nix:2`、`.github/workflows/nix.yml:22`）
- 位置：`settings.gradle.kts:19`；另见 `flake.nix:2`、`.github/workflows/nix.yml:22`

`rootProject.name = "fcitx5-android"`、flake.nix:2 的描述和 nix.yml:22 的 cachix 缓存 `fcitx5-android` 仍沿用上游名称，而 fork 的 token 无法推送到上游的缓存。
STYLE@G1 没有单独写出修法；关于 cachix 缓存的修法见 G1-01。

- 提出者：STYLE@G1（NIT，8/10）

#### F530 [NIT] .gitignore 没有忽略 keystore 文件

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`README.md:91`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`README.md:91`）
- 位置：`.gitignore:60`；另见 `lib/ime-core/src/test/resources/remote/test-server.p12`

`*.p12`、`*.jks`、`*.keystore` 的忽略规则都被注释掉了，而 README 指导用户创建 `release.p12`，复制进工作树的密钥可能被误提交。
GENERIC-1@S1 还指出 `lib/ime-core/src/test/resources/remote/test-server.p12` 已提交，但自离线化改动（69f2562a）以来已无任何引用。
建议：忽略这些模式，并为测试 fixture 设例外。

- 提出者：GENERIC-1@S1（NIT，8/10）

#### F531 [NIT] baseline-lib-ime-core.xml 条目 ID 内嵌行注释

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`config/detekt/baseline-lib-ime-core.xml:7`、`lib/ime-core/.../core/FormattedText.kt:39-40`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`config/detekt/baseline-lib-ime-core.xml:7`、`lib/ime-core/.../core/FormattedText.kt:39-40`）
- 位置：`config/detekt/baseline-lib-ime-core.xml:7`

`NestedBlockDepth` 的基线 ID 包含 `// called from JNI`，修改这条注释就会让该发现重新出现，与 G1-20 属于同类的基线 ID 脆弱问题（CORRECTNESS-1@S3 在 G1-20 的发现中也提到此条）。
建议：给 `FormattedText.fromByteCursor` 加 `@Suppress("NestedBlockDepth")` 或降低其嵌套深度，然后删除该条目。

- 提出者：CORRECTNESS-2@S3（NIT，8/10）

#### F532 [NIT] 续跑时才设置 EVAL 不会输出 probe.tsv.zst

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/engine-data.sh:184-185`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/engine-data.sh:184-185`）
- 位置：`lib/ime-dict-tool/engine-data.sh:184`；另见 `lib/ime-dict-tool/engine-data.sh:185`

在续跑中首次设置 EVAL 时 `probe` 会运行（engine-data.sh:184），但 `compress`（:185）与 `manifest` 已标记完成，所以 `out/probe.tsv.zst` 不会写出，manifest 也不列出它。
建议：`probe` 运行时同时删除 `done/compress` 与 `done/manifest`。GENERIC-1@S4 在其 pinyin.data 过期的发现（G2-04）中也提到 `done/compress` 未清除的问题。

- 提出者：CORRECTNESS-2@S4（NIT，8/10）

#### F533 [NIT] JsonStrings 接受带正负号的畸形 \u 转义

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/JsonStrings.kt:61`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/JsonStrings.kt:61`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/JsonStrings.kt:61`

JsonStrings.kt:61 用 `toInt(16)` 解析转义，它接受前导正负号，于是 `\u-001` 解码为 U+FFFF、`\u+041` 解码为 `A`，而类文档说任何畸形输入都是错误。
建议：要求四个字符全是十六进制数字。

- 提出者：CORRECTNESS-1@S4（NIT，8/10）

#### F535 [NIT] clean -o 指向输入目录时会就地覆盖原始分片

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`Main.kt:345-361`、`WebText.kt:76-93`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:345-361`、`WebText.kt:76-93`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:348`

Main.kt:348 中每个输出以输入文件的名字写出，`WebText.write` 再把它 rename 覆盖到源文件上；中途停止会留下部分已清洗、部分原始的分片，原始下载随之丢失。
engine-data.sh 使用不同目录，但可在已有的重名 `require` 旁加一行规范目录检查来防止这种情况。

- 提出者：CORRECTNESS-1@S5（NIT，8/10）

#### F536 [NIT] 部分错误逃出 runCli，以裸堆栈结束

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Mixer.kt:72`、`Main.kt:76-95`、`ArpaModel.kt:117-150`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Mixer.kt:72`、`Main.kt:76-95`、`ArpaModel.kt:117-150`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Mixer.kt:72`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:76-95`

Mixer.kt:72 的 `check()` 抛 IllegalStateException，candidates.tsv 中的短行（`passes` 读 `f[7]`）抛 IndexOutOfBoundsException，`runCli`（Main.kt:76-95）都不捕获；`f[1].toInt()` 的 NumberFormatException 虽被捕获，但消息不含文件名和行号。
建议：此处改用 `require`，行解析使用带行号的 `SourceException`。

- 提出者：GENERIC-1@S5（NIT，8/10）

#### F537 [NIT] MainTest 对 lexicon 拒绝只检查退出码

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:192`、`Main.kt:83-95`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:192`、`Main.kt:83-95`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:192`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:194`

MainTest.kt:192、194 只断言退出码为 1，而任何 SourceException、IOException 或 IAE 都会得到 1。
建议：再断言 err 点名 add.tsv 并含「is no reading」，同时确认先前的 pack 文件没有被改动。

- 提出者：CORRECTNESS-2@S6（NIT，8/10）

#### F538 [NIT] MainTest 精确比对 ime-core 的完整错误文案

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`MainTest.kt:274`、`PinyinDataBuilder.kt:94`、`DataFile.kt:49`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`MainTest.kt:274`、`PinyinDataBuilder.kt:94`、`DataFile.kt:49`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:274`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:287`

MainTest.kt:274 与 :287 精确比对「the model has no <unk>\n」和「file kind 2, expected 1\n」，改写 ime-core 中任一消息都会在行为不变的情况下打破 dict-tool 的测试。
建议：断言退出码 1 加关键片段。

- 提出者：TESTING@G2（NIT，8/10）

#### F539 [NIT] Mixer 在内存峰值时保留重复的概率列

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`Mixer.kt:43`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Mixer.kt:43`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Mixer.kt:88`

Mixer.kt:88 的 `p2`/`bigramProb` 与 `p3`/`trigramProb` 是分开的 DoubleColumn，与基础模型、ChatCounts 和 KneserNey 同时存活；engine-data.sh 记录 mix 需要 7 GB。
三元循环之后 `p2`、`p3` 只用于填充 log10 列；建议原地转换并传给 `ArpaModel`，每个 n-gram 可省 8 字节。

- 提出者：PERFORMANCE@G2（NIT，8/10）

#### F541 [NIT] latin_counts.py 的工作进程数写死为 6

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lexicon/tools/latin_counts.py:41`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/tools/latin_counts.py:41`）
- 位置：`lexicon/tools/latin_counts.py:41`

latin_counts.py:41 的 `Pool(6)` 忽略机器的核数。
建议用 `os.cpu_count()` 或把数量作为参数传入。

- 提出者：PERFORMANCE@G2（NIT，8/10）

#### F542 [NIT] 一音一字检查按 UTF-16 代码单元计数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:46`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:46`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/Misreadings.kt:46`

`f[0].length` 计的是 UTF-16 代码单元，含增补平面字符（如 CJK 扩展 B）的词会在构建时被拒绝。
建议：改用 `codePointCount`。

- 提出者：CORRECTNESS-1@S7（NIT，8/10）

#### F543 [NIT] KeyboardTest“不重复提交”检查未触达被测守卫

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/KeyboardTest.kt:159`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Keyboard.kt:131`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/KeyboardTest.kt:159`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Keyboard.kt:131`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/KeyboardTest.kt:156`

`char(',')` 之后测试又输入了 `d`，所以 REFINE 之前的快照已是 `commit == ""`；删除 `Keyboard.refine()` 中的 `shown.copy(commit = "")` 测试仍会通过。
建议：在提交类事件（`char(',')` 或部分选择 `char('2')`）之后直接发送 REFINE，并断言 `commit == ""` 且 `!handled`。

- 提出者：CORRECTNESS-2@S16（NIT，8/10）

#### F544 [NIT] SharedLexiconTest 忽略 delete() 的返回值

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:101`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:101`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:100`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/SharedLexiconTest.kt:101`

如果拼音日志删除失败，“told at the next start”检查（第 101-105 行）会在没有测试恢复路径的情况下通过。
建议：改为 `assertTrue(dir.resolve(Engines.USER_PINYIN).delete())`。

- 提出者：GENERIC-1@S16（NIT，8/10）

#### F545 [NIT] 码表注册信息分散在三个列表中

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`LibimeMigration.kt:22`、`Engines.kt:949`、`LibimeMigration.kt:164`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`LibimeMigration.kt:22`、`Engines.kt:949`、`LibimeMigration.kt:164`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:22`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:128`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:936`

`INPUT_METHODS` 重复了 `"engine-wubi"` 等名字，`TABLE_CONFIGS`（LibimeMigration.kt:128）与 `Engines.TABLES`（Engines.kt:936）列出同样的码表，新增一张码表需要三处协同修改。
建议：把 libime 名放到 `TableMethod` 上，并由它派生另外两个列表。

- 提出者：STYLE@G3（NIT，8/10）

#### F546 [NIT] toMatches依赖有序遍历却未在参数类型上约束

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:200`、`SyllableMatches.kt:23`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:200`、`SyllableMatches.kt:23`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/SpellingIndex.kt:200`

`SyllableMatches.indexOf` 对键做二分查找，若调用者传入无序 Map，查找会静默出错；目前所有调用者都传 `TreeMap`。
建议：把 `toMatches(m: Map<Int, Int>)` 的参数改为 `SortedMap<Int, Int>` 以强制保证有序。

- 提出者：GENERIC-1@S10（NIT，8/10）

#### F547 [NIT] 拟离开当前页时测试循环会挂起而非失败

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:569`、`PinyinSession.kt:152`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:569`、`PinyinSession.kt:152`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:569`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionRefineTest.kt:101`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionHabitTest.kt:49`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionHabitTest.kt:169`

`Select(-1)` 什么也不做却仍报告按键已处理，`s.commit` 保持为空，循环永不结束；PinyinSessionHabitTest.kt:49 和 :169 同样未检查的 `indexOf` 会以误导性的消息失败。
建议：像 PinyinSessionRefineTest:101 那样限制循环次数，或断言索引至少为 0。

- 提出者：CORRECTNESS-1@S18（NIT，8/10）

#### F548 [NIT] 笔画测试从同一对象读回learning，未验证传递给拼音

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:159`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:159`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:159`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:78`

`assertFalse(l.learning)` 从同一对象读回值，应断言 `pinyin.learning`，以检查 StrokeLookup 把设置传给了拼音（EnginesTest 的 `nothingIsLearnedWhereTheAppSaysNotTo` 已端到端覆盖）。
第 78 行用四个位置布尔参数构造 `Snapshot`；改用 StrokeLookup 那样的命名参数，字段顺序变化时也不会出错。

- 提出者：CORRECTNESS-1@S18（NIT，8/10）

#### F549 [NIT] 笔画测试中𠀀条目的用途没有说明

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:37`、`Strokes.kt:82`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:37`、`Strokes.kt:82`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:37`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokesTest.kt:23`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/Strokes.kt:82`

𠀀（扩展 B）用于检查 `Strokes.read` 只保留 BMP 字（`Strokes.kt:82`），唯一证据是它不在 `find("hzs")` 中，而第 23 行注释只列出 㐄。
建议：把它加入注释，或显式断言它被排除。

- 提出者：GENERIC-1@S18（NIT，8/10）

#### F550 [NIT] 辅助函数shown会写入它正在检查的存储

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:58`、`TableUser.kt:70`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:58`、`TableUser.kt:70`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:58`

一旦什么都没学，`wqw` 和 `wqvb` 无路可走，`shown` 便会上屏 你 并把一次选择记入正在使用的存储；`whatIsForgottenStaysForgotten` 中的比较因此包含测试本不想做的选择，只是因为先按编码长度排序才通过。
建议：读取候选时不要上屏。

- 提出者：CORRECTNESS-1@S19（NIT，8/10）

#### F551 [NIT] 「整句上屏不起新词组」测试只覆盖全新会话

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:394`、`TableSession.kt:261`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:394`、`TableSession.kt:261`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:394`

提交 你好 之前 `recent` 为空，所以回归成 `if (chars.size != 1) return`（不调用 `recent.clear()`）仍会通过。
建议：依次输入 你（wqiy）、你好（wqvb）、们（wun），断言不提供 你们。

- 提出者：CORRECTNESS-2@S19（NIT，8/10）

#### F552 [NIT] 「自动词组长到最长编码」未被检查

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:309-320`、`TableSession.kt:64`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:309-320`、`TableSession.kt:64`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:309`

在 `autoPhraseLength = -1`、码长 = 4 下，唯一的断言针对两字词组，把 -1 映射到任何不小于 2 的值都能通过。
建议：再断言 `wwww` 下提供四字词组 你们你们。

- 提出者：CORRECTNESS-2@S19（NIT，8/10）

#### F553 [NIT] FakePinyin的注释错位，picked标志从不重置

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:427-429`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:427-429`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:427`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:455`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableSessionTest.kt:500`

「nini」注释位于 `learning` 上方而非 `picked`；`picked` 在 Reset 和 CommitRaw 后仍保留（第 455 行），之后某次查询的 CommitRaw 会因假对象而得到多余的 你 前缀。
第 500-501 行（NextPage 后 `Select(1)` 返回 窝）只因假对象忽略分页才通过。
建议：在 Reset 和 CommitRaw 时清除 `picked`，不要通过该假对象断言分页结果。

- 提出者：GENERIC-1@S19（NIT，8/10）

#### F554 [NIT] Matrix用四个可空字段并以q != null区分两种形态

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Matrix.kt:11-18`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Matrix.kt:11-18`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Matrix.kt:11`

每个方法都使用 `!!`（`scales!!`、`kernel!!`、`f!!`）。
建议：改为带量化与浮点两个子类的 sealed class，使非法组合无法构造。

- 提出者：STYLE@G3（NIT，8/10）

#### F555 [NIT] 编码与文本用制表符拼接成字符串键（基本类型偏执）

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:213`、`Engines.kt:943`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:213`、`Engines.kt:943`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:213`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:57`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:138`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:143`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:930`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:759`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:785`

code 和 text 拼接为 `$code\t$text`，又在第 57、138、143 行用 `indexOf` 拆开；应改用 data class 作键。
`Engines.addedTableFiles`（Engines.kt:930）返回一个两元素列表，调用方用 `.first()` 和 `.last()` 读取（第 759、785 行），改为两个具名函数可以说明哪个是哪个文件。

- 提出者：STYLE@G3（NIT，8/10）

#### F556 [NIT] ChineseNumbers 对小时为十的「X点五十」处理不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:262`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:262`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:262`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:274`

「九点五十」整体保留为汉字，但「十点五十」变成「10点五十」：`ten()` 转换了小时，`roughOrMinutes`（ChineseNumbers.kt:274）却把分钟保留为汉字；「十二点五十」也一样（ChineseNumbers.kt:262）。
建议当小时后面跟着「点」和不带「分」的数字时，小时也保留为汉字。

- 提出者：CORRECTNESS-2@S14（NIT，8/10）

#### F557 [NIT] LibimeImportTest 的注释样例暗示了并不存在的注释规则

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:58`、`SourceException.kt:81`、`LibimeImport.kt:153`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:58`、`SourceException.kt:81`、`LibimeImport.kt:153`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:58`

`splitValues` 没有注释规则，样例「# a comment」（LibimeImportTest.kt:58）被丢弃只是因为它的第三个记号不是数字；像「# hao」这样的两记号行会导入一个读作 hao 的词「#」。
建议换成一行明显畸形的输入，或加一个固定两记号情况的测试。

- 提出者：CORRECTNESS-2@S20（NIT，8/10）

#### F558 [NIT] LibimeImportTest 每个测试都重建 polyphones

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:117`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:117`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/LibimeImportTest.kt:117`

JUnit 4 为每个测试方法新建实例，所以 `polyphones` 这个 `PinyinData`（LibimeImportTest.kt:117）为全部 10 个测试各构建一次，而只有 3 个测试用到它。
建议像 UserModelTest 处理 `data` 那样，把它放进 companion object。

- 提出者：GENERIC-1@S20（NIT，8/10）

#### F559 [NIT] UserModel 承担职责过多，总量重算逻辑重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:120`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:120`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:26`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:120`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:139`

UserModel 共 404 行（低于 detekt 的 600 行上限），却同时包含计数与概率、音节 trie、屏蔽、词包分数与分层、列出以及共享文本导出；trie 相关成员（`childSyllables`、`childNodes`、`nodeWords`、`addToTrie`、`removeFromTrie`、`inserted`）可以拆成独立的类。
总量重算在 120-121 行和 139-140 行重复出现，建议提取 `recomputeTotal()`。

- 提出者：STYLE@G4（NIT，8/10）

#### F560 [NIT] ChineseNumbers 中的命名与布尔标志难以理解

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:105`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:105`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:105`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:183`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:199`

ChineseNumbers.kt:105 的 `Run` 带有两个互斥的布尔值 `decimal` 与 `time`，用 `Kind` 枚举表达更直接；`Reading` 同时有名为 `digit` 的属性和函数（183、199 行）；`integer()` 返回 String，辅助函数 `single`、`ten` 的名称没有说明它们判断什么；`time()` 与 `decimal()` 用单字母游标 `m`、`f`。
建议使用 `minuteEnd`、`isSingleDigitAsDigit` 这类名称。

- 提出者：STYLE@G4（NIT，8/10）

#### F561 [NIT] consumeSwipe 按轴分支读写平行的 X/Y 字段

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:248`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:248`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/keyboard/KeyGestureRecognizer.kt:248`

`consumeSwipe`（KeyGestureRecognizer.kt:248）两次对 `SwipeAxis` 做 `when`，读写六个平行字段（`swipeLastX/Y`、`swipeX/YUnconsumed`、`swipeTotalX/Y`）。
建议每个轴用一个小的 `AxisTracker(last, unconsumed, total)`，去掉分支和平行字段。

- 提出者：STYLE@G4（NIT，8/10）

#### F562 [NIT] ContextMemory 的 LRU 注释与代码不符

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:26`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:26`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:25`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:35`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ContextMemory.kt:82`

注释（ContextMemory.kt:25）说「the app last written in last」，但映射按访问排序，读取也会重排：`remembers()`（ContextMemory.kt:35）、`context()` 和 `deleted()` 都会，因此查询方法 `remembers()` 会改变淘汰顺序；第 82 行手动淘汰，而 `removeEldestEntry` 可以代劳。
建议改写注释，或使用 `peek` 式的读取。

- 提出者：STYLE@G4（NIT，8/10）

#### F563 [NIT] `ksc` 与 `learn` 的会话计算了无人读取的预测

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`Main.kt:397`、`Learning.kt:28`、`PinyinSession.kt:77`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Main.kt:397`、`Learning.kt:28`、`PinyinSession.kt:77`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Main.kt:397`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Learning.kt:55`

Main.kt:397（`ksc`）和 Learning.kt:55（`learn`）的会话保留默认的 `prediction = true`，每个样本最后一次上屏都会运行 `Predictor.predict`，而 `KeystrokeRun` 从不读取结果。
修复：像 `PredictLearning` 一样传 `prediction = false`。

- 提出者：PERFORMANCE@G5（NIT，8/10）

#### F564 [NIT] `tune` 的网格搜索只用单线程

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`Tuning.kt:85-90`、`Main.kt:103`、`Main.kt:113`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`Tuning.kt:85-90`、`Main.kt:103`、`Main.kt:113`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/Tuning.kt:85`

Tuning.kt:85 的 49 个网格点（各 4 次运行）和 8 个按键网格点彼此独立、各有自己的 `PinyinRun`，却串行执行，而 `pinyin`/`ksc` 通过 `dealt` 使用全部核心。
修复：把网格点分发到多个线程，墙钟时间约可按核心数缩短，结果与顺序无关。

- 提出者：PERFORMANCE@G5（NIT，8/10）

#### F565 [NIT] run-on-device.sh 每个集合都重装两个 APK

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认）
- 位置：`lib/ime-eval/run-on-device.sh:16`；另见 `lib/ime-eval/run-on-device.sh:19`

run-on-device.sh:16 每跑一个集合都重新安装 debug APK（含引擎数据与模型）和测试 APK，连续跑多个集合时反复推送。
修复：拆出单独的安装步骤，或提供跳过安装的开关。PERFORMANCE 在同一条中还指出 :19 切换输入法后从不恢复，应先保存 `settings get secure default_input_method` 并在退出时 `ime set` 回去（同 G5-06）。

- 提出者：PERFORMANCE@G5（NIT，8/10）

#### F566 [NIT] prev2/prev 拆分逻辑重复了多份

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`PinyinRun.kt:88-90`、`PredictLearning.kt:41-43`、`PredictRun.kt:32-34`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinRun.kt:88-90`、`PredictLearning.kt:41-43`、`PredictRun.kt:32-34`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:88`；另见 `lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictLearning.kt:41-43`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PredictRun.kt:32-34`

PinyinRun.kt:88、PredictLearning.kt:41-43 和 PredictRun.kt:32-34 写了同样的三行 prev2/prev 拆分，`PinyinSession.lastTwo` 是第四份私有副本；目前它们行为一致。
修复：在 `TextWords` 上提供一个共享 helper，这样会话的判断条件变化时，评测的解码仍与会话一致。

- 提出者：STYLE@G5（NIT，8/10）

#### F567 [NIT] 读音逻辑在三个工具中重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`make-chat-set.py:145-147`、`make-dialog-sets.py:70-76`、`make-mixed-set.py:41-44`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`make-chat-set.py:145-147`、`make-dialog-sets.py:70-76`、`make-mixed-set.py:41-44`）
- 位置：`lib/ime-eval/tools/make-mixed-set.py:42`

「切分 run、在 `before` 之后读每个词、拒绝任何 `None`」的逻辑同时出现在 make-chat-set.py 的 `main`、make-dialog-sets.py 的 `main` 和 make-mixed-set.py 的 `read`（:42）中，三者可能彼此漂移。
修复：由 `make-chat-set.loaders` 返回一个 `read(run, before='')` helper 供三处共用。

- 提出者：CORRECTNESS-2@S24（NIT，8/10）

#### F568 [NIT] make-chat-set.py 无参数运行时抛 IndexError

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`make-chat-set.py:156`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`make-chat-set.py:156`）
- 位置：`lib/ime-eval/tools/make-chat-set.py:156`

make-chat-set.py:156 在无参数时访问 `sys.argv[1]` 抛出 IndexError，而不是打印用法。
修复：缺少参数时打印 usage。CORRECTNESS-2@S24 在同一条中还指出只有该脚本是 100755，其余四个工具 shebang 相同却是 100644（见 G5-47）；GENERIC-1@S24（G5-46）和 STYLE（G5-40）也顺带提到此 IndexError。

- 提出者：CORRECTNESS-2@S24（NIT，8/10）

#### F569 [NIT] EngineEvent::Other 不告诉会话按下的是哪个键

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:333`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:333`）
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:333`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:329`

组合输入时，会话吞掉 Left、Right、Home、End 和 Delete，却不知道收到的是哪一个（arg 为 0），所以实体键盘用户无法在拼音内移动或逐个浏览候选，而 libime 允许这样做。line 329 的注释说明这是有意为之；若以后要改，可把 keysym 作为 arg 传入。

- 提出者：GENERIC-1@S26（NIT，8/10）

#### F570 [NIT] 服务端预编辑没有设置光标

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:383`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:383`）
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:383`

客户端预编辑把光标设在末尾，但服务端预编辑路径（不带 Preedit 标志的编辑框，例如 TYPE_NULL）没有设置光标。
修复：在这里也调用 setCursor(snapshot.preedit.size())，使两条路径一致。

- 提出者：GENERIC-1@S26（NIT，8/10）

#### F571 [NIT] 屏蔽短语沿用热词语法解析

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:229`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:229`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:230`；另见 `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:225`、`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer.h:103`

以 : 开头的词被当作加权分数：短语 a :x 会让 std::stof("x") 抛出，SafeJNI 把它变成 RuntimeException；词表外的 # 片段同样会抛出；短语中的 / 会把它拆成两个屏蔽短语，从而屏蔽其他文本，这正是 line 225 注释说要避免的情况。应用的纯汉字 spelled() 会挡住这些输入，只影响新 C++ 和 Kotlin API 的其他调用方。
修复：在 offline-recognizer.h:103 记录该语法，或拒绝此类短语。

- 提出者：CORRECTNESS-2@S52（NIT，8/10）

#### F572 [NIT] 码表输入法名称保存在两个平行数组中

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:211`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:211`）
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:211`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:177`

methods[]（line 177）和 tables[]（line 211）都列出 engine-wubi 到 engine-wubipinyin，每个码表在 AndroidEngineConfig 中还需要一个成员；新增码表要改三处，只靠注释保持同步。
修复：用一个带可选 Option<EngineTableConfig> AndroidEngineConfig::* 成员指针（pinyin、shuangpin、t9 为 nullptr）的数组，同时服务 listInputMethods 和 table()。

- 提出者：STYLE@G6（NIT，8/10）

#### F573 [NIT] fourRows 函数名硬编码了 Rows 常量的值

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/matrix-kernel.cpp:38`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/matrix-kernel.cpp:38`）
- 位置：`app/src/main/cpp/matrix-kernel.cpp:38`；另见 `app/src/main/cpp/matrix-kernel.cpp:30`、`app/src/main/cpp/matrix-kernel.cpp:78`

块大小是具名常量 Rows（line 30），但函数名写死了 4，改动 Rows 后名字就不再准确，rowBlock 则始终准确；line 78 起的 qs/ss/xs/ys 也可改名为 weights、scales 等，而 q/x/y 与文档中的 y = Q·x 一致，可以保留。

- 提出者：STYLE@G6（NIT，8/10）

#### F574 [NIT] 迁移 replace 写入或改名失败时残留 .new 文件

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`EngineMigration.kt:66-73`、`CustomPhraseManager.kt:60-65`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineMigration.kt:66-73`、`CustomPhraseManager.kt:60-65`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:66`

迁移在每次启动前都会运行，`replace()` 写入或改名失败时会在 fcitx 的配置目录里留下 `profile.new` 或 `conf/androidengine.conf.new`；`CustomPhraseManager.write` 则会在 `finally` 中删除自己的 `.new` 文件。
修复：在 `finally` 中删除 `next`。

- 提出者：CORRECTNESS-2@S29（NIT，8/10）

#### F575 [NIT] 每个事件都从索引 0 取候选，代价随翻页深度增长

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`EngineBridge.kt:194`、`native-lib.cpp:769`、`androidengine.cpp:64-66`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EngineBridge.kt:194`、`native-lib.cpp:769`、`androidengine.cpp:64-66`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:194`；另见 `app/src/main/cpp/androidengine/androidengine.cpp:69`

翻到第 N 页时，每个按键或翻页事件都会重建 `[0, first+shown)`，映射到两个数组，并全部经 JNI 转成 UTF-8；在常规页大小下这点开销很小。
修复：若深度翻页重要，只发送当前显示的切片和 `first`，native 侧（androidengine.cpp:69-70）已经做了钳制。

- 提出者：GENERIC-1@S29（NIT，8/10）

#### F576 [NIT] 导出的备份包含被标记为 sensitive 的剪贴板记录

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`UserDataManager.kt:68`、`ClipboardManager.kt:102`、`ClipboardEntry.kt:19`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`UserDataManager.kt:68`、`ClipboardManager.kt:102`、`ClipboardEntry.kt:19`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:68`

导出会把 `clbdb` 中 `sensitive=1` 的行（例如从密码管理器复制的密码）原样复制进明文 zip；这是上游行为，而且导出只在用户主动发起时运行。
修复：在导出的 `clbdb` 副本中去掉 `sensitive=1` 的行。

- 提出者：GENERIC-1@S30（NIT，8/10）

#### F577 [NIT] 选择当前显示的最近档位，不会写入该档位的值

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`ManagedPreferenceUi.kt:56`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ManagedPreferenceUi.kt:56`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceUi.kt:55`

旧值（例如 380 ms）显示为最接近的档位（「long」）时，再选「long」是空操作：ListPreference 不保存未变化的值，`pick` 也会提前返回，用户得先选别的档位才能得到 450。这与文档一致，但并不直观。
修复：在文档或摘要中加一行说明。

- 提出者：CORRECTNESS-2@S31（NIT，8/10）

#### F578 [NIT] 编辑器合并时，被编辑的短语被移到文件末尾

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`CustomPhraseManager.kt:45-47`、`CustomPhrases.kt:147-151`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`CustomPhraseManager.kt:45-47`、`CustomPhrases.kt:147-151`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:45`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:47`

文件在编辑器加载后发生过变化时，被编辑或切换状态的短语会被算作「删除加新增」，新增的那份放到末尾，同一 key 下各短语在文件中的顺序随之改变，而测试把这个顺序当作有意义的。
修复：把新增项放到同一 key 被删除项原来的位置。

- 提出者：GENERIC-1@S31（NIT，8/10）

#### F579 [NIT] .conf 无法解析的输入法被静默丢弃且没有日志

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`TableManager.kt:29-36`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TableManager.kt:29-36`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:29`

`runCatching{}.getOrNull()` 会悄悄丢掉这个输入法。
修复：像 `listThemes` 处理主题文件那样，记一条带文件名的 `Timber.w`。

- 提出者：GENERIC-1@S32（NIT，8/10）

#### F580 [NIT] 悬浮候选栏的翻页图标缺少无障碍标签

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`PaginationUi.kt:30-38`、`PagedCandidatesUi.kt:63`、`ExpandedCandidateLayout.kt:60`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PaginationUi.kt:30-38`、`PagedCandidatesUi.kt:63`、`ExpandedCandidateLayout.kt:60`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/floating/PaginationUi.kt:37`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/ExpandedCandidateLayout.kt:60`、`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/ExpandedCandidateLayout.kt:70`

展开窗口的翻页按钮已经加上 `a11y_candidates_prev_page`/`_next_page`（ExpandedCandidateLayout.kt:60、:70），但悬浮栏里可点击的上一页/下一页 `ImageView` 仍没有 `contentDescription`，TalkBack 会把它们读作未标注的按钮。
修复：给 `prevIcon`/`nextIcon` 设置同样的两个字符串。

- 提出者：CORRECTNESS-1@S35（NIT，8/10）

#### F581 [NIT] EditorInfo 检查器未按类别过滤输入类型标志

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`EditorInfoParser.kt:45-46`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EditorInfoParser.kt:45-46`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/editorinfo/EditorInfoParser.kt:46`

`TYPE_FLAGS` 混合了共享同一些位的 `TYPE_TEXT_FLAG_*` 和 `TYPE_NUMBER_FLAG_*`（例如 `NUMBER_FLAG_SIGNED` 与 `TEXT_FLAG_CAP_CHARACTERS` 都是 0x1000），检查器会列出错误类别的标志。
修复：像 variation 过滤那样按类别前缀过滤。

- 提出者：GENERIC-1@S36（NIT，8/10）

#### F582 [NIT] excerptText 扫描超出摘录范围，脱敏输出丢失换行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`ClipboardAdapter.kt:62`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ClipboardAdapter.kt:62`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardAdapter.kt:62`

`indexOf('\n', start)` 在每次绑定时都会扫描到超长单行条目的末尾，违背了这个函数减少渲染时间的目的；脱敏分支用 `append`，而普通分支用 `appendLine`，所以多行的脱敏条目会合并成一行。
修复：把搜索范围限制在 `excerptEnd` 之内，并在脱敏形式中保留换行。

- 提出者：GENERIC-1@S36（NIT，8/10）

#### F583 [NIT] privateImeOptions 用完全相等来比较

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`EditorInfoTraits.kt:25`、`EditorInfoTraitsTest.kt:58`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EditorInfoTraits.kt:25`、`EditorInfoTraitsTest.kt:58`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/editing/EditorInfoTraits.kt:25`

按惯例 `privateImeOptions` 是逗号分隔的选项列表，改用 `split(',').any { it.trim() == DeleteSurroundingFlag }` 匹配，可以在按键偏好界面以后加入其他选项时保持这个开关有效。
修复：按逗号拆分后匹配。

- 提出者：GENERIC-1@S36（NIT，8/10）

#### F584 [NIT] 后面没有偏好项的 section 没有测试，且会被静默丢弃

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`ManagedPreferenceCategory.kt:24`、`ManagedPreferenceSectionsTest.kt:102-113`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`ManagedPreferenceCategory.kt:24`、`ManagedPreferenceSectionsTest.kt:102-113`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceCategory.kt:23`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceSectionsTest.kt:110`

`twoSectionsInARowAreRejected` 只覆盖了连续两个 section；后面没有任何偏好项的 `section()` 会在 `createUi` 中被静默丢弃，这正是 ManagedPreferenceCategory.kt:23 的 `require` 注释所警告的情况。
修复：拒绝这种情况，或至少为它加一个测试。

- 提出者：CORRECTNESS-1@S50（NIT，8/10）

#### F585 [NIT] 服务启动时 subtype 被重复同步多次

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`FcitxInputMethodService.kt:237`、`Fcitx.kt:467`、`SubtypeManager.kt:71-72`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`FcitxInputMethodService.kt:237`、`Fcitx.kt:467`、`SubtypeManager.kt:71-72`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:237`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:467`

每次同步都包含两次 binder 调用（`setAdditionalInputMethodSubtypes` 和 `setExplicitlyEnabledInputMethodSubtypes`），system_server 还会把 subtype 写到磁盘。
修复：只保留传入本地化名称的那一次调用。

- 提出者：PERFORMANCE@G7（NIT，8/10）

#### F586 [NIT] 输入法 id 以字符串字面量重复书写

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/InputMethodNames.kt:18-26`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:906-910`、`Fcitx.kt:413-415`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/InputMethodNames.kt:18-26`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:906-910`、`Fcitx.kt:413-415`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/InputMethodNames.kt:17`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/SubtypeManager.kt:21`

`engine-pinyin`、`engine-shuangpin`、`engine-t9` 已经有 `Engines.PINYIN`、`SHUANGPIN`、`T9`，码表 id 就是 `Engines.TABLES` 的键；SubtypeManager.kt:21 也重写了 `keyboard-us`。
修复：使用这些常量，这样输入法改名或新增时会编译失败，而不是悄悄回退到 fcitx 的名称。

- 提出者：STYLE@G7（NIT，8/10）

#### F587 [NIT] Engines 构造与 Additions 使用一长串同类型的位置参数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:68-70`、`Engines.kt:68-76`、`EngineBridge.kt:169`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:68-70`、`Engines.kt:68-76`、`EngineBridge.kt:169`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:69`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:169`

`Engines(...)` 在一行里传入七个位置参数，其中五个是函数引用；:169 传入两个同类型的 lambda，其中一个是尾随 lambda：`Engines.Additions(…, { read(newDictionaries) }) { read(baseDictionaries) }`，把 new 和 base 对调也能静默通过编译。
修复：使用命名参数。

- 提出者：STYLE@G7（NIT，8/10）

#### F588 [NIT] Display 与 IdleUi.State 两个枚举一一对应

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:187-191`、`lib/ime-core/.../input/bar/IdleUiPolicy.kt:14`、`app/.../input/bar/ui/IdleUi.kt:54-56`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:187-191`、`lib/ime-core/.../input/bar/IdleUiPolicy.kt:14`、`app/.../input/bar/ui/IdleUi.kt:54-56`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:187`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/IdleUi.kt:245`

两个枚举都是同样的五个条目；新增一个空闲状态需要改两个枚举、这里的 `when`，以及 IdleUi.kt:245 的索引 `when`。
修复：让 IdleUi 直接接受 `Display`。

- 提出者：STYLE@G7（NIT，8/10）

#### F589 [NIT] 词典存在性检查与 BOM 剥离逻辑在多处重复

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:85`、`ImportedDictionaries.kt:94`、`ImportedDictionaries.kt:38`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:85`、`ImportedDictionaries.kt:94`、`ImportedDictionaries.kt:38`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:85`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:98`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:94`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:38`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:61`

`listOf(true, false).any { File(…, TextDictionary.fileName(name, it)).exists() }` 出现在这里、:98 和 ImportedDictionaries.kt:94；BOM 剥离的 `mapIndexed { i, l -> … }` 出现在 ImportedDictionaries.kt:38 和 :61。
修复：增加 `TextDictionary.existsIn(dir, name, type)` 和一个统一的 BOM 剥离 helper。

- 提出者：STYLE@G7（NIT，8/10）

#### F590 [NIT] vivo 兼容路径中 MotionEvent.obtain 未回收

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:424-430`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:424-430`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:430`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:424`

vivo 兼容模式下每个转发的触摸事件都用 MotionEvent.obtain 创建新事件但从不回收，每次丢失一个池化事件。
建议：在 :430 之后调用 e.recycle()。

- 提出者：GENERIC-1@S37（NIT，8/10）

#### F591 [NIT] 通用手势视图通过隐藏通道写入 BaseInputView

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:182-183`、`app/src/voice/.../VoiceHoldOverlay.kt:43`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:182-183`、`app/src/voice/.../VoiceHoldOverlay.kt:43`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:182`

任何 CustomGestureView（标签、状态项等）每次长按都会向上遍历祖先去设置 BaseInputView.longPressPointer，这是只为语音存在的隐藏旁路。
建议：把指针 id 随长按动作或事件一起传递。

- 提出者：STYLE@G8（NIT，8/10）

#### F592 [NIT] Text↔T9 别名逻辑分散在三处

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`、`KeyboardWindow.kt:165-167`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`、`KeyboardWindow.kt:165-167`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:132`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:139`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:159`

别名在 KeyboardWindow.kt:132 和 :139 两处转换，onImeUpdate（:159-169）又重复了 detach/attach/notify 序列。
建议：合并为一个 showLayout()，别名处理也可以移入 LayoutSwitchPolicy。

- 提出者：STYLE@G8（NIT，8/10）

#### F593 [NIT] swipeText 用三态 String? 表达语义

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`BaseKeyboard.kt:159-160`、`BaseKeyboard.kt:342`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323-327`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`BaseKeyboard.kt:159-160`、`BaseKeyboard.kt:342`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323-327`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:160`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:342`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:323`

swipeText 以 null 表示内置滑动、空字符串表示无（TextKeyboard.kt:323），含义隐含；:342 的 ifEmpty { it.content } 中 it 指外层 Popup，读起来像是字符串本身。
建议：使用密封类型 SwipeChoice，并给 lambda 参数命名。

- 提出者：STYLE@G8（NIT，8/10）

#### F594 [NIT] 九键空格标签与 TextKeyboard 不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:236-240`、`TextKeyboard.kt:188-197`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:236-240`、`TextKeyboard.kt:188-197`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:236`

T9Keyboard 的 onInputMethodUpdate 省略了子模式标签（如 ASCII 切换）以及 TextKeyboard 在输入法变化时给出的 TalkBack 播报。
建议：把共用逻辑提取到 BaseKeyboard 或辅助函数中。

- 提出者：GENERIC-1@S38（NIT，8/10）

#### F595 [NIT] 面板模式有两个真值来源且到处分支

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindowPreset.kt:16`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:98`、`PickerLayout.kt:66`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindowPreset.kt:16`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:98`、`PickerLayout.kt:66`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:34`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindowPreset.kt:16`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:146`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:98`、`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerLayout.kt:66`

panel: Boolean 标志与 Density.Panel（PickerWindowPreset.kt:16-22、PickerGridAdapter.kt:146）必须保持一致，PickerWindow（98-183）和 PickerLayout（66-85）中约十处 if (panel)/side != null 分支意味着新增第三种样式要改动所有分支。
建议：用一个携带密度、键和栏扩展的密封类 PickerStyle 替代。

- 提出者：STYLE@G8（NIT，8/10）

#### F596 [NIT] 许可页在主线程读取并解析 aboutlibraries JSON

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`LicensesFragment.kt:22`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`LicensesFragment.kt:22`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:22`

LicensesFragment 用不带 dispatcher 的 lifecycleScope.launch，协程仍在 Main 上运行，JSON 的读取和解析并没有离开 UI 线程。
建议：在 Dispatchers.IO 或 Dispatchers.Default 上解析，再回到 Main 构建 preference。

- 提出者：GENERIC-1@S40（NIT，8/10）

#### F597 [NIT] DialogSeekBarPreference 的 step 未钳制到 1

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/DialogSeekBarPreference.kt:34-35`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/DialogSeekBarPreference.kt:34-35`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/DialogSeekBarPreference.kt:58`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/DialogSeekBarPreference.kt:153`

KDoc 说 step 小于 1 时按 1 处理，但没有代码执行这一点，step 为 0 时会除以零。
建议：在 :58 使用 step = getInteger(...).coerceAtLeast(1)。

- 提出者：GENERIC-1@S41（NIT，8/10）

#### F598 [NIT] 多个进度通知共用 ID 0，取消时会互相影响

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:353-354`、`TableInputMethodFragment.kt:435`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:353-354`、`TableInputMethodFragment.kt:435`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/QuickPhraseListFragment.kt:353-354`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/TableInputMethodFragment.kt:435`

这是上游代码。`QuickPhraseListFragment` 的 `RELOAD_ID` 与 `IMPORT_ID` 都从 0 开始，`TableInputMethodFragment.IMPORT_ID`（第 435 行）也是；通知 ID 在整个应用范围内有效（与渠道无关），取消一个进度通知可能取消另一个。
建议：使用不同的 ID 区间或 tag。

- 提出者：GENERIC-1@S42（NIT，8/10）

#### F599 [NIT] 已存储的 AutoPhraseLength=1 显示为「关闭」

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:158`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:158`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:158`

原生取值范围为 `IntConstrain(-1, 8)`，迁移的 libime 设置原样保留；值为 1 时 `nearestOf` 中 0 与 2 距离相同，`minBy` 选中 `0`，页面显示关闭，而引擎实际生成长度至 1 的词组。
建议：正值只映射到最近的正值档位。

- 提出者：CORRECTNESS-1@S43（NIT，8/10）

#### F600 [NIT] 嵌套的 `Section` 会被拍平到顶层

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:215-221`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:215-221`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:215-221`

`Section` 分支忽略其 `into` 参数，总是把分类加到 `screen`，放在另一分区内的分区会静默变成顶层；目前没有页面嵌套分区。
建议：使用 `into(category)`，或 `require` 分区只能位于顶层。

- 提出者：GENERIC-1@S43（NIT，8/10）

#### F601 [NIT] zh-rCN 首页分类与其中条目都叫“词库”

- 来源：第一轮（整仓 @07d2778） · 共识：1/5 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`values-zh-rCN/strings.xml:378`、`MainFragment.kt:90-101`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`values-zh-rCN/strings.xml:378`、`MainFragment.kt:90-101`）
- 位置：`app/src/main/res/values-zh-rCN/strings.xml:383`；另见 `app/src/main/res/values-zh-rCN/strings.xml:378`

zh-rCN 中分类标题 `home_section_words`（:378）和该分类下的条目 `word_packs`（:383）都译为“词库”（见 MainFragment.kt），英文中分别是 Words 和 Dictionaries。
修复：给其中一个换一个中文标签。

- 提出者：CORRECTNESS-1@S48（NIT，8/10）

#### F602 [NIT] es 将备份译为“Exportar datos de uso”（使用数据）

- 来源：第一轮（整仓 @07d2778） · 共识：1/5 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`values-es/strings.xml:114`、`values/strings.xml:234`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`values-es/strings.xml:114`、`values/strings.xml:234`）
- 位置：`app/src/main/res/values-es/strings.xml:114`

values-es/strings.xml:114 的“Exportar datos de uso”意为“导出使用数据”，在一个不发送任何数据的应用里会让人以为是遥测。
修复：改为“Hacer una copia de seguridad”和“Restaurar una copia”，与原文含义一致。

- 提出者：CORRECTNESS-2@S48（NIT，8/10）

#### F603 [NIT] CMake install 任务每次构建都会运行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/NativeBuildTasks.kt:50-64`、`FcitxComponentPlugin.kt:38-39`、`app/build.gradle.kts:102-108`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/NativeBuildTasks.kt:50-64`、`FcitxComponentPlugin.kt:38-39`、`app/build.gradle.kts:102-108`）
- 位置：`build-logic/convention/src/main/kotlin/NativeBuildTasks.kt:50`

`CMakeBuildInstallTask` 没有声明输出，FcitxComponentPlugin 注册的 9 个此类任务永远不是 up to date，每次构建都会为它们启动 `cmake --build` 和 `cmake --install` 进程。这是上游代码。
建议：加一个以 CMake install manifest 为依据的 `upToDateWhen` 检查，或注明接受这一开销。

- 提出者：PERFORMANCE@G1（NIT，7/10）

#### F604 [NIT] pull_request.yml 与 nix.yml 不取消被新推送取代的运行

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`.github/workflows/pull_request.yml:3-11`、`.github/workflows/nix.yml:3-9`、`unit_test.yml:28`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/pull_request.yml:3-11`、`.github/workflows/nix.yml:3-9`、`unit_test.yml:28`）
- 位置：`.github/workflows/pull_request.yml:3`；另见 `.github/workflows/nix.yml:19`

pull_request.yml 和 nix.yml 没有带 `cancel-in-progress` 的 `concurrency` 组（unit_test.yml 有），PR 每次推送都会留下最多 5 个仍在运行的旧完整 release 构建，每个都在编译引擎数据。
PERFORMANCE@G1 在同一条中还指出两个工作流都没有设 `permissions:`，且 nix.yml:19 把 `GITHUB_TOKEN` 传给 install-nix-action（与 G1-01 相同）。
建议：复制 unit_test.yml 的 concurrency 块，加 `permissions: contents: read`，并把第三方 action 固定到 commit SHA。

- 提出者：PERFORMANCE@G1（NIT，7/10）

#### F605 [NIT] `ENGINE_DATA` 只标识了语言模型那次发布

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:214`、`AboutFragment.kt:46-47`、`.github/ISSUE_TEMPLATE/missing_words.yaml:50`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:214`、`AboutFragment.kt:46-47`、`.github/ISSUE_TEMPLATE/missing_words.yaml:50`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:206`

关于页和缺词报告显示的是 `engine-data-20261005-3`，而词包（`words-202610`）和句子模型（`sentence-models-20261001`）各自独立版本化，其中一个单独变化时显示的身份不完整。
建议：使用组合标识，或为每个发布各设一个 BuildConfig 字段。

- 提出者：CORRECTNESS-1@S2（NIT，7/10）

#### F606 [NIT] 大文件下载时没有任何提示输出

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:240-266`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:240-266`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:233`

首次构建要下载约 400 MB 引擎数据和约 180 MB 语音数据，停滞的读取每次尝试最长要等 60 秒，却没有任何消息说明正在下载什么、存到哪里。
建议：在打开请求前加 `logger.lifecycle("downloading $url -> $out")`。

- 提出者：GENERIC-1@S2（NIT，7/10）

#### F607 [NIT] readings.tsv 给错别字 神祗 加了常用读音

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lexicon/readings.tsv:246`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/readings.tsv:246`）
- 位置：`lexicon/readings.tsv:246`

神祗 是 神祇（shénqí）的常见错写；readings.tsv:246 把 神祗 列在 shen'qi 下，会把这个错写形式推给所有输入 shenqi（神奇、神祇 的读音）的人。
README 自己的规则排除错别字（「a wrong one costs every user who types its reading」）；建议删除 :246，并考虑把 神祗 加入 remove.tsv。

- 提出者：CORRECTNESS-1@S4（NIT，7/10）

#### F608 [NIT] NewWords 生产用的长度 4 从未被断言

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:25`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:25`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/NewWordsTest.kt:25`

`Main.words` 用默认 `MAX_LENGTH = 4` 的 `NewWords(known, minCount)`，但所有 NewWords 测试的上限都是 3 或 2，长度 4 的草图过滤（`sketches[3]`）与精确计数都不会产生被检查的候选。
建议：在 `pages` 中加入一个 4 字串并运行 `find(maxLength = 4)`。

- 提出者：CORRECTNESS-1@S6（NIT，7/10）

#### F609 [NIT] MainTest 一个测试覆盖约 12 个场景

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:130-198`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:130-198`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/MainTest.kt:130-198`

MainTest.kt:130-198 的一个测试覆盖阈值、`--only`、`--lexicon`、两条坏读音和三种用法错误退出，第一个失败会掩盖其余。
建议：按选项拆分；该发现另指出 `cc` 的 CLI 解析（`Crawl.parse`：负的 `--from`、`--files 0`）和 `text` 命令没有 CLI 级测试（相关缺口见 G2-14）。

- 提出者：CORRECTNESS-2@S6（NIT，7/10）

#### F610 [NIT] WebTextTest 的顺序测试不可能失败

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:70-85`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:70-85`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:70`

WebTextTest.kt:70 只有 6 行，构成一个 row group 和一个读线程，即使去掉 `SET preserve_insertion_order = true` 输出顺序也不变，而该设置正是让每次构建得到相同混合模型的关键。
建议：写足够多的行形成多个 row group（在辅助函数的 COPY 中使用小的 `ROW_GROUP_SIZE`），或重命名该测试。

- 提出者：GENERIC-1@S6（NIT，7/10）

#### F611 [NIT] forEachAfter 的二元/三元词 id 未对词表大小校验

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:233-246`、`Engines.kt:118`、`UserModel.kt:343`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:233-246`、`Engines.kt:118`、`UserModel.kt:343`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:313`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:118`

`NgramModel.init` 检查数组大小与 run 表，但未检查 `biWord`、`triWord` 小于 `vocabularySize`（`PinyinDictionary` 则会运行 `words.checkBelow`）；`Predictor.offerFollowers` 把这些 id 交给词表查找，所以以 `verify = false` 打开的损坏文件会在按键时抛 `IndexOutOfBoundsException`，而 `CorruptionTest` 的遍历从不调用 `forEachAfter`。
仅列为 NIT，因为设备只对 APK 内签名的拼音数据跳过校验（Engines.kt:118）。建议：对两个数组加 `checkBelow`，或扩展损坏遍历以覆盖 `forEachAfter`。

- 提出者：CORRECTNESS-1@S7（NIT，7/10）

#### F612 [NIT] LibimeFiles 写出码表编码时未转义

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:135`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:142`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:135`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:142`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:135`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/libime/LibimeFiles.kt:142`

在构造的 libime 码表中，含 `\n` 或空格的编码字节会向码表解析器读取的文本中加入额外行（`[Rule]`、`KeyCode=`、其他条目），见第 135 与 142 行；libime 本身也原样写出编码，且文件由用户自己选择，所以影响仅为导入损坏。
建议：拒绝含空白或控制字符的编码。

- 提出者：GENERIC-1@S9（NIT，7/10）

#### F613 [NIT] PinyinDecoderTest 精确断言打分调用次数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoderTest.kt:285`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoderTest.kt:285`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoderTest.kt:285`

对打分器调用次数做精确相等断言，固定了内部计数；一个保持每键成本不变的剪枝改动也可能使它失败。
建议：改为断言上界（如每次计数都在第一次的小倍数之内），并保留与重置解码器的 ×10 对比。

- 提出者：TESTING@G3（NIT，7/10）

#### F614 [NIT] LatinWords每次按键为每个探测长度分配子串

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/LatinWords.kt:24`、`PinyinSegmenter.kt:62-69`、`PinyinSession.kt:548`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/LatinWords.kt:24`、`PinyinSegmenter.kt:62-69`、`PinyinSession.kt:548`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/LatinWords.kt:24`

`lettersOfWords` 对每个输入位置调用 `forEachAt`，每个探测长度都分配一个 substring，每次按键最多约 L × `longest` 个字符串；类文档实测约 0.2 ms，影响较小。
建议：用字母 trie 或基于区间计算的哈希来去掉这些分配。

- 提出者：GENERIC-1@S10（NIT，7/10）

#### F615 [NIT] 停顿时的精排切片可能重排九键音节列表

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:661`、`NineKeys.kt:84`、`NineKeys.kt:47`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:661`、`NineKeys.kt:84`、`NineKeys.kt:47`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:599`

`refine()` 调用 `place()`，后者再次运行 `nineKeys.offer()`；若精排后的最佳读法以不同音节开头，列表会被重排并获得新 id，`EngineBridge` 发布新列表，而 `Keyboard.char` 以旧 id 发出的点击会被丢弃。
建议：若这不是有意的，精排期间保留上次 `read()` 得到的列表。

- 提出者：GENERIC-1@S11（NIT，7/10）

#### F616 [NIT] 非字母键提交笔画查询结果时未把该字告知拼音

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:83`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:83`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:83`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/stroke/StrokeLookup.kt:94`

`pick` 会把上屏的字作为上下文告知拼音（第 94-96 行），但 `end` 在非字母键提交字符时没有这样做，拼音的下一句因此以过时的上下文精排。
建议：`end` 提交字符时调用 `pinyin.apply(Action.Context(text))`。

- 提出者：CORRECTNESS-2@S12（NIT，7/10）

#### F617 [NIT] PhraseRules的used去重范围超出注释所述

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/PhraseRules.kt:50`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/PhraseRules.kt:50`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/PhraseRules.kt:50`

任何两个指向同一字同一键的部分都只取一次，例如两字词 `a2` 规则中的 `p21` 和 `n11`；libime 两者都取，所以导入码表的词组编码会少一个键。
建议：只在其中一个部分从末尾计键时才去重。

- 提出者：CORRECTNESS-2@S12（NIT，7/10）

#### F618 [NIT] TableDictionary中「只有词组和拼音查询需要」的注释已失效

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableDictionary.kt:38`、`TableSession.kt:300`、`SharedWords.kt:52`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableDictionary.kt:38`、`TableSession.kt:300`、`SharedWords.kt:52`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableDictionary.kt:38`

引入 `CodedWords` 后，它在任何有规则码表的第一个键就对用户词编码，所以这次全表遍历如今在每个 五笔/自然码/二笔 会话的第一个键都会运行。
建议：更新注释。

- 提出者：CORRECTNESS-2@S12（NIT，7/10）

#### F619 [NIT] 九键测试名称承诺了abbreviations情形却从未测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:35`、`T9Segmenter.kt:121`、`Main.kt:253`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:35`、`T9Segmenter.kt:121`、`Main.kt:253`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9SegmenterTest.kt:35`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/pinyin/T9Segmenter.kt:121`

`...UnlessAbbreviationsAre` 只断言 `abbreviations = false`；ime-eval 的「t9」方案（eval/Main.kt:253）使用的 `abbreviations && longest <= n` 分支（T9Segmenter.kt:121）没有任何测试断言。
建议：用默认 segmenter 加一个用例，展示在也能读出音节处出现 INITIAL 边。

- 提出者：GENERIC-1@S17（NIT，7/10）

#### F620 [NIT] 九键测试在注释给出确切结果处用了弱断言

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:135`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:135`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionT9Test.kt:135`

注释说「没被读走的部分原样保留：数字」，但 `startsWith("ni") && length == 6` 在数字换成字母时也会通过。
建议：断言确切的期望值，如 `ni9999`。

- 提出者：GENERIC-1@S18（NIT，7/10）

#### F621 [NIT] 压缩测试从不检查压缩是否发生，断言失败时存储不关闭

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:85`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:85`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/table/TableUserTest.kt:85`

`compactedTheLogReadsTheSame` 只比较显示内容，即使压缩从未运行也会通过；断言失败时 Store 也不会被关闭。
建议：断言文件比未压缩的日志短或统计记录数，并像 `whatIsForgottenStaysForgotten` 那样用 `use {}` 关闭存储。

- 提出者：GENERIC-1@S19（NIT，7/10）

#### F622 [NIT] 每次按键都线性扫描所有已存词组

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:53`、`TableSession.kt:314`、`TableOptions.kt:58`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:53`、`TableSession.kt:314`、`TableOptions.kt:58`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:53`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableUser.kt:62`

`saved()` 和 `leadsAnywhere()`（:62）对每个已存词组执行 `startsWith`；像 CodedWords 那样用带 `subMap` 的 TreeMap 可以避免扫描。
另外 `TableSession.rank` 对首键范围内的每个条目查询 `picks(e)` 时都会装箱。

- 提出者：PERFORMANCE@G3（NIT，7/10）

#### F623 [NIT] TableConf是第三个INI读取器

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:81`、`utils/Ini.kt:53`、`LibimeMigration.kt:172`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:81`、`utils/Ini.kt:53`、`LibimeMigration.kt:172`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/table/TableConf.kt:81`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/LibimeMigration.kt:172`

LibimeMigration.kt:172 解析同样的 fcitx 格式，却不反转义值，也不跳过 `#` 注释。
建议：共用一个节解析器。

- 提出者：STYLE@G3（NIT，7/10）

#### F624 [NIT] 非 BMP 字母后输入 ASCII 引号时只读到半个字符

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:56`、`InputEditor.kt:39`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:56`、`InputEditor.kt:39`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/AutoPairs.kt:56`

`AutoPairs.kt:56` 用 `textBeforeCursor(1)` 检查引号前的字符；在扩展 B 区汉字或 emoji 之后，它返回孤立的低代理，`isLetterOrDigit()` 为 false，于是在这类字母后输入的 `"` 会开启配对而不是闭合。
建议读取 2 个单元并用 `Character.codePointBefore` 判断。

- 提出者：CORRECTNESS-1@S14（NIT，7/10）

#### F625 [NIT] UserScorerTest 用默认 modelWords = 0 列出模型词

- 来源：第一轮（整仓 @07d2778） · 共识：1/18 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:55`、`UserModel.kt:221-226`、`Engines.kt:444`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:55`、`UserModel.kt:221-226`、`Engines.kt:444`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/UserScorerTest.kt:55`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:436`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:454`、`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/PinyinRun.kt:52`

这个默认值会在字典词上存储包分数和第 1 层，而生产代码从不产生这种状态：Engines.kt:436/454 和 PinyinRun.kt:52 都传入 `vocabularySize`；测试只靠 UserScorer 的 `word >= vocabularySize` 守卫才通过（UserScorerTest.kt:55）。
建议传入 `modelWords = data.model.vocabularySize`，让测试与真实调用一致。

- 提出者：CORRECTNESS-2@S20（NIT，7/10）

#### F626 [NIT] FakeEditor 在 isAvailable = false 时仍修改缓冲区

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/testFixtures/kotlin/org/fcitx/fcitx5/android/input/editing/FakeEditor.kt:33`、`InputConnectionEditor.kt:23-55`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/testFixtures/kotlin/org/fcitx/fcitx5/android/input/editing/FakeEditor.kt:33`、`InputConnectionEditor.kt:23-55`）
- 位置：`lib/ime-core/src/testFixtures/kotlin/org/fcitx/fcitx5/android/input/editing/FakeEditor.kt:33`

生产的 `InputConnectionEditor` 在没有连接时丢弃所有调用，而 `FakeEditor`（FakeEditor.kt:33）仍会编辑缓冲区和选区；现有 `EditingSessionTest` 用例同时断言 `e.calls.isEmpty()`，所以目前没有掩盖问题，但将来只检查 `e.text` 的测试可能在真实编辑器不会出现的行为上通过。
建议在 `!isAvailable` 时让修改类覆写方法提前返回（或直接失败）。

- 提出者：GENERIC-1@S22（NIT，7/10）

#### F627 [NIT] forEachCount 用装箱 Pair 缓存所有词对

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:161`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:161`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/UserModel.kt:161`

`forEachCount`（UserModel.kt:161）在压缩时运行，处在越过阈值的那次 learn 之内、fsync 之前，却把每个词对都存成装箱的 `Pair<Long, Float>`。
建议对 `counts` 做两遍遍历（先词、后词对），这样无需缓冲。

- 提出者：PERFORMANCE@G4（NIT，7/10）

#### F628 [NIT] KeyHabits 一次超出多条时的裁剪分支从未执行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:112`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:112`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/user/KeyHabits.kt:112`

`over > 1` 分支（`sortedWith(order).take(over)`，KeyHabits.kt:112）没有测试运行过；`restore` 不裁剪，因此由更高上限的构建写下的日志会在第一次 `learn` 时进入这一分支，而 `theLeastCountedGoFirst` 只超出上限一条。
建议向 `KeyHabits(limit = 2)` restore `limit + 2` 条习惯，调用一次 `learn`，断言哪些条目保留下来。

- 提出者：TESTING@G4（NIT，7/10）

#### F629 [NIT] ChineseNumbers 规则函数存在数据泥团与双重否定

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:251`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:251`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:251`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:129`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:131`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:140`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/ChineseNumbers.kt:150`

`decide`、`single`、`ten`、`digitsAsDigits`、`roughOrMinutes` 一起传递 `(text, start, run, before, after)`（ChineseNumbers.kt:251），把这些上下文放到 `Run` 上或一个小的 `Context` 类中可以缩短每个签名。
129、131、140、150 行使用 `?.let { it in SET } == true / != true` 的写法，建议改用小的 `charAt(i) in SET` 辅助函数，读起来更直白。

- 提出者：STYLE@G4（NIT，7/10）

#### F630 [NIT] AudioHistory 没有校验 capacity

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/AudioHistory.kt:22`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/AudioHistory.kt:22`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/AudioHistory.kt:12`

同类（KeyHabits、UserModel、SymbolPanel）都用 `require` 检查大小，而 `AudioHistory`（AudioHistory.kt:12）在 `capacity = 0` 时会在之后的 `% capacity` 处抛出 `ArithmeticException`。
建议加上 `require(capacity > 0)`。

- 提出者：STYLE@G4（NIT，7/10）

#### F631 [NIT] release-report.sh 的 sed 模式依赖 GNU sed

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`release-report.sh:65`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`release-report.sh:65`）
- 位置：`lib/ime-eval/release-report.sh:65`

release-report.sh:65 的 sed 模式使用 `\?`，这是 GNU sed 的扩展；在 BSD sed 上，`:weight` 后缀会留在报告的加权行中。
修复：改用 `sed -E 's/:0(\.[0-9]*)?//g'`。

- 提出者：CORRECTNESS-2@S23（NIT，7/10）

#### F632 [NIT] SMALL 路径含空格或 `:` 时解析出错

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`release-report.sh:36`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`release-report.sh:36`）
- 位置：`lib/ime-eval/release-report.sh:36`

release-report.sh:36 把 SMALL 路径拼进 `SPECS`，路径含空格或 `:` 时会破坏 `for spec in $SPECS` 的分词和 `IFS=: read` 的拆分。
修复：拒绝这类路径，或把 SMALL 放在 `SPECS` 之外处理。

- 提出者：GENERIC-1@S23（NIT，7/10）

#### F633 [NIT] TableRun 的 `commitsOnTo` 结果未缓存

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`TableRun.kt:113-118`、`TableSession.kt:43`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TableRun.kt:113-118`、`TableSession.kt:43`）
- 位置：`lib/ime-eval/src/main/kotlin/org/fcitx/fcitx5/android/eval/TableRun.kt:113`

TableRun.kt:113 的 `commitsOnTo` 在每次词出现时都被调用并新建会话，但其结果只取决于 (code, word, next key)。
修复：像 `cheapest` 那样缓存。

- 提出者：PERFORMANCE@G5（NIT，7/10）

#### F634 [NIT] ParallelTest 断言了内部分发顺序

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ParallelTest.kt:31`、`Parallel.kt:19`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ParallelTest.kt:31`、`Parallel.kt:19`）
- 位置：`lib/ime-eval/src/test/kotlin/org/fcitx/fcitx5/android/eval/ParallelTest.kt:31`

ParallelTest.kt:31 断言 `"03","14","25","036"`，这是条目分发给各线程的方式，而不是调用方得到的结果；改成连续分块时结果顺序不变，但该测试会失败。
修复：只断言每个 worker 的隔离性，例如各 worker 看到的条目互不相交。

- 提出者：TESTING@G5（NIT，7/10）

#### F635 [NIT] 没有可用操作的长按也会取消本次 refine

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/cpp/androidengine/androidengine.cpp:117`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/androidengine/androidengine.cpp:117`）
- 位置：`app/src/main/cpp/androidengine/androidengine.cpp:117`

stopRefining() 在 offers() 之前执行；offers 为 0 时应用不显示菜单（BaseInputView.kt 的 actions.isEmpty()），但本次输入的 25M refine 已被取消，直到下一次按键。
修复：只在确实会返回至少一个操作时才停止 refine。

- 提出者：CORRECTNESS-1@S26（NIT，7/10）

#### F636 [NIT] OpenCC、Marisa 查找未设 REQUIRED

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:32`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:32`）
- 位置：`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:32`；另见 `lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:44`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:41`、`lib/fcitx5-chinese-addons/src/main/cpp/CMakeLists.txt:15`

被跳过的 submodule 顶层原本运行 find_package(OpenCC ... REQUIRED)，配置阶段会给出清晰错误；现在 line 32 不是 REQUIRED，而 line 44 强制 ENABLE_OPENCC ON，某个 ABI 缺少预编译库时会在之后以不清晰的目标或头文件错误失败。另外 line 41 重复了 line 15，本地 FindFcitx5Module.cmake 忽略 COMPONENTS。
修复：使用 find_package(Marisa REQUIRED) 和 find_package(OpenCC REQUIRED)。CORRECTNESS-1@S27 和 CORRECTNESS-2@S27 在 G6-08 的漂移问题中也提出了这一点。

- 提出者：GENERIC-1@S27（NIT，7/10）

#### F637 [NIT] 未捕获 AudioRecord 构造时的 SecurityException

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:248`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:248`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:248`；另见 `app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:241`

若权限在 VoiceFeature 检查之后、麦克风打开之前被撤销，line 241 创建的 Vad 要等到 GC 才释放，而且处理器对任何失败都显示「麦克风无法使用」。
修复：与 IllegalArgumentException 一并捕获 SecurityException。

- 提出者：CORRECTNESS-1@S28（NIT，7/10）

#### F638 [NIT] VoiceEngineTest 固定了发给模型的具体文本

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/androidTestVoice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngineTest.kt:125`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/androidTestVoice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngineTest.kt:125`）
- 位置：`app/src/androidTestVoice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngineTest.kt:125`；另见 `app/src/androidTestVoice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngineTest.kt:126`

line 126（写出的是最后一个候选）检查的是行为；line 125 则会在重构为向模型询问等价的文本集合、输出不变时失败。
建议：放宽为「每个候选的 bare 文本都被询问过」，或删除该断言。

- 提出者：TESTING@G6（NIT，7/10）

#### F639 [NIT] host-eval.sh 中关于解码器的注释放在了构建命令上方

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/sherpa-onnx/host-eval.sh:16`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/host-eval.sh:16`）
- 位置：`lib/sherpa-onnx/host-eval.sh:16`；另见 `lib/sherpa-onnx/host-eval.sh:5`

注释「it decodes all the files it is given at once…」描述的是运行 sherpa-onnx-offline，却放在 cmake --build -j nproc/2 这一行上方，读起来像是构建并行度的理由。
修复：移到第 5 行的用法说明处，或改写。

- 提出者：STYLE@G6（NIT，7/10）

#### F640 [NIT] libime 配置迁移原地单向改写，不保留原文件

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:26`、`LibimeMigration.kt:83`、`Fcitx.kt:449`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:26`、`LibimeMigration.kt:83`、`Fcitx.kt:449`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:26`

`EngineMigration` 每次启动都原地替换 profile、`conf/*.conf` 和 `inputmethod/*.conf`，不保留原文件；`LibimeMigration` 若有 bug 就无法撤销，旧版本也读不了改写后的文件。
修复：在第一次改写前，像 `ImportedDictionaries` 处理词典那样保留一份一次性的 `.libime` 副本。

- 提出者：CORRECTNESS-1@S29（NIT，7/10）

#### F641 [NIT] 删光所有参数后，链接末尾残留 ? 或 #

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：❓ 无法验证（`ClearURLs.kt:119-128`）
- 当前状态（5881d31）：❓ 在 5881d31 上无法验证：`Uri.Builder` 对 `encodedQuery("")` 或 `encodedFragment("")` 会输出 `?` 还是 `#`，取决于 android.net.Uri 框架源码，而它不在仓库中。仓库这一侧成立：当所有参数都被过滤掉时，`filterParams` 返回 `""`（ClearURLs.kt:119-128，对空列表调用 `joinToString`），该值在 :76 和 :83 处被传入。ClearURLsTest.kt 中没有所有参数都被丢弃的用例。
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:76`

`filterParams` 删除全部参数时返回空串，而 `Uri.Builder.encodedQuery` 收到空串仍会输出 `?`（fragment 同理输出 `#`），`https://example.com/?utm_source=x` 会变成 `https://example.com/?`。
修复：把空结果映射为 `null`，并加一个全部删除的测试。

- ❓ 无法验证的原因：`Uri.Builder` 对 `encodedQuery("")` 或 `encodedFragment("")` 会输出 `?` 还是 `#`，取决于 android.net.Uri 框架源码，而它不在仓库中。仓库这一侧成立：当所有参数都被过滤掉时，`filterParams` 返回 `""`（ClearURLs.kt:119-128，对空列表调用 `joinToString`），该值在 :76 和 :83 处被传入。ClearURLsTest.kt 中没有所有参数都被丢弃的用例。
- 提出者：CORRECTNESS-1@S30（NIT，7/10）

#### F642 [NIT] 导入旧版偏好时忽略 renameTo 的返回值

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:110`、`UserDataOrigin.kt:18-19`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:110`、`UserDataOrigin.kt:18-19`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:110`

若改名失败，旧名称的文件仍被复制进来，所有设置悄悄停留在默认值。
修复：检查返回值，为 `false` 时用 `errorRuntime` 让导入失败。

- 提出者：CORRECTNESS-1@S30（NIT，7/10）

#### F643 [NIT] 解开重定向后，目标链接自身的跟踪参数没有被清理

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:58-62`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:58-62`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:62`

ClearURLs 解出重定向目标后直接返回，目标 URL 自己携带的跟踪参数仍然保留。
修复：对解开后的 URL 再运行一次 `transformWith`，并限制递归次数。

- 提出者：GENERIC-1@S30（NIT，7/10）

#### F644 [NIT] CustomQuickPhrase 启用/禁用时忽略 renameTo 的结果

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/CustomQuickPhrase.kt:53-55`、`TextDictionary.kt:46`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/CustomQuickPhrase.kt:53-55`、`TextDictionary.kt:46`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/CustomQuickPhrase.kt:53`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/quickphrase/CustomQuickPhrase.kt:62`、`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/dict/TextDictionary.kt:46`

`enable`/`disable`（:53、:62）即使改名失败也把 `file` 指向新路径；`TextDictionary.rename`（TextDictionary.kt:46）则会检查结果。这是上游代码。
修复：同样检查返回值。

- 提出者：GENERIC-1@S31（NIT，7/10）

#### F645 [NIT] 每次读取时重新定位绝对路径，而没有改存相对文件名

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:58-75`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:58-75`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:62`

当前的修复在每次读取时重新查找绝对路径；若像 `exportTheme` 那样保存相对于 `dir` 的文件名，下一次存储迁移或改名就不会再弄坏主题。
修复：改为保存相对文件名。

- 提出者：CORRECTNESS-2@S32（NIT，7/10）

#### F646 [NIT] 列表时重写主题文件会打乱按修改时间的排序

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:75`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:75`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:75`

主题列表按 mtime 从新到旧排序；按这个顺序重新保存被重定位或迁移的主题，会让最新的主题得到最早的新 mtime，导入备份后下一次列表顺序颠倒，mtime 相同时顺序则不确定。
修复：重写之后恢复每个文件原来的 `lastModified`。

- 提出者：GENERIC-1@S32（NIT，7/10）

#### F647 [NIT] take(42) 可能切断代理对，建议里出现半个 emoji

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:129`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:129`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:129`

如果第 42 个字符处恰好是 emoji，建议中会画出半个、损坏的字形。
修复：按码点边界截断，例如用 `offsetByCodePoints`。

- 提出者：CORRECTNESS-1@S34（NIT，7/10）

#### F648 [NIT] measureWidth 用 getItem() 可能提前触发分页加载

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：❓ 无法验证（`GridPagingCandidateViewAdapter.kt:36`、`SpanHelper.kt:25`）
- 当前状态（5881d31）：❓ 在 5881d31 上无法验证：仓库这一侧成立：`measureWidth` 在 GridPagingCandidateViewAdapter.kt:36 处调用 `getItem(position)`，而它会在 span 查找期间被调用（SpanHelper.kt:25）。`PagingDataAdapter.getItem` 是否会向 Paging 发送触发加载的访问提示（access hint），取决于 AndroidX Paging，而它无法获取。
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/GridPagingCandidateViewAdapter.kt:37`

`SpanHelper` 在布局时会为位置 `i + 1` 调用它，可能提前触发页面加载；`peek(position)` 读取项目而不产生访问提示。
修复：改用 `peek(position)`。

- ❓ 无法验证的原因：仓库这一侧成立：`measureWidth` 在 GridPagingCandidateViewAdapter.kt:36 处调用 `getItem(position)`，而它会在 span 查找期间被调用（SpanHelper.kt:25）。`PagingDataAdapter.getItem` 是否会向 Paging 发送触发加载的访问提示（access hint），取决于 AndroidX Paging，而它无法获取。
- 提出者：GENERIC-1@S35（NIT，7/10）

#### F649 [NIT] 契约测试手工复制了私有的 composingOverhang 公式

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/input/editing/InputEditorContractTest.kt:311-317`、`lib/ime-core/.../input/editing/EditingSession.kt:343-347`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/input/editing/InputEditorContractTest.kt:311-317`、`lib/ime-core/.../input/editing/EditingSession.kt:343-347`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/input/editing/InputEditorContractTest.kt:312`

测试手算了 `EditingSession.composingOverhang()` 这个私有函数的算式；若会话里的公式改变，契约测试仍在测旧公式。
修复：把这个函数在 ime-core 中改为 `internal` 并直接调用，或加注释把两处联系起来。

- 提出者：CORRECTNESS-2@S50（NIT，7/10）

#### F650 [NIT] 四行的常量策略配了五个测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/input/picker/DefaultPickerPolicyTest.kt:19-60`、`app/.../input/picker/PickerPolicy.kt:37-44`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/input/picker/DefaultPickerPolicyTest.kt:19-60`、`app/.../input/picker/PickerPolicy.kt:37-44`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/input/picker/DefaultPickerPolicyTest.kt:19`

每个断言都只是在重复 `filter = true` 或 `transform = raw`，而团队规则要求测试量与改动相称。
修复：保留 `thePopupDoesNotTransformPunctuation`，删掉其余的。

- 提出者：CORRECTNESS-2@S50（NIT，7/10）

#### F651 [NIT] RawConfigTest 的部分用例只验证 Kotlin 语义

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/core/RawConfigTest.kt:188-193`、`Types.kt:79-83`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/core/RawConfigTest.kt:188-193`、`Types.kt:79-83`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/core/RawConfigTest.kt:186`

`valueIsMutableInPlace` 和 `subItemsCanBeReplacedWholesale` 测试的是上游 data class 上的 `var` 赋值，增加了维护成本，却不守护任何行为。
修复：可以考虑删掉（评审者认为是可选项）。

- 提出者：TESTING@G7（NIT，7/10）

#### F652 [NIT] FcitxInputMethodService 已是大类，且继续膨胀

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:845`、`config/detekt/baseline-app.xml:26`、`ContextMemory.kt:42`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:845`、`config/detekt/baseline-app.xml:26`、`ContextMemory.kt:42`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:845`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:854`

这个类有 1006 行，已在 detekt baseline 中被列为 LargeClass，又加入了自动配对（`commitTyped`、`pairing`）、上下文上报和语言相关的 `getResources` override，而 CLAUDE.md 要求 app 层保持很薄；`tellTextBeforeCursor(after = {…}) {…}` 先接默认 lambda、后接主 lambda，:854 的三态 `Boolean?` 中 `false` 同时表示「没有询问」和「非空」。
修复：抽出一个上下文上报器，用小枚举代替 `Boolean?`。

- 提出者：STYLE@G7（NIT，7/10）

#### F654 [NIT] 新增注释的风格与周围上游代码不一致，且难以理解

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:31-38`、`EngineBridge.kt:36-42`、`InputConnectionEditor.kt:61-70`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:31-38`、`EngineBridge.kt:36-42`、`InputConnectionEditor.kt:61-70`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/CustomPhraseManager.kt:31`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:36`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt:187`、`app/src/main/java/org/fcitx/fcitx5/android/input/editing/InputConnectionEditor.kt:61`

例如「[base] is as [load] gave it, or as this returned: what the file has now, in its order」；其他例子在 EngineBridge.kt:36-42、:187-188，以及 InputConnectionEditor.kt:61-70 一个 `when` 分支里的 10 行注释块。
修复：像周围的上游注释那样，用一句平实的话说明原因。

- 提出者：STYLE@G7（NIT，7/10）

#### F655 [NIT] 新增注释措辞难懂且引用不存在的文件

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt:19`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/SpaceLongPressBehavior.kt:16`、`CustomGestureView.kt:181`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt:19`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/SpaceLongPressBehavior.kt:16`、`CustomGestureView.kt:181`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/SpaceLongPressBehavior.kt:16`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:181`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:83`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:162`、`app/src/main/java/org/fcitx/fcitx5/android/utils/Const.kt:19`

例如 SpaceLongPressBehavior 的「a text build lists it not」以及 CustomGestureView:181、T9Keyboard:83、BaseKeyboard:162-164 的注释，措辞与周围上游代码的平实风格不一致。
Const.kt:19 引用了仓库中不存在的 dev/TRAINING-PLAN.md。评审未单列修复方案。

- 提出者：STYLE@G8（NIT，7/10）

#### F656 [NIT] 最后一个类别为空时会高亮最近标签

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`PickerWindow.kt:162`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/picker/PickerSections.kt:23-31`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PickerWindow.kt:162`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/picker/PickerSections.kt:23-31`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerWindow.kt:162`

若过滤（hideUnsupportedEmojis）使最后一个类别为空，startOf 返回 rows.size，categoryAt 返回 0（高亮最近标签），scrollToPositionWithOffset 收到越界位置并被静默忽略。
建议：钳制到最后一个有行的类别。

- 提出者：CORRECTNESS-1@S38（NIT，7/10）

#### F657 [NIT] 提交哈希为 N/A 时关于页链接失效

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:51`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AboutFragment.kt:42-43`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ProjectExtensions.kt:51`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AboutFragment.kt:42-43`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/AboutFragment.kt:42`；另见 `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:51`

在没有 git 的环境构建时（ProjectExtensions.kt:51）哈希为 N/A，关于页会生成指向 .../commit/N/A 的链接。
建议：哈希为 N/A 时不设置点击动作。

- 提出者：CORRECTNESS-2@S40（NIT，7/10）

#### F658 [NIT] 「首选」复选框不反映卡片当前位置

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:120`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:120`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/PunctuationEditorFragment.kt:118`

编辑已是该键首张卡片的卡片时，「首选」复选框仍默认未勾选，与卡片实际位置不符。
建议：该卡片已是首选时预先勾选；或当该键只有一张卡片时隐藏复选框。
CORRECTNESS-1@S42 在 G8b-01 的发现中也提到复选框默认未勾选且取消勾选无法降级。

- 提出者：CORRECTNESS-2@S42（NIT，7/10）

#### F659 [NIT] 手工列出的搜索条目复制他页标题且无测试关联

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:56-59`、`behaviour/AdvancedSettingsFragment.kt:120`、`SearchHighlight.kt:37`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:56-59`、`behaviour/AdvancedSettingsFragment.kt:120`、`SearchHighlight.kt:37`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/search/SettingsIndex.kt:44-58`

`SearchHighlight` 按标题字符串查找要高亮的设置，而 `SettingsIndex` 中手工列出的条目（导出/导入用户数据、长按字符、标点、码表）复制了 `AdvancedSettingsFragment` 与 `KeyboardSettingsFragment` 的标题；其中任一标题改动后高亮会静默失效。
建议：在索引与各 Fragment 之间共享字符串 id，或加测试确认每个 `Target.title` 存在于其页面。

- 提出者：GENERIC-1@S43（NIT，7/10）

#### F660 [NIT] 主题网格宽度变化后间距装饰仍用旧列数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ResponsiveThemeListView.kt:32`、`ThemeListItemDecoration.kt:12`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ResponsiveThemeListView.kt:32`、`ThemeListItemDecoration.kt:12`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/theme/ResponsiveThemeListView.kt:32`

宽度变化时只更新 `grid.spanCount`，`ThemeListItemDecoration` 仍按构建时的列数计算偏移，在不重建 Activity 的尺寸变化后（少见）网格间距会出错。此为继承自上游的代码。
建议：让装饰的列数可变，或替换装饰。

- 提出者：GENERIC-1@S44（NIT，7/10）

#### F661 [NIT] NostalgicSerializer 用默认 `Json` 而非调用方配置解码

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/NostalgicSerializer.kt:24`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/utils/NostalgicSerializer.kt:24`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/NostalgicSerializer.kt:24`

`NostalgicSerializer.kt` 第 24 行的 `Json.decodeFromJsonElement` 丢弃了外层解码器的配置和 serializers module；目前应用中没有设置不同选项的 `Json`，因此无害。
建议：改用 `(decoder as JsonDecoder).json.decodeFromJsonElement(...)`。

- 提出者：GENERIC-1@S45（NIT，7/10）

#### F662 [NIT] ContentResolverTest 只在 minSdk 上运行

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:17`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:17`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/utils/ContentResolverTest.kt:17`

`ContentResolverTest` 第 17 行只配置 `sdk = [23]`；从 API 26 起 5 参数的 `ContentResolver.query` 走 Bundle 重载。
建议：使用 `@Config(sdk = [23, 35])` 覆盖支持范围的两端。

- 提出者：GENERIC-1@S51（NIT，7/10）

#### F663 [NIT] 仅为计数而从引擎取出整个用户词库

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:92`、`UserWordListFragment.kt:187`、`lib/ime-core/.../engine/host/Engines.kt:243-246`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:92`、`UserWordListFragment.kt:187`、`lib/ime-core/.../engine/host/Engines.kt:243-246`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:92`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:187`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordListFragment.kt:268`

`UserWordsFragment.refreshCounts`（每次 `onResume`）以及 `UserWordListFragment.kt:187`、`:268`（每次编辑后）都从引擎复制出所有类型的全部用户词，再计数或过滤。
建议：在引擎中提供计数 API 和按类型查询。

- 提出者：PERFORMANCE@G8（NIT，7/10）

#### F664 [NIT] 调试用 TypingHostActivity 的监听器吞掉回车键事件

- 来源：第一轮（整仓 @07d2778） · 共识：1/5 位 reviewer（1 NIT）· 置信度 7/10 · 验证：❓ 无法验证（`TypingHostActivity.kt:34-37`、`FcitxInputMethodService.kt:355`）
- 当前状态（5881d31）：❓ 在 5881d31 上无法验证：该监听器对所有动作都返回 true（debug TypingHostActivity.kt:34-37），并且输入法向多行输入框发送 Return 时，以 KEYCODE_ENTER 的按下/抬起事件发送（FcitxInputMethodService.kt:355）。“TextView 在按下和抬起时都会把 Enter 按键事件以 IME_NULL 传给监听器，并在监听器返回 true 时丢弃换行”这一点依赖于 TextView 的代码；SDK 中只有桩代码（android-stubs-src.jar）
- 位置：`app/src/debug/java/org/fcitx/fcitx5/android/input/TypingHostActivity.kt:36`

TextView 在插入换行之前，会把每个 KEYCODE_ENTER 以 `IME_NULL` 传给 editor-action 监听器；该监听器总是返回 true，所以默认的多行输入框永远收不到换行符，按键事件形式的回车会被记录为 [0, 0]。
修复：`if (actionId == EditorInfo.IME_NULL) false else { actions += actionId; true }`。

- ❓ 无法验证的原因：该监听器对所有动作都返回 true（debug TypingHostActivity.kt:34-37），并且输入法向多行输入框发送 Return 时，以 KEYCODE_ENTER 的按下/抬起事件发送（FcitxInputMethodService.kt:355）。“TextView 在按下和抬起时都会把 Enter 按键事件以 IME_NULL 传给监听器，并在监听器返回 true 时丢弃换行”这一点依赖于 TextView 的代码；SDK 中只有桩代码（android-stubs-src.jar）
- 提出者：CORRECTNESS-1@S46（NIT，7/10）

#### F665 [NIT] 使用 API 24 渐变的启动图标背景放在无限定 drawable/ 中

- 来源：第一轮（整仓 @07d2778） · 共识：1/5 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`drawable/ic_launcher_background.xml:10-11`、`ic_launcher_background_debug.xml:10-11`、`Versions.kt:12`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`drawable/ic_launcher_background.xml:10-11`、`ic_launcher_background_debug.xml:10-11`、`Versions.kt:12`）
- 位置：`app/src/main/res/drawable/ic_launcher_background.xml:11`

`ic_launcher_background.xml` 和 `ic_launcher_background_debug.xml` 用 `aapt:attr` `<gradient>` 填充，VectorDrawable 从 API 24 才支持这种写法，而 minSdk 是 23。
目前只有 `mipmap-anydpi-v26` 图标使用它们，不会出错；但将来若在 API 23 上运行的代码引用它们，会无法 inflate，lint 也会报 NewApi。
修复：把两个文件移到 `drawable-v26/`。

- 提出者：GENERIC-1@S47（NIT，7/10）

#### F666 [NIT] `BUILD_TIME` 每次都变，无改动的构建也不是 up to date

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:31`、`ProjectExtensions.kt:53-55`、`Utils.kt:16-21`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:31`、`ProjectExtensions.kt:53-55`、`Utils.kt:16-21`）
- 位置：`build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:31`；另见 `build-logic/convention/src/main/kotlin/ProjectExtensions.kt:55`

`BUILD_TIME` 默认取 `currentTimeMillis()`（ProjectExtensions.kt:55），所以没有源码改动的 `assembleTextDebug` 仍会重新生成并编译 BuildConfig、重新 dex，并重新打包带有大体积未压缩资源的 APK。这是上游代码。
建议：使用提交时间（`git log -1 --format=%ct`），或在 debug 构建中固定该值。

- 提出者：PERFORMANCE@G1（NIT，6/10）

#### F667 [NIT] voice 模型在 build/ 下存了两份

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`VoiceDataPlugin.kt:73-77`、`EngineDataPlugin.kt:301-306`、`VoiceDataPlugin.kt:84-89`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`VoiceDataPlugin.kt:73-77`、`EngineDataPlugin.kt:301-306`、`VoiceDataPlugin.kt:84-89`）
- 位置：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:126`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:294`

解压任务（EngineDataPlugin.kt:294）把整个压缩包解到 build/voice-sources，`CopyVoiceModels` 再把其中约 240 MB 复制到 build/generated/voice-assets，每次 `clean` 后两步都会重跑。
建议：只解压 `MODEL_FILES` 列出的文件（`cmake -E tar xf <archive> <members>`），并改为移动或硬链接而不是复制。

- 提出者：PERFORMANCE@G1（NIT，6/10）

#### F669 [NIT] 字节码可达性指纹过于敏感，可改用独立模块

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:40-43`、`EngineDataPlugin.kt:171-174`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:40-43`、`EngineDataPlugin.kt:171-174`）
- 位置：`build-logic/convention/src/main/kotlin/ToolFingerprint.kt:1-80`

指纹对整个 class 文件求哈希，只改行号或改同一个 Kotlin file facade 里的无关函数也会触发数分钟的重编；未来 JDK 新增的常量池 tag 会让构建以 `error(...)` 失败。
建议（可选，目前正确性不需要）：把数据格式和编译器移到只被该工具依赖的小模块，用 `@Classpath` 作为键。

- 提出者：CORRECTNESS-1@S2（NIT，6/10）

#### F670 [NIT] 显式设置的输出路径可能被 AGP 覆盖

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❓ 无法验证（`EngineDataPlugin.kt:185`、`VoiceDataPlugin.kt:88`、`release-report.sh:9`）
- 当前状态（5881d31）：❓ 第二轮无法验证：该论断取决于 AGP 9.3.1 的 addGeneratedSourceDirectory 是否会重置任务的 outputDir。AGP 的源码既不在仓库中，也不在本地 Gradle 缓存中，并且不存在 app/build 输出。本次改动只是移动了被引用的代码（EngineDataPlugin.kt:185、:194、:206、:217-219；VoiceDataPlugin.kt:88、:99；release-report.sh:9）。
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:178`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:187`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:198`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:209-211`、`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:99`、`lib/ime-eval/release-report.sh:9`

AGP 的 `addGeneratedSourceDirectory`（第 209-211 行；VoiceDataPlugin.kt:99）会自行设置所连接的 `DirectoryProperty`，最后配置的变体生效，这里设置的 `generated/engine-*` 路径就成了死代码；`lib/ime-eval/release-report.sh:9` 让用户去 `app/build/generated/engine-models/engine` 找文件，该目录可能不存在。
建议：删掉这些 `set` 调用并修正脚本注释，或在构建后核对真实路径。CORRECTNESS-1@S2 未读 AGP 源码（置信度 6）。

- ❓ 无法验证的原因：该论断取决于 AGP 9.3.1 的 addGeneratedSourceDirectory 是否会重置任务的 outputDir。AGP 的源码既不在仓库中，也不在本地 Gradle 缓存中，并且不存在 app/build 输出。本次改动只是移动了被引用的代码（EngineDataPlugin.kt:185、:194、:206、:217-219；VoiceDataPlugin.kt:88、:99；release-report.sh:9）。
- 提出者：CORRECTNESS-1@S2（NIT，6/10）

#### F671 [NIT] CopyVoiceModels 等任务缺少缓存注解

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:104`、`BuildMetadataPlugin.kt:54`、`EngineDataPlugin.kt:369-370`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:104`、`BuildMetadataPlugin.kt:54`、`EngineDataPlugin.kt:369-370`）
- 位置：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:104`；另见 `build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:56`

同级的 `CopyModels` 标注了 `@DisableCachingByDefault`，而 `CopyVoiceModels` 没有任何缓存注解，`validatePlugins` 会告警；`BuildMetadataTask`（BuildMetadataPlugin.kt:56）有同样的缺口。
建议：补上注解以保持一致。STYLE@G1 与 CORRECTNESS-1@S2 在 G1-18 的发现中也顺带指出了 `CopyVoiceModels`。

- 提出者：GENERIC-1@S2（NIT，6/10）

#### F672 [NIT] 解压任务的 CMake 路径忽略 `cmake.dir`

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`EngineDataPlugin.kt:133`、`VoiceDataPlugin.kt:75`）
- 当前状态（5881d31）：仍存在（第二轮已验证：`EngineDataPlugin.kt:133`、`VoiceDataPlugin.kt:75`）
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:126`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:138`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:290`

解压任务总是使用 `<sdk>/cmake/<ver>/bin/cmake`（第 126、138、290 行），所以通过 local.properties 的 `cmake.dir` 能完成原生构建的环境在这里仍会失败；这些任务与 AGP 自带的 CMake 自动安装之间也没有排序。第 290 行的错误信息清晰，影响较小。
建议：同时支持 `cmake.dir`，或在文档中写明这一要求。

- 提出者：CORRECTNESS-2@S2（NIT，6/10）

#### F673 [NIT] BuildMetadataTask 用弃用 API 且时间戳不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:67-68`、`ProjectExtensions.kt:55`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:67-68`、`ProjectExtensions.kt:55`）
- 位置：`build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:67`；另见 `build-logic/convention/src/main/kotlin/BuildMetadataPlugin.kt:31`

任务动作内的 `with(project)` 在 Gradle 9 中已弃用并会破坏配置缓存；它还在运行时再次调用 `buildTimestamp`，使 `build-metadata.json` 记录的时间与 `BuildConfig.BUILD_TIME`（第 31 行）不同。该代码来自上游。
建议：在配置期把这些值捕获到 `@Input` 属性中。

- 提出者：GENERIC-1@S2（NIT，6/10）

#### F674 [NIT] latin.py 跨拼写累加页数，同页被重复计数

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lexicon/tools/latin.py:57`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lexicon/tools/latin.py:57`）
- 位置：`lexicon/tools/latin.py:57`

latin.py:57 把同一词各拼写的页数相加，同时含 APP 和 app 的页面会对 MIN_PAGES 计两次。
建议：在 latin_counts.py 中按小写词统计不同页面数。

- 提出者：GENERIC-1@S4（NIT，6/10）

#### F675 [NIT] mix 对 Common Crawl 分片也只取聊天类页面，与文档不符

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:651`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:651`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Main.kt:651`；另见 `lib/ime-dict-tool/engine-data.sh:3`、`lib/ime-dict-tool/build.gradle.kts:15`

Main.kt:651 让每个 `.parquet` 输入都经过默认 `chatOnly = true` 的 `WebText.read`，因此只混入 crawl 中的聊天类页面；而 README（「Common Crawl 一次抓取里中文网页」）、engine-data.sh:3 和 build.gradle.kts:15 说混入的是 crawl 的中文网页。
已报告的数字是用这段代码测得的，所以建议修正文档，或把该行为做成显式选项。

- 提出者：GENERIC-1@S5（NIT，6/10）

#### F676 [NIT] :lib:ime-dict-tool 没有覆盖率报告或下限

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`.github/workflows/unit_test.yml:80`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`.github/workflows/unit_test.yml:80`）
- 位置：`.github/workflows/unit_test.yml:80`

ime-core 有 Kover 覆盖率下限，但 ime-dict-tool 的输出同样随 APK 发布，CI（unit_test.yml:80）却没有它的覆盖率报告。
Kover 报告能显示哪些命令没有测试（`text`、经 CLI 的 `strokes`、`cc` 选项解析）；该评审认为添加是可选的。

- 提出者：TESTING@G2（NIT，6/10）

#### F677 [NIT] WebTextTest 夹具的 date 类型与 WebText 分片不同

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:149`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:149`）
- 位置：`lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:149`；另见 `lib/ime-dict-tool/src/test/kotlin/org/fcitx/fcitx5/android/dicttool/WebTextTest.kt:85`

WebTextTest.kt:149 的辅助函数把 `date` 写成 TIMESTAMP，所以 :85 期望 `2023-06-14 00:00:00`；而 WebText 的 KDoc 写的是 `2023-06-14T...`，`WebText.write` 也以 VARCHAR 存日期。
差异无害（调用方只用 `take(4)`），但用 VARCHAR 的 ISO 日期才能测到工具平常读取的输入。

- 提出者：GENERIC-1@S6（NIT，6/10）

#### F678 [NIT] Examples 每个字符做装箱 Long 的查找

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Examples.kt:49`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Examples.kt:49`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/Examples.kt:49`

Examples.kt:49 的 `short[k]`、`long[k]` 是 `HashMap<Long, …>` 查找，每个字符最多 5 次 Long 装箱，且对每个已清洗分片的每一页都要执行。
建议：用包内的 `LongIndex` 加平行的词数组避免装箱。GENERIC-1@S4 在其 ChatCounts 发现（G2-06）中也提到 Examples.kt:49 每次查找都装箱 Long。

- 提出者：PERFORMANCE@G2（NIT，6/10）

#### F679 [NIT] ArpaModel 写每个数都调用 String.format

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ArpaModel.kt:105`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ArpaModel.kt:105`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/ArpaModel.kt:105`

写混合 ARPA 时每个 n-gram 行调用 2–3 次 `String.format("%.6g")`（ArpaModel.kt:105），每次 mix 和每个权重各写一遍；Main 的 `words` 和 `pack` 对每行也是如此。
建议：复用一个 `Formatter`，或写一个 6 位有效数字的小写入器，并对比输出字节确保文件不变。

- 提出者：PERFORMANCE@G2（NIT，6/10）

#### F680 [NIT] WebText.accepts() 逐字线性扫描三个字符串

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:41`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:41`）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/WebText.kt:41`

WebText.kt:41 中每个 `c in SIMPLIFIED/TRADITIONAL/PARTICLES` 都线性扫描一个字符串（每字约 60 次比较）；`words` 在 6 次 NewWords 遍历中每次都重新经 DuckDB 解码、过滤并切分同样的分片。
建议：用 `BooleanArray(65536)` 查表让单字检查成为常数时间，并把接受的行一次写入临时文件，免去重复解码。

- 提出者：PERFORMANCE@G2（NIT，6/10）

#### F681 [NIT] backoff(prev, word) 把 NO_WORD 当作 <unk>

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:331`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:248`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:213`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:331`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:248`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:213`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/PinyinData.kt:331`

`prev = NO_WORD` 时 `known(-1)` 映射为 `<unk>`，方法返回 (`<unk>`, word) 的回退权重；而类文档说作为上下文的 `NO_WORD` 表示无上下文，`score(prev, word)` 也照此处理。目前只有词典工具调用此方法，且总是传入真实词。
建议：`prev == NO_WORD` 时返回 `0f`。

- 提出者：CORRECTNESS-1@S7（NIT，6/10）

#### F682 [NIT] StringTable.startsWith 有分配，与类文档不符

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/StringTable.kt:43`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/StringTable.kt:11`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/StringTable.kt:43`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/StringTable.kt:11`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/StringTable.kt:43`

`prefix.indices.all {}` 每次调用都创建 `IntRange` 和迭代器，而 `startsWith` 在 `CodeTable.prefixRange` 二分查找的每一步都会运行；开销可忽略，但与类文档“读取不分配”的说法不符。
建议：改为普通下标循环。

- 提出者：CORRECTNESS-1@S7（NIT，6/10）

#### F683 [NIT] BitPacked 公共构造函数依赖调用方的字节序

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/BitPacked.kt:18`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/BitPacked.kt:26`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/BitPacked.kt:18`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/BitPacked.kt:26`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/data/BitPacked.kt:18`

传入 `ByteBuffer.wrap` 得到的缓冲区（默认大端）时，会读出错误的宽度和大小；模块内的调用方都传 DataFile 的小端段。
建议：在构造函数内使用 `buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)`。

- 提出者：CORRECTNESS-1@S7（NIT，6/10）

#### F684 [NIT] 放在第 0 层的词包词被当作“无词包”

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`LayerPrior.kt:57`、`Engines.kt:461`、`UserModel.kt:221-226`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`LayerPrior.kt:57`、`Engines.kt:461`、`UserModel.kt:221-226`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:57`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/LayerPrior.kt:54`

`Engines` 把以 `intoNew=false` 导入的词包列在第 0 层，而 `layerOf` 把 0 视为“no pack”；因此词典有而模型没有的词包词（modelWords ≤ id < vocabulary.size）取的是词典的层，与第 54-55 行 KDoc“词包的词取词包的层”不符。
建议：让 `extra` 用 -1 表示“无”，或收窄该注释。

- 提出者：CORRECTNESS-2@S9（NIT，6/10）

#### F685 [NIT] firstWords 每键都对起点所有 arc 重新打分

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`PinyinDecoder.kt:389`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`PinyinDecoder.kt:389`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:389`

增量搜索不覆盖 `firstWords`：单独一个声母约有 2k 条起点 arc（起点处没有 `wordsPerReading` 上限），每条都要经过先验、用户和 n-gram 打分器。
建议：在 start、prev2、prev 与起点 arc 组不变时缓存这些分数。

- 提出者：PERFORMANCE@G3（NIT，6/10）

#### F686 [NIT] 测试名称承诺的leave()余量上屏路径从未被执行

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:504`、`PinyinSession.kt:461`、`PinyinDecoder.kt:363`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:504`、`PinyinSession.kt:461`、`PinyinDecoder.kt:363`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionTest.kt:499`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:461`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:117`

在 `aKeyNotReadKeepsWhatTheFirstCandidateLeavesAsTyped` 中，再 读完了整个「zai」，所以 `leave()` 的 `commitRaw()` 余量分支（PinyinSession.kt:461）从未运行；解码器为每条边都加 raw 弧（PinyinDecoder.kt:117），整句读法总排第一，该分支在拼音中看似不可达。
建议：重命名测试，或加一个真正走到余量分支的用例；若不存在这样的用例，则简化 `leave()`。

- 提出者：CORRECTNESS-2@S18（NIT，6/10）

#### F687 [NIT] 误读测试中「无提示」的断言可能什么都没测

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionMisreadingTest.kt:48`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionMisreadingTest.kt:48`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionMisreadingTest.kt:48`；另见 `lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSessionMisreadingTest.kt:53`

第 48 行取 `candidates(0, 1).single().hint` 却不检查该候选是否为 般若；第 53 行用 `firstOrNull { it.text == ... }?.hint.orEmpty()`，般若 不在列表中时也会通过。
建议：先断言 般若 存在，再检查其提示。

- 提出者：CORRECTNESS-2@S18（NIT，6/10）

#### F688 [NIT] 每个会话重复持有只读缓存，空闲时也保留

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:63`、`Engines.kt:832`、`PinyinSession.kt:105`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:63`、`Engines.kt:832`、`PinyinSession.kt:105`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/rerank/Reranker.kt:63`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/Predictor.kt:177`

拼音、九键和双拼会话各有自己的 Reranker Scorer 与 LateRefiner Scorer（`kept` 各上限约 6 MB）、自己的 `Predictor.charReadings`（约 2 万项，Predictor.kt:177）和解码器 `extensions`，并在每次 `sessions.clear()` 后全部重建。
建议：在 Engines 层共享这些只读缓存。

- 提出者：PERFORMANCE@G3（NIT，6/10）

#### F689 [NIT] ForwardedKeys 用 xor 求释放的修饰位，会带上新置位

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ForwardedKeys.kt:59`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ForwardedKeys.kt:59`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/editing/ForwardedKeys.kt:59`

KDoc 说「clears exactly the meta bits that release dropped」，但当修饰键的抬起事件带有新置位（锁存或锁定）时，`last xor metaState`（ForwardedKeys.kt:59）也会返回这一位，让编辑器把它一起清除。
建议改为 `last and metaState.inv()`，与 KDoc 一致。

- 提出者：GENERIC-1@S14（NIT，6/10）

#### F690 [NIT] SettingsSearch 每次查询都重新小写并拼接全部条目文本

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearch.kt:37`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearch.kt:37`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/ui/search/SettingsSearch.kt:37`

`find`（SettingsSearch.kt:37）在每次按键时运行，每次都把每个条目的文本小写化并拼接。
建议在构造函数中一次性算好每个条目的小写标题与搜索文本。

- 提出者：PERFORMANCE@G4（NIT，6/10）

#### F691 [NIT] 线程检查守护测试按反射数公共方法个数，过于脆弱

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:644-648`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:644-648`）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/editing/EditingSessionTest.kt:645`

`EditingSessionTest.kt:645` 的守护测试按反射统计公共方法个数，新增或拆分一个名称不以 `get` 开头的公共查询方法，即使行为不变也会让测试失败。
守护测试的目的合理；建议改为与显式的方法名允许列表比对，只在出现未知公共方法时失败，并提示把它加入 `calls`。GENERIC-1@S21 在 G4-12 中也建议按名称比对。

- 提出者：TESTING@G4（NIT，6/10）

#### F692 [NIT] make-chat-set.py 单字全局读音导致部分样本标错

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/ime-eval/tools/make-chat-set.py:34`、`data/pinyin-chat.tsv:474`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-eval/tools/make-chat-set.py:34`、`data/pinyin-chat.tsv:474`）
- 位置：`lib/ime-eval/tools/make-chat-set.py:33`

make-chat-set.py:33 的 `'系': 'xi'`、`'还': 'hai'`、`'地': 'de'`、`'着': 'zhe'` 等映射在分词器把该字单独留下时一律生效，系(ji)鞋带、还(huan)给 等用法会得到错误输入，被计为解码器失误。
噪声较小且文件头已承认；修复：像处理「得」的 `MUST` 那样加一份简短的例外表。

- 提出者：CORRECTNESS-2@S24（NIT，6/10）

#### F693 [NIT] androidengine addon 缺失时此处没有日志

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:821`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/native-lib.cpp:821`）
- 位置：`app/src/main/cpp/native-lib.cpp:821`

所有拼音和码表输入现在都依赖 androidengine addon；addon("androidengine", true) 返回 null 时引擎回调被静默跳过。addon 加载器会记录加载失败的原因，但在此处加一条 FCITX_ERROR() << "androidengine not loaded" 能让用户日志中的故障一目了然。

- 提出者：GENERIC-1@S25（NIT，6/10）

#### F694 [NIT] HookAddCustomCommand 遇无 COMMAND 调用会配置失败

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❓ 无法验证
- 当前状态（5881d31）：❓ 在 5881d31 上无法验证：对不存在的 arg_COMMAND 执行 list(GET) 是否会导致 configure 失败，以及不加引号的 ${ARGV} 如何丢弃空元素，取决于 CMake 的实现，而它不在仓库中
- 位置：`lib/fcitx5/src/main/cpp/cmake/HookAddCustomCommand.cmake:8`

list(GET arg_COMMAND 0 …) 在 fcitx5 或 addons 中出现不带 COMMAND 的 add_custom_command(OUTPUT … APPEND DEPENDS …) 时会以 list GET given empty list 终止配置；未加引号转发 ${ARGV} 还会丢掉空参数。这是继承自上游的问题，在当前固定版本下不会发生。
修复：把这一检查包在 if(arg_COMMAND) 中。

- ❓ 无法验证的原因：对不存在的 arg_COMMAND 执行 list(GET) 是否会导致 configure 失败，以及不加引号的 ${ARGV} 如何丢弃空元素，取决于 CMake 的实现，而它不在仓库中
- 提出者：CORRECTNESS-2@S27（NIT，6/10）

#### F695 [NIT] NoModel 路径的注释夸大了它能捕获的情况

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/file-utils.cc:105`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/file-utils.cc:105`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceListener.kt:108`；另见 `lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/file-utils.cc:105`

资源缺失或不可读时并不会抛 IllegalArgumentException：vendored 的 file-utils.cc:105 直接调用 exit(-1)，杀死 IME 进程。VoiceDataPlugin 总会打包这些资源，正常构建中不会发生。
修复：更正注释。

- 提出者：CORRECTNESS-1@S28（NIT，6/10）

#### F696 [NIT] letGo 在主线程释放原生模型

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:49`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:49`）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngine.kt:49`

recognizer?.release() 在最后一次使用两分钟后于 UI 线程拆除 onnxruntime 会话，若此时正在使用键盘，可能掉帧。
建议：在仍持有 lock 的前提下把释放投递到后台执行器。

- 提出者：GENERIC-1@S28（NIT，6/10）

#### F697 [NIT] CreateStream(hotwords, blocked) 解析无主机测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:219-249`、`VoiceEngineTest.kt:56`、`lib/sherpa-onnx/host-test.sh:26-28`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:219-249`、`VoiceEngineTest.kt:56`、`lib/sherpa-onnx/host-test.sh:26-28`）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:219`

/ 分割、每条一个短语的检查以及被排除的路径只由 VoiceEngineTest 中唯一的「开 放」短语覆盖；在被排除的路径上，含模型缺失 token 的短语根本不会被屏蔽。用一个小 tokens 表写 gtest 即可覆盖。
由于 Kotlin 侧的 VoiceBlocking 仍会过滤假设，评审者认为优先级低。

- 提出者：TESTING@G6（NIT，6/10）

#### F699 [NIT] 导出时 fcitx 线程可能正在追加或压缩引擎日志

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:72`、`AdvancedSettingsFragment.kt:85-90`、`lib/ime-core/.../engine/store/RecordStore.kt:158-168`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:72`、`AdvancedSettingsFragment.kt:85-90`、`lib/ime-core/.../engine/store/RecordStore.kt:158-168`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:72`

被 `walkTopDown` 列出、但在打开前已被改名的 `<log>.compacting` 会让整个导出以 FileNotFoundException 失败，零散的 `.compacting` 和 `.unreadable` 文件也会进入备份；末尾不完整的最后一条记录没有问题，RecordStore 打开时会截掉它。
修复：跳过 `.compacting` 文件，并容忍在列出之后、打开之前消失的文件。

- 提出者：CORRECTNESS-2@S30（NIT，6/10）

#### F700 [NIT] setIntoNew 改名失败时残留 .new-words.tmp

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:166`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:166`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/ImportedDictionaries.kt:166`

`setIntoNew`（:166-167）在改名失败时不删除 `.new-words.tmp`。
修复：像 `write` 那样在 try/finally 中删除临时文件。

- 提出者：GENERIC-1@S31（NIT，6/10）

#### F701 [NIT] 码表文件以词典文件名而不是输入法名命名

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:86`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:86`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:86`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:89`、`app/src/main/java/org/fcitx/fcitx5/android/data/table/TableManager.kt:75`

两个输入法的词典文件基名相同时，会以 `table_dict_already_exists` 失败（:89-91）；改为 `$name.txt`（:75 的 conf 检查已保证它唯一）就能消除这种失败和这个分支。
修复：用输入法名命名码表文件。

- 提出者：CORRECTNESS-2@S32（NIT，6/10）

#### F702 [NIT] getBaseline() 重复计入 paddingTop

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/AutoScaleTextView.kt:202`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/AutoScaleTextView.kt:202`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/AutoScaleTextView.kt:202`

`baselineY` 已经从 `contentTop = paddingTop` 开始计算（`calculateBaselineY`），`onDraw` 也只按 `baselineY` 平移，因此报告的基线比实际低了 `paddingTop`，会影响按基线对齐的父布局（横向 `LinearLayout` 默认按基线对齐）。
修复：`return baselineY.roundToInt()`。

- 提出者：GENERIC-1@S32（NIT，6/10）

#### F703 [NIT] getResources() 缓存字段跨线程读写且未加 @Volatile

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:459`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:459`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:459`

`SubtypeManager.syncWith(enabledIme(), this)` 在 `postFcitxJob`（不在主线程）中通过这个 override 读取字符串，而主线程在 `pickedLocalesChanged()` 中写入 `appLocales` 和 `localizedResources`；最坏的情况是多建一个配置 context，或读到过期的语言。
修复：把这两个字段标为 `@Volatile`，或显式传入 `localizedResources`。

- 提出者：CORRECTNESS-1@S33（NIT，6/10）

#### F704 [NIT] 引号修复重置 fcitx 全部状态，丢掉上下文后不重新告知

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:754`

有选区时 `onCursorUpdate` 返回 `CursorUpdate.None`，因此这里的 `reset()` 丢掉了引擎的上下文，却没有任何地方重新告知。
修复：无论更新结果如何，在这次 reset 之后都重新告知上下文。

- 提出者：CORRECTNESS-2@S33（NIT，6/10）

#### F706 [NIT] ClearURLsTest 以相对于工作目录的路径读取规则文件

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:66`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:66`）
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:65`

`File(src/main/res/raw/clearurls_rules.json)` 只有在从模块目录运行测试时才有效；Gradle 满足这一点，但并非所有 IDE 运行配置都满足。
修复：从 `build.gradle.kts` 设置的系统属性中解析路径。

- 提出者：CORRECTNESS-2@S50（NIT，6/10）

#### F707 [NIT] 每个 StatusAreaEvent 都经 JNI 重读并重新解析标点映射

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:40`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:40`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/PunctuationComponent.kt:40`

这是上游代码。
修复：按语言代码缓存映射，并在标点编辑器保存时让缓存失效。

- 提出者：PERFORMANCE@G7（NIT，6/10）

#### F708 [NIT] 长按列表清空后滑动预览仍显示字母

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`BaseKeyboard.kt:342`、`KeyDefPreset.kt:58`、`BaseKeyboard.kt:265-270`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`BaseKeyboard.kt:342`、`KeyDefPreset.kt:58`、`BaseKeyboard.kt:265-270`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:342`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:276`

用户清空某字母的长按列表时 swipeText 返回空字符串，预览回退显示 it.content（该字母），但抬起时什么都不输入（:276 跳过滑动，手指离开键后点击被抑制）。
建议：swipeText 为空字符串时不显示预览或关闭预览。

- 提出者：CORRECTNESS-1@S37（NIT，6/10）

#### F709 [NIT] 按住说话的密码框保护没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`

CommonKeyActionListener 中阻止在密码框使用语音输入的隐私保护是内联在监听器里的代码，因此没有测试。
建议：让 ime-core 中 VoiceHold 的入口接收 inPasswordField 参数，用现有测试固定这一保护。

- 提出者：TESTING@G8（NIT，6/10）

#### F710 [NIT] 九键占位键仍为可用且可点击状态

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`T9Keyboard.kt:83-86`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyView.kt:115-116`、`BaseKeyboard.kt:450-455`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`T9Keyboard.kt:83-86`、`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyView.kt:115-116`、`BaseKeyboard.kt:450-455`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/T9Keyboard.kt:83`

TalkBack 线性导航会停在列下方三个无标签按钮上；vivo 兼容模式下第二根手指落到列上（POINTER_DOWN）会被分发给占位键，只播放触感和声音。
建议：对占位键设置 isEnabled = false 和 importantForAccessibility = NO。

- 提出者：CORRECTNESS-1@S38（NIT，6/10）

#### F711 [NIT] 覆盖字母的键角在下次标点广播前显示内置符号

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`TextKeyboard.kt:172-176`、`KeyView.kt:335`、`TextKeyboard.kt:301-305`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`TextKeyboard.kt:172-176`、`KeyView.kt:335`、`TextKeyboard.kt:301-305`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:172`

覆盖在构造时解析并立即作用于滑动，但 updatePunctuationKeys() 只在 onPunctuationUpdate 或偏好变化时运行，而 KeyboardWindow 只把标点更新转发给当前显示的布局；构造后或从数字布局返回后，键角与滑动可能不一致。
建议：在 onAttach() 中也调用 updatePunctuationKeys()。

- 提出者：CORRECTNESS-2@S38（NIT，6/10）

#### F712 [NIT] 肤色变换在每次绑定时重复计算

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:159`、`PickerPolicy.kt:65-67`、`EmojiModifier.kt:106-108`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:159`、`PickerPolicy.kt:65-67`、`EmojiModifier.kt:106-108`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerGridAdapter.kt:159`

选择非默认肤色时，每次绑定可修饰表情都会执行 getCodePoints、buildEmoji、RGI UnicodeSet.contains 和 TextPaint.hasGlyph，滚动时的重新绑定会重复这些计算。
建议：把变换后的字符串缓存在 filtered 旁边；invalidateKey() 已包含肤色。

- 提出者：PERFORMANCE@G8（NIT，6/10）

#### F713 [NIT] 移除「重新加载配置」后无快捷方式应用文件修改

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/status/StatusAreaWindow.kt:54-71`、`Fcitx.kt:70`、`DeveloperFragment.kt:92-99`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/status/StatusAreaWindow.kt:54-71`、`Fcitx.kt:70`、`DeveloperFragment.kt:92-99`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/status/StatusAreaWindow.kt:129`；另见 `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:38`

提供者仍把 config/config、config/profile 和 punc.mb.* 列为可编辑文本（FcitxDataProvider.kt:38），去掉状态区入口后，在文件管理器中修改这些文件只能通过开发者设置重启 fcitx 才能生效。
建议：在设置中提供重新加载入口，或在提供者写入这些文件时自动重新加载。

- 提出者：CORRECTNESS-2@S39（NIT，6/10）

#### F714 [NIT] 应用语言摘要可能不更新

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:129-134`、`AppLanguage.kt:58-63`、`AndroidManifest.xml:65`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:129-134`、`AppLanguage.kt:58-63`、`AndroidManifest.xml:65`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt:129`

若 setApplicationLocales 产生相同的配置（例如手机只设 zh-CN 时从空字符串改为 zh-CN），页面不会被重建，摘要保留旧标签。
建议：在 choose 回调中设置摘要。

- 提出者：CORRECTNESS-1@S40（NIT，6/10）

#### F715 [NIT] 用户词导入导出失败只弹 toast，不写日志

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:114-119`、`utils/Toast.kt:23-25`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:114-119`、`utils/Toast.kt:23-25`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:114-119`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/UserWordsFragment.kt:136-140`

`UserWordsFragment` 第 114-119 行与第 136-140 行失败时只显示 toast，从不调用 Timber，用户为错误报告导出的日志里没有失败信息；`AdvancedSettingsFragment` 至少会打印堆栈。
建议：在每个 toast 旁加 `Timber.w(e)`。

- 提出者：GENERIC-1@S43（NIT，6/10）

#### F733 [NIT] 许可条目与 README 只写旧的句子模型发布

- 来源：第二轮（更新 07d2778..5881d31） · 共识：5/7 位 reviewer（5 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/licenses/libraries/sentence-models.json:3`、`README.md:57`、`sentence-models.json:4`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`app/licenses/libraries/sentence-models.json:3`；另见 `app/licenses/libraries/sentence-models.json:6`、`app/licenses/libraries/sentence-models.json`、`README.md:57`、`README.md:173`、`README.md:19-20`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:831`

`app/licenses/libraries/sentence-models.json` 的 `artifactVersion`（:3）和 `website`（:6）仍只指向 `sentence-models-20261001`，新发布 `sentence-models-t9-20261007` 只出现在 description 文字里，没有链接；README.md:57 与 :173（中英文「数据与许可」）也只写旧发布。
GENERIC-1 另指出 README.md:19-20 仍按一个发布描述两个模型（4.5 MB + 25 MB），没有提 T9 对、APK 多出的约 30 MB 和多下载的两个文件；GENERIC-2 还指出 Engines.kt:831 仍写「over the one model」（另见 D-07）。
建议：在 README 中英文两部分和许可条目里写上并链接 T9 发布；GENERIC-1 建议把 URL 加进 description 或拆成单独条目，STYLE 建议按 `app/licenses/libraries/` 每个文件描述一个制品的惯例另建 `sentence-models-t9.json`。各票均为 NIT。

- 子论断 F733.e 被否定：「这一个许可证条目现在涵盖两个发布版本，但通过 artifactVersion 和 website 只链接了 sentence-models-20261001，而 app/licenses/libraries/ 中的其他每个文件都只描述一个制品。」→ sentence-models.json:3,6 确实只链接了 sentence-models-20261001，但“其他每个文件都只描述一个制品”这一说法是错误的：common-crawl.json:3 在一个条目中提到了两个抓取批次（CC-MAIN-2026-39 和 CC-MAIN-2026-34），fineweb-2.json:3 也提到了多个分片
- 提出者：第二轮 CORRECTNESS-1（NIT，9/10）、第二轮 CORRECTNESS-2（NIT，9/10）、第二轮 GENERIC-1（NIT，9/10）、第二轮 GENERIC-2（NIT，9/10）、第二轮 STYLE（NIT，8/10）

#### F734 [NIT] T9 模型文件存在但不可读时不回退到通用模型

- 来源：第二轮（更新 07d2778..5881d31） · 共识：4/7 位 reviewer（4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`Engines.kt:139-145`、`Engines.kt:136-138`、`Engines.kt:124-125`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:139`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:138`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:142`

`ModelFile.get()` 只在 `FileNotFoundException` 时使用 `instead`（Engines.kt:138）；T9 文件存在但损坏（`IllegalArgumentException` 或其他 `IOException`，Engines.kt:139、:142）时返回 null，九键只用解码器、完全没有重排，尽管通用模型可以用。CORRECTNESS-1 指出 KDoc 的「has [instead]'s」只对缺文件成立。
建议：在这两个 catch 分支中调用 `onError` 之后也返回 `instead?.get()`。各阶段都认为 APK 签名和 SHA-256 校验使这种情况不太可能出现；四个阶段均为 NIT。

- 提出者：第二轮 CORRECTNESS-1（NIT，9/10）、第二轮 CORRECTNESS-2（NIT，9/10）、第二轮 GENERIC-1（NIT，8/10）、第二轮 GENERIC-2（NIT，9/10）

#### F735 [NIT] T9 回退到通用模型时没有任何日志或信号

- 来源：第二轮（更新 07d2778..5881d31） · 共识：3/7 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`Engines.kt:136-138`、`EngineDataPlugin.kt:211`、`EngineBridge.kt:69`）
- 当前状态（5881d31）：第二轮指出，与第一轮 F55 是同一问题
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:138`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:136`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:211`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineBridge.kt`

T9 文件缺失时 `ModelFile` 静默改用通用模型对（Engines.kt:136、:138），既不调用 `onError` 也不记日志。构建总会下载四个文件，所以 release APK 缺 T9 文件属于打包缺陷（CORRECTNESS-1 举 EngineDataPlugin.kt:211 所说的压缩资产让 `openFd` 抛 FileNotFoundException 为例），这时九键会悄悄按通用模型的 top-1 水平工作，bug 报告也看不出用的是哪一对。GENERIC-2 指出原来缺模型的路径同样静默，与现有风格一致。
建议：在宿主侧（EngineBridge）于 T9 回退时记一次 info 级日志（如 `Timber.i`），或提供一个不经 `onError` 的回调；CORRECTNESS-1 另提出可检查 APK 是否含全部四个模型资产。三个阶段均为 NIT。

- 提出者：第二轮 CORRECTNESS-1（NIT，8/10）、第二轮 GENERIC-1（NIT，7/10）、第二轮 GENERIC-2（NIT，8/10）

#### F736 [NIT] EngineDataPlugin 中 T9 模型注释语句不通

- 来源：第二轮（更新 07d2778..5881d31） · 共识：2/7 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:110`

EngineDataPlugin.kt:110 的注释「the nine keys' own pair, trained on from the two above」语句不通，两个阶段都认为本意是「fine-tuned from the two above」。
建议：改写这条注释。GENERIC-1、STYLE 均为 NIT。

- 提出者：第二轮 GENERIC-1（NIT，9/10）、第二轮 STYLE（NIT，8/10）

#### F737 [NIT] 缺文件分支的注释已过时

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:137-138`、`Engines.kt:164-165`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:137`

Engines.kt:137 的注释「pinyin reads by the decoder alone」现在只在 `instead` 为 null 时成立；对九键，缺文件意味着改用通用模型。
建议：改写注释，覆盖两种情况。STYLE 为 NIT。

- 提出者：第二轮 STYLE（NIT，9/10）

#### F738 [NIT] reranker 的 KDoc 仍写「over the one model」

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:830-834`、`Engines.kt:540`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:831`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:834`

`reranker(model)`（Engines.kt:831）现在可以作用于任一对的小模型，KDoc 却仍说「over the one model」。
建议：KDoc 改为指明 [model]，Engines.kt:834 的 `refiner` KDoc 也应提到它的参数。STYLE 为 NIT；GENERIC-2 在 D-05 的发现中也指出了这一句。

- 提出者：第二轮 STYLE（NIT，9/10）

#### F739 [NIT] MODELS 用嵌套 Pair 而非具名类型

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:105-115`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:105`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:69`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:196`

`MODELS`（EngineDataPlugin.kt:105）现在是 `name to (tag to sha)`，而同文件已用 `private class Source(name, sha256, files)`（:69）描述下载；:196 的 lambda 把这个同时装着 tag 和 SHA 的 Pair 叫作 `release`。
建议：改用 `private class Model(val release: String, val sha256: String)`，或按 tag 分组（`mapOf(tag to mapOf(name to sha))`），两者都能去掉各写两遍的发布 tag 字符串。STYLE 为 NIT。

- 提出者：第二轮 STYLE（NIT，9/10）

#### F740 [NIT] RELEASES 前缀与 LM_URL、WORDS_URL 写法不一致

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:102`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:102`；另见 `build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:89`、`build-logic/convention/src/main/kotlin/EngineDataPlugin.kt:94`

新增的 `RELEASES`（EngineDataPlugin.kt:102）提取了 `https://github.com/Sraw/youmo-ime/releases/download/` 前缀，但 `LM_URL`（:89）和 `WORDS_URL`（:94）仍写完整 URL，同一文件里出现两种写法。
建议：让这两个 URL 也由 `RELEASES` 拼出，或这里也保留完整的逐资产 URL。STYLE 为 NIT。

- 提出者：第二轮 STYLE（NIT，8/10）

#### F741 [NIT] SPELLED 新正则不再把下划线和带重音字母当词字符

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20`）
- 当前状态（5881d31）：第二轮新增
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20`

VoiceText.kt:20 的新 lookaround 只排除 `[A-Za-z0-9]`；原来的 `\b` 在所有平台上都把 `_` 当作词字符，在 ICU 和 Java ≤18 上还包括带重音的拉丁字母，所以 `x_A B` 现在会变成 `x_AB`，提交说明没有提到这一变化。
GENERIC-2 认为对语音输出影响可以忽略，建议改用 `[\p{Alnum}_]` 式的 lookaround，在修复「了」的同时保留旧的 ASCII 行为。GENERIC-2 为 NIT；CORRECTNESS-2 和 STYLE 在其他段落中也提到 `_` 现在算边界，但都认为无碍。

- 提出者：第二轮 GENERIC-2（NIT，8/10）

#### F742 [NIT] 存在 T9 文件时未检查双拼不读 T9 模型对

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103-112`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:103`

提交说「full pinyin and shuangpin never read them」，但有 T9 文件时只验证了 PINYIN（EnginesModelTest.kt:103）。双拼通过 `reranker()`/`refiner()` 的默认参数拿到通用模型，将来若传错 ModelFile 不会被发现。
建议：加一次 `pause(SHUANGPIN, …)`，做同样的已加载路径断言。TESTING 为 NIT；CORRECTNESS-2 在 D-01 的发现中也提到这一点。

- 提出者：第二轮 TESTING（NIT，8/10）

#### F743 [NIT] 成对的句子模型以四个零散字段传递（Data Clump）

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:158-165`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:164`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:540`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:496-499`

句子模型总是（小、大）一对出现，但代码用四个独立字段保存（Engines.kt:164 一带），在 Engines.kt:540 作为两个独立参数传入，并在 Engines.kt:496-499 用四次调用释放；再加一对就要改这三处，也没有什么能阻止 `reranker(t9SentenceModel), refiner(refiningModel)` 这样的错配。
建议：用一个小的 pair 类，带 `reranker()`、`refiner()`、`drop()` 和成对的 `instead`，让每个输入法只持有一个值；至少把 ModelFile 放进列表循环释放。STYLE 为 NIT。

- 提出者：第二轮 STYLE（NIT，8/10）

#### F744 [NIT] VoiceText 中字符类 [A-Za-z0-9] 写了两遍

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20`）
- 当前状态（5881d31）：第二轮新增
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20`

`SPELLED`（VoiceText.kt:20）两次写出 `[A-Za-z0-9]`，而同文件已把 CJK 字符类提成 `private const val CJK` 再插值。
建议：同样提取，如 `private const val ASCII_WORD = "A-Za-z0-9"`；STYLE 指出新字符类不像 `\b` 那样把 `_` 算作词字符，对识别器输出无害，但常量名能把这一差异写明。STYLE 为 NIT。

- 提出者：第二轮 STYLE（NIT，7/10）

#### F745 [NIT] SPELLED 修复只靠 JDK 17 的 CI 守护，ASCII 边界无测试

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:48-54`、`.github/workflows/unit_test.yml:52-55`）
- 当前状态（5881d31）：第二轮新增
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHoldTest.kt:50`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceText.kt:20`、`.github/workflows/unit_test.yml`

「订了 C E P」的唯一测试 VoiceHoldTest.kt:50 只在 Java ≤18 或 ICU 上会因 `\b` 失败；unit_test.yml 目前固定 Java 17，所以守护有效，但 CI 一旦改用 JDK 21，`\b` 回来也不会被发现（本地就曾如此）。新加的 `(?<![A-Za-z0-9])`/`(?![A-Za-z0-9])` 检查（让「C E Pa」「3C E」保持拼写形式）没有任何测试。
建议：新增一个小的 VoiceTextTest 覆盖 ASCII 相邻字符的用例，并在 CJK 用例旁注明它需要 JDK 17。TESTING 为 NIT。

- 提出者：第二轮 TESTING（NIT，7/10）

#### F746 [NIT] T9 模型文件损坏时的行为没有测试

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:139-146`、`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:155-169`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesModelTest.kt:155`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:142`

T9 文件存在但不可读时调用 `onError`，九键只用解码器，不回退到通用模型（Engines.kt:142）。TESTING 认为这可能是有意的（KDoc 只承诺缺文件时回退），但无论选哪种，现在只有通用模型对的这一行为有测试（EnginesModelTest.kt:155 `aSentenceModelThatCannotBeReadLeavesPinyinWorking`）。
建议：加一个 T9 用例把所选行为固定下来。GENERIC-2 在 D-01 的发现中也要求补这个 T9 用例；行为本身是否应改为回退见 D-14。TESTING 为 NIT。

- 提出者：第二轮 TESTING（NIT，7/10）

#### F747 [NIT] 首次切到九键时在 fcitx 线程上加载 T9 小模型

- 来源：第二轮（更新 07d2778..5881d31） · 共识：1/7 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:540`、`Engines.kt:131-132`、`EngineBridge.kt:59-71`）
- 当前状态（5881d31）：第二轮新增
- 与九键单独的模型对直接相关：若按 F730 的决定去掉这对模型，本条大概率随之不再适用，修复时需确认。
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:540`

改动前九键会话复用全拼已加载的小模型；现在创建九键会话时 `reranker(t9SentenceModel)`（Engines.kt:540）要复制并解析约 4.5 MB。每个进程只发生一次，代码库对首个拼音会话已接受同样的代价。
PERFORMANCE 认为除非低端手机上切换延迟明显，否则无需修改；NIT。

- 提出者：第二轮 PERFORMANCE（NIT，7/10）

## 5. PRE-EXISTING（reviewer 认为继承自上游 fcitx5-android）

第一轮的基线是空树，严格来说没有「改动之前就存在」的问题。下面这些条目，指出它们的 reviewer 全部标了 PRE-EXISTING：代码来自上游 fcitx5-android，youmo 没有改过，也没有因为 youmo 的改动而更容易触发。它们仍存在于当前代码中，级别只作标签，没有验证，也不建议优先修。第二轮没有 PRE-EXISTING 条目。

#### F717 [SHOULD FIX PRE-EXISTING] sendKeyToFcitxChar 把 jchar 当作 C 字符串读取

- 来源：第一轮（整仓 @07d2778） · 共识：3 位 reviewer（2 SHOULD FIX PRE-EXISTING, 1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:873`、`lib/fcitx5/.../fcitx-utils/key.h:177`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/native-lib.cpp:873`、`lib/fcitx5/.../fcitx-utils/key.h:177`）
- 位置：`app/src/main/cpp/native-lib.cpp:873`

reinterpret_cast<const char *>(&c) 把 2 字节的 jchar 当作 const char *；只要高字节非 0，字符串就没有 NUL 结尾，keySymFromString 会越界读取栈内存，非 ASCII 字符也不是合法的 UTF-8。
三票都标为 PRE-EXISTING（上游代码）；CORRECTNESS-2@S25 与 GENERIC-1@S25 指出树内目前没有调用 FcitxAPI.sendKey(Char, …) 的地方，CORRECTNESS-2@S25 因此评为 NIT，另两票评为 SHOULD FIX。
修复：先把字符编码为 UTF-8（如 fcitx::utf8::UCS4ToUTF8(c)），或删除这个入口。

- 验证说明（5881d31）：app/src/main/cpp/native-lib.cpp:873；keySymFromString 接受 `const std::string &`（lib/fcitx5/.../fcitx-utils/key.h:177），因此构造 std::string 时会对 2 字节的 `c` 调用 strlen。在 5881d31 中存在。上游：第 873 行来自 f193b282a（Rocka，2023-10-11），早于 19596419
- 提出者：CORRECTNESS-1@S25（SHOULD FIX PRE-EXISTING，8/10）、CORRECTNESS-2@S25（NIT PRE-EXISTING，8/10）、GENERIC-1@S25（SHOULD FIX PRE-EXISTING，8/10）

#### F718 [SHOULD FIX PRE-EXISTING] 路径在文件与目录间变化时 DataManager.sync 每次启动抛异常

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 SHOULD FIX PRE-EXISTING）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataHierarchy.kt:38`、`DataManager.kt:106-111`、`Fcitx.kt:435`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataHierarchy.kt:38`、`DataManager.kt:106-111`、`Fcitx.kt:435`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/data/DataHierarchy.kt:38`

`DataHierarchy.kt:38`：目录变成文件时，`UpdateFile` 以写方式打开一个目录（EISDIR）；文件变成目录时，目录没有对应的动作，子项的 `mkdirs` 在旧文件下失败。两种情况都会从 `nativeStartup` 中的 `DataManager.sync()` 抛出。
修复：类型变化时发出 `DeleteFile`/`DeleteDir`，并排在创建动作之前。

- 验证说明（5881d31）：app/src/main/java/org/fcitx/fcitx5/android/core/data/DataHierarchy.kt:38（目录→文件：`UpdateFile` 会在 DataManager.kt:106-111 处对已存在的目录调用 `outputStream()`。文件→目录：子项的 `CreateFile` 会失败，因为 `parentFile.mkdirs()` 无法在旧文件下创建目录。无论哪种情况，`sync()` 都会在 DataManager.kt:84 保存描述符之前从 Fcitx.kt:435 的 `nativeStartup` 中抛出异常，因此下次启动时会再次抛出。来源：第 38 行来自 youmo 的提交 fffa7952d，它重述了上游在 19596419 DataHierarchy.kt:94-96 中完全相同的规则。DataManager.kt:106-112 处的 `copyFile` 来自上游 1c39c2a2b。）
- 提出者：CORRECTNESS-1@S30（SHOULD FIX PRE-EXISTING，6/10）

#### F719 [NIT PRE-EXISTING] repositionCursor 缺少运行状态检查

- 来源：第一轮（整仓 @07d2778） · 共识：3 位 reviewer（3 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:912-914`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/native-lib.cpp:912-914`）
- 位置：`app/src/main/cpp/native-lib.cpp:912`

repositionCursor 没有其他 JNI 入口都有的 RETURN_IF_NOT_RUNNING，在启动前或 exit() 之后调用会解引用空的 p_frontend。三票均标为 PRE-EXISTING（上游代码）；GENERIC-1@S25 指出其唯一调用者（经 withFcitxContext 的 moveCursor）通常在 fcitx 运行时执行。
修复：加上同级函数使用的保护。

- 验证说明（5881d31）：app/src/main/cpp/native-lib.cpp:912-914 没有 RETURN_IF_NOT_RUNNING；:126 调用 `p_frontend->call<...>`；p_frontend 在 :460 处为 nullptr，在 :469 处 reset 之后也为 nullptr。上游：920ab7a77（Potato Hatsue 2022）、6a8b6f2b3 和 60754ad6f（Rocka 2021/2022）
- 提出者：CORRECTNESS-1@S25（NIT PRE-EXISTING，8/10）、CORRECTNESS-2@S25（NIT PRE-EXISTING，7/10）、GENERIC-1@S25（NIT PRE-EXISTING，6/10）

#### F720 [NIT PRE-EXISTING] 上游状态机遗留的格式问题（既有问题）

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachine.kt:44`、`ClipboardStateMachine.kt:56`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachine.kt:44`、`ClipboardStateMachine.kt:56`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachine.kt:43`

随上游迁入的 `ExpandButtonStateMachine`（ExpandButtonStateMachine.kt:43）中有 `initialState =  Hidden`（双空格）和多余空行，ClipboardStateMachine.kt 缺少末尾换行。
STYLE@G4 将其标为 PRE-EXISTING，认为本次评审中无需改动。

- 验证说明（5881d31）：lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/bar/ExpandButtonStateMachine.kt:44（在 5881d31 中存在：:44 处的 `initialState =  Hidden`、:14-15 处的空行以及 :52 处的末尾空行；ClipboardStateMachine.kt:56 以 `}` 结尾，没有末尾换行符。这些是上游 fcitx5-android 的代码行：git blame -C -M 将它们归属到 dface012/51eaa403（Potato Hatsue）和 faf44b61（Rocka），均早于 19596419。Youmo 的 75d22aa6 将这些文件原样从 app/ 移出，19596419 中的副本在这些位置逐字节相同。）
- 提出者：STYLE@G4（NIT PRE-EXISTING，9/10）

#### F721 [NIT PRE-EXISTING] 状态机详细日志打印无 toString() 的 Builder

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`EventStateMachine.kt:117-119`、`app/.../EventStateMachine.kt:126`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`EventStateMachine.kt:117-119`、`app/.../EventStateMachine.kt:126`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/utils/EventStateMachine.kt:117`

`filtered.joinToString()` 记录的是对象哈希而不是状态转换。该代码来自上游，此次迁移只是把详细日志开关换成了同样受控的 `CoreLog.v`。
评审未给出具体修复写法。

- 验证说明（5881d31）：EventStateMachine.kt:117-119：对 `TransitionEventBuilder.Builder`（:73，没有 toString 的内部类）使用 `${filtered.joinToString()}`。Blame：:117 来自上游 51eaa4032（19596419 的祖先）。:118-119 的日志字符串来自 youmo 的 75d22aa62/bd10cce34（Timber.d → CoreLog.v），而同样的 `${filtered.joinToString()}` 在上游 19596419 的 app/.../EventStateMachine.kt:126 中也存在
- 提出者：GENERIC-1@S8（NIT PRE-EXISTING，8/10）

#### F722 [NIT PRE-EXISTING] FormattedText.fromByteCursor 没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`FormattedText.kt:41-66`、`jni-utils.h:197`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`FormattedText.kt:41-66`、`jni-utils.h:197`）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/core/FormattedText.kt:41`

该函数把 fcitx 的 UTF-8 字节光标转换为 JNI 使用的 UTF-16 下标，现在位于纯 JVM 模块中，补测试成本很低（该发现标为 PRE-EXISTING）。
建议覆盖：ASCII、3 字节 CJK 字符、4 字节 emoji、位于字符中间的光标、越过末尾的光标。

- 验证说明（5881d31）：FormattedText.kt:41-66；在 5881d31 上执行 `git grep fromByteCursor` 只找到 jni-utils.h:197、定义本身和一条 detekt baseline 条目，没有测试。Blame：全部来自上游（60bda3460、45bdac146、0c427f451，均为 19596419 的祖先）；19596419 上同样没有测试
- 提出者：TESTING@G3（NIT PRE-EXISTING，8/10）

#### F723 [NIT PRE-EXISTING] 上游回调创建的 local ref 直到 JNI 调用返回才释放

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/cpp/native-lib.cpp:642`、`object-conversion.h:177-183`、`jni-utils.h:78-92`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/native-lib.cpp:642`、`object-conversion.h:177-183`、`jni-utils.h:78-92`）
- 位置：`app/src/main/cpp/native-lib.cpp:642`；另见 `app/src/main/cpp/native-lib.cpp:686`

candidateListCallback（line 642）、pagedCandidateCallback、keyEventCallback（line 686，五个装箱值）和 inputPanelCallback 从不删除自己创建的对象，local ref 会一直累积到外层 JNI 调用返回。这是 youmo 未改动的上游 fcitx5-android 代码，标为 PRE-EXISTING。
修复：用 JRef 包装。

- 验证说明（5881d31）：app/src/main/cpp/native-lib.cpp:642（candidateEntityToObject 返回一个裸的 NewObject 局部引用，object-conversion.h:177-183）和 :686-690（五个裸的 NewObject 引用）；两者都没有被包装或删除。JEnv（jni-utils.h:78-92）不压入局部帧（local frame）；这些回调在 loopOnce 的 JNI 调用内部运行（:1211-1212）。上游：87bde649e（Rocka 2026-05-30）、466fed587/214a50378（Rocka 2022）
- 提出者：PERFORMANCE@G6（NIT PRE-EXISTING，8/10）

#### F724 [NIT PRE-EXISTING] ConfigDescriptor 解析非数字 Int 值时抛异常而非返回错误

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/config/ConfigDescriptor.kt:214`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/utils/config/ConfigDescriptor.kt:214`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/config/ConfigDescriptor.kt:214`

`IntMin`、`IntMax` 和 Int 类型的 `DefaultValue` 用 `toInt()` 解析，非数字时 `NumberFormatException` 直接抛出 `parse`，而该函数本应通过 Either 返回 `ParseException`。审查者标为 PRE-EXISTING。
建议：改用 `toIntOrNull()` 并 `raise(ParseException.BadFormDesc(raw))`。

- 验证说明（5881d31）：app/src/main/java/org/fcitx/fcitx5/android/utils/config/ConfigDescriptor.kt:214、:216、:260（在 :233 处的 `either {}` 块内调用 String.toInt()；没有任何地方捕获 NumberFormatException，而 Arrow 的 either 只拦截 raise）。存在于 5881d31。根据 git blame，代码来自上游 fcitx5-android：d780a20ed（berberman，2021-11-28；第 213-216、257-264 行）和 47de31cd4（`either {`，第 233 行）都是分叉点 19596419 的祖先提交
- 提出者：GENERIC-1@S43（NIT PRE-EXISTING，8/10）

#### F725 [NIT PRE-EXISTING] 其他 Java→原生路径仍使用 modified UTF-8

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/cpp/object-conversion.h:77`、`jni-utils.h:26`、`native-lib.cpp:1092`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/cpp/object-conversion.h:77`、`jni-utils.h:26`、`native-lib.cpp:1092`）
- 位置：`app/src/main/cpp/object-conversion.h:77`

object-conversion.h:77 的 CString（GetStringUTFChars）仍用于 RawConfig 值、剪贴板和输入法名称，对 U+FFFF 以上的字符产生 modified UTF-8，而 utf8FromJString 已为引擎结果避开这一问题。该票标为 PRE-EXISTING。
修复：在这些地方复用 utf8FromJString。

- 验证说明（5881d31）：app/src/main/cpp/object-conversion.h:77 → CString 使用 GetStringUTFChars（jni-utils.h:26）；剪贴板（native-lib.cpp:1092）和输入法名称（:952、:971）走的也是同一路径。上游：a13e85420、9b9101a66、652d13d93（Rocka 2022-2024）
- 提出者：CORRECTNESS-1@S25（NIT PRE-EXISTING，7/10）

#### F726 [NIT PRE-EXISTING] 未处理按键的警告日志把按键字符写进 release 的 logcat

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:281`、`ForwardedKeys.kt:34-37`、`FcitxEvent.kt:109-115`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:281`、`ForwardedKeys.kt:34-37`、`FcitxEvent.kt:109-115`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:281`

`Timber.w` 的消息 `Unhandled Fcitx KeyEvent: $it` 打印整个 data class，包括 `unicode`，级别是 WARN；release 构建中 `ConciseTree` 会把 INFO 及以上写进 logcat，而没有 key code 的转发字符在每次抬键时都会走到这一行，密码框也一样。
修复：消息中去掉 `unicode`，或改用 debug 级别。

- 验证说明（5881d31）：app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:281（`ForwardedKeys.decide` 对没有键码的按键抬起事件返回 `Ignore`，见 ForwardedKeys.kt:34-37。`Data` 是一个包含 `unicode` 的 data class，见 FcitxEvent.kt:109-115。Release 构建会植入 `ConciseTree`，它会打印 WARN 及以上级别的日志，见 Timber.kt:19-24,30。该分支没有密码输入框检查。来源：第 281 行来自 youmo 的提交 c6082570c，沿用了上游在 19596419 FcitxInputMethodService.kt:301 处完全相同的 `Timber.w("Unhandled Fcitx KeyEvent: $it")`。`ConciseTree` 来自上游。）
- 提出者：GENERIC-1@S33（NIT PRE-EXISTING，7/10）

#### F727 [NIT PRE-EXISTING] 每次 onAttached 都新增 load-state 监听器且从不移除

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:268`、`ClipboardWindow.kt:267`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:268`、`ClipboardWindow.kt:267`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:268`

`adapter.addLoadStateListener` 在每次 attach 时都会运行，`onDetached` 却从不调用 `removeLoadStateListener`；目前调用方每次都新建窗口，所以每个实例只多出一个过期的监听器。
修复：在 `onDetached` 中移除监听器，以免将来复用实例时出错。

- 验证说明（5881d31）：app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:268（:280-286 处的 `onDetached` 中没有 `removeLoadStateListener`。来源：上游提交 01c09b8b2，存在于 19596419 ClipboardWindow.kt:267。）
- 提出者：CORRECTNESS-1@S36（NIT PRE-EXISTING，7/10）

#### F728 [NIT PRE-EXISTING] 依附于视图的弹出菜单在 service 作用域中启动

- 来源：第一轮（整仓 @07d2778） · 共识：1 位 reviewer（1 NIT PRE-EXISTING）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:169`、`ClipboardManager.kt:116`、`ClipboardWindow.kt:167-168`）
- 当前状态（5881d31）：仍存在（全面验证时已在 5881d31 上确认：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:169`、`ClipboardManager.kt:116`、`ClipboardWindow.kt:167-168`）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:169`

`promptDeleteAll` 在 `service.lifecycleScope` 上挂起查询 `ClipboardManager.haveUnpinned()`，然后显示锚定在 `ui.deleteAllButton` 上的 `PopupMenu`；若在此期间 InputView 被替换（旋转或换主题），弹窗会对一个已分离的锚点显示，很可能抛 `BadTokenException`。这个窗口只是一次 Room 查询，所以很少发生。
修复：改在 `imeScope` 上启动，数据库写入（delete、realDelete、undo）仍留在 service 作用域。

- 验证说明（5881d31）：app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardWindow.kt:169（`haveUnpinned()` 在 ClipboardManager.kt:116 处挂起。随后 :181-199 处运行 `PopupMenu(context, ui.deleteAllButton).show()`，没有检查视图是否仍处于 attached 状态。:284 处 `onDetached` 中的 `promptMenu?.dismiss()` 在这个菜单创建之前就已运行。来源：上游，提交 01c09b8b2、20775c9ef、409378e08 和 6d658241c，存在于 19596419 ClipboardWindow.kt:167-168。）
- 提出者：CORRECTNESS-2@S36（NIT PRE-EXISTING，6/10）

## 6. 撤回与编排者备注

### 6.1 撤回（Withdrawn）：17 条

下面的条目经 verifier 对照代码否定，已撤回，不建议按原论断修改。编号保持不变。

#### F33 [SHOULD FIX] 许可页的数据来源缺少 Common Crawl 和句子模型

- 来源：第一轮（整仓 @07d2778） · 共识：5/11 位 reviewer（1 SHOULD FIX, 4 NIT）· 置信度 7/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:81`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:24`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt:80`、`app/src/main/res/values/strings.xml:481`

README「Data and licences」说明语言模型混入了 Common Crawl n-gram，句子模型以 Apache-2.0 发布（CORRECTNESS-2、GENERIC-1 提到由 Qwen 蒸馏），但 LicensesFragment 的 ENGINE_DATA 只列出 libime 和 FineWeb-2；CORRECTNESS-1 指出 :24 的注释承诺列出词和模型的文本来源。
CORRECTNESS-2 未发现要求这些致谢的许可条款，但认为应用内列表应与 README 一致。
建议：增加 Common Crawl 条目（使用条款 URL）和句子模型条目（Apache-2.0、发布 URL）。CORRECTNESS-1 定为 SHOULD FIX，另两个阶段为 NIT。

- ❌ 验证说明：许可证页面确实列出了两者的署名。LicensesFragment.kt:33-50 列出了 AndroidAppConventionPlugin.kt:149 从 app/licenses 收集的 aboutlibraries 条目，其中包括 app/licenses/libraries/common-crawl.json:2 和 sentence-models.json:2-9（Apache-2.0，标签 "native"）。只有 LicensesFragment.kt:80-83 处仅含两项的 ENGINE_DATA 标题区遗漏了它们。
- 跨模块组合并：G8a-49、G9-22（引擎数据致谢只写 FineWeb-2，未提 Common Crawl）
- 提出者：CORRECTNESS-1@S40（SHOULD FIX，7/10）、CORRECTNESS-2@S40（NIT，8/10）、GENERIC-1@S40（NIT，7/10）、CORRECTNESS-1@S48（NIT，7/10）、GENERIC-1@S48（NIT，6/10）

#### F47 [SHOULD FIX] 共享打分器变化后其他会话复用过期的 beam

- 来源：第一轮（整仓 @07d2778） · 共识：4/12 位 reviewer（4 SHOULD FIX）· 置信度 8/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:245`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:517`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:519`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:635`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:430`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:445`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:515`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:105`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:190`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:518`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:288`

PinyinDecoder 复用上次 beam 前只检查 `graph.start`、`prev2`/`prev` 与 arc，依赖调用方在分数变化时调用 `reset()`（WordScorer KDoc）。Engines.kt:517-536 让 PINYIN、T9、SHUANGPIN 会话共享一个 `user()` 和 `prior()`，但每个会话各有解码器，`PinyinSession` 只在自身 learn、prior learn、block、forget 后重置自己的解码器，`Engines.learnSaved`（:635）不重置任何解码器；CORRECTNESS-2@S9 补充 `clear()`、`commitRaw()` 与不学习的提交也保留 `lastEnd`。
结果：用户切换键盘后，在相同上下文输入相同按键时，排序沿用学习前的分数（影响是排序过期，不会崩溃）。
建议：让 UserModel 与 LayerPrior 维护代数计数器，解码器比较后自行重置，或由 Engines 重置所有会话的解码器；最低限度在 `PinyinSession.clear()` 中调用 `decoder.reset()`。注：TESTING@G3 以 PinyinSession.kt:105 报告同一缺陷（含缺少测试），按头部路径规则归入 G3b。

- 可能的运行时影响：切换拼音类输入法后，候选排序不反映刚学到的词，用户看到过期的候选顺序。
- ❌ 验证说明：切换后唯一保留下来的 beam 是得分为 0 的起始状态。激活时发送 Reset（androidengine.cpp:284），停用时先发送 Enter 再发送 Reset（:395-401）。Reset 会清空输入（K/engine/session/PinyinSession.kt:159），因此切换后的第一次解码只有一个键长（K/engine/pinyin/SyllableGraph.kt:46）。随后 `until = minOf(last, graph.end)`（K/engine/lattice/PinyinDecoder.kt:289）使 from ≤ 1，而 :247-248 用当前的 scorer 重新计算位置 0 之后的所有 beam
- 子论断 F47.a 被否定：「共享同一 scorer 的另一个会话学习之后，保留的 beam 仍会被复用，因为 PinyinSession 只重置自己的解码器，而 `Engines.learnSaved` 一个也不重置，从而导致排序过时。」→ K/engine/lattice/PinyinDecoder.kt:246-248、:289；androidengine.cpp:284（另一个会话学习之后，没有任何已评分的 beam 被复用）
- 子论断 F47.b 被否定：「在一个布局中提交后，另一个布局中的下一次解码会复用以旧计数和旧先验评分的前缀 beam，因为每个会话只重置自己的解码器，而 `clear()` 和不学习的提交会让 `lastEnd` 保持已设置状态。」→ 所谓“在另一个布局中的下一次解码”就是那次单键解码：K/engine/lattice/PinyinDecoder.kt:247 会清除从 `from` ≤ graph.end = 1 开始的 beam
- 子论断 F47.c 被否定：「休眠会话的解码器保留 `lastEnd`，因此在另一个会话学习之后，以相同上下文输入相同的键，会显示在学习之前评分的 beam。」→ lastEnd 确实被保留（K/engine/session/PinyinSession.kt:515 处的 clear() 不会重置它），但重新输入是从空输入开始逐键进行的，每次解码只保留在学习之后计算的 beam（PinyinDecoder.kt:289、:247）
- 子论断 F47.d 被否定：「PINYIN、SHUANGPIN 和 T9 共享一个 UserModel 和 LayerPrior，但每个解码器只在自己的学习、遗忘或先验变更之后才重置，因此在切换输入法后，另一个会话会复用以旧计数评分的 beam，且没有测试覆盖这一点。」→ 共享的 UserModel 和 LayerPrior（K/engine/host/Engines.kt:521、:529、:534）以及只重置自身的操作（PinyinSession.kt:352、:408、:430、:445）与所述一致，也确实没有测试覆盖这一点，但由此推不出切换后会复用旧计数 beam 的说法（androidengine.cpp:284；PinyinDecoder.kt:247-248、:289）
- 跨模块组合并：G3a-14、G3b-77（一个拼音会话学习后，其他会话保留的束仍用旧计数打分）
- 提出者：CORRECTNESS-1@S9（SHOULD FIX，6/10）、CORRECTNESS-2@S9（SHOULD FIX，7/10）、GENERIC-1@S9（SHOULD FIX，6/10）、TESTING@G3（SHOULD FIX，8/10）

#### F117 [SHOULD FIX] 旧版备份识别同时接受上游 Fcitx5 的备份

- 来源：第一轮（整仓 @07d2778） · 共识：3/9 位 reviewer（1 SHOULD FIX, 2 NIT）· 置信度 5/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:25`；另见 `app/src/test/java/org/fcitx/fcitx5/android/data/UserDataOriginTest.kt:24`

`UserDataOrigin` 用 `LEGACY_APPLICATION_ID_ROOT`（`org.fcitx.fcitx5.android`）识别本分支改名前的备份，但这也是上游 fcitx5-android 的包名。上游导出的备份因此会被当作 Legacy 接受：其偏好设置被改名，`databases/` 和 `external/` 被复制进本 app，而规格只承诺迁移本分支改名前的版本。
CORRECTNESS-2@S30 指出较新的上游 `clbdb` 会触发 `fallbackToDestructiveMigrationOnDowngrade`，清掉本想恢复的剪贴板历史；CORRECTNESS-2@S50 指出上游的偏好值和剪贴板数据库版本可能不兼容（未验证）。
修复：若有意接受，就在文档或导入确认文案中说明；否则用 `versionName` 或在导出元数据中加分支标记来区分，或在旧版导入时给出警告，并增加上游来源备份的测试用例。

- 可能的运行时影响：导入上游 Fcitx5 的备份时，它会被当作本应用旧版数据复制进来，可能因数据库降级迁移清空剪贴板历史，或带入不兼容的设置。
- ❌ 验证说明：代码层面的说法属实：app/src/main/java/org/fcitx/fcitx5/android/data/UserDataOrigin.kt:24-25 接受导出方 `org.fcitx.fcitx5.android`，因此上游的备份会作为 Legacy 通过检查。但所述的危害没有依据。app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/db/ClipboardDatabase.kt:13 为 `version = 4`，自上游 652d13d9（2024-04-08）以来未变，因此上游的 clbdb 并不构成降级。无法解码的偏好设置值会回退为默认值（app/src/main/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreference.kt:120-123）。libime 的配置和数据按设计会被迁移（app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:13-17）。
- 提出者：CORRECTNESS-2@S30（NIT，7/10）、GENERIC-1@S30（NIT，7/10）、CORRECTNESS-2@S50（SHOULD FIX，5/10）

#### F173 [SHOULD FIX] 迁移不创建 conf/ 父目录，码表设置无法迁移

- 来源：第一轮（整仓 @07d2778） · 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:66`；另见 `app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:34`、`app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:42`

`EngineMigration.replace()`（:66-73）打开 `conf/androidengine.conf.new` 之前不创建父目录。只改过码表设置（`table/wbx.conf` 等）、从未保存过 addon 配置的 libime 用户没有 `conf/` 目录，`FileOutputStream` 抛出 `FileNotFoundException`，被 :42 的 catch 吞掉记成警告，每次启动都重复失败，五笔、仓颉、自然码的设置永远不会迁移过来。CORRECTNESS-1@S29 说明自己没有确认 fcitx 在这种情况下是否确实不建 `conf/`，app 里也没有代码创建它。
修复：在 `replace` 中调用 `parentFile?.mkdirs()`（与 `Engines.built`、`CustomPhraseManager.write` 一致），并在 EngineMigrationTest 中加一个只有 `table/wbx.conf`、没有 `conf/` 目录的用例。

- 可能的运行时影响：从 libime 版本升级、只改过码表设置的用户，其五笔、仓颉等码表设置不会被迁移过来。
- ❌ 验证说明：凡是在设置应用中改过码表设置的用户都已经有 `conf/`。`AndroidKeyboardEngine::save`（app/src/main/cpp/androidkeyboard/androidkeyboard.cpp:203）在每次 fcitx 保存时都会用 safeSaveAsIni 写入 `conf/androidkeyboard.conf`，而 safeSaveAsIni 会创建该目录。保存操作在设置界面 onStop 时执行（app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:151），也会在每次导出前执行（app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/AdvancedSettingsFragment.kt:123）。即使 `conf/` 不存在，保存之后的第一次启动也会重试 app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:33-34 并成功。因此这些设置并非“从不”被迁移，警告也不会在“每次启动”时重复出现。
- 子论断 F173.a 被否定：「`replace()` 打开 `conf/androidengine.conf.new` 时没有创建 `conf/`，因此只使用码表的 libime 用户的五笔/仓颉/自然码设置永远不会被迁移，并且警告会在每次启动时重复出现。」→ 证据相同：任何一次保存（app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainActivity.kt:151）都会由 app/src/main/cpp/androidkeyboard/androidkeyboard.cpp:203 创建 `conf/`，并且 app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:33-34 会在下一次启动时成功。
- 子论断 F173.b 被否定：「`replace()` 不创建父目录，因此在 `conf/` 从未被创建时，写入 `conf/androidengine.conf` 会抛出 `FileNotFoundException`，码表设置永远不会被迁移。」→ FileNotFoundException 这一部分属实（app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:69 没有调用 mkdirs）。“永远不会被迁移”不成立：app/src/main/cpp/androidkeyboard/androidkeyboard.cpp:203 会在第一次 fcitx 保存时创建 `conf/`，下一次运行 app/src/main/java/org/fcitx/fcitx5/android/core/EngineMigration.kt:33-34 时即完成迁移。
- 提出者：CORRECTNESS-1@S29（SHOULD FIX，6/10）、GENERIC-1@S29（SHOULD FIX，7/10）

#### F182 [SHOULD FIX] clear()未重置unrefined，之后精排可能在graph!!处崩溃

- 来源：第一轮（整仓 @07d2778） · 共识：2/9 位 reviewer（1 SHOULD FIX, 1 NIT）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:515`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:458`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:558`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:661`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:688`、`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/lattice/PinyinDecoder.kt:115`

`leave()`（:458-461）中若首候选只读了输入的开头，`pick(0)` 调用 `read()` 可能设置 `unrefined`（:558）；`commitRaw()` 调用的 `clear()` 把 `graph` 置空却不清 `unrefined`，返回的快照 `refines = true`（:688），用户停顿时宿主发送 `Action.Refine`，`place(graph!!, …)`（:661）在 fcitx 线程抛出 NullPointerException。
只有当解码器给不出覆盖整个输入的读法时才会发生；CORRECTNESS-1 认为因 raw 弧（PinyinDecoder.kt:115-117）目前不会发生并评为 NIT，GENERIC-1 认为罕见并评为 SHOULD FIX。
建议：在 `clear()` 中加 `unrefined = null`，或让 `refine()` 以 `val g = graph ?: return snapshot()` 开头。

- 可能的运行时影响：在罕见情况下用户停顿触发精排时，输入法会在 fcitx 线程因 NullPointerException 崩溃。
- ❌ 验证说明：该崩溃不会发生。refineLater 只在面板上存在 EngineCandidateList 时才发送 Refine（app/src/main/cpp/androidengine/androidengine.cpp:365）。clear() 之后唯一会让 `unrefined` 保持已设置状态的路径是 leave()，位于 lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/session/PinyinSession.kt:459-463，而它的快照中没有候选词，因此不会设置候选列表（androidengine.cpp:386）。onEvent 抛出的任何异常也会被 engineFailed 清除（app/src/main/cpp/native-lib.cpp:558-560）。
- 提出者：CORRECTNESS-1@S11（NIT，7/10）、GENERIC-1@S11（SHOULD FIX，6/10）

#### F213 [SHOULD FIX] WordPack.validName 没有测试

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 8/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/user/WordPackTest.kt:42`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManager.kt:84`

`WordPack.validName` 是 `PinyinDictManager.importPack`（PinyinDictManager.kt:84）用用户提供的包名拼文件路径之前唯一的检查，CORRECTNESS-1@S20 称仓库中没有测试调用它；`validLayer` 拒绝 `=` 的规则也未测试，而 LayerPrior 的 `name=value` 格式依赖它。
建议补充 `../x`、`a/b`、`.x`、空名、65 个字符和一个合法名的用例。TESTING@G4 在总评中另称 `:app` 的 `PinyinDictManagerTest` 已测「../x」路径穿越用例（未作为发现提出）。

- ❌ 验证说明：app/src/test/java/org/fcitx/fcitx5/android/data/pinyin/PinyinDictManagerTest.kt:57 断言 importPack("../x", pack) 会失败，而只有 validName 会拒绝这个名字（PinyinDictManager.kt:84、WordPack.kt:77-78）；关于 '=' 的那一半成立，因为 WordPackTest.kt:52-55 只测试了空格、空字符串、逗号和 65 个字符
- 提出者：CORRECTNESS-1@S20（SHOULD FIX，8/10）

#### F240 [SHOULD FIX] 空格长按说话可在上一次识别未完成时开启第二个会话

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceFeature.kt:28`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:176`

inputView.hold 在 ended() 之前一直存在，但 hold() 和 CommonKeyActionListener.kt:176 都不检查它；在「识别中…」阶段（最长 15 s）再次长按空格会叠加第二个浮层和第二个 VoiceListener。每个 listener 在各自的单线程调度器上解码，较短的第二段话可能先于第一段提交，顺序颠倒。VoiceWindow.press 已有对应保护（if (session?.over == false) return）。
修复：在这里加同样的保护 if (inputView.hold != null) return。

- 可能的运行时影响：两段语音的识别结果可能以颠倒的顺序写入。
- ❌ 验证说明：一张与键盘同样大小、可点击的卡片（app/src/voice/java/org/fcitx/fcitx5/android/input/voice/VoiceHoldOverlay.kt:104、:115）位于键盘上方的 popup.root 上（app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:290）。它从 begin() 开始一直保留，直到 end() 关闭监听器为止（VoiceHoldSession.kt:123-125），因此在第一个会话仍可能提交内容时，第二次长按空格无法到达该按键。
- 提出者：GENERIC-1@S28（SHOULD FIX，7/10）

#### F250 [SHOULD FIX] 未检查 NO_POSITION 就用适配器位置索引列表

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:53`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:66`、`app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:70`

DynamicListTouchCallback 的 getSwipeDirs（:53）、onMove（:66）和 onSwiped（:70）直接用 bindingAdapterPosition 索引 entries；滑动删除或 deleteSelected 后，正在播放移除动画的行该值为 -1，触摸这一行会抛 IndexOutOfBoundsException。
建议：位置为 RecyclerView.NO_POSITION 时分别返回 0、返回 false 或不做处理。

- 可能的运行时影响：列表项删除动画期间触摸该行会使设置界面崩溃。
- ❌ 验证说明：app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListTouchCallback.kt:53/66/70 处的索引没有 NO_POSITION 防护，但在移除动画期间的普通触摸不会走到这里。这一点依赖 AndroidX 框架的行为（假设；不在仓库中）：ItemTouchHelper 用 RecyclerView.findChildViewUnder 选取被触摸的行，该方法会跳过正在以动画移出的行；并且它只在确认位置不是 NO_POSITION 之后才调用 onSwiped。长按拖动路径（app/src/main/java/org/fcitx/fcitx5/android/ui/common/DynamicListAdapter.kt:83、:114）需要在默认 120 ms 的移除动画期间达到长按超时，而该行被分离时会取消这次触摸。
- 提出者：CORRECTNESS-2@S40（SHOULD FIX，7/10）

#### F264 [SHOULD FIX] `toneless` 在 NFD 之后才替换 ü，ü 被转成 u

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`lib/ime-eval/tools/make-new-words-set.py:150`

make-new-words-set.py:150 的 `toneless` 先做 NFD，此时 ü 已拆成 u + U+0308，随后的替换不起作用，lǜ/nǚ 变成 lu/nu（Python 验证 `toneless("lǜ") == "lu"`）。
已提交的集合中是 `jiaolv`/`ernv`，说明来源本就写 v，或文件并非由这段代码生成；若在带声调的 rime_wanxiang 读音上重新运行，会无报错地写出错误输入（焦虑 → jiaolu）。
修复：在去除组合符号之前使用 `unicodedata.normalize("NFD", s).replace("u\u0308", "v")`。

- ❌ 验证说明：lib/ime-eval/tools/make-new-words-set.py:150 处的字面量是分解形式的 `u` + U+0308（字节为 `u 314 210`），而不是 U+00FC。它与 NFD 输出匹配（ǜ → u U+0308 U+0300），因此 lǜ 会变成 lv，而不是 lu。
- 提出者：CORRECTNESS-1@S24（SHOULD FIX，6/10）

#### F265 [SHOULD FIX] 剪贴板监听器集合在后台线程迭代时被其他线程修改

- 来源：第一轮（整仓 @07d2778） · 共识：1/12 位 reviewer（1 SHOULD FIX）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（第一轮）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:97`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:426`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:538`、`app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:550`

`ClipboardManager.updateLastEntry`（:97）在 `Dispatchers.Default` 的 `launch` 中迭代监听器集合（底层是 WeakHashMap），而 KawaiiBarComponent（:426）和 Fcitx.kt（:538、:550）在其他线程上添加或移除监听器；该协程没有异常处理器，一旦抛出 `ConcurrentModificationException` 就会让进程崩溃。这是上游代码。
修复：用锁保护集合并迭代它的副本，或在 Main 线程上调用监听器。

- 可能的运行时影响：复制内容时若恰好有监听器被添加或移除，输入法进程可能崩溃。
- ❌ 验证说明：在剪贴板路径上，app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:97 处的 forEach 运行在一个 try 块内，该 try 块对应的 `catch (exception: Exception)`（位于 app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:201）会捕获 ConcurrentModificationException。只有在 app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:203 重试期间发生的第二次该异常才会逃逸。因此在一次复制期间添加或移除一个监听器只会被记入日志，不会导致崩溃。
- 提出者：GENERIC-1@S30（SHOULD FIX，6/10）

#### F534 [NIT] batches.py 的 min() 处理 NaN 与 minOf 不一致

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`lexicon/tools/batches.py:82`

Python 的 `min(nan, x)` 返回 nan，而 `min(x, nan)` 返回 x；当只有右侧熵为 NaN 时，batches.py:82 显示左值，而 Main.kt 的 `passes()` 把该熵视为 NaN。
建议：任一值为 NaN 时打印 `'-'`，否则取较小值。

- ❌ 验证说明：NewWords.kt:177-178,215,233（每个被采样的出现都会同时添加一个 LEFT 邻居和一个 RIGHT 邻居，因此两侧的总数相等，`entropy()` 要么两侧都是 NaN，要么两侧都不是。未保留的行在两侧都写入 NaN。batches.py:82 读取的 `words` 输出从不会出现单侧 NaN，因此依赖顺序的 `min()` 与 `minOf` 的结果从不会出现分歧。）
- 提出者：CORRECTNESS-2@S4（NIT，8/10）

#### F540 [NIT] CommonCrawl 的 GZIPInputStream 从不关闭

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:88`；另见 `lib/ime-dict-tool/src/main/kotlin/org/fcitx/fcitx5/android/dicttool/CommonCrawl.kt:50`、`lexicon/tools/latin.py:53`

`pages()`（CommonCrawl.kt:88）与 `wet.paths` 的读取（:50）都不关闭 GZIPInputStream，Inflater 的原生 zlib 状态要等 GC 的 Cleaner 运行才释放，每次运行约 3000 个 WET 文件各一次。
建议：两处都包在 `use {}` 中；该发现还指出 lexicon/tools/latin.py:53 也在 `with` 之外打开文件。

- ❌ 验证说明：CommonCrawl.kt:50-51（`wet.paths` 的 reader 会被关闭：Kotlin 的 `Reader.readLines()` 经由 `forEachLine`/`useLines`，它们把 reader 包在 `use{}` 中并关闭 `GZIPInputStream`）。另外两部分成立：`pages()` 在 CommonCrawl.kt:88 处从不关闭其流，lexicon/tools/latin.py:53 在 `with` 之外使用了裸 `open()`。
- 提出者：PERFORMANCE@G2（NIT，8/10）

#### F653 [NIT] BaseInputView 承载了子类并不共用的状态

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:97`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt:170`

候选操作菜单、触摸吞噬（:170）、语音 `hold`、`longPressPointer` 和 `downYs` 都放在 CandidatesView 也继承的基类中，而 CandidatesView 从不使用 hold 和 pointer 状态；`hold` 和 `longPressPointer` 还是由其他类设置的公开可变字段。
修复：把菜单移进独立的组件，把 hold 和 pointer 跟踪移到 InputView。

- ❌ 验证说明：CandidatesView 使用了候选操作菜单。app/src/main/java/org/fcitx/fcitx5/android/input/CandidatesView.kt:103 传入了 `onCandidateAction = { ... -> showCandidateActionMenu(...) }`，因此该菜单（BaseInputView.kt:97）以及吞掉触摸并关闭菜单的逻辑（BaseInputView.kt:170-183）同样服务于 CandidatesView。只有 `hold`、`longPressPointer` 和 `downY`（BaseInputView.kt:155-163）仅由 InputView 使用。它们是公开的 var，由 VoiceHoldOverlay.kt:124 和 CustomGestureView.kt:182-183 设置。
- 提出者：STYLE@G7（NIT，7/10）

#### F668 [NIT] 手写 SentencePiece 读取器的输出没有任何校验

- 来源：第一轮（整仓 @07d2778） · 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`build-logic/convention/src/main/kotlin/VoiceDataPlugin.kt:145`

如果生成的 `bpe.vocab` 有误，hotword 会静默编码为空且不报错，只有 CI 不运行的 `VoiceEngineTest` 能发现。
建议：比写测试更便宜的防护是在 vocab 行数与 `tokens.txt` 不一致时让 `copyVoiceModels` 失败；TESTING@G1 注明这假设两个文件列出相同的 piece，未经核实。

- ❌ 验证说明：app/src/androidTestVoice/java/org/fcitx/fcitx5/android/input/voice/VoiceEngineTest.kt:40-50（`theUsersWordsAreListenedFor` 使用打包的资源运行并断言 `with > without`，如果 "早 上" 经 bpe_encoder_ 编码后为空，该断言就会失败；:52-84 处的屏蔽测试同样经过该编码器）；sherpa-onnx 还会为每个无法编码的 token 记录日志（sherpa-onnx/csrc/utils.cc:65-69、offline-recognizer-transducer-impl.h:322-325）
- 提出者：TESTING@G1（NIT，6/10）

#### F698 [NIT] 用户词以 modified UTF-8 跨越 sherpa-onnx JNI

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`lib/sherpa-onnx/src/main/cpp/sherpa-onnx/jni/offline-recognizer.cc:622`；另见 `app/src/main/cpp/native-lib.cpp:505`

GetStringUTFChars 返回 modified UTF-8；native-lib.cpp:505-507 已说明这会破坏 U+FFFF 以上的字符并在应用侧绕开，而这里含此类字符的屏蔽词会以代理字节到达 EncodeHotwords 并被排除。BPE 词表大概没有 CJK Ext-B token，所以可能无害，但基于 GetStringRegion 的辅助函数能与应用自身的修复保持一致。

- ❌ 验证说明：VoiceHotwords.spelled 对任何包含代理项（surrogate）的词都返回 null（lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/input/voice/VoiceHotwords.kt:43）。ofUser 只用经 spelled 处理后的词构建 `blocked`（:64、:70），而 VoiceEngine.kt:89 是 createStream(hotwords, blocked) 在生产代码中唯一的调用方。超出 U+FFFF 的词永远不会到达 jni/offline-recognizer.cc:622 处的 GetStringUTFChars
- 提出者：STYLE@G6（NIT，6/10）

#### F705 [NIT] onFinishInput 的 KDoc 承诺了实际不会发生的回调

- 来源：第一轮（整仓 @07d2778） · 共识：1/9 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/input/broadcast/InputBroadcastReceiver.kt:23`；另见 `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:949`

KDoc 说离开字段时会回调，但它只由 `InputView.finishInput`（来自 `onFinishInputView`）和 `onDetachedFromWindow` 调用，`FcitxInputMethodService.onFinishInput`（:949）从不转发；VoiceWindow 仍然正确，因为它在 `onStartInput` 里也会丢弃。
修复：修改文档，或在服务的 `onFinishInput` 中转发这个调用。

- ❌ 验证说明：离开输入框时该回调确实会触发。app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:932-936 处的 `onFinishInputView(finishingInput)` 调用 `inputView?.finishInput()`，后者会广播 `onFinishInput`（InputView.kt:357-359）。当编辑目标改变时，平台会在 `onFinishInput` 之前调用 `onFinishInputView(true)`，因此即使服务的 `onFinishInput`（FcitxInputMethodService.kt:949-955）没有转发它，InputBroadcastReceiver.kt:23 处的 KDoc 依然成立。
- 提出者：CORRECTNESS-2@S34（NIT，6/10）

#### F716 [NIT] 文件/content VIEW 过滤器带有 BROWSABLE 类别

- 来源：第一轮（整仓 @07d2778） · 共识：1/5 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❌ 已否定（理由见下方「验证说明」）
- 当前状态（5881d31）：已撤回（全面验证时被否定）
- 位置：`app/src/main/AndroidManifest.xml:79`

AndroidManifest.xml:79 处 file/content 的 VIEW intent filter 带有 BROWSABLE，使网页可以用任意 `content://` 或 `file://` URI 打开 MainActivity 并弹出“导入此词库？”提示，用户仍需确认两次。
文件管理器和下载界面不需要 BROWSABLE。修复：删除该 category。

- ❌ 验证说明：该过滤器不会接收任意 content:// 或 file:// URI：AndroidManifest.xml:90-105 将其限制为以 .dict/.scel/.txt 结尾的路径（pathPattern/pathSuffix）。BROWSABLE 位于 :80，导入提示在 MainActivity.kt:92-101 处显示。浏览器是否会转发由网页发起的 file/content intent 属于外部行为，无法检查
- 提出者：GENERIC-1@S46（NIT，6/10）

### 6.2 编排者备注（verifier 的备注，未作为发现处理）

**全面验证 A1**（覆盖 F11, F23, F43 等 20 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 部分。我把其中的 README（规范依据）块和文件头作为项目目标。
- 此列表中没有任何条目标记为 PRE-EXISTING。根据 `git blame`，有两个条目引用的行是上游代码：F227 和 F405/F441（Rocka，2024-2025）。按照上下文中针对整个仓库的规则，我没有以它们早于此次变更为由将其驳回（refute）。
- 部分判定在一定程度上依赖于仓库之外的第三方行为：
  - F227：Gradle 中 `providers.exec` 的默认值 `ignoreExitValue=false`。
  - F405：JDK 的 `File.createTempFile` 权限（模式 0666 减去 umask）以及 Kotlin `Base64.Default` 的严格程度。
  - F128：R8 在 Gradle JVM 内运行（AGP）。
  - F253：ART JIT 和后台 dexopt。
  - 在上述每种情况下，代码层面的部分均已验证。
- F405：该失败并非完全静默。ProjectExtensions.kt:90 会打印异常消息，但构建仍会成功。
- F43.b：gradle/libs.versions.toml 声明了约 70 个库和插件条目。解析后的传递依赖集合更大。无论按哪种口径，它们都没有用校验和固定。
- 观察到的情况，不作为发现：gradle.properties:12 写着堆内存 "was raised from 2g"。d546ba0a 实际上是把它从 3g 降到了 1536m，因此该注释已过时。
- 观察到的情况，不作为发现：unit_test.yml:82 写着 voice flavour 自身的代码 "is tested by the voice tests in ime-core"。app/src/voice 的源码并不在 ime-core 中。
- 我运行了一次只读的 `git ls-remote --tags origin`，以确认远程仓库上存在这些 data 标签。该命令不在允许的 git 子命令范围内。没有修改任何内容。

**全面验证 A2**（覆盖 F446, F447, F526 等 15 条）

- 验证者备注
- F668：CI 不运行 voice 插桩测试（emulator_test.yml:65,81 只构建并测试 text flavor），因此损坏的 bpe.vocab 只有在开发者运行 connectedVoiceDebugAndroidTest 时才会被发现。部分错误但仍能编码 早上 的 vocab 会通过测试。ssentencepiece 由 CMake 获取（cmake/simple-sentencepiece.cmake），无法读取。
- F671：`validatePlugins` 是否发出警告取决于 Gradle 更严格的校验。build-logic/convention/build.gradle.kts 没有配置 `validatePlugins`，且无法获取 Gradle 的源码，因此这部分按原说法采信。DataDescriptorTask（DataDescriptorPlugin.kt:72）和 CMakeBuildInstallTask（NativeBuildTasks.kt:50）同样缺少该注解。
- F673：在执行阶段访问 Task.project 已被弃用，这属于 Gradle 方面的事实，无法在此检查。BuildMetadataTask 也没有声明任何输入，只有 `@OutputFile`（:62-63）。
- F603：预构建资源任务传入的 buildTarget 为空（FcitxComponentPlugin.kt:60），因此它只运行 `cmake --install`（NativeBuildTasks.kt:77）。其他 8 个任务两个命令都会运行。
- F530：README 把 `signKeyFile` 放在 `~/.gradle/gradle.properties` 中，因此 keystore 路径可能在仓库之外。test-server.p12 是在 7c436cb3 中添加的（"Ask the user's own server while they pause, in a cloud build"）。
- F529：fork 的 token 无法推送到上游的 cachix 缓存，这是 Cachix 权限方面的事实；只对照代码检查了硬编码的名称。
- F669："recompile" 指重新生成引擎数据（CompileEngineData），ToolFingerprint.kt:15 称这需要几分钟。它不是指 Kotlin 编译。

**全面验证 A3**（覆盖 F77, F101, F130 等 20 条）

- 验证者备注
- 没有运行任何代码。我没有重新计算 F77 的“9995 个中有 3744 个”这一数字；F77 仅依据 IEEE-754 推理来判断（n=2 时恰好为 0，n≥3 时会留下残差）。在生产环境中，pack 的已知列表包含许多计数各不相同的基础层词语，因此要让所有计数都相等，需要极小的输入。
- F101：CommonCrawl.kt:129-130 确实会运行，经由本地分支；从 :131 起的 HTTP 路径不会运行。
- F192：多计的问题发生在 PinyinDictReader.kt:70（所引用的 :65 是 add 的签名）。unknownSyllables 的 KDoc（"how many readings it made us skip"）也与之不一致。
- F193：未知标志不会让运行成功。运行随后会因缺少输入文件（exit 1）或在命令的输入数量检查处（exit 2）失败，并给出误导性的消息。属于 FLAGS 但该命令未使用的标志（例如 pinyin --lexicon）会被忽略，且没有任何提示消息。
- F194：用于构建发布版 new.words 的 pack --lexicon 只读取 f[0] 和 f[1]。第 2-7 列发生错位会改变 pack.words 以及由其构建的整理（curation）批次，而不会直接改变 new.words。
- F275 依赖于 JDK HttpClient 的行为，其源码不在仓库中。
- F367.b：所引用的 latin.py:53 是读取计数的位置；跳过注释的 reject 读取位于 :49-50。
- 在本次运行早期，我违反 shell 规则，对仓库使用了几个只读的非 git shell 命令（grep、wc、head、ls）。没有修改任何内容。

**全面验证 A4**（覆盖 F407, F408, F448 等 20 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 部分。我把其中的 README 部分（规范依据）作为项目目标。
- F407、F533 和 F540 依赖于 Kotlin 标准库和 JDK 的行为（在非 `String` 接收者上调用 `CharSequence.indexOf`、`String.toInt(radix)` 接受正负号、`Reader.readLines()` 会关闭 reader）。仓库中没有这些实现的源码。我根据已知的标准库/JDK 实现进行判断，这是一个假设。
- 在仔细阅读工具规则之前，我运行过一次只读的 `ls`/`find`（不是 git 命令），在 ~/.gradle/caches 中查找 kotlin-stdlib 的源码，没有找到。其他所有 shell 调用都是 `git -C ... grep/ls-tree/ls-files`。
- F456：该条目在 Main.kt:604 处引用的调用链现在位于 :605。Main.kt:604 处是它的注释。

**全面验证 A5**（覆盖 F541, F607, F608 等 12 条）

- 验证者备注
- F541：latin_counts.py:41 使用 `Pool(6)`，从不调用 `os.cpu_count()`。
- F607：该词是 U+795E U+7957（神祗），不是 祇（U+7947）。`PinyinDictReader.kt:60` 以权重 0f 加入列出的每一个读音，`EngineDataPlugin.kt:181` 把 readings.tsv 传给 app 构建。
- F608：`WebTextTest.kt:111` 以生产环境的默认长度 4（`Main.kt:376`、`NewWords.kt:273`）运行 `words` 命令。它的分片中没有长于 3 个字符的汉字串，所以长度为 4 的各轮会运行，但不产生任何被检查的候选。`NewWordsTest.kt:64` 构造了一个长度为 4 的 finder，但一轮也没有执行。
- F609：没有测试以 `cc` 或 `text` 调用 `runCli`。`CommonCrawlTest` 直接调用 `CommonCrawl.extract`，从而跳过了 `Crawl.parse`（`Main.kt:155-161`）。
- F610："one reader thread"（只有一个读取线程）这一说法不准确，因为 `WebText.kt:120` 设置了 `threads = 2`。结论仍然成立：`WebText.kt:113` 说明插入顺序是 DuckDB 的默认行为，所以去掉这条 SET 不会改变任何东西。一次 COPY 写出一个 row group 属于 DuckDB 的行为，我无法在仓库中核实。
- F674：latin_counts.py:28-34 对页面中每一个不同的拼写各把该页面计入一次，latin.py:57 再按小写键对这些计数求和。
- F675：engine-data.sh:98（以及 :153）把 `cc/clean/*.parquet` 传给 `mix`，`mix` 调用 `WebText.read` 时使用其默认值 `chatOnly = true`（`WebText.kt:55`）。`Main.kt:46`（USAGE）也写明只取类聊天的页面。
- F676：lib/ime-dict-tool/build.gradle.kts:21-24 没有应用 Kover 插件。上传的产物（unit_test.yml:123-130）中没有 dict-tool 的报告。`EngineDataPlugin.kt:166,217` 把该工具的输出放进 app 的 assets。
- F677：WebText.kt:79 写入的是 `date VARCHAR`，WebText.kt:52,59 记载的是 ISO 格式 `2023-06-14T...`。测试断言的却是 TIMESTAMP 转换后的形式 `2023-06-14 00:00:00`（WebTextTest.kt:85）。
- F678：每个位置的装箱查找最多是 4 次，而不是 5 次：三次 `short[k]` 查找（n = 2..4，Examples.kt:47-49）加上一次 `long[k]`（:52）。
- F679：一次 ARPA 写出对每个 unigram 或 bigram 行调用 `number()` 两次，对每个 trigram 行调用一次（ArpaModel.kt:84,88-90,95）。"2 or 3" 这个次数只有跨多次运行计算时才成立：`mix` 步骤（engine-data.sh:98），再加上每个 WEIGHTS 值各一次（:153），而 WEIGHTS 默认为空。
- F680：`when` 用线性的 String 扫描检查 `in SIMPLIFIED/TRADITIONAL/PARTICLES`（WebText.kt:40-44）。`words` 在 `maxLength + 2 = 6` 轮中的每一轮都重新读取每一个分片（`NewWords.kt:84`、`Main.kt:378-389`）。

**全面验证 A6**（覆盖 F78, F181, F232 等 20 条）

- 验证者备注
- F78：`compileEngineData` 把 `misreadings.tsv` 声明为 `@InputFile`（EngineDataPlugin.kt:182, :342），并对其运行 `Misreadings.parse`（Main.kt:699），所以格式错误的行也会让本地 app 构建失败。该测试的其他检查（未知音节、重复词）只在测试中运行：PinyinDictReader 遇到未知音节时会跳过，而不是失败（PinyinDictReader.kt:20）。
- F181：装箱确实存在。仓库中没有任何测量说明 README 中的 "2 minutes, 5 GB"（README.md:201）有多少是它造成的。对 480 万个 bigram 索引装箱（Quantizer.kt:74）大约耗费 100 MB，而工具的堆是 4 GB（EngineDataPlugin.kt:356），所以内存占比看起来很小。时间占比未经核实。
- F232：段表（section table）本身没有校验和。类文档（DataFile.kt:32-33）说丢失的段会 "by the reader asking for it"（由请求该段的读取方）发现，这对 `has()` 不成立。被翻转的 id 如果与已有的 id 冲突，会以 "listed twice" 被拒绝。
- F308：`git grep -P` 确认 Misreadings.kt:54 处的字面量是 `u` + U+0308，预组合形式没有出现。关于 F308.b，"quietly" 只在运行时成立：以 NFC 重新保存后，MisreadingsTest.kt:38 会失败。
- F373.b："Every score NaN"（每个得分都是 NaN）言过其实。只有该层中被赋予 NaN 的词以及经过这些词的路径得分为 NaN（LayerPrior.kt:51），除非该层是 `base`。`parse` 只被 lib/ime-eval 使用（PinyinRun.kt:49）。接受 NaN/Infinity 这一点依赖 Kotlin 标准库的 `toFloatOrNull`，它不在仓库中。
- F425：DataFormatException 会在重建路径上从 `built()` 中抛出（Engines.kt:819）。随后 `addedTable` 把它当作 RuntimeException 捕获，并报告 "cannot read input method"（Engines.kt:775），所以这是一个被报告的加载失败，而不是崩溃。导入时也检查不出这个问题，因为 `TableText.check` 读取表，但从不构建它（TableText.kt:140-143）。
- F612：编码中的换行符会多出一行。空格只会在同一行内把编码和文字分开（CodeTableReader.kt:93）。
- F613：第 285 行是把这三个计数相互比较，而不是与某个固定的数字比较。
- F683：大端缓冲区总是无法通过宽度检查，因为按大端读取的宽度 1..32 至少是 2^24，此时会抛出 IllegalArgumentException。所有主代码中的调用方都经由 `DataFile.section()`，它返回的是小端视图（DataFile.kt:84）。
- F289.d：晚风 和 电报码 不在随附的表中（EngineDataPlugin.kt:83 列出的是 cj、erbi、wbpy、wbx、zrm）。`git ls-tree 5881d31 -- dev` 的结果为空。
- F682：这假设 Kotlin 编译器不会把 `indices.all` 编译（lower）为基于索引的循环。编译器不在仓库中。
- 过程：早期有一次只读的 `grep` 是通过 shell（而不是 git）运行的，之后我改用了 grep 工具。没有修改任何东西。

**全面验证 A7**（覆盖 F684, F685, F721 等 20 条）

- 验证者备注
- 简短的文件名代表 lib/ime-core/src/{main,test}/kotlin/org/fcitx/fcitx5/android/ 下的路径，ImportedTablesTest 除外（位于 app/src/test）。
- F64、F135：没有针对性的测试能触达这些防护。带种子的模糊测试（LibimeFilesTest.kt:244-252）只可能偶然触达 `rule()` 或 IOOBE 的 catch：被变异的计数或 trie 头会使后续的读取发生偏移。它对这些防护没有做任何断言。我无法运行它来排除这种可能。
- F64.c 引用了 LibimeFilesTest.kt:217，那是 `keysSharingATailFail`，与该论断无关。
- F135.b：我无法解压 db.main.dict。"Node arrays past 4096 bytes"（节点数组超过 4096 字节）依据的是 LibimeTrie.kt:108-120 的布局和 6694 个尾部键。
- F136：去掉 TableSession.kt:259 的 `recent.clear()` 后，TableSessionTest.kt:394 仍会因为另一个原因通过。第一次输入 你好 之前 `recent` 为空，第二次之后没有提交任何单字。排序（Ranking）的去重只能解释另外两种情况。
- F66：关闭学习时重排器（reranker）的上下文有断言（PinyinSessionTest.kt:322-330）。精修器（refiner）读取的是同一个 `textBefore()`（PinyinSession.kt:633,658），所以精修器这边的缺口是缺少断言，而不是缺少行为。
- F376：:96 处的注释与 "zhongg" 本身相符（zhong 以 zh 开头）。"A different case/input was meant"（本意是另一个用例/输入）是对意图的解读；经过核实的是那个重复的断言。
- F685：没有随附的数据就无法核实 "about 2k"（约 2k）条弧。PinyinDecoder.kt:376-377 的代码注释写的是 "thousands"。
- F721：在 5881d31（以及 07d2778）中，该日志行位于 :119，比所引用的 :117 靠下两行。

**全面验证 A8**（覆盖 F380, F381, F382 等 20 条）

- 验证者备注
- F412：在 5881d31 中，所引用的行都比条目所说的位置靠下一行：文档在 :23-24，`[lock]` 在 :26，`if (last) continue` 在 :120。该文件自 07d2778 起没有变化。
- F547：只有 PinyinSessionTest.kt:569 是无界循环。PinyinSessionRefineTest.kt:101 位于一个由 `assertTrue(slices <= SLICES + 1)` 限定的 refine 循环内，而且该循环中没有选择 拟 的操作。PinyinSessionHabitTest.kt:49 和 :169 是单次 Select 调用，出问题时会失败，而不会挂起。
- F548：`StrokeLookup.learning` 的 getter 返回 `pinyin.learning`（StrokeLookup.kt:39-43），所以该断言目前确实观察到了 pinyin 的值。只有让两个访问器都拥有 backing field 的回归才会不被发现。
- F549：StrokesTest.kt:51 处的 `find("hzs") == listOf("㐄")` 隐式地检查了 𠀀 被排除。
- F553：页大小为 2 或以上的、能感知分页的 fake 同样不会翻页，Select(1) 仍会得到 窝。只有页大小为 1 时这些断言才会失败。无论哪种情况，NextPage 都没有被执行到。
- F554：`apply()` 和 `unpacked()` 中不包含 `!!`。按表示形式分支的三个方法（`dot`、`times`、`row`）都包含。
- F383：TableSessionTest 的 `type` 辅助函数（:55）返回 `Pair<String, Snapshot>`，所以它是另一个辅助函数。TableUserTest.kt:41 和 SharedWordsTest.kt:42 完全相同。
- F413：`logSum` 按节点缓存（`summed` 标志），所以对每个被取分母的节点，该数组只分配一次。

**全面验证 A9**（覆盖 F614, F615, F616 等 13 条）

- 验证者备注
- A9 中没有任何条目是 PRE-EXISTING、带有子论断、带有 P 编号或带有 Runtime-impact 论断。
- F614：所指的函数是 LatinWords.kt 中的 `forEachAt`；`lettersOfWords` 是它在 PinyinSegmenter.kt 中的调用方。
- F617：libime 的源码不在仓库中，只有预构建的 .dict 数据。"libime 两者都取" 这一说法依据的是代码自身的注释（PhraseRules.kt:48），该注释说 libime 会把重复的部分取两次。只有含有 a-rule 且其 p_k 与 n_1 部分重叠的表才受影响，并且只影响恰好 k 个字的词组。
- F618：只有当用户模型中至少有一条 2 个字符以上的已列出文本或自有文本时，这一轮才会在第一个键时运行（SharedWords.kt:50-52）。该注释（446370ec5，2026-09-30）早于 CodedWords（a97a3ac9，2026-10-06）。
- F621：测试失败时只有 `reopened` 会泄漏；`store` 已经在断言之前于 :94 关闭。
- F622：TableUser.leadsAnywhere 并不是每个键都运行。只有当 `table.hasMatch` 为 false 时才会走到它（TableSession.kt:282），并且 `any` 在第一个命中处就停止。`saved()` 和 `seen()` 确实在每个键上扫描全部内容（TableSession.kt:299, :320）。与装箱无关的另一点是，`key(...)` 不是 inline 的，所以每个条目都会分配一个 lambda（:330）。
- F688：每个会话保留的 Scorer 按设计为每个输入保存可变状态（"each keeps what it ran for its input"，Engines.kt:831）。只有 charReadings 和 extensions 是纯只读的派生缓存。

**全面验证 A10**（覆盖 F16, F51, F67 等 20 条）

- 验证者备注
- F16：commitTyped 会调用 pairs.type，但如果提交到达时 EditingSession.composing 非空，就会在读取之前于 AutoPairs.kt:41 返回。这里把该论断理解为涵盖普通光标位置的提交，其中包括每一个虚拟键字母或符号的提交。
- F302.c：上游 fcitx5 默认会把密码字段切换到普通键盘布局（instance.cpp:1745-1747），所以密码字段中通常不会记录 preedit。其他敏感字段没有这样的排除。
- F213：validLayer 中对 '=' 的拒绝既没有在 WordPackTest 中测试，也没有在 dict-tool 的 MainTest `--layer` 用例中测试。
- F386：后半段的退格使会话的预测停留在 1，而编辑器的光标在 0。测试中没有任何内容检查这一点。

**全面验证 A11**（覆盖 F427, F467, F468 等 20 条）

- 核验者备注
- 上下文文件没有 `## Stated intent` 一节。我改用其中的 README（spec of record，即权威规格）一节作为项目目标。
- F467：所引用的字面量只编码了 UNSEEN（20）。PRIOR（2）由 UserModelTest.kt:115-116 处的 `2f * alone` 和 `/ 3f` 编码，而该条目没有引用这两处。`0.5 / 21` 中的 `0.5` 是显式传入的 `weight = 0.5f` 参数，而不是常量。
- F427.b：调用 id() 时，如果传入的 Entry 已在 `ids` 中，会提前返回且不更新 `entries`。因此存储的读音（reading）是最近一次新增的读音，而不严格是最后一次 id() 调用的读音。
- F476：按 Kotlin 的运算符优先级（&& 先于 ||），该条件按预期求值。这条发现只涉及可读性。
- F468：这依赖 JDK 的标准行为：对目录使用 FileOutputStream 会抛出 FileNotFoundException，而 File.delete 会删除空目录。

**全面验证 A12**（覆盖 F627, F628, F629 等 20 条）

- 核验者备注
- F629：双重否定的行位于 ChineseNumbers.kt:130、132、141 和 151，各比所引用的 129/131/140/150 低一行；ChineseNumbers.kt 在 07d2778 与 5881d31 之间没有变化。没有哪个函数同时接受全部五个参数；这组成团参数（clump）的子集在 decide、single、ten、digitsAsDigits 和 roughOrMinutes 中反复出现。
- F720：双空格位于第 44 行，而不是第 43 行。ClipboardStateMachine.kt 在最后的 `}` 之前还有一个空行（第 55 行）。
- F628：“从不运行”（never run）被理解为“没有任何测试执行到它”。KeyHabitsTest.kt:73-80 只执行到 `over == 1`。Restore（KeyHabits.kt:66）从不裁剪，因此恢复后超出上限的日志在下一次 learn 时确实会进入 `over > 1` 分支。
- F630：`% capacity` 只在 add（AudioHistory.kt:22）中会抛出异常。当 capacity 为 0 时，before() 在执行到 `%` 之前就返回空数组。
- F38.d：MainTest.kt:38 运行 `lm m c` 只是为了检查它会作为用法错误被拒绝。lm 函数（Main.kt:300）从未在测试中运行。
- F138：runCli 和 main（Main.kt:52-54）不捕获任何异常，因此缺少 BASE/<name>.tsv 时，score 会留下一个未捕获的 FileNotFoundException。随后 release-report.sh:48 以退出码 2 退出，并且不向 report.txt 写入任何行。
- F166.b：只有当新单元格是数字时，才能“无错误”地读取它。release-report.sh:41 处的 number() 遇到其他任何内容都会以退出码 2 退出。
- F215：在 C++ 配置中（app/src/main/cpp/androidengine/androidengine.h:41），应用对错字纠正（typos）和相邻键（neighbour keys）的默认值同样为 true，而 Engines.kt:530 设置了 neighbours = s.typos。
- 工具：除 git 外，我还使用了只读的 shell 命令（grep、sed、od，以及检查 import 顺序的简短 python 脚本）来读取文件。我没有运行任何构建或测试。

**全面验证 A13**（覆盖 F477, F478, F479 等 20 条）

- 核验者备注
- F483：ZERO 位于 TableRun.kt:61。第 :65 行是 `private var nanos`。这些文件在 07d2778 与 5881d31 之间没有变化，因此这是引用笔误。
- F482：工厂调用位于 Learning.kt:43-44，而不是 :42-43。从 Kotlin 的角度看，这是名称复用，而不是编译器意义上的遮蔽（shadowing）。
- F563：learn 在 Learning.kt:28 处的 `plain` 会话同样保持 prediction 开启。PredictLearning.kt:31 已经传入 `prediction = false`，这就是修复的模式。
- F633：缓存还会使 commitsOnTo 的 `timed()` 调用不再计入 TableRun 的 nanos、actions 和 slowest 计数器，从而改变报告的延迟。
- F631：依据 POSIX 和 BSD BRE 规则判断。没有运行 BSD sed。
- 上下文文件没有 `## Stated intent` 一节。项目目标采用了 README（spec of record）。这些条目都不依赖于它。

**全面验证 A14**（覆盖 F634, F692, F53 等 20 条）

- 核验者备注
- 上下文文件没有 `## Stated intent` 一节，因此我用其中的 README（spec of record）作为项目目标。
- F139：所引用的 utils.cc:139 是 EncodeHotwords 中的 `} else {`。空指针解引用位于第 152、169 和 185 行。
- F168：recognize() 有四条 return 语句（VoiceEngine.kt:139、141、142、143），而不是五条。
- F220/F242：只有当用户有热词（hotwords）时，才会重建这个含 78k 个短语的图（VoiceEngine.kt:90）。否则 createStream() 共享 hotwords_graph_（impl.h:215），只剩下等待这一项。
- F241：在 APK 构建中，find_path/find_library 回退路径取决于 NDK 工具链的 find-root 模式，而该设置不在仓库中。无论哪种情况，SHERPA_ONNXRUNTIME_* 环境变量这条途径都适用。
- F327.b：输入法页面显示的是 R.string.im_latin_words（InputMethodSettings.kt:127、:200-203），而不是 fcitx 的描述，因此用户在那里看不到未翻译的 msgid。
- F391.b：据我回忆（在此无法核实），CMake 会以 "requires a positive integer" 拒绝 `-j 0`。如果是这样，构建会报错退出，而不是让 Ninja 不限任务数运行。
- F428：剩余的行（rows % 4）使用 dot()，即单个累加器（matrix-kernel.cpp:56-60），这同样与 JVM 的八路（eight lanes）不同。
- F692：某个读错的样本是否计为未命中（miss），取决于解码器如何处理错误的输入（例如把 不了 读作 `bule`）；我没有运行评估。

**全面验证 A15**（覆盖 F492, F493, F494 等 20 条）

- 核验者备注
- F492：这七个代码块位于 VoiceListener.kt:105、127、159、168、178、306 和 316。:327 处的 catch 不会重新抛出 CancellationException。
- F494：这是名称冲突，而不是严格意义上的遮蔽。Kotlin 仍会把不带限定的 `stream(...)` 调用解析为该函数。第 143 行在作用域中存在局部 val `result` 的情况下不带限定地调用 `result(...)`，因此那里的 `VoiceEngine.` 限定符是可选的。
- F495：这两个 150 ms 常量名称不同，驱动的动画也不同：ANIMATE_MS 是麦克风按下时的缩放（VoiceWindow.kt:291），APPEAR_MS 是卡片的淡入淡出（VoiceHoldOverlay.kt:122）。两个显示已识别文本（heard-text）的视图都使用 maxLines=2 和 ellipsize START，但 gravity 和文字尺寸设置不同。
- F496：双空行位于 VoiceHoldSession.kt:44-45。
- F497：第 720 行来自 youmo 的提交 841d04df，而不是来自内置（vendored）的 v1.13.8（db227873）。
- F571：唯一的调用方 VoiceHotwords.spelled（lib/ime-core/.../VoiceHotwords.kt:33,42）传入 2-12 个汉字，因此目前不会有 ':'、'#' 或 '/' 到达这个解析器。仅由一个 ':' 片段构成的短语会被跳过（utils.cc:138）。数字形式的 ':5' 或 '#5' 会被静默地当作分数或阈值。非数字的情况会由 std::stof 抛出异常（utils.cc:53/57），并经由 SafeJNI（offline-recognizer.cc:614）以 RuntimeException 的形式到达 Kotlin。
- F573：调用位置已移到 matrix-kernel.cpp:94（引用为 78）。
- F636：上游 fcitx5-chinese-addons 自身的顶层使用 `find_package(OpenCC 1.0.1 REQUIRED)`（子模块 CMakeLists.txt:41）。chttrans 链接 OpenCC::OpenCC（modules/chttrans/CMakeLists.txt:10），因此缺少预构建产物时，会在之后的生成（generate）阶段表现为缺少目标（missing-target）的错误。
- F637：该作用域的处理程序显示 Failure.NoMicrophone（VoiceListener.kt:67），与已处理路径在 :255 处显示的字符串相同，因此用户看到的消息并不更差。Vad 有终结器（kotlin-api/Vad.kt:52）。AudioRecord 构造函数是否会抛出 SecurityException 属于 Android 框架行为，在此无法核实。
- F694：以下是我对 CMake 的理解，未对照其源码核实：cmake_parse_arguments 会让未使用的多值关键字保持未定义，而对未定义变量执行 list(GET) 会把输出设为 NOTFOUND 且不报错。只有已定义但为空的列表才会使其失败。如果这一点成立，那么“配置失败”这一半很可能不成立，而“空参数被丢弃”这一半符合 CMake 对不带引号参数的规则。该文件来自上游（e8717b6c，Rocka，2025-07-12）。
- F695：SHERPA_ONNX_EXIT 调用的是 `_Exit(code)`（macros.h:58），而不是 exit()。newFromAsset 没有 SafeJNI 包装（offline-recognizer.cc:469-506），因此损坏模型引发的 C++ 异常同样不会变成 IllegalArgumentException。

**全面验证 A16**（覆盖 F697, F698, F717 等 20 条）

- 核验者备注
- 上下文文件：它没有 `## Stated intent` 一节，因此将其中的 README 文本用作规格。
- F717：当低字节为 0 时（例如 U+4E00），第一个字节就是 NUL，因此字符串为空，不会发生越界读取（F717.b 中“高字节非零”（high byte non-zero）的说法略显宽泛）。sendKey(Char) 没有生产代码调用方；只有 FcitxTest.kt:132/361 和 EngineEvalRunner.kt:102 调用它，且传入的是 ASCII 字符。
- F25.d：应用还注册了 `keyboard-us`（androidkeyboard.cpp:167），因此“只有 engine-* id”（only engine-* ids）的说法不严谨。F25.e：SoftKeyboardTest.kt:128-150 也会驱动引擎 addon，并以同样的方式失败。
- F123、F268：在 5881d31 处，pinyin/wbx 名称未注册（F25），因此没有任何上屏提交（commit）到达引擎。在这些名称修复之前，设备端学习以及 CommitStringEvent 丢失这两种影响都处于潜伏状态。setEnabledIme(["pinyin"]) 目前保存的是一个空列表。
- F146：不改变选区的按键（修饰键、每一次按键释放、执行编辑器动作的 Enter）不会产生光标报告。“每个按键”（Every key）只对会输入字符或移动光标的按键成立。getTextBeforeCursor 会阻塞在 IPC 上，这属于 Android 框架行为；代码自身的注释（:851-852）称这次读取为一次往返（round trip）。
- F172.b：第二个实例的 startupFcitx 会提前返回（native-lib.cpp:573-575）。随后它的 dispatcher 会从第二个线程在同一个 uv loop 上运行 loopOnce，而且它可能永远收不到 ReadyEvent，因此 EngineEvalRunner 更可能在 :49 处超时，而不是继续运行。
- F247：Engines.close()（Engines.kt:879-888）不会丢弃 ModelFile 句子模型，因此调用它并不会释放这些模型。在 5881d31 处共有四个模型文件（Engines.kt:918-923），而不是两个。
- F248：这些词典被放入两个列表，一个用于基础层，一个用于新词层，而不是一个列表。

**全面验证 A17**（覆盖 F270, F301, F328 等 20 条）

- 验证者备注
- F301：关于 LocaleManager.getApplicationLocales 是 binder（IPC）调用的说法，依据的是 AOSP 框架源码，而该源码不在仓库中。仓库只能表明该调用在每次 onStartInputView 时都会执行。
- F328：debug 构建会添加后缀 ".debug"（AndroidAppConventionPlugin.kt:50），因此其 action 为 io.github.sraw.youmo.debug.action...
- F393.b：`level == 1` 这一谓词是从上游复制来的（19596419 处的 Fcitx.kt:71）。上游有一个生产代码中的调用方（19596419 处的 AddonListFragment.kt:37），youmo 将其删除了。
- F416：clipboardItemTimeout 是一个隐藏的偏好设置项（AppPrefs.kt:412），因此 -1 只可能来自旧版本的设置或导入的备份。
- F417：要发生死锁，挂起必须在等待测试调度器（例如，排队在构建 FakeFcitxConnection 所用的 TestScope 上的任务）。runBlocking 内的普通 delay 会在 runBlocking 自己的事件循环上恢复。
- F429：凭据加密（CE）存储在手机解锁前无法打开，这是平台行为。仓库在直接启动（direct-boot）模式下切换到设备保护存储（FcitxApplication.kt:77-82），并在解锁时退出（FcitxApplication.kt:49-58），这些都与该行为一致；该退出还会丢弃内存中已学习到的所有内容。
- F432：org.mechdancer:dependency 0.1.2 的源码不在仓库中。以类型为键的唯一性是根据名称 UniqueComponentWrapper 以及测试中的显式向上转型推断出来的。
- F434：无法从仓库判断 Robolectric 是否会在测试之间删除第一个 application 的临时目录。
- F435：第 52 行使用的是可见的转义 `\uFEFF`，因此所引用的位置 53 并不存在不可见字符的问题。
- F498：ensureConnected（FcitxDaemon.kt:51）同样在未持锁的情况下读取 `clients`。
- F500：该函数会抛出异常，而不是返回一个失败的 Result。它还把 provider 提供的显示名称（PinyinDictionaryFragment.kt:208,229）用作 cacheDir 中的路径，而 readWords（PinyinDictManager.kt:116）明确不信任该名称。
- F502：两个阈值被设为相等（KawaiiBarComponent.kt:286-287），因此名称互换在运行时不会造成任何变化。
- F503：固定（pin）图标的两个使用方在加载图标后都会应用各自的着色（ClipboardEntryUi.kt:47；Menu.kt:25-28），因此我没有发现共享状态带来的任何可见影响。这种共享行为来自框架的 Drawable.mutate() 约定。

**全面验证 A18**（覆盖 F504, F505, F506 等 20 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 部分。我使用其 README（作为权威规格）来确定项目目标。
- F585：我统计的是每次服务启动两次同步，而不是三次。onCreate 会先调用 `pickedLocalesChanged()`（FcitxInputMethodService.kt:219），因此 :715 处的同步只会在语言真正发生变化后执行。只有当某次服务启动正是启动 fcitx 的那次时，Fcitx.kt:467 才会在该次启动时额外增加一次同步。关于 system_server 会把 subtype 写入磁盘的说法，依赖于不在仓库中的 AOSP 代码，因此我无法核实。
- F511：AppPrefs 中每一个 `hidden { f(` 包装都使用 `) }` 这种收尾写法（:62、:65、:70、:83、:99、:139、:144、:368、:439）。因此该文件混用了两种风格；以这种方式收尾的并不只有 switch 块。
- F510 和 F577：代码注释将这两种行为都描述为有意为之（ClearURLs.kt:51、ManagedPreferenceUi.kt:29-31,55）。这些说法仍与代码相符。
- F577 引用的行是 :55；该语句本身位于 :56。

**全面验证 A19**（覆盖 F586, F587, F588 等 20 条）

- 验证者备注
- F641：我记得 AOSP 的 `Uri.Builder` 会把空的编码部分存为 `Part.EMPTY`，并且 `HierarchicalUri` 对空部分会跳过 `?` 和 `#`。如果这是对的，就不会输出悬空的 `?` 或 `#`，但我无法对照仓库中的代码进行核实。
- F646：最新优先的排序以及迁移后主题的保存都是上游代码（e5def047，Rocka 2023）。Youmo 的 fe9e6305 添加了重新定位（relocation）的触发条件。
- F647：KawaiiBarComponent.kt:129 是上游代码（856570f4，Rocka 2024）。F648：GridPagingCandidateViewAdapter.kt:36 是上游代码（15975f9d，Rocka 2023）。
- F650：关于测试数量的“团队规则”在 youmo 仓库中没有任何书面记载；对 *.md 文件运行 `git grep` 没有找到。
- 除只读的 git 命令外，我还通过 shell 运行了 `cat`、`ls` 和 `grep`。它们都没有修改任何内容。

**全面验证 A20**（覆盖 F700, F701, F702 等 12 条）

- 验证者备注
- F705：该反驳依赖于文档中记载的 `InputMethodService` 约定（`onFinishInputView` 在 "prior to switching to another target for editing"（切换到另一个编辑目标之前）运行，且 `finishingInput` 意味着随后会调用 `onFinishInput`）。此处无法读取框架源码。仓库自身在 InputView.kt:356 的 KDoc 也假定了同样的约定。
- F707：JNI 读取和解析只在标点功能启用时运行（PunctuationComponent.kt:38）。否则每个 StatusAreaEvent 只会设置一个空映射。
- F706：相对路径读取位于第 66 行。第 65 行是 `loadRules` 的函数头。
- F704：一旦选区折叠，上下文会在下一次光标报告时重新告知（`ResetIfNotEmpty` 会引出 `tellTextBeforeCursor`，FcitxInputMethodService.kt:818-825）。在选区上直接输入时，仍会在没有该上下文的情况下组字。`Action.Reset` 在 PinyinSession.kt:159-162 处丢弃上下文。
- F703：`syncWith` 在 `postFcitxJob` 内、于 `fcitx.lifecycleScope` 上运行，后者即 `Dispatchers.Default`（FcitxLifecycle.kt:29）。它通过 `InputMethodNames.of` 和 `context::getString` 读取 `getResources()`。
- F727：KawaiiBarComponent.kt:301 和 TextEditingWindow.kt:96 每次 attach 都会创建一个新的 `ClipboardWindow`，因此每个实例只运行一次 `onAttached`，目前监听器不会累积。
- F701：TableManager.kt:89-92 处的拒绝是有意为之（注释 "another input method's"，youmo 提交 c6c54546d）。:86 处的命名来自上游（7b3abe168）。

**全面验证 A21**（覆盖 F33, F92, F109 等 20 条）

- 验证者备注
- F33：Common Crawl 条目为 `"licenses": []`，因此其摘要为空白，点击也没有任何反应（showLicenseDialog，LicensesFragment.kt:56-58）。其使用条款链接无法从该页面打开。strings.xml:481 将语言模型的来源仅标注为 FineWeb-2，而 EngineDataPlugin.kt:47 混入了一份 Common Crawl 抓取数据。
- F190：仓库内的代码显示，holder 以高度 0 创建，并在尺寸确定前的一次测量过程中被绑定（第 74 行）。一次测量过程会创建多少个 holder，取决于 RecyclerView 的填充循环以及 ConstraintLayout 的测量规格（measure spec）。这两者都是此处无法读取的 AndroidX 代码。
- F400：唯一的实例是在代码中以 null attrs 构建的（PreferenceScreenFactory.kt:201-202，该处显式设置了 summary provider），因此错误的 styleable 目前在运行时没有影响。该代码来自上游（Rocka，65ed2da3d）。
- F174：被阻塞的调用只是在 Dispatchers.Default 上读取一个缓存字段（FcitxLifecycle.kt:29）。InputView.kt:239 也使用了同样的 runImmediately 模式。
- F109：该条目给出的位置 KeyboardWindow.kt:132 与 locale 映射无关。

**全面验证 A22**（覆盖 F514, F515, F516 等 20 条）

- 验证者备注
- 这里没有任何条目被标记为 PRE-EXISTING。根据 `git blame`，其中几条是上游代码（19596419 之前的提交），它们作为整个仓库变更的一部分来评判：F514（dedfc1820）、F590（c47e41654、ac5e97bc0）、F596（7363ca812、6a8135bef）、F597（f5e80d3a5、7de87f2ad）、F657（runCmd c0acd028a/adbe322e3；AboutFragment:42 e2bf8dc23；:43 是 youmo 的 2aac261f9）。
- 行号有偏移但缺陷本身不变：KeyCaps 的 `contentDescription` 位于 :54（引用为 :57）；PopupOverridesFragment 中的嵌套 Pair 位于 :155-158（引用为 :154）；PinyinDictionaryFragment:64 是 `enum class Layer`，而不是 Pair；嵌套的 Pair 位于 :183。
- F657："N/A" 分支需要 `providers.exec` 返回一个非零退出结果。如果在未设置 `isIgnoreExitValue` 时 Gradle 会在非零退出时抛出异常（此处无法获取 Gradle 源码），那么没有 git 的构建会改为在配置阶段失败。
- F708：停留在按键边界加 touchSlop 范围内的滑动在 Up 时不会被消费，随后 KeyGestureRecognizer.kt:202-206 会执行一次点击。这种情况下字母会被输入，因此预览是正确的。这种不一致只对滑出该按键的滑动成立。
- F710：T9Keyboard.kt:82 处的注释（"never touched"）与 BaseKeyboard.kt:450-455 处的 vivo 第二根手指路径相矛盾。
- F711：如果输入法一开始就是 T9，那么广播期间 TextKeyboard 不是当前键盘（KeyboardWindow.kt:173-175），因此在它显示期间收到一次广播之前，它的角标一直保持内置值。
- F709：VoiceHoldSession.kt:107 在提交时会再次检查 `inPasswordField`，而这项检查同样没有测试。
- F516：LicensesFragment 中 Triple 的 title 是字符串资源 Int，而 `Credit.title` 是 String。除此之外结构相同。
- F515：AndroidX 的 `Fragment.onViewCreated` 被认为是空操作（无法获取源码），因此跳过 `super` 目前没有影响。

**全面验证 A23**（覆盖 F712, F713, F714 等 20 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 部分。我改用了其 README（作为权威规格）；没有任何结论依赖于它。
- F712：在默认肤色（Default，AppPrefs.kt:441-445）下，每次 bind 只解析码位并检查一个属性。RGI 和 hasGlyph 检查只在用户选择肤色后才会运行。
- F713：上游的 ReloadConfig 只重新读取全局 `config` 文件（fcitx instance.cpp:2044-2047），因此它同样从未应用对 profile 或标点文件的修改。setGlobalConfig 在重新加载前会保存内存中的配置（native-lib.cpp:198-201），这会覆盖通过 provider 所做的修改。结束进程也会使修改生效。
- F714：假定 AppCompat 和框架只会在真正的配置变更时重建 activity。它们的源码不在仓库中。
- F179 和 F354：attach 时持久化（persist-on-attach），以及在未存储任何值且没有默认值时 dispatchSetInitialValue 跳过 onSetInitialValue，都是 AndroidX Preference 的行为，与 SettingsIndex.kt:70-72 和 ManagedPreferenceUi.kt:74 处的代码注释一致。
- F520：notifyDataSetChanged 之后，RecyclerView 只会重新绑定已 attach 的条目，因此实际开销是（已绑定条目数）× n。O(n²) 只作为上界成立。

**全面验证 A24**（覆盖 F522, F523, F598 等 13 条）

- 验证者备注
- F599：引擎把 AutoPhraseLength < 2 视为关闭（lib/ime-core/.../engine/table/TableSession.kt:255），所以把存储的值 1 标为 Off 与引擎的实际行为一致。该论断按字面成立，但这个显示本身没有错。
- F600：目前没有任何 Item 列表在 Section 里嵌套 Section（InputMethodSettings.kt:104-187），所以这段扁平化处理目前还走不到。
- F661：两个调用方也都用默认的 Json 解码（data/theme/ThemeFilesManager.kt:50,131），所以目前运行时没有任何变化。
- F724：唯一的调用方 PreferenceScreenFactory.kt:56 通过 getOrElse { throw it } 重新抛出 ParseException，所以运行时只有异常类型不同。ConfigDescriptor.kt:293（List<Int> DefaultValue）也有同样的 toInt() 写法。
- F598、F660 和 F661 引用的行，git blame 将其归到 fork 点之前的上游提交（ab1b33089、2ecc6441d、9c384bf18、0eb401b87）。它们没有标为 PRE-EXISTING，而本次审查把整个仓库视为变更，所以按原样评判。
- F659：索引和 AdvancedSettingsFragment 读取的是同一批 R.string id，所以修改某个字符串的文本时两者会保持同步。只有当该 fragment 改用别的资源，或用别的方式构造标题时，高亮才会悄无声息地失效。

**全面验证 A25**（覆盖 F60, F284, F298 等 16 条）

- 验证者备注
- F339：核心论断对 UI 图标成立；两处反驳只针对 "every other icon" 这一措辞（启动器图标的各图层不是白色）。
- F424：youmo 新增且未被引用的 8 个字符串是 browse_user_data_dir_summary、custom_phrases_summary、home_section_appearance、home_section_keyboard、my_words_summary、search、section_size_and_padding 和 section_toolbar_and_panels。
- F601：zh-rTW 重复了同样的冲突（values-zh-rTW/strings.xml:378 和 :383 都是 詞庫）。
- F602：es 的 import_user_data（values-es/strings.xml:115）也用了同样的 "datos de uso" 措辞。
- F664：就我所了解的 AOSP TextView 行为而言，该论断很可能成立；标为 unverifiable 只是因为这里无法获取该源码。
- F716：MainActivity 是导出的（AndroidManifest.xml:66），所以无论过滤器怎么写，任何应用都能向它发送携带任意 URI 的显式 VIEW intent。BROWSABLE 的问题只涉及网页。
- 此列表中没有任何条目标为 PRE-EXISTING。

**全面验证 A26**（覆盖 F732, F736, F737 等 13 条）

- 验证者备注
- EngineDataPlugin.kt:54,110、release-report.sh:2 和 engine-data.sh:2 引用了 `dev/TRAINING-PLAN.md`，但没有任何 `dev/` 路径被跟踪（`git ls-files`）。
- F737：当回退文件本身也缺失时（`instead?.get()` 返回 null），该注释同样成立。"Only when instead is null" 的说法略窄。
- F743：按文件回退在运行时已经可能混用配对。如果某个构建只有两个 T9 文件中的一个，T9 就会得到一个 T9 模型和一个通用模型（Engines.kt:138,164-165）。
- F741 和 F745 部分依赖 JDK 和 ICU 的 `\b` 语义，而这里无法获取它们的源码。仓库自己的注释（VoiceText.kt:17-19）和提交 af16cd95 描述了相同的行为。
- F747：在应用中，`asset()` 对文件做内存映射（EngineBridge.kt:182-184），NativeMatrixKernel 使 `unpack=false`。所以拷贝来自 Safetensors 的 ByteArray 和 FloatArray 读取，而不是 asset 拷贝。T9 文件的大小（假定与通用 4M 模型一样约 4.5 MB）无法核实，因为该文件不在仓库中。
- app/licenses/libraries/sentence-models.json：`artifactVersion` 和 `website` 仍只写着 sentence-models-20261001。T9 发布版只出现在 `description` 中。

**第二轮 V1**（覆盖 F729, F730, F731 等 6 条）

- 验证者备注
- F734.b、.c 和 .d 称“仅剩解码器”或“没有重排序”。损失是按文件计算的：如果只有一个 T9 文件损坏，另一个 T9 模型仍会运行（Engines.kt:540 处的逐键重排序器，或 Engines.kt:842-846 处的停顿精修器）。
- F731 和 F730 依赖于 T9 文件与通用文件大小相同。发布文件（sentence-models-t9-20261007）和 .gradle/engine-downloads 不在磁盘上，因此无法读取文件大小。
- Safetensors.kt:80-81 是 ByteArray 分配和复制所在的行。验证列表引用的是 79-80，早了一行。
- 发布构建中出现 T9 文件缺失，前提是打包配置有误。下载的文件会校验 SHA-256，缺失时构建失败（EngineDataPlugin.kt:196-202），而且 `.safetensors` 已经是 noCompress（EngineDataPlugin.kt:212）。

**第二轮 V2**（覆盖 P1, P3, P4 等 20 条）

- 验证者备注
- P161：新的 T9 测试只断言加载了哪些 .safetensors 路径。没有任何测试检查 T9 候选词、上屏，或 EngineBridge.kt:186 所使用的 T9State/syllables 接入。
- P50：同时使用 T9 和全拼的用户现在会在堆上持有两对模型，因为每个 ModelFile 都会复制自己的一份（Engines.kt:157-165）。
- ModelFile 只在出现 FileNotFoundException 时才回退到 `instead`（Engines.kt:136-138）。损坏或被截断的 t9 文件会得到 null，不会回退到通用模型。
- app/licenses/libraries/sentence-models.json 仍然只为 sentence-models-20261001 提供 artifactVersion 和 website。t9 发布版本只出现在其 description 中。

**第二轮 V3**（覆盖 P162, P165, P195 等 20 条）

- 验证者备注
- EnginesTest.kt:87：在 5881d31e 把句子模型测试移出之后，EnginesTest 中已没有任何地方调用私有辅助函数 `Engines.pause`。MAX_SLICES（611）只被该辅助函数使用。
- README.md:55-56 和 :170-171 仍然只提到 sentence-models-20261001 发布版本。在 app/licenses/libraries/sentence-models.json 中，`artifactVersion` 和 `website` 也只指向 20261001。新的 sentence-models-t9-20261007 发布版本只出现在该文件的 `description` 中。
- P375 引用的另一处位置，即 PinyinSessionRefineTest.kt:7 处的 `NO_WORD` 导入，仍然未被使用：`NO_WORD` 只出现在导入那一行。本次改动没有涉及该文件。
- P195 的论断统计了八个相同的具名参数。在被审查的修订版本中只有六个相同：T9 的构造现在传入了不同的重排序器和精修器模型，而 habits 原本就按会话各不相同。
- 我假设工作树等于 5881d31，因此上面的 HEAD 行号就是被审查修订版本中的行号。基线文件是用 `git show 07d2778:<path>` 读取的。

**第二轮 V4**（覆盖 P411, P414, P426 等 19 条）

- 验证者备注
- 模型测试移出后，EnginesTest.kt:87 的 `Engines.pause` 和 MAX_SLICES（:611）已没有任何调用方。`model` 和 `refining`（:60-61）仍然是 `var`，但在该文件中从未被重新赋值。config/detekt/baseline-lib-ime-core.xml 中没有 EnginesTest 条目，因此如果 detekt 的未使用私有成员规则会扫描测试源码，就可能标记 `pause`。
- build-logic 是一个 included build（被包含的构建），不是子项目，所以 DetektConventionPlugin 从不对 EngineDataPlugin 运行 detekt。P437 中引用的 80 行限制只是作为对比；那里没有任何机制强制执行它。
- 当九键模型对存在时，在同一进程中于 T9 和全拼之间切换会让两对模型都保持加载状态（Engines.kt:164-165、:540）。只有关闭句子模型设置才会释放它们（:499-500）。
- app/licenses/libraries/sentence-models.json：description 现在提到了 t9 发布版本，但 artifactVersion 和 website 仍然只指向 sentence-models-20261001。
- lib/ime-eval/release-report.sh:8-9 仍然把 MODELS 描述为只包含通用模型对。
- 重定向到 /tmp 失败，所以我在 .sisyphus/handoff/ 下写了两份基线文件的临时副本，之后已将其删除。仓库工作树仍然是干净的。

**第一轮 V1**（覆盖 F15, F31, F100 等 17 条）

- 核查者备注
- F31：我认为 `fcitx5-android` Cachix 缓存属于上游，因为它的名称与上游项目一致。仓库中没有任何文件表明由谁控制它。
- F36：flake.nix:58 使用来自 nixos-unstable 的普通 `python3`，且 flake.lock 固定了该修订版本，因此 nix shell 很可能得到 Python 3.12 或更高版本。在 nix 之外使用 Python 3.11 或更早版本的机器仍会失败。CI 从不运行 apply.py。
- F44：PageCleanerTest.kt:29-31 间接检查了：在没有冲突时，出现两次的行计数低于 3。按行求和的缺陷会让该测试失败，而用最大值代替最小值的缺陷仍会通过。
- F63：重新运行时，只有在设置了 LEXICON（engine-data.sh:182 会删除 done/manifest）或 manifest 步骤从未完成时，manifest() 才会再次运行。否则旧的哈希值保持不变。
- F102：长度阈值是根据 type/token 曲线得出的估计；我没有运行任何东西。典型的 2-5k 字的小说章节仍高于 1/10 的比例。该规则命中的是大约 2 万个汉字及以上的页面。Main.kt:362 报告了总共保留了多少页面，但没有报告每条规则各丢弃了多少。
- F159：在此改动之前，`bisai` 很可能已经把 比赛 排在第一位。该改动把 鼻塞 从 `bisai` 的候选中移除；它不改变首选项。我没有运行引擎来确认哪个词排在第一位。
- F285.b：提交 9dcb1b59（从 SenseVoice 切换到 X-ASR）把 README 中的 ~240 MB 改成了 ~180 MB，但没有更新 app/build.gradle.kts:62。
- F288.b：所引用的第 40/42/50 行偏差了一行。所描述的注释位于第 41/43/51 行。
- F306.a：指向 lexicon/README.md 的指引位于 misreadings.tsv:4，处在一个跨越第 2-4 行的句子末尾。
- F305：engine-data.sh:13-14 处的头部说明只要求 LEXICON 和 WORDS 使用绝对路径。$CORRECT 在第 84 行定义，并在使用它的地方（第 103 行和 weight() 中）发生单词拆分（word splitting）。

**第一轮 V2**（覆盖 F3, F12, F45 等 18 条）

- 核查者备注
- 路径前缀：K/ = lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/，T/ = lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/，J/ = app/src/main/java/org/fcitx/fcitx5/android/。
- 上下文文件中没有 `## Stated intent` 一节。我使用了其中的 README 部分（作为正式依据的规范）来确定项目目标。
- F3：压缩只写入计数（UserStore.kt:50），因此被遗忘的词要到压缩后的第一次重启才会真正消失。在此之前，它们仍保留在内存中的 trie 里。
- F47：每一条范围更窄的代码事实都准确。过期复用需要在该次学习之后出现一次输入长度至少为两个键的解码，而没有任何主机路径会产生这种解码（Keyboard 每个事件只发送一个键，且每次激活都以 Reset 开始）。
- F61/F233：导入界面的 PinyinDictManager.importFromFile 用 runCatching 包裹了转换过程，因此会捕获 OOM。每次启动都崩溃的问题来自 ImportedDictionaries.migrate 处理已在 dictionaries 目录中的 .dict 文件，例如随旧应用用户数据一起带入的文件。分配是否真的失败取决于设备的堆上限。
- F79：`return@repeat` 是否会触发，取决于 拟 是否在第 10 次选取之前排到第一位。
- F310：Gradle 的 Test 任务默认启用断言，而 build-logic 没有将其关闭，因此 `assert` 在 ./gradlew 下确实会执行。
- F311.c：`id(word)` 辅助函数是 PinyinDecoderTest 和 TextWordsTest 各自的私有函数。LayerPriorTest 自己没有这样的函数。
- F356.b：由于 Predictor.kt:76 处的 `!any &&`，第二次 `offered(word)` 调用只会对排在第一个已提供（offered）词之前的后继词（followers）运行。
- F55：笔画数据走的是同一条静默的 FileNotFoundException 路径（Engines.kt:694）。

**第一轮 V3**（覆盖 F48, F49, F62 等 19 条）

- 验证者备注
- 所有路径均相对于仓库根目录。
- F48：在拼音中长按 Forget（遗忘）只会调用 `user?.forget(words)`（PinyinSession.kt:349），永远不会到达码表。只有从设置列表中删除（Engines.kt:350）才会调用 forgetText。重启后，两条路径的结果相同。该缺陷只在码表已加载到内存中时出现：从其日志重新打开的码表已经在 `saved` 中保存了该词。
- F49："cannot read input method" 错误只写入日志（`Timber.w`，EngineBridge.kt:69）。之后按键会以原始字符的形式到达应用（native-lib.cpp:555）。CodeTableReaderTest.kt:246 断言没有 码长 的码表能够通过 `check`。
- F62：生产环境的每页大小是 `EngineSettings.pageSize`（默认 7），而不是 5。EngineBridge.kt:194 还会至少获取 32 个候选词。该论断的要点依然成立。
- F65：该 CI 作业设置了 `timeout-minutes: 20`（.github/workflows/unit_test.yml:42）。挂起会在 20 分钟后以作业超时告终，且不会指出是哪个测试失败。
- F182：Kotlin 中的缺陷本身确实存在。只有当宿主在一个设置了 `refines` 但没有候选词的快照之后发送 Refine 时，它才会显现。
- F183：一次打分最多需要 读音数 ×（窗口 − 上下文位置）步。上下文已满时为 5 × 25 = 125 步，低于约 190 步的上限，因此该情形需要较短的上下文。REFINE_BUDGET=1（PinyinSession.kt:802）× SLICES=64（Reranker.kt:41）已经限制了每次输入的工作量。
- F208：“学到的预测无法删除”这一论断对词库中的词成立。用户自己的词仍然可以从设置列表中删除（Engines.kt:237、347）。
- F209：`jin` 这个例子适用于在 IN_ING 下将 5464 读作 jin 的情况。
- F256：Forget 只会为模型中已有 id 的词写入 FORGOT 记录（UserModel.kt:134-136）。Block 则总是写入词列表。
- F257：当前的所有写入方都无法产生一条 CRC 有效、却在重放时抛出异常的记录。entry 和 learn 中的 require 检查在 UserLog 中已有防护。负的 `readShort`（`List(negative)`）需要 CRC 恰好碰巧匹配的损坏数据。另外，`userModel` 在存储打开之前就已被赋值（Engines.kt:195），因此在抛出异常之后，后续调用会在没有存储、没有附加词库、也没有应用任何词列表的情况下运行。
- F259：候选词标签使用的是同一个经过过滤的字符串，因此第一个候选词显示的标签是 `q`。不一致之处是相对于 .conf 中的位置而言的。由于子模块未检出，未核对 fcitx 自身的行为。

**第一轮 V4**（覆盖 F290, F312, F313 等 19 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 一节。我以其 README（权威规范）作为项目目标的依据。
- 除非给出了 app/ 或 .github 路径，文件名均指验证列表中引用的 lib/ime-core 路径。
- F357.b：其中范围较窄的论点（`writeCounts` 不施加任何上限）属实。只有“习惯数据过大”这一后果无法触发。
- F29：只有读作带 零 的分钟数会经过 `%02d`。其他分钟数和小时使用 Long.toString，保持为 ASCII。
- F37：如果应用通过 restartInput 清空输入框，onStartInput 会遗忘这些配对（FcitxInputMethodService.kt:669）。点击到位置 0 则总会触发该缺陷。
- F261 需要 fcitx 的引号状态在该配对仍被记住期间已被重置（AutoPairs.kt:23-24）。这是 “ 能够越过 ” 的唯一途径。
- F137：只有在遗忘与重启之间没有运行压缩（compaction）时，该遗忘操作才会丢失。压缩会在遗忘之后写入计数。
- F260：UserModel.kt:20-23 在文档中说明丢弃是有意为之。这影响的是等级判定，而不是此处核对的事实。
- F57：文件最终是否变为空取决于文件系统。ext4 的 auto_da_alloc 会缩小这个窗口；f2fs 没有这样的防护。

**第一轮 V5**（覆盖 F262, F317, F318 等 14 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 一节，因此我以其 README（权威规范）作为项目目标的依据。
- F262：该运行时论断假设编辑器在应用 setSelection 时不会对齐到码点边界，标准 EditText 正是如此。IME 代码也没有添加任何对齐处理。
- F317.c：Triple 展平只发生在新增配对的 learn 上。对已有文本重新计数，或 text == own 时，会在此之前返回（KeyHabits.kt:66、:106-107）。:65 处的 filterKeys 复制则在每次 learn 时都会发生。
- F318：第 164 行只有在第 163 行未返回时才会执行。因此在常见的导入路径上（以空格或 `'` 分隔的拼音），entry() 每次调用只构建一个 Regex，而不是两个。第 164 行的调用链还额外加了 lowercase()。
- F9.b：--beam 0 能通过校验，然后在读取评测集文件之后于 PinyinDecoder.kt:46 抛出 IllegalArgumentException。这仍然不是用法错误（退出码 2）。
- F293.b：EvalSet.kt:50（RunResultFormat.parse）只跳过空行，不跳过注释行。EvalSet.parse（:17-18）对每一行的计数都正确。
- F19.e：评测使用的 refiner 始终是本地的 Reranker（Main.kt:219）。它在预算为 Int.MAX_VALUE 时的 refine 不会返回 null，因此该无限循环处于潜伏状态。

**第一轮 V6**（覆盖 F10, F20, F21 等 19 条）

- 验证者备注
- 假设：fcitx5 和 fcitx5-chinese-addons 子模块未检出。因此 F104（上游的顶层 CMake）、F295（fcitx::utf8::UCS4ToUTF8）和 F359（Fcitx5Macros 以及读取 LINGUAS 的 msgfmt --desktop）依据的是这些项目已发布的源码。X-ASR 的 tokens.txt 和 BPE 模型在构建时下载，因此 F20（tokens.txt 中缺少某个子词片段 piece）和 F280（一个字符有不止一种分词方式）的前提条件无法在此核对。
- F10.b：Reranker.kt:37-41 将一次停顿限制在 64 个切片以内，因此在实测的手机上，重排远在半秒之前就会完成（137 个切片 ≈ 0.5 秒）。由于没有挂钟时间上限，较慢的手机耗时会更长。
- F10.e：只有 getCandidateActions 之前的那个窗口是真实存在的。candidateActions() 会调用 stopRefining()（androidengine.cpp:117），因此除非有新事件到达，否则在排队的 triggerCandidateAction 之前不会运行任何切片，而该新事件会关闭菜单。
- F10.f：refine 产生的 CandidateListEvent 在 getCandidateActions 恢复之前就已到达主线程，因此 BaseInputView.kt:61-62 处的关闭操作是在菜单尚不存在时执行的。
- F20.b：被屏蔽的词只在屏蔽重搜索（VoiceEngine.kt:143）中编码，而不是在每一段语音上都编码。热词则会为每个流编码（VoiceListener.kt:123、313、326）。
- F20.f 以及 F20 的运行时论断：SHERPA_ONNX_LOGE 调用 __android_log_print（macros.h:30-32），后者会按其缓冲区大小截断每条消息。因此，包含数千个词的列表只有一部分能到达 logcat。
- 在 FcitxDaemon.restartFcitx（FcitxDaemon.kt:114）之后也能触发 F116，因为只有 onBindInput 会重新激活输入上下文（FcitxInputMethodService.kt:615）。
- F278：该延迟来自每次按下时创建的一个 silero VAD 会话。仅凭代码无法测量这需要多长时间。

**第一轮 V7**（覆盖 F361, F5, F6 等 19 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 一节，因此我将其 README（权威规范）作为项目目标。
- F361：fcitx5-chinese-addons 子模块未检出（gitlink 0d3fd040）。我的判定依据是本 CMakeLists（它没有链接任何 Lua 目标，只调用了 find_package），以及上游的目录结构（其中只有 im/pinyin 使用 LuaAddonLoader）。应用有自己的 fcitx5-lua 依赖（app/build.gradle.kts:129）。
- F5：UTF-8 的非 ASCII 值能够保留下来，虽经重新编码，但含义不变。对于非 UTF-8 的百分号编码序列（Uri.decode 会将其变为 U+FFFD）以及被双重编码的值，损坏确实存在。
- F18.d：在 pack-replace 路径上，替换成功后流会由 `bufferedReader().use` 关闭（PinyinDictionaryFragment.kt:233）。在另外两条路径上，以及 setIntoNew 抛出异常时，流会泄漏。
- F18 附注：importFromInputStream 将 provider 提供的显示名称用作路径，即 `File(cacheDir, name)`（PinyinDictManager.kt:102），而 readWords 则刻意不信任该名称。这不改变等级。
- F87.b：“no JVM test can cover”（没有任何 JVM 测试能覆盖）的说法言过其实。应用的单元测试已经在 Robolectric 下运行（ClearURLsTest.kt:15）。
- F105.c：当导入的 external/ 改变了词库签名（`seen`）时，Engines.reload() 会调用 dropUser（Engines.kt:865），拼音存储会被重新打开。内置码表的存储无论哪种情况都保持打开（Engines.kt:857）。
- F58：关闭标点后残留过时的全角按键标签，这一点确实存在。我之所以驳回，只是因为运行时影响的论断称中文标点也会被上屏（commit）。

**第一轮 V8**（覆盖 F117, F140, F141 等 19 条）

- 验证者备注
- F117：只有运行时影响被驳回。如果上游将来把剪贴板数据库升级到版本 4 以上，导入操作将清空剪贴板历史，因为 ClipboardManager.kt:104 设置了 fallbackToDestructiveMigrationOnDowngrade。
- F141：对于振幅为 0 的 35/45 ms，各档位的距离分别为 80（System default）、200、318 和 475，因此显示的是 System default，而选择它会在 :56 处直接返回，不进行写入。
- F143：未经 fsync 的 rename 是否会留下空文件取决于文件系统。ext4 的 auto_da_alloc 会降低这一风险；f2fs 没有这样的保护措施。
- F143.b：“六处”和“只有 EngineMigration 会执行 sync”这两项统计对 app/ 成立。lib/ime-core 中还有三处副本：Engines.kt:805-807 和 WordLists.kt:77-79 不执行 sync，RecordStore.kt:165 则使用了 `fd.sync()`。
- F147：全选不会删除任何内容，因此不会跳过任何记录工作。被删除的文本只有在输入框保持为空时才会恢复，因为非空的读取会覆盖该记忆（ContextMemory.kt:44-46）。
- F170：FcitxInputMethodService.kt:295 处的 `?: return` 还会跳过其后的 `evaluateOnInputMethodActivate` 步骤。
- F171.b：许多隐藏的整数设置同样没有 Levels：键盘高度、侧边和底部内边距、网格列数（span count）、候选窗口尺寸以及 clipboard_item_timeout。
- F173：EngineMigration.kt:69 处缺少 mkdirs 的问题确实存在。只有在首次启动时 `conf/` 不存在的情况下才有影响。
- F185：160dp 只在语音按钮可见时适用（语音版构建、非密码输入框）。在文本版构建中，语音按钮为 GONE（IdleUi.kt:104），因此固定按钮占 120dp，上限为 320dp。图标会通过 CENTER_INSIDE 缩小（ToolButton.kt:37），而不是被裁切。
- F244：这依赖于 fcitx5 在获得焦点（focus-in）时激活引擎。子模块未检出，但无论如何，androidengine.cpp:284 都会在激活时发送 Reset。
- F265：在未同步、基于 WeakHashMap 的集合上的竞争确实存在（主线程在 KawaiiBarComponent.kt:426 处添加元素）。`find` 分支中被捕获的异常还会跳过 `clbDao.updateTime`。
- F266：非模态的 ListPopupWindow 可以接收外部触摸，因此触摸其他位置仍会将其关闭。过时的菜单主要在候选词发生变化但没有触摸时残留，例如使用实体键盘输入时。

**第一轮 V9**（覆盖 F281, F329, F330 等 19 条）

- 验证者备注
- F281：:754 处的重置没有触发，原因比相应预测给出的更简单。AutoPairs.backspace() 在 :747 处的快照之前就已移除这对符号（AutoPairs.kt:83），所以 `quoteOpen` 在之前和之后都是 false。fcitx 的标点源码不在仓库中（lib/fcitx5-chinese-addons 子模块为空）。认为 fcitx 仍将 “ 视为未闭合的说法，来自代码自身在 :750-753 处的注释。
- F34.e：“仅由编辑器的重新加载刷新”言过其实。engines.reload() 也会在 fcitx 启动时运行（Fcitx.kt:452），并会从词典页面运行（PinyinDictionaryFragment.kt:236、:264）。在其中任一处运行之前，过期覆盖都可能发生。
- F349/F349.a：`sent.captured` 已经是一个真实的 KeyEvent，在 KeyEventRelay.kt:52 处重建，第 95 行检查的正是它的 `unicodeChar`。改用一个记录型 BaseInputConnection，检查的也是同一个值，并不会多检查什么。
- F360：scel2org5 把结果写入 `-o dest`（PinyinDictManager.kt:139），因此要发生挂起，stdout/stderr 上的输出必须超过管道缓冲区的容量。该转换器的源码不在仓库中。
- F360.c：fd 泄漏在 API 23 上确实存在（minSdk 23，Versions.kt:12）。从 API 24 起，平台的 Process 实现会在子进程退出时关闭管道，因此在这些版本上不会发生泄漏。这是平台行为，不是仓库代码。
- F93：焦点在第 2 列时，按键错开一列；焦点在第 3 或第 4 列时，错开两列。焦点在第 0 或第 1 列时，空位排在最后，这一行是对齐的。对于 e，焦点在第 2 列还是第 1 列取决于屏幕宽度（popupWidth 38dp，见 PopupComponent.kt:51、PopupContainerUi.kt:47）。
- F347：多余的分隔线只在最后一个候选词不在行末时出现，因为行末在 GridDecoration.kt:53 处被跳过。
- F90：长按的触感反馈仍可能触发（CustomGestureView.kt:179），但之后没有任何消息或操作。
- F94：用户仍可通过两步回到跟随系统：先选择另一种应用内语言，再选择 “Same as phone”（与手机一致）。
- F329：第 43 行是 `@DrawableRes` 注解；声明在 :44。

**第一轮 V10**（覆盖 F96, F97, F107 等 19 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 一节。我以其中的 README 一节（正式记录的规格）作为项目目标。
- F96：并发修改的窗口只有 IO 上的 `items.map` 调用（CustomPhraseManager.kt:29）。只有恰好落在该窗口内的 UI 编辑才会触发它，因此很少见。在主线程之外读取 `ui.entries` 引用也是一次未同步的读取。
- F115.b：onDestroy 在 finish() 之后异步运行，而不是紧接着运行。只有当 onDestroy 在 Room 写入返回之前取消了作用域，这份副本才会丢失。
- F125：重命名失败后，文件仍以旧名称留在列表中。“似乎消失了”更符合移动失败的情况。
- F126：会有一个 toast 报告该 IOException（PinyinCustomPhraseFragment.kt:313），所以这次丢失并非完全无声。如果用户在离开前改动了其他任何内容，这些编辑仍会被保存。
- F149/F175：代码注释说明这些行为是有意为之：BaseKeyboard.kt:162-164 处直接提交字母，以及 BackspaceSwipeBehavior.kt:24-26 处在取消时删除选中内容。
- F151：对话框卡住的情况可能在 activity 存活期间发生，例如当 ACTION_RUN intent 到达 onNewIntent 并弹出该 fragment 时（MainActivity.kt:105）。
- F114：BuiltinQuickPhrase（BuiltinQuickPhrase.kt:11-21）没有扩展名检查，并接受调用方传入的覆盖路径。构造的路由可以让编辑器读取任何已存在的应用私有文件，并写入任意路径。CustomQuickPhrase 只接受 `.mb` 或 `.mb.disable` 文件。
- F250：在更少见的情况下，索引仍可能出错。其一是在系统动画时长缩放被调慢时长按。其二是某一行在被按住或拖动时被移除（例如用第二根手指点击 Undo（撤销）），这会在松开时导致 swapItem(-1, …) 或 getSwipeDirs。

**第一轮 V11**（覆盖 F272, F273, F333 等 19 条）

- 验证者备注
- 上下文文件中没有 `## Stated intent` 一节。我以其中的 README 一节（第 137-370 行）作为项目目标。
- F272 和 F333：没有代码读取 `TextKeyboard.quickphrase` 或 `NumberKeyboard.space`，因此只有在以后的改动读取它们时，才会发生 NPE 和 ClassCastException。在 app/src 或 lib/ime-core 中没有找到其他调用 triggerQuickPhrase 或 triggerUnicode 的路径（fcitx 插件自身的触发键需要物理键盘）。我没有确认快捷短语编辑器（QuickPhraseList）是否真的能从某个设置页面进入。唯一的入口是名为 QuickPhrase 或 Editor 的 fcitx 配置选项。
- F273：纵向滑动需要达到 RecyclerView 高度的一半，或是一次纵向快速滑动（ItemTouchHelper 默认值）。这一保护只在 enableOrder=true 的列表中、对处于多选状态或不可移除的条目失效。
- F14：通过前缀绕过在临时目录之外写入文件，需要知道临时目录精确到毫秒的名称（TempDir.kt:11）。在临时目录之外创建目录则是无条件的（ZipStream.kt:23-24）。泄漏的 FileOutputStream 只有在 GC 终结它们时才会被释放。
- F41：所引用的 FcitxKeyPreference.kt:24 与删除标题无关。它从 DialogSeekBarPreference 的 styleable 中读取 FcitxKeyPreference_useSimpleSummaryProvider，这是另一个问题。
- F73 和 F73.c：只有当 conf 中有 Name[zh] 而没有 Name[zh_HK] 时，zh_HK 用户才会看到未翻译的名称。失效的回退逻辑本身是确定存在的。
- F110：待处理的保存任务是被 fcitx 的 ON_STOP 取消的（FcitxLifecycle.kt:52-53），而不是被 fragment 自身的 onStop 取消。Fcitx.stop 只在处于 READY 状态时发出它，即最后一个客户端断开连接或 fcitx 重启时。Fcitx.kt:553 只记录被丢弃任务的数量。
- F118 和 F186：fcitx5-chinese-addons 子模块未检出，因此我没有阅读标点插件的查找代码。我的判定基于以下假设：以单个码点为键的查找（androidengine.cpp:289-290）无法匹配多字符的键，并且引擎每次按键恰好提交一个结果。
- F180：应用自己的输入法通过 InputMethodNames.kt:17-27 和 InputMethodSettings 避开了这个问题。影响仅限于 fcitx 自己翻译的字符串（应用不认识的输入法名称、插件配置页面、状态区操作）。
- F204：未勾选 “first” 时，被编辑的卡片保留其原有索引，该索引可能排在该键其他卡片之前。`indexOfFirst` 的结果（第 156 行）只在勾选该框时才会使用。

**第一轮 V12**（覆盖 F226, F251, F274 等 17 条）

- 验证者备注
- F251：MyListPreferenceDialogFragment.kt:17 还添加了一个中性的 “Default”（默认）按钮，因此对话框显示文本、Cancel 和 Default。目前该 ListPreference 没有 key，MyPreferenceFragment.kt:18 把这个为 null 的 key 传入 newInstance(key: String)。
- F252：应用内的 Import（导入）按钮（`launcher.launch("*/*")`，PinyinDictionaryFragment.kt:112）确实可以导入 .words。应用内没有任何文字提示用户直接打开下载的文件。下载提供程序的 content URI 通常没有扩展名，因此这些 pathPattern 对任何格式都很少能匹配。
- F1/F27：device_root 资源副本（CMake 安装内容加预构建资源）的大小无法从源码测得。如果超过 25 MB，云备份会被完全跳过，这也会阻止 F1 所描述的上传。引擎数据和语音模型是从 APK 读取的生成资源目录，不会被同步。
- F353：两个调用要发生冲突，必须落在同一毫秒内。所有调用方（TableManager、UserDataManager、QuickPhraseManager、ThemeFilesManager）都由用户触发，且都没有嵌套调用 withTempDir。
- F335.b：BaseDynamicListUi 也没有确认步骤。它是否就是被“替换”的 UI 属于历史问题，我没有核查。
- F338.a：“在设置重构和插件移除之后”属于历史问题，我没有核查。
- F297.b 引用的是第 33 行，那一行是 fillColor；浮点数在第 32 行。

### 6.3 被丢弃的低置信度 NIT

第一轮：G1-29「detekt.yml 头注释「只列差异项」不准确」（5/10）、G3a-39「libime 导入错误可能把用户词写入 logcat」（4/10）、G5-41「make-mixed-set.py 的拉丁部分模式接受数字」（5/10）、G6-42「小数组也放进临界区，可能阻塞 ART 并发 GC」（5/10）、G6-46「码表预设用 setValue 设置，描述中的默认值仍是通用值」（5/10）、G6-56「refusedForGood 不随 Activity 重建而保留」（5/10）、G6-59「内核内层循环每次只加载 4 字节再扩展」（5/10）、G7-106「两个输入框之间的空档里，隐私门控按非敏感处理」（4/10）、G8a-42「可添加的长按字符数量没有上限」（5/10）、G8b-12「窄屏上较宽的标点映射会撑出卡片」（5/10）

第二轮：D-13「测试迁移后 EnginesTest 留下未使用的辅助代码」（5/10）

## 7. 各 stage 的结论

第二轮：建议修改后批准 7，批准 0，要求修改 0。第一轮：建议修改后批准 154，批准 11，要求修改 17。CORRECTNESS 和 GENERIC stage 另附 Checklist 行，原文保留：findings on = 有发现的项，clean = 检查过且无问题的项，n/a = 不适用的项。

| 轮次 | Stage | 结论 | Checklist |
|---|---|---|---|
| 第二轮 | CORRECTNESS-1 | 建议修改后批准：T9 模型的接入和正则修复是正确的，并且有测试覆盖；尚未解决的问题是 APK 体积的取舍、缺少针对 T9 的发布门禁，以及错误路径、测试和文档方面的一些小缺口。 | Checklist: findings on 1,2,8; clean 3,4,5,6,7; n/a none |
| 第二轮 | CORRECTNESS-2 | 建议修改后批准：T9 自有模型和正则修复确实实现了提交中声称的效果，但堆内存和 APK 开销翻倍，以及发布门禁中缺少 T9 的问题，应当予以解决或被明确接受。 | Checklist: findings on 1,8; clean 2,3,4,5,6,7; n/a none |
| 第二轮 | GENERIC-1 | 建议修改后批准：T9 模型选择、构建时回退和正则修复是正确的；尚未解决的问题是切换输入法时的堆内存占用，以及 T9 丢弃路径缺少测试。 | Checklist: findings on 1,2,7; clean 3,4,5,6,8; n/a none |
| 第二轮 | GENERIC-2 | 建议修改后批准：T9 模型路由和回退是正确的，主要路径都有测试，但同时使用 T9 和拼音输入的用户现在要占用大约两倍的模型内存，而且 T9 丢弃路径和文件不可读路径都没有测试。 | Checklist: findings on 1,2; clean 3,4,5,6,7,8; n/a none |
| 第二轮 | PERFORMANCE | 建议修改后批准：该改动是正确的，也没有引入泄漏，但在九键和全拼之间切换的用户现在要占用大约两倍的模型内存，并且没有任何机制释放闲置的那一对模型。 |  |
| 第二轮 | TESTING | 建议修改后批准：测试是原样搬移的，两个新的 T9 测试覆盖了主要意图（自有模型对、回退、与拼音隔离），但新的 T9 丢弃/重新读取逻辑没有测试，而该包对通用模型对的同一逻辑是有测试的。 |  |
| 第二轮 | STYLE | 建议修改后批准：该改动遵循了该包的惯用写法，按类重复的测试夹具与现有测试类的做法一致。唯一的 SHOULD FIX 是未经测试的 T9 丢弃路径；其余都是命名、注释和小的结构问题。 |  |
| 第一轮 | CORRECTNESS-1@S1 | 建议修改后批准：这里没有缺陷会破坏已发布的 text 构建，但 CI 从不编译 voice 变体，也不运行 ime-eval 测试；voice APK 缺少许可声明；也没有任何机制保障不联网的承诺。 | Checklist: findings on 2,4,5,8; clean 1,3,6; n/a 7 |
| 第一轮 | CORRECTNESS-2@S1 | 建议修改后批准：没有 MUST FIX：S1 的基础设施按现有写法是正确的，但 voice 变体没有 CI，许可声明不完整，而且构建配置中带有只适用于某一台机器的变通做法。 | Checklist: findings on 2,4,8; clean 1,3,5,6; n/a 7 |
| 第一轮 | GENERIC-1@S1 | 建议修改后批准：我没有发现会破坏已发布 text 构建的缺陷，但 CI 从不构建 voice 变体，也不运行 ime-eval 测试；voice APK 存在许可方面的缺口；也没有任何机制强制执行不联网的承诺，或把 nix.yml 的令牌限制为只读。 | Checklist: findings on 1,2,4,5,8; clean 3,6; n/a 7 |
| 第一轮 | CORRECTNESS-1@S2 | 建议修改后批准：所有发现都不会破坏构建，也不会违背 README 的承诺。最显眼的是版本名：数据发布标签会给应用一个错误的版本字符串。另一个重要缺口是缓存键指纹未经测试，可能让过期的引擎数据在没有任何报错的情况下被发布。 | Checklist: findings on 3,4,8; clean 1,2,5,6; n/a 7 |
| 第一轮 | CORRECTNESS-2@S2 | 建议修改后批准：每个下载都用 SHA-256 固定，任务装配按当前配置是正确的，但在配置缓存下工具指纹会过期，而且不联网的承诺和模型不压缩的要求都没有构建时检查。 | Checklist: findings on 1,2,5,8; clean 3,4; n/a 6,7 |
| 第一轮 | GENERIC-1@S2 | 建议修改后批准：下载都用 SHA-256 固定，text 构建不依赖 voice，资源名、工具参数和热词规则都与其调用方相符；唯一真实的缺陷是指纹在配置缓存下过期，而它只出现在本仓库未启用的模式中。 | Checklist: findings on 2,4,8; clean 1,3,5,6; n/a 7 |
| 第一轮 | CORRECTNESS-1@S3 | 建议修改后批准：唯一高于 NIT 级别的发现是 Gradle 发行包校验和未固定。代码生成的按键映射与上游完全一致，我检查过的 sym 值都正确。每个新增的版本目录（catalog）条目都有使用，gummy-bears 的 coreLib2 签名与应用的脱糖（desugaring）配置相符，detekt 也在 CI 中运行。 | Checklist: findings on 4; clean 1,3,5,6,7,8; n/a 2 |
| 第一轮 | CORRECTNESS-2@S3 | 批准：所有发现都是 NIT；代码生成器对其所有调用方都是正确的，版本目录和 detekt 配置一致且有文档说明。 | Checklist: findings on 4; clean 1,3,6,7,8; n/a 2,5 |
| 第一轮 | GENERIC-1@S3 | 建议修改后批准：代码生成和版本目录是正确的；SHOULD FIX 项是缺失的供应链和权限防护，它们使“不联网”承诺得不到强制保证，而不是当前存在的缺陷。 | Checklist: findings on 4,5; clean 3,6,7,8; n/a 1,2 |
| 第一轮 | CORRECTNESS-1@S4 | 建议修改后批准：没有 MUST FIX；SHOULD FIX 项是 apply.py 中仅 Python 3.12 支持的语法、engine-data.sh 中过期或不匹配的 pinyin.data，以及 engine-data.sh 与应用构建中 misreadings 层和 latin 层不一致。 | Checklist: findings on 3,7,8; clean 2,4,5,6; n/a 1 |
| 第一轮 | CORRECTNESS-2@S4 | 建议修改后批准：这里没有任何问题会破坏已发布的应用或用户数据。缺陷出在发布时使用的工具和人工整理的数据中：apply.py 无法在 Python ≤3.11 上运行，发布任务的清单（manifest）可能记录错误的来源信息，还有一些常见读音被丢弃了。 | Checklist: findings on 1,2; clean 3,4,6,8; n/a 5,7 |
| 第一轮 | GENERIC-1@S4 | 建议修改后批准：没有发现会破坏应用，但 apply.py 在低于 3.12 的 Python 上会直接失败，而且 engine-data.sh 的续跑逻辑以及缺失的应用构建输入，使其整理和评估输出与应用实际发布的内容不一致。 | Checklist: findings on 2,3,7,8; clean 1,4,5,6; n/a none |
| 第一轮 | CORRECTNESS-1@S5 | 建议修改后批准：没有缺陷会导致设备上出现错误的运行时行为，但未经检查的人工整理读音可能让发布的词典悄无声息地缺少某个词，而按子串匹配垃圾内容的做法会使语言模型和新词所依据的语料产生偏差。 | Checklist: findings on 1,2; clean 3,4,5,8; n/a 6,7 |
| 第一轮 | CORRECTNESS-2@S5 | 建议修改后批准：Mixer 的重新归一化、n-gram 键打包和 CLI 装配都是正确的。SHOULD FIX 项是过滤和校验方面的缺口，会在不报错的情况下降低已发布词典或整理输入的质量；它们都不会破坏构建。 | Checklist: findings on 2,8; clean 1,3,4,5; n/a 6,7 |
| 第一轮 | GENERIC-1@S5 | 建议修改后批准：我没有发现 MUST FIX。SHOULD FIX 项是：readings.tsv 中的一处笔误会悄无声息地把一个词从已发布的词典中移除，以及 PageCleaner 和 NewWords 中的过滤启发式规则会丢弃正常页面，或让重复的连续片段通过熵检验。 | Checklist: findings on 1,2,7; clean 3,4,5,8; n/a 6 |
| 第一轮 | CORRECTNESS-1@S6 | 建议修改后批准：我手工核对了测试中的数值（CountFit、Surprise、带 Miller-Madow 校正的熵、FNV-1a、pack 和 words 的输出），它们都成立。测试覆盖了重要的边界情况：拒绝 glob 与 DuckDB 路径中的引号、被截断的 WARC 记录、ARPA 行号。有两处需要修复：ReadersTest 把一个错误的“skipped readings”（跳过的读音）计数固定成了预期值，以及 CommonCrawl 的 HTTP 重试策略没有测试。 | Checklist: findings on 2,8; clean 3,4,5,6; n/a 1,7 |
| 第一轮 | CORRECTNESS-2@S6 | 建议修改后批准：我手工核对的预期值都成立，发现的问题都不会导致错误输出。退化拟合防护、sketch 上界和抓取重试路径没有测试，应当补上测试。 | Checklist: findings on none; clean 3,4,5,6,8; n/a 1,2,7 |
| 第一轮 | GENERIC-1@S6 | 建议修改后批准：测试是确定性的，在 CI 中运行（unit_test.yml 中的 `:lib:ime-dict-tool:test`），并固定了 SQL 引号转义、glob 拒绝和许可主机过滤器的行为；主要缺口是 Main.kt 中有几条 CLI 路径没有测试，此外还有误导性的注释和较弱的断言。 | Checklist: findings on none; clean 1,3,4,5,6,8; n/a 2,7 |
| 第一轮 | CORRECTNESS-1@S7 | 批准：所有发现都是 NIT；仅有的正确性缺口需要在未经校验的情况下加载损坏的数据才会出现，而设备只会不经校验地加载已签名 APK 中的数据，或它在写入时已校验过的文件。 | Checklist: findings on 4,7; clean 1,2,3,5,6,8; n/a none |
| 第一轮 | CORRECTNESS-2@S7 | 批准：所有发现都是 NIT；数据格式、查找、回退（backoff）打分，以及针对损坏或恶意构造文件的加载时检查都是正确的，设备端调用方也能处理被拒绝的文件。 | Checklist: findings on 8; clean 1, 2, 3, 4, 5, 6, 7; n/a none |
| 第一轮 | GENERIC-1@S7 | 建议修改后批准：有一个 SHOULD FIX：快捷短语解析器会丢弃或改动 fcitx 能接受的条目。其余都是 NIT，数据格式代码对大小、边界和校验和的检查很仔细。 | Checklist: findings on 3,7; clean 1,2,4,5,6,8; n/a none |
| 第一轮 | CORRECTNESS-1@S8 | 建议修改后批准：没有缺陷会导致服务中断或违背已声明的承诺；遗忘功能的缺口，以及九键检测对数字编码码表的误判确实存在，但影响范围小。 | Checklist: findings on 2,5,8; clean 1,3,4,6,7; n/a none |
| 第一轮 | CORRECTNESS-2@S8 | 要求修改：在设置中一次遗忘两个或更多已学习的词时，一个也不会被遗忘：它们会立刻重新出现在列表中，并且仍然可以打出来。 | Checklist: findings on 2,8; clean 1,3,4,5,6,7; n/a none |
| 第一轮 | GENERIC-1@S8 | 要求修改：在设置中一次遗忘多个已学习的词后，它们仍留在列表中、仍可打出、仍与码表共享，并在重启后回来。 | Checklist: findings on 2,3,5,8; clean 1,4,6,7; n/a none |
| 第一轮 | CORRECTNESS-1@S9 | 建议修改后批准：解码器、zstd 读取器和 libime 读取器在我追踪的路径上都是正确的（包括增量 beam 复用和 RFC 8878 的表）；SHOULD FIX 项是恶意构造的导入文件引起的内存暴涨，以及共享同一个 scorer 的会话之间缺少对保留 beam 的失效处理。 | Checklist: findings on 4,7,8; clean 1,2,3,5,6; n/a none |
| 第一轮 | CORRECTNESS-2@S9 | 建议修改后批准：解码器、预测器、libime 读取器和 Zstd 解码器在我追踪的每条路径上都是正确的；两个 SHOULD FIX 项是跨会话的排序过期问题，以及大型或恶意构造的导入带来的内存风险。 | Checklist: findings on 4,7,8; clean 2,3,5,6; n/a 1 |
| 第一轮 | GENERIC-1@S9 | 建议修改后批准：没有 MUST FIX；lattice 和 libime 读取器做了仔细的边界检查，发现的问题涉及共享状态过期、热路径上的内存分配以及内存上限。 | Checklist: findings on 1,4,6,7,8; clean 2,3,5; n/a none |
| 第一轮 | CORRECTNESS-1@S10 | 建议修改后批准：没有正确性或数据安全缺陷。T9 中有一个真实的排序缺陷（末尾的 2/3/6/7 被判定为 EXTENDED 而不是 PARTIAL），值得修复并补上测试。 | Checklist: findings on 8; clean 3,4,5,6,7; n/a 1,2 |
| 第一轮 | CORRECTNESS-2@S10 | 批准：没有高于 NIT 级别的缺陷：解析、切分和词组放置在所追踪的边界情况下都成立；三个发现分别是全拼与双拼切分器之间的模糊音规则不一致、死代码，以及一处与代码不符的文档。 | Checklist: findings on none; clean 3,4,5,6,7,8; n/a 1,2 |
| 第一轮 | GENERIC-1@S10 | 建议修改后批准：没有 MUST FIX。两个 SHOULD FIX 发现是九键下以及启用 u/ou 模糊音时的切分缺口；其余词组和切分器代码在端到端追踪下都成立。 | Checklist: findings on 7,8; clean 1,2,3,4,5,6; n/a none |
| 第一轮 | CORRECTNESS-1@S11 | 建议修改后批准：重排序（rerank）、存储（store）和会话（session）代码在每个边界都检查不可信的模型文件，不让上下文进入敏感字段，并按描述遵守其预算；唯一真实的缺陷是：由于预测时 `actionable` 为 false，应用中无法对预测结果使用 Forget/Block。 | Checklist: findings on 2,8; clean 1,3,4,5,6,7; n/a none |
| 第一轮 | CORRECTNESS-2@S11 | 建议修改后批准：这个分片中没有任何内容违背 README 关于离线和重排序的说法。模糊音九键的预编辑（preedit）与上屏 bug、密码字段中未加防护的 Block 和 Forget，以及记录重放抛异常时打开失败的问题，都确实存在，但影响范围有限。 | Checklist: findings on 2,5,7,8; clean 1,3,4,6; n/a none |
| 第一轮 | GENERIC-1@S11 | 建议修改后批准：没有 MUST FIX。用户日志存储可能静默截断，然后重新播种并覆盖用户已学习的词；refine 路径中仍残留一个由 `graph!!` 引起的潜在崩溃。对不可信模型的解析有良好的边界限制，`learning` 开关让密码字段的文本不会进入上下文和学习。 | Checklist: findings on 2,3,5,7,8; clean 1,4,6; n/a none |
| 第一轮 | CORRECTNESS-1@S12 | 建议修改后批准：有三个影响范围有限的真实缺陷（以 u 开头的拉丁字母单词、被删除的词在重启后回来、导入检查不完整），没有 MUST FIX。 | Checklist: findings on 1,3,4,5,7; clean 2,6,8; n/a none |
| 第一轮 | CORRECTNESS-2@S12 | 建议修改后批准：没有 MUST FIX。四个 SHOULD FIX 项是：让已遗忘的词回来的日志重放、遗漏了引擎所校验内容的导入检查、`u` 捕获导致无法输入拉丁字母单词，以及共享词缺少顶屏防护。 | Checklist: findings on 1,7,8; clean 2,3,4,5,6; n/a none |
| 第一轮 | GENERIC-1@S12 | 建议修改后批准：没有 MUST FIX；三个 SHOULD FIX 缺陷影响范围有限：在空输入状态下无法输入以 u 开头的拉丁字母单词，已遗忘的共享词在重启后回来，导入会接受之后加载失败的码表。 | Checklist: findings on 3,4,5,7; clean 1,2,6,8; n/a none |
| 第一轮 | CORRECTNESS-1@S13 | 建议修改后批准：用户词库代码一致且测试充分，但词表的持久性、导入时无上限的内存分配，以及用户自己的词被永久衰减的问题应当修复。 | Checklist: findings on 4,7; clean 1,2,3,5,6,8; n/a none |
| 第一轮 | CORRECTNESS-2@S13 | 要求修改：在设置中多选遗忘已学习的词并不会移除它们（Engines.removeWords 调用到 UserModel.forget 的仅限单个词的规则所致），而且大批量遗忘可能在重启后被悄无声息地撤销。 | Checklist: findings on 2,7,8; clean 1,3,4,5,6; n/a none |
| 第一轮 | GENERIC-1@S13 | 建议修改后批准：没有 MUST FIX。两个 SHOULD FIX 发现是词表的持久性，以及对导入输入的无上限内存分配；两者影响范围都有限，修复也简单。 | Checklist: findings on 4,5,7; clean 1,2,3,6,8; n/a none |
| 第一轮 | CORRECTNESS-1@S14 | 建议修改后批准：有三个 SHOULD FIX 发现（成对符号未闭合期间每次按键一次 binder 往返、数字转换误判，以及上下文记忆的键比其 KDoc 所述更粗粒度），没有 MUST FIX。 | Checklist: findings on 5,7; clean 1,2,3,4,6,8; n/a none |
| 第一轮 | CORRECTNESS-2@S14 | 建议修改后批准：没有 MUST FIX；光标位于 0 时的成对符号泄漏、每次按键一次的往返调用以及引号状态反转，都是影响有限的真实缺陷，应在发布前修复。 | Checklist: findings on 5,7; clean 1,2,3,4,6,8; n/a none |
| 第一轮 | GENERIC-1@S14 | 建议修改后批准：没有 MUST FIX：问题包括括号内每个字符一次 IPC 调用、跨输入框的上下文泄漏、一个时间窗口很窄的光标竞态、一个可能绕过屏蔽词的问题，以及一个与区域设置相关的数字 bug，每个的影响范围都有限。 | Checklist: findings on 5,7; clean 1,2,3,4,6,8; n/a none |
| 第一轮 | CORRECTNESS-1@S15 | 建议修改后批准：测试全面，并与其覆盖的代码相符；发现的问题是快捷短语解析器中一个可能丢弃用户行的缺口，以及若干测试缺口，影响范围都有限。 | Checklist: findings on 3,8; clean 4,5,6; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-2@S15 | 建议修改后批准：测试细致，并与被测代码相符，但损坏防护漏掉了一个在按键时可能抛异常的查找，并且把查找时出现的格式错误算作可接受。 | Checklist: findings on 7,8; clean 3,4,6; n/a 1,2,5 |
| 第一轮 | GENERIC-1@S15 | 建议修改后批准：测试全面，并与其执行的代码相符；两个 SHOULD FIX 项是：构建从未声明的一个 Test 任务输入，以及一条未经测试的解析路径，它会生成测试声称不应存在的空短语条目。 | Checklist: findings on 8; clean 3,4,5,6; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-1@S16 | 建议修改后批准：没有测试断言错误的行为，但 pack 和 layer-prior 测试以及 pick 循环并未完整检查其名称和注释所描述的内容。 | Checklist: findings on 5; clean 3,6,8; n/a 1,2,4,7 |
| 第一轮 | CORRECTNESS-2@S16 | 建议修改后批准：测试全面，并与被测代码相符；有一个测试的后半部分什么也没有断言，因为它的准备步骤保留了 `.told` 标记，另外几个测试比其名称所说的要弱。 | Checklist: findings on none; clean 3,5,7,8; n/a 1,2,4,6 |
| 第一轮 | GENERIC-1@S16 | 建议修改后批准：测试全面，并符合优先用 fake 而非 mock 以及单线程的约定；唯一真正的缺口是 `Engines` 中未经测试的 T9 装配，其余都是小的测试规范性修正。 | Checklist: findings on 8; clean 2,3,4,5,6,7; n/a 1 |
| 第一轮 | CORRECTNESS-1@S17 | 建议修改后批准：测试通过摘要校验真实的 libime 和 zstd 文件，并将模型与一个朴素的参考实现进行比较，但有一个测试是因为触发了错误的防护才通过的，针对恶意构造导入的 trie 防护没有测试，而且没有测试覆盖到 reranker 的上下文路径。 | Checklist: findings on 4; clean 3,5,8; n/a 1,2,6,7 |
| 第一轮 | CORRECTNESS-2@S17 | 建议修改后批准：测试以 libime 写出的测试夹具和一个朴素的参考前向计算为准进行检查，但有一个测试是出于错误的原因通过的，而且生产环境的九键配置（含模糊音）、码表提示和构词输出，以及 Scorer 的上限都没有测试。 | Checklist: findings on 8; clean 3,4; n/a 1,2,5,6,7 |
| 第一轮 | GENERIC-1@S17 | 建议修改后批准：测试是确定性的，大多也很精确，但有一个测试测的分支与其名称所说的不同，三个循环在出现回归时可能让 CI 挂起，而且针对不可信文件的 trie 防护没有测试。 | Checklist: findings on 4,8; clean 3,5; n/a 1,2,6,7 |
| 第一轮 | CORRECTNESS-1@S18 | 建议修改后批准：没有 MUST FIX。有三个测试没有检查其名称所描述的行为，其中一个缺口是精排器（refiner）对密码字段的保证，而该保证仍绑定在已移除的云端钩子上。 | Checklist: findings on 5,8; clean 3; n/a 1,2,4,6,7 |
| 第一轮 | CORRECTNESS-2@S18 | 建议修改后批准：这些测试与其所覆盖的代码一致，但其中一个把一个会丢弃先前上下文的笔画查询选词结果固定了下来，而密码字段精排测试检查的是一个已移除的接缝（seam），而不是 PRIVACY.md 所承诺的内容。 | Checklist: findings on 5; clean 3,8; n/a 1,2,4,6,7 |
| 第一轮 | GENERIC-1@S18 | 建议修改后批准：测试全面且具有确定性（固定时钟、每个测试使用独立实例），但密码字段精排器测试检查的是随云端构建一起移除的路径，而精排器关于空上下文的隐私承诺没有得到断言。 | Checklist: findings on 5,8; clean 4,6; n/a 1,2,3,7 |
| 第一轮 | CORRECTNESS-1@S19 | 建议修改后批准：这些测试与我追踪过的代码路径一致，但其中三个在其名称所指的回归出现时也不会失败，另有两个测试把一次丢失的按键和一次选择键偏移固定了下来。 | Checklist: findings on 3,8; clean 4,5; n/a 1,2,6,7 |
| 第一轮 | CORRECTNESS-2@S19 | 建议修改后批准：这些测试与码表引擎的行为一致，但即使移除它们所针对的防护，若干断言仍会通过：更新版本的记录类型、“码表中已有该项”的检查，以及短拼音自动上屏。 | Checklist: findings on 3; clean 5,7,8; n/a 1,2,4,6 |
| 第一轮 | GENERIC-1@S19 | 建议修改后批准：测试全面且与所测代码一致，但没有任何测试覆盖在码表中选中的共享词；一旦用户在拼音中遗忘或删除该词，它会在重启后重新出现。 | Checklist: findings on 3,8; clean 5; n/a 1,2,4,6,7 |
| 第一轮 | CORRECTNESS-1@S20 | 建议修改后批准：断言与我追踪过的代码路径一致，顶栏（bar）、剪贴板和空闲界面测试是正确的。有三个缺口使相应行为未被检查：压缩（compaction）失败后重试前的等待、在多个读音下遗忘某个文本，以及包名路径检查。 | Checklist: findings on 3,4,8; clean 5,7; n/a 1,2,6 |
| 第一轮 | CORRECTNESS-2@S20 | 建议修改后批准：测试正确且与生产代码一致，但压缩退避测试无法检测到回归，而多读音的 `forgetText` 路径没有测试。 | Checklist: findings on 8; clean 3,4,5; n/a 1,2,6,7 |
| 第一轮 | GENERIC-1@S20 | 建议修改后批准：测试大体正确且与所测代码一致，但压缩退避测试（UserStoreTest.kt:314）无论退避是否存在都会通过。 | Checklist: findings on 3,8; clean 4,5,6; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-1@S21 | 建议修改后批准：经追踪这些测试是正确的，但其中一个掩盖了位置 0 处一个真实的配对遗忘缺陷，一个把一个已知的光标（caret）缺陷固定了下来，而且配对路径在每次按键时都会对编辑器发起一次不必要的调用。 | Checklist: findings on 7,8; clean 5,6; n/a 1,2,3,4 |
| 第一轮 | CORRECTNESS-2@S21 | 建议修改后批准：测试正确且全面，但其中一个掩盖了位置 0 处的引号状态缺陷，另一个把一个光标缺陷固定了下来。两者的影响范围都有限。 | Checklist: findings on 6,8; clean 5,7; n/a 1,2,3,4 |
| 第一轮 | GENERIC-1@S21 | 建议修改后批准：测试全面且与源码一致，但其中一个掩盖了位置 0 处一次真实遗漏的配对重置，另外两个把错误的光标行为固定了下来，而不是把它标记出来。 | Checklist: findings on 6,8; clean 4,5,7; n/a 1,2,3 |
| 第一轮 | CORRECTNESS-1@S22 | 建议修改后批准：除一个测试外其余测试都是正确的：VoiceHold 的缩写用例只在 JDK 19 或更高版本上通过，因此在 JDK 17 的 CI 中会失败，并掩盖了 VoiceText 的 `\b` 正则在设备上的一个缺陷。 | Checklist: findings on 8 (on device, VoiceHoldSession's use of VoiceHold.text runs under ICU, where the regex behaves differently than on the test JVM); clean 3, 5, 6; n/a 1, 2, 4, 7 |
| 第一轮 | CORRECTNESS-2@S22 | 要求修改：VoiceHoldTest 的缩写断言在项目所用的 JDK 17 上失败，而且它所断言的行为（VoiceText 中的 `\b`）在使其通过的 JDK 21 JVM 与 Android 之间并不相同。 | Checklist: findings on 3,8; clean 5,6; n/a 1,2,4,7 |
| 第一轮 | GENERIC-1@S22 | 建议修改后批准：这些测试相对其源码是正确的，但其中一个测试把 KeyGestureRecognizer 中一个潜在的点击抑制缺陷固定了下来，该缺陷应当修复，而不是被固定。 | Checklist: findings on 8; clean 3,4,5,6; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-1@S23 | 建议修改后批准：该测试框架（harness）从不随应用发布，也没有 MUST FIX 缺陷，但它的主机端运行和发布门禁所衡量的引擎配置与应用实际运行的配置不同（精排器限制、邻近键、学习路径）。 | Checklist: findings on 2,8; clean 3,5,6; n/a 1,4,7 |
| 第一轮 | CORRECTNESS-2@S23 | 建议修改后批准：没有 MUST FIX，但主机端精排器的行为和只覆盖一半范围的结果匹配，都可能在不报任何错误的情况下使所报告的准确率出现偏差。 | Checklist: findings on 8; clean 3,4,6; n/a 1,2,5,7 |
| 第一轮 | GENERIC-1@S23 | 建议修改后批准：仅限主机端的评估工具中有两个 SHOULD FIX 发现：`pinyin` 运行会在应用不精排的情况下进行精排，而且较旧的 BASE 报告会使发布报告出错。两者都不影响 APK。 | Checklist: findings on 2,3,8; clean 4,5,6; n/a 1,7 |
| 第一轮 | CORRECTNESS-1@S24 | 建议修改后批准：测试和工具总体上是可靠的；缺口在于未经测试的选项和命令、某个数据生成器中不起作用的 ü 转换，以及一项不成立的关于研究用途数据的声明。 | Checklist: findings on 5,8; clean 3,4,6; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-2@S24 | 建议修改后批准：我没有发现该分片所发布的内容中存在错误行为，但它的测试从不在 CI 中运行，c0140d6b 中新增的 CLI 选项既没有校验也没有测试，T9 以及 predict-learn/predict-set 代码没有单元测试，并且 README 中的数据声明需要针对 LCCC 加以限定。 | Checklist: findings on none; clean 3,4,5,6,8; n/a 1,2,7 |
| 第一轮 | GENERIC-1@S24 | 要求修改：源自 LCCC 的评估集（它们同时还用于发布门禁和语言模型（LM）权重的选择）与 README 中“未使用任何仅供研究的数据”的说法相矛盾。 | Checklist: findings on 5; clean 3,4,6,8; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-1@S25 | 建议修改后批准：JNI 连接和内核的边界检查是正确的；原生内核没有一致性测试，而引擎异常时的回退路径会使会话与面板不同步。 | Checklist: findings on 1,2,6; clean 3,4,5,7,8; n/a none |
| 第一轮 | CORRECTNESS-2@S25 | 建议修改后批准：通往 Kotlin 引擎的 JNI 桥接与其 Kotlin 对应实现一致，并保持了既定目标（离线、在敏感字段中关闭 `learning`）。引擎异常路径会使会话与面板不同步，而且新增的原生代码没有测试。 | Checklist: findings on 3,7,8; clean 1,2,4,5,6; n/a none |
| 第一轮 | GENERIC-1@S25 | 建议修改后批准：JNI 胶水代码和原生内核按现有写法是正确的，但整个重排器所依赖的内核没有针对其 JVM 参考实现的测试，而且一个持续失败的引擎会在每次按键时都向日志写入一条堆栈跟踪，造成日志泛滥。 | Checklist: findings on 2,8; clean 1,3,4,5,6,7; n/a none |
| 第一轮 | CORRECTNESS-1@S26 | 建议修改后批准：该 addon 很精简，把按键处理保留在 ime-core 中，并与其事件和 offer 编号一致。两个 SHOULD FIX 项分别是：在一次较晚发生的重排之后，一次点击可能上屏用户并未点击的词；以及浮动候选窗口中翻页控件的丢失。 | Checklist: findings on 6,8; clean 1,2,3,5,7; n/a 4 |
| 第一轮 | CORRECTNESS-2@S26 | 建议修改后批准：原生桥接是可靠的，并把决策逻辑保留在 ime-core 中；缺陷仅限于导入码表的设置页面、实体键盘的翻页界面、一个精排与点击之间的顺序竞争，以及一处上游的空指针解引用。 | Checklist: findings on 3,6; clean 1,2,4,5,7,8; n/a none |
| 第一轮 | GENERIC-1@S26 | 建议修改后批准：我没有发现 MUST FIX。隐私标志（`PasswordOrSensitive`，它也涵盖无痕字段）以及事件和 offer 编号与 ime-core 一致。主要缺口是浮动栏中的触摸翻页，以及在精排重新排列列表时仅凭索引进行的选词或操作。 | Checklist: findings on 3,6,8; clean 1,2,4,5,7; n/a none |
| 第一轮 | CORRECTNESS-1@S27 | 建议修改后批准：移除 libime 的做法是一致的，应用的每个使用方（模块复制列表、标点头文件、scel2org5）仍能正常解析；主要风险是这些模块失去了子模块的 project() 上下文，这可能会破坏 addon conf 的翻译。 | Checklist: findings on 1,3,8; clean 4,5,6; n/a 2,7 |
| 第一轮 | CORRECTNESS-2@S27 | 建议修改后批准：去掉 libime 后构建的是正确的模块，且 CI 会编译它们。复制过来的顶层文件在版本和 REQUIRED 处理上已与上游不同，并且没有防护措施来防止子模块升级时出现偏离。 | Checklist: findings on 3,8; clean 1,2,4,5,6,7; n/a none |
| 第一轮 | GENERIC-1@S27 | 批准：该分片中 youmo 的改动把 libime 的 pinyin/table/pinyinhelper 从构建中移除。被移除的 prefab 模块已没有任何使用方（已检查应用 CMake、androidengine CMake 和 Gradle）。其余部分是上游胶水代码，只剩下 NIT。 | Checklist: findings on 8; clean 1,2,3,4,5,6; n/a 7 |
| 第一轮 | CORRECTNESS-1@S28 | 建议修改后批准：没有 MUST FIX。SHOULD FIX 项包括：可导出的日志中可能出现用户输入的词、缺少第三方许可证声明，以及一条从不释放等待中工作的模型加载错误路径。 | Checklist: findings on 5,6; clean 1,2,3,4,7,8; n/a none |
| 第一轮 | CORRECTNESS-2@S28 | 建议修改后批准：两个有文档记录的补丁和有文档记录的上游更新路径证明了内置（vendoring）sherpa-onnx 是合理的，按住说话的各条路径会在抬起、取消、字段切换和分离（detach）时关闭麦克风；缺口在于用户输入的词被写入 logcat、模型加载抛出 `Error` 时会挂起，以及缺少许可证署名。 | Checklist: findings on 5,6; clean 1,2,3,4,7,8; n/a none |
| 第一轮 | GENERIC-1@S28 | 建议修改后批准：没有 MUST FIX。语音会话生命周期中的两个并发缺陷都可能丢失听写文本或打乱其顺序，而 toList 竞争还可能使识别器的内存无法释放；应当补上 onnxruntime 的供应链缺口。 | Checklist: findings on 2,3,4,6,8; clean 1,5,7; n/a none |
| 第一轮 | CORRECTNESS-1@S29 | 建议修改后批准：我没有发现 MUST FIX。就我追踪的范围而言，引擎桥接、JNI 签名、keep 规则、PasswordOrSensitive 限制以及迁移的幂等性都是正确的。SHOULD FIX 项是一个会丢失码表设置的迁移边界情况，以及 EngineBridge 的数据安全逻辑缺少测试。 | Checklist: findings on 1,5; clean 2,3,4,6,7,8; n/a none |
| 第一轮 | CORRECTNESS-2@S29 | 建议修改后批准：JNI 契约、R8 keep 规则、迁移幂等性和敏感字段限制都经过端到端检查确认无误；仅有的真正问题是调试日志中记录了输入的文本，而这些日志会出现在公开的错误报告中，以及 EngineBridge 中未经测试的文件处理逻辑。 | Checklist: findings on 5; clean 1,2,3,4,6,7,8; n/a none |
| 第一轮 | GENERIC-1@S29 | 建议修改后批准：没有 MUST FIX。两个 SHOULD FIX 项是记录密码文本的详细事件日志，以及在缺少 `conf/` 时失败的 libime 配置迁移。线程限定、JNI 边界检查以及对上下文和学习的密码/敏感字段限制都是可靠的。 | Checklist: findings on 1,5,7,8; clean 2,3,4,6; n/a none |
| 第一轮 | CORRECTNESS-1@S30 | 要求修改：默认开启的 ClearURLs 路径会对每个复制链接的查询部分先解码再重新编码，因此带有编码后的 `&`、`+`、嵌套 URL 或尾随文本的链接会在无任何提示的情况下被破坏。 | Checklist: findings on 1,2,6; clean 3,4,5,7,8; n/a none |
| 第一轮 | CORRECTNESS-2@S30 | 要求修改：内置的 ClearURLs 默认开启，并会对每个复制的 http(s) 链接解码两次，因此带有编码后的 `&`、`+` 或空格的链接会被改写成不同的含义，而剪贴板建议粘贴的正是改写后的链接。 | Checklist: findings on 1,2,3,6; clean 4,5,7,8; n/a none |
| 第一轮 | GENERIC-1@S30 | 要求修改：URL 清理是内置的且默认开启，它会改写复制的链接及其后面的任何文本，而不只是其中的跟踪参数。 | Checklist: findings on 1,2,3,5,6,7; clean 4,8; n/a none |
| 第一轮 | CORRECTNESS-1@S31 | 建议修改后批准：没有 MUST FIX；三个 SHOULD FIX 缺陷各自只影响一条狭窄的路径：一个标注错误的振动级别、一个包的第二次导入失败，以及引擎错过一次重新加载时丢失的自定义短语编辑。 | Checklist: findings on 3,6,8; clean 1,2,4,5,7; n/a none |
| 第一轮 | CORRECTNESS-2@S31 | 建议修改后批准：没有 MUST FIX。风险在于用户数据的持久性：写入没有执行 fsync，而且引擎的写入可能覆盖编辑器已保存的短语。包名检查在首次导入和替换之间不一致，并且隐藏设置无法重置。 | Checklist: findings on 1,3,6,8; clean 2,4,5,7; n/a none |
| 第一轮 | GENERIC-1@S31 | 建议修改后批准：三个 SHOULD FIX 发现（首次解锁前缺少弹出框覆盖配置、无法更改的隐藏设置、泄漏的 provider 流），没有 MUST FIX。 | Checklist: findings on 3,8; clean 1,2,4,5,6,7; n/a none |
| 第一轮 | CORRECTNESS-1@S32 | 建议修改后批准：码表导入（在引擎自身的读取检查之后进行原子重命名、防止覆盖其他输入法文件的防护、删除时清理引擎数据）和主题修复都是正确的；在停顿精排运行期间，候选菜单针对过期索引的防护并不完整。 | Checklist: findings on 4,6,8; clean 1,2,3,5,7; n/a none |
| 第一轮 | CORRECTNESS-2@S32 | 建议修改后批准：没有 MUST FIX。在依赖长按遗忘和迁移之前，应当修复候选索引竞争问题，并补上主题和码表移动操作所缺少的测试。 | Checklist: findings on 4,6; clean 1,2,3,5,7,8; n/a none |
| 第一轮 | GENERIC-1@S32 | 要求修改：主题导入会按照取自不受信任 zip 的路径写入文件，因此一个共享的主题可以把文件放到主题目录之外。 | Checklist: findings on 2,4,6; clean 1,3,5,7,8; n/a none |
| 第一轮 | CORRECTNESS-1@S33 | 建议修改后批准：没有 MUST FIX；最重要的修复是可见密码字段中的自动配对（auto-pairs）（以及语音），其次是此次改动在主线程上新增的逐事件 IPC 调用。 | Checklist: findings on 5,6,7; clean 1,2,3,4,8; n/a none |
| 第一轮 | CORRECTNESS-2@S33 | 建议修改后批准：没有 MUST FIX。主要缺陷是可见密码字段的缺口（自动配对默认开启），以及键盘隐藏后再次显示时丢失的引擎上下文。 | Checklist: findings on 5,8; clean 1,2,3,4,6,7; n/a none |
| 第一轮 | GENERIC-1@S33 | 建议修改后批准：没有 MUST FIX；缺口在于自动配对和语音在可见密码字段中的情况、上下文记忆的键，以及每个硬件按键一次的 IPC。 | Checklist: findings on 4,5,7,8; clean 1,2,3,6; n/a none |
| 第一轮 | CORRECTNESS-1@S34 | 建议修改后批准：IdleUiPolicy、ReturnKeyAppearance 以及内置语音按钮的改动保持了原有语义，并符合离线目标；剩余问题是一个密码限制缺口，以及影响有限的界面竞争问题。 | Checklist: findings on 5,6,8; clean 1,2,3,4,7; n/a none |
| 第一轮 | CORRECTNESS-2@S34 | 建议修改后批准：没有任何一项发现会导致错误行为并以损坏状态发布。语音限制在可见密码字段上的缺口，以及窄屏上的工具栏宽度问题，都是真实但有限的缺陷，值得修复。 | Checklist: findings on 5,6,8; clean 1,2,3,7; n/a 4 |
| 第一轮 | GENERIC-1@S34 | 建议修改后批准：没有 MUST FIX；唯一的 SHOULD FIX 是语音检查在可见密码字段上存在的一个狭窄隐私缺口。 | Checklist: findings on 4,5,8; clean 1,2,3,6,7; n/a none |
| 第一轮 | CORRECTNESS-1@S35 | 建议修改后批准：youmo 在此处的自身改动（视图作用域的 `imeScope`，它保留了 Main.immediate 调度器；回车键外观和标签；翻页按钮标签）是正确的根因修复，且所有 `onReturnDrawableUpdate` 重写都已更新。两个缺陷是继承自上游的翻页和下翻页缺陷，youmo 的引擎可能触发它们，但影响有限。 | Checklist: findings on 8; clean 1,2,3,5,6,7; n/a 4 |
| 第一轮 | CORRECTNESS-2@S35 | 建议修改后批准：youmo 在本分片中的修改正确，并且与其调用方一致；继承下来的分页差一错误（off-by-one）、空网格向下翻页时的崩溃，以及按过期索引进行选择的竞态，都是真实存在的缺陷，但影响范围有限。 | Checklist: findings on 6; clean 1,3,5,7,8; n/a 2,4 |
| 第一轮 | GENERIC-1@S35 | 建议修改后批准：没有 MUST FIX 级别的发现：分页差一错误、候选词点击与停顿重排之间的竞态，以及空列表向下翻页时的崩溃，都是真实问题，但范围很窄。 | Checklist: findings on 6,8; clean 1,2,3,4,5,7; n/a none |
| 第一轮 | CORRECTNESS-1@S36 | 建议修改后批准：新的编辑衔接代码（InputConnectionEditor、KeyEventRelay、EditorInfoTraits、imeScope）正确且有测试；唯一真实的缺陷是，文本编辑面板的剪切和 Backspace 跳过了键盘路径会执行的 ContextMemory 更新。 | Checklist: findings on 8; clean 1,2,3,4,5,6,7; n/a none |
| 第一轮 | CORRECTNESS-2@S36 | 批准：本分片中的 youmo 代码保持了上游行为，修复了两个上游的按键转发缺陷，并且在 minSdk 上有测试；仅有的发现都是 NIT。 | Checklist: findings on 1, 6; clean 2, 3, 4, 5, 7, 8; n/a none |
| 第一轮 | GENERIC-1@S36 | 建议修改后批准：没有 MUST FIX。就我所读到的部分而言，youmo 特有的编辑衔接代码（InputConnectionEditor 在 API 23 上按码点测量、KeyEventRelay、EditorInfoTraits）是正确的。真实的缺陷是文本编辑面板绕过了 ContextMemory，以及两个罕见的剪贴板窗口竞态，其中一个可能导致输入法崩溃。 | Checklist: findings on 6,7,8; clean 2,3,4,5; n/a 1 |
| 第一轮 | CORRECTNESS-1@S37 | 建议修改后批准：我没有发现 MUST FIX 问题。真实的缺陷是新的 T9 切换现在会走到一个主线程上的 `runBlocking`，以及在 vivo 变通方案下按住说话的指针跟踪错误；此外还缺少针对字母布局映射的测试。 | Checklist: findings on 6,7; clean 1,2,3,4,5,8; n/a none |
| 第一轮 | CORRECTNESS-2@S37 | 建议修改后批准：没有 MUST FIX；vivo 指针 id 不匹配以及把取消当作松开处理是真实的缺陷，但影响范围有限；新的应用侧判定逻辑没有测试。 | Checklist: findings on 8; clean 1,2,3,4,5,6,7; n/a none |
| 第一轮 | GENERIC-1@S37 | 建议修改后批准：没有 MUST FIX。问题包括：密码框中具有误导性的麦克风标记、文本版构建中过期的 `Voice` 设置、vivo 变通方案下错误的指针跟踪、系统取消时仍会输入文字，以及一个未经测试的按键动作判定。 | Checklist: findings on 1,2,3,5,6,7,8; clean 4; n/a none |
| 第一轮 | CORRECTNESS-1@S38 | 建议修改后批准：选择器、符号面板和九键的改动按描述工作。不设上限的最近使用列表和不一致的空滑动行为是真实缺陷，但影响有限，这里没有任何问题达到 MUST FIX 级别。 | Checklist: findings on 3,5,7; clean 1,4,6,8; n/a 2 |
| 第一轮 | CORRECTNESS-2@S38 | 建议修改后批准：没有 MUST FIX。最大的问题是最近使用列表没有上限、会记录密码框中的符号，以及新的一行去掉了快捷短语入口。 | Checklist: findings on 5,7,8; clean 1,3,4,6; n/a 2 |
| 第一轮 | GENERIC-1@S38 | 建议修改后批准：没有 MUST FIX。SHOULD FIX 级别的缺陷是：最近使用分区没有上限、滑动覆盖对 Shift/Caps 的处理，以及选择器首次打开时可能按完整列表进行布局。 | Checklist: findings on 1,5,7,8; clean 3,4,6; n/a 2 |
| 第一轮 | CORRECTNESS-1@S39 | 建议修改后批准：没有发现会破坏既定意图的问题，但 `ceil` 修复使得在常见手机上默认的 "e" 弹出框中可以触发顶行选择偏移；而在弹出框中选中多字符项后，单次 Shift 仍保持开启。 | Checklist: findings on 1,4,8; clean 3,5,6,7; n/a 2 |
| 第一轮 | CORRECTNESS-2@S39 | 要求修改：FcitxDataProvider 在解析文档 id 时没有进行规范化，因此被授予 Youmo 文件夹访问权限的应用可以读取输入法私有的剪贴板和输入数据，这违背了 PRIVACY.md。 | Checklist: findings on 1,4,5,8; clean 2,3,6,7; n/a none |
| 第一轮 | GENERIC-1@S39 | 要求修改：FcitxDataProvider 允许任何持有目录树授权的应用通过 `..` docId 访问应用内部的剪贴板和学习词数据。 | Checklist: findings on 1,4,5,8; clean 2,3,6,7; n/a none |
| 第一轮 | CORRECTNESS-1@S40 | 建议修改后批准：没有 MUST FIX。主要问题是应用语言选择器与系统的语言列表不一致，以及缺少数据来源致谢；除此之外，本分片中 youmo 的改动达到了预期目的。 | Checklist: findings on 4,5,8; clean 1,2,3,6; n/a 7 |
| 第一轮 | CORRECTNESS-2@S40 | 建议修改后批准：所有发现都不属于 MUST FIX。用户最容易看到的两个缺陷是重建时 intent 被再次处理，以及按应用设置的语言不匹配；其余是生命周期边缘情况、一个缺失的测试和一些清理工作。 | Checklist: findings on 3,4,6,8; clean 1,2,5; n/a 7 |
| 第一轮 | GENERIC-1@S40 | 建议修改后批准：没有 MUST FIX；SHOULD FIX 项包括：语言选择器中的错误状态、受保护的列表项可以被滑动删除、导出的 activity 未校验路由 parcel，以及两条范围很窄的崩溃或写入丢失路径。 | Checklist: findings on 4,6,8; clean 1,2,3,5; n/a 7 |
| 第一轮 | CORRECTNESS-1@S41 | 建议修改后批准：没有确定会导致生产环境故障的问题，但可取消的重新加载可能让键盘覆盖自定义短语的编辑，而且导入提示和包层默认值在可触达的流程中行为异常。 | Checklist: findings on 6; clean 1,2,3,4,5,8; n/a 7 |
| 第一轮 | CORRECTNESS-2@S41 | 建议修改后批准：所有发现都不属于 MUST FIX。SHOULD FIX 项是影响范围有限的界面和设置缺陷：重复出现的导入对话框、一个包被移到了基础层、自定义短语的保存丢失或未重新加载，以及以文件名为键进行包替换。 | Checklist: findings on 6; clean 1,2,3,4,5,8; n/a 7 |
| 第一轮 | GENERIC-1@S41 | 建议修改后批准：没有 MUST FIX。SHOULD FIX 级别的发现是自定义短语保存/导出和词典导入路径中的真实缺陷，每个的影响范围都有限（编辑丢失或延迟生效、文件描述符泄漏、重复提示、罕见崩溃）。 | Checklist: findings on 2,6,8; clean 3,4,5,7; n/a 1 |
| 第一轮 | CORRECTNESS-1@S42 | 建议修改后批准：没有 MUST FIX。重写后的标点编辑器在卡片的按键改变时会把该键的标点顺序排错，逐次保存每个编辑时既不保证顺序也没有错误处理，并且可能承诺可以在多个标点之间选择，而实际输入时并不提供这种选择。 | Checklist: findings on 2,6,8; clean 1,3,4,5; n/a 7 |
| 第一轮 | CORRECTNESS-2@S42 | 建议修改后批准：没有 MUST FIX。主要问题是新的标点页面承诺可以为同一个键在多张卡片之间进行选择，而引擎从不提供这种选择。 | Checklist: findings on 2,4,6,8; clean 1,3,5; n/a 7 |
| 第一轮 | GENERIC-1@S42 | 建议修改后批准：没有 MUST FIX。SHOULD FIX 项包括：用户输入的名称导致崩溃、未经测试的放置规则、无序的逐次编辑保存，以及没有检查键的长度，影响都限于设置界面。 | Checklist: findings on 2,6; clean 1,3,4,5,8; n/a 7 |
| 第一轮 | CORRECTNESS-1@S43 | 建议修改后批准：新的设置页面与原生选项键和枚举名称一致，用户词的编辑和删除路径正确；但设置搜索对码表选项的跳转有误，而导入码表的页面显示的是拼音的选项。 | Checklist: findings on 3,4,8; clean 1,2,5,6; n/a 7 |
| 第一轮 | CORRECTNESS-2@S43 | 要求修改：在应用自己的输入法页面（双拼方案、页大小、码表词组选项、英文单词提示）上点击任意选项都会崩溃，因为这些 ListPreference 没有 key。 | Checklist: findings on 3,4,8; clean 2,5,6,7; n/a 1 |
| 第一轮 | GENERIC-1@S43 | 建议修改后批准：没有 MUST FIX：InputMethodSettings 的选项键与 androidengine.h 和 androidkeyboard.h 一致，搜索索引无法写入用户偏好设置；不设上限的导入读取和未经测试的排序逻辑值得修复。 | Checklist: findings on 2,7; clean 1,3,4,5,6,8; n/a none |
| 第一轮 | CORRECTNESS-1@S44 | 批准：本分片中 youmo 的改动（实时尺寸预览、搜索跳转到外观标签页、Material 3 样式重做、设置页面上的完成图标）与其注释描述的一致；新的发现只有 NIT，而索引缺陷属于上游代码。 | Checklist: findings on 8; clean 1,3,4,5,6; n/a 2,7 |
| 第一轮 | CORRECTNESS-2@S44 | 批准：youmo 在本分片中的改动按预期工作；针对这些改动的 2 个发现都是 NIT（一个监听器重复了 ThemeManager 的工作，偏好设置键是硬编码字符串），值得修复的缺陷都在已有的上游代码中。 | Checklist: findings on 3,7; clean 1,4,5,6,8; n/a 2 |
| 第一轮 | GENERIC-1@S44 | 建议修改后批准：没有 MUST FIX。SHOULD FIX 级别的发现是错误的主题选中标记、标签错误的删除操作，以及在主线程上读取图片，均继承自上游。youmo 的改动（预览跟随尺寸设置、打开搜索结果所在的标签页、迁移到 AppCompat 和 SwitchPreferenceCompat、设置页面的完成图标）是正确的；其中唯一的问题是一个多余的监听器。 | Checklist: findings on 6,7,8; clean 1,2,3,4,5; n/a none |
| 第一轮 | CORRECTNESS-1@S45 | 建议修改后批准：没有任何改动会导致确定或大范围的错误行为。SHOULD FIX 项是：fcitx 的 locale 滞后于应用所选的语言、无效的 `Locales.language` 回退、薄弱的 zip-slip 防护和 FD 泄漏，以及缺少针对 `picked()` 的测试。 | Checklist: findings on 4,5,6,7,8; clean 1,2,3; n/a none |
| 第一轮 | CORRECTNESS-2@S45 | 建议修改后批准：没有 MUST FIX。本分片中的工具类改动按描述工作（`styledColor` 的替换没有留下任何对 splitties 版本的导入，`queryFileName` 会清理调用方用来写文件的文件名），但 `NaiveDustman.reset` 可能丢失重新添加的条目，而且 zip 解压器的路径检查比较薄弱。 | Checklist: findings on 4,5,6,8; clean 1,2,3,7; n/a none |
| 第一轮 | GENERIC-1@S45 | 建议修改后批准：没有 MUST FIX。youmo 的改动（路径安全的 `queryFileName`、`openUrl` 回退、`Locales.picked`、Material 版的 `styledColor`、CoreLog 输出端）是可靠的。SHOULD FIX 项是工具类中影响有限的缺陷。 | Checklist: findings on 4,6; clean 1,2,3,5,7,8; n/a none |
| 第一轮 | CORRECTNESS-1@S46 | 要求修改：备份配置会把输入的词、剪贴板和设置数据发送到设备之外，这与 PRIVACY.md 以及 README 中关于输入内容永远不会离开手机的承诺相矛盾。 | Checklist: findings on 1,4,5,8; clean 3,6; n/a 2,7 |
| 第一轮 | CORRECTNESS-2@S46 | 要求修改：`allowBackup="true"` 加上几乎不排除任何内容的云备份规则，会把剪贴板历史和学习词上传到手机之外，这违背了 PRIVACY.md 中的承诺。 | Checklist: findings on 1,3,4,5; clean 6,8; n/a 2,7 |
| 第一轮 | GENERIC-1@S46 | 要求修改：默认的 Auto Backup 会把用户的学习词、剪贴板历史和自定义短语发送到手机之外，这与 PRIVACY.md 相矛盾。 | Checklist: findings on 1,4,5,8; clean 3,6; n/a 2,7 |
| 第一轮 | CORRECTNESS-1@S47 | 批准：所有发现都是 NIT：未使用的资源、生成器输出中的噪声以及 lint 级别的 XML 规范问题，这些都不会改变行为，也不影响 README/PRIVACY 的意图。 | Checklist: findings on 8; clean 1,3,4,5,7; n/a 2,6 |
| 第一轮 | CORRECTNESS-2@S47 | 批准：只有 NIT（无用的 drawable 和次要的资源规范问题）；检查过的每一个资源引用、主题属性和控件约定都能正确解析。 | Checklist: findings on 8; clean 1,3,4,5; n/a 2,6,7 |
| 第一轮 | GENERIC-1@S47 | 批准：本分片是静态资源，只有 NIT 级别的发现；唯一的 SHOULD FIX 是另一个分片中 PRE-EXISTING 的上游代码。 | Checklist: findings on 3,6,8; clean 1,4,5,7; n/a 2 |
| 第一轮 | CORRECTNESS-1@S48 | 建议修改后批准：没有错误的运行时行为，但备份摘要隐瞒了剪贴板历史（包括带密码标记的条目）会写入该文件这一点，而且生成的 locale 配置把不完整、过时的翻译作为应用语言提供。 | Checklist: findings on 1,3,5,8; clean 4; n/a 2,6,7 |
| 第一轮 | CORRECTNESS-2@S48 | 建议修改后批准：资源一致，可以安全构建；两个 SHOULD FIX 项是向用户展示了只翻译了一部分的 locale，以及备份摘要没有提到剪贴板历史。 | Checklist: findings on 1,5,8; clean 3,4; n/a 2,6,7 |
| 第一轮 | GENERIC-1@S48 | 建议修改后批准：所有发现都不会破坏行为。两个 SHOULD FIX 项是备份摘要中的隐私披露缺口，以及系统把不完整的翻译作为应用语言提供。 | Checklist: findings on 1,3,5,8; clean 4,7; n/a 2,6 |
| 第一轮 | CORRECTNESS-1@S49 | 建议修改后批准：没有 MUST FIX。若干设备端测试和评估运行器从未针对 engine-* 输入法名称进行更新，而且评估运行器丢弃了样本上下文，并在 25M 重排之前就读取候选词。 | Checklist: findings on 3,6,8; clean 4,5; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-2@S49 | 建议修改后批准：本分片中没有生产缺陷。设备端测试套件仍在使用应用已移除的 libime 输入法名称，因此会失败；另有一个主题迁移测试并未测试其名称所指的情形却能通过。 | Checklist: findings on 3,5,6,8; clean 4; n/a 1,2,7 |
| 第一轮 | GENERIC-1@S49 | 建议修改后批准：这些问题都不会破坏生产环境。问题包括：一个什么都不检查也能通过的测试、测试覆盖缺口，以及可能与应用自身引擎冲突或让开发者设备处于被改动状态的设备测试。 | Checklist: findings on 3,5,6,8; clean 4; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-1@S50 | 建议修改后批准：测试很全面（伪编辑器在 API 23 和当前版本上都以真实的 `BaseInputConnection` 为准进行校验），但它们没有覆盖一个破坏剪贴板内容的缺陷和一个标点切换竞态，而且有一个测试把一个已知的大小写敏感缺陷固定了下来。 | Checklist: findings on 6; clean 3,4,5,8; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-2@S50 | 建议修改后批准：没有 MUST FIX。三个 SHOULD FIX 项：导入时拒绝大写扩展名、标点更新可能应用过期的映射，以及上游备份会被当作旧版（legacy）导入而通过。 | Checklist: findings on 3,6,8; clean 4,5; n/a 1,2,7 |
| 第一轮 | GENERIC-1@S50 | 建议修改后批准：测试合理且针对性强，但有一个断言永远不会失败，还有一个测试把用户可能遇到的大小写敏感导入缺陷固定了下来。 | Checklist: findings on 6,8; clean 3,4,5; n/a 1,2,7 |
| 第一轮 | CORRECTNESS-1@S51 | 建议修改后批准：这些测试和文本 flavor 的桩代码与其覆盖的代码一致，并保证文本版构建中不含麦克风，但输入法页面测试遗漏了 `engine-t9` 及其九键逻辑。 | Checklist: findings on 1,8; clean 3,4,5; n/a 2,6,7 |
| 第一轮 | CORRECTNESS-2@S51 | 建议修改后批准：测试与其覆盖的代码一致，但九键设置页面没有测试，而且文本版构建会静默忽略从语音版构建沿用过来的 `Voice` 长按设置。 | Checklist: findings on 3,8; clean 1,4,5; n/a 2,6,7 |
| 第一轮 | GENERIC-1@S51 | 建议修改后批准：没有 MUST FIX。这些发现是测试覆盖缺口（t9 页面、provider 名称的路径防护）、可通过持久化偏好设置触发的文本版构建空操作，以及 CI 未检查的 flavor 一致性。 | Checklist: findings on 2,3,4,8; clean 1,5,6,7; n/a none |
| 第一轮 | CORRECTNESS-1@S52 | 建议修改后批准：就所读代码而言，屏蔽自动机、beam 掩码、带稳定映射引用的锁，以及贯穿 JNI 和 Kotlin 的 n-best 传递都是正确的。剩下的问题是用户词会进入可导出的日志，以及一个未经验证的分词假设。 | Checklist: findings on 5,8; clean 1,2,3,4,6,7; n/a none |
| 第一轮 | CORRECTNESS-2@S52 | 建议修改后批准：对应用的配置而言，屏蔽和 n-best 逻辑是正确的。没有 MUST FIX 级别的发现。两个 SHOULD FIX 发现是新 API 在 greedy/无词表情况下的崩溃，以及被屏蔽的词通过 EncodeBase 泄漏到 logcat。 | Checklist: findings on 4,5; clean 1,2,3,6,7,8; n/a none |
| 第一轮 | GENERIC-1@S52 | 建议修改后批准：阻塞掩码、带锁的 CompletingTokens 缓存，以及 n-best/JNI 管道都是正确的（构造函数签名、保留规则和调用方均已检查）；唯一真正的缺陷是：当用户输入的词含有词表外字符时，这些词会被写入 logcat。 | Checklist: findings on 5; clean 1,2,3,4,6,7,8; n/a none |
| 第一轮 | PERFORMANCE@G1 | 建议修改后批准：没有 MUST FIX：构建和基础设施代码没有给应用增加任何网络路径，下载内容都经过 SHA-256 校验，并会关闭连接。SHOULD FIX 项是构建和 CI 花在重复、串行或未缓存工作上的时间，以及因缺少 baseline profile 导致的冷启动延迟。 |  |
| 第一轮 | TESTING@G1 | 建议修改后批准：构建基础设施没有任何损坏，但有两组现有测试（ime-eval，以及 voice flavor 的编译和测试）从未在 CI 中运行，且 ToolFingerprint 中自定义的缓存键逻辑没有测试。 |  |
| 第一轮 | STYLE@G1 | 建议修改后批准：没有缺陷会破坏构建或产品目标，但未经测试的指纹逻辑、未限定范围的 workflow 权限以及缺失的 onnxruntime 致谢应当修复。 |  |
| 第一轮 | PERFORMANCE@G2 | 建议修改后批准：没有 MUST FIX：该模块组是构建期和发布期使用的工具，从不打包进 APK；SHOULD FIX 项涉及发布任务的吞吐量和可运维性（crawl 抓取的并发度、静默重试、mix 中的热循环）。 |  |
| 第一轮 | TESTING@G2 | 建议修改后批准：测试套件质量不错，但应用构建实际发布时所用的那几条 `pinyin` 和 `table` 调用，以及已发布词包背后的层过滤器，都没有测试。 |  |
| 第一轮 | STYLE@G2 | 建议修改后批准：没有 MUST FIX。五项 SHOULD FIX：命令和标志的接线方式难以扩展；两个词库解析器脆弱或校验宽松（`readings.tsv` 中的一个拼写错误就会把一个词从发布的词典中删除）；`apply.py` 需要 Python 3.12 或更高版本；`engine-data.sh` 不像应用构建那样应用 misreadings（误读条目）。 |  |
| 第一轮 | PERFORMANCE@G3 | 建议修改后批准：我没有发现正确性或泄漏缺陷。SHOULD FIX 项是较少见路径上的主线程卡顿和堆内存占用：码表（table）词组保存、大型导入词典、模型加载和笔画查询。 |  |
| 第一轮 | TESTING@G3 | 建议修改后批准：没有 MUST FIX。三项 SHOULD FIX：一个跨会话的过期 beam 缺陷没有测试；一个测试的关键断言不可能失败；一项导入检查漏掉了引擎在首次使用时会拒绝的内容。 |  |
| 第一轮 | STYLE@G3 | 建议修改后批准：没有 MUST FIX；主要问题是：一个具有误导性的模式检查会在数字编码的码表中吞掉 Escape 键、重复的转义逻辑，以及两个过大的有状态类。我没有在日志中发现 PII，也没有发现 G3 削弱上下文或隐私规则。 |  |
| 第一轮 | PERFORMANCE@G4 | 建议修改后批准：没有 MUST FIX。SHOULD FIX 项包括：解码器逐键路径上的装箱；在括号对内每输入一个字符都要与编辑器往返一次；交互路径上遍历整个词典；以及用户数据保存方式上的两处缺口。 |  |
| 第一轮 | TESTING@G4 | 建议修改后批准：测试全面且针对行为，但有一条断言依赖 JDK 版本、在 Android 上不成立，一个依赖区域设置（locale）的格式没有测试，多音字屏蔽计数器也未经测试。 |  |
| 第一轮 | STYLE@G4 | 建议修改后批准：代码符合该包的纯类、传值进入（values-in）模式，但 AutoPairs 路径绕过了 EditingSession 的线程检查保护，其余都是命名和重复方面的 NIT。 |  |
| 第一轮 | PERFORMANCE@G5 | 建议修改后批准：没有 MUST FIX：这些是无用功，以及主机端评估所测量内容上的缺口，对发布的应用没有影响。 |  |
| 第一轮 | TESTING@G5 | 建议修改后批准：没有 MUST FIX。主要缺口是：CI 从不运行 ime-eval 测试，新选项未经校验，T9 和预测未经测试，发布门禁所依赖的重排序和切片上限行为也未经测试。 |  |
| 第一轮 | STYLE@G5 | 建议修改后批准：没有任何发现属于 MUST FIX。三个 SHOULD FIX 缺陷都局限在这个仅在主机端运行的评估工具内：新的 CLI 选项从未被校验；一个缓存不会缓存不可达的词；一个发布门禁按位置读取表格列。 |  |
| 第一轮 | PERFORMANCE@G6 | 建议修改后批准：没有任何发现属于 MUST FIX；voice flavor 每次按住时都会做可避免的工作（重建未被使用的热词图，以及关闭后仍在解码），并且模型权重被复制后驻留在 Java 堆上。 |  |
| 第一轮 | TESTING@G6 | 建议修改后批准：没有 MUST FIX，但原生桥接层、C++ 矩阵内核和 sherpa-onnx 补丁都没有在 CI 中运行的测试；唯一的例外是 CompletingTokensAreThoseThatMatch，它对照 ForwardOneStep 检查 CompletingTokens，但没有任何 CI 任务运行它。 |  |
| 第一轮 | STYLE@G6 | 建议修改后批准：G6 中没有正确性或隐私缺陷；两项 SHOULD FIX 发现属于可维护性风险：选项默认值在三个配置结构体之间被复制，以及依赖 Kotlin 枚举顺序且没有测试的 offer 位。 |  |
| 第一轮 | PERFORMANCE@G7 | 建议修改后批准：我没有发现破坏正确性的性能缺陷，但复制到堆上的模型权重、一个从未被释放的 Engines 对象、在第一次按键时构造引擎，以及整文件读取用户词典，都会带来可测量的堆内存和延迟开销。 |  |
| 第一轮 | TESTING@G7 | 建议修改后批准：没有 MUST FIX。主要缺口是：一套因移除 libime 而处于损坏状态的模拟器测试套件，以及 service 和 EngineBridge 粘合代码中未经测试的隐私和数据丢失相关决策。 |  |
| 第一轮 | STYLE@G7 | 建议修改后批准：四项 SHOULD FIX 发现（并不总是执行 sync 的原子写入、以字面量复制的级别键、针对应用已不再提供的输入法的插桩测试、详细日志中的已输入文本），没有 MUST FIX。 |  |
| 第一轮 | PERFORMANCE@G8 | 建议修改后批准：没有 MUST FIX；SHOULD FIX 项是：无上限的最近列表、词组导入时主线程上的突发负载、T9 中逐键的视图重建、T9 的急切构造，以及泄漏的导入流。 |  |
| 第一轮 | TESTING@G8 | 建议修改后批准：我没有发现会在生产环境中导致错误行为的问题，但 youmo 新增的若干决策规则（弹出网格尺寸、滑动覆盖、索引副作用、T9 设置页、提供方名称保护）在发布时没有附带项目自身约定所要求的测试。 |  |
| 第一轮 | STYLE@G8 | 建议修改后批准：没有 MUST FIX 发现。主要风险是结构性的：InputMethodSettings 重新声明了引擎的配置键；一个全局的、基于时间的高亮单例代替了导航参数；导入决策逻辑放在 Fragment 中且未经测试；其余发现是较小的重复和风格不一致问题。 |  |
| 第一轮 | PERFORMANCE@G9 | 要求修改：备份规则会把输入日志、学习到的词和剪贴板历史上传到 Google Drive，这与 PRIVACY.md 中这些数据保留在手机上的承诺相矛盾。 |  |
| 第一轮 | STYLE@G9 | 要求修改：备份规则会把学习到的输入数据和剪贴板历史发送到 Google Drive，这与 README 和 PRIVACY.md 中输入内容永不离开手机的承诺相矛盾。 |  |

## 8. 处置（用户决定）

| id | 级别 | 标题 | 处置 |
|---|---|---|---|
| F1 | MUST FIX | Auto Backup 将学习词和剪贴板历史上传云端 | Apply：关闭整个云备份（`android:allowBackup="false"`），而不是只把用户数据排除在备份之外。修复尚未开始。 |
| F2 | MUST FIX | LCCC 派生评测集与 README「不使用仅限科研数据」矛盾 | Apply：降为文档问题。发布的权重和词库都不依赖仅限科研用途的数据，这些数据只用于评测；只修改 README 那句话的表述。见下方复核。 |
| F162 | SHOULD FIX | Engines 单个约 950 行的类承担过多职责 | Apply：要拆分 `Engines`。修复尚未开始。 |
| F730 | SHOULD FIX | 九键与全拼/双拼混用时堆上常驻两套句子模型 | Apply：去掉九键单独的一对句子模型，九键与全拼、双拼共用通用模型。修复尚未开始。 |

### 8.1 批次 1（已修复，已提交并推送，见 8.4）

| id | 处置 |
|---|---|
| F1 | 已修复：`allowBackup="false"` |
| F2 | 已修复：中英文 README 改写；F769 指出两个评测脚本的 docstring 需同步 |
| F3 | 已修复；修复引入 F751，需第 2 轮 |
| F5 | 部分修复：F767 是 `decodeURL` 的剩余部分 |
| F6 | 已修复；F765 是基线上就有的另一缺口 |
| F7 | 已修复 |
| F8 | 已修复；F750 随之可达 |
| F251 | 已修复 |
| F730 | 已修复：九键改用通用模型 |
| F729、F731、F733、F734、F736–F740、F742、F743、F746、F747 | 随 F730 不再适用 |
| F732 | 仍适用 |

构建与测试全部通过（ime-core 1,011、dict-tool 59、ime-eval 72、app 238 个测试，detekt，`assembleTextDebug` arm64-v8a）。修复审查（6 个 stage）新增 F748–F771 共 24 条，全部验证：SHOULD FIX 5、NIT 11、PRE-EXISTING 6、撤回 2。详见 `ensemble-review-youmo-ime-fix1-report.md`。

### 8.2 批次 1 第 2 轮（已提交并推送，见 8.4）

修了 F751、F748、F749、F752、F765、F750、F767、F769；构建和测试全部通过（app 247 个测试）。第 2 轮修复审查（2×CORRECTNESS + TESTING）3 个 stage 都「要求修改」，原因是 F772：F767 的修法改坏了 Google 包装的 `q=https://…` 链接。新增 F772–F780，全部验证：MUST FIX 1（F772）、SHOULD FIX PRE-EXISTING 1（F780）、NIT 6、撤回 1（F774）。已达两轮上限，F772、F780 等你决定。详见 `ensemble-review-youmo-ime-fix2-report.md`。

### 8.3 批次 1 第 3 轮（用户批准的额外一轮，已提交并推送，见 8.4）

修了 F772（`decodeURL` 对跟在 `=` 后面的查询值目标先解码一次）、F780（`listThemes` 跳过名字不合规的主题）、F773（三个 LCCC 数据文件的注释头，数据行未变）。构建和测试全部通过（app 248 个测试）。单个 CORRECTNESS-X 复审结论为批准，新增 3 条 NIT（F781、F782，以及 PRE-EXISTING 的 F783），按 SOP 未做验证。批次 1 没有剩下未解决的 MUST FIX 或已验证的 SHOULD FIX。详见 `ensemble-review-youmo-ime-fix3-report.md`。

### 8.4 批次 1 的提交

批次 1 的改动在 2026-10-08 按主题拆成 8 个提交，直接提交到 `main` 并推送到 `github.com/Sraw/youmo-ime`（`5881d31e..a39dc41d`，快进）。作者和提交者都是仓库里 youmo 提交一贯使用的 `Yunlu Liaozheng <15711730+Sraw@users.noreply.github.com>`，提交说明末尾只有 `Co-Authored-By: Claude Opus 5.5` 一行。每个提交都单独编译通过（C2 另跑了 ime-core 的 1014 个测试），最后一个提交的文件树与三轮验证过的工作区逐字节相同。

| 提交 | 内容 | 条目 |
|---|---|---|
| `9dbbd3ac` | 关闭云备份 | F1 |
| `026f4244` | 一次删除多个学到的词时每个都真正删除；压缩后无计数时写 EMPTIED 记录 | F3、F751 |
| `25c13d8a` | 九键重新使用通用句子模型（撤回 5881d31e 的模型对） | F730 |
| `6ec13708` | ClearURLs 不再破坏链接 | F5、F767、F772 |
| `896e557c` | 文档提供者不越出根目录；`isPlainFileName` | F7、F748、F749、F752 |
| `54755d53` | 主题导入不信任压缩包里的路径 | F6、F765、F780 |
| `dc291b67` | 输入法设置页的列表能打开；默认按钮只在有默认值时显示 | F8、F251、F750 |
| `a39dc41d` | README 和评测集说明 LCCC 数据只用于评测和选参数 | F2、F769、F773 |

### 8.5 批次 2 第 1 轮（worktree `youmo-ime-fix`，基线 `a39dc41d`，未提交）

修了 125 条已验证、有运行时影响的 SHOULD FIX：16 个修复者各管互不重叠的文件，4 个跟进修复者做跨文件改动。F22、F171、F180、F186、F258、F272、F276 只做了安全的部分，等用户决定。构建和测试全部通过（ime-core 1050、app 348、detekt、assembleTextDebug），但 voice flavor 和 lib/sherpa-onnx 还没编译。修复审查 20 个阶段，新增 F784–F882：34 条成立的 SHOULD FIX（18 条有运行时影响）、54 条 NIT、8 条 PRE-EXISTING；F803、F867、F870 被驳回。详见 `ensemble-review-youmo-ime-fix-b2-report.md`。

修复审查报出、尚未处理的 NIT：F753–F764（第 1 轮审查中未选入第 2 轮的）、F775–F779、F781、F782；PRE-EXISTING：F766、F768、F771、F783。

其余条目尚未处置。F162（拆分 `Engines`）尚未开始。

## 9. 工作文件

均为本地工作文件（本分支的 `review/` 下只有合并报告和 4 份修复报告）：

- `ensemble-review-youmo-ime-report.md`：第一轮报告。`ensemble-review-youmo-ime-since-07d2778-report.md`：第二轮报告。本文件：`ensemble-review-youmo-ime-combined-report.md`。
- 第一轮：`ensemble-review-youmo-ime-context.md`、`-diff-S<n>.md`、`-out/`（182 个 stage 原始输出）、`-agg-*.jsonl`、`-final.json`、`-verify-*`。
- 全面验证：`ensemble-review-youmo-ime-vall-list-A<n>.md`（26 份验证清单）、`-vall-out-A<n>.md`（verifier 原始输出）、`-vall-marks.json`、`-vall-results.json`；F2 复核：`-F2-recheck-out.md`。
- 第二轮：`ensemble-review-youmo-ime-since-07d2778-context.md`、`-diff.md`、`-out/`（7 个 stage 原始输出）、`-agg.jsonl`、`-final.json`、`-verify-*`。
