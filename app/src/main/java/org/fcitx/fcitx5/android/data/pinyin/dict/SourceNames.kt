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
 * Imported dictionaries are kept as `<name>.txt.gz` (`.disable` when off), a quarter of the text. 0.3.2 and older kept
 * `<name>.txt[.disable]`, converted on the compile thread ([unconverted]); a restored old backup brings those back.
 */
object SourceNames {

    const val GZ = ".txt.gz"

    /** Until renamed into place. */
    const val TMP = "$GZ.tmp"

    /** Apart from [TMP]: a same-name import during a conversion would share it, each deleting the other's. */
    const val CONVERT_TMP = "$GZ.convert.tmp"

    private const val OFF = ".disable"

    private const val TEXT = ".txt"

    fun isSource(fileName: String) = fileName.endsWith(GZ) || fileName.endsWith("$GZ$OFF")

    /** An older build's text, on or off: to convert. */
    fun isText(fileName: String) = fileName.endsWith(TEXT) || fileName.endsWith("$TEXT$OFF")

    /** An import's, a conversion's, or an older build's `.txt.tmp`. */
    fun isTemp(fileName: String) = fileName.endsWith(TMP) || fileName.endsWith(CONVERT_TMP) || fileName.endsWith("$TEXT.tmp")

    fun isOn(fileName: String) = !fileName.endsWith(OFF)

    /** Either kind: what `.txt.gz` or `.txt` (and `.disable`) leave. */
    fun nameOf(fileName: String): String = fileName.removeSuffix(OFF).removeSuffix(".gz").removeSuffix(TEXT)

    fun fileName(name: String, on: Boolean = true) = "$name$GZ" + if (on) "" else OFF

    /** An older build's file for it. */
    fun textName(name: String, on: Boolean = true) = "$name$TEXT" + if (on) "" else OFF

    /** Each converted before the next compile. */
    fun unconverted(dir: File): List<File> = dir.listFiles { f -> f.isFile && isText(f.name) }.orEmpty().sortedBy { it.name }

    /** A kept dictionary through gzip, a text one as is. */
    fun open(file: File): InputStream = open(file, FileInputStream(file))

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
     * Synced through the writing descriptor before close (a power cut never leaves a name renamed onto an empty file); both
     * streams closed however it ends. [write] leaves the stream open, a writer over it flushed.
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
