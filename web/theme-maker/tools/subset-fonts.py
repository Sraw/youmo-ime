#!/usr/bin/env python3
# The theme maker's two fonts (060-render-p3 W1), cut to what a keyboard shows: "Youmo Latin" of Roboto (Latin,
# digits, punctuation) and "Youmo Han" of Noto Sans CJK SC (the characters on the app's keys, 注音, the page's
# sample candidates), each at 400 and 700, as WOFF2 in web/theme-maker/fonts/. tools/assemble.mjs puts them in
# the page as data: URLs (its CSP allows fonts so and no other way). A theme's own fonts are the theme's.
#
# The sources are the fonts Robolectric draws the app's screenshots with (so the page's pixels are compared with
# the same glyphs: test/pixel-smoke.mjs): Roboto (Apache License 2.0) and Noto Sans CJK (SIL Open Font License
# 1.1); their name tables, their copyright and licence entries kept in the subsets.
#
# Needs fontTools and brotli (pip install fonttools brotli). Run when the layouts gain characters:
#   python3 web/theme-maker/tools/subset-fonts.py <nativeruntime-dist-compat jar of Robolectric, or its fonts/ folder>
# (the jar: ~/.gradle/caches/modules-2/files-2.1/org.robolectric/nativeruntime-dist-compat/*/*/*.jar)
import io
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTCollection, TTFont

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'web/theme-maker/fonts'
LAYOUTS = ROOT / 'lib/keyboard-scene/src/commonMain/layouts'
SNAPSHOTS = ROOT / 'lib/keyboard-scene/src/commonTest/data/snapshots'

# the page's sample candidates and texts the app gives a keyboard (its name, Return's, a screen reader's), beyond the layouts'
SAMPLES = (
    '你好你呢尼拟妮泥倪逆明天下午三点开会收到谢谢快速输入发送这条消息的了是我不在有人中大上个'
    '拼音注音五笔仓颉双拼手写符号表情颜文字搜索英文数字空格换行确定返回发送前往完成下一步上一步锁定撤销重做全选剪切复制粘贴关'
    '，。、；：？！…—～·“”‘’（）《》【】「」『』〈〉￥'
    # the page's own previews (src/ui.js): the clipboard's, the symbol picker's tabs, the editing panel's arrows
    '复制的一段文字幽默输入法常用中文英文数学箭头全选粘贴剪切←→'
)

# Latin: ASCII, Latin-1, general punctuation, the euro; Han: the CJK punctuation, the full-width forms, 注音 and its tone marks
LATIN = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100)) | set(range(0x2010, 0x2028)) | set(range(0x2030, 0x203B)) | {0x20AC, 0x2122}
HAN = set(range(0x3000, 0x3040)) | set(range(0xFF01, 0xFF5F)) | set(range(0x3105, 0x3130)) | set(range(0x31A0, 0x31BB)) | {0x02C7, 0x02CA, 0x02CB, 0x02D9}


def layout_chars():
    """Every character of the layouts' attribute values and texts (not their comments: the XML parser drops them)."""
    out = set()
    for f in sorted(LAYOUTS.glob('*.xml')):
        for e in ET.parse(f).iter():
            for v in e.attrib.values():
                out |= set(v)
            out |= set(e.text or '')
    return out


def snapshot_chars():
    """Every character the engine's snapshots draw: the environments' texts too (an input method's name, Return's)."""
    out = set()
    for f in sorted(SNAPSHOTS.glob('*.txt')):
        for m in re.finditer(r'text "((?:[^"\\]|\\.)*)"', f.read_text(encoding='utf-8')):
            out |= set(m.group(1))
    return out


def source(where, name):
    p = Path(where)
    if p.is_dir():
        return (p / name).read_bytes()
    with zipfile.ZipFile(p) as z:
        return z.read('fonts/' + name)


def cut(font, codepoints, out):
    opts = subset.Options()
    opts.flavor = 'woff2'
    opts.layout_features = ['*']
    # the names, the copyright and the licence (0–6, 13, 14), in English
    opts.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    opts.hinting = False
    opts.desubroutinize = True
    s = subset.Subsetter(opts)
    s.populate(unicodes=sorted(codepoints))
    s.subset(font)
    font.flavor = 'woff2'
    font.save(out)
    return out.stat().st_size


def main(where):
    chars = {ord(c) for c in layout_chars() | snapshot_chars() | set(SAMPLES) if ord(c) >= 0x20}
    latin = LATIN | {c for c in chars if c < 0x2E80 and c not in HAN}
    han = HAN | {c for c in chars if c >= 0x2E80} | {c for c in chars if c not in latin}
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for weight, roboto, noto in ((400, 'Roboto-Regular.ttf', 'NotoSansCJK-Regular.ttc'), (700, 'Roboto-Bold.ttf', 'NotoSansCJK-Bold.ttc')):
        r = TTFont(io.BytesIO(source(where, roboto)))
        has = set(r.getBestCmap())
        total += cut(r, latin & has, OUT / f'latin-{weight}.woff2')
        # face 2 of the collection: Noto Sans CJK SC; it also takes what Roboto lacks below U+2E80 (arrows, ⌫, ⇧)
        n = TTCollection(io.BytesIO(source(where, noto))).fonts[2]
        assert 'SC' in n['name'].getDebugName(4), n['name'].getDebugName(4)
        total += cut(n, (han | (latin - has)) & set(n.getBestCmap()), OUT / f'han-{weight}.woff2')
    print(OUT, total, 'bytes;', len(latin), 'Latin and', len(han), 'Han code points asked')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__ or 'usage: subset-fonts.py <robolectric nativeruntime-dist-compat jar | fonts dir>')
    main(sys.argv[1])
