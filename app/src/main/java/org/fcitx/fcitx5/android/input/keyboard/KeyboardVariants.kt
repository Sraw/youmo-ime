/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.layout.Layout

/**
 * The one way a keyboard, and the settings' editor, says which defaults its keys have (060-keys-unify §1.3,
 * I12, I13): the form its layout's family is (a theme's own layout's too), the nine keys' letters (双拼's its own), the
 * 18 keys' `;`. The input method's language is none of it: every one's lists are pinyin's (用户 2026-11-11).
 */
object KeyboardVariants {

    /** 双拼's nine keys' letters (NINE_SP): `;` on 2 aside, as T9Keyboard shows them. */
    val SHUANGPIN_NINE: Map<Char, String> = ('2'..'9').associateWith { d ->
        NineKeyLetters.NINE_SP.letters(NineKeyLetters.NINE_SP.codes[d - '2']).lowercase()
    }

    /**
     * [layout] shown for the input method [im] (its unique name, `engine-shuangpin#keys18`) under [scheme];
     * [nineLetters] the nine keys' letters as the keyboard has them (a user's t9_letters), else the language's.
     */
    fun of(layout: Layout, im: String, scheme: ShuangpinScheme, nineLetters: Map<Char, String>? = null): KeyboardVariant =
        of(DefaultMarks.variantOf(layout).form, im, scheme, nineLetters)

    /** A keyboard of [form] (its layout's family's) shown for [im]: [of]. */
    fun of(form: Form, im: String, scheme: ShuangpinScheme, nineLetters: Map<Char, String>? = null): KeyboardVariant {
        val shuangpin = isShuangpin(form, im)
        return KeyboardVariant(
            form = form,
            nineLetters = nineLetters ?: if (shuangpin) SHUANGPIN_NINE else KeyboardVariant.STANDARD_NINE,
            semicolon = shuangpin && scheme.types(';'),
        )
    }

    /** Whether [im] on a keyboard of [form] is 双拼's: 注音's and 手写's keyboards are theirs whatever types. */
    fun isShuangpin(form: Form, im: String): Boolean =
        form != Form.ZhuyinFull && form != Form.ZhuyinNine && form != Form.Handwriting && im.substringBefore('#') == InputMethodCatalog.SHUANGPIN

    /**
     * Every variant a key of a [form]'s page is shown under (the editor's: a list the same as the default on
     * each of them is no setting, KeyLists.with): pinyin's, and 双拼's where its letters or `;` make it differ.
     */
    fun all(form: Form, scheme: ShuangpinScheme): List<KeyboardVariant> {
        val semicolon = scheme.types(';')
        return when (form) {
            Form.Nine -> listOf(KeyboardVariant(form), KeyboardVariant(form, SHUANGPIN_NINE, semicolon))
            Form.Grouped14, Form.Grouped18 -> listOf(KeyboardVariant(form), KeyboardVariant(form, semicolon = semicolon)).distinct()
            else -> listOf(KeyboardVariant(form))
        }
    }
}
