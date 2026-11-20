/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.graphics.Typeface.BOLD
import android.graphics.Typeface.ITALIC
import android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.core.text.buildSpannedString

/** The rendering half of [FormattedText], whose data half is in `:lib:ime-core` and cannot reach Android. */
fun FormattedText.toSpannedString(highlightColor: Int) = buildSpannedString {
    for (i in strings.indices) {
        val str = strings[i]
        val fmt = flags[i]
        val start = length
        append(str)
        if (fmt == TextFormatFlag.NoFlag.flag) continue
        val end = length
        if (fmt.hasFlag(TextFormatFlag.Underline)) {
            setSpan(UnderlineSpan(), start, end, SPAN_INCLUSIVE_EXCLUSIVE)
        }
        if (fmt.hasFlag(TextFormatFlag.HighLight)) {
            setSpan(BackgroundColorSpan(highlightColor), start, end, SPAN_INCLUSIVE_EXCLUSIVE)
        }
        if (fmt.hasFlag(TextFormatFlag.Bold)) {
            setSpan(StyleSpan(BOLD), start, end, SPAN_INCLUSIVE_EXCLUSIVE)
        }
        if (fmt.hasFlag(TextFormatFlag.Strike)) {
            setSpan(StrikethroughSpan(), start, end, SPAN_INCLUSIVE_EXCLUSIVE)
        }
        if (fmt.hasFlag(TextFormatFlag.Italic)) {
            setSpan(StyleSpan(ITALIC), start, end, SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }
}
