/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * The [PopupOverrides] that [text] holds now, parsed again only when it changed. Not thread-safe:
 * its readers, the long press and a letter's swipe and corner, all run on the main thread.
 */
class PopupOverridesCache(private val text: () -> String) {

    private var parsedText = ""
    private var parsed = PopupOverrides.Empty

    val current: PopupOverrides
        get() {
            val now = text()
            if (now != parsedText) {
                parsedText = now
                parsed = PopupOverrides.parse(now)
            }
            return parsed
        }

    companion object {
        // one copy for PopupComponent and TextKeyboard, so the long press and the swipe cannot drift apart
        val user = PopupOverridesCache { AppPrefs.getInstance().internal.popupOverrides.getValue() }

        // the other keyboards' keys', by the key's name (`t9/2`): their long press and its first's swipe (060-swipe-slots)
        val byName = PopupOverridesCache { AppPrefs.getInstance().internal.keyPopups.getValue() }
    }
}
