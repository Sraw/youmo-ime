/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Resources
import androidx.annotation.RawRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.ThemeReader

/**
 * Theme 4.0 files from keyboard-scene's src/commonMain/themes (the web reads them too), copied as raw resources
 * (`scene_theme_<name>`). A two-version file is two [Theme.Builtin]s, `<name>#light` and `<name>#dark`.
 */
object BuiltinThemeFiles {

    /** In the list's order: Monet's first. */
    val FILES: List<Pair<String, Int>> = listOf(
        MONET to R.raw.scene_theme_monet,
        MATERIAL to R.raw.scene_theme_material,
        PIXEL to R.raw.scene_theme_pixel,
        AMOLED_BLACK to R.raw.scene_theme_amoled_black,
    )

    /** Its colours are the system's (`system()`), reread when they change. */
    const val MONET = "monet"
    const val MATERIAL = "material"
    const val PIXEL = "pixel"
    const val AMOLED_BLACK = "amoled-black"

    private const val LIGHT = "light"
    private const val DARK = "dark"
    private const val SEPARATOR = '#'

    fun ref(name: String, dark: Boolean, paired: Boolean): String = if (paired) "$name$SEPARATOR${if (dark) DARK else LIGHT}" else name

    /** 0.5's names (also in 3.0 files and the old maker) to current versions: users see after the upgrade what they saw. */
    val LEGACY: Map<String, String> = mapOf(
        "MaterialLight" to "material#light",
        "MaterialDark" to "material#dark",
        "PixelLight" to "pixel#light",
        "PixelDark" to "pixel#dark",
        "NordLight" to "素雅#light",
        "NordDark" to "素雅#dark",
        "DeepBlue" to "简蓝#dark",
        "Monokai" to "墨灰",
        "AMOLEDBlack" to "amoled-black",
        "MonetLight" to "monet#light",
        "MonetDark" to "monet#dark",
    )

    /**
     * Themes 0.6.0 dropped (nord, deep-blue, monokai), by every name settings may hold, mapped to the closest built-in pack
     * ([ThemePrefs.migrateThemeRefs]); the names stay reserved.
     */
    val RETIRED: Map<String, String> = mapOf(
        "nord" to "素雅#light",
        "nord#light" to "素雅#light",
        "nord#dark" to "素雅#dark",
        "deep-blue" to "简蓝#dark",
        "deep-blue#dark" to "简蓝#dark",
        "monokai" to "墨灰",
        "monokai#dark" to "墨灰",
    )

    /** Null for none. */
    fun moved(name: String): String? = LEGACY[name] ?: RETIRED[name]

    fun legacyName(ref: String): String? = LEGACY.entries.firstOrNull { it.value == ref }?.key

    fun load(resources: Resources, system: SystemColors, files: List<Pair<String, Int>> = FILES): List<Theme.Builtin> =
        files.flatMap { (name, raw) -> read(text(resources, raw), system).also { check(it.first().file == name) { "${it.first().file} in the file of $name" } } }

    fun text(resources: Resources, @RawRes raw: Int): String = resources.openRawResource(raw).use { it.readBytes().decodeToString() }

    /** Light first. An unreadable one is a build bug: thrown, not shown as no theme. */
    fun read(text: String, system: SystemColors): List<Theme.Builtin> {
        val theme = when (val t = ThemeReader.theme(text, system)) {
            is Parsed.Ok -> t.value
            is Parsed.Failed -> error("a built-in theme does not read: ${t.problems}")
        }
        val paired = theme.variants.size > 1
        return listOf(false, true).filter { it in theme.variants }.map { dark ->
            Theme.Builtin.of(theme.variant(dark), ref(theme.name, dark, paired), followsSystem = theme.name == MONET)
        }
    }

    /** By name and version (`pixel#dark`), or name alone for a one-version theme (`deep-blue#dark` too); packs likewise. */
    fun <T : Theme.Versioned> find(themes: List<T>, ref: String): T? = themes.find { it.name == ref } ?: run {
        val name = ref.substringBeforeLast(SEPARATOR)
        val version = ref.substringAfterLast(SEPARATOR, "")
        val dark = when (version) {
            LIGHT -> false
            DARK -> true
            else -> return null
        }
        themes.singleOrNull { it.file == name }?.takeIf { it.isDark == dark }
    }
}
