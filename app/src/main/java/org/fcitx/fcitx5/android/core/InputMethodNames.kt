/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * An input method's name in the app's language. fcitx names them in its own (the phone's, read as
 * it starts, while the app may speak another), and the app's own input methods in Chinese only;
 * an input method the app does not know keeps fcitx's name.
 */
object InputMethodNames {

    /** fcitx's English (US) keyboard: the engine's English, [Engines.ENGLISH], under the name fcitx gives it. */
    const val KEYBOARD = Engines.ENGLISH

    // the other tables' ids are the keys of Engines.TABLES, which has a constant for Wubi alone: the test holds them together
    private val names = mapOf(
        Engines.PINYIN to R.string.im_pinyin,
        // no input method of its own: pinyin on the 14 keys, as a setting's "not for" names it
        Engines.PINYIN_KEYS to R.string.im_pinyin_keys,
        Engines.SHUANGPIN to R.string.im_shuangpin,
        // as PINYIN_KEYS: 双拼 on its 18 keys and on the nine keys
        Engines.SHUANGPIN_KEYS18 to R.string.im_shuangpin_keys18,
        Engines.SHUANGPIN_NINE to R.string.im_shuangpin_nine,
        Engines.T9 to R.string.im_t9,
        Engines.WUBI to R.string.im_wubi,
        "engine-cangjie" to R.string.im_cangjie,
        "engine-ziranma" to R.string.im_ziranma,
        "engine-erbi" to R.string.im_erbi,
        "engine-wubipinyin" to R.string.im_wubipinyin,
        Engines.HANDWRITING to R.string.im_handwriting,
        Engines.ENGLISH to R.string.im_english,
    )

    fun of(context: Context, uniqueName: String, fallback: String): String =
        names[uniqueName]?.let(context::getString) ?: fallback

    fun of(context: Context, entry: InputMethodEntry) = of(context, entry.uniqueName, entry.displayName)
}
