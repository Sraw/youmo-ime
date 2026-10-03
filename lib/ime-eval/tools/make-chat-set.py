#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""
Makes data/pinyin-chat.tsv from LCCC-base's validation split (MIT; its README asks for research
use, so nothing the app ships learns from LCCC):

    python3 tools/make-chat-set.py lccc_base_valid.jsonl.gz <dict_sc.txt> <lm_sc.arpa> > data/pinyin-chat.tsv

with app/build/engine-sources/{dict/dict_sc.txt,lm_sc/lm_sc.arpa} as the dictionary and model.
Each turn is cut at punctuation; a run of 4 to 12 Han chars becomes a sample when its text hashes
into the kept 1/25, the run's earlier part of the same turn being its context. The run is split
into the dictionary's words the way the model finds likeliest (the sum of unigram scores), and
its input is their readings: a word with more than one reading takes the one of READINGS (得 the
one of a must after a word of MUST), and a run with a word READINGS does not settle is left out
rather than guessed.
"""
import gzip
import hashlib
import json
import re
import sys
from collections import defaultdict

# the reading a chat line means by a word the dictionary gives more than one, looked at by hand
# for the words that most often keep a run out
READINGS = {
    '的': 'de', '了': 'le', '不': 'bu', '都': 'dou', '还': 'hai', '这': 'zhe', '没': 'mei',
    '那': 'na', '会': 'hui', '呢': 'ne', '说': 'shuo', '能': 'neng', '给': 'gei', '哦': 'o',
    '么': 'me', '和': 'he', '大': 'da', '得': 'de', '家': 'jia', '她': 'ta', '被': 'bei',
    '谁': 'shei', '种': 'zhong', '行': 'xing', '哪': 'na', '着': 'zhe', '喝': 'he', '胖': 'pang',
    '咋': 'za', '阿': 'a', '约': 'yue', '见': 'jian', '亲': 'qin', '呗': 'bei', '从': 'cong',
    '车': 'che', '诶': 'ei', '嗯': 'en', '地': 'de', '儿': 'er', '喔': 'o', '无': 'wu', '呐': 'na',
    '句': 'ju', '咱': 'zan', '女': 'nv', '差': 'cha', '嗨': 'hai', '吖': 'ya', '弄': 'nong',
    '俩': 'lia', '红': 'hong', '抢': 'qiang', '难': 'nan', '份': 'fen', '些': 'xie', '呆': 'dai',
    '转': 'zhuan', '信': 'xin', '强': 'qiang', '追': 'zhui', '洗': 'xi', '脚': 'jiao', '系': 'xi',
    '卡': 'ka', '汤': 'tang', '骑': 'qi', '嘞': 'lei', '鸟': 'niao', '读': 'du', '提': 'ti',
    '妳': 'ni', '跳': 'tiao', '臭': 'chou', '勒': 'le', '攒': 'zan', '六': 'liu',
    '还有': 'hai you', '成都': 'cheng du', '那边': 'na bian', '睡着': 'shui zhao', '咱俩': 'zan lia',
    '赚钱': 'zhuan qian', '受不了': 'shou bu liao', '暖和': 'nuan huo', '谁说': 'shei shuo',
    '那时候': 'na shi hou',
}
# 得 is dei after these, as in 还得 / 就得 / 也得, where it says what has to be
MUST = ('还', '也', '就', '都', '那', '总', '非', '可', '你', '我', '他', '她', '咱')
HEADER = """\
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: Copyright (c) 2020 lemon234071 (LCCC, https://github.com/thu-coai/CDial-GPT)
#
# Chat lines as people type them: runs of 4 to 12 Han chars from LCCC-base's validation split
# (Wang et al., 2020: Weibo and other chat, MIT; its README asks for research use, so nothing the
# app ships learns from it). Picked and given readings for this project by
# tools/make-chat-set.py; the text is kept as its writers typed it, slips and all, so a first
# choice is sometimes judged wrong for being better written. Measures what a change does to chat
# next to pinyin.tsv.
# One sample per line: input<TAB>expected<TAB>chat[<TAB>context], the context being what came
# before the run in the same turn."""
KEEP_ONE_IN = 25
MAX_WORD = 8
CONTEXT = 64
RUN = re.compile(r'^[一-鿿]{4,12}$')
PUNCTUATION = re.compile(r'([，。！？、,.!?~～…；;：:\s“”"（）()]+)')


def loaders(dictionary, arpa):
    """(words of a run, reading of a word after what came before it) from the dictionary and model."""
    readings = defaultdict(set)
    with open(dictionary, encoding='utf-8') as f:
        for line in f:
            fields = line.split()
            if len(fields) >= 2:
                readings[fields[0]].add(fields[1].replace("'", ' '))
    unigrams = {}
    with open(arpa, encoding='utf-8') as f:
        section = None
        for line in f:
            if line.startswith('\\'):
                section = line.strip()
                if section == '\\2-grams:':
                    break
            elif section == '\\1-grams:':
                fields = line.rstrip('\n').split('\t')
                if len(fields) >= 2:
                    unigrams[fields[1]] = float(fields[0])
    unknown = unigrams['<unk>']

    def words(run):
        best = [float('-inf')] * (len(run) + 1)
        start = [0] * (len(run) + 1)
        best[0] = 0.0
        for end in range(1, len(run) + 1):
            for begin in range(max(0, end - MAX_WORD), end):
                word = run[begin:end]
                if word in readings and best[begin] + unigrams.get(word, unknown) > best[end]:
                    best[end] = best[begin] + unigrams.get(word, unknown)
                    start[end] = begin
        if best[-1] == float('-inf'):
            return None
        split, end = [], len(run)
        while end > 0:
            split.append(run[start[end]:end])
            end = start[end]
        return split[::-1]

    def reading(word, before):
        if word == '得' and before.endswith(MUST):
            return 'dei'
        if len(readings[word]) == 1:
            return next(iter(readings[word]))
        return READINGS.get(word)

    return words, reading


def main(chat, dictionary, arpa):
    words, reading = loaders(dictionary, arpa)
    print(HEADER)
    seen = set()
    with gzip.open(chat, 'rt', encoding='utf-8') as f:
        for line in f:
            for turn in json.loads(line):
                before = ''
                # LCCC puts a space between chars; any other blank, a tab say, must not reach a line
                for part in PUNCTUATION.split(re.sub(r'\s+', '', turn)):
                    if RUN.match(part) and part not in seen and \
                            int(hashlib.md5(part.encode()).hexdigest(), 16) % KEEP_ONE_IN == 0:
                        seen.add(part)
                        split = words(part)
                        syllables = split and [reading(word, ''.join(split[:i])) for i, word in enumerate(split)]
                        if syllables and None not in syllables:
                            sample = [''.join(syllables).replace(' ', ''), part, 'chat']
                            if before:
                                sample.append(before[-CONTEXT:])
                            print('\t'.join(sample))
                    before += part


if __name__ == '__main__':
    main(*sys.argv[1:])
