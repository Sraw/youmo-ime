/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Kept from the last run so the keyboard draws the punctuation keys correctly before the host is up. With none kept
 * (a first install) [known] is null and the keyboard holds the punctuation keys back.
 */
object PunctuationStartKeys {
    private const val FILE = "punctuation-start-keys"
    private const val VERSION = "v1"

    // the keyboard, made before the service's read is done, is told when it is
    private val read = MutableStateFlow<Map<String, String>?>(null)

    @Volatile
    private var written: Map<String, String>? = null

    /** Null till read, or none kept. */
    val known: Map<String, String>? get() = read.value

    val keys: StateFlow<Map<String, String>?> get() = read

    // device-protected: the keyboard starts before the first unlock (direct boot)
    private fun file(ctx: Context) = File(ctx.createDeviceProtectedStorageContext().filesDir, FILE)

    /** On its own thread, off the main one. */
    fun preload(ctx: Context) {
        val f = file(ctx)
        thread(name = "punctuation-start-keys") {
            val keys = load(f)
            synchronized(this) {
                if (read.value == null && written == null) {
                    read.value = keys
                    // as on disk: the host's first write of the same keys is no rewrite
                    written = keys
                }
            }
        }
    }

    /** Written only when changed. */
    fun write(ctx: Context, keys: Map<String, String>) {
        synchronized(this) {
            if (keys == written) return
            read.value = keys
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        try {
            // a header (version, count) so a torn or empty file reads as none kept, not as punctuation off
            val text = "$VERSION\t${keys.size}\n" + keys.entries.joinToString("") { "${it.key}\t${it.value}\n" }
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            // recorded only once on disk: a failed write (full disk) retries at the next change
            if (tmp.renameTo(f)) synchronized(this) { written = keys } else Timber.w("punctuation start keys: not renamed")
        } catch (e: IOException) {
            Timber.w(e, "punctuation start keys not written")
        }
    }

    /** As a first install (tests). */
    fun forget(ctx: Context) {
        synchronized(this) {
            read.value = null
            written = null
        }
        file(ctx).delete()
    }

    private fun load(f: File): Map<String, String>? = try {
        if (!f.exists()) null else parse(f.readLines())
    } catch (e: IOException) {
        Timber.w(e, "punctuation start keys not read")
        null
    }

    internal fun parse(lines: List<String>): Map<String, String>? {
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size != 2 || header[0] != VERSION) return null
        val entries = lines.drop(1).filter { it.isNotEmpty() }
        if (header[1].toIntOrNull() != entries.size || entries.any { '\t' !in it }) return null
        val keys = entries.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        // a torn last line: its mark lost
        return keys.takeIf { it.all { (k, v) -> k.isNotEmpty() && v.isNotEmpty() } }
    }

    /** For a test that starts the keyboard with no service start before it. */
    fun loadNow(ctx: Context) {
        val keys = load(file(ctx))
        synchronized(this) { read.value = keys }
    }
}
