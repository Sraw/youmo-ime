/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots

/**
 * The [SwipeSlots] the setting holds now, parsed again only when it changed. Read on the main
 * thread alone: the keys' swipes and marks, and the settings page.
 */
object SwipeSlotsCache {

    private var parsedText: String? = null
    private var parsed = SwipeSlots.Default

    val current: SwipeSlots
        get() {
            val now = AppPrefs.getInstance().internal.keySwipes.getValue()
            if (now != parsedText) {
                parsedText = now
                parsed = if (now.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(now)
            }
            return parsed
        }
}

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
