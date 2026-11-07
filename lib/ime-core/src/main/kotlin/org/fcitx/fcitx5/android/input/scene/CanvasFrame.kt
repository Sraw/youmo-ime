/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op

/**
 * What a frame of the keys' canvas (the app's KeyboardCanvasView) draws of a key, as values: the
 * view only records and draws what this says.
 */
object CanvasFrame {

    /** A face of a key drawn this frame: its [pressed] one or not, at [alpha], recorded first where [record]. */
    data class Face(val pressed: Boolean, val alpha: Float, val record: Boolean)

    /**
     * A key's faces this frame, [pressed] or not, each [normalRecorded] / [pressedRecorded] already.
     * The normal face always, the pressed one once it was ever recorded: a node no frame draws is
     * taken out of the render tree and loses its recording (seen on a device: a key let go drawn as
     * nothing), so both stay drawn and a press only changes their alpha, the face not shown at 0.
     */
    fun faces(pressed: Boolean, normalRecorded: Boolean, pressedRecorded: Boolean): List<Face> = buildList {
        add(Face(pressed = false, alpha = if (pressed) 0f else 1f, record = !normalRecorded))
        if (pressed || pressedRecorded) add(Face(pressed = true, alpha = if (pressed) 1f else 0f, record = !pressedRecorded))
    }

    /**
     * The box a key's node is recorded in: its [bounds] and whatever of [ops] reaches past them (a
     * shadow by its blur, a text by a generous guess of its ink), [pad] more round it for the edges'
     * anti-aliasing. Not the whole view's: a node that size a key made every key's redraw the board's.
     */
    fun extent(bounds: Box, ops: List<Op>, pad: Double): Box {
        var b = bounds
        for (op in ops) {
            val reach = when (op) {
                is Op.Shadow -> op.shape.bounds.let { s -> Box(s.left - op.blur, s.top - op.blur, s.right + op.blur, s.bottom + op.blur) }
                is Op.Fill -> op.shape.bounds
                is Op.Ripple -> op.shape.bounds
                is Op.Stroke -> op.shape.bounds.let { s -> Box(s.left - op.width, s.top - op.width, s.right + op.width, s.bottom + op.width) }
                is Op.Gradient -> op.shape.bounds
                is Op.NineSlice -> op.shape.bounds
                is Op.Picture -> op.shape.bounds
                is Op.Icon -> op.box
                is Op.Text -> {
                    val wide = op.size * op.scaleX * (op.text.length + 1)
                    Box(op.x - op.size, op.baseline - op.size * TEXT_UP, op.x + wide, op.baseline + op.size * TEXT_DOWN)
                }
            }
            b = b.union(reach)
        }
        return Box(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    /** Whether [ops] show the theme's picture: drawn where the view is over it, recorded again when that moves. */
    fun showsPicture(ops: List<Op>): Boolean = ops.any { it is Op.Picture }

    // a text's ink at most this many times its size above and below its baseline (accents, descenders)
    private const val TEXT_UP = 1.5
    private const val TEXT_DOWN = 0.6
}
