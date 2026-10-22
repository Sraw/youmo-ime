/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.ImportedWords.Incoming
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportBatch
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportUndo
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Imported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/** The imported words in the learning record (U-101, U-102): batches written, replayed, folded, undone. */
class ImportedWordsTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val clock = FakeClock()

    private fun file() = File(dir.root, LearnLog.FILE)

    private fun open() = LearnLog(file(), clock, compactAt = 1L shl 30, foldDaily = false).also { it.open() }

    private fun words(vararg w: Pair<String, String>) = w.map { Incoming(it.first, it.second) }

    @Test
    fun aPlanLeavesOutWhatIsKnownAndRepeatsAndOrdersByTheFilesCount() {
        val incoming = listOf(
            Incoming("汪小明", "wang xiao ming"),
            Incoming("鲸园", "jing yuan", 3.0),
            Incoming("你好", "ni hao", 9.0),
            Incoming("鲸园", "jing yuan", 5.0),
            Incoming("量子", "liang zi", 7.0),
        )
        val plan = ImportedWords.plan(4, "a.scel", 20000, incoming) { text, _ -> text == "你好" }
        // the counts first, the file's order where none; 鲸园 once, as the file first has it
        assertEquals(
            listOf(ImportBatch(4, "a.scel", 20000, 3), Imported(4, 0, "量子", "liang zi"), Imported(4, 1, "鲸园", "jing yuan"), Imported(4, 2, "汪小明", "wang xiao ming")),
            plan,
        )
        assertEquals(emptyList<LearnRecord>(), ImportedWords.plan(1, "b.txt", 0, incoming.take(1)) { _, _ -> true })
    }

    @Test
    fun importsReplayAndFoldAsWritten() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("汪小明" to "wang xiao ming", "行长" to "hang zhang")) { _, _ -> false })
        log.imports(ImportedWords.plan(2, "b.txt", 20001, words("行长" to "xing zhang", "汪小明" to "wang xiao ming", "鲸园" to "jing yuan")) { _, _ -> false })
        val imports = log.state.imports
        // the second batch does not take 汪小明 over; 行长 in both its readings
        assertEquals(listOf(1, 2), imports.batches.map { it.id })
        assertTrue(imports.has("行长", "xing zhang") && imports.has("行长", "hang zhang"))
        // by batch, then the batch's order: 汪小明 before 行长, both before 鲸园 of the second
        assertTrue(imports.order("汪小明") < imports.order("行长"))
        assertTrue(imports.order("行长") < imports.order("鲸园"))
        assertEquals(-1L, imports.order("你好"))
        assertEquals(4, imports.size)
        // a commit after them: what has no minute of its own folds with the records before one older than the cut
        log.record(listOf(row("ni", "你", "ni")))
        val live = log.state.dump()
        log.close()
        val bytes = file().readBytes()
        assertEquals(live, LearnLog.replay(bytes).dump())
        val out = ByteArrayOutputStream()
        assertTrue(LearnLog.fold(bytes, LearnClock.cut(clock) + LearnClock.DAY_MINUTES, out))
        assertEquals(live, LearnLog.replay(out.toByteArray()).dump())
        assertTrue(live.contains("ImportedRow(batch=2, order=2, text=鲸园, reading=jing yuan)"))
    }

    @Test
    fun anImportedWordCommittedTwiceIsTheUsersAndStaysWhenItsBatchIsUndone() {
        val log = open()
        val changes = ArrayList<LearnState.Changes>()
        log.listener = { changes += it }
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("汪小明" to "wang xiao ming", "鲸园" to "jing yuan")) { _, _ -> false })
        assertTrue(changes.single().imports)
        repeat(2) { log.record(listOf(corrected("wangxiaoming", "汪小明", "wang xiao ming", origin = Row.IMPORTED, shown = "王小明"))) }
        assertEquals(Mine.FORMED_FROM_IMPORT, log.state.mine.getValue("汪小明").how)
        changes.clear()
        log.imports(listOf(ImportUndo(1)))
        // both out of the record's imports; the user's word stays theirs
        assertTrue(log.state.imports.isEmpty())
        assertTrue(changes.single().imports)
        assertTrue("汪小明" in log.state.mine)
        // a word of the batch undone is not taken: it comes back only with its batch
        log.imports(listOf(Imported(1, 0, "鲸园", "jing yuan")))
        assertFalse(log.state.imports.has("鲸园", "jing yuan"))
        // 撤销: the batch as it was, from the places its words keep
        val back = log.state.imports.restore(1)
        assertEquals(listOf(ImportBatch(1, "a.txt", 20000, 2), Imported(1, 0, "汪小明", "wang xiao ming"), Imported(1, 1, "鲸园", "jing yuan")), back)
        log.imports(back)
        assertTrue(log.state.imports.has("鲸园", "jing yuan"))
        assertEquals(1, log.state.imports.lastId)
        // the same places: the user model's ids of them stay
        assertEquals(2, log.state.imports.places)
        assertEquals(emptyList<LearnRecord>(), log.state.imports.restore(1))
    }

    @Test
    fun aWordForgottenIsNoLongerImportedNorBroughtBackWithItsBatch() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("行长" to "hang zhang", "行长" to "xing zhang", "鲸园" to "jing yuan")) { _, _ -> false })
        val changes = ArrayList<LearnState.Changes>()
        log.listener = { changes += it }
        log.forget("行长")
        assertEquals(1, log.state.imports.size)
        assertTrue(changes.single().imports)
        assertEquals(listOf("鲸园"), log.state.imports.places().map(log.state.imports::text))
        log.imports(listOf(ImportUndo(1)))
        assertEquals(listOf("鲸园"), log.state.imports.restore(1).filterIsInstance<Imported>().map { it.text })
    }

    // review-int-records N3: a batch a rewrite took (全部清空, 退回恢复前) is not put back by a 撤销 left on screen
    @Test
    fun aBatchARewriteTookIsNotPutBack() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("鲸园" to "jing yuan")) { _, _ -> false })
        assertTrue(log.rewrite { _, out -> LearnBackup.writeLog(out, LearnState()); true })
        assertTrue(log.state.imports.isEmpty())
        assertEquals(emptyList<LearnRecord>(), log.state.imports.restore(1))
        log.close()
    }

    // F2811: a batch undone before 全部清空 is not put back by its 撤销 after it
    @Test
    fun aBatchUndoneBeforeAClearIsNotPutBack() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("鲸园" to "jing yuan")) { _, _ -> false })
        log.imports(listOf(ImportUndo(1)))
        assertTrue(log.rewrite { _, out -> LearnBackup.writeLog(out, LearnState()); true })
        assertEquals(emptyList<LearnRecord>(), log.state.imports.restore(1))
        assertTrue(log.state.imports.isEmpty())
        log.close()
    }

    // F3075: a rewrite that keeps the ImportUndo (an act's undo drops only the act after it) ends the 撤销 too
    @Test
    fun aBatchUndoneBeforeARewriteKeepingItIsNotPutBack() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("鲸园" to "jing yuan")) { _, _ -> false })
        log.imports(listOf(ImportUndo(1)))
        assertTrue(log.rewrite { bytes, out -> out.write(bytes); true })
        assertEquals(emptyList<LearnRecord>(), log.state.imports.restore(1))
        log.close()
    }

    // F3078: an import written in one append while the log is being compacted in the background loses no word
    @Test
    fun anImportWhileCompactingKeepsEveryWord() {
        val later = ArrayList<Runnable>()
        val log = LearnLog(file(), clock, background = { later += it }, compactAt = 64, foldDaily = false).also { it.open() }
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("汪小明" to "wang xiao ming", "行长" to "hang zhang")) { _, _ -> false })
        assertTrue(later.isNotEmpty())
        log.imports(ImportedWords.plan(2, "b.txt", 20001, words("鲸园" to "jing yuan", "鲸喵" to "jing miao")) { _, _ -> false })
        later.forEach { it.run() }
        val live = log.state.dump()
        log.close()
        assertEquals(live, LearnLog.replay(file().readBytes()).dump())
        assertTrue(live.contains("text=鲸喵") && live.contains("text=汪小明"))
    }

    // an import's records have no minute: they fold with what is before them, a tail of them alone too
    @Test
    fun aTailOfImportsAloneFolds() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("鲸园" to "jing yuan")) { _, _ -> false })
        val live = log.state.dump()
        log.close()
        val out = ByteArrayOutputStream()
        assertTrue(LearnLog.fold(file().readBytes(), LearnClock.cut(clock), out))
        assertEquals(live, LearnLog.replay(out.toByteArray()).dump())
        assertTrue(live.contains("ImportedRow(batch=1, order=0, text=鲸园, reading=jing yuan)"))
    }

    // review-G M1: a record read afresh (a fold, then a rewrite) numbers the places again; taken
    // over by the words the model reads, each keeps the place it had, what is gone stays gone
    @Test
    fun aRecordReadAfreshKeepsEachWordsPlace() {
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words("汪小明" to "wang xiao ming", "行长" to "hang zhang")) { _, _ -> false })
        log.imports(ImportedWords.plan(2, "b.txt", 20001, words("鲸园" to "jing yuan", "鲸喵" to "jing miao")) { _, _ -> false })
        log.imports(listOf(LearnRecord.ImportUndo(1)))
        val held = log.state.imports
        val places = held.places().associateBy { held.text(it) }
        assertTrue(log.rewrite { bytes, out -> LearnLog.fold(bytes, LearnClock.cut(clock) + LearnClock.DAY_MINUTES, out) })
        assertTrue(log.state.imports === held)
        assertEquals(places, held.places().associateBy { held.text(it) })
        assertFalse(held.has("汪小明", "wang xiao ming"))
        // a rewrite ends every 撤销, as a start does (F2811): the batch undone before it stays out
        assertEquals(emptyList<LearnRecord>(), held.restore(1))
        assertEquals(listOf(2, 3), held.places())
        assertEquals(listOf(2), held.batches.map { it.id })
    }

    // the packed table past its first size (16): hundreds of words, some in two readings, undone
    // and put back, replayed; every word still found where it was (R11 F2702)
    @Test
    fun manyWordsGrowTheTableAndAreFoundUndoneAndBack() {
        val syllables = listOf("ni", "hao", "ma", "shi", "de", "wo", "ta", "zhong", "guo", "ren")
        val first = (0 until 600).map { i ->
            "词$i" to listOf(i / 100, i / 10 % 10, i % 10).joinToString(" ") { syllables[it] }
        }
        // a second batch: the first 50 again in another reading, and 50 new
        val second = first.take(50).map { (t, r) -> t to "$r ren" } + (600 until 650).map { "词$it" to "zhong guo" }
        val log = open()
        log.imports(ImportedWords.plan(1, "a.txt", 20000, words(*first.toTypedArray())) { _, _ -> false })
        log.imports(ImportedWords.plan(2, "b.txt", 20001, words(*second.toTypedArray())) { _, _ -> false })
        fun check(firstLive: Boolean) {
            val imports = log.state.imports
            for ((t, r) in first) assertEquals(t, firstLive, imports.has(t, r))
            for ((t, r) in second) assertTrue(t, imports.has(t, r))
            for ((t, _) in first.take(50)) assertEquals(t, if (firstLive) 2 else 1, imports.placesOf(t).size)
            // the file's order kept within each batch
            if (firstLive) assertTrue(first.zipWithNext().all { (a, b) -> imports.order(a.first) < imports.order(b.first) })
            assertEquals((if (firstLive) 600 else 0) + 100, imports.rows().count { it is LearnRecord.ImportedRow })
        }
        check(firstLive = true)
        log.imports(listOf(ImportUndo(1)))
        check(firstLive = false)
        log.imports(log.state.imports.restore(1))
        check(firstLive = true)
        val live = log.state.dump()
        log.close()
        assertEquals(live, LearnLog.replay(file().readBytes()).dump())
    }
}
