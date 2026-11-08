/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import kotlin.math.roundToInt

/**
 * A key as a test sees it (060-render-p2 S3): a key of the scene a [KeyboardCanvasView] draws
 * ([SceneShownKey]). [KeyboardHarness] finds them ([KeyboardHarness.key], [KeyboardHarness.keyNamed])
 * and puts fingers on them by where they are ([bounds]). Places are the window's, read each time
 * asked: a key moves with its keyboard (one-handed, floating).
 */
interface ShownKey {
    /** The scene's key id (`q`, `space`, layout-1's). */
    val id: String?

    /** Which of the two a split row draws of one key it is (G, V): 0, or 1 for the second. */
    val copy: Int

    /** Its main text as shown ("" for an icon). */
    val label: String

    /** Its corner as shown (a letter's symbol, the nine keys' digit, a table's letter under its roots); null for none. */
    val alt: String?

    /** What a screen reader reads for it: its node's; null for none. */
    val description: String?

    /** Whether it takes a press (a blank key does not, nor a key the scene has as disabled). */
    val enabled: Boolean

    /** The state a screen reader is told (Shift's once, locked): its node's; null for none. */
    val state: String?

    /** Its resource name as its node tells it (`button_caps`); null for none. */
    val resourceName: String?

    /** Its cell in the window, where a popup is placed from. */
    val bounds: Rect

    /** What is drawn of it in the window: its cell less the gaps between keys. */
    val face: Rect

    /** Where a finger counts as on it, in the window: its cell, a row's blank end too for the scene's (A's left). */
    val hit: Rect

    /** Whether it is shown pressed now. */
    val pressed: Boolean

    /** A click, as the end of a tap calls it (the scene key's click): whether anything took it. */
    fun click(): Boolean

    /** The size [text] is drawn at on it (the scene's text's); null where it draws no such text. */
    fun textSize(text: String): Float?

    /** How many icons are drawn on it: its own, its marks' (a shortcut's command). */
    val drawnIcons: Int

    /** Every text drawn on it (its label, corner, marks' words), in no order but its own. */
    val drawnTexts: List<String>

    /**
     * Where [text] is drawn on it, in the window: the scene's text from its
     * left and baseline, its ink's top and width a guess (0.75 and 0.6 of its size: letters and
     * digits). Null where it draws no such text.
     */
    fun drawn(text: String): RectF?

    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()
}

/**
 * A key the scene of [canvas] draws as a [ShownKey], key [id]'s [copy]th, looked up in the scene
 * each time asked: a Shift relabels the letters in a scene made anew.
 */
class SceneShownKey(val canvas: KeyboardCanvasView, override val id: String, override val copy: Int = 0) : ShownKey {
    /** The scene's key now: what the canvas draws for it. */
    val node: KeyNode get() = canvas.keyAt(id, copy) ?: error("no key $id ($copy) in the scene now")

    override val label: String get() = node.label.orEmpty()
    override val alt: String? get() = canvas.alt(id, copy)
    override val description: String? get() = canvas.spoken(id, copy)
    override val enabled: Boolean get() = canvas.enabled(id, copy)

    // as its node tells it, the node made as TalkBack asks for it
    override val state: String? get() = told()?.stateDescription?.toString()
    override val resourceName: String? get() = told()?.viewIdResourceName?.substringAfter(":id/")

    private fun told(): AccessibilityNodeInfoCompat? {
        val i = canvas.scene?.keys?.indexOf(node) ?: return null
        return canvas.accessibilityNodeProvider?.createAccessibilityNodeInfo(i)?.let(AccessibilityNodeInfoCompat::wrap)
    }
    // where the window shows it: the canvas, or a keyboard over it, moved or scaled (a floating one dragged smaller) too
    override val bounds: Rect get() = inWindow(node.bounds)
    override val face: Rect get() = inWindow(node.shape.bounds)
    override val hit: Rect get() = inWindow(node.hit.bounds)
    override val pressed: Boolean get() = canvas.shownPressed(id, copy)
    override fun click(): Boolean = canvas.click(id, copy)

    private fun texts() = node.ops(false).filterIsInstance<Op.Text>()

    override fun textSize(text: String): Float? = texts().firstOrNull { it.text == text }?.size?.toFloat()

    override val drawnIcons: Int get() = node.ops(false).count { it is Op.Icon }

    override val drawnTexts: List<String> get() = texts().map { it.text }

    override fun drawn(text: String): RectF? = texts().firstOrNull { it.text == text }?.let { t ->
        val at = IntArray(2).also(canvas::getLocationInWindow)
        // drawn from its left (ScenePainter), its width guessed: about 0.6 of its size a character
        val width = t.size * t.scaleX * WIDE * t.text.length
        RectF((at[0] + t.x).toFloat(), (at[1] + t.baseline - t.size * INK).toFloat(), (at[0] + t.x + width).toFloat(), (at[1] + t.baseline).toFloat())
    }
    override fun toString() = "scene key $id${if (copy != 0) " ($copy)" else ""}"

    /**
     * Where the scene drew the key's marks (rules E 疑问 3, the slot by slot hook): its symbol's
     * ([alt]'s text) and each shortcut's (an icon, or a text but its label and symbol), in the order
     * drawn, as T(op) or B(ottom) and L(eft), C(entre) or R(ight) of the face's middle; null, no
     * symbol drawn. The key's own label or icon, in its middle, is no mark.
     */
    fun markPlaces(): MarkPlaces {
        val face = node.shape.bounds
        fun place(x: Double, y: Double): String? {
            val dx = x - face.centerX
            val dy = y - face.centerY
            // its own label or icon: the middle
            if (kotlin.math.abs(dx) < face.width * CENTRE && kotlin.math.abs(dy) < face.height * CENTRE) return null
            val side = when {
                kotlin.math.abs(dx) < face.width * CENTRE -> "C"
                dx < 0 -> "L"
                else -> "R"
            }
            return (if (dy < 0) "T" else "B") + side
        }
        val symbolText = alt
        var symbol: String? = null
        val shortcuts = mutableListOf<String>()
        val ops = node.ops(false)
        // in drawing order: the icons and texts as they come
        for (op in ops) {
            val at = when (op) {
                is Op.Icon -> place(op.box.centerX, op.box.centerY)
                is Op.Text -> if (op.text == label) null else {
                    val wide = op.text.sumOf { if (it.code < 0x2E80) WIDE else 1.0 }
                    place(op.x + op.size * op.scaleX * wide / 2, op.baseline - op.size * INK / 2)
                }
                else -> null
            } ?: continue
            if (op is Op.Text && op.text == symbolText && symbol == null) symbol = at else shortcuts += at
        }
        return MarkPlaces(symbol, shortcuts)
    }

    /** [markPlaces]: the symbol's place, null for none drawn, and each shortcut's. */
    data class MarkPlaces(val symbol: String?, val shortcuts: List<String>)

    private companion object {
        // within this share of the face's width or height of its middle: centred
        const val CENTRE = 0.08

        // a letter's or digit's ink above its baseline, of the text's size
        const val INK = 0.75

        // a character's width, of the text's size
        const val WIDE = 0.6
    }

    private fun inWindow(b: org.fcitx.fcitx5.android.scene.Box): Rect {
        val p = floatArrayOf(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        // each view's matrix and place in its parent, up to the window
        var v: View = canvas
        while (true) {
            v.matrix.mapPoints(p)
            for (i in p.indices step 2) {
                p[i] += v.left.toFloat()
                p[i + 1] += v.top.toFloat()
            }
            val parent = v.parent as? View ?: break
            for (i in p.indices step 2) {
                p[i] -= parent.scrollX.toFloat()
                p[i + 1] -= parent.scrollY.toFloat()
            }
            v = parent
        }
        return Rect(p[0].roundToInt(), p[1].roundToInt(), p[2].roundToInt(), p[3].roundToInt())
    }
}
