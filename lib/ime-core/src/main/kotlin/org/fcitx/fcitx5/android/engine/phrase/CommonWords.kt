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
 * letter of each syllable where it has two or more (`mzp`; one character's would take every input
 * starting with that letter). The keys are spelt as the keys type each syllable ([index]'s
 * `spell`): its pinyin, or 双拼's two keys, whose first is then the initial's.
 *
 * The file and the export are one text: a header, then a word a line, its replacements after it,
 * apart by tabs (spaces read too, as a phone has no tab key; written as tabs). A word may carry its
 * reading in brackets, `重庆[chong'qing]`; one without is read as the dictionary reads it as it is
 * imported, and kept so ([parse]'s `guess`), so a dictionary changed later moves nothing. A
 * replacement holds no blank: a word, not a sentence (a custom phrase is for that).
 */
class CommonWords private constructor(val words: List<Word>) {

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
    enum class Problem { NO_REPLACEMENT, SAME_AS_WORD, NO_PINYIN, BAD_PINYIN, TOO_LONG, TOO_MANY }

    /** The [line]th line (1 based) of a file, [text], left out for [problem]. */
    class Skipped(val line: Int, val text: String, val problem: Problem)

    /** A file read: its words, and the lines left out. */
    class Read(val words: CommonWords, val skipped: List<Skipped>)

    val isEmpty: Boolean get() = words.isEmpty()

    /** As the file and the export have it: the header, then a line a word, tabs apart. */
    fun format(): String = buildString {
        append(HEADER).append('\n')
        for (w in words) {
            append(w.text).append('[').append(w.pinyin).append(']')
            for (r in w.replacements) append('\t').append(r)
            append('\n')
        }
    }

    /** These words without [replacement] of [text]'s; a word left with none goes. */
    fun without(text: String, replacement: String): CommonWords = CommonWords(
        words.mapNotNull { w ->
            if (w.text != text) w else w.replacements.filter { it != replacement }.takeIf { it.isNotEmpty() }?.let { Word(w.text, w.syllables, it) }
        }
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
        return Imported(CommonWords(out), added, updated, full)
    }

    /** These words with an import's laid over them: [added] new, [updated] replaced, [full] left out for want of room. */
    class Imported(val words: CommonWords, val added: Int, val updated: Int, val full: List<Word>)

    /**
     * The words keyed as the keys type them: [spell] is how a syllable is typed (its pinyin, 双拼's
     * two keys), null where it cannot be, which leaves that word's keys of it out.
     */
    fun index(spell: (Int) -> String?): Index {
        val byKey = HashMap<String, MutableList<Hit>>()
        for ((i, w) in words.withIndex()) {
            for ((kind, key) in keys(w, spell)) byKey.getOrPut(key) { ArrayList() } += Hit(i, kind)
        }
        return Index(this, byKey)
    }

    internal class Hit(val word: Int, val kind: Kind)

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
        fun found(keys: List<String>): List<Found> {
            val hits = keys.flatMap { byKey[it].orEmpty() }.distinctBy { it.word }.sortedWith(compareBy({ it.kind }, { it.word }))
            val seen = HashSet<String>()
            return hits.flatMap { h -> of.words[h.word].let { w -> w.replacements.filter(seen::add).map { Found(it, w.text) } } }
        }
    }

    companion object {
        const val HEADER = "# youmo-common-words 1"
        const val MAX_WORDS = 2000
        const val MAX_REPLACEMENTS = 9
        const val MAX_REPLACEMENT_CHARS = 32

        /** A word given its reading may be this long; one read by the dictionary as long as it reads (EngineAssets.MAX_WORD_CHARS). */
        const val MAX_WORD_CHARS = 16

        /** Its first this many characters are a key of a word longer than that. */
        const val FIRST = 3

        val EMPTY = CommonWords(emptyList())

        /**
         * [mine], the words as one writer has them since it read [base], laid over [theirs], the
         * file as it is now (the settings page and the keyboard both write it): a word [mine]
         * added or changed is as [mine] has it, one it took out is gone, one it left alone is as
         * [theirs] has it (gone if the other took it out); a word [theirs] added is kept after them.
         */
        fun merged(base: CommonWords, mine: CommonWords, theirs: CommonWords): CommonWords {
            if (theirs.words == base.words) return mine
            val before = base.words.associateBy { it.text }
            val now = theirs.words.associateBy { it.text }
            val out = LinkedHashMap<String, Word>()
            for (w in mine.words) {
                if (before[w.text] != w) out[w.text] = w else now[w.text]?.let { out[w.text] = it }
            }
            for (w in theirs.words) if (w.text !in out && w.text !in before) out[w.text] = w
            return CommonWords(out.values.toList())
        }

        /** [words] as they are, checked by [problem] already (the settings' list). */
        fun of(words: List<Word>) = CommonWords(words)

        /**
         * The keys [word] is typed by, as [spell] types a syllable, in [Kind]'s order; one key a
         * kind at most, none of a kind a syllable cannot be typed of.
         */
        fun keys(word: Word, spell: (Int) -> String?): List<Pair<Kind, String>> {
            val spelt = word.syllables.map(spell)
            val n = spelt.size
            fun joined(parts: List<String?>) = if (parts.any { it.isNullOrEmpty() }) null else parts.joinToString("")
            return listOfNotNull(
                joined(spelt)?.let { Kind.WHOLE to it },
                joined(spelt.take(FIRST)).takeIf { n > FIRST }?.let { Kind.FIRST_THREE to it },
                joined(spelt.map { it?.take(1) }).takeIf { n >= 2 }?.let { Kind.INITIALS to it },
            )
        }

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
                kept.isEmpty() -> if (replacements.any { it == text }) Problem.SAME_AS_WORD else Problem.NO_REPLACEMENT
                syllables == null -> Problem.NO_PINYIN
                else -> null
            }
        }

        /** [replacements] as kept: no blank in one, none the word itself, each once, [MAX_REPLACEMENTS] at most. */
        fun clean(text: String, replacements: List<String>): List<String> =
            replacements.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() && it != text }.distinct().take(MAX_REPLACEMENTS)

        /** [pinyin] (`chong'qing`, `chong qing`, `chongqing`) as [text]'s syllables, as WordLists reads a user's; null where it does not read so. */
        fun syllables(text: String, pinyin: String): IntArray? = WordLists.entry(text, pinyin)?.syllables

        /**
         * [text], a file as [format] writes it or a user edits one, read: a line a word, its
         * replacements after it apart by blanks; `#` starts a comment. A word with no reading in
         * brackets is read as [guess] reads it (the dictionary, for those of them all at once);
         * not read so, it is skipped. Two lines of one word are one, their replacements in turn.
         * Past [MAX_WORDS] words, the rest are skipped too.
         */
        fun parse(text: String, guess: (List<String>) -> Map<String, String?> = { emptyMap() }): Read {
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
            return Read(CommonWords(words.values.toList()), skipped)
        }

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
