/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/** A key as a theme names it: [board]/[key], `qwerty/q`; [board] [KeyNames.ALL] for a function key on every board. */
data class KeyName(val board: String, val key: String) {
    override fun toString() = "$board/$key"
}

/**
 * Every key a theme can style by itself, by name: a board's prefix and the key's name on it. A key
 * in a place (a letter, a digit, a cell) is named by the place, the same whatever its letters are on
 * a scheme; a function key by its role, the same on every board. Names go into theme files, so a
 * name once released is never changed or taken away: a key a board loses keeps its name here.
 * The theme maker reads this list (its layout data) to tell a name it does not know.
 */
object KeyNames {

    /** The board of a function key's look on every board. */
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
    const val HANDWRITING = "handwriting"

    /** Function keys, the same name on every board. */
    val ROLES: List<String> = listOf(
        "shift", "backspace", "space", "enter", "confirm", "symbols", "numbers", "back", "lang",
        "comma", "period", "semicolon", "star", "apostrophe", "reset", "lookup", "lookup-on",
        "slash", "at", "dot", "domain", "search", "lock", "emoji", "emoticon", "reading",
    )

    private val letters = ('a'..'z').map(Char::toString)

    // a 3×4 grid's cells, row by row
    private fun cells(rows: List<Int>) = rows.flatMapIndexed { r, n -> (1..n).map { c -> "r${r + 1}c$c" } }

    /** Each board's keys named by their place. */
    val POSITIONS: Map<String, List<String>> = linkedMapOf(
        QWERTY to letters,
        // as on a computer's 大千 keyboard: ㄅ is 1, ㄆ q
        ZHUYIN to "1234567890-qwertyuiopasdfghjkl;zxcvbnm,./".map(Char::toString),
        NINE to (1..9).map(Int::toString) + "0" + "side",
        ZHUYIN_NINE to cells(listOf(3, 3, 3, 3)) + "0" + "side",
        K14 to cells(listOf(5, 5, 4)),
        K18 to cells(listOf(7, 6, 5)),
        // the digits and every sign a pad has beside them (NumberPad)
        NUMBER to (0..9).map(Int::toString) + listOf("+", "-", "*", "/", ",", "=", ".", "#", "(", ")", ":"),
        SYMBOLS to emptyList(),
        EMOJI to emptyList(),
        HANDWRITING to emptyList(),
    )

    val BOARDS: Set<String> get() = POSITIONS.keys

    private val boardSyntax = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /**
     * [text] read as a name; one not of the form board/key is an error. A board or key this build
     * does not know reads (a newer app's theme): [isKnown] tells.
     */
    fun parse(text: String): KeyName {
        val slash = text.indexOf('/')
        require(slash > 0) { "key name \"$text\" is not board/key" }
        val board = text.substring(0, slash)
        val key = text.substring(slash + 1)
        require(board == ALL || boardSyntax.matches(board)) { "key name \"$text\" has a bad board" }
        require(key.isNotEmpty() && key.none(Char::isWhitespace)) { "key name \"$text\" has a bad key" }
        return KeyName(board, key)
    }

    /** The names a key of [board] can have: its places and every role. */
    fun on(board: String): Set<String> = POSITIONS[board].orEmpty().toSet() + ROLES

    fun isKnown(name: KeyName) =
        if (name.board == ALL) name.key in ROLES else name.board in POSITIONS && name.key in on(name.board)

    fun isRole(key: String) = key in ROLES
}
