/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.CharReadings
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLongArray

/** One vocabulary shared by dictionary and n-gram model, in one [DataFile] so they cannot be mixed across builds. */
class PinyinData private constructor(file: DataFile) {

    val meta: Map<String, String> = file.meta(Section.META)

    // checked first: every id in the file depends on it
    init {
        val count = meta[META_SYLLABLE_COUNT]?.toIntOrNull() ?: -1
        val checksum = meta[META_SYLLABLE_CHECKSUM]?.toLongOrNull() ?: -1
        if (!Syllables.matches(count, checksum)) {
            throw DataFormatException("built for a different syllable table ($count syllables, checksum $checksum)")
        }
    }

    val vocabulary = Vocabulary(StringTable(file.bitPacked(Section.VOCAB_OFFSETS), file.section(Section.VOCAB_CHARS)))
    val dictionary = PinyinDictionary(
        vocabulary.size,
        file.bitPacked(Section.DICT_SYLLABLE),
        file.bitPacked(Section.DICT_CHILD_START),
        file.bitPacked(Section.DICT_WORD_START),
        file.bitPacked(Section.DICT_WORDS),
        file.section(Section.DICT_WEIGHT),
        Quantizer.decodeTable(file.section(Section.DICT_WEIGHT_CODEBOOK)),
    )
    val model = NgramModel(file, meta.int(META_LM_VOCABULARY), meta.int(META_UNKNOWN))

    val layers = (meta[META_LAYERS]?.split(',') ?: listOf(WordLayers.BASE)).let { names ->
        // asked for, not has(): a damaged section table loses a section and still passes its checksums
        WordLayers(names, if (names.size > 1) file.bitPacked(Section.WORD_LAYER) else null, vocabulary.size)
    }

    /** Built on first use, on whichever thread asks first. */
    val wordIndex: WordIndex by lazy { WordIndex(vocabulary, model.vocabularySize) }

    val tones = ToneTable(
        file.bitPacked(Section.TONE_CHAR_KEYS),
        file.section(Section.TONE_CHAR),
        file.bitPacked(Section.TONE_WORDS),
        file.bitPacked(Section.TONE_WORD_START),
        file.bitPacked(Section.TONE_SYLLABLES),
        file.section(Section.TONE_TONES),
    )

    val charReadings = CharReadings(
        file.bitPacked(Section.READING_CHARS),
        file.bitPacked(Section.READING_START),
        file.bitPacked(Section.READING_SYLLABLES),
        file.section(Section.READING_TONES),
        file.bitPacked(Section.READING_EXAMPLE_START),
        file.bitPacked(Section.READING_EXAMPLES),
        vocabulary,
    )

    val misreadings: Map<String, Misreadings> by lazy {
        meta.mapNotNull { (key, value) ->
            if (!key.startsWith(Misreadings.META_PREFIX)) return@mapNotNull null
            val word = key.removePrefix(Misreadings.META_PREFIX)
            Misreadings.fromMeta(word, value)?.let { word to it }
        }.toMap()
    }

    init {
        ensureFormat(model.vocabularySize <= vocabulary.size) { "the model has more words than the vocabulary" }
    }

    internal object Section {
        const val META = 1
        const val VOCAB_OFFSETS = 2
        const val VOCAB_CHARS = 3
        const val DICT_SYLLABLE = 4
        const val DICT_CHILD_START = 5
        const val DICT_WORD_START = 6
        const val DICT_WORDS = 7
        const val UNI_PROB = 8
        const val UNI_BACKOFF = 9
        const val UNI_BIGRAM_START = 10
        const val BI_WORD = 11
        const val BI_PROB = 12
        const val BI_BACKOFF = 13
        const val BI_TRIGRAM_START = 14
        const val BI_PROB_CODEBOOK = 15
        const val BI_BACKOFF_CODEBOOK = 16
        const val TRI_WORD = 17
        const val TRI_PROB = 18
        const val TRI_PROB_CODEBOOK = 19
        const val DICT_WEIGHT = 20
        const val DICT_WEIGHT_CODEBOOK = 21
        const val WORD_LAYER = 22
        const val TONE_CHAR_KEYS = 23
        const val TONE_CHAR = 24
        const val TONE_WORDS = 25
        const val TONE_WORD_START = 26
        const val TONE_SYLLABLES = 27
        const val TONE_TONES = 28
        const val READING_CHARS = 29
        const val READING_START = 30
        const val READING_SYLLABLES = 31
        const val READING_TONES = 32
        const val READING_EXAMPLE_START = 33
        const val READING_EXAMPLES = 34
    }

    companion object {
        /** Bump on any layout change. */
        const val VERSION = 4

        internal const val META_SYLLABLE_COUNT = "syllables.count"
        internal const val META_SYLLABLE_CHECKSUM = "syllables.checksum"
        internal const val META_LM_VOCABULARY = "lm.vocabulary"
        internal const val META_UNKNOWN = "lm.unknown"
        internal const val META_LAYERS = "layers"

        fun load(buffer: ByteBuffer, verify: Boolean = true): PinyinData =
            PinyinData(DataFile.open(buffer, DataFile.KIND_PINYIN, VERSION, verify))

        private fun Map<String, String>.int(key: String): Int =
            get(key)?.toIntOrNull() ?: throw DataFormatException("meta $key missing")
    }
}

/** The dictionary's layers by [names] and each word's, for [org.fcitx.fcitx5.android.engine.lattice.LayerPrior]. A word in several is of the first. */
class WordLayers internal constructor(val names: List<String>, private val packed: BitPacked?, val words: Int) {
    init {
        ensureFormat(names.isNotEmpty() && names.all { it.isNotEmpty() }) { "layer names ${names.joinToString(",")}" }
        ensureFormat(packed == null || packed.size == words) { "layers for ${packed?.size} of $words words" }
        // here, not in layer(): that runs for every word scored and must not fail on a key press
        packed?.checkBelow("word layers", names.size)
    }

    val count: Int get() = names.size

    fun layer(word: Int): Int = if (packed == null || word !in 0 until packed.size) 0 else packed[word]

    companion object {
        const val BASE = "base"

        const val NEW = "new"
    }
}

class Vocabulary internal constructor(private val strings: StringTable) {
    val size: Int get() = strings.size
    fun word(id: Int): String = strings[id]

    fun length(id: Int): Int = strings.length(id)
    fun char(id: Int, at: Int): Char = strings.char(id, at)
}

/** A trie over syllable ids, nodes breadth-first so children are a run sorted by syllable; words most probable first. */
class PinyinDictionary internal constructor(
    vocabularySize: Int,
    private val syllables: BitPacked,
    private val childStart: BitPacked,
    private val wordStart: BitPacked,
    private val words: BitPacked,
    private val weights: ByteBuffer,
    private val weightTable: FloatArray,
) {
    init {
        val nodes = syllables.size
        ensureFormat(nodes >= 1 && childStart.size == nodes + 1 && wordStart.size == nodes + 1) { "dictionary arrays disagree on their sizes" }
        ensureFormat(weights.capacity() == words.size) { "dictionary weights disagree with its words" }
        // breadth-first: every node but the root is exactly one node's child, so child runs end at nodeCount
        childStart.checkRuns("dictionary children", 1, nodes)
        // children after their parent, so no path can loop
        for (node in 0 until nodes) ensureFormat(childStart[node] > node) { "dictionary node $node precedes its parent" }
        wordStart.checkRuns("dictionary words", 0, words.size)
        words.checkBelow("dictionary words", vocabularySize)
        // spelt out by id (Syllables.spelling) wherever a trie is walked
        syllables.checkBelow("dictionary syllables", Syllables.count)
    }

    val root: Int get() = 0
    val nodeCount: Int get() = syllables.size

    fun syllable(node: Int): Int = syllables[node]

    fun firstChild(node: Int): Int = childStart[node]
    fun childCount(node: Int): Int = childStart[node + 1] - childStart[node]

    fun childFrom(next: Int, end: Int, syllable: Int): Int {
        var at = next
        while (at < end && syllables[at] < syllable) at++
        return at
    }

    fun child(node: Int, syllable: Int): Int {
        val from = childStart[node]
        val to = childStart[node + 1]
        val at = syllables.lowerBound(from, to, syllable)
        return if (at < to && syllables[at] == syllable) at else -1
    }

    fun find(path: IntArray): Int = path.fold(root) { node, s -> if (node < 0) -1 else child(node, s) }

    fun wordCount(node: Int): Int = wordStart[node + 1] - wordStart[node]

    /** Unchecked: past [wordCount] lie the next node's words. */
    fun word(node: Int, index: Int): Int = words[wordStart[node] + index]

    /** log10 adjustment for this reading; 0 unless a polyphone's rarer reading. */
    fun weight(node: Int, index: Int): Float = weightTable[weights.get(wordStart[node] + index).toInt() and 0xff]

    fun wordsOf(node: Int): Int = wordStart[node]

    fun wordAt(at: Int): Int = words[at]

    fun weightAt(at: Int): Float = weightTable[weights.get(at).toInt() and 0xff]
}

/**
 * Backoff trigram model as sorted arrays, log10, bigram and trigram values quantised to a byte. Ids from
 * [vocabularySize] up score as the unknown word. A [NO_WORD] context is start of input and shortens the
 * history, so the first word scores as its unigram, as libime does (lm_sc.arpa has no `<s>`).
 */
class NgramModel internal constructor(file: DataFile, val vocabularySize: Int, private val unknown: Int) {

    private val uniProb = file.section(PinyinData.Section.UNI_PROB)
    private val uniBackoff = file.section(PinyinData.Section.UNI_BACKOFF)
    private val uniBigramStart = file.bitPacked(PinyinData.Section.UNI_BIGRAM_START)
    private val biWord = file.bitPacked(PinyinData.Section.BI_WORD)
    private val biProb = file.section(PinyinData.Section.BI_PROB)
    private val biBackoff = file.section(PinyinData.Section.BI_BACKOFF)
    private val biTrigramStart = file.eliasFano(PinyinData.Section.BI_TRIGRAM_START)
    private val biProbTable = Quantizer.decodeTable(file.section(PinyinData.Section.BI_PROB_CODEBOOK))
    private val biBackoffTable = Quantizer.decodeTable(file.section(PinyinData.Section.BI_BACKOFF_CODEBOOK))
    private val triWord = file.bitPacked(PinyinData.Section.TRI_WORD)
    private val triProb = file.section(PinyinData.Section.TRI_PROB)
    private val triProbTable = Quantizer.decodeTable(file.section(PinyinData.Section.TRI_PROB_CODEBOOK))

    // every size before any value: a value from a wrong-sized table could be out of bounds
    init {
        val v = vocabularySize.toLong()
        ensureFormat(v >= 1 && uniProb.capacity().toLong() == v * 4 && uniBackoff.capacity().toLong() == v * 4 && uniBigramStart.size.toLong() == v + 1) {
            "unigram arrays do not match a vocabulary of $vocabularySize"
        }
        ensureFormat(unknown in 0 until vocabularySize) { "unknown word $unknown is outside the model" }
        ensureFormat(biProb.capacity() == biWord.size && biBackoff.capacity() == biWord.size && biTrigramStart.size == biWord.size + 1) {
            "bigram arrays disagree on their sizes"
        }
        ensureFormat(triProb.capacity() == triWord.size) { "trigram arrays disagree on their sizes" }
        uniBigramStart.checkRuns("bigram runs", 0, biWord.size)
        biTrigramStart.checkRuns("trigram runs", 0, triWord.size)
    }

    private fun known(word: Int) = if (word in 0 until vocabularySize) word else unknown

    fun score(word: Int): Float = uniProb.getFloat(known(word) * 4)

    val unknownScore: Float get() = uniProb.getFloat(unknown * 4)

    fun score(prev: Int, word: Int): Float {
        if (prev == NO_WORD) return score(word)
        val p = known(prev)
        val w = known(word)
        val bigram = bigram(p, w)
        return if (bigram >= 0) biProbTable[biProb.get(bigram).toInt() and 0xff] else uniBackoff.getFloat(p * 4) + score(w)
    }

    fun score(prev2: Int, prev: Int, word: Int): Float = scoreAfter(context(prev2, prev), word)

    /** (prev2, prev) looked up once for many [scoreAfter]s. */
    fun context(prev2: Int, prev: Int): Long {
        val bigram = if (prev2 == NO_WORD || prev == NO_WORD) -1 else bigram(known(prev2), known(prev))
        return (prev.toLong() shl Int.SIZE_BITS) or (bigram.toLong() and 0xffffffffL)
    }

    fun prevOf(context: Long): Int = (context shr Int.SIZE_BITS).toInt()

    fun scoreAfter(context: Long, word: Int): Float = scoreAfter(context, trigrams(context), word)

    fun trigrams(context: Long): Trigrams {
        val bigram = context.toInt()
        if (bigram < 0) return Trigrams.NONE
        val run = biTrigramStart.run(bigram)
        return Trigrams(run.start, run.end)
    }

    @JvmInline
    value class Trigrams internal constructor(internal val bits: Long) {

        internal constructor(start: Int, end: Int) : this((start.toLong() shl Int.SIZE_BITS) or (end.toLong() and 0xffffffffL))

        internal val start: Int get() = (bits ushr Int.SIZE_BITS).toInt()
        internal val end: Int get() = bits.toInt()

        companion object {
            val NONE = Trigrams(0, 0)
        }
    }

    fun scoreAfter(context: Long, trigrams: Trigrams, word: Int): Float {
        val prev = prevOf(context)
        val bigram = context.toInt()
        if (bigram < 0) return score(prev, word)
        val w = known(word)
        val from = trigrams.start
        val to = trigrams.end
        val at = triWord.lowerBound(from, to, w)
        if (at < to && triWord[at] == w) return triProbTable[triProb.get(at).toInt() and 0xff]
        return biBackoffTable[biBackoff.get(bigram).toInt() and 0xff] + score(prev, word)
    }

    /** Calls [visit] with every word seen after [context], in word order, with its [scoreAfter]. */
    internal fun forEachAfter(context: Long, visit: WordVisitor) {
        val prev = prevOf(context)
        if (prev == NO_WORD) return
        val p = known(prev)
        val bigram = context.toInt()
        var b = uniBigramStart[p]
        val bEnd = uniBigramStart[p + 1]
        val trigrams = trigrams(context)
        var t = trigrams.start
        val tEnd = trigrams.end
        val backoff = if (bigram < 0) 0f else biBackoffTable[biBackoff.get(bigram).toInt() and 0xff]
        // both runs are sorted by word: merge them
        while (b < bEnd || t < tEnd) {
            val bw = if (b < bEnd) biWord[b] else Int.MAX_VALUE
            val tw = if (t < tEnd) triWord[t] else Int.MAX_VALUE
            when {
                // a word past the model (only in a damaged file) is skipped here: checking at load would page in tens of MB
                b < bEnd && bw >= vocabularySize -> b++
                t < tEnd && tw >= vocabularySize -> t++
                tw <= bw -> {
                    visit.visit(tw, triProbTable[triProb.get(t).toInt() and 0xff])
                    t++
                    if (tw == bw) b++
                }
                else -> {
                    visit.visit(bw, backoff + biProbTable[biProb.get(b).toInt() and 0xff])
                    b++
                }
            }
        }
    }

    internal fun interface WordVisitor {
        fun visit(word: Int, score: Float)
    }

    fun backoff(word: Int): Float = uniBackoff.getFloat(known(word) * 4)

    fun backoff(prev: Int, word: Int): Float {
        if (prev == NO_WORD) return 0f
        val bigram = bigram(known(prev), known(word))
        return if (bigram >= 0) biBackoffTable[biBackoff.get(bigram).toInt() and 0xff] else 0f
    }

    // direct-mapped bigram cache, one long per slot so another thread (ime-eval) sees a slot whole or not at all;
    // the decoder repeats the same pairs on every path of every key
    private val bigrams: AtomicLongArray? =
        if (vocabularySize < 1 shl KEY_BITS && biWord.size < (1 shl VALUE_BITS) - 1) AtomicLongArray(1 shl CACHE_BITS) else null

    private fun bigram(prev: Int, word: Int): Int {
        val cache = bigrams ?: return search(prev, word)
        val key = ((prev + 1).toLong() shl (KEY_BITS + VALUE_BITS)) or (word.toLong() shl VALUE_BITS)
        val slot = ((prev * HASH_A) xor word) * HASH_B ushr (Int.SIZE_BITS - CACHE_BITS)
        val held = cache.get(slot)
        if (held and KEY_MASK == key) return (held and VALUE_MASK).toInt() - 1
        val at = search(prev, word)
        cache.lazySet(slot, key or (at + 1).toLong())
        return at
    }

    private fun search(prev: Int, word: Int): Int {
        val from = uniBigramStart[prev]
        val to = uniBigramStart[prev + 1]
        val at = biWord.lowerBound(from, to, word)
        return if (at < to && biWord[at] == word) at else -1
    }

    internal fun bigramsHeld(): Int = bigrams?.let { c -> (0 until c.length()).count { c.get(it) != 0L } } ?: 0

    companion object {
        const val NO_WORD = -1

        private const val KEY_BITS = 20
        private const val VALUE_BITS = 24
        private const val VALUE_MASK = (1L shl VALUE_BITS) - 1
        private const val KEY_MASK = VALUE_MASK.inv()

        private const val CACHE_BITS = 15
        private const val HASH_A = -0x61c88647 // 0x9e3779b9
        private const val HASH_B = -0x7a143595 // 0x85ebca6b
    }
}
