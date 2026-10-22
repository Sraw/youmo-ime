/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Forget
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LogRewritesTest {
    // F3161: RecordStore's emptied mark after the header is no record: the last act after it is still the last, taken back, the mark gone
    @Test
    fun anActAfterAnEmptiedMarkIsTakenBack() {
        val header = LearnRecords.FORMAT.header()
        val act = Forget(10, 1, "幽默")
        val log = header + LearnRecords.FORMAT.record(LearnLog.EMPTIED) {} + LearnRecords.encode(act)
        val out = ByteArrayOutputStream()
        assertTrue(LogRewrites.dropLast(log, listOf(act), out))
        assertArrayEquals(header, out.toByteArray())
        // the mark is not taken for an act either
        assertFalse(LogRewrites.dropLast(log, listOf(act, act), ByteArrayOutputStream()))
    }
}
