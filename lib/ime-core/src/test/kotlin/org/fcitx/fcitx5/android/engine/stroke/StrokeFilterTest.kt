/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StrokeFilterTest {

    // made-up orders, enough to tell them apart: 望 is written two ways
    private val dict = """
        ...
        养	nphhhpn
        羊	nphhhs
        望	nzphhsh
        望	hszph
        样	hspnnph
        杨	hspnzp
        王	hhsh
        〇	z
        网	szpnpn
        我	phshzp
        沃	nnhpn
        们	pszs
    """.trimIndent()

    private val strokes = Strokes(CodeTable.load(ByteBuffer.wrap(Strokes.read(dict.reader().buffered(), "stroke").build().toByteArray()))) { 0f }

    /** Pinyin as far as the filter sees it: candidates by input, a pick committed whole or as its first character ([partial]); every action recorded. */
    private class FakePinyin(
        words: Map<String, List<String>>,
        private val partial: Map<String, String> = emptyMap(),
        private val deletes: Int = 0,
    ) : Session {
        val words = words.toMutableMap()
        val actions = ArrayList<Action>()
        val asked = ArrayList<Pair<Int, Int>>()
        val whys = ArrayList<Int>()
        var input = ""
        override var learning = true

        override fun reads(c: Char) = c in 'a'..'z' || c == ';'

        override fun endsInput(c: Char) = !reads(c) || c == ';'

        override fun apply(action: Action): Snapshot {
            actions += action
            return when (action) {
                is Action.Key -> if (endsInput(action.char)) leave() else type(input + action.char)
                Action.Backspace -> type(input.dropLast(1))
                is Action.Pick -> pick(action.index)
                is Action.Select -> pick(action.index)
                Action.Reset -> type("")
                is Action.Forget -> drop(action.index)
                is Action.Block -> drop(action.index)
                else -> shown()
            }
        }

        private fun type(to: String): Snapshot {
            input = to
            return shown()
        }

        private fun pick(index: Int): Snapshot {
            val word = all().getOrNull(index) ?: return shown()
            val left = partial[word]
            input = left.orEmpty()
            return shown(commit = word).copy(deleteBefore = deletes)
        }

        private fun leave(): Snapshot {
            val first = all().firstOrNull()
            val commit = if (first == null) input else first + partial[first].orEmpty()
            input = ""
            return shown(commit).copy(handled = false, deleteBefore = deletes)
        }

        private fun drop(index: Int): Snapshot {
            words[input] = all().filterIndexed { i, _ -> i != index }
            return shown()
        }

        private fun all() = words[input].orEmpty()

        fun shown(commit: String = "") = Snapshot(
            commit, input, all().take(PAGE), 0, hasPreviousPage = false, hasNextPage = all().size > PAGE,
            handled = true, predicting = false, total = all().size, actionable = true,
        )

        override fun candidates(from: Int, count: Int): List<Choice> {
            asked += from to count
            return all().drop(from).take(count).map { Choice(it, "hint-$it") }
        }

        override fun offers(index: Int) = if (index in all().indices) setOf(Offer.FORGET) else emptySet()

        override fun why(index: Int): Why? {
            whys += index
            return null
        }

        override fun reread(): Snapshot? = if (input.isEmpty()) null else shown()

        companion object {
            const val PAGE = 5
        }
    }

    private val yang = listOf("样", "杨", "Yang", "养", "羊", "🐑", "王", "望", "网")
    private val pinyin = FakePinyin(mapOf("yan" to listOf("言"), "yang" to yang, "yangg" to listOf("阳"), "women" to listOf("我们", "我", "沃"), "men" to listOf("们")), mapOf("我" to "men"))
    private val filter = StrokeFilter(pinyin, { strokes }, pageSize = 3)

    private fun type(keys: String) = keys.map { filter.apply(Action.Key(it)) }.last()

    private fun all() = filter.candidates(0, 100).map { it.text }

    @Test
    fun aStrokeKeepsThoseWhoseFirstCharacterStartsSoInTheirOrderAnEmojiAfterItsCharacter() {
        type("yang")
        val s = filter.apply(Action.Stroke('n'))
        assertEquals("yang 丶", s.preedit)
        assertEquals(listOf("养", "羊", "🐑"), s.candidates)
        assertTrue(s.hasNextPage)
        assertEquals(listOf("养", "羊", "🐑", "望"), all())
        assertEquals(listOf("hint-养", "hint-羊", "hint-🐑"), s.hints)
        assertTrue(filter.filtering)
    }

    @Test
    fun theNextStrokeIsTheSameCharactersNextEitherOrderOfOneWrittenTwoWays() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        assertEquals(listOf("养", "羊", "🐑"), filter.apply(Action.Stroke('p')).candidates)
        filter.apply(Action.Backspace)
        filter.apply(Action.Backspace)
        filter.apply(Action.Stroke('h'))
        assertEquals(listOf("样", "杨", "望"), filter.apply(Action.Stroke('s')).candidates)
        assertEquals("yang 一丨", filter.apply(Action.Refine).preedit)
    }

    @Test
    fun aCharacterTheMainlandWritesGoesByThatOrderAloneOneItDoesNotByAny() {
        // 望 in the mainland order; its Taiwan order keeps it no longer, 样 and 杨 by the table's
        val mainland = mapOf('望'.code to "nzphhsh")
        val f = StrokeFilter(FakePinyin(mapOf("yang" to yang)), { strokes }, pageSize = 3, mainland = { cp, drawn -> mainland[cp]?.startsWith(drawn) })
        "yang".forEach { f.apply(Action.Key(it)) }
        f.apply(Action.Stroke('h'))
        assertEquals(listOf("样", "杨"), f.apply(Action.Stroke('s')).candidates)
        assertEquals(listOf("样", "杨"), f.candidates(0, 100).map { it.text })
        f.apply(Action.Backspace)
        f.apply(Action.Backspace)
        f.apply(Action.Stroke('n'))
        assertEquals(listOf("养", "羊", "🐑", "望"), f.candidates(0, 100).map { it.text })
        assertEquals(listOf("望"), f.apply(Action.Stroke('z')).candidates)
    }

    @Test
    fun aPickIsTheSessionsCandidateAtItsOwnIndexTheStrokesGone() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        val s = filter.apply(Action.Pick(1))
        assertEquals(Action.Pick(4), pinyin.actions.last())
        assertEquals("羊", s.commit)
        assertFalse(filter.filtering)
        type("yang")
        filter.apply(Action.Stroke('n'))
        // space, a digit: of the page shown, the second page's first is 望
        assertEquals("养", filter.apply(Action.Select(0)).commit)
        assertEquals(Action.Pick(3), pinyin.actions.last())
        type("yang")
        filter.apply(Action.Stroke('n'))
        filter.apply(Action.NextPage)
        assertEquals("望", filter.apply(Action.Select(0)).commit)
        assertEquals(Action.Pick(7), pinyin.actions.last())
    }

    @Test
    fun aLongPressIsOfTheSessionsCandidateAndTheStrokesStay() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        assertEquals(setOf(Offer.FORGET), filter.offers(3))
        assertEquals(emptySet<Offer>(), filter.offers(4))
        val s = filter.apply(Action.Forget(1))
        assertEquals(Action.Forget(4), pinyin.actions.last())
        assertEquals("yang 丶", s.preedit)
        assertTrue(filter.filtering)
        assertEquals(listOf("养", "🐑", "望"), s.candidates)
        filter.apply(Action.Block(2))
        assertEquals(Action.Block(6), pinyin.actions.last())
        assertEquals(listOf("养", "🐑"), all())
        filter.why(1)
        assertEquals(listOf(4), pinyin.whys)
        assertNull(filter.why(2))
        assertEquals(listOf(4), pinyin.whys)
    }

    @Test
    fun pagesTurnOverWhatIsKeptAndNoneLeftTheKeyIsTheApps() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        val second = filter.apply(Action.NextPage)
        assertEquals(listOf("望"), second.candidates)
        assertTrue(second.hasPreviousPage)
        assertFalse(second.hasNextPage)
        assertEquals(3, second.first)
        assertEquals(listOf("🐑", "望"), filter.candidates(2, 5).map { it.text })
        // no page past the last: the key still the filter's, something being kept
        val last = filter.apply(Action.NextPage)
        assertEquals(1, last.page)
        assertTrue(last.handled)
        assertEquals(listOf("养", "羊", "🐑"), filter.apply(Action.PreviousPage).candidates)
        assertFalse(filter.apply(Action.PreviousPage).hasPreviousPage)
        filter.apply(Action.Backspace)
        filter.apply(Action.Stroke('z'))
        assertFalse(filter.apply(Action.NextPage).handled)
        assertFalse(filter.apply(Action.PreviousPage).handled)
    }

    @Test
    fun aRefineThatChangesNothingLooksThroughNothingAgainAReadAlways() {
        type("yang")
        filter.apply(Action.Stroke('z'))
        val looked = pinyin.asked.size
        assertTrue(looked > 0)
        assertEquals("yang 乛", filter.apply(Action.Refine).preedit)
        // one read of those looked through, to tell they are as they were
        assertEquals(looked + 1, pinyin.asked.size)
        assertEquals(0 to yang.size, pinyin.asked.last())
        // a reread may change the order anywhere: looked through again, the same as before
        assertEquals("yang 乛", filter.reread()?.preedit)
        val reread = pinyin.asked.size
        assertTrue(reread > looked)
        pinyin.words["yang"] = listOf("望", "王")
        val s = filter.reread()!!
        assertEquals(emptyList<String>(), s.candidates)
        assertTrue(pinyin.asked.size > reread)
        filter.apply(Action.Backspace)
        assertEquals(listOf("望"), filter.apply(Action.Stroke('n')).candidates)
    }

    @Test
    fun theOrderRefinedUnderTheStrokesIsFilteredAgainOnTheSamePage() {
        val p = FakePinyin(mapOf("yang" to yang))
        val f = StrokeFilter(p, { strokes }, pageSize = 3)
        "yang".forEach { f.apply(Action.Key(it)) }
        f.apply(Action.Stroke('n'))
        assertEquals(listOf("望"), f.apply(Action.NextPage).candidates)
        assertEquals(listOf("羊", "🐑"), f.candidates(1, 2).map { it.text })
        // the sentence models' order comes in under the strokes: 望 first now, the second page still shown
        p.words["yang"] = listOf("望", "王", "养", "样", "羊", "🐑")
        val s = f.apply(Action.Refine)
        assertEquals("yang 丶", s.preedit)
        assertEquals(listOf("🐑"), s.candidates)
        assertEquals(1, s.page)
        assertEquals(listOf("望", "养", "羊", "🐑"), f.candidates(0, 10).map { it.text })
        f.apply(Action.Select(0))
        assertEquals(Action.Pick(5), p.actions.last())
    }

    @Uat("PY-157")
    @Test
    fun aRefineThatReordersOnlyPastThePageTheSessionShowsIsFilteredAgain() {
        val p = FakePinyin(mapOf("yang" to yang))
        val f = StrokeFilter(p, { strokes }, pageSize = 3)
        "yang".forEach { f.apply(Action.Key(it)) }
        f.apply(Action.Stroke('n'))
        assertEquals(listOf("养", "羊", "🐑", "望"), f.candidates(0, 10).map { it.text })
        p.words["yang"] = listOf("样", "杨", "Yang", "养", "羊", "王", "望", "网", "🐑")
        f.apply(Action.Refine)
        assertEquals(listOf("养", "羊", "望"), f.candidates(0, 10).map { it.text })
        assertEquals("望", f.apply(Action.Select(2)).commit)
        assertEquals(Action.Pick(6), p.actions.last())
    }

    @Uat("PY-157")
    @Test
    fun aCandidateGoneFromTheSessionUnderTheStrokesPutsNothingUpAndIsLookedForAgain() {
        val p = FakePinyin(mapOf("yang" to yang))
        val f = StrokeFilter(p, { strokes }, pageSize = 3)
        "yang".forEach { f.apply(Action.Key(it)) }
        f.apply(Action.Stroke('n'))
        assertEquals("羊", f.candidates(1, 1).single().text)
        p.words["yang"] = yang - "羊"
        val actions = p.actions.size
        assertEquals("", f.apply(Action.Pick(1)).commit)
        assertEquals(actions, p.actions.size)
        assertTrue(f.filtering)
        assertEquals(listOf("养", "🐑", "望"), f.candidates(0, 10).map { it.text })
    }

    @Uat("PY-157")
    @Test
    fun aCandidateTheSessionMovedUnderTheStrokesIsPickedAndPressedWhereItIsNow() {
        val p = FakePinyin(mapOf("yang" to yang))
        val f = StrokeFilter(p, { strokes }, pageSize = 3)
        "yang".forEach { f.apply(Action.Key(it)) }
        f.apply(Action.Stroke('n'))
        // the session's list moved with nothing told: each kept is one place down
        p.words["yang"] = listOf("王") + yang
        f.why(1)
        assertEquals(listOf(5), p.whys)
        f.apply(Action.Forget(2))
        assertEquals(Action.Forget(6), p.actions.last())
        val q = FakePinyin(mapOf("yang" to yang))
        val g = StrokeFilter(q, { strokes }, pageSize = 3)
        "yang".forEach { g.apply(Action.Key(it)) }
        g.apply(Action.Stroke('n'))
        q.words["yang"] = listOf("王") + yang
        assertEquals("羊", g.apply(Action.Pick(1)).commit)
        assertEquals(Action.Pick(5), q.actions.last())
    }

    @Test
    fun lingIsACharacterNotAMark() {
        val p = FakePinyin(mapOf("ling" to listOf("零", "〇", "🐑")))
        val f = StrokeFilter(p, { strokes }, pageSize = 3)
        "ling".forEach { f.apply(Action.Key(it)) }
        assertEquals(listOf("〇", "🐑"), f.apply(Action.Stroke('z')).candidates)
    }

    @Test
    fun backspaceTakesTheLastStrokeThenThePinyin() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        filter.apply(Action.Stroke('p'))
        val actions = pinyin.actions.size
        assertEquals("yang 丶", filter.apply(Action.Backspace).preedit)
        val s = filter.apply(Action.Backspace)
        assertEquals("yang", s.preedit)
        assertEquals(yang.take(FakePinyin.PAGE), s.candidates)
        assertEquals("the session had none of the strokes", actions, pinyin.actions.size)
        assertEquals("yan", filter.apply(Action.Backspace).preedit)
        assertEquals(Action.Backspace, pinyin.actions.last())
    }

    @Test
    fun aPickOfPartLeavesTheRestTypedWithNoStrokes() {
        type("women")
        filter.apply(Action.Stroke('p'))
        assertEquals(listOf("我们", "我"), all())
        val s = filter.apply(Action.Pick(1))
        assertEquals("我", s.commit)
        assertEquals("men", s.preedit)
        assertEquals(listOf("们"), s.candidates)
        assertTrue(s.handled)
        assertFalse(filter.filtering)
    }

    @Test
    fun noneKeptLeavesTheBarEmptyAndTheStrokeToTakeBack() {
        type("yang")
        val s = filter.apply(Action.Stroke('z'))
        assertEquals("yang 乛", s.preedit)
        assertEquals(emptyList<String>(), s.candidates)
        assertEquals(0, s.total)
        assertFalse(s.hasNextPage)
        assertEquals("yang 乛", filter.apply(Action.Select(0)).preedit)
        assertEquals(yang.take(FakePinyin.PAGE), filter.apply(Action.Backspace).candidates)
    }

    @Test
    fun aKeyDropsTheStrokesAndIsTheSessions() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        val s = filter.apply(Action.Key('g'))
        assertEquals("yangg", s.preedit)
        assertEquals(listOf("阳"), s.candidates)
        assertFalse(filter.filtering)
    }

    @Test
    fun aStrokeWithNothingTypedDoesNothing() {
        val s = filter.apply(Action.Stroke('n'))
        assertEquals("", s.preedit)
        assertTrue(s.handled)
        assertTrue(pinyin.actions.isEmpty())
        assertFalse(filter.filtering)
        val none = StrokeFilter(pinyin, { null })
        "yang".forEach { none.apply(Action.Key(it)) }
        assertEquals("yang", none.apply(Action.Stroke('n')).preedit)
        assertFalse(none.filtering)
    }

    @Test
    fun theSessionsCandidatesAreLookedThroughOnlyAsFarAsAskedAndTheLookAhead() {
        val many = (1..5000).map { if (it % 1000 == 0) "养" else "样" }
        val deep = FakePinyin(mapOf("y" to many))
        val f = StrokeFilter(deep, { strokes }, pageSize = 3, lookAhead = 2000)
        f.apply(Action.Key('y'))
        val s = f.apply(Action.Stroke('n'))
        // 养 at 999 and 1999 within the look-ahead; those after it are not looked at
        assertEquals(listOf("养", "养"), s.candidates)
        assertEquals(2, s.total)
        assertTrue(deep.asked.all { (from, count) -> from + count <= 2000 })
        assertNull(f.why(5))
    }

    @Test
    fun aKeyThatEndsTheInputCommitsTheFirstCandidateTheStrokesKeepThenGoesOn() {
        // what the user sees: the strokes' first committed, the strokes gone, the key still the app's
        class Row(val why: String, val key: Char, val stroke: Char, val commit: String)
        assertRows(
            listOf(
                Row("a mark ends the input: what the strokes keep first (养), not the session's own first (样)", '，', 'n', "养"),
                Row("so does the nine keys' 0", '0', 'n', "养"),
                Row("a key read but not where it stands (a 双拼 `;`) ends it as a mark does", ';', 'n', "养"),
                Row("the strokes keep nothing: the key is the session's, which commits its own first", '，', 'z', "样"),
            ),
            { it.why },
        ) { row ->
            val pinyin = FakePinyin(mapOf("yang" to yang))
            val filter = StrokeFilter(pinyin, { strokes }, pageSize = 3)
            "yang".forEach { filter.apply(Action.Key(it)) }
            filter.apply(Action.Stroke(row.stroke))
            val s = filter.apply(Action.Key(row.key))
            assertEquals(row.commit, s.commit)
            assertEquals("", s.preedit)
            assertFalse(s.handled)
            assertFalse(filter.filtering)
            assertEquals(Action.Key(row.key), pinyin.actions.last())
        }
    }

    @Test
    fun aKeyThatEndsTheInputAfterAFirstCandidateOfPartOfItLeavesTheRestTyped() {
        // 我 of women, kept first by its stroke: men stays typed as the user sees it, never the session's guess at it
        val pinyin = FakePinyin(mapOf("women" to listOf("沃", "我"), "men" to listOf("们")), mapOf("我" to "men"))
        val filter = StrokeFilter(pinyin, { strokes }, pageSize = 3)
        "women".forEach { filter.apply(Action.Key(it)) }
        filter.apply(Action.Stroke('p'))
        val s = filter.apply(Action.Key('，'))
        assertEquals("我", s.commit)
        assertEquals("men", s.preedit)
        assertEquals(listOf("们"), s.candidates)
        assertTrue(s.handled)
        assertFalse(filter.filtering)
        assertFalse(Action.Key('，') in pinyin.actions)
    }

    @Test
    fun aKeyThatEndsTheInputDeletesWhatThePickAndTheKeyEachDelete() {
        // the kept first picked, then the key left to the session: both one's deletes before the cursor happen
        val pinyin = FakePinyin(mapOf("yang" to yang), deletes = 1)
        val filter = StrokeFilter(pinyin, { strokes }, pageSize = 3)
        "yang".forEach { filter.apply(Action.Key(it)) }
        filter.apply(Action.Stroke('n'))
        val s = filter.apply(Action.Key('，'))
        assertEquals("养", s.commit)
        assertEquals(2, s.deleteBefore)
    }

    @Test
    @Uat("PY-113")
    fun readingsAndLookupsOfAKeptCandidateAreOfItsOwnPlace() {
        val inner = object : Session by pinyin {
            override fun readingOf(index: Int) = listOf(WordReading(yang[index], 0, IntArray(0)))
            override fun lookedUp(index: Int) = yang[index]
        }
        val filter = StrokeFilter(inner, { strokes }, pageSize = 3)
        "yang".forEach { filter.apply(Action.Key(it)) }
        val s = filter.apply(Action.Stroke('n'))
        assertEquals(listOf("养", "羊", "🐑"), s.candidates)
        assertEquals(s.candidates, s.candidates.indices.map { filter.lookedUp(it) })
        assertEquals(s.candidates, s.candidates.indices.map { filter.readingOf(it)?.single()?.text })
    }

    // 首页单字: the first stays, then [singles] characters with their emoji, then the words they pass
    private fun singles(words: List<String>, singles: Int, pageSize: Int = 3): Pair<FakePinyin, StrokeFilter> {
        val pinyin = FakePinyin(mapOf("x" to words))
        val filter = StrokeFilter(pinyin, { strokes }, pageSize = pageSize, singles = singles)
        filter.apply(Action.Key('x'))
        filter.apply(Action.Stroke('h'))
        return pinyin to filter
    }

    private val wang = listOf("王者", "王国", "样子", "王", "🙂", "杨树", "样", "杨", "望")

    @Test
    fun theFirstSinglesComeRightAfterTheFirstWithTheirEmojiTheWordsAfterThemInOrder() {
        val rows = listOf(
            Triple(0, "none: as the session has them", wang),
            Triple(1, "one: 王 and its 🙂 after the first", listOf("王者", "王", "🙂", "王国", "样子", "杨树", "样", "杨", "望")),
            Triple(2, "two: 样 next, the words after in their order", listOf("王者", "王", "🙂", "样", "王国", "样子", "杨树", "杨", "望")),
            Triple(5, "five, more than there are: every one", listOf("王者", "王", "🙂", "样", "杨", "望", "王国", "样子", "杨树")),
        )
        assertRows(rows, { (n, why) -> "$n singles, $why" }) { (n, _, expected) ->
            val (_, filter) = singles(wang, n)
            assertEquals(expected, filter.candidates(0, 100).map { it.text })
        }
        val pinyin = FakePinyin(mapOf("x" to wang))
        val filter = StrokeFilter(pinyin, { strokes }, pageSize = 3, singles = 2)
        filter.apply(Action.Key('x'))
        assertEquals(listOf("王者", "王", "🙂"), filter.apply(Action.Stroke('h')).candidates)
    }

    @Test
    fun aFirstAloneIsNoneOfThemTooFewAreAsManyAsThereAreOnesAlreadyThereStillMoved() {
        // two singles asked for, each row the session's candidates and what the filter shows
        val rows = listOf(
            Triple("the first a character alone: it stays, and two more come after it", listOf("王", "王者", "王国", "样", "杨"), listOf("王", "样", "杨", "王者", "王国")),
            Triple("one alone: that one", listOf("王者", "王国", "样子", "望"), listOf("王者", "望", "王国", "样子")),
            Triple("none: as it was", listOf("王者", "王国"), listOf("王者", "王国")),
            Triple("one already second: the next moved up after it all the same", listOf("王者", "王", "样子", "样"), listOf("王者", "王", "样", "样子")),
            Triple("an emoji after the first stays with it", listOf("王者", "😀", "王国", "王"), listOf("王者", "😀", "王", "王国")),
        )
        assertRows(rows, { it.first }) { (_, words, shown) ->
            assertEquals(shown, singles(words, 2).second.candidates(0, 9).map { it.text })
        }
    }

    @Test
    fun aSingleFarDownComesUpPastManyWords() {
        val words = (1..20).map { "王$it" } + "样"
        val (pinyin, filter) = singles(words, 1, pageSize = 5)
        assertEquals(listOf("王1", "样", "王2", "王3", "王4"), filter.candidates(0, 5).map { it.text })
        assertEquals(listOf("王1", "样", "王2", "王3", "王4"), filter.apply(Action.Refine).candidates)
        assertEquals("样", filter.apply(Action.Select(1)).commit)
        assertEquals(Action.Pick(20), pinyin.actions.last())
    }

    @Test
    @Uat("PY-136")
    fun aMovedCandidatesOffersPinBlockReasonAndCardAreOfItsOwnPlace() {
        val inner = FakePinyin(mapOf("x" to wang))
        val session = object : Session by inner {
            override fun readingOf(index: Int) = listOf(WordReading(wang[index], 0, IntArray(0)))
            override fun lookedUp(index: Int) = wang[index]
        }
        val filter = StrokeFilter(session, { strokes }, pageSize = 3, singles = 2)
        filter.apply(Action.Key('x'))
        filter.apply(Action.Stroke('h'))
        assertEquals("王", filter.lookedUp(1))
        assertEquals("王", filter.readingOf(1)?.single()?.text)
        assertEquals(setOf(Offer.FORGET), filter.offers(1))
        filter.why(1)
        assertEquals(3, inner.whys.last())
        filter.apply(Action.Pin(1))
        assertEquals(Action.Pin(3), inner.actions.last())
        filter.apply(Action.Unpin(1))
        assertEquals(Action.Unpin(3), inner.actions.last())
        // blocked: gone from the session's, kept and ordered again, 样 and 杨 now after the first
        val s = filter.apply(Action.Block(1))
        assertEquals(Action.Block(3), inner.actions.last())
        assertEquals(listOf("王者", "样", "杨"), s.candidates)
    }

    @Test
    @Uat("PY-136")
    fun theOrderIsMadeAgainAtEachStrokeAndBackspaceAndAPageTurnGoesOnFromIt() {
        val (_, filter) = singles(wang, 2)
        // the second page: the second alone, then the words passed in their order
        assertEquals(listOf("样", "王国", "样子"), filter.apply(Action.NextPage).candidates)
        assertEquals(listOf("样子", "样", "杨"), filter.apply(Action.Stroke('s')).candidates)
        assertEquals(listOf("样子", "样", "杨", "杨树", "望"), filter.candidates(0, 9).map { it.text })
        assertEquals(listOf("王者", "王", "🙂"), filter.apply(Action.Backspace).candidates)
    }

    @Test
    fun noSinglesTheSessionsOrder() {
        val (_, filter) = singles(wang, 0)
        assertEquals(wang.take(3), filter.candidates(0, 3).map { it.text })
    }

    // [StrokeFilter] over a [PinyinSession] whose sentence models reorder its list while strokes are drawn.

    // the habits' words filled the first page, so the refiner's pick went past the page the filter compared; a pin does so here
    @Uat("PY-157")
    @Test
    fun aRefineThatReordersPastTheFirstPageLeavesATapTheCandidateTapped() {
        val data = sessionTestData()
        val learner = Learner(data)
        var weighed = false
        val refiner = SentenceRefiner { _, readings, _, _ -> if (weighed) readings.lastIndex else null }
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 1, prediction = false, user = learner.model, learnTo = learner.learnTo(), refiner = refiner)
        val drawn = "...\n你\thh\n拟\tss"
        val filter = StrokeFilter(session, { Strokes(CodeTable.load(ByteBuffer.wrap(Strokes.read(drawn.reader().buffered(), "stroke").build().toByteArray()))) { 0f } }, pageSize = 1)
        fun type(keys: String) = keys.map { filter.apply(Action.Key(it)) }.last()
        assertEquals("你在", type("nizai").candidates.single())
        filter.apply(Action.Pin(0))
        filter.apply(Action.Reset)
        type("nizai")
        assertEquals(listOf("你在", "你再", "拟再", "拟在", "你", "拟"), session.candidates(0, 10).map { it.text })
        filter.apply(Action.Stroke('h'))
        assertEquals(listOf("你在", "你", "你再"), filter.candidates(0, 10).map { it.text })
        weighed = true
        // the pin stays first, the refiner's pick after it: the page the session shows is the same
        assertEquals(listOf("你在"), filter.apply(Action.Refine).candidates)
        assertEquals(listOf("你在", "拟在", "你再", "拟再", "你", "拟"), session.candidates(0, 10).map { it.text })
        assertEquals(listOf("你在", "你", "你再"), filter.candidates(0, 10).map { it.text })
        assertEquals("你再", filter.apply(Action.Pick(2)).commit)
    }
}
