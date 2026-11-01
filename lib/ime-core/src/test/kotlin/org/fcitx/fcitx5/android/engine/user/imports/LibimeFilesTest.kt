/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user.imports

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.user.imports.Fixtures.resource
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
    fun aDictionaryTheUserImportedUnderLibime() {
        assertEquals(listOf("骁骎 xiao'qin 0.0", "琮璟 cong'jing 0.0"), LibimeFiles.pinyinDictionary(resource("extra.dict")))
    }

    @Test
    fun anEmptyUserDictionary() {
        assertEquals(emptyList<String>(), LibimeFiles.pinyinDictionary(resource("user.dict")))
    }

    @Test
    fun whatIsAndIsNotLibimes() {
        assertTrue(LibimeFiles.isPinyinDictionary(resource("extra.dict")))
        assertFalse(LibimeFiles.isPinyinDictionary(resource("db.main.dict")))
        assertFalse(LibimeFiles.isPinyinDictionary(ByteArray(3)))
        assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(resource("db.main.dict")) }
    }

    @Test
    fun aVersionLibimeNeverWroteFails() {
        assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(file(PINYIN, 3) { emptyTrie() }) }
        assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(file(PINYIN, 0) {}) }
    }

    @Test
    fun anUncompressedDictionary() {
        assertEquals(emptyList<String>(), LibimeFiles.pinyinDictionary(file(PINYIN, 1) { emptyTrie() }))
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
        val e = assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(pinyinTrie(nodes, tail)) }
        assertTrue(e.message, e.message!!.contains("share its tail"))
    }

    @Test
    fun siblingsOutOfOrderFail() {
        // root → placeholder 1 → 3, whose next sibling is 2: a label going back down, as a loop of siblings must
        val nodes = Array(4) { Node() }
        nodes[0] = Node(base = 0, check = -1, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = 3)
        nodes[3] = Node(base = -1, check = 0, sibling = 2)
        nodes[2] = Node(base = -1, check = 0)
        val e = assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(pinyinTrie(nodes, ByteArray(6))) }
        assertTrue(e.message, e.message!!.contains("siblings out of order"))
    }

    @Test
    fun aTrieDeeperThanAnyKeyFails() {
        // root → placeholder 1 → 'a' at 97, then 'a' after 'a' down nodes 258 to 1281: 1,025 deep
        val a = 'a'.code
        val path = listOf(a) + (258..1281)
        val nodes = Array(path.last() + 1) { Node() }
        nodes[0] = Node(base = 0, check = -1, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = a)
        for (k in path.indices) {
            val next = path.getOrNull(k + 1) ?: 0
            nodes[path[k]] = Node(base = next xor a, check = if (k == 0) 0 else path[k - 1], child = a)
        }
        val e = assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(pinyinTrie(nodes, byteArrayOf())) }
        assertTrue(e.message, e.message!!.contains("deeper than"))
    }

    @Test
    fun aTriePointingPastItsTailFails() {
        // a key whose rest would be 100 bytes into a tail of 6
        val nodes = Array(3) { Node() }
        nodes[0] = Node(base = 0, check = -1, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = 2)
        nodes[2] = Node(base = -100, check = 0)
        val e = assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(pinyinTrie(nodes, ByteArray(6))) }
        assertTrue(e.message, e.message!!.contains("points past its end"))
    }

    @Test(timeout = 60_000)
    fun corruptFilesFailAsDataFormat() {
        // whatever the bytes, no other exception and no hang: imported files come from anywhere
        val random = Random(11)
        val body = Zstd.decompress(resource("extra.dict"), 8)
        repeat(300) {
            val corrupt = body.copyOf()
            repeat(1 + random.nextInt(4)) { corrupt[random.nextInt(corrupt.size)] = random.nextInt(256).toByte() }
            val data = file(PINYIN, 1) { write(corrupt) }
            try {
                LibimeFiles.pinyinDictionary(data)
            } catch (_: DataFormatException) {
            }
        }
        for (length in listOf(8, 20, body.size / 2)) {
            assertThrows(DataFormatException::class.java) { LibimeFiles.pinyinDictionary(file(PINYIN, 1) { write(body, 0, length) }) }
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
    private fun pinyinTrie(nodes: Array<Node>, tail: ByteArray) = file(PINYIN, 1) { trie(nodes, tail) }

    private fun DataOutputStream.trie(nodes: Array<Node>, tail: ByteArray) {
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

    private companion object {
        const val PINYIN = 0x000fc613
    }
}
