/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.runtime.Engines.Imported
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.ImportFiles
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists

/**
 * A list of words imported in the settings, for [UserLexicon], split out of it (S11): the user's
 * own export added to the [lists] ([addWords], [blockAll]) and made theirs again ([owned]), another
 * input method's list blocked where marked so and the rest written to the learning record as a
 * batch of imported words ([imports], U-101), those [user] knows already left out. A change the
 * sessions must see drops them ([dropSessions]).
 */
internal class WordImport(
    private val assets: EngineAssets,
    private val lists: WordLists,
    private val user: () -> UserModel,
    private val addWords: (List<Entry>) -> Int,
    private val blockAll: (List<Entry>) -> Int,
    private val dropSessions: () -> Unit,
    private val owned: (Map<Entry, Float>) -> Int,
    private val imports: (String, List<ImportedWords.Incoming>, (String, String) -> Boolean) -> Int,
    /** English's words blocked ([WordListFiles.ENGLISH_BLOCK_MARK]): how many newly. */
    private val blockEnglish: (List<String>) -> Int = { 0 },
) {

    /**
     * The words of [lines], a list named [file]: the user's own export ([WordListFiles.EXPORT_MARK],
     * or an older one's header) added, blocked and learned as it was; another input method's list
     * blocked where marked so and imported as a batch (U-101), in the record, not on the hand list.
     */
    fun importWords(lines: Sequence<String>, file: String = ""): Imported {
        val list = readList(lines)
        val add = LinkedHashSet<Entry>()
        val block = LinkedHashSet<Entry>()
        var unread = list.unread
        val guessed = assets.pinyinsOf(list.read.filter { it.pinyin == null && it.text.isNotEmpty() }.map { it.text }, strayFirst = true)
        val incoming = ArrayList<Pair<Entry, Double?>>()
        for ((i, line) in list.read.withIndex()) {
            val pinyin = line.pinyin ?: guessed[line.text]
            // a blocked emoji's syllables are not one a code point (WordLists.blockedEntry)
            val entry = pinyin?.let { if (line.blocked) WordLists.blockedEntry(line.text, it) else WordLists.entry(line.text, it) }
            when {
                entry == null -> unread++
                line.blocked -> block += entry
                list.own -> add += entry
                // another list's line of no Chinese (a Latin word given a guessed pinyin) is no word to import
                !Han.any(line.text) -> unread++
                else -> incoming += entry to list.counts[i]
            }
        }
        // a word the file has both ways: blocked, the safer of the two
        add -= block
        val added = if (add.isEmpty()) 0 else addWords(add.toList())
        var blocked = 0
        if (block.isNotEmpty()) {
            blocked = blockAll(block.toList())
            dropSessions()
        }
        if (list.english.isNotEmpty()) {
            blocked += blockEnglish(list.english)
            dropSessions()
        }
        val batch = incoming.filter { it.first !in block }.map { (e, count) -> ImportedWords.Incoming(e.text, e.spelled(), count) }
        val imported = if (batch.isEmpty()) 0 else imports(file, batch, kept())
        if (imported > 0) dropSessions()
        return Imported(added, blocked, unread, learnAll(list.learn), imported)
    }

    // a list's lines as read: the words ([read], each with its line's count), the learned of an export, whether it is the user's own
    private class ListRead(val read: List<WordLists.Line>, val counts: List<Double?>, val learn: Map<Entry, Float>, val unread: Int, val own: Boolean, val english: List<String>)

    private fun readList(lines: Sequence<String>): ListRead {
        val learn = LinkedHashMap<Entry, Float>()
        var unread = 0
        // `~`, a learned word, only in the user's own export (its first line the mark): another
        // list's line never becomes a learned word unseen, it is not read (counted unread). One
        // pass, no line kept but as read
        var export: Boolean? = null
        var own = false
        val read = ArrayList<WordLists.Line>()
        val counts = ArrayList<Double?>()
        val english = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trimStart().removePrefix("\uFEFF")
            if (export == null) {
                export = WordListFiles.exportMark(raw)
                own = WordListFiles.exportMark(raw, old = true) == true
            }
            if (line.startsWith(WordListFiles.ENGLISH_BLOCK_MARK)) {
                val word = line.removePrefix(WordListFiles.ENGLISH_BLOCK_MARK).trim()
                if (EnglishText.isWord(word)) english += word else unread++
            } else if (!line.startsWith(WordListFiles.LEARNED_MARK)) {
                WordLists.line(raw)?.let {
                    read += it
                    counts += ImportFiles.frequency(raw)
                }
            } else {
                val rest = line.removePrefix(WordListFiles.LEARNED_MARK)
                val word = WordLists.line(rest)
                val entry = word?.pinyin?.let { WordLists.entry(word.text, it) }
                if (export != true || entry == null || word.blocked) unread++ else learn[entry] = learnedCount(rest)
            }
        }
        return ListRead(read, counts, learn, unread, own, english)
    }

    // the words an import leaves as they are (U-101): the dictionary's (in any reading: a list's misreading of
    // one is no new word, review-G S4) or a user dictionary's, added, imported or blocked already
    private fun kept(): (String, String) -> Boolean {
        val model = user()
        val blocked = lists.blockedWords.toHashSet()
        return { text, reading ->
            model.knowsText(text) || HabitCounts.entry(text, reading)?.let { model.origin(it) != Row.NONE || model.knows(it) || it in blocked } ?: true
        }
    }

    /**
     * Makes each of [words] the user's own, as a phone move brings them back: not one added, blocked
     * or theirs already; an export's count of each comes back as its uses today ([owned]). How many.
     */
    private fun learnAll(words: Map<Entry, Float>): Int {
        if (words.isEmpty()) return 0
        val added = lists.addedWords.toHashSet()
        val blocked = lists.blockedWords.toHashSet()
        return owned(words.filterKeys { it !in added && it !in blocked })
    }
}

// a learned line's count, its last field; 1 for none
private fun learnedCount(line: String) = line.trim().split(' ').last().toFloatOrNull()?.takeIf { it > 0f } ?: 1f
