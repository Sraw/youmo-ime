/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.xml.Attributes
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/**
 * A theme file read and checked: theme-4.xsd's words, and what an import checks beyond them (the 24
 * colours of 3.0 all there, every `@name` naming a colour and none itself, at most 1000 styles, 4
 * fonts and 200 icons, a selector's grammar exactly).
 */
object ThemeReader {
    const val NAMESPACE = "https://youmo-ime/theme/4"
    private const val MAX_STYLES = 1000
    private const val MAX_FONTS = 4
    private const val MAX_ICONS = 200
    private const val MAX_COLORS = 200
    private const val PERCENT = 100.0
    private val VERSION = Regex("4\\.[0-9]+")
    private val VAR_NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
    private val FILE = Regex("[^/\\\\:*?\"<>|\\s][^/\\\\:*?\"<>|]{0,127}")
    private val ICON = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /** The colours the app's own widgets take by name (theme 3.0's, less their Color suffix). */
    val REQUIRED_COLORS = listOf(
        "background", "bar", "keyboard", "key", "keyText", "candidateText", "candidateLabel", "candidateComment", "altKey",
        "altKeyText", "accentKey", "accentKeyText", "keyPressHighlight", "keyShadow", "popupBackground", "popupText",
        "spaceBar", "divider", "clipboardEntry", "genericActiveBackground", "genericActiveForeground", "keyBorder", "keyHint",
        "toolbarIcon",
    )

    fun theme(source: String): Parsed<Theme> = when (val xml = Xml.parse(source)) {
        is Parsed.Failed -> xml
        is Parsed.Ok -> theme(xml.value)
    }

    /**
     * [root] read. A theme comes from anywhere: whatever goes wrong reading it (a bug of ours it
     * finds, a stack it overflows) is a problem of the file, never a crash of the keyboard.
     */
    @Suppress("TooGenericExceptionCaught")
    fun theme(root: XmlElement): Parsed<Theme> = try {
        read(root)
    } catch (e: Throwable) {
        Parsed.Failed(root.line, "the theme could not be read: ${e::class.simpleName}")
    }

    private fun read(root: XmlElement): Parsed<Theme> {
        if (root.name != "theme") return Parsed.Failed(root.line, "the root is <${root.name}>, not <theme>")
        val p = Problems()
        val a = Attributes(root, p)
        if (a.string("xmlns") != NAMESPACE) a.problem("xmlns= must be $NAMESPACE")
        val name = a.required("name")?.also { if (it.isBlank() || it.length > MAX_NAME) a.problem("name= must be 1–$MAX_NAME characters") }
        val dark = a.boolean("dark") ?: a.required("dark").let { false }
        val version = a.matching("version", VERSION, "4.x") ?: a.required("version")
        a.done()
        val order = listOf("colors", "picture", "font", "style", "icon")
        root.children.zipWithNext().firstOrNull { (x, y) -> order.indexOf(x.name) > order.indexOf(y.name) }
            ?.let { (_, y) -> p.add(y.line, "<${y.name}> is out of order (colors, picture, font, style, icon)") }
        root.children.filter { it.name !in order }.forEach { p.add(it.line, "<${it.name}> is not allowed in <theme>") }
        val colorsEl = root.all("colors")
        if (colorsEl.size != 1) p.add(root.line, "<theme> needs one <colors>")
        val colors = colorsEl.firstOrNull()?.let { colors(it, p) }.orEmpty()
        val pictures = root.all("picture")
        if (pictures.size > 1) p.add(pictures[1].line, "<picture> given twice")
        val picture = pictures.firstOrNull()?.let { picture(it, p) }
        val fonts = root.all("font").also { if (it.size > MAX_FONTS) p.add(it[MAX_FONTS].line, "over $MAX_FONTS fonts") }
            .mapNotNull { named(it, p, "src") }.toMap()
        val iconEls = root.all("icon")
        if (iconEls.size > MAX_ICONS) p.add(iconEls[MAX_ICONS].line, "over $MAX_ICONS icons")
        val icons = iconEls.take(MAX_ICONS).mapNotNull { named(it, p, "src") }.toMap()
        val styleEls = root.all("style")
        if (styleEls.size > MAX_STYLES) p.add(styleEls[MAX_STYLES].line, "over $MAX_STYLES styles")
        // the rest not read: the file is refused already, and a million styles would each be a problem
        val styles = styleEls.take(MAX_STYLES).mapIndexedNotNull { i, el -> style(el, i, p, colors.keys, fonts.keys) }
        return p.result { Theme(name.orEmpty(), dark, version.orEmpty(), colors, styles, fonts, icons, picture) }
    }

    private const val MAX_NAME = 64

    // the colour variables, each resolved: none missing, none circular
    private fun colors(el: XmlElement, p: Problems): Map<String, Argb> {
        Attributes(el, p).done()
        val raw = LinkedHashMap<String, Pair<ColorExpr, Int>>()
        if (el.children.size > MAX_COLORS) p.add(el.line, "over $MAX_COLORS colours")
        for (c in el.children) {
            val (name, value) = color(c, p) ?: continue
            if (name in raw) p.add(c.line, "the colour $name is given twice")
            raw[name] = value to c.line
        }
        REQUIRED_COLORS.filter { it !in raw }.takeIf { it.isNotEmpty() }?.let { p.add(el.line, "colours missing: ${it.joinToString(" ")}") }
        return resolved(raw, p)
    }

    // a <color>'s name and value, null where it is not one
    private fun color(c: XmlElement, p: Problems): Pair<String, ColorExpr>? {
        if (c.name != "color") return null.also { p.add(c.line, "<${c.name}> is not allowed in <colors>") }
        val a = Attributes(c, p)
        val name = a.matching("name", VAR_NAME, "a colour name") ?: a.required("name")
        val value = a.value("value", "a colour", ColorExpr::parse) ?: a.required("value").let { null }
        a.done()
        return if (name == null || value == null) null else name to value
    }

    // every colour variable resolved, those that name what is not there or themselves left out (and a problem)
    private fun resolved(raw: Map<String, Pair<ColorExpr, Int>>, p: Problems): Map<String, Argb> {
        val done = HashMap<String, Argb>()
        fun resolve(name: String, seen: Set<String>): Argb? {
            done[name]?.let { return it }
            val (expr, line) = raw[name] ?: return null
            if (name in seen) return null.also { p.add(line, "the colour $name is defined by itself") }
            val v = expr.resolve { resolve(it, seen + name) }
            if (v == null) expr.refs().filter { it !in raw }.forEach { p.add(line, "@$it is no colour") }
            return v?.also { done[name] = it }
        }
        raw.keys.forEach { resolve(it, emptySet()) }
        return raw.keys.mapNotNull { k -> done[k]?.let { k to it } }.toMap()
    }

    private fun picture(el: XmlElement, p: Problems): Picture? {
        val a = Attributes(el, p)
        val src = a.matching("src", FILE, "a file name") ?: a.required("src")
        val brightness = a.value("brightness", "0%–100%") { Values.percent(it)?.takeIf { v -> v <= PERCENT } } ?: PERCENT
        val blur = a.value("blur", "0dp–25dp") { Values.dp(it, MAX_BLUR) } ?: 0.0
        a.done()
        val cuts = el.children.mapNotNull { cut ->
            if (cut.name != "cut") return@mapNotNull null.also { p.add(cut.line, "<${cut.name}> is not allowed in <picture>") }
            val ca = Attributes(cut, p)
            val orientation = ca.oneOf("orientation", listOf("portrait", "landscape")) ?: ca.required("orientation")
            val file = ca.matching("file", FILE, "a file name") ?: ca.required("file")
            ca.string("rect")
            ca.oneOf("rotation", listOf("0", "90", "180", "270"))
            ca.done()
            if (orientation != null && file != null) orientation to file else null
        }.toMap()
        if (cuts.isEmpty()) p.add(el.line, "<picture> needs a <cut>")
        return src?.let { Picture(it, brightness, blur, cuts) }
    }

    private const val MAX_BLUR = 25.0

    private fun named(el: XmlElement, p: Problems, file: String): Pair<String, String>? {
        val a = Attributes(el, p)
        val name = if (el.name == "icon") a.matching("name", ICON, "an icon name") else a.matching("name", VAR_NAME, "a font name")
        val src = a.matching(file, FILE, "a file name")
        if (name == null) a.required("name")
        if (src == null) a.required(file)
        a.done()
        return if (name != null && src != null) name to src else null
    }

    private fun style(el: XmlElement, order: Int, p: Problems, colors: Set<String>, fonts: Set<String>): Style? {
        val a = Attributes(el, p)
        val select = a.required("select")
        val selectors = select?.let { Selector.parseList(it) ?: null.also { a.problem("select=\"$select\" is no selector") } }
        // the properties it sets, in PROPERTIES' order: a theme's styles are many, each sets a few
        val values = LinkedHashMap<String, Value>()
        for ((prop, what) in PROPERTY_VALUES) {
            if (el[prop] == null) continue
            a.value(prop, what) { Values.read(prop, it) }?.let { values[prop] = it }
        }
        a.done()
        for (v in values.values) {
            colorsOf(v).flatMap { it.refs() }.filter { it !in colors }.forEach { a.problem("@$it is no colour") }
        }
        (values["font"] as? Value.Word)?.word?.takeIf { it != "system" && it !in fonts }?.let { a.problem("font=\"$it\" is no <font>") }
        if (values.isEmpty()) a.problem("a style that sets nothing")
        return selectors?.let { Style(it, values, order, el.line) }
    }

    // each property and how a problem names its value
    private val PROPERTY_VALUES = Values.PROPERTIES.map { it to "a value of $it" }

    private fun colorsOf(v: Value): List<ColorExpr> = when (v) {
        is Value.OfColor -> listOf(v.color)
        is Value.OfShadow -> listOfNotNull(v.shadow.color)
        is Value.OfBorder -> listOfNotNull(v.border.color)
        is Value.OfFill -> when (val f = v.fill) {
            is Fill.Solid -> listOf(f.color)
            is Fill.Linear -> f.stops.map { it.first }
            is Fill.Picture -> listOfNotNull(f.over)
            is Fill.Image -> listOfNotNull(f.fallback)
            else -> emptyList()
        }
        else -> emptyList()
    }
}
