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
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.input.theme.ThemePackCheck
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.Text
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.Effect
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.utils.errorRuntime
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
import org.fcitx.fcitx5.android.scene.theme.Picture as ScenePicture
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/**
 * The user's theme 4.0 packs (060-render-p3 T8a): each a folder `v4/<name>/` in the themes' directory,
 * of its `theme.xml`, the files it names by their names, and its `layouts/` (T8b reads them). A 3.0 theme
 * stays a json beside them ([ThemeFilesManager]); a backup takes the folder with the rest, and an older
 * build restoring it lists json files only, so it never sees them.
 *
 * A pack is put in place whole: written into `v4/.saving/new-<random>/`, each file synced, then renamed over
 * its name (the one it replaces renamed away first, into `.saving/old-<random>/<name>`, and deleted after),
 * and the directory synced. A kill between the two renames leaves the old one aside: the next listing puts it
 * back where nothing took its place ([list]).
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

    // the listed packs whose parsed themes are kept: a screen of the theme page's cards and the one in use, mostly
    private const val LISTED_KEPT = 16
    private val VERSIONS = listOf("#light", "#dark")

    /** The system's colours the packs' `system()` take now (Monet's, ThemeManager keeps them). */
    @Volatile
    var systemColors: SystemColors = SystemColors.NONE

    /** The packs' folder in the themes' [themes]. */
    fun folder(themes: File) = File(themes, FOLDER)

    /**
     * [source] read as a pack is: thrown where it does not read. A pack may have the styles a 3.0 theme
     * written as 4.0 has ([LookTheme.MAX_STYLES], past a hand-written file's), so one exported imports again.
     */
    fun read(source: String): SceneTheme = when (val t = parse(source)) {
        is Parsed.Ok -> t.value
        is Parsed.Failed -> error("a pack does not read: ${t.problems.take(SHOWN)}")
    }

    private fun parse(source: String, appVersion: String? = null) =
        ThemeReader.theme(source.removePrefix(BOM), systemColors, appVersion, LookTheme.MAX_STYLES)

    /**
     * The pack in [dir], its version [dark], as it is drawn: the last listing's parse where it was kept and the file
     * has not changed since ([lastListed]), else read from its file. One that no longer reads (changed or removed since it was listed) is drawn as the built-in
     * theme, the user told, never thrown where the keyboard draws.
     */
    fun scene(dir: String, dark: Boolean): SceneTheme = runCatching { (readListed(dir) ?: read(File(dir, THEME_XML).readText())).variant(dark) }
        .getOrElse { e ->
            Timber.w(e, "Theme pack $dir drawn as the built-in theme")
            EngineNotices.failedOnce(R.string.user_data_unreadable)
            fallback(dark)
        }

    // the newest packs ([LISTED_KEPT]) as the last listing read them, their file's time and the system's colours
    // then: their versions' draws and the theme page's cards take it rather than read and parse the file again. A
    // few only, replaced by the next listing: the IME's process lives long, and a hundred packs' themes are not held
    private class Listed(val stamp: Long, val colors: SystemColors, val theme: SceneTheme)

    private val lastListed = ConcurrentHashMap<String, Listed>()

    private fun readListed(dir: String): SceneTheme? = lastListed[dir]
        ?.takeIf { it.colors == systemColors && it.stamp == File(dir, THEME_XML).lastModified() }?.theme

    // the built-in theme a pack that does not read is drawn as (the settings' default's file)
    private fun fallback(dark: Boolean): SceneTheme =
        read(BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_pixel)).variant(dark)

    // the 24 colours by their names in theme 4 (LookTheme's order is the Palette's)
    private val NAMES = LookTheme.colors(LookDefaults.look(Palette(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)).palette).map { it.first }

    /** [scene]'s 24 colours as [ThemeLook] holds them: what the views round the keys are drawn in. */
    fun palette(scene: SceneTheme): Palette {
        val c = NAMES.map { scene.colors.getValue(it).value }
        return Palette(
            c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16],
            c[17], c[18], c[19], c[20], c[21], c[22], c[23],
        )
    }

    // the last value [prop] has in a style selecting [widget] alone, in no scene or state: what a view takes of it
    private fun plain(scene: SceneTheme, widget: String, prop: String): Value? = scene.styles.lastOrNull { s ->
        prop in s.values && s.selectors.any { it == Selector(emptySet(), widget) }
    }?.values?.get(prop)

    /**
     * What the views round [scene]'s keys (InputView's picture, the panels, the candidates) take of it: its
     * colours ([palette]), its picture under the keyboard as a theme 3.0's (LookDefaults), the fonts of its
     * keys' and its candidates' styles and the candidates' weight, its files in [dir]. The rest of a 3.0 look
     * is the defaults' (a built-in theme's): the keys, bar and popups are drawn as the file says ([drawn]).
     */
    fun look(scene: SceneTheme, palette: Palette, dir: String): ThemeLook {
        fun path(name: String) = File(dir, name).path
        val picture = scene.picture?.let { p ->
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

    /**
     * [scene] as it is drawn from the pack's folder [dir]: each file it names (its key images, fonts, icons,
     * picture) by its path, as a 3.0 theme's are in memory; ScenePainter and the fonts take them as they are.
     */
    fun drawn(scene: SceneTheme, dir: String): SceneTheme {
        fun path(name: String) = File(dir, name).path
        fun value(v: Value): Value = when (val e = (v as? Value.OfEffect)?.effect) {
            // A3: a press effect's image by its path too
            is Effect.Ripple -> Value.OfEffect(e.copy(file = path(e.file)))
            is Effect.Particles -> Value.OfEffect(e.copy(file = path(e.file)))
            null -> ((v as? Value.OfFill)?.fill as? Fill.Image)?.let { Value.OfFill(it.copy(file = path(it.file))) } ?: v
        }
        val styles = scene.styles.map { s -> Style(s.selectors, s.values.mapValues { (_, v) -> value(v) }, s.order, s.line) }
        val picture = scene.picture?.let { p -> ScenePicture(path(p.src), p.brightness, p.blurDp, p.cuts.mapValues { path(it.value) }, p.animated) }
        return SceneTheme(
            scene.name, scene.dark, scene.version, scene.colors, styles, scene.fonts.mapValues { path(it.value) },
            scene.icons.mapValues { path(it.value) }, picture, scene.variants, scene.baseColors, scene.darkColors, scene.meta, scene.systemColors,
            scene.soundFiles.map { (key, file) -> key to path(file) },
        )
    }

    /**
     * The key images [scene]'s styles name (its [drawn] one's: by path), each once, as ThemeAssets decodes a
     * keyboard's (only the file is read of each: the engine places the slices); its keys' press effects' images
     * with them (A3), decoded as one is.
     */
    fun images(scene: SceneTheme): List<NineSlice> {
        val values = scene.styles.flatMap { it.values.values }
        val slices = values.mapNotNull { ((it as? Value.OfFill)?.fill as? Fill.Image) }
            .map { NineSlice(it.file, Insets(0, 0, 0, 0), it.pxPerDp.coerceAtLeast(1)) }
        val effects = values.mapNotNull { (it as? Value.OfEffect)?.effect }.map { NineSlice(it.file, Insets(0, 0, 0, 0), 1) }
        return (slices + effects).distinctBy { it.file }
    }

    /**
     * The versions of the pack [file] in [dir], [scene] its theme read: each a [Theme.Pack], the light first, told
     * from the pack there before it by its `theme.xml`'s time (a pack imported again is another theme).
     */
    fun versions(scene: SceneTheme, file: String, dir: File): List<Theme.Pack> {
        val paired = scene.variants.size > 1
        val stamp = File(dir, THEME_XML).lastModified()
        return listOf(false, true).filter { it in scene.variants }
            .map { dark -> Theme.Pack(BuiltinThemeFiles.ref(file, dark, paired), dark, file, dir.path, stamp) }
    }

    /** Whether a theme asking for app [minApp] is refused by app [appVersion] (null: a dev build, which takes all). */
    fun minAppRefused(minApp: String, appVersion: String?): Boolean =
        appVersion != null && ThemeReader.newer(minApp, appVersion)

    /**
     * Whether the pack folder [dir] holds a later build's file than the app [appVersion]'s ([ThemeReader.laterBuilds]);
     * one not read, or past a pack's [ThemeAssetLimits.MAX_THEME_XML_BYTES] (not read whole), not.
     */
    fun laterBuilds(dir: File, appVersion: String?): Boolean = runCatching {
        File(dir, THEME_XML).takeIf { it.length() <= ThemeAssetLimits.MAX_THEME_XML_BYTES }?.readText()?.removePrefix(BOM)
    }.getOrNull()?.let { ThemeReader.laterBuilds(it, appVersion) } == true

    /**
     * The version a pack's `min-app` is checked against for the app named [versionName], or null for none.
     * A release (`0.6.0`) and a pre-release at its tag (`0.6.0-beta1`) are their numeric core
     * ([ThemeReader.newer] compares no more). A build past a tag (`0.5.2-791-gabc1234`) or with no tag
     * (`-untagged`) is the code going to the next version, not its last tag's: checked against nothing, so
     * the packs made for that next version can be tried on it.
     */
    fun minAppVersion(versionName: String): String? = versionName.takeUnless { DEV_BUILD.containsMatchIn(it) }

    private val DEV_BUILD = Regex("-[0-9]+-g[0-9a-f]+$|-untagged$")

    /** How the settings would refer to the versions of a pack named [name] of [variants] ([BuiltinThemeFiles.ref]). */
    fun refs(name: String, variants: Set<Boolean>): List<String> =
        listOf(false, true).filter { it in variants }.map { BuiltinThemeFiles.ref(name, it, variants.size > 1) }

    /** Whether [name] may not be a pack's: a path, `.`-led (the folder's own), or a version's name (`x#dark`). */
    fun badName(name: String): Boolean = !isPlainFileName(name) || name.startsWith(".") || VERSIONS.any { name.endsWith(it) }

    /**
     * Whether a pack [name] of [variants] would be named as a built-in theme of [builtins] is (its file's name,
     * or one of its versions' a built-in's reference or 0.5 name): the settings name one theme by it.
     */
    fun builtinClash(name: String, variants: Set<Boolean>, builtins: List<Theme.Builtin>): Boolean {
        val refs = refs(name, variants) + name
        return builtins.any { it.file == name } || refs.any { BuiltinThemeFiles.find(builtins, it) != null || BuiltinThemeFiles.moved(it) != null }
    }

    /**
     * The packs in [themes] (the themes' directory), newest first (the newest [ThemeAssetLimits.MAX_PACKS]), each
     * of its versions. Not listed, logged and the user told as a 3.0 theme's json is, a pack an import would refuse:
     * one whose `theme.xml` does not read (a later build's, one cut short), of a name not its folder's, not a pack's
     * or a built-in's ([builtins]: one restored, or a later build's built-in), hiding the keys' labels one cannot type
     * without, or missing a file it names. What a kill left of a pack being written is the caller's to clear first
     * ([sweepPacks], under its lock). Newest by its `theme.xml`'s time (the
     * import's), not the folder's: the check's stamp written in it changes that one.
     */
    fun list(themes: File, builtins: List<Theme.Builtin>, fresh: Boolean = true): List<Theme.Pack> {
        val root = folder(themes)
        // [fresh] false: a second folder listed after the themes' (the built-in packs), whose parses are kept
        if (fresh) lastListed.clear()
        val dirs = root.listFiles(FileFilter { it.isDirectory && !it.name.startsWith(".") }) ?: return emptyList()
        // the app's boards read once a listing, and only for a pack not checked yet (its stamp)
        val builtIn = lazy { builtInBoards() }
        return dirs.sortedByDescending { File(it, THEME_XML).lastModified() }.take(ThemeAssetLimits.MAX_PACKS).flatMap { listed(it, builtins, builtIn) }
    }

    /** What a kill left in [themes]' packs' SAVING cleared. */
    fun sweepPacks(themes: File) = sweep(folder(themes))

    // a kill between a place's renames left the pack it replaced aside (`old-<random>/<name>`): put back where none
    // took its place; the rest (a pack half written, one being deleted, one replaced) cleared
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
        val missing = ThemePackCheck.files(theme).all.firstOrNull { !inside(dir, it) }
        if (missing != null) return skipped(dir, "misses its file $missing", R.string.theme_file_missing)
        if (lastListed.size < LISTED_KEPT) lastListed[dir.path] = Listed(File(dir, THEME_XML).lastModified(), systemColors, theme)
        return versions(theme, dir.name, dir)
    }

    private fun skipped(dir: File, why: String, @StringRes tell: Int): List<Theme.Pack> {
        EngineNotices.failedOnce(tell)
        Timber.w("Theme pack ${dir.name} not listed: it $why")
        return emptyList()
    }

    // [name] a plain file right in [dir] (a crafted name of the theme's, `..` say, never one out of it)
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

    /**
     * The folders a zip's entry [name] (a directory's ending in `/`) may be unpacked into: its top, and `layouts/`'s
     * files; a dot file never (the app's own, [CHECKED], is written by its check alone).
     */
    fun unpacked(name: String): Boolean {
        val parts = name.split('/')
        return when {
            parts.last().startsWith(".") -> false
            parts.size == 1 -> true
            parts.size == 2 && parts[0] == LAYOUTS -> true
            else -> false
        }
    }

    /**
     * Whether a zip of the entries [names] has a folder a pack may not: only `layouts/` (no folder in it), a Mac's
     * `__MACOSX/` ignored.
     */
    fun foreignFolder(names: Collection<String>): String? = names.firstNotNullOfOrNull { name ->
        // the folders the entry is in: `a/b/` and `a/b/x` are in a and b, `x` in none
        val folders = name.split('/').dropLast(1)
        when {
            folders.isEmpty() || folders[0] == MACOS -> null
            folders[0] != LAYOUTS -> folders[0]
            folders.size > 1 -> folders.joinToString("/")
            else -> null
        }
    }

    /** Whether a zip of the entries [names] has its `theme.xml` in a folder, not at its top (a folder zipped whole). */
    fun themeXmlInFolder(names: Collection<String>): Boolean =
        THEME_XML !in names && names.any { it.endsWith("/$THEME_XML") && !it.startsWith("$MACOS/") }

    /**
     * A pack unpacked in [from] and checked, for the user to see before it is taken ([info]): what [commit]
     * ([ThemeFilesManager.Opened.Pack]) puts in place.
     */
    class Staged internal constructor(
        val theme: SceneTheme,
        val source: String,
        internal val from: File,
        internal val files: Set<String>,
        val layouts: List<String>,
        /** Its layouts as they would be drawn: the keyboards replaced, the files not used, their `commit:` actions. */
        val layoutReport: ThemePackLayouts.Report,
        /** Each a line to show: what is ignored by this version, keys hard to read. */
        val warnings: List<String>,
        /** Its preview image, a file checked to be one. */
        val preview: File?,
    ) {
        val name: String get() = theme.name
    }

    /** The checks of an import, as [ThemeFilesManager] has them: its probe of images and fonts, its refusal of a limit. */
    internal class Checks(
        val probe: ThemeFilesManager.AssetProbe,
        val refused: (ThemeAssetLimits.Breach) -> Nothing,
        val appVersion: String?,
        val lowContrast: (List<Boolean>) -> String?,
        // an animated picture drawn still (A4): not refused, said
        val animatedStill: () -> String = { "animated picture drawn still" },
    )

    /**
     * The pack unpacked in [from] (its top files; [entries] every entry's name, as the zip has them) checked
     * whole before any is taken: its `theme.xml` read (refused where it does not: the engine's problems, the
     * first few; `min-app` newer than [Checks.appVersion]), a style hiding the labels of the keys one cannot
     * type without refused, each file it names there and read as what it is named for (images by their header,
     * held to the limits; fonts; icons held to [SvgSubset]), its layouts held to theirs. Thrown with the reason.
     */
    internal fun check(from: File, entries: Collection<String>, checks: Checks): Staged {
        foreignFolder(entries)?.let { errorRuntime(R.string.exception_theme_folder, it) }
        val xml = File(from, THEME_XML)
        ThemeAssetLimits.themeXml(xml.length())?.let(checks.refused)
        val source = xml.readText().removePrefix(BOM)
        val read = readChecked(source, checks)
        val theme = read.value
        // the layouts held to their count and sizes before the blank-key check reads them all
        val layouts = checkLayouts(File(from, LAYOUTS), checks)
        blankKeys(from, theme, source, lazy { builtInBoards() }, trustStamp = false).firstOrNull()?.let {
            if (it == BlankKeys.TOO_MANY) errorRuntime(R.string.exception_theme_too_many_scenes)
            errorRuntime(R.string.exception_theme_hidden_required, it.toString())
        }
        val name = theme.name
        if (badName(name)) errorRuntime(R.string.exception_theme_pack_name, name)
        val files = ThemePackCheck.files(theme)
        checkFiles(from, files, checks)
        val still = theme.picture?.takeIf { it.animated }?.let { p ->
            ThemeAssetLimits.animated(File(from, p.src))
        }
        val warnings = read.warnings.map { it.toString() } +
            listOfNotNull(checks.lowContrast(ThemePackCheck.lowContrast(theme)), still?.let { checks.animatedStill() })
        val report = ThemePackLayouts.report(File(from, LAYOUTS), layouts, { SceneLayouts.parseBuiltIn(appContext, it) }, BuiltInLayouts(appContext)::text)
        return Staged(theme, source, from, files.all, layouts, report, warnings, files.preview?.let { File(from, it) })
    }

    private fun readChecked(source: String, checks: Checks): Parsed.Ok<SceneTheme> =
        when (val read = parse(source, checks.appVersion)) {
            is Parsed.Ok -> read
            is Parsed.Failed -> {
                // one this version is too old for, said so (the update is what helps), not as a broken file
                val minApp = (parse(source) as? Parsed.Ok)?.value?.meta?.minApp
                if (minApp != null && minAppRefused(minApp, checks.appVersion)) errorRuntime(R.string.exception_theme_min_app, minApp)
                errorRuntime(R.string.exception_theme_invalid, read.problems.take(SHOWN).joinToString("\n"))
            }
        }

    private fun checkFiles(from: File, files: ThemePackCheck.Files, checks: Checks) {
        files.all.firstOrNull { !inside(from, it) || it == THEME_XML }?.let { errorRuntime(R.string.exception_theme_file, it) }
        fun size(name: String) = checks.probe.imageSize(File(from, name)) ?: errorRuntime(R.string.exception_theme_image, name)
        // the source photo too, though only its cuts are drawn: every file a pack brings is what it is named
        (files.pictures + listOfNotNull(files.source)).forEach { n -> size(n).let { (w, h) -> ThemeAssetLimits.picture(w, h)?.let(checks.refused) } }
        files.images.forEach { n -> size(n).let { (w, h) -> ThemeAssetLimits.keyImage(w, h, File(from, n).length())?.let(checks.refused) } }
        files.effects.forEach { n -> size(n).let { (w, h) -> ThemeAssetLimits.effectImage(n, w, h, File(from, n).length())?.let(checks.refused) } }
        files.preview?.let { n -> size(n).let { (w, h) -> ThemeAssetLimits.preview(w, h, File(from, n).length())?.let(checks.refused) } }
        files.fonts.forEach { n -> if (!checks.probe.fontReads(File(from, n))) errorRuntime(R.string.exception_theme_font, n) }
        files.icons.forEach { n ->
            val f = File(from, n)
            ThemeAssetLimits.icon(f.length())?.let(checks.refused)
            SvgSubset.check(f.readText()).firstOrNull()?.let { errorRuntime(R.string.exception_theme_icon, "$n: $it") }
        }
        // a class this version does not know too: its file is kept and exported, so held to the same
        files.sounds.forEach { n ->
            val f = File(from, n)
            ThemeAssetLimits.soundSize(f.length())?.let(checks.refused)
            SoundFile.check(n, f.readBytes()).firstOrNull()?.let { errorRuntime(R.string.exception_theme_sound, it.message) }
        }
    }

    private fun checkLayouts(dir: File, checks: Checks): List<String> {
        if (!dir.exists()) return emptyList()
        val all = dir.listFiles().orEmpty().toList()
        all.firstOrNull { !it.isFile || !it.name.endsWith(".xml") || !isPlainFileName(it.name) }
            ?.let { errorRuntime(R.string.exception_theme_layout, it.name) }
        ThemeAssetLimits.layouts(all.map { it.length() })?.let(checks.refused)
        return all.map { it.name }.sorted()
    }

    /**
     * The boards a pack in [dir] would be shown on, for [ThemePackCheck.hidesRequired]: the app's, each replaced by the
     * pack's own of its id only where the app would draw it (PackLayouts.drawn); one drawing refuses leaves the
     * app's, as drawn.
     */
    internal fun boards(dir: File, builtInLayouts: List<SceneLayout> = builtInBoards()): List<BlankKeys.Board> {
        val own = File(dir, LAYOUTS)
        // the pack's files the app would read (ThemePackLayouts.read: an id it has, within the limit)
        val texts = BuiltInLayouts.RAW.keys.mapNotNull { id -> ThemePackLayouts.read(own, id)?.let { id to it } }.toMap()
        return PackLayouts.drawn(builtInLayouts.map { BlankKeys.Board(it.layout, it.fragments) }, texts) { BuiltInLayouts(appContext).text(it) }
    }

    // the keyboards only: a fragment (grouped-bottom) read as a layout is refused, logged as broken
    private fun builtInBoards(): List<SceneLayout> =
        (BuiltInLayouts.RAW.keys - BuiltInLayouts.FRAGMENTS).mapNotNull { SceneLayouts.parseBuiltIn(appContext, it) }

    /** How many times the blank-key check ran, not answered by a pack's stamp: a test's count. */
    @VisibleForTesting
    internal var blankChecks = 0

    /**
     * [ThemePackCheck.hidesRequired] of the pack in [dir], asked once: a pack that passes is stamped ([CHECKED]: a
     * hash of its theme.xml, its layouts, the rule's version and the app's boards: an app update checks again only where those changed), and a listing finds the stamp
     * instead of asking again (the check takes a good part of a second; a listing may hold 100 packs).
     */
    private fun blankKeys(
        dir: File,
        theme: SceneTheme,
        source: String,
        builtIn: Lazy<List<SceneLayout>>,
        trustStamp: Boolean = true,
        want: String = stampOf(dir, source),
    ): List<BlankKeys.Blank> {
        val stamp = File(dir, CHECKED)
        // an import asks always: a stamp is the app's word, never a pack's
        if (trustStamp && stamp.isFile && stamp.readText() == want) return emptyList()
        stamp.delete()
        blankChecks++
        val found = ThemePackCheck.hidesRequired(theme, boards(dir, builtIn.value))
        if (found.isEmpty()) runCatching { stamp.writeText(want) }.onFailure { Timber.w(it, "cannot stamp ${dir.name}") }
        return found
    }

    /**
     * Whether a listing leaves out the pack in [dir] for keys left blank: a stamp that fits, no; one that does not
     * (an app update changed the boards, a backup restored) checked again off the main thread ([recheck]: the check
     * takes a good part of a second a pack), the pack listed meanwhile, and the list read again ([onRefused]) where
     * the check refuses it; that refusal kept for the stamp it was asked for.
     */
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

    // a listing's checks again, by folder: the stamp each was asked for, and the ones refused
    private val asked = ConcurrentHashMap<String, String>()
    private val refused = ConcurrentHashMap<String, String>()
    /** A test's own process: the checks asked, refused and the listing's kept themes forgotten. */
    @VisibleForTesting
    internal fun forgetListings() {
        asked.clear()
        refused.clear()
        lastListed.clear()
    }

    private val checker by lazy { Executors.newSingleThreadExecutor() }

    /** Where a listing's check again runs ([refusedByRecheck]): a worker thread; a test's at once. */
    @VisibleForTesting
    internal var recheck: (Runnable) -> Unit = { checker.execute(it) }

    /** A pack listed refused by its check again: the themes read again on the main thread, the pack left out. */
    @VisibleForTesting
    internal var onRefused: () -> Unit = { ContextCompat.getMainExecutor(appContext).execute { ThemeManager.refreshThemes() } }

    private fun stampOf(dir: File, source: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${BlankKeys.RULE} ${builtInHash()}\n${source.removePrefix(BOM)}".toByteArray())
        File(dir, LAYOUTS).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach {
            md.update("\n${it.name}\n".toByteArray())
            md.update(it.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The app's boards, as a stamp holds them: a hash of their files' bytes, once a process (not read: the stamp
     * changes with any change to them); a test's own.
     */
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

    /** The stamps of the packs under [themes] dropped: a restored backup's are its word, not the app's. */
    fun dropStamps(themes: File) {
        folder(themes).listFiles(FileFilter { it.isDirectory })?.forEach { File(it, CHECKED).delete() }
    }

    // a pack's stamp: its blank-key check passed (a dot file: neither listed nor exported)
    private const val CHECKED = ".checked"

    // a file saved with a byte-order mark (Windows' Notepad): the engine's reader wants `<` first
    private const val BOM = "\uFEFF"
    private const val SHOWN = 5

    /**
     * [staged] put in place in [themes] as `v4/<name>/`, a pack of its name replaced whole: its versions. Nothing
     * of it left if it fails (thrown), the pack it would replace as it was.
     */
    internal fun place(themes: File, staged: Staged): List<Theme.Pack> {
        val root = folder(themes)
        val saving = File(root, SAVING).apply { mkdirs() }
        val temp = File(saving, NEW + UUID.randomUUID())
        val target = File(root, staged.name)
        // the pack it replaces, aside as old-<random>/<name> (what a listing after a kill puts back)
        var aside: File? = null
        var old: File? = null
        var done = false
        try {
            need(temp.mkdirs(), "cannot make ${temp.name}")
            copy(File(staged.from, THEME_XML), File(temp, THEME_XML))
            staged.files.forEach { copy(File(staged.from, it), File(temp, it)) }
            // the check's stamp: the listing takes its word
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
                // the one it replaced back; where even that fails, left aside for the next listing to put back
                if (old?.exists() == true && !target.exists()) old.renameTo(target)
            }
            if (done || old?.exists() != true) aside?.deleteRecursively()
        }
        return versions(staged.theme, staged.name, target)
    }

    /** A test's: run by [place] between moving the pack it replaces aside and putting the new one in place. */
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

    // the renames on disk too: as ThemeFilesManager's syncDir
    private fun sync(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Timber.d(e, "Theme pack dir not synced")
        }
    }

    /** The pack [file] in [themes] gone: moved aside at once, then deleted. */
    internal fun delete(themes: File, file: String) {
        if (!isPlainFileName(file) || file.startsWith(".")) return
        val root = folder(themes)
        val dir = File(root, file).takeIf { it.isDirectory } ?: return
        val aside = File(File(root, SAVING).apply { mkdirs() }, GONE + UUID.randomUUID())
        (if (dir.renameTo(aside)) aside else dir).deleteRecursively()
        sync(root)
    }

    /** [pack] as a `.theme` zip into [dest]: its `theme.xml` first, then the files it names, then its layouts. */
    internal fun export(pack: Theme.Pack, dest: OutputStream) = dest.use { exportTo(pack, it) }

    // a read failing before the zip is opened closes [dest] all the same ([export]'s use)
    private fun exportTo(pack: Theme.Pack, dest: OutputStream) {
        val dir = File(pack.dir)
        val source = File(dir, THEME_XML).readText()
        val theme = read(source)
        val names = ThemePackCheck.files(theme).all.sorted()
        names.firstOrNull { !inside(dir, it) }?.let { errorRuntime(R.string.exception_theme_file, it) }
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

    /**
     * [look] (a 3.0 theme's, named [name]) as a pack's `theme.xml` and its files by the names it gives them
     * ([LookTheme.fileNames]): drawn as the 3.0 theme is ([LookTheme.xml], its candidates' font and weight
     * with it since 060-render-p3 T6a).
     */
    fun of(look: ThemeLook, name: String, dark: Boolean): Pair<String, Map<String, String>> =
        LookTheme.xml(look, name, dark) to LookTheme.fileNames(look)
}
