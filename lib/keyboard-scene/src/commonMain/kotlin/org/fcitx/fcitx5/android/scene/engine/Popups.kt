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
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Theme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The popups' sizes (rules D §4), dp, theme 3.0's PopupComponent's, PopupKeyboardUi's and PopupMenuUi's.
 * Their text is dp, not sp as it was: it no longer follows the system's font size (060-render-p2 §5.3).
 * Where they go over the key is the app's (PopupComponent): the engine sizes and draws them.
 */
object Popups {
    /** A key's bubble: this tall, its text in the top [PREVIEW_ROW_DP], over the key's row. */
    const val PREVIEW_HEIGHT_DP = 116.0
    const val PREVIEW_ROW_DP = 48.0

    /** The bubble no narrower than this, nor than the key, nor than its text and [TEXT_PADDING_DP] a side. */
    const val PREVIEW_MIN_WIDTH_DP = 38.0

    /**
     * Space each side of the bubble's text; a long press's keys leave as much each side of their
     * widest label, so the bubble and the key over it come out one width.
     */
    const val TEXT_PADDING_DP = 6.0

    /** A character in the bubble (a letter, a symbol, a label of one): a letter key's, at the keys' text scale. */
    const val SYMBOL_DP = 23.0

    /** A shortcut's words in the bubble (PreviewLabel), at the keys' text scale, shrunk no smaller than [MIN_WORDS_DP]. */
    const val WORDS_DP = 16.0
    const val MIN_WORDS_DP = 12.0

    /** A long press's keys: at least this wide, this tall, the whole [LIFT_DP] above the key's top; their text. */
    const val KEY_MIN_WIDTH_DP = 34.0
    const val KEY_HEIGHT_DP = 40.0
    const val LIFT_DP = 6.0
    const val KEY_TEXT_DP = 20.0

    /** A menu's cells (a key's, the edit menu's): a key's size, [MENU_LIFT_DP] above the key's top. */
    const val MENU_CELL_DP = 48.0
    const val MENU_LIFT_DP = 52.0

    /** A cell's face in it: [MENU_FACE_DP] across, [MENU_SELECTED_DP] the one in focus (theme 3.0's 33 and 34). */
    const val MENU_FACE_DP = 33.0
    const val MENU_SELECTED_DP = 34.0

    /** A menu of words (an item of no icon: 手写's 注): pills this many cells wide, [MENU_PILL_SIDE_DP] in from their sides; the text. */
    const val MENU_WORDS_WIDTH = 1.75
    const val MENU_PILL_SIDE_DP = 2.0
    const val MENU_TEXT_DP = 14.0

    /** A cell that picks nothing (the edit menu's in a password field) drawn this much of itself. */
    const val DISABLED_ALPHA = 0.3

    /** [dp] in whole px, as a view's size was (splitties' dp: rounded down): the popups are placed in whole pixels. */
    fun px(dp: Double, density: Double): Double = floor(dp * density + EPSILON)

    private const val EPSILON = 1e-9
}

/** A long press's keys as the engine sizes them, px: each [width] × [height], their text at [size]. */
data class PopupKeys(val width: Double, val height: Double, val size: Double)

/** A menu's cell: [icon] the icon's name, or null for [label] in words; [label] what it is called (what a screen reader says). */
data class MenuCell(val label: String, val icon: String?)

/**
 * The popups as scenes ([SceneBuilder.preview], [SceneBuilder.popup], [SceneBuilder.menu]): each its
 * own small scene, at (0, 0), its cells keys as a keyboard's are, so a theme styles them as it does
 * keys (060-render-v2 §3.1: a bubble or a popup with a key's properties) and a backend draws them as
 * it draws the keyboard. Its looks are the theme's `preview`, `popup` and `menu`, the one in focus
 * `:selected`, a cell that picks nothing `:disabled`.
 *
 * The bubble is one key (its shape a rectangle for now; a tail, a path, is 4.0's). A long press's keys
 * are a panel (the scene's base: `popup`'s fill and shadow) and a key a cell, drawn its text only, the
 * one in focus its `popup:selected` fill as well: a cell drawn the panel's fill and shadow again would
 * lay a shadow over the row under it. A menu's cells are each a face of its own, the one in focus
 * a little larger and tinted by its `overlay`.
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

    /** The bubble's words' size and the least they shrink to, px: as the keys' text is scaled (紧凑, floating). */
    fun wordsSize(): Pair<Double, Double> = scale().let { s -> Popups.WORDS_DP * s * d to Popups.MIN_WORDS_DP * s * d }

    fun padding(): Double = px(Popups.TEXT_PADDING_DP)

    /** How wide [text] is drawn in the bubble at [size] px. */
    fun previewWidth(text: String, size: Double): Double = look("preview").second.let { measurer.measure(text, it.font, it.weight, size).advance }

    private fun scale() = KeyTextScale.of(env.compact, env.floating)

    /**
     * Over a key [keyWidth] px across, [text] in a bubble: at [size] px (a shortcut's words fitted,
     * PreviewLabel), else a character's size as the keys' text is scaled. As wide as the key, its text
     * and its padding, at least [Popups.PREVIEW_MIN_WIDTH_DP]; the text centred in its top row as
     * AutoScaleTextView centred it.
     */
    fun preview(text: String, keyWidth: Double, size: Double?): Scene {
        val (look, textLook) = look("preview")
        val px = size ?: (Popups.SYMBOL_DP * scale() * d)
        val m = Content.Measured(text, measurer.measure(text, textLook.font, textLook.weight, px), px, textLook.font, textLook.weight)
        val width = maxOf(px(Popups.PREVIEW_MIN_WIDTH_DP), keyWidth, ceil(m.m.advance) + 2 * padding())
        val box = Box(0.0, 0.0, width, px(Popups.PREVIEW_HEIGHT_DP))
        val face = face(box, look)
        val ops = paint.face(geometry(face, look), look, false) +
            paint.content(m.at(width / 2, px(Popups.PREVIEW_ROW_DP) / 2, Part.Label)) { textLook }
        return Scene(box.width, box.height, emptyList(), listOf(node("preview", "preview", face, box, emptySet(), ops, text)), emptyList())
    }

    /**
     * A long press's [labels] [columns] across: each key as wide as the widest label and its padding
     * and no narrower than [minWidth], one text size for all ([Popups.KEY_TEXT_DP]); where so wide they
     * would not fit across [room] (null: no room known yet, the popups' place not laid out), narrower,
     * all their labels smaller alike. Each label fitted by itself came out at as many sizes (`.com`
     * smaller than `.cn`).
     */
    fun popupKeys(labels: List<String>, columns: Int, room: Double?, minWidth: Double, height: Double): PopupKeys {
        val textLook = look("popup").second
        val size = Popups.KEY_TEXT_DP * d
        val widest = labels.maxOfOrNull { measurer.measure(it, textLook.font, textLook.weight, size).advance } ?: 0.0
        val fitting = ceil(widest) + 2 * padding()
        val wanted = maxOf(minWidth, fitting)
        val width = if (room == null || columns <= 0) wanted else min(wanted, floor(room / columns))
        // its padding too, so never below nothing however narrow
        return PopupKeys(width, height, if (fitting > width) size * width / fitting else size)
    }

    /**
     * [labels] as [keys] in [rows], the bottom one first, each the index of a label in its column
     * (-1: an empty slot, its column kept so each key is drawn where the finger picks it); the label
     * [selected] in focus.
     */
    fun popup(labels: List<String>, rows: List<List<Int>>, keys: PopupKeys, selected: Int): Scene {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val panel = Box(0.0, 0.0, columns * keys.width, rows.size * keys.height)
        val (look, _) = look("popup")
        val base = paint.face(geometry(face(panel, look), look), look, false)
        val nodes = rows.flatMapIndexed { r, row ->
            val top = panel.bottom - (r + 1) * keys.height
            row.mapIndexedNotNull { c, index ->
                val label = labels.getOrNull(index) ?: return@mapIndexedNotNull null
                val cell = Box(c * keys.width, top, (c + 1) * keys.width, top + keys.height)
                val states = if (index == selected) setOf(KeyState.Selected) else emptySet()
                val (cellLook, textLook) = look("popup", states)
                val face = face(cell, cellLook)
                val m = Content.Measured(label, measurer.measure(label, textLook.font, textLook.weight, keys.size), keys.size, textLook.font, textLook.weight)
                val ops = listOfNotNull(paint.fill(face, cellLook.fill).takeIf { KeyState.Selected in states }) +
                    paint.content(m.at(cell.centerX, cell.centerY, Part.Label)) { textLook }
                node("popup/$index", "popup", face, cell, states, ops, label)
            }
        }
        return Scene(panel.width, panel.height, base, nodes, emptyList())
    }

    /** A menu of [cells]: a cell's width and height, px, and its words' size. */
    fun menuKeys(cells: List<MenuCell>): PopupKeys {
        val cell = px(Popups.MENU_CELL_DP)
        val words = cells.any { it.icon == null }
        return PopupKeys(if (words) floor(cell * Popups.MENU_WORDS_WIDTH) else cell, cell, Popups.MENU_TEXT_DP * d)
    }

    /**
     * [cells] in a row, [order] the cell in each column; [selected] in focus, [disabled] drawn faded.
     * Icons at their own size in the middle (ImageView's CENTER_INSIDE), words in a pill.
     */
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
            // theme 3.0's InsetDrawable: the cell less its inset, halved to whole pixels a side
            val half = floor((keys.height - px(if (index == selected) Popups.MENU_SELECTED_DP else Popups.MENU_FACE_DP)) / 2)
            val side = if (words) px(Popups.MENU_PILL_SIDE_DP) else half
            val face = face(cell.inset(side, half, side, half), look)
            val content = if (item.icon != null) {
                val (w, h) = icons.dp(item.icon).let { (w, h) -> w * d to h * d }
                Drawn.of(PlacedIcon(item.icon, Box(cell.centerX - w / 2, cell.centerY - h / 2, cell.centerX + w / 2, cell.centerY + h / 2), Part.Icon))
            } else {
                val m = Content.Measured(item.label, measurer.measure(item.label, textLook.font, textLook.weight, keys.size), keys.size, textLook.font, textLook.weight)
                m.at(cell.centerX, cell.centerY, Part.Label)
            }
            val drawn = paint.face(geometry(face, look), look, KeyState.Selected in states) + paint.content(content) { textLook }
            val ops = if (KeyState.Disabled in states) drawn.map { faded(it, Popups.DISABLED_ALPHA) } else drawn
            node("menu/$index", "menu", face, cell, states, ops, item.label)
        }
        return Scene(order.size * keys.width, keys.height, emptyList(), nodes, emptyList())
    }

    private fun face(box: Box, look: KeyLook) = Shape.Rect(box, Faces.radii(look.radius, box, d))

    // the face's layers: its shadow as a key's (the face and its offset), but not kept within a cell,
    // the popup over the keyboard
    private fun geometry(face: Shape.Rect, look: KeyLook): FaceGeometry {
        val s = look.shadow
        val shadow = face.takeIf { s.color != null && (s.dx != 0.0 || s.dy != 0.0 || s.blur != 0.0) }?.let {
            Shape.Rect(it.box.union(it.box.moved(s.dx * d, s.dy * d)), it.radii)
        }
        return FaceGeometry(face, shadow, look.border.width * d, face, face.box)
    }

    // a popup's piece as a key: never pressed, its pressed drawing its own (a backend asks for both)
    private fun node(id: String, kind: String, shape: Shape.Rect, bounds: Box, states: Set<KeyState>, ops: List<Op>, label: String) = KeyNode(
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

    // drawn [alpha] of itself (theme 3.0 faded the cell's view as a whole; here each layer); a board's blank too
    internal fun faded(op: Op, alpha: Double): Op {
        fun Argb.of() = Argb((value and RGB) or ((this.alpha * alpha).roundToInt() shl ALPHA_SHIFT))
        return when (op) {
            is Op.Fill -> op.copy(color = op.color.of())
            is Op.Shadow -> op.copy(color = op.color.of())
            is Op.Stroke -> op.copy(color = op.color.of())
            is Op.Ripple -> op.copy(color = op.color.of())
            is Op.Gradient -> op.copy(stops = op.stops.map { (c, at) -> c.of() to at })
            is Op.Text -> op.copy(color = op.color.of())
            is Op.Icon -> op.copy(tint = op.tint.of())
            // the colours over the picture and under the image faded; the picture and the image themselves as they are
            is Op.Picture -> op.copy(over = op.over?.of())
            is Op.NineSlice -> op.copy(fallback = op.fallback?.of())
        }
    }

    private companion object {
        const val RGB = 0x00FFFFFF
        const val ALPHA_SHIFT = 24
    }
}
