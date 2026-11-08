# Data, build, release

## Building

| What | Command |
|---|---|
| The APK, debug | `./gradlew :app:assembleDebug` |
| The two resource packs (below) | `./gradlew :app:packs` → `app/build/packs/` |
| One ABI only (much faster) | add `-PbuildABI=arm64-v8a` (phones) (also an arm64 emulator) or `-PbuildABI=x86_64` (an x86_64 emulator, redroid) |
| Release as shipped: the APK, the packs signed with the release key, the two bundles and the pack listing | `./gradlew :app:releaseBundles -PbuildABI=arm64-v8a` (with `youmo.packKey` set) |
| A test build for profiling (simpleperf from the shell on an unrooted phone; never a release) | add `-PbuildProfileable=true` |
| Engine data alone | `./gradlew :app:compileEngineData` (and `:app:compileEnglishData`, `:app:copySentenceModels`) |
| Build-logic's own tests | `./gradlew -p build-logic test` |

Needs: SDK platform 36, build-tools 36.1.0, NDK 28.0.13004108, CMake 3.31.6 (or `cmake.dir` in
`local.properties`; for `lib/handwriting` and `lib/sherpa-onnx`), a JDK 21 to run Gradle (CI uses 21, as the local build, so the unit tests' screenshots are compared on the JDK they were recorded on;
the Android modules target Java 21, `lib/ime-core` stays at the JDK 11 API as a guard against JDK-only members; host tools take the JDK's own). Versions are in `build-logic/convention/src/main/kotlin/Versions.kt`. No submodules:
fcitx5 and chinese-addons are not in the tree. The few upstream files the app carries (punctuation
table, quick phrase tables, `charselectdata`) are in `app/src/main/host-assets/pkgdata`
(`host-assets.md`: source, SHA-256, licence); the generators of the host's golden files and key
tables fetch upstream fcitx5 442edbc9 and chinese-addons 0d3fd040 by `lib/ime-core/tools/upstream.sh`
(GitHub tarballs, SHA-256 pinned, cached).

`lib/keyboard-scene` is Kotlin Multiplatform, its JS tests run in node. Kotlin/JS downloads Node.js
itself; the repository's repositories are set in `settings.gradle.kts` only (`FAIL_ON_PROJECT_REPOS`),
so nodejs.org is declared there as an ivy repository for `org.nodejs:node` alone, the root build
turns off the plugin's own repository (`downloadBaseUrl` null), and node's archives are in
`gradle/verification-metadata.xml` (darwin and linux, arm64 and x64: add a platform's hash there to
run the JS tests on it). npm, not yarn (`kotlin.js.yarn=false`), and its test packages (mocha and
friends) are pinned with their integrity hashes by `kotlin-js-store/package-lock.json`. The root
build applies `base` because the Kotlin/JS plugin wants the root's `clean`.

Memory: compiling the pinyin data alone is ~4 GB resident (3.6 GB measured, in a JVM of its own;
BUILDING.md's 5 GB counts the Gradle daemon beside it) and three table compiles run at once
(1 GB each). The repository sets
`-Xmx2048m`; on a small machine run without the daemon and do not build while an emulator runs.

## Convention plugins (`build-logic/convention`)

| Plugin / file | Does |
|---|---|
| `AndroidAppConventionPlugin` | app defaults (SDKs, version from tags, signing from env/properties, R8), and `PermissionCheckTask`: fails the build if the merged manifest asks for a permission not on its allowlist |
| `AndroidLib…`, `AndroidBase…` | library and shared Android defaults |
| `EngineDataPlugin` | downloads (SHA-256 pinned) and compiles the engine's data into generated assets, stored uncompressed (see below) |
| `AssetPacksPlugin` | the resource packs (below): `packTextPack`, `packVoicePack` (`packs` both), `releaseBundles`; the trusted public keys into `BuildConfig.PACK_KEYS` |
| `VoiceDataPlugin` | the voice pack's files: the recognizer's models, the polish tagger (from `youmo.voiceModelDir`, which an APK build needs for now: the pinned release is the offline model), hotword list |
| `HandwritingDataPlugin` | `:lib:handwriting` (onnxruntime, `pickFirst` with sherpa-onnx's identical copy) and the handwriting models (the text pack's) |
| `BuildMetadataPlugin` | build time and git hash into `BuildConfig` (the data release names are `EngineDataPlugin`'s fields) |
| `DetektConventionPlugin` | detekt on every Kotlin module, one config, a baseline per module |
| `ToolFingerprint` | hashes the classes the dict tool's `main` reaches, so the data task re-runs only when code that makes data changes, not on every ime-core edit |
| `DownloadTasks`, `Hashes` | downloads pinned by SHA-256, and the hashing they and the fingerprints use |
| `Utils`, `Versions` | shared helpers; SDK and tool versions |
| `NativeAppConventionPlugin` | ABI splits (`-PbuildABI`, one APK per ABI) and a version code per ABI |
| `UnitTestResourcesPlugin` | gives Robolectric the app's linked resources (res + manifest) without its assets, so unit tests do not depend on merged assets and the engine data they drag in |
| `ProjectExtensions` | version name from `git describe --tags --long --match v[0-9]*`; ABI selection (`-PbuildABI`) for the native libraries, which `lib/handwriting` and `lib/sherpa-onnx` build with their own CMake through `lib-convention` |

Any edit to build-logic changes the data task's implementation, so the next build compiles the
engine data again (a few minutes). Expected.

## The engine's data

Nothing big is made in an app build. Each part is made by a release-time job, published as a public
GitHub release of this repository, and pinned in `EngineDataPlugin` by tag and SHA-256:

| Part | Release | Made by | In the text pack (or voice pack, or APK) as |
|---|---|---|---|
| Pinyin language model (libime's, mixed with chat-like pages of FineWeb-2 and a CommonCrawl crawl) | `engine-data-*` (`LM_RELEASE`) | `lib/ime-dict-tool/engine-data.sh` on a rented 16-core machine, ~1 h, ~100 GB down | compiled with libime's dictionary into `engine/pinyin.data` |
| New words (curated `lexicon/add.tsv`, packed for that model) | `words-*` (`WORDS_RELEASE`) | `ime-dict-tool pack --lexicon lexicon/add.tsv` | the `new` layer of `pinyin.data` |
| English words and trigram model (FineWeb sample-10BT) | `english-data-*` (`ENGLISH_RELEASE`) | `ime-dict-tool english-lm`, then `prune --threshold 3e-8` (relative entropy; english-data-20261015: 46 MB compiled, slips fixed 73.8%; at english-data-20261012 the prune took 117 → 44 MB and 73.8 → 72.7%) | `engine/english.data` |
| Sentence models 4M and 25M (own training, Apache-2.0) | `sentence-models-*` (`MODELS_RELEASE`) | the training pipeline (not in this repo) | `engine/sentence-model*.safetensors` |
| Voice recognizer (X-ASR zh-en, sherpa-onnx's int8 export, its decoder int8 by us, its encoder fine-tuned by us for accented Mandarin: LoRA on CosyVoice3-synthesized dialect speech and Common Voice, held to the original by AISHELL-1/LibriSpeech anchor batches, merged) in onnxruntime's ORT format | `voice-models-*` (`ORT_RELEASE` in `VoiceDataPlugin`) | onnxruntime 1.30's `convert_onnx_models_to_ort --optimization_style Fixed --target_platform arm` (its `manifest.txt`) | `voice/encoder.ort`, `decoder.ort`, `joiner.ort`, mapped in place (lib/sherpa-onnx README) |
| Handwriting recognizers (own training on synthetic ink), their characters and frequencies | `handwriting-models-*` (`RELEASE` in `HandwritingDataPlugin`) | the recognizers' training sources (not in this repo): `export.py` to ONNX; the dense and LSTM weights int8 by onnxruntime's `quantize_dynamic` (the release's `quantize.py`, from the float release's ORT files); onnxruntime 1.30's `convert_onnx_models_to_ort --optimization_style Fixed --target_platform arm` (its `manifest.txt`) | `handwriting/*.ort` mapped in place (`OrtModel`), `chars.txt`, `prior.data` |
| Tones (候选词注音, the zhuyin keyboards' tone keys): Unihan's kMandarin, kHanyuPinlu, kXHC1983, kTGHZ2013, kHanyuPinyin (Unicode-3.0) as mozillazg/pinyin-data (MIT, `UNIHAN_COMMIT`) lists them, in that order (a character's first reading is its usual tone); the words of mozillazg/phrase-pinyin-data's `pinyin.txt` (MIT, `PHRASES_COMMIT`), not its `large_pinyin.txt` (CC-CEDICT, share-alike, and 汉典's, no licence) | pinned files, SHA-256 checked | compiled with the dictionary, `lexicon/tones.tsv` and `misreadings.tsv`'s readings after them (`--char-tones`, `--phrase-tones`, `--tones`) | the tones sections of `engine/pinyin.data` (`ToneTable`); measured by `ime-eval readings`. Version 4 adds each character's readings in order with each reading's commonest words, a dictionary's only (phrase-pinyin-data's, `tones.tsv`'s or `misreadings.tsv`'s, not the model's others: 荣和, 去呢) (`CharReadings`, sections 29–34, `CharReadingsBuilder`: which readings 通用规范 ∪ 现汉, else 频率词典's, else kMandarin's; for the candidates a neutral tone only where the syllable has no full tone or kMandarin reads it so, the card's the others too, flagged `CARD_ONLY` in the tone byte, last; the order 频率词典's, then the model's words) |
| Code tables, strokes, dictionary | libime's and rime-stroke's sources, pinned archives | compiled in the build | `engine/table/*.data`, `engine/stroke.data` |
| Emoji: the panel's list and groups (Unicode's `emoji-test.txt`, `EMOJI_TEST`), their Chinese keywords (CLDR's `annotations/zh.xml`, `CLDR_RELEASE`), both Unicode-3.0 | pinned files, SHA-256 checked | `ime-dict-tool emoji` with `lexicon/emoji.tsv` and the compiled `pinyin.data` (each word's pinyin for the panel's search, the commoner word first), in the build (`compileEmojiData`) | `engine/emoji.data`, read by the emoji panel and the engine (`EmojiData`) |
| Characters by their parts (拆字): the open 汉语拆字字典's `chaizi-jt.txt` (kfcd/chaizi, CC BY 3.0, `CHAIZI_COMMIT`) | pinned file, SHA-256 checked | `ime-dict-tool chaizi` with the compiled `pinyin.data` (each part's likeliest reading, a radical read as the character it stands for, 艹 as 草; the commoner character first), in the build (`compileChaiziData`) | `engine/chaizi.data`, read by full pinyin after `u` (`Chaizi`) |
| A character's radical, strokes and parts (its card): Unihan's kRSUnicode and kTotalStrokes in `Unihan.zip`, each radical's character in `CJKRadicals.txt` (both Unicode 17.0.0, Unicode-3.0), the parts of `chaizi-jt.txt` as above, rime-stroke's orders for a character Unihan counts no strokes of, `lexicon/stroke-order.tsv`, this project's (6,940 simplified characters' and parts' stroke orders as the mainland's 《现代汉语通用字笔顺规范》 gives them, each stroke by its shape in Unicode's CJK Strokes names (HZG 横折钩), its kind 1–5 following; first taken from cnchar's `stroke-order-jian.json`, theajack/cnchar, MIT, its notice in the file's head; cnchar's slips corrected in place, 皙 德 膝 …, and the shapes it writes alike told apart by the part a stroke is in: 横撇 / 横钩, 竖折 / 竖弯 …) | pinned files, SHA-256 checked; the stroke orders checked in | `ime-dict-tool charinfo` (the radical as the mainland writes it where the parts show it, 氵 扌 艹: `CharInfoBuilder.VARIANTS`; `--cover`: how many of the characters handwriting knows have each, and readings; under 94% with the mainland's order fails, about 95% have it; an order whose strokes are not as many as Unihan's kTotalStrokes, or whose kinds are none of rime-stroke's orders of the character, fails unless its line says why: 范 骨 and the 艹 of 茔 荥 荦 荧 莹 萦, which Unihan counts as Taiwan's; a note with nothing to say why of fails too; a character with the order counts its strokes as it does), in the build (`compileCharInfo`) | `engine/charinfo.data` (`DataFile.KIND_CHAR_INFO`, version 3, each stroke a `StrokeShape`), read for a character's card (`CharInfo`): its stroke order the mainland's, for a character it lacks the stroke table's (`engine/stroke.data`) order of Unihan's count, else its first; its strokes counted as the order shown is; none, the card has no radical |
| Traditional-to-simplified character tables: OpenCC's `TSCharacters.txt`, `STCharacters.txt` (Apache-2.0), verbatim from tag [ver.1.2.0](https://github.com/BYVoid/OpenCC/tree/ver.1.2.0/data/dictionary) (SHA-256 `ad870b4f…`, `9cedfb8b…`), the version fcitx's chttrans has | checked in | copied | `app/src/main/assets/learn/`, read by `Simplifier` for the learning record (the text before the cursor read simplified) |

Downloads go to `.gradle/engine-downloads` at the root (survives `clean`). The build also applies
`lexicon/remove.tsv`, `readings.tsv`, `misreadings.tsv`, `tones.tsv`, `latin.words` and `emoji.tsv` when compiling.

To ship new data: run the job, check its `report.txt` against the last release's numbers, publish the
files as a new release (public: the build downloads anonymously), update the tag and SHA-256 in
`EngineDataPlugin`, rebuild, run the evaluation (see [testing.md](testing.md)), and update the
numbers in README if they moved. Data releases still in use are never deleted.

A change to how `english-lm` counts (`EnglishVocabulary`'s words and slips, `EnglishCounts`,
`EnglishText`'s sentences) reaches the app only with the next `english-data` release. Before
publishing one, run `english-lm` on the same shards with the dict tool as at the last release and as
now; compare the two `pass one` lines (words, slips, and how many are under a tenth of their group's
commonest, held to another spelling) and the two models' words (their 1-grams); and run ime-eval's
`english` set on both compiled models. `EnglishVocabulary.CONTRACTION_SHARE` (1%), the least share of
its group's commonest that a spelling held to another must have (we'll beside well, wed beside we'd:
only where a contraction has a real word's letters, `EnglishDecoder.LIKE_CONTRACTIONS`), is not tuned
on FineWeb: it should drop slips such as we'l'l and keep words such as we'll. A possessive (user's) is
held to its group's commonest, as any other spelling.

### Models: int8, mapped in place, one onnxruntime

Decided by the owner (2026-10-15), for every model the app ships, the ones to come too:

- **int8 weights, dynamic, convolutions float.** A model ships with its weights in int8, quantized
  dynamically: onnxruntime's `quantize_dynamic`, per channel, `op_types_to_quantize` the matrix
  multiplications (Gemm written as MatMul), LSTM/GRU and embeddings (Gather), the activations
  quantized as each layer runs. The voice recognizer is sherpa-onnx's int8 export, made so (its
  encoder's 344 MatMulInteger, its 82 Conv float), with its decoder quantized the same way by us;
  the handwriting recognizers by the release's `quantize.py`; the sentence models are int8
  safetensors (a scale per output row, `MatrixKernel`).
  - **Convolutions are never quantized.** Measured: the handwriting image model with its
    convolutions int8 too ran 15.6 → 44.3 ms a character (onnxruntime's ConvInteger is a slow
    generic kernel that converts the activations to int8 and back at every layer, where the float
    convolution is its tuned one), to save 12 MB; the voice decoder's one Conv1d int8 made each
    step 0.111 → 0.129 ms where float convolution and int8 elsewhere made it 0.077 ms. Conv
    weights are a small part of a model; the matrices are its size and their time.
  - **Not static** (activation ranges calibrated beforehand, QLinearConv/QLinearMatMul): it needs
    calibration data that is like the real input, risks accuracy where it is not, and what it buys
    is speed on convolutions, measured nowhere yet. Worth trying only for a convolutional model, on
    a phone with int8 dot-product instructions, judged by that phone's numbers.
  - Before a release the int8 model is measured against its float one on its evaluation set, and
    the release's `manifest.txt` gives both: int8 cost nothing measurable so far (handwriting on
    CASIA: 91.44% → 91.43%, the models 121 → 43 MB). Another layer than a convolution stays float
    only where int8 of it was measured to cost accuracy or time, and the manifest says so.
- **Mapped from the file, never loaded.** A model or data file is stored uncompressed (in its
  pack, which is copied as files into the app's storage; in the APK `noCompress`) and memory-mapped
  where it lies, so its weights are clean file pages the system can
  drop and read again, not the app's heap. ORT models: the file mapped (`AppData.map`), the
  session made from those bytes with `session.use_ort_model_bytes_directly` and
  `session.use_ort_model_bytes_for_initializers`, and `session.disable_prepacking`, as prepacking
  copies weights to the heap (`OrtModel`, lib/sherpa-onnx's README); `.data` and `.safetensors`
  through `EngineBridge.asset`. Never read a model into a byte array. Checked with `dumpsys meminfo`:
  the model's size shows under `.apk mmap`, not in the private columns.
- **One onnxruntime version** for the runtime the app links (`ortVersion` in
  `lib/handwriting/build.gradle.kts` and `lib/sherpa-onnx/src/main/cpp/cmake/onnxruntime-android-*.cmake`,
  which that build script checks pin the same archive: onnxruntime-libs' prebuilt), the converter
  that makes the ORT files
  (`convert_onnx_models_to_ort --optimization_style Fixed --target_platform arm`) and the quantizer.
  An ORT file loads only in a runtime at least as new as the one that wrote it. To move to another
  version: change the runtime's pins, convert every ORT model again with it, publish them as new
  releases, and measure each against the last on its set.

### Licences of what ships

Everything in the APK must be licensed for distribution. Before adding a corpus, a dictionary or a
pretrained model, read both its licence and its README/data card for use restrictions
("research only", non-commercial), and the training data of any model. LCCC is research-only: it is
an evaluation set in `lib/ime-eval`. It measures, and it helps choose parameters that ship: the
language model's mix weight (`engine-data.sh`'s score counts its chat and dialog sets) and
`Predictor.USER_WEIGHT`. No model or dictionary is trained on it. New third-party data gets an
entry in `app/licenses/libraries/` and in README's data section.

### `lexicon/`

The curated decisions: `add.tsv` (new words with readings), `reject.tsv` (never again),
`remove.tsv` (libime words that should not be there), `readings.tsv` (libime words read wrongly),
`misreadings.tsv`, `tones.tsv` (words whose tones the downloaded lists get wrong), `latin.words`. See `lexicon/README.md` for how a candidate is judged and how a
batch is curated. A word in either list is never put before a curator again.

## Resource packs

Decided by the owner for 0.6.0 (dev/design/060-asset-packs.md): the APK carries no dictionary or
model. They are two packs the user imports on the 资源包 page (`PacksFragment`), from files picked
with the system's picker (several at once); the app keeps no network permission.

| Pack | Holds (asset paths, `PackKind`) | Without it |
|---|---|---|
| `text` (文字包) | `engine/pinyin.data`, `english.data`, `sentence-model*`, `stroke.data`, `table/`, `handwriting/` | the keyboard is `PackGateView`: 需要导入文字包, a tap opens the page, its 切换输入法 button the system's keyboard picker |
| `voice` (语音包) | `voice/` (recognizer, VAD, hotwords, polish tagger) | the microphone and the space's hold say 语音输入需要导入语音包; a tap opens the page |

What stays in the APK (assets 6.7 MB of a 21 MB release APK; 0.6.0's text pack is 311 MB, the voice pack 185 MB): what the
app reads with no pack or is small and belongs to no input method's data: `engine/emoji.data` (the emoji panel), `chaizi.data`,
`charinfo.data` (a character's card), `opencc-s2tw.data` (traditional output), `learn/` (OpenCC's tables), the host's
`pkgdata` (punctuation, quick phrases, `charselectdata` for Unicode input). Moving them would gain under 7 MB and make the
emoji panel, symbols and Unicode input need the text pack too. The native libraries (onnxruntime 25 MB of them) are code, so
they stay: a pack carries no code.

- **Format.** A `.youmopack` is a zip, every entry STORED: `manifest.json` first (`PackManifest`: pack,
  version, format, signing key's name, each file's path, size, SHA-256), `manifest.sig` (64 bytes,
  Ed25519 over the manifest's bytes) second, then the files. The build writes the same bytes from the
  same inputs (entry order and times fixed) and a `.json` listing beside each pack.
- **Versions apart from the app** (`app/packs.properties`): a pack's `version` is raised when its content
  changes (`releaseBundles` fails a changed pack under the same version), `format` when the code can no
  longer read the older data, and the app's `minVersion` when it needs data only a newer pack has. The
  app refuses another format and says which pack and version it needs; a pack below `minVersion` stays
  installed but unused (需要更新到 vN), one above what the app knows says 需要更新应用.
- **Signing.** Ed25519, pure Kotlin in ime-core (`Ed25519`, RFC 8032 vectors); the build signs with the
  JDK's. The APK trusts the keys in `BuildConfig.PACK_KEYS`: `app/pack-keys/release.pub`'s, and the
  committed test key (`test-ed25519.pem`, `test.pub`) too only in the build types listed in
  `AssetPacksPlugin.TEST_KEY_BUILDS` (debug, which the tests run, and benchmark, debug-signed and never
  shipped); any other build type, release and any added later, trusts release.pub's only and fails to
  build without one (`AssetPacksPluginTest` holds release's to release.pub). Several keys may be listed, for a key change: old and new packs
  both load. The release key's private half is never in the repo: its path is `youmo.packKey` in
  local.properties (or `YOUMO_PACK_KEY`); without it `:app:packs` signs with the test key, which release
  builds refuse.
- **Import** (`PackShelf` in ime-core, `PackStore`/`PackImports` in the app): the manifest's signature is
  checked first, then each file is copied into a new generation directory while its size and SHA-256 are
  checked, after a free-space check against the manifest's sizes; only when all are right is the
  `current` pointer swapped atomically (rename; the pack's directory fsynced after each rename, so a power cut keeps them). A pack already there at that version (the same
  manifest) is not copied again if its files in use, read through then, still are it; else it is
  copied anew. An older version over a newer one is imported (a pack gone wrong taken back; it must
  still meet the app's `minVersion`), the result saying 已用 vX 替换了更新的 vN. The generation replaced is deleted only once the host is up
  on the new one (`HostDaemon.restartIfRunning` waits for it, and for a host still starting before
  restarting it: that one may have read the old files), then `PackShelf.sweep`; a host still starting
  after 10 s (before or after the restart) is not known to have left them: not deleted then, left
  for the next start's load. A killed import leaves the old pack in use; the next start deletes what is not `current`.
  What fails after the copy (the restart, the state told) is logged: the import stands, counted.
  The user's file is never deleted; the results say they may delete it.
- **Storage.** Device-protected (`createDeviceProtectedStorageContext().filesDir/packs`), so Direct Boot
  reads it before the first unlock, and an upgrade keeps it. Excluded from cloud and key/value backup
  (over 25 MB); a device transfer carries it. Loading checks the signature and each file's size, not
  every hash (that would read hundreds of MB at each start). Thirty seconds after the start, a
  thread of the lowest priority reads each pack in use through (`PackStore.verifyInBackground`,
  `PackShelf.verify`): one changed is `PackStatus.Damaged`, not used till imported again (the gate:
  文字包已损坏，请重新导入; the page's row: vN 已损坏，请重新导入; voice input's tip the same).
- **Reading** (`AppData`): every asset path the engine, handwriting and voice open goes through it, to
  the pack's file if a pack owns the path, else to the APK's asset; `.data`, `.safetensors` and `.ort`
  are memory-mapped from the file. When a pack changes, `PackStore.state` bumps a generation and the
  service makes the keyboard and engine anew.
- **Tests.** JVM tests use `TestShelves` (a shelf of tiny test-key packs, `PackStore.useShelf`;
  `KeyboardHarness` installs both), and engine-data tests read the build's generated directories as
  before. Devices: the debug build's `PackImportProvider` (shell or root only) takes `content write` +
  `content call import`, as `uat/tools/uat.py packs` and CI's emulator job do; a release UAT imports
  through the picker.

## Native code

The app has no native code of its own; the fcitx host is Kotlin ([host.md](host.md)).
`lib/handwriting` builds `libyoumo-handwriting` (linked to onnxruntime, loaded only when
handwriting is used) and `libyoumo-matrix` (`matrix-kernel.cpp`, the sentence models' kernel,
`-O3` even in debug, no onnxruntime); `NativeMatrixKernel.loaded` falls back to
`MatrixKernel.JVM` when it does not load. `lib/sherpa-onnx` builds the recognizer (its models are the voice pack's).
The APK's `.so` files are those, onnxruntime's and `libc++_shared`. Host tests for native helpers:
`lib/sherpa-onnx/host-test.sh` (CI runs it).

When adding a method to `HostApi`, read the Kotlin incremental compilation
trap in [pitfalls.md](pitfalls.md#kotlin-ic-and-interface-delegation) before building an APK.

## Versions and releases

- The version name comes only from `v*` tags, lightweight or annotated (`git describe --tags`; a
  release is tagged with `git tag -a`): `0.1.0` at `v0.1.0`, `0.1.0-5-gabc1234` five commits later,
  `Versions.baseVersionName` marked `-untagged` (`0.1.0-untagged`) with no tag, as a source archive
  or a fork without the tags builds: never the name of a release.
- `versionCode = baseVersionCode × 10 + ABI id`, and `baseVersionCode` follows from
  `Versions.baseVersionName` (`versionCodeOf`: 12 at 0.1.0, 1 more a patch, 100 a minor, 10,000 a
  major; minor and patch below 100). Raise `baseVersionName` for each release.
- A release: bump versions (and a pack's `version` in `app/packs.properties` if its content
  changed) → commit → `git tag -a vX.Y.Z` → `./gradlew :app:releaseBundles -PbuildABI=arm64-v8a`
  with the APK signed with the release key (kept outside the repo; `signKeyFile`/`signKeyAlias`/`signKeyPwd` in
  `~/.gradle/gradle.properties` or `SIGN_KEY_*` env; unsigned if absent) and the packs with the pack
  key (`youmo.packKey`, below) → install over the last
  release and type on it, on Android 10 too (the AVD `youmo29`, testing.md: a misreading's hint, the 快速输入 page with an entry) → GitHub release with the APK, the packs (`release-packs.json` says
  which changed: an unchanged one need not be uploaded again), the two bundles (文字版, 语音版) and `SHA256SUMS.txt`, notes in Chinese
  and English saying which packs are new → copy `app/build/release-bundles/release-packs.json` to
  `app/packs-released.json` and commit it: the next release compares with it. `releaseBundles`
  fails with no such listing (`app/packs-released.json`, or `-PpreviousPacks=<file>`), unless
  given `-PfirstPackRelease` (the first release with packs, 0.6.0), which fails if there is one.
  A pack's content is its format and its files (`contentSha256`, the app's `PackManifest` and the
  plugin alike): either changed under the same `version` fails the task.
- Test builds between releases go up as **draft** releases (the repository is public) and are
  deleted once a release supersedes them. Data releases are public and stay.
- Package `io.github.sraw.youmo` (debug: `.debug` suffix), installable beside upstream Fcitx5.

## CI (`.github/workflows`)

| Workflow | When | Runs |
|---|---|---|
| `unit_test.yml` | push, PR | four jobs side by side (setup in `.github/actions/setup-build`): `:lib:ime-core:check`, dict-tool and eval tests, build-logic tests, `:app:testDebugUnitTest` and the same at SDK 29 (a job each, `-PskipExhaustive`, two test JVMs at once), sherpa-onnx's host tests, Python tool tests (`lexicon/tools`, `lib/ime-eval/tools`), detekt, Kover reports, test failures annotated |
| `key_marks.yml` | push, PR changing the keys' marks or drawing (its `paths`); Tue 03:37; on demand | `KeyMarksEverywhereUatTest` alone at SDK 35 and 29, which the app jobs above leave out (half their time) |
| `pull_request.yml` | PR | `assembleRelease` and `packs` on Ubuntu (the APK unsigned, the packs with the test key) |
| `engine_downloads.yml` | push to main changing `EngineDataPlugin.kt`, `HandwritingDataPlugin.kt`, `lib/handwriting/build.gradle.kts` or the workflow; Mon/Thu 04:41; on demand | the engine data's download tasks alone (the handwriting models and onnxruntime too, a cache of their own), saving the `engine-downloads-*` cache a new pull request's build reads |
| `emulator_test.yml` | weekly, and PRs labelled `emulator` | instrumented tests (`HostTest`, `SoftKeyboardTest`, `VoiceEngineTest` …) on API 35 and API 29 (minSdk) emulators, the two packs imported first through `PackImportProvider` |
