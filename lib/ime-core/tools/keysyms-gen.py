#!/usr/bin/env python3
"""Generates ime-core's key-symbol tables and their golden files from fcitx5's own headers.

The inputs come from github.com/fcitx/fcitx5 at 442edbc9 (5.1.21-54, the commit the host was ported
from), checked by SHA-256. Nothing in the
build calls it; its outputs are committed.

  python3 -I lib/ime-core/tools/keysyms-gen.py [--cache <dir>]

Writes
  lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/host/KeySymUnicodeTable.kt
  lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/host/KeySymNameTable.kt
  lib/ime-core/src/test/resources/keysyms/{name-to-sym,sym-to-name,unicode-to-sym,sym-to-unicode}.tsv

The golden files are what fcitx's `Key::keySymFromString`, `keySymToString` (non-localized),
`keySymFromUnicode` and `keySymToUnicode` return (src/lib/fcitx-utils/key.cpp:622-800 at the
pinned commit), computed here by the same rules over the downloaded tables. unicode-to-sym and
sym-to-unicode list only the entries the plain rules don't give (see their header lines), so the
tests walk every code point / keysym and fall back to the rule.
"""
import argparse
import hashlib
import os
import re
import sys
import tempfile
import urllib.request

COMMIT = "442edbc9b1880c766ff9cc9a9ac67e864b200595"
BASE = f"https://raw.githubusercontent.com/fcitx/fcitx5/{COMMIT}/src/lib/fcitx-utils/"
FILES = {
    "keydata.h": "e3d36cfac7fe7bf899f88b14b886cdb858bc1e0db70b75464b3137c0d36c2bc3",
    "keynametable.h": "90dafd7bc734c77a07814285ab09ab95f49c08594f082caa2372c624f283fb33",
    "keynametable-compat.h": "b98c38b519515e4ec0627059d7121dc4a73afaa7b8fbf5ec81c819993bcb5e18",
    "keysymgen.h": "458dc2f6246d0b69179f59b847a35d47a51bc16cca1cedec0d5ce82be0a2b616",
}
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))
KT_DIR = os.path.join(ROOT, "lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/host")
RES = os.path.join(ROOT, "lib/ime-core/src/test/resources/keysyms")


def fetch(cache):
    out = {}
    for name, sha in FILES.items():
        path = os.path.join(cache, name)
        if not os.path.exists(path):
            with urllib.request.urlopen(BASE + name) as r, open(path, "wb") as f:
                f.write(r.read())
        data = open(path, "rb").read()
        got = hashlib.sha256(data).hexdigest()
        if got != sha:
            sys.exit(f"{name}: SHA-256 {got}, expected {sha}")
        out[name] = data.decode("utf-8")
    return out


def parse(src):
    sym_ucs = [(int(a, 16), int(b, 16)) for a, b in
               re.findall(r"\{0x([0-9a-f]+),\s*0x([0-9a-f]+)\}", src["keydata.h"])]
    assert sym_ucs == sorted(sym_ucs) and len({s for s, _ in sym_ucs}) == len(sym_ucs)
    nt = src["keynametable.h"]
    names_block = nt[nt.index("keyNameList[]"):nt.index("keyValueByNameOffset[]")]
    names = re.findall(r'^"([^"]*)",?$', names_block, re.M)
    values_block = nt[nt.index("keyValueByNameOffset[]"):nt.index("keyNameOffsetByValue[]")]
    values = [int(v, 16) for v in re.findall(r"^0x([0-9a-f]+),?$", values_block, re.M)]
    by_value = [(int(s, 16), int(o)) for s, o in
                re.findall(r"^\{0x([0-9a-f]+), (\d+)\},?$", nt[nt.index("keyNameOffsetByValue[]"):], re.M)]
    assert len(names) == len(values) and names == sorted(names, key=lambda n: n.encode())
    assert [s for s, _ in by_value] == sorted({s for s, _ in by_value})
    enum = {m[0]: int(m[1], 16) for m in re.findall(r"^FcitxKey_(\w+) = 0x([0-9a-fA-F]+),", src["keysymgen.h"], re.M)}
    compat = [(n, enum[s]) for n, s in re.findall(r'\{"([^"]+)", FcitxKey_(\w+)\}', src["keynametable-compat.h"])]
    assert [n for n, _ in compat] == sorted((n for n, _ in compat), key=lambda n: n.encode())
    return sym_ucs, names, values, by_value, compat, enum


# key.cpp:689-736
def from_unicode(u, ucs_sorted, enum):
    if 0x20 <= u <= 0x7E or 0xA0 <= u <= 0xFF:
        return u
    if (enum["BackSpace"] & 0x7F) <= u <= (enum["Clear"] & 0x7F) or u in (enum["Return"] & 0x7F, enum["Escape"] & 0x7F):
        return u | 0xFF00
    if u == enum["Delete"] & 0x7F:
        return enum["Delete"]
    if 0xD800 <= u <= 0xDFFF or 0xFDD0 <= u <= 0xFDEF or u > 0x10FFFF or (u & 0xFFFE) == 0xFFFE:
        return 0
    if u in ucs_sorted:
        return ucs_sorted[u]
    return u | 0x01000000


# key.cpp:738-800
def to_unicode(s, table, enum):
    if 0x20 <= s <= 0x7E or 0xA0 <= s <= 0xFF:
        return s
    if s == enum["KP_Space"]:
        return enum["space"] & 0x7F
    if (enum["BackSpace"] <= s <= enum["Clear"] or enum["KP_Multiply"] <= s <= enum["KP_9"]
            or s in (enum["Return"], enum["Escape"], enum["Delete"], enum["KP_Tab"], enum["KP_Enter"], enum["KP_Equal"])):
        return s & 0x7F
    if 0x0100D800 <= s <= 0x0100DFFF:
        return 0
    if 0x01000000 <= s <= 0x0110FFFF:
        c = s - 0x01000000
        return c if c < 0x110000 and (c & 0xFFFFF800) != 0xD800 else 0
    return table.get(s, 0)


def chunks(items, n):
    return [items[i:i + n] for i in range(0, len(items), n)]


HEADER = """/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: %s
 */
package org.fcitx.fcitx5.android.host

// Generated by lib/ime-core/tools/keysyms-gen.py from fcitx5 src/lib/fcitx-utils/%s at 442edbc9; do not edit.
"""


def unicode_table(sym_ucs):
    # entries split over two lines in keydata.h (Odoubleacute, ...) count too: 777, not 738
    rows = "".join("        " + " ".join(f"0x{k:04x}, 0x{u:04x}," for k, u in c) + "\n" for c in chunks(sym_ucs, 6))
    return (HEADER % ("1995-1997 Peter Mattis, Spencer Kimball and Josh MacDonald", "keydata.h") +
            "// keysym_to_unicode_tab: keysym, code point, keysym, code point, ..., sorted by keysym.\n"
            '@Suppress("LargeClass", "MagicNumber")\ninternal object KeySymUnicodeTable {\n'
            f"    val PAIRS = intArrayOf(\n{rows}    )\n}}\n")


def name_table(names, values, by_value, compat):
    def blob(entries, per_line):
        lines = [" ".join(c) for c in chunks(entries, per_line)]
        return "\n".join(f'        "{l} " +' for l in lines[:-1]) + f'\n        "{lines[-1]}"'
    named = blob([f"{n}={v:x}" for n, v in zip(names, values)], 6)
    rev = blob([f"{s:x}={names[o]}" for s, o in by_value], 6)
    comp = blob([f"{n}={v:x}" for n, v in compat], 5)
    return (HEADER % ("2015~2025 CSSlayer <wengxt@gmail.com>", "keynametable.h, keynametable-compat.h") + f'''
/**
 * fcitx's key names as `name=keysym` words (keysyms in hex). Strings, not arrays, so the class
 * initializer stays far below the JVM's 64 KB method limit; parsed on first use.
 */
@Suppress("LargeClass")
internal object KeySymNameTable {{
    /** `keyNameList` + `keyValueByNameOffset`: sorted by name (bytes; all ASCII). */
    val names: Array<String> by lazy {{ words(NAMES).map {{ it.substringBefore('=') }}.toTypedArray() }}
    val nameSyms: IntArray by lazy {{ words(NAMES).map {{ it.substringAfter('=').toInt(16) }}.toIntArray() }}

    /** `keyNameOffsetByValue`: the one name `keySymToString` gives a keysym, sorted by keysym. */
    val valueSyms: IntArray by lazy {{ words(BY_VALUE).map {{ it.substringBefore('=').toInt(16) }}.toIntArray() }}
    val valueNames: Array<String> by lazy {{ words(BY_VALUE).map {{ it.substringAfter('=') }}.toTypedArray() }}

    /** keynametable-compat.h `keyNameListCompat`: fcitx 4's names, sorted. */
    val compatNames: Array<String> by lazy {{ words(COMPAT).map {{ it.substringBefore('=') }}.toTypedArray() }}
    val compatSyms: IntArray by lazy {{ words(COMPAT).map {{ it.substringAfter('=').toInt(16) }}.toIntArray() }}

    private fun words(s: String) = s.split(' ')

    private const val NAMES =
{named}

    private const val BY_VALUE =
{rev}

    private const val COMPAT =
{comp}
}}
''')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory for the downloaded headers (default: a temp dir)")
    a = ap.parse_args()
    cache = a.cache or tempfile.mkdtemp(prefix="keysyms-")
    os.makedirs(cache, exist_ok=True)
    sym_ucs, names, values, by_value, compat, enum = parse(fetch(cache))
    table = dict(sym_ucs)
    # keydata.cpp:12-21 stable-sorts the table by ucs; key.cpp:719-731 then binary-searches it with
    # mid = (min + max) / 2, so a code point with several keysyms gets the one the search lands on
    # (U+2500: 0x9f1, not the first, 0x8a3), not the first: the search itself, run for each
    tab = sorted(sym_ucs, key=lambda p: p[1])
    ucs_sorted = {}
    for u in {u for _, u in tab}:
        lo, hi = 0, len(tab) - 1
        while hi >= lo:
            mid = (lo + hi) // 2
            if tab[mid][1] < u:
                lo = mid + 1
            elif tab[mid][1] > u:
                hi = mid - 1
            else:
                ucs_sorted[u] = tab[mid][0]
                break
    os.makedirs(RES, exist_ok=True)
    with open(os.path.join(KT_DIR, "KeySymUnicodeTable.kt"), "w") as f:
        f.write(unicode_table(sym_ucs))
    with open(os.path.join(KT_DIR, "KeySymNameTable.kt"), "w") as f:
        f.write(name_table(names, values, by_value, compat))

    def tsv(name, header, rows):
        with open(os.path.join(RES, name), "w") as f:
            f.write(f"# from fcitx5 {COMMIT[:8]} by lib/ime-core/tools/keysyms-gen.py; {header}\n")
            f.writelines("\t".join(r) + "\n" for r in rows)

    # keySymFromString: the main table wins, then compat (key.cpp:622-646)
    resolved = dict(zip(names, values))
    for n, v in compat:
        resolved.setdefault(n, v)
    tsv("name-to-sym.tsv", "name, Key::keySymFromString(name)",
        sorted(((n, f"{v:x}") for n, v in resolved.items()), key=lambda r: r[0].encode()))
    tsv("sym-to-name.tsv", "keysym, Key::keySymToString(keysym, Portable); unlisted keysyms give \"\"",
        [(f"{s:x}", names[o]) for s, o in by_value])
    rows = []
    for u in range(0x110000 + 0x100):
        got = from_unicode(u, ucs_sorted, enum)
        plain = u if (0x20 <= u <= 0x7E or 0xA0 <= u <= 0xFF) else u | 0x01000000
        if got != plain:
            rows.append((f"{u:x}", f"{got:x}"))
    tsv("unicode-to-sym.tsv", "code point, Key::keySymFromUnicode(cp) for 0..0x1100ff where it isn't "
        "cp (0x20-0x7e, 0xa0-0xff) or cp|0x01000000", rows)
    rows = []
    for s in list(range(0x10000)) + list(range(0x0100D7F0, 0x0100E010)) + list(range(0x0110FFF0, 0x01110010)):
        got = to_unicode(s, table, enum)
        if 0x20 <= s <= 0x7E or 0xA0 <= s <= 0xFF:
            plain = s
        elif 0x01000000 <= s <= 0x0110FFFF:
            plain = s - 0x01000000
        else:
            plain = 0
        if got != plain:
            rows.append((f"{s:x}", f"{got:x}"))
    tsv("sym-to-unicode.tsv", "keysym, Key::keySymToUnicode(keysym) for 0..0xffff and the edges of "
        "0x01000000+ where it isn't sym (0x20-0x7e, 0xa0-0xff), sym-0x01000000 (0x01000000-0x0110ffff) or 0", rows)
    print(f"{len(sym_ucs)} keysym/unicode pairs, {len(names)} names, {len(by_value)} by value, {len(compat)} compat")


if __name__ == "__main__":
    main()
