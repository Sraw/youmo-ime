/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.sqrt

class VoiceSegmentsTest {

    /** a step of the recognizer's: decoded once for each window accepted */
    private class Step(val text: String, val endpoint: Boolean = false)

    /** the recognizer as a script: each window accepted makes the next step ready */
    private class Script(steps: List<Step>) : VoiceRecognition<String> {
        private val steps = ArrayDeque(steps)
        private var ready = 0
        private var now = Step("")
        override fun accept(samples: FloatArray) {
            if (samples.isNotEmpty()) ready++
        }
        override fun inputFinished() = Unit
        override fun isReady() = ready > 0 && steps.isNotEmpty()
        override fun decode() {
            ready--
            now = steps.removeFirst()
        }
        override fun isEndpoint() = now.endpoint
        override fun reset() {
            now = Step("")
        }
        override fun result() = now.text
        override fun text(result: String) = result
        override fun firstTime(result: String): Float? = if (result.isEmpty()) null else 0.5f
    }

    private val lives = mutableListOf<String>()
    private val closes = mutableListOf<String>()

    private fun segments(vararg steps: Step) =
        VoiceSegments(Script(steps.toList()), 16000, choose = { it }, live = { lives += it }, closed = { closes += it })

    // a window at [db] dBFS, as the capture thread hands it: to the quiet, then to the recognizer
    private fun VoiceSegments<String>.hear(db: Double, voiced: Boolean, times: Int) = repeat(times) {
        val w = FloatArray(512) { sqrt(Math.pow(10.0, db / 10)).toFloat() }
        window(w, w.size, voiced)
        go { accept(w) }
    }

    private fun go(block: suspend () -> Unit) =
        block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })

    private fun chinese(text: String) = listOf(Step(text.take(1)), Step(text), Step(text, endpoint = true))

    @Test
    fun theRoomsSorryNeitherShowsNorIsTypedChineseAfterItIs() {
        val s = segments(Step("I'm"), Step("I'm sorry."), Step("I'm sorry.", endpoint = true), *chinese("你好").toTypedArray())
        // -60 dBFS, no window voiced: the gained room
        s.hear(-60.0, voiced = false, times = 3)
        assertEquals(listOf<String>(), lives)
        assertEquals(listOf<String>(), closes)
        s.hear(-60.0, voiced = false, times = 3)
        assertEquals(listOf("你", "你好"), lives)
        assertEquals(listOf("你好"), closes)
        assertEquals(1, s.kept)
        assertEquals(8000, s.speechStart)
    }

    @Test
    fun eachSegmentIsJudgedOnItsOwnWindows() {
        // said loudly, then the room alone: the room's segment is not lifted by the speech before it
        // loud enough to type a short word: 8 windows, 3 past the first with sound (VoiceQuiet.SKIP);
        // counted into the room's span (no new start at the endpoint), they would type its 「I'm sorry.」
        val loud = listOf(Step("你")) + List(6) { Step("你好") } + Step("你好", endpoint = true)
        val s = segments(*loud.toTypedArray(), Step("I'm"), Step("I'm sorry."), Step("I'm sorry.", endpoint = true))
        s.hear(-30.0, voiced = true, times = 8)
        s.hear(-60.0, voiced = false, times = 3)
        assertEquals(listOf("你好"), closes)
        assertEquals(1, s.kept)
    }

    @Test
    fun aLoudVoicedShortWordIsTyped() {
        // its 3rd loudest window read, the first 3 with sound being no level (VoiceQuiet.SKIP)
        val s = segments(Step("O"), *Array(6) { Step("OK") }, Step("OK", endpoint = true))
        s.hear(-30.0, voiced = true, times = 8)
        assertEquals(listOf("OK"), closes)
        assertEquals(1, s.kept)
    }

    @Test
    fun theLastSegmentClosesAtTheEnd() {
        val s = segments(Step("你"), Step("你好"))
        s.hear(-60.0, voiced = false, times = 1)
        go { s.finish(FloatArray(512)) }
        assertEquals(listOf("你", "你好"), lives)
        assertEquals(listOf("你好"), closes)
    }
}
