/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * What each punctuation key types at the host's start (HostCore.startPunctuationKeys), kept from the last run: the
 * keyboard draws the keys by it before the host is up, so a key pressed then (typed once the host is) shows what it
 * types (060-render-p2 S7a). Written by the host on its thread as it changes; read off the main thread at the
 * service's start. None kept (a first install): [known] null, and the keyboard holds the punctuation keys back.
 */
object PunctuationStartKeys {
    private const val FILE = "punctuation-start-keys"

    @Volatile
    private var read: Map<String, String>? = null

    @Volatile
    private var written: Map<String, String>? = null

    /** The keys as kept: null till read, or none kept. */
    val known: Map<String, String>? get() = read

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Read on a thread of its own (the service's onCreate): a file read kept off the main thread. */
    fun preload(ctx: Context) {
        val f = file(ctx)
        thread(name = "punctuation-start-keys") {
            val keys = load(f)
            synchronized(this) { if (read == null && written == null) read = keys }
        }
    }

    /** [keys] kept for the next start, written only when changed. */
    fun write(ctx: Context, keys: Map<String, String>) {
        synchronized(this) {
            if (keys == written) return
            written = keys
            read = keys
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        try {
            tmp.writeText(keys.entries.joinToString("") { "${it.key}\t${it.value}\n" })
            if (!tmp.renameTo(f)) Timber.w("punctuation start keys: not renamed")
        } catch (e: IOException) {
            Timber.w(e, "punctuation start keys not written")
        }
    }

    /** Forgotten, the file too: as a first install (tests). */
    fun forget(ctx: Context) {
        synchronized(this) {
            read = null
            written = null
        }
        file(ctx).delete()
    }

    private fun load(f: File): Map<String, String>? = try {
        if (!f.exists()) null else f.readLines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    } catch (e: IOException) {
        Timber.w(e, "punctuation start keys not read")
        null
    }

    /** [load] at once, for a test that starts the keyboard with no service start before it. */
    fun loadNow(ctx: Context) {
        val keys = load(file(ctx))
        synchronized(this) { read = keys }
    }
}
