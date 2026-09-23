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

        fun mismatch(name: String): String? {
            val sel = Selection.getSelectionStart(editable) to Selection.getSelectionEnd(editable)
            val comp = BaseInputConnection.getComposingSpanStart(editable) to
                BaseInputConnection.getComposingSpanEnd(editable)
            val predicted = session.selection.latest.let { it.start to it.end }
            val tracked = if (session.composing.isEmpty()) -1 to -1 else session.composing.let { it.start to it.end }
            return if (predicted == sel && tracked == comp) null
            else "$name: editor '$editable' sel=$sel comp=$comp; session predicted=$predicted composing=$tracked"
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

    /** The same at the app's minSdk, where [InputConnectionEditor] measures code points itself. */
    @Config(sdk = [23])
    class AtMinSdk : EditingSessionOverRealEditorTest()
}
