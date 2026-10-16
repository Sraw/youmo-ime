# 构建与开发 · Building and development

[中文](#构建与开发) · [English](#building-and-development) · [返回 README](README.md)

---

## 构建与开发

### 需要

- Android SDK Platform 36 与 Build-Tools 36.1.0；
- Android NDK 28.0.13004108 与 CMake 3.31.6；
- [extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)、GNU gettext ≥ 0.20。

版本以 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) 为准。

### 构建

```shell
git clone https://github.com/Sraw/youmo-ime.git
cd youmo-ime
git submodule update --init --recursive
sudo apt install extra-cmake-modules gettext   # Debian/Ubuntu；Arch：pacman -S extra-cmake-modules；macOS：brew install extra-cmake-modules gettext
./gradlew :app:assembleTextDebug   # 带语音：:app:assembleVoiceDebug
```

版本有两个：`text`（纯文字，默认）和 `voice`（离线语音输入，多一个录音权限和约 180 MB 的语音模型），都不申请网络权限。
`./gradlew :app:assembleRelease` 两个都打。默认四种 ABI 都编，只编一种快得多：手机加 `-PbuildABI=arm64-v8a`（发布的安装包就是这样打的），
x86_64 的模拟器或 redroid 加 `-PbuildABI=x86_64`。

### 数据

构建会自动下载数据，并按 SHA-256 校验：

- libime 发布的词典和码表文本源，rime-stroke 的笔画表；
- `voice` 还有 X-ASR 原版模型包（取其中的词表）和 silero VAD；
- 本仓库 release 里的数据，每类一个，版本写在 `build-logic` 里：

| release | 内容 | 编进安装包的 |
|---|---|---|
| `engine-data-*` | 混好的拼音语言模型 | `engine/pinyin.data` |
| `words-*` | 新词包 | 拼音词典的新词层 |
| `sentence-models-*` | 两个句子模型（4M 和 25M 参数） | `engine/sentence-model*.safetensors` |
| `english-data-*` | 英文词表和语言模型 | `engine/english.data` |
| `voice-models-*` | 转成 ORT 格式的语音识别模型（只用于 `voice`） | `voice/*.ort` |
| `handwriting-models-*` | 手写识别模型（ORT 格式）、字表和字频 | `handwriting/*` |

第一次构建，文字版约下载 500 MB，语音版再多约 260 MB（拼音语言模型解开后占 1 GB 磁盘）。编译拼音数据大约要 2 分钟、5 GB 内存。

每个数据 release 里都有 `manifest.txt`，写明它从哪些数据、用什么命令生成。重新生成的方法见
[specs/data-and-build.md](specs/data-and-build.md)：拼音语言模型用 `lib/ime-dict-tool/engine-data.sh`，英文用
`ime-dict-tool english-lm` 再 `prune`。

### 签名

正式版需要签名。把以下三项写进 `~/.gradle/gradle.properties`（也可以用环境变量 `SIGN_KEY_FILE`、`SIGN_KEY_ALIAS`、`SIGN_KEY_PWD`；
密钥文件也可以用 base64 写在 `signKeyBase64` / `SIGN_KEY_BASE64` 里），然后运行 `./gradlew :app:assembleRelease`。
没有配置密钥，或者密钥文件路径不存在时，构建不会报错，而是生成未签名的安装包。

```properties
signKeyFile=/path/to/release.p12
signKeyAlias=...
signKeyPwd=...
```

### 开发

改代码之前先读 [specs/](specs/README.md)：架构、引擎、应用、数据与构建、测试、约定和踩过的坑（英文）。
改了用户能看到的行为或设置，同时更新[使用说明](docs/README.md)。

| 位置 | 内容 |
|---|---|
| `lib/ime-core` | 纯 JVM 模块，不依赖 Android：拼音与码表引擎、解码器、句子模型推理，以及各种决策逻辑 |
| `app` | Android 部分：界面、`InputMethodService`、设置、与 Fcitx5 的 JNI 桥 |
| `app/src/voice`、`app/src/text` | 两个版本各自的代码：语音输入只在 `voice` 里 |
| `lib/sherpa-onnx` | 语音识别运行库（sherpa-onnx 的源码，有本项目的改动） |
| `lib/ime-eval` | 评测：用测试集模拟打字，算首选正确率等指标 |
| `lib/ime-dict-tool` | 从文本源构建引擎数据（只在构建时用，不进安装包） |
| `lexicon/` | 新词的来源和校对记录 |

| 做什么 | 命令 |
|---|---|
| 核心逻辑测试、API 级别检查、覆盖率门槛 | `./gradlew :lib:ime-core:check` |
| 应用的 JVM 测试 | `./gradlew :app:testTextDebugUnitTest` |
| 静态检查 | `./gradlew detekt` |
| 拼音准确率（README 里的表） | `lib/ime-eval/release-report.sh` |
| 英文打字评测 | `ime-eval english <english.data> lib/ime-eval/data/english.txt` |

---

## Building and development

### Requirements

- Android SDK Platform 36 and Build-Tools 36.1.0;
- Android NDK 28.0.13004108 and CMake 3.31.6;
- [extra-cmake-modules](https://github.com/KDE/extra-cmake-modules) and GNU gettext ≥ 0.20.

[Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) has the current versions.

### Building

```shell
git clone https://github.com/Sraw/youmo-ime.git
cd youmo-ime
git submodule update --init --recursive
sudo apt install extra-cmake-modules gettext   # Debian/Ubuntu; Arch: pacman -S extra-cmake-modules; macOS: brew install extra-cmake-modules gettext
./gradlew :app:assembleTextDebug   # with voice: :app:assembleVoiceDebug
```

There are two builds, neither with a network permission: `text`, the default, and `voice`, offline voice input, with the microphone
permission and a ~180 MB speech model. `./gradlew :app:assembleRelease` makes both. All four ABIs are built by default; one is much faster:
`-PbuildABI=arm64-v8a` for phones (as the released APKs are built), `-PbuildABI=x86_64` for an x86_64 emulator or redroid.

### Data

The build downloads its data and checks the SHA-256 of each file:

- the dictionary and code table text sources published by libime, and rime-stroke's stroke table;
- for `voice`, X-ASR's original model archive (for its token list) and silero VAD;
- this repository's data releases, one of each kind, their versions pinned in `build-logic`:

| Release | What | In the APK as |
|---|---|---|
| `engine-data-*` | The mixed pinyin language model | `engine/pinyin.data` |
| `words-*` | The new-word pack | the pinyin dictionary's layer of new words |
| `sentence-models-*` | The two sentence models (4M and 25M parameters) | `engine/sentence-model*.safetensors` |
| `english-data-*` | English words and language model | `engine/english.data` |
| `voice-models-*` | The speech recognizer in ORT format (`voice` only) | `voice/*.ort` |
| `handwriting-models-*` | The handwriting recognizers in ORT format, their characters and frequencies | `handwriting/*` |

The first build downloads about 500 MB for `text` and some 260 MB more for `voice` (the pinyin model takes 1 GB of disk unpacked). Compiling the pinyin data takes about 2 minutes and 5 GB of memory.

Each data release has a `manifest.txt` saying what it was made from and by which command. How to make them again is in
[specs/data-and-build.md](specs/data-and-build.md): the pinyin model by `lib/ime-dict-tool/engine-data.sh`, English by
`ime-dict-tool english-lm` and then `prune`.

### Signing

A release build needs a signing key. Put these three in `~/.gradle/gradle.properties` (or the environment variables `SIGN_KEY_FILE`, `SIGN_KEY_ALIAS` and `SIGN_KEY_PWD`;
the key file can also be given base64-encoded as `signKeyBase64` / `SIGN_KEY_BASE64`), then run `./gradlew :app:assembleRelease`.
Without a key, or with a key file path that does not exist, the build does not fail: it makes an unsigned APK.

```properties
signKeyFile=/path/to/release.p12
signKeyAlias=...
signKeyPwd=...
```

### Development

Before changing the code, read [specs/](specs/README.md): architecture, the engine, the app, data and build, testing, conventions, pitfalls.
A change to what users see or to a setting updates the [user's manual](docs/README.md) too.

| Where | What |
|---|---|
| `lib/ime-core` | Plain JVM, no Android: the pinyin and table engines, the decoder, sentence-model inference, and the decision logic |
| `app` | The Android part: views, the `InputMethodService`, preferences, the JNI bridge to Fcitx5 |
| `app/src/voice`, `app/src/text` | Each build's own code: voice input is only in `voice` |
| `lib/sherpa-onnx` | The speech recognition runtime (sherpa-onnx's source, with this project's changes) |
| `lib/ime-eval` | Evaluation: types the test sets and measures top-1 accuracy and more |
| `lib/ime-dict-tool` | Builds the engine data from text sources (build time only, not in the APK) |
| `lexicon/` | Where the new words come from, and their curation |

| To | Run |
|---|---|
| Test the core logic, check the API level and coverage floors | `./gradlew :lib:ime-core:check` |
| Run the app's JVM tests | `./gradlew :app:testTextDebugUnitTest` |
| Run static analysis | `./gradlew detekt` |
| Measure pinyin accuracy (the README's table) | `lib/ime-eval/release-report.sh` |
| Measure English typing | `ime-eval english <english.data> lib/ime-eval/data/english.txt` |
