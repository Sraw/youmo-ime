<p align="center"><img src="docs/images/logo.png" width="128" height="128" alt="幽默输入法"></p>

<h1 align="center">幽默输入法 · Youmo IME</h1>

<p align="center"><a href="#幽默输入法">中文</a> · <a href="#youmo-ime">English</a> · <a href="docs/README.md">使用说明</a> · <a href="CHANGELOG.md">版本日志</a> · <a href="https://github.com/Sraw/youmo-ime/releases">下载</a></p>

<p align="center"><a href="https://t.me/+smgndImIg7EzNjYx"><img src="https://img.shields.io/badge/Telegram-%E4%BA%A4%E6%B5%81%E7%BE%A4-26A5E4?logo=telegram&logoColor=white" alt="Telegram 交流群"></a></p>

---

## 幽默输入法

**完全离线**的 Android 中文输入法：拼音、九键、双拼、五笔等码表、手写、英文，语音版还能离线语音输入。
不申请网络权限，打的字、说的话都不会离开手机。

> 从 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 分出，保留 [Fcitx5](https://github.com/fcitx/fcitx5) 框架；
> 拼音引擎、语言模型和句子模型是自己做的。与 Fcitx5 官方无关，不会合并回上游，问题请在[本仓库](https://github.com/Sraw/youmo-ime/issues)反馈。

### 特色

- **整句拼音**：全拼、8 种双拼、九键（按 `64426` 就是"你好"）；简拼、半截输入，按到相邻键会自动纠正。
- **自己训练的模型**：拼音和英文的 n-gram 模型从网页统计而来；两个小型句子模型参考前文，给整句重新排序。
- **新词**：近几年的新词（显眼包、破防、红温……）逐个校对后收进词库，每次发版更新。
- **手写**：一次写一个字，按整句理解，后写的字能改正前面认错的。
- **码表**：五笔、仓颉、二笔、自然码、五笔拼音；重码按前文排，上屏后有联想。
- **英文键盘**：自动纠错、补全、预测下一个词。
- **滑行输入**：英文、拼音、九键、码表都能一笔划出一个词（默认关）。
- **表情和小工具**：候选里给出对应的表情，表情面板按拼音搜索；u 开头按部件拆字，算式后打 = 出结果，rq/sj/xq 出日期时间。
- **横屏分体、单手、悬浮键盘，实体键盘**：横屏时字母键分成左右两半；键盘可以缩到一边单手打，或变成能拖动的小窗；接实体键盘时候选显示在光标旁。
- **边打边学**：记住你选的词和顺序，常用的排到前面；能导入搜狗等输入法的词库；长按候选可以删除、固定、屏蔽，做错了马上撤销；学到的东西自动备份，换手机能恢复。
- **离线语音**（语音版）：按住空格说话，可以中英混说，自动加标点；带口音的普通话也能识别。目前只支持普通话和英语，还不支持方言。
- **越用越顺手**：记住你的用词；可以导入搜狗等词库；长按候选可以忘掉、置顶或屏蔽。

每项功能和设置的详细说明见[使用说明](docs/README.md)。

### 效果

拼音首选正确率（%），2026-10-19 测，开着相邻键纠错（App 默认如此）：

| | 主集 | 近期网页 | 聊天 | 对话 | 新词 |
|---|---|---|---|---|---|
| 只用 n-gram | 88.3 | 72.7 | 68.7 | 67.6 | 79.5 |
| 加句子模型（App 默认） | 90.9 | 76.2 | 72.7 | 71.2 | 80.0 |

九键打主集 88.8%。英文每 25 个键按错一个时，73.8% 的错词能自动改对。

### 下载

到 [Releases](https://github.com/Sraw/youmo-ime/releases) 下载：文字版约 310 MB，语音版约 470 MB（多申请录音权限）。
需要 Android 6.0 以上的 arm64 手机。模型和词库都在安装包里，所以包比较大。每月发一版，主要更新词库。

### 下一步计划

- **改进语音识别**：用真实的口音录音继续改进带口音的普通话，再支持粤语等方言。

### 许可与数据

- 代码：LGPL-2.1-or-later。构建方法见 [BUILDING.md](BUILDING.md)；[隐私政策](PRIVACY.md)。
- 词典、码表和拼音模型的基础来自 [libime](https://github.com/fcitx/libime)（LGPL-2.1-or-later），笔画表来自 [rime-stroke](https://github.com/rime/rime-stroke)（LGPL-3.0-only）。
- 表情列表来自 Unicode 的 [emoji-test.txt](https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt)，表情的中文关键词来自 [CLDR](https://github.com/unicode-org/cldr)（均为 Unicode-3.0）。
- 繁简转换的字表来自 [OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0，1.2.0 版的 `TSCharacters.txt`、`STCharacters.txt`，未改动），供学习把输入框里的繁体文字按简体读。
- 拆字数据来自开放词典《汉语拆字字典》[kfcd/chaizi](https://github.com/kfcd/chaizi)（[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)），按本项目的拼音数据给部件注音，未改动拆分。
- 自己统计和训练的部分：n-gram 和新词来自 [FineWeb](https://huggingface.co/datasets/HuggingFaceFW/fineweb)、[FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2)（ODC-By 1.0）和 [Common Crawl](https://commoncrawl.org) 的网页；
  句子模型（Apache-2.0）用 Qwen3.5-9B-Base（Apache-2.0）蒸馏，文件格式与结构沿用 [metasequoiaime/chinese-ime-lm](https://github.com/metasequoiaime/chinese-ime-lm)；手写模型只用合成笔迹训练（开源手写字体，以及 [Make Me a Hanzi](https://github.com/skishore/makemeahanzi) 的笔画，Arphic Public License），
  由 [onnxruntime](https://github.com/microsoft/onnxruntime)（MIT）运行。
- 语音识别：[X-ASR](https://github.com/Gilgamesh-J/X-ASR)（Apache-2.0），编码器由本项目为带口音的普通话微调：训练语音用 [CosyVoice3](https://github.com/FunAudioLLM/CosyVoice)（Apache-2.0）合成并加上 [Common Voice](https://commonvoice.mozilla.org)（CC0），以 [AISHELL-1](https://www.openslr.org/33/)（Apache-2.0）和 [LibriSpeech](https://www.openslr.org/12/)（CC BY 4.0）防止原有能力退化；由 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）和 [silero VAD](https://github.com/snakers4/silero-vad)（MIT）运行。
- 安装包里的模型和词库都没有用仅限科研的数据训练；[LCCC](https://github.com/thu-coai/CDial-GPT) 和 CASIA 只用来评测。
- 致谢：上面这些项目，以及 [fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons)、[Qwen](https://github.com/QwenLM)。

---

## Youmo IME

*幽默 (yōumò) means humor.*

An Android Chinese input method that runs **entirely offline**: pinyin, nine keys, shuangpin, Wubi and other code tables, handwriting,
English, and offline voice input in the voice build. It asks for no network permission; what you type and say never leaves the phone.

> Branched off [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android), keeping the [Fcitx5](https://github.com/fcitx/fcitx5) framework;
> the pinyin engine, language models and sentence models are its own. Not affiliated with Fcitx5, never merged upstream; report issues [here](https://github.com/Sraw/youmo-ime/issues).

### Features

- **Whole-sentence pinyin**: full pinyin, 8 shuangpin schemes, nine keys (`64426` is 你好); abbreviations, half-typed syllables, slipped keys corrected.
- **Models of its own**: pinyin and English n-gram models counted from web pages; two small sentence models reorder sentences by the text before.
- **New words**: the last few years' words, curated one by one, updated with every release.
- **Handwriting**: one character at a time, read as a sentence, so a later character puts an earlier misread right.
- **Code tables**: Wubi, Cangjie, Erbi, Ziranma, Wubi-Pinyin; candidates ordered by the text before, next-word suggestions.
- **English keyboard**: autocorrection, completions, next-word predictions.
- **Glide typing**: one stroke for a word in English, pinyin, nine keys and the code tables (off by default).
- **Emoji and small tools**: emoji among the candidates, the emoji panel searched by pinyin; characters typed by their parts (u…), sums worked out after =, the date and time from rq/sj/xq.
- **Split, one-handed and floating keyboards, physical keyboards**: in landscape the letters split in two halves; the keyboard shrinks to one side or floats as a small window you drag; with a physical keyboard, candidates show by the cursor.
- **Offline voice** (voice build): hold the space bar to talk, Mandarin and English mixed, punctuation added; accented Mandarin understood too. No dialects yet.
- **Learns as you type**: your words and the order you pick them in, the common ones first; imported dictionaries (Sogou and others); long-press a candidate to delete, pin or block it, with an undo; what it learned is backed up on its own and can be restored on a new phone.

The [user's manual](docs/README.md) (in Chinese) covers every feature and setting.

### Accuracy

Pinyin top-1 (%), measured 2026-10-19 with neighbouring-key correction on (the app's default):

| | Main | Recent web | Chat | Dialog | New words |
|---|---|---|---|---|---|
| n-gram alone | 88.3 | 72.7 | 68.7 | 67.6 | 79.5 |
| with the sentence models (the app's default) | 90.9 | 76.2 | 72.7 | 71.2 | 80.0 |

Nine keys, main set: 88.8%. English with one key in 25 slipping: 73.8% of slipped words corrected.

### Download

From [Releases](https://github.com/Sraw/youmo-ime/releases): text build about 310 MB, voice build about 470 MB (asks for the microphone too).
Needs an arm64 phone on Android 6.0 or later. The models and dictionary are inside the APK, hence its size. A release a month, mostly for new words.

### Next

- **Better voice recognition**: Mandarin with a regional accent further, from real accented speech, then dialects such as Cantonese.

### Licences and data

- Code: LGPL-2.1-or-later. Building: [BUILDING.md](BUILDING.md); [privacy policy](PRIVACY.md).
- Dictionary, code tables and the base of the pinyin model: [libime](https://github.com/fcitx/libime) (LGPL-2.1-or-later); stroke table: [rime-stroke](https://github.com/rime/rime-stroke) (LGPL-3.0-only).
- Emoji list: Unicode's [emoji-test.txt](https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt); their Chinese keywords: [CLDR](https://github.com/unicode-org/cldr) (both Unicode-3.0).
- Traditional-to-simplified character tables: [OpenCC](https://github.com/BYVoid/OpenCC) (Apache-2.0; 1.2.0's `TSCharacters.txt` and `STCharacters.txt`, unchanged), for learning to read traditional text in the field as simplified.
- Characters by their parts (拆字): the open 汉语拆字字典 [kfcd/chaizi](https://github.com/kfcd/chaizi) ([CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)), its parts read by this project's pinyin data, the splits unchanged.
- Counted and trained by this project: n-grams and new words from web pages of [FineWeb](https://huggingface.co/datasets/HuggingFaceFW/fineweb), [FineWeb-2](https://huggingface.co/datasets/HuggingFaceFW/fineweb-2) (ODC-By 1.0) and [Common Crawl](https://commoncrawl.org);
  sentence models (Apache-2.0) distilled from Qwen3.5-9B-Base (Apache-2.0), in the file format and architecture of [metasequoiaime/chinese-ime-lm](https://github.com/metasequoiaime/chinese-ime-lm); handwriting recognizers trained on synthetic ink only (open handwriting fonts and
  [Make Me a Hanzi](https://github.com/skishore/makemeahanzi)'s strokes, Arphic Public License), run by [onnxruntime](https://github.com/microsoft/onnxruntime) (MIT).
- Speech recognition: [X-ASR](https://github.com/Gilgamesh-J/X-ASR) (Apache-2.0), its encoder fine-tuned by this project for accented Mandarin on speech synthesized by [CosyVoice3](https://github.com/FunAudioLLM/CosyVoice) (Apache-2.0) and [Common Voice](https://commonvoice.mozilla.org) (CC0), held to the original by [AISHELL-1](https://www.openslr.org/33/) (Apache-2.0) and [LibriSpeech](https://www.openslr.org/12/) (CC BY 4.0); run with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) and [silero VAD](https://github.com/snakers4/silero-vad) (MIT).
- No model or dictionary in the app is trained on research-only data; [LCCC](https://github.com/thu-coai/CDial-GPT) and CASIA are used only to evaluate.
- Thanks to the projects above, and to [fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons) and [Qwen](https://github.com/QwenLM).
