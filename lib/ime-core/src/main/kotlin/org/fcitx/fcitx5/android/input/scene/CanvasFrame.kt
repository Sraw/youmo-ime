/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import kotlin.math.hypot

/** What a frame of KeyboardCanvasView draws of a key, as values: the view only records and draws this. */
object CanvasFrame {

    data class Face(val pressed: Boolean, val alpha: Float, val record: Boolean)

    /** The normal face always, the pressed one once recorded: a node no frame draws loses its recording (a released key drew as nothing), so presses change alpha only. */
    fun faces(pressed: Boolean, normalRecorded: Boolean, pressedRecorded: Boolean): List<Face> =
        FACES[(if (pressed) PRESSED else 0) + (if (normalRecorded) NORMAL_RECORDED else 0) + (if (pressedRecorded) PRESSED_RECORDED else 0)]

    private fun made(pressed: Boolean, normalRecorded: Boolean, pressedRecorded: Boolean): List<Face> = buildList {
        add(Face(pressed = false, alpha = if (pressed) 0f else 1f, record = !normalRecorded))
        if (pressed || pressedRecorded) add(Face(pressed = true, alpha = if (pressed) 1f else 0f, record = !pressedRecorded))
    }

    // the eight answers made once: asked for each key in each frame
    private const val PRESSED = 4
    private const val NORMAL_RECORDED = 2
    private const val PRESSED_RECORDED = 1
    private val FACES = List(PRESSED * 2) { i -> made(i and PRESSED != 0, i and NORMAL_RECORDED != 0, i and PRESSED_RECORDED != 0) }

    /** [bounds] and whatever of [ops] reaches past them (shadow blur, a generous text-ink guess, rotation), [pad] more for anti-aliasing. */
    fun extent(bounds: Box, ops: List<Op>, pad: Double): Box {
        var b = bounds
        for (op in ops) {
            val reach = when (op) {
                is Op.Shadow -> op.shape.bounds.let { s -> Box(s.left - op.blur, s.top - op.blur, s.right + op.blur, s.bottom + op.blur) }
                is Op.Fill -> op.shape.bounds
                // clipped to its shape
                is Op.InnerShadow -> op.shape.bounds
                is Op.Ripple -> op.shape.bounds
                is Op.Stroke -> op.shape.bounds.let { s -> Box(s.left - op.width, s.top - op.width, s.right + op.width, s.bottom + op.width) }
                is Op.Gradient -> op.shape.bounds
                is Op.NineSlice -> op.shape.bounds
                is Op.Picture -> op.shape.bounds
                // its stand-in drawn when the image does not decode
                is Op.Sticker -> extent(op.box, op.standIn, 0.0)
                is Op.Icon -> op.box.let { i -> if (op.rotation == 0.0) i else turned(i, (i.left + i.right) / 2, (i.top + i.bottom) / 2) }
                is Op.Text -> ink(op)
            }
            b = b.union(reach)
        }
        return Box(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    private fun ink(op: Op.Text): Box {
        // letter spacing (em) widens it; a stroke reaches round it, a shadow moved and blurred
        val spaced = op.size * op.letterSpacing.coerceAtLeast(0.0) * op.text.length
        val wide = op.size * op.scaleX * (op.text.length + 1) + spaced
        val o = op.stroke?.width ?: 0.0
        val glyphs = Box(op.x - op.size - o, op.baseline - op.size * TEXT_UP - o, op.x + wide + o, op.baseline + op.size * TEXT_DOWN + o)
        val ink = op.shadow?.let { sh ->
            glyphs.union(Box(glyphs.left + sh.dx - sh.blur, glyphs.top + sh.dy - sh.blur, glyphs.right + sh.dx + sh.blur, glyphs.bottom + sh.dy + sh.blur))
        } ?: glyphs
        return if (op.rotation == 0.0) ink else turned(ink, op.x, op.baseline)
    }

    // the square round ([x], [y]) reaching its farthest corner
    private fun turned(box: Box, x: Double, y: Double): Box {
        val r = listOf(box.left, box.right).maxOf { cx -> listOf(box.top, box.bottom).maxOf { cy -> hypot(cx - x, cy - y) } }
        return Box(x - r, y - r, x + r, y + r)
    }

    /** Recorded again when the view moves over the picture. */
    fun showsPicture(ops: List<Op>): Boolean = ops.any { it is Op.Picture }

    // a text's ink at most this many times its size above and below its baseline (accents, descenders)
    private const val TEXT_UP = 1.5
    private const val TEXT_DOWN = 0.6
}
