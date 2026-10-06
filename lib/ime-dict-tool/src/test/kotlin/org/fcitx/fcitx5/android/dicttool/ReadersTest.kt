/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.fields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class ReadersTest {

    private fun arpa(text: String, sink: ArpaReader.Sink = ArpaReader.Sink { _, _, _ -> }) =
        ArpaReader.read(text.trimIndent().reader().buffered(), "lm", sink)

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
    fun fieldsSplitOnAnyRunOfWhitespace() {
        assertEquals(listOf("你好", "ni'hao", "0"), " 你好\t ni'hao  0\t\r".fields())
        assertEquals(listOf("a", "\u3000"), "a \u3000".fields())
        assertEquals(emptyList<String>(), " \t ".fields())
    }

    @Test
    fun arpaNgramsArriveWithTheirBackoffs() {
        val got = ArrayList<String>()
        val counts = arpa(TINY_ARPA) { words, prob, backoff -> got += "${words.joinToString(" ")} $prob $backoff" }
        assertEquals(listOf(3, 1, 1), counts)
        assertEquals(listOf("<unk> -5.0 0.0", "你 -1.0 -0.5", "好 -1.5 0.0", "你 好 -0.3 -0.1", "你 好 你 -0.7 0.0"), got)
    }

    @Test
    fun arpaStructureIsChecked() {
        assertSourceError(0, "declares 2 1-grams, the file has 3") { arpa(TINY_ARPA.replace("ngram 1=3", "ngram 1=2")) }
        assertSourceError(0, "truncated") { arpa(TINY_ARPA.replace("\\end\\", "")) }
        // a preamble before \data\ is skipped, as SRILM and KenLM do
        assertEquals(listOf(3, 1, 1), arpa("# made by a toolkit\n" + TINY_ARPA.trimIndent()))
        assertSourceError(5, "only up to 3-grams") { arpa(TINY_ARPA.replace("ngram 3=1", "ngram 3=1\nngram 4=1")) }
        assertSourceError(15, "expected a 3-gram") { arpa(TINY_ARPA.replace("-0.7\t你 好 你", "-0.7\t你 好 你\t-0.2")) }
        assertSourceError(8, "bad probability") { arpa(TINY_ARPA.replace("-1.0\t你", "x\t你")) }
    }

    @Test
    fun dictionaryLinesMixSeparatorsAndSkipUnknownSyllables() {
        val builder = PinyinDataBuilder().unigram("<unk>", -5f, 0f).unigram("好", -1f, 0f)
        val reader = PinyinDictReader(builder)
        reader.read("好\thao\t0\n\n好 hao'ni -0.5\n好\txyz\t0\n耗 hao\n好\tni'xyz'xyz\n".reader().buffered(), "dict")
        assertEquals(3, reader.entries)
        assertEquals(mapOf("xyz" to 3), reader.unknownSyllables)
        assertEquals(3, reader.skipped)
        val data = PinyinData.load(ByteBuffer.wrap(builder.build().toByteArray()))
        assertEquals(2, data.dictionary.wordCount(data.dictionary.find(intArrayOf(org.fcitx.fcitx5.android.engine.pinyin.Syllables.id("hao")))))
    }

    @Test
    fun aMisreadWordIsTypedByItsReadingAndLessByItsMisreadingsOnly() {
        val builder = PinyinDataBuilder().unigram("<unk>", -5f, 0f).unigram("般若", -4f, 0f)
        val reader = PinyinDictReader(builder, misread = mapOf("般若" to listOf("bo're", "ban'ruo")))
        reader.read("般若\tban'ruo\t0\n般若\tpan're\t0\n".reader().buffered(), "dict")
        reader.corrected()
        assertEquals(2, reader.corrections)
        val data = PinyinData.load(ByteBuffer.wrap(builder.build().toByteArray()))
        fun weight(vararg reading: String): Float? {
            val node = data.dictionary.find(reading.map { org.fcitx.fcitx5.android.engine.pinyin.Syllables.id(it) }.toIntArray())
            return if (node < 0 || data.dictionary.wordCount(node) == 0) null else data.dictionary.weight(node, 0)
        }
        assertEquals(0f, weight("bo", "re"))
        assertEquals(Misreadings.WEIGHT, weight("ban", "ruo")!!, 0.1f)
        assertEquals(null, weight("pan", "re"))
    }

    @Test
    fun badDictionaryLinesPointAtTheirLine() {
        val reader = PinyinDictReader(PinyinDataBuilder())
        assertSourceError(2, "expected \"word pinyin [weight]\"") { reader.read("好 hao\n好\n".reader().buffered(), "dict") }
        assertSourceError(1, "bad weight") { reader.read("好 hao x\n".reader().buffered(), "dict") }
        assertSourceError(1, "weight NaN") { reader.read("好 hao NaN\n".reader().buffered(), "dict") }
    }

    companion object {
        const val TINY_ARPA = """
            \data\
            ngram 1=3
            ngram 2=1
            ngram 3=1

            \1-grams:
            -5.0	<unk>
            -1.0	你	-0.5
            -1.5	好

            \2-grams:
            -0.3	你 好	-0.1

            \3-grams:
            -0.7	你 好 你

            \end\
        """
    }
}
