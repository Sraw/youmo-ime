/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts

/** With the theme's layout a keyboard is drawn with: `18 键（主题：17 键）`. */
object KeyboardLabels {

    /** A pack's is unbounded. */
    private const val NAME_MAX = 24

    fun boardOf(keyboard: ImKeyboard): String = when (keyboard) {
        ImKeyboard.FULL -> "qwerty"
        ImKeyboard.KEYS18 -> "k18"
        ImKeyboard.KEYS14 -> "k14"
        ImKeyboard.NINE_KEYS -> "nine"
        ImKeyboard.ZHUYIN_NINE -> "zhuyin-nine"
        ImKeyboard.ZHUYIN_FULL -> "zhuyin"
        // a layout of the user's is not the theme's: [themedName] names none
        ImKeyboard.CUSTOM -> "qwerty"
    }

    /** Null where it is the app's: none in the pack, one refused, or (14 and 18 keys) groups [im] cannot type. */
    fun themedName(context: Context, im: String, keyboard: ImKeyboard): String? {
        if (keyboard == ImKeyboard.CUSTOM) return null
        val id = boardOf(keyboard)
        val shown = SceneLayouts.of(context, id) ?: return null
        if (shown === SceneLayouts.builtIn(context, id)) return null
        val letters = listOf(NineKeyLetters.G14, NineKeyLetters.G18).firstOrNull { GroupedKeyboard.layoutOf(it) == id }
        if (letters != null && GroupedKeyboard.grouping(context, letters, own = im == InputMethodCatalog.PINYIN, log = false).layout !== shown) return null
        return shortName(shown.layout.name)
    }

    /** Cut by characters, not UTF-16 units, so an emoji or an extension-B character is never cut in half. */
    internal fun shortName(name: String): String {
        if (name.codePointCount(0, name.length) <= NAME_MAX) return name
        return name.substring(0, name.offsetByCodePoints(0, NAME_MAX)) + "…"
    }

    fun label(context: Context, im: String, keyboard: ImKeyboard, label: String): String =
        themedName(context, im, keyboard)?.let { context.getString(R.string.keyboard_themed, label, it) } ?: label

    /** 「17 键（自定义）」; one whose file was refused, 「17 键（读不出，暂用 18 键）」 with [base] given. */
    fun custom(context: Context, layout: CustomChoice, base: String? = null): String {
        val name = shortName(layout.name)
        return if (layout.drawn || base == null) context.getString(R.string.keyboard_custom_named, name) else context.getString(R.string.keyboard_custom_unread, name, base)
    }

    /** Its own preference's (PinyinKeyboard…), the full keyboard's for a table. */
    fun builtInName(context: Context, im: String, keyboard: ImKeyboard): String = context.getString(
        when (im) {
            InputMethodCatalog.PINYIN -> PinyinKeyboard.of(keyboard).stringRes
            InputMethodCatalog.SHUANGPIN -> ShuangpinKeyboard.of(keyboard).stringRes
            InputMethodCatalog.ENGLISH -> EnglishKeyboard.of(keyboard).stringRes
            else -> R.string.pinyin_keyboard_full
        },
    )
}
