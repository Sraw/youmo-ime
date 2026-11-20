/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLE_DIR
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.platform.Blobs
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.table.CodedIndex
import org.fcitx.fcitx5.android.engine.table.CodedWords
import org.fcitx.fcitx5.android.engine.table.SavedPhrases
import org.fcitx.fcitx5.android.engine.table.SharedWords
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.IOException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.zip.CRC32

/** The table input methods, each loaded at first need and kept with what the user taught it (older builds' saved phrases from the record, [SavedPhrases]). Shares the user's words coded per table ([shared]) and forgets in every table what is forgotten anywhere ([forgetInTables]). On the fcitx thread. */
internal class TableRegistry(
    private val blobs: Blobs,
    private val onError: (IOException) -> Unit,
    // codes the user's words for a table, off the input thread
    private val coding: Executor,
    private val assets: EngineAssets,
    private val lexicon: UserLexicon,
    // whether the record has a text used or pinned anywhere: something to forget
    private val knows: (String) -> Boolean = { false },
    // the record's state, holding older builds' table phrases (LearnState.tableWords)
    private val state: () -> LearnState = { LearnState() },
    // removes those phrases from the record (LearnLog.dropTableWords)
    private val dropSaved: (List<TableWordRow>) -> Unit = {},
    // where per-table on-disk indexes of merged dictionaries live ([CodedIndex]; objects once exhausted the heap);
    // null keeps none and tables offer none of the file's words
    private val indexes: () -> UserFiles? = { null },
) {

    /** Both outlive the sessions made over them. */
    class Table(
        val dictionary: TableDictionary,
        val saved: SavedPhrases,
        // the loaded table data: its merged-file index is keyed on it
        val data: ByteBuffer? = null,
    ) {
        var shared: SharedWords? = null

        // the user's words coded for this table: dictionaries' per user model, with later add()s apart ([later]), and
        // made or typed words as of its changes then
        var listed: CodedWords? = null
        // dictionaries' words (300k+) coded off the input thread: a second or more on a phone
        var coding: InBackground<Listed>? = null
        var later: CodedWords? = null
        var own: CodedWords? = null
        var codedFor: UserModel? = null
        var listedAt = -1
        var listedFrom = 0
        var laterTo = -1
        var ownAt = -1
        // the merged file's words coded for this table, opened or built off the input thread
        var indexing: InBackground<CodedIndex>? = null
    }

    private val listedSince get() = lexicon.listedSince

    /** One user lexicon for every input method: [table] offers the user's words coded by its rules; older saved phrases are pinyin's words; blocks and forgets anywhere apply here, except to the table's own entries, which a pinyin block leaves. */
    fun shared(table: Table, im: String): SharedWords = table.shared ?: object : SharedWords {
        // an added word also in the file comes from both: offered once
        override fun words(prefix: String): List<Pair<String, String>> {
            val listed = coded().flatMap { it.words(prefix) }
            val merged = mergedNow()?.let { (index, file) -> index.exact(prefix, file) }.orEmpty()
            return if (merged.isEmpty()) listed else (listed + merged).distinct()
        }
        override fun longer(prefix: String, byLength: Boolean) =
            mergedNow()?.let { (index, file) -> index.longer(prefix, byLength, file) }.orEmpty()
        override fun leadsAnywhere(prefix: String) = coded().any { it.leadsAnywhere(prefix) } || mergedNow()?.first?.leadsAnywhere(prefix) == true
        // the gate besides isBlockedWord (two characters at least): a lone held character is held too
        override fun blocked(text: String) = lexicon.isBlockedWord(text) || lexicon.gate().holds(text)
        override fun blockable(text: String) = text.codePointCount(0, text.length) >= 2 && assets.pinyinOf(text) != null
        override fun forget(text: String) = lexicon.forgetEverywhere(text)
        // as pinyin offers to forget: what the record has, or an added word
        override fun learned(text: String) =
            knows(text) || lexicon.addedOf(text).isNotEmpty() || lexicon.user().let { m -> m.entriesOf(text).any { m.hasLearned(listOf(it)) } }
        override fun prepare() {
            coded()
            mergedNow()
        }

        private fun mergedNow(): Pair<CodedIndex, DictionaryFile>? = merged(table, im, lexicon.user())

        private fun coded(): List<CodedWords> {
            val model = lexicon.user()
            if (table.codedFor !== model) {
                table.listed = null
                table.own = null
                table.codedFor = model
            }
            // recoded only once words were listed other than by add() (a reload's dictionaries), in the background
            val listed = table.listed?.takeIf { model.listings - table.listedAt == listedSince.size - table.listedFrom }
                ?: coded(table, model)?.also { table.listed = it }
                ?: CodedWords(table.dictionary, emptyList())
            val later = table.later?.takeIf { table.laterTo == listedSince.size }
                ?: listedLater(table, listed, merged(table, im, model)).also {
                    table.later = it
                    table.laterTo = listedSince.size
                }
            val own = table.own?.takeIf { table.ownAt == model.changes }
                ?: CodedWords(table.dictionary, model.ownTexts()).also {
                    table.own = it
                    table.ownAt = model.changes
                }
            return listOf(own, listed, later)
        }
    }.also { table.shared = it }

    /** Starts coding [im]'s shared words for the current user model, if its session made them. */
    fun prepare(im: String) {
        tables[im]?.shared?.prepare()
    }

    // [table]'s listed words for [model], once coded on [coding]; null till then. add()s are coded apart (later)
    private fun coded(table: Table, model: UserModel): CodedWords? {
        val coding = table.coding ?: InBackground<Listed>(coding) { onError(IOException("coding the user's words for a table", it)) }.also { table.coding = it }
        val dictionary = table.dictionary
        val made = coding.get(model to model.listings - listedSince.size) {
            // merged-file words are left to its index (1.9 million were 250 MB a table); one also added by hand is coded
            // here too, offered once ([words])
            val texts = model.listedTexts()
            val at = model.listings
            val from = listedSince.size
            { stillAsked -> Listed(CodedWords(dictionary, texts, stillAsked), at, from) }
        } ?: return null
        if (table.listedAt != made.at || table.listedFrom != made.from) {
            table.listedAt = made.at
            table.listedFrom = made.from
            table.later = null
        }
        return made.words
    }

    // the coded words and the listings and add()s read at
    internal class Listed(val words: CodedWords, val at: Int, val from: Int)

    /** add()s since [table]'s [listed] were coded, coded alone; texts already coded in another reading or in the merged [index] left out. */
    private fun listedLater(table: Table, listed: CodedWords, index: Pair<CodedIndex, DictionaryFile>?): CodedWords {
        val texts = listedSince.subList(table.listedFrom, listedSince.size).filterNot { text ->
            table.dictionary.encode(text)?.let { code ->
                listed.words(code).any { it.first == code && it.second == text } || index?.let { (i, file) -> i.has(code, text, file) } == true
            } == true
        }
        return CodedWords(table.dictionary, texts)
    }

    /** [shared] while habits are off: blocks and enabled dictionaries' words (a setting, not a habit), none of the user's own. */
    fun dictionariesOnly(table: Table, im: String): SharedWords = object : SharedWords by shared(table, im) {
        private fun mergedNow() = merged(table, im, lexicon.freshUser())
        override fun words(prefix: String) = mergedNow()?.let { (index, file) -> index.exact(prefix, file) }.orEmpty()
        override fun longer(prefix: String, byLength: Boolean) =
            mergedNow()?.let { (index, file) -> index.longer(prefix, byLength, file) }.orEmpty()
        override fun leadsAnywhere(prefix: String) = mergedNow()?.first?.leadsAnywhere(prefix) == true
        override fun learned(text: String) = false
        override fun prepare() {
            mergedNow()
        }
    }

    /** [table]'s index of the merged file, opened or built on [coding]; null till then or with no file or place. An OOM is reported, not fatal ([InBackground]), though [CodedIndex.RUN] bounds the build. */
    private fun merged(table: Table, im: String, model: UserModel): Pair<CodedIndex, DictionaryFile>? {
        val file = model.dictionaryFile ?: return null
        val dir = indexes() ?: return null
        val name = "merged.$im.ymc"
        val indexing = table.indexing
            ?: InBackground<CodedIndex>(coding) { onError(IOException("coding the merged dictionaries for a table", it)) }.also { table.indexing = it }
        val dictionary = table.dictionary
        val data = table.data
        val index = indexing.get(file) {
            { stillAsked ->
                val key = "${data?.let(::crc32) ?: 0}/${file.size}/${file.stamp}"
                // stopped: what it returns is dropped
                CodedIndex.open(dir.file(name), key) ?: CodedIndex.build(dictionary, file, dir, name, key, stillAsked) ?: CodedIndex.NONE
            }
        } ?: return null
        return index to file
    }

    // the whole buffer, position kept: CRC32.update(ByteBuffer) is API 26, minSdk 29
    private fun crc32(buffer: ByteBuffer): Long = CRC32().apply { update(buffer.duplicate().also { (it as Buffer).clear() }) }.value

    /** In every table that saved them, loaded or not, as a saved phrase would be offered again. */
    fun forgetInTables(texts: Collection<String>) {
        val forgotten = texts.toSet()
        val rows = state().tableWords.entries.mapNotNull { (key, since) ->
            val (im, code, text) = key
            if (text in forgotten) TableWordRow(im, code, text, since) else null
        }
        drop(rows)
    }

    // out of the record, counted where a table offered it; an unloaded table is not loaded to tell, so its rows
    // count (no undo is the safe answer)
    private fun drop(rows: List<TableWordRow>) {
        if (rows.isEmpty()) return
        dropSaved(rows)
        forgets += rows.count { row ->
            row.im in TABLES && tables[row.im]?.let { it.dictionary.indexOf(row.code, row.text) < 0 } != false
        }
    }

    /** Phrases the tables forgot, counted up and never reset: compare before and after a call; such forgets cannot be undone. */
    var forgets = 0
        private set

    /** Of [texts], those [im] cannot take, with the table word they give way to: their code is a lone, whole, self-committing entry under [options], so the word is never offered. */
    fun sameCode(im: String, options: TableOptions, texts: Collection<String>): Map<String, String> {
        if (!options.autoSelect || options.autoSelectLength == 0) return emptyMap()
        val dictionary = table(im).dictionary
        if (dictionary.rules.isEmpty) return emptyMap()
        return texts.mapNotNull { text -> lone(dictionary, options, text)?.let { text to it } }.toMap()
    }

    // the lone word under [text]'s whole self-committing code; null if none
    private fun lone(dictionary: TableDictionary, options: TableOptions, text: String): String? {
        if (text.codePointCount(0, text.length) < 2) return null
        val code = dictionary.encode(text) ?: return null
        if (dictionary.contains(code, text)) return null
        if (code.length > dictionary.maxLength || code.length < options.autoSelectLength) return null
        if (dictionary.matchPinyin(code, prefix = true).isNotEmpty()) return null
        val coded = dictionary.match(code, null)
        return if (coded.any { dictionary.code(it) != code }) null else coded.map(dictionary::text).distinct().singleOrNull()
    }

    private val tables = HashMap<String, Table>()

    fun table(im: String): Table = tables.getOrPut(im) {
        val data = blobs.load("$TABLE_DIR/${TABLES.getValue(im).file}")
        val dictionary = TableDictionary(CodeTable.load(data.duplicate(), verify = false))
        Table(dictionary, SavedPhrases(dictionary, im, state, ::drop), data)
    }

    /** Adds to [into] the beyond-BMP characters of every [TABLES] entry, each table read and let go; unreadable ones told and skipped. */
    fun beyondBasic(into: MutableSet<Int>) {
        for (method in TABLES.values) {
            val table = readAlone(method.file) ?: continue
            for (i in 0 until table.size) beyondBasic(table.textLength(i), { table.textChar(i, it) }, into)
        }
    }

    // null, told, if it cannot be read (as EngineAssets.readOr)
    private fun readAlone(file: String): CodeTable? {
        val failed = try {
            return CodeTable.load(blobs.load("$TABLE_DIR/$file"), verify = false)
        } catch (e: IOException) {
            e
        } catch (e: DataFormatException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        }
        onError(IOException("cannot read $file: its characters not in 生僻字屏蔽's grid", failed))
        return null
    }

    /** After the engines closed: each table loads again. */
    fun closed() {
        tables.clear()
    }
}
