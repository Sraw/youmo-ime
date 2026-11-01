/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A directory of the user's files, as the platform keeps them: the learning record and its
 * backups, the word lists, the stashes a settings restore leaves, the tables' indexes. The engine
 * reaches them by name through this and [LogFile] alone, never by a path: on the phone and the
 * JVM a directory ([org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles]), in a test memory.
 * Its [toString] is what an error names it by.
 */
interface UserFiles {
    /** The file [name] in it, there or not. */
    fun file(name: String): LogFile

    /** The directory [name] in it, there or not. */
    fun dir(name: String): UserFiles

    /** Whether it is there, a directory. */
    fun exists(): Boolean

    /** Makes it, and the directories it is in: whether it is there now. */
    fun mkdirs(): Boolean

    /** The names of the files in it, by name; none if it is not there. */
    fun files(): List<String>

    /** The names of the directories in it, by name; none if it is not there. */
    fun dirs(): List<String>

    /**
     * What is in it, listed at once; null if it cannot be listed now (not there, an I/O error, no
     * permission), which [files] and [dirs] give as none and a copy of it must not take for empty.
     */
    fun listing(): Listing? = if (exists()) Listing(files(), dirs()) else null

    /**
     * The names of a [UserFiles]' files and directories, each by name; [unknown], the names listed
     * whose kind did not read (a stat failing, EIO on the external storage's FUSE) or that are
     * neither: something is there, so a move must not take it for none.
     */
    data class Listing(val files: List<String>, val dirs: List<String>, val unknown: List<String> = emptyList())

    /** How many bytes it holds, all in it counted, its directories' own lengths (the file system's) too; 0 if it is not there. */
    fun size(): Long

    /** Deletes it if it is empty: whether it was deleted (false if it was not there). */
    fun delete(): Boolean

    /** Deletes it with all in it: whether it is gone. */
    fun deleteAll(): Boolean

    /** Renames it to [dest], one of the same platform's, there or an empty directory (replaced, as rename(2) does): whether it was. */
    fun renameTo(dest: UserFiles): Boolean
}

/**
 * A file of the user's, in a [UserFiles]: a log appended to ([append], [truncate]), a file
 * written whole ([create]; atomically with [org.fcitx.fcitx5.android.engine.store.replaceFile]),
 * or one read whole or mapped ([read], [map]). What the platform cannot do throws an
 * [IOException], as a disk does. Its [toString] is what an error names it by.
 */
interface LogFile {
    val name: String

    /** Whether it is there, a file. */
    fun exists(): Boolean

    /**
     * Whether anything is at its path, a directory too (as `java.io.File.exists`): where a file is
     * wanted and something else stands, a caller that must not take it for none asks this.
     */
    fun taken(): Boolean

    /** Its length in bytes; 0 if it is not there, a directory's own length (the file system's) if one is at its path. */
    fun length(): Long

    /** All its bytes. */
    @Throws(IOException::class)
    fun read(): ByteArray

    /** Its first [length] bytes, as they are now: another thread may be appending. */
    @Throws(IOException::class)
    fun read(length: Int): ByteArray

    /** Its bytes as a stream, from the start. */
    @Throws(IOException::class)
    fun input(): InputStream

    /** Its bytes, read-only: mapped where the platform maps, never read through. */
    @Throws(IOException::class)
    fun map(): ByteBuffer

    /** When it was last written, in ms since the epoch; 0 if it is not there. */
    fun lastModified(): Long

    /** Sets when it was last written (a moved file keeps its time: dictionary stamps compare it): whether it was set. */
    fun setLastModified(time: Long): Boolean

    /**
     * Writes it anew, from empty, made if it is not there (its directory must be): the same file
     * emptied, an append still open on it writing on at its new end.
     */
    @Throws(IOException::class)
    fun create(): LogOutput

    /** Writes on at its end, made if it is not there (its directory must be). */
    @Throws(IOException::class)
    fun append(): LogOutput

    /** Cuts it to [length] bytes (longer: zeros after), made if it is not there; an append still open on it writes on at its new end. */
    @Throws(IOException::class)
    fun truncate(length: Long)

    /** Renames it to [dest], in place of a file there, not of a directory: whether it was. A directory at its path moves as [UserFiles.renameTo]. */
    fun renameTo(dest: LogFile): Boolean

    /** Deletes it, or an empty directory at its path: whether it was there to delete. */
    fun delete(): Boolean

    /** Makes it, empty, unless anything is at its path: whether it was made. */
    @Throws(IOException::class)
    fun createNew(): Boolean

    /** Makes the directories it is to be in. */
    fun mkdirs()

    /** The file beside it named as it is with [suffix] after. */
    fun beside(suffix: String): LogFile
}

/** What [LogFile.create] and [LogFile.append] write to: [sync] has the bytes written so far on the disk. */
abstract class LogOutput : OutputStream() {
    @Throws(IOException::class)
    abstract fun sync()
}

/** Writes [bytes] as the whole of the file, as File.writeBytes did: not atomic ([org.fcitx.fcitx5.android.engine.store.replaceFile] is). */
@Throws(IOException::class)
fun LogFile.write(bytes: ByteArray) = create().use { it.write(bytes) }
