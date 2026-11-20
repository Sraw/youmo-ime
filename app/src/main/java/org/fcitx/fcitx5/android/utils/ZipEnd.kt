/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Read off the file's end alone: `ZipFile` loads the whole central directory, and a malicious one could run out of
 * memory. Null where there is no consistent end record; a restore takes null for no backup.
 */
object ZipEnd {

    fun entries(file: File): Long? = try {
        RandomAccessFile(file, "r").use(::entries)
    } catch (_: IOException) {
        null
    }

    private fun entries(f: RandomAccessFile): Long? {
        val length = f.length()
        if (length < END_SIZE) return null
        val n = minOf(length, (END_SIZE + MAX_COMMENT).toLong()).toInt()
        val tail = read(f, length - n, n)
        // the last record whose comment reaches the file's end exactly: a comment may hold the signature
        val at = (tail.size - END_SIZE downTo 0).firstOrNull { u32(tail, it) == END && it + END_SIZE + u16(tail, it + 20) == tail.size }
            ?: return null
        val endAt = length - tail.size + at
        val onDisk = u16(tail, at + 8).toLong()
        val total = u16(tail, at + 10).toLong()
        val size = u32(tail, at + 12)
        val offset = u32(tail, at + 16)
        // a full field defers to zip64's record if any; a classic zip of exactly 65 535 entries has none and reads as is
        val full = listOf(total == FULL16, size == FULL32, offset == FULL32)
        if (full.any { it } && (full.all { it } || locatorAt(f, endAt))) return zip64(f, endAt)
        if (u16(tail, at + 4) != 0 || u16(tail, at + 6) != 0) return null
        return held(onDisk, total, size, offset, endAt)
    }

    private fun locatorAt(f: RandomAccessFile, endAt: Long) =
        endAt >= LOCATOR_SIZE && u32(read(f, endAt - LOCATOR_SIZE, LOCATOR_SIZE), 0) == LOCATOR

    // pointed to by its locator, just before the classic record
    private fun zip64(f: RandomAccessFile, endAt: Long): Long? {
        if (!locatorAt(f, endAt)) return null
        val recordAt = u64(read(f, endAt - LOCATOR_SIZE, LOCATOR_SIZE), 8)
        // subtracted, not added: a record offset near Long's end would wrap past the check
        if (recordAt < 0 || recordAt > endAt - LOCATOR_SIZE - RECORD64_SIZE) return null
        val r = read(f, recordAt, RECORD64_SIZE)
        if (u32(r, 0) != END64 || u32(r, 16) != 0L || u32(r, 20) != 0L) return null
        return held(u64(r, 24), u64(r, 32), u64(r, 40), u64(r, 48), recordAt)
    }

    // on one disk, before [dirEnd], with room for that many headers
    private fun held(onDisk: Long, total: Long, size: Long, offset: Long, dirEnd: Long): Long? =
        total.takeIf {
            onDisk == total && total >= 0 && size >= 0 && offset >= 0 && offset <= dirEnd - size &&
                total <= size / CENTRAL_HEADER_SIZE
        }

    private fun read(f: RandomAccessFile, at: Long, n: Int): ByteArray {
        f.seek(at)
        return ByteArray(n).also(f::readFully)
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and BYTE) or (b[at + 1].toInt() and BYTE shl BITS)
    private fun u32(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl BITS * 2)

    // a zip64 field past Long's range reads negative, and is refused as such
    private fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl BITS * 4)

    private const val BYTE = 0xff
    private const val BITS = 8
    private const val END = 0x06054b50L
    private const val END_SIZE = 22
    private const val MAX_COMMENT = 0xffff
    private const val LOCATOR = 0x07064b50L
    private const val LOCATOR_SIZE = 20
    private const val END64 = 0x06064b50L
    private const val RECORD64_SIZE = 56
    private const val CENTRAL_HEADER_SIZE = 46
    private const val FULL16 = 0xffffL
    private const val FULL32 = 0xffffffffL
}
