/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.scene.theme.SoundFile
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.input.theme.ThemePackCheck
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.layout.PackSeparator
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.Text
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.input.pet.PetFiles
import org.fcitx.fcitx5.android.utils.isPlainFileName
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/**
 * `v4/<name>/` packs, which an older build (json only) never sees. Placed whole: written into `.saving/new-<random>/`,
 * then renamed in after the old one is set aside; a kill between leaves it aside and the next [list] puts it back.
 */
object ThemePackFiles {

    const val FOLDER = "v4"
    const val THEME_XML = "theme.xml"
    const val LAYOUTS = "layouts"

    private const val SAVING = ".saving"
    private const val NEW = "new-"
    private const val OLD = "old-"
    private const val GONE = "gone-"
    private const val MACOS = "__MACOSX"

    // about a screen of cards plus the one in use
    private val LISTED_KEPT = 16 + BuiltinPacks.FILES.size
    private val VERSIONS = listOf("#light", "#dark")

    @Volatile
    var systemColors: SystemColors = SystemColors.NONE

    fun folder(themes: File) = File(themes, FOLDER)

    /** As many styles as a 3.0 theme written as 4.0, so an export reimports. */
    fun read(source: String): SceneTheme = when (val t = parse(source)) {
        is Parsed.Ok -> t.value
        is Parsed.Failed -> error("a pack does not read: ${t.problems.take(SHOWN)}")
    }

    private fun parse(source: String, appVersion: String? = null) =
        ThemeReader.theme(source.removePrefix(BOM), systemColors, appVersion, LookTheme.MAX_STYLES)

    /** One that no longer reads is drawn as the built-in theme and the user told, never thrown where the keyboard draws. */
    fun scene(dir: String, dark: Boolean): SceneTheme = runCatching { (readListed(dir) ?: read(File(dir, THEME_XML).readText())).variant(dark) }
        .getOrElse { e ->
            Timber.w(e, "Theme pack $dir drawn as the built-in theme")
            EngineNotices.failedOnce(R.string.user_data_unreadable)
            fallback(dark)
        }

    // the IME's process lives long: only the newest few parses are held
    private class Listed(val stamp: Long, val colors: SystemColors, val theme: SceneTheme)

    private val lastListed = ConcurrentHashMap<String, Listed>()

    private fun readListed(dir: String): SceneTheme? = lastListed[dir]
        ?.takeIf { it.colors == systemColors && it.stamp == File(dir, THEME_XML).lastModified() }?.theme

    private fun fallback(dark: Boolean): SceneTheme =
        read(BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_pixel)).variant(dark)

    // LookTheme's order is the Palette's
    private val NAMES = LookTheme.colors(LookDefaults.look(Palette(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)).palette).map { it.first }

    fun palette(scene: SceneTheme): Palette {
        val c = NAMES.map { scene.colors.getValue(it).value }
        return Palette(
            c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16],
            c[17], c[18], c[19], c[20], c[21], c[22], c[23],
        )
    }

    private fun plain(scene: SceneTheme, widget: String, prop: String): Value? = scene.styles.lastOrNull { s ->
        prop in s.values && s.selectors.any { it == Selector(emptySet(), widget) }
    }?.values?.get(prop)

    /** Only what the views around the keys take; keys, bar and popups are drawn from the file ([drawn]). */
    fun look(scene: SceneTheme, palette: Palette, dir: String): ThemeLook {
        fun path(name: String) = File(dir, name).path
        val picture = scene.shownPicture?.let { p ->
            val portrait = p.cuts["portrait"] ?: p.cuts.getValue("landscape")
            Picture(
                path(p.src), Crop(path(portrait), null, 0), p.cuts["landscape"]?.let { Crop(path(it), null, 0) },
                p.brightness.toInt().coerceIn(0, PERCENT), p.blurDp.toInt().coerceIn(0, MAX_BLUR), p.animated,
            )
        }
        fun font(widget: String) = (plain(scene, widget, "font") as? Value.Word)?.word?.let { scene.fonts[it] }?.let(::path)
        val weight = (plain(scene, "candidate", "weight") as? Value.Number)?.number?.toInt()?.takeIf { it in Text.WEIGHTS }
        val text = LookDefaults.TEXT.copy(
            keyFont = font("key"),
            candidateFont = font("candidate"),
            candidateWeight = weight ?: LookDefaults.TEXT.candidateWeight,
        )
        return LookDefaults.look(palette, picture).copy(text = text)
    }

    private const val PERCENT = 100
    private const val MAX_BLUR = 25

    fun drawn(scene: SceneTheme, dir: String): SceneTheme = scene.withPaths { File(dir, it).path }

    /** The engine places the slices; press effects' images included. */
    fun images(scene: SceneTheme): List<NineSlice> {
        val values = scene.styles.flatMap { it.values.values }
        val slices = values.mapNotNull { ((it as? Value.OfFill)?.fill as? Fill.Image) }
            .map { NineSlice(it.file, Insets(0, 0, 0, 0), it.pxPerDp.coerceAtLeast(1)) }
        val effects = values.mapNotNull { (it as? Value.OfEffect)?.effect }.map { NineSlice(it.file, Insets(0, 0, 0, 0), 1) }
        return (slices + effects).distinctBy { it.file }
    }

    /** Light first; a reimport (another `theme.xml` time) is another theme. */
    fun versions(scene: SceneTheme, file: String, dir: File): List<Theme.Pack> {
        val paired = scene.variants.size > 1
        val stamp = File(dir, THEME_XML).lastModified()
        return listOf(false, true).filter { it in scene.variants }
            .map { dark -> Theme.Pack(BuiltinThemeFiles.ref(file, dark, paired), dark, file, dir.path, stamp) }
    }

    /** Null [appVersion]: a dev build, which takes all. */
    fun minAppRefused(minApp: String, appVersion: String?): Boolean =
        appVersion != null && ThemeReader.newer(minApp, appVersion)

    fun laterBuilds(dir: File, appVersion: String?): Boolean = runCatching {
        File(dir, THEME_XML).takeIf { it.length() <= ThemeAssetLimits.MAX_THEME_XML_BYTES }?.readText()?.removePrefix(BOM)
    }.getOrNull()?.let { ThemeReader.laterBuilds(it, appVersion) } == true

    /** A build past a tag (`0.5.2-791-gabc1234`) is checked against nothing, so packs for the next version can be tried. */
    fun minAppVersion(versionName: String): String? = versionName.takeUnless { DEV_BUILD.containsMatchIn(it) }

    private val DEV_BUILD = Regex("-[0-9]+-g[0-9a-f]+$|-untagged$")

    fun refs(name: String, variants: Set<Boolean>): List<String> =
        listOf(false, true).filter { it in variants }.map { BuiltinThemeFiles.ref(name, it, variants.size > 1) }

    fun badName(name: String): Boolean = !isPlainFileName(name) || name.startsWith(".") || VERSIONS.any { name.endsWith(it) }

    /** The settings name one theme by a built-in's reference or 0.5 name. */
    fun builtinClash(name: String, variants: Set<Boolean>, builtins: List<Theme.Builtin>): Boolean {
        val refs = refs(name, variants) + name
        return builtins.any { it.file == name } || refs.any { BuiltinThemeFiles.find(builtins, it) != null || BuiltinThemeFiles.moved(it) != null }
    }

    /** Newest first by `theme.xml`'s time; a pack an import would refuse is left out and told. Kill leftovers swept first. */
    fun list(themes: File, builtins: List<Theme.Builtin>, fresh: Boolean = true): List<Theme.Pack> {
        val root = folder(themes)
        // [fresh] false: a second folder (the built-in packs), whose parses are kept
        if (fresh) lastListed.clear()
        val dirs = root.listFiles(FileFilter { it.isDirectory && !it.name.startsWith(".") }) ?: return emptyList()
        val builtIn = lazy { builtInBoards() }
        return dirs.sortedByDescending { File(it, THEME_XML).lastModified() }.take(ThemeAssetLimits.MAX_PACKS).flatMap { listed(it, builtins, builtIn) }
    }

    fun sweepPacks(themes: File) = sweep(folder(themes))

    // a pack set aside by a killed place is put back where nothing took its place; the rest cleared
    private fun sweep(root: File) {
        val saving = File(root, SAVING)
        saving.listFiles(FileFilter { it.isDirectory && it.name.startsWith(OLD) })?.forEach { aside ->
            val pack = aside.listFiles()?.singleOrNull() ?: return@forEach
            val target = File(root, pack.name)
            if (!badName(pack.name) && !target.exists() && pack.renameTo(target)) Timber.i("Theme pack ${pack.name} put back after a kill")
        }
        saving.deleteRecursively()
        sync(root)
    }

    private fun listed(dir: File, builtins: List<Theme.Builtin>, builtIn: Lazy<List<SceneLayout>>): List<Theme.Pack> {
        val xml = File(dir, THEME_XML)
        val source = xml.takeIf { it.isFile && it.length() <= ThemeAssetLimits.MAX_THEME_XML_BYTES }?.readText()
        val theme = when (val t = source?.let { parse(it) }) {
            is Parsed.Ok -> t.value
            is Parsed.Failed -> return skipped(dir, "does not read: ${t.problems.take(SHOWN)}", R.string.user_data_unreadable)
            null -> return skipped(dir, "has no theme.xml", R.string.user_data_unreadable)
        }
        val name = theme.name
        when {
            name != dir.name -> return skipped(dir, "is named $name", R.string.user_data_unreadable)
            badName(name) || builtinClash(name, theme.variants, builtins) -> return skipped(dir, "has a built-in's name", R.string.user_data_unreadable)
            refusedByRecheck(dir, theme, source, builtIn) -> return skipped(dir, "leaves keys blank", R.string.user_data_unreadable)
        }
        val missing = ThemePackCheck.files(theme).required.firstOrNull { !inside(dir, it) }
        if (missing != null) return skipped(dir, "misses its file $missing", R.string.theme_file_missing)
        if (lastListed.size < LISTED_KEPT) lastListed[dir.path] = Listed(File(dir, THEME_XML).lastModified(), systemColors, theme)
        return versions(theme, dir.name, dir)
    }

    private fun skipped(dir: File, why: String, @StringRes tell: Int): List<Theme.Pack> {
        EngineNotices.failedOnce(tell)
        Timber.w("Theme pack ${dir.name} not listed: it $why")
        return emptyList()
    }

    // never one out of [dir] (a crafted `..`)
    @VisibleForTesting
    internal fun inside(dir: File, name: String): Boolean {
        if (!isPlainFileName(name)) return false
        val f = File(dir, name)
        return f.isFile && try {
            f.canonicalFile.parentFile == dir.canonicalFile
        } catch (_: IOException) {
            false
        }
    }

    /** Never a dot file ([CHECKED] is the check's alone). */
    fun unpacked(name: String): Boolean {
        val parts = name.split('/')
        return when {
            parts.last().startsWith(".") -> false
            parts.size == 1 -> true
            parts.size == 2 && parts[0] == LAYOUTS -> true
            else -> false
        }
    }

    /** Only `layouts/` (without subfolders); a Mac's `__MACOSX/` is ignored. */
    fun foreignFolder(names: Collection<String>): String? = names.firstNotNullOfOrNull { name ->
        val folders = name.split('/').dropLast(1)
        when {
            folders.isEmpty() || folders[0] == MACOS -> null
            folders[0] != LAYOUTS -> folders[0]
            folders.size > 1 -> folders.joinToString("/")
            else -> null
        }
    }

    fun themeXmlInFolder(names: Collection<String>): Boolean =
        THEME_XML !in names && names.any { it.endsWith("/$THEME_XML") && !it.startsWith("$MACOS/") }

    class Staged internal constructor(
        val theme: SceneTheme,
        val source: String,
        internal val from: File,
        internal val files: Set<String>,
        val layouts: List<String>,
        val layoutReport: ThemePackLayouts.Report,
        val warnings: List<String>,
        val preview: File?,
    ) {
        val name: String get() = theme.name
    }

    internal class Checks(
        val probe: ThemeFilesManager.AssetProbe,
        val refused: (ThemeAssetLimits.Breach) -> Nothing,
        val appVersion: String?,
        val lowContrast: (List<Boolean>) -> String?,
        val animatedStill: () -> String = { "animated picture drawn still" },
    )

    /** Checked whole before anything is taken; a style hiding labels of keys one cannot type without is refused. */
    internal fun check(from: File, entries: Collection<String>, checks: Checks): Staged {
        foreignFolder(entries)?.let { errorRuntime(R.string.exception_theme_folder, it) }
        val xml = File(from, THEME_XML)
        ThemeAssetLimits.themeXml(xml.length())?.let(checks.refused)
        val source = xml.readText().removePrefix(BOM)
        val read = readChecked(source, checks)
        val theme = read.value
        // held to their limits before the blank-key check reads them all
        val layouts = checkLayouts(File(from, LAYOUTS), checks)
        blankKeys(from, theme, source, lazy { builtInBoards() }, trustStamp = false).firstOrNull()?.let {
            if (it == BlankKeys.TOO_MANY) errorRuntime(R.string.exception_theme_too_many_scenes)
            errorRuntime(R.string.exception_theme_hidden_required, it.toString())
        }
        val name = theme.name
        if (badName(name)) errorRuntime(R.string.exception_theme_pack_name, name)
        val files = ThemePackCheck.files(theme)
        PetFiles.check(from, theme)
        checkFiles(from, files, checks)
        val still = listOfNotNull(theme.picture, theme.darkPicture).filter { it.animated }.firstNotNullOfOrNull { p ->
            ThemeAssetLimits.animated(File(from, p.src))
        }
        val warnings = read.warnings.map { it.toString() } +
            listOfNotNull(checks.lowContrast(ThemePackCheck.lowContrast(theme)), still?.let { checks.animatedStill() })
        val listed = ThemeLayoutsToList.staged(File(from, LAYOUTS), theme.name, theme.meta, LayoutImport.appVersion(checks.appVersion))
        val report = ThemePackLayouts.report(File(from, LAYOUTS), layouts, { SceneLayouts.parseBuiltIn(appContext, it) }, BuiltInLayouts(appContext)::text, listed.listed)
            .listing(listed.replaced, listed.notFitting)
        return Staged(theme, source, from, present(from, files), layouts, report, warnings, files.preview?.let { File(from, it) })
    }

    private fun readChecked(source: String, checks: Checks): Parsed.Ok<SceneTheme> =
        when (val read = parse(source, checks.appVersion)) {
            is Parsed.Ok -> read
            is Parsed.Failed -> {
                // a too-old app is said so (updating helps), not as a broken file
                val minApp = (parse(source) as? Parsed.Ok)?.value?.meta?.minApp
                if (minApp != null && minAppRefused(minApp, checks.appVersion)) errorRuntime(R.string.exception_theme_min_app, minApp)
                errorRuntime(R.string.exception_theme_invalid, read.problems.take(SHOWN).joinToString("\n"))
            }
        }

    private fun checkFiles(from: File, files: ThemePackCheck.Files, checks: Checks) {
        files.required.firstOrNull { !inside(from, it) || it == THEME_XML }?.let { errorRuntime(R.string.exception_theme_file, it) }
        fun size(name: String) = checks.probe.imageSize(File(from, name)) ?: errorRuntime(R.string.exception_theme_image, name)
        // the source photo too, though only its cuts are drawn
        (files.pictures + listOfNotNull(files.source) + files.darkPictures).forEach { n ->
            size(n).let { (w, h) -> ThemeAssetLimits.picture(w, h)?.let(checks.refused) }
        }
        files.images.forEach { n -> size(n).let { (w, h) -> ThemeAssetLimits.keyImage(w, h, File(from, n).length())?.let(checks.refused) } }
        files.effects.forEach { n -> size(n).let { (w, h) -> ThemeAssetLimits.effectImage(n, w, h, File(from, n).length())?.let(checks.refused) } }
        files.preview?.let { n -> size(n).let { (w, h) -> ThemeAssetLimits.preview(w, h, File(from, n).length())?.let(checks.refused) } }
        files.fonts.forEach { n -> if (!checks.probe.fontReads(File(from, n))) errorRuntime(R.string.exception_theme_font, n) }
        files.icons.forEach { n -> icon(File(from, n), checks) }
        files.stickers.filter { inside(from, it) }.forEach { n -> raster(File(from, n), ThemeAssetLimits::sticker, checks) }
        // an unknown class too: its file is kept and exported
        files.sounds.forEach { n ->
            val f = File(from, n)
            ThemeAssetLimits.soundSize(f.length())?.let(checks.refused)
            SoundFile.check(n, f.readBytes()).firstOrNull()?.let { errorRuntime(R.string.exception_theme_sound, it.message) }
        }
    }

    private fun icon(f: File, checks: Checks) {
        if (RasterIcon.named(f.name)) return raster(f, ThemeAssetLimits::rasterIcon, checks)
        ThemeAssetLimits.icon(f.length())?.let(checks.refused)
        SvgSubset.check(f.readText()).firstOrNull()?.let { errorRuntime(R.string.exception_theme_icon, "${f.name}: $it") }
    }

    // its length before it is read (a header of one pixel stands in), then its header: the bytes alone say its size and whether it moves
    private fun raster(f: File, limit: (Long, RasterIcon.Header?) -> ThemeAssetLimits.Breach?, checks: Checks) {
        val length = f.length()
        limit(length, RasterIcon.Header(1, 1, false))?.let(checks.refused)
        limit(length, RasterIcon.header(f.readBytes()))?.let(checks.refused)
    }

    // a sticker the pack lacks is drawn as what it hides, and is neither copied nor exported
    private fun present(dir: File, files: ThemePackCheck.Files) = files.required + files.stickers.filter { inside(dir, it) }

    private fun checkLayouts(dir: File, checks: Checks): List<String> {
        if (!dir.exists()) return emptyList()
        val all = dir.listFiles().orEmpty().toList()
        all.firstOrNull { !it.isFile || !it.name.endsWith(".xml") || !isPlainFileName(it.name) }
            ?.let { errorRuntime(R.string.exception_theme_layout, it.name) }
        ThemeAssetLimits.layouts(all.map { it.length() })?.let(checks.refused)
        return all.map { it.name }.sorted()
    }

    internal fun boards(dir: File, builtInLayouts: List<SceneLayout> = builtInBoards()): List<BlankKeys.Board> {
        val own = File(dir, LAYOUTS)
        val texts = BuiltInLayouts.RAW.keys.mapNotNull { id -> ThemePackLayouts.read(own, id)?.let { id to it } }.toMap()
        return PackLayouts.drawn(builtInLayouts.map { BlankKeys.Board(it.layout, it.fragments, it.id) }, texts) { BuiltInLayouts(appContext).text(it) }
    }

    // keyboards only: a fragment read as a layout is refused
    private fun builtInBoards(): List<SceneLayout> =
        (BuiltInLayouts.RAW.keys - BuiltInLayouts.FRAGMENTS).mapNotNull { SceneLayouts.parseBuiltIn(appContext, it) }

    @VisibleForTesting
    internal var blankChecks = 0

    /** A passing pack is stamped so a listing (up to 100 packs) skips the check; an app update rechecks only what changed. */
    private fun blankKeys(
        dir: File,
        theme: SceneTheme,
        source: String,
        builtIn: Lazy<List<SceneLayout>>,
        trustStamp: Boolean = true,
        want: String = stampOf(dir, source),
    ): List<BlankKeys.Blank> {
        val stamp = File(dir, CHECKED)
        // an import always checks: a stamp is the app's word, never a pack's
        if (trustStamp && stamp.isFile && stamp.readText() == want) return emptyList()
        stamp.delete()
        blankChecks++
        val found = ThemePackCheck.hidesRequired(theme, boards(dir, builtIn.value))
        if (found.isEmpty()) runCatching { stamp.writeText(want) }.onFailure { Timber.w(it, "cannot stamp ${dir.name}") }
        return found
    }

    /** A stale stamp is rechecked off the main thread, the pack listed meanwhile; [onRefused] reloads the list. */
    private fun refusedByRecheck(dir: File, theme: SceneTheme, source: String, builtIn: Lazy<List<SceneLayout>>): Boolean {
        val want = stampOf(dir, source)
        val stamp = File(dir, CHECKED)
        if (stamp.isFile && runCatching { stamp.readText() }.getOrNull() == want) return false
        val key = dir.path
        if (asked.put(key, want) != want) {
            val listing = Thread.currentThread()
            recheck(Runnable {
                val found = runCatching { blankKeys(dir, theme, source, builtIn, want = want) }
                    .onFailure { Timber.w(it, "Theme pack ${dir.name} not checked again") }.getOrNull()
                if (found.isNullOrEmpty()) return@Runnable
                refused[key] = want
                if (Thread.currentThread() !== listing) onRefused()
            })
        }
        return refused[key] == want
    }

    // by folder: the stamp each was asked for, and the refused ones
    private val asked = ConcurrentHashMap<String, String>()
    private val refused = ConcurrentHashMap<String, String>()
    @VisibleForTesting
    internal fun forgetListings() {
        asked.clear()
        refused.clear()
        lastListed.clear()
    }

    private val checker by lazy { Executors.newSingleThreadExecutor() }

    @VisibleForTesting
    internal var recheck: (Runnable) -> Unit = { checker.execute(it) }

    @VisibleForTesting
    internal var onRefused: () -> Unit = { ContextCompat.getMainExecutor(appContext).execute { ThemeManager.refreshThemes() } }

    @VisibleForTesting
    internal fun stampOf(dir: File, source: String, rule: Int = BlankKeys.RULE): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$rule ${builtInHash()}\n${source.removePrefix(BOM)}".toByteArray())
        File(dir, LAYOUTS).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach {
            md.update("\n${it.name}\n".toByteArray())
            md.update(it.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Once a process. */
    @VisibleForTesting
    internal var builtInHash: () -> String = { builtInBytesHash }

    private val builtInBytesHash by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        BuiltInLayouts.RAW.toSortedMap().forEach { (id, res) ->
            md.update("\n$id\n".toByteArray())
            md.update(appContext.resources.openRawResource(res).use { it.readBytes() })
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A restored backup's stamps are its word, not the app's. */
    fun dropStamps(themes: File) {
        folder(themes).listFiles(FileFilter { it.isDirectory })?.forEach { File(it, CHECKED).delete() }
    }

    // a dot file: neither listed nor exported
    private const val CHECKED = ".checked"

    // e.g. Windows Notepad's: the engine's reader wants `<` first
    private const val BOM = "\uFEFF"
    private const val SHOWN = 5

    /** On failure nothing is left and the old pack is as it was. */
    internal fun place(themes: File, staged: Staged): List<Theme.Pack> {
        val root = folder(themes)
        val saving = File(root, SAVING).apply { mkdirs() }
        val temp = File(saving, NEW + UUID.randomUUID())
        val target = File(root, staged.name)
        var aside: File? = null
        var old: File? = null
        var done = false
        try {
            need(temp.mkdirs(), "cannot make ${temp.name}")
            copy(File(staged.from, THEME_XML), File(temp, THEME_XML))
            staged.files.forEach { copy(File(staged.from, it), File(temp, it)) }
            File(staged.from, CHECKED).takeIf { it.isFile }?.let { copy(it, File(temp, CHECKED)) }
            if (staged.layouts.isNotEmpty()) {
                val layouts = File(temp, LAYOUTS).apply { mkdirs() }
                staged.layouts.forEach { copy(File(staged.from, "$LAYOUTS/$it"), File(layouts, it)) }
                sync(layouts)
            }
            sync(temp)
            if (target.exists()) {
                aside = File(saving, OLD + UUID.randomUUID()).also { need(it.mkdirs(), "cannot make ${it.name}") }
                old = File(aside, staged.name).also { need(target.renameTo(it), "cannot move ${target.name} aside") }
                sync(saving)
            }
            beforeInPlace?.invoke()
            need(temp.renameTo(target), "cannot put ${target.name} in place")
            sync(root)
            done = true
        } finally {
            if (!done) {
                temp.deleteRecursively()
                // where even that fails, left aside for the next listing
                if (old?.exists() == true && !target.exists()) old.renameTo(target)
            }
            if (done || old?.exists() != true) aside?.deleteRecursively()
        }
        return versions(staged.theme, staged.name, target)
    }

    @VisibleForTesting
    internal var beforeInPlace: (() -> Unit)? = null

    private fun need(ok: Boolean, what: String) {
        if (!ok) throw IOException(what)
    }

    private fun copy(from: File, to: File) {
        FileOutputStream(to).use { out ->
            from.inputStream().use { it.copyTo(out) }
            out.fd.sync()
        }
    }

    private fun sync(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Timber.d(e, "Theme pack dir not synced")
        }
    }

    /** Gives an old pack's qwerty the app's 分词 (once); not for non-UTF-8 files, whose other bytes would not round-trip. */
    internal fun addSeparator(themes: File, dir: File, app: SceneLayout, appText: (String) -> String?): String? {
        val layouts = File(dir, LAYOUTS)
        val file = File(layouts, SEPARATED)
        if (!file.isFile) return "no $SEPARATED"
        if (file.length() > ThemeAssetLimits.MAX_LAYOUT_BYTES) return "over the limit"
        val bytes = file.readBytes()
        val text = bytes.decodeToString()
        if (!text.encodeToByteArray().contentEquals(bytes)) return "not UTF-8"
        val bom = if (text.startsWith(BOM)) BOM else ""
        val rewrite = PackSeparator.addSeparator(text.removePrefix(BOM), { ThemePackLayouts.read(layouts, it) }, appText, app.layout, app.fragments)
        if (rewrite is PackSeparator.Rewrite.Kept) return rewrite.why
        val out = (bom + (rewrite as PackSeparator.Rewrite.Done).text).encodeToByteArray()
        // the mark counted, as PackLayouts.readable counts it
        if (out.size > ThemeAssetLimits.MAX_LAYOUT_BYTES) return "over the limit rewritten"
        // not UUID's: SecureRandom made at a cold start for this alone
        val temp = File(File(folder(themes), SAVING).apply { mkdirs() }, SEPARATING + System.nanoTime())
        var done = false
        try {
            FileOutputStream(temp).use { it.write(out); it.fd.sync() }
            beforeInPlace?.invoke()
            need(temp.renameTo(file), "cannot put ${file.name} in place")
            sync(layouts)
            done = true
        } finally {
            if (!done) temp.delete()
        }
        return null
    }

    private const val SEPARATED = "qwerty.xml"
    private const val SEPARATING = "sep-"

    internal fun delete(themes: File, file: String) {
        if (!isPlainFileName(file) || file.startsWith(".")) return
        val root = folder(themes)
        val dir = File(root, file).takeIf { it.isDirectory } ?: return
        val aside = File(File(root, SAVING).apply { mkdirs() }, GONE + UUID.randomUUID())
        (if (dir.renameTo(aside)) aside else dir).deleteRecursively()
        sync(root)
    }

    internal fun export(pack: Theme.Pack, dest: OutputStream) = dest.use { exportTo(pack, it) }

    // [export]'s use closes [dest] even if a read fails before the zip opens
    private fun exportTo(pack: Theme.Pack, dest: OutputStream) {
        val dir = File(pack.dir)
        val source = File(dir, THEME_XML).readText()
        val theme = read(source)
        val files = ThemePackCheck.files(theme)
        files.required.firstOrNull { !inside(dir, it) }?.let { errorRuntime(R.string.exception_theme_file, it) }
        val names = present(dir, files).sorted()
        ZipOutputStream(dest.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(THEME_XML))
            zip.write(source.encodeToByteArray())
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                File(dir, name).inputStream().use { it.copyTo(zip) }
            }
            File(dir, LAYOUTS).listFiles(FileFilter { it.isFile && it.name.endsWith(".xml") }).orEmpty().sortedBy { it.name }.forEach { f ->
                zip.putNextEntry(ZipEntry("$LAYOUTS/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
            }
            zip.closeEntry()
        }
    }

    fun of(look: ThemeLook, name: String, dark: Boolean): Pair<String, Map<String, String>> =
        LookTheme.xml(look, name, dark) to LookTheme.fileNames(look)
}
