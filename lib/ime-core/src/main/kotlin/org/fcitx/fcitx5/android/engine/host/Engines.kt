/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.TableConf
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.TableText
import org.fcitx.fcitx5.android.engine.table.TableUser
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserStore
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The input methods the androidengine addon lists, by name, each with its session made on first
 * use. [load] gives a data file by its path in the app's assets; what the user picks, in pinyin
 * and in each table, is kept under [userDir] (in memory only if null). When pinyin's log is made
 * afresh, what libime's pinyin learned ([legacy], if the user had it) is read into it first. What the user added to
 * pinyin ([additions]) is read at first need, and again on [reload]; so are the table input methods
 * they added ([userTables]), asked for by a name the engine has none of. The sentence models'
 * int8 weights are multiplied by [kernel], if given (in C++, say); if not, by Kotlin.
 *
 * Everything here runs on one thread, the one fcitx runs on.
 */
class Engines(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit = {},
    private val legacy: () -> LibimeImport.Legacy? = { null },
    private val additions: () -> Additions? = { null },
    private val userTables: (String) -> UserTable? = { null },
    private val kernel: MatrixKernel? = null,
) : Closeable {

    /**
     * A table input method the user added, as fcitx's table addon had it: its `.conf` ([TableConf])
     * with what the user set of it over it ([settings]), and of the table file it names, the [text]
     * ([CodeTableReader]), read only to build the table, which is kept built under the user
     * directory until the file's [stamp] (its size and time, say) changes.
     */
    class UserTable(
        val conf: String,
        val stamp: (file: String) -> String,
        val text: (file: String) -> BufferedReader,
        val settings: String = "",
    )

    /**
     * What the user added to pinyin, as libime kept it: its custom phrases file, and the lines
     * of its dictionaries turned on, in libime's text format (`你好 ni'hao 0`), read only when
     * the user's words are made: they can be many, and are kept in the model, not here.
     * [dictionaries] tells the dictionaries apart (their names, sizes and times, say): read again
     * on [reload] only if it changed. The custom phrases the user changes from the keyboard
     * ([PhraseBook]) are handed to [savePhrases], all of them.
     */
    class Additions(
        val phrases: String,
        val dictionaries: String,
        val savePhrases: (CustomPhrases) -> Unit = {},
        val dictionary: () -> List<String>,
    )

    // the files are signed with the app: no need to read them through for their checksums
    private val pinyinData by lazy(LazyThreadSafetyMode.NONE) { PinyinData.load(load(PINYIN_DATA), verify = false) }

    /** A sentence model, read when first asked for; dropped when turned off, read (or tried) again when turned on. */
    private inner class ModelFile(private val path: String, private val unpack: Boolean) {
        private var model: SentenceModel? = null
        private var tried = false

        fun get(): SentenceModel? {
            if (tried) return model
            tried = true
            model = try {
                SentenceModel.load(load(path), unpack, kernel ?: MatrixKernel.JVM)
            } catch (e: IOException) {
                onError(e)
                null
            } catch (e: IllegalArgumentException) {
                // pinyin reads as well without it as before it was added
                onError(IOException("cannot read $path", e))
                null
            }
            return model
        }

        fun drop() {
            model = null
            tried = false
        }
    }

    // in Kotlin, floats rather than int8 as stored: 17 MB rather than 4.5, and on ART two and a
    // half times faster; a kernel is faster still on int8
    private val sentenceModel = ModelFile(SENTENCE_MODEL, unpack = kernel == null)
    // int8 as stored: as floats it would be 100 MB, half of what an app may take on some phones
    private val refiningModel = ModelFile(REFINING_MODEL, unpack = false)

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

    private var phrases: PhraseBook? = null

    private fun phrases(): PhraseBook = phrases ?: PhraseBook(CustomPhrases.parse(added()?.phrases.orEmpty())) {
        try {
            added?.savePhrases?.invoke(it)
        } catch (e: IOException) {
            // kept in memory: what the user sees stays as they asked, till the next start
            onError(e)
        }
    }.also { phrases = it }

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
        for (line in lines) LibimeImport.dictionaryEntry(line)?.let { model.list(it) }
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
            // old data, however it came to be: no failure reading it should stop typing
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
                sentenceModel.drop()
                refiningModel.drop()
            }
        }

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning], nor by a table that does not learn. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true): Snapshot {
        val keyboard = keyboards[im] ?: Keyboard(session(im)).also { keyboards[im] = it }
        return keyboard.onEvent(event, arg, learning && tables[im]?.learns != false)
    }

    /**
     * [before] is the text before the cursor, put where the engines did not: see
     * [Keyboard.context]. The host passes it only where the user learns, so each learns as its
     * [onEvent] would.
     */
    fun context(before: String) = keyboards.forEach { (im, keyboard) ->
        keyboard.context(before, tables[im]?.learns != false)
    }

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = sessions[im]?.candidates(from, count).orEmpty()

    /** What a long press on the [index]th of [im]'s candidates offers; nothing if it has no session yet. */
    fun offers(im: String, index: Int): Set<Offer> = sessions[im]?.offers(index).orEmpty()

    private fun session(im: String): Session = sessions.getOrPut(im) {
        val s = settings
        when (im) {
            PINYIN -> PinyinSession(
                pinyinData, PinyinSegmenter(s.fuzzy, s.typos, neighbours = s.typos),
                pageSize = s.pageSize, user = user(), prediction = s.prediction, phraseBook = phrases(),
                reranker = reranker(), refiner = refiner(),
            )
            SHUANGPIN -> PinyinSession(
                pinyinData, ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true,
                pageSize = s.pageSize, user = user(), prediction = s.prediction, phraseBook = phrases(),
                reranker = reranker(), refiner = refiner(),
            )
            else -> {
                val method = TABLES[im]
                val table = if (method == null) addedTable(im) else table(im)
                val own = method?.let { s.tables[it.group]?.applyTo(it.options) ?: it.options } ?: checkNotNull(table.options)
                val options = own.copy(pageSize = s.pageSize)
                // looking a character up by pinyin learns nothing: it is not how the user writes
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(pinyinData, PinyinSegmenter(), prediction = false)
                TableSession(table.dictionary, options, lookUp, table.user)
            }
        }
    }

    /**
     * A table and what the user taught it: both outlive the sessions made over them. [options]:
     * those of a table the user added, as its `.conf` has them; null for one of [TABLES]. Not
     * [learns], as libime's table with Learning off, it keeps nothing, not even till the next start.
     */
    private class Table(
        val dictionary: TableDictionary,
        val user: TableUser,
        val store: TableUser.Store?,
        val options: TableOptions? = null,
        val learns: Boolean = true,
    )

    private val tables = HashMap<String, Table>()

    private fun table(im: String): Table = tables.getOrPut(im) {
        val dictionary = TableDictionary(CodeTable.load(load("$TABLE_DIR/${TABLES.getValue(im).file}"), verify = false))
        val user = TableUser(dictionary)
        Table(dictionary, user, userDir?.let { openStore(File(it, userTable(im)), user) })
    }

    // a log that cannot be read or moved aside: learn in memory rather than not type
    private fun openStore(file: File, user: TableUser): TableUser.Store? {
        file.parentFile?.mkdirs()
        return try {
            TableUser.Store(file, user, onError).apply { open() }
        } catch (e: IOException) {
            onError(e)
            null
        }
    }

    // added tables that failed, not tried again (on every key) until reload
    private val failed = HashSet<String>()

    /** A table the user added, built from its text unless built already; what fails is reported. */
    private fun addedTable(im: String): Table = tables.getOrPut(im) {
        require(im !in failed) { "input method $im failed to load" }
        // until it loads: not tried again on every key
        failed += im
        val table = try {
            val added = userTables(im) ?: throw IllegalArgumentException("no input method $im")
            val conf = TableConf.parse(added.conf, added.settings)
            val dictionary = TableDictionary(built(im, conf.file, added))
            val user = TableUser(dictionary)
            val store = if (conf.learning) userDir?.let { openStore(File(it, addedTableFiles(im).last()), user) } else null
            Table(dictionary, user, store, conf.options, conf.learning)
        } catch (e: IOException) {
            throw unreadable(im, e)
        } catch (e: SourceException) {
            throw unreadable(im, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // a table file from anywhere (DataFormatException, say): no failure should go unsaid
            throw unreadable(im, e)
        }
        failed -= im
        table
    }

    private fun unreadable(im: String, e: Throwable): IllegalArgumentException {
        onError(e as? IOException ?: IOException("cannot read input method $im", e))
        return IllegalArgumentException("cannot read input method $im", e)
    }

    /**
     * [added]'s table: as built before if its stamp is the same, else built from its text and
     * kept for next time. One that cannot be kept is built again at the next start.
     */
    private fun built(im: String, file: String, added: UserTable): CodeTable {
        // built again when the file changes, or how its text is read
        val stamp = "$BUILD $file ${added.stamp(file)}"
        val cache = userDir?.let { File(it, addedTableFiles(im).first()) }
        if (cache != null && cache.isFile) {
            try {
                val table = CodeTable.load(map(cache))
                if (table.header[STAMP] == stamp) return table
            } catch (e: DataFormatException) {
                // cut short by a kill, or by a build that writes them otherwise: built again
                onError(IOException("cannot read $cache", e))
            } catch (e: IOException) {
                onError(e)
            }
        }
        val reader = CodeTableReader()
        added.text(file).use { reader.read(it, file) }
        TableText.codePhrases(reader)
        reader.builder.header(STAMP, stamp)
        val bytes = reader.builder.build().toByteArray()
        if (cache != null) {
            try {
                cache.parentFile?.mkdirs()
                val next = File(cache.path + ".new")
                next.writeBytes(bytes)
                if (!next.renameTo(cache)) throw IOException("cannot replace $cache")
                // mapped, as at the next start: pages the OS may drop, not a heap the size of the table
                return CodeTable.load(map(cache), verify = false)
            } catch (e: IOException) {
                onError(e)
            }
        }
        return CodeTable.load(ByteBuffer.wrap(bytes), verify = false)
    }

    private fun map(file: File): ByteBuffer = RandomAccessFile(file, "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }

    /** A reranker of the session's own, over the one model: each keeps what it ran for its input. */
    private fun reranker(): Reranker? = if (settings.sentenceModel) sentenceModel.get()?.let { Reranker(it) } else null

    /** As [reranker], over the larger model, which weighs the readings again while the user pauses. */
    private fun refiner(): SentenceRefiner? = if (settings.sentenceModel) LateRefiner() else null

    /**
     * The larger model is read at the first pause, not when a session is made: 26 MB copied out
     * of the asset on the fcitx thread would hold up the switch to pinyin. Unreadable, it has
     * nothing to say, and the session stops asking.
     */
    private inner class LateRefiner : SentenceRefiner {
        private var reranker: Reranker? = null

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            val r = reranker ?: refiningModel.get()?.let { Reranker(it, limit = Reranker.REFINE_LIMIT) }?.also { reranker = it }
            return r?.refine(context, readings, scores, budget) ?: SentenceRefiner.NONE
        }
    }

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
        // the tables the user added are asked for again: built again only if they changed
        failed.clear()
        for (im in tables.keys.filter { it !in TABLES }) tables.remove(im)?.store?.let(::closeQuietly)
        val model = userModel ?: return
        if (added()?.dictionaries.orEmpty() == before) return
        val log = store
        if (log == null) {
            addDictionaries(model)
        } else {
            // read again from the log all the same: what did not reach it is lost either way
            closeQuietly(log)
            store = null
            userModel = null
        }
    }

    override fun close() {
        // each on its own: one that fails to close must not leave the others open
        (listOfNotNull(store) + tables.values.mapNotNull { it.store }).forEach(::closeQuietly)
        // used after all the same: each reads its log again, and appends to it
        store = null
        userModel = null
        tables.clear()
        sessions.clear()
        keyboards.clear()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            onError(e)
        }
    }

    companion object {
        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        const val PINYIN_DATA = "engine/pinyin.data"
        const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
        /** Six times the work of [SENTENCE_MODEL], and right more often: see [PinyinSession]'s refiner. */
        const val REFINING_MODEL = "engine/sentence-model-large.safetensors"
        const val TABLE_DIR = "engine/table"
        const val USER_PINYIN = "pinyin.user"

        /** Under the user directory: the tables the user added, built, and what each learned. */
        const val USER_TABLES = "tables"

        // the header key a built table keeps its source's stamp under
        private const val STAMP = "androidengine.stamp"

        // raised when a table's text is read otherwise (CodeTableReader, TableText): built again
        private const val BUILD = 1

        /**
         * Under the user directory, what is kept of a table the user added: the table built, and
         * what it learned. Gone with the table, lest another of its name find them.
         */
        fun addedTableFiles(im: String): List<String> = listOf("$USER_TABLES/$im.table", "$USER_TABLES/$im.user")

        /** Where what the user taught the table input method [im] is kept: `wubi.user` and so on. */
        fun userTable(im: String) = im.removePrefix("engine-") + ".user"

        /** Each table input method. */
        val TABLES: Map<String, TableMethod> = mapOf(
            "engine-wubi" to TableMethod("wbx.data", "Wubi", TableOptions.WUBI),
            "engine-cangjie" to TableMethod("cj.data", "Cangjie", TableOptions.CANGJIE),
            "engine-ziranma" to TableMethod("zrm.data", "Ziranma", TableOptions.ZIRANMA),
            "engine-erbi" to TableMethod("erbi.data", "Erbi", TableOptions.ERBI),
            "engine-wubipinyin" to TableMethod("wbpy.data", "WubiPinyin", TableOptions.WUBI_PINYIN),
            "engine-dianbao" to TableMethod("db.data", "Dianbaoma", TableOptions.DIANBAO),
            "engine-bingchan" to TableMethod("qxm.data", "Bingchan", TableOptions.BINGCHAN),
            "engine-wanfeng" to TableMethod("wanfeng.data", "Wanfeng", TableOptions.WANFENG),
        )
    }
}

/**
 * A table input method: its [file] under [Engines.TABLE_DIR], the [group] of its settings in the
 * addon's config (see [EngineSettings.tables]), and how it behaves unless those say otherwise.
 */
class TableMethod(val file: String, val group: String, val options: TableOptions)
