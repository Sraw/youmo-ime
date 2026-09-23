/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.os.Build
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Holds [FakeEditor] to the behaviour of the editor it stands in for.
 *
 * Every `EditingSession` test trusts the fake to behave like a real editor; if it drifts, those
 * tests keep passing while asserting things no editor does. Here each scenario runs twice --
 * once on the fake, once on AOSP's real `BaseInputConnection` over an `Editable` (what a plain
 * `EditText` hands an input method), reached through the production [InputConnectionEditor] --
 * and the resulting text, selection, composing region and return values must be identical.
 *
 * To model a new editor behaviour, add a scenario here first; the real side says what the fake
 * has to do.
 */
@RunWith(RobolectricTestRunner::class)
open class InputEditorContractTest {

    private data class Outcome(
        val text: String,
        val selection: Pair<Int, Int>,
        val composing: Pair<Int, Int>,
        val returned: List<String>,
    )

    private interface Subject {
        val editor: InputEditor

        /** Where a random sequence records what the editor returned along the way. */
        var returned: MutableList<String>
        fun outcome(returned: List<String>): Outcome
    }

    private class Fake(text: String, selStart: Int, selEnd: Int) : Subject {
        private val fake = FakeEditor(text, selStart, reselectsZeroAfterInsert = Build.VERSION.SDK_INT > 23)
            .apply { reportSelection(selStart, selEnd) }
        override val editor: InputEditor = fake
        override var returned = mutableListOf<String>()

        override fun outcome(returned: List<String>) = Outcome(
            fake.text,
            fake.selectionStart to fake.selectionEnd,
            fake.composingStart to fake.composingEnd,
            returned,
        )
    }

    private class Real(text: String, selStart: Int, selEnd: Int) : Subject {
        private val editable = SpannableStringBuilder(text).also {
            Selection.setSelection(it, selStart, selEnd)
        }
        private val connection =
            object : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
                override fun getEditable() = this@Real.editable
            }
        override val editor: InputEditor = InputConnectionEditor { connection }
        override var returned = mutableListOf<String>()

        override fun outcome(returned: List<String>) = Outcome(
            editable.toString(),
            Selection.getSelectionStart(editable) to Selection.getSelectionEnd(editable),
            BaseInputConnection.getComposingSpanStart(editable) to
                BaseInputConnection.getComposingSpanEnd(editable),
            returned,
        )
    }

    private class Scenario(
        val name: String,
        val text: String,
        val selStart: Int,
        val selEnd: Int = selStart,
        val ops: Subject.(returned: MutableList<String>) -> Unit,
    )

    private val wave = String(Character.toChars(0x1F44B)) // one code point, two UTF-16 units
    private val high = wave[0]
    private val low = wave[1]

    private fun run(scenarios: List<Scenario>) {
        val mismatches = scenarios.mapNotNull { sc ->
            val outcomes = listOf(Fake(sc.text, sc.selStart, sc.selEnd), Real(sc.text, sc.selStart, sc.selEnd))
                .map { subject -> mutableListOf<String>().also { subject.(sc.ops)(it) }.let(subject::outcome) }
            val (fake, real) = outcomes
            if (fake == real) null else "${sc.name}\n    fake: $fake\n    real: $real"
        }
        assertTrue(
            "FakeEditor disagrees with BaseInputConnection in ${mismatches.size} scenario(s):\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    @Test
    fun commitText() = run(
        listOf(
            Scenario("at a cursor", "abc", 1) { editor.commitText("X", 1) },
            Scenario("over a selection", "abcdef", 1, 4) { editor.commitText("X", 1) },
            Scenario("over a reversed selection", "abcdef", 4, 1) { editor.commitText("X", 1) },
            Scenario("cursor 0: before the new text", "abc", 1) { editor.commitText("XY", 0) },
            Scenario("cursor 2: one past the new text", "abcd", 1) { editor.commitText("XY", 2) },
            Scenario("cursor -1: one before the new text", "abcd", 2) { editor.commitText("XY", -1) },
            Scenario("cursor far past the end", "ab", 2) { editor.commitText("XYZ", 10) },
            Scenario("cursor far before the start", "abcd", 2) { editor.commitText("X", -10) },
            Scenario("replaces the composing region, not the selection", "abcdef", 6) {
                editor.setComposingRegion(1, 3)
                editor.commitText("X", 1)
            },
            Scenario("empty text deletes a selection", "abcdef", 1, 4) { editor.commitText("", 1) },
            Scenario("empty text with cursor 0", "abcdef", 1, 4) { editor.commitText("", 0) },
            Scenario("cursor 0 over a selection", "abcdef", 1, 4) { editor.commitText("XY", 0) },
            Scenario("cursor -1 over a composition", "abcdef", 6) {
                editor.setComposingRegion(2, 4)
                editor.commitText("XY", -1)
            },
            Scenario("inside a batch, then a selection", "abc", 3) {
                editor.batchEdit {
                    editor.commitText("XY", 1)
                    editor.setSelection(1, 1)
                }
            },
        )
    )

    @Test
    fun composingText() = run(
        listOf(
            Scenario("grows a composition, then commits it", "ab", 1) {
                editor.setComposingText("n", 1)
                editor.setComposingText("ni", 1)
                editor.commitText("你", 1)
            },
            Scenario("composes over a selection", "abcdef", 1, 4) { editor.setComposingText("ni", 1) },
            Scenario("empty composing text removes the composition", "ab", 2) {
                editor.setComposingText("ni", 1)
                editor.setComposingText("", 1)
            },
            Scenario("finishing keeps the text", "ab", 2) {
                editor.setComposingText("ni", 1)
                editor.finishComposingText()
            },
        )
    )

    @Test
    fun setSelection() = run(
        listOf(
            Scenario("in range", "abcdef", 0) { editor.setSelection(2, 4) },
            Scenario("at the very end", "abcdef", 0) { editor.setSelection(6, 6) },
            Scenario("past the end is ignored", "abcdef", 1) { editor.setSelection(3, 9) },
            Scenario("negative is ignored", "abcdef", 1) { editor.setSelection(-1, 2) },
            Scenario("reversed is kept as given", "abcdef", 0) { editor.setSelection(4, 1) },
        )
    )

    @Test
    fun setComposingRegion() = run(
        listOf(
            Scenario("in range", "abcdef", 6) { editor.setComposingRegion(1, 3) },
            Scenario("reversed", "abcdef", 6) { editor.setComposingRegion(3, 1) },
            Scenario("out of range is clamped", "abcdef", 6) { editor.setComposingRegion(-2, 10) },
            Scenario("empty clears it", "abcdef", 6) {
                editor.setComposingRegion(1, 3)
                editor.setComposingRegion(2, 2)
            },
            Scenario("finishing clears it", "abcdef", 6) {
                editor.setComposingRegion(1, 3)
                editor.finishComposingText()
            },
        )
    )

    @Test
    fun deleteSurroundingTextInUnits() = run(
        listOf(
            Scenario("before", "abcdef", 4) { editor.deleteSurroundingText(2, 0, false) },
            Scenario("after", "abcdef", 2) { editor.deleteSurroundingText(0, 2, false) },
            Scenario("around a selection, which survives", "abcdef", 2, 4) {
                editor.deleteSurroundingText(1, 1, false)
            },
            Scenario("around a reversed selection", "abcdef", 4, 2) {
                editor.deleteSurroundingText(1, 1, false)
            },
            Scenario("past both ends", "abcdef", 3) { editor.deleteSurroundingText(10, 10, false) },
            Scenario("nothing", "abcdef", 3) { editor.deleteSurroundingText(0, 0, false) },
            Scenario("negative lengths are ignored", "abcdef", 3) { editor.deleteSurroundingText(-2, -1, false) },
            Scenario("a selection and a composition beyond it", "abcdefgh", 2, 3) {
                editor.setComposingRegion(1, 6)
                editor.deleteSurroundingText(1, 1, false)
            },
            Scenario("around a composition, which survives", "abcdef", 3) {
                editor.setComposingRegion(2, 4)
                editor.deleteSurroundingText(1, 1, false)
            },
            Scenario("splits a surrogate pair, being in units", "a$wave", 3) {
                editor.deleteSurroundingText(1, 0, false)
            },
        )
    )

    @Test
    fun deleteSurroundingTextInCodePoints() = run(
        listOf(
            Scenario("an emoji before", "a$wave", 3) { editor.deleteSurroundingText(1, 0, true) },
            Scenario("an emoji after", "${wave}a", 0) { editor.deleteSurroundingText(0, 1, true) },
            Scenario("mixed", "x${wave}b$wave", 6) { editor.deleteSurroundingText(3, 0, true) },
            Scenario("more than there is", "a$wave", 3) { editor.deleteSurroundingText(9, 9, true) },
            // the composing arguments are what EditingSession would pass: how far the
            // composition sticks out past the selection on each side
            Scenario("around a composition", "a${wave}bc${wave}d", 4) {
                editor.setComposingRegion(3, 4)
                editor.deleteSurroundingText(1, 1, true, composingBefore = 1, composingAfter = 0)
            },
            Scenario("a composition before the cursor, an emoji before it", "a${wave}bcde", 7) {
                editor.setComposingRegion(3, 5)
                // the editor deletes from the composition's start, 4 units before the cursor
                editor.deleteSurroundingText(1, 0, true, composingBefore = 4, composingAfter = 0)
            },
            Scenario("the cursor inside a composition, emoji on both sides", "${wave}bcd$wave", 3) {
                editor.setComposingRegion(2, 5)
                editor.deleteSurroundingText(1, 1, true, composingBefore = 1, composingAfter = 2)
            },
            Scenario("a malformed after side refuses the whole deletion", "a${wave}b$high", 4) {
                editor.deleteSurroundingText(1, 1, true)
            },
        )
    )

    /** Malformed text: what the real editor does with a surrogate that has lost its partner. */
    @Test
    fun deleteSurroundingTextInCodePointsWithLoneSurrogates() = run(
        listOf(
            Scenario("a lone low surrogate before", "a$low", 2) { editor.deleteSurroundingText(1, 0, true) },
            Scenario("a lone high surrogate before", "a$high", 2) { editor.deleteSurroundingText(1, 0, true) },
            Scenario("a lone high surrogate after", "${high}a", 0) { editor.deleteSurroundingText(0, 1, true) },
        )
    )

    @Test
    fun textBeforeCursor() = run(
        listOf(
            Scenario("at the start", "abc", 0) { it += "${editor.textBeforeCursor(2)}" },
            Scenario("in the middle", "abcdef", 4) { it += "${editor.textBeforeCursor(2)}" },
            Scenario("more than there is", "abc", 2) { it += "${editor.textBeforeCursor(10)}" },
            Scenario("before a selection", "abcdef", 2, 5) { it += "${editor.textBeforeCursor(10)}" },
            Scenario("before a reversed selection", "abcdef", 5, 2) { it += "${editor.textBeforeCursor(10)}" },
            Scenario("ignores the composing region", "abcdef", 4) {
                editor.setComposingRegion(2, 4)
                it += "${editor.textBeforeCursor(10)}"
            },
        )
    )

    /**
     * Random sequences of every operation, text with surrogate pairs and the odd lone surrogate,
     * positions in and out of range. Seeded, so a failure names a reproducible sequence.
     */
    @Test
    fun randomSequences() {
        val random = kotlin.random.Random(20260923)
        val alphabet = listOf("a", "b", "c", wave, wave, "$high", "$low")
        fun text(max: Int) = (0 until random.nextInt(max + 1)).joinToString("") { alphabet.random(random) }
        val scenarios = (0 until 300).map { n ->
            val initial = text(8)
            val start = random.nextInt(initial.length + 1)
            val end = random.nextInt(initial.length + 1)
            val ops = (0 until random.nextInt(1, 7)).map {
                val a = random.nextInt(-1, 12)
                val b = random.nextInt(-1, 12)
                val t = text(3)
                val ncp = random.nextInt(-3, 4)
                when (random.nextInt(8)) {
                    0 -> "commitText($t, $ncp)" to { e: Subject -> e.editor.commitText(t, ncp) }
                    1 -> "setComposingText($t, $ncp)" to { e: Subject -> e.editor.setComposingText(t, ncp) }
                    2 -> "setSelection($a, $b)" to { e: Subject -> e.editor.setSelection(a, b) }
                    3 -> "setComposingRegion($a, $b)" to { e: Subject -> e.editor.setComposingRegion(a, b) }
                    4 -> "finishComposingText()" to { e: Subject -> e.editor.finishComposingText() }
                    5 -> "deleteSurroundingText($a, $b, units)" to { e: Subject ->
                        e.editor.deleteSurroundingText(a, b, inCodePoints = false)
                    }
                    6 -> "deleteSurroundingText($a, $b, code points)" to { e: Subject ->
                        // the composing overhang, as EditingSession works it out
                        val o = e.outcome(emptyList())
                        val (s0, s1) = o.selection.let { minOf(it.first, it.second) to maxOf(it.first, it.second) }
                        val (c0, c1) = o.composing
                        val before = if (c0 < 0) 0 else (s0 - c0).coerceAtLeast(0)
                        val after = if (c0 < 0) 0 else (c1 - s1).coerceAtLeast(0)
                        e.editor.deleteSurroundingText(a, b, inCodePoints = true, before, after)
                    }
                    else -> "textBeforeCursor($a)" to { e: Subject ->
                        e.returned += "${e.editor.textBeforeCursor(a.coerceAtLeast(0))}"
                    }
                }
            }
            Scenario("#$n '$initial' [$start,$end]: ${ops.joinToString { it.first }}", initial, start, end) { returned ->
                this.returned = returned
                ops.forEach { (_, op) -> op(this) }
                returned += "${editor.textBeforeCursor(20)}"
            }
        }
        run(scenarios)
    }

    /**
     * The same contract at API 23, the app's minSdk. There is no
     * `deleteSurroundingTextInCodePoints` below API 24, so [InputConnectionEditor] measures the
     * code points itself and deletes that many units; this is the only place that path runs.
     */
    @Config(sdk = [23])
    class AtMinSdk : InputEditorContractTest()
}
