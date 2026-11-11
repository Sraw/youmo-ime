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

    /** Where they are unpacked: the folder whose `v4/` holds them, as the themes' does the user's. */
    fun dir(context: Context) = File(context.noBackupFilesDir, DIR)

    /**
     * The packs unpacked into [root] from [open]'s zips (by [FILES]' ids), unless [root] has those of [version] already; whether they are
     * there. One that does not unpack (a full disk) leaves none: tried again at the next start, its themes not listed.
     */
    fun unpack(root: File, version: String = BuildConfig.VERSION_NAME + BuildConfig.VERSION_CODE, open: (String) -> InputStream): Boolean {
        val stamp = File(root, STAMP)
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == version) return true
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
            failed(root, e)
        } catch (e: IllegalStateException) {
            // a zip whose theme.xml does not read: the build's bug, not a crash at every start
            failed(root, e)
        } catch (e: SecurityException) {
            failed(root, e)
        }
    }

    /**
     * The packs unpacked in [root], each version one, in [FILES]' order, [Theme.Pack.builtin]; none of a name in
     * [taken] (a pack of the user's of the same name, imported before it was built in: the user's shown).
     */
    fun list(root: File, builtins: List<Theme.Builtin>, taken: Set<String> = emptySet()): List<Theme.Pack> {
        if (!File(root, STAMP).isFile) return emptyList()
        val order = File(root, ORDER).takeIf { it.isFile }?.readLines().orEmpty()
        return ThemePackFiles.list(root, builtins).filter { it.file !in taken }.map { it.copy(builtin = true) }
            .sortedBy { p -> order.indexOf(p.file).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private const val ORDER = ".order"

    private fun failed(root: File, e: Throwable): Boolean {
        Timber.w(e, "Built-in theme packs not unpacked")
        root.deleteRecursively()
        return false
    }

    /** The zip of [id], one of [FILES], from the app's resources. */
    fun open(context: Context, id: String): InputStream = context.resources.openRawResource(FILES.first { it.first == id }.second)
}
