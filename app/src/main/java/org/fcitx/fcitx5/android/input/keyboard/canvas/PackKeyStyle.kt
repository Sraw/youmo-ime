/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Shadow
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.Value

/**
 * The key style settings (横向间距, 纵向间距, 按键圆角, 按键边框) over a 4.0 pack's keys, as over a 3.0 theme's
 * (KeyStyleChoice.applyTo): what the pack sets is its default only (the owner, 0.6.0-beta2: 国风·天青 drew its
 * `inset="5dp 3dp"` whatever was set). A setting at 跟随主题 leaves the pack's as it is.
 *
 * For every key selector of the pack that sets what a setting is over, and the plain `key`, a style after all
 * the pack's in each form (upright, on its side, 紧凑, both; and `@flat` each where 无边框 is set: SceneWindow puts
 * the keys in `@flat` then) with the form's scenes added. Its values are the pack's own cascade's for that
 * selector in that form, the set ones replaced: so in every form it outranks the pack's style that won there (the
 * same selector, a scene more or as many, and later; the added in the order of their selectors' weight), and what
 * is not set is what the pack drew there, whatever order the pack wrote its styles in.
 *
 * - 纵向间距 is the mean of the top and bottom inset, the pack's bottom-minus-top kept (机械键帽's room for its
 *   depth); at 紧凑 the level shrunk ([Gap.compact]) as a 3.0 theme's is.
 * - 无边框: no face, border, shadow or depth; the space a bar of `spaceBar`, Return a circle of `accentKey`, as a
 *   flat 3.0 theme's (LookTheme). 有边框 over a pack is its own faces (it is not in `@flat`): nothing added.
 */
object PackKeyStyle {

    fun over(theme: Theme, choice: KeyStyleChoice): Theme {
        val flat = choice.blocks == false
        val asked = buildSet {
            if (choice.hGap != null || choice.vGap != null) add(INSET)
            if (choice.radiusDp != null) add(RADIUS)
            if (flat) addAll(FACE)
        }
        if (asked.isEmpty()) return theme
        val selectors = (
            listOf(Selector(emptySet(), KEY)) + theme.styles.flatMap { s ->
                if (s.values.keys.none { it in asked }) emptyList() else s.selectors.filter { it.widget == KEY && it.part == null }
            }
            ).distinct().sortedBy { it.specificity }
        val forms = if (flat) FORMS + FORMS.map { it + FLAT } else FORMS
        val cascades = Cascades(theme)
        var order = (theme.styles.maxOfOrNull { it.order } ?: 0) + 1
        val added = selectors.flatMap { sel ->
            forms.mapNotNull { form ->
                val scenes = sel.scenes + form
                val look = KeyLook(cascades.of(scenes).of(target(sel)), theme)
                values(look, choice, scenes, kind(sel)).takeIf { it.isNotEmpty() }?.let { Style(listOf(sel.copy(scenes = scenes)), it, order++, 0) }
            }
        }
        return theme.withStyles(theme.styles + added)
    }

    private fun values(look: KeyLook, choice: KeyStyleChoice, scenes: Set<String>, kind: String?): Map<String, Value> = buildMap {
        val landscape = LANDSCAPE in scenes
        if (choice.hGap != null || choice.vGap != null) {
            put(INSET, Value.OfInset(inset(look.inset, choice.hGap, choice.vGap, landscape, COMPACT in scenes)))
        }
        if (FLAT in scenes) {
            putAll(flat(kind))
        } else {
            choice.radiusDp?.let { put(RADIUS, radius(it.toDouble())) }
        }
    }

    // [inset] with the set directions the settings', in the form [landscape] and [compact] say
    private fun inset(inset: Inset, h: KeyStyleChoice.Spacing?, v: KeyStyleChoice.Spacing?, landscape: Boolean, compact: Boolean): Inset {
        val across = h?.let { (if (landscape) it.landscape else it.portrait).toDouble() }
        val upDown = v?.let { (if (landscape) it.landscape else it.portrait).let { dp -> if (compact) Gap.compact(dp) else dp }.toDouble() }
            ?: return Inset(inset.top, across ?: inset.right, inset.bottom, across ?: inset.left)
        // the pack's bottom-minus-top about the set level, none above or below the cell's edge
        val half = (inset.bottom - inset.top) / 2
        val top = (upDown - half).coerceAtLeast(0.0)
        return Inset(top, across ?: inset.right, (top + 2 * half).coerceAtLeast(0.0), across ?: inset.left)
    }

    // as LookTheme's flat keys: no face; the space a bar, Return a circle
    private fun flat(kind: String?): Map<String, Value> = when (kind) {
        SPACE -> mapOf(FILL to solid("spaceBar"), "shape" to Value.Word("bar"), RADIUS to radius(FLAT_SPACE_RADIUS_DP))
        ENTER -> mapOf(FILL to solid("accentKey"), "shape" to Value.Word("circle"))
        else -> mapOf(FILL to Value.OfFill(Fill.Solid(ColorExpr.Literal(Argb.TRANSPARENT))), RADIUS to radius(0.0))
    } + mapOf(
        "border" to Value.OfBorder(Border.NONE),
        "shadow" to Value.OfShadow(Shadow.NONE),
        "inner-shadow" to Value.OfShadow(Shadow.NONE),
        "depth" to Value.Number(0.0),
    )

    private fun solid(color: String) = Value.OfFill(Fill.Solid(ColorExpr.Ref(color)))

    private fun radius(dp: Double) = Value.OfRadius(Radius(List(CORNERS) { dp }))

    // what [sel] selects, as a key: its pack's cascade there is what the pack draws on such a key
    private fun target(sel: Selector) =
        Selector.Target(KEY, sel.board, sel.key, sel.starRole ?: sel.roles.firstOrNull(), sel.classes, sel.states)

    // the space or Return [sel] selects, by its class or its role; null for any other
    private fun kind(sel: Selector): String? =
        (sel.classes + setOfNotNull(sel.starRole) + sel.roles).firstOrNull { it == SPACE || it == ENTER }

    private const val KEY = "key"
    private const val INSET = "inset"
    private const val RADIUS = "radius"
    private const val FILL = "fill"
    private const val LANDSCAPE = "landscape"
    private const val COMPACT = "compact"
    private const val FLAT = "flat"
    private const val SPACE = "space"
    private const val ENTER = "enter"
    private const val CORNERS = 4
    private const val FLAT_SPACE_RADIUS_DP = 3.0

    // what 无边框 sets: the face's
    private val FACE = setOf(FILL, RADIUS, "border", "shadow", "inner-shadow", "depth", "shape")
    private val FORMS = listOf(emptySet(), setOf(LANDSCAPE), setOf(COMPACT), setOf(LANDSCAPE, COMPACT))
}
