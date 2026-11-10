/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
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
    private const val VERSION = "v1"

    // what was read (or written since): the keyboard, made before the service's read is done, told when it is
    private val read = MutableStateFlow<Map<String, String>?>(null)

    @Volatile
    private var written: Map<String, String>? = null

    /** The keys as kept: null till read, or none kept. */
    val known: Map<String, String>? get() = read.value

    /** [known] as it comes. */
    val keys: StateFlow<Map<String, String>?> get() = read

    // device-protected: the keyboard starts before the first unlock too (direct boot), where the credential store
    // can be neither read nor written
    private fun file(ctx: Context) = File(ctx.createDeviceProtectedStorageContext().filesDir, FILE)

    // where 0.6's betas kept it (the credential store): moved over once, when the user has unlocked and the
    // device-protected copy is not there yet, so an upgrade does not hold the punctuation keys back again.
    // One there already (the host wrote it before the first unlock after the upgrade): the old one only deleted
    internal fun migrate(ctx: Context) {
        val f = file(ctx)
        val old = File(ctx.filesDir, FILE)
        if (ctx.getSystemService(UserManager::class.java)?.isUserUnlocked != true || !old.isFile) return
        if (f.exists()) {
            // the two paths one file (a store with no device-protected part of its own): the only copy kept
            if (f.absolutePath != old.absolutePath) old.delete()
            return
        }
        try {
            old.copyTo(f)
            old.delete()
        } catch (e: IOException) {
            Timber.w(e, "punctuation start keys not moved")
        }
    }

    /** Read on a thread of its own (the service's onCreate): a file read kept off the main thread. */
    fun preload(ctx: Context) {
        val f = file(ctx)
        thread(name = "punctuation-start-keys") {
            migrate(ctx)
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

    /** [keys] kept for the next start, written only when changed. */
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
            // only once on disk: a write that failed (a full disk) is tried again at the next change
            if (tmp.renameTo(f)) synchronized(this) { written = keys } else Timber.w("punctuation start keys: not renamed")
        } catch (e: IOException) {
            Timber.w(e, "punctuation start keys not written")
        }
    }

    /** Forgotten, the file too: as a first install (tests). */
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

    /** [load] at once, for a test that starts the keyboard with no service start before it. */
    fun loadNow(ctx: Context) {
        migrate(ctx)
        val keys = load(file(ctx))
        synchronized(this) { read.value = keys }
    }
}
