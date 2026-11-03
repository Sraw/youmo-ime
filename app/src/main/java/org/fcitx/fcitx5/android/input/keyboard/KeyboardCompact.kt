/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * Whether the keyboard is 紧凑 as 键盘高度 is set now ([KeyboardHeightLevels], the level the settings
 * list shows): its keys' text smaller ([keyTextScale]), their marks in the top right only ([KeyMarks]).
 * Read as the keys are made; the service makes them anew when this changes.
 */
internal fun keyboardCompact(): Boolean {
    val keyboard = AppPrefs.getInstance().keyboard
    return KeyboardHeightLevels.isCompact(keyboard.keyboardHeightPercent.getValue(), keyboard.keyboardHeightPercentLandscape.getValue())
}
