/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** A directory of user files (learning record, backups, word lists, stashes, table indexes), reached only by name through this and [LogFile]. [toString] names it in errors. */
interface UserFiles {
    fun file(name: String): LogFile

    fun dir(name: String): UserFiles

    /** Whether it is there as a directory. */
    fun exists(): Boolean

    /** Whether it is there now. */
    fun mkdirs(): Boolean

    /** Sorted; none if absent. */
    fun files(): List<String>

    /** Sorted; none if absent. */
    fun dirs(): List<String>

    /** Listed at once; null if it cannot be listed now (absent, I/O error, permission), which a copy must not take for empty. */
    fun listing(): Listing? = if (exists()) Listing(files(), dirs()) else null

    /** [unknown]: names whose kind did not read (stat failing, EIO on FUSE) or neither: something is there, so a move must not ignore it. */
    data class Listing(val files: List<String>, val dirs: List<String>, val unknown: List<String> = emptyList())

    /** Everything in it, directory lengths too; 0 if absent. */
    fun size(): Long

    /** Only if empty; false if absent. */
    fun delete(): Boolean

    fun deleteAll(): Boolean

    /** Renames onto [dest] (same platform; absent or an empty directory, replaced as rename(2) does). */
    fun renameTo(dest: UserFiles): Boolean
}

/** A user file: appended to, written whole ([create]; atomically via [org.fcitx.fcitx5.android.engine.store.replaceFile]), or read or mapped. Unsupported operations throw [IOException]. */
interface LogFile {
    val name: String

    /** Whether it is there as a file. */
    fun exists(): Boolean

    /** Anything at its path, a directory too: for callers that must not take a non-file for none. */
    fun taken(): Boolean

    /** 0 if absent; a directory's own length if one is at its path. */
    fun length(): Long

    @Throws(IOException::class)
    fun read(): ByteArray

    /** As they are now: another thread may be appending. */
    @Throws(IOException::class)
    fun read(length: Int): ByteArray

    @Throws(IOException::class)
    fun input(): InputStream

    /** Mapped where the platform maps, never read through. */
    @Throws(IOException::class)
    fun map(): ByteBuffer

    /** 0 if absent. */
    fun lastModified(): Long

    /** A moved file keeps its time: dictionary stamps compare it. */
    fun setLastModified(time: Long): Boolean

    /** Writes it anew from empty (its directory must exist); an open append writes on at the new end. */
    @Throws(IOException::class)
    fun create(): LogOutput

    /** Made if absent (its directory must exist). */
    @Throws(IOException::class)
    fun append(): LogOutput

    /** Zeros past the old end; made if absent; an open append writes on at the new end. */
    @Throws(IOException::class)
    fun truncate(length: Long)

    /** Replaces a file at [dest], not a directory; a directory at its path moves as [UserFiles.renameTo]. */
    fun renameTo(dest: LogFile): Boolean

    /** Deletes it, or an empty directory at its path. */
    fun delete(): Boolean

    /** Makes it empty unless anything is at its path: whether it was made. */
    @Throws(IOException::class)
    fun createNew(): Boolean

    fun mkdirs()

    /** The sibling file named as this plus [suffix]. */
    fun beside(suffix: String): LogFile
}

/** [sync] puts the bytes written so far on disk. */
abstract class LogOutput : OutputStream() {
    @Throws(IOException::class)
    abstract fun sync()
}

/** Not atomic ([org.fcitx.fcitx5.android.engine.store.replaceFile] is). */
@Throws(IOException::class)
fun LogFile.write(bytes: ByteArray) = create().use { it.write(bytes) }
