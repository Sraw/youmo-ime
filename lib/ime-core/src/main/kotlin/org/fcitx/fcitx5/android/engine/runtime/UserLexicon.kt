/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.runtime.Engines.Additions
import org.fcitx.fcitx5.android.engine.user.CharGate
import org.fcitx.fcitx5.android.engine.user.HeldWords
import org.fcitx.fcitx5.android.engine.user.RareChoice
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import java.io.IOException

/**
 * The user's words for [Engines]: additions ([added]), the decoder's user model ([user], filled from the record via
 * [attach]), the layer prior, and hand-added and blocked lists ([WordLists]). Lists, adds, blocks, imports, exports
 * and forgets them; forgetting reaches the record ([forgotten]), never a table's remembered words, and changes drop
 * sessions. Also holds 生僻字屏蔽 ([held], [gate]): a setting, so the habits-off model holds the same back. On the fcitx thread.
 */
internal class UserLexicon(
    private val userDir: UserFiles?,
    private val onError: (IOException) -> Unit,
    private val additions: () -> Additions?,
    private val assets: EngineAssets,
    private val dropSessions: () -> Unit,
    // texts the model forgot, whatever the tables do: for the learning record
    private val forgotten: (Collection<String>) -> Unit = {},
    // puts the user's words and counts from the record into a new model
    private val attach: (UserModel) -> Unit = {},
    // blocked texts are unpinned wherever pinned: the block wins
    private val blocked: (Collection<String>) -> Unit = {},
    // makes words the user's own in the record where not yet, with an export's uses: how many were new
    private val owned: (Map<Entry, Float>) -> Int = { 0 },
    // writes a list's words as an imported batch, skipping known words: how many went in
    private val imports: (String, List<ImportedWords.Incoming>, (String, String) -> Boolean) -> Int = { _, _, _ -> 0 },
    // the words tables remembered, listed with the learned ones, and dropping some of them
    private val tableWords: () -> List<TableWordRow> = { emptyList() },
    private val dropTableWords: (List<TableWordRow>) -> Unit = {},
) {

    private val pinyinData get() = assets.pinyinData

    /** Read at first need: see [UserAdditions]. */
    val added = UserAdditions(additions, onError)

    /** See [WordImport]. */
    val wordImport by lazy(LazyThreadSafetyMode.NONE) {
        WordImport(
            assets = assets, lists = lists, user = ::user, addWords = ::add, blockAll = ::blockAll,
            dropSessions = dropSessions, owned = owned, imports = imports, blockEnglish = { englishBlocks.block(it).size },
        )
    }

    private var userModel: UserModel? = null
    private var layerPrior: LayerPrior? = null

    /**
     * One per engine lifetime: no longer learned, nor read from an older log. Every layer here is 0, so its
     * scorer only holds a score to 0 as the inner one does; the eval's measured priors are what it is for.
     */
    fun prior(): LayerPrior = layerPrior ?: LayerPrior(pinyinData.layers, extra = packLayers).also { layerPrior = it }

    // the current model's, as the prior outlives it
    private val packLayers = object : LayerPrior.PackLayers {
        override fun layerOf(word: Int) = userModel?.layerOf(word) ?: -1
        override fun modelWord(word: Int) = userModel?.modelWord(word) ?: word
    }

    // dictionaries' words are listed uncounted, scored below the model's unknown word by their dictionary rank until
    // owned; hand-added words are the user's choice, scored as the model's word from the start (UserModel.modelWord)
    fun user(): UserModel = (userModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, pinyinData.wordIndex, added.dictionary(), added.stale()).also { model ->
        // kept while being made, for attach; not once a step failed, so the next need rebuilds it whole
        userModel = model
        try {
            newlyListed.clear()
            lists.applyTo(model)
            attach(model)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            userModel = null
            throw e
        }
    }).also { it.held = held() }

    // 生僻字屏蔽's held words for the data and choice they were made of: kept across invalidate()s, remade only when either changes
    private var heldWords = HeldWords.NONE
    private var heldData: Any? = null
    private var heldChoice: Any? = null

    /** For the tests: [HeldWords] made, once per data and choice. */
    internal var heldMade = 0

    /** Tests' gate in place of the user's choice: one holding back 的, which no choice can (RareCharContractTest). */
    internal var gateOverride: CharGate? = null

    /** The dictionary words 生僻字屏蔽 holds back as chosen ([UserAdditions.rareChoice]); none while off. Made on the fcitx thread at first need after a change: walks every word's characters. */
    fun held(): HeldWords {
        val choice: Any = gateOverride ?: added.rareChoice()
        val data = pinyinData
        // a CharGate (a test's) by identity, a RareChoice by value, so a reread same choice is the same
        if (data === heldData && choice == heldChoice) {
            heldChoice = choice
            return heldWords
        }
        val gate = when (choice) {
            is CharGate -> choice
            // levels read only when on: a build without them is off, told once (EngineAssets.charLevels)
            is RareChoice -> if (choice.on && !choice.newer) choice.gate(assets.charLevels) else CharGate.NONE
            else -> CharGate.NONE
        }
        heldWords = if (gate.none) HeldWords.NONE else HeldWords.of(gate, data.vocabulary).also { heldMade++ }
        heldData = data
        heldChoice = choice
        return heldWords
    }

    /** For texts without a word id. */
    fun gate(): CharGate = held().gate

    /** Blocked or with a held-back character: what stroke lookup, 拆字 and glide alternatives leave out. */
    fun withheld(text: String): Boolean = isBlocked(text) || gate().holds(text)

    // in the user directory with the log, in memory without one
    private val lists by lazy(LazyThreadSafetyMode.NONE) { WordLists(userDir, onError) }

    /** English's blocked words, a separate list (no reading), shown and unblocked among pinyin's. */
    val englishBlocks by lazy(LazyThreadSafetyMode.NONE) { EnglishBlocks(userDir, onError) }

    private fun Entry.word(kind: UserWord.Kind, count: Float = 0f) =
        UserWord(text, spelled(), kind, count)

    fun userWords(): List<UserWord> = UserWord.Kind.entries.flatMap { userWords(it) }

    fun userWords(kind: UserWord.Kind): List<UserWord> = when (kind) {
        UserWord.Kind.ADDED -> lists.voiceAlways.let { always -> lists.addedWords.map { it.word(kind).copy(voiceAlways = it.text in always) } }
        UserWord.Kind.LEARNED -> user().ownWords().sortedByDescending { it.second }.map { (entry, count) -> entry.word(kind, count) } +
            tableWords().map { UserWord(it.text, it.code, kind, table = it.im) }
        UserWord.Kind.BLOCKED -> lists.blockedWords.map { it.word(kind) } + englishBlocks.blocked.map { UserWord(it, "", kind) }
    }

    fun userWordCounts(): Map<UserWord.Kind, Int> = mapOf(
        UserWord.Kind.ADDED to lists.addedWords.size,
        UserWord.Kind.LEARNED to user().ownWords().size + tableWords().size,
        UserWord.Kind.BLOCKED to lists.blockedWords.size + englishBlocks.blocked.size,
    )

    /** Hand-added words' texts in list order: never held back as coarse (listed, they have no count), ranked after the user's formed words. */
    fun addedTexts(): Set<String> = lists.addedWords.mapTo(LinkedHashSet()) { it.text }

    fun addWord(text: String, pinyin: String): Boolean {
        val entry = WordLists.entry(text, pinyin) ?: return false
        add(listOf(entry))
        return true
    }

    /** How many were new. */
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

    /** Adds and blocks (text, pinyin) at once, as a restore merges them: one write each, sessions dropped once. */
    fun addListed(added: Collection<Pair<String, String>>, blocked: Collection<Pair<String, String>>) {
        val add = added.mapNotNull { (text, pinyin) -> WordLists.entry(text, pinyin) }
        val block = blocked.mapNotNull { (text, pinyin) -> WordLists.blockedEntry(text, pinyin) }
        // English's, no pinyin: an older build's backup has none
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

    // from a long press too: the asking session goes on (the host fetches its candidates next); others read the model as they decode
    fun block(entry: Entry) = blockAll(listOf(entry))

    /** How many were new. */
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
        // forgotten in the record, which takes each out of the model
        val learned = words.filter { it.kind == UserWord.Kind.LEARNED && it.table.isEmpty() }.map { it.text }
        if (learned.isNotEmpty()) forgotten(learned)
        // a table's under its code alone: pinyin's word of the same text stays
        val tables = words.filter { it.kind == UserWord.Kind.LEARNED && it.table.isNotEmpty() }.mapTo(HashSet()) { Triple(it.table, it.pinyin, it.text) }
        if (tables.isNotEmpty()) dropTableWords(tableWords().filter { Triple(it.im, it.code, it.text) in tables })
        byKind[UserWord.Kind.BLOCKED]?.let { unblock(it.filterNotNull()) }
        englishBlocks.unblock(words.filter { it.kind == UserWord.Kind.BLOCKED && it.pinyin.isEmpty() }.map { it.text })
        dropSessions()
    }

    /**
     * Takes [entries] off the added words, untypeable once the model is remade. One no dictionary has would return
     * from the log as the user's own word, so it is forgotten first while the log is open. One a dictionary has keeps
     * its learning, tables included. While a dictionary does not read, nothing is forgotten.
     */
    private fun removeAdded(entries: List<Entry>) {
        // a model made after they are removed lists none of them: not remade
        val listedThem = userModel != null
        val removed = lists.removeAll(entries)
        if (removed.isEmpty()) return
        // with no log the model is kept: no longer scored as the model's word
        removed.forEach { userModel?.unlistByHand(it) }
        selfMade(removed)?.let { (back, texts) ->
            // forgotten in the record as a text, so only those listed at no other reading: its forget unlists every reading
            back.map { it.text }.filter { it in texts }.distinct().takeIf { it.isNotEmpty() }?.let(forgotten)
        }
        if (listedThem) dropUser()
    }

    /** 「以后总是换」 on or off for an added word; whether it changed. */
    fun setVoiceAlways(text: String, on: Boolean): Boolean = lists.setVoiceAlways(text, on)

    /** The added words voice always puts in. */
    val voiceAlways: Set<String> get() = lists.voiceAlways

    /** [WordLists.putVoiceAlways]. */
    fun putVoiceAlways(texts: Collection<String>, merge: Boolean) = lists.putVoiceAlways(texts, merge)

    /** Takes [text] off the added words as a long press deletes it: out of the model at once ([UserModel.unlist]), sessions kept. */
    fun unAdd(text: String) {
        val entries = lists.addedWords.filter { it.text == text }
        if (entries.isEmpty()) return
        lists.removeAll(entries)
        entries.forEach { userModel?.unlist(it) }
    }

    /** Of [removed], those neither base nor user dictionaries have, plus the texts tables forget (in no reading anywhere, nor still added). Null if a dictionary did not read. */
    private fun selfMade(removed: List<Entry>): Pair<List<Entry>, Set<String>>? {
        // an empty model knows only the base dictionary's words
        val base = UserModel(pinyinData.dictionary, pinyinData.vocabulary)
        val left = removed.filterNotTo(HashSet(), base::knows)
        if (left.isEmpty()) return emptyList<Entry>() to emptySet<String>()
        val texts = left.mapTo(HashSet()) { it.text }
        // a settings action, not a key: also find words whose first character reads differently inside (了解)
        texts -= WordText.readings(pinyinData.dictionary, pinyinData.vocabulary, texts, strayFirst = true).keys
        lists.addedWords.forEach { texts -= it.text }
        // a merged dictionary's word stays; its text is no word of the user's
        val file = added.dictionary()
        if (file != null) {
            left.removeAll { file.find(it) >= 0 }
            texts.removeAll(file::hasText)
        }
        // nor while the merged file is being made or failed: its words are unknown
        if (added.unknown()) return null
        return removed.filter { it in left } to texts
    }

    // as listed: one syllable a space, so read back as it was, not split again
    private fun UserWord.entry() = if (kind == UserWord.Kind.BLOCKED) WordLists.blockedEntry(text, Syllables.apostrophed(pinyin)) else WordText.entry(text, Syllables.apostrophed(pinyin))

    /** As its list keeps it, for an undo to put back ([reAdd], [reBlock]). */
    fun entryOf(word: UserWord): Entry? = word.entry()

    /** Undoes an unblock. */
    fun reBlock(entries: List<Entry>) {
        if (entries.isNotEmpty()) blockAll(entries)
    }

    // English's learned words listed in the current model, spelt by letters: those it lacked
    private var latin = emptySet<Entry>()
    private var latinIn: UserModel? = null

    /** Lists English's learned [texts] spelt by letters for 中英混输 (`youmo` offers youmo); unlists those no longer given. A remade model lists all again. */
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
            // English's, no reading: marked so, since a line of letters alone would read as pinyin
            UserWord.Kind.BLOCKED -> if (it.pinyin.isEmpty()) WordListFiles.ENGLISH_BLOCK_MARK + it.text else "!$line"
            // its count after it, for its place: read back only from an export (importWords)
            UserWord.Kind.LEARNED -> "${WordListFiles.LEARNED_MARK} $line ${it.count}"
            UserWord.Kind.ADDED -> line
        }
    }

    /** Remakes the user's words at next need: a listed word cannot be taken out of a model, only left out of the next. */
    private fun dropUser() {
        userModel = null
        freshModel = null
    }

    // texts add() listed since the model was made, in order: each table codes them alone
    private val newlyListed = ArrayList<String>()

    /** Tables index into it, so only add() and user() change it. */
    val listedSince: List<String> get() = newlyListed

    /** In pinyin, any reading; a table's remembered words are its own. */
    fun forgetEverywhere(text: String) {
        forgotten(listOf(text))
    }

    // blocked texts in any reading: tables have no readings
    private var blockedTexts: Set<String>? = null

    private fun blockedTexts(): Set<String> = blockedTexts ?: lists.blockedWords.mapTo(HashSet()) { it.text }.also { blockedTexts = it }

    /** A character blocked in pinyin, for stroke lookup: any reading. */
    fun isBlocked(text: String): Boolean = text in blockedTexts()

    /** A word blocked, for a table; not a single character, which blocked under one reading would vanish from tables under all. */
    // a word as WordLists takes one; an emoji blocked in its word's reading is no table's word to hide
    fun isBlockedWord(text: String): Boolean =
        text.codePointCount(0, text.length) >= 2 && WordLists.isWordly(text) && text in blockedTexts()

    /** Rereads the user's words if the dictionaries differ from [before] ([UserAdditions.drop]): see [Engines.reload]. */
    fun reloadDictionaries(before: String) {
        if (userModel == null && freshModel == null || added.dictionaries() == before) return
        dropUser()
    }

    /** After the record closed: [user] is remade from it. */
    fun closed() {
        userModel = null
        freshModel = null
    }

    private var freshModel: UserModel? = null

    /** What a long-press delete takes off ([unAdd]), for its undo. */
    fun addedOf(text: String): List<Entry> = lists.addedWords.filter { it.text == text }

    fun blockedOf(text: String): List<Entry> = lists.blockedWords.filter { it.text == text }

    /** With their current list positions, for [reAdd]. */
    fun addedPlaces(entries: List<Entry>): List<IndexedValue<Entry>> {
        val all = lists.addedWords
        return entries.mapNotNull { e -> all.indexOf(e).takeIf { it >= 0 }?.let { IndexedValue(it, e) } }
    }

    /** Puts [entries] back where they were in 我的词 ([addedPlaces]), sessions kept as in [unAdd]. */
    fun reAdd(entries: List<IndexedValue<Entry>>) {
        lists.putBack(entries).forEach { userModel?.listByHand(it) }
    }

    /** Undoes a block, sessions kept as in [block]. */
    fun unblock(entries: List<Entry>) {
        blockedTexts = null
        lists.unblockAll(entries).forEach {
            userModel?.unblock(it)
            freshModel?.unblock(it)
        }
    }

    /** 「全部清空」: no added or blocked words, nor unreadable lines of their files; the model remade at next need. */
    fun clearLists() {
        lists.clear()
        englishBlocks.clear()
        blockedTexts = null
        dropUser()
        dropSessions()
    }

    /** 「不用我的输入习惯」: the fresh-install model plus enabled dictionaries (a setting) and blocks; no user words, counts or pairs. [attach] is not told: nothing of the record is in it. */
    fun freshUser(): UserModel = (freshModel ?: UserModel(pinyinData.dictionary, pinyinData.vocabulary, pinyinData.wordIndex, added.dictionary(), added.stale()).also { model ->
        freshModel = model
        lists.blockedWords.forEach { model.block(it) }
    }).also { it.held = held() }
}
