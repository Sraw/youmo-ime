/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Queue
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Reader
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A key held behind reads commits the piece the pad held as it was pressed: strokes drawn while it
 * waited (a tap's dot taken back, the stroke after it) stay on the pad.
 */
class InkKeysTest {

    private val worker = Queue()
    private val main = Queue()
    private val recognition = HandwritingRecognition(worker, main, { -1 }, Reader())
    private val ink = InkPad()
    private var nexts = 0
    private var undos = 0
    private val keys = InkKeys(
        recognition,
        object : InkKeys.Pad {
            override val pad = ink
            override fun undo() = ink.finished.isNotEmpty().also { if (it) undos++ }
            override fun next() {
                nexts++
                ink.next()
            }
        },
        { _, _ -> },
        {},
    )

    private fun stroke(at: Float) {
        ink.down(at, at)
        ink.move(at + 1, at + 1)
        ink.up(0)?.let(keys::read)
    }

    private fun press(act: String) = keys.onAction({ true }) { keys.took(KeyAction.HostKeyAction(act)) }

    private fun backspace() = keys.onAction({ true }) {
        keys.took(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)))
    }

    private fun runAll() {
        while (worker.size > 0 || main.size > 0) {
            worker.run()
            main.run()
        }
    }

    @Test
    fun aKeyHeldBehindReadsKeepsTheStrokesDrawnWhileItWaited() {
        stroke(1f)
        ink.next() // the piece's pause ended, its read still pending
        press("z")
        stroke(5f) // the tap's dot, then a stroke under the pen
        ink.down(9f, 9f)
        runAll()
        assertEquals(0, nexts)
        assertEquals(1, ink.finished.size)
        assertEquals(true, ink.drawing)
    }

    @Test
    fun aKeyHeldBehindReadsStillCommitsThePieceItWasPressedOn() {
        stroke(1f)
        press("z")
        runAll()
        assertEquals(1, nexts)
        assertFalse(ink.finished.isNotEmpty())
    }

    @Test
    fun aBackspaceHeldBehindReadsTakesBackNoStrokeDrawnWhileItWaited() {
        stroke(1f)
        backspace()
        stroke(5f) // drawn after the press: not the stroke it was pressed to take back
        runAll()
        assertEquals(0, undos)
        assertEquals(2, ink.finished.size)
    }

    @Test
    fun aBackspaceHeldBehindReadsTakesBackTheStrokeItWasPressedOn() {
        stroke(1f)
        backspace()
        runAll()
        assertEquals(1, undos)
    }

    @Test
    fun aBackspaceHeldWithNoStrokeGoesToTheEngineThoughOneWasDrawnWhileItWaited() {
        stroke(1f)
        ink.next()
        backspace()
        stroke(5f)
        runAll()
        assertEquals(0, undos)
        assertEquals(1, ink.finished.size)
    }

    // the piece still open as the key was pressed: a stroke drawn in its pause is of that piece, committed with it
    @Test
    fun aKeyHeldWhileItsPieceGoesOnCommitsTheStrokesAddedToIt() {
        stroke(1f)
        press("z")
        stroke(5f)
        runAll()
        assertEquals(1, nexts)
        assertFalse(ink.finished.isNotEmpty())
    }
}
