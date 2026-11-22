/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A skin's icons as the app's: their names, whether they are one colour, how their pressed one differs. */
object SkinIcons {
    /** Baidu's key codes ([SkinLook.icons]' names); a code's states (`F39_S<n>`, `STAT_STYLE`) are the reader's. */
    val byBaiduCode: Map<String, String> = mapOf(
        "F36" to "backspace",
        "F10" to "shift",
        "F11" to "shift",
        "F39" to "enter",
        "F75" to "enter",
        "F16" to "language",
        "F6" to "number-pad",
        "F7" to "emoji",
    )

    private val TOOLBAR_WORDS = listOf(
        listOf("emoji", "face") to "emoji",
        listOf("keyboard") to "choose-keyboard",
        listOf("cursor", "edit") to "cursor-move",
        listOf("clip") to "clipboard",
        listOf("setting") to "input-method-settings",
        listOf("skin", "theme") to "theme",
        listOf("float") to "floating",
    )

    /** A toolbar button's icon name by a word in the skin's name of it (Baidu's `toolbarMenuItem_<X>`), the first that matches. */
    fun byToolbarName(name: String): String? {
        val n = name.lowercase()
        return TOOLBAR_WORDS.firstOrNull { (words, _) -> words.any { it in n } }?.second
    }

    /** The names a theme's `<icon>` may replace. */
    val NAMES: Set<String> = (Icons.RETURN + Icons.COMMANDS + Icons.KEYS + Icons.TOOLBAR + Icons.PANEL).toSet()

    /** The key an icon is drawn on, styled by this selector; any other is the bar's, drawn as it is. */
    fun keyOf(name: String): String? = when (name) {
        "backspace" -> "key[role=backspace]"
        "shift", "shift-once", "shift-locked" -> "key[role=shift]"
        "language" -> "key[role=lang]"
        in Icons.RETURN -> "key.enter"
        else -> null
    }

    /**
     * The colour of an icon drawn in one ([MONO_SHARE] of its opaque pixels within [MONO_SPREAD] of their mean in each of
     * red, green and blue), opaque; null for one of colours (or none opaque).
     */
    fun mono(p: Pixels): Int? {
        var n = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (c in p.argb) {
            if (c ushr ALPHA_SHIFT < OPAQUE_FROM) continue
            n++
            r += c shr RED and BYTE
            g += c shr GREEN and BYTE
            b += c and BYTE
        }
        if (n == 0L) return null
        val mr = (r.toDouble() / n).roundToInt()
        val mg = (g.toDouble() / n).roundToInt()
        val mb = (b.toDouble() / n).roundToInt()
        var near = 0L
        for (c in p.argb) {
            if (c ushr ALPHA_SHIFT < OPAQUE_FROM) continue
            val d = max(abs((c shr RED and BYTE) - mr), max(abs((c shr GREEN and BYTE) - mg), abs((c and BYTE) - mb)))
            if (d <= MONO_SPREAD) near++
        }
        return if (near * MONO_DIVISOR >= n * MONO_SHARE_PARTS) Pixels.argbOf(BYTE, mr, mg, mb) else null
    }

    sealed interface Pressed {
        /** Drawn as at rest. */
        data object Same : Pressed

        /** The same shape in another colour: only its `:pressed` `icon-color`. */
        data class Color(val argb: Int) : Pressed

        /** An icon of its own. */
        data object Image : Pressed
    }

    /** How [icon]'s pressed one differs from it at rest, both as [fit] makes them. */
    fun pressed(icon: SkinIcon): Pressed {
        val p = icon.pressed ?: return Pressed.Same
        val n = icon.normal
        if (p.width == n.width && p.height == n.height && p.argb.contentEquals(n.argb)) return Pressed.Same
        val a = mono(n)
        val b = mono(p)
        return if (a != null && b != null && sameShape(n, p)) {
            if (a == b) Pressed.Same else Pressed.Color(b)
        } else {
            Pressed.Image
        }
    }

    private fun sameShape(a: Pixels, b: Pixels): Boolean =
        a.width == b.width && a.height == b.height && a.argb.indices.all { abs((a.argb[it] ushr ALPHA_SHIFT) - (b.argb[it] ushr ALPHA_SHIFT)) <= SHAPE_SPREAD }

    /** At most [side] on its longer side, its transparent margins kept (where it sits in its box is the skin's). */
    fun fit(p: Pixels, side: Int = RasterIcon.MAX_SIDE): Pixels {
        val m = max(p.width, p.height)
        if (m <= side) return p
        val s = side.toDouble() / m
        return p.resized(max(1, (p.width * s).roundToInt()), max(1, (p.height * s).roundToInt()))
    }

    private const val ALPHA_SHIFT = 24
    private const val RED = 16
    private const val GREEN = 8
    private const val BYTE = 0xFF
    private const val OPAQUE_FROM = 0x80
    const val MONO_SPREAD = 24
    private const val MONO_SHARE_PARTS = 95L
    private const val MONO_DIVISOR = 100L
    private const val SHAPE_SPREAD = 8
}
