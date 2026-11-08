/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.runtime.Engines.Additions
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import java.io.IOException

/**
 * The user's words, for [Engines]: what they added to pinyin ([added]: custom phrases,
 * dictionaries), the decoder's lexicon of them ([user]: their own words and counts as the learning
 * record has them, put in by [attach]), its layer prior, and the words they added and blocked by
 * hand ([WordLists]). It lists, adds, blocks, imports ([wordImport]), exports and forgets those
 * words for the settings, blocks and forgets them for a table's candidate; another input method's
 * list it imports goes to the learning record as a batch of imported words ([imports], U-101). A
 * word forgotten here is forgotten in the tables too ([forgetInTables]) and in the learning record
 * ([forgotten]); a change the sessions must see drops them ([dropSessions]). Run on the fcitx
 * thread, as [Engines] is.
 */
internal class UserLexicon(
    private val userDir: UserFiles?,
    private val onError: (IOException) -> Unit,
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

    /** The user's dictionaries and custom phrases, read at first need: see [UserAdditions]. */
    val added = UserAdditions(additions, onError)

    /** Lists of words imported: see [WordImport]. */
    val wordImport by lazy(LazyThreadSafetyMode.NONE) {
        WordImport(
            assets = assets, lists = lists, user = ::user, addWords = ::add, blockAll = ::blockAll,
            dropSessions = dropSessions, owned = owned, imports = imports, blockEnglish = { englishBlocks.block(it).size },
        )
    }

    private var userModel: UserModel? = null
    private var layerPrior: LayerPrior? = null

    /** The prior per layer, one for the life of the engine: no longer learned, nor read from an older build's log (impl/design-1b.md §4). */
    fun prior(): LayerPrior = layerPrior ?: LayerPrior(pinyinData.layers, extra = packLayers).also { layerPrior = it }

    // the model of the moment's, as the prior outlives it
    private val packLayers = object : LayerPrior.PackLayers {
        override fun layerOf(word: Int) = userModel?.layerOf(word) ?: -1
        override fun modelWord(word: Int) = userModel?.modelWord(word) ?: word
    }

    // the dictionaries' words are the model's, listed, uncounted: scored below the model's unknown
    // word as their dictionary ranks them until the user owns them. The words added by hand are the
    // user's choice: scored as the model's word of their text from the start (UserModel.modelWord)
    fun user(): UserModel = userModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, pinyinData.wordIndex, added.dictionary(), added.stale()).also { model ->
        // kept while it is made, for what attach asks of it; not once a step failed, so the next
        // need makes it whole again rather than going on with half of it
        userModel = model
        try {
            newlyListed.clear()
            lists.applyTo(model)
            attach(model)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            userModel = null
            throw e
        }
    }

    // in the user directory with the log, in memory without one
    private val lists by lazy(LazyThreadSafetyMode.NONE) { WordLists(userDir, onError) }

    /** English's blocked words, a list of its own (no reading): shown and unblocked among pinyin's, no pinyin to them. */
    val englishBlocks by lazy(LazyThreadSafetyMode.NONE) { EnglishBlocks(userDir, onError) }

    private fun Entry.word(kind: UserWord.Kind, count: Float = 0f) =
        UserWord(text, spelled(), kind, count)

    fun userWords(): List<UserWord> = UserWord.Kind.entries.flatMap { userWords(it) }

    fun userWords(kind: UserWord.Kind): List<UserWord> = when (kind) {
        UserWord.Kind.ADDED -> lists.addedWords.map { it.word(kind) }
        UserWord.Kind.LEARNED -> user().ownWords().sortedByDescending { it.second }.map { (entry, count) -> entry.word(kind, count) }
        UserWord.Kind.BLOCKED -> lists.blockedWords.map { it.word(kind) } + englishBlocks.blocked.map { UserWord(it, "", kind) }
    }

    fun userWordCounts(): Map<UserWord.Kind, Int> = mapOf(
        UserWord.Kind.ADDED to lists.addedWords.size,
        UserWord.Kind.LEARNED to user().ownWords().size,
        UserWord.Kind.BLOCKED to lists.blockedWords.size + englishBlocks.blocked.size,
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
        val block = blocked.mapNotNull { (text, pinyin) -> WordLists.blockedEntry(text, pinyin) }
        // English's, no pinyin: a backup's of an older build has none
        englishBlocks.block(blocked.filter { it.second.isEmpty() }.map { it.first })
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
        englishBlocks.unblock(words.filter { it.kind == UserWord.Kind.BLOCKED && it.pinyin.isEmpty() }.map { it.text })
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
        texts -= WordText.readings(pinyinData.dictionary, pinyinData.vocabulary, texts, strayFirst = true).keys
        lists.addedWords.forEach { texts -= it.text }
        // a merged dictionary's word stays, a word of its text no word of the user's
        val file = added.dictionary()
        if (file != null) {
            left.removeAll { file.find(it) >= 0 }
            texts.removeAll(file::hasText)
        }
        // nor while the merged file is being made (0.3.1's minute of upgrade) or failed: its words not known yet
        if (added.unknown()) return null
        return removed.filter { it in left } to texts
    }

    // as listed: one syllable a space, so read back as it was, not split again
    private fun UserWord.entry() = if (kind == UserWord.Kind.BLOCKED) WordLists.blockedEntry(text, Syllables.apostrophed(pinyin)) else WordText.entry(text, Syllables.apostrophed(pinyin))

    /** [word] as its list keeps it: what a removal taken back puts back ([reAdd], [reBlock]). */
    fun entryOf(word: UserWord): Entry? = word.entry()

    /** [entries] blocked again, as an unblock taken back. */
    fun reBlock(entries: List<Entry>) {
        if (entries.isNotEmpty()) blockAll(entries)
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
        val line = "${it.text} ${Syllables.apostrophed(it.pinyin)}"
        when (it.kind) {
            // English's, no reading: marked so, a line of letters alone reading as pinyin
            UserWord.Kind.BLOCKED -> if (it.pinyin.isEmpty()) WordListFiles.ENGLISH_BLOCK_MARK + it.text else "!$line"
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

    // the texts add() listed in the user model since it was made, in order: coded by each table
    // alone, not with every listed word again
    private val newlyListed = ArrayList<String>()

    /** [newlyListed] as the tables read it: they index into it, so only add() and user() change it. */
    val listedSince: List<String> get() = newlyListed

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
    fun isBlockedWord(text: String): Boolean = text.codePointCount(0, text.length) >= 2 && Han.any(text) && text in blockedTexts()

    /** The user's words read again if the dictionaries are no longer as they were [before] ([UserAdditions.drop]): see [Engines.reload]. */
    fun reloadDictionaries(before: String) {
        if (userModel == null && freshModel == null || added.dictionaries() == before) return
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
        englishBlocks.unblock(englishBlocks.blocked)
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
    fun freshUser(): UserModel = freshModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, pinyinData.wordIndex, added.dictionary(), added.stale()).also { model ->
        freshModel = model
        lists.blockedWords.forEach { model.block(it) }
    }
}
