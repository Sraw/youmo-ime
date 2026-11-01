package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.host.ConfigHome
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

    // the app's own files, an older build's themes on the external storage moved in first (A4): settled by
    // the theme list at the app's start, on the main thread. That is the process's first ask for the themes
    // (Application.onCreate), so it waits on no other thread's move; and the move is once an install, of a
    // few json files and their images. Off the main thread the active theme would not be there to draw with.
    // one directory a process (HostConfigDir), asked for each time as the engine's data is
    private val themes get() = HostConfigDir.inUse(ConfigHome.THEME)
    private val dir get() = themes.let { it.dir.also { dir -> if (it.writable) dir.mkdirs() } }

    /** Whether a theme can be saved, deleted or imported: not while the themes are not settled (A4). */
    val writable get() = themes.writable

    private fun notSettled(): Nothing = throw IOException(appContext.getString(R.string.user_data_not_settled))

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

    // the images by name only, as exported: an absolute path breaks once the files dir moves
    private fun Theme.Custom.withImageNames() = backgroundImage?.let {
        copy(
            backgroundImage = it.copy(
                croppedFilePath = File(it.croppedFilePath).name,
                srcFilePath = File(it.srcFilePath).name
            )
        )
    } ?: this

    fun saveThemeFiles(theme: Theme.Custom) {
        if (!writable) notSettled()
        themeFile(theme).writeText(Json.encodeToString(CustomThemeSerializer, theme.withImageNames()))
    }

    fun deleteThemeFiles(theme: Theme.Custom) {
        if (!writable) notSettled()
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
                    // one that did not read is left as it is (nothing writes a theme not listed); the user told
                    if (e is IOException) EngineNotices.failedOnce(R.string.user_data_unreadable)
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }
                // the name picks the file save and delete write, so a planted one must not be a path out of dir
                if (!isPlainFileName(theme.name)) {
                    Timber.w("Theme file ${it.absolutePath} has a name that is a path: ${theme.name}")
                    return@decode null
                }
                val image = theme.backgroundImage
                val found = if (image == null) {
                    theme
                } else {
                    val here = image.foundIn(dir) { it.isThemeImage() }
                    if (here == null) {
                        Timber.w("Cannot find background image file for theme ${theme.name}")
                        return@decode null
                    }
                    theme.copy(backgroundImage = here)
                }
                // Update the saved file if migration happens
                if (migrated && writable) {
                    // listed by age: rewritten newest first, the newest would get the oldest new time
                    val modified = it.lastModified()
                    saveThemeFiles(found)
                    themeFile(found).setLastModified(modified)
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
                val tweakedTheme = theme.withImageNames()
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
            if (!writable) notSettled()
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
                    if (!isPlainFileName(decoded.name)) errorRuntime(R.string.invalid_value)
                    if (ThemeManager.BuiltinThemes.find { it.name == decoded.name } != null)
                        errorRuntime(R.string.exception_theme_name_clash)
                    val oldTheme = ThemeManager.getTheme(decoded.name) as? Theme.Custom
                    val newCreated = oldTheme == null
                    val newTheme = if (decoded.backgroundImage != null) {
                        val srcFile = importedImageFile(decoded.backgroundImage.srcFilePath)
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val croppedFile = importedImageFile(decoded.backgroundImage.croppedFilePath)
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        // both found before either is copied: one refused would leave the other in dir
                        val extractedSrcFile = extracted.find { it.name == srcFile.name }
                            ?: errorRuntime(R.string.exception_theme_src_image)
                        val extractedCroppedFile = extracted.find { it.name == croppedFile.name }
                            ?: errorRuntime(R.string.exception_theme_cropped_image)
                        val oldSrcFile = oldTheme?.backgroundImage?.srcFilePath?.let { File(it) }
                        val srcFileNameMatches = oldSrcFile?.name == srcFile.name
                        // allow overwriting background image files when theme and file names all are same
                        extractedSrcFile.copyTo(srcFile, overwrite = srcFileNameMatches)
                        val oldCroppedFile =
                            oldTheme?.backgroundImage?.croppedFilePath?.let { File(it) }
                        val croppedFileNameMatches = oldCroppedFile?.name == croppedFile.name
                        extractedCroppedFile.copyTo(croppedFile, overwrite = croppedFileNameMatches)
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

// a json names its images by file name; an older one by absolute path, of the old package id's dir in a backup
// from before the renaming to 幽默输入法, while the images came along into this one; null if either is missing
internal fun Theme.Custom.CustomBackground.foundIn(dir: File, isThemeImage: (File) -> Boolean) =
    copy(
        croppedFilePath = File(dir, File(croppedFilePath).name).path,
        srcFilePath = File(dir, File(srcFilePath).name).path
    ).takeIf { isThemeImage(File(it.croppedFilePath)) && isThemeImage(File(it.srcFilePath)) }
