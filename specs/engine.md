# The engine (`lib/ime-core/.../engine`)

The input methods are this project's own, in Kotlin, replacing libime and fcitx5-chinese-addons'
engines. Read the class KDoc before changing a class: most say why they are as they are, with the
measurement that decided it.

## What every Chinese input method has

Each Chinese input method the app ships (全拼, 双拼, 九键, the code tables, handwriting over them, and any added
later) has the same six (five decided 2026-10-14, the sixth 0.6.1's); a new one is not done without them:

1. **The word model** (the pinyin data's n-gram) in ranking, after the text before the cursor.
2. **The sentence models** as the 性能档位 has them: none (low), the small one at each key (medium),
   the large one at a pause too (high). One setting, shared, on the 通用输入 page (`InputMethodSettings.SHARED`); each input method's page has its own options and a link there.
3. **Prediction** (联想) after a commit: one setting, shared; a prediction picked is learned.
4. **Learning** into the one user model (`UserLexicon.user()`), what the user picks; not while
   `learning` is off (a password).
5. **The user's lexicon**: their words, added and blocked (`WordLists`), found and ranked as the
   dictionary's are; a blocked word never offered.
6. **生僻字屏蔽** (0.6.1, `dev/design/061-rare-char-block.md`): a character of a group held back
   (`CharLevels`: 三级, 繁体, 其它表外, 扩展区; 一级/二级 never), or held one by one, is never offered,
   predicted, put in a sentence or committed alone, nor any word with it. One gate on `UserLexicon`
   (`held()`/`gate()`: `CharGate` by text, `HeldWords` by word id, rebuilt only when the data or the choice
   changed) behind the "is this word blocked?" each input method already asks
   (`UserModel.blocked*`, `UserLexicon.isBlocked*`), plus the tables, handwriting, strokes, 拆字 and
   voice by text. Held back is a view: the user's words stay. `RareCharContractTest` types each way a
   candidate comes (keys and glide) through hold → let through → hold on one `Engines`, ime-eval's
   `RareCharsUatTest` every `LISTED` Chinese input method on the real data (specs/testing.md says
   which covers what); a new input method is not done until it passes there.

All have them: the pinyins, handwriting (2026-10-14) and the code tables (2026-10-14, `TableLanguage`;
see Tables for where a table's order may move).
Every session's prediction is `session/Predictions` (pinyin's too since S11, its long press and
its learning still its own), and handwriting and the tables keep the text before the cursor there;
they share the large model's slices at a pause (`session/RefineSlices`) too.

## Packages

| Package | Holds |
|---|---|
| `session` | `Session` (the host's view: `apply(Action): Snapshot`, `offers`, `candidates`), `PinyinSession` (全拼, 双拼, 九键), `NineKeys`, `CoarseCandidates` (the candidates' coarse first pass), `Predictions` (联想 for every session: pinyin's, a table's, handwriting's), `OfferRules` (what a long press offers, one rule for all: nothing but a phrase's delete where nothing is learned), `Pager` (every session's paging of its candidates) |
| `pinyin` | Turning keys into syllables: `Syllables` (every syllable, append-only ids), `SpellingIndex`, `PinyinSegmenter` / `ShuangpinSegmenter` / `T9Segmenter` → `SyllableGraph` (the spelling index and the nine keys' tables made once for the settings that shape them, `SettingsMemo`); `Fuzzy`, `Typo`, `KeyNeighbours`, `ShuangpinScheme`, `NineKeyLetters`, `LatinWords` (the dictionary's Latin words) and `UserLatinWords` (English's learned words, its dictionary's aside, which `Engines.syncLatin` lists in the user model spelt by letters before a key of 全拼's: U-31 §3) |
| `lattice` | Turning syllables into text: `PinyinDecoder` (its `LatticeBuilder` collects the word lattice, `Lattice`, along a `Path`; `BeamSearch` searches it; the first words it ranks itself), `Penalties`, `WordScorer`, `UserWords`, `LayerPrior`, `Predictor` (联想), `TextWords` (the model's words in text the engine did not commit), `CoarseWords` |
| `rerank` | The sentence models: `SentenceModel` (char-level transformer, int8 safetensors), `Reranker` (`SentencePicker` / `SentenceRefiner`), `MatrixKernel` (the hot loop; the app runs it in C++, `NativeMatrixKernel`), `Safetensors`, `Json` |
| `user` | Learning: `UserModel` (counts of words and pairs, words made from pieces), `UserScorer` (mixes them into the model), `SyllableTrie` (the learned words by syllables), `Counts`, `HabitCounts` (the counts from the learning record), `WordLists` (added and blocked by hand; the text export (`exportWords`, `WordImport`) writes a blocked word `!text pin'yin`, an emoji's its word's reading read back by `WordLists.blockedEntry`, and English's `!@en word` (`WordListFiles.ENGLISH_BLOCK_MARK`: no reading, and letters alone read as pinyin) into `EnglishBlocks`; a table hides a word blocked everywhere (`UserLexicon.isBlockedWord`) only where it is of Han or of ASCII letters and digits: an emoji blocked in pinyin's reading stays in a code table; `blockedEntry` relaxes the syllable a code point only for a text of no Han nor ASCII letter or digit (ℹ️ too; the edge on purpose: a word of other letters, Cyrillic or accented Latin, is read as an emoji is, its syllables as given, and a table does not hide it); a build before this one reads neither back, and loses a block of an emoji of more or fewer code points than syllables from `words.blocked` too; the user dictionary's export is a learning backup since 0.3.1 (`BackupOp.Copy`, WD-10), which its import restores; an earlier build's text export, which `UserLexicon.exportWords` still writes for the tests, has added words as `词 pin'yin`, blocked ones with a `!` before them, learned ones with a `~` apart before them and their count after them (`~ 词 pin'yin 3.0`: apart, an older version's parser reads the line as two words and skips it, rather than adding a word `~词`; picked into the base, `WordListFiles.baseLines` takes the word without the count, and only from an export: `baseLine` alone drops a `~` line); both imports read `~` only after `WordListFiles.EXPORT_MARK`, the file's first non-blank line (byte order mark and spaces aside), as `WordListFiles.exportMark` tells it for `baseLines`, the user dictionary's import and `isOwnExport` alike; `isOwnExport` and `exportMark(old = true)` also take an older export's header, and say whether the user dictionary's import adds a list's words back (another list's are imported words, below); the default target dictionary asks `isUserExport` (`isOwnExport`, or a `!` line); the user dictionary's import makes those words the user's once each in the learning record (`Mine.TAKEN_IN`), the exported count as uses on the day (`~ 词 pin'yin 3.0` comes back at 3, under one as one), skipping any that are added, blocked or already learned; an older export has no `~`, so its learned words come back as added), `WordPack`, 生僻字屏蔽's `RareChoice` (the user's file `pinyin/rarechars.txt`: groups held, characters let through or held one by one; parsed and written keeping lines and groups it does not know, a later version's read as off and never written), `CharGate` (a text held?), `HeldWords` (the dictionary's words held, a bitmap by id), `RareGrid` (the page's characters with readings, copied off the engine in one call) |
| `table` | Code tables (五笔, 仓颉, 自然码, 二笔, 五笔拼音): `TableDictionary`, `TableSession`, `PinyinLookUp` (拼音反查: a decorator over the pinyin session the table hands every action while it is on), `TableOptions`, `PhraseRules` (组词规则), `SavedPhrases` (the phrases an older build saved, from the learning record), `SharedWords`, `TableLanguage` (the five a table shares with pinyin), `TableText` (reading a table's sources), `IndexCounts` |
| `glide` | Glide typing over Chinese keys: `Glide`, `GlideLayout` (the keys' places), `GlidePath`, `GlideSession`, `PinyinGlide` (a path → pinyin readings, full, 双拼, nine keys), `TableGlide` (→ a table's codes), `GlideReading` |
| `english` | `EnglishData`, `EnglishDecoder` (keys → words with slips), `GlideDecoder` (a glide's path → words), `EnglishSession` (Gboard-like word-at-a-time typing, autocorrect, glides), `EnglishPhrases` (the custom phrases among its candidates, A1), `EnglishUser` (the user's words, a view of `learn.log`), `Corrections` (a field's last corrections, for a tap back), `EnglishText`, `Misspellings`, `EnglishDataBuilder` (compiles `english.data`) |
| `stroke` | `Strokes` (by strokes, and a character's orders, `ordersOf`/`startsWith`), `StrokeLookup` (`u` then `h s p n z` inside pinyin), `StrokeFilter` (笔画筛选: a stroke drawn on the keyboard filters what pinyin shows) |
| `handwriting` | `HandwritingSession` (the characters written; made by `SessionFactory` for an `InkOver` and ime-eval, no input method of its own since 0.6.1), `InkOver` (written on over another input method's keys), `HandwritingDecoder` (the characters written read as a sentence with the pinyin model and the user's words), `InkRecognizer` (the character's two recognizers run as their training code runs them, over `InkNet`s the app supplies), `InkFeatures` (what each recognizer reads of the strokes, equal to the training code's) |
| `phrase` | `TextVariables` (the date/time variables `$year`, `${minute}`, `$$`: one scan for `expand`, the editor's `problems` and its help table; a phrase's `#` text, a key's long press item and swipe text of variables alike), `CustomPhrases` (libime's `customphrase` format, its date/time variables `TextVariables`'s; keys kept lower case; `place`: each phrase a candidate of its own at its order, text, never learned; in a table, a code reached by a phrase alone shows it but commits it only by a tap (space, a selection key, a key no code starts, a key past the longest code drop the code: `TableSession`); a decoder's candidate of the same text stays where it is ranked and learned, the phrase not listed again after it (U-66); `CustomPhrases.BUILT_IN`: the date, time and weekday under `rq`, `sj`, `xq`, as keys on full pinyin's keyboards only (26, 18, 14, nine: `Segmenter.spellsFullPinyin`), under a key the user file has none of (in 双拼 `sj` and `xq` spell syllables), never in a table nor English; on a keyed keyboard `PhraseKeys` reads the keys (A1: a keyed keyboard's input as the keys' letters, each code any of its key's, an upper-case letter itself, a syllable taken off the bar its letters (`KeyedSegmenter.takenAs`: 双拼's the scheme's keys), separators skipped; `PhraseBook.typed`: what is offered and the keys an input reads, every phrase of them offered by order, then key, then file order); setting `BuiltInPhrases`, on 通用输入, off: none of them, the user's own as before), `PhraseBook` (a delete from the keyboard under such a key first copies that key's built-in phrases into the user file, so a deleted one stays off; a pin goes to the learning record, not here; `builtInsOf`: the words 日期, 时间, 星期 (`WORD_KEYS`) bring the built-in ones still on in every Chinese input method, placed by `EmojiCandidates` before the word's emoji, never twice where the input is the key itself); `CommonWords`/`CommonWordBook` (常用词, dev/design/060-common-words.md: a word, its syllables and up to 9 replacements, the file `pinyin/commonwords.txt` an export's text, `# youmo-common-words 1` then `text[pinyin]` and the replacements tab-separated; keys a word's whole pinyin, its first three syllables' when longer than three, its initials (each syllable's first letter, `zh`/`ch`/`sh` too) when of two or more, each as the session spells a syllable (`Segmenter.typedAs`: 双拼 the scheme's taught keys); `PinyinSession.place` puts the replacements of the words whose key is the input exactly (a keyed keyboard's through `PhraseKeys`) first, before a pin and a phrase of order 1, whole before first three before initials, each text once, as text never learned; only pinyin sessions, zhuyin's two (`Kinds.ZHUYIN_NINE`, `Kinds.ZHUYIN_DACHEN`) not, and only with the habits on (`SessionFactory`), read from `Engines.Additions.commonWords`, saved through `saveCommonWords`); `EmojiData` (`engine/emoji.data`: the emoji panel's groups and the words emoji stand for) and `EmojiCandidates` (a word's emoji after the first two candidates, pinyin's and a table's alike; a table's word alone on its code commits itself as ever, and its emoji and built-in phrases come first in the 联想 after it, `TableSession.pickOffered` replacing the word (`Snapshot.deleteBefore`, in code points) — the offer ends with any other key or pick: setting `Emoji`, those the phone cannot draw left out by `Engines.emojiShows`); `Chaizi` (`engine/chaizi.data`: characters by the pinyin of their parts, full pinyin's after `u`, setting `Chaizi`, those the phone's font lacks left out by `Engines.textShows`) and `AsideCandidates` (where pinyin puts both: the emoji after the first two, the characters first) |
| `data` | File formats: `DataFile` (the container), `PinyinData`, `CodeTable`, `NgramTables`, `BitPacked`, `EliasFano` (the trigram runs' starts; a lookup costs more, so not for the unigrams'), `Quantizer`, `StringTable`, `WordIndex`, `PinyinDataBuilder`, `CodeTableReader`, `NgramWords`, `SortedKeys`, readers of text sources, `Misreadings`, `DataAge`, `CharLevels` (生僻字屏蔽's levels from the APK's `engine/charlevels.data`, `DataFile` kind 6; `CharLevelsBuilder`) |
| `store` | `RecordFormat` / `RecordStore`: append-only logs with CRC'd records, compacted by rename (over a `LogFile`); a record garbled mid-log (CRC wrong, its length in `1..MAX_RECORD`, a sound record readable after it and any run of such) is skipped on open (B2, `RecordFormat.read`'s `garbled`, passed by `RecordStore.open` alone; backups and rewrites still stop at the first bad record): the whole file is first kept aside as `learn.log.unreadable[.N]` (`RecordStore.isAside`), then rewritten without the bad records, so the next open sees a clean log; a header not known or damage that cannot be skipped is kept aside and cut as before; each of these is reported as `RecordStore.KeptAside`, which the app tells once (`learning_kept_aside`); `ReplaceFile` (a file written whole, put in place by rename) |
| `platform` | The ports by which the core reaches the platform, so that nothing in it opens a `java.io.File`, starts a thread or reads the system clock itself: `Blobs` (the data files' bytes by path), `UserFiles` (a directory of the user's: its files and directories, size, delete, rename) and `LogFile` (one file in it: read, map, append, create, truncate, rename; `exists` is a file there, `taken` anything at the path, a directory too), `Clock` (`LearnClock`'s wall time and UTC offset, and uptime for the tables' timings), and `java.util.concurrent.Executor` for every thread. `platform.jvm` is the phone's and the JVM's implementation, the code from before the ports: `JvmFiles` / `JvmFile`, `JvmClock`, `JvmThreads.each(name)` (a daemon thread a task), `replaceFile(File)` for the app's own files. Tests keep the user's files in `MemoryFiles` (ime-core testFixtures), the disk only where the bytes on it or its refusals are what is tested |
| `user.imports` | Words as text (`WordText`: a text's readings, a dictionary line) and libime's `.dict` read without libime (`LibimeFiles.pinyinDictionary`: its `DATrie`, zstd), for importing |
| (`engine` itself) | `InputMethodCatalog` (the input methods by name, the tables, `LISTED`, the keys each reads, which glide; the data files' names) |
| `runtime` | `Engines` (the keyboards over the sessions, made on first use; `invalidate()` has them all made anew; a setting, the sessions alone, the decoders' look-ahead kept while the user model's counts are as they were when it was made, `UserModel.countsVersion`), its parts: `SessionFactory` (each input method's session as the settings have it; one `ChineseLanguage` per user model and one list of coarse words per set of words added by hand, shared by the sessions made between two invalidates), `FieldContext` (the field's context, flags and warm-ups: each a `WarmUp`, named steps in order handed to `warmUps` as one task, so the platform decides the thread), `LearningFacade` (the one holder of `learn.log`, opened at first use: backups, stashes, the user's acts, imports, U-93), `LexiconFacade` (the words as the settings show them); `EnginesHost` (the `EngineHost` port on `Engines` alone, for tests and the replay), `UserWord`, `EngineAssets`, `EngineSettings` (+ `ModelLevel`), `TableRegistry`, `InBackground` (work handed to `warmUps`, waited on by who needs it), `UserLexicon` (the user model and the lists; its parts `UserAdditions`, the words added from the user's dictionaries, and `WordImport`, another input method's list read in), `EnglishLexicon` (the English page's words, read from `learn.log`), `Keyboard` (the addon's event numbers) |

## What voice input gets from the engine

- **Hotwords**: 我的词 alone, the user's added and learned words, not one committed only once
  (`VoiceHotwords.ofUser`: added at 5, learned at 1.5, blocked none, 3000 at most), bias the
  recognizer; none of theirs while U-93's 「用我的输入习惯」 is off (`habits`).
- **Blocking**: a blocked word is kept out of a result (`VoiceBlocking`), found by the model's word
  boundaries (`Engines.wordBoundaries`, `TextWords.boundaries` with `splitLong`).
- **生僻字屏蔽**: a hypothesis with a character held back is passed over for the next without
  (`VoiceChoice`; all with one, the first as the recognizer wrote it); a user word with one is
  neither a hotword nor a homophone's target (`Engines.rareHeld`).
- **Reranking**: the recognizer's hypotheses are rescored by the word model (`Engines.logProbs`,
  `VoiceRerank`).
- **Homophones** (dev/design/063-voice-model-replace): on the stretch reranking chose, before the polish, a
  stretch reading exactly as a user's word (toned, char by char, `CharReadings`/`ToneTable`) may become it
  (徐静→徐婧; `VoiceHomophones`): added words, learned ones typed more than 3 times. Never the user's own
  word, one across a common word's edge (unigram log10 P ≥ -5.5: 和瑞 of 和瑞典), nor inside a longer
  dictionary word (徐静 of 徐静蕾), nor holding a dictionary word read otherwise (那个 of 是那个, not the
  user's 是哪个). Where they allow, the word model decides (`VoiceHomophones.pick`): each way of writing it
  (each stretch alone, then those not overlapping together, 16 at most) is scored log10 P after the stretch
  before it plus a bonus per word put in, added 1.5 and learned 1, and the best wins over the stretch as
  heard only if higher (公式→攻势 after 敌人的, not after 推导这个). Added words marked 「总是换」
  (`UserWord.voiceAlways`, the file `words.voice-always` beside `words.added`, a text a line) go in unasked.
  None that makes a blocked word. One engine call a stretch (`Engines.voicePick`: the gates and the scores),
  none where nothing reads alike; null or late (1 s), the stretch as chosen. What shows while a stretch is
  said is picked the same way but never waited for (`VoiceChoice.Live`): each place is answered once for
  the same four characters each side and the word, a text with a place not answered shows those known
  (the place's last answer this stretch meanwhile), one ask runs beside the decoding at a time, for the
  latest text only, and is shown only if that text still shows; none asked after a failure in the talk.
  The stretch, closed, is picked anew (`VoiceChoice.Talk`). The polish sees the user's words. Marks go
  with a deleted word and come back with its undo; one left by an older build's delete is dropped at
  start, so the word added again is not marked.
- **Asked, then remembered** (0.6.1, dev/design/061-voice-learn-from-edits.md): `VoiceEdits` (ime-core
  `input/voice/`, pure; `engine/runtime/VoiceEditsGlue` binds it to `Engines`) follows the text of one talk,
  told whole by `Engines.voiceSaid(text, before, hinted)` once it is in, with the field's text before the
  cursor then; only where the app asks (its switch on, a field that learns), else it calls `voiceEditsEnd`
  and the engine never sees voice's text (review S6). A window, one per talk: Backspaces the app takes
  (`EngineEvent.BACKSPACE` not handled) and commits of pinyin, 双拼, 九键 and handwriting not picked from
  联想 (r13 U-30; tables, English, 联想, paste, and any commit with a character not Chinese, a mark or a
  digit, end it, Q5) are followed by cells, a pinyin commit with the syllables typed for it
  (`CommitRecorder`'s record, `VoiceEditsGlue.readingOf`; review S2); what goes in otherwise is reported by
  the app (`voicePutIn`), and the context (`Engines.context`) ends it where what happened is not sure (the
  field cleared, the cursor elsewhere, text before not as expected). A selection of the window is a gap
  that what is typed next replaces, as Backspaces make one. A stretch of 2+ characters deleted whole and
  typed again, the same length, each character sharing a toned reading with the one heard
  (`EngineAssets.voiceReadings`; Q2), is asked about once a window (Q6) only if voice's own homophones, the
  word added as the settings add it (one this window added at the place left out, review M4), would put
  it in at that place, judged by `voicePick` on the stretch voice judged after the one kept before it (the talk's stretches as
  kept, told by `Engines.voiceStretches` at its end; the clause after what is before it where none is found,
  as the polish wrote it otherwise) (`Words.takes`; so a
  word blocked, inside a longer word or across a common one's edge is not; a common word is). Where only
  「总是换」 would (the word model does not take it there; `Takes.IF_ALWAYS`), or a word added already is
  corrected again where voice left it (whatever the model would say now), it asks 「以后总是把『公式』写成『攻势』？」 (`Ask.always`):
  taken, the word is added if not yet and marked (`UserLexicon.setVoiceAlways`), its 撤销 unmarks it and
  takes it off only if the ask added it (`Ask.added`); a word marked already is not asked about. A sentence's mark
  deleted with it, the stretch is judged once its Chinese is typed again (review S5). A word asked about
  is not asked again that window: one typed at the place after it is, but the first typed back again is
  not (Q6). A syllable typed that none of the character's readings in the tables has asks nothing (voice
  could not hear what would be added as typed). One character typed
  again is asked about where a learned word of the user's covers it (§3 (a)); else it says once how to
  have it remembered (`Ask.hint`, Q3; `hinted`, the app's word that it was said, stops the looking, S7).
  The user's words are read and voice's table built once an edit (S7). `voiceConfirm(before, after)` reads
  the field again before the notice. `voiceRemember` adds it by hand (`LexiconFacade.addWord`), then takes
  off one this window added at the place (review M4/S8, `UserLexicon.unAdd`); `voiceForget` (its 撤销) takes
  it off the added words only, nothing forgotten in the learning record (review R1), typed again learned as
  if never added (S3). Nothing is followed while U-93 is off (turning it off ends the window, S4), a commit
  is not learned from, or input finished (`voiceEditsEnd`). Memory only, the last 256 characters.

## Pinyin, from keys to candidates

1. **Segment.** A `Segmenter` turns the input into a `SyllableGraph`: nodes are positions, each edge
   carries every syllable that stretch may stand for, with `SyllableMatches` flags saying how it
   strays (fuzzy, typo, slipped onto a neighbouring key, initial only = 简拼, partial = still being
   typed, a Latin letter, raw). Unlike libime, every cut is kept and the language model chooses.
   `PinyinSegmenter` (全拼), `ShuangpinSegmenter` (双拼, same graph; where a key and the next make a
   syllable it reads the key alone too, an initial flagged `SyllableMatches.ABBREV`: 简拼 in the
   middle, as Rime's `abbrev`, at `Penalties.abbrev` (−2) on top of the initial's −1, the least that
   left input in whole syllables reading as before, dev/design/061-shuangpin-abbrev-measure.md;
   over such a key, there or before on the path, the decoder offers only the words the user typed
   or chose, `UserWords.typed`: a count, which the learning record's 30 typing days give,
   `chosen`, or pinned under any keys (`LearnState.pins`, `UserModel.setPinned`: typed while pinned); of the dictionary, the merged ones and the imported alike, and none with no user
   model or the habits off. `UserModel.typedBelow` keeps the dictionary's and the merged file's
   nodes to them (`TypedNodes`), at each reading a word was typed at: a word that comes has its nodes
   marked there and then, and all are marked anew when asked after one went (a count to 0, a forget,
   an unpin), not on the key after each new word's first commit;
   `LatticeBuilder.step` leaves the other nodes be, and looks no word ahead over such a key. A key read
   as its neighbour is no key read alone (`Path.alone` counts the first, `Path.abbreviated` both): a
   slip reads the whole dictionary. The user's decision (DECISIONS.md 「0.6.1
   双拼中间简拼改 U」, dev/design/061-shuangpin-abbrev-research.md): every word behind a score of
   syllables a key doubled a key's time on a phone;
   `ime-eval pinyin --sp-abbrev off` reads without it, `--history <words.txt>` types words before
   the run (`TypedHistory`), without which a run reads no key alone; what asks how the user typed reads the
   graph without them nor slips onto a neighbouring key, `SyllableGraph.withoutOtherReadings`: whether the input ends on a syllable
   (`endsOnSyllable`), whether `;` ends one (`readsAfter`), and whether a code `readsWhole` (`Retype` reads a code
   so as whole syllables: on purpose, a word typed before read there by keys alone, second, is learned when picked
   as any pick, but a delete and the same keys again is no correction to it). The words a key alone reads are placed by `HabitOrder` and `HabitRanker` (`Tier.ALONE`,
   dev/design/061-sp-abbrev.md §3.3): one word typed, read exactly as whole syllables then two or more keys alone
   (`HabitOrder.aloneShape`), goes first where the code reads no whole syllables to its end (`ufmuh`, `umuh`,
   `x'm`) and right after the first where it does (`ky` kuai, `xm` xian), or where a word typed reads whole
   syllables then the last key alone (`yr|y` with 原因 typed: the gate, §3.4, decided once a decode over the
   start's words typed, `HabitOrder.gate`, not by the candidates read so far, so a page further on never swaps the
   first two); up to three, the pins and the first learned here before them, `FLOORED` still second (after the
   first of them, or before them all where the code reads whole), never `LIFTED`, and none past the three lifted
   either (`spellings` leaves a word read alone where it is); such a word takes a slot of the order though
   `ranksHere` fails (its reading has no slip). The review's M3 (no word typed under a code read whole) is gone.
   `GroupedShuangpinSegmenter` reads the keys alone too (§3.1), `T9Segmenter` (digits; with 连按
   on, off by default, a key pressed again soon is its next letter and nothing else, `MultiTap`: the
   session puts it in as a letter typed as itself, upper case, in place of the digit, so `4 4` fast
   is `H`. The 2026-10-12 hybrid, read as keys too with a `Penalties.tapped`, went 2026-10-19: a
   learned 广告 overrode the h the key's preview showed). The 14 keys (拼音 14 键) are the same
   segmenter over a grouped layout: see [The 14 keys](#the-14-keys-grouped-keys) below.

   **Keys tapped off-centre** (纠正错拼和按偏的键; dev/design/061-neighbour-correction.md). A typing key's
   tap carries a `Touch` (pinyin/Touch.kt): where it fell across the key (`dx`, `dy`, ±0.5 its edges),
   the key's size in dp and the keys around it (`Near`: what each types, its centre and size in the
   tapped key's units). Where it fell is where the finger came down (`KeyGesture.fellAt`/`fellDown`),
   for a tap that slid past the slop or out of the key too (the 14 keys' place on the key, `clickAt`,
   stays NaN for those). The app measures it (`withTouch`, TypingKeys.kt, for the keyboards that are `TypingKeys`, from the keys drawn, by
   `KeyBoxes.touch`: the next keys in the row and those of the rows above and below overlapping it; a key over two rows (a layout's rows="2") is in both rows;
   a key on both halves of a split keyboard is the copy nearest), `HostKeyAction`/`SymAction` carry
   it, the host passes it on as the key's payload (`Action.Key.touch`), `PressedKeys` keeps it by
   place and `Segmenter.segment(input, taps, touches)` reads it. `SlipCost.of` scores reading a key as
   one around it: the log of how much likelier the tap is under a finger aimed at it than at the key
   hit (round Gaussians of σ 10 dp), never above 0, −2 more where the keys as typed spell a syllable
   too (a legal slip, `ta` near `y`), none below a floor of each keyboard's. −3 on the nine keys' digits (pinyin's and
   双拼's): below it they cost the 6T more than 061-neighbour-correction §7.2 allows, below. The rest by the user's
   2026-11-16 rule, the loosest floor that turns no sentence typed in the keys' middle (σ 0.15, both halves × seeds
   1–3) wrong (061-neighbour-18key-impl-report §5): 全拼 −1.75 (`PinyinSegmenter.SLIP`) and 大千 −1.75, with tones or
   without, so neither reads a legal slip; 双拼 on the full keyboard −3.75 (`ShuangpinSegmenter.SLIP`); the pinyin
   18 keys −4; the pinyin 14 keys −6; 双拼's 18 and 14 keys −5 (to −6 none turned wrong, but past −5 a key on the
   18 keys took a sixth more arcs, over the tenth allowed: `NeighbourKeyWorkTest`); 注音九键 −2. So a tap in a key's middle reads as typed and
   one at its edge reads the neighbour too. 全拼 (`PinyinSegmenter`: `SpellingIndex` has every
   one-letter substitution, the segmenter keeps those the touches allow; never a syllable still
   growing at the end of the input, nor consonants alone where tapped in the middle, 简拼 being
   read), 双拼 on the full keyboard (`ShuangpinSegmenter`), 九键 (`T9Segmenter`, 1 and 0 no
   neighbours) and the pinyin 14/18 keys (`T9Segmenter` over the groups, a tap put onto the key
   meant at its edge nearest the key hit, its letters weighed by where it lies there, the 18 keys' too
   since 061-neighbour-18key, the user's 2026-11-16), 双拼's 18, 14 and nine keys
   (`GroupedShuangpinSegmenter`, by the same groups) and 大千 (`T9Segmenter` over `ZhuyinKeys.DACHEN`, a symbol
   key's neighbours the symbol keys around it, a tone key none) read slips (061-neighbour-groups), and 注音九键
   since the user's 2026-11-16 (`T9Segmenter` over `ZhuyinKeys.NINE`, a group key's neighbours the group keys
   around it, floor -2: past it every slip that also spells as typed, so it reads only where the keys spell
   nothing; the loosest that turns no sentence typed in the keys' middle (σ 0.15) wrong, with tones or without,
   where -2.5 turned one with no tones; 061-neighbour-18key-impl-report). A key with no place (a hardware keyboard, TalkBack's double tap): 全拼 reads a
   slip only where the keys spell nothing (the 0.6.0 rule, `SlipCost.fixed`) and never consonants
   alone; the others none. The decoder adds the slip's cost in the arc's score where it scores the syllable (`step`,
   `startWords`), so `Lattice.same` sees it and a kept lattice is dropped where it changed; an arc over
   a slip is marked `SLIPPED`, apart from one over a key alone (`ALONE`; `Lattice.mark`: `Path.abbreviated` past
   `Path.alone`), and what reads a code read whole leaves out only the slips (`takes()` where `holdsSlips`, as
   `withoutSlips` does in the graph: a key alone is the user's 简拼, 061-sp-abbrev §3.6); a code shows the syllable as meant (`Reading.slips`), and a word read
   through a slip is learned under the keys meant (`CommitRecorder`, `PressedKeys.keys(…, slips)`),
   so what the keys typed read as typed is not raised by it (user's N1-A).
2. **Decode.** `PinyinDecoder` puts every dictionary word along every path into a lattice (`LatticeBuilder`) and beam
   searches it (`BeamSearch`), scoring each step by `WordScorer` (the n-gram model, through `UserScorer` with the
   user's counts added; a word of the user's whose text the model has, added by hand or typed at a
   reading of the user's, is that model word to it (one only imported, from a dictionary or a pack,
   is the model's unknown word, plus its pack score, till picked), scored and as context, in every input
   method: `UserModel.modelWord`; one test, `UserModel.chosen` (added by hand, or owned: the user's word in the learning record), says whether a word is the user's, for
   its score, its layer's prior, the context of 联想 and the words 联想 offers after it, and the
   reading handwriting takes it at) plus the `Penalties` of how the input was read. It keeps whole sequences
   (a real N-best, not trigram-merged), and reuses the beams of the unchanged prefix so a key costs
   only its last syllables; the words collected from a position whose collecting read no edge where
   the graph changed (`SyllableGraph.sameUntil`) are kept too, which 双拼's 简拼 in the middle needs:
   every key read alone made collecting from every position grow a key's time with the input. Of a
   position whose collecting did read there, the arcs ending before it are kept and only the steps
   that crossed it are taken again (`collectOn`, from the steps `collectFrom` and it keep a
   position: those onto where the graph changed or past it, at least the last key; not while a tone
   is typed, a word's tones being asked of the whole path).
   The first words keep their scores and order while the arcs from the start are the last decode's
   and none reads to the end.
   `PinyinDecoder.work` counts what a decode took (arcs; steps, which guard `collectOn`; words scored
   in the search; first words scored anew, which guard the scores kept), the same on any
   machine: `ShuangpinKeyWorkTest` (`:lib:ime-eval:uatTest`) holds a 小鹤 key on the app's data
   under ceilings by it, and each key's reading to a decoder's made afresh; `NeighbourKeyWorkTest`
   holds the keys read by where they were tapped (full pinyin, 小鹤, 九键, 14 keys, 双拼's 18, 14 and nine keys,
   大千, clean taps) to a tenth over the same input with no place (061-neighbour-correction §7.2; it was 3.5% at most; 小鹤
   with the 3000 likeliest words typed, 8% of the arcs: with none typed a key takes so little that
   the slips' two hundred arcs are 12% of it).
3. **Rank.** At `ModelLevel.MEDIUM` and up, `Reranker` adds the 4M sentence model's score of the
   decoder's best whole-input readings (after the text before the cursor) to the decoder's score;
   at `HIGH`, while the user pauses, the 25M model refines the top readings in time slices
   (`SentenceRefiner`, `Action.Refine`). The pause's weighing goes on from work slices between keys
   already did (the scorer keeps it) and stops after `SLICES` slices, so tap pace can change a long
   input's result: typed fast, nothing was done between keys and the cap gives up where slow typing
   finishes (parity r2-7560, pinned by `TapPaceUatTest`). Kept by user decision (DECISIONS.md
   2026-10-25) over a cost limit that would refine fewer long inputs. Adding to, not replacing, the decoder's score was the
   measured choice (replacing lost 2.2 points).
4. **Present.** `PinyinSession` lists the readings of all the input, then words the start may be;
   picking a partial one keeps it and reads the rest after it. Committed text becomes the context
   of what follows; `Predictor` offers next words (联想) until a key is typed. The input keeps a
   cursor (`Action.Cursor` from a tap on the preedit, `Action.Step` from an arrow): keys go in
   and backspace deletes there, and the input is read again whole, as if typed so (the decoder
   searches again from the first syllable that changed). A piece picked whose keys that changes is
   dropped; `PreeditPlaces` says where each place of the preedit is in the input, syllables
   spaced or spelt out (双拼); a step passes the input places no preedit place is (a trailing `'`). On the nine keys a syllable taken puts the cursor past it if it was in
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
   there (不再联想) never. A whole commit of 3 to 12 letters (`learn/Phrases`) is counted as a phrase
   (`LearnState.phrases`, snapshot row `PhraseRow`, type 49; a 联想 that went on with one counts it through
   `Commit.continued`, appended); not one of Chinese numerals alone (`Phrases.NUMERALS`: 一二三, 幺三八,
   贰仟伍佰; a number put in as words, user 2026-11-02), one with other words in it is (三个人). Used whole 3
   times, the rest of it (2 characters or more) is offered first
   after the text before ends its start from a word's start, covering the last word put in
   (`user/Continuations`, kept in `UserModel.continuations` by `HabitCounts`; U-73): at most 2, in front of
   the words but a word the user put first there; two phrases of the same rest, once (the one used most).
   After a word the model has not (the user's new word, a rare one: `prev` `NO_WORD`), the phrases alone,
   filed after the text put in last (`Predictions` keeps it while the text before ends with it; pinyin its
   own word), so a pick of one is learned. A backup (`LearnBackup.backedUp`) leaves out a phrase used once
   (user 2026-11-02, C54): most whole commits are never put in again, and the tables and handwriting kept no
   sentence before (S8), so a file the user hands around holds only those used again; the phone's snapshot
   keeps them, as the count must reach 3 across days and a fold keeps only the snapshot (the tail is the
   last hour's commits), and a merge leaves the phone's own as they were. 退回恢复前's copy
   (`BackupHead.BEFORE_RESTORE`) keeps them: the phone's own file, never listed nor in the settings'
   zip (`Backups.DIR` is left out), laid over the learning whole by a revert. The settings' zip where
   the engine gave no backup (`UserDataManager.export` with no `learning`: the record unreadable, say)
   carries `learn.log` itself, all of it: kept so as not to lose the learning, as a parse of the file
   would fail as the engine's did. Only the start is looked at, not what is before it (C51):
   a sentence never typed that starts with the same word gets the phrase first, in place of the word it
   wanted. Measured by `predict-learn --continuations on|off` (top1/top5 on held-out sentences after the tune
   half is typed three times): pinyin-chat 12.9/28.5% → 6.1/25.3%, pinyin 20.4/40.9% → 14.0/37.1%; the
   sentences typed, 36.4/62.0% → 55.8/74.8% and 58.9/83.8% → 73.6/90.2%. The user kept it first (D-C3,
   2026-11-02) at that cost. Turned off everywhere as `NoPredict("", phrase)`; deleted as a
   `PhraseForget` (event type 16), its count alone: a word of the same text (added by hand, imported, a
   table's saved, learned) is not touched, nor do the tables forget anything, so the undo chip takes it
   back (a `Forget` would take the words with it, past an undo). A word's `Forget` leaves the phrases (C52). Where nothing is learned (`Session.learning` false) the habits still rank, after no
   word, and nothing is recorded. A pinned sentence the model has not as one word is put in as
   the fewest of its words. `Session.why` (为什么排在这, U-81) says a candidate's tier and uses
   under the code of the level that ranked it, a prediction's under its `pred:` code; a candidate
   no level ranked says nothing, and its menu has no WHY. impl/design-1b.md in
   `dev/design/learning-ux/` has the rules.

### Kept in sentences, taken back when deleted (U-08, U-03)

- **Kept in sentences** (`session/Enforced`, from `HabitOrder.enforce`): pins and the user's firsts
  of two or more characters (a first only after its word), and a word added by hand that is the only
  word over its keys (`PinyinDecoder.arcsBetween`; where those keys read whole, 双拼's arcs over a key read alone
  are not counted, so a word typed so read leaves the added one alone there), are put into the decoder's sentences where the
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
- **Keys read alone** (双拼, all four keyboards, dev/design/061-sp-abbrev.md §3.5, §3.9). An arc carries
  `ALONE` where its path reads a key alone and `SLIPPED` where it reads a slip too (`Lattice.mark`); `takes()`
  holds back only `SLIPPED` under a code read whole. The words from the start are the best `DEFAULT_WORDS` (64) by
  score and, where the graph reads keys alone, up to `TYPED_SLOTS` (8) more of the words typed read with no slip
  (`rankedWords`): on the grouped keyboards a word typed is otherwise not among the 64. The slots stay right after
  the first 64 whatever more is asked, so a list read further (`PinyinSession.readUntil`) begins with the one before. Over a key alone the
  decoder walks the dictionary node's children that have a word typed below (`LatticeBuilder.collectTyped`,
  `UserWords.nextTypedBelow`, `TypedNodes.nextBelow`) instead of looking up each of the key's 20–60 syllables;
  `Reading.matched` walks a node's children the same way where they are few (`WALKED_PER_MATCH`). The lattice is
  the same either way (`PinyinDecoderTypedTest`, `ReadingSlipsTest`).
- **Across one keyboard's spellings** (U-20, D32 B; the user's rule in docs/pinyin.md 完整拼音和简拼).
  Not in the scores but in the ranking (`HabitRanker.spellings`): `SpelledForm` tells a code's keys
  as the full spelling, the 简拼 (a key a syllable) or a part of a word of two syllables or more, by
  their count alone (fewer keys than syllables: none). A word used ≥2 times spelt in full on the same
  keyboard (`Kinds.keyboard`: 连按 is the nine keys') and not under the code goes up to third under
  its 简拼 or a part (fourth behind a pin, after any floored word; one already in the first three stays);
  one corrected to under a 简拼 is no lower than second spelt in full or in part (right after a pin),
  the floor before the lift. A word moved so, or held at its place so, shows `Tier.FLOORED` or `Tier.LIFTED` (为什么排在这, and `kept` holds it
  in a sentence's first page). Counted from the STAT rows as they are: `LearnState` keeps them by word
  and, for full and 简拼 codes, by keyboard and first key (`SpelledForm.filedUnder`: the nine keys by
  the reading's first letter, so 连按's letters and digits meet), so nothing is rebuilt after a commit;
  no record of its own, no format change. `HabitOrder` walks in such words the decoder lacks, where
  `SpelledForm.spells` says the keys start each syllable in turn.
  Measured (2026-10-24, this machine's JVM, `ime-eval learn-log-bench`, 200,000 commits over 365
  days): a key typed with the record 0.81 ms before and after (v0.3.2: 0.81); the words of one first
  key and their counts, as the first key after a commit finds them, 0.13 ms (an index rebuilt per
  commit, as first written, was 2.2 ms; both timed as that day's bench did, `spelledFrom` then
  `spelled` per word: it now times `spelledFrom(...).size`, so a rerun is not the same figure). The two indexes hold only rows of words of two syllables or
  more spelt in full or by 简拼: on the bench, where every row is one, about 1 MB of heap for 4,865
  rows (state 10.7 MB with them, 9.7 MB without). `ime-eval learn` held-out was unchanged by the rule (90.3 / 91.1 / 90.7 before and after),
  as it reads only the first, which this never moves; learn-ux `spelled.txt` passes 9 of 9.
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
  with `guess` (the app's: `HostApi.pinyinsOf`, i.e. `EngineAssets.pinyinsOf` with a stray first, as the user
  dictionary's import reads words alone) a line that is a word alone gets the reading guessed for it,
  `GUESS_BATCH` words a call, and one it has none for is skipped (`Detected.unguessed`, said in the dialog, not
  held to the 2%; a batch whose guess throws, but for a cancel, is skipped so too). A GB18030 file whose words
  alone the guess reads fewer than `AGREE` of is `MISMATCH`, and with a guess too many lines of no word are
  `UNREADABLE`, not `NO_PINYIN`. The app guesses only for a file that may go into the base, its wait on the
  host stopped by Cancel or the page going and given up after 15 s (`PinyinDictionaryFragment.guessing`);
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
  (`WordImport.importWords`, `UserLexicon.wordImport`; the user's own export, `WordListFiles.exportMark` with `old`, is added
  as before) is a batch in the learning record (`ImportBatch`/`Imported`/`ImportUndo`, rows
  `BatchRow`/`ImportedRow`; `ImportedWords` in `LearnState.imports`, `plan` orders a batch by the
  file's count, `ImportFiles.frequency`, a .scel's by its own via `ImportFiles.Scel`; a list's word
  the dictionary has in any reading, or a line of no Chinese, is not taken). `ImportedWords` keeps
  them packed (text, syllables, batch and place in arrays; a place never reused, a word taken out
  only marked gone, so 撤销 puts the same places back from the record's own bytes), one instance
  shared by `LearnState` and `UserModel` (`attachImports`, from `HabitCounts.attach`, so a model made
  again has them). A rewrite of the record (an undo, a clear, a restore) makes them anew, their places
  numbered again: the model is made again, and the sessions (`LearningFacade.rewriteLearning`, then `Engines.invalidate`; review-G M1). A
  restore (`BackupMerge`) puts every batch first, the phone's of an id before the backup's (renumbered
  unless the same file, day and count), then the phone's words, then the backup's: a word in both is
  the phone's batch's (review-G S10). U-86's 全部清空 drops the batches with the rest. Their ids are `UserModel`'s past `vocabulary.size + 2^28`, one per place; they are
  not in its trie but in `ImportTrie` (`UserWords.startWords`, packed arrays, made again when the
  imports change), which `LatticeBuilder.collectStart` walks from the lattice's start alone: arcs
  flagged `START_ONLY`, which `BeamSearch.extend` skips, so such a word is never inside a sentence or after a
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
  as likely, and its candidate then shows the right reading with tones when the typed letters fit
  a misreading and not the reading, syllable by syllable, each by a start of it, the last ones
  maybe untyped (`xxxuez` → 莘莘学子 shēn shēn xué zǐ; `shu` for 说服 none). A label only, in
  `hint()`: it ranks nothing; the misread entry itself is what offers the word.
- **Tones** (`engine/reading/`, the tones sections of `pinyin.data`, `ToneTable`): each character
  read as a syllable has a usual tone and the tones it may have (Unihan's, the likeliest list first;
  a character alone in `lexicon/tones.tsv` sets its usual one, 谁 shéi); a word whose tones are not
  its characters' usual ones (爱好 ài hào, ~4,800 of them) has its own, kept by word id and reading.
  Later sources win: phrase-pinyin-data, then `tones.tsv`, then `misreadings.tsv`'s readings. Words of
  the dictionary and phrase-pinyin-data go through `ToneRules`: a word no source lists takes the tones of
  the longest listed word it ends with; a final 为 is wéi (but 因为). `tones.tsv` is curated and taken as
  written. Then no word keeps a 轻声 (the user, 2026-11-02: every reading in its characters' own tones):
  a syllable any source reads neutral takes its character's usual tone (东西 dōng xī, 桌子 zhuō zǐ,
  记得 jì dé), unless that is the neutral one too (的, 吗, 们: no other to take). 一 and 不 are always yī
  and bù (no sandhi). `ReadingTable` is the interface (one table
  now, `mainland`), `Zhuyin` the syllables in zhuyin, `ToneMark` the written forms (hǎo, ㄏㄠˇ, ˙ㄗ).
  `ime-eval readings` measures it on `lib/ime-eval/data/readings/chat.tsv` (500 hand-checked words; a
  release needs 98%), read by text or, `--typed`, by the syllables the list gives. That set is the test
  set: nothing in `tones.tsv` is taken from it. The sets are simplified text, so the measure reads them
  without the app's traditional-to-simplified step. A syllable the list reads neutral is right in any
  tone of it: which tone is the character's own is the data's to say.
  候选词注音 (`EngineSettings.candidateReading`, off by default) puts `readings` on each `Snapshot` and
  `Choice`: one string a code point, by `CandidateReadings`, from `Session.readingOf` (pinyin's words as
  typed) or else the text. A character whose tone is not known is its syllable toneless in pinyin and
  left empty in zhuyin (where it would read as the first tone); read by its text, a character takes a
  dictionary syllable of known tone first; with readings on in the pinyin style, every cell keeps the
  reading line above, so one without a reading (an emoji, English) sits on its neighbours' baseline; the
  zhuyin style, drawn to the right, adds no line, and a character left without one is on the same baseline. Engines adds them for every input method but English; pinyin's and 双拼's
  hints (all readings) go while it is on. The host keeps them only while the shown text (traditional)
  has a code point for each; the bar is taller for pinyin (`CandidateBarHeight.dp(textSp, style)`).
  While it is off, those hints become `readings` instead (`CandidateReadings.ofHint`: a misreading's
  toned syllables as they are, a part's or stroke lookup's toneless one toned by the character's table;
  a polyphone's several, `xing/hang`, as one: `ordered(cp, Session.lookedUp)`'s first, A5; on or off alike),
  their hint emptied; the views draw them as pinyin above in the bar's own height (`ReadingSpan`'s hint
  mode, sized by `CandidateBarHeight.hintSize`, floor `HINT_FLOOR_SP`), the baseline unmoved. An emoji's
  or a table's hint, no syllable a Han character, stays a hint.
  Handwriting's candidates of one Han character (not what may follow) show all its readings instead
  (`CandidateReadings.allOf`): the first `MAX_SHOWN` (2) of `ordered(cp, before)` joined by `SEPARATOR`
  (`/`), `MORE` (`…`) after them for more; `ordered` is `PinyinData.charReadings`' order (`CharReadings`,
  compiled by `CharReadingsBuilder`), the reading of the word the text before makes with the character
  first (`Predictor.reading`: 银 then 行, háng); a traditional character by its simplified one's. The bar
  draws them by `ReadingSpan` (pinyin smaller to `CandidateBarHeight.readingsSize`'s 7 sp floor, then the
  candidate wider; zhuyin a column a reading). A settings change that touches only `candidateReading`
  keeps the sessions (`Engines.settings`); the host then sends `Keyboard.RECALL` (`HostCore.configure`)
  so what is composing is read again, and the app makes its views anew with `ViewRebuild.READING`, which
  does not end the composition.
  A held candidate's card (`Engines.card(im, index, text, paged, shown)`, a `CandidateCard`; the text checked
  against the candidate as the learning menu checks it, a traditional shown 銀行 taken for the engine's 银行
  and its characters shown): `Looked`, a character's full card at once for a candidate the session
  says was looked up (`Session.lookedUp`: handwriting's characters, full pinyin's `u` parts and stroke
  lookup, not what was typed nor what may follow; `paged`, the index from the page shown), its readings
  in `ordered(card = true)`'s order with up to two words each (`CharReadings`: a dictionary's words only,
  phrase-pinyin-data's, `lexicon/tones.tsv`'s or `misreadings.tsv`'s, the commonest in the model; the card's alone, the
  dictionaries' neutral tones the candidates leave out, 和 huo, flagged `CARD_ONLY` and after the rest; a
  traditional character's words its simplified one's, `readFrom`), and `engine/charinfo.data`'s radical, strokes and parts (`CharInfo`), the
  parts' readings by `CandidateReadings`, the stroke order the mainland's (`CharInfo.Entry.order`,
  `lexicon/stroke-order.tsv`'s, each stroke a `StrokeShape`: 行 丿丿丨一一亅, TalkBack reading each shape's name) or,
  for a character without, the stroke table's (`Strokes`) of Unihan's count, else its first (the table's orders
  sorted, not rime-stroke's own order; the five kinds only, a 折 `StrokeShape.ZHE`), the strokes counted as the
  order shown is (Unihan's count may be Taiwan's glyph's: 范 9 beside the mainland's 8); what is
  not known is null. Any other candidate with a Han character in it: a compact line of its reading and,
  a tap away, `Char` (one character: `CandidateReadings.lineOf`, háng / xíng…, and its card) or `Word`
  (two to `WordCard.MAX_CHARS` = 8 Han characters: `WordCard`, the word in pinyin and zhuyin, a row each
  Han character with its radical and strokes); more than 8, `Line`, the reading alone. The readings are the
  bar's: `Engines.readingsOf` takes the same two steps as `readingAndHint`, sharing its `above` (a hint
  that is a reading, drawn above it) and `read` (else the text as the session typed it), in both styles whatever 候选词注音 is; the line in its style (off: zhuyin on the zhuyin
  keyboards, else pinyin). A word's row reads `CardReading` (syllable and tone, null tone where none is
  known); `Engines.charCardOf(char, reading)`, stateless, makes that character's card with the reading first
  (`CharCard.of(first = …)`, `Row.inWord`; prepended when the dictionary's list has no such reading).
  Emoji, English, quick input's entries: none. The app shows it as the held candidate's menu head
  (`CardNav`, `CompactLineView`, `WordCardView`, `CharCardView`, laid out by `CharCardLayout`).
- **Strokes.** `u` with nothing typed starts `StrokeLookup` (`h s p n z` = 横竖撇捺折); any other
  letter turns it back into pinyin (`usb`, or `uan` as a slip for `yan`), typed at the strokes'
  cursor, which they keep as pinyin keeps its own.
- **笔画筛选.** `StrokeFilter` wraps every pinyin and 双拼 session outermost (`SessionFactory.make`,
  `InputMethodCatalog.filtersByStrokes`), over its glide too (`Engines.glide` reads through it). A
  stroke drawn on the keyboard comes as `Keyboard.strokeKey` (0xF0110 up, `Keyboard.STROKES`, one
  per `h s p n z`; `KeyRouter` swallows it in any other input method and a temporary mode, so it
  is never typed), `Action.Stroke` to the session. While something is typed it keeps, in the
  inner session's order, the candidates whose first character starts so: in the mainland's order
  where `CharInfo.orderKeys` has one (lexicon/stroke-order.tsv, the card's; rime-stroke's other orders
  are mostly Taiwan's and start otherwise for 473 of those 6940, 艳 丿一一丨: ST-120; compared in place,
  `CharInfo.orderStartsWith`), else, for the ones it lacks (some, most of them traditional or rare), in any of
  rime-stroke's (`Strokes.startsWith`, a sorted char → entries index made on first use); an emoji or mark goes
  as the one before it, a Latin word goes. The next stroke is that character's next. Then `singles`
  (首页至少几个单字, `EngineSettings.strokeSingles`, 0–5, 2 by default) of the kept characters alone (one
  ideographic code point, an emoji after one going with it) are put right after the kept first, the
  words they passed after them in their order; a first that is one alone does not count. `inOrder`
  is the kept list as filtered, `keptAt` the order shown, indices mapped through it. It looks
  through the inner candidates 32 at a time as a page asks, at most `LOOK_AHEAD` (2000); indices
  (`Select`, `Pick`, `Forget`, `Pin`, `offers`, `why`) are mapped back to the inner session's.
  Backspace takes the last stroke, then the pinyin; a pick or a key it reads ends the filter and is
  the inner session's (a partial pick leaves the rest typed, no strokes). A key that ends the input
  (`Session.endsInput`: punctuation, the nine keys' 0, 双拼's `;` where its `readsAfter` refuses it)
  first picks the kept first candidate, then goes to the inner session; if that pick read only part
  of the pinyin, the rest stays typed and the key is swallowed (`Keyboard` keeps a key not read
  handled while something is still composing, so the app never types it before the rest). Nothing kept leaves the bar
  empty and Backspace takes the stroke back. The preedit is the inner one, a space, the strokes
  (`Strokes.shown`). `ime-eval strokes <pinyin.data> <stroke.data> <set> [--charinfo <charinfo.data>] [--first-char] [--scheme t9]
  [--rerank <sentence-model> --refine <sentence-model-large>]` times it (`--charinfo`: drawn and filtered by the mainland's orders, as the app; the first character typed by
  the sample's exact reading, samples without one left out, an untimed warm-up first): on
  pinyin-chat.tsv's first characters alone (a syllable's homophones, 1941 samples) a stroke's snapshot
  is 0.05 ms p50 / 0.27 ms p95, the look through to 2000 0.31 / 1.54 ms, first right after 0–4 strokes
  71.8 / 86.3 / 92.0 / 95.1 / 97.1%; on the nine keys 0.06 / 0.42 and 0.73 / 2.12 ms, 61.8 / 78.7 /
  86.3 / 91.3 / 94.6%. With the sentence models the snapshot is the same, first right within 0.6
  points, their refine up to 17 ms once on a long sentence (one desktop core, 2026-11-01).
- **Latin words** in pinyin (`iphone` → iPhone) come from the `latin` layer, spelt in letter
  syllables; full pinyin only, setting `EngineSettings.LATIN_WORDS` (a toggle on full pinyin's page).
  The keyed keyboards (nine keys, 14, 18, own groups; not 注音) take them too: `T9Segmenter`'s
  `latin`/`userLatin` add a `LETTER` edge at each place inside a word the keys could spell
  (`LatinWords.forEachAtKeys`, a trie over the words' keys per keyboard; a fixed letter, `'A'..'Z'`,
  only that letter), every letter of the key a match at 0, no penalty beyond the full keyboard's.
  The preedit and Enter (`PinyinSession.shown`) give such an edge its letter in lower case.
  The tries are built in the field's warm-up (`WarmUp.LATIN_KEYS`: the nine keys' as a nine-key
  field starts, 14, 18 and own groups' with full pinyin's, since which one is typed shows only at a key);
  a few ms each on a desktop core. A keyboard with letters on several keys holds every word by its
  letters' first keys, its other typings only while the trie is under `LatinWords.MAX_KEY_NODES`
  (100 000: each letter on three keys, 375 000 unbounded and 100–165 ms to build, 25–75 ms bounded).

### Long press on a candidate

`Session.offers(index)` says what a long press may do (an op names its candidate by index and text, as shown: a list the host shows converted, 繁體 by chttrans, names it so, and `Engines.learning`'s `shown`, `HostCore.shownText` from `KotlinHost`, matches it to the engine's text for the menu (and the report); the menu answers with the engine's text, and an act must name that, else `Unavailable` (the list changed under the menu to another candidate shown alike). The simplified word is what is blocked, deleted or pinned, and a block or delete takes every candidate of the list shown alike too (国家 and a dictionary's own 國家, either pressed), the last first, each acted on by the host at its index of all with the engine's text, which `HostCore.actOnCandidate` checks against what it shows there (an act sends the list back to its first page: a page's index would name another candidate); undone together, only those done; a pin only the pressed one); `LearningOps.menu` turns that into the
`LearningOp.MenuItem`s and `LearningMenu.rows` into the rows shown: `DELETE` (删除 on the user's or an
imported word, 忘掉学到的 on a dictionary's: what was learned of its text, everywhere), `BLOCK` (屏蔽,
its scope stated first: everywhere, or for a table under the code typed alone; into the block list,
which the settings can undo; it unpins the text everywhere; a sentence of several words, 啊之 at a zhi, is blocked whole as it reads, its text and syllables one entry (`WordLists`, `UserModel.blockedEntries`) that no id stands for, which `PinyinSession.place` leaves out (`UserModel.blocksSentence`), its words offered alone still; `PinyinLookUp` offers pinyin's block, not its pin; an emoji or a character by its parts among pinyin's is blocked as its text in a reading, the word it stands for's or its own (`PinyinSession.blocked`), the emoji left out for that word's reading alone; a pinyin glide's other reading's word as it reads (`GlideSession.blockOther`, a table's under its code); a character of 笔画查字 under the strokes typed alone, filed as a table's block of table `strokes` (`StrokeLookup.BLOCK_TABLE`, `Session.blockScope` says UNDER_CODE for the menu; a table's 拼音反查 says EVERYWHERE, the pinyin word it blocks, where the table's rule would say UNDER_CODE); handwriting's candidate is the last character shown, blocked alone in its likeliest reading, each character of a sentence when it is the last; English's words in a list of their own, `words.en.blocked` in the user directory a word a line as written (`EnglishBlocks`, `UserLexicon.englishBlocks`; the session blocks the data's or user's form of what it shows, The at a sentence's start as the): one written in lower case blocked in any case, one with a capital as written alone (US, not us); apart from pinyin's blocks of a Latin word, each list its own (review L2: the two read text differently, pinyin by reading, English by spelling): never offered, completed, predicted, glided or corrected to, listed among the blocked with no reading (`UserWord(text, "", BLOCKED)`), in a backup as a BLOCKED `ListedWord` with no code, which an older build drops; pinyin's `words.blocked` is unchanged. A long press's act reaches a session through the host's candidate actions (`KeyRouter.actions`), which a snapshot not `actionable` has none of: English's and 笔画查字's are actionable with candidates shown, or their 屏蔽 was dropped on the device while the menu showed it. Handwriting's decoder leaves a blocked character out of words too (`HandwritingDecoder.extend`), or 天人 shows 人 again), `PIN` (固定首位: first under the keys of
its own span, in the learning record; the custom phrases are untouched; not for handwriting), `UNPIN`,
`DELETE_PHRASE` (从自定义词组中删除, on a custom phrase: `PhraseBook.remove`, a built-in one turned off,
no undo), `DELETE_COMMON_WORD` (从常用词中删除, on a 常用词 replacement, the only row it has, learning or
not: `CommonWordBook.remove` takes that replacement off its word, the word gone with its last; no undo), `NO_PREDICT` (不再联想, on a 联想), `WHY` (为什么排在这; not for English, nor a phrase alone). Each has conditions (`PinyinSession.forgettable/pinnable/blockable`), and none
are offered where nothing is learned (a password field, `Session.learning` false).

### The 14 keys (grouped keys)

拼音's 键盘 setting picks the touch keyboard: the full one, or 14 keys of two letters each (iFlytek's
拼音双键: `QW ER TY UI OP / AS DF GH JK L / ZX CV BN M`). The engine does not read the setting.

- **Codes.** A grouped layout is a `NineKeyLetters` preset (`G14`), not made through `normalize`:
  each key is one code character, U+F8DA + its first letter's place (BMP private use past the
  syllables' and `G18`'s; R16 F4356, ⒜–⒵ before: text a user may type). The three grouped blocks are
  `G18` U+F8C0–F8D9, `G14` U+F8DA–F8F3, `NINE_SP` U+F8F4–F8FC (`NineKeyLetters.CODES`; U+F8FC its `;`, 微软's ing, which 1 sends while typing), U+F8FD–F8FF free. The eight-key layouts (STANDARD and the user's 按键字母) keep the digits 2–9 and the
  separator `'1'`, their `tag`, `serialize`, `parse` byte for byte (a contract test). The touch
  nine keys do not send those digits: `Keyboard.pinyinNineKey(digit)` (plane 15, 0xF0400+,
  `Keyboard.PINYIN_NINE`) for 2–9 and for 1 while typing, which `KeyboardPair` routes to the
  nine-keys session. 0 stays a plain `0`.
  `NineKeyLetters.grouped(tag)` gives a grouped preset back from its tag; `parse` reads the eight
  keys alone. What cannot be read is shown and committed by `asTyped`, each code as its key's
  first letter (UI alone is `u`): no code reaches the user's text. `PopupOverrides` (按键字符) takes
  none of them.
- **Sessions.** `InputMethodCatalog.PINYIN_KEYS` (`engine-pinyin#keys`, no fcitx input method; the name
  under which a setting says 「拼音 · 14 键」) is the grouped session: a `T9Segmenter` over
  `G14`, no abbreviations, `spell = true`, wrapped in `ForgetsInTables` as the nine keys are, no
  glide, no `u` stroke lookup (笔画筛选 wraps it as every pinyin session). It is made at the first code. `PINYIN_KEYS18` (`engine-pinyin#keys18`,
  「拼音 · 18 键」) is the same over `G18`, fed `Keyboard.pinyin18Key` and its taps `Keyboard.pinyin18Tap`; `T9` (`engine-t9`) survives only
  as pinyin's nine-keys session (「拼音 · 九键」), fed `Keyboard.PINYIN_NINE` — never a listed input
  method (0.4's 九键拼音 in a profile is dropped as any input method not known: an upgrade straight from
  0.4 is not supported). 0.6.0's handwriting input method (`engine-handwriting`) is
  dropped from every group by `HandwritingRetire` (`host/`, through `Profile.retireHandwriting`,
  run from `KotlinHost.retireHandwriting` at each read): first of a group, pinyin takes its place (moved, or
  added); its 繁体 in chttrans goes to pinyin there, else is dropped (`HandwritingRetire.traditional`); once
  (`Internal.handwritingRetired`) the toolbar's 手写 is pinned and, where it was first, turned on, and the user
  told (`Internal.handwritingRetiredUnseen`, said on the keyboard as 九键's notice is). On
  the nine keys every digit is a `PINYIN_NINE` code while typing: one the engine doesn't read (0)
  ends the input with the first candidate and goes in after it (`Keyboard.char`), as 0.4 did. The router's IM is `engine-pinyin` on every pinyin keyboard;
  `ImKeyboards.session(im, keyboard)` names the session. `KeyboardPair` stands for 拼音's keyboard: a
  code or a syllable key goes to its grouped session, an ASCII letter (a hardware keyboard) to
  full pinyin; while nothing is typed everything else goes to full pinyin (Esc to the app, digits
  pick). Switching sessions mid-input ends the one typing with ENTER, committed as its composing row
  shows it (unread keys by `asTyped`). Both
  get `context`, `fieldFlags` and RESET. So a hardware keyboard always types full pinyin, whatever
  the setting says.
- **Accuracy** (top-1, default reading, both sentence models; full pinyin, the 14 keys (`g14` and
  `g14-strict` alike), the nine keys (`t9-strict`), each on the same subset: the samples
  `ime-eval t9 --keys 14 --same` keeps): pinyin-chat held out (983 samples) 73.4 / 61.3 / 53.6%;
  pinyin.tsv (429 samples) 97.0 / 94.6 / 88.8%. The 14 keys sit between the nine keys and full pinyin.
- **Tap position** (design `dev/design/k14-tap-position.md`). A tap on a key of two letters is one
  character of `Keyboard.TAPS` (U+F0200 on, supplementary private use, after `AGAIN`):
  `tapKey(code, at) = TAPS.first + (code − G14_BLOCK.first) × 17 + round(at × 16)`, `at` clamped to [0, 1] first
  (just past 1 would round into the next code's places), NaN refused (the code itself is sent).
  `Keyboard.char` decodes it whatever the session into `Action.Key(code, at = n / 16)`; the input
  keeps the code, the place goes on `PressedKeys`' run (NaN for a syllable taken, a letter typed
  as itself, a text of several), through every edit, and `taps(from, to)` gives one value a place.
  Never learned, kept or backed up: `qk:` codes and every row are the same wherever a key was
  tapped (`PinyinSessionTapsTest`, uatTest `Keys14UatTest`). `T9Segmenter.segment(input, taps)`
  (grouped layouts only; `Segmenter`'s default ignores taps) gives each edge, per match,
  `against = Σ max(0, ±2(at − 0.5))` over its keys of two letters, the sign by whether the
  spelling typed for that syllable (a fuzzy partner's, üe's ue: a table beside the index, three bits
  a key, ties none: `letterTies`) has the key's first or second letter. The decoder adds
  `Penalties.tap × against` (−1, no dead zone) in `collect` and `collectStart`; at the centre, or
  with no place, the graph and the scores are byte for byte as before (both sets: 0 won, 0 lost).
  The bar (`NineKeys.offer`) puts after the rest a syllable one of whose keys was tapped at its
  other letter's centre or further (against ≥ 0.5), a yes or no so a tap near the middle moves
  nothing. Habits stay above it (user, 2026-10-25). Simulated hands (`ime-eval pinyin --taps
  <hand>:<α>`; hands perfect, sigma0.2, sigma0.3, right0.15, left0.15, right0.1, middle, uniform),
  `g14-strict`, both sentence models, the `--same` sets, held-out, top-1: chat (983) untapped 61.3,
  perfect 69.0, σ0.2 67.3, σ0.3 65.9, right 0.15 67.3, left 0.15 66.1, right 0.1 67.3, near the
  centre (unaware) 59.9, uniform 58.3; pinyin.tsv (203) 95.6, 96.6, 96.1, 95.6, 96.6, 95.1, 96.6,
  95.1, 93.1. α was swept on held-out first and the tune-half rule written afterwards; it gives 1
  too (see `Penalties.tap`). Simulated, for choosing α only.
- **Learning.** The grouped session's codes are kind `qk:<tag>` (`Kinds.keys`), apart from `qp`
  and `t9:*` as the nine keys are from full pinyin; the words and their counts are shared. The kind
  and the codes are the record's format: never changed once in a release. 「已学条目」 names `qk:` as
  「14 键」 and shows its keys as letters (`bn ui`, `NineKeyLetters.shown`).
- **What does not apply**: typos (keys tapped off-centre are read, see Segment above), glide, 拆字 (the hardware keyboard
  still has it); 中英混输 does apply. Fuzzy pinyin, u↔v included, does; so do `rq`/`sj`/`xq` and the custom
  phrases' keys, typed as their letters' keys (`PhraseKeys`, A1).

### 双拼 on 18 keys and on the nine keys

双拼's 键盘 setting (`AppPrefs.keyboard.shuangpinKeyboard`: `Full`, `Keys18`, `Keys14`, `NineKeys`, stored by
name) picks its touch keyboard; every scheme is open to each but 14 (not for 微软). The engine does not read the
setting either: it reads the codes.

- **Codes.** `NineKeyLetters.G18` groups `q,we,rt,y,u,io,p,a,sd,fg,h,jk,l;,z,xc,v,bn,m`, each key
  U+F8C0 + its first letter's place in the alphabet (BMP private use, freed by `Action.Syllable.IDS`
  going from 100 to 99; not braille, which blind users type as text). `NINE_SP` groups
  `abc,def,ghi,jkl;,mno,pqrs,tuv,wxyz`, codes U+F8F4 + (digit − 2), always the standard letters.
  Neither is normalized and neither has a separator; `;` (微软's ing) is on L's key.
  `NineKeyLetters.ofCode(c)` names the grouped keyboard a code is of (G14, G18, NINE_SP; null for
  the nine keys' digits). `InputMethodCatalog.readsCode(im, c)`: pinyin reads G14's, `PINYIN_NINE` and `PINYIN_18`; 双拼 G18's, NINE_SP's and `SHUANGPIN_14`;
  `KeyRouter` swallows any other code an input method gets (the old keyboard still on screen just
  after a switch), so a code is never read as another keyboard's group.
- **Sessions.** `InputMethodCatalog.SHUANGPIN_KEYS18`, `SHUANGPIN_KEYS14` (`engine-shuangpin#keys14`, the 14 keys'
  groups sent as `Keyboard.shuangpin14Key`; none for 微软, `ImKeyboards.has14`) and
  `InputMethodCatalog.SHUANGPIN_NINE` are the grouped sessions'
  ids and the names under which a setting says it is no use there (「双拼 · 18 键」, 「双拼 · 14 键」, 「双拼 · 九键」).
  `SHUANGPIN` is a `KeyboardPair` as pinyin is, a grouped session made at the first code of its
  range; a hardware keyboard still types the full-keyboard scheme (it reads `;`). The segmenter
  reads each syllable as the scheme's two keys turned into their groups' codes; the graph has one
  path, the ambiguity in each edge's syllables.
- **Segmenter.** `GroupedShuangpinSegmenter(scheme, letters, fuzzy)`, built as
  `ShuangpinSegmenter` is (pairs, a key alone as PARTIAL/INITIAL, a/e/o alone, fuzzy, and 简拼 in the middle:
  a key that makes a syllable with the next read alone too, an `ABBREV` initial for the words typed only,
  061-sp-abbrev §3.1; `abbreviations = false` for measuring, as `ime-eval --sp-abbrev off`), but no
  spelling slip, not even ü typed with its own key (the user's 2026-11-03). With `neighbours` (纠错 on,
  061-neighbour-groups) it reads a key tapped by its neighbour's edge as that key, as `T9Segmenter` does
  over pinyin's groups: each key's touch onto the key meant (`slipped`), its cost `SlipCost`'s (floor −5 on the
  18 and 14 keys, −3 on the nine keys' digits as on pinyin's; above); a pair read through a slip carries its slips,
  its arc marked `SLIPPED`, as 全键盘 双拼's are.
  It and `T9Segmenter` are `KeyedSegmenter`s: `letters`, `isKey`, `asTyped`, `initialsOf` (the
  bar's initials: the scheme's initials on the key's letters, 自然码's v zh) and `started` (a
  syllable not whole yet shows as its initial in 双拼, as many letters on the nine keys).
  `PinyinSession` and `NineKeys` use the interface, never `T9Segmenter`. An initial taken off the bar
  binds the key after it as its final, read by group (owner's decision (b), 2026-10-26): XC IO with
  c taken is ci, cuo …, and the bar offers those from the initial on.
- **Routing.** A `KeyboardPair` may have several keyed sessions, picked by the code (`keyedOf`);
  turning from one to another is as to full: what was typed committed as shown, both reset. A hardware
  char that is no letter reaches 双拼 as `EngineEvent.HARD_CHAR` (KeyRouter, `!virtual`): full 双拼's
  while a grouped session types where the scheme types it (微软's `;`), else the grouped
  session's mark; the screen's `;` (L;'s long press, the symbol page) is always a mark after what is
  typed (你；, never ni；).
- **Collisions** (design §1.5, each scheme's taught spelling of 418 syllables): on 18 keys 244–272
  codes, 253–301 syllables sharing their keys with another, 12.4–16.6% wrong for a syllable alone
  with no context; on the nine keys every syllable shares, about 46% wrong alone. 九键双拼 ships
  anyway (owner 2026-10-25), its summary saying so.
- **Top-1** (E2, 2026-10-26; both sentence models, the samples `shuangpin <scheme> --keys 18 --same`
  keeps, every keyboard on the same ones; the 8 schemes 0.4 had, range): chat's held-out full / 18 keys / nine
  keys 73.8 / 61.2–64.4 / 30.4–31.5%, pinyin.tsv 97.0 / 92.1–93.9 / 70.9–76.7%. For comparison 九键
  拼音 53.6% on chat, the 14 keys 61.3%. Table per scheme: design §1.5.
  微软 nine keys after `;` moved to 1 (2026-11-09, `shuangpin ms --keys nine` then `pinyin --scheme ms-nine`, both
  models, the app's pinyin.data, all samples typed rather than `--same`'s): chat held-out 31.5 → 33.0%, pinyin.tsv
  73.7 → 75.3%; against the old run 4 won, 1 lost of 827 shared.
- **The code as keys** (issue #5; setting `ShuangpinKeysShown`, off: the pinyin spelt, as before).
  `PinyinSession(keysShown = true)` (`SessionFactory`, every 双拼 session, the hardware one too) shows
  each syllable as its edge's keys (`Shows.keysOf`): a slipped key as the key meant (`Slips.meant`, as
  the pinyin shows the syllable meant); on a grouped keyboard `KeyedSegmenter.keysShown` gives the
  scheme's letters each key was read as, of the syllable's codes (fuzzy partners' too) the one whose
  letters the keys pressed hold, never the group nor its first letter; a syllable or initial taken
  off the bar its scheme keys (zhong `vs`, zh `v`). A typo read (ü typed with its own key, `TYPO`) is no slip:
  its key shows as pressed. `PreeditPlaces` maps the text to the keys as it does the spelt pinyin,
  so `'`, a pick's rest and 编码行 in the field follow; a taken piece shown shorter than its keys has its letters all at its start
  (`Builder.add(whole)`; not while an edit left the cursor inside it, nor once what is left of it
  shows letter for letter; Backspace right of it takes its last letter, as with the switch off), and `Action.Step` passes the places no preedit place is (`PreeditPlaces.shows`),
  so the cursor never stops inside the keys of what shows shorter than them; Enter on a grouped keyboard (`CommitRaw`, `restShown`) commits the letters shown. Chaizi
  and the misreading hints stay off as `spell` keeps them; the bar and the nine keys' column list pinyin.
- **Learning.** Kind `spk:<scheme>:<tag>` (`Kinds.shuangpinKeys`, the scheme as
  `EngineSettings.SCHEMES` names it, no colon in any), apart from `sp:<scheme>` and from each
  scheme's: the same keys read other syllables under another. `Kinds.isShuangpin` is true for
  both prefixes, `Kinds.keysTag` gives the tag, `Kinds.takeLayout` keeps a syllable taken from the
  bar under `<scheme>:<tag>`. 「已学条目」 names an `spk:` pin 「双拼 18 键」, 「双拼 14 键」 or 「双拼九键」 by its
  tag, its scheme after, its keys as letters (`bn io`).
- **Schemes.** Three since 0.5 (the user's 2026-11-01): 自然码 `Ziranma`, 微软 `MS`, 小鹤 `Xiaohe`
  (`EngineSettings.SCHEMES`, `ShuangpinScheme`'s tables). The option's default is 小鹤 (`EngineOptions`;
  `EngineSettings`' own stays 自然码, the engine's tests'), so a new install, direct boot and a value
  reset get 小鹤 with nothing written. `SchemeMigration.settle`, run by `EngineOptions.load` at every
  read (startup, the module files read again, so a restore too): on an upgrade (`KotlinHost.loadOptions`:
  not the first run, not `Internal.schemeSettled`; a 0.4 backup's prefs restored have neither) none
  stored is 自然码, 0.4's default, written explicitly, nothing said; any of the five 0.4 had besides
  (`RETIRED`: 紫光, 智能ABC, 中文之星, 拼音加加, 国标) is 小鹤, the user told once
  (`Internal.schemeRetiredTold` set once it is seen, the scheme waiting in `schemeRetiredUnseen` till
  then; a 0.4 backup restored tells again). Settled where a read says the same again: a scheme kept or
  dropped at once, the 自然码 pin once its write is on disk (`ConfigWriter.write`'s landed), so a config
  held as unreadable, not writable or a failed write is pinned again at the next start; direct boot
  settles nothing. Learning under a dropped scheme (`sp:Ziguang`, `spk:Ziguang:…`) is kept as it
  is, listed and forgotten by its old name (`ShuangpinNames` names all eight).
- Pinyin's 18 keys' taps (061-neighbour-18key, the user's 2026-11-16) are a block of their own,
  `Keyboard.TAPS_18` (U+F0500 on, `pinyin18Tap(code, at) = TAPS_18.first + (code − G18_BLOCK.first) × 17 +
  round(at × 16)`), not `TAPS`' indices 26–51 the design left (k14-tap §1.4): 双拼's 18 keys send the same G18
  codes, so a tap there would read as 双拼's; as `PINYIN_18` is pinyin's alone (`KeyboardPair.pinyinKeyed` →
  `PINYIN_KEYS18`), so are its taps. Decoded, typed letters and quick phrase as `TAPS` (`Keyboard.isTap`,
  `untapped`, `typedLetter`). Every key sends one where the layout leans; only a key of two letters a–z
  (`NineKeyLetters.weighs`: WE RT IO SD FG JK XC BN; not pinyin's L, the group `l;`) has its own letters weighed by it,
  and the engine, the app and the eval count alike. A key of one letter's place is for a slip from it alone: the key
  slipped onto has its letters weighed by where the tap lies on it (`T9Segmenter.slipAgainst`, `placeOn`; the code
  review's S1). Measured (ime-eval `--touch k18:…` with `TouchSim.placed`, held-out py18, rerank+refine,
  s0.2/s0.3 × seeds 1–3): dev/design/061-neighbour-18key-impl-report.md.
- **A layout's own groups** (060-render-p3 T9a, the engine part; T9b, the app's: app.md, grouped keys).
  `NineKeyLetters.custom(groups)` (each a key's `letters=`, rows apart by a space): `problem` refuses
  groups that leave a letter of a–z on no key (ü as v), a key of 0 or more than 5 letters (a–z, `;`), a
  letter twice on one key, or more than 30 keys. Groups whose letters are G14's or G18's, in any order
  (as many, the same set: 061-custom-layout §6, L4), are those keyboards (their codes, a group's code its first
  letter's wherever its key is; their `qk:`/`spk:` record and 双拼: the built-in keys moved about move no data;
  written in rows, the rows put in the built-in order, `rowsOf` the app's GroupedKeyboard hands its taps,
  its `Grouping.letters`, for where a tap falls on a key stacked: review L4 S1; the engine's G14/G18 have one row a key and
  read a place along it: a stacked key's tap is sent with where down it fell, a slip onto one placed down it,
  `Near.stacked`, `T9Segmenter.placeOn`).
  A group is its letters in their order: `wq` is not the 14 keys' `qw` (a tap's place goes against the first letter on
  the left), a grouping with it one of its own, pinyin's alone (docs/settings.md); any other gets
  codes `NineKeyLetters.CUSTOM_BLOCK` (U+F840 + its key's index, freed by `Action.Syllable.IDS` 98 → 97,
  so `Keyboard.SYLLABLES` ends at U+F83F): a stable encoding, kept in its `qk:<tag>` record (and backups),
  never to move; the record filed under its `tag()` (the keys' letters, commas between;
  `NineKeyLetters.filedAs` reads one back, G14, G18 or a layout's own, never NINE_SP; a tag of G14's or G18's
  groups in another order is still read as one of its own, as 0.6.0 filed such a layout, its learning no longer typed on). Making,
  comparing (`SceneGroups.letters`, `mismatch`, `leaningOf`) and filing are pure; `ofCode` reads a
  custom code as the keyboard `NineKeyLetters.inUse` last named (the session typing on it, T9b), none
  past its keys. Rows are where a tap falls, not the keyboard: with or without them it is equal. Pinyin reads them (`readsCode`), never text (`isCode`).
  A tap is `Keyboard.tapKey(code, x, y)`: `Keyboard.PLACES` (U+F1000 on, 30 × 17 × 9), decoded into
  `Action.Key(code, at = NineKeyLetters.place(x, y))`, x and y packed into the one value the taps carry
  (`2 × (1 + y step) + x`; a one dimensional place, the 14 keys', is x alone); a custom code's
  `tapKey(code, at)` is its middle row, and a G14 code's `tapKey(code, x, y)` its `TAPS` character as always.
  Against: `NineKeyLetters.against(code, index, place)`: a key of one row of two by the halves' rule above;
  any other by `against(index, x, y, rows)` = the distance from the tap to the letter's cell (rows share the
  key's height, a row's letters its width) over the distance from that cell to the key's farthest corner,
  0 to 1 (a row of two equals the halves' rule at every place, `NineKeyLettersCustomTest`). The segmenter
  keeps three bits a key for which letter (five letters a key). `typedLetter` gives the letter the tap went
  least against. `lean` stays the layout's (`Key.leans`; `lean="off"` sends the code alone).
  Typed in pinyin by its own session `PINYIN_KEYS_OWN` (`engine-pinyin#keysOwn`; `KeyboardPair.pinyinKeyed`
  routes `CUSTOM_BLOCK` and `PLACES` to it): a `T9Segmenter` over `EngineSettings.ownKeyboard` (the
  groups the app tells, `ownKeys`, rows kept, so a change of rows makes it anew; a change of `ownKeys` alone
  drops that session alone, `KeyboardPair.dropKeyed`, the field's last context told the new one), no
  abbreviations, `spell = true`, in `ForgetsInTables`, its letters `inUse` as it is made; learned under
  `qk:<tag>` (`EnginesOwnKeysTest`). Settings naming no keyboard (none, or groups `problem` refuses):
  `Engines` swallows those codes, what is shown left as it is (no `refines` or `picked` carried), never typed as characters. The learning
  report and the learned list show a grouped kind's keys by `Kinds.keysOf` (`qk:` `filedAs`, `spk:` `grouped`).

### 注音 on the nine keys and on 大千

拼音's 键盘 setting also offers 注音九键 and 注音全键盘 (大千, keys named by their QWERTY place); the engine reads the codes.

- **Codes.** `Keyboard.ZHUYIN_NINE` (0xF0450+: 11 groups, 分词, the tone key and its five tones, the 37
  symbols) and `Keyboard.ZHUYIN_FULL` (0xF0490+: 37 symbols, 5 tones), routed by `KeyboardPair` to the
  sessions `InputMethodCatalog.ZHUYIN_NINE` / `ZHUYIN`. `Keyboard.keyedChar` turns them into the input's
  BMP characters (`ZhuyinKeys.BLOCK`, U+F880–F8B5, freed by `Action.Syllable.IDS` going from 99 to 98).
- **Segmenter.** `T9Segmenter` over `ZhuyinKeys.NINE` / `DACHEN` with a `KeySpelling.Bopomofo`: each
  syllable spelt by `Zhuyin.of`, a symbol a fixed key, an initial its zhuyin initial. A tone is a separator
  (`SyllableGraph.isSeparator`); `SyllableGraph.tone(edge)` reads it after the edge. On 大千 a space after a
  syllable with no tone is tone 1 (`KeyedSegmenter.tonesBySpace`), else it picks the first candidate. With 纠错
  on 大千 reads a symbol key tapped by its neighbour's edge as that one too (`ZhuyinKeyboard` a `TypingKeys` there,
  tone keys none; 061-neighbour-groups); so does 注音九键 a group key by the group keys around it (its group
  codes, since the user's 2026-11-16).
- **Keys and bar.** 注音九键 has the tones ˉ ˊ ˇ ˋ a key each down the left (˙ on ˉ's long press and
  swipe); the key after ㄖㄥㄩ's group is 分词 (tone 0) while something is typed, else a comma as punctuation
  maps it. A tone with nothing typed is not read (`PinyinSession.reads`): it types nothing. The bar's
  syllables are shown in zhuyin (`KeyedSegmenter.shownOffered`; an initial as its symbol, r as ㄖ), and one
  taken off it as well (`T9Segmenter.asTyped`), though its pinyin is what goes into the input. An initial
  taken goes in as its symbol typed as itself (`KeyedSegmenter.keyOf`), no separator: a final typed next
  goes on from it, and alone it reads its whole syllable (ㄓ is zhi). So a whole syllable shown as an
  initial is (zhi, chi, shi, ri, zi, ci, si) is left off the bar for the initial (`NineKeys.offer`).
- **Tones.** Optional. `PinyinDecoder`'s `toneAllows` (`PinyinData.tones.allows`) adds `Penalties.tone`
  (−4) for each word whose character cannot take the tone typed: soft, a word is never dropped.
- **Learning.** Kinds `zy:nine`, `zy:dachen` (`Kinds.isZhuyin`), grouped, no spellings linked (U-20 is
  pinyin's). Custom phrases match each symbol's pinyin fragments (`ZhuyinKeys.PINYIN_WAYS`).
- **Top-1** (decoder alone, pinyin.tsv / pinyin-chat.tsv, `ime-eval zhuyin`): 全拼 93.9 / 70.0%, 注音全键盘
  93.2 / 69.4%, with tones 97.4 / 83.4%; 九键拼音 87.6 / 51.5%, 注音九键 86.0 / 48.7%, with tones 95.8 / 70.6% (tones as the app allows them: `PinyinData.tones`; the eval passed none before 0.5.1, so earlier "with tones" numbers were without).

## Tables

`TableSession` ranks entries whose code starts with the input as libime does (codes up to
`noSortInputLength` in table order, then shorter codes first), the table's own before the words
from outside it of a code as long (U-28). Auto-select commits a
lone full code, or the first candidate when the next key leads nowhere; 空码 is dropped by the next
key. A tap with a place (`Action.Key.touch`, 纠正错拼和按偏的键 on: `SessionFactory` hands it
`TableSlips.SLIPS`, a `SlipCost` with floor −1.5) is read as a key around it only where the key as
typed leads nowhere (an empty code, or 顶字) and that one leads on (`TableSlips`, asked by `TableSession.slipped` before
anything else reads the key: the nearest, then the one whose first entry the word model weighs more;
061-neighbour-table): never a code's first key, one past a full code or an end key, one put in before
the code's end, the wildcard, the pinyin key, a selection key or an end key, nor a code with the wildcard,
nor as one of them. Where the key would commit something (`TableSession.topped`: the first candidate shown,
as `autoCommit` takes it, by 顶字 or for a code that waits, below; a 简码, or a part of a code that shows the
character first, then the next code's first key, typed so on purpose most often) only past
`SLIPS.toppingFloor` (−1: the loosest that changes no word typed in the keys' middle, σ 0.15, five tables, both plans, seeds 1–3, both halves, where −1.5 changed one; a looser hand changes a few, 覅 → 要饭; −0.1 read a third as many slips) and for a key whose code's first table entry is another text the word model
weighs at least as much (the table's first, not the order the session would show: that is weighed only for
the code typed). Codes read toward pinyin entries alone (五笔拼音) are not read. A code with a key so read
(`TableSlips.marks`, a bit per place, moved as keys are put in and deleted) never commits itself as it is
typed; where its one candidate would have (`TableSession.waits`), the next key that cannot go on commits
it through `autoCommit`, as a full code's next key does, so the space after it is the new code's (the
user's call, DECISIONS「形码临键纠错：改读出的唯一码自动上屏」); else it waits for a space or a pick.
`ime-eval table-slips` measures it on whole codes and on the parts that show a word first (`--plan prefix`). A code with entries, and
typing on target, are as without a place. The code keeps a cursor as pinyin's input does; a key typed before its end is put in there and
never commits by the next-key rule (the code is being mended, not full), a lone full code still
commits. `pinyinKey` (五笔 `z`, 二笔 `[`) looks a character up by pinyin, showing its code (the
cursor then the pinyin's);
`matchingKey` is the wildcard (五笔 `z`, 仓颉 and 二笔 `*`). The keyboard's 拼 key (`Action.LookUp`,
`Keyboard.LOOKUP`) turns a lookup mode on and off for every table with a pinyin session (each has one
now, 仓颉 too, made as it first looks up: `SessionFactory.MadeOnUse`): on, every letter looks up
(`PinyinLookUp.start(first =)`), a pick leaving it on; a code of letters typed when it is pressed goes
into a lookup (one with a wildcard or `;` stays a code), a lookup it started back into a code (`drop`:
`Session.handBack`, the pieces picked put in and learned as a pick, the code keys after them the code
as typed, up to the longest, nothing of them committed or learned).
The mode (`TableSession.LookUpMode`) is held per input method by the `SessionFactory`, so it outlives
sessions made anew; `Snapshot.lookingUp` marks any lookup and `KeyRouter` puts 拼 (`LOOKING_UP`)
before its preedit. A table's roots (`TableRoots`: 仓颉's one a key, 王码五笔 86's 键名 and roots) show
on its keys as `TableOptions.keyLabels` (`KeyLabels`, the group's `KeyLabels` option, defaulting to the
preset's in a group an older config already has); 仓颉's preedit and hints are spelt in them
(`TableOptions.shown`, one character a letter, so cursors are unchanged). What the keyboard needs of
a table is `Engines.tableKeyboard`: the mode and its roots from the settings, its non-letter code keys
once its session is made (the table is not read to answer), the same answer while none of them changed. Characters typed one by one make no
phrase (U-28 C46: 自动造词 and its two settings are gone; an older config's `AutoPhraseLength` and
`SaveAutoPhraseAfter` are read by nothing); the phrases an older build saved (its FXUT log, which 0.4
converted into the learning record's `TABLE_WORD` rows and told pinyin of) stay the table's (`SavedPhrases`).
`SharedWords` gives every table the user's words from the other input methods, coded by its rules:
those made, added, typed, and every word of an imported dictionary (owner, 2026-10-19), ranked after
the table's own. A full code the table has one entry for, which commits itself, takes none of them
(D8): one who types it blind keeps it, however big an import (F2328 had 搜狗标准 393k take 五笔's
auto-commit on eval text from 22% to 9% before this).
The dictionaries' words are coded in the background (`InBackground`, on `warmUps`) at the first key
after a user model is made or reloaded; till then a table offers the rest.
Options per table are in the host's `EngineOptions` (`engineTable(...)`) and `TableOptions`; prediction and 性能档位
are the shared settings on the 通用输入 page (`InputMethodSettings.SHARED`, `SharedInputFragment`), page size
on the hardware keyboard page (`HARDWARE_KEYBOARD`); each table's group in `androidengine.conf` still carries them.

Every table's words follow one rule, set at build time (`table --words --pinyin`, decided on 五笔
86 and kept for all, `lexicon/README.md` "Words in the code tables"): the table keeps every entry
it ships, only reordered, under each full code the commonest first; words added from pinyin.data go
when under a tenth as common as their code's commonest; new words (the pack's) never go and count
ten times; characters never go; 简码 (codes shorter than the longest) stay as they are.

At typing time a table has the five every Chinese input method has (`TableLanguage`, made in
`SessionFactory.tableLanguage`, over the one `ChineseLanguage` handwriting and the tables share for a user model). Where it orders by use (调频: off on a new install, D7; an install that
typed a table before 0.4 keeps it on, as 0.4 wrote `OrderPolicy=Freq` once;
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
The user's custom phrases (A1; `TableSession.phrases`, the built-in ones not) come under a code that
is their key, at their order but never first (`Ranking.placeCustom`, at least index 1): a table's code
and a phrase's key are both letters, and space must still commit the code's own first, so a code
typed blind keeps it. None where the code's lone entry commits itself (D8), nor do they stop one that does
(`Ranking.only` passes over text); a key longer than the table's code length never shows (the code
ends first). A wildcard's letter in a key is the letter. A key counts for `leadsAnywhere`. A phrase is text: not learned, never committing
itself, its long press `DELETE_PHRASE` (`Action.Unpin` → `PhraseBook.remove`) learning or not.
恢复码表顺序 is `LearningFacade.resetTableOrder(im)` (`TableOrderReset`): what was learned of the order goes,
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
(`EnglishDecoder`: spelt, started, or spelt with a slip); a key typed as one beside it costs by where
the tap fell (`Action.Key.touch`, kept a place each beside the input; 061-neighbour-english): pinyin's
`SlipCost` (σ 10 dp, never below 0), at most `far` (a tap in a key's middle costs about as much: 2.2–2.8 on
a phone's keys 32–36 dp wide), the fixed `near` where a key has no place (a hardware key, TalkBack); a word that is none is corrected and
space commits the correction; backspace right after takes it back and that word is never corrected
again; so is a word picked over its correction, and one whose correction a tap back takes back
(`Corrections`: the cursor put right after a correction of the field's last few, the word typed is
the one candidate). Space after a word adds a space and predicts; punctuation moves that space
behind it; double space is a full stop. `EnglishUser` reads the words from `learn.log`, each word
committed an `EN_COMMIT` (prev, word, typed, how, the field's flags): counts over the last 30
typing days; never corrected (`EN_WORD` flag) and added words kept till deleted on the English
page, which gives a word back to correction. Typed twice is not enough (D11). `Misspellings` corrects common misspellings the web taught the model as
words. Auto-capitalisation is the keyboard's (`AutoCaps`), not the engine's.
The user's custom phrases (A1, the built-in ones not) come under a word typed that is their key, in
any case (on the nine keys as the keys' letters, `PhraseKeys`), at their order but never first
(`EnglishPhrases`, `CustomPhrases.place(from = 1)`): space commits the word typed or its correction
as before. Which candidates are phrases is known of the list it placed them in alone (by identity), so
predictions and a correction tapped back have none. Where the nine keys find no word, the guess
space commits (each key's first letter) is put first, the phrases after it. A phrase picked is
committed as it is, no space after, not learned.
Long press: `DELETE_PHRASE` alone.

Glide typing: the keyboard (`GlideInput`, `GlideGesture` in ime-core deciding when a finger glides
rather than taps, swipes or holds) hands the path, in key widths across and rows down over where
its letter keys are (`engine/glide/GlideLayout`, a letter on two keys on the split keyboard), to
`Engines.glide`, which reads it as the input method does (a pinyin's or a table's readings found
then, `GlideSession.read`: a path the decoders slip on loses the glide, not the key), then sends
`Keyboard.GLIDE` carrying it (`KeyEvent.payload`, to `Keyboard.onEvent`), as handwriting sends
`INK`; `Action.Glide` commits the best word with the session's own space and offers the others: a pick replaces it, a
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
not read at all), nor does what the keyboard typed there (`KeyRouter` sends no `TYPED` from a
PasswordOrSensitive field: a password shown is Sensitive, not Password, and so looks like an
incognito field), so there only the session's own glide counts; after typed text, a Backspace or
an arrow nothing before the cursor is known, and a glide gets no space before it (part of the
F2074 limit). A glided word always takes its in-sentence form (`inSentence`): the data's
sentence-start "Hello" is "hello" anywhere, a word the data or the user has only capitalised (London,
iPhone) keeps it; a capital comes only from Shift, as for a word tapped (`AutoCaps` turns Shift on
where the field asks for sentence capitals), Caps Lock all capitals. Where English suggests nothing (`KeyRouter`'s
`suggestsEnglish`: NoSpellCheck, an address, a password, suggestions off) the keys type themselves.

On the nine keys English takes `Action.NineKey(digit, upper)` (the host `Keyboard`'s
`NINE_DIGITS`, 0xF0040 up, past `LETTERS`, clear of INK and GLIDE). The input is then digits
(`'` from 1, kept in place) and `NineKeyWords` reads it: an `EnglishDecoder` whose substitution is
`nineKeyCost` (a digit fits any of its `NineKeyLetters`, the same 按键字母 as pinyin's nine keys; a letter only
itself) and whose every edit costs `NINE_KEY_COSTS`' `NEVER`, the user's own words matched the same
way; a digit tapped with a place (`Action.NineKey.touch`) also fits the letters of the digit key next
to it that the tap leans to (`EnglishDecoder.nineKeyTap`: pinyin nine keys' `SlipCost`, floor -3: on keys
80–88 dp wide a tap within about a tenth of the width of that edge), 2
dearer where some word of the keys as typed is among the words (`legalSlip`, as pinyin's legal slip:
without it one word of 19669 typed on target went wrong, `not` read `you`), then words as long as the keys first, completions after, each by score, in the case Shift gave
each key. A letter a long press pinned (`Action.NineKey` with the letter, `Keyboard.NINE_LETTERS`
from 0xF0060; where English does not suggest the addon swallows the digits and types the letter) is
itself among the keys, and marks the word the nine keys' (`pinned`) though no digit is left: never
corrected, first among the candidates when every letter is pinned (a prefix of longer words too), and
a word so spelled the data lacks is learned and offered by its keys after (owner 2026-10-20). The
preedit and `typed()` are the first candidate, else each key's first letter
(`NineKeyWords.firstLetters`, a guess not learned), so space, punctuation, Enter and the
arrows commit a word, never the digits; the cursor stays at the end; `keypad()` picks the nth
candidate (`picksByDigit`). `ime-eval english --keys nine` measures it (2026-10-20, english.txt:
95.9% first, 98.4% in the first three). `--hand s0.2` (TouchSim's hand, either keyboard) taps each key
with a place and gives rows as the session reads it (`-t`) and as with `readsTouch` off (`-0`):
clean, a slip a word (`slip1`, a key landing in one next to it) and, on the full keyboard, the
`--noise` slips (`slips`). 2026-11-15, english.txt, s0.2: full keyboard clean 87 → 71 words wrong
(26 put right, 10 newly wrong, as `rammy` → `army`), slips fixed 73.8% → 75.8%, a slip a word
80.2% → 82.7% (first three 93.4% → 96.2%); a looser hand (s0.3): clean 87 → 74 wrong, but slips fixed
73.8% → 73.4% and a slip a word 80.5% → 79.3%. Nine keys, s0.2: clean the same words, a slip a word 0% → 72.9%.
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
(`PinyinGlide.Spelling`): full pinyin the letters, 双拼 its scheme's two a syllable (微软's
-ing is on `;`, which a glide path cannot carry: `GlidePath` takes only a..z, so those syllables
cannot glide, though the unsplit letter keyboard has a `;` key for them), the nine keys
the letters too, on their digits' keys (`GlideLayout.sameKey`: letters on one key follow without
the path moving). A table glides its codes (`engine/glide/TableGlide`): its entries walked in code
order as a trie, a code scored by its likeliest entry under the word model. `GlideSession` wraps
each of their sessions (`InputMethodCatalog.glides`: all but handwriting, English its own): `Action.Glide`
types the best reading's keys (the nine keys' as `Action.Letter`, so 426 is not read as gan), after
`between` where something is typed already (pinyin's `'`, so glides chain into a sentence; a
table's first candidate picked), and a glide that starts the input puts the next two readings'
words after the first candidate, a pick of one typing its reading and picking it there, so it is
learned and followed as any word. `ime-eval pinyin-glide` measures it per dictionary word of a
pinyin set (`--shuangpin`, `--keys nine`, `--table`): on pinyin-chat (readings compared with their separators, xi'an ≠ xian) full pinyin 85.3% first,
96.5% in the first three, 0.4 ms; 双拼 (自然码) 82.1% / 94.2%; the nine keys 53.5% / 76.7% (a key three or four letters, the path reads far more; the weights moved it under a point); 五笔 70.6% / 90.0%,
仓颉 94.5% / 99.3% (the characters it has). These times are with no user model: with one, a glide works out again only the words the user has counts of, at the nodes (pinyin) or entries (a table) it reaches, the rest kept till a word gets its first count, is forgotten or blocked; unmeasured. A table entry with no count of its own keeps its score meanwhile, though a counted word is in its likeliest split (你们 as 你 + 们). The setting is off by default: a sloppy fast tap may glide.

Writing on the keys (the toolbar's 手写, designs 060-kbd-handwriting and 061-handwriting-toolbar; 0.6.0's
handwriting input method and 键盘手写 merged into it): `InkOver` (`engine/handwriting`) wraps,
outermost, the session of each input method that `InputMethodCatalog.writesOnKeys` (all but English;
over a `GlideSession` and a `StrokeFilter` where those are, which `Engines.glide` unwraps). An
`Action.Ink` goes to a `HandwritingSession` of its own, made at the first (`SessionFactory.handwritingSession`,
`Kinds.HANDWRITING`, no input method of the list); while that shows anything (what is written, its 联想) every
action is its, but a letter, which puts its best sentence in and is typed into the wrapped session in the
same snapshot; what it put in is told the wrapped session as an `Action.Context`, and a new sentence written
after the wrapped session put text in is told the text before anew. An Ink while the wrapped session has
input typed (from before 手写 was on, a hardware key, a swipe's command) first puts that in as space would,
its first candidate (a pinyin's a word at a time, more than its longest typed as it is), and is written
then, in one snapshot. One while it offers 联想 starts writing and drops the offer. A Backspace the app does
and a key passed on reach the handwriting too, so a sentence it put in and deleted at once is taken back
(U-03). A reset (the field left, the keyboard hidden or made anew) puts the written sentence in as it
shows (DECISIONS A2). The last action the handwriting's (`lastInk`), what is read again is its though it
shows nothing (its 联想 turned off, then taken back). `Engines.ink`/`inkSent` take the input method (its
shown session, a pair's keyed one), and `inkEnded` and the pieces ended over a drop of the sessions count
every handwriting session made. Which touch is a stroke is the app's: with 手写 on (`HandwritingMode.writes`),
every touch on the letter area (`BaseKeyboard`), none elsewhere; no threshold, no taking a tap back.

## Handwriting

One character at a time: two recognizers, trained outside this repository on synthetic ink only
(fonts and Make Me a Hanzi's strokes; CASIA for evaluation alone) and shipped in the release
`handwriting-models-*` (`HandwritingDataPlugin`, its `manifest.txt`), read the character by its
image (9 × 64 × 64 planes) and by its pen path, weighed 0.7 to 0.3 with 0.2 × its log frequency.
Their dense and recurrent weights are int8 (the convolutions float): 121 MB → 43 MB in the APK, the
same on CASIA to a hundredth of a point (`handwriting-models-20261015` against `-20261014b`). They
are mapped from their resource-pack file where they lie (`OrtModel`: the model's bytes used
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
the pen was up long enough, an `Action.Ink` with no reading, then `Action.Refine` slices), after the text before
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
The sentence is shown in the field and kept there as the keyboard goes (`KeyRouter.composesText`), as English's word is; pinyin's and a table's codes show above the candidates.
The pad (`InkPad`, ime-core's
`input/handwriting`) decides when to read (each stroke ended), when a piece is done (the pen up
`InkPad.finishMillis`, the app's `handwritingPause` setting, `FINISH_MILLIS` its default: its ink
wiped, its reading kept), and that backspace takes back a
stroke. The reading goes to the session with a key: the app sends `Keyboard.INK` carrying it
(`KeyEvent.payload`, S13), which the keyboard turns into `Action.Ink`, so the two cannot come
apart (once the app had to offer it to `Engines` just before the key, an order nothing checked).
`Engines.ink` makes the session first, for a reading never to be typed as the key's character
where handwriting cannot load; with handwriting not the input method (a piece finished as the pad
leaves), it holds the reading for its next Ink (`HandwritingSession.hold`), no key sent. A key sent
that never reached the session (a temp mode, quick input or Unicode over handwriting, filters every
key; the field lost focus) is held the same, by `Engines.inkSent` after the key
(`HandwritingSession.took`): its piece is shown with the next stroke, not lost with its ink left on the pad.

Pieces are numbered, later higher, and every reading carries its piece: what a reading does is
decided in the session, on the host thread, in order with the keys. A reading of a piece written
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
- **The user's logs outlive the data.** The learning record writes words as text and spelt syllables, never
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
  Till 0.4 an older build's logs (`pinyin.user`, a table's `<im>.user`, `english.user`, libime's
  `user.dict` and `user.history`) were converted into it once; this build reads none of them (an
  install of 0.4 or before upgrades through 0.5 to 0.6.2 first), and the clears delete them, a
  table's log and its `<im>.user.told` with a clear of everything or once read into the record
  (`learn.table-logs-read`; `LearningControl.retired`). The tables' saved
  phrases are the record's `TableWordRow`s, offered in the order saved (`SavedPhrases`). A
  fault of the record's own turns learning off for the run (`LogHabits`, `CommitRecorder.safely`)
  and never loses the commit. The traditional text before the cursor is read simplified by
  OpenCC's tables (`assets/learn/`, `Simplifier`): text with a character simplified text never has
  is traditional, and only there are those simplified text has too changed (乾淨 to 干净, 由於 to
  由于; 乾坤, 哪吒 as they are).
- **The user's controls over learning (`learn/control/`, PLAN §2.8, impl/streams/E-design.md).**
  One entry, `Engines.learning(im, op)` (`HostApi.learning` in the app), for every `LearningOp`:
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
  seqs go on; both delete the record's copies kept aside and U-86 and U-88 今天 the older builds' files (renamed `.cleared` first, so a clear killed midway finishes at the next open: `resumeDrop`). `RecordStore.rewrite` finishes a fold being written first and never writes a header
  alone. A word's last-used calendar day is `LearnState.dates` (`DATE` row 80: typing day to
  local day). A search field (`NO_PREDICT`, U-91) shows no 联想 either (`PinyinSession.predict`,
  `Predictions.predict`); `FieldContext` keeps the field's flags for a session made after its context (`FieldFlags`: the host's one copy, given to each `Engines` it makes). A
  commit flagged `NO_PREDICT` or `NO_PAIRS`
  (`Commit.UNPAIRED`) counts no pair and no 联想 pick (a `pred` row) on replay: a tail written by a build that still paired them
  loses those pairs at the next open, as meant; an older build reading the record pairs them. A clear that
  fails on disk says so (`LearningResult.Failed`) and still saves a backup if they are on. An undo
  (`LearningOps.undo`) whose rewrite of the record failed answers `Failed` too, saving no backup: the act on disk, the
  rewrite that drops it threw (the record took no rewrite, or the disk filled in it). An act whose records were never
  written (refused while storage was full, or the record not saving) is taken back in memory and answers `Done`, nothing
  on disk to drop (`LearnLog.dropUnwritten`: the state made the file's again); of an act written in part, the rewrite
  drops the part written. Both make the state the file's, so an act the file refused records of is taken back only
  while those are all that is unwritten (`LearnLog.unwritten` counts them, R25 F8066): with anything else in memory
  alone (learned before the act, as a word learned after storage filled and then deleted, or after it) the undo answers
  `Unavailable`, nothing dropped. `Unavailable` too when the act can no longer be undone (its token stale, something learned since), and for a
  forget on a record that did not open this run (`LearnLog.rewrite` writes nothing there), the word still deleted. `LearningOp.Saving` answers
  `LearningResult.Saving(saving, rewrites)`: `rewrites` false only while the record takes no rewrite till the process
  starts anew (`RecordStore.checkRewrites` refuses: a write failed and the store did not open again; or a file there that
  did not open this run, `LearnLog.takesRewrites`), true when storage is
  full (writes refused, the record open), where the app tells to try again, not to restart.
  The tables (stream C) learn into the record too: a candidate's block is per code (`TableBlock`,
  listed as `Listed.TABLE_BLOCKS`), kept by U-88/U-86 but 全部清空, which also forgets the phrases
  an older build saved (the `TABLE_WORD` rows); 恢复码表顺序 is `LearningOp.ResetTableOrder`. Tables and
  handwriting key their 联想 by `Predictions.why` (the predictor's `after`), as pinyin, and reread.
  Pinyin's 联想 goes on from the user's own word the record has last where the decoder made it of
  several (你好 of 你 好: `PinyinSession.ownWord`), so what was put in after it, keyed by its text,
  is offered and turned off there.
- **Learning backups (`learn/backup/`, impl/streams/D-design.md, U-100).** `Backups` keeps them,
  `BackupControl` makes, restores and takes a restore back (退回恢复前), `BackupMerge` merges one
  into the phone's record. An FXLB file is a head (70), the record as a snapshot, then the lists
  (71, 72), then always and once, last, an END (74) holding the file's length in bytes, itself
  included; a file without it, or whose END is not last or names another length (cut at a record's
  boundary, a record lost or added, a draft build's file), is refused. 73 is the record's (below);
  75 and 76 (0.5.0) the app's address bar's book (`AddressData`: its suffix counts, its remembered
  addresses, a string each, opaque here), before the END, which v0.4.0 skips; one without them
  (v0.4.0's) restores none and leaves the app's as they are (`Backup.addresses` null). 77 (0.6.3) an
  added word voice always puts in (`VOICE_ALWAYS`, its text), after the lists: 0.6.2 and before skip it
  and restore the words unmarked, a backup without it marks none; a restore marks them beside the
  phone's, 退回恢复前 puts the copy's back as they were. The version stays 1, as a bumped one an older
  build refuses whole. 78–79 a later build's of the file and dropped on read and never written (a kept row of 70–79 but
  73 is left out of `bytes`), any other type the record's and kept as it is (review-int-records M1).
  `ORIGIN` (73) rows say by how many typing days an install's backups were moved, so the same one
  restored again lands where it did; every install that opens the record writes one of offset 0
  (`BackupControl.claim`), so a record carried to another phone (D2D, a settings zip) restores the
  backups made where it was on its own days (S5). A backup's import batches new to the phone get
  ids past the live record's last, undone ones included (S3). The lists, `EnWordRow` flags, a
  table's blocks and 不再联想 merge as a union: an older backup brings back a flag removed since
  (U-100's 合在一起). U-86 (not 全部清空) keeps the ORIGIN rows, as it keeps the table rows they moved; a pin of a text blocked after the merge is dropped from either side, and a word on both of a backup's lists comes back blocked (C9). Automatic backups are kept two newest by typing day, are timed no earlier than the last commit's minute. Rows kept unread are written with the ids of KINDs of the build that wrote
  them: a later build must not put kind ids in rows it expects a backup to carry. `TABLE_WORD` rows
  are the phrases an older build saved in a table, as 0.4 converted them: merged, backed up and kept by
  清空, served by `SavedPhrases` (those the table lacks, oldest first); forgetting one writes it removed
  (`LearnLog.dropTableWords`).
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

生僻字屏蔽 costs a key one bitmap lookup per arc (`HeldWords.has(id)`); the bitmap is built by a walk
of every dictionary word's characters at the first need after the data or the choice changed
(`UserLexicon.held`, counted by `heldMade`): 13–22 ms on the Mac's JVM (2026-11-14), not again while
neither changed. The page's copy of the grid (`Engines.rareGrid`, one `runOnReady`) took 10–13 ms there,
sorting off the fcitx thread another 10–13 ms. Which 扩展 B characters something types (DECISIONS N2)
is the pack's part, walked once an `Engines` (every dictionary word, every table, the parts: the pack's
data does not change under it), and the user's words' (their merged dictionaries, those added or
imported, those learned), walked again as the grid is made again: `LexiconFacade` keeps the grid till
the user model or its `listings`/`changes` move, the key the tables keep theirs by. A merged
dictionary, a million words for a big one, is walked once a file (`DictionaryFile`, which comes new
only with a new model), not at each grid made again as the user learns: a million words took 28–45 ms
on the Mac's JVM (2026-11-14, a made-up dictionary of two to four characters a word). The first making
on a cold process, a debug build on the Mac's API 36 emulator (2026-11-14, five runs): 0.8–1.5 s on the
fcitx thread, of which the user model 0.39–0.70 s (made then rather than at the first key, which would
make it anyway), the pack's walk 0.31–0.55 s, the copy 0.11–0.23 s; the user's words 1 ms (none merged). Left there
on purpose: only the settings rows of 生僻字屏蔽 ask, once per engine for the pack's part, while no
keyboard is up but for a field tapped in that second; moving the walk off the thread would need a
second call from the page (the pack's part made on another thread, then the grid), for a cost paid
once.

双拼 with 简拼 in the middle, a key's round trip through `EngineEvalRunner` (lib/ime-eval/run-on-device.sh,
`engine-shuangpin`, 小鹤) on the API 36 emulator of the Mac mini, 2026-11-14: 10 inputs each of 20, 40
and 60 keys, in whole syllables and as a first syllable then initials, keys 41–60. Before the words
were kept from key to key (`SyllableGraph.sameUntil`), p50/p95 17.7/25.8 ms whole and 21.7/29.8 ms
initials, growing with the input; after, 5.5/8.0 and 7.6/9.8, as without 简拼 in the middle
(the decoder before it, 7689e738f, 6.7–7.5/11.9–15.3 and 9.1–10.3/15.9–18.4 over two runs). On the host JVM
(`ime-eval pinyin --scheme xiaohe`) keys 41–60 went 2.0/2.5 → 0.6/0.8 ms whole, 2.5/3.1 → 0.6/0.9
initials; all candidates the same.

That emulator could not show what 简拼 in the middle costs: it runs on the Mac's cores, where the extra
work of a key was about 0.6 ms of a 5–7 ms round trip, less than two runs of one build differ by. On the
OnePlus 6T (the same runner and inputs, each build installed in turn, three rounds alternated, 2026-11-14)
keys 41–60 took, p50/p95, 9.8/16.7 ms whole and 14.8/24.4 ms initials before 简拼 in the middle
(3cbb9726b), 20.4/25.9 and 27.7/35.6 with it (191bc1ddf): every position a score of readings more, and the
last positions collecting all of theirs again each key. With `collectOn`, the walk along a node's children,
the beam comparing before it looks for a state's double, and the first words kept while no arc from the
start changed and none reads to the end, 14.1/20.1 and 20.3/28.0 (keys 1–20:
7.7 → 15.3 → 12.0 ms whole), all candidates the same on the 双拼 sets; on the host JVM, through
`PinyinSession`, a key 0.70 → 0.41 ms whole and 0.91 → 0.54 initials, 0.07 and 0.15 with no key read
alone. What was left was the reading itself: a 小鹤 key in whole syllables scored 6,400 words on 13,500 arcs
where it scored 2,900 on 540, nearly all of them dropped by the model's score, which nothing cheaper
bounds. So a key alone now reads only the words the user typed or chose (step 1 above): with the 3000 likeliest words typed a key in whole syllables scores 3,600 words on 2,300 arcs in 92 steps (6,800 on 3,700 in 148 by initials), with nothing typed 2,900 on 1,600 in 57, the words from the positions between a syllable's two keys, which nothing then reaches
(`ShuangpinKeyWorkTest` prints them and holds them a quarter above); with each key tapped in a place and read as its neighbour too, 3,800 on 2,500 in 97 (7,000 on 4,000 in 157 by initials), the case a phone is timed on. A slow phone is measured on the
phone, and by counts where a time would not hold still.

Keys tapped off-centre on the 6T (`EngineEvalRunner` with `-e evalNine true` for the digits, each key's
`Touch` of `ime-eval --touch …:s0.2:clean --touched`, 30 samples of 60 keys, learned data on, 2026-11-14/15;
a Chrome process took 75% of a core throughout, so rounds of one build differ by 2 ms): keys 41–60, the median
of the rounds' p50 and p95, the feature against its base (946a4ab04): 全拼 +0.9/+1.3 ms, 小鹤 whole −0.3,
initials −0.1, 九键 at floor −4 +2.7/+3.1 (19.7 → 22.4), at −3 +1.4/−0.9 (5 rounds against 5). The nine keys'
cost was not the decoder: a touch gives nearly every graph a slip, so `HabitOrder.ranksHere` walked the
code read whole once a candidate, a key (`readsAlong`), and `words` read each candidate's words again
(host, through `PinyinSession`: `reading` 63k → 212k calls, 1.5M → 3.4M steps). Each word at its place is
now read once an order (`Readings`), and walked on the code read whole only where its reading goes over an
edge read otherwise (then `Reading.otherwise`; since 061-sp-abbrev a slip, `Reading.slips`): 107k calls, 1.7M steps
with touches. A word over keys no slip reads (`SyllableGraph.readsSlipWithin`), from a place reached along the code
read whole, is not read at all: the decoder read it there, as every candidate takes its slot unread where no slip is. On the
nine keys' 60-key samples that leaves a word in ten read (host: 35.6k of 39.7k words skipped), and on the
emulator the nine keys went from +0.49 to +0.17 ms over 946a4ab04 at keys 41–60, the candidates the same at
every key (061-nine-perf-impl-report).

061-sp-abbrev reads a key alone on the 18 and 14 keys and the nine keys too (only words typed or chosen,
step 1 above). Host JVM, the 86 long 小鹤 inputs with taps placed (`--touch <keyboard>:s0.2:clean`), learning on
(3000 words typed), one thread, no Gradle worker running, keys 41–60, p50/p95 in ms, abbreviations off against on:
full keyboard 0.88/1.22 → 0.95/1.32 (a key alone was read there before: 0.96/1.26 then), 18 keys 0.93/1.30 →
1.05/1.41 (+13%), 14 keys 0.98/1.50 → 1.15/1.63 (+17%), nine keys 1.15/2.05 → 1.59/2.41 (+38%), the first
candidates and pages the same as the design's prototype at every key. The cost is the decoder (nine keys
0.39 → 0.63 ms a key) and the code line (0.02 → 0.14), not `HabitOrder` (0.02). The budget was +15% for the
grouped keyboards; the nine and 14 keys' figures were accepted by the user 2026-11-17, optimisation later.
`ShuangpinKeyWorkTest` holds the grouped keyboards' arcs and steps a quarter above what they read with 3000 words
typed; the 6T is timed against `dev/design/061-sp-abbrev/6t-base.md` (p50, 小鹤, w3000: 18 keys 13.6 ms,
14 keys 16.0, nine keys 21.3). w3000 is the design's draw of 3000 words by frequency (not in the tree); a history
from the tree is `ime-eval history <pinyin.data> 3000 <words.txt>`, the likeliest 3000, typed on a device with
`HISTORY=<words.txt> lib/ime-eval/run-on-device.sh` (specs/testing.md).
