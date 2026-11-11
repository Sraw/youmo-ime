/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A skin's looks ([SkinLook]) written as a theme 4.0 pack: `theme.xml` and its files ([SkinOutput]), for the
 * import's own check to read as any pack (ThemeReader, the asset limits). The rules (dev/design/060-skin-import.md §4):
 *
 * - The 24 colours of [ThemeReader.REQUIRED_COLORS] and `first` from the skin's (the board's mean, a key face's,
 *   the labels'); a dark version's only where they differ, under `<colors variant="dark">`.
 * - One `<picture>`, the first version's board (a theme has one); a second version's board is its colour.
 * - Key faces as `image(…)` nine-patches, their transparent margins cut off (the gap is the key's inset), at most
 *   [MAX_KEY_SIDE] px, drawn at the skin's pixels per dp ([SkinLook.width] / 360).
 * - Square keys with no border nor shadow, as a skin's images draw their own; a pressed face where the skin has one,
 *   else the app's press tint.
 * - The skin's own name and author; the theme's file names its own (`l-key-n.png`), never a name from the skin.
 */
class SkinTheme(private val source: SkinSource, private val decode: (ByteArray) -> Pixels?, private val fallbackName: String) {
    private val files = LinkedHashMap<String, SkinOutput>()

    // an image of the skin's written once, whichever keys have it (Baidu's styles share an atlas's tiles)
    private val written = HashMap<SkinFill.Image, String>()

    fun write(): SkinConversion {
        val looks = source.variants.sortedBy { it.ordinal }.associateWith { source.look(it) }
        val first = looks.keys.first()
        val main = looks.getValue(first)
        val notes = LinkedHashSet<SkinNote>()
        looks.values.forEach { notes += it.notes }
        if (looks.size == 2 && looks.getValue(Variant.DARK).board != null) notes += SkinNote.DARK_PICTURE
        val palettes = looks.mapValues { palette(it.value) }
        val variants = if (looks.size == 1 && first == Variant.LIGHT && lum(main.boardColor ?: WHITE) < DARK_BOARD) "dark" else looks.keys.joinToString(" ") { it.word }
        val body = ArrayList<String>()
        body += colors(palettes.getValue(first), dark = false)
        if (looks.size == 2) {
            val light = palettes.getValue(Variant.LIGHT)
            body += colors(palettes.getValue(Variant.DARK).filter { (k, v) -> light[k] != v }, dark = true)
        }
        main.board?.let { board ->
            add(SkinOutput.Image(BOARD, board.capped(MAX_PICTURE_SIDE), MAX_PICTURE_BYTES, shrink = true))
            body += """  <picture src="$BOARD">"""
            body += """    <cut orientation="portrait" file="$BOARD"/>"""
            body += "  </picture>"
        }
        main.font?.let { (file, bytes) ->
            val name = "skin-" + file.filter { it in 'a'..'z' || it in '0'..'9' || it == '.' }.takeLast(MAX_FILE_PART)
            add(SkinOutput.Bytes(name, bytes))
            body += """  <font name="skin" src="${escape(name)}"/>"""
        }
        for ((variant, look) in looks) body += styles(look, variant, scene = if (variant == first) "" else "@dark ")
        if (main.sounds.isNotEmpty()) {
            body += "  <sounds>"
            for ((key, sound) in main.sounds) {
                val name = "sound-" + key.word + "." + sound.first.substringAfterLast('.')
                add(SkinOutput.Bytes(name, sound.second))
                body += """    <sound key="${key.word}" src="$name"/>"""
            }
            body += "  </sounds>"
        }
        val preview = source.preview?.let(decode)?.let {
            add(SkinOutput.Image(PREVIEW, it.capped(MAX_PREVIEW_SIDE), MAX_PREVIEW_BYTES, shrink = true))
            PREVIEW
        }
        val name = themeName(source.name).ifEmpty { themeName(fallbackName) }.ifEmpty { "skin" }
        val author = clean(source.author).take(MAX_META)
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<theme xmlns="${ThemeReader.NAMESPACE}" name="${escape(name)}" variants="$variants" version="4.0">""")
            append("  <meta")
            if (author.isNotBlank()) append(""" author="${escape(author)}"""")
            append(""" version="1" min-app="$MIN_APP"""")
            if (preview != null) append(""" preview="$preview"""")
            appendLine("/>")
            body.forEach(::appendLine)
            appendLine("</theme>")
        }
        return SkinConversion(name, xml, files.values.toList(), notes)
    }

    private fun add(out: SkinOutput) {
        files[out.name] = out
    }

    private fun colors(c: Map<String, String>, dark: Boolean): List<String> =
        listOf(if (dark) """  <colors variant="dark">""" else "  <colors>") +
            c.map { (k, v) -> """    <color name="$k" value="$v"/>""" } + "  </colors>"

    // the app's own widgets' colours (panels, the clipboard, menus) from the skin's
    private fun palette(look: SkinLook): Map<String, String> {
        val key = look.keys["key"]
        val fn = look.keys["key.function"] ?: key
        val en = look.keys["key.enter"] ?: fn
        val sp = look.keys["key.space"] ?: key
        val board = look.boardColor?.let(::opaque) ?: DEFAULT_BOARD
        val dark = lum(board) < DARK_TEXT
        val text = key?.color ?: if (dark) WHITE else NEAR_BLACK
        val cand = look.candidate ?: text
        val first = look.first ?: cand
        fun face(k: SkinKey?, default: Int): Int = solid(fillColor(k?.fill) ?: default, board)
        val c = linkedMapOf(
            "background" to hex(board), "bar" to hex(look.barColor?.let(::opaque) ?: board), "keyboard" to hex(board),
            "key" to hex(face(key, WHITE)), "keyText" to hex(text),
            "candidateText" to hex(cand), "candidateLabel" to "alpha(${hex(cand)}, 70%)", "candidateComment" to "alpha(${hex(cand)}, 70%)",
            "altKey" to hex(face(fn, ALT_KEY)), "altKeyText" to hex(fn?.color ?: text),
            "accentKey" to hex(face(en, ALT_KEY)), "accentKeyText" to hex(en?.color ?: fn?.color ?: text),
            "keyPressHighlight" to if (dark) "#33ffffff" else "#1f000000", "keyShadow" to "#33000000",
            "popupBackground" to hex(solid(fillColor(look.hintFill) ?: face(key, WHITE), board)), "popupText" to hex(look.hintText ?: text),
            "spaceBar" to hex(face(sp, WHITE)), "divider" to "alpha(${hex(text)}, 12%)", "clipboardEntry" to hex(face(key, WHITE)),
            "genericActiveBackground" to hex(first), "genericActiveForeground" to if (lum(first) < ACTIVE_DARK) "#ffffff" else "#000000",
            "keyBorder" to "transparent", "keyHint" to "alpha(${hex(text)}, 60%)", "toolbarIcon" to hex(cand),
            "first" to hex(first),
        )
        return c
    }

    // one version's styles; [scene] '' for the first, '@dark ' for the second
    private fun styles(look: SkinLook, variant: Variant, scene: String): List<String> {
        val out = ArrayList<String>()
        val px = look.width / BaiduSkin.DP_ACROSS
        val prefix = variant.word.take(1)
        fun st(selectors: String, vararg attrs: Pair<String, String?>) {
            val set = attrs.filter { it.second != null }
            if (set.isEmpty()) return
            val sel = selectors.split(',').joinToString(", ") { scene + it.trim() }
            out += """  <style select="${escape(sel)}" """ + set.joinToString(" ") { (k, v) -> """$k="${escape(v!!)}"""" } + "/>"
        }
        if (look.board != null && scene.isEmpty()) {
            st("keyboard", "fill" to "picture")
            st("bar", "fill" to "picture")
        } else {
            st("keyboard", "fill" to "@keyboard")
            st("bar", "fill" to "@bar")
        }
        st("panel", "fill" to "@keyboard")
        st(
            "key", "radius" to "0", "border" to "none", "shadow" to "none", "color" to "@keyText", "icon-color" to "@keyText", "press" to "tint",
            "inset" to look.inset?.let { (v, h) -> "${v}dp ${h}dp" },
        )
        var pressedFaces = false
        for ((sel, k) in look.keys) {
            val fill = k.fill?.let { fill(it, prefix, sel, "n", px) }
            val color = k.color?.let(::hex)
            st(sel, "fill" to fill, "color" to color, "icon-color" to color?.takeIf { k.icons })
            val pressed = k.pressed?.takeIf { !same(it, k.fill) }?.let { fill(it, prefix, sel, "p", px) }
            if (pressed != null) pressedFaces = true
            st(
                sel.split(',').joinToString(",") { "$it:pressed" }, "fill" to pressed, "overlay" to pressed?.let { "transparent" },
                "color" to k.pressedColor?.takeIf { it != k.color }?.let(::hex),
            )
        }
        if (!pressedFaces) st("key:pressed", "overlay" to "@keyPressHighlight")
        st("key mark, key shortcut", "color" to "@keyHint", "icon-color" to "@keyHint")
        st("candidate", "color" to "@candidateText")
        st("candidate:first", "color" to "@first")
        st("candidate label", "color" to "@candidateLabel")
        st("candidate comment", "color" to "@candidateComment")
        st("candidates-divider", "fill" to "transparent")
        st("candidates-panel", "fill" to "@keyboard")
        st("preedit", "color" to (look.preedit?.let(::hex) ?: "@keyText"), "cursor-color" to "@first")
        st("toolbar", "icon-color" to "@toolbarIcon")
        st("toolbar:selected", "icon-color" to "@first")
        val hint = look.hintFill
        if (hint is SkinFill.Image) {
            st("preview", "fill" to fill(hint, prefix, "preview", "n", px), "color" to "@popupText")
        } else {
            st("preview", "fill" to "@popupBackground", "radius" to "6dp", "shadow" to SHADOW, "color" to "@popupText")
        }
        st("popup", "fill" to "@popupBackground", "radius" to "6dp", "shadow" to SHADOW, "color" to "@popupText")
        st("popup:selected", "fill" to "@genericActiveBackground", "color" to "@genericActiveForeground")
        st("menu", "fill" to "@popupBackground", "radius" to "6dp", "color" to "@popupText", "icon-color" to "@popupText")
        st("menu:selected", "fill" to "@genericActiveBackground", "color" to "@genericActiveForeground", "icon-color" to "@genericActiveForeground")
        st("clipboard", "fill" to "@clipboardEntry", "color" to "@keyText")
        st("picker categories", "fill" to "@altKey", "color" to "@altKeyText")
        st("editing", "fill" to "@altKey")
        st("voice mic", "fill" to "@first", "icon-color" to "@genericActiveForeground")
        if (look.font != null && scene.isEmpty()) st("key", "font" to "skin")
        return out
    }

    // a face's fill value: a colour, or an image of its own (its transparent margins cut, at most MAX_KEY_SIDE)
    private fun fill(f: SkinFill, prefix: String, sel: String, which: String, px: Double): String {
        if (f is SkinFill.Color) return hex(f.argb)
        val img = f as SkinFill.Image
        return written.getOrPut(img) { image(img, prefix, sel, which, px) }
    }

    private fun image(img: SkinFill.Image, prefix: String, sel: String, which: String, px: Double): String {
        var p = img.pixels
        var ins = img.insets ?: Pixels.Box(0, 0, 0, 0)
        val box = p.opaqueBounds()
        if (box != null && (box.width != p.width || box.height != p.height)) {
            ins = Pixels.Box(
                max(0, ins.left - box.left), max(0, ins.top - box.top),
                max(0, ins.right - (p.width - box.right)), max(0, ins.bottom - (p.height - box.bottom)),
            )
            p = p.crop(box.left, box.top, box.width, box.height)!!
        }
        var scale = 1.0
        if (max(p.width, p.height) > MAX_KEY_SIDE) {
            scale = MAX_KEY_SIDE.toDouble() / max(p.width, p.height)
            p = p.resized(max(1, (p.width * scale).roundToInt()), max(1, (p.height * scale).roundToInt()))
        }
        val name = (prefix + "-" + sel + "-" + which).lowercase().replace(NOT_NAME, "-").trim('-') + ".png"
        add(SkinOutput.Image(name, p, MAX_KEY_BYTES, shrink = false))
        var l = (ins.left * scale).roundToInt()
        var r = (ins.right * scale).roundToInt()
        var t = (ins.top * scale).roundToInt()
        var b = (ins.bottom * scale).roundToInt()
        if (l + r >= p.width) { l = 0; r = 0 }
        if (t + b >= p.height) { t = 0; b = 0 }
        val n = (px * scale).roundToInt().coerceIn(1, MAX_DENSITY)
        return "image($name, $l $t $r $b, ${n}x)"
    }

    private fun same(a: SkinFill?, b: SkinFill?): Boolean = when {
        a is SkinFill.Color && b is SkinFill.Color -> a.argb == b.argb
        a is SkinFill.Image && b is SkinFill.Image -> a.pixels.width == b.pixels.width && a.pixels.argb.contentEquals(b.pixels.argb)
        else -> false
    }

    private fun fillColor(f: SkinFill?): Int? = when (f) {
        is SkinFill.Color -> f.argb
        is SkinFill.Image -> f.pixels.average()
        null -> null
    }

    companion object {
        const val MIN_APP = "0.6.0"
        const val BOARD = "board.png"
        const val PREVIEW = "preview.png"
        const val MAX_KEY_SIDE = 512
        const val MAX_KEY_BYTES = 2L * 1024 * 1024
        const val MAX_PICTURE_SIDE = 4096
        const val MAX_PICTURE_BYTES = 16L * 1024 * 1024
        const val MAX_PREVIEW_SIDE = 720
        const val MAX_PREVIEW_BYTES = 500L * 1024
        private const val MAX_DENSITY = 8
        private const val MAX_META = 64
        private const val MAX_FILE_PART = 40
        private const val SHADOW = "0 2dp 6dp #33000000"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val NEAR_BLACK = 0xFF111111.toInt()
        private const val DEFAULT_BOARD = 0xFFD1D4DB.toInt()
        private const val ALT_KEY = 0xFFBDC2CC.toInt()
        private const val DARK_BOARD = 0.1
        private const val DARK_TEXT = 0.2
        private const val ACTIVE_DARK = 0.4
        private const val SOLID_ALPHA = 0x99
        private const val GAMMA = 2.2
        private val NOT_NAME = Regex("[^a-z0-9]+")
        private val SPACES = Regex("\\s+")
        private val VARIANT_SUFFIX = Regex("#(light|dark)$", RegexOption.IGNORE_CASE)

        /** [s] with no control character and one space for each run of white space. */
        fun clean(s: String): String = s.filter { it >= ' ' && it != '\u007F' }.replace(SPACES, " ").trim()

        /**
         * A skin's name as its theme's: [clean], no `/` nor `\` (a theme's name names its folder), not led by a dot,
         * not ending as the app's names of a version do (`#dark`), at most [ThemeReader.MAX_NAME].
         */
        fun themeName(s: String): String {
            var n = clean(s).replace('/', '-').replace('\\', '-').trimStart('.', ' ')
            while (VARIANT_SUFFIX.containsMatchIn(n)) n = n.replace(VARIANT_SUFFIX, "").trimEnd()
            return n.take(ThemeReader.MAX_NAME).trim()
        }

        fun hex(argb: Int): String {
            val a = argb ushr 24
            val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
            return if (a == 0xFF) "#$rgb" else "#" + a.toString(16).padStart(2, '0') + rgb
        }

        private fun opaque(argb: Int) = argb or (0xFF shl 24)

        // a face mostly transparent (art drawn on the board): the app's own panels need a solid colour
        private fun solid(argb: Int, board: Int): Int = if (argb ushr 24 == 0xFF) argb else if (argb ushr 24 > SOLID_ALPHA) opaque(argb) else board

        fun lum(argb: Int): Double {
            fun ch(shift: Int) = ((argb shr shift and 0xFF) / 255.0).pow(GAMMA)
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }

        private fun escape(s: String) = buildString {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(c)
                }
            }
        }

        private fun Pixels.capped(side: Int): Pixels {
            val m = max(width, height)
            if (m <= side) return this
            val s = side.toDouble() / m
            return resized(max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()))
        }
    }
}

/** A file of a converted skin's theme, named by the converter; the platform writes it (an image as PNG). */
sealed interface SkinOutput {
    val name: String

    /** [pixels] as PNG within [maxBytes]; where [shrink], halved in size till it is (a picture stretched anyway), else none. */
    class Image(override val name: String, val pixels: Pixels, val maxBytes: Long, val shrink: Boolean) : SkinOutput

    class Bytes(override val name: String, val bytes: ByteArray) : SkinOutput
}

/** A skin converted: the theme's [name], its `theme.xml` ([themeXml]), its [files] and what did not carry over ([notes]). */
class SkinConversion(val name: String, val themeXml: String, val files: List<SkinOutput>, val notes: Set<SkinNote>)
