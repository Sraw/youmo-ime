/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.phrase.PartsLookup
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinyin session's characters by their parts (Chaizi), after u. */
class PinyinSessionChaiziTest {

    private val data = sessionTestData()

    private val chaizi = mapOf("nihao" to listOf("妳" to "ni", "好" to "hao"), "anhao" to listOf("妟" to "yan"))

    private fun session(spell: Boolean = false) = PinyinSession(
        data, PinyinSegmenter(), spell = spell, pageSize = 20, user = UserModel(data.dictionary, data.vocabulary),
        emoji = { listOf("👋") }, chaizi = { chaizi[it].orEmpty() },
    )

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun uThenTheirPartsPinyinListsTheCharactersFirstEachWithItsReading() {
        val session = session()
        val s = session.type("uni'hao")
        assertEquals(listOf("妳", "好"), s.candidates.subList(0, 2))
        // a candidate the decoder read as well is listed once
        assertEquals(1, s.candidates.count { it == "好" })
        assertEquals(listOf("ni", "hao"), session.candidates(0, 2).map { it.hint })
        // nothing to pin, forget nor block; put in as text
        assertEquals(emptySet<Offer>(), session.offers(0))
        // no emoji of a reading of u…
        assertFalse("👋" in s.candidates)
        assertEquals("妳", session.apply(Select(0)).commit)
    }

    @Test
    fun uThenAVowelMayBeASlipForY_soTheReadingComesFirst() {
        val s = session().type("uanhao")
        assertEquals("妟", s.candidates[1])
        assertFalse(s.candidates[0] == "妟")
    }

    @Test
    fun withoutTheUOrOnlyPartOfASpellingNone() {
        assertFalse("妳" in session().type("nihao").candidates)
        assertFalse("妳" in session().type("unih").candidates)
        // 双拼's u is a key of its own
        assertFalse("妳" in session(spell = true).type("unihao").candidates)
    }

    // a polyphone by its parts is blocked in the reading the lookup picks of those its hint lists: the
    // hint goes to the lookup, so a reading the hint leaves out is never the one blocked
    @Test
    fun aPolyphoneByItsPartsIsBlockedInTheReadingTheLookupPicksOfItsHint() {
        val hints = ArrayList<String?>()
        val lookup = object : PartsLookup {
            override fun of(letters: String) = if (letters == "chichu") listOf("行" to "xing/heng") else emptyList()

            // the last the hint lists: the hint's first were it not passed
            override fun shownIn(text: String, before: String, hint: String?): String? {
                hints += hint
                return hint?.substringAfterLast('/')
            }
        }
        val blocked = ArrayList<UserModel.Entry>()
        val session = PinyinSession(
            data, PinyinSegmenter(), pageSize = 20, user = UserModel(data.dictionary, data.vocabulary), chaizi = lookup, block = { blocked += it },
        )
        val at = session.type("uchichu").candidates.indexOf("行")
        assertTrue(at >= 0)
        session.apply(Action.Block(at))
        assertTrue(hints.toString(), hints.isNotEmpty() && hints.all { it == "xing/heng" })
        assertEquals("行", blocked.single().text)
        assertEquals(listOf("heng"), blocked.single().syllables.map(Syllables::spelling))
    }
}
