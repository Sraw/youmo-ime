/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import kotlin.math.roundToInt

/** A colour, `0xAARRGGBB` as Android has it (and theme 3.0 wrote it). */
data class Argb(val value: Int) {
    val alpha: Int get() = (value ushr ALPHA_SHIFT) and BYTE
    val red: Int get() = (value shr RED_SHIFT) and BYTE
    val green: Int get() = (value shr GREEN_SHIFT) and BYTE
    val blue: Int get() = value and BYTE

    val transparent: Boolean get() = alpha == 0

    /** `#aarrggbb`, as the scene's text writes it. */
    override fun toString(): String = "#" + (value.toLong() and ALL).toString(HEX).padStart(EIGHT, '0')

    companion object {
        val TRANSPARENT = Argb(0)

        fun of(a: Int, r: Int, g: Int, b: Int): Argb =
            Argb((a.channel() shl ALPHA_SHIFT) or (r.channel() shl RED_SHIFT) or (g.channel() shl GREEN_SHIFT) or b.channel())

        private fun Int.channel(): Int = coerceIn(0, BYTE)

        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BYTE = 0xFF
        private const val ALL = 0xFFFFFFFFL
        private const val HEX = 16
        private const val EIGHT = 8
    }
}

/**
 * A colour as theme-4.xsd writes it, before its variables are known: `#rrggbb`, `#aarrggbb`,
 * `@name`, `transparent`, or a function of colours (nested at will, though the XSD's pattern shows
 * one level):
 * - `darker(c, n%)`: each of r, g, b n% nearer black; `lighter(c, n%)`: nearer white;
 * - `alpha(c, n%)`: c with its alpha set to n% (not multiplied: what the maker's slider shows);
 * - `mix(a, b, n%)`: n% of the way from a to b, alpha too (the floating handle's 10% of the text).
 */
sealed interface ColorExpr {
    data class Literal(val argb: Argb) : ColorExpr
    data class Ref(val name: String) : ColorExpr
    data class Fn(val name: String, val args: List<ColorExpr>, val percent: Double) : ColorExpr

    /** Its colour, [vars] giving a variable's (null: no such colour). */
    fun resolve(vars: (String) -> Argb?): Argb? = when (this) {
        is Literal -> argb
        is Ref -> vars(name)
        is Fn -> args.map { it.resolve(vars) ?: return null }.let { apply(name, it, percent / PERCENT) }
    }

    /** The variables it names. */
    fun refs(): List<String> = when (this) {
        is Literal -> emptyList()
        is Ref -> listOf(name)
        is Fn -> args.flatMap { it.refs() }
    }

    companion object {
        private const val PERCENT = 100.0
        private const val FULL = 255
        private val HEX6 = Regex("#[0-9A-Fa-f]{6}")
        private val HEX8 = Regex("#[0-9A-Fa-f]{8}")
        private val VAR = Regex("@[a-zA-Z][a-zA-Z0-9_-]*")
        private val PERCENT_ARG = Regex("([0-9]|[1-9][0-9]|100)%")
        private val ARITY = mapOf("darker" to 1, "lighter" to 1, "alpha" to 1, "mix" to 2)

        /** [text] read, or null where it is no colour. */
        fun parse(text: String): ColorExpr? {
            val t = text.trim()
            return when {
                t == "transparent" -> Literal(Argb.TRANSPARENT)
                HEX6.matches(t) -> Literal(Argb(t.substring(1).toLong(16).toInt() or (FULL shl 24)))
                HEX8.matches(t) -> Literal(Argb(t.substring(1).toLong(16).toInt()))
                VAR.matches(t) -> Ref(t.substring(1))
                else -> function(t)
            }
        }

        private fun function(t: String): ColorExpr? {
            val name = t.substringBefore('(')
            val arity = ARITY[name] ?: return null
            if (!t.endsWith(")")) return null
            val args = splitTop(t.substring(name.length + 1, t.length - 1)) ?: return null
            if (args.size != arity + 1) return null
            val percent = args.last().trim().takeIf(PERCENT_ARG::matches)?.dropLast(1)?.toDouble() ?: return null
            val colors = args.dropLast(1).map { parse(it) ?: return null }
            return Fn(name, colors, percent)
        }

        /** [s] split at its commas outside parentheses; null where they do not match. */
        fun splitTop(s: String): List<String>? {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            for ((i, c) in s.withIndex()) {
                when (c) {
                    '(' -> depth++
                    ')' -> if (--depth < 0) return null
                    ',' -> if (depth == 0) {
                        out += s.substring(start, i)
                        start = i + 1
                    }
                }
            }
            if (depth != 0) return null
            out += s.substring(start)
            return out
        }

        private fun apply(name: String, c: List<Argb>, f: Double): Argb {
            val a = c[0]
            return when (name) {
                "darker" -> Argb.of(a.alpha, toward(a.red, 0, f), toward(a.green, 0, f), toward(a.blue, 0, f))
                "lighter" -> Argb.of(a.alpha, toward(a.red, FULL, f), toward(a.green, FULL, f), toward(a.blue, FULL, f))
                "alpha" -> Argb.of((FULL * f).roundToInt(), a.red, a.green, a.blue)
                else -> {
                    val b = c[1]
                    Argb.of(toward(a.alpha, b.alpha, f), toward(a.red, b.red, f), toward(a.green, b.green, f), toward(a.blue, b.blue, f))
                }
            }
        }

        private fun toward(from: Int, to: Int, f: Double): Int = (from + (to - from) * f).roundToInt()
    }
}
