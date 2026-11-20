/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.concurrent.thread

/** What [Engines] otherwise holds only in comments: build order, its one thread, nothing learned in a sensitive field. */
class EnginesInvariantsTest : EnginesFixture() {

    // init { stashes.takeAll() } runs last, after everything a merge opens
    @Test
    fun aStashIsMergedAsTheEnginesAreMade() {
        val bytes = Engines(::load, MemoryFiles(), warmUps = inPlace).use { other ->
            other.type(InputMethodCatalog.PINYIN, "hao")
            other.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
            checkNotNull((other.learning(null, BackupOp.Copy) as BackupResult).bytes)
        }
        val dir = MemoryFiles()
        dir.dir(RestoreStash.DIR).dir("1000").apply { mkdirs() }.file(RestoreStash.BACKUP).write(bytes)
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }, warmUps = inPlace).use { engines ->
            assertFalse(dir.dir(RestoreStash.DIR).exists())
            // the record it merged into is the one the engines learn in from then on
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        }
        assertEquals(emptyList<IOException>(), errors)
        val state = LearnLog.replay(dir.file(LearnLog.FILE).read())
        assertEquals(setOf("好", "你"), state.stats.keys.map { it.text }.toSet())
    }

    // made with nothing to take in and no user directory: no part asked before it is made
    @Test
    fun theEnginesAreMadeWithNoUserDirectory() {
        val errors = ArrayList<IOException>()
        Engines(::load, null, onError = { errors += it }, warmUps = inPlace).use { engines ->
            assertEquals("你", engines.type(InputMethodCatalog.PINYIN, "ni").let { engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code) }.commit)
            assertTrue(engines.userWords().isEmpty())
        }
        assertEquals(emptyList<IOException>(), errors)
    }

    @Test
    fun aCallOffTheHostThreadIsToldWhereChecked() {
        val told = ArrayList<String>()
        Engines(::load, null, warmUps = inPlace, offHostThread = { synchronized(told) { told += it } }).use { engines ->
            engines.type(InputMethodCatalog.PINYIN, "ni")
            thread { engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, 'h'.code) }.join()
            assertEquals(1, told.size)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(1, told.size)
        }
    }

    // one row per public entry, so a missed guard is named; close is left out ([use] closes again on this thread)
    @Test
    fun everyEntryOffTheHostThreadIsTold() {
        val path = Glide(FloatArray(0), GlideLayout(emptyList()))
        fun row(name: String, call: Engines.() -> Unit) = name to call
        val calls = listOf(
            row("onEvent") { onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0) }, row("context") { context(InputMethodCatalog.PINYIN, "") },
            row("candidates") { candidates(InputMethodCatalog.PINYIN, 0, 1) }, row("offers") { offers(InputMethodCatalog.PINYIN, 0) },
            row("settings set") { settings = EngineSettings(pageSize = 3) }, row("learning") { learning(null, BackupOp.Copy) },
            row("addWord") { addWord("你好", "ni hao") }, row("removeWords") { removeWords(emptyList()) },
            row("removeWord") { removeWord(UserWord("你好", "ni hao", UserWord.Kind.entries.first())) },
            row("importWords") { importWords(emptySequence()) },
            row("blockWord") { blockWord("你好", "ni hao") }, row("addEnglishWord") { addEnglishWord("hello") },
            row("forgetEnglishWords") { forgetEnglishWords(listOf("hello")) }, row("restoreEnglishCorrection") { restoreEnglishCorrection(listOf("hello")) },
            row("tableKeys") { tableKeys(InputMethodCatalog.WUBI) }, row("pinyinOf") { pinyinOf("你") }, row("pinyinsOf") { pinyinsOf(listOf("你")) },
            row("logProbs") { logProbs(listOf("你")) }, row("wordBoundaries") { wordBoundaries(listOf("你好")) }, row("charReading") { charReading("你") },
            row("userWords") { userWords() }, row("userWords(kind)") { userWords(UserWord.Kind.entries.first()) }, row("userWordCounts") { userWordCounts() },
            row("sameCodes") { sameCodes(listOf("你"), listOf(InputMethodCatalog.WUBI)) }, row("englishWords") { englishWords() },
            row("importBatches") { importBatches() }, row("importedWords") { importedWords() }, row("dropImport") { dropImport(0) },
            row("restoreImport") { restoreImport(0) }, row("exportWords") { exportWords() }, row("useHabits get") { useHabits },
            row("useHabits set") { useHabits = true }, row("ink") { ink(Action.Ink(0), shown = false, im = InputMethodCatalog.PINYIN) }, row("inkSent") { inkSent(Action.Ink(0), im = InputMethodCatalog.PINYIN) },
            row("glide") { glide(InputMethodCatalog.ENGLISH, path) }, row("inkEnded") { inkEnded }, row("reload") { reload() },
        )
        assertRows(calls, { it.first }) { (_, call) ->
            val told = ArrayList<String>()
            Engines(::load, null, warmUps = inPlace, offHostThread = { synchronized(told) { told += it } }).use { engines ->
                // what the call does past the check (a session it cannot make here) is not this test's
                thread { runCatching { call(engines) } }.join()
                assertEquals(1, synchronized(told) { told.size })
            }
        }
    }

    // long-press acts included, which the host sends with learning on (pressShown)
    @Test
    fun nothingIsRecordedInASensitiveField() {
        // a word learned first in an ordinary field, so the acts below have something to act on
        fun learned() = MemoryFiles().also { dir ->
            Engines(::load, dir, warmUps = inPlace).use { engines ->
                engines.type(InputMethodCatalog.PINYIN, "ni")
                assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
            }
        }
        val dir = learned()
        val before = records(dir).size
        Engines(::load, dir, warmUps = inPlace).use { engines ->
            fun key(c: Char) = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, c.code, learning = false)
            engines.context(InputMethodCatalog.PINYIN, "", learning = false, started = true)
            "ni".forEach(::key)
            key(' ')
            "ni".forEach(::key)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1, learning = false)
            "haoni".forEach(::key)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BACKSPACE, 0, learning = false)
            key(' ')
            "ni".forEach(::key)
            val shown = engines.candidates(InputMethodCatalog.PINYIN, 0, 2).map { it.text }
            val acts = listOf(
                LearningOp.Pin(1, shown[1]), LearningOp.Delete(0, shown[0]), LearningOp.NoPredict(0, shown[0]), LearningOp.Block(1, shown[1]),
            )
            acts.forEach { op -> assertFalse("$op", engines.learning(InputMethodCatalog.PINYIN, op) is LearningResult.Done) }
            key(' ')
            engines.context(InputMethodCatalog.T9, "", learning = false, started = true)
            "64".forEach { engines.onEvent(InputMethodCatalog.T9, EngineEvent.CHAR, it.code, learning = false) }
            engines.onEvent(InputMethodCatalog.T9, EngineEvent.PICK, 0, learning = false)
            engines.context(InputMethodCatalog.SHUANGPIN, "", learning = false, started = true)
            "ni".forEach { engines.onEvent(InputMethodCatalog.SHUANGPIN, EngineEvent.CHAR, it.code, learning = false) }
            engines.onEvent(InputMethodCatalog.SHUANGPIN, EngineEvent.PICK, 0, learning = false)
            engines.context(InputMethodCatalog.WUBI, "", learning = false, started = true)
            "wqiy".forEach { engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.CHAR, it.code, learning = false) }
            engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.CHAR, ' '.code, learning = false)
            engines.context(InputMethodCatalog.ENGLISH, "", learning = false, started = true)
            "the ".forEach { engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, it.code, learning = false) }
        }
        // what the record gained is what an open and close alone add to it (its start, a day's, the install's claim): none of the user's typing nor acts
        val idle = learned()
        val idleBefore = records(idle).size
        // its record opened as a key opens it (the install's claim on it written then), nothing typed
        Engines(::load, idle, warmUps = inPlace).use { it.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0, learning = false) }
        val added = { d: MemoryFiles, from: Int -> records(d).drop(from).map { it::class.simpleName } }
        assertEquals(added(idle, idleBefore), added(dir, before))
    }
}
