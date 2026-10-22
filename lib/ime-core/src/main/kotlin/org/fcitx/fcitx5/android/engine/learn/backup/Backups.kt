/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.store.replaceFile
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/** A backup [Backups] keeps: its file's [name], its head, and whether this install made it. */
data class BackupInfo(val name: String, val head: BackupHead, val own: Boolean, val size: Long)

/**
 * The learning backups kept in the input method's own directory [dir] (U-106, D31: no other app
 * reads them; they go with the app): those made every [EVERY] typing days, of which the [KEEP]
 * newest of this install stay, those copied in from elsewhere (another phone's, an earlier
 * install's), which stay till the user deletes them, and the one copy made before a restore
 * ([BEFORE_RESTORE]), for 退回恢复前.
 *
 * Which install made a backup is told by [installId], kept where backups do not go (the app's
 * no-backup directory), so a copy brought over is another's.
 */
class Backups(
    private val dir: File?,
    val installId: Long,
    private val onError: (IOException) -> Unit = {},
    // writes a backup made due by a commit, so the key does not wait on the disk; null: here
    private val background: Executor? = null,
) {
    /** Whether a backup is made every [EVERY] typing days (U-106's switch). */
    var auto = true

    // the typing day of this install's newest automatic backup, once listed: asked on every commit
    @Volatile
    private var lastAuto: Int? = null

    /** The backups kept, newest first; the copy made before a restore apart ([beforeRestore]). */
    fun list(): List<BackupInfo> {
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(LearnBackup.EXTENSION) && f.name != BEFORE_RESTORE }.orEmpty()
        return files.mapNotNull(::info).sortedByDescending { it.head.time }
    }

    private fun info(file: File): BackupInfo? = try {
        LearnBackup.head(file.inputStream())?.let { BackupInfo(file.name, it, it.installId == installId, file.length()) }
    } catch (e: IOException) {
        onError(IOException("$file does not read as a backup", e))
        null
    }

    /** The bytes of the backup [name], as [list] names it; null if there is none. */
    fun read(name: String): ByteArray? = file(name)?.takeIf { it.isFile }?.readBytes()

    /** Deletes the backup [name]: whether there was one. */
    fun delete(name: String): Boolean {
        val gone = file(name)?.delete() == true
        if (gone) lastAuto = null
        return gone
    }

    // a name as listed, never a path out of the directory nor the copy [list] keeps apart
    private fun file(name: String) = dir?.takeIf { '/' !in name && name != BEFORE_RESTORE && name.endsWith(LearnBackup.EXTENSION) }?.let { File(it, name) }

    /** Whether an automatic backup is due on typing day [day]: on the first, then every [EVERY]; never for an install of no id. */
    fun due(day: Int): Boolean {
        if (!auto || dir == null) return false
        if (installId == 0L || day < 1) return false
        // the newest not after [day]: one kept from before the log went back behind it (F3090) would
        // otherwise make every day before it due, a backup at every start till typing passed it (F3238)
        val last = lastAuto ?: (
            list().filter { it.own && it.head.kind == BackupHead.AUTO && it.head.day <= day }.maxOfOrNull { it.head.day } ?: NONE
            ).also { lastAuto = it }
        return last == NONE || day - last >= EVERY || day < last
    }

    // the automatic backups being written: what invalidate waits for
    private val writing = ArrayList<Future<*>>()

    /**
     * Keeps the automatic backup of typing day [day] [make] gives (null: none, the log not read),
     * named by its head's time, and drops this install's older ones past the [KEEP] newest; in
     * the background if there is one, where [make] runs too. One not written is due again.
     */
    fun saveAuto(day: Int, make: () -> ByteArray?) {
        val dir = dir ?: return
        lastAuto = day
        val write = FutureTask {
            try {
                val bytes = make()
                if (bytes == null) {
                    // nothing to back up yet (no log read): due again, not in seven typing days
                    lastAuto = null
                } else {
                    val time = LearnBackup.head(bytes.inputStream())?.time ?: System.currentTimeMillis()
                    val name = "$AUTO_PREFIX$time${LearnBackup.EXTENSION}"
                    write(File(dir, name), bytes)
                    // the one just written kept, it holds the learning now, and the newest of the others by
                    // typing day, as [due] reads them: neither a clock set back nor a log begun again behind
                    // the newest (due's day < last) may make it the one dropped (F3090)
                    list().filter { it.own && it.head.kind == BackupHead.AUTO && it.name != name }
                        .sortedWith(compareByDescending<BackupInfo> { it.head.day }.thenByDescending { it.head.time })
                        .drop(KEEP - 1).forEach { File(dir, it.name).delete() }
                }
            } catch (e: IOException) {
                notWritten(e)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // the log read wrong, however: told, never a thread's death unseen
                notWritten(IOException("an automatic backup not made", e))
            }
        }
        writing.removeAll { it.isDone }
        writing += write
        background?.execute(write) ?: write.run()
    }

    private fun notWritten(e: IOException) {
        lastAuto = null
        onError(e)
    }

    /** Keeps [bytes] as the copy made before a restore, in place of the last. */
    @Throws(IOException::class)
    fun saveBeforeRestore(bytes: ByteArray) {
        write(File(dir ?: throw IOException("no directory for backups"), BEFORE_RESTORE), bytes)
    }

    /** The copy made before the last restore, if it is kept. */
    fun beforeRestore(): ByteArray? = dir?.let { File(it, BEFORE_RESTORE) }?.takeIf { it.isFile }?.readBytes()

    /** Whether the copy made before the last restore is kept, without reading it (it may be megabytes). */
    fun hasBeforeRestore(): Boolean = dir?.let { File(it, BEFORE_RESTORE) }?.isFile == true

    /** When the copy made before the last restore was made (epoch milliseconds), if it is kept and reads. */
    fun beforeRestoreTime(): Long? = dir?.let { File(it, BEFORE_RESTORE) }?.takeIf { it.isFile }?.let(::info)?.head?.time

    fun dropBeforeRestore() {
        dir?.let { File(it, BEFORE_RESTORE).delete() }
    }

    /**
     * What U-86 and U-88 leave (C25), done before their rewrite (review-DE-design DE-M1): an
     * automatic backup being written finished, then this install's made at or after
     * [fromMinute] (epoch minutes; [Int.MIN_VALUE], U-86, all of them) and the copy made before a
     * restore deleted, so none brings back what is cleared; those from elsewhere left.
     */
    fun invalidate(fromMinute: Int) {
        for (w in writing) {
            try {
                w.get()
            } catch (e: ExecutionException) {
                onError(IOException("an automatic backup not written", e))
            }
        }
        writing.clear()
        val from = if (fromMinute == Int.MIN_VALUE) Long.MIN_VALUE else fromMinute * LearnClock.MINUTE
        list().filter { it.own && it.head.kind == BackupHead.AUTO && it.head.time >= from }.forEach { delete(it.name) }
        dropBeforeRestore()
        lastAuto = null
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        replaceFile(file, File(file.path + ".new")) { it.writeBytes(bytes) }
    }

    companion object {
        /** U-106's 〔7〕 typing days. */
        const val EVERY = 7

        /** U-106's 〔2〕. */
        const val KEEP = 2

        /** The directory, in the engine's. */
        const val DIR = "backups"

        const val BEFORE_RESTORE = "before-restore.fxlb"

        private const val AUTO_PREFIX = "auto-"
        private const val NONE = Int.MIN_VALUE

        /**
         * The install's id, in [file] (made the first time); a random one for no [file], as a
         * test's phone is another install each time. 0, told, if [file] can be neither read nor
         * written: never a new one each start, which would make this install's backups another's.
         */
        fun installId(file: File?, onError: (IOException) -> Unit = {}): Long {
            if (file == null) return random()
            return try {
                val kept = file.takeIf { it.isFile }?.readText()
                kept?.trim()?.toLongOrNull() ?: random().also { id ->
                    // a garbled file: this install's backups become another's from here on, so say so
                    if (kept != null) onError(IOException("$file does not read as an install id: a new one made"))
                    file.parentFile?.mkdirs()
                    replaceFile(file, File(file.path + ".new")) { it.writeText(id.toString()) }
                }
            } catch (e: IOException) {
                onError(IOException("no install id: backups not made", e))
                0L
            }
        }

        // never 0, which a backup of no known install has
        private fun random(): Long {
            var id = 0L
            while (id == 0L) id = SecureRandom().nextLong()
            return id
        }
    }
}
