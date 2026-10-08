package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.isPlainFileName
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ThemeFilesManager {

    private val dir = File(appContext.getExternalFilesDir(null), "theme").also { it.mkdirs() }

    private fun themeFile(theme: Theme.Custom) = File(dir, theme.name + ".json")

    // the image paths in an imported json are untrusted: only their file name is kept, as in listThemes,
    // and never a json's, which listThemes would load as one more theme
    private fun importedImageFile(path: String) =
        File(path).name.takeIf { isPlainFileName(it) && !it.endsWith(".json", ignoreCase = true) }
            ?.let { File(dir, it) }

    // a planted json (a crafted import, another app on shared storage) would aim delete and export at any file
    private fun File.isThemeImage() = exists() && try {
        canonicalFile.parentFile == dir.canonicalFile
    } catch (_: IOException) {
        false
    }

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val croppedImageFile = File(dir, "$themeName-cropped.png")
        val srcImageFile = File(dir, "$themeName-src")
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun saveThemeFiles(theme: Theme.Custom) {
        themeFile(theme).writeText(Json.encodeToString(CustomThemeSerializer, theme))
    }

    fun deleteThemeFiles(theme: Theme.Custom) {
        themeFile(theme).delete()
        theme.backgroundImage?.let {
            File(it.croppedFilePath).delete()
            File(it.srcFilePath).delete()
        }
    }

    fun listThemes(): MutableList<Theme.Custom> {
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val (theme, migrated) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, it.readText())
                }.getOrElse { e ->
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }
                // the name picks the file save and delete write, so a planted one must not be a path out of dir
                if (!isPlainFileName(theme.name)) {
                    Timber.w("Theme file ${it.absolutePath} has a name that is a path: ${theme.name}")
                    return@decode null
                }
                // the paths are absolute; a backup imported from another package id (the renaming to
                // 幽默输入法) has them in that app's directory, while the images came along into this one
                val image = theme.backgroundImage
                val found = if (image == null ||
                    File(image.croppedFilePath).isThemeImage() && File(image.srcFilePath).isThemeImage()
                ) {
                    theme
                } else {
                    val here = image.copy(
                        croppedFilePath = File(dir, File(image.croppedFilePath).name).path,
                        srcFilePath = File(dir, File(image.srcFilePath).name).path
                    )
                    if (!File(here.croppedFilePath).isThemeImage() || !File(here.srcFilePath).isThemeImage()) {
                        Timber.w("Cannot find background image file for theme ${theme.name}")
                        return@decode null
                    }
                    theme.copy(backgroundImage = here)
                }
                // Update the saved file if migration happens or the images were found elsewhere
                if (migrated || found !== theme) {
                    saveThemeFiles(found)
                }
                return@decode found
            }.toMutableList()
    }

    /**
     * [dest] will be closed on finished
     */
    fun exportTheme(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            ZipOutputStream(dest.buffered()).use { zipStream ->
                // we don't export the internal path of images
                val tweakedTheme = theme.backgroundImage?.let {
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = theme.backgroundImage.croppedFilePath
                                .substringAfterLast('/'),
                            srcFilePath = theme.backgroundImage.srcFilePath
                                .substringAfterLast('/'),
                        )
                    )
                } ?: theme
                if (tweakedTheme.backgroundImage != null) {
                    requireNotNull(theme.backgroundImage)
                    // write cropped image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.croppedFilePath))
                    File(theme.backgroundImage.croppedFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                    // write src image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.srcFilePath))
                    File(theme.backgroundImage.srcFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                }
                // write json
                zipStream.putNextEntry(ZipEntry("${tweakedTheme.name}.json"))
                zipStream.write(
                    Json.encodeToString(CustomThemeSerializer, tweakedTheme)
                        .encodeToByteArray()
                )
                // done
                zipStream.closeEntry()
            }
        }

    /**
     * @return (newCreated, theme, migrated)
     */
    fun importTheme(src: InputStream): Result<Triple<Boolean, Theme.Custom, Boolean>> =
        runCatching {
            ZipInputStream(src).use { zipStream ->
                withTempDir { tempDir ->
                    val extracted = zipStream.extract(tempDir)
                    val jsonFile = extracted.find { it.extension == "json" }
                        ?: errorRuntime(R.string.exception_theme_json)
                    val (decoded, migrated) = Json.decodeFromString(
                        CustomThemeSerializer.WithMigrationStatus,
                        jsonFile.readText()
                    )
                    // the name becomes a file name in dir, so a crafted one must not be a path out of it
                    if (!isPlainFileName(decoded.name)) errorRuntime(R.string.exception_theme_json)
                    if (ThemeManager.BuiltinThemes.find { it.name == decoded.name } != null)
                        errorRuntime(R.string.exception_theme_name_clash)
                    val oldTheme = ThemeManager.getTheme(decoded.name) as? Theme.Custom
                    val newCreated = oldTheme == null
                    val newTheme = if (decoded.backgroundImage != null) {
                        val srcFile = importedImageFile(decoded.backgroundImage.srcFilePath)
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val oldSrcFile = oldTheme?.backgroundImage?.srcFilePath?.let { File(it) }
                        val srcFileNameMatches = oldSrcFile?.name == srcFile.name
                        extracted.find { it.name == srcFile.name }
                            // allow overwriting background image files when theme and file names all are same
                            ?.copyTo(srcFile, overwrite = srcFileNameMatches)
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val croppedFile = importedImageFile(decoded.backgroundImage.croppedFilePath)
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        val oldCroppedFile =
                            oldTheme?.backgroundImage?.croppedFilePath?.let { File(it) }
                        val croppedFileNameMatches = oldCroppedFile?.name == croppedFile.name
                        extracted.find { it.name == croppedFile.name }
                            ?.copyTo(croppedFile, overwrite = croppedFileNameMatches)
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        if (!srcFileNameMatches) {
                            oldSrcFile?.delete()
                        }
                        if (!croppedFileNameMatches) {
                            oldCroppedFile?.delete()
                        }
                        decoded.copy(
                            backgroundImage = decoded.backgroundImage.copy(
                                croppedFilePath = croppedFile.path,
                                srcFilePath = srcFile.path
                            )
                        )
                    } else {
                        decoded
                    }
                    saveThemeFiles(newTheme)
                    Triple(newCreated, newTheme, migrated)
                }
            }
        }

}
