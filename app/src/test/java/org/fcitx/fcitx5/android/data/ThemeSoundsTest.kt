/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.os.Handler
import android.os.HandlerThread
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 060-theme-sounds-icons §1.4: the theme's key sounds on their own thread, a pool for each theme wanted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeSoundsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val thread = HandlerThread("theme-sounds-test").apply { start() }
    private val sounds = ThemeSounds(Handler(thread.looper))

    @After
    fun stop() {
        thread.quit()
    }

    private fun idle() = shadowOf(thread.looper).idle()

    // a 100 ms 8-bit PCM WAV of [bytes] samples at 8 kHz, mono
    private fun wav(name: String, data: Int = 800): File {
        fun le(v: Int, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
        val fmt = "fmt ".toByteArray() + le(16, 4) + le(1, 2) + le(1, 2) + le(8000, 4) + le(8000, 4) + le(1, 2) + le(8, 2)
        val body = "WAVE".toByteArray() + fmt + "data".toByteArray() + le(data, 4) + ByteArray(data)
        return tmp.newFile(name).apply { writeBytes("RIFF".toByteArray() + le(body.size, 4) + body) }
    }

    private fun loaded(vararg files: File) {
        val pool = sounds.currentPool!!
        files.forEach { shadowOf(pool).notifyPathLoaded(it.path, true) }
        idle()
    }

    @Test
    fun eachClassPlaysItsOwnOnceLoaded() {
        val letter = wav("letter.wav")
        val space = wav("space.wav")
        sounds.use("a") { mapOf(SoundKey.LETTER to letter.path, SoundKey.SPACE to space.path) }
        idle()
        assertEquals(setOf(SoundKey.LETTER, SoundKey.SPACE), sounds.given)
        // not loaded yet: the caller plays the system's click
        assertFalse(sounds.play(SoundKey.SPACE, 0.5f))
        loaded(letter, space)
        assertEquals(setOf(SoundKey.LETTER, SoundKey.SPACE), sounds.loaded)
        assertTrue(sounds.play(SoundKey.SPACE, 0.5f))
        idle()
        val pool = shadowOf(sounds.currentPool!!)
        assertTrue(pool.wasPathPlayed(space.path))
        assertFalse(pool.wasPathPlayed(letter.path))
        assertEquals(0.5f, pool.getPathPlaybacks(space.path).single().leftVolume)
    }

    @Test
    fun aLoadOfAPoolReplacedIsDropped() {
        val letter = wav("letter.wav")
        sounds.use("a") { mapOf(SoundKey.LETTER to letter.path) }
        idle()
        val old = sounds.currentPool!!
        // the same theme imported again (another stamp): a pool anew, files read again
        var read = 0
        sounds.use("a2") { read++; mapOf(SoundKey.LETTER to letter.path) }
        idle()
        assertEquals(1, read)
        assertNotSame(old, sounds.currentPool)
        shadowOf(old).notifyPathLoaded(letter.path, true)
        idle()
        assertTrue(sounds.loaded.isEmpty())
        // the same again: nothing anew
        val now = sounds.currentPool
        sounds.use("a2") { read++; emptyMap() }
        idle()
        assertEquals(1, read)
        assertSame(now, sounds.currentPool)
    }

    @Test
    fun aFailedLoadIsTheSystems() {
        val letter = wav("letter.wav")
        sounds.use("a") { mapOf(SoundKey.LETTER to letter.path) }
        idle()
        shadowOf(sounds.currentPool!!).notifyPathLoaded(letter.path, false)
        idle()
        assertFalse(sounds.play(SoundKey.LETTER, 1f))
    }

    @Test
    fun releasedHoldsNoPool() {
        val letter = wav("letter.wav")
        sounds.use("a") { mapOf(SoundKey.LETTER to letter.path) }
        idle()
        loaded(letter)
        sounds.release()
        idle()
        assertNull(sounds.currentPool)
        assertTrue(sounds.loaded.isEmpty())
        assertFalse(sounds.play(SoundKey.LETTER, 1f))
        // none wanted: none made
        sounds.use(null) { emptyMap() }
        idle()
        assertNull(sounds.currentPool)
    }

    @Test
    fun aFileTooBigOrNoSoundIsNotLoaded() {
        val big = tmp.newFile("big.wav").apply { writeBytes(ByteArray(100 * 1024 + 1)) }
        val long = wav("long.wav", data = 8008)
        val missing = File(tmp.root, "gone.wav")
        val good = wav("good.wav")
        sounds.use("a") {
            mapOf(SoundKey.LETTER to big.path, SoundKey.SPACE to long.path, SoundKey.ENTER to missing.path, SoundKey.BACKSPACE to good.path)
        }
        idle()
        // only the good one handed to the pool: the shadow knows no load of the others
        for (f in listOf(big, long, missing)) {
            assertTrue(runCatching { shadowOf(sounds.currentPool!!).notifyPathLoaded(f.path, true) }.isFailure)
        }
        loaded(good)
        assertEquals(setOf(SoundKey.BACKSPACE), sounds.loaded)
    }
}
