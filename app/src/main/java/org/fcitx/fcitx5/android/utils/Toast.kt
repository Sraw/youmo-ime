/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R

fun Context.toast(string: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, string, duration).showBriefly(this)
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).showBriefly(this)
}

// Android's 2 s and 3.5 s are longer than wanted: 1 s and 2 s
internal const val TOAST_SHORT_MS = 1000
internal const val TOAST_LONG_MS = 2000

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

// on the main thread, where every toast here is shown
private var last: Toast? = null
private val cancelLast = Runnable {
    last?.cancel()
    last = null
}

/**
 * Cancelled after our length or the user's accessibility timeout. The newest replaces one still shown: the system
 * queues toasts, and a queued one would spend its time waiting. [Toast.cancel] still works on text toasts from 11 on.
 */
fun Toast.showBriefly(context: Context) {
    mainHandler.removeCallbacks(cancelLast)
    last?.cancel()
    last = this
    show()
    val ms = if (duration == Toast.LENGTH_LONG) TOAST_LONG_MS else TOAST_SHORT_MS
    val shown = context.getSystemService(AccessibilityManager::class.java)
        ?.getRecommendedTimeoutMillis(ms, AccessibilityManager.FLAG_CONTENT_TEXT) ?: ms
    mainHandler.postDelayed(cancelLast, shown.toLong())
}

fun Context.toast(t: Throwable, duration: Int = Toast.LENGTH_SHORT) {
    toast(t.localizedMessage ?: t.stackTraceToString(), duration)
}

suspend fun <T> Context.toast(result: Result<T>, duration: Int = Toast.LENGTH_SHORT) {
    withContext(Dispatchers.Main.immediate) {
        result
            .onSuccess { toast(R.string.done, duration) }
            .onFailure { toast(it, duration) }
    }
}
