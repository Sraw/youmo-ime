# specs/: how this project is built, for whoever changes it

These pages are for an agent (or a person) about to change the code. They say where things are,
how they fit together, the rules the code follows and why, and the traps that have bitten before.
`CLAUDE.md` at the root is the one-page summary; this is the long form. What the app does for a
user, and every setting, is in [`docs/`](../docs/README.md).

| Page | Read it before |
|---|---|
| [architecture.md](architecture.md) | any change: the modules, the processes and threads, a key's way from the screen to the editor |
| [engine.md](engine.md) | touching `lib/ime-core/.../engine`: pinyin, tables, English, learning, ranking, the data files |
| [app.md](app.md) | touching `app`: the service, the keyboard views, settings, persistence, the two flavors |
| [data-and-build.md](data-and-build.md) | touching `build-logic`, a data release, `lexicon/`, native code, versions, CI |
| [testing.md](testing.md) | finishing any change: which tests, the evaluation, trying it on a device |
| [conventions.md](conventions.md) | writing code, comments, commits; adding a setting; product rules that are decided |
| [host.md](host.md) | touching `lib/ime-core/.../host` or `KotlinFcitx`, the fcitx host in Kotlin: the key pipeline and its extension points, which input method a field is on |
| [pitfalls.md](pitfalls.md) | debugging something odd; before building an APK to ship or test |

## The project in five lines

- 幽默输入法 (Youmo IME), `io.github.sraw.youmo`: an offline Chinese input method for Android,
  branched from fcitx5-android and never merged back. Upstream compatibility is not a constraint.
- The input method framework is fcitx5's, ported to Kotlin (`lib/ime-core`'s `host/`, settings still in
  fcitx's INI files); the input methods themselves (pinyin, 九键, 双拼, five code tables, handwriting,
  English) are this project's own engine in Kotlin, also in `lib/ime-core`. No native fcitx is left.
- Ranking: an n-gram decoder over a word lattice, two small sentence models (4M, 25M) reranking its
  best sentences, and what the user typed. All on the phone; the app has no network permission.
- Two builds (the `speech` flavor dimension): `text` and `voice` (offline voice input, X-ASR on
  sherpa-onnx). Released for arm64-v8a.
- Data (language model, new words, English, sentence models) is built outside the app and pinned
  by SHA-256 to this repository's GitHub releases; the build fetches and compiles it.

## Keeping these pages true

They describe the code as it is. A change that makes a sentence here wrong changes the sentence in
the same commit, as it would a comment. Prefer naming a class (`PinyinDecoder`) over a line number,
and a rule's reason over the rule alone.
