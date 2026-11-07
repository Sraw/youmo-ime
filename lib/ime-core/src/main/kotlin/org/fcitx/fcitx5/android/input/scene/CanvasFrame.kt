/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import kotlin.math.hypot

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
    fun faces(pressed: Boolean, normalRecorded: Boolean, pressedRecorded: Boolean): List<Face> =
        FACES[(if (pressed) PRESSED else 0) + (if (normalRecorded) NORMAL_RECORDED else 0) + (if (pressedRecorded) 1 else 0)]

    private fun made(pressed: Boolean, normalRecorded: Boolean, pressedRecorded: Boolean): List<Face> = buildList {
        add(Face(pressed = false, alpha = if (pressed) 0f else 1f, record = !normalRecorded))
        if (pressed || pressedRecorded) add(Face(pressed = true, alpha = if (pressed) 1f else 0f, record = !pressedRecorded))
    }

    // the eight answers made once: asked for each key in each frame, a list and its faces each time
    private const val PRESSED = 4
    private const val NORMAL_RECORDED = 2
    private val FACES = List(PRESSED * 2) { i -> made(i and PRESSED != 0, i and NORMAL_RECORDED != 0, i and 1 != 0) }

    /**
     * The box a key's node is recorded in: its [bounds] and whatever of [ops] reaches past them (a
     * shadow by its blur, a text by a generous guess of its ink, either turned any way about its
     * point where it is rotated), [pad] more round it for the edges' anti-aliasing. The node is the
     * key's place, not the whole view's, so what a key draws is all it holds; it does not narrow what a
     * frame redraws: a press invalidates the view, whose list of nodes is recorded again, all of it.
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
                is Op.Icon -> op.box.let { i -> if (op.rotation == 0.0) i else turned(i, (i.left + i.right) / 2, (i.top + i.bottom) / 2) }
                is Op.Text -> {
                    val wide = op.size * op.scaleX * (op.text.length + 1)
                    val ink = Box(op.x - op.size, op.baseline - op.size * TEXT_UP, op.x + wide, op.baseline + op.size * TEXT_DOWN)
                    if (op.rotation == 0.0) ink else turned(ink, op.x, op.baseline)
                }
            }
            b = b.union(reach)
        }
        return Box(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    // [box] turned any way about ([x], [y]): within the square round that point reaching its farthest corner
    private fun turned(box: Box, x: Double, y: Double): Box {
        val r = listOf(box.left, box.right).maxOf { cx -> listOf(box.top, box.bottom).maxOf { cy -> hypot(cx - x, cy - y) } }
        return Box(x - r, y - r, x + r, y + r)
    }

    /** Whether [ops] show the theme's picture: drawn where the view is over it, recorded again when that moves. */
    fun showsPicture(ops: List<Op>): Boolean = ops.any { it is Op.Picture }

    // a text's ink at most this many times its size above and below its baseline (accents, descenders)
    private const val TEXT_UP = 1.5
    private const val TEXT_DOWN = 0.6
}
