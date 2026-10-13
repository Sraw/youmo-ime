#!/usr/bin/env python3
"""latin.py <latin-counts.tsv> <lm.arpa> <out.words>: the Latin words people type inside Chinese
(iPhone, APP, PDF, bug), as a word pack in the `latin` layer, read as the Latin-letter syllables
(I'P'H'O'N'E), so the engine offers them for the letters typed in lower case.

From latin_counts.py's counts: a word of letters only (digits and C++ cannot be typed as letter
syllables), seen MIN_COUNT times or more on MIN_PAGES pages or more; not one lexicon/reject.tsv
rejects (spam, adult and gambling sites, HTML left in text, what nobody types inside Chinese), in
any spelling, or, rejected as `case`, in that spelling alone (WIFI, where WiFi is kept). One whose
letters are all pinyin (shi, china, ai; not counting 呣, 嗯 and 儿, so NBA is not n'ba) is scored
PINYIN_PENALTY lower: the same letters type Chinese, which should come first unless the text
around says otherwise. Each spelling of a word written in a tenth or more of its uses is a word
of its own: app, APP and App.

Scored on the model's unigram scale: its count's log10, moved by how far the Latin words libime's
model already has (QQ, USB, iPad ...) sit from their own counts, the median of them.
"""
import collections
import math
import os
import re
import statistics
import sys
from pathlib import Path

from common import SHARDS, rows, syllables

MIN_COUNT = 100
MIN_PAGES = 25
MIN_SHARE = 0.1
MAX_LENGTH = 20
PINYIN_PENALTY = float(os.environ.get('PINYIN_PENALTY', '-1'))
LEXICON = Path(__file__).resolve().parent.parent


def shards(counts_path):
    """The FineWeb shards counted, as latin_counts.py's first line names them."""
    with open(counts_path, encoding='utf-8') as f:
        header = f.readline().rstrip('\n')
    names = header[len(SHARDS):].split() if header.startswith(SHARDS) else []
    if not names:
        raise SystemExit(f'{counts_path}: no {SHARDS!r} line first; count again with latin_counts.py')
    return names


def all_pinyin(word, pinyin):
    n = len(word)
    ok = [True] + [False] * n
    for end in range(1, n + 1):
        ok[end] = any(ok[start] and word[start:end] in pinyin for start in range(max(0, end - 6), end))
    return ok[n]


def main(counts_path, arpa_path, out_path):
    pinyin = {s for s in syllables() if s.islower() and s not in ('m', 'n', 'ng', 'r')}
    rejects = list(rows(LEXICON / 'reject.tsv'))
    rejected = {r[0].lower() for r in rejects if r[1:2] != ['case']}
    cased = {r[0] for r in rejects if r[1:2] == ['case']}
    counted = shards(counts_path)
    spellings = collections.defaultdict(dict)
    pages = collections.Counter()
    for word, count, on in rows(counts_path):
        if re.fullmatch(r'[A-Za-z]{2,%d}' % MAX_LENGTH, word):
            spellings[word.lower()][word] = int(count)
            # latin_counts.py's pages are the word's, in any spelling: not to be summed
            pages[word.lower()] = int(on)
    model = {}
    with open(arpa_path, encoding='utf-8') as arpa:
        for line in arpa:
            if line.startswith('\\2-grams'):
                break
            # any whitespace, as ArpaReader splits; no '\n' left on a unigram without a backoff
            f = line.split()
            if len(f) >= 2 and re.fullmatch(r'[A-Za-z]+', f[1]):
                model[f[1]] = float(f[0])
    seen = {w: c for forms in spellings.values() for w, c in forms.items()}
    offsets = [score - math.log10(seen[w]) for w, score in model.items() if seen.get(w, 0) >= MIN_COUNT]
    if not offsets:
        raise SystemExit(f'{arpa_path}: no Latin word of the model seen {MIN_COUNT} times or more in {counts_path}, '
                         'nothing to place the scores by')
    offset = statistics.median(offsets)
    words = []
    for key, forms in spellings.items():
        total = sum(forms.values())
        if total < MIN_COUNT or pages[key] < MIN_PAGES or key in rejected:
            continue
        penalty = PINYIN_PENALTY if all_pinyin(key, pinyin) else 0.0
        for word, count in forms.items():
            if count >= MIN_SHARE * total and word not in cased:
                words.append((word, round(min(-3.0, math.log10(count) + offset + penalty), 2)))
    words.sort(key=lambda w: (-w[1], w[0]))
    named = ', '.join(counted[:-1]) + ' and ' + counted[-1] if len(counted) > 1 else counted[0]
    with open(out_path, 'w', encoding='utf-8') as out:
        out.write('# youmo words 1\n# layer: latin\n')
        out.write('# The Latin words typed inside Chinese, from FineWeb-2 cmn_Hani (ODC-By 1.0) shards %s;\n' % named)
        out.write('# made by lexicon/tools/latin.py, %d words, offset %.2f from %d words of the model.\n'
                  % (len(words), offset, len(offsets)))
        for word, score in words:
            out.write('%s\t%s\t%s\n' % (word, "'".join(word.upper()), score))
    print(f'{len(words)} words; offset {offset:.2f} from {len(offsets)} of the model\'s', file=sys.stderr)


if __name__ == '__main__':
    main(*sys.argv[1:])
