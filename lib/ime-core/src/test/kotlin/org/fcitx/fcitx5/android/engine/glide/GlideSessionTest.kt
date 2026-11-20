/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.table.SharedWords
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.dictionary
import org.fcitx.fcitx5.android.engine.table.learning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class GlideSessionTest {

    // niao likelier than ni'hao: a clean path over n-i-h-a-o reads 鸟 first
    private val data = pinyinDataOf(
        Triple("鸟", "niao", -2f),
        Triple("尿", "niao", -3f),
        Triple("你好", "ni hao", -3f),
        Triple("好", "hao", -2.5f),
        Triple("你", "ni", -2f),
    )

    private val decoder = PinyinGlide(data, GlidePath.Weights(corner = 0f))

    private val boundary = Action.Key(PinyinGlide.SEPARATOR)

    // the text before the cursor as the app has it: what was committed, deleted, or told as a field's
    private var text = ""
    private fun written(inner: Session) = object : Session by inner {
        override fun apply(action: Action) = inner.apply(action).also {
            text = when (action) {
                Action.Reset -> ""
                is Action.Context -> action.before
                else -> text.dropLast(it.deleteBefore)
            } + it.commit
        }
    }

    // what the session follows, as the next input's context: the app's text, as far as it was told
    private var followed = ""
    private fun following(inner: Session) = object : Session by inner {
        override fun apply(action: Action) = inner.apply(action).also {
            followed = when (action) {
                Action.Reset -> ""
                is Action.Context -> action.before
                else -> followed + it.commit
            }
        }
    }

    private var session = GlideSession(written(PinyinSession(data, PinyinSegmenter(), prediction = false)), boundary) { g, n -> decoder.decode(g.path, g.layout, n) }

    private fun path(keys: String) = keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()

    private fun glide(keys: String) = session.apply(session.read(Glide(path(keys), GlideLayout.QWERTY)))

    @Test
    fun theLikeliestTypedTheOthersWordsAfterItsFirst() {
        val s = glide("nihao")
        assertEquals("鸟", s.candidates[0])
        assertEquals("你好", s.candidates[1])
        assertEquals("尿", s.candidates[2])
        assertEquals(s.candidates, session.candidates(0, s.candidates.size).map { it.text })
        assertEquals(listOf("你好", "尿"), session.candidates(1, 2).map { it.text })
        assertEquals("尿", session.candidates(2, 5).first().text)
        val all = session.candidates(0, 100).map { it.text }
        assertEquals(all.size, s.total)
        assertEquals(1, all.count { it == "你好" })
    }

    @Test
    fun asManyReadingsAsItIsMadeFor() {
        session = GlideSession(PinyinSession(data, PinyinSegmenter(), prediction = false), boundary, readings = 1) { g, n -> decoder.decode(g.path, g.layout, n) }
        val s = glide("nihao")
        assertEquals("鸟", s.candidates[0])
        assertEquals("尿", s.candidates[1])
    }

    @Test
    fun anotherReadingsWordPickedIsTypedAndPicked() {
        glide("nihao")
        assertEquals("你好", session.apply(Action.Pick(1)).commit)
        glide("nihao")
        assertEquals("你好", session.apply(Action.Select(1)).commit)
        glide("nihao")
        assertEquals("尿", session.apply(Action.Pick(2)).commit)
    }

    @Test
    fun aPickOfAnotherReadingKeepsWhatWasCommittedBefore() {
        glide("hao")
        session.apply(Action.Pick(0))
        glide("nihao")
        val s = session.apply(Action.Pick(1))
        assertEquals("你好", s.commit)
        assertEquals("好你好", text)
    }

    @Test
    fun aCandidateOfPartOfTheInputPickedLeavesTheOthersBehind() {
        val at = glide("nihao").let { session.candidates(0, 20).indexOfFirst { it.text == "你" } }
        assertTrue(at > 2)
        val s = session.apply(Action.Pick(at))
        assertEquals(s.preedit.isNotEmpty(), true)
        assertTrue("鸟" !in s.candidates)
        assertEquals(s.candidates, session.candidates(0, s.candidates.size).map { it.text })
    }

    @Test
    fun noneBlockedOffered() {
        session = GlideSession(PinyinSession(data, PinyinSegmenter(), prediction = false), boundary, blocked = { it == "你好" }) { g, n -> decoder.decode(g.path, g.layout, n) }
        assertTrue("你好" !in glide("nihao").candidates)
    }

    @Test
    fun pagesPastTheFirstAreTheSessionsOwnAfterTheOthers() {
        session = GlideSession(PinyinSession(data, PinyinSegmenter(), prediction = false, pageSize = 2), boundary) { g, n -> decoder.decode(g.path, g.layout, n) }
        val first = glide("nihao")
        assertEquals("你好", first.candidates[1])
        val next = session.apply(Action.NextPage)
        assertEquals(first.candidates.size, next.first)
        assertEquals(next.candidates, session.candidates(next.first, next.candidates.size).map { it.text })
        assertEquals(next.candidates[0], session.apply(Action.Select(0)).commit)
    }

    @Test
    fun nothingToForgetOfAnotherReadingsWord() {
        val s = glide("nihao")
        assertTrue(session.offers(1).isEmpty())
        assertEquals(s.candidates, session.apply(Action.Forget(1)).candidates)
        assertEquals(session.offers(1), emptySet<Offer>())
    }

    @Test
    fun aGlideAfterInputGoesOnAfterABoundaryWithNoOthers() {
        glide("ni")
        val s = glide("hao")
        assertEquals("你好", s.candidates[0])
        assertTrue("鸟" !in s.candidates)
    }

    @Test
    fun typingLeavesTheOthersBehind() {
        glide("nihao")
        val s = session.apply(Action.Backspace)
        assertTrue("你好" !in s.candidates)
        assertEquals(s.candidates, session.candidates(0, s.candidates.size).map { it.text })
    }

    @Test
    fun noReadingIsTheSessionsGlide() {
        assertEquals("", session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY))).preedit)
    }

    // a table's: hints and selection labels, a 空码, a word it has not, an offer past the others
    private val table = dictionary("wqiy 你", "vbg 好", "vbgf 妤", "vbgg 姓")
    private val shared = object : SharedWords by SharedWords.NONE {
        override fun blockable(text: String) = true
    }
    private val tableSession = following(TableSession(table, TableOptions(autoSelect = false, selectionKeys = "123"), shared = shared, language = learning()))

    // one session over the table for every glide of a test, as the app has it
    private var tableReadings = emptyList<GlideReading>()
    private val tableGlide by lazy { GlideSession(tableSession, Action.Pick(0)) { _, _ -> tableReadings } }

    private fun tableGlided(vararg readings: GlideReading): Snapshot {
        tableReadings = readings.toList()
        session = tableGlide
        return session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY)))
    }

    @Test
    fun aTablesHintsAndLabelsStayOnTheirCandidates() {
        val s = tableGlided(GlideReading("vbg", "好", 0f), GlideReading("wqiy", "你", -1f))
        assertEquals(listOf("好", "你", "妤", "姓"), s.candidates)
        assertEquals(s.candidates.size, s.hints.size)
        assertEquals("", s.hints[1])
        assertEquals(listOf("f", "g"), s.hints.drop(2))
        // the key for the session's second, 2, on it, none on the other
        assertEquals("1 23", s.labels)
    }

    @Test
    fun anOfferPastTheOthersIsOfTheSessionsWord() {
        tableGlided(GlideReading("vbg", "好", 0f), GlideReading("wqiy", "你", -1f))
        session.apply(Action.Block(2))
        // 妤 blocked: not the other reading's 你 nor 姓; the code still typed goes first
        val again = tableGlided(GlideReading("vbg", "好", 0f), GlideReading("wqiy", "你", -1f))
        assertEquals(listOf("好", "你", "姓"), again.candidates)
    }

    @Test
    fun aWordTheSessionHasNotIsCommittedAndFollowed() {
        tableGlided(GlideReading("vbg", "好", 0f))
        session.apply(Action.Pick(0))
        tableGlided(GlideReading("wqiy", "你", 0f), GlideReading("vbg", "奻", -1f))
        val s = session.apply(Action.Pick(1))
        assertEquals("奻", s.commit)
        assertEquals("", s.preedit)
        // what follows goes on after the word, and after what was committed before it
        assertEquals("好奻", followed)
    }

    @Test
    fun anEmojiInPlaceOfAWordPastTheBmpTakesItsCodePointsFromWhatIsFollowed() {
        // as TableSession swaps an emoji for a word it committed: the word's code points deleted, 𠀾 two UTF-16 units of one
        var swap = false
        val inner = object : Session by tableSession {
            override fun apply(action: Action) = tableSession.apply(action).let {
                if (swap && action == Action.Refine) it.copy(commit = "📢", deleteBefore = 2) else it
            }
        }
        tableReadings = listOf(GlideReading("wqiy", "你", 0f), GlideReading("vbg", "𠀾𠀾", -1f))
        session = GlideSession(inner, Action.Pick(0)) { _, _ -> tableReadings }
        session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY)))
        assertEquals("𠀾𠀾", session.apply(Action.Pick(1)).commit)
        swap = true
        session.apply(Action.Refine)
        swap = false
        tableReadings = listOf(GlideReading("wqiy", "你", 0f), GlideReading("vbg", "奻", -1f))
        session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY)))
        session.apply(Action.Pick(1))
        assertEquals("📢奻", followed)
    }

    @Test
    fun aGlideAfterACodeThatReadsNothingTakesItBack() {
        session = GlideSession(tableSession, Action.Pick(0)) { _, _ -> listOf(GlideReading("vbg", "好", 0f)) }
        session.apply(Action.Key('v'))
        assertTrue(session.apply(Action.Key('x')).candidates.isEmpty())
        val s = session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY)))
        assertEquals("", s.commit)
        assertEquals("vbg", s.preedit)
        assertEquals("好", s.candidates.first())
    }

    @Test
    fun aGlideAfterALookupThatMatchesNothingTakesItBack() {
        val pinyin = PinyinSession(data, PinyinSegmenter(), prediction = false)
        val table = CodeTable.load(ByteBuffer.wrap(Strokes.read("...\n你\tpsnpzsn\n".reader().buffered(), "stroke").build().toByteArray()))
        val looked = StrokeLookup(pinyin, { Strokes(table) { 0f } }, follow = pinyin::follow)
        session = GlideSession(looked, boundary) { g, n -> decoder.decode(g.path, g.layout, n) }
        session.apply(Action.Key(StrokeLookup.KEY))
        assertTrue(session.apply(Action.Key('h')).candidates.isEmpty())
        val s = glide("hao")
        assertEquals("", s.commit)
        assertEquals("好", s.candidates.first())
    }

    @Test
    fun whyAndTheListReadAgainAreTheSessionsShiftedPastTheOthers() {
        val inner = object : Session by tableSession {
            override fun why(index: Int) = Why(null, uses = index)
        }
        tableReadings = listOf(GlideReading("vbg", "好", 0f), GlideReading("wqiy", "你", -1f))
        session = GlideSession(inner, Action.Pick(0)) { _, _ -> tableReadings }
        val s = session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY)))
        assertEquals(listOf("好", "你", "妤", "姓"), s.candidates)
        assertEquals(listOf(0, null, 1, 2), s.candidates.indices.map { session.why(it)?.uses })
        assertEquals(s.candidates, session.reread()!!.candidates)
    }

    // the others' places read nothing; the session's own its reading, shifted past them
    @Test
    @Uat("PY-50")
    fun readingsAndLookupsAreTheSessionsShiftedPastTheOthers() {
        val inner = object : Session by tableSession {
            override fun readingOf(index: Int) = listOf(WordReading("r$index", 0, IntArray(0)))
            override fun lookedUp(index: Int) = "l$index"
        }
        tableReadings = listOf(GlideReading("vbg", "好", 0f), GlideReading("wqiy", "你", -1f))
        session = GlideSession(inner, Action.Pick(0)) { _, _ -> tableReadings }
        val s = session.apply(session.read(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY)))
        assertEquals(listOf("好", "你", "妤", "姓"), s.candidates)
        assertEquals(listOf("r0", null, "r1", "r2"), s.candidates.indices.map { session.readingOf(it)?.single()?.text })
        assertEquals(listOf("l0", null, "l1", "l2"), s.candidates.indices.map { session.lookedUp(it) })
    }
}
