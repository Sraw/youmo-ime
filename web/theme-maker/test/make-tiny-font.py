#!/usr/bin/env python3
"""A tiny valid TrueType font: .notdef and one square glyph for A-Z a-z 0-9. Test fixture only."""
import struct, sys

UPM = 1000
def checksum(b):
    b += b'\0' * ((4 - len(b) % 4) % 4)
    return sum(struct.unpack('>%dI' % (len(b) // 4), b)) & 0xffffffff

# glyph 0 .notdef: empty; glyph 1: a square 100..600
def simple_glyph(pts):
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    hdr = struct.pack('>hhhhh', 1, min(xs), min(ys), max(xs), max(ys))
    body = struct.pack('>H', len(pts) - 1) + struct.pack('>H', 0)
    body += bytes([1] * len(pts))  # on-curve, 16-bit coords
    px = py = 0
    xb = b''; yb = b''
    for x, y in pts:
        xb += struct.pack('>h', x - px); yb += struct.pack('>h', y - py); px, py = x, y
    g = hdr + body + xb + yb
    g += b'\0' * ((4 - len(g) % 4) % 4)
    return g

g1 = simple_glyph([(100, 0), (100, 600), (600, 600), (600, 0)])
glyf = g1
loca = struct.pack('>HHH', 0, 0, len(g1) // 2)  # short offsets: /2

head = struct.pack('>IIIIHHqqhhhhHHhhh', 0x00010000, 0x00010000, 0, 0x5F0F3CF5, 0x000B, UPM,
                   0, 0, 100, 0, 600, 600, 0, 8, 2, 0, 0)
hhea = struct.pack('>IhhhHhhhhhhhhhhhH', 0x00010000, 800, -200, 0, 700, 0, 0, 600, 1, 0, 0, 0, 0, 0, 0, 0, 2)
maxp = struct.pack('>IHHHHHHHHHHHHHH', 0x00010000, 2, 4, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0)
hmtx = struct.pack('>HhHh', 500, 0, 700, 100)

chars = list(range(0x30, 0x3A)) + list(range(0x41, 0x5B)) + list(range(0x61, 0x7B))
# cmap format 4: a segment per character (each to glyph 1: a run's delta would number them on), plus 0xFFFF
runs = [(c, c) for a, b in [(0x30, 0x39), (0x41, 0x5A), (0x61, 0x7A)] for c in range(a, b + 1)] + [(0xFFFF, 0xFFFF)]
seg = len(runs)
ends = b''.join(struct.pack('>H', e) for s, e in runs)
starts = b''.join(struct.pack('>H', s) for s, e in runs)
deltas = b''.join(struct.pack('>H', ((1 - s) % 0x10000) if s != 0xFFFF else 1) for s, e in runs)
ranges = b'\0\0' * seg
import math
sr = 2 * (2 ** int(math.log2(seg)))
f4body = struct.pack('>HHHH', seg * 2, sr, int(math.log2(sr // 2)), seg * 2 - sr) + ends + b'\0\0' + starts + deltas + ranges
f4 = struct.pack('>HHH', 4, 6 + len(f4body), 0) + f4body
cmap = struct.pack('>HHHHI', 0, 1, 3, 1, 12) + f4

def name_rec(nid, s):
    return nid, s.encode('utf-16-be')
names = [name_rec(1, 'Youmo Tiny'), name_rec(2, 'Regular'), name_rec(3, 'Youmo Tiny'), name_rec(4, 'Youmo Tiny'),
         name_rec(5, 'Version 1.0'), name_rec(6, 'YoumoTiny')]
strs = b''; recs = b''
for nid, s in names:
    recs += struct.pack('>HHHHHH', 3, 1, 0x409, nid, len(s), len(strs)); strs += s
name = struct.pack('>HHH', 0, len(names), 6 + 12 * len(names)) + recs + strs

os2 = struct.pack('>HhHHHhhhhhhhhhhh', 4, 600, 400, 5, 0, 650, 700, 0, 140, 650, 700, 0, 480, 50, 300, 0)
os2 += bytes(10)  # panose
os2 += struct.pack('>IIII', 1, 0, 0, 0)  # unicode ranges (basic latin)
os2 += b'NONE'
os2 += struct.pack('>HHHhhhHH', 0x40, 0x30, 0x7A, 800, -200, 0, 800, 200)
os2 += struct.pack('>II', 1, 0)  # code page ranges
os2 += struct.pack('>hhHHH', 500, 600, 0, 0x20, 0)
post = struct.pack('>IIhhIIIII', 0x00030000, 0, -100, 50, 0, 0, 0, 0, 0)

tables = {b'OS/2': os2, b'cmap': cmap, b'glyf': glyf, b'head': head, b'hhea': hhea, b'hmtx': hmtx,
          b'loca': loca, b'maxp': maxp, b'name': name, b'post': post}
tags = sorted(tables)
n = len(tags)
es = int(math.log2(n)); sr = 16 * (2 ** es)
out = struct.pack('>IHHHH', 0x00010000, n, sr, es, n * 16 - sr)
offset = 12 + 16 * n
dirs = b''; data = b''
for t in tags:
    b = tables[t]
    dirs += struct.pack('>4sIII', t, checksum(b), offset + len(data), len(b))
    data += b + b'\0' * ((4 - len(b) % 4) % 4)
font = bytearray(out + dirs + data)
# head.checkSumAdjustment
hoff = 12 + 16 * tags.index(b'head')
head_at = struct.unpack('>I', font[hoff + 8:hoff + 12])[0]
adj = (0xB1B0AFBA - checksum(bytes(font))) & 0xffffffff
font[head_at + 8:head_at + 12] = struct.pack('>I', adj)
open(sys.argv[1], 'wb').write(bytes(font))
print(len(font))
