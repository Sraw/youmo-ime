/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.data.PinyinData.Section
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/** Compiles a dictionary and an ARPA backoff model into [PinyinData] (build host and tests). [UNKNOWN] must be a unigram: missing words score as it. */
class PinyinDataBuilder {

    private val model = NgramWords()
    private val entries = ArrayList<Entry>()
    private var layerNames = listOf(WordLayers.BASE)

    fun layers(names: List<String>) = apply {
        require(names.isNotEmpty() && names.all { it.isNotEmpty() && ',' !in it }) { "layer names $names" }
        require(names.toSet().size == names.size) { "a layer named twice in $names" }
        require(entries.all { it.layer < names.size }) { "an entry added already is of a layer past $names" }
        layerNames = names
    }

    fun unigram(word: String, prob: Float, backoff: Float) = apply { model.unigram(word, prob, backoff) }

    fun unigramOf(word: String): Float? = model[word]?.let { model.tables.unigramProb(it) }

    fun bigram(prev: String, word: String, prob: Float, backoff: Float) = apply { model.bigram(prev, word, prob, backoff) }

    fun trigram(prev2: String, prev: String, word: String, prob: Float) = apply { model.trigram(prev2, prev, word, prob) }

    /** [weight]: log10 adjustment for this reading (libime has a few positive ones, so not a probability). */
    fun entry(word: String, syllables: IntArray, weight: Float = 0f, layer: Int = 0) = apply {
        require(syllables.isNotEmpty()) { "\"$word\" has no syllables" }
        syllables.forEach { require(it in 0 until Syllables.count) { "\"$word\": bad syllable id $it" } }
        require(weight.isFinite()) { "\"$word\": weight $weight" }
        require(layer in layerNames.indices) { "\"$word\": layer $layer of ${layerNames.size}" }
        entries += Entry(syllables, word, weight, layer)
    }

    private val charTones = HashMap<Int, Int>()

    private val wordTones = HashMap<String, HashMap<List<Int>, ByteArray>>()
    private val curated = HashSet<Pair<String, List<Int>>>()

    private val charReadings = CharReadingsBuilder()

    /** Call in order of likelihood: the first tone given for a character and syllable is its usual one. */
    fun charTone(codePoint: Int, syllable: Int, tone: Int) = apply {
        require(codePoint in 0 until ToneTable.MAX_CODE_POINT && syllable in 0 until Syllables.count && tone in 1..ToneMark.NEUTRAL) {
            "tone $tone of U+${codePoint.toString(16)} as syllable $syllable"
        }
        val key = codePoint * ToneTable.KEY_SPAN + syllable
        val old = charTones[key] ?: tone
        charTones[key] = old or (1 shl (tone - 1 + ToneTable.MASK_SHIFT))
    }

    fun charReading(source: ReadingSource, codePoint: Int, syllable: Int, tone: Int) = apply {
        require(codePoint in 0 until ToneTable.MAX_CODE_POINT && syllable in 0 until Syllables.count && tone in 1..ToneMark.NEUTRAL) {
            "reading $syllable $tone of U+${codePoint.toString(16)}"
        }
        charReadings.add(source, codePoint, syllable, tone)
    }

    /** A later call for the same reading replaces it, so feed least trusted first; [curated] tones skip [ToneRules]. */
    fun wordTones(word: String, syllables: IntArray, tones: ByteArray, curated: Boolean = false) = apply {
        val cps = word.codePoints().toArray()
        require(cps.size == syllables.size && tones.size == syllables.size) { "\"$word\": ${syllables.size} syllables, ${tones.size} tones" }
        require(tones.all { it in 1..ToneMark.NEUTRAL }) { "\"$word\": tones ${tones.joinToString()}" }
        val own = ByteArray(tones.size) { i -> OWN_TONES[cps[i] to syllables[i]]?.toByte() ?: tones[i] }
        wordTones.getOrPut(word) { HashMap() }[syllables.toList()] = own
        if (curated) this.curated += word to syllables.toList() else this.curated -= word to syllables.toList()
    }

    private var built = false

    fun build(meta: Map<String, String> = emptyMap()): DataFile.Writer {
        check(!built) { "already built" }
        val lmSize = model.words.size
        val unknown = requireNotNull(model[UNKNOWN]) { "the model has no $UNKNOWN" }
        built = true
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
        writeTones(out) { e, tones ->
            val id = model.id(e.word)
            val listed = wordTones[e.word]?.containsKey(e.syllables.toList()) == true
            if (id < lmSize) charReadings.word(id, e.word.codePoints().toArray(), e.syllables.toList(), tones, Math.pow(10.0, model.tables.unigramProb(id).toDouble()), listed)
        }
        charReadings.write(out)
        model.tables.write(out, lmSize)
        return out
    }

    private fun writeVocabulary(out: DataFile.Writer) = StringTable.write(out, Section.VOCAB_OFFSETS, Section.VOCAB_CHARS, model.words)

    private class Entry(val syllables: IntArray, val word: String, val weight: Float, val layer: Int)

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

    private fun likeliest(): Map<String, Entry> = HashMap<String, Entry>().also { m ->
        entries.forEach { e -> m.merge(e.word, e) { old, new -> if (new.weight > old.weight) new else old } }
    }

    /** Character tones first (what words compare with and neutral tones become), then words whose tones differ, by text so builds are reproducible. */
    private fun writeTones(out: DataFile.Writer, read: (Entry, ByteArray) -> Unit) {
        val chars = charToneBits()
        val keys = chars.keys.sorted()
        out.add(Section.TONE_CHAR_KEYS, BitPacked.encode(keys.size) { keys[it] })
        out.add(Section.TONE_CHAR, ByteArray(keys.size) { chars.getValue(keys[it]).toByte() })
        writeToneWords(out, chars, read)
    }

    private fun usual(chars: Map<Int, Int>, cps: IntArray, reading: List<Int>) =
        ByteArray(cps.size) { i -> ((chars[cps[i] * ToneTable.KEY_SPAN + reading[i]] ?: 0) and ToneTable.USUAL_MASK).toByte() }

    private fun charToneBits(): HashMap<Int, Int> {
        val chars = HashMap(charTones)
        for ((word, readings) in wordTones.entries.sortedWith(compareBy({ it.key.codePointCount(0, it.key.length) > 1 }, { it.key }))) {
            val cps = word.codePoints().toArray()
            for ((reading, listed) in readings) {
                val tones = if (cps.size == 1) listed else ToneRules.full(listed, usual(chars, cps, reading))
                cps.indices.forEach { i ->
                    val key = cps[i] * ToneTable.KEY_SPAN + reading[i]
                    // a character listed alone (谁 shéi) is its usual tone, whatever Unihan listed first
                    val usual = if (cps.size == 1) tones[i].toInt() else (chars[key] ?: tones[i].toInt()) and ToneTable.USUAL_MASK
                    val typed = listOf(tones[i], listed[i]).filter { it > 0 }.fold(0) { m, t -> m or (1 shl (t - 1 + ToneTable.MASK_SHIFT)) }
                    chars[key] = ((chars[key] ?: 0) and ToneTable.USUAL_MASK.inv()) or usual or typed
                }
            }
        }
        return chars
    }

    private fun writeToneWords(out: DataFile.Writer, chars: Map<Int, Int>, read: (Entry, ByteArray) -> Unit) {
        val exceptions = sortedMapOf<Int, MutableList<Pair<List<Int>, ByteArray>>>()
        val likeliest = likeliest()
        entries.distinctBy { it.word to it.syllables.toList() }.forEach { e ->
            val reading = e.syllables.toList()
            val own = wordTones[e.word]?.get(reading)
            val cps = e.word.codePoints().toArray()
            // a Latin word's letters (iPhone) are not a syllable each
            if (cps.size != reading.size) return@forEach
            val usual = usual(chars, cps, reading)
            if (own == null && usual.any { it.toInt() == 0 }) return@forEach
            val ruled = if (own != null && (e.word to reading) in curated) own else ToneRules.tones(e.word, cps, reading, own, usual) { w, r -> wordTones[w]?.get(r) }
            val tones = ToneRules.full(ruled, usual)
            if (likeliest[e.word]?.syllables.contentEquals(e.syllables)) read(e, tones)
            if (!usual.contentEquals(tones)) exceptions.getOrPut(model.id(e.word)) { ArrayList() } += reading to tones
        }
        val runs = exceptions.flatMap { (id, list) -> list.map { id to it } }
        val starts = IntArray(runs.size + 1).also { s -> runs.forEachIndexed { i, r -> s[i + 1] = s[i] + r.second.first.size } }
        val syllables = runs.flatMap { it.second.first }
        out.add(Section.TONE_WORDS, BitPacked.encode(runs.size) { runs[it].first })
        out.add(Section.TONE_WORD_START, BitPacked.encode(starts))
        out.add(Section.TONE_SYLLABLES, BitPacked.encode(syllables.size) { syllables[it] })
        out.add(Section.TONE_TONES, runs.flatMap { it.second.second.toList() }.toByteArray())
    }

    private class Node(val syllable: Int) {
        val children = sortedMapOf<Int, Node>()

        val words = HashMap<Int, Float>()
    }

    private fun writeDictionary(out: DataFile.Writer, score: (Int) -> Float) {
        val root = Node(0)
        entries.forEach { e ->
            val node = e.syllables.fold(root) { node, s -> node.children.getOrPut(s) { Node(s) } }
            // the same reading listed twice (two source files) keeps its likelier weight
            val id = model.id(e.word)
            node.words[id] = maxOf(e.weight, node.words[id] ?: e.weight)
        }
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

        // 一 and 不 as dictionaries list them, not as sandhi says them (yí bù)
        internal val OWN_TONES = mapOf(
            '一'.code to Syllables.id("yi") to 1,
            '不'.code to Syllables.id("bu") to 4,
        )
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

    fun addAll(other: IntList, from: Int, until: Int) {
        val count = until - from
        if (size + count > a.size) a = a.copyOf(maxOf(a.size * 2, size + count))
        System.arraycopy(other.a, from, a, size, count)
        size += count
    }

    operator fun get(i: Int) = a[i]

    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

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

    fun addAll(other: FloatList, from: Int, until: Int) {
        val count = until - from
        if (size + count > a.size) a = a.copyOf(maxOf(a.size * 2, size + count))
        System.arraycopy(other.a, from, a, size, count)
        size += count
    }

    operator fun get(i: Int) = a[i]

    operator fun set(i: Int, v: Float) {
        a[i] = v
    }

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = a.copyOf(size)
}
