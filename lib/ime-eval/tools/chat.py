# SPDX-License-Identifier: LGPL-2.1-or-later
# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
"""
What make-chat-set.py, make-dialog-sets.py and make-mixed-set.py share: the runs they cut chat
text into, which runs they keep, and how a run is typed. A module of its own, as a script's
name cannot be imported; they and make-long-set.py import it by name from this directory.
"""
import re
from collections import defaultdict

# the reading a chat line means by a polyphonic word, checked by hand for the words that most keep a run out
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
# 了 and 着 after a verb and 不, where they say it cannot be done (去不了, 摸不着)
CANNOT = {'了': 'liao', '着': 'zhao'}
# a word read otherwise before these (系鞋带); None where either reading is as likely, which leaves the run out
AHEAD = {'系': (('鞋带', '领带', '安全带'), 'ji'), '还': (('给',), None)}
# a run in KEEP_ONE_IN is kept by its text's hash: pinyin-chat.tsv bucket 0, pinyin-dialog.tsv bucket 1
KEEP_ONE_IN = 25
MAX_WORD = 8
# the most of what came before a run kept as its context, in chars
CONTEXT = 64
RUN = re.compile(r'^[一-鿿]{4,12}$')
PUNCTUATION = re.compile(r'([，。！？、,.!?~～…；;：:\s“”"（）()]+)')


def loaders(dictionary, arpa):
    """(words of a run, its input typed after what came before it, or None) from the dictionary and model."""
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

    def reading(word, before, after):
        if word == '得' and before.endswith(MUST):
            return 'dei'
        if word in CANNOT and len(before) > 1 and before.endswith('不'):
            return CANNOT[word]
        if word in AHEAD and after.startswith(AHEAD[word][0]):
            return AHEAD[word][1]
        if len(readings[word]) == 1:
            return next(iter(readings[word]))
        return READINGS.get(word)

    def read(run, before=''):
        split = words(run)
        if not split:
            return None
        syllables = [reading(word, before + ''.join(split[:i]), ''.join(split[i + 1:]))
                     for i, word in enumerate(split)]
        return None if None in syllables else ''.join(syllables).replace(' ', '')

    return words, read
