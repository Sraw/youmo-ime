#!/usr/bin/env python3
"""batches.py: candidates for a curator (lexicon/README.md), in batches of 200.

  batches.py --pack P --candidates C --probe O --examples E --out DIR [--size N] [--lexicon DIR]
             [--brief] [--words W] [--first N]

P: a word pack, the candidates with their suggested readings (`ime-dict-tool pack`).
C: the candidates' numbers (`ime-dict-tool words`).
O: `ime-eval pinyin` over P's readings, a line each in P's order (the pinyin without its
   apostrophes as the input): what the engine types for the reading now.
E: `ime-dict-tool examples` over the same pages.
A candidate the engine already types right alone, or one the lists decide, is left out: adding
the first changes nothing, and the second was decided. The rest go most frequent first.
--brief: the first pass, 1000 a batch without the sentences (a fifth of the reading), where a
curator may say `unsure`; --words: only the words a file lists (the unsure ones, for a second
pass with the sentences); --first: only the N most frequent.
"""
import argparse
import math
import os


def rows(path):
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            yield line.rstrip('\n').split('\t')


def decided(lexicon):
    words = set()
    for name in ('add.tsv', 'reject.tsv'):
        path = os.path.join(lexicon, name)
        if os.path.exists(path):
            words.update(r[0] for r in rows(path))
    return words


def main():
    a = argparse.ArgumentParser()
    for flag in ('--pack', '--candidates', '--probe', '--out'):
        a.add_argument(flag, required=True)
    a.add_argument('--examples')
    a.add_argument('--size', type=int)
    a.add_argument('--brief', action='store_true')
    a.add_argument('--words')
    a.add_argument('--first', type=int)
    a.add_argument('--lexicon', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
    args = a.parse_args()
    size = args.size if args.size is not None else (1000 if args.brief else 200)
    if size < 1 or not (args.brief or args.examples):
        raise SystemExit('--size: 1 or more; --examples: wanted unless --brief')
    only = None
    if args.words:
        with open(args.words, encoding='utf-8') as f:
            only = {line.split('\t', 1)[0].strip() for line in f if line.strip() and not line.startswith('#')}

    pack = [(r[0], r[1]) for r in rows(args.pack)]
    with open(args.probe, encoding='utf-8') as f:
        probe = [line.rstrip('\n').split('\t') for line in f]
    if len(probe) != len(pack):
        raise SystemExit(f'{args.probe}: {len(probe)} lines, the pack {len(pack)} words')
    skip = decided(args.lexicon)
    todo = {}
    for (word, reading), out in zip(pack, probe):
        if out[0] != reading.replace("'", ''):
            raise SystemExit(f'{args.probe}: {out[0]} where the pack reads {word} {reading}')
        top = out[2:5]
        if word not in skip and (not top or top[0] != word) and (only is None or word in only):
            todo[word] = [reading, '/'.join(top)]
    for r in rows(args.candidates):
        if r[0] in todo:
            entropy = min(float(r[4]), float(r[5]))
            todo[r[0]] += [int(r[1]), r[3], '-' if math.isnan(entropy) else f'{entropy:.2f}']
    examples = {} if args.brief else {r[0]: r[1:] for r in rows(args.examples) if r[0] in todo}

    order = sorted((w for w in todo if len(todo[w]) == 5), key=lambda w: (-todo[w][2], w))[:args.first]
    os.makedirs(args.out, exist_ok=True)
    for b in range(0, len(order), size):
        path = os.path.join(args.out, f'batch-{b // size + 1:03d}.tsv')
        with open(path, 'w', encoding='utf-8') as f:
            if args.brief:
                f.write('# word\treading\tcount\tengine\n')
            else:
                f.write('# word\treading\tcount\tpmi\tentropy\tengine\texamples...\n')
            for w in order[b:b + size]:
                reading, engine, count, pmi, entropy = todo[w]
                if args.brief:
                    f.write('\t'.join([w, reading, str(count), engine]) + '\n')
                    continue
                said = [s if len(s) <= 60 else s[:57] + '...' for s in examples.get(w, [])]
                f.write('\t'.join([w, reading, str(count), pmi, entropy, engine] + said) + '\n')
    print(f'{len(order)} candidates for a curator in {math.ceil(len(order) / size)} batches; '
          f'{len(pack) - len(todo)} typed right alone, decided or not asked for')


if __name__ == '__main__':
    main()
