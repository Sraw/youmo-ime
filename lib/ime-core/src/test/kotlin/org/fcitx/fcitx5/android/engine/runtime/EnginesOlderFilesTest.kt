/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The older builds' learning files, which this build never reads: deleted with what the clears clear. */
class EnginesOlderFilesTest : EnginesFixture() {

    private val root = MemoryFiles()

    private fun Engines.op(op: LearningOp) = learning(InputMethodCatalog.PINYIN, op)

    // an older build put back would offer what was cleared again
    @Test
    fun libimesFilesAndATablesOldLogGoWithAClear() {
        val libime = listOf(MemoryFiles().file("user.dict"), MemoryFiles().file("user.history"))
        val files = libime + listOf("wubi.user", "wubi.user.told", "cangjie.user").map { root.file(it) }
        files.forEach { it.write("x".toByteArray()) }
        Engines(::load, root, warmUps = inPlace, legacyFiles = libime).use { engines ->
            assertTrue(engines.op(LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
        }
        assertTrue(files.none { it.exists() || it.beside(".cleared").exists() })
    }

    // before it is read into the record a table's log holds phrases the user still has; libime's go with any
    @Test
    fun aClearOfTodayTakesATablesLogOnlyOnceRead() {
        val log = root.file("cangjie.user").apply { write("x".toByteArray()) }
        val older = listOf(root.file("pinyin.user"), MemoryFiles().file("user.dict")).onEach { it.write("x".toByteArray()) }
        Engines(::load, root, warmUps = inPlace, legacyFiles = older.drop(1)).use { engines ->
            assertTrue(engines.op(LearningOp.ClearRecent(LearningOp.Span.TODAY)) is LearningResult.Cleared)
        }
        assertTrue(log.exists())
        assertTrue(older.none { it.exists() })
        root.file(Engines.TABLE_LOGS_READ).createNew()
        Engines(::load, root, warmUps = inPlace).use { engines ->
            assertTrue(engines.op(LearningOp.ClearRecent(LearningOp.Span.TODAY)) is LearningResult.Cleared)
        }
        assertFalse(log.exists())
    }
}
