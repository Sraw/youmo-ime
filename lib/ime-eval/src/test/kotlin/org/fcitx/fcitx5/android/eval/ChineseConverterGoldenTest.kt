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

/** contract: the Kotlin s2tw against OpenCC itself (golden.tsv.gz, `opencc -c s2tw.json` over every dictionary key and data/ sentence). */
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

    /** UTF-8 with a lone surrogate read as CESU-8 (as JNI hands it over); anything [DataInputStream.readUTF] cannot take throws. */
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
