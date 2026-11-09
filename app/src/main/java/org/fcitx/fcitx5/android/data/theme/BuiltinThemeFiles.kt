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
 * The built-in themes: theme 4.0 files of keyboard-scene's src/commonMain/themes, which the web reads
 * too, copied by the build as raw resources (`scene_theme_<name>`, app/build.gradle.kts). A file of two
 * versions is two [Theme.Builtin]s, `<name>#light` and `<name>#dark` (060-render-p3 T3: how the settings
 * refer to one); a file of one, its name alone.
 */
object BuiltinThemeFiles {

    /** Each file by its theme's name, in the list's order: Monet's first, as before. */
    val FILES: List<Pair<String, Int>> = listOf(
        MONET to R.raw.scene_theme_monet,
        "material" to R.raw.scene_theme_material,
        "pixel" to R.raw.scene_theme_pixel,
        "nord" to R.raw.scene_theme_nord,
        "deep-blue" to R.raw.scene_theme_deep_blue,
        "monokai" to R.raw.scene_theme_monokai,
        "amoled-black" to R.raw.scene_theme_amoled_black,
    )

    /** The theme whose colours are the system's (`system()`); read again when they change. */
    const val MONET = "monet"

    private const val LIGHT = "light"
    private const val DARK = "dark"
    private const val SEPARATOR = '#'

    /** How the settings refer to the version [dark] of the built-in theme [name] (one of [paired] versions or its only). */
    fun ref(name: String, dark: Boolean, paired: Boolean): String = if (paired) "$name$SEPARATOR${if (dark) DARK else LIGHT}" else name

    /**
     * 0.5's built-in themes by the names the settings had for them (and theme 3.0 files and the old theme
     * maker give them), each the version it is now: every user sees after the upgrade what they saw.
     */
    val LEGACY: Map<String, String> = mapOf(
        "MaterialLight" to "material#light",
        "MaterialDark" to "material#dark",
        "PixelLight" to "pixel#light",
        "PixelDark" to "pixel#dark",
        "NordLight" to "nord#light",
        "NordDark" to "nord#dark",
        "DeepBlue" to "deep-blue",
        "Monokai" to "monokai",
        "AMOLEDBlack" to "amoled-black",
        "MonetLight" to "monet#light",
        "MonetDark" to "monet#dark",
    )

    /** [ref]'s 0.5 name, if it is a built-in theme 0.5 had. */
    fun legacyName(ref: String): String? = LEGACY.entries.firstOrNull { it.value == ref }?.key

    /** The built-in themes' files read from the app's resources, with [system]'s colours. */
    fun load(resources: Resources, system: SystemColors, files: List<Pair<String, Int>> = FILES): List<Theme.Builtin> =
        files.flatMap { (name, raw) -> read(text(resources, raw), system).also { check(it.first().file == name) { "${it.first().file} in the file of $name" } } }

    /** A file's text, as [load] reads it. */
    fun text(resources: Resources, @RawRes raw: Int): String = resources.openRawResource(raw).use { it.readBytes().decodeToString() }

    /**
     * The built-in theme of [text] as each of its versions, the light first, with [system]'s colours.
     * One that does not read is a bug of the build: thrown, not shown as no theme.
     */
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

    /**
     * The built-in theme of [themes] [ref] names: its name and version (`pixel#dark`), or its name alone where
     * it has one version (`deep-blue`, `deep-blue#dark` too); null where none is.
     */
    fun find(themes: List<Theme.Builtin>, ref: String): Theme.Builtin? = themes.find { it.name == ref } ?: run {
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
