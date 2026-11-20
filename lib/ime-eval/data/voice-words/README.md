# Voice's user words: cases

What voice's homophone step (`VoiceChoice.pick`, `VoiceHomophones`) is measured on, from voice-eval's endpoint
segments of the 0.6.1 voice pack (`seg-<set>-cur-p242.jsonl`: aishell, ali-long, cv-test, newwords, ascend, fleurs,
cv-test-acc, ali-test-long-061).

- `cases.tsv`: one user word a row, with the clip it is tried on. `type` is `wanted` (the recognizer heard the
  reference's word as a homophone: voice should write the word), `uword` (a right word made a dictionary homophone:
  voice should leave the right one) or `uname` (a right two-character word made a common given name read alike, from
  the given names of Chinese-Names-Corpus's three-character names). `heard` is what the recognizer wrote there.
- `words-wanted.tsv`, `words-unwanted.tsv`: the words of the `wanted` rows, and of the others, as `--words` reads them
  (`text`, pinyin, `added` or `learned`).

Whole sets with all the words at once (the CER should fall with the wanted ones and rise little with the others):

    ime-eval voice-post seg-*.jsonl --words words-wanted.tsv --data app/build/generated/engine-assets [--bonus 1.5,1]

The polish's marks on what it now sees: `--picked <dir>` writes the segments with the words put in, voice-eval's
`tag_segs.py` tags them, then `ime-eval voice-post <dir>/seg-*.jsonl --source tagged --tagger polish-tagger.json`.

The words and `heard` texts are short pieces of the sets' reference and recognized transcripts, each under its set's
licence: AISHELL-1 (Apache-2.0), Common Voice (CC0-1.0), FLEURS (CC-BY-4.0), ASCEND and AliMeeting (CC-BY-SA-4.0),
`newwords` voice-eval's own synthesized clips. The given names of the `uname` rows are from Chinese-Names-Corpus
(github.com/wainshine/Chinese-Names-Corpus, Apache-2.0).
