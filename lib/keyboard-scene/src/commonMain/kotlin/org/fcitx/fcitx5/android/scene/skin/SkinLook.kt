/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.theme.SoundKey

/**
 * A skin format the converter reads ([BaiduSkin], [SogouSkin]): what it says of itself, and each of its versions
 * as a [SkinLook]. A new format is one more of these and a line in [SkinConverter.READERS]; the theme is written
 * from the looks alone ([SkinTheme]).
 */
interface SkinSource {
    /** The skin's own name and author, as it gives them (cleaned for a theme by [SkinTheme]). */
    val name: String
    val author: String

    /** Its preview picture's bytes, or null. */
    val preview: ByteArray?

    /** The versions it has: [Variant.LIGHT], [Variant.DARK] or both. */
    val variants: Set<Variant>

    /** One version of it in theme terms. */
    fun look(variant: Variant): SkinLook
}

/** A theme's two versions. */
enum class Variant(val word: String) { LIGHT("light"), DARK("dark") }

/** How a face is filled: a colour, or an image drawn as a nine-patch of [insets] (none: stretched whole). */
sealed interface SkinFill {
    data class Color(val argb: Int) : SkinFill

    class Image(val pixels: Pixels, val insets: Pixels.Box?) : SkinFill
}

/**
 * A kind of key's face: [fill] and [pressed], its label's [color] and [pressedColor]; [icons] that its icons take the
 * colour too; [drawn] that its face draws its own label or symbol (none of the app's drawn over it).
 */
class SkinKey(
    val fill: SkinFill?,
    val pressed: SkinFill?,
    var color: Int?,
    val pressedColor: Int?,
    val icons: Boolean,
    val drawn: Boolean = false,
)

/**
 * One version of a skin in theme terms, filled by a [SkinSource]: [width] is the skin's pixels across the keyboard,
 * which make its dp ([SkinTheme]: 360 dp across). [keys] are by the theme selector they become (`key`,
 * `key.function`, `key[role=backspace]`, `qwerty/q`), [inset] the gap around a key (up and down, across) in dp.
 */
class SkinLook(val width: Int) {
    /** The whole input method's background, the bar's part on top ([barHeight] of its pixels), or null. */
    var board: Pixels? = null
    var barHeight = 0
    var boardColor: Int? = null
    var barColor: Int? = null
    val keys = LinkedHashMap<String, SkinKey>()
    var inset: Pair<Int, Int>? = null
    var candidate: Int? = null
    var first: Int? = null
    var preedit: Int? = null
    var hintFill: SkinFill? = null
    var hintText: Int? = null
    val sounds = LinkedHashMap<SoundKey, Pair<String, ByteArray>>()
    var font: Pair<String, ByteArray>? = null

    /** The keys' corner radius in dp; null: square, as skins whose images draw their own shape. */
    var radius: Double? = null
    val notes = LinkedHashSet<SkinNote>()
}

/** What a skin had that its theme does not: told the user as the import asks ([SkinConversion.notes]). */
enum class SkinNote {
    /** The keys' positions and sizes, and boards the app lays out otherwise (18 keys): the app's own layouts. */
    LAYOUT,

    /** Layers drawn over the board that are no key (stickers, shadows, frames). */
    DECORATION,

    /** Key and background animations. */
    ANIMATION,

    /** Per-style text sizes and fonts (Baidu's `font.json`, sizes in the styles). */
    TEXT_SIZE,

    /** The toolbar's and the candidate bar's own icons. */
    TOOLBAR_ICONS,

    /** Letters drawn as pictures: only their colour is taken. */
    LETTER_IMAGES,

    /** A key sound that is no format or length a theme takes. */
    SOUND,

    /** The dark version's background picture: a theme has one picture, the light one's. */
    DARK_PICTURE,

    /** Periods of the day past day and night (Sogou's third and later). */
    MORE_PERIODS,

    /** An image that did not decode, was past the limits or is not in the file. */
    IMAGES,

    /** Values a theme takes from other files (Trime's `__include`, `__patch`): only its own file's are read. */
    INCLUDES,

    /** More themes or colour schemes in the file than the one light and one dark taken. */
    MORE_THEMES,

    /** The styles of panels and modes the app draws its own way (clipboard, emoji, one-handed, floating, Material You). */
    PANELS,
}

/** Why a file is no skin the app converts: [reason], with [detail] (a file's name) where one says more. */
class SkinError(val reason: Reason, val detail: String = "", cause: Throwable? = null) : Exception("$reason $detail".trim(), cause) {
    enum class Reason {
        /** Not a ZIP, or one broken past reading. */
        NOT_ZIP,

        /** A ZIP of no format the app knows. */
        UNKNOWN,

        /** Past the size of a skin file ([SkinLimits.MAX_ARCHIVE_BYTES]). */
        TOO_BIG,

        /** Past [SkinLimits.MAX_ENTRIES] files. */
        TOO_MANY_FILES,

        /** A file past [SkinLimits.MAX_ENTRY_BYTES] unpacked, all past [SkinLimits.MAX_TOTAL_BYTES], or packed past [SkinLimits.MAX_RATIO]. */
        UNPACKED_TOO_BIG,

        /** A file encrypted, or packed in a way not read. */
        ENCRYPTED,

        /** A file's name a path out of the skin (`../`, absolute). */
        UNSAFE_NAME,

        /** Sogou's encrypted container (`Skin` header): computer skins. */
        SOGOU_ENCRYPTED,

        /** A Sogou skin for the computer: a candidate window, no keyboard. */
        SOGOU_PC,

        /** A skin whose keyboard was not found (no 26-key board, no key faces). */
        NO_KEYBOARD,

        /** A skin's file that reads as nothing it should be (a converter's check that failed). */
        BROKEN,
    }
}

/** The bounds the converter holds a skin to: the file, its unpacked files, an image decoded. */
object SkinLimits {
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    /** Unpacked to packed, for a file past [RATIO_FROM] bytes: past it, a zip bomb. */
    const val MAX_RATIO = 200
    const val RATIO_FROM = 1024L * 1024

    /** An image's pixels, checked before decoding: a skin's largest atlas is 2215×2561 (5.7 M). */
    const val MAX_IMAGE_PIXELS = 12L * 1024 * 1024

    /** A text file read (INI, CSS, TIL): past it, read as empty. */
    const val MAX_TEXT_BYTES = 1024 * 1024
}
