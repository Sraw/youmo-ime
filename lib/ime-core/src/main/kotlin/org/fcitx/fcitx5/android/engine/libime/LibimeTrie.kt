/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.libime

import org.fcitx.fcitx5.android.engine.data.DataFormatException

/**
 * libime's `DATrie` (cedar's double array with tails) as it saves one, only walked for its keys:
 * nothing is looked up in it. A value is its raw 32 bits, what they mean up to the file.
 *
 * A file is not trusted to be a tree. A node is a child only of the one its check names, so
 * with the root never a child each is walked once; tails, which keys could share, are read no
 * more than their length all told, as in any trie cedar saves. Keys share their paths too, so a
 * long path to many leaves would hand out its length for each: the keys handed out come to no
 * more than a few times the trie's size. A crafted file cannot make it walk, keep, or hand out
 * much more than it holds.
 */
internal class LibimeTrie private constructor(
    private val tail: ByteArray,
    private val base: IntArray,
    private val check: IntArray,
    private val sibling: ByteArray,
    private val child: ByteArray,
) {

    fun interface Visitor {
        /** A key: the first [length] bytes of [key], which is reused for the next. */
        fun visit(key: ByteArray, length: Int, value: Int)
    }

    /** Each key and its value, in the trie's order (by byte). */
    fun forEach(visitor: Visitor) {
        if (base.isEmpty()) return
        tailRead = 0
        keyBytes = 0
        try {
            walk(0, 0, ByteArray(64), visitor)
        } catch (@Suppress("TooGenericExceptionCaught") e: IndexOutOfBoundsException) {
            throw DataFormatException("libime trie points past its end", e)
        }
    }

    private var tailRead = 0
    private var keyBytes = 0L

    // what its keys may come to: the trie as saved, a node 10 bytes
    private val maxKeyBytes = KEYS_PER_BYTE * (tail.size + 10L * base.size)

    private fun walk(from: Int, depth: Int, keyBuffer: ByteArray, visitor: Visitor) {
        var key = keyBuffer
        val b = base[from]
        if (b < 0) return tailKey(-b, depth, key, visitor)
        var label = u8(child, from)
        // the root's first child is cedar's placeholder
        if (from == 0) label = u8(sibling, node(b xor label, from, placeholder = true))
        if (from == 0 && label == 0) return
        while (true) {
            val to = node(b xor label, from)
            // a key no dictionary has: before the stack runs out
            if (depth >= MAX_DEPTH) throw DataFormatException("libime trie deeper than $MAX_DEPTH")
            if (label == 0) {
                // a key ends here: the value is the node's base
                visit(visitor, key, depth, base[to])
            } else {
                if (depth + 1 >= key.size) key = key.copyOf(key.size * 2)
                key[depth] = label.toByte()
                walk(to, depth + 1, key, visitor)
            }
            val next = u8(sibling, to)
            if (next == 0) break
            // siblings come in order: one that does not loops
            if (next <= label) throw DataFormatException("libime trie siblings out of order")
            label = next
        }
    }

    /** A key whose rest is in the tail from [at], NUL-ended, its value after it. */
    private fun tailKey(at: Int, depth: Int, keyBuffer: ByteArray, visitor: Visitor) {
        var key = keyBuffer
        var i = at
        var length = depth
        while (tail[i] != 0.toByte()) {
            if (++tailRead > tail.size) throw DataFormatException("libime trie keys share its tail")
            if (length == key.size) key = key.copyOf(key.size * 2)
            key[length++] = tail[i++]
        }
        visit(visitor, key, length, le32(tail, i + 1))
    }

    // cedar's marks for a key erased, or a node kept with none, are no value to read
    private fun visit(visitor: Visitor, key: ByteArray, length: Int, value: Int) {
        if (value in NO_VALUES) return
        keyBytes += length
        if (keyBytes > maxKeyBytes) throw DataFormatException("libime trie's keys come to more than $KEYS_PER_BYTE times its size")
        visitor.visit(key, length, value)
    }

    private fun node(index: Int, parent: Int, placeholder: Boolean = false): Int {
        // the root is its own parent, as cedar's placeholder: no other node leads back to it
        val child = if (index == 0) placeholder else index in base.indices && check[index] == parent
        if (!child) {
            throw DataFormatException("libime trie node $index is not a child of $parent")
        }
        return index
    }

    companion object {
        // keys branch near their start, their rest kept in the tail
        private const val MAX_DEPTH = 1024

        // the 电报码 table libime ships hands out a third of its tries' size in keys, the zhwiki and
        // moegirl dictionaries 0.6 of theirs: room to spare
        private const val KEYS_PER_BYTE = 4L

        // NO_VALUE and NO_PATH: -1 and -2 in an integer trie, NaN 1 and 2 in a float one
        private val NO_VALUES = intArrayOf(-1, -2, 0x7fc00001, 0x7fc00002)

        /** The trie at [input]'s position, which is left past it. */
        fun read(input: BigEndianInput): LibimeTrie {
            val tailLength = input.u32()
            val size = input.u32()
            // the node, sibling/child and block arrays: 8 + 2 + 20 / 256 bytes a node
            if (tailLength < 0 || size < 0 || tailLength.toLong() + size * 10L > input.remaining) {
                throw DataFormatException("libime trie of $size nodes past the end")
            }
            val tail = input.bytes(tailLength)
            val base = IntArray(size)
            val check = IntArray(size)
            for (i in 0 until size) {
                base[i] = input.u32()
                check[i] = input.u32()
            }
            input.skip(12) // the heads of cedar's block lists
            val sibling = ByteArray(size)
            val child = ByteArray(size)
            for (i in 0 until size) {
                sibling[i] = input.u8().toByte()
                child[i] = input.u8().toByte()
            }
            input.skip((size ushr 8) * 20) // cedar's blocks, for inserting
            return LibimeTrie(tail, base, check, sibling, child)
        }

        private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF

        // a tail's value is little-endian, unlike the rest of the file
        private fun le32(bytes: ByteArray, at: Int) =
            u8(bytes, at) or (u8(bytes, at + 1) shl 8) or (u8(bytes, at + 2) shl 16) or (u8(bytes, at + 3) shl 24)
    }
}

/** What libime marshals: big-endian numbers, strings as a length and their bytes. */
internal class BigEndianInput(private val data: ByteArray, private var pos: Int = 0) {

    val remaining get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw DataFormatException("libime file cut short")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u32(): Int {
        need(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xFF) }
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    /** libime's `marshallString`: a 32-bit length, then UTF-8. */
    fun string(): String = String(bytes(u32()), Charsets.UTF_8)
}
