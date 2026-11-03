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
 * 2. [settle], once the keys are drawn from the themes: a built-in theme in use is copied with them
 *    and the copy put in its place, then the settings are removed. A Monet theme in use is not copied
 *    (a copy's colours would stop following the wallpaper): the settings are kept for the Monet
 *    themes' keys ([monet]).
 */
object ThemeLookMigration {

    /** Every key of the old settings, the hidden radii and the levels' too. */
    val KEYS = listOf(
        "key_border", "key_border_stroke", "key_ripple_effect", "key_radius", "key_radius_level",
        "key_horizontal_margin", "key_horizontal_margin_landscape", "key_vertical_margin",
        "key_vertical_margin_landscape", "key_spacing_level", "text_editing_button_radius", "clipboard_entry_radius",
    )

    /**
     * The old settings as [prefs] has them; null if none of them is there (a new install, or settled).
     * Those the old settings page did not show (the border's stroke, the ripple, the two radii of the
     * text editing and clipboard pages) are the defaults whatever [prefs] has: no user could change
     * them, so a backup's values say nothing of what the user wanted. Removed with the rest.
     */
    fun legacy(prefs: SharedPreferences): LegacyLook? {
        if (KEYS.none(prefs::contains)) return null
        val d = LegacyLook()
        return LegacyLook(
            keyBorder = prefs.getBoolean("key_border", d.keyBorder),
            keyRadius = prefs.getInt("key_radius", d.keyRadius),
            hMargin = prefs.getInt("key_horizontal_margin", d.hMargin),
            hMarginLandscape = prefs.getInt("key_horizontal_margin_landscape", d.hMarginLandscape),
            vMargin = prefs.getInt("key_vertical_margin", d.vMargin),
            vMarginLandscape = prefs.getInt("key_vertical_margin_landscape", d.vMarginLandscape),
        )
    }

    /** Where the old settings are kept for the Monet themes' keys, once settled: their values joined. */
    const val MONET_KEY = "monet_key_style"

    /** The old settings the Monet themes are drawn with, [settle]d into [prefs]; null for none (the defaults). */
    fun monet(prefs: SharedPreferences): LegacyLook? = prefs.getString(MONET_KEY, null)?.let(::decode)

    /**
     * What the Monet themes' keys are drawn with, read as the themes are ([ThemeManager]): their look is
     * made from the wallpaper's colours, not read from a file. Set before a Monet theme's look is first asked.
     */
    @Volatile
    var monetLook: LegacyLook? = null

    internal fun encode(l: LegacyLook) = listOf(
        l.keyBorder, l.keyBorderStroke, l.keyRippleEffect, l.keyRadius,
        l.hMargin, l.hMarginLandscape, l.vMargin, l.vMarginLandscape,
    ).joinToString(",")

    internal fun decode(text: String): LegacyLook? {
        val v = text.split(',')
        @Suppress("MagicNumber")
        if (v.size != 8) return null
        val b = v.take(3).map { it.toBooleanStrictOrNull() ?: return null }
        val n = v.drop(3).map { it.toIntOrNull() ?: return null }
        return LegacyLook(b[0], b[1], b[2], n[0], n[1], n[2], n[3], n[4])
    }

    /** A theme in use: light, dark or the one chosen, read and set. */
    class Slot(val get: () -> Theme, val set: (Theme.Custom) -> Unit)

    /**
     * Step 2: each built-in theme in [slots] is copied with the old settings as its keys, the copy
     * [save]d and put in the slot (one copy a theme, however many slots have it); a Monet theme in one
     * keeps them for its keys ([MONET_KEY]). Then the old settings are removed, but if a copy could
     * not be saved: kept, to be tried at the next start. True if the user is to be told, once: the
     * settings were not the defaults and are settled. Run twice, the second finds nothing to do.
     */
    fun settle(prefs: SharedPreferences, slots: List<Slot>, save: (Theme.Custom) -> Boolean): Boolean {
        val legacy = legacy(prefs) ?: return false
        var failed = false
        var monet = false
        if (!legacy.isDefault) {
            // one try a theme, a failed one too (null): getOrPut would try a null again, a second copy saved
            val copies = HashMap<String, Theme.Custom?>()
            for (slot in slots) {
                when (val theme = slot.get()) {
                    is Theme.Custom -> Unit
                    is Theme.Monet -> monet = true
                    else -> {
                        if (theme.name !in copies) {
                            copies[theme.name] =
                                Theme.Custom(UUID.randomUUID().toString(), theme.isDark, legacy.applyTo(theme.look)).takeIf(save)
                        }
                        val copy = copies[theme.name]
                        if (copy == null) failed = true else slot.set(copy)
                    }
                }
            }
        }
        if (monet) {
            prefs.edit { putString(MONET_KEY, encode(legacy)) }
            monetLook = legacy
        }
        if (failed) return false
        prefs.edit { KEYS.forEach(::remove) }
        return !legacy.isDefault
    }
}
