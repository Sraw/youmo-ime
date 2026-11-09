/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.Keep
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

    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    val DefaultTheme = ThemePreset.PixelDark

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs).also {
        // before a Monet theme's look is made
        ThemeLookMigration.monetLook = it.monetLook()
    }

    private var monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())

    // before the themes, which an older format's are read with (the key style settings before 3.0)
    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes(prefs.legacyLook())

    fun getTheme(name: String) =
        customThemes.find { it.name == name } ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + monetThemes + BuiltinThemes

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes(prefs.legacyLook()))
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

    /** Deletes the theme [name]; whether it was let, none while the themes are not settled (the user told). */
    fun deleteTheme(name: String): Boolean {
        if (refuseIfNotSettled()) return false
        customThemes.find { it.name == name }?.also {
            ThemeFilesManager.deleteThemeFiles(it)
            customThemes.remove(it)
        }
        // its picture and thumbnail let go; one imported again under its name is decoded anew
        filesChanged(name) {
            if (activeTheme.name == name) {
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
        monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
        _activeTheme = evaluateActiveTheme()
        if (tell) appContext.toast(R.string.key_style_moved, Toast.LENGTH_LONG)
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        isDarkMode = newConfig.isDarkMode()
        monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
        // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
        // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
        val before = _activeTheme
        activeTheme = evaluateActiveTheme()
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
        }
    }

}