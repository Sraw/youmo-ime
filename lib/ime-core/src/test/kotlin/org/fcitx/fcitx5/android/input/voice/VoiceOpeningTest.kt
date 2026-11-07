/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceOpeningTest {

    private val steps = mutableListOf<String>()

    private fun open(load: () -> String, use: (String?) -> Unit = { steps += "use $it" }) = VoiceOpening.run(
        start = { steps += "start" },
        load = { load().also { steps += "load" } },
        use = use,
        stop = { steps += "stop" },
        unload = { steps += "unload $it" },
        failed = { steps += "failed ${it.message}" },
    )

    @Test
    fun theDetectorLoadsOnlyOnceTheMicrophoneIsOn() {
        open({ "vad" })
        assertEquals(listOf("start", "load", "use vad", "stop", "unload vad"), steps)
    }

    @Test
    fun aDetectorFailingLeavesNoneAndTheMicrophoneGoesOn() {
        open({ throw UnsatisfiedLinkError("no sherpa") })
        // no detector: VoiceQuiet keeps all (VoiceQuietTest.noDetectorKeepsEverything); the record let go
        assertEquals(listOf("start", "failed no sherpa", "use null", "stop"), steps)
    }

    @Test
    fun theRecordIsLetGoWhateverBreaks() {
        assertThrows(IllegalStateException::class.java) { open({ "vad" }) { error("read") } }
        assertEquals(listOf("start", "load", "stop", "unload vad"), steps)
        steps.clear()
        assertThrows(IllegalStateException::class.java) {
            VoiceOpening.run<String>({ error("busy") }, { "vad" }, {}, { steps += "stop" }, { steps += "unload" })
        }
        assertEquals(listOf("stop"), steps)
    }
}
