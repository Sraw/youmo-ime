/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/** Where English puts the user's phrases among its words, and which of what is shown they are. */
class EnglishPhrasesTest {
    private val day = { GregorianCalendar(2026, Calendar.OCTOBER, 31) }
    private val nine = NineKeyLetters.STANDARD

    private fun phrases(file: String) = EnglishPhrases(PhraseBook(CustomPhrases.parse(file)), day)

    private fun keys(letters: String) = letters.map(nine::key).joinToString("")

    private class Row(val why: String, val file: String, val typed: String, val nineKeys: Boolean, val words: List<String>, val shown: List<String>, val noWord: Boolean)

    @Test
    @Uat("WD-19")
    fun wherePhrasesGo() {
        val rows = listOf(
            Row("letters: never first, the word space commits kept", "the,1=the end\n", "the", false, listOf("the", "then"), listOf("the", "the end", "then"), false),
            Row("letters, no word: the phrase alone", "qm,1=张三\n", "qm", false, emptyList(), listOf("张三"), false),
            Row("nine keys, no word: the guess space commits first", "qm,1=张三\n", keys("qm"), true, emptyList(), listOf("pm", "张三"), true),
            // no guess shown where there is no phrase to show after it: as with no phrases at all
            Row("nine keys, no word, the phrase turned off: nothing", "qm,-1=张三\n", keys("qm"), true, emptyList(), emptyList(), true),
            Row("nine keys, no word, a key that only leads to a phrase: nothing", "qmx,1=张三\n", keys("qm"), true, emptyList(), emptyList(), true),
            Row("nine keys with words: the words first", "qm,1=张三\n", keys("qm"), true, listOf("on"), listOf("on", "张三"), false),
            Row("nine keys with words, the phrase turned off: the words alone", "qm,-1=张三\n", keys("qm"), true, listOf("on"), listOf("on"), false),
        )
        assertRows(rows, { it.why }) { r ->
            val p = phrases(r.file)
            val shown = p.place(r.typed, if (r.nineKeys) nine else null, r.words) { "pm" }
            assertEquals(r.shown, shown)
            assertEquals(r.noWord, p.noWord(shown))
            assertEquals(r.shown.indices.filter { r.shown[it] == "张三" || r.shown[it] == "the end" }, shown.indices.filter { p.at(shown, it) != null })
        }
    }

    // which candidates are phrases is known of the list place made alone: an equal one the session
    // built anew (a prediction, a correction tapped back) has none
    @Test
    fun aListEqualButNotTheOnePlaceMadeHasNoPhrase() {
        val p = phrases("qm,1=张三\n")
        val shown = p.place(keys("qm"), nine, emptyList()) { "pm" }
        assertNotNull(p.at(shown, 1))
        val copy = ArrayList(shown)
        assertNull(p.at(copy, 1))
        assertFalse(p.noWord(copy))
        assertTrue(p.noWord(shown))
        assertFalse(p.delete(copy, 1))
    }
}
