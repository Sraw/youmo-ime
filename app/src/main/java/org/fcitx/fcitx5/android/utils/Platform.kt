/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.ClipData
import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.FcitxApplication
import kotlin.math.roundToInt

val appContext: Context
    get() = FcitxApplication.getInstance().applicationContext

fun Configuration.isDarkMode(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

val PackageInfo.versionCodeCompat: Long
    get() = longVersionCode

fun ClipData.timestamp() = description.timestamp

@Suppress("DEPRECATION")
fun InputMethodService.forceShowSelf() {
    requestShowSelf(InputMethodManager.SHOW_FORCED)
}

/**
 * When the EditorInfo has [InputType.TYPE_NULL] and [EditorInfo.IME_NULL], it's likely not an input field
 */
fun EditorInfo.isTypeNull(): Boolean {
    return inputType == InputType.TYPE_NULL && imeOptions == EditorInfo.IME_NULL
}

fun Int.alpha(a: Float) = ColorUtils.setAlphaComponent(this, (a * 0xff).roundToInt())
