/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.host.Engines.Additions
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.NEW_WORD_SCORE
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.USER_PINYIN
import org.fcitx.fcitx5.android.engine.host.Engines.Imported
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.UserStore
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The user's words, for [Engines]: what they added to pinyin ([additions]: custom phrases,
 * dictionaries), the model of what they typed with its log under [userDir] (made
 * afresh from libime's, [legacy]), its layer prior, and the words they added
 * and blocked by hand ([WordLists]). It lists, adds, blocks, imports, exports and forgets those
 * words for the settings, blocks and forgets them for a table's candidate, and learns what a
 * table saved. A word forgotten here is forgotten in the tables too ([forgetInTables]); a change
 * the sessions must see drops them ([dropSessions]). Run on the fcitx thread, as [Engines] is.
 */
internal class UserLexicon(
    private val userDir: File?,
    private val onError: (IOException) -> Unit,
    private val legacy: () -> LibimeImport.Legacy?,
    private val additions: () -> Additions?,
    private val assets: EngineAssets,
    private val compactions: Executor,
    private val forgetInTables: (Collection<String>) -> Unit,
    private val dropSessions: () -> Unit,
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

    /** The user's log, while open; closed by [Engines.close] with the tables' logs. */
    var store: UserStore? = null
        private set
    private var userModel: UserModel? = null
    private var layerPrior: LayerPrior? = null

    /** Learned with the user model, and kept in its log; one for the life of the engine. */
    fun prior(): LayerPrior = layerPrior ?: LayerPrior(pinyinData.layers, extra = packLayers).also { layerPrior = it }

    // the model of the moment's, as the prior outlives it
    private val packLayers = object : LayerPrior.PackLayers {
        override fun layerOf(word: Int) = userModel?.layerOf(word) ?: -1
        override fun modelWord(word: Int) = userModel?.modelWord(word) ?: word
    }

    // the dictionaries' words go in listed, uncounted: scored as the model's unknown word until
    // picked, and not kept in the log unless picked. The words added by hand are the user's choice:
    // scored as the model's word of their text from the start (UserModel.modelWord)
    fun user(): UserModel = userModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, index = pinyinData.wordIndex).also { model ->
        userModel = model
        newlyListed.clear()
        if (userDir != null) {
            userDir.mkdirs()
            val file = File(userDir, USER_PINYIN)
            // a log that cannot be read or moved aside: learn in memory rather than not type
            store = try {
                UserStore(file, model, onError = onError, prior = prior(), background = compactions).apply { open(seed = ::importLegacy) }
            } catch (e: IOException) {
                onError(e)
                null
            }
        }
        addDictionaries(model)
        lists.applyTo(model)
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

    /** The texts of the words added by hand, which no session holds back as coarse: listed, they have no count to say they are the user's. */
    fun addedTexts(): Set<String> = lists.addedWords.mapTo(HashSet()) { it.text }

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
        val blocked = lists.blockAll(entries)
        val model = user()
        entries.forEach { model.block(it) }
        return blocked.size
    }

    fun removeWords(words: Collection<UserWord>) {
        val byKind = words.groupBy({ it.kind }, { it.entry() })
        byKind[UserWord.Kind.ADDED]?.let { removeAdded(it.filterNotNull()) }
        byKind[UserWord.Kind.LEARNED]?.let { learned ->
            val entries = learned.filterNotNull()
            val model = user()
            // a FORGOT record a word forgotten, each a pass over all the counts as a start replays it:
            // compacted so none is. knows holds for a dictionary's word forgetEach skips too, but no
            // learned word is the dictionary's: at worst a stale one costs a compaction
            val forgotten = entries.toSet().count(model::knows)
            model.forgetEach(entries)
            forgetInTables(entries.map { it.text })
            if (forgotten > 1) compactUser()
        }
        byKind[UserWord.Kind.BLOCKED]?.let { entries ->
            blockedTexts = null
            lists.unblockAll(entries.filterNotNull()).forEach { userModel?.unblock(it) }
        }
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
            if (back.isNotEmpty()) {
                user().forgetEach(back)
                if (back.size > 1) compactUser()
            }
        }
        if (listedThem) dropUser()
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
            plain = {
                left -= it
                texts -= it.text
            },
        ) { entry, _, _ ->
            left -= entry
            texts -= entry.text
        }
        if (unread || addedFailed) return null
        return removed.filter { it in left } to texts
    }

    // as listed: one syllable a space, so read back as it was, not split again
    private fun UserWord.entry() = LibimeImport.entry(text, pinyin.replace(' ', '\''))

    fun importWords(lines: Sequence<String>): Imported {
        val add = LinkedHashSet<Entry>()
        val block = LinkedHashSet<Entry>()
        var unread = 0
        val read = lines.mapNotNull { WordLists.line(it) }.toList()
        val guessed = assets.pinyinsOf(read.filter { it.pinyin == null && it.text.isNotEmpty() }.map { it.text }, strayFirst = true)
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
            dropSessions()
        }
        return Imported(added, blocked, unread)
    }

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
        closeQuietly(log, onError)
        store = null
        userModel = null
    }

    /** Rewrites the user's log as the counts now are: a start then reads none of what was forgotten. */
    private fun compactUser() {
        store?.compact()
    }

    private fun addDictionaries(model: UserModel) {
        val modelWords = pinyinData.model.vocabularySize
        forEachDictionaryWord(onError, { model.list(it) }) { entry, score, layer -> model.list(entry, score, layer, modelWords) }
    }

    /**
     * Each word of the user's dictionaries as it is read: [plain] one of a dictionary
     * merged into the base one, [scored] any other, with the score and the layer it goes in.
     * [failed] hears of each that does not read.
     */
    private fun forEachDictionaryWord(failed: (IOException) -> Unit, plain: (Entry) -> Unit, scored: (Entry, Float, Int) -> Unit) {
        // as they are read: one dictionary in the heap at a time, not all their lines at once
        try {
            added()?.dictionary?.invoke()?.forEach { line -> LibimeImport.dictionaryEntry(line)?.let(plain) }
        } catch (e: IOException) {
            failed(e)
        }
        val newLayer = prior().layer(WordLayers.NEW)
        // a dictionary has no scores of its own: its words the model lacks are scored as the new
        // words dictionary's typical one, and weighed with it
        try {
            added()?.newDictionary?.invoke()?.forEach { line ->
                LibimeImport.dictionaryEntry(line)?.let { scored(it, NEW_WORD_SCORE, newLayer) }
            }
        } catch (e: IOException) {
            failed(e)
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

    // the texts add() listed in the user model since it was made, in order: coded by each table
    // alone, not with every listed word again
    private val newlyListed = ArrayList<String>()

    /** [newlyListed] as the tables read it: they index into it, so only add() and user() change it. */
    val listedSince: List<String> get() = newlyListed

    /**
     * Phrases a table saved, as pinyin's words: learned once more [again], else only those pinyin lacks.
     * Read with no stray first (EngineAssets.pinyinsOf), a word may read as its pieces do (了解 le jie, a
     * reading the log would keep and an export carry): that one is read again with a stray first, and
     * learned as the dictionary then reads it (liao jie); if the dictionary has no reading of it, as its
     * pieces read, the only one pinyin can offer it by. Told a table's history (not [again]), every text
     * so read that pinyin lacks is read again, all in one walk; a phrase saved as it is typed, on the
     * input thread, only if the model or the user's words (typed, made, listed) have its text: a
     * dictionary word of neither is learned there as its pieces read. The walk reads the base dictionary
     * alone: a word only the user's dictionaries have, so misread, is learned so either way.
     */
    fun learnSaved(texts: Collection<String>, again: Boolean) {
        if (texts.isEmpty()) return
        val model = user()
        val read = assets.pinyinsOf(texts).mapNotNull { (text, reading) -> readAs(text, reading) }
        val unknown = read.filterNotTo(HashSet(), model::knows)
        val misread = if (again) unknown.filter { mayBeWord(it.text, model) } else unknown
        // one walk with a stray first for them all, some hundred times the walk; none for no text
        val stray = assets.pinyinsOf(misread.map { it.text }, strayFirst = true)
        for (entry in read) {
            val word = if (entry !in unknown) entry else readAs(entry.text, stray[entry.text])?.takeIf(model::knows)
            when {
                word == null -> model.learn(null, listOf(entry))
                again -> model.learn(null, listOf(word))
            }
        }
    }

    // a text the model or the user's words have in some reading, found without a walk: the model's
    // by their index, the user's gone through once
    private fun mayBeWord(text: String, model: UserModel): Boolean =
        pinyinData.wordIndex.find(text) >= 0 || model.hasText(text)

    // as EngineAssets.pinyinsOf spells a reading: one syllable a space
    private fun readAs(text: String, reading: String?): Entry? = reading?.let { LibimeImport.entry(text, it.replace(' ', '\'')) }

    /** Forgets [text] in pinyin, however read, and in every table that saved it. */
    fun forgetEverywhere(text: String) {
        user().forgetText(text)
        forgetInTables(listOf(text))
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

    /** Blocks [text] from a table's candidate, as the dictionary reads it and as the user typed it: false if it reads none. */
    fun blockText(text: String): Boolean {
        val read = assets.pinyinOf(text, strayFirst = true)?.let { LibimeImport.entry(text, it.replace(' ', '\'')) }
        val entries = (listOfNotNull(read) + user().entriesOf(text)).distinct()
        if (entries.isEmpty()) return false
        blockAll(entries)
        return true
    }

    /** Forgets what was read of [additions], read again at next need: what their dictionaries were, for [reloadDictionaries]. */
    fun dropAdditions(): String {
        val before = userAdditions?.dictionaries.orEmpty()
        addedRead = false
        phrases = null
        return before
    }

    /** The user's words read again if the dictionaries are no longer as they were [before]: see [Engines.reload]. */
    fun reloadDictionaries(before: String) {
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

    /** After [store] was closed: [user] reads the log again, and appends to it. */
    fun closed() {
        store = null
        userModel = null
    }
}
