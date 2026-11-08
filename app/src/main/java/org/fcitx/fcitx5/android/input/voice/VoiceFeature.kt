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

/**
 * Voice input: holding the space and tapping the bar's microphone talk. Offered in every build;
 * its models are the voice pack's, which is optional: without it (or with one this app does not
 * read) a talk asks for it instead. The microphone is asked for at the first talk.
 */
object VoiceFeature {
    class Credit(val title: String, val licence: String, val url: String)

    /**
     * Hold to talk: the long press on space has fired, its finger ([pointer]) still down; what is
     * said is [commit]ted once it lifts. Without the voice pack, says so; without the microphone
     * permission, asks for it instead.
     */
    fun hold(inputView: InputView, pointer: Int, commit: (String) -> Unit) {
        // a talk going on already (tapped, then the space held): that one goes on
        if (inputView.hold != null) return
        // a hold is no place to open a page: said on the keyboard only
        if (!packReady(inputView.context, open = false)) return
        if (!permitted(inputView.context)) return
        VoiceHoldOverlay(inputView, pointer, commit).begin()
    }

    /**
     * Tap to talk: the bar's microphone (or a key's shortcut) tapped. Stops the talk going on, or
     * starts one, its mask over the keys; what is said is [commit]ted (the settled part as it is
     * said: the session's own). Not in a password field, nor while a hold is still being
     * recognized. Without the voice pack, opens the 资源包 page; without the microphone permission,
     * asks for it instead.
     */
    fun tap(inputView: InputView, commit: (String) -> Unit) {
        when (val going = inputView.hold) {
            is VoiceTapOverlay -> going.micTapped()
            null -> if (!inputView.service.inPasswordField && packReady(inputView.context, open = true) && permitted(inputView.context)) {
                VoiceTapOverlay(inputView, commit).begin()
            }
            else -> {}
        }
    }

    // the voice pack there, one this app reads; else said on the keyboard, and the packs' page opened if [open]
    private fun packReady(context: Context, open: Boolean): Boolean {
        val status = PackStore.status(PackKind.VOICE)
        if (status is PackStatus.Ready) return true
        if (status is PackStatus.NeedsUpdate) {
            EngineNotices.tip(context.getString(R.string.pack_voice_needed_update, status.needed.minVersion))
        } else if (status is PackStatus.NeedsNewerApp) {
            // the pack is newer than this app reads: the app is what needs updating, not the pack
            EngineNotices.tip(context.getString(R.string.pack_voice_needed_app, status.manifest.version))
        } else if (status is PackStatus.Damaged) {
            EngineNotices.tip(context.getString(R.string.pack_voice_damaged))
        } else {
            EngineNotices.tip(context.getString(R.string.pack_voice_needed))
        }
        if (open) AppUtil.launchMainToPacks(context)
        return false
    }

    // the microphone allowed; else the asking page opened
    private fun permitted(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        VoicePermissionActivity.start(context)
        return false
    }

    /** on the licences page */
    fun credits(context: Context) = listOf(
        Credit(context.getString(R.string.voice_model_credit), "Apache-2.0", "https://github.com/Gilgamesh-J/X-ASR"),
        // the encoder's fine-tuning kept its English with LibriSpeech, whose licence asks for the notice
        Credit("LibriSpeech", "CC-BY-4.0", "https://www.openslr.org/12/"),
        Credit("sherpa-onnx:1.13.8", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE"),
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
