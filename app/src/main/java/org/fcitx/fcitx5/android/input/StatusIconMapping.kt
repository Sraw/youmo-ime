/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry

object StatusIconMapping {
    @DrawableRes
    fun fromEntry(entry: InputMethodEntry): Int {
        when (entry.icon) {
            "fcitx-pinyin" -> return R.drawable.ic_status_pinyin
            "fcitx-shuangpin" -> return R.drawable.ic_status_shuangpin
            "fcitx-wubi", "fcitx-wbpy" -> return R.drawable.ic_status_wubi
            "fcitx-cangjie" -> return R.drawable.ic_status_cangjie
            "fcitx-ziranma" -> return R.drawable.ic_status_ziranma
            "fcitx-zhengma", "fcitx_zhengma" -> return R.drawable.ic_status_zhengma
        }
        when (entry.languageCode) {
            "en", "en_US" -> return R.drawable.ic_status_en
            "zh", "zh_CN", "zh_TW", "zh_HK" -> return R.drawable.ic_status_zh
            "ja" -> return R.drawable.ic_status_hiragana
            "ko" -> return R.drawable.ic_status_hangul
            "si" -> return R.drawable.ic_status_sayura
            "th" -> return R.drawable.ic_status_thai
            "vi" -> return R.drawable.ic_status_vi
        }
        return R.drawable.ic_baseline_keyboard_24
    }
}
