/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.theme.SoundKey

/** A skin format the converter reads; a new format is one more of these and a line in [SkinConverter.READERS]. */
interface SkinSource {
    val name: String
    val author: String

    val preview: ByteArray?

    val variants: Set<Variant>

    fun look(variant: Variant): SkinLook
}

enum class Variant(val word: String) { LIGHT("light"), DARK("dark") }

sealed interface SkinFill {
    data class Color(val argb: Int) : SkinFill

    class Image(val pixels: Pixels, val insets: Pixels.Box?) : SkinFill
}

/** [drawn]: its face draws its own label or symbol (none of the app's drawn over it). */
class SkinKey(
    val fill: SkinFill?,
    val pressed: SkinFill?,
    val color: Int?,
    val pressedColor: Int?,
    val icons: Boolean,
    val drawn: Boolean = false,
)

/** [width] is the skin's pixels across the keyboard, which make its dp ([SkinTheme]: 360 dp across). */
class SkinLook(val width: Int) {
    var board: Pixels? = null
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

    var radius: Double? = null
    val notes = LinkedHashSet<SkinNote>()
}

/** Told the user as the import asks ([SkinConversion.notes]). */
enum class SkinNote {
    LAYOUT,

    DECORATION,

    ANIMATION,

    TEXT_SIZE,

    TOOLBAR_ICONS,

    LETTER_IMAGES,

    SOUND,

    /** A theme has one picture, the light one's. */
    DARK_PICTURE,

    MORE_PERIODS,

    IMAGES,

    INCLUDES,

    MORE_THEMES,

    PANELS,
}

class SkinError(val reason: Reason, val detail: String = "", cause: Throwable? = null) : Exception("$reason $detail".trim(), cause) {
    enum class Reason {
        NOT_ZIP,

        UNKNOWN,

        TOO_BIG,

        TOO_MANY_FILES,

        UNPACKED_TOO_BIG,

        ENCRYPTED,

        UNSAFE_NAME,

        SOGOU_ENCRYPTED,

        SOGOU_PC,

        NO_KEYBOARD,

        BROKEN,
    }
}

/** All of it is in memory at once, in the input method's process. */
object SkinLimits {
    const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    const val MAX_RATIO = 200
    const val RATIO_FROM = 1024L * 1024

    const val MAX_IMAGE_PIXELS = 12L * 1024 * 1024

    const val MAX_KEPT_PIXELS = MAX_IMAGE_PIXELS

    const val MAX_TEXT_BYTES = 1024 * 1024

    /** A CJK font is often 10–30 MB; half a pack's 32 MB a file leaves room for the pictures. */
    const val MAX_FONT_BYTES = 16 * 1024 * 1024
}
