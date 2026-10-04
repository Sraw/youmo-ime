/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WordPackTest {

    private fun parse(text: String) = WordPack.parse(text.lineSequence(), "p.words")

    private fun failsAt(line: Int, message: String, text: String) {
        try {
            parse(text)
            fail("read $text")
        } catch (e: SourceException) {
            assertEquals("p.words:$line: $message", e.message)
        }
    }

    @Test
    fun aPackNamesItsLayerAndScoresItsWords() {
        val pack = parse("﻿# youmo words 1\n# layer: 2026q1\n# from the pipeline\n\n搭子\tda'zi\t-5.6\n绿 lü -4\n")
        assertEquals("2026q1", pack.layer)
        assertEquals(listOf(entry("搭子", "da", "zi"), entry("绿", "lv")), pack.words.map { it.entry })
        assertEquals(listOf(-5.6f, -4f), pack.words.map { it.score })
        assertEquals(WordPack.DEFAULT_LAYER, parse("# youmo words 1\n").layer)
        assertTrue(WordPack.isPack("﻿# youmo words 1\r"))
        // a later version is a pack still, so it is not taken for a dictionary; just not one this reads
        assertTrue(WordPack.isPack("# youmo words 2"))
        assertFalse(WordPack.isPack("你 ni 0"))
    }

    @Test
    fun whatIsNotAPackSaysWhere() {
        failsAt(1, "not a word pack: expected \"# youmo words 1\"", "你 ni 0\n")
        failsAt(1, "not a word pack: expected \"# youmo words 1\"", "")
        failsAt(1, "a word pack of another version: expected \"# youmo words 1\"", "# youmo words 2\n搭子 da'zi -1\n")
        try {
            WordPack.parse(emptySequence())
            fail("read nothing")
        } catch (e: SourceException) {
            assertEquals("pack:1: not a word pack: empty", e.message)
        }
        failsAt(2, "bad layer name \"a b\"", "# youmo words 1\n# layer: a b\n")
        failsAt(2, "bad layer name \"\"", "# youmo words 1\n# layer:\n")
        failsAt(2, "bad layer name \"a,b\"", "# youmo words 1\n# layer: a,b\n")
        failsAt(2, "bad layer name \"${"x".repeat(65)}\"", "# youmo words 1\n# layer: ${"x".repeat(65)}\n")
        failsAt(3, "expected \"word pin'yin log10P\"", "# youmo words 1\n搭子 da'zi -1\n搭子 da'zi\n")
        failsAt(2, "expected \"word pin'yin log10P\"", "# youmo words 1\n搭子 da'zi 0.5\n")
        failsAt(2, "expected \"word pin'yin log10P\"", "# youmo words 1\n搭子 da'zi NaN\n")
        failsAt(2, "expected \"word pin'yin log10P\"", "# youmo words 1\n搭子 da -1\n")
        failsAt(2, "expected \"word pin'yin log10P\"", "# youmo words 1\n搭子 xx'zi -1\n")
    }
}
