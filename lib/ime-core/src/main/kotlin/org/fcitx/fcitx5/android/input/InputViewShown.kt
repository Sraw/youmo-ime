/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * From Android 11 hiding the keyboard tells its view GONE by hand and showing it tells nothing back; the window's own
 * VISIBLE comes only where it went away in between, so a quick hide and show (the app remade for dark mode) left it GONE.
 */
class InputViewShown {
    private var hidden = false

    fun hidden() {
        hidden = true
    }

    /** At a show: true where the view is to be told VISIBLE, hidden since the last show while its window [windowVisible] stayed. */
    fun shown(windowVisible: Boolean): Boolean = (hidden && windowVisible).also { hidden = false }
}
