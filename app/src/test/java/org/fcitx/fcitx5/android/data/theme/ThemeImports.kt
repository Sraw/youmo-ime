/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The 3.0 theme zip [src] imported the way the theme page imports it ([ThemeFilesManager.openTheme]): the tests of
 * 3.0 imports go through the path a user's import takes, not a second one of its own. A 4.0 pack there fails.
 */
fun ThemeFilesManager.importTheme(
    src: InputStream,
    themes: List<Theme> = ThemeManager.getAllThemes(),
): Result<ThemeFilesManager.Imported> =
    openTheme(src, themes).mapCatching { opened ->
        when (opened) {
            is ThemeFilesManager.Opened.Json -> opened.imported
            is ThemeFilesManager.Opened.Pack -> {
                opened.discard()
                error("a theme 4.0 pack, not a 3.0 theme")
            }
        }
    }

/**
 * [theme] as a theme 3.0 zip (its files by name, then its json), as the app exported before 0.6.0 and the theme
 * maker's page still makes and reads: test data only (the zips the page's tests read, ThemeMakerRulesTest).
 */
fun ThemeFilesManager.exportJson(theme: Theme.Custom, dest: OutputStream): Result<Unit> =
    runCatching {
        ZipOutputStream(dest.buffered()).use { zip ->
            theme.look.files().forEach { path ->
                zip.putNextEntry(ZipEntry(File(path).name))
                File(path).inputStream().use { it.copyTo(zip) }
            }
            val named = theme.copy(look = theme.look.mapFiles { File(it).name })
            zip.putNextEntry(ZipEntry("${named.name}.json"))
            zip.write(Json.encodeToString(CustomThemeSerializer, named).encodeToByteArray())
            zip.closeEntry()
        }
    }

/**
 * A user's pack (what no built-in theme is, as motion, effects or a style of a layout's): the built-in material's file
 * named [name], [styles] put last, [files] (name to bytes) beside it, imported as the theme page imports it and placed;
 * its light version.
 */
fun materialPack(name: String, vararg styles: String, files: Map<String, ByteArray> = emptyMap()): Theme {
    val text = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_material)
        .replace("name=\"material\"", "name=\"$name\"")
        .replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>"))
    val zip = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML))
            it.write(text.encodeToByteArray())
            for ((file, content) in files) {
                it.putNextEntry(ZipEntry(file))
                it.write(content)
            }
        }
    }.toByteArray()
    val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack
    val versions = opened.commit().getOrThrow()
    ThemeManager.packPlaced(versions[0].file)
    return versions.first { !it.isDark }
}
