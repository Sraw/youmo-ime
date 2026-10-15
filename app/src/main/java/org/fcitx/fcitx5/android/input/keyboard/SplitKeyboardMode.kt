/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** The 分体键盘 setting, as [SplitKeyboard] decides by it. */
enum class SplitKeyboardMode(override val stringRes: Int, val mode: SplitKeyboard.Mode) : ManagedPreferenceEnum {
    Auto(R.string.split_keyboard_auto, SplitKeyboard.Mode.AUTO),
    Always(R.string.split_keyboard_always, SplitKeyboard.Mode.ALWAYS),
    Off(R.string.split_keyboard_off, SplitKeyboard.Mode.OFF),
}
