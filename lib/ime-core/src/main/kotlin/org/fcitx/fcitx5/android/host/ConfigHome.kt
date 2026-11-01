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
 * Where the user's files fcitx kept on the external storage are: the app's own storage. [CONFIG], the
 * host's config files (the profile, the `.conf` files under `conf/`), in `files/host-config`; [DATA],
 * fcitx's `data/` (custom phrases, imported dictionaries, quick input, edited punctuation), in
 * `files/host-data`; [THEME], the custom themes, in `files/host-theme`. Up to 0.4 the config was
 * fcitx's `config/` on the external storage (`Android/data/<package>/files`), or `files/config` at a
 * start that found no external storage, and was read as nothing there: a phone cleaner emptying
 * `Android/data`, or one start without the external storage, put every 通用输入 setting back to its
 * default, written so at the next change (A4, 2026-10-28).
 *
 * [settle] moves an older build's directory in, once: copied whole to its [Home.staged], each copy
 * read back against the checksum its source had as it was copied (the source read once), then the
 * copy renamed to [Home.name], so a kill leaves no half move and the directory there means moved.
 * The old directory is then deleted (the user's decision, 2026-10-31): a copy left on the external
 * storage is one a cleaner or another app reads, and going back to 0.4 means an uninstall, which
 * takes both. Only a home the move made deletes anything: [MOVED] in it, written into the copy
 * before the rename, says where it came from, so a home made empty never deletes an older build's
 * directory that appears later; it goes once the old is gone, a delete that fails (or a kill
 * before it) tried again at the next start. Files are streamed, never held whole: `data/` holds
 * the imported dictionaries and what is compiled of them.
 */
object ConfigHome {
    /**
     * One directory moved in: [name] under the app's files, [old] where an older build had it (under
     * the external files directory, or under the app's files when that was away).
     */
    data class Home(val name: String, val old: String) {
        /** The copy being made, renamed to [name] once all of it is read back. */
        val staged: String get() = "$name.new"
    }

    /**
     * In a home the move made, until the directory it came from is deleted: that one's [Use]. A
     * backup leaves it out (restored into a home, it would have the next start delete what it never moved).
     */
    const val MOVED = ".moved-from"

    val CONFIG = Home("host-config", "config")
    val DATA = Home("host-data", "data")
    val THEME = Home("host-theme", "theme")

    /** The directory the host reads its config from, this run. */
    enum class Use {
        /** [Home.name]. */
        HOME,

        /** The external files directory's [Home.old], an older build's. */
        EXTERNAL,

        /** The app's files' [Home.old], where an older build fell back to. */
        FALLBACK,

        /** None: every setting at its default. */
        NONE,
    }

    enum class Problem {
        /**
         * Not moved yet, and the external storage is not there to say whether an older build's
         * config is on it: what can be read is read, nothing written, the move tried at the next start.
         */
        EXTERNAL_AWAY,

        /**
         * Not in place, the [Place.error] why, the move (or the making) tried at the next start: an
         * older build's directory that could not be copied in (no room for the copy, say), used where it
         * is and written there; or none to move and the home that could not be made, so nothing at all
         * ([Use.NONE], not writable).
         */
        NOT_MOVED,
    }

    /**
     * [use] read this run, written to only if [writable]; [problem] what kept it from [Use.HOME], with
     * the [error] of a copy that failed; [movedFrom], what this start moved into [Home.name].
     */
    data class Place(
        val use: Use,
        val writable: Boolean,
        val problem: Problem? = null,
        val movedFrom: Use? = null,
        val error: IOException? = null,
    )

    /**
     * One decision a process for each home: the first to ask [of] it settles it ([settle] with
     * [external] and [fresh] as they are then), and every reader and writer after, whatever its
     * thread, gets that same place, a failed move too (tried again by the next process, never midway:
     * an editor holding the old directory would write where the engine no longer reads). [settled]
     * hears each decision once, as it is made.
     */
    class Settled(
        private val files: UserFiles,
        private val external: () -> UserFiles?,
        private val fresh: () -> Boolean,
        // bytes free for [files] (the copy is made there, the old directory kept)
        private val room: () -> Long = { Long.MAX_VALUE },
        private val settled: (Home, Place) -> Unit = { _, _ -> },
    ) {
        private val places = ConcurrentHashMap<Home, Place>()

        // by the key [places] is keyed by: two equal [Home]s settle one at a time, not each into the same staged copy
        private val locks = ConcurrentHashMap<Home, Any>()

        /** [which]'s place this process; one move at a time for each, the others asking waiting for it. */
        fun of(which: Home): Place = places[which] ?: synchronized(locks.getOrPut(which) { Any() }) {
            places[which] ?: settle(files, external(), which, fresh(), room()).also {
                places[which] = it
                settled(which, it)
            }
        }
    }

    /**
     * Where [which] is this run, an older build's moved in first if [files] (the app's files
     * directory) has no [Home.name] yet. [external] is the external files directory, null when it is not
     * there (getExternalFilesDir's null, the storage away; one that cannot be listed is taken as
     * away). Its [Home.old] is taken over the app's files' one, which an older build wrote only at a
     * start without it. [room]: the bytes free for [files]; a source larger is not copied (a copy that
     * filled the storage would fail, then be made again at every start). [fresh]: nothing of an older
     * build's can be anywhere (a new install, its first start), so a start without the external
     * storage makes the directory rather than wait for it, which on a phone that never has one would
     * be every start.
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
            // a fallback may be stale under the external one: read, as an older build read it then, but nothing settled
            return Place(if (fallback.exists()) Use.FALLBACK else Use.NONE, writable = false, Problem.EXTERNAL_AWAY)
        }
        val old = external.dir(which.old)
        // by the listing just made, not a second stat: one failing (EIO on the external storage's
        // FUSE) would read as no older config, and an empty home made would hide it for good. Listed
        // but of a kind that did not read: there, so away, not none
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

    // the directory [home] was moved in from, if its [MOVED] says so, deleted, then the marker: never
    // failing the home, which is in use whatever comes of it; the external one only while the storage
    // lists, an unlisted one being away, not gone
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

    // [from] into [to], made, every file read back and checked against the checksum and length its
    // source streamed while it was copied; a directory that cannot be listed, or holds a name whose kind
    // does not read, fails it, never copied in part and then taken as moved (the source is deleted after)
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
                // each file, as there is no syncing a directory through the port: the rename after
                // must not land before the bytes do
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

    // [input] into [out], [sum] taken as it goes: the bytes streamed
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
