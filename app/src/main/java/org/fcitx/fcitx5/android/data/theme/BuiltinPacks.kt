/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.extract
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Built-in themes that are theme 4.0 packs, shipped as raw-resource `.theme` zips (Robolectric has raw, not assets) and
 * drawn like imported packs. Unpacked once per app version into [dir]`/v4/<name>/` (outside backups), listed from there.
 */
object BuiltinPacks {

    private const val DIR = "builtin-themes"

    val FILES: List<Pair<String, Int>> = listOf(
        "jianlan" to R.raw.builtin_theme_jianlan,
        "chengfeng" to R.raw.builtin_theme_chengfeng,
        "suya" to R.raw.builtin_theme_suya,
        "qingxin" to R.raw.builtin_theme_qingxin,
        "mohui" to R.raw.builtin_theme_mohui,
        "liquid-glass" to R.raw.builtin_theme_liquid_glass,
        "neon-cyber" to R.raw.builtin_theme_neon_cyber,
        "tianqing" to R.raw.builtin_theme_tianqing,
        "keycap" to R.raw.builtin_theme_keycap,
        "cream" to R.raw.builtin_theme_cream,
        "paper" to R.raw.builtin_theme_paper,
        "dopamine" to R.raw.builtin_theme_dopamine,
    )

    private const val STAMP = ".unpacked"

    private const val ORDER = ".order"

    // failed unpacks this process, with when: retried only after [RETRY_NANOS] (a full disk may be cleared meanwhile), not at every refresh
    private val failedHere = HashMap<String, Long>()

    private const val RETRY_NANOS = 10L * 60 * 1_000_000_000

    /** Device-protected: before the first unlock after a reboot the keyboard still draws them. */
    fun dir(context: Context) = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, DIR)

    /**
     * Unless [root] has [version]'s already; whether they are there. A failed unpack leaves none, retried ten minutes later or
     * next start, its themes unlisted. A folder gone under a present stamp is unpacked again.
     */
    fun unpack(
        root: File,
        version: String = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}",
        now: () -> Long = System::nanoTime,
        open: (String) -> InputStream,
    ): Boolean {
        val stamp = File(root, STAMP)
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == version && placed(root)) return true
        val attempt = "${root.path}|$version"
        if (synchronized(failedHere) { failedHere[attempt]?.let { now() - it < RETRY_NANOS } == true }) return false
        return try {
            root.deleteRecursively()
            val v4 = ThemePackFiles.folder(root)
            val names = FILES.map { (id) ->
                val temp = File(root, ".$id")
                temp.mkdirs()
                ZipInputStream(open(id)).use { it.extract(temp, keep = ThemePackFiles::unpacked) }
                val name = ThemePackFiles.read(File(temp, ThemePackFiles.THEME_XML).readText()).name
                v4.mkdirs()
                if (!temp.renameTo(File(v4, name))) throw IOException("$id not placed")
                name
            }
            File(root, ORDER).writeText(names.joinToString("\n"))
            stamp.writeText(version)
            true
        } catch (e: IOException) {
            failed(root, attempt, now(), e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // an unreadable theme.xml or entry name: a build bug, not a crash at every start
            failed(root, attempt, now(), e)
        }
    }

    /** In [FILES]' order; none where they are not unpacked. */
    fun names(root: File): List<String> =
        if (!File(root, STAMP).isFile) emptyList() else File(root, ORDER).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty()

    private fun placed(root: File): Boolean {
        val v4 = ThemePackFiles.folder(root)
        val names = File(root, ORDER).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() } ?: return false
        return names.all { File(v4, it).isDirectory }
    }

    /** Each version one, [Theme.Pack.builtin]; none of a name in [taken] (a same-named user pack imported earlier wins). */
    fun list(root: File, builtins: List<Theme.Builtin>, taken: Set<String> = emptySet()): List<Theme.Pack> {
        if (!File(root, STAMP).isFile) return emptyList()
        val order = File(root, ORDER).takeIf { it.isFile }?.readLines().orEmpty()
        return ThemePackFiles.list(root, builtins, fresh = false).filter { it.file !in taken }.map { it.copy(builtin = true) }
            .sortedBy { p -> order.indexOf(p.file).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private fun failed(root: File, attempt: String, at: Long, e: Throwable): Boolean {
        Timber.w(e, "Built-in theme packs not unpacked")
        synchronized(failedHere) { failedHere[attempt] = at }
        root.deleteRecursively()
        return false
    }

    fun open(context: Context, id: String): InputStream = context.resources.openRawResource(FILES.first { it.first == id }.second)
}
