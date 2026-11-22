/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.keyboard.KeyAction

/**
 * A key pressed while a stroke is read goes in after its reading, and not at all if another input started
 * meanwhile. Backspace takes the pad's last stroke back while it has one; a key for the engine commits the piece.
 */
class InkKeys(
    private val recognition: HandwritingRecognition,
    private val pad: Pad,
    private val deliver: (InkReading, piece: Int) -> Unit,
) {

    interface Pad {
        val pad: InkPad
        fun undo(): Boolean
        fun next()
    }

    // those before were another input's
    private var since = pad.pad.piece

    fun restart() {
        pad.next()
        since = pad.pad.piece
    }

    fun read(read: InkPad.Read) {
        recognition.read(read) { reading ->
            if (read.piece >= since) deliver(reading, read.piece)
        }
    }

    /** Dropped if another input started, or [attached] says the keyboard is gone: it was not typed for that one. */
    fun onAction(attached: () -> Boolean, act: () -> Unit) {
        if (recognition.busy) {
            val at = since
            // strokes drawn while it waited (a tap's dot taken back and the stroke after) are not the piece it commits
            val pressed = pad.pad.piece.takeIf { pad.pad.finished.isNotEmpty() } ?: NONE
            val strokes = pad.pad.finished.size
            recognition.afterReads {
                if (!attached() || at != since) return@afterReads
                commits = pressed
                commitsStrokes = strokes
                try {
                    act()
                } finally {
                    commits = null
                }
            }
        } else {
            act()
        }
    }

    // [NONE]: none; null, now
    private var commits: Int? = null

    private var commitsStrokes = 0

    /**
     * Decided in its turn: behind a held key, the strokes it would take back are of the piece that key commits.
     * A key for the engine wipes its strokes now, not once the engine's end comes back, else a backspace right
     * behind takes back a stroke of what is committed. A held backspace goes to the engine where the pad had none.
     */
    fun took(action: KeyAction): Boolean {
        // the host does not see the strokes: something typed as its hold begins
        if (action is KeyAction.BackspaceDownAction) {
            if (pad.pad.finished.isNotEmpty()) action.hold.taken()
            return false
        }
        if (action is KeyAction.SymAction && action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace) {
            val held = commits
            when {
                // as pressed: not one drawn while it waited
                held == null || held == NONE -> if (held == null && pad.undo()) return taken(action)
                held == pad.pad.piece && pad.pad.finished.size > commitsStrokes -> return taken(action)
                held == pad.pad.piece && pad.undo() -> return taken(action)
            }
        }
        val wipes = commits?.let { it == pad.pad.piece } ?: pad.pad.finished.isNotEmpty()
        if ((action is KeyAction.SymAction || action is KeyAction.HostKeyAction) && wipes) pad.next()
        return false
    }

    // something was typed as its hold began, which stops short of the field for it
    private fun taken(action: KeyAction.SymAction): Boolean {
        action.hold?.taken()
        return true
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
