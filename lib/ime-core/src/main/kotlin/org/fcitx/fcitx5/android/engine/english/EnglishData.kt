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

/**
 * The compiled English data: the words as they are written (I, London, don't) and a trigram model
 * over them, in one [DataFile]. Words are also in the order of their [key]s, the letters typed for
 * them, for finding a word by its keys, or by keys near them. Written by [EnglishDataBuilder].
 * That order found broken is reported to [onError].
 */
class EnglishData private constructor(file: DataFile, private val onError: (DataFormatException) -> Unit) {

    val meta: Map<String, String> = file.meta(Section.META)

    val vocabulary = Vocabulary(StringTable(file.bitPacked(Section.VOCAB_OFFSETS), file.section(Section.VOCAB_CHARS)))
    val model = NgramModel(file, meta.int(META_LM_VOCABULARY), meta.int(META_UNKNOWN))

    /** The sentence start, `<s>`: the context of a sentence's first word. */
    val start: Int = meta.int(META_START)

    /** The model's unknown word, `<unk>`: typed for none. */
    val unknown: Int = meta.int(META_UNKNOWN)

    private val keyOrder: BitPacked = file.bitPacked(KEY_ORDER)

    init {
        ensureFormat(model.vocabularySize == vocabulary.size) { "the model has ${model.vocabularySize} words of ${vocabulary.size}" }
        ensureFormat(start in 0 until vocabulary.size) { "start $start is no word" }
        keyOrder.checkBelow("key order", vocabulary.size)
    }

    /** How many words are in key order: all but the model's own (`<s>`, `<unk>`); none if the order is not. */
    val keyed: Int get() = if (keyForms != null) keyOrder.size else 0

    /** The word at [rank] in key order. */
    fun keyedWord(rank: Int): Int = keyOrder[rank]

    // made when first asked for, as WordIndex is: how each key reads from its word where the word
    // lies ([key]), rather than 200k strings kept, an estimated 8 MB of heap. Checked here, not on
    // load (unverified there, as the app's): keys out of order would send the search past a key's
    // end, and a file that broken offers no word, [onError] told, rather than fail on a key
    private val keyForms: ByteArray? by lazy {
        val forms = ByteArray(keyOrder.size) { form(keyOrder[it]) }
        val disordered = (1 until forms.size).firstOrNull { compareKeys(it - 1, it, forms) > 0 }
        if (disordered == null) return@lazy forms
        onError(DataFormatException("key $disordered of ${forms.size} out of order: English offers no word"))
        null
    }

    /** The length of the key at [rank] in key order. */
    fun keyLength(rank: Int): Int = keyLength(rank, checkNotNull(keyForms))

    /** The char at [at] of the key at [rank] in key order, read where its word lies. */
    fun keyChar(rank: Int, at: Int): Char = keyChar(rank, at, checkNotNull(keyForms))

    /** The key at [rank] in key order, made a string: the search reads it in place ([keyLength], [keyChar]). */
    fun keyAt(rank: Int): String = key(vocabulary.word(keyOrder[rank]))

    // how [key] makes [word]'s key: as it is, lower-cased, or less its apostrophes too
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
        // dont for don't: the char at [at] of those that are no apostrophe
        var left = at
        for (i in 0 until vocabulary.length(word)) {
            val c = vocabulary.char(word, i)
            if (c != '\'' && left-- == 0) return c.lowercaseChar()
        }
        throw IndexOutOfBoundsException("key $rank has no char $at")
    }

    // as String.compareTo compares the keys, which the builder sorted
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

    /** The id of the word written [text], exactly, or [NgramModel.NO_WORD]. */
    fun id(text: CharSequence): Int = index.find(text)

    fun word(id: Int): String = vocabulary.word(id)

    /** Builds what is made when first asked for, off the thread that types. */
    fun warmUp() {
        keyForms
        index
    }

    companion object {
        /** Layout of the sections; bump on any change to it. 2: the model's trigram runs' starts in EliasFano. */
        const val VERSION = 2

        internal const val META_LM_VOCABULARY = "lm.vocabulary"
        internal const val META_UNKNOWN = "lm.unknown"
        internal const val META_START = "lm.start"

        // past the pinyin data's numbers, whose vocabulary and model sections this file shares: a
        // number taken twice is refused as the file is written (DataFile.Writer.add)
        internal const val KEY_ORDER = 30

        private const val KEY_AS_IS: Byte = 0
        private const val KEY_LOWERED: Byte = 1
        private const val KEY_STRIPPED: Byte = 2

        /** What is typed for [word]: its letters, lower case, without apostrophes (dont for don't). */
        fun key(word: String): String {
            if (word.none { it == '\'' || it.isUpperCase() }) return word
            val b = StringBuilder(word.length)
            for (c in word) if (c != '\'') b.append(c.lowercaseChar())
            return b.toString()
        }

        /**
         * @param onError told if the key order is found broken, when first used: English then offers no word
         * @throws DataFormatException if [buffer] is not English data this build can read
         */
        fun load(buffer: ByteBuffer, verify: Boolean = true, onError: (DataFormatException) -> Unit = {}): EnglishData =
            EnglishData(DataFile.open(buffer, DataFile.KIND_ENGLISH, VERSION, verify), onError)

        private fun Map<String, String>.int(key: String): Int =
            get(key)?.toIntOrNull() ?: throw DataFormatException("meta $key missing")
    }
}

/**
 * Compiles an ARPA-style backoff model of English words into the layout [EnglishData] reads. Runs on
 * the build host (lib/ime-dict-tool) and in tests. The words are the unigrams, in the order added,
 * which must be commonest first, as english-lm writes them (EnglishVocabulary's order):
 * EnglishDecoder takes the ids below EnglishDecoder.FREQUENT as a long run's common words and the
 * lower of two ids as the commoner, and an ARPA file has no counts to check that by;
 * [UNKNOWN] and [EnglishText.START] must be among them.
 */
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
        // the words typed, by key, the likelier of one key first; the model's own are typed by none
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
