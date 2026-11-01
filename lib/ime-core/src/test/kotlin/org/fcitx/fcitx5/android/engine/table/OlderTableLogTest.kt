/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A table's log of the builds till 0.4.0 read for its phrases: it is read once (the record's mark
 * after), so a log 0.4 left torn or odd must give what it holds whole and never stop the read.
 *
 * contract: a 0.4.0 install's table log format (its header, record types), written out literally.
 */
class OlderTableLogTest {
    private val format = RecordFormat("FXUT", 1)

    private fun rec(type: Int, code: String, text: String) = format.record(type.toByte()) { writeUTF(code); writeUTF(text) }

    private fun log(vararg records: ByteArray) = records.fold(format.header()) { all, r -> all + r }

    private class Row(val why: String, val bytes: ByteArray, val phrases: List<Pair<String, String>>)

    @Test
    fun whatEachLogKeepsAsPhrases() {
        val saved = rec(SAVED, "vbvn", "好女")
        val rows = listOf(
            Row("picked and saved, in the log's order", log(rec(PICKED, "wq", "你们"), saved), listOf("wq" to "你们", "vbvn" to "好女")),
            Row("forgotten after: gone", log(saved, rec(FORGOT, "vbvn", "好女")), emptyList()),
            Row("forgotten then saved again: kept", log(saved, rec(FORGOT, "vbvn", "好女"), saved), listOf("vbvn" to "好女")),
            Row("saved twice: once", log(saved, saved), listOf("vbvn" to "好女")),
            Row("an empty code or text is no phrase", log(rec(SAVED, "", "好"), rec(SAVED, "vb", ""), saved), listOf("vbvn" to "好女")),
            Row("seen (3) and a later build's type: skipped", log(rec(3, "wq", "你"), rec(9, "wq", "们"), saved), listOf("vbvn" to "好女")),
            Row("a record cut short at the end: the whole ones before", log(saved, rec(SAVED, "wq", "你们").dropLast(3).toByteArray()), listOf("vbvn" to "好女")),
            Row("no FXUT header: none", "FXLB".toByteArray() + saved, emptyList()),
            Row("another format's log: none", RecordFormat("FXLL", 1).header() + saved, emptyList()),
            Row("empty: none", ByteArray(0), emptyList()),
        )
        assertRows(rows, { it.why }) { assertEquals(it.phrases, OlderTableLog.phrases(it.bytes)) }
    }

    private companion object {
        const val PICKED = 1
        const val SAVED = 2
        const val FORGOT = 4
    }
}
