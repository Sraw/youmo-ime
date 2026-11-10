/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * The classes of key a theme's `<sound key=…>` gives a sound to (060-theme-sounds-icons §1.1), by the
 * selectors' words: what an author styles a key as is what they give a sound to.
 */
enum class SoundKey(val word: String) {
    LETTER("letter"),
    FUNCTION("function"),
    SPACE("space"),
    ENTER("enter"),
    BACKSPACE("backspace"),
    ;

    companion object {
        /** The class named [word] (`<sound key=…>`); null for one this version does not know. */
        fun of(word: String): SoundKey? = entries.firstOrNull { it.word == word }

        /**
         * A key's class: its layout's `sound=` where that is one of its own (`space`, `delete`, `return`),
         * else ([layoutSound] `standard`) by its [kind]: an enter or space key styled so clicks as one even
         * where its layout left `sound=` out.
         */
        fun of(layoutSound: String, kind: String): SoundKey = when (layoutSound) {
            "space" -> SPACE
            "delete" -> BACKSPACE
            "return" -> ENTER
            else -> when (kind) {
                "enter" -> ENTER
                "space" -> SPACE
                "function" -> FUNCTION
                else -> LETTER
            }
        }
    }
}
