/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/** A [ToolbarButton]'s icon (a theme's by its name, else the app's: [BarIcons]) and its name, the name on the settings page too. */
data class ToolbarButtonLooks(val icon: BarIcons.Icon, @param:StringRes val label: Int) {
    companion object {
        fun of(button: ToolbarButton) = ToolbarButtonLooks(BarIcons.of(button), label(button))

        private fun label(button: ToolbarButton) = when (button) {
            ToolbarButton.Undo -> R.string.undo
            ToolbarButton.Redo -> R.string.redo
            ToolbarButton.CursorMove -> R.string.text_editing
            ToolbarButton.Clipboard -> R.string.clipboard
            ToolbarButton.More -> R.string.status_area
            ToolbarButton.Emoji -> R.string.a11y_key_emoji
            ToolbarButton.Voice -> R.string.voice_input
            ToolbarButton.Hide -> R.string.hide_keyboard
            ToolbarButton.TradSimp -> R.string.toolbar_trad_simp
            ToolbarButton.Floating -> R.string.floating_keyboard
            ToolbarButton.OneHanded -> R.string.one_handed
            ToolbarButton.Habits -> R.string.learning_habits_off
            ToolbarButton.ClearRecent -> R.string.learning_clear_recent
            ToolbarButton.Theme -> R.string.theme
            ToolbarButton.InputMethodSettings -> R.string.input_method_options
            ToolbarButton.Keyboard -> R.string.status_keyboard
            ToolbarButton.QuickPhrase -> R.string.quick_phrases
            ToolbarButton.Handwriting -> R.string.handwriting
        }

        /** The app's image of a place of the bar or a panel ([BarIcons.Fallback]): drawn where the theme has none of its name. */
        @Suppress("CyclomaticComplexMethod")
        @DrawableRes
        fun drawable(fallback: BarIcons.Fallback): Int = when (fallback) {
            BarIcons.Fallback.ExpandMore -> R.drawable.ic_baseline_expand_more_24
            BarIcons.Fallback.ExpandLess -> R.drawable.ic_baseline_expand_less_24
            BarIcons.Fallback.Undo -> R.drawable.ic_baseline_undo_24
            BarIcons.Fallback.Redo -> R.drawable.ic_baseline_redo_24
            BarIcons.Fallback.CursorMove -> R.drawable.ic_cursor_move
            BarIcons.Fallback.Clipboard -> R.drawable.ic_clipboard
            BarIcons.Fallback.MoreHoriz -> R.drawable.ic_baseline_more_horiz_24
            BarIcons.Fallback.TagFaces -> R.drawable.ic_baseline_tag_faces_24
            BarIcons.Fallback.Voice -> R.drawable.ic_baseline_keyboard_voice_24
            BarIcons.Fallback.ArrowDropDown -> R.drawable.ic_baseline_arrow_drop_down_24
            BarIcons.Fallback.ChttransTrad -> R.drawable.ic_fcitx_status_chttrans_trad
            BarIcons.Fallback.ChttransSimp -> R.drawable.ic_fcitx_status_chttrans_simp
            BarIcons.Fallback.OpenWith -> R.drawable.ic_baseline_open_with_24
            BarIcons.Fallback.PanTool -> R.drawable.ic_baseline_pan_tool_24
            BarIcons.Fallback.ViewPrivate -> R.drawable.ic_view_private
            BarIcons.Fallback.DeleteSweep -> R.drawable.ic_baseline_delete_sweep_24
            BarIcons.Fallback.Palette -> R.drawable.ic_baseline_palette_24
            BarIcons.Fallback.Language -> R.drawable.ic_baseline_language_24
            BarIcons.Fallback.Dialpad -> R.drawable.ic_baseline_dialpad_24
            BarIcons.Fallback.Keyboard -> R.drawable.ic_baseline_keyboard_24
            BarIcons.Fallback.QuickPhrase -> R.drawable.ic_quick_phrase
            BarIcons.Fallback.Handwriting -> R.drawable.ic_handwriting
            BarIcons.Fallback.Close -> R.drawable.ic_baseline_close_24
            BarIcons.Fallback.OpenInFull -> R.drawable.ic_baseline_open_in_full_24
            BarIcons.Fallback.PuncActive -> R.drawable.ic_fcitx_status_punc_active
            BarIcons.Fallback.PuncInactive -> R.drawable.ic_fcitx_status_punc_inactive
            BarIcons.Fallback.FullwidthActive -> R.drawable.ic_fcitx_status_fullwidth_active
            BarIcons.Fallback.FullwidthInactive -> R.drawable.ic_fcitx_status_fullwidth_inactive
            BarIcons.Fallback.PredictionActive -> R.drawable.ic_fcitx_status_prediction_active
            BarIcons.Fallback.PredictionInactive -> R.drawable.ic_fcitx_status_prediction_inactive
            BarIcons.Fallback.Settings -> R.drawable.ic_baseline_settings_24
            BarIcons.Fallback.Backspace -> R.drawable.ic_baseline_backspace_24
            BarIcons.Fallback.ArrowPrev -> R.drawable.ic_baseline_arrow_prev_24
            BarIcons.Fallback.ArrowNext -> R.drawable.ic_baseline_arrow_next_24
            BarIcons.Fallback.AspectRatio -> R.drawable.ic_baseline_aspect_ratio_24
        }
    }
}
