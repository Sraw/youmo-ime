/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.BaiduNames.Kind
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A Baidu `.bda` skin, the newer engine's: a ZIP of `Info.txt`, pictures `res/<id>.png` and `port/appearanceConfig`,
 * a protobuf ([ProtoWire]) of image, text and colour styles by number and panels of keys by name (`KEY_Q`,
 * `KEY_F39_S21`, [BaiduNames]) pointing at them. One version, no key positions: the 26-key pinyin panel's styles
 * are read as [BaiduSkin] reads a classic board's. Frame animations (GIF, APNG, video) are said, never decoded.
 */
class BaiduBdaSkin(private val f: SkinFiles) : SkinSource {
    private val root: String
    override val name: String
    override val author: String
    override val preview: ByteArray?

    init {
        val config = f.names.filter { it.endsWith(CONFIG) }.minByOrNull { it.length } ?: throw SkinError(SkinError.Reason.UNKNOWN)
        root = config.removeSuffix(CONFIG)
        // key=value lines, under a section or none
        val meta = HashMap<String, String>()
        for (line in f.text(f.first(root + "Info.txt")).lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) meta.getOrPut(line.substring(0, eq).trim()) { line.substring(eq + 1).trim() }
        }
        name = meta["Name"].orEmpty()
        author = meta["Author"].orEmpty()
        preview = f.first(root + "demo.png", root + "preview.png")?.let(f::bytes)
    }

    override val variants: Set<Variant> = setOf(Variant.LIGHT)

    override fun look(variant: Variant): SkinLook {
        val bytes = f.bytes(root + CONFIG) ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
        if (bytes.size > MAX_CONFIG_BYTES) throw SkinError(SkinError.Reason.TOO_BIG, CONFIG)
        return Reader(ProtoWire(bytes).root).look()
    }

    private data class Ref(val type: Int, val key: Int)

    private class Keyed(val back: Ref, val fores: List<Ref>, val action: String, val kind: Kind)

    private inner class Reader(config: ProtoWire.Message) {
        private val images = entries(config, IMAGE_STYLES)
        private val texts = entries(config, TEXT_STYLES)
        private val colors = entries(config, COLOR_STYLES)
        private val panels = config.all(PANELS).mapNotNull { f -> config.message(f)?.let { e -> e.string(1)?.let { k -> e.message(2)?.let { k to it } } } }.toMap()
        private val palette = config.message(PALETTE)
        private val width = config.int(DESIGN_WIDTH)?.takeIf { it in MIN_WIDTH..MAX_SIDE } ?: DEFAULT_WIDTH

        // a text's size over the app's standard key height, in the skin's pixels
        private val rowPx = BaiduNames.ROW_DP * width / SkinTheme.DP_ACROSS
        private var frames = false
        private var textSize = false

        // whether a picture draws something, by its file: keys sharing one are not scanned each
        private val drawn = HashMap<String, Boolean>()

        private fun entries(m: ProtoWire.Message, n: Int): Map<Int, ProtoWire.Message> =
            m.all(n).mapNotNull { f -> m.message(f)?.let { e -> e.int(1)?.let { k -> e.message(2)?.let { k to it } } } }.toMap()

        private fun ref(m: ProtoWire.Message?): Ref? = m?.let { s -> s.int(2)?.let { Ref(s.int(1) ?: IMAGE, it) } }

        fun look(): SkinLook {
            val panel = panels[PINYIN] ?: panels.entries.firstOrNull { it.key.endsWith("_26") }?.value ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
            val look = SkinLook(width)
            val cand = panel.message(CAND)
            board(look, panel, cand)
            keys(look, panel)
            look.candidate = textColors(ref(cand?.message(CELL_FORE))).first
            look.first = textColors(ref(cand?.message(FIRST_CELL_FORE))).first ?: look.candidate
            cand?.messages(CAND_KEYS)?.firstOrNull { it.string(1) == EXPAND }?.message(2)?.let { k ->
                icon(k.messages(KEY_FORES).mapNotNull(::ref))?.let { look.icon("candidates-expand", it.normal, it.pressed) }
            }
            palette(look)
            notes(look)
            return look
        }

        // the keyboard's background, over the bar's too where it is the whole panel's; the bar's at its own height
        private fun board(look: SkinLook, panel: ProtoWire.Message, cand: ProtoWire.Message?) {
            val ph = (width * PANEL_RATIO).roundToInt()
            val whole = face(ref(panel.message(WHOLE_BACK))).first
            val bar = face(ref(cand?.message(CAND_BAR))).first
            val ch = ((bar as? SkinFill.Image)?.pixels?.let { it.height.toDouble() * width / it.width } ?: (width * BAR_RATIO)).roundToInt()
                .coerceIn(1, MAX_SIDE / 2)
            if (whole is SkinFill.Image) {
                BaiduSkin.lay(look, whole, null, width, ph + ch, 0)
            } else {
                BaiduSkin.lay(look, face(ref(panel.message(BACK))).first ?: whole, bar, width, ph, ch)
            }
        }

        private fun keys(look: SkinLook, panel: ProtoWire.Message) {
            // one key an action, as a classic board's section is: the rest the same key again
            val keys = panel.messages(KEYS).mapNotNull(::keyed).distinctBy { it.action }
            for (k in keys) BaiduNames.icon(k.action)?.let { icon -> if (icon !in look.icons) icon(k.fores)?.let { look.icon(icon, it.normal, it.pressed) } }
            val kinds = keys.groupBy { it.kind }
            if (kinds[Kind.LETTER].orEmpty().any { k -> k.fores.none { it.type == TEXT } && icon(k.fores) != null }) look.notes += SkinNote.LETTER_IMAGES
            var function: Pair<Ref, SkinKey>? = null
            for ((kind, list) in Kind.entries.mapNotNull { k -> kinds[k]?.let { k to it } }) {
                val back = list.groupingBy { it.back }.eachCount().maxBy { it.value }.key
                val k = list.first { it.back == back }
                val key = key(k, kind, if (kind == Kind.LETTER) list else listOf(k))
                if (kind == Kind.FUNCTION) function = back to key
                if (asFunction(kind, back, key, function)) continue
                look.keys[kind.selector] = key
                if (kind == Kind.LETTER && key.scale != null) look.keys[LETTER] = key
            }
            val base = look.keys[KEY] ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
            if (LETTER in look.keys) look.keys[KEY] = SkinKey(base.fill, base.pressed, base.color, base.pressedColor, false, markColor = base.markColor, pressedMarkColor = base.pressedMarkColor)
        }

        // a panel's key of a kind styled; none for a state's or a variant's look, not the theme's
        private fun keyed(e: ProtoWire.Message): Keyed? {
            val name = BaiduNames.key(e.string(1).orEmpty())?.takeIf { it.state == null && it.variant == null } ?: return null
            val key = e.message(2) ?: return null
            val kind = BaiduNames.kind(name.action) ?: return null
            val back = ref(key.message(KEY_BACK)) ?: return null
            return Keyed(back, key.messages(KEY_FORES).mapNotNull(::ref), name.action, kind)
        }

        // backspace and shift as the function keys: one style for them all
        private fun asFunction(kind: Kind, back: Ref, key: SkinKey, fn: Pair<Ref, SkinKey>?) =
            (kind == Kind.BACKSPACE || kind == Kind.SHIFT) && fn != null && fn.first == back && BaiduSkin.same(fn.second, key)

        private fun key(k: Keyed, kind: Kind, sized: List<Keyed>): SkinKey {
            val (nm, hl) = face(k.back)
            val text = k.fores.filter { it.type == TEXT }
            val (color, pressedColor) = textColors(text.firstOrNull())
            val (mark, pressedMark) = textColors(text.getOrNull(1))
            val scale = BaiduNames.scale(sized.mapNotNull { s -> s.fores.firstOrNull { it.type == TEXT }?.let { size(it) } })
            if (scale?.noted == true) textSize = true
            return SkinKey(
                nm, hl, color ?: palette?.int(LABEL)?.takeIf { kind == Kind.LETTER }, pressedColor, icons = kind != Kind.LETTER,
                markColor = mark, pressedMarkColor = pressedMark, scale = scale?.percent,
            )
        }

        private fun size(ref: Ref): Double? = texts[ref.key]?.int(TEXT_SIZE)?.takeIf { it in 1..MAX_SIDE }?.let { it / rowPx }

        // a back style's face and pressed face: a picture (its inner rect stretched) or a colour
        private fun face(ref: Ref?): Pair<SkinFill?, SkinFill?> = when (ref?.type) {
            IMAGE -> images[ref.key].let { s -> atom(s?.message(1)) to atom(s?.message(2)) }
            COLOR -> colors[ref.key].let { s -> s?.int(1)?.let(SkinFill::Color) to s?.int(2)?.let(SkinFill::Color) }
            else -> null to null
        }

        private fun textColors(ref: Ref?): Pair<Int?, Int?> {
            val t = ref?.takeIf { it.type == TEXT }?.let { texts[it.key] } ?: return null to null
            return t.int(TEXT_NORMAL) to t.int(TEXT_HIGHLIGHT)
        }

        // an image atom's picture, a still one only (an animated one said, not decoded)
        private fun atom(a: ProtoWire.Message?): SkinFill.Image? {
            val p = f.pixels(file(a)) ?: return null
            return SkinFill.Image(p, a?.message(INNER_RECT)?.let { inner(it, p.width, p.height) })
        }

        // an image atom's file, a still one's only
        private fun file(a: ProtoWire.Message?): String? {
            val res = a?.message(1) ?: return null
            val type = res.int(1) ?: STATIC
            if (type in FRAMES) frames = true
            if (type != STATIC && type != WEBP) return null
            val id = res.string(2)?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
            return f.first(root + "res/$id.png", root + "res/$id.webp", root + "res/$id")
        }

        // a stretch area as the insets around it
        private fun inner(rect: ProtoWire.Message, w: Int, h: Int): Pixels.Box? {
            val size = rect.message(2) ?: return null
            val x = (rect.message(1)?.int(1) ?: 0).coerceIn(0, w)
            val y = (rect.message(1)?.int(2) ?: 0).coerceIn(0, h)
            val rw = size.int(1)?.coerceIn(0, w) ?: return null
            val rh = size.int(2)?.coerceIn(0, h) ?: return null
            return Pixels.Box(x, y, max(0, w - x - rw), max(0, h - y - rh))
        }

        // the first image fore that draws something (a 1×1 or empty one holds a place), at rest and pressed
        private fun icon(fores: List<Ref>): SkinIcon? = fores.filter { it.type == IMAGE }.firstNotNullOfOrNull { r ->
            val s = images[r.key]
            val name = file(s?.message(1))?.takeIf { n -> drawn.getOrPut(n) { f.pixels(n)?.let { max(it.width, it.height) >= MIN_ICON && it.opaqueBounds() != null } == true } }
            name?.let(f::pixels)?.let { SkinIcon(it, atom(s?.message(2))?.pixels) }
        }

        // the system palette's colours first where the skin has them
        private fun palette(look: SkinLook) {
            val p = palette ?: return
            p.int(LABEL)?.let { look.candidate = it }
            p.int(BRAND)?.let { look.first = it }
            if (look.board == null) p.int(BACKGROUND)?.let { look.boardColor = it }
        }

        private fun notes(look: SkinLook) {
            for (t in texts.values) {
                if (!t.string(FONT_NAME).isNullOrEmpty() || t.message(1)?.int(1) == FONT) look.notes += SkinNote.FONT
                if (t.message(1)?.int(1)?.let { it in FRAMES } == true) frames = true
            }
            for (s in images.values) for (n in 1..2) if (s.message(n)?.message(1)?.int(1)?.let { it in FRAMES } == true) frames = true
            if (frames) look.notes += SkinNote.FRAME_ANIMATION
            if (f.bytes(root + "port/animationConfig")?.isNotEmpty() == true) look.notes += SkinNote.ANIMATION
            if (textSize) look.notes += SkinNote.TEXT_SIZE
            look.notes += SkinNote.PANELS
            look.notes += SkinNote.NO_DARK
            if (f.imageFailed) look.notes += SkinNote.IMAGES
        }
    }

    companion object {
        private const val CONFIG = "port/appearanceConfig"
        const val MAX_CONFIG_BYTES = 1024 * 1024

        // AppearanceConfig's fields
        private const val IMAGE_STYLES = 1
        private const val TEXT_STYLES = 2
        private const val COLOR_STYLES = 3
        private const val PANELS = 4
        private const val PALETTE = 5
        private const val DESIGN_WIDTH = 6

        // Panel's, Cand's, Key's
        private const val KEYS = 3
        private const val CAND = 4
        private const val BACK = 7
        private const val WHOLE_BACK = 10
        private const val CAND_BAR = 1
        private const val CELL_FORE = 4
        private const val FIRST_CELL_FORE = 5
        private const val CAND_KEYS = 10
        private const val KEY_BACK = 1
        private const val KEY_FORES = 2

        // ImageAtom's, TextStyle's, ColorPalette's
        private const val INNER_RECT = 2
        private const val FONT_NAME = 2
        private const val TEXT_SIZE = 3
        private const val TEXT_NORMAL = 4
        private const val TEXT_HIGHLIGHT = 5
        private const val LABEL = 1
        private const val BRAND = 5
        private const val BACKGROUND = 6

        // SkinStyle's types; Resource's
        private const val IMAGE = 0
        private const val COLOR = 1
        private const val TEXT = 2
        private const val STATIC = 0
        private const val FONT = 6
        private const val WEBP = 8
        private val FRAMES = setOf(1, 2, 3, 9)

        private const val PINYIN = "py_26"
        private const val EXPAND = "CAND_F9"
        private const val KEY = "key"
        private const val LETTER = "key.letter"
        private const val MIN_ICON = 4
        private const val MAX_ID = 128
        private const val DEFAULT_WIDTH = 1080
        private const val MIN_WIDTH = 100
        private const val MAX_SIDE = 4096
        private const val PANEL_RATIO = 0.56
        private const val BAR_RATIO = 0.11

        /** Whether [names] are a `.bda` skin's. */
        fun accepts(names: Collection<String>) = names.any { it.endsWith(CONFIG) }
    }
}
