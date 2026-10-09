# 幽默输入法（youmo-ime）批次 2 第 2 轮修复报告

- 基线：本地快照 `5e7f1d9b`（= `a39dc41d` + 批次 2 第 1 轮的未提交修复，`refs/youmo-snap/b2r1`）。第 2 轮结束后的状态：本地快照 `7e884ee1`（`refs/youmo-snap/b2r2`）。两个快照都只在本地，没有推送。改动仍未提交，在 worktree `youmo-ime-fix`。
- 第 2 轮相对第 1 轮：65 个文件，+2138/−467。

## 修了什么

- 第 1 轮修复审查中成立的 34 条 SHOULD FIX（F784–F802、F804–F818）。
- 用户的 5 项决定：
  - D-F171：被 `hidden {}` 藏起来的设置，升级后第一次启动时重置一次；每次导入设置后，下一次启动时再重置一次。由「级别」控制的隐藏项（键盘高度、长按延时、振动、音量）不重置。ThemePrefs 里的隐藏项不在范围内。
  - D-F180：在应用里改语言时重启 fcitx（fcitx 没在运行时不重启）。Android 13 及以上从 `LocaleManager` 读新语言。
  - D-F186：同一个标点键有多张卡片时，按键弹出候选，「首选」排第一并预先放进输入框；只有一张卡片的键照旧直接上屏。
  - D-F272：长按「符」进快捷短语，长按「123」进 Unicode 输入（26 键和九键布局都加了）。
  - D-F276：引擎在第一次正常响应之前出错，就记住这个输入法、不再调用，直到重新加载或 fcitx 重启，并弹一次提示。提示文字只加了英文、简体、繁体。
- F22、F258 按用户决定保持现状。
- 回归 R-F169：码表设置页现在只写用户改过的选项，每项都不加注释，所以改回默认值也能保存下来。
- 跟进修复者 Y1：F786（`EngineBridge` 启动引擎前先把 `engine.old` 恢复回来）和 F801（候选在点击时就记下序号和文字）。
- 修复者自己做的取舍：F797 把 Zstd 上限恢复为 128 MiB，因为实测最新的 zhwiki 词典解压后有 64.5 MiB；F799 把断电后全零的日志尾也当作损坏保存下来，不再静默截断；F788 只在标点处截断链接，紧跟在跟踪参数值后面的汉字仍会被当作参数值（F890）。
- CORE 修复者提出一个待决定的问题，没有修：迁移时被搁置的 libime 词典，要不要在词典设置页告诉用户。

## 构建与测试（全部通过）

- 文本版：ime-core 1063、dict-tool 59、ime-eval 72、app 371（1 个跳过）；detekt 通过；`assembleTextDebug`（arm64-v8a）通过，APK 284.7 MB；`compileTextDebugAndroidTestKotlin` 通过；覆盖率 21.49%（第 1 轮后为 18.97%）。
- 语音版（第 1 轮没编译过，即 F807）：`assembleVoiceDebug` 通过（APK 469.6 MB），`compileVoiceDebugAndroidTestKotlin` 通过，`testVoiceDebugUnitTest` 371 个，0 失败。
- `lib/sherpa-onnx/host-test.sh`：context-graph 6 个、新加的 utils-test 2 个，全部通过。
- 第一次构建 detekt 报了 2 处：`ReplaceFile.replaceDirectory` 的 throw 太多，`EnginesTest` 超过 600 行。做了一次范围内的修复：抽出 `renameOrThrow`；把用到 `userTable` 的测试原样移到新的 `EnginesTablesTest`，并加了共用的 `EnginesFixture`。改完后第二次构建全部通过。
- **仪器测试没有跑**：本机没有 KVM，跑不了模拟器。所以 D-F186、R-F169/F816、F809、F801 的原生部分只有仪器测试，还没执行过（F883）。另外，FcitxTest 里原有的 12 个用例还在用已经不存在的输入法名 `pinyin`、`wbx`，跑起来必然失败（F912，修复前就存在）。

## 修复审查

按 SOP 派 2×CORRECTNESS + TESTING，按两个分片各跑一遍，共 6 个 stage（Q1：lib、原生、语音、androidTest；Q2：app），结论都是「建议修改后批准」。42 条原始发现合并为 32 条，丢弃 1 条置信度 ≤5 的 NIT，剩 31 条（F883–F913）。3 个 verifier 逐条核对，28 条成立。F895、F900、F907 被否定，因为这些问题在第 2 轮之前就存在，第 2 轮既没有让它们变得可触发，也没有让它们更糟。没有 MUST FIX。

### 成立的 SHOULD FIX（9 条）

| id | 类型 | 标题 | 相关条目 |
|---|---|---|---|
| F883 | 测试/其他 | 本轮原生改动只由从未运行过的仪器测试覆盖 | F800（仍存在） |
| F884 | 运行时 | isProsePunctuation 的范围含 〇、々 及全角字母数字 | F788 |
| F885 | 运行时 | 首选卡为成对标点时，候选列表显示错误的半边 | D-F186 |
| F886 | 运行时 | 被遗忘的词写入 .forgotten 文件后可能永不删除 | F792 |
| F887 | 运行时 | 方向键读取文本为 null 时移动未被记录，仍会拆开 emoji | F814（仍存在） |
| F888 | 运行时 | D-F276 闩锁把首次应答前的任何异常都当作无法加载 | D-F276 |
| F889 | 测试/其他 | 键盘与文件都改过的键，其合并规则没有测试 | F817 |
| F890 | 运行时 | F788 只修了一部分：跟踪参数值后紧跟的汉字仍被删除 | F788（仍存在） |
| F891 | 运行时 | 九宫格上长按「符」打开快速输入，但该布局无法输入字母 | D-F272 |

### PRE-EXISTING（3 条，都已确认）

- F911 [SHOULD FIX] 词典页 onStop 仍有 F795 修复过的崩溃竞态
- F912 [SHOULD FIX] FcitxTest 旧用例仍启用已不存在的 pinyin 与 wbx
- F913 [SHOULD FIX] 复制图片时读取出错仍会让应用（含键盘）崩溃

### NIT（16 条成立）

- F892 EngineBridgeTest 的 Made 依赖测试执行顺序
- F893 EngineBridge 对象的 KDoc 已过时
- F894 F802 测试的第二次保存依赖 replaceFile 的清理
- F896 F806 注释称不记录异常消息，但首次失败的堆栈含消息
- F897 .forgotten 中被截断的末行之后追加的文本会丢失
- F898 tornAppend 的两种边界情况没有测试
- F899 F810 的 utils-test 只能手动运行，未接入 CI
- F901 导入后重置隐藏设置的三行调用链没有测试
- F902 FcitxTest 清理时遗留 .told 与 .forgotten 文件
- F903 没有测试检查加载时应用的遗忘在再次启动后仍有效
- F904 ArrowKeysTest 断言精确读取次数，固定了缓存实现
- F905 恢复 engine.old 失败时，下一次导入会删掉唯一副本
- F906 ThemePrefs 中由级别控制的键未标 setByLevels
- F908 恢复 engine.old 与重置隐藏设置时都没有日志
- F909 AppPrefsTest 中的保留键是手写列表
- F910 D-F186 的切换输入法提交与快捷键路径没有测试

### 被否定的 3 条（都是修复前就存在）

- F895：PhraseFile 的「未读取」保护没有测试。这段保护在 5e7f1d9b 就有，原来也没测。
- F900：页面离开后导入失败不显示错误。修复前同样不显示。
- F907：导入成功后的后续操作仍在会重启 fcitx 的 catch 里。修复前就是这样，这轮没有引入新的抛出点。

## 详细数据

`ensemble-review-youmo-ime-fix-b2r2-final.json`（条目）、`-verify-marks.json` 和 `-verify-out/`（验证结果）、`-out/`（6 个 stage 的原始输出）、`youmo-fix-b2r2-<组>-report.md`（各修复者的说明）。
