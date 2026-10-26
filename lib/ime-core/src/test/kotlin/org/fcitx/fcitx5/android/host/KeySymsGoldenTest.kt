/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeySyms] against the golden files of lib/ime-core/tools/keysyms-gen.py (fcitx5 442edbc9's
 * `keySymFromString`, `keySymToString`, `keySymFromUnicode`, `keySymToUnicode`, key.cpp:622-800).
 * They are read from test resources, not from the vendored tree. The two Unicode files list only
 * what the plain rule (named in each test) does not give, so every code point and keysym is walked.
 * contract: goldens generated from fcitx's key.cpp, which mutation cannot see.
 */
class KeySymsGoldenTest {

    private fun rows(name: String): List<List<String>> =
        javaClass.getResourceAsStream("/keysyms/$name")!!.bufferedReader().readLines()
            .filter { !it.startsWith("#") }.map { it.split('\t') }

    private fun hexMap(name: String) = rows(name).associate { (a, b) -> a.toInt(16) to b.toInt(16) }

    private fun isLatin1(c: Int) = c in 0x20..0x7e || c in 0xa0..0xff

    @Test
    fun everyNameGivesItsSym() {
        val g = rows("name-to-sym.tsv")
        assertEquals(2910, g.size)
        for ((name, sym) in g) assertEquals(name, sym.toInt(16), KeySyms.fromString(name))
    }

    @Test
    fun singleCharactersAndUnknownNames() {
        assertEquals(0x61, KeySyms.fromString("a"))
        assertEquals(0x01004e2d, KeySyms.fromString("中"))
        assertEquals(0x01f600 or KeySyms.UNICODE_OFFSET, KeySyms.fromString("😀"))
        assertEquals(0, KeySyms.fromString(""))
        assertEquals(0, KeySyms.fromString("NoSuchKey"))
    }

    @Test
    fun everySymGivesItsNameAndBack() {
        val g = rows("sym-to-name.tsv").associate { (sym, name) -> sym.toInt(16) to name }
        assertEquals(2495, g.size)
        for (s in 0..0xffff) assertEquals("%x".format(s), g[s] ?: "", KeySyms.toString(s))
        for ((sym, name) in g) {
            assertEquals(name, KeySyms.toString(sym))
            assertEquals(name, sym, KeySyms.fromString(name)) // sym -> name -> sym
        }
    }

    @Test
    fun everyCodePointGivesFcitxSymAndBack() {
        val g = hexMap("unicode-to-sym.tsv")
        for (u in 0..0x1100ff) {
            val sym = KeySyms.fromUnicode(u)
            assertEquals("%x".format(u), g[u] ?: if (isLatin1(u)) u else u or KeySyms.UNICODE_OFFSET, sym)
            // unicode -> sym -> unicode: every code point with a keysym comes back
            if (sym != 0) assertEquals("%x".format(u), u, KeySyms.toUnicode(sym))
        }
    }

    @Test
    fun everySymGivesFcitxCodePoint() {
        val g = hexMap("sym-to-unicode.tsv")
        val syms = (0..0xffff) + (0x0100d7f0 until 0x0100e010) + (0x0110fff0 until 0x01110010)
        for (s in syms) {
            val plain = when {
                isLatin1(s) -> s
                s in 0x01000000..0x0110ffff -> s - 0x01000000
                else -> 0
            }
            assertEquals("%x".format(s), g[s] ?: plain, KeySyms.toUnicode(s))
        }
    }
}
