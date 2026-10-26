#!/usr/bin/env python3
"""Golden outputs of OpenCC 1.2.0 for every config the app ships, from the host build of build.sh.

  lib/ime-eval/tools/opencc/build.sh <work>
  python3 -I lib/ime-eval/tools/opencc/golden.py <work>

Writes lib/ime-eval/src/test/resources/opencc/golden.tsv.gz: a header line "#input\t<config>..."
then one case per line, the input followed by `opencc -c <config>.json` of it for each config, an
empty column where the output equals the input. Inputs are UTF-8; a few deliberately hold a NUL or a
CESU-8 lone surrogate (see MIXED). One file, line by line, so gzip finds the near-identical
outputs of s2t/s2tw/s2hk/s2twp next to each other.

The corpus: every key of every dictionary of the release (data/dictionary/*.txt of the pinned
tarball, so both directions are exercised), every Chinese sentence and context of
lib/ime-eval/data, and MIXED. The opencc CLI reading stdin converts line by line
(src/tools/CommandLine.cpp:47-67), each line through Converter::Convert(std::string), the call
chttrans makes; a NUL ends the conversion of its line.
"""
import glob
import gzip
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../.."))
OUT = os.path.join(ROOT, "lib/ime-eval/src/test/resources/opencc")
CONFIGS = ["s2t", "s2tw", "s2twp", "s2hk", "t2s", "tw2s", "tw2sp", "hk2s", "t2tw", "t2hk", "tw2t", "hk2t",
           "t2jp", "jp2t"]
HAN = re.compile(r"[㐀-鿿\U00020000-\U0003ffff]")

MIXED = [
    b"", b"abc 123", "汉字abc混合123文字".encode(), "发展中国家的头发".encode(),
    "😀简体😀干燥🇨🇳".encode(), "后天́的组合字符干".encode(), "a‍后".encode(),
    "一\u0000后".encode(), b"\xed\xa0\x80\xe5\x90\x8e",  # a lone surrogate (CESU-8) before 后
    # no invalid UTF-8 (b"\xff\xfe..."): opencc prints "Invalid UTF8" and stops; the Kotlin side
    # only ever sees Java strings, whose lone surrogates come through as the case above
    "里面的里程碑".encode(), "皇后在后面".encode(),
    "联系方式：电话０１２３，邮箱a@b.cn。".encode(), "「引号」『书名』".encode(),
    "《红楼梦》的作者是曹雪芹".encode(), "计算机软件开发工程师".encode(), "台湾的鼠标和软件".encode(),
    "臺灣的滑鼠和軟體".encode(), "髮型與發展".encode(), "乾燥的幹部".encode(), "　全角空格　".encode(),
    ("发展" * 2000).encode(), ("面" * 5000 + "条").encode(),
]


def corpus(src):
    """the lines to convert: every dictionary key, data/'s Chinese sentences, MIXED; each once"""
    lines = []
    for f in sorted(glob.glob(os.path.join(src, "data/dictionary/*.txt"))):
        for l in open(f, encoding="utf-8"):
            k = l.split("\t", 1)[0].strip()
            if k:
                lines.append(k.encode())
    for f in sorted(glob.glob(os.path.join(ROOT, "lib/ime-eval/data/**/*.tsv"), recursive=True)
                    + glob.glob(os.path.join(ROOT, "lib/ime-eval/data/**/*.txt"), recursive=True)):
        for l in open(f, encoding="utf-8"):
            if l.startswith("#"):
                continue
            for col in l.rstrip("\n").split("\t"):
                if HAN.search(col):
                    lines.append(col.strip().encode())
    seen, out = set(), []
    for l in lines + MIXED:
        if l not in seen:
            seen.add(l)
            out.append(l)
    return out


def main():
    work = os.path.realpath(sys.argv[1])
    src = os.path.join(work, "OpenCC-ver.1.2.0")
    tool = os.path.join(work, "install/bin/opencc")
    share = os.path.join(work, "install/share/opencc")
    lines = corpus(src)
    assert all(b"\n" not in l for l in lines)
    os.makedirs(OUT, exist_ok=True)
    data = b"".join(l + b"\n" for l in lines)
    outs = []
    with tempfile.TemporaryDirectory() as tmp:
        inp = os.path.join(tmp, "in.txt")
        open(inp, "wb").write(data)
        for c in CONFIGS:
            res = os.path.join(tmp, c)
            # stdin, not -i: only stdin is converted line by line (CommandLine.cpp:47-67, 200-205);
            # -i converts whole buffers, where a NUL would cut the rest of the buffer
            with open(inp, "rb") as i:
                subprocess.run([tool, "-c", os.path.join(share, c + ".json"), "-o", res], stdin=i, check=True)
            got = open(res, "rb").read().split(b"\n")
            assert len(got) == len(lines) + 1 and got[-1] == b"", (c, len(got), len(lines))
            outs.append(got[:-1])
            print(c, sum(o != l for o, l in zip(got, lines)), "changed of", len(lines))
    with gzip.GzipFile(os.path.join(OUT, "golden.tsv.gz"), "wb", mtime=0) as g:
        g.write(b"#input\t" + b"\t".join(c.encode() for c in CONFIGS) + b"\n")
        for i, l in enumerate(lines):
            assert b"\t" not in l
            g.write(l + b"".join(b"\t" + (b"" if o[i] == l else o[i]) for o in outs) + b"\n")

if __name__ == "__main__":
    main()
