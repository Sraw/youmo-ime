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

/**
 * Words as text, read as the dictionary reads them: a word's reading spelled (`ni'hao`), a line of
 * a dictionary in libime's text format (`text pin'yin cost`, what a `.dict` reads as and an import
 * or a word list writes), and the likeliest readings of texts given none. Libime's names, as the
 * format is its, but nothing of libime's learning: every word list, import and dictionary reads so.
 */
object WordText {

    /**
     * The syllables of each of [texts] the dictionary has, as its likeliest reading of it: the
     * one of highest weight, the first found of equals. One walk of the trie, as deep as the
     * longest of [texts], at each depth by only the syllables their characters there have as
     * words of their own (as Predictor reads a word), or by any where one has none, and by any at
     * one depth of a path: a character may take inside a word a syllable it never has alone (儿 is
     * r in 这儿, er alone). Not at the first depth unless [strayFirst] (an import, a settings action,
     * a block from a table's candidate, each run once, which may also walk again with it for what a
     * walk without did not find): a stray there is any child of the root's, which makes a text or two
     * some hundred times the walk, and what a table reads as it is typed in (a phrase it saves, what
     * a long press offers) is read without, on the input thread; a phrase so read piece by piece may
     * be read again with [strayFirst]. A text or two walk a small part of the
     * trie, an import of thousands most of it.
     * A word is made a string only if its length and first char could match.
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
        // [strayed]: the path took a syllable its character there has not alone, which it may do once
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

    // whether a path of [readings] at [depth] may take a stray syllable: once a path, at the first depth only if [strayFirst]
    private fun canStray(strayed: Boolean, depth: Int, strayFirst: Boolean) = !strayed && (depth > 0 || strayFirst)

    // at each depth below [deepest], the syllables [readings] walks without straying: those of the characters of [texts] there
    private fun syllablesAt(dictionary: PinyinDictionary, vocabulary: Vocabulary, texts: Set<String>, deepest: Int): Array<BitSet> {
        val chars = HashSet<Int>().apply { texts.forEach { text -> forEachCodePoint(text) { add(it) } } }.toIntArray()
        chars.sort()
        // each character's syllables as a word of its own, from the root's children
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

    // the one code point word [id] is, or -1 if it is more or none
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

    /**
     * The word of a line of a dictionary in libime's text format, as libime reads one: `text
     * pin'yin`, a cost after it or not, apart by any whitespace, a value in quotes if fcitx
     * escaped it. Null for a blank or unreadable line, which libime skips too.
     */
    fun dictionaryEntry(line: String): Entry? = dictionaryEntry(splitValues(line))

    private fun dictionaryEntry(fields: List<String>): Entry? {
        if (fields.size !in 2..3) return null
        if (fields.size == 3 && fields[2].toFloatOrNull() == null) return null
        return entry(fields[0], fields[1])
    }

    /**
     * The words of [lines], a dictionary in libime's text format, a line each as this engine keeps
     * them (`text pin'yin cost`), those [dictionaryEntry] cannot read left out.
     */
    fun dictionaryText(lines: Sequence<String>): Sequence<String> = lines.mapNotNull { line ->
        val fields = splitValues(line)
        dictionaryEntry(fields)?.let { "${escapeValue(fields[0])} ${fields[1]} ${fields.getOrNull(2)?.toFloat() ?: 0f}" }
    }

    /** [text] read as [code] (`ni'hao`); null unless each character has a syllable spelled so. */
    internal fun entry(text: String, code: String): Entry? {
        if (text.isEmpty()) return null
        val syllables = code.split('\'').map { Syllables.id(spelling(it)) }
        if (syllables.any { it < 0 } || syllables.size != text.codePointCount(0, text.length)) return null
        return Entry(text, syllables.toIntArray())
    }

    // libime writes lü and nüe with the ü, and reads lue as lüe; WordLists reads a user's pinyin so too
    internal fun spelling(s: String): String = when (s) {
        "lue" -> "lve"
        "nue" -> "nve"
        else -> s.replace('ü', 'v')
    }
}
