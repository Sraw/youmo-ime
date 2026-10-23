/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class CodedIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val wubi = dictionary("wqiy 你", "vbg 好", "wqvb 你好", "trnt 我", "wu 们", "wuyy 门", "wqwu 你们")

    private fun file(vararg lines: String): DictionaryFile =
        File(folder.root, "merged.ymd").also { DictionaryCompiler.compile(listOf(lines.asSequence()), it) }.let(DictionaryFile::open)

    @Test
    fun theFilesWordsAreCodedAsCodedWordsCodesThem() {
        // 你好 the table's own; 我们 in two readings, coded once; 们 a character; 好你 vbwq; 你门 and 你们 one code
        val lines = arrayOf("你好 ni'hao 0.5", "我们 wo'men 0.4", "我们 wo'meng 0.1", "们 men 0.3", "好你 hao'ni 0.2", "你门 ni'men 0.1", "x你 x'ni 0.1")
        val d = file(*lines)
        val index = CodedIndex.build(wubi, d, File(folder.root, "wubi.ymc"), "k")!!
        val words = CodedWords(wubi, (0 until d.size).map(d::text))
        assertEquals(3, index.size)
        for (prefix in listOf("t", "tr", "trwu", "trwux", "v", "vbwq", "w", "wq", "wqwu", "a", "")) {
            assertEquals(prefix, words.words(prefix).toSet(), index.words(prefix, d).toSet())
            assertEquals(prefix, words.leadsAnywhere(prefix), index.leadsAnywhere(prefix))
        }
        assertTrue(index.has("trwu", "我们", d))
        assertFalse(index.has("trw", "我们", d))
        assertFalse(index.has("wqvb", "你好", d))
    }

    @Test
    fun openedAgainForItsKeyOnlyAndNotDamaged() {
        val d = file("我们 wo'men 0.4", "好你 hao'ni 0.2")
        val dest = File(folder.root, "wubi.ymc")
        CodedIndex.build(wubi, d, dest, "k")
        assertEquals(listOf("trwu" to "我们"), CodedIndex.open(dest, "k")!!.words("tr", d))
        assertNull(CodedIndex.open(dest, "other"))
        assertNull(CodedIndex.open(File(folder.root, "none.ymc"), "k"))
        // a record's byte changed, or the file cut short: built again rather than read wrong
        val bytes = dest.readBytes()
        dest.writeBytes(bytes.copyOf().also { it[it.size - 12] = (it[it.size - 12] + 1).toByte() })
        assertNull(CodedIndex.open(dest, "k"))
        dest.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(CodedIndex.open(dest, "k"))
        // no temporaries left beside it
        assertEquals(setOf("merged.ymd", "wubi.ymc"), folder.root.list()!!.toSet())
    }

    @Test
    fun aSecondBuildOfOneIndexWaitsForTheFirst() {
        // a newer file's build started while the older still runs (each on its own warm-up thread):
        // they shared the work dir and temporary, the newer deleting the older's runs (review F3271)
        val older = file("我们 wo'men 0.4")
        val newer = File(folder.root, "newer.ymd").also { DictionaryCompiler.compile(listOf(sequenceOf("好你 hao'ni 0.2")), it) }.let(DictionaryFile::open)
        val dest = File(folder.root, "wubi.ymc")
        var second: CodedIndex? = null
        val other = Thread { second = CodedIndex.build(wubi, newer, dest, "new") }
        var started = false
        val first = CodedIndex.build(wubi, older, dest, "old", wanted = {
            if (!started) {
                started = true
                other.start()
                // long enough for the second to run whole, had it not waited
                other.join(WAIT_MS)
            }
            true
        })
        other.join()
        assertEquals(listOf("trwu" to "我们"), first!!.words("tr", older))
        assertEquals(listOf("vbwq" to "好你"), second!!.words("vb", newer))
        assertNotNull(CodedIndex.open(dest, "new"))
        assertEquals(setOf("merged.ymd", "newer.ymd", "wubi.ymc"), folder.root.list()!!.toSet())
    }

    @Test
    fun manyWordsSpillIntoRunsAndMergeInOrder() {
        // past one run in the heap: the runs on disk merged as one index, sorted by code
        val (table, lines) = manyWords(chars = 400)
        val d = file(*lines.toList().toTypedArray())
        assertTrue(d.size > CodedIndex.RUN)
        val index = CodedIndex.build(table, d, File(folder.root, "big.ymc"), "k")!!
        val coded = CodedWords(table, (0 until d.size).map(d::text))
        assertEquals(coded.size, index.size)
        for (prefix in listOf("a", "ab", "abc", "abcd", "zz")) {
            assertEquals(prefix, coded.words(prefix).toSet(), index.words(prefix, d).toSet())
            assertEquals(prefix, index.words(prefix, d).map { it.first }, index.words(prefix, d).map { it.first }.sorted())
        }
    }

    @Test
    fun aBuildNoLongerWantedStopsAndWritesNothing() {
        val d = file("我们 wo'men 0.4")
        val dest = File(folder.root, "wubi.ymc")
        assertNull(CodedIndex.build(wubi, d, dest, "k") { false })
        assertFalse(dest.exists())
        assertNotNull(CodedIndex.build(wubi, d, dest, "k") { true })
    }

    @Test
    fun aSessionReadsTheLongerCodesAsFarAsShownAndInTheOrderItHadWithAllOfThem() {
        // codes of two, three and four letters, so the merged words of longer codes go between the table's
        val table = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy")
            .header("码长", "4")
            .rule("e2", "p11+p21")
            .rule("e3", "p11+p21+p31")
            .rule("a4", "p11+p21+p31+p41")
            .apply { listOf("wqiy 你", "vbg 好", "trnt 我", "wu 们", "wuyy 门", "ww 冈", "wwvt 网").forEach { e -> e.split(' ').let { entry(it[0], it[1]) } } }
            .build().toByteArray().let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }
        val d = file(
            "你们 ni'men 0.5", "们你 men'ni 0.4", "你好 ni'hao 0.3", "门好 men'hao 0.2", "你们好 ni'men'hao 0.1",
            "你们好我 ni'men'hao'wo 0.1", "我们 wo'men 0.1", "好你 hao'ni 0.1", "你我 ni'wo 0.1",
        )
        val index = CodedIndex.build(table, d, File(folder.root, "t.ymc"), "k")!!
        val small = CodedWords(table, listOf("们们", "你门好"))
        val all = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = small.words(prefix) + index.words(prefix, d)
            override fun leadsAnywhere(prefix: String) = small.leadsAnywhere(prefix) || index.leadsAnywhere(prefix)
        }
        val lazy = object : SharedWords by all {
            override fun words(prefix: String) = small.words(prefix) + index.exact(prefix, d)
            override fun longer(prefix: String, byLength: Boolean) = index.longer(prefix, byLength, d)
        }
        for (options in listOf(TableOptions.WUBI, TableOptions.WUBI.copy(noSortInputLength = 2), TableOptions.WUBI.copy(sortByCodeLength = false))) {
            val eager = TableSession(table, options.copy(autoSelect = false), shared = all, language = learning())
            val paged = TableSession(table, options.copy(autoSelect = false), shared = lazy, language = learning())
            fun both(code: String): List<String> {
                eager.apply(Action.Reset)
                paged.apply(Action.Reset)
                val expected = eager.type(code).second.let { eager.candidates(0, 50).map { it.text } }
                assertEquals("$options $code", expected, paged.type(code).second.let { paged.candidates(0, 50).map { it.text } })
                return expected
            }
            for (code in listOf("w", "ww", "wv", "wq", "v", "t")) both(code)
            // pinned under w: a merged word of a longer code, then a table entry of one
            for (text in listOf("你们好我", "网")) {
                val at = both("w").indexOf(text)
                eager.apply(Action.Pin(at))
                paged.apply(Action.Pin(at))
                assertEquals(text, both("w").first())
            }
        }
    }

    private companion object {
        const val WAIT_MS = 500L
    }
}
