# 幽默输入法（youmo-ime）批次 1 修复审查报告（第 2 轮）

> 第 2 轮修复在 worktree `youmo-ime-fix`（未提交）。本报告审查的是第 2 轮相对第 1 轮的增量：每个文件与第 1 轮结束时的状态比较（副本在 `ensemble-review-youmo-ime-baseline-r2/`），diff 16 个文件、740 行（`ensemble-review-youmo-ime-fix2-diff.md`）。

## 1. 第 2 轮修了什么

| id | 来源 | 怎么修的 |
|---|---|---|
| F751 | F3 的修复引入 | 压缩后若没有计数可写，就写一条 `EMPTIED` 记录（9 字节），日志不再被当成新文件、不再从 libime 重新导入；重放时跳过这条记录。旧版本会忽略未知记录类型。 |
| F748 | 第 1 轮审查 | 文件名规则只保留一份：新增 `utils/FileName.kt` 的 `isPlainFileName`，provider 和主题导入共用。 |
| F749 | 第 1 轮审查 | `FcitxDataProviderTest` 改为 Robolectric，直接驱动真实的 provider（7 个测试）。 |
| F752 | 第 1 轮审查 | `ThemeFilesManagerTest` 改为 Robolectric，端到端测试 `importTheme` 的两道新防护（4 个测试）。 |
| F765 | 修复前就存在，按你的要求一起修 | 导入时拒绝以 `.json` 结尾的图片名；`listThemes` 只信任直接位于 `theme/` 内的图片路径。 |
| F750 | F8 修好后暴露 | 只有在列表有字符串默认值时才显示「默认」按钮。代码表的真实默认值拿不到（fcitx 的配置描述只给出类默认值 0），所以选了隐藏按钮这一方案。 |
| F767 | F5 的剩余部分 | 已经以 `http(s)://` 开头的重定向目标原样保留，其余目标解码一次，若方案仍是编码状态再继续解码。**这个修法引入了 F772，见下。** |
| F769 | F2 的后续 | 两个 LCCC 评测脚本的 docstring 和它们写出的三段文件头改成与 README 一致；没有重新生成数据文件。 |

## 2. 构建与测试

第 2 轮改完后一次通过：ime-core 1,013、dict-tool 59、ime-eval 72、app 247 个测试（1 个跳过），0 失败；detekt 通过；`assembleTextDebug`（arm64-v8a）成功；app 行覆盖率 14.21%（第 1 轮后 13.24%）。

## 3. 修复审查

- 阵容（SOP：第 2 轮没有修 MUST FIX，所以只有 2×CORRECTNESS 加 1×TESTING）：3 个 `reviewer` stage，**3 个都是「要求修改」**，原因是同一条 MUST FIX。
- 合并后 9 条，全部由 1 个 verifier 核对：确认 8 条，否定 1 条（F774）。编号接着前面，从 F772 开始。

| id | 级别 | 共识 | 验证 | 位置 | 标题 |
|---|---|---|---|---|---|
| F772 | MUST FIX | 3/3（3 MUST FIX） | ✅ 已确认 | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:104` | `decodeURL` 原样保留字面 `https://` 开头的目标，改坏 Google 包装的链接 |
| F773 | NIT | 2/3 | ✅ 已确认 | `lib/ime-eval/data/pinyin-chat.tsv:5` +2 处 | 已提交的 LCCC 数据文件头仍是旧说法 |
| F775 | NIT | 2/3 | ✅ 已确认 | `app/src/test/.../InputMethodSettingsTest.kt:137` | 测试名讲对话框，实际只测了 `hasDefault()` |
| F776 | NIT | 2/3 | ✅ 已确认 | `lib/ime-core/.../engine/store/RecordStore.kt:192` | 记录类型 0 只在 RecordStore 里保留，RecordFormat 没有约束 |
| F777 | NIT | 1/3 | ✅ 已确认 | `app/src/test/.../ClearURLsTest.kt:83` | 第 2 轮的 ClearURLs 测试只覆盖原样尾部的目标 |
| F778 | NIT | 1/3 | ✅ 已确认 | `app/src/test/.../FcitxDataProviderTest.kt:68` | provider 测试没有调用 `openDocument` |
| F779 | NIT | 1/3 | ✅ 已确认 | `lib/ime-core/src/test/.../UserStoreTest.kt:267` | 没有测试 EMPTIED 记录之后继续追加的日志 |
| F780 | SHOULD FIX PRE-EXISTING | 2/3 | ✅ 已确认 | `app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt:25` +2 处 | `listThemes` 不检查被植入主题 JSON 的 `name`，保存和删除可越出 `theme/` |
| F774 | NIT（已撤回） | 2/3 | ❌ 已否定 | `lib/ime-eval/tools/make-chat-set.py:1` | make-chat-set.py 的文件模式从 644 变成 755 |

### F772 [MUST FIX] `decodeURL` 原样保留字面 `https://` 开头的目标，改坏 Google 包装的链接

- 共识：3/3 位 reviewer（3 MUST FIX）· 置信度 8/10 · 验证：✅ 已确认（`ClearURLs.kt:104`；规则 `\/url\?.*?(?:url|q)=(https?[^&]+)`，`forceRedirection: true`）
- 由第 2 轮的 F767 修复引入。

Google 的规则从 `q=`（以及 `adurl=`）这个查询参数里取目标。Gmail、Docs、Calendar 包装的链接里，目标的协议是字面的 `https://`，但目标自己的 `=`、`&` 是百分号编码的，例如 `google.com/url?q=https://www.youtube.com/watch?v%3Dabc&sa=D`。第 1 轮会解码一次，得到 `?v=abc`，是对的；第 2 轮新增的提前返回把它原样保留成 `?v%3Dabc`，目标服务器读到的就不是原来的参数了。
ClearURLs 和剪贴板功能默认都开（`AppPrefs.kt:403`、`:426-431`），清理后的文本会存进剪贴板历史；系统剪贴板本身不变，所以坏链接只会在从输入法的剪贴板历史或建议里粘贴时出现。现有测试里没有这种情况。
三位 reviewer 的建议一致：按目标是从哪里截出来的来区分。目标紧跟在 `=` 后面（是一个查询参数的值），就像跳转服务器一样解码一次；`?https://…`、`&https://…` 这种原样尾部（href.li、govdelivery）保持不变。并加一个 `q=https://…%3D…%26…` 的测试。

- 提出者：TESTING（MUST FIX，7/10）、CORRECTNESS-1（MUST FIX，7/10）、CORRECTNESS-2（MUST FIX，8/10）

### F780 [SHOULD FIX PRE-EXISTING] `listThemes` 不检查被植入主题 JSON 的 `name`

- 共识：2/3（TESTING SHOULD FIX PRE-EXISTING 7/10、CORRECTNESS-2 SHOULD FIX PRE-EXISTING 8/10）· 验证：✅ 已确认，现在存在，5881d31e 上也存在（`ThemeFilesManager.kt:25,52,79-90`）
- F765 挡住了通过导入植入第二个 JSON，但另一个应用仍可能把 JSON 直接放进 `theme/`（minSdk 23 的旧设备允许往共享存储的 `Android/data` 写；provider 的根就是 `getExternalFilesDir(null)`）。这样一个 JSON 若 `name` 是 `../…/x`，迁移或按文件名找到图片后，`saveThemeFiles` 会在 `theme/` 外写 `<name>.json`；用户在界面上删除这个主题时，`deleteThemeFiles` 也会删 `theme/` 外的 `<name>.json`（只限 `.json` 结尾的路径）。第 2 轮的 fixer B 自己也报告了这个缺口。
- 建议：`listThemes` 跳过 `!isPlainFileName(theme.name)` 的主题（或要求名字与文件名一致），并在 `aThemeIsTrustedOnlyWithItsImagesInTheThemeDir` 里加一个植入名字的用例。

### 其余 NIT

- **F773**：`pinyin-chat.tsv:5-6`、`pinyin-dialog.tsv:5`、`predict/chat.tsv:5` 的注释行仍写「nothing the app ships learns from it」或「only measured with」，与 README 和改过的脚本文件头不一致。只改这几行注释即可，不必重新生成数据。
- **F775**：`aChoiceHasNoDefaultSoItsDialogOffersNone` 只断言 `hasDefault()`；对话框按钮的判断本身没被测到。便宜的做法是按它实际断言的内容改名。
- **F776**：类型 0 只在 `RecordStore` 里被当成保留值；`RecordFormat`（UserLog 和 TableLog 共用）不拒绝它，将来的格式若用了 0 会被静默丢弃。建议在 `RecordFormat` 里保留并拒绝。
- **F777**：`aRedirectTargetThatWasNeverEncodedIsKeptAsItIs` 只用了 href.li 和 govdelivery，没有从参数值里截出的字面协议目标——正是 F772 的情况。
- **F778**：provider 测试没调用 `openDocument`（真正的读写入口），加一行即可。
- **F779**：`aLogCompactedToNothingIsNotSeededAgain` 停在压缩那一步，没有验证之后再追加的记录能被重放。

### 撤回

- **F774**（2/3 NIT）：`git diff --summary 5881d31e` 没有任何模式变化，`make-chat-set.py` 在 5881d31e 上就是 100755（`dee9d090` 创建时即如此）。reviewer 看到的「old mode 100644」是我生成增量 diff 时用临时文件当基线造成的假象，不是代码改动。

## 4. 结论

两轮修复已到 SOP 的上限。仍未解决、需要你决定的：

- **F772（MUST FIX，第 2 轮引入）**
- **F780（SHOULD FIX，修复前就存在）**
- NIT：F773、F775–F779。

第 1 轮修复审查里没有选进第 2 轮的条目（F753–F764 的 NIT，以及 PRE-EXISTING 的 F766、F768、F771）仍然开着。
