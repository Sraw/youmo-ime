/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.libime

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** A trie whose keys share a path [DEPTH] long, written field by field as cedar saves one. */
class LibimeTrieTest {

    @Test
    fun keysSharingALongPathComeToNoMoreThanAFewTimesTheTrie() {
        // 20 keys of 1,001 bytes from a trie of some 23 KB: read
        assertEquals(List(20) { DEPTH + 1 }, keyLengths(chain(leaves = 20)))
        // 255 of them: a quarter of a megabyte of keys from those 23 KB
        assertThrows(DataFormatException::class.java) { keyLengths(chain(leaves = 255)) }
    }

    private fun keyLengths(trie: ByteArray): List<Int> {
        val lengths = ArrayList<Int>()
        LibimeTrie.read(BigEndianInput(trie)).forEach { _, length, _ -> lengths += length }
        return lengths
    }

    private class Node(var base: Int = 0, var check: Int = -1, var sibling: Int = 0, var child: Int = 0)

    /**
     * root → placeholder 1 → 'a' at 97, then 'a' after 'a' down nodes 258 on to [DEPTH] deep, where
     * [leaves] children (labels 1 on, at [LEAVES] + label) each end a key in the tail's empty rest.
     */
    private fun chain(leaves: Int): ByteArray {
        val nodes = Array(LEAVES + 256) { Node() }
        nodes[0] = Node(base = 0, child = 1)
        nodes[1] = Node(base = 0, check = 0, sibling = A)
        val path = listOf(A) + (2..DEPTH).map { 256 + it }
        nodes[path[0]].check = 0
        for (k in 0 until path.size - 1) {
            nodes[path[k]].base = path[k + 1] xor A
            nodes[path[k]].child = A
            nodes[path[k + 1]].check = path[k]
        }
        val bottom = nodes[path.last()]
        bottom.base = LEAVES
        bottom.child = 1
        for (label in 1..leaves) {
            nodes[LEAVES + label] = Node(base = -1, check = path.last(), sibling = if (label < leaves) label + 1 else 0)
        }
        // a NUL at 1, its value 0 after it
        val tail = ByteArray(6)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
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
        return bytes.toByteArray()
    }

    private companion object {
        const val A = 'a'.code
        const val DEPTH = 1000
        const val LEAVES = 2048
    }
}
