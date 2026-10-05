# lexicon: the words a curator decided on

The candidates `lib/ime-dict-tool/engine-data.sh` finds in a crawl are runs of characters that hold
together; many are no words (作为一, 获得各种, 日皮视频). Packed unread they cost more than they bring
(dev/TRAINING-PLAN.md 11.7b: on the new-word set a clean layer of 363 words +8.9, a dirty one of
780,000 nothing, and −4.2 once ranked above the rest). These lists are what was decided, kept in the repository so a decision is made once:
each release's word pack is `pack --lexicon lexicon/add.tsv`, and a word in either list is never
put before a curator again.

| File | A line |
|---|---|
| `add.tsv` | `word  reading  kind  date  note`: goes in the `new` layer, read as written |
| `reject.tsv` | `word  kind  date  note`: never goes in |

Tab-separated, `#` starts a comment. A reading is `pin'yin`, a syllable a character, `v` for ü.

## Kinds

Taken: `word` (a word or set phrase people type as one: 内卷, 面试地点, 长期服用), `name` (a person,
place, work, brand or organisation people know: 墨雨云间, 坦佩, 多抓鱼), `slang` (吗喽, 尬聊, 那咋了),
`term` (a field's word: 酸甲酯).

Left out: `fragment` (a run across a word's edge: 作为一, 外还有, 长效运), `phrase` (a loose
combination nobody types as one, or one so long it is a sentence's part: 获得各种, 无需专业),
`typo` (a misspelling or misuse: 总得来说), `junk` (spam, adult or gambling ads, SEO stuffing,
machine text: 日皮视频, 万霖 when only spam pages use it), `obscure` (a name or term so rare that
lifting it would push out a common word under the same reading).

## How a candidate is judged

- Would someone typing Chinese type these characters together and want them as one candidate?
  Then take it, phrase or not: the engine got it wrong alone (only such candidates are shown).
- The reading is the one people say in this word: 长期 chang'qi, 行业 hang'ye, 音乐 yin'yue,
  便宜 pian'yi. Correct it when the suggested one is wrong; that is the commonest error.
- Look at the engine's first candidate for the same reading. When it is itself a common, right
  word or phrase (主要有 for 主要由, 小时候 for 小时后, 等多种 for 等多重), take the candidate only
  if people type it at least as often (第二步 beside 第二部: yes); otherwise leave the choice to
  the sentence around it (`phrase`). When it is a wrong guess (胀气服用, 面试的点), take the
  candidate if it is right.
- A fragment often starts with the end of a longer word (化建设 of 数字化建设, 业数字化 of
  企业数字化, 页第 of 首页第) or stops inside one (市市场监, 进一步推); the examples show it.
- The examples show how the pages use it. A candidate only spam pages use is `junk` however
  word-like it looks.
- When unsure, leave it out (`obscure`): a word left out costs a little, a wrong one costs every
  user who types its reading.

## Curating a batch

```
python3 lexicon/tools/batches.py --pack <pack.words> --candidates <candidates.tsv> \
    --probe <ime-eval output of the pack's readings> --examples <examples.tsv> --out <dir>
```

writes `<dir>/batch-NNN.tsv`, 200 candidates each, most frequent first, leaving out what the lists
already decide and what the engine already types right alone. `--right-from N` keeps those seen N
times or more anyway: no candidate is a word of the dictionary, so one typed right alone is put
together from shorter words, and a context can undo that (砍一刀 alone, 看一道 after 帮我).
Its columns: word, suggested reading, count in the crawl, pmi, the lesser entropy, the engine's first three candidates for the
reading, and up to three sentences. A curator writes `<dir>/batch-NNN.out.tsv`, a line each:

```
word  add|reject  kind  reading (add only)  note (optional, short)
```

and `python3 lexicon/tools/apply.py <dir>/batch-NNN.out.tsv` checks it against the batch and adds it
to the lists, dated. An add whose reading shares under half its syllables with the suggested one
needs a note saying why (婠婠 wan'wan: 婠 is wan, not guan): a curator that slipped a line gives
each word the next one's reading, which a syllable count alone does not catch.

Two passes keep the reading down: `--brief` batches hold 1000 candidates with the word, reading,
count and the engine's candidates only, where a curator may also answer `unsure` (nothing else on
the line). Most candidates need no sentences: a fragment, a brand of a gambling site or a set
phrase shows as one. apply.py writes the unsure ones to `batch-NNN.unsure.txt`, and
`batches.py --words <that file>` makes the second pass's batches, with the sentences.
