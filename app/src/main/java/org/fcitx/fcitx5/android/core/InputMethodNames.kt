/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.content.res.Resources
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** fcitx names input methods in the phone's language at its start (the app may speak another) and ours in Chinese only. */
object InputMethodNames {

    const val KEYBOARD = InputMethodCatalog.ENGLISH

    // the other tables' ids are InputMethodCatalog.TABLES' keys: the test holds them together
    private val names = mapOf(
        InputMethodCatalog.PINYIN to R.string.im_pinyin,
        // no input methods of their own: pinyin's and 双拼's keyboards, as a setting's "not for" names them
        InputMethodCatalog.T9 to R.string.im_t9,
        InputMethodCatalog.PINYIN_KEYS18 to R.string.im_pinyin_keys18,
        InputMethodCatalog.PINYIN_KEYS to R.string.im_pinyin_keys,
        InputMethodCatalog.ZHUYIN_NINE to R.string.im_zhuyin_nine,
        InputMethodCatalog.ZHUYIN to R.string.im_zhuyin,
        InputMethodCatalog.SHUANGPIN to R.string.im_shuangpin,
        InputMethodCatalog.SHUANGPIN_KEYS18 to R.string.im_shuangpin_keys18,
        InputMethodCatalog.SHUANGPIN_KEYS14 to R.string.im_shuangpin_keys14,
        InputMethodCatalog.SHUANGPIN_NINE to R.string.im_shuangpin_nine,
        InputMethodCatalog.WUBI to R.string.im_wubi,
        "engine-cangjie" to R.string.im_cangjie,
        "engine-ziranma" to R.string.im_ziranma,
        "engine-erbi" to R.string.im_erbi,
        "engine-wubipinyin" to R.string.im_wubipinyin,
        InputMethodCatalog.ENGLISH to R.string.im_english,
    )

    fun of(context: Context, uniqueName: String, fallback: String): String = names[uniqueName]?.let(context::getString) ?: fallback

    fun of(res: Resources, uniqueName: String, fallback: String): String =
        names[uniqueName]?.let(res::getString) ?: fallback

    fun of(context: Context, entry: InputMethodEntry) = of(context, entry.uniqueName, entry.displayName)
}
