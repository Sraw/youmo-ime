/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.os.SystemClock
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    private var interruptions = 0

    private val events = object : VoiceListener.Events {
        override fun loaded() = Unit
        override fun failed(why: VoiceListener.Failure) {
            failures += why
        }
        override fun ready() = Unit
        override fun silent() = Unit
        override fun heard(heard: VoiceLive.Heard) = Unit
        override fun level(level: Float) = Unit
        override fun interrupted() {
            interruptions++
        }
    }

    private val language = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> = emptyMap()
        override suspend fun logProbs(texts: List<String>): FloatArray? = null
    }

    private val audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)

    private fun listener(audio: AudioManager? = null) = VoiceListener(events, { VoiceHotwords.Words.NONE }, language, audio)

    /** [read] gets the size asked for. */
    private class Microphone(private val read: (Int) -> Int) : ShadowAudioRecord.AudioRecordSourceProvider {
        @Volatile
        var opened: AudioRecord? = null

        private val source = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInFloatArray(buffer: FloatArray, offset: Int, size: Int, blocking: Boolean) = read(size)
            override fun readInShortArray(buffer: ShortArray, offset: Int, size: Int, blocking: Boolean) = read(size)
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

    private fun idleMainUntil(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!done() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private class End(val why: String, val end: (VoiceListener, CountDownLatch) -> Unit)

    // the focus taken with the recording and given back however it ends, not held till the talk's text is in
    @Test(timeout = 60_000)
    @Uat("VO-57")
    fun theFocusIsHeldWhileRecordingAndGivenBackHoweverItEnds() {
        val shadow = shadowOf(audio)
        var last: AudioFocusRequest? = null
        assertRows(
            listOf(
                End("lifted: given back once the tail is recorded, before the text is recognized") { listener, let ->
                    listener.finish(SystemClock.uptimeMillis()) { _, _ -> }
                    let.countDown()
                },
                End("cancelled, the keyboard hidden or the field left: the talk closed, a read still blocked in the HAL") { listener, _ ->
                    listener.close()
                },
                End("the microphone failing midway") { _, let ->
                    failing = true
                    let.countDown()
                },
            ),
            { it.why },
        ) { row ->
            failing = false
            val reading = CountDownLatch(1)
            val letGo = CountDownLatch(1)
            val microphone = Microphone { size ->
                reading.countDown()
                letGo.await(WAIT_S, TimeUnit.SECONDS)
                check(!failing) { "the microphone taken away" }
                size
            }
            ShadowAudioRecord.setSourceProvider(microphone)
            val listener = listener(audio)
            try {
                listener.start()
                assertTrue("the microphone was not read", reading.await(WAIT_S, TimeUnit.SECONDS))
                val taken = shadow.lastAudioFocusRequest?.audioFocusRequest
                assertNotSame("not taken as the recording started", last, taken)
                last = taken
                row.end(listener, letGo)
                val record = microphone.opened!!
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
                while (record.recordingState != AudioRecord.RECORDSTATE_STOPPED && System.nanoTime() < until) Thread.sleep(POLL_MS)
                assertEquals(AudioRecord.RECORDSTATE_STOPPED, record.recordingState)
                // well before the blocked read's own WAIT_S: a close gives it back without waiting for the read
                val by = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIVEN_BACK_S)
                while (shadow.lastAbandonedAudioFocusRequest !== taken && System.nanoTime() < by) Thread.sleep(POLL_MS)
                assertSame(taken, shadow.lastAbandonedAudioFocusRequest)
            } finally {
                letGo.countDown()
                listener.close()
            }
        }
    }

    @Volatile
    private var failing = false

    @Test(timeout = 20_000)
    @Uat("VO-57")
    fun switchedOffNothingIsAsked() {
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        ShadowAudioRecord.setSourceProvider(Microphone {
            reading.countDown()
            letGo.await(WAIT_S, TimeUnit.SECONDS)
            0
        })
        val listener = listener(audio = null)
        try {
            listener.start()
            assertTrue("the microphone was not read", reading.await(WAIT_S, TimeUnit.SECONDS))
            assertNull(shadowOf(audio).lastAudioFocusRequest)
        } finally {
            letGo.countDown()
            listener.close()
        }
    }

    // a call ringing mid-talk: the session is told, which stops it as if lifted
    @Test(timeout = 20_000)
    @Uat("VO-57")
    fun anotherAppTakingTheFocusIsToldOnce() {
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        ShadowAudioRecord.setSourceProvider(Microphone {
            reading.countDown()
            letGo.await(WAIT_S, TimeUnit.SECONDS)
            0
        })
        val listener = listener(audio)
        try {
            listener.start()
            assertTrue("the microphone was not read", reading.await(WAIT_S, TimeUnit.SECONDS))
            shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            idleMainUntil { interruptions > 0 }
            assertEquals(1, interruptions)
            assertEquals(emptyList<VoiceListener.Failure>(), failures)
        } finally {
            letGo.countDown()
            listener.close()
        }
    }

    private companion object {
        // a failure is posted to the main thread right after the microphone's stop: well within these polls
        const val SETTLE_POLLS = 20
        const val WAIT_S = 10L
        const val GIVEN_BACK_S = 2L
        const val POLL_MS = 10L
    }
}
