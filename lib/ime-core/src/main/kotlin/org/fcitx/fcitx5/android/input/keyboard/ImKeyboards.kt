/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme

/**
 * An on-screen keyboard an input method is typed on (I1): pinyin's, 双拼's and English's own
 * choice, set on its page or from ⋯'s 键盘. A hardware keyboard types the full one's whatever is
 * chosen (KeyboardPair). The app stores its own enums' names for these (PinyinKeyboard,
 * ShuangpinKeyboard, EnglishKeyboard in AppPrefs): those never renamed, this one free to be.
 */
enum class ImKeyboard { FULL, KEYS18, KEYS14, NINE_KEYS, ZHUYIN_NINE, ZHUYIN_FULL }

/**
 * Which keyboards each input method offers, and which layout the screen shows for it: the input
 * method is the language or the scheme, the keyboard its setting (I1 §2). The app reads the
 * choices from its preferences and passes them in; this decides.
 */
object ImKeyboards {

    /** What the screen shows: each a layout of its own, the keys sending their own codes ([layoutOf]). */
    enum class Layout {
        TEXT,
        PINYIN14,
        PINYIN18,
        PINYIN_NINE,
        SHUANGPIN18,
        SHUANGPIN14,
        SHUANGPIN_NINE,
        ZHUYIN_NINE,
        ZHUYIN_FULL,
        ENGLISH_NINE,
        HANDWRITING,
    }

    /** Each input method's keyboard as the user chose it: [ImKeyboard.FULL] for one never chosen. */
    data class Chosen(
        val pinyin: ImKeyboard = ImKeyboard.FULL,
        val shuangpin: ImKeyboard = ImKeyboard.FULL,
        val english: ImKeyboard = ImKeyboard.FULL,
    ) {
        /** [im]'s, [ImKeyboard.FULL] for one with no choice. */
        fun of(im: String): ImKeyboard = when (im) {
            InputMethodCatalog.PINYIN -> pinyin
            InputMethodCatalog.SHUANGPIN -> shuangpin
            InputMethodCatalog.ENGLISH -> english
            else -> ImKeyboard.FULL
        }
    }

    private val ALL = listOf(ImKeyboard.FULL, ImKeyboard.KEYS18, ImKeyboard.KEYS14, ImKeyboard.NINE_KEYS)

    /**
     * The keyboards [im] offers, in the order offered; none for one with no choice (a table, handwriting).
     * 双拼's 14 keys not under a [scheme] that types `;` (微软: ing), which no key of theirs has (I1 Q5).
     */
    fun choices(im: String, scheme: ShuangpinScheme): List<ImKeyboard> = when (im) {
        // pinyin typed in zhuyin too (design 051 Q1 A): its words, two keyboards of its own
        InputMethodCatalog.PINYIN -> ALL + ImKeyboard.ZHUYIN_NINE + ImKeyboard.ZHUYIN_FULL
        InputMethodCatalog.SHUANGPIN -> if (has14(scheme)) ALL else ALL - ImKeyboard.KEYS14
        InputMethodCatalog.ENGLISH -> listOf(ImKeyboard.FULL, ImKeyboard.NINE_KEYS)
        else -> emptyList()
    }

    /** Whether 双拼's [scheme] can be typed on the 14 keys: every key of it is a letter. */
    fun has14(scheme: ShuangpinScheme): Boolean = !scheme.types(';')

    /**
     * The keyboard [im] is typed on, [chosen] its choice: one it does not offer the nearest it does,
     * the 14 keys under 微软 the 18 keys (grouped keys as well, the letters two or one a key).
     */
    fun effective(im: String, chosen: ImKeyboard, scheme: ShuangpinScheme): ImKeyboard {
        val offered = choices(im, scheme)
        return when {
            chosen in offered -> chosen
            chosen == ImKeyboard.KEYS14 && ImKeyboard.KEYS18 in offered -> ImKeyboard.KEYS18
            else -> ImKeyboard.FULL
        }
    }

    /**
     * The choice [im]'s [chosen] is to be stored as once [scheme] no longer offers it ([effective]:
     * 双拼's 14 keys the 18 keys under 微软), null while it does: set on a scheme's change, so
     * the keyboard changes with it and stays so for a scheme that has the 14 keys again.
     */
    fun fallback(im: String, chosen: ImKeyboard, scheme: ShuangpinScheme): ImKeyboard? =
        effective(im, chosen, scheme).takeIf { it != chosen && choices(im, scheme).isNotEmpty() }

    /** What [im]'s keys were typed on: the layout shown, and for 双拼 its [scheme], which reads the same letters otherwise. */
    data class TypedWith(val layout: Layout, val scheme: ShuangpinScheme?)

    /**
     * What [im] is typed with now ([layoutOf], 双拼's scheme too): what was typed is typed again on
     * the same alone (清除's 恢复, HostCore.retype), another's keys meaning other keys or syllables.
     */
    fun typedWith(im: String, chosen: Chosen, scheme: ShuangpinScheme): TypedWith =
        TypedWith(layoutOf(im, chosen, scheme), scheme.takeIf { im == InputMethodCatalog.SHUANGPIN })

    /**
     * The layout input method [im] shows, each one's keyboard as [chosen] has it, 双拼's under
     * [scheme]; in a [password] field English's the full keyboard whatever it chose, a letter a key.
     */
    fun layoutOf(im: String, chosen: Chosen, scheme: ShuangpinScheme, password: Boolean = false): Layout {
        val keyboard = effective(im, chosen.of(im), scheme)
        return when (im) {
            InputMethodCatalog.HANDWRITING -> Layout.HANDWRITING
            InputMethodCatalog.PINYIN -> when (keyboard) {
                ImKeyboard.FULL -> Layout.TEXT
                ImKeyboard.KEYS18 -> Layout.PINYIN18
                ImKeyboard.KEYS14 -> Layout.PINYIN14
                ImKeyboard.NINE_KEYS -> Layout.PINYIN_NINE
                ImKeyboard.ZHUYIN_NINE -> Layout.ZHUYIN_NINE
                ImKeyboard.ZHUYIN_FULL -> Layout.ZHUYIN_FULL
            }
            InputMethodCatalog.SHUANGPIN -> when (keyboard) {
                ImKeyboard.FULL -> Layout.TEXT
                ImKeyboard.KEYS18 -> Layout.SHUANGPIN18
                ImKeyboard.KEYS14 -> Layout.SHUANGPIN14
                ImKeyboard.NINE_KEYS -> Layout.SHUANGPIN_NINE
                // not offered: effective has made them FULL
                ImKeyboard.ZHUYIN_NINE, ImKeyboard.ZHUYIN_FULL -> Layout.TEXT
            }
            InputMethodCatalog.ENGLISH -> if (keyboard == ImKeyboard.NINE_KEYS && !password) Layout.ENGLISH_NINE else Layout.TEXT
            else -> Layout.TEXT
        }
    }

    /**
     * Whether English on the nine keys spells words from the keys ([predictive], 预测) rather than
     * a letter at a time (连按): only where English [suggests] words at all (its setting on, the
     * field neither a password, nor an address, nor one asking for no suggestions), as the engine
     * hands the keys to no word there.
     */
    fun spellsWords(predictive: Boolean, suggests: Boolean, password: Boolean, address: Boolean, noSuggestions: Boolean): Boolean =
        predictive && suggests && !password && !address && !noSuggestions
}
