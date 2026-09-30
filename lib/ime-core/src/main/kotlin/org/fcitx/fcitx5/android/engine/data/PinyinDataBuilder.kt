/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.PinyinData.Section
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Compiles a dictionary and an ARPA-style backoff model into the layout [PinyinData] reads.
 * Runs on the build host (lib/ime-dict-tool) and in tests; nothing on the device writes this.
 *
 * Every word of a bigram or trigram must have been added as a unigram first, and every trigram
 * needs its leading bigram, as in any well-formed ARPA file. [UNKNOWN] must be among the
 * unigrams: dictionary words the model lacks score as it.
 */
class PinyinDataBuilder {

    private val ids = HashMap<String, Int>()
    private val words = ArrayList<String>()
    private val uniProb = FloatList()
    private val uniBackoff = FloatList()
    private val biPrev = IntList()
    private val biWord = IntList()
    private val biProb = FloatList()
    private val biBackoff = FloatList()
    private val triPrev2 = IntList()
    private val triPrev = IntList()
    private val triWord = IntList()
    private val triProb = FloatList()
    private val entries = ArrayList<Entry>()

    fun unigram(word: String, prob: Float, backoff: Float) = apply {
        finite(word, prob, backoff)
        require(ids.put(word, words.size) == null) { "unigram \"$word\" added twice" }
        words += word
        uniProb += prob
        uniBackoff += backoff
    }

    fun bigram(prev: String, word: String, prob: Float, backoff: Float) = apply {
        finite("$prev $word", prob, backoff)
        biPrev += lmId(prev)
        biWord += lmId(word)
        biProb += prob
        biBackoff += backoff
    }

    fun trigram(prev2: String, prev: String, word: String, prob: Float) = apply {
        finite("$prev2 $prev $word", prob, 0f)
        triPrev2 += lmId(prev2)
        triPrev += lmId(prev)
        triWord += lmId(word)
        triProb += prob
    }

    /**
     * [word] is spelt [syllables] (ids from [Syllables]). [weight] is a log10 adjustment for
     * this reading: 0 for most words, below 0 for a polyphone's rarer reading (呆 as ai rather
     * than dai). libime's data also has a few small positive ones, so it is not a probability.
     */
    fun entry(word: String, syllables: IntArray, weight: Float = 0f) = apply {
        require(syllables.isNotEmpty()) { "\"$word\" has no syllables" }
        syllables.forEach { require(it in 0 until Syllables.count) { "\"$word\": bad syllable id $it" } }
        require(weight.isFinite()) { "\"$word\": weight $weight" }
        entries += Entry(syllables, word, weight)
    }

    // a NaN would poison the quantiser: it equals nothing, so it breaks every bin it lands in
    private fun finite(ngram: String, prob: Float, backoff: Float) =
        require(prob.isFinite() && backoff.isFinite()) { "\"$ngram\": $prob $backoff" }

    private fun lmId(word: String) = requireNotNull(ids[word]) { "\"$word\" is not a unigram" }

    private var built = false

    /** Once only: building appends the dictionary's own words to the vocabulary. */
    fun build(meta: Map<String, String> = emptyMap()): DataFile.Writer {
        check(!built) { "already built" }
        val lmSize = words.size
        val unknown = requireNotNull(ids[UNKNOWN]) { "the model has no $UNKNOWN" }
        built = true
        // dictionary words the model lacks get ids after the model's own
        entries.forEach { e -> ids.getOrPut(e.word) { words.size.also { words += e.word } } }
        val out = DataFile.Writer(DataFile.KIND_PINYIN, PinyinData.VERSION)
        out.addMeta(
            Section.META,
            meta + mapOf(
                PinyinData.META_SYLLABLE_COUNT to Syllables.count.toString(),
                PinyinData.META_SYLLABLE_CHECKSUM to Syllables.checksum().toString(),
                PinyinData.META_LM_VOCABULARY to lmSize.toString(),
                PinyinData.META_UNKNOWN to unknown.toString(),
            )
        )
        writeVocabulary(out)
        writeDictionary(out) { id -> uniProb[if (id < lmSize) id else unknown] }
        writeModel(out, lmSize)
        return out
    }

    private fun writeVocabulary(out: DataFile.Writer) = StringTable.write(out, Section.VOCAB_OFFSETS, Section.VOCAB_CHARS, words)

    private class Entry(val syllables: IntArray, val word: String, val weight: Float)

    private class Node(val syllable: Int) {
        val children = sortedMapOf<Int, Node>()

        /** word id to reading weight */
        val words = HashMap<Int, Float>()
    }

    private fun writeDictionary(out: DataFile.Writer, score: (Int) -> Float) {
        val root = Node(0)
        entries.forEach { e ->
            val node = e.syllables.fold(root) { node, s -> node.children.getOrPut(s) { Node(s) } }
            // the same reading listed twice (say, in two source files) keeps its likelier weight
            val id = ids.getValue(e.word)
            node.words[id] = maxOf(e.weight, node.words[id] ?: e.weight)
        }
        // breadth-first, so that each node's children get consecutive ids
        val order = ArrayList<Node>().apply { add(root) }
        var i = 0
        while (i < order.size) order += order[i++].children.values
        val childStart = IntArray(order.size + 1)
        val wordStart = IntArray(order.size + 1)
        var nextChild = 1
        order.forEachIndexed { n, node ->
            childStart[n] = nextChild
            nextChild += node.children.size
            wordStart[n + 1] = wordStart[n] + node.words.size
        }
        childStart[order.size] = nextChild
        // most probable first, so a caller wanting the top few can stop early
        val nodeWords = order.flatMap { node ->
            node.words.entries.sortedWith(compareByDescending<Map.Entry<Int, Float>> { score(it.key) + it.value }.thenBy { it.key })
        }
        val weights = FloatArray(nodeWords.size) { nodeWords[it].value }
        val weightQ = Quantizer.build(weights, exactZero = true)
        out.add(Section.DICT_SYLLABLE, BitPacked.encode(order.size) { order[it].syllable })
        out.add(Section.DICT_CHILD_START, BitPacked.encode(childStart))
        out.add(Section.DICT_WORD_START, BitPacked.encode(wordStart))
        out.add(Section.DICT_WORDS, BitPacked.encode(nodeWords.size) { nodeWords[it].key })
        out.add(Section.DICT_WEIGHT, ByteArray(weights.size) { weightQ.encode(weights[it]).toByte() })
        out.add(Section.DICT_WEIGHT_CODEBOOK, weightQ.codebook())
    }

    private fun writeModel(out: DataFile.Writer, lmSize: Int) {
        val bi = (0 until biWord.size).sortedWith(compareBy<Int>({ biPrev[it] }, { biWord[it] }))
        val biKeys = LongArray(bi.size) { key(biPrev[bi[it]], biWord[bi[it]]) }
        for (k in 1 until biKeys.size) require(biKeys[k - 1] != biKeys[k]) { "duplicate bigram at ${bi[k]}" }
        // each trigram's context is the position of its leading bigram in sorted order
        val triContext = IntArray(triWord.size) { t ->
            val at = biKeys.binarySearch(key(triPrev2[t], triPrev[t]))
            require(at >= 0) { "trigram ${words[triPrev2[t]]} ${words[triPrev[t]]} ${words[triWord[t]]} has no leading bigram" }
            at
        }
        val tri = (0 until triWord.size).sortedWith(compareBy<Int>({ triContext[it] }, { triWord[it] }))
        for (k in 1 until tri.size) {
            require(triContext[tri[k - 1]] != triContext[tri[k]] || triWord[tri[k - 1]] != triWord[tri[k]]) { "duplicate trigram at ${tri[k]}" }
        }

        out.add(Section.UNI_PROB, floats(lmSize) { uniProb[it] })
        out.add(Section.UNI_BACKOFF, floats(lmSize) { uniBackoff[it] })
        out.add(Section.UNI_BIGRAM_START, BitPacked.encode(starts(lmSize, bi.size) { biPrev[bi[it]] }))
        out.add(Section.BI_WORD, BitPacked.encode(bi.size) { biWord[bi[it]] })
        val biProbQ = Quantizer.build(biProb.toArray(), exactZero = false)
        val biBackoffQ = Quantizer.build(biBackoff.toArray(), exactZero = true)
        out.add(Section.BI_PROB, ByteArray(bi.size) { biProbQ.encode(biProb[bi[it]]).toByte() })
        out.add(Section.BI_BACKOFF, ByteArray(bi.size) { biBackoffQ.encode(biBackoff[bi[it]]).toByte() })
        out.add(Section.BI_PROB_CODEBOOK, biProbQ.codebook())
        out.add(Section.BI_BACKOFF_CODEBOOK, biBackoffQ.codebook())
        out.add(Section.BI_TRIGRAM_START, BitPacked.encode(starts(bi.size, tri.size) { triContext[tri[it]] }))
        val triProbQ = Quantizer.build(triProb.toArray(), exactZero = false)
        out.add(Section.TRI_WORD, BitPacked.encode(tri.size) { triWord[tri[it]] })
        out.add(Section.TRI_PROB, ByteArray(tri.size) { triProbQ.encode(triProb[tri[it]]).toByte() })
        out.add(Section.TRI_PROB_CODEBOOK, triProbQ.codebook())
    }

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or b.toLong()

    private fun floats(size: Int, value: (Int) -> Float): ByteArray =
        LittleEndianOutput(size * 4).apply { for (i in 0 until size) writeFloat(value(i)) }.toByteArray()

    /** For [count] items sorted by [owner] in 0 until [owners]: where each owner's run starts, plus the end. */
    private fun starts(owners: Int, count: Int, owner: (Int) -> Int): IntArray {
        val starts = IntArray(owners + 1)
        for (i in 0 until count) starts[owner(i) + 1]++
        for (o in 0 until owners) starts[o + 1] += starts[o]
        return starts
    }

    companion object {
        const val UNKNOWN = "<unk>"
    }
}

internal class IntList {
    private var a = IntArray(16)
    var size = 0
        private set

    operator fun plusAssign(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
}

internal class FloatList {
    private var a = FloatArray(16)
    var size = 0
        private set

    operator fun plusAssign(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
}
