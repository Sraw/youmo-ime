# The engine (`lib/ime-core/.../engine`)

The input methods are this project's own, in Kotlin, replacing libime and fcitx5-chinese-addons'
engines. Read the class KDoc before changing a class: most say why they are as they are, with the
measurement that decided it.

## What every Chinese input method has

Each Chinese input method the app ships (全拼, 双拼, 九键, the code tables, handwriting, and any added
later) has the same five, decided 2026-10-14; a new one is not done without them:

1. **The word model** (the pinyin data's n-gram) in ranking, after the text before the cursor.
2. **The sentence models** as the 性能档位 has them: none (low), the small one at each key (medium),
   the large one at a pause too (high). One setting, shared, shown on each input method's page.
3. **Prediction** (联想) after a commit: one setting, shared; a prediction picked is learned.
4. **Learning** into the one user model (`UserLexicon.user()`), what the user picks; not while
   `learning` is off (a password).
5. **The user's lexicon**: their words, added and blocked (`WordLists`), found and ranked as the
   dictionary's are; a blocked word never offered.

All have them: the pinyins, handwriting (2026-10-14) and the code tables (2026-10-14, `TableLanguage`;
see Tables for where a table's order may move).
Handwriting and the tables share their prediction and the text before the cursor
(`session/Predictions`) and the large model's slices at a pause (`session/RefineSlices`); pinyin
keeps its own, the model they follow.

## Packages

| Package | Holds |
|---|---|
| `session` | `Session` (the host's view: `apply(Action): Snapshot`, `offers`, `candidates`), `PinyinSession` (全拼, 双拼, 九键), `NineKeys` |
| `pinyin` | Turning keys into syllables: `Syllables` (every syllable, append-only ids), `SpellingIndex`, `PinyinSegmenter` / `ShuangpinSegmenter` / `T9Segmenter` → `SyllableGraph`; `Fuzzy`, `Typo`, `KeyNeighbours`, `ShuangpinScheme`, `NineKeyLetters`, `LatinWords` |
| `lattice` | Turning syllables into text: `PinyinDecoder` (word lattice + beam search), `Penalties`, `WordScorer`, `UserWords`, `LayerPrior`, `Predictor` (联想), `TextWords` (the model's words in text the engine did not commit), `CoarseWords` |
| `rerank` | The sentence models: `SentenceModel` (char-level transformer, int8 safetensors), `Reranker` (`SentencePicker` / `SentenceRefiner`), `MatrixKernel` (the hot loop; the app runs it in C++, `NativeMatrixKernel`), `Safetensors`, `Json` |
| `user` | Learning: `UserModel` (counts of words and pairs, words made from pieces), `UserScorer` (mixes them into the model), `UserLog`/`UserStore` (the log on disk), `WordLists` (added and blocked by hand), `WordPack`, `LibimeImport` |
| `table` | Code tables (五笔, 仓颉, 自然码, 二笔, 五笔拼音): `TableDictionary`, `TableSession`, `TableOptions`, `PhraseRules` (组词规则), `TableUser`, `SharedWords` |
| `english` | `EnglishData`, `EnglishDecoder` (keys → words with slips), `GlideDecoder` (a glide's path → words), `EnglishSession` (Gboard-like word-at-a-time typing, autocorrect, glides), `EnglishUser`, `EnglishText`, `Misspellings` |
| `stroke` | `Strokes`, `StrokeLookup` (`u` then `h s p n z` inside pinyin) |
| `handwriting` | `HandwritingSession` (the input method), `HandwritingDecoder` (the characters written read as a sentence with the pinyin model and the user's words), `InkRecognizer` (the character's two recognizers run as their training code runs them, over `InkNet`s the app supplies), `InkFeatures` (what each recognizer reads of the strokes, equal to the training code's) |
| `phrase` | `CustomPhrases` (libime's `customphrase` format, with date/time variables), `PhraseBook`; `EmojiData` (`engine/emoji.data`: the emoji panel's groups and the words emoji stand for) and `EmojiCandidates` (a word's emoji after the first two candidates, pinyin's and a table's alike: setting `Emoji`, those the phone cannot draw left out by `Engines.emojiShows`); `Chaizi` (`engine/chaizi.data`: characters by the pinyin of their parts, full pinyin's after `u`, setting `Chaizi`, those the phone's font lacks left out by `Engines.textShows`) and `AsideCandidates` (where pinyin puts both: the emoji after the first two, the characters first) |
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
   user's counts added; a word of the user's whose text the model has, added by hand or typed at a
   reading of the user's, is that model word to it (one only imported, from a dictionary or a pack,
   is the model's unknown word, plus its pack score, till picked), scored and as context, in every input
   method: `UserModel.modelWord`; one test, `UserModel.chosen`, says whether a word is the user's, for
   its score, its layer's prior, the context of 联想 and the words 联想 offers after it, and the
   reading handwriting takes it at) plus the `Penalties` of how the input was read. It keeps whole sequences
   (a real N-best, not trigram-merged), and reuses the beams of the unchanged prefix so a key costs
   only its last syllables.
3. **Rank.** At `ModelLevel.MEDIUM` and up, `Reranker` adds the 4M sentence model's score of the
   decoder's best whole-input readings (after the text before the cursor) to the decoder's score;
   at `HIGH`, while the user pauses, the 25M model refines the top readings in time slices
   (`SentenceRefiner`, `Action.Refine`). Adding to, not replacing, the decoder's score was the
   measured choice (replacing lost 2.2 points).
4. **Present.** `PinyinSession` lists the readings of all the input, then words the start may be;
   picking a partial one keeps it and reads the rest after it. Committed text becomes the context
   of what follows; `Predictor` offers next words (联想) until a key is typed. The input keeps a
   cursor (`Action.Cursor` from a tap on the preedit, `Action.Step` from an arrow): keys go in
   and backspace deletes there, and the input is read again whole, as if typed so (the decoder
   searches again from the first syllable that changed). A piece picked whose keys that changes is
   dropped; `PreeditPlaces` says where each place of the preedit is in the input, syllables
   spaced or spelt out (双拼). On the nine keys a syllable taken puts the cursor past it if it was in
   or after its digits, and backspace gives the last one back at the end alone.
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
  letter turns it back into pinyin (`usb`, or `uan` as a slip for `yan`), typed at the strokes'
  cursor, which they keep as pinyin keeps its own.
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
key. The code keeps a cursor as pinyin's input does; a key typed before its end is put in there and
never commits by the next-key rule (the code is being mended, not full), a lone full code still
commits. `pinyinKey` (五笔 `z`, 二笔 `[`) looks a character up by pinyin, showing its code (the
cursor then the pinyin's);
`matchingKey` is the wildcard (五笔 `z`, 仓颉 and 二笔 `*`). Characters typed one by one become
phrases by the table's 组词规则 (`autoPhraseLength`), saved after `saveAutoPhraseAfter` uses.
`SharedWords` gives every table the user's words from the other input methods, coded by its rules.
Options per table are in `androidengine.h` (`engineTable(...)`) and `TableOptions`; a table's page
shows pinyin's page size, prediction and 性能档位 too (`EngineTablePage`), the same settings.

Every table's words follow one rule, set at build time (`table --words --pinyin`, decided on 五笔
86 and kept for all, `lexicon/README.md` "Words in the code tables"): the table keeps every entry
it ships, only reordered, under each full code the commonest first; words added from pinyin.data go
when under a tenth as common as their code's commonest; new words (the pack's) never go and count
ten times; characters never go; 简码 (codes shorter than the longest) stay as they are.

At typing time a table has the five every Chinese input method has (`TableLanguage`, made in
`Engines.tableLanguage`). Where it orders by use (常用的字词排前面, on for all but 仓颉), a code's
重码 — the candidates whose code is all that was typed, longer than `noSortInputLength` (五笔's 一二级
简码 stay put; 自然码 and 二笔 keep none) — are placed: the user's words once picked here first (one of the other input methods', or a phrase saved as typed
character by character: decided so, not to move a blind typist's first for a word never picked here; till
then it goes where its picks put it, after a pick of the table's), then the table's
entries by the word model after the text before (`TextWords.logProb` over the user's scorer), each
of the table's picks adding (log10(1 + picks): the user model's counts alone moved a pick forward too
slowly for a table that orders by use); a blocked entry is left out of the weighing. The small sentence model weighs them again at each key (medium), the large one at a
pause (high). Off, the table's order stands for those who type by position. Whatever the order,
what is put in is learned by the one user model (each word by its reading, after the one before),
forgetting a candidate forgets it everywhere, and 联想 follows a commit until the next key. A long press on a
联想 offers Predictions' forget and block (block only where the table could block the text as a candidate, `SharedWords.blockable`: never a single character, since `UserLexicon` blocks a text in every reading and a character blocked would go from pinyin and strokes too), as handwriting's does: forgetting one forgets that entry's
reading and the tables' copy of it, blocking leaves it out of 联想 from then on.
`ime-eval table <t.data> <set> --preset <p> --pinyin <pinyin.data> [--rerank --refine]` measures
the 重码 typed first after the text before, codes too short to be sorted left out. The preset must
order by use (wubi, ziranma, erbi, wubipinyin): a table in its own order is not weighed by the text
before, so 仓颉 and plain, which 常用的字词排前面 can turn on in the app, are not measured.
On pinyin.tsv (2026-10-16), the table's order → the word model (low): 五笔 76.4% → 93.3%, 二笔
63.0% → 91.9%, 自然码 75.1% → 93.1%, 五笔拼音 79.9% → 95.0%.

## English

`EnglishSession`: word-at-a-time, as Gboard. The candidates are the word typed and what it may be
(`EnglishDecoder`: spelt, started, or spelt with a slip); a word that is none is corrected and
space commits the correction; backspace right after takes it back and that word is never corrected
again. Space after a word adds a space and predicts; punctuation moves that space behind it;
double space is a full stop. `EnglishUser` learns committed words; a word counted twice is the
user's, never corrected. `Misspellings` corrects common misspellings the web taught the model as
words. Auto-capitalisation is the keyboard's (`AutoCaps`), not the engine's.

Glide typing: the keyboard (`GlideInput`, `GlideGesture` in ime-core deciding when a finger glides
rather than taps, swipes or holds) hands the path, in key widths across and rows down over where
its letter keys are (`engine/glide/GlideLayout`, a letter on two keys on the split keyboard), to
`Engines.offerGlide`, then sends `Keyboard.GLIDE`, as handwriting sends `INK`; `Action.Glide`
commits the best word with the session's own space and offers the others: a pick replaces it, a
backspace deletes it. `GlideDecoder` walks the words' key trie with `engine/glide/GlidePath`: a
letter goes on only where the path passes near its key in order (first and last near its ends), and
a word is scored by the model less SHARK²'s location channel (the mean distance between the path
and the word's keys joined, each resampled) and how near its keys come. `ime-eval english-glide`
measures it on simulated paths (key centres off by a Gaussian of 0.3 key widths, a touch point
every 0.05): 94.6% first, 97.6% in the first three, 0.7 ms a word on one desktop core. Besides
the shape and the keys, `GlidePath` costs where the path starts and lifts away from a word's ends,
and each turn of the path (by how far it turns) that none of the word's keys is at: a finger turns
on the keys it means.

Full pinyin glides too (`engine/glide/PinyinGlide`): the dictionary's syllable trie is walked as
English's key trie, each reading with a word scored by its likeliest word (the unigram, or its
characters one after another where likelier, as the decoder's best path takes 你好) less the path's
cost; one word a glide (chained, the readings ran to thousands). Each pinyin glides its own keys
(`PinyinGlide.Spelling`): full pinyin the letters, 双拼 its scheme's two a syllable, the nine keys
the letters too, on their digits' keys (`GlideLayout.sameKey`: letters on one key follow without
the path moving). A table glides its codes (`engine/glide/TableGlide`): its entries walked in code
order as a trie, a code scored by its likeliest entry under the word model. `GlideSession` wraps
each of their sessions (`Engines.glides`: all but handwriting, English its own): `Action.Glide`
types the best reading's keys (the nine keys' as `Action.Letter`, so 426 is not read as gan), after
`between` where something is typed already (pinyin's `'`, so glides chain into a sentence; a
table's first candidate picked), and a glide that starts the input puts the next two readings'
words after the first candidate, a pick of one typing its reading and picking it there, so it is
learned and followed as any word. `ime-eval pinyin-glide` measures it per dictionary word of a
pinyin set (`--shuangpin`, `--keys nine`, `--table`): on pinyin-chat full pinyin 85.3% first,
96.5% in the first three, 0.4 ms; 双拼 (自然码, 小鹤, 微软) 82–83% / 94%; the nine keys 53.5% / 76.7% (a key three or four letters, the path reads far more; the weights moved it under a point); 五笔 70.6% / 90.0%,
仓颉 94.5% / 99.3% (the characters it has). The setting is off by default: a sloppy fast tap may glide.

## Handwriting

One character at a time: two recognizers, trained outside this repository on synthetic ink only
(fonts and Make Me a Hanzi's strokes; CASIA for evaluation alone) and shipped in the release
`handwriting-models-*` (`HandwritingDataPlugin`, its `manifest.txt`), read the character by its
image (9 × 64 × 64 planes) and by its pen path, weighed 0.7 to 0.3 with 0.2 × its log frequency.
Their dense and recurrent weights are int8 (the convolutions float): 121 MB → 43 MB in the APK, the
same on CASIA to a hundredth of a point (`handwriting-models-20261015` against `-20261014b`). They
are mapped from the APK where they lie (`OrtModel`: the asset uncompressed, the model's bytes used
directly, prepacking off), so their weights are clean file pages, not the heap: on redroid after
writing, the app's private memory is the same with either, its mapped APK 173 → 96 MB.
`InkFeatures` is held to the training code by golden data (`ink-golden.json.gz`, the training
sources' `gen_golden.py`). Lines and characters written over each other are not read: their
recognizers (CTC, in the first release, `handwriting-models-20261014`) read real handwriting far
worse (CASIA, characters wrong: a line 44%, an overlay 32%, against 9% for characters one at a time
read as a sentence).

`HandwritingSession` reads the characters written as a sentence, as pinyin reads syllables: each
one written joins those before it, uncommitted, and `HandwritingDecoder` finds the likeliest words
through their readings, by the pinyin dictionary's syllable trie (each reading followed by its
characters' syllables, `Predictor.charReadingsOf`) and the user's new words, scored by the pinyin
session's scorer (the model, the user's counts, the layer prior) plus `INK_WEIGHT` × the
recognizer's log-probabilities; after the words before the cursor. The best sentence is the
preedit; the candidates are the last character's readings, each as the best sentence ending with
it has it, and a pick commits that sentence. Measured with `ime-eval handwriting` (rows from the
training sources' `lmdata.py`: CASIA's test lines, each character that writer's isolated sample;
the weight set on synthetic ink made as hard as real, `HARD=2.2`, where 1.0 was best, and 12
readings of a position for words): on 67,599 characters of the 51 CASIA writers never used to pick a
checkpoint (`HELD=1`), 74.7% first right by the ink alone, 91.4% once the sentence is written,
85.1% as each character is, the last in the first ten 95.7% (the first release: 72.5%, 89.2%,
82.5%, 94.3%). The words from each position are kept while its readings and the
next seven are the same (`HandwritingDecoder.arcs`), and the beams of the unchanged prefix with their
scores (`HandwritingDecoder.keep`), so a stroke costs the last characters' words, not the whole
sentence's; what is learned while a sentence is written counts from the next one. The 性能档位 is pinyin's: the decoder's best sentences (`Decoding.best`,
five, whatever they end with) weighed again by the small sentence model as each stroke is read
(medium) and by the large one once the character is finished (high: the pad tells the engine when
the pen was up long enough, `Engines.inkFinished`, then `Action.Refine` slices), after the text before
(128 chars, none where nothing is learned); on 11,197 held-out CASIA characters, final 90.5% → 90.8%
→ 91.3%, as written 83.9% → 84.5% → 85.3% (low, medium, high; `ime-eval handwriting --rerank/--refine`,
run within the app's budgets, at pinyin's `Reranker.WEIGHT`, 0.4; 0.8 gave half a point more as
written, not worth a weight of its own). Once a sentence is put in, what may
follow is offered as pinyin offers it (`Predictor`, the prediction setting), a word picked learned.
A sentence picked other than the first is learned, its words as the
decoder found them (`Sentence.words` of `Decoding.endings`/`top`: a word or a character alone at the
reading the user has it at, whichever reading the search finds first; else a
word at the dictionary reading of highest weight found, a character at its likeliest,
`HandwritingDecoder.alone`): the user chose it over the ink's best. A dictionary word has one entry in
the user model, so of its dictionary readings the user's is the last typed; a reading of the user's
the dictionary lacks wins over the dictionary's, and of it and a dictionary reading typed too, the more
typed. The user has a reading while it is
their choice, counted or added by hand (`UserModel.chosen`, `UserModel.reading`); one only imported
has none till picked (its reading the dictionary lacks weighs -inf), and a word forgotten falls back to the dictionary's
everywhere, here, in handwriting's and pinyin's 联想 and in the tables (`Predictor.reading`), so it is not learned
back at the reading forgotten. The first is not, however put in: the
readings are often wrong, and a misread word not noticed, learned, would come back.
The sentence is shown in the field and kept there as the keyboard goes (`composesText` in
`androidengine.cpp`), as English's word is; pinyin's and a table's codes show above the candidates.
The pad (`InkPad`, ime-core's
`input/handwriting`) decides when to read (each stroke ended), when a piece is done (the pen up
`InkPad.finishMillis`, the app's `handwritingPause` setting, `FINISH_MILLIS` its default: its ink
wiped, its reading kept), and that backspace takes back a
stroke. The session cannot be handed a reading through fcitx, which carries keys: the app offers it
(`Engines.offerInk`) and sends `Keyboard.INK`, which the keyboard turns into `Action.Ink`.

Pieces are numbered, later higher, and every reading carries its piece: what a reading does is
decided in the session, on the fcitx thread, in order with the keys. A reading of a piece written
reads it again (none takes it out); a later piece's adds a character, unless read as nothing (a
first dot); one of a piece already ended (committed, taken back, the cursor moved) or taken out
comes too late. The session's `ended` goes back to the pad, which wipes the piece it is writing once it is
among them. One rule instead of the pad guessing: a slow first read (the models opened), a key
pressed while a stroke is read, and a reading of nothing all come out right.

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
