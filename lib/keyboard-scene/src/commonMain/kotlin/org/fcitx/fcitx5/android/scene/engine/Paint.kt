/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.TextShadow
import org.fcitx.fcitx5.android.scene.TextStroke
import org.fcitx.fcitx5.android.scene.theme.Argb
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
        is Fill.Image -> Op.NineSlice(shape, fill.file, fill.insets, fill.pxPerDp, fill.fallback?.let(theme::color)?.takeUnless { it.transparent })
        is Fill.Picture -> Op.Picture(shape, fill.blurDp, fill.brightness, fill.over?.let(theme::color)?.takeUnless { it.transparent })
    }

    /**
     * A key's face in one state: its shadow, its cap's band (`depth`), fill, inner shadow, pressed layer
     * ([pressed]: the state is), border (CSS's order: an inset shadow over the background, under the border).
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
        g.depth?.let { cap -> look.depthColor.takeUnless { it.transparent }?.let { add(Op.Fill(cap, it)) } }
        if (face != null) fill(face, look.fill)?.let(::add)
        if (face != null) inner(face, look)?.let(::add)
        val overlay = look.overlay?.takeIf { !it.transparent && look.press != "face" }
        if (pressed && overlay != null && g.pressed.bounds.hasSize) {
            add(if (look.press == "ripple") Op.Ripple(g.pressed, overlay) else Op.Fill(g.pressed, overlay))
        }
        val border = look.border
        val line = g.line
        if (face != null && line != null && border.color != null) {
            val c = theme.color(border.color)
            if (!c.transparent) add(Op.Stroke(line, g.border, c))
        }
    }

    // `inner-shadow`: inside the face, all but the face moved by its offset, blurred; the ring round it as
    // far out as the blur reaches (BlurMaskFilter's radius: some twice it), so it is whole at the face's edge
    private fun inner(face: Shape, look: KeyLook): Op? {
        val s = look.innerShadow ?: return null
        val c = s.color?.let(theme::color)?.takeUnless { it.transparent } ?: return null
        val blur = s.blur * density
        val hole = face.moved(s.dx * density, s.dy * density)
        val far = 2 * blur + 1
        val ring = face.bounds.union(hole.bounds).inset(-far, -far, -far, -far)
        return Op.InnerShadow(face, hole, ring, blur, c)
    }

    /**
     * [d]'s texts and icons, each coloured as [looks] has its part; a text outlined and shadowed as its
     * part is (`stroke`, `text-shadow`), so a text of a transparent colour with an outline or a shadow is
     * still drawn (CSS's shadow of a transparent text). A text in runs (`font-han`, `font-latin`; a table's
     * roots in lines) outlined or shadowed is drawn twice: every run's outline and shadow first, then every
     * run's fill, so no run's outline is over the fill of the run before it.
     */
    fun content(d: Drawn, looks: (Part) -> TextLook): List<Op> = buildList {
        for (i in d.icons) {
            val tint = looks(i.part).iconColor
            if (!tint.transparent) add(Op.Icon(i.name, i.box, tint))
        }
        val texts = d.texts.mapNotNull { t ->
            val look = looks(t.part)
            val color = look.color
            val stroke = look.stroke?.let { s -> s.color?.let(theme::color)?.takeUnless { it.transparent }?.let { TextStroke(s.width * density, it) } }
            val shadow = look.textShadow?.let { s ->
                s.color?.let(theme::color)?.takeUnless { it.transparent }?.let { TextShadow(s.dx * density, s.dy * density, s.blur * density, it) }
            }
            if (color.transparent && stroke == null && shadow == null) return@mapNotNull null
            Op.Text(t.text, t.x, t.baseline, t.font, t.weight, t.size, color, t.scaleX, letterSpacing = t.spacing, stroke = stroke, shadow = shadow, part = t.part.xml)
        }
        if (texts.size < 2 || texts.none { it.stroke != null || it.shadow != null }) {
            addAll(texts)
            return@buildList
        }
        texts.filter { it.stroke != null || it.shadow != null }.forEach { add(it.copy(color = NO_FILL)) }
        texts.filter { !it.color.transparent }.forEach { add(it.copy(stroke = null, shadow = null)) }
    }

    private companion object {
        val NO_FILL = Argb(0)
    }
}
