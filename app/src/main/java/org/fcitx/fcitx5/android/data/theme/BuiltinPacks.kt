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
 * The built-in themes that are theme 4.0 packs (pictures, sounds, effects: 060-builtin-themes), shipped as
 * `.theme` zips in the raw resources (`builtin_theme_<id>`: Robolectric has them, not the assets) and drawn as an imported pack is ([Theme.Pack], [Theme.Pack.builtin]):
 * one code path. Unpacked once an install of the app's version into [dir]`/v4/<name>/` (not the themes' folder:
 * no backup carries them), listed from there each start; their pictures decoded only when drawn, as a pack's are.
 */
object BuiltinPacks {

    private const val DIR = "builtin-themes"

    /** Each zip by its id, its raw resource, in the list's order. */
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

    // "<root>|<version>" of each unpack that failed in this process, with when (System.nanoTime): not tried
    // again (and its folder deleted again) at every refresh of the themes, only [RETRY_NANOS] later, as a
    // full disk may have been cleared while the keyboard's process lives on
    private val failedHere = HashMap<String, Long>()

    private const val RETRY_NANOS = 10L * 60 * 1_000_000_000

    /**
     * Where they are unpacked: the folder whose `v4/` holds them, as the themes' does the user's. Device-protected
     * storage: before the first unlock after a reboot the keyboard draws them still (a slot moved off Nord, say).
     */
    fun dir(context: Context) = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, DIR)

    /**
     * The packs unpacked into [root] from [open]'s zips (by [FILES]' ids), unless [root] has those of [version] already; whether they are
     * there. One that does not unpack (a full disk, or a zip the build got wrong) leaves none: tried again ten minutes later or at the next
     * start (not at each refresh of the themes), its themes not listed. A folder gone while the stamp is there is
     * unpacked again.
     */
    fun unpack(root: File, version: String = "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}", open: (String) -> InputStream): Boolean {
        val stamp = File(root, STAMP)
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == version && placed(root)) return true
        val attempt = "${root.path}|$version"
        if (synchronized(failedHere) { failedHere[attempt]?.let { System.nanoTime() - it < RETRY_NANOS } == true }) return false
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
            failed(root, attempt, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // a zip whose theme.xml or an entry's name does not read: the build's bug, not a crash at every start
            failed(root, attempt, e)
        }
    }

    /** The names of the packs unpacked in [root] ([unpack]), in [FILES]' order; none where they are not. */
    fun names(root: File): List<String> =
        if (!File(root, STAMP).isFile) emptyList() else File(root, ORDER).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty()

    // each folder [ORDER] names is there
    private fun placed(root: File): Boolean {
        val v4 = ThemePackFiles.folder(root)
        val names = File(root, ORDER).takeIf { it.isFile }?.let { runCatching { it.readLines() }.getOrNull() } ?: return false
        return names.all { File(v4, it).isDirectory }
    }

    /**
     * The packs unpacked in [root], each version one, in [FILES]' order, [Theme.Pack.builtin]; none of a name in
     * [taken] (a pack of the user's of the same name, imported before it was built in: the user's shown).
     */
    fun list(root: File, builtins: List<Theme.Builtin>, taken: Set<String> = emptySet()): List<Theme.Pack> {
        if (!File(root, STAMP).isFile) return emptyList()
        val order = File(root, ORDER).takeIf { it.isFile }?.readLines().orEmpty()
        return ThemePackFiles.list(root, builtins, fresh = false).filter { it.file !in taken }.map { it.copy(builtin = true) }
            .sortedBy { p -> order.indexOf(p.file).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private fun failed(root: File, attempt: String, e: Throwable): Boolean {
        Timber.w(e, "Built-in theme packs not unpacked")
        synchronized(failedHere) { failedHere[attempt] = System.nanoTime() }
        root.deleteRecursively()
        return false
    }

    /** The zip of [id], one of [FILES], from the app's resources. */
    fun open(context: Context, id: String): InputStream = context.resources.openRawResource(FILES.first { it.first == id }.second)
}
