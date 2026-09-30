/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * What libime's pinyin learned of the user, for those coming from it, read from the text libime
 * writes of it: its user dictionary as `PinyinDictionary::saveText` writes it, a word a line
 * (`text pin'yin cost`), and its history as `HistoryBigram::dump` does, a sentence a line, newest
 * first, words apart by spaces, each `text<TAB>code` if it was kept with how it was read. The code
 * is libime's encoding of the reading, two bytes a syllable, which the app has libime spell.
 *
 * A word is learned as it was read. libime keeps most history without readings, though, so a
 * word without one is read as the dictionary reads it (its likeliest reading, for a polyphone),
 * or failing that character by character. One that cannot be read (Latin letters, say) is
 * dropped, and its sentence split there: the words either side were not typed one after the
 * other as far as the model can tell.
 */
object LibimeImport {

    /**
     * The text of libime's user dictionary and of its history, a line each, and how to spell a
     * code of the history (`pin'yin`, or anything not a reading if it is none).
     */
    class Legacy(val dictionary: List<String>, val history: List<String>, val decode: (String) -> String = { it })

    /**
     * Learns [history], oldest sentence first, then each word of [userDictionary] the history did
     * not have, into [model]. A word of the history is read as [decode] spells its code, else as
     * [dictionary] reads it.
     *
     * @return how many words were learned
     */
    fun learn(
        model: UserModel,
        dictionary: PinyinDictionary,
        vocabulary: Vocabulary,
        userDictionary: Sequence<String>,
        history: Sequence<String>,
        decode: (String) -> String = { it },
    ): Int {
        val lines = history.map { line -> words(line).map { Word(it.text, it.code?.let(decode)) } }.toList()
        val unread = HashSet<String>()
        for (word in lines.flatten()) {
            // nothing else is in the dictionary: Latin letters would only deepen the walk
            if (word.code?.let { entry(word.text, it) } != null || !isHan(word.text)) continue
            unread += word.text
            forEachCharacter(word.text) { unread += it }
        }
        val readings = readings(dictionary, vocabulary, unread)
        var words = 0
        for (sentence in lines.flatMap { sentences(it) { text -> read(text, readings) } }.asReversed()) {
            model.learn(null, sentence)
            words += sentence.size
        }
        for (entry in userDictionary.mapNotNull { dictionaryEntry(it) }) {
            if (model.probability(NO_WORD, model.id(entry)) == 0f) {
                model.learn(null, listOf(entry))
                words++
            }
        }
        return words
    }

    /**
     * The syllables of each of [texts] the dictionary has, as its likeliest reading of it: the
     * one of highest weight, the first found of equals. One walk of the trie, as deep as the
     * longest of [texts]; a word is made a string only if its length and first char could match.
     */
    internal fun readings(dictionary: PinyinDictionary, vocabulary: Vocabulary, texts: Set<String>): Map<String, IntArray> {
        if (texts.isEmpty()) return emptyMap()
        // a reading is a syllable a character, so a word at depth d has d characters
        val depths = texts.mapTo(HashSet()) { it.codePointCount(0, it.length) }
        val firsts = texts.mapNotNullTo(HashSet()) { it.firstOrNull() }
        val deepest = depths.max()
        val best = HashMap<String, IntArray>()
        val weights = HashMap<String, Float>()
        val path = IntArray(deepest)
        fun visit(node: Int, depth: Int) {
            if (depth in depths) {
                for (i in 0 until dictionary.wordCount(node)) {
                    val id = dictionary.word(node, i)
                    if (vocabulary.length(id) == 0 || vocabulary.char(id, 0) !in firsts) continue
                    val text = vocabulary.word(id)
                    val weight = dictionary.weight(node, i)
                    if (text in texts && weight > (weights[text] ?: Float.NEGATIVE_INFINITY)) {
                        weights[text] = weight
                        best[text] = path.copyOf(depth)
                    }
                }
            }
            if (depth == deepest) return
            val first = dictionary.firstChild(node)
            for (child in first until first + dictionary.childCount(node)) {
                path[depth] = dictionary.syllable(child)
                visit(child, depth + 1)
            }
        }
        visit(dictionary.root, 0)
        return best
    }

    /** [text] as [readings] reads it, whole or else character by character; null if it cannot. */
    internal fun read(text: String, readings: Map<String, IntArray>): Entry? {
        if (text.isEmpty()) return null
        readings[text]?.let { return Entry(text, it) }
        val syllables = ArrayList<Int>()
        var readable = true
        forEachCharacter(text) { c ->
            val reading = readings[c]
            if (reading == null || reading.size != 1) readable = false else syllables += reading[0]
        }
        return if (readable) Entry(text, syllables.toIntArray()) else null
    }

    private fun isHan(text: String): Boolean {
        if (text.isEmpty()) return false
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!Character.isIdeographic(c)) return false
            i += Character.charCount(c)
        }
        return true
    }

    private inline fun forEachCharacter(text: String, action: (String) -> Unit) {
        var i = 0
        while (i < text.length) {
            val end = text.offsetByCodePoints(i, 1)
            action(text.substring(i, end))
            i = end
        }
    }

    /** The word of a line of the user dictionary; null for a blank or unreadable one. */
    internal fun dictionaryEntry(line: String): Entry? {
        val fields = words(line)
        // a line of the text format has a cost after the reading; the history's words do not
        if (fields.size != 3 || fields.any { it.code != null } || fields[2].text.toFloatOrNull() == null) return null
        return entry(fields[0].text, fields[1].text)
    }

    /**
     * The runs of words of a line of the history that are each read: as it was kept, as this
     * engine spells, or else as [read] reads its text.
     */
    internal fun sentences(line: String, read: (String) -> Entry? = { null }): List<List<Entry>> = sentences(words(line), read)

    private fun sentences(words: List<Word>, read: (String) -> Entry?): List<List<Entry>> {
        val out = ArrayList<List<Entry>>()
        var run = ArrayList<Entry>()
        for (word in words) {
            val entry = word.code?.let { entry(word.text, it) } ?: read(word.text)
            if (entry != null) {
                run.add(entry)
            } else if (run.isNotEmpty()) {
                out += run
                run = ArrayList()
            }
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    /** [text] read as [code] (`ni'hao`); null unless each character has a syllable spelled so. */
    internal fun entry(text: String, code: String): Entry? {
        if (text.isEmpty()) return null
        val syllables = code.split('\'').map { Syllables.id(spelling(it)) }
        if (syllables.any { it < 0 } || syllables.size != text.codePointCount(0, text.length)) return null
        return Entry(text, syllables.toIntArray())
    }

    // libime writes lü and nüe with the ü, and reads lue as lüe
    private fun spelling(s: String): String = when (s) {
        "lue" -> "lve"
        "nue" -> "nve"
        else -> s.replace('ü', 'v')
    }

    internal class Word(val text: String, val code: String?)

    /**
     * [line] cut into words at spaces, each with its code if a tab follows its text, fcitx's
     * escaping of a value undone: a value holding a space, tab or quote is written in quotes, a
     * quote or backslash in it after a backslash, a newline as `\n`.
     */
    internal fun words(line: String): List<Word> {
        val out = ArrayList<Word>()
        val field = StringBuilder()
        var text: String? = null
        var quoted = false
        var escaped = false
        var any = false
        fun endWord() {
            val value = field.toString()
            val t = text
            if (t != null) out += Word(t, value) else if (any || value.isNotEmpty()) out += Word(value, null)
            field.setLength(0)
            text = null
            any = false
        }
        for (c in line) {
            when {
                escaped -> {
                    field.append(if (c == 'n') '\n' else c)
                    escaped = false
                }
                quoted && c == '\\' -> escaped = true
                c == '"' -> {
                    quoted = !quoted
                    any = true
                }
                quoted -> field.append(c)
                c == ' ' -> endWord()
                c == '\t' && text == null -> {
                    text = field.toString()
                    field.setLength(0)
                }
                else -> field.append(c)
            }
        }
        endWord()
        return out
    }
}
