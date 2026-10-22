/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.Engines.Additions
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.USER_PINYIN
import org.fcitx.fcitx5.android.engine.host.Engines.Imported
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.ImportFiles
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.File
import java.io.IOException

/**
 * The user's words, for [Engines]: what they added to pinyin ([additions]: custom phrases,
 * dictionaries), the decoder's lexicon of them ([user]: their own words and counts as the learning
 * record has them, put in by [attach]), its layer prior, and the words they added and blocked by
 * hand ([WordLists]). It lists, adds, blocks, imports, exports and forgets those words for the
 * settings, blocks and forgets them for a table's candidate, and owns what a table saved; another
 * input method's list it imports goes to the learning record as a batch of imported words
 * ([imports], U-101). A word
 * forgotten here is forgotten in the tables too ([forgetInTables]) and in the learning record
 * ([forgotten]); a change the sessions must see drops them ([dropSessions]). An older build's log
 * under [userDir] (made afresh from libime's, [legacy]) is only read, to be converted
 * ([legacyModel]). Run on the fcitx thread, as [Engines] is.
 */
internal class UserLexicon(
    private val userDir: File?,
    private val onError: (IOException) -> Unit,
    private val legacy: () -> LibimeImport.Legacy?,
    private val additions: () -> Additions?,
    private val assets: EngineAssets,
    private val forgetInTables: (Collection<String>) -> Unit,
    private val dropSessions: () -> Unit,
    // the texts the model forgot, whatever the tables do: for the learning record (review-1a-app S3)
    private val forgotten: (Collection<String>) -> Unit = {},
    // the user's own words and the counts put into a model made, as the learning record has them
    private val attach: (UserModel) -> Unit = {},
    // the texts blocked: unpinned wherever pinned, the block winning (impl/design-1b.md §3)
    private val blocked: (Collection<String>) -> Unit = {},
    // words made the user's own in the learning record, where they are not yet, each with the uses it
    // comes with (an export's count): how many were not
    private val owned: (Map<Entry, Float>) -> Int = { 0 },
    // a list's words written as a batch of imported words (U-101), of the file named so, but those
    // the predicate says are words already: how many were
    private val imports: (String, List<ImportedWords.Incoming>, (String, String) -> Boolean) -> Int = { _, _, _ -> 0 },
) {

    private val pinyinData get() = assets.pinyinData

    private var userAdditions: Additions? = null
    private var addedRead = false
    // additions() threw as last read: which dictionaries the user has is not known
    private var addedFailed = false

    private fun added(): Additions? {
        if (addedRead) return userAdditions
        addedRead = true
        addedFailed = false
        userAdditions = try {
            additions()
        } catch (e: IOException) {
            addedFailed = true
            onError(e)
            null
        }
        return userAdditions
    }

    private var phrases: PhraseBook? = null

    fun phrases(): PhraseBook = phrases ?: PhraseBook(CustomPhrases.parse(added()?.phrases.orEmpty()), CustomPhrases.BUILT_IN) {
        try {
            userAdditions?.savePhrases?.invoke(it)
        } catch (e: IOException) {
            // kept in memory: what the user sees stays as they asked, till the next start
            onError(e)
        }
    }.also { phrases = it }

    private var userModel: UserModel? = null
    private var layerPrior: LayerPrior? = null

    /** The prior per layer, one for the life of the engine: no longer learned, nor read from an older build's log (impl/design-1b.md §4). */
    fun prior(): LayerPrior = layerPrior ?: LayerPrior(pinyinData.layers, extra = packLayers).also { layerPrior = it }

    // the model of the moment's, as the prior outlives it
    private val packLayers = object : LayerPrior.PackLayers {
        override fun layerOf(word: Int) = userModel?.layerOf(word) ?: -1
        override fun modelWord(word: Int) = userModel?.modelWord(word) ?: word
    }

    // the dictionaries' words go in listed, uncounted: scored as the model's unknown word until
    // the user owns them. The words added by hand are the user's choice: scored as the model's word
    // of their text from the start (UserModel.modelWord)
    fun user(): UserModel = userModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, index = pinyinData.wordIndex).also { model ->
        userModel = model
        newlyListed.clear()
        addDictionaries(model)
        lists.applyTo(model)
        attach(model)
    }

    /**
     * The model an older build kept in [USER_PINYIN] (libime's, if it has none or one with nothing
     * learned in it, as that build's start filled it), read and never written, with the user's
     * dictionaries and lists as [user] has them: what converting it makes the user's
     * (LegacyConversion). Null with no user directory, or nothing to convert (the dictionaries not
     * read for none).
     *
     * [file] another than the user's, an older build's in a settings backup restored, has none of
     * libime's read in its place: that is the phone's.
     *
     * @throws IOException if libime's files cannot be read: the conversion is tried again next time
     */
    @Throws(IOException::class)
    fun legacyModel(file: File? = null): UserModel? {
        val dir = userDir ?: return null
        val model = UserModel(pinyinData.dictionary, pinyinData.vocabulary, index = pinyinData.wordIndex)
        val log = file ?: File(dir, USER_PINYIN)
        // one cut short of its header (killed as 0.3.0 first wrote it, a full disk) is as none, as the
        // tables' and English's logs are: not a failure owed to every start
        val bytes = if (log.isFile) log.readBytes() else null
        val read = if (bytes != null && UserLog.hasHeader(bytes)) UserLog.read(bytes, model) else 0
        if (read <= UserLog.header().size && (file != null || !importLegacy(model))) return null
        addDictionaries(model)
        lists.applyTo(model)
        return model
    }

    // in the user directory with the log, in memory without one
    private val lists by lazy(LazyThreadSafetyMode.NONE) { WordLists(userDir, onError) }

    private fun Entry.word(kind: UserWord.Kind, count: Float = 0f) =
        UserWord(text, syllables.joinToString(" ") { Syllables.spelling(it) }, kind, count)

    fun userWords(): List<UserWord> = UserWord.Kind.entries.flatMap { userWords(it) }

    fun userWords(kind: UserWord.Kind): List<UserWord> = when (kind) {
        UserWord.Kind.ADDED -> lists.addedWords.map { it.word(kind) }
        UserWord.Kind.LEARNED -> user().ownWords().sortedByDescending { it.second }.map { (entry, count) -> entry.word(kind, count) }
        UserWord.Kind.BLOCKED -> lists.blockedWords.map { it.word(kind) }
    }

    fun userWordCounts(): Map<UserWord.Kind, Int> = mapOf(
        UserWord.Kind.ADDED to lists.addedWords.size,
        UserWord.Kind.LEARNED to user().ownWords().size,
        UserWord.Kind.BLOCKED to lists.blockedWords.size,
    )

    /**
     * The texts of the words added by hand, in the list's order, which no session holds back as
     * coarse (listed, they have no count to say they are the user's), ranked after the user's formed words.
     */
    fun addedTexts(): Set<String> = lists.addedWords.mapTo(LinkedHashSet()) { it.text }

    fun addWord(text: String, pinyin: String): Boolean {
        val entry = WordLists.entry(text, pinyin) ?: return false
        add(listOf(entry))
        return true
    }

    /** Adds [entries]; how many were not there already. */
    private fun add(entries: List<Entry>): Int {
        // unblocked, as the user asks for them
        unblock(entries)
        val newlyAdded = lists.addAll(entries)
        for (entry in newlyAdded) {
            val model = userModel ?: break
            val listings = model.listings
            model.listByHand(entry)
            // listed now: the tables code it alone (see shared)
            if (model.listings > listings) newlyListed += entry.text
        }
        dropSessions()
        return newlyAdded.size
    }

    /** [added] and [blocked] (text, pinyin) put on the lists at once, as a restore merges them: one write of each, the sessions dropped once. */
    fun addListed(added: Collection<Pair<String, String>>, blocked: Collection<Pair<String, String>>) {
        val add = added.mapNotNull { (text, pinyin) -> WordLists.entry(text, pinyin) }
        val block = blocked.mapNotNull { (text, pinyin) -> WordLists.entry(text, pinyin) }
        if (add.isNotEmpty()) add(add)
        if (block.isNotEmpty()) blockAll(block)
        dropSessions()
    }

    fun blockWord(text: String, pinyin: String): Boolean {
        val entry = WordLists.entry(text, pinyin) ?: return false
        blockAll(listOf(entry))
        dropSessions()
        return true
    }

    // from a long press too, the session that asked going on with its input: not dropped, as the
    // host fetches its candidates next; the others read the model as they decode
    fun block(entry: Entry) = blockAll(listOf(entry))

    /** Blocks [entries]; how many were not already. */
    private fun blockAll(entries: List<Entry>): Int {
        blockedTexts = null
        val newlyBlocked = lists.blockAll(entries)
        val model = user()
        entries.forEach { model.block(it) }
        freshModel?.let { fresh -> entries.forEach { fresh.block(it) } }
        blocked(entries.mapTo(LinkedHashSet()) { it.text })
        return newlyBlocked.size
    }

    fun removeWords(words: Collection<UserWord>) {
        val byKind = words.groupBy({ it.kind }, { it.entry() })
        byKind[UserWord.Kind.ADDED]?.let { removeAdded(it.filterNotNull()) }
        // forgotten in the learning record, which takes each out of the model (impl/design-1b.md §3)
        val learned = words.filter { it.kind == UserWord.Kind.LEARNED }.map { it.text }
        if (learned.isNotEmpty()) {
            forgetInTables(learned)
            forgotten(learned)
        }
        byKind[UserWord.Kind.BLOCKED]?.let { unblock(it.filterNotNull()) }
        dropSessions()
    }

    /**
     * Takes [entries] off the added words, typeable no more once the model is made again. One no
     * dictionary has comes back from the log as the user's own word, if it was picked: forgotten
     * first, on the log still open, so a log that does not open again loses no FORGOT record. One
     * a dictionary has keeps what was learned of it, in the tables too; and the tables, which have
     * no readings, keep what they learned of a text a dictionary has, or the added words still, in
     * another reading. While a dictionary does not read, nothing is forgotten.
     */
    private fun removeAdded(entries: List<Entry>) {
        // a model made once they are taken off lists none of them: not made again
        val listedThem = userModel != null
        val removed = lists.removeAll(entries)
        if (removed.isEmpty()) return
        // with no log the model is kept: no longer scored as the model's word
        removed.forEach { userModel?.unlistByHand(it) }
        selfMade(removed)?.let { (back, texts) ->
            // and a table's pick of one is read back as its saved phrase
            if (texts.isNotEmpty()) forgetInTables(texts)
            // forgotten in the learning record as a text, so only one listed at no other reading: its
            // forget takes every reading off the list (Engines.changed)
            back.map { it.text }.filter { it in texts }.distinct().takeIf { it.isNotEmpty() }?.let(forgotten)
        }
        if (listedThem) dropUser()
    }

    /**
     * Takes [text] off the added words as a long press deletes it (U-82): out of the model at once
     * ([UserModel.unlist]), the sessions kept, as the one that asked goes on with its input.
     */
    fun unAdd(text: String) {
        val entries = lists.addedWords.filter { it.text == text }
        if (entries.isEmpty()) return
        lists.removeAll(entries)
        entries.forEach { userModel?.unlist(it) }
    }

    /**
     * Of [removed], those neither the base dictionary nor the user's dictionaries have,
     * with the texts of them the tables forget: those none of these has in any reading, nor the
     * added words still. Null if a dictionary did not read: any of them may be its word.
     */
    private fun selfMade(removed: List<Entry>): Pair<List<Entry>, Set<String>>? {
        // a model with nothing in it knows the base dictionary's words alone
        val base = UserModel(pinyinData.dictionary, pinyinData.vocabulary)
        val left = removed.filterNotTo(HashSet(), base::knows)
        if (left.isEmpty()) return emptyList<Entry>() to emptySet<String>()
        val texts = left.mapTo(HashSet()) { it.text }
        // a settings action, not a key: a word whose first character reads there as it never does alone (了解) found too
        texts -= LibimeImport.readings(pinyinData.dictionary, pinyinData.vocabulary, texts, strayFirst = true).keys
        lists.addedWords.forEach { texts -= it.text }
        var unread = false
        // read again, as the model is made again: the dictionaries are not kept in memory, and the
        // model lists a dictionary's word as it does one added
        forEachDictionaryWord(
            failed = {
                unread = true
                onError(it)
            },
        ) {
            left -= it
            texts -= it.text
        }
        if (unread || addedFailed) return null
        return removed.filter { it in left } to texts
    }

    // as listed: one syllable a space, so read back as it was, not split again
    private fun UserWord.entry() = LibimeImport.entry(text, pinyin.replace(' ', '\''))

    /** [word] as its list keeps it: what a removal taken back puts back ([reAdd], [reBlock]). */
    fun entryOf(word: UserWord): Entry? = word.entry()

    /** [entries] blocked again, as an unblock taken back. */
    fun reBlock(entries: List<Entry>) {
        if (entries.isNotEmpty()) blockAll(entries)
    }

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
            val entry = pinyin?.let { WordLists.entry(line.text, it) }
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
        val added = if (add.isEmpty()) 0 else add(add.toList())
        var blocked = 0
        if (block.isNotEmpty()) {
            blocked = blockAll(block.toList())
            dropSessions()
        }
        val batch = incoming.filter { it.first !in block }.map { (e, count) -> ImportedWords.Incoming(e.text, reading(e), count) }
        val imported = if (batch.isEmpty()) 0 else imports(file, batch, kept())
        if (imported > 0) dropSessions()
        return Imported(added, blocked, unread, learnAll(list.learn), imported)
    }

    // a list's lines as read: the words ([read], each with its line's count), the learned of an export, whether it is the user's own
    private class ListRead(val read: List<WordLists.Line>, val counts: List<Double?>, val learn: Map<Entry, Float>, val unread: Int, val own: Boolean)

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
        for (raw in lines) {
            val line = raw.trimStart().removePrefix("\uFEFF")
            if (export == null) {
                export = WordListFiles.exportMark(raw)
                own = WordListFiles.exportMark(raw, old = true) == true
            }
            if (!line.startsWith(WordListFiles.LEARNED_MARK)) {
                WordLists.line(raw)?.let {
                    read += it
                    counts += ImportFiles.frequency(raw)
                }
                continue
            }
            val rest = line.removePrefix(WordListFiles.LEARNED_MARK)
            val word = WordLists.line(rest)
            val entry = word?.pinyin?.let { WordLists.entry(word.text, it) }
            if (export != true || entry == null || word.blocked) unread++ else learn[entry] = learnedCount(rest)
        }
        return ListRead(read, counts, learn, unread, own)
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

    private fun reading(entry: Entry) = entry.syllables.joinToString(" ") { Syllables.spelling(it) }

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

    // English's learned words listed in the model of the moment, spelt by letters (U-31 §3): those it lacked
    private var latin = emptySet<Entry>()
    private var latinIn: UserModel? = null

    /**
     * [texts], the English words English learned, listed spelt by their letters for pinyin's 中英混输
     * (U-31 §3: `youmo` typed offers youmo), as the dictionary's Latin words are; those listed before
     * and not now unlisted. A model made anew lists them all again; one it knows already it keeps as it is.
     */
    fun listLatin(texts: Set<String>) {
        val model = user()
        val was = if (latinIn === model) latin else emptySet()
        val entries = texts.mapTo(HashSet()) { t -> Entry(t, IntArray(t.length) { LatinWords.syllable(t[it].lowercaseChar()) }) }
        for (e in was) if (e !in entries) model.unlist(e)
        latin = entries.filterTo(HashSet()) { e -> e in was || !model.knows(e) }
        for (e in latin) if (e !in was) model.listLatin(e)
        latinIn = model
    }

    fun exportWords(): List<String> = userWords().map {
        val line = "${it.text} ${it.pinyin.replace(' ', '\'')}"
        when (it.kind) {
            UserWord.Kind.BLOCKED -> "!$line"
            // its count after it, for its place: read back only from an export (importWords)
            UserWord.Kind.LEARNED -> "${WordListFiles.LEARNED_MARK} $line ${it.count}"
            UserWord.Kind.ADDED -> line
        }
    }

    /**
     * Makes the user's words again from the learning record, the dictionaries and the lists, at
     * next need: a word listed cannot be taken out of the model, only left out of the next one.
     */
    private fun dropUser() {
        userModel = null
        freshModel = null
    }

    private fun addDictionaries(model: UserModel) {
        forEachDictionaryWord(onError) { model.list(it) }
    }

    /**
     * Each word of the user's dictionaries, all merged into the base one, as it is read, to [each].
     * [failed] hears of each that does not read.
     */
    private fun forEachDictionaryWord(failed: (IOException) -> Unit, each: (Entry) -> Unit) {
        // as they are read: one dictionary in the heap at a time, not all their lines at once
        try {
            added()?.dictionary?.invoke()?.forEach { line -> LibimeImport.dictionaryEntry(line)?.let(each) }
        } catch (e: IOException) {
            failed(e)
        }
    }

    /**
     * What libime learned, into a log with nothing in it yet. A failure to read or learn them is
     * thrown (as an [IOException]): the conversion that asked is owed to the next start.
     */
    // whether libime had any
    private fun importLegacy(model: UserModel): Boolean {
        try {
            val old = legacy() ?: return false
            LibimeImport.learn(
                model, pinyinData.dictionary, pinyinData.vocabulary,
                old.dictionary.asSequence(), old.history.asSequence(), old.decode,
            )
            return true
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // old data, however it came to be: no failure reading it should stop typing
            throw IOException("cannot import libime's data", e)
        }
    }

    // the texts add() listed in the user model since it was made, in order: coded by each table
    // alone, not with every listed word again
    private val newlyListed = ArrayList<String>()

    /** [newlyListed] as the tables read it: they index into it, so only add() and user() change it. */
    val listedSince: List<String> get() = newlyListed

    /**
     * Phrases a table saved (in an older build: 自动造词 is gone), as pinyin's words: those pinyin lacks.
     * Read with no stray first (EngineAssets.pinyinsOf), a word may read as its pieces do (了解 le jie, a
     * reading the log would keep and an export carry): every text so read that pinyin lacks is read
     * again with a stray first, all in one walk, and learned as the dictionary then reads it (liao jie);
     * if the dictionary has no reading of it, as its pieces read, the only one pinyin can offer it by.
     * The walk reads the base dictionary alone: a word only the user's dictionaries have, so misread,
     * is learned so either way.
     */
    fun learnSaved(texts: Collection<String>) {
        if (texts.isEmpty()) return
        val model = user()
        val read = assets.pinyinsOf(texts).mapNotNull { (text, reading) -> readAs(text, reading) }
        val unknown = read.filterNotTo(HashSet(), model::knows)
        // one walk with a stray first for them all, some hundred times the walk; none for no text
        val stray = assets.pinyinsOf(unknown.map { it.text }, strayFirst = true)
        // a word pinyin lacks is the user's own, in the learning record (impl/design-1b.md §4); one it
        // has was counted once more, which the record's counts, of commits, no longer do (step 2's)
        val own = read.filter { entry -> entry in unknown && readAs(entry.text, stray[entry.text])?.takeIf(model::knows) == null }
        if (own.isNotEmpty()) owned(own.associateWith { 0f })
    }

    // as EngineAssets.pinyinsOf spells a reading: one syllable a space
    private fun readAs(text: String, reading: String?): Entry? = reading?.let { LibimeImport.entry(text, it.replace(' ', '\'')) }

    /** Forgets [text] in pinyin, however read, and in every table that saved it. */
    fun forgetEverywhere(text: String) {
        forgetInTables(listOf(text))
        forgotten(listOf(text))
    }

    // the texts blocked, of any reading: a table has no readings to tell apart
    private var blockedTexts: Set<String>? = null

    private fun blockedTexts(): Set<String> = blockedTexts ?: lists.blockedWords.mapTo(HashSet()) { it.text }.also { blockedTexts = it }

    /** A character blocked in pinyin, for its strokes: found by strokes, it is the character, any reading. */
    fun isBlocked(text: String): Boolean = text in blockedTexts()

    /**
     * A word blocked, for a table. Not a character: blocked in pinyin under one reading (了 liao),
     * a table, which has no readings, would lose it under all.
     */
    fun isBlockedWord(text: String): Boolean = text.codePointCount(0, text.length) >= 2 && text in blockedTexts()

    /** Forgets what was read of [additions], read again at next need: what their dictionaries were, for [reloadDictionaries]. */
    fun dropAdditions(): String {
        val before = userAdditions?.dictionaries.orEmpty()
        addedRead = false
        phrases = null
        return before
    }

    /** The user's words read again if the dictionaries are no longer as they were [before]: see [Engines.reload]. */
    fun reloadDictionaries(before: String) {
        if (userModel == null && freshModel == null || added()?.dictionaries.orEmpty() == before) return
        dropUser()
    }

    /** After the learning record was closed: [user] is made again from it. */
    fun closed() {
        userModel = null
        freshModel = null
    }

    private var freshModel: UserModel? = null

    /** The words added by hand of [text]: what a long press's delete takes off the list ([unAdd]), for its undo to put back. */
    fun addedOf(text: String): List<Entry> = lists.addedWords.filter { it.text == text }

    /** The words blocked of [text]. */
    fun blockedOf(text: String): List<Entry> = lists.blockedWords.filter { it.text == text }

    /** [entries] of the words added by hand, each with its place on the list now: what [reAdd] puts back there. */
    fun addedPlaces(entries: List<Entry>): List<IndexedValue<Entry>> {
        val all = lists.addedWords
        return entries.mapNotNull { e -> all.indexOf(e).takeIf { it >= 0 }?.let { IndexedValue(it, e) } }
    }

    /**
     * [entries] back on the words added by hand where they were ([addedPlaces]), as a delete taken
     * back finds them, their order among 我的词 as before (review-int N3): the sessions kept, as [unAdd].
     */
    fun reAdd(entries: List<IndexedValue<Entry>>) {
        lists.putBack(entries).forEach { userModel?.listByHand(it) }
    }

    /** [entries] blocked no more, as a block taken back: the sessions kept, as [block]. */
    fun unblock(entries: List<Entry>) {
        blockedTexts = null
        lists.unblockAll(entries).forEach {
            userModel?.unblock(it)
            freshModel?.unblock(it)
        }
    }

    /** U-86 「全部清空」: no word added by hand nor blocked any more; the model made anew at next need. */
    fun clearLists() {
        lists.removeAll(lists.addedWords)
        lists.unblockAll(lists.blockedWords)
        blockedTexts = null
        dropUser()
        dropSessions()
    }

    /**
     * U-93 「不用我的输入习惯」 on: the words as a fresh install has them but for the dictionaries the
     * user turned on (a setting, not a habit) and the blocks, which hold whatever (C26): none of
     * their own words, counts or pairs, none added by hand. [attach] is not told: nothing of the
     * learning record is in it.
     */
    fun freshUser(): UserModel = freshModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, index = pinyinData.wordIndex).also { model ->
        freshModel = model
        addDictionaries(model)
        lists.blockedWords.forEach { model.block(it) }
    }

    /** As [freshUser], the phrases: the built-in ones alone, as installed, the user's left out (U-93). */
    fun freshPhrases(): PhraseBook = PhraseBook(CustomPhrases.parse(""), CustomPhrases.BUILT_IN) {}
}


// a learned line's count, its last field; 1 for none
private fun learnedCount(line: String) = line.trim().split(' ').last().toFloatOrNull()?.takeIf { it > 0f } ?: 1f
