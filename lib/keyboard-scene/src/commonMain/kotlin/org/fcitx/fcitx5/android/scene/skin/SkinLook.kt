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

/**
 * [drawn]: its face draws its own label or symbol (none of the app's drawn over it). [scale] is a text size in percent,
 * [weight] 300 or 700, [pressScale] a percent, [transitionMs] its time, [pressSink] in dp: [SkinTheme] clamps each to what a
 * theme takes, and leaves out a weight other than those or a sink that is not a number.
 */
class SkinKey(
    val fill: SkinFill?,
    val pressed: SkinFill?,
    val color: Int?,
    val pressedColor: Int?,
    val icons: Boolean,
    val drawn: Boolean = false,
    val pressedIconColor: Int? = null,
    val markColor: Int? = null,
    val pressedMarkColor: Int? = null,
    val scale: Int? = null,
    val weight: Int? = null,
    val pressScale: Int? = null,
    val transitionMs: Int? = null,
    val pressSink: Double? = null,
)

/** An icon as the skin draws it, at rest and [pressed] (null: the same). */
class SkinIcon(val normal: Pixels, val pressed: Pixels?)

/** A keyboard's keys as the skin places them, in [width] across (the units of [SkinKeyBox]); its id one of [SkinLayoutWriter.IDS]. */
class SkinBoard(val id: String, val rows: List<List<SkinKeyBox>>, val width: Double)

/** A key's touch rectangle; a board with one not finite is not written (only its layout is left out). */
class SkinKeyBox(val x: Double, val y: Double, val w: Double, val h: Double, val action: SkinAction)

/**
 * What a key does, matched against the app's layout. A letter key, a digit key of `nine` (`Letter('1')`) and a grouped key of
 * `k18`/`k14` (any one of its group's letters) are a [Letter].
 */
sealed interface SkinAction {
    data class Letter(val char: Char) : SkinAction

    /**
     * A layout's `role=` (`backspace`, `shift`, `enter`, `space`, `numbers`, `symbols`, `lang`) or a key's id (`emoji`,
     * `comma`, `period`, `reset`, `zero`, …, as in the app's own `layouts/`).
     */
    data class Role(val name: String) : SkinAction

    /** Drawn only (a decoration). */
    data object None : SkinAction
}

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

    private val iconsByName = LinkedHashMap<String, SkinIcon>()
    private val letters = LinkedHashMap<Char, SkinIcon>()

    /** By the app's icon names ([SkinIcons.NAMES]), through [icon]; only the first version's are read. */
    val icons: Map<String, SkinIcon> get() = iconsByName

    /** Drawn as stickers on 26 keys' letters, through [letterImage]; only the first version's are read. */
    val letterImages: Map<Char, SkinIcon> get() = letters

    /**
     * The first version's own dark board, for a skin of two versions (a second version's board is its dark one anyway);
     * a skin with one version has no dark one to put it in. Only the first version's is read.
     */
    var darkPicture: Pixels? = null

    /** Only the first version's are read. */
    val layouts = ArrayList<SkinBoard>()

    /** In the order seen; a note's arguments, or none. */
    val notes = LinkedHashMap<SkinNote, List<String>>()

    /** Kept made small ([SkinIcons.fit]) where [name] is one of the app's; another only told of ([SkinNote.KEY_ICONS]). */
    fun icon(name: String, normal: Pixels, pressed: Pixels?) {
        if (name in SkinIcons.NAMES) iconsByName[name] = SkinIcon(SkinIcons.fit(normal), pressed?.let { SkinIcons.fit(it) }) else notes += SkinNote.KEY_ICONS
    }

    /** Kept made small, for a letter of a to z (either case, the first given); another left out. */
    fun letterImage(c: Char, normal: Pixels, pressed: Pixels?) {
        val l = c.lowercaseChar().takeIf { it in 'a'..'z' && it !in letters } ?: return
        letters[l] = SkinIcon(SkinIcons.fit(normal, SkinTheme.MAX_STICKER_SIDE), pressed?.let { SkinIcons.fit(it, SkinTheme.MAX_STICKER_SIDE) })
    }
}

/** A note with no arguments, or [SkinNote]'s arguments kept as first given. */
operator fun MutableMap<SkinNote, List<String>>.plusAssign(note: SkinNote) {
    getOrPut(note) { emptyList() }
}

/** Told the user as the import asks ([SkinConversion.notes]). */
enum class SkinNote {
    /** No keyboard's keys carried over. */
    LAYOUT,

    /** Some did not: the arguments are their ids ([SkinLayoutWriter.IDS]). */
    LAYOUT_SOME,

    /** Keys the app does not have left out of keyboards written (a number or tool row): the arguments are their ids. */
    LAYOUT_EXTRA,

    /** What a keyboard written lacks of the app's, the skin not having it: each argument a board's id and a `lacks` finding's arguments, tab-separated. */
    LAYOUT_LACKS,

    DECORATION,

    ANIMATION,

    KEYBOARD_ANIMATION,

    FRAME_ANIMATION,

    TEXT_SIZE,

    FONT,

    /** The argument is how many did not match. */
    TOOLBAR_ICONS,

    KEY_ICONS,

    LETTER_IMAGES,

    SOUND,

    /** The dark version's picture did not fit or read. */
    DARK_PICTURE,

    /** The skin has one version: the dark one is made of its colours. */
    NO_DARK,

    MORE_PERIODS,

    IMAGES,

    /** The argument is how many. */
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

        /** A format told apart and not converted: [detail] is one of [SkinFormat]'s ids. */
        UNSUPPORTED,
    }
}

/** [SkinError.Reason.UNSUPPORTED]'s formats, by an id the app and the web page each put in their own words. */
object SkinFormat {
    /** 手心输入法's skin. */
    const val SHOUXIN = "shouxin"

    /** Gboard's theme. */
    const val GBOARD = "gboard"

    /** 仓输入法's skin. */
    const val CANG = "cang"

    /** A theme inside an APK. */
    const val APK = "apk"

    /** Sogou's own-made skin: pictures and colours by key kind, no keys' places. */
    const val SOGOU_DIY = "sogou-diy"

    val ALL = listOf(SHOUXIN, GBOARD, CANG, APK, SOGOU_DIY)
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
