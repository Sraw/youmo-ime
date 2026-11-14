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

/**
 * The user's words as the settings show and change them, for [Engines]: pinyin's ([UserLexicon]:
 * added, learned, blocked, imported, the dictionary's readings of a text) and English's
 * ([EnglishLexicon]), and what the tables cannot take of them. On the engines' thread.
 */
internal class LexiconFacade(
    private val lexicon: UserLexicon,
    private val english: EnglishLexicon,
    private val assets: EngineAssets,
    private val tables: TableRegistry,
    private val learner: LearningFacade,
    private val settings: () -> EngineSettings,
) {
    /** The words the user added, those they made as they typed (most typed first), and those they blocked. */
    fun userWords(): List<UserWord> = lexicon.userWords()

    /** [kind]'s part of [userWords] alone, each dated as the learning record has it (U-80): a list of the settings shows one kind. */
    fun userWords(kind: UserWord.Kind): List<UserWord> =
        lexicon.userWords(kind).let { if (kind == UserWord.Kind.BLOCKED) it else learner.dated(it) }

    /** How many words of each kind [userWords] lists, with none of them made a [UserWord]. */
    fun userWordCounts(): Map<UserWord.Kind, Int> = lexicon.userWordCounts()

    /**
     * The words of [texts] that tables among [ims] cannot take, each with the tables' words they
     * give way to, as (table input method, its word): 「五笔：与 X 同码」 in the user dictionary (D8).
     */
    fun sameCodes(texts: Collection<String>, ims: Collection<String>): Map<String, List<Pair<String, String>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (im in ims) {
            val method = TABLES[im] ?: continue
            val options = tableOptions(method, settings())
            for ((text, word) in tables.sameCode(im, options, texts)) out.getOrPut(text) { ArrayList(1) } += im to word
        }
        return out
    }

    /**
     * How the dictionary reads [text]: as few of its words as make it up, each read as it most
     * likely is; syllables apart by spaces, null if some character is none of its words. For
     * the user to check, as a character of several readings may be read the other way; a first
     * character read as a word has it, though it never is so alone (了 liao in 了解).
     */
    fun pinyinOf(text: String): String? = assets.pinyinOf(text, strayFirst = true)

    /**
     * [pinyinOf] of each of [texts], the dictionary walked once for them all, and once more for
     * what those not read whole missed: a walk is the whole of it, a thousand words imported with
     * no pinyin would be a thousand walks.
     */
    fun pinyinsOf(texts: Collection<String>): Map<String, String?> = assets.pinyinsOf(texts, strayFirst = true)

    /** Where each of [texts] splits into words, as the model finds likeliest: TextWords.boundaries. */
    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = assets.wordBoundaries(texts)

    /** log10 P of each of [texts] under the pinyin model (TextWords.logProb): for ranking what voice input heard. */
    fun logProbs(texts: List<String>): FloatArray = assets.logProbs(texts)

    /** [EngineAssets.voiceHomophones]. */
    fun voiceHomophones(words: List<UserWord>) = assets.voiceHomophones(words)

    /** [EngineAssets.voiceHomophonesAllowed]. */
    fun voiceHomophonesAllowed(spans: List<VoiceHomophones.Span>) = assets.voiceHomophonesAllowed(spans)

    /** Which of [texts] have a character 生僻字屏蔽 holds back now ([UserLexicon.gate]): for voice, the user's words and the recognizer's characters. */
    fun rareHeld(texts: Collection<String>): Set<String> {
        val gate = lexicon.gate()
        return if (gate.none) emptySet() else texts.filterTo(HashSet()) { gate.holds(it) }
    }

    // what the grid takes of the pack's data, which a new pack comes with new engines for: walked once
    // (the page of the user's words asks at each showing, review S7)
    private var pack: Pair<IntArray, Set<Int>>? = null

    // the grid made, and of which of the user's words: their model and its counts, as the tables key
    // theirs; the model held weakly, one replaced not kept for this
    private var grid: RareGrid? = null
    private var gridModel = WeakReference<UserModel>(null)
    private var gridListings = 0
    private var gridChanges = 0

    // the merged dictionaries' characters beyond the basic plane, of the file they were walked in: a
    // million words of a big one, walked again only with a new file (a new model), not as the user learns
    private var mergedFile = WeakReference<DictionaryFile>(null)
    private var mergedTyped: Set<Int> = emptySet()

    /** How many times a merged dictionary was walked for the grid: for the tests. */
    internal var mergedWalks = 0

    /**
     * 生僻字屏蔽's grid ([RareGrid.copy]): the characters the readings know, of 扩展 B on those the
     * dictionary's words, the tables or the parts have, or the user's words (their dictionaries, those
     * added or imported, those learned: each has a reading), and those the dictionary's one-character
     * words and the strokes have with none; null without the levels (EngineAssets.charLevels). Made
     * again only once the user's words changed.
     */
    fun rareGrid(): RareGrid? {
        val levels = assets.charLevels ?: return null
        val model = lexicon.user()
        if (gridModel.get() === model && gridListings == model.listings && gridChanges == model.changes) grid?.let { return it }
        val (single, packTyped) = pack ?: packChars().also { pack = it }
        val typed = HashSet(packTyped)
        // the dictionaries merged are not listed but as the user picks or blocks their words
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

    // the dictionary's one-character words; the characters beyond the basic plane its words, the tables and the parts have
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

    /** A character's readings as the dictionary has them, the likeliest first: `zhang/chang`; empty for none. */
    fun charReading(c: String): String = assets.charReading(c)

    /** Adds [text] read as [pinyin] (see [WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean = lexicon.addWord(text, pinyin)

    /** Blocks [text] read as [pinyin] from the settings; false if it does not read so. */
    fun blockWord(text: String, pinyin: String): Boolean = lexicon.blockWord(text, pinyin)

    /** Takes each of [words] off its list, a list written once: an added word is no longer typeable, a learned one forgotten, a blocked one offered again. */
    fun removeWords(words: Collection<UserWord>) = lexicon.removeWords(words)

    /**
     * The English words the user committed lately, as written, and those never corrected, the
     * most typed first: [UserWord.Kind.ADDED] those added, else [UserWord.Kind.LEARNED]; no pinyin.
     */
    fun englishWords(): List<UserWord> = english.words()

    /**
     * Makes [text] an English word of the user's, added: offered by its keys, never corrected, and
     * kept however long untyped. False for what is no word English types (letters, an
     * apostrophe or a hyphen within).
     */
    fun addEnglishWord(text: String): Boolean = english.add(text)

    /** Forgets each of the English words [texts]: their counts, the pairs they are in, and that they are never corrected. */
    fun forgetEnglishWords(texts: Collection<String>) = english.forget(texts)

    /** Has each of the English words [texts] corrected again (U-31 恢复), their counts and pairs kept. */
    fun restoreEnglishCorrection(texts: Collection<String>) = english.restoreCorrection(texts)

    /**
     * The words of a list the user imports, named [file], a word a line (see [WordLists.line]); one
     * with no pinyin read as the dictionary reads it ([pinyinOf]). The user's own export
     * ([exportWords]) comes back as it was: added, blocked, learned with its count. Another input
     * method's list is blocked where marked so, and its other words imported as a batch (U-101):
     * typed only as words the input starts with, after the dictionary's of the same keys, never in
     * a sentence, till typed twice; a count after a word orders the batch (D24).
     */
    fun importWords(lines: Sequence<String>, file: String): Engines.Imported = lexicon.wordImport.importWords(lines, file)

    /**
     * The user's words as [importWords] reads them back: the added as `幽默 you'mo`, the learned
     * (most typed first) with a `~` apart before them and their count after (`~ 幽默 you'mo 3.0`, learned
     * again only from a file headed by the export's mark), the blocked with a `!` before them.
     */
    // the user's export is a backup (FXLB) since 4fa9c54f: this text form is kept for the word lists' round-trip tests
    fun exportWords(): List<String> = lexicon.exportWords()
}

/** Into [into], the code points past the basic plane of a text [length] UTF-16 units long, read a unit at a time by [char]. */
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
