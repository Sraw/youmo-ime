/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * [CharSelectData] and [QuickPhraseTable] against fcitx's own code: the golden files are what
 * `charselectdata.cpp` and `quickphraseprovider.cpp:29-104`, compiled unchanged from upstream fcitx5
 * at 442edbc9, return for the same input (`lib/ime-core/tools/host-golden`, `gen.py` says how).
 * contract: fcitx's data files and the goldens generated from its code, which mutation cannot see.
 */
class TempModeGoldenTest {

    private fun resource(name: String) = javaClass.getResourceAsStream("/host/$name")!!

    private fun lines(name: String) = resource(name).bufferedReader().readLines()

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val charSelect by lazy { CharSelectData(javaClass.getResourceAsStream("/pkgdata/unicode/charselectdata")!!.readBytes()) }

    @Test
    fun findMatchesFcitx() {
        val rows = lines("charselect-find.tsv")
        assertEquals(168, rows.size)
        for (row in rows) {
            val f = row.split('\t')
            val (q, count, hash) = f
            val first = f[3]
            val got = charSelect.find(unescapeGolden(q)).map { Integer.toHexString(it).uppercase() }
            assertEquals("count of '$q'", count.toInt(), got.size)
            assertEquals("first of '$q'", first, got.take(40).joinToString(","))
            assertEquals("list of '$q'", hash, sha(got.joinToString(",")))
        }
    }

    @Test
    fun everyNameMatchesFcitx() {
        val rows = lines("charselect-names.tsv")
        val blocks = rows.filter { it.startsWith("B\t") }.associate { it.split('\t').let { (_, b, h) -> b.toInt(16) to h } }
        assertEquals(0x110, blocks.size)
        for ((block, hash) in blocks) {
            val text = buildString {
                for (c in block shl 12 until (block + 1 shl 12)) append("%X\t%s\n".format(c, charSelect.name(c)))
            }
            assertEquals("block %X".format(block), hash, sha(text))
        }
        rows.filter { it.startsWith("S\t") }.forEach { row ->
            val (_, c, name) = row.split('\t', limit = 3)
            assertEquals(name, charSelect.name(c.toInt(16)))
        }
    }

    private fun checkQuickPhrase(table: QuickPhraseTable, golden: String) {
        var checked = 0
        val rows = lines(golden)
        var i = 0
        while (i < rows.size) {
            val (q, count, hash) = rows[i].split('\t').drop(1)
            val expected = rows.drop(i + 1).takeWhile { it.startsWith("C\t") }
            val got = table.populate(unescapeGolden(q)).map { (k, v) -> "${esc(k)}\t${esc(v)}" }
            assertEquals("count of '$q'", count.toInt(), got.size)
            assertEquals("first of '$q'", expected.map { it.removePrefix("C\t") }, got.take(40))
            assertEquals("list of '$q'", hash, sha(got.joinToString("\n")))
            i += 1 + expected.size
            checked++
        }
        assertTrue("no rows in the golden", checked > 0)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun dir(path: String) = FilePkgDataDir(File(javaClass.getResource("/host/$path")!!.toURI()))

    @Test
    fun shippedTablesMatchFcitx() {
        // the app's tables (app/src/main/host-assets)
        val shipped = FilePkgDataDir(File(javaClass.getResource("/pkgdata/data/quickphrase.d")!!.toURI()).parentFile.parentFile)
        checkQuickPhrase(QuickPhraseTable.load(listOf(shipped)), "quickphrase-builtin.tsv")
    }

    @Test
    fun loadingRulesMatchFcitx() {
        val table = QuickPhraseTable.load(listOf(dir("quickphrase-fixture/user"), dir("quickphrase-fixture/builtin")))
        checkQuickPhrase(table, "quickphrase-fixture.tsv")
    }
}
