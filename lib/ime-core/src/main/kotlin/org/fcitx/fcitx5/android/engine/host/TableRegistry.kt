/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.TABLES
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.TABLE_DIR
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.TOLD
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.userTable
import org.fcitx.fcitx5.android.engine.table.CodedWords
import org.fcitx.fcitx5.android.engine.table.SharedWords
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableUser
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * The table input methods of [Engines], each loaded at first need and kept, with what the user
 * taught it: those of [TABLES] from the app's assets ([load]), the phrases each saved in an older
 * build kept under [userDir]. It shares the user's words ([lexicon]) with each table, coded by its rules ([shared]), tells
 * pinyin what a table saved, and forgets in every table, loaded or not, what is forgotten
 * anywhere ([forgetInTables]). Run on the fcitx thread, as [Engines] is.
 */
internal class TableRegistry(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit,
    private val compactions: Executor,
    // where the user's words are coded for a table, off the input thread
    private val coding: Executor,
    private val assets: EngineAssets,
    private val lexicon: UserLexicon,
    // whether the learning record has a text, used or pinned anywhere: something to forget (impl/design-1b.md §3)
    private val knows: (String) -> Boolean = { false },
    // whether the learning record is kept on disk: what pinyin is told of a table's phrases lasts only then (review-int-records N8)
    private val recorded: () -> Boolean = { true },
    // the learning record opened, before an older build's log is: its conversion reads that log's
    // picks, which this build no longer keeps, and opening a large one compacts it (review C S5)
    private val beforeOpen: () -> Unit = {},
) {

    /** A table and what the user taught it: both outlive the sessions made over them. */
    class Table(
        val dictionary: TableDictionary,
        val user: TableUser,
        val store: TableUser.Store?,
    ) {
        var shared: SharedWords? = null

        // the user's words coded for this table: those of their dictionaries, made once a user
        // model, with those add() listed since apart ([later], from [listedFrom] in listedSince);
        // and those they made or typed, as of its changes then
        var listed: CodedWords? = null
        // the dictionaries' words, 300k and more, coded off the input thread: a second, more on a phone
        var coding: InBackground<Listed>? = null
        var later: CodedWords? = null
        var own: CodedWords? = null
        var codedFor: UserModel? = null
        var listedAt = -1
        var listedFrom = 0
        var laterTo = -1
        var ownAt = -1
    }

    private val listedSince get() = lexicon.listedSince

    /**
     * One user lexicon for every input method: [table] offers the words the user made, added or
     * typed with pinyin, coded by its rules, as of now; the phrases it saved in an older build
     * are pinyin's words (told once); what is blocked or forgotten anywhere is so here, but for
     * the table's own entries, which a block from pinyin leaves (C12).
     */
    fun shared(table: Table, im: String): SharedWords = table.shared ?: object : SharedWords {
        override fun words(prefix: String) = coded().flatMap { it.words(prefix) }
        override fun leadsAnywhere(prefix: String) = coded().any { it.leadsAnywhere(prefix) }
        override fun blocked(text: String) = lexicon.isBlockedWord(text)
        override fun blockable(text: String) = text.codePointCount(0, text.length) >= 2 && assets.pinyinOf(text) != null
        override fun forget(text: String) = lexicon.forgetEverywhere(text)
        // as pinyin offers to forget: what the record has, or a word added by hand (PY-33)
        override fun learned(text: String) =
            knows(text) || lexicon.addedOf(text).isNotEmpty() || lexicon.user().let { m -> m.entriesOf(text).any { m.hasLearned(listOf(it)) } }
        override fun prepare() {
            coded()
        }

        private fun coded(): List<CodedWords> {
            val model = lexicon.user()
            if (table.codedFor !== model) {
                table.listed = null
                table.own = null
                table.codedFor = model
            }
            // all coded again only once words were listed other than by add() (a reload's dictionaries),
            // in the background: till then the table offers its own words and those below, not these
            val listed = table.listed?.takeIf { model.listings - table.listedAt == listedSince.size - table.listedFrom }
                ?: coded(table, model)?.also { table.listed = it }
                ?: CodedWords(table.dictionary, emptyList())
            val later = table.later?.takeIf { table.laterTo == listedSince.size }
                ?: listedLater(table, listed).also {
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
    }.also { shared ->
        table.shared = shared
        tellPinyinOnce(table, im)
    }

    /** [im]'s shared words, if its session made them, started coding for the user model as it now is. */
    fun prepare(im: String) {
        tables[im]?.shared?.prepare()
    }

    // [table]'s listed words for [model] as it now lists them, once coded on [coding]; null till then.
    // Asked for by the listings not add()'s (a reload's dictionaries): one add() made is coded apart (later)
    private fun coded(table: Table, model: UserModel): CodedWords? {
        val coding = table.coding ?: InBackground<Listed>(coding) { onError(IOException("coding the user's words for a table", it)) }.also { table.coding = it }
        val dictionary = table.dictionary
        val made = coding.get(model to model.listings - listedSince.size) {
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

    // the listed words coded, and the listings and add()s they were read at
    internal class Listed(val words: CodedWords, val at: Int, val from: Int)

    /** What add() listed since [table]'s [listed] were coded, coded alone; a text listed before in another reading left out, coded already. */
    private fun listedLater(table: Table, listed: CodedWords): CodedWords {
        val texts = listedSince.subList(table.listedFrom, listedSince.size).filterNot { text ->
            table.dictionary.encode(text)?.let { code -> listed.words(code).any { it.first == code && it.second == text } } == true
        }
        return CodedWords(table.dictionary, texts)
    }

    /**
     * What [table] saved before pinyin heard of its phrases, told it once: a phrase the user took
     * out of pinyin's words later is not put back.
     */
    private fun tellPinyinOnce(table: Table, im: String) {
        val told = userDir?.let { File(it, userTable(im) + TOLD) }
        if (told?.exists() == true) return
        lexicon.learnSaved(table.user.savedTexts())
        // learned in memory only: told again at the next start, when the record may open
        if (!recorded()) return
        try {
            told?.createNewFile()
        } catch (_: IOException) {
            // told again next time, harmlessly: pinyin learns only those it lacks; where the log
            // cannot be kept either, that is reported already
        }
    }

    // the tables loaded, and those with a log on disk: a phrase saved there would be shared again
    fun forgetInTables(texts: Collection<String>) {
        val forgotten = texts.toSet()
        // a table saves and shares no single character: none to load for one
        val phrases = forgotten.any { it.codePointCount(0, it.length) >= 2 }
        val logged = TABLES.keys.filter { im -> im in tables || (phrases && userDir?.let { File(it, userTable(im)).exists() } == true) }
        for (im in logged) table(im).user.forgetTexts(forgotten)
    }

    /** How many phrases the tables forgot (here, or a long press in one): no learning record has them, so no undo takes them back. */
    val forgets get() = tables.values.sumOf { it.user.forgot }

    /** 全部清空 (U-86): the phrases every table saved, loaded or kept on disk, forgotten, and an older build's picks with them. */
    fun clearSaved() {
        val logged = TABLES.keys.filter { im -> im in tables || userDir?.let { File(it, userTable(im)).exists() } == true }
        for (im in logged) table(im).user.let {
            it.forgetTexts(it.savedTexts())
            it.forgetOlderPicks()
        }
    }

    /**
     * Of [texts], those [im] cannot take (D8, TBL-12), each with the table's word it gives way to:
     * the word's code is one the table has a lone entry of, its code whole, that commits itself
     * under [options], so a session never offers the word there (TableSession's rank).
     */
    fun sameCode(im: String, options: TableOptions, texts: Collection<String>): Map<String, String> {
        if (!options.autoSelect || options.autoSelectLength == 0) return emptyMap()
        val dictionary = table(im).dictionary
        if (dictionary.rules.isEmpty) return emptyMap()
        return texts.mapNotNull { text -> lone(dictionary, options, text)?.let { text to it } }.toMap()
    }

    // the table's word alone under [text]'s code, that code whole and committing itself; null if none
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
        val dictionary = TableDictionary(CodeTable.load(load("$TABLE_DIR/${TABLES.getValue(im).file}"), verify = false))
        val user = TableUser(dictionary)
        if (userDir != null) beforeOpen()
        Table(dictionary, user, userDir?.let { openStore(File(it, userTable(im)), user) })
    }

    // a log that cannot be read or moved aside: learn in memory rather than not type
    private fun openStore(file: File, user: TableUser): TableUser.Store? {
        file.parentFile?.mkdirs()
        return try {
            TableUser.Store(file, user, onError, background = compactions).apply { open() }
        } catch (e: IOException) {
            onError(e)
            null
        }
    }

    /** The logs of the tables loaded, for [Engines.close] to close. */
    fun stores(): List<TableUser.Store> = tables.values.mapNotNull { it.store }

    /** After [stores] were closed: each table loads again, reads its log again, and appends to it. */
    fun closed() {
        tables.clear()
    }
}
