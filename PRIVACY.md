# 隐私政策 · Privacy Policy

[中文](#隐私政策) · [English](#privacy-policy)

## 隐私政策

幽默输入法不收集任何数据，也不上传给我们或任何第三方。

幽默输入法有两个版本：

- **离线版**（默认）：不申请网络权限，无法联网。
- **云端版**：多申请一个网络权限，只用于“云端模型”这一项。它默认关闭；你打开并填写自己服务器的地址后，
  每次停顿时，会把光标前的文字（包括之前上屏的字，最多 128 个字）和前五个整句候选发到这个地址，由服务器返回每个候选的打分。
  只经 HTTPS 发送（连本机 127.0.0.1 也是），并且只发给打开时你核对过密钥的那台服务器；密码框、无痕键盘里从不发送；
  关闭它就不再发送。服务器由你自己运行，我们收不到这些内容；能使用这台服务器的人可以看到它们。
  这些设置（地址、令牌、密钥）只存在手机本地，不进系统备份，也不进“导出用户数据”。
  打开后每天至多一次（以及你在设置里点“服务器的新词包”时），还会向同一台服务器要一次新词包（`GET /words`）；这个请求不带任何
  你输入的内容。

除此之外，两个版本相同：

- 拼音解码、两个句子模型都在手机上运行。
- 你输入的内容、学到的词和词频、剪贴板记录、设置，都只存在手机上这个应用自己的目录里。
- 为了按前文给出更准的候选，引擎会读输入框里光标前的文字，和你打的字一样只在手机上处理。密码框等敏感输入框不读。
- 只有你在"高级"设置里主动导出用户数据时，这些数据才会写成一个文件，存到你选的位置。
- 卸载应用会删除它的全部数据。

本政策如有变动，会在这个文件里更新。

## Privacy Policy

Youmo IME collects no data, and uploads none to us or to anyone else.

There are two builds:

- **Offline** (the default): asks for no network permission and cannot go online.
- **Cloud**: asks for network access, used only by the "Cloud model" setting. It is off by default. Once you turn it on and give
  your own server's address, at each pause it sends the text before the cursor (up to 128 characters) and the best five candidate
  sentences to that address, which answers with a score for each. It goes over HTTPS
  only (even to this device, 127.0.0.1), and only to the server whose key you compared when turning it on; nothing
  is sent from password fields or incognito keyboards; turning it off stops it. You run the server, so we never receive any of
  it; whoever can use that server can. These settings (address, token, key) stay on the phone: not in system backups, not in
  "Export user data". While it is on, the app also asks the same server for a word pack (`GET /words`) at most once a day, and
  when you tap "Word pack from the server" in the settings; that request carries nothing you typed.

Otherwise the two builds are the same:

- Pinyin decoding and both sentence models run on the phone.
- What you type, the words and frequencies it learns, the clipboard history and the settings stay in the app's own directories on the phone.
- To suggest better candidates from what comes before, the engine reads the text before the cursor in the input field. Like what you type, it is handled on the phone only, and it is not read from password or other sensitive fields.
- Your user data is written to a file only when you export it yourself in the Advanced settings, to a place you choose.
- Uninstalling the app deletes all of its data.

Changes to this policy will be made in this file.
