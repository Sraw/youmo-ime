/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.store.replaceFile
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

data class BackupInfo(val name: String, val head: BackupHead, val own: Boolean, val size: Long)

/**
 * Learning backups in the app's own directory [dir]: automatic ones every [EVERY] typing days (the [KEEP]
 * newest of this install stay), ones copied from elsewhere (kept till deleted), and the pre-restore copy
 * ([BEFORE_RESTORE]). [installId] is kept in the no-backup directory, so a copied-in backup is another install's.
 */
class Backups(
    private val dir: UserFiles?,
    val installId: Long,
    private val onError: (IOException) -> Unit = {},
    // writes due backups so the key does not wait on disk; null: inline
    private val background: Executor? = null,
    private val clock: LearnClock = JvmClock,
) {
    var auto = true

    // asked on every commit
    @Volatile
    private var lastAuto: Int? = null

    /** Newest first; the pre-restore copy apart ([beforeRestore]). */
    fun list(): List<BackupInfo> {
        val dir = dir ?: return emptyList()
        val files = dir.files().filter { it.endsWith(LearnBackup.EXTENSION) && it != BEFORE_RESTORE }.map(dir::file)
        return files.mapNotNull(::info).sortedByDescending { it.head.time }
    }

    private fun info(file: LogFile): BackupInfo? = try {
        LearnBackup.head(file.input())?.let { BackupInfo(file.name, it, it.installId == installId, file.length()) }
    } catch (e: IOException) {
        onError(IOException("$file does not read as a backup", e))
        null
    }

    fun read(name: String): ByteArray? = file(name)?.takeIf { it.exists() }?.read()

    fun delete(name: String): Boolean {
        val gone = file(name)?.delete() == true
        if (gone) lastAuto = null
        return gone
    }

    // a listed name only: never a path out of the directory, nor the pre-restore copy
    private fun file(name: String) = dir?.takeIf { '/' !in name && name != BEFORE_RESTORE && name.endsWith(LearnBackup.EXTENSION) }?.file(name)

    /** On the first typing day, then every [EVERY]; never for an install without an id. */
    fun due(day: Int): Boolean {
        if (!auto || dir == null) return false
        if (installId == 0L || day < 1) return false
        // the newest not after [day]: a backup from before the log went back would make every earlier day due
        val last = lastAuto ?: (
            list().filter { it.own && it.head.kind == BackupHead.AUTO && it.head.day <= day }.maxOfOrNull { it.head.day } ?: NONE
            ).also { lastAuto = it }
        return last == NONE || day - last >= EVERY || day < last
    }

    // what invalidate waits for
    private val writing = ArrayList<Future<*>>()

    /** Saves the automatic backup [make] builds (null: log not read) and drops this install's older ones past [KEEP]; in the background if any. */
    fun saveAuto(day: Int, make: () -> ByteArray?) {
        val dir = dir ?: return
        lastAuto = day
        val write = FutureTask {
            try {
                val bytes = make()
                if (bytes == null) {
                    // nothing to back up yet: due again
                    lastAuto = null
                } else {
                    val time = LearnBackup.head(bytes.inputStream())?.time ?: clock.now()
                    val name = "$AUTO_PREFIX$time${LearnBackup.EXTENSION}"
                    write(dir.file(name), bytes)
                    // keep the new one and the newest of the others by typing day: neither a clock set back nor a restarted
                    // log may make it the one dropped
                    list().filter { it.own && it.head.kind == BackupHead.AUTO && it.name != name }
                        .sortedWith(compareByDescending<BackupInfo> { it.head.day }.thenByDescending { it.head.time })
                        .drop(KEEP - 1).forEach { dir.file(it.name).delete() }
                }
            } catch (e: IOException) {
                notWritten(e)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // report; never let the thread die unseen
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

    @Throws(IOException::class)
    fun saveBeforeRestore(bytes: ByteArray) {
        write((dir ?: throw IOException("no directory for backups")).file(BEFORE_RESTORE), bytes)
    }

    fun beforeRestore(): ByteArray? = dir?.file(BEFORE_RESTORE)?.takeIf { it.exists() }?.read()

    /** Without reading it (it may be megabytes). */
    fun hasBeforeRestore(): Boolean = dir?.file(BEFORE_RESTORE)?.exists() == true

    fun beforeRestoreTime(): Long? = dir?.file(BEFORE_RESTORE)?.takeIf { it.exists() }?.let(::info)?.head?.time

    fun dropBeforeRestore() {
        dir?.file(BEFORE_RESTORE)?.delete()
    }

    /** Before a clear's rewrite: finishes pending automatic backups, then deletes this install's made at or after [fromMinute] and the pre-restore copy, so none brings back what is cleared. */
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

    private fun write(file: LogFile, bytes: ByteArray) {
        file.mkdirs()
        replaceFile(file, file.beside(".new")) { it.write(bytes) }
    }

    companion object {
        const val EVERY = 7

        const val KEEP = 2

        const val DIR = "backups"

        const val BEFORE_RESTORE = "before-restore.fxlb"

        private const val AUTO_PREFIX = "auto-"
        private const val NONE = Int.MIN_VALUE

        /** The install's id from [file], made the first time; random without a file. 0 if unreadable and unwritable, never a fresh one each start, which would make this install's backups another's. */
        fun installId(file: LogFile?, onError: (IOException) -> Unit = {}): Long {
            if (file == null) return random()
            return try {
                val kept = file.takeIf { it.exists() }?.read()?.toString(Charsets.UTF_8)
                kept?.trim()?.toLongOrNull() ?: random().also { id ->
                    // garbled: this install's backups become another's from here on
                    if (kept != null) onError(IOException("$file does not read as an install id: a new one made"))
                    file.mkdirs()
                    replaceFile(file, file.beside(".new")) { it.write(id.toString().toByteArray()) }
                }
            } catch (e: IOException) {
                onError(IOException("no install id: backups not made", e))
                0L
            }
        }

        // never 0, which means no known install
        private fun random(): Long {
            var id = 0L
            while (id == 0L) id = SecureRandom().nextLong()
            return id
        }
    }
}
