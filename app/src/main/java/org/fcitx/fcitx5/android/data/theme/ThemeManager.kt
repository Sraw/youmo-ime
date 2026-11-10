/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.IOException

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    // the built-in themes' files with the system's colours (Monet's): first, the settings' defaults are of them.
    // The packs' system() take them too (ThemePackFiles.systemColors)
    private var builtinThemes: List<Theme.Builtin> = ThemeMonet.systemColors(appContext).let {
        ThemePackFiles.systemColors = it
        BuiltinThemeFiles.load(appContext.resources, it)
    }

    /** The built-in themes, each version of a file one ([BuiltinThemeFiles]): Monet's first. */
    val BuiltinThemes: List<Theme.Builtin> get() = builtinThemes

    /** The built-in theme [ref] names (`pixel#dark`); thrown where none is, a bug: for the app's own refs. */
    fun builtin(ref: String): Theme.Builtin = BuiltinThemeFiles.find(builtinThemes, ref) ?: error("no built-in theme $ref")

    val DefaultTheme: Theme.Builtin get() = builtin("pixel#dark")

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs).also {
        // before a Monet theme's look is made
        ThemeLookMigration.monetLook = it.monetLook()
        // before the keyboard's size is read
        it.migrateSizeFollow()
    }

    // before the themes, which an older format's are read with (the key style settings before 3.0)
    private val customThemes: MutableList<Theme.Custom> = listCustomThemes(prefs).first.toMutableList()

    // the user's theme 4.0 packs, each version one (060-render-p3 T8a); listed after the 3.0 themes, whose
    // listing clears what a kill left of a save first
    private var packs: List<Theme.Pack> = ThemeFilesManager.listPacks(builtinThemes)

    /** The user's theme 4.0 packs, each version one ([Theme.Pack]). */
    val Packs: List<Theme.Pack> get() = packs

    /**
     * The 3.0 themes a pack's import replaces ([ThemeFilesManager.Opened.Pack.customs], recorded before it was
     * placed: [ThemeFilesManager.replacing]), the replacement finished here, on the main thread once the list
     * has the pack ([packPlaced]; a kill after the place, at the next start's listing). A slot naming one bare
     * where the pack has two versions (`mine`) names its version of the same lightness (`mine#dark`); one named
     * as a version keeps the name, which then finds the pack. Then its json and files go, and its record. One
     * whose slots were not written is left, found by its name till the next listing tries again. A 3.0 theme
     * beside a pack of its name no import recorded (a backup restored over the pack) is left be, as listed.
     * The slots written quietly ([finishing]): the change fired once, as the list is. A name no pack lists has its
     * record dropped [atStart] only (the import failed, or a kill before the place): later, an import's place may
     * be under way. Whether a slot was renamed.
     */
    private fun finishReplacements(atStart: Boolean = false): Boolean {
        // one that does not read is kept as it is, tried again at the next listing
        val recorded = runCatching { ThemeFilesManager.replacing() }.getOrElse {
            Timber.w(it, "Themes replaced by packs not read")
            return false
        }
        if (!ThemeFilesManager.writable || recorded.isEmpty()) return false
        var renamed = false
        val done = recorded.filterTo(HashSet()) { name ->
            val custom = customThemes.find { it.name == name }
            val versions = packs.filter { it.name == name || it.file == name }
            // gone already, or no pack placed at the start: nothing to finish
            if (custom == null || (versions.isEmpty() && atStart)) return@filterTo true
            if (versions.isEmpty()) return@filterTo false
            // named bare, the pack of two versions: its version of the same lightness
            val to = if (versions.any { it.name == name }) null else (versions.firstOrNull { it.isDark == custom.isDark } ?: versions.first()).name
            val named = to != null && prefs.namesTheme(name)
            finishing = true
            val written = try {
                !named || prefs.renameThemeRefs(name, to!!)
            } finally {
                finishing = false
            }
            if (!written) Timber.w("Settings naming theme $name not written: replaced by its pack at the next listing")
            if (named && written) renamed = true
            written && replacedDeleted(custom).also { if (it) customThemes.remove(custom) }
        }
        // one not written (a full disk) is done again next time; thrown, every start would crash in onCreate
        runCatching { ThemeFilesManager.replaced(done) }.onFailure { Timber.w(it, "Themes replaced by packs not recorded as done") }
        return renamed
    }

    /** The replacements recorded finished as a process's start does: a test's next start. */
    @VisibleForTesting
    internal fun finishReplacementsAtStart() = finishReplacements(atStart = true)

    // the slots being written by [finishReplacements]: the change fired once the list is, not with the theme replaced
    private var finishing = false

    private fun replacedDeleted(custom: Theme.Custom): Boolean = try {
        ThemeFilesManager.deleteThemeFiles(custom).also { if (!it) Timber.w("Theme ${custom.name} replaced by its pack not deleted") }
    } catch (e: IOException) {
        Timber.w(e, "Theme ${custom.name} replaced by its pack not deleted")
        false
    }

    // whether the mark of a pack that took the last process down was consulted: once a process, once
    // the packs are listed (not while the files are not settled, A4: none to reset, the mark kept till
    // then), before any of them is drawn
    private var crashConsulted = false

    private fun packsListed() {
        if (crashConsulted || !ThemeFilesManager.writable) return
        crashConsulted = true
        PackDrawGuard.crashed()?.let(::packCrashed)
    }

    /** The mark consulted again, as a process's first listing of the packs would: a test's next start. */
    @VisibleForTesting
    internal fun consultPackCrash() {
        crashConsulted = false
        packsListed()
    }

    /**
     * The pack [file] took the keyboard down as it was first drawn ([PackDrawGuard]): the settings naming one
     * of its versions go back to their defaults (a built-in theme), the user told. The pack is kept, to be
     * deleted or picked again.
     */
    @VisibleForTesting
    internal fun packCrashed(file: String) {
        val refs = packs.filter { it.file == file }.map { it.name }.filter { r -> customThemes.none { it.name == r } }
        Timber.w("Theme pack $file did not draw its first frame: the settings naming it reset")
        if (prefs.dropThemeRefs(refs.toSet())) appContext.toast(appContext.getString(R.string.theme_pack_draw_failed, file), Toast.LENGTH_LONG)
    }

    /**
     * The user's themes ([ThemeFilesManager.listThemes]; [ThemePrefs.legacyLook] for an older format's),
     * the settings naming themes made right with them (060-render-p3 T3), as a start does and a refresh
     * again (themes not settled at the start may be since: A4). One named as a built-in theme is now
     * (`monokai`, which 0.5 let in) would hide it: saved again under a UUID, the settings naming it with
     * it ([ThemeFilesManager.moveClashing]); then 0.5's names of the built-in themes in the settings
     * moved ([ThemePrefs.migrateThemeRefs]), not one a theme of the user's may have though it is not
     * listed ([ThemeFilesManager.mayHoldThemes]). With whether a setting was changed.
     */
    @VisibleForTesting
    internal fun listCustomThemes(prefs: ThemePrefs): Pair<List<Theme.Custom>, Boolean> {
        var changed = false
        val themes = ThemeFilesManager.moveClashing(
            ThemeFilesManager.listThemes(prefs.legacyLook()),
            clashes = { BuiltinThemeFiles.find(builtinThemes, it) != null },
            repoint = { from, to -> prefs.renameThemeRefs(from, to).also { changed = changed || it } },
        )
        val customs = themes.mapTo(HashSet()) { it.name }
        customs += ThemeFilesManager.mayHoldThemes(BuiltinThemeFiles.LEGACY.keys)
        if (prefs.migrateThemeRefs(customs)) changed = true
        return themes to changed
    }

    /**
     * The theme [name] refers to: the user's 3.0 theme of that name, else a pack's version ([BuiltinThemeFiles.find]:
     * `name#dark`), else the built-in one, else the built-in one of that 0.5 name (a setting not moved yet:
     * [ThemePrefs.migrateThemeRefs]); null where none is.
     */
    fun getTheme(name: String): Theme? =
        customThemes.find { it.name == name } ?: BuiltinThemeFiles.find(packs, name) ?: BuiltinThemeFiles.find(builtinThemes, name)
            ?: BuiltinThemeFiles.LEGACY[name]?.let { BuiltinThemeFiles.find(builtinThemes, it) }

    /** The user's packs, the user's 3.0 themes, the built-in ones. */
    fun getAllThemes(): List<Theme> = packs + customThemes + builtinThemes

    // Monet's versions read again with the system's colours as they are now (and its keys' look as it is);
    // whether the packs were (the system's colours changed: a configuration's other changes leave them be)
    private fun reloadMonet(): Boolean {
        val colors = ThemeMonet.systemColors(appContext)
        val monet = BuiltinThemeFiles.load(appContext.resources, colors, BuiltinThemeFiles.FILES.filter { it.first == BuiltinThemeFiles.MONET })
        val others = builtinThemes.filter { it.file != BuiltinThemeFiles.MONET }
        // Monet's first, as the files are
        builtinThemes = monet + others
        if (colors == ThemePackFiles.systemColors) return false
        // the packs' system() too: each read again (a copy reads its file anew)
        ThemePackFiles.systemColors = colors
        packs = packs.map { it.copy() }
        return true
    }

    fun refreshThemes() {
        val (themes, changed) = listCustomThemes(prefs)
        customThemes.clear()
        customThemes.addAll(themes)
        packs = ThemeFilesManager.listPacks()
        val renamed = finishReplacements()
        packsListed()
        // the copy for before the first unlock names what this one does (the start syncs it after init)
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

    /** [activeTheme], null before [init] (a test's process that never set one up). */
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
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    // the theme's picture decoded ahead, off the main thread, as wide as the screen (the keyboard's, nearly)
    private fun prefetch() {
        val dm = appContext.resources.displayMetrics
        val landscape = appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ThemeAssets.prefetch(shownLook(_activeTheme), landscape, dm.widthPixels, dm.density)
    }

    // the last look shown and what it was made of: the same object while neither changes, as what is
    // decoded of a look (ThemeAssets) is kept by it. A theme's look is compared as the object it is
    private class Shown(val theme: ThemeLook, val choice: KeyStyleChoice, val look: ThemeLook)

    @Volatile
    private var shown: Shown? = null

    // the key style settings, held: shownLook is asked a few times a key as a keyboard is made
    @Volatile
    private var keyStyle = prefs.keyStyleChoice()

    private val keyStyleKeys = setOf(prefs.keyBorderChoice.key, prefs.keySpacingChoice.key, prefs.keyRadiusChoice.key)

    /**
     * [theme]'s look as the keyboard draws it: the key style settings (大小与样式) over its keys. All the
     * keyboard reads it, also where only colours are read (the same as the theme's), so none is missed
     * and a test can tell. Not for what is the theme's own (its thumbnail, export, editing): [Theme.look].
     */
    fun shownLook(theme: Theme): ThemeLook {
        val choice = keyStyle
        shown?.let { if (it.theme === theme.look && it.choice == choice) return it.look }
        return choice.applyTo(theme.look).also { shown = Shown(theme.look, choice, it) }
    }

    /** Saves [theme]; whether it was, none while the themes are not settled or it could not be written (the user told). */
    fun saveTheme(theme: Theme.Custom): Boolean {
        if (refuseIfNotSettled()) return false
        try {
            ThemeFilesManager.saveThemeFiles(theme)
        } catch (e: IOException) {
            // a full disk, say: its file as it was, the list too. Told in the user's words: the exception's
            // message is a path of the app's files
            Timber.w(e, "Theme ${theme.name} not saved")
            appContext.toast(R.string.theme_not_saved, Toast.LENGTH_LONG)
            return false
        }
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        // its files may be new under the same names (a picture cut again)
        filesChanged(theme.name) {
            if (activeTheme.name == theme.name) {
                activeTheme = theme
            }
        }
        return true
    }

    /**
     * Deletes the theme [name] (a pack's version: the pack, each of its versions); whether it was let, none while
     * the themes are not settled (the user told).
     */
    fun deleteTheme(name: String): Boolean {
        if (refuseIfNotSettled()) return false
        customThemes.find { it.name == name }?.also {
            // its json not deleted: still listed (the next listing reads it), not shown as gone
            if (!ThemeFilesManager.deleteThemeFiles(it)) {
                Timber.w("Theme $name not deleted")
                return false
            }
            customThemes.remove(it)
        }
        val pack = packs.find { it.name == name }?.also { pack ->
            ThemeFilesManager.deletePack(pack.file)
            packs = packs.filter { it.file != pack.file }
        }
        // its picture and thumbnail let go; one imported again under its name is decoded anew
        filesChanged(name) {
            // a pack's other version in use is gone with it
            if (activeTheme.name == name || (pack != null && (activeTheme as? Theme.Pack)?.file == pack.file)) {
                activeTheme = evaluateActiveTheme()
            }
        }
        return true
    }

    /**
     * The theme [name]'s files written or removed (a save, a delete, an import): what was decoded of them
     * let go ([ThemeAssets.clear]: a file may be new under its old name), then [update] (the themes
     * read again, say). The theme in use, if it is [name] and reads as it did (imported again with
     * another picture), is told as changed all the same: the keyboard drawn anew with its new files,
     * not the ones decoded before; else decoded ahead again (told already if [update] changed it).
     */
    fun filesChanged(name: String, update: () -> Unit = {}) {
        ThemeAssets.clear()
        val before = _activeTheme
        update()
        when {
            _activeTheme !== before -> Unit
            before.name == name -> fireChange()
            else -> prefetch()
        }
    }

    /** The pack [file] imported (put in place, a pack of its name replaced): listed again, the keyboard drawn anew if it is in use. */
    fun packPlaced(file: String) {
        filesChanged((_activeTheme as? Theme.Pack)?.takeIf { it.file == file }?.name ?: file) { refreshThemes() }
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = theme
        prefs.normalModeTheme.setValue(theme)
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        // a slot a replacement repoints (finishReplacements): the theme found by it changes once the list does
        if (finishing) return@OnChangeListener
        when (key) {
            in prefs.dayNightModePrefNames -> activeTheme = evaluateActiveTheme()
            // InputView moves the board itself: made anew, the symbols shown would go back to the letters.
            // The side one-handed mode is put at, from wherever (status area, arrows, settings page), is
            // where the status area's toggle opens it again; remembered, it changes nothing shown
            prefs.oneHanded.key -> OneHanded.sideToRemember(prefs.oneHanded.getValue())?.let(prefs.oneHandedSide::setValue)
            prefs.splitDock.key, prefs.oneHandedSide.key -> Unit
            in keyStyleKeys -> {
                keyStyle = prefs.keyStyleChoice()
                fireChange()
            }
            else -> fireChange()
        }
    }

    // the themes not settled (A4): nothing saved or deleted, as it would be lost at the next start; the user told
    fun refuseIfNotSettled() = !ThemeFilesManager.writable.also { if (!it) appContext.toast(R.string.user_data_not_settled) }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        // the app's start, on the main thread (the toast): before the theme in use is read
        finishReplacements(atStart = true)
        packsListed()
        _activeTheme = evaluateActiveTheme()
        settleKeyStyle()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.registerOnChangeListener(onThemePrefsChange)
        prefetch()
    }

    // the key style settings before theme file 3.0 into the themes in use (ThemeLookMigration step 2), once
    // the themes of an older format are saved with them; not while the files are not settled or one was not
    private fun settleKeyStyle() {
        if (!ThemeFilesManager.writable || ThemeFilesManager.rewriteFailed) return
        val slots = listOf(prefs.normalModeTheme, prefs.lightModeTheme, prefs.darkModeTheme).map { p ->
            ThemeLookMigration.Slot(get = p::getValue, set = { p.setValue(it) })
        }
        val tell = prefs.settleLook(slots, ::saveTheme)
        // a Monet theme's look made again with what it keeps
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
        // a pack's read again equals the one before (its file's the same): drawn anew all the same, its system() new
        if (_activeTheme === before && before is Theme.Pack && packsRead) {
            _activeTheme = evaluateActiveTheme()
            fireChange()
            return
        }
        // turned (or another change of the configuration): the picture's cut for it decoded ahead, as
        // the theme picked is; a theme changed did that already
        if (_activeTheme === before) prefetch()
    }

    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            // what the Monet themes' keys are drawn with is no managed preference: before the first unlock too
            val monet = prefs.monetLook()
            if (monet != null) putString(ThemeLookMigration.MONET_KEY, ThemeLookMigration.encode(monet))
            else remove(ThemeLookMigration.MONET_KEY)
            // the sizes' 跟随主题 moved (no managed preference): before the first unlock not moved again
            putBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, prefs.sizeFollowMigrated)
        }
    }

}