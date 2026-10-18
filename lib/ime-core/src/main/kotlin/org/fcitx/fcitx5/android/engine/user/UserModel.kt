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
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * What the user typed: how often each word was committed and after which word, and the words
 * they put together from pieces, which the dictionary lacks. [UserScorer] mixes the counts into
 * the n-gram model's scores; the decoder finds the new words through [UserWords].
 *
 * A word is its text and the syllables it was read as, so it means the same after the
 * dictionary is rebuilt with other ids: that is what [Entry] is and what the journal keeps.
 *
 * Counts halve when their sum passes [limit], dropping those that fall below a quarter: what
 * was typed long ago gives way to what is typed now, and the counts stay bounded. A word the user
 * made keeps a quarter, though, for it is theirs to type however seldom they do. A new word
 * whose count is gone, forgotten in a longer candidate, stays in the trie, scored as the model's
 * unknown word, until the next start: few enough not to be worth rebuilding the trie for.
 *
 * [index], the model's words by text, gives each of the user's words the model's word of its text
 * if it has one ([modelWord]): a word of the dictionary's typed, or added by hand ([listByHand]), at a
 * reading of the user's. One only listed from the user's dictionaries does not, till typed: it is
 * not [chosen], so neither its reading nor its score is the user's yet.
 */
class UserModel(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val limit: Float = DEFAULT_LIMIT,
    private val index: WordIndex? = null,
) : UserWords, LayerPrior.PackLayers {
    init {
        require(limit > 0f) { "limit $limit" }
    }

    /** A word committed: its [text], read as [syllables]. */
    class Entry(val text: String, val syllables: IntArray) {
        init {
            require(text.isNotEmpty() && syllables.isNotEmpty() && syllables.all { it in 0 until Syllables.count }) {
                "entry $text ${syllables.contentToString()}"
            }
        }

        override fun equals(other: Any?) = other is Entry && text == other.text && syllables.contentEquals(other.syllables)
        override fun hashCode() = text.hashCode() * 31 + syllables.contentHashCode()
        override fun toString() = text + syllables.joinToString(" ", "(", ")") { Syllables.spelling(it) }
    }

    /** Hears of each sentence learned, and of words forgotten, to keep it: see [UserStore]. */
    fun interface Journal {
        fun record(prev: Entry?, sentence: List<Entry>)

        fun forgot(words: List<Entry>) = Unit
    }

    var journal: Journal? = null

    // words the dictionary lacks, by id from vocabulary.size up
    private val newWords = ArrayList<Entry>()
    // their ids by text, for newEntryOf: not to go through every id on a commit (ids holds the user's dictionaries too)
    private val newByText = HashMap<String, ArrayList<Int>>()
    // the model's word of each one's text, NO_WORD for none: looked up once, as the id is made, not on every score
    private var sameText = IntArray(INITIAL_WORDS)
    private val ids = HashMap<Entry, Int>()
    // the entry each word was learned as, to write it out again: one of the dictionary's as last typed
    private val entries = HashMap<Int, Entry>()
    // those of them from the user's dictionaries: see list
    private val listed = HashSet<Int>()
    // those of them the user added by hand (listByHand), by id from vocabulary.size up as sameText: in
    // memory only, listed again from their list on a start; an array, as modelWord asks on every score
    private var byHand = BooleanArray(INITIAL_WORDS)
    // from a word pack: the score of a word the model lacks, and the layer (exact as a float), by id.
    // Only ime-eval lists scored words (a candidate pack evaluated); the app no longer does, and for
    // it these cost one map miss per word the model lacks
    private val packScores = Counts()
    private val packLayers = Counts()

    // the new words, by their syllables
    private val trie = SyllableTrie()

    // unigrams under NO_WORD, bigrams under their first word
    private val counts = Counts()
    // the second words of the bigrams, by the first: to find them without going through every count
    private val followers = HashMap<Int, HashSet<Int>>()
    // the texts of the words counted, for typed, asked on a key: ids holds every word of the user's
    // dictionaries as well, hundreds of thousands after a large import
    private val typedTexts = HashSet<String>()

    // the words the user blocked (see block): as they read, so a word made later is blocked as
    // it is made; and as the decoder asks, the dictionary's by node and id, the user's by id, with
    // how many readings of each are blocked
    private val blockedEntries = HashSet<Entry>()
    private val blockedReadings = HashSet<Long>()
    private val blockedIds = Counts()

    /** The sum of the words' counts. */
    var total = 0f
        private set

    /**
     * Goes up as [ownTexts] may change: a word made, typed a first time, forgotten, blocked or
     * offered again, counts halved. For another input method to know its copy of them is stale.
     */
    var changes = 0
        private set

    /** Goes up as [listedTexts] change: a word of the user's dictionaries listed. */
    var listings = 0
        private set

    /**
     * Counts [sentence], each word and each pair, the first after [prev] if given (the word
     * committed before it) and still known: one forgotten since is not made a word again. Words
     * of the sentence the dictionary lacks become the user's.
     *
     * @return the words' ids
     */
    fun learn(prev: Entry?, sentence: List<Entry>): IntArray {
        require(sentence.isNotEmpty()) { "nothing to learn" }
        val words = IntArray(sentence.size) { idAsTyped(sentence[it]) }
        // a word before whose reading is not kept yet takes this one: its pair is written out with it
        var before = if (prev != null && knows(prev)) id(prev).also { entries.getOrPut(it) { prev } } else NO_WORD
        for (w in words) {
            add(NO_WORD, w, 1f)
            if (before != NO_WORD) add(before, w, 1f)
            before = w
        }
        if (total > limit) {
            // dropped, a word the user made (or brought from libime) would be gone at the next start
            val own = entries.keys.filter { isOwn(it) && counts[key(NO_WORD, it)] > 0f }
            counts.scale(HALF, MIN_COUNT)
            for (id in own) if (counts[key(NO_WORD, id)] == 0f) counts.add(key(NO_WORD, id), MIN_COUNT)
            indexFollowers()
            indexTyped()
            // a word typed once long ago may be gone from ownTexts
            changes++
            recomputeTotal()
        }
        // after halving: the journal may compact, writing out the counts as they now are
        journal?.record(prev, sentence)
        return words
    }

    /**
     * Forgets [words], a candidate the user asked to: their counts and every pair they are in.
     * One of the user's own words, forgotten alone, is gone from the trie too, as libime drops
     * it from its user dictionary. One [list]ed stays typeable, its counts gone; so does one in a
     * longer candidate, till the next start.
     */
    fun forget(words: List<Entry>) {
        val forgotten = words.mapNotNull { ids[it] }.toSet()
        if (forgotten.isEmpty()) return
        forgetCounts(forgotten)
        val word = words.singleOrNull()
        val id = word?.let { ids[it] } ?: NO_WORD
        if (word != null && isOwn(id)) dropOwn(word, id)
        changes++
        journal?.forgot(words)
    }

    /**
     * [forget]s each of [words] alone, as the settings take words off their list: each of the
     * user's own is gone from the trie, the counts gone through once for them all, and each is
     * journaled alone, so it reads back so.
     */
    fun forgetEach(words: Collection<Entry>) {
        val known = words.filter { it in ids }.associateWith { ids.getValue(it) }
        if (known.isEmpty()) return
        forgetCounts(known.values.toSet())
        for ((word, id) in known) if (isOwn(id)) dropOwn(word, id)
        changes++
        for (word in known.keys) journal?.forgot(listOf(word))
    }

    private fun forgetCounts(forgotten: Set<Int>) {
        counts.removeIf { first(it) in forgotten || second(it) in forgotten }
        indexFollowers()
        indexTyped()
        recomputeTotal()
    }

    private fun recomputeTotal() {
        total = 0f
        counts.forEach { key, count -> if (first(key) == NO_WORD) total += count }
    }

    private fun dropOwn(word: Entry, id: Int) {
        trie.remove(word.syllables, id)
        // learned again, it is a new word, put back in the trie
        ids.remove(word)
        entries.remove(id)
    }

    /** Adds [count] to [entry] as a word; for counts kept, as compaction writes them. */
    fun addCount(entry: Entry, count: Float) = add(NO_WORD, idAsTyped(entry), count)

    /** Adds [count] to [second] after [first]. */
    fun addCount(first: Entry, second: Entry, count: Float) = add(idAsTyped(first), idAsTyped(second), count)

    /** Calls [word] with every word counted, its count above 0. */
    fun forEachWordCount(word: (Entry, Float) -> Unit) {
        counts.forEach { key, count -> if (first(key) == NO_WORD && count > 0f) word(entries.getValue(second(key)), count) }
    }

    /** Calls [word] with every word counted, then [pair] with every pair. */
    fun forEachCount(word: (Entry, Float) -> Unit, pair: (Entry, Entry, Float) -> Unit) {
        // twice through the counts, words then pairs: no copy of every pair held while compacting
        counts.forEach { key, count -> if (first(key) == NO_WORD) word(entries.getValue(second(key)), count) }
        counts.forEach { key, count ->
            if (first(key) != NO_WORD) pair(entries.getValue(first(key)), entries.getValue(second(key)), count)
        }
    }

    /**
     * The chance the user types [word] after [prev] ([NO_WORD] for none), from the counts alone:
     * the pair's share of what followed [prev], leaning on the word's own share when [prev] was
     * seldom typed. 0 for a word never committed, which is nearly all the decoder asks about.
     */
    override fun probability(prev: Int, word: Int): Float {
        val count = counts[key(NO_WORD, word)]
        if (count == 0f) return 0f
        val alone = count / (total + UNSEEN)
        if (prev == NO_WORD) return alone
        val before = counts[key(NO_WORD, prev)]
        if (before == 0f) return alone
        return (counts[key(prev, word)] + PRIOR * alone) / (before + PRIOR)
    }

    override fun count(word: Int): Float = counts[key(NO_WORD, word)]

    override fun forEachAfter(prev: Int, visit: (Int) -> Unit) {
        if (prev == NO_WORD) return
        followers[prev]?.forEach { if (counts[key(prev, it)] > 0f) visit(it) }
    }

    private fun indexFollowers() {
        followers.clear()
        counts.forEach { key, _ -> if (first(key) != NO_WORD) followers.getOrPut(first(key)) { HashSet() } += second(key) }
    }

    private fun indexTyped() {
        typedTexts.clear()
        counts.forEach { key, count -> if (first(key) == NO_WORD && count > 0f) entries[second(key)]?.let { typedTexts += it.text } }
    }

    /** The id of [entry]: the dictionary's word if it has it so read, else the user's. */
    fun id(entry: Entry): Int {
        ids[entry]?.let { return it }
        var id = inDictionary(entry)
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
            trie.add(entry.syllables, id)
            entries[id] = entry
        }
        ids[entry] = id
        if (entry in blockedEntries && id >= vocabulary.size) resolveBlock(entry, id, add = true)
        return id
    }

    // the id of entry as typed: a word of the dictionary's is then read so, not as it was listed or blocked
    private fun idAsTyped(entry: Entry): Int = id(entry).also { entries[it] = entry }

    /**
     * [id] of a word from the user's dictionaries: one they did not make, so forgetting it drops
     * what was learned of it but leaves it to type, as libime leaves its extra dictionaries be.
     */
    fun list(entry: Entry): Int = id(entry).also { if (it >= vocabulary.size && listed.add(it)) listings++ }

    /** [list]s [entry] as a word the user added by hand: scored as the model's of its text ([modelWord]). */
    fun listByHand(entry: Entry): Int = list(entry).also { if (it >= vocabulary.size) byHand[it - vocabulary.size] = true }

    /**
     * [entry] no longer added by hand: listed still, till the next start, but scored as the model's
     * unknown word, unless the user typed it ([chosen]).
     */
    fun unlistByHand(entry: Entry) {
        ids[entry]?.let { if (it >= vocabulary.size) byHand[it - vocabulary.size] = false }
    }

    /**
     * [list]s a word of a [WordPack], of [layer] (an index [org.fcitx.fcitx5.android.engine.lattice.LayerPrior]
     * gave its name) and scored [score] if the model lacks it: see [score] and [layerOf]. A word
     * of the model's (an id below [modelWords]) keeps the model's score and the dictionary's
     * layer. Listed twice, the later holds: a pack read again replaces what it said before.
     *
     * Only ime-eval calls it, to evaluate a candidate pack (lib/ime-dict-tool/engine-data.sh): the
     * app no longer lists scored words, its built-in new words being a layer of the dictionary.
     */
    fun list(entry: Entry, score: Float, layer: Int, modelWords: Int = 0): Int {
        require(score <= 0f && score.isFinite()) { "score $score" }
        val id = list(entry)
        if (id >= modelWords) {
            packScores[id.toLong()] = score
            packLayers[id.toLong()] = layer.toFloat()
        }
        return id
    }

    /**
     * The model's word for [word]: the model's of the same text for one of the user's whose text the
     * model has, added by hand or typed, so it scores as that word and as the context of those after
     * it ([UserScorer]); else [word] itself. A word only imported, from a dictionary or a pack, is
     * not the user's choice yet: the model's unknown word (with its pack score) till picked.
     */
    override fun modelWord(word: Int): Int {
        if (word < vocabulary.size) return word
        val same = sameText[word - vocabulary.size]
        return if (same == NO_WORD || !chosen(word)) word else same
    }

    /**
     * Whether [word] is the user's choice: typed (counted), or added by hand. The one test of
     * whether a word's reading ([reading]) and score ([modelWord]) are the user's: one only
     * imported, from a dictionary or a pack, is not, till picked.
     */
    fun chosen(word: Int): Boolean = (word >= vocabulary.size && byHand[word - vocabulary.size]) || count(word) > 0f

    /**
     * The log10 probability a pack gave word [id], or NaN: [UserScorer] uses it where the model has
     * no word. Always NaN in the app, which lists no scored words (only ime-eval does).
     */
    fun score(id: Int): Float = packScores.getOrDefault(id.toLong(), Float.NaN)

    /** The layer a pack put word [id] in, -1 if none did (always, in the app: see [score]). */
    override fun layerOf(id: Int): Int = packLayers.getOrDefault(id.toLong(), -1f).toInt()

    // a word the user made, not the dictionary's nor one of their dictionaries'
    private fun isOwn(id: Int) = id >= vocabulary.size && id !in listed

    // the id of entry if it has one, without making it the user's word
    private fun known(entry: Entry): Int = if (entry in ids || inDictionary(entry) != NO_WORD) id(entry) else NO_WORD

    private fun inDictionary(entry: Entry): Int {
        val node = dictionary.find(entry.syllables)
        if (node < 0) return NO_WORD
        for (i in 0 until dictionary.wordCount(node)) {
            if (vocabulary.word(dictionary.word(node, i)) == entry.text) return dictionary.word(node, i)
        }
        return NO_WORD
    }

    /**
     * Blocks [entry], the dictionary's word or one of the user's, so it is never offered; whether
     * there is such a word. One the dictionary lacks and the user never typed is none: nothing
     * offers it to block.
     */
    fun block(entry: Entry): Boolean {
        if (!blockedEntries.add(entry)) return true
        changes++
        val id = known(entry)
        if (id == NO_WORD) return false
        resolveBlock(entry, id, add = true)
        return true
    }

    /** Offers [entry] again, if [block]ed. */
    fun unblock(entry: Entry) {
        if (!blockedEntries.remove(entry)) return
        changes++
        val id = known(entry)
        if (id != NO_WORD) resolveBlock(entry, id, add = false)
    }

    // a word of the dictionary's is blocked at its node, one reading of several; the user's has one
    private fun resolveBlock(entry: Entry, id: Int, add: Boolean) {
        if (id < vocabulary.size) {
            val reading = key(dictionary.find(entry.syllables), id)
            if (add) blockedReadings += reading else blockedReadings -= reading
        }
        blockedIds.add(id.toLong(), if (add) 1f else -1f)
    }

    override fun blocked(word: Int, node: Int) =
        blockedAnyhow(word) && (word >= vocabulary.size || key(node, word) in blockedReadings)

    override fun blockedAnyhow(word: Int) = blockedIds[word.toLong()] > 0f

    /**
     * The words the user made, by putting a reading together from pieces, with how often each
     * was typed (as its counts now are, halved over time): not those of their dictionaries or
     * packs, nor those forgotten.
     */
    fun ownWords(): List<Pair<Entry, Float>> = entries.filter { (id, entry) -> isOwn(id) && entry !in blockedEntries }
        .map { (id, entry) -> entry to counts[key(NO_WORD, id)] }

    /**
     * The texts of the words of the user's dictionaries and packs, and those they added, for
     * the other input methods to offer too, blocked ones as well: those ask what is blocked as
     * they offer. See [listings].
     */
    fun listedTexts(): List<String> = listed.mapNotNullTo(LinkedHashSet()) { id -> entries[id]?.text }.toList()

    /**
     * The texts of the words the user made, and of the dictionary's they typed, for the other
     * input methods to offer too; not those blocked or forgotten. See [changes].
     */
    fun ownTexts(): List<String> {
        val texts = LinkedHashSet<String>()
        for ((id, entry) in entries) if (isOwn(id) && entry !in blockedEntries) texts += entry.text
        counts.forEach { key, count ->
            val id = second(key)
            val typed = first(key) == NO_WORD && count > 0f
            if (typed && id < vocabulary.size && !blockedAnyhow(id)) texts += vocabulary.word(id)
        }
        return texts.toList()
    }

    /** Every reading of [text] the model has an id for: those typed, made, listed. */
    fun entriesOf(text: String): List<Entry> = ids.keys.filter { it.text == text }

    /**
     * The reading of [text] the user typed most of the words made that the dictionary lacks, still
     * known (a word made is gone once forgotten; one of the user's dictionaries stays, its count
     * none); null for none.
     */
    fun newEntryOf(text: String): Entry? = newByText[text]
        ?.map { newWords[it - vocabulary.size] }
        ?.filter { it in ids }
        ?.maxByOrNull { counts[key(NO_WORD, ids.getValue(it))] }

    /** Whether the user typed [text] as a word with an id here, however read. */
    fun typed(text: String): Boolean = text in typedTexts

    /** Whether [entriesOf] lists any reading of [text]: no list made, and gone through only to the first. */
    fun hasText(text: String): Boolean = ids.keys.any { it.text == text }

    /** Forgets [text] however it was read: see [forget]. */
    fun forgetText(text: String) = entriesOf(text).forEach { forget(listOf(it)) }

    /** Whether the dictionary or the user has [entry] as a word already. */
    fun knows(entry: Entry): Boolean = entry in ids || inDictionary(entry) != NO_WORD

    /** How many words the user added to the dictionary's, forgotten ones too: no id is given twice. */
    val size: Int get() = newWords.size

    override fun child(node: Int, syllable: Int) = trie.child(node, syllable)

    override fun childCount(node: Int) = trie.childCount(node)

    override fun wordCount(node: Int) = trie.wordCount(node)

    override fun word(node: Int, index: Int) = trie.word(node, index)

    override fun text(word: Int) = newWords[word - vocabulary.size].text

    // as learned: a word of the dictionary's too, read as the user typed it (行 as hang); only while
    // it is their choice (chosen): forgetting a word of the dictionary's keeps its entry, and a reading
    // kept so would learn the word back at the reading forgotten; one only imported, its reading
    // perhaps wrong, would take the decoder's arc from the dictionary's reading of the word (行人)
    override fun reading(word: Int) = if (chosen(word)) {
        entries[word]?.syllables ?: newWords.getOrNull(word - vocabulary.size)?.syllables
    } else {
        null
    }

    private fun add(first: Int, second: Int, count: Float) {
        if (first == NO_WORD && counts[key(first, second)] == 0f) changes++
        counts.add(key(first, second), count)
        if (first == NO_WORD) total += count else followers.getOrPut(first) { HashSet() } += second
        if (first == NO_WORD && counts[key(first, second)] > 0f) entries[second]?.let { typedTexts += it.text }
    }

    private fun key(first: Int, second: Int) = (first.toLong() shl Int.SIZE_BITS) or (second.toLong() and 0xffffffffL)
    private fun first(key: Long) = (key shr Int.SIZE_BITS).toInt()
    private fun second(key: Long) = key.toInt()

    companion object {
        const val DEFAULT_LIMIT = 50_000f
        private const val INITIAL_WORDS = 16
        private const val HALF = 0.5f
        private const val MIN_COUNT = 0.25f
        // counts the model pretends were spent on words not yet typed: one word typed once is
        // not yet all the user will ever type
        internal const val UNSEEN = 20f
        // how much a word's own share stands in for pairs not yet seen after prev
        internal const val PRIOR = 2f
    }
}
