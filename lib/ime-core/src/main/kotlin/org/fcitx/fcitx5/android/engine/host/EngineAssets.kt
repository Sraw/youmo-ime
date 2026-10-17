/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.CHAIZI_DATA
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.EMOJI_DATA
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.ENGLISH_DATA
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.PINYIN_DATA
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.REFINING_MODEL
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.SENTENCE_MODEL
import org.fcitx.fcitx5.android.engine.host.Engines.Companion.STROKE_DATA
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * What [Engines] reads from the app's assets ([load]), each when first asked for and kept: the
 * pinyin data and what every session over it shares (the Latin words, the characters' syllables
 * and readings, the decoder's extensions, the model's words for [TextWords]), the two sentence
 * models and the refiner over the larger, the strokes, English's words and their trigram model,
 * the emoji ([emojiOf]), and how the dictionary reads a text ([pinyinsOf]). What cannot be read is reported to
 * [onError]; the sentence models' int8 weights are multiplied by [kernel], if given, else by
 * Kotlin. Run on the fcitx thread, as [Engines] is, but for [pinyinData], which Engines' warm-up
 * reads ahead off it.
 */
internal class EngineAssets(
    private val load: (String) -> ByteBuffer,
    private val onError: (IOException) -> Unit,
    private val kernel: MatrixKernel?,
    private val emojiShows: (String) -> Boolean = { true },
    private val textShows: (String) -> Boolean = { true },
) {

    // the files are signed with the app: no need to read them through for their checksums
    // read by Engines' warm-up off the fcitx thread too: synchronized, so a session made meanwhile waits for it
    val pinyinData by lazy { PinyinData.load(load(PINYIN_DATA), verify = false) }

    // the Latin words full pinyin reads as typed in lower case (iphone for iPhone)
    val latinWords by lazy(LazyThreadSafetyMode.NONE) { LatinWords.of(pinyinData.dictionary) }

    // English's words and model; none if the app has not got them or cannot read them (a stale
    // VERSION, say), English then suggesting nothing, and [onError] told why
    val englishData: EnglishData? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            EnglishData.load(load(ENGLISH_DATA), verify = false) { onError(IOException("$ENGLISH_DATA: $NO_ENGLISH", it)) }
        } catch (e: IOException) {
            onError(IOException("cannot read $ENGLISH_DATA: $NO_ENGLISH", e))
            null
        } catch (e: DataFormatException) {
            onError(IOException("cannot read $ENGLISH_DATA: $NO_ENGLISH", e))
            null
        }
    }

    // built once for the pinyins' sessions, which read the same data and score alike
    val charSyllables by lazy(LazyThreadSafetyMode.NONE) { Predictor.charReadingsOf(pinyinData.dictionary, pinyinData.vocabulary) }

    private var extensions: Array<IntArray?>? = null

    /**
     * Where the pinyins' decoders keep the longer words they look ahead to (PinyinDecoder), picked
     * by what the user has learned: one array for the sessions of one user model, made at first
     * need, and made anew after [dropDecoderExtensions].
     */
    val decoderExtensions: Array<IntArray?>
        get() = extensions ?: arrayOfNulls<IntArray>(pinyinData.dictionary.nodeCount).also { extensions = it }

    /** For the sessions made next, as those before them go: the user's words may since have been cleared, imported or forgotten. */
    fun dropDecoderExtensions() {
        extensions = null
    }

    /** A sentence model, read when first asked for; dropped when turned off, read (or tried) again when turned on. */
    inner class ModelFile(private val path: String, private val unpack: Boolean) {
        private var model: SentenceModel? = null
        private var tried = false
        private var reranker: Reranker? = null
        private var rerankerLimit = 0

        fun get(): SentenceModel? {
            if (tried) return model
            tried = true
            model = try {
                SentenceModel.load(load(path), unpack, kernel ?: MatrixKernel.JVM)
            } catch (e: FileNotFoundException) {
                // a build without them (EngineDataPlugin fetches them): pinyin reads by the decoder alone
                onError(IOException("cannot open $path: pinyin reads by the decoder alone", e))
                null
            } catch (e: IOException) {
                onError(e)
                null
            } catch (e: IllegalArgumentException) {
                // pinyin reads as well without it as before it was added
                onError(IOException("cannot read $path", e))
                null
            }
            return model
        }

        /** The one reranker over the model, comparing [limit] readings, made at first need: a model is asked with one limit. */
        fun reranker(limit: Int): Reranker? {
            reranker?.let { made ->
                // made for another limit, it would compare as many readings as that one asked for
                check(limit == rerankerLimit) { "$path's reranker compares $rerankerLimit readings, not $limit" }
                return made
            }
            return get()?.let { Reranker(it, limit = limit) }?.also {
                reranker = it
                rerankerLimit = limit
            }
        }

        fun drop() {
            model = null
            tried = false
            reranker = null
        }
    }

    // in Kotlin, floats rather than int8 as stored: 17 MB rather than 4.5, and on ART two and a
    // half times faster; a kernel is faster still on int8
    val sentenceModel = ModelFile(SENTENCE_MODEL, unpack = kernel == null)
    // int8 as stored: as floats it would be 100 MB, half of what an app may take on some phones
    val refiningModel = ModelFile(REFINING_MODEL, unpack = false)

    fun pinyinOf(text: String, strayFirst: Boolean = false): String? = pinyinsOf(listOf(text), strayFirst)[text]

    /**
     * How the dictionary reads each of [texts] ([Engines.pinyinOf]), one walk for them all. With
     * [strayFirst], what those not read whole missed is walked for once more, every child of the
     * root's, as a first character may read inside a word as it never does alone (了 liao in 了解):
     * some hundred times the walk (LibimeImport.readings), so not for what a table asks as it is
     * typed in (a phrase it saves, what a long press offers), which reads 了解 le jie: a reading the
     * dictionary has not, so UserLexicon.learnSaved reads such a word again with a stray first.
     */
    fun pinyinsOf(texts: Collection<String>, strayFirst: Boolean = false): Map<String, String?> {
        val offsets = texts.associateWith { text ->
            val chars = text.codePointCount(0, text.length)
            if (chars == 0 || chars > MAX_WORD_CHARS) null
            else IntArray(chars + 1) { if (it == chars) text.length else text.offsetByCodePoints(0, it) }
        }
        val pieces = HashSet<String>()
        for ((text, at) in offsets) {
            if (at == null) continue
            for (i in 0 until at.size - 1) for (j in i + 1 until at.size) pieces += text.substring(at[i], at[j])
        }
        val dictionary = pinyinData.dictionary
        val vocabulary = pinyinData.vocabulary
        val found = LibimeImport.readings(dictionary, vocabulary, pieces)
        val missed = if (strayFirst) strayPieces(offsets, found) else emptySet()
        val readings = if (missed.isEmpty()) found else found + LibimeImport.readings(dictionary, vocabulary, missed, strayFirst = true)
        return offsets.mapValues { (text, at) -> at?.let { reading(text, it, readings) } }
    }

    /**
     * What [pinyinsOf] walks for again with a stray first: the pieces [found] lacks of the texts it
     * does not read whole, of two characters or more, as the first walk finds every character that
     * is a word. None, and there is no second walk.
     */
    internal fun strayPieces(offsets: Map<String, IntArray?>, found: Map<String, IntArray>): Set<String> {
        val pieces = HashSet<String>()
        for ((text, at) in offsets) {
            if (at == null || text in found) continue
            for (i in 0 until at.size - 2) for (j in i + 2 until at.size) pieces += text.substring(at[i], at[j])
        }
        pieces -= found.keys
        return pieces
    }

    // as few of the dictionary's words as make [text] up, apart at [offsets]
    private fun reading(text: String, offsets: IntArray, readings: Map<String, IntArray>): String? {
        val chars = offsets.size - 1
        // fewest pieces to each character, and the piece that got there
        val fewest = IntArray(chars + 1) { if (it == 0) 0 else Int.MAX_VALUE }
        val from = IntArray(chars + 1)
        for (j in 1..chars) for (i in 0 until j) {
            if (fewest[i] == Int.MAX_VALUE || text.substring(offsets[i], offsets[j]) !in readings) continue
            if (fewest[i] + 1 < fewest[j]) {
                fewest[j] = fewest[i] + 1
                from[j] = i
            }
        }
        if (fewest[chars] == Int.MAX_VALUE) return null
        val syllables = ArrayList<Int>()
        var j = chars
        while (j > 0) {
            val i = from[j]
            syllables.addAll(0, readings.getValue(text.substring(offsets[i], offsets[j])).asList())
            j = i
        }
        return syllables.joinToString(" ") { Syllables.spelling(it) }
    }

    private val textWords by lazy(LazyThreadSafetyMode.NONE) { TextWords(pinyinData.model, pinyinData.wordIndex) }

    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = texts.associateWith { textWords.boundaries(it) }

    fun logProbs(texts: List<String>): FloatArray = FloatArray(texts.size) { textWords.logProb(texts[it]) }

    // the words emoji stand for; none if the app has not got them
    private val emoji: EmojiData by lazy(LazyThreadSafetyMode.NONE) {
        try {
            EmojiData.read(Charsets.UTF_8.decode(load(EMOJI_DATA)))
        } catch (e: IOException) {
            onError(IOException("cannot read $EMOJI_DATA: no emoji candidates", e))
            EmojiData.EMPTY
        }
    }

    // asked of each emoji once it is to be offered: some 40k of them in all, each a font lookup
    private val shown = HashMap<String, Boolean>()

    /** The emoji [word] stands for (EmojiCandidates), but those the phone cannot draw. */
    fun emojiOf(word: String): List<String> = emoji.of(word).filter { shown.getOrPut(it) { emojiShows(it) } }

    // characters by their parts' pinyin; none if the app has not got them
    private val chaizi: Chaizi by lazy(LazyThreadSafetyMode.NONE) {
        try {
            Chaizi.read(Charsets.UTF_8.decode(load(CHAIZI_DATA)))
        } catch (e: IOException) {
            onError(IOException("cannot read $CHAIZI_DATA: no characters by their parts", e))
            Chaizi.EMPTY
        }
    }

    // asked of each character by its parts once found: a rare one may be past the phone's font
    private val drawn = HashMap<String, Boolean>()

    /** The characters whose parts [letters] spells (Chaizi), each with its reading, but those the phone cannot draw. */
    fun chaiziOf(letters: String): List<Pair<String, String>> =
        chaizi.of(letters).filter { drawn.getOrPut(it) { textShows(it) } }.map { it to charReading(it) }

    // null till asked for; an app without the data has no lookup
    private var strokeTable: Strokes? = null
    private var strokesTried = false

    fun strokes(): Strokes? {
        if (!strokesTried) {
            strokesTried = true
            strokeTable = try {
                val words = pinyinData.wordIndex
                val model = pinyinData.model
                Strokes(CodeTable.load(load(STROKE_DATA), verify = false)) { c ->
                    val id = words.find(c)
                    if (id < 0) Float.NEGATIVE_INFINITY else model.score(id)
                }
            } catch (e: FileNotFoundException) {
                onError(IOException("cannot open $STROKE_DATA: no lookup by strokes", e))
                null
            } catch (e: IOException) {
                onError(e)
                null
            } catch (e: DataFormatException) {
                onError(IOException(e))
                null
            }
        }
        return strokeTable
    }

    // a character to its readings, the likeliest first, for the stroke lookup to show
    private val charReadings: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        val dictionary = pinyinData.dictionary
        val vocabulary = pinyinData.vocabulary
        val readings = HashMap<String, MutableList<Pair<Float, String>>>()
        val first = dictionary.firstChild(dictionary.root)
        for (node in first until first + dictionary.childCount(dictionary.root)) {
            for (i in 0 until dictionary.wordCount(node)) {
                val word = dictionary.word(node, i)
                if (vocabulary.length(word) != 1) continue
                readings.getOrPut(vocabulary.word(word)) { ArrayList() } +=
                    dictionary.weight(node, i) to Syllables.spelling(dictionary.syllable(node))
            }
        }
        readings.mapValues { (_, r) ->
            r.sortedByDescending { it.first }.map { it.second }.distinct().take(MAX_READINGS).joinToString("/")
        }
    }

    fun charReading(c: String): String = charReadings[c].orEmpty()

    val lateRefiner: SentenceRefiner = LateRefiner()

    /**
     * The larger model is read at the first pause, not when a session is made, so the switch to
     * pinyin does not wait while the fcitx thread reads it (SentenceModel.load logs how long); its
     * int8 weights are no longer copied out of the asset. Unreadable, it has nothing to say, and
     * the session stops asking.
     */
    private inner class LateRefiner : SentenceRefiner {
        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            val r = refiningModel.reranker(Reranker.REFINE_LIMIT) ?: return SentenceRefiner.NONE
            // null is not done yet, more slices to come: not NONE, which would end it after the first
            return r.refine(context, readings, scores, budget)
        }
    }

    private companion object {
        // longer is a sentence, not a word: and the dictionary is walked as deep
        const val MAX_WORD_CHARS = 8

        const val MAX_READINGS = 3

        const val NO_ENGLISH = "English suggests nothing"
    }
}
