/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `EditingSession` driven over a real editor: after each operation, the session's prediction
 * must be exactly where the editor put the cursor, and its composing range exactly the
 * editor's. This is what cursor prediction is for -- a mismatch makes the service treat the
 * editor's next report as the user moving the cursor.
 *
 * Complements `InputEditorContractTest`, which checks the fake; this checks the session's
 * arithmetic against the real thing, including the API 23 path in [InputConnectionEditor].
 */
@RunWith(RobolectricTestRunner::class)
open class EditingSessionOverRealEditorTest {

    private val wave = String(Character.toChars(0x1F44B))

    private class Setup(text: String, cursor: Int, composing: Pair<Int, Int>? = null) {
        val editable = SpannableStringBuilder(text).also { Selection.setSelection(it, cursor) }
        private val connection =
            object : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
                override fun getEditable() = this@Setup.editable
            }
        val session = EditingSession(InputConnectionEditor { connection }).apply {
            selection.resetTo(cursor)
            if (composing != null) {
                connection.setComposingRegion(composing.first, composing.second)
                this.composing.update(composing.first, composing.second)
                setComposingText(FormattedText(arrayOf(text.substring(composing.first, composing.second)), intArrayOf(0), -1))
            }
        }

        /** When set, the text the editor must end up with. */
        var expectedText: String? = null

        fun mismatch(name: String): String? {
            val sel = Selection.getSelectionStart(editable) to Selection.getSelectionEnd(editable)
            val comp = BaseInputConnection.getComposingSpanStart(editable) to
                BaseInputConnection.getComposingSpanEnd(editable)
            val predicted = session.selection.latest.let { it.start to it.end }
            val tracked = if (session.composing.isEmpty()) -1 to -1 else session.composing.let { it.start to it.end }
            val textOk = expectedText == null || expectedText == editable.toString()
            return if (predicted == sel && tracked == comp && textOk) null
            else "$name: editor '$editable' sel=$sel comp=$comp; session predicted=$predicted composing=$tracked" +
                (expectedText?.let { "; expected text '$it'" } ?: "")
        }
    }

    private fun check(vararg cases: Pair<String, () -> Setup>) {
        val failures = cases.mapNotNull { (name, run) -> run().mismatch(name) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun deletingByCodePoints() = check(
        "an emoji before the cursor" to {
            Setup("a$wave", 3).apply { session.deleteSurrounding(1, 0, inCodePoints = true) }
        },
        "several, mixed" to {
            Setup("x${wave}b$wave", 6).apply { session.deleteSurrounding(3, 0, inCodePoints = true) }
        },
        "more than there is" to {
            Setup("a$wave", 3).apply { session.deleteSurrounding(9, 0, inCodePoints = true) }
        },
        "malformed text is refused" to {
            Setup("a${wave[1]}", 2).apply { session.deleteSurrounding(1, 0, inCodePoints = true) }
        },
    )

    @Test
    fun deletingNextToAComposition() = check(
        "a composition before the cursor, an emoji before that" to {
            Setup("a${wave}bcde", 7, composing = 3 to 5).apply { session.deleteSurrounding(1, 0, inCodePoints = true) }
        },
        "the cursor at the end of the composition" to {
            Setup("a${wave}bc", 5, composing = 3 to 5).apply { session.deleteSurrounding(1, 0, inCodePoints = true) }
        },
        "the cursor inside the composition, emoji on both sides" to {
            Setup("${wave}bcd$wave", 3, composing = 2 to 5).apply { session.deleteSurrounding(1, 1, inCodePoints = true) }
        },
        "in units, before a composition" to {
            Setup("abcdef", 6, composing = 3 to 5).apply { session.deleteSurrounding(2, 0, inCodePoints = false) }
        },
        "in units, more than there is before a composition" to {
            Setup("abcdef", 6, composing = 2 to 5).apply { session.deleteSurrounding(9, 0, inCodePoints = false) }
        },
    )

    @Test
    fun directBackspace() = check(
        "an emoji" to {
            Setup("a$wave", 3).apply { session.backspace(EditorTraits(acceptsDeleteSurrounding = true)) }
        },
        "a plain letter" to {
            Setup("ab", 2).apply { session.backspace(EditorTraits(acceptsDeleteSurrounding = true)) }
        },
    )

    private fun preedit(text: String, cursor: Int) = FormattedText(arrayOf(text), intArrayOf(0), cursor)

    /** fcitx's preedit shown in the editor, with its cursor at the end, in the middle, or unset. */
    @Test
    fun composing() = check(
        "a first preedit at the cursor" to {
            Setup("ab", 1).apply { session.updateComposingText(preedit("ni", 2)) }
        },
        "growing" to {
            Setup("ab", 2).apply {
                session.updateComposingText(preedit("n", 1))
                session.updateComposingText(preedit("ni", 2))
                session.updateComposingText(preedit("nih", -1))
            }
        },
        "the preedit cursor in the middle" to {
            Setup("ab", 2).apply { session.updateComposingText(preedit("nihao", 2)) }
        },
        "the same preedit, the cursor moved" to {
            Setup("", 0).apply {
                session.updateComposingText(preedit("nihao", 5))
                session.updateComposingText(preedit("nihao", 1))
            }
        },
        "a preedit over a selection" to {
            Setup("abcdef", 1).apply {
                session.applySelectionOffset(0, 2)
                session.updateComposingText(preedit("ni", 2))
                expectedText = "anidef"
            }
        },
        "cleared" to {
            Setup("ab", 1).apply {
                session.updateComposingText(preedit("nihao", 3))
                session.updateComposingText(FormattedText.Empty)
                expectedText = "ab"
            }
        },
        "committed as something else" to {
            Setup("ab", 1).apply {
                session.updateComposingText(preedit("nihao", 5))
                session.commitText("你好")
            }
        },
        "committed as itself, the cursor in the middle" to {
            Setup("ab", 1).apply {
                session.updateComposingText(preedit("abc", 1))
                session.commitText("abc")
            }
        },
        "the cursor leaving the composition" to {
            Setup("xy", 2).apply {
                session.updateComposingText(preedit("ni", 2))
                Selection.setSelection(editable, 0) // the user taps at 0
                session.onCursorUpdate(0, 0, 2, 4, ignoreSystemCursor = false)
                expectedText = "xyni"
            }
        },
    )

    /**
     * Random sessions: the operations the service drives, plus the user moving the cursor, in
     * any order over text with surrogate pairs. After each step the prediction must be where the
     * editor is, and the text must be what a session over [FakeEditor] (held to the platform by
     * `InputEditorContractTest`) produced -- right cursor, right text. Every step's report is fed
     * back as the service would. Seeded, so a failure names a reproducible sequence.
     */
    @Test
    fun randomSessions() {
        val random = kotlin.random.Random(20260923)
        val alphabet = listOf("a", "b", "c", wave, wave)
        fun text(max: Int) = (0 until random.nextInt(max + 1)).joinToString("") { alphabet.random(random) }
        val failures = mutableListOf<String>()
        repeat(300) { n ->
            val initial = text(6)
            val setup = Setup(initial, initial.length)
            val fake = FakeEditor(initial, initial.length, reselectsZeroAfterInsert = android.os.Build.VERSION.SDK_INT > 23)
            val twin = EditingSession(fake).apply { selection.resetTo(initial.length) }
            val log = mutableListOf<String>()
            for (step in 0 until random.nextInt(1, 10)) {
                val t = text(3)
                val k = random.nextInt(0, 4)
                val at = random.nextInt(0, 12)
                // the second argument sends the Backspace key event when the session declines
                val op: Pair<String, EditingSession.(sendKey: () -> Unit) -> Unit> = when (random.nextInt(10)) {
                    0 -> "commitText($t)" to { commitText(t) }
                    1 -> "commitText($t, cursor $k)" to { commitText(t, k.coerceAtMost(t.length)) }
                    2 -> "preedit($t, $k)" to { updateComposingText(preedit(t, k.coerceAtMost(t.length))) }
                    3 -> "preedit(empty)" to { updateComposingText(FormattedText.Empty) }
                    4 -> "deleteSurrounding($k, 0)" to { deleteSurrounding(k, 0, inCodePoints = true) }
                    5 -> "backspace" to { sendKey -> if (!backspace(EditorTraits(acceptsDeleteSurrounding = true))) sendKey() }
                    6 -> "select back $k" to { applySelectionOffset(-k) }
                    7 -> "cancelSelection" to { cancelSelection() }
                    8 -> "user taps at $at" to {} // applied to the editors below
                    else -> "deleteSelection" to { deleteSelection() }
                }
                log += op.first
                val name = "#$n '$initial': ${log.joinToString()}"
                // With direct deletion on, the key event only goes at the very start, where it
                // does nothing, or before an unpaired surrogate, which DEL removes as one unit.
                setup.session.(op.second) {
                    val c = Selection.getSelectionStart(setup.editable)
                    if (c > 0) {
                        if (!Character.isSurrogate(setup.editable[c - 1])) failures += "$name: key event at $c in '${setup.editable}'"
                        setup.editable.delete(c - 1, c)
                    }
                }
                twin.(op.second) {
                    if (fake.selectionStart > 0) fake.deleteSurroundingText(1, 0, inCodePoints = false)
                }
                if (op.first.startsWith("user taps")) {
                    Selection.setSelection(setup.editable, at.coerceAtMost(setup.editable.length))
                    fake.reportSelection(at.coerceAtMost(fake.text.length), at.coerceAtMost(fake.text.length))
                } else {
                    setup.mismatch(name)?.let { failures += it }
                }
                if (fake.text != setup.editable.toString()) {
                    failures += "$name: text '${setup.editable}', expected '${fake.text}'"
                }
                // the editor's report for this step, as the service would pass it on
                setup.session.onCursorUpdate(
                    Selection.getSelectionStart(setup.editable), Selection.getSelectionEnd(setup.editable),
                    BaseInputConnection.getComposingSpanStart(setup.editable),
                    BaseInputConnection.getComposingSpanEnd(setup.editable),
                    ignoreSystemCursor = false,
                )
                twin.onCursorUpdate(fake.selectionStart, fake.selectionEnd, fake.composingStart, fake.composingEnd, ignoreSystemCursor = false)
                if (failures.isNotEmpty()) break
            }
        }
        assertTrue(failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    /** The same at the app's minSdk, where [InputConnectionEditor] measures code points itself. */
    @Config(sdk = [23])
    class AtMinSdk : EditingSessionOverRealEditorTest()
}
