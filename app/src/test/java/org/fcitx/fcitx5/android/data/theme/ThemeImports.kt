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

/** Through the theme page's path ([ThemeFilesManager.openTheme]), not a second one; a 4.0 pack fails. */
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

/** A 3.0 zip as the app exported before 0.6.0 and the web maker still uses: test data only. */
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

/** The built-in material file as user pack [name] with [styles] last and [files] beside, imported and placed; light version. */
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
