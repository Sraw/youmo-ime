# 幽默输入法（youmo-ime）批次 1 修复审查报告（第 3 轮，用户批准的额外一轮）

> 第 3 轮只修 F772、F780、F773，改动在 worktree `youmo-ime-fix`（未提交）。本报告审查的是第 3 轮相对第 2 轮的增量：每个文件与第 2 轮结束时的状态比较（副本在 `ensemble-review-youmo-ime-baseline-r3/`），diff 7 个文件、153 行（`ensemble-review-youmo-ime-fix3-diff.md`）。

## 1. 第 3 轮修了什么

| id | 来源 | 怎么修的 |
|---|---|---|
| F772（MUST FIX） | 第 2 轮 F767 的修法引入 | `transformWith` 看重定向规则截出的目标前一个字符：是 `=`（目标是一个查询参数的值）就调用 `decodeURL(str, isQueryValue = true)`，先解码一次（和跳转服务器的做法一致），再走原来的 `^https?:?%` 循环。原样尾部、且已经读作 `http(s)://` 的目标（href.li、govdelivery）仍保持不变。 |
| F780（SHOULD FIX，修复前就存在） | 第 2 轮修复审查 | `listThemes` 解析主题 JSON 后，`name` 不符合共用的 `isPlainFileName` 规则就记一条 `Timber.w` 并跳过，因此不会被保存、删除或导出。没有要求 `name` 与 JSON 的文件名一致：合法的名字已经把读写限制在 `theme/<name>.json`，而能往 `theme/` 里放 JSON 的应用本来就能覆盖那里的任何文件；加这个要求还会让被改名或复制过的主题消失。应用自己创建的主题名都是 UUID，不受影响。 |
| F773（NIT） | 第 2 轮修复审查 | 三个 LCCC 数据文件（`pinyin-chat.tsv`、`pinyin-dialog.tsv`、`predict/chat.tsv`）开头的 `#` 注释块换成脚本里当前的 `HEADER`、`DIALOG_HEADER`、`PREDICT_HEADER`。数据行逐字节未变；没有任何地方对这三个文件做哈希或固定版本。 |

新增测试：
- `ClearURLsTest.aRedirectTargetInAQueryValueIsDecodedOnceThoughItsSchemeIsPlain`：Google `q=https://example.com/p?a%3D1%26b%3D2&sa=D` 得到 `https://example.com/p?a=1&b=2`；YouTube 包在 Google 里的链接得到 `https://www.youtube.com/watch?v=abc`；googleadservices `adurl=https://example.com/p?a%3D1` 得到 `https://example.com/p?a=1`。修复前这个测试失败。（`google.com/aclk?…adurl=` 被 google 规则列为例外，不会被展开，所以用了 googleadservices。）
- `ThemeFilesManagerTest.aThemeIsTrustedOnlyWithItsImagesInTheThemeDir` 加了一个植入的 `escape.json`（`name` 为 `../y`，图片在 `theme/` 里）：断言它不出现在列表里，且 `theme/` 的上一级没有出现 `y.json`。修复前这个断言失败。

## 2. 构建与测试

一次通过：ime-core 1,013、dict-tool 59、ime-eval 72（数据文件改动后用 `--rerun` 重跑，因为这个任务不把数据文件当输入）、app 248 个测试（1 个跳过），0 失败；detekt 通过；`assembleTextDebug`（arm64-v8a）成功；app 行覆盖率 14.23%。

## 3. 修复审查（SOP 规定额外一轮只用一个 CORRECTNESS-X stage，不做 Step 4b 验证）

- 结论：**批准**。三条修复都做到了规格说的事，没有发现高于 NIT 的问题。
- reviewer 逐条看了 `clearurls_rules.json` 里全部 60 条重定向规则：href.li、anonym.to、deviantart 的目标跟在 `?` 后面，awstrack、amazon-adsystem、mozgcp、mozaws、imgsrc 的跟在 `/` 后面，都走原样尾部的路径；跟在 `=` 后面的都是表单式查询值，解码一次正确。
- 新报出 3 条 NIT（未经 verifier 核对，SOP 规定这一轮不做 Step 4b）：

| id | 级别 | 位置 | 标题 |
|---|---|---|---|
| F781 | NIT（8/10） | `app/src/test/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLsTest.kt:83` | 没有测试「字面协议的查询值只解码一次」。三个新用例在旧的「解码到不变为止」逻辑下也会通过，退回过度解码不会被发现。建议加 `google.com/url?q=https://example.com/s?q%3Da%2526b&sa=D` → `https://example.com/s?q=a%26b`。 |
| F782 | NIT（5/10） | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:109` | 字面协议查询值里落单的 `%`（如 `q=https://example.com/50%off`）现在会被 `Uri.decode`；在设备上坏的转义会被替换成 U+FFFD，Robolectric 可能表现不同，单测看不出。这种值对跳转服务器本身也是坏的，影响很小。可选：解码结果多出 `\uFFFD` 时退回原串。 |
| F783 | NIT PRE-EXISTING（8/10） | `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClearURLs.kt:63` | 展开重定向后直接 `return x`，不再对目标本身套用规则，例如编码在 Google `q=` 里的 `utm_*` 参数会留在复制的链接中。浏览器插件会在下一次请求时再清理，剪贴板没有第二遍。5881d31e 上就是如此，第 3 轮没有让它更糟。 |

- 本轮之后没有仍未解决的 MUST FIX 或已验证的 SHOULD FIX。F783 是 PRE-EXISTING，按规则不算未解决项。

## 4. 处置

| id | 处置 |
|---|---|
| F772 | 已修复（第 3 轮） |
| F780 | 已修复（第 3 轮） |
| F773 | 已修复（第 3 轮） |
| F781、F782 | 新 NIT，未修，留待以后 |
| F783 | PRE-EXISTING，未修 |
