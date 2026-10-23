# The engine (`lib/ime-core/.../engine`)

The input methods are this project's own, in Kotlin, replacing libime and fcitx5-chinese-addons'
engines. Read the class KDoc before changing a class: most say why they are as they are, with the
measurement that decided it.

## What every Chinese input method has

Each Chinese input method the app ships (全拼, 双拼, 九键, the code tables, handwriting, and any added
later) has the same five, decided 2026-10-14; a new one is not done without them:

1. **The word model** (the pinyin data's n-gram) in ranking, after the text before the cursor.
2. **The sentence models** as the 性能档位 has them: none (low), the small one at each key (medium),
   the large one at a pause too (high). One setting, shared, on the 通用输入 page (`InputMethodSettings.SHARED`); each input method's page has its own options and a link there.
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
| `session` | `Session` (the host's view: `apply(Action): Snapshot`, `offers`, `candidates`), `PinyinSession` (全拼, 双拼, 九键), `NineKeys`, `CoarseCandidates` (the candidates' coarse first pass) |
| `pinyin` | Turning keys into syllables: `Syllables` (every syllable, append-only ids), `SpellingIndex`, `PinyinSegmenter` / `ShuangpinSegmenter` / `T9Segmenter` → `SyllableGraph`; `Fuzzy`, `Typo`, `KeyNeighbours`, `ShuangpinScheme`, `NineKeyLetters`, `LatinWords` (the dictionary's Latin words) and `UserLatinWords` (English's learned words, its dictionary's aside, which `Engines.syncLatin` lists in the user model spelt by letters before a key of 全拼's: U-31 §3) |
| `lattice` | Turning syllables into text: `PinyinDecoder` (word lattice + beam search), `Penalties`, `WordScorer`, `UserWords`, `LayerPrior`, `Predictor` (联想), `TextWords` (the model's words in text the engine did not commit), `CoarseWords` |
| `rerank` | The sentence models: `SentenceModel` (char-level transformer, int8 safetensors), `Reranker` (`SentencePicker` / `SentenceRefiner`), `MatrixKernel` (the hot loop; the app runs it in C++, `NativeMatrixKernel`), `Safetensors`, `Json` |
| `user` | Learning: `UserModel` (counts of words and pairs, words made from pieces), `UserScorer` (mixes them into the model), `SyllableTrie` (the learned words by syllables), `Counts`, `UserLog` (an older build's log, read once to convert: `UserLexicon.legacyModel`), `HabitCounts` (the counts from the learning record), `WordLists` (added and blocked by hand; the user dictionary's export is a learning backup since 0.3.1 (`BackupOp.Copy`, WD-10), which its import restores; an earlier build's text export, which `UserLexicon.exportWords` still writes for the tests, has added words as `词 pin'yin`, blocked ones with a `!` before them, learned ones with a `~` apart before them and their count after them (`~ 词 pin'yin 3.0`: apart, an older version's parser reads the line as two words and skips it, rather than adding a word `~词`; picked into the base, `WordListFiles.baseLines` takes the word without the count, and only from an export: `baseLine` alone drops a `~` line); both imports read `~` only after `WordListFiles.EXPORT_MARK`, the file's first non-blank line (byte order mark and spaces aside), as `WordListFiles.exportMark` tells it for `baseLines`, the user dictionary's import and `isOwnExport` alike; `isOwnExport` and `exportMark(old = true)` also take an older export's header, and say whether the user dictionary's import adds a list's words back (another list's are imported words, below); the default target dictionary asks `isUserExport` (`isOwnExport`, or a `!` line); the user dictionary's import makes those words the user's once each in the learning record (`Mine.TAKEN_IN`), the exported count as uses on the day (`~ 词 pin'yin 3.0` comes back at 3, under one as one), skipping any that are added, blocked or already learned; an older export has no `~`, so its learned words come back as added), `WordPack`, `LibimeImport` |
| `table` | Code tables (五笔, 仓颉, 自然码, 二笔, 五笔拼音): `TableDictionary`, `TableSession`, `TableOptions`, `PhraseRules` (组词规则), `TableUser`, `SharedWords`, `TableLanguage` (the five a table shares with pinyin), `TableText` / `IniText` (reading a table's sources), `IndexCounts` |
| `glide` | Glide typing over Chinese keys: `Glide`, `GlideLayout` (the keys' places), `GlidePath`, `GlideSession`, `PinyinGlide` (a path → pinyin readings, full, 双拼, nine keys), `TableGlide` (→ a table's codes), `GlideReading` |
| `english` | `EnglishData`, `EnglishDecoder` (keys → words with slips), `GlideDecoder` (a glide's path → words), `EnglishSession` (Gboard-like word-at-a-time typing, autocorrect, glides), `EnglishUser` (the user's words, a view of `learn.log`), `Corrections` (a field's last corrections, for a tap back), `LegacyEnglishUser` + `EnglishUserStore` (the older builds' `english.user`, read once by `LegacyConversion`), `EnglishText`, `Misspellings`, `EnglishDataBuilder` (compiles `english.data`) |
| `stroke` | `Strokes`, `StrokeLookup` (`u` then `h s p n z` inside pinyin) |
| `handwriting` | `HandwritingSession` (the input method), `HandwritingDecoder` (the characters written read as a sentence with the pinyin model and the user's words), `InkRecognizer` (the character's two recognizers run as their training code runs them, over `InkNet`s the app supplies), `InkFeatures` (what each recognizer reads of the strokes, equal to the training code's) |
| `phrase` | `CustomPhrases` (libime's `customphrase` format, with date/time variables; `place`: each phrase a candidate of its own at its order, text, never learned; a decoder's candidate of the same text stays where it is ranked and learned, the phrase not listed again after it (U-66); `CustomPhrases.BUILT_IN`: the date, time and weekday under `rq`, `sj`, `xq`, as keys in full pinyin only, under a key the user file has none of (in 双拼 `sj` and `xq` spell syllables); setting `BuiltInPhrases`, on 通用输入, off: none of them, the user's own as before), `PhraseBook` (a delete from the keyboard under such a key first copies that key's built-in phrases into the user file, so a deleted one stays off; a pin goes to the learning record, not here; `builtInsOf`: the words 日期, 时间, 星期 (`WORD_KEYS`) bring the built-in ones still on in every Chinese input method, placed by `EmojiCandidates` before the word's emoji, never twice where the input is the key itself); `EmojiData` (`engine/emoji.data`: the emoji panel's groups and the words emoji stand for) and `EmojiCandidates` (a word's emoji after the first two candidates, pinyin's and a table's alike; a table's word alone on its code commits itself as ever, and its emoji and built-in phrases come first in the 联想 after it, `TableSession.pickOffered` replacing the word (`Snapshot.deleteBefore`, in code points) — the offer ends with any other key or pick: setting `Emoji`, those the phone cannot draw left out by `Engines.emojiShows`); `Chaizi` (`engine/chaizi.data`: characters by the pinyin of their parts, full pinyin's after `u`, setting `Chaizi`, those the phone's font lacks left out by `Engines.textShows`) and `AsideCandidates` (where pinyin puts both: the emoji after the first two, the characters first) |
| `data` | File formats: `DataFile` (the container), `PinyinData`, `CodeTable`, `NgramTables`, `BitPacked`, `EliasFano` (the trigram runs' starts; a lookup costs more, so not for the unigrams'), `Quantizer`, `StringTable`, `WordIndex`, `PinyinDataBuilder`, `CodeTableReader`, `NgramWords`, `SortedKeys`, readers of text sources, `Misreadings`, `DataAge` |
| `store` | `RecordFormat` / `RecordStore`: append-only logs with CRC'd records, compacted by rename; `ReplaceFile` (a file written whole, put in place by rename) |
| `libime` | Reading libime's binary files without libime (its `DATrie`, zstd): for users coming from upstream and for importing `.dict` |
| `host` | `Engines` (the input methods by name, sessions made on first use), `EngineAssets`, `EngineSettings` (+ `ModelLevel`), `TableRegistry`, `InBackground` (work handed to `warmUps`, waited on by who needs it), `UserLexicon`, `EnglishLexicon` (the English page's words, read from `learn.log`), `Keyboard` (the addon's event numbers), `LibimeMigration`, `AddonDefaults` |

## What voice input gets from the engine

- **Hotwords**: 我的词 alone, the user's added and learned words, not one committed only once
  (`VoiceHotwords.ofUser`: added at 5, learned at 1.5, blocked none, 3000 at most), bias the
  recognizer; none of theirs while U-93's 「用我的输入习惯」 is off (`habits`).
- **Blocking**: a blocked word is kept out of a result (`VoiceBlocking`), found by the model's word
  boundaries (`Engines.wordBoundaries`, `TextWords.boundaries` with `splitLong`).
- **Reranking**: the recognizer's hypotheses are rescored by the word model (`Engines.logProbs`,
  `VoiceRerank`).

## Pinyin, from keys to candidates

1. **Segment.** A `Segmenter` turns the input into a `SyllableGraph`: nodes are positions, each edge
   carries every syllable that stretch may stand for, with `SyllableMatches` flags saying how it
   strays (fuzzy, typo, slipped onto a neighbouring key, initial only = 简拼, partial = still being
   typed, a Latin letter, raw). Unlike libime, every cut is kept and the language model chooses.
   `PinyinSegmenter` (全拼), `ShuangpinSegmenter` (双拼, same graph), `T9Segmenter` (digits; with 连按
   on, off by default, a key pressed again soon is its next letter and nothing else, `MultiTap`: the
   session puts it in as a letter typed as itself, upper case, in place of the digit, so `4 4` fast
   is `H`. The 2026-10-12 hybrid, read as keys too with a `Penalties.tapped`, went 2026-10-19: a
   learned 广告 overrode the h the key's preview showed).
2. **Decode.** `PinyinDecoder` puts every dictionary word along every path into a lattice and beam
   searches it, scoring each step by `WordScorer` (the n-gram model, through `UserScorer` with the
   user's counts added; a word of the user's whose text the model has, added by hand or typed at a
   reading of the user's, is that model word to it (one only imported, from a dictionary or a pack,
   is the model's unknown word, plus its pack score, till picked), scored and as context, in every input
   method: `UserModel.modelWord`; one test, `UserModel.chosen` (added by hand, or owned: the user's word in the learning record), says whether a word is the user's, for
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
5. **Learn.** Each commit is a record in the learning record (`learn/LearnLog`, `learn.log`; the
   reducer `LearnState`): rows by code (the keys typed, per input method) and the word before
   (`LastWord`: the last word of the model's reading of the text, `TextWords.boundaries` as 联想 reads
   it, or a word of the user's ending it at one of those starts; the same for a commit and a lookup). The
   candidates are ranked by them (`HabitRanker`, through `session/HabitOrder`): the user's first
   under (code, word before) after two corrections, a word corrected to twice and used more than
   the decoder's first across the words before, then the words used there, the user's own words,
   the decoder's; a pin in front. Text of none's (2–4 Han characters) committed twice within 30
   typing days becomes the user's word (`Mine`, never expiring). The decoder's and 联想's counts
   are the words' uses in the last 30 typing days (`user/HabitCounts` fills `UserModel`: all anew
   on a new typing day, a forget or an undo; a commit's words and a word formed, with its pairs,
   alone). 联想 is ranked the same way, for every input method (`session/PredictionOrder`: the
   predictor's best 30 under the code `pred:<the word before>`, nothing before it), its picks filed
   by their text alone, so they move neither pinyin's order nor the decoder's counts; a word put in
   right after another 3 times (`Predictor.PAIRS`), any way, is offered after it (`UserModel.forEachPredicted`,
   counted apart from the decoder's pairs, which leave out words of no reading), one turned off
   there (不再联想) never. Where nothing is learned (`Session.learning` false) the habits still rank, after no
   word, and nothing is recorded. A pinned sentence the model has not as one word is put in as
   the fewest of its words. `Session.why` (为什么排在这, U-81) says a candidate's tier and uses
   under the code of the level that ranked it, a prediction's under its `pred:` code; a candidate
   no level ranked says nothing, and its menu has no WHY. impl/design-1b.md in
   `dev/design/learning-ux/` has the rules.

### Kept in sentences, taken back when deleted (U-08, U-03)

- **Kept in sentences** (`session/Enforced`, from `HabitOrder.enforce`): pins and the user's firsts
  of two or more characters (a first only after its word), and a word added by hand that is the only
  word over its keys (`PinyinDecoder.arcsBetween`), are put into the decoder's sentences where the
  sentence has word ends at both of theirs; two that overlap, neither. The sentences are taken of
  `moreSentences` (the beam's) so as many stay. This runs right after decoding, before the habit
  levels and the sentence models, so a pause cannot undo it. A pin or first over the whole input is
  not put in (it is first already, and would leave every sentence the same); a word added by hand
  is (U-65: first on its own keys). The decoder's own sentence is then gone: picked in pieces.
- **Taken back** (`session/CommitJournal`, held by `CommitRecorder`): a session's last 16 commits as
  written, and how much of each is left. A Backspace the app took takes a code point while the
  journal is verified; a commit gone whole goes to an UNDO (`LearnLog.undoRecent`, the tail only)
  at the next action that is no context, and the next commit typed again for it at the same place
  is a correction there (`Retype`). A host context checks the journal: it verifies it if it places
  the cursor exactly, and may show commits deleted whole (a cut, a selection's Backspace, read with
  `after`), which are taken back the same way; anything else drops it all. The rule is "in doubt,
  no UNDO": an UNDO cannot be repaired, a missed one costs little. So the host tells `started`
  (another field, by `FieldPolicy.focus`'s name), `after` (the text after the cursor) and `selected`
  (`Action.Context`); an empty `before` is not known; text read in full is all there is; an
  unverified journal reads no deletions; a field emptied (`cleared`) takes back only what Backspaces
  deleted one by one. `LearnLog.rewritten()` (a rewrite) changes `rewrites`, and every journal drops.
- **The same for handwriting and the tables** (F2655): `session/FieldJournal` is the journal with
  its plumbing (dropped on a rewrite or learning off, the UNDO, `simplify` on both the commits and
  the host's text), held by `CommitRecorder`, `HandwritingSession` and `TableSession` alike;
  `FieldJournal.before` is what every action tells it first. A 联想 pick is journalled with its
  record (`Predictions.picked`). Handwriting's retype is `Retype.matchKeyed`: each deleted commit's
  pick rows in turn, a character each, taken where it is among what that character's ink read as
  (kept by the commit's record, not its seq), under the deleted code with no word before; a run
  ending inside a deleted commit goes on at the next commit. Tables need no matcher (形码看键): the
  same keys again are the same code, whose own pick row is the correction; other keys are new
  input. A table's pinyin lookup is journalled with the lookup's record (`Session.lastCommit`); a
  glide's held-back word as text of its own (`TableSession.heldBack`), nothing to take back.

### Rules in the decoder worth knowing

- **Counts and short input.** The user's counts apply on paths of two or more words (`UserScorer`,
  MULTI) and never on an arc with a short edge (简拼, a partial or extended syllable, look-ahead):
  under 简拼 the first is the model's alone, what was typed in full never moves it (D32). The
  user's own words come next to it there by the ranking, not by the scores. Do not add per-case
  boosts or global penalties for "my word should win" (both were tried and lost on the sets).
- **Layers.** The dictionary has layers (`WordLayers`: the base from libime, `new` from
  `lexicon/add.tsv` via the word pack, `latin`). Imported dictionaries are merged into the base only
  (`ImportedDictionaries.migrate` moves those an older build listed in `.new-words` there and
  deletes the list); the settings show the built-in new words as part of 基础词库. `LayerPrior`
  adds one log10 value per layer; picks no longer move it.
- **Imported dictionaries: analysed, then compiled** (D5, D6). A file for either dictionary is read
  through by `DictionaryFormat.analyse` before a word is imported: charset (BOM, else strict UTF-8,
  else strict GB18030), Rime's `---`…`...` header, and from a sample of ~2,000 lines spread over the
  file which column is the text, how the pinyin is spelt and what the numbers are. Anything unsure is
  `Refused` with a reason (格式不支持), never imported half right: a second layout or kind of number
  over 2% (1% for numbers), initials only, more than 2% of all lines unread, the sample's characters
  mostly not having their pinyin as the base dictionary reads them (`MISMATCH`, GB18030-decoded files only: Big5 decoded as GBK,
  or no Mandarin readings), more words than `DictionaryCompiler.MAX_WORDS` (`TOO_MANY`). `analyse`'s
  `write` callback gets every word read with its pinyin, in the one form the sources keep (`text pin'yin weight`);
  the app writes them to the file `PinyinDictManager.Prepared.lines`, which the import reads. The app (`PinyinDictManager.prepare`/
  `importPrepared`) keeps the texts in `dictionaries/` gzip-compressed (`<name>.txt.gz`, off
  `<name>.txt.gz.disable`; rime-frost 84.5 MiB of text to 23.6) as sources only and compiles those on into
  `merged.ymd` (`MergedDictionary`, `DictionaryCompiler`) on one thread for the process; the engine
  maps that file and never parses the texts (0.3.1 did, at each focus, and ran out of heap on 1.9M
  words). `merged.ymd` is a cache of the sources: out of backups, free to change layout (bump
  `DictionaryFile.VERSION` and `MergedDictionary.REVISION`), made again from them. A stamp of the
  sources' names, sizes and times says when to compile again; a stale file is
  used until the new one is made, then the engine is reloaded. A plain `<name>.txt[.disable]` (0.3.1
  and 0.3.2 kept them so, a restored older backup brings them) is converted to `.gz` on the compile
  thread at the top of the next compile, shown as its first stage (`MergedDictionary.Step.CONVERT`);
  a `.txt` is always whole, so it is converted again over a same-name `.gz` (a kill between the
  rename and the delete, an older backup restored over this build's files). Every create, rename and
  delete of a source is under one lock (`ImportedDictionaries`'), gzip and deflate outside it into a
  `.tmp`; see [pitfalls](pitfalls.md) for its order. The user dictionary holds at most
  100,000 imported words in total (`USER_MAX_WORDS`): the file's words plus those of earlier
  imports still kept (`ImportLayer.Batch.left`), as its imported words are in the heap. A file
  refused for the base (`Refused`, imported line by line) counts its non-blank, non-comment lines.
- **Imported words** (U-101, U-102). Another input method's list read into the user dictionary
  (`UserLexicon.importWords`; the user's own export, `WordListFiles.exportMark` with `old`, is added
  as before) is a batch in the learning record (`ImportBatch`/`Imported`/`ImportUndo`, rows
  `BatchRow`/`ImportedRow`; `ImportedWords` in `LearnState.imports`, `plan` orders a batch by the
  file's count, `ImportFiles.frequency`, a .scel's by its own via `ImportFiles.Scel`; a list's word
  the dictionary has in any reading, or a line of no Chinese, is not taken). `ImportedWords` keeps
  them packed (text, syllables, batch and place in arrays; a place never reused, a word taken out
  only marked gone, so 撤销 puts the same places back from the record's own bytes), one instance
  shared by `LearnState` and `UserModel` (`attachImports`, from `HabitCounts.attach`, so a model made
  again has them). A rewrite of the record (an undo, a clear, a restore) makes them anew, their places
  numbered again: `Engines.reattach` then makes the model again, and the sessions (review-G M1). A
  restore (`BackupMerge`) puts every batch first, the phone's of an id before the backup's (renumbered
  unless the same file, day and count), then the phone's words, then the backup's: a word in both is
  the phone's batch's (review-G S10). U-86's 全部清空 drops the batches with the rest. Their ids are `UserModel`'s past `vocabulary.size + 2^28`, one per place; they are
  not in its trie but in `ImportTrie` (`UserWords.startWords`, packed arrays, made again when the
  imports change), which `PinyinDecoder.collectStart` walks from the lattice's start alone: arcs
  flagged `START_ONLY`, which `extend` skips, so such a word is never inside a sentence or after a
  word, nor in handwriting's sentences (C19), scored as the model's unknown word and
  `Penalties.imported` (-2) below. Owned, added by hand or listed it is a whole word in the trie
  (`UserModel.settle`) and out of `ImportTrie`; out again as that ends, never dropped while
  imported. `HabitRanker` puts the `IMPORTED` tier after the last dictionary word spelt exactly as
  the code (`Reading.exact`: no fuzzy, typo or neighbour), after them all if none is, orders it by
  batch and place (D24), and never takes one as the original first (D23); `HabitOrder` puts in a
  page of them a level. Committed twice it is the user's (`Mine.FORMED_FROM_IMPORT`) and leaves the
  tier. It is no voice hotword (`userWords` leaves it out) and not in the test-only text export (the user's export is the FXLB backup, which carries it and its
  batch); the tables take it
  as a dictionary's word (`UserModel.listedTexts`, below). `ImportLayer` lists the batches (词库与短语
  → 导入的词) and undoes one (`dropImport`, the user's words staying), putting it back for 撤销
  (`restoreImport` by batch). With 100k imported: +23–27 MB retained heap, +0.3–0.5 s to open.
  Import records carry no minute; `LearnLog.fold` dates one as the record before it, so a tail of
  imports alone folds.
- **Coarse words** (`CoarseWords`, list `coarse.txt` beside it): never predicted, `PENALTY` lower in
  the decoder, a candidate with one goes past the first page, English never corrects to one. Once
  the user typed one (committed it once) it is theirs and none of this applies: it is ranked and
  predicted as any word. What goes on the list, and what stays off (words typed on purpose, plain
  anatomy, words common in another sense), is the file's header; single characters are on it too (a vulgar
  character the web made common, such as 肏 for `cao`).
- **Misreadings** (`lexicon/misreadings.tsv`): a word is also under its common misreading, a tenth
  as likely, and its candidate then shows the right reading with tones.
- **Strokes.** `u` with nothing typed starts `StrokeLookup` (`h s p n z` = 横竖撇捺折); any other
  letter turns it back into pinyin (`usb`, or `uan` as a slip for `yan`), typed at the strokes'
  cursor, which they keep as pinyin keeps its own.
- **Latin words** in pinyin (`iphone` → iPhone) come from the `latin` layer, spelt in letter
  syllables; full pinyin only, setting `EngineSettings.LATIN_WORDS` (a toggle on full pinyin's page).

### Long press on a candidate

`Session.offers(index)` says what a long press may do; `LearningOps.menu` turns that into the
`LearningOp.MenuItem`s and `LearningMenu.rows` into the rows shown: `DELETE` (删除 on the user's or an
imported word, 忘掉学到的 on a dictionary's: what was learned of its text, everywhere), `BLOCK` (屏蔽,
its scope stated first: everywhere, or for a table under the code typed alone; into the block list,
which the settings can undo; it unpins the text everywhere), `PIN` (固定首位: first under the keys of
its own span, in the learning record; the custom phrases are untouched; not for handwriting), `UNPIN`,
`DELETE_PHRASE` (从自定义词组中删除, on a custom phrase: `PhraseBook.remove`, a built-in one turned off,
no undo), `NO_PREDICT` (不再联想, on a 联想), `WHY` (为什么排在这; not for English, nor a phrase alone). Each has conditions (`PinyinSession.forgettable/pinnable/blockable`), and none
are offered where nothing is learned (a password field, `Session.learning` false).

## Tables

`TableSession` ranks entries whose code starts with the input as libime does (codes up to
`noSortInputLength` in table order, then shorter codes first), the table's own before the words
from outside it of a code as long (U-28). Auto-select commits a
lone full code, or the first candidate when the next key leads nowhere; 空码 is dropped by the next
key. The code keeps a cursor as pinyin's input does; a key typed before its end is put in there and
never commits by the next-key rule (the code is being mended, not full), a lone full code still
commits. `pinyinKey` (五笔 `z`, 二笔 `[`) looks a character up by pinyin, showing its code (the
cursor then the pinyin's);
`matchingKey` is the wildcard (五笔 `z`, 仓颉 and 二笔 `*`). Characters typed one by one make no
phrase (U-28 C46: 自动造词 and its two settings are gone; an older config's `AutoPhraseLength` and
`SaveAutoPhraseAfter` are read by nothing); the phrases an older build saved (`TableUser`, the FXUT
log) stay the user's words and are told to pinyin once.
`SharedWords` gives every table the user's words from the other input methods, coded by its rules:
those made, added, typed, and every word of an imported dictionary (owner, 2026-10-19), ranked after
the table's own. A full code the table has one entry for, which commits itself, takes none of them
(D8): one who types it blind keeps it, however big an import (F2328 had 搜狗标准 393k take 五笔's
auto-commit on eval text from 22% to 9% before this).
The dictionaries' words are coded in the background (`InBackground`, on `warmUps`) at the first key
after a user model is made or reloaded; till then a table offers the rest.
Options per table are in `androidengine.h` (`engineTable(...)`) and `TableOptions`; prediction and 性能档位
are the shared settings on the 通用输入 page (`InputMethodSettings.SHARED`, `SharedInputFragment`), page size
on the hardware keyboard page (`HARDWARE_KEYBOARD`); the addon's `EngineTablePage` config still carries them.

Every table's words follow one rule, set at build time (`table --words --pinyin`, decided on 五笔
86 and kept for all, `lexicon/README.md` "Words in the code tables"): the table keeps every entry
it ships, only reordered, under each full code the commonest first; words added from pinyin.data go
when under a tenth as common as their code's commonest; new words (the pack's) never go and count
ten times; characters never go; 简码 (codes shorter than the longest) stay as they are.

At typing time a table has the five every Chinese input method has (`TableLanguage`, made in
`Engines.tableLanguage`). Where it orders by use (调频: off on a new install, D7; an install that
typed a table before keeps it on, `EngineMigration.orderByUseKept` writing `OrderPolicy=Freq` once,
at the first unlocked start: a locked start notes which groups said nothing yet (`tables.order-unset`
in the config home), so a `No` fcitx wrote meanwhile, its new default, is not taken for the user's;
the engine directory put back from `engine.old` and made first, so a new install has its marker
before it types a table; an older build's table log opens after the learning record, whose
conversion reads its picks (`TableRegistry` `beforeOpen`);
the presets keep fcitx's values for ime-eval), a code's 重码 — the candidates whose code is all that
was typed, longer than `noSortInputLength` (五笔's 一二级简码 stay put; 自然码 and 二笔 keep none) —
are placed: the table's entries by the word model after the text before (`TextWords.logProb` over
the user's scorer), one blocked under the code left out of the weighing. The small sentence model
weighs them again at each key (medium), the large one at a pause (high). Off, the table's order
stands for those who type by position. The user's habits then reorder it under the keys typed
(`TableHabitOrder`, U-28, PLAN §2.7): 调频 on, the 重码 as `HabitRanker` ranks pinyin's with no
前文 (U-02: picked once, before those not used; twice running over the first, first), a word from
outside the table (the user's own from pinyin, one imported) after the table's own till used here;
off, the table's own where they are, those from outside after them among themselves
(`HabitRanker.rest`, 「首选以外」). Never under a wildcard nor where the code is kept in order. Then the
one pinned under the code (`Habits.pin` on `Code(tb:<im>, typed)`, 调频 on or off) first;
`Session.why` says each one's tier, uses and that code (U-81).
What is put in is learned into the learning record (`KeyedRows`): a PICK row under the code typed
(none under a code with the matching key), no word before it, and a WHOLE row under no keys after
the word before, for 联想 (its words' uses and pairs only: no STAT nor FIRST of its own,
`LearnState.keyed`); their words have no reading, so `HabitCounts` never counts them for pinyin's
order (D28), and a pair to one is 联想's alone (`UserModel.setPair(byText)`: `probability` has it,
`decoderProbability`, which `UserScorer` reads, does not). A 联想 picked, there as in pinyin, is
learned so too, under `Kinds.PREDICTION` (`pred:<the word before>`, by its text). A field's U-91 flags (`Action.Context.learnFlags`)
hold for them all, the lookup too. A 拼音反查 commit is learned as
全拼 (the lookup's `PinyinSession` learns into `Kinds.FULL`), the table's codes untouched. A long
press: 删除/忘掉学到的 for a word from outside the table, or the table's own while it orders by use
and the record knows it (forgotten everywhere, U-84); 固定首位 under the code typed; 屏蔽 under the
code typed alone (event 15 `TableBlock`, snapshot row 65 `TableBlockRow` in learn.log,
`Habits.blockUnder`), a character too, read through `TableLanguage.blockedUnder`: the record's whatever
the session's habits, so blocks hold with 「用我的输入习惯」 off (U-93, C26); a block made while it is off still unpins the word in the learning record (the block wins, review R11 F3021), and an older build's table phrases are converted into the record once even while it is off (F3066). D8 counts the table's
entries of the code as texts not blocked there: one blocked lets the words from outside in, a row
twice is one. A block
from pinyin hides only the words from outside the table, never its own entries (C12; nor in a glide).
恢复码表顺序 is `Engines.resetTableOrder(im)` (`TableOrderReset`): what was learned of the order goes,
pins and blocks stay; the table's settings page has it (`InputMethodSettings.Action`, confirmed first). 联想 follows a commit until the next key. A long press on a
联想 offers Predictions' forget and block (block only where the table could block the text as a candidate, `SharedWords.blockable`: never a single character, since `UserLexicon` blocks a text in every reading and a character blocked would go from pinyin and strokes too), as handwriting's does: forgetting one forgets that entry's
reading and the tables' copy of it, blocking leaves it out of 联想 from then on.
`ime-eval table <t.data> <set> --preset <p> --pinyin <pinyin.data> [--rerank --refine]` measures
the 重码 typed first after the text before, codes too short to be sorted left out. The preset must
order by use (wubi, ziranma, erbi, wubipinyin): a table in its own order is not weighed by the text
before, so 仓颉 and plain, which 调频 can turn on in the app, are not measured.
On pinyin.tsv (2026-10-16), the table's order → the word model (low): 五笔 76.4% → 93.3%, 二笔
63.0% → 91.9%, 自然码 75.1% → 93.1%, 五笔拼音 79.9% → 95.0%.

## English

`EnglishSession`: word-at-a-time, as Gboard. The candidates are the word typed and what it may be
(`EnglishDecoder`: spelt, started, or spelt with a slip); a word that is none is corrected and
space commits the correction; backspace right after takes it back and that word is never corrected
again; so is a word picked over its correction, and one whose correction a tap back takes back
(`Corrections`: the cursor put right after a correction of the field's last few, the word typed is
the one candidate). Space after a word adds a space and predicts; punctuation moves that space
behind it; double space is a full stop. `EnglishUser` reads the words from `learn.log`, each word
committed an `EN_COMMIT` (prev, word, typed, how, the field's flags): counts over the last 30
typing days; never corrected (`EN_WORD` flag) and added words kept till deleted on the English
page, which gives a word back to correction. Typed twice is not enough (D11). `Misspellings` corrects common misspellings the web taught the model as
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
on the keys it means. Words the user committed that the data lacks glide too: `EnglishSession` holds each to the same `GlidePath` and scores it by the user's counts less the cost. Whether a glided word needs a space before it is read off the
field's char before the cursor, as the host tells it (`Action.Context`), or what the session
put in itself; what was written in a field now empty (`Action.Context.cleared`) is scored after
but is not before the cursor: no space after it, and no Backspace takes any of it (`clearedAt`); a field not learned from tells nothing (user decision F2074: such a field's text is
not read at all), nor does what the keyboard typed there (`androidengine` sends no `TYPED` from a
PasswordOrSensitive field: a password shown is Sensitive, not Password, and so looks like an
incognito field), so there only the session's own glide counts; after typed text, a Backspace or
an arrow nothing before the cursor is known, and a glide gets no space before it (part of the
F2074 limit). A glided word always takes its in-sentence form (`inSentence`): the data's
sentence-start "Hello" is "hello" anywhere, a word the data or the user has only capitalised (London,
iPhone) keeps it; a capital comes only from Shift, as for a word tapped (`AutoCaps` turns Shift on
where the field asks for sentence capitals), Caps Lock all capitals. Where English suggests nothing (`androidengine`'s
`suggestsEnglish`: NoSpellCheck, an address, a password, suggestions off) the keys type themselves.

On the nine keys English takes `Action.NineKey(digit, upper)` (the host `Keyboard`'s
`NINE_DIGITS`, 0xF0040 up, past `LETTERS`, clear of INK and GLIDE). The input is then digits
(`'` from 1, kept in place) and `NineKeyWords` reads it: an `EnglishDecoder` whose substitution is
`nineKeyCost` (a digit fits any of its `NineKeyLetters`, the same 按键字母 as 九键拼音; a letter only
itself) and whose every edit costs `NINE_KEY_COSTS`' `NEVER`, the user's own words matched the same
way, then words as long as the keys first, completions after, each by score, in the case Shift gave
each key. A letter a long press pinned (`Action.NineKey` with the letter, `Keyboard.NINE_LETTERS`
from 0xF0060; where English does not suggest the addon swallows the digits and types the letter) is
itself among the keys, and marks the word the nine keys' (`pinned`) though no digit is left: never
corrected, first among the candidates when every letter is pinned (a prefix of longer words too), and
a word so spelled the data lacks is learned and offered by its keys after (owner 2026-10-20). The
preedit and `typed()` are the first candidate, else each key's first letter
(`NineKeyWords.firstLetters`, a guess not learned), so space, punctuation, Enter and the
arrows commit a word, never the digits; the cursor stays at the end; `keypad()` picks the nth
candidate (`picksByDigit`). `ime-eval english --keys nine` measures it (2026-10-20, english.txt:
95.9% first, 98.4% in the first three).
Each char a screen key types there is sent as `EngineEvent.TYPED` (`Keyboard` passes it on, then
`Session.typed`: the session's edge, and its known text if it knows the text before it). The
screen's Backspace there goes the same way as `Session.ERASED`, as does the session's own
Backspace with nothing typed: the last code point of what is known taken off, of the edge where
nothing is known; a CR taken or left last has it forget, a "\r\n" being taken differently by
editors (a key event both, an opted-in editor the "\n"). An opted-in editor has one code point
deleted directly (a surrogate pair as one; an unpaired surrogate goes as a key event, which
removes it); any other editor gets a key event, and what it takes is the editor's to decide (an
emoji sequence or a flag whole, perhaps). So where the code point taken or the char left may
belong to such a cluster (a combining mark, a ZWJ, a variation selector, a keycap, or any char
outside the BMP: an emoji, a skin tone, a regional indicator, a tag), all that is known is
forgotten rather than guessed; a glide right after may then add no space. So "abc",
Backspace, a glide gives "ab hello ". The edge keeps the last 32 chars before its trailing spaces
for Backspaces with nothing known: where the session forgot the text (a Reset: an Enter, an arrow,
a hardware key, the field left) and no Context was read since, even in a field learned from
(measured on redroid in the test page's 不要提示 field: a 1-char edge gave "abhello "). A
Backspace over a selection is one `ERASED` too, so the service reads the context then
(`handleBackspaceKey`; the cursor report, predicted, tells none). None from a password field, nor from a hardware key, which comes this way in fields that suggest too:
those, and keys no char, go as an Enter. The Glide char goes in with no Enter before it, and the
one key right after it, if Backspace or a char other than an ASCII letter, is the session's as
where it suggests (the word taken back, the space moved); the words that would follow are put away
by a `TYPED` of 0, so `Keyboard` knows the panel empty. Learning there is as anywhere: glided words
are counted outside password and sensitive fields (they are the user's words), while typed ones
never reach the session as words.

Full pinyin glides too (`engine/glide/PinyinGlide`): the dictionary's syllable trie is walked as
English's key trie, each reading with a word scored by its likeliest word (the unigram, or its
characters one after another where likelier, as the decoder's best path takes 你好) less the path's
cost; one word a glide (chained, the readings ran to thousands). The user's own words glide
too: their trie (`UserWords`) is walked alongside the dictionary's, a reading in both one reading. Each pinyin glides its own keys
(`PinyinGlide.Spelling`): full pinyin the letters, 双拼 its scheme's two a syllable (微软's and
紫光's -ing is on `;`, which a glide path cannot carry: `GlidePath` takes only a..z, so those syllables
cannot glide, though the unsplit letter keyboard has a `;` key for them), the nine keys
the letters too, on their digits' keys (`GlideLayout.sameKey`: letters on one key follow without
the path moving). A table glides its codes (`engine/glide/TableGlide`): its entries walked in code
order as a trie, a code scored by its likeliest entry under the word model. `GlideSession` wraps
each of their sessions (`Engines.glides`: all but handwriting, English its own): `Action.Glide`
types the best reading's keys (the nine keys' as `Action.Letter`, so 426 is not read as gan), after
`between` where something is typed already (pinyin's `'`, so glides chain into a sentence; a
table's first candidate picked), and a glide that starts the input puts the next two readings'
words after the first candidate, a pick of one typing its reading and picking it there, so it is
learned and followed as any word. `ime-eval pinyin-glide` measures it per dictionary word of a
pinyin set (`--shuangpin`, `--keys nine`, `--table`): on pinyin-chat (readings compared with their separators, xi'an ≠ xian) full pinyin 85.3% first,
96.5% in the first three, 0.4 ms; 双拼 (自然码) 82.1% / 94.2%; the nine keys 53.5% / 76.7% (a key three or four letters, the path reads far more; the weights moved it under a point); 五笔 70.6% / 90.0%,
仓颉 94.5% / 99.3% (the characters it has). These times are with no user model: with one, a glide works out again only the words the user has counts of, at the nodes (pinyin) or entries (a table) it reaches, the rest kept till a word gets its first count, is forgotten or blocked; unmeasured. A table entry with no count of its own keeps its score meanwhile, though a counted word is in its likeliest split (你们 as 你 + 们). The setting is off by default: a sloppy fast tap may glide.

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
Every sentence put in, the first too, is recorded as one commit (`KeyedRows`, U-29): each
character a PICK row under handwriting's code (`hw`) whose keys are the first three readings of its
ink sorted by code point (`KeyedRows.handwriting`), no word before it, the last one corrected where
the first shown was another and the ones before it as read; and the sentence's words, at the
readings the decoder gives them (the dictionary's likeliest, as 添加词 fills one in), a WHOLE row after
the word before, for 联想, of no origin where the sentence is no word of the model's nor the user's.
Counted by their text alone (`LearnState.heard`), never for pinyin's order (D28, `HabitCounts`); a
sentence of 2–4 Han characters that is no word, put in twice, forms the user's word at that reading
(U-06), typed in pinyin as any of theirs. The candidates for the character last
written are ranked by those rows under its code as pinyin's with no 前文 and no pin (U-29,
`HabitRanker`): used ones before those not, by uses; one picked over the first twice running,
first; `Session.why` says so. A long press on a candidate acts on the
character it shows (U-29, U-82): `FORGET` where something is learned of it (`Habits.knows`),
forgotten everywhere; `BLOCK`, into the block list at the reading `HandwritingDecoder.alone` gives
it, which hides it in every reading (the decoder's last search forgotten, `forgetSearch`); never
`PIN` (C18). What is written is read again after either. Its readings, for 联想, are the
decoder's (`Sentence.words` of `Decoding.endings`/`top`: a word or a character alone at the
reading the user has it at, whichever reading the search finds first; else a
word at the dictionary reading of highest weight found, a character at its likeliest,
`HandwritingDecoder.alone`): the user chose it over the ink's best. A dictionary word has one entry in
the user model, so of its dictionary readings the user's is the last typed; a reading of the user's
the dictionary lacks wins over the dictionary's, and of it and a dictionary reading typed too, the more
typed. The user has a reading while it is
their choice, owned or added by hand (`UserModel.chosen`, `UserModel.reading`; a dictionary word
typed at its own reading is no longer one); one only imported
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

Every compiled file is a `DataFile` (`emoji.data` and `chaizi.data` aside: plain UTF-8 text): `"FCITXIME"`, container version, kind, kind version, then
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
- **The learning record, `learn.log` (`engine/learn/`, PLAN §2.2, impl/design-1a.md).** FXLR
  version 1 for good: a change is a new record type or fields appended to a type's end. Type ids are
  never reused; `LearnRecords`' class comment is the allocation: events 1–15 (`TABLE_BLOCK` 15) and 28
  (`EN_CORRECT`), 16–27 and 29–31 free (next: 16); snapshot rows 32–48, 65 (`TABLE_BLOCK_ROW`) and 80
  (`DATE_ROW`), 70–79 the backup file's (73 `ORIGIN` the one a log may also hold, 74 its END, 75–79 kept for the file: a backup drops every other row of 70–79 on write and read), never a log's; free rows 49–64, 66–69, 81 on (next: 49). One reducer,
  `LearnState.apply`, serves the start's replay, the fold and the live commit, so what a start reads
  is what was learned live. A record this build cannot read is kept as it is (`Unknown`,
  `LearnState.kept`) and written back after a fold's snapshot; one whose apply throws is reported and
  left out (keeping it would apply what it half did again at each fold); a fold keeps the snapshot's
  KIND ids (`Reader.kinds`). `RecordStore` folds it in the background from the file's own bytes
  (`LearnLog.fold`): what is older than the cut becomes a snapshot, the rest stays as written.
  `learn.converted` (`ConvertedMarker`) says the old logs were converted once (`LegacyConversion`),
  how far each was read (length, time written, CRC-32 of those bytes) and the older pinyin model's
  own words then. When an older build wrote them since (a downgrade, then this build again), the
  next open takes in what it did (`LegacyConversion.since`): the forgets its logs record past what
  was read, unless the word comes again after (a log rewritten since says none), its model's new
  words of the user's, a table's saved words; never a forget inferred from something missing, never
  counts, English words or pins (an older build's pin is an order-1 phrase, still shown first). The
  marker is written when converting and after a catch-up, never at close, so a process killed and
  one closed open the same: this build's own appends to the logs it still writes (the tables',
  English's) are read again and change nothing (its forgets are the record's already: `FORGET`
  drops a text's table words, English's forget writes `EN_FORGET`). A heavy first conversion is
  made off the typing thread (uat-0.3.1): `learn.converting` beside the log says it is owed, and
  the log learns meanwhile. The state made is merged into the log's snapshot (each count the
  larger, so merging twice changes nothing) and the tail written since is kept as written: what
  was typed then counts on top, and 清掉最近 still reaches it. `Engines` puts the merge in place
  where typing pauses (`Engines.paused`: a field started, the keyboard shown or hidden, a second after
  the last key; never while something is composed), and makes it at once before an act on the whole record
  (`finishConversion`: a backup, an export, a restore, 退回, 清空, 清掉今天, an import, the pages' counts;
  not the backups' switch, told at every start, nor 清掉最近 10 分钟 or 1 小时, which reach the tail
  alone: those would convert on the typing thread). The merge reads whole records only, one being
  appended is the tail's.
  A merge made stale by a fold is made again a few times, then on the fcitx thread; one that cannot
  be written is owed to the next start. The app's `additions()` and `legacy()` are read on the
  fcitx thread and handed over as values. A clear while it is owed owes it no more (`remarkLegacy`),
  the tables' logs alone too. A
  fault of the record's own turns learning off for the run (`LogHabits`, `CommitRecorder.safely`)
  and never loses the commit. The traditional text before the cursor is read simplified by
  OpenCC's tables (`assets/learn/`, `Simplifier`): text with a character simplified text never has
  is traditional, and only there are those simplified text has too changed (乾淨 to 干净, 由於 to
  由于; 乾坤, 哪吒 as they are).
- **The user's controls over learning (`learn/control/`, PLAN §2.8, impl/streams/E-design.md).**
  One entry, `Engines.learning(im, op)` (`FcitxAPI.learning` in the app), for every `LearningOp`:
  a long press's menu and acts, the undo chip, U-93's switch (`Engines.useHabits`: sessions made
  as installed but for the blocks and the turned-on base dictionaries, a setting, not a habit:
  pinyin's `freshUser` keeps them, and tables offer their blocks plus those dictionaries' words,
  `TableRegistry.dictionariesOnly`, never own, added or learned words; review F3412), U-88 清掉最近 and U-86 清空学到的, the lexicon page's lists.
  A candidate's act goes as its long press always did, as the host's event, so the addon shows the
  list it leaves; what changes the list otherwise has the app send `Keyboard.AGAIN`, a private-use
  key the session answers by reading its input anew (`Session.reread`). `LearningControl` keeps
  the last act's records (`LearnLog.appended`) to take it back: by writing the opposite where
  there is one (a pin, a prediction turned off), else by `LearnLog.rewrite` without them; never
  once the log was rewritten since (`LearnLog.rewrites`). The clears are rewrites too
  (`LogRewrites`): U-88 drops the tail's commits from its cut on (never before the snapshot's cut
  or the last restore), U-86 writes a new snapshot of what stays, HEAD kept so typing days and
  seqs go on; both delete the older builds' retired files (`LegacyConversion.retired`) and remark
  the marker. `RecordStore.rewrite` finishes a fold being written first and never writes a header
  alone. A word's last-used calendar day is `LearnState.dates` (`DATE` row 80: typing day to
  local day). A search field (`NO_PREDICT`, U-91) shows no 联想 either (`PinyinSession.predict`,
  `Predictions.predict`); `Engines` keeps the field's flags for a session made after its context. A
  commit flagged `NO_PREDICT` or `NO_PAIRS`
  (`Commit.UNPAIRED`) counts no pair and no 联想 pick (a `pred` row) on replay: a tail written by a build that still paired them
  loses those pairs at the next open, as meant; an older build reading the record pairs them. A clear that
  fails on disk says so (`LearningResult.Failed`) and still saves a backup if they are on.
  The tables (stream C) learn into the record too: a candidate's block is per code (`TableBlock`,
  listed as `Listed.TABLE_BLOCKS`), kept by U-88/U-86 but 全部清空, which also forgets the phrases
  an older build saved in `<im>.user`; 恢复码表顺序 is `LearningOp.ResetTableOrder`. Tables and
  handwriting key their 联想 by `Predictions.why` (the predictor's `after`), as pinyin, and reread.
  Pinyin's 联想 goes on from the user's own word the record has last where the decoder made it of
  several (你好 of 你 好: `PinyinSession.ownWord`), so what was put in after it, keyed by its text,
  is offered and turned off there.
- **Learning backups (`learn/backup/`, impl/streams/D-design.md, U-100).** `Backups` keeps them,
  `BackupControl` makes, restores and takes a restore back (退回恢复前), `BackupMerge` merges one
  into the phone's record. An FXLB file is a head (70), the record as a snapshot, then the lists
  (71, 72), then always and once, last, an END (74) holding the file's length in bytes, itself
  included; a file without it, or whose END is not last or names another length (cut at a record's
  boundary, a record lost or added, a draft build's file), is refused. 73 is the record's (below),
  75–79 a later build's of the file and dropped on read and never written (a kept row of 70–79 but
  73 is left out of `bytes`), any other type the record's and kept as it is (review-int-records M1).
  `ORIGIN` (73) rows say by how many typing days an install's backups were moved, so the same one
  restored again lands where it did; every install that opens the record writes one of offset 0
  (`BackupControl.claim`), so a record carried to another phone (D2D, a settings zip) restores the
  backups made where it was on its own days (S5). A backup's import batches new to the phone get
  ids past the live record's last, undone ones included (S3). The lists, `EnWordRow` flags, a
  table's blocks and 不再联想 merge as a union: an older backup brings back a flag removed since
  (U-100's 合在一起). U-86 (not 全部清空) keeps the ORIGIN rows, as it keeps the table rows they moved; a pin of a text blocked after the merge is dropped from either side, and a word on both of a backup's lists comes back blocked (C9). Automatic backups are kept two newest by typing day, are timed no earlier than the last commit's minute, and are not made while an older build's conversion is pending and can still land this run: one that failed lets them be made of what there is (review R11 F3086). Rows kept unread are written with the ids of KINDs of the build that wrote
  them: a later build must not put kind ids in rows it expects a backup to carry. `TABLE_WORD` rows
  are a mirror of `<im>.user` (`TableUser`, which serves a table's saved phrases and tells pinyin
  through `learnSaved`): converted, merged, backed up and kept by 清空, read by nothing yet. An older build's PICKED records are kept through compaction until converted (review R11 F2649).
- **`merged.ymd`, version 2** (`DictionaryFile`, `DictionaryCompiler`; `dev/design/dict-size.md` §3).
  Trie words come in the nodes' breadth-first order, so a node's words are the run between two
  entries of `WORD_START` (nodes + 1); a trie word's syllables are its node's path, read walking up
  (`syllables`, `length`: for a chosen or learned word, never per arc), and only the words kept
  apart for the base (from `trieWords` up) store theirs (`SYL_START`/`SYLLABLES`). Scores are u16
  codes into `SCORE_BOOK`, the file's distinct scores when there are at most 65,536 (exact; frost
  has 9,014; meta `scores` = `book`), else linear over [floor, 0] with the best exactly 0f (`linear`,
  the book holding the floor); −0f is written 0f. The text index is `TEXT_PLACES` (places sorted by
  their text's slot, `BitPacked`) and `TEXT_BUCKETS` (each slot's start, `EliasFano`): `find` walks the
  trie by syllables, then a bucket for places past `trieWords`. `TEXT_START`, `FIRST_CHILD`,
  `NODE_SYLLABLE`, `WORD_START` are `BitPacked` at per-file widths. frost: 105.6 MiB (v1) to 49.4.
  `checkStructure` holds the BFS invariant (`firstChild[i] > i`); a walk up stops below the root as
  well, so a damaged file read before its check (`verify = false`) gives a short reading, never a
  walk that does not end. A text's learned reading (`UserModel.mergedEntryOf`) is its best-scored
  trie place; on a tie the lowest place wins: the shortest reading, then the lowest syllable ids
  (v1 took the highest; neither is wrong, words of an unweighted source all tie). A v1 file is refused
  (`format version 1, this build reads 2`), deleted and compiled again; a table's `.ymc`
  (`CodedIndex`) is keyed by the file's stamp and is rebuilt with it. Per-key cost is gated by
  `ime-eval pinyin --merged <source.txt> [--stale]` ([testing](testing.md)).
- **Untrusted input.** Imported dictionaries, safetensors headers, libime files and zstd frames come
  from outside: every size is checked before allocating, nesting is bounded (`Json.MAX_DEPTH`),
  and a failure says what did not fit. Keep it so.

## Performance

A key must decode in a few milliseconds on a phone. Nearly all the time is the scorer and, at
`MEDIUM`+, the 4M model; `HIGH`'s 25M model runs only in pause slices. Measure with `ime-eval`
(it reports latency per key, p50/p95/p99) before and after anything on the decode path, and keep allocations off
it: `Counts`, `IndexCounts` and `WordIndex` exist because boxing in a `HashMap` showed up.
