/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.CHAIZI_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.CHAR_INFO_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.CHAR_LEVELS_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.EMOJI_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.REFINING_MODEL
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SENTENCE_MODEL
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.STROKE_DATA
import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.Blobs
import org.fcitx.fcitx5.android.engine.reading.ToneMark
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.imports.WordText
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import java.io.IOException

/**
 * What [Engines] reads from the app's assets, each on first ask and kept: pinyin data and what its sessions
 * share, the sentence models, strokes, English, emoji and more. Unreadable files go to [onError]. Runs on the
 * fcitx thread, except [pinyinData], which Engines' warm-up reads ahead.
 */
internal class EngineAssets(
    private val blobs: Blobs,
    private val onError: (IOException) -> Unit,
    private val kernel: MatrixKernel?,
    private val emojiShows: (String) -> Boolean = { true },
    private val textShows: (String) -> Boolean = { true },
) {

    // signed with the app, so no checksum pass; synchronized lazy because the warm-up thread reads it too
    val pinyinData by lazy { PinyinData.load(blobs.load(PINYIN_DATA), verify = false) }

    // Latin words pinyin reads as typed in lower case (iphone for iPhone); warmed up off the fcitx thread too
    val latinWords by lazy { LatinWords.of(pinyinData.dictionary) }

    // none if missing or unreadable (a stale VERSION): English then suggests nothing and [onError] is told
    val englishData: EnglishData? by lazy(LazyThreadSafetyMode.NONE) {
        readOr(null, "cannot read $ENGLISH_DATA: $NO_ENGLISH") {
            EnglishData.load(blobs.load(ENGLISH_DATA), verify = false) { onError(IOException("$ENGLISH_DATA: $NO_ENGLISH", it)) }
        }
    }

    // shared by the pinyin sessions; 联想 reads it at a table's first commit (half a second on a phone), so
    // it is built on the warm-up thread under a lock
    val charSyllables by lazy { Predictor.charReadingsOf(pinyinData.dictionary, pinyinData.vocabulary) }

    private var extensions: Array<IntArray?>? = null

    // the user model and counts version [extensions] was made for; [extensionsMixed] once two models asked
    private var extensionsUser: UserModel? = null
    private var extensionsMixed = false
    private var extensionsCounts = 0

    /** Where the pinyin decoders over [user] keep the longer words they look ahead to: one array per user model, made at need, remade after [dropDecoderExtensions]. */
    fun decoderExtensions(user: UserModel?): Array<IntArray?> {
        extensions?.let { held ->
            if (user !== extensionsUser) extensionsMixed = true
            return held
        }
        return arrayOfNulls<IntArray>(pinyinData.dictionary.nodeCount).also {
            extensions = it
            extensionsUser = user
            extensionsMixed = false
            extensionsCounts = user?.countsVersion ?: 0
            extensionsMade++
        }
    }

    /** For the tests. */
    internal fun extensionsPicked(): List<Int> = extensions?.flatMap { it?.filter { w -> w >= 0 } ?: emptyList() } ?: emptyList()

    /** For the tests: a setting change must not make a new one. */
    internal var extensionsMade = 0
        private set

    /** For the next sessions: the user's words may since have been cleared, imported or forgotten. */
    fun dropDecoderExtensions() {
        extensions = null
        // nor keep the old model reachable till the next is made
        extensionsUser = null
        extensionsMixed = false
    }

    /** [dropDecoderExtensions] unless the same user model's counts are unchanged since the array was made, so a setting change does not redo the look-ups. */
    fun dropDecoderExtensionsIfLearned() {
        if (extensions == null) return
        if (extensionsMixed) return dropDecoderExtensions()
        val user = extensionsUser ?: return
        if (user.countsVersion != extensionsCounts) dropDecoderExtensions()
    }

    /** A sentence model read on first ask; dropped when turned off, read again when turned on. */
    inner class ModelFile(private val path: String, private val unpack: Boolean) {
        private var model: SentenceModel? = null
        private var tried = false
        private var reranker: Reranker? = null
        private var rerankerLimit = 0

        fun get(): SentenceModel? {
            if (tried) return model
            tried = true
            // missing or unreadable: pinyin reads by the decoder alone
            model = readOr(null, "cannot read $path: pinyin reads by the decoder alone") {
                SentenceModel.load(blobs.load(path), unpack, kernel ?: MatrixKernel.JVM)
            }
            return model
        }

        /** The one reranker over the model, made at first need; a model is asked with one limit. */
        fun reranker(limit: Int): Reranker? {
            reranker?.let { made ->
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

    // Kotlin multiplies floats faster than int8 (17 MB not 4.5); a kernel is faster still on int8
    val sentenceModel = ModelFile(SENTENCE_MODEL, unpack = kernel == null)
    // int8 as stored: as floats it would be 100 MB, half of what an app may take on some phones
    val refiningModel = ModelFile(REFINING_MODEL, unpack = false)

    fun pinyinOf(text: String, strayFirst: Boolean = false): String? = pinyinsOf(listOf(text), strayFirst)[text]

    /**
     * How the dictionary reads each of [texts], one walk for all. [strayFirst] walks the unread pieces again
     * from every root child, as a first character may read differently inside a word (了 liao in 了解); about
     * a hundred times the cost, so not for tables' as-typed asks.
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
        val found = WordText.readings(dictionary, vocabulary, pieces)
        val missed = if (strayFirst) strayPieces(offsets, found) else emptySet()
        val readings = if (missed.isEmpty()) found else found + WordText.readings(dictionary, vocabulary, missed, strayFirst = true)
        return offsets.mapValues { (text, at) -> at?.let { reading(text, it, readings) } }
    }

    /** The pieces of two+ characters [found] lacks from texts not read whole, for the stray-first walk; empty means no second walk. */
    internal fun strayPieces(offsets: Map<String, IntArray?>, found: Map<String, IntArray>): Set<String> {
        val pieces = HashSet<String>()
        for ((text, at) in offsets) {
            if (at == null || text in found) continue
            for (i in 0 until at.size - 2) for (j in i + 2 until at.size) pieces += text.substring(at[i], at[j])
        }
        pieces -= found.keys
        return pieces
    }

    // as few dictionary words as make [text] up, split at [offsets]
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
        return Syllables.spelled(syllables.size) { syllables[it] }
    }

    private val textWords by lazy(LazyThreadSafetyMode.NONE) { TextWords(pinyinData.model, pinyinData.wordIndex) }

    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = texts.associateWith { textWords.boundaries(it, splitLong = true) }

    fun logProbs(texts: List<String>): FloatArray = FloatArray(texts.size) { textWords.logProb(texts[it]) }

    /** The user's [words] for voice homophones: each read as typed with the dictionary's tones (any tone its character has where unknown); each character's readings all heard. */
    fun voiceHomophones(words: List<UserWord>): VoiceHomophones {
        val data = pinyinData
        return VoiceHomophones.of(
            words,
            readingsOf = ::voiceWordReadings,
            // looked up in the mapped table (stateless reads, any thread), not copied to the heap
            charReadings = ::voiceReadings,
        )
    }

    /** Per character, as voice reads user words; null if its pinyin does not spell it. */
    fun voiceWordReadings(w: UserWord): List<IntArray>? {
        val data = pinyinData
        val syllables = w.pinyin.split(' ', '\'').filter { it.isNotEmpty() }.map(Syllables::id).toIntArray()
        if (syllables.size != w.text.length || syllables.any { it < 0 }) return null
        val tones = data.tones.tones(w.text, syllables, textWords.id(w.text).takeIf { it != NgramModel.NO_WORD } ?: -1)
        return syllables.indices.map { k ->
            val tone = tones?.get(k)?.toInt() ?: ToneMark.NONE
            if (tone != ToneMark.NONE) intArrayOf(VoiceHomophones.reading(syllables[k], tone))
            else data.charReadings.of(w.text[k].code).filter { it.syllable == syllables[k] }.map { VoiceHomophones.reading(it.syllable, it.tone) }.toIntArray()
        }
    }

    /** As [VoiceHomophones.reading] packs them; null for none. */
    fun voiceReadings(c: Char): IntArray? {
        val of = pinyinData.charReadings.of(c.code)
        return if (of.isEmpty()) null else IntArray(of.size) { VoiceHomophones.reading(of[it].syllable, of[it].tone) }
    }

    /** By the dictionary's words and the model's counts. */
    fun voiceHomophonesAllowed(spans: List<VoiceHomophones.Span>): BooleanArray {
        val data = pinyinData
        // readings of the words in them (VoiceHomophones.allowed reads each), one dictionary walk for all
        val wordTexts = HashSet<String>()
        for (span in spans) {
            val text = span.text
            for (from in 0 until text.length - 1) {
                for (to in from + 2..text.length) text.substring(from, to).takeIf { textWords.id(it) != NgramModel.NO_WORD }?.let(wordTexts::add)
            }
        }
        val readings by lazy(LazyThreadSafetyMode.NONE) { WordText.readings(data.dictionary, data.vocabulary, wordTexts) }
        val dictionary = object : VoiceHomophones.Dictionary {
            override fun isWord(text: String) = textWords.id(text) != NgramModel.NO_WORD

            override fun isCommon(text: String): Boolean {
                val id = textWords.id(text)
                return id != NgramModel.NO_WORD && data.model.score(id) >= COMMON_WORD
            }

            override fun wordReading(text: String): IntArray? {
                val id = textWords.id(text).takeIf { it != NgramModel.NO_WORD } ?: return null
                val syllables = readings[text] ?: return null
                val tones = data.tones.tones(text, syllables, id) ?: return null
                return IntArray(syllables.size) { VoiceHomophones.reading(syllables[it], tones[it].toInt()) }
            }
        }
        return BooleanArray(spans.size) { VoiceHomophones.allowed(spans[it], dictionary) }
    }

    // none if the app has not got them
    private val emoji: EmojiData by lazy {
        readOr(EmojiData.EMPTY, "cannot read $EMOJI_DATA: no emoji candidates") {
            EmojiData.read(Charsets.UTF_8.decode(blobs.load(EMOJI_DATA)))
        }
    }

    // per emoji, on first offer: a font lookup each
    private val shown = HashMap<String, Boolean>()

    /** Leaving out those the phone cannot draw. */
    fun emojiOf(word: String): List<String> = emoji.of(word).filter { shown.getOrPut(it) { emojiShows(it) } }

    // none if the app has not got them
    private val chaizi: Chaizi by lazy {
        readOr(Chaizi.EMPTY, "cannot read $CHAIZI_DATA: no characters by their parts") {
            Chaizi.read(Charsets.UTF_8.decode(blobs.load(CHAIZI_DATA)))
        }
    }

    /** None known if the app has not got them. */
    val charInfo: CharInfo by lazy {
        readOr(CharInfo.EMPTY, "cannot read $CHAR_INFO_DATA: no radicals or strokes on a character's card") {
            CharInfo.load(blobs.load(CHAR_INFO_DATA), verify = false)
        }
    }

    /** Characters' 生僻字屏蔽 groups; null (told once) if unreadable, turning blocking off rather than guessing by ranges, which would hold back common table characters. */
    val charLevels: CharLevels? by lazy(LazyThreadSafetyMode.NONE) {
        readOr(null, "cannot read $CHAR_LEVELS_DATA: 生僻字屏蔽 off") { CharLevels.load(blobs.load(CHAR_LEVELS_DATA), verify = false) }
    }

    // [read], or [fallback] with [onError] told [message] when the file is missing, unreadable or malformed:
    // the one rule for every optional file here
    private inline fun <T> readOr(fallback: T, message: String, read: () -> T): T = try {
        read()
    } catch (e: IOException) {
        onError(IOException(message, e))
        fallback
    } catch (e: DataFormatException) {
        onError(IOException(message, e))
        fallback
    } catch (e: IllegalArgumentException) {
        onError(IOException(message, e))
        fallback
    }

    // per character found by parts: a rare one may be past the phone's font
    private val drawn = HashMap<String, Boolean>()

    /** Leaving out those the phone cannot draw. */
    fun chaiziOf(letters: String): List<Pair<String, String>> =
        chaizi.of(letters).filter { drawn.getOrPut(it) { textShows(it) } }.map { it to charReading(it) }

    /** Every character findable by parts, shown or not: for 生僻字屏蔽's grid. */
    fun chaiziChars(): Sequence<String> = chaizi.all()

    private var strokeTable: Strokes? = null
    private var strokesTried = false

    fun strokes(): Strokes? {
        if (!strokesTried) {
            strokesTried = true
            strokeTable = readOr(null, "cannot read $STROKE_DATA: no lookup by strokes") {
                val words = pinyinData.wordIndex
                val model = pinyinData.model
                Strokes(CodeTable.load(blobs.load(STROKE_DATA), verify = false)) { c ->
                    val id = words.find(c)
                    if (id < 0) Float.NEGATIVE_INFINITY else model.score(id)
                }
            }
        }
        return strokeTable
    }

    // a character's readings, likeliest first, for the stroke lookup and part hints: a polyphone drawn as one
    // (CandidateReadings.ofHint), and a part blocked by them
    private val charReadings: Map<String, String> by lazy {
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

    /** Reads the aside sources (emoji, parts, readings) on the warm-up thread rather than at the first key; only those in use. Their lazies are synchronized for it. */
    fun warmUpAside(emoji: Boolean, chaizi: Boolean, charReadings: Boolean) {
        if (emoji) this.emoji
        if (chaizi) this.chaizi
        if (charReadings) this.charReadings
    }

    val lateRefiner: SentenceRefiner = LateRefiner()

    /** Reads the larger model at the first pause, not at session creation, so switching to pinyin does not wait; unreadable, it says nothing and the session stops asking. */
    private inner class LateRefiner : SentenceRefiner {
        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            val r = refiningModel.reranker(Reranker.REFINE_LIMIT) ?: return SentenceRefiner.NONE
            // null means more slices to come; NONE would end it after the first
            return r.refine(context, readings, scores, budget)
        }
    }

    private companion object {
        // log10 unigram at or above which a dictionary word is too common to cut: a stretch across its edge is not replaced
        const val COMMON_WORD = -5.5f

        // longer is a sentence: and the dictionary is walked as deep
        const val MAX_WORD_CHARS = 8

        const val MAX_READINGS = 3

        const val NO_ENGLISH = "English suggests nothing"
    }
}
