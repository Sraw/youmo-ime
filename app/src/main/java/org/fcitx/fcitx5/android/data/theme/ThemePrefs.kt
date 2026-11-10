/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.floating.FloatingHandleSide
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightLevels
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitDock
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.look.Gap

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Theme,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        val ui = ManagedThemePreferenceUi(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    // the keys and defaults the preferences declare: a copy here would drift when one changes
    private fun defaults(vararg prefs: ManagedPreference.PInt) = prefs.map { it.key to it.defaultValue }

    // the size of the keyboard: here, with the preview over it, rather than with the keys'
    // behavior; AppPrefs.Keyboard holds the values, for the input view reads them there
    init {
        val keyboard = AppPrefs.getInstance().keyboard
        section(R.string.section_size)
        levels(
            R.string.keyboard_height, "keyboard_height_level",
            defaults(keyboard.keyboardHeightPercent, keyboard.keyboardHeightPercentLandscape),
            // ime-core's, which the keyboard reads 紧凑 by
            listOf(R.string.level_compact, R.string.level_low, R.string.level_standard, R.string.level_tall, R.string.level_taller)
                // a level added or taken away in ime-core is a label to add or take away here, not one cut off
                .also { require(it.size == KeyboardHeightLevels.ALL.size) { "a label for each keyboard height level" } }
                .zip(KeyboardHeightLevels.ALL) { label, l -> Level(label, l.portrait, l.landscape) },
            follow = keyboard.keyboardHeightFollowTheme,
        )
        levels(
            R.string.keyboard_side_padding, "keyboard_side_padding_level",
            defaults(keyboard.keyboardSidePadding, keyboard.keyboardSidePaddingLandscape),
            listOf(
                Level(R.string.level_none, 0, 0),
                Level(R.string.level_narrow, 8, 48),
                Level(R.string.level_wide, 24, 120),
            ),
            follow = keyboard.keyboardSidePaddingFollowTheme,
        )
        levels(
            R.string.keyboard_bottom_padding, "keyboard_bottom_padding_level",
            defaults(keyboard.keyboardBottomPadding, keyboard.keyboardBottomPaddingLandscape, keyboard.keyboardBottomFlush),
            listOf(
                Level(R.string.level_flush, 0, 0, 1),
                Level(R.string.level_none, 0, 0, 0),
                Level(R.string.level_little, 8, 4, 0),
                Level(R.string.level_more, 20, 12, 0),
            ),
            follow = keyboard.keyboardBottomPaddingFollowTheme,
        )
    }

    /** [migrateSizeFollow] done: copied with the managed preferences. */
    val sizeFollowMigrated: Boolean get() = sharedPreferences.getBoolean(SIZES_FOLLOW_MIGRATED, false)

    /**
     * Once (060-render-p3 T5): each size an older version had at its default follows the theme, one
     * set otherwise stays as it is, not following; [SIZES_FOLLOW_MIGRATED] marks it done. A backup of
     * an older version brought back has no mark, its sizes their own: done again at the next start.
     * The mark is a key of no preference, so the hidden settings' reset leaves it; it is copied for
     * before the first unlock (ThemeManager.syncToDeviceEncryptedStorage). Done too where a 跟随主题 is
     * stored: only this version writes one, so a copy of before the mark was copied is not moved again
     * (a default level picked there would follow the theme). A number not an int (a damaged file) is
     * taken as set: kept, not following.
     */
    fun migrateSizeFollow() {
        if (sizeFollowMigrated) return
        // each list with its 跟随主题 flag's key
        val lists = managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Levels>().mapNotNull { l -> l.follow?.let { l to it.first } }
        val stored = lists.any { (_, flag) -> sharedPreferences.contains(flag) }
        sharedPreferences.edit {
            if (!stored) lists.forEach { (list, flag) ->
                val set = list.keys.any { (name, default) ->
                    runCatching { sharedPreferences.getInt(name, default) }.getOrNull() != default
                }
                putBoolean(flag, !set)
            }
            putBoolean(SIZES_FOLLOW_MIGRATED, true)
        }
    }

    // the code being typed over the app, outside the keyboard (FloatingCodeStrip), or in it above the
    // candidates as up to 0.5.0; the keyboard is lower floating, the keys keeping their height. For
    // everyone, upgrades too (the user, 2026-11-01). The view is made anew for it
    val codeRow = enumList(R.string.code_row_style, "code_row_style", CodeRowStyle.Floating)

    // the on-screen bar's words, its 联想 and the expanded grid; the bar grows with it, the keys
    // keep their height (CandidateBarHeight). The floating window has its own candidates_window_font_size
    val candidateTextSize = int(
        R.string.candidate_text_size, "candidate_text_size",
        CandidateBarHeight.DEFAULT_TEXT_SP, 14, 28, "sp"
    )

    // the letters in two halves, for the thumbs, on a phone on its side (TextKeyboard.splits)
    val splitKeyboard = enumList(R.string.split_keyboard, "split_keyboard", SplitKeyboardMode.Auto)

    // the other boards at a side while it splits; read by the input view as it changes, not made anew
    val splitDock = enumList(R.string.split_dock, "split_dock", SplitDock.Right) {
        splitKeyboard.getValue() != SplitKeyboardMode.Off
    }

    // the whole keyboard at a side for one thumb while it does not split; also from the status area,
    // and read by the input view as it changes, as the dock is; floating, it does nothing
    val oneHanded = enumList(R.string.one_handed, "one_handed", OneHanded.Off) {
        !floatingKeyboard.getValue()
    }

    // the side 单手模式 was last at, which turning it on from the status area goes back to: a
    // left-handed user would otherwise move it across every time
    val oneHandedSide = hidden { enumList(R.string.one_handed, "one_handed_side", OneHanded.Right) }

    // the keyboard over the app, smaller, dragged anywhere (FloatingKeyboard); also from the status
    // area. The view is made anew for it: the letters do not split floating
    val floatingKeyboard = switch(R.string.floating_keyboard, "floating_keyboard", false)

    // where its drag handle is: made anew for it too. Set also while it does not float, for the next time
    val floatingHandle = enumList(R.string.floating_handle, "floating_handle", FloatingHandleSide.Bottom)

    // the handle on the docked keyboard too, where floatingHandle puts it, added to the keyboard (the
    // keys keep their height unless the 70% cap binds, the user 2026-10-22);
    // its grip long pressed lets the keyboard go floating under the finger. Made anew for it
    val alwaysShowHandle = switch(R.string.always_show_floating_handle, "always_show_floating_handle", false)

    init {
        section(R.string.section_key_style)
    }

    // 0.5.0's three, each over the theme's or following it (the user, 2026-11-04); new keys, as
    // ThemeLookMigration reads 0.5.0's to tell whether to move them into the theme. Following is the
    // default: what 0.5.0 had set is in the theme in use already, so an upgrade looks as it did
    val keyBorderChoice = enumList(R.string.key_border, "key_border_choice", KeyBorderChoice.FollowTheme)
    val keySpacingChoice = enumList(R.string.key_spacing, "key_spacing_choice", KeySpacingChoice.FollowTheme)
    val keyRadiusChoice = enumList(R.string.key_radius, "key_radius_choice", KeyRadiusChoice.FollowTheme)

    init {
        note(R.string.key_style_follow_theme, "key_style_note")
    }

    /** What the three set over a theme's keys ([ThemeManager.shownLook]). */
    fun keyStyleChoice() = KeyStyleChoice(
        keyBorderChoice.getValue().blocks,
        keySpacingChoice.getValue().gap,
        keyRadiusChoice.getValue().radiusDp,
    )

    enum class KeyBorderChoice(override val stringRes: Int, val blocks: Boolean?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        On(R.string.key_border_on, true),
        Off(R.string.key_border_off, false),
    }

    enum class KeySpacingChoice(override val stringRes: Int, val gap: Gap?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        Compact(R.string.level_compact, KeyStyleChoice.GAP_COMPACT),
        Standard(R.string.level_standard, KeyStyleChoice.GAP_STANDARD),
        Loose(R.string.level_loose, KeyStyleChoice.GAP_LOOSE),
    }

    enum class KeyRadiusChoice(override val stringRes: Int, val radiusDp: Int?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        Square(R.string.level_square, KeyStyleChoice.RADIUS_SQUARE),
        Rounded(R.string.level_rounded, KeyStyleChoice.RADIUS_ROUNDED),
        Round(R.string.level_round, KeyStyleChoice.RADIUS_ROUND),
    }

    /** The key style settings as they were before theme file 3.0, while they are in the file ([ThemeLookMigration]). */
    fun legacyLook() = ThemeLookMigration.legacy(sharedPreferences)

    /** The old settings kept for the Monet themes' keys ([ThemeLookMigration.monet]). */
    fun monetLook() = ThemeLookMigration.monet(sharedPreferences)

    /** [ThemeLookMigration.settle] on these settings. */
    fun settleLook(slots: List<ThemeLookMigration.Slot>, save: (Theme.Custom) -> Boolean) =
        ThemeLookMigration.settle(sharedPreferences, slots, save)

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = hidden {
        enumList(
            R.string.navbar_background,
            "navbar_background",
            NavbarBackground.Full,
            // 35+ forces edge to edge
            enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM }
        )
    }.apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sharedPreferences.edit {
                remove(this@apply.key)
            }
        }
    }

    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    init {
        section(R.string.section_day_night)
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        R.string.light_mode_theme,
        "light_mode_theme",
        ThemeManager.builtin(if (BuildConfig.DEBUG) "material#light" else "pixel#light"),
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        ThemeManager.builtin(if (BuildConfig.DEBUG) "material#dark" else "pixel#dark"),
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    /**
     * 060-render-p3 T3: a slot ([normalModeTheme], [lightModeTheme], [darkModeTheme]) naming a built-in theme
     * by its 0.5 name (`PixelDark`, `MonetLight`) names it as it is now (`pixel#dark`, `monet#light`,
     * [BuiltinThemeFiles.LEGACY]): what each showed it shows. One naming a theme of the user's
     * ([customs]: the listed ones' names, and any a json not listed or a directory not read may hold,
     * [ThemeFilesManager.mayHoldThemes]) is left, which it was before as well (the user's first); till it
     * is moved, [ThemeManager.getTheme] reads an old name as the built-in theme. Run at every start and
     * refresh, no mark:
     * no old name is written any more (an import of one is refused), so a backup of an older version
     * brought back, and the copy for before the first unlock (written by an older version, read with
     * this one's), are moved as they are read. A name of no theme (one deleted) is left: the default.
     * Whether a slot was moved.
     */
    fun migrateThemeRefs(customs: Set<String>): Boolean {
        val moved = listOf(normalModeTheme.key, lightModeTheme.key, darkModeTheme.key).mapNotNull { key ->
            // a value not a string (a damaged file) is no name: left, read as the default
            val name = runCatching { sharedPreferences.getString(key, null) }.getOrNull() ?: return@mapNotNull null
            BuiltinThemeFiles.LEGACY[name]?.takeIf { name !in customs }?.let { key to it }
        }
        if (moved.isNotEmpty()) sharedPreferences.edit { moved.forEach { (key, ref) -> putString(key, ref) } }
        return moved.isNotEmpty()
    }

    /**
     * Each slot naming the user's theme [from] names [to] (060-render-p3 T3: one hiding a built-in theme
     * saved again under a new name, [ThemeFilesManager.moveClashing]); on disk before it returns, as the
     * old theme's json is deleted next. Whether it was written.
     */
    /** Whether a slot names the theme [name]. */
    fun namesTheme(name: String): Boolean = listOf(normalModeTheme.key, lightModeTheme.key, darkModeTheme.key)
        .any { runCatching { sharedPreferences.getString(it, null) }.getOrNull() == name }

    fun renameThemeRefs(from: String, to: String): Boolean {
        val keys = listOf(normalModeTheme.key, lightModeTheme.key, darkModeTheme.key)
            .filter { runCatching { sharedPreferences.getString(it, null) }.getOrNull() == from }
        if (keys.isEmpty()) return true
        return sharedPreferences.edit().apply { keys.forEach { putString(it, to) } }.commit()
    }

    /** The slots naming one of [refs] back to their defaults ([PackDrawGuard]): whether any was. */
    fun dropThemeRefs(refs: Set<String>): Boolean {
        val keys = listOf(normalModeTheme.key, lightModeTheme.key, darkModeTheme.key)
            .filter { runCatching { sharedPreferences.getString(it, null) }.getOrNull() in refs }
        if (keys.isNotEmpty()) sharedPreferences.edit { keys.forEach(::remove) }
        return keys.isNotEmpty()
    }

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )

    companion object {
        /** [migrateSizeFollow] done. */
        const val SIZES_FOLLOW_MIGRATED = "keyboard_sizes_follow_migrated"
    }
}
