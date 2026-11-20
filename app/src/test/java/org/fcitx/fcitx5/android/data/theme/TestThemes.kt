/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.scene.theme.SystemColors
import java.io.File

/** Built-in themes read from keyboard-scene's files with no Context; Monet with its fallbacks; each by its 0.5 name. */
object TestThemes {

    // app/build.gradle.kts sets it; the module's directory else
    private val src = File(System.getProperty("scene.src") ?: "../lib/keyboard-scene/src")

    fun read(path: String, system: SystemColors = SystemColors.NONE): List<Theme.Builtin> =
        BuiltinThemeFiles.read(File(src, path).readText(), system)

    private fun builtin(name: String) = read("commonMain/themes/$name.xml")

    private val material by lazy { builtin("material") }
    private val pixel by lazy { builtin("pixel") }
    private val nord by lazy { builtin("nord") }
    // read anew each time: a Monet look is made once, of what ThemeLookMigration keeps then
    private val monet get() = builtin("monet")
    private val transparent by lazy { read("commonTest/data/transparent.xml") }

    val MaterialLight get() = material[0]
    val MaterialDark get() = material[1]
    val PixelLight get() = pixel[0]
    val PixelDark get() = pixel[1]
    val NordLight get() = nord[0]
    val NordDark get() = nord[1]
    val DeepBlue by lazy { builtin("deep-blue").single() }
    val Monokai by lazy { builtin("monokai").single() }
    val AMOLEDBlack by lazy { builtin("amoled-black").single() }
    val MonetLight get() = monet[0]
    val MonetDark get() = monet[1]
    val TransparentLight get() = transparent[0]
    val TransparentDark get() = transparent[1]

    /** As the app lists them (Monet's first). */
    val builtins: List<Pair<String, Theme.Builtin>> by lazy {
        listOf(
            "MonetLight" to MonetLight, "MonetDark" to MonetDark,
            "MaterialLight" to MaterialLight, "MaterialDark" to MaterialDark,
            "PixelLight" to PixelLight, "PixelDark" to PixelDark,
            "NordLight" to NordLight, "NordDark" to NordDark,
            "DeepBlue" to DeepBlue, "Monokai" to Monokai, "AMOLEDBlack" to AMOLEDBlack,
        )
    }
}
