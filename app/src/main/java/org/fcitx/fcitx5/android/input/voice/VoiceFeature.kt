/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.utils.AppUtil

/** Its models are the optional voice pack's: without it a talk asks for it instead. */
object VoiceFeature {
    class Credit(val title: String, val licence: String, val url: String)

    /** Without the voice pack nothing happens (checked again here, the pack gone meanwhile, with a tip only). */
    fun hold(inputView: InputView, pointer: Int, commit: (String) -> Unit) {
        // a talk going on already (tapped, then the space held): that one goes on
        if (inputView.hold != null) return
        // a hold is no place to open a page: said on the keyboard only
        if (!packReady(inputView.context, open = false)) return
        if (!permitted(inputView.context)) return
        VoiceHoldOverlay(inputView, pointer, commit).begin()
    }

    /** Stops the talk going on, or starts one. Not in a password field, nor while a hold is still being recognized. */
    fun tap(inputView: InputView, commit: (String) -> Unit) {
        when (val going = inputView.hold) {
            is VoiceTapOverlay -> going.micTapped()
            null -> if (!inputView.service.inPasswordField && packReady(inputView.context, open = true) && permitted(inputView.context)) {
                VoiceTapOverlay(inputView, commit).begin()
            }
            else -> {}
        }
    }

    private fun packReady(context: Context, open: Boolean): Boolean {
        val status = PackStore.status(PackKind.VOICE)
        val tip = when (status) {
            is PackStatus.Ready -> return true
            is PackStatus.NeedsUpdate -> context.getString(R.string.pack_voice_needed_update, status.needed.minVersion)
            // the app is what needs updating, not the pack
            is PackStatus.NeedsNewerApp -> context.getString(R.string.pack_voice_needed_app, status.manifest.version)
            is PackStatus.Damaged -> context.getString(R.string.pack_voice_damaged)
            PackStatus.Missing -> context.getString(R.string.pack_voice_needed)
        }
        EngineNotices.tip(tip)
        if (open) AppUtil.launchMainToPacks(context)
        return false
    }

    private fun permitted(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        VoicePermissionActivity.start(context)
        return false
    }

    fun credits(context: Context) = listOf(
        Credit(context.getString(R.string.voice_model_credit), "Apache-2.0", "https://github.com/Gilgamesh-J/X-ASR"),
        // the encoder's fine-tuning kept its English with LibriSpeech, whose licence asks for the notice
        Credit("LibriSpeech", "CC-BY-4.0", "https://www.openslr.org/12/"),
        // trained on data for non-commercial use only: the voice pack is licensed so, apart from the app. Its FineWeb-2
        // sentences are on the engine data's FineWeb-2 row (LicensesFragment)
        Credit(context.getString(R.string.voice_tagger_credit), "CC-BY-NC-SA-4.0", "https://creativecommons.org/licenses/by-nc-sa/4.0/"),
        Credit("MagicData-RAMC (Magic Data Technology)", "CC-BY-NC-ND-4.0", "https://www.openslr.org/123/"),
        Credit("RealTalk-CN (BAAI)", "CC-BY-NC-SA-4.0", "https://huggingface.co/datasets/BAAI/RealTalk-CN"),
        Credit("CS2W (Guo et al., EMNLP 2023; sentences from MagicData-RAMC)", "CC-BY-SA-4.0", "https://github.com/guozishan/CS2W"),
        Credit("Chinese ELECTRA (hfl/chinese-electra-180g-small-ex-discriminator)", "Apache-2.0", "https://github.com/ymcui/Chinese-ELECTRA"),
        Credit("sherpa-onnx:1.13.8", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE"),
        // what sherpa-onnx is built with, in its library in the APK; its onnxruntime is credited with handwriting
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
