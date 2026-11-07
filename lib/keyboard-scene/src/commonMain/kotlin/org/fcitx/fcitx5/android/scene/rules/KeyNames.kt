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

    /**
     * The resource names the old key views had (`R.id.button_*`), by role: what a screen reader's
     * node of a key gives as its id (060-render-p2 S3), so UAT (`uat.py keys()`, `SoftKeyboardTest`)
     * finds a key the scene draws by the name it found its view by. Only the roles whose views had
     * one; Shift's and Return's were named for what they did (caps, return). Published as the views'
     * were: never renamed. The number pad's space (`button_mini_space`) is S4's, with that board.
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
}
