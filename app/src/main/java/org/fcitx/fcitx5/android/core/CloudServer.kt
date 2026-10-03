/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.util.AtomicFile
import org.fcitx.fcitx5.android.engine.remote.CloudConfig
import org.fcitx.fcitx5.android.engine.remote.HttpRemoteModel
import org.fcitx.fcitx5.android.engine.remote.RemoteModel
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.Executors

/**
 * The cloud build's settings for the user's own server, and the server as they have it, asked
 * for by the engine at each question (on the fcitx thread): null while it is off or has nowhere
 * it may send to ([CloudConfig.target]).
 *
 * Kept in no_backup, not in the app's preferences: those go into an export and a backup, and
 * one restored or imported elsewhere would send what is typed there, to a server whose key no
 * one compared, without the user ever saying yes; the token would travel in the archive too.
 */
class CloudServer(path: File) {
    private val file = AtomicFile(path)

    // one question at a time: a newer one cancels the one before (RemoteRefiner), and one cancelled
    // while it waits here is never sent, so typing fast does not queue stale work on the server
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "cloud-server").apply { isDaemon = true } }
    }

    // read again when the file changes: before the first unlock it cannot be read, and is off.
    // Under the lock, as save() writes: AtomicFile's openRead() would undo a write under way
    private var read: Pair<Long, CloudConfig>? = null

    private var made: Pair<CloudConfig.Target, RemoteModel>? = null // the fcitx thread's alone

    @Synchronized
    fun config(): CloudConfig {
        val stamp = file.baseFile.lastModified()
        read?.let { (at, config) -> if (at == stamp) return config }
        val config = try {
            if (stamp == 0L) CloudConfig() else load()
        } catch (_: IOException) {
            return CloudConfig()
        } catch (_: IllegalArgumentException) { // a malformed \u escape: unreadable, so off
            return CloudConfig()
        }
        read = stamp to config
        return config
    }

    @Synchronized
    fun save(config: CloudConfig) {
        val out = file.startWrite()
        try {
            Properties().apply {
                setProperty(ENABLED, config.enabled.toString())
                setProperty(SERVER, config.server)
                setProperty(TOKEN, config.token)
                setProperty(KEY, config.key)
            }.store(out, null)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
        read = file.baseFile.lastModified() to config
    }

    fun current(): RemoteModel? {
        val target = config().target() ?: return null
        made?.let { (t, model) -> if (t == target) return model }
        // asked again as each request's turn comes: one made just before the user turned it off is not sent
        return HttpRemoteModel(target.url, target.token, executor, target.key, wanted = { config().target() == target })
            .also { made = target to it }
    }

    /**
     * Asks the server for its word pack ([HttpRemoteModel.words]) if the cloud is on and it was not
     * asked today, and hands what comes to [keep], on the server's thread. What fails is tried
     * again tomorrow: a pack is not worth a retry storm. Nothing is sent when the cloud is off.
     */
    fun updateWords(now: Long = System.currentTimeMillis(), keep: (HttpRemoteModel.Words) -> Unit) {
        val target = config().target() ?: return
        val stamp = File(file.baseFile.parentFile, WORDS_CHECKED)
        if (now - stamp.lastModified() < WORDS_EVERY) return
        try {
            stamp.createNewFile()
        } catch (_: IOException) {
            return
        }
        if (!stamp.setLastModified(now)) return
        executor.execute {
            val words = try {
                HttpRemoteModel(target.url, target.token, { it.run() }, target.key, WORDS_TIMEOUT, wanted = { config().target() == target }).words()
            } catch (e: IOException) {
                Timber.i(e, "cloud word pack")
                null
            }
            if (words != null) keep(words)
        }
    }

    private fun load(): CloudConfig {
        val p = Properties().apply { file.openRead().use { load(it) } }
        return CloudConfig(p.getProperty(ENABLED) == "true", p.getProperty(SERVER).orEmpty(), p.getProperty(TOKEN).orEmpty(), p.getProperty(KEY).orEmpty())
    }

    companion object {
        val instance by lazy { CloudServer(File(appContext.noBackupFilesDir, "cloud.properties")) }

        private const val ENABLED = "enabled"
        private const val SERVER = "server"
        private const val TOKEN = "token"
        private const val KEY = "key"
        private const val WORDS_CHECKED = "cloud-words.checked"
        private const val WORDS_EVERY = 24 * 60 * 60 * 1000L
        private const val WORDS_TIMEOUT = 30_000
    }
}
