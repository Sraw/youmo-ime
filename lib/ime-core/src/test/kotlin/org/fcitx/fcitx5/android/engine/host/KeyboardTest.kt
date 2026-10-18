/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.english.EnglishDecoderTest
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.GlideDecoderTest
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {

    /**
     * Reads lower-case letters; offers the input upper-cased, then the input with a `!`. Picking
     * the second reads only the first letter, leaving the rest typed. A pick then offers `next`.
     * Another key commits the first candidate. Two [Action.Refine] after a letter turn the
     * candidates round.
     */
    private class FakeSession(private val nineKeys: Boolean = false) : Session {
        val actions = ArrayList<Action>()
        val learned = ArrayList<Boolean>()
        private var input = ""
        private var predicting = false
        private var slices = 0
        private var refined = false

        override var learning = true

        override fun reads(c: Char) = c in 'a'..'z' || nineKeys && c in '2'..'9'

        override fun candidates(from: Int, count: Int) = all().drop(from).take(count).map { Choice(it) }
        override fun offers(index: Int) = emptySet<Offer>()

        private fun all() = when {
            predicting -> listOf("next")
            input.isEmpty() -> emptyList()
            else -> listOf(input.uppercase(), input.take(1) + "!").let { if (refined) it.reversed() else it }
        }

        override fun apply(action: Action): Snapshot {
            actions += action
            learned += learning
            if (action == Action.Refine) {
                if (--slices == 0) refined = true
            } else {
                slices = 0
                refined = false
            }
            var commit = ""
            var handled = true
            when (action) {
                is Action.Key -> {
                    predicting = false
                    if (reads(action.char)) {
                        input += action.char
                        slices = 2
                    } else {
                        commit = input.uppercase()
                        input = ""
                        handled = false
                    }
                }
                Action.Backspace -> if (input.isEmpty()) {
                    predicting = false
                    handled = false
                } else {
                    input = input.dropLast(1)
                }
                is Action.Select, is Action.Pick -> {
                    val index = if (action is Action.Select) action.index else (action as Action.Pick).index
                    val picked = all().getOrNull(index)
                    when {
                        picked == null -> handled = input.isNotEmpty()
                        predicting -> {
                            commit = picked
                            predicting = false
                        }
                        index == 1 -> {
                            commit = picked
                            input = input.drop(1)
                        }
                        else -> {
                            commit = picked
                            input = ""
                            predicting = true
                        }
                    }
                }
                Action.CommitRaw -> {
                    commit = input
                    input = ""
                }
                Action.Reset, is Action.Context -> {
                    input = ""
                    predicting = false
                }
                Action.NextPage, Action.PreviousPage, is Action.Forget, is Action.Pin, is Action.Unpin, is Action.Block, Action.Refine, Action.Ink, Action.Glide, is Action.Syllable, is Action.Letter, is Action.Cursor, is Action.Step -> {}
            }
            val shown = all()
            return Snapshot(commit, input, shown, 0, false, false, handled, predicting, refines = slices > 0)
        }
    }

    private val session = FakeSession()
    private val keyboard = Keyboard(session, nineKeys = false)

    private fun char(c: Char) = keyboard.onEvent(EngineEvent.CHAR, c.code)

    private fun type(text: String) = text.forEach { char(it) }

    @Test
    fun aSyllablePickComesThroughAsItsIndexAndList() {
        val nine = FakeSession(nineKeys = true)
        val keyboard = Keyboard(nine, nineKeys = true)
        for ((index, id) in listOf(0 to 0, 5 to 7, Action.Syllable.MAX - 1 to Action.Syllable.IDS - 1)) {
            keyboard.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(index, id).code)
        }
        assertEquals(Keyboard.SYLLABLES.last, Keyboard.syllableKey(Action.Syllable.MAX - 1, Action.Syllable.IDS - 1))
        assertEquals(
            listOf(Action.Syllable(0, 0), Action.Syllable(5, 7), Action.Syllable(Action.Syllable.MAX - 1, Action.Syllable.IDS - 1)),
            nine.actions,
        )
    }

    @Test
    fun onlyOnTheNineKeysIsEscapeWithNothingTypedSwallowedOrACharacterASyllable() {
        // a table whose codes are digits reads 2 as the nine keys do
        val digits = FakeSession(nineKeys = true)
        val table = Keyboard(digits, nineKeys = false)
        assertFalse(table.onEvent(EngineEvent.ESCAPE, 0).handled)
        val c = Keyboard.syllableKey(0, 0)
        assertFalse(table.onEvent(EngineEvent.CHAR, c.code).handled)
        assertEquals(listOf<Action>(Action.Key(c)), digits.actions)
        assertTrue(Keyboard(FakeSession(), nineKeys = true).onEvent(EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun offTheNineKeysADigitTheSessionDoesNotReadPicksWhileComposing() {
        // a table whose codes are the digits 2 to 9: 1 is no code, it picks the first
        val digits = FakeSession(nineKeys = true)
        val table = Keyboard(digits, nineKeys = false)
        table.onEvent(EngineEvent.CHAR, '2'.code)
        table.onEvent(EngineEvent.CHAR, '1'.code)
        assertEquals(listOf(Action.Key('2'), Action.Select(0)), digits.actions)
    }

    @Test
    fun lettersTheSessionReadsAreTyped() {
        type("ni")
        val s = char('h')
        assertTrue(s.handled)
        assertEquals("nih", s.preedit)
        assertEquals(listOf(Action.Key('n'), Action.Key('i'), Action.Key('h')), session.actions)
    }

    @Test
    fun whileTheUserPausesTheSessionRefinesTillItHasNoMoreToDo() {
        assertFalse(keyboard.onEvent(EngineEvent.REFINE, 0).handled)
        assertTrue(session.actions.isEmpty())
        type("ab")
        // a slice that changed nothing is not shown again
        val first = keyboard.onEvent(EngineEvent.REFINE, 0)
        assertFalse(first.handled)
        assertTrue(first.refines)
        val second = keyboard.onEvent(EngineEvent.REFINE, 0)
        assertTrue(second.handled)
        assertFalse(second.refines)
        assertEquals(listOf("a!", "AB"), second.candidates)
        session.actions.clear()
        assertFalse(keyboard.onEvent(EngineEvent.REFINE, 0).handled)
        assertTrue(session.actions.isEmpty())
        // what was committed with the last key is not committed again
        char('c')
        char(',')
        val afterPunctuation = keyboard.onEvent(EngineEvent.REFINE, 0)
        assertEquals("", afterPunctuation.commit)
        assertFalse(afterPunctuation.handled)
        // nor what a pick that left some typed committed
        type("ab")
        char('2')
        val afterPick = keyboard.onEvent(EngineEvent.REFINE, 0)
        assertEquals("", afterPick.commit)
        assertFalse(afterPick.handled)
    }

    @Test
    fun withNothingTypedOtherKeysAreTheApps() {
        for (c in " 1,A") assertFalse(char(c).handled)
        // characters still reach the session, to end what it would run on from
        assertEquals(" 1,A".map { Action.Key(it) }, session.actions)
        session.actions.clear()
        assertFalse(keyboard.onEvent(EngineEvent.ENTER, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.ESCAPE, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.PAGE_DOWN, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.OTHER, 0).handled)
        assertTrue(session.actions.isEmpty())
    }

    @Test
    fun anEmojiKeyEndsTheInputThoughNothingReadsIt() {
        type("ab")
        val s = keyboard.onEvent(EngineEvent.CHAR, 0x1F600)
        assertEquals("AB", s.commit)
        assertFalse(s.handled)
        assertEquals(Action.Key('\uFFFD'), session.actions.last())
    }

    @Test
    fun keysThatAreNoCharactersAreSwallowedWhileComposing() {
        type("ab")
        val s = keyboard.onEvent(EngineEvent.OTHER, 0)
        assertTrue(s.handled)
        assertEquals("ab", s.preedit)
        assertEquals("", s.commit)
        assertEquals(Action.Key('b'), session.actions.last())
    }

    // androidengine deletes before the cursor for any snapshot it shows, handled or not
    @Test
    fun theLastSnapshotShownAgainDeletesNothingAgain() {
        // a session that deletes before the cursor with each key, as English its space after a word
        var preedit = "a"
        val deleting = object : Session {
            override var learning = true
            override fun reads(c: Char) = true
            override fun candidates(from: Int, count: Int) = emptyList<Choice>()
            override fun offers(index: Int) = emptySet<Offer>()
            override fun apply(action: Action) = Snapshot(",", preedit, emptyList(), 0, false, false, true, false, deleteBefore = 1)
        }
        val keyboard = Keyboard(deleting, nineKeys = false)
        assertEquals(1, keyboard.onEvent(EngineEvent.CHAR, ','.code).deleteBefore)
        assertEquals(0, keyboard.onEvent(EngineEvent.OTHER, 0).deleteBefore)
        // nothing typed: a tap in the app's text
        preedit = ""
        keyboard.onEvent(EngineEvent.CHAR, ','.code)
        assertEquals(0, keyboard.onEvent(EngineEvent.CURSOR, 0).deleteBefore)
    }

    @Test
    fun whetherToLearnReachesTheSessionWithEachEvent() {
        keyboard.onEvent(EngineEvent.CHAR, 'a'.code, learning = false)
        keyboard.onEvent(EngineEvent.CHAR, ' '.code, learning = false)
        keyboard.onEvent(EngineEvent.CHAR, 'b'.code)
        assertEquals(listOf(false, false, true), session.learned)
    }

    @Test
    fun spaceAndDigitsPick() {
        type("ab")
        assertEquals("AB", char(' ').commit)
        type("ab")
        assertEquals("a!", char('2').commit)
        assertEquals(Action.Select(1), session.actions.last())
        // 0 is the tenth
        type("c")
        char('0')
        assertEquals(Action.Select(9), session.actions.last())
    }

    @Test
    fun enterKeepsTheInputAsTypedAndEscapeDropsIt() {
        type("ab")
        val enter = keyboard.onEvent(EngineEvent.ENTER, 0)
        assertEquals("ab", enter.commit)
        assertTrue(enter.handled)
        type("ab")
        val escape = keyboard.onEvent(EngineEvent.ESCAPE, 0)
        assertEquals("", escape.commit)
        assertEquals("", escape.preedit)
        assertTrue(escape.handled)
    }

    @Test
    fun punctuationEndsTheInputInTheSessionThenGoesToTheApp() {
        type("ab")
        val s = char(',')
        assertEquals("AB", s.commit)
        assertFalse(s.handled)
        assertEquals(Action.Key(','), session.actions.last())
        assertFalse(s.predicting)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun aPredictionIsDroppedByAKeyTheSessionDoesNotRead() {
        type("ab")
        assertTrue(char(' ').predicting)
        // space after a word is a space
        val s = char(' ')
        assertFalse(s.handled)
        assertFalse(s.predicting)
        assertEquals(Action.Key(' '), session.actions.last())
    }

    @Test
    fun theTextBeforeTheCursorGoesToTheSessionOnlyWithNothingShown() {
        keyboard.context("你好")
        assertEquals(Action.Context("你好"), session.actions.last())
        type("ab")
        keyboard.context("x")
        assertEquals(Action.Key('b'), session.actions.last())
        char(' ')
        // a prediction shown
        keyboard.context("x")
        assertEquals(Action.Select(0), session.actions.last())
        keyboard.onEvent(EngineEvent.RESET, 0)
        keyboard.context("x")
        assertEquals(Action.Context("x"), session.actions.last())
        // set for it as for a key: it comes first in a field just focused
        keyboard.context("y", learning = false)
        assertFalse(session.learning)
        keyboard.context("z")
        assertTrue(session.learning)
    }

    @Test
    fun aPredictionIsDroppedByEnterAndEscapeToo() {
        type("ab")
        char(' ')
        assertFalse(keyboard.onEvent(EngineEvent.ENTER, 0).handled)
        assertEquals(Action.Reset, session.actions.last())
        type("ab")
        char(' ')
        // escape only closes the offer
        assertTrue(keyboard.onEvent(EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun aLetterAfterAPredictionStartsNewInput() {
        type("ab")
        char(' ')
        val s = char('c')
        assertTrue(s.handled)
        assertEquals("c", s.preedit)
    }

    @Test
    fun pagesTurnOnlyWhileComposing() {
        type("ab")
        keyboard.onEvent(EngineEvent.PAGE_DOWN, 0)
        assertEquals(Action.NextPage, session.actions.last())
        keyboard.onEvent(EngineEvent.PAGE_UP, 0)
        assertEquals(Action.PreviousPage, session.actions.last())
    }

    @Test
    fun aTapInWhatIsTypedGoesToTheSession() {
        type("ab")
        keyboard.onEvent(EngineEvent.CURSOR, 1)
        assertEquals(Action.Cursor(1), session.actions.last())
    }

    @Test
    fun aPickIsByIndexAmongAll() {
        type("ab")
        assertEquals("a!", keyboard.onEvent(EngineEvent.PICK, 1).commit)
        assertEquals(Action.Pick(1), session.actions.last())
    }

    @Test
    fun aForgetIsByIndexAmongAll() {
        type("ab")
        val s = keyboard.onEvent(EngineEvent.FORGET, 1)
        assertEquals(Action.Forget(1), session.actions.last())
        assertEquals("ab", s.preedit)
        assertTrue(s.handled)
    }

    @Test
    fun aPinAnUnpinAndABlockAreByIndexAmongAll() {
        type("ab")
        keyboard.onEvent(EngineEvent.PIN, 1)
        assertEquals(Action.Pin(1), session.actions.last())
        keyboard.onEvent(EngineEvent.UNPIN, 0)
        assertEquals(Action.Unpin(0), session.actions.last())
        keyboard.onEvent(EngineEvent.BLOCK, 2)
        assertEquals(Action.Block(2), session.actions.last())
    }

    @Test
    fun backspaceAndResetGoToTheSession() {
        type("ab")
        assertEquals("a", keyboard.onEvent(EngineEvent.BACKSPACE, 0).preedit)
        keyboard.onEvent(EngineEvent.RESET, 0)
        assertEquals(Action.Reset, session.actions.last())
        assertFalse(keyboard.onEvent(EngineEvent.BACKSPACE, 0).handled)
        assertFalse(keyboard.onEvent(99, 0).handled)
    }

    /** Where English suggests nothing the app types the screen's keys, each told the session: a glide after one knows whether to space. */
    @Test
    fun aGlideAfterWhatTheAppTypedWhereEnglishSuggestsNothing() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        fun glided(): String? {
            english.offerGlide(GlideDecoderTest.glide("hello"))
            return keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit
        }
        assertFalse(keyboard.onEvent(EngineEvent.TYPED, 'c'.code).handled)
        assertEquals(" hello ", glided())
        // a key that is no char: nothing known before the cursor
        keyboard.onEvent(EngineEvent.ENTER, 0)
        assertEquals("hello ", glided())
        // nor is Enter's arg a char typed (a hardware key's, which androidengine sends as Enter)
        keyboard.onEvent(EngineEvent.ENTER, 'c'.code)
        assertEquals("hello ", glided())
    }

    /** The offer androidengine puts away where English suggests nothing (TYPED 0): the app's next read of the text is taken, not dropped as if still on show. */
    @Test
    fun theOfferPutAwayWhereEnglishSuggestsNothingLetsTheTextBeRead() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertTrue(keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).candidates.isNotEmpty())
        val away = keyboard.onEvent(EngineEvent.TYPED, 0)
        assertFalse(away.handled)
        assertTrue(away.candidates.isEmpty())
        keyboard.context("abc")
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals(" hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** TYPED past the BMP (an emoji) or to a session not English's: the key passed on, the session not told of it. */
    @Test
    fun whatTheAppTypedIsPassedOnPastTheBmpOrNotEnglish() {
        val fake = FakeSession()
        assertFalse(Keyboard(fake, nineKeys = false).onEvent(EngineEvent.TYPED, 'c'.code).handled)
        assertTrue(fake.actions.isEmpty())
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        keyboard.onEvent(EngineEvent.TYPED, 'c'.code)
        // the emoji forgets the c before it: no space before the glide
        assertFalse(keyboard.onEvent(EngineEvent.TYPED, 0x1F600).handled)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** Only spaces before the cursor: they are the glide's space, none added. */
    @Test
    fun aGlideAfterOnlySpacesAddsNone() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        keyboard.context("   ")
        english.offerGlide(GlideDecoderTest.glide("hello"))
        val commit = keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit
        assertFalse(commit.startsWith(" "))
        assertTrue(commit.endsWith(" "))
    }

    /** TYPED while a word is typed (the field's flags changed under it): the word committed as an Enter would, not dropped, and the char still known after it. */
    @Test
    fun whatTheAppTypedWhileAWordIsTypedCommitsIt() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        keyboard.onEvent(EngineEvent.CHAR, 'h'.code)
        keyboard.onEvent(EngineEvent.CHAR, 'e'.code)
        val typed = keyboard.onEvent(EngineEvent.TYPED, ' '.code)
        assertEquals("he", typed.commit)
        assertEquals("", typed.preedit)
        // the space typed is before the cursor: none added before the glide
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** TYPED of a letter or a Backspace while a word is typed: the word committed, the letter after it or its last char taken back. */
    @Test
    fun aLetterOrABackspaceTheAppTypedWhileAWordIsTypedCommitsIt() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        keyboard.onEvent(EngineEvent.CHAR, 'h'.code)
        keyboard.onEvent(EngineEvent.CHAR, 'e'.code)
        assertEquals("he", keyboard.onEvent(EngineEvent.TYPED, 'c'.code).commit)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals(" hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
        keyboard.onEvent(EngineEvent.TYPED, 0)
        keyboard.context("ab ")
        keyboard.onEvent(EngineEvent.CHAR, 'h'.code)
        assertEquals("h", keyboard.onEvent(EngineEvent.TYPED, BACKSPACE).commit)
        // the h taken back: the space before it last again
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** TYPED past the BMP while a word is typed: the word committed, then passed on as a key the session knows nothing of. */
    @Test
    fun anEmojiTheAppTypedWhileAWordIsTypedCommitsItAndForgets() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        keyboard.context("")
        keyboard.onEvent(EngineEvent.CHAR, 'h'.code)
        keyboard.onEvent(EngineEvent.CHAR, 'e'.code)
        val typed = keyboard.onEvent(EngineEvent.TYPED, 0x1F600)
        assertEquals("he", typed.commit)
        assertEquals("", typed.preedit)
        assertFalse(typed.handled)
        // the emoji after the word: nothing known before the cursor, no space before the glide
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** No text read (no context sent), the app's own Backspace takes the last char typed off what the session keeps, not all it knows. */
    @Test
    fun aBackspaceTheAppDidWithNothingReadKeepsTheCharBefore() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        "abc".forEach { keyboard.onEvent(EngineEvent.TYPED, it.code) }
        keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals(" hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** The app's own Backspace where English suggests nothing (TYPED of Session.ERASED): the last char known taken off, not all of it forgotten. */
    @Test
    fun aBackspaceTheAppDidTakesOffTheLastCharKnown() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        keyboard.context("")
        "abc".forEach { keyboard.onEvent(EngineEvent.TYPED, it.code) }
        assertFalse(keyboard.onEvent(EngineEvent.TYPED, BACKSPACE).handled)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals(" hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
        // a space taken back: the b before it again
        keyboard.onEvent(EngineEvent.TYPED, 0)
        keyboard.context("ab ")
        keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals(" hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
        // only the edge known (no Context): its char gone, nothing is known, no space added
        keyboard.onEvent(EngineEvent.ENTER, 0)
        keyboard.onEvent(EngineEvent.TYPED, 'c'.code)
        keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** The app's Backspaces with only the edge known (no Context): a char at a time, across a space, up to its last 32 chars before the spaces after them. */
    @Test
    fun theAppsBackspacesTakeTheEdgeACharAtATime() {
        fun glidedAfter(typed: String, backspaces: Int): String {
            val english = EnglishSession(EnglishDecoderTest.data())
            val keyboard = Keyboard(english, nineKeys = false)
            typed.forEach { keyboard.onEvent(EngineEvent.TYPED, it.code) }
            repeat(backspaces) { keyboard.onEvent(EngineEvent.TYPED, BACKSPACE) }
            english.offerGlide(GlideDecoderTest.glide("hello"))
            return keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit
        }
        assertEquals(" hello ", glidedAfter("ab c", 2))
        assertEquals(" hello ", glidedAfter("ab c", 3))
        assertEquals("hello ", glidedAfter("ab c", 4))
        // 33 typed, the edge keeps 32, and the spaces after them
        val long = "a".repeat(33) + "  "
        assertEquals(" hello ", glidedAfter(long, 2 + 31))
        assertEquals("hello ", glidedAfter(long, 2 + 32))
    }

    /** The app's Backspace: a code point taken that may belong to a cluster (outside the BMP, a mark), or one left last, has the session forget; a plain char or a lone "\n", it is taken off; a "\r\n", forgotten. */
    @Test
    fun theAppsBackspaceForgetsWhatACodePointOfAClusterMayLeave() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        fun glidedAfter(before: String): String {
            keyboard.onEvent(EngineEvent.TYPED, 0)
            keyboard.context(before)
            keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
            english.offerGlide(GlideDecoderTest.glide("hello"))
            return keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit
        }
        assertEquals(" hello ", glidedAfter("abcd"))
        assertEquals("hello ", glidedAfter("abc\uD83D\uDE00"))
        assertFalse(glidedAfter("").startsWith(" "))
        // a family's last person gone, a ZWJ left; a skin tone, a flag's half apart
        assertEquals("hello ", glidedAfter("ab\uD83D\uDC68\u200D\uD83D\uDC69"))
        assertEquals("hello ", glidedAfter("ab\uD83D\uDC4D\uD83C\uDFFD"))
        assertEquals("hello ", glidedAfter("ab\uD83C\uDDEF\uD83C\uDDF5"))
        // an accent: the editor may take it alone or with its letter, so nothing is known
        assertEquals("hello ", glidedAfter("abe\u0301"))
        // a variation selector left at the end: forgotten (EnglishGlideTest
        // .theAppsBackspaceForgetsTheTextWhereAClusterMayBeCut tells forgetting by the case)
        assertEquals("hello ", glidedAfter("ab\u2764\uFE0Fc"))
        // a keycap's mark (Me), a vowel sign (Mc), a ZWJ taken: forgotten
        assertEquals("hello ", glidedAfter("ab1\u20E3"))
        assertEquals("hello ", glidedAfter("ab\u0915\u093F"))
        assertEquals("hello ", glidedAfter("ab\u200D"))
        // a lone "\n" taken off; a CR taken (else "ab" known, a space added): forgotten
        assertEquals(" hello ", glidedAfter("ab\n"))
        assertEquals("hello ", glidedAfter("ab\r"))
        // a "\r\n" last, taken whole or in part by the editor: forgotten, so a second Backspace
        // leaves nothing known (the CR left is checked as the FE0F and ZWJ left above are)
        keyboard.onEvent(EngineEvent.TYPED, 0)
        keyboard.context("ab\r\n")
        repeat(2) { keyboard.onEvent(EngineEvent.TYPED, BACKSPACE) }
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** What was written in a chat's box now empty: the reply follows it, but nothing is before the cursor. */
    @Test
    fun aGlideInAFieldClearedSinceItWasWrittenHasNoSpaceBefore() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        fun glided(): String {
            english.offerGlide(GlideDecoderTest.glide("hello"))
            return keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit
        }
        // not learning, the service tells no such context (FieldPolicy.told): the edge kept empty all the same
        for (learning in listOf(true, false)) {
            keyboard.onEvent(EngineEvent.TYPED, 0)
            keyboard.context("ok", learning, cleared = true)
            assertFalse(glided().startsWith(" "))
            // a Backspace takes nothing of it (else "ok" would be before the cursor)
            keyboard.onEvent(EngineEvent.TYPED, 0)
            keyboard.context("ok", learning, cleared = true)
            keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
            assertFalse(glided().startsWith(" "))
            // a glide's word taken back leaves the field empty again
            keyboard.onEvent(EngineEvent.TYPED, 0)
            keyboard.context("ok", learning, cleared = true)
            glided()
            keyboard.onEvent(EngineEvent.BACKSPACE, 0)
            keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
            assertFalse(glided().startsWith(" "))
            // a char typed is before the cursor
            keyboard.onEvent(EngineEvent.TYPED, 0)
            keyboard.context("ok", learning, cleared = true)
            keyboard.onEvent(EngineEvent.TYPED, 'a'.code)
            assertEquals(" hello ", glided())
            // typed and taken back to the field's start, nothing before the cursor again
            keyboard.onEvent(EngineEvent.TYPED, 0)
            keyboard.context("ok", learning, cleared = true)
            "hi ".forEach { keyboard.onEvent(EngineEvent.TYPED, it.code) }
            repeat(4) { keyboard.onEvent(EngineEvent.TYPED, BACKSPACE) }
            assertFalse(glided().startsWith(" "))
        }
        // not cleared: after "ok", a space
        keyboard.onEvent(EngineEvent.TYPED, 0)
        keyboard.context("ok")
        assertEquals(" hello ", glided())
    }

    /** Only the edge known (no Context): an accent typed after a letter, the app's Backspace has the edge forgotten, the editor's unit unknown. */
    @Test
    fun theAppsBackspaceOnTheEdgeForgetsAfterAnAccent() {
        val english = EnglishSession(EnglishDecoderTest.data())
        val keyboard = Keyboard(english, nineKeys = false)
        "ab\u0301".forEach { keyboard.onEvent(EngineEvent.TYPED, it.code) }
        keyboard.onEvent(EngineEvent.TYPED, BACKSPACE)
        english.offerGlide(GlideDecoderTest.glide("hello"))
        assertEquals("hello ", keyboard.onEvent(EngineEvent.CHAR, Keyboard.GLIDE).commit)
    }

    /** TYPED to a session not English's while a word is typed: the word committed, the panel gone with the key. */
    @Test
    fun whatTheAppTypedWhileAWordIsTypedInAnotherSessionCommitsIt() {
        type("ab")
        val typed = keyboard.onEvent(EngineEvent.TYPED, 'c'.code)
        assertEquals("ab", typed.commit)
        assertEquals("", typed.preedit)
        assertTrue(typed.candidates.isEmpty())
        assertFalse(typed.handled)
    }

    private companion object {
        val BACKSPACE = Session.ERASED.single().code
    }
}
