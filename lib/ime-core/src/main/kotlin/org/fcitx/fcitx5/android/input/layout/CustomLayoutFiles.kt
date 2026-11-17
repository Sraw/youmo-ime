/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import java.io.InputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The user's own layouts as files (061-custom-layout §4.1): `files/layouts/<id>.xml`, the name only ever made from an id
 * the reader takes (letters, digits and single dashes, [LayoutCheck.MAX_ID] at most: nothing to climb out of the folder
 * with), each no bigger than a theme pack's layout ([MAX_BYTES]), at most [MAX_LAYOUTS] of them, as theme packs. A file
 * picked or restored is untrusted: read no further than the limit, and only as UTF-8.
 */
object CustomLayoutFiles {
    /** What a layout's file is stored with: its id and this. */
    const val SUFFIX = ".xml"

    /** What an exported layout is offered as: `<名字>.layout` (§2.1). */
    const val EXTENSION = "layout"

    /** The most bytes of a layout read (§2.6): a bigger file is refused unread. */
    const val MAX_BYTES: Long = PackLayouts.MAX_BYTES

    /** The most layouts of one's own on a phone (§2.6), as many as theme packs. */
    const val MAX_LAYOUTS = ThemeAssetLimits.MAX_PACKS

    /** The record of the theme packs whose layouts were put in the list (§11: `file@stamp` a line), in the folder. */
    const val CONVERTED = ".converted"

    /**
     * The most of [CONVERTED] read, its lines and each line's length: written by the app, but restored from a backup. No
     * more is written than is read ([convertedLines] stops at the bytes too), or the next restore would not read it.
     */
    const val MAX_CONVERTED_BYTES = 64L * 1024
    const val MAX_CONVERTED_LINE = 256
    const val MAX_CONVERTED_LINES = (MAX_CONVERTED_BYTES / (MAX_CONVERTED_LINE + 1)).toInt()

    // LayoutReader's IDENT, which a <keyboard id> must match to read at all
    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /** Whether [id] is one a layout of one's own may have, so a file may be named by it. */
    fun validId(id: String): Boolean = id.length <= LayoutCheck.MAX_ID && ID.matches(id)

    /** The file layout [id] is stored in; only of a [validId]. */
    fun fileName(id: String): String {
        require(validId(id)) { "not a layout id: $id" }
        return id + SUFFIX
    }

    /** The id of the layout stored in [fileName], null where it is no layout's file (a temporary one, [CONVERTED], one put there by hand). */
    fun idOf(fileName: String): String? =
        fileName.takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX)?.takeIf(::validId)

    private const val NOT_IN_NAMES = "/\\:"

    /** The name layout [name] is exported under: `<name>.layout`, what is not shown left out, what no file name may hold made `_`. */
    fun exportName(name: String): String {
        val safe = LayoutCheck.plain(name).map { if (it in NOT_IN_NAMES) '_' else it }.joinToString("").trim().trim('.')
        return safe.ifEmpty { "layout" } + "." + EXTENSION
    }

    /** A file read for a layout: its [Text], or why it is not one. */
    sealed interface Read {
        class Text(val text: String) : Read

        /** More than [MAX_BYTES]: nothing past them was read. */
        data object TooBig : Read

        /** Not UTF-8. */
        data object NotText : Read
    }

    /** [input] read as a layout: at most [MAX_BYTES], and one more to tell a bigger one; the stream is left to the caller to close. */
    fun read(input: InputStream, max: Long = MAX_BYTES): Read {
        val bytes = readAtMost(input, max) ?: return Read.TooBig
        return decode(bytes)?.let(Read::Text) ?: Read.NotText
    }

    /** [input]'s bytes, null where there are more than [max] (one more read to know). */
    fun readAtMost(input: InputStream, max: Long): ByteArray? {
        val limit = max.toInt()
        val out = ByteArray(limit + 1)
        var n = 0
        while (n <= limit) {
            val r = input.read(out, n, limit + 1 - n)
            if (r < 0) break
            n += r
        }
        return if (n > limit) null else out.copyOf(n)
    }

    /** [bytes] as UTF-8, null where they are not (a picture picked, a file in another encoding). */
    fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * Whether a backup's file [fileName] of [bytes] is put in the folder by a restore (§4.5): a layout's file by its name,
     * UTF-8, no bigger than [MAX_BYTES], of the layout its name says (a file renamed in the zip is not one it holds); of a
     * family the list has a section for ([LayoutCheck.FAMILIES]: another would be shown nowhere, not to be deleted, and
     * counted), an id no board of the app's has nor a key name's prefix; and taken by the check a theme pack's made one is
     * held to ([LayoutCheck.check] not strict, §3.2: it reads, its `min-app` not past [appVersion], it keeps what its base
     * must be typed on), except that a number board whose pad lacks a key that pad's fields type (a `lacks pad=…`
     * finding) is refused, since that pad would be drawn without it. The rest of the strict check's rules are the 信息's
     * to tell, as for such a pack's.
     */
    fun restorable(fileName: String, bytes: ByteArray, sources: LayoutCheck.Sources, appVersion: String): Boolean {
        val id = idOf(fileName) ?: return false
        if (bytes.size > MAX_BYTES) return false
        val text = decode(bytes) ?: return false
        // a backup is untrusted: what throws reading or checking it is one not restored
        val layout = runCatching { LayoutImport.layout(text) }.getOrNull() ?: return false
        if (layout.id != id || layout.family !in LayoutCheck.FAMILIES) return false
        val report = runCatching { LayoutCheck.check(text, sources, appVersion, strict = false) }.getOrNull() ?: return false
        return report.ok && report.warnings.none { it.key in NOT_RESTORED || LayoutCheck.padLacking(it) != null }
    }

    // what the check not strict only tells of, a restore refuses (the family's is above)
    private val NOT_RESTORED = setOf("id-built-in", "id-prefix", "family")

    /**
     * Which of a backup's layout files [fileNames] a restore looks at: layouts' names, the first [MAX_LAYOUTS] of them by
     * name ([restorable] and the room on the phone decide which it takes).
     */
    fun restoredNames(fileNames: Collection<String>): List<String> =
        fileNames.filter { idOf(it) != null }.sorted().take(MAX_LAYOUTS)

    /**
     * [text] of a [CONVERTED] record as its lines: blank ones and ones too long left out, each of a key once ([keyOf]:
     * a pack's, a layout's) where it was first, as it was last (a pack imported again its new stamp, the old dropped;
     * a layout written again its new digest); no more than [MAX_CONVERTED_LINES], nor than [MAX_CONVERTED_BYTES]
     * written a line each, the newest kept ([CURRENT] first, always).
     */
    fun convertedLines(text: String): List<String> {
        val last = LinkedHashMap<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_CONVERTED_LINE }.forEach { last[keyOf(it)] = it }
        val current = listOfNotNull(last.remove(CURRENT))
        var bytes = current.sumOf { it.encodeToByteArray().size + 1L }
        val kept = ArrayDeque<String>()
        for (line in last.values.reversed()) {
            val size = line.encodeToByteArray().size + 1L
            if (current.size + kept.size >= MAX_CONVERTED_LINES || bytes + size > MAX_CONVERTED_BYTES) break
            bytes += size
            kept.addFirst(line)
        }
        return current + kept
    }

    // what a line is of: [CURRENT] itself, a layout's (`=<id>`), a pack's (its name before the `@` of its stamp; one
    // whose name begins with `=` too, L7 re-check NIT-1)
    private fun keyOf(line: String): String = when {
        line == CURRENT -> CURRENT
        else -> madeOf(line)?.let { MADE + it.id } ?: ("@" + line.substringBeforeLast('@'))
    }

    // [line] as a layout's ([Made.line]): its four fields, the first an id; null for any other (a pack's whose name
    // begins with `=`)
    private fun madeOf(line: String): Made? {
        if (!line.startsWith(MADE)) return null
        val parts = line.removePrefix(MADE).split(' ', limit = MADE_PARTS)
        return parts.takeIf { p -> p.size == MADE_PARTS && validId(p[0]) && p.all { it.isNotEmpty() } }?.let { Made(it[0], it[1], it[2], it[3]) }
    }

    /**
     * The [CONVERTED] record after a restore (§4.5, §11): the phone's lines and the backup's ([convertedLines]: the
     * backup's where both have one of a key). A pack either converted is not converted again: its layouts are in the
     * folder (the backup's replacing the phone's of their ids).
     */
    fun mergeConverted(phone: String?, backup: String?): String =
        convertedLines(listOfNotNull(phone, backup).joinToString("\n")).joinToString("") { "$it\n" }

    /**
     * The line a record written since 0.6.1's conversion begins with (its upgrade's first start writes it, a pack or
     * none): a backup's record without it is of an app whose packs' boards stood for the app's (0.6.0, a 0.6.1 beta).
     */
    const val CURRENT = "#l7"

    // a layout's line: `=<id> <board> <digest> <pack file>`
    private const val MADE = "="

    /** A layout a pack's conversion wrote: its [id], of pack [file]'s [board], its text's [digest] as written. */
    data class Made(val id: String, val board: String, val digest: String, val file: String) {
        /** As [CONVERTED] holds it. */
        val line: String get() = "$MADE$id $board $digest $file"
    }

    /** What a [CONVERTED] record holds: the packs converted ([packs], `file@stamp`), the layouts made ([made]), whether [CURRENT]. */
    class Record(val packs: List<String>, val made: List<Made>, val current: Boolean) {
        /** The packs converted by name (a pack restored is stamped with the copy's time, not the backup's). */
        val packNames: Set<String> get() = packs.mapTo(HashSet()) { it.substringBeforeLast('@') }

        /** The layout pack [file]'s [board] was last written to; null where none was. */
        fun madeOf(file: String, board: String): Made? = made.lastOrNull { it.file == file && it.board == board }

        /** The layouts pack [file]'s conversions wrote, a board each. */
        fun madeBy(file: String): List<Made> = made.filter { it.file == file }
    }

    /** [text] of a [CONVERTED] record read ([convertedLines]); a line of a layout that does not read left out. */
    fun record(text: String?): Record {
        val lines = text?.let(::convertedLines).orEmpty()
        val made = lines.mapNotNull(::madeOf)
        return Record(lines.filter { it != CURRENT && madeOf(it) == null && '@' in it }, made, CURRENT in lines)
    }

    private const val MADE_PARTS = 4

    /** What a layout's text [bytes] is told by in [Made.digest]: SHA-256's first 16 hex digits. */
    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(DIGEST_BYTES).joinToString("") { "%02x".format(it.toInt() and BYTE) }

    private const val DIGEST_BYTES = 8
    private const val BYTE = 0xFF

    /** How a pack's board is put in the list ([target]): saved under a free id, over the one it made before, or left as the user has it. */
    enum class Put { NEW, REPLACE, KEEP }

    /** Where a pack's board goes ([target]): [id], how ([put]). */
    data class Target(val id: String, val put: Put)

    /**
     * Where pack [file]'s [board] is listed (L7 review S2: a layout of the id is never written over unless this pack made
     * it as it is): over the layout the pack's last conversion made of it ([Record.madeOf]) where its file is as written
     * ([digestOf] the same: an update, said as it is imported); that one kept as it is where the same import of the pack
     * is listed again ([same]: a start ended in the middle, tried again) and the user changed it since; else a new one,
     * [base] or the first free after it ([freeId]: the user's own, one they changed, another pack's of the same hash
     * kept); a file already what the board makes under its id ([writes]: the digest of that, null where it is not made)
     * is no other's but written over, as it is (a conversion killed between its save and its record, tried again: not a
     * second copy at `-2`, L7 re-check SHOULD-1). [digestOf]: an id's file's [digest], null where there is none (one
     * that cannot be read: not null).
     */
    fun target(
        record: Record,
        file: String,
        board: String,
        base: String,
        same: Boolean,
        writes: (String) -> String? = { null },
        digestOf: (String) -> String?,
    ): Target {
        val prev = record.madeOf(file, board)
        if (prev != null) {
            val now = digestOf(prev.id)
            if (now == prev.digest) return Target(prev.id, Put.REPLACE)
            if (now != null && same) return Target(prev.id, Put.KEEP)
        }
        val id = freeId(base) { id -> digestOf(id)?.let { it != writes(id) } ?: false }
        return Target(id, if (digestOf(id) != null) Put.REPLACE else Put.NEW)
    }

    /**
     * The id a pack's [board] is listed under: [wanted] (the layout its conversion made, `t<hash>-<board>`), else the
     * first of `-2`, `-3`… no file of [taken] has (another's, the user's own or changed: kept); within an id's length.
     */
    fun freeId(wanted: String, taken: (String) -> Boolean): String =
        (sequenceOf(wanted) + generateSequence(2) { it + 1 }.map { n -> wanted.take(LayoutCheck.MAX_ID - 1 - n.toString().length) + "-" + n })
            .first { validId(it) && !taken(it) }
}
