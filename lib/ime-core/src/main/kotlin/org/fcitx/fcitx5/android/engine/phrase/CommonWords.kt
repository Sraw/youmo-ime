/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.WordLists

/**
 * 常用词 (dev/design/060-common-words.md): words the user wants replaced by others, `米字旁 → 米、糸`.
 * Typed by its pinyin, a word's replacements go first among the candidates, in their order, the
 * word itself after them as ranked.
 *
 * Each word is typed by up to three keys ([Kind]): its whole pinyin (`mizipang`), the pinyin of its
 * first three characters where it has four or more (`zhonghuaren` of 中华人民共和国), and the first
 * letter of each syllable where it has three or more (`mzp`), or two on full pinyin's 26 keys alone
 * (`zg`; elsewhere two initials are a syllable's keys: 双拼's `wm` is wen, the nine keys' 96 wo; one
 * character's would take every input starting with that letter). The keys are spelt as the keys
 * type each syllable ([index]'s `spell`): its pinyin, or 双拼's two keys, whose first is then the
 * initial's; full pinyin's lüe and nüe either way, `lve` or `lue`, as it reads them ([index]'s `also`).
 *
 * The file and the export are one text: a header, then a word a line, its replacements after it,
 * apart by tabs (spaces read too, as a phone has no tab key; written as tabs). A word may carry its
 * reading in brackets, `重庆[chong'qing]`; one without is read as the dictionary reads it as it is
 * imported, and kept so ([parse]'s `guess`), so a dictionary changed later moves nothing. A
 * replacement holds no blank: a word, not a sentence (a custom phrase is for that). A line of the
 * file not read ([unread]) is written back as it was, so nothing the user put there is lost by a
 * save; a file of a later version ([newer]) is read as none and not written over.
 */
class CommonWords private constructor(
    val words: List<Word>,
    /** The lines of the file read that were not, as they were: written back after the words. */
    val unread: List<String> = emptyList(),
    /** Read off a file of a later version than [VERSION]: no words, and nothing to write over it. */
    val newer: Boolean = false,
) {

    /** [text], read [syllables], replaced by [replacements] in their order. */
    class Word(val text: String, val syllables: IntArray, val replacements: List<String>) {
        /** Its reading as the file writes it: `mi'zi'pang`. */
        val pinyin: String get() = Syllables.apostrophed(syllables)

        override fun equals(other: Any?) =
            other is Word && text == other.text && syllables.contentEquals(other.syllables) && replacements == other.replacements

        override fun hashCode() = (text.hashCode() * 31 + syllables.contentHashCode()) * 31 + replacements.hashCode()

        override fun toString() = "$text[$pinyin] ${replacements.joinToString(" ")}"
    }

    /** The keys a word is typed by, in the order their replacements go when several words share one. */
    enum class Kind { WHOLE, FIRST_THREE, INITIALS }

    /** What is wrong with a line, or a word as the settings would save it. */
    enum class Problem { NO_REPLACEMENT, SAME_AS_WORD, NO_PINYIN, BAD_PINYIN, TOO_LONG, TOO_MANY, BLANK_IN_REPLACEMENT, NEWER }

    /** The [line]th line (1 based) of a file, [text], left out for [problem]. */
    class Skipped(val line: Int, val text: String, val problem: Problem)

    /** A file read: its words, and the lines left out. */
    class Read(val words: CommonWords, val skipped: List<Skipped>)

    val isEmpty: Boolean get() = words.isEmpty()

    /** As the file and the export have it: the header, then a line a word, tabs apart; the lines [unread] after them. */
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

    /** These words without [replacement] of [text]'s; a word left with none goes. */
    fun without(text: String, replacement: String): CommonWords = CommonWords(
        words.mapNotNull { w ->
            if (w.text != text) w else w.replacements.filter { it != replacement }.takeIf { it.isNotEmpty() }?.let { Word(w.text, w.syllables, it) }
        },
        unread,
        newer,
    )

    /**
     * [file]'s words laid over these, as an import has them: one of a word these have takes its
     * place ([Imported.updated]), any other goes at the end ([Imported.added]) while there is room
     * ([MAX_WORDS]); those past it are [Imported.full].
     */
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

    /** These words with an import's laid over them: [added] new, [updated] replaced, [full] left out for want of room. */
    class Imported(val words: CommonWords, val added: Int, val updated: Int, val full: List<Word>)

    /**
     * The words keyed as the keys type them: [spell] is how a syllable is typed (its pinyin, 双拼's
     * two keys), null where it cannot be, which leaves that word's keys of it out; [also], another
     * way a spelling is typed ([keys]); [initialsOfTwo], whether a word of two has its initials' key.
     */
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

    // a word under a key, of [kind], its syllables ending at [ends] in it (0 and its length too)
    internal class Hit(val word: Int, val kind: Kind, val ends: IntArray)

    /** A replacement offered: its [text], and the [word] it replaces. */
    class Found(val text: String, val word: String)

    /** [CommonWords] by their keys, as one way of typing spells them. */
    class Index internal constructor(val of: CommonWords, private val byKey: Map<String, List<Hit>>) {

        val isEmpty: Boolean get() = byKey.isEmpty()

        operator fun contains(key: String) = key in byKey

        // asked at every key typed on keys of letter groups: the keys of each length, once
        private val keysByLength by lazy { byKey.keys.groupBy { it.length } }

        /** The keys [typed] reads as ([PhraseKeys]): those of its lengths it reads. */
        fun keysRead(typed: PhraseKeys): List<String> = typed.lengths.flatMap { keysByLength[it].orEmpty() }.filter(typed::reads)

        /**
         * The replacements under [keys] together, each text once: by the kind of key ([Kind]'s
         * order), then as the words are listed, each word's in its order.
         */
        fun found(keys: List<String>): List<Found> = ofHits(keys.flatMap { byKey[it].orEmpty() })

        /**
         * The replacements [typed] is a key of, as [found]: typed in letters, each `'` in it where
         * one of the word's syllables ends (`xi'an` 西安's, not 先's `xian`; `miz'ipang` none).
         */
        fun foundTyped(typed: String): List<Found> {
            val key = typed.filter { it != SEPARATOR }
            val hits = byKey[key] ?: return emptyList()
            var at = 0
            val apart = ArrayList<Int>()
            for (c in typed) if (c == SEPARATOR) apart += at else at++
            return ofHits(hits.filter { h -> apart.all { it == 0 || it == key.length || it in h.ends } })
        }

        private fun ofHits(hits: List<Hit>): List<Found> {
            val seen = HashSet<String>()
            return hits.distinctBy { it.word }.sortedWith(compareBy({ it.kind }, { it.word }))
                .flatMap { h -> of.words[h.word].let { w -> w.replacements.filter(seen::add).map { Found(it, w.text) } } }
        }
    }

    companion object {
        /** The file's version: one of a later one is read as none ([newer]). */
        const val VERSION = 1
        const val HEADER = "# youmo-common-words $VERSION"
        const val MAX_WORDS = 2000
        const val MAX_REPLACEMENTS = 9
        const val MAX_REPLACEMENT_CHARS = 32

        /** A word given its reading may be this long; one read by the dictionary as long as it reads (EngineAssets.MAX_WORD_CHARS). */
        const val MAX_WORD_CHARS = 16

        /** Its first this many characters are a key of a word longer than that. */
        const val FIRST = 3

        val EMPTY = CommonWords(emptyList())

        // the separator typed between syllables (SyllableGraph.SEPARATOR)
        private const val SEPARATOR = '\''

        /**
         * [mine], the words as one writer has them since it read [base], laid over [theirs], the
         * file as it is now (the settings page and the keyboard both write it): a word [mine]
         * added or changed is as [mine] has it, one it took out is gone, one it left alone is as
         * [theirs] has it (gone if the other took it out); a word [theirs] added is kept after them.
         */
        fun merged(base: CommonWords, mine: CommonWords, theirs: CommonWords): CommonWords {
            // the file's lines not read stay as it has them (the page and the keyboard keep none)
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

        /** [words] as they are, checked by [problem] already (the settings' list). */
        fun of(words: List<Word>) = CommonWords(words)

        /**
         * The keys [word] is typed by, as [spell] types a syllable, in [Kind]'s order; none where
         * a syllable cannot be typed. One of each kind, but where [also] gives a spelling
         * another way (full pinyin's `lue` for `lve`): a key each way, [MAX_WAYS] at most a kind.
         * A word of two has its initials' key only where [initialsOfTwo] (full pinyin's 26 keys).
         */
        fun keys(word: Word, spell: (Int) -> String?, initialsOfTwo: Boolean = true, also: (String) -> String? = { null }): List<Pair<Kind, String>> =
            keyParts(word, spell, initialsOfTwo, also).map { it.kind to it.parts.joinToString("") }

        // a key, its syllables' parts apart
        private class KeyParts(val kind: Kind, val parts: List<String>)

        private fun keyParts(word: Word, spell: (Int) -> String?, initialsOfTwo: Boolean, also: (String) -> String?): List<KeyParts> {
            val spelt = word.syllables.map { spell(it)?.takeIf(String::isNotEmpty) ?: return emptyList() }
            val ways = spelt.map { listOfNotNull(it, also(it)) }
            val n = spelt.size
            // each way of each syllable in turn, the first ways first
            fun every(of: List<List<String>>): List<List<String>> =
                of.fold(listOf(emptyList<String>())) { acc, w -> acc.flatMap { a -> w.map { a + it } }.take(MAX_WAYS) }
            return every(ways).map { KeyParts(Kind.WHOLE, it) } +
                (if (n > FIRST) every(ways.take(FIRST)).map { KeyParts(Kind.FIRST_THREE, it) } else emptyList()) +
                (if (n > 2 || n == 2 && initialsOfTwo) listOf(KeyParts(Kind.INITIALS, spelt.map { it.take(1) })) else emptyList())
        }

        // the keys of a word a kind at most: its syllables' other ways are few (lüe, nüe)
        private const val MAX_WAYS = 4

        /** Full pinyin's other way of [spelling]: lüe and nüe's `ue` for `ve` ([index]'s `also`). */
        fun fullPinyinAlso(spelling: String): String? =
            if (spelling.endsWith("ve") && spelling.length == 3) spelling.dropLast(2) + "ue" else null

        /**
         * What keeps [text] read [syllables] replaced by [replacements] from being a word, as the
         * settings save it and a line is read; null for nothing. [replacements] as the user gave
         * them, before [clean]: none of them all blanks is no replacement.
         */
        fun problem(text: String, syllables: IntArray?, replacements: List<String>): Problem? {
            val kept = clean(text, replacements)
            return when {
                text.codePointCount(0, text.length) > MAX_WORD_CHARS -> Problem.TOO_LONG
                replacements.any { it.codePointCount(0, it.length) > MAX_REPLACEMENT_CHARS } -> Problem.TOO_LONG
                // a word, not a sentence: one with a blank is refused, not joined up behind the user's back
                replacements.any { r -> r.trim().any(Char::isWhitespace) } -> Problem.BLANK_IN_REPLACEMENT
                kept.isEmpty() -> if (replacements.any { it == text }) Problem.SAME_AS_WORD else Problem.NO_REPLACEMENT
                syllables == null -> Problem.NO_PINYIN
                else -> null
            }
        }

        /** [replacements] as kept: trimmed, none blank or the word itself, each once, [MAX_REPLACEMENTS] at most. */
        fun clean(text: String, replacements: List<String>): List<String> =
            replacements.map { it.trim() }.filter { it.isNotEmpty() && it != text }.distinct().take(MAX_REPLACEMENTS)

        /** [pinyin] (`chong'qing`, `chong qing`, `chongqing`) as [text]'s syllables, as WordLists reads a user's; null where it does not read so. */
        fun syllables(text: String, pinyin: String): IntArray? = WordLists.entry(text, pinyin)?.syllables

        /**
         * [text], a file as [format] writes it or a user edits one, read: a line a word, its
         * replacements after it apart by blanks; `#` starts a comment. A word with no reading in
         * brackets is read as [guess] reads it (the dictionary, for those of them all at once);
         * not read so, it is skipped. Two lines of one word are one, their replacements in turn.
         * Past [MAX_WORDS] words, the rest are skipped too. The lines skipped are kept as they were
         * ([unread]). A file whose header says a later [VERSION] is read as none ([newer]).
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
                    // a reading given that does not read is wrong; the dictionary's not reading it is none at all
                    val why = if (problem == Problem.NO_PINYIN && l.pinyin != null) Problem.BAD_PINYIN else problem
                    skipped += Skipped(l.number, l.raw, why ?: Problem.NO_PINYIN)
                    continue
                }
                val before = words[l.word]
                val replacements = clean(l.word, before?.replacements.orEmpty() + l.replacements)
                words[l.word] = Word(l.word, before?.syllables ?: syllables, replacements)
            }
            return Read(CommonWords(words.values.toList(), skipped.map { it.text }), skipped)
        }

        // the header's line and itself where it names a later version than this reads
        private fun later(text: String): Pair<Int, String>? {
            val (i, first) = text.removePrefix("\ufeff").lineSequence().withIndex().firstOrNull { it.value.isNotBlank() } ?: return null
            val version = HEADER_LINE.matchEntire(first.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return if (version > VERSION) i + 1 to first.trim() else null
        }

        private val HEADER_LINE = Regex("# youmo-common-words (\\d+)")

        private class Line(val number: Int, val raw: String, val word: String, val pinyin: String?, val replacements: List<String>)

        // the lines of words of [text], comments and blank lines left out
        private fun lines(text: String): List<Line> = text.removePrefix("\ufeff").lineSequence().withIndex().mapNotNull { (i, raw) ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            // the reading may have blanks in it (`[chong qing]`), as a user types one
            val read = READ.matchEntire(line)
            val fields = (read?.groupValues?.get(3) ?: line).trim().split(BLANKS).filter { it.isNotEmpty() }
            if (read != null) Line(i + 1, line, read.groupValues[1], read.groupValues[2], fields) else Line(i + 1, line, fields[0], null, fields.drop(1))
        }.toList()

        private val BLANKS = Regex("\\s+")

        // a word, its reading in brackets right after it (a blank between taken too), the rest
        private val READ = Regex("(\\S+?)\\s*\\[([^\\]]*)\\](.*)")
    }
}
