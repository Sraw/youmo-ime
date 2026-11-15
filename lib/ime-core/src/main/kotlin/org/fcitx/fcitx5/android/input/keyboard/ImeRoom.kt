/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The room the docked keyboard and the app share, px: the input method's window as the system makes it, less what the
 * input view keeps under the keyboard for the navigation bar (061-landscape-height §5.2). One sum for the keyboard's
 * first frame (before its view is measured), the settings' preview and the tests, so they cannot disagree; once
 * laid out the input view's own height less that room is the real one (InputView), which this predicts.
 */
object ImeRoom {

    /**
     * Out of the display's whole height as held, [displayHeight] (never a height that left a bar out already: below
     * API 35 the screen's height has, and taking the bar again takes it twice). The window is the display less the
     * status bar where it shows ([statusShown]: an app full screen hides it) and, below API 35 ([api35] false) with
     * the navigation bar's background not the keyboard's ([fullBackground] false: 无, 仅颜色), less the bar under the
     * input method, [imeNav]: the system fits the window above it (measured on API 34: the input method's bar, 48 dp
     * with gestures, not the 24 dp strip apps are told of). The input view keeps [imeNav] itself where the keyboard
     * draws the bar's background (全部), unless 贴底 ([flush]); from API 35 the window is edge to edge either way.
     * The app never reaches [api35] with [fullBackground] false: from API 35 the background setting is removed and
     * greyed out (ThemePrefs.navbarBackground), so it is 全部; the sum keeps the case whole all the same.
     */
    fun available(
        displayHeight: Int,
        statusBar: Int,
        imeNav: Int,
        api35: Boolean,
        fullBackground: Boolean,
        flush: Boolean,
        statusShown: Boolean = true,
    ): Int {
        val window = displayHeight - (if (statusShown) statusBar else 0) - (if (!api35 && !fullBackground) imeNav else 0)
        val kept = if (fullBackground && !flush) imeNav else 0
        return window - kept
    }
}
