/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * [UserFiles] in memory, for a test: what the engine writes kept as it would be on a disk, under
 * this root, till the test drops it. A file is written through as it goes (an append not yet
 * closed is read with what it wrote so far), a write needs its directory made first, and a rename
 * replaces what was at its destination, as on the phone. Safe across threads, as a compaction
 * reads the log on its own. Another [MemoryFiles] made over the same [root] (an engines made anew)
 * sees the same files. Held to [JvmFiles][org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles]
 * on a disk by UserFilesContractTest; a directory's own length is ext4's (and f2fs's), [DIR_LENGTH].
 * Not modelled: a [LogFile.create] stream keeps its offset on a disk when another handle cuts the
 * file (its next write leaves a hole of zeros); here every stream writes on at the end, as an
 * append does, which is all [UserFiles] promises.
 */
class MemoryFiles private constructor(private val root: Root, private val path: String) : UserFiles {
    constructor() : this(Root(), "")

    private class Root {
        val files = HashMap<String, ByteArrayOutputStream>()
        val dirs = HashSet<String>().apply { add("") }

        // when each file was last written: a clock that only goes forward, as a test wants no sleeps
        val modified = HashMap<String, Long>()
        var clock = 0L

        fun touch(path: String) {
            modified[path] = ++clock
        }
    }

    /** The bytes of every file under this directory, by their paths below it: what a test compares. */
    val all: Map<String, ByteArray>
        get() = synchronized(root) {
            root.files.filterKeys { it.startsWith(prefix) }.map { (k, v) -> k.removePrefix(prefix) to v.toByteArray() }.toMap()
        }

    private val prefix get() = if (path.isEmpty()) "" else "$path/"

    private fun child(name: String): String {
        require(name.isNotEmpty() && '/' !in name) { "not a name: $name" }
        return prefix + name
    }

    override fun file(name: String): LogFile = MemoryFile(root, child(name))

    override fun dir(name: String): UserFiles = MemoryFiles(root, child(name))

    override fun exists() = synchronized(root) { path in root.dirs }

    override fun mkdirs(): Boolean = synchronized(root) {
        // every ancestor checked before any is made: a file on the way makes nothing, as File.mkdirs does
        val chain = generateSequence(path) { at -> parent(at).takeIf { at.isNotEmpty() } }.filter { it.isNotEmpty() }.toList()
        if (chain.any { it in root.files }) return false
        root.dirs += chain
        true
    }

    override fun files(): List<String> = synchronized(root) { root.files.keys.mapNotNull(::below).sorted() }

    override fun dirs(): List<String> = synchronized(root) { root.dirs.mapNotNull(::below).sorted() }

    // a name directly below this directory, of [key]: null for one elsewhere
    private fun below(key: String): String? = key.takeIf { path in root.dirs && it.startsWith(prefix) && it.length > prefix.length }
        ?.removePrefix(prefix)?.takeIf { '/' !in it }

    // its files' bytes and each directory's own length, itself too, as a walk of the disk counts them
    override fun size(): Long = synchronized(root) {
        if (path !in root.dirs) return 0L
        val files = root.files.filterKeys { it.startsWith(prefix) }.values.sumOf { it.size().toLong() }
        files + DIR_LENGTH * root.dirs.count { it == path || it.startsWith(prefix) }
    }

    override fun delete(): Boolean = synchronized(root) {
        val empty = files().isEmpty() && dirs().isEmpty()
        if (path.isEmpty() || path !in root.dirs || !empty) return false
        root.dirs -= path
        true
    }

    override fun deleteAll(): Boolean = synchronized(root) {
        // a file at the path goes too, as File.deleteRecursively takes one
        root.files.keys.removeAll { it == path || it.startsWith(prefix) }
        root.dirs.removeAll { it != "" && (it == path || it.startsWith(prefix)) }
        true
    }

    override fun renameTo(dest: UserFiles): Boolean = synchronized(root) {
        val to = (dest as MemoryFiles).path
        // onto a directory only if it is empty, as rename(2) replaces one
        val free = to !in root.files && (to !in root.dirs || MemoryFiles(root, to).let { it.files().isEmpty() && it.dirs().isEmpty() })
        // nor into itself
        val place = parent(to) in root.dirs && !to.startsWith("$path/")
        if (path !in root.dirs || !free || !place) return false
        root.dirs -= to
        val moved = root.files.keys.filter { it.startsWith(prefix) }
        moved.forEach {
            val at = to + "/" + it.removePrefix(prefix)
            root.files[at] = root.files.remove(it)!!
            root.modified.remove(it)?.let { time -> root.modified[at] = time }
        }
        val dirs = root.dirs.filter { it == path || it.startsWith(prefix) }
        root.dirs -= dirs.toSet()
        dirs.forEach { root.dirs += to + it.removePrefix(path) }
        true
    }

    override fun toString() = "memory:/$path"

    private class MemoryFile(private val root: Root, private val path: String) : LogFile {
        override val name: String get() = path.substringAfterLast('/')

        private fun bytes() = root.files[path]

        override fun exists() = synchronized(root) { path in root.files }

        override fun taken() = synchronized(root) { path in root.files || path in root.dirs }

        override fun length() = synchronized(root) { bytes()?.size()?.toLong() ?: if (path in root.dirs) DIR_LENGTH else 0L }

        override fun read(): ByteArray = synchronized(root) { (bytes() ?: throw FileNotFoundException(toString())).toByteArray() }

        override fun read(length: Int): ByteArray {
            val all = read()
            if (all.size < length) throw EOFException(toString())
            return all.copyOf(length)
        }

        override fun input(): InputStream = ByteArrayInputStream(read())

        override fun map(): ByteBuffer = ByteBuffer.wrap(read()).asReadOnlyBuffer()

        override fun lastModified() = synchronized(root) { if (path in root.files) root.modified[path] ?: 0L else 0L }

        override fun setLastModified(time: Long) = synchronized(root) {
            (path in root.files).also { if (it) root.modified[path] = time }
        }

        // the directory it is in: one not made is no place to write
        private fun open(fresh: Boolean): ByteArrayOutputStream = synchronized(root) {
            if (parent(path) !in root.dirs || path in root.dirs) throw FileNotFoundException(toString())
            // written anew, it is the same file emptied: an append open on it writes on after what is written now
            root.touch(path)
            bytes()?.also { if (fresh) it.reset() } ?: ByteArrayOutputStream().also { root.files[path] = it }
        }

        override fun create(): LogOutput = Output(open(fresh = true))

        override fun append(): LogOutput = Output(open(fresh = false))

        // writes to the bytes the file had as it was opened: a file renamed over meanwhile is
        // written no more, as an unlinked one is not on the disk
        private inner class Output(private val to: ByteArrayOutputStream) : LogOutput() {
            private var closed = false

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(root) {
                if (closed) throw IOException("closed")
                to.write(b, off, len)
                if (root.files[path] === to) root.touch(path)
            }

            override fun sync() {
                if (closed) throw IOException("closed")
            }

            override fun close() {
                closed = true
            }
        }

        // one not there is made, as RandomAccessFile's "rw" makes it
        override fun truncate(length: Long) = synchronized(root) {
            val was = open(fresh = false)
            val kept = was.toByteArray().copyOf(length.toInt())
            was.reset()
            was.write(kept)
        }

        override fun renameTo(dest: LogFile): Boolean = synchronized(root) {
            val to = (dest as MemoryFile).path
            // a directory at the path moves as a directory does
            if (path in root.dirs) return MemoryFiles(root, path).renameTo(MemoryFiles(root, to))
            val moved = root.files[path] ?: return false
            if (parent(to) !in root.dirs || to in root.dirs) return false
            root.files.remove(path)
            root.files[to] = moved
            root.modified.remove(path)?.let { root.modified[to] = it }
            true
        }

        // a directory at the path goes too if it is empty, as File.delete takes one
        override fun delete(): Boolean = synchronized(root) { root.files.remove(path) != null || (path in root.dirs && MemoryFiles(root, path).delete()) }

        override fun createNew(): Boolean = synchronized(root) {
            if (path in root.files || path in root.dirs) return false
            open(fresh = true)
            true
        }

        override fun mkdirs() {
            MemoryFiles(root, parent(path)).mkdirs()
        }

        override fun beside(suffix: String): LogFile = MemoryFile(root, path + suffix)

        override fun toString() = "memory:/$path"
    }

    private companion object {
        fun parent(path: String) = path.substringBeforeLast('/', "")

        // what ext4 and f2fs give as a directory's own length
        const val DIR_LENGTH = 4096L
    }
}
