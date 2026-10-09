/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingIndexTest {

    /** The syllables [key] spells in full, spelt; none where it spells nothing. */
    private fun SpellingIndex.spells(key: String): Set<String> {
        var node = root
        for (c in key) {
            node = child(node, c)
            if (node < 0) return emptySet()
        }
        val m = matches(node) ?: return emptySet()
        return (0 until m.size).map { Syllables.spelling(m.syllable(it)) }.toSet()
    }

    @Test
    fun uAloneIsNoSpellingOfOuUnderTheFuzzyPair() {
        val index = SpellingIndex(setOf(Fuzzy.U_OU), typos = true)
        // with no initial the final is the syllable, and u is none, as in 双拼
        assertEquals(emptySet<String>(), index.spells("u"))
        assertEquals(setOf("ou"), index.spells("ou"))
        // after an initial the pair holds
        assertEquals(setOf("du", "dou"), index.spells("du"))
        // so xiu is no xi then 欧
        val g = PinyinSegmenter(setOf(Fuzzy.U_OU)).segment("xiu")
        assertTrue(g.edges(2).none { g.kind(it) == Kind.SYLLABLE })
    }
}
