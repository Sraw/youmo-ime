/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Looper
import android.widget.Toast
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode
import org.fcitx.fcitx5.android.utils.snapshot
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.IOException

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    // first, as the settings' defaults are of them
    private var builtinThemes: List<Theme.Builtin> = ThemeMonet.systemColors(appContext).let {
        ThemePackFiles.systemColors = it
        BuiltinThemeFiles.load(appContext.resources, it)
    }

    val BuiltinThemes: List<Theme.Builtin> get() = builtinThemes

    fun builtin(ref: String): Theme.Builtin = BuiltinThemeFiles.find(builtinThemes, ref) ?: error("no built-in theme $ref")

    val DefaultTheme: Theme.Builtin get() = builtin("pixel#dark")

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs).also {
        ThemeLookMigration.monetLook = it.monetLook()
        it.migrateSizeFollow()
        it.migrateKeyboardStyle()
    }

    private val keyboardStyleKeys = with(prefs) {
        setOf(keyboardStylePortrait.key, keyboardStyleLandscape.key, keyboardStyleUnderPortrait.key, keyboardStyleUnderLandscape.key)
    }

    // a user 3.0 theme of one of these names is moved off it ([clashesBuiltin])
    private val builtinPackNames: Set<String> = BuiltinPacks.dir(appContext).let { root ->
        BuiltinPacks.unpack(root) { BuiltinPacks.open(appContext, it) }
        BuiltinPacks.names(root).toSet()
    }

    // before the themes: an older format's are read with the pre-3.0 key style settings
    private val customThemes: MutableList<Theme.Custom> = listCustomThemes(prefs).first.toMutableList()

    // after the 3.0 themes, whose listing clears a killed save's leftovers. Old packs get 分词 once, before anything is read
    private var packs: List<Theme.Pack> = run {
        ThemeSeparators.addSeparatorsOnce()
        ThemeFilesManager.listPacks(builtinThemes)
    }

    // a user pack of the same name is shown instead
    private var builtinPacks: List<Theme.Pack> = listBuiltinPacks()

    private fun listBuiltinPacks(): List<Theme.Pack> = BuiltinPacks.dir(appContext).let { root ->
        BuiltinPacks.unpack(root) { BuiltinPacks.open(appContext, it) }
        BuiltinPacks.list(root, builtinThemes, packs.mapTo(HashSet()) { it.file })
    }

    val BuiltinPackNames: Set<String> get() = builtinPackNames

    val BuiltinPackThemes: List<Theme.Pack> get() = builtinPacks

    private val allPacks get() = packs + builtinPacks

    val Packs: List<Theme.Pack> get() = packs

    /**
     * Finishes a pack import's replacement of 3.0 themes on the main thread once the list has the pack; a slot naming one
     * bare names the version of the same lightness. A record no pack lists is dropped only [atStart]. Returns whether a
     * slot was renamed.
     */
    private fun finishReplacements(atStart: Boolean = false): Boolean {
        val recorded = runCatching { ThemeFilesManager.replacing() }.getOrElse {
            Timber.w(it, "Themes replaced by packs not read")
            return false
        }
        if (!ThemeFilesManager.writable || recorded.isEmpty()) return false
        var renamed = false
        val done = recorded.filterTo(HashSet()) { name ->
            val custom = customThemes.find { it.name == name }
            val versions = packs.filter { it.name == name || it.file == name }
            if (custom == null || (versions.isEmpty() && atStart)) return@filterTo true
            if (versions.isEmpty()) return@filterTo false
            val to = if (versions.any { it.name == name }) null else (versions.firstOrNull { it.isDark == custom.isDark } ?: versions.first()).name
            val named = to != null && prefs.namesTheme(name)
            val written = !named || quietly { prefs.renameThemeRefs(name, to!!) }
            if (!written) Timber.w("Settings naming theme $name not written: replaced by its pack at the next listing")
            if (named && written) renamed = true
            written && replacedDeleted(custom).also { if (it) customThemes.remove(custom) }
        }
        // thrown, every start would crash in onCreate
        runCatching { ThemeFilesManager.replaced(done) }.onFailure { Timber.w(it, "Themes replaced by packs not recorded as done") }
        return renamed
    }

    @VisibleForTesting
    internal fun finishReplacementsAtStart() = finishReplacements(atStart = true)

    // the change is fired once the list is, not as the theme is replaced
    private var finishing = false

    private inline fun <T> quietly(write: () -> T): T {
        finishing = true
        try {
            return write()
        } finally {
            finishing = false
        }
    }

    private fun replacedDeleted(custom: Theme.Custom): Boolean = try {
        ThemeFilesManager.deleteThemeFiles(custom).also { if (!it) Timber.w("Theme ${custom.name} replaced by its pack not deleted") }
    } catch (e: IOException) {
        Timber.w(e, "Theme ${custom.name} replaced by its pack not deleted")
        false
    }

    // once a process, after the packs are listed, before any is drawn
    private var crashConsulted = false

    private fun packsListed() {
        if (crashConsulted || !ThemeFilesManager.writable) return
        crashConsulted = true
        PackDrawGuard.crashed()?.let(::packCrashed)
    }

    @VisibleForTesting
    internal fun consultPackCrash() {
        crashConsulted = false
        packsListed()
    }

    /** Its slots go back to defaults and the user is told; the pack is kept. */
    @VisibleForTesting
    internal fun packCrashed(file: String) {
        val refs = packs.filter { it.file == file }.map { it.name }.filter { r -> customThemes.none { it.name == r } }
        Timber.w("Theme pack $file did not draw its first frame: the settings naming it reset")
        if (prefs.dropThemeRefs(refs.toSet())) appContext.toast(appContext.getString(R.string.theme_pack_draw_failed, file), Toast.LENGTH_LONG)
    }

    /** A user theme named as a built-in (`monokai`) is resaved under a UUID; then 0.5's names migrated. Whether a setting changed. */
    @VisibleForTesting
    internal fun listCustomThemes(prefs: ThemePrefs): Pair<List<Theme.Custom>, Boolean> {
        var changed = false
        val themes = ThemeFilesManager.moveClashing(
            ThemeFilesManager.listThemes(prefs.legacyLook()),
            clashes = ::clashesBuiltin,
            repoint = { from, to -> quietly { prefs.renameThemeRefs(from, to) }.also { changed = changed || it } },
        )
        val customs = themes.mapTo(HashSet()) { it.name }
        customs += ThemeFilesManager.mayHoldThemes(BuiltinThemeFiles.LEGACY.keys + BuiltinThemeFiles.RETIRED.keys)
        if (prefs.migrateThemeRefs(customs)) changed = true
        return themes to changed
    }

    @VisibleForTesting
    internal fun clashesBuiltin(name: String) = BuiltinThemeFiles.find(builtinThemes, name) != null || name in BuiltinThemeFiles.RETIRED ||
        name in builtinPackNames

    /** User 3.0, then pack version (`name#dark`), then built-in, then an unmigrated 0.5 name. */
    fun getTheme(name: String): Theme? =
        customThemes.find { it.name == name } ?: BuiltinThemeFiles.find(packs, name) ?: builtinRef(name)
            ?: BuiltinThemeFiles.moved(name)?.let(::builtinRef)

    private fun builtinRef(ref: String): Theme? = BuiltinThemeFiles.find(builtinThemes, ref) ?: BuiltinThemeFiles.find(builtinPacks, ref)

    fun getAllThemes(): List<Theme> = packs + customThemes + builtinThemes + builtinPacks

    private fun reloadMonet(): Boolean {
        val colors = ThemeMonet.systemColors(appContext)
        val monet = BuiltinThemeFiles.load(appContext.resources, colors, BuiltinThemeFiles.FILES.filter { it.first == BuiltinThemeFiles.MONET })
        val others = builtinThemes.filter { it.file != BuiltinThemeFiles.MONET }
        builtinThemes = monet + others
        if (colors == ThemePackFiles.systemColors) return false
        ThemePackFiles.systemColors = colors
        packs = packs.map { it.copy() }
        builtinPacks = builtinPacks.map { it.copy() }
        return true
    }

    fun refreshThemes() = refreshThemes { false }

    // [repoint] runs after reading the list and before finding the active theme, so the change fires once
    private fun refreshThemes(repoint: () -> Boolean) {
        val (themes, changed) = listCustomThemes(prefs)
        customThemes.clear()
        customThemes.addAll(themes)
        packs = ThemeFilesManager.listPacks()
        builtinPacks = listBuiltinPacks()
        val renamed = finishReplacements() or repoint()
        packsListed()
        // the copy for before the first unlock
        if (changed || renamed) syncToDeviceEncryptedStorage()
        activeTheme = evaluateActiveTheme()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    val activeOrNull: Theme? get() = if (::_activeTheme.isInitialized) _activeTheme else null

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        prefetch()
        onChangeListeners.snapshot().forEach { it.onThemeChange(_activeTheme) }
    }

    private fun prefetch() {
        val dm = appContext.resources.displayMetrics
        val landscape = KeyboardArea.landscape(appContext)
        val look = shownLook(_activeTheme)
        // a themed bar icon draws nothing till read: queued on the decode thread it would be hundreds of ms late
        val icons = PackIcons.of(_activeTheme)
        val picture = Runnable { ThemeAssets.prefetch(look, landscape, dm.widthPixels, dm.density) }
        when {
            icons === PackIcons.EMPTY -> picture.run()
            Looper.myLooper() == Looper.getMainLooper() -> {
                BarIconDrawables.readAhead(icons)
                picture.run()
            }
            else -> ThemeAssets.poster(Runnable {
                BarIconDrawables.readAhead(icons)
                picture.run()
            })
        }
    }

    // the same object while neither changes: ThemeAssets caches decodes by identity
    private class Shown(val theme: ThemeLook, val choice: KeyStyleChoice, val look: ThemeLook)

    @Volatile
    private var shown: Shown? = null

    // asked a few times a key as a keyboard is made
    @Volatile
    private var keyStyle = startKeyStyle(prefs)

    /** A pack's keys are styled by them in the scene (Env.keyStyle). */
    val keyStyleShown: KeyStyleChoice get() = keyStyle

    @VisibleForTesting
    internal fun startKeyStyle(prefs: ThemePrefs): KeyStyleChoice {
        prefs.migrateKeySpacing()
        return prefs.keyStyleChoice()
    }

    private val keyStyleKeys = setOf(prefs.keyBorderChoice.key, prefs.keyHSpacingChoice.key, prefs.keyVSpacingChoice.key, prefs.keyRadiusChoice.key)

    /** All keyboard reads go through this so none misses the key style settings; the theme's own uses read [Theme.look]. */
    fun shownLook(theme: Theme): ThemeLook {
        val choice = keyStyle
        shown?.let { if (it.theme === theme.look && it.choice == choice) return it.look }
        return choice.applyTo(theme.look).also { shown = Shown(theme.look, choice, it) }
    }

    /** False while not settled or not written (the user told). */
    fun saveTheme(theme: Theme.Custom): Boolean {
        if (refuseIfNotSettled()) return false
        try {
            ThemeFilesManager.saveThemeFiles(theme)
        } catch (e: IOException) {
            // a full disk: file and list as they were; the message is an app-files path, so the user is told in words
            Timber.w(e, "Theme ${theme.name} not saved")
            appContext.toast(R.string.theme_not_saved, Toast.LENGTH_LONG)
            return false
        }
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        // its files may be new under the same names
        filesChanged(theme.name) {
            if (activeTheme.name == theme.name) {
                activeTheme = theme
            }
        }
        return true
    }

    /** A 3.0 theme beside a same-named pack keeps the other. */
    fun deleteTheme(theme: Theme): Boolean = when (theme) {
        is Theme.Pack -> !theme.builtin && deleteTheme(theme.name, ofCustom = false, ofPack = true)
        is Theme.Custom -> deleteTheme(theme.name, ofCustom = true, ofPack = false)
        else -> deleteTheme(theme.name)
    }

    /** A pack version deletes the whole pack. */
    fun deleteTheme(name: String): Boolean = deleteTheme(name, ofCustom = true, ofPack = true)

    private fun deleteTheme(name: String, ofCustom: Boolean, ofPack: Boolean): Boolean {
        if (refuseIfNotSettled()) return false
        if (ofCustom) customThemes.find { it.name == name }?.also {
            // json not deleted: still listed next time, so not shown as gone
            if (!ThemeFilesManager.deleteThemeFiles(it)) {
                Timber.w("Theme $name not deleted")
                return false
            }
            customThemes.remove(it)
        }
        val pack = if (!ofPack) null else packs.find { it.name == name }?.also { pack ->
            ThemeFilesManager.deletePack(pack.file)
            packs = packs.filter { it.file != pack.file }
        }
        filesChanged(name) {
            if (activeTheme.name == name || (pack != null && (activeTheme as? Theme.Pack)?.file == pack.file)) {
                activeTheme = evaluateActiveTheme()
            }
        }
        return true
    }

    /** Decoded files dropped first: a file may be new under its old name; the active theme told as changed regardless. */
    fun filesChanged(name: String, update: () -> Unit = {}) {
        ThemeAssets.clear()
        SceneThemes.forgetAll()
        val before = _activeTheme
        update()
        when {
            _activeTheme !== before -> Unit
            before.name == name -> fireChange()
            else -> prefetch()
        }
    }

    fun packPlaced(file: String) {
        // one version replaced by two: a slot naming it bare names the same lightness
        val single = packs.singleOrNull { it.file == file && it.name == file }
        // repointed before the theme in use is found: never the default for a moment
        filesChanged((_activeTheme as? Theme.Pack)?.takeIf { it.file == file }?.name ?: file) {
            refreshThemes { single != null && repointBare(file, single.isDark) }
        }
    }

    private fun repointBare(file: String, dark: Boolean): Boolean {
        val versions = packs.filter { it.file == file }
        if (versions.size < 2 || !prefs.namesTheme(file)) return false
        val to = (versions.firstOrNull { it.isDark == dark } ?: versions.first()).name
        return quietly { prefs.renameThemeRefs(file, to) }.also { if (!it) Timber.w("Settings naming theme pack $file not renamed to $to") }
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = systemVersion(theme)
        prefs.normalModeTheme.setValue(theme)
    }

    private fun evaluateActiveTheme(): Theme {
        return systemVersion(
            if (prefs.followSystemDayNightTheme.getValue()) {
                if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
            } else {
                prefs.normalModeTheme
            }.getValue(),
        )
    }

    /** An imported pair follows the system's dark mode regardless of the slot; built-in pairs keep the slot's version. */
    private fun systemVersion(theme: Theme): Theme {
        if (theme !is Theme.Pack || theme.builtin || theme.isDark == isDarkMode) return theme
        return packs.firstOrNull { it.file == theme.file && it.isDark == isDarkMode } ?: theme
    }

    /** Light first. */
    fun packPair(theme: Theme): Pair<Theme.Pack, Theme.Pack>? {
        if (theme !is Theme.Pack) return null
        val versions = allPacks.filter { it.file == theme.file && it.builtin == theme.builtin }
        val light = versions.firstOrNull { !it.isDark } ?: return null
        val dark = versions.firstOrNull { it.isDark } ?: return null
        return light to dark
    }

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        // finishReplacements repoints slots: the theme changes once the list does
        if (finishing) return@OnChangeListener
        when (key) {
            in prefs.dayNightModePrefNames -> activeTheme = evaluateActiveTheme()
            // InputView moves the board itself; a float/split change remakes views keeping the text (ViewRebuild.STYLE)
            prefs.splitDock.key, prefs.oneHandedSide.key, in keyboardStyleKeys -> Unit
            in keyStyleKeys -> {
                keyStyle = prefs.keyStyleChoice()
                fireChange()
            }
            else -> fireChange()
        }
    }

    // not settled: anything saved would be lost at the next start
    fun refuseIfNotSettled() = !ThemeFilesManager.writable.also { if (!it) appContext.toast(R.string.user_data_not_settled) }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        // on the main thread (the toast), before the theme in use is read
        finishReplacements(atStart = true)
        packsListed()
        _activeTheme = evaluateActiveTheme()
        settleKeyStyle()
        listPackLayouts()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.registerOnChangeListener(onThemePrefsChange)
        prefetch()
    }

    // once, and for packs a backup restored; off the main thread
    private fun listPackLayouts() {
        ThemeLayoutsToList.atStart(packs) {
            val other = if (prefs.followSystemDayNightTheme.getValue()) (if (isDarkMode) prefs.lightModeTheme else prefs.darkModeTheme).getValue() else null
            ThemeLayoutsToList.InUse(_activeTheme, other, isDarkMode)
        }
    }

    // not while unsettled or an older theme is unsaved
    private fun settleKeyStyle() {
        if (!ThemeFilesManager.writable || ThemeFilesManager.rewriteFailed) return
        val slots = listOf(prefs.normalModeTheme, prefs.lightModeTheme, prefs.darkModeTheme).map { p ->
            ThemeLookMigration.Slot(get = p::getValue, set = { p.setValue(it) })
        }
        val tell = prefs.settleLook(slots, ::saveTheme)
        reloadMonet()
        _activeTheme = evaluateActiveTheme()
        if (tell) appContext.toast(R.string.key_style_moved, Toast.LENGTH_LONG)
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        isDarkMode = newConfig.isDarkMode()
        val packsRead = reloadMonet()
        // `ManagedThemePreference` finds the theme its name refers to ([getTheme]),
        // thus `evaluateActiveTheme()` should be called after Monet's are read again
        val before = _activeTheme
        activeTheme = evaluateActiveTheme()
        // a reread pack equals the old one: drawn anew anyway, its system() new
        if (_activeTheme === before && before is Theme.Pack && packsRead) {
            _activeTheme = evaluateActiveTheme()
            fireChange()
            return
        }
        // a configuration change: the picture decoded ahead; a theme change did that already
        if (_activeTheme === before) prefetch()
    }

    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            // not a managed preference: needed before the first unlock too
            val monet = prefs.monetLook()
            if (monet != null) putString(ThemeLookMigration.MONET_KEY, ThemeLookMigration.encode(monet))
            else remove(ThemeLookMigration.MONET_KEY)
            // not managed preferences: copied so the migration does not rerun before the first unlock
            putBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, prefs.sizeFollowMigrated)
            putBoolean(ThemePrefs.KEYBOARD_STYLE_MIGRATED, prefs.keyboardStyleMigrated)
        }
    }

}