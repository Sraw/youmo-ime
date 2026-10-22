/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.ImportFiles.Scel
import org.fcitx.fcitx5.android.engine.user.ImportFiles.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Other input methods' lists as the import reads them: a line's count, Sogou's .scel. */
class ImportFilesTest {

    @Test
    fun aLinesCountIsItsLastNumber() {
        assertEquals(12.0, ImportFiles.frequency("航星 hang'xing 12")!!, 0.0)
        assertEquals(3.0, ImportFiles.frequency("'hang'xing 航星 3")!!, 0.0)
        assertEquals(0.0, ImportFiles.frequency("航星\thang'xing\t0")!!, 0.0)
        assertNull(ImportFiles.frequency("航星 hang'xing"))
        // 3D is a word, not a count
        assertNull(ImportFiles.frequency("3D"))
        // a word of digits, of no count (review R11 F3005)
        assertNull(ImportFiles.frequency("520 wu'er'ling"))
        assertEquals(7.0, ImportFiles.frequency("520 wu'er'ling 7")!!, 0.0)
    }

    /** A .scel as Sogou writes one: [pinyin] its table of syllables, [groups] its words by reading, [phrases] the later section's. */
    private fun scel(
        pinyin: List<String>,
        groups: List<Pair<List<Int>, List<Pair<String, Int?>>>>,
        phrases: List<Pair<List<Int>, String>> = emptyList(),
        header: Int = 0x44,
    ): ByteArray {
        val body = Le()
        body.u32(pinyin.size)
        pinyin.forEachIndexed { i, p ->
            body.u16(i)
            body.string(p)
        }
        for ((reading, words) in groups) {
            body.u16(words.size)
            body.indices(reading)
            for ((text, count) in words) {
                body.string(text)
                if (count == null) {
                    body.u16(0)
                } else {
                    body.u16(10)
                    body.u16(count)
                    repeat(8) { body.u8(0) }
                }
            }
        }
        val phraseOffset = 0x1540 + body.size()
        for ((reading, text) in phrases) {
            repeat(17) { body.u8(if (it == 2) 1 else 0) }
            body.indices(reading)
            body.string(text)
        }
        val head = ByteBuffer.allocate(0x1540).order(ByteOrder.LITTLE_ENDIAN)
        head.put(byteArrayOf(0x40, 0x15, 0, 0, header.toByte(), 0x43, 0x53, 0x01, 0x01, 0, 0, 0))
        head.putInt(0x5C, phrases.size)
        head.putInt(0x60, phraseOffset)
        head.putInt(0x120, groups.size)
        return head.array() + body.toByteArray()
    }

    private class Le : ByteArrayOutputStream() {
        fun u8(v: Int) = write(v)
        fun u16(v: Int) {
            write(v and 0xff)
            write(v shr 8 and 0xff)
        }

        fun u32(v: Int) {
            u16(v and 0xffff)
            u16(v ushr 16)
        }

        fun indices(values: List<Int>) {
            u16(values.size * 2)
            values.forEach(::u16)
        }

        fun string(text: String) = indices(text.map { it.code })
    }

    private fun read(bytes: ByteArray) = ArrayList<Word>().also { out -> Scel.read(bytes) { out += it } }

    @Test
    fun aCellDictionarysWordsComeWithTheirReadingsAndCounts() {
        val pinyin = listOf("jing", "yuan", "liang", "zi", "lue")
        val bytes = scel(
            pinyin,
            listOf(
                listOf(0, 1) to listOf("鲸园" to 120, "京源" to null),
                listOf(2, 3) to listOf("量子" to 7),
                // a syllable the table has not: left out
                listOf(2, 9) to listOf("两字" to 1),
                // a count past 0x7fff: unsigned (review R11 F2937)
                listOf(4) to listOf("略" to 40000),
            ),
            phrases = listOf(listOf(0, 3) to "鲸子"),
        )
        assertTrue(Scel.isScel(bytes))
        assertEquals(
            listOf(Word("鲸园", "jing'yuan", 120), Word("京源", "jing'yuan", null), Word("量子", "liang'zi", 7), Word("略", "lve", 40000), Word("鲸子", "jing'zi", null)),
            read(bytes),
        )
        assertEquals("鲸园 jing'yuan 120", read(bytes).first().line())
    }

    @Test
    fun aFileOfNoSyllableTableReadsSogousOwn() {
        val bytes = scel(emptyList(), listOf(listOf(0, 1) to listOf("阿爱" to null)), header = 0x45)
        assertEquals(listOf(Word("阿爱", "a'ai", null)), read(bytes))
        // as fcitx's scel2org5 has it: 449 of them, the last a digit
        val last = scel(emptyList(), listOf(listOf(448) to listOf("九" to null)))
        assertEquals("9", read(last).single().pinyin)
    }

    @Test
    fun anythingElseIsNoCellDictionary() {
        assertFalse(Scel.isScel("鲸园 jing'yuan".toByteArray()))
        assertThrows(IOException::class.java) { read("鲸园 jing'yuan".toByteArray()) }
        val whole = scel(listOf("jing", "yuan"), listOf(listOf(0, 1) to listOf("鲸园" to 1)))
        assertThrows(IOException::class.java) { read(whole.copyOf(whole.size - 3)) }
    }

    // review-G S6: a table of more syllables than pinyin has, or a count past 2^31, is no cell
    // dictionary, said so; the counts here are of the syllables and the groups, not a word's
    @Test
    fun aCountTooLargeIsNoCellDictionary() {
        assertThrows(IOException::class.java) { read(scel((0 until 5000).map { "a" }, emptyList())) }
        val huge = scel(listOf("jing"), listOf(listOf(0) to listOf("京" to 1)))
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(0x120, Int.MIN_VALUE)
        assertThrows(IOException::class.java) { read(huge) }
    }
}
