/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import java.text.BreakIterator

/**
 * A clipboard entry cut into 「切词」's pieces: an email as name, `@`, domain; a URL by scheme, host, `/` parts, query,
 * fragment; Chinese as the pinyin model splits it; other words, numbers and marks each a piece; spaces none. Nothing
 * is cut inside a grapheme cluster (👨‍👩‍👧, 🇨🇳, 𬭚 whole).
 */
object ClipboardChunks {

    data class Chunk(val text: String, val from: Int, val to: Int)

    // ASCII only after the scheme (a URL pasted into Chinese ends where the Chinese begins); none glued to a word before
    private val URL = Regex("""(?i)(?<![a-z0-9])(?:(?:https?|ftps?|sftp|wss?|file|ssh|git|smb|rtsp|ed2k|thunder)://|www\.)[!-~]+""")
    // from the name's first char only: else each char of a long token without @ starts a match (O(n²))
    private val EMAIL = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")

    // what a sentence puts after a URL or an email, not part of it; a ) only when the link opened none
    private const val TRAILING = ".,;:!?]}'\">"

    /** Each to be split by the model and passed back to [split]. */
    fun hanRuns(text: String): List<String> =
        plain(text).flatMap { (from, to) -> runs(text, from, to) }
            .filter { it.kind == Kind.Han }
            .map { text.substring(it.from, it.to) }
            .distinct()

    fun split(text: String, words: Map<String, IntArray> = emptyMap()): List<String> = chunks(text, words).map { it.text }

    /** [words]: the model's boundaries per run, 0 and length included; a run missing goes cluster by cluster. */
    fun chunks(text: String, words: Map<String, IntArray> = emptyMap()): List<Chunk> {
        val out = ArrayList<Chunk>()
        var at = 0
        for (match in links(text)) {
            plainPieces(text, at, match.first, words, out)
            linkPieces(text, match.first, match.last + 1, out)
            at = match.last + 1
        }
        plainPieces(text, at, text.length, words, out)
        return out
    }

    /** Ending between grapheme clusters. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val it = BreakIterator.getCharacterInstance().apply { setText(text) }
        return text.substring(0, if (it.isBoundary(max)) max else it.preceding(max).coerceAtLeast(0))
    }

    /** In tap order: adjacent pieces with their text between, else a space where a Latin word or number would run together. */
    fun join(text: String, all: List<Chunk>, picked: List<Chunk>): String = buildString {
        var last: Chunk? = null
        for (chunk in picked) {
            val before = last
            if (before != null) {
                val next = all.indexOf(before) + 1
                when {
                    // the text between, but for what is invisible (a zero-width space, a ZWJ)
                    next in all.indices && all[next] == chunk ->
                        for (i in before.to until chunk.from) if (!isFormat(text[i])) append(text[i])
                    isLatinWord(lastBase(before.text)) && isLatinWord(chunk.text.codePointAt(0)) -> append(' ')
                }
            }
            append(chunk.text)
            last = chunk
        }
    }

    private fun isLatinWord(cp: Int) =
        Character.isDigit(cp) || (Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN)

    // a café spelled e and U+0301 ends in e
    private fun lastBase(s: String): Int {
        var i = s.length
        while (i > 0) {
            val cp = s.codePointBefore(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) return cp
            i -= Character.charCount(cp)
        }
        return s.codePointAt(0)
    }

    // not overlapping, trailing punctuation left out
    private fun links(text: String): List<IntRange> {
        val found = (URL.findAll(text) + EMAIL.findAll(text)).map { trimmed(text, it.range) }
            .sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val kept = ArrayList<IntRange>()
        for (range in found) if (!range.isEmpty() && (kept.isEmpty() || range.first > kept.last().last)) kept += range
        return kept
    }

    private fun trimmed(text: String, range: IntRange): IntRange {
        var last = range.last
        // counted once as [last] moves back: a link followed by thousands of dots is not counted again at each
        var opened = 0
        var closed = 0
        for (i in range.first until last) {
            if (text[i] == '(') opened++ else if (text[i] == ')') closed++
        }
        while (last > range.first) {
            val c = text[last]
            // Foo_(bar) keeps its ), (see a.cn) does not
            val unopened = c == ')' && opened <= closed
            if (c !in TRAILING && !unopened) break
            last--
            if (text[last] == '(') opened-- else if (text[last] == ')') closed--
        }
        return range.first..last
    }

    private fun linkPieces(text: String, begin: Int, end: Int, out: MutableList<Chunk>) {
        fun piece(from: Int, to: Int) {
            out += Chunk(text.substring(from, to), from, to)
        }
        val link = text.substring(begin, end)
        val at = link.indexOf('@')
        if (at > 0 && "://" !in link && !link.startsWith("www.", ignoreCase = true)) {
            piece(begin, begin + at)
            piece(begin + at, begin + at + 1)
            piece(begin + at + 1, end)
            return
        }
        val scheme = link.indexOf("://").let { if (it < 0) 0 else it + 3 }
        if (scheme > 0) piece(begin, begin + scheme)
        // the host ends at the first of / ? #, then a piece begins at each of them
        var from = scheme
        var inQuery = false
        for (i in scheme until link.length) {
            val c = link[i]
            // a / in the query or the fragment is theirs
            val cuts = c == '?' || c == '#' || (c == '/' && !inQuery)
            if (c == '?' || c == '#') inQuery = true
            if (cuts && i > from) {
                piece(begin + from, begin + i)
                from = i
            }
        }
        if (from < link.length) piece(begin + from, end)
    }

    private fun isFormat(c: Char) = Character.getType(c) == Character.FORMAT.toInt()

    // without the format chars a cluster took in at its ends (b‍): nothing invisible is committed
    private fun MutableList<Chunk>.piece(text: String, from: Int, to: Int) {
        var a = from
        var b = to
        while (a < b && isFormat(text[a])) a++
        while (b > a && isFormat(text[b - 1])) b--
        if (a < b) this += Chunk(text.substring(a, b), a, b)
    }

    private fun plainPieces(text: String, from: Int, to: Int, words: Map<String, IntArray>, out: MutableList<Chunk>) {
        for ((kind, begin, end) in runs(text, from, to)) {
            when (kind) {
                Kind.Space -> Unit
                Kind.Han -> {
                    val run = text.substring(begin, end)
                    val clusters = clusterEnds(text, begin, end).map { it - begin }
                    val bounds = words[run]?.takeIf { it.size >= 2 && it.first() == 0 && it.last() == run.length }
                    // the model's ends that fall between clusters (none inside 𬭚's two halves), else each cluster
                    val ends = bounds?.let { b -> clusters.toHashSet().let { set -> b.filter { it in set } } } ?: clusters
                    var at = 0
                    for (e in ends) {
                        if (e > at) out.piece(text, begin + at, begin + e)
                        at = e
                    }
                }
                Kind.Mark -> {
                    var at = begin
                    for (e in clusterEnds(text, begin, end)) {
                        out.piece(text, at, e)
                        at = e
                    }
                }
                Kind.Letter, Kind.Digit -> out.piece(text, begin, end)
            }
        }
    }

    private fun clusterEnds(text: String, from: Int, to: Int): List<Int> {
        val it = BreakIterator.getCharacterInstance().apply { setText(text.substring(from, to)) }
        val out = ArrayList<Int>()
        var e = it.next()
        while (e != BreakIterator.DONE) {
            out += from + e
            e = it.next()
        }
        return out
    }

    private fun plain(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var at = 0
        for (link in links(text)) {
            out += at to link.first
            at = link.last + 1
        }
        out += at to text.length
        return out
    }

    private enum class Kind { Han, Letter, Digit, Space, Mark }

    private data class Run(val kind: Kind, val from: Int, val to: Int)

    // one of format chars only (a lone ZWJ, a zero-width space) is no piece
    private fun kindOf(text: String, from: Int, to: Int): Kind {
        val cp = text.codePointAt(from)
        val invisible = (from until to).all { isFormat(text[it]) }
        return when {
            invisible || Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> Kind.Space
            Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN -> Kind.Han
            Character.isDigit(cp) -> Kind.Digit
            Character.isLetter(cp) -> Kind.Letter
            else -> Kind.Mark
        }
    }

    // a mark keeps a word or number whole between two of its kind: don't, 3.14, 1,000
    private fun runs(text: String, from: Int, to: Int): List<Run> {
        if (from >= to) return emptyList()
        val ends = clusterEnds(text, from, to)
        val starts = listOf(from) + ends.dropLast(1)
        val kinds = starts.indices.map { kindOf(text, starts[it], ends[it]) }
        val out = ArrayList<Run>()
        var i = 0
        while (i < starts.size) {
            val kind = kinds[i]
            var j = i + 1
            if (kind == Kind.Letter || kind == Kind.Digit || kind == Kind.Han) {
                val joiner = if (kind == Kind.Letter) "'’-" else if (kind == Kind.Digit) ".,:" else ""
                fun joins(at: Int) = at + 1 < starts.size && ends[at] - starts[at] == 1 && text[starts[at]] in joiner && kinds[at + 1] == kind
                while (j < starts.size) {
                    if (kinds[j] == kind) {
                        j++
                    } else if (joins(j)) {
                        j += 2
                    } else {
                        break
                    }
                }
            }
            out += Run(kind, starts[i], ends[j - 1])
            i = j
        }
        return out
    }
}
