/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxUtils

/** One of the user's own quick phrases: [key] typed to find it, [content] what it puts in. */
data class QuickPhraseEntry(val key: String, val content: String) {
    /**
     * The same entry to the user: the key case aside, as typing finds it ([QuickPhraseBook.find]), the
     * content as it is. Two such would be listed for the same keys with the same text.
     */
    fun sameAs(other: QuickPhraseEntry): Boolean = content == other.content && folded(key) == folded(other.key)

    internal val identity: Pair<String, String> get() = folded(key) to content

    private companion object {
        // each code point lowered as find lowers them, not String.lowercase's locale and special cases
        fun folded(key: String): String {
            val out = StringBuilder(key.length)
            var i = 0
            while (i < key.length) {
                val c = key.codePointAt(i)
                out.appendCodePoint(Character.toLowerCase(c))
                i += Character.charCount(c)
            }
            return out.toString()
        }
    }
}

/**
 * What quick input offers: the user's own entries ([mine], `data/quickphrase.d/mine.mb` of the
 * user's data) and the app's tables ([builtIn]). The user's are in order of use, the one used,
 * added or changed last first ([used], `mine.used`, the times kept apart so the keyboard writes
 * no file the settings page writes); the built-in ones in their table's order.
 */
class QuickPhraseBook(
    val builtIn: QuickPhraseTable,
    mine: List<QuickPhraseEntry> = emptyList(),
    used: Map<QuickPhraseEntry, Long> = emptyMap(),
) {
    private val times = HashMap(used)

    // the file's order, the tie-break between two never used
    private val entries = QuickPhraseFiles.distinct(mine)

    @Volatile
    private var ordered: List<QuickPhraseEntry> = order()

    private fun order() = entries.withIndex().sortedWith(
        compareByDescending<IndexedValue<QuickPhraseEntry>> { times[it.value] ?: 0L }.thenBy { it.index },
    ).map { it.value }

    /** The user's entries, the last used first. */
    val mine: List<QuickPhraseEntry> get() = ordered

    /** [entry] used at [at]: it goes first. One not in the book is left alone. */
    @Synchronized
    fun use(entry: QuickPhraseEntry, at: Long) {
        if (entry !in entries) return
        times[entry] = at
        ordered = order()
    }

    /**
     * What [typed] finds, each place of it the characters its key may be ([QuickPhraseKeys]): the
     * user's entries whose key starts with them, case aside, the last used first, then the built-in
     * ones the same way, case kept as fcitx has it, in table order. [withMine] false: none of the
     * user's (借手机, the habits off). Nothing typed finds nothing here: the caller lists [mine].
     */
    fun find(typed: List<Set<Int>>, withMine: Boolean = true): List<Found> {
        if (typed.isEmpty()) return emptyList()
        val lowered = typed.map { set -> set.mapTo(HashSet()) { Character.toLowerCase(it) } }
        val out = ArrayList<Found>()
        if (withMine) mine.filter { starts(it.key, lowered, ignoreCase = true) }.mapTo(out) { Found(it.key, it.content, true) }
        builtIn.entries().filter { starts(it.first, typed, ignoreCase = false) }.mapTo(out) { Found(it.first, it.second, false) }
        return out
    }

    /** A match: [mine] one of the user's entries, else a built-in table's. */
    data class Found(val key: String, val content: String, val mine: Boolean) {
        val entry get() = QuickPhraseEntry(key, content)
    }

    companion object {
        val EMPTY = QuickPhraseBook(QuickPhraseTable.EMPTY)

        const val DIR = "data/quickphrase.d"
        const val MINE = "mine.mb"
        const val USED = "mine.used"

        /** The longest key: quick input takes at most 30 characters ([QuickPhrase]). */
        const val MAX_KEY = 30

        /**
         * The longest content, in UTF-16 units: what the page saves and an import takes. One longer
         * (the clipboard's) is refused before the page is opened, its text in the Intent besides.
         */
        const val MAX_CONTENT = 5000

        private fun starts(key: String, typed: List<Set<Int>>, ignoreCase: Boolean): Boolean {
            var i = 0
            for (set in typed) {
                if (i >= key.length) return false
                val c = key.codePointAt(i)
                if ((if (ignoreCase) Character.toLowerCase(c) else c) !in set) return false
                i += Character.charCount(c)
            }
            return true
        }

        /**
         * The book of [user]'s data and the app's [builtIn]: of the user's directory `mine.mb`,
         * `mine.used` and the `<name>.mb.disable` that turn a built-in table off. No other `.mb` of
         * the user's is read (no release ever wrote one), so a built-in table is never hidden by name.
         */
        fun load(user: PkgDataDir?, builtIn: PkgDataDir): QuickPhraseBook {
            val disables = user?.let { u ->
                object : PkgDataDir {
                    override fun read(path: String): ByteArray? = null
                    override fun list(dir: String) = u.list(dir)?.filter { it.endsWith(".disable") }
                }
            }
            val table = QuickPhraseTable.load(listOfNotNull(disables, builtIn))
            val mine = user?.read("$DIR/$MINE")?.let(QuickPhraseFiles::parseMine).orEmpty()
            val used = user?.read("$DIR/$USED")?.let { QuickPhraseFiles.parseUsed(String(it, Charsets.UTF_8)) }.orEmpty()
            return QuickPhraseBook(table, mine, used)
        }
    }
}

/**
 * The user's quick phrase files. `mine.mb` is fcitx's `.mb`, a line `key content` each, the content
 * through fcitx's escaping (several lines as `"…\n…"`), so the file is the export and fcitx5 on a
 * computer reads it as it is. `mine.used` is a line `millis<TAB>key<TAB>escaped content` each.
 */
object QuickPhraseFiles {

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * The lines of a `.mb` file as entries, in order, lines fcitx skips left out; a UTF-8 BOM at its
     * start (a file saved by Windows' Notepad) is not part of the first key.
     */
    fun parseLines(bytes: ByteArray): List<QuickPhraseEntry> {
        val text = if (bytes.size >= BOM.size && (BOM.indices).all { bytes[it] == BOM[it] }) bytes.copyOfRange(BOM.size, bytes.size) else bytes
        return QuickPhraseTable.parse(text).map { (k, v) -> QuickPhraseEntry(k, v) }
    }

    /**
     * The user's entries of `mine.mb`: [parseLines], each once ([distinct]), none longer than
     * [QuickPhraseBook.MAX_CONTENT] (only a file made elsewhere has one). What is left out here is
     * gone from the file at its next write.
     */
    fun parseMine(bytes: ByteArray): List<QuickPhraseEntry> =
        distinct(parseLines(bytes).filter { it.content.length <= QuickPhraseBook.MAX_CONTENT })

    /** [entries] each once ([QuickPhraseEntry.sameAs]), the first of each kept. */
    fun distinct(entries: List<QuickPhraseEntry>): List<QuickPhraseEntry> = entries.distinctBy { it.identity }

    /** [entries] as a `.mb` file reads them back ([parseMine]). */
    fun formatMine(entries: List<QuickPhraseEntry>): String =
        entries.joinToString("") { "${it.key} ${FcitxUtils.escapeForValue(it.content)}\n" }

    /** `mine.used`'s times; a line that does not read is skipped. */
    fun parseUsed(text: String): Map<QuickPhraseEntry, Long> {
        val out = HashMap<QuickPhraseEntry, Long>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t', limit = 3).takeIf { it.size == 3 }
            val at = parts?.get(0)?.toLongOrNull()
            val content = parts?.get(2)?.let(FcitxUtils::unescapeForValue)
            if (at != null && content != null) out[QuickPhraseEntry(parts[1], content)] = at
        }
        return out
    }

    /** [used] as `mine.used`, only the times of [entries] (one changed or deleted goes). */
    fun formatUsed(used: Map<QuickPhraseEntry, Long>, entries: Collection<QuickPhraseEntry>): String {
        val kept = entries.toSet()
        return used.entries.filter { it.key in kept }.sortedBy { it.value }
            .joinToString("") { "${it.value}\t${it.key.key}\t${FcitxUtils.escapeForValue(it.key.content)}\n" }
    }

    /** Whether [key] can be a key: not empty, no whitespace (a `.mb` line's key ends at the first), at most [QuickPhraseBook.MAX_KEY]. */
    fun validKey(key: String): Boolean =
        key.isNotEmpty() && key.none { it.isWhitespace() } && key.codePointCount(0, key.length) <= QuickPhraseBook.MAX_KEY

    /** What [merge] made: [entries] after it, and how many lines it skipped as already there or too long. */
    data class Merged(val entries: List<QuickPhraseEntry>, val duplicates: Int, val tooLong: Int)

    /**
     * [lines] of a `.mb` file ([parseLines]) added after [mine]: the ones whose key is good
     * ([validKey]), whose content is not longer than [QuickPhraseBook.MAX_CONTENT], and that are not
     * there already ([QuickPhraseEntry.sameAs]).
     */
    fun merge(mine: List<QuickPhraseEntry>, lines: List<QuickPhraseEntry>): Merged {
        val out = ArrayList(mine)
        val seen = mine.mapTo(HashSet()) { it.identity }
        var duplicates = 0
        var tooLong = 0
        for (e in lines) {
            when {
                !validKey(e.key) || e.content.isEmpty() -> {}
                e.content.length > QuickPhraseBook.MAX_CONTENT -> tooLong++
                !seen.add(e.identity) -> duplicates++
                else -> out += e
            }
        }
        return Merged(out, duplicates, tooLong)
    }
}

/** How a quick phrase's content shows in the candidate bar: its first line's start. */
object QuickPhraseExcerpt {
    const val ELLIPSIS = "…"
    const val MORE_LINES = "⏎"

    /**
     * [content]'s first line, cut to [width] columns (a CJK or other wide character two, the rest one)
     * with [ELLIPSIS], [MORE_LINES] after when more lines follow.
     */
    fun of(content: String, width: Int = 24): String {
        val newline = content.indexOf('\n')
        val first = if (newline < 0) content else content.substring(0, newline)
        val more = newline >= 0 && content.substring(newline + 1).isNotBlank()
        val out = StringBuilder()
        var used = 0
        var i = 0
        var cut = false
        while (i < first.length) {
            val c = first.codePointAt(i)
            val w = if (wide(c)) 2 else 1
            if (used + w > width) {
                cut = true
                break
            }
            out.appendCodePoint(c)
            used += w
            i += Character.charCount(c)
        }
        if (cut) out.append(ELLIPSIS)
        if (more) out.append(MORE_LINES)
        return out.toString()
    }

    // East Asian wide and full width, roughly: the blocks a phrase's Chinese, kana, hangul and full-width forms are in
    @Suppress("MagicNumber")
    private fun wide(c: Int) = c in 0x1100..0x115F || c in 0x2E80..0xA4CF || c in 0xAC00..0xD7A3 ||
        c in 0xF900..0xFAFF || c in 0xFE30..0xFE4F || c in 0xFF00..0xFF60 || c in 0xFFE0..0xFFE6 ||
        c in 0x1F300..0x1F64F || c in 0x1F900..0x1F9FF || c in 0x20000..0x3FFFD
}
