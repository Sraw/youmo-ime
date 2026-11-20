/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.data.BitPacked
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.NgramWords
import org.fcitx.fcitx5.android.engine.data.PinyinData.Section
import org.fcitx.fcitx5.android.engine.data.StringTable
import org.fcitx.fcitx5.android.engine.data.Vocabulary
import org.fcitx.fcitx5.android.engine.data.WordIndex
import org.fcitx.fcitx5.android.engine.data.ensureFormat
import java.nio.ByteBuffer

/** Compiled English data: words as written (I, London, don't), a trigram model, and words sorted by [key] for lookup. */
class EnglishData private constructor(file: DataFile, private val onError: (DataFormatException) -> Unit) {

    val meta: Map<String, String> = file.meta(Section.META)

    val vocabulary = Vocabulary(StringTable(file.bitPacked(Section.VOCAB_OFFSETS), file.section(Section.VOCAB_CHARS)))
    val model = NgramModel(file, meta.int(META_LM_VOCABULARY), meta.int(META_UNKNOWN))

    /** `<s>`: the context of a sentence's first word. */
    val start: Int = meta.int(META_START)

    val unknown: Int = meta.int(META_UNKNOWN)

    private val keyOrder: BitPacked = file.bitPacked(KEY_ORDER)

    init {
        ensureFormat(model.vocabularySize == vocabulary.size) { "the model has ${model.vocabularySize} words of ${vocabulary.size}" }
        ensureFormat(start in 0 until vocabulary.size) { "start $start is no word" }
        keyOrder.checkBelow("key order", vocabulary.size)
    }

    val keyed: Int get() = if (keyForms != null) keyOrder.size else 0

    fun keyedWord(rank: Int): Int = keyOrder[rank]

    // lazy: keys read in place, not 200k strings; checked here, not at unverified load, so a broken order offers no
    // word and tells [onError] instead of failing on a key
    private val keyForms: ByteArray? by lazy {
        val forms = ByteArray(keyOrder.size) { form(keyOrder[it]) }
        val disordered = (1 until forms.size).firstOrNull { compareKeys(it - 1, it, forms) > 0 }
        if (disordered == null) return@lazy forms
        onError(DataFormatException("key $disordered of ${forms.size} out of order: English offers no word"))
        null
    }

    fun keyLength(rank: Int): Int = keyLength(rank, checkNotNull(keyForms))

    fun keyChar(rank: Int, at: Int): Char = keyChar(rank, at, checkNotNull(keyForms))

    fun keyAt(rank: Int): String = key(vocabulary.word(keyOrder[rank]))

    private fun form(word: Int): Byte {
        var form = KEY_AS_IS
        for (i in 0 until vocabulary.length(word)) {
            val c = vocabulary.char(word, i)
            if (c == '\'') return KEY_STRIPPED
            if (c.isUpperCase()) form = KEY_LOWERED
        }
        return form
    }

    private fun keyLength(rank: Int, forms: ByteArray): Int {
        val word = keyOrder[rank]
        val length = vocabulary.length(word)
        if (forms[rank] != KEY_STRIPPED) return length
        var n = 0
        for (i in 0 until length) if (vocabulary.char(word, i) != '\'') n++
        return n
    }

    private fun keyChar(rank: Int, at: Int, forms: ByteArray): Char {
        val word = keyOrder[rank]
        when (forms[rank]) {
            KEY_AS_IS -> return vocabulary.char(word, at)
            KEY_LOWERED -> return vocabulary.char(word, at).lowercaseChar()
        }
        var left = at
        for (i in 0 until vocabulary.length(word)) {
            val c = vocabulary.char(word, i)
            if (c != '\'' && left-- == 0) return c.lowercaseChar()
        }
        throw IndexOutOfBoundsException("key $rank has no char $at")
    }

    // as String.compareTo, which the builder sorted by
    private fun compareKeys(a: Int, b: Int, forms: ByteArray): Int {
        val lengthA = keyLength(a, forms)
        val lengthB = keyLength(b, forms)
        for (k in 0 until minOf(lengthA, lengthB)) {
            val d = keyChar(a, k, forms).compareTo(keyChar(b, k, forms))
            if (d != 0) return d
        }
        return lengthA.compareTo(lengthB)
    }

    private val index: WordIndex by lazy { WordIndex(vocabulary, vocabulary.size) }

    fun id(text: CharSequence): Int = index.find(text)

    fun word(id: Int): String = vocabulary.word(id)

    fun warmUp() {
        keyForms
        index
    }

    companion object {
        /** Bump on any layout change. */
        const val VERSION = 2

        internal const val META_LM_VOCABULARY = "lm.vocabulary"
        internal const val META_UNKNOWN = "lm.unknown"
        internal const val META_START = "lm.start"

        // past pinyin data's section ids, whose vocabulary and model sections this file shares; a clash is refused when written
        internal const val KEY_ORDER = 30

        private const val KEY_AS_IS: Byte = 0
        private const val KEY_LOWERED: Byte = 1
        private const val KEY_STRIPPED: Byte = 2

        fun key(word: String): String {
            if (word.none { it == '\'' || it.isUpperCase() }) return word
            val b = StringBuilder(word.length)
            for (c in word) if (c != '\'') b.append(c.lowercaseChar())
            return b.toString()
        }

        /** @throws DataFormatException if [buffer] is not English data this build can read */
        fun load(buffer: ByteBuffer, verify: Boolean = true, onError: (DataFormatException) -> Unit = {}): EnglishData =
            EnglishData(DataFile.open(buffer, DataFile.KIND_ENGLISH, VERSION, verify), onError)

        private fun Map<String, String>.int(key: String): Int =
            get(key)?.toIntOrNull() ?: throw DataFormatException("meta $key missing")
    }
}

/** Compiles an English ARPA model into [EnglishData] (build host and tests). Unigrams must come commonest first, as english-lm writes them: the decoder takes a lower id as commoner. */
class EnglishDataBuilder {
    private val model = NgramWords()

    fun unigram(word: String, prob: Float, backoff: Float) = apply { model.unigram(word, prob, backoff) }

    fun bigram(prev: String, word: String, prob: Float, backoff: Float) = apply { model.bigram(prev, word, prob, backoff) }

    fun trigram(prev2: String, prev: String, word: String, prob: Float) = apply { model.trigram(prev2, prev, word, prob) }

    fun build(meta: Map<String, String> = emptyMap()): DataFile.Writer {
        val words = model.words
        val tables = model.tables
        val unknown = model.id(UNKNOWN)
        val start = model.id(EnglishText.START)
        val out = DataFile.Writer(DataFile.KIND_ENGLISH, EnglishData.VERSION)
        out.addMeta(
            Section.META,
            meta + mapOf(
                EnglishData.META_LM_VOCABULARY to words.size.toString(),
                EnglishData.META_UNKNOWN to unknown.toString(),
                EnglishData.META_START to start.toString(),
            ),
        )
        StringTable.write(out, Section.VOCAB_OFFSETS, Section.VOCAB_CHARS, words)
        tables.write(out, words.size)
        val typed = words.indices.filter { it != unknown && it != start }
        val keys = typed.associateWith { EnglishData.key(words[it]) }
        val order = typed.sortedWith(compareBy<Int> { keys.getValue(it) }.thenByDescending { tables.unigramProb(it) }.thenBy { it })
        out.add(EnglishData.KEY_ORDER, BitPacked.encode(order.size) { order[it] })
        return out
    }

    companion object {
        const val UNKNOWN = "<unk>"
    }
}
