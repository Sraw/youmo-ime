/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/** A theme as theme-4.xsd writes it, its colour variables resolved; [dark] and [colors] those of the version it is drawn as. */
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
    val baseColors: Map<String, Argb> = colors,
    /** Resolved again with the dark `system()` roles; null where it has no dark version. */
    val darkColors: Map<String, Argb>? = null,
    val meta: Meta? = null,
    val systemColors: SystemColors = SystemColors.NONE,
    /** A key this version does not know too: its file is kept and [ThemeWriter] writes them back as they are. */
    val soundFiles: List<Pair<String, String>> = emptyList(),
    /** `<picture variant="dark">`, drawn by the dark version in place of [picture]. */
    val darkPicture: Picture? = null,
    /** Raster icons whose alpha alone is drawn, in `icon-color` (`tint="true"`). */
    val tintedIcons: Set<String> = emptySet(),
) {
    /** The picture this version draws. */
    val shownPicture: Picture? get() = if (dark) darkPicture ?: picture else picture

    val sounds: Map<SoundKey, String> by lazy { soundFiles.mapNotNull { (k, f) -> SoundKey.of(k)?.let { it to f } }.toMap() }

    // once, not a function object each colour
    private val variable: (String) -> Argb? = colors::get
    private val system: (String) -> Argb? = systemColors.of(dark)::get

    fun color(expr: ColorExpr): Argb = expr.resolve(variable, system) ?: Argb.TRANSPARENT

    /** The later style's where two do. */
    val keyboardSizes: KeyboardSizes by lazy {
        fun last(name: String) = styles.lastOrNull { it.values[name] is Value.OfTwin }?.let { (it.values[name] as Value.OfTwin).twin }
        KeyboardSizes(last("height"), last("side-padding"), last("bottom-padding"))
    }

    /** Itself where it has only the other. */
    fun variant(dark: Boolean): Theme = if (dark == this.dark || dark !in variants) {
        this
    } else {
        val drawn = if (dark) darkColors ?: baseColors else baseColors
        Theme(name, dark, version, drawn, styles, fonts, icons, picture, variants, baseColors, darkColors, meta, systemColors, soundFiles, darkPicture, tintedIcons)
    }

    /** Every file it draws named through [path] (a pack's directory), the rest as it is; one place, so a field added is not dropped. */
    fun withPaths(path: (String) -> String): Theme {
        fun value(prop: String, v: Value): Value {
            val effect = (v as? Value.OfEffect)?.effect
            val image = (v as? Value.OfFill)?.fill as? Fill.Image
            return when {
                effect is Effect.Ripple -> Value.OfEffect(effect.copy(file = path(effect.file)))
                effect is Effect.Particles -> Value.OfEffect(effect.copy(file = path(effect.file)))
                image != null -> Value.OfFill(image.copy(file = path(image.file)))
                prop == "sticker" && v is Value.Word && v.word != Values.NONE -> Value.Word(path(v.word))
                else -> v
            }
        }
        fun Picture.at() = Picture(path(src), brightness, blurDp, cuts.mapValues { path(it.value) }, animated)
        val styles = styles.map { s -> Style(s.selectors, s.values.mapValues { (k, v) -> value(k, v) }, s.order, s.line) }
        return Theme(
            name, dark, version, colors, styles, fonts.mapValues { path(it.value) }, icons.mapValues { path(it.value) }, picture?.at(), variants,
            baseColors, darkColors, meta, systemColors, soundFiles.map { (key, file) -> key to path(file) }, darkPicture?.at(), tintedIcons,
        )
    }
}

/** Material's roles differ between light and dark; the palette's tones are the same in both, so the caller puts them in each. */
data class SystemColors(val light: Map<String, Argb>, val dark: Map<String, Argb>) {
    fun of(dark: Boolean): Map<String, Argb> = if (dark) this.dark else light

    companion object {
        val NONE = SystemColors(emptyMap(), emptyMap())
    }
}

data class KeyboardSizes(val height: Twin? = null, val sidePadding: Twin? = null, val bottomPadding: Twin? = null)

class Style(val selectors: List<Selector>, val values: Map<String, Value>, val order: Int, val line: Int)

/** [animated]: an animated WebP the keyboard plays (its cuts the still one), not blurred. */
class Picture(val src: String, val brightness: Double, val blurDp: Double, val cuts: Map<String, String>, val animated: Boolean = false)

data class Meta(val author: String?, val version: String?, val license: String?, val minApp: String?, val preview: String?)
