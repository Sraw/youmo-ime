/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.Uat
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

    /** Pinyin as far as these need: typed letters, [words] as candidates, a pick offering [FOLLOW]; every action recorded. */
    private class FakePinyin(
        private val words: Map<String, List<String>>,
        // an input whose first candidate takes part of it: that word and the input left
        private val part: Map<String, Pair<String, String>> = emptyMap(),
    ) : Session {
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
                is Action.Pick -> pick(action.index)
                // space's: the first candidate, of [part] if it takes part of the input
                is Action.Select -> part[input]?.let { (w, rest) ->
                    input = rest
                    shown(commit = w)
                } ?: pick(action.index)
                Action.CommitRaw -> shown(commit = input.also { input = "" })
                is Action.Context, Action.Reset -> type("")
                else -> shown()
            }
        }

        private fun pick(index: Int) = words[input]?.getOrNull(index)?.let {
            input = ""
            predicting = true
            shown(commit = it)
        } ?: shown()

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
        assertEquals(Action.Context("人"), pinyin.actions.last())
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
        assertEquals(listOf<Action>(Action.Context("天")), pinyin.actions.drop(before))
    }

    @Test
    @Uat("HW-46")
    fun aFirstStrokeWhilePinyinHasSomethingTypedPutsItsFirstCandidateInThenWrites() {
        type("ni")
        val s = over.apply(Action.Ink(0, read("天")))
        assertEquals("你", s.commit)
        assertEquals("天", s.preedit)
        assertTrue(over.inking)
        assertEquals("", pinyin.input)
        assertEquals(Action.Select(0), pinyin.actions.last { it !is Action.Context })
        assertEquals(listOf("天"), over.candidates(0, 5).map { it.text })
    }

    @Test
    fun anInputItsFirstCandidateTakesPartOfIsPutInWordByWord() {
        fun row(words: Map<String, List<String>>, part: Map<String, Pair<String, String>>, commit: String): () -> Unit = {
            val p = FakePinyin(words, part)
            val o = InkOver(p) { HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex)) }
            "nihao".forEach { o.apply(Action.Key(it)) }
            val s = o.apply(Action.Ink(0, read("天")))
            assertEquals(commit, s.commit)
            assertEquals("", p.input)
            assertEquals("天", s.preedit)
        }
        assertRows(
            "the first candidate a word of it, then the rest's" to
                row(mapOf("hao" to listOf("好")), mapOf("nihao" to ("你" to "hao")), "你好"),
            // no candidate for what is left: put in as typed, as enter would
            "what has no candidate put in as typed" to
                row(emptyMap(), mapOf("nihao" to ("你" to "hao")), "你hao"),
        )
    }

    @Test
    fun twoCharactersWrittenShowAsOneSentence() {
        assertNull(over.handwritingMade)
        write(0, "天")
        assertEquals(2, write(1, "人").preedit.length)
    }

    // the pad's late reading of a piece already put in writes nothing; one row per way a piece ends
    @Test
    fun aLateReadingOfAPiecePutInLeavesWhatIsTyped() = assertRows(
        "put in by a letter: pinyin's input stays" to {
            write(0, "天")
            assertEquals("天", type("n").commit)
            val late = write(0, "天", "人")
            assertEquals("", late.commit)
            assertEquals("n", late.preedit)
            assertEquals("n", pinyin.input)
            assertFalse(over.inking)
            val finished = over.apply(Action.Ink(0))
            assertEquals("", finished.commit)
            assertEquals("n", finished.preedit)
            type("i")
            over.apply(Action.Pick(0))
            assertEquals(FakePinyin.FOLLOW, write(0, "天").candidates)
        },
        // the late reading shows what pinyin showed after the text put in, not the handwriting's nothing
        "put in by the pad's enter: what pinyin shows after it stays" to {
            val p = FakePinyin(mapOf("" to listOf("的")))
            val o = InkOver(p) { HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex)) }
            o.apply(Action.Ink(0, read("天")))
            assertEquals("天", o.apply(Action.CommitRaw).commit)
            val late = o.apply(Action.Ink(0, read("天", "人")))
            assertEquals("", late.commit)
            assertEquals(listOf("的"), late.candidates)
            assertFalse(o.inking)
        },
        "nothing pinyin showed yet: the handwriting's dropping" to {
            val o = InkOver(FakePinyin(emptyMap())) {
                HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex), ended = 0)
            }
            val late = o.apply(Action.Ink(0, read("天")))
            assertEquals("", late.commit)
            assertEquals("", late.preedit)
            assertFalse(o.inking)
        },
    )

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
    fun pinyinsFollowingWordsGoAsWritingStarts() {
        type("ni")
        val picked = over.apply(Action.Pick(0))
        assertEquals("你", picked.commit)
        assertTrue(picked.predicting)
        assertEquals("天", write(0, "天").preedit)
        assertEquals(Action.Context("你"), pinyin.actions.last())
        assertEquals("天", over.apply(Action.Select(0)).commit)
    }

    @Test
    fun theInputEndedPutsWhatIsWrittenIn() {
        over.apply(Action.Context("好"))
        write(0, "天")
        assertEquals("天", over.endInput().commit)
        assertFalse(over.inking)
        assertEquals(Action.Reset, pinyin.actions.last())
        assertEquals("", over.apply(Action.Backspace).preedit)
        type("n")
        assertEquals("", over.endInput().commit)
        assertEquals("", pinyin.input)
    }

    // 清除, Escape and the backspace swipe drop what is written as they drop pinyin's code
    @Test
    fun aResetDropsWhatIsWritten() {
        write(0, "天")
        val s = over.apply(Action.Reset)
        assertEquals("", s.commit)
        assertEquals("", s.preedit)
        assertFalse(over.inking)
        assertEquals(Action.Reset, pinyin.actions.last())
        assertEquals("人", write(1, "人").preedit)
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
