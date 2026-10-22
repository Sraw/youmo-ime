/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The older builds' learning a heavy first open left owed ([LearnLog.conversionPending]), converted
 * and merged into the log on [warmUps], typing going on meanwhile on what is learned from now
 * (uat-0.3.1: a heavy user's first open waited 19.5 s on it); put in place where typing pauses
 * ([take]), or made at once before what reads or rewrites the whole record ([finish]). A conversion
 * that fails is owed still, to the next start. Everything but the work handed over runs on the fcitx
 * thread.
 */
internal class OwedConversion(
    private val warmUps: Executor,
    private val onError: (IOException) -> Unit,
    private val note: (ConversionNote) -> Unit = {},
    private val uptimeMillis: () -> Long = UPTIME,
) {

    // what [begin] made for the log it was made for, merged into the log as it was then: put in
    // place by [take] if the log is still that, records appended or not
    private val converted = AtomicReference<Converted?>()

    // [legacy], the files converted as read off this thread, for a merge made again there
    private class Converted(val log: LearnLog, val merged: LearnLog.Merged, val legacy: LearnLog.Legacy)

    // a merge put in place failed on disk this run: owed to the next start, not tried at every pause (review-uat-fixes S5)
    // written by the background conversion as well as here
    @Volatile
    var failed = false

    // the app's files did not read (Engines): nothing is made in the background, so an automatic backup
    // waits on no conversion that cannot land so (F3100); not [failed], as [finish] tries it still, on
    // this thread, before an act on the whole record (F3212)
    @Volatile
    var notStarted = false

    /**
     * Whether a backup is to wait on the conversion owed: not one that failed this run or was never begun.
     * Read only together with the conversion still being owed, so [notStarted] left set after a later
     * [finish] lands it changes nothing.
     */
    val mayLand: Boolean get() = !failed && !notStarted

    // merges made again as the log was folded under them, before one is made on this thread
    private var staleMerges = 0

    // when [begin] began, for [ConversionNote.Landed]
    private var began = 0L

    // when the last key came, for [paused]
    private var lastKey = Long.MIN_VALUE / 2

    /** A key came: typing goes on. */
    fun keyed() {
        lastKey = uptimeMillis()
    }

    /**
     * Typing paused in the field (uat-0.3.1 recheck: a field reopened for minutes never put it in
     * place): the keyboard shown or hidden, or ([idle]) [IDLE_MILLIS] gone since the last key. The
     * conversion made for [log] put in place then ([take]), never while something is [composing].
     * Whether one is made and still waits, to be asked again.
     */
    fun paused(log: LearnLog?, composing: Boolean, idle: Boolean): Boolean {
        val quiet = !idle || uptimeMillis() - lastKey >= IDLE_MILLIS
        if (ready && !composing && quiet) take(log)
        return ready
    }

    /** Whether a conversion is made and waits for [take]. */
    val ready: Boolean get() = converted.get() != null

    /** [log]'s conversion of [files] ([logs] of them) begun on [warmUps], if one is owed: [files] read nothing of this thread's. */
    fun begin(log: LearnLog, files: LearnLog.Legacy, logs: Int) {
        val work = log.conversion(files) ?: return
        began = uptimeMillis()
        note(ConversionNote.Started(logs))
        inBackground { merge(log, work(), files) }
    }

    // [state], converted, merged into [log] as it is on disk, for [take]
    private fun merge(log: LearnLog, state: LearnState, files: LearnLog.Legacy) {
        log.mergeConversion(state, files)?.let {
            converted.set(Converted(log, it, files))
            note(ConversionNote.Made)
        }
    }

    // told once put in place
    private fun landed(taken: LearnLog.Taken, state: LearnState) {
        if (taken == LearnLog.Taken.DONE) note(ConversionNote.Landed(state.mine.size, state.stats.size, state.enWords.size, uptimeMillis() - began))
    }

    private fun inBackground(work: () -> Unit) = warmUps.execute {
        try {
            work()
        } catch (e: IOException) {
            failed = true
            onError(IOException("the older builds' learning not converted: owed to the next start", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            failed = true
            onError(IOException("the older builds' learning not converted: owed to the next start", e))
        }
    }

    /**
     * The conversion made for [log], put in place where typing pauses (a field started, the engines
     * closed), not on a key in the middle of a sentence (review-uat-fixes S7): told as any change is
     * (the counts made anew). Merged again off this thread if a fold rewrote the log under it, a few
     * times, then here.
     */
    fun take(log: LearnLog?) {
        val made = converted.get() ?: return
        if (log == null) return
        // made for a log closed since: dropped, not [ready] for good (the bridge asks every second)
        if (made.log !== log) {
            converted.compareAndSet(made, null)
            return
        }
        if (!converted.compareAndSet(made, null)) return
        when (log.takeConversion(made.merged).also { landed(it, made.merged.converted) }) {
            LearnLog.Taken.STALE -> if (staleMerges++ < MAX_STALE_MERGES) {
                inBackground { merge(log, made.merged.converted, made.legacy) }
            } else {
                finish(log, made)
            }
            LearnLog.Taken.FAILED -> failed = true
            else -> Unit
        }
    }

    /**
     * [log]'s conversion merged in now, made here if it was not yet (review-uat-fixes S3): before the
     * user's acts on the whole record (a backup, an export, a restore, a clear) and its pages, which
     * would read or rewrite it without what the older build learned. Not again where it failed.
     */
    fun finish(log: LearnLog) = finish(log, converted.getAndSet(null))

    private fun finish(log: LearnLog, made: Converted?) {
        if (!log.conversionPending || failed) return
        val taken = log.finishConversion(made?.takeIf { it.log === log }?.merged)
        if (taken == LearnLog.Taken.FAILED) failed = true
        // what the older build learned, as the record has it now: no state of the conversion's at hand
        landed(taken, log.state)
    }

    companion object {
        // merges made again off the typing thread as folds rewrote the log under them, before one is made on it
        private const val MAX_STALE_MERGES = 3
        private const val NANOS_PER_MILLI = 1_000_000L

        /** How long since the last key typing counts as paused ([paused]). */
        const val IDLE_MILLIS = 1_000L

        /** Monotonic milliseconds. */
        val UPTIME: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }

        /**
         * Whether [op] reads or rewrites the whole record, so the conversion owed is made first
         * (review-uat-fixes S3): not the backups' switch, asked at every start, nor 清掉最近 short of
         * today, which reaches the tail alone; either would make it on the typing thread
         * (verify-uat-fixes B1, V2).
         */
        fun readsTheWhole(op: LearningOp) = when (op) {
            BackupOp.Copy, BackupOp.Revert, is BackupOp.Restore, is BackupOp.RestoreKept, LearningOp.Overview -> true
            is LearningOp.ClearLearned, is LearningOp.ListItems, is LearningOp.RemoveWords, is LearningOp.RemoveItems -> true
            is LearningOp.RestoreCorrection, is LearningOp.ResetTableOrder -> true
            is LearningOp.ClearRecent -> op.span == LearningOp.Span.TODAY
            // no else: a new op does not compile until it says whether it reads the whole record
            is LearningOp.OnCandidate, is LearningOp.OnPage, is LearningOp.Undo, is LearningOp.UseHabits -> false
            BackupOp.Kept, is BackupOp.Delete, is BackupOp.Peek, is BackupOp.SetAuto -> false
        }
    }
}

/** What the older builds' learning's conversion did ([Engines]' `onConversion`): counts, no text of the user's. */
sealed class ConversionNote {
    /** Begun off the typing thread, of [logs] older logs. */
    data class Started(val logs: Int) : ConversionNote()

    /** Made, on the conversion's thread: to be put in place where typing pauses ([Engines.paused]). */
    data object Made : ConversionNote()

    /**
     * Put in place, [millis] after it began: the older build's [words] (the user's own), [counts] and
     * [english] words; the whole record's where it was made at once (Engines' finishConversion).
     */
    data class Landed(val words: Int, val counts: Int, val english: Int, val millis: Long) : ConversionNote()
}
