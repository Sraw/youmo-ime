/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

/**
 * A key's one name (060-keys-unify): a character key's `<form>/<key>` (`full/q`, `t9/5`, `g14/qw`,
 * `zyfull//`), a function key's `fn/<name>`. Everything a key is set by (its long press, its two
 * swipe slots, its marks) is found by it, on every keyboard that has the key.
 */
data class KeyId(val name: String) {
    /** The part before the first `/` (`zyfull//`'s `zyfull`), as KeyShortcuts.formOf reads it. */
    val form: String get() = name.substringBefore('/')

    /** The part after the first `/`, which may hold another `/`. */
    val key: String get() = name.substringAfter('/', "")

    val isFunction: Boolean get() = form == FUNCTION

    override fun toString() = name

    companion object {
        const val FUNCTION = "fn"

        /**
         * A layout's `shortcut=` as a name: one with no `/` (`comma`, `backspace`, as layouts and theme
         * packs write a function key) is the function key's. The only place a bare name becomes one.
         */
        fun of(layoutName: String): KeyId = KeyId(if ('/' in layoutName) layoutName else "$FUNCTION/$layoutName")

        fun function(name: String) = KeyId("$FUNCTION/$name")
    }
}

/** How a long press's item is typed. */
sealed interface Commit {
    /** As the key's character would be: one code point through the keyboard (and the engine), more as text. */
    data object AsKey : Commit

    /** As it is, past the engine (九键 1's marks: its `.` never 。). */
    data object AsIs : Commit

    /** A keypad's digit: the candidate of that number while typing, else the digit (D7). */
    data class Keypad(val digit: Char) : Commit

    /** A layout's `<item send=…>` (注音九键's ˙ is its tone 5), as written: the app reads it. */
    data class Action(val send: String) : Commit
}

data class Item(val text: String, val commit: Commit = Commit.AsKey)

/**
 * A key's long press as built in: its [row], made for the keyboard's input method and never stored (a
 * nine key's letters and digit, a group's letters and corner), and its [symbols], what the user may
 * change; [first], what its swipe types and its corner shows, null with none; [focus] where the popup opens.
 */
data class DefaultList(
    val row: List<Item>,
    val symbols: List<Item>,
    val first: Item?,
    /** The item under the finger as the popup opens: the first, else the row's middle the generator says. */
    val focus: Int = (row + symbols).indexOf(first).coerceAtLeast(0),
) {
    val items: List<Item> get() = row + symbols
}

/** A keyboard as built: what its keys' rows are made for, fixed while it shows. */
data class KeyboardVariant(
    val form: Form,
    val language: Language = Language.Pinyin,
    /** A nine key's letters by its digit (2 to 9), as the input method puts them (双拼's NINE_SP). */
    val nineLetters: Map<Char, String> = STANDARD_NINE,
    /** 双拼 of a scheme typing `;` (微软's ing): the 18 keys' L group has it; else the group is L alone. */
    val semicolon: Boolean = false,
) {
    enum class Form { Full, Nine, Grouped14, Grouped18, ZhuyinFull, ZhuyinNine, Handwriting, Number, Panel }

    enum class Language { Pinyin, Shuangpin, English, Zhuyin, Table, Handwriting }

    companion object {
        val STANDARD_NINE: Map<Char, String> = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl", '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )
    }
}
