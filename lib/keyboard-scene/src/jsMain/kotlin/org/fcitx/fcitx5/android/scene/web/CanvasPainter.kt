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
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
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
 * - what Android draws as one shape is drawn on a layer and laid on the canvas once ([laid]): a shape less
 *   another (a key cap's band under its face; an inner shadow's ring less its hole) is the one filled and the
 *   other cut out of it by the nonzero rule (Path.op's DIFFERENCE of two winding fills, a path that crosses
 *   itself too), and text outlined is its stroke and fill together, so a colour not opaque is laid once, not twice;
 * - text is set as Paint sets it: squeezed by its scale from where it starts, letter-spaced half a space in
 *   from its start (Paint.letterSpacing's), outlined by a stroke twice the width drawn with its fill
 *   (FILL_AND_STROKE), its shadow both again, moved and blurred;
 * - a nine-slice cut as NineSliceLayout cuts it, its source's pixels whole; an icon its vector's paths in its
 *   tint, at whole pixels as a drawable's bounds are, each path's stroke as VectorDrawable's defaults.
 * [width] and [height] are the scene's: where the picture is laid. The layers are canvases the size of the
 * context's own ([ctx]`.canvas`): a page's canvas has one.
 */
internal class CanvasPainter(
    private val ctx: dynamic,
    private val assets: PaintAssets,
    private val density: Double,
    private val width: Double,
    private val height: Double,
) {
    // the context drawn on: the canvas's, or a layer's while one is drawn ([laid])
    private var c: dynamic = ctx

    // a canvas without the context's letterSpacing (Safari before 18.4, Firefox before 115) has letters set one by one
    private val spaces: Boolean = hasLetterSpacing(ctx)

    init {
        // layers, areas and text outlines all size themselves by it; fail here, not midway through a scene
        check(ctx.canvas != null && ctx.canvas != undefined) { "a painter needs a context with a canvas" }
    }

    fun draw(ops: List<Op>) = ops.forEach(::draw)

    fun draw(op: Op) {
        when (op) {
            is Op.Fill -> if (op.less == null) fill(op.shape, op.color) else fillLess(op.shape, op.less!!, op.color)
            is Op.Ripple -> fill(op.shape, op.color)
            is Op.Shadow -> blurred(op.blur, op.color) {
                trace(op.shape)
                c.fill()
            }
            is Op.InnerShadow -> innerShadow(op)
            is Op.Stroke -> {
                trace(op.shape)
                c.strokeStyle = css(op.color)
                c.lineWidth = op.width
                // Paint's miter limit, not a canvas's 10
                c.miterLimit = MITER_LIMIT
                c.lineJoin = "miter"
                c.stroke()
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
        c.fillStyle = css(color)
        c.fill()
    }

    // what of [shape] is outside [less]: the one filled on a layer, the other cut out of it
    private fun fillLess(shape: Shape, less: Shape, color: Argb) = laid(area(shape.bounds), color, 0.0) {
        trace(shape)
        c.fill()
        cut(less)
    }

    // inside the face, its ring less its hole in the shadow's colour, blurred: the ring filled on a layer, the
    // hole cut out of it, that blurred as one onto the canvas clipped to the face
    private fun innerShadow(op: Op.InnerShadow) {
        c.save()
        trace(op.shape)
        c.clip()
        val r = op.ring
        laid(area(r), op.color, op.blur) {
            c.beginPath()
            c.rect(r.left, r.top, r.width, r.height)
            c.fill()
            cut(op.hole)
        }
        c.restore()
    }

    // [shape] cut out of what the layer has: the nonzero rule, as Path.op takes a winding path
    private fun cut(shape: Shape) {
        c.globalCompositeOperation = "destination-out"
        trace(shape)
        c.fillStyle = OPAQUE
        c.fill()
        c.globalCompositeOperation = "source-over"
    }

    /**
     * [draw] (paths filled, text set: in the styles it is given) drawn on a layer, within the device's [area], as
     * one thing in [color] blurred [radius] px: opaque there, then laid on the canvas once at [color]'s alpha, or
     * its shadow ([blurred]) where [radius] is some. What overlaps itself in it (a stroke and its fill) is not
     * laid twice, and what is cut out of it is gone before it is blurred.
     */
    private inline fun laid(area: IntArray, color: Argb, radius: Double, draw: () -> Unit) {
        if (area[2] == 0 || area[3] == 0) return
        val l = layer()
        val (a, b) = deviceX()
        val scale = sqrt(a * a + b * b)
        val m = c.getTransform()
        l.save()
        l.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        l.clearRect(area[0], area[1], area[2], area[3])
        l.setTransform(m)
        val solid = if (radius > 0) OPAQUE else css(Argb.of(OPAQUE_ALPHA, color.red, color.green, color.blue))
        l.fillStyle = solid
        l.strokeStyle = solid
        val canvas = c
        c = l
        try {
            draw()
        } finally {
            c = canvas
            l.restore()
        }
        c.save()
        c.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val image = l.canvas
        val x = area[0]
        val y = area[1]
        val w = area[2]
        val h = area[3]
        if (radius > 0) {
            blurred(radius, color, scale) { c.drawImage(image, x, y, w, h, x, y, w, h) }
        } else {
            c.globalAlpha = color.alpha / OPAQUE_ALPHA.toDouble()
            c.drawImage(image, x, y, w, h, x, y, w, h)
        }
        c.restore()
    }

    // the layer for [laid]: a canvas the size of the context's, kept for the next paint of it
    private fun layer(): dynamic {
        val canvas = ctx.canvas
        val w = canvas.width as Int
        val h = canvas.height as Int
        val kept = LAYERS.get(canvas)
        // one of another size (the canvas resized since) is made again
        val fits = kept != null && kept != undefined && kept.canvas.width == w && kept.canvas.height == h
        if (fits) return kept
        val l = newCanvas(canvas, w, h).getContext("2d")
        LAYERS.set(canvas, l)
        return l
    }

    // [box] (in the context's units) on the device: x, y, width, height in whole pixels, round it and on the canvas
    private fun area(box: Box): IntArray {
        val t = c.getTransform()
        val m = listOf(t.a, t.b, t.c, t.d, t.e, t.f).map { (it as Number).toDouble() }
        val corners = listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom)
        val xs = corners.map { (x, y) -> m[0] * x + m[2] * y + m[4] }
        val ys = corners.map { (x, y) -> m[1] * x + m[3] * y + m[5] }
        val left = max(0.0, floor(xs.min()) - 1).toInt()
        val top = max(0.0, floor(ys.min()) - 1).toInt()
        val right = min((ctx.canvas.width as Int).toDouble(), ceil(xs.max()) + 1).toInt()
        val bottom = min((ctx.canvas.height as Int).toDouble(), ceil(ys.max()) + 1).toInt()
        return intArrayOf(left, top, max(0, right - left), max(0, bottom - top))
    }


    /**
     * [draw] (a path filled, a text set, an image laid: in the fill style it is given) blurred [radius] px in
     * [color], or as it is where [radius] is none: a canvas's shadow of what [draw] draws far to the left, so only
     * the shadow lands where it was. Its blur in the device's pixels: the radius times [scale] (the context's).
     */
    private inline fun blurred(radius: Double, color: Argb, scale: Double = deviceScale(), draw: () -> Unit) {
        if (radius <= 0) {
            c.fillStyle = css(color)
            c.strokeStyle = css(color)
            draw()
            return
        }
        c.save()
        val (a, b) = deviceX()
        c.shadowColor = css(color)
        c.shadowBlur = 2 * sigma(radius) * scale
        c.shadowOffsetX = a * FAR
        c.shadowOffsetY = b * FAR
        c.translate(-FAR, 0.0)
        // opaque: the shadow is its colour's alpha, the shape's coverage times it
        c.fillStyle = OPAQUE
        c.strokeStyle = OPAQUE
        draw()
        c.restore()
    }

    // where a step of 1 across goes on the device: the context's transform's first column (none known: 1, 0)
    private fun deviceX(): Pair<Double, Double> {
        if (c.getTransform == undefined) return 1.0 to 0.0
        val m = c.getTransform()
        return (m.a as Number).toDouble() to (m.b as Number).toDouble()
    }

    private fun deviceScale(): Double = deviceX().let { (a, b) -> sqrt(a * a + b * b) }

    private fun gradient(op: Op.Gradient) {
        // CSS's: 0deg up, 90deg right, the line long enough for the corners to reach the ends' colours (ScenePainter's)
        val b = op.shape.bounds
        val a = op.angle * PI / HALF_TURN
        val dx = sin(a)
        val dy = -cos(a)
        val half = abs(b.width / 2 * dx) + abs(b.height / 2 * dy)
        val g = c.createLinearGradient(b.centerX - dx * half, b.centerY - dy * half, b.centerX + dx * half, b.centerY + dy * half)
        op.stops.forEach { (color, at) -> g.addColorStop(at.coerceIn(0.0, 1.0), css(color)) }
        trace(op.shape)
        c.fillStyle = g
        c.fill()
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
        c.save()
        trace(op.shape)
        c.clip()
        for (p in pieces) {
            // the source's whole pixels, as Android's Rect of them
            val sl = p.src.left.toInt()
            val st = p.src.top.toInt()
            val sw = p.src.right.toInt() - sl
            val sh = p.src.bottom.toInt() - st
            if (sw > 0 && sh > 0) c.drawImage(image.image, sl, st, sw, sh, p.dst.left, p.dst.top, p.dst.width, p.dst.height)
        }
        c.restore()
    }

    private fun picture(op: Op.Picture) {
        val p = assets.picture(op.blurDp?.roundToInt(), op.brightness?.roundToInt())
        if (p != null && p != undefined) {
            c.save()
            trace(op.shape)
            c.clip()
            c.drawImage(p, 0.0, 0.0, width, height)
            c.restore()
        }
        // the colour over it, there with the picture or without (theme 3.0's key face)
        op.over?.let { fill(op.shape, it) }
    }

    private fun text(op: Op.Text) {
        val rotated = op.rotation != 0.0
        if (rotated) {
            c.save()
            c.translate(op.x, op.baseline)
            c.rotate(op.rotation * PI / HALF_TURN)
            c.translate(-op.x, -op.baseline)
        }
        val stroke = op.stroke
        op.shadow?.let { s ->
            val x = op.x + s.dx
            val y = op.baseline + s.dy
            if (stroke == null) {
                blurred(s.blur, s.color) { glyphs(op, x, y, null) }
            } else {
                laid(textArea(op, x, y, stroke.width, s.blur), s.color, s.blur) { glyphs(op, x, y, stroke) }
            }
        }
        // FILL_AND_STROKE: the outline and the letters one shape, its colour laid once
        stroke?.let { s -> laid(textArea(op, op.x, op.baseline, s.width, 0.0), s.color, 0.0) { glyphs(op, op.x, op.baseline, s) } }
        if (!op.color.transparent) {
            c.fillStyle = css(op.color)
            glyphs(op, op.x, op.baseline, null)
        }
        if (rotated) c.restore()
    }

    // the room [op] set at ([x], [y]) takes on the device, its outline [stroke] wide and blurred [blur] round it: what
    // a layer clears and lays for it, not the whole canvas (a theme outlining every key's words laid the canvas once
    // a key). Its run measured; a letter's ascent and descent, an italic's lean, generously: a layer cut short
    // would cut the letters, one too large costs only pixels
    private fun textArea(op: Op.Text, x: Double, y: Double, stroke: Double, blur: Double): IntArray {
        c.save()
        c.font = font(op.weight, op.size, assets.family(op.font))
        val m = c.measureText(op.text)
        c.restore()
        val run = (m.width as Number).toDouble()
        // marks stacked past the generous ascent or descent (Thai, Vietnamese, some emoji fonts): the ink's own
        // reach where the canvas tells it, so no layer cuts them
        val ascent = maxOf(op.size * ASCENT, (m.actualBoundingBoxAscent as Number?)?.toDouble() ?: 0.0)
        val descent = maxOf(op.size * DESCENT, (m.actualBoundingBoxDescent as Number?)?.toDouble() ?: 0.0)
        val width = (run + op.letterSpacing * op.size * op.text.length) * op.scaleX
        val pad = stroke + blur * BLUR_REACH
        val lean = op.size * LEAN
        return area(Box(x - pad - lean, y - ascent - pad, x + width + pad + lean, y + descent + pad))
    }

    // the glyphs at ([x], [y]) in the styles set, outlined by [stroke] where given (its colour the fill's: FILL_AND_STROKE)
    private fun glyphs(op: Op.Text, x: Double, y: Double, stroke: TextStroke?) {
        val spacing = op.letterSpacing * op.size
        // the text's styles within the save: the page's context is left as it was found
        c.save()
        c.font = font(op.weight, op.size, assets.family(op.font))
        if (spaces) c.letterSpacing = "${spacing}px"
        c.textAlign = "left"
        c.textBaseline = "alphabetic"
        // Paint spaces letters half before each, a canvas all after: moved by the half, squeezed as the
        // letters are (Minikin's spacing is the size times the scale; a canvas's is, under the scale below)
        c.translate(x + spacing * op.scaleX / 2, y)
        if (op.scaleX != 1.0) c.scale(op.scaleX, 1.0)
        if (stroke != null) {
            c.lineWidth = 2 * stroke.width
            c.lineJoin = "round"
        }
        if (spaces || spacing == 0.0) {
            set(op.text, 0.0, stroke != null)
        } else {
            // no letterSpacing: each letter where a canvas with it would set it, its advance and the spacing on
            var at = 0.0
            for (letter in letters(op.text)) {
                set(letter, at, stroke != null)
                at += (c.measureText(letter).width as Number).toDouble() + spacing
            }
        }
        c.restore()
    }

    private fun set(text: String, x: Double, stroked: Boolean) {
        if (stroked) c.strokeText(text, x, 0.0)
        c.fillText(text, x, 0.0)
    }

    private fun icon(op: Op.Icon) {
        val icon = assets.icon(op.name)
        // a transparent icon-color hides the icon, a theme's own coloured paths too
        if (icon == null || icon == undefined || op.tint.transparent) return
        // whole pixels, as the drawable's bounds
        val b = op.box
        val left = b.left.roundToInt()
        val top = b.top.roundToInt()
        val w = b.right.roundToInt() - left
        val h = b.bottom.roundToInt() - top
        c.save()
        c.translate(left.toDouble(), top.toDouble())
        if (op.rotation != 0.0) {
            c.translate(w / 2.0, h / 2.0)
            c.rotate(op.rotation * PI / HALF_TURN)
            c.translate(-w / 2.0, -h / 2.0)
        }
        val vw = (icon.vw as Number).toDouble()
        val vh = (icon.vh as Number).toDouble()
        if (icon.vx == null) {
            // the app's own icons (no viewBox origin given) stretched to the box as its VectorDrawable's bounds are:
            // a box a px wider than tall, its sides rounded apart, is filled the same on both
            c.scale(w / vw, h / vh)
        } else {
            // a theme's SVG scaled alike both ways and centred (it may be wide), its viewBox's origin at the box's
            // top left (Material Symbols' is 0 -960), as the app's PackIconDrawable fits it
            val fit = SvgSubset.Icon(vw, vh, emptyList(), (icon.vx as Number).toDouble(), (icon.vy as Number).toDouble())
                .fit(Box(0.0, 0.0, w.toDouble(), h.toDouble()))
            c.translate(fit.dx, fit.dy)
            c.scale(fit.scale, fit.scale)
        }
        val tint = css(op.tint)
        val paths: Array<dynamic> = icon.paths.unsafeCast<Array<dynamic>>()
        for (p in paths) {
            c.save()
            (p.transform as String?)?.let(::transform)
            (p.alpha as Number?)?.let { c.globalAlpha = it.toDouble() }
            val path = newPath2D(p.d as String)
            if (p.stroke != null && p.stroke != undefined) {
                c.strokeStyle = tint
                c.lineWidth = (p.stroke as Number).toDouble()
                // VectorDrawable's defaults for a path's stroke (the icons' JSON says none of these): a canvas's
                // are the same but its miter limit, 10, and an earlier op may have left others
                c.lineCap = "butt"
                c.lineJoin = "miter"
                c.miterLimit = MITER_LIMIT
                c.stroke(path)
            } else {
                // a theme SVG's own colour keeps its hue, faded by the icon-color's alpha as the tinted paths are,
                // rounded as the app's PackIconDrawable rounds it
                val own = p.fill as Number?
                c.fillStyle = own?.let { Argb(it.toInt()).let { f -> css(Argb.of((f.alpha * op.tint.alpha / OPAQUE_ALPHA.toDouble()).roundToInt(), f.red, f.green, f.blue)) } } ?: tint
                c.fill(path, (p.rule as String?) ?: "nonzero")
            }
            c.restore()
        }
        c.restore()
    }

    // an icon's group transform as SVG writes it (`translate(x y) rotate(r) scale(x y)`), applied in order
    private fun transform(t: String) {
        for (m in STEP.findAll(t)) {
            val v = m.groupValues[2].trim().split(SPACE).filter { it.isNotEmpty() }.map { it.toDouble() }
            when (m.groupValues[1]) {
                "translate" -> c.translate(v[0], v.getOrElse(1) { 0.0 })
                "rotate" -> c.rotate(v[0] * PI / HALF_TURN)
                "scale" -> c.scale(v[0], v.getOrElse(1) { v[0] })
            }
        }
    }

    private fun trace(shape: Shape) {
        c.beginPath()
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
                    c.rect(b.left, b.top, b.width, b.height)
                } else {
                    roundRect(b, r)
                }
            }
            is Shape.Path -> shape.ops.forEach {
                when (it) {
                    is Shape.Path.Op.MoveTo -> c.moveTo(it.x, it.y)
                    is Shape.Path.Op.LineTo -> c.lineTo(it.x, it.y)
                    Shape.Path.Op.Close -> c.closePath()
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
        c.moveTo(b.left + tl, b.top)
        c.lineTo(b.right - tr, b.top)
        if (tr > 0) c.arc(b.right - tr, b.top + tr, tr, -PI / 2, 0.0)
        c.lineTo(b.right, b.bottom - br)
        if (br > 0) c.arc(b.right - br, b.bottom - br, br, 0.0, PI / 2)
        c.lineTo(b.left + bl, b.bottom)
        if (bl > 0) c.arc(b.left + bl, b.bottom - bl, bl, PI / 2, PI)
        c.lineTo(b.left, b.top + tl)
        if (tl > 0) c.arc(b.left + tl, b.top + tl, tl, PI, PI * 3 / 2)
        c.closePath()
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

        // a text's room round its run, in its size: above its baseline, below, either side (textArea); a blur's
        // reach in its radii
        private const val ASCENT = 1.25
        private const val DESCENT = 0.5
        private const val LEAN = 0.25
        private const val BLUR_REACH = 3.0
        private const val HALF_TURN = 180.0
        private const val OPAQUE_ALPHA = 255

        // far enough that a shape put there shows nothing on any canvas a page makes
        const val FAR = 100_000.0
        private const val OPAQUE = "#000000"
        private val STEP = Regex("(translate|rotate|scale)\\(([^)]*)\\)")
        private val SPACE = Regex("[\\s,]+")

        // each page canvas's layer (its 2D context), weakly: a canvas let go takes its layer with it
        private val LAYERS: dynamic = js("new WeakMap()")
    }
}

// made once, not a function a call (an icon's every path, a text's every letter)
private val MAKE_PATH2D: dynamic = js("(function (d) { return new Path2D(d); })")
private val HAS_LETTER_SPACING: dynamic = js("(function (c) { return 'letterSpacing' in c; })")
private val CODE_POINTS: dynamic = js("(function (t) { return Array.from(t); })")

private fun newPath2D(d: String): dynamic = MAKE_PATH2D(d)

// a canvas [w] × [h]: an OffscreenCanvas where there is one, else one of [like]'s document
private fun newCanvas(like: dynamic, w: Int, h: Int): dynamic {
    val make: dynamic = js(
        "(function (like, w, h) { if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(w, h); " +
            "var c = like.ownerDocument.createElement('canvas'); c.width = w; c.height = h; return c; })",
    )
    return make(like, w, h)
}

private fun hasLetterSpacing(ctx: dynamic): Boolean = HAS_LETTER_SPACING(ctx) as Boolean

// [text]'s letters: its code points (a canvas sets no fewer)
private fun letters(text: String): Array<String> = CODE_POINTS(text).unsafeCast<Array<String>>()
