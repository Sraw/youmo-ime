/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.annotation.SuppressLint
import timber.log.Timber

@SuppressLint("PrivateApi")
fun getSystemProperty(key: String): String {
    return try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as String
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // a ROM without the hidden class, or one that hides or guards it: no such property, not a crash at start
        Timber.d(e, "no system property %s", key)
        ""
    }
}
