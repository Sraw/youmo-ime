/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinGlideSessionTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // niao likelier than ni'hao: a clean path over n-i-h-a-o reads 鸟 first
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("鸟", -2f, 0f)
                .unigram("尿", -3f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你", -2f, 0f)
                .entry("鸟", syl("niao"))
                .entry("尿", syl("niao"))
                .entry("你好", syl("ni", "hao"))
                .entry("好", syl("hao"))
                .entry("你", syl("ni"))
                .build().toByteArray()
        )
    )

    private val decoder = PinyinGlide(data, GlidePath.Weights(corner = 0f))

    // what the session was told, to see that a pick of another reading takes the input back, not the context
    private val told = ArrayList<Action>()
    private fun recorded(inner: Session) = object : Session by inner {
        override fun apply(action: Action) = inner.apply(action).also { told += action }
    }

    private var session = PinyinGlideSession(recorded(PinyinSession(data, PinyinSegmenter(), prediction = false))) { decoder }

    private fun path(keys: String) = keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()

    private fun glide(keys: String) = session.offer(Glide(path(keys), GlideLayout.QWERTY)).let { session.apply(Action.Glide) }

    @Test
    fun theLikeliestTypedTheOthersWordsAfterItsFirst() {
        val s = glide("nihao")
        assertEquals("鸟", s.candidates[0])
        assertEquals("你好", s.candidates[1])
        assertEquals("尿", s.candidates[2])
        assertEquals(s.candidates, session.candidates(0, s.candidates.size).map { it.text })
        assertEquals(listOf("你好", "尿"), session.candidates(1, 2).map { it.text })
        assertEquals("尿", session.candidates(2, 5).first().text)
        // all, the other among them once
        val all = session.candidates(0, 100).map { it.text }
        assertEquals(all.size, s.total)
        assertEquals(1, all.count { it == "你好" })
    }

    @Test
    fun anotherReadingsWordPickedIsTypedAndPicked() {
        glide("nihao")
        assertEquals("你好", session.apply(Action.Pick(1)).commit)
        glide("nihao")
        assertEquals("你好", session.apply(Action.Select(1)).commit)
        glide("nihao")
        // past the others: the session's own
        assertEquals("尿", session.apply(Action.Pick(2)).commit)
    }

    @Test
    fun aPickOfAnotherReadingKeepsWhatWasCommittedBefore() {
        glide("nihao")
        told.clear()
        session.apply(Action.Pick(1))
        assertTrue(Action.Reset !in told)
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
        session = PinyinGlideSession(PinyinSession(data, PinyinSegmenter(), prediction = false), blocked = { it == "你好" }) { decoder }
        assertTrue("你好" !in glide("nihao").candidates)
    }

    @Test
    fun pagesPastTheFirstAreTheSessionsOwnAfterTheOthers() {
        session = PinyinGlideSession(PinyinSession(data, PinyinSegmenter(), prediction = false, pageSize = 2)) { decoder }
        val first = glide("nihao")
        assertEquals("你好", first.candidates[1])
        val next = session.apply(Action.NextPage)
        // after the first page, the other among it
        assertEquals(first.candidates.size, next.first)
        assertEquals(next.candidates, session.candidates(next.first, next.candidates.size).map { it.text })
        // a digit picks of the page shown
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
        session.offer(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY))
        assertEquals("", session.apply(Action.Glide).preedit)
    }
}
