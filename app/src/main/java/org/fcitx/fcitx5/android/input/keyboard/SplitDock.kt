/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** The 分体时其他面板 setting: where [SplitKeyboard.Dock] puts the boards other than the letters. */
enum class SplitDock(override val stringRes: Int, val dock: SplitKeyboard.Dock) : ManagedPreferenceEnum {
    Right(R.string.split_dock_right, SplitKeyboard.Dock.RIGHT),
    Left(R.string.split_dock_left, SplitKeyboard.Dock.LEFT),
    Full(R.string.split_dock_full, SplitKeyboard.Dock.FULL),
}
