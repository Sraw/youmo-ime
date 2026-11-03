/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

/** A [ToolbarButton]'s icon and its name, the name on the settings page too. */
data class ToolbarButtonLooks(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    companion object {
        fun of(button: ToolbarButton) = when (button) {
            ToolbarButton.Undo -> ToolbarButtonLooks(R.drawable.ic_baseline_undo_24, R.string.undo)
            ToolbarButton.Redo -> ToolbarButtonLooks(R.drawable.ic_baseline_redo_24, R.string.redo)
            ToolbarButton.CursorMove -> ToolbarButtonLooks(R.drawable.ic_cursor_move, R.string.text_editing)
            ToolbarButton.Clipboard -> ToolbarButtonLooks(R.drawable.ic_clipboard, R.string.clipboard)
            ToolbarButton.More -> ToolbarButtonLooks(R.drawable.ic_baseline_more_horiz_24, R.string.status_area)
            ToolbarButton.Emoji -> ToolbarButtonLooks(R.drawable.ic_baseline_tag_faces_24, R.string.a11y_key_emoji)
            ToolbarButton.Voice -> ToolbarButtonLooks(R.drawable.ic_baseline_keyboard_voice_24, R.string.voice_input)
            ToolbarButton.Hide -> ToolbarButtonLooks(R.drawable.ic_baseline_arrow_drop_down_24, R.string.hide_keyboard)
            ToolbarButton.TradSimp -> ToolbarButtonLooks(R.drawable.ic_fcitx_status_chttrans_simp, R.string.toolbar_trad_simp)
            ToolbarButton.Floating -> ToolbarButtonLooks(R.drawable.ic_baseline_open_with_24, R.string.floating_keyboard)
            ToolbarButton.OneHanded -> ToolbarButtonLooks(R.drawable.ic_baseline_pan_tool_24, R.string.one_handed)
            ToolbarButton.Habits -> ToolbarButtonLooks(R.drawable.ic_view_private, R.string.learning_habits_off)
            ToolbarButton.ClearRecent -> ToolbarButtonLooks(R.drawable.ic_baseline_delete_sweep_24, R.string.learning_clear_recent)
            ToolbarButton.Theme -> ToolbarButtonLooks(R.drawable.ic_baseline_palette_24, R.string.theme)
            ToolbarButton.InputMethodSettings -> ToolbarButtonLooks(R.drawable.ic_baseline_language_24, R.string.input_method_options)
            ToolbarButton.Keyboard -> ToolbarButtonLooks(R.drawable.ic_baseline_dialpad_24, R.string.status_keyboard)
            ToolbarButton.QuickPhrase -> ToolbarButtonLooks(R.drawable.ic_quick_phrase, R.string.quick_phrases)
        }
    }
}
