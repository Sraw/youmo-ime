/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.pet.PetWriter
import org.fcitx.fcitx5.android.scene.xml.Xml

/**
 * A [Theme] as theme-4.xsd's text, canonical: one theme is always the same text, and [ThemeReader] reads it back as the
 * same theme. Not faithful for `<colors>` using `system()` or other colours: a theme read keeps only their resolved
 * values, so the file written has literals.
 */
object ThemeWriter {

    fun write(theme: Theme): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        val variants = when (theme.variants) {
            setOf(true) -> "dark"
            setOf(false) -> "light"
            else -> "light dark"
        }
        appendLine("""<theme xmlns="${ThemeReader.NAMESPACE}" name="${escape(theme.name)}" variants="$variants" version="${escape(theme.version)}">""")
        theme.meta?.let { m ->
            element(
                "  ", "meta",
                listOf("author" to m.author, "version" to m.version, "license" to m.license, "min-app" to m.minApp, "preview" to m.preview),
            )
        }
        appendLine("  <colors>")
        colors(theme.baseColors)
        appendLine("  </colors>")
        theme.darkColors?.filter { (name, c) -> theme.baseColors[name] != c }?.takeIf { it.isNotEmpty() }?.let {
            appendLine("""  <colors variant="dark">""")
            colors(it)
            appendLine("  </colors>")
        }
        listOfNotNull(theme.picture?.let { it to "" }, theme.darkPicture?.let { it to " variant=\"dark\"" }).forEach { (p, variant) ->
            appendLine("""  <picture$variant src="${escape(p.src)}" brightness="${number(p.brightness)}%" blur="${length(p.blurDp)}"${if (p.animated) " animated=\"true\"" else ""}>""")
            p.cuts.entries.sortedBy { (o, _) -> CUTS.indexOf(o).takeIf { it >= 0 } ?: CUTS.size }.forEach { (o, file) ->
                appendLine("""    <cut orientation="${escape(o)}" file="${escape(file)}"/>""")
            }
            appendLine("  </picture>")
        }
        theme.fonts.entries.sortedBy { it.key }.forEach { (name, src) -> element("  ", "font", listOf("name" to name, "src" to src)) }
        // one that sets nothing (only ignored later-version properties) is left out: a file of it would not read
        theme.styles.filter { it.values.isNotEmpty() }.forEach { s ->
            val props = WRITTEN.mapNotNull { p -> s.values[p]?.let { p to value(p, it) } }
            element("  ", "style", listOf("select" to s.selectors.joinToString(", ", transform = ::selector)) + props)
        }
        theme.icons.entries.sortedBy { it.key }.forEach { (name, src) -> element("  ", "icon", listOf("name" to name, "src" to src) + listOfNotNull(("tint" to "true").takeIf { name in theme.tintedIcons })) }
        if (theme.soundFiles.isNotEmpty()) {
            appendLine("  <sounds>")
            theme.soundFiles.forEach { (key, src) -> element("    ", "sound", listOf("key" to key, "src" to src)) }
            appendLine("  </sounds>")
        }
        theme.pet?.let { append(PetWriter.write(it)) }
        appendLine("</theme>")
    }

    private val CUTS = listOf("portrait", "landscape")

    private val WRITTEN = (Values.PROPERTIES + Values.HANDLE_SIZES + Values.KEYBOARD_SIZES).distinct()

    private fun StringBuilder.colors(colors: Map<String, Argb>) {
        val required = ThemeReader.REQUIRED_COLORS.filter { it in colors }
        val own = colors.keys.filter { it !in ThemeReader.REQUIRED_COLORS }.sorted()
        (required + own).forEach { name -> appendLine("""    <color name="$name" value="${color(colors.getValue(name))}"/>""") }
    }

    private fun StringBuilder.element(indent: String, name: String, attributes: List<Pair<String, String?>>) {
        append(indent).append('<').append(name)
        attributes.forEach { (k, v) -> if (v != null) append(' ').append(k).append("=\"").append(escape(v)).append('"') }
        appendLine("/>")
    }

    fun selector(s: Selector): String = buildString {
        val known = Selector.SCENES.toList()
        s.scenes.sortedWith(compareBy({ known.indexOf(it).takeIf { i -> i >= 0 } ?: known.size }, { it })).forEach { append('@').append(it).append(' ') }
        append(
            when {
                s.starRole != null -> "*/${s.starRole}"
                s.board != null && s.key != null -> "${s.board}/${escapeName(s.key)}"
                s.board != null -> "${s.board}/*"
                else -> s.widget
            },
        )
        s.classes.sorted().forEach { append('.').append(it) }
        s.roles.sorted().forEach { append("[role=").append(it).append(']') }
        s.states.sorted().forEach { append(':').append(it) }
        s.part?.let { append(' ').append(it) }
    }

    // a lone star is every key of the board, so a star in a name is escaped too
    private fun escapeName(name: String): String = buildString {
        name.forEach { c ->
            if (c in NAME_ESCAPED) append('\\')
            append(c)
        }
    }

    private const val NAME_ESCAPED = ",.:[* \\"

    fun value(property: String, v: Value): String = when (v) {
        is Value.OfFill -> fill(v.fill)
        is Value.OfColor -> color(v.color)
        is Value.OfRadius -> radius(v.radius)
        is Value.OfInset -> inset(v.inset)
        is Value.OfShadow -> shadow(v.shadow)
        is Value.OfBorder -> border(property, v.border)
        is Value.Word -> v.word
        is Value.Flag -> v.on.toString()
        is Value.Number -> number(property, v.number)
        is Value.Percent -> "${number(v.percent)}%"
        is Value.OfPath -> "path(${path(v.path)})"
        is Value.OfShown -> shown(v.shown)
        is Value.OfTransition -> "${v.transition.ms}ms ${v.transition.easing}"
        is Value.OfAppear -> "${v.appear.kind} ${v.appear.ms}ms"
        is Value.OfEffect -> effect(v.effect)
        is Value.OfTwin -> listOf(v.twin.portrait, v.twin.landscape).distinct()
            .joinToString(" ") { if (property == "height") "${number(it)}%" else length(it) }
    }

    private fun effect(e: Effect?): String = when (e) {
        null -> "none"
        is Effect.Ripple -> "ripple-image(${e.file}, ${number(e.radius)}%, ${e.ms}ms)"
        is Effect.Particles -> "particles(${e.file}, ${e.count}, ${length(e.spread)}, ${e.ms}ms)"
    }

    private fun shadow(s: Shadow): String =
        s.color?.takeIf { s != Shadow.NONE }?.let { "${length(s.dx)} ${length(s.dy)} ${length(s.blur)} ${color(it)}" } ?: "none"

    // a stroke has no `none`: one of no colour is none drawn, a transparent one
    private fun border(property: String, b: Border): String = if (property == "stroke") {
        "${length(b.width)} ${color(b.color ?: ColorExpr.Literal(Argb.TRANSPARENT))}"
    } else {
        b.color?.takeIf { b != Border.NONE }?.let { "${length(b.width)} ${color(it)}" } ?: "none"
    }

    private fun number(property: String, n: Double): String = when (property) {
        "scale", "press-scale" -> "${number(n)}%"
        "size" -> "${number(n)}sp"
        "letter-spacing" -> "${number(n)}em"
        // a keyboard's height and width are twins, not numbers
        "depth", "offset", "width", "height", "gap", "blur", "ink-width", "press-sink" -> length(n)
        else -> number(n)
    }

    // to six decimals: the text never longer than ShapePath.MAX_LENGTH
    private fun path(p: ShapePath): String = p.segments.joinToString(" ") { s ->
        when (s) {
            is ShapePath.Segment.Move -> "M${xy(s.x, s.y)}"
            is ShapePath.Segment.Line -> "L${xy(s.x, s.y)}"
            is ShapePath.Segment.Quad -> "Q${xy(s.cx, s.cy)} ${xy(s.x, s.y)}"
            is ShapePath.Segment.Cubic -> "C${xy(s.c1x, s.c1y)} ${xy(s.c2x, s.c2y)} ${xy(s.x, s.y)}"
            ShapePath.Segment.Close -> "Z"
        }
    }

    private fun xy(x: Double, y: Double): String = "${number(x)} ${number(y)}"

    private fun shown(s: Shown): String = when (s) {
        Shown.ImeName -> "ime-name"
        Shown.None -> "none"
        is Shown.Text -> "text(${s.text})"
        is Shown.Icon -> "icon(${s.name})"
    }

    private fun fill(f: Fill): String = when (f) {
        is Fill.Solid -> color(f.color)
        is Fill.Linear -> "linear(${number(f.angle)}deg, ${f.stops.joinToString(", ") { (c, at) -> "${color(c)} ${number(at)}%" }})"
        is Fill.Image -> {
            // image()'s grammar reads 1x to 8x: another would not read back
            require(f.pxPerDp in 1..MAX_PX_PER_DP) { "image(): ${f.pxPerDp}x is not 1x–8x" }
            "image(${f.file}, ${f.insets.joinToString(" ")}, ${f.pxPerDp}x" + (f.fallback?.let { ", ${color(it)})" } ?: ")")
        }
        // picture()'s grammar has no bare 0
        is Fill.Picture -> when {
            f.blurDp == null && f.brightness == null && f.over == null -> "picture"
            // picture()'s text has no such form, and nothing read or made is one
            f.blurDp == null || f.brightness == null ->
                throw IllegalArgumentException("picture(${f.blurDp}, ${f.brightness}, ${f.over}): picture() names both its blur and brightness, or neither and no colour")
            else -> "picture(${number(f.blurDp)}dp, ${number(f.brightness)}%" + (f.over?.let { ", ${color(it)})" } ?: ")")
        }
    }

    private fun radius(r: Radius): String = when {
        r.half -> "50%"
        r.corners.distinct().size == 1 -> length(r.corners[0])
        else -> r.corners.joinToString(" ") { length(it) }
    }

    private fun inset(i: Inset): String =
        if (i.top == i.bottom && i.left == i.right) "${length(i.top)} ${length(i.right)}" else listOf(i.top, i.right, i.bottom, i.left).joinToString(" ") { length(it) }

    fun color(c: ColorExpr): String = when (c) {
        is ColorExpr.Literal -> color(c.argb)
        is ColorExpr.Ref -> "@${c.name}"
        is ColorExpr.Fn -> "${c.name}(${c.args.joinToString(",") { color(it) }},${number(c.percent)}%)"
        is ColorExpr.System -> "system(${c.name},${color(c.fallback)})"
    }

    private fun color(c: Argb): String = c.toString().let { if (c.alpha == OPAQUE) "#" + it.substring(ALPHA_DIGITS) else it }

    private const val MAX_PX_PER_DP = 8
    private const val OPAQUE = 0xff
    private const val ALPHA_DIGITS = 3

    private fun length(dp: Double): String = if (dp == 0.0) "0" else "${number(dp)}dp"

    /** Never an exponent (the JVM writes 0.0001 as `1.0E-4`, JS 1e-7 as `1e-7`; the reader takes neither). */
    internal fun number(d: Double): String {
        val text = d.toString()
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        val plain = if (e < 0) text else shifted(text.substring(0, e), text.substring(e + 1).toInt())
        return plain.removeSuffix(".0").let { if ('.' in it) it.trimEnd('0').removeSuffix(".") else it }
    }

    private fun shifted(mantissa: String, exponent: Int): String {
        val negative = mantissa.startsWith('-')
        val m = mantissa.removePrefix("-")
        val point = m.indexOf('.').let { if (it < 0) m.length else it }
        val digits = m.replace(".", "")
        val at = point + exponent
        val body = when {
            at <= 0 -> "0." + "0".repeat(-at) + digits
            at >= digits.length -> digits + "0".repeat(at - digits.length)
            else -> digits.substring(0, at) + "." + digits.substring(at)
        }
        return (if (negative) "-" else "") + body
    }

    // XML's five, and the white space a reader of another kind would fold
    private fun escape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\t' -> append("&#9;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                // as the reader drops them
                else -> if (Xml.allows(c)) append(c)
            }
        }
    }
}
