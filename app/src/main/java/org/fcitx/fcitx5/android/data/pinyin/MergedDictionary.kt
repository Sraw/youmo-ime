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
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.runtime.BackgroundWork
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.pack.PackKind
import timber.log.Timber
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipException

/**
 * The enabled imported dictionaries compiled into one mapped file: parsing text at engine start ran the heap out on
 * large ones. Rebuilt on one process-wide thread when the gzipped sources change; the engine keeps its old file till then.
 */
object MergedDictionary {

    const val FILE = "merged.ymd"

    // bump when compiling changes so older files are rebuilt
    private const val REVISION = 2

    private class Opened(val stamp: String, val file: DictionaryFile)

    class Seen(val file: DictionaryFile?, val token: String, val stale: Boolean = false)

    // set from the fcitx thread (a fresh start's map) and the compile thread; the former only if none is yet
    private val opened = AtomicReference<Opened?>()

    // one at a time, at most one waiting, which reads the files as they are when it runs
    private val compiles: ExecutorService = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { Thread(it, "dictionary-compile").apply { isDaemon = true } }) {
        override fun execute(command: Runnable) = BackgroundWork.counted(Executor { super.execute(it) }).execute(command)
    }
    private val queued = AtomicBoolean()

    // failed stamps are not retried at each start (persisted in [FAILED]), only for other sources or when 词库 opens
    private val failed = HashSet<String>()

    private const val FAILED = ".merged.failed"

    private fun failedBefore(dir: File, stamp: String): Boolean =
        synchronized(failed) { stamp in failed } || runCatching { File(dir, FAILED).readText().trim() == stamp }.getOrDefault(false)

    private fun fail(dir: File, stamp: String) {
        synchronized(failed) { failed += stamp }
        runCatching { File(dir, FAILED).writeText(stamp) }.onFailure { Timber.w(it, "%s", FAILED) }
    }

    // unreadable files skipped, the rest compiled; retried when the file changes or 词库 opens
    internal val unreadNotes = Notes(".merged.unreadable")

    // texts not converted for want of space: skipped till 词库 opens, which says to free space
    internal val unwrittenNotes = Notes(".merged.unwritten")

    // converted texts whose delete was refused: not reconverted while the gzip is there; the delete retried each compile
    internal val leftNotes = Notes(".merged.left", LegacyTextConversion::leftKey)

    // an upgrade's compile owed: after a kill no text nor old file would tell it
    private const val UPGRADING = ".merged.upgrading"

    fun unreadable(dir: File): Set<String> = unreadNotes.namesIn(dir)

    fun unconverted(dir: File): Set<String> = unwrittenNotes.namesIn(dir)

    private fun owe(marker: File) {
        runCatching { marker.createNewFile() }.onFailure { Timber.w(it, "%s", UPGRADING) }
    }

    private var swept = false

    // a tmp older than this is a killed process's and swept (storage keeps times to the second only)
    private val startedAt = System.currentTimeMillis() - MTIME_SLACK_MS

    private const val MTIME_SLACK_MS = 2_000L

    enum class Kind { UPGRADE, UPDATE }

    enum class Step { CONVERT, READ, MERGE, WRITE }

    data class Work(val kind: Kind, val stage: Step, val percent: Int)

    private val working = MutableStateFlow<Work?>(null)

    val work: StateFlow<Work?> = working.asStateFlow()

    private val ended = MutableSharedFlow<Pair<Kind, Boolean>>(extraBufferCapacity = 4)

    /** Each compile not cancelled: its kind and whether it made the file. */
    val done: SharedFlow<Pair<Kind, Boolean>> = ended.asSharedFlow()

    @VisibleForTesting
    internal var told: (Work) -> Unit = {}

    class Base(val fingerprint: String, val has: () -> (String, IntArray) -> Boolean)

    /** A word the text pack has is kept out of the merged trie, offered from the base alone. */
    @Volatile
    var base: () -> Base? = { packBase() }

    // keyed by the pack's directory, not PackStore's generation, which a voice import bumps too and only after placing
    @Volatile
    private var readBase: Pair<File?, Base?>? = null

    @VisibleForTesting
    internal var readPackBase: () -> Base? = { textPackBase() }

    @VisibleForTesting
    internal fun packBase(): Base? {
        val dir = PackStore.dir(PackKind.TEXT)
        readBase?.let { (read, base) -> if (read == dir) return base }
        return readPackBase().also { readBase = dir to it }
    }

    private fun mapped(): ByteBuffer = AppData.map(InputMethodCatalog.PINYIN_DATA)

    // its length and a CRC of its ends: tells another same-length pack apart without reading 100 MB
    private fun textPackBase(): Base? = try {
        val data = mapped()
        val crc = CRC32()
        crc.update(data.duplicate().apply { limit(minOf(FINGERPRINT_BYTES, capacity())) })
        crc.update(data.duplicate().apply { position(maxOf(0, capacity() - FINGERPRINT_BYTES)) })
        Base("${InputMethodCatalog.PINYIN_DATA} ${data.capacity()} ${crc.value}") { DictionaryCompiler.inBase(PinyinData.load(mapped(), verify = false)) }
    } catch (e: IOException) {
        Timber.w(e, "pinyin data: merged words not split off the base")
        null
    }

    private const val FINGERPRINT_BYTES = 64 shl 10

    @Volatile
    var made: () -> Unit = { HostDaemon.getFirstConnectionOrNull()?.runIfReady { reloadEngine() } }

    // a reload not run (fcitx disconnected meanwhile) is no failure: the next additions pick the file up
    private fun tellMade() {
        runCatching { made() }.onFailure { Timber.w(it, "merged dictionaries: engine not told") }
    }

    fun sources(dir: File): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(SourceNames.GZ) }
        .orEmpty().sortedBy { it.name }

    /** An app update with a new base is another stamp. */
    fun stamp(sources: List<File>, base: Base? = this.base()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$REVISION ${base?.fingerprint}\n".toByteArray())
        for (f in sources) digest.update("${f.name}\u0000${f.length()}\u0000${f.lastModified()}\n".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * File and token from one read, or a compile landing between would leave the engine told of a file it lacks. Never
     * compiles on the fcitx thread; a stale file is used till the queued compile is done. Not [compiles]: nothing queued.
     */
    fun current(dir: File, compiles: Boolean = true): Seen {
        val sources = sources(dir)
        val unconverted = LegacyTextConversion.toConvert(dir)
        if (unconverted.isNotEmpty()) {
            // older texts are pending till converted; the current file is mapped before the compile is queued
            val now = opened.get() ?: mapUnchecked(File(dir, FILE), null)
            if (compiles) refresh(dir)
            return Seen(now?.file, now?.let { "$FILE ${it.stamp}" } ?: (sources + unconverted).joinToString(" ") { it.name }, staleOf(now, base()))
        }
        if (sources.isEmpty()) {
            // a file left by a kill after the last source was deleted: deleted then
            if (compiles && (opened.get() != null || File(dir, FILE).exists())) refresh(dir)
            val now = opened.get()
            return Seen(now?.file, now?.let { "$FILE ${it.stamp}" }.orEmpty())
        }
        val base = base()
        val stamp = stamp(sources, base)
        val now = opened.get() ?: mapUnchecked(File(dir, FILE), stamp)
        if (compiles && now?.stamp != stamp) refresh(dir)
        return Seen(now?.file, now?.let { "$FILE ${it.stamp}" } ?: sources.joinToString(" ") { it.name }, staleOf(now, base))
    }

    private fun staleOf(now: Opened?, base: Base?) = now != null && now.file.base != base?.fingerprint.orEmpty()

    // header only; CRCs are checked on the compile thread. [stamp] null matches any
    private fun mapUnchecked(dest: File, stamp: String?): Opened? {
        if (!dest.isFile) return null
        val file = try {
            DictionaryFile.open(JvmFile(dest), verify = false)
        } catch (e: IOException) {
            Timber.w(e, "%s: not mapped", FILE)
            return null
        }
        if (stamp != null && file.stamp != stamp) return null
        val mapped = Opened(file.stamp, file)
        // a compile landed meanwhile: its newer file stays
        if (!opened.compareAndSet(null, mapped)) return opened.get()
        compiles.submit {
            try {
                DictionaryFile.open(JvmFile(dest))
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

    /** Until then a word the dictionaries may have is not known to be none of theirs. */
    fun pending(dir: File): Boolean {
        // an off one changes nothing; a converted one's words are in the gzip; one not read or written still owes its words
        val texts = SourceNames.unconverted(dir)
        if (texts.isNotEmpty()) {
            val held = LegacyTextConversion.leftHeld(dir)
            if (texts.any { SourceNames.isOn(it.name) && LegacyTextConversion.leftKey(it) !in held }) return true
        }
        val sources = sources(dir)
        return sources.isNotEmpty() && opened.get()?.stamp != stamp(sources)
    }

    /** One already waiting does for this, as it reads the files when it runs. */
    fun refresh(dir: File): Future<*>? {
        if (!queued.compareAndSet(false, true)) return null
        return compiles.submit {
            queued.set(false)
            try {
                make(dir, forImport = false, AtomicBoolean())
            } catch (e: IOException) {
                Timber.w(e, "merged dictionaries")
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // a compile fault (its OOM comes as an IOException): logged, the engine goes on with what it has
                if (e !is CancellationException) Timber.e(e, "merged dictionaries")
            }
        }
    }

    fun retry(dir: File): Future<*>? {
        synchronized(failed) { failed.clear() }
        File(dir, FAILED).delete()
        // unconverted texts are retried; a damaged gzip stays named
        unreadNotes.replace(dir, unreadNotes.notedIn(dir).filterNot { SourceNames.isText(Signature.fileNameOf(it)) })
        unwrittenNotes.replace(dir, emptyList())
        // one already queued runs after the clear: waited for through a step behind it
        return refresh(dir) ?: compiles.submit {}
    }

    /**
     * An import's: [cancel] stops this compile only.
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

    // [forImport]: an UPDATE whose failure the caller shows; else an upgrade is decided by what it finds, never by file times
    private fun make(dir: File, forImport: Boolean, cancel: AtomicBoolean) {
        sweep(dir)
        if (cancel.get()) throw CancellationException("cancelled")
        try {
            makeFrom(dir, forImport, Progress(if (forImport) Kind.UPDATE else Kind.UPGRADE, cancel))
        } finally {
            // once, at the end: clearing between conversion and compile emptied the bar
            working.value = null
        }
    }

    private fun makeFrom(dir: File, forImport: Boolean, progress: Progress) {
        val dest = File(dir, FILE)
        val owed = File(dir, UPGRADING)
        // a refused old-format file is freed before the conversion writes beside it
        val dropped = dropUnread(dest)
        if (dropped) owe(owed)
        val conversion = LegacyTextConversion.convert(dir, { owe(owed) }, { progress.converting = true }) { progress.tell(Step.CONVERT, it) }
        progress.unconverted = conversion.unconverted
        val converted = conversion.any
        // a backup may keep one both on and off: one of them
        ImportedDictionaries.settle(dir)
        val sources = sources(dir)
        // a conversion gives the gzip its text's old time, so file times would misread a later compile as an upgrade
        val upgrade = !forImport && (converted || progress.unconverted || dropped || owed.exists())
        progress.kind = if (upgrade) Kind.UPGRADE else Kind.UPDATE

        fun upgraded() {
            if (forImport) return
            owed.delete()
            working.value = null
            if (upgrade) ended.tryEmit(Kind.UPGRADE to !progress.unconverted)
        }
        if (sources.isEmpty()) {
            dropAll(dir)
            upgraded()
            return
        }
        val base = base()
        val stamp = stamp(sources, base)
        if (opened.get()?.stamp == stamp) return upgraded()
        // for the engine at once if it has none yet: stale, its words are still right
        val there = openOrDelete(dest)
        if (there != null && (opened.get() == null || there.stamp == stamp)) {
            opened.set(Opened(there.stamp, there))
            tellMade()
            if (there.stamp == stamp) return upgraded()
        }
        if (failedBefore(dir, stamp)) {
            // failed before: an import fails (its caller tells), a reload goes on quietly
            if (forImport) throw IOException("$FILE: failed to compile before, not tried again till retried")
            return
        }
        compile(sources, dest, stamp, base, progress, tells = !forImport)
        owed.delete()
        File(dir, FAILED).delete()
    }

    private fun dropAll(dir: File) {
        val dest = File(dir, FILE)
        if (dest.exists() && !dest.delete()) Timber.w("%s: cannot delete it", FILE)
        dir.listFiles { f -> f.name.startsWith("merged.") && f.name.endsWith(".ymc") }?.forEach { it.delete() }
        if (opened.get() != null) {
            opened.set(null)
            tellMade()
        }
    }

    // a current-format file is kept: damage is caught by openOrDelete's verified open
    private fun dropUnread(dest: File): Boolean {
        if (!dest.isFile) return false
        val format = DataFile.peekKindVersion(dest)
        if (format == DictionaryFile.VERSION) return false
        Timber.w("%s: format %s, deleted, made again", FILE, format)
        if (!dest.delete()) Timber.w("%s: cannot delete it", FILE)
        return format == FORMAT_BEFORE
    }

    private const val FORMAT_BEFORE = 1

    private class Progress(var kind: Kind, val cancel: AtomicBoolean) {
        // the compile's steps scaled into what is left after conversion
        var converting = false

        var unconverted = false

        fun tell(step: Step, share: Float) {
            if (cancel.get()) throw CancellationException("cancelled")
            val (from, to) = stageShare(step, converting)
            val percent = (from + (to - from) * share.coerceIn(0f, 1f)).toInt()
            if (working.value?.percent != percent || working.value?.stage != step) working.value = Work(kind, step, percent).also(told)
        }
    }

    // [tells]: not for an import. A damaged gzip is skipped with what was read kept, not failing the whole compile
    private fun compile(sources: List<File>, dest: File, stamp: String, base: Base?, progress: Progress, tells: Boolean) {
        val cancel = progress.cancel
        val total = sources.sumOf { it.length() }.coerceAtLeast(1)
        var read = 0L
        // closed however it ends: one stopped halfway is not read to its end, where useLines would close it
        val inputs = ArrayList<InputStream>()
        // a source turned off or deleted mid-compile: no failure, the compile queued after does it
        var superseded = false
        val damaged = Collections.synchronizedList(ArrayList<String>())
        progress.tell(Step.READ, 0f)
        var ok = false
        try {
            val result = DictionaryCompiler.compile(
                sources.map { f ->
                    val signature = Signature.lineOf(f)
                    linesOf(f, inputs, { read += it }) { damaged += signature }
                },
                dest,
                stamp = stamp,
                base = base?.fingerprint.orEmpty(),
                inBase = base?.has?.invoke() ?: { _, _ -> false },
                progress = { stage, done, of ->
                    progress.tell(stepOf(stage), if (stage == DictionaryCompiler.Stage.READ) read.toFloat() / total else done.toFloat() / maxOf(of, 1))
                },
                names = sources.map { SourceNames.nameOf(it.name) },
            )
            Timber.i("merged dictionaries: %d words of %d files, %d of the base, %d lines unread", result.words, sources.size, result.inBase, result.unread)
            opened.set(Opened(stamp, DictionaryFile.open(JvmFile(dest))))
            unreadNotes.note(dest.parentFile!!, damaged)
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
            if (tells && !cancel.get() && !superseded) ended.tryEmit(progress.kind to (ok && !progress.unconverted))
        }
    }

    // a damaged gzip ends the lines, keeping what was read; the caller closes [inputs]
    private fun linesOf(f: File, inputs: MutableList<InputStream>, read: (Int) -> Unit, damaged: () -> Unit): Sequence<String> = sequence {
        val file = object : FilterInputStream(FileInputStream(f).also { inputs += it }) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also {
                if (it > 0) read(it)
            }
        }
        val lines = try {
            SourceNames.open(f, file).also { inputs += it }.bufferedReader().lineSequence().iterator()
        } catch (e: IOException) {
            if (!isDamage(e)) throw e
            Timber.w(e, "%s: damaged, skipped", f.name)
            damaged()
            return@sequence
        }
        while (true) {
            val line = try {
                if (lines.hasNext()) lines.next() else null
            } catch (e: IOException) {
                if (!isDamage(e)) throw e
                Timber.w(e, "%s: damaged, the rest skipped", f.name)
                damaged()
                null
            }
            yield(line ?: break)
        }
    }

    private fun isDamage(e: IOException) = e is ZipException || e is EOFException

    private fun stepOf(stage: DictionaryCompiler.Stage) = when (stage) {
        DictionaryCompiler.Stage.READ -> Step.READ
        DictionaryCompiler.Stage.MERGE -> Step.MERGE
        DictionaryCompiler.Stage.WRITE -> Step.WRITE
    }

    // reading and sorting is most of it; conversion takes the first 30
    private fun stageShare(step: Step, converting: Boolean): Pair<Float, Float> {
        val (from, to) = when (step) {
            Step.CONVERT -> return 0f to CONVERT_SHARE
            Step.READ -> 0f to 60f
            Step.MERGE -> 60f to 90f
            Step.WRITE -> 90f to 100f
        }
        if (!converting) return from to to
        val scale = (100f - CONVERT_SHARE) / 100f
        return CONVERT_SHARE + from * scale to CONVERT_SHARE + to * scale
    }

    private const val CONVERT_SHARE = 30f

    // damaged, cut short or another build's: deleted and rebuilt
    private fun openOrDelete(dest: File): DictionaryFile? {
        if (!dest.exists()) return null
        return try {
            DictionaryFile.open(JvmFile(dest))
        } catch (e: IOException) {
            Timber.w(e, "%s: compiled again", FILE)
            dest.delete()
            null
        }
    }

    // a killed compile or import's leftovers, which one not rerun would leave
    private fun sweep(dir: File) {
        if (swept) return
        swept = true
        // not a table index beside it (merged.<im>.ymc), which its build sweeps
        dir.listFiles { f -> FILE in f.name && DictionaryCompiler.isWorkFile(f.name) }?.forEach { it.deleteRecursively() }
        // one this process is writing is newer, never deleted
        synchronized(ImportedDictionaries) {
            dir.listFiles { f -> SourceNames.isTemp(f.name) }
                ?.filter { it.lastModified() < startedAt }?.forEach { it.delete() }
        }
    }

    /** Left out of a backup, rebuilt after a restore. */
    fun isCompiled(file: File): Boolean {
        val name = file.name
        return name.endsWith(".ymd") || name.endsWith(".ymc") || DictionaryCompiler.isWorkFile(name) ||
            SourceNames.isTemp(name) || name == FAILED || name == unreadNotes.fileName || name == unwrittenNotes.fileName || name == leftNotes.fileName ||
            name == UPGRADING ||
            generateSequence(file.parentFile) { it.parentFile }.any { DictionaryCompiler.isWorkFile(it.name) }
    }

    fun sourceWords(dir: File): Map<String, Int> = DictionaryCompiler.sourceWords(File(dir, FILE))

    /** Tests share the one object. */
    @VisibleForTesting
    internal fun reset() {
        compiles.submit {}.get()
        opened.set(null)
        synchronized(failed) { failed.clear() }
        unreadNotes.clear()
        unwrittenNotes.clear()
        leftNotes.clear()
        swept = false
        queued.set(false)
        working.value = null
        told = {}
        readBase = null
    }
}
