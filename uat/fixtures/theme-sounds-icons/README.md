# theme-sounds-icons

060-theme-sounds-icons 的夹具（uat/14-settings.md 的 ST-89–ST-92（ST-91 用 bad/））。`theme.xml` 是内置 Nord 改名 `uat-sounds-icons`，
换了 backspace、enter、shift 三个图标，空格上 `icon(star)`，带五类按键音。脚本 `push_icons_pack`（uat/scripts/14-settings.py）把它打成 .theme。

| 文件 | 内容 |
|---|---|
| `letter.wav` | 16 位 PCM，44.1 kHz，单声道，80 ms |
| `function.wav` | 8 位 PCM，8 kHz，单声道，100 ms |
| `space.ogg` | Vorbis，48 kHz，双声道，120 ms |
| `enter.ogg` | Vorbis，8 kHz，单声道，150 ms |
| `backspace.mp3` | MPEG-1 Layer III，44.1 kHz，双声道，带 ID3v2 和 Xing |
| `short.mp3` | MPEG-2 Layer III，22.05 kHz，单声道，没有标签（逐帧数时长），不在 theme.xml 里 |
| `backspace.svg` | `viewBox="0 -960 960 960"` |
| `enter.svg` | 非正方形 viewBox（48×24） |
| `shift.svg` | 只有 width/height；一条路径 `currentColor`，一条写死 `#f80` |
| `star.svg` | 一条着色路径，一条写死 `#ff000080`（半透明红） |
| `bad/` | 导入该被拒的：`big.wav`（>100 KB）、`deep.wav`（24 位）、`long.wav`（2 s）、`opus.ogg`、`wav-named.mp3`、`junk-tail.mp3`、`no-fill.svg`、`zero-opacity.svg` |

声音由 ffmpeg 的 `sine` 源生成（`-c:a pcm_s16le`/`pcm_u8`/`vorbis -strict -2`/`libmp3lame`/`libopus`）；`enter.ogg` 是单声道，ffmpeg 自带的 Vorbis 编码器只做双声道，改用 libsndfile（python `soundfile`）写。
