# The engine (`lib/ime-core/.../engine`)

The input methods are this project's own, in Kotlin, replacing libime and fcitx5-chinese-addons'
engines. Read the class KDoc before changing a class: most say why they are as they are, with the
measurement that decided it.

## Packages

| Package | Holds |
|---|---|
| `session` | `Session` (the host's view: `apply(Action): Snapshot`, `offers`, `candidates`), `PinyinSession` (全拼, 双拼, 九键), `NineKeys` |
| `pinyin` | Turning keys into syllables: `Syllables` (every syllable, append-only ids), `SpellingIndex`, `PinyinSegmenter` / `ShuangpinSegmenter` / `T9Segmenter` → `SyllableGraph`; `Fuzzy`, `Typo`, `KeyNeighbours`, `ShuangpinScheme`, `NineKeyLetters`, `LatinWords` |
| `lattice` | Turning syllables into text: `PinyinDecoder` (word lattice + beam search), `Penalties`, `WordScorer`, `UserWords`, `LayerPrior`, `Predictor` (联想), `TextWords` (the model's words in text the engine did not commit), `CoarseWords` |
| `rerank` | The sentence models: `SentenceModel` (char-level transformer, int8 safetensors), `Reranker` (`SentencePicker` / `SentenceRefiner`), `MatrixKernel` (the hot loop; the app runs it in C++, `NativeMatrixKernel`), `Safetensors`, `Json` |
| `user` | Learning: `UserModel` (counts of words and pairs, words made from pieces), `UserScorer` (mixes them into the model), `UserLog`/`UserStore` (the log on disk), `WordLists` (added and blocked by hand), `WordPack`, `LibimeImport` |
| `table` | Code tables (五笔, 仓颉, 自然码, 二笔, 五笔拼音): `TableDictionary`, `TableSession`, `TableOptions`, `PhraseRules` (组词规则), `TableUser`, `SharedWords` |
| `english` | `EnglishData`, `EnglishDecoder` (keys → words with slips), `EnglishSession` (Gboard-like word-at-a-time typing, autocorrect), `EnglishUser`, `EnglishText`, `Misspellings` |
| `stroke` | `Strokes`, `StrokeLookup` (`u` then `h s p n z` inside pinyin) |
| `phrase` | `CustomPhrases` (libime's `customphrase` format, with date/time variables), `PhraseBook` |
| `data` | File formats: `DataFile` (the container), `PinyinData`, `CodeTable`, `NgramTables`, `BitPacked`, `EliasFano` (the trigram runs' starts; a lookup costs more, so not for the unigrams'), `Quantizer`, `StringTable`, `WordIndex`, `PinyinDataBuilder`, readers of text sources, `Misreadings`, `DataAge` |
| `store` | `RecordFormat` / `RecordStore`: append-only logs with CRC'd records, compacted by rename |
| `libime` | Reading libime's binary files without libime (its `DATrie`, zstd): for users coming from upstream and for importing `.dict` |
| `host` | `Engines` (the input methods by name, sessions made on first use), `EngineAssets`, `EngineSettings` (+ `ModelLevel`), `TableRegistry`, `UserLexicon`, `EnglishLexicon` (the English words the user committed, kept in `english.user`), `Keyboard` (the addon's event numbers), `LibimeMigration`, `AddonDefaults` |

## Pinyin, from keys to candidates

1. **Segment.** A `Segmenter` turns the input into a `SyllableGraph`: nodes are positions, each edge
   carries every syllable that stretch may stand for, with `SyllableMatches` flags saying how it
   strays (fuzzy, typo, slipped onto a neighbouring key, initial only = 简拼, partial = still being
   typed, a Latin letter, raw). Unlike libime, every cut is kept and the language model chooses.
   `PinyinSegmenter` (全拼), `ShuangpinSegmenter` (双拼, same graph), `T9Segmenter` (digits; a key
   pressed again soon is read both as keys and as its next letter, `MultiTap`).
2. **Decode.** `PinyinDecoder` puts every dictionary word along every path into a lattice and beam
   searches it, scoring each step by `WordScorer` (the n-gram model, through `UserScorer` with the
   user's counts added) plus the `Penalties` of how the input was read. It keeps whole sequences
   (a real N-best, not trigram-merged), and reuses the beams of the unchanged prefix so a key costs
   only its last syllables.
3. **Rank.** At `ModelLevel.MEDIUM` and up, `Reranker` adds the 4M sentence model's score of the
   decoder's best whole-input readings (after the text before the cursor) to the decoder's score;
   at `HIGH`, while the user pauses, the 25M model refines the top readings in time slices
   (`SentenceRefiner`, `Action.Refine`). Adding to, not replacing, the decoder's score was the
   measured choice (replacing lost 2.2 points).
4. **Present.** `PinyinSession` lists the readings of all the input, then words the start may be;
   picking a partial one keeps it and reads the rest after it. Committed text becomes the context
   of what follows; `Predictor` offers next words (联想) until a key is typed.
5. **Learn.** Committed words are counted (`UserModel`); a sentence the user assembled from pieces,
   or a reading other than the first, is learned as one new word. Counts halve past a limit.

### Rules in the decoder worth knowing

- **User words first.** A word of two or more characters the user committed at least twice is
  spared the 简拼 / partial / extended penalties along its path (`UserWords.count`); forgetting or
  blocking undoes it, halving fades it. This is the one general rule for "my word should win";
  do not add per-case boosts or global penalties for it (both were tried and lost on the sets).
- **Layers.** The dictionary has layers (`WordLayers`: the base from libime, `new` from
  `lexicon/add.tsv` via the word pack, `latin`, imported dictionaries as base or new). `LayerPrior`
  adds one learned log10 value per layer, moved by the user's picks within a bound.
- **Coarse words** (`CoarseWords`, list `coarse.txt` beside it): never predicted, `PENALTY` lower in
  the decoder, a candidate with one goes past the first page, English never corrects to one. Once
  the user typed one it is theirs and none of this applies.
- **Misreadings** (`lexicon/misreadings.tsv`): a word is also under its common misreading, a tenth
  as likely, and its candidate then shows the right reading with tones.
- **Strokes.** `u` with nothing typed starts `StrokeLookup` (`h s p n z` = 横竖撇捺折); any other
  letter turns it back into pinyin (`usb`, or `uan` as a slip for `yan`).
- **Latin words** in pinyin (`iphone` → iPhone) come from the `latin` layer, spelt in letter
  syllables; full pinyin only.

### Long press on a candidate

`Session.offers(index)` says what a long press may do, the addon shows those in a menu:
`FORGET` (忘记词汇: what was learned of it), `PIN` (以自定义词组的方式置顶: a custom phrase of
the input, first), `UNPIN` (从自定义词组中删除), `BLOCK` (不再显示这个词: into the block list, which the
settings can undo). Each has conditions (`PinyinSession.forgettable/pinnable/blockable`), and none
are offered where nothing is learned (a password field, `Session.learning` false).

## Tables

`TableSession` ranks entries whose code starts with the input as libime does (codes up to
`noSortInputLength` in table order, then shorter codes first, then by use). Auto-select commits a
lone full code, or the first candidate when the next key leads nowhere; 空码 is dropped by the next
key. `pinyinKey` (五笔 `z`, 二笔 `[`) looks a character up by pinyin, showing its code;
`matchingKey` is the wildcard (五笔 `z`, 仓颉 and 二笔 `*`). Characters typed one by one become
phrases by the table's 组词规则 (`autoPhraseLength`), saved after `saveAutoPhraseAfter` uses.
`SharedWords` gives every table the user's words from the other input methods, coded by its rules.
Options per table are in `androidengine.h` (`engineTable(...)`) and `TableOptions`.

## English

`EnglishSession`: word-at-a-time, as Gboard. The candidates are the word typed and what it may be
(`EnglishDecoder`: spelt, started, or spelt with a slip); a word that is none is corrected and
space commits the correction; backspace right after takes it back and that word is never corrected
again. Space after a word adds a space and predicts; punctuation moves that space behind it;
double space is a full stop. `EnglishUser` learns committed words; a word counted twice is the
user's, never corrected. `Misspellings` corrects common misspellings the web taught the model as
words. Auto-capitalisation is the keyboard's (`AutoCaps`), not the engine's.

## Data files and compatibility

Every compiled file is a `DataFile`: `"FCITXIME"`, container version, kind, kind version, then
numbered sections with CRC32s, little-endian and 8-byte aligned so it can be read memory-mapped in
place. Rules that keep old files and old users working:

- **`Syllables` is append-only.** Compiled dictionaries store syllable ids; reordering or removing
  one re-spells every word of every existing file. Files record the count and a checksum of the
  syllables they were built with; `matches` accepts a file built before later ones were appended.
- **Bump the kind's `VERSION`** (`PinyinData.VERSION`, `EnglishData.VERSION`, …) when its layout
  changes, and make the reader refuse what it cannot read (`ensureFormat`, `DataFormatException`)
  rather than misread it. A data file the app cannot load shows `engine_unloadable` to the user.
- **The user's logs outlive the data.** `UserLog` writes words as text and spelt syllables, never
  ids; a record whose syllables this build does not know is skipped. `RecordFormat` records are
  length-prefixed and CRC'd: a record cut short by a kill ends the replay, what came before stands.
  Add a new record type rather than changing an old one; an old build must skip what it does not
  know.
- **Untrusted input.** Imported dictionaries, safetensors headers, libime files and zstd frames come
  from outside: every size is checked before allocating, nesting is bounded (`Json.MAX_DEPTH`),
  and a failure says what did not fit. Keep it so.

## Performance

A key must decode in a few milliseconds on a phone. Nearly all the time is the scorer and, at
`MEDIUM`+, the 4M model; `HIGH`'s 25M model runs only in pause slices. Measure with `ime-eval`
(it reports latency per key, p50/p95/p99) before and after anything on the decode path, and keep allocations off
it: `Counts`, `IndexCounts` and `WordIndex` exist because boxing in a `HashMap` showed up.
