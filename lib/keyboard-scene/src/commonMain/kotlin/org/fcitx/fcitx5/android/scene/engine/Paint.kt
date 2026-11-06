/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Theme

/**
 * Step 4's and 6's drawing of §6: a face's layers as ops, under its content. The order is theme
 * 3.0's: the shadow, the fill, the pressed layer, the border; then the glyph and marks. Nothing
 * transparent is drawn (an op a renderer would skip anyway, and one the snapshots would carry).
 */
internal class Paint(private val theme: Theme, private val density: Double) {

    /** [fill] over [shape], or nothing (none, or transparent). */
    fun fill(shape: Shape, fill: Fill?): Op? = when (fill) {
        null -> null
        is Fill.Solid -> theme.color(fill.color).takeUnless { it.transparent }?.let { Op.Fill(shape, it) }
        is Fill.Linear -> Op.Gradient(shape, fill.angle, fill.stops.map { (c, at) -> theme.color(c) to at })
        is Fill.Image -> Op.NineSlice(shape, fill.file, fill.insets, fill.pxPerDp)
        is Fill.Picture -> Op.Picture(shape, fill.blurDp, fill.brightness)
    }

    /**
     * A key's face in one state: its shadow, fill, pressed layer ([pressed]: the state is), border.
     * The pressed layer is the look's `overlay` over the pressed box, a ripple's from the touch, but
     * none for `press="face"`: the pressed look's own fill is the pressed face (rules B §3: a key with
     * a pressed face of its own is not tinted too), nor where the box has no size (a face of nothing).
     */
    fun face(g: FaceGeometry, look: KeyLook, pressed: Boolean): List<Op> = buildList {
        val face = g.face
        val shadow = look.shadow
        if (g.shadow != null && shadow.color != null) {
            val c = theme.color(shadow.color)
            if (!c.transparent) add(Op.Shadow(g.shadow, shadow.blur * density, c))
        }
        if (face != null) fill(face, look.fill)?.let(::add)
        val overlay = look.overlay?.takeIf { !it.transparent && look.press != "face" }
        if (pressed && overlay != null && g.pressed.box.hasSize) {
            add(if (look.press == "ripple") Op.Ripple(g.pressed, overlay) else Op.Fill(g.pressed, overlay))
        }
        val border = look.border
        val w = g.border
        if (face != null && w > 0 && border.color != null) {
            val c = theme.color(border.color)
            // the line's middle half its width in, so all of it is inside the face
            val line = Shape.Rect(face.box.inset(w / 2, w / 2, w / 2, w / 2), face.radii.map { maxOf(0.0, it - w / 2) })
            if (!c.transparent) add(Op.Stroke(line, w, c))
        }
    }

    /** [d]'s texts and icons, each coloured as [looks] has its part. */
    fun content(d: Drawn, looks: (Part) -> TextLook): List<Op> = buildList {
        for (i in d.icons) {
            val tint = looks(i.part).iconColor
            if (!tint.transparent) add(Op.Icon(i.name, i.box, tint))
        }
        for (t in d.texts) {
            val color = looks(t.part).color
            if (!color.transparent) add(Op.Text(t.text, t.x, t.baseline, t.font, t.weight, t.size, color, t.scaleX))
        }
    }
}
