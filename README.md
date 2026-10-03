# 幽默输入法 · Youmo IME

[中文](#幽默输入法) · [English](#youmo-ime)

---

## 幽默输入法

一个在手机上**完全离线**运行的 Android 中文输入法。它基于 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)，
保留 [Fcitx5](https://github.com/fcitx/fcitx5) 作为输入法框架，拼音引擎是自己用 Kotlin 写的，取代了原来的 libime，
并且在 n-gram 解码器之上加了两个小型神经网络模型给整句候选重新排序。

> 这是一个独立的项目，与 Fcitx5 和 fcitx5-android 官方无关；它从 fcitx5-android 分出，保留了完整的历史，但不会合并回上游。问题请在本仓库反馈，不要报给上游。

### 特点

- **自己的拼音引擎**：全拼、双拼（8 种方案），按整句解码；简拼、半截输入、相邻键打错的纠正；按前文给出联想。
- **神经网络重排**：
  - 每次按键，4M 参数的模型（4.5 MB）在解码器的前两个整句里重排；
  - 停顿时，25M 参数的模型（25 MB）在前五个整句里精排；
  - 两个模型都会读输入框里光标前的文字作为上下文（密码框等敏感输入框不读）。
- **越用越顺手**：用户词库、调频；可以导入词库，支持自定义短语；长按候选可以删除或置顶词组。
- **码表输入**：五笔、仓颉、自然码、二笔等，用自己的码表引擎，也可以导入码表。
- **不联网**：默认的离线版不申请网络权限，输入的内容不会离开手机（[隐私政策](PRIVACY.md)）。
- **可选的自建云端（云端版）**：在自己的电脑上运行 [`cloud/server.py`](cloud/README.md)（Qwen3.5 4B，8 GB 显卡就行），停顿时由它给前五个整句重新打分；
  默认关闭，只经 HTTPS 发到你核对过密钥的服务器，密码框和无痕键盘从不发送。云端版是单独的安装包，多一个网络权限，与离线版互相替换安装。
- **其他**：Material 3 界面、可自定义长按字符、主题、剪贴板管理（复制链接时自动去掉跟踪参数）、表情与符号、实体键盘下的悬浮候选栏。

### 效果

首选正确率（%）。"解码器"指只用 n-gram 解码器，"+ 模型"指加上 4M 和 25M 两个模型。

| 测试集 | 解码器 | + 模型 |
|---|---|---|
| 主集（日常与书面句子） | 88.5 | 91.1 |
| 聊天 | 69.5 | 73.3 |
| 万象 246 | 64.2 | 68.7 |
| 大集（取 1/10，22317 句） | 74.1 | 78.8 |

测法见 `lib/ime-eval`。

### 下载

安装包会发布在 [Releases](https://github.com/Sraw/youmo-ime/releases)；在那之前请按下文自行构建。

包名是 `io.github.sraw.youmo`，可以和上游的 Fcitx5（`org.fcitx.fcitx5.android`）同时安装。
从本分支改名前的版本换过来，可以在旧版的“高级”设置里导出用户数据，再到新版里导入：设置、主题、剪贴板会带过来；
旧版的备份不含引擎学到的词和词频，这部分要重新积累（新版的备份包含它们）。

### 数据与许可

- 代码：LGPL-2.1-or-later，与上游相同。
- 词典、拼音语言模型、码表：来自 [libime](https://github.com/fcitx/libime) 发布的文本源（LGPL-2.1-or-later），构建时编译成本引擎的格式。
  拼音语言模型里混入了 [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) 中文类聊天网页的 n-gram（ODC-By 1.0）。
- 句子模型：本项目自己训练（Apache-2.0）。先在 FineWeb-2 中文网页上训练语言模型，再用 Qwen3.5-9B-Base（Apache-2.0）给候选列表打分做蒸馏。
  文件发布在 [sentence-models-20261001](https://github.com/Sraw/youmo-ime/releases/tag/sentence-models-20261001)。
- 不使用任何仅限科研用途的数据。

### 构建

需要：

- Android SDK Platform 36 与 Build-Tools 36.1.0；
- Android NDK 28.0.13004108 与 CMake 3.31.6；
- [extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)、GNU gettext ≥ 0.20。

版本以 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) 为准。

```shell
git clone https://github.com/Sraw/youmo-ime.git
cd youmo-ime
git submodule update --init --recursive
sudo apt install extra-cmake-modules gettext   # Debian/Ubuntu；Arch：pacman -S extra-cmake-modules；macOS：brew install extra-cmake-modules gettext
./gradlew :app:assembleOfflineDebug   # 云端版：:app:assembleCloudDebug
```

有两个版本：`offline`（离线版，默认发布，不申请网络权限）和 `cloud`（云端版，多一个网络权限，用于自建云端）。
`./gradlew :app:assembleRelease` 会两个都打。

构建会自动下载引擎数据和句子模型，并按 SHA-256 校验：

- 第一次下载 libime 的数据源和两个 FineWeb-2 分片，约 10 GB；
- 混入 FineWeb-2 这一步大约要 7 分钟、7 GB 内存。内存只有 16 GB 的机器先运行 `./gradlew --stop`。
  加 `-Pengine.mix=false` 可以跳过这一步，但拼音模型就不含聊天语料了。

正式版需要签名。把以下三项写进 `~/.gradle/gradle.properties`（也可以用环境变量 `SIGN_KEY_FILE`、`SIGN_KEY_ALIAS`、`SIGN_KEY_PWD`；
密钥文件也可以用 base64 写在 `signKeyBase64` / `SIGN_KEY_BASE64` 里），然后运行 `./gradlew :app:assembleRelease`。
没有配置密钥，或者密钥文件路径不存在时，构建不会报错，而是生成未签名的安装包。

```properties
signKeyFile=/path/to/release.p12
signKeyAlias=...
signKeyPwd=...
```

### 开发

| 位置 | 内容 |
|---|---|
| `lib/ime-core` | 纯 JVM 模块，不依赖 Android：拼音与码表引擎、解码器、句子模型推理，以及各种决策逻辑 |
| `app` | Android 部分：界面、`InputMethodService`、设置、与 Fcitx5 的 JNI 桥 |
| `lib/ime-eval` | 评测：用测试集模拟打字，算首选正确率等指标 |
| `lib/ime-dict-tool` | 从文本源构建引擎数据（只在构建时用，不进安装包） |

| 做什么 | 命令 |
|---|---|
| 核心逻辑测试、API 级别检查、覆盖率门槛 | `./gradlew :lib:ime-core:check` |
| 应用的 JVM 测试 | `./gradlew :app:testOfflineDebugUnitTest` |
| 静态检查 | `./gradlew detekt` |

### 致谢

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)、[Fcitx5](https://github.com/fcitx/fcitx5)、[libime](https://github.com/fcitx/libime)、
[fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons)、[FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2)、[Qwen](https://github.com/QwenLM)。

---

## Youmo IME

*幽默 (yōumò) means humor.*

An Android Chinese input method that runs **entirely offline** on the phone. It is built on
[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) and keeps [Fcitx5](https://github.com/fcitx/fcitx5)
as the input method framework. The pinyin engine is its own, written in Kotlin in place of libime, and two
small neural models rerank the n-gram decoder's whole-sentence candidates.

> This is an independent project, not affiliated with Fcitx5 or fcitx5-android. It branched off fcitx5-android,
> keeping the full history, and will not be merged upstream. Please report issues here, not upstream.

### Features

- **Its own pinyin engine**: full pinyin and shuangpin (8 schemes), decoded as whole sentences; abbreviations, half-typed syllables and slipped neighbouring keys; next-word suggestions from what you have typed.
- **Neural reranking**:
  - at every key, a 4M-parameter model (4.5 MB) reorders the decoder's best two sentences;
  - when you pause, a 25M-parameter model (25 MB) reorders the best five;
  - both read the text before the cursor in the input field as context (never in password or other sensitive fields).
- **Learns as you type**: user words and frequencies, imported dictionaries, custom phrases; long-press a candidate to remove or pin it.
- **Code tables**: Wubi, Cangjie, Ziranma, Erbi and more, on its own table engine; tables can be imported.
- **No network**: the default offline build asks for no network permission, and what you type never leaves the phone ([privacy policy](PRIVACY.md)).
- **Your own server, optionally (cloud build)**: run [`cloud/server.py`](cloud/README.md) (Qwen3.5 4B; an 8 GB graphics card will do) on your own computer, and when you pause it rescores the best five sentences.
  Off by default; it sends only over HTTPS to the server whose key you checked, never from password fields or incognito keyboards. The cloud build is a separate package with one more permission, network access; it installs over the offline build and the other way round.
- **More**: Material 3 interface, configurable long-press characters, themes, clipboard management (tracking parameters stripped from copied links), emoji and symbols, a floating candidate bar with a physical keyboard.

### Accuracy

Top-1 accuracy (%). "Decoder" is the n-gram decoder alone; "+ models" adds the 4M and 25M models.

| Set | Decoder | + models |
|---|---|---|
| Main (everyday and written sentences) | 88.5 | 91.1 |
| Chat | 69.5 | 73.3 |
| Wanxiang 246 | 64.2 | 68.7 |
| Large set (a tenth, 22,317 sentences) | 74.1 | 78.8 |

See `lib/ime-eval` for how these are measured.

### Download

Builds will be published on [Releases](https://github.com/Sraw/youmo-ime/releases); until then, build it yourself as below.

The package name is `io.github.sraw.youmo`, so it installs alongside upstream Fcitx5 (`org.fcitx.fcitx5.android`).
To move from this fork's builds before the renaming, export your user data in the old app's Advanced settings and import it in the new one.
Settings, themes and the clipboard come along. The old app's backups leave out the words and frequencies the engine has learnt, so those start again (the new app's backups include them).

### Data and licences

- Code: LGPL-2.1-or-later, as upstream.
- Dictionary, pinyin language model and code tables: compiled at build time from the text sources published by [libime](https://github.com/fcitx/libime) (LGPL-2.1-or-later).
  The pinyin language model also mixes in n-grams from chat-like Chinese pages of [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) (ODC-By 1.0).
- Sentence models: this project's own (Apache-2.0). A language model is first trained on FineWeb-2's Chinese pages, then distilled from Qwen3.5-9B-Base's (Apache-2.0) scores of candidate lists.
  The files are published at [sentence-models-20261001](https://github.com/Sraw/youmo-ime/releases/tag/sentence-models-20261001).
- No research-only data is used.

### Building

Requirements:

- Android SDK Platform 36 and Build-Tools 36.1.0;
- Android NDK 28.0.13004108 and CMake 3.31.6;
- [extra-cmake-modules](https://github.com/KDE/extra-cmake-modules) and GNU gettext ≥ 0.20.

[Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) has the current versions.

```shell
git clone https://github.com/Sraw/youmo-ime.git
cd youmo-ime
git submodule update --init --recursive
sudo apt install extra-cmake-modules gettext   # Debian/Ubuntu; Arch: pacman -S extra-cmake-modules; macOS: brew install extra-cmake-modules gettext
./gradlew :app:assembleOfflineDebug   # the cloud build: :app:assembleCloudDebug
```

There are two builds: `offline`, published by default, with no network permission, and `cloud`, with network access for your own server.
`./gradlew :app:assembleRelease` makes both.

The build downloads the engine data and the sentence models and checks their SHA-256:

- the first build downloads libime's data sources and two FineWeb-2 shards, about 10 GB;
- mixing in FineWeb-2 takes about 7 minutes and 7 GB of memory. On a 16 GB machine, run `./gradlew --stop` first.
  `-Pengine.mix=false` skips the mix, at the cost of the chat text in the pinyin model.

A release build needs a signing key. Put these three in `~/.gradle/gradle.properties` (or the environment variables `SIGN_KEY_FILE`, `SIGN_KEY_ALIAS` and `SIGN_KEY_PWD`;
the key file can also be given base64-encoded as `signKeyBase64` / `SIGN_KEY_BASE64`), then run `./gradlew :app:assembleRelease`.
Without a key, or with a key file path that does not exist, the build does not fail: it makes an unsigned APK.

```properties
signKeyFile=/path/to/release.p12
signKeyAlias=...
signKeyPwd=...
```

### Development

| Where | What |
|---|---|
| `lib/ime-core` | Plain JVM, no Android: the pinyin and table engines, the decoder, sentence-model inference, and the decision logic |
| `app` | The Android part: views, the `InputMethodService`, preferences, the JNI bridge to Fcitx5 |
| `lib/ime-eval` | Evaluation: types the test sets and measures top-1 accuracy and more |
| `lib/ime-dict-tool` | Builds the engine data from text sources (build time only, not in the APK) |

| To | Run |
|---|---|
| Test the core logic, check the API level and coverage floors | `./gradlew :lib:ime-core:check` |
| Run the app's JVM tests | `./gradlew :app:testOfflineDebugUnitTest` |
| Run static analysis | `./gradlew detekt` |

### Thanks

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android), [Fcitx5](https://github.com/fcitx/fcitx5), [libime](https://github.com/fcitx/libime),
[fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons), [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2), [Qwen](https://github.com/QwenLM).
