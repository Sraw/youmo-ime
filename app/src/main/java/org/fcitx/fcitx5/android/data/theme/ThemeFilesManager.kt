package org.fcitx.fcitx5.android.data.theme

import android.graphics.BitmapFactory
import android.graphics.fonts.Font
import android.icu.text.ListFormatter
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
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
import org.fcitx.fcitx5.android.input.theme.SkinZip
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.LegacyLook
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.SkinConversion
import org.fcitx.fcitx5.android.scene.skin.SkinLayoutWriter
import org.fcitx.fcitx5.android.scene.skin.SkinNote
import org.fcitx.fcitx5.android.scene.skin.SkinOutput
import org.fcitx.fcitx5.android.scene.skin.SkinPlatform
import org.fcitx.fcitx5.android.scene.theme.SoundFile
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.isPlainFileName
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

    // Settled on the main thread at Application.onCreate (first ask, so no race); later the active theme would miss the first draw.
    private val themes get() = HostConfigDir.inUse(ConfigHome.THEME)
    internal val dir get() = themes.let { it.dir.also { dir -> if (it.writable) dir.mkdirs() } }

    val writable get() = themes.writable

    private fun notSettled(): Nothing = throw IOException(appContext.getString(R.string.user_data_not_settled))

    private fun themeFile(theme: Theme.Custom) = File(dir, theme.name + ".json")

    // names in an imported json are untrusted: file name only, and never a json, SAVING or the packs' folder
    private fun importedFile(path: String) =
        File(path).name.takeIf { isPlainFileName(it) && !it.endsWith(".json", ignoreCase = true) && it != SAVING && it != ThemePackFiles.FOLDER }
            ?.let { File(dir, it) }

    // a directory, so no import's plain file name can be a file being written and no listing reads one as a theme
    private const val SAVING = ".saving"

    private var swept = false

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

    private fun stageJson(theme: Theme.Custom, modified: Long? = null) =
        stage { it.write(Json.encodeToString(CustomThemeSerializer, theme.withFileNames()).encodeToByteArray()) }
            .also { temp -> modified?.let(temp::setLastModified) }

    // whole or not at all: a cut-short writeText (full disk, power cut) left an empty file and the theme lost
    private fun writeWhole(theme: Theme.Custom, modified: Long? = null) {
        stageJson(theme, modified).let { synchronized(writes) { it.renameOver(themeFile(theme)) } }
        syncDir()
    }

    // the renames too, or a power cut right after could undo one; Linux syncs a directory opened for reading, others may refuse
    private fun syncDir() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Timber.d(e, "Theme dir not synced")
        }
    }

    @VisibleForTesting
    internal fun forgetSwept() {
        swept = false
        packsSwept = false
    }

    // a planted json (crafted import, another app on shared storage) would aim delete and export at any file
    private fun File.isThemeAsset() = isFile && try {
        canonicalFile.parentFile == dir.canonicalFile
    } catch (_: IOException) {
        false
    }

    internal interface AssetProbe {
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

    @VisibleForTesting
    internal val themeDir get() = dir

    private fun Theme.Custom.withFileNames() = copy(look = look.mapFiles { File(it).name })

    fun saveThemeFiles(theme: Theme.Custom) {
        if (!writable) notSettled()
        writeWhole(theme)
    }

    /** One a delete failed on is listed again, its files kept for it. */
    fun deleteThemeFiles(theme: Theme.Custom): Boolean {
        if (!writable) notSettled()
        return deleteCustom(theme, ThemeManager.getAllThemes())
    }

    private fun deleteCustom(theme: Theme.Custom, themes: List<Theme>): Boolean {
        val json = themeFile(theme)
        if (!json.delete() && json.exists()) return false
        val others = othersFiles(theme.name, themes)
        theme.look.files().map(::File).filter { it.isThemeAsset() && it.name !in others }.forEach { it.delete() }
        return true
    }

    private var packsSwept = false

    fun listPacks(builtins: List<Theme.Builtin> = ThemeManager.BuiltinThemes): List<Theme.Pack> {
        // not while an import writes in SAVING: the first sweep can come from an import's packPlaced listing
        sweepPacksOnce()
        return ThemePackFiles.list(dir, builtins)
    }

    internal fun sweepPacksOnce() {
        synchronized(writes) {
            if (writable && !packsSwept && writing == 0) {
                packsSwept = true
                ThemePackFiles.sweepPacks(dir)
            }
        }
    }

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

    fun deletePack(file: String) {
        if (!writable) notSettled()
        ThemePackFiles.delete(dir, file)
    }

    /** True while an older theme could not be resaved as 3.0: the old key-style settings are kept till it is. */
    @Volatile
    var rewriteFailed = false
        private set

    /** Newest first; an older theme is resaved as 3.0 with [legacy] keys, a 3.0 theme never changed by them. */
    fun listThemes(legacy: LegacyLook? = null): MutableList<Theme.Custom> {
        rewriteFailed = false
        synchronized(writes) {
            if (writable && !swept && writing == 0) {
                swept = true
                val saving = File(dir, SAVING)
                if (saving.isDirectory) saving.listFiles()?.forEach { it.delete() } else saving.delete()
            }
        }
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull decode@{
                val (theme, migrated) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, it.readText())
                }.getOrElse { e ->
                    // one that did not read (a later build's, cut short) is left as it is: nothing writes a theme not listed
                    EngineNotices.failedOnce(R.string.user_data_unreadable)
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }
                if (!isPlainFileName(theme.name)) {
                    Timber.w("Theme file ${it.absolutePath} has a name that is a path: ${theme.name}")
                    return@decode null
                }
                // save, delete and moveClashing write `<name>.json`: one named otherwise is moved there, or unlisted
                if (writable && it.nameWithoutExtension != theme.name && !movedTo(it, theme, File(dir, "${theme.name}.json"))) {
                    return@decode null
                }
                val here = theme.foundIn(dir) { f -> f.isThemeAsset() } ?: run {
                    // a picture or font gone (partial restore, deleted by hand): not listed; the user is told reimporting brings it back
                    EngineNotices.failedOnce(R.string.theme_file_missing)
                    Timber.w("Cannot find a file of theme ${theme.name}")
                    return@decode null
                }
                val found = if (migrated && legacy != null && !legacy.isDefault) {
                    here.copy(look = legacy.applyTo(here.look))
                } else {
                    here
                }
                if (migrated && writable) {
                    try {
                        writeWhole(found, themeFile(found).lastModified())
                    } catch (e: IOException) {
                        // runs at app start: drawn as migrated anyway, saved at the next start
                        Timber.w(e, "Theme ${found.name} not saved again as 3.0")
                        rewriteFailed = true
                    }
                }
                return@decode found
            }.toMutableList()
    }

    // never over an existing file (no hard link: Android's sepolicy denies `link`); a same-theme copy at [named] is deleted
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

    /** A json there that did not read, or an unreadable directory (before first unlock): all [names] then. */
    fun mayHoldThemes(names: Collection<String>): List<String> {
        val there = if (writable) dir.list()?.toHashSet() else null
        return if (there == null) names.toList() else names.filter { "$it.json" in there }
    }

    /**
     * Resaves user themes named as a built-in under a UUID ([ThemeManager.getTheme] would hide the built-in). [repoint]
     * runs between the new json written and the old deleted, so a kill leaves every setting valid and a redo reuses it.
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
            source.delete()
            copy
        }
        syncDir()
        // a copy a previous start left is listed already
        return moved.distinctBy { it.name }
    }

    /** Always a 4.0 zip; Monet's with its current colours fixed. [dest] is closed when done. */
    fun exportTheme(theme: Theme, dest: OutputStream): Result<Unit> = runCatching {
        // closed also where a read fails before the zip opens
        dest.use {
            when (theme) {
                is Theme.Pack -> ThemePackFiles.export(theme, it)
                is Theme.Custom -> exportCustom(theme, it)
                is Theme.Builtin -> exportCustom(theme.toCustom(), it)
            }
        }
    }

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

    // a 3.0 photo past a pack's cut limit is exported as its portrait cut
    private fun exportedLook(look: ThemeLook): ThemeLook {
        val picture = look.picture ?: return look
        val fits = probe.imageSize(File(picture.src))?.let { (w, h) -> ThemeAssetLimits.picture(w, h) == null } == true
        return if (fits) look else look.copy(picture = picture.copy(src = picture.portrait.file))
    }

    private fun ownFiles(theme: Theme.Custom, dest: OutputStream) {
        theme.look.files().map(::File).firstOrNull { !it.isThemeAsset() }?.let {
            dest.close()
            errorRuntime(R.string.exception_theme_file, it.name)
        }
    }

    data class Imported(val created: Boolean, val theme: Theme.Custom, val migrated: Boolean, val renamedFrom: String? = null)

    sealed interface Opened {
        data class Json(val imported: Imported) : Opened

        class Pack internal constructor(
            val staged: ThemePackFiles.Staged,
            private val packThere: Boolean,
            internal val customs: List<Theme.Custom>,
            private val unpacked: File,
            val notes: Map<SkinNote, List<String>> = emptyMap(),
            /** Converted from a skin: its layouts are taken only where [commit] is told to, with [skinThemeXmlWithLayouts]. */
            val fromSkin: Boolean = false,
            private val skinThemeXmlWithLayouts: String? = null,
            private val names: List<String> = emptyList(),
        ) : Opened {
            val replaces: Boolean get() = packThere || customs.isNotEmpty()

            /** Off the main thread; what was unpacked is removed either way. [withLayouts] false leaves a skin's layouts out. */
            fun commit(withLayouts: Boolean = true): Result<List<Theme.Pack>> = runCatching {
                try {
                    if (!writable) notSettled()
                    val placing = if (fromSkin && staged.layouts.isNotEmpty()) skinStaged(withLayouts) else staged
                    // recorded first; the replaced 3.0 themes are finished on the main thread once the pack is listed
                    val names = recordAdding(customs.map { it.name }.toSet())
                    val placed = runCatching { writingInSaving { ThemePackFiles.place(dir, placing) } }
                    if (placed.isFailure) runCatching { replaced(names) }.onFailure { placed.exceptionOrNull()!!.addSuppressed(it) }
                    placed.getOrThrow().also {
                        runCatching { ThemeSeparators.importedOverRestored(staged.name) }.onFailure { Timber.w(it, "Theme pack ${staged.name} left to be given 分词") }
                    }
                } finally {
                    discard()
                }
            }

            // the theme.xml whose min-app counts its layouts goes with them; checked again as it is placed
            private fun skinStaged(withLayouts: Boolean): ThemePackFiles.Staged {
                if (withLayouts) {
                    File(unpacked, ThemePackFiles.THEME_XML).writeBytes(skinThemeXmlWithLayouts!!.encodeToByteArray())
                } else {
                    File(unpacked, ThemePackFiles.LAYOUTS).deleteRecursively()
                }
                val kept = if (withLayouts) names else names.filter { !it.startsWith(ThemePackFiles.LAYOUTS + "/") }
                return ThemePackFiles.check(unpacked, kept, packChecks)
            }

            fun discard() {
                unpacked.deleteRecursively()
            }
        }
    }

    @VisibleForTesting
    internal var replacingFile: () -> File = { File(appContext.noBackupFilesDir, "theme-pack-replaces") }

    @Synchronized
    fun replacing(): Set<String> = replacingFile().let { if (it.exists()) it.readLines().filter { l -> l.isNotEmpty() }.toSet() else emptySet() }

    @Synchronized
    fun replaced(names: Set<String>) {
        if (names.isEmpty()) return
        val left = replacing() - names
        if (left.isEmpty()) replacingFile().delete() else record(left)
    }

    // finishReplacements writes it on the main thread
    @Synchronized
    private fun recordAdding(names: Set<String>): Set<String> {
        if (names.isEmpty()) return names
        // an unreadable one is set aside, else no later replacing import could proceed
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

    // a kill leaves the old record or the new, never half
    private fun record(names: Set<String>) {
        val file = replacingFile()
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { it.write(names.joinToString("\n", postfix = "\n").encodeToByteArray()); it.fd.sync() }
        if (!temp.renameTo(file)) throw IOException("cannot record the themes replaced")
    }

    // what a kill left is cleared at the first open
    private val unpacking get() = File(appContext.cacheDir, "theme-import")

    @Volatile
    private var unpackingSwept = false
    private const val STALE_UNPACKED_MS = 60 * 60 * 1000L

    /** A zip with a top-level `theme.xml` is a 4.0 pack kept for [Opened.Pack.commit]; else a 3.0 theme imported at once. */
    fun openTheme(src: InputStream, themes: List<Theme> = ThemeManager.getAllThemes()): Result<Opened> =
        runCatching {
            if (!writable) notSettled()
            unpacked { temp ->
                val names = ArrayList<String>()
                val extracted = ZipInputStream(src).use { zipStream ->
                    zipStream.extract(temp, keep = ThemePackFiles::unpacked, names = names) { count, size, total ->
                        ThemeAssetLimits.entry(count, size, total)?.let(::refused)
                    }
                }
                if (ThemePackFiles.themeXmlInFolder(names)) errorRuntime(R.string.exception_theme_xml_top)
                if (!File(temp, ThemePackFiles.THEME_XML).isFile) Opened.Json(importJson(extracted, themes)) else pack(temp, names, themes)
            }
        }

    /**
     * [open] on the IO threads, interrupted where the caller is cancelled (a conversion stops at its next image); a
     * pack it made by then, which no one will take or discard, is removed, and how it stopped is no one's to be told.
     */
    suspend fun <T : Opened> opening(open: () -> T): T {
        var made: T? = null
        try {
            return runInterruptible(Dispatchers.IO) { open().also { made = it } }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (e !is CancellationException && currentCoroutineContext().isActive) throw e
            (made as? Opened.Pack)?.discard()
            throw e as? CancellationException ?: CancellationException("import let go", e)
        }
    }

    /** Baidu `.bds`/`.bdi`, Sogou `.ssf`, converted in memory to a 4.0 pack. */
    fun openSkin(src: InputStream, fileName: String, themes: List<Theme> = ThemeManager.getAllThemes()): Result<Opened.Pack> =
        SkinImport.backstop(runCatching {
            if (!writable) notSettled()
            converted(SkinImport.convert(src, fileName, skinCodec), themes)
        })

    @VisibleForTesting
    internal fun converted(conversion: SkinConversion, themes: List<Theme>): Opened.Pack =
        unpacked { temp ->
            val names = ArrayList<String>()
            fun write(name: String, bytes: ByteArray) {
                check(isPlainFileName(name) && name !in names) { "skin file name $name" }
                File(temp, name).writeBytes(bytes)
                names += name
            }
            write(ThemePackFiles.THEME_XML, conversion.themeXml.encodeToByteArray())
            for (out in conversion.files) {
                when (out) {
                    is SkinOutput.Image -> write(out.name, SkinImport.png(out, skinCodec))
                    is SkinOutput.Bytes -> write(out.name, out.bytes)
                }
            }
            // not through write(): a layout's name is a path, of one of the ids only
            val layouts = conversion.layouts.filterKeys { it in SkinLayoutWriter.IDS }
            if (layouts.isNotEmpty()) File(temp, ThemePackFiles.LAYOUTS).mkdir()
            for ((id, xml) in layouts) {
                File(temp, "${ThemePackFiles.LAYOUTS}/$id.xml").writeBytes(xml.encodeToByteArray())
                names += "${ThemePackFiles.LAYOUTS}/$id.xml"
            }
            pack(temp, names, themes, conversion.notes, conversion.themeXmlWithLayouts.takeIf { layouts.isNotEmpty() })
        }

    internal interface SkinCodec : SkinPlatform {
        fun png(pixels: Pixels): ByteArray
    }

    @VisibleForTesting
    internal var skinCodec: SkinCodec = AndroidSkinCodec

    private fun <T : Opened> unpacked(use: (File) -> T): T {
        // also clears what a closed dialog left (an hour old): the IME process may live for days
        val stale = System.currentTimeMillis() - STALE_UNPACKED_MS
        unpacking.listFiles()?.forEach { if (!unpackingSwept || it.lastModified() < stale) it.deleteRecursively() }
        unpackingSwept = true
        val temp = File(unpacking, UUID.randomUUID().toString())
        if (!temp.mkdirs()) throw IOException("Cannot create '${temp.name}'")
        var kept = false
        try {
            return use(temp).also { kept = it is Opened.Pack }
        } finally {
            if (!kept) temp.deleteRecursively()
        }
    }

    private fun pack(
        temp: File,
        names: List<String>,
        themes: List<Theme>,
        notes: Map<SkinNote, List<String>>? = null,
        skinThemeXmlWithLayouts: String? = null,
    ): Opened.Pack {
        val staged = ThemePackFiles.check(temp, names, packChecks)
        packClash(staged, themes)
        val packs = themes.filterIsInstance<Theme.Pack>()
        val folder = File(ThemePackFiles.folder(dir), staged.name)
        val there = folder.exists()
        // a later build's unlisted pack is kept, as a 3.0 json is; any other unlisted one is replaced
        val laterBuilds = ThemePackFiles.laterBuilds(folder, ThemePackFiles.minAppVersion(versionName))
        if (there && packs.none { it.file == staged.name } && laterBuilds) {
            errorRuntime(R.string.exception_theme_newer_kept)
        }
        if (!there && packs.filter { !it.builtin }.distinctBy { it.file }.size >= ThemeAssetLimits.MAX_PACKS) {
            errorRuntime(R.string.exception_theme_packs, ThemeAssetLimits.MAX_PACKS.toString())
        }
        val refs = ThemePackFiles.refs(staged.name, staged.theme.variants) + staged.name
        val customs = themes.filterIsInstance<Theme.Custom>().filter { it.name in refs }
        return Opened.Pack(staged, there, customs, temp, notes.orEmpty(), fromSkin = notes != null, skinThemeXmlWithLayouts, names)
    }

    @VisibleForTesting
    internal var versionName: String = BuildConfig.VERSION_NAME

    private val packChecks
        get() = ThemePackFiles.Checks(
            probe, ::refused, ThemePackFiles.minAppVersion(versionName),
            lowContrast = { low ->
                if (low.isEmpty()) {
                    null
                } else {
                    val names = low.map { appContext.getString(if (it) R.string.theme_pack_dark else R.string.theme_pack_light) }
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

    /** A built-in name is refused (settings name one theme by it); a user 3.0 theme of the name is replaced. */
    private fun packClash(staged: ThemePackFiles.Staged, themes: List<Theme>) {
        val builtins = themes.filterIsInstance<Theme.Builtin>().ifEmpty { ThemeManager.BuiltinThemes }
        if (ThemePackFiles.builtinClash(staged.name, staged.theme.variants, builtins)) errorRuntime(R.string.exception_theme_name_clash)
        if (staged.name in ThemeManager.BuiltinPackNames) errorRuntime(R.string.exception_theme_name_clash)
    }

    private fun importJson(extracted: List<File>, themes: List<Theme>): Imported {
        // not a Mac's "._<name>" (attributes some zippers put at the top)
        val jsonFile = extracted.find { it.isFile && it.extension == "json" && !it.name.startsWith("._") }
            ?: errorRuntime(R.string.exception_theme_json)
        val (read, migrated) = Json.decodeFromString(
            CustomThemeSerializer.WithMigrationStatus,
            jsonFile.readText()
        )
        ThemeAssetLimits.look(read.look)?.let(::refused)
        if (!isPlainFileName(read.name)) errorRuntime(R.string.invalid_value)
        // a taken name gets `-1`, `-2`…: neither the app nor the user can rename a theme
        val packs = themes.filterIsInstance<Theme.Pack>()
        fun taken(name: String) = BuiltinThemeFiles.find(ThemeManager.BuiltinThemes, name) != null || BuiltinThemeFiles.moved(name) != null ||
            BuiltinThemeFiles.find(packs, name) != null || packs.any { it.file == name }
        fun free(name: String) = !taken(name) && themes.none { it.name == name } && !File(dir, "$name.json").exists()
        val renamed = taken(read.name)
        val decoded = if (!renamed) read else generateSequence(1) { it + 1 }.map { read.copy(name = suffixed(read.name, "-$it")) }
            .first { free(it.name) }
        val oldTheme = themes.find { it.name == decoded.name } as? Theme.Custom
        val newCreated = oldTheme == null
        val unlisted = if (oldTheme == null) themeFile(decoded).takeIf { it.isFile }?.let { file ->
            runCatching { Json.decodeFromString(CustomThemeSerializer, file.readText()) }.onFailure {
                if (it is CustomThemeSerializer.UnknownVersion) errorRuntime(R.string.exception_theme_newer_kept)
            }.getOrNull()
        } else null
        val files = foundFiles(decoded.look, extracted)
        // a file no theme names is taken over, else that name could never be imported
        val others = othersFiles(decoded.name, themes)
        files.keys.firstOrNull { it in others }?.let { errorRuntime(R.string.exception_theme_file_clash, it) }
        val oldFiles = (oldTheme ?: unlisted)?.look?.files().orEmpty().map { File(it).name }.toSet()
        val newTheme = decoded.copy(look = decoded.look.mapFiles { File(dir, File(it).name).path })
        writingInSaving { place(files, oldFiles, newTheme) }
        // only once the json names the new files, so a failed write leaves the replaced theme whole
        (oldFiles - files.keys - others).mapNotNull(::importedFile).forEach { it.delete() }
        return Imported(newCreated, newTheme, migrated, read.name.takeIf { renamed })
    }

    // cut by whole code points so the json's file name stays within 255 bytes
    @VisibleForTesting
    internal fun suffixed(name: String, suffix: String): String {
        var base = name
        while (base.isNotEmpty() && "$base$suffix.json".toByteArray().size > MAX_FILE_NAME_BYTES) {
            base = base.substring(0, base.offsetByCodePoints(base.length, -1))
        }
        return base + suffix
    }

    // all checked before any is copied: one refused would leave the others in dir
    private fun foundFiles(look: ThemeLook, extracted: List<File>): Map<String, File> {
        val files = look.files().associate { name ->
            val target = importedFile(name) ?: errorRuntime(R.string.exception_theme_file, name)
            target.name to (extracted.find { it.isFile && it.name == target.name } ?: errorRuntime(R.string.exception_theme_file, name))
        }
        check(look, files)
        return files
    }

    /** Unlisted jsons are read loosely: any string naming a file here counts. */
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

    /** All copied beside first, renamed in json last, so a failure leaves no orphan whose name no import could take. */
    private fun place(files: Map<String, File>, oldFiles: Set<String>, theme: Theme.Custom) {
        val staged = mutableMapOf<String, File>()
        val placed = mutableListOf<File>()
        var json: File? = null
        var done = false
        try {
            files.forEach { (name, from) -> staged[name] = stage { out -> from.inputStream().use { it.copyTo(out) } } }
            json = stageJson(theme)
            staged.forEach { (name, temp) -> File(dir, name).also { temp.renameOver(it); placed += it } }
            // under the lock listThemes moves a json under
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
        listOfNotNull(look.text.keyFont, look.text.candidateFont).distinct().forEach { name ->
            if (!probe.fontReads(of(name))) errorRuntime(R.string.exception_theme_font, File(name).name)
        }
    }

    private fun refused(breach: ThemeAssetLimits.Breach): Nothing {
        val (message, limit) = REFUSALS.getValue(breach)
        errorRuntime(message, limit.toString())
    }

    // a table, not a when: one entry per breach, held to every breach by a test
    internal val REFUSALS: Map<ThemeAssetLimits.Breach, Pair<Int, Long>> = mapOf(
        ThemeAssetLimits.Breach.Entries to (R.string.exception_theme_entries to ThemeAssetLimits.MAX_ENTRIES.toLong()),
        ThemeAssetLimits.Breach.FileSize to (R.string.exception_theme_file_size to ThemeAssetLimits.MAX_FILE_BYTES / MB),
        ThemeAssetLimits.Breach.TotalSize to (R.string.exception_theme_total_size to ThemeAssetLimits.MAX_TOTAL_BYTES / MB),
        ThemeAssetLimits.Breach.PictureSide to (R.string.exception_theme_picture_side to ThemeAssetLimits.MAX_PICTURE_SIDE.toLong()),
        ThemeAssetLimits.Breach.KeyImageSide to (R.string.exception_theme_key_image_side to ThemeAssetLimits.MAX_KEY_IMAGE_SIDE.toLong()),
        ThemeAssetLimits.Breach.KeyImageSize to (R.string.exception_theme_key_image_size to ThemeAssetLimits.MAX_KEY_IMAGE_BYTES / MB),
        ThemeAssetLimits.Breach.Overrides to (R.string.exception_theme_overrides to ThemeAssetLimits.MAX_OVERRIDES.toLong()),
        ThemeAssetLimits.Breach.ThemeXml to (R.string.exception_theme_xml_size to ThemeAssetLimits.MAX_THEME_XML_BYTES / KB),
        ThemeAssetLimits.Breach.Layouts to (R.string.exception_theme_layouts to ThemeAssetLimits.MAX_LAYOUTS.toLong()),
        ThemeAssetLimits.Breach.LayoutSize to (R.string.exception_theme_layout_size to ThemeAssetLimits.MAX_LAYOUT_BYTES / KB),
        ThemeAssetLimits.Breach.PreviewSize to (R.string.exception_theme_preview_size to ThemeAssetLimits.MAX_PREVIEW_BYTES / KB),
        ThemeAssetLimits.Breach.PreviewSide to (R.string.exception_theme_preview_side to ThemeAssetLimits.MAX_PREVIEW_SIDE.toLong()),
        ThemeAssetLimits.Breach.IconSize to (R.string.exception_theme_icon_size to ThemeAssetLimits.MAX_ICON_BYTES / KB),
        ThemeAssetLimits.Breach.EffectImageSide to (R.string.exception_theme_effect_image_side to ThemeAssetLimits.MAX_EFFECT_IMAGE_SIDE.toLong()),
        ThemeAssetLimits.Breach.EffectImageSize to (R.string.exception_theme_effect_image_size to ThemeAssetLimits.MAX_EFFECT_IMAGE_BYTES / KB),
        ThemeAssetLimits.Breach.EffectImageType to (R.string.exception_theme_effect_image_type to 0L),
        ThemeAssetLimits.Breach.SoundSize to (R.string.exception_theme_sound_size to SoundFile.MAX_BYTES / KB),
        ThemeAssetLimits.Breach.RasterIconSide to (R.string.exception_theme_raster_icon_side to ThemeAssetLimits.MAX_RASTER_ICON_SIDE.toLong()),
        ThemeAssetLimits.Breach.RasterIconSize to (R.string.exception_theme_icon_size to ThemeAssetLimits.MAX_RASTER_ICON_BYTES / KB),
        ThemeAssetLimits.Breach.StickerSide to (R.string.exception_theme_sticker_side to ThemeAssetLimits.MAX_STICKER_SIDE.toLong()),
        ThemeAssetLimits.Breach.StickerSize to (R.string.exception_theme_sticker_size to ThemeAssetLimits.MAX_STICKER_BYTES / KB),
        ThemeAssetLimits.Breach.RasterType to (R.string.exception_theme_raster_type to 0L),
    )

    private const val MB = 1024L * 1024
    private const val KB = 1024L
    private const val MS_A_SECOND = 1000
    private const val MAX_FILE_NAME_BYTES = 255

}

// an older json names files by absolute path, maybe the old package's
internal fun Theme.Custom.foundIn(dir: File, isThemeAsset: (File) -> Boolean) =
    copy(look = look.mapFiles { File(dir, File(it).name).path })
        .takeIf { it.look.files().all { f -> isThemeAsset(File(f)) } }
