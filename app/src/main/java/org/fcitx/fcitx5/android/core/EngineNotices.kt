/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast

/**
 * What the engine says to the user, from any thread: on the keyboard if it is shown (§3: one notice
 * there, and Android 13 drops an input method's toasts), a toast with none. The input method sets
 * [sayer] while it runs: core does not reach into input (F3539).
 */
object EngineNotices {

    /** [message] said on the main thread; [steady], a failure (NoticeState.show). */
    fun interface Sayer {
        fun say(message: String, steady: Boolean)
    }

    @Volatile
    var sayer: Sayer? = null

    /** A failure, said as a key fails: not put away by the key typed after it, and long (rule 4). */
    fun failed(@StringRes id: Int) = post(steady = true) { appContext.getString(id) }

    /**
     * A plain tip, what fcitx's own addons say (androidnotification's showTip: punctuation, full
     * width, a group switched): said for 2 s and typed away, as any line said (rule 5, F3464).
     */
    fun tip(message: String) = post(steady = false) { message }

    // looked up when it is said, on the main thread
    private fun post(steady: Boolean, message: () -> String) = ContextCompat.getMainExecutor(appContext).execute {
        val said = message()
        sayer?.say(said, steady) ?: appContext.toast(said, if (steady) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
    }
}
