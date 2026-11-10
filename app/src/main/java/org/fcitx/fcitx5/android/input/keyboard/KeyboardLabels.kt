/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts

/**
 * The keyboards' names as the input method pages and ⋯'s 键盘 list them, with the theme's layout a keyboard is drawn
 * with (060-render-p3 T8b): `18 键（主题：17 键）`.
 */
object KeyboardLabels {

    /** The longest of a theme's layout's name shown: a pack's is unbounded. */
    private const val NAME_MAX = 24

    /** The layout a keyboard of the input method pages is drawn with ([ImKeyboard]'s board). */
    fun boardOf(keyboard: ImKeyboard): String = when (keyboard) {
        ImKeyboard.FULL -> "qwerty"
        ImKeyboard.KEYS18 -> "k18"
        ImKeyboard.KEYS14 -> "k14"
        ImKeyboard.NINE_KEYS -> "nine"
        ImKeyboard.ZHUYIN_NINE -> "zhuyin-nine"
        ImKeyboard.ZHUYIN_FULL -> "zhuyin"
    }

    /**
     * The name of the theme's layout [im]'s [keyboard] is drawn with now (`17 键`), null where it is the app's:
     * none in the pack, one refused, or (14 and 18 keys) groups [im] cannot type, the app's drawn instead (T9b).
     */
    fun themedName(context: Context, im: String, keyboard: ImKeyboard): String? {
        val id = boardOf(keyboard)
        val shown = SceneLayouts.of(context, id) ?: return null
        if (shown === SceneLayouts.builtIn(context, id)) return null
        val letters = when (id) {
            "k14" -> NineKeyLetters.G14
            "k18" -> NineKeyLetters.G18
            else -> null
        }
        if (letters != null && GroupedKeyboard.grouping(context, letters, own = im == InputMethodCatalog.PINYIN, log = false).layout !== shown) return null
        val name = shown.layout.name
        return if (name.length > NAME_MAX) name.take(NAME_MAX) + "…" else name
    }

    /** [label] (a keyboard's own name) with the theme's layout's it is drawn with ([themedName]). */
    fun label(context: Context, im: String, keyboard: ImKeyboard, label: String): String =
        themedName(context, im, keyboard)?.let { context.getString(R.string.keyboard_themed, label, it) } ?: label
}
