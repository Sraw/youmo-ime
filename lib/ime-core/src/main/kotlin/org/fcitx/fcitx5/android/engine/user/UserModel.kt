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
 * The decoder's lexicon of the user's (impl/design-1b.md §4): the words they own ([own]: theirs
 * as the learning record says, formed or kept from an older build), those of their dictionaries
 * and those added by hand, which the dictionary lacks, in a trie the decoder finds them in
 * ([UserWords]); the words blocked; and the counts [UserScorer] mixes into the n-gram model's
 * scores, how often each word was committed and after which word, which [HabitCounts] fills
 * from the learning record. Whether a word is the user's ([owns]) is never a count: it does not
 * fade as the counts do.
 *
 * A word is its text and the syllables it was read as, so it means the same after the
 * dictionary is rebuilt with other ids: that is what [Entry] is and what the record keeps.
 *
 * [learn], [forget] and [addCount] are an older build's: its log (`pinyin.user`, UserLog), or
 * libime's history (LibimeImport), is replayed through them into a model of its own, to be
 * converted (LegacyConversion). The model typing goes on with never learns through them: its
 * words come from [own] and its counts from [setCount] ([HabitCounts]).
 *
 * [index], the model's words by text, gives each of the user's words the model's word of its text
 * if it has one ([modelWord]): a word of the dictionary's typed, or added by hand ([listByHand]), at a
 * reading of the user's. One only listed from the user's dictionaries does not, till typed: it is
 * not [chosen], so neither its reading nor its score is the user's yet.
 */
class UserModel(
    private val dictionary: PinyinDictionary,
    private val vocabulary: Vocabulary,
    private val index: WordIndex? = null,
    private val merged: DictionaryFile? = null,
    // [merged] compiled against another base: each of its words checked against this one's (review S7)
    private val mergedStale: Boolean = false,
) : UserWords, LayerPrior.PackLayers {

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

    // of [listed], English's words pinyin spells (EN-24): neither in [listedTexts] nor counted in
    // [listings], as the tables code no letters (review-uat-fixes S8: each word learned recoded them all)
    private val latinListed = HashSet<Int>()
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

    // the words the user owns (own), by id: theirs whether typed lately or not
    private val owned = HashSet<Int>()

    // the words imported (U-101): the learning record's ([attachImports]), each by id from
    // importBase up by its place there, its text and syllables read from there, so 100 000 of them
    // take little. Offered from where the input starts alone (startWords) but those in the trie
    // as whole words ([promoted]): the user's own, listed, added by hand
    private var imports: ImportedWords? = null
    private val importBase = vocabulary.size + IMPORT_OFFSET
    private val promoted = HashSet<Int>()
    private val importedByHand = HashSet<Int>()
    // the model's word of the text of each one chosen ([modelWord]), looked up once
    private val importSame = HashMap<Int, Int>()
    private var startTrie: ImportTrie? = null
    private var startTrieOf = -1

    // the words of the dictionaries the user merged ([merged], DictionaryFile): each by id from
    // mergedBase up by its place there, read from the file in place and walked in its own trie
    // ([mergedWords]), never copied: 1.9 million of them as objects ran a phone's heap out (0.3.1).
    // Listed all, as a dictionary's word always was; a word the base has as well keeps the base's id
    private val mergedBase = vocabulary.size + MERGED_OFFSET
    private val mergedByHand = HashSet<Int>()

    // unigrams under NO_WORD, bigrams under their first word
    private val counts = Counts()
    // the bigrams to a word put in by its text alone (a table's, handwriting's, their 联想): 联想's,
    // never the decoder's (PLAN §2.7 只记搭配给联想; review C S2), see decoderProbability. Typing reads
    // 联想's own pairs ([predicted]); these say what was learned of a word ([hasLearned]) and what
    // [probability] and [forEachAfter] report of it
    private val textPairs = Counts()
    // the second words of the bigrams, both kinds, by the first: to find them without going through every count
    private val followers = HashMap<Int, HashSet<Int>>()
    // 联想's pairs (HabitCounts): what followed each word, put in by any input method, and how often
    private val predicted = HashMap<Int, HashMap<Int, Float>>()
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
     * Goes up as [ownTexts] may change, or the counts: a word made, owned or disowned, typed a
     * first time, forgotten, blocked or offered again, the counts made again. For another input
     * method to know its copy of them is stale.
     */
    var changes = 0
        private set

    /** Goes up as [listedTexts] change: a word of the user's dictionaries listed. */
    var listings = 0
        private set

    /**
     * Goes up as a word of the user's gets an id or loses it: what [known] and [idsOfText] say of
     * a text is the same while this is, or while [textsChangedSince] does not name it.
     */
    var idsChanged = 0
        private set

    // the texts of the last words that got an id or lost it, the last of them at [idsChanged]
    private val changedTexts = ArrayDeque<String>()

    private fun wordChanged(text: String) {
        idsChanged++
        changedTexts.addLast(text)
        if (changedTexts.size > CHANGED_KEPT) changedTexts.removeFirst()
    }

    // every text's ids may have changed (the words imported did): [textsChangedSince] says null
    private fun allChanged() {
        idsChanged += CHANGED_KEPT + 1
        changedTexts.clear()
    }

    // an imported word's id found by its text ([idsOfText]) once it has an entry: given one, its text changed
    private fun entered(id: Int, entry: Entry) {
        if ((isImport(id) || isMerged(id)) && id !in entries) wordChanged(entry.text)
        entries[id] = entry
    }

    /** The texts whose ids changed since [idsChanged] was [at], or null if more than are kept. */
    fun textsChangedSince(at: Int): List<String>? {
        val n = idsChanged - at
        return if (at < 0 || n > changedTexts.size) null else changedTexts.subList(changedTexts.size - n, changedTexts.size)
    }

    /**
     * Counts [sentence], each word and each pair, the first after [prev] if given (the word
     * committed before it) and still known: one forgotten since is not made a word again. Words
     * of the sentence the dictionary lacks become the user's made words ([madeWords]). An older
     * build's log replayed, or libime's history, to be converted: see the class.
     *
     * @return the words' ids
     */
    fun learn(prev: Entry?, sentence: List<Entry>): IntArray {
        require(sentence.isNotEmpty()) { "nothing to learn" }
        val words = IntArray(sentence.size) { idAsTyped(sentence[it]) }
        // a word before whose reading is not kept yet takes this one: its pair is written out with it
        var before = if (prev != null && knows(prev)) id(prev).also { if (it !in entries) entered(it, prev) } else NO_WORD
        for (w in words) {
            add(NO_WORD, w, 1f)
            if (before != NO_WORD) add(before, w, 1f)
            before = w
        }
        return words
    }

    /**
     * [entry] as the user's own word (impl/design-1b.md §3): in the trie if the dictionary lacks
     * it, its reading and score theirs ([chosen]), for good: no count takes it away, only [disown].
     */
    fun own(entry: Entry): Int {
        val id = idAsTyped(entry)
        if (owned.add(id)) {
            changes++
            mergedChoice(id)
        }
        if (isImport(id)) settle(id)
        return id
    }

    /**
     * [entry] the user's no more (forgotten, an undo of the commit that made it): out of the trie
     * if neither their dictionaries nor their hand list have it, so typed again it is a new word.
     */
    fun disown(entry: Entry) {
        val id = ids[entry] ?: return
        if (!owned.remove(id)) return
        mergedChoice(id)
        changes++
        if (isImport(id)) settle(id) else if (id >= vocabulary.size && !isListed(id)) dropOwn(entry, id)
    }

    /**
     * The words imported, [imports] (the learning record's, U-101, PLAN §2.3): each offered only as
     * a word the input starts with ([startOnly], [startWords]) till the user owns it, adds it by
     * hand or a list has it. Read from there as they change ([importsChanged]), not copied: the
     * model is made again from a record read afresh.
     */
    fun attachImports(imports: ImportedWords) {
        this.imports = imports
        startTrieOf = -1
        importsChanged()
    }

    /** The words imported changed: the tables code them again with the listed ([listings]). */
    fun importsChanged() {
        changes++
        listings++
        allChanged()
    }

    /**
     * Whether [word] is only imported (U-101): neither the user's choice ([chosen]) nor listed.
     * The decoder offers it where the input starts and never inside a sentence (C19); learning
     * files it imported.
     */
    override fun startOnly(word: Int) = isImport(word) && imports?.has(word - importBase) == true && !chosen(word) && word !in listed

    /**
     * Whether [entry] is a word only imported: see [startOnly]. Not one the dictionary has since (a
     * newer engine data's), which [startWords] leaves out too (review-G NIT 6).
     */
    fun isImported(entry: Entry): Boolean = idOf(entry)?.let(::startOnly) == true && inDictionary(entry) == NO_WORD

    override val startWords: StartWords?
        get() {
            val imports = imports?.takeIf { it.size > 0 } ?: return null
            // made again as words come, not as they go (a delete, an undo): those gone [startOnly] leaves out (review-G S4)
            if (startTrieOf != imports.grown) {
                // one the dictionary or the user's has as another word (listed before the record was read) is that word
                startTrie = ImportTrie(imports, importBase) { place ->
                    val entry = Entry(imports.text(place), imports.syllables(place))
                    ids[entry].let { it != null && it != importBase + place } || inDictionary(entry) != NO_WORD || mergedId(entry) != NO_WORD
                }
                startTrieOf = imports.grown
            }
            return startTrie
        }

    private fun isImport(id: Int) = id >= importBase

    // the id ranges, one per kind of word: a when over them is exhaustive, so a new kind is not
    // missed in one of the places that tell them apart (review F3281)
    private enum class Kind { BASE, USER, MERGED, IMPORT }

    private fun kindOf(id: Int): Kind = when {
        isImport(id) -> Kind.IMPORT
        isMerged(id) -> Kind.MERGED
        id >= vocabulary.size -> Kind.USER
        else -> Kind.BASE
    }

    override fun isMerged(word: Int) = word >= mergedBase && word < importBase

    // the id of entry as a merged dictionary has it, else NO_WORD
    private fun mergedId(entry: Entry): Int = merged?.find(entry)?.takeIf { it >= 0 }?.let { mergedBase + it } ?: NO_WORD

    // listed: of the user's dictionaries (all of a merged one's), or added by hand
    private fun isListed(id: Int) = id in listed || isMerged(id)

    override val mergedWords: StartWords? = merged?.let { file ->
        object : StartWords {
            override fun child(node: Int, syllable: Int) = file.child(node, syllable)
            override fun firstChild(node: Int) = file.firstChild(node)
            override fun childCount(node: Int) = file.childCount(node)
            override fun syllable(node: Int) = file.nodeSyllable(node)
            override fun wordCount(node: Int) = file.wordCount(node)
            override fun word(node: Int, index: Int) = mergedBase + file.word(node, index)
        }
    }

    // the trie's places only, as [idsOfText] and [mergedEntryOf]: one past trieWords is a base word's, in no trie (review F3342)
    override fun forEachMergedOfText(text: String, visit: (Int) -> Unit) {
        merged?.let { file -> file.forEachOfText(text) { if (it < file.trieWords) visit(mergedBase + it) } }
    }

    /** See [UserWords.anyMergedChosen]: asked per key, so a bit set's emptiness, not a walk of [owned]. */
    override fun anyMergedChosen(): Boolean = !mergedChosen.isEmpty

    /** See [UserWords.anyMergedCounted]: asked per glide, so a count kept as the counts change. */
    override fun anyMergedCounted(): Boolean = mergedCounted > 0

    // the merged words chosen ([chosen]), by place: [chosen] asked of each past the decoder's limit
    // without boxing an id into [owned]'s or [mergedByHand]'s lookups (review F3277)
    private val mergedChosen = java.util.BitSet()

    // how many merged words have a count of their own
    private var mergedCounted = 0

    // [mergedChosen] kept in step with [owned] and [mergedByHand], for [id] one of them just changed
    private fun mergedChoice(id: Int) {
        if (isMerged(id)) mergedChosen[id - mergedBase] = id in owned || id in mergedByHand
    }

    // [mergedCounted] as [word]'s count went from [was] to [now]
    private fun counted(word: Int, was: Float, now: Float) {
        if (!isMerged(word) || (was > 0f) == (now > 0f)) return
        if (now > 0f) mergedCounted++ else mergedCounted--
    }

    /**
     * The score a merged dictionary gave word [id], log10 against its best word (so at most 0), NaN
     * for none, a word of no merged dictionary's, or one the user chose ([chosen]), scored as theirs:
     * [UserScorer] puts it below the model's unknown word.
     */
    fun mergedScore(id: Int): Float = if (isMerged(id) && !chosen(id)) checkNotNull(merged).score(id - mergedBase) else Float.NaN

    // the id of entry as imported, if it is
    private fun importId(entry: Entry): Int? = imports?.find(entry.text, entry.syllables)?.takeIf { it >= 0 }?.let { importBase + it }

    // the id entry has, made, merged or imported, without making one: id()'s order (review S3)
    private fun idOf(entry: Entry): Int? = ids[entry] ?: mergedId(entry).takeIf { it != NO_WORD } ?: importId(entry)

    // an imported word in the trie while it is a whole word (the user's own, listed, added by hand), else offered from the start alone
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

    /** Whether [word] is one of the user's own words: see [own]. */
    fun owns(word: Int): Boolean = word in owned

    /** The counts made anew: none till [setCount] and [setPair] give them ([HabitCounts]). */
    internal fun clearCounts() {
        counts.clear()
        textPairs.clear()
        followers.clear()
        predicted.clear()
        typedTexts.clear()
        total = 0f
        mergedCounted = 0
        changes++
    }

    /** [word]'s count as [count]; [entry], its reading, kept for [forEachCount] if it has none yet (never a chosen word's own). */
    internal fun setCount(word: Int, count: Float, entry: Entry? = null) {
        if (entry != null && word !in entries) entered(word, entry)
        val key = key(NO_WORD, word)
        val was = counts[key]
        if (was == count) return
        counts[key] = count
        counted(word, was, count)
        total += count - was
        if (was <= 0f && count > 0f) {
            changes++
            typedTexts += text(word, entries[word])
        }
    }

    /** The count of [next] after [prev] as [count]; [byText], [next] was put in by its text alone, for 联想 only. */
    internal fun setPair(prev: Int, next: Int, count: Float, byText: Boolean = false) {
        if (byText) textPairs[key(prev, next)] = count else counts[key(prev, next)] = count
        if (count > 0f) followers.getOrPut(prev) { HashSet() } += next
    }

    /** How often [next] was put in right after [prev], for 联想 ([forEachPredicted]). */
    internal fun setPredicted(prev: Int, next: Int, count: Float) {
        if (count > 0f) predicted.getOrPut(prev) { HashMap() }[next] = count else predicted[prev]?.remove(next)
    }

    override fun forEachPredicted(prev: Int, visit: (word: Int, count: Float) -> Unit) {
        predicted[prev]?.forEach { (word, count) -> visit(word, count) }
    }

    // a word's text: the user's for one of theirs, else the dictionary's
    private fun text(word: Int, entry: Entry?) = entry?.text ?: if (word < vocabulary.size) vocabulary.word(word) else text(word)

    /**
     * Forgets [words], a candidate the user asked to: their counts and every pair they are in.
     * One of the user's own words, forgotten alone, is gone from the trie too, as libime drops
     * it from its user dictionary. One [list]ed stays typeable, its counts gone; so does one in a
     * longer candidate, till the next start.
     */
    fun forget(words: List<Entry>) {
        val forgotten = words.mapNotNull(::idOf).toSet()
        if (forgotten.isEmpty()) return
        forgetCounts(forgotten)
        val word = words.singleOrNull()
        val id = word?.let(::idOf) ?: NO_WORD
        if (word != null && isMade(id)) dropOwn(word, id)
        changes++
    }

    /**
     * Whether anything is learned of [words]: a count, a pair one of them is in, or, alone, a word
     * the user owns ([owns]) or made. A word of the dictionary's never typed has none, so a long
     * press on it offers no forgetting.
     */
    fun hasLearned(words: List<Entry>): Boolean {
        val known = words.mapNotNull(::idOf).toSet()
        if (known.isEmpty()) return false
        if (words.size == 1 && (owns(known.first()) || isMade(known.first()))) return true
        // a word's own count first; a pair's first word may never have been counted (the word before, read from the editor)
        return known.any { count(it) > 0f } || counts.any { key -> first(key) in known || second(key) in known } ||
            textPairs.any { key -> first(key) in known || second(key) in known }
    }

    private fun forgetCounts(forgotten: Set<Int>) {
        counts.removeIf { first(it) in forgotten || second(it) in forgotten }
        predicted.keys.removeAll(forgotten)
        predicted.values.forEach { it.keys.removeAll(forgotten) }
        textPairs.removeIf { first(it) in forgotten || second(it) in forgotten }
        indexFollowers()
        indexTyped()
        recomputeTotal()
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

    private fun dropOwn(word: Entry, id: Int) {
        wordChanged(word.text)
        if (owned.remove(id)) mergedChoice(id)
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
    fun forEachWordCount(word: (Entry, Float) -> Unit) = forEachCount(word) { _, _, _ -> }

    /**
     * Calls [word] with every word counted, then [pair] with every pair, each above 0. A word counted
     * by its text alone (a table's, handwriting's; a word before read from the editor) has no reading
     * here, so no entry, and is left out.
     */
    fun forEachCount(word: (Entry, Float) -> Unit, pair: (Entry, Entry, Float) -> Unit) {
        // twice through the counts, words then pairs: no copy of every pair held while compacting
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

    /**
     * The chance the user types [word] after [prev] ([NO_WORD] for none), from the counts alone:
     * the pair's share of what followed [prev], leaning on the word's own share when [prev] was
     * seldom typed. 0 for a word never committed, which is nearly all the decoder asks about.
     */
    override fun probability(prev: Int, word: Int): Float = probability(prev, word, textPairs[key(prev, word)])

    /** [probability] as the decoder weighs it: the pairs to a word put in by its text alone left out, 联想's (see [setPair]). */
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

    /** The id of [entry]: the dictionary's word if it has it so read, else the user's. */
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

    // the id of entry as typed: a word of the dictionary's is then read so, not as it was listed or blocked
    private fun idAsTyped(entry: Entry): Int = id(entry).also { entered(it, entry) }

    /**
     * [id] of a word from the user's dictionaries: one they did not make, so forgetting it drops
     * what was learned of it but leaves it to type, as libime leaves its extra dictionaries be.
     * One the dictionary has is listed too: no word of the user's, but the tables offer it
     * ([listedTexts]), a word added by hand being typable there whether pinyin knew it or not
     * (UAT TBL-12, 鲸园).
     */
    fun list(entry: Entry): Int = id(entry).also {
        if (listed.add(it) || latinListed.remove(it)) listings++
        if (isImport(it)) settle(it)
    }

    /** [list], for a word of English's: see [latinListed]. */
    fun listLatin(entry: Entry): Int = id(entry).also { if (listed.add(it)) latinListed += it }

    /** [list]s [entry] as a word the user added by hand: scored as the model's of its text ([modelWord]). */
    fun listByHand(entry: Entry): Int = list(entry).also {
        when (kindOf(it)) {
            Kind.IMPORT -> importedByHand += it
            Kind.MERGED -> {
                mergedByHand += it
                mergedChoice(it)
            }
            Kind.USER -> byHand[it - vocabulary.size] = true
            Kind.BASE -> Unit
        }
    }

    /**
     * [entry] no longer added by hand: listed still, till the next start, but scored as the model's
     * unknown word, unless the user owns it ([chosen]).
     */
    fun unlistByHand(entry: Entry) {
        ids[entry]?.let {
            when (kindOf(it)) {
                Kind.IMPORT -> importedByHand -= it
                Kind.MERGED -> mergedByHand -= it
                Kind.USER -> byHand[it - vocabulary.size] = false
                Kind.BASE -> Unit
            }
            mergedChoice(it)
        }
    }

    /**
     * [entry], added by hand, deleted at once (U-82): listed no more, and out of the trie unless the
     * user owns it, so it is not offered again; a word of the dictionary's stays the dictionary's.
     */
    fun unlist(entry: Entry) {
        val id = ids[entry] ?: return
        unlistByHand(entry)
        if (!listed.remove(id)) return
        if (!latinListed.remove(id)) listings++
        changes++
        // one a merged dictionary has stays its word, offered from there (as libime leaves its extra dictionaries be)
        if (isImport(id)) settle(id) else if (id >= vocabulary.size && !isMerged(id) && id !in owned) dropOwn(entry, id)
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
        if (isImport(word) || isMerged(word)) {
            if (!chosen(word)) return word
            val same = importSame.getOrPut(word) { index?.find(text(word)) ?: NO_WORD }
            return if (same == NO_WORD) word else same
        }
        val same = sameText[word - vocabulary.size]
        return if (same == NO_WORD || !chosen(word)) word else same
    }

    /** Whether [word] is one the user added by hand ([listByHand]), of the user's own trie or imported so. */
    fun addedByHand(word: Int): Boolean =
        when (kindOf(word)) {
            Kind.IMPORT -> word in importedByHand
            Kind.MERGED -> word in mergedByHand
            Kind.USER -> byHand[word - vocabulary.size]
            Kind.BASE -> false
        }

    /**
     * Whether [word] is the user's choice: their own ([owns]), or added by hand. The one test of
     * whether a word's reading ([reading]) and score ([modelWord]) are the user's: one only
     * imported, from a dictionary or a pack, is not, nor one of the dictionary's typed, whose
     * reading is the dictionary's. Never a count, which fades (impl/design-1b.md M3).
     */
    override fun chosen(word: Int): Boolean = if (isMerged(word)) mergedChosen[word - mergedBase] else addedByHand(word) || owns(word)

    /**
     * The log10 probability a pack gave word [id], or NaN: [UserScorer] uses it where the model has
     * no word. Always NaN in the app, which lists no scored words (only ime-eval does).
     */
    fun score(id: Int): Float = packScores.getOrDefault(id.toLong(), Float.NaN)

    /** The layer a pack put word [id] in, -1 if none did (always, in the app: see [score]). */
    override fun layerOf(id: Int): Int = packLayers.getOrDefault(id.toLong(), -1f).toInt()

    /**
     * Where [entry] comes from, as the learning record files it (impl/design-1a.md §3): added by
     * hand ([Row.ADDED]); only imported ([Row.IMPORTED], [startOnly]); the dictionary's or a user
     * dictionary's ([Row.DICTIONARY]); else none ([Row.NONE]). What the user typed is not asked: a word they made would hide its pending step.
     */
    fun origin(entry: Entry): Int {
        val id = idOf(entry)
        return when {
            id != null && addedByHand(id) -> Row.ADDED
            (id != null && isListed(id)) || inDictionary(entry) != NO_WORD || mergedId(entry) != NO_WORD -> Row.DICTIONARY
            id != null && startOnly(id) -> Row.IMPORTED
            else -> Row.NONE
        }
    }

    // a word the user made, typing it (learn), not the dictionary's nor one of their dictionaries', nor imported
    private fun isMade(id: Int) = id >= vocabulary.size && !isListed(id) && !isImport(id)

    /** The id of [entry] if it has one, the dictionary's, imported or the user's, without making it the user's word; else [NO_WORD]. */
    fun known(entry: Entry): Int = if (knows(entry)) id(entry) else NO_WORD

    /** The ids of [text], of the user's words and of the dictionary's (by [index]): those a pair after it goes on from. */
    fun idsOfText(text: String): List<Int> {
        val out = ArrayList<Int>(2)
        index?.find(text)?.takeIf { it >= 0 }?.let(out::add)
        newByText[text]?.forEach { id -> if (entries[id]?.let { it in ids } == true && id !in out) out += id }
        // an imported word typed: a pair after it goes on from it too
        imports?.placesOf(text)?.forEach { place -> (importBase + place).let { if (it in entries && it !in out) out += it } }
        // a merged word's, typed or not: one committed in a table or handwriting is learned and
        // paired under it (review S2); those the base has come back through index above
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

    /**
     * Blocks [entry], the dictionary's word or one of the user's, so it is never offered; whether
     * there is such a word. One the dictionary lacks and the user never typed is none: nothing
     * offers it to block.
     */
    fun block(entry: Entry): Boolean {
        if (entry in blockedEntries) return true
        // its id first: one given it now (an imported word's) while it is blocked would be blocked twice, by id() too
        val id = known(entry)
        blockedEntries += entry
        changes++
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

    override fun blockedAnyhow(word: Int) = blockedIds[word.toLong()] > 0f || mergedStale && inStaleBase(word)

    // a stale file's word the base has now: the base's alone, offered and blocked as that (review S7).
    // Looked up once a place, only while the recompile is owed
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

    /** What the user turned off as a prediction after a word (U-72): their learning record's, set as the model is made. */
    var predictionsOff: (prev: String, next: String) -> Boolean = { _, _ -> false }

    override fun notPredicted(prev: String, next: String) = predictionsOff(prev, next)

    /** The words the user owns ([own]), not blocked, with how often each was typed lately. */
    fun ownWords(): List<Pair<Entry, Float>> = owned.mapNotNull { id -> entries[id]?.takeIf { it !in blockedEntries }?.let { it to counts[key(NO_WORD, id)] } }

    /**
     * The words the user made in an older build, by putting a reading together from pieces, with
     * how often each was typed: not those of their dictionaries or packs, nor those forgotten. What
     * converting that build's log makes the user's own (LegacyConversion).
     */
    fun madeWords(): List<Pair<Entry, Float>> = entries.filter { (id, entry) -> isMade(id) && entry !in blockedEntries }
        .map { (id, entry) -> entry to counts[key(NO_WORD, id)] }

    /**
     * The texts of the words of the user's dictionaries and packs, and those they added, for
     * the other input methods to offer too, blocked ones as well: those ask what is blocked as
     * they offer. See [listings].
     */
    fun listedTexts(): List<String> {
        val out = listed.filterNot(latinListed::contains).mapNotNullTo(LinkedHashSet()) { id -> entries[id]?.text ?: if (id < vocabulary.size) vocabulary.word(id) else if (isMerged(id)) text(id) else null }
        // the words imported too: the tables offer them as they do a dictionary's (TBL-12a), after their own, never at a code alone (D8)
        imports?.let { i -> i.places().mapTo(out, i::text) }
        return out.toList()
    }

    /** The dictionaries the user merged, compiled: what [mergedWords] walks. */
    val dictionaryFile: DictionaryFile? get() = merged

    /**
     * The texts of the words the user owns ([own]), for the other input methods to offer too; not
     * those blocked. See [changes].
     */
    fun ownTexts(): List<String> = ownWords().mapTo(LinkedHashSet()) { it.first.text }.toList()

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
        ?: mergedEntryOf(text)

    // a merged dictionary's reading of text, its best ranked: one never typed in pinyin (review S2)
    private fun mergedEntryOf(text: String): Entry? {
        val file = merged ?: return null
        var best = -1
        file.forEachOfText(text) { place -> if (place < file.trieWords && (best < 0 || file.score(place) > file.score(best))) best = place }
        return if (best < 0) null else file.entry(best)
    }

    /** Whether the user typed [text] as a word with an id here, however read. */
    fun typed(text: String): Boolean = text in typedTexts

    /** Whether [entriesOf] lists any reading of [text]: no list made, and gone through only to the first. */
    fun hasText(text: String): Boolean = ids.keys.any { it.text == text }

    /** Whether the dictionary or the user has [entry] as a word already. */
    fun knows(entry: Entry): Boolean =
        ids[entry]?.let { !gone(it) } == true || inDictionary(entry) != NO_WORD || mergedId(entry) != NO_WORD || importId(entry) != null

    // an id an imported word had, its batch undone or the word deleted since, and nothing else the user's: as
    // after a restart, no word (U-84), so the same list imports it again (review-G S1)
    private fun gone(id: Int) = isImport(id) && imports?.has(id - importBase) != true && id !in owned && id !in listed && id !in importedByHand

    /**
     * Whether [text] is a word the dictionary has, in any reading, or a user dictionary's or one
     * added by hand: what an import leaves out by its text (a list's misreading of a dictionary
     * word is no new word).
     */
    fun knowsText(text: String): Boolean =
        index?.find(text)?.let { it >= 0 } == true || newByText[text]?.any { it in listed } == true || merged?.hasText(text) == true

    /** How many words the user added to the dictionary's, forgotten ones too: no id is given twice. */
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

    // as learned: a word of the dictionary's too, read as the user typed it (行 as hang); only while
    // it is their choice (chosen): forgetting a word of the dictionary's keeps its entry, and a reading
    // kept so would learn the word back at the reading forgotten; one only imported, its reading
    // perhaps wrong, would take the decoder's arc from the dictionary's reading of the word (行人)
    override fun reading(word: Int) = if (chosen(word)) {
        entries[word]?.syllables ?: when (kindOf(word)) {
            Kind.IMPORT -> imports?.syllables(word - importBase)
            Kind.MERGED -> merged?.syllables(word - mergedBase)
            Kind.USER, Kind.BASE -> newWords.getOrNull(word - vocabulary.size)?.syllables
        }
    } else {
        null
    }

    private fun add(first: Int, second: Int, count: Float) {
        val was = counts[key(first, second)]
        if (first == NO_WORD && was == 0f) changes++
        counts.add(key(first, second), count)
        if (first == NO_WORD) counted(second, was, counts[key(first, second)])
        if (first == NO_WORD) total += count else followers.getOrPut(first) { HashSet() } += second
        if (first == NO_WORD && counts[key(first, second)] > 0f) entries[second]?.let { typedTexts += it.text }
    }

    private fun key(first: Int, second: Int) = (first.toLong() shl Int.SIZE_BITS) or (second.toLong() and 0xffffffffL)
    private fun first(key: Long) = (key shr Int.SIZE_BITS).toInt()
    private fun second(key: Long) = key.toInt()

    companion object {
        // the texts of words changed kept for [textsChangedSince]: more and all is found again
        internal const val CHANGED_KEPT = 256

        // the user's words made that the arrays first have room for, doubled as they fill
        private const val INITIAL_WORDS = 16
        // where the ids of the words imported start, past the dictionary's: no user makes 2^28 words
        private const val IMPORT_OFFSET = 1 shl 28
        // where the ids of the merged dictionaries' words start, below the imported: DictionaryCompiler
        // takes no more than MAX_WORDS, well short of the 2^27 between
        private const val MERGED_OFFSET = 1 shl 27
        // counts the model pretends were spent on words not yet typed: one word typed once is
        // not yet all the user will ever type
        internal const val UNSEEN = 20f
        // how much a word's own share stands in for pairs not yet seen after prev
        internal const val PRIOR = 2f
    }
}
