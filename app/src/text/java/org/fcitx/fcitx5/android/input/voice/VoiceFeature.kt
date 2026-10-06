/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.wm.InputWindow

/** A text build's: no voice input, no microphone (the voice flavor's has them). */
object VoiceFeature {
    class Credit(val title: String, val licence: String, val url: String)

    const val AVAILABLE = false

    // the voice flavor's signatures, which this one has to match
    @Suppress("FunctionOnlyReturningConstant")
    fun window(): InputWindow? = null

    @Suppress("UnusedParameter")
    fun credits(context: Context): List<Credit> = emptyList()

    @Suppress("UnusedParameter")
    fun hold(inputView: InputView, overlay: FrameLayout, commit: (String) -> Unit) = Unit
}
