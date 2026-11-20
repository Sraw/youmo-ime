/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException

/** An act taken back where the disk refused records: what counts as unwritten (LearningControl.takeBack). */
class UnwrittenLearningControlTest {

    // records refused since the state was last made the file's, held against the act's own
    @Uat("WD-46")
    @Test
    fun whatIsUnwrittenIsCountedARecordAndAnewOnceTheStateIsTheFiles() {
        assertRows(
            // with all of the act in the file the rewrite takes it out and the count starts anew
            "something learned in memory, the delete after it written: taken back by the rewrite; a forget refused next, in memory" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    guide()
                    disk.full = false
                    val token = forget("点了")
                    assertEquals(1, log.unwritten)
                    assertEquals(LearningControl.TakenBack.DONE, control.takeBack(token))
                    assertEquals("the state the file's, as after any rewrite", fromFile().dump(), log.state.dump())
                    assertEquals(1 to 1, uses())
                    assertEquals(0, log.unwritten)
                    disk.full = true
                    takenBackInMemory(forget())
                }
            },
            "a forget refused and taken back in memory, then another: taken back too" to {
                with(RefusedScene()) {
                    disk.full = true
                    takenBackInMemory(forget())
                    takenBackInMemory(forget())
                }
            },
            "an act of three records refused in one write (words imported): three counted, the act's own, taken back" to {
                with(RefusedScene()) {
                    disk.full = true
                    val batch = listOf(
                        LearnRecord.ImportBatch(1, "a.txt", 20000, 2),
                        LearnRecord.Imported(1, 0, "鲸园", "jing yuan"),
                        LearnRecord.Imported(1, 1, "汪小明", "wang xiao ming"),
                    )
                    val token = control.undoable { log.imports(batch) }
                    assertEquals(3, log.unwritten)
                    assertNotEquals("the batch in the state", fromFile().dump(), log.state.dump())
                    assertEquals(LearningControl.TakenBack.DONE, control.takeBack(token))
                    assertEquals(fromFile().dump(), log.state.dump())
                    assertEquals(emptyList<IOException>(), told)
                }
            },
            // the control is not told of what came after, as nothing in the app does: the count alone holds it back
            "something else learned in memory after the delete, the act still the last: no longer taken back, it is kept" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    val token = forget("点了")
                    val hears = log.appended
                    log.appended = null
                    guide()
                    log.appended = hears
                    gone(token)
                    assertEquals(2 to 1, uses())
                }
            },
        )
    }

    @Uat("WD-46")
    @Test
    fun aPinOrANoPredictIsTakenBackByItsOppositeWhateverIsUnwritten() {
        fun takenBack(act: LearnLog.() -> Unit) = with(RefusedScene()) {
            disk.full = true
            there()
            val before = log.state.dump()
            val token = control.undoable { log.act() }
            assertNotEquals("the act in the state", before, log.state.dump())
            assertEquals(LearningControl.TakenBack.DONE, control.takeBack(token))
            assertEquals("as before the act, what was learned in memory kept", before, log.state.dump())
            assertEquals(emptyList<IOException>(), told)
        }
        assertRows(
            "固定首位 with 点了 learned in memory alone" to { takenBack { pin(Code(Kinds.FULL, "zhidao"), "指导", "zhi dao", true) } },
            "不再联想 with 点了 learned in memory alone" to { takenBack { noPredict("老师", "指导", true) } },
            "a table block with 点了 learned in memory alone" to {
                takenBack { event { minute, _ -> LearnRecord.TableBlock(minute, "engine-wbx", "wqiy", "你", on = true) } }
            },
        )
    }

    @Uat("WD-46")
    @Test
    fun aForgetOnARecordThatDidNotOpenIsNoLongerTakenBack() {
        val told = ArrayList<IOException>()
        val log = LearnLog(MemoryFiles().file(LearnLog.FILE), FakeClock(), told::add, openAppend = { throw IOException("not opened") }).also { it.open() }
        val control = LearningControl({ log }, onError = told::add).also { it.attach(log) }
        val word = corrected("zhidao", "指导", "zhi dao", prev = "老师", shown = "知道")
        checkNotNull(log.record(listOf(word)))
        assertFalse(log.onDisk)
        assertEquals(1, log.state.stats.size)
        val learned = log.state.dump()
        val token = control.undoable { log.forget("指导") }
        val forgotten = log.state.dump()
        assertNotEquals(learned, forgotten)
        told.clear()
        assertEquals(LearningControl.TakenBack.GONE, control.takeBack(token))
        assertEquals("still deleted", forgotten, log.state.dump())
        assertEquals(emptyList<IOException>(), told)
        assertEquals("no file open to have refused it: none counted", 0, log.unwritten)
    }
}
