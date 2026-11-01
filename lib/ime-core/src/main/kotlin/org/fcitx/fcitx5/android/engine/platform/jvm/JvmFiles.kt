/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform.jvm

import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.LogOutput
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** [UserFiles] in the directory [dir] of the platform's file system: the phone's, the JVM's. */
class JvmFiles(val dir: File) : UserFiles {
    override fun file(name: String) = JvmFile(File(dir, name))

    override fun dir(name: String) = JvmFiles(File(dir, name))

    override fun exists() = dir.isDirectory

    override fun mkdirs() = dir.mkdirs() || dir.isDirectory

    // by name: the disk's own order is none
    override fun files(): List<String> = dir.listFiles { f -> f.isFile }.orEmpty().map { it.name }.sorted()

    override fun dirs(): List<String> = dir.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()

    // one listFiles, its null (which files and dirs take for none) kept
    // each kind asked once: a stat that fails reads as neither, and is listed as unknown rather than dropped
    override fun listing() = dir.listFiles()?.let { all ->
        val files = ArrayList<String>()
        val dirs = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (f in all) {
            when {
                f.isFile -> files
                f.isDirectory -> dirs
                else -> unknown
            }.add(f.name)
        }
        UserFiles.Listing(files.sorted(), dirs.sorted(), unknown.sorted())
    }

    // the directories' own lengths too, as the stash's limit has always counted them
    override fun size() = dir.walk().sumOf { it.length() }

    override fun delete() = dir.delete()

    override fun deleteAll() = dir.deleteRecursively()

    override fun renameTo(dest: UserFiles) = dir.renameTo((dest as JvmFiles).dir)

    override fun toString() = dir.toString()

    override fun equals(other: Any?) = other is JvmFiles && other.dir == dir

    override fun hashCode() = dir.hashCode()
}

/** A [LogFile] at [file] of the platform's file system. */
class JvmFile(val file: File) : LogFile {
    override val name: String get() = file.name

    override fun exists() = file.isFile

    override fun taken() = file.exists()

    override fun length() = file.length()

    override fun read() = file.readBytes()

    override fun read(length: Int) = ByteArray(length).also { bytes -> RandomAccessFile(file, "r").use { it.readFully(bytes) } }

    override fun input(): InputStream = FileInputStream(file)

    override fun map(): ByteBuffer = RandomAccessFile(file, "r").use { f -> f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }

    override fun lastModified() = file.lastModified()

    override fun setLastModified(time: Long) = file.setLastModified(time)

    override fun create(): LogOutput = Output(FileOutputStream(file))

    override fun append(): LogOutput = Output(FileOutputStream(file, true))

    override fun truncate(length: Long) = RandomAccessFile(file, "rw").use { it.setLength(length) }

    override fun renameTo(dest: LogFile) = file.renameTo((dest as JvmFile).file)

    override fun delete() = file.delete()

    override fun createNew() = file.createNewFile()

    override fun mkdirs() {
        file.parentFile?.mkdirs()
    }

    override fun beside(suffix: String) = JvmFile(File(file.path + suffix))

    override fun toString() = file.toString()

    override fun equals(other: Any?) = other is JvmFile && other.file == file

    override fun hashCode() = file.hashCode()

    private class Output(private val out: FileOutputStream) : LogOutput() {
        override fun write(b: Int) = out.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun flush() = out.flush()

        override fun sync() = out.fd.sync()

        override fun close() = out.close()
    }
}
