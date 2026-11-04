/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
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

    /**
     * Pinyin as far as the filter sees it: the input typed, its candidates by input, a candidate picked
     * committed whole, or its first character only where [partial] has it (我 of women leaves men); one
     * forgotten or blocked is gone from the input's. A key that ends the input (not a letter, or `;`, read
     * as a 双拼 scheme's key but never where it stands here) leaves as PinyinSession does: its own first
     * candidate committed, what that did not read as typed, the key not handled. Every action it is
     * given is recorded, and every index it is asked why of. A pick and a leave each delete [deletes]
     * before the cursor (English's space after a word does).
     */
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
        // the hints are the session's
        assertEquals(listOf("hint-养", "hint-羊", "hint-🐑"), s.hints)
        assertTrue(filter.filtering)
    }

    @Test
    fun theNextStrokeIsTheSameCharactersNextEitherOrderOfOneWrittenTwoWays() {
        type("yang")
        filter.apply(Action.Stroke('n'))
        assertEquals(listOf("养", "羊", "🐑"), filter.apply(Action.Stroke('p')).candidates)
        // 望 is hszph too: h then s finds it beside 样 and 杨
        filter.apply(Action.Backspace)
        filter.apply(Action.Backspace)
        filter.apply(Action.Stroke('h'))
        assertEquals(listOf("样", "杨", "望"), filter.apply(Action.Stroke('s')).candidates)
        assertEquals("yang 一丨", filter.apply(Action.Refine).preedit)
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
        // 羊 gone: kept again, 🐑 now after 养
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
        assertEquals(looked, pinyin.asked.size)
        // a reread may change the order anywhere: looked through again, the same as before
        assertEquals("yang 乛", filter.reread()?.preedit)
        val reread = pinyin.asked.size
        assertTrue(reread > looked)
        // read again into another order: kept again
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
        // a pick is the session's: handled
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
        // nothing to pick: space picks nothing, the strokes stay
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
        // nor with no strokes to read
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
        // taken, so the host does not type the mark itself
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

    // the character card and 候选词注音 ask of a place in the list shown: a kept candidate's is
    // the session's of its own place, not of the place it now has
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
}
