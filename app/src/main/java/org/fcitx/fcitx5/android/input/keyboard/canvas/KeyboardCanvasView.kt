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
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.scene.CanvasFrame
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneA11y
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One view for a keyboard's keys (060-render-v2 §7): the scene the engine made of them drawn, each
 * key in a [RenderNode] of its own for each of its two faces (pressed or not), so a press shows by
 * swapping that key's two nodes' alpha alone ([CanvasFrame.faces]), nothing laid out or recorded
 * again; the keyboard's base in one more, drawn once. A key showing the picture through it is drawn
 * from it clipped to its shape (the engine's [Op.Picture]), recorded again when the view moves over
 * the picture (one-handed to the other side).
 *
 * The scene is made again only as the view draws ([onDraw]): a touch or a press shown in between
 * finds its keys in the scene drawn last, so no finger waits on the engine.
 *
 * A key's touch is the scene's key's ([SceneTouch], 060-render-p2 S1): this view finds the key a
 * finger is on by the scene's hit areas ([keyAt]) and where it is ([cell], [popupBounds]), shows it
 * pressed as its touch says ([showPressed]), and gives a screen reader one virtual node a key
 * ([Speech]), with the key's own resource name (`button_space`), so UAT finds it as before; its words
 * and actions there are still [Host.keyView]'s view's (S3).
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
        /**
         * The keys' scene at [width] × [height] px, as the keyboard is now; null where there is none to
         * draw. The same instance as last time where nothing drawn changed: kept as it was drawn.
         */
        fun scene(width: Int, height: Int): Scene?

        /** The view whose key [node] is: its state and its actions, to a screen reader (S3 takes them to the scene). */
        fun keyView(node: KeyNode): KeyView?

        /** There was no scene of the keys ([scene] null): told once, the keyboard's to draw them another way. */
        fun noScene(view: KeyboardCanvasView)
    }

    // a key's two faces, recorded once each when first drawn; kept across scenes where they are the same.
    // Whether it is shown pressed ([down]) and what its view said of its state ([state]: Shift's) go with
    // it: a scene made again for a key typed looks up nothing by a name made for it, a frame neither
    private class Faces(val node: KeyNode, val state: String?) {
        var normal: RenderNode? = null
        var pressed: RenderNode? = null
        var down = false
        val pictured = node.faces.values.any(CanvasFrame::showsPicture)

        fun of(k: KeyNode) = node.id == k.id && node.copy == k.copy
    }

    private val painter = ScenePainter(this, look, images, fonts)
    private var built: Scene? = null
    private var builtFor = 0 to 0
    private var dirty = true
    private var failed = false
    private var faces: List<Faces> = emptyList()
    private var base: RenderNode? = null
    private val speech = Speech()

    // where the view was over the picture's base, and the base's size, when the picture was recorded
    private val pictureAt = IntArray(PICTURE_PLACE)
    private val pictureNow = IntArray(PICTURE_PLACE)
    private val viewAt = IntArray(2)
    private val baseAt = IntArray(2)

    private val moved = ViewTreeObserver.OnPreDrawListener {
        if (pictureMoved()) dropPictures()
        true
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, speech)
        // the keyboard takes every touch (BaseKeyboard.onInterceptTouchEvent): none is this view's
        isClickable = false
    }

    /**
     * The scene drawn last, not made again: what a touch, a press and a screen reader find the keys
     * in. Made here only if there was none yet (asked before the first frame: a screen reader, a test).
     */
    private val current: Scene? get() = built ?: if (failed) null else scene

    /** The scene as the keyboard is now: made again after [invalidateScene], or at another size (drawing, and tests). */
    @get:VisibleForTesting
    val scene: Scene?
        get() {
            if (failed) return null
            if (!dirty && builtFor == (width to height)) return built
            if (width <= 0 || height <= 0) return null
            dirty = false
            builtFor = width to height
            val start = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("scene")
            val s = try {
                host.scene(width, height)
            } finally {
                Trace.endSection()
            }
            // the one drawn already (nothing that shows changed: Host.scene kept it): its keys' nodes kept, nothing said
            if (s != null && s === built) return s
            Timber.d("keyboard canvas: scene %d × %d made in %.2f ms", width, height, (SystemClock.elapsedRealtimeNanos() - start) / NANOS_A_MS)
            if (s == null) {
                failed = true
                built = null
                faces = emptyList()
                base = null
                host.noScene(this)
                return null
            }
            val old = faces
            if (s.base != built?.base) base = null
            built = s
            var said: MutableList<Int>? = null
            faces = s.keys.mapIndexed { i, k ->
                val f = Faces(k, host.keyView(k)?.let(ViewCompat::getStateDescription)?.toString())
                // the key in the last scene: at the same place in it mostly (a scene made again has the same keys)
                val was = old.getOrNull(i)?.takeIf { it.of(k) } ?: old.firstOrNull { it.of(k) }
                if (was != null) {
                    // drawn as before, it keeps its nodes: Shift relabels the letters, not the rest
                    if (was.node.faces == k.faces) {
                        f.normal = was.normal
                        f.pressed = was.pressed
                    }
                    f.down = was.down
                    // its state changed (Shift once, locked): told below
                    if (was.state != f.state) (said ?: ArrayList<Int>().also { said = it }) += i
                }
                // a key gone with the scene is pressed no more: nothing carries it
                f
            }
            speech.invalidateRoot()
            // a key's state changed told to a screen reader as its view's was: on the key's node, which
            // TalkBack reads out when it has focus
            said?.forEach { speech.invalidateVirtualView(it, AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION) }
            return s
        }

    /** What the keyboard shows changed (Shift, Return, a key shown or hidden): the scene is made again, before it is next drawn. */
    fun invalidateScene() {
        dirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        invalidateScene()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        super.onDetachedFromWindow()
    }

    // whether the view is elsewhere over the picture's base (or the base another size) than when the
    // picture was last recorded: one-handed to the other side moves the keyboard, not the base under it
    private fun pictureMoved(): Boolean {
        val pictureHost = PictureHost.of(this) ?: return false
        getLocationInWindow(viewAt)
        pictureHost.base.getLocationInWindow(baseAt)
        pictureNow[0] = viewAt[0] - baseAt[0]
        pictureNow[1] = viewAt[1] - baseAt[1]
        pictureNow[2] = pictureHost.base.width
        pictureNow[3] = pictureHost.base.height
        if (pictureNow.contentEquals(pictureAt)) return false
        pictureNow.copyInto(pictureAt)
        return true
    }

    // what shows the picture recorded again, where it is now (the frame about to be drawn draws it)
    private fun dropPictures() {
        var any = false
        if (built?.base?.let(CanvasFrame::showsPicture) == true) {
            base = null
            any = true
        }
        faces.filter { it.pictured }.forEach {
            it.normal = null
            it.pressed = null
            any = true
        }
        if (any) invalidate()
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
            // a software canvas (a bitmap's: screenshots) draws no RenderNode, keeps nothing to record again
            painter.missed = false
            painter.draw(canvas, s.base)
            faces.forEach { painter.draw(canvas, it.node.ops(it.down)) }
            return
        }
        canvas.drawRenderNode(base.live() ?: record("base", Box(0.0, 0.0, width.toDouble(), height.toDouble()), s.base).also { base = it })
        for (f in faces) {
            val shown = CanvasFrame.faces(f.down, f.normal.live() != null, f.pressed.live() != null)
            // by index: no iterator for each key in each frame
            for (i in shown.indices) {
                val face = shown[i]
                val node = if (face.record) {
                    record(f.node.id, f.node.bounds, f.node.ops(face.pressed)).also { if (face.pressed) f.pressed = it else f.normal = it }
                } else {
                    (if (face.pressed) f.pressed else f.normal)!!
                }
                node.alpha = face.alpha
                canvas.drawRenderNode(node)
            }
        }
    }

    // a node still holding what was recorded: one the renderer let go of (the window gone, memory
    // trimmed) is recorded again
    private fun RenderNode?.live(): RenderNode? = this?.takeIf { it.hasDisplayList() }

    private fun nodeOf(f: Faces): RenderNode = if (f.down) {
        f.pressed.live() ?: record(f.node.id, f.node.bounds, f.node.ops(true)).also { f.pressed = it }
    } else {
        f.normal.live() ?: record(f.node.id, f.node.bounds, f.node.ops(false)).also { f.normal = it }
    }

    // a node the size of what [ops] draw round [bounds] (a key's shadow reaches out of its cell), drawn where it is
    private fun record(name: String, bounds: Box, ops: List<Op>): RenderNode = RenderNode(name).apply {
        val e = CanvasFrame.extent(bounds, ops, EDGE_PX)
        val l = floor(e.left).toInt()
        val t = floor(e.top).toInt()
        val r = ceil(e.right).toInt()
        val b = ceil(e.bottom).toInt()
        setPosition(l, t, r, b)
        setClipToBounds(false)
        val c = beginRecording(r - l, b - t)
        try {
            c.translate(-l.toFloat(), -t.toFloat())
            painter.draw(c, ops)
        } finally {
            endRecording()
        }
    }

    /** Key [ref] shown pressed or not, as its touch says ([SceneKey]): its node swapped, nothing else drawn anew. */
    fun showPressed(ref: KeyRef, on: Boolean) {
        if (current == null) return
        val f = faces.firstOrNull { it.node.of(ref) } ?: return
        if (f.down == on) return
        f.down = on
        invalidate()
    }

    /** The key at ([x], [y]) in this view, by the hit areas of the scene drawn last; null in a gap. */
    fun keyAt(x: Float, y: Float): KeyNode? = current?.keyAt(x.toDouble(), y.toDouble())

    /**
     * Key [ref]'s cell in this view, into [out], as the scene drawn last has it: where its view was, the
     * frame its touch is read in ([SceneKey]); false where it has no such key.
     */
    fun cell(ref: KeyRef, out: Rect): Boolean {
        val k = current?.keys?.firstOrNull { it.of(ref) } ?: return false
        out.set(k.bounds)
        return true
    }

    /**
     * Where a popup over key [ref] is placed, in the window, into [out]: its face across and its cell
     * down, as a key view's bounds were (KeyView.bounds); false where the scene drawn last has no such key.
     */
    fun popupBounds(ref: KeyRef, out: Rect): Boolean {
        val k = current?.keys?.firstOrNull { it.of(ref) } ?: return false
        getLocationInWindow(viewAt)
        val face = k.shape.bounds
        val cell = k.bounds
        out.set(
            viewAt[0] + face.left.roundToInt(), viewAt[1] + cell.top.roundToInt(),
            viewAt[0] + face.right.roundToInt(), viewAt[1] + cell.bottom.roundToInt(),
        )
        return true
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

    /** Whether key [id] is shown pressed. */
    @VisibleForTesting
    fun shownPressed(id: String, copy: Int = 0): Boolean = faces.any { it.node.id == id && it.node.copy == copy && it.down }

    /** Whether the frame drawn last missed something still coming (an image, the picture): to be recorded again. */
    @get:VisibleForTesting
    val missed: Boolean get() = painter.missed

    /** The pictures' places looked at, as a frame does before it is drawn: what showed the picture recorded again if the view moved over it. */
    @VisibleForTesting
    fun beforeFrame() {
        moved.onPreDraw()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = speech.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * A screen reader's keys: one virtual node a key, its words the scene's ([KeyNode.a11y], the
     * app's string where the layout names one), the rest as its view says (its resource name, Shift's
     * state, what it can do), so TalkBack reads and works the keys as it did the views.
     */
    private inner class Speech : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val s = current ?: return INVALID_ID
            val k = s.keyAt(x.toDouble(), y.toDouble()) ?: return INVALID_ID
            return s.keys.indexOf(k).takeIf { spoken(k) } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            current?.keys?.forEachIndexed { i, k -> if (spoken(k)) virtualViewIds += i }
        }

        // a blank key is nothing to TalkBack (BlankKey), as its view was not
        private fun spoken(k: KeyNode) = host.keyView(k)?.importantForAccessibility != IMPORTANT_FOR_ACCESSIBILITY_NO

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val k = current?.keys?.getOrNull(virtualViewId)
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
            val info = view.createAccessibilityNodeInfo()
            try {
                val own = AccessibilityNodeInfoCompat.wrap(info)
                node.className = own.className
                node.stateDescription = own.stateDescription
                node.isEnabled = own.isEnabled
                node.isClickable = own.isClickable
                node.isLongClickable = own.isLongClickable
                own.actionList.filter { it.id !in HELPERS }.forEach(node::addAction)
            } finally {
                // pooled before Android 13 (a no-op since): made for each key read out, given back
                @Suppress("DEPRECATION")
                info.recycle()
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val k = current?.keys?.getOrNull(virtualViewId) ?: return false
            return host.keyView(k)?.performAccessibilityAction(action, arguments) == true
        }
    }

    // the layout's names of the app's strings (a11y_key_shift…): the ones its keys name, looked up by name
    private fun stringNamed(name: String): String? = A11Y_STRINGS[name]?.let(context::getString)

    private companion object {
        const val PACKAGE_SHIFT = 24
        const val NANOS_A_MS = 1e6

        // a node's room past what it draws, for its edges' anti-aliasing
        const val EDGE_PX = 2.0

        // where the view is over the picture's base (x, y) and the base's size (w, h)
        const val PICTURE_PLACE = 4

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

/** Whether this is key [ref]: a split row's second copy ([KeyNode.copy] 1) is its own. */
internal fun KeyNode.of(ref: KeyRef) = id == ref.id && (copy != 0) == ref.copy

/** [b] rounded to whole pixels, as a view's place is. */
internal fun Rect.set(b: Box) = set(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
