/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.table.TableText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class CodeTableReaderTest {

    private fun read(text: String) = CodeTableReader().apply { read(text.trimIndent().reader().buffered(), "table") }

    private fun CodeTableReader.table() = CodeTable.load(ByteBuffer.wrap(builder.build().toByteArray()))

    private fun CodeTable.texts(code: String) = exactRange(code).map { text(it) }

    private fun assertSourceError(line: Int, reason: String, block: () -> Unit) {
        try {
            block()
            fail("accepted")
        } catch (e: SourceException) {
            assertEquals(e.message, line, e.line)
            assertTrue(e.message, e.message!!.contains(reason))
        }
    }

    @Test
    fun codeTablesKeepHeaderRulesAndMarkedEntries() {
        val reader = read(TINY_TABLE)
        assertEquals(6, reader.entries)
        assertEquals(listOf("a;b 怪"), reader.strayCodes)
        val table = reader.table()
        assertEquals(mapOf("键码" to "abc", "码长" to "4", "拼音" to "@"), table.header)
        assertEquals(mapOf("e2" to "p11+p12+p21+p22"), table.rules)
        assertEquals(listOf("工", "式 子"), table.texts("a"))
        assertEquals(listOf("阿"), table.texts("@a"))
        // the ideographic space is a character to type, not whitespace to trim
        assertEquals(listOf("\u3000"), table.texts("b"))
    }

    @Test
    fun codeTableSemicolonsAreCommentsOnlyInTheHeader() {
        val reader = read("\uFEFF;c\n键码=;a\n[数据]\n;a 分\n")
        assertEquals(1, reader.entries)
        assertTrue(reader.strayCodes.isEmpty())
    }

    @Test
    fun badCodeTablesPointAtTheirLine() {
        assertSourceError(0, "no [数据]") { read("键码=a\n") }
        assertSourceError(1, "expected key=value") { read("键码\n") }
        assertSourceError(2, "expected \"code text\"") { read("[数据]\nabc\n") }
    }

    @Test
    fun libimesEnglishReadsAsTheChinese() {
        // as libime's saveText writes a table back
        val table = read(
            """
            # made by libime
            KeyCode=abc
            Length=4
            Pinyin=@
            [Rule]
            e2=p11+p12+p21+p22
            [Data]
            a 工
            @a 阿
            """,
        ).table()
        assertEquals(mapOf("键码" to "abc", "码长" to "4", "拼音" to "@"), table.header)
        assertEquals(mapOf("e2" to "p11+p12+p21+p22"), table.rules)
        assertEquals(listOf("阿"), table.texts("@a"))
    }

    @Test
    fun quotedTextsReadUnquoted() {
        val table = read("键码=ab\n[数据]\na \"x y\"\nb \"\\\"\\\\\"\n").table()
        assertEquals(listOf("x y"), table.texts("a"))
        assertEquals(listOf("\"\\"), table.texts("b"))
    }

    @Test
    fun phrasesAreCodedByTheRules() {
        val reader = read(
            """
            键码=abcd
            码长=4
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 工
            cd 作
            [词组]
            工作
            工无
            """,
        )
        assertEquals(listOf("工作", "工无"), reader.phrases)
        // 无 has no code, so 工无 has none
        assertEquals(1, TableText.codePhrases(reader))
        assertEquals(listOf("工作"), reader.table().texts("abcd"))
        assertEquals(0, TableText.codePhrases(read("键码=a\n[数据]\na 工\n")))
    }

    @Test
    fun newWordsGoInWhereTheirCodeIsFree() {
        val reader = read(
            """
            键码=abcdefgh
            码长=4
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 工
            cd 作
            ef 内
            gh 卷
            ac 式
            dh 子
            abcd 工作
            acdh 样
            efg 丁
            efga 甲
            cda 丙
            cdaa 乙
            """,
        )
        val added = TableText.addWords(
            reader,
            // known; coded; code taken by an earlier new word; known; the code of an entry (式子
            // is ac+dh, 样's); no code for 无; a single character
            listOf("工作", "内卷", "内卷儿", "作工", "工作", "式子", "工无", "工"),
        )
        assertEquals(2, added)
        val table = reader.table()
        assertEquals(listOf("内卷"), table.texts("efgh"))
        assertEquals(listOf("作工"), table.texts("cdab"))
        assertEquals(listOf("工作"), table.texts("abcd"))
        assertEquals(listOf("样"), table.texts("acdh"))
        // no rules, no words
        assertEquals(0, TableText.addWords(read("键码=a\n[数据]\na 工\n"), listOf("工工")))
    }

    @Test
    fun newWordsChangeNothingTheTablesShorterCodesDo() {
        val reader = read(
            """
            键码=abcdefgh
            码长=4
            拼音=@
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 工
            ba 作
            cc 内
            dd 卷
            ce 式
            ccda 样
            ccdb 杨
            ef 子
            @efab 夜
            """,
        )
        val added = TableText.addWords(
            reader,
            // ccdd: cc led to ccda, ccd to it too, and comes after it; abba: aba led nowhere, 顶屏
            // there; 内式 is ccce, and ccc led nowhere; efab is a pinyin spelling
            listOf("内卷", "工作", "内式", "子工", "式卷", "式内"),
        )
        // 式卷 (cedd): ce led only to 式, which commits itself; 式内 (cecc) as much
        assertEquals(1, added)
        assertEquals(listOf("内卷"), reader.table().texts("ccdd"))

    }

    @Test
    fun aTableIsCheckedAsTheEngineWillReadIt() {
        TableText.check(TINY_TABLE.trimIndent().reader().buffered(), "table")
        TableText.check("键码=a\n[数据]\n[词组]\n工作\n".reader().buffered(), "table")
        assertSourceError(2, "expected \"code text\"") { TableText.check("[数据]\nabc\n".reader().buffered(), "table") }
        assertSourceError(0, "nothing to type") { TableText.check("键码=a\n[数据]\n".reader().buffered(), "table") }
    }

    @Test
    fun valuesUnescapeAsFcitxEscapesThem() {
        assertEquals("a\nb\tc\"d\\e", unescapeValue("\"a\\nb\\tc\\\"d\\\\e\""))
        assertEquals("\u000c\r\u000b", unescapeValue("\"\\f\\r\\v\""))
        // an escape fcitx does not make stands for the character
        assertEquals("q", unescapeValue("\"\\q\""))
        // not quoted, or not quoted as fcitx quotes: as it is
        assertEquals("a\\nb", unescapeValue("a\\nb"))
        assertEquals("\"", unescapeValue("\""))
        assertEquals("\"a\"b\"", unescapeValue("\"a\"b\""))
        assertEquals("\"a\\\"", unescapeValue("\"a\\\""))
        assertEquals("", unescapeValue("\"\""))
    }

    @Test
    fun valuesEscapeAsFcitxEscapesThem() {
        assertEquals("好", escapeValue("好"))
        assertEquals("\"a b\"", escapeValue("a b"))
        assertEquals("\"a\\nb\\tc\\\"d\\\\e\\f\\r\\v\"", escapeValue("a\nb\tc\"d\\e\u000c\r\u000b"))
        for (value in listOf("", "a b", "\"", "a\\n", "　", "x\"y\"")) assertEquals(value, unescapeValue(escapeValue(value)))
    }

    @Test
    fun valuesSplitAsFcitxConsumesThem() {
        assertEquals(listOf("你好", "ni'hao", "0"), splitValues(" 你好\tni'hao \u000B0\r"))
        assertEquals(listOf("a b", "c\\", "x\\ty\""), splitValues("\"a b\" \"c\\\\\"x\\ty\""))
        assertEquals(listOf("x\ty\"", "q"), splitValues("\"x\\ty\\\"\" \"\\q\""))
        // what follows a closing quote starts the next value
        assertEquals(listOf("a", "b"), splitValues("\"a\"b"))
        // a quote that never closes is the value's own
        assertEquals(listOf("\"a", "b"), splitValues("\"a b"))
        assertEquals(listOf("a\"b"), splitValues("a\"b"))
        assertEquals(listOf("\"a\\\""), splitValues("\"a\\\""))
        assertEquals(emptyList<String>(), splitValues(" \t"))
        assertEquals(listOf(""), splitValues("\"\""))
    }

    companion object {
        const val TINY_TABLE = """
            ;fcitx 版本 0x03 码表文件
            键码=abc
            码长=4
            拼音=@
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            a 工
            a	式 子
            @a 阿
            @z 杂
            a;b 怪
            b 　
        """ // the last text is U+3000, the ideographic space
    }
}
