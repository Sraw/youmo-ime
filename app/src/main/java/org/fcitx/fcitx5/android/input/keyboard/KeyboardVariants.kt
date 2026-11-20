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

/** The input method's language is none of it: every one's lists are pinyin's. */
object KeyboardVariants {

    /** `;` on 2 aside, as T9Keyboard shows them. */
    val SHUANGPIN_NINE: Map<Char, String> = ('2'..'9').associateWith { d ->
        NineKeyLetters.NINE_SP.letters(NineKeyLetters.NINE_SP.codes[d - '2']).lowercase()
    }

    /** [nineLetters]: a user's t9_letters, else the language's. */
    fun of(layout: Layout, im: String, scheme: ShuangpinScheme, nineLetters: Map<Char, String>? = null, shape: String = layout.id): KeyboardVariant =
        // the app's and a pack's layouts by their own ids, whatever based-on= a pack writes; a layout of the user's by
        // its own family's board (a grouped one on qwerty is k18's), not its based-on=
        of(DefaultMarks.variantOf(layout, shape).form, im, scheme, nineLetters)

    fun of(form: Form, im: String, scheme: ShuangpinScheme, nineLetters: Map<Char, String>? = null): KeyboardVariant {
        val shuangpin = isShuangpin(form, im)
        return KeyboardVariant(
            form = form,
            nineLetters = nineLetters ?: if (shuangpin) SHUANGPIN_NINE else KeyboardVariant.STANDARD_NINE,
            semicolon = shuangpin && scheme.types(';'),
        )
    }

    /** 注音's and 手写's keyboards are theirs whatever types. */
    fun isShuangpin(form: Form, im: String): Boolean =
        form != Form.ZhuyinFull && form != Form.ZhuyinNine && im.substringBefore('#') == InputMethodCatalog.SHUANGPIN
}
