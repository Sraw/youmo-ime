#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""
Makes two sets of samples whose context runs past what the large sentence model reads (its 103
tokens), so the start it reads from is measured.

    python3 tools/make-long-set.py data/pinyin-web.tsv > data/pinyin-long.tsv

data/pinyin-long.tsv: the web set's lines walked in order, each one's context and run taken as the text
written so far, with a 。 between lines of different turns: web fragments joined, mostly of other pages,
not one text. One line in EVERY becomes a sample, its context the last 110 to 200 chars before its run.

    python3 tools/make-long-set.py --docs <dict_sc.txt> <lm.arpa> ../../docs/*.md > data/pinyin-long-docs.tsv

with libime's dict_sc.txt (app/build/engine-sources/dict) and the engine's model (app/build/engine-sources/lm_mixed/lm.arpa).

data/pinyin-long-docs.tsv: one text each, the help pages of docs/ (this project's own, LGPL) as prose,
code, tables and markup left out. A run of 4 to 12 Han chars that chat.py can read, one in DOCS_EVERY,
becomes a sample once 110 to 200 chars of its page come before it, those chars its context; one with a
word chat.py reads as chat does but a help page does not (行, 得了) is left out.
"""

import os
import re
import sys

EVERY = 10
SHORTEST, LONGEST = 110, 200

HEADER = """\
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
#
# pinyin-web.tsv's runs with a context of 110 to 200 chars, past the 103 tokens the large sentence model
# reads, so the start it reads from is measured: the text before a run is the web set's lines before it
# in order, joined. Made by tools/make-long-set.py.
# One sample per line: input<TAB>expected<TAB>long<TAB>context."""


DOCS_EVERY = 12

DOCS_HEADER = """\
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
#
# Runs of docs/'s help pages with a context of 110 to 200 chars of the same page before them, past the
# 103 tokens the large sentence model reads: one text, where pinyin-long.tsv joins fragments. Made by
# tools/make-long-set.py --docs.
# One sample per line: input<TAB>expected<TAB>long-docs<TAB>context."""


def prose(markdown):
    """A help page's paragraphs and list items as one text, code blocks, tables and markup left out, a 。 after a line with no mark of its own."""
    lines, fenced = [], False
    for line in markdown.splitlines():
        if line.startswith("```"):
            fenced = not fenced
            continue
        line = line.strip()
        if fenced or not line or line.startswith(("|", "<", "!")):
            continue
        line = re.sub(r"^(#+|[-*]|\d+\.)\s+", "", line)
        line = re.sub(r"`([^`]*)`", r"\1", line)
        line = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", line)
        line = line.replace("**", "").strip()
        if re.search(r"[一-鿿]", line):
            lines.append(line if re.search(r"[。！？：；…）」]$", line) else line + "。")
    return "".join(lines)


def docs(dictionary, arpa, pages, every=DOCS_EVERY):
    from chat import PUNCTUATION, RUN, loaders

    words, read = loaders(dictionary, arpa)
    print(DOCS_HEADER)
    seen = 0
    for page in pages:
        text = prose(open(page, encoding="utf-8").read())
        at = 0
        for piece in PUNCTUATION.split(text):
            begin, at = at, at + len(piece)
            if not RUN.match(piece):
                continue
            seen += 1
            want = SHORTEST + (seen * 53) % (LONGEST - SHORTEST + 1)
            if seen % every or begin < want:
                continue
            split = words(piece)
            # chat.py reads these as chat does; a help page's 行 is mostly hang (一行键), its 得了 deliao
            if '行' in split or any(a == '得' and b == '了' for a, b in zip(split, split[1:])):
                continue
            keys = read(piece, text[:begin])
            if keys:
                print(f"{keys}\t{piece}\tlong-docs\t{text[begin - want:begin]}")


def main(path):
    rows = [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8") if l.strip() and not l.startswith("#")]
    written = ""
    last = ""
    print(HEADER)
    for i, row in enumerate(rows):
        context = row[3] if len(row) > 3 else ""
        if last and context.startswith(last):
            before = written[: len(written) - len(last)] + context
        else:
            before = written + ("。" if written else "") + context
        want = SHORTEST + (i * 53) % (LONGEST - SHORTEST + 1)
        if i % EVERY == 0 and len(before) >= want:
            print(f"{row[0]}\t{row[1]}\tlong\t{before[-want:]}")
        last = context + row[1]
        written = before + row[1]


if __name__ == "__main__":
    if sys.argv[1] == "--docs":
        docs(sys.argv[2], sys.argv[3], sys.argv[4:], int(os.environ.get("EVERY", DOCS_EVERY)))
    else:
        main(sys.argv[1])
