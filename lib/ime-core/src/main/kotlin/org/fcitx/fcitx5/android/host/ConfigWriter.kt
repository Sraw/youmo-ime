/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import org.fcitx.fcitx5.android.engine.platform.jvm.replaceFile

/**
 * The host's config files written off its thread, merged: each [write] says a file changed and how
 * to make its text, and [executor] (one background thread) writes every file once with the latest
 * text, however many changes came before it ran. So a state change (繁體 on, the default input
 * method) is on disk shortly after it happens, not only when a settings page closes: native fcitx
 * saved such state at `MainActivity.onStop`, and a process killed from the keyboard lost it.
 *
 * Not [writable] (direct boot, before the user unlocks: `filesDir`, not the user's files; or a config
 * whose place is not settled, [ConfigHome.Problem.EXTERNAL_AWAY]) nothing is ever written, [flush]
 * included; the process is stopped at unlock and the next one reads the user's. A file [hold] names
 * is not written either: one there that could not be read, which the defaults in its place must not
 * replace.
 */
class ConfigWriter(
    val writable: Boolean,
    private val executor: Executor,
    /** Writes [text] to [path], whether it is on disk now: a failed write (a full disk) is not. */
    private val sink: (path: String, text: String) -> Boolean,
) {
    private val pending = LinkedHashMap<String, () -> String>()
    // what is to run once a write of the path is on disk ([write]'s landed)
    private val landing = HashMap<String, MutableList<() -> Unit>>()
    private val held = HashSet<String>()
    private var scheduled = false

    /**
     * [path] is not written from now on, what is pending for it dropped. A write [drain] has already
     * begun for it still lands: held between drain's check and its sink, the text is the one read
     * before, not a default.
     */
    fun hold(path: String) {
        synchronized(this) {
            held += path
            pending.remove(path)
            // what was to run once it lands never does: not at a write after a release either
            landing.remove(path)
        }
    }

    /**
     * [path] written again: it read back since [hold] (a reload), or is found not there at all, so what
     * the host holds of it is the file's (or the defaults a missing file means). Never released, it
     * stays held for the rest of the process.
     */
    fun release(path: String) {
        synchronized(this) { held -= path }
    }

    /**
     * [path] (under the config home, `conf/chttrans.conf`) is to hold [text]'s text, made when it is
     * written. [landed] runs on the writer's thread once a write of [path] is on disk, this one or a
     * later one merged into it: never if none lands (held, not [writable], the write failed), so what
     * it settles is settled only by the file.
     */
    fun write(path: String, landed: (() -> Unit)? = null, text: () -> String) {
        if (!writable) return
        val schedule = synchronized(this) {
            if (path in held) return
            pending[path] = text
            landed?.let { landing.getOrPut(path) { mutableListOf() } += it }
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
        // what is to run once a path lands taken with its text: one asked for while this batch is
        // written waits for the write after it, whose text has what it settles
        val (batch, lands) = synchronized(this) {
            scheduled = false
            val batch = LinkedHashMap(pending).also { pending.clear() }
            batch to batch.keys.mapNotNull { path -> landing.remove(path)?.let { path to it } }.toMap()
        }
        for ((path, text) in batch) {
            // held after the batch was taken: dropped, as hold drops what is pending
            if (synchronized(this) { path in held }) continue
            val waiting = lands[path]
            if (sink(path, text())) {
                waiting?.forEach { it() }
            } else if (waiting != null) {
                // not on disk: a later write of it may land them
                synchronized(this) { if (path !in held) landing.getOrPut(path) { mutableListOf() }.addAll(0, waiting) }
            }
        }
    }

    companion object {
        /**
         * Writes under [root] through [replaceFile]: to `<file>.new`, synced, then renamed over the
         * file, so a reader (or the next start after a power cut) sees the old text or the new one,
         * never part of it nor an empty file.
         */
        fun files(root: File, onError: (String, IOException) -> Unit): (String, String) -> Boolean = { path, text ->
            val file = File(root, path)
            try {
                file.parentFile?.mkdirs()
                replaceFile(file, File(file.path + ".new")) { it.writeText(text) }
                true
            } catch (e: IOException) {
                onError(path, e)
                false
            }
        }
    }
}
