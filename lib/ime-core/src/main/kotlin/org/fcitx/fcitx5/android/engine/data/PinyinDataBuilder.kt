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

    private val model = NgramWords()
    private val entries = ArrayList<Entry>()
    private var layerNames = listOf(WordLayers.BASE)

    /** Names the dictionary's layers, [names] indexed as [entry]'s layer; the first is the base. */
    fun layers(names: List<String>) = apply {
        require(names.isNotEmpty() && names.all { it.isNotEmpty() && ',' !in it }) { "layer names $names" }
        require(names.toSet().size == names.size) { "a layer named twice in $names" }
        require(entries.all { it.layer < names.size }) { "an entry added already is of a layer past $names" }
        layerNames = names
    }

    fun unigram(word: String, prob: Float, backoff: Float) = apply { model.unigram(word, prob, backoff) }

    /** [word]'s unigram probability, or null if it was not added as one. */
    fun unigramOf(word: String): Float? = model[word]?.let { model.tables.unigramProb(it) }

    fun bigram(prev: String, word: String, prob: Float, backoff: Float) = apply { model.bigram(prev, word, prob, backoff) }

    fun trigram(prev2: String, prev: String, word: String, prob: Float) = apply { model.trigram(prev2, prev, word, prob) }

    /**
     * [word] is spelt [syllables] (ids from [Syllables]). [weight] is a log10 adjustment for
     * this reading: 0 for most words, below 0 for a polyphone's rarer reading (呆 as ai rather
     * than dai). libime's data also has a few small positive ones, so it is not a probability.
     */
    fun entry(word: String, syllables: IntArray, weight: Float = 0f, layer: Int = 0) = apply {
        require(syllables.isNotEmpty()) { "\"$word\" has no syllables" }
        syllables.forEach { require(it in 0 until Syllables.count) { "\"$word\": bad syllable id $it" } }
        require(weight.isFinite()) { "\"$word\": weight $weight" }
        require(layer in layerNames.indices) { "\"$word\": layer $layer of ${layerNames.size}" }
        entries += Entry(syllables, word, weight, layer)
    }

    private var built = false

    /** Once only: building appends the dictionary's own words to the vocabulary. */
    fun build(meta: Map<String, String> = emptyMap()): DataFile.Writer {
        check(!built) { "already built" }
        val lmSize = model.words.size
        val unknown = requireNotNull(model[UNKNOWN]) { "the model has no $UNKNOWN" }
        built = true
        // dictionary words the model lacks get ids after the model's own
        entries.forEach { e -> model.add(e.word) }
        val out = DataFile.Writer(DataFile.KIND_PINYIN, PinyinData.VERSION)
        out.addMeta(
            Section.META,
            meta + mapOf(
                PinyinData.META_SYLLABLE_COUNT to Syllables.count.toString(),
                PinyinData.META_SYLLABLE_CHECKSUM to Syllables.checksum().toString(),
                PinyinData.META_LM_VOCABULARY to lmSize.toString(),
                PinyinData.META_UNKNOWN to unknown.toString(),
                PinyinData.META_LAYERS to layerNames.joinToString(","),
            )
        )
        writeVocabulary(out)
        writeDictionary(out) { id -> model.tables.unigramProb(if (id < lmSize) id else unknown) }
        writeLayers(out)
        model.tables.write(out, lmSize)
        return out
    }

    private fun writeVocabulary(out: DataFile.Writer) = StringTable.write(out, Section.VOCAB_OFFSETS, Section.VOCAB_CHARS, model.words)

    private class Entry(val syllables: IntArray, val word: String, val weight: Float, val layer: Int)

    /** A word's layer is the first it has an entry in; a model word with no entry is of the base. */
    private fun writeLayers(out: DataFile.Writer) {
        if (layerNames.size == 1) return
        val size = model.words.size
        val layer = IntArray(size) { -1 }
        entries.forEach { e ->
            val id = model.id(e.word)
            layer[id] = if (layer[id] < 0) e.layer else minOf(layer[id], e.layer)
        }
        out.add(Section.WORD_LAYER, BitPacked.encode(size) { maxOf(layer[it], 0) })
    }

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
            val id = model.id(e.word)
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

    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

    /** Empties the list, keeping its storage for reuse. */
    fun clear() {
        size = 0
    }

    fun toArray(): IntArray = a.copyOf(size)
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

    operator fun set(i: Int, v: Float) {
        a[i] = v
    }

    /** Empties the list, keeping its storage for reuse. */
    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = a.copyOf(size)
}
