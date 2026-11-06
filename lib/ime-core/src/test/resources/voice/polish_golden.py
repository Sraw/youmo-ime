"""VoicePolishTest's golden sample: the polish tagger's labels written out (voice-eval's tags.py: its lists, norm and
apply, copied here so the sample can be made again from this repo) and the freeze rule as voice-eval's fusion_exp.py
`stable` ran it (a character's labels kept once K characters follow it, each prefix tagged on its last W; copied too),
on fixed texts. The tagger is a stand-in, VoicePolishTest's fake(): labels from each character, where it is in the
window and the window's length, so that the window and the freezing change what comes out.

    python3 polish_golden.py polish-golden.tsv

A line a case, tab-separated: `ops`/`afters` (the label lists), `norm <in> <out>`, `apply <text> <ops> <afters> <out>`
(labels as indices, space-separated), `stream <text> <step> <K> <W> <out>` (the text handed over `step` characters at
a time, then ended).
"""
import random
import re
import sys

AFTER = ['', '，', '。', '？', '！', '、', '…', '：', '“', '”', '；', '。”', '？”', '：“', '，“', '”，', '”。',
         '！”', '…”', '，”', '”、', '、“', '”？', '”！', '”；', '”“', '”、“', '”：', '：“']
AFTER = list(dict.fromkeys(AFTER))
REPS = ['得', '地', '它', '她', '他', '吗', '的', '了', '和', '那', '这', '么', '呢', '吧', '啊', '呀', '是', '个',
        '在', '就', '也', '都', '还', '有', '人', '会', '说', '要', '能', '可', '么', '没', '一', '二', '两',
        '。', '，', '？', '！', '、', '…', '：', '；']
REPS = list(dict.fromkeys(REPS))
OPS = ['keep', 'del'] + REPS


def norm(s):
    s = re.sub(r'[\s《》·]+', '', s).replace('……', '…').replace('...', '…')
    return s.replace(',', '，').replace('?', '？').replace('!', '！').replace(':', '：').replace(';', '；')


def apply(a, ops, after):
    out = []
    for c, o, t in zip(a, ops, after):
        out.append(c if o == 'keep' else '' if o == 'del' else o)
        out.append(t)
    s = ''.join(out)
    return re.sub(r'[，、；：]+(?=[。？！…，])', '', s).replace('…', '……')


def fake(w):
    """VoicePolishTest.fake: (op index, after index) a character of the window [w]."""
    op, af = [], []
    for i, c in enumerate(w):
        cp = ord(c)
        r = (cp * 31 + i * 7 + len(w) * 3) % 11
        op.append(0 if r < 7 else 1 if r == 7 else 2 + cp % len(REPS))
        r = (cp * 17 + i * 5 + len(w)) % 9
        af.append(0 if r < 6 else 1 + (cp + i) % (len(AFTER) - 1))
    return op, af


def stream(a, step, K, window):
    """fusion_exp.py stable's freeze at one K, with the fake tagger."""
    n = len(a)
    froz = [None] * n
    seen = [None] * n
    for p in list(range(step, n, step)) + [n]:
        lo = max(0, p - window) if window else 0
        for i in range(lo):
            if froz[i] is None: froz[i] = seen[i]
        po, pa = fake(a[lo:p])
        last = p == n
        for i in range(lo, p):
            seen[i] = (po[i - lo], pa[i - lo])
            k = p - 1 - i
            if froz[i] is None and (k >= K or last): froz[i] = seen[i]
    return apply(a, [OPS[x[0]] for x in froz], [AFTER[x[1]] for x in froz])


TEXTS = [
    '嗯那个我们明天下午三点 开会吧,然后那个 那个报告你记得带上',
    '我觉得他说的的对就是那个……就是说我们得早点走?',
    '今天天气很好 我们去公园玩 然后去吃饭 吃完饭再去看电影 看完电影回家 回家以后洗澡睡觉',
    '《三体》这本书你看过没有·刘慈欣写的 我觉得写得特别好!',
    '把这个文件发给张总 抄送李经理和王经理 就说下周一之前要回复 谢谢',
    'OK那我们就这么定了 meeting 改到周三 3点半 in room 302',
    '他他他说不来了...那我们就自己去吧:先坐地铁再打车;到了给我打电话',
    '这个这个 呃 我想问一下就是 这个 这个价格还能不能再便宜一点 因为我们买得比较多 一次买五百个',
    '好的😀收到 马上处理',
    '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十',
    '',
    '嗯',
]


def main(out):
    rnd = random.Random(20261106)
    lines = ['\t'.join(['ops'] + OPS), '\t'.join(['afters'] + AFTER)]
    for t in TEXTS + ['  , ?!:; 《》·……...  ', '\u3000全角\u00a0空格', 'a\u0085b']:
        lines.append(f'norm\t{t}\t{norm(t)}')
    for t in TEXTS:
        a = norm(t)
        for _ in range(3):
            op = [rnd.choice([0, 0, 0, 1, rnd.randrange(len(OPS))]) for _ in a]
            af = [rnd.choice([0, 0, 0, rnd.randrange(len(AFTER))]) for _ in a]
            out_ = apply(a, [OPS[k] for k in op], [AFTER[k] for k in af])
            lines.append(f'apply\t{a}\t{" ".join(map(str, op))}\t{" ".join(map(str, af))}\t{out_}')
    for t in TEXTS:
        a = norm(t)
        # the test hands the stream prefixes of [a], which norm again: 「……」 would be one 「…」 there
        if not a or norm(a) != a: continue
        for step, K, W in [(4, 16, 64), (8, 16, 64), (10, 16, 64), (3, 2, 8), (5, 0, 6), (1, 4, 12)]:
            lines.append(f'stream\t{a}\t{step}\t{K}\t{W}\t{stream(a, step, K, W)}')
    with open(out, 'w', encoding='utf-8') as w:
        w.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main(sys.argv[1])
