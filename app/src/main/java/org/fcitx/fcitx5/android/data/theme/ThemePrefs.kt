/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
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
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleMigration
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitDock
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.utils.appContext

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

    // as the preferences declare them: a copy here would drift
    private fun defaults(vararg prefs: ManagedPreference.PInt) = prefs.map { it.key to it.defaultValue }

    init {
        val keyboard = AppPrefs.getInstance().keyboard
        section(R.string.section_size)
        levels(
            R.string.keyboard_height, "keyboard_height_level",
            defaults(keyboard.keyboardHeightPercent, keyboard.keyboardHeightPercentLandscape),
            listOf(R.string.level_compact, R.string.level_low, R.string.level_standard, R.string.level_tall, R.string.level_taller)
                .also { require(it.size == KeyboardHeightLevels.ALL.size) { "a label for each keyboard height level" } }
                .zip(KeyboardHeightLevels.ALL) { label, l -> Level(label, l.portrait, l.landscapeLevel) },
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

    val sizeFollowMigrated: Boolean get() = sharedPreferences.getBoolean(SIZES_FOLLOW_MIGRATED, false)

    /**
     * Once: each size at an older version's default follows the theme. Also marked where a 跟随主题 is stored (only this
     * version writes one), so an older copy is not migrated again.
     */
    fun migrateSizeFollow() {
        if (sizeFollowMigrated) return
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

    val keyboardStyleMigrated: Boolean get() = runCatching { sharedPreferences.getBoolean(KEYBOARD_STYLE_MIGRATED, false) }.getOrDefault(false)

    /** Once; the old keys stay for a backup restored into 0.6.0. Done on the pre-unlock copy too: 0.6.0's has the old keys only. */
    fun migrateKeyboardStyle() {
        if (keyboardStyleMigrated) return
        fun string(key: String) = runCatching { sharedPreferences.getString(key, null) }.getOrNull()
        val split = when (string(SPLIT_KEYBOARD_060)) {
            "Always" -> KeyboardStyleMigration.Split060.ALWAYS
            "Off" -> KeyboardStyleMigration.Split060.OFF
            else -> KeyboardStyleMigration.Split060.AUTO
        }
        val side = OneHanded.entries.firstOrNull { it.name == string(ONE_HANDED_060) }
        val floating = runCatching { sharedPreferences.getBoolean(FLOATING_KEYBOARD_060, false) }.getOrDefault(false)
        val migrated = KeyboardStyleMigration.from060(split, oneHanded = side != null, floating)
        // none stored (new install, 0.6.0's defaults): nothing written
        val any = listOf(SPLIT_KEYBOARD_060, ONE_HANDED_060, FLOATING_KEYBOARD_060).any(sharedPreferences::contains)
        sharedPreferences.edit {
            fun put(style: StyleKey, under: StyleKey, stored: KeyboardStyleMigration.Stored) {
                if (!any || sharedPreferences.contains(style.key) || sharedPreferences.contains(under.key)) return
                stored.style?.let { putString(style.key, it.name) }
                stored.under?.let { putString(under.key, it.name) }
            }
            put(keyboardStylePortrait, keyboardStyleUnderPortrait, migrated.portrait)
            put(keyboardStyleLandscape, keyboardStyleUnderLandscape, migrated.landscape)
            // 0.6.0 kept it as it changed; older ones may not have
            side?.let { putString(oneHandedSide.key, it.name) }
            putBoolean(KEYBOARD_STYLE_MIGRATED, true)
        }
    }

    class StyleKey(sharedPreferences: SharedPreferences, key: String) : ManagedPreference<String>(sharedPreferences, key, "") {
        override fun setValue(value: String) = sharedPreferences.edit { if (value.isEmpty()) remove(key) else putString(key, value) }

        override fun getValue(): String = runCatching { sharedPreferences.getString(key, null) }.getOrNull().orEmpty()

        // unstored stays unstored in the pre-unlock copy: its default depends on the width there
        override fun putValueTo(editor: SharedPreferences.Editor) {
            val value = getValue()
            if (value.isEmpty()) editor.remove(key) else editor.putString(key, value)
        }
    }

    // floating, the keyboard is lower with keys keeping their height
    val codeRow = enumList(
        R.string.code_row_style, "code_row_style", CodeRowStyle.Floating,
        confirm = ManagedPreferenceUi.Confirm(CodeRowStyle.InField, R.string.code_row_in_field, R.string.code_row_in_field_note),
    )

    // the bar grows, the keys keep their height; the floating window has its own size
    val candidateTextSize = int(
        R.string.candidate_text_size, "candidate_text_size",
        CandidateBarHeight.DEFAULT_TEXT_SP, 14, 28, "sp"
    )

    /** Unstored means the shape's default by its width, so only the user, the toggles and the migration write one. */
    val keyboardStylePortrait = StyleKey(sharedPreferences, "keyboard_style_portrait").apply { register() }
    val keyboardStyleLandscape = StyleKey(sharedPreferences, "keyboard_style_landscape").apply { register() }
    val keyboardStyleUnderPortrait = StyleKey(sharedPreferences, "keyboard_style_portrait_under").apply { register() }
    val keyboardStyleUnderLandscape = StyleKey(sharedPreferences, "keyboard_style_landscape_under").apply { register() }

    init {
        ManagedPreferenceUi.Choice(
            R.string.keyboard_style_portrait, "keyboard_style_portrait_list", KeyboardStyles.LABELS,
            current = { KeyboardStyles.shown(it, landscape = false).ordinal },
            pick = { ctx, i -> KeyboardStyles.set(ctx, landscape = false, KeyboardStyle.entries[i]) },
            watched = listOf(keyboardStylePortrait, keyboardStyleUnderPortrait),
        ).registerUi()
        ManagedPreferenceUi.Choice(
            R.string.keyboard_style_landscape, "keyboard_style_landscape_list", KeyboardStyles.LABELS,
            current = { KeyboardStyles.shown(it, landscape = true).ordinal },
            pick = { ctx, i -> KeyboardStyles.set(ctx, landscape = true, KeyboardStyle.entries[i]) },
            watched = listOf(keyboardStyleLandscape, keyboardStyleUnderLandscape),
        ).registerUi()
    }

    // read by the input view as it changes, not remade
    val splitDock = enumList(R.string.split_dock, "split_dock", SplitDock.Right) {
        KeyboardStyles.splitDockOffered(appContext)
    }

    // app state, not a setting: no UI, and not `hidden`, which a backup import resets
    val oneHandedSide = ManagedPreference.PStringLike(
        sharedPreferences,
        "one_handed_side",
        OneHanded.Right,
        ManagedPreference.enumCodec<OneHanded>(),
    ).apply { register() }

    val floatingHandle = enumList(R.string.floating_handle, "floating_handle", FloatingHandleSide.Bottom)

    // keys keep their height unless the app's 120 dp binds (KeyboardHeightCap)
    val alwaysShowHandle = switch(R.string.always_show_floating_handle, "always_show_floating_handle", false)

    init {
        section(R.string.section_key_style)
    }

    // following is the default: what 0.5.0 set is in the theme already, so an upgrade looks the same
    val keyBorderChoice = enumList(R.string.key_border, "key_border_choice", KeyBorderChoice.FollowTheme)
    val keyHSpacingChoice = enumList(R.string.key_h_spacing, KEY_H_SPACING, KeyHSpacingChoice.FollowTheme)
    val keyVSpacingChoice = enumList(R.string.key_v_spacing, KEY_V_SPACING, KeyVSpacingChoice.FollowTheme)
    val keyRadiusChoice = enumList(R.string.key_radius, "key_radius_choice", KeyRadiusChoice.FollowTheme)

    init {
        note(R.string.key_style_follow_theme, "key_style_note")
    }

    fun keyStyleChoice() = KeyStyleChoice(
        keyBorderChoice.getValue().blocks,
        keyHSpacingChoice.getValue().spacing,
        keyVSpacingChoice.getValue().spacing,
        keyRadiusChoice.getValue().radiusDp,
    )

    /** One already stored is kept; a non-level value (damaged backup) is following. The old key is dropped anyway. */
    fun migrateKeySpacing() {
        if (!sharedPreferences.contains(KEY_SPACING_052)) return
        // stored as another type: read as unset, never a crash at every start
        val old = runCatching { sharedPreferences.getString(KEY_SPACING_052, null) }.getOrNull()
        val level = KeyHSpacingChoice.entries.firstOrNull { it.name == old } ?: KeyHSpacingChoice.FollowTheme
        sharedPreferences.edit {
            if (!sharedPreferences.contains(KEY_H_SPACING)) putString(KEY_H_SPACING, level.name)
            if (!sharedPreferences.contains(KEY_V_SPACING)) putString(KEY_V_SPACING, vertical(level).name)
            remove(KEY_SPACING_052)
        }
    }

    // each named, so a renamed or added level fails to compile rather than throw at a start
    private fun vertical(level: KeyHSpacingChoice): KeyVSpacingChoice = when (level) {
        KeyHSpacingChoice.FollowTheme -> KeyVSpacingChoice.FollowTheme
        KeyHSpacingChoice.Compact -> KeyVSpacingChoice.Compact
        KeyHSpacingChoice.Standard -> KeyVSpacingChoice.Standard
        KeyHSpacingChoice.Loose -> KeyVSpacingChoice.Loose
    }

    enum class KeyBorderChoice(override val stringRes: Int, val blocks: Boolean?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        On(R.string.key_border_on, true),
        Off(R.string.key_border_off, false),
    }

    enum class KeyHSpacingChoice(override val stringRes: Int, val spacing: KeyStyleChoice.Spacing?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        Compact(R.string.level_compact, KeyStyleChoice.H_COMPACT),
        Standard(R.string.level_standard, KeyStyleChoice.H_STANDARD),
        Loose(R.string.level_loose, KeyStyleChoice.H_LOOSE),
    }

    /** Stored by name, so an added level keeps the others' meaning; a version without [VeryCompact] reads it as following. */
    enum class KeyVSpacingChoice(override val stringRes: Int, val spacing: KeyStyleChoice.Spacing?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        VeryCompact(R.string.level_very_compact, KeyStyleChoice.V_VERY_COMPACT),
        Compact(R.string.level_compact, KeyStyleChoice.V_COMPACT),
        Standard(R.string.level_standard, KeyStyleChoice.V_STANDARD),
        Loose(R.string.level_loose, KeyStyleChoice.V_LOOSE),
    }

    enum class KeyRadiusChoice(override val stringRes: Int, val radiusDp: Int?) : ManagedPreferenceEnum {
        FollowTheme(R.string.follow_theme, null),
        Square(R.string.level_square, KeyStyleChoice.RADIUS_SQUARE),
        Rounded(R.string.level_rounded, KeyStyleChoice.RADIUS_ROUNDED),
        Round(R.string.level_round, KeyStyleChoice.RADIUS_ROUND),
    }

    fun legacyLook() = ThemeLookMigration.legacy(sharedPreferences)

    fun monetLook() = ThemeLookMigration.monet(sharedPreferences)

    fun settleLook(slots: List<ThemeLookMigration.Slot>, save: (Theme.Custom) -> Boolean) =
        ThemeLookMigration.settle(sharedPreferences, slots, save)

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
     * A slot naming a built-in by its 0.5 name names it as it is now; one naming a possible user theme is left. No mark:
     * no old name is written any more, so old backups and the pre-unlock copy are moved as read.
     */
    fun migrateThemeRefs(customs: Set<String>): Boolean {
        val moved = themeSlots.mapNotNull { key ->
            // a non-string value (damaged file) is no name: left, read as the default
            val name = slotValue(key) ?: return@mapNotNull null
            BuiltinThemeFiles.moved(name)?.takeIf { name !in customs }?.let { key to it }
        }
        if (moved.isNotEmpty()) sharedPreferences.edit { moved.forEach { (key, ref) -> putString(key, ref) } }
        return moved.isNotEmpty()
    }

    private val themeSlots get() = listOf(normalModeTheme.key, lightModeTheme.key, darkModeTheme.key)

    private fun slotValue(key: String): String? = runCatching { sharedPreferences.getString(key, null) }.getOrNull()

    fun namesTheme(name: String): Boolean = themeSlots.any { slotValue(it) == name }

    /** On disk before it returns, as the old theme's json is deleted next. */
    fun renameThemeRefs(from: String, to: String): Boolean {
        val keys = themeSlots.filter { slotValue(it) == from }
        if (keys.isEmpty()) return true
        return sharedPreferences.edit().apply { keys.forEach { putString(it, to) } }.commit()
    }

    fun dropThemeRefs(refs: Set<String>): Boolean {
        val keys = themeSlots.filter { slotValue(it) in refs }
        if (keys.isNotEmpty()) sharedPreferences.edit { keys.forEach(::remove) }
        return keys.isNotEmpty()
    }

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )

    companion object {
        const val SIZES_FOLLOW_MIGRATED = "keyboard_sizes_follow_migrated"

        const val KEYBOARD_STYLE_MIGRATED = "keyboard_style_migrated"

        /** Kept for 0.6.0. */
        const val SPLIT_KEYBOARD_060 = "split_keyboard"
        const val ONE_HANDED_060 = "one_handed"
        const val FLOATING_KEYBOARD_060 = "floating_keyboard"

        const val KEY_SPACING_052 = "key_spacing_choice"
        const val KEY_H_SPACING = "key_h_spacing_choice"
        const val KEY_V_SPACING = "key_v_spacing_choice"
    }
}
