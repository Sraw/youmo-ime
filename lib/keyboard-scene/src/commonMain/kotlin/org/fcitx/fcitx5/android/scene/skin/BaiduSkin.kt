/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.theme.SoundKey
import kotlin.math.roundToInt

/**
 * A Baidu skin of the classic engine (dev/design/060-skin-import.md §2.1): `.bds` for Android, `.bdi` for iOS. A
 * ZIP of one version at its top, or `light/` and `dark/` (`.bdi`: `light/skin/`, `dark/skin/`), each with
 * `port/gen.ini` (the panel, the candidate bar), the boards' INI files (`port/py_26.ini`: a key a `[KEYn]` of
 * styles, rectangles and actions), `port/res/default.css` (styles: an image of an atlas, colours, a sound) and the
 * atlases (`res/<name>.png` cut by `<name>.til`: a source rectangle and a stretchable inner one, a nine-patch).
 *
 * The 26-key pinyin board says how each kind of key looks: the style most of its letters have, and the function
 * keys', space's, enter's, backspace's and shift's by their action codes. Keys without an action are decoration,
 * not taken. Positions are not taken (the app's layouts are its own); the gap around a letter is.
 */
class BaiduSkin(private val f: SkinFiles) : SkinSource {
    private val roots: Map<Variant, String>
    override val name: String
    override val author: String
    override val preview: ByteArray?

    init {
        val found = f.names.mapNotNull { n -> n.takeIf { it.endsWith(GEN) }?.removeSuffix(GEN) }.sortedBy { it.length }
        if (found.isEmpty()) throw SkinError(SkinError.Reason.UNKNOWN)
        roots = LinkedHashMap<Variant, String>().apply {
            for (r in found) getOrPut(if (r.split('/').contains("dark")) Variant.DARK else Variant.LIGHT) { r }
        }
        val info = f.first("Info.txt", found[0] + "Info.txt")
        val text = f.text(info)
        // Info.txt is key=value lines, under a section or none
        val meta = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) meta.getOrPut(line.substring(0, eq).trim()) { line.substring(eq + 1).trim() }
        }
        name = meta["Name"].orEmpty()
        author = meta["Author"].orEmpty()
        preview = f.first("demo.png", found[0] + "demo.png", found[0] + "port/demo.png")?.let(f::bytes)
    }

    override val variants: Set<Variant> get() = roots.keys

    override fun look(variant: Variant): SkinLook = Reader(roots.getValue(variant)).look()

    private inner class Reader(private val r: String) {
        private val css = f.ini(f.first(r + "port/res/default.css", r + "res/default.css"))
        private val tiles = HashMap<String, SkinFill.Image?>()
        private var letterImages = false

        private fun style(id: String?): Map<String, String> = id?.trim()?.takeIf { it.isNotEmpty() }?.let { css["STYLE$it"] }.orEmpty()

        // 'atlas,n': the n-th image of res/atlas.png as its .til cuts it
        private fun tile(ref: String?): SkinFill.Image? {
            val parts = ref?.split(',')?.map { it.trim() } ?: return null
            if (parts.size < 2 || parts[0].isEmpty()) return null
            val key = parts[0] + "," + parts[1]
            if (key in tiles) return tiles[key]
            val atlas = f.image(f.first(r + "res/" + parts[0] + ".png", r + "port/res/" + parts[0] + ".png"))
            val til = f.ini(f.first(r + "res/" + parts[0] + ".til", r + "port/res/" + parts[0] + ".til"))["IMG" + parts[1]].orEmpty()
            val src = SkinText.ints(til["SOURCE_RECT"], RECT)?.takeIf { it[2] > 0 && it[3] > 0 }
            val out = if (atlas == null || src == null) {
                null
            } else {
                val x = src[0].coerceIn(0, atlas.width - 1)
                val y = src[1].coerceIn(0, atlas.height - 1)
                val w = minOf(src[2], atlas.width - x)
                val h = minOf(src[3], atlas.height - y)
                val inner = SkinText.ints(til["INNER_RECT"], RECT)
                val insets = inner?.let {
                    Pixels.Box(maxOf(0, it[0] - x), maxOf(0, it[1] - y), maxOf(0, x + w - it[0] - it[2]), maxOf(0, y + h - it[1] - it[3]))
                }
                SkinFill.Image(atlas.pixels(x, y, w, h), insets)
            }
            tiles[key] = out
            return out
        }

        // a back style: its face and pressed face, an image or a colour
        private fun face(id: String?): Pair<SkinFill?, SkinFill?> {
            val s = style(id)
            val nm = tile(s["NM_IMG"]) ?: SkinText.color(s["NM_COLOR"])?.let(SkinFill::Color)
            val hl = tile(s["HL_IMG"]) ?: SkinText.color(s["HL_COLOR"])?.let(SkinFill::Color)
            return nm to hl
        }

        // a fore style list's first: the label's colour and pressed colour (of the glyph where it is a picture)
        private fun label(fore: String?): Pair<Int?, Int?> {
            val s = style(fore?.split(',')?.firstOrNull())
            if ("NM_COLOR" in s && "NM_IMG" !in s) return SkinText.color(s["NM_COLOR"]) to SkinText.color(s["HL_COLOR"])
            val nm = tile(s["NM_IMG"])
            if (nm != null) letterImages = true
            return nm?.pixels?.glyphColor() to tile(s["HL_IMG"])?.pixels?.glyphColor()
        }

        fun look(): SkinLook {
            val gen = f.ini(r + GEN)
            val size = SkinText.ints(gen["PANEL"]?.get("SIZE"), 2)
            val pw = size?.get(0)?.takeIf { it in MIN_WIDTH..MAX_SIDE } ?: DEFAULT_WIDTH
            val ph = size?.get(1)?.takeIf { it in 1..MAX_SIDE } ?: (pw * PANEL_RATIO).roundToInt()
            val look = SkinLook(pw)
            val cand = f.ini(r + "port/" + (gen["CAND"]?.get("LAYOUT_NAME") ?: "cand1") + ".cnd")
            val ch = SkinText.ints(gen["CAND"]?.get("VIEW_RECT"), RECT)?.get(3)?.takeIf { it in 1..MAX_SIDE / 2 } ?: (pw * BAR_RATIO).roundToInt()
            board(look, gen, cand, pw, ph, ch)
            look.candidate = label(cand["CAND"]?.get("FORE_STYLE")).first
            look.first = label(cand["CAND"]?.get("FIRST_FORE")).first ?: look.candidate
            look.preedit = label(gen["INPUT"]?.get("FORE_STYLE")).first
            keys(look, pw)
            val hint = f.ini(r + "port/" + (gen["HINT"]?.get("LAYOUT_NAME") ?: "hint1") + ".pop")
            val icon = hint["ICON" + (hint["HINT"]?.get("BACK_ICON") ?: "1")].orEmpty()
            look.hintFill = face(icon["BACK_STYLE"]).first
            look.hintText = label(icon["FORE_STYLE"]).first
            sound(look, gen)
            look.notes += SkinNote.LAYOUT
            val animated = css.values.any { "PRESS_ANIM" in it || "SHOW_ANIM" in it }
            if (animated || f.names.any { it.startsWith(r) && it.endsWith("anim.ini") }) look.notes += SkinNote.ANIMATION
            if (f.names.any { it.startsWith(r) && it.endsWith("font.json") }) look.notes += SkinNote.TEXT_SIZE
            val toolbar = f.names.any { it.startsWith(r + "port/") && it.substringAfterLast('/').startsWith("26tool") }
            if (toolbar || cand.keys.any { it.startsWith("ICON") }) look.notes += SkinNote.TOOLBAR_ICONS
            if (letterImages) look.notes += SkinNote.LETTER_IMAGES
            if (f.imageFailed) look.notes += SkinNote.IMAGES
            return look
        }

        // the bar's background over the keyboard's, one picture; translucent (iOS blur skins) laid on its own mean
        private fun board(look: SkinLook, gen: Ini, cand: Ini, pw: Int, ph: Int, ch: Int) {
            val panel = face(gen["PANEL"]?.get("BACK_STYLE")?.split(',')?.firstOrNull()).first
            val bar = face(cand["CAND"]?.get("BACK_STYLE")).first
            look.barHeight = ch
            if (panel !is SkinFill.Image && bar !is SkinFill.Image) {
                look.boardColor = (panel as? SkinFill.Color)?.argb
                look.barColor = (bar as? SkinFill.Color)?.argb ?: look.boardColor
                return
            }
            val board = Pixels(pw, ch + ph)
            when (panel) {
                is SkinFill.Image -> {
                    board.paste(panel.pixels.nine(panel.insets, pw, ph), 0, ch)
                    look.boardColor = panel.pixels.average(keepAlpha = false)
                }
                is SkinFill.Color -> { board.paste(Pixels.filled(pw, ph, panel.argb), 0, ch); look.boardColor = panel.argb }
                null -> Unit
            }
            when (bar) {
                is SkinFill.Image -> {
                    val strip = bar.pixels.nine(bar.insets, pw, ch)
                    board.over(strip, 0, 0)
                    look.barColor = strip.average(keepAlpha = false)
                }
                is SkinFill.Color -> { board.paste(Pixels.filled(pw, ch, bar.argb), 0, 0); look.barColor = bar.argb }
                // no bar background: the keyboard's shows through it in Baidu too (panel is an image here: checked above)
                null -> { board.paste((panel as SkinFill.Image).pixels.nine(panel.insets, pw, ch), 0, 0); look.barColor = look.boardColor }
            }
            look.board = if (board.translucent()) {
                Pixels.filled(pw, ch + ph, board.average(keepAlpha = false) ?: GREY).also { it.over(board, 0, 0) }
            } else {
                board
            }
        }

        private fun keys(look: SkinLook, pw: Int) {
            val board = f.first(r + "port/py_26.ini", r + "port/def_26.ini", r + "port/en_26.ini") ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
            val kinds = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            val letters = ArrayList<IntArray>()
            for ((section, key) in f.ini(board)) {
                val back = key["BACK_STYLE"]?.split(',')?.firstOrNull()?.trim()?.takeIf { section.startsWith("KEY") && it.isNotEmpty() }
                val action = key["CENTER"]?.trim().orEmpty()
                // no action: drawn over the board, not a key (a sticker, a shadow)
                if (back != null && action.isEmpty() && style(back)["NM_IMG"] != null) look.notes += SkinNote.DECORATION
                val kind = kind(action)
                if (back == null || kind == null) continue
                kinds.getOrPut(kind) { ArrayList() } += back to key["FORE_STYLE"].orEmpty()
                if (kind == LETTER) SkinText.ints(key["VIEW_RECT"], RECT)?.let { letters += it }
            }
            var function: Pair<String?, Int?>? = null
            for ((kind, selector) in SELECTORS) {
                val list = kinds[kind].orEmpty()
                val back = list.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
                val (color, pressedColor) = label(list.firstOrNull { it.first == back }?.second)
                if (kind == "function") function = back to color
                // backspace and shift as the other function keys: one style for them all
                val asFunction = (kind == "backspace" || kind == "shift") && function == back to color
                if (back == null || asFunction) continue
                val (nm, hl) = face(back)
                look.keys[selector] = SkinKey(nm, hl, color, pressedColor, icons = kind != LETTER)
            }
            if (KEY !in look.keys) throw SkinError(SkinError.Reason.NO_KEYBOARD)
            look.inset = inset(letters, look.keys.getValue(KEY).fill, pw)
        }

        // a key's kind by its action: a letter, a function key of its own style, another function key; null: none
        private fun kind(action: String): String? = when {
            action.length == 1 && action[0] in 'a'..'z' -> LETTER
            else -> FUNCTION_KINDS[action] ?: if (action.matches(FUNCTION)) "function" else null
        }

        // the gap around a letter, dp: the row's pitch less the key's drawn body. A view rect wider than the pitch
        // (iOS .bdi: they overlap, the image drawn at its own size, not stretched) shows the image's opaque part;
        // a narrower one stretches the image to it, its transparent margins (in the corners) kept
        private fun inset(rects: List<IntArray>, fill: SkinFill?, pw: Int): Pair<Int, Int>? {
            if (rects.size < 3) return null
            fun median(xs: List<Int>) = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val rows = rects.groupBy { it[1] }
            val pitchX = median(rows.values.flatMap { row -> row.sortedBy { it[0] }.zipWithNext { a, b -> b[0] - a[0] } })
            val pitchY = median(rows.keys.sorted().zipWithNext { a, b -> b - a })
            val vw = median(rects.map { it[2] })!!
            val vh = median(rects.map { it[3] })!!
            val img = (fill as? SkinFill.Image)?.pixels
            val box = img?.opaqueBounds() ?: Pixels.Box(0, 0, img?.width ?: vw, img?.height ?: vh)
            val iw = img?.width ?: vw
            val ih = img?.height ?: vh
            fun gap(pitch: Int?, view: Int, size: Int, body: Int): Double {
                if (pitch == null || pitch <= 0) return 0.0
                val drawn = if (view > pitch * OVERLAP) body else view - (size - body)
                return (pitch - drawn) / 2.0
            }
            val px = pw / DP_ACROSS
            fun dp(v: Double) = (v / px).roundToInt().coerceIn(0, MAX_INSET_DP)
            return dp(gap(pitchY, vh, ih, box.height)) to dp(gap(pitchX, vw, iw, box.width))
        }

        private fun sound(look: SkinLook, gen: Ini) {
            val path = style(gen["PANEL"]?.get("SOUND_STYLE"))["PRESS_SOUND_PATH"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val at = f.first(r + "res/" + path, r + path, path)
            val got = at?.let { f.bytes(it) }?.let { SkinSound.convert(path, it) }
            if (got != null) look.sounds[SoundKey.LETTER] = got else look.notes += SkinNote.SOUND
        }
    }

    companion object {
        private const val GEN = "port/gen.ini"
        private const val RECT = 4
        private const val KEY = "key"
        private const val LETTER = "letter"
        const val DP_ACROSS = 360.0
        private const val MAX_INSET_DP = 24
        private const val OVERLAP = 1.05
        private const val DEFAULT_WIDTH = 1080
        private const val MIN_WIDTH = 100
        private const val MAX_SIDE = 4096
        private const val PANEL_RATIO = 0.56
        private const val BAR_RATIO = 0.11
        private const val GREY = 0xFF808080.toInt()
        private val FUNCTION = Regex("F\\d+")

        // Baidu's action codes of the keys styled on their own
        private val FUNCTION_KINDS = mapOf("F38" to "space", "F39" to "enter", "F75" to "enter", "F36" to "backspace", "F10" to "shift", "F11" to "shift")
        private val SELECTORS = listOf(
            LETTER to KEY, "function" to "key.function", "space" to "key.space", "enter" to "key.enter",
            "backspace" to "key[role=backspace]", "shift" to "key[role=shift]",
        )

        /** Whether [names] are a Baidu classic skin's. */
        fun accepts(names: Set<String>) = names.any { it.endsWith(GEN) }
    }
}
