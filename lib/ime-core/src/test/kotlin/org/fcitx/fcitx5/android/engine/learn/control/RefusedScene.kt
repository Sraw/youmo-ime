/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.runtime.RefusingDisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException

/** The record on a disk that refuses: 指导 learned and written; a forget is the act. */
internal class RefusedScene {
    val dir = MemoryFiles()
    val disk = RefusingDisk()
    val told = ArrayList<IOException>()
    val log = LearnLog(disk.file(dir.file(LearnLog.FILE)), FakeClock(), compactAt = 1L shl 30, openAppend = disk::open).also { it.open() }
    val control = LearningControl({ log }, onError = told::add).also { it.attach(log) }

    init {
        guide()
    }

    // 指导 picked over 知道 after 老师
    fun guide() = checkNotNull(log.record(listOf(corrected("zhidao", "指导", "zhi dao", prev = "老师", shown = "知道"))))

    // 点了 learned too
    fun there() = checkNotNull(log.record(listOf(corrected("dl", "点了", "dian le", prev = "我", origin = Row.NONE, shown = "带来"))))

    fun forget(text: String = "指导") = control.undoable { log.forget(text) }.also { assertEquals(0, uses(log.state, text)) }

    // what the file replays to
    fun fromFile(): LearnState = LearnLog.replay(written())

    private fun written() = dir.file(LearnLog.FILE).read()

    // under any code: 点了 is learned under dl
    private fun uses(state: LearnState, text: String) =
        state.stats.entries.filter { it.key.text == text }.sumOf { it.value.uses.sumFrom(Int.MIN_VALUE) }

    // 指导's uses in memory and in the file
    fun uses() = uses(log.state, "指导") to uses(fromFile(), "指导")

    // taken back in memory with no rewrite: the file as it was, nothing said, and not twice
    fun takenBackInMemory(token: Long) {
        val written = written()
        assertEquals(LearningControl.TakenBack.DONE, control.takeBack(token))
        assertEquals(1 to 1, uses())
        assertTrue(written.contentEquals(written()))
        assertEquals(LearningControl.TakenBack.GONE, control.takeBack(token))
        assertEquals(emptyList<IOException>(), told)
    }

    // not taken back: the state and the file as the act left them, nothing said of the disk
    fun gone(token: Long) {
        val state = log.state.dump()
        val written = written()
        assertEquals(LearningControl.TakenBack.GONE, control.takeBack(token))
        assertEquals("nothing dropped", state, log.state.dump())
        assertTrue(written.contentEquals(written()))
        assertEquals(emptyList<IOException>(), told)
    }
}
