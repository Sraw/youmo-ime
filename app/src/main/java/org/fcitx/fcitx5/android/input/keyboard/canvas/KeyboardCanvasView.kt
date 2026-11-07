/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.input.scene.SceneA11y
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import kotlin.math.roundToInt

/**
 * One view for a keyboard's keys (060-render-v2 §7): the scene the engine made of them drawn, each
 * key in a [RenderNode] of its own for each of its two faces (pressed or not), so a press shows by
 * swapping that key's two nodes' alpha alone, nothing laid out or recorded again; the keyboard's base in one more,
 * drawn once. A key showing the picture through it is drawn from it clipped to its shape (the
 * engine's [Op.Picture]).
 *
 * What a key does stays with [Host.keyView]'s view (P1: the old keys, not drawn, still take the
 * touches the keyboard hands them, BaseKeyboard's findTouchTarget, and run their gestures): this
 * view finds the key a finger is on by the scene's hit areas ([keyViewAt]), shows it pressed as its
 * view is ([showPressed]), and gives a screen reader one virtual node a key ([Speech]), with the
 * key's own resource name (`button_space`), so UAT finds it as before.
 */
@SuppressLint("ViewConstructor")
class KeyboardCanvasView(
    context: Context,
    look: ThemeLook,
    images: BoardImages,
    fonts: SceneFonts,
    private val host: Host,
) : View(context) {

    interface Host {
        /** The keys' scene at [width] × [height] px, as the keyboard is now; null where there is none to draw. */
        fun scene(width: Int, height: Int): Scene?

        /** The view whose key [node] is: its gestures, its actions, its words. */
        fun keyView(node: KeyNode): KeyView?
    }

    // a key's two faces, recorded once each when first drawn; kept across scenes where they are the same
    private class Faces(val node: KeyNode) {
        var normal: RenderNode? = null
        var pressed: RenderNode? = null
    }

    private val painter = ScenePainter(this, look, images, fonts)
    private var built: Scene? = null
    private var builtFor = 0 to 0
    private var dirty = true
    private var faces: List<Faces> = emptyList()
    private var base: RenderNode? = null
    private val pressed = HashSet<String>()
    private val speech = Speech()

    init {
        ViewCompat.setAccessibilityDelegate(this, speech)
        // the keyboard takes every touch (BaseKeyboard.onInterceptTouchEvent): none is this view's
        isClickable = false
    }

    /** The scene as the keyboard is now: made again after [invalidateScene], or at another size. */
    val scene: Scene?
        get() {
            if (!dirty && builtFor == (width to height)) return built
            dirty = false
            builtFor = width to height
            val old = faces.associateBy { key(it.node) }
            val s = if (width > 0 && height > 0) host.scene(width, height) else null
            if (s?.base != built?.base) base = null
            built = s
            // a key drawn as before keeps its nodes: Shift relabels the letters, not the rest
            faces = s?.keys?.map { k ->
                old[key(k)]?.takeIf { it.node.faces == k.faces }?.let { f -> Faces(k).also { it.normal = f.normal; it.pressed = f.pressed } } ?: Faces(k)
            }.orEmpty()
            speech.invalidateRoot()
            return s
        }

    /** What the keyboard shows changed (Shift, Return, a key shown or hidden): the scene is made again, before it is next drawn or touched. */
    fun invalidateScene() {
        dirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        invalidateScene()
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        // an image or the picture in since it was recorded: recorded again
        if (painter.missed) {
            painter.missed = false
            base = null
            faces.forEach { it.normal = null; it.pressed = null }
        }
        if (!canvas.isHardwareAccelerated) {
            // a software canvas (a bitmap's: screenshots) draws no RenderNode
            painter.draw(canvas, s.base)
            faces.forEach { painter.draw(canvas, it.node.ops(isPressed(it.node))) }
            return
        }
        canvas.drawRenderNode(base.live() ?: record("base", s.base).also { base = it })
        // both faces drawn once recorded, the one not shown at alpha 0: a node no display list
        // draws any more is taken out of the tree and loses what was recorded (seen: a key let go
        // drawn as nothing), so a press only changes the two nodes' alpha
        for (f in faces) {
            val on = isPressed(f.node)
            val normal = f.normal.live() ?: record(f.node.id, f.node.ops(false)).also { f.normal = it }
            normal.alpha = if (on) 0f else 1f
            canvas.drawRenderNode(normal)
            val pressed = f.pressed.live() ?: if (on) record(f.node.id, f.node.ops(true)).also { f.pressed = it } else null
            pressed?.let {
                it.alpha = if (on) 1f else 0f
                canvas.drawRenderNode(it)
            }
        }
    }

    // a node still holding what was recorded: one the renderer let go of (the window gone, memory
    // trimmed) is recorded again
    private fun RenderNode?.live(): RenderNode? = this?.takeIf { it.hasDisplayList() }

    private fun nodeOf(f: Faces): RenderNode = if (isPressed(f.node)) {
        f.pressed.live() ?: record(f.node.id, f.node.ops(true)).also { f.pressed = it }
    } else {
        f.normal.live() ?: record(f.node.id, f.node.ops(false)).also { f.normal = it }
    }

    // the whole view's size: a key's shadow, a mark, reach out of its cell
    private fun record(name: String, ops: List<Op>): RenderNode = RenderNode(name).apply {
        setPosition(0, 0, width, height)
        setClipToBounds(false)
        val c = beginRecording(width, height)
        try {
            painter.draw(c, ops)
        } finally {
            endRecording()
        }
    }

    private fun key(n: KeyNode) = "${n.id}#${n.copy}"

    private fun isPressed(n: KeyNode) = key(n) in pressed

    /** [view]'s key shown pressed or not, as the view is: its node swapped, nothing else drawn anew. */
    fun showPressed(view: KeyView, on: Boolean) {
        val node = scene?.keys?.firstOrNull { host.keyView(it) === view } ?: return
        val changed = if (on) pressed.add(key(node)) else pressed.remove(key(node))
        if (changed) invalidate()
    }

    /**
     * The view of the key at ([x], [y]) in this view, by the scene's hit areas, [hit] set to where the
     * view is in the keyboard (the row's parent), as a touch target's; null in a gap or on a key with no view.
     */
    fun keyViewAt(x: Float, y: Float, hit: Rect = Rect()): KeyView? {
        val key = scene?.keyAt(x.toDouble(), y.toDouble())?.let(host::keyView) ?: return null
        key.getHitRect(hit)
        (key.parent as? View)?.let { row -> hit.offset(row.left, row.top) }
        return key
    }

    /** The scene's key [id] ([copy]: the one of a split row's two), for tests and UAT. */
    @VisibleForTesting
    fun keyAt(id: String, copy: Int = 0): KeyNode? = scene?.keys?.firstOrNull { it.id == id && it.copy == copy }

    /** Where key [id] is drawn, in the window, as KeyView.bounds says a key's place. */
    @VisibleForTesting
    fun bounds(id: String, copy: Int = 0): Rect? {
        val k = keyAt(id, copy) ?: return null
        val at = IntArray(2).also(::getLocationInWindow)
        val b = k.bounds
        return Rect(at[0] + b.left.roundToInt(), at[1] + b.top.roundToInt(), at[0] + b.right.roundToInt(), at[1] + b.bottom.roundToInt())
    }

    /**
     * A finger put down on key [id]'s middle ([down]) or lifted there, through the keyboard as a real
     * one comes: what the key does, its press shown.
     */
    @VisibleForTesting
    fun press(id: String, down: Boolean = true): Boolean {
        val k = keyAt(id) ?: return false
        val parent = parent as? ViewGroup ?: return false
        val now = SystemClock.uptimeMillis()
        if (down) downAt = now
        val x = left + k.anchor.x.toFloat()
        val y = top + k.anchor.y.toFloat()
        val e = MotionEvent.obtain(downAt, now, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, x, y, 0)
        return parent.dispatchTouchEvent(e).also { e.recycle() }
    }

    private var downAt = 0L

    /** The node drawn for key [id] now (pressed or not), recorded if it was not: what a press swaps. */
    @VisibleForTesting
    fun shownNode(id: String, copy: Int = 0): RenderNode? {
        scene
        return faces.firstOrNull { it.node.id == id && it.node.copy == copy }?.let(::nodeOf)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = speech.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * A screen reader's keys: one virtual node a key, its words the scene's ([KeyNode.a11y], the
     * app's string where the layout names one), the rest as its view says (its resource name, Shift's
     * state, what it can do), so TalkBack reads and works the keys as it did the views.
     */
    private inner class Speech : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val s = scene ?: return INVALID_ID
            val k = s.keyAt(x.toDouble(), y.toDouble()) ?: return INVALID_ID
            return s.keys.indexOf(k).takeIf { spoken(k) } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            scene?.keys?.forEachIndexed { i, k -> if (spoken(k)) virtualViewIds += i }
        }

        // a blank key is nothing to TalkBack (BlankKey), as its view was not
        private fun spoken(k: KeyNode) = host.keyView(k)?.importantForAccessibility != IMPORTANT_FOR_ACCESSIBILITY_NO

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val k = scene?.keys?.getOrNull(virtualViewId)
            if (k == null) {
                // asked of a key gone with the last scene: a node there must still have bounds and words
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            val view = host.keyView(k)
            node.text = k.label.orEmpty()
            node.contentDescription = SceneA11y.spoken(k.a11y, k.label) { name -> stringNamed(name) }
            val b = k.bounds
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
            if (view == null) return
            // a layout's id (button_space), not one made at run time (a letter's, generateViewId: no package)
            if (view.id != NO_ID && view.id ushr PACKAGE_SHIFT != 0) node.viewIdResourceName = resources.getResourceName(view.id)
            val own = AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo())
            node.className = own.className
            node.stateDescription = own.stateDescription
            node.isEnabled = own.isEnabled
            node.isClickable = own.isClickable
            node.isLongClickable = own.isLongClickable
            own.actionList.filter { it.id !in HELPERS }.forEach(node::addAction)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val k = scene?.keys?.getOrNull(virtualViewId) ?: return false
            return host.keyView(k)?.performAccessibilityAction(action, arguments) == true
        }
    }

    // the layout's names of the app's strings (a11y_key_shift…): the ones its keys name, looked up by name
    private fun stringNamed(name: String): String? = A11Y_STRINGS[name]?.let(context::getString)

    private companion object {
        const val PACKAGE_SHIFT = 24

        // the node's focus and selection are the helper's own
        val HELPERS = setOf(
            AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfoCompat.ACTION_FOCUS,
            AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS,
            AccessibilityNodeInfoCompat.ACTION_SELECT,
            AccessibilityNodeInfoCompat.ACTION_CLEAR_SELECTION,
        )

        val A11Y_STRINGS = mapOf(
            "a11y_key_shift" to org.fcitx.fcitx5.android.R.string.a11y_key_shift,
            "a11y_key_backspace" to org.fcitx.fcitx5.android.R.string.a11y_key_backspace,
            "a11y_key_switch_language" to org.fcitx.fcitx5.android.R.string.a11y_key_switch_language,
        )
    }
}
