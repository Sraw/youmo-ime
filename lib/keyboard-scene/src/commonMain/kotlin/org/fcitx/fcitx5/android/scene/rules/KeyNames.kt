/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

/**
 * The names themes call keys by, as ime-core's `input.theme.KeyNames` has them (copied for P1; P2
 * moves ime-core onto this one and deletes that). Published names: never renamed (KeyNames.kt:16).
 */
object KeyNames {
    /** A key's role: what `*`/role and `[role=…]` select (layout-1.xsd's `role`, word for word). */
    val ROLES: List<String> = listOf(
        "shift", "backspace", "space", "enter", "confirm", "symbols", "numbers", "back", "lang",
        "comma", "period", "semicolon", "star", "apostrophe", "reset", "lookup", "lookup-on",
        "slash", "at", "dot", "domain", "search", "lock", "emoji", "emoticon", "reading",
    )

    /** The boards' names (`qwerty/q`'s first half). */
    val BOARDS: List<String> = listOf("qwerty", "zhuyin", "nine", "zhuyin-nine", "k14", "k18", "number", "symbols", "handwriting")

    /** The board of a theme's single key that is a function key on every board (`*`/backspace). */
    const val ALL = "*"

    private val boardSyntax = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /**
     * [text] as a theme's single key's name, `board/key` (`qwerty/q`, `*`/backspace): its board and
     * key; an error where it is not of that form. A board or key this build does not know reads (a
     * newer app's theme): it is kept, not used.
     */
    fun split(text: String): Pair<String, String> {
        val slash = text.indexOf('/')
        require(slash > 0) { "key name \"$text\" is not board/key" }
        val board = text.substring(0, slash)
        val key = text.substring(slash + 1)
        require(board == ALL || boardSyntax.matches(board)) { "key name \"$text\" has a bad board" }
        require(key.isNotEmpty() && key.none(Char::isWhitespace)) { "key name \"$text\" has a bad key" }
        return board to key
    }

    /** Whether [key] is a function key's role, which `*`/[key] styles on every board. */
    fun isRole(key: String): Boolean = key in ROLES

    /** The letters the 26 keys are named by. */
    val LETTERS: List<String> = ('a'..'z').map(Char::toString)

    /**
     * The resource names the old key views had (`R.id.button_*`), by role: what a screen reader's
     * node of a key gives as its id (060-render-p2 S3), so UAT (`uat.py keys()`, `SoftKeyboardTest`)
     * finds a key the scene draws by the name it found its view by. Only the roles whose views had
     * one; Shift's and Return's were named for what they did (caps, return). Published as the views'
     * were: never renamed. A board's key whose view had a name of its own: [BOARD_RESOURCES].
     */
    val RESOURCES: Map<String, String> = mapOf(
        "shift" to "button_caps",
        "backspace" to "button_backspace",
        "space" to "button_space",
        "enter" to "button_return",
        "confirm" to "button_confirm",
        "lang" to "button_lang",
        "comma" to "button_comma",
        "period" to "button_period",
        "semicolon" to "button_semicolon",
        "star" to "button_star",
        "lookup" to "button_lookup",
        "lookup-on" to "button_lookup_on",
        "slash" to "button_slash",
        "at" to "button_at",
        "dot" to "button_dot",
        "domain" to "button_domain",
    )

    /** The keys whose views had a name of their board's own (`board/key`), over [RESOURCES]: the number pad's space, handwriting's 注, the symbol panel's lock. */
    val BOARD_RESOURCES: Map<String, String> = mapOf(
        "number/space" to "button_mini_space",
        "handwriting/reading" to "button_reading",
        "symbols/lock" to "button_panel_lock",
    )
}
