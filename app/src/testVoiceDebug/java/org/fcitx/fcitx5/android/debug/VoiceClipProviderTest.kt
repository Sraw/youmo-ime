/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.debug

import android.app.Application
import android.net.Uri
import org.fcitx.fcitx5.android.input.voice.VoiceClipMic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VoiceClipProviderTest {

    private lateinit var provider: VoiceClipProvider
    private val dir get() = File(RuntimeEnvironment.getApplication().filesDir, "voiceclips")

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(VoiceClipProvider::class.java).create().get()
        VoiceClipMic.clear()
        dir.mkdirs()
        // raw 16-bit PCM: two samples each
        File(dir, "a.wav").writeBytes(ByteArray(4))
        File(dir, "b.wav").writeBytes(ByteArray(4))
        ShadowBinder.setCallingUid(2000)
    }

    @After
    fun tearDown() {
        VoiceClipMic.clear()
        ShadowBinder.reset()
    }

    private fun state() = provider.call("state", null, null).getString("state")

    @Test
    fun theShellAndRootAreLetIn() {
        for (uid in listOf(2000, 0, 1002000)) {
            ShadowBinder.setCallingUid(uid)
            assertEquals("on=false talking=false playing=false queued=", state())
        }
    }

    @Test
    fun anAppIsNot() {
        ShadowBinder.setCallingUid(10123)
        assertThrows(SecurityException::class.java) { state() }
        assertThrows(SecurityException::class.java) {
            provider.openFile(Uri.parse("content://x.voiceclip/a.wav"), "r")
        }
    }

    @Test
    fun openFileTakesANameNotAPath() {
        for (bad in listOf("content://x.voiceclip/..", "content://x.voiceclip/%2F..%2Fx")) {
            assertThrows(IllegalArgumentException::class.java) { provider.openFile(Uri.parse(bad), "r") }
        }
        provider.openFile(Uri.parse("content://x.voiceclip/c.wav"), "w").close()
        assertEquals(true, File(dir, "c.wav").isFile)
    }

    @Test
    fun theQueueIsInOrderBlanksSkipped() {
        provider.call("queue", "a.wav, 800 ,,b.wav", null)
        assertEquals("on=true talking=false playing=false queued=a.wav,800ms,b.wav", state())
    }

    @Test
    fun aClipMissingLeavesTheQueueAsItWas() {
        provider.call("queue", "a.wav", null)
        assertThrows(IllegalArgumentException::class.java) { provider.call("queue", "b.wav,nope.wav", null) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("queue", "../a.wav", null) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("queue", "a.wav,60001", null) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("queue", ".", null) }
        assertThrows(IllegalArgumentException::class.java) { provider.openFile(Uri.parse("content://x.voiceclip/."), "r") }
        // over 16 MB
        java.io.RandomAccessFile(File(dir, "big.wav"), "rw").use { it.setLength((16L shl 20) + 2) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("queue", "big.wav", null) }
        assertEquals("on=true talking=false playing=false queued=a.wav", state())
    }

    @Test
    fun clearEmptiesItAndTurnsItOff() {
        provider.call("queue", "a.wav", null)
        provider.call("clear", null, null)
        assertEquals("on=false talking=false playing=false queued=", state())
        provider.call("on", null, null)
        assertEquals("on=true talking=false playing=false queued=", state())
    }
}
