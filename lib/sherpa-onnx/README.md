# sherpa-onnx, vendored

The speech recognizer of the voice builds: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(k2-fsa, Apache-2.0, `src/main/cpp/LICENSE`), built from source by this module instead of taken as
upstream's AAR, so that it is ours to change.

- **Upstream:** v1.13.8 (11afbd0). Of it, `src/main/cpp` holds the top `CMakeLists.txt`,
  `cmake/`, `LICENSE`, and of `sherpa-onnx/` the `CMakeLists.txt`, `csrc/`, `jni/` and
  `kotlin-api/`, as they were: the commit "Vendor sherpa-onnx v1.13.8, unmodified".
- **Ours:** every later commit touching `src/main/cpp`. `git log -p -- lib/sherpa-onnx/src/main/cpp`
  after that commit is the whole difference from upstream.
- **Built:** by the module's CMake build (`build.gradle.kts`), recognizer, VAD and JNI only, no
  speech synthesis. CMake downloads onnxruntime's Android libraries and the small dependencies
  (kaldi-native-fbank, kaldi-decoder, simple-sentencepiece, eigen, json), each SHA-256 checked by
  its `cmake/*.cmake`. kaldi-decoder brings kaldifst and openfst with it, kaldi-native-fbank kissfft.
  Each of them is credited on the app's licences page (`VoiceFeature.credits` in `app/src/voice`):
  a dependency added or updated here is changed there too. Only the app's `voice` flavor depends
  on the module.

## What we changed

- **Blocked phrases:** `OfflineRecognizer::CreateStream(hotwords, blocked)` (Kotlin
  `createStream(hotwords, blocked)`): a transducer's modified beam search does not let a hypothesis
  complete any of them (`ContextGraph::CompletingTokens`), so the next best is written instead.
- **N-best:** `OfflineRecognizerResult.nbest` and `nbestScores`, the beam's hypotheses best first
  (in `AsJsonString` too). The app ranks them again with its pinyin model (`VoiceRerank`).
- **Hotwords graph:** `CreateStream(hotwords)` keeps the graph of the last hotwords it was given and
  returns it again for the same ones, rather than building the pack's hotwords into a new graph for
  each stream.
- **Models mapped in place:** a transducer model in onnxruntime's ORT format (`.ort`) loaded from
  the Android assets is not copied: its asset stays open, mapped from the APK (stored uncompressed),
  and onnxruntime reads its weights where they lie (`session.use_ort_model_bytes_directly`,
  `use_ort_model_bytes_for_initializers`). Prepacking copies the weights, so it is off
  (`session.disable_prepacking`; with it on, onnxruntime crashes on bytes used in place). On redroid,
  `VoiceEngineTest`'s native heap fell from 550 to 325 MB (median), 749 to 520 MB at the peak, and the
  tests ran in 15 s rather than 20. The ORT files are the release `voice-models-*`
  (`VoiceDataPlugin`); converted for x86 they give the evaluation sets' error rates exactly, and on
  arm64 (onnxruntime 1.28 under qemu, as Neoverse N1 and N2) the ARM files' outputs equal the ONNX
  models', prepacked or not.

Each change is marked `youmo` in the source, except the deletions under "What we removed". Their C++ unit tests run on this machine with
`lib/sherpa-onnx/host-test.sh`; the app's `VoiceEngineTest` covers them through the JNI.
`lib/sherpa-onnx/host-eval.sh` builds `sherpa-onnx-offline` for this machine, to evaluate the
recognizer on the app's code without a phone, on the app's onnxruntime version
(`cmake/onnxruntime-linux-x86_64.cmake`, kept with the Android files).

## What we removed

What our builds never use is deleted, so that it is neither read nor compiled: every file listed in
`upstream-removed.txt` (relative to `src/main/cpp`), and the `CMakeLists.txt` entries naming them.
Layer A is what no build of ours compiled at all (speech synthesis, speaker diarization, the NPU
backends, the tests and programs other than `sherpa-onnx-offline`, the Kotlin API of the rest).
Layer B is what was compiled but never ran: every recognizer but the offline transducer (with its
greedy and modified beam search, hotwords' context graph and LM rescoring), ten-vad, and the JNI
of the rest. So `OfflineRecognizerImpl::Create` makes a transducer recognizer or stops,
`OfflineModelConfig` (C++ and Kotlin) holds only the transducer's fields, and `VadModelConfig` only
silero's; the JNI reads no more than those.
Layer C is the build options that would now configure a broken build: speech synthesis, speaker
diarization, the NPUs, WASM, Python, the C API, websocket, PortAudio, ALSA and the tests are gone
from the `CMakeLists.txt` files, with the leftover NPU headers and the `cmake/` files that fetched
their dependencies. What stays: `BINARY` (for `sherpa-onnx-offline`), `JNI`, `CHECK`, the
onnxruntime choices (`GPU`, `DIRECTML`, `SPACEMIT`, static or shared) and the sanitizer. Branches on the
removed options left in dependency `.cmake` files and `csrc/macros.h` are kept on purpose: they are dead,
and leaving them keeps the next upstream diff small.

## Updating upstream

Copy the same paths of the new release over `src/main/cpp` in a commit of its own, delete what
`upstream-removed.txt` lists in the next (new upstream files that we don't use get added to the
list there), then bring our commits forward onto it (`git log` above lists them; the
`CMakeLists.txt` changes are among them).

`csrc/CMakeLists.txt` lists its sources itself (`set(sources …)`), so upstream's edits to its list never
reach us. To sort the new release's files:

1. The roots are the `.cc` files in `jni/CMakeLists.txt`, `csrc/sherpa-onnx-offline.cc` and the
   sources `host-test.sh` compiles. What they `#include`, followed transitively, is the kept header set;
   a kept header's `.cc` is a kept source.
2. A `.cc` with no header of its own (`log.cc` was one) is not found that way: check each new one by hand
   with `grep` for the functions it defines.
3. Every `.cc` under `sherpa-onnx/` then goes either into `csrc/CMakeLists.txt` `set(sources …)` or into
   `upstream-removed.txt`. This lists the ones in neither:

   ```
   cd src/main/cpp && for f in $(find sherpa-onnx -name '*.cc'); do
     grep -q "$(basename $f)" sherpa-onnx/*/CMakeLists.txt ../../../host-test.sh ||
       grep -qx "$f" ../../../upstream-removed.txt || echo "$f"; done
   ```
4. Build `host-test.sh`, `host-eval.sh` and an APK: the Android link uses `--no-undefined`, so a kept file
   calling into a deleted one fails there rather than on the phone.
