/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparatorTest {
    private val seq = Sequence("s1", "engine-pinyin", "text", listOf(Step("type", "nihao"), Step("space"), Step("type", "ta")))

    private fun state(text: String, cands: List<String>, ic: List<String>? = null, preedit: String = "") =
        State(text, text.length to text.length, preedit, cands, ic?.map { JsonArray(listOf(JsonPrimitive(it))) })

    private val device = Output(
        "s1",
        listOf(
            state("", listOf("你好", "拟好", "你"), listOf("setComposingRegion")),
            state("你好", listOf("啊", "呀"), listOf("commitText")),
            state("你好", listOf("他", "她", "它"), listOf()),
        ),
    )

    private fun jvm(vararg cands: List<String>) = Output("s1", device.states.mapIndexed { i, s -> s.copy(cands = cands[i], ic = null) })

    // a score difference by the pair, as the sentence model would give it
    private val near = Comparator(jvm = true, epsilon = 0.01f) { _, a, b -> if (a + b in setOf("她他", "塔它")) 0.001f else 1f }

    @Test
    fun theSameRunsAreTheSame() {
        val r = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(device))
        assertTrue(r.passed)
        assertEquals(1, r.same)
    }

    @Test
    fun deviceAgainstDeviceComparesTheCalls() {
        val other = device.copy(states = device.states.mapIndexed { i, s -> if (i == 1) s.copy(ic = listOf(JsonArray(listOf(JsonPrimitive("setComposingText"))))) else s })
        val r = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(other))
        assertFalse(r.passed)
        assertEquals(1, r.differences.single().index)
        assertTrue(r.differences.single().what.startsWith("calls"))
    }

    @Test
    fun aBusyStepsDifferenceIsTimingNotAHostDifference() {
        // the recorder's idle wait ran out on step 1: what it read may be the step half done (F4177)
        val other = device.copy(states = device.states.mapIndexed { i, s -> if (i == 1) s.copy(text = "", busy = true) else s })
        assertTrue(Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(other)).passed)
        val notBusy = other.copy(states = other.states.map { it.copy(busy = false) })
        assertFalse(Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(notBusy)).passed)
    }

    @Test
    fun aCallLateAfterABusyStepCountsWithTheNextStep() {
        // F4295: step 1 read busy, its commitText landed after the read: in step 2's calls on this device
        val late = device.copy(
            states = device.states.mapIndexed { i, s ->
                when (i) {
                    1 -> s.copy(ic = listOf(), busy = true)
                    2 -> s.copy(ic = device.states[1].ic)
                    else -> s
                }
            },
        )
        assertTrue(Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(late)).passed)
        // a call neither step made is still a difference
        val wrong = late.copy(states = late.states.mapIndexed { i, s -> if (i == 2) s.copy(ic = listOf(JsonArray(listOf(JsonPrimitive("setComposingText"))))) else s })
        assertEquals(2, Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(wrong)).differences.single().index)
    }

    @Test
    fun aBusyLastStepsDifferenceIsToldAsTiming() {
        // F4295: no later state shows what it did, so it is not dropped; F4307: device timing is an
        // allowed difference (DECISIONS.md 2026-10-25), so it is listed apart and the run passes
        val other = device.copy(states = device.states.mapIndexed { i, s -> if (i == 2) s.copy(text = "", busy = true) else s })
        val r = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(other))
        assertTrue(r.passed)
        assertTrue(r.differences.isEmpty())
        assertEquals(2, r.timing.single().index)
        val report = StringBuilder().also { Comparator.report(r, it) }.toString()
        assertTrue(report, report.contains("TIMING ${seq.id} step 2"))
    }

    @Test
    fun aBusyLastStepFailsWhenNothingElseTellsIt() {
        // timing needs a busy last step (F4307) in a sequence both ran to its end, without an error, with
        // an idle step compared before it (F4322)
        fun busyFrom(from: Int, states: List<State> = device.states) =
            device.copy(states = states.mapIndexed { i, s -> if (i >= from) s.copy(text = "", busy = true) else s })
        // failing at the step the difference is at, as a difference (not a stop or a missing one)
        fun fails(other: Output, at: Int = 2) = {
            val r = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(other))
            assertFalse(r.passed)
            assertTrue(r.timing.isEmpty())
            assertEquals(at, r.differences.single().index)
        }
        val lastBusy = busyFrom(2)
        assertRows(
            "the last step not read busy: its difference is the host's" to fails(lastBusy.copy(states = lastBusy.states.map { it.copy(busy = false) })),
            "every step read busy: nothing was compared" to fails(busyFrom(0)),
            "one output ended in an error: its stop is no timing" to fails(lastBusy.copy(error = "crash")),
            "one output stopped short at a busy step: the other's later states tell it" to fails(busyFrom(1, device.states.take(2)), at = 1),
        )
    }

    @Test
    fun deviceAgainstDeviceAllowsNoSwap() {
        val other = device.copy(states = device.states.mapIndexed { i, s -> if (i == 2) s.copy(cands = listOf("她", "他", "它")) else s })
        val r = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(other))
        assertEquals(2, r.differences.single().index)
        assertTrue(r.swapped.isEmpty())
    }

    @Test
    fun theJvmsPageIsComparedAsFarAsTheBarShows() {
        val r = near.compare(listOf(seq), listOf(device), listOf(jvm(listOf("你好", "拟好", "你", "泥", "尼"), listOf("啊", "呀", "吗"), listOf("他", "她", "它", "塔"))))
        assertTrue(r.passed)
        assertEquals(1, r.same)
    }

    @Test
    fun aSwapOfNeighboursWithinEpsilonIsListedApart() {
        val r = near.compare(listOf(seq), listOf(device), listOf(jvm(listOf("你好", "拟好", "你"), listOf("啊", "呀"), listOf("她", "他", "它"))))
        assertTrue(r.passed)
        assertEquals(0, r.same)
        val swap = r.swapped.single()
        assertEquals(listOf("她", "他"), listOf(swap.a, swap.b))
        assertEquals("你好", swap.context)
    }

    @Test
    fun theLastOnTheBarMaySwapWithTheFirstPastIt() {
        val r = near.compare(listOf(seq), listOf(device), listOf(jvm(listOf("你好", "拟好", "你"), listOf("啊", "呀"), listOf("他", "她", "塔", "它"))))
        assertEquals("「塔」「它」", r.swapped.single().let { "「${it.a}」「${it.b}」" })
    }

    @Test
    fun aSwapPastEpsilonIsADifference() {
        val r = near.compare(listOf(seq), listOf(device), listOf(jvm(listOf("拟好", "你好", "你"), listOf("啊", "呀"), listOf("他", "她", "它"))))
        assertEquals(0, r.differences.single().index)
        assertTrue(r.differences.single().what, "≥ ε" in r.differences.single().what)
    }

    @Test
    fun aSwapNotOfNeighboursIsADifference() {
        val r = near.compare(listOf(seq), listOf(device), listOf(jvm(listOf("你好", "拟好", "你"), listOf("啊", "呀"), listOf("它", "她", "他"))))
        assertEquals(2, r.differences.single().index)
    }

    @Test
    fun aSwapLeavesTheRestUncompared() {
        val seq4 = seq.copy(steps = seq.steps + Step("space"))
        val dev = device.copy(states = device.states + state("你好他", listOf()))
        val other = jvm(listOf("你好", "拟好", "你"), listOf("啊", "呀"), listOf("她", "他", "它")).let { it.copy(states = it.states + state("你好她", listOf())) }
        val r = near.compare(listOf(seq4), listOf(dev), listOf(other))
        assertTrue(r.passed)
        assertEquals(1, r.swapped.size)
    }

    @Test
    fun theTextAndTheCursorAreComparedExactly() {
        val other = device.copy(states = device.states.mapIndexed { i, s -> if (i == 1) s.copy(sel = 1 to 1) else s })
        assertTrue(near.compare(listOf(seq), listOf(device), listOf(other)).differences.single().what.startsWith("selection"))
    }

    @Test
    fun aStoppedRunAndAMissingOneFail() {
        val stopped = device.copy(states = device.states.take(1), error = "Blocked: no key")
        val r = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf(stopped))
        assertEquals(1, r.errors.single().index)
        assertFalse(r.passed)
        val missing = Comparator(jvm = false).compare(listOf(seq), listOf(device), listOf())
        assertEquals(listOf("s1"), missing.missing)
    }

    @Test
    fun theNameOverlayIsNotComparedOnTheJvm() {
        val seqIme = seq.copy(steps = listOf(Step("ime", "keyboard-us"), Step("space"), Step("type", "ta")))
        val dev = device.copy(states = device.states.mapIndexed { i, s -> if (i == 0) s.copy(preedit = "En") else s })
        val jvmOut = jvm(listOf("你好", "拟好", "你"), listOf("啊", "呀"), listOf("他", "她", "它"))
        assertTrue(near.compare(listOf(seqIme), listOf(dev), listOf(jvmOut)).passed)
        // device against device it is: both builds show the same overlay
        assertFalse(Comparator(jvm = false).compare(listOf(seqIme), listOf(dev), listOf(device)).passed)
    }

    @Test
    fun onlyTheReferencesSequencesAreCompared() {
        val r = Comparator(jvm = false).compare(listOf(seq, seq.copy(id = "s2")), listOf(device), listOf(device))
        assertTrue(r.passed)
        assertEquals(1, r.same)
    }
}
