/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import java.lang.ref.WeakReference

/** The user's words as the settings show and change them: pinyin's ([UserLexicon]), English's ([EnglishLexicon]), and what tables cannot take. On the engines' thread. */
internal class LexiconFacade(
    private val lexicon: UserLexicon,
    private val english: EnglishLexicon,
    private val assets: EngineAssets,
    private val tables: TableRegistry,
    private val learner: LearningFacade,
    private val settings: () -> EngineSettings,
) {
    /** Added, learned (most typed first) and blocked. */
    fun userWords(): List<UserWord> = lexicon.userWords()

    /** One kind, dated as the record has it: a settings list shows one kind. */
    fun userWords(kind: UserWord.Kind): List<UserWord> =
        lexicon.userWords(kind).let { if (kind == UserWord.Kind.BLOCKED) it else learner.dated(it) }

    /** Without making any [UserWord]. */
    fun userWordCounts(): Map<UserWord.Kind, Int> = lexicon.userWordCounts()

    /** Words of [texts] that tables among [ims] cannot take, with the table words they give way to: 「五笔：与 X 同码」. */
    fun sameCodes(texts: Collection<String>, ims: Collection<String>): Map<String, List<Pair<String, String>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (im in ims) {
            val method = TABLES[im] ?: continue
            val options = tableOptions(method, settings())
            for ((text, word) in tables.sameCode(im, options, texts)) out.getOrPut(text) { ArrayList(1) } += im to word
        }
        return out
    }

    /** How the dictionary reads [text], fewest words, likeliest readings; space-separated, null if a character is in none. A first character read as a word keeps that reading (了 liao in 了解). */
    fun pinyinOf(text: String): String? = assets.pinyinOf(text, strayFirst = true)

    /** [pinyinOf] of each, with one dictionary walk for all (plus one for the misses): a thousand imports would otherwise be a thousand walks. */
    fun pinyinsOf(texts: Collection<String>): Map<String, String?> = assets.pinyinsOf(texts, strayFirst = true)

    /** Likeliest word splits (TextWords.boundaries). */
    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = assets.wordBoundaries(texts)

    /** log10 P under the pinyin model (TextWords.logProb), for ranking what voice heard. */
    fun logProbs(texts: List<String>): FloatArray = assets.logProbs(texts)

    fun voiceHomophones(words: List<UserWord>) = assets.voiceHomophones(words)

    fun voicePick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>) = assets.voicePick(homophones, text, before, blocked)

    /** Which of [texts] have a character 生僻字屏蔽 holds back now ([UserLexicon.gate]). */
    fun rareHeld(texts: Collection<String>): Set<String> {
        val gate = lexicon.gate()
        return if (gate.none) emptySet() else texts.filterTo(HashSet()) { gate.holds(it) }
    }

    // the grid's share of the pack's data, walked once (the words page asks at each showing); a new pack comes with new engines
    private var pack: Pair<IntArray, Set<Int>>? = null

    // the grid made and the user model and counts it is of, as tables key theirs; the model held weakly
    private var grid: RareGrid? = null
    private var gridModel = WeakReference<UserModel>(null)
    private var gridListings = 0
    private var gridChanges = 0

    // merged dictionaries' beyond-BMP characters and the file walked: a million words, rewalked only for a new file
    private var mergedFile = WeakReference<DictionaryFile>(null)
    private var mergedTyped: Set<Int> = emptySet()

    /** For the tests. */
    internal var mergedWalks = 0

    /** 生僻字屏蔽's grid ([RareGrid.copy]) of characters the readings know, beyond-BMP ones only where dictionary, tables, parts or the user's words have them; null without levels. Remade only when the user's words change. */
    fun rareGrid(): RareGrid? {
        val levels = assets.charLevels ?: return null
        val model = lexicon.user()
        if (gridModel.get() === model && gridListings == model.listings && gridChanges == model.changes) grid?.let { return it }
        val (single, packTyped) = pack ?: packChars().also { pack = it }
        val typed = HashSet(packTyped)
        // merged dictionaries are not listed, only as the user picks or blocks their words
        typed += mergedTyped(model.dictionaryFile)
        for (text in model.listedTexts() + model.ownTexts()) beyondBasic(text.length, text::get, typed)
        return RareGrid.copy(levels, assets.pinyinData.charReadings, single.asSequence() + assets.strokes()?.codePoints().orEmpty(), typed::contains)
            .also {
                grid = it
                gridModel = WeakReference(model)
                gridListings = model.listings
                gridChanges = model.changes
            }
    }

    private fun mergedTyped(file: DictionaryFile?): Set<Int> {
        if (file == null) return emptySet()
        if (mergedFile.get() === file) return mergedTyped
        mergedWalks++
        val typed = HashSet<Int>()
        for (place in 0 until file.size) file.text(place).let { beyondBasic(it.length, it::get, typed) }
        mergedFile = WeakReference(file)
        mergedTyped = typed
        return typed
    }

    // the dictionary's one-character words; beyond-BMP characters its words, the tables and the parts have
    private fun packChars(): Pair<IntArray, Set<Int>> {
        val vocabulary = assets.pinyinData.vocabulary
        val single = (0 until vocabulary.size).asSequence().mapNotNull { id ->
            val n = vocabulary.length(id)
            val c = vocabulary.char(id, 0)
            when {
                n == 1 -> c.code
                n == 2 && Character.isHighSurrogate(c) && Character.isLowSurrogate(vocabulary.char(id, 1)) -> Character.toCodePoint(c, vocabulary.char(id, 1))
                else -> null
            }
        }.toList().toIntArray()
        val typed = HashSet<Int>()
        for (id in 0 until vocabulary.size) beyondBasic(vocabulary.length(id), { vocabulary.char(id, it) }, typed)
        tables.beyondBasic(typed)
        for (c in assets.chaiziChars()) beyondBasic(c.length, c::get, typed)
        return single to typed
    }

    /** Likeliest first: `zhang/chang`; empty for none. */
    fun charReading(c: String): String = assets.charReading(c)

    /** Adds [text] read as [pinyin] ([WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean = lexicon.addWord(text, pinyin)

    /** False if [text] does not read as [pinyin]. */
    fun blockWord(text: String, pinyin: String): Boolean = lexicon.blockWord(text, pinyin)

    /** Writes each list once: an added word becomes untypeable, a learned one forgotten, a blocked one offered again. */
    fun removeWords(words: Collection<UserWord>) = lexicon.removeWords(words)

    /** The user's recent and never-corrected English words as written, most typed first; [UserWord.Kind.ADDED] or [UserWord.Kind.LEARNED], no pinyin. */
    fun englishWords(): List<UserWord> = english.words()

    /** Adds an English word: offered by its keys, never corrected, kept however long untyped. False for what English cannot type (letters, inner apostrophe or hyphen). */
    fun addEnglishWord(text: String): Boolean = english.add(text)

    /** Forgets counts, pairs and the never-corrected mark. */
    fun forgetEnglishWords(texts: Collection<String>) = english.forget(texts)

    /** Corrects them again (恢复), keeping counts and pairs. */
    fun restoreEnglishCorrection(texts: Collection<String>) = english.restoreCorrection(texts)

    /**
     * Imports a word list named [file], a word a line ([WordLists.line]); words without pinyin read as the dictionary
     * does. The user's own export returns as it was; another input method's list is blocked where marked, the rest a
     * batch typed only as input-initial words after the dictionary's until typed twice; counts order the batch.
     */
    fun importWords(lines: Sequence<String>, file: String): Engines.Imported = lexicon.wordImport.importWords(lines, file)

    /** The user's words as [importWords] reads them back: added `幽默 you'mo`, learned with `~` and a count (`~ 幽默 you'mo 3.0`, only under the export's header), blocked with `!`. */
    fun exportWords(): List<String> = lexicon.exportWords()
}

/** Adds to [into] the beyond-BMP code points of a text read a UTF-16 unit at a time by [char]. */
internal inline fun beyondBasic(length: Int, char: (Int) -> Char, into: MutableSet<Int>) {
    var i = 0
    while (i < length - 1) {
        val c = char(i)
        if (Character.isHighSurrogate(c)) {
            val low = char(i + 1)
            if (Character.isLowSurrogate(low)) into += Character.toCodePoint(c, low)
            i += 2
        } else {
            i++
        }
    }
}
