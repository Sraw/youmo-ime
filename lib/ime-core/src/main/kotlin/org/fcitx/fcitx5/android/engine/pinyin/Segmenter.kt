/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** Turns what was typed into the syllables it may stand for, the same for every way of typing pinyin. */
fun interface Segmenter {
    fun segment(input: String): SyllableGraph

    /** Whether [c] is a key of this way of typing: what a keyboard hands to the engine. */
    fun reads(c: Char): Boolean = c in 'a'..'z'
}
