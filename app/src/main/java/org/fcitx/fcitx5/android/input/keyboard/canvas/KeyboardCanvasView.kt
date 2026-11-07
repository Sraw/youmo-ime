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
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.scene.CanvasFrame
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneA11y
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
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
 * ([Speech], S3): its words, id, state and corner the scene's ([SceneA11y.of]), with the old view's
 * resource name (`button_space`) so UAT finds it as before; its actions its scene key's ([Host.key]).
 */
@SuppressLint("ViewConstructor")
class KeyboardCanvasView(
    context: Context,
    private val images: BoardImages,
    private val host: Host,
) : View(context) {

    /**
     * A [scene] and the look it was made in ([shownLook], ThemeManager.shownLook's), with its [fonts]:
     * what paints it. The theme's, or the built-in theme's a theme that makes no scene falls back to
     * (060-render-p2 U8), the whole of it.
     */
    class Drawn(val scene: Scene, val shownLook: ThemeLook, val fonts: SceneFonts)

    interface Host {
        /**
         * The keys' scene at [width] × [height] px, as the keyboard is now; null where there is none to
         * draw. The same scene as last time where nothing drawn changed: kept as it was drawn.
         */
        fun scene(width: Int, height: Int): Drawn?

        /** The key wired for [node] (its touch, [SceneTouch.keyOf]): what a screen reader's click and long click on its node do; null, none. */
        fun key(node: KeyNode): SceneKey?
    }

    // a key's two faces, recorded once each when first drawn; kept across scenes where they are the same.
    // Whether it is shown pressed ([down]) and the state a screen reader is told ([state]: Shift's,
    // SceneA11y.state) go with it: a scene made again for a key typed looks up nothing by a name made
    // for it, a frame neither
    private class Faces(val node: KeyNode, val state: String?) {
        var normal: RenderNode? = null
        var pressed: RenderNode? = null
        var down = false
        val pictured = node.faces.values.any(CanvasFrame::showsPicture)

        fun of(k: KeyNode) = node.id == k.id && node.copy == k.copy
    }

    // the look of the scene drawn, and what paints in it: another look (the built-in's, U8), another painter
    private var painter: ScenePainter? = null
    private var painted: ThemeLook? = null
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
            val drawn = try {
                host.scene(width, height)
            } finally {
                Trace.endSection()
            }
            val s = drawn?.scene
            // the one drawn already (nothing that shows changed: Host.scene kept it): its keys' nodes kept, nothing said
            if (s != null && s === built) return s
            Timber.d("keyboard canvas: scene %d × %d made in %.2f ms", width, height, (SystemClock.elapsedRealtimeNanos() - start) / NANOS_A_MS)
            if (s == null) {
                // none, even of the built-in theme (060-render-p2 U8, Host.scene): nothing drawn, nor
                // touched or read, until what the keyboard shows changes ([invalidateScene]) and it is asked again
                Timber.e("keyboard canvas: no scene of the keys %d × %d; none drawn", width, height)
                failed = true
                built = null
                faces = emptyList()
                base = null
                speech.invalidateRoot()
                return null
            }
            // painted in another look: nothing recorded in the last is kept
            val repainted = drawn.shownLook != painted
            if (repainted) {
                painter = ScenePainter(this, drawn.shownLook, images, drawn.fonts)
                painted = drawn.shownLook
            }
            val old = faces
            if (repainted || s.base != built?.base) base = null
            built = s
            var said: MutableList<Int>? = null
            faces = s.keys.mapIndexed { i, k ->
                val f = Faces(k, SceneA11y.state(k.role, k.states))
                // the key in the last scene: at the same place in it mostly (a scene made again has the same keys)
                val was = old.getOrNull(i)?.takeIf { it.of(k) } ?: old.firstOrNull { it.of(k) }
                if (was != null) {
                    // drawn as before, it keeps its nodes: Shift relabels the letters, not the rest
                    if (!repainted && was.node.faces == k.faces) {
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
            speech.reading.clear()
            speech.invalidateRoot()
            // a key's state changed told to a screen reader as a view's state description is: on the
            // key's node, which TalkBack reads out when it has focus
            said?.forEach { speech.invalidateVirtualView(it, AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION) }
            return s
        }

    /** What the keyboard shows changed (Shift, Return, a key shown or hidden): the scene is made again, before it is next drawn. */
    fun invalidateScene() {
        dirty = true
        failed = false
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
        val painter = painter ?: return
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
            painter?.draw(c, ops)
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

    /**
     * Key [ref]'s node told [type] (a click, a long click: [SceneKey]), as a view tells its own: a
     * screen reader says what was typed. Nothing where the scene drawn last has no such key.
     */
    fun tell(ref: KeyRef, type: Int) {
        val i = current?.keys?.indexOfFirst { it.of(ref) } ?: return
        if (i >= 0) speech.sendEventForVirtualView(i, type)
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

    /** Key [id]'s corner as drawn (a letter's symbol, the nine keys' digit); null where it has none. */
    @VisibleForTesting
    fun alt(id: String, copy: Int = 0): String? = keyAt(id, copy)?.alt?.takeIf { it.isNotEmpty() }

    /** What a screen reader reads for key [id] (its node's description); null where there is no such key. */
    @VisibleForTesting
    fun spoken(id: String, copy: Int = 0): String? = keyAt(id, copy)?.let { SceneA11y.of(it, ::stringNamed).description }

    /** Whether key [id] takes a press, as its node says. */
    @VisibleForTesting
    fun enabled(id: String, copy: Int = 0): Boolean = keyAt(id, copy)?.let { SceneA11y.of(it, ::stringNamed).enabled && host.key(it)?.keyEnabled != false } == true

    /** Key [id] clicked as the end of a tap clicks it, or TalkBack's click ([SceneKey.click]): whether anything took it. */
    @VisibleForTesting
    fun click(id: String, copy: Int = 0): Boolean = keyAt(id, copy)?.let(host::key)?.click() == true

    /** The look the scene drawn was painted in: the theme's, or the built-in's it fell back to (U8). */
    @get:VisibleForTesting
    val paintedIn: ThemeLook? get() = painted

    /** Whether the frame drawn last missed something still coming (an image, the picture): to be recorded again. */
    @get:VisibleForTesting
    val missed: Boolean get() = painter?.missed == true

    /** The pictures' places looked at, as a frame does before it is drawn: what showed the picture recorded again if the view moved over it. */
    @VisibleForTesting
    fun beforeFrame() {
        moved.onPreDraw()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = speech.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * A screen reader's keys: one virtual node a key of the scene drawn last, all it is told the
     * scene's ([SceneA11y.of]: its words, the old view's resource name, Shift's state, its corner in
     * the extras, U6), what it can do its scene key's ([Host.key]), so TalkBack reads and works the
     * keys as it did the views with none of them. A click or long click on a node is the key's own
     * ([SceneKey.click], [SceneKey.longClick]: what the end of a tap, the long press's moment call),
     * as a view's performClick and performLongClick were.
     */
    private inner class Speech : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val s = current ?: return INVALID_ID
            val k = s.keyAt(x.toDouble(), y.toDouble()) ?: return INVALID_ID
            return s.keys.indexOf(k)
        }

        // every key: a blank place is no key of the scene's (Gap), nothing to TalkBack as its view was not
        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            current?.keys?.indices?.forEach { virtualViewIds += it }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val k = current?.keys?.getOrNull(virtualViewId)
            if (k == null) {
                // asked of a key gone with the last scene: a node there must still have bounds and words
                node.contentDescription = ""
                @Suppress("DEPRECATION")
                node.setBoundsInParent(Rect())
                return
            }
            val said = SceneA11y.of(k, ::stringNamed)
            node.text = said.text
            node.contentDescription = said.description
            node.className = SceneA11y.CLASS_NAME
            val b = k.bounds
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
            said.resourceName?.let { node.viewIdResourceName = "$resourcePackage:id/$it" }
            node.stateDescription = said.state
            node.extras.putString(SceneA11y.EXTRA_KEY, said.id)
            said.alt?.let { node.extras.putString(SceneA11y.EXTRA_ALT, it) }
            val key = host.key(k)
            node.isEnabled = said.enabled && key?.keyEnabled != false
            node.isClickable = key != null
            node.isLongClickable = key?.longClickable == true
            if (node.isEnabled && node.isClickable) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            if (node.isEnabled && node.isLongClickable) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK)
            // its words read a character, word or paragraph at a time, as a view's description was
            reading.node(virtualViewId, said.description, node)
        }

        val reading = NodeReading({ resources.configuration.locales[0] }, ::sendEventForVirtualView)

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            reading.populate(virtualViewId, current?.keys?.getOrNull(virtualViewId)?.let { SceneA11y.of(it, ::stringNamed).description }, event)
        }

        // as a view's: a click on one that is clickable done and taken whatever it did (View.performAccessibilityAction)
        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val k = current?.keys?.getOrNull(virtualViewId) ?: return false
            // reading its words, as a view's (enabled or not): View.performAccessibilityActionInternal
            reading.perform(virtualViewId, SceneA11y.of(k, ::stringNamed).description, action, arguments)?.let { return it }
            val key = host.key(k)?.takeIf { it.keyEnabled && KeyState.Disabled !in k.states } ?: return false
            return when (action) {
                // told on this node (virtualViewId's), whichever copy's key it is wired to
                AccessibilityNodeInfoCompat.ACTION_CLICK -> true.also { key.click(KeyRef.scene(k.id, k.copy)) }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> key.longClickable.also { if (it) key.longClick(KeyRef.scene(k.id, k.copy)) }
                else -> false
            }
        }
    }

    // the package the app's ids are named in (button_space's), as a view's resource name has it
    private val resourcePackage: String by lazy { resources.getResourcePackageName(org.fcitx.fcitx5.android.R.id.keyboard_view) }

    // the layout's names of the app's strings (a11y_key_shift…): the ones its keys name, looked up by name
    private fun stringNamed(name: String): String? = A11Y_STRINGS[name]?.let(context::getString)

    private companion object {
        const val NANOS_A_MS = 1e6

        // a node's room past what it draws, for its edges' anti-aliasing
        const val EDGE_PX = 2.0

        // where the view is over the picture's base (x, y) and the base's size (w, h)
        const val PICTURE_PLACE = 4

        val A11Y_STRINGS = mapOf(
            "a11y_key_shift" to org.fcitx.fcitx5.android.R.string.a11y_key_shift,
            "a11y_key_backspace" to org.fcitx.fcitx5.android.R.string.a11y_key_backspace,
            "a11y_key_switch_language" to org.fcitx.fcitx5.android.R.string.a11y_key_switch_language,
            SceneA11y.SHIFT_ONCE to org.fcitx.fcitx5.android.R.string.a11y_state_shift_once,
            SceneA11y.CAPS_LOCK to org.fcitx.fcitx5.android.R.string.a11y_state_caps_lock,
        )
    }
}

/** Whether this is key [ref]: a split row's second copy ([KeyNode.copy] 1) is its own. */
internal fun KeyNode.of(ref: KeyRef) = id == ref.id && (copy != 0) == ref.copy

/** [b] rounded to whole pixels, as a view's place is. */
internal fun Rect.set(b: Box) = set(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
