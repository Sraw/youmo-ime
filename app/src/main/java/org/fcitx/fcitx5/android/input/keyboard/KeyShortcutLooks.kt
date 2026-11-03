/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand

/** How a [KeyCommand] looks: its name, and the icon drawn in a key's corner and the edit menu (the bar's where it has one). */
class KeyShortcutLooks private constructor(@StringRes val label: Int, @DrawableRes val icon: Int) {
    companion object {
        fun of(command: KeyCommand): KeyShortcutLooks = when (command) {
            KeyCommand.SelectAll -> KeyShortcutLooks(R.string.shortcut_select_all, R.drawable.ic_baseline_select_all_24)
            KeyCommand.Copy -> KeyShortcutLooks(R.string.shortcut_copy, R.drawable.ic_baseline_content_copy_24)
            KeyCommand.Cut -> KeyShortcutLooks(R.string.shortcut_cut, R.drawable.ic_baseline_content_cut_24)
            KeyCommand.Paste -> KeyShortcutLooks(R.string.shortcut_paste, R.drawable.ic_baseline_content_paste_24)
            KeyCommand.Undo -> KeyShortcutLooks(R.string.shortcut_undo, R.drawable.ic_baseline_undo_24)
            KeyCommand.Redo -> KeyShortcutLooks(R.string.shortcut_redo, R.drawable.ic_baseline_redo_24)
            KeyCommand.Clear -> KeyShortcutLooks(R.string.shortcut_clear, R.drawable.ic_baseline_delete_sweep_24)
            KeyCommand.Restore -> KeyShortcutLooks(R.string.shortcut_restore, R.drawable.ic_baseline_restore_24)
            KeyCommand.FieldStart -> KeyShortcutLooks(R.string.shortcut_field_start, R.drawable.ic_baseline_first_page_24)
            KeyCommand.FieldEnd -> KeyShortcutLooks(R.string.shortcut_field_end, R.drawable.ic_baseline_last_page_24)
            KeyCommand.EditMenu -> KeyShortcutLooks(R.string.shortcut_edit_menu, R.drawable.ic_cursor_move)
            KeyCommand.Clipboard -> KeyShortcutLooks(R.string.shortcut_clipboard, R.drawable.ic_clipboard)
            KeyCommand.Emoji -> KeyShortcutLooks(R.string.shortcut_emoji, R.drawable.ic_baseline_tag_faces_24)
            KeyCommand.Voice -> KeyShortcutLooks(R.string.shortcut_voice, R.drawable.ic_baseline_keyboard_voice_24)
            KeyCommand.TradSimp -> KeyShortcutLooks(R.string.shortcut_trad_simp, R.drawable.ic_fcitx_status_chttrans_trad)
            KeyCommand.ChooseKeyboard -> KeyShortcutLooks(R.string.shortcut_choose_keyboard, R.drawable.ic_baseline_keyboard_24)
            KeyCommand.HideKeyboard -> KeyShortcutLooks(R.string.shortcut_hide_keyboard, R.drawable.ic_baseline_keyboard_hide_24)
        }
    }
}
