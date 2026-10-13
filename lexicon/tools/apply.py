#!/usr/bin/env python3
"""apply.py <batch-NNN.out.tsv>...: a curator's decisions (lexicon/README.md) into add.tsv and
reject.tsv, dated today. Every word of the batch beside it (batch-NNN.tsv) must be decided once,
and nothing else; a reading must have a syllable a character. `unsure` (a brief batch's) is
written to batch-NNN.unsure.txt for a second look with the sentences, and to neither list.
Nothing is written unless all is well."""
import datetime
import os
import sys

import common

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
TAKE = {'word', 'name', 'slang', 'term'}
LEAVE = {'fragment', 'phrase', 'typo', 'junk', 'obscure', 'case'}
SYLLABLES = common.syllables()


def length(word):
    """In UTF-16 units, as the engine counts a word's characters: one of CJK Extension B is two."""
    return len(word.encode('utf-16-le')) // 2


def words(path):
    return [r[0].strip() for r in common.rows(path)]


def suggested(path):
    """The batch's suggested reading of each word."""
    return {r[0].strip(): r[1].strip().split("'") for r in common.rows(path) if len(r) > 1}


def parse_decisions(out, seen):
    """A curator's decisions on a batch, checked against it; each word is noted in `seen`, across batches."""
    batch = out.replace('.out.tsv', '.tsv')
    if batch == out or not os.path.exists(batch):
        raise SystemExit(f'{out}: no batch beside it ({batch})')
    asked = set(words(batch))
    suggestion = suggested(batch)
    add, reject, unsure, errors, here = [], [], [], [], set()
    with open(out, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            if not line.strip() or line.startswith('#'):
                continue
            fields = [x.strip() for x in line.rstrip('\n').split('\t')]
            word, verdict = fields[0], fields[1] if len(fields) > 1 else ''
            kind = fields[2] if len(fields) > 2 else ''
            where = f'{out}:{n}: {word}'
            if word not in asked:
                errors.append(f'{where}: not in the batch')
            elif word in seen:
                errors.append(f'{where}: decided twice ({seen[word]})')
            seen.setdefault(word, where)
            here.add(word)
            if verdict == 'add':
                reading = fields[3] if len(fields) > 3 else ''
                syllables = reading.split("'")
                if kind not in TAKE:
                    errors.append(f'{where}: kind {kind!r}, not one of {sorted(TAKE)}')
                note = fields[4] if len(fields) > 4 else ''
                if len(syllables) != length(word) or not all(s in SYLLABLES for s in syllables):
                    errors.append(f'{where}: reading {reading!r}')
                elif word in suggestion and not note:
                    # a curator that slipped a line gives each word the next one's reading
                    agree = sum(a == b for a, b in zip(syllables, suggestion[word]))
                    if agree * 2 < len(syllables):
                        said = "'".join(suggestion[word])
                        errors.append(f'{where}: reading {reading!r} far from the suggested '
                                      f'{said!r}, with no note saying why')
                add.append((word, reading, kind, note))
            elif verdict == 'reject':
                if kind not in LEAVE:
                    errors.append(f'{where}: kind {kind!r}, not one of {sorted(LEAVE)}')
                # a note where an add's reading goes, or after it
                reject.append((word, kind, ' '.join(x for x in fields[3:5] if x)))
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
        batch_adds, batch_rejects, batch_unsure, batch_errors = parse_decisions(out, seen)
        adds += batch_adds
        rejects += batch_rejects
        errors += batch_errors
        unsure[out.replace('.out.tsv', '.unsure.txt')] = batch_unsure
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
    for path, unsure_words in unsure.items():
        if unsure_words:
            with open(path, 'w', encoding='utf-8') as f:
                f.write(''.join(w + '\n' for w in unsure_words))
        elif os.path.exists(path):
            os.remove(path)
    print(f'{len(added)} added, {len(rejected)} rejected, {sum(map(len, unsure.values()))} unsure, '
          f'{len(adds) + len(rejects) - len(added) - len(rejected)} decided before and left as they were')


if __name__ == '__main__':
    main()
