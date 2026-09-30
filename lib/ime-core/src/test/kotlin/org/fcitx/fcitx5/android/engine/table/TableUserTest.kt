/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.store.RecordStore
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class TableUserTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dictionary(vararg entries: String) = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .rule("e3", "p11+p21+p31+p32")
        .rule("a4", "p11+p21+p31+n11")
        .apply { entries.forEach { e -> e.split(' ').let { entry(it[0], it[1]) } } }
        .build()
        .toByteArray()
        .let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }

    private val wubi = dictionary("a 工", "aaaa 工", "aaaa 恭恭敬敬", "aaad 工期", "wqiy 你", "wun 们", "vbg 好")

    private fun TableSession.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    /** What a user did: picked the second of aaaa, saved 你们 by picking it, and typed 你好 once. */
    private fun teach(session: TableSession) {
        session.type("aaaa")
        session.apply(Select(1))
        session.type("wqiy")
        session.type("wun")
        session.type("wqwu")
        session.apply(Select(0))
        session.apply(Reset)
        session.type("wqiy")
        session.type("vbg ")
        session.apply(Reset)
    }

    /** What [session] shows for what [teach] taught. */
    private fun shown(session: TableSession): List<List<String>> = listOf("aaaa", "wqw", "wqvb").map {
        session.apply(Reset)
        session.type(it).candidates.also { session.apply(Reset) }
    }

    private fun stored(file: File, compactAt: Long = RecordStore.DEFAULT_COMPACT_AT, table: TableDictionary = wubi): Pair<TableSession, TableUser.Store> {
        val user = TableUser(table)
        val store = TableUser.Store(file, user, compactAt = compactAt).apply { open() }
        return TableSession(table, TableOptions.WUBI, user = user) to store
    }

    @Test
    fun whatIsTaughtIsKeptAndReadBack() {
        val file = folder.root.resolve("wubi.user")
        val (session, store) = stored(file)
        teach(session)
        val expected = shown(session)
        store.close()
        assertEquals(listOf(listOf("恭恭敬敬", "工"), listOf("你们"), listOf("你好")), expected)
        // what a session with nothing kept shows
        assertEquals(listOf("工", "恭恭敬敬"), shown(TableSession(wubi, TableOptions.WUBI))[0])
        val (again, reopened) = stored(file)
        assertEquals(expected, shown(again))
        reopened.close()
    }

    @Test
    fun compactedTheLogReadsTheSame() {
        val file = folder.root.resolve("wubi.user")
        val (session, store) = stored(file, compactAt = 1)
        teach(session)
        repeat(3) {
            session.type("aaaa")
            session.apply(Select(1))
        }
        val expected = shown(session)
        store.close()
        // compacted on open, then each time the log doubled
        val (again, reopened) = stored(file)
        assertEquals(expected, shown(again))
        reopened.close()
    }

    @Test
    fun aPickOfAnEntryGoneFromTheTableCountsAsAPhrases() {
        val file = folder.root.resolve("wubi.user")
        val (session, store) = stored(file)
        teach(session)
        store.close()
        // a later table without 恭恭敬敬
        val (again, reopened) = stored(file, table = dictionary("aaaa 工", "aaaa 敬", "wqiy 你", "wun 们", "vbg 好"))
        assertEquals(listOf("工", "敬"), again.type("aaaa").candidates)
        reopened.close()
    }

    @Test
    fun aLogOfAnotherKindIsSetAside() {
        val file = folder.root.resolve("wubi.user")
        file.writeBytes(UserLog.header())
        val (session, store) = stored(file)
        teach(session)
        store.close()
        assertTrue(File(file.path + RecordStore.UNREADABLE).exists())
        val (again, reopened) = stored(file)
        assertFalse(shown(again).any { it.isEmpty() })
        reopened.close()
    }

    @Test
    fun aRecordOfALaterVersionOrWrittenWrongIsSkipped() {
        val file = folder.root.resolve("wubi.user")
        stored(file).let { (session, store) ->
            teach(session)
            store.close()
        }
        // a later type whose fields are no strings, and a pick whose text does not read
        file.appendBytes(TableUser.TableLog.FORMAT.record(9) { writeInt(0x0002FFFF) })
        file.appendBytes(TableUser.TableLog.FORMAT.record(1) { writeShort(2); writeByte(0xFF); writeByte(0xFF) })
        file.appendBytes(TableUser.TableLog.picked("wqwu", "你们", 5))
        val (session, store) = stored(file)
        assertEquals(listOf(listOf("恭恭敬敬", "工"), listOf("你们"), listOf("你好")), shown(session))
        repeat(2) { session.apply(Select(session.type("aaaa").candidates.indexOf("工"))) }
        store.close()
        val (again, reopened) = stored(file)
        assertEquals("工", shown(again)[0].first())
        reopened.close()
        assertEquals(6, TableUser(wubi).also { TableUser.Store(file, it).apply { open(); close() } }.picks("wqwu", "你们"))
    }

    @Test
    fun whatWasSeenReadsBackInTheOrderSeen() {
        val file = folder.root.resolve("wubi.user")
        val user = TableUser(wubi)
        val store = TableUser.Store(file, user).apply { open() }
        for (code in listOf("xa", "xb", "xc", "xa")) user.sighted(code, "字$code", 3)
        val live = user.seen("")
        assertEquals(listOf("xa", "xc", "xb"), live.map { it.first })
        store.close()
        fun reread() = TableUser(wubi).also { TableUser.Store(file, it).apply { open(); close() } }
        assertEquals(live, reread().seen(""))
        // compacted: the same order again
        TableUser(wubi).let { TableUser.Store(file, it, compactAt = 1).apply { open(); close() } }
        assertEquals(live, reread().seen(""))
        // the third time across a restart saves it
        reread().let { back ->
            TableUser.Store(file, back).apply {
                open()
                back.sighted("xa", "字xa", 3)
                close()
            }
        }
        reread().let {
            assertTrue(it.isSaved("xa", "字xa"))
            assertEquals(listOf("xc", "xb"), it.seen("").map { p -> p.first })
        }
    }

    @Test
    fun onlyTheMostRecentlySeenAreKept() {
        val file = folder.root.resolve("wubi.user")
        val user = TableUser(wubi)
        TableUser.Store(file, user).apply {
            open()
            repeat(1030) { user.sighted("x$it", "字", 5) }
            close()
        }
        val back = TableUser(wubi).also { TableUser.Store(file, it).apply { open(); close() } }
        assertEquals(user.seen(""), back.seen(""))
        assertEquals(1024, back.seen("").size)
        assertEquals("x1029", back.seen("").first().first)
        assertTrue(back.seen("x0").isEmpty())
    }

    @Test
    fun aPhraseALaterTableHasIsTheTablesAlone() {
        val file = folder.root.resolve("wubi.user")
        val (session, store) = stored(file)
        teach(session)
        store.close()
        val (again, reopened) = stored(file, table = dictionary("wqwu 你们", "wqiy 你", "wun 们", "vbg 好"))
        // once, not also as a phrase: wqwu itself commits it at once
        assertEquals(listOf("你们"), again.type("wqw").candidates)
        reopened.close()
    }

    @Test
    fun aPhraseSavedIsSavedOnce() {
        val user = TableUser(wubi)
        val records = ArrayList<ByteArray>()
        user.journal = { records += it }
        user.save("wqwu", "你们")
        user.save("wqwu", "你们")
        user.sighted("wqwu", "你们", 3)
        assertEquals(1, records.size)
        assertEquals(listOf("wqwu" to "你们"), user.saved("wq"))
        assertTrue(user.leadsAnywhere("wqw"))
        assertFalse(user.leadsAnywhere("x"))
    }
}
