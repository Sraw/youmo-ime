/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.database.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.IOException

/** On the app's scope (leaving cancels the page's own right after onStop); starts at once on the main thread, failures shown. */
fun launchOutlivingPage(tag: String, block: suspend () -> Unit): Job =
    FcitxApplication.getInstance().coroutineScope.launch(Dispatchers.Main.immediate) {
        reportingFailure({
            Timber.w(it, tag)
            // the page may be gone by now
            appContext.toast(it)
        }, block)
    }

/** IO, SQL and Security exceptions go to [onFailure]; anything else is a bug and crashes, cancellation passes through. */
internal suspend fun reportingFailure(onFailure: (Throwable) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IOException) {
        onFailure(e)
    } catch (e: SQLException) {
        onFailure(e)
    } catch (e: SecurityException) {
        onFailure(e)
    }
}
