/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.host.ChineseConverter
import org.fcitx.fcitx5.android.host.OpenCCData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * contract: the Kotlin s2tw against OpenCC 1.2.0 itself: golden.tsv.gz is `opencc -c s2tw.json` of the host
 * build (tools/opencc/build.sh, golden.py) over every dictionary key of the release, every Chinese
 * sentence of data/ and edge cases. Every case must match. The converter reads the data as the app
 * does: the dictionaries of lib/ime-dict-tool/opencc compiled by [OpenCCData.write], then mapped.
 */
class ChineseConverterGoldenTest {

    private val converter = ChineseConverter.s2tw(
        OpenCCData.read(
            ByteBuffer.wrap(
                OpenCCData.write(ChineseConverter.S2TW.map { OpenCCData.parse(File("../ime-dict-tool/opencc/$it.txt").readText()) }),
            ),
        ),
    )

    @Test
    fun everyGoldenCaseMatches() {
        val lines = GZIPInputStream(javaClass.getResourceAsStream("/opencc/golden.tsv.gz")!!).readBytes().split('\n'.code.toByte())
        val header = String(lines[0], Charsets.UTF_8).split('\t')
        val column = header.indexOf("s2tw")
        // the file ends with a newline: nothing after the last one
        val cases = lines.subList(1, lines.size - 1)
        val wrong = cases.mapNotNull { line ->
            val fields = line.split('\t'.code.toByte())
            val input = decode(fields[0])
            // an empty column: OpenCC's output is the input
            val expected = fields[column].takeIf { it.isNotEmpty() }?.let(::decode) ?: input
            converter.convert(input).takeIf { it != expected }?.let { "$input → $it, OpenCC $expected" }
        }
        assertEquals(82_800, cases.size)
        assertEquals("cases that differ (the first 20): ${wrong.take(20)}", 0, wrong.size)
    }

    private fun ByteArray.split(by: Byte): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in indices) if (this[i] == by) {
            out += copyOfRange(start, i)
            start = i + 1
        }
        out += copyOfRange(start, size)
        return out
    }

    /**
     * UTF-8, but a lone surrogate written as CESU-8 (as JNI's modified UTF-8 hands one over) is that
     * surrogate: what the Kotlin side sees for such a string. Through [DataInputStream.readUTF], so
     * only for what the golden set has: a line under 64 KiB, and no 4-byte UTF-8 (a supplementary
     * character) in a line that also has a lone surrogate; anything else throws, never compares wrong.
     */
    private fun decode(bytes: ByteArray): String {
        val s = String(bytes, Charsets.UTF_8)
        if ('�' !in s) return s
        val framed = ByteArrayOutputStream().apply {
            write(bytes.size shr 8)
            write(bytes.size and 0xff)
            write(bytes)
        }.toByteArray()
        return DataInputStream(framed.inputStream()).readUTF()
    }
}
