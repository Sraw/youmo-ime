/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import org.fcitx.fcitx5.android.R

/**
 * An input method's name in the app's language. fcitx names them in its own (the phone's, read as
 * it starts, while the app may speak another), and the app's own input methods in Chinese only;
 * an input method the app does not know keeps fcitx's name.
 */
object InputMethodNames {

    private val names = mapOf(
        "engine-pinyin" to R.string.im_pinyin,
        "engine-shuangpin" to R.string.im_shuangpin,
        "engine-wubi" to R.string.im_wubi,
        "engine-cangjie" to R.string.im_cangjie,
        "engine-ziranma" to R.string.im_ziranma,
        "engine-erbi" to R.string.im_erbi,
        "engine-wubipinyin" to R.string.im_wubipinyin,
        "keyboard-us" to R.string.im_english,
    )

    fun of(context: Context, uniqueName: String, fallback: String): String =
        names[uniqueName]?.let(context::getString) ?: fallback

    fun of(context: Context, entry: InputMethodEntry) = of(context, entry.uniqueName, entry.displayName)
}
