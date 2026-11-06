# 输入测试台（`:testbed`）

一个独立的测试用 App（`org.fcitx.fcitx5.android.testbed`，应用名「输入测试台」），把各种输入框和各种应用行为
集中在一起，模拟器和真机测试都用它。不进发布包，app 也不依赖它。minSdk 29，Android 10 能用（不像测试页的
WebView shell 要 API 34）。只用框架自带的 View，没有任何库。

## 安装

```sh
./gradlew :testbed:installDebug          # 装到 ANDROID_SERIAL 那台；多台时先 export ANDROID_SERIAL=emulator-5556
```

模拟器的 clean 快照（`uat/tools/avd.sh`）里没有它：`uat.py up` 起的是只读快照，每次起来都要再装一次（几秒）。
要省这一步可以重做快照时把它装进去（在 avd.sh 装 WebView shell 的地方加一行 `adb install`），目前没这么做。

## 打开

```sh
adb shell am start -n org.fcitx.fcitx5.android.testbed/.MainActivity                    # 首页：所有页面的列表
adb shell am start -n org.fcitx.fcitx5.android.testbed/.MainActivity --es page behavior  # 直接打开某一页
```

脚本里：`d.testbed("behavior")`。每个原生输入框的 resource id 就是下表里的名字
（`org.fcitx.fcitx5.android.testbed:id/<名字>`），hint 写着它测什么，contentDescription 是「名字：测什么」，
`d.find(rid=…)` 或按 desc 都能找到。

## 事件记录和状态读取

- 每页底部的「事件记录 ▲」点开是这一页的事件，新的在上面：文本变化（前后内容）、选区变化、编辑动作
  （onEditorAction 的 actionId）、焦点变化、按键（onKeyDown）、应用自己做的事（`app`）。
- 同样的事件打到 logcat，tag `Testbed`，每行一个 JSON：`adb logcat -s Testbed:I`。字段有 `page` `field`
  `event`（text/selection/focus/action/key/app/web_*）`t`，text 和 selection 事件带上 `text` `before`
  `selStart` `selEnd` `compStart` `compEnd`（composing 区间，没有时是 -1）。
- **状态读取**（debug 包才有）：

  ```sh
  adb shell content query --uri content://org.fcitx.fcitx5.android.testbed.state
  # Row: 0 json=[{"name":"chat_clear","text":"你好","selStart":2,"selEnd":2,"compStart":-1,"compEnd":-1,"focused":true,...},...]
  ```

  脚本里 `d.testbed_state()` 返回 `{名字: {...}}`，当前页（和开着的对话框）的原生框都在里面。判断重复上屏、丢字
  就比它的 `text` 和 composing 区间。这个 ContentProvider 是导出的（`content` 命令以 shell 身份调用），但只回答
  shell（uid 2000）和 root，别的 App 调用一律 SecurityException，所以不会给其他 App 开读取输入的口子；release 包里没有它。
- WebView 页的框不在状态里：它们的事件经 `console.log` 进事件记录和 logcat（`web_input` `web_selection` 等，
  `value` 是框的内容）。

## 页面

### `types` 普通 EditText 输入类型

| id | 测什么 |
|---|---|
| `text` | 普通文本 |
| `multiline` | 多行（MULTI_LINE，回车换行） |
| `short_message` | 短消息，imeOptions=actionSend |
| `cap_chars` `cap_words` `cap_sentences` | 自动大写：全部 / 单词首字母 / 句首 |
| `no_suggestions` | NO_SUGGESTIONS |
| `email` `uri` `person_name` | 邮箱、网址、人名 |
| `postal` | 邮编/地址（Android 没有邮编类型，用 POSTAL_ADDRESS） |
| `number` `signed` `decimal` `phone` | 数字、带符号、小数、电话 |
| `date` `time` `datetime` | 日期、时间、日期时间 |
| `pw_text` `pw_number` `pw_web` `pw_visible` | 四种密码 |
| `no_learning` | IME_FLAG_NO_PERSONALIZED_LEARNING |
| `no_extract` | IME_FLAG_NO_EXTRACT_UI |

### `ime` imeOptions 和焦点

| id | 测什么 |
|---|---|
| `ime_go` `ime_search` `ime_send` `ime_next` `ime_previous` `ime_done` `ime_none` | 各种回车动作 |
| `chain_1` `chain_2` `chain_3` | next/previous 来回跳（带 NAVIGATE_NEXT/PREVIOUS 标志） |
| `with_hint` | 有 hint |
| `initial_text` | 有初始文字 |
| `preselected` | 预先选中第 2–4 个字 |
| `max10` | maxLength=10 |

### `behavior` 模拟应用行为（语音和输入状态机的测试靠它）

| id | 测什么 |
|---|---|
| `chat_clear` | 发送/回车后只 `setText("")` |
| `chat_restart` | 发送后 `setText("")` 再 `restartInput` |
| `chat_hide` | 发送后清空并收起键盘 |
| `finish_composing` | 搜索框：每次文本变化应用就去掉 composing（`removeComposingSpans`，即应用侧的 finishComposingText） |
| `phone_format` | TextWatcher 把手机号改成 3-4-4 加空格 |
| `upper` | TextWatcher 把小写改成大写 |
| `interval` `timed` | 按钮开始/停止：每隔 `interval` 秒对 `timed` `setText(原文+"#")` 或 `setSelection(0)` |
| `restart_target` | 按钮点一下对它 `restartInput` |
| `no_digits` | InputFilter 不让输入数字 |
| `null_extracted` | InputConnection 的 `getExtractedText` 返回 null |
| `null_before` | InputConnection 的 `getTextBeforeCursor` 返回 null |
| `terminal` | TYPE_NULL，原始按键（每个键也记成 key 事件） |
| `shared`（`shared_a` `shared_b`） | 两个框共用一个 View id；状态里按名字分开 |
| `dialog_field` | 按钮打开的 AlertDialog 里的框 |
| `sheet_field` | 按钮打开的底部弹出层（贴底的 Dialog）里的框 |
| `landscape_extract` | 不带 noExtractUi：先转横屏再点，测全屏提取模式 |
| `bottom` | 内容最底部（上面有一大段空白），键盘弹出会挡住或要页面跟着滚 |

### `webview` WebView

assets 里的本地 `web.html`，不访问网络。框的 id：`w_text` `w_search` `w_email` `w_number` `w_tel` `w_url`
`w_password` `w_textarea` `w_editable`（contenteditable）、`w_form_input`（表单回车提交后清空）、`w_rewrite`
（JS 在 input 事件里转大写）、`w_noauto`（autocomplete=off）、`w_moveselect`（每次输入 JS 把光标移到开头）。
一页里多个框，用来测 WebView 共用 fieldId。

### `switch` 切换场景

`switch_a` `switch_b` 两个框；按钮「2 秒后把焦点切到另一个框」（测说着话换框）；按钮「2 秒后跳到另一个
Activity，再自动回来」（另一个 Activity 停 2 秒自己 finish）。

## 没做的

- Compose 页（BasicTextField / TextField）：仓库没有任何 Compose 依赖，加上会给 verification-metadata 添一大批
  条目、拖慢构建，先没做。要做时建议单独一个 flavor。
