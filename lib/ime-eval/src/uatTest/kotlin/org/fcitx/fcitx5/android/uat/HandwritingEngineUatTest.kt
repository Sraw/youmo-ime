/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/09-handwriting.md, what the engine makes of what was written: the sentence read with the
 * app's model, space, picks, ⌫, punctuation, 联想 and learning. A character's readings are given
 * as the recognizer hands them (its own reading of strokes is its tests' and the device's HW-03).
 */
class HandwritingEngineUatTest {

    private val da = arrayOf("大", "太", "犬")
    private val ren = arrayOf("人", "入", "八")
    private val wo = arrayOf("我", "找", "伐")

    private fun replay() = EngineReplay(im = Engines.HANDWRITING)

    // chttrans re-sends the composing text (chttrans.cpp:214-215): what is written and not yet in
    // shows traditional at the toggle, with nothing more written; it goes in as shown (F4183)
    @Test
    @Uat("HW-26")
    fun aCharacterNotYetInIsShownTraditionalAtTheToggle() = with(HostReplay(im = Engines.HANDWRITING)) {
        write("国", "图", "固")
        assertEquals("国", clientPreedit)
        toggle("chttrans")
        assertEquals("國", clientPreedit)
        space()
        assertEquals("國", text)
    }

    @Test
    @Uat("HW-04")
    fun aCharacterWrittenAfterCorrectsTheOneBefore() = with(replay()) {
        // left side between 讠 and 氵: read 淡 first, 谈 second
        write("淡", "谈", "炎")
        expectPreedit("淡")
        assertEquals("谈", candidates[1])
        write("话", "活", "诂")
        expectPreedit("谈话")
    }

    @Test
    @Uat("HW-05")
    fun aCandidatePickedPutsInTheSentenceEndingWithIt() = with(replay()) {
        write(*da)
        write(*ren)
        val c = candidates[1]
        pickAt(1)
        assertTrue("$text, $c", text.length == 2 && text.endsWith(c))
        expectPreedit("")
    }

    @Test
    @Uat("HW-07")
    fun backspaceTakesTheUnwrittenCharacterThenTheText() = with(replay()) {
        focus("好")
        write(*ren)
        backspace()
        expectText("好")
        expectPreedit("")
        backspace()
        expectText("")
    }

    @Test
    @Uat("HW-08")
    fun punctuationPutsTheSentenceInFirst() = with(replay()) {
        write(*ren)
        // the pad's 。 is a commit over the engine (commitOverEngine): what is written first, as its policy says.
        // The policy is applied by hand here; the service's wiring of it is CommonKeyActionListenerTest's
        // (app tests), the policy's own cases PendingInputPolicyTest's: this holds the engine's half
        assertEquals(PendingInputPolicy.Resolution.SelectFirstCandidate, PendingInputPolicy.resolve("zh_CN", hasPreedit = preedit.isNotEmpty()))
        pickAt(0)
        commit("。")
        expectText("人。")
    }

    @Test
    @Uat("HW-10")
    fun aSpaceThenPredictions() = with(replay()) {
        write(*wo)
        space()
        expectText("我")
        val next = page
        assertTrue("predictions $next", next.isNotEmpty())
        pickAt(0)
        expectText("我" + next.first())
    }

    @Test
    @Uat("HW-11")
    fun returnPutsInWithoutPredictions() = with(replay()) {
        write(*wo)
        enter()
        assertTrue(text, text.startsWith("我"))
        assertTrue("predictions $page", page.isEmpty())
    }

    @Test
    @Uat("HW-14")
    fun aCharacterPickedRanksUpTwiceFirst() = with(replay()) {
        write(*ren)
        assertEquals(listOf("人", "入", "八"), candidates.take(3))
        pick("入")
        focus("")
        write(*ren)
        assertEquals(listOf("人", "入"), candidates.take(2))
        pick("入")
        focus("")
        write(*ren)
        assertEquals("入", candidates.first())
    }

    @Test
    @Uat("HW-23")
    fun aCharacterDeletedRightAwayIsNotUsed() = with(replay()) {
        write(*ren)
        val order = candidates.take(3)
        pickAt(2)
        backspace()
        expectText("")
        // written again right away, as the device's step: no new focus
        write(*ren)
        assertEquals(order, candidates.take(3))
    }
}
