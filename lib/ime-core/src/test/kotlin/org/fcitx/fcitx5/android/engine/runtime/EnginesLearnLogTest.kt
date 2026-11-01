/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.simplifierOf
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginesLearnLogTest : EnginesFixture() {

    private val root = MemoryFiles()

    // impl/design-1a.md: each commit in the learning record, read back as it ranks
    @Test
    fun aCommitIsRecordedInTheLearningLog() {
        val engines = Engines(::load, root, simplifier = { simplifierOf('妳' to '你') })
        // the keyboard made by its first key, then told of the app's text
        repeat(2) {
            engines.context(InputMethodCatalog.PINYIN, "妳")
            engines.type(InputMethodCatalog.PINYIN, "ni")
            assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        }
        engines.close()
        val state = LearnLog.replay(root.file(LearnLog.FILE).read())
        val key = state.stats.keys.single()
        assertEquals("qp:ni" to "你", key.code.toString() to key.text)
        // the app's 妳 read as 你: the word before
        assertEquals(1, state.pairs.keys.count { it.prev == "你" && it.next == "你" })
        // forgotten from the settings: there too
        val again = Engines(::load, root)
        again.removeWords(listOf(UserWord("你", "ni", UserWord.Kind.LEARNED)))
        again.type(InputMethodCatalog.PINYIN, "ni")
        again.close()
        assertTrue(LearnLog.replay(root.file(LearnLog.FILE).read()).stats.isEmpty())
    }

    // review-1a-app N3: before the user unlocks (no user directory) it learns in memory
    @Test
    fun withNoUserDirectoryItLearnsInMemory() {
        Engines(::load, null).use { engines ->
            engines.type(InputMethodCatalog.PINYIN, "ni")
            assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
            // learned there, in a record on no disk: 拟 picked twice counted beside 你
            repeat(2) {
                val at = engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("拟")
                assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, at).commit)
            }
            val log = engines.learnLog()
            assertFalse(log.onDisk)
            assertEquals(setOf("你", "拟"), log.state.stats.keys.map { it.text }.toSet())
        }
    }
}
