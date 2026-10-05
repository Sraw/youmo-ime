#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""
Makes data/pinyin-collide.tsv: what the new-word layer must not take. Each sample is a common word
of the dictionary that a curated new word reads the same as (打字 beside 搭子, 设计 beside 社稷 ...),
typed after the text before it in a sentence of pages no model or curator saw. The new-word set
rewards a layer for coming first; this one, for staying out of the way.

    python3 tools/make-collision-set.py words <add.tsv> <dict>... <lm.arpa> > collide-words.txt
    ime-dict-tool examples -o collide-examples.tsv --only collide-words.txt --per-word 3 <held-out shards>
    python3 tools/make-collision-set.py set <add.tsv> <dict>... <collide-examples.tsv> > data/pinyin-collide.tsv

The held-out shards are a crawl's WET files the engine data never took (`ime-dict-tool cc --from`
past engine-data.sh's FROM + FILES, then `clean`). A dictionary word is taken when it is
common (unigram log10 at least MIN_UNIGRAM), has one reading in the dictionaries, so the sentence
reads it as the new word does, and is no curated word itself. A sample's context is what comes
before it in its sentence, up to CONTEXT characters; one at the sentence's start has none. An
occurrence whose first or last character makes a dictionary word with the one beside it is passed
over (求得 in 需求得到 is no 求得). Each
word gives at most PER_WORD samples.
"""
import sys
from collections import defaultdict

MIN_UNIGRAM = -5.5
CONTEXT = 12
PER_WORD = 2


def curated(path):
    words = {}
    for line in open(path, encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        f = line.rstrip('\n').split('\t')
        words[f[0]] = f[1]
    return words


def readings(paths):
    by_word = defaultdict(set)
    for path in paths:
        for line in open(path, encoding='utf-8'):
            f = line.split()
            if len(f) >= 2:
                by_word[f[0]].add(f[1])
    return by_word


def unigrams(path, wanted):
    scores = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('\\1-grams'):
                break
        for line in f:
            if line.startswith('\\'):
                break
            p = line.split('\t')
            if len(p) >= 2 and p[1] in wanted:
                scores[p[1]] = float(p[0])
    return scores


def collisions(new, dicts):
    """dictionary word -> the new words that read as it does"""
    by_reading = defaultdict(list)
    for word, reading in new.items():
        by_reading[reading].append(word)
    out = {}
    for word, rs in dicts.items():
        if len(rs) == 1 and word not in new and len(word) >= 2:
            (reading,) = rs
            if reading in by_reading:
                out[word] = by_reading[reading]
    return out


def occurrences(text, word):
    at = text.find(word)
    while at >= 0:
        yield at
        at = text.find(word, at + 1)


def alone(text, at, length, dicts):
    end = at + length
    return not (at > 0 and text[at - 1:at + 1] in dicts) and not (end < len(text) and text[end - 1:end + 1] in dicts)


def main():
    mode, add, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
    new = curated(add)
    dicts = readings(rest[:-1])
    common = collisions(new, dicts)
    if mode == 'words':
        scores = unigrams(rest[-1], set(common))
        for word in sorted(common):
            if scores.get(word, -99) >= MIN_UNIGRAM:
                print(word)
        return
    print('# SPDX-License-Identifier: LGPL-2.1-or-later')
    print('# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors')
    print('# made by tools/make-collision-set.py: input, expected, tag, context')
    for line in open(rest[-1], encoding='utf-8'):
        f = line.rstrip('\n').split('\t')
        word, sentences = f[0], f[1:]
        if word not in common:
            continue
        reading = next(iter(dicts[word])).replace("'", '')
        taken = 0
        for sentence in sentences:
            at = next((i for i in occurrences(sentence, word) if alone(sentence, i, len(word), dicts)), -1)
            if at < 0 or taken == PER_WORD:
                continue
            taken += 1
            context = sentence[max(0, at - CONTEXT):at].replace('\t', ' ')
            print(f'{reading}\t{word}\tcollide\t{context}' if context else f'{reading}\t{word}\tcollide')


if __name__ == '__main__':
    main()
