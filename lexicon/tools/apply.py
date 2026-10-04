#!/usr/bin/env python3
"""apply.py <batch-NNN.out.tsv>...: a curator's decisions (lexicon/README.md) into add.tsv and
reject.tsv, dated today. Every word of the batch beside it (batch-NNN.tsv) must be decided once,
and nothing else; a reading must have a syllable a character. `unsure` (a brief batch's) is
written to batch-NNN.unsure.txt for a second look with the sentences, and to neither list.
Nothing is written unless all is well."""
import datetime
import os
import re
import sys

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
TAKE = {'word', 'name', 'slang', 'term'}
LEAVE = {'fragment', 'phrase', 'typo', 'junk', 'obscure'}

def spellings():
    """The engine's syllables, as Syllables.kt lists them: a reading of any other cannot be packed."""
    path = os.path.join(HERE, '..', 'lib', 'ime-core', 'src', 'main', 'kotlin', 'org', 'fcitx', 'fcitx5',
                        'android', 'engine', 'pinyin', 'Syllables.kt')
    with open(path, encoding='utf-8') as f:
        source = f.read()
    listed = source[source.index('SPELLINGS ='):source.index('private val spellings')]
    return set(''.join(re.findall(r'"([^"]*)"', listed)).split())


SYLLABLES = spellings()


def length(word):
    """In UTF-16 units, as the engine counts a word's characters: one of CJK Extension B is two."""
    return len(word.encode('utf-16-le')) // 2


def words(path):
    with open(path, encoding='utf-8') as f:
        return [line.split('\t', 1)[0].strip() for line in f if line.strip() and not line.startswith('#')]


def check(out, seen):
    batch = out.replace('.out.tsv', '.tsv')
    if batch == out or not os.path.exists(batch):
        raise SystemExit(f'{out}: no batch beside it ({batch})')
    asked = set(words(batch))
    add, reject, unsure, errors, here = [], [], [], [], set()
    with open(out, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            if not line.strip() or line.startswith('#'):
                continue
            f_ = [x.strip() for x in line.rstrip('\n').split('\t')]
            word, verdict = f_[0], f_[1] if len(f_) > 1 else ''
            kind = f_[2] if len(f_) > 2 else ''
            where = f'{out}:{n}: {word}'
            if word not in asked:
                errors.append(f'{where}: not in the batch')
            elif word in seen:
                errors.append(f'{where}: decided twice ({seen[word]})')
            seen.setdefault(word, where)
            here.add(word)
            if verdict == 'add':
                reading = f_[3] if len(f_) > 3 else ''
                syllables = reading.split("'")
                if kind not in TAKE:
                    errors.append(f'{where}: kind {kind!r}, not one of {sorted(TAKE)}')
                if len(syllables) != length(word) or not all(s in SYLLABLES for s in syllables):
                    errors.append(f'{where}: reading {reading!r}')
                add.append((word, reading, kind, f_[4] if len(f_) > 4 else ''))
            elif verdict == 'reject':
                if kind not in LEAVE:
                    errors.append(f'{where}: kind {kind!r}, not one of {sorted(LEAVE)}')
                # a note where an add's reading goes, or after it
                reject.append((word, kind, ' '.join(x for x in f_[3:5] if x)))
            elif verdict == 'unsure':
                unsure.append(word)
            else:
                errors.append(f'{where}: {verdict!r}, not add, reject or unsure')
    missing = asked - here
    if missing:
        errors.append(f'{out}: {len(missing)} words of the batch not decided, {sorted(missing)[:5]}...')
    return add, reject, unsure, errors


def main():
    today = datetime.date.today().isoformat()
    adds, rejects, errors, unsure, seen = [], [], [], {}, {}
    for out in sys.argv[1:]:
        a, r, u, e = check(out, seen)
        adds += a
        rejects += r
        errors += e
        unsure[out.replace('.out.tsv', '.unsure.txt')] = u
    if errors or not sys.argv[1:]:
        print('\n'.join(errors) or __doc__, file=sys.stderr)
        raise SystemExit(1)
    # decided before: kept as it was (a changed mind is an edit of the lists, made by hand)
    known = set(words(os.path.join(HERE, 'add.tsv'))) | set(words(os.path.join(HERE, 'reject.tsv')))
    added = [a for a in adds if a[0] not in known]
    rejected = [r for r in rejects if r[0] not in known]
    with open(os.path.join(HERE, 'add.tsv'), 'a', encoding='utf-8') as f:
        for word, reading, kind, note in added:
            f.write(f'{word}\t{reading}\t{kind}\t{today}\t{note}\n')
    with open(os.path.join(HERE, 'reject.tsv'), 'a', encoding='utf-8') as f:
        for word, kind, note in rejected:
            f.write(f'{word}\t{kind}\t{today}\t{note}\n')
    for path, words_ in unsure.items():
        if words_:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(''.join(w + '\n' for w in words_))
        elif os.path.exists(path):
            os.remove(path)
    print(f'{len(added)} added, {len(rejected)} rejected, {sum(map(len, unsure.values()))} unsure, '
          f'{len(adds) + len(rejects) - len(added) - len(rejected)} decided before and left as they were')


if __name__ == '__main__':
    main()
