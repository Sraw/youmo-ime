/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user.imports

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.data.splitValues
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.util.BitSet

/** Words as text, as the dictionary reads them: `ni'hao` readings, libime text dictionary lines (`text pin'yin cost`), and likeliest readings of bare texts. */
object WordText {

    /**
     * The dictionary's likeliest reading of each of [texts] (highest weight, first of equals), in one trie walk as deep
     * as the longest text, by the syllables each character has alone, plus one stray syllable a path (儿 is r in 这儿).
     * A stray at the first depth costs some hundred times the walk, so it is only taken with [strayFirst] (imports and
     * one-off actions), never on the input thread.
     */
    internal fun readings(
        dictionary: PinyinDictionary,
        vocabulary: Vocabulary,
        texts: Set<String>,
        strayFirst: Boolean = false,
    ): Map<String, IntArray> {
        if (texts.isEmpty()) return emptyMap()
        // a reading is a syllable a character, so a word at depth d has d characters
        val depths = BooleanArray(texts.maxOf { it.codePointCount(0, it.length) } + 1)
        val firsts = BitSet()
        for (text in texts) {
            depths[text.codePointCount(0, text.length)] = true
            if (text.isNotEmpty()) firsts.set(text[0].code)
        }
        val deepest = depths.size - 1
        val walked = syllablesAt(dictionary, vocabulary, texts, deepest)
        val best = HashMap<String, IntArray>()
        val weights = HashMap<String, Float>()
        val path = IntArray(deepest)
        fun visit(node: Int, depth: Int, strayed: Boolean) {
            if (depths[depth]) {
                for (i in 0 until dictionary.wordCount(node)) {
                    val id = dictionary.word(node, i)
                    if (vocabulary.length(id) == 0 || !firsts[vocabulary.char(id, 0).code]) continue
                    val text = vocabulary.word(id)
                    val weight = dictionary.weight(node, i)
                    if (text in texts && weight > (weights[text] ?: Float.NEGATIVE_INFINITY)) {
                        weights[text] = weight
                        best[text] = path.copyOf(depth)
                    }
                }
            }
            if (depth == deepest) return
            val mayStray = canStray(strayed, depth, strayFirst)
            val first = dictionary.firstChild(node)
            for (child in first until first + dictionary.childCount(node)) {
                val syllable = dictionary.syllable(child)
                val stray = !walked[depth][syllable]
                if (stray && !mayStray) continue
                path[depth] = syllable
                visit(child, depth + 1, strayed || stray)
            }
        }
        visit(dictionary.root, 0, strayed = false)
        return best
    }

    private fun canStray(strayed: Boolean, depth: Int, strayFirst: Boolean) = !strayed && (depth > 0 || strayFirst)

    private fun syllablesAt(dictionary: PinyinDictionary, vocabulary: Vocabulary, texts: Set<String>, deepest: Int): Array<BitSet> {
        val chars = HashSet<Int>().apply { texts.forEach { text -> forEachCodePoint(text) { add(it) } } }.toIntArray()
        chars.sort()
        val alone = Array(chars.size) { BitSet() }
        val first = dictionary.firstChild(dictionary.root)
        for (node in first until first + dictionary.childCount(dictionary.root)) {
            for (i in 0 until dictionary.wordCount(node)) {
                val at = chars.binarySearch(codePointOf(vocabulary, dictionary.word(node, i)))
                if (at >= 0) alone[at].set(dictionary.syllable(node))
            }
        }
        val walked = Array(deepest) { BitSet() }
        for (text in texts) {
            var depth = 0
            forEachCodePoint(text) { c ->
                val syllables = alone[chars.binarySearch(c)]
                // none alone: in longer words it may be read as any
                if (syllables.isEmpty) walked[depth].set(0, Syllables.count) else walked[depth].or(syllables)
                depth++
            }
        }
        return walked
    }

    private fun codePointOf(vocabulary: Vocabulary, id: Int): Int = when (vocabulary.length(id)) {
        1 -> vocabulary.char(id, 0).code
        2 -> {
            val high = vocabulary.char(id, 0)
            val low = vocabulary.char(id, 1)
            if (Character.isSurrogatePair(high, low)) Character.toCodePoint(high, low) else -1
        }
        else -> -1
    }

    private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            action(c)
            i += Character.charCount(c)
        }
    }

    /** The word of a libime text dictionary line as libime reads it (cost optional, any whitespace, quoted values unescaped); null for a blank or unreadable line. */
    fun dictionaryEntry(line: String): Entry? = dictionaryEntry(splitValues(line))

    private fun dictionaryEntry(fields: List<String>): Entry? {
        if (fields.size !in 2..3) return null
        if (fields.size == 3 && fields[2].toFloatOrNull() == null) return null
        return entry(fields[0], fields[1])
    }

    /** [lines] of a libime text dictionary as `text pin'yin cost`, unreadable ones left out. */
    fun dictionaryText(lines: Sequence<String>): Sequence<String> = lines.mapNotNull { line ->
        val fields = splitValues(line)
        dictionaryEntry(fields)?.let { "${escapeValue(fields[0])} ${fields[1]} ${fields.getOrNull(2)?.toFloat() ?: 0f}" }
    }

    internal fun entry(text: String, code: String): Entry? {
        if (text.isEmpty()) return null
        val syllables = code.split('\'').map { Syllables.id(spelling(it)) }
        if (syllables.any { it < 0 } || syllables.size != text.codePointCount(0, text.length)) return null
        return Entry(text, syllables.toIntArray())
    }

    // libime writes lü and nüe with the ü, and reads lue as lüe
    internal fun spelling(s: String): String = when (s) {
        "lue" -> "lve"
        "nue" -> "nve"
        else -> s.replace('ü', 'v')
    }
}
