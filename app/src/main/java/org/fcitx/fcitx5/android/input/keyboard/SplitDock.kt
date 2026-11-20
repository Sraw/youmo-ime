/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard

enum class SplitDock(override val stringRes: Int, val dock: SplitKeyboard.Dock) : ManagedPreferenceEnum {
    Right(R.string.split_dock_right, SplitKeyboard.Dock.RIGHT),
    Left(R.string.split_dock_left, SplitKeyboard.Dock.LEFT),
    Full(R.string.split_dock_full, SplitKeyboard.Dock.FULL),
}
