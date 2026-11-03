/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/** What 候选词注音 shows above or beside each character of a candidate: nothing, pinyin with tones, or zhuyin. */
enum class ReadingStyle(val key: String) {
    OFF("Off"),
    PINYIN("Pinyin"),
    ZHUYIN("Zhuyin");

    companion object {
        /** By [key], as the engine's config keeps it; anything else is [OFF]. */
        fun of(key: String?): ReadingStyle = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** A word of a candidate as its session read it: its [text], its id in pinyin data (-1 if none), its [syllables]. */
class WordReading(val text: String, val word: Int, val syllables: IntArray)

/**
 * The reading of each character of a candidate, written out ([ToneMark]) as [style] has it: one string
 * a code point, empty for a code point that is no Han character or whose tone is not known.
 *
 * A candidate whose session knows how its words were read (pinyin's: 银行 typed yinhang) goes by
 * that; any other (a table's, handwriting's, a prediction's) by its text: the longest words of the
 * model first, each read the likeliest way the dictionary has it, a character left over alone. Asked
 * for only while [style] is not [ReadingStyle.OFF].
 */
class CandidateReadings(
    private val data: PinyinData,
    private val table: ReadingTable,
    // traditional text (仓颉's, handwriting's) to simplified, a character at a time: the dictionary's (說 as 说)
    private val simplify: (String) -> String = { it },
    charReadings: () -> Map<Int, IntArray>,
) {

    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, charReadings = charReadings)
    private val charReadings by lazy(LazyThreadSafetyMode.NONE, charReadings)

    /** [text] written out as [style] has it, [words] how its session read it if it knows; empty if nothing of it is shown. */
    fun of(text: String, style: ReadingStyle, words: List<WordReading>? = null): List<String> {
        if (style == ReadingStyle.OFF || text.none { Character.isIdeographic(it.code) || it.isSurrogate() }) return emptyList()
        val read = words?.takeIf { w -> w.joinToString("") { it.text } == text } ?: byText(simplify(text).takeIf { it.length == text.length } ?: text)
        val out = ArrayList<String>(text.length)
        for (w in read) {
            val tones = table.tones(w.text, w.syllables, w.word)
            var at = 0
            var i = 0
            while (at < w.text.length) {
                val cp = w.text.codePointAt(at)
                val tone = tones?.get(i)?.toInt() ?: ToneMark.NONE
                out += if (tones == null || !Character.isIdeographic(cp) || tone == ToneMark.NONE) "" else written(w.syllables[i], tone, style)
                at += Character.charCount(cp)
                i++
            }
            if (erhua(w)) out[out.size - 1] = if (style == ReadingStyle.ZHUYIN) ERHUA_ZHUYIN else ERHUA_PINYIN
        }
        return if (out.all { it.isEmpty() }) emptyList() else out
    }

    /**
     * A pinyin hint of [text] (a misreading's true "jué sè", a part's or a stroke lookup's "hao") as one reading
     * a character, written as [style] has it (pinyin while 候选词注音 is off): the hint's own tones where it has
     * them, else its syllables with the tones they have in [text], toneless where none is known; empty if [text]
     * is not all Han or [hint] is no syllable a character (an emoji's name stays a hint).
     */
    fun ofHint(text: String, hint: String, style: ReadingStyle = ReadingStyle.PINYIN): List<String> {
        if (hint.isBlank() || !text.codePoints().allMatch(Character::isIdeographic)) return emptyList()
        val parts = hint.trim().split(' ')
        if (parts.size != text.codePointCount(0, text.length) || parts.any { p -> p.isEmpty() || !p.all(Char::isLetter) }) return emptyList()
        val toned = parts.any { p -> p.any { it.code > ASCII } }
        val ids = IntArray(parts.size) { Syllables.id(if (toned) ToneMark.parse(parts[it])?.first.orEmpty() else parts[it]) }
        if (style == ReadingStyle.PINYIN || ids.any { it < 0 }) return if (toned || ids.any { it < 0 }) parts else ofSyllables(text, parts, ids)
        // zhuyin: each syllable's symbols, in the hint's tone or the character's
        val known = if (toned) null else table.tones(text, ids)
        val tones = IntArray(ids.size) { if (toned) ToneMark.parse(parts[it])?.second ?: ToneMark.NONE else known?.get(it)?.toInt() ?: ToneMark.NONE }
        return ids.mapIndexed { i, id -> Zhuyin.of(id)?.let { ToneMark.zhuyin(it, tones[i]) }.orEmpty() }
    }

    // [parts], toneless syllables of [text], with the tones they have in it where known
    private fun ofSyllables(text: String, parts: List<String>, ids: IntArray): List<String> {
        val read = of(text, ReadingStyle.PINYIN, listOf(WordReading(text, -1, ids)))
        return parts.mapIndexed { i, p -> read.getOrNull(i)?.ifEmpty { null } ?: p }
    }

    // a word's last 儿 read er, 儿化 (哪儿 nǎr, written nǎ r; ㄦ with no ˙, as Taiwan's textbooks): not a word
    // whose 儿 is a child (女儿 nǚ ér)
    private fun erhua(w: WordReading): Boolean =
        w.text.length > 1 && w.text.last() == '儿' && w.syllables.last().let { it == ER || it == R } && w.text !in CHILD

    private fun written(syllable: Int, tone: Int, style: ReadingStyle): String = when (style) {
        ReadingStyle.ZHUYIN -> Zhuyin.of(syllable)?.let { ToneMark.zhuyin(it, tone) }.orEmpty()
        else -> ToneMark.pinyin(Syllables.spelling(syllable), tone)
    }

    /** [text] as words of the model, longest first; a character no word starts with alone. */
    private fun byText(text: String): List<WordReading> {
        val out = ArrayList<WordReading>()
        val index = data.wordIndex
        var at = 0
        while (at < text.length) {
            var found: WordReading? = null
            var end = minOf(text.length, at + MAX_WORD)
            while (end > at && found == null) {
                // never through a surrogate pair
                if (end == text.length || !Character.isLowSurrogate(text[end])) {
                    val id = index.find(text, at, end)
                    val syllables = if (id >= 0) predictor.reading(id) else null
                    if (syllables != null && syllables.size == text.codePointCount(at, end)) found = WordReading(text.substring(at, end), id, syllables)
                }
                end--
            }
            val one = found ?: run {
                val cp = text.codePointAt(at)
                // a character only the dictionary has (rare ones have a reading or two); with none, a
                // syllable no character has a tone for, so it is left unmarked
                WordReading(text.substring(at, at + Character.charCount(cp)), -1, intArrayOf(charReadings[cp]?.firstOrNull() ?: UNREAD))
            }
            out += one
            at += one.text.length
        }
        return out
    }

    companion object {
        // the longest word looked for in a candidate's text, in chars
        private const val MAX_WORD = 8
        private const val ASCII = 0x7f
        private val ER = Syllables.id("er")
        private val R = Syllables.id("r")
        private const val ERHUA_PINYIN = "r"
        private const val ERHUA_ZHUYIN = "ㄦ"

        // words ending in 儿 that is a child or a son, read ér
        private val CHILD = setOf(
            "女儿", "婴儿", "幼儿", "孤儿", "男儿", "胎儿", "宠儿", "患儿", "健儿", "少儿", "弃儿", "育儿",
            "新生儿", "早产儿", "混血儿", "低能儿", "娇儿", "妻儿", "生儿",
        )

        // a syllable no character has a tone for: the Latin letter A, which no Han character reads as
        private val UNREAD = Syllables.id("A")
    }
}
