/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream

class WebTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun chatInSimplifiedChineseIsTaken() {
        // 6 particles in 20 Han chars, well over 5 a thousand
        assertTrue(WebText.accepts("https://bbs.example.com/1", "你们去哪儿吃饭啊？我还没吃呢，一起吧！好啊哈哈"))
    }

    @Test
    fun writtenPagesAreLeftOut() {
        // not a particle in it
        assertFalse(WebText.accepts("https://news.example.com/1", "今年一季度我国工业经济增势良好，新产业持续发力。"))
        // exactly 5 a thousand is enough, one fewer is not
        val han = "国".repeat(995)
        assertTrue(WebText.accepts("https://a.example.com/", han + "吗呢吧啊呀"))
        assertFalse(WebText.accepts("https://a.example.com/", han + "国吗呢吧啊"))
    }

    @Test
    fun traditionalChineseIsLeftOut() {
        val chat = "吗呢吧啊呀".repeat(4)
        // one traditional to four simplified is still simplified; one more is not
        assertTrue(WebText.accepts("https://a.example.com/", chat + "们这说时為"))
        assertFalse(WebText.accepts("https://a.example.com/", chat + "们这说时為會"))
        assertFalse(WebText.accepts("https://a.example.com/", chat + "們這說時為會"))
    }

    @Test
    fun encyclopediasAreLeftOut() {
        val chat = "你们去哪儿吃饭啊？我还没吃呢，一起吧！好啊哈哈"
        val encyclopedias = listOf(
            "https://zh.wikipedia.org/wiki/x", "https://zh.m.wikibooks.org/x", "https://zh.wiktionary.org/x",
            "https://www.wikiwand.com/zh/x", "https://baike.baidu.com/item/x", "http://www.baike.com/wiki/x",
            "https://zh.moegirl.org.cn/x", "https://x.fandom.com/zh/wiki/y", "https://wiki.biligame.com/x",
            "https://x.huijiwiki.com/y", "https://zh.wikihow.com/x", "https://zh.moegirl.org/x",
            // a host is the same in any case and with a final dot
            "https://ZH.WIKIPEDIA.ORG/wiki/x", "https://zh.wikipedia.org./wiki/x",
        )
        for (url in encyclopedias) assertFalse(url, WebText.accepts(url, chat))
        // only the host counts: a forum post that links to one is a forum post
        assertTrue(WebText.accepts("https://bbs.example.com/t?from=zh.wikipedia.org", chat))
    }

    @Test
    fun aShardIsReadInItsOrderAPageAtATime() {
        val shard = parquet(
            "https://a.example.com/1" to "  你去哪儿啊？ \n\n我还没吃呢",
            "https://zh.wikipedia.org/wiki/x" to "你去哪儿啊？我还没吃呢",
            "https://b.example.com/2" to "今年一季度我国工业经济增势良好",
            "https://c.example.com/3" to "好吧\n走吧",
            // a page with no url is still a page; one with no text is none
            null to "那行吧",
            "https://d.example.com/4" to null,
        )
        val pages = ArrayList<List<String>>()
        val dates = ArrayList<String>()
        WebText.read(shard) { page, date -> pages += page; dates += date }
        assertEquals(listOf(listOf("你去哪儿啊？", "我还没吃呢"), listOf("好吧", "走吧"), listOf("那行吧")), pages)
        // the first page is dated, the others not
        assertEquals(listOf("2023-06-14 00:00:00", "", ""), dates)
    }

    @Test
    fun aPathDuckDbWouldTakeForAGlobIsRefused() {
        for (name in listOf("shard[1].parquet", "*.parquet", "shard?.parquet")) {
            assertThrows(name, IllegalArgumentException::class.java) { WebText.read(File(tmp.root, name)) { _, _ -> } }
        }
    }

    @Test
    fun aShardDuckDbCannotReadIsAnIoError() {
        val notParquet = tmp.newFile("broken.parquet").apply { writeText("not parquet") }
        assertThrows(IOException::class.java) { WebText.read(notParquet) { _, _ -> } }
    }

    @Test
    fun theWordsAShardUsesThatTheDataLacksAreListedWithTheirYear() {
        val lm = tmp.newFile("lm.arpa").apply { writeText(ReadersTest.TINY_ARPA.trimIndent()) }.path
        val dict = tmp.newFile("dict.txt").apply { writeText("你\tni\t0\n好 hao\n") }.path
        val data = tmp.root.resolve("pinyin.data").path
        assertEquals(0, runCli(arrayOf("pinyin", "-o", data, "--lm", lm, dict), StringBuilder(), StringBuilder()))
        val shard = parquet("https://a.example.com/1" to "你好吧，好你呢\n你好啊", "https://b.example.com/2" to "你好吗")
        val out = StringBuilder()
        val candidates = tmp.root.resolve("candidates.tsv")
        val code = runCli(arrayOf("words", "-o", candidates.path, "--data", data, "--min-count", "2", shard.path), out, StringBuilder())
        assertEquals(out.toString(), 0, code)
        assertTrue(out.toString(), out.contains("pass 1: 2 pages"))
        assertTrue(out.toString(), out.contains("words: 1 runs of at least 2, 1 of them not in the data"))
        val rows = candidates.readLines().filter { !it.startsWith("#") }.map { it.split("\t") }
        assertEquals(listOf("你好"), rows.map { it[0] })
        // three times, first on the dated page; 吧/啊/吗 are single and no words of the data: not counted
        assertEquals(listOf("3", "2023", "0"), listOf(rows[0][1], rows[0][2], rows[0][6]))
    }

    @Test
    fun aModelIsMixedWithAShardAndAConversationFileTogether() {
        val lm = tmp.newFile("lm.arpa").apply { writeText(ReadersTest.TINY_ARPA.trimIndent()) }.path
        val shard = parquet("https://a.example.com/1" to "你好吧\n好你吗")
        val chat = tmp.root.resolve("chat.jsonl.gz")
        GZIPOutputStream(chat.outputStream()).bufferedWriter().use { it.write("[\"你 好\"]\n") }
        val out = StringBuilder()
        val code = runCli(
            arrayOf("mix", "-o", tmp.root.resolve("m.arpa").path, "--lm", lm, "--cutoffs", "1,1", shard.path, chat.path),
            out,
            StringBuilder(),
        )
        assertEquals(out.toString(), 0, code)
        // the page's two lines, split at the particles the model has not got, and the conversation
        assertTrue(out.toString(), out.contains("chat: 2 documents, 6 words"))
    }

    /**
     * A parquet file of (url, text, date) rows, written by DuckDB as FineWeb-2's are read, the first
     * page dated; a quote in its name, as read_parquet's path is a string literal.
     */
    private fun parquet(vararg pages: Pair<String?, String?>): File {
        val file = tmp.root.resolve("it's a shard.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE pages (url VARCHAR, text VARCHAR, date TIMESTAMP)") }
            connection.prepareStatement("INSERT INTO pages VALUES (?, ?, ?)").use { insert ->
                pages.forEachIndexed { i, (url, text) ->
                    insert.setObject(1, url)
                    insert.setObject(2, text)
                    insert.setObject(3, if (i == 0) java.sql.Timestamp.valueOf("2023-06-14 00:00:00") else null)
                    insert.executeUpdate()
                }
            }
            connection.createStatement().use { it.execute("COPY pages TO '${file.path.replace("'", "''")}' (FORMAT parquet)") }
        }
        return file
    }
}
