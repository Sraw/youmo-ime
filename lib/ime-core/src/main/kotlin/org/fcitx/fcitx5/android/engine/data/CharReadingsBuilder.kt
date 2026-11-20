/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.PinyinData.Section
import org.fcitx.fcitx5.android.engine.reading.CharReadings
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/** Unihan's reading fields, as mozillazg/pinyin-data names its files. */
enum class ReadingSource(val field: String) {
    MANDARIN("kMandarin"),

    /** 《现代汉语频率词典》: readings by how often heard. */
    PINLU("kHanyuPinlu"),

    /** 《通用规范汉字字典》: readings alphabetical. */
    TGHZ("kTGHZ2013"),

    /** 《现代汉语词典》: alphabetical. */
    XHC("kXHC1983"),

    /** 《汉语大字典》: includes old readings (行 xìng, 还 fú). */
    HANYU_PINYIN("kHanyuPinyin");

    val modern: Boolean get() = this == PINLU || this == TGHZ || this == XHC

    companion object {
        fun of(name: String): ReadingSource? = entries.firstOrNull { name.contains(it.field + ".") || name == it.field }
    }
}

/**
 * Each character's readings in card and candidate order, and each reading's commonest words: 通用规范 and 现汉, else
 * 频率词典, else kMandarin then 汉语大字典; ordered by 频率词典, else by how common the model's words are. Candidates
 * drop a neutral tone beside a full one (子 zi) unless kMandarin's (的 de); the card keeps those ([cardOnly]).
 */
internal class CharReadingsBuilder {

    private val listed = HashMap<Int, Array<IntList?>>()

    fun add(source: ReadingSource, codePoint: Int, syllable: Int, tone: Int) {
        val lists = listed.getOrPut(codePoint) { arrayOfNulls(ReadingSource.entries.size) }
        val list = lists[source.ordinal] ?: IntList().also { lists[source.ordinal] = it }
        val r = syllable * TONES + tone
        if ((0 until list.size).none { list[it] == r }) list += r
    }

    private class Use(var count: Double = 0.0, val words: ArrayList<Pair<Double, Int>> = ArrayList(EXAMPLES + 1))

    private val uses = HashMap<Long, Use>()

    /** Word [id] reads [cps] as [syllables]/[tones]; only a [listed] word of 2-4 characters may be an example (some model words are two run together: 荣和). */
    fun word(id: Int, cps: IntArray, syllables: List<Int>, tones: ByteArray, p: Double, listed: Boolean) {
        if (cps.size < 2) return
        for (i in cps.indices) {
            val use = uses.getOrPut(key(cps[i], syllables[i] * TONES + tones[i])) { Use() }
            use.count += p
            if (!listed || cps.size > MAX_EXAMPLE || use.words.any { it.second == id }) continue
            use.words += p to id
            use.words.sortWith(compareByDescending<Pair<Double, Int>> { it.first }.thenBy { it.second })
            if (use.words.size > EXAMPLES) use.words.removeAt(EXAMPLES)
        }
    }

    private fun key(cp: Int, reading: Int) = cp.toLong() shl Int.SIZE_BITS or reading.toLong()

    private fun of(lists: Array<IntList?>, s: ReadingSource) = lists[s.ordinal]?.toArray()?.toList().orEmpty()

    // 一 and 不 keep only their own tones; what 频率词典 heard (好 hāo) is no reading
    private fun listedOf(codePoint: Int, lists: Array<IntList?>): List<Int> {
        val modern = (of(lists, ReadingSource.TGHZ) + of(lists, ReadingSource.XHC)).distinct().ifEmpty { of(lists, ReadingSource.PINLU) }
        val all = modern.ifEmpty { (of(lists, ReadingSource.MANDARIN) + of(lists, ReadingSource.HANYU_PINYIN)).distinct() }
        val own = OWN_TONES[codePoint] ?: return all
        return all.filter { r -> r / TONES != own / TONES || r == own }
    }

    private fun isCardOnly(r: Int, all: List<Int>, mandarin: List<Int>) =
        r % TONES == ToneMark.NEUTRAL && r !in mandarin && all.any { it / TONES == r / TONES && it % TONES != ToneMark.NEUTRAL }

    fun ordered(codePoint: Int): List<Int> {
        val lists = listed[codePoint] ?: return emptyList()
        val all = listedOf(codePoint, lists)
        val mandarin = of(lists, ReadingSource.MANDARIN)
        val kept = all.filter { !isCardOnly(it, all, mandarin) }
        if (of(lists, ReadingSource.TGHZ).isEmpty() && of(lists, ReadingSource.XHC).isEmpty() && of(lists, ReadingSource.PINLU).isEmpty()) return kept
        return byUse(codePoint, lists, kept)
    }

    /** Readings only the card lists (子 zi), after [ordered]'s. */
    fun cardOnly(codePoint: Int): List<Int> {
        val lists = listed[codePoint] ?: return emptyList()
        val all = listedOf(codePoint, lists)
        val mandarin = of(lists, ReadingSource.MANDARIN)
        return all.filter { isCardOnly(it, all, mandarin) }
    }

    private fun byUse(codePoint: Int, lists: Array<IntList?>, kept: List<Int>): List<Int> {
        fun of(s: ReadingSource) = of(lists, s)
        val heard = of(ReadingSource.PINLU)
        val tghz = of(ReadingSource.TGHZ)
        val xhc = of(ReadingSource.XHC)
        fun at(list: List<Int>, r: Int) = list.indexOf(r).let { if (it < 0) Int.MAX_VALUE else it }
        return kept.sortedWith(
            compareBy<Int> { at(heard, it) }
                .thenByDescending { uses[key(codePoint, it)]?.count ?: 0.0 }
                .thenBy { at(tghz, it) }
                .thenBy { at(xhc, it) }
        )
    }

    /** Examples only for characters with more than one reading. */
    fun write(out: DataFile.Writer) {
        val keys = IntList()
        val starts = IntList().apply { plusAssign(0) }
        val readings = IntList()
        val flags = IntList()
        val exampleStarts = IntList().apply { plusAssign(0) }
        val examples = IntList()
        for (cp in listed.keys.sorted()) {
            val ordered = ordered(cp)
            val extra = cardOnly(cp)
            if (ordered.isEmpty() && extra.isEmpty()) continue
            keys += cp
            val all = ordered + extra
            all.forEachIndexed { i, r ->
                readings += r
                flags += if (i < ordered.size) 0 else CharReadings.CARD_ONLY
                if (all.size > 1) uses[key(cp, r)]?.words?.forEach { examples += it.second }
                exampleStarts += examples.size
            }
            starts += readings.size
        }
        val r = readings.toArray()
        out.add(Section.READING_CHARS, BitPacked.encode(keys.toArray()))
        out.add(Section.READING_START, BitPacked.encode(starts.toArray()))
        out.add(Section.READING_SYLLABLES, BitPacked.encode(r.size) { r[it] / TONES })
        out.add(Section.READING_TONES, ByteArray(r.size) { (r[it] % TONES or flags[it]).toByte() })
        out.add(Section.READING_EXAMPLE_START, BitPacked.encode(exampleStarts.toArray()))
        out.add(Section.READING_EXAMPLES, BitPacked.encode(examples.toArray()))
    }

    companion object {
        const val TONES = 8

        /** Each of two to [MAX_EXAMPLE] characters. */
        const val EXAMPLES = 2
        const val MAX_EXAMPLE = 4

        private val OWN_TONES = PinyinDataBuilder.OWN_TONES.entries.associate { (k, tone) -> k.first to k.second * TONES + tone }
    }
}
