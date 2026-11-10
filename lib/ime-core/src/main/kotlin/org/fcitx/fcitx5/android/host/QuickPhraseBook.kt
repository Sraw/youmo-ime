/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxUtils

/**
 * One of the user's own quick phrases: [key] typed to find it (ASCII, [QuickPhraseFiles.validKey]),
 * [content] what it puts in, [note] shown beside it in the candidates (any text: `#wz` with 百度 and
 * with 新浪 for two sites), [folder] the one it is kept in on the settings page ("": none). Folders
 * sort the page alone: typing finds an entry whatever its folder.
 */
data class QuickPhraseEntry(val key: String, val content: String, val note: String = "", val folder: String = "") {
    /**
     * The same entry to the user: the key case aside, as typing finds it ([QuickPhraseBook.find]), the
     * note and content as they are; the folder aside, a move being no new entry. Two such would be
     * listed for the same keys with the same text and note.
     */
    fun sameAs(other: QuickPhraseEntry): Boolean = content == other.content && note == other.note && folded(key) == folded(other.key)

    internal val identity: Triple<String, String, String> get() = Triple(folded(key), note, content)

    /** Out of any folder: what its time of use is kept by (`mine.used`), and what finds it again after a move. */
    val unfiled: QuickPhraseEntry get() = if (folder.isEmpty()) this else copy(folder = "")

    /**
     * No key: an old one that was Chinese from its first character, moved whole into [note] when the
     * file was read ([QuickPhraseFiles.split]). Listed with nothing typed, found by no key, until the
     * user gives it one.
     */
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

/** The user's entries ([entries], grouped by folder as the file is) and their folders in order, empty ones too. */
data class QuickPhraseMine(val entries: List<QuickPhraseEntry>, val folders: List<String>) {
    companion object {
        val EMPTY = QuickPhraseMine(emptyList(), emptyList())
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
    // by each entry out of its folder, as mine.used keeps them
    private val times = HashMap(used)

    // the file's order, the tie-break between two never used
    private val entries = QuickPhraseFiles.distinct(mine)

    @Volatile
    private var ordered: List<QuickPhraseEntry> = order()

    private fun order() = entries.withIndex().sortedWith(
        compareByDescending<IndexedValue<QuickPhraseEntry>> { times[it.value.unfiled] ?: 0L }.thenBy { it.index },
    ).map { it.value }

    /** The user's entries, the last used first. */
    val mine: List<QuickPhraseEntry> get() = ordered

    /** [entry] used at [at]: it goes first. One not in the book is left alone. */
    @Synchronized
    fun use(entry: QuickPhraseEntry, at: Long) {
        if (entry !in entries) return
        times[entry.unfiled] = at
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
        if (withMine) mine.filter { starts(it.key, lowered, ignoreCase = true) }.mapTo(out) { Found.of(it) }
        builtInFound(typed).mapTo(out) { Found(it.first, it.second, false) }
        return out
    }

    // one character a place (26 keys, a hardware keyboard): the table's own prefix walk; else every entry tried
    private fun builtInFound(typed: List<Set<Int>>): List<Pair<String, String>> {
        if (typed.any { it.size != 1 }) return builtIn.entries().filter { starts(it.first, typed, ignoreCase = false) }.toList()
        val prefix = StringBuilder()
        typed.forEach { prefix.appendCodePoint(it.single()) }
        return builtIn.populate(prefix.toString())
    }

    /** A match: [mine] one of the user's entries (with its [note] and [folder]), else a built-in table's. */
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

        /** The longest key: quick input takes at most 30 characters ([QuickPhrase]). */
        const val MAX_KEY = 30

        /** The longest note, in code points: an old key's 30 moved into it fit. */
        const val MAX_NOTE = 30

        /** The longest folder name, in code points: its chip on the settings page. */
        const val MAX_FOLDER = 20

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
 * The user's quick phrase files (design 060-quick-input-folders). `mine.mb` is fcitx's `.mb`, a line
 * `key content` each, the content through fcitx's escaping (several lines as `"…\n…"`), so fcitx5 on
 * a computer and older builds read it, and the export is the same file:
 *
 * ```
 * #youmo-quickphrase-v2
 * #wz｜百度 www.baidu.com
 * ｜地址 "北京市海淀区\n3 号楼"
 * 【目录】网址
 * gh https://github.com
 * ```
 *
 * - [HEADER], the version: a line with no whitespace, which fcitx skips. A file without it is
 *   version 1, of the builds before notes, or a computer's; it reads by the same rule ([split]).
 * - A line's key is the entry's key, then [NOTE_MARK] and its note when it has one: the computer
 *   shows `#wz｜百度` beside the content, telling the two `#wz` apart.
 * - [FOLDER_MARK] and a name: the entries after it are in that folder, those before any in none.
 *   One with no entries is a line all the same, so a folder is kept empty.
 *
 * A note's and a folder's spaces are written as no-break spaces: a line's key ends at its first
 * ASCII whitespace. So a note keeps no no-break space or tab of its own: each is a space ([note]).
 * Builds before 0.6.0 skip a line whose key has one when they import it (their keys had no
 * whitespace), and keep it when they read their own `mine.mb`. `mine.used` is [USED_HEADER], then a line `millis<TAB>key and note<TAB>escaped
 * content` each, the key and note joined as in `mine.mb`; its times are by entry out of its folder.
 */
object QuickPhraseFiles {

    /** `mine.mb`'s first line from version 2 on (notes and folders). */
    const val HEADER = "#youmo-quickphrase-v2"

    /** `mine.used`'s first line from version 2 on. */
    const val USED_HEADER = "#youmo-quickphrase-used-v2"

    /** Between a line's key and the entry's note. */
    const val NOTE_MARK = '｜'

    /** A folder's line: this, then its name. */
    const val FOLDER_MARK = "【目录】"

    private const val NBSP = ' '

    /** What a folder cannot be named: the page's shelves of entries in none, and of all. */
    val RESERVED_FOLDERS = setOf("全部", "未分类", "未分類", "All", "No folder")
    private const val FIRST_KEY_CHAR = 0x21
    private const val LAST_KEY_CHAR = 0x7E

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** Whether [c] can be in a key: visible ASCII, what the keyboard types into quick input. */
    fun keyChar(c: Char): Boolean = c.code in FIRST_KEY_CHAR..LAST_KEY_CHAR

    /**
     * A line's key [joined] as an entry's key and note: the key up to its first character that cannot
     * be in one ([keyChar]), the rest the note, one [NOTE_MARK] at its start dropped and its no-break
     * spaces spaces again. One rule for both versions: a key of before (version 1) has its notes
     * typed into it, so `#wz百度` is `#wz` with 百度 and `地址` no key with 地址; and a version 2 file
     * a build before 0.6.0 wrote again (the version line gone, `#wz｜百度` kept) reads as it was meant,
     * not as 「｜百度」.
     */
    fun split(joined: String): Pair<String, String> {
        val at = joined.indexOfFirst { !keyChar(it) }.let { if (it < 0) joined.length else it }
        return joined.substring(0, at) to unspaced(joined.substring(at).removePrefix(NOTE_MARK.toString()))
    }

    /**
     * [raw] as a note is kept: each whitespace character (a tab, a no-break space, a line break) a
     * space, the ends trimmed. The file writes a note's spaces as no-break spaces, so this is what
     * reads back.
     */
    fun note(raw: String): String =
        String(CharArray(raw.length) { raw[it].let { c -> if (c.isWhitespace() || c == NBSP) ' ' else c } }).trim()

    /** [entry]'s key and note as a line's key ([split] of version 2 reads it back). */
    fun joined(entry: QuickPhraseEntry): String =
        if (entry.note.isEmpty()) entry.key else entry.key + NOTE_MARK + spaced(entry.note)

    private fun spaced(text: String) = String(CharArray(text.length) { text[it].let { c -> if (c.code < 0x80 && c.isWhitespace()) NBSP else c } })

    private fun unspaced(text: String) = text.replace(NBSP, ' ')

    /** What a `.mb` file holds: its entries in order, each in its folder, its folders in order, and whether it was [v2]. */
    class Parsed(val entries: List<QuickPhraseEntry>, val folders: List<String>, val v2: Boolean)

    /**
     * A `.mb` file's [bytes] as entries, lines fcitx skips left out but for [HEADER] and folders; a
     * UTF-8 BOM at its start (a file saved by Windows' Notepad) is not part of the first key.
     */
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
        // a line with neither key nor note (`｜ x`) has nothing to write it back by: no entry
        val entries = lines.mapNotNull { (f, line) ->
            val (key, note) = split(line.first)
            QuickPhraseEntry(key, line.second, note, f).takeUnless { key.isEmpty() && note.isEmpty() }
        }
        return Parsed(entries, folders.toList(), v2)
    }

    /** [parse]'s entries alone. */
    fun parseLines(bytes: ByteArray): List<QuickPhraseEntry> = parse(bytes).entries

    /**
     * The user's `mine.mb`: [parse], each entry once ([distinct]), none longer than
     * [QuickPhraseBook.MAX_CONTENT] (only a file made elsewhere has one), grouped by folder. What is
     * left out here is gone from the file at its next write.
     */
    fun readMine(bytes: ByteArray): QuickPhraseMine {
        val parsed = parse(bytes)
        return grouped(distinct(parsed.entries.filter { it.content.length <= QuickPhraseBook.MAX_CONTENT }), parsed.folders)
    }

    /** [readMine]'s entries alone. */
    fun parseMine(bytes: ByteArray): List<QuickPhraseEntry> = readMine(bytes).entries

    /** [entries] each once ([QuickPhraseEntry.sameAs]), the first of each kept. */
    fun distinct(entries: List<QuickPhraseEntry>): List<QuickPhraseEntry> = entries.distinctBy { it.identity }

    /**
     * [entries] grouped as the file keeps them: those in no folder, then each of [folders]' in its
     * order, each group in the order given; a folder of an entry missing from [folders] added after them.
     */
    fun grouped(entries: List<QuickPhraseEntry>, folders: List<String>): QuickPhraseMine {
        val all = (folders + entries.map { it.folder }).filter { it.isNotEmpty() }.distinct()
        val rank = all.withIndex().associate { (i, f) -> f to i + 1 }
        return QuickPhraseMine(entries.sortedBy { rank[it.folder] ?: 0 }, all)
    }

    /** [entries] and [folders] as a `.mb` file [readMine] reads back, grouped as [grouped] puts them. */
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

    /** `mine.used`'s times, by entry out of its folder; a line that does not read is skipped. */
    fun parseUsed(text: String): Map<QuickPhraseEntry, Long> {
        val out = HashMap<QuickPhraseEntry, Long>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t', limit = 3).takeIf { it.size == 3 }
            val at = parts?.get(0)?.toLongOrNull()
            val content = parts?.get(2)?.let(FcitxUtils::unescapeForValue)
            if (at != null && content != null) {
                val (key, note) = split(parts[1])
                if (key.isNotEmpty() || note.isNotEmpty()) out[QuickPhraseEntry(key, content, note)] = at
            }
        }
        return out
    }

    /** [used] as `mine.used`, only the times of [entries] (one changed or deleted goes). */
    fun formatUsed(used: Map<QuickPhraseEntry, Long>, entries: Collection<QuickPhraseEntry>): String {
        val kept = entries.mapTo(HashSet()) { it.unfiled }
        return USED_HEADER + "\n" + used.entries.filter { it.key in kept }.sortedBy { it.value }
            .joinToString("") { "${it.value}\t${joined(it.key)}\t${FcitxUtils.escapeForValue(it.key.content)}\n" }
    }

    /** Whether [key] can be a key: 1 to [QuickPhraseBook.MAX_KEY] visible ASCII characters ([keyChar]), no space. */
    fun validKey(key: String): Boolean = key.isNotEmpty() && key.length <= QuickPhraseBook.MAX_KEY && key.all(::keyChar)

    /**
     * Whether [note] can be a note: at most [QuickPhraseBook.MAX_NOTE] characters, as [note] keeps it
     * (no whitespace but spaces, none at the ends), so it reads back as it is written.
     */
    fun validNote(note: String): Boolean = note.codePointCount(0, note.length) <= QuickPhraseBook.MAX_NOTE && note == note(note)

    /**
     * Whether [name] can be a folder's: not blank, at most [QuickPhraseBook.MAX_FOLDER] characters, as
     * [note] keeps it (reads back as it is written), and not one of [RESERVED_FOLDERS].
     */
    fun validFolder(name: String): Boolean =
        name.isNotEmpty() && name == note(name) && name.codePointCount(0, name.length) <= QuickPhraseBook.MAX_FOLDER &&
            name !in RESERVED_FOLDERS

    /**
     * What [merge] made: [entries] after it, the [folders], and how many lines it skipped as already
     * there, too long, or with a key that cannot be one; [keyless] of those added had a key Chinese
     * from its start, now their note ([QuickPhraseEntry.keyless]).
     */
    data class Merged(
        val entries: List<QuickPhraseEntry>,
        val duplicates: Int,
        val tooLong: Int,
        val badKey: Int,
        val folders: List<String> = emptyList(),
        val keyless: Int = 0,
    )

    /**
     * [lines] of a `.mb` file ([parse]) added after [mine]: the ones whose key is good ([validKey], or
     * none, the old key in the note), whose note is ([validNote]), whose content is not longer than
     * [QuickPhraseBook.MAX_CONTENT], and that are not there already ([QuickPhraseEntry.sameAs]); their
     * folders after [folders], one of the same name the same folder.
     */
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
        for (e in lines) {
            when {
                // a line of no content (only a quoted "" gives one) is no line at all, its key whatever
                // it is. A picture picked for the file never gets here: parse drops its lines (no
                // value after a space, or not UTF-8), so it is said as not a 快速输入 file
                e.content.isEmpty() -> {}
                // no key and no note: nothing to write it back by
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

    // East Asian wide and full width, roughly: the blocks a phrase's Chinese, kana, hangul and full-width forms are in
    @Suppress("MagicNumber")
    private fun wide(c: Int) = c in 0x1100..0x115F || c in 0x2E80..0xA4CF || c in 0xAC00..0xD7A3 ||
        c in 0xF900..0xFAFF || c in 0xFE30..0xFE4F || c in 0xFF00..0xFF60 || c in 0xFFE0..0xFFE6 ||
        c in 0x1F300..0x1F64F || c in 0x1F900..0x1F9FF || c in 0x20000..0x3FFFD
}
