/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Icons
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
    /** A theme file's styles at most; one the app writes itself may say more (`maxStyles`). */
    const val MAX_STYLES = 1000
    private const val MAX_FONTS = 4
    private const val MAX_ICONS = 200
    private const val MAX_COLORS = 200
    private const val PERCENT = 100.0
    private val VERSION = Regex("4\\.[0-9]+")
    private val VAR_NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
    // ASCII white space spelt out: `\s` is more on JS than on the JVM, and a file must read the same on both
    private val FILE = Regex("[^/\\\\:*?\"<>| \\t\\n\\r\\f\\x0B\\u00A0][^/\\\\:*?\"<>|]{0,127}")
    private val ICON = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    /** The colours the app's own widgets take by name (theme 3.0's, less their Color suffix). */
    val REQUIRED_COLORS = listOf(
        "background", "bar", "keyboard", "key", "keyText", "candidateText", "candidateLabel", "candidateComment", "altKey",
        "altKeyText", "accentKey", "accentKeyText", "keyPressHighlight", "keyShadow", "popupBackground", "popupText",
        "spaceBar", "divider", "clipboardEntry", "genericActiveBackground", "genericActiveForeground", "keyBorder", "keyHint",
        "toolbarIcon",
    )

    /**
     * Elements of a later stage of 0.6.0 (060-render-p3; §16's second tier: a sound pack), read and
     * ignored with a warning, whatever they hold; a stage that makes one work moves it out.
     */
    val RESERVED_ELEMENTS = setOf("sounds", "sound")

    /**
     * [source] read, `system()` colours from [systemColors] (none: each its fallback; the light
     * `<colors>` take its light ones, `<colors variant="dark">` and a dark-only theme its dark ones),
     * refused where its `<meta min-app>` is newer than [appVersion]. [appVersion] null: not checked;
     * the app (T8a) passes its release's base version (`Versions.baseVersionName`, `0.6.0`), not a
     * development build's `0.6.0-12-gabcdef`, whose suffix [newer] would drop anyway.
     */
    fun theme(source: String, systemColors: SystemColors = SystemColors.NONE, appVersion: String? = null, maxStyles: Int = MAX_STYLES): Parsed<Theme> =
        when (val xml = Xml.parse(source)) {
            is Parsed.Failed -> xml
            is Parsed.Ok -> theme(xml.value, systemColors, appVersion, maxStyles)
        }

    /**
     * [root] read. A theme comes from anywhere: whatever goes wrong reading it (a bug of ours it
     * finds, a stack it overflows) is a problem of the file, never a crash of the keyboard.
     */
    @Suppress("TooGenericExceptionCaught")
    fun theme(root: XmlElement, systemColors: SystemColors = SystemColors.NONE, appVersion: String? = null, maxStyles: Int = MAX_STYLES): Parsed<Theme> = try {
        read(root, systemColors, appVersion, maxStyles)
    } catch (e: Throwable) {
        Parsed.Failed(root.line, "the theme could not be read: ${e::class.simpleName}")
    }

    private fun read(root: XmlElement, system: SystemColors, appVersion: String?, maxStyles: Int): Parsed<Theme> {
        if (root.name != "theme") return Parsed.Failed(root.line, "the root is <${root.name}>, not <theme>")
        val p = Problems()
        val a = Attributes(root, p)
        if (a.string("xmlns") != NAMESPACE) a.problem("xmlns= must be $NAMESPACE")
        val name = a.required("name")?.also { if (it.isBlank() || it.length > MAX_NAME) a.problem("name= must be 1–$MAX_NAME characters") }
        val variants = variants(a)
        val version = a.matching("version", VERSION, "4.x") ?: a.required("version")
        a.done()
        val order = listOf("meta", "colors", "picture", "font", "style", "icon")
        root.children.filter { it.name in order }.zipWithNext().firstOrNull { (x, y) -> order.indexOf(x.name) > order.indexOf(y.name) }
            ?.let { (_, y) -> p.add(y.line, "<${y.name}> is out of order (meta, colors, picture, font, style, icon)") }
        root.children.filter { it.name !in order }.forEach {
            if (it.name in RESERVED_ELEMENTS) p.warn(it.line, ignored("<${it.name}>")) else p.add(it.line, "<${it.name}> is not allowed in <theme>")
        }
        val metas = root.all("meta")
        if (metas.size > 1) p.add(metas[1].line, "<meta> given twice")
        val meta = metas.firstOrNull()?.let { meta(it, p, appVersion) }
        val (colors, darkColors) = colorTables(root, variants, p, system)
        val dark = variants == setOf(true)
        val pictures = root.all("picture")
        if (pictures.size > 1) p.add(pictures[1].line, "<picture> given twice")
        val picture = pictures.firstOrNull()?.let { picture(it, p) }
        val fonts = root.all("font").also { if (it.size > MAX_FONTS) p.add(it[MAX_FONTS].line, "over $MAX_FONTS fonts") }
            .mapNotNull { named(it, p, "src") }.toMap()
        val iconEls = root.all("icon")
        if (iconEls.size > MAX_ICONS) p.add(iconEls[MAX_ICONS].line, "over $MAX_ICONS icons")
        val icons = iconEls.take(MAX_ICONS).mapNotNull { named(it, p, "src") }.toMap()
        val styleEls = root.all("style")
        if (styleEls.size > maxStyles) p.add(styleEls[maxStyles].line, "over $maxStyles styles")
        // the rest not read: the file is refused already, and a million styles would each be a problem
        val styles = styleEls.take(maxStyles).mapIndexedNotNull { i, el -> style(el, i, p, colors.keys, fonts.keys) }
        return p.result {
            val drawn = if (dark) darkColors ?: colors else colors
            Theme(name.orEmpty(), dark, version.orEmpty(), drawn, styles, fonts, icons, picture, variants, colors, darkColors, meta, system)
        }
    }

    // <colors>, and <colors variant="dark"> where given: the dark one the light one but what it gives
    private fun colorTables(root: XmlElement, variants: Set<Boolean>, p: Problems, system: SystemColors): Pair<Map<String, Argb>, Map<String, Argb>?> {
        val (darkEls, baseEls) = root.all("colors").partition { it["variant"] == "dark" }
        if (baseEls.size != 1) p.add(root.line, "<theme> needs one <colors>")
        if (darkEls.size > 1) p.add(darkEls[1].line, "<colors variant=\"dark\"> given twice")
        if (darkEls.isNotEmpty() && true !in variants) p.add(darkEls[0].line, "<colors variant=\"dark\"> in a theme with no dark variant")
        val baseRaw = baseEls.firstOrNull()?.let { rawColors(it, p, required = true) }.orEmpty()
        val reported = HashSet<Triple<Boolean, String, String>>()
        val colors = resolved(baseRaw, p, system.of(variants == setOf(true)), reported, null)
        // the dark version's whenever there is one: its system() colours are the dark roles, a <colors variant="dark"> or not
        val darkColors = if (true !in variants) null else {
            val over = darkEls.firstOrNull()?.let { rawColors(it, p, required = false) }.orEmpty()
            over.filterKeys { it !in baseRaw }.forEach { (k, v) -> p.add(v.second, "the colour $k is not in the light <colors>") }
            resolved(baseRaw + over, p, system.dark, reported, over.keys)
        }
        return colors to darkColors
    }

    // variants="light|dark|light dark", or 3.0's way dark="true|false": one of the two
    private fun variants(a: Attributes): Set<Boolean> {
        val given = a.string("variants") != null || a.string("dark") != null
        val dark = a.boolean("dark")
        val variants = a.oneOf("variants", listOf("light", "dark", "light dark"))
        if (a.string("dark") != null && a.string("variants") != null) a.problem("dark= and variants= both given")
        if (!given) a.problem("variants= is required")
        return when (variants) {
            "light" -> setOf(false)
            "dark" -> setOf(true)
            "light dark" -> setOf(false, true)
            else -> setOf(dark ?: false)
        }
    }

    private fun ignored(what: String) = "$what is not supported by this version, ignored"

    private const val MAX_META = 64
    private const val THREE = 3
    private val APP_VERSION = Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")
    private val PREVIEW = Regex("[^/\\\\:*?\"<>| \\t\\n\\r\\f\\x0B\\u00A0][^/\\\\:*?\"<>|]{0,122}\\.(png|webp)")

    private fun meta(el: XmlElement, p: Problems, appVersion: String?): Meta {
        val a = Attributes(el, p)
        fun text(name: String) = a.value(name, "1–$MAX_META characters") { it.takeIf { v -> v.isNotBlank() && v.length <= MAX_META } }
        val meta = Meta(
            author = text("author"),
            version = text("version"),
            license = text("license"),
            minApp = a.matching("min-app", APP_VERSION, "a version like 0.6.0"),
            preview = a.matching("preview", PREVIEW, "a .png or .webp file name"),
        )
        a.done()
        el.children.forEach { p.add(it.line, "<${it.name}> is not allowed in <meta>") }
        val min = meta.minApp
        if (min != null && appVersion != null && newer(min, appVersion)) p.add(el.line, "the theme needs the app $min or newer, this is $appVersion")
        return meta
    }

    /**
     * Whether [version] (`0.6.0`) is newer than [app] (`0.5.2`; a leading `v` and what follows a `-`
     * dropped: `v0.6.0-beta1` is `0.6.0`). Either not three numbers after that: false, it cannot tell.
     */
    fun newer(version: String, app: String): Boolean {
        fun numbers(v: String) = v.removePrefix("v").substringBefore('-').takeIf(APP_VERSION::matches)?.split('.')?.map { it.toInt() }
        val x = numbers(version) ?: return false
        val y = numbers(app) ?: return false
        return (0 until THREE).firstOrNull { x[it] != y[it] }?.let { x[it] > y[it] } ?: false
    }

    private const val MAX_NAME = 64

    // a <colors>' variables as written, each with its line; the 24 all there where [required]
    private fun rawColors(el: XmlElement, p: Problems, required: Boolean): Map<String, Pair<ColorExpr, Int>> {
        Attributes(el, p).apply { oneOf("variant", listOf("dark")) }.done()
        val raw = LinkedHashMap<String, Pair<ColorExpr, Int>>()
        if (el.children.size > MAX_COLORS) p.add(el.line, "over $MAX_COLORS colours")
        for (c in el.children) {
            val (name, value) = color(c, p) ?: continue
            if (name in raw) p.add(c.line, "the colour $name is given twice")
            raw[name] = value to c.line
        }
        if (required) REQUIRED_COLORS.filter { it !in raw }.takeIf { it.isNotEmpty() }?.let { p.add(el.line, "colours missing: ${it.joinToString(" ")}") }
        return raw
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

    // every colour variable resolved, those that name what is not there or themselves left out (and a
    // problem; each once in [reported] by its table, colour and message: the dark colours resolve the
    // light ones they do not give again, [dark] those they give, said to be the dark colours')
    private fun resolved(
        raw: Map<String, Pair<ColorExpr, Int>>,
        p: Problems,
        system: Map<String, Argb>,
        reported: MutableSet<Triple<Boolean, String, String>>,
        dark: Set<String>?,
    ): Map<String, Argb> {
        val done = HashMap<String, Argb>()
        val where = if (dark != null) " in the dark colours" else ""
        fun problem(colour: String, line: Int, message: String) {
            if (reported.add(Triple(dark?.contains(colour) == true, colour, message))) p.add(line, message + where)
        }
        fun resolve(name: String, seen: Set<String>): Argb? {
            done[name]?.let { return it }
            val (expr, line) = raw[name] ?: return null
            if (name in seen) return null.also { problem(name, line, "the colour $name is defined by itself") }
            val v = expr.resolve({ resolve(it, seen + name) }, system::get)
            if (v == null) expr.refs().filter { it !in raw }.forEach { problem(name, line, "@$it is no colour") }
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
        if (a.oneOf("animated", listOf("true", "false")) == "true") p.warn(el.line, ignored("<picture animated=\"true\">"))
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
        var reserved = 0
        for ((prop, what) in PROPERTY_VALUES) {
            val raw = el[prop] ?: continue
            val v = a.value(prop, what) { Values.read(prop, it) ?: RESERVED_VALUE.takeIf { _ -> Values.reservedValue(prop, it) } }
            if (v === RESERVED_VALUE) {
                p.warn(el.line, ignored("$prop=\"$raw\""))
                reserved++
            } else if (v != null) {
                values[prop] = v
            }
        }
        reserved += reserved(el, a, p, colors)
        a.done()
        selectors?.mapNotNull { it.reserved }?.distinct()?.forEach { p.warn(el.line, ignored(it)) }
        named(values, a, colors, fonts)
        selectors?.let { readElsewhere(it, values.keys, el.line, p) }
        if (values.isEmpty() && reserved == 0) a.problem("a style that sets nothing")
        return selectors?.let { Style(it, values, order, el.line) }
    }

    // what a style's [values] name is there: its colours in <colors>, its fonts a <font>, its icons the app's
    private fun named(values: Map<String, Value>, a: Attributes, colors: Set<String>, fonts: Set<String>) {
        for (v in values.values) {
            colorsOf(v).flatMap { it.refs() }.filter { it !in colors }.forEach { a.problem("@$it is no colour") }
        }
        for (font in FONT_PROPERTIES) {
            (values[font] as? Value.Word)?.word?.takeIf { it != "system" && it !in fonts }?.let { a.problem("$font=\"$it\" is no <font>") }
        }
        for ((prop, v) in values) {
            ((v as? Value.OfShown)?.shown as? Shown.Icon)?.name?.takeIf { it !in Icons.ALL }?.let { a.problem("$prop=\"icon($it)\" is no icon of the app's") }
        }
    }

    // the properties naming a font: a <font>'s, or system
    private val FONT_PROPERTIES = listOf("font", "font-han", "font-latin")

    // a key's properties read from one part of it (`align` and `space-content` from its label, `return-icon-*`
    // from its icon, inherited from the key): set on another part they do nothing, which a warning says
    private fun readElsewhere(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        for (prop in props) {
            val from = if (prop.startsWith(Values.RETURN_ICON)) "icon" else if (prop in LABEL_READ) "label" else continue
            selectors.mapNotNull { it.part }.filter { it != from }.distinct()
                .forEach { p.warn(line, "$prop= on a key's $it does nothing: it is read from its $from") }
        }
    }

    private val LABEL_READ = setOf("align", "space-content")

    // a property of today's value a later stage reads ([Values.reservedValue]): ignored
    private val RESERVED_VALUE = Value.Flag(false)

    // a later stage's properties ([Values.RESERVED]) each a warning where of their form and naming colours there; how many
    private fun reserved(el: XmlElement, a: Attributes, p: Problems, colors: Set<String>): Int {
        val given = Values.RESERVED.filterKeys { el[it] != null }
        for ((prop, form) in given) {
            val refs = a.value(prop, "a value of $prop") { form.read(it) }?.flatMap { it.refs() } ?: continue
            refs.filter { it !in colors }.forEach { a.problem("@$it is no colour") }
            p.warn(el.line, ignored("$prop="))
        }
        return given.size
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
