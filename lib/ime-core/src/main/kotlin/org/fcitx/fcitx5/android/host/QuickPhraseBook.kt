/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxUtils

/** A user's quick phrase: [key] ASCII ([QuickPhraseFiles.validKey]); [folder] only sorts the settings page, never the search. */
data class QuickPhraseEntry(val key: String, val content: String, val note: String = "", val folder: String = "") {
    /** The same entry to the user: key case and folder aside (a move is no new entry). */
    fun sameAs(other: QuickPhraseEntry): Boolean = content == other.content && note == other.note && folded(key) == folded(other.key)

    internal val identity: Triple<String, String, String> get() = Triple(folded(key), note, content)

    /** Out of any folder: what `mine.used` keys its time by, and what finds it again after a move. */
    val unfiled: QuickPhraseEntry get() = if (folder.isEmpty()) this else copy(folder = "")

    /** No key: an old Chinese-first key moved into [note] on read ([QuickPhraseFiles.split]); listed with nothing typed. */
    val keyless: Boolean get() = key.isEmpty()

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

data class QuickPhraseMine(val entries: List<QuickPhraseEntry>, val folders: List<String>) {
    companion object {
        val EMPTY = QuickPhraseMine(emptyList(), emptyList())
    }
}

/**
 * The user's entries ([mine], `mine.mb`) in order of last use ([used], kept in `mine.used` so the keyboard
 * never writes a file the settings page writes), then the app's tables ([builtIn]) in table order.
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
        compareByDescending<IndexedValue<QuickPhraseEntry>> { times[it.value.unfiled] ?: 0L }.thenBy { it.index },
    ).map { it.value }

    val mine: List<QuickPhraseEntry> get() = ordered

    /** One not in the book is left alone. */
    @Synchronized
    fun use(entry: QuickPhraseEntry, at: Long) {
        if (entry !in entries) return
        times[entry.unfiled] = at
        ordered = order()
    }

    /** Entries whose key starts with [typed] (each place a set of chars, [QuickPhraseKeys]); nothing typed finds nothing. */
    fun find(typed: List<Set<Int>>, withMine: Boolean = true): List<Found> {
        if (typed.isEmpty()) return emptyList()
        val lowered = typed.map { set -> set.mapTo(HashSet()) { Character.toLowerCase(it) } }
        val out = ArrayList<Found>()
        if (withMine) mine.filter { starts(it.key, lowered, ignoreCase = true) }.mapTo(out) { Found.of(it) }
        builtInFound(typed).mapTo(out) { Found(it.first, it.second, false) }
        return out
    }

    // one char a place (26 keys, a hardware keyboard): the table's prefix walk; else every entry tried
    private fun builtInFound(typed: List<Set<Int>>): List<Pair<String, String>> {
        if (typed.any { it.size != 1 }) return builtIn.entries().filter { starts(it.first, typed, ignoreCase = false) }.toList()
        val prefix = StringBuilder()
        typed.forEach { prefix.appendCodePoint(it.single()) }
        return builtIn.populate(prefix.toString())
    }

    data class Found(val key: String, val content: String, val mine: Boolean, val note: String = "", val folder: String = "") {
        val entry get() = QuickPhraseEntry(key, content, note, folder)

        companion object {
            fun of(entry: QuickPhraseEntry) = Found(entry.key, entry.content, true, entry.note, entry.folder)
        }
    }

    companion object {
        val EMPTY = QuickPhraseBook(QuickPhraseTable.EMPTY)

        const val DIR = "data/quickphrase.d"
        const val MINE = "mine.mb"
        const val USED = "mine.used"

        /** Quick input takes at most 30 characters ([QuickPhrase]). */
        const val MAX_KEY = 30

        /** In code points: an old key's 30 moved into it fit. */
        const val MAX_NOTE = 30

        const val MAX_FOLDER = 20

        /** In UTF-16 units; a longer clipboard text is refused before the page opens (it would also bloat the Intent). */
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

        /** Reads only the user's `mine.mb`, `mine.used` and `<name>.mb.disable`, so a built-in table is never hidden by name. */
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
 * The user's quick phrase files. `mine.mb` is fcitx's `.mb` (`key content`, content escaped), so fcitx5 on a
 * computer and older builds read it, and the export is the same file:
 *
 * ```
 * #youmo-quickphrase-v2
 * #wz｜百度 www.baidu.com
 * ｜地址 "北京市海淀区\n3 号楼"
 * 【目录】网址
 * gh https://github.com
 * ```
 *
 * [HEADER] has no whitespace, so fcitx skips it. A key ends at its first ASCII whitespace, so notes' and
 * folders' spaces are written as no-break spaces. `mine.used` is [USED_HEADER], then `millis<TAB>key+note<TAB>content` lines.
 */
object QuickPhraseFiles {

    const val HEADER = "#youmo-quickphrase-v2"

    const val USED_HEADER = "#youmo-quickphrase-used-v2"

    const val NOTE_MARK = '｜'

    const val FOLDER_MARK = "【目录】"

    private const val NBSP = ' '

    /** The page's shelf names in every language the app has: a file moves between phones as it is. */
    val RESERVED_FOLDERS = setOf("全部", "未分类", "未分類", "All", "No folder", "待补关键词", "待補關鍵詞", "Need a key")

    fun reservedFolder(name: String): Boolean = RESERVED_FOLDERS.any { it.equals(name, ignoreCase = true) }
    private const val FIRST_KEY_CHAR = 0x21
    private const val LAST_KEY_CHAR = 0x7E

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** Visible ASCII: what the keyboard types into quick input. */
    fun keyChar(c: Char): Boolean = c.code in FIRST_KEY_CHAR..LAST_KEY_CHAR

    /**
     * The key up to its first non-[keyChar], the rest the note (one leading [NOTE_MARK] dropped). The same rule reads
     * version 1 keys with notes typed in (`#wz百度`) and v2 lines an old build rewrote without the header.
     * A lone bar is the note itself (0.5.x took `｜` as a key; [joined] writes it `｜｜`).
     */
    fun split(joined: String): Pair<String, String> {
        val at = joined.indexOfFirst { !keyChar(it) }.let { if (it < 0) joined.length else it }
        val rest = joined.substring(at)
        return joined.substring(0, at) to unspaced(if (rest.length > 1) rest.removePrefix(NOTE_MARK.toString()) else rest)
    }

    /** Each whitespace a space, ends trimmed: the file's no-break spaces read back so. */
    fun note(raw: String): String =
        String(CharArray(raw.length) { raw[it].let { c -> if (c.isWhitespace() || c == NBSP) ' ' else c } }).trim()

    fun joined(entry: QuickPhraseEntry): String =
        if (entry.note.isEmpty()) entry.key else entry.key + NOTE_MARK + spaced(entry.note)

    private fun spaced(text: String) = String(CharArray(text.length) { text[it].let { c -> if (c.code < 0x80 && c.isWhitespace()) NBSP else c } })

    private fun unspaced(text: String) = text.replace(NBSP, ' ')

    class Parsed(val entries: List<QuickPhraseEntry>, val folders: List<String>, val v2: Boolean)

    /** Lines fcitx skips are left out, but for [HEADER] and folders; a UTF-8 BOM (Notepad) is not part of the first key. */
    fun parse(bytes: ByteArray): Parsed {
        val text = if (bytes.size >= BOM.size && (BOM.indices).all { bytes[it] == BOM[it] }) bytes.copyOfRange(BOM.size, bytes.size) else bytes
        val lines = ArrayList<Pair<String, Pair<String, String>>>()
        val folders = LinkedHashSet<String>()
        var v2 = false
        var folder = ""
        QuickPhraseTable.parse(text, bare = { line ->
            when {
                line == HEADER -> v2 = true
                // a name that cannot be one (a file made elsewhere): its entries in none
                line.startsWith(FOLDER_MARK) -> {
                    folder = unspaced(line.substring(FOLDER_MARK.length)).trim().takeIf(::validFolder).orEmpty()
                    if (folder.isNotEmpty()) folders += folder
                }
            }
        }) { lines += folder to it }
        // a key field with no key char is a note alone, kept keyless; blank too, nothing to write it back by
        val entries = lines.mapNotNull { (f, line) ->
            val (key, note) = split(line.first)
            QuickPhraseEntry(key, line.second, note, f).takeUnless { key.isEmpty() && note.isBlank() }
        }
        return Parsed(entries, folders.toList(), v2)
    }

    /** [parse], deduplicated, over-long ones dropped, grouped by folder; what is left out goes at the next write. */
    fun readMine(bytes: ByteArray): QuickPhraseMine {
        val parsed = parse(bytes)
        return grouped(distinct(parsed.entries.filter { it.content.length <= QuickPhraseBook.MAX_CONTENT }), parsed.folders)
    }

    fun parseMine(bytes: ByteArray): List<QuickPhraseEntry> = readMine(bytes).entries

    fun distinct(entries: List<QuickPhraseEntry>): List<QuickPhraseEntry> = entries.distinctBy { it.identity }

    /** As the file keeps them: no folder first, then [folders] in order; an unlisted folder after them. */
    fun grouped(entries: List<QuickPhraseEntry>, folders: List<String>): QuickPhraseMine {
        val all = (folders + entries.map { it.folder }).filter { it.isNotEmpty() }.distinct()
        val rank = all.withIndex().associate { (i, f) -> f to i + 1 }
        return QuickPhraseMine(entries.sortedBy { rank[it.folder] ?: 0 }, all)
    }

    fun formatMine(entries: List<QuickPhraseEntry>, folders: List<String> = emptyList()): String {
        val mine = grouped(entries, folders)
        val byFolder = mine.entries.groupBy { it.folder }
        val out = StringBuilder(HEADER).append('\n')
        byFolder[""]?.forEach { line(out, it) }
        for (f in mine.folders) {
            out.append(FOLDER_MARK).append(spaced(f)).append('\n')
            byFolder[f]?.forEach { line(out, it) }
        }
        return out.toString()
    }

    private fun line(out: StringBuilder, e: QuickPhraseEntry) {
        out.append(joined(e)).append(' ').append(FcitxUtils.escapeForValue(e.content)).append('\n')
    }

    fun parseUsed(text: String): Map<QuickPhraseEntry, Long> {
        val out = HashMap<QuickPhraseEntry, Long>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t', limit = 3).takeIf { it.size == 3 }
            val at = parts?.get(0)?.toLongOrNull()
            val content = parts?.get(2)?.let(FcitxUtils::unescapeForValue)
            if (at != null && content != null) {
                val (key, note) = split(parts[1])
                if (key.isNotEmpty() || note.isNotBlank()) out[QuickPhraseEntry(key, content, note)] = at
            }
        }
        return out
    }

    /** Only the times of [entries]: one changed or deleted goes. */
    fun formatUsed(used: Map<QuickPhraseEntry, Long>, entries: Collection<QuickPhraseEntry>): String {
        val kept = entries.mapTo(HashSet()) { it.unfiled }
        return USED_HEADER + "\n" + used.entries.filter { it.key in kept }.sortedBy { it.value }
            .joinToString("") { "${it.value}\t${joined(it.key)}\t${FcitxUtils.escapeForValue(it.key.content)}\n" }
    }

    fun validKey(key: String): Boolean = key.isNotEmpty() && key.length <= QuickPhraseBook.MAX_KEY && key.all(::keyChar)

    /** At most [QuickPhraseBook.MAX_NOTE] and already as [note] keeps it, so it reads back as written. */
    fun validNote(note: String): Boolean = note.codePointCount(0, note.length) <= QuickPhraseBook.MAX_NOTE && note == note(note)

    /** Not blank, at most [QuickPhraseBook.MAX_FOLDER], as [note] keeps it, and not reserved. */
    fun validFolder(name: String): Boolean =
        name.isNotEmpty() && name == note(name) && name.codePointCount(0, name.length) <= QuickPhraseBook.MAX_FOLDER &&
            !reservedFolder(name)

    /** [merge]'s result: how many lines it skipped, and how many keyless ones it added. */
    data class Merged(
        val entries: List<QuickPhraseEntry>,
        val duplicates: Int,
        val tooLong: Int,
        val badKey: Int,
        val folders: List<String> = emptyList(),
        val keyless: Int = 0,
    )

    /** [lines] added after [mine] when valid and not there already ([QuickPhraseEntry.sameAs]); same-named folders merge. */
    fun merge(
        mine: List<QuickPhraseEntry>,
        lines: List<QuickPhraseEntry>,
        folders: List<String> = emptyList(),
        newFolders: List<String> = emptyList(),
    ): Merged {
        val out = ArrayList(mine)
        val seen = mine.mapTo(HashSet()) { it.identity }
        var duplicates = 0
        var tooLong = 0
        var badKey = 0
        var keyless = 0
        for (line in lines) {
            val e = line.copy(note = note(line.note))
            when {
                // only a quoted "" gives no content: no line at all. A picture parse already dropped (no value, not UTF-8)
                e.content.isEmpty() -> {}
                !(e.keyless || validKey(e.key)) || !validNote(e.note) || (e.keyless && e.note.isEmpty()) -> badKey++
                e.content.length > QuickPhraseBook.MAX_CONTENT -> tooLong++
                !seen.add(e.identity) -> duplicates++
                else -> {
                    out += e
                    if (e.keyless) keyless++
                }
            }
        }
        val all = (folders + newFolders + out.map { it.folder }).filter { it.isNotEmpty() }.distinct()
        return Merged(out, duplicates, tooLong, badKey, all, keyless)
    }
}

object QuickPhraseExcerpt {
    const val ELLIPSIS = "…"
    const val MORE_LINES = "⏎"

    /** The first line cut to [width] columns (a wide char two), with [ELLIPSIS], and [MORE_LINES] when more follow. */
    fun of(content: String, width: Int = 24): String {
        val newline = content.indexOf('\n')
        // a CRLF file's line ends at its \r too
        val first = (if (newline < 0) content else content.substring(0, newline)).removeSuffix("\r")
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

    // East Asian wide and full width, roughly
    @Suppress("MagicNumber")
    private fun wide(c: Int) = c in 0x1100..0x115F || c in 0x2E80..0xA4CF || c in 0xAC00..0xD7A3 ||
        c in 0xF900..0xFAFF || c in 0xFE30..0xFE4F || c in 0xFF00..0xFF60 || c in 0xFFE0..0xFFE6 ||
        c in 0x1F300..0x1F64F || c in 0x1F900..0x1F9FF || c in 0x20000..0x3FFFD
}
