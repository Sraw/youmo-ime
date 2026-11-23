/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.rules.KeyNames as SceneKeyNames

/** [board] [KeyNames.ALL] for a function key on every board. */
data class KeyName(val board: String, val key: String) {
    override fun toString() = "$board/$key"
}

/**
 * Keys a theme can style by name: by place, or a function key by role. Names go into theme files: never changed or
 * removed once released, but a key gone from its board takes its name with it (a theme naming it still reads, styling
 * nothing). The theme maker reads this list.
 */
object KeyNames {

    const val ALL = "*"

    const val QWERTY = "qwerty"
    const val ZHUYIN = "zhuyin"
    const val NINE = "nine"
    const val ZHUYIN_NINE = "zhuyin-nine"
    const val K14 = "k14"
    const val K18 = "k18"
    const val NUMBER = "number"
    const val SYMBOLS = "symbols"
    const val EMOJI = "emoji"

    val ROLES: List<String> = listOf(
        "shift", "backspace", "space", "enter", "confirm", "symbols", "numbers", "back", "lang",
        "comma", "period", "semicolon", "star", "apostrophe", "reset", "lookup", "lookup-on",
        "slash", "at", "dot", "domain", "search", "lock", "emoji", "emoticon",
    )

    private val letters = ('a'..'z').map(Char::toString)

    // row by row
    private fun cells(rows: List<Int>) = rows.flatMapIndexed { r, n -> (1..n).map { c -> "r${r + 1}c$c" } }

    /** By their place. */
    val POSITIONS: Map<String, List<String>> = linkedMapOf(
        QWERTY to letters,
        // as on a computer's 大千 keyboard: ㄅ is 1, ㄆ q
        ZHUYIN to "1234567890-qwertyuiopasdfghjkl;zxcvbnm,./".map(Char::toString),
        NINE to (1..9).map(Int::toString) + "0" + "side",
        // the tones (c1), the groups and the slot (c2–c4), ? ! ~ (c5); ⌫, r1c5, by its role
        ZHUYIN_NINE to cells(listOf(5, 5, 5, 5)) - "r1c5",
        K14 to cells(listOf(5, 5, 4)),
        K18 to cells(listOf(7, 6, 5)),
        // the digits, the two signs still keys and the scroll column; the other signs are the column's items
        NUMBER to (0..9).map(Int::toString) + listOf("=", ".", "side"),
        SYMBOLS to emptyList(),
        EMOJI to emptyList(),
    )

    val BOARDS: Set<String> get() = POSITIONS.keys

    /** Not board/key: an error. An unknown board or key reads (a newer theme): [isKnown] tells. */
    fun parse(text: String): KeyName = SceneKeyNames.split(text).let { (board, key) -> KeyName(board, key) }

    fun on(board: String): Set<String> = POSITIONS[board].orEmpty().toSet() + ROLES

    fun isKnown(name: KeyName) =
        if (name.board == ALL) name.key in ROLES else name.board in POSITIONS && name.key in on(name.board)

    fun isRole(key: String) = key in ROLES
}
