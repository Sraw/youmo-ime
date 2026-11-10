/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.rules.KeyNames

/**
 * All a theme draws the keyboard with (theme file 3.0): its colours, its picture, what is under the
 * keyboard and the bar, the keys' shape and faces, single keys' own faces, and the fonts. The app
 * reads and writes it as json, the theme maker on the web the same; files are named, not held: a
 * name is a file in the theme's directory (the app keeps absolute paths in memory, [mapFiles]).
 *
 * Every range is checked as it is made, so a file out of range does not read, as a field it does not
 * know does not: the import fails with the reason, not a keyboard drawn wrong.
 */
data class ThemeLook(
    val palette: Palette,
    val picture: Picture?,
    val base: BoardBase,
    val bar: BarBase,
    val keys: Keys,
    val text: Text,
) {
    init {
        // what shows the picture needs one
        require(picture != null || (base !is BoardBase.Picture && bar !is BarBase.Picture && !keys.showsPicture())) {
            "the picture is shown but there is none"
        }
    }

    /** The files the look names, each once, in the order the json has them. */
    fun files(): List<String> {
        val out = LinkedHashSet<String>()
        picture?.let {
            out += it.src
            out += it.portrait.file
            it.landscape?.let { l -> out += l.file }
        }
        keys.kinds().forEach { k ->
            k.fill.file()?.let(out::add)
            k.pressed.file()?.let(out::add)
        }
        keys.overrides.values.forEach { o ->
            o.fill?.file()?.let(out::add)
            o.pressed?.file()?.let(out::add)
        }
        text.keyFont?.let(out::add)
        text.candidateFont?.let(out::add)
        return out.toList()
    }

    /**
     * The blurs the picture is shown at, dp: under the keyboard, under the bar, in the keys (the kinds' and
     * single keys'); none without a picture. What is decoded ahead when the theme is picked.
     */
    fun pictureBlurs(): Set<Int> {
        val p = picture ?: return emptySet()
        val out = LinkedHashSet<Int>()
        if (base == BoardBase.Picture) out += p.blurDp
        (bar as? BarBase.Picture)?.let { out += it.blurDp }
        keys.kinds().forEach { k -> (k.fill as? KeyFill.Picture)?.let { out += it.blurDp } }
        keys.overrides.values.forEach { o -> (o.fill as? KeyFill.Picture)?.let { out += it.blurDp } }
        return out
    }

    /** The same look with every file name put through [f]: names to paths as read, paths to names as written. */
    fun mapFiles(f: (String) -> String): ThemeLook = copy(
        picture = picture?.let {
            it.copy(
                src = f(it.src),
                portrait = it.portrait.copy(file = f(it.portrait.file)),
                landscape = it.landscape?.let { l -> l.copy(file = f(l.file)) },
            )
        },
        keys = keys.copy(
            letter = keys.letter.mapFiles(f),
            function = keys.function.mapFiles(f),
            space = keys.space.mapFiles(f),
            enter = keys.enter.mapFiles(f),
            overrides = keys.overrides.mapValues { (_, o) ->
                o.copy(fill = o.fill?.mapFile(f), pressed = o.pressed?.mapFile(f))
            },
        ),
        text = text.copy(keyFont = text.keyFont?.let(f), candidateFont = text.candidateFont?.let(f)),
    )
}

/**
 * A theme's colours, 0xAARRGGBB. The first 21 are those of theme file 2.1; the three after them came
 * with 3.0, each what was drawn in its place before: the border in the shadow's colour, a key's
 * corner text and the toolbar's icons in the function keys' text colour.
 */
data class Palette(
    val backgroundColor: Int,
    val barColor: Int,
    val keyboardColor: Int,
    val keyBackgroundColor: Int,
    val keyTextColor: Int,
    val candidateTextColor: Int,
    val candidateLabelColor: Int,
    val candidateCommentColor: Int,
    val altKeyBackgroundColor: Int,
    val altKeyTextColor: Int,
    val accentKeyBackgroundColor: Int,
    val accentKeyTextColor: Int,
    val keyPressHighlightColor: Int,
    val keyShadowColor: Int,
    val popupBackgroundColor: Int,
    val popupTextColor: Int,
    val spaceBarColor: Int,
    val dividerColor: Int,
    val clipboardEntryColor: Int,
    val genericActiveBackgroundColor: Int,
    val genericActiveForegroundColor: Int,
    val keyBorderColor: Int = keyShadowColor,
    val keyHintColor: Int = altKeyTextColor,
    val toolbarIconColor: Int = altKeyTextColor,
)

/** A rectangle of an image, in its pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One orientation's cut of the picture: [file] the cut, [cropRect] where in the source it is (null: the whole), turned by [rotation]. */
data class Crop(val file: String, val cropRect: Box?, val rotation: Int) {
    init {
        require(rotation % 90 == 0 && rotation in 0..270) { "rotation $rotation is not a quarter turn" }
    }
}

/**
 * The theme's picture: [src] as picked, cut once for portrait and once for landscape ([landscape]
 * null: the portrait cut, centre cropped, as before 3.0), at [brightness] percent, blurred [blurDp].
 * [animated]: a theme 4.0's animated `<picture>` (060-render-p3 A4), [src] the animated WebP the keyboard
 * plays, centre cropped, [blurDp] not drawn while it plays; the cuts its still picture. No 3.0 file has one.
 */
data class Picture(
    val src: String,
    val portrait: Crop,
    val landscape: Crop?,
    val brightness: Int,
    val blurDp: Int,
    val animated: Boolean = false,
) {
    init {
        requireBrightness(brightness)
        requireBlur(blurDp)
    }
}

/** Under the whole keyboard, the bar and the keys: the palette's colour, or the picture. */
sealed interface BoardBase {
    data object Color : BoardBase

    data object Picture : BoardBase
}

/** Under the candidates and the toolbar: the keyboard's base seen through, the palette's bar colour, or the picture's part there. */
sealed interface BarBase {
    data object Transparent : BarBase

    data object Color : BarBase

    data class Picture(val blurDp: Int, val brightness: Int) : BarBase {
        init {
            requireBlur(blurDp)
            requireBrightness(brightness)
        }
    }
}

/**
 * An image stretched to any size: its [insets] (source pixels, left top right bottom) are corners
 * and edges kept as they are, the middle stretched; all four 0, the whole image stretched. [pxPerDp]
 * is how many of its pixels make a dp, the scale its corners are drawn at.
 */
data class NineSlice(val file: String, val insets: Insets, val pxPerDp: Int) {
    init {
        require(pxPerDp in 1..MAX_PX_PER_DP) { "pxPerDp $pxPerDp out of 1..$MAX_PX_PER_DP" }
    }

    companion object {
        const val MAX_PX_PER_DP = 8

        /** A key image's longest side, px, as the app takes one in (ThemeAssetLimits): no inset is past it. */
        const val MAX_SIDE = 1024
    }
}

data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) { "negative insets" }
    }

    /** No corners: the image stretched whole. */
    val isZero get() = left == 0 && top == 0 && right == 0 && bottom == 0
}

/** A key's face: its kind's colour from the palette, an image, or the picture's part where the key is. */
sealed interface KeyFill {
    data object Color : KeyFill

    data class Image(val slice: NineSlice) : KeyFill

    data class Picture(val blurDp: Int, val brightness: Int) : KeyFill {
        init {
            requireBlur(blurDp)
            requireBrightness(brightness)
        }
    }
}

/** A key's face while pressed: its own face darkened (the palette's press highlight over it), a colour, an image. */
sealed interface KeyPressed {
    data object Darken : KeyPressed

    data class Color(val argb: Int) : KeyPressed

    data class Image(val slice: NineSlice) : KeyPressed
}

/** The four kinds of keys a theme styles apart. */
enum class KeyKind { Letter, Function, Space, Enter }

data class KindStyle(val fill: KeyFill, val pressed: KeyPressed) {
    internal fun mapFiles(f: (String) -> String) = copy(fill = fill.mapFile(f), pressed = pressed.mapFile(f))
}

enum class PressEffect { Tint, Ripple }

enum class KeyLabel { Show, Hide }

/** One key's own face over its kind's: what is null is its kind's; [label] Hide when the image has the text already. */
data class KeyOverride(val fill: KeyFill?, val pressed: KeyPressed?, val label: KeyLabel)

/**
 * The room round each key, dp, portrait and landscape: inside the key's cell, so a key is pressed where its gap is too.
 * At the keyboard height 紧凑 the room above and below is [vCompact] and [vLandCompact] (the user, 2026-11-06: the
 * rows closer, the keys taller in the same height); across, the keyboard is as wide as ever, so it is the same.
 */
data class Gap(val h: Int, val v: Int, val hLand: Int, val vLand: Int) {
    init {
        require(listOf(h, v, hLand, vLand).all { it in 0..MAX_GAP_DP }) { "gap out of 0..$MAX_GAP_DP" }
    }

    /** [v] at 紧凑: [compact]. */
    val vCompact: Int get() = compact(v)

    /** [vLand] at 紧凑: [compact]. */
    val vLandCompact: Int get() = compact(vLand)

    companion object {
        const val MAX_GAP_DP = 24

        /**
         * What of the room above and below a key is left at 紧凑, percent. One share for any gap, the theme's or
         * the setting's, so a looser theme or level stays looser there too. 40 makes the default 7 dp 3 (as
         * wide as the 3 dp across), keys some 8 dp taller on a phone upright; chosen from emulator shots of
         * 70, 55, 40 and 25 (design 060-key-spacing).
         */
        const val COMPACT_V_PERCENT = 40

        private const val PERCENT = 100

        /** [dp] at 紧凑: [COMPACT_V_PERCENT] of it, to the nearest dp (a half up). */
        fun compact(dp: Int): Int = (dp * COMPACT_V_PERCENT + PERCENT / 2) / PERCENT
    }
}

/**
 * The keys: [blocks] drawn as blocks, or flat (no face; the space a bar, Return a circle); the
 * shape; [press] how a press shows; each kind's style; [overrides] single keys' by name ([KeyNames.split]:
 * `qwerty/q`, `*`/backspace). A name of a board or key this build does not know is kept, not used.
 */
data class Keys(
    val blocks: Boolean,
    val radiusDp: Int,
    val borderDp: Int,
    val shadowDp: Int,
    val gap: Gap,
    val press: PressEffect,
    val letter: KindStyle,
    val function: KindStyle,
    val space: KindStyle,
    val enter: KindStyle,
    val overrides: Map<String, KeyOverride>,
) {
    init {
        require(radiusDp in 0..MAX_RADIUS_DP) { "radiusDp $radiusDp out of 0..$MAX_RADIUS_DP" }
        require(borderDp in 0..MAX_BORDER_DP) { "borderDp $borderDp out of 0..$MAX_BORDER_DP" }
        require(shadowDp in 0..MAX_SHADOW_DP) { "shadowDp $shadowDp out of 0..$MAX_SHADOW_DP" }
        overrides.keys.forEach { KeyNames.split(it) }
    }

    fun kind(kind: KeyKind) = when (kind) {
        KeyKind.Letter -> letter
        KeyKind.Function -> function
        KeyKind.Space -> space
        KeyKind.Enter -> enter
    }

    fun kinds() = listOf(letter, function, space, enter)

    internal fun showsPicture() =
        kinds().any { it.fill is KeyFill.Picture } || overrides.values.any { it.fill is KeyFill.Picture }

    companion object {
        const val MAX_RADIUS_DP = 24
        const val MAX_BORDER_DP = 3
        const val MAX_SHADOW_DP = 3

        /** The single keys a theme may style at most (an import refuses more: the app's ThemeAssetLimits). */
        const val MAX_OVERRIDES = 400
    }
}

/**
 * The fonts (file names; null the system's), their weights (300, 400, 700) and the keys' text size,
 * percent of each key's own.
 */
data class Text(
    val keyFont: String?,
    val candidateFont: String?,
    val keyWeight: Int,
    val candidateWeight: Int,
    val keyScale: Int,
) {
    init {
        require(keyWeight in WEIGHTS && candidateWeight in WEIGHTS) { "a weight not in $WEIGHTS" }
        require(keyScale in MIN_SCALE..MAX_SCALE) { "keyScale $keyScale out of $MIN_SCALE..$MAX_SCALE" }
    }

    companion object {
        val WEIGHTS = listOf(300, 400, 700)
        const val MIN_SCALE = 85
        const val MAX_SCALE = 130
    }
}

private const val MAX_BLUR_DP = 25

private fun requireBlur(blurDp: Int) = require(blurDp in 0..MAX_BLUR_DP) { "blurDp $blurDp out of 0..$MAX_BLUR_DP" }

private fun requireBrightness(brightness: Int) = require(brightness in 0..100) { "brightness $brightness out of 0..100" }

private fun KeyFill.file() = (this as? KeyFill.Image)?.slice?.file

private fun KeyPressed.file() = (this as? KeyPressed.Image)?.slice?.file

private fun KeyFill.mapFile(f: (String) -> String) =
    if (this is KeyFill.Image) KeyFill.Image(slice.copy(file = f(slice.file))) else this

private fun KeyPressed.mapFile(f: (String) -> String) =
    if (this is KeyPressed.Image) KeyPressed.Image(slice.copy(file = f(slice.file))) else this
