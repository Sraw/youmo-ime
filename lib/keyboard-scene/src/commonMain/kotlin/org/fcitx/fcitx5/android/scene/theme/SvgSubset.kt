/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problem
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement
import kotlin.math.abs

/**
 * The SVG a theme's `<icon>` may be: filled paths and nothing else. Read by the engine's XML reader (no DTD: no XXE);
 * anything that reaches outside the file, runs, or that a drawer would have to grow for is refused.
 */
object SvgSubset {

    const val MAX_PATHS = 256

    /** An icon's outline, not a traced photo. */
    const val MAX_NUMBERS = 20_000

    private val SHARED = setOf("fill", "fill-rule", "fill-opacity", "opacity", "id")
    private val ATTRIBUTES = mapOf(
        "svg" to SHARED + setOf("xmlns", "version", "width", "height", "viewBox"),
        "g" to SHARED,
        "path" to SHARED + "d",
    )

    private val NUMBER = Regex("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val LENGTH = Regex("${NUMBER.pattern}(?:px)?")
    // longest first: JS's matches() takes the first alternative that matches a prefix and never tries the rest (#f008 was refused)
    private val COLOR = Regex("#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})")

    fun check(source: String): List<Problem> = when (val read = read(source)) {
        is Parsed.Failed -> read.problems
        is Parsed.Ok -> emptyList()
    }

    /** [x], [y] the viewBox's origin (Material Symbols' `0 -960 960 960`). */
    data class Icon(val width: Double, val height: Double, val paths: List<Path>, val x: Double = 0.0, val y: Double = 0.0) {
        /** SVG's `xMidYMid meet`, so a wide icon is not stretched; both drawers use it. */
        fun fit(box: Box): Fit {
            val scale = minOf(box.width / width, box.height / height)
            return Fit(scale, box.left + (box.width - width * scale) / 2 - x * scale, box.top + (box.height - height * scale) / 2 - y * scale)
        }
    }

    data class Fit(val scale: Double, val dx: Double, val dy: Double)

    /** [color] null where it takes the key's icon-color (no fill, or currentColor: Material icons write none). */
    data class Path(val segments: List<ShapePath.Segment>, val evenOdd: Boolean, val alpha: Double, val color: Argb? = null)

    /** Refused where nothing would be drawn: a required key replaced by it would be blank. */
    fun read(source: String): Parsed<Icon> {
        val root = when (val xml = Xml.parse(source)) {
            is Parsed.Failed -> return xml
            is Parsed.Ok -> xml.value
        }
        if (root.name != "svg") return Parsed.Failed(listOf(Problem(root.line, "the root is <${root.name}>, not <svg>")))
        val problems = ArrayList<Problem>()
        val count = Count()
        element(root, problems, count)
        if (count.paths > MAX_PATHS) problems += Problem(0, "over $MAX_PATHS paths")
        if (count.numbers > MAX_NUMBERS) problems += Problem(0, "over $MAX_NUMBERS numbers in the paths")
        if (problems.isNotEmpty()) return Parsed.Failed(problems)
        val box = root["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.map(String::toDouble)
        val w = box?.get(2) ?: root["width"]?.removeSuffix("px")?.toDouble() ?: DEFAULT_SIDE
        val h = box?.get(3) ?: root["height"]?.removeSuffix("px")?.toDouble() ?: DEFAULT_SIDE
        val paths = drawn(root, count)
        if (paths.isEmpty()) return Parsed.Failed(listOf(Problem(root.line, NOTHING_DRAWN)))
        return Parsed.Ok(Icon(w, h, paths, box?.get(0) ?: 0.0, box?.get(1) ?: 0.0))
    }

    // fill, fill-rule and fill-opacity inherited as SVG's are (the nearest one's), opacity multiplied
    private fun drawn(root: XmlElement, count: Count): List<Path> {
        val paths = ArrayList<Path>()
        fun walk(el: XmlElement, fill: String?, rule: String?, fillOpacity: Double, alpha: Double) {
            val f = el["fill"] ?: fill
            val r = el["fill-rule"] ?: rule
            val fo = el["fill-opacity"]?.toDouble() ?: fillOpacity
            val a = alpha * (el["opacity"]?.toDouble() ?: 1.0)
            if (el.name == "path" && f != "none") {
                val color = f?.takeIf { it.startsWith("#") }?.let(::cssColor)
                val segments = count.segments.getValue(el)
                if (a * fo * (color?.alpha ?: OPAQUE) > 0 && IconPath.draws(segments)) paths += Path(segments, r == "evenodd", a * fo, color)
            }
            el.children.forEach { walk(it, f, r, fo, a) }
        }
        walk(root, null, null, 1.0, 1.0)
        return paths
    }

    /** Zero-area paths (`M0 0L1 0Z`) still count: their area is not worked out. */
    const val NOTHING_DRAWN = "nothing to draw: every path is filled none, of no opacity, or moves only"

    /** Alpha LAST, the other way round from the project's own `#aarrggbb` ([ColorExpr]), so it is read here and never by that. */
    fun cssColor(hex: String): Argb {
        val h = hex.removePrefix("#")
        val full = if (h.length <= SHORT_RGBA) h.map { "$it$it" }.joinToString("") else h
        fun ch(k: Int) = full.substring(2 * k, 2 * k + 2).toInt(HEX)
        return Argb.of(if (full.length == LONG_RGBA) ch(ALPHA_LAST) else OPAQUE, ch(0), ch(1), ch(2))
    }

    private const val SHORT_RGBA = 4
    private const val LONG_RGBA = 8
    private const val ALPHA_LAST = 3
    private const val HEX = 16
    private const val OPAQUE = 255

    private const val DEFAULT_SIDE = 24.0

    private class Count {
        var paths = 0
        var numbers = 0
        var tooMany = false
        val segments = HashMap<XmlElement, List<ShapePath.Segment>>()
    }

    private fun element(el: XmlElement, p: MutableList<Problem>, count: Count) {
        val allowed = ATTRIBUTES[el.name]
        if (allowed == null) {
            p += Problem(el.line, "<${el.name}> is not allowed in an icon (only <svg>, <g> and <path>)")
            return
        }
        if (el.text.isNotBlank()) p += Problem(el.line, "text is not allowed in <${el.name}>")
        for ((name, value) in el.attributes) {
            if (name !in allowed) {
                p += Problem(el.line, "$name= is not allowed on <${el.name}>")
                continue
            }
            attribute(name, value)?.let { p += Problem(el.line, "$name=\"${value.take(SHOWN)}\": $it") }
        }
        if (el.name == "path") {
            count.paths++
            val d = el["d"]
            if (d == null) {
                p += Problem(el.line, "<path> needs d=")
            } else if (!count.tooMany) {
                when (val read = IconPath.parse(d, MAX_NUMBERS - count.numbers)) {
                    is IconPath.Read.Ok -> {
                        count.numbers += read.numbers
                        count.segments[el] = read.segments
                    }
                    is IconPath.Read.Bad -> p += Problem(el.line, "d=: ${read.message}")
                    IconPath.Read.TooMany -> {
                        count.tooMany = true
                        count.numbers = MAX_NUMBERS + 1
                    }
                }
            }
        }
        el.children.forEach { child ->
            if (child.name == "svg") p += Problem(child.line, "<svg> is not allowed in an icon's <svg>") else element(child, p, count)
        }
    }

    private fun attribute(name: String, value: String): String? = when (name) {
        "fill" -> "a #hex colour, none or currentColor".unless(value in FILL_WORDS || COLOR.matches(value))
        "fill-rule" -> "nonzero or evenodd".unless(value in FILL_RULES)
        "fill-opacity", "opacity" -> "a number 0–1".unless(finite(value) && value.toDouble() in 0.0..1.0)
        "width", "height" -> "a number $MIN_SIDE or over".unless(LENGTH.matches(value) && finite(value.removeSuffix("px")) && value.removeSuffix("px").toDouble() >= MIN_SIDE)
        "viewBox" -> "four numbers, the last two over 0".unless(viewBox(value))
        "xmlns" -> "must be $NAMESPACE".unless(value == NAMESPACE)
        else -> null
    }

    private fun String.unless(ok: Boolean): String? = if (ok) null else this

    /** What a drawing in floats scales by finitely. */
    const val MAX_NUMBER = 1e6
    const val MIN_SIDE = 1e-3

    // 1e999 reads as Infinity, 1e300 is a float's Infinity: no drawing scales by either
    private fun inRange(value: Double): Boolean = abs(value) <= MAX_NUMBER

    private fun finite(value: String): Boolean = NUMBER.matches(value) && inRange(value.toDouble())

    // a side of 1e-300 would scale the icon by Infinity
    private fun viewBox(value: String): Boolean {
        val v = value.trim().split(Regex("[\\s,]+"))
        if (v.size != VIEW_BOX || !v.all(::finite)) return false
        return v[2].toDouble() >= MIN_SIDE && v[3].toDouble() >= MIN_SIDE
    }

    private val FILL_WORDS = setOf("none", "currentColor")
    private val FILL_RULES = setOf("nonzero", "evenodd")

    private const val NAMESPACE = "http://www.w3.org/2000/svg"
    private const val VIEW_BOX = 4
    private const val SHOWN = 32
}
