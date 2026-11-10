package org.fcitx.fcitx5.android.data.theme

import android.graphics.BitmapFactory
import android.graphics.fonts.Font
import android.icu.text.ListFormatter
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.LegacyLook
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.SoundFile
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
import java.nio.file.Files
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
    // and never a json's, which listThemes would load as one more theme, nor SAVING's or the packs' folder's
    private fun importedFile(path: String) =
        File(path).name.takeIf { isPlainFileName(it) && !it.endsWith(".json", ignoreCase = true) && it != SAVING && it != ThemePackFiles.FOLDER }
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
        stageJson(theme, modified).let { synchronized(writes) { it.renameOver(themeFile(theme)) } }
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
        packsSwept = false
    }

    // a planted json (a crafted import, another app on shared storage) would aim delete and export at any file
    private fun File.isThemeAsset() = isFile && try {
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

    /** Whether its json is gone: one a delete failed on is listed again, its files kept for it. */
    fun deleteThemeFiles(theme: Theme.Custom): Boolean {
        if (!writable) notSettled()
        return deleteCustom(theme, ThemeManager.getAllThemes())
    }

    // [theme]'s json and its files, not a file another of [themes] names too, as an import leaves another's be
    private fun deleteCustom(theme: Theme.Custom, themes: List<Theme>): Boolean {
        val json = themeFile(theme)
        if (!json.delete() && json.exists()) return false
        val others = othersFiles(theme.name, themes)
        theme.look.files().map(::File).filter { it.isThemeAsset() && it.name !in others }.forEach { it.delete() }
        return true
    }

    // what a kill left of a pack being written is cleared at the process's first listing of them
    private var packsSwept = false

    /** The user's theme 4.0 packs, each version one ([ThemePackFiles.list]; none named as one of [builtins] is). */
    fun listPacks(builtins: List<Theme.Builtin> = ThemeManager.BuiltinThemes): List<Theme.Pack> {
        // Q1: not while an import writes in SAVING (the first sweep can come after a start not settled, A4,
        // from the listing an import's packPlaced asks for): a later listing sweeps once none does
        synchronized(writes) {
            // swept under the lock, as listThemes does: no import begins writing meanwhile; the packs read after
            if (writable && !packsSwept && writing == 0) {
                packsSwept = true
                ThemePackFiles.sweepPacks(dir)
            }
        }
        return ThemePackFiles.list(dir, builtins)
    }

    // the imports writing in SAVING now (off the main thread); the sweeps wait till none is
    private val writes = Any()
    private var writing = 0

    private inline fun <T> writingInSaving(block: () -> T): T {
        synchronized(writes) { writing++ }
        try {
            return block()
        } finally {
            synchronized(writes) { writing-- }
        }
    }

    /** Deletes the pack [file], every version of it. */
    fun deletePack(file: String) {
        if (!writable) notSettled()
        ThemePackFiles.delete(dir, file)
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
        synchronized(writes) {
            if (writable && !swept && writing == 0) {
                swept = true
                val saving = File(dir, SAVING)
                // a plain file there would refuse every save till it went
                if (saving.isDirectory) saving.listFiles()?.forEach { it.delete() } else saving.delete()
            }
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
                // save, delete, the rewrite below and moveClashing write `<name>.json`: one named otherwise
                // (put there by hand: `foo.json` holding `monokai`) is moved to it, or not listed where that
                // is another's json (a second theme of the name) or the move failed. Read as it is while
                // nothing may be written: nothing writes it then
                if (writable && it.nameWithoutExtension != theme.name && !movedTo(it, theme, File(dir, "${theme.name}.json"))) {
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
                        writeWhole(found, themeFile(found).lastModified())
                    } catch (e: IOException) {
                        // this runs as the app starts: drawn as migrated all the same, saved at the next start
                        Timber.w(e, "Theme ${found.name} not saved again as 3.0")
                        rewriteFailed = true
                    }
                }
                return@decode found
            }.toMutableList()
    }

    // [file] (a json of [theme] named otherwise) moved to [named], never over one there: checked and renamed under
    // the lock an import's json is put in place under (no hard link: Android's sepolicy denies apps `link`), its age
    // kept. One that reads as the same theme as [named] is deleted, else it would bring the theme back once that is
    // deleted; one of another theme of the name is left, not listed. Whether it moved
    private fun movedTo(file: File, theme: Theme.Custom, named: File): Boolean = synchronized(writes) {
        if (named.exists()) {
            val same = runCatching { Json.decodeFromString(CustomThemeSerializer, named.readText()) == theme }.getOrDefault(false)
            if (same) {
                file.delete()
                Timber.w("Theme file ${file.absolutePath} deleted: a copy of ${named.name}")
            } else {
                Timber.w("Theme file ${file.absolutePath} not listed: ${named.name} is another's")
            }
            return false
        }
        try {
            Files.move(file.toPath(), named.toPath())
        } catch (e: IOException) {
            EngineNotices.failedOnce(R.string.user_data_unreadable)
            Timber.w(e, "Theme file ${file.absolutePath} not listed: not moved to ${named.name}")
            return false
        }
        return true
    }

    /**
     * Of [names], the ones a theme of the user's may be named though none of that name is listed: its
     * json is there (one that did not read: a later build's, one cut short), or the directory cannot be
     * read (the themes not settled, A4, or before the first unlock; a listing that failed): all then. A
     * setting naming one is not moved to a built-in theme for good (ThemePrefs.migrateThemeRefs). The
     * directory read once, as this runs on the main thread as the app starts.
     */
    fun mayHoldThemes(names: Collection<String>): List<String> {
        val there = if (writable) dir.list()?.toHashSet() else null
        return if (there == null) names.toList() else names.filter { "$it.json" in there }
    }

    /**
     * 060-render-p3 T3: the user's [themes] whose names are now a built-in theme's reference ([clashes]:
     * `monokai`, `pixel#dark`, which 0.5's import let through as no built-in theme had them) saved again
     * under a UUID, their files as they are and their json's age kept (the list's order), as the theme
     * of that name would hide the built-in one ([ThemeManager.getTheme]: the user's first). [repoint]
     * (every setting naming the old name named the new one; false if that was not written) runs between
     * the new json written and the old one deleted, so a kill anywhere leaves each setting naming a json
     * there is, and the next start does what was left. The UUID is the old name's ([UUID.nameUUIDFromBytes]),
     * else, where a json of that name is another theme's (an old backup brought back after the copy was
     * changed), the first of `<name>#1`, `<name>#2`… whose json is not there or is this theme's copy: so
     * it is redone onto the same file, no second copy. Each is `<name>.json` ([listThemes] moves one named
     * otherwise); one that is not is left. Nothing while the themes are not settled (before the first
     * unlock too): none is listed then, and nothing may be written. One not saved or not repointed stays
     * as it was, moved at the next start. The list, each moved one in its place.
     */
    fun moveClashing(
        themes: List<Theme.Custom>,
        clashes: (String) -> Boolean,
        repoint: (from: String, to: String) -> Boolean,
    ): List<Theme.Custom> {
        if (!writable || themes.none { clashes(it.name) }) return themes
        val moved = themes.map { theme ->
            val source = themeFile(theme)
            if (!clashes(theme.name) || !source.exists()) return@map theme
            val copy = generateSequence(0) { it + 1 }
                .map { i -> if (i == 0) theme.name else "${theme.name}#$i" }
                .map { theme.copy(name = UUID.nameUUIDFromBytes(it.encodeToByteArray()).toString()) }
                // a json of that name not this theme's copy (one that reads otherwise, or not at all)
                .first { copy -> !themeFile(copy).exists() || copy in themes }
            try {
                writeWhole(copy, source.lastModified())
            } catch (e: IOException) {
                Timber.w(e, "Theme ${theme.name} not saved under a new name")
                return@map theme
            }
            if (!repoint(theme.name, copy.name)) {
                Timber.w("Settings naming theme ${theme.name} not written: kept till the next start")
                return@map theme
            }
            // its json only: its files are the copy's
            source.delete()
            copy
        }
        syncDir()
        // a copy a start before left (killed before the old json went) is listed already
        return moved.distinctBy { it.name }
    }

    /**
     * [theme] as a theme 4.0 zip into [dest], closed when done (060-render-p3 Q5: an export is always 4.0): a
     * pack as it is; a 3.0 theme as the engine draws it ([ThemePackFiles.of]), its files copied by the names its
     * `theme.xml` gives them; Monet's as it is now, its colours fixed ([Theme.Builtin.toCustom]).
     */
    fun exportTheme(theme: Theme, dest: OutputStream): Result<Unit> = runCatching {
        // closed also where a read fails before the zip is opened (the caller's document left open else)
        dest.use {
            when (theme) {
                is Theme.Pack -> ThemePackFiles.export(theme, it)
                is Theme.Custom -> exportCustom(theme, it)
                is Theme.Builtin -> exportCustom(theme.toCustom(), it)
            }
        }
    }

    /**
     * A 3.0 theme's [name] as its exported pack's, one a pack's import takes ([ThemePackFiles.badName]): no `/`, not
     * `.`-led, not a version's (`x#dark`), cut to a theme 4's length; `theme` where nothing is left.
     */
    @VisibleForTesting
    internal fun packName(name: String): String {
        fun unversioned(n: String): String {
            var v = n
            while (v.endsWith("#light") || v.endsWith("#dark")) v = v.substringBeforeLast('#')
            return v
        }
        val n = unversioned(LookTheme.themeName(unversioned(name.replace('/', '_').trimStart('.'))).trimStart('.'))
        return n.takeUnless { it.isBlank() || ThemePackFiles.badName(it) } ?: "theme"
    }

    private fun exportCustom(theme: Theme.Custom, dest: OutputStream) {
        ownFiles(theme, dest)
        val (xml, names) = ThemePackFiles.of(exportedLook(theme.look), packName(theme.name), theme.isDark)
        ZipOutputStream(dest.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML))
            zip.write(xml.encodeToByteArray())
            names.forEach { (path, name) ->
                zip.putNextEntry(ZipEntry(name))
                File(path).inputStream().use { it.copyTo(zip) }
            }
            zip.closeEntry()
        }
    }

    // a 3.0 theme's source photo is not limited (only its cuts are drawn), a pack's is as its cuts: one past that
    // exported as its portrait cut (the source is for cutting again, which the app does not), so the pack imports
    private fun exportedLook(look: ThemeLook): ThemeLook {
        val picture = look.picture ?: return look
        val fits = probe.imageSize(File(picture.src))?.let { (w, h) -> ThemeAssetLimits.picture(w, h) == null } == true
        return if (fits) look else look.copy(picture = picture.copy(src = picture.portrait.file))
    }

    // a planted json would aim an export at any file of the app's: only the theme dir's go, checked before any is written
    private fun ownFiles(theme: Theme.Custom, dest: OutputStream) {
        theme.look.files().map(::File).firstOrNull { !it.isThemeAsset() }?.let {
            dest.close()
            errorRuntime(R.string.exception_theme_file, it.name)
        }
    }

    /**
     * [theme] as a theme 3.0 zip (its files, then its json), as the app exported before 0.6.0 and the theme
     * maker's page still makes and reads: the zips the page's tests read are written by it (ThemeMakerRulesTest).
     */
    @VisibleForTesting
    internal fun exportJson(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            ownFiles(theme, dest)
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
                    importJson(extracted, themes)
                }
            }
        }

    /**
     * What a theme zip held ([openTheme]): a 3.0 theme, imported as [importTheme] does; or a 4.0 pack, checked
     * whole and not yet taken, for the user to see ([ThemePackFiles.Staged]) and take ([Pack.commit]) or not
     * ([Pack.discard]).
     */
    sealed interface Opened {
        data class Json(val imported: Imported) : Opened

        class Pack internal constructor(
            val staged: ThemePackFiles.Staged,
            private val packThere: Boolean,
            /** The user's 3.0 themes named as it or one of its versions: replaced by it, their json deleted once it is listed. */
            internal val customs: List<Theme.Custom>,
            private val unpacked: File,
        ) : Opened {
            /** A pack of its name, or a 3.0 theme named as it is, is there: taking it replaces that one. */
            val replaces: Boolean get() = packThere || customs.isNotEmpty()

            /** Put in place (off the main thread), its versions; what was unpacked gone either way. */
            fun commit(): Result<List<Theme.Pack>> = runCatching {
                try {
                    if (!writable) notSettled()
                    // the 3.0 themes it replaces ([customs]) are, once the pack is listed (ThemeManager.packPlaced,
                    // or the next start's listing after a kill: ThemeManager.finishReplacements), on the main thread;
                    // recorded first, so that one finishes only what an import began
                    val names = recordAdding(customs.map { it.name }.toSet())
                    val placed = runCatching { writingInSaving { ThemePackFiles.place(dir, staged) } }
                    // not placed: nothing replaced, the record as it was (one not written: dropped at the next start),
                    // the place's failure the one told
                    if (placed.isFailure) runCatching { replaced(names) }.onFailure { placed.exceptionOrNull()!!.addSuppressed(it) }
                    placed.getOrThrow()
                } finally {
                    discard()
                }
            }

            fun discard() {
                unpacked.deleteRecursively()
            }
        }
    }

    /** Where the names of the 3.0 themes a pack's import replaces are kept till done: this phone's, not backed up. */
    @VisibleForTesting
    internal var replacingFile: () -> File = { File(appContext.noBackupFilesDir, "theme-pack-replaces") }

    /** The 3.0 themes a pack's import replaces, recorded before it was placed and not finished yet (ThemeManager). */
    @Synchronized
    // @throws IOException if it is there but does not read: not taken as empty, which a write would then make so
    fun replacing(): Set<String> = replacingFile().let { if (it.exists()) it.readLines().filter { l -> l.isNotEmpty() }.toSet() else emptySet() }

    /** [names] finished (or nothing to finish): their record gone. */
    @Synchronized
    fun replaced(names: Set<String>) {
        if (names.isEmpty()) return
        val left = replacing() - names
        if (left.isEmpty()) replacingFile().delete() else record(left)
    }

    // [names] added to the record, read and written as one (finishReplacements writes it on the main thread):
    // the ones not in it already
    @Synchronized
    private fun recordAdding(names: Set<String>): Set<String> {
        if (names.isEmpty()) return names
        // one that does not read set aside (kept, logged): else no later import that replaces a theme could take
        val before = try {
            replacing()
        } catch (e: IOException) {
            val file = replacingFile()
            val aside = file.renameTo(File(file.parentFile, file.name + ".bad"))
            Timber.w(e, "Record of the themes replaced not read: set aside $aside")
            if (!aside) throw e
            emptySet()
        }
        return (names - before).also { if (it.isNotEmpty()) record(before + it) }
    }

    // written whole aside, then renamed over the record: a kill leaves the old record or the new, not half of one
    private fun record(names: Set<String>) {
        val file = replacingFile()
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { it.write(names.joinToString("\n", postfix = "\n").encodeToByteArray()); it.fd.sync() }
        if (!temp.renameTo(file)) throw IOException("cannot record the themes replaced")
    }

    // where a pack is unpacked till the user takes it or not: the cache's, what a kill left cleared at the first open
    private val unpacking get() = File(appContext.cacheDir, "theme-import")

    @Volatile
    private var unpackingSwept = false
    private const val STALE_UNPACKED_MS = 60 * 60 * 1000L

    /**
     * Opens the theme zip [src] ([themes] as [importTheme]'s): one with a `theme.xml` at its top is a theme 4.0
     * pack (060-render-p3 T8a), unpacked (its top and `layouts/`, counted and held to the limits as it is),
     * checked whole ([ThemePackFiles.check]) and kept unpacked for [Opened.Pack.commit]; any other a 3.0 theme
     * (or older), imported at once as before.
     */
    fun openTheme(src: InputStream, themes: List<Theme> = ThemeManager.getAllThemes()): Result<Opened> =
        runCatching {
            if (!writable) notSettled()
            // what a kill left cleared at the first open; later, what a dialog closed otherwise than by its buttons
            // left (an hour old: no dialog open so long), as the input method's process may live for days
            val stale = System.currentTimeMillis() - STALE_UNPACKED_MS
            unpacking.listFiles()?.forEach { if (!unpackingSwept || it.lastModified() < stale) it.deleteRecursively() }
            unpackingSwept = true
            val temp = File(unpacking, UUID.randomUUID().toString())
            if (!temp.mkdirs()) throw IOException("Cannot create '${temp.name}'")
            var kept = false
            try {
                val names = ArrayList<String>()
                val extracted = ZipInputStream(src).use { zipStream ->
                    zipStream.extract(temp, keep = ThemePackFiles::unpacked, names = names) { count, size, total ->
                        ThemeAssetLimits.entry(count, size, total)?.let(::refused)
                    }
                }
                // a folder zipped whole: its theme.xml in it, said so (a 3.0 theme's json read so is not a pack's)
                if (ThemePackFiles.themeXmlInFolder(names)) errorRuntime(R.string.exception_theme_xml_top)
                if (!File(temp, ThemePackFiles.THEME_XML).isFile) return@runCatching Opened.Json(importJson(extracted, themes))
                val staged = ThemePackFiles.check(temp, names, packChecks)
                packClash(staged, themes)
                val packs = themes.filterIsInstance<Theme.Pack>()
                val folder = File(ThemePackFiles.folder(dir), staged.name)
                val there = folder.exists()
                // one there not listed of a later build is kept, as a 3.0 theme's json is (importJson); any other
                // not listed (one that no longer reads, misses a file, past the packs listed) is replaced, the
                // natural repair: not listed, it could not be deleted
                if (there && packs.none { it.file == staged.name } && ThemePackFiles.laterBuilds(folder, BuildConfig.VERSION_NAME)) {
                    errorRuntime(R.string.exception_theme_newer_kept)
                }
                if (!there && packs.distinctBy { it.file }.size >= ThemeAssetLimits.MAX_PACKS) {
                    errorRuntime(R.string.exception_theme_packs, ThemeAssetLimits.MAX_PACKS.toString())
                }
                val refs = ThemePackFiles.refs(staged.name, staged.theme.variants) + staged.name
                val customs = themes.filterIsInstance<Theme.Custom>().filter { it.name in refs }
                Opened.Pack(staged, there, customs, temp).also { kept = true }
            } finally {
                if (!kept) temp.deleteRecursively()
            }
        }

    private val packChecks
        get() = ThemePackFiles.Checks(
            probe, ::refused, BuildConfig.VERSION_NAME,
            lowContrast = { low ->
                if (low.isEmpty()) {
                    null
                } else {
                    val names = low.map { appContext.getString(if (it) R.string.theme_pack_dark else R.string.theme_pack_light) }
                    // joined as the app's language joins a list (`light and dark`, `浅色和深色`)
                    val versions = ListFormatter.getInstance(appContext.resources.configuration.locales[0]).format(names)
                    appContext.getString(R.string.theme_pack_low_contrast, versions)
                }
            },
            animatedStill = {
                val l = ThemeAssetLimits
                appContext.getString(
                    R.string.theme_pack_animated_still, (l.MAX_ANIMATED_BYTES / MB).toString(), l.MAX_ANIMATED_SIDE.toString(),
                    l.MAX_ANIMATED_FRAMES.toString(), (MS_A_SECOND / l.MIN_ANIMATED_FRAME_MS).toString(),
                )
            },
        )

    /**
     * A pack named as a built-in theme is ([ThemePackFiles.builtinClash]: the settings name one theme by it). One
     * named as a 3.0 theme of the user's replaces it ([Opened.Pack.customs]).
     */
    private fun packClash(staged: ThemePackFiles.Staged, themes: List<Theme>) {
        val builtins = themes.filterIsInstance<Theme.Builtin>().ifEmpty { ThemeManager.BuiltinThemes }
        if (ThemePackFiles.builtinClash(staged.name, staged.theme.variants, builtins)) errorRuntime(R.string.exception_theme_name_clash)
    }

    // the theme 3.0 json among [extracted] (an older one's too) imported with its files ([importTheme])
    private fun importJson(extracted: List<File>, themes: List<Theme>): Imported {
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
        // a built-in theme's name, or its 0.5 name, which the settings move to it (ThemePrefs.migrateThemeRefs);
        // a pack's or one of its versions' (the settings name one theme by it)
        val packs = themes.filterIsInstance<Theme.Pack>()
        val builtin = BuiltinThemeFiles.find(ThemeManager.BuiltinThemes, decoded.name) != null || decoded.name in BuiltinThemeFiles.LEGACY
        val pack = BuiltinThemeFiles.find(packs, decoded.name) != null || packs.any { it.file == decoded.name }
        if (builtin || pack) errorRuntime(R.string.exception_theme_name_clash)
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
        writingInSaving { place(files, oldFiles, newTheme) }
        // only once the json names the new files: one not written leaves the theme it replaces whole.
        // Never another theme's (a json not listed may name any), nor a json or SAVING (importedFile's rule)
        (oldFiles - files.keys - others).mapNotNull(::importedFile).forEach { it.delete() }
        return Imported(newCreated, newTheme, migrated)
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
            // under the lock listThemes moves a json named otherwise under: neither renames over the other
            synchronized(writes) { json.renameOver(themeFile(theme)) }
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
            ThemeAssetLimits.Breach.ThemeXml -> R.string.exception_theme_xml_size to ThemeAssetLimits.MAX_THEME_XML_BYTES / KB
            ThemeAssetLimits.Breach.Layouts -> R.string.exception_theme_layouts to ThemeAssetLimits.MAX_LAYOUTS.toLong()
            ThemeAssetLimits.Breach.LayoutSize -> R.string.exception_theme_layout_size to ThemeAssetLimits.MAX_LAYOUT_BYTES / KB
            ThemeAssetLimits.Breach.PreviewSize -> R.string.exception_theme_preview_size to ThemeAssetLimits.MAX_PREVIEW_BYTES / KB
            ThemeAssetLimits.Breach.PreviewSide -> R.string.exception_theme_preview_side to ThemeAssetLimits.MAX_PREVIEW_SIDE.toLong()
            ThemeAssetLimits.Breach.IconSize -> R.string.exception_theme_icon_size to ThemeAssetLimits.MAX_ICON_BYTES / KB
            ThemeAssetLimits.Breach.EffectImageSide -> R.string.exception_theme_effect_image_side to ThemeAssetLimits.MAX_EFFECT_IMAGE_SIDE.toLong()
            ThemeAssetLimits.Breach.EffectImageSize -> R.string.exception_theme_effect_image_size to ThemeAssetLimits.MAX_EFFECT_IMAGE_BYTES / KB
            ThemeAssetLimits.Breach.EffectImageType -> R.string.exception_theme_effect_image_type to 0L
            ThemeAssetLimits.Breach.SoundSize -> R.string.exception_theme_sound_size to SoundFile.MAX_BYTES / KB
        }
        errorRuntime(message, limit.toString())
    }

    private const val MB = 1024L * 1024
    private const val KB = 1024L
    private const val MS_A_SECOND = 1000

}

// a json names its files by file name; an older one by absolute path, of the old package id's dir in a backup
// from before the renaming to 幽默输入法, while the files came along into this one; null if one is missing
internal fun Theme.Custom.foundIn(dir: File, isThemeAsset: (File) -> Boolean) =
    copy(look = look.mapFiles { File(dir, File(it).name).path })
        .takeIf { it.look.files().all { f -> isThemeAsset(File(f)) } }
