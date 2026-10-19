/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.CommitRaw
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Pick
import org.fcitx.fcitx5.android.engine.session.Action.PreviousPage
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionTest {

    private val data = sessionTestData()

    private fun session(pageSize: Int = PinyinSession.DEFAULT_PAGE_SIZE) = PinyinSession(data, PinyinSegmenter(), pageSize = pageSize)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    // more characters of shi than the decoder gives at first
    private val chars = (0 until 100).map { (0x4E00 + it).toChar().toString() }

    private fun many() = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .apply { chars.forEachIndexed { i, c -> unigram(c, -2f - i / 64f, 0f).entry(c, syl("shi")) } }
                .build().toByteArray(),
        ),
    )

    @Test
    fun theWholeInputIsReadFirst() {
        val s = session().type("nihaoma")
        assertEquals("你好吗", s.candidates.first())
        assertEquals("ni hao ma", s.preedit)
        assertEquals("", s.commit)
        assertTrue(s.handled)
        assertFalse(s.predicting)
    }

    @Test
    fun keysHandedOverTogetherReadAsTypedOneByOne() {
        val byKey = session()
        val atOnce = session()
        assertEquals(byKey.type("nihao"), atOnce.typeAll("nihao"))
        assertEquals(byKey.apply(Select(0)), atOnce.apply(Select(0)))
        // over the words offered after a commit
        assertEquals(byKey.type("ma"), atOnce.typeAll("ma"))
        // a separator the segmenter does not read: a key at a time
        byKey.apply(Reset)
        atOnce.apply(Reset)
        assertEquals(byKey.type("xi'an"), atOnce.typeAll("xi'an"))
    }

    @Test
    fun keysHandedOverTogetherReadAsTypedOneByOneWithARerankerAndARefiner() {
        // as Engines builds them, and what each was asked last: the decoder keeps what it read
        // before, and the two reorder what is shown, its last reading first, then its second
        class Weighed {
            var picked = ""
            val refined = ArrayList<String>()
            val session = PinyinSession(
                data, PinyinSegmenter(), pageSize = 20,
                refiner = { context, readings, scores, _ ->
                    refined += "$context $readings $scores"
                    1
                },
            ) { context, readings, scores ->
                picked = "$context $readings $scores"
                readings.lastIndex
            }
        }
        val byKey = Weighed()
        val atOnce = Weighed()
        val typed = byKey.session.type("nizai")
        assertTrue(typed.refines)
        assertEquals(typed, atOnce.session.typeAll("nizai"))
        assertEquals(byKey.picked, atOnce.picked)
        // the pause: the refiner weighs the input as last read
        assertEquals(byKey.session.apply(Action.Refine), atOnce.session.apply(Action.Refine))
        assertEquals(1, atOnce.refined.size)
        assertEquals(byKey.refined, atOnce.refined)
        // after a commit, which the reranker reads before the input
        assertEquals(byKey.session.apply(Select(0)), atOnce.session.apply(Select(0)))
        assertEquals(byKey.session.type("ma"), atOnce.session.typeAll("ma"))
        assertEquals(byKey.picked, atOnce.picked)
    }

    @Test
    fun withPredictionOffNothingIsOfferedAfterACommit() {
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        session.type("wo")
        val committed = session.apply(Select(0))
        assertEquals("我", committed.commit)
        assertFalse(committed.predicting)
        assertTrue(committed.candidates.isEmpty())
    }

    @Test
    fun pickingEverythingCommitsItAndPredictsWhatFollows() {
        val session = session()
        session.type("wo")
        val committed = session.apply(Select(0))
        assertEquals("我", committed.commit)
        assertEquals("", committed.preedit)
        // what the model saw after 我
        assertTrue(committed.predicting)
        assertEquals(listOf("再"), committed.candidates)
        val next = session.apply(Select(0))
        assertEquals("再", next.commit)
        assertEquals(listOf("见"), next.candidates)
    }

    @Test
    fun aModelWordNothingWasSeenAfterPredictsWhatFollowsItsEnd() {
        // 拟再 as a compiled word pack has it: a unigram with no bigram of its own
        val packed = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("再", -2.5f, 0f)
                    .unigram("见", -3f, 0f)
                    .unigram("拟再", -2f, 0f)
                    .bigram("再", "见", -0.5f, 0f)
                    .entry("再", syl("zai"))
                    .entry("见", syl("jian"))
                    .entry("拟再", syl("ni", "zai"))
                    .build().toByteArray(),
            ),
        )
        // what was committed is known to read ni zai as a learning session knows it
        val session = PinyinSession(packed, PinyinSegmenter(), user = UserModel(packed.dictionary, packed.vocabulary))
        val committed = session.type("nizai").let { session.apply(Select(it.candidates.indexOf("拟再"))) }
        assertEquals("拟再", committed.commit)
        assertTrue(committed.predicting)
        assertEquals(listOf("见"), committed.candidates)
    }

    @Test
    fun whatWasCommittedIsTheContextOfWhatIsTypedNext() {
        val session = session()
        assertEquals("在", session.type("zai").candidates.first())
        session.apply(Reset)
        session.type("wo")
        session.apply(Select(0))
        // a key ends the prediction; after 我, 再 is the likelier
        val typed = session.type("zai")
        assertFalse(typed.predicting)
        assertEquals("再", typed.candidates.first())
        // until reset
        session.apply(Reset)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun theRerankerPutsItsPickFirstAfterWhatWasCommitted() {
        val contexts = ArrayList<String>()
        // the decoder's last reading, whatever it is
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20) { context, readings, scores ->
            assertEquals(readings.size, scores.size)
            contexts += context
            readings.lastIndex
        }
        val plain = session(pageSize = 20).type("nizai").candidates
        val readings = plain.takeWhile { it.length == 2 }
        assertTrue(readings.size > 1)
        val s = session.type("nizai")
        assertEquals(listOf(readings.last()) + readings.dropLast(1), s.candidates.take(readings.size))
        assertEquals(plain.drop(readings.size), s.candidates.drop(readings.size))
        assertEquals(List(5) { "" }, contexts)
        // the pick is what a space commits, and what follows reads after it and the pieces picked
        val committed = session.apply(Select(0)).commit
        assertEquals(readings.last(), committed)
        contexts.clear()
        val typed = session.type("nizai")
        assertEquals(List(5) { committed }, contexts)
        session.apply(Select(typed.candidates.indexOf("拟")))
        assertEquals(committed + "拟", contexts.last())
        // gone with a reset
        session.apply(Reset)
        contexts.clear()
        session.type("ni")
        assertEquals(listOf("", ""), contexts)
    }

    @Test
    fun aPickPastTheFirstChoiceLiftsItsLayerUntilItComesFirst() {
        // 拟 of another layer, scored 2.5 orders under 你; a step of 1 lifts it over in three picks
        val layered = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .layers(listOf("base", "new"))
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你", -2f, 0f)
                    .unigram("拟", -4.5f, 0f)
                    .entry("你", syl("ni"))
                    .entry("拟", syl("ni"), layer = 1)
                    .build().toByteArray(),
            ),
        )
        val prior = LayerPrior(layered.layers, step = 1f, bound = 4f)
        val session = PinyinSession(layered, PinyinSegmenter(), prior = prior, prediction = false)
        assertEquals(listOf("你", "拟"), session.type("ni").candidates)
        // the first choice taken: nothing learned
        session.apply(Select(0))
        assertEquals(0f, prior[1], 0f)
        repeat(3) {
            assertEquals("你", session.type("ni").candidates[0])
            assertEquals("拟", session.apply(Select(1)).commit)
        }
        assertEquals(3f, prior[1], 0f)
        // 拟 first now, and taking it there teaches nothing more
        assertEquals(listOf("拟", "你"), session.type("ni").candidates)
        session.apply(Select(0))
        assertEquals(3f, prior[1], 0f)
        // 你 picked over it: back down one
        session.type("ni")
        session.apply(Select(1))
        assertEquals(2f, prior[1], 0f)
        // not while learning is off
        session.learning = false
        session.type("ni")
        session.apply(Select(1))
        assertEquals(2f, prior[1], 0f)
    }

    @Test
    fun whatTheRerankerPutFirstIsLearnedAsPickedOverTheDecoder() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, user = user) { _, readings, _ -> readings.lastIndex }
        // the decoder's best, second now: nothing new
        assertEquals("你在", session.type("nizai").candidates[1])
        session.apply(Select(1))
        assertEquals(0, user.size)
        // the reranker's: learned as one word, for the decoder to have it first without it
        val picked = session.type("nizai").candidates[0]
        assertEquals(picked, session.apply(Select(0)).commit)
        assertEquals(1, user.size)
        assertEquals(picked, PinyinSession(data, PinyinSegmenter(), user = user).type("nizai").candidates.first())
    }

    @Test
    fun aKeyTheAppTookEndsTheContextCommitted() {
        val session = session()
        session.type("wo")
        session.apply(Select(0))
        // an arrow the app took: 在 again, as with no context
        session.passedOn()
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun whatTheAppsBackspaceErasedIsNoContext() {
        val session = session()
        session.type("wo")
        session.apply(Select(0))
        // the app's Backspace erases 我 (the session's input empty): 在, as in an empty field
        assertFalse(session.apply(Action.Backspace).handled)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun whatTheAppsBackspaceErasedIsNoContextWhileLearningIsOff() {
        val session = session()
        session.learning = false
        session.type("wo")
        session.apply(Select(0))
        // the premise: not learning, what was committed is context all the same (我 then 再)
        val kept = session()
        kept.learning = false
        kept.type("wo")
        kept.apply(Select(0))
        assertEquals("再", kept.type("zai").candidates.first())
        assertFalse(session.apply(Action.Backspace).handled)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun whatTheAppsBackspaceLeftIsTheContext() {
        val session = session()
        session.type("wo")
        session.apply(Select(0))
        session.type("zai")
        session.apply(Select(0))
        // 在 erased, 我 left: as if the app's text were 我
        session.apply(Action.Backspace)
        val fresh = session().apply { apply(Action.Context("我")) }
        assertEquals(fresh.type("zai").candidates, session.type("zai").candidates)
    }

    @Test
    fun aCharacterPastTheBmpIsErasedWhole() {
        // 𠀀 (Ext-B) a word of the model's, so that what is committed after 我 ends with a surrogate pair
        val wide = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("我", -2f, 0f)
                    .unigram("𠀀", -2f, 0f)
                    .unigram("在", -2f, 0f)
                    .unigram("再", -2.5f, 0f)
                    .bigram("我", "再", -0.5f, 0f)
                    .entry("我", syl("wo"))
                    .entry("𠀀", syl("ke"))
                    .entry("在", syl("zai"))
                    .entry("再", syl("zai"))
                    .build().toByteArray(),
            ),
        )
        val session = PinyinSession(wide, PinyinSegmenter())
        session.type("wo")
        session.apply(Select(0))
        assertEquals("𠀀", session.type("ke").let { session.apply(Select(0)) }.commit)
        // one Backspace takes both halves: 我 is left, no lone surrogate after it to end the context
        session.apply(Action.Backspace)
        val fresh = PinyinSession(wide, PinyinSegmenter()).apply { apply(Action.Context("我")) }
        val after = session.type("zai").candidates
        assertEquals("再", after.first())
        assertEquals(fresh.type("zai").candidates, after)
    }

    @Test
    fun theTextTheAppHasIsTheContextOfWhatIsTypedNext() {
        val session = session()
        val context = session.apply(Action.Context("他说我"))
        // nothing is offered for it, as a prediction would be after a commit
        assertEquals(emptyList<String>(), context.candidates)
        assertEquals("再", session.type("zai").candidates.first())
        // text ending with no word of the model's is nothing to go on from
        session.apply(Action.Context("我 "))
        assertEquals("在", session.type("zai").candidates.first())
        session.apply(Action.Context("我"))
        session.apply(Reset)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun theAppsTextEndingWithWhatWasCommittedKeepsTheContextCommitted() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        session.type("wo")
        assertEquals("我", session.apply(Select(0)).commit)
        // the editor reports the cursor where the service did not expect it, but it did not move
        session.apply(Action.Context("他说我"))
        session.type("zai")
        assertEquals("我", contexts.last())
        // it did move
        session.apply(Reset)
        session.apply(Action.Context("他说你"))
        session.type("zai")
        assertEquals("他说你", contexts.last())
        // more committed than the host reads of the text: its text ends what was
        session.apply(Reset)
        session.apply(Action.Context("我".repeat(70)))
        session.type("zai")
        assertEquals("再", session.apply(Select(0)).commit)
        session.apply(Action.Context(("我".repeat(70) + "再").takeLast(64)))
        session.type("zai")
        assertEquals("我".repeat(70) + "再", contexts.last())
        // but not a word of it that happens to be
        session.apply(Action.Context("他说再"))
        session.type("zai")
        assertEquals("他说再", contexts.last())
    }

    @Test
    fun whileLearningIsOffTheAppsTextIsNoContext() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        session.learning = false
        session.apply(Action.Context("我"))
        assertEquals("在", session.type("zai").candidates.first())
        assertEquals(listOf(""), contexts.distinct())
        session.learning = true
        session.apply(Action.Context("我"))
        assertEquals("再", session.type("zai").candidates.first())
        assertEquals("我", contexts.last())
    }

    @Test
    fun theRerankerIsToldMoreThanItReadsSoACommitDropsNothingItDoes() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        val committed = StringBuilder()
        repeat(40) {
            session.type("nizai")
            committed.append(session.apply(Select(0)).commit)
        }
        session.type("nizai")
        assertTrue(committed.length > 64)
        assertEquals(committed.toString(), contexts.last())
        // cut where a pair of chars is not split
        val pairs = "\uD840\uDC00".repeat(100)
        assertEquals(pairs.substring(74) + "b", PinyinSession.tail("a" + pairs + "b"))
    }

    @Test
    fun whileLearningIsOffTheRerankerIsToldNothingCommitted() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        session.learning = false
        session.type("nizai")
        session.apply(Select(0))
        session.type("nizai")
        assertEquals(listOf(""), contexts.distinct())
    }

    @Test
    fun aPiecePickedIsTheContextOfTheRest() {
        val session = session(pageSize = 20)
        val s = session.type("nizai")
        assertEquals("你在", s.candidates.first())
        val picked = session.apply(Select(s.candidates.indexOf("拟")))
        assertEquals("", picked.commit)
        assertEquals("拟zai", picked.preedit)
        // after 拟, 再 is the likelier
        assertEquals("再", picked.candidates.first())
        assertEquals("拟再", session.apply(Select(0)).commit)
    }

    @Test
    fun backspaceDeletesAKeyAndThenThePieceItEmptied() {
        val session = session()
        val s = session.type("nihao")
        assertEquals("你hao", session.apply(Select(s.candidates.indexOf("你"))).preedit)
        assertEquals("你ha", session.apply(Backspace).preedit)
        assertEquals("你h", session.apply(Backspace).preedit)
        // the last key after 你 goes, and 你 with it: ni is read again
        val back = session.apply(Backspace)
        assertEquals("ni", back.preedit)
        assertEquals("你", back.candidates.first())
        assertEquals("n", session.apply(Backspace).preedit)
        val empty = session.apply(Backspace)
        assertEquals("", empty.preedit)
        assertEquals(emptyList<String>(), empty.candidates)
        assertTrue(empty.handled)
        // nothing typed: the app's backspace
        assertFalse(session.apply(Backspace).handled)
    }

    @Test
    fun pagesTurnWithinTheCandidates() {
        val session = session(pageSize = 1)
        val first = session.type("ni")
        assertEquals(0, first.page)
        assertFalse(first.hasPreviousPage)
        assertTrue(first.hasNextPage)
        val second = session.apply(NextPage)
        assertEquals(1, second.page)
        assertTrue(second.hasPreviousPage)
        assertEquals(1, first.candidates.size)
        // the last page does not turn further
        var s = second
        while (s.hasNextPage) s = session.apply(NextPage)
        assertEquals(s, session.apply(NextPage))
        // a pick is on the page shown
        val picked = session.apply(Select(0))
        assertEquals(s.candidates.single(), picked.commit)
        session.type("ni")
        assertEquals(0, session.apply(PreviousPage).page)
        // an index past the page picks nothing, but the key was for the candidates
        val past = session.apply(Select(1))
        assertEquals("", past.commit)
        assertTrue(past.handled)
        // with no candidates, space or a digit is the app's
        session.apply(Reset)
        assertFalse(session.apply(Select(0)).handled)
        assertThrows(IllegalArgumentException::class.java) { session(pageSize = 0) }
    }

    @Test
    fun aHostListsAndPicksAmongAllCandidatesWhateverThePage() {
        val session = session(pageSize = 1)
        val s = session.type("ni")
        val all = session.candidates(0, 100).map { it.text }
        assertEquals(all.size, s.total)
        assertTrue(all.size > 1)
        assertEquals(all.drop(1).take(1), session.candidates(1, 1).map { it.text })
        assertTrue(session.candidates(all.size, 5).isEmpty())
        // the second, though page one shows only the first
        assertEquals(all[1], session.apply(Pick(1)).commit)
        // nothing there: the pick is ignored while something is typed
        session.type("ni")
        assertTrue(session.apply(Pick(all.size)).handled)
        session.apply(Reset)
        assertFalse(session.apply(Pick(0)).handled)
    }

    @Test
    fun theKeysReadAreThoseOfTheSegmenterAndASeparatorWithinInput() {
        val session = session()
        assertTrue(session.reads('a'))
        assertFalse(session.reads('1'))
        assertFalse(session.reads(','))
        assertFalse(session.reads('\''))
        session.type("xi")
        assertTrue(session.reads('\''))
        // 双拼 reads what its scheme gives a part, ; in 微软
        val ms = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.MICROSOFT), spell = true)
        assertTrue(ms.reads(';'))
        assertFalse(session.reads(';'))
    }

    @Test
    fun 双拼SemicolonIsAFinalAfterAnInitialAndPunctuationElsewhere() {
        for (scheme in listOf(ShuangpinScheme.MICROSOFT, ShuangpinScheme.ZIGUANG)) {
            val session = PinyinSession(data, ShuangpinSegmenter(scheme), spell = true)
            // nothing typed: the app's punctuation key
            val empty = session.apply(Key(';'))
            assertFalse(empty.handled)
            assertEquals("", empty.preedit)
            // after a whole syllable: the first candidate, then the app's ；
            val ni = session.type("ni")
            val after = session.apply(Key(';'))
            assertFalse(after.handled)
            assertEquals(ni.candidates.first(), after.commit)
            assertEquals("", after.preedit)
            // after an initial: ing, read into the input (no 星 in the data: shown as typed)
            val xing = session.type("x;")
            assertTrue(xing.handled)
            assertEquals("x;", xing.preedit)
            val segmenter = ShuangpinSegmenter(scheme)
            assertTrue(segmenter.readsAfter("x", ';'))
            assertTrue(segmenter.readsAfter("nix", ';'))
            assertFalse(segmenter.readsAfter("", ';'))
            assertFalse(segmenter.readsAfter("ni", ';'))
            assertFalse(segmenter.readsAfter("x;", ';'))
            session.apply(Reset)
        }
    }

    @Test
    fun enterCommitsWhatWasTyped() {
        val session = session()
        val s = session.type("nihao")
        session.apply(Select(s.candidates.indexOf("你")))
        val raw = session.apply(CommitRaw)
        assertEquals("你hao", raw.commit)
        assertEquals("", raw.preedit)
        // text as typed ends the context: nothing to predict
        assertFalse(raw.predicting)
        assertFalse(session.apply(CommitRaw).handled)
    }

    @Test
    fun separatorsShowWhereTheyWereTyped() {
        val session = session()
        assertEquals("xi'an", session.type("xi'an").preedit)
        session.apply(Reset)
        val xian = session.type("xian")
        assertEquals("先", xian.candidates.first())
        assertEquals("xian", xian.preedit)
        session.apply(Reset)
        // a separator first is the app's, and ends predictions shown
        assertFalse(session.apply(Key('\'')).handled)
        session.type("wo")
        assertTrue(session.apply(Select(0)).predicting)
        val separator = session.apply(Key('\''))
        assertFalse(separator.handled)
        assertFalse(separator.predicting)
        assertEquals(emptyList<String>(), separator.candidates)
        // input nothing reads: kept as typed
        assertEquals("vvv", session.type("vvv").preedit)
    }

    @Test
    fun 双拼IsShownSpeltOut() {
        val session = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.XIAOHE), spell = true)
        val s = session.type("nihc")
        assertEquals("你好", s.candidates.first())
        assertEquals("ni hao", s.preedit)
        // a key still being typed shows as typed
        session.apply(Reset)
        assertEquals("ni h", session.type("nih").preedit)
    }

    @Test
    fun aFuzzyReadingShowsWhatWasTyped() {
        val session = PinyinSession(data, PinyinSegmenter(setOf(Fuzzy.Z_ZH)))
        val s = session.type("zong")
        assertEquals("中", s.candidates.first())
        assertEquals("zong", s.preedit)
    }

    @Test
    fun aKeyNotReadCommitsTheFirstCandidateAndEndsTheContext() {
        val session = session()
        session.type("nihao")
        val s = session.apply(Key(','))
        assertEquals("你好", s.commit)
        assertFalse(s.handled)
        assertFalse(s.predicting)
        assertTrue(s.candidates.isEmpty())
        // 我 would have 再 follow it; after the space it is no context
        session.type("wo")
        assertTrue(session.apply(Select(0)).predicting)
        assertFalse(session.apply(Key(' ')).handled)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun aKeyNotReadAfterAPieceCommitsTheRestAsTheFirstCandidateReadsIt() {
        val session = session(pageSize = 20)
        val s = session.type("nizai")
        session.apply(Select(s.candidates.indexOf("拟")))
        // 再 reads the rest
        assertEquals("拟再", session.apply(Key('.')).commit)
        // nothing to commit: the key is the app's all the same
        val separator = session.apply(Key('\''))
        assertEquals("", separator.commit)
        assertFalse(separator.handled)
    }

    /**
     * The decoder's words past the first it gives are read as the list is shown, or turned, that
     * far: none is left out, and the count is told once they are all read.
     */
    @Test
    fun wordsPastTheFirstAreReadAsTheListIsShownThatFar() {
        val session = PinyinSession(many(), PinyinSegmenter(), pageSize = 10)
        val shown = session.type("shi")
        assertEquals("not counted yet", -1, shown.total)
        assertTrue(shown.hasNextPage)
        assertEquals(chars.drop(90).take(5), session.candidates(90, 5).map { it.text })
        assertEquals(chars, session.candidates(0, 200).map { it.text })
        // counted now, and the last page turned to
        var page = session.apply(NextPage)
        repeat(8) { page = session.apply(NextPage) }
        assertEquals(100, page.total)
        assertEquals(chars.takeLast(10), page.candidates)
        assertFalse(page.hasNextPage)
    }

    /** A page turned past the first words reads the decoder's next. */
    @Test
    fun aPageTurnedPastTheFirstWordsReadsTheNext() {
        val session = PinyinSession(many(), PinyinSegmenter(), pageSize = 10)
        session.type("shi")
        var page = session.apply(NextPage)
        repeat(5) { page = session.apply(NextPage) }
        assertEquals(chars.subList(60, 70), page.candidates)
        page = session.apply(NextPage)
        assertEquals(chars.subList(70, 80), page.candidates)
        // the page shown's first
        assertEquals(chars[70], session.apply(Select(0)).commit)
    }

    /** A phrase placed past the decoder's first words is there from the start: it does not move as the rest are read. */
    @Test
    fun aPhrasePlacedPastTheFirstWordsStaysWhereItIs() {
        val session = PinyinSession(many(), PinyinSegmenter(), pageSize = 10, phraseBook = PhraseBook(CustomPhrases.parse("shi,70=短语")) {})
        session.type("shi")
        val first = session.candidates(0, 80).map { it.text }
        // order 70: the 70th
        assertEquals("短语", first[69])
        assertEquals(first, session.candidates(0, 200).map { it.text }.take(80))
    }

    /** A word forgotten on a page past the decoder's first words went back to the first page. */
    @Test
    fun aWordForgottenPastTheFirstWordsStaysOnThePageShown() {
        val many = many()
        val user = UserModel(many.dictionary, many.vocabulary)
        val session = PinyinSession(many, PinyinSegmenter(), pageSize = 10, user = user)
        session.type("shi")
        repeat(7) { session.apply(NextPage) }
        // never learned, so not offered (PY-33)
        assertFalse(Offer.FORGET in session.offers(72))
        // learned only as the word before another: something to forget, its place not moved
        val word = session.candidates(72, 1).single().text
        user.learn(UserModel.Entry(word, syl("shi")), listOf(UserModel.Entry(chars[0], syl("shi"))))
        session.apply(Reset)
        session.type("shi")
        repeat(7) { session.apply(NextPage) }
        assertEquals(word, session.candidates(72, 1).single().text)
        assertTrue(Offer.FORGET in session.offers(72))
        assertEquals(7, session.apply(Action.Forget(72)).page)
        assertFalse(user.hasLearned(listOf(UserModel.Entry(word, syl("shi")))))
    }
}
