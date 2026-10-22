/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** [LearningControl.undoable] and [LearningControl.undo]: the chip's 撤销, an act taken back as though never done. */
class LearningControlUndoTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val clock = FakeClock()
    private val errors = ArrayList<IOException>()

    private fun file() = File(dir.root, LearnLog.FILE)

    private fun open(): LearnLog = LearnLog(file(), clock, errors::add, compactAt = 1L shl 30).also { it.open() }

    private fun control(log: LearnLog) = LearningControl({ log }, clock, onError = errors::add).also { it.attach(log) }

    private fun replayed() = LearnLog.replay(file().readBytes())

    private fun LearnLog.commit(vararg rows: Row): LearnRecord.Commit = checkNotNull(record(rows.toList()))

    private fun LearnLog.guide() = checkNotNull(record(listOf(corrected("zhidao", "指导", "zhi dao", prev = "老师", shown = "知道"))))

    private fun LearnState.uses(text: String) =
        stats.entries.filter { it.key.code == Code(Kinds.FULL, "zhidao") && it.key.text == text }.sumOf { it.value.uses.sumFrom(Int.MIN_VALUE) }

    @Test
    fun anActTakenBackIsAsThoughNeverDone() {
        val log = open()
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true)
        val control = control(log)
        var inverse = 0
        // the pin replaced: taken back, the one before is pinned again
        val token = control.undoable(inverse = { inverse++ }) {
            log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = false)
            log.pin(Code(Kinds.FULL, "dz"), "的值", "de zhi", on = true)
        }
        assertEquals("的值", log.state.pins.values.single().text)
        assertTrue(control.undo(token))
        assertEquals("地址", log.state.pins.values.single().text)
        assertEquals("地址", replayed().pins.values.single().text)
        assertEquals(1, inverse)
        // once
        assertFalse(control.undo(token))
    }

    @Test
    fun aForgetTakenBackBringsTheWordBack() {
        val log = open()
        repeat(2) { log.commit(corrected("wxm", "汪小明", "wang xiao ming", origin = Row.NONE, shown = "王小明")) }
        val was = log.state.dump()
        val control = control(log)
        val token = control.undoable { log.forget("汪小明") }
        assertTrue(log.state.mine.isEmpty())
        assertTrue(control.undo(token))
        assertEquals(was, log.state.dump())
    }

    @Test
    fun anActIsTakenBackOnlyTillSomethingElseIsWritten() {
        val log = open()
        val control = control(log)
        val first = control.undoable { log.forget("你") }
        val second = control.undoable { log.forget("好") }
        // only the last
        assertFalse(control.undo(first))
        log.guide()
        assertFalse(control.undo(second))
        // an act of nothing written: its inverse alone
        var undone = false
        assertTrue(control.undo(control.undoable(inverse = { undone = true }) {}))
        assertTrue(undone)
    }

    @Test
    fun anActWithAnOppositeIsTakenBackWithoutARewrite() {
        val log = open()
        val control = control(log)
        val written = log.rewrites
        val pinned = control.undoable { log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true) }
        assertTrue(control.undo(pinned))
        val off = control.undoable { log.noPredict("我", "的", on = true) }
        assertTrue(control.undo(off))
        // a table's block under its code (U-28), as its long press makes it
        val blocked = control.undoable { log.event { minute, _ -> LearnRecord.TableBlock(minute, "engine-wbx", "wqiy", "你", on = true) } }
        assertEquals(setOf("你"), log.state.tableBlocks[("engine-wbx" to "wqiy")])
        assertTrue(control.undo(blocked))
        assertEquals(written, log.rewrites)
        assertTrue(log.state.pins.isEmpty() && log.state.noPredict.isEmpty())
        assertTrue(log.state.tableBlocks.values.all { it.isEmpty() })
        assertTrue(replayed().tableBlocks.values.all { it.isEmpty() })
        // a forget has none: the log rewritten without it
        log.guide()
        assertTrue(control.undo(control.undoable { log.forget("指导") }))
        assertNotEquals(written, log.rewrites)
        assertEquals(1, replayed().uses("指导"))
    }

    @Test
    fun anActIsNotTakenBackOnceTheLogWasRewritten() {
        val log = open()
        log.guide()
        val control = control(log)
        val token = control.undoable { log.forget("指导") }
        // a restore, say: what the act wrote may no longer be last, or there
        assertTrue(log.rewrite { bytes, out -> out.write(bytes).let { true } })
        assertFalse(control.undo(token))
        assertEquals(0, log.state.uses("指导"))
    }

    @Test
    fun aRecordNotLastIsNotDropped() {
        val log = open()
        log.forget("你")
        log.guide()
        val out = ByteArrayOutputStream()
        assertFalse(LogRewrites.dropLast(file().readBytes(), listOf(LearnRecord.Forget(0, 0, "你")), out))
        assertFalse(LogRewrites.dropLast(file().readBytes(), emptyList(), out))
        assertEquals(0, out.size())
    }

    // an act that throws may have written: the one before it is no longer the last to take back
    @Test
    fun anActThatThrowsLeavesNoneToTakeBack() {
        val log = open()
        val control = control(log)
        val first = control.undoable { log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true) }
        runCatching { control.undoable { log.noPredict("我", "的", on = true); error("the act failed") } }
        assertFalse(control.undo(first))
        assertEquals("地址", log.state.pins.values.single().text)
    }
}
