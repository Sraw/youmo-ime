/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.WordLists

/**
 * 常用词: words the user wants replaced by others (`米字旁 → 米、糸`); typing a word puts its replacements
 * first, the word itself after them as ranked.
 *
 * Each word is keyed ([Kind]) by its whole pinyin, its first three characters' pinyin (4+ characters), and
 * its initials (3+ syllables, or 2 on full pinyin's 26 keys only: elsewhere two initials spell a
 * syllable). The file and export are one text: a header, then a word per line with its replacements,
 * tab-separated; an optional reading in brackets (`重庆[chong'qing]`), else the dictionary's at import,
 * kept. Unread lines are written back as they were; a later-version file ([newer]) is not overwritten.
 */
class CommonWords private constructor(
    val words: List<Word>,
    /** Lines not read, written back after the words. */
    val unread: List<String> = emptyList(),
    /** From a later-version file: no words, never written over. */
    val newer: Boolean = false,
) {

    class Word(val text: String, val syllables: IntArray, val replacements: List<String>) {
        /** As the file writes it: `mi'zi'pang`. */
        val pinyin: String get() = Syllables.apostrophed(syllables)

        override fun equals(other: Any?) =
            other is Word && text == other.text && syllables.contentEquals(other.syllables) && replacements == other.replacements

        override fun hashCode() = (text.hashCode() * 31 + syllables.contentHashCode()) * 31 + replacements.hashCode()

        override fun toString() = "$text[$pinyin] ${replacements.joinToString(" ")}"
    }

    /** Also the order replacements go when several words share a key. */
    enum class Kind { WHOLE, FIRST_THREE, INITIALS }

    enum class Problem { NO_REPLACEMENT, NO_PINYIN, BAD_PINYIN, TOO_LONG, TOO_MANY, BLANK_IN_REPLACEMENT, NEWER }

    /** [line] is 1-based. */
    class Skipped(val line: Int, val text: String, val problem: Problem)

    class Read(val words: CommonWords, val skipped: List<Skipped>)

    val isEmpty: Boolean get() = words.isEmpty()

    fun format(): String = buildString {
        check(!newer) { "a later version's common words are not written over" }
        append(HEADER).append('\n')
        for (w in words) {
            append(w.text).append('[').append(w.pinyin).append(']')
            for (r in w.replacements) append('\t').append(r)
            append('\n')
        }
        for (line in unread) append(line).append('\n')
    }

    /** A word left with no replacements goes. */
    fun without(text: String, replacement: String): CommonWords = without(listOf(text), replacement)

    fun without(texts: Collection<String>, replacement: String): CommonWords = CommonWords(
        words.mapNotNull { w ->
            if (w.text !in texts) w else w.replacements.filter { it != replacement }.takeIf { it.isNotEmpty() }?.let { Word(w.text, w.syllables, it) }
        },
        unread,
        newer,
    )

    /** [file]'s words over these: a known word is replaced, others appended while under [MAX_WORDS]; the rest are [Imported.full]. */
    fun importing(file: CommonWords): Imported {
        val out = words.toMutableList()
        val at = words.withIndex().associate { (i, w) -> w.text to i }
        var added = 0
        var updated = 0
        val full = ArrayList<Word>()
        for (w in file.words) {
            val i = at[w.text]
            when {
                i != null -> {
                    if (out[i] != w) updated++
                    out[i] = w
                }
                out.size < MAX_WORDS -> {
                    out += w
                    added++
                }
                else -> full += w
            }
        }
        return Imported(CommonWords(out, unread, newer), added, updated, full)
    }

    class Imported(val words: CommonWords, val added: Int, val updated: Int, val full: List<Word>)

    /** The words by key: [spell] types a syllable (null: cannot, dropping that key); [also] another spelling; [initialsOfTwo] whether two-syllable words get an initials key. */
    fun index(spell: (Int) -> String?, initialsOfTwo: Boolean = true, also: (String) -> String? = { null }): Index {
        val byKey = HashMap<String, MutableList<Hit>>()
        for ((i, w) in words.withIndex()) {
            for (k in keyParts(w, spell, initialsOfTwo, also)) {
                val ends = k.parts.runningFold(0) { at, part -> at + part.length }
                byKey.getOrPut(k.parts.joinToString("")) { ArrayList() } += Hit(i, k.kind, ends.toIntArray())
            }
        }
        return Index(this, byKey)
    }

    // [ends]: where its syllables end in the key (0 and its length too)
    internal class Hit(val word: Int, val kind: Kind, val ends: IntArray)

    /** A replacement offered for [word], first of [words] (all words offering it under these keys: deleting removes it from each). */
    class Found(val text: String, val word: String, val words: List<String> = listOf(word))

    class Index internal constructor(val of: CommonWords, private val byKey: Map<String, List<Hit>>) {

        val isEmpty: Boolean get() = byKey.isEmpty()

        operator fun contains(key: String) = key in byKey

        // asked on every grouped-keys key: keys by length, once
        private val keysByLength by lazy { byKey.keys.groupBy { it.length } }

        fun keysRead(typed: PhraseKeys): List<String> = typed.lengths.flatMap { keysByLength[it].orEmpty() }.filter(typed::reads)

        /** Replacements under [keys], each text once: by [Kind], then word order, then each word's own order. */
        fun found(keys: List<String>): List<Found> = ofHits(keys.flatMap { byKey[it].orEmpty() })

        /** As [found], typed in letters with any `'` at the word's syllable ends (`xi'an` 西安, not 先). */
        fun foundTyped(typed: String): List<Found> {
            val key = typed.filter { it != SEPARATOR }
            val hits = byKey[key] ?: return emptyList()
            var at = 0
            val apart = ArrayList<Int>()
            for (c in typed) if (c == SEPARATOR) apart += at else at++
            return ofHits(hits.filter { h -> apart.all { it == 0 || it == key.length || it in h.ends } })
        }

        private fun ofHits(hits: List<Hit>): List<Found> {
            val byText = LinkedHashMap<String, MutableList<String>>()
            for (h in hits.distinctBy { it.word }.sortedWith(compareBy({ it.kind }, { it.word }))) {
                val w = of.words[h.word]
                for (r in w.replacements) byText.getOrPut(r) { ArrayList(1) }.add(w.text)
            }
            return byText.map { (text, words) -> Found(text, words.first(), words) }
        }
    }

    companion object {
        /** A later version reads as none ([newer]). */
        const val VERSION = 1
        const val HEADER = "# youmo-common-words $VERSION"
        const val MAX_WORDS = 2000
        const val MAX_REPLACEMENTS = 9
        const val MAX_REPLACEMENT_CHARS = 32

        /** With a given reading; one read by the dictionary may be as long as it reads (EngineAssets.MAX_WORD_CHARS). */
        const val MAX_WORD_CHARS = 16

        /** A longer word is also keyed by this many first characters. */
        const val FIRST = 3

        val EMPTY = CommonWords(emptyList())

        private const val SEPARATOR = '\''

        /** Three-way merge of [mine] (since [base]) over [theirs] (the file now; settings page and keyboard both write it); words [theirs] added go after. */
        fun merged(base: CommonWords, mine: CommonWords, theirs: CommonWords): CommonWords {
            // the file's unread lines stay as it has them
            if (theirs.words == base.words) return CommonWords(mine.words, theirs.unread, theirs.newer)
            val before = base.words.associateBy { it.text }
            val now = theirs.words.associateBy { it.text }
            val out = LinkedHashMap<String, Word>()
            for (w in mine.words) {
                if (before[w.text] != w) out[w.text] = w else now[w.text]?.let { out[w.text] = it }
            }
            for (w in theirs.words) if (w.text !in out && w.text !in before) out[w.text] = w
            return CommonWords(out.values.toList(), theirs.unread, theirs.newer)
        }

        /** Already checked by [problem] (the settings' list). */
        fun of(words: List<Word>) = CommonWords(words)

        /** [word]'s keys in [Kind] order (none if a syllable cannot be typed); with [also], one key per spelling, [MAX_WAYS] at most per kind. */
        fun keys(word: Word, spell: (Int) -> String?, initialsOfTwo: Boolean = true, also: (String) -> String? = { null }): List<Pair<Kind, String>> =
            keyParts(word, spell, initialsOfTwo, also).map { it.kind to it.parts.joinToString("") }

        private class KeyParts(val kind: Kind, val parts: List<String>)

        private fun keyParts(word: Word, spell: (Int) -> String?, initialsOfTwo: Boolean, also: (String) -> String?): List<KeyParts> {
            val spelt = word.syllables.map { spell(it)?.takeIf(String::isNotEmpty) ?: return emptyList() }
            val ways = spelt.map { listOfNotNull(it, also(it)) }
            val n = spelt.size
            fun every(of: List<List<String>>): List<List<String>> =
                of.fold(listOf(emptyList<String>())) { acc, w -> acc.flatMap { a -> w.map { a + it } }.take(MAX_WAYS) }
            return every(ways).map { KeyParts(Kind.WHOLE, it) } +
                (if (n > FIRST) every(ways.take(FIRST)).map { KeyParts(Kind.FIRST_THREE, it) } else emptyList()) +
                (if (n > 2 || n == 2 && initialsOfTwo) listOf(KeyParts(Kind.INITIALS, spelt.map { it.take(1) })) else emptyList())
        }

        private const val MAX_WAYS = 4

        /** Full pinyin's `ue` for `ve` (lüe, nüe). */
        fun fullPinyinAlso(spelling: String): String? =
            if (spelling.endsWith("ve") && spelling.length == 3) spelling.dropLast(2) + "ue" else null

        /** What keeps the entry from being a word, null if nothing; [replacements] before [clean]: all blank means none. */
        fun problem(text: String, syllables: IntArray?, replacements: List<String>): Problem? {
            val kept = clean(replacements)
            return when {
                text.codePointCount(0, text.length) > MAX_WORD_CHARS -> Problem.TOO_LONG
                replacements.any { it.codePointCount(0, it.length) > MAX_REPLACEMENT_CHARS } -> Problem.TOO_LONG
                // a word, not a sentence: refuse blanks rather than silently join
                replacements.any { r -> r.trim().any(Char::isWhitespace) } -> Problem.BLANK_IN_REPLACEMENT
                kept.isEmpty() -> Problem.NO_REPLACEMENT
                syllables == null -> Problem.NO_PINYIN
                else -> null
            }
        }

        /** Trimmed, none blank, each once, [MAX_REPLACEMENTS] at most (the word itself may be one: it then goes at its place). */
        fun clean(replacements: List<String>): List<String> =
            replacements.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_REPLACEMENTS)

        /** [pinyin] (`chong'qing`, `chong qing`, `chongqing`) as syllables, as WordLists reads it; null if it does not read. */
        fun syllables(text: String, pinyin: String): IntArray? = WordLists.entry(text, pinyin)?.syllables

        /**
         * Parses the file or a user's edit of it: a word per line with blank-separated replacements; `#` comments.
         * A word without a bracketed reading takes [guess]'s, else is skipped. Duplicate words merge. Skipped lines
         * and comments are kept ([unread]) and written after the words. A later [VERSION] header reads as none ([newer]).
         */
        fun parse(text: String, guess: (List<String>) -> Map<String, String?> = { emptyMap() }): Read {
            later(text)?.let { (number, header) -> return Read(CommonWords(emptyList(), newer = true), listOf(Skipped(number, header, Problem.NEWER))) }
            val lines = lines(text)
            val skipped = ArrayList<Skipped>()
            val guessed = lines.filter { it.pinyin == null }.map { it.word }.distinct().let { if (it.isEmpty()) emptyMap() else guess(it) }
            val words = LinkedHashMap<String, Word>()
            for (l in lines) {
                val pinyin = l.pinyin ?: guessed[l.word]
                val syllables = pinyin?.let { syllables(l.word, it) }
                val problem = problem(l.word, syllables, l.replacements)
                    ?: if (l.word !in words && words.size >= MAX_WORDS) Problem.TOO_MANY else null
                if (problem != null || syllables == null) {
                    // a given reading that fails is wrong; a dictionary miss is none
                    val why = if (problem == Problem.NO_PINYIN && l.pinyin != null) Problem.BAD_PINYIN else problem
                    skipped += Skipped(l.number, l.raw, why ?: Problem.NO_PINYIN)
                    continue
                }
                val before = words[l.word]
                val replacements = clean(before?.replacements.orEmpty() + l.replacements)
                words[l.word] = Word(l.word, before?.syllables ?: syllables, replacements)
            }
            // comments kept too, in order among skipped lines (all after the words); the header is rewritten
            val unread = (skipped.map { it.line to it.text } + comments(text)).sortedBy { it.first }.map { it.second }
            return Read(CommonWords(words.values.toList(), unread), skipped)
        }

        private fun later(text: String): Pair<Int, String>? {
            val (i, first) = text.removePrefix("\ufeff").lineSequence().withIndex().firstOrNull { it.value.isNotBlank() } ?: return null
            val version = HEADER_LINE.matchEntire(first.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return if (version > VERSION) i + 1 to first.trim() else null
        }

        private val HEADER_LINE = Regex("# youmo-common-words (\\d+)")

        private class Line(val number: Int, val raw: String, val word: String, val pinyin: String?, val replacements: List<String>)

        private fun lines(text: String): List<Line> = text.removePrefix("\ufeff").lineSequence().withIndex().mapNotNull { (i, raw) ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            // the reading may have blanks (`[chong qing]`)
            val read = READ.matchEntire(line)
            val fields = (read?.groupValues?.get(3) ?: line).trim().split(BLANKS).filter { it.isNotEmpty() }
            if (read != null) Line(i + 1, line, read.groupValues[1], read.groupValues[2], fields) else Line(i + 1, line, fields[0], null, fields.drop(1))
        }.toList()

        private fun comments(text: String): List<Pair<Int, String>> = text.removePrefix("\ufeff").lineSequence().withIndex()
            .mapNotNull { (i, raw) -> raw.trim().takeIf { it.startsWith("#") && !HEADER_LINE.matches(it) }?.let { i + 1 to it } }.toList()

        private val BLANKS = Regex("\\s+")

        // a word, its bracketed reading (a blank between allowed), the rest
        private val READ = Regex("(\\S+?)\\s*\\[([^\\]]*)\\](.*)")
    }
}
