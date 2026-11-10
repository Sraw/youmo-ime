/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.keyboard.KeyAction

/**
 * A pad's strokes and the keys typed beside it, in order (the handwriting keyboard's, and the letters'
 * written on, 键盘手写): what the pad reads is handed to [deliver] with its piece, a piece done to
 * [finished] after its readings; a key pressed while a stroke is read goes in after its reading, and
 * not at all if another input started meanwhile ([restart]). Backspace takes the pad's last stroke
 * back while it has one; a key for the engine commits the piece, its strokes wiped first.
 */
class InkKeys(
    private val recognition: HandwritingRecognition,
    private val pad: Pad,
    private val deliver: (InkReading, piece: Int) -> Unit,
    private val finished: (piece: Int) -> Unit,
) {

    /** What is written on: its [pad], a stroke taken back ([undo], false with none), the piece ended as it is ([next]). */
    interface Pad {
        val pad: InkPad
        fun undo(): Boolean
        fun next()
    }

    // the first piece of the field's input: those before were another input's
    private var since = pad.pad.piece

    /** Another input started (a field, the keyboard shown again): the pad starts afresh, what it was reading dropped. */
    fun restart() {
        pad.next()
        since = pad.pad.piece
    }

    /** [read] read, its reading delivered if still of this input. */
    fun read(read: InkPad.Read) {
        recognition.read(read) { reading ->
            if (read.piece >= since) deliver(reading, read.piece)
        }
    }

    /** [piece] done, told after its readings: the engine must have them first. */
    fun finish(piece: Int) {
        recognition.afterReads { if (piece >= since) finished(piece) }
    }

    /**
     * [act] now, or once the strokes being read are in, as written before it; dropped if another input
     * started meanwhile, or [attached] says the keyboard is gone: it was not typed for that one.
     */
    fun onAction(attached: () -> Boolean, act: () -> Unit) {
        if (recognition.busy) {
            val at = since
            recognition.afterReads { if (attached() && at == since) act() }
        } else {
            act()
        }
    }

    /**
     * [action] in its turn, before the engine has it: true where the pad took it (backspace with a
     * stroke to take back). Decided in its turn: behind a held key, the strokes it would take back are of
     * the piece that key commits. A key for the engine commits the piece: its strokes wiped now, not once
     * the engine's end of it comes back, else a backspace right behind (held with it) takes back a
     * stroke of what is committed.
     */
    fun took(action: KeyAction): Boolean {
        if (action is KeyAction.SymAction && action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace && pad.undo()) return true
        if ((action is KeyAction.SymAction || action is KeyAction.HostKeyAction) && pad.pad.finished.isNotEmpty()) pad.next()
        return false
    }
}
