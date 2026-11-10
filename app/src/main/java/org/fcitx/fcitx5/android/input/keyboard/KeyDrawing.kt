/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * 在键上划: what a finger drawn across the keys does ([GlideInput]), one of four, stored by name: a word
 * glided across its keys (滑行输入), a stroke filtering pinyin's candidates (笔画筛选), a character
 * written on the keys (键盘手写, its recognizer the text pack's: InputMethodSettings says so while that is
 * missing), or nothing.
 */
enum class KeyDrawing(override val stringRes: Int) : ManagedPreferenceEnum {
    Glide(R.string.glide_typing),
    StrokeFilter(R.string.stroke_filter),
    Handwriting(R.string.key_drawing_handwriting),
    Off(R.string.swipe_off),
}
