/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.wm.InputWindow

/** A voice build's: the panel the bar's microphone opens (the text flavor's has none). */
object VoiceFeature {
    class Credit(val title: String, val licence: String, val url: String)

    const val AVAILABLE = true

    fun window(): InputWindow? = VoiceWindow()

    /**
     * Hold to talk: the long press on space has fired, its finger ([pointer]) still down; what is
     * said is [commit]ted once it lifts. Without the microphone permission, asks for it instead.
     */
    fun hold(inputView: InputView, overlay: FrameLayout, pointer: Int, commit: (String) -> Unit) {
        val context = inputView.context
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoicePermissionActivity.start(context)
            return
        }
        VoiceHoldOverlay(inputView, overlay, pointer, commit).begin()
    }

    /** on the licences page */
    fun credits(context: Context) = listOf(
        Credit(context.getString(R.string.voice_model_credit), "Apache-2.0", "https://github.com/Gilgamesh-J/X-ASR"),
        // the encoder's fine-tuning kept its English with LibriSpeech, whose licence asks for the notice
        Credit("LibriSpeech", "CC-BY-4.0", "https://www.openslr.org/12/"),
        Credit("sherpa-onnx:1.13.8", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE"),
        Credit("silero-vad", "MIT", "https://github.com/snakers4/silero-vad/blob/master/LICENSE"),
        // what sherpa-onnx is built with (lib/sherpa-onnx/README.md), in its library in the APK; its
        // onnxruntime is handwriting's too, credited on the page with it
        Credit("kaldi-native-fbank:1.22.3", "Apache-2.0", "https://github.com/csukuangfj/kaldi-native-fbank/blob/master/LICENSE"),
        Credit("kissfft", "BSD-3-Clause", "https://github.com/mborgerding/kissfft/blob/master/COPYING"),
        Credit("kaldi-decoder:0.3.0", "Apache-2.0", "https://github.com/k2-fsa/kaldi-decoder/blob/master/LICENSE"),
        Credit("kaldifst:1.8.0", "Apache-2.0", "https://github.com/k2-fsa/kaldifst/blob/master/LICENSE"),
        Credit("openfst:1.8.5", "Apache-2.0", "https://github.com/csukuangfj/openfst"),
        Credit("simple-sentencepiece:0.7", "Apache-2.0", "https://github.com/pkufool/simple-sentencepiece/blob/master/LICENSE"),
        Credit("eigen:5.0.1", "MPL-2.0", "https://gitlab.com/libeigen/eigen/-/blob/master/COPYING.MPL2"),
        Credit("nlohmann/json:3.12.0", "MIT", "https://github.com/nlohmann/json/blob/develop/LICENSE.MIT"),
    )
}
