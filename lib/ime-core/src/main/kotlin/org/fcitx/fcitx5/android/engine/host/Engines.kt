/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.TableUser
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserStore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The input methods the androidengine addon lists, by name, each with its session made on first
 * use. [load] gives a data file by its path in the app's assets; what the user picks, in pinyin
 * and in each table, is kept under [userDir] (in memory only if null). When pinyin's log is made
 * afresh, what libime's pinyin learned ([legacy], if the user had it) is read into it first. What the user added to
 * pinyin ([additions]) is read at first need, and again on [reload].
 *
 * Everything here runs on one thread, the one fcitx runs on.
 */
class Engines(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit = {},
    private val legacy: () -> LibimeImport.Legacy? = { null },
    private val additions: () -> Additions? = { null },
) : Closeable {

    /**
     * What the user added to pinyin, as libime kept it: its custom phrases file, and the lines
     * of its dictionaries turned on, in libime's text format (`你好 ni'hao 0`), read only when
     * the user's words are made: they can be many, and are kept in the model, not here.
     * [dictionaries] tells the dictionaries apart (their names, sizes and times, say): read again
     * on [reload] only if it changed.
     */
    class Additions(val phrases: String, val dictionaries: String, val dictionary: () -> List<String>)

    // the files are signed with the app: no need to read them through for their checksums
    private val pinyinData by lazy(LazyThreadSafetyMode.NONE) { PinyinData.load(load(PINYIN_DATA), verify = false) }

    // floats rather than int8 as stored: 17 MB rather than 4.5, and on ART two and a half times faster;
    // dropped when turned off, loaded (or tried) again when turned on
    private var sentenceModel: SentenceModel? = null
    private var sentenceModelTried = false

    private fun sentenceModel(): SentenceModel? {
        if (sentenceModelTried) return sentenceModel
        sentenceModelTried = true
        sentenceModel = try {
            SentenceModel.load(load(SENTENCE_MODEL), unpack = true)
        } catch (e: IOException) {
            onError(e)
            null
        } catch (e: IllegalArgumentException) {
            // pinyin reads as well without it as before it was added
            onError(IOException("cannot read $SENTENCE_MODEL", e))
            null
        }
        return sentenceModel
    }

    private var added: Additions? = null
    private var addedRead = false

    private fun added(): Additions? {
        if (addedRead) return added
        addedRead = true
        added = try {
            additions()
        } catch (e: IOException) {
            onError(e)
            null
        }
        return added
    }

    private var phrases: CustomPhrases? = null

    private fun phrases(): CustomPhrases = phrases ?: CustomPhrases.parse(added()?.phrases.orEmpty()).also { phrases = it }

    private var store: UserStore? = null
    private var userModel: UserModel? = null

    // the dictionaries' words go in as words the user added, uncounted: scored as the model's
    // unknown word until picked, and not kept in the log unless picked
    private fun user(): UserModel = userModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary).also { model ->
        userModel = model
        if (userDir != null) {
            userDir.mkdirs()
            val file = File(userDir, USER_PINYIN)
            // a log that cannot be read or moved aside: learn in memory rather than not type
            store = try {
                UserStore(file, model, onError = onError).apply { open(seed = ::importLegacy) }
            } catch (e: IOException) {
                onError(e)
                null
            }
        }
        addDictionaries(model)
    }

    private fun addDictionaries(model: UserModel) {
        val lines = try {
            added()?.dictionary?.invoke().orEmpty()
        } catch (e: IOException) {
            onError(e)
            emptyList()
        }
        for (line in lines) LibimeImport.dictionaryEntry(line)?.let { model.id(it) }
    }

    /**
     * What libime learned, into a log with nothing in it yet. Whatever fails is reported and
     * pinyin goes on without it. Files that cannot be read leave the log empty, for the next
     * start to try again; a failure while learning them keeps what was learned before it.
     */
    private fun importLegacy(model: UserModel) {
        try {
            val old = legacy() ?: return
            LibimeImport.learn(
                model, pinyinData.dictionary, pinyinData.vocabulary,
                old.dictionary.asSequence(), old.history.asSequence(), old.decode,
            )
        } catch (e: IOException) {
            onError(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // old data, through native converters: no failure of theirs should stop typing
            onError(IOException("cannot import libime's data", e))
        } catch (e: LinkageError) {
            onError(IOException("cannot import libime's data", e))
        }
    }

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    /** What the user set; the sessions are made again for a change, what was typed dropped. */
    var settings = EngineSettings()
        set(value) {
            if (value == field) return
            field = value
            keyboards.clear()
            sessions.clear()
            if (!value.sentenceModel) {
                sentenceModel = null
                sentenceModelTried = false
            }
        }

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true): Snapshot {
        val keyboard = keyboards[im] ?: Keyboard(session(im)).also { keyboards[im] = it }
        return keyboard.onEvent(event, arg, learning)
    }

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = sessions[im]?.candidates(from, count).orEmpty()

    private fun session(im: String): Session = sessions.getOrPut(im) {
        val s = settings
        when (im) {
            PINYIN -> PinyinSession(
                pinyinData, PinyinSegmenter(s.fuzzy, s.typos, neighbours = s.typos),
                pageSize = s.pageSize, user = user(), prediction = s.prediction, reranker = reranker(), phrases = phrases(),
            )
            SHUANGPIN -> PinyinSession(
                pinyinData, ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true,
                pageSize = s.pageSize, user = user(), prediction = s.prediction, reranker = reranker(), phrases = phrases(),
            )
            else -> {
                val method = TABLES[im] ?: throw IllegalArgumentException("no input method $im")
                val options = (s.tables[method.group]?.applyTo(method.options) ?: method.options).copy(pageSize = s.pageSize)
                val table = table(im)
                // looking a character up by pinyin learns nothing: it is not how the user writes
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(pinyinData, PinyinSegmenter(), prediction = false)
                TableSession(table.dictionary, options, lookUp, table.user)
            }
        }
    }

    /** A table and what the user taught it: both outlive the sessions made over them. */
    private class Table(val dictionary: TableDictionary, val user: TableUser, val store: TableUser.Store?)

    private val tables = HashMap<String, Table>()

    private fun table(im: String): Table = tables.getOrPut(im) {
        val dictionary = TableDictionary(CodeTable.load(load("$TABLE_DIR/${TABLES.getValue(im).file}"), verify = false))
        val user = TableUser(dictionary)
        // a log that cannot be read or moved aside: learn in memory rather than not type
        val store = userDir?.let { dir ->
            dir.mkdirs()
            try {
                TableUser.Store(File(dir, userTable(im)), user, onError).apply { open() }
            } catch (e: IOException) {
                onError(e)
                null
            }
        }
        Table(dictionary, user, store)
    }

    /** A reranker of the session's own, over the one model: each keeps what it ran for its input. */
    private fun reranker(): Reranker? = if (settings.sentenceModel) sentenceModel()?.let { Reranker(it) } else null

    /**
     * Reads [additions] again, for the user changed them: the sessions are made anew, what was
     * typed dropped. If the dictionaries changed, the user's words are read again from the log,
     * with them; with no log, what was learned is only in memory, so the new words are added to it
     * and the removed ones kept till the next start.
     */
    fun reload() {
        val before = added?.dictionaries.orEmpty()
        addedRead = false
        phrases = null
        keyboards.clear()
        sessions.clear()
        val model = userModel ?: return
        if (added()?.dictionaries.orEmpty() == before) return
        val log = store
        if (log == null) {
            addDictionaries(model)
        } else {
            log.close()
            store = null
            userModel = null
        }
    }

    override fun close() {
        // each on its own: one that fails to close must not leave the others open
        (listOfNotNull(store) + tables.values.mapNotNull { it.store }).forEach {
            try {
                it.close()
            } catch (e: IOException) {
                onError(e)
            }
        }
        // used after all the same: each reads its log again, and appends to it
        store = null
        userModel = null
        tables.clear()
        sessions.clear()
        keyboards.clear()
    }

    companion object {
        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        const val PINYIN_DATA = "engine/pinyin.data"
        const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
        const val TABLE_DIR = "engine/table"
        const val USER_PINYIN = "pinyin.user"

        /** Where what the user taught the table input method [im] is kept: `wubi.user` and so on. */
        fun userTable(im: String) = im.removePrefix("engine-") + ".user"

        /** Each table input method. */
        val TABLES: Map<String, TableMethod> = mapOf(
            "engine-wubi" to TableMethod("wbx.data", "Wubi", TableOptions.WUBI),
            "engine-cangjie" to TableMethod("cj.data", "Cangjie", TableOptions.CANGJIE),
            "engine-ziranma" to TableMethod("zrm.data", "Ziranma", TableOptions.ZIRANMA),
            "engine-erbi" to TableMethod("erbi.data", "Erbi", TableOptions.ERBI),
        )
    }
}

/**
 * A table input method: its [file] under [Engines.TABLE_DIR], the [group] of its settings in the
 * addon's config (see [EngineSettings.tables]), and how it behaves unless those say otherwise.
 */
class TableMethod(val file: String, val group: String, val options: TableOptions)
