/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.nio.ByteBuffer

/**
 * The compiled pinyin data: one vocabulary shared by a syllable-keyed dictionary and an n-gram
 * language model, in a single [DataFile] so the three can never be mixed across builds.
 * Written by [PinyinDataBuilder].
 */
class PinyinData private constructor(file: DataFile) {

    val meta: Map<String, String> = file.meta(Section.META)

    // checked before anything else is read: every id in the file depends on the table
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
    }

    companion object {
        /** Layout of the sections below; bump on any change to it. */
        const val VERSION = 1

        internal const val META_SYLLABLE_COUNT = "syllables.count"
        internal const val META_SYLLABLE_CHECKSUM = "syllables.checksum"
        internal const val META_LM_VOCABULARY = "lm.vocabulary"
        internal const val META_UNKNOWN = "lm.unknown"

        /** @throws DataFormatException if [buffer] is not pinyin data this build can read */
        fun load(buffer: ByteBuffer): PinyinData = PinyinData(DataFile.open(buffer, DataFile.KIND_PINYIN, VERSION))

        private fun Map<String, String>.int(key: String): Int =
            get(key)?.toIntOrNull() ?: throw DataFormatException("meta $key missing")
    }
}

/** Word ids to text. Ids below [NgramModel.vocabularySize] are the model's own words. */
class Vocabulary internal constructor(private val strings: StringTable) {
    val size: Int get() = strings.size
    fun word(id: Int): String = strings[id]
}

/**
 * A trie over syllable ids. Nodes are numbered breadth-first from the root (0), so the children
 * of a node are a contiguous run of node ids, sorted by syllable; a node's words are the words
 * spelt by the syllables on the path to it, most probable first.
 */
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
        // breadth-first: the root's children come right after it, and every node but the root
        // is exactly one node's child, so the child runs end at nodeCount
        childStart.checkRuns("dictionary children", 1, nodes)
        // children after their parent, so no path through the trie can loop back on itself
        for (node in 0 until nodes) ensureFormat(childStart[node] > node) { "dictionary node $node precedes its parent" }
        wordStart.checkRuns("dictionary words", 0, words.size)
        words.checkBelow("dictionary words", vocabularySize)
    }

    val root: Int get() = 0
    val nodeCount: Int get() = syllables.size

    /** The syllable on the edge into [node]; meaningless for the root. */
    fun syllable(node: Int): Int = syllables[node]

    fun firstChild(node: Int): Int = childStart[node]
    fun childCount(node: Int): Int = childStart[node + 1] - childStart[node]

    /** @return the child of [node] reached by [syllable], or -1 */
    fun child(node: Int, syllable: Int): Int {
        val from = childStart[node]
        val to = childStart[node + 1]
        val at = syllables.lowerBound(from, to, syllable)
        return if (at < to && syllables[at] == syllable) at else -1
    }

    /** @return the node spelt by [path] from the root, or -1 */
    fun find(path: IntArray): Int = path.fold(root) { node, s -> if (node < 0) -1 else child(node, s) }

    fun wordCount(node: Int): Int = wordStart[node + 1] - wordStart[node]

    /** [index] must be below [wordCount]; it is not checked, and past it lie the next node's words. */
    fun word(node: Int, index: Int): Int = words[wordStart[node] + index]

    /** The log10 adjustment for this reading of [word]; 0 unless it is a polyphone's rarer reading */
    fun weight(node: Int, index: Int): Float = weightTable[weights.get(wordStart[node] + index).toInt() and 0xff]
}

/**
 * A backoff n-gram model up to trigrams, stored as sorted arrays: each unigram points at the run
 * of its bigrams (sorted by next word), each bigram at the run of its trigrams. Scores are log10
 * probabilities, as in the ARPA file it was built from; bigram and trigram values are quantised
 * to a byte (see [Quantizer]).
 *
 * Words from [vocabularySize] up are dictionary words the model never saw; they score as the
 * model's unknown word. [NO_WORD] as a context means there is none (the start of input), and
 * shortens the history: with `prev == NO_WORD`, `prev2` is ignored. The first word thus scores
 * as its unigram, which is what libime gets from the same model: lm_sc.arpa has no `<s>`, and
 * KenLM gives a missing `<s>` no bigrams and a backoff of 0.
 */
class NgramModel internal constructor(file: DataFile, val vocabularySize: Int, private val unknown: Int) {

    private val uniProb = file.section(PinyinData.Section.UNI_PROB)
    private val uniBackoff = file.section(PinyinData.Section.UNI_BACKOFF)
    private val uniBigramStart = file.bitPacked(PinyinData.Section.UNI_BIGRAM_START)
    private val biWord = file.bitPacked(PinyinData.Section.BI_WORD)
    private val biProb = file.section(PinyinData.Section.BI_PROB)
    private val biBackoff = file.section(PinyinData.Section.BI_BACKOFF)
    private val biTrigramStart = file.bitPacked(PinyinData.Section.BI_TRIGRAM_START)
    private val biProbTable = Quantizer.decodeTable(file.section(PinyinData.Section.BI_PROB_CODEBOOK))
    private val biBackoffTable = Quantizer.decodeTable(file.section(PinyinData.Section.BI_BACKOFF_CODEBOOK))
    private val triWord = file.bitPacked(PinyinData.Section.TRI_WORD)
    private val triProb = file.section(PinyinData.Section.TRI_PROB)
    private val triProbTable = Quantizer.decodeTable(file.section(PinyinData.Section.TRI_PROB_CODEBOOK))

    // every size before any value: a value read from a table of the wrong size could be out of bounds
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

    /** log10 P(word) */
    fun score(word: Int): Float = uniProb.getFloat(known(word) * 4)

    /** log10 P(word | prev); a [NO_WORD] context is the unigram score */
    fun score(prev: Int, word: Int): Float {
        if (prev == NO_WORD) return score(word)
        val p = known(prev)
        val w = known(word)
        val bigram = bigram(p, w)
        return if (bigram >= 0) biProbTable[biProb.get(bigram).toInt() and 0xff] else uniBackoff.getFloat(p * 4) + score(w)
    }

    /** log10 P(word | prev2 prev) */
    fun score(prev2: Int, prev: Int, word: Int): Float {
        if (prev2 == NO_WORD || prev == NO_WORD) return score(prev, word)
        val context = bigram(known(prev2), known(prev))
        if (context < 0) return score(prev, word)
        val w = known(word)
        val from = biTrigramStart[context]
        val to = biTrigramStart[context + 1]
        val at = triWord.lowerBound(from, to, w)
        if (at < to && triWord[at] == w) return triProbTable[triProb.get(at).toInt() and 0xff]
        return biBackoffTable[biBackoff.get(context).toInt() and 0xff] + score(prev, word)
    }

    /** log10 backoff weight of [word] as a context; 0 when it has none */
    fun backoff(word: Int): Float = uniBackoff.getFloat(known(word) * 4)

    /** log10 backoff weight of the context (prev, word); 0 for a bigram the model lacks */
    fun backoff(prev: Int, word: Int): Float {
        val bigram = bigram(known(prev), known(word))
        return if (bigram >= 0) biBackoffTable[biBackoff.get(bigram).toInt() and 0xff] else 0f
    }

    /** @return the index of bigram (prev, word), or -1 */
    private fun bigram(prev: Int, word: Int): Int {
        val from = uniBigramStart[prev]
        val to = uniBigramStart[prev + 1]
        val at = biWord.lowerBound(from, to, word)
        return if (at < to && biWord[at] == word) at else -1
    }

    companion object {
        const val NO_WORD = -1
    }
}
