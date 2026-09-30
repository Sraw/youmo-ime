/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.Vocabulary
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
 * was typed long ago gives way to what is typed now, and the counts stay bounded. A new word
 * whose count is dropped stays in the trie, scored as the model's unknown word, until the next
 * start: few enough, at a word per sentence typed, not to be worth rebuilding the trie for.
 */
class UserModel(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val limit: Float = DEFAULT_LIMIT,
) : UserWords {
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
    private val ids = HashMap<Entry, Int>()
    // the entry each word was learned as, to write it out again
    private val entries = HashMap<Int, Entry>()
    // those of them from the user's dictionaries: see list
    private val listed = HashSet<Int>()

    // the trie of the new words: children by syllable, sorted, per node
    private val childSyllables = arrayListOf(IntArray(0))
    private val childNodes = arrayListOf(IntArray(0))
    private val nodeWords = arrayListOf(IntArray(0))

    // unigrams under NO_WORD, bigrams under their first word
    private val counts = Counts()

    /** The sum of the words' counts. */
    var total = 0f
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
        val words = IntArray(sentence.size) { id(sentence[it]) }
        var before = if (prev == null) NO_WORD else known(prev)
        for (w in words) {
            add(NO_WORD, w, 1f)
            if (before != NO_WORD) add(before, w, 1f)
            before = w
        }
        if (total > limit) {
            counts.scale(HALF, MIN_COUNT)
            total = 0f
            counts.forEach { key, count -> if (first(key) == NO_WORD) total += count }
        }
        // after halving: the journal may compact, writing out the counts as they now are
        journal?.record(prev, sentence)
        return words
    }

    /**
     * Forgets [words], a candidate the user asked to: their counts and every pair they are in.
     * One of the user's own words, forgotten alone, is gone from the trie too, as libime drops
     * it from its user dictionary. One [list]ed stays typeable, its counts gone; so does one in a
     * longer candidate, till the next start, as does a word whose counts were halved away.
     */
    fun forget(words: List<Entry>) {
        val ids = words.mapNotNull { ids[it] }.toSet()
        if (ids.isEmpty()) return
        counts.removeIf { first(it) in ids || second(it) in ids }
        total = 0f
        counts.forEach { key, count -> if (first(key) == NO_WORD) total += count }
        val word = words.singleOrNull()
        val id = word?.let { this.ids[it] } ?: NO_WORD
        if (word != null && isOwn(id)) {
            removeFromTrie(word.syllables, id)
            // learned again, it is a new word, put back in the trie
            this.ids.remove(word)
            entries.remove(id)
        }
        journal?.forgot(words)
    }

    /** Adds [count] to [entry] as a word; for counts kept, as compaction writes them. */
    fun restore(entry: Entry, count: Float) = add(NO_WORD, id(entry), count)

    /** Adds [count] to [second] after [first]. */
    fun restore(first: Entry, second: Entry, count: Float) = add(id(first), id(second), count)

    /** Calls [word] with every word counted, then [pair] with every pair. */
    fun forEachCount(word: (Entry, Float) -> Unit, pair: (Entry, Entry, Float) -> Unit) {
        val pairs = ArrayList<Pair<Long, Float>>()
        counts.forEach { key, count ->
            if (first(key) == NO_WORD) word(entries.getValue(second(key)), count) else pairs += key to count
        }
        for ((key, count) in pairs) pair(entries.getValue(first(key)), entries.getValue(second(key)), count)
    }

    /**
     * The chance the user types [word] after [prev] ([NO_WORD] for none), from the counts alone:
     * the pair's share of what followed [prev], leaning on the word's own share when [prev] was
     * seldom typed. 0 for a word never committed, which is nearly all the decoder asks about.
     */
    fun probability(prev: Int, word: Int): Float {
        val count = counts[key(NO_WORD, word)]
        if (count == 0f) return 0f
        val alone = count / (total + UNSEEN)
        if (prev == NO_WORD) return alone
        val before = counts[key(NO_WORD, prev)]
        if (before == 0f) return alone
        return (counts[key(prev, word)] + PRIOR * alone) / (before + PRIOR)
    }

    /** The id of [entry]: the dictionary's word if it has it so read, else the user's. */
    fun id(entry: Entry): Int {
        ids[entry]?.let { return it }
        var id = inDictionary(entry)
        if (id == NO_WORD) {
            id = vocabulary.size + newWords.size
            newWords += entry
            addToTrie(entry.syllables, id)
        }
        ids[entry] = id
        entries[id] = entry
        return id
    }

    /**
     * [id] of a word from the user's dictionaries: one they did not make, so forgetting it drops
     * what was learned of it but leaves it to type, as libime leaves its extra dictionaries be.
     */
    fun list(entry: Entry): Int = id(entry).also { if (it >= vocabulary.size) listed += it }

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

    /** How many words the user added to the dictionary's, forgotten ones too: no id is given twice. */
    val size: Int get() = newWords.size

    override fun child(node: Int, syllable: Int): Int {
        val syllables = childSyllables[node]
        val at = syllables.binarySearch(syllable)
        return if (at >= 0) childNodes[node][at] else -1
    }

    override fun childCount(node: Int) = childSyllables[node].size

    override fun wordCount(node: Int) = nodeWords[node].size

    override fun word(node: Int, index: Int) = nodeWords[node][index]

    override fun text(word: Int) = newWords[word - vocabulary.size].text

    private fun addToTrie(syllables: IntArray, word: Int) {
        var node = root
        for (s in syllables) {
            var next = child(node, s)
            if (next < 0) {
                next = nodeWords.size
                childSyllables += IntArray(0)
                childNodes += IntArray(0)
                nodeWords += IntArray(0)
                val at = -(childSyllables[node].binarySearch(s) + 1)
                childSyllables[node] = childSyllables[node].inserted(at, s)
                childNodes[node] = childNodes[node].inserted(at, next)
            }
            node = next
        }
        nodeWords[node] = nodeWords[node] + word
    }

    // the nodes stay: a walk down them finds no word, and the next start leaves them out
    private fun removeFromTrie(syllables: IntArray, word: Int) {
        var node = root
        for (s in syllables) {
            node = child(node, s)
            if (node < 0) return
        }
        nodeWords[node] = nodeWords[node].filter { it != word }.toIntArray()
    }

    private fun IntArray.inserted(at: Int, value: Int) = IntArray(size + 1) {
        when {
            it < at -> this[it]
            it == at -> value
            else -> this[it - 1]
        }
    }

    private fun add(first: Int, second: Int, count: Float) {
        counts.add(key(first, second), count)
        if (first == NO_WORD) total += count
    }

    private fun key(first: Int, second: Int) = (first.toLong() shl Int.SIZE_BITS) or (second.toLong() and 0xffffffffL)
    private fun first(key: Long) = (key shr Int.SIZE_BITS).toInt()
    private fun second(key: Long) = key.toInt()

    companion object {
        const val DEFAULT_LIMIT = 50_000f
        private const val HALF = 0.5f
        private const val MIN_COUNT = 0.25f
        // counts the model pretends were spent on words not yet typed: one word typed once is
        // not yet all the user will ever type
        private const val UNSEEN = 20f
        // how much a word's own share stands in for pairs not yet seen after prev
        private const val PRIOR = 2f
    }
}
