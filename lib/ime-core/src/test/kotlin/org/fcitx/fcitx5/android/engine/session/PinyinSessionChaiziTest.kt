/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
