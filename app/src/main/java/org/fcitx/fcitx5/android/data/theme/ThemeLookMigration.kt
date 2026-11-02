/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.input.theme.LegacyLook
import java.util.UUID

/**
 * The key style settings before theme file 3.0 (global: borders, corners, gaps…) into the themes,
 * in two steps, one rule: a theme of an older format gets the old settings as its keys.
 *
 * 1. As the themes are read ([ThemeFilesManager.listThemes] with [legacy]): each of an older format
 *    is saved as 3.0 with them. One of 3.0 is never changed, so an old backup restored (the old
 *    settings come back with it) leaves the themes made since as they are.
 * 2. [settle], once the keys are drawn from the themes: a built-in or Monet theme in use is copied
 *    with them and the copy put in its place, then the settings are removed.
 */
object ThemeLookMigration {

    /** Every key of the old settings, the hidden radii and the levels' too. */
    val KEYS = listOf(
        "key_border", "key_border_stroke", "key_ripple_effect", "key_radius", "key_radius_level",
        "key_horizontal_margin", "key_horizontal_margin_landscape", "key_vertical_margin",
        "key_vertical_margin_landscape", "key_spacing_level", "text_editing_button_radius", "clipboard_entry_radius",
    )

    /** The old settings as [prefs] has them; null if none of them is there (a new install, or settled). */
    fun legacy(prefs: SharedPreferences): LegacyLook? {
        if (KEYS.none(prefs::contains)) return null
        val d = LegacyLook()
        return LegacyLook(
            keyBorder = prefs.getBoolean("key_border", d.keyBorder),
            keyBorderStroke = prefs.getBoolean("key_border_stroke", d.keyBorderStroke),
            keyRippleEffect = prefs.getBoolean("key_ripple_effect", d.keyRippleEffect),
            keyRadius = prefs.getInt("key_radius", d.keyRadius),
            hMargin = prefs.getInt("key_horizontal_margin", d.hMargin),
            hMarginLandscape = prefs.getInt("key_horizontal_margin_landscape", d.hMarginLandscape),
            vMargin = prefs.getInt("key_vertical_margin", d.vMargin),
            vMarginLandscape = prefs.getInt("key_vertical_margin_landscape", d.vMarginLandscape),
        )
    }

    /** A theme in use: light, dark or the one chosen, read and set. */
    class Slot(val get: () -> Theme, val set: (Theme.Custom) -> Unit)

    /**
     * Step 2: each theme in [slots] that is not the user's own (built in, Monet) is copied with the
     * old settings as its keys, the copy [save]d and put in the slot (one copy a theme, however many
     * slots have it); then the old settings are removed. True if the user is to be told, once: the
     * settings were not the defaults. Run twice, the second finds nothing to do.
     */
    fun settle(prefs: SharedPreferences, slots: List<Slot>, save: (Theme.Custom) -> Unit): Boolean {
        val legacy = legacy(prefs) ?: return false
        if (!legacy.isDefault) {
            val copies = HashMap<String, Theme.Custom>()
            for (slot in slots) {
                val theme = slot.get()
                if (theme is Theme.Custom) continue
                val copy = copies.getOrPut(theme.name) {
                    Theme.Custom(UUID.randomUUID().toString(), theme.isDark, legacy.applyTo(theme.look)).also(save)
                }
                slot.set(copy)
            }
        }
        prefs.edit { KEYS.forEach(::remove) }
        return !legacy.isDefault
    }
}
