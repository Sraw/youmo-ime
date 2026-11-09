package org.fcitx.fcitx5.android.data.theme

import android.graphics.BitmapFactory
import android.graphics.fonts.Font
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.LegacyLook
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.isPlainFileName
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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

    // the file names in an imported json are untrusted: only their file name is kept, as in listThemes,
    // and never a json's, which listThemes would load as one more theme, nor SAVING's
    private fun importedFile(path: String) =
        File(path).name.takeIf { isPlainFileName(it) && !it.endsWith(".json", ignoreCase = true) && it != SAVING }
            ?.let { File(dir, it) }

    // where a file is written before it is renamed into place: a directory, so no import's file (a plain
    // name, this one refused) can be a file being written, and no listing reads one as a theme
    private const val SAVING = ".saving"

    // what a kill left in SAVING is cleared at the process's first listing, before anything is saved
    private var swept = false

    /**
     * A file written whole beside the themes, in SAVING, and synced: for [renameOver] to put in place,
     * deleted if it could not be written.
     */
    private fun stage(write: (OutputStream) -> Unit): File {
        val temp = File(File(dir, SAVING).apply { mkdirs() }, UUID.randomUUID().toString())
        var written = false
        try {
            FileOutputStream(temp).use { write(it); it.fd.sync() }
            written = true
        } finally {
            if (!written) temp.delete()
        }
        return temp
    }

    private fun File.renameOver(target: File) {
        if (!renameTo(target)) {
            delete()
            throw IOException("cannot put ${target.name} in place")
        }
    }

    // [theme]'s json staged: [modified] set before the rename, which keeps it
    private fun stageJson(theme: Theme.Custom, modified: Long? = null) =
        stage { it.write(Json.encodeToString(CustomThemeSerializer, theme.withFileNames()).encodeToByteArray()) }
            .also { temp -> modified?.let(temp::setLastModified) }

    // a json whole or not at all: writeText cut short (a full disk, a power cut) left an empty or half
    // file, which no start reads again, the theme lost
    private fun writeWhole(theme: Theme.Custom, modified: Long? = null) {
        stageJson(theme, modified).renameOver(themeFile(theme))
        syncDir()
    }

    // the renames on disk too, not only the files' bytes (a power cut right after one could undo it).
    // Linux (Android) syncs a directory opened for reading; another system may refuse: nothing lost but this
    private fun syncDir() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Timber.d(e, "Theme dir not synced")
        }
    }

    /** For a test: the next listing clears SAVING again, as a process's first does. */
    @VisibleForTesting
    internal fun forgetSwept() {
        swept = false
    }

    // a planted json (a crafted import, another app on shared storage) would aim delete and export at any file
    private fun File.isThemeAsset() = exists() && try {
        canonicalFile.parentFile == dir.canonicalFile
    } catch (_: IOException) {
        false
    }

    /** What an import reads of an image or a font before it takes it: Android's, a fake's in a JVM test. */
    internal interface AssetProbe {
        /** Its width and height, read from its header; null if it is no image. */
        fun imageSize(file: File): Pair<Int, Int>?

        fun fontReads(file: File): Boolean
    }

    private object AndroidAssetProbe : AssetProbe {
        override fun imageSize(file: File): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
        }

        override fun fontReads(file: File) = try {
            Font.Builder(file).build()
            true
        } catch (_: Exception) {
            false
        }
    }

    @VisibleForTesting
    internal var probe: AssetProbe = AndroidAssetProbe

    /** Where the themes and their files are (made if it can be written). */
    @VisibleForTesting
    internal val themeDir get() = dir

    // the files by name only, as exported: an absolute path breaks once the files dir moves
    private fun Theme.Custom.withFileNames() = copy(look = look.mapFiles { File(it).name })

    /** @throws IOException if it could not be written; the theme's file is then as it was */
    fun saveThemeFiles(theme: Theme.Custom) {
        if (!writable) notSettled()
        writeWhole(theme)
    }

    fun deleteThemeFiles(theme: Theme.Custom) {
        if (!writable) notSettled()
        themeFile(theme).delete()
        // not a file another theme names too, as an import leaves another's be
        val others = othersFiles(theme.name, ThemeManager.getAllThemes())
        theme.look.files().map(::File).filter { it.isThemeAsset() && it.name !in others }.forEach { it.delete() }
    }

    /**
     * Whether the last [listThemes] could not save a theme of an older format again as 3.0: the old
     * key style settings it was drawn with are kept till it is ([ThemeLookMigration.settle]).
     */
    @Volatile
    var rewriteFailed = false
        private set

    /**
     * The themes in the directory, newest first; one of an older format saved again as 3.0, with
     * [legacy] (the key style settings before 3.0, while they are there) as its keys: drawn as it was.
     * A theme of 3.0 already is never changed by them, so an old backup restored leaves the new ones be.
     */
    fun listThemes(legacy: LegacyLook? = null): MutableList<Theme.Custom> {
        rewriteFailed = false
        if (writable && !swept) {
            swept = true
            val saving = File(dir, SAVING)
            // a plain file there would refuse every save till it went
            if (saving.isDirectory) saving.listFiles()?.forEach { it.delete() } else saving.delete()
        }
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val (theme, migrated) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, it.readText())
                }.getOrElse { e ->
                    // one that did not read (a later build's, one cut short) is left as it is (nothing writes
                    // a theme not listed); the user told
                    EngineNotices.failedOnce(R.string.user_data_unreadable)
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }
                // the name picks the file save and delete write, so a planted one must not be a path out of dir
                if (!isPlainFileName(theme.name)) {
                    Timber.w("Theme file ${it.absolutePath} has a name that is a path: ${theme.name}")
                    return@decode null
                }
                val here = theme.foundIn(dir) { f -> f.isThemeAsset() } ?: run {
                    // a picture or font gone (a backup restored in part, a file deleted by hand): not
                    // listed, as one that does not read; the user told it is gone (a restart finds
                    // no file again) and that importing it again brings it back
                    EngineNotices.failedOnce(R.string.theme_file_missing)
                    Timber.w("Cannot find a file of theme ${theme.name}")
                    return@decode null
                }
                val found = if (migrated && legacy != null && !legacy.isDefault) {
                    here.copy(look = legacy.applyTo(here.look))
                } else {
                    here
                }
                // Update the saved file if migration happens
                if (migrated && writable) {
                    // listed by age: rewritten newest first, the newest would get the oldest new time
                    try {
                        writeWhole(found, it.lastModified())
                    } catch (e: IOException) {
                        // this runs as the app starts: drawn as migrated all the same, saved at the next start
                        Timber.w(e, "Theme ${found.name} not saved again as 3.0")
                        rewriteFailed = true
                    }
                }
                return@decode found
            }.toMutableList()
    }

    /**
     * [dest] will be closed on finished
     */
    fun exportTheme(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            // a planted json would aim it at any file of the app's: only the theme dir's go, checked before any is written
            theme.look.files().map(::File).firstOrNull { !it.isThemeAsset() }?.let {
                dest.close()
                errorRuntime(R.string.exception_theme_file, it.name)
            }
            ZipOutputStream(dest.buffered()).use { zipStream ->
                // the files it names, by name: we don't export their internal paths
                theme.look.files().forEach { path ->
                    zipStream.putNextEntry(ZipEntry(File(path).name))
                    File(path).inputStream().use { it.copyTo(zipStream) }
                }
                val tweakedTheme = theme.withFileNames()
                zipStream.putNextEntry(ZipEntry("${tweakedTheme.name}.json"))
                zipStream.write(
                    Json.encodeToString(CustomThemeSerializer, tweakedTheme)
                        .encodeToByteArray()
                )
                // done
                zipStream.closeEntry()
            }
        }

    /** A theme imported: [created] new (not one of its name replaced), [migrated] from an older format. */
    data class Imported(val created: Boolean, val theme: Theme.Custom, val migrated: Boolean)

    /**
     * Imports the theme zip [src]. [themes] are the themes there are, taken where they are changed (the
     * main thread) before this runs on another: it reads them, not the list being changed.
     */
    fun importTheme(src: InputStream, themes: List<Theme> = ThemeManager.getAllThemes()): Result<Imported> =
        runCatching {
            if (!writable) notSettled()
            ZipInputStream(src).use { zipStream ->
                withTempDir { tempDir ->
                    // counted as it is unpacked: refused at the first entry past a limit, a zip bomb too
                    // a theme's files are at the zip's top, as it is exported; what is in a folder is counted, not taken
                    val extracted = zipStream.extract(tempDir, flat = true) { count, size, total ->
                        ThemeAssetLimits.entry(count, size, total)?.let(::refused)
                    }
                    // not a Mac's "._<name>" beside it (its attributes, some zippers put at the top)
                    val jsonFile = extracted.find { it.isFile && it.extension == "json" && !it.name.startsWith("._") }
                        ?: errorRuntime(R.string.exception_theme_json)
                    val (decoded, migrated) = Json.decodeFromString(
                        CustomThemeSerializer.WithMigrationStatus,
                        jsonFile.readText()
                    )
                    ThemeAssetLimits.look(decoded.look)?.let(::refused)
                    // the name becomes a file name in dir, so a crafted one must not be a path out of it
                    if (!isPlainFileName(decoded.name)) errorRuntime(R.string.invalid_value)
                    if (ThemeManager.BuiltinThemes.find { it.name == decoded.name } != null)
                        errorRuntime(R.string.exception_theme_name_clash)
                    val oldTheme = themes.find { it.name == decoded.name } as? Theme.Custom
                    val newCreated = oldTheme == null
                    // a json of that name not listed: a later build's is kept (this one cannot read it, nor
                    // know its files); one that reads (a file of it missing) is replaced, its files with it; a
                    // broken one is replaced
                    val unlisted = if (oldTheme == null) themeFile(decoded).takeIf { it.isFile }?.let { file ->
                        runCatching { Json.decodeFromString(CustomThemeSerializer, file.readText()) }.onFailure {
                            if (it is CustomThemeSerializer.UnknownVersion) errorRuntime(R.string.exception_theme_newer_kept)
                        }.getOrNull()
                    } else null
                    val files = foundFiles(decoded.look, extracted)
                    // another theme's file is a clash; one no theme names (left by a backup from before 3.0 put
                    // back over a 3.0 theme's json, say) is taken over, else its name could never be imported
                    val others = othersFiles(decoded.name, themes)
                    files.keys.firstOrNull { it in others }?.let { errorRuntime(R.string.exception_theme_file_clash, it) }
                    val oldFiles = (oldTheme ?: unlisted)?.look?.files().orEmpty().map { File(it).name }.toSet()
                    val newTheme = decoded.copy(look = decoded.look.mapFiles { File(dir, File(it).name).path })
                    place(files, oldFiles, newTheme)
                    // only once the json names the new files: one not written leaves the theme it replaces whole.
                    // Never another theme's (a json not listed may name any), nor a json or SAVING (importedFile's rule)
                    (oldFiles - files.keys - others).mapNotNull(::importedFile).forEach { it.delete() }
                    Imported(newCreated, newTheme, migrated)
                }
            }
        }

    // every file of [look] found among [extracted] (by name in dir) and checked before any is copied:
    // one refused would leave the others in dir
    private fun foundFiles(look: ThemeLook, extracted: List<File>): Map<String, File> {
        val files = look.files().associate { name ->
            val target = importedFile(name) ?: errorRuntime(R.string.exception_theme_file, name)
            target.name to (extracted.find { it.isFile && it.name == target.name } ?: errorRuntime(R.string.exception_theme_file, name))
        }
        check(look, files)
        return files
    }

    /**
     * The files of every theme but [name], by name: the listed ones', and of each json not listed (a later
     * build's, a broken one) every string that is the name of a file here, read loosely: its files are
     * not known, and taken over they would be lost to it when it is read again.
     */
    private fun othersFiles(name: String, themes: List<Theme>): Set<String> {
        val listed = themes.filterIsInstance<Theme.Custom>()
        val here = dir.list()?.toSet().orEmpty()
        val unlisted = dir.listFiles(FileFilter { it.extension == "json" }).orEmpty()
            .filter { f -> f.nameWithoutExtension != name && listed.none { f.nameWithoutExtension == it.name } }
            .flatMap { f -> runCatching { strings(Json.parseToJsonElement(f.readText())) }.getOrDefault(emptyList()) }
            .map { File(it).name }.filter { it in here }
        return listed.filter { it.name != name }.flatMap { it.look.files() }.map { File(it).name }.toSet() + unlisted
    }

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.values.flatMap(::strings)
        is JsonArray -> e.flatMap(::strings)
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
    }

    /**
     * [files] (by name in dir) and [theme]'s json copied whole beside the themes, then the files renamed
     * into place, the json last: if any of it fails, none of what was not in dir as [oldFiles] is left (a
     * copy cut short would be an orphan whose name no later import could take). All written before any is
     * renamed, only a rename in one directory can fail between the files and the json; one of [oldFiles]
     * renamed over by then keeps the new bytes, drawn with them by the theme it was of, its json intact.
     */
    private fun place(files: Map<String, File>, oldFiles: Set<String>, theme: Theme.Custom) {
        val staged = mutableMapOf<String, File>()
        val placed = mutableListOf<File>()
        var json: File? = null
        var done = false
        try {
            files.forEach { (name, from) -> staged[name] = stage { out -> from.inputStream().use { it.copyTo(out) } } }
            json = stageJson(theme)
            staged.forEach { (name, temp) -> File(dir, name).also { temp.renameOver(it); placed += it } }
            json.renameOver(themeFile(theme))
            done = true
        } finally {
            if (!done) {
                staged.values.forEach { it.delete() }
                json?.delete()
                placed.filter { it.name !in oldFiles }.forEach { it.delete() }
            }
        }
        syncDir()
    }

    // the pictures' cuts, key images and fonts: read before taken, each as what it is named for
    private fun check(look: ThemeLook, files: Map<String, File>) {
        fun of(name: String) = files.getValue(File(name).name)
        fun size(name: String) = probe.imageSize(of(name)) ?: errorRuntime(R.string.exception_theme_image, File(name).name)
        look.picture?.let { p ->
            listOfNotNull(p.portrait.file, p.landscape?.file).forEach { name ->
                val (w, h) = size(name)
                ThemeAssetLimits.picture(w, h)?.let(::refused)
            }
        }
        val slices = look.keys.kinds().flatMap { listOf(it.fill, it.pressed) } +
            look.keys.overrides.values.flatMap { listOfNotNull(it.fill, it.pressed) }
        slices.mapNotNull { (it as? KeyFill.Image)?.slice ?: (it as? KeyPressed.Image)?.slice }
            .map { it.file }.distinct().forEach { name ->
                val (w, h) = size(name)
                ThemeAssetLimits.keyImage(w, h, of(name).length())?.let(::refused)
            }
        // a font's size is a file's limit, checked as the zip was unpacked
        listOfNotNull(look.text.keyFont, look.text.candidateFont).distinct().forEach { name ->
            if (!probe.fontReads(of(name))) errorRuntime(R.string.exception_theme_font, File(name).name)
        }
    }

    // the limit in the message is the one checked, not a number written into the strings
    private fun refused(breach: ThemeAssetLimits.Breach): Nothing {
        val (message, limit) = when (breach) {
            ThemeAssetLimits.Breach.Entries -> R.string.exception_theme_entries to ThemeAssetLimits.MAX_ENTRIES.toLong()
            ThemeAssetLimits.Breach.FileSize -> R.string.exception_theme_file_size to ThemeAssetLimits.MAX_FILE_BYTES / MB
            ThemeAssetLimits.Breach.TotalSize -> R.string.exception_theme_total_size to ThemeAssetLimits.MAX_TOTAL_BYTES / MB
            ThemeAssetLimits.Breach.PictureSide -> R.string.exception_theme_picture_side to ThemeAssetLimits.MAX_PICTURE_SIDE.toLong()
            ThemeAssetLimits.Breach.KeyImageSide -> R.string.exception_theme_key_image_side to ThemeAssetLimits.MAX_KEY_IMAGE_SIDE.toLong()
            ThemeAssetLimits.Breach.KeyImageSize -> R.string.exception_theme_key_image_size to ThemeAssetLimits.MAX_KEY_IMAGE_BYTES / MB
            ThemeAssetLimits.Breach.Overrides -> R.string.exception_theme_overrides to ThemeAssetLimits.MAX_OVERRIDES.toLong()
        }
        errorRuntime(message, limit.toString())
    }

    private const val MB = 1024L * 1024

}

// a json names its files by file name; an older one by absolute path, of the old package id's dir in a backup
// from before the renaming to 幽默输入法, while the files came along into this one; null if one is missing
internal fun Theme.Custom.foundIn(dir: File, isThemeAsset: (File) -> Boolean) =
    copy(look = look.mapFiles { File(dir, File(it).name).path })
        .takeIf { it.look.files().all { f -> isThemeAsset(File(f)) } }
