#!/usr/bin/env python3
"""latin_counts.py <fineweb shard.parquet>...: how often each Latin word is written inside Chinese
text, in each spelling (iPhone, APP, app): a word with a Han character on one side or the other,
a space between allowed, so a sentence of English is not counted, only words Chinese is typed
around. Writes latin-counts.tsv here, `word<TAB>count<TAB>pages`, of those seen 3 times or more.
For lexicon/tools/latin.py."""
import collections
import re
import sys
from multiprocessing import Pool

import pyarrow.parquet as pq

HAN = '\\u3400-\\u4dbf\\u4e00-\\u9fff'
WORD = r'([A-Za-z][A-Za-z0-9]*(?:[+#]+)?)'
# a whole word: not the end of a longer token (foo.bar, a/b, x_y), nor the start of one
WHOLE_BEFORE = r'(?<![A-Za-z0-9./_@:-])'
WHOLE_AFTER = r'(?![A-Za-z0-9./_@-])'
TOKEN = re.compile(r'(?:(?<=[%s])|(?<=[%s] ))%s%s|%s%s(?=[%s]| [%s])'
                   % (HAN, HAN, WORD, WHOLE_AFTER, WHOLE_BEFORE, WORD, HAN, HAN))


def count(job):
    path, group = job
    texts = pq.ParquetFile(path).read_row_group(group, columns=['text']).column('text').to_pylist()
    words, pages = collections.Counter(), collections.Counter()
    for text in texts:
        seen = set()
        for m in TOKEN.finditer(text):
            w = m.group(1) or m.group(2)
            if len(w) <= 24:
                words[w] += 1
                seen.add(w)
        pages.update(seen)
    return words, pages


if __name__ == '__main__':
    jobs = [(p, g) for p in sys.argv[1:] for g in range(pq.ParquetFile(p).num_row_groups)]
    words, pages = collections.Counter(), collections.Counter()
    with Pool(6) as pool:
        for w, p in pool.imap_unordered(count, jobs):
            words.update(w)
            pages.update(p)
    with open('latin-counts.tsv', 'w') as out:
        for w, n in words.most_common():
            if n >= 3:
                out.write(f'{w}\t{n}\t{pages[w]}\n')
