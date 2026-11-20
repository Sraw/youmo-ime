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
 * Config files written off the host thread, merged: each file once with its latest text, soon after a state change
 * (native fcitx saved at `MainActivity.onStop`, and a process killed from the keyboard lost it). Not [writable]
 * (direct boot, or [ConfigHome.Problem.EXTERNAL_AWAY]) nothing is written; a [hold]ed file (one that could not be
 * read) is not either, so defaults never replace it.
 */
class ConfigWriter(
    val writable: Boolean,
    private val executor: Executor,
    /** False when not on disk (a full disk). */
    private val sink: (path: String, text: String) -> Boolean,
) {
    private val pending = LinkedHashMap<String, () -> String>()
    private val landing = HashMap<String, MutableList<() -> Unit>>()
    private val held = HashSet<String>()
    private var scheduled = false

    /** A write [drain] already began still lands, with the text read before, not a default. */
    fun hold(path: String) {
        synchronized(this) {
            held += path
            pending.remove(path)
            // what was to run once it lands never does: not at a write after a release either
            landing.remove(path)
        }
    }

    /** It read back since [hold], or is not there at all; never released, held for the rest of the process. */
    fun release(path: String) {
        synchronized(this) { held -= path }
    }

    /** [landed] runs on the writer's thread once a write of [path] (this or a later merged one) is on disk; never if none lands. */
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

    /** On the caller's thread: the host's stop. */
    fun flush() {
        if (writable) drain()
    }

    // one drain at a time: a flush returns only once a write already taken is on disk too, as fcitx's save()
    private val draining = Any()

    private fun drain() = synchronized(draining) {
        // landing callbacks taken with the text: one asked for meanwhile waits for the next write
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
        /** Via [replaceFile] (`<file>.new`, synced, renamed): a reader sees the old text or the new, never part. */
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
