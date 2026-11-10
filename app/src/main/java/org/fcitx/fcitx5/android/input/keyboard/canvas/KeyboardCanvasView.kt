/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
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
import org.fcitx.fcitx5.android.data.theme.PackDrawGuard
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.scene.CanvasFrame
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneA11y
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Ink
import org.fcitx.fcitx5.android.scene.KeyEffect
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Motion
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.theme.Transition
import org.fcitx.fcitx5.android.scene.look.ThemeLook
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
     * (060-render-p2 U8), the whole of it. [pack]: the theme 4.0 pack it is of, its first frame guarded
     * ([PackDrawGuard]); null, another theme's.
     */
    class Drawn(val scene: Scene, val shownLook: ThemeLook, val fonts: SceneFonts, val pack: PackDrawGuard.Key? = null)

    interface Host {
        /**
         * The keys' scene at [width] × [height] px, as the keyboard is now; null where there is none to
         * draw. The same scene as last time where nothing drawn changed: kept as it was drawn.
         */
        fun scene(width: Int, height: Int): Drawn?

        /** The key wired for [node] (its touch, [SceneTouch.keyOf]): what a screen reader's click and long click on its node do; null, none. */
        fun key(node: KeyNode): SceneKey?

        /** Its layout's id (a key's resource name may be its board's: [SceneA11y.of]); null for none. */
        val board: String? get() = null

        /** A scene made anew, about to be drawn: [scene], or null for none (nothing drawn). */
        fun built(scene: Scene?) {}
        /** The keyboard's own words for a string its layout names (注's on and off, the lock's), over the app's shared ones; null for none. */
        fun string(name: String): String? = null
    }

    // a key's two faces, recorded once each when first drawn; kept across scenes where they are the same.
    // Whether it is shown pressed ([down]) and the state a screen reader is told ([state]: Shift's,
    // SceneA11y.state) go with it: a scene made again for a key typed looks up nothing by a name made
    // for it, a frame neither; its words ([said]: 注's and the lock's change with their state) told too when they change
    private class Faces(val node: KeyNode, val state: String?, val said: String?) {
        var normal: RenderNode? = null
        var pressed: RenderNode? = null
        var down = false
        val pictured = node.faces.values.any(CanvasFrame::showsPicture)
        // pressed sooner after the key before than its fade lasts (typing fast): it does not move, as with motion off
        var fast = false

        // how it is moving (060-render-p3 A1): made only for a key of a theme's motion while 键盘动效 may
        // move it, so a theme without motion, or motion off, keeps none and draws as ever
        var moving: Moving? = null

        fun of(k: KeyNode) = node.id == k.id && node.copy == k.copy
    }

    // a key's motion as it is now: its pressed face's alpha (fading when let go), scale and sink, and the
    // face it had before its state changed ([leaving], over the new one, fading). One animator at a time,
    // its frames changing these and the nodes' properties alone: nothing recorded, laid out or made
    private inner class Moving {
        var alpha = 1f
        var scale = 1f
        var sink = 0f
        var leaving: RenderNode? = null
        var anim: ValueAnimator? = null
        // its pressed face fading as it is let go: drawn at [alpha], not at CanvasFrame's 0
        var releasing = false

        fun stop() {
            anim?.let {
                anim = null
                it.cancel()
            }
            alpha = 1f
            releasing = false
            leaving?.let(::letGoOf)
            leaving = null
        }
    }

    // the look of the scene drawn, and what paints in it: another look (the built-in's, U8), another painter
    private var painter: ScenePainter? = null
    private var painted: ThemeLook? = null
    private var built: Scene? = null

    // what is drawn under the keys of [built] ([under]), made once a scene: compared and drawn each frame
    private var drawnUnder: List<Op> = emptyList()
    private var builtFor = 0 to 0
    private var dirty = true
    private var failed = false
    // the pack the scene drawn is of (Drawn.pack)
    private var pack: PackDrawGuard.Key? = null

    // the pack the scene built is of (Drawn.pack), kept once its first frame is drawn ([pack] cleared then)
    private var scenePack: PackDrawGuard.Key? = null
    private var faces: List<Faces> = emptyList()
    private var base: RenderNode? = null
    private val speech = Speech()

    // the keys' press effects (060-render-p3 A3), and where the last finger came down on which key: what they play from
    // made at the first effect played: none (no animator) for a theme without effects or with 键盘动效 off
    private var effectsMade: KeyEffects? = null
    private val effects: KeyEffects get() = effectsMade ?: KeyEffects(this, images).also { effectsMade = it }
    private var touched: KeyRef? = null
    private var touchedX = 0f
    private var touchedY = 0f

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

    /**
     * Whether the board's own fill is drawn under the keys: not for a row or a column laid on what is
     * under it (the bar's number row, 展开候选's column), whose old keys drew no keyboard under them.
     */
    var drawsBase = true
        set(value) {
            field = value
            invalidateScene()
        }

    /** The keys of the scene drawn last ([current]): not made again for what changed since (glide typing's, strokes'). */
    val shownKeys: List<KeyNode> get() = current?.keys.orEmpty()

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
                faces.forEach { it.moving?.stop() }
                faces.forEach(::discardFaces)
                faces = emptyList()
                dropBase()
                speech.invalidateRoot()
                host.built(null)
                return null
            }
            // painted in another look: nothing recorded in the last is kept
            val repainted = drawn.shownLook != painted
            if (repainted) {
                painter = ScenePainter(this, drawn.shownLook, images, drawn.fonts)
                painted = drawn.shownLook
            }
            val old = faces
            val under = s.under(drawsBase)
            if (repainted || under != drawnUnder) dropBase()
            drawnUnder = under
            built = s
            pack = drawn.pack
            scenePack = drawn.pack
            var said: MutableList<Int>? = null
            var worded: MutableList<Int>? = null
            faces = s.keys.mapIndexed { i, k ->
                val f = Faces(k, SceneA11y.state(k.role, k.states), SceneA11y.of(k, ::stringNamed).description)
                // the key in the last scene: at the same place in it mostly (a scene made again has the same keys)
                val was = old.getOrNull(i)?.takeIf { it.of(k) } ?: old.firstOrNull { it.of(k) }
                if (was != null) {
                    // drawn as before, it keeps its nodes: Shift relabels the letters, not the rest
                    f.down = was.down
                    f.fast = was.fast
                    if (!repainted && was.node.faces == k.faces) {
                        f.normal = was.normal
                        f.pressed = was.pressed
                        f.moving = was.moving
                    } else {
                        // its state changed (Shift, locked) where it was: the face it had fades out over its new one;
                        // laid out anew (a size, one-handed) or relabelled in the same state, shown at once
                        crossFade(was, f, repainted || was.node.states == k.states || was.node.bounds != k.bounds)
                    }
                    // its state changed (Shift once, locked): told below
                    if (was.state != f.state) (said ?: ArrayList<Int>().also { said = it }) += i
                    // its words changed (注 on, the lock shut: what a tap does): told as a view's new description is
                    if (was.said != f.said) (worded ?: ArrayList<Int>().also { worded = it }) += i
                }
                // a key gone with the scene is pressed no more: nothing carries it
                f
            }
            // a key's motion not carried into the new faces (a key gone, its faces changed) stops: no animator left unreachable
            val carried = faces.mapNotNullTo(HashSet()) { it.moving }
            old.forEach { o -> o.moving?.takeIf { it !in carried }?.stop() }
            // the nodes no new key kept: their display lists (and the theme's bitmaps they hold) let go now, not at GC;
            // a face fading out over its key's new one (a state change's crossfade) kept till its fade ends
            val kept = faces.flatMapTo(HashSet()) { listOfNotNull(it.normal, it.pressed, it.moving?.leaving) }
            old.forEach { f -> listOfNotNull(f.normal, f.pressed).forEach { if (it !in kept) it.discardDisplayList() } }
            speech.reading.clear()
            speech.invalidateRoot()
            // a key's state changed told to a screen reader as a view's state description is: on the
            // key's node, which TalkBack reads out when it has focus
            said?.forEach { speech.invalidateVirtualView(it, AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION) }
            worded?.forEach { speech.invalidateVirtualView(it, AccessibilityEventCompat.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION) }
            host.built(s)
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
        // a keyboard attached after the picture began to play (symbols, numbers, another layout): on a layer too
        if (PictureHost.of(this)?.animated != null) setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // the keyboard hidden keeps its view attached: out of sight, nothing moves on
        if (visibility != VISIBLE) {
            faces.forEach { it.moving?.stop() }
            effectsMade?.stop()
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        // a keyboard hidden moves no more: no frame asked for out of sight
        faces.forEach { it.moving?.stop() }
        effectsMade?.release()
        dropBase()
        faces.forEach(::discardFaces)
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

    /**
     * The picture under it now played (060-render-p3 A4): what shows it recorded again see-through, and the
     * keys on a hardware layer, so each frame under them composites one texture, not every key's node again.
     */
    fun pictureChanged() {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        dropPictures()
    }

    // what shows the picture recorded again, where it is now (the frame about to be drawn draws it)
    private fun dropPictures() {
        var any = false
        if (built != null && CanvasFrame.showsPicture(drawnUnder)) {
            dropBase()
            any = true
        }
        faces.filter { it.pictured }.forEach {
            discardFaces(it)
            any = true
        }
        if (any) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        val painter = painter ?: return
        // a pack's first frame of the process about to be drawn, shown: marked till it is (PackDrawGuard), one
        // that takes the keyboard down not drawn again on its own. A pack built and not drawn marks nothing.
        // A mark this view left pending (hidden before its frame committed) cleared as it draws another theme or pack
        PackDrawGuard.shows(this, scenePack)
        if (pack?.let { PackDrawGuard.drawing(it, this) } == false) pack = null
        // an image or the picture in since it was recorded: recorded again
        if (painter.missed) {
            painter.missed = false
            dropBase()
            faces.forEach(::discardFaces)
        }
        if (!canvas.isHardwareAccelerated) {
            // a software canvas (a bitmap's: screenshots) draws no RenderNode, keeps nothing to record again
            painter.missed = false
            painter.draw(canvas, drawnUnder)
            faces.forEach { painter.draw(canvas, it.node.ops(it.down)) }
            // drawn, glyphs and pictures, here and now
            pack?.let(PackDrawGuard::frameDrawn)
            return
        }
        canvas.drawRenderNode(base.live() ?: record("base", Box(0.0, 0.0, width.toDouble(), height.toDouble()), drawnUnder).also { base = it })
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
                // a key of a theme's motion: its pressed face as it moves (a node just recorded too)
                if (face.pressed) applyMotion(f)
                canvas.drawRenderNode(node)
            }
            f.moving?.let { drawLeaving(canvas, it) }
        }
        // over every key: a particle flies over the keys round it
        effectsMade?.draw(canvas)
        // what the frame's nodes draw (a pack's fonts' glyphs, its pictures) is drawn on the RenderThread
        // after this: the pack's first frame drawn once a frame is committed, not here. ViewRootImpl.performDraw
        // takes the commit callbacks before it draws, so one registered here goes with the window's next
        // frame, asked for (a keyboard shown and hidden untouched draws one): that one committed, the frames
        // are drawn in order, this one was. Once it is, none is asked for again (drawing() false, pack null),
        // nor past a few (PackDrawGuard.askFrame: a device that calls back none draws no more)
        pack?.let { p ->
            viewTreeObserver.registerFrameCommitCallback { PackDrawGuard.frameDrawn(p) }
            if (PackDrawGuard.askFrame(p)) postInvalidateOnAnimation()
        }
    }

    // a crossfade's old face, its fade over: its display list (the theme's bitmaps) freed now, not at GC; never
    // one a key shows still
    private fun letGoOf(node: RenderNode) {
        if (faces.none { it.normal === node || it.pressed === node }) node.discardDisplayList()
    }

    // the face a state change left, fading over the new one; one the renderer let go of dropped
    private fun drawLeaving(canvas: Canvas, m: Moving) {
        val leaving = m.leaving ?: return
        if (leaving.hasDisplayList()) canvas.drawRenderNode(leaving) else m.leaving = null
    }

    // a node let go of: its display list freed now, not when the GC finalizes it
    private fun dropBase() {
        base?.discardDisplayList()
        base = null
    }

    private fun discardFaces(f: Faces) {
        f.normal?.discardDisplayList()
        f.pressed?.discardDisplayList()
        f.normal = null
        f.pressed = null
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
        if (on) {
            endFades(f)
            val now = SystemClock.uptimeMillis()
            f.fast = now - lastDownAt < (f.node.motion?.transition?.ms ?: 0)
            lastDownAt = now
        }
        f.node.motion?.let { pressMoves(f, on, it) }
        if (on) {
            f.node.motion?.effect?.let { playEffect(f.node, ref, it) }
            // a finger's place is its press's alone (a later press without a touch, a screen reader's, plays from the middle)
            touched = null
        }
        invalidate()
    }

    /** Where a finger came down on key [ref], in this view: what its press's effect plays from (A3). */
    fun touchedAt(ref: KeyRef, x: Float, y: Float) {
        touched = ref
        touchedX = x
        touchedY = y
    }

    // A3: a key's effect played at each press, typed fast too (its pools bound it); where 键盘动效 may move keys
    // alone, from where the finger came down on it (a screen reader's or a test's press: its middle)
    private fun playEffect(k: KeyNode, ref: KeyRef, effect: KeyEffect) {
        if (!KeyMotionSwitch.on(context)) return
        val at = touched == ref
        effects.play(effect, k.shape, if (at) touchedX else k.anchor.x.toFloat(), if (at) touchedY else k.anchor.y.toFloat())
    }

    /** The keys' press effects, for tests (A3). */
    @get:VisibleForTesting
    internal val keyEffects: KeyEffects get() = effects

    // the last key's down (uptime ms): a key pressed within its fade's length of it is typed fast
    private var lastDownAt = Long.MIN_VALUE / 2

    // the other keys' release fades and crossfades (Shift's) ended (at rest) as a key goes down: typing fast, a key's
    // frame draws no fades with it
    private fun endFades(pressed: Faces) {
        for (o in faces) {
            val m = o.moving ?: continue
            if (o !== pressed && (m.releasing || m.leaving != null)) m.anim?.end()
        }
    }

    /** Animators made for the keys' motion (060-render-p3 A1): none while 键盘动效 may not move them. */
    @get:VisibleForTesting
    var animatorsMade = 0
        private set

    // 060-render-p3 §4.1: the pressed face shown whole at once (CanvasFrame swaps the alphas), only its scale and
    // sink moving, 80 ms at most; let go, it fades out over the face under it. A press while it moves jumps to
    // pressed. Not to move ([KeyMotionSwitch]): nothing kept, nothing made, drawn as a theme without motion
    private fun pressMoves(f: Faces, on: Boolean, motion: Motion) {
        if (!KeyMotionSwitch.on(context)) {
            f.moving?.stop()
            f.moving = null
            applyMotion(f)
            return
        }
        val m = f.moving ?: Moving().also { f.moving = it }
        val interrupted = m.anim != null || m.leaving != null
        m.stop()
        val scale = motion.pressScale.toFloat()
        val sink = motion.pressSink.toFloat()
        if (on) {
            val press = motion.press.takeUnless { f.fast }
            if (press != null && !interrupted) {
                m.scale = 1f
                m.sink = 0f
                animate(f, m, press, { v -> m.scale = 1f + (scale - 1f) * v; m.sink = sink * v }, {})
            } else {
                m.scale = scale
                m.sink = sink
            }
        } else {
            val fade = motion.transition
            if (fade == null || f.fast || f.pressed?.hasDisplayList() != true) {
                m.scale = 1f
                m.sink = 0f
            } else {
                val s0 = m.scale
                val k0 = m.sink
                m.releasing = true
                animate(f, m, fade, { v -> m.alpha = 1f - v; m.scale = s0 + (1f - s0) * v; m.sink = k0 * (1f - v) }) {
                    m.releasing = false
                    m.alpha = 1f
                    m.scale = 1f
                    m.sink = 0f
                }
            }
        }
        applyMotion(f)
    }

    // the face [was] showed fading out over [f]'s new one, where its look has a transition and the key may move
    private fun crossFade(was: Faces, f: Faces, still: Boolean) {
        val old = was.moving
        // let go and fading still (Shift's own release): that face goes on fading from where it is, else the one it showed
        val fading = old?.releasing == true
        val from = (if (fading) was.pressed else was.normal).takeIf { it?.hasDisplayList() == true }
        val alpha = if (fading) old!!.alpha else 1f
        old?.stop()
        val motion = f.node.motion ?: return
        if (!KeyMotionSwitch.on(context)) return
        if (f.down) {
            // held (laid out anew or relabelled too): its new pressed face as pressed as the old one was, not back to whole
            f.moving = Moving().also { it.scale = motion.pressScale.toFloat(); it.sink = motion.pressSink.toFloat() }
            return
        }
        if (still) return
        val t = motion.transition ?: return
        from ?: return
        val m = Moving().also { f.moving = it }
        m.leaving = from
        from.alpha = alpha
        animate(f, m, t, { v -> from.alpha = alpha * (1f - v) }) {
            m.leaving = null
            letGoOf(from)
        }
    }

    // one animator for [m]: each frame [step] (the eased fraction) then the node's properties set and a frame asked
    // for; its end [end], unless stopped first. Nothing recorded, no frame asked once it ends
    private fun animate(f: Faces, m: Moving, t: Transition, step: (Float) -> Unit, end: () -> Unit) {
        animatorsMade++
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = t.ms.toLong()
        a.interpolator = KeyMotionSwitch.curve(t)
        a.addUpdateListener {
            Trace.beginSection(ANIM_TRACE)
            try {
                step(it.animatedFraction)
                applyMotion(f)
                invalidate()
            } finally {
                Trace.endSection()
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (m.anim !== a) return
                m.anim = null
                end()
                applyMotion(f)
                invalidate()
            }
        })
        m.anim = a
        a.start()
    }

    // [f]'s pressed node's properties as it moves now: its alpha while let go fading, its scale about its key's
    // middle and its sink; as still where it is not moving
    private fun applyMotion(f: Faces) {
        if (f.node.motion == null) return
        val node = f.pressed ?: return
        val m = f.moving
        if (m?.releasing == true) node.alpha = m.alpha
        val s = m?.scale ?: 1f
        node.pivotX = (f.node.bounds.centerX - node.left).toFloat()
        node.pivotY = (f.node.bounds.centerY - node.top).toFloat()
        node.scaleX = s
        node.scaleY = s
        node.translationY = m?.sink ?: 0f
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
     * Slot [id]'s cell in this view, into [out], as the scene at the view's size now has it (made
     * again if the size changed: one-handed, split, another height): where the keyboard puts its own
     * view (the handwriting pad, 060-render-p2 S5b); false where the scene has no such slot, or none.
     */
    fun slot(id: String, out: Rect): Boolean {
        val s = scene?.slots?.firstOrNull { it.id == id } ?: return false
        out.set(s.bounds)
        return true
    }

    /** The handwriting pad's ink in slot [id] as the scene has it (060-render-p3 T7b); null where it has no such slot. */
    fun slotInk(id: String): Ink? = scene?.slots?.firstOrNull { it.id == id }?.ink

    /**
     * Where a popup over key [ref] is placed, in the window, into [out]: its face across and its cell
     * down; false where the scene drawn last has no such key.
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

    /** Where key [id] is drawn, in the window: a popup's anchor. */
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

    /** Key [id]'s pressed node, recorded if it was not, its motion's properties set on it as a frame sets them (A1). */
    @VisibleForTesting
    fun pressedNode(id: String, copy: Int = 0): RenderNode? {
        scene
        val f = faces.firstOrNull { it.node.id == id && it.node.copy == copy } ?: return null
        val n = f.pressed.live() ?: record(f.node.id, f.node.bounds, f.node.ops(true)).also { f.pressed = it }
        if (f.node.motion != null) applyMotion(f)
        return n
    }

    /** Key [id]'s motion's animator running; null where none is (a test moves it by its play time). */
    @VisibleForTesting
    fun animator(id: String, copy: Int = 0): ValueAnimator? =
        faces.firstOrNull { it.node.id == id && it.node.copy == copy }?.moving?.anim

    /** The face key [id] had before its state changed, fading out over its new one; null where none is. */
    @VisibleForTesting
    fun leavingNode(id: String, copy: Int = 0): RenderNode? =
        faces.firstOrNull { it.node.id == id && it.node.copy == copy }?.moving?.leaving

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
     * A key's place on screen through every transform above this view: ExploreByTouchHelper puts a node's
     * parent bounds at the view's place on screen unscaled, so in the theme page's half-size preview
     * (ThemeFragment) TalkBack framed and found the keys at twice their size, the right half missing.
     */
    private fun onScreen(left: Double, top: Double, right: Double, bottom: Double): Rect {
        val m = Matrix()
        var v: View = this
        while (true) {
            val p = v.parent as? View ?: break
            m.postConcat(v.matrix)
            m.postTranslate((v.left - p.scrollX).toFloat(), (v.top - p.scrollY).toFloat())
            v = p
        }
        val at = IntArray(2).also(v::getLocationOnScreen)
        m.postTranslate(at[0].toFloat(), at[1].toFloat())
        val r = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()).also(m::mapRect)
        return Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
    }

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
            val said = SceneA11y.of(k, host.board, ::stringNamed)
            node.text = said.text
            node.contentDescription = said.description
            node.className = SceneA11y.CLASS_NAME
            val b = k.bounds
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
            node.setBoundsInScreen(onScreen(b.left, b.top, b.right, b.bottom))
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
    private fun stringNamed(name: String): String? = host.string(name) ?: A11Y_STRINGS[name]?.let(context::getString)

    private companion object {
        const val NANOS_A_MS = 1e6

        // a node's room past what it draws, for its edges' anti-aliasing
        const val EDGE_PX = 2.0

        /** A trace section round each frame of the keys' motion (060-render-p3 §4.3, A0's perf.py times it). */
        const val ANIM_TRACE = "canvas anim"

        // where the view is over the picture's base (x, y) and the base's size (w, h)
        const val PICTURE_PLACE = 4

        val A11Y_STRINGS = mapOf(
            "a11y_key_shift" to org.fcitx.fcitx5.android.R.string.a11y_key_shift,
            "a11y_key_backspace" to org.fcitx.fcitx5.android.R.string.a11y_key_backspace,
            // 展开候选's column (expanded-side.xml)
            "a11y_candidates_prev_page" to org.fcitx.fcitx5.android.R.string.a11y_candidates_prev_page,
            "a11y_candidates_next_page" to org.fcitx.fcitx5.android.R.string.a11y_candidates_next_page,
            // the number pad's space: no label (the typing keyboards' says the input method)
            "a11y_key_space" to org.fcitx.fcitx5.android.R.string.a11y_key_space,
            "a11y_key_switch_language" to org.fcitx.fcitx5.android.R.string.a11y_key_switch_language,
            "a11y_key_separator" to org.fcitx.fcitx5.android.R.string.a11y_key_separator,
            // the symbol panel's way to the number pad
            "a11y_key_numbers" to org.fcitx.fcitx5.android.R.string.a11y_key_number_pad,
            // the emoji's row (emoji.xml, emoticon.xml): its search, the way back from the emoticons
            "a11y_key_emoji_search" to org.fcitx.fcitx5.android.R.string.emoji_search,
            "a11y_key_emoji" to org.fcitx.fcitx5.android.R.string.a11y_key_emoji,
            SceneA11y.SHIFT_ONCE to org.fcitx.fcitx5.android.R.string.a11y_state_shift_once,
            SceneA11y.CAPS_LOCK to org.fcitx.fcitx5.android.R.string.a11y_state_caps_lock,
        )
    }
}

// what is drawn under the keys: the board's base and the slots' faces (a slot's view drawn over it, its own).
// Not the board's own picture (the base's first op, the whole board's): the view under the keyboard (the picture
// host's base, ThemePictureDrawable.board) shows it already, and a picture with alpha laid twice is darker than
// theme 3.0 drew it. A blank place's picture face after it is the key's and stays.
internal fun Scene.under(drawsBase: Boolean): List<Op> =
    (if (drawsBase) base.filterIndexed { i, op -> !(i == 0 && op is Op.Picture && op.shape.bounds == Box(0.0, 0.0, width, height)) } else emptyList())
        .let { b -> if (slots.all { it.ops.isEmpty() }) b else b + slots.flatMap { it.ops } }

/** Whether this is key [ref]: a split row's second copy ([KeyNode.copy] 1) is its own. */
internal fun KeyNode.of(ref: KeyRef) = id == ref.id && (copy != 0) == ref.copy

/** [b] rounded to whole pixels, as a view's place is. */
internal fun Rect.set(b: Box) = set(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
