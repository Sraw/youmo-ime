/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import org.fcitx.fcitx5.android.core.CoreLog
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

sealed class PackStatus {
    object Missing : PackStatus()

    class Ready(val manifest: PackManifest) : PackStatus()

    /** This APK cannot use it: a pack of [needed]'s format, at least its version, wanted. */
    class NeedsUpdate(val manifest: PackManifest, val needed: PackRequirement) : PackStatus()

    /** Of a newer format than this APK reads: the app needs updating, not the pack. */
    class NeedsNewerApp(val manifest: PackManifest, val needed: PackRequirement) : PackStatus()

    /** Its files no longer match [manifest]: marked in its generation, not used until imported again. */
    class Damaged(val manifest: PackManifest) : PackStatus()
}

enum class VerifyDue {
    NONE,

    /** The first start after an import. */
    UNVERIFIED,

    /** Or at a time the clock has not reached. */
    STALE,
}

sealed class Refusal {
    /** No manifest and signature first, or a manifest that does not read. */
    class NotAPack(val detail: String) : Refusal()

    /** [key]: the manifest's name of its key. */
    class BadSignature(val key: String) : Refusal()

    class BadFile(val path: String) : Refusal()

    class Incompatible(val manifest: PackManifest, val needed: PackRequirement) : Refusal()

    class NoSpace(val need: Long, val free: Long) : Refusal()

    class Unreadable(val detail: String) : Refusal()
}

sealed class ImportResult {
    class Imported(val manifest: PackManifest, val replaced: PackManifest?) : ImportResult()

    class AlreadyThere(val manifest: PackManifest) : ImportResult()

    class Refused(val why: Refusal) : ImportResult()

    /** Kept as the user asked: nothing copied. */
    class KeptNewer(val manifest: PackManifest, val installed: PackManifest) : ImportResult()
}

/**
 * The imported packs under [root] (device-protected: the keyboard is direct-boot aware). A pack is
 * `root/<id>/<generation>/`; `root/<id>/current` names the one in use. An import copies into staging,
 * checking every byte against the signed manifest, then renames it and replaces `current` atomically,
 * so a kill at any point leaves the old pack in use; [load] deletes leftovers. The replaced generation
 * stays till [sweep], since the host still reads it until restarted. A `verified` or `damaged` file in a
 * generation records [verify]'s result.
 */
class PackShelf(
    private val root: File,
    private val trusted: List<ByteArray>,
    private val requirements: Map<PackKind, PackRequirement>,
    private val margin: Long = DEFAULT_MARGIN,
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterRead: (PackKind) -> Unit = {},
    // a test's hook: a file in use opened to be read through (one failing as a disk does)
    private val openFile: (File) -> InputStream = { it.inputStream() },
    private val syncDir: (File) -> Unit = ::syncDirectory,
) {
    init {
        require(trusted.isNotEmpty()) { "no trusted key" }
        require(trusted.all { it.size == Ed25519.KEY_SIZE }) { "a trusted key is not 32 bytes" }
        require(requirements.keys == PackKind.entries.toSet()) { "a requirement for each pack" }
    }

    private data class Held(val status: PackStatus, val dir: File? = null, val verifiedAt: Long? = null)

    @Volatile
    private var state: Map<PackKind, Held> = PackKind.entries.associateWith { Held(PackStatus.Missing) }

    fun status(kind: PackKind): PackStatus = state.getValue(kind).status

    fun dir(kind: PackKind): File? = state.getValue(kind).dir

    /** Null if not read through since its import. */
    fun verifiedAt(kind: PackKind): Long? = state.getValue(kind).verifiedAt

    /** Once after import (the copy was checked as written, not as kept), then every [VERIFY_EVERY_MS]. */
    fun due(kind: PackKind): VerifyDue {
        val held = state.getValue(kind)
        if (held.status !is PackStatus.Ready) return VerifyDue.NONE
        val at = held.verifiedAt ?: return VerifyDue.UNVERIFIED
        val now = clock()
        // a time the clock has not reached: the clock was set back
        return if (now - at > VERIFY_EVERY_MS || at > now + CLOCK_SLACK_MS) VerifyDue.STALE else VerifyDue.NONE
    }

    /**
     * Reads which packs are there, deleting what an import left behind. Manifests are checked against the keys,
     * files by size only (hashing hundreds of MB at every start costs seconds; [verify] reads them later).
     * With [tidy] false the leftovers stay for [tidy], so the main thread waiting on this never waits on deletes.
     */
    @Synchronized
    fun load(tidy: Boolean = true) {
        state = PackKind.entries.associateWith { kind -> loadOne(kind) }
        if (tidy) tidy()
    }

    @Synchronized
    fun tidy() {
        for (kind in PackKind.entries) {
            val dir = File(root, kind.id)
            // a pointer that cannot be read now (an I/O error): nothing deleted
            val current = when (val read = currentOf(dir)) {
                Current.Unreadable -> continue
                is Current.Named -> read.name
                Current.None -> null
            }
            dir.listFiles()?.forEach { if (it.name != CURRENT && it.name != current) it.deleteRecursively() }
        }
    }

    private fun loadOne(kind: PackKind): Held {
        val dir = File(root, kind.id)
        // a pointer that cannot be read now (an I/O error): no pack this time, nothing deleted
        val current = when (val read = currentOf(dir)) {
            Current.Unreadable -> return Held(PackStatus.Missing)
            is Current.Named -> read.name
            Current.None -> null
        }
        if (current == null) return Held(PackStatus.Missing)
        val gen = File(dir, current)
        // unreadable, unsigned or unparsable: as good as none, imported anew
        val manifest = runCatching {
            val bytes = File(gen, PackManifest.NAME).readBytes()
            val signature = File(gen, PackManifest.SIGNATURE).readBytes()
            PackManifest.parse(bytes).takeIf { signed(bytes, signature) }
        }.getOrNull() ?: return Held(PackStatus.Missing)
        if (manifest.kind != kind) return Held(PackStatus.Missing)
        if (File(gen, DAMAGED).exists() || manifest.files.any { File(gen, it.path).let { f -> !f.isFile || f.length() != it.size } }) {
            return Held(PackStatus.Damaged(manifest))
        }
        val status = judge(manifest)
        if (status !is PackStatus.Ready) return Held(status)
        // unreadable or half written (a power cut): read through again
        val verifiedAt = runCatching { File(gen, VERIFIED).readText().trim().toLongOrNull() }.getOrNull()
        return Held(status, gen, verifiedAt)
    }

    /** What an import replaced, once its readers moved to the new one (the host restarted); [load] does it at a start. */
    @Synchronized
    fun sweep() {
        for (kind in PackKind.entries) {
            val dir = File(root, kind.id)
            // none, unreadable or not a generation's name: nothing is known to be unused
            val current = (currentOf(dir) as? Current.Named)?.name ?: continue
            dir.listFiles()?.forEach { if (it.name != CURRENT && it.name != current) it.deleteRecursively() }
        }
    }

    private sealed interface Current {
        object None : Current
        object Unreadable : Current
        class Named(val name: String) : Current
    }

    private fun currentOf(dir: File): Current {
        val pointer = File(dir, CURRENT)
        if (!pointer.isFile) return Current.None
        val text = try {
            pointer.readText().trim()
        } catch (e: IOException) {
            CoreLog.e("pack pointer $pointer unreadable: ${e.javaClass.simpleName}")
            return Current.Unreadable
        }
        return if (PackManifest.safePath(text) && '/' !in text) Current.Named(text) else Current.None
    }

    /**
     * Hashes [kind]'s files in use against its manifest; a change marks it [PackStatus.Damaged] (true), a
     * match marks it verified. An I/O error other than absence says nothing: logged, retried at the next due.
     * Not under the shelf's lock (it is long): an import meanwhile is left as it is.
     */
    fun verify(kind: PackKind): Boolean {
        val held = state.getValue(kind)
        val status = held.status
        val gen = held.dir
        if (status !is PackStatus.Ready || gen == null) return false
        val read = read(kind, status.manifest, gen) { _, _ -> }
        afterRead(kind)
        synchronized(this) {
            // an import came between: what was read is no longer what is used
            if (state.getValue(kind).dir != gen) return false
            when (read) {
                Read.INTACT -> state = state + (kind to held.copy(verifiedAt = markVerified(gen)))
                Read.DAMAGED -> {
                    mark(gen, DAMAGED, "damaged".toByteArray())
                    state = state + (kind to Held(PackStatus.Damaged(status.manifest)))
                }
                Read.UNKNOWN -> Unit
            }
        }
        return read == Read.DAMAGED
    }

    private enum class Read { INTACT, DAMAGED, UNKNOWN }

    // one gone is damage, one unreadable says nothing. FileNotFoundException is also EACCES or EMFILE
    // for a file that is there, so it says nothing either
    private fun read(kind: PackKind, manifest: PackManifest, gen: File, progress: (Long, Long) -> Unit): Read {
        var file: File? = null
        return try {
            val buffer = ByteArray(BUFFER)
            var done = 0L
            val same = manifest.files.all { entry ->
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                openFile(File(gen, entry.path).also { file = it }).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        done += n
                        digest.update(buffer, 0, n)
                        progress(done, manifest.size)
                    }
                }
                size == entry.size && PackManifest.hex(digest.digest()) == entry.sha256
            }
            if (same) Read.INTACT else Read.DAMAGED
        } catch (e: FileNotFoundException) {
            if (file?.exists() == false) Read.DAMAGED else unread(kind, e)
        } catch (e: IOException) {
            unread(kind, e)
        }
    }

    private fun unread(kind: PackKind, e: IOException): Read {
        CoreLog.e("pack ${kind.id} not read through: ${e.javaClass.simpleName}")
        return Read.UNKNOWN
    }

    // not written (the storage refusing): the next start reads it through again
    private fun markVerified(gen: File): Long {
        val now = clock()
        mark(gen, VERIFIED, now.toString().toByteArray())
        return now
    }

    // what cannot be written is logged: a lost damaged mark, the next verify finds it again
    private fun mark(gen: File, name: String, bytes: ByteArray) {
        try {
            File(gen, name).writeSynced(bytes)
        } catch (e: IOException) {
            CoreLog.e("pack mark $name not written: ${e.javaClass.simpleName}")
        }
    }

    private fun judge(manifest: PackManifest): PackStatus {
        val needed = requirements.getValue(manifest.kind)
        return when {
            manifest.format > needed.format -> PackStatus.NeedsNewerApp(manifest, needed)
            manifest.format < needed.format || manifest.version < needed.minVersion -> PackStatus.NeedsUpdate(manifest, needed)
            else -> PackStatus.Ready(manifest)
        }
    }

    fun usable(manifest: PackManifest): Boolean = judge(manifest) is PackStatus.Ready

    private fun signed(manifest: ByteArray, signature: ByteArray) = trusted.any { Ed25519.verify(it, manifest, signature) }

    /** Imports the zip [input] reads (manifest, signature, files); nothing in use changes unless all of it checks. [input] is not closed. */
    @Synchronized
    fun import(input: InputStream, progress: (copied: Long, total: Long) -> Unit = { _, _ -> }): ImportResult = try {
        // the zip closed (its inflater's memory freed at once), [input] not
        ZipInputStream(object : FilterInputStream(input) {
            override fun close() = Unit
        }).use { importZip(it, progress) }
    } catch (e: Refused) {
        ImportResult.Refused(e.why)
    } catch (e: IOException) {
        ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
    } catch (e: IllegalArgumentException) {
        // an entry's name not UTF-8 is ZipInputStream's IllegalArgumentException, which crashed the app's scope
        ImportResult.Refused(Refusal.NotAPack(e.message ?: e.javaClass.simpleName))
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        // the provider's stream failing otherwise (a SecurityException): not read, not "no pack"
        CoreLog.e("pack import failed: ${e.javaClass.simpleName}")
        ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
    }

    fun installed(kind: PackKind): PackManifest? = when (val status = state.getValue(kind).status) {
        is PackStatus.Ready -> status.manifest
        is PackStatus.NeedsUpdate -> status.manifest
        is PackStatus.NeedsNewerApp -> status.manifest
        is PackStatus.Damaged -> status.manifest
        PackStatus.Missing -> null
    }

    /** Only the head read and checked as ours; null where [import] would refuse. [input] is not closed. */
    fun manifestOf(input: InputStream): PackManifest? = try {
        ZipInputStream(object : FilterInputStream(input) {
            override fun close() = Unit
        }).use { zip ->
            val manifestBytes = head(zip, PackManifest.NAME, PackManifest.MAX_BYTES)
            val signature = head(zip, PackManifest.SIGNATURE, Ed25519.SIGNATURE_SIZE)
            if (signed(manifestBytes, signature)) PackManifest.parse(manifestBytes) else null
        }
    } catch (_: Refused) {
        null
    } catch (_: IOException) {
        null
    } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
        // a malformed zip or a failing stream: said by the import that follows
        null
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
        val before = state.getValue(manifest.kind).status
        val installed = installed(manifest.kind)
        // the same pack: nothing copied if its files still hash right; else (or unreadable) copied anew to mend it
        if (before is PackStatus.Ready && before.manifest.sameAs(manifest)) {
            val held = state.getValue(manifest.kind)
            val gen = held.dir
            if (gen != null && read(manifest.kind, before.manifest, gen, progress) == Read.INTACT) {
                state = state + (manifest.kind to held.copy(verifiedAt = markVerified(gen)))
                return ImportResult.AlreadyThere(manifest)
            }
        }

        val dir = roomFor(manifest)
        val staging = File(dir, "$STAGING${System.nanoTime()}")
        // deleted if the import stops before `current` names it
        var unused: File? = null
        try {
            copyFiles(zip, manifest, staging, progress)
            File(staging, PackManifest.NAME).writeSynced(manifestBytes)
            File(staging, PackManifest.SIGNATURE).writeSynced(signature)
            val gen = File(dir, "g${System.currentTimeMillis()}-${System.nanoTime() % GEN_SPREAD}")
            unused = gen
            staging.renameOrFail(gen)
            // sync the directory, or a power cut could leave `current` naming a generation whose rename was lost
            syncDir(dir)
            val pointer = File(dir, "$CURRENT.tmp")
            pointer.writeSynced(gen.name.toByteArray())
            // the one step that changes which pack is used
            pointer.renameOrFail(File(dir, CURRENT))
            unused = null
            syncDir(dir)
            // not marked verified: read through at the next start
            state = state + (manifest.kind to Held(PackStatus.Ready(manifest), gen))
            // the old generation kept: the engine is moved to the new one first, then [sweep] deletes it
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            unused?.takeIf { it.exists() }?.deleteRecursively()
        }
        return ImportResult.Imported(manifest, installed)
    }

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
        /** `fsync` on the directory opened read-only, as Linux allows; false where a JVM refuses (then the FS's ordering is all). */
        fun syncDirectory(dir: File): Boolean = try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            true
        } catch (_: IOException) {
            false
        }

        const val CURRENT = "current"

        const val VERIFIED = "verified"

        const val DAMAGED = "damaged"

        /** Read through again after this (at a start, charging). */
        const val VERIFY_EVERY_MS = 30L * 24 * 60 * 60 * 1000

        private const val CLOCK_SLACK_MS = 24L * 60 * 60 * 1000
        private const val STAGING = ".staging-"
        private const val BUFFER = 1 shl 16
        private const val GEN_SPREAD = 1_000_000L

        /** Kept free beyond a pack's size: the phone's own need, the engine's user files growing. */
        const val DEFAULT_MARGIN = 32L shl 20
    }
}
