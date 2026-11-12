/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * What the engines do when the disk refuses the learning record a write (review R11 F2743), and
 * that what is learned in memory alone is told, whatever the cause (061-userdict-loss).
 */
class EnginesRecordFaultTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    // review R11 F2743: an append the disk refuses is reported once; the act is done, and kept in memory for the run
    @Test
    fun anAppendRefusedIsReportedOnceAndTheActKeptInMemory() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        Engines(::load, JvmFiles(dir), onError = { errors += it }).use { engines ->
            // 拟 used once, written: a word learned there, to delete
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            refuseNextAppend(engines)
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) is LearningResult.Done)
            assertEquals(1, errors.size)
            // forgotten for the run all the same
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            refuseNextAppend(engines)
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Pin(1, "拟")) is LearningResult.Done)
            assertEquals(2, errors.size)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("拟", "你"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        }
    }

    /**
     * 0.5.2's fresh install had no user directory: the record did not open (ENOENT) and what was learned
     * lived in memory for days, gone with the process, nothing said. Learned in memory alone, for any
     * cause, it is told, once, as the first word learned is not kept; nothing on a record that keeps it.
     */
    @Test
    fun whatIsLearnedButNotKeptIsToldOnceWhateverTheCause() = assertRows(
        listOf<Pair<String, () -> UserFiles?>>(
            "no user directory (before the unlock)" to { null },
            "a user directory not there (0.5.2's fresh install)" to { JvmFiles(File(folder.root, "engine")) },
            "a record that does not open" to { JvmFiles(folder.newFolder()).also { it.dir(LearnLog.FILE).mkdirs() } },
            "a write refused" to { JvmFiles(folder.newFolder()) },
        ),
        { it.first },
    ) { (name, dir) ->
        var told = 0
        Engines(::load, dir(), unkept = { told++ }).use { engines ->
            if (name == "a write refused") {
                pick(engines)
                assertEquals("kept so far", 0, told)
                refuseNextAppend(engines)
            }
            pick(engines)
            assertEquals(1, told)
            // told once: the next words learned in memory too, said no more
            pick(engines)
            engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟"))
            assertEquals(1, told)
        }
    }

    @Test
    fun whatIsLearnedAndKeptIsNotTold() {
        var told = 0
        Engines(::load, JvmFiles(folder.newFolder()), unkept = { told++ }).use { engines ->
            repeat(2) { pick(engines) }
            engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟"))
        }
        assertEquals(0, told)
    }

    // 拟 picked under ni: a commit learned
    private fun pick(engines: Engines) {
        val at = engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("拟")
        assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, at).commit)
    }

    // the learning record (opened if not yet) with its next write refused: the store reports it, then opens the file again
    private fun refuseNextAppend(engines: Engines) {
        // opened as its first use opens it
        checkNotNull(engines.learnLog().store).refuseNextWrite()
    }
}
