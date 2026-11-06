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

    /** The letters the 26 keys are named by. */
    val LETTERS: List<String> = ('a'..'z').map(Char::toString)
}
