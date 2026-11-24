/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.layout.KeyData.Board
import org.fcitx.fcitx5.android.scene.layout.KeyData.Held

/**
 * What the app's keys do held or swiped, by board and name ([KeyData.board] of each of src/commonMain/layouts): what
 * KeyData puts in place of a theme's. Written by BuiltInKeyDataTest (-Psnapshots.record=true), which holds it to the
 * layouts; not edited by hand.
 */
@Suppress("LongMethod", "LargeClass")
internal object BuiltInKeyData {
    private val NONE = Held()

    val BOARDS: Map<String, Board> by lazy {
        mapOf(
            "emoji" to emoji(),
            "emoji-search" to emojiSearch(),
            "emoticon" to emoticon(),
            "expanded-side" to expandedSide(),
            "k14" to k14(),
            "k18" to k18(),
            "nine" to nine(),
            "number" to number(),
            "number-row" to numberRow(),
            "qwerty" to qwerty(),
            "symbols" to symbols(),
            "zhuyin" to zhuyin(),
            "zhuyin-nine" to zhuyinNine(),
        )
    }

    private fun emoji() = Board(
        popups = false,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/space" to NONE,
        ),
        unnamed = mapOf(
            "back" to NONE,
            "emoticon" to NONE,
            "search" to NONE,
        ),
    )

    private fun emojiSearch() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
        ),
        unnamed = mapOf(
            "a" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "b" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "back" to NONE,
            "c" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "d" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "e" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "f" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "g" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "h" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "i" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "j" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "k" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "l" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "m" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "n" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "o" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "p" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "q" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "r" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "s" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "t" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "u" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "v" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "w" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "x" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "y" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "z" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
        ),
    )

    private fun emoticon() = Board(
        popups = false,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/comma" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/space" to NONE,
        ),
        unnamed = mapOf(
            "back" to NONE,
            "emoji" to NONE,
        ),
    )

    private fun expandedSide() = Board(
        popups = false,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
        ),
        unnamed = mapOf(
            "page-down" to NONE,
            "page-up" to NONE,
        ),
    )

    private fun k14() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/comma" to NONE,
            "fn/lang" to NONE,
            "fn/numbers" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/separator" to NONE,
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "g14/as" to NONE,
            "g14/bn" to NONE,
            "g14/cv" to NONE,
            "g14/df" to NONE,
            "g14/er" to NONE,
            "g14/gh" to NONE,
            "g14/jk" to NONE,
            "g14/l" to NONE,
            "g14/m" to NONE,
            "g14/op" to NONE,
            "g14/qw" to NONE,
            "g14/ty" to NONE,
            "g14/ui" to NONE,
            "g14/zx" to NONE,
        ),
        unnamed = mapOf(
            "at" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "domain" to Held(popup = Popup("list", null, listOf(".com" to null, ".cn" to null, ".net" to null, ".org" to null), mapOf("as-is" to "true"))),
            "dot" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "slash" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
        ),
        places = mapOf("g14/qw" to 0, "g14/er" to 1, "g14/ty" to 2, "g14/ui" to 3, "g14/op" to 4, "g14/as" to 5, "g14/df" to 6, "g14/gh" to 7, "g14/jk" to 8, "g14/l" to 9, "g14/zx" to 10, "g14/cv" to 11, "g14/bn" to 12, "g14/m" to 13),
    )

    private fun k18() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/comma" to NONE,
            "fn/lang" to NONE,
            "fn/numbers" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/separator" to NONE,
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "g18/a" to NONE,
            "g18/bn" to NONE,
            "g18/fg" to NONE,
            "g18/h" to NONE,
            "g18/io" to NONE,
            "g18/jk" to NONE,
            "g18/l" to NONE,
            "g18/m" to NONE,
            "g18/p" to NONE,
            "g18/q" to NONE,
            "g18/rt" to NONE,
            "g18/sd" to NONE,
            "g18/u" to NONE,
            "g18/v" to NONE,
            "g18/we" to NONE,
            "g18/xc" to NONE,
            "g18/y" to NONE,
            "g18/z" to NONE,
        ),
        unnamed = mapOf(
            "at" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "domain" to Held(popup = Popup("list", null, listOf(".com" to null, ".cn" to null, ".net" to null, ".org" to null), mapOf("as-is" to "true"))),
            "dot" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "slash" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
        ),
        places = mapOf("g18/q" to 0, "g18/we" to 1, "g18/rt" to 2, "g18/y" to 3, "g18/u" to 4, "g18/io" to 5, "g18/p" to 6, "g18/a" to 7, "g18/sd" to 8, "g18/fg" to 9, "g18/h" to 10, "g18/jk" to 11, "g18/l" to 12, "g18/z" to 13, "g18/xc" to 14, "g18/v" to 15, "g18/bn" to 16, "g18/m" to 17),
    )

    private fun nine() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/lang" to NONE,
            "fn/numbers" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/retype" to NONE,
            "fn/shift" to Held(actions = mapOf("double-tap" to "caps:lock")),
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "fn/zero" to NONE,
            "t9/1" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/2" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/3" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/4" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/5" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/6" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/7" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/8" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
            "t9/9" to Held(popup = Popup("nine", null, emptyList(), emptyMap())),
        ),
    )

    private fun number() = Board(
        popups = false,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "num/0" to NONE,
            "num/1" to NONE,
            "num/2" to NONE,
            "num/3" to NONE,
            "num/4" to NONE,
            "num/5" to NONE,
            "num/6" to NONE,
            "num/7" to NONE,
            "num/8" to NONE,
            "num/9" to NONE,
        ),
        unnamed = mapOf(
            "." to NONE,
            "=" to NONE,
            "back" to Held(actions = mapOf("long-press" to "panel-lock")),
        ),
    )

    private fun numberRow() = Board(
        popups = true,
        named = emptyMap(),
        unnamed = mapOf(
            "0" to NONE,
            "1" to NONE,
            "2" to NONE,
            "3" to NONE,
            "4" to NONE,
            "5" to NONE,
            "6" to NONE,
            "7" to NONE,
            "8" to NONE,
            "9" to NONE,
        ),
    )

    private fun qwerty() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/comma" to NONE,
            "fn/lang" to NONE,
            "fn/numbers" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/separator" to NONE,
            "fn/shift" to Held(actions = mapOf("double-tap" to "caps:lock")),
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "full/a" to NONE,
            "full/b" to NONE,
            "full/c" to NONE,
            "full/d" to NONE,
            "full/e" to NONE,
            "full/f" to NONE,
            "full/g" to NONE,
            "full/h" to NONE,
            "full/i" to NONE,
            "full/j" to NONE,
            "full/k" to NONE,
            "full/l" to NONE,
            "full/m" to NONE,
            "full/n" to NONE,
            "full/o" to NONE,
            "full/p" to NONE,
            "full/q" to NONE,
            "full/r" to NONE,
            "full/s" to NONE,
            "full/t" to NONE,
            "full/u" to NONE,
            "full/v" to NONE,
            "full/w" to NONE,
            "full/x" to NONE,
            "full/y" to NONE,
            "full/z" to NONE,
        ),
        unnamed = mapOf(
            "at" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "domain" to Held(popup = Popup("list", null, listOf(".com" to null, ".cn" to null, ".net" to null, ".org" to null), mapOf("as-is" to "true"))),
            "dot" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "lookup" to NONE,
            "lookup-on" to NONE,
            "semicolon" to NONE,
            "slash" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "star" to NONE,
        ),
    )

    private fun symbols() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/numbers" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/space" to NONE,
        ),
        unnamed = mapOf(
            "back" to NONE,
            "lock" to NONE,
        ),
    )

    private fun zhuyin() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/comma" to NONE,
            "fn/lang" to NONE,
            "fn/numbers" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "zyfull/," to NONE,
            "zyfull/-" to NONE,
            "zyfull/." to NONE,
            "zyfull//" to NONE,
            "zyfull/0" to NONE,
            "zyfull/1" to NONE,
            "zyfull/2" to NONE,
            "zyfull/3" to NONE,
            "zyfull/4" to NONE,
            "zyfull/5" to NONE,
            "zyfull/6" to NONE,
            "zyfull/7" to NONE,
            "zyfull/8" to NONE,
            "zyfull/9" to NONE,
            "zyfull/;" to NONE,
            "zyfull/a" to NONE,
            "zyfull/b" to NONE,
            "zyfull/c" to NONE,
            "zyfull/d" to NONE,
            "zyfull/e" to NONE,
            "zyfull/f" to NONE,
            "zyfull/g" to NONE,
            "zyfull/h" to NONE,
            "zyfull/i" to NONE,
            "zyfull/j" to NONE,
            "zyfull/k" to NONE,
            "zyfull/l" to NONE,
            "zyfull/m" to NONE,
            "zyfull/n" to NONE,
            "zyfull/o" to NONE,
            "zyfull/p" to NONE,
            "zyfull/q" to NONE,
            "zyfull/r" to NONE,
            "zyfull/s" to NONE,
            "zyfull/t" to NONE,
            "zyfull/u" to NONE,
            "zyfull/v" to NONE,
            "zyfull/w" to NONE,
            "zyfull/x" to NONE,
            "zyfull/y" to NONE,
            "zyfull/z" to NONE,
        ),
        unnamed = mapOf(
            "at" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "domain" to Held(popup = Popup("list", null, listOf(".com" to null, ".cn" to null, ".net" to null, ".org" to null), mapOf("as-is" to "true"))),
            "dot" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "slash" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
        ),
    )

    private fun zhuyinNine() = Board(
        popups = true,
        named = mapOf(
            "fn/backspace" to NONE,
            "fn/comma" to NONE,
            "fn/lang" to NONE,
            "fn/numbers" to NONE,
            "fn/period" to NONE,
            "fn/return" to Held(menu = listOf(MenuItem("Emoji", "emoji", "picker:"))),
            "fn/space" to NONE,
            "fn/symbols" to NONE,
            "zy9/1" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/10" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/11" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/2" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/3" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/4" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/5" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/6" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/7" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/8" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/9" to Held(popup = Popup("group", null, emptyList(), emptyMap())),
            "zy9/exclam" to NONE,
            "zy9/question" to NONE,
            "zy9/tilde" to NONE,
            "zy9/tone-1" to Held(popup = Popup("list", null, listOf("˙" to "action:tone-5"), emptyMap())),
            "zy9/tone-2" to NONE,
            "zy9/tone-3" to NONE,
            "zy9/tone-4" to NONE,
        ),
        unnamed = mapOf(
            "at" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "domain" to Held(popup = Popup("list", null, listOf(".com" to null, ".cn" to null, ".net" to null, ".org" to null), mapOf("as-is" to "true"))),
            "dot" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
            "slash" to Held(popup = Popup("none", null, emptyList(), emptyMap())),
        ),
    )
}
