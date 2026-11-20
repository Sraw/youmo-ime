/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.data.WordIndex
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.StartWords
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * The decoder's lexicon of the user's: the words they own ([own], from the record), their dictionaries' words and
 * those added by hand, in a trie the decoder walks ([UserWords]); the blocked words; and the counts [UserScorer]
 * mixes in, filled by [HabitCounts]. Whether a word is the user's ([owns]) is never a count, so it does not fade.
 *
 * A word is its text and syllables ([Entry]), so it means the same after the dictionary is rebuilt with other ids.
 * [modelWord] gives each user word the model's word of its text, if any; a word only listed from the user's
 * dictionaries does not, till typed.
 */
class UserModel(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val index: WordIndex? = null,
    private val merged: DictionaryFile? = null,
    // compiled against another base: each of its words checked against this one
    private val mergedStale: Boolean = false,
) : UserWords, LayerPrior.PackLayers {

    class Entry(val text: String, val syllables: IntArray) {
        init {
            require(text.isNotEmpty() && syllables.isNotEmpty() && syllables.all { it in 0 until Syllables.count }) {
                "entry $text ${syllables.contentToString()}"
            }
        }

        override fun equals(other: Any?) = other is Entry && text == other.text && syllables.contentEquals(other.syllables)
        override fun hashCode() = text.hashCode() * 31 + syllables.contentHashCode()
        override fun toString() = "$text(${spelled()})"

        /** How it reads, syllables space-separated, as the record files it. */
        fun spelled(): String = Syllables.spelled(syllables)

        companion object {
            /** A sentence of [words] as one entry: blocked whole. */
            fun of(text: String, words: List<Entry>) = Entry(text, words.flatMap { it.syllables.asList() }.toIntArray())
        }

        /** How it reads, `'`-separated, as the lists show it. */
        fun apostrophed(): String = Syllables.apostrophed(syllables)
    }

    private val newWords = ArrayList<Entry>()
    // ids by text for newEntryOf: not to go through every id on a commit (ids holds the user's dictionaries too)
    private val newByText = HashMap<String, ArrayList<Int>>()
    // looked up once as the id is made, not on every score
    private var sameText = IntArray(INITIAL_WORDS)
    private val ids = HashMap<Entry, Int>()
    // to write each entry out again: a dictionary word as last typed
    internal val entries = HashMap<Int, Entry>()
    private val listed = HashSet<Int>()

    // English words pinyin spells: neither in [listedTexts] nor [listings], as the tables code no letters (each
    // learned word recoded them all)
    private val latinListed = HashSet<Int>()
    // in memory only, relisted on a start; an array, as modelWord asks it on every score
    private var byHand = BooleanArray(INITIAL_WORDS)
    // word-pack scores and layers by id: only ime-eval lists scored words; for the app one map miss per unknown word
    private val packScores = Counts()
    private val packLayers = Counts()

    private val trie = SyllableTrie()

    private val owned = HashSet<Int>()

    // imports by id from importBase, read from the record in place so 100 000 take little; offered from the input's
    // start alone except those in the trie as whole words ([promoted])
    private var imports: ImportedWords? = null
    private val importBase = vocabulary.size + IMPORT_OFFSET
    private val promoted = HashSet<Int>()
    private val importedByHand = HashSet<Int>()
    private val importSame = HashMap<Int, Int>()
    private var startTrie: ImportTrie? = null
    private var startTrieOf = -1

    // the merged dictionaries' words by id from mergedBase, read in place and walked in their own trie, never copied
    // (1.9 million objects ran a phone's heap out); a word the base also has keeps the base's id
    private val mergedBase = vocabulary.size + MERGED_OFFSET
    private val mergedByHand = HashSet<Int>()

    // unigrams under NO_WORD, bigrams under their first word
    private val counts = Counts()
    // bigrams to a word put in by text alone (a table's, handwriting's): 联想's, never the decoder's; they feed
    // [hasLearned], [probability] and [forEachAfter]
    private val textPairs = Counts()
    private val followers = HashMap<Int, HashSet<Int>>()
    private val predicted = HashMap<Int, HashMap<Int, Float>>()
    // asked on a key: ids holds hundreds of thousands of dictionary words after a large import
    private val typedTexts = HashSet<String>()

    // by reading, so a word made later is blocked as it is made; also by node and id as the decoder asks, with counts
    internal val blockedEntries = HashSet<Entry>()
    private val blockedReadings = HashSet<Long>()
    private val blockedIds = Counts()

    var total = 0f
        private set

    /** Goes up as a count, pair or [total] the decoders score with may change; their kept look-ahead holds while this stays. */
    var countsVersion = 0
        private set

    /** Goes up as [ownTexts] or the counts may change: for another input method to know its copy is stale. */
    var changes = 0
        internal set

    var listings = 0
        private set

    /** Goes up as a user word gets or loses an id: [known] and [idsOfText] hold while this does, or while [textsChangedSince] does not name the text. */
    val idsChanged: Int get() = idChanges.count

    private val idChanges = ChangedTexts()

    private fun wordChanged(text: String) = idChanges.word(text)

    internal fun entered(id: Int, entry: Entry) {
        if ((isImport(id) || isMerged(id)) && id !in entries) wordChanged(entry.text)
        val had = entries.put(id, entry)
        // the reading it had is still one it was typed at ([typedBelow])
        if (had != null && count(id) > 0f) typedNodes.alsoRead(id, had.syllables) else if (had == null && typed(id)) typedNodes.added(id)
    }

    fun textsChangedSince(at: Int): List<String>? = idChanges.since(at)

    /** [entry] as the user's own word: in the trie if the dictionary lacks it, its reading and score theirs, for good (only [disown] takes it away). */
    fun own(entry: Entry): Int {
        val id = idAsTyped(entry)
        if (owned.add(id)) {
            changes++
            ownedChanged(id)
        }
        if (isImport(id)) settle(id)
        return id
    }

    /** [entry] the user's no more: out of the trie unless their dictionaries or hand list have it, so retyped it is a new word. */
    fun disown(entry: Entry) {
        val id = ids[entry] ?: return
        if (!owned.remove(id)) return
        ownedChanged(id)
        changes++
        if (isImport(id)) settle(id) else if (id >= vocabulary.size && !isListed(id)) dropOwn(entry, id)
    }

    /** The imported words, each offered only where the input starts till owned, added or listed; read in place as they change ([importsChanged]). */
    fun attachImports(imports: ImportedWords) {
        this.imports = imports
        startTrieOf = -1
        importsChanged()
    }

    fun importsChanged() {
        // their places only grow today, but what a place holds is the imports' to say
        heldImports.clear()
        changes++
        listings++
        idChanges.all()
    }

    /** Whether [word] is only imported: offered where the input starts, never inside a sentence. */
    override fun startOnly(word: Int) = isImport(word) && imports?.has(word - importBase) == true && !chosen(word) && word !in listed

    /** Whether [entry] is only imported (see [startOnly]) and not since in the dictionary (a newer engine data). */
    fun isImported(entry: Entry): Boolean = idOf(entry)?.let(::startOnly) == true && inDictionary(entry) == NO_WORD

    override val startWords: StartWords?
        get() {
            val imports = imports?.takeIf { it.size > 0 } ?: return null
            // rebuilt as words come, not as they go: those gone [startOnly] leaves out
            if (startTrieOf != imports.grown) {
                // one the dictionary or the user's has as another word is that word
                startTrie = ImportTrie(imports, importBase) { place ->
                    val entry = Entry(imports.text(place), imports.syllables(place))
                    ids[entry].let { it != null && it != importBase + place } || inDictionary(entry) != NO_WORD || mergedId(entry) != NO_WORD
                }
                startTrieOf = imports.grown
            }
            return startTrie
        }

    private fun isImport(id: Int) = id >= importBase

    // one per id range: an exhaustive when, so a new kind is not missed somewhere
    private enum class Kind { BASE, USER, MERGED, IMPORT }

    private fun kindOf(id: Int): Kind = when {
        isImport(id) -> Kind.IMPORT
        isMerged(id) -> Kind.MERGED
        id >= vocabulary.size -> Kind.USER
        else -> Kind.BASE
    }

    override fun isMerged(word: Int) = word >= mergedBase && word < importBase

    private fun mergedId(entry: Entry): Int = merged?.find(entry)?.takeIf { it >= 0 }?.let { mergedBase + it } ?: NO_WORD

    private fun isListed(id: Int) = id in listed || isMerged(id)

    override val mergedWords: StartWords? = merged?.let { MergedTrie(it, mergedBase) }

    // the trie's places only: one past trieWords is a base word's, in no trie
    override fun forEachMergedOfText(text: String, visit: (Int) -> Unit) {
        merged?.let { file -> file.forEachOfText(text) { if (it < file.trieWords) visit(mergedBase + it) } }
    }

    /** Asked per key, so a bit set's emptiness, not a walk of [owned]. */
    override fun anyMergedChosen(): Boolean = !mergedChosen.isEmpty

    /** Asked per glide, so a count kept as the counts change. */
    override fun anyMergedCounted(): Boolean = mergedCounted > 0

    // by place: [chosen] asked past the decoder's limit without boxing an id
    private val mergedChosen = java.util.BitSet()

    private var mergedCounted = 0

    // [owns] is asked of every start arc each key, and a set's lookup boxes each id
    private val ownedBase = java.util.BitSet()

    // the nodes to typed or chosen words, for a walk over a lone 双拼 key ([typedBelow])
    internal val typedNodes = TypedNodes(dictionary, merged, vocabulary.size, mergedBase until importBase, { entries[it]?.syllables }) { visit ->
        counts.forEach { key, count -> if (first(key) == NO_WORD && count > 0f) visit(second(key)) }
        owned.forEach(visit)
        mergedByHand.forEach(visit)
    }

    override fun typed(word: Int): Boolean = count(word) > 0f || chosen(word) || typedNodes.kept(word)

    /** The pinned words with their pinned readings: [typed] while pinned. */
    internal fun setPinned(words: Map<Int, List<IntArray>>) = typedNodes.pinned(words).also { changes++ }

    override fun typedBelow(node: Int): Boolean = typedNodes.below(node)

    override fun nextTypedBelow(from: Int): Int = typedNodes.nextBelow(from)

    override fun mergedTypedBelow(node: Int): Boolean = typedNodes.mergedBelow(node)

    private fun ownedChanged(id: Int) {
        if (id in 0 until vocabulary.size) ownedBase[id] = id in owned
        mergedChoice(id)
    }

    private fun mergedChoice(id: Int) {
        if (isMerged(id)) mergedChosen[id - mergedBase] = id in owned || id in mergedByHand
        if (id in owned || id in mergedByHand) typedNodes.added(id) else typedNodes.stale = true
    }

    private fun counted(word: Int, was: Float, now: Float) {
        if ((was > 0f) == (now > 0f)) return
        if (now > 0f) typedNodes.added(word) else typedNodes.stale = true
        if (isMerged(word)) mergedCounted += if (now > 0f) 1 else -1
    }

    /** A merged dictionary's log10 score for [id] (≤ 0); NaN for none or a word the user chose (scored as theirs). */
    fun mergedScore(id: Int): Float = if (isMerged(id) && !chosen(id)) checkNotNull(merged).score(id - mergedBase) else Float.NaN

    private fun importId(entry: Entry): Int? = imports?.find(entry.text, entry.syllables)?.takeIf { it >= 0 }?.let { importBase + it }

    // made, merged or imported, without making one: id()'s order
    internal fun idOf(entry: Entry): Int? = ids[entry] ?: mergedId(entry).takeIf { it != NO_WORD } ?: importId(entry)

    // an imported word is in the trie while a whole word (own, listed, added), else offered from the start alone
    private fun settle(id: Int) {
        val whole = id in owned || id in listed || id in importedByHand
        if (whole == id in promoted) return
        val syllables = entries[id]?.syllables ?: imports?.syllables(id - importBase) ?: return
        if (whole) {
            promoted += id
            trie.add(syllables, id)
        } else {
            promoted -= id
            trie.remove(syllables, id)
        }
    }

    fun owns(word: Int): Boolean = if (word in 0 until vocabulary.size) ownedBase[word] else word in owned

    internal fun clearCounts() {
        counts.clear()
        textPairs.clear()
        followers.clear()
        predicted.clear()
        typedTexts.clear()
        total = 0f
        mergedCounted = 0
        typedNodes.forgetReadings()
        changes++
        countsVersion++
    }

    /** [word]'s count; [entry], its reading, kept for [forEachCount] if none yet (never a chosen word's own). */
    internal fun setCount(word: Int, count: Float, entry: Entry? = null) {
        if (entry != null && word !in entries) entered(word, entry) else if (entry != null && count > 0f) typedNodes.alsoRead(word, entry.syllables)
        val key = key(NO_WORD, word)
        val was = counts[key]
        if (was == count) return
        countsVersion++
        counts[key] = count
        counted(word, was, count)
        total += count - was
        if (was <= 0f && count > 0f) {
            changes++
            typedTexts += text(word, entries[word])
        }
    }

    /** The count of [next] after [prev]; [byText]: [next] was put in by text alone, for 联想 only. */
    internal fun setPair(prev: Int, next: Int, count: Float, byText: Boolean = false) {
        countsVersion++
        if (byText) textPairs[key(prev, next)] = count else counts[key(prev, next)] = count
        if (count > 0f) followers.getOrPut(prev) { HashSet() } += next
    }

    internal fun setPredicted(prev: Int, next: Int, count: Float) {
        if (count > 0f) predicted.getOrPut(prev) { HashMap() }[next] = count else predicted[prev]?.remove(next)
    }

    override fun forEachPredicted(prev: Int, visit: (word: Int, count: Float) -> Unit) {
        predicted[prev]?.forEach { (word, count) -> visit(word, count) }
    }

    private fun text(word: Int, entry: Entry?) = entry?.text ?: if (word < vocabulary.size) vocabulary.word(word) else text(word)

    /** Whether anything is learned of [words]: a count, a pair, or alone a word owned or made; a never-typed dictionary word has none, so no forget is offered. */
    fun hasLearned(words: List<Entry>): Boolean {
        val known = words.mapNotNull(::idOf).toSet()
        if (known.isEmpty()) return false
        if (words.size == 1 && (owns(known.first()) || isMade(known.first()))) return true
        // a pair's first word may never have been counted (read from the editor)
        return known.any { count(it) > 0f } || counts.any { key -> first(key) in known || second(key) in known } ||
            textPairs.any { key -> first(key) in known || second(key) in known }
    }

    internal fun forgetCounts(forgotten: Set<Int>) {
        countsVersion++
        counts.removeIf { first(it) in forgotten || second(it) in forgotten }
        predicted.keys.removeAll(forgotten)
        predicted.values.forEach { it.keys.removeAll(forgotten) }
        textPairs.removeIf { first(it) in forgotten || second(it) in forgotten }
        indexFollowers()
        indexTyped()
        recomputeTotal()
        typedNodes.forgetReadings(forgotten)
    }

    private fun recomputeTotal() {
        total = 0f
        mergedCounted = 0
        counts.forEach { key, count ->
            if (first(key) == NO_WORD) {
                total += count
                if (count > 0f && isMerged(second(key))) mergedCounted++
            }
        }
    }

    internal fun dropOwn(word: Entry, id: Int) {
        wordChanged(word.text)
        if (owned.remove(id)) ownedChanged(id)
        trie.remove(word.syllables, id)
        ids.remove(word)
        entries.remove(id)
    }

    /** Calls [word] for every counted word, then [pair] for every pair, each above 0; words counted by text alone have no entry and are left out. */
    fun forEachCount(word: (Entry, Float) -> Unit, pair: (Entry, Entry, Float) -> Unit) {
        // twice through: no copy of every pair held while compacting
        counts.forEach { key, count ->
            val entry = entries[second(key)]
            if (first(key) == NO_WORD && count > 0f && entry != null) word(entry, count)
        }
        counts.forEach { key, count ->
            val before = if (first(key) == NO_WORD || count <= 0f) null else entries[first(key)]
            val entry = entries[second(key)]
            if (before != null && entry != null) pair(before, entry, count)
        }
    }

    /** The chance the user types [word] after [prev] from the counts alone, backing off to the word's own share where [prev] was seldom typed. */
    override fun probability(prev: Int, word: Int): Float = probability(prev, word, textPairs[key(prev, word)])

    /** [probability] without the pairs to text-only words, which are 联想's (see [setPair]). */
    fun decoderProbability(prev: Int, word: Int): Float = probability(prev, word, 0f)

    private fun probability(prev: Int, word: Int, byText: Float): Float {
        val count = counts[key(NO_WORD, word)]
        if (count == 0f) return 0f
        val alone = count / (total + UNSEEN)
        if (prev == NO_WORD) return alone
        val before = counts[key(NO_WORD, prev)]
        if (before == 0f) return alone
        return (counts[key(prev, word)] + byText + PRIOR * alone) / (before + PRIOR)
    }

    override fun count(word: Int): Float = counts[key(NO_WORD, word)]

    override fun forEachAfter(prev: Int, visit: (Int) -> Unit) {
        if (prev == NO_WORD) return
        followers[prev]?.forEach { if (counts[key(prev, it)] > 0f || textPairs[key(prev, it)] > 0f) visit(it) }
    }

    private fun indexFollowers() {
        followers.clear()
        counts.forEach { key, _ -> if (first(key) != NO_WORD) followers.getOrPut(first(key)) { HashSet() } += second(key) }
        textPairs.forEach { key, _ -> followers.getOrPut(first(key)) { HashSet() } += second(key) }
    }

    private fun indexTyped() {
        typedTexts.clear()
        counts.forEach { key, count -> if (first(key) == NO_WORD && count > 0f) entries[second(key)]?.let { typedTexts += it.text } }
    }

    fun id(entry: Entry): Int {
        ids[entry]?.let { return it }
        var id = inDictionary(entry)
        if (id == NO_WORD) id = mergedId(entry)
        if (id == NO_WORD) id = importId(entry) ?: NO_WORD
        if (id == NO_WORD) {
            id = vocabulary.size + newWords.size
            newWords += entry
            newByText.getOrPut(entry.text) { ArrayList(1) } += id
            if (newWords.size > sameText.size) {
                sameText = sameText.copyOf(sameText.size * 2)
                byHand = byHand.copyOf(sameText.size)
            }
            sameText[newWords.size - 1] = index?.find(entry.text) ?: NO_WORD
            changes++
            wordChanged(entry.text)
            trie.add(entry.syllables, id)
            entries[id] = entry
        }
        ids[entry] = id
        if (entry in blockedEntries && id >= vocabulary.size) resolveBlock(entry, id, add = true)
        return id
    }

    // as typed: a dictionary word is then read so, not as listed or blocked
    internal fun idAsTyped(entry: Entry): Int = id(entry).also { entered(it, entry) }

    /** Lists [entry] from the user's dictionaries: forgetting drops what was learned but leaves it typable, as libime does. A dictionary word is listed too, so tables offer a hand-added word (鲸园). */
    fun list(entry: Entry): Int = id(entry).also {
        if (listed.add(it) || latinListed.remove(it)) listings++
        if (isImport(it)) settle(it)
    }

    fun listLatin(entry: Entry): Int = id(entry).also { if (listed.add(it)) latinListed += it }

    /** [list]s [entry] as added by hand: scored as the model's word of its text ([modelWord]). */
    fun listByHand(entry: Entry): Int = list(entry).also {
        when (kindOf(it)) {
            Kind.IMPORT -> importedByHand += it
            Kind.MERGED -> {
                mergedByHand += it
                mergedChoice(it)
            }
            Kind.USER -> byHand[it - vocabulary.size] = true
            // the dictionary's own word stays the dictionary's, but the user named it: typed
            Kind.BASE -> typedNodes.byHand(it, entry.syllables, added = true)
        }
    }

    /** [entry] no longer added by hand: listed till the next start, but scored as unknown unless owned. */
    fun unlistByHand(entry: Entry) {
        ids[entry]?.let {
            when (kindOf(it)) {
                Kind.IMPORT -> importedByHand -= it
                Kind.MERGED -> mergedByHand -= it
                Kind.USER -> byHand[it - vocabulary.size] = false
                Kind.BASE -> typedNodes.byHand(it, entry.syllables, added = false)
            }
            mergedChoice(it)
        }
    }

    /** [entry], added by hand, deleted at once: unlisted and out of the trie unless owned, so not offered again. */
    fun unlist(entry: Entry) {
        val id = ids[entry] ?: return
        unlistByHand(entry)
        if (!listed.remove(id)) return
        if (!latinListed.remove(id)) listings++
        changes++
        // a merged dictionary's word stays its own, as libime leaves its extra dictionaries be
        if (isImport(id)) settle(id) else if (id >= vocabulary.size && !isMerged(id) && id !in owned) dropOwn(entry, id)
    }

    /** Lists a [WordPack] word of [layer], scored [score] if the model lacks it; a model word (id below [modelWords]) keeps the model's score. Only ime-eval calls it, to evaluate a candidate pack. */
    fun list(entry: Entry, score: Float, layer: Int, modelWords: Int = 0): Int {
        require(score <= 0f && score.isFinite()) { "score $score" }
        val id = list(entry)
        if (id >= modelWords) {
            packScores[id.toLong()] = score
            packLayers[id.toLong()] = layer.toFloat()
        }
        return id
    }

    /** The model's word for [word]: for a user word whose text the model has (added or typed), that word, so it scores and contexts as it; else [word]. An only-imported word stays unknown till picked. */
    override fun modelWord(word: Int): Int {
        if (word < vocabulary.size) return word
        if (isImport(word) || isMerged(word)) {
            if (!chosen(word)) return word
            val same = importSame.getOrPut(word) { index?.find(text(word)) ?: NO_WORD }
            return if (same == NO_WORD) word else same
        }
        val same = sameText[word - vocabulary.size]
        return if (same == NO_WORD || !chosen(word)) word else same
    }

    fun addedByHand(word: Int): Boolean =
        when (kindOf(word)) {
            Kind.IMPORT -> word in importedByHand
            Kind.MERGED -> word in mergedByHand
            Kind.USER -> byHand[word - vocabulary.size]
            Kind.BASE -> false
        }

    /** Whether [word] is the user's choice (owned or added by hand): the one test of whether its reading and score are the user's. Never a count, which fades. */
    override fun chosen(word: Int): Boolean = if (isMerged(word)) mergedChosen[word - mergedBase] else addedByHand(word) || owns(word)

    /** A pack's log10 probability for [id], or NaN; always NaN in the app, which lists no scored words. */
    fun score(id: Int): Float = packScores.getOrDefault(id.toLong(), Float.NaN)

    override fun layerOf(word: Int): Int = packLayers.getOrDefault(word.toLong(), -1f).toInt()

    /** Where [entry] comes from as the record files it: [Row.ADDED], [Row.IMPORTED], [Row.DICTIONARY], else [Row.NONE]. What the user typed is not asked: a made word would hide its pending step. */
    fun origin(entry: Entry): Int {
        val id = idOf(entry)
        return when {
            id != null && addedByHand(id) -> Row.ADDED
            (id != null && isListed(id)) || inDictionary(entry) != NO_WORD || mergedId(entry) != NO_WORD -> Row.DICTIONARY
            id != null && startOnly(id) -> Row.IMPORTED
            else -> Row.NONE
        }
    }

    internal fun isMade(id: Int) = id >= vocabulary.size && !isListed(id) && !isImport(id)

    fun known(entry: Entry): Int = if (knows(entry)) id(entry) else NO_WORD

    /** The ids of [text], user's and dictionary's: those a pair after it goes on from. */
    fun idsOfText(text: String): List<Int> {
        val out = ArrayList<Int>(2)
        index?.find(text)?.takeIf { it >= 0 }?.let(out::add)
        newByText[text]?.forEach { id -> if (entries[id]?.let { it in ids } == true && id !in out) out += id }
        imports?.placesOf(text)?.forEach { place -> (importBase + place).let { if (it in entries && it !in out) out += it } }
        // a merged word's, typed or not: one committed in a table or handwriting is paired under it
        merged?.let { file ->
            file.forEachOfText(text) { place -> (mergedBase + place).let { if (place < file.trieWords && it !in out) out += it } }
        }
        return out
    }

    private fun inDictionary(entry: Entry): Int {
        val node = dictionary.find(entry.syllables)
        if (node < 0) return NO_WORD
        for (i in 0 until dictionary.wordCount(node)) {
            if (vocabulary.word(dictionary.word(node, i)) == entry.text) return dictionary.word(node, i)
        }
        return NO_WORD
    }

    /** Blocks [entry] so it is never offered; whether there is such a word (one never typed and not in the dictionary is none). */
    fun block(entry: Entry): Boolean {
        if (entry in blockedEntries) return true
        blockedTextSet = null
        // its id first: one given it now while blocked would be blocked twice
        val id = known(entry)
        blockedEntries += entry
        changes++
        if (id == NO_WORD) return false
        resolveBlock(entry, id, add = true)
        return true
    }

    private var blockedTextSet: Set<String>? = null

    fun blocksText(text: String): Boolean =
        text in (blockedTextSet ?: blockedEntries.mapTo(HashSet()) { it.text }.also { blockedTextSet = it })

    /** Whether the sentence [text] is blocked whole as [words] read it (no id stands for it). */
    fun blocksSentence(text: String, words: () -> List<Entry>?): Boolean =
        blocksText(text) && words()?.let { Entry.of(text, it) in blockedEntries } == true

    fun unblock(entry: Entry) {
        if (!blockedEntries.remove(entry)) return
        blockedTextSet = null
        changes++
        val id = known(entry)
        if (id != NO_WORD) resolveBlock(entry, id, add = false)
    }

    // a dictionary word is blocked at its node, one reading of several
    private fun resolveBlock(entry: Entry, id: Int, add: Boolean) {
        if (id < vocabulary.size) {
            val reading = key(dictionary.find(entry.syllables), id)
            if (add) blockedReadings += reading else blockedReadings -= reading
        }
        blockedIds.add(id.toLong(), if (add) 1f else -1f)
    }

    override fun blocked(word: Int, node: Int) =
        isHeld(word) || blockedIds[word.toLong()] > 0f && (word >= vocabulary.size || key(node, word) in blockedReadings) ||
            mergedStale && inStaleBase(word)

    override fun blockedAnyhow(word: Int) = isHeld(word) || blockedIds[word.toLong()] > 0f || mergedStale && inStaleBase(word)

    /** The words 生僻字屏蔽 holds back: the dictionary's by bit, the rest by text, each asked once. A new one counts as a [changes], so what was read of this model is read anew. */
    var held: HeldWords = HeldWords.NONE
        set(value) {
            if (value === field) return
            field = value
            heldUser.clear()
            heldMerged.clear()
            heldImports.clear()
            changes++
        }

    // by place within their kind: ids from 2^27 or 2^28 would make a bitmap by id 16 or 32 MB
    private val heldUser = HeldCache()
    private val heldMerged = HeldCache()
    private val heldImports = HeldCache()

    private fun isHeld(word: Int): Boolean {
        val h = held
        if (h === HeldWords.NONE || word < 0) return false
        return when (kindOf(word)) {
            Kind.BASE -> h.has(word)
            Kind.USER -> heldUser.of(word - vocabulary.size, { text(word) }, h.gate)
            Kind.MERGED -> heldMerged.of(word - mergedBase, { text(word) }, h.gate)
            Kind.IMPORT -> heldImports.of(word - importBase, { text(word) }, h.gate)
        }
    }

    // a stale file's word the base has now is the base's alone; looked up once a place, only while the recompile is owed
    private val staleChecked = java.util.BitSet()
    private val staleInBase = java.util.BitSet()

    private fun inStaleBase(word: Int): Boolean {
        if (!isMerged(word)) return false
        val place = word - mergedBase
        if (!staleChecked[place]) {
            staleChecked.set(place)
            if (inDictionary(checkNotNull(merged).entry(place)) != NO_WORD) staleInBase.set(place)
        }
        return staleInBase[place]
    }

    /** Predictions the user turned off after a word, from the record. */
    var predictionsOff: (prev: String, next: String) -> Boolean = { _, _ -> false }

    override fun notPredicted(prev: String, next: String) = predictionsOff(prev, next)

    val continuations = Continuations()

    /** The rests of whole phrases [before] ends a start of, none turned off, blocked or with a held character. */
    fun continuationsAfter(before: String, starts: IntArray, last: String, limit: Int = Continuations.LIMIT): List<Continuations.Found> =
        continuations.find(before, starts, last, limit) { whole ->
            !predictionsOff("", whole) && blockedEntries.none { it.text in whole } && !held.gate.holds(whole)
        }

    fun ownWords(): List<Pair<Entry, Float>> = owned.mapNotNull { id -> entries[id]?.takeIf { it !in blockedEntries }?.let { it to counts[key(NO_WORD, id)] } }

    /** The texts of the user's dictionaries, packs and added words, blocked ones too, for the other input methods (they check blocks as they offer). */
    fun listedTexts(): List<String> {
        val out = listed.filterNot(latinListed::contains).mapNotNullTo(LinkedHashSet()) { id -> entries[id]?.text ?: if (id < vocabulary.size) vocabulary.word(id) else if (isMerged(id)) text(id) else null }
        // imports too: the tables offer them as a dictionary's, after their own, never at a code alone
        imports?.let { i -> i.places().mapTo(out, i::text) }
        return out.toList()
    }

    val dictionaryFile: DictionaryFile? get() = merged

    /** The texts of the user's own words, not blocked, for the other input methods. */
    fun ownTexts(): List<String> = ownWords().mapTo(LinkedHashSet()) { it.first.text }.toList()

    fun entriesOf(text: String): List<Entry> = ids.keys.filter { it.text == text }

    /** The reading of [text] typed most among made words the dictionary lacks, still known; null for none. */
    fun newEntryOf(text: String): Entry? = newByText[text]
        ?.map { newWords[it - vocabulary.size] }
        ?.filter { it in ids }
        ?.maxByOrNull { counts[key(NO_WORD, ids.getValue(it))] }
        ?: mergedEntryOf(text)

    // a merged dictionary's best-ranked reading of text, never typed in pinyin
    private fun mergedEntryOf(text: String): Entry? {
        val file = merged ?: return null
        var best = -1
        file.forEachOfText(text) { place -> if (place < file.trieWords && (best < 0 || file.score(place) > file.score(best))) best = place }
        return if (best < 0) null else file.entry(best)
    }

    fun typed(text: String): Boolean = text in typedTexts

    fun hasText(text: String): Boolean = ids.keys.any { it.text == text }

    fun knows(entry: Entry): Boolean =
        ids[entry]?.let { !gone(it) } == true || inDictionary(entry) != NO_WORD || mergedId(entry) != NO_WORD || importId(entry) != null

    // an imported word's id whose batch was undone or word deleted, and nothing else the user's: no word, so the
    // same list imports it again
    private fun gone(id: Int) = isImport(id) && imports?.has(id - importBase) != true && id !in owned && id !in listed && id !in importedByHand

    /** Whether [text] is a dictionary word in any reading, or a user dictionary's or added one: what an import leaves out by text. */
    fun knowsText(text: String): Boolean =
        index?.find(text)?.let { it >= 0 } == true || newByText[text]?.any { it in listed } == true || merged?.hasText(text) == true

    /** How many words the user added, forgotten ones too: no id is given twice. */
    val size: Int get() = newWords.size

    override fun child(node: Int, syllable: Int) = trie.child(node, syllable)

    override fun childCount(node: Int) = trie.childCount(node)

    override fun wordCount(node: Int) = trie.wordCount(node)

    override fun word(node: Int, index: Int) = trie.word(node, index)

    override fun text(word: Int) = when (kindOf(word)) {
        Kind.IMPORT -> checkNotNull(imports).text(word - importBase)
        Kind.MERGED -> checkNotNull(merged).text(word - mergedBase)
        Kind.USER, Kind.BASE -> newWords[word - vocabulary.size].text
    }

    // the user's reading only while chosen: a forgotten dictionary word's kept entry would learn it back at that
    // reading, and an imported word's possibly wrong reading would take the dictionary's arc (行人)
    override fun reading(word: Int) = if (chosen(word)) {
        entries[word]?.syllables ?: when (kindOf(word)) {
            Kind.IMPORT -> imports?.syllables(word - importBase)
            Kind.MERGED -> merged?.syllables(word - mergedBase)
            Kind.USER, Kind.BASE -> newWords.getOrNull(word - vocabulary.size)?.syllables
        }
    } else {
        null
    }

    internal fun add(first: Int, second: Int, count: Float) {
        val was = counts[key(first, second)]
        if (first == NO_WORD && was == 0f) changes++
        countsVersion++
        counts.add(key(first, second), count)
        if (first == NO_WORD) counted(second, was, counts[key(first, second)])
        if (first == NO_WORD) total += count else followers.getOrPut(first) { HashSet() } += second
        if (first == NO_WORD && counts[key(first, second)] > 0f) entries[second]?.let { typedTexts += it.text }
    }

    private fun key(first: Int, second: Int) = (first.toLong() shl Int.SIZE_BITS) or (second.toLong() and 0xffffffffL)
    private fun first(key: Long) = (key shr Int.SIZE_BITS).toInt()
    private fun second(key: Long) = key.toInt()

    companion object {
        internal const val CHANGED_KEPT = 256

        private const val INITIAL_WORDS = 16
        // past the dictionary's: no user makes 2^28 words
        private const val IMPORT_OFFSET = 1 shl 28
        // below the imported: DictionaryCompiler's MAX_WORDS is well short of the 2^27 between
        private const val MERGED_OFFSET = 1 shl 27
        // counts the model pretends were spent on words not yet typed
        internal const val UNSEEN = 20f
        // how much a word's own share stands in for pairs not yet seen after prev
        internal const val PRIOR = 2f
    }
}

private class HeldCache {
    private val asked = java.util.BitSet()
    private val held = java.util.BitSet()

    fun of(place: Int, text: () -> String, gate: CharGate): Boolean {
        if (!asked[place]) {
            asked.set(place)
            if (gate.holds(text())) held.set(place)
        }
        return held[place]
    }

    fun clear() {
        asked.clear()
        held.clear()
    }
}

private class MergedTrie(private val file: DictionaryFile, private val base: Int) : StartWords {
    override fun child(node: Int, syllable: Int) = file.child(node, syllable)
    override fun firstChild(node: Int) = file.firstChild(node)
    override fun childCount(node: Int) = file.childCount(node)
    override fun syllable(node: Int) = file.nodeSyllable(node)
    override fun wordCount(node: Int) = file.wordCount(node)
    override fun word(node: Int, index: Int) = base + file.word(node, index)
}

private class ChangedTexts {
    var count = 0
        private set
    private val texts = ArrayDeque<String>()

    fun word(text: String) {
        count++
        texts.addLast(text)
        if (texts.size > UserModel.CHANGED_KEPT) texts.removeFirst()
    }

    fun all() {
        count += UserModel.CHANGED_KEPT + 1
        texts.clear()
    }

    fun since(at: Int): List<String>? {
        val n = count - at
        return if (at < 0 || n > texts.size) null else texts.subList(texts.size - n, texts.size)
    }
}
