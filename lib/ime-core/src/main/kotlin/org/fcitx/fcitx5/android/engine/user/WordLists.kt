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
    fun add(entry: Entry): Boolean = added.add(entry).also { if (it) write(ADDED, added) }

    fun remove(entry: Entry): Boolean = added.remove(entry).also { if (it) write(ADDED, added) }

    fun block(entry: Entry): Boolean = blocked.add(entry).also { if (it) write(BLOCKED, blocked) }

    fun unblock(entry: Entry): Boolean = blocked.remove(entry).also { if (it) write(BLOCKED, blocked) }

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

    companion object {
        const val ADDED = "words.added"
        const val BLOCKED = "words.blocked"

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
