/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.scene.rules.KeyMarks

/**
 * The choices of 上滑图标位置 and 下滑图标位置 ([KeyMarks.At]), one list in one order and wording for
 * both, stored by name: the older TopRight, Bottom and None read as they were.
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

// the swipe settings as they are now, which the keys' swipes and marks read

/** 上滑 and 下滑 on or off, as the settings are now. */
internal fun swipeWays(): KeySwipe.Ways {
    val k = AppPrefs.getInstance().keyboard
    return KeySwipe.Ways(k.swipeUp.getValue(), k.swipeDown.getValue())
}

/** 上滑图标位置 and 下滑图标位置 as read: apart ([KeyMarks.settled], the up one moved off the down one's). */
internal fun swipeSpots(): KeyMarks.Spots {
    val k = AppPrefs.getInstance().keyboard
    return KeyMarks.settled(k.swipeUpPosition.getValue().at, k.swipeDownPosition.getValue().at)
}

/**
 * Whether a long press's first can be drawn at all (the corners given the scene, told a screen reader): a
 * way on, its place not 无, the first there (the orientation's way, or a key set so).
 */
internal fun firstShown(): Boolean {
    val ways = swipeWays()
    val spots = swipeSpots()
    val slots = SwipeSlotsCache.current
    fun firstThere(up: Boolean) = (slots.orientation == SwipeOrientation.FirstUp) == up ||
        slots.keys.any { slots.resolve(it, up) == SwipeSlot.First }
    return (ways.up && spots.symbol != KeyMarks.At.None && firstThere(true)) ||
        (ways.down && spots.shortcut != KeyMarks.At.None && firstThere(false))
}
