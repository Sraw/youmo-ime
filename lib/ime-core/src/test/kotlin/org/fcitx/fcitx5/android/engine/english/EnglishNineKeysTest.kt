/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** English typed on the nine keys ([Action.NineKey]): each key any of its letters, the first word they may be shown and committed. */
class EnglishNineKeysTest {

    private val data = EnglishDecoderTest.data()

    private fun session() = EnglishSession(data)

    private fun EnglishSession.commit(word: String): String {
        word.forEach { apply(Action.Key(it)) }
        return apply(Action.Key(' ')).commit
    }

    private fun EnglishSession.nine(keys: String, upper: Boolean = false): Snapshot =
        keys.map { if (it in '2'..'9') apply(Action.NineKey(it, upper)) else apply(Action.Key(it)) }.last()

    @Test
    fun onTheNineKeysTheFirstWordTheyMayBeIsShownAndCommittedForThem() {
        val session = session()
        val typed = session.nine("843")
        assertEquals("the", typed.preedit)
        assertEquals(listOf("the", "there", "they", "then"), session.candidates(0, 10).map { it.text })
        assertEquals(-1, typed.cursor)
        assertTrue(session.picksByDigit)
        assertEquals("the ", session.apply(Action.Key(' ')).commit)
        assertFalse(session.picksByDigit)
        // punctuation, Enter, a key the app's: the word shown, never the keys
        session.nine("43")
        assertEquals("he, ", session.apply(Action.Key(',')).commit)
        session.nine("43")
        assertEquals("he", session.apply(Action.CommitRaw).commit)
        session.nine("43")
        val zero = session.apply(Action.Key('0'))
        assertEquals("he", zero.commit)
        assertFalse(zero.handled)
    }

    @Test
    fun onTheNineKeysCaseIsAsTyped() {
        assertEquals("The", session().let { s -> s.apply(Action.NineKey('8', upper = true)); s.nine("43") }.preedit)
        assertEquals("THE", session().nine("843", upper = true).preedit)
        // a name keeps its capital; i is I
        assertEquals("London", session().nine("566366").preedit)
        assertEquals("I", session().nine("4").preedit)
    }

    @Test
    fun onTheNineKeysALetterOrAnApostropheTypedIsItself() {
        assertEquals("hell", session().nine("4e55").preedit)
        val dont = session()
        assertEquals("don't", dont.nine("366'8").preedit)
        assertEquals(listOf("don't"), dont.candidates(0, 10).map { it.text })
        // an apostrophe where no word has one: nothing fits, each key its first letter, never a digit
        val none = session()
        assertEquals("d'mmt", none.nine("3'668").preedit)
        assertTrue(none.candidates(0, 10).isEmpty())
        // a hyphen ends the word, as a digit not the nine keys' does
        val hyphen = session()
        hyphen.nine("43")
        assertEquals("he", hyphen.apply(Action.Key('-')).commit)
    }

    @Test
    fun onTheNineKeysBackspaceTakesTheLastKeyAndATapLeavesTheInput() {
        val session = session()
        session.nine("4355")
        assertEquals("hello", session.apply(Action.Backspace).preedit)
        // a capital's key taken back takes its Shift with it
        val shifted = session()
        shifted.apply(Action.NineKey('4', upper = true))
        assertEquals("He", shifted.nine("3").preedit)
        shifted.apply(Action.Backspace)
        shifted.apply(Action.Backspace)
        assertEquals("he", shifted.nine("43").preedit)
        val tapped = session.apply(Action.Cursor(1))
        assertEquals(-1, tapped.cursor)
        assertEquals("hello", tapped.preedit)
    }

    @Test
    fun onTheNineKeysAWordCommittedIsLearnedAndTheUsersOwnFit() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        session.commit("zork")
        assertTrue(user.count("zork") > 0f)
        session.nine("9675")
        assertEquals(listOf("zork"), session.candidates(0, 10).map { it.text })
        assertEquals("zork ", session.apply(Action.Pick(0)).commit)
        assertEquals(2f, user.count("zork"))
    }

    // no word fits: each key's first letter, in the case typed, committed for the user to mend; a
    // guess, so not learned
    @Test
    fun noWordFitsEachKeysFirstLetterAndItIsNotLearned() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        session.apply(Action.NineKey('9', upper = true))
        assertEquals("Wmpj", session.nine("675").preedit)
        assertEquals("Wmpj ", session.apply(Action.Key(' ')).commit)
        assertEquals(0f, user.count("Wmpj"))
        assertEquals(0f, user.count("wmpj"))
        // a letter pinned among the keys stays itself
        val mixed = session()
        mixed.nine("9")
        mixed.apply(Action.NineKey('x'))
        assertEquals("wxm", mixed.nine("6").preedit)
    }

    // a letter a long press pins: itself among the keys; a word all pinned is no slip to correct,
    // committed as it is and learned, offered by its keys after
    @Test
    fun aPinnedLetterIsItselfAndAWordSoSpelledIsLearned() {
        assertEquals("hell", session().let { s -> s.nine("4"); s.apply(Action.NineKey('e')); s.nine("55") }.preedit)
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        "hte".forEach { session.apply(Action.NineKey(it)) }
        assertTrue(session.picksByDigit)
        assertEquals("hte ", session.apply(Action.Key(' ')).commit)
        assertTrue(user.count("hte") > 0f)
        session.nine("483")
        assertEquals("hte", session.candidates(0, 10).map { it.text }.first())
        // every letter pinned, a word the data has longer ones of (hell, hello): the pinned one first
        val prefix = session()
        "hel".forEach { prefix.apply(Action.NineKey(it)) }
        val offered = prefix.candidates(0, 10).map { it.text }
        assertEquals("hel", offered.first())
        assertTrue("hello still offered: $offered", "hello" in offered)
        assertEquals(emptyList<String>(), offered.drop(1).filterNot { it.startsWith("hel") })
        assertEquals("hel", prefix.apply(Action.Key(' ')).commit.trim())
        // a capital pinned keeps its case
        val capital = session()
        "Zo".forEach { capital.apply(Action.NineKey(it)) }
        assertEquals("Zo ", capital.apply(Action.Key(' ')).commit)
    }
}
