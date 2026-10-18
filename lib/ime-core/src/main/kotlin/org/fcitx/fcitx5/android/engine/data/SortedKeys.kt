/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/**
 * The end of the run from [from] (before [to]) whose keys have [c] where [keyChar] reads them: the
 * run is sorted, and every key in it is long enough to have that place. The English decoders' and a
 * table glide's walk of their sorted keys as a trie.
 */
internal inline fun runEnd(from: Int, to: Int, c: Char, keyChar: (Int) -> Char): Int {
    var lo = from
    var hi = to
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (keyChar(mid) <= c) lo = mid + 1 else hi = mid
    }
    return lo
}
