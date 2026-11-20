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
import org.fcitx.fcitx5.android.input.keyboard.HandwritingMode
import org.fcitx.fcitx5.android.input.scene.CanvasFrame
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneA11y
import org.fcitx.fcitx5.android.scene.Box
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
 * Each key has a [RenderNode] for each of its two faces, so a press only swaps their alpha; nothing is
 * laid out or recorded again. The scene is made only in [onDraw]: a touch in between finds its keys in the
 * last drawn scene, so no finger waits on the engine. TalkBack's virtual nodes keep the old view's resource
 * name (`button_space`) so UAT still finds it.
 */
@SuppressLint("ViewConstructor")
class KeyboardCanvasView(
    context: Context,
    private val images: BoardImages,
    private val host: Host,
) : View(context) {

    /** [pack] has its first frame guarded ([PackDrawGuard]). */
    class Drawn(
        val scene: Scene,
        val shownLook: ThemeLook,
        val fonts: SceneFonts,
        val pack: PackDrawGuard.Key? = null,
        val icons: PackIcons = PackIcons.EMPTY,
    )

    interface Host {
        fun scene(width: Int, height: Int): Drawn?

        fun key(node: KeyNode): SceneKey?

        val board: String? get() = null

        fun built(scene: Scene?) {}
        fun string(name: String): String? = null
    }

    // recorded once each, kept across scenes where the same. [down], [state] and [said] travel with the key,
    // so a scene remade for a typed key looks up nothing by name
    private class Faces(val node: KeyNode, val state: String?, val said: String?) {
        var normal: RenderNode? = null
        var pressed: RenderNode? = null
        var down = false
        val pictured = node.faces.values.any(CanvasFrame::showsPicture)
        var fast = false

        var moving: Moving? = null

        fun of(k: KeyNode) = node.id == k.id && node.copy == k.copy
    }

    // one animator at a time, changing these and node properties alone: nothing recorded, laid out or made
    private inner class Moving {
        var alpha = 1f
        var scale = 1f
        var sink = 0f
        var leaving: RenderNode? = null
        var anim: ValueAnimator? = null
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

    private var painter: ScenePainter? = null
    private var painted: ThemeLook? = null

    private var paintedIcons = PackIcons.EMPTY
    private var built: Scene? = null

    private var drawnUnder: List<Op> = emptyList()
    private var builtFor = 0 to 0
    private var dirty = true
    private var failed = false
    private var pack: PackDrawGuard.Key? = null

    private var scenePack: PackDrawGuard.Key? = null
    private var faces: List<Faces> = emptyList()
    private var base: RenderNode? = null
    private val speech = Speech()

    private var effectsMade: KeyEffects? = null
    private val effects: KeyEffects get() = effectsMade ?: KeyEffects(this, images).also { effectsMade = it }
    private var touched: KeyRef? = null
    private var touchedX = 0f
    private var touchedY = 0f

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
        isClickable = false
    }

    private val current: Scene? get() = built ?: if (failed) null else scene

    var drawsBase = true
        set(value) {
            field = value
            invalidateScene()
        }

    val shownKeys: List<KeyNode> get() = current?.keys.orEmpty()

    val shownScene: Scene? get() = current

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
            if (s != null && s === built) return s
            Timber.d("keyboard canvas: scene %d × %d made in %.2f ms", width, height, (SystemClock.elapsedRealtimeNanos() - start) / NANOS_A_MS)
            if (s == null) {
                // none even of the built-in theme: nothing drawn, touched or read until [invalidateScene]
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
            val repainted = drawn.shownLook != painted || drawn.icons != paintedIcons
            if (repainted) {
                painter = ScenePainter(this, drawn.shownLook, images, drawn.fonts, drawn.icons)
                painted = drawn.shownLook
                paintedIcons = drawn.icons
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
                val was = old.getOrNull(i)?.takeIf { it.of(k) } ?: old.firstOrNull { it.of(k) }
                if (was != null) {
                    f.down = was.down
                    f.fast = was.fast
                    if (!repainted && was.node.faces == k.faces) {
                        f.normal = was.normal
                        f.pressed = was.pressed
                        f.moving = was.moving
                    } else {
                        // a state change (Shift, locked) crossfades; a new layout or relabel in the same state shows at once
                        crossFade(was, f, repainted || was.node.states == k.states || was.node.bounds != k.bounds)
                    }
                    if (was.state != f.state) (said ?: ArrayList<Int>().also { said = it }) += i
                    if (was.said != f.said) (worded ?: ArrayList<Int>().also { worded = it }) += i
                }
                f
            }
            // a motion not carried into the new faces stops: no animator left unreachable
            val carried = faces.mapNotNullTo(HashSet()) { it.moving }
            old.forEach { o -> o.moving?.takeIf { it !in carried }?.stop() }
            // free display lists (and their bitmaps) now, not at GC; a crossfading face is kept till its fade ends
            val kept = faces.flatMapTo(HashSet()) { listOfNotNull(it.normal, it.pressed, it.moving?.leaving) }
            old.forEach { f -> listOfNotNull(f.normal, f.pressed).forEach { if (it !in kept) it.discardDisplayList() } }
            speech.reading.clear()
            speech.invalidateRoot()
            // as a view's state description: TalkBack reads it out when the key has focus
            said?.forEach { speech.invalidateVirtualView(it, AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION) }
            worded?.forEach { speech.invalidateVirtualView(it, AccessibilityEventCompat.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION) }
            host.built(s)
            return s
        }

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
        // a keyboard attached after the picture began to play: on a layer too
        if (PictureHost.of(this)?.animated != null) setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            faces.forEach { it.moving?.stop() }
            effectsMade?.stop()
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        faces.forEach { it.moving?.stop() }
        effectsMade?.release()
        dropBase()
        faces.forEach(::discardFaces)
        super.onDetachedFromWindow()
    }

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

    fun pictureChanged() {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        dropPictures()
    }

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
        // a pack's first frame of the process is marked till drawn (PackDrawGuard): one that takes the keyboard
        // down is not drawn again on its own. A pending mark is cleared when another theme draws
        PackDrawGuard.shows(this, scenePack)
        if (pack?.let { PackDrawGuard.drawing(it) } == false) pack = null
        if (painter.missed) {
            painter.missed = false
            dropBase()
            faces.forEach(::discardFaces)
        }
        if (!canvas.isHardwareAccelerated) {
            // a software canvas (screenshots) draws no RenderNode
            painter.missed = false
            painter.draw(canvas, drawnUnder)
            faces.forEach { painter.draw(canvas, drawn(it.node, it.down)) }
            pack?.let(PackDrawGuard::frameDrawn)
            return
        }
        canvas.drawRenderNode(base.live() ?: record("base", Box(0.0, 0.0, width.toDouble(), height.toDouble()), drawnUnder).also { base = it })
        for (f in faces) {
            val shown = CanvasFrame.faces(f.down, f.normal.live() != null, f.pressed.live() != null)
            for (i in shown.indices) {
                val face = shown[i]
                val node = if (face.record) {
                    record(f.node.id, f.node.bounds, drawn(f.node, face.pressed)).also { if (face.pressed) f.pressed = it else f.normal = it }
                } else {
                    (if (face.pressed) f.pressed else f.normal)!!
                }
                node.alpha = face.alpha
                if (face.pressed) applyMotion(f)
                canvas.drawRenderNode(node)
            }
            f.moving?.let { drawLeaving(canvas, it) }
        }
        effectsMade?.draw(canvas)
        // the nodes draw on the RenderThread after this: the frame counts as drawn once committed. A commit
        // callback registered here goes with the next frame (performDraw takes them before drawing). Asked
        // no more once done, nor past a few (PackDrawGuard.askFrame: some devices never call back)
        pack?.let { p ->
            viewTreeObserver.registerFrameCommitCallback { PackDrawGuard.frameDrawn(p) }
            if (PackDrawGuard.askFrame(p)) postInvalidateOnAnimation()
        }
    }

    // its display list freed now, not at GC; never one a key still shows
    private fun letGoOf(node: RenderNode) {
        if (faces.none { it.normal === node || it.pressed === node }) node.discardDisplayList()
    }

    private fun drawLeaving(canvas: Canvas, m: Moving) {
        val leaving = m.leaving ?: return
        if (leaving.hasDisplayList()) canvas.drawRenderNode(leaving) else m.leaving = null
    }

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

    // one the renderer let go of (window gone, memory trimmed) is recorded again
    private fun RenderNode?.live(): RenderNode? = this?.takeIf { it.hasDisplayList() }

    private fun nodeOf(f: Faces): RenderNode = if (f.down) {
        f.pressed.live() ?: record(f.node.id, f.node.bounds, drawn(f.node, true)).also { f.pressed = it }
    } else {
        f.normal.live() ?: record(f.node.id, f.node.bounds, drawn(f.node, false)).also { f.normal = it }
    }

    // sized to what [ops] draw round [bounds]: a key's shadow reaches out of its cell
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

    var faded: ((KeyNode) -> Boolean)? = null

    fun refade() {
        faces.forEach(::discardFaces)
        invalidate()
    }

    private fun drawn(node: KeyNode, pressed: Boolean): List<Op> =
        node.ops(pressed).let { ops -> if (faded?.invoke(node) == true) HandwritingMode.faded(ops) else ops }

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
            touched = null
        }
        invalidate()
    }

    fun touchedAt(ref: KeyRef, x: Float, y: Float) {
        touched = ref
        touchedX = x
        touchedY = y
    }

    private fun playEffect(k: KeyNode, ref: KeyRef, effect: KeyEffect) {
        if (!KeyMotionSwitch.on(context)) return
        val at = touched == ref
        effects.play(effect, k.shape, if (at) touchedX else k.anchor.x.toFloat(), if (at) touchedY else k.anchor.y.toFloat())
    }

    @get:VisibleForTesting
    internal val keyEffects: KeyEffects get() = effects

    private var lastDownAt = Long.MIN_VALUE / 2

    private fun endFades(pressed: Faces) {
        for (o in faces) {
            val m = o.moving ?: continue
            if (o !== pressed && (m.releasing || m.leaving != null)) m.anim?.end()
        }
    }

    @get:VisibleForTesting
    var animatorsMade = 0
        private set

    // the pressed face shows whole at once, only its scale and sink move; let go, it fades out
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

    private fun crossFade(was: Faces, f: Faces, still: Boolean) {
        val old = was.moving
        val fading = old?.releasing == true
        val from = (if (fading) was.pressed else was.normal).takeIf { it?.hasDisplayList() == true }
        val alpha = if (fading) old!!.alpha else 1f
        old?.stop()
        val motion = f.node.motion ?: return
        if (!KeyMotionSwitch.on(context)) return
        if (f.down) {
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

    fun tell(ref: KeyRef, type: Int) {
        val i = current?.keys?.indexOfFirst { it.of(ref) } ?: return
        if (i >= 0) speech.sendEventForVirtualView(i, type)
    }

    fun keyAt(x: Float, y: Float): KeyNode? = current?.keyAt(x.toDouble(), y.toDouble())

    fun cell(ref: KeyRef, out: Rect): Boolean {
        val k = current?.keys?.firstOrNull { it.of(ref) } ?: return false
        out.set(k.bounds)
        return true
    }

    /** The first slot of [kind] (a layout's id= is its own), as the scene at the view's size now has it (remade if the size changed). */
    fun slot(kind: String, out: Rect): Boolean {
        val s = scene?.slots?.firstOrNull { it.kind == kind } ?: return false
        out.set(s.bounds)
        return true
    }

    fun popupBounds(ref: KeyRef, out: Rect, head: Boolean = false): Boolean {
        val k = current?.keys?.firstOrNull { it.of(ref) } ?: return false
        getLocationInWindow(viewAt)
        val face = k.shape.bounds
        val cell = if (head) k.head else k.bounds
        out.set(
            viewAt[0] + face.left.roundToInt(), viewAt[1] + cell.top.roundToInt(),
            viewAt[0] + face.right.roundToInt(), viewAt[1] + cell.bottom.roundToInt(),
        )
        return true
    }

    @VisibleForTesting
    fun keyAt(id: String, copy: Int = 0): KeyNode? = scene?.keys?.firstOrNull { it.id == id && it.copy == copy }

    @VisibleForTesting
    fun bounds(id: String, copy: Int = 0): Rect? {
        val k = keyAt(id, copy) ?: return null
        val at = IntArray(2).also(::getLocationInWindow)
        val b = k.bounds
        return Rect(at[0] + b.left.roundToInt(), at[1] + b.top.roundToInt(), at[0] + b.right.roundToInt(), at[1] + b.bottom.roundToInt())
    }

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

    @VisibleForTesting
    fun shownNode(id: String, copy: Int = 0): RenderNode? {
        scene
        return faces.firstOrNull { it.node.id == id && it.node.copy == copy }?.let(::nodeOf)
    }

    @VisibleForTesting
    fun shownPressed(id: String, copy: Int = 0): Boolean = faces.any { it.node.id == id && it.node.copy == copy && it.down }

    @VisibleForTesting
    fun pressedNode(id: String, copy: Int = 0): RenderNode? {
        scene
        val f = faces.firstOrNull { it.node.id == id && it.node.copy == copy } ?: return null
        val n = f.pressed.live() ?: record(f.node.id, f.node.bounds, drawn(f.node, true)).also { f.pressed = it }
        if (f.node.motion != null) applyMotion(f)
        return n
    }

    @VisibleForTesting
    fun animator(id: String, copy: Int = 0): ValueAnimator? =
        faces.firstOrNull { it.node.id == id && it.node.copy == copy }?.moving?.anim

    @VisibleForTesting
    fun leavingNode(id: String, copy: Int = 0): RenderNode? =
        faces.firstOrNull { it.node.id == id && it.node.copy == copy }?.moving?.leaving

    @VisibleForTesting
    fun alt(id: String, copy: Int = 0): String? = keyAt(id, copy)?.alt?.takeIf { it.isNotEmpty() }

    @VisibleForTesting
    fun spoken(id: String, copy: Int = 0): String? = keyAt(id, copy)?.let { SceneA11y.of(it, ::stringNamed).description }

    @VisibleForTesting
    fun enabled(id: String, copy: Int = 0): Boolean = keyAt(id, copy)?.let { SceneA11y.of(it, ::stringNamed).enabled && host.key(it)?.keyEnabled != false } == true

    @VisibleForTesting
    fun click(id: String, copy: Int = 0): Boolean = keyAt(id, copy)?.let(host::key)?.click() == true

    @get:VisibleForTesting
    val paintedIn: ThemeLook? get() = painted

    @get:VisibleForTesting
    val missed: Boolean get() = painter?.missed == true

    @VisibleForTesting
    fun beforeFrame() {
        moved.onPreDraw()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = speech.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * ExploreByTouchHelper puts parent bounds at the view's screen place unscaled, so in the theme page's
     * half-size preview TalkBack found the keys at twice their size.
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

    /** Click and long click are the key's own, as a view's performClick and performLongClick were. */
    private inner class Speech : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val s = current ?: return INVALID_ID
            val k = s.keyAt(x.toDouble(), y.toDouble()) ?: return INVALID_ID
            return s.keys.indexOf(k)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            current?.keys?.indices?.forEach { virtualViewIds += it }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val k = current?.keys?.getOrNull(virtualViewId)
            if (k == null) {
                // asked of a key gone with the last scene: a node must still have bounds and words
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
            reading.node(virtualViewId, said.description, node)
        }

        val reading = NodeReading({ resources.configuration.locales[0] }, ::sendEventForVirtualView)

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            reading.populate(virtualViewId, current?.keys?.getOrNull(virtualViewId)?.let { SceneA11y.of(it, ::stringNamed).description }, event)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val k = current?.keys?.getOrNull(virtualViewId) ?: return false
            reading.perform(virtualViewId, SceneA11y.of(k, ::stringNamed).description, action, arguments)?.let { return it }
            val key = host.key(k)?.takeIf { it.keyEnabled && KeyState.Disabled !in k.states } ?: return false
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> true.also { key.click(KeyRef.scene(k.id, k.copy)) }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> key.longClickable.also { if (it) key.longClick(KeyRef.scene(k.id, k.copy)) }
                else -> false
            }
        }
    }

    private val resourcePackage: String by lazy { resources.getResourcePackageName(org.fcitx.fcitx5.android.R.id.keyboard_view) }

    private fun stringNamed(name: String): String? = host.string(name) ?: A11Y_STRINGS[name]?.let(context::getString)

    private companion object {
        const val NANOS_A_MS = 1e6

        // a node's room past what it draws, for its edges' anti-aliasing
        const val EDGE_PX = 2.0

        const val ANIM_TRACE = "canvas anim"

        const val PICTURE_PLACE = 4

        val A11Y_STRINGS = mapOf(
            "a11y_key_shift" to org.fcitx.fcitx5.android.R.string.a11y_key_shift,
            "a11y_key_backspace" to org.fcitx.fcitx5.android.R.string.a11y_key_backspace,
            "a11y_candidates_prev_page" to org.fcitx.fcitx5.android.R.string.a11y_candidates_prev_page,
            "a11y_candidates_next_page" to org.fcitx.fcitx5.android.R.string.a11y_candidates_next_page,
            "a11y_key_space" to org.fcitx.fcitx5.android.R.string.a11y_key_space,
            "a11y_key_switch_language" to org.fcitx.fcitx5.android.R.string.a11y_key_switch_language,
            "a11y_key_separator" to org.fcitx.fcitx5.android.R.string.a11y_key_separator,
            "a11y_key_numbers" to org.fcitx.fcitx5.android.R.string.a11y_key_number_pad,
            "a11y_key_emoji_search" to org.fcitx.fcitx5.android.R.string.emoji_search,
            "a11y_key_emoji" to org.fcitx.fcitx5.android.R.string.a11y_key_emoji,
            SceneA11y.SHIFT_ONCE to org.fcitx.fcitx5.android.R.string.a11y_state_shift_once,
            SceneA11y.CAPS_LOCK to org.fcitx.fcitx5.android.R.string.a11y_state_caps_lock,
        )
    }
}

// Not the board's own picture (the base's first op): the picture host behind the keyboard shows it,
// and a picture with alpha laid twice is darker. A later picture (a split gap's) stays.
internal fun Scene.under(drawsBase: Boolean): List<Op> =
    (if (drawsBase) base.filterIndexed { i, op -> !(i == 0 && op is Op.Picture && op.shape.bounds == Box(0.0, 0.0, width, height)) } else emptyList())
        .let { b -> if (slots.all { it.ops.isEmpty() }) b else b + slots.flatMap { it.ops } }

internal fun KeyNode.of(ref: KeyRef) = id == ref.id && (copy != 0) == ref.copy

internal fun Rect.set(b: Box) = set(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
