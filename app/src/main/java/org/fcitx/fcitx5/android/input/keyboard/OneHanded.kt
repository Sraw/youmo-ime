/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** The 单手模式 setting: the whole keyboard at a side while the letters do not split ([SplitKeyboard.placement]). */
enum class OneHanded(override val stringRes: Int, val dock: SplitKeyboard.Dock) : ManagedPreferenceEnum {
    Off(R.string.one_handed_off, SplitKeyboard.Dock.FULL),
    Right(R.string.split_dock_right, SplitKeyboard.Dock.RIGHT),
    Left(R.string.split_dock_left, SplitKeyboard.Dock.LEFT),
}
