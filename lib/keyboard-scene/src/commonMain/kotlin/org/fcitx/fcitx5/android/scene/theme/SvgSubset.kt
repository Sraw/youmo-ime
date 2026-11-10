/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problem
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement
import kotlin.math.abs

/**
 * The SVG a theme 4.0 pack's `<icon>` may be (060-render-p3 T8a): filled paths and nothing else. An
 * icon from the web is read by the engine's XML reader (no DTD, no entities of its own: no XXE, no
 * billion laughs), and anything that reaches outside the file or runs (`<script>`, `<image>`, `<use>`,
 * `href`, `style`, `on…`) or that a later drawer would have to grow for (strokes, gradients, text,
 * filters, transforms) is refused, so whatever draws icons later draws only what this lets in.
 */
object SvgSubset {

    /** Paths in one icon at most. */
    const val MAX_PATHS = 256

    /** Numbers in all its paths at most: an icon's outline, not a traced photo. */
    const val MAX_NUMBERS = 20_000

    private val SHARED = setOf("fill", "fill-rule", "fill-opacity", "opacity", "id")
    private val ATTRIBUTES = mapOf(
        "svg" to SHARED + setOf("xmlns", "version", "width", "height", "viewBox"),
        "g" to SHARED,
        "path" to SHARED + "d",
    )

    private val NUMBER = Regex("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val LENGTH = Regex("${NUMBER.pattern}(?:px)?")
    private val COLOR = Regex("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
    private const val COMMANDS = "MmLlHhVvCcSsQqTtAaZz"

    /** What is wrong with [source] as an icon, each with its line; none: it is one. */
    fun check(source: String): List<Problem> {
        val root = when (val xml = Xml.parse(source)) {
            is Parsed.Failed -> return xml.problems
            is Parsed.Ok -> xml.value
        }
        if (root.name != "svg") return listOf(Problem(root.line, "the root is <${root.name}>, not <svg>"))
        val problems = ArrayList<Problem>()
        val count = Count()
        element(root, problems, count)
        if (count.paths > MAX_PATHS) problems += Problem(0, "over $MAX_PATHS paths")
        if (count.numbers > MAX_NUMBERS) problems += Problem(0, "over $MAX_NUMBERS numbers in the paths")
        return problems
    }

    /** An icon's outline as a drawer takes it: its viewBox's sides and its filled paths, in one tint. */
    data class Icon(val width: Double, val height: Double, val paths: List<Path>)

    /** A filled path: [d] as SVG has it, [evenOdd] its rule, [alpha] its opacity (fill-opacity times its groups'). */
    data class Path(val d: String, val evenOdd: Boolean, val alpha: Double)

    /**
     * [source] as an [Icon] where [check] finds nothing wrong with it: the sides its viewBox's (else width and
     * height, else 24), the paths not filled `none`, each with the rule and opacity its groups give it. The
     * theme maker's preview draws a theme's own icons with it (060-render-p3 §5.1 panel 4).
     */
    fun read(source: String): Parsed<Icon> {
        val problems = check(source)
        if (problems.isNotEmpty()) return Parsed.Failed(problems)
        val root = (Xml.parse(source) as Parsed.Ok).value
        val box = root["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.map(String::toDouble)
        val w = box?.get(2) ?: root["width"]?.removeSuffix("px")?.toDouble() ?: DEFAULT_SIDE
        val h = box?.get(3) ?: root["height"]?.removeSuffix("px")?.toDouble() ?: DEFAULT_SIDE
        val paths = ArrayList<Path>()
        fun walk(el: XmlElement, fill: String?, rule: String?, alpha: Double) {
            val f = el["fill"] ?: fill
            val r = el["fill-rule"] ?: rule
            val a = alpha * (el["opacity"]?.toDouble() ?: 1.0)
            if (el.name == "path" && f != "none") {
                paths += Path(el["d"]!!, r == "evenodd", a * (el["fill-opacity"]?.toDouble() ?: 1.0))
            }
            el.children.forEach { walk(it, f, r, a) }
        }
        walk(root, null, null, 1.0)
        return Parsed.Ok(Icon(w, h, paths))
    }

    private const val DEFAULT_SIDE = 24.0

    private class Count {
        var paths = 0
        var numbers = 0
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
            if (d == null) p += Problem(el.line, "<path> needs d=") else path(d, count)?.let { p += Problem(el.line, "d=: $it") }
        }
        el.children.forEach { child ->
            if (child.name == "svg") p += Problem(child.line, "<svg> is not allowed in an icon's <svg>") else element(child, p, count)
        }
    }

    // what is wrong with [value] of [name], null if nothing
    private fun attribute(name: String, value: String): String? = when (name) {
        "fill" -> "a #hex colour, none or currentColor".unless(value in FILL_WORDS || COLOR.matches(value))
        "fill-rule" -> "nonzero or evenodd".unless(value in FILL_RULES)
        "fill-opacity", "opacity" -> "a number 0–1".unless(finite(value) && value.toDouble() in 0.0..1.0)
        "width", "height" -> "a number".unless(LENGTH.matches(value) && finite(value.removeSuffix("px")))
        "viewBox" -> "four numbers, the last two over 0".unless(viewBox(value))
        "xmlns" -> "must be $NAMESPACE".unless(value == NAMESPACE)
        else -> null
    }

    private fun String.unless(ok: Boolean): String? = if (ok) null else this

    /** A number's size at most, and a viewBox side's least: an icon's, what a drawing in floats scales by finitely. */
    const val MAX_NUMBER = 1e6
    const val MIN_SIDE = 1e-3

    // a number drawn as a float: 1e999 is written as one but read as Infinity, 1e300 a float's Infinity,
    // which no drawing scales by
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

    // [d] as SVG's path data: commands and numbers, a move first; what is wrong with it, null if nothing
    private fun path(d: String, count: Count): String? {
        var i = 0
        var first = true
        while (i < d.length) {
            val c = d[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c in COMMANDS -> {
                    if (first && c != 'M' && c != 'm') return "a path starts with M"
                    first = false
                    i++
                }
                else -> {
                    if (first) return "a path starts with M"
                    val number = NUMBER.matchAt(d, i) ?: return "'${d[i]}' at ${i + 1} is no command or number"
                    if (!inRange(number.value.toDouble())) return "${number.value} at ${i + 1} is past a number's range"
                    count.numbers++
                    i = number.range.last + 1
                }
            }
        }
        return if (first) "empty" else null
    }

    private const val NAMESPACE = "http://www.w3.org/2000/svg"
    private const val VIEW_BOX = 4
    private const val SHOWN = 32
}
