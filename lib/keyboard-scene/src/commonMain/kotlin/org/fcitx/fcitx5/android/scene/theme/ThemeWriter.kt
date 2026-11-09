/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A [Theme] as theme-4.xsd's text, canonical: one theme is always the same text, whoever wrote the
 * file it was read from and however, and [ThemeReader] reads it back as the same theme (its
 * [Theme.styles] at other lines). What an export and the theme maker save.
 *
 * - The elements in the reader's order (meta, colors, the dark colors, picture, font, style, icon),
 *   two spaces an indent, a line each.
 * - Colours as [Theme] holds them, resolved: `<colors>` the light version's (or the only one's), the
 *   24 of [ThemeReader.REQUIRED_COLORS] first and in that order, the theme's own after them by name;
 *   `<colors variant="dark">` only those the dark version has otherwise. A colour is written as
 *   the shortest the reader takes ([color]): `#rrggbb` where opaque, else `#aarrggbb`, a function's
 *   arguments after a bare `,`; so a colour read (at most [ColorExpr.MAX_LENGTH] characters) is never
 *   written longer, and reads back. A style's colours are kept as written, `@name`, `system()` and all.
 * - A style's selectors joined by `, `, each as [selector] writes it; its properties in
 *   [Values.PROPERTIES]' order and then [Values.HANDLE_SIZES]' and [Values.KEYBOARD_SIZES]', each as
 *   [value] writes it. A style that sets nothing is left out.
 * - A `path(…)` absolute (`M0 0 L1 0 Q1 1 0 1 Z`), a command to each segment; a bubble's size in %, a
 *   depth and offset in dp; a handle's grip, its width and height, in dp.
 * - Fonts and icons by name.
 *
 * Not a faithful exporter of a 4.0 file whose `<colors>` use `system()` or other colours (`@name`,
 * `darker(@key, 10%)`): a theme read keeps their values only, resolved with the system's colours of
 * the reading, so the file written has those values as literals and no longer follows the system's
 * colours nor a colour it named. What it is made for, LookTheme's themes (literal colours) and a file
 * of literal `<colors>`, it writes as they are.
 *
 * A theme picked as its other version ([Theme.variant]) is written as its file: read back, it is the
 * version first drawn.
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
        theme.picture?.let { p ->
            appendLine("""  <picture src="${escape(p.src)}" brightness="${number(p.brightness)}%" blur="${length(p.blurDp)}">""")
            // portrait first: the two orientations the reader takes, then any other it would refuse anyway
            p.cuts.entries.sortedBy { (o, _) -> CUTS.indexOf(o).takeIf { it >= 0 } ?: CUTS.size }.forEach { (o, file) ->
                appendLine("""    <cut orientation="${escape(o)}" file="${escape(file)}"/>""")
            }
            appendLine("  </picture>")
        }
        theme.fonts.entries.sortedBy { it.key }.forEach { (name, src) -> element("  ", "font", listOf("name" to name, "src" to src)) }
        // one that sets nothing (a later stage's properties alone, ignored as read) left out: a file of it would not read
        theme.styles.filter { it.values.isNotEmpty() }.forEach { s ->
            val props = WRITTEN.mapNotNull { p -> s.values[p]?.let { p to value(p, it) } }
            element("  ", "style", listOf("select" to s.selectors.joinToString(", ", transform = ::selector)) + props)
        }
        theme.icons.entries.sortedBy { it.key }.forEach { (name, src) -> element("  ", "icon", listOf("name" to name, "src" to src)) }
        appendLine("</theme>")
    }

    private val CUTS = listOf("portrait", "landscape")

    // a style's properties in the order written: PROPERTIES', then a handle's grip and the keyboard's size (one height)
    private val WRITTEN = (Values.PROPERTIES + Values.HANDLE_SIZES + Values.KEYBOARD_SIZES).distinct()

    private fun StringBuilder.colors(colors: Map<String, Argb>) {
        val required = ThemeReader.REQUIRED_COLORS.filter { it in colors }
        val own = colors.keys.filter { it !in ThemeReader.REQUIRED_COLORS }.sorted()
        (required + own).forEach { name -> appendLine("""    <color name="$name" value="${color(colors.getValue(name))}"/>""") }
    }

    // an element with no children, its attributes those given (a null one left out)
    private fun StringBuilder.element(indent: String, name: String, attributes: List<Pair<String, String?>>) {
        append(indent).append('<').append(name)
        attributes.forEach { (k, v) -> if (v != null) append(' ').append(k).append("=\"").append(escape(v)).append('"') }
        appendLine("/>")
    }

    /**
     * [s] as a `select=` writes it: its scenes (`@flat`; [Selector.SCENES]' order, then the scenes of what
     * is typed by name), the widget or the key (`qwerty/q`, `*`/role, `qwerty/` and a star for every key
     * of a board), its classes, roles and states each by name, its part. A key's name has every
     * character a selector reads otherwise escaped (`zhuyin/\,`, `number/\*`), as [Selector] reads it.
     */
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

    // what a selector reads otherwise, escaped wherever it is in a key's name: a list's comma, a space, a
    // qualifier's . : [, a star (alone, every key of the board), the escape itself
    private fun escapeName(name: String): String = buildString {
        name.forEach { c ->
            if (c in NAME_ESCAPED) append('\\')
            append(c)
        }
    }

    private const val NAME_ESCAPED = ",.:[* \\"

    /** [v], the value of [property], as the property's attribute writes it. */
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
        // one value where upright and turned are alike
        is Value.OfTwin -> listOf(v.twin.portrait, v.twin.landscape).distinct()
            .joinToString(" ") { if (property == "height") "${number(it)}%" else length(it) }
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
        "scale" -> "${number(n)}%"
        "size" -> "${number(n)}sp"
        "letter-spacing" -> "${number(n)}em"
        // T7a's grip on a handle (Values.HANDLE_SIZES): a keyboard's height and width are twins, not numbers
        "depth", "offset", "width", "height", "gap", "blur", "ink-width" -> length(n)
        else -> number(n)
    }

    // every segment absolute, a command each: read back, the same segments (as read, to six decimals: the
    // written text never longer than ShapePath.MAX_LENGTH)
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
        is Fill.Image -> "image(${f.file}, ${f.insets.joinToString(" ")}, ${f.pxPerDp}x" + (f.fallback?.let { ", ${color(it)})" } ?: ")")
        // its blur in dp always: picture()'s grammar has no bare 0
        is Fill.Picture -> when {
            f.blurDp == null && f.brightness == null && f.over == null -> "picture"
            // the <picture>'s blur and brightness (null) with another's colour over it: picture()'s text has no
            // such form (it names a blur and brightness, or nothing), and none read nor made is one
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

    // two values where top and bottom, left and right are alike (a key's gaps), else all four
    private fun inset(i: Inset): String =
        if (i.top == i.bottom && i.left == i.right) "${length(i.top)} ${length(i.right)}" else listOf(i.top, i.right, i.bottom, i.left).joinToString(" ") { length(it) }

    /**
     * [c] as a colour's text, the shortest the reader takes: `#rrggbb` where opaque, else `#aarrggbb`
     * (`#00000000`, not `transparent`), `@name`, a function of them with no space after its commas.
     */
    fun color(c: ColorExpr): String = when (c) {
        is ColorExpr.Literal -> color(c.argb)
        is ColorExpr.Ref -> "@${c.name}"
        is ColorExpr.Fn -> "${c.name}(${c.args.joinToString(",") { color(it) }},${number(c.percent)}%)"
        is ColorExpr.System -> "system(${c.name},${color(c.fallback)})"
    }

    // [Argb]'s `#aarrggbb`, its alpha left out where opaque
    private fun color(c: Argb): String = c.toString().let { if (c.alpha == OPAQUE) "#" + it.substring(ALPHA_DIGITS) else it }

    private const val OPAQUE = 0xff
    private const val ALPHA_DIGITS = 3

    // a length: dp, 0 without its unit (as CSS writes it, and the built-in themes)
    private fun length(dp: Double): String = if (dp == 0.0) "0" else "${number(dp)}dp"

    /**
     * [d] as a plain decimal, the same on the JVM and JS: `4` not `4.0`, `0.5`, never an exponent
     * (the JVM writes 0.0001 as `1.0E-4`, JS 1e-7 as `1e-7`; the reader takes neither).
     */
    internal fun number(d: Double): String {
        val text = d.toString()
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        val plain = if (e < 0) text else shifted(text.substring(0, e), text.substring(e + 1).toInt())
        return plain.removeSuffix(".0").let { if ('.' in it) it.trimEnd('0').removeSuffix(".") else it }
    }

    // a mantissa (`-1.25`) with its point moved [exponent] places
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

    // an attribute's value: XML's five, and the white space a reader of another kind would fold
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
                else -> append(c)
            }
        }
    }
}
