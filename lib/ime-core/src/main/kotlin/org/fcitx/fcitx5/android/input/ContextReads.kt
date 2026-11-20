/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** Only the last context read asked is made, so of a burst of cursor reports one waits on the editor; none from before [forget] (maybe a password field). */
class ContextReads {

    private var asked = 0

    fun ask(): Int = ++asked

    /** Still the last asked, in the field it was asked in. */
    fun isLatest(read: Int): Boolean = read == asked

    fun forget() {
        asked++
    }
}
