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

// Android's two lengths, 2 s and 3.5 s, are longer than the user wants (2026-11-15): short 1 s, long 2 s
internal const val TOAST_SHORT_MS = 1000
internal const val TOAST_LONG_MS = 2000

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

// the one shown last, till its time is up (on the main thread, where every toast here is shown)
private var last: Toast? = null
private val cancelLast = Runnable {
    last?.cancel()
    last = null
}

/**
 * Shown, then cancelled after [TOAST_SHORT_MS] or [TOAST_LONG_MS] by its [Toast.getDuration], or as
 * long as the user asked the system for (accessibility's longer timeouts; from Android 10 the system
 * lengthens a toast by the same recommended timeout itself: measured on API 29 and 36, 10 s asked,
 * 10 s shown). The newest takes the place of one still shown, as the keyboard's notice does: the
 * system queues toasts, and one waiting behind another would spend its time, counted from here, in
 * the queue. A text toast is the system's to draw from Android 11 on; [Toast.cancel] still takes
 * back the app's own (NotificationManagerService.cancelToast by its token).
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
