/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * How much smaller the keys' text is than its size in the layout: 紧凑's lower keys, the floating
 * keyboard's narrower ones. Both at once take the smaller, not the product: the two together
 * (0.77) left a letter 17.7 dp, too small to read at a glance (051-compact §1.3).
 */
object KeyTextScale {

    const val COMPACT = 0.9f

    /** The scale, [compact] or not, floating at [floating] (FloatingKeyboard.textScale) or docked (null). */
    fun of(compact: Boolean, floating: Float?): Float =
        minOf(if (compact) COMPACT else 1f, floating ?: 1f)

    /**
     * 大千's symbols, on top of [of]: the tallest text on the narrowest keys, cut off at 紧凑 turned
     * and on a small phone (360 × 740 dp), so smaller again there, as large as 注音九键's groups then.
     * The other keyboards' text fits as it is (051-compact, the user's 5th: only 注音's shrunk).
     */
    const val DACHEN_COMPACT = 0.9f

    fun dachen(compact: Boolean): Float = if (compact) DACHEN_COMPACT else 1f
}
