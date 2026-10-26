#!/usr/bin/env python3
"""Writes the host's golden files from fcitx's own code (see build.sh) into the test resources.

    gen.py <build dir>

Inputs are upstream fcitx5 at 442edbc9 (../upstream.sh): unicode/charselectdata (SHA-256 pinned
below) and the three quickphrase.d tables, which must equal the app's copy in
app/src/main/host-assets/pkgdata (the one copy the app ships and the tests read). Outputs, under
lib/ime-core/src/test/resources/host/:
  charselect-find.tsv       query, count, SHA-256 of the comma-joined hex list, the first 40
  charselect-names.tsv      per 0x1000 block: SHA-256 of "<hex>\t<name>\n" lines; then samples
  quickphrase-builtin.tsv   the shipped tables: input, count, SHA-256, the first 40 key/value
  quickphrase-fixture.tsv   the same over quickphrase-fixture/{user,builtin}
"""
import hashlib
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(subprocess.run([os.path.join(HERE, '../upstream.sh'), 'fcitx5'], check=True, capture_output=True,
                                  text=True).stdout.strip(), 'src/modules')
PKGDATA = os.path.join(HERE, '../../../../app/src/main/host-assets/pkgdata')
OUT = os.path.join(HERE, '../../src/test/resources/host')
CHARSELECT_SHA = '82a47500ca9c2918197acbc51ff74e8159bbd91d779cdf7ec483561f86eff841'
FIRST = 40


def sha(s):
    return hashlib.sha256(s.encode()).hexdigest()


def run(binary, pkgdata, stdin):
    env = dict(os.environ, GOLDEN_PKGDATA=pkgdata)
    return subprocess.run([binary], input=stdin.encode(), env=env, check=True,
                          capture_output=True).stdout.decode()


def charselect(build):
    path = os.path.join(SRC, 'unicode/charselectdata')
    raw = open(path, 'rb').read()
    assert hashlib.sha256(raw).hexdigest() == CHARSELECT_SHA, 'charselectdata changed'
    assert open(os.path.join(PKGDATA, 'unicode/charselectdata'), 'rb').read() == raw, "the app's charselectdata differs"
    queries = [l.rstrip('\n') for l in open(os.path.join(HERE, 'charselect-queries.txt'), encoding='utf-8')]
    out = run(os.path.join(build, 'charselect-golden'), PKGDATA, ''.join('find %s\n' % q for q in queries))
    with open(os.path.join(OUT, 'charselect-find.tsv'), 'w', encoding='utf-8') as f:
        for line in out.splitlines():
            _, q, cps = line.split('\t')
            items = cps.split(',') if cps else []
            f.write('%s\t%d\t%s\t%s\n' % (q.replace('\\', '\\\\').replace(' ', '\\s'), len(items), sha(cps),
                                          ','.join(items[:FIRST])))
    out = run(os.path.join(build, 'charselect-golden'), PKGDATA, 'names\n')
    blocks = {}
    samples = []
    for line in out.splitlines():
        _, h, name = line.split('\t', 2)
        c = int(h, 16)
        blocks.setdefault(c >> 12, []).append('%s\t%s\n' % (h, name))
        if c % 997 == 0 or c < 0x80:
            samples.append(line[2:])
    with open(os.path.join(OUT, 'charselect-names.tsv'), 'w', encoding='utf-8') as f:
        for b in sorted(blocks):
            f.write('B\t%X\t%s\n' % (b, sha(''.join(blocks[b]))))
        for s in samples:
            f.write('S\t%s\n' % s)


def quickphrase(build, pkgdata, inputs, name):
    out = run(os.path.join(build, 'quickphrase-golden'), pkgdata, ''.join(i + '\n' for i in inputs))
    groups = []
    for line in out.splitlines():
        parts = line.split('\t')
        if parts[0] == 'Q':
            groups.append((parts[1], []))
        else:
            groups[-1][1].append('%s\t%s' % (parts[1], parts[2]))
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        for q, items in groups:
            f.write('Q\t%s\t%d\t%s\n' % (q, len(items), sha('\n'.join(items))))
            for it in items[:FIRST]:
                f.write('C\t%s\n' % it)


def esc(s):
    return s.replace('\\', '\\\\').replace('\t', '\\t')


def builtin_inputs(files):
    keys = set()
    for p in files:
        for line in open(p, encoding='utf-8'):
            t = line.strip()
            if t and len(t.split(None, 1)) == 2:
                keys.add(t.split(None, 1)[0])
    inputs = set(keys)
    for k in keys:
        for n in (1, 2, 3):
            inputs.add(k[:n])
    inputs |= {'zz', ':zz', 'é', '中'}
    return [esc(i) for i in sorted(inputs)]


def main():
    build = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    charselect(build)
    qd = os.path.join(SRC, 'quickphrase/quickphrase.d')
    files = []
    for n in sorted(os.listdir(qd)):
        if n.endswith('.mb'):
            data = open(os.path.join(qd, n), 'rb').read()
            assert open(os.path.join(PKGDATA, 'data/quickphrase.d', n), 'rb').read() == data, "the app's %s differs" % n
            files.append(os.path.join(qd, n))
    assert sorted(os.listdir(os.path.join(PKGDATA, 'data/quickphrase.d'))) == sorted(map(os.path.basename, files))
    quickphrase(build, PKGDATA, builtin_inputs(files), 'quickphrase-builtin.tsv')
    fx = os.path.join(OUT, 'quickphrase-fixture')
    dirs = os.path.join(fx, 'user') + ':' + os.path.join(fx, 'builtin')
    fixture_inputs = ['', 'a', 'b', 'k', 'q', 'u', 'x', 'é', '中', 'zz', 'over', 'dis', 'esc', 'm']
    quickphrase(build, dirs, [esc(i) for i in fixture_inputs], 'quickphrase-fixture.tsv')


if __name__ == "__main__":
    main()
