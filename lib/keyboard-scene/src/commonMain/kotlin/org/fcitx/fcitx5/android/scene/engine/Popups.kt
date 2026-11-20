/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Point
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale
import org.fcitx.fcitx5.android.scene.theme.Appear
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.ShapePath
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Transition
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** The popups' sizes, dp, from theme 3.0's popup views; their text is dp, not sp: it no longer follows the system font size. */
object Popups {
    const val PREVIEW_HEIGHT_DP = 116.0
    const val PREVIEW_ROW_DP = 48.0

    const val PREVIEW_MIN_WIDTH_DP = 38.0

    /** A long press's keys leave as much each side of their widest label, so the bubble and the key over it are one width. */
    const val TEXT_PADDING_DP = 6.0

    const val SYMBOL_DP = 23.0

    const val WORDS_DP = 16.0
    const val MIN_WORDS_DP = 12.0

    const val KEY_MIN_WIDTH_DP = 34.0
    const val KEY_HEIGHT_DP = 40.0
    const val LIFT_DP = 6.0
    const val KEY_TEXT_DP = 20.0

    const val MENU_CELL_DP = 48.0
    const val MENU_LIFT_DP = 52.0

    const val MENU_FACE_DP = 33.0
    const val MENU_SELECTED_DP = 34.0

    const val MENU_WORDS_WIDTH = 1.75
    const val MENU_PILL_SIDE_DP = 2.0
    const val MENU_TEXT_DP = 14.0

    /** At most a third of the head and clear of its corners, scaled by the bubble's `size`. */
    const val TAIL_DP = 10.0
    const val TAIL_WIDTH_DP = 16.0

    const val DISABLED_ALPHA = 0.3

    /** Rounded down as splitties' dp did for views: the popups are placed in whole pixels. */
    fun px(dp: Double, density: Double): Double = floor(dp * density + EPSILON)

    private const val EPSILON = 1e-9
}

data class PopupKeys(val width: Double, val height: Double, val size: Double)

data class PopupMotion(val appear: Appear?, val going: Transition?)

data class MenuCell(val label: String, val icon: String?)

/**
 * The popups as small scenes at (0, 0), their cells keys, so a theme styles them as keys. A long press's panel fill and
 * shadow are not drawn again per cell, which would shadow the row under it.
 */
internal class PopupBuilder(
    private val theme: Theme,
    private val env: Env,
    private val measurer: TextMeasurer,
    private val icons: IconSizes,
    private val cascade: Cascade,
    private val paint: Paint,
) {
    private val d = env.density

    private fun px(dp: Double) = Popups.px(dp, d)

    private fun look(widget: String, states: Set<KeyState> = emptySet()): Pair<KeyLook, TextLook> {
        val v = cascade.of(Selector.Target(widget, states = states.mapTo(HashSet()) { it.xml }))
        return KeyLook(v, theme) to TextLook(v, theme)
    }

    fun motion(widget: String): PopupMotion? {
        val look = look(widget).first
        val appear = look.appear?.takeIf { it.ms > 0 }
        val going = look.transition?.takeIf { it.ms > 0 && "transition" in ThemeReader.POPUP_MOTION[widget].orEmpty() }
        return if (appear == null && going == null) null else PopupMotion(appear, going)
    }

    fun wordsSize(): Pair<Double, Double> = scale().let { s -> Popups.WORDS_DP * s * d to Popups.MIN_WORDS_DP * s * d }

    fun padding(): Double = px(Popups.TEXT_PADDING_DP)

    fun previewWidth(text: String, size: Double): Double = look("preview").second.let { Content.measure(measurer, text, it, it.weight, size).m.advance }

    private fun scale() = KeyTextScale.of(env.compact, env.floating)

    fun preview(text: String, keyWidth: Double, size: Double?): Scene {
        val (look, textLook) = look("preview")
        val s = look.size
        val px = (size ?: (Popups.SYMBOL_DP * scale() * d)) * s
        val m = Content.measure(measurer, text, textLook, textLook.weight, px)
        val width = ceil(maxOf(px(Popups.PREVIEW_MIN_WIDTH_DP) * s, keyWidth * s, ceil(m.m.advance) + 2 * padding() * s))
        val row = px(Popups.PREVIEW_ROW_DP) * s
        // moved down, a rectangle's or path's bottom is drawn below the key's bottom
        val reach = px(Popups.PREVIEW_HEIGHT_DP) + ceil(row - px(Popups.PREVIEW_ROW_DP))
        val moved = px(abs(look.offset)).let { if (look.offset < 0) -it else it }
        val height = reach - moved
        val box = Box(0.0, 0.0, width, reach)
        val head = Box(0.0, 0.0, width, row)
        val bubbly = look.path == null && look.shape == Values.BUBBLE
        val face: Shape = if (bubbly) bubble(head, look, s) else face(box, look)
        val g = geometry(face, look, box) { w -> if (bubbly) bubble(head, look, s, w / 2) else Faces.line(face, w, look.path, box) }
        val ops = paint.face(g, look, false) +
            paint.content(m.at(width / 2, row / 2, Part.Label)) { textLook }
        val bounds = Box(0.0, 0.0, width, height)
        return Scene(width, height, emptyList(), listOf(node("preview", "preview", face, bounds, emptySet(), ops, text)), emptyList())
    }

    private fun bubble(head: Box, look: KeyLook, s: Double, w: Double = 0.0): Shape {
        val radii = Faces.radii(look.radius, head, d)
        val inner = head.inset(w, w, w, w)
        val round = radii.map { maxOf(0.0, it - w) }
        val (half, height) = tail(head, radii, look, s) ?: return Shape.Rect(inner, round)
        if (w == 0.0) return ShapePath.roundedOutline(head, radii, half to height)
        val slant = hypot(half, height)
        val foot = half * (height + w) / height - w * slant / height
        val tip = height + w - w * slant / half
        return if (foot > 0 && tip > 0) ShapePath.roundedOutline(inner, round, foot to tip) else Shape.Rect(inner, round)
    }

    private fun tail(head: Box, radii: List<Double>, look: KeyLook, s: Double): Pair<Double, Double>? {
        if (!look.tail) return null
        val room = head.width / 2 - maxOf(radii[2], radii[3])
        val half = minOf(Popups.TAIL_WIDTH_DP * d * s / 2, head.width / TAIL_SHARE / 2, room)
        return if (half > 0) half to Popups.TAIL_DP * d * s else null
    }

    /** One text size for all keys (labels fitted one by one came out at several sizes). */
    fun popupKeys(labels: List<String>, columns: Int, room: Double?, minWidth: Double, height: Double): PopupKeys {
        val textLook = look("popup").second
        val size = Popups.KEY_TEXT_DP * d
        val widest = labels.maxOfOrNull { Content.measure(measurer, it, textLook, textLook.weight, size).m.advance } ?: 0.0
        val fitting = ceil(widest) + 2 * padding()
        val wanted = maxOf(minWidth, fitting)
        val width = if (room == null || columns <= 0) wanted else min(wanted, floor(room / columns))
        return PopupKeys(width, height, if (fitting > width) maxOf(size * width / fitting, px(Popups.MIN_WORDS_DP)) else size)
    }

    /** -1 is an empty slot, its column kept so each key stays where the finger picks it. */
    fun popup(labels: List<String>, rows: List<List<Int>>, keys: PopupKeys, selected: Int): Scene {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val panel = Box(0.0, 0.0, columns * keys.width, rows.size * keys.height)
        val (look, _) = look("popup")
        val base = paint.face(geometry(face(panel, look), look, panel), look, false)
        val nodes = rows.flatMapIndexed { r, row ->
            val top = panel.bottom - (r + 1) * keys.height
            row.mapIndexedNotNull { c, index ->
                val label = labels.getOrNull(index) ?: return@mapIndexedNotNull null
                val cell = Box(c * keys.width, top, (c + 1) * keys.width, top + keys.height)
                val states = if (index == selected) setOf(KeyState.Selected) else emptySet()
                val (cellLook, textLook) = cellLook(states)
                val face = face(cell, cellLook)
                val m = cut(label, keys.width - 2 * padding()) { Content.measure(measurer, it, textLook, textLook.weight, keys.size) }
                val selected = if (KeyState.Selected in states) {
                    val g = geometry(face, cellLook, cell)
                    paint.face(FaceGeometry(face, null, g.border, face, cell, g.line), cellLook, false)
                } else {
                    emptyList()
                }
                val ops = selected + paint.content(m.at(cell.centerX, cell.centerY, Part.Label)) { textLook }
                node("popup/$index", "popup", face, cell, states, ops, label)
            }
        }
        return Scene(panel.width, panel.height, base, nodes, emptyList())
    }

    // found by halving (a longer start is no narrower), each measured once
    private fun cut(label: String, room: Double, measure: (String) -> Content.Measured): Content.Measured {
        val whole = measure(label)
        if (room <= 0 || whole.m.advance <= room) return whole
        val ends = clusterEnds(label).dropLast(1)
        var best: Content.Measured? = null
        var low = 0
        var high = ends.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val shown = measure(label.substring(0, ends[mid]) + ELLIPSIS)
            if (shown.m.advance <= room) {
                best = shown
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best ?: measure(ELLIPSIS)
    }

    // the panel's own border and shape are not drawn again round the key in focus
    private fun cellLook(states: Set<KeyState>): Pair<KeyLook, TextLook> {
        val target = Selector.Target("popup", states = states.mapTo(HashSet()) { it.xml })
        val v = cascade.of(target).filterKeys { it !in CELL_OWN } + cascade.stated(target, KeyState.Selected.xml).filterKeys { it in CELL_OWN }
        return KeyLook(v, theme) to TextLook(v, theme)
    }

    fun menuKeys(cells: List<MenuCell>): PopupKeys {
        val cell = px(Popups.MENU_CELL_DP)
        val words = cells.any { it.icon == null }
        return PopupKeys(if (words) floor(cell * Popups.MENU_WORDS_WIDTH) else cell, cell, Popups.MENU_TEXT_DP * d)
    }

    fun menu(cells: List<MenuCell>, order: List<Int>, selected: Int, disabled: Set<Int>): Scene {
        val keys = menuKeys(cells)
        val words = cells.any { it.icon == null }
        val nodes = order.mapIndexedNotNull { col, index ->
            val item = cells.getOrNull(index) ?: return@mapIndexedNotNull null
            val cell = Box(col * keys.width, 0.0, (col + 1) * keys.width, keys.height)
            val states = buildSet {
                if (index == selected) add(KeyState.Selected)
                if (index in disabled) add(KeyState.Disabled)
            }
            val (look, textLook) = look("menu", states)
            val half = floor((keys.height - px(if (index == selected) Popups.MENU_SELECTED_DP else Popups.MENU_FACE_DP)) / 2)
            val side = if (words) px(Popups.MENU_PILL_SIDE_DP) else half
            val inset = cell.inset(side, half, side, half)
            val face = face(inset, look)
            val content = if (item.icon != null) {
                val (w, h) = icons.dp(item.icon).let { (w, h) -> w * d to h * d }
                Drawn.of(PlacedIcon(item.icon, Box(cell.centerX - w / 2, cell.centerY - h / 2, cell.centerX + w / 2, cell.centerY + h / 2), Part.Icon))
            } else {
                val m = Content.measure(measurer, item.label, textLook, textLook.weight, keys.size)
                m.at(cell.centerX, cell.centerY, Part.Label)
            }
            val drawn = paint.face(geometry(face, look, inset), look, KeyState.Selected in states) + paint.content(content) { textLook }
            val ops = if (KeyState.Disabled in states) drawn.map { faded(it, Popups.DISABLED_ALPHA) } else drawn
            node("menu/$index", "menu", face, cell, states, ops, item.label)
        }
        return Scene(order.size * keys.width, keys.height, emptyList(), nodes, emptyList())
    }

    private fun face(box: Box, look: KeyLook): Shape =
        look.path?.takeIf { box.hasSize }?.flatten(box) ?: Shape.Rect(box, Faces.radii(look.radius, box, d))

    private fun geometry(
        face: Shape,
        look: KeyLook,
        box: Box,
        line: (Double) -> Shape = { Faces.line(face, it, look.path, box) },
    ): FaceGeometry {
        val s = look.shadow
        val shadow = face.takeIf { s.color != null && (s.dx != 0.0 || s.dy != 0.0 || s.blur != 0.0) }?.let {
            when (it) {
                is Shape.Rect -> Shape.Rect(it.box.union(it.box.moved(s.dx * d, s.dy * d)), it.radii)
                is Shape.Path -> it.moved(s.dx * d, s.dy * d)
            }
        }
        val border = look.border.width * d
        return FaceGeometry(face, shadow, border, face, face.bounds, if (border > 0) line(border) else null)
    }

    // never pressed, its pressed drawing its own (a backend asks for both)
    private fun node(id: String, kind: String, shape: Shape, bounds: Box, states: Set<KeyState>, ops: List<Op>, label: String) = KeyNode(
        id = id,
        copy = 0,
        kind = kind,
        role = null,
        shape = shape,
        bounds = bounds,
        hit = Shape.Rect(bounds),
        anchor = Point(bounds.centerX, bounds.centerY),
        up = Point(0.0, -1.0),
        states = states,
        faces = mapOf(states to ops, states + KeyState.Pressed to ops),
        a11y = label,
        actions = emptyMap(),
        label = label,
    )

    internal fun faded(op: Op, share: Double): Op {
        fun Argb.of() = Argb((value and RGB) or ((alpha * share).roundToInt() shl ALPHA_SHIFT))
        return when (op) {
            is Op.Fill -> op.copy(color = op.color.of())
            is Op.Shadow -> op.copy(color = op.color.of())
            is Op.InnerShadow -> op.copy(color = op.color.of())
            is Op.Stroke -> op.copy(color = op.color.of())
            is Op.Ripple -> op.copy(color = op.color.of())
            is Op.Gradient -> op.copy(stops = op.stops.map { (c, at) -> c.of() to at })
            is Op.Text -> op.copy(
                color = op.color.of(),
                stroke = op.stroke?.let { it.copy(color = it.color.of()) },
                shadow = op.shadow?.let { it.copy(color = it.color.of()) },
            )
            is Op.Icon -> op.copy(tint = op.tint.of())
            is Op.Picture -> op.copy(over = op.over?.of())
            is Op.NineSlice -> op.copy(fallback = op.fallback?.of())
        }
    }

    private companion object {
        const val TAIL_SHARE = 3.0

        const val ELLIPSIS = "…"

        val CELL_OWN = setOf("border", "inner-shadow", "shape")
        const val RGB = 0x00FFFFFF
        const val ALPHA_SHIFT = 24
    }
}

/** Grapheme clusters as Android's BreakIterator draws them, without java.text (JS). */
internal fun clusterEnds(s: String): List<Int> {
    val ends = ArrayList<Int>()
    var i = 0
    var prev = -1
    var indicators = 0
    while (i < s.length) {
        val c = s[i]
        val wide = c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
        val cp = if (wide) 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) else c.code
        val indicator = cp in 0x1F1E6..0x1F1FF
        val joins = ends.isNotEmpty() && (joinsBefore(prev, cp) || indicator && indicators % 2 == 1 || !wide && isMark(c))
        indicators = if (indicator) indicators + 1 else 0
        i += if (wide) 2 else 1
        if (joins) ends[ends.lastIndex] = i else ends += i
        prev = cp
    }
    return ends
}

private fun joinsBefore(prev: Int, cp: Int): Boolean =
    prev == ZWJ || cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF ||
        cp in 0xE0020..0xE007F || prev == '\r'.code && cp == '\n'.code

private fun isMark(c: Char): Boolean =
    c.category.let { it == CharCategory.NON_SPACING_MARK || it == CharCategory.ENCLOSING_MARK || it == CharCategory.COMBINING_SPACING_MARK }

private const val ZWJ = 0x200D
