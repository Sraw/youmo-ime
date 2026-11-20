/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.scene.look.LegacyLook
import java.util.UUID

/**
 * Moves the pre-3.0 global key style settings into the themes: a theme of an older format gets them as its keys.
 * Listing saves each older theme as 3.0 with them ([legacy]); a 3.0 theme is never changed, so an old backup's
 * settings leave newer themes be. Then [settle] copies a built-in theme in use and removes the settings; a Monet theme
 * is not copied (it would stop following the wallpaper), so the settings are kept for it ([monet]).
 */
object ThemeLookMigration {

    /** The hidden radii and the levels' too. */
    val KEYS = listOf(
        "key_border", "key_border_stroke", "key_ripple_effect", "key_radius", "key_radius_level",
        "key_horizontal_margin", "key_horizontal_margin_landscape", "key_vertical_margin",
        "key_vertical_margin_landscape", "key_spacing_level", "text_editing_button_radius", "clipboard_entry_radius",
    )

    /**
     * Null if none is there (a new install, or settled). Those the old page did not show (border stroke, ripple, two
     * radii) are defaults whatever [prefs] has: no user could have set them.
     */
    fun legacy(prefs: SharedPreferences): LegacyLook? {
        if (KEYS.none(prefs::contains)) return null
        val d = LegacyLook()
        // no longer managed preferences: a wrong type (bad backup, hand edit) is the default, not a crash at every start
        fun bool(key: String, default: Boolean) = runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)
        fun int(key: String, default: Int) = runCatching { prefs.getInt(key, default) }.getOrDefault(default)
        return LegacyLook(
            keyBorder = bool("key_border", d.keyBorder),
            keyRadius = int("key_radius", d.keyRadius),
            hMargin = int("key_horizontal_margin", d.hMargin),
            hMarginLandscape = int("key_horizontal_margin_landscape", d.hMarginLandscape),
            vMargin = int("key_vertical_margin", d.vMargin),
            vMarginLandscape = int("key_vertical_margin_landscape", d.vMarginLandscape),
        )
    }

    /** The old settings kept for the Monet themes' keys once settled, values joined. */
    const val MONET_KEY = "monet_key_style"

    /** Null for none (the defaults). */
    fun monet(prefs: SharedPreferences): LegacyLook? =
        runCatching { prefs.getString(MONET_KEY, null) }.getOrNull()?.let(::decode)

    /** Monet looks are made from the wallpaper, not read from a file. Set before a Monet theme's look is first asked. */
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
        return LegacyLook(
            keyBorder = b[0], keyBorderStroke = b[1], keyRippleEffect = b[2], keyRadius = n[0],
            hMargin = n[1], hMarginLandscape = n[2], vMargin = n[3], vMarginLandscape = n[4],
        )
    }

    /** Light, dark or the one chosen. */
    class Slot(val get: () -> Theme, val set: (Theme.Custom) -> Unit)

    /**
     * One copy per built-in theme however many slots have it; a Monet one keeps them ([MONET_KEY]). The settings are removed
     * unless a copy could not be saved (retried next start). True if the user is to be told once. Idempotent.
     */
    fun settle(prefs: SharedPreferences, slots: List<Slot>, save: (Theme.Custom) -> Boolean): Boolean {
        val legacy = legacy(prefs) ?: return false
        var failed = false
        var monet = false
        if (!legacy.isDefault) {
            // a failed try is kept as null: getOrPut would retry it and save a second copy
            val copies = HashMap<String, Theme.Custom?>()
            for (slot in slots) {
                val theme = slot.get()
                when {
                    theme is Theme.Custom -> Unit
                    // a 4.0 pack's keys are its file's
                    theme is Theme.Pack -> Unit
                    theme is Theme.Builtin && theme.followsSystem -> monet = true
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
