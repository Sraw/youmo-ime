/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.math.roundToInt

/**
 * An iFlytek (讯飞) shop skin (`.it`, a plain ZIP), as five real ones from the shop show it: `theme_info.ini` (name, author, `PREVIEW`), at the top or in the one folder all is in, and per
 * screen size a folder named by its short side holding `layout/style.ini` (styles by name: an image section's name,
 * `TEXT_COLOR`, `BG_COLOR`), `layout/image.ini` (`[Image_*]`: a file of `res/` and its stretchable band, `X_DIVS`,
 * `Y_DIVS`) and `res/`. Read: the one folder of the largest size not past 1080 (else the smallest) that has both
 * files, since a skin's other sizes override only some images and lean on it for the rest. One version; keys' places
 * and icons (`layout/layout_*`, templates of percentages) are not read, only noted. The app's own skins (`info.ini`,
 * numbered styles) are another format, not taken.
 */
class XunfeiSkin(private val f: SkinFiles) : SkinSource {
    private val root: String
    private val dir: String
    private val width: Int
    private val style: Ini
    private val image: Ini
    private val pictures = HashMap<String, SkinFill.Image?>()
    private var missing = false
    override val name: String
    override val author: String
    override val preview: ByteArray?
    override val variants: Set<Variant> = setOf(Variant.LIGHT)

    init {
        root = rootOf(f.names) ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
        val sizes = sizesOf(f.names, root)
        val size = sizes.filter { it <= MAX_SIZE }.maxOrNull() ?: sizes.minOrNull() ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
        dir = "$root$size/"
        width = size.coerceIn(MIN_WIDTH, MAX_WIDTH)
        style = capped(f.ini(dir + STYLE))
        image = capped(f.ini(dir + IMAGE))
        val info = f.ini(root + INFO)["THEME_INFO"].orEmpty()
        name = info["NAME"].orEmpty()
        author = info["AUTHOR"].orEmpty()
        preview = info["PREVIEW"]?.let { image[it.trim()]?.get("SRC") }?.let(::file)?.let(f::bytes)
    }

    private fun file(src: String?): String? = src?.trim()?.takeIf { it.isNotEmpty() }?.let { f.first(dir + "res/" + it) }

    // an image section, once however many styles name it (one file written for them all)
    private fun picture(section: String): SkinFill.Image? = pictures.getOrPut(section) {
        val s = image[section] ?: return@getOrPut null
        val px = f.pixels(file(s["SRC"]))
        if (px == null) {
            missing = true
            return@getOrPut null
        }
        val x = band(s["X_DIVS"], px.width)
        val y = band(s["Y_DIVS"], px.height)
        SkinFill.Image(px, if (x == null && y == null) null else Pixels.Box(x?.first ?: 0, y?.first ?: 0, x?.second ?: 0, y?.second ?: 0))
    }

    // 'a,b', the stretchable pixels of [size]: as insets before and after it
    private fun band(value: String?, size: Int): Pair<Int, Int>? =
        SkinText.ints(value, 2)?.takeIf { it[0] in 0 until it[1] && it[1] <= size }?.let { it[0] to size - it[1] }

    // a style's image where it reads, else its colour
    private fun fill(name: String): SkinFill? {
        val s = style[name] ?: return null
        s["IMAGE"]?.substringBefore(',')?.trim()?.let(::picture)?.let { return it }
        return SkinText.color(s["BG_COLOR"])?.let { SkinFill.Color(it) }
    }

    private fun text(name: String): Int? = SkinText.color(style[name]?.get("TEXT_COLOR"))

    override fun look(variant: Variant): SkinLook {
        val look = SkinLook(width)
        val bar = fill("Style_Cand_Default")
        SkinPatch.board(look, board(), (bar as? SkinFill.Image)?.let { shortened(it, MAX_BAR_HEIGHT) } ?: bar)
        val letter = SkinKey(
            fill("Style_Keyboard_Key_2"), fill("Style_Keyboard_Key_2_Press"),
            text("Style_Keyboard_Key_Fore_2"), text("Style_Keyboard_Key_Fore_2_Press"), icons = false,
        )
        look.keys["key"] = letter
        val function = SkinKey(
            fill("Style_Keyboard_Key_1") ?: letter.fill, fill("Style_Keyboard_Key_1_Press") ?: letter.pressed,
            text("Style_Keyboard_Key_Fore_1") ?: letter.color, text("Style_Keyboard_Key_Fore_1_Press") ?: letter.pressedColor, icons = true,
        )
        val same = SkinPatch.same(function.fill, letter.fill) && SkinPatch.same(function.pressed, letter.pressed) &&
            function.color == letter.color && function.pressedColor == letter.pressedColor
        if (!same) look.keys["key.function"] = function
        look.candidate = text("Style_Cand_Word_1")
        look.first = text("Style_Cand_Word_1_Focused")
        // its own background is not carried over: on the bar's instead, only where it still reads
        look.preedit = text("Style_Composing_Key_Fixed")?.takeIf { c -> look.barColor?.let { contrast(c, it) >= MIN_CONTRAST } != false }
        look.hintFill = fill("Style_Balloon_Amplify")
        look.hintText = text("Style_Balloon_Amplify")
        look.notes += SkinNote.LAYOUT
        look.notes += SkinNote.KEY_ICONS
        animations(look)
        look.notes += SkinNote.NO_DARK
        if (missing || f.imageFailed) look.notes += SkinNote.IMAGES
        return look
    }

    // the keyboard's picture, one under the skin's width stretched to it: a strip as the skin stretches it, else in proportion
    private fun board(): SkinFill? {
        val fill = fill("Style_Py_9") ?: picture("Image_Layout_Bg")
        val w = minOf(width, MAX_SIZE)
        if (fill !is SkinFill.Image || fill.pixels.width >= w) return fill
        val px = fill.pixels
        val strip = px.width * 2 < w || px.height > px.width
        val h = if (strip) (w * BOARD_RATIO).roundToInt() else (px.height * w.toDouble() / px.width).roundToInt()
        val short = shortened(fill, h)
        return SkinFill.Image(short.pixels.nine(short.insets, w, h), null)
    }

    // made [maxH] high first where taller: a stretch across makes a row of the new width for every row of the old
    private fun shortened(img: SkinFill.Image, maxH: Int): SkinFill.Image {
        val px = img.pixels
        if (px.height <= maxH) return img
        val k = maxH.toDouble() / px.height
        val insets = img.insets?.let { Pixels.Box(it.left, (it.top * k).roundToInt(), it.right, (it.bottom * k).roundToInt()) }
        return SkinFill.Image(px.resized(px.width, maxH), insets)
    }

    // the keyboards' animation.ini: frames, the keyboard's coming in, or other key animation
    private fun animations(look: SkinLook) {
        for (n in f.names) {
            if (!n.startsWith(root + "layout/") || !n.endsWith("/animation.ini")) continue
            if (ANIMATIONS.all { it in look.notes }) return
            for ((section, keys) in f.ini(n)) {
                look.notes += when {
                    "ANIM_IMAGE" in keys -> SkinNote.FRAME_ANIMATION
                    section.startsWith("Anim_Keyboard") -> SkinNote.KEYBOARD_ANIMATION
                    else -> SkinNote.ANIMATION
                }
            }
        }
    }

    companion object {
        private const val INFO = "theme_info.ini"
        private const val STYLE = "layout/style.ini"
        private const val IMAGE = "layout/image.ini"
        private val SIZE = Regex("([1-9]\\d{0,4})/layout/style\\.ini")
        private const val MAX_SIZE = 1080
        private const val MIN_WIDTH = 240
        private const val MAX_WIDTH = 4096
        private const val MAX_SECTIONS = 5000

        // the shop's whole backgrounds are about 0.78 as high as wide
        private const val BOARD_RATIO = 0.78

        // the bar is drawn at most 0.115 as high as a board at most 4096 wide
        private const val MAX_BAR_HEIGHT = 480
        private const val MIN_CONTRAST = 3.0
        private val ANIMATIONS = listOf(SkinNote.FRAME_ANIMATION, SkinNote.KEYBOARD_ANIMATION, SkinNote.ANIMATION)

        private fun contrast(a: Int, b: Int): Double {
            val la = SkinTheme.lum(a)
            val lb = SkinTheme.lum(b)
            return (maxOf(la, lb) + LUM_OFFSET) / (minOf(la, lb) + LUM_OFFSET)
        }

        private const val LUM_OFFSET = 0.05

        private fun capped(ini: Ini): Ini = if (ini.size <= MAX_SECTIONS) ini else ini.entries.take(MAX_SECTIONS).associate { it.key to it.value }

        // "" or "<folder>/": where theme_info.ini is, at the top or in the one folder all is in
        private fun rootOf(names: Set<String>): String? {
            if (INFO in names) return ""
            val top = names.map { it.substringBefore('/', "") }.toSet().singleOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return "$top/".takeIf { it + INFO in names }
        }

        // the size folders beside theme_info.ini with both a style.ini and an image.ini
        private fun sizesOf(names: Set<String>, root: String): List<Int> = names.mapNotNull { n ->
            if (!n.startsWith(root)) return@mapNotNull null
            val m = SIZE.matchEntire(n.substring(root.length)) ?: return@mapNotNull null
            m.groupValues[1].toInt().takeIf { root + m.groupValues[1] + "/" + IMAGE in names }
        }

        /** Whether [names] are an iFlytek shop skin's. */
        fun accepts(names: Set<String>): Boolean = rootOf(names)?.let { sizesOf(names, it).isNotEmpty() } == true
    }
}
