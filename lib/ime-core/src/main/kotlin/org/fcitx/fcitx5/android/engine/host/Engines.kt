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
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.table.CodedWords
import org.fcitx.fcitx5.android.engine.table.SharedWords
import org.fcitx.fcitx5.android.engine.table.TableConf
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.TableText
import org.fcitx.fcitx5.android.engine.table.TableUser
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.KeyHabits
import org.fcitx.fcitx5.android.engine.user.UserStore
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.engine.user.WordPack
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
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
        /**
         * The word packs turned on ([WordPack]), each named (its file) with its lines and whether
         * it is merged into the new words dictionary rather than the base one; [dictionaries]
         * tells these apart too.
         */
        val packs: () -> List<Pack> = { emptyList() },
        /** The lines of the dictionaries turned on that are merged into the new words dictionary. */
        val newDictionary: () -> List<String> = { emptyList() },
        /** The lines of the dictionaries turned on that are merged into the base dictionary. */
        val dictionary: () -> List<String>,
    )

    class Pack(val name: String, val lines: Sequence<String>, val intoNew: Boolean = false)

    // the files are signed with the app: no need to read them through for their checksums
    private val pinyinData by lazy(LazyThreadSafetyMode.NONE) { PinyinData.load(load(PINYIN_DATA), verify = false) }

    // the Latin words full pinyin reads as typed in lower case (iphone for iPhone)
    private val latinWords by lazy(LazyThreadSafetyMode.NONE) { LatinWords.of(pinyinData.dictionary) }

    /**
     * A sentence model, read when first asked for; dropped when turned off, read (or tried) again
     * when turned on. A build without the file has [instead]'s, if given.
     */
    private inner class ModelFile(private val path: String, private val unpack: Boolean, private val instead: ModelFile? = null) {
        private var model: SentenceModel? = null
        private var tried = false

        fun get(): SentenceModel? {
            if (tried) return model
            tried = true
            model = try {
                SentenceModel.load(load(path), unpack, kernel ?: MatrixKernel.JVM)
            } catch (_: FileNotFoundException) {
                // a build without them (EngineDataPlugin fetches them): pinyin reads by the decoder alone
                instead?.get()
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

    // the nine keys' own pair, trained on what the decoder reads from digits: the readings it
    // weighs there differ (瘙痒症 against 盼望着你 for the same keys), and full pinyin's stay as they are
    private val t9SentenceModel = ModelFile(T9_SENTENCE_MODEL, unpack = kernel == null, instead = sentenceModel)
    private val t9RefiningModel = ModelFile(T9_REFINING_MODEL, unpack = false, instead = refiningModel)

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
    private var layerPrior: LayerPrior? = null

    /** Learned with the user model, and kept in its log; one for the life of the engine. */
    private fun prior(): LayerPrior = layerPrior ?: LayerPrior(pinyinData.layers, extra = { id -> userModel?.layerOf(id) ?: 0 }).also { layerPrior = it }

    // the dictionaries' words go in as words the user added, uncounted: scored as the model's
    // unknown word until picked, and not kept in the log unless picked
    private fun user(): UserModel = userModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary).also { model ->
        userModel = model
        if (userDir != null) {
            userDir.mkdirs()
            val file = File(userDir, USER_PINYIN)
            // a log that cannot be read or moved aside: learn in memory rather than not type
            store = try {
                UserStore(file, model, onError = onError, prior = prior(), habits = habits).apply { open(seed = ::importLegacy) }
            } catch (e: IOException) {
                onError(e)
                null
            }
        }
        addDictionaries(model)
        lists.applyTo(model)
    }

    // kept in the user model's log, read with it: asked for after user()
    private val habits = KeyHabits()

    // in the user directory with the log, in memory without one
    private val lists by lazy(LazyThreadSafetyMode.NONE) { WordLists(userDir, onError) }

    /** A word of the user's, as the settings list them; [pinyin] its syllables apart by spaces. */
    data class UserWord(val text: String, val pinyin: String, val kind: Kind, val count: Float = 0f) {
        enum class Kind {
            /** Added by hand, in the settings. */
            ADDED,

            /** Put together by the user from pieces as they typed. */
            LEARNED,

            /** Blocked, a long press on a candidate or in the settings: never offered. */
            BLOCKED,
        }
    }

    private fun Entry.word(kind: UserWord.Kind, count: Float = 0f) =
        UserWord(text, syllables.joinToString(" ") { Syllables.spelling(it) }, kind, count)

    /** The words the user added, those they made as they typed (most typed first), and those they blocked. */
    fun userWords(): List<UserWord> =
        lists.addedWords.map { it.word(UserWord.Kind.ADDED) } +
            user().ownWords().sortedByDescending { it.second }.map { (entry, count) -> entry.word(UserWord.Kind.LEARNED, count) } +
            lists.blockedWords.map { it.word(UserWord.Kind.BLOCKED) }

    /**
     * How the dictionary reads [text]: as few of its words as make it up, each read as it most
     * likely is; syllables apart by spaces, null if some character is none of its words. For
     * the user to check, as a character of several readings may be read the other way.
     */
    fun pinyinOf(text: String): String? = pinyinsOf(listOf(text))[text]

    /**
     * [pinyinOf] of each of [texts], the dictionary walked once for them all: a walk is the
     * whole of it, a thousand words imported with no pinyin would be a thousand walks.
     */
    fun pinyinsOf(texts: Collection<String>): Map<String, String?> {
        val offsets = texts.associateWith { text ->
            val chars = text.codePointCount(0, text.length)
            if (chars == 0 || chars > MAX_WORD_CHARS) null
            else IntArray(chars + 1) { if (it == chars) text.length else text.offsetByCodePoints(0, it) }
        }
        val pieces = HashSet<String>()
        for ((text, at) in offsets) {
            if (at == null) continue
            for (i in 0 until at.size - 1) for (j in i + 1 until at.size) pieces += text.substring(at[i], at[j])
        }
        val readings = LibimeImport.readings(pinyinData.dictionary, pinyinData.vocabulary, pieces)
        return offsets.mapValues { (text, at) -> at?.let { reading(text, it, readings) } }
    }

    // as few of the dictionary's words as make [text] up, apart at [offsets]
    private fun reading(text: String, offsets: IntArray, readings: Map<String, IntArray>): String? {
        val chars = offsets.size - 1
        // fewest pieces to each character, and the piece that got there
        val fewest = IntArray(chars + 1) { if (it == 0) 0 else Int.MAX_VALUE }
        val from = IntArray(chars + 1)
        for (j in 1..chars) for (i in 0 until j) {
            if (fewest[i] == Int.MAX_VALUE || text.substring(offsets[i], offsets[j]) !in readings) continue
            if (fewest[i] + 1 < fewest[j]) {
                fewest[j] = fewest[i] + 1
                from[j] = i
            }
        }
        if (fewest[chars] == Int.MAX_VALUE) return null
        val syllables = ArrayList<Int>()
        var j = chars
        while (j > 0) {
            val i = from[j]
            syllables.addAll(0, readings.getValue(text.substring(offsets[i], offsets[j])).asList())
            j = i
        }
        return syllables.joinToString(" ") { Syllables.spelling(it) }
    }

    private val textWords by lazy(LazyThreadSafetyMode.NONE) { TextWords(pinyinData.model, pinyinData.wordIndex) }

    /** Where each of [texts] splits into words, as the model finds likeliest: TextWords.boundaries. */
    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = texts.associateWith { textWords.boundaries(it) }

    /** log10 P of each of [texts] under the pinyin model (TextWords.logProb): for ranking what voice input heard. */
    fun logProbs(texts: List<String>): FloatArray = FloatArray(texts.size) { textWords.logProb(texts[it]) }

    /** Adds [text] read as [pinyin] (see [WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean {
        val entry = WordLists.entry(text, pinyin) ?: return false
        add(listOf(entry))
        return true
    }

    /** Adds [entries]; how many were not there already. */
    private fun add(entries: List<Entry>): Int {
        // unblocked, as the user asks for them
        blockedTexts = null
        lists.unblockAll(entries).forEach { userModel?.unblock(it) }
        val added = lists.addAll(entries)
        added.forEach { userModel?.list(it) }
        sessions.clear()
        keyboards.clear()
        return added.size
    }

    /** Blocks [text] read as [pinyin] from the settings; false if it does not read so. */
    fun blockWord(text: String, pinyin: String): Boolean {
        val entry = WordLists.entry(text, pinyin) ?: return false
        blockAll(listOf(entry))
        sessions.clear()
        keyboards.clear()
        return true
    }

    // from a long press too, the session that asked going on with its input: not dropped, as the
    // host fetches its candidates next; the others read the model as they decode
    private fun block(entry: Entry) = blockAll(listOf(entry))

    /** Blocks [entries]; how many were not already. */
    private fun blockAll(entries: List<Entry>): Int {
        blockedTexts = null
        val blocked = lists.blockAll(entries)
        val model = user()
        entries.forEach { model.block(it) }
        habits.forgetAll(entries.mapTo(HashSet()) { it.text })
        return blocked.size
    }

    /** Takes [word] off its list: an added word is no longer typeable, a learned one forgotten, a blocked one offered again. */
    fun removeWord(word: UserWord) = removeWords(listOf(word))

    /** [removeWord] for each of [words], a list written once. */
    fun removeWords(words: Collection<UserWord>) {
        val byKind = words.groupBy({ it.kind }, { it.entry() })
        byKind[UserWord.Kind.ADDED]?.let { if (lists.removeAll(it.filterNotNull()).isNotEmpty()) dropUser() }
        byKind[UserWord.Kind.LEARNED]?.let { learned ->
            user().forget(learned.filterNotNull())
            habits.forgetAll(learned.filterNotNull().mapTo(HashSet()) { it.text })
            forgetInTables(learned.filterNotNull().map { it.text })
        }
        byKind[UserWord.Kind.BLOCKED]?.let { entries ->
            blockedTexts = null
            lists.unblockAll(entries.filterNotNull()).forEach { userModel?.unblock(it) }
        }
        sessions.clear()
        keyboards.clear()
    }

    // as listed: one syllable a space, so read back as it was, not split again
    private fun UserWord.entry() = LibimeImport.entry(text, pinyin.replace(' ', '\''))

    /** What [importWords] made of the lines: words added and blocked that were not already, lines it could not read. */
    data class Imported(val added: Int, val blocked: Int, val unread: Int)

    /**
     * The words of a list the user imports, a word a line (see [WordLists.line]): added, or
     * blocked where marked so; one with no pinyin read as the dictionary reads it ([pinyinOf]).
     */
    fun importWords(lines: Sequence<String>): Imported {
        val add = LinkedHashSet<Entry>()
        val block = LinkedHashSet<Entry>()
        var unread = 0
        val read = lines.mapNotNull { WordLists.line(it) }.toList()
        val guessed = pinyinsOf(read.filter { it.pinyin == null && it.text.isNotEmpty() }.map { it.text })
        for (line in read) {
            val pinyin = line.pinyin ?: guessed[line.text]
            val entry = pinyin?.let { WordLists.entry(line.text, it) }
            when {
                entry == null -> unread++
                line.blocked -> block += entry
                else -> add += entry
            }
        }
        // a word the file has both ways: blocked, the safer of the two
        add -= block
        val added = if (add.isEmpty()) 0 else add(add.toList())
        var blocked = 0
        if (block.isNotEmpty()) {
            blocked = blockAll(block.toList())
            sessions.clear()
            keyboards.clear()
        }
        return Imported(added, blocked, unread)
    }

    /**
     * The user's words as [importWords] reads them back: the added and the learned (most typed
     * first) as `幽默 you'mo`, the blocked with a `!` before them.
     */
    fun exportWords(): List<String> = userWords().map {
        val line = "${it.text} ${it.pinyin.replace(' ', '\'')}"
        if (it.kind == UserWord.Kind.BLOCKED) "!$line" else line
    }

    /**
     * Makes the user's words again from the log, the dictionaries and the lists: a word listed
     * cannot be taken out of the model, only left out of the next one. With no log, the model
     * is all there is of what was learned, so it is kept, the word typeable till the next start.
     */
    private fun dropUser() {
        val log = store ?: return
        closeQuietly(log)
        store = null
        userModel = null
    }

    private fun addDictionaries(model: UserModel) {
        val lines = try {
            added()?.dictionary?.invoke().orEmpty()
        } catch (e: IOException) {
            onError(e)
            emptyList()
        }
        for (line in lines) LibimeImport.dictionaryEntry(line)?.let { model.list(it) }
        val modelWords = pinyinData.model.vocabularySize
        val newLayer = prior().layer(WordLayers.NEW)
        val newLines = try {
            added()?.newDictionary?.invoke().orEmpty()
        } catch (e: IOException) {
            onError(e)
            emptyList()
        }
        // a dictionary has no scores of its own: its words the model lacks are scored as the new
        // words dictionary's typical one, and weighed with it
        for (line in newLines) LibimeImport.dictionaryEntry(line)?.let { model.list(it, NEW_WORD_SCORE, newLayer, modelWords) }
        val packs = try {
            added()?.packs?.invoke().orEmpty()
        } catch (e: IOException) {
            onError(e)
            emptyList()
        }
        for (imported in packs) {
            // one that does not read is left out, the rest still go in
            val pack = try {
                WordPack.parse(imported.lines, imported.name)
            } catch (e: SourceException) {
                onError(IOException(e.message, e))
                continue
            }
            // merged into the layer the user put it in, whatever layer the pack names: one weight
            // for the new words, however many packs they came in
            val layer = if (imported.intoNew) newLayer else 0
            pack.words.forEach { model.list(it.entry, it.score, layer, modelWords) }
        }
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
                t9SentenceModel.drop()
                t9RefiningModel.drop()
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
            PINYIN -> StrokeLookup(
                PinyinSession(
                    pinyinData, PinyinSegmenter(s.fuzzy, s.typos, neighbours = s.typos, latin = if (s.latinWords) latinWords else null),
                    pageSize = s.pageSize, user = user(), prediction = s.prediction, prior = prior(), phraseBook = phrases(),
                    reranker = reranker(), refiner = refiner(), block = ::block, habits = habits.scope(PINYIN),
                ),
                ::strokes, ::charReading, s.pageSize, blocked = ::isBlocked,
            )
            // 简拼 only where the digits read nothing else: as common on the evaluation set, and faster
            T9 -> PinyinSession(
                pinyinData, T9Segmenter(s.fuzzy, abbreviations = false), spell = true,
                pageSize = s.pageSize, user = user(), prediction = s.prediction, prior = prior(), phraseBook = phrases(),
                reranker = reranker(t9SentenceModel), refiner = refiner(t9RefiningModel), block = ::block, habits = habits.scope(T9),
            )
            SHUANGPIN -> PinyinSession(
                pinyinData, ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true,
                pageSize = s.pageSize, user = user(), prediction = s.prediction, prior = prior(), phraseBook = phrases(),
                reranker = reranker(), refiner = refiner(), block = ::block, habits = habits.scope("$SHUANGPIN/${s.shuangpin}"),
            )
            else -> {
                val method = TABLES[im]
                val table = if (method == null) addedTable(im) else table(im)
                val own = method?.let { s.tables[it.group]?.applyTo(it.options) ?: it.options } ?: checkNotNull(table.options)
                val options = own.copy(pageSize = s.pageSize)
                // looking a character up by pinyin learns nothing: it is not how the user writes
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(pinyinData, PinyinSegmenter(), prediction = false)
                TableSession(table.dictionary, options, lookUp, table.user, shared(table, im))
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
    ) {
        var shared: SharedWords? = null

        // the user's words coded for this table: those of their dictionaries, made once a user
        // model; and those they made or typed, as of its changes then
        var listed: CodedWords? = null
        var own: CodedWords? = null
        var codedFor: UserModel? = null
        var listedAt = -1
        var ownAt = -1
    }

    /**
     * One user lexicon for every input method: [table] offers the words the user made, added or
     * typed with pinyin, coded by its rules, as of now; the phrases it saves become pinyin's
     * words (those saved before this, once); what is blocked or forgotten anywhere is so here.
     */
    private fun shared(table: Table, im: String): SharedWords = table.shared ?: object : SharedWords {
        override fun words(prefix: String) = coded().flatMap { it.words(prefix) }
        override fun leadsAnywhere(prefix: String) = coded().any { it.leadsAnywhere(prefix) }
        override fun blocked(text: String) = isBlockedWord(text)
        override fun blockable(text: String) = text.codePointCount(0, text.length) >= 2 && pinyinOf(text) != null
        override fun block(text: String) = blockText(text)
        override fun forget(text: String) = forgetEverywhere(text)

        private fun coded(): List<CodedWords> {
            val model = user()
            if (table.codedFor !== model) {
                table.listed = null
                table.own = null
                table.codedFor = model
            }
            val listed = table.listed?.takeIf { table.listedAt == model.listings }
                ?: CodedWords(table.dictionary, model.listedTexts()).also {
                    table.listed = it
                    table.listedAt = model.listings
                }
            val own = table.own?.takeIf { table.ownAt == model.changes }
                ?: CodedWords(table.dictionary, model.ownTexts()).also {
                    table.own = it
                    table.ownAt = model.changes
                }
            return listOf(own, listed)
        }
    }.also { shared ->
        table.shared = shared
        if (table.learns) {
            table.user.onSaved = { learnSaved(listOf(it), again = true) }
            tellPinyinOnce(table, im)
        }
    }

    /**
     * What [table] saved before pinyin heard of its phrases, told it once: a phrase the user took
     * out of pinyin's words later is not put back.
     */
    private fun tellPinyinOnce(table: Table, im: String) {
        val log = if (im in TABLES) userTable(im) else addedTableFiles(im).last()
        val told = userDir?.let { File(it, log + TOLD) }
        if (told?.exists() == true) return
        learnSaved(table.user.savedTexts(), again = false)
        try {
            told?.createNewFile()
        } catch (_: IOException) {
            // told again next time, harmlessly: pinyin learns only those it lacks; where the log
            // cannot be kept either, that is reported already
        }
    }

    /** Phrases a table saved, as pinyin's words: learned once more [again], else only those pinyin lacks. */
    private fun learnSaved(texts: Collection<String>, again: Boolean) {
        if (texts.isEmpty()) return
        val model = user()
        for ((text, reading) in pinyinsOf(texts)) {
            val entry = reading?.let { LibimeImport.entry(text, it.replace(' ', '\'')) } ?: continue
            if (again || !model.knows(entry)) model.learn(null, listOf(entry))
        }
    }

    /** Forgets [text] in pinyin, however read, and in every table that saved it. */
    private fun forgetEverywhere(text: String) {
        user().forgetText(text)
        habits.forgetAll(setOf(text))
        forgetInTables(listOf(text))
    }

    // the tables loaded, and those with a log on disk: a phrase saved there would be shared again
    private fun forgetInTables(texts: Collection<String>) {
        val logged = TABLES.keys.filter { im -> im in tables || userDir?.let { File(it, userTable(im)).exists() } == true }
        for (im in logged) {
            val user = table(im).user
            texts.forEach(user::forgetText)
        }
        for ((im, table) in tables) if (im !in TABLES) texts.forEach(table.user::forgetText)
    }

    // the texts blocked, of any reading: a table has no readings to tell apart
    private var blockedTexts: Set<String>? = null

    private fun blockedTexts(): Set<String> = blockedTexts ?: lists.blockedWords.mapTo(HashSet()) { it.text }.also { blockedTexts = it }

    /** A character blocked in pinyin, for its strokes: found by strokes, it is the character, any reading. */
    private fun isBlocked(text: String): Boolean = text in blockedTexts()

    /**
     * A word blocked, for a table. Not a character: blocked in pinyin under one reading (了 liao),
     * a table, which has no readings, would lose it under all.
     */
    private fun isBlockedWord(text: String): Boolean = text.codePointCount(0, text.length) >= 2 && text in blockedTexts()

    /** Blocks [text] from a table's candidate, as the dictionary reads it and as the user typed it: false if it reads none. */
    private fun blockText(text: String): Boolean {
        val read = pinyinOf(text)?.let { LibimeImport.entry(text, it.replace(' ', '\'')) }
        val entries = (listOfNotNull(read) + user().entriesOf(text)).distinct()
        if (entries.isEmpty()) return false
        blockAll(entries)
        return true
    }

    private val tables = HashMap<String, Table>()

    // null till asked for; an app without the data has no lookup
    private var strokeTable: Strokes? = null
    private var strokesTried = false

    private fun strokes(): Strokes? {
        if (!strokesTried) {
            strokesTried = true
            strokeTable = try {
                val words = pinyinData.wordIndex
                val model = pinyinData.model
                Strokes(CodeTable.load(load(STROKE_DATA), verify = false)) { c ->
                    val id = words.find(c)
                    if (id < 0) Float.NEGATIVE_INFINITY else model.score(id)
                }
            } catch (_: FileNotFoundException) {
                null
            } catch (e: IOException) {
                onError(e)
                null
            } catch (e: DataFormatException) {
                onError(IOException(e))
                null
            }
        }
        return strokeTable
    }

    // a character to its readings, the likeliest first, for the stroke lookup to show
    private val charReadings: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        val dictionary = pinyinData.dictionary
        val vocabulary = pinyinData.vocabulary
        val readings = HashMap<String, MutableList<Pair<Float, String>>>()
        val first = dictionary.firstChild(dictionary.root)
        for (node in first until first + dictionary.childCount(dictionary.root)) {
            for (i in 0 until dictionary.wordCount(node)) {
                val word = dictionary.word(node, i)
                if (vocabulary.length(word) != 1) continue
                readings.getOrPut(vocabulary.word(word)) { ArrayList() } +=
                    dictionary.weight(node, i) to Syllables.spelling(dictionary.syllable(node))
            }
        }
        readings.mapValues { (_, r) ->
            r.sortedByDescending { it.first }.map { it.second }.distinct().take(MAX_READINGS).joinToString("/")
        }
    }

    private fun charReading(c: String): String = charReadings[c].orEmpty()

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
    private fun reranker(model: ModelFile = sentenceModel): Reranker? = if (settings.sentenceModel) model.get()?.let { Reranker(it) } else null

    /** As [reranker], over the larger model, which weighs the readings again while the user pauses. */
    private fun refiner(model: ModelFile = refiningModel): SentenceRefiner? = if (settings.sentenceModel) LateRefiner(model) else null

    /**
     * The larger model is read at the first pause, not when a session is made: 26 MB copied out
     * of the asset on the fcitx thread would hold up the switch to pinyin. Unreadable, it has
     * nothing to say, and the session stops asking.
     */
    private inner class LateRefiner(private val model: ModelFile) : SentenceRefiner {
        private var reranker: Reranker? = null

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            val r = reranker ?: model.get()?.let { Reranker(it, limit = Reranker.REFINE_LIMIT) }?.also { reranker = it }
                ?: return SentenceRefiner.NONE
            // null is not done yet, more slices to come: not NONE, which would end it after the first
            return r.refine(context, readings, scores, budget)
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
        if (store == null) {
            addDictionaries(model)
            lists.applyTo(model)
        } else {
            // read again from the log all the same: what did not reach it is lost either way
            dropUser()
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
        // longer is a sentence, not a word: and the dictionary is walked as deep
        private const val MAX_WORD_CHARS = 8

        // the median score of the new words dictionary's 78 thousand words (words-202610): what a
        // word of a dictionary merged into it, which has no score, is taken to be
        const val NEW_WORD_SCORE = -6.2f

        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        /** Pinyin on a phone's nine keys (九键). */
        const val T9 = "engine-t9"

        const val PINYIN_DATA = "engine/pinyin.data"

        /** Characters by their strokes (rime-stroke), for pinyin's `u` lookup; read if the app has it. */
        const val STROKE_DATA = "engine/stroke.data"
        private const val MAX_READINGS = 3
        /** Read if the app has one; without it the engine types by the decoder alone. */
        const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
        /** Six times the work of [SENTENCE_MODEL], and right more often: see [PinyinSession]'s refiner. */
        const val REFINING_MODEL = "engine/sentence-model-large.safetensors"
        /** The nine keys' [SENTENCE_MODEL] and [REFINING_MODEL]; the general ones where a build has none. */
        const val T9_SENTENCE_MODEL = "engine/sentence-model-t9.safetensors"
        const val T9_REFINING_MODEL = "engine/sentence-model-t9-large.safetensors"
        const val TABLE_DIR = "engine/table"
        const val USER_PINYIN = "pinyin.user"

        /** Beside a table's log: its phrases saved before were told to pinyin (see shared). */
        const val TOLD = ".told"

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
        )
    }
}

/**
 * A table input method: its [file] under [Engines.TABLE_DIR], the [group] of its settings in the
 * addon's config (see [EngineSettings.tables]), and how it behaves unless those say otherwise.
 */
class TableMethod(val file: String, val group: String, val options: TableOptions)
