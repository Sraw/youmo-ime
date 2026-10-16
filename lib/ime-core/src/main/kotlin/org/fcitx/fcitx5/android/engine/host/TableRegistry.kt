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
import org.fcitx.fcitx5.android.engine.table.TableUser
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * The table input methods of [Engines], each loaded at first need and kept, with what the user
 * taught it: those of [TABLES] from the app's assets ([load]), what the user taught each kept
 * under [userDir]. It shares the user's words ([lexicon]) with each table, coded by its rules ([shared]), tells
 * pinyin what a table saved, and forgets in every table, loaded or not, what is forgotten
 * anywhere ([forgetInTables]). Run on the fcitx thread, as [Engines] is.
 */
internal class TableRegistry(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit,
    private val compactions: Executor,
    private val assets: EngineAssets,
    private val lexicon: UserLexicon,
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
     * typed with pinyin, coded by its rules, as of now; the phrases it saves become pinyin's
     * words (those saved before this, once); what is blocked or forgotten anywhere is so here.
     */
    fun shared(table: Table, im: String): SharedWords = table.shared ?: object : SharedWords {
        override fun words(prefix: String) = coded().flatMap { it.words(prefix) }
        override fun leadsAnywhere(prefix: String) = coded().any { it.leadsAnywhere(prefix) }
        override fun blocked(text: String) = lexicon.isBlockedWord(text)
        override fun blockable(text: String) = text.codePointCount(0, text.length) >= 2 && assets.pinyinOf(text) != null
        override fun block(text: String) = lexicon.blockText(text)
        override fun forget(text: String) = lexicon.forgetEverywhere(text)

        private fun coded(): List<CodedWords> {
            val model = lexicon.user()
            if (table.codedFor !== model) {
                table.listed = null
                table.own = null
                table.codedFor = model
            }
            // all coded again only once words were listed other than by add() (a reload's dictionaries)
            val listed = table.listed?.takeIf { model.listings - table.listedAt == listedSince.size - table.listedFrom }
                ?: CodedWords(table.dictionary, model.listedTexts()).also {
                    table.listed = it
                    table.listedAt = model.listings
                    table.listedFrom = listedSince.size
                    table.later = null
                }
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
        // what learnSaved looks a saved phrase up in, made in the background rather than on the input thread at the first save
        val data = assets.pinyinData
        compactions.execute { data.wordIndex }
        table.user.onSaved = { lexicon.learnSaved(listOf(it), again = true) }
        tellPinyinOnce(table, im)
    }

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
        lexicon.learnSaved(table.user.savedTexts(), again = false)
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

    private val tables = HashMap<String, Table>()

    fun table(im: String): Table = tables.getOrPut(im) {
        val dictionary = TableDictionary(CodeTable.load(load("$TABLE_DIR/${TABLES.getValue(im).file}"), verify = false))
        val user = TableUser(dictionary)
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
