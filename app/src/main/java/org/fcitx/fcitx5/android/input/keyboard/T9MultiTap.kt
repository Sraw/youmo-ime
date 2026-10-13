/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** 连按 on the nine keys, off or how soon a key pressed again is its next letter ([MultiTap]). */
enum class T9MultiTap(override val stringRes: Int, val timeout: Long) : ManagedPreferenceEnum {
    Off(R.string.t9_multi_tap_off, 0),
    Fast(R.string.t9_multi_tap_fast, 400),
    Medium(R.string.t9_multi_tap_medium, 600),
    Slow(R.string.t9_multi_tap_slow, 900)
}
