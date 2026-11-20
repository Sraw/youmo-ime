/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

/**
 * How much smaller the keys' text is than its size in the layout: 紧凑's lower keys, the floating
 * keyboard's narrower ones. Both at once take the smaller, not the product: the two together
 * (0.77) left a letter 17.7 dp, too small to read at a glance.
 */
object KeyTextScale {

    const val COMPACT = 0.9

    /** The scale, [compact] or not, floating at [floating] (FloatingKeyboard.textScale) or docked (null). */
    fun of(compact: Boolean, floating: Double?): Double =
        minOf(if (compact) COMPACT else 1.0, floating ?: 1.0)
}
