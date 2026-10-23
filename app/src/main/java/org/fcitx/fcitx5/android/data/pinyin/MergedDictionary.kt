/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/**
 * The dictionaries merged into the base, turned on, compiled into one file the engine maps
 * ([FILE], a [DictionaryFile]): never parsed as text at the engine's start, where 0.3.1 ran the
 * heap out on a 1.9-million-word one at every focus. The texts stay beside it as its sources,
 * which an older build reads and a backup carries; the file is made again from them whenever
 * which are on, or one of them, changes (its stamp), on one thread of its own for the process,
 * so an import, a change on the page and the engine's start never compile at once. Until a
 * compile is done the engine goes on with the file it had, or without the merged words; then it
 * is reloaded ([made]).
 */
object MergedDictionary {

    /** The compiled file, among the dictionaries. */
    const val FILE = "merged.ymd"

    // a change to how the file is compiled (scores, what is kept) that a file made before must be made again for
    private const val REVISION = 1

    private class Opened(val stamp: String, val file: DictionaryFile)

    /** What the engine is handed at once: the file in use (null for none yet) and [token], which changes with it. */
    class Seen(val file: DictionaryFile?, val token: String, val stale: Boolean = false)

    // set from the fcitx thread (a fresh start's file, mapped) and the compile thread: the first only if none is yet (review NIT)
    private val opened = AtomicReference<Opened?>()

    // the one compile at a time, with at most one more waiting: a change made while one runs is
    // compiled after it, from the files as they are then
    private val compiles: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "dictionary-compile").apply { isDaemon = true } }
    private val queued = AtomicBoolean()

    // stamps that failed to compile (the disk full, too many words): not tried again at each
    // reload, nor at each start (kept in [FAILED] beside them), but for other sources or another
    // base, or when the 词库 page opens ([retry]), as the failure's notice says
    private val failed = HashSet<String>()

    private const val FAILED = ".merged.failed"

    private fun failedBefore(dir: File, stamp: String): Boolean =
        synchronized(failed) { stamp in failed } || runCatching { File(dir, FAILED).readText().trim() == stamp }.getOrDefault(false)

    private fun fail(dir: File, stamp: String) {
        synchronized(failed) { failed += stamp }
        runCatching { File(dir, FAILED).writeText(stamp) }.onFailure { Timber.w(it, "%s", FAILED) }
    }

    private var swept = false

    /** What a compile is for: the first after an upgrade from 0.3.1's texts, or a change since. */
    enum class Kind { UPGRADE, UPDATE }

    /** A compile under way: [percent] of it done, at [stage]. */
    data class Work(val kind: Kind, val stage: DictionaryCompiler.Stage, val percent: Int)

    private val working = MutableStateFlow<Work?>(null)

    /** The compile under way, for the keyboard and the page to show; null while there is none. */
    val work: StateFlow<Work?> = working.asStateFlow()

    private val ended = MutableSharedFlow<Pair<Kind, Boolean>>(extraBufferCapacity = 4)

    /** Each compile done, not cancelled: what it was for, and whether it made the file (the engine has it then). */
    val done: SharedFlow<Pair<Kind, Boolean>> = ended.asSharedFlow()

    /** Told each step [work] takes, on the compile thread: a test's hook. */
    @VisibleForTesting
    internal var told: (Work) -> Unit = {}

    /** The base dictionary words are split off against: its fingerprint, and whether it has a word read so. */
    class Base(val fingerprint: String, val has: () -> (String, IntArray) -> Boolean)

    /**
     * The pinyin data typing reads, mapped from the app's assets: a word it has is kept out of the
     * merged file's trie, offered from the base alone; none in a test (no assets).
     */
    @Volatile
    var base: () -> Base? = { assetBase }

    private val assetBase by lazy { assetBase() }

    private fun mapped(): ByteBuffer = appContext.assets.openFd(Engines.PINYIN_DATA).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
    }

    // its length and a CRC of its ends (the header, the last section's tail): another build's data
    // of the same length is told apart without reading its 100 MB through
    private fun assetBase(): Base? = try {
        val data = mapped()
        val crc = CRC32()
        crc.update(data.duplicate().apply { limit(minOf(FINGERPRINT_BYTES, capacity())) })
        crc.update(data.duplicate().apply { position(maxOf(0, capacity() - FINGERPRINT_BYTES)) })
        Base("${Engines.PINYIN_DATA} ${data.capacity()} ${crc.value}") { DictionaryCompiler.inBase(PinyinData.load(mapped(), verify = false)) }
    } catch (e: IOException) {
        Timber.w(e, "pinyin data: merged words not split off the base")
        null
    }

    private const val FINGERPRINT_BYTES = 64 shl 10

    /** Told once the engine has a file it did not: the engine read again. */
    @Volatile
    var made: () -> Unit = { FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadEngine() } }

    // a reload not run (fcitx disconnected meanwhile) is no failure of the compile: the next additions pick the file up
    private fun tellMade() {
        runCatching { made() }.onFailure { Timber.w(it, "merged dictionaries: engine not told") }
    }

    /** The dictionaries in [dir] turned on: the sources, by name. */
    fun sources(dir: File): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".${PinyinDictionary.Type.Text.ext}") }
        .orEmpty().sortedBy { it.name }

    /**
     * What [sources] are, as their names, sizes and times, with the base they are split off and
     * the compiler's revision: a change to one, to which are on, or a new base dictionary or
     * compiler (an update of the app) is another.
     */
    fun stamp(sources: List<File>, base: Base? = this.base()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$REVISION ${base?.fingerprint}\n".toByteArray())
        for (f in sources) digest.update("${f.name}\u0000${f.length()}\u0000${f.lastModified()}\n".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The file for the engine and its name, from one read (a compile landing between two would
     * leave the engine told of a file it does not have): never compiled nor read through here, on
     * the fcitx thread. The file on disk of the sources as they are is mapped at once at a fresh
     * start, checked through after, off the thread. One of other sources than [dir]'s now (or none
     * yet) has a compile queued, and is used until it is done.
     */
    fun current(dir: File): Seen {
        val sources = sources(dir)
        if (sources.isEmpty()) {
            // a file left by a kill after the last was deleted: deleted then
            if (opened.get() != null || File(dir, FILE).exists()) refresh(dir)
            val now = opened.get()
            return Seen(now?.file, now?.let { "$FILE ${it.stamp}" }.orEmpty())
        }
        val base = base()
        val stamp = stamp(sources, base)
        val now = opened.get() ?: mapUnchecked(File(dir, FILE), stamp)
        if (now?.stamp != stamp) refresh(dir)
        // a file of another base dictionary (an update's, its compile not done) in use meanwhile
        val stale = now != null && now.file.base != base?.fingerprint.orEmpty()
        return Seen(now?.file, now?.let { "$FILE ${it.stamp}" } ?: sources.joinToString(" ") { it.name }, stale)
    }

    // the file there if it is of [stamp], mapped without reading it through (its header only),
    // its CRCs checked on the compile thread: one damaged is dropped and made again
    private fun mapUnchecked(dest: File, stamp: String): Opened? {
        if (!dest.isFile) return null
        val file = try {
            DictionaryFile.open(dest, verify = false)
        } catch (e: IOException) {
            Timber.w(e, "%s: not mapped", FILE)
            return null
        }
        if (file.stamp != stamp) return null
        val mapped = Opened(stamp, file)
        // a compile landed meanwhile: its file, newer, stays
        if (!opened.compareAndSet(null, mapped)) return opened.get()
        compiles.submit {
            try {
                DictionaryFile.open(dest)
            } catch (e: IOException) {
                Timber.w(e, "%s: damaged, made again", FILE)
                opened.compareAndSet(mapped, null)
                dest.delete()
                tellMade()
                refresh(dest.parentFile!!)
            }
        }
        return mapped
    }

    /**
     * Whether [dir]'s dictionaries turned on are not all in the file in use yet: compiled, or one
     * failed. A word they may have is not then known to be none of theirs.
     */
    fun pending(dir: File): Boolean {
        val sources = sources(dir)
        return sources.isNotEmpty() && opened.get()?.stamp != stamp(sources)
    }

    /**
     * Compiles [dir]'s dictionaries turned on into [FILE] if it is not of them, on the compile
     * thread, after the one running; [made] told if the engine has another file then. One
     * already waiting does for this: it reads the files as they are when it runs.
     */
    fun refresh(dir: File): Future<*>? {
        if (!queued.compareAndSet(false, true)) return null
        return compiles.submit {
            queued.set(false)
            try {
                make(dir, forImport = false, AtomicBoolean())
            } catch (e: IOException) {
                Timber.w(e, "merged dictionaries")
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // a fault of the compile's (its OOM is told as an IOException): logged, the engine goes on with what it has
                if (e !is CancellationException) Timber.e(e, "merged dictionaries")
            }
        }
    }

    /** [refresh] again what failed in this process: the 词库 page opened, as the failure's notice says. */
    fun retry(dir: File): Future<*>? {
        synchronized(failed) { failed.clear() }
        File(dir, FAILED).delete()
        // one queued already (a check's) runs after the clear: waited for through a step behind it, on the one thread
        return refresh(dir) ?: compiles.submit {}
    }

    /**
     * [refresh] and wait for it: an import's, which tells of its words once they are in.
     * [cancel] set (before it starts, or while it runs) stops it, this one and no other.
     *
     * @throws IOException if the file could not be made
     * @throws CancellationException if [cancel] stopped it: the file there stays
     */
    fun refreshNow(dir: File, cancel: AtomicBoolean = AtomicBoolean()) {
        // one waiting may have read the files before this change: another after it
        try {
            compiles.submit<Unit> { make(dir, forImport = true, cancel) }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // [forImport]: an import's, an UPDATE whose failure its caller shows; else told in [done], an
    // upgrade if none was made yet from dictionaries an older build left
    private fun make(dir: File, forImport: Boolean, cancel: AtomicBoolean) {
        sweep(dir)
        if (cancel.get()) throw CancellationException("cancelled")
        val sources = sources(dir)
        val dest = File(dir, FILE)
        if (sources.isEmpty()) {
            if (dest.exists() && !dest.delete()) Timber.w("%s: cannot delete it", FILE)
            // the tables' indexes of its words too
            dir.listFiles { f -> f.name.startsWith("merged.") && f.name.endsWith(".ymc") }?.forEach { it.delete() }
            if (opened.get() != null) {
                opened.set(null)
                tellMade()
            }
            return
        }
        val base = base()
        val stamp = stamp(sources, base)
        if (opened.get()?.stamp == stamp) return
        val firstOpen = !forImport && opened.get() == null
        val kind = if (firstOpen && !dest.exists() && fromOlderBuild(sources, installedAt())) Kind.UPGRADE else Kind.UPDATE
        // what is on disk, for the engine at once if it has none yet: stale, its words are still right
        val there = openOrDelete(dest)
        if (there != null && (opened.get() == null || there.stamp == stamp)) {
            opened.set(Opened(there.stamp, there))
            tellMade()
            if (there.stamp == stamp) return
        }
        if (failedBefore(dir, stamp)) {
            // failed before: not compiled again till retried; an import that asked for it fails (its caller tells), a reload goes on quietly
            if (forImport) throw IOException("$FILE: failed to compile before, not tried again till retried")
            return
        }
        compile(sources, dest, stamp, base, kind, cancel, tells = !forImport)
        File(dir, FAILED).delete()
    }

    // the dictionaries there before this build was put on: an older one's (0.3.1's), upgraded;
    // one imported or restored since is no upgrade
    private fun fromOlderBuild(sources: List<File>, installedAt: Long?): Boolean = installedAt != null && sources.any { it.lastModified() < installedAt }

    private val packageInstalledAt: () -> Long? = {
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }.getOrNull()
    }

    /** When this build was put on; null if it cannot be told (no upgrade then). A test's hook, as [base] and [made]. */
    @VisibleForTesting
    internal var installedAt: () -> Long? = packageInstalledAt

    // [sources] compiled into [dest], told as [kind] in [work] and [done] as it goes; the engine told then
    // [tells]: the end told in [done]; not for an import's compile, whose caller shows its own failure (one dialog, review)
    private fun compile(sources: List<File>, dest: File, stamp: String, base: Base?, kind: Kind, cancel: AtomicBoolean, tells: Boolean) {
        val total = sources.sumOf { it.length() }.coerceAtLeast(1)
        var read = 0L
        // each source's stream, closed at the end however it ends: one stopped halfway (a cancel, a
        // failure) is not read to its end, where useLines would close it
        val inputs = ArrayList<FileInputStream>()
        // a source turned off or deleted as it was compiled: no failure, the compile queued after does (review)
        var superseded = false
        fun tell(stage: DictionaryCompiler.Stage, share: Float) {
            if (cancel.get()) throw CancellationException("cancelled")
            val percent = (stageShare(stage).let { (from, to) -> from + (to - from) * share.coerceIn(0f, 1f) }).toInt()
            if (working.value?.percent != percent || working.value?.stage != stage) working.value = Work(kind, stage, percent).also(told)
        }
        tell(DictionaryCompiler.Stage.READ, 0f)
        var ok = false
        try {
            val result = DictionaryCompiler.compile(
                sources.map { f ->
                    sequence {
                        // the bytes read tell how far: the lines are of no length known beforehand
                        val input = object : FilterInputStream(FileInputStream(f).also { inputs += it }) {
                            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also {
                                if (it > 0) read += it
                            }
                        }
                        yieldAll(input.bufferedReader().lineSequence())
                    }
                },
                dest,
                stamp = stamp,
                base = base?.fingerprint.orEmpty(),
                inBase = base?.has?.invoke() ?: { _, _ -> false },
                progress = { stage, done, of ->
                    tell(stage, if (stage == DictionaryCompiler.Stage.READ) read.toFloat() / total else done.toFloat() / maxOf(of, 1))
                },
                names = sources.map { PinyinDictionary.nameOf(it.name) },
            )
            Timber.i("merged dictionaries: %d words of %d files, %d of the base, %d lines unread", result.words, sources.size, result.inBase, result.unread)
            opened.set(Opened(stamp, DictionaryFile.open(dest)))
            ok = true
            tellMade()
        } catch (e: IOException) {
            val dir = dest.parentFile!!
            superseded = stamp(sources(dir), base) != stamp
            if (superseded) refresh(dir) else fail(dir, stamp)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            if (e !is CancellationException) fail(dest.parentFile!!, stamp)
            throw e
        } finally {
            inputs.forEach { runCatching { it.close() } }
            working.value = null
            // a cancel is no failure to tell of: what was asked for was not to have it
            if (tells && !cancel.get() && !superseded) ended.tryEmit(kind to ok)
        }
    }

    // each stage's share of the whole, in percent: reading and sorting the lines is most of it
    private fun stageShare(stage: DictionaryCompiler.Stage) = when (stage) {
        DictionaryCompiler.Stage.READ -> 0f to 60f
        DictionaryCompiler.Stage.MERGE -> 60f to 90f
        DictionaryCompiler.Stage.WRITE -> 90f to 100f
    }

    // a file that is damaged, cut short or of another build is deleted: compiled again
    private fun openOrDelete(dest: File): DictionaryFile? {
        if (!dest.exists()) return null
        return try {
            DictionaryFile.open(dest)
        } catch (e: IOException) {
            Timber.w(e, "%s: compiled again", FILE)
            dest.delete()
            null
        }
    }

    // what a compile or an import killed halfway left (the process killed, the phone off): the
    // compiler makes its own anew, but one not run again (its dictionaries deleted since) would stay
    private fun sweep(dir: File) {
        if (swept) return
        swept = true
        // this file's own, not a table index's beside it (merged.<im>.ymc), which its build sweeps
        dir.listFiles { f -> FILE in f.name && DictionaryCompiler.isWorkFile(f.name) }?.forEach { it.deleteRecursively() }
        dir.listFiles { f -> f.name.endsWith(".${PinyinDictionary.Type.Text.ext}.tmp") }?.forEach { it.delete() }
    }

    /** Whether [file] is made from the dictionaries, or half of one, not one: left out of a backup, made again after a restore. */
    fun isCompiled(file: File): Boolean {
        val name = file.name
        return name.endsWith(".ymd") || name.endsWith(".ymc") || DictionaryCompiler.isWorkFile(name) ||
            name.endsWith(".${PinyinDictionary.Type.Text.ext}.tmp") || name == FAILED ||
            generateSequence(file.parentFile) { it.parentFile }.any { DictionaryCompiler.isWorkFile(it.name) }
    }

    /** How many words each dictionary on had at the last compile, by name: the file's header, for the page. */
    fun sourceWords(dir: File): Map<String, Int> = DictionaryCompiler.sourceWords(File(dir, FILE))

    /** All of it as at the process's start: tests share the one object. */
    @VisibleForTesting
    internal fun reset() {
        compiles.submit {}.get()
        opened.set(null)
        synchronized(failed) { failed.clear() }
        swept = false
        queued.set(false)
        working.value = null
        told = {}
        installedAt = packageInstalledAt
    }
}
