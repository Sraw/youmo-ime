/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/** What 候选词注音 shows per candidate character: nothing, toned pinyin, or zhuyin. */
enum class ReadingStyle(val key: String) {
    OFF("Off"),
    PINYIN("Pinyin"),
    ZHUYIN("Zhuyin");

    companion object {
        /** Anything unknown is [OFF]. */
        fun of(key: String?): ReadingStyle = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** A candidate word as its session read it; [word] is its pinyin-data id, -1 if none. */
class WordReading(val text: String, val word: Int, val syllables: IntArray)

/**
 * Each code point's reading of a candidate in [style], empty where not Han or unread. A character whose tone
 * is unknown (䏝 chun) is toneless in pinyin and empty in zhuyin, where toneless would read as tone 1. A
 * candidate whose session knows its words goes by them; others by text, longest model words first, each
 * read the likeliest way.
 */
class CandidateReadings(
    private val data: PinyinData,
    private val table: ReadingTable,
    // traditional to simplified per character, the dictionary's (說 as 说)
    private val simplify: (String) -> String = { it },
    charReadings: () -> Map<Int, IntArray>,
) {

    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, charReadings = charReadings)
    private val charReadings by lazy(LazyThreadSafetyMode.NONE, charReadings)

    /** Empty if nothing of [text] is shown; [words] when the session knows them. */
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
                out += when {
                    tones == null || !Character.isIdeographic(cp) || w.syllables[i] == UNREAD -> ""
                    else -> written(w.syllables[i], tone, style)
                }
                at += Character.charCount(cp)
                i++
            }
            if (erhua(w)) out[out.size - 1] = if (style == ReadingStyle.ZHUYIN) ERHUA_ZHUYIN else ERHUA_PINYIN
        }
        return if (out.all { it.isEmpty() }) emptyList() else out
    }

    /** The code point whose [CharReadings] apply: its own, else its simplified one's (還 还). */
    fun readFrom(codePoint: Int): Int {
        if (data.charReadings.of(codePoint, card = true).isNotEmpty()) return codePoint
        val s = simplify(String(Character.toChars(codePoint)))
        return if (s.codePointCount(0, s.length) == 1) s.codePointAt(0) else codePoint
    }

    /** [codePoint]'s readings, first the one it has in the longest dictionary word ending with [before] plus it (银 then 行: háng). */
    fun ordered(codePoint: Int, before: String = "", card: Boolean = false): List<Reading> {
        val c = String(Character.toChars(codePoint))
        val all = data.charReadings.of(readFrom(codePoint), card)
        if (all.size < 2 || before.isEmpty()) return all
        val text = simplify(before + c).takeIf { it.length == before.length + c.length } ?: (before + c)
        val index = data.wordIndex
        var from = maxOf(0, text.length - MAX_WORD)
        if (from > 0 && Character.isLowSurrogate(text[from])) from++
        while (from < before.length) {
            val id = index.find(text, from, text.length)
            val syllables = if (id >= 0) predictor.reading(id) else null
            if (syllables != null && syllables.size == text.codePointCount(from, text.length)) {
                val tones = table.tones(text.substring(from), syllables, id)
                val read = tones?.let { Reading(syllables.last(), it.last().toInt()) }
                if (read != null && read in all) return listOf(read) + (all - read)
            }
            from += if (Character.isHighSurrogate(text[from])) 2 else 1
        }
        return all
    }

    /** One character with all its readings ([ordered]): the first [MAX_SHOWN] joined by [SEPARATOR], then [MORE] if more; otherwise as [of]. */
    fun allOf(text: String, style: ReadingStyle, before: String = ""): List<String> {
        if (style == ReadingStyle.OFF || text.isEmpty() || text.codePointCount(0, text.length) != 1) return of(text, style)
        val cp = text.codePointAt(0)
        if (!Character.isIdeographic(cp)) return of(text, style)
        val all = ordered(cp, before)
        if (all.size < 2) return of(text, style)
        val shown = all.take(MAX_SHOWN).map { written(it.syllable, it.tone, style) }
        if (shown.any { it.isEmpty() }) return of(text, style)
        return listOf(shown.joinToString(SEPARATOR.toString()) + if (all.size > MAX_SHOWN) MORE.toString() else "")
    }

    /** A held candidate's compact line for one character read [first]: then its other readings, [MAX_SHOWN] in all, [MORE] if more (háng / xíng…); empty where [first] is. */
    fun lineOf(text: String, first: String, style: ReadingStyle): String {
        if (first.isEmpty() || text.isEmpty()) return ""
        val rest = ordered(text.codePointAt(0)).map { written(it.syllable, it.tone, style) }.filter { it.isNotEmpty() && it != first }.distinct()
        return (listOf(first) + rest.take(MAX_SHOWN - 1)).joinToString(LINE_SEPARATOR) + if (rest.size > MAX_SHOWN - 1) MORE.toString() else ""
    }

    /**
     * A pinyin [hint] of [text] as one reading per character in [style]: the hint's tones where given, else the
     * tones they have in [text], toneless where unknown (empty in zhuyin, as in [of]); empty if [text] is not all
     * Han or [hint] is not one syllable a character. A several-readings hint (`le/liao`) is drawn as one, the
     * one [ordered] puts first after [before].
     */
    fun ofHint(text: String, hint: String, style: ReadingStyle = ReadingStyle.PINYIN, before: String = ""): List<String> {
        if (hint.isBlank() || !text.codePoints().allMatch(Character::isIdeographic)) return emptyList()
        val several = hint.trim().split(SEPARATOR)
        if (several.size > 1) return oneWritten(text, several, style, before)
        val parts = hint.trim().split(' ')
        if (parts.size != text.codePointCount(0, text.length) || parts.any { p -> p.isEmpty() || !p.all(Char::isLetter) }) return emptyList()
        val toned = parts.any { p -> p.any { it.code > ASCII } }
        val ids = IntArray(parts.size) { Syllables.id(if (toned) ToneMark.parse(parts[it])?.first.orEmpty() else parts[it]) }
        if (style == ReadingStyle.PINYIN || ids.any { it < 0 }) return if (toned || ids.any { it < 0 }) parts else ofSyllables(text, parts, ids)
        // zhuyin: each syllable's symbols in the hint's tone or the character's
        val known = if (toned) null else table.tones(text, ids)
        val tones = IntArray(ids.size) { if (toned) ToneMark.parse(parts[it])?.second ?: ToneMark.NONE else known?.get(it)?.toInt() ?: ToneMark.NONE }
        return ids.mapIndexed { i, id -> written(id, tones[i], style) }
    }

    /** The one reading a several-readings hint of one character is drawn as: [ordered]'s first among those [hint] lists; null if none applies. Blocking by parts blocks this shown reading. */
    fun oneOf(text: String, before: String = "", hint: String? = null): Reading? {
        if (text.isEmpty() || text.codePointCount(0, text.length) != 1) return null
        val all = ordered(text.codePointAt(0), before)
        if (hint == null) return all.firstOrNull()
        val listed = hint.trim().split(SEPARATOR).mapNotNullTo(HashSet()) { p -> ToneMark.parse(p)?.first?.let(Syllables::id)?.takeIf { it >= 0 } }
        return all.firstOrNull { it.syllable in listed }
    }

    // [oneOf] of [several] in [style]; the hint's first if the data has none; nothing where [style] cannot
    // write the chosen one, since it is blocked in that one (PartsLookup.shownIn) and another would contradict it
    private fun oneWritten(text: String, several: List<String>, style: ReadingStyle, before: String): List<String> {
        if (text.codePointCount(0, text.length) != 1 || several.any { p -> p.isEmpty() || !p.all(Char::isLetter) }) return emptyList()
        val one = oneOf(text, before, several.joinToString(SEPARATOR.toString())) ?: return ofHint(text, several.first(), style)
        return written(one.syllable, one.tone, style).let { if (it.isEmpty()) emptyList() else listOf(it) }
    }

    // [parts], toneless syllables of [text], with their tones in it where known
    private fun ofSyllables(text: String, parts: List<String>, ids: IntArray): List<String> {
        val read = of(text, ReadingStyle.PINYIN, listOf(WordReading(text, -1, ids)))
        return parts.mapIndexed { i, p -> read.getOrNull(i)?.ifEmpty { null } ?: p }
    }

    // a word-final 儿 read er is 儿化 (哪儿 nǎ r; ㄦ without ˙), unless it means child (女儿 nǚ ér)
    private fun erhua(w: WordReading): Boolean =
        w.text.length > 1 && w.text.last() == '儿' && w.syllables.last().let { it == ER || it == R } && w.text !in CHILD

    // the one place a reading is written: in zhuyin none for an unknown tone, which would read as tone 1
    private fun written(syllable: Int, tone: Int, style: ReadingStyle): String = when (style) {
        ReadingStyle.ZHUYIN -> if (tone == ToneMark.NONE) "" else Zhuyin.of(syllable)?.let { ToneMark.zhuyin(it, tone) }.orEmpty()
        else -> ToneMark.pinyin(Syllables.spelling(syllable), tone)
    }

    /** [text] as model words, longest first; a character no word starts with alone. */
    private fun byText(text: String): List<WordReading> {
        val out = ArrayList<WordReading>()
        val index = data.wordIndex
        var at = 0
        while (at < text.length) {
            var found: WordReading? = null
            var end = minOf(text.length, at + MAX_WORD)
            while (end > at && found == null) {
                // never split a surrogate pair
                if (end == text.length || !Character.isLowSurrogate(text[end])) {
                    val id = index.find(text, at, end)
                    val syllables = if (id >= 0) predictor.reading(id) else null
                    if (syllables != null && syllables.size == text.codePointCount(at, end)) found = WordReading(text.substring(at, end), id, syllables)
                }
                end--
            }
            val one = found ?: run {
                val cp = text.codePointAt(at)
                val c = text.substring(at, at + Character.charCount(cp))
                // a dictionary-only character: its first reading with a known tone (䏝 zhuān, not toneless chun), else
                // the first; with none, a syllable no character has, so it stays unmarked
                val all = charReadings[cp]
                val toned = all?.firstOrNull { s -> table.tones(c, intArrayOf(s))?.let { it[0].toInt() != ToneMark.NONE } == true }
                WordReading(c, -1, intArrayOf(toned ?: all?.firstOrNull() ?: UNREAD))
            }
            out += one
            at += one.text.length
        }
        return out
    }

    companion object {
        /** Between a character's readings (xíng/háng), as hints also write them (EngineAssets.charReading). */
        const val SEPARATOR = '/'

        /** Between readings on a held candidate's compact line ([lineOf]). */
        const val LINE_SEPARATOR = " / "

        /** After them when there are more than shown: hé/hè…. */
        const val MORE = '…'

        const val MAX_SHOWN = 2

        // the longest word looked for in a candidate's text, in chars
        private const val MAX_WORD = 8
        private const val ASCII = 0x7f
        private val ER = Syllables.id("er")
        private val R = Syllables.id("r")
        private const val ERHUA_PINYIN = "r"
        private const val ERHUA_ZHUYIN = "ㄦ"

        // words whose final 儿 means child or son, read ér
        private val CHILD = setOf(
            "女儿", "婴儿", "幼儿", "孤儿", "男儿", "胎儿", "宠儿", "患儿", "健儿", "少儿", "弃儿", "育儿",
            "新生儿", "早产儿", "混血儿", "低能儿", "娇儿", "妻儿", "生儿",
        )

        // a syllable no Han character reads as
        private val UNREAD = Syllables.id("A")
    }
}
