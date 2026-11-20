/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Room the docked keyboard and app share: the IME window less the nav bar room the input view keeps. One sum for the first frame, the settings preview and tests. */
object ImeRoom {

    /** [displayHeight] the whole display (below API 35 the screen height already left a bar out); the input method's bar [imeNav] (48 dp with gestures, not apps' 24 dp) unless [flush]. */
    fun available(displayHeight: Int, statusBar: Int, imeNav: Int, flush: Boolean, statusShown: Boolean = true): Int {
        val window = displayHeight - (if (statusShown) statusBar else 0)
        val kept = if (!flush) imeNav else 0
        return window - kept
    }
}
