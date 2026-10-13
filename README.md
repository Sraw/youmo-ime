# 幽默输入法 · Youmo IME

[中文](#幽默输入法) · [English](#youmo-ime) · [使用说明](docs/README.md) · [下载](https://github.com/Sraw/youmo-ime/releases)

---

## 幽默输入法

一个**完全离线**的 Android 中文输入法：拼音、九键、双拼、五笔，英文，还能离线语音输入。不申请网络权限，你打的字、说的话都不会离开手机。

> 本项目从 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 分出，保留 [Fcitx5](https://github.com/fcitx/fcitx5) 作为输入法框架，
> 但拼音引擎、语言模型、句子模型都是自己做的。它与 Fcitx5 和 fcitx5-android 官方无关，也不会合并回上游；问题请在[本仓库](https://github.com/Sraw/youmo-ime/issues)反馈。

### 特色

- **九键拼音**：按整句判断，直接按 `64426` 就是"你好"；左边的拼音选择栏可以把某一段定成某个音节；简拼也能用。
  快速连按同一个键也能表示它的第几个字母，输入法同时按两种读法判断，正常快打不受影响。每个键上的字母可以自定义。
- **五笔**（86 版）：码满唯一自动上屏，空码自动丢弃，`z` 键是万能键，也能先按 `z` 再打拼音反查五笔编码；
  候选旁可以显示编码提示，方便学编码；一个字一个字打出来的词会自动造成词。另有五笔拼音混打，以及仓颉、二笔、码表式自然码。
- **自己训练的语言模型**：
  - **拼音 n-gram 模型**：本项目自己从网页里统计训练。在 libime 模型的基础上，混入从 FineWeb-2 和一次 Common Crawl 抓取的中文聊天类网页里统计出的 n-gram，日常聊天打得更准。
  - **英文 n-gram 模型**：本项目自己从 FineWeb 的英文网页统计训练。
  - **句子模型**：两个小型神经网络，本项目自己训练。每次按键，4M 参数的模型在前两个整句里重排；你停顿时，25M 参数的模型在前五个整句里精排。两个模型都参考光标前已经写好的文字（密码等敏感输入框不读）。
- **新词**：近几年的新词（显眼包、破防、红温……）由本项目自己从网页里找出、逐个校对后编进词库，每次发版更新。
- **全拼与双拼**：整句输入，8 种双拼方案；简拼、半截输入、按到相邻键的自动纠正；按前文给出联想。
- **英文键盘**：自动纠错（`teh` → the）、补全、下一个词的预测，会记住你打的词；拼音里也能直接打出 iPhone、APP 这类英文词。
- **越用越顺手**：记住你的用词和习惯；可以导入词库（.txt、Fcitx 5 的 .dict、搜狗 .scel）、自定义短语；长按候选可以让它忘掉学到的、置顶或屏蔽。
- **其他**：Material 3 界面、主题（可以用自己的图片）、可自定义的长按字符、剪贴板（复制链接时自动去掉跟踪参数）、表情与符号、实体键盘下的悬浮候选栏。

### 语音输入（语音版）

- **目前只支持普通话和英语**，可以中英混说；暂不支持粤语等方言。
- 识别全部在手机上完成，录音不保存、不上传。
- **按住空格说话，松开上屏**；说的时候手指上滑再松开就取消。也可以点工具栏的麦克风，在语音面板里说。
- 自动加标点；数字按习惯写，"二零零二年"写成 2002年，"百分之五十七"写成 57%。
- 用拼音的语言模型从几个识别结果里挑最通顺的；词库里的新词和你自己的词更容易被听出来，屏蔽的词不会出现。
- 识别模型是 [X-ASR](https://github.com/Gilgamesh-J/X-ASR) 中英模型，由 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 在手机上运行。

### 效果

拼音首选正确率（%）。"解码器"指只用 n-gram 模型，"+ 句子模型"指加上两个句子模型。

| 测试集 | 解码器 | + 句子模型 |
|---|---|---|
| 主集（日常与书面句子，548 句） | 88.3 | 90.9 |
| 网页（近期网页，模型没读过，3642 句） | 72.9 | 76.5 |
| 聊天（留出的一半，1064 句） | 68.7 | 72.7 |
| 对话（590 句） | 67.6 | 71.2 |
| 新词（405 句） | 79.5 | 80.0 |

2026-10-12 测，和 App 默认一样开着相邻键纠错。九键打主集，加句子模型后首选 88.8%。
英文：正常打字 99.6% 按原意上屏；每 25 个键按错一个时，打错的词 72.7% 能被自动改对。

### 下载

安装包在 [Releases](https://github.com/Sraw/youmo-ime/releases)，每次发布有两个：

| 版本 | 内容 | 大小 |
|---|---|---|
| `text` 文字版 | 只有打字，只申请振动权限 | 约 260 MB |
| `voice` 语音版 | 多了离线语音输入，多申请录音权限 | 约 440 MB |

- 需要 Android 6.0 以上的 64 位 ARM 手机（arm64-v8a），近几年的手机都是。
- 安装包大，是因为语言模型、句子模型和词库都在包里，离线就能用；准确率优先于体积。
- 包名 `io.github.sraw.youmo`，可以和上游的 Fcitx5 同时安装；两个版本可以互相覆盖安装，设置和学到的词都会保留。
- 下载后可以用 `SHA256SUMS.txt` 核对文件。

安装后怎么设置、各个键和手势、每个设置的意思，都在[使用说明](docs/README.md)里；应用设置首页右上角的问号也能打开它。

### 发版计划

- **每月发一版**，主要更新词库：把近期出现的新词收进来。应用不联网，新词只随新版本到达。
- 有值得尽快修的 bug 时，随时发修复版。
- 装的版本里的数据超过半年时，设置首页会提醒你更新。

### 下一步计划

- **手写输入**。
- **优化语音输入**：提高识别准确率，并支持方言。

### 数据与许可

- 代码：LGPL-2.1-or-later，与上游相同。
- 拼音词典、码表，以及拼音语言模型的基础：来自 [libime](https://github.com/fcitx/libime)（LGPL-2.1-or-later）。
- 本项目自己训练、统计的数据：
  - 拼音 n-gram 混入的部分，统计自 [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2)（ODC-By 1.0）和 [Common Crawl](https://commoncrawl.org)（遵守其使用条款）的中文网页；
  - 英文 n-gram，统计自 [FineWeb](https://huggingface.co/datasets/HuggingFaceFW/fineweb)（ODC-By 1.0）的英文网页；
  - 句子模型（Apache-2.0）：先在 FineWeb-2 中文网页上训练，再用 Qwen3.5-9B-Base（Apache-2.0）给候选打分做蒸馏；
  - 新词库：从 Common Crawl 中文网页里找出，逐个校对（`lexicon/`）。
- 笔画表：[rime-stroke](https://github.com/rime/rime-stroke)（LGPL-3.0-only）。
- 语音识别：[X-ASR](https://github.com/Gilgamesh-J/X-ASR)（Apache-2.0），由 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）和 [silero VAD](https://github.com/snakers4/silero-vad)（MIT）运行。
- 安装包里的模型和词库都没有用仅限科研用途的数据训练。评测用的聊天、对话测试集取自 [LCCC](https://github.com/thu-coai/CDial-GPT)（仅限科研），只用来评测和调参。
- [隐私政策](PRIVACY.md)。

### 构建与开发

见 [BUILDING.md](BUILDING.md)。

### 致谢

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)、[Fcitx5](https://github.com/fcitx/fcitx5)、[libime](https://github.com/fcitx/libime)、
[fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons)、[FineWeb](https://huggingface.co/datasets/HuggingFaceFW/fineweb)、[FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2)、[Common Crawl](https://commoncrawl.org)、[Qwen](https://github.com/QwenLM)、[X-ASR](https://github.com/Gilgamesh-J/X-ASR)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)。

---

## Youmo IME

*幽默 (yōumò) means humor.*

An Android Chinese input method that runs **entirely offline**: pinyin, nine keys, shuangpin, Wubi, English, and offline voice input.
It asks for no network permission; what you type and say never leaves the phone.

> It branched off [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) and keeps [Fcitx5](https://github.com/fcitx/fcitx5) as the
> input method framework, but the pinyin engine, the language models and the sentence models are its own. It is not affiliated with Fcitx5
> or fcitx5-android and will not be merged upstream; please report issues [here](https://github.com/Sraw/youmo-ime/issues).

### Features

- **Nine keys**: decoded as whole sentences, so `64426` is 你好; the syllable bar on the left fixes a stretch to one syllable; abbreviations work.
  A key pressed again quickly can also mean its next letter: both readings are decoded, so fast typing is not disturbed. Each key's letters can be changed.
- **Wubi** (86): a full unique code commits itself, a code with no character is dropped, `z` stands for any key and, followed by pinyin,
  looks up a character's Wubi code; codes can be shown beside candidates to learn them; words typed character by character become words.
  Also Wubi-Pinyin mixed, Cangjie, Erbi and table-style Ziranma.
- **Language models of its own**:
  - **Pinyin n-gram model**: counted and trained by this project. libime's model is the base, mixed with n-grams this project counted from chat-like Chinese pages of FineWeb-2 and a Common Crawl crawl, for everyday chat.
  - **English n-gram model**: counted by this project from FineWeb's English pages.
  - **Sentence models**: two small neural networks trained by this project. At every key a 4M-parameter model reorders the best two sentences; when you pause, a 25M-parameter model reorders the best five. Both read the text before the cursor (never in password or other sensitive fields).
- **New words**: the words of the last few years, found in web pages and curated one by one by this project, updated with every release.
- **Full pinyin and shuangpin**: whole sentences, 8 shuangpin schemes; abbreviations, half-typed syllables, slipped neighbouring keys corrected; next-word suggestions.
- **English keyboard**: autocorrection (`teh` → the), completions, next-word predictions, learning your words; Latin words such as iPhone and APP type straight from pinyin.
- **Learns as you type**: your words and habits; imported dictionaries (.txt, Fcitx 5 .dict, Sogou .scel), custom phrases; long-press a candidate to forget what was learnt of it, pin it or block it.
- **More**: Material 3 interface, themes (your own picture too), configurable long-press characters, clipboard (tracking parameters stripped from copied links), emoji and symbols, a floating candidate bar with a physical keyboard.

### Voice input (the voice build)

- **Mandarin and English only for now**, mixed freely; Cantonese and other dialects are not supported yet.
- Recognition runs entirely on the phone; recordings are neither kept nor uploaded.
- **Hold the space bar to talk, let go to commit**; slide up before letting go to cancel. Or tap the microphone on the toolbar for the voice panel.
- Punctuation is added; numbers are written as people write them (二零零二年 → 2002年, 百分之五十七 → 57%).
- The pinyin language model picks the most fluent of several results; new words and your own words are heard more readily, blocked words never appear.
- The recognizer is the [X-ASR](https://github.com/Gilgamesh-J/X-ASR) zh-en model, run on the phone by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

### Accuracy

Pinyin top-1 accuracy (%). "Decoder" is the n-gram model alone; "+ sentence models" adds the two sentence models.

| Set | Decoder | + sentence models |
|---|---|---|
| Main (everyday and written sentences, 548) | 88.3 | 90.9 |
| Web (recent pages the models never read, 3,642) | 72.9 | 76.5 |
| Chat (the held-out half, 1,064) | 68.7 | 72.7 |
| Dialog (590) | 67.6 | 71.2 |
| New words (405) | 79.5 | 80.0 |

Measured on 2026-10-12, with neighbouring-key correction on as in the app by default. On nine keys the main set is 88.8% first with the sentence models.
English: clean typing is committed as meant 99.6% of the time; with one key in 25 slipping, 72.7% of the slipped words are corrected.

### Download

Builds are on [Releases](https://github.com/Sraw/youmo-ime/releases), two per release:

| Build | What | Size |
|---|---|---|
| `text` | Typing only; asks only for vibration | about 260 MB |
| `voice` | Adds offline voice input; asks for the microphone too | about 440 MB |

- Needs a 64-bit ARM phone (arm64-v8a) on Android 6.0 or later, as phones of the last several years are.
- The APKs are large because the language models, sentence models and dictionary are inside, to work offline; accuracy comes before size.
- The package name is `io.github.sraw.youmo`, so it installs alongside upstream Fcitx5; either build installs over the other, keeping settings and learnt words.
- `SHA256SUMS.txt` checks the files.

The user's manual, in Chinese, is in [docs/](docs/README.md): setting it up, every key, gesture and setting. The question mark at the top right of the app's settings opens it.

### Releases

- **One release a month**, mostly for the dictionary: the words that have newly appeared. The app has no network access, so new words come only with new versions.
- A fix release whenever a bug is worth fixing at once.
- When the data in the installed version is more than six months old, the settings page says so.

### Next

- **Handwriting input.**
- **Better voice input**: more accurate recognition, and dialects.

### Data and licences

- Code: LGPL-2.1-or-later, as upstream.
- Pinyin dictionary, code tables, and the base of the pinyin language model: [libime](https://github.com/fcitx/libime) (LGPL-2.1-or-later).
- Counted and trained by this project:
  - the pinyin n-grams mixed in, from Chinese pages of [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) (ODC-By 1.0) and [Common Crawl](https://commoncrawl.org) (under its terms of use);
  - the English n-grams, from English pages of [FineWeb](https://huggingface.co/datasets/HuggingFaceFW/fineweb) (ODC-By 1.0);
  - the sentence models (Apache-2.0): trained on FineWeb-2's Chinese pages, then distilled from Qwen3.5-9B-Base's (Apache-2.0) scores of candidates;
  - the new words: found in Common Crawl's Chinese pages and curated one by one (`lexicon/`).
- Stroke table: [rime-stroke](https://github.com/rime/rime-stroke) (LGPL-3.0-only).
- Speech recognition: [X-ASR](https://github.com/Gilgamesh-J/X-ASR) (Apache-2.0), run with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) and [silero VAD](https://github.com/snakers4/silero-vad) (MIT).
- No model or dictionary in the app is trained on research-only data. The chat and dialog test sets come from [LCCC](https://github.com/thu-coai/CDial-GPT) (research use only) and are used only to evaluate and tune.
- [Privacy policy](PRIVACY.md).

### Building and development

See [BUILDING.md](BUILDING.md).

### Thanks

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android), [Fcitx5](https://github.com/fcitx/fcitx5), [libime](https://github.com/fcitx/libime),
[fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons), [FineWeb](https://huggingface.co/datasets/HuggingFaceFW/fineweb), [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2), [Common Crawl](https://commoncrawl.org), [Qwen](https://github.com/QwenLM), [X-ASR](https://github.com/Gilgamesh-J/X-ASR), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).
