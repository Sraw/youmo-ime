/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Other apps' playback paused while the microphone records, so music is not heard as speech: a transient focus,
 * [take]n as recording starts and given back as it stops. [lost] (main thread): another app took it, a call ringing
 * or an alarm. Thread-safe.
 */
class VoiceFocus(private val audio: AudioManager, private val lost: () -> Unit) {

    // not _MAY_DUCK: music only made quieter is still heard
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(::changed, Handler(Looper.getMainLooper()))
        .build()

    private var held = false
    private var over = false
    private var told = false

    /** Not after [giveBack]: a capture that starts after its talk closed does not keep the focus for good. */
    @Synchronized
    fun take() {
        if (over || held) return
        // refused during a call, or where the system counts a keyboard as in the background: recorded all the same
        held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) Timber.i("voice: audio focus refused")
    }

    @Synchronized
    fun giveBack() {
        over = true
        if (!held) return
        held = false
        audio.abandonAudioFocusRequest(request)
    }

    // a duck (a notification, a navigation prompt) is not ours to act on: we play nothing
    private fun changed(change: Int) {
        val taken = synchronized(this) {
            val now = held && !told && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            if (now) told = true
            now
        }
        if (taken) lost()
    }
}
