/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What a long press on each of 九键's keys 1 to 9 offers besides its digit and letters: symbols
 * typed from there alone, never read as the key's letters (those the engine decodes and
 * multi-tap goes through). Each key's as the user set it, else [DEFAULTS]; at most [MAX] a key.
 */
class NineKeySymbols private constructor(private val edited: Map<Char, List<String>>) {

    /** [digit]'s symbols, in the order its long press shows them. */
    fun symbols(digit: Char): List<String> = edited[digit] ?: DEFAULTS[digit].orEmpty()

    /** Whether [digit]'s are its [DEFAULTS]. */
    fun isDefault(digit: Char): Boolean = digit !in edited

    val isStandard: Boolean get() = edited.isEmpty()

    /** These with [digit]'s [symbols] as [split] keeps them; its [DEFAULTS] kept as no change. */
    fun with(digit: Char, symbols: List<String>): NineKeySymbols {
        if (digit !in KEYS) return this
        val kept = symbols.filter(::allowed).distinct().take(MAX)
        return NineKeySymbols(if (kept == DEFAULTS[digit]) edited - digit else edited + (digit to kept))
    }

    /** One line a key set otherwise than [DEFAULTS]: its digit, then its symbols, apart by spaces. */
    fun serialize(): String = edited.toSortedMap().entries.joinToString("\n") { (digit, symbols) ->
        (listOf(digit.toString()) + symbols).joinToString(" ")
    }

    override fun equals(other: Any?) = other is NineKeySymbols && other.edited == edited

    override fun hashCode() = edited.hashCode()

    override fun toString() = serialize()

    companion object {
        const val MAX = 10

        val KEYS = '1'..'9'

        /**
         * 1's: the rest of 搜狗's column, typed as they are (. not 。). 2 to 9's: the full keyboard's
         * corner characters of the key's letters as a phone has them (abc: a@ b! c"), its digits
         * left out, a long press shows them already, typed as Chinese punctuation maps them as there.
         */
        val DEFAULTS: Map<Char, List<String>> = mapOf(
            '1' to listOf("…", "：", "；", "~", ".", "-", "@", "、", "“", "”"),
            '2' to listOf("@", "!", "\""),
            '3' to listOf("+", "-"),
            '4' to listOf("=", "/"),
            '5' to listOf("#", "(", ")"),
            '6' to listOf("\\", "~"),
            '7' to listOf("*"),
            '8' to listOf("?"),
            '9' to listOf(":", "'"),
        )

        val STANDARD = NineKeySymbols(emptyMap())

        /** As [serialize] wrote it; a line not a key's left out. */
        fun parse(text: String): NineKeySymbols = text.lines().fold(STANDARD) { symbols, line ->
            val parts = line.split(' ').filter { it.isNotEmpty() }
            val digit = parts.firstOrNull()?.singleOrNull()
            if (digit == null || digit !in KEYS) symbols else symbols.with(digit, parts.drop(1))
        }

        /**
         * What a key is set to from [typed]: its [split] symbols, none for nothing typed; null when
         * something was typed and none of it kept (digits or letters alone), so the key is left as
         * it was rather than emptied unasked.
         */
        fun edited(typed: String): List<String>? = split(typed).takeIf { it.isNotEmpty() || typed.isBlank() }

        /**
         * [typed]'s symbols, apart by spaces (a full-width one too), as a key keeps them: [allowed]
         * ones, each once, [MAX] at most.
         */
        fun split(typed: String): List<String> =
            typed.map { if (it.isWhitespace()) ' ' else it }.joinToString("").split(' ').filter(::allowed).distinct().take(MAX)

        /**
         * Not a digit or a letter alone: a long press offers those of the key already, in the row
         * under its symbols, and any other is no symbol. Nothing blank or spaced.
         */
        fun allowed(symbol: String): Boolean {
            if (symbol.isEmpty() || symbol.any { it.isWhitespace() }) return false
            val c = symbol.singleOrNull() ?: return true
            return c !in '0'..'9' && c.lowercaseChar() !in 'a'..'z'
        }
    }
}
