/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.data.splitValues
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.store.replaceFile
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * The words the user added by hand and the words they blocked, as they edit them in the settings
 * or block one from the keyboard. Each list is a file in [dir], a word a line as libime's text
 * dictionaries have it (`你好 ni'hao`), written whole on each change: they are lists a user keeps
 * by hand, short, and unlike the learning record not counts. With no [dir], kept in memory.
 */
class WordLists(private val dir: UserFiles?, private val onError: (IOException) -> Unit = {}) {
    // the lists whose file did not read: what is in memory is not all of them, so not written over them
    private val unread = HashSet<String>()
    private val added = read(ADDED)
    // an emoji blocked in its word's reading has as many syllables as that word, not as its code points (blockedEntry)
    private val blocked = read(BLOCKED) { line -> splitValues(line).takeIf { it.size == 2 || it.size == 3 && it[2].toFloatOrNull() != null }?.let { blockedEntry(it[0], it[1]) } }

    val addedWords: List<Entry> get() = added.toList()
    val blockedWords: List<Entry> get() = blocked.toList()

    /** Adds [entry]; whether it was not there already. */
    fun add(entry: Entry): Boolean = addAll(listOf(entry)).isNotEmpty()

    fun remove(entry: Entry): Boolean = removeAll(listOf(entry)).isNotEmpty()

    fun block(entry: Entry): Boolean = blockAll(listOf(entry)).isNotEmpty()

    fun unblock(entry: Entry): Boolean = unblockAll(listOf(entry)).isNotEmpty()

    // a list written once however many change: a thousand words imported are one write, not a thousand

    /** Adds [entries]; those that were not there already. */
    fun addAll(entries: Collection<Entry>): List<Entry> = change(ADDED, added, entries) { added.add(it) }

    fun removeAll(entries: Collection<Entry>): List<Entry> = change(ADDED, added, entries) { added.remove(it) }

    /** Puts [entries] back on the added words, each at its index then (a delete taken back): those that were not there. */
    fun putBack(entries: List<IndexedValue<Entry>>): List<Entry> {
        val back = entries.filter { it.value !in added }.sortedBy { it.index }
        if (back.isEmpty()) return emptyList()
        val all = added.toMutableList()
        for ((i, e) in back) all.add(i.coerceAtMost(all.size), e)
        added.clear()
        added.addAll(all)
        write(ADDED, added)
        return back.map { it.value }
    }

    fun blockAll(entries: Collection<Entry>): List<Entry> = change(BLOCKED, blocked, entries) { blocked.add(it) }

    fun unblockAll(entries: Collection<Entry>): List<Entry> = change(BLOCKED, blocked, entries) { blocked.remove(it) }

    private inline fun change(name: String, list: Set<Entry>, entries: Collection<Entry>, op: (Entry) -> Boolean) =
        entries.filter(op).also { if (it.isNotEmpty()) write(name, list) }

    /** Lists the added words in [model], and blocks the blocked ones there. */
    fun applyTo(model: UserModel) {
        added.forEach { model.listByHand(it) }
        blocked.forEach { model.block(it) }
    }

    private fun read(name: String, entry: (String) -> Entry? = WordText::dictionaryEntry): LinkedHashSet<Entry> {
        val file = dir?.file(name) ?: return LinkedHashSet()
        return try {
            if (!file.taken()) LinkedHashSet() else file.input().bufferedReader().useLines { lines ->
                lines.mapNotNull(entry).toCollection(LinkedHashSet())
            }
        } catch (e: IOException) {
            onError(e)
            unread += name
            LinkedHashSet()
        }
    }

    private fun write(name: String, entries: Collection<Entry>) {
        val dir = dir ?: return
        val file = dir.file(name)
        if (name in unread) {
            onError(IOException("$file did not read: not written over"))
            return
        }
        try {
            dir.mkdirs()
            // a whole list or the one before it, never half of one, nor an empty one after a power cut
            replaceFile(file, dir.file("$name.new")) { temp ->
                temp.write(entries.joinToString("") { "${escapeValue(it.text)} ${it.apostrophed()}\n" }.toByteArray())
            }
        } catch (e: IOException) {
            // kept in memory: what the user sees stays as they asked, till the next start
            onError(e)
        }
    }

    /**
     * A line of a word list the user imports, as other input methods export them too: a word
     * and its pinyin either way round (`幽默 you'mo`, `you mo 幽默`), a count after them left
     * out; a word alone, its [pinyin] null, for the dictionary to read. `!` before the word
     * blocks it, as [Engines][org.fcitx.fcitx5.android.engine.runtime.Engines] exports the blocked.
     */
    data class Line(val text: String, val pinyin: String?, val blocked: Boolean) {
        companion object {
            /** A line with no word in it this reads: counted, so a file read as nothing does not say so silently. */
            val UNREAD = Line("", null, false)
        }
    }

    companion object {
        const val ADDED = "words.added"
        const val BLOCKED = "words.blocked"

        // not toFloat's: 3D and NaN are words
        private val NUMBER = Regex("-?\\d+(\\.\\d+)?")
        private val SPACES = Regex("\\s+")
        private val APART = Regex("[\\s']+")

        /**
         * A word list as other input methods write theirs: UTF-8, or UTF-16 with its byte order
         * mark (Sogou's export), or else GB18030 (older Windows ones).
         */
        fun decode(bytes: ByteArray): String {
            val marked = bomCharset(bytes, bytes.size)
            return when {
                marked != null -> String(bytes, 2, bytes.size - 2, marked)
                else -> try {
                    strictUtf8().decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: CharacterCodingException) {
                    String(bytes, GB18030)
                }
            }
        }

        /** UTF-16LE or BE by the byte order mark in the first [size] bytes of [head]; null for none. */
        internal fun bomCharset(head: ByteArray, size: Int): Charset? {
            fun bom(a: Int, b: Int) = size >= 2 && head[0] == a.toByte() && head[1] == b.toByte()
            return when {
                bom(0xFF, 0xFE) -> Charsets.UTF_16LE
                bom(0xFE, 0xFF) -> Charsets.UTF_16BE
                else -> null
            }
        }

        /** A UTF-8 decoder that fails on bytes UTF-8 has not: the list is then GB18030's. */
        internal fun strictUtf8(): CharsetDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        internal val GB18030: Charset = Charset.forName("GB18030")

        /** [line] as a word to import; null for a blank line or a `#` comment, [Line.UNREAD] for one with no word in it. */
        fun line(line: String): Line? {
            var rest = line.trim().removePrefix("\uFEFF")
            if (rest.isEmpty() || rest.startsWith("#")) return null
            val blocked = rest.startsWith("!")
            if (blocked) rest = rest.substring(1)
            var text: String? = null
            val pinyin = ArrayList<String>()
            for (token in rest.split(SPACES)) when {
                // a count, a cost, or a word pack's score (-5.6)
                token.isEmpty() || NUMBER.matches(token) -> {}
                token.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == 'ü' } -> pinyin += token
                text == null -> text = token
                // a second word: not a line this reads
                else -> return Line.UNREAD
            }
            text ?: return Line.UNREAD
            return Line(text, pinyin.joinToString(" ").ifEmpty { null }, blocked)
        }

        /**
         * [text] read as [pinyin], as a user types it in: syllables apart by spaces or `'`, or run
         * together (`nihao`) where they split one way into a syllable a character; ü as v or ü.
         * Null where it does not read so.
         */
        fun entry(text: String, pinyin: String): Entry? {
            val word = text.trim()
            val chars = word.codePointCount(0, word.length)
            // as spelled first: a letter is a syllable of its own (A股 A'gu), lowercased a vowel or none
            val given = pieces(pinyin)
            // only where the word has that letter: 啊 A is the syllable a, not the letter
            val letters = given.size == chars && word.isNotEmpty() && given.withIndex().all { (i, part) ->
                part.none { it.isUpperCase() } || part == String(Character.toChars(word.codePointAt(word.offsetByCodePoints(0, i))))
            }
            if (letters) WordText.entry(word, given.joinToString("'"))?.let { return it }
            val parts = pieces(pinyin.lowercase())
            if (word.isEmpty() || parts.isEmpty()) return null
            val syllables = if (parts.size == chars) parts else split(parts.joinToString(""), chars) ?: return null
            return WordText.entry(word, syllables.joinToString("'"))
        }

        /**
         * A blocked [text] read as [pinyin]: as [entry], but a text with no Han in it (an emoji
         * blocked in its word's reading, U-82: 👍🏻 hao, 😃 ha'ha) read as the syllables given,
         * whatever its code points: [entry] would take none, or split the reading among them.
         */
        fun blockedEntry(text: String, pinyin: String): Entry? {
            val word = text.trim()
            val chars = word.codePointCount(0, word.length)
            // only a text of no Han, letter or digit (an emoji): a word's are a syllable each
            if (word.isEmpty() || word.codePoints().anyMatch { Han.isHan(it) || Character.isLetterOrDigit(it) }) return entry(text, pinyin)
            val ids = pieces(pinyin.lowercase()).map { Syllables.id(WordText.spelling(it)) }
            return if (ids.isNotEmpty() && ids.none { it < 0 } && ids.size != chars) Entry(word, ids.toIntArray()) else entry(text, pinyin)
        }

        // [pinyin]'s syllables, or runs of them, ü as v
        private fun pieces(pinyin: String) = pinyin.trim().replace('ü', 'v').split(APART).filter { it.isNotEmpty() }

        // spelling as [count] syllables, if one way only does it
        private fun split(spelling: String, count: Int): List<String>? {
            // the tables below are (n+1)×(count+1): a line of an imported file can be any length
            if (count > MAX_SPLIT || spelling.length > count * MAX_SPELLING) return null
            // ways[i][k]: the splits of spelling[i:] into k syllables, up to 2 (more is as bad as 2)
            val n = spelling.length
            val ways = Array(n + 1) { IntArray(count + 1) }
            val next = Array(n + 1) { IntArray(count + 1) { -1 } }
            ways[n][0] = 1
            for (i in n - 1 downTo 0) for (k in 1..count) for (j in i + 1..minOf(n, i + MAX_SPELLING)) {
                if (ways[j][k - 1] == 0 || Syllables.id(WordText.spelling(spelling.substring(i, j))) < 0) continue
                ways[i][k] = minOf(2, ways[i][k] + ways[j][k - 1])
                next[i][k] = j
            }
            if (ways[0][count] != 1) return null
            val result = ArrayList<String>(count)
            var i = 0
            for (k in count downTo 1) {
                val j = next[i][k]
                result += spelling.substring(i, j)
                i = j
            }
            return result
        }

        private const val MAX_SPELLING = 6

        // characters of a word whose pinyin is run together: a word, not a sentence
        private const val MAX_SPLIT = 32
    }
}
