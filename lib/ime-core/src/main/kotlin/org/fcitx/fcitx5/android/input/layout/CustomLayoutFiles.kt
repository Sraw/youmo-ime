/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import java.io.InputStream
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
     * must be typed on). The rest of the strict check's rules are the 信息's to tell, as for such a pack's.
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

    /**
     * Which of a backup's layout files [fileNames] a restore looks at: layouts' names, the first [MAX_LAYOUTS] of them by
     * name ([restorable] and the room on the phone decide which it takes).
     */
    fun restoredNames(fileNames: Collection<String>): List<String> =
        fileNames.filter { idOf(it) != null }.sorted().take(MAX_LAYOUTS)

    /**
     * [text] of a [CONVERTED] record as its lines: blank ones and ones too long left out, no more than
     * [MAX_CONVERTED_LINES], nor than [MAX_CONVERTED_BYTES] written a line each.
     */
    fun convertedLines(text: String): List<String> {
        var bytes = 0L
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_CONVERTED_LINE }.distinct()
            .take(MAX_CONVERTED_LINES)
            .takeWhile { bytes += it.encodeToByteArray().size + 1; bytes <= MAX_CONVERTED_BYTES }
            .toList()
    }

    /**
     * The [CONVERTED] record after a restore (§4.5, §11): the phone's lines and the backup's, each once. A pack either
     * converted is not converted again: its layouts are in the folder (the backup's replacing the phone's of their ids).
     */
    fun mergeConverted(phone: String?, backup: String?): String =
        convertedLines(listOfNotNull(phone, backup).joinToString("\n")).joinToString("") { "$it\n" }
}
