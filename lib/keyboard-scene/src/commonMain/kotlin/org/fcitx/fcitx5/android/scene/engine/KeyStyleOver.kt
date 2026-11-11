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
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Shadow
import org.fcitx.fcitx5.android.scene.theme.TextLook
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
 * - [flat] (无边框; the keys are then in `@flat` too, [Env.flat]): no face, border, shadow, depth or sink; the
 *   space a bar of `spaceBar`, Return a circle of `accentKey`, as a flat 3.0 theme's (LookTheme). Not on a key
 *   whose face carries what it shows ([keeps]): that one keeps its face as the theme drew it, as `face="keep"`.
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

    /**
     * A key's [values] (the theme's cascade's) with these laid on: [kind] `space` or `enter` (a flat bar, a
     * circle), else null; [kept] its face kept as the theme drew it ([keeps]).
     */
    fun on(values: Map<String, Value>, kind: String?, kept: Boolean, landscape: Boolean, compact: Boolean): Map<String, Value> {
        if (none) return values
        val out = HashMap(values)
        if (across != null || upDown != null) {
            val own = (values[INSET] as? Value.OfInset)?.inset ?: Inset(0.0, 0.0, 0.0, 0.0)
            out[INSET] = Value.OfInset(inset(own, landscape, compact))
        }
        if (flat && !kept) out.putAll(flat(kind)) else radiusDp?.let { out[RADIUS] = radius(it) }
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

    companion object {
        /**
         * Whether a key keeps its face under [flat]: the face an image in any of [looks] (its states, pressed and
         * not), or what it shows (its words, its icon) hidden or transparent in any of [parts], as a converted
         * skin's keys whose picture carries the letter: flat would leave such a key blank.
         */
        fun keeps(looks: List<KeyLook>, parts: List<TextLook>): Boolean =
            looks.any { it.fill is Fill.Image } || parts.any { it.hidden || it.color.alpha == 0 }

        private const val INSET = "inset"
        private const val RADIUS = "radius"
        private const val FILL = "fill"
        private const val SHAPE = "shape"
        private const val CORNERS = 4
        private const val FLAT_SPACE_RADIUS_DP = 3.0

        private fun radius(dp: Double) = Value.OfRadius(Radius(List(CORNERS) { dp }))

        private fun solid(color: String) = Value.OfFill(Fill.Solid(ColorExpr.Ref(color)))

        // as LookTheme's flat keys: no face; the space a bar, Return a circle; no cap, sink or shadow on any
        private fun flat(kind: String?): Map<String, Value> = when (kind) {
            "space" -> mapOf(FILL to solid("spaceBar"), SHAPE to Value.Word("bar"), RADIUS to radius(FLAT_SPACE_RADIUS_DP))
            "enter" -> mapOf(FILL to solid("accentKey"), SHAPE to Value.Word("circle"))
            else -> mapOf(FILL to Value.OfFill(Fill.Solid(ColorExpr.Literal(Argb.TRANSPARENT))), SHAPE to Value.Word("rect"), RADIUS to radius(0.0))
        } + mapOf(
            "border" to Value.OfBorder(Border.NONE),
            "shadow" to Value.OfShadow(Shadow.NONE),
            "inner-shadow" to Value.OfShadow(Shadow.NONE),
            "depth" to Value.Number(0.0),
            "press-sink" to Value.Number(0.0),
        )
    }

}
