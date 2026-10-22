/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.store.RecordStore
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class TableUserTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val wubi = dictionary("a 工", "aaaa 工", "aaaa 恭恭敬敬", "aaad 工期", "wqiy 你", "wun 们", "vbg 好")

    private val file get() = folder.root.resolve("wubi.user")

    /** What an older build wrote: picks, 你们 saved, 你好 seen typed character by character, and [more]. */
    private fun olderLog(vararg more: ByteArray) {
        file.writeBytes(
            TableLog.FORMAT.header() + TableLog.picked("aaaa", "恭恭敬敬", 3) + TableLog.saved("wqwu", "你们") +
                TableLog.seen("wqvb", "你好", 2) + more.fold(ByteArray(0)) { a, b -> a + b },
        )
    }

    private fun read(table: TableDictionary = wubi, compactAt: Long = RecordStore.DEFAULT_COMPACT_AT): Pair<TableUser, TableUser.Store> {
        val user = TableUser(table)
        return user to TableUser.Store(file, user, compactAt = compactAt).apply { open() }
    }

    private fun reread(table: TableDictionary = wubi) = read(table).let { (user, store) -> store.close(); user }

    @Test
    fun anOlderBuildsSavedPhrasesAreKeptItsPicksAndWhatItSawAreNot() {
        olderLog()
        val user = reread()
        assertEquals(listOf("wqwu" to "你们"), user.saved(""))
        // a pick of the table's own entry is the learning record's, converted once: not a phrase
        assertFalse(user.isSaved("aaaa", "恭恭敬敬"))
        // seen, not saved: 自动造词 is gone
        assertFalse(user.isSaved("wqvb", "你好"))
        assertEquals(listOf("你们"), TableSession(wubi, TableOptions.WUBI.copy(autoSelect = false), user = user).type("wqw").second.candidates)
    }

    @Test
    fun aPickOfAnEntryGoneFromTheTableIsTheUsersPhrase() {
        olderLog()
        // a later table without 恭恭敬敬, left out as rare: the user picked it, it stays theirs
        val user = reread(dictionary("aaaa 工", "aaaa 敬", "wqiy 你", "wun 们", "vbg 好"))
        assertTrue(user.isSaved("aaaa", "恭恭敬敬"))
    }

    @Test
    fun aPhraseALaterTableHasIsTheTablesAlone() {
        olderLog()
        val user = reread(dictionary("wqwu 你们", "wqiy 你", "wun 们", "vbg 好"))
        assertFalse("你们" in user.savedTexts())
    }

    @Test
    fun compactedTheLogKeepsTheSavedPhrasesAndTheOlderBuildsPicks() {
        // two picks of one entry, and a pick forgotten after
        olderLog(TableLog.picked("aaaa", "恭恭敬敬", 2), TableLog.picked("aaad", "工期", 1), TableLog.forgot("aaad", "工期"))
        read(compactAt = 1).second.close()
        val kept = ArrayList<String>()
        TableLog.FORMAT.read(file.readBytes()) { type, input ->
            kept += when (type) {
                TableLog.SAVED -> "saved ${input.readUTF()} ${input.readUTF()}"
                TableLog.PICKED -> "picked ${input.readUTF()} ${input.readUTF()} ${input.readInt()}"
                else -> "type $type"
            }
        }
        // the picks are what a conversion run after this open still reads (review R11 F2649);
        // what was seen typed is no one's
        assertEquals(listOf("saved wqwu 你们", "picked aaaa 恭恭敬敬 5"), kept)
        assertEquals(listOf("wqwu" to "你们"), reread().saved(""))
    }

    // review R11 F3009: a compaction an append made due, written on the executor, keeps what the
    // log keeps; what is forgotten meanwhile goes in after it
    @Test
    fun compactedInTheBackgroundTheLogReadsTheSame() {
        olderLog(TableLog.saved("wqvb", "你好"), TableLog.saved("wuwq", "们你"))
        val deferred = ArrayList<Runnable>()
        val user = TableUser(wubi)
        TableUser.Store(file, user, compactAt = 1, background = Executor { deferred += it }).apply { open() }.use {
            user.forget("wqvb", "你好")
            while (deferred.isNotEmpty()) deferred.removeAt(0).run()
            user.forget("wuwq", "们你")
        }
        assertFalse(File(file.path + ".compacting").exists())
        val again = reread()
        assertEquals(setOf("你们"), again.savedTexts())
        var picks = 0
        TableLog.FORMAT.read(file.readBytes()) { type, _ -> if (type == TableLog.PICKED) picks++ }
        assertEquals(1, picks)
    }

    @Test
    fun aLogOfAnotherKindIsSetAside() {
        file.writeBytes(UserLog.header())
        read().second.close()
        assertTrue(File(file.path + RecordStore.UNREADABLE).exists())
        assertTrue(reread().savedTexts().isEmpty())
    }

    @Test
    fun aRecordOfALaterVersionOrWrittenWrongIsSkipped() {
        // a later type whose fields read as a saved phrase's would, and a saved phrase whose text does not read
        olderLog(
            TableLog.FORMAT.record(9) { writeUTF("vbwq"); writeUTF("好你") },
            TableLog.FORMAT.record(2) { writeShort(2); writeByte(0xFF); writeByte(0xFF) },
        )
        assertEquals(setOf("你们"), reread().savedTexts())
    }

    // what is kept, compacted, of an older build's records
    private fun kept(): List<String> {
        val kept = ArrayList<String>()
        TableLog.FORMAT.read(file.readBytes()) { type, input ->
            kept += when (type) {
                TableLog.SAVED -> "saved ${input.readUTF()} ${input.readUTF()}"
                TableLog.PICKED -> "picked ${input.readUTF()} ${input.readUTF()} ${input.readInt()}"
                else -> "type $type"
            }
        }
        return kept
    }

    // review F3153: a phrase kept of an older build's pick, forgotten, is no pick a conversion brings back
    @Test
    fun aForgottenPhraseOfAnOlderPickLosesThePick() {
        olderLog()
        val rare = dictionary("aaaa 工", "aaaa 敬", "wqiy 你", "wun 们", "vbg 好")
        val (user, store) = read(rare)
        store.use { user.forget("aaaa", "恭恭敬敬") }
        read(rare, compactAt = 1).second.close()
        assertEquals(listOf("saved wqwu 你们"), kept())
    }

    // review F3140: 全部清空 drops the older build's picks too, a phrase saved still kept
    @Test
    fun theOlderPicksForgottenAllGoTheSavedStay() {
        olderLog()
        val (user, store) = read()
        store.use { user.forgetOlderPicks() }
        read(compactAt = 1).second.close()
        assertEquals(listOf("saved wqwu 你们"), kept())
    }

    @Test
    fun whatIsForgottenStaysForgotten() {
        olderLog(TableLog.saved("wqvb", "你好"))
        val (user, store) = read()
        store.use {
            user.forget("wqwu", "你们")
            // nothing saved of it: no record
            val records = ArrayList<ByteArray>()
            val journal = user.journal
            user.journal = { records += it; journal?.invoke(it) }
            user.forget("aaaa", "工")
            assertTrue(records.isEmpty())
        }
        assertEquals(setOf("你好"), reread().savedTexts())
        // compacted, the same
        read(compactAt = 1).second.close()
        assertEquals(setOf("你好"), reread().savedTexts())
    }

    @Test
    fun savedPhrasesAreFoundByTheStartOfTheirCodeInCodeOrder() {
        olderLog(TableLog.saved("wuwq", "们你"), TableLog.saved("wqvb", "你好"))
        val user = reread()
        assertEquals(listOf("wqvb" to "你好", "wqwu" to "你们"), user.saved("wq"))
        assertEquals(listOf("wqwu" to "你们"), user.saved("wqw"))
        assertEquals(3, user.saved("").size)
        assertTrue(user.leadsAnywhere("wu"))
        assertFalse(user.leadsAnywhere("wv"))
        assertFalse(user.leadsAnywhere("wqwuw"))
    }

    @Test
    fun manyTextsAreForgottenAtOnceUnderEveryCodeTheyWereSavedBy() {
        olderLog(TableLog.saved("wqwx", "你们"), TableLog.saved("wuwq", "们你"), TableLog.saved("wqvb", "你好"))
        val (user, store) = read()
        val records = ArrayList<ByteArray>()
        store.use {
            val journal = user.journal
            user.journal = { records += it; journal?.invoke(it) }
            user.forgetTexts(setOf("你们", "你好", "好你"))
        }
        assertEquals(setOf("们你"), user.savedTexts())
        // one record for each code and text forgotten: 你们 twice, 你好
        assertEquals(3, records.size)
        assertEquals(setOf("们你"), reread().savedTexts())
    }
}
