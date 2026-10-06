/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.wm.InputWindow

/** A voice build's: the panel the bar's microphone opens (the text flavor's has none). */
object VoiceFeature {
    class Credit(val title: String, val licence: String, val url: String)

    const val AVAILABLE = true

    fun window(): InputWindow = VoiceWindow()

    /** on the licences page: the model's licence asks for its source, authors and name */
    fun credits(context: Context) = listOf(
        Credit(context.getString(R.string.voice_model_credit), "FunASR Model License 1.1", "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE"),
        Credit("sherpa-onnx:1.13.8", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE"),
        Credit("silero-vad", "MIT", "https://github.com/snakers4/silero-vad/blob/master/LICENSE"),
    )
}
