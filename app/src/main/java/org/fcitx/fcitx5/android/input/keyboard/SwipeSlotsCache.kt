/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
