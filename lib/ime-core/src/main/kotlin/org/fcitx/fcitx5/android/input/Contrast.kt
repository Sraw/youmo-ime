/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2 contrast (the first colour drawn over the second, the second opaque), and keeping 撤销's accent legible on its popup (MaterialLight is 1.75:1). */
object Contrast {

    /** WCAG AA: 14 sp bold is not large text. */
    const val TEXT = 4.5

    fun ratio(a: Int, b: Int): Double {
        // a translucent accent reads as what it makes of the background (0x990d47a1 on white: 3.2:1 drawn, 9:1 alpha ignored)
        val la = luminance(over(a, b))
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Else blended a tenth at a time toward [toward] (keeping the hue), or toward black or white if [toward] fails too; a blended one is opaque. */
    fun legible(colour: Int, background: Int, toward: Int, min: Double = TEXT): Int {
        // as it is, alpha too: a custom theme's translucent accent that reads, drawn, stays translucent
        if (ratio(colour, background) >= min) return colour
        val drawn = over(colour, background)
        val extreme = if (ratio(BLACK, background) >= ratio(WHITE, background)) BLACK else WHITE
        for (target in listOf(toward, extreme)) {
            for (step in 1..STEPS) {
                val c = blend(drawn, target, step.toDouble() / STEPS)
                if (ratio(c, background) >= min) return c
            }
        }
        return extreme
    }

    /** Its alpha dropped: opaque. */
    private fun over(fg: Int, bg: Int): Int = blend(bg, fg, ((fg ushr 24) and 0xff) / 255.0)

    private fun blend(a: Int, b: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val x = (a shr shift) and 0xff
            val y = (b shr shift) and 0xff
            return (x + (y - x) * t).toInt().coerceIn(0, 0xff) shl shift
        }
        return BLACK or channel(16) or channel(8) or channel(0)
    }

    private fun luminance(c: Int): Double {
        fun linear(shift: Int): Double {
            val s = ((c shr shift) and 0xff) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private const val STEPS = 10
    private const val BLACK = 0xff000000.toInt()
    private const val WHITE = 0xffffffff.toInt()
}
