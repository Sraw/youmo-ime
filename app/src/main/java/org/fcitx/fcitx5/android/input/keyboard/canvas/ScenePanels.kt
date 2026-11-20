/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.HandwritingMode
import org.fcitx.fcitx5.android.input.keyboard.KeyInk
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.scene.theme.Argb

/** A look that made no theme has the look of before worked out here. Not floating nor the board's place: a panel is over every board. */
object ScenePanels {

    private fun looks(theme: Theme): PanelLooks? =
        SceneThemes.of(theme, ThemeManager.shownLook(theme))?.let { PanelLooks(SceneThemes.cascades(it)) }

    private fun scenes(context: Context, theme: Theme) = SceneWindow.scenes(context, theme)

    private fun keyRadiusDp(theme: Theme) = ThemeManager.shownLook(theme).keys.radiusDp.toDouble()

    fun clipboard(context: Context, theme: Theme): PanelLooks.Clipboard =
        looks(theme)?.clipboard(scenes(context, theme))
            ?: PanelLooks.Clipboard(Argb(theme.clipboardEntryColor), keyRadiusDp(theme) / 2, false, Argb(theme.keyTextColor))

    fun picker(context: Context, theme: Theme, side: Boolean): PanelLooks.Picker =
        looks(theme)?.picker(scenes(context, theme), side) ?: Argb(theme.keyTextColor).let { text ->
            val fade = if (side) PanelLooks.SIDE_FADE else PanelLooks.TAB_FADE
            PanelLooks.Picker(
                fill = null,
                categories = PanelLooks.Categories(Argb(theme.barColor).takeIf { side }, Argb((text.value and RGB) or (fade shl ALPHA))),
                // opaque, as PanelLooks' default (the app's alpha(1f) of before)
                selected = PanelLooks.Categories(Argb(theme.keyboardColor).takeIf { side }, Argb((text.value and RGB) or (OPAQUE shl ALPHA))),
            )
        }

    fun editing(context: Context, theme: Theme): PanelLooks.Editing =
        looks(theme)?.editing(scenes(context, theme)) ?: PanelLooks.Editing(null, null, false, keyRadiusDp(theme))

    /** Px; else the app's of before, twice the keys' radius in dp as as many px (kept as it looked). */
    fun editingRadius(context: Context, look: PanelLooks.Editing): Float = when {
        look.round -> PILL
        else -> look.radiusDp?.let { px(context, it) } ?: (look.keyRadiusDp * 2).toFloat()
    }

    fun clipboardRadius(context: Context, look: PanelLooks.Clipboard): Float = if (look.round) PILL else px(context, look.radiusDp)

    // splitties' dp of a Float: not truncated
    private fun px(context: Context, dp: Double) = (dp * context.resources.displayMetrics.density).toFloat()

    fun voice(context: Context, theme: Theme): PanelLooks.Voice =
        looks(theme)?.voice(scenes(context, theme)) ?: Argb(theme.accentKeyBackgroundColor).let { accent ->
            PanelLooks.Voice(PanelLooks.mask(Argb(theme.keyboardColor)), null, Argb((accent.value and RGB) or (PanelLooks.MIC_ALPHA shl ALPHA)), accent)
        }

    /** Where the theme's `handwriting/pad` ink shows on the letter keys, else the letters' own colour. */
    fun ink(context: Context, theme: Theme): Pair<Int, Float> {
        val pad = looks(theme)?.pad(scenes(context, theme))
        val color = HandwritingMode.inkColor(pad?.color?.value, theme.keyTextColor, theme.keyBackgroundColor)
        return color to (pad?.widthDp?.toFloat() ?: KeyInk.INK_DP)
    }

    private const val RGB = 0xFFFFFF
    private const val ALPHA = 24
    private const val OPAQUE = 0xFF

    // GradientDrawable draws none rounder than half its shorter side (API 29's too)
    private const val PILL = 10_000f
}
