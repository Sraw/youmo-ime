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
 * The user's layouts as `files/layouts/<id>.xml`, the name only ever made from a [validId] (nothing to climb
 * out of the folder with). Picked or restored files are untrusted: read no further than [MAX_BYTES], UTF-8 only.
 */
object CustomLayoutFiles {
    const val SUFFIX = ".xml"

    const val EXTENSION = "layout"

    /** A bigger file is refused unread. */
    const val MAX_BYTES: Long = PackLayouts.MAX_BYTES

    /** As many as theme packs. */
    const val MAX_LAYOUTS = ThemeAssetLimits.MAX_PACKS

    /** The theme packs whose layouts were put in the list (`file@stamp` a line). */
    const val CONVERTED = ".converted"

    /** Restored from a backup, so bounded; no more is written than is read, or the next restore would not read it. */
    const val MAX_CONVERTED_BYTES = 64L * 1024
    const val MAX_CONVERTED_LINE = 256
    const val MAX_CONVERTED_LINES = (MAX_CONVERTED_BYTES / (MAX_CONVERTED_LINE + 1)).toInt()

    // LayoutReader's IDENT, which a <keyboard id> must match to read at all
    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    fun validId(id: String): Boolean = id.length <= LayoutCheck.MAX_ID && ID.matches(id)

    /** Only of a [validId]. */
    fun fileName(id: String): String {
        require(validId(id)) { "not a layout id: $id" }
        return id + SUFFIX
    }

    /** Null for a file that is no layout's (a temporary one, [CONVERTED], one put there by hand). */
    fun idOf(fileName: String): String? =
        fileName.takeIf { it.endsWith(SUFFIX) }?.removeSuffix(SUFFIX)?.takeIf(::validId)

    private const val NOT_IN_NAMES = "/\\:"

    /** What is not shown left out, what no file name may hold made `_`. */
    fun exportName(name: String): String {
        val safe = LayoutCheck.plain(name).map { if (it in NOT_IN_NAMES) '_' else it }.joinToString("").trim().trim('.')
        return safe.ifEmpty { "layout" } + "." + EXTENSION
    }

    sealed interface Read {
        class Text(val text: String) : Read

        /** Nothing past them was read. */
        data object TooBig : Read

        data object NotText : Read
    }

    /** One byte more is read to tell a bigger one; the caller closes the stream. */
    fun read(input: InputStream, max: Long = MAX_BYTES): Read {
        val bytes = readAtMost(input, max) ?: return Read.TooBig
        return decode(bytes)?.let(Read::Text) ?: Read.NotText
    }

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

    /** Null where not UTF-8 (a picture picked, another encoding). */
    fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * Whether a restore puts a backup's [fileName] in: a layout of the id its name says, of a listed family, an id no
     * built-in board or key name prefix has, passing the non-strict check (a board lacking keys is restored, as imported).
     */
    fun restorable(fileName: String, bytes: ByteArray, sources: LayoutCheck.Sources, appVersion: String): Boolean {
        val id = idOf(fileName) ?: return false
        if (bytes.size > MAX_BYTES) return false
        val text = decode(bytes) ?: return false
        // a backup is untrusted: what throws reading or checking it is one not restored
        val layout = runCatching { LayoutImport.layout(text) }.getOrNull() ?: return false
        if (layout.id != id || layout.family !in LayoutCheck.FAMILIES) return false
        val report = runCatching { LayoutCheck.check(text, sources, appVersion, strict = false) }.getOrNull() ?: return false
        return report.ok && report.warnings.none { it.key in NOT_RESTORED }
    }

    // what the check not strict only tells of, a restore refuses (the family's is above)
    private val NOT_RESTORED = setOf("id-built-in", "id-prefix", "family")

    /** The first [MAX_LAYOUTS] by name; [restorable] and the room on the phone decide which are taken. */
    fun restoredNames(fileNames: Collection<String>): List<String> =
        fileNames.filter { idOf(it) != null }.sorted().take(MAX_LAYOUTS)

    /**
     * Lines of a [CONVERTED] record: blank and over-long ones dropped, each key once at its last place, within
     * [MAX_CONVERTED_LINES] and [MAX_CONVERTED_BYTES], the newest kept ([CURRENT] first, always).
     */
    fun convertedLines(text: String): List<String> {
        val last = LinkedHashMap<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_CONVERTED_LINE }.forEach {
            // moved to the end, not left where the key first stood
            val key = keyOf(it)
            last.remove(key)
            last[key] = it
        }
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

    // [CURRENT] itself, a layout's (`=<id>`), or a pack's (its name before the `@`, even one beginning with `=`)
    private fun keyOf(line: String): String = when {
        line == CURRENT -> CURRENT
        else -> madeOf(line)?.let { MADE + it.id } ?: ("@" + line.substringBeforeLast('@'))
    }

    // null for any other line (a pack's whose name begins with `=`)
    private fun madeOf(line: String): Made? {
        if (!line.startsWith(MADE)) return null
        val parts = line.removePrefix(MADE).split(' ', limit = MADE_PARTS)
        return parts.takeIf { p -> p.size == MADE_PARTS && validId(p[0]) && p.all { it.isNotEmpty() } }?.let { Made(it[0], it[1], it[2], it[3]) }
    }

    /** The phone's lines and the backup's, the backup's winning per key: a pack converted on either is not converted again. */
    fun mergeConverted(phone: String?, backup: String?): String =
        convertedLines(listOfNotNull(phone, backup).joinToString("\n")).joinToString("") { "$it\n" }

    /** Begins records written since the 0.6.1 conversion; a backup's without it predates it. */
    const val CURRENT = "#l7"

    // a layout's line: `=<id> <board> <digest> <pack file>`
    private const val MADE = "="

    data class Made(val id: String, val board: String, val digest: String, val file: String) {
        val line: String get() = "$MADE$id $board $digest $file"
    }

    class Record(val packs: List<String>, val made: List<Made>, val current: Boolean) {
        /** By name: a restored pack is stamped with the copy's time, not the backup's. */
        val packNames: Set<String> get() = packs.mapTo(HashSet()) { it.substringBeforeLast('@') }

        fun madeOf(file: String, board: String): Made? = made.lastOrNull { it.file == file && it.board == board }
    }

    /** A line of a layout that does not read is left out. */
    fun record(text: String?): Record {
        val lines = text?.let(::convertedLines).orEmpty()
        val made = lines.mapNotNull(::madeOf)
        return Record(lines.filter { it != CURRENT && madeOf(it) == null && '@' in it }, made, CURRENT in lines)
    }

    private const val MADE_PARTS = 4

    /** SHA-256's first 16 hex digits. */
    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(DIGEST_BYTES).joinToString("") { "%02x".format(it.toInt() and BYTE) }

    private const val DIGEST_BYTES = 8
    private const val BYTE = 0xFF

    enum class Put { NEW, REPLACE, KEEP }

    data class Target(val id: String, val put: Put)

    /**
     * Where pack [file]'s [board] is listed: never over a layout unless this pack made it as it is. Over its last
     * conversion's when unchanged; kept when the same import is listed again and the user changed it; else a new id
     * ([freeId]). A file already what the board makes ([writes]) is written over (a conversion killed before its record).
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

    /** [wanted], else the first of `-2`, `-3`… [taken] lacks, within an id's length. */
    fun freeId(wanted: String, taken: (String) -> Boolean): String =
        (sequenceOf(wanted) + generateSequence(2) { it + 1 }.map { n -> wanted.take(LayoutCheck.MAX_ID - 1 - n.toString().length) + "-" + n })
            .first { validId(it) && !taken(it) }
}
