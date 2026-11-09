/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * The app's panels as the theme in use has them (060-render-p3 T7b, [PanelLooks]): the clipboard's
 * entries, the picker's categories, the editing panel's buttons, the voice mask. A theme that sets none
 * of it, a theme 3.0's among them, has the engine's default rules: the look the app worked out itself
 * before. A look that made no theme (SceneThemes logs it; no key is drawn either) has that look of
 * before worked out here, of its keys and colours.
 *
 * The scenes are the window's ([SceneWindow.scenes]): the screen's and the settings', not floating nor
 * the board's place (a panel is over every board), nor what is typed.
 */
object ScenePanels {

    private fun looks(theme: Theme): PanelLooks? =
        SceneThemes.of(ThemeManager.shownLook(theme), theme.name, theme.isDark)?.let { PanelLooks(SceneThemes.cascades(it)) }

    private fun scenes(context: Context, theme: Theme) = SceneWindow.scenes(context, theme)

    private fun keyRadiusDp(theme: Theme) = ThemeManager.shownLook(theme).keys.radiusDp.toDouble()

    /** A clipboard entry's card. */
    fun clipboard(context: Context, theme: Theme): PanelLooks.Clipboard =
        looks(theme)?.clipboard(scenes(context, theme))
            ?: PanelLooks.Clipboard(Argb(theme.clipboardEntryColor), keyRadiusDp(theme) / 2, false, Argb(theme.keyTextColor))

    /** The picker's, its categories the symbols' column down its [side] or the emoji's tabs. */
    fun picker(context: Context, theme: Theme, side: Boolean): PanelLooks.Picker =
        looks(theme)?.picker(scenes(context, theme), side) ?: Argb(theme.keyTextColor).let { text ->
            val fade = if (side) PanelLooks.SIDE_FADE else PanelLooks.TAB_FADE
            PanelLooks.Picker(
                fill = null,
                categories = PanelLooks.Categories(Argb(theme.barColor).takeIf { side }, Argb((text.value and RGB) or (fade shl ALPHA))),
                selected = PanelLooks.Categories(Argb(theme.keyboardColor).takeIf { side }, text),
            )
        }

    /** The editing panel's buttons. */
    fun editing(context: Context, theme: Theme): PanelLooks.Editing =
        looks(theme)?.editing(scenes(context, theme)) ?: PanelLooks.Editing(null, null, false, keyRadiusDp(theme))

    /**
     * An editing button's corners, px: the theme's ([PanelLooks.Editing.radiusDp]; [PanelLooks.Editing.round],
     * a pill), else the app's of before, twice the keys' radius in dp as as many px (ThemeColors.buttonRadius's:
     * kept as it looked).
     */
    fun editingRadius(context: Context, look: PanelLooks.Editing): Float = when {
        look.round -> PILL
        else -> look.radiusDp?.let { px(context, it) } ?: (look.keyRadiusDp * 2).toFloat()
    }

    /** A clipboard entry's corners, px. */
    fun clipboardRadius(context: Context, look: PanelLooks.Clipboard): Float = if (look.round) PILL else px(context, look.radiusDp)

    // splitties' dp of a Float: not truncated
    private fun px(context: Context, dp: Double) = (dp * context.resources.displayMetrics.density).toFloat()

    /** The voice mask. */
    fun voice(context: Context, theme: Theme): PanelLooks.Voice =
        looks(theme)?.voice(scenes(context, theme)) ?: Argb(theme.accentKeyBackgroundColor).let { accent ->
            PanelLooks.Voice(PanelLooks.mask(Argb(theme.keyboardColor)), null, Argb((accent.value and RGB) or (PanelLooks.MIC_ALPHA shl ALPHA)), accent)
        }

    private const val RGB = 0xFFFFFF
    private const val ALPHA = 24

    // a pill's corners: GradientDrawable draws none rounder than half its shorter side (API 29's too)
    private const val PILL = 10_000f
}
