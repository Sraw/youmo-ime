/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Where a pack stands for this APK. */
sealed class PackStatus {
    /** None imported (or what was is unreadable). */
    object Missing : PackStatus()

    /** Imported and usable. */
    class Ready(val manifest: PackManifest) : PackStatus()

    /** Imported, but this APK cannot use it: a pack of [needed]'s format, at least its version, wanted. */
    class NeedsUpdate(val manifest: PackManifest, val needed: PackRequirement) : PackStatus()

    /** Imported, of a newer format than this APK reads: the app needs updating, not the pack. */
    class NeedsNewerApp(val manifest: PackManifest, val needed: PackRequirement) : PackStatus()

    /** Imported, but its files no longer what [manifest] says ([PackShelf.verify]): not used, to be imported again. */
    class Damaged(val manifest: PackManifest) : PackStatus()
}

/** Why a file was not imported. */
sealed class Refusal {
    /** Not a pack: no manifest and signature first, or a manifest that does not read ([detail]). */
    class NotAPack(val detail: String) : Refusal()

    /** Its signature is none of the keys this build trusts ([key]: the manifest's name of its key). */
    class BadSignature(val key: String) : Refusal()

    /** A file differs from the manifest, is not in it, or is missing ([path]). */
    class BadFile(val path: String) : Refusal()

    /** A pack this build cannot use: [manifest] against [needed]. */
    class Incompatible(val manifest: PackManifest, val needed: PackRequirement) : Refusal()

    /** [need] bytes wanted, [free] there. */
    class NoSpace(val need: Long, val free: Long) : Refusal()

    /** The file could not be read, or the copy written ([detail]). */
    class Unreadable(val detail: String) : Refusal()
}

/** What an import did. */
sealed class ImportResult {
    /** [manifest] in use from now on, in place of [replaced] if one was there. */
    class Imported(val manifest: PackManifest, val replaced: PackManifest?) : ImportResult()

    /** [manifest] is the pack already in use: nothing copied. */
    class AlreadyThere(val manifest: PackManifest) : ImportResult()

    class Refused(val why: Refusal) : ImportResult()
}

/**
 * The imported packs, under [root] (the device-protected `files/packs`: read before the first
 * unlock, as the keyboard is direct-boot aware; kept by an upgrade, as any app data). A pack is
 * `root/<id>/<generation>/` with its files at their asset paths and its manifest and signature;
 * `root/<id>/current` names the generation in use. An import copies into a staging directory,
 * checking every byte against the signed manifest as it goes; only once all of it checks is the
 * staging directory renamed to a generation and `current` replaced (written beside, synced, renamed
 * over: one atomic step). Killed at any point before, the old pack stays in use; [load] deletes what
 * is left over (staging, older generations), at the next start.
 *
 * [trusted]: the raw Ed25519 public keys this build takes a pack signed by. [requirements]: what
 * it reads of each pack. [margin]: bytes kept free beyond a pack's size.
 */
class PackShelf(
    private val root: File,
    private val trusted: List<ByteArray>,
    private val requirements: Map<PackKind, PackRequirement>,
    private val margin: Long = DEFAULT_MARGIN,
) {
    init {
        require(trusted.isNotEmpty()) { "no trusted key" }
        require(trusted.all { it.size == Ed25519.KEY_SIZE }) { "a trusted key is not 32 bytes" }
        require(requirements.keys == PackKind.entries.toSet()) { "a requirement for each pack" }
    }

    @Volatile
    private var state: Map<PackKind, Pair<PackStatus, File?>> = PackKind.entries.associateWith { PackStatus.Missing to null }

    fun status(kind: PackKind): PackStatus = state.getValue(kind).first

    /** The directory of [kind]'s files if it is [PackStatus.Ready], else null. */
    fun dir(kind: PackKind): File? = state.getValue(kind).second

    /**
     * Reads which packs are there and what they are, deleting what an import left behind
     * (staging, older generations, a `current` half written). Each pack's manifest is checked
     * against the keys again, and its files' sizes, not their contents (read at every start, a
     * pack's 300 MB would cost seconds): their contents were checked as they were copied, and the
     * app's own storage is not written by anyone else.
     */
    @Synchronized
    fun load() {
        state = PackKind.entries.associateWith { kind -> loadOne(kind) }
    }

    private fun loadOne(kind: PackKind): Pair<PackStatus, File?> {
        val dir = File(root, kind.id)
        val current = File(dir, CURRENT).takeIf { it.isFile }?.readText()?.trim()?.takeIf { PackManifest.safePath(it) && '/' !in it }
        // anything but the generation in use: staging, older ones, a pointer half written
        dir.listFiles()?.forEach { if (it.name != CURRENT && it.name != current) it.deleteRecursively() }
        if (current == null) return PackStatus.Missing to null
        val gen = File(dir, current)
        // unreadable, unsigned or unparsable: as good as none, imported anew
        val manifest = runCatching {
            val bytes = File(gen, PackManifest.NAME).readBytes()
            val signature = File(gen, PackManifest.SIGNATURE).readBytes()
            PackManifest.parse(bytes).takeIf { signed(bytes, signature) }
        }.getOrNull() ?: return PackStatus.Missing to null
        if (manifest.kind != kind || manifest.files.any { File(gen, it.path).length() != it.size }) return PackStatus.Missing to null
        val status = judge(manifest)
        return status to gen.takeIf { status is PackStatus.Ready }
    }

    /**
     * Reads [kind]'s files in use through, against its manifest: what [load] does not (sizes
     * only). One found changed is [PackStatus.Damaged] from then on, till imported again; true
     * then. Long (a pack's hundreds of MB), so not under the shelf's lock: an import meanwhile
     * (its files replaced, the old ones deleted) leaves the new pack as it is.
     */
    fun verify(kind: PackKind): Boolean {
        val (status, gen) = state.getValue(kind)
        if (status !is PackStatus.Ready || gen == null) return false
        if (intact(status.manifest, gen)) return false
        synchronized(this) {
            if (state.getValue(kind).second != gen) return false
            state = state + (kind to (PackStatus.Damaged(status.manifest) to null))
        }
        return true
    }

    // each of [manifest]'s files in [gen], its size and SHA-256 as it says; one unreadable is not
    private fun intact(manifest: PackManifest, gen: File): Boolean = try {
        val buffer = ByteArray(BUFFER)
        manifest.files.all { entry ->
            val file = File(gen, entry.path)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    digest.update(buffer, 0, n)
                }
            }
            size == entry.size && PackManifest.hex(digest.digest()) == entry.sha256
        }
    } catch (_: IOException) {
        false
    }

    private fun judge(manifest: PackManifest): PackStatus {
        val needed = requirements.getValue(manifest.kind)
        return when {
            manifest.format > needed.format -> PackStatus.NeedsNewerApp(manifest, needed)
            manifest.format < needed.format || manifest.version < needed.minVersion -> PackStatus.NeedsUpdate(manifest, needed)
            else -> PackStatus.Ready(manifest)
        }
    }

    private fun signed(manifest: ByteArray, signature: ByteArray) = trusted.any { Ed25519.verify(it, manifest, signature) }

    /**
     * Imports the pack [input] reads (a zip: manifest, signature, then the files), told how far it
     * got by [progress] (bytes copied of the pack's size). Nothing in use changes unless all of it
     * checks. [input] is not closed.
     */
    @Synchronized
    fun import(input: InputStream, progress: (copied: Long, total: Long) -> Unit = { _, _ -> }): ImportResult = try {
        importZip(ZipInputStream(input), progress)
    } catch (e: Refused) {
        ImportResult.Refused(e.why)
    } catch (e: IOException) {
        ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        // the zip itself malformed past what ZipException covers: an entry's name not UTF-8 is
        // ZipInputStream's IllegalArgumentException, which crashed the app's scope (and process)
        ImportResult.Refused(Refusal.NotAPack(e.message ?: e.javaClass.simpleName))
    }

    private class Refused(val why: Refusal, cause: Throwable? = null) : Exception(cause)

    private fun refuse(why: Refusal, cause: Throwable? = null): Nothing = throw Refused(why, cause)

    private fun importZip(zip: ZipInputStream, progress: (Long, Long) -> Unit): ImportResult {
        val manifestBytes = head(zip, PackManifest.NAME, PackManifest.MAX_BYTES)
        val signature = head(zip, PackManifest.SIGNATURE, Ed25519.SIGNATURE_SIZE)
        // what the manifest says is read only once it is known to be ours
        if (!signed(manifestBytes, signature)) {
            val key = runCatching { PackManifest.parse(manifestBytes).key }.getOrDefault("")
            refuse(Refusal.BadSignature(key))
        }
        val manifest = try {
            PackManifest.parse(manifestBytes)
        } catch (e: IllegalArgumentException) {
            refuse(Refusal.NotAPack(e.message.orEmpty()), e)
        }
        val status = judge(manifest)
        if (status !is PackStatus.Ready) refuse(Refusal.Incompatible(manifest, requirements.getValue(manifest.kind)))
        val before = state.getValue(manifest.kind).first
        val installed = when (before) {
            is PackStatus.Ready -> before.manifest
            is PackStatus.NeedsUpdate -> before.manifest
            is PackStatus.NeedsNewerApp -> before.manifest
            is PackStatus.Damaged -> before.manifest
            PackStatus.Missing -> null
        }
        // the same pack: nothing to copy, if what is in use still is it (its files read through
        // again: a start reads only their sizes); else copied anew, the import mending it
        if (before is PackStatus.Ready && before.manifest.sameAs(manifest)) {
            val gen = state.getValue(manifest.kind).second
            if (gen != null && intact(before.manifest, gen)) return ImportResult.AlreadyThere(manifest)
        }

        val dir = roomFor(manifest)
        val staging = File(dir, "$STAGING${System.nanoTime()}")
        try {
            copyFiles(zip, manifest, staging, progress)
            File(staging, PackManifest.NAME).writeSynced(manifestBytes)
            File(staging, PackManifest.SIGNATURE).writeSynced(signature)
            val gen = File(dir, "g${System.currentTimeMillis()}-${System.nanoTime() % GEN_SPREAD}")
            staging.renameOrFail(gen)
            val pointer = File(dir, "$CURRENT.tmp")
            pointer.writeSynced(gen.name.toByteArray())
            // the one step that changes which pack is used
            pointer.renameOrFail(File(dir, CURRENT))
            state = state + (manifest.kind to (PackStatus.Ready(manifest) to gen))
            // the old generation, mapped by whoever still uses it: its pages stay till they let go
            dir.listFiles()?.forEach { if (it.name != CURRENT && it.name != gen.name) it.deleteRecursively() }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return ImportResult.Imported(manifest, installed)
    }

    // the pack's directory, with room for [manifest]'s files (and [margin] more) on its storage
    private fun roomFor(manifest: PackManifest): File {
        val dir = File(root, manifest.kind.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
        val need = manifest.size + margin
        val free = dir.usableSpace
        if (free < need) refuse(Refusal.NoSpace(need, free))
        return dir
    }

    private fun File.renameOrFail(to: File) {
        if (!renameTo(to)) throw IOException("cannot rename $this to $to")
    }

    // the entry [name], first of what is left, at most [max] bytes
    private fun head(zip: ZipInputStream, name: String, max: Int): ByteArray {
        val entry = zip.nextEntry ?: refuse(Refusal.NotAPack("empty"))
        if (entry.name != name) refuse(Refusal.NotAPack("no $name"))
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) refuse(Refusal.NotAPack("$name too large"))
        }
        return out.toByteArray()
    }

    private fun copyFiles(zip: ZipInputStream, manifest: PackManifest, staging: File, progress: (Long, Long) -> Unit) {
        val listed = manifest.files.associateBy { it.path }
        val seen = HashSet<String>()
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        for (entry in generateSequence { zip.nextEntry }.filterNot { it.isDirectory }) {
            val file = listed[entry.name] ?: refuse(Refusal.BadFile(entry.name))
            if (!seen.add(file.path)) refuse(Refusal.BadFile(file.path))
            val target = File(staging, file.path)
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot make $it") }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(target).use { out ->
                while (true) {
                    // a file's bytes changed after it was made fail the zip's CRC before the hash: the same file wrong
                    val n = try {
                        zip.read(buffer)
                    } catch (e: ZipException) {
                        refuse(Refusal.BadFile(file.path), e)
                    }
                    if (n < 0) break
                    size += n
                    // no more than it says: a pack cannot fill the storage past its size
                    if (size > file.size) refuse(Refusal.BadFile(file.path))
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    copied += n
                    progress(copied, manifest.size)
                }
                out.fd.sync()
            }
            if (size != file.size || PackManifest.hex(digest.digest()) != file.sha256) refuse(Refusal.BadFile(file.path))
        }
        manifest.files.firstOrNull { it.path !in seen }?.let { refuse(Refusal.BadFile(it.path)) }
    }

    private fun File.writeSynced(bytes: ByteArray) = FileOutputStream(this).use {
        it.write(bytes)
        it.fd.sync()
    }

    companion object {
        const val CURRENT = "current"
        private const val STAGING = ".staging-"
        private const val BUFFER = 1 shl 16
        private const val GEN_SPREAD = 1_000_000L

        /** Kept free beyond a pack's size: the phone's own need, the engine's user files growing. */
        const val DEFAULT_MARGIN = 32L shl 20
    }
}
