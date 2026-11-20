/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots

/** Main thread alone. */
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
