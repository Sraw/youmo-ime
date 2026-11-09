/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A theme as theme-4.xsd writes it (`https://youmo-ime/theme/4`), its colour variables resolved
 * ([colors]: every one a colour, none naming one that is not there or itself).
 *
 * It has a light version, a dark one or both ([variants], `true` the dark); [dark] and [colors] are
 * those of the one it is drawn as: its only one, or the light one of two until [variant] picks.
 */
class Theme(
    val name: String,
    val dark: Boolean,
    val version: String,
    val colors: Map<String, Argb>,
    val styles: List<Style>,
    val fonts: Map<String, String>,
    val icons: Map<String, String>,
    val picture: Picture?,
    val variants: Set<Boolean> = setOf(dark),
    /** `<colors>` resolved: the light version's, or the only one's. */
    val baseColors: Map<String, Argb> = colors,
    /**
     * The dark version's colours where it has one: `<colors>` with what `<colors variant="dark">` gives
     * instead, resolved again with the dark `system()` roles; null where it has no dark version.
     */
    val darkColors: Map<String, Argb>? = null,
    val meta: Meta? = null,
    /** The system's colours it was read with: a style's `system()` takes [dark]'s. */
    val systemColors: SystemColors = SystemColors.NONE,
) {
    // once, not a function object each colour
    private val variable: (String) -> Argb? = colors::get
    private val system: (String) -> Argb? = systemColors.of(dark)::get

    /** [expr]'s colour here; transparent where it names what is not there (checked on reading). */
    fun color(expr: ColorExpr): Argb = expr.resolve(variable, system) ?: Argb.TRANSPARENT

    /** This theme drawn as its dark or light version; itself where it has only the other. */
    fun variant(dark: Boolean): Theme = if (dark == this.dark || dark !in variants) {
        this
    } else {
        val drawn = if (dark) darkColors ?: baseColors else baseColors
        Theme(name, dark, version, drawn, styles, fonts, icons, picture, variants, baseColors, darkColors, meta, systemColors)
    }
}

/**
 * What `system(name, fallback)` takes, by name ([ColorExpr.SYSTEM_NAMES]): Material's roles differ
 * between the light and the dark ([light], [dark]: Android's `system_primary_light`, `…_dark`); the
 * palette's tones are the same in both, so the caller puts them in each.
 */
data class SystemColors(val light: Map<String, Argb>, val dark: Map<String, Argb>) {
    fun of(dark: Boolean): Map<String, Argb> = if (dark) this.dark else light

    companion object {
        /** None: every `system()` its fallback (an older Android, the web). */
        val NONE = SystemColors(emptyMap(), emptyMap())
    }
}

/** A `<style>`: its selectors, its properties as read, and where it is (later wins a tie). */
class Style(val selectors: List<Selector>, val values: Map<String, Value>, val order: Int, val line: Int)

class Picture(val src: String, val brightness: Double, val blurDp: Double, val cuts: Map<String, String>)

/** `<meta>`: who made it, which of theirs, its licence, the oldest app it is for (`0.6.0`), its preview picture. */
data class Meta(val author: String?, val version: String?, val license: String?, val minApp: String?, val preview: String?)
