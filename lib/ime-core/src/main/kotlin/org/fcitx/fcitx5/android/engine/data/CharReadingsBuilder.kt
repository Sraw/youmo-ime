/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.PinyinData.Section
import org.fcitx.fcitx5.android.engine.reading.CharReadings
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/** Which of Unihan's fields a character's reading was listed in, as mozillazg/pinyin-data names its files. */
enum class ReadingSource(val field: String) {
    /** One reading a character, the most used: some 44,000 characters. */
    MANDARIN("kMandarin"),

    /** 《现代汉语频率词典》: the readings heard in its texts, the most heard first; 3,800 characters. */
    PINLU("kHanyuPinlu"),

    /** 《通用规范汉字字典》 (2013): 8,100 characters, its readings in alphabetical order. */
    TGHZ("kTGHZ2013"),

    /** 《现代汉语词典》 (1983): 11,000 characters, alphabetical. */
    XHC("kXHC1983"),

    /** 《汉语大字典》: 34,000 characters, old readings among them (行 xìng, 还 fú). */
    HANYU_PINYIN("kHanyuPinyin");

    /** Whether it lists the readings of a modern dictionary, which a character's card shows. */
    val modern: Boolean get() = this == PINLU || this == TGHZ || this == XHC

    companion object {
        /** The field a file of [name] lists (`pinyin-data-…-kXHC1983.txt`); null for one named for none. */
        fun of(name: String): ReadingSource? = entries.firstOrNull { name.contains(it.field + ".") || name == it.field }
    }
}

/**
 * Each character's readings in the order its card and handwriting's candidates list them, and a
 * reading's commonest words ([PinyinData.charReadings]), compiled with the tones.
 *
 * Which readings: those of 《通用规范汉字字典》 and 《现代汉语词典》, or of 《现代汉语频率词典》 for a character
 * neither has ([ReadingSource.modern]); a character none of the three has (a rare one, a radical as
 * 亻), kMandarin's and then 《汉语大字典》's. 一 and 不 keep their
 * own tones only, as everywhere (yī, bù: 现汉's yí yì bú are how they are said before others).
 *
 * The candidates' readings ([ordered]) leave out a neutral tone where the character has its syllable in
 * a full tone too (子 zi beside zǐ, 头 tou, 和 huo, 啊 a): they are written in full tones (the user,
 * 2026-11-02), but for a character whose reading is a neutral one (的 de, 们 men, 吗 ma: kMandarin's).
 * The card lists the dictionaries' readings, those too ([cardOnly], after the others: team-lead,
 * 2026-11-02, the card being where a character is looked up).
 *
 * In what order: 《现代汉语频率词典》's, by how often each was heard, for a character it has; the rest
 * by how common the model's words reading the character so are, all of them together; then as the
 * dictionaries list them (通用规范 first).
 */
internal class CharReadingsBuilder {

    // code point to each source's readings, in the order listed: a syllable times 8 plus its tone
    private val listed = HashMap<Int, Array<IntList?>>()

    fun add(source: ReadingSource, codePoint: Int, syllable: Int, tone: Int) {
        val lists = listed.getOrPut(codePoint) { arrayOfNulls(ReadingSource.entries.size) }
        val list = lists[source.ordinal] ?: IntList().also { lists[source.ordinal] = it }
        val r = syllable * TONES + tone
        if ((0 until list.size).none { list[it] == r }) list += r
    }

    private class Use(var count: Double = 0.0, val words: ArrayList<Pair<Double, Int>> = ArrayList(EXAMPLES + 1))

    // a character read a way (code point, syllable × 8 + tone) to how common the words reading it so are, and the commonest of them
    private val uses = HashMap<Long, Use>()

    /**
     * Word [id], [p] in the model (a probability), has [cps] read as [syllables] with [tones]: what
     * orders its characters' readings, and, of two to four characters and [listed] (a dictionary's
     * word read so: phrase-pinyin-data's, `lexicon/tones.tsv`'s), may be one's example. Not the
     * model's others: some are two words run together (荣和, 去呢, 弦数; the user, 2026-11-02).
     */
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

    // the dictionaries' readings, 一 and 不 in their own tones alone: 频率词典 orders them, but what it heard
    // (好 hāo, 沉 chēn) is no reading of its own
    private fun listedOf(codePoint: Int, lists: Array<IntList?>): List<Int> {
        val modern = (of(lists, ReadingSource.TGHZ) + of(lists, ReadingSource.XHC)).distinct().ifEmpty { of(lists, ReadingSource.PINLU) }
        val all = modern.ifEmpty { (of(lists, ReadingSource.MANDARIN) + of(lists, ReadingSource.HANYU_PINYIN)).distinct() }
        val own = OWN_TONES[codePoint] ?: return all
        return all.filter { r -> r / TONES != own / TONES || r == own }
    }

    // a neutral tone the candidates leave out: its syllable in a full tone too, and not kMandarin's reading
    private fun cardOnly(r: Int, all: List<Int>, mandarin: List<Int>) =
        r % TONES == ToneMark.NEUTRAL && r !in mandarin && all.any { it / TONES == r / TONES && it % TONES != ToneMark.NEUTRAL }

    /** [codePoint]'s readings the candidates show, in order, each a syllable times 8 plus its tone. */
    fun ordered(codePoint: Int): List<Int> {
        val lists = listed[codePoint] ?: return emptyList()
        val all = listedOf(codePoint, lists)
        val mandarin = of(lists, ReadingSource.MANDARIN)
        val kept = all.filter { !cardOnly(it, all, mandarin) }
        if (of(lists, ReadingSource.TGHZ).isEmpty() && of(lists, ReadingSource.XHC).isEmpty() && of(lists, ReadingSource.PINLU).isEmpty()) return kept
        return byUse(codePoint, lists, kept)
    }

    /** [codePoint]'s readings only its card lists (子 zi, 和 huo), after [ordered]'s, as the dictionaries list them. */
    fun cardOnly(codePoint: Int): List<Int> {
        val lists = listed[codePoint] ?: return emptyList()
        val all = listedOf(codePoint, lists)
        val mandarin = of(lists, ReadingSource.MANDARIN)
        return all.filter { cardOnly(it, all, mandarin) }
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

    /** The sections [CharReadings] reads; examples for a character of more than one reading only. */
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
        /** A reading's syllable and tone as one number: the syllable times this, plus the tone. */
        const val TONES = 8

        /** At most this many words show a reading, each of two to [MAX_EXAMPLE] characters. */
        const val EXAMPLES = 2
        const val MAX_EXAMPLE = 4

        // 一 and 不 in their own tones alone, as PinyinDataBuilder's tones have them
        private val OWN_TONES = PinyinDataBuilder.OWN_TONES.entries.associate { (k, tone) -> k.first to k.second * TONES + tone }
    }
}
