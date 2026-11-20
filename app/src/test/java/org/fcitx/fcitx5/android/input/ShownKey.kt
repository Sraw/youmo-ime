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

/** A scene key as a test sees it. Places are the window's, read each time: a key moves with its keyboard (one-handed, floating). */
interface ShownKey {
    val id: String?

    /** Which of a split row's two (G, V): 0 or 1. */
    val copy: Int

    /** "" for an icon. */
    val label: String

    val alt: String?

    val description: String?

    val enabled: Boolean

    val state: String?

    val resourceName: String?

    val bounds: Rect

    /** Its cell less the gaps between keys. */
    val face: Rect

    /** Its cell, plus a row's blank end for the scene's edge keys (A's left). */
    val hit: Rect

    val pressed: Boolean

    /** Whether anything took it. */
    fun click(): Boolean

    fun textSize(text: String): Float?

    /** Its own and its marks' (a shortcut's command). */
    val drawnIcons: Int

    val drawnTexts: List<String>

    /** In the window, from the text's left and baseline; top and width guessed (0.75 and 0.6 of its size). */
    fun drawn(text: String): RectF?

    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()
}

/** Looked up in the scene each time: a Shift relabels the letters in a scene made anew. */
class SceneShownKey(val canvas: KeyboardCanvasView, override val id: String, override val copy: Int = 0) : ShownKey {
    val node: KeyNode get() = canvas.keyAt(id, copy) ?: error("no key $id ($copy) in the scene now")

    override val label: String get() = node.label.orEmpty()
    override val alt: String? get() = canvas.alt(id, copy)
    override val description: String? get() = canvas.spoken(id, copy)
    override val enabled: Boolean get() = canvas.enabled(id, copy)

    // the node made as TalkBack asks for it
    override val state: String? get() = told()?.stateDescription?.toString()
    override val resourceName: String? get() = told()?.viewIdResourceName?.substringAfter(":id/")

    private fun told(): AccessibilityNodeInfoCompat? {
        val i = canvas.scene?.keys?.indexOf(node) ?: return null
        return canvas.accessibilityNodeProvider?.createAccessibilityNodeInfo(i)?.let(AccessibilityNodeInfoCompat::wrap)
    }
    // a keyboard moved or scaled (a floating one dragged smaller) too
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
        val width = t.size * t.scaleX * WIDE * t.text.length
        RectF(inWindow(org.fcitx.fcitx5.android.scene.Box(t.x, t.baseline - t.size * INK, t.x + width, t.baseline)))
    }
    override fun toString() = "scene key $id${if (copy != 0) " ($copy)" else ""}"

    /** The places of the key's symbol and shortcut marks in drawing order, as T/B and L/C/R of the face's middle; the label in the middle is no mark. */
    fun markPlaces(): MarkPlaces {
        val face = node.shape.bounds
        fun place(x: Double, y: Double): String? {
            val dx = x - face.centerX
            val dy = y - face.centerY
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

    data class MarkPlaces(val symbol: String?, val shortcuts: List<String>)

    private companion object {
        const val CENTRE = 0.08

        const val INK = 0.75

        const val WIDE = 0.6
    }

    private fun inWindow(b: org.fcitx.fcitx5.android.scene.Box): Rect {
        val p = floatArrayOf(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
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
