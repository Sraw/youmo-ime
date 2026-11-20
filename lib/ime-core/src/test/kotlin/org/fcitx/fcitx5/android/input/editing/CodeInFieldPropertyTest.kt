/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Code in the field as a property: whatever the app does between the keyboard's writes and however late the editor
 * reports, no character the keyboard did not write is deleted, replaced or reordered by it ([FakeEditor.owners] says
 * whose each is). The app's own change is reported before the keyboard acts again, but for a span ended unreported
 * (EditText's clearComposingText). Not covered: the app deleting part of the code and keeping the rest (a read back
 * cannot tell who wrote it); clearing the field stands in.
 */
class CodeInFieldPropertyTest {

    @Test
    fun `the keyboard never deletes nor reorders the app's characters`() = SEEDS.forEach { seed ->
        val random = Random(seed)
        repeat(RUNS) { Run(random, it).steps() }
    }

    /** [STEPS] random steps of the keyboard, the app and the editor's reports. */
    private class Run(private val random: Random, private val run: Int) {
        private val e: FakeEditor
        private val s: EditingSession
        private var expected: String
        private val log = mutableListOf<String>()
        private val reports = ArrayDeque<IntArray>()
        private var reported: IntArray

        init {
            val initial = random.text(random.nextInt(8))
            val cursor = random.nextInt(initial.length + 1)
            e = FakeEditor(initial, cursor)
            s = EditingSession(e).also { it.selection.resetTo(cursor) }
            s.inlineCompletion = random.nextBoolean()
            // an app's InputFilter upper-casing what is typed: the app's characters compared by letter
            if (random.nextInt(6) == 0) e.rewriter = { it.uppercase() }
            // an editor that gives its composing region at once (Android 13) or not
            e.snapshots = random.nextBoolean()
            expected = appChars()
            reported = now()
        }

        private fun now() = intArrayOf(e.selectionStart, e.selectionEnd, e.composingStart, e.composingEnd)

        private fun appChars() = e.text.filterIndexed { i, _ -> e.owners[i] == FakeEditor.APP }.uppercase()

        private fun queue() {
            val now = now()
            if (now.contentEquals(reported)) return
            reported = now
            reports.addLast(now)
        }

        private fun state() = "'${e.text}' ${e.owners} sel ${e.selectionStart}..${e.selectionEnd} comp ${e.composingStart}..${e.composingEnd}"

        private fun session() = "ses ${s.composing} ${s.kind} '${s.composingText}' lat ${s.selection.latest} queued ${reports.size}"

        // the log joined only when it fails: joined at every step it was most of the test's work
        private fun held() {
            val chars = appChars()
            if (expected != chars) assertEquals("run $run:\n${log.joinToString("\n")}", expected, chars)
        }

        private fun deliver() {
            val r = reports.removeFirst()
            s.onCursorUpdate(r[0], r[1], r[2], r[3])
            queue()
            log += "report ${r.joinToString()} -> ${state()} ${session()}"
            held()
        }

        private fun keyboard(what: String, act: () -> Unit) {
            act()
            queue()
            log += "$what -> ${state()} ${session()}"
            held()
        }

        private fun app(what: String, act: () -> Unit) {
            act()
            queue()
            log += "app $what -> ${state()}"
            expected = appChars()
            // reported, with all before it, before the keyboard acts again
            while (reports.isNotEmpty()) deliver()
        }

        fun steps() = repeat(STEPS) {
            when (val k = random.nextInt(16)) {
                3, 4 -> if (reports.isNotEmpty()) deliver()
                in 5..8, 13, 14 -> appStep(k)
                else -> keyboardStep(k)
            }
        }

        private fun appStep(k: Int) {
            val length = e.text.length
            when (k) {
                5 -> {
                    val at = random.nextInt(length + 1)
                    app("insert at $at") { e.appReplace(at, at, random.text(1 + random.nextInt(3))) }
                }
                6 -> if (length > 0) {
                    val a = random.nextInt(length)
                    val b = a + 1 + random.nextInt(length - a)
                    // not the code itself, unless all goes (see the class's note)
                    val code = if (s.codeInField) s.composing.start until s.composing.end else IntRange.EMPTY
                    val span = if (e.composingStart >= 0) e.composingStart until e.composingEnd else IntRange.EMPTY
                    val clear = (a until b).any { it in code || it in span }
                    app(if (clear) "clear" else "delete $a..$b") { e.appReplace(if (clear) 0 else a, if (clear) length else b, "") }
                }
                7 -> app("drop span") { e.appDropSpan() }
                // as EditText's clearComposingText in a search box: the span alone gone, unreported; over the cursor alone
                14 -> if (e.selectionStart == e.selectionEnd) app("drop span, unreported") {
                    e.appDropSpan()
                    reported = now()
                }
                8 -> {
                    val a = random.nextInt(length + 1)
                    val b = if (random.nextBoolean()) a else a + random.nextInt(length - a + 1)
                    app("select $a..$b") { e.appSelect(a, b) }
                }
                else -> app("unreadable or not") { e.revealLimit = if (e.revealLimit == null) Int.MAX_VALUE else null }
            }
        }

        private fun keyboardStep(k: Int) {
            when (k) {
                0, 1, 2 -> {
                    val code = random.code()
                    // a code's first letter typed, its cursor at its end; then moved in it (a tap, the arrow keys)
                    val at = if (s.composing.isEmpty() || random.nextBoolean()) -1 else random.nextInt(code.length + 1)
                    keyboard("write '$code' at $at") { s.writeCode(FormattedText(arrayOf(code), intArrayOf(0), at)) }
                }
                9 -> keyboard("leave") { s.leaveComposition(fallBack = random.nextBoolean()) }
                // the commit replaces its code; one over the app's selection replaces it, the user's choice, not the keyboard's deletion
                10 -> if (s.codeInField || e.selectionStart == e.selectionEnd) keyboard("commit") { s.commitText("你") }
                11 -> keyboard("code emptied") { s.writeCode(FormattedText.Empty) }
                12 -> {
                    // input restarts with what the editor said before it
                    while (reports.isNotEmpty()) deliver()
                    keyboard("restart") { s.codeRestarted(again = random.nextBoolean()) }
                }
                else -> {
                    // a connection gone for one write: every call dropped
                    e.isAvailable = false
                    keyboard("write, no connection") { s.writeCode(FormattedText(arrayOf(random.code()), intArrayOf(0), -1)) }
                    e.isAvailable = true
                }
            }
        }
    }

    private companion object {
        fun Random.text(n: Int) = String(CharArray(n) { if (nextInt(5) == 0) ' ' else 'a' + nextInt(3) })

        fun Random.code() = text(1 + nextInt(6)).trim().ifEmpty { "a" }

        val SEEDS = longArrayOf(20261113, 20261114, 20261115, 20261116)
        const val RUNS = 5000
        const val STEPS = 40
    }
}
