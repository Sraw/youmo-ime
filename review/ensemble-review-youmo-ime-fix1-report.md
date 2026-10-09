# 幽默输入法（youmo-ime）批次 1 修复与修复审查报告（第 1 轮）

> 修复在 git worktree `youmo-ime-fix`（从 `5881d31e` 分出，未提交）。修复审查按 SOP 的 fix review 进行：`since` = 基线 5881d31e，`<ID>` = `youmo-ime-fix1`。合并报告见 `ensemble-review-youmo-ime-combined-report.md`。

## 1. 本批修复了什么

| id | 级别 | 问题 | 怎么修的 |
|---|---|---|---|
| F1 | MUST FIX | Auto Backup 上传学习词和剪贴板历史 | 按用户决定，`AndroidManifest.xml` 设 `android:allowBackup="false"`，关闭整个云备份。`dataExtractionRules` 仍保留（API 31+ 的设备间迁移仍按 `<device-transfer>` 规则进行，按用户决定不改）。Android 11 及以下，设备间迁移也随之关闭。 |
| F2 | 降为文档问题 | README 称不使用仅限科研数据 | 中英文 README 各改一句：发布的词库、语言模型和句子模型都不用仅限科研的数据训练；聊天、对话评测集来自 LCCC，只用于评测和选参数（混合权重和 `Predictor.USER_WEIGHT` 是在含这些集合的分数上调的）。 |
| F3 | MUST FIX | 一次删除多个已学词时一个也删不掉 | 新增 `UserModel.forgetEach`，逐词遗忘、每词写一条单词 FORGOT 记录；`Engines.removeWords` 改用它，删除多个词后压缩用户日志。 |
| F5 | MUST FIX | ClearURLs 二次解码改坏链接 | 在编码态的 query/fragment 上按 `&` 过滤，保留的参数原样写回；没有可删参数时原样返回；`decodeURL` 只在结果仍以 `http(s)%` 开头时继续解码。 |
| F6 | MUST FIX | 主题导入信任 zip 内路径 | 图片只取文件名，拒绝空名、`.`、`..`；主题名含 `/` 或为 `.`、`..` 时拒绝。 |
| F7 | MUST FIX | 文档提供者可越界读写私有数据 | 所有接受文档 id 或父 id 的入口都先规范化并按规范路径逐段检查是否在根目录内；`isChildDocument` 改为逐段比较；新建、复制、移动、改名拒绝含 `/`、`.`、`..` 的名字。 |
| F8 | MUST FIX | 输入法设置页 ListPreference 无 key，点击崩溃 | `Choice` 分支设 `key = item.key`（`isPersistent = false` 不变）。 |
| F251 | SHOULD FIX | AutoPhraseLength 对话框说明挤掉选项列表 | 去掉对话框说明，改为显示在该行摘要里、当前值的下方；为过 detekt 的 LongMethod，摘要逻辑抽成 `choiceSummary`。 |
| F730 | SHOULD FIX | 九键与全拼混用时常驻两套模型 | 按用户决定，撤回 `5881d31e` 中九键单独模型对的部分（`Engines.kt`、`EngineDataPlugin.kt`、`sentence-models.json`），九键改用通用模型；保留测试从 EnginesTest 移到 EnginesModelTest；`af16cd95` 未动。 |

新增或修改的测试：`UserModelTest.wordsForgottenEachAloneAreAllGoneAndReadBackSo`、`EnginesUserWordsTest.wordsTheUserPutTogetherAreRemovedAtOnceForGood`、`EnginesModelTest.theNineKeysReadTheModelsFullPinyinReads`、`ClearURLsTest` 新增 5 个、新增 `ThemeFilesManagerTest`（2 个）和 `FcitxDataProviderTest`（5 个）、`InputMethodSettingsTest` 改为 Robolectric 并新增 2 个。

改动：16 个已跟踪文件（+337/−127）加 2 个新测试文件，diff 1,011 行（`ensemble-review-youmo-ime-fix1-diff.md`）。

## 2. 构建与测试（JDK 21，aarch64 + qemu）

| 命令 | 基线 5881d31e | 修复后 |
|---|---|---|
| `:lib:ime-core:check` | 通过 | 通过，1,011 个测试 |
| `:lib:ime-dict-tool:test`、`:lib:ime-eval:test` | 通过 | 通过，59 + 72 个测试 |
| `detekt` | 通过 | 第一次失败（`InputMethodSettings.kt` 的 `add` 84 行 > 80，由 F8/F251 的修改引起）；做了一次范围内修复（抽出 `choiceSummary`），之后通过 |
| `:app:testTextDebugUnitTest` | 224 个，223 通过，1 跳过 | 238 个，237 通过，1 跳过，0 失败 |
| `:app:koverLogTextDebug` | 行覆盖率 12.89% | 13.24% |
| `:app:assembleTextDebug`（arm64-v8a） | 成功，APK 312.8 MB | 成功，APK 283.0 MB（去掉了九键模型对） |

## 3. 修复审查

- 阵容（SOP 的 fix review）：2×CORRECTNESS、1×TESTING，加上本批修复的 MUST FIX 涉及的其余 rubric 各 1 个：GENERIC、PERFORMANCE、STYLE。共 6 个 `reviewer` stage（claude-opus-5.5），全部「建议修改后批准」，没有 MUST FIX。
- 合并后 24 条（42 个 finding），丢弃置信度 ≤5 的 NIT 2 条。全部条目都验证了（2 个 verifier）：确认 22 条，否定 2 条。条目编号接着前两轮，从 F748 开始。
- 有效问题：SHOULD FIX 5 条，NIT 11 条；另有 6 条 PRE-EXISTING（在基线 5881d31e 上就存在，这次修复没有让它变得可达或更糟）。

| id | 级别 | 共识 | 验证 | 关联修复 | 位置 | 标题 |
|---|---|---|---|---|---|---|
| F748 | SHOULD FIX | 5/6 | ✅ 已确认 | F7 | `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:323` | isPlainDisplayName 与 isPlainFileName 重复 |
| F749 | SHOULD FIX | 5/6 | ✅ 已确认 | F7 | `app/src/test/java/org/fcitx/fcitx5/android/provider/FcitxDataProviderTest.kt:31` | provider 测试复刻映射逻辑，未测 provider 方法本身 |
| F750 | SHOULD FIX | 2/6 | ✅ 已确认 | F8 | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/MyListPreferenceDialogFragment.kt:17` | F8 使列表对话框可打开后，「默认」按钮不起作用 |
| F751 | SHOULD FIX | 2/6 | ✅ 已确认 | F3 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:352` | 多词删除后压缩可留下仅有头部的日志，libime 词被重新导入 |
| F752 | SHOULD FIX | 1/6 | ✅ 已确认 | F6 | `app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManagerTest.kt:22` | 主题导入测试只测谓词，未测 importTheme 的新防护 |
| F753 | NIT | 3/6 | ✅ 已确认 | F1 | `app/src/main/AndroidManifest.xml:26` | 清单注释称数据「留在手机上」，但设备间迁移仍会复制 |
| F754 | NIT | 2/6 | ✅ 已确认 | F6 | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:139` | 主题名被拒时提示「找不到主题 json」，信息不准确 |
| F755 | NIT | 2/6 | ✅ 已确认 | F8 | `app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:45` | 测试辅助类 PlaceholderStrings 被复制了一份 |
| F756 | NIT | 2/6 | ✅ 已确认 | F3 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:352` | 压缩条件 learned.size > 1 计入了未遗忘的条目 |
| F757 | NIT | 1/6 | ✅ 已确认 | F5 | `app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:14` | ClearURLsTest 中关于 Robolectric 用途的注释过时 |
| F758 | NIT | 1/6 | ✅ 已确认 | F251 | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:53` | Choice.message 已改作摘要，名称不再贴切 |
| F759 | NIT | 1/6 | ✅ 已确认 | F7 | `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:187` | isChildDocument 每次调用解析四次规范路径 |
| F760 | NIT | 1/6 | ✅ 已确认 | F3 | `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:421` | compactUser 靠捕获 ISE 处理日志未打开这一预期状态 |
| F761 | NIT | 1/6 | ✅ 已确认 | F251 | `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:304` | 选项摘要在值不在列表中时会显示「null」 |
| F762 | NIT | 1/6 | ✅ 已确认 | F6 | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:153` | 裁剪图路径被拒时，已复制的源图留在 theme/ 中 |
| F764 | NIT | 1/6 | ✅ 已确认 | F7 | `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:82-84` | canonicalFile 抛 IOException 时客户端得到通用异常 |
| F765 | SHOULD FIX PRE | 2/6 | ✅ 已确认 | F6 | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:63` | 主题 zip 可在 theme/ 内植入第二个主题 JSON，其绝对路径被信任 |
| F766 | SHOULD FIX PRE | 1/6 | ✅ 已确认 | F5 | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:120` | 清理链接时，链接后的文字随最后一个跟踪参数一起被删 |
| F767 | SHOULD FIX PRE | 1/6 | ✅ 已确认 | F5 | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:101` | 从未编码过的重定向目标仍会被解码一次 |
| F768 | SHOULD FIX PRE | 1/6 | ✅ 已确认 | F6 | `app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20-24` | ZipStream 解压时目录项可在临时目录之外创建目录 |
| F769 | NIT PRE | 1/6 | ✅ 已确认 | F2 | `lib/ime-eval/tools/make-chat-set.py:5-6` | LCCC 工具的 docstring 与改写后的 README 矛盾 |
| F771 | NIT PRE | 1/6 | ✅ 已确认 | F5 | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:129` | ClearURLs 缺少删除全部参数和编码 key 的测试 |

### 3.1 有效问题

#### F748 [SHOULD FIX] isPlainDisplayName 与 isPlainFileName 重复

- 共识：5/6 位 reviewer（1 SHOULD FIX, 4 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:323`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:185`、`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20`）
- 关联的本批修复：F7
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:323`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:185`、`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:19`

FcitxDataProvider.kt:323 的 `isPlainDisplayName` 与 ThemeFilesManager.kt:185 的 `isPlainFileName` 函数体相同，分属不同包、各守一个信任边界，且各有一份用例相同的测试；以后收紧其中一个（STYLE 举例：拒绝 `\` 或 NUL）时，另一个会与之不一致。
修复（各阶段一致）：在 `utils/` 中只保留一个函数和一份测试，两处共用；STYLE 建议放到 `utils/FileUtil.kt`，连同 `File.normalizedWithin`，并让 `ZipStream.extract` 也改用 `normalizedWithin`（其 PRE-EXISTING 字符串前缀检查见 R1-06）。
严重度不一：STYLE 为 SHOULD FIX，CORRECTNESS-1、CORRECTNESS-2、TESTING、GENERIC-1 为 NIT。

- 提出者：CORRECTNESS-1（NIT，9/10）、CORRECTNESS-2（NIT，9/10）、TESTING（NIT，9/10）、GENERIC-1（NIT，9/10）、STYLE（SHOULD FIX，9/10）

#### F749 [SHOULD FIX] provider 测试复刻映射逻辑，未测 provider 方法本身

- 共识：5/6 位 reviewer（3 SHOULD FIX, 2 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/provider/FcitxDataProviderTest.kt:30-31`）
- 关联的本批修复：F7
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/provider/FcitxDataProviderTest.kt:31`；另见 `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:185`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:185-191`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:219`、`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:268`

FcitxDataProviderTest 的 `resolve()`（:31）自己重建 `fileFromDocId`（`File("${pkg.path}/", docId).normalizedWithin(...)`），测试只调用顶层辅助函数（`normalizedWithin`、`isPlainDisplayName`），没有调用 `queryDocument`、`openDocument`、`createDocument`、`renameDocument` 或 `isChildDocument`。
因此去掉 `createAbstractFile` 的 `requirePlainDisplayName`（:268）、去掉 `renameDocument` 的根同级检查（:219）或回退 `isChildDocument`（:185-191，其 FileNotFoundException 转 false 的处理是新增的）后，测试仍全绿；而 F7 是安全修复。
修复：用本模块已在使用的 Robolectric 构建真实 provider（TESTING：`Robolectric.buildContentProvider(FcitxDataProvider::class.java).create(ProviderInfo)`；STYLE：`Robolectric.setupContentProvider`），断言 `queryDocument("files/../databases")`、`createDocument("files", mime, "../x")`、`renameDocument("files", "x")` 失败，`isChildDocument("files", "files/../databases")` 为 false。
严重度不一：CORRECTNESS-2、TESTING、STYLE 为 SHOULD FIX，CORRECTNESS-1、GENERIC-1 为 NIT；GENERIC-1 同一发现还顺带指出 ThemeFilesManagerTest 只测谓词（见 R1-09）。

- 提出者：CORRECTNESS-1（NIT，8/10）、CORRECTNESS-2（SHOULD FIX，8/10）、TESTING（SHOULD FIX，8/10）、GENERIC-1（NIT，8/10）、STYLE（SHOULD FIX，8/10）

#### F750 [SHOULD FIX] F8 使列表对话框可打开后，「默认」按钮不起作用

- 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/MyListPreferenceDialogFragment.kt:17-18`）
- 关联的本批修复：F8
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/MyListPreferenceDialogFragment.kt:17`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:247`、`app/src/main/java/org/fcitx/fcitx5/android/ui/main/modified/Functions.kt:37`

设 `key = item.key`（InputMethodSettings.kt:247）后，输入法设置页的 ListPreference 对话框可以打开；MyListPreferenceDialogFragment 总是添加中性按钮 `default_`，它调用 `ListPreference.restore()`（Functions.kt:37）读取 `mDefaultValue`，而 `InputMethodSettings.create` 的 `Choice` 从不设置默认值，所以按下该按钮只会关闭对话框、不改任何设置。
修复（两阶段一致）：只在偏好有默认值时才添加中性按钮。
另一做法两阶段说法不同：CORRECTNESS-1 建议给 `Choice` 默认值并调用 `setDefaultValue`；CORRECTNESS-2 建议经 `setOnPreferenceChangeListener` 应用默认值、不要调用 `setDefaultValue`，因为 attach 前设置的默认值会覆盖 `value = item.nearest(raw.value)`。

- 可能的运行时影响：用户在双拼方案等输入法设置对话框中点「默认」时，设置不会恢复默认，对话框直接关闭。
- 提出者：CORRECTNESS-1（SHOULD FIX，8/10）、CORRECTNESS-2（SHOULD FIX，9/10）

#### F751 [SHOULD FIX] 多词删除后压缩可留下仅有头部的日志，libime 词被重新导入

- 共识：2/6 位 reviewer（2 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:78`）
- 关联的本批修复：F3
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:352`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/store/RecordStore.kt:78`

`removeWords` 在多词遗忘后调用 `compactUser()`（Engines.kt:352）；若剩余计数为空（例如只有从 libime `user.dict` 迁移来、基础词典没有的词，且没有历史、习惯和 `LayerPrior` 值），`RecordStore.writeCounts` 只写出头部。
`RecordStore.open`（RecordStore.kt:78）把长度不超过 `HEADER_SIZE` 的日志视为新日志并运行 `importLegacy`，而 `EngineBridge.legacy()` 从不删除 `data/pinyin/user.dict`，于是下次启动时（CORRECTNESS-1 补充：或之后一次 `dropUser()` 后立即）被删的词又回来，违背 F3 的目标；不做这次压缩时，FORGOT 记录会让日志长于头部，不会重新导入。
修复（两个阶段一致）：模型已无计数时跳过 `compactUser()`；或在日志中保留一个压缩时不会丢失的「已导入（seeded）」标记，使压缩后的空日志不再被导入。

- 可能的运行时影响：用户在设置中一次删除多个从 libime 迁移来的词后，重启（或清除后）这些词会重新出现在用户词库中并可再次输入。
- 提出者：CORRECTNESS-1（SHOULD FIX，7/10）、PERFORMANCE（SHOULD FIX，7/10）

#### F752 [SHOULD FIX] 主题导入测试只测谓词，未测 importTheme 的新防护

- 共识：1/6 位 reviewer（1 SHOULD FIX）· 置信度 7/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManagerTest.kt:23`）
- 关联的本批修复：F6
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManagerTest.kt:22`

没有测试覆盖 `importTheme` 中的 `decoded.name` 检查和 `importedImageFile`，删掉任一项测试仍全绿；用例表对图片路径还有误导：`"../../files/engine/tables/x"` 显示为被拒，而 `importTheme` 会把该图片路径截成最后一段，作为 `theme/x` 接受。
修复：参照 PinyinDictManagerTest（在 `@Config(application = FcitxApplication::class)` 下测试 `importPack("../x")`），向 `importTheme` 传入内存 zip：`name = "../x"` 应失败且 `theme/` 外无写入；`srcFilePath = "../../a-src"` 应只在 `theme/a-src` 产生文件。
TESTING 注明未核实 ThemeManager 的静态初始化（`listThemes`、prefs）能否在 Robolectric 下运行；GENERIC-1 在 R1-08 的发现中也顺带提到这一缺口。

- 提出者：TESTING（SHOULD FIX，7/10）

#### F753 [NIT] 清单注释称数据「留在手机上」，但设备间迁移仍会复制

- 共识：3/6 位 reviewer（3 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/AndroidManifest.xml:26`）
- 关联的本批修复：F1
- 位置：`app/src/main/AndroidManifest.xml:26`

AndroidManifest.xml:26 的新注释说学习词和剪贴板「stay on the phone」，但在 API 31+ 上，`allowBackup="false"` 时设备间迁移仍按 `data_extraction_rules.xml` 的 `<device-transfer>` 规则复制它们（GENERIC-1：该块只排除 usr、descriptor.json、licenses.json、README.md 和 no_backup）；用户决定保留这一行为，所以只需改注释，不推翻 F1。
CORRECTNESS-1 与 STYLE 还指出 `<cloud-backup>` 块和 full_backup_content.xml 现已不起作用，却仍读起来像在配置云备份。
修复：注释改为「no cloud backup; device transfer still applies on Android 12+」（各阶段措辞相近）；STYLE 另提出删除失效的块和 `fullBackupContent`；CORRECTNESS-1 可选地建议告知用户旧版本已上传的备份会留在其 Google 账户中直到过期或被删除（与 R1-11 相关）。三票均为 NIT。

- 提出者：CORRECTNESS-1（NIT，8/10）、GENERIC-1（NIT，7/10）、STYLE（NIT，8/10）

#### F754 [NIT] 主题名被拒时提示「找不到主题 json」，信息不准确

- 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:139`）
- 关联的本批修复：F6
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:139`

ThemeFilesManager.kt:139 对构造的路径型主题名抛出 `exception_theme_json`（「Unable to find theme json」），告诉用户文件缺失，而实际是名称无效。
修复：新增专用字符串（STYLE 举例 `exception_theme_name_invalid`），或（CORRECTNESS-2）复用一条已有的通用提示。

- 提出者：CORRECTNESS-2（NIT，9/10）、STYLE（NIT，9/10）

#### F755 [NIT] 测试辅助类 PlaceholderStrings 被复制了一份

- 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:30`）
- 关联的本批修复：F8
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:45`；另见 `app/src/test/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettingsTest.kt:30`、`app/src/test/java/org/fcitx/fcitx5/android/data/prefs/ManagedPreferenceSectionsTest.kt:36`

InputMethodSettingsTest.kt:45 的 `PlaceholderStrings` 几乎照抄 ManagedPreferenceSectionsTest.kt:36，只多一个 override，两份可能逐渐不一致。
TESTING 另指出类级 `@RunWith(Robolectric)`（:30）让原有的纯 JVM 测试也启动 Robolectric。
修复：移到共享测试工具（STYLE 举例 `app/src/test/.../testutil`）；TESTING 也提出可把两个新的 Robolectric 测试放到单独的类中。

- 提出者：TESTING（NIT，8/10）、STYLE（NIT，9/10）

#### F756 [NIT] 压缩条件 learned.size > 1 计入了未遗忘的条目

- 共识：2/6 位 reviewer（2 NIT）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:352`）
- 关联的本批修复：F3
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:352`；另见 `lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:348-352`

Engines.kt:352 用 `learned.size > 1` 决定是否压缩，统计的是请求的条目（包括其余几行用 `filterNotNull()` 丢掉的 null），而不是实际遗忘的词或写出的记录。
PERFORMANCE 指出：`forgetEach` 什么都没忘（条目为 null 或未知）或只忘了一个词时，也会在 fcitx 线程上整份重写日志并 `fd.sync()`，且可能紧接在同一循环中 `append` 已因 `tooLong()` 压缩之后；每次删除最多几十毫秒。
STYLE 另指出 `learned.filterNotNull()` 现在算了三次（:348-352），新注释「a record a word, each read at a start as a pass over all the counts」难以理解，建议改为「one FORGOT record per word, each a full pass over the counts when replayed at start: compact so none is replayed」。
修复说法不同：PERFORMANCE 建议让 `forgetEach` 返回遗忘的词数，为 2 及以上时才压缩；STYLE 建议只绑定一次 `val entries = learned.filterNotNull()` 并检查 `entries.size > 1`。

- 提出者：PERFORMANCE（NIT，8/10）、STYLE（NIT，9/10）

#### F757 [NIT] ClearURLsTest 中关于 Robolectric 用途的注释过时

- 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:14`）
- 关联的本批修复：F5
- 位置：`app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:14`

ClearURLsTest.kt:14 的注释说引入 Robolectric 是为了 `UrlQuerySanitizer`，而本次改动已移除它。
建议：注释只提 `android.net.Uri`。

- 提出者：TESTING（NIT，9/10）

#### F758 [NIT] Choice.message 已改作摘要，名称不再贴切

- 共识：1/6 位 reviewer（1 NIT）· 置信度 9/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:53`）
- 关联的本批修复：F251
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:53`；另见 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:42`

InputMethodSettings.kt:53 的 `Choice.message` 现在用于行摘要，唯一的使用者传入 `R.string.im_auto_phrase_summary`。
建议：仿照 `Toggle.summary`（:42）改名为 `summary`，`choiceSummary` 读起来也更自然。

- 提出者：STYLE（NIT，9/10）

#### F759 [NIT] isChildDocument 每次调用解析四次规范路径

- 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:187`）
- 关联的本批修复：F7
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:187`

FcitxDataProvider.kt:187 的 `isChildDocument` 调两次 `fileFromDocId`（各一次 `canonicalFile`），对父文件再取 `.canonicalFile`，`normalizedWithin` 内对子文件又取一次；`DocumentsProvider` 每次树 URI 操作都会调用它，因此每次操作约有五次路径解析。
建议：用一个辅助函数为每个 id 只返回一次规范文件，可减半；PERFORMANCE 认为与 binder 调用相比代价很小。

- 提出者：PERFORMANCE（NIT，8/10）

#### F760 [NIT] compactUser 靠捕获 ISE 处理日志未打开这一预期状态

- 共识：1/6 位 reviewer（1 NIT）· 置信度 8/10 · 验证：✅ 已确认（`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:421-426`、`RecordStore.kt:131`、`UserStore.kt:68`）
- 关联的本批修复：F3
- 位置：`lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/engine/host/Engines.kt:421`

Engines.kt:421 的 `compactUser` 捕获 `RecordStore.compact` 中 `check(out != null)` 抛出的 `IllegalStateException`，用表示编程错误的异常处理一个预期状态；`RecordStore.append` 在 `out == null` 时已直接返回。
建议：给 `compact` 同样的提前返回（或给 UserStore 加 `isOpen`），去掉 try/catch。CORRECTNESS-2 与 GENERIC-1 在核对 F3 时认为捕获该 `check()` 错误在正确性上足够，未将其列为问题。

- 提出者：STYLE（NIT，8/10）

#### F761 [NIT] 选项摘要在值不在列表中时会显示「null」

- 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:304`）
- 关联的本批修复：F251
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/im/InputMethodSettings.kt:304`

InputMethodSettings.kt:304 的摘要用 `"${it.entry}\n$message"` 拼接，值不是任何条目时会显示「null」，而 `SimpleSummaryProvider` 会显示「Not set」。
GENERIC-1 指出 `nearest()` 目前会把值保持在列表中，所以这只是防御性问题。

- 提出者：GENERIC-1（NIT，7/10）

#### F762 [NIT] 裁剪图路径被拒时，已复制的源图留在 theme/ 中

- 共识：1/6 位 reviewer（1 NIT）· 置信度 7/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:149-154`）
- 关联的本批修复：F6
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:153`

ThemeFilesManager.kt:153 对裁剪图新增的 `importedImageFile(...) ?: errorRuntime(...)` 在源图已复制进 `dir` 之后才执行，构造或损坏的主题会留下一个没有主题使用的文件（可达数 MB）。
PERFORMANCE 指出基线代码在 zip 中缺少裁剪文件时也会同样遗留。
修复：先解析并检查两条路径、并在 `extracted` 中找到两个文件，再复制其中任何一个。

- 提出者：PERFORMANCE（NIT，7/10）

#### F764 [NIT] canonicalFile 抛 IOException 时客户端得到通用异常

- 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:76`）
- 关联的本批修复：F7
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:82-84`；另见 `app/src/main/java/org/fcitx/fcitx5/android/provider/FcitxDataProvider.kt:76`

`canonicalBaseDir`（FcitxDataProvider.kt:76）和 `normalizedWithin`（经 `fileFromDocId`，:82-84）都调用 `canonicalFile`，它可能抛出普通 `IOException`；`DocumentsProvider.call` 只把 `FileNotFoundException` 转成 `ParcelableException`，客户端因此得到通用 RuntimeException，而不是方法承诺的 `FileNotFoundException`。
GENERIC-1 认为实际很少发生。修复：在 `fileFromDocId` 中包住该调用，改抛 `FileNotFoundException`。

- 提出者：GENERIC-1（NIT，6/10）

### 3.2 PRE-EXISTING

#### F765 [SHOULD FIX PRE-EXISTING] 主题 zip 可在 theme/ 内植入第二个主题 JSON，其绝对路径被信任

- 共识：2/6 位 reviewer（2 SHOULD FIX PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`ThemeFilesManager.kt:26-27`）
- 关联的本批修复：F6
- 与已有条目的关系：第一轮没有报告过。在基线 5881d31e 上就存在，F6 的修复没有覆盖它。
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:63`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:26`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:43-44`、`app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:67-70`

F6 只保证图片落在 `theme/` 内，不检查文件内容：构造的 zip 可把图片名设为 `evil.json`（CORRECTNESS-1：裁剪图）或 `B.json`（CORRECTNESS-2：`srcFilePath`）并附带同名的合法主题 JSON，`importTheme` 会把它复制进 `dir`。
下次 `listThemes`（ThemeFilesManager.kt:63）加载它时，只要文件存在就原样信任其绝对 `croppedFilePath`/`srcFilePath`（如 `files/pinyin.user`、内部用户日志或剪贴板数据库）；删除该主题（`deleteThemeFiles`，:43-44）会删掉这些私有文件，导出则把它们复制进共享 zip。
修复（两阶段一致）：导入时在 `importedImageFile`（:26）拒绝 `.json` 名；`listThemes` 中只在图片的规范父目录为 `dir` 时接受，否则按文件名重定位到 `dir`（同 :67-70 的回退分支）。两票均为 PRE-EXISTING SHOULD FIX。

- 提出者：CORRECTNESS-1（SHOULD FIX PRE-EXISTING，7/10）、CORRECTNESS-2（SHOULD FIX PRE-EXISTING，7/10）

#### F766 [SHOULD FIX PRE-EXISTING] 清理链接时，链接后的文字随最后一个跟踪参数一起被删

- 共识：1/6 位 reviewer（1 SHOULD FIX PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:120`、`ClipboardManager.kt:229`、`ClearURLs.kt:74`）
- 关联的本批修复：F5
- 与已有条目的关系：与第一轮 F140 是同一问题：这是 F140 里「后面的文字被删」那一半；「被编码」那一半已随 F5 的修复消失。
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:120`

`cleanClipboardText` 把整段剪贴板文本交给 `transform`（ClearURLs.kt:120）；在 `https://a.com/?utm_source=x 看这个` 或链接后另起一行的文本中，尾随文字属于最后一个查询对的值，删除该参数时一并丢失。
CORRECTNESS-1 指出旧代码同样会丢失这些文字（PRE-EXISTING）。
修复：只清理 URL 片段（到第一个空白字符为止），其余文本原样保留。

- 提出者：CORRECTNESS-1（SHOULD FIX PRE-EXISTING，8/10）

#### F767 [SHOULD FIX PRE-EXISTING] 从未编码过的重定向目标仍会被解码一次

- 共识：1/6 位 reviewer（1 SHOULD FIX PRE-EXISTING）· 置信度 8/10 · 验证：✅ 已确认（`ClearURLs.kt:102`）
- 关联的本批修复：F5
- 与已有条目的关系：与 F5 是同一问题：F5 中 `decodeURL` 那部分没有修完，现在只会多解码一次，但从未编码过的重定向目标仍会被解码一次。
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:101`

`decodeURL`（ClearURLs.kt:101）总会执行一次 `Uri.decode`；govdelivery 规则捕获 `(https?:\/\/.*)`、href.li 规则捕获 `(http.+)`，这些目标本身未编码，其中的 `%26`、`%2B` 会变成 `&`、`+`，与新文档注释「once per layer of encoding」不符（未编码目标没有编码层）。
GENERIC-1 标为 PRE-EXISTING：基线版本解码到不再变化为止，此改动并未更糟。
修复：`var d = str; while (!urlPattern.matchesAt(d, 0)) { val n = Uri.decode(d); if (n == d) break; d = n }`；这也能处理 awstrack 的 `https:%2F%2F`，从而不再需要 `encodedSchemePattern`。

- 提出者：GENERIC-1（SHOULD FIX PRE-EXISTING，8/10）

#### F768 [SHOULD FIX PRE-EXISTING] ZipStream 解压时目录项可在临时目录之外创建目录

- 共识：1/6 位 reviewer（1 SHOULD FIX PRE-EXISTING）· 置信度 6/10 · 验证：✅ 已确认（`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20-24`）
- 关联的本批修复：F6
- 与已有条目的关系：与第一轮 F14 不同：F14 只讲文件项的前缀检查和未关闭的输出流（`ZipStream.kt:20-21`），这里是目录项（`:23-24`）没有任何包含检查。
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/utils/ZipStream.kt:20-24`

`importTheme` 先调用 `ZipStream.extract`（ZipStream.kt:20-24）：目录项直接 `dir.mkdir()`，不做包含检查，`../../files/x/` 这样的条目可在应用内部存储中建目录，占住应用将来要创建的文件位置；文件项的检查是字符串 `startsWith`，名称前缀相同的兄弟目录也能通过。
F6 只检查 JSON 中的名称，管不到这一步；GENERIC-1 标为 PRE-EXISTING。STYLE 在其重复辅助函数的发现（R1-07）中也提到 ZipStream.kt:19 的同一字符串前缀检查，`../<millis>0/x` 可逃到 cacheDir 下的兄弟目录。
修复：目录项做同样的检查，并改用 `normalizedWithin` 那样按路径分量比较的 `File.startsWith`；同时关闭每个 `file.outputStream()`（目前从未关闭）。

- 提出者：GENERIC-1（SHOULD FIX PRE-EXISTING，6/10）

#### F769 [NIT PRE-EXISTING] LCCC 工具的 docstring 与改写后的 README 矛盾

- 共识：1/6 位 reviewer（1 NIT PRE-EXISTING）· 置信度 9/10 · 验证：✅ 已确认（`lib/ime-eval/tools/make-chat-set.py:5-6`、`make-dialog-sets.py:5-6`）
- 关联的本批修复：F2
- 与已有条目的关系：verifier 指出：docstring 自基线起没变，与旧 README 一致；矛盾是这次改写 README（F2）之后才出现的。
- 位置：`lib/ime-eval/tools/make-chat-set.py:5-6`；另见 `lib/ime-eval/tools/make-dialog-sets.py:5-6`

make-chat-set.py:5-6 与 make-dialog-sets.py:5-6 的 docstring 仍说应用发布的东西都不从 LCCC 学习，而改写后的 README 说 LM 混合权重和 `Predictor.USER_WEIGHT` 是在包含这些集合的分数上调出来的。
GENERIC-1 标为 PRE-EXISTING：F2 只改了 README（中英文），工具 docstring 未改。
修复：两个 docstring 都采用 README 的说法。

- 提出者：GENERIC-1（NIT PRE-EXISTING，9/10）

#### F771 [NIT PRE-EXISTING] ClearURLs 缺少删除全部参数和编码 key 的测试

- 共识：1/6 位 reviewer（1 NIT PRE-EXISTING）· 置信度 7/10 · 验证：✅ 已确认（`app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:18-108`）
- 关联的本批修复：F5
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:129`；另见 `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:126`

全部参数都被删除时 `filterParams` 返回 `""`，`Uri.Builder.encodedQuery("")` 和 `encodedFragment("")` 仍会写出结尾的 `?` 或 `#`（与基线版本相同，TESTING 标为 PRE-EXISTING）；新的 `Uri.decode(key)`（ClearURLs.kt:126）也没有用例。
建议：用 `https://example.com/p?utm_source=x` 固定预期结果，并加入 `utm%5Fsource=x` 用例，证明编码过的 key 仍能匹配规则。

- 提出者：TESTING（NIT PRE-EXISTING，7/10）

### 3.3 撤回

#### F763 [NIT] 用户词测试断言日志存储细节而非用户可见行为

- 共识：1/6 位 reviewer（1 NIT）· 置信度 6/10 · 验证：❌ 已否定（理由见下方）
- 关联的本批修复：F3
- 位置：`lib/ime-core/src/test/kotlin/org/fcitx/fcitx5/android/engine/host/EnginesUserWordsTest.kt:136`

EnginesUserWordsTest.kt:136 的 `replayed.size == 0` 用 `UserModel.size`（会计入已遗忘的词）和日志文件名来判断压缩是否运行，检查的是日志如何存储，而不是用户看到什么；其上方的重启检查已证明修复有效。
若把压缩移到 `close()`，该测试会失败而用户无感知。
建议：只有当启动时的重放代价是需求时才保留此断言，并在测试名或注释中说明。

- ❌ 验证说明：`EnginesUserWordsTest.kt:131` 在读取日志（:135-136）之前就调用了 `engines.close()`。即使把压缩挪进 `close()`，读取时文件仍已压缩、`replayed.size` 仍为 0；只有去掉压缩才会让测试失败，因为 FORGOT 重放会让词留在 newWords 中（`UserModel.kt:58,166-171,349`）。所以该断言检验的正是用户可见的结果，论断不成立。
- 提出者：TESTING（NIT，6/10）

#### F770 [NIT PRE-EXISTING] ClearURLs 用 var x 保存待清理的链接，命名不清

- 共识：1/6 位 reviewer（1 NIT PRE-EXISTING）· 置信度 9/10 · 验证：❌ 已否定（理由见下方）
- 关联的本批修复：F5
- 位置：`app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:48`

ClearURLs.kt:48 的 `var x` 保存正在清理的 URL，本次改动又给 `x` 增加了一次赋值（STYLE 标为 PRE-EXISTING）。
建议：改名为 `cleaned` 或 `current`。

- ❌ 验证说明：`ClearURLs.kt:83-84` 只是把基线中无条件的 `x = uri.buildUpon()…` 移进了一个 `if`，没有新增写入；`x` 的四处写入与 5881d31e 相同（:48、59、65、84）。`x` 这个名字是原有的、至今仍在，但与 F510 不同：F510 说的是 :51-52 的惰性 filter 在循环重新赋值时读取 `x`，不是命名问题。
- 提出者：STYLE（NIT PRE-EXISTING，9/10）

### 3.4 编排者备注（verifier 的备注）

- F748：`ZipStream` 的字符串前缀检查在第 20 行而不是第 19 行，自 5881d31e 未变。
- F750：基线上 key 为 null，`findPreference(null)` 会抛异常，所以「默认」按钮根本到不了。设了 key 之后，调用链 InputMethodConfigFragment → `MyPreferenceFragment.kt:18` → `MyListPreferenceDialogFragment.kt:17` 就能到达它。`create()` 从不设默认值，`def()` 返回 null，中性按钮只会关闭对话框。
- F751：触发条件是删除之后所有计数、层先验和按键习惯都已清空（`UserStore.kt:50-57` 也会写入先验和习惯）。例如一个刚迁移的用户，学到的数据只有 libime `user.dict` 里的自有词，此后没再打字。之后任何追加都会让日志超过 `HEADER_SIZE=8`。`EngineBridge.kt:110-113` 从不删除 libime 的文件，所以种子数据会再读一次；同一进程内的 `dropUser()` 也会重新打开存储并再次导入。
- F753：`Versions.kt:13` 设 targetSdk = 36。面向 API 31+ 的应用，`allowBackup="false"` 只停止云备份，不停止设备间迁移（Android 12 的行为变化）。学到的词在 `filesDir/engine`（`EngineBridge.kt:69`），剪贴板在 `databases/clbdb`（`ClipboardManager.kt:102`），`<device-transfer>` 都没有排除。保留设备间迁移是用户对 F1 的决定，本条只针对注释的措辞。
- F756.b：三处 `learned.filterNotNull()` 在基线上就有（5881d31e `Engines.kt:356-358`），新加的只有 `learned.size > 1` 的判断及其注释。
- F759：「每个 tree-URI 操作」这部分依赖 AOSP 的 `DocumentsProvider.enforceTree`，不在仓库里；当文档 id 等于 tree id 时，该方法会跳过 `isChildDocument`。
- F771：按 verifier 对 AOSP `android.net.Uri` 的记忆，空的编码部分会变成 `Part.EMPTY`，`Uri.Builder` 不会为它加 `?` 或 `#`。如果是这样，删光全部参数不会留下 `?` 或 `#`，但缺少测试这一点仍然成立。本机没有框架源码，无法核对。
- F768：应用 targetSdk 36；在 Android 14+ 上 `ZipInputStream` 会拒绝含 `..` 的条目名，所以只有 API 23-33 的设备会被利用（minSdk 23）。这是框架行为，仓库里无法核对。
- F765：`extract` 返回 `destDir.listFiles()`（`ZipStream.kt:28`），两个 JSON 中哪一个被当作 `jsonFile`（`ThemeFilesManager.kt:132`）取决于目录顺序，所以要植入第二个主题，攻击者的主 JSON 需要先被找到。
- F764：跨进程时，从 provider 逃逸的 `IOException` 到客户端可能变成 null 结果而不是 RuntimeException；无论哪种，都不是承诺的 `FileNotFoundException`。

## 4. 各条目的处置

| id | 处置 |
|---|---|
| F1 | Apply，已修复（worktree，未提交） |
| F2 | Apply（文档），已修复；F769 指出 `lib/ime-eval/tools` 两个脚本的 docstring 需同步 |
| F3 | Apply，已修复；修复引入了 F751（需要第 2 轮） |
| F5 | Apply，部分修复：F767 是 `decodeURL` 的剩余部分 |
| F6 | Apply，已修复；F765（第二个主题 JSON）是基线上就有的另一缺口 |
| F7 | Apply，已修复 |
| F8 | Apply，已修复；F750（「默认」按钮无效）随之可达 |
| F251 | Apply，已修复 |
| F730 | Apply，已修复 |
| F729、F731、F733、F734、F736–F740、F742、F743、F746、F747 | 随 F730 不再适用（fixer A 逐条判断） |
| F732 | 仍适用：发布门禁只用全拼评测，九键仍未把关 |

