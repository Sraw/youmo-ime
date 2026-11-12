/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Shadow
import org.fcitx.fcitx5.android.scene.theme.Value

/**
 * The app's 按键样式 (横向间距, 纵向间距, 按键圆角, 按键边框) over a 4.0 theme's own keys: laid on each key's values
 * after the theme's cascade, so it is the real key's (its board, id, role, classes, states, pressed) whatever
 * selectors the theme wrote. A 3.0 theme's look has them written in already (KeyStyleChoice.applyTo): it has
 * none of this. Each null (and [flat] false) is the theme's own; a theme maker's preview has none ([Env.keyStyle]).
 *
 * - [across], [upDown]: each key's left and right, top and bottom inset, dp, upright and on its side; at 紧凑 the
 *   top and bottom shrunk ([Gap.compact]) as a 3.0 theme's are. The theme's bottom-minus-top is kept about the
 *   level (机械键帽's room for its depth), never above the cell's edge.
 * - [radiusDp]: every key's corners.
 * - [flat] (无边框; the keys are then in `@flat` too, [Env.flat]): every key alike, no fill, border, shadow,
 *   inner shadow, depth or sink, whatever its face was (the owner: what is set is what is drawn, no exceptions;
 *   a skin whose picture carries its letters then shows none, as the user chose). A key of `face="keep"` (a 4.0
 *   pack's 拼, 确定) is flattened too: it is a key. Slots are no keys and keep their face (九键's side column, the
 *   handwriting pad): [flat] is laid on keys alone.
 */
data class KeyStyleOver(
    val across: Level? = null,
    val upDown: Level? = null,
    val radiusDp: Double? = null,
    val flat: Boolean = false,
) {
    /** A level of a setting, dp: upright and on its side. */
    data class Level(val portrait: Int, val landscape: Int)

    /** Whether it changes nothing (every setting following the theme). */
    val none: Boolean get() = across == null && upDown == null && radiusDp == null && !flat

    /** A key's [values] (the theme's cascade's) with these laid on, in the form [landscape] and [compact] say. */
    fun on(values: Map<String, Value>, landscape: Boolean, compact: Boolean): Map<String, Value> {
        if (none) return values
        val out = HashMap(values)
        if (across != null || upDown != null) {
            val own = (values[INSET] as? Value.OfInset)?.inset ?: Inset(0.0, 0.0, 0.0, 0.0)
            out[INSET] = Value.OfInset(inset(own, landscape, compact))
        }
        radiusDp?.let { out[RADIUS] = radius(it) }
        if (flat) out.putAll(FLAT)
        return out
    }

    private fun inset(own: Inset, landscape: Boolean, compact: Boolean): Inset {
        val h = across?.let { (if (landscape) it.landscape else it.portrait).toDouble() }
        val v = upDown?.let { (if (landscape) it.landscape else it.portrait).let { dp -> if (compact) Gap.compact(dp) else dp }.toDouble() }
            ?: return Inset(own.top, h ?: own.right, own.bottom, h ?: own.left)
        val half = (own.bottom - own.top) / 2
        val top = (v - half).coerceAtLeast(0.0)
        return Inset(top, h ?: own.right, (top + 2 * half).coerceAtLeast(0.0), h ?: own.left)
    }

    private companion object {
        const val INSET = "inset"
        const val RADIUS = "radius"
        const val CORNERS = 4

        fun radius(dp: Double) = Value.OfRadius(Radius(List(CORNERS) { dp }))

        // no face: every key alike
        val FLAT: Map<String, Value> = mapOf(
            "fill" to Value.OfFill(Fill.Solid(ColorExpr.Literal(Argb.TRANSPARENT))),
            "border" to Value.OfBorder(Border.NONE),
            "shadow" to Value.OfShadow(Shadow.NONE),
            "inner-shadow" to Value.OfShadow(Shadow.NONE),
            "depth" to Value.Number(0.0),
            "press-sink" to Value.Number(0.0),
        )
    }
}
