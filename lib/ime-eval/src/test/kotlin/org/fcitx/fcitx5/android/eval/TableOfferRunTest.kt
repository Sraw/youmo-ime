/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class TableOfferRunTest {

    // 五笔's rules; each shorter code of 汪小 igih, 汪小明 iije, 小明 ihje leads on to a longer one, as in the real table
    private val table = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .rule("e3", "p11+p21+p31+p32")
        .rule("a4", "p11+p21+p31+n11")
        .entry("ig", "汪").entry("ih", "小").entry("je", "明")
        .entry("igiz", "测").entry("ihjz", "试").entry("iijz", "呀")
        .build().toByteArray().let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }

    private fun run(vararg sentences: String, names: List<String> = emptyList()) =
        TableOfferRun(table, TableOptions.WUBI, null).run(sentences.toList(), names)

    @Test
    fun eachCharacterAfterTheFirstIsAPlaceAnOfferMayCome() = assertRows(
        "汪小明: after 小 汪小, after 明 汪小明 and 小明; the sentence ends on an offer" to {
            assertEquals(TableOfferRun.Result(2, 2, 1, 1, 3, 0, 0), run("汪小明"))
        },
        "a comma ends the run: 小 after it is a place, nothing offered" to {
            assertEquals(TableOfferRun.Result(1, 0, 1, 0, 0, 0, 0), run("汪，小"))
        },
        "a name typed alone counts where it is offered whole; 明明 is one character twice, never" to {
            assertEquals(TableOfferRun.Result(0, 0, 0, 0, 0, 2, 1), run(names = listOf("汪小", "明明")))
        },
    )
}
