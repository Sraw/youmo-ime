/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.store.replaceFile
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The host's config files written off its thread, merged: each [write] says a file changed and how
 * to make its text, and [executor] (one background thread) writes every file once with the latest
 * text, however many changes came before it ran. So a state change (繁體 on, the default input
 * method) is on disk shortly after it happens, not only when a settings page closes: native fcitx
 * saved such state at `MainActivity.onStop`, and a process killed from the keyboard lost it.
 *
 * Not [writable] (direct boot, before the user unlocks: `filesDir`, not the user's files) nothing is
 * ever written, [flush] included; the process is stopped at unlock and the next one reads the user's.
 */
class ConfigWriter(
    private val writable: Boolean,
    private val executor: Executor,
    private val sink: (path: String, text: String) -> Unit,
) {
    private val pending = LinkedHashMap<String, () -> String>()
    private var scheduled = false

    /** [path] (under the config home, `conf/chttrans.conf`) is to hold [text]'s text, made when it is written. */
    fun write(path: String, text: () -> String) {
        if (!writable) return
        val schedule = synchronized(this) {
            pending[path] = text
            !scheduled.also { scheduled = true }
        }
        if (schedule) executor.execute(::drain)
    }

    /** Writes what is pending now, on the caller's thread: the host's stop. */
    fun flush() {
        if (writable) drain()
    }

    // one drain at a time: a flush (save(), the host's stop) returns only once a write the executor
    // had already taken is on disk too, as fcitx's save() is done when it returns
    private val draining = Any()

    private fun drain() = synchronized(draining) {
        val batch = synchronized(this) {
            scheduled = false
            LinkedHashMap(pending).also { pending.clear() }
        }
        for ((path, text) in batch) sink(path, text())
    }

    companion object {
        /**
         * Writes under [root] through [replaceFile]: to `<file>.new`, synced, then renamed over the
         * file, so a reader (or the next start after a power cut) sees the old text or the new one,
         * never part of it nor an empty file.
         */
        fun files(root: File, onError: (String, IOException) -> Unit): (String, String) -> Unit = { path, text ->
            val file = File(root, path)
            try {
                file.parentFile?.mkdirs()
                replaceFile(file, File(file.path + ".new")) { it.writeText(text) }
            } catch (e: IOException) {
                onError(path, e)
            }
        }
    }
}
