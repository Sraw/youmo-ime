/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * 在键上划: what a finger drawn across the keys does ([GlideInput]), one of three, stored by name: a word
 * glided across its keys (滑行输入), a stroke filtering pinyin's candidates (笔画筛选), or nothing. 0.6.0's
 * fourth, 键盘手写, is the toolbar's 手写 (AppPrefs.migrateKeyHandwriting), which writes over all of them.
 */
enum class KeyDrawing(override val stringRes: Int) : ManagedPreferenceEnum {
    Glide(R.string.glide_typing),
    StrokeFilter(R.string.stroke_filter),
    Off(R.string.swipe_off),
}

/**
 * 笔画筛选灵敏度: how far from where it went down a stroke drawn must have gone, at its lift, to be one
 * ([GlideTouch]'s lift distance); short of it, the key it came down on is tapped. In dp, stored by name;
 * glide typing does not read it. Medium the default, from synthetic taps (dev/design/061-swipe-eval.md §一).
 */
enum class StrokeLift(override val stringRes: Int, val dp: Float) : ManagedPreferenceEnum {
    High(R.string.stroke_lift_high, 20f),
    Medium(R.string.stroke_lift_medium, 24f),
    Low(R.string.stroke_lift_low, 28f),
}
