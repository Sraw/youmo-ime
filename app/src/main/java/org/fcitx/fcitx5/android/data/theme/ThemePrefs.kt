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
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level

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

    // the size of the keyboard: here, with the preview over it, rather than with the keys'
    // behavior; AppPrefs.Keyboard holds the values, for the input view reads them there
    init {
        section(R.string.section_size)
        levels(
            R.string.keyboard_height, "keyboard_height_level",
            listOf("keyboard_height_percent" to 30, "keyboard_height_percent_landscape" to 49),
            listOf(
                Level(R.string.level_low, 26, 42),
                Level(R.string.level_standard, 30, 49),
                Level(R.string.level_tall, 34, 55),
                Level(R.string.level_taller, 38, 60),
            )
        )
        levels(
            R.string.keyboard_side_padding, "keyboard_side_padding_level",
            listOf("keyboard_side_padding" to 0, "keyboard_side_padding_landscape" to 0),
            listOf(
                Level(R.string.level_none, 0, 0),
                Level(R.string.level_narrow, 8, 48),
                Level(R.string.level_wide, 24, 120),
            )
        )
        levels(
            R.string.keyboard_bottom_padding, "keyboard_bottom_padding_level",
            listOf("keyboard_bottom_padding" to 0, "keyboard_bottom_padding_landscape" to 0),
            listOf(
                Level(R.string.level_none, 0, 0),
                Level(R.string.level_little, 8, 4),
                Level(R.string.level_more, 20, 12),
            )
        )
        section(R.string.section_keys)
    }

    // keys drawn apart: borderless, one letter was hard to tell from the next
    val keyBorder = switch(R.string.key_border, "key_border", true)

    val keyBorderStroke = hidden {
        switch(
            R.string.key_border_stroke, "key_border_stroke", false,
            enableUiOn = { keyBorder.getValue() }
        )
    }

    val keyRippleEffect = hidden { switch(R.string.key_ripple_effect, "key_ripple_effect", false) }

    val keyHorizontalMargin: ManagedPreference.PInt
    val keyHorizontalMarginLandscape: ManagedPreference.PInt
    val keyVerticalMargin: ManagedPreference.PInt
    val keyVerticalMarginLandscape: ManagedPreference.PInt

    init {
        val (horizontal, horizontalLandscape) = hidden {
            twinInt(
                R.string.key_horizontal_margin,
                R.string.portrait,
                "key_horizontal_margin",
                3,
                R.string.landscape,
                "key_horizontal_margin_landscape",
                3,
                0,
                24,
                "dp"
            )
        }
        keyHorizontalMargin = horizontal
        keyHorizontalMarginLandscape = horizontalLandscape
        val (vertical, verticalLandscape) = hidden {
            twinInt(
                R.string.key_vertical_margin,
                R.string.portrait,
                "key_vertical_margin",
                7,
                R.string.landscape,
                "key_vertical_margin_landscape",
                4,
                0,
                24,
                "dp"
            )
        }
        keyVerticalMargin = vertical
        keyVerticalMarginLandscape = verticalLandscape
        levels(
            R.string.key_spacing, "key_spacing_level",
            listOf(
                "key_horizontal_margin" to 3, "key_horizontal_margin_landscape" to 3,
                "key_vertical_margin" to 7, "key_vertical_margin_landscape" to 4,
            ),
            listOf(
                Level(R.string.level_compact, 2, 2, 5, 3),
                Level(R.string.level_standard, 3, 3, 7, 4),
                Level(R.string.level_loose, 5, 5, 9, 6),
            )
        )
    }

    val keyRadius = hidden { int(R.string.key_radius, "key_radius", 4, 0, 48, "dp") }

    init {
        levels(
            R.string.key_radius, "key_radius_level",
            listOf("key_radius" to 4),
            listOf(
                Level(R.string.level_square, 0),
                Level(R.string.level_rounded, 4),
                Level(R.string.level_round, 10),
            )
        )
    }

    val textEditingButtonRadius =
        hidden { int(R.string.text_editing_button_radius, "text_editing_button_radius", 8, 0, 48, "dp") }

    val clipboardEntryRadius =
        hidden { int(R.string.clipboard_entry_radius, "clipboard_entry_radius", 2, 0, 48, "dp") }

    enum class PunctuationPosition(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.punctuation_pos_none),
        Bottom(R.string.punctuation_pos_bottom),
        TopRight(R.string.punctuation_pos_top_right);
    }

    val punctuationPosition = enumList(
        R.string.punctuation_position,
        "punctuation_position",
        PunctuationPosition.Bottom
    )

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = hidden {
        enumList(
        R.string.navbar_background,
        "navbar_background",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) NavbarBackground.Full else NavbarBackground.ColorOnly,
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
        if (BuildConfig.DEBUG) ThemePreset.MaterialLight else ThemePreset.PixelLight,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        if (BuildConfig.DEBUG) ThemePreset.MaterialDark else ThemePreset.PixelDark,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )
}
