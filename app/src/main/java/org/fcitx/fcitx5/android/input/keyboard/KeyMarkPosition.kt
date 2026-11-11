/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * The choices of 标点位置 and 快捷键图标位置 ([KeyMarks.At]), one list in one order and wording for
 * both, stored by name: 标点位置's older TopRight, Bottom and None read as they were.
 */
enum class KeyMarkPosition(override val stringRes: Int, val at: KeyMarks.At) : ManagedPreferenceEnum {
    TopRight(R.string.mark_at_top_right, KeyMarks.At.TopRight),
    BottomRight(R.string.mark_at_bottom_right, KeyMarks.At.BottomRight),
    TopLeft(R.string.mark_at_top_left, KeyMarks.At.TopLeft),
    BottomLeft(R.string.mark_at_bottom_left, KeyMarks.At.BottomLeft),
    Bottom(R.string.mark_at_bottom, KeyMarks.At.Bottom),
    None(R.string.mark_at_none, KeyMarks.At.None);

    companion object {
        fun of(at: KeyMarks.At) = entries.first { it.at == at }
    }
}

/** 标点位置 and 快捷键图标位置 as stored, apart ([KeyMarks.settled]): what every key and preview draws by. */
