/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

/** A key's one name (`full/q`, `fn/comma`): its long press, swipes and marks are found by it on every keyboard that has it. */
data class KeyId(val name: String) {
    val form: String get() = name.substringBefore('/')

    val key: String get() = name.substringAfter('/', "")

    val isFunction: Boolean get() = form == FUNCTION

    override fun toString() = name

    companion object {
        const val FUNCTION = "fn"

        /** The only place a bare name becomes a function key's. */
        fun of(layoutName: String): KeyId = KeyId(if ('/' in layoutName) layoutName else "$FUNCTION/$layoutName")

        fun function(name: String) = KeyId("$FUNCTION/$name")
    }
}

sealed interface Commit {
    data object AsKey : Commit

    data object AsIs : Commit

    data class Keypad(val digit: Char) : Commit

    data class Action(val send: String) : Commit
}

data class Item(val text: String, val commit: Commit = Commit.AsKey)

/** The user's list, once set, is stored whole and replaces both [row] and [symbols]. */
data class DefaultList(
    val row: List<Item>,
    val symbols: List<Item>,
    val first: Item?,
    val focus: Int = (row + symbols).indexOf(first).coerceAtLeast(0),
) {
    val items: List<Item> get() = row + symbols
}

/** No language: every input method's keys have the same lists; only 双拼's letters differ. */
data class KeyboardVariant(
    val form: Form,
    val nineLetters: Map<Char, String> = STANDARD_NINE,
    val semicolon: Boolean = false,
) {
    enum class Form { Full, Nine, Grouped14, Grouped18, ZhuyinFull, ZhuyinNine, Number, Panel }

    companion object {
        val STANDARD_NINE: Map<Char, String> = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl", '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )
    }
}
