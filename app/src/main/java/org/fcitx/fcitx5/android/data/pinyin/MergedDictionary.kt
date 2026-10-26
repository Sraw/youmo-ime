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
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.host.BackgroundWork
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
 * The dictionaries merged into the base, turned on, compiled into one file the engine maps
 * ([FILE], a [DictionaryFile]): never parsed as text at the engine's start, where 0.3.1 ran the
 * heap out on a 1.9-million-word one at every focus. The texts stay beside it as its sources,
 * gzip'd ([SourceNames]), which a backup carries; the file is made again from them whenever
 * which are on, or one of them, changes (its stamp), on one thread of its own for the process,
 * so an import, a change on the page and the engine's start never compile at once. Until a
 * compile is done the engine goes on with the file it had, or without the merged words; then it
 * is reloaded ([made]).
 *
 * An older build's texts (0.3.2's `.txt`, or a backup of its restored) are converted to gzip on
 * the compile thread, at the top of each compile, before it lists the sources: never on the fcitx
 * thread. Its file (the format before, which this reader refuses) is deleted first.
 */
object MergedDictionary {

    /** The compiled file, among the dictionaries. */
    const val FILE = "merged.ymd"

    // a change to how the file is compiled (scores, what is kept) that a file made before must be made again for
    private const val REVISION = 2

    private class Opened(val stamp: String, val file: DictionaryFile)

    /** What the engine is handed at once: the file in use (null for none yet) and [token], which changes with it. */
    class Seen(val file: DictionaryFile?, val token: String, val stale: Boolean = false)

    // set from the fcitx thread (a fresh start's file, mapped) and the compile thread: the first only if none is yet (review NIT)
    private val opened = AtomicReference<Opened?>()

    // the one compile at a time, with at most one more waiting: a change made while one runs is
    // compiled after it, from the files as they are then
    // each counted till it has run, for the debug build's idle signal (BackgroundWork)
    private val compiles: ExecutorService = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { Thread(it, "dictionary-compile").apply { isDaemon = true } }) {
        override fun execute(command: Runnable) = BackgroundWork.counted(Executor { super.execute(it) }).execute(command)
    }
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

    // dictionaries' files that could not be read, as name, length and time ([Signature]): an older
    // build's text that could not be read, a kept one whose gzip is damaged. Skipped, the others
    // compiled; not tried again at each reload and start (kept in its file), but once the file
    // changes or the 词库 page opens ([retry]), which names them ([unreadable]) (F3592, F3593)
    internal val unreadNotes = Notes(".merged.unreadable")

    // an older build's texts read but not converted, their gzip not written (the disk full): kept
    // as they were, skipped like [unreadNotes] till the 词库 page opens ([retry]), which says to free
    // space, not to delete them ([unconverted]): nothing is wrong with them (F3645)
    internal val unwrittenNotes = Notes(".merged.unwritten")

    // an older build's texts converted, their gzip in place, but the text itself not deleted (refused):
    // not converted again at each start (an upgrade told each time, F3660) while that gzip, of the
    // text's state, is there; its delete tried again at each compile, the note dropped once it is
    // gone, or once the gzip is (deleted: the text converted then like any, F3674, F3675; a turn
    // renames both, the note kept, F3686).
    // Not named on the page nor cleared by [retry]: its words are in the gzip, nothing is owed.
    // Noted by its name on ([LegacyTextConversion.leftKey]), whichever state it is in
    internal val leftNotes = Notes(".merged.left", LegacyTextConversion::leftKey)

    // an upgrade's compile owed: its texts converted or the file of the format before dropped, the
    // compile not done yet. A kill meanwhile leaves no text nor old file to tell it by (F3601)
    private const val UPGRADING = ".merged.upgrading"

    /** The names of [dir]'s dictionaries that could not be read, left out of the merged file till they change: for the page to name. */
    fun unreadable(dir: File): Set<String> = unreadNotes.namesIn(dir)

    /** The names of [dir]'s texts not converted for want of space, kept as they were till the page opens again: for the page to name. */
    fun unconverted(dir: File): Set<String> = unwrittenNotes.namesIn(dir)

    private fun owe(marker: File) {
        runCatching { marker.createNewFile() }.onFailure { Timber.w(it, "%s", UPGRADING) }
    }

    private var swept = false

    // this object's first use in the process, wall-clock, less [MTIME_SLACK_MS]: a dictionary's tmp older is a killed
    // one's, swept; one this process writes is not, though the storage keep its time to the second only (F4100)
    private val startedAt = System.currentTimeMillis() - MTIME_SLACK_MS

    private const val MTIME_SLACK_MS = 2_000L

    /**
     * What a compile is for: an upgrade, the first from what an older build left (0.3.1's texts, or
     * 0.3.2's converted and its file of the format before dropped, or one of these a kill stopped
     * before its compile was done), or a change since.
     */
    enum class Kind { UPGRADE, UPDATE }

    /**
     * A compile's steps: an older build's texts converted (gzip'd) first if there are any, then
     * the compiler's own ([DictionaryCompiler.Stage]).
     */
    enum class Step { CONVERT, READ, MERGE, WRITE }

    /** A compile under way: [percent] of it done, at [stage]. */
    data class Work(val kind: Kind, val stage: Step, val percent: Int)

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

    /** The dictionaries in [dir] turned on, as this build keeps them: the sources, by name. */
    fun sources(dir: File): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(SourceNames.GZ) }
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
        val unconverted = LegacyTextConversion.toConvert(dir)
        if (unconverted.isNotEmpty()) {
            // an older build's texts (0.3.1's or 0.3.2's, or a backup of theirs restored): pending till
            // converted and compiled, without the 词库 page opened; the file in use meanwhile, or the one
            // there (a backup's texts restored beside it) as it is, mapped before the compile is queued (F3604)
            val now = opened.get() ?: mapUnchecked(File(dir, FILE), null)
            refresh(dir)
            return Seen(now?.file, now?.let { "$FILE ${it.stamp}" } ?: (sources + unconverted).joinToString(" ") { it.name }, staleOf(now, base()))
        }
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
        return Seen(now?.file, now?.let { "$FILE ${it.stamp}" } ?: sources.joinToString(" ") { it.name }, staleOf(now, base))
    }

    // a file of another base dictionary (an update's, its compile not done) in use meanwhile
    private fun staleOf(now: Opened?, base: Base?) = now != null && now.file.base != base?.fingerprint.orEmpty()

    // the file there if it is of [stamp] (of any, null), mapped without reading it through (its
    // header only), its CRCs checked on the compile thread: one damaged is dropped and made again
    private fun mapUnchecked(dest: File, stamp: String?): Opened? {
        if (!dest.isFile) return null
        val file = try {
            DictionaryFile.open(dest, verify = false)
        } catch (e: IOException) {
            Timber.w(e, "%s: not mapped", FILE)
            return null
        }
        if (stamp != null && file.stamp != stamp) return null
        val mapped = Opened(file.stamp, file)
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
        // one off converting changes nothing in the file (review CORRECTNESS-2); one left after its
        // conversion has its words in the gzip (F3668). One not read or not written still owes its
        // words, till it is converted: pending, a word of it not taken for none of theirs (F3677)
        val texts = SourceNames.unconverted(dir)
        if (texts.isNotEmpty()) {
            val held = LegacyTextConversion.leftHeld(dir)
            if (texts.any { SourceNames.isOn(it.name) && LegacyTextConversion.leftKey(it) !in held }) return true
        }
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
        // the texts not converted tried again; a damaged gzip stays named, its compile (of the same stamp) not run again
        unreadNotes.replace(dir, unreadNotes.notedIn(dir).filterNot { SourceNames.isText(Signature.fileNameOf(it)) })
        unwrittenNotes.replace(dir, emptyList())
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
    // upgrade by what it finds: texts converted or not, a file of the format before dropped, an
    // upgrade owed ([UPGRADING]); never by the files' times (F3644)
    private fun make(dir: File, forImport: Boolean, cancel: AtomicBoolean) {
        sweep(dir)
        if (cancel.get()) throw CancellationException("cancelled")
        try {
            makeFrom(dir, forImport, Progress(if (forImport) Kind.UPDATE else Kind.UPGRADE, cancel))
        } finally {
            // once, at the end: not between the conversion and the compile, the bar emptied between (F3597)
            working.value = null
        }
    }

    // the work of [make], after its sweep
    private fun makeFrom(dir: File, forImport: Boolean, progress: Progress) {
        val dest = File(dir, FILE)
        val owed = File(dir, UPGRADING)
        // one this reader refuses (0.3.2's, 105 MiB for frost) freed before the conversion writes beside it
        val dropped = dropUnread(dest)
        if (dropped) owe(owed)
        val conversion = LegacyTextConversion.convert(dir, { owe(owed) }, { progress.converting = true }) { progress.tell(Step.CONVERT, it) }
        progress.unconverted = conversion.unconverted
        val converted = conversion.any
        // a backup's kept both on and off (no text of its name): one of them
        ImportedDictionaries.settle(dir)
        val sources = sources(dir)
        // by what this compile found, not the files' times: a conversion gives the gzip its text's old
        // time, so a later compile from none (all off, then one on) looked like an upgrade (F3644)
        val upgrade = !forImport && (converted || progress.unconverted || dropped || owed.exists())
        progress.kind = if (upgrade) Kind.UPGRADE else Kind.UPDATE

        // nothing (more) to compile: an upgrade's end told here, as a compile's is (F3597)
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
        // what is on disk, for the engine at once if it has none yet: stale, its words are still right
        val there = openOrDelete(dest)
        if (there != null && (opened.get() == null || there.stamp == stamp)) {
            opened.set(Opened(there.stamp, there))
            tellMade()
            if (there.stamp == stamp) return upgraded()
        }
        if (failedBefore(dir, stamp)) {
            // failed before: not compiled again till retried; an import that asked for it fails (its caller tells), a reload goes on quietly
            if (forImport) throw IOException("$FILE: failed to compile before, not tried again till retried")
            return
        }
        compile(sources, dest, stamp, base, progress, tells = !forImport)
        // an import's too: one whose compile converted texts made the marker, and the compile is done (F4099)
        owed.delete()
        File(dir, FAILED).delete()
    }

    // none on: no file, nor the tables' indexes of its words
    private fun dropAll(dir: File) {
        val dest = File(dir, FILE)
        if (dest.exists() && !dest.delete()) Timber.w("%s: cannot delete it", FILE)
        dir.listFiles { f -> f.name.startsWith("merged.") && f.name.endsWith(".ymc") }?.forEach { it.delete() }
        if (opened.get() != null) {
            opened.set(null)
            tellMade()
        }
    }

    // [dest] deleted if its header is not this reader's format: whether it was of the format before,
    // an upgrade. One whose header says this format is kept here: a damaged one is caught by
    // openOrDelete's verified open and made again as an update (F3599), without mapping it twice (F4044)
    private fun dropUnread(dest: File): Boolean {
        if (!dest.isFile) return false
        val format = DataFile.peekKindVersion(dest)
        if (format == DictionaryFile.VERSION) return false
        Timber.w("%s: format %s, deleted, made again", FILE, format)
        if (!dest.delete()) Timber.w("%s: cannot delete it", FILE)
        return format == FORMAT_BEFORE
    }

    // merged.ymd's format in 0.3.2 and 0.3.1 (DictionaryFile v1)
    private const val FORMAT_BEFORE = 1

    /** How far a compile is, told in [work] as its [kind], at each step's share; [cancel] checked at each. */
    private class Progress(var kind: Kind, val cancel: AtomicBoolean) {
        // texts converted first: the compile's steps scaled into what is left after them
        var converting = false

        // a text this compile could not convert: the upgrade told failed (the page names it, retries it)
        var unconverted = false

        fun tell(step: Step, share: Float) {
            if (cancel.get()) throw CancellationException("cancelled")
            val (from, to) = stageShare(step, converting)
            val percent = (from + (to - from) * share.coerceIn(0f, 1f)).toInt()
            if (working.value?.percent != percent || working.value?.stage != step) working.value = Work(kind, step, percent).also(told)
        }
    }

    // [sources] compiled into [dest], told as [progress]'s kind in [work] and [done] as it goes; the engine told then.
    // [tells]: the end told in [done]; not for an import's compile, whose caller shows its own failure (one dialog, review).
    // A source whose gzip is damaged is skipped, what was read of it kept, and noted ([unreadable]): one
    // bad file no longer fails the whole compile, every imported word gone with it (F3593)
    private fun compile(sources: List<File>, dest: File, stamp: String, base: Base?, progress: Progress, tells: Boolean) {
        val cancel = progress.cancel
        val total = sources.sumOf { it.length() }.coerceAtLeast(1)
        var read = 0L
        // each source's stream, closed at the end however it ends: one stopped halfway (a cancel, a
        // failure) is not read to its end, where useLines would close it
        val inputs = ArrayList<InputStream>()
        // a source turned off or deleted as it was compiled: no failure, the compile queued after does (review)
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
            opened.set(Opened(stamp, DictionaryFile.open(dest)))
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
            // a cancel is no failure to tell of: what was asked for was not to have it
            if (tells && !cancel.get() && !superseded) ended.tryEmit(progress.kind to (ok && !progress.unconverted))
        }
    }

    // [f]'s lines, the bytes read (compressed) told to [read]; a gzip damaged (none at all, cut short)
    // ends them, what was read kept as a text's readable lines were, [damaged] told: the source skipped,
    // not the compile failed (F3593). Its streams added to [inputs], which the caller closes
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

    // each step's share of the whole, in percent: reading and sorting the lines is most of it; texts
    // converted first take the first 30 (frost's 84.5 MiB deflated is about half a compile's time)
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
        // a dictionary half written (an import's, a conversion's) by a process before this one: one
        // this process is writing (an import's, on the page's thread) is newer, never deleted
        synchronized(ImportedDictionaries) {
            dir.listFiles { f -> SourceNames.isTemp(f.name) }
                ?.filter { it.lastModified() < startedAt }?.forEach { it.delete() }
        }
    }

    /** Whether [file] is made from the dictionaries, or half of one, not one: left out of a backup, made again after a restore. */
    fun isCompiled(file: File): Boolean {
        val name = file.name
        return name.endsWith(".ymd") || name.endsWith(".ymc") || DictionaryCompiler.isWorkFile(name) ||
            SourceNames.isTemp(name) || name == FAILED || name == unreadNotes.fileName || name == unwrittenNotes.fileName || name == leftNotes.fileName ||
            name == UPGRADING ||
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
        unreadNotes.clear()
        unwrittenNotes.clear()
        leftNotes.clear()
        swept = false
        queued.set(false)
        working.value = null
        told = {}
    }
}
