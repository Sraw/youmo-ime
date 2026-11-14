/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
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
