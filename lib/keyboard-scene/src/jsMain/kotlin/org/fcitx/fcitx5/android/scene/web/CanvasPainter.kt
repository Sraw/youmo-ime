/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.TextStroke
import org.fcitx.fcitx5.android.scene.theme.Argb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What a page gives the painter besides the scene: its files, the keyboard's picture, the icons, the fonts' CSS families. */
internal interface PaintAssets {
    /** The CSS font-family list a scene's font name (`system`, a theme's `<font>`) is drawn and measured in. */
    fun family(font: String): String

    /** The theme's image [file] (a nine-slice's): `{image, width, height}`, the image a 2D context draws; null where there is none (yet). */
    fun image(file: String): dynamic

    /** The theme's picture, blurred [blurDp] and at [brightness] (null: the theme's own), the scene's size, drawn at its top left; null where there is none. */
    fun picture(blurDp: Int?, brightness: Int?): dynamic

    /** The icon [name] as the app draws it: `{vw, vh, paths: [{d, rule?, stroke?, alpha?, transform?}]}`; null where there is none. */
    fun icon(name: String): dynamic
}

/**
 * A scene's ops ([Op]) drawn on a 2D canvas context (CanvasRenderingContext2D): the app's ScenePainter for the
 * theme maker's page, op for op, so the page shows what the phone does. It computes nothing of where things go
 * (the engine did), only how a canvas draws each, as ScenePainter has Android draw it:
 * - a blur is Skia's ([sigma]: what BlurMaskFilter makes of its radius), drawn as a canvas's shadow of the
 *   shape put far off (a shadow's blur is every browser's; `filter` is not), so nothing of the shape itself shows;
 * - a shape less another (a key cap's band under its face) is the shape clipped to outside the other, and an
 *   inner shadow its ring with the hole cut by the even-odd rule: Android's Path.op, for the shapes a scene has;
 * - text is set as Paint sets it: squeezed by its scale from where it starts, letter-spaced half a space in
 *   from its start (Paint.letterSpacing's), outlined by a stroke twice the width drawn with its fill
 *   (FILL_AND_STROKE), its shadow both again, moved and blurred;
 * - a nine-slice cut as NineSliceLayout cuts it, its source's pixels whole; an icon its vector's paths in its
 *   tint, at whole pixels as a drawable's bounds are.
 * [width] and [height] are the scene's: where the picture is laid.
 */
internal class CanvasPainter(
    private val ctx: dynamic,
    private val assets: PaintAssets,
    private val density: Double,
    private val width: Double,
    private val height: Double,
) {
    fun draw(ops: List<Op>) = ops.forEach(::draw)

    fun draw(op: Op) {
        when (op) {
            is Op.Fill -> if (op.less == null) fill(op.shape, op.color) else fillLess(op.shape, op.less!!, op.color)
            is Op.Ripple -> fill(op.shape, op.color)
            is Op.Shadow -> blurred(op.blur, op.color) {
                trace(op.shape)
                ctx.fill()
            }
            is Op.InnerShadow -> innerShadow(op)
            is Op.Stroke -> {
                trace(op.shape)
                ctx.strokeStyle = css(op.color)
                ctx.lineWidth = op.width
                // Paint's miter limit, not a canvas's 10
                ctx.miterLimit = MITER_LIMIT
                ctx.lineJoin = "miter"
                ctx.stroke()
            }
            is Op.Gradient -> gradient(op)
            is Op.NineSlice -> nineSlice(op)
            is Op.Picture -> picture(op)
            is Op.Text -> text(op)
            is Op.Icon -> icon(op)
        }
    }

    private fun fill(shape: Shape, color: Argb) {
        trace(shape)
        ctx.fillStyle = css(color)
        ctx.fill()
    }

    // what of [shape] is outside [less]: clipped to a box round both less [less] (even-odd), then filled
    private fun fillLess(shape: Shape, less: Shape, color: Argb) {
        ctx.save()
        val round = shape.bounds.union(less.bounds)
        ctx.beginPath()
        ctx.rect(round.left - 1, round.top - 1, round.width + 2, round.height + 2)
        add(less)
        ctx.clip("evenodd")
        fill(shape, color)
        ctx.restore()
    }

    // inside the face, its ring less its hole (even-odd: the hole is inside the ring) in the shadow's colour, blurred
    private fun innerShadow(op: Op.InnerShadow) {
        ctx.save()
        trace(op.shape)
        ctx.clip()
        blurred(op.blur, op.color) {
            val r = op.ring
            ctx.beginPath()
            ctx.rect(r.left, r.top, r.width, r.height)
            add(op.hole)
            ctx.fill("evenodd")
        }
        ctx.restore()
    }

    /**
     * [draw] (a path filled, a text set: in the fill style it is given) blurred [radius] px in [color], or as it
     * is where [radius] is none: a canvas's shadow, its blur and offset in the device's pixels, of what [draw]
     * draws far to the left, so only the shadow lands where it was.
     */
    private inline fun blurred(radius: Double, color: Argb, draw: () -> Unit) {
        if (radius <= 0) {
            ctx.fillStyle = css(color)
            ctx.strokeStyle = css(color)
            draw()
            return
        }
        ctx.save()
        val (a, b) = deviceX()
        ctx.shadowColor = css(color)
        ctx.shadowBlur = 2 * sigma(radius) * sqrt(a * a + b * b)
        ctx.shadowOffsetX = a * FAR
        ctx.shadowOffsetY = b * FAR
        ctx.translate(-FAR, 0.0)
        // opaque: the shadow is its colour's alpha, the shape's coverage times it
        ctx.fillStyle = OPAQUE
        ctx.strokeStyle = OPAQUE
        draw()
        ctx.restore()
    }

    // where a step of 1 across goes on the device: the context's transform's first column (none known: 1, 0)
    private fun deviceX(): Pair<Double, Double> {
        if (ctx.getTransform == undefined) return 1.0 to 0.0
        val m = ctx.getTransform()
        return (m.a as Number).toDouble() to (m.b as Number).toDouble()
    }

    private fun gradient(op: Op.Gradient) {
        // CSS's: 0deg up, 90deg right, the line long enough for the corners to reach the ends' colours (ScenePainter's)
        val b = op.shape.bounds
        val a = op.angle * PI / HALF_TURN
        val dx = sin(a)
        val dy = -cos(a)
        val half = abs(b.width / 2 * dx) + abs(b.height / 2 * dy)
        val g = ctx.createLinearGradient(b.centerX - dx * half, b.centerY - dy * half, b.centerX + dx * half, b.centerY + dy * half)
        op.stops.forEach { (color, at) -> g.addColorStop(at.coerceIn(0.0, 1.0), css(color)) }
        trace(op.shape)
        ctx.fillStyle = g
        ctx.fill()
    }

    private fun nineSlice(op: Op.NineSlice) {
        val image = assets.image(op.file)
        if (image == null || image == undefined) {
            // theme 3.0's colour where the image is not (yet)
            op.fallback?.let { fill(op.shape, it) }
            return
        }
        val i = op.insets
        val pieces = NineSliceCut.of(
            (image.width as Number).toDouble(), (image.height as Number).toDouble(), i[0], i[1], i[2], i[3], op.pxPerDp, op.shape.bounds, density,
        )
        ctx.save()
        trace(op.shape)
        ctx.clip()
        for (p in pieces) {
            // the source's whole pixels, as Android's Rect of them
            val sl = p.src.left.toInt()
            val st = p.src.top.toInt()
            val sw = p.src.right.toInt() - sl
            val sh = p.src.bottom.toInt() - st
            if (sw > 0 && sh > 0) ctx.drawImage(image.image, sl, st, sw, sh, p.dst.left, p.dst.top, p.dst.width, p.dst.height)
        }
        ctx.restore()
    }

    private fun picture(op: Op.Picture) {
        val p = assets.picture(op.blurDp?.roundToInt(), op.brightness?.roundToInt())
        if (p != null && p != undefined) {
            ctx.save()
            trace(op.shape)
            ctx.clip()
            ctx.drawImage(p, 0.0, 0.0, width, height)
            ctx.restore()
        }
        // the colour over it, there with the picture or without (theme 3.0's key face)
        op.over?.let { fill(op.shape, it) }
    }

    private fun text(op: Op.Text) {
        val rotated = op.rotation != 0.0
        if (rotated) {
            ctx.save()
            ctx.translate(op.x, op.baseline)
            ctx.rotate(op.rotation * PI / HALF_TURN)
            ctx.translate(-op.x, -op.baseline)
        }
        op.shadow?.let { s -> blurred(s.blur, s.color) { glyphs(op, op.x + s.dx, op.baseline + s.dy, op.stroke) } }
        op.stroke?.let { s ->
            ctx.fillStyle = css(s.color)
            ctx.strokeStyle = css(s.color)
            glyphs(op, op.x, op.baseline, s)
        }
        if (!op.color.transparent) {
            ctx.fillStyle = css(op.color)
            glyphs(op, op.x, op.baseline, null)
        }
        if (rotated) ctx.restore()
    }

    // the glyphs at ([x], [y]) in the styles set, outlined by [stroke] where given (its colour the fill's: FILL_AND_STROKE)
    private fun glyphs(op: Op.Text, x: Double, y: Double, stroke: TextStroke?) {
        val spacing = op.letterSpacing * op.size
        ctx.font = font(op.weight, op.size, assets.family(op.font))
        ctx.letterSpacing = "${spacing}px"
        ctx.textAlign = "left"
        ctx.textBaseline = "alphabetic"
        ctx.save()
        // Paint spaces letters half before each, a canvas all after: moved by the half, squeezed as the
        // letters are (Minikin's spacing is the size times the scale; a canvas's is, under the scale below)
        ctx.translate(x + spacing * op.scaleX / 2, y)
        if (op.scaleX != 1.0) ctx.scale(op.scaleX, 1.0)
        if (stroke != null) {
            ctx.lineWidth = 2 * stroke.width
            ctx.lineJoin = "round"
            ctx.strokeText(op.text, 0.0, 0.0)
        }
        ctx.fillText(op.text, 0.0, 0.0)
        ctx.restore()
    }

    private fun icon(op: Op.Icon) {
        val icon = assets.icon(op.name)
        if (icon == null || icon == undefined) return
        // whole pixels, as the drawable's bounds
        val b = op.box
        val left = b.left.roundToInt()
        val top = b.top.roundToInt()
        val w = b.right.roundToInt() - left
        val h = b.bottom.roundToInt() - top
        ctx.save()
        ctx.translate(left.toDouble(), top.toDouble())
        if (op.rotation != 0.0) {
            ctx.translate(w / 2.0, h / 2.0)
            ctx.rotate(op.rotation * PI / HALF_TURN)
            ctx.translate(-w / 2.0, -h / 2.0)
        }
        ctx.scale(w / (icon.vw as Number).toDouble(), h / (icon.vh as Number).toDouble())
        val tint = css(op.tint)
        val paths: Array<dynamic> = icon.paths.unsafeCast<Array<dynamic>>()
        for (p in paths) {
            ctx.save()
            (p.transform as String?)?.let(::transform)
            (p.alpha as Number?)?.let { ctx.globalAlpha = it.toDouble() }
            val path = newPath2D(p.d as String)
            if (p.stroke != null && p.stroke != undefined) {
                ctx.strokeStyle = tint
                ctx.lineWidth = (p.stroke as Number).toDouble()
                ctx.stroke(path)
            } else {
                ctx.fillStyle = tint
                ctx.fill(path, (p.rule as String?) ?: "nonzero")
            }
            ctx.restore()
        }
        ctx.restore()
    }

    // an icon's group transform as SVG writes it (`translate(x y) rotate(r) scale(x y)`), applied in order
    private fun transform(t: String) {
        for (m in STEP.findAll(t)) {
            val v = m.groupValues[2].trim().split(SPACE).filter { it.isNotEmpty() }.map { it.toDouble() }
            when (m.groupValues[1]) {
                "translate" -> ctx.translate(v[0], v.getOrElse(1) { 0.0 })
                "rotate" -> ctx.rotate(v[0] * PI / HALF_TURN)
                "scale" -> ctx.scale(v[0], v.getOrElse(1) { v[0] })
            }
        }
    }

    private fun trace(shape: Shape) {
        ctx.beginPath()
        add(shape)
    }

    // [shape] added to the path: a rectangle rounded as Skia rounds it (radii too large for a side all scaled
    // down alike), or the path's edges
    private fun add(shape: Shape) {
        when (shape) {
            is Shape.Rect -> {
                val b = shape.box
                val r = shape.radii
                if (r.all { it <= 0.0 }) {
                    ctx.rect(b.left, b.top, b.width, b.height)
                } else {
                    roundRect(b, r)
                }
            }
            is Shape.Path -> shape.ops.forEach {
                when (it) {
                    is Shape.Path.Op.MoveTo -> ctx.moveTo(it.x, it.y)
                    is Shape.Path.Op.LineTo -> ctx.lineTo(it.x, it.y)
                    Shape.Path.Op.Close -> ctx.closePath()
                }
            }
        }
    }

    private fun roundRect(b: Box, radii: List<Double>) {
        val k = listOf(
            1.0, b.width / (radii[0] + radii[1]), b.width / (radii[2] + radii[3]), b.height / (radii[0] + radii[3]), b.height / (radii[1] + radii[2]),
        ).filter { it.isFinite() && it > 0 }.fold(1.0) { a, x -> min(a, x) }
        val r = radii.map { maxOf(0.0, it) * k }
        val tl = r[0]
        val tr = r[1]
        val br = r[2]
        val bl = r[3]
        ctx.moveTo(b.left + tl, b.top)
        ctx.lineTo(b.right - tr, b.top)
        if (tr > 0) ctx.arc(b.right - tr, b.top + tr, tr, -PI / 2, 0.0)
        ctx.lineTo(b.right, b.bottom - br)
        if (br > 0) ctx.arc(b.right - br, b.bottom - br, br, 0.0, PI / 2)
        ctx.lineTo(b.left + bl, b.bottom)
        if (bl > 0) ctx.arc(b.left + bl, b.bottom - bl, bl, PI / 2, PI)
        ctx.lineTo(b.left, b.top + tl)
        if (tl > 0) ctx.arc(b.left + tl, b.top + tl, tl, PI, PI * 3 / 2)
        ctx.closePath()
    }

    companion object {
        /** A colour as CSS writes it, its alpha 0–1. */
        fun css(c: Argb): String = "rgba(${c.red},${c.green},${c.blue},${c.alpha / 255.0})"

        /** A canvas's `font`: the weight, the size in px, the families. */
        fun font(weight: Int, size: Double, family: String): String = "$weight ${size}px $family"

        /** The Gaussian's sigma Skia gives a blur of [radius] (SkBlurMask::ConvertRadiusToSigma: BlurMaskFilter's). */
        fun sigma(radius: Double): Double = if (radius > 0) SIGMA_SCALE * radius + SIGMA_BIAS else 0.0

        private const val SIGMA_SCALE = 0.57735
        private const val SIGMA_BIAS = 0.5
        private const val MITER_LIMIT = 4.0
        private const val HALF_TURN = 180.0

        // far enough that a shape put there shows nothing on any canvas a page makes
        const val FAR = 100_000.0
        private const val OPAQUE = "#000000"
        private val STEP = Regex("(translate|rotate|scale)\\(([^)]*)\\)")
        private val SPACE = Regex("[\\s,]+")
    }
}

private fun newPath2D(d: String): dynamic {
    val make: dynamic = js("(function (d) { return new Path2D(d); })")
    return make(d)
}
