/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Queue
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Reader
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A key held behind reads commits the piece the pad held as it was pressed: strokes drawn while it
 * waited (a tap's dot taken back, the stroke after it) stay on the pad. A backspace takes back the
 * stroke it was pressed on, or goes to the engine where none was.
 */
class InkKeysTest {

    // a pad and its keys, fresh for each row
    private class Pad {
        private val worker = Queue()
        private val main = Queue()
        private val recognition = HandwritingRecognition(worker, main, { -1 }, Reader())
        val ink = InkPad()
        var nexts = 0
        var undos = 0

        // what the key's action told the keyboard: true, taken by the pad; false, sent to the engine
        var took: Boolean? = null
        private val keys = InkKeys(
            recognition,
            object : InkKeys.Pad {
                override val pad = ink
                // the stroke taken back off the pad, as the pad's view does
                override fun undo() = (ink.undo(0) != null).also { if (it) undos++ }
                override fun next() {
                    nexts++
                    ink.next()
                }
            },
            { _, _ -> },
            {},
        )

        fun stroke(at: Float) {
            ink.down(at, at)
            ink.move(at + 1, at + 1)
            ink.up(0)?.let(keys::read)
        }

        fun press(act: String) = keys.onAction({ true }) { took = keys.took(KeyAction.HostKeyAction(act)) }

        fun backspace() = keys.onAction({ true }) {
            took = keys.took(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)))
        }

        fun runAll() {
            while (worker.size > 0 || main.size > 0) {
                worker.run()
                main.run()
            }
        }
    }

    private class Row(
        val why: String,
        val nexts: Int,
        val undos: Int,
        val strokes: Int,
        val took: Boolean,
        val drawing: Boolean = false,
        val acts: Pad.() -> Unit,
    )

    @Test
    fun aKeyHeldBehindReadsActsOnThePadAsItWasPressed() = assertRows(
        listOf(
            Row("a key held keeps the strokes drawn while it waited (the tap's dot, a stroke under the pen)", 0, 0, 1, false, drawing = true) {
                stroke(1f)
                ink.next() // the piece's pause ended, its read still pending
                press("z")
                stroke(5f)
                ink.down(9f, 9f)
            },
            Row("a key held still commits the piece it was pressed on", 1, 0, 0, false) {
                stroke(1f)
                press("z")
            },
            Row("a key held while its piece goes on commits the strokes added to it in its pause", 1, 0, 0, false) {
                stroke(1f)
                press("z")
                stroke(5f)
            },
            Row("a backspace held takes back no stroke drawn while it waited, nor goes to the engine", 0, 0, 2, true) {
                stroke(1f)
                backspace()
                stroke(5f)
            },
            Row("a backspace held takes back the stroke it was pressed on", 0, 1, 0, true) {
                stroke(1f)
                backspace()
            },
            Row("a backspace held with no stroke goes to the engine though one was drawn while it waited", 0, 0, 1, false) {
                stroke(1f)
                ink.next()
                backspace()
                stroke(5f)
            },
        ),
        Row::why,
    ) { row ->
        val pad = Pad()
        pad.(row.acts)()
        pad.runAll()
        assertEquals("taken by the pad", row.took, pad.took)
        assertEquals("pieces committed", row.nexts, pad.nexts)
        assertEquals("strokes taken back", row.undos, pad.undos)
        assertEquals("strokes left", row.strokes, pad.ink.finished.size)
        assertEquals("a stroke under the pen", row.drawing, pad.ink.drawing)
    }
}
