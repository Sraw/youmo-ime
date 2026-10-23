/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The names of the imported dictionaries' files: each kept gzip-compressed as `<name>.txt.gz`, or
 * `<name>.txt.gz.disable` turned off (a quarter of the text: frost's 84.5 MiB is 23.6). 0.3.2 and
 * older kept `<name>.txt[.disable]`, which the compile thread converts ([unconverted]); a restored
 * backup of theirs brings those back.
 */
object SourceNames {

    /** What a dictionary kept turned on ends with. */
    const val GZ = ".txt.gz"

    /** What a dictionary being written (imported, converted) ends with, till it is renamed into place. */
    const val TMP = "$GZ.tmp"

    /**
     * What a conversion of an older build's text is written to: apart from an import's [TMP], which a
     * same-name import made while the text converts would share, each deleting the other's (F3594).
     */
    const val CONVERT_TMP = "$GZ.convert.tmp"

    private const val OFF = ".disable"

    private const val TEXT = ".txt"

    /** Whether [fileName] is a dictionary kept as this build keeps them, on or off. */
    fun isSource(fileName: String) = fileName.endsWith(GZ) || fileName.endsWith("$GZ$OFF")

    /** Whether [fileName] is a dictionary as an older build kept it, text, on or off: to convert. */
    fun isText(fileName: String) = fileName.endsWith(TEXT) || fileName.endsWith("$TEXT$OFF")

    /** Whether [fileName] is a dictionary half written: an import's, a conversion's, or an older build's import's `.txt.tmp`. */
    fun isTemp(fileName: String) = fileName.endsWith(TMP) || fileName.endsWith(CONVERT_TMP) || fileName.endsWith("$TEXT.tmp")

    /** Whether [fileName], a dictionary of either kind, is turned on. */
    fun isOn(fileName: String) = !fileName.endsWith(OFF)

    /** The dictionary's name, from its file's, either kind: what `.txt.gz` or `.txt` (and `.disable`) leave. */
    fun nameOf(fileName: String): String = fileName.removeSuffix(OFF).removeSuffix(".gz").removeSuffix(TEXT)

    /** The file a dictionary called [name] is kept in, on or off. */
    fun fileName(name: String, on: Boolean = true) = "$name$GZ" + if (on) "" else OFF

    /** The file an older build kept a dictionary called [name] in, on or off. */
    fun textName(name: String, on: Boolean = true) = "$name$TEXT" + if (on) "" else OFF

    /** The dictionaries in [dir] an older build left as text, on or off: each converted before the next compile. */
    fun unconverted(dir: File): List<File> = dir.listFiles { f -> f.isFile && isText(f.name) }.orEmpty().sortedBy { it.name }

    /** [file]'s lines' bytes: a kept dictionary's through gzip, a text one's as they are. */
    fun open(file: File): InputStream = open(file, FileInputStream(file))

    /** [input], [file]'s bytes, read as [open] reads them. */
    fun open(file: File, input: InputStream): InputStream = if (file.name.removeSuffix(OFF).endsWith(GZ)) {
        try {
            GZIPInputStream(input, BUFFER)
        } catch (e: IOException) {
            input.close()
            throw e
        }
    } else {
        input
    }

    /**
     * [temp] written gzip'd by [write], synced through the descriptor that wrote it before it is
     * closed (a power cut then never leaves a name renamed onto an empty file); both streams closed
     * however it ends, the file's too if the gzip's could not be made (F3594). [write] leaves
     * [OutputStream] open, a writer over it flushed.
     */
    fun <T> writeGzip(temp: File, write: (OutputStream) -> T): T = FileOutputStream(temp).use { file ->
        GZIPOutputStream(file, BUFFER).use { out ->
            write(out).also {
                out.finish()
                file.fd.sync()
            }
        }
    }

    private const val BUFFER = 1 shl 16
}
