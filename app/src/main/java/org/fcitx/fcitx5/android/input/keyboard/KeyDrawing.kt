/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** Stored by name. 0.6.0's fourth, 键盘手写, is the toolbar's 手写 (AppPrefs.migrateKeyHandwriting). */
enum class KeyDrawing(override val stringRes: Int) : ManagedPreferenceEnum {
    Glide(R.string.glide_typing),
    StrokeFilter(R.string.stroke_filter),
    Off(R.string.swipe_off),
}

/** Short of it, the key it came down on is tapped. In dp, stored by name; glide typing does not read it. */
enum class StrokeLift(override val stringRes: Int, val dp: Float) : ManagedPreferenceEnum {
    High(R.string.stroke_lift_high, 20f),
    Medium(R.string.stroke_lift_medium, 24f),
    Low(R.string.stroke_lift_low, 28f),
}
