/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/** End of the run in [from, to) whose keys have [c] at [keyChar]; keys sorted and long enough. */
internal inline fun runEnd(from: Int, to: Int, c: Char, keyChar: (Int) -> Char): Int {
    var lo = from
    var hi = to
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (keyChar(mid) <= c) lo = mid + 1 else hi = mid
    }
    return lo
}
