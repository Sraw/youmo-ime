/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** The 快捷键标记位置 setting's five choices ([KeyMarks.ShortcutAt]), stored by name. */
enum class ShortcutMarkPosition(override val stringRes: Int, val at: KeyMarks.ShortcutAt) : ManagedPreferenceEnum {
    TopLeft(R.string.shortcut_mark_top_left, KeyMarks.ShortcutAt.TopLeft),
    BottomLeft(R.string.shortcut_mark_bottom_left, KeyMarks.ShortcutAt.BottomLeft),
    TopRight(R.string.shortcut_mark_top_right, KeyMarks.ShortcutAt.TopRight),
    BottomRight(R.string.shortcut_mark_bottom_right, KeyMarks.ShortcutAt.BottomRight),
    Bottom(R.string.shortcut_mark_bottom, KeyMarks.ShortcutAt.Bottom),
}
