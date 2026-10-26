/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBacklogTest {

    private fun windowOf(v: Float) = FloatArray(4) { v }

    @Test
    @Uat("VO-26")
    fun whatIsSaidWhileTheModelsLoadIsKeptInOrder() {
        val backlog = VoiceBacklog(1000)
        // the VAD still loading: each window read, none handed on
        for (i in 1..5) assertTrue(backlog.window(windowOf(i.toFloat())).isEmpty())
        val held = backlog.start()
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), held.map { it[0] })
        // from now on as read
        assertEquals(listOf(6f), backlog.window(windowOf(6f)).map { it[0] })
        assertEquals(0L, backlog.dropped)
    }

    @Test
    fun aLoadLongerThanTheRoomDropsTheOldest() {
        val backlog = VoiceBacklog(8)
        for (i in 1..4) backlog.window(windowOf(i.toFloat()))
        assertEquals(listOf(3f, 4f), backlog.start().map { it[0] })
        assertEquals(8L, backlog.dropped)
    }

    @Test
    @Uat("VO-26")
    fun cancelledWhileLoadingNothingIsHandedOn() {
        val backlog = VoiceBacklog(1000)
        backlog.window(windowOf(1f))
        backlog.drop()
        assertEquals(0, backlog.heldCount)
        assertTrue(backlog.start().isEmpty())
    }
}
