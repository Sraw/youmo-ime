/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioRecord
import android.os.Looper
import android.os.SystemClock
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real [VoiceListener]'s microphone half over Robolectric's AudioRecord, the models never loaded; the recognizer's half has no JVM seam. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class VoiceListenerMicrophoneTest {

    private val failures = ArrayList<VoiceListener.Failure>()

    private val events = object : VoiceListener.Events {
        override fun loaded() = Unit
        override fun failed(why: VoiceListener.Failure) {
            failures += why
        }
        override fun ready() = Unit
        override fun silent() = Unit
        override fun heard(heard: VoiceLive.Heard) = Unit
        override fun level(level: Float) = Unit
    }

    private val language = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> = emptyMap()
        override suspend fun logProbs(texts: List<String>): FloatArray? = null
    }

    private fun listener() = VoiceListener(events, { VoiceHotwords.Words.NONE }, language)

    private class Microphone(private val read: () -> Int) : ShadowAudioRecord.AudioRecordSourceProvider {
        @Volatile
        var opened: AudioRecord? = null

        private val source = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInFloatArray(buffer: FloatArray, offset: Int, size: Int, blocking: Boolean) = read()
            override fun readInShortArray(buffer: ShortArray, offset: Int, size: Int, blocking: Boolean) = read()
        }

        override fun get(record: AudioRecord): ShadowAudioRecord.AudioRecordSource {
            opened = record
            return source
        }
    }

    @After
    fun theMicrophoneAsItWas() {
        ShadowAudioRecord.clearSource()
    }

    // closed while the models still loaded, the stop never came: a read blocked in the HAL left the microphone recording
    @Test(timeout = 20_000)
    @Uat("VO-40")
    fun closedWhileTheModelsLoadTheMicrophoneIsStopped() {
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val microphone = Microphone {
            reading.countDown()
            letGo.await(WAIT_S, TimeUnit.SECONDS)
            0
        }
        ShadowAudioRecord.setSourceProvider(microphone)
        val listener = listener()
        try {
            listener.start()
            assertTrue("the microphone was not read", reading.await(WAIT_S, TimeUnit.SECONDS))
            val record = microphone.opened!!
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
            listener.close()
            assertEquals(AudioRecord.RECORDSTATE_STOPPED, record.recordingState)
        } finally {
            letGo.countDown()
            listener.close()
        }
    }

    // a read throwing was said as 语音识别出错; it is the microphone's
    @Test(timeout = 20_000)
    @Uat("VO-39")
    fun aReadThrowingIsTheMicrophonesFailure() {
        ShadowAudioRecord.setSourceProvider(Microphone { throw IllegalStateException("the microphone taken away") })
        val listener = listener()
        try {
            listener.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
            while (failures.isEmpty() && System.nanoTime() < until) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(POLL_MS)
            }
            assertEquals(listOf(VoiceListener.Failure.NoMicrophone), failures)
        } finally {
            listener.close()
        }
    }

    // a read throwing once the finger is lifted is no failure: what was said is in, typed as at the tail's end
    @Test(timeout = 20_000)
    @Uat("VO-39")
    fun aReadThrowingOnceLiftedIsNoFailure() {
        val reading = CountDownLatch(1)
        val lifted = CountDownLatch(1)
        val microphone = Microphone {
            reading.countDown()
            lifted.await(WAIT_S, TimeUnit.SECONDS)
            throw IllegalStateException("the microphone taken away")
        }
        ShadowAudioRecord.setSourceProvider(microphone)
        val listener = listener()
        try {
            listener.start()
            assertTrue("the microphone was not read", reading.await(WAIT_S, TimeUnit.SECONDS))
            val record = microphone.opened!!
            listener.finish(SystemClock.uptimeMillis()) { _, _ -> }
            lifted.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
            while (record.recordingState != AudioRecord.RECORDSTATE_STOPPED && System.nanoTime() < until) Thread.sleep(POLL_MS)
            assertEquals(AudioRecord.RECORDSTATE_STOPPED, record.recordingState)
            repeat(SETTLE_POLLS) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(POLL_MS)
            }
            assertEquals(emptyList<VoiceListener.Failure>(), failures)
        } finally {
            lifted.countDown()
            listener.close()
        }
    }

    private companion object {
        // a failure is posted to the main thread right after the microphone's stop: well within these polls
        const val SETTLE_POLLS = 20
        const val WAIT_S = 10L
        const val POLL_MS = 10L
    }
}
