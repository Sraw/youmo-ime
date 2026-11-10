/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.animation.TimeAnimator
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.os.Trace
import android.view.View
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.scene.KeyEffect
import org.fcitx.fcitx5.android.scene.Shape
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The keys' press effects (060-render-p3 A3) over [view]'s keys: a ripple image grown from where the finger
 * is, clipped to the key's face; particles flying out from there. Each image recorded once into a node of
 * its own ([sprites]) that every ripple and particle of it draws; a ripple a node clipped to its key's
 * face (a rounded rectangle's outline, another shape's path) round a node of the image, a particle a node of the image; all from pools that never grow past
 * [MAX_RIPPLES] and [MAX_PARTICLES] (more at once: the oldest taken). A frame only sets the nodes' alpha,
 * scale and place ([tick]): nothing recorded, nothing made. One [TimeAnimator] while any plays, ended when
 * none does.
 */
internal class KeyEffects(private val view: View, private val images: BoardImages) {

    // an image recorded once, at its decoded size; [sample] its file's width over that (KeyImageBudget may decode it smaller)
    private class Sprite(val node: RenderNode, val width: Int, val height: Int, val sample: Float)

    private val sprites = HashMap<String, Sprite>()

    private abstract class Playing {
        var sprite: Sprite? = null
        var start = 0L

        // when it was taken, in order (two in the same ms told apart)
        var taken = 0L
        var ms = 0
        var live = false
    }

    // a ripple: [clip] at its key's face, clipped to its outline, drawing [inner], the image about where the finger was
    // fading nodes of one image each: drawn straight at their alpha, no layer of their own a frame (the same look)
    private class Ripple : Playing() {
        val clip = RenderNode("ripple clip").apply { setHasOverlappingRendering(false) }
        val inner = RenderNode("ripple").apply { setHasOverlappingRendering(false) }
        var scale = 1f
    }

    private class Particle : Playing() {
        val node = RenderNode("particle").apply { setHasOverlappingRendering(false) }
        var x = 0f
        var y = 0f
        var dx = 0f
        var dy = 0f
    }

    private val ripples = ArrayList<Ripple>(MAX_RIPPLES)
    private val particles = ArrayList<Particle>(MAX_PARTICLES)

    // the nodes taken so far: each one's turn, the oldest the lowest
    private var turns = 0L

    // a key's outline or path, made again for each ripple into these (no allocation a press)
    private val outline = Outline()
    private val path = Path()
    private val box = RectF()
    private val radii = FloatArray(RADII)

    // the presses played: each one's particles a flight of their own, the same for the same count (reproducible)
    private var presses = 0

    private val ticker = TimeAnimator().apply { setTimeListener { _, _, _ -> tick(SystemClock.uptimeMillis()) } }

    // the path each playing ripple is clipped to (null: its outline's rounded rectangle), for a test
    private val clipPaths = HashMap<Ripple, Path?>()

    /** Nodes the pools hold: never more than [MAX_RIPPLES] + [MAX_PARTICLES]. */
    @get:VisibleForTesting
    val pooled: Int get() = ripples.size + particles.size

    /** Whether its animator runs (asks for frames). */
    @get:VisibleForTesting
    val running: Boolean get() = ticker.isStarted

    /** Effects playing now. */
    @get:VisibleForTesting
    val playing: Int get() = ripples.count { it.live } + particles.count { it.live }

    /** The particles playing now, in their pool's order: where each is (a test reads their flight). */
    @VisibleForTesting
    fun particleNodes(): List<RenderNode> = particles.filter { it.live }.map { it.node }

    /** The ripples playing now: the node of the image, its scale and place. */
    @VisibleForTesting
    fun rippleNodes(): List<RenderNode> = ripples.filter { it.live }.map { it.inner }

    /** The clips of the ripples playing now. */
    @VisibleForTesting
    fun rippleClips(): List<RenderNode> = ripples.filter { it.live }.map { it.clip }

    /** The path each ripple playing now is clipped to in its node; null where its outline (a rounded rectangle's) clips it. */
    @VisibleForTesting
    fun rippleClipPaths(): List<Path?> = ripples.filter { it.live }.map { clipPaths[it] }

    /** [effect] played over [face] (its key's [shape]) from ([x], [y]) in the view; nothing where its image is not in. */
    fun play(effect: KeyEffect, shape: Shape, x: Float, y: Float) {
        val sprite = sprite(effect.file) ?: return
        val now = SystemClock.uptimeMillis()
        when (effect) {
            is KeyEffect.Ripple -> ripple(effect, sprite, shape, x, y, now)
            is KeyEffect.Particles -> particles(effect, sprite, x, y, now)
        }
        presses++
        tick(now)
        if (!ticker.isStarted) ticker.start()
    }

    private fun ripple(effect: KeyEffect.Ripple, sprite: Sprite, shape: Shape, x: Float, y: Float, now: Long) {
        val r = taken(ripples, MAX_RIPPLES, ::Ripple)
        val face = shape.bounds
        val l = face.left.roundToInt()
        val t = face.top.roundToInt()
        r.clip.setPosition(l, t, face.right.roundToInt(), face.bottom.roundToInt())
        if (r.sprite !== sprite || !r.inner.hasDisplayList()) {
            draw(r.inner, sprite)
            r.sprite = sprite
        }
        clipTo(r, shape, l, t)
        r.inner.pivotX = sprite.width / 2f
        r.inner.pivotY = sprite.height / 2f
        r.inner.translationX = x - l - sprite.width / 2f
        r.inner.translationY = y - t - sprite.height / 2f
        // the image's longer half grown to the radius
        r.scale = (effect.radius / (max(sprite.width, sprite.height) / 2.0)).toFloat()
        r.start = now
        r.ms = effect.ms
        r.live = true
    }

    private fun particles(effect: KeyEffect.Particles, sprite: Sprite, x: Float, y: Float, now: Long) {
        val ends = effect.ends(presses)
        val scale = view.resources.displayMetrics.density / PX_PER_DP * sprite.sample
        for (i in 0 until effect.count) {
            val p = taken(particles, MAX_PARTICLES, ::Particle)
            if (p.sprite !== sprite || !p.node.hasDisplayList()) {
                draw(p.node, sprite)
                p.sprite = sprite
            }
            p.node.pivotX = sprite.width / 2f
            p.node.pivotY = sprite.height / 2f
            p.node.scaleX = scale
            p.node.scaleY = scale
            p.x = x - sprite.width / 2f
            p.y = y - sprite.height / 2f
            p.dx = ends[2 * i].toFloat()
            p.dy = ends[2 * i + 1].toFloat()
            p.start = now
            p.ms = effect.ms
            p.live = true
        }
    }

    // one not playing from [pool], else a new one while it is short of [most], else the one taken longest ago
    private fun <T : Playing> taken(pool: ArrayList<T>, most: Int, made: () -> T): T {
        var pick: T? = null
        for (i in pool.indices) if (!pool[i].live) pick = pick ?: pool[i]
        if (pick == null && pool.size < most) pick = made().also { pool += it }
        val taken = pick ?: oldest(pool)
        taken.taken = turns++
        return taken
    }

    private fun <T : Playing> oldest(pool: ArrayList<T>): T {
        var oldest = pool[0]
        for (i in 1 until pool.size) if (pool[i].taken < oldest.taken) oldest = pool[i]
        return oldest
    }

    // [node] made to draw [sprite] (a node holding the image's), its own size the image's
    private fun draw(node: RenderNode, sprite: Sprite) {
        node.setPosition(0, 0, sprite.width, sprite.height)
        val c = node.beginRecording()
        try {
            c.drawRenderNode(sprite.node)
        } finally {
            node.endRecording()
        }
    }

    // [file]'s image recorded, once; null where it is not decoded (yet, or at all: that press plays nothing)
    private fun sprite(file: String): Sprite? {
        sprites[file]?.takeIf { it.node.hasDisplayList() }?.let { return it }
        val bitmap = images.bitmap(file) ?: return null
        val node = sprites[file]?.node ?: RenderNode("effect image")
        node.setPosition(0, 0, bitmap.width, bitmap.height)
        val c = node.beginRecording()
        try {
            // filtered: a ripple scales its image up many times
            c.drawBitmap(bitmap, 0f, 0f, FILTERED)
        } finally {
            node.endRecording()
        }
        val sample = images.size(file)?.let { it[0].toFloat() / bitmap.width } ?: 1f
        return Sprite(node, bitmap.width, bitmap.height, sample).also { sprites[file] = it }
    }

    // [r]'s clip node (at [left], [top]) recorded round its image, clipped to [shape]: a rounded rectangle of one radius
    // by its outline (antialiased), another shape by its path in the node (an outline's path clips from API 33 alone;
    // a path clips any shape, concave too, by the even-odd rule the key's hit test reads it by)
    private fun clipTo(r: Ripple, shape: Shape, left: Int, top: Int) {
        val b = shape.bounds
        box.set((b.left - left).toFloat(), (b.top - top).toFloat(), (b.right - left).toFloat(), (b.bottom - top).toFloat())
        val radius = (shape as? Shape.Rect)?.radii?.takeIf { rs -> rs.all { it == rs[0] } }?.get(0)
        if (radius != null) {
            outline.setRoundRect(box.left.roundToInt(), box.top.roundToInt(), box.right.roundToInt(), box.bottom.roundToInt(), radius.toFloat())
            r.clip.setOutline(outline)
        } else {
            pathOf(shape, left, top)
        }
        r.clip.setClipToOutline(radius != null)
        val c = r.clip.beginRecording()
        try {
            if (radius == null) c.clipPath(path)
            c.drawRenderNode(r.inner)
        } finally {
            r.clip.endRecording()
        }
        clipPaths[r] = if (radius == null) Path(path) else null
    }

    // [shape] into [path], at [left], [top] (its box in [box])
    private fun pathOf(shape: Shape, left: Int, top: Int) {
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        when (shape) {
            is Shape.Rect -> {
                for (i in 0 until FOUR) {
                    radii[2 * i] = shape.radii[i].toFloat()
                    radii[2 * i + 1] = shape.radii[i].toFloat()
                }
                path.addRoundRect(box, radii, Path.Direction.CW)
            }
            is Shape.Path -> for (op in shape.ops) {
                when (op) {
                    is Shape.Path.Op.MoveTo -> path.moveTo((op.x - left).toFloat(), (op.y - top).toFloat())
                    is Shape.Path.Op.LineTo -> path.lineTo((op.x - left).toFloat(), (op.y - top).toFloat())
                    Shape.Path.Op.Close -> path.close()
                }
            }
        }
    }

    /** Each playing effect's nodes as they are at [now]; those done let go, the animator ended when none plays. */
    @VisibleForTesting
    fun tick(now: Long) {
        Trace.beginSection(ANIM_TRACE)
        try {
            var any = false
            for (i in ripples.indices) {
                val r = ripples[i]
                val f = if (r.live) fraction(r, now) else continue
                r.live = f < 1f
                if (r.live) {
                    any = true
                    val s = r.scale * easeOut(f)
                    r.inner.scaleX = s
                    r.inner.scaleY = s
                    r.clip.alpha = 1f - f
                }
            }
            for (i in particles.indices) {
                val p = particles[i]
                val f = if (p.live) fraction(p, now) else continue
                p.live = f < 1f
                if (p.live) {
                    any = true
                    val e = easeOut(f)
                    p.node.translationX = p.x + p.dx * e
                    p.node.translationY = p.y + p.dy * e
                    p.node.alpha = 1f - f
                }
            }
            if (!any && ticker.isStarted) ticker.end()
            view.invalidate()
        } finally {
            Trace.endSection()
        }
    }

    private fun fraction(p: Playing, now: Long): Float = if (p.ms <= 0) 1f else ((now - p.start).toFloat() / p.ms).coerceIn(0f, 1f)

    // fast at first, slowing to its end (a decelerate curve)
    private fun easeOut(f: Float): Float = 1f - (1f - f) * (1f - f)

    /** The effects playing drawn over the keys: the ripples, then the particles. */
    fun draw(canvas: Canvas) {
        for (i in ripples.indices) if (ripples[i].live) canvas.drawRenderNode(ripples[i].clip)
        for (i in particles.indices) if (particles[i].live) canvas.drawRenderNode(particles[i].node)
    }

    /** Every effect stopped (the keyboard hidden, motion off): nothing drawn, no frame asked for. */
    fun stop() {
        ripples.forEach { it.live = false }
        particles.forEach { it.live = false }
        clipPaths.clear()
        if (ticker.isStarted) ticker.end()
    }

    /** Stopped, and every node's drawing let go now, not at GC (the view detached); recorded again at the next press. */
    fun release() {
        stop()
        sprites.values.forEach { it.node.discardDisplayList() }
        ripples.forEach {
            it.clip.discardDisplayList()
            it.inner.discardDisplayList()
        }
        particles.forEach { it.node.discardDisplayList() }
    }

    companion object {
        /** The most ripples at once (a key's each press; typing fast, the oldest taken). */
        const val MAX_RIPPLES = 8

        /** The most particles at once (060-render-p3 A3): past it, the oldest taken. */
        const val MAX_PARTICLES = 48

        // a particle's image drawn at this many of its pixels a dp (as an xxhdpi drawable)
        private const val PX_PER_DP = 3f
        private const val RADII = 8
        private const val FOUR = 4
        private const val ANIM_TRACE = "canvas anim"
        private val FILTERED = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
