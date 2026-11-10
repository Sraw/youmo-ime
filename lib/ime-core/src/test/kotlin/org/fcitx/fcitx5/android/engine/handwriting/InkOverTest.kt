/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkOverTest {

    /**
     * Pinyin as far as these need: letters typed into the input, its candidates [words]' of it, a pick
     * committing one and offering [FOLLOW] after it (联想). Every action recorded.
     */
    private class FakePinyin(private val words: Map<String, List<String>>) : Session {
        val actions = ArrayList<Action>()
        var input = ""
        private var predicting = false
        override var learning = true
        var flags = 0
        var passed = 0

        override fun passedOn() {
            passed++
        }

        override fun reads(c: Char) = c in 'a'..'z'

        override fun apply(action: Action): Snapshot {
            actions += action
            return when (action) {
                is Action.Key -> if (reads(action.char)) type(input + action.char) else shown(commit = input.also { input = "" }).copy(handled = false)
                is Action.Letter -> type(input + action.letter)
                Action.Backspace -> type(input.dropLast(1))
                is Action.Pick -> words[input]?.getOrNull(action.index)?.let {
                    input = ""
                    predicting = true
                    shown(commit = it)
                } ?: shown()
                is Action.Context, Action.Reset -> type("")
                else -> shown()
            }
        }

        private fun type(to: String): Snapshot {
            input = to
            predicting = false
            return shown()
        }

        fun shown(commit: String = "") = Snapshot(
            commit, input, if (predicting) FOLLOW else words[input].orEmpty(), 0, hasPreviousPage = false, hasNextPage = false,
            handled = true, predicting = predicting,
        )

        override fun candidates(from: Int, count: Int) = words[input].orEmpty().drop(from).take(count).map { Choice(it) }

        override fun offers(index: Int) = setOf(Offer.FORGET)

        override fun fieldFlags(flags: Int) {
            this.flags = flags
        }

        companion object {
            val FOLLOW = listOf("们")
        }
    }

    private val pinyin = FakePinyin(mapOf("n" to listOf("你", "呢"), "ni" to listOf("你", "泥")))
    private var made = 0
    private var ended = HandwritingSession.NO_PIECE
    private val over = InkOver(pinyin) {
        made++
        HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex), ended = ended)
    }

    private fun write(piece: Int, vararg texts: String) = over.apply(Action.Ink(piece, read(*texts)))

    private fun type(keys: String) = keys.map { over.apply(Action.Key(it)) }.last()

    @Test
    fun anInkIsWrittenItsCandidatesPickedAndWhatWasPutInToldPinyin() {
        assertEquals(0, made)
        val shown = write(0, "天", "人", "山")
        assertEquals(1, made)
        assertEquals("天", shown.preedit)
        assertTrue(over.inking)
        assertEquals(listOf("天", "人", "山"), over.candidates(0, 5).map { it.text })
        val picked = over.apply(Action.Pick(1))
        assertEquals("人", picked.commit)
        // pinyin goes on from it
        assertEquals(Action.Context("人"), pinyin.actions.last())
        // a second ink: the same handwriting
        write(1, "山")
        assertEquals(1, made)
    }

    @Test
    fun aLetterAfterTheWritingPutsTheBestInThenTypesIt() {
        write(0, "天")
        val s = type("n")
        assertEquals("天", s.commit)
        assertEquals("n", s.preedit)
        assertEquals(listOf("你", "呢"), s.candidates)
        assertFalse(over.inking)
        // a letter of the nine keys' too
        over.apply(Action.Reset)
        write(1, "人")
        assertEquals("人", over.apply(Action.Letter('n')).commit)
    }

    @Test
    fun whileWrittenEveryActionIsHandwritings() {
        write(0, "天")
        write(1, "人")
        val before = pinyin.actions.size
        // backspace takes the last character back, a punctuation key puts the rest in
        assertEquals("天", over.apply(Action.Backspace).preedit)
        val key = over.apply(Action.Key(','))
        assertEquals("天", key.commit)
        assertFalse(key.handled)
        // none of it pinyin's but the context told after
        assertEquals(listOf<Action>(Action.Context("天")), pinyin.actions.drop(before))
    }

    @Test
    fun nothingIsWrittenWhilePinyinHasSomethingTyped() {
        type("ni")
        assertTrue(over.typing)
        val ink = Action.Ink(0, read("天"))
        val s = over.apply(ink)
        assertEquals("ni", s.preedit)
        assertFalse(over.inking)
        assertEquals(listOf("你", "泥"), over.candidates(0, 5).map { it.text })
        // dropped: taken (not held again), its piece ended (the pad wipes it)
        assertEquals(true, over.handwritingMade?.took(ink))
        assertEquals(0, over.handwritingMade?.ended)
    }

    @Test
    fun whatHandwritingShowsIsNoInputTyped() {
        assertNull(over.handwritingMade)
        write(0, "天")
        assertFalse(over.typing)
        assertEquals(2, write(1, "人").preedit.length)
    }

    @Test
    fun aSentenceWrittenAfterPinyinPutTextInGoesOnFromIt() {
        val gao = InkOver(FakePinyin(mapOf("gao" to listOf("高")))) {
            HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex))
        }
        gao.apply(Action.Ink(0, read("天")))
        assertEquals("天", gao.apply(Action.Pick(0)).commit)
        "gao".forEach { gao.apply(Action.Key(it)) }
        assertEquals("高", gao.apply(Action.Pick(0)).commit)
        // 山 after 高 (the model's bigram), though 人 is likelier alone: the text before told anew
        assertEquals("山", gao.apply(Action.Ink(1, read("人", "山", step = 0.1))).preedit)
    }

    @Test
    fun theOneLetterTakenBackFirstIsWritten() {
        type("n")
        // the keyboard deletes the letter it takes back as the first dot, then writes
        over.apply(Action.Backspace)
        assertEquals("天", write(0, "天").preedit)
    }

    @Test
    fun anInkTakingBackDeletesTheOneLetterTypedThenIsWritten() {
        type("n")
        val s = over.apply(Action.Ink(0, read("天"), takesBack = true))
        assertEquals("天", s.preedit)
        assertEquals("", pinyin.input)
        assertEquals(Action.Backspace, pinyin.actions.last { it !is Action.Context })
    }

    @Test
    fun anInkTakingBackDeletesNothingWhereMoreThanTheTapIsTyped() {
        // each row its own pinyin and InkOver: what one row typed is not the next one's start
        fun row(before: (InkOver) -> Unit, preedit: String, deleteBefore: Int = 0): () -> Unit = {
            val p = FakePinyin(mapOf("n" to listOf("你", "呢"), "ni" to listOf("你", "泥")))
            val o = InkOver(p) { HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex)) }
            before(o)
            val s = o.apply(Action.Ink(0, read("天"), takesBack = true))
            assertEquals(preedit, s.preedit)
            assertEquals(deleteBefore, s.deleteBefore)
            assertFalse(p.actions.contains(Action.Backspace))
            assertFalse(s.refines)
            assertFalse(s.picked)
        }
        fun keys(o: InkOver, k: String) = k.forEach { o.apply(Action.Key(it)) }
        assertRows(
            // another key (a long press's letter) after the tap: neither deleted, the ink dropped
            "a second letter after the tap" to row({ keys(it, "ni") }, preedit = "ni"),
            // the tap typed, then a pick before the stroke's reading came: the committed text is not touched
            "the tap picked away before the reading" to row({ keys(it, "n"); it.apply(Action.Pick(0)) }, preedit = "天"),
            // the cursor moved after the tap: the input is no longer the tap alone
            "a cursor move after the tap" to row({ keys(it, "n"); it.apply(Action.Cursor(0)) }, preedit = "n"),
            // a reset after the tap: nothing typed is left to take back
            "a reset after the tap" to row({ keys(it, "n"); it.apply(Action.Reset) }, preedit = "天"),
        )
    }

    // a letter while the handwriting shows: what its answer deletes comes off the sentence put in first, then off the text before it
    @Test
    fun aLettersDeleteComesOffWhatWasPutInFirst() {
        fun row(deletes: Int, commit: String, deleteBefore: Int): () -> Unit = {
            val p = FakePinyin(emptyMap())
            val o = InkOver(object : Session by p {
                override fun apply(action: Action): Snapshot = p.apply(action).copy(deleteBefore = if (action is Action.Key) deletes else 0)
            }) { HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex)) }
            o.apply(Action.Ink(0, read("天")))
            val s = o.apply(Action.Key('n'))
            assertEquals(commit, s.commit)
            assertEquals(deleteBefore, s.deleteBefore)
        }
        assertRows(
            "nothing deleted: the sentence stays" to row(0, "天", 0),
            "one deleted: off the sentence put in" to row(1, "", 0),
            "two deleted: the sentence, then one before it" to row(2, "", 1),
        )
    }

    @Test
    fun aVowelRefinedAfterItsKeyIsStillTakenBack() {
        // pinyin refines a, o, e (whole syllables) a moment after the key: the input is still the tap's
        for ((i, v) in "aoe".withIndex()) {
            over.apply(Action.Reset)
            type(v.toString())
            over.apply(Action.Refine)
            val s = over.apply(Action.Ink(i, read("天"), takesBack = true))
            assertEquals("$v", "天", s.preedit)
            assertEquals("$v", "", pinyin.input)
            over.apply(Action.Reset)
        }
    }

    @Test
    fun aTakenBackInkIsTheOneTheHandwritingTook() {
        type("n")
        val ink = Action.Ink(0, read("天"), takesBack = true)
        over.apply(ink)
        // Engines.inkSent asks with the action sent: not held again
        assertEquals(true, over.handwritingMade?.took(ink))
    }

    @Test
    fun anInkTakingBackAfterABackspaceAndALetterAgainCountsOnlyTheNewInput() {
        type("n")
        over.apply(Action.Backspace)
        type("n")
        assertEquals("天", over.apply(Action.Ink(0, read("天"), takesBack = true)).preedit)
    }

    @Test
    fun pinyinsFollowingWordsGoAsWritingStarts() {
        type("ni")
        val picked = over.apply(Action.Pick(0))
        assertEquals("你", picked.commit)
        assertTrue(picked.predicting)
        assertEquals("天", write(0, "天").preedit)
        // told pinyin the text before again: its offer gone
        assertEquals(Action.Context("你"), pinyin.actions.last())
        assertEquals("天", over.apply(Action.Select(0)).commit)
    }

    @Test
    fun aContextAndAResetGoToBoth() {
        over.apply(Action.Context("好"))
        write(0, "天")
        over.apply(Action.Reset)
        assertFalse(over.inking)
        assertEquals(Action.Reset, pinyin.actions.last())
        // what was written is gone with it
        assertEquals("", over.apply(Action.Backspace).preedit)
    }

    @Test
    fun learningAndFieldFlagsReachBoth() {
        over.learning = false
        over.fieldFlags(3)
        write(0, "天")
        assertEquals(false, over.handwritingMade?.learning)
        over.learning = true
        assertEquals(true, over.handwritingMade?.learning)
        assertTrue(pinyin.learning)
        assertEquals(3, pinyin.flags)
    }

    @Test
    fun aPieceEndedBeforeItWasMadeIsNotShown() {
        // as Engines makes it after dropping the sessions: the latest piece heard of ended
        ended = 4
        assertEquals("", write(3, "天").preedit)
        assertEquals("人", write(5, "人").preedit)
        assertEquals(5, over.handwritingMade?.latestPiece)
    }

    @Test
    fun whatShowsIsAskedOfWhichHasIt() {
        type("n")
        assertEquals(setOf(Offer.FORGET), over.offers(0))
        assertNull(over.reread())
        over.apply(Action.Backspace)
        write(0, "天")
        assertEquals(over.handwritingMade?.offers(0), over.offers(0))
        assertEquals("天", over.reread()?.preedit)
        assertFalse(over.takesEveryCharacter)
        assertFalse(over.picksByDigit)
        assertTrue(over.rereads)
        // a key the app took while the handwriting shows: the handwriting's, pinyin not told
        over.passedOn()
        assertEquals(0, pinyin.passed)
        over.apply(Action.Reset)
        over.passedOn()
        assertEquals(1, pinyin.passed)
    }
}
