#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""
Makes two sets from LCCC-base's validation split (MIT; research use, so they only measure and help
choose parameters; nothing the app ships is trained on it), with make-chat-set.py's runs and readings:

    python3 tools/make-dialog-sets.py lccc_base_valid.jsonl.gz <dict_sc.txt> <lm_sc.arpa> data/pinyin-dialog.tsv data/predict/chat.tsv

pinyin-dialog.tsv: a run typed in a later turn of a dialogue, its context the same speaker's turn
  before (what the phone committed last, a message ago) and the turn's earlier part: what the
  reading needs from more than the run itself. Runs are kept by another 1/25 than pinyin-chat's.
predict/chat.tsv (apart: not an input set): what follows after the first word of a run (联想): `context<TAB>next`, the
  context being the turn up to there; any offer `next` starts with saves keys.
"""
import gzip
import hashlib
import importlib.util
import json
import os
import re
import sys

spec = importlib.util.spec_from_file_location('chat', os.path.join(os.path.dirname(__file__), 'make-chat-set.py'))
chat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chat)

KEEP_ONE_IN = 25
BUCKET = 1  # pinyin-chat.tsv keeps bucket 0
CONTEXT = 64

DIALOG_HEADER = """# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: Copyright (c) 2020 lemon234071 (LCCC, https://github.com/thu-coai/CDial-GPT)
#
# Chat runs typed after the same speaker's previous turn in a dialogue, from LCCC-base's validation
# split (Wang et al., 2020; MIT, research use: the set only measures and helps choose parameters;
# nothing the app ships is trained on it). Made by
# tools/make-dialog-sets.py; a run's context is that previous turn, a space, then the earlier part
# of its own turn, at most the last 64 chars.
# One sample per line: input<TAB>expected<TAB>dialog<TAB>context."""

PREDICT_HEADER = """# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: Copyright (c) 2020 lemon234071 (LCCC, https://github.com/thu-coai/CDial-GPT)
#
# What follows the first word of a chat run (联想), from LCCC-base's validation split (MIT,
# research use: the set only measures and helps choose parameters; nothing the app ships is
# trained on it). Made by tools/make-dialog-sets.py.
# One sample per line: context<TAB>next, the context the turn up to and with that word."""


def kept(part):
    return int(hashlib.md5(part.encode()).hexdigest(), 16) % KEEP_ONE_IN == BUCKET


def turns(path):
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            dialogue = [re.sub(r'\s+', '', t) for t in json.loads(line)]
            for i, turn in enumerate(dialogue):
                yield (dialogue[i - 2] if i >= 2 else None), turn


def main(source, dictionary, arpa, dialog_out, predict_out):
    split, read = chat.loaders(dictionary, arpa)
    seen = set()
    dialog, predict = [], []
    for previous, turn in turns(source):
        before = ''
        for part in chat.PUNCTUATION.split(turn):
            if chat.RUN.match(part) and part not in seen and kept(part):
                seen.add(part)
                words = split(part)
                if words:
                    if len(words) > 1:
                        head = words[0]
                        predict.append(f'{(before + head)[-CONTEXT:]}\t{part[len(head):]}')
                    syllables = [read(w, ''.join(words[:i])) for i, w in enumerate(words)]
                    if previous and None not in syllables:
                        context = (previous + (' ' + before if before else ''))[-CONTEXT:]
                        dialog.append('\t'.join([''.join(syllables).replace(' ', ''), part, 'dialog', context]))
            before += part
    with open(dialog_out, 'w', encoding='utf-8') as f:
        f.write(DIALOG_HEADER + '\n' + '\n'.join(dialog) + '\n')
    with open(predict_out, 'w', encoding='utf-8') as f:
        f.write(PREDICT_HEADER + '\n' + '\n'.join(predict) + '\n')
    print(f'{len(dialog)} dialog samples, {len(predict)} prediction samples', file=sys.stderr)


if __name__ == '__main__':
    main(*sys.argv[1:])
