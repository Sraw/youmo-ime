/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Detected
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Order
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Reading
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Reason
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Refused
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Source
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat.Weight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A file's format told before it is imported, one format each; anything unsure refused (D6). */
class DictionaryFormatTest {

    private val words = listOf("你好" to "ni hao", "幽默" to "you mo", "输入法" to "shu ru fa", "词库" to "ci ku", "拼音" to "pin yin")

    private fun analyse(bytes: ByteArray) = DictionaryFormat.analyse({ bytes.inputStream() })

    private fun detected(bytes: ByteArray) = analyse(bytes).let { it as? Detected ?: error("refused: ${(it as Refused).reason}") }

    private fun refused(bytes: ByteArray) = (analyse(bytes) as Refused).reason

    // the lines it writes as the sources keep them, as it reads them
    private fun lines(bytes: ByteArray) =
        ArrayList<String>().also { out -> DictionaryFormat.analyse({ bytes.inputStream() }, write = { out += it }) }

    private fun text(lines: List<String>) = lines.joinToString("\n", postfix = "\n").toByteArray()

    @Test
    fun frostsFcitxTextIsTextThenPinyinThenAProbability() {
        // as rime-frost's frost_dict_for_fcitx5.txt: probabilities, its new words at the end 0.0
        val bytes = text(words.map { (t, p) -> "$t ${p.replace(' ', '\'')} 3.398253139827843e-05" } + "啊 a 1.2e-07" + "黑渐层 hei'jian'ceng 0.0")
        val d = detected(bytes)
        assertEquals(Source.PLAIN, d.source)
        assertEquals(Order.TEXT_FIRST, d.order)
        assertEquals(Reading.APOSTROPHES, d.reading)
        assertEquals(Weight.PROBABILITY, d.weight)
        assertEquals(7, d.words)
        assertNull(d.refusedForBase)
        assertEquals("你好 ni'hao 3.398253139827843E-5", lines(bytes).first())
        assertEquals("黑渐层 hei'jian'ceng 0.0", lines(bytes).last())
    }

    @Test
    fun rimesDictYamlHasItsHeaderSkippedAndSpacesInItsPinyin() {
        val head = listOf("# Rime dictionary", "# encoding: utf-8", "---", "name: luna_pinyin", "version: \"2024\"", "sort: by_weight", "...", "")
        val bytes = text(head + words.map { (t, p) -> "$t\t$p\t120" } + "# a comment")
        val d = detected(bytes)
        assertEquals(Source.RIME, d.source)
        assertEquals(Reading.SPACES, d.reading)
        assertEquals(Weight.COUNT, d.weight)
        assertEquals(5, d.words)
        assertEquals(0, d.unread)
        assertEquals("输入法 shu'ru'fa 120.0", lines(bytes)[2])
    }

    @Test
    fun sogousExportIsUtf16PinyinFirst() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            words.joinToString("\r\n") { (t, p) -> "'${p.replace(' ', '\'')} $t 1" }.toByteArray(Charsets.UTF_16LE)
        val d = detected(bytes)
        assertEquals(Charsets.UTF_16LE, d.charset)
        assertEquals(Order.PINYIN_FIRST, d.order)
        assertEquals(Reading.APOSTROPHES, d.reading)
        assertEquals("幽默 you'mo 0.0", lines(bytes)[1])
    }

    // a list's order and the kind of its numbers, each from a whole file of one layout
    @Test
    fun orderAndWeightAreTheFilesOwn() {
        val rows = listOf(
            Triple("QQ's export: pinyin first, counts", words.map { (t, p) -> "${p.replace(' ', '\'')} $t 12" }, Order.PINYIN_FIRST to Weight.COUNT),
            Triple("libime's own text: costs, log10", words.map { (t, p) -> "$t ${p.replace(' ', '\'')} -${t.length}.5" }, Order.TEXT_FIRST to Weight.LOG),
        )
        assertRows(rows, { it.first }) { (why, lines, expected) ->
            val d = detected(text(lines))
            assertEquals(why, expected, d.order to d.weight)
        }
    }

    // a 1 among costs is no log10 probability: written as none, not kept to make the compiler's signs
    // mixed and the whole source unranked (R12 F3292)
    @Test
    fun aOneAmongCostsIsWrittenAsNone() {
        val bytes = text(words.map { (t, p) -> "$t ${p.replace(' ', '\'')} -${t.length}.5" } + "啊 a 1")
        assertEquals(Weight.LOG, detected(bytes).weight)
        assertEquals("啊 a 0.0", lines(bytes).last())
        assertEquals("你好 ni'hao -2.5", lines(bytes).first())
    }

    // Rime's share of a polyphone's readings (`10%`) reads as a number of no kind (R12 F3300)
    @Test
    fun rimesPercentagesReadAsNoWeight() {
        val head = listOf("---", "name: luna_pinyin", "...")
        val bytes = text(head + words.map { (t, p) -> "$t\t$p\t120" } + "的\tdi\t10%" + "的\tde\t90%")
        val d = detected(bytes)
        assertEquals(Weight.COUNT, d.weight)
        assertEquals(7, d.words)
        assertEquals(0, d.unread)
        assertEquals(listOf("的 di 0.0", "的 de 0.0"), lines(bytes).takeLast(2))
    }

    @Test
    fun pinyinRunTogetherReadsWhereItSplitsOneWay() {
        val bytes = text(words.map { (t, p) -> "$t ${p.replace(" ", "")}" })
        val d = detected(bytes)
        assertEquals(Reading.RUN_TOGETHER, d.reading)
        assertEquals(Weight.NONE, d.weight)
        assertEquals("你好 ni'hao 0.0", lines(bytes).first())
    }

    @Test
    fun gbkReadsAsGb18030() {
        val bytes = words.joinToString("\n") { (t, p) -> "$t ${p.replace(' ', '\'')}" }.toByteArray(charset("GB18030"))
        assertEquals(charset("GB18030"), detected(bytes).charset)
        assertEquals("拼音 pin'yin 0.0", lines(bytes).last())
    }

    // the base dictionary as the analysis asks it: [pairs]' characters, each with its syllable
    private fun knowing(pairs: List<Pair<String, String>>): (String, IntArray) -> Boolean {
        val known = HashSet<Pair<String, Int>>()
        for ((t, p) in pairs) {
            val entry = LibimeImport.entry(t, p.replace(' ', '\'')) ?: error("no entry $t")
            t.forEachIndexed { i, c -> known += c.toString() to entry.syllables[i] }
        }
        return { text, syllables -> (text to syllables.single()) in known }
    }

    // Big5's pairs all decode as GBK: read so, every character is another, its pinyin no reading of it (review S1)
    @Test
    fun big5ReadAsGbkIsRefusedByItsReadings() {
        val traditional = listOf("你好" to "ni hao", "幽默" to "you mo", "輸入法" to "shu ru fa", "詞庫" to "ci ku", "拼音" to "pin yin")
        val knows = knowing(traditional)
        val big5 = traditional.joinToString("\n") { (t, p) -> "$t ${p.replace(' ', '\'')}" }.toByteArray(charset("Big5"))
        assertEquals(Reason.MISMATCH, (DictionaryFormat.analyse({ big5.inputStream() }, knows = knows) as Refused).reason)
        // the same words in UTF-8 have their readings
        val utf8 = traditional.joinToString("\n") { (t, p) -> "$t ${p.replace(' ', '\'')}" }.toByteArray()
        assertTrue(DictionaryFormat.analyse({ utf8.inputStream() }, knows = knows) is Detected)
        // and a UTF-8 file is what it decodes as even where the base lacks its characters: only
        // a GB18030 read can be another encoding (R12 F3301)
        assertTrue(DictionaryFormat.analyse({ utf8.inputStream() }, knows = { _, _ -> false }) is Detected)
    }

    // a line of block marks as long as a line may be: read in a small stack (64 KB), where a mark
    // taken off a call at a time overflowed it (review of the dictionary import, fixed in c178d7ea;
    // the huge line itself: DictionaryFormatBoundedHeapTest)
    @Test
    fun manyBlockMarksAreReadInASmallStack() {
        val marks = text(words.map { (t, p) -> "$t ${p.replace(' ', '\'')}" } + ("!".repeat(4000) + "你好 ni'hao"))
        var result: Any? = null
        val reader = Thread(null, { result = runCatching { analyse(marks) }.getOrElse { it } }, "small stack", 64L shl 10)
        reader.start()
        reader.join()
        assertTrue("$result", result is Detected)
    }

    // anything not sure of refused, with the reason
    @Test
    fun filesNotOfOneLayoutAreRefusedWithTheReason() {
        val rows = listOf(
            Triple("initials only", text(listOf("你好 nh", "幽默 ym", "输入法 srf", "词库 ck")), Reason.INITIALS),
            Triple("Baidu's parentheses", text(words.map { (t, p) -> "$t(${p.replace(' ', '|')}) 2" }), Reason.UNREADABLE),
            Triple("a NUL", text(listOf("你好 ni'hao")) + byteArrayOf(0, 0, 0) + text(listOf("幽默 you'mo")), Reason.BINARY),
            // bytes neither UTF-8 nor GB18030 decodes
            Triple("garbage", Random(7).nextBytes(4096) + byteArrayOf(0x80.toByte(), 0xFF.toByte()), Reason.ENCODING),
            Triple("comments only", text(listOf("# nothing", "")), Reason.NO_WORDS),
            Triple(
                "two layouts",
                text(words.map { (t, p) -> "$t ${p.replace(' ', '\'')}" } + words.map { (t, p) -> "${p.replace(' ', '\'')} $t" }),
                Reason.MIXED,
            ),
            Triple(
                "numbers of two kinds",
                text(words.mapIndexed { i, (t, p) -> "$t ${p.replace(' ', '\'')} " + if (i % 2 == 0) "0.25" else "300" }),
                Reason.MIXED_WEIGHTS,
            ),
        )
        assertRows(rows, { it.first }) { (why, bytes, reason) -> assertEquals(why, reason, refused(bytes)) }
    }

    @Test
    fun moreThanTwoPercentUnreadIsRefusedAndLessIsCounted() {
        val good = (1..100).map { "你好 ni'hao $it" }
        assertEquals(Reason.UNREADABLE, refused(text(good + List(5) { "乱码 ??? x y" })))
        val d = detected(text(good + "乱码 ??? x y"))
        assertEquals(100, d.words)
        assertEquals(1, d.unread)
    }

    // told the share read through both passes, and stopped by a throw from it (the user cancelled)
    @Test
    fun progressRunsToTheEndAndAThrowStopsIt() {
        val bytes = text((1..200_000).map { "你好 ni'hao $it" })
        val told = ArrayList<Float>()
        assertTrue(DictionaryFormat.analyse({ bytes.inputStream() }, size = bytes.size.toLong(), progress = { told += it }) is Detected)
        assertTrue(told.size > 2 && told.zipWithNext().all { (a, b) -> a <= b } && told.last() > 0.9f)
        val stop = runCatching { DictionaryFormat.analyse({ bytes.inputStream() }, size = bytes.size.toLong(), progress = { if (it > 0.3f) throw java.util.concurrent.CancellationException() }) }
        assertTrue(stop.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    // a file UTF-8 fails on late, read again as GB18030 from its start: the bar never goes back (R12 F3319)
    @Test
    fun progressNeverGoesBackWhenUtf8FailsLate() {
        val bytes = text(List(200_000) { "你好 ni'hao" }) + "你好 ni'hao\n".toByteArray(charset("GB18030"))
        val told = ArrayList<Float>()
        val read = DictionaryFormat.analyse({ bytes.inputStream() }, size = bytes.size.toLong(), progress = { told += it })
        // the late fallback taken, else this tests nothing (review F3431)
        assertEquals(WordLists.GB18030, (read as Detected).charset)
        assertTrue("$told", told.size > 2 && told.zipWithNext().all { (a, b) -> a <= b })
    }

    // review F3398: the GB18030 pass heard all through, even over what the UTF-8 one read: a cancel
    // (a throw from progress) stops it there, not only once it catches up
    @Test
    fun aCancelStopsTheGb18030PassWhileItCatchesUp() {
        val bytes = text((1..200_000).map { "ni hao $it" }) + "你好 ni'hao\n".toByteArray(charset("GB18030"))
        // each stream opened, the passes told apart by which one is read when progress is heard, not
        // by how many opens come before (a sniff of the byte order mark or not; review F3439)
        val opened = ArrayList<java.io.ByteArrayInputStream>()
        var first: java.io.ByteArrayInputStream? = null
        var second: java.io.ByteArrayInputStream? = null
        val cancelled = runCatching {
            DictionaryFormat.analyse({ bytes.inputStream().also { opened += it } }, size = bytes.size.toLong(), progress = {
                val reading = opened.last()
                if (first == null) first = reading
                // cancelled once a later pass is heard, though the bar stands where the first left it
                if (reading !== first) {
                    second = reading
                    throw java.util.concurrent.CancellationException()
                }
            })
        }.exceptionOrNull()
        assertTrue("$cancelled", cancelled is java.util.concurrent.CancellationException)
        // stopped early in the re-read, not after it caught up with the first pass
        assertTrue("${second!!.available()}", second!!.available() > bytes.size / 2)
    }

    @Test
    fun wordsWithoutPinyinGoOnlyIntoTheUserDictionary() {
        val d = detected(text(words.map { it.first }))
        assertEquals(5, d.textOnly)
        assertEquals(Reason.NO_PINYIN, d.refusedForBase)
        // a few among words with pinyin: the base reads the rest
        val some = detected(text((1..100).map { "你好 ni'hao" } + "幽默"))
        assertNull(some.refusedForBase)
        assertTrue(some.textOnly == 1)
        // a user's list: a word with pinyin, one without, one blocked
        val list = detected(text(listOf("幽默测试 you'mo'ce'shi", "蓝鲸花园", "!马上就好")))
        assertEquals(1, list.words)
        assertEquals(2, list.textOnly)
        assertEquals(0, list.unread)
    }
}
