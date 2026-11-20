/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.UserFiles

/**
 * Where the user's fcitx files live: the app's own storage. Older builds kept them on external storage, where a
 * phone cleaner or a start without it reset every setting. [settle] moves an old directory in once: copied to
 * [Home.staged], read back against the source's checksum, then renamed, so a kill leaves no half move. The old
 * is then deleted (a copy left outside is readable by others), only by a home carrying [MOVED]. Files are streamed.
 */
object ConfigHome {
    /** [old]: where an older build had it (external files, or the app's files when that was away). */
    data class Home(val name: String, val old: String) {
        val staged: String get() = "$name.new"
    }

    /** Until the source is deleted; a backup leaves it out, or a restore would delete what it never moved. */
    const val MOVED = ".moved-from"

    val CONFIG = Home("host-config", "config")
    val DATA = Home("host-data", "data")
    val THEME = Home("host-theme", "theme")

    enum class Use {
        HOME,

        EXTERNAL,

        /** Where an older build fell back to. */
        FALLBACK,

        /** Every setting at its default. */
        NONE,
    }

    enum class Problem {
        /** Not moved, and the external storage away: read what can be, write nothing, try again next start. */
        EXTERNAL_AWAY,

        /** The move failed ([Place.error]): the old directory used and written where it is, or none at all. */
        NOT_MOVED,
    }

    data class Place(
        val use: Use,
        val writable: Boolean,
        val problem: Problem? = null,
        val movedFrom: Use? = null,
        val error: IOException? = null,
    )

    /**
     * One decision per home per process: the first [of] settles it, everyone after gets the same place, a failed
     * move too (never retried midway: an editor holding the old directory would write where the engine no longer
     * reads). [settled] hears each decision before any reader gets it; one that throws still leaves it decided.
     */
    class Settled(
        private val files: UserFiles,
        private val external: () -> UserFiles?,
        private val fresh: () -> Boolean,
        // the copy is made there, the old directory kept
        private val room: () -> Long = { Long.MAX_VALUE },
        private val settled: (Home, Place) -> Unit = { _, _ -> },
    ) {
        private val places = ConcurrentHashMap<Home, Place>()

        // two equal [Home]s settle one at a time, not each into the same staged copy
        private val locks = ConcurrentHashMap<Home, Any>()

        fun of(which: Home): Place = places[which] ?: synchronized(locks.getOrPut(which) { Any() }) {
            places[which] ?: settle(files, external(), which, fresh(), room()).also {
                try {
                    settled(which, it)
                } finally {
                    places[which] = it
                }
            }
        }
    }

    /**
     * [external] null when the storage is away (unlistable counts as away); its [Home.old] wins over the app's
     * files' one. A source larger than [room] is not copied (it would fail again every start). [fresh] (first
     * start of an install): make the home rather than wait for a storage a phone may never have.
     */
    fun settle(
        files: UserFiles,
        external: UserFiles?,
        which: Home = CONFIG,
        fresh: Boolean = false,
        room: Long = Long.MAX_VALUE,
    ): Place {
        val home = files.dir(which.name)
        if (home.exists()) {
            dropOld(files, external, which, home)
            return Place(Use.HOME, writable = true)
        }
        val fallback = files.dir(which.old)
        val listing = external?.listing()
        if (external == null || listing == null) {
            if (fresh && !fallback.exists()) return made(home)
            // a fallback may be stale under the external one: read, but nothing settled
            return Place(if (fallback.exists()) Use.FALLBACK else Use.NONE, writable = false, Problem.EXTERNAL_AWAY)
        }
        val old = external.dir(which.old)
        // by this listing, not a second stat: an EIO on the external FUSE would read as none and hide it for good
        if (which.old !in listing.dirs && (which.old in listing.unknown || external.file(which.old).taken())) {
            return Place(if (fallback.exists()) Use.FALLBACK else Use.NONE, writable = false, Problem.EXTERNAL_AWAY)
        }
        val (use, source) = when {
            which.old in listing.dirs -> Use.EXTERNAL to old
            fallback.exists() -> Use.FALLBACK to fallback
            else -> return made(home)
        }
        val staged = files.dir(which.staged)
        try {
            // a copy a killed start left half made: not renamed in with this one
            need(staged.deleteAll()) { "cannot delete $staged" }
            val size = source.size()
            need(size <= room) { "$source holds $size bytes, $room free" }
            copy(source, staged)
            staged.file(MOVED).create().use { out ->
                out.write(use.name.toByteArray())
                out.sync()
            }
            need(staged.renameTo(home)) { "cannot rename $staged to $home" }
        } catch (e: IOException) {
            staged.deleteAll()
            return Place(use, writable = true, Problem.NOT_MOVED, error = e)
        }
        dropOld(files, external, which, home)
        return Place(Use.HOME, writable = true, movedFrom = use)
    }

    // deletes where [MOVED] says it came from, then the marker; never fails the home; external only while listed
    private fun dropOld(files: UserFiles, external: UserFiles?, which: Home, home: UserFiles) {
        val marker = home.file(MOVED)
        if (!marker.exists()) return
        // one that does not read is tried again at the next start
        val text = try {
            String(marker.read())
        } catch (_: IOException) {
            return
        }
        val gone = when (Use.entries.find { it.name == text }) {
            Use.EXTERNAL -> {
                val listing = external?.listing() ?: return
                // listed, its kind not read: there still, deleted at a later start
                if (which.old in listing.unknown) return
                which.old !in listing.dirs || external.dir(which.old).deleteAll()
            }
            Use.FALLBACK -> files.dir(which.old).let { !it.exists() || it.deleteAll() }
            // not one of ours (torn, or unreadable): nothing known to delete
            else -> true
        }
        if (gone) marker.delete()
    }

    // nothing to move: an empty directory of its own, which marks it settled
    private fun made(home: UserFiles) = if (home.mkdirs()) {
        Place(Use.HOME, writable = true)
    } else {
        Place(Use.NONE, writable = false, Problem.NOT_MOVED, error = IOException("cannot make $home"))
    }

    // every file read back against its checksum and length; an unlistable directory fails it, never copied in part
    private fun copy(from: UserFiles, to: UserFiles) {
        // listed once: a second listing could fail where the first did not, and read as empty
        val listing = from.listing() ?: throw IOException("cannot list $from")
        need(listing.unknown.isEmpty()) { "$from: ${listing.unknown} neither a file nor a directory that reads" }
        need(to.mkdirs()) { "cannot make $to" }
        for (name in listing.files) {
            val source = from.file(name)
            val target = to.file(name)
            val sum = CRC32()
            val length = target.create().use { out ->
                val length = source.input().use { input -> stream(input, out, sum) }
                // each file: a directory cannot be synced through the port, and the rename must not land before the bytes
                out.sync()
                length
            }
            // best effort: a dictionary's stamp and the notes' signatures compare it
            target.setLastModified(source.lastModified())
            // the source's length too: a read that ended early without throwing would check only what it read
            need(length == source.length() && readsBack(target, length, sum.value)) { "$target does not read back as $source" }
        }
        for (name in listing.dirs) copy(from.dir(name), to.dir(name))
    }

    private fun stream(input: InputStream, out: OutputStream, sum: CRC32): Long {
        val buffer = ByteArray(CHUNK)
        var length = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            sum.update(buffer, 0, n)
            out.write(buffer, 0, n)
            length += n
        }
        return length
    }

    private inline fun need(ok: Boolean, what: () -> String) {
        if (!ok) throw IOException(what())
    }

    private fun readsBack(file: LogFile, length: Long, crc: Long): Boolean = file.input().use { input ->
        val sum = CRC32()
        val buffer = ByteArray(CHUNK)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            sum.update(buffer, 0, n)
            read += n
        }
        read == length && sum.value == crc
    }

    private const val CHUNK = 1 shl 16
}
