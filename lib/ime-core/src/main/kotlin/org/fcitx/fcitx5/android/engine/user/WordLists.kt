/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The words the user added by hand and the words they blocked, as they edit them in the settings
 * or block one from the keyboard. Each list is a file in [dir], a word a line as libime's text
 * dictionaries have it (`你好 ni'hao`), written whole on each change: they are lists a user keeps
 * by hand, short, and unlike the log ([UserStore]) not counts. With no [dir], kept in memory.
 */
class WordLists(private val dir: File?, private val onError: (IOException) -> Unit = {}) {
    private val added = read(ADDED)
    private val blocked = read(BLOCKED)

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

    fun blockAll(entries: Collection<Entry>): List<Entry> = change(BLOCKED, blocked, entries) { blocked.add(it) }

    fun unblockAll(entries: Collection<Entry>): List<Entry> = change(BLOCKED, blocked, entries) { blocked.remove(it) }

    private inline fun change(name: String, list: Set<Entry>, entries: Collection<Entry>, op: (Entry) -> Boolean) =
        entries.filter(op).also { if (it.isNotEmpty()) write(name, list) }

    /** Lists the added words in [model], and blocks the blocked ones there. */
    fun applyTo(model: UserModel) {
        added.forEach { model.list(it) }
        blocked.forEach { model.block(it) }
    }

    private fun read(name: String): LinkedHashSet<Entry> {
        val file = dir?.let { File(it, name) } ?: return LinkedHashSet()
        return try {
            if (!file.exists()) LinkedHashSet() else file.useLines { lines ->
                lines.mapNotNull { LibimeImport.dictionaryEntry(it) }.toCollection(LinkedHashSet())
            }
        } catch (e: IOException) {
            onError(e)
            LinkedHashSet()
        }
    }

    private fun write(name: String, entries: Collection<Entry>) {
        val dir = dir ?: return
        try {
            dir.mkdirs()
            val file = File(dir, name)
            val temp = File(dir, "$name.new")
            temp.writeText(entries.joinToString("") { "${escapeValue(it.text)} ${code(it.syllables)}\n" })
            // a whole list or the one before it, never half of one
            if (!temp.renameTo(file)) throw IOException("cannot write $file")
        } catch (e: IOException) {
            // kept in memory: what the user sees stays as they asked, till the next start
            onError(e)
        }
    }

    /**
     * A line of a word list the user imports, as other input methods export them too: a word
     * and its pinyin either way round (`幽默 you'mo`, `you mo 幽默`), a count after them left
     * out; a word alone, its [pinyin] null, for the dictionary to read. `!` before the word
     * blocks it, as [Engines][org.fcitx.fcitx5.android.engine.host.Engines] exports the blocked.
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

        /**
         * A word list as other input methods write theirs: UTF-8, or UTF-16 with its byte order
         * mark (Sogou's export), or else GB18030 (older Windows ones).
         */
        fun decode(bytes: ByteArray): String {
            fun bom(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
            return when {
                bom(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bom(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: CharacterCodingException) {
                    String(bytes, Charset.forName("GB18030"))
                }
            }
        }

        /** [line] as a word to import; null for a blank line or a `#` comment, [Line.UNREAD] for one with no word in it. */
        fun line(line: String): Line? {
            var rest = line.trim().removePrefix("\uFEFF")
            if (rest.isEmpty() || rest.startsWith("#")) return null
            val blocked = rest.startsWith("!")
            if (blocked) rest = rest.substring(1)
            var text: String? = null
            val pinyin = ArrayList<String>()
            for (token in rest.split(Regex("\\s+"))) when {
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

        /** [syllables] as libime's text dictionaries spell them: `ni'hao`. */
        fun code(syllables: IntArray) = syllables.joinToString("'") { Syllables.spelling(it) }

        /**
         * [text] read as [pinyin], as a user types it in: syllables apart by spaces or `'`, or run
         * together (`nihao`) where they split one way into a syllable a character; ü as v or ü.
         * Null where it does not read so.
         */
        fun entry(text: String, pinyin: String): Entry? {
            val word = text.trim()
            val chars = word.codePointCount(0, word.length)
            // as spelled first: a letter is a syllable of its own (A股 A'gu), lowercased a vowel or none
            val given = pinyin.trim().replace('ü', 'v').split(Regex("[\\s']+")).filter { it.isNotEmpty() }
            // only where the word has that letter: 啊 A is the syllable a, not the letter
            val letters = given.size == chars && word.isNotEmpty() && given.withIndex().all { (i, part) ->
                part.none { it.isUpperCase() } || part == String(Character.toChars(word.codePointAt(word.offsetByCodePoints(0, i))))
            }
            if (letters) LibimeImport.entry(word, given.joinToString("'"))?.let { return it }
            val parts = pinyin.trim().lowercase().replace('ü', 'v').split(Regex("[\\s']+")).filter { it.isNotEmpty() }
            if (word.isEmpty() || parts.isEmpty()) return null
            val syllables = if (parts.size == chars) parts else split(parts.joinToString(""), chars) ?: return null
            return LibimeImport.entry(word, syllables.joinToString("'"))
        }

        // spelling as [count] syllables, if one way only does it
        private fun split(spelling: String, count: Int): List<String>? {
            // ways[i][k]: the splits of spelling[i:] into k syllables, up to 2 (more is as bad as 2)
            val n = spelling.length
            val ways = Array(n + 1) { IntArray(count + 1) }
            val next = Array(n + 1) { IntArray(count + 1) { -1 } }
            ways[n][0] = 1
            for (i in n - 1 downTo 0) for (k in 1..count) for (j in i + 1..minOf(n, i + MAX_SPELLING)) {
                if (ways[j][k - 1] == 0 || Syllables.id(fixed(spelling.substring(i, j))) < 0) continue
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

        private fun fixed(s: String) = when (s) {
            "lue" -> "lve"
            "nue" -> "nve"
            else -> s
        }

        private const val MAX_SPELLING = 6
    }
}
