#!/usr/bin/env python3
"""stroke_shapes.py <stroke-order-jian.json> <chaizi-jt.txt> <stroke-order.tsv> > new.tsv: each stroke of
lexicon/stroke-order.tsv by its shape, from cnchar's letters (the user, 2026-11-02: the card shows a
stroke's shape, not only its kind). Run once to make the file; it is kept by hand since, so a run now
is a check of what the rules would give: a line whose kinds are not cnchar's (a stroke corrected by
hand, 皙 德 …) is printed as it is, and a diff shows what was changed by hand.

cnchar (github.com/theajack/cnchar at b8397db3e, src/cnchar/plugin/order/dict/stroke-order-jian.json, MIT)
writes a stroke as one of 26 letters, some for two shapes: e 横撇 or 横钩, b 竖折 or 竖弯, y 斜钩 or 卧钩,
w 横撇弯钩 or 横折折折钩, o 横斜钩 or 横折弯钩, v 横折折 or 横折弯, x 竖折撇 or 竖折折; d is a 点 (心's
first, 宀's second). They are told apart by the part of the character a stroke is in, as
《GB13000.1字符集汉字折笔规范》 shows its shapes: the rules below. chaizi-jt.txt (kfcd/chaizi, as the build
downloads it) says which parts a character has, for 风's and 四's.

A line of the tsv is `字<TAB>strokes[<TAB>note]`, the strokes the standard's digits (as the file was
before, 1 横 2 竖 3 撇 4 点 5 折) or the shapes' codes (Unicode's CJK Strokes names, as it is now): the
shapes made must be of the line's kinds, or the line is kept.
"""
import json
import re
import sys

PLAIN = {'j': 'H', 'i': 'T', 'f': 'S', 'g': 'SG', 's': 'P', 'k': 'D', 'd': 'D', 'l': 'N', 'c': 'HZ',
         'r': 'HZG', 'p': 'HZT', 'a': 'HZZP', 'q': 'HZZZ', 'h': 'ST', 'u': 'SWG', 'z': 'SZWG', 'n': 'PZ',
         'm': 'PD', 't': 'WG'}

# e, 横撇 HP or 横钩 HG, by the part it is in: the part's letters, {its stroke: shape}; of the parts a
# stroke is in, the longest's (宀 kde before 又 ek: 宰 旁 寝)
E = [
    ('de', {1: 'HG'}),               # 冖 宀 (写 军 宝 骨, 雨's top)
    ('dek', {1: 'HG'}),              # 冖 宀 before an 又-like part (旁 宰 寝)
    ('deg', {1: 'HG'}),              # 冖 before 水 (泶 荥)
    ('sesl', {1: 'HG'}),             # 欠
    ('sesk', {1: 'HG'}),             # 欠, its 捺 a 点 (趑 阙 劂)
    ('seh', {1: 'HG'}),              # 饣
    ('segsk', {1: 'HG'}),            # 尔
    ('fjes', {2: 'HG'}),             # 虍
    ('ekkjsk', {0: 'HG'}),           # 买 (卖 读 实)
    ('esfel', {0: 'HG', 3: 'HP'}),   # 皮
    ('esfek', {0: 'HG', 3: 'HP'}),   # 皮, its 捺 a 点 (颇)
    ('ekeg', {0: 'HP', 2: 'HG'}),    # 予 矛
    ('efj', {0: 'HG'}),              # 疋 (旋 胥 蛋 疏)
    ('el', {0: 'HP'}),               # 又 夂 久 之 支
    ('ek', {0: 'HP'}),               # 又 and 巠's top at a part's start (劝 对 经)
    ('se', {1: 'HP'}),               # 𠂊 夕 (鱼 免 角 危 名 臽)
    ('ke', {1: 'HP'}),               # 礻 衤 令 今
    ('ne', {1: 'HP'}),               # 互 彑 (彖 彝)
    ('re', {1: 'HP'}),               # 永
    ('eg', {0: 'HP'}),               # 子 了 孑 (函 亟 丞)
    ('ge', {1: 'HP'}),               # 水
]
# o: 横斜钩 in these and what is made of them, else 横折弯钩 (九 乙 几 瓦 丸 凡 亢 乞)
# (虱's 乁 is 卂's, but chaizi has 乁 in 几 too: 虱 by name)
HXG_PARTS = set('风凤飞气卂夙虱')
# b: 竖弯 in these (their 丿 竖弯 一), else 竖折 (山 出 亡 世 区 凶 母 屯)
SW_PARTS = set('四西酉覀')
# the characters corrected by hand before the shapes were (cnchar's letters for them wrong)
FIXED = {
    '弢': 'HZ H SZWG SZ S S HP N',
    '德': 'P P S H S S HZ S S H H D BXG D D',
    '撅': 'H SG T H P D P H SZ S P P HG P N',
    '炽': 'D P P D S HZ H P D',
    '皙': 'H S P D P P H S P S HZ H H',
    '膝': 'P HZG H H H S P D P N SG D T P D',
    '黏': 'P H S P D P D SG D T P D S H S HZ H',
}
# what no rule settles (hanzipi: 承 52111534, its 6th a 折, 横撇 as 丞's), and 匹, whose 竖弯 cnchar
# writes as a 竖弯钩 (u): 匸 closes it, as 四's; 鸠's 九, on the left, a 横折提 (GF 2001's example, as 颓's)
OVERRIDE = {'承': 'HP SG H H H HP P N', '匹': 'H P SW SZ', '鸠': 'P HZT P HZG D SZWG H'}
# what 《GB13000.1字符集汉字折笔规范》's table of turning strokes (GF 2001-2001) gives otherwise than the
# letter does, by its example characters, where 楷体 as taught writes it so too: in a character with one of
# the parts, wherever the part's letters are, its stroke so: 敢's 横折 (cnchar e); 互 母 乐 / 发 牙 降's 竖折
# (cnchar n, 撇折), and 彑 舛 韋's, as 互 降's. Not followed, the standard's 宋体 only: 要's 横折钩 (taught a
# 横折), 执's 横斜钩 (taught 丸's 横折弯钩)
GF = [
    ('敢', 'ejffjj', 0, 'HZ'),
    ('互', 'jnej', 1, 'SZ'),
    ('彑彖彘彝蠡', 'nej', 0, 'SZ'),
    ('牙', 'jngs', 1, 'SZ'),
    ('发', 'nselk', 0, 'SZ'),
    ('夅', 'seljnf', 4, 'SZ'),          # 夂 㐄: not 车's 撇折 (辚's jnf)
    ('舛', 'sekjnf', 4, 'SZ'),
    ('韋暐韡', 'cjjnf', 3, 'SZ'),        # 韋's foot is 㐄 too (chaizi has not 暐 韡)
]
KIND = {'H': 1, 'T': 1, 'S': 2, 'SG': 2, 'P': 3, 'D': 4, 'N': 4}
CODES = set(PLAIN.values()) | {'HP', 'HG', 'XG', 'BXG', 'HPWG', 'HZZZG', 'HZZ', 'HZW', 'SZP', 'SZZ', 'SZ', 'SW', 'HXG', 'HZWG'}


def kinds(shapes):
    return ''.join(str(KIND.get(s, 5)) for s in shapes)


def parts_of(chaizi):
    """A character to every part it is made of, its parts' parts too."""
    direct = {}
    for line in chaizi:
        f = line.rstrip('\n').split('\t')
        direct.setdefault(f[0], set()).update(p for way in f[1:] for p in way.split())
    memo = {}

    def parts(c):
        # each character's own walk: a cycle in chaizi cannot leave one with another's cut-short parts
        if c not in memo:
            r, todo = set(), [c]
            while todo:
                for p in direct.get(todo.pop(), ()):
                    # □: chaizi's stand-in for a part it cannot write, shared by thousands: no part
                    if p != c and p != '□' and p not in r:
                        r.add(p)
                        todo.append(p)
            memo[c] = r
        return memo[c]
    return parts


def e_shape(v, i):
    """The shape of e, stroke i of letters v, by the longest pattern over it; a pattern is matched on the
    letters, not the parts, so it may run across two (寂: 疋's efj over 宀's e and 叔's start, 横钩 as
    宀's is): a new pattern is checked by the characters it changes."""
    best = {}
    for pat, at in E:
        for m in re.finditer('(?=' + re.escape(pat) + ')', v):
            j = i - m.start()
            if j in at:
                best.setdefault(len(pat), set()).add(at[j])
    if best:
        top = best[max(best)]
        if len(top) == 1:
            return next(iter(top))
    return None


def shapes(c, v, parts, changed=None):
    """The shapes of character c's strokes, cnchar's letters v; None for a stroke no rule settles."""
    out = []
    for i, x in enumerate(v):
        if x in PLAIN:
            out.append(PLAIN[x])
        elif x == 'y':
            out.append('BXG' if i and v[i - 1] == 'd' else 'XG')
        elif x == 'w':
            out.append('HPWG' if v[i + 1:i + 2] == 'f' else 'HZZZG')
        elif x == 'v':
            out.append('HZZ' if c == '凹' else 'HZW')
        elif x == 'x':
            out.append('SZP' if c in '专传转砖抟啭' else 'SZZ')
        elif x == 'b':
            sw = v[max(0, i - 1):i + 2] == 'sbj' and bool(({c} | parts(c)) & SW_PARTS)
            out.append('SW' if sw else 'SZ')
        elif x == 'o':
            out.append('HXG' if ({c} | parts(c)) & HXG_PARTS else 'HZWG')
        elif x == 'e':
            out.append(e_shape(v, i))
        else:
            raise ValueError(f'{c}: a letter cnchar has not: {x}')
    for of, letters, at, shape in GF:
        if ({c} | parts(c)) & set(of):
            for m in re.finditer('(?=' + re.escape(letters) + ')', v):
                # said, a line each: the rule matches its letters anywhere in the character, so a
                # run's diff is checked character by character
                if changed is not None and out[m.start() + at] != shape:
                    changed.append(f'{c}: stroke {m.start() + at + 1} {out[m.start() + at]} made {shape}, by GF 2001 ({of})')
                out[m.start() + at] = shape
    return out


def convert(orders, chaizi, tsv):
    """The tsv's lines with shapes, and what was left as it was or not settled, a line each."""
    parts = parts_of(chaizi)
    lines, notes = [], []
    for line in tsv:
        line = line.rstrip('\n')
        if line.startswith('#') or not line.strip():
            lines.append(line)
            continue
        f = line.split('\t')
        if len(f) < 2:
            raise ValueError(f'{f[0]}: no tab before its strokes')
        c, strokes = f[0], f[1]
        written = strokes.split()
        have = strokes if len(written) == 1 and strokes.isdigit() else kinds(written)
        if c not in orders:   # added by hand (冖), cnchar has not the character
            lines.append(line)
            continue
        changed = []
        if c in FIXED or c in OVERRIDE:
            made = (FIXED.get(c) or OVERRIDE[c]).split()
        else:
            made = shapes(c, orders[c], parts, changed)
            if None in made:
                notes.append(f'{c} {orders[c]}: stroke {made.index(None) + 1} settled by no rule')
                lines.append(line)
                continue
        if kinds(made) != have:
            if len(written) > 1 or written[0] in CODES:
                notes.append(f'{c}: kept as written, {kinds(made)} by the rules')
                lines.append(line)
                continue
            raise ValueError(f'{c}: the shapes made, {kinds(made)}, are not the line\'s {have}')
        notes += changed
        lines.append('\t'.join([c, ' '.join(made)] + f[2:]))
    return lines, notes


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding='utf-8') as f:
        orders = json.load(f)
    with open(sys.argv[2], encoding='utf-8') as chaizi, open(sys.argv[3], encoding='utf-8') as tsv:
        lines, notes = convert(orders, list(chaizi), list(tsv))
    print('\n'.join(lines))
    for n in notes:
        print(n, file=sys.stderr)


if __name__ == '__main__':
    main()
