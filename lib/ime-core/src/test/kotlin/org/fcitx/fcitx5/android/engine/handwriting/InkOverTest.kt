/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

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
        // another key (a long press's letter) after the tap: neither deleted, the ink dropped
        type("ni")
        val s = over.apply(Action.Ink(0, read("天"), takesBack = true))
        assertEquals("ni", s.preedit)
        assertFalse(pinyin.actions.contains(Action.Backspace))
    }

    @Test
    fun anInkTakingBackDeletesNothingOnceTheTapIsPickedAway() {
        // the tap typed, then a pick before the stroke's reading came: the committed text is not touched
        type("n")
        assertEquals("你", over.apply(Action.Pick(0)).commit)
        val s = over.apply(Action.Ink(0, read("天"), takesBack = true))
        assertEquals(0, s.deleteBefore)
        assertFalse(pinyin.actions.contains(Action.Backspace))
        assertEquals("天", s.preedit)
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
        over.passedOn()
    }
}
