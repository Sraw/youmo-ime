#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""
Makes data/pinyin-mixed.tsv: Chinese with an English word or code in it, typed in one go as people
do (我用iPhone拍的 as `woyongiphonepaide`), from the same held-out pages as pinyin-web.tsv:

    python3 tools/make-mixed-set.py <crawl and files> held-out.jsonl.gz <dict_sc.txt> <lm_sc.arpa> > data/pinyin-mixed.tsv

A sample is a run of 2 to 8 Han chars, one Latin word (letters, then letters or digits), and 1 to
8 Han chars, cut at punctuation, one run in KEEP_ONE_IN; the Han parts read as make-chat-set.py
reads them (a run with a word it cannot settle left out), the Latin typed in lower case and
expected as written.
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

KEEP_ONE_IN = 3
RUN = re.compile(r'([一-鿿]{2,8})([A-Za-z][A-Za-z0-9]{1,14})([一-鿿]{1,8})')
HEADER = """\
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
#
# Chinese with a Latin word in it, typed in one go, from the chat-like pages of CommonCrawl {}
# (held out as pinyin-web.tsv). Made by tools/make-mixed-set.py; reported, not a release gate.
# One sample per line: input<TAB>expected<TAB>mixed"""


def main(source, path, dictionary, arpa):
    words, reading = chat.loaders(dictionary, arpa)

    def read(run, before=''):
        split = words(run)
        syllables = split and [reading(word, before + ''.join(split[:i])) for i, word in enumerate(split)]
        return None if not syllables or None in syllables else ''.join(syllables).replace(' ', '')

    print(HEADER.format(source))
    seen = set()
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            for text in json.loads(line):
                for part in chat.PUNCTUATION.split(re.sub(r'\s+', '', text)):
                    m = RUN.fullmatch(part)
                    if not m or part in seen or int(hashlib.md5(part.encode()).hexdigest(), 16) % KEEP_ONE_IN:
                        continue
                    seen.add(part)
                    head, latin, tail = m.groups()
                    a, b = read(head), read(tail, head)
                    if a and b:
                        print(f'{a}{latin.lower()}{b}\t{part}\tmixed')


if __name__ == '__main__':
    main(*sys.argv[1:])
