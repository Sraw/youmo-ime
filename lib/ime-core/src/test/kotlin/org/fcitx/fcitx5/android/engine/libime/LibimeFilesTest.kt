/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.libime

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.libime.ZstdTest.Companion.resource
import org.fcitx.fcitx5.android.engine.libime.ZstdTest.Companion.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random

/**
 * libime's files read as its tools write them. The real ones are libime's (see
 * `resources/libime/README.md`); the others are written here, field by field, for what those lack.
 */
class LibimeFilesTest {

    @Test
    fun libimesTableReadsAsItsTextSource() {
        val text = LibimeFiles.table(resource("db.main.dict"))
        assertTrue(text.startsWith("KeyCode=0123456789\nLength=4\n[Data]\n0001 一\n0002 丁\n"))
        assertEquals(6694, text.lines().size - 1)
        // the text 电报码's source was built from, header aside: checked once against it
        assertEquals("b389359bf95ab3cf4c651652a48202520728bda1a847db866764c9c25a953f19", sha256(text.toByteArray()))
    }

    @Test
    fun aDictionaryTheUserImportedUnderLibime() {
        assertEquals(listOf("骁骎 xiao'qin 0.0", "琮璟 cong'jing 0.0"), LibimeFiles.pinyinDictionary(resource("extra.dict")))
    }

    @Test
    fun anEmptyUserDictionary() {
        assertEquals(emptyList<String>(), LibimeFiles.pinyinDictionary(resource("user.dict")))
    }

    @Test
    fun libimesHistoryAsItDumpsIt() {
        val words = "时间 QNLS 利好 HNKE 我们 WKCH 谢谢 NPNP 你 GN 今天 LTFS 天气 FSMN 很 KH 好 KE 我 WK 不 AX 知道 ONEE"
            .split(' ').chunked(2).joinToString(" ") { (word, code) -> "$word\t$code" }
        assertEquals(listOf(words), LibimeFiles.history(resource("user.history")))
    }

    @Test
    fun whatIsAndIsNotLibimes() {
        assertTrue(LibimeFiles.isPinyinDictionary(resource("extra.dict")))
        assertFalse(LibimeFiles.isPinyinDictionary(resource("db.main.dict")))
        assertTrue(LibimeFiles.isTable(resource("db.main.dict")))
        assertFalse(LibimeFiles.isTable(resource("extra.dict")))
        assertFalse(LibimeFiles.isTable(ByteArray(3)))
        assertThrows(DataFormatException::class.java) { LibimeFiles.table(resource("extra.dict")) }
        assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(resource("db.main.dict")) }
        assertThrows(DataFormatException::class.java) { LibimeFiles.history(resource("extra.dict")) }
    }

    @Test
    fun aVersionLibimeNeverWroteFails() {
        assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(file(PINYIN, 3) { emptyTrie() }) }
        assertThrows(DataFormatException::class.java) { LibimeFiles.history(file(HISTORY, 5) {}) }
        assertThrows(DataFormatException::class.java) { LibimeFiles.table(file(TABLE, 0) {}) }
    }

    @Test
    fun anUncompressedDictionary() {
        assertEquals(emptyList<String>(), LibimeFiles.pinyinDictionary(file(PINYIN, 1) { emptyTrie() }))
    }

    @Test
    fun aTableHeaderWithAllItHas() {
        val data = file(TABLE, 1) {
            writeInt('@'.code) // pinyin
            writeInt('&'.code) // prompt
            writeInt('^'.code) // construct
            writeInt(4)
            ints("abc".map { it.code })
            ints(listOf('\''.code))
            writeInt(2)
            rule(equal = true, length = 2, Triple(false, 1, 1), Triple(false, 2, 1))
            rule(equal = false, length = 4, Triple(false, 1, 1), Triple(true, 1, 0x80), Triple(true, 2, 0x81))
            repeat(5) { emptyTrie() } // phrases, characters, their construct and lookup codes, prompts
        }
        assertEquals(
            """
            KeyCode=abc
            Length=4
            InvalidChar='
            Pinyin=@
            Prompt=&
            ConstructPhrase=^
            [Rule]
            e2=p11+p21
            a4=p11+n1z+n2y
            [Data]

            """.trimIndent(),
            LibimeFiles.table(data),
        )
    }

    @Test
    fun aRuleWithAFlagLibimeHasNotFails() {
        val data = file(TABLE, 1) {
            repeat(4) { writeInt(0) }
            ints(emptyList())
            ints(emptyList())
            writeInt(1)
            writeInt(2)
        }
        assertThrows(DataFormatException::class.java) { LibimeFiles.table(data) }
    }

    @Test
    fun aCharacterNoneCanBeFails() {
        val data = file(TABLE, 1) {
            repeat(3) { writeInt(0) }
            writeInt(4)
            ints(listOf(0x110000))
            ints(emptyList())
            ints(emptyList())
            repeat(2) { emptyTrie() }
        }
        assertThrows(DataFormatException::class.java) { LibimeFiles.table(data) }
    }

    @Test
    fun historyPoolsNewestFirstAsLibimeKeepsThem() {
        val data = file(HISTORY, 2) {
            pool(listOf("旧"), listOf("新"))
            pool(listOf("你好\u0002GN", "世界\u0002QNJE"), listOf(), listOf("有\u0000空"))
            pool(listOf("带 空格"))
        }
        assertEquals(
            listOf("新", "旧", "你好\tGN 世界\tQNJE", "\"带 空格\""),
            LibimeFiles.history(data),
        )
    }

    @Test
    fun theOldestHistoryHasTwoPools() {
        val data = file(HISTORY, 1) {
            pool(listOf("甲"))
            pool(listOf("乙"))
            pool(listOf("丙")) // not a pool of it: ignored
        }
        assertEquals(listOf("甲", "乙"), LibimeFiles.history(data))
    }

    @Test
    fun aPoolKeepsAsManyAsLibimeDoes() {
        val data = file(HISTORY, 2) {
            pool(*Array(130) { listOf("$it") })
            pool()
            pool()
        }
        assertEquals((129 downTo 2).map { "$it" }, LibimeFiles.history(data))
    }

    @Test
    fun aCountPastTheEndFails() {
        val data = file(HISTORY, 1) { writeInt(1000) }
        assertThrows(DataFormatException::class.java) { LibimeFiles.history(data) }
    }

    @Test
    fun aCountPastWhatCanFollowFails() {
        // a thousand keys need 4,000 bytes, not 1,000
        val data = file(TABLE, 1) {
            repeat(4) { writeInt(0) }
            writeInt(1000)
            write(ByteArray(1000))
        }
        assertThrows(DataFormatException::class.java) { LibimeFiles.table(data) }
    }

    @Test
    fun aTrieSkipsWhatCedarMarksEmpty() {
        // root → placeholder 1 → 'G' at 0x47, whose base 0x100 puts N, O and P at 0x14E..0x150,
        // each key's rest in the tail: 你 costs -1.5, 拟 has cedar's NO_VALUE, 泥 a NaN cost
        val tail = ByteArrayOutputStream().apply { write(0) }
        val offsets = listOf("你" to (-1.5f).toRawBits(), "拟" to 0x7fc00001, "泥" to 0x7fc00005).map { (text, value) ->
            tail.size().also {
                tail.write("!$text".toByteArray())
                tail.write(0)
                repeat(4) { tail.write(value ushr (8 * it)) }
            }
        }
        val nodes = Array(0x151) { Node() }
        nodes[0] = Node(base = 0, check = -1, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = 'G'.code)
        nodes['G'.code] = Node(base = 0x100, check = 0, child = 'N'.code)
        nodes[0x14E] = Node(base = -offsets[0], check = 'G'.code, sibling = 'O'.code)
        nodes[0x14F] = Node(base = -offsets[1], check = 'G'.code, sibling = 'P'.code)
        nodes[0x150] = Node(base = -offsets[2], check = 'G'.code)
        assertEquals(listOf("你 ni -1.5"), LibimeFiles.pinyinDictionary(pinyinTrie(nodes, tail.toByteArray())))
    }

    @Test
    fun aTrieThatLoopsBackToItsRootFails() {
        // root → placeholder 1 → node 2, whose child is the root again
        val nodes = Array(3) { Node() }
        nodes[0] = Node(base = 0, check = 2, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = 2)
        nodes[2] = Node(base = 2, check = 0, child = 2)
        val e = assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(pinyinTrie(nodes, byteArrayOf())) }
        assertTrue(e.message, e.message!!.contains("node 0 is not a child of 2"))
    }

    @Test
    fun keysSharingATailFail() {
        // two leaves read the same tail: 1,000 bytes each, over and over
        val tail = ByteArray(1006) { if (it == 0 || it >= 1001) 0 else 'x'.code.toByte() }
        val nodes = Array(4) { Node() }
        nodes[0] = Node(base = 0, check = -1, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = 2)
        nodes[2] = Node(base = -1, check = 0, sibling = 3)
        nodes[3] = Node(base = -1, check = 0)
        assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(pinyinTrie(nodes, tail)) }
    }

    @Test
    fun spellingLibimesCodes() {
        assertEquals("shi'jian", LibimeFiles.spell("QNLS"))
        assertEquals("ni", LibimeFiles.spell("GN"))
        // no initial, and ü
        assertEquals("ai'lv", LibimeFiles.spell("XBH`"))
        assertEquals("", LibimeFiles.spell(""))
        assertNull(LibimeFiles.spell("GNL"))
        assertNull(LibimeFiles.spell("好的"))
    }

    @Test
    fun corruptFilesFailAsDataFormat() {
        // whatever the bytes, no other exception and no hang: imported files come from anywhere
        val random = Random(11)
        val body = Zstd.decompress(resource("db.main.dict"), 8)
        repeat(300) {
            val corrupt = body.copyOf()
            repeat(1 + random.nextInt(4)) { corrupt[random.nextInt(minOf(corrupt.size, 4096))] = random.nextInt(256).toByte() }
            val data = file(TABLE, 1) { write(corrupt) }
            try {
                LibimeFiles.table(data)
            } catch (_: DataFormatException) {
            }
        }
        for (length in listOf(8, 20, 100, body.size / 2)) {
            assertThrows(DataFormatException::class.java) { LibimeFiles.table(file(TABLE, 1) { write(body, 0, length) }) }
        }
    }

    private fun file(magic: Int, version: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(magic)
            writeInt(version)
            body()
        }
        return bytes.toByteArray()
    }

    private class Node(val base: Int = 0, val check: Int = -1, val sibling: Int = 0, val child: Int = 0)

    /** A pinyin dictionary of one trie, as cedar saves [nodes] and [tail]. */
    private fun pinyinTrie(nodes: Array<Node>, tail: ByteArray) = file(PINYIN, 1) {
        writeInt(tail.size)
        writeInt(nodes.size)
        write(tail)
        nodes.forEach {
            writeInt(it.base)
            writeInt(it.check)
        }
        write(ByteArray(12))
        nodes.forEach {
            writeByte(it.sibling)
            writeByte(it.child)
        }
        write(ByteArray((nodes.size ushr 8) * 20))
    }

    // cedar with no node: no tail, no nodes, its three list heads
    private fun DataOutputStream.emptyTrie() = write(ByteArray(20))

    private fun DataOutputStream.ints(values: List<Int>) {
        writeInt(values.size)
        values.forEach { writeInt(it) }
    }

    private fun DataOutputStream.rule(equal: Boolean, length: Int, vararg parts: Triple<Boolean, Int, Int>) {
        writeInt(if (equal) 1 else 0)
        writeByte(length)
        writeInt(parts.size)
        for ((fromBack, character, index) in parts) {
            writeInt(if (fromBack) 1 else 0)
            writeByte(character)
            writeByte(index)
        }
    }

    private fun DataOutputStream.pool(vararg sentences: List<String>) {
        writeInt(sentences.size)
        for (sentence in sentences) {
            writeInt(sentence.size)
            for (word in sentence) {
                val bytes = word.toByteArray()
                writeInt(bytes.size)
                write(bytes)
            }
        }
    }

    private companion object {
        const val PINYIN = 0x000fc613
        const val HISTORY = 0x000fc315
        const val TABLE = 0x000fcabe
    }
}
