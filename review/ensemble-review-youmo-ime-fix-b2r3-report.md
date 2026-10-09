# 幽默输入法（youmo-ime）批次 2 第 3 轮（用户批准的额外一轮）修复报告

- 基线：本地快照 `7e884ee1`（批次 2 第 1、2 轮之后，`refs/youmo-snap/b2r2`）。本轮结束后：本地快照 `96eeac88`（`refs/youmo-snap/b2r3`）。快照只在本地。改动仍未提交，在 worktree `youmo-ime-fix`。
- 本轮相对第 2 轮：21 个文件，+556/−56。

## 修了什么

- F884、F890（ClearURLs）：只在标点和符号处截断链接，〇、々、杭州数码和全角字母数字不再截断；链接最后一个参数值从 ASCII 转为汉字时，在汉字前截断，所以 `…&utm_source=x看这个` 变成 `…看这个`。值从第一个字起就是汉字时，仍算作链接的一部分。
- F885：标点「首选」卡片是一对（如 “”）时，候选列表不再列出它自己的另一半。
- F886：删除码表时一并删除 `.told` 和 `.forgotten`；不会读日志的码表（没有码表文件、`Learning=False`、`.conf` 没指定码表）不再写 `.forgotten`。顺带修了 F897 的一半：`.forgotten` 末行被截断时，新写入的词前面先补换行。
- F887：方向键读光标旁文字超时（返回 null）时，这次移动也会被记录，emoji 不会被拆开。
- F888：失败标记只在「数据读不了」时生效：`IOException`、`DataFormatException`，以及 Engines 读不了导入码表时抛的那个异常；起因是内存不足的不算。普通程序错误和内存不足不再关闭输入法，也不提示重装。
- F889：补了键盘和设置页都改过同一个快捷短语时的合并测试；代码本来就对。
- D-F891（用户决定）：九键布局去掉两个长按，`T9Keyboard.kt` 与 `a39dc41d` 完全一致；26 键布局保留。
- D-PUTASIDE（用户决定）：迁移时没读出来、被另外保存的词库，会记在 `.libime/.unread` 里；打开拼音词库设置页时弹一次对话框，列出每个词库和原因（太大，内存不够／文件已损坏／没有可读的词），按「确定」后不再显示。字符串只加了英文、简体、繁体。
- F883：本机没有模拟器，仍然跑不了。修复者确认每个新仪器测试都能单独运行，不依赖已删掉的输入法名，并在 `youmo-fix-b2r3-NATIVE-report.md` 里给出只跑这 8 个测试的命令。还补了切换输入法时提交待定标点的测试。

## 构建与测试（全部通过）

- ime-core 1069、dict-tool 59、ime-eval 72、app 382（1 个跳过）；detekt 通过；`assembleTextDebug`（arm64-v8a）通过，APK 284.7 MB；`compileTextDebugAndroidTestKotlin` 和 `compileVoiceDebugKotlin` 通过；覆盖率 21.62%。
- 第一次构建 detekt 报了 2 处（`Engines.kt` 条件太复杂、`EngineBridgeTest` 的 throw 太多），做了一次范围内的修复：抽出 `forgetsAsItLoads`，测试里加了 `callThrowing` 帮助函数，行为和断言都没变。
- 仪器测试没有跑（无模拟器）。

## 复审（CORRECTNESS-X，单个 stage，按 SOP 不做验证）

结论：「建议修改后批准」。以下条目都没有核对过。

| id | 级别 | 问题 | 相关条目 |
|---|---|---|---|
| F914 | SHOULD FIX | 码表 `Learning=False` 期间遗忘的词，重新打开学习后又会出现：本轮让这类码表不再写 `.forgotten`，但它的旧日志还在。建议：码表存在且指定了文件时照样记下，只跳过没有码表的情况 | F886 |
| F915 | SHOULD FIX | 新的截断会改坏要保留的参数：`…?id=1&utm_campaign=618大促` 变成 `…?id=1大促`，`id` 的值被改了；rawRule 路径也一样。7e884ee1 上这两种都正确。建议：截断点落在会被删除或改写的值里时，原样返回 | F890 |
| F916 | SHOULD FIX | 这次改动之前就被另外保存的词库永远不会提示：`a39dc41d` 的迁移已经会把损坏的词库移走，但没有记录。建议：一次性把 `.libime` 里没有同名文本词库的文件补记进来，原因写成通用说明 | D-PUTASIDE |
| F917 | SHOULD FIX | F883 仍未解决：F885 和切换输入法的原生路径也只有没跑过的仪器测试 | F883 |
| F918 | NIT | 失败标记靠比对 Engines 的异常文字来识别；改成专门的异常类型更稳 | F888 |
| F919 | NIT | `keepForgotten` 把文件读了两遍 | F897 |
| F920 | SHOULD FIX，PRE-EXISTING | 成对标点先压进了栈：用户改选别的卡片或按退格后，下次按 `"` 会以 ” 开头 | — |

复审确认正确：F887、F884、F889、D-F891、D-PUTASIDE 的写入与清除、F886 的删除部分、F885 的修法（按代码推演）。

## 详细数据

各修复者说明：`youmo-fix-b2r3-<组>-report.md`；复审原文：`ensemble-review-youmo-ime-fix-b2r3-out/CORRECTNESS-X.md`；diff：`ensemble-review-youmo-ime-fix-b2r3-diff.md`。
