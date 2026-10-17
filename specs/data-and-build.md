# Data, build, release

## Building

| What | Command |
|---|---|
| Text build, debug | `./gradlew :app:assembleTextDebug` |
| Voice build, debug | `./gradlew :app:assembleVoiceDebug` |
| One ABI only (much faster) | add `-PbuildABI=arm64-v8a` (phones) or `-PbuildABI=x86_64` (redroid, emulator) |
| Release, both builds, as shipped | `./gradlew :app:assembleTextRelease :app:assembleVoiceRelease -PbuildABI=arm64-v8a` |
| Engine data alone | `./gradlew :app:compileEngineData` (and `:app:compileEnglishData`, `:app:copySentenceModels`) |
| Build-logic's own tests | `./gradlew -p build-logic test` |

Needs: SDK platform 36, build-tools 36.1.0, NDK 28.0.13004108, CMake 3.31.6 (or `cmake.dir` in
`local.properties`), extra-cmake-modules, gettext ≥ 0.20, a JDK 17 or newer to run Gradle (CI uses 17;
code targets Java 11). Versions are in `build-logic/convention/src/main/kotlin/Versions.kt`. Submodules:
`git submodule update --init --recursive` (fcitx5, its prebuilt dependencies, chinese-addons).

Memory: compiling the pinyin data alone is ~4 GB resident (3.6 GB measured, in a JVM of its own;
BUILDING.md's 5 GB counts the Gradle daemon beside it) and three table compiles run at once
(1 GB each); a native rebuild of fcitx5 peaks around 4 GB with Gradle. The repository sets
`-Xmx2048m`; on a small machine run without the daemon and do not build while an emulator runs.

## Convention plugins (`build-logic/convention`)

| Plugin / file | Does |
|---|---|
| `AndroidAppConventionPlugin` | app defaults (SDKs, version from tags, signing from env/properties, R8), and `PermissionCheckTask`: fails the build if the merged manifest asks for a permission not on its allowlist |
| `AndroidLib…`, `AndroidBase…` | library and shared Android defaults |
| `Native…ConventionPlugin`, `NativeBuildTasks` | CMake/NDK setup, ABI selection (`-PbuildABI`) |
| `FcitxComponentPlugin`, `FcitxHeadersPlugin` | build fcitx's CMake targets and install their files into the app's assets; export headers |
| `DataDescriptorPlugin` | `descriptor.json`: the SHA-256 of every file under `src/main/assets`, so `DataManager` syncs only what changed |
| `EngineDataPlugin` | downloads (SHA-256 pinned) and compiles the engine's data into generated assets, stored uncompressed (see below) |
| `VoiceDataPlugin` | voice build only: the recognizer's models, VAD, hotword list |
| `HandwritingDataPlugin` | both builds: `:lib:handwriting` (onnxruntime, `pickFirst` with sherpa-onnx's identical copy) and the handwriting models |
| `BuildMetadataPlugin` | build time and git hash into `BuildConfig` (the data release names are `EngineDataPlugin`'s fields) |
| `DetektConventionPlugin` | detekt on every Kotlin module, one config, a baseline per module |
| `ToolFingerprint` | hashes the classes the dict tool's `main` reaches, so the data task re-runs only when code that makes data changes, not on every ime-core edit |
| `ProjectExtensions` | version name from `git describe --tags --long --match v[0-9]*` |

Any edit to build-logic changes the data task's implementation, so the next build compiles the
engine data again (a few minutes). Expected.

## The engine's data

Nothing big is made in an app build. Each part is made by a release-time job, published as a public
GitHub release of this repository, and pinned in `EngineDataPlugin` by tag and SHA-256:

| Part | Release | Made by | In the APK as |
|---|---|---|---|
| Pinyin language model (libime's, mixed with chat-like pages of FineWeb-2 and a CommonCrawl crawl) | `engine-data-*` (`LM_RELEASE`) | `lib/ime-dict-tool/engine-data.sh` on a rented 16-core machine, ~1 h, ~100 GB down | compiled with libime's dictionary into `engine/pinyin.data` |
| New words (curated `lexicon/add.tsv`, packed for that model) | `words-*` (`WORDS_RELEASE`) | `ime-dict-tool pack --lexicon lexicon/add.tsv` | the `new` layer of `pinyin.data` |
| English words and trigram model (FineWeb sample-10BT) | `english-data-*` (`ENGLISH_RELEASE`) | `ime-dict-tool english-lm`, then `prune --threshold 3e-8` (relative entropy: 117 → 44 MB compiled, slips fixed 73.8 → 72.7%) | `engine/english.data` |
| Sentence models 4M and 25M (own training, Apache-2.0) | `sentence-models-*` (`MODELS_RELEASE`) | the training pipeline (not in this repo) | `engine/sentence-model*.safetensors` |
| Voice recognizer (X-ASR zh-en, sherpa-onnx's int8 export, its decoder int8 by us, its encoder fine-tuned by us for accented Mandarin: LoRA on synthesized dialect speech, merged) in onnxruntime's ORT format | `voice-models-*` (`ORT_RELEASE` in `VoiceDataPlugin`) | onnxruntime 1.30's `convert_onnx_models_to_ort --optimization_style Fixed --target_platform arm` (its `manifest.txt`) | `voice/encoder.ort`, `decoder.ort`, `joiner.ort`, mapped in place (lib/sherpa-onnx README) |
| Handwriting recognizers (own training on synthetic ink), their characters and frequencies | `handwriting-models-*` (`RELEASE` in `HandwritingDataPlugin`) | the recognizers' training sources (not in this repo): `export.py` to ONNX; the dense and LSTM weights int8 by onnxruntime's `quantize_dynamic` (the release's `quantize.py`, from the float release's ORT files); onnxruntime 1.30's `convert_onnx_models_to_ort --optimization_style Fixed --target_platform arm` (its `manifest.txt`) | `handwriting/*.ort` mapped in place (`OrtModel`), `chars.txt`, `prior.data` |
| Code tables, strokes, dictionary | libime's and rime-stroke's sources, pinned archives | compiled in the build | `engine/table/*.data`, `engine/stroke.data` |
| Emoji: the panel's list and groups (Unicode's `emoji-test.txt`, `EMOJI_TEST`), their Chinese keywords (CLDR's `annotations/zh.xml`, `CLDR_RELEASE`), both Unicode-3.0 | pinned files, SHA-256 checked | `ime-dict-tool emoji` with `lexicon/emoji.tsv` and the compiled `pinyin.data` (each word's pinyin for the panel's search, the commoner word first), in the build (`compileEmojiData`) | `engine/emoji.data`, read by the emoji panel and the engine (`EmojiData`) |

Downloads go to `.gradle/engine-downloads` at the root (survives `clean`). The build also applies
`lexicon/remove.tsv`, `readings.tsv`, `misreadings.tsv`, `latin.words` and `emoji.tsv` when compiling.

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
- **Mapped from the file, never loaded.** A model or data file is stored uncompressed in the APK
  (`noCompress`) and memory-mapped where it lies, so its weights are clean file pages the system can
  drop and read again, not the app's heap. ORT models: the asset opened `AASSET_MODE_BUFFER`, the
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
`misreadings.tsv`, `latin.words`. See `lexicon/README.md` for how a candidate is judged and how a
batch is curated. A word in either list is never put before a curator again.

## Native code

`app/src/main/cpp` builds `native-lib` (JNI), the fcitx frontend and the addons the app adds
(`androidengine`, `androidfrontend`, `androidaddonloader`, `androidnotification`) and
`matrix-kernel.cpp`. `lib/fcitx5` builds Fcitx5 itself from the submodule with prebuilt
dependencies (`lib/fcitx5/src/main/cpp/prebuilt`). Host tests for native helpers:
`app/src/test/cpp/host-test.sh`, `lib/sherpa-onnx/host-test.sh` (CI runs both).

When adding a method to the JNI surface or to `FcitxAPI`, read the Kotlin incremental compilation
trap in [pitfalls.md](pitfalls.md#kotlin-ic-and-interface-delegation) before building an APK.

## Versions and releases

- The version name comes only from `v*` tags, lightweight or annotated (`git describe --tags`; a
  release is tagged with `git tag -a`): `0.1.0` at `v0.1.0`, `0.1.0-5-gabc1234` five commits later,
  `Versions.baseVersionName` marked `-untagged` (`0.1.0-untagged`) with no tag, as a source archive
  or a fork without the tags builds: never the name of a release.
- `versionCode = baseVersionCode × 10 + ABI id`, and `baseVersionCode` follows from
  `Versions.baseVersionName` (`versionCodeOf`: 12 at 0.1.0, 1 more a patch, 100 a minor, 10,000 a
  major; minor and patch below 100). Raise `baseVersionName` for each release.
- A release: bump versions → commit → `git tag -a vX.Y.Z` → build both release APKs for arm64-v8a
  signed with the release key (kept outside the repo; `signKeyFile`/`signKeyAlias`/`signKeyPwd` in
  `~/.gradle/gradle.properties` or `SIGN_KEY_*` env; unsigned if absent) → install over the last
  release and type on it → GitHub release with both APKs and `SHA256SUMS.txt`, notes in Chinese
  and English.
- Test builds between releases go up as **draft** releases (the repository is public) and are
  deleted once a release supersedes them. Data releases are public and stay.
- Package `io.github.sraw.youmo` (debug: `.debug` suffix), installable beside upstream Fcitx5.

## CI (`.github/workflows`)

| Workflow | When | Runs |
|---|---|---|
| `unit_test.yml` | push, PR | `:lib:ime-core:check`, dict-tool and eval tests, build-logic tests, `:app:testTextDebugUnitTest`, voice test compile, native host tests |
| `pull_request.yml` | PR | `assembleTextRelease` and `assembleVoiceRelease` on Ubuntu (unsigned) |
| `engine_downloads.yml` | push to main changing `EngineDataPlugin.kt`, on demand | the engine data's download tasks alone, saving the `engine-downloads-*` cache a new pull request's build reads |
| `emulator_test.yml` | weekly, and PRs labelled `emulator` | instrumented tests (`FcitxTest`, `SoftKeyboardTest` …) for Text and Voice on an API 35 emulator |
