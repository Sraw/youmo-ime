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
    // no control character, nor a name all dots
    internal val FILE = Regex("(?!\\.+$)[^/\\\\:*?\"<>| \\x00-\\x1F\\u00A0][^/\\\\:*?\"<>|\\x00-\\x1F]{0,127}")
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
        val fonts = named(atMost(root.all("font"), MAX_FONTS, p, "fonts"), p, "font")
        val icons = named(atMost(root.all("icon"), MAX_ICONS, p, "icons"), p, "icon")
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
     * Whether [source] is a later build's theme, whether or not it reads here: its root's `version=` a later
     * format's than 4.x (`5.0`), or its `<meta min-app>` newer than [app]. A file that is no XML, or says
     * neither, is not ([newer]: what cannot tell is not).
     */
    fun laterBuilds(source: String, app: String): Boolean {
        val root = (Xml.parse(source) as? Parsed.Ok)?.value?.takeIf { it.name == "theme" } ?: return false
        val format = root["version"]?.let(FORMAT::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
        if (format != null && format > FORMAT_MAJOR) return true
        return root.all("meta").any { meta -> meta["min-app"]?.let { newer(it, app) } == true }
    }

    private const val FORMAT_MAJOR = 4
    private val FORMAT = Regex("([0-9]{1,4})\\.[0-9]{1,4}")

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

    /** A theme's `name=` at its longest, characters. */
    const val MAX_NAME = 64

    // a <colors>' variables as written, each with its line; the 24 all there where [required]
    private fun rawColors(el: XmlElement, p: Problems, required: Boolean): Map<String, Pair<ColorExpr, Int>> {
        Attributes(el, p).apply { oneOf("variant", listOf("dark")) }.done()
        val raw = LinkedHashMap<String, Pair<ColorExpr, Int>>()
        if (el.children.size > MAX_COLORS) p.add(el.line, "over $MAX_COLORS colours")
        for (c in el.children.take(MAX_COLORS)) {
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
        // the names being resolved, one set for the whole walk (not a copy at each step: a long chain stays cheap)
        val seen = HashSet<String>()
        fun resolve(name: String): Argb? {
            done[name]?.let { return it }
            val (expr, line) = raw[name] ?: return null
            if (!seen.add(name)) return null.also { problem(name, line, "the colour $name is defined by itself") }
            val v = expr.resolve(::resolve, system::get)
            seen.remove(name)
            if (v == null) expr.refs().filter { it !in raw }.forEach { problem(name, line, "@$it is no colour") }
            return v?.also { done[name] = it }
        }
        raw.keys.forEach { resolve(it) }
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

    // the first [max] of [els], one more a problem ("over [max] [what]")
    private fun atMost(els: List<XmlElement>, max: Int, p: Problems, what: String): List<XmlElement> {
        if (els.size > max) p.add(els[max].line, "over $max $what")
        return els.take(max)
    }

    // [els]' names and files, a name given twice a problem as a colour's is ([what]: font, icon)
    private fun named(els: List<XmlElement>, p: Problems, what: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (el in els) {
            val (name, src) = named(el, p, "src") ?: continue
            if (name in out) p.add(el.line, "the $what $name is given twice")
            out[name] = src
        }
        return out
    }

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
            val v = a.value(prop, what) { Values.read(prop, it) ?: follow(prop, it) }
            if (v == FOLLOW && selectors.orEmpty().any { it.widget != NAVBAR }) {
                p.warn(el.line, "color=\"follow\" is the navigation bar's alone: ignored")
                reserved++
            } else if (v != null) {
                values[prop] = v
            }
        }
        reserved += sizesAndReserved(el, a, p, colors, selectors.orEmpty(), values)
        a.done()
        selectors?.let { selectorWarnings(el, p, it) }
        selectors?.let { frame(it, values, el.line, p) }
        named(values, a, colors, fonts)
        selectors?.let { readElsewhere(it, values.keys, el.line, p) }
        reserved += selectors?.let { shapes(it, values, el.line, p) } ?: 0
        selectors?.let { unread(it, values, el.line, p) }
        if (values.isEmpty() && reserved == 0) a.problem("a style that sets nothing")
        return selectors?.let { Style(it, values, order, el.line) }
    }

    // a keyboard's size: on a keyboard alone, or (under a scene, in a state, a list) read, warned of, ignored; the same
    // names of another widget a later stage's, of one in a list with a keyboard too; how many ignored
    private fun sizesAndReserved(
        el: XmlElement, a: Attributes, p: Problems, colors: Set<String>, selectors: List<Selector>, values: MutableMap<String, Value>,
    ): Int {
        val keyboard = selectors.any { it.widget == "keyboard" }
        val sized = if (keyboard) sizes(el, a, p, selectors, values) else 0
        val keyboards = keyboard && selectors.all { it.widget == "keyboard" }
        val handles = selectors.isNotEmpty() && selectors.all { it.widget == HANDLE }
        if (handles) grip(el, a, values)
        // the bar's height (T6b), on a style of bars alone: under a scene too (a bar lower turned)
        val bars = selectors.isNotEmpty() && selectors.all { it.widget == "bar" }
        if (bars && el["height"] != null) {
            val what = "${Values.MIN_BAR_HEIGHT.toInt()}%–${Values.MAX_BAR_HEIGHT.toInt()}%"
            a.value("height", what) { Values.barHeight(it) }?.let { values["height"] = Value.Percent(it) }
        }
        val skip = when {
            keyboards -> Values.KEYBOARD_SIZES
            handles -> Values.HANDLE_SIZES
            bars -> listOf("height")
            else -> emptyList()
        }
        return sized + reserved(el, a, p, colors, skip)
    }

    // the handle's grip ([Values.HANDLE_SIZES]), on a style of handles alone (in a list with others, a later stage's)
    private fun grip(el: XmlElement, a: Attributes, values: MutableMap<String, Value>) {
        for (prop in Values.HANDLE_SIZES.filter { el[it] != null }) {
            val max = if (prop == "width") Values.MAX_GRIP_WIDTH_DP else Values.MAX_GRIP_HEIGHT_DP
            a.value(prop, "0–${max.toInt()}dp") { Values.gripSize(prop, it) }?.let { values[prop] = Value.Number(it) }
        }
    }

    // `color="follow"` ([Values.FOLLOW]): the navigation bar's (a style of others warned of and ignoring it)
    private fun follow(prop: String, raw: String): Value? = FOLLOW.takeIf { prop == "color" && raw == Values.FOLLOW }

    // the frame is the system's outline, of one radius and an elevation's shadow (straight down, no offset of its own)
    private fun frame(selectors: List<Selector>, values: Map<String, Value>, line: Int, p: Problems) {
        if (selectors.none { it.widget == "frame" }) return
        val shadow = (values["shadow"] as? Value.OfShadow)?.shadow
        if (shadow != null && (shadow.dx != 0.0 || shadow.dy != 0.0)) {
            p.warn(line, "shadow= of the frame has no offset (the system's, of its blur's height): its x and y are ignored")
        }
        val radius = (values["radius"] as? Value.OfRadius)?.radius
        if (radius != null && radius.corners.distinct().size > 1) p.warn(line, "radius= of the frame is one radius: its first is taken")
    }

    // what of the selectors selects nothing: a later stage's, an input method this app has none of (a misspelt
    // one, a table it lacks)
    private fun selectorWarnings(el: XmlElement, p: Problems, selectors: List<Selector>) {
        selectors.mapNotNull { it.reserved }.distinct().forEach { p.warn(el.line, ignored(it)) }
        selectors.flatMap { it.scenes }.filter { it.startsWith("im=") && it.removePrefix("im=") !in Selector.IMS }.distinct()
            .forEach { p.warn(el.line, "@$it is no input method of this app: it selects nothing") }
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

    // a window widget's property it has no use for (`navbar fill=`, `frame fill=`): warned of, as one may think it does
    // something; in a list, where another widget may read it, too (of that widget)
    private fun unreadByWindow(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        for ((w, states) in selectors.filter { it.widget in WINDOW_READS }.map { it.widget to it.states }.distinct()) {
            val on = w + states.sorted().joinToString("") { ":$it" }
            val reads = windowReads(w, states)
            val said = if (reads.isEmpty()) "it reads nothing in that state" else "it reads ${reads.joinToString(" ")}"
            props.filter { it !in reads }.forEach { p.warn(line, "$it= on $on does nothing: $said") }
        }
    }

    // a window widget's properties read in [states]: in none all of WINDOW_READS; pressed, a side button's
    // overlay (WindowLooks.sideButton's ripple); no other state of the window is ever asked for
    private fun windowReads(widget: String, states: Set<String>): List<String> = when {
        states.isEmpty() -> WINDOW_READS.getValue(widget)
        widget == "side-button" && states == setOf("pressed") -> listOf("overlay")
        else -> emptyList()
    }

    // what the window's widgets, the panels and the candidates have no use for (T7a, T7b, T6a)
    private fun unread(selectors: List<Selector>, values: Map<String, Value>, line: Int, p: Problems) {
        unreadByWindow(selectors, values.keys, line, p)
        unreadByPanel(selectors, values.keys, line, p)
        unreadByBar(selectors, values, line, p)
        val fill = (values["fill"] as? Value.OfFill)?.fill
        // a panel's native view takes a colour; a picture's or an image's fill is the scene's alone (PanelLooks.fill)
        if (fill != null && fill !is Fill.Solid) {
            selectors.map { it.widget }.filter { it in Selector.PANEL_WIDGETS }.distinct()
                .forEach { p.warn(line, "fill= on $it is no colour: a panel's fill is a colour alone, ignored") }
        }
        unreadPad(selectors, values.keys, line, p)
    }

    // a panel's property it has no use for (`clipboard shadow=`), as the window's; the voice mask's blur and the
    // handwriting pad's ink and grid set on what does not read them, too
    private fun unreadByPanel(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        for (s in selectors.filter { it.widget in Selector.PANEL_WIDGETS }.map { Selector.Target(it.widget, states = it.states, part = it.part) }.distinct()) {
            val reads = panelReads(s)
            val at = s.widget + s.states.sorted().joinToString("") { ":$it" } + s.part?.let { " $it" }.orEmpty()
            val said = if (reads.isEmpty()) "it reads nothing in that state" else "it reads ${reads.joinToString(" ")}"
            props.filter { it !in reads }.forEach { p.warn(line, "$it= on $at does nothing: $said") }
        }
        for (prop in props) {
            val (widget, whose) = when (prop) {
                "blur" -> "voice" to "the voice mask's"
                in PAD_PROPERTIES -> "key" to "the handwriting pad's (handwriting/pad)"
                else -> continue
            }
            // the window's widgets and the candidates' warned of already, by what they read (unreadByWindow, unreadByBar)
            selectors.map { it.widget }.filter { it != widget && it !in Selector.PANEL_WIDGETS && it !in WINDOW_READS && it !in Selector.BAR_WIDGETS }.distinct()
                .forEach { p.warn(line, "$prop= on $it does nothing: it is $whose") }
        }
    }

    // the pad's ink and grid on a key named that is not the pad (`qwerty/q`, `handwriting/*`, which selects no slot, `*/enter`):
    // it reads them alone; a class or a kind may be the pad's
    private fun unreadPad(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        val pad = props.filter { it in PAD_PROPERTIES }
        if (pad.isEmpty()) return
        val named = selectors.filter { it.widget == "key" && (it.board != null || it.starRole != null) }
            .filterNot { it.board == PAD_BOARD && it.key == PAD_KEY }
            .map { s -> s.board?.let { "$it/${s.key ?: "*"}" } ?: "*/${s.starRole}" }.distinct()
        for (at in named) pad.forEach { p.warn(line, "$it= on $at does nothing: it is the handwriting pad's (handwriting/pad)") }
    }

    // what [s] (a panel, its states and part) reads: in a state, only the picker's one shown
    private fun panelReads(s: Selector.Target): List<String> {
        val selected = s.widget == "picker" && s.states == setOf("selected")
        return when {
            // the one shown: its categories' fill and colour, and the colour they inherit; no fill of the picker's own
            selected && s.part == null -> listOf("color")
            s.states.isEmpty() || selected -> PANEL_READS.getValue(s.part?.let { "${s.widget} $it" } ?: s.widget)
            else -> emptyList()
        }
    }

    // what each panel reads, and each part of one (060-render-p3 T7b, engine/PanelLooks): `color` and `icon-color`
    // on the picker and the voice mask are their parts', inherited
    private val PANEL_READS = mapOf(
        "clipboard" to listOf("fill", "radius", "color"),
        "picker" to listOf("fill", "color"),
        "picker categories" to listOf("fill", "color"),
        "editing" to listOf("fill", "radius"),
        "voice" to listOf("fill", "blur", "icon-color"),
        "voice mic" to listOf("fill", "icon-color"),
    )

    private val PAD_PROPERTIES = setOf("ink-color", "ink-width", "grid-color", "grid")
    private const val PAD_BOARD = "handwriting"
    private const val PAD_KEY = "pad"

    // what each widget of the window round the keys reads (060-render-p3 T7a, engine/WindowLooks, SceneBuilder's split gap)
    private val WINDOW_READS = mapOf(
        "frame" to listOf("radius", "border", "shadow"),
        HANDLE to listOf("fill", "color", "icon-color", "width", "height"),
        "side-button" to listOf("fill", "icon-color", "shape", "radius", "overlay"),
        "split-gap" to listOf("fill", "radius"),
        NAVBAR to listOf("color"),
    )

    // a property the bar or a candidate has no use for (`candidate size=`, `candidates-divider radius=`), or none in
    // that state or part: warned of, as the window's are; the bar's base of a gradient or an image, which the
    // bar's view does not draw (only a colour or the picture)
    private fun unreadByBar(selectors: List<Selector>, values: Map<String, Value>, line: Int, p: Problems) {
        // the window's and the panels' warned of by what they read (unreadByWindow, unreadByPanel)
        val others = selectors.map { it.widget }.distinct()
            .filter { it !in Selector.BAR_WIDGETS && it !in Selector.RESERVED_WIDGETS && it !in WINDOW_READS && it !in Selector.PANEL_WIDGETS }
        for (w in others) CANDIDATE_ONLY.filter { it in values }.forEach { p.warn(line, "$it= on $w does nothing: only a candidate has it") }
        for (w in others) PREEDIT_ONLY.filter { it in values }.forEach { p.warn(line, "$it= on $w does nothing: only the code being typed (preedit) has it") }
        unplacedBar(selectors, line, p)
        for (s in selectors.filter { it.widget in Selector.BAR_WIDGETS }.distinctBy { Triple(it.widget, it.states, it.part) }) {
            val on = s.widget + s.states.sorted().joinToString("") { ":$it" } + (s.part?.let { " $it" } ?: "")
            val reads = barReads(s.widget, s.states, s.part)
            val said = if (reads.isEmpty()) "it reads nothing ${if (s.part != null) "of that part " else ""}in that state" else "it reads ${reads.joinToString(" ")}"
            values.keys.filter { it !in reads }.forEach { p.warn(line, "$it= on $on does nothing: $said") }
            val fill = (values["fill"] as? Value.OfFill)?.fill
            if ("fill" !in reads || fill == null || fill is Fill.Solid) continue
            if (s.widget != "bar") {
                p.warn(line, "fill= on $on is a colour: a gradient, an image or the picture is not drawn there, none drawn")
            } else if (fill !is Fill.Picture) {
                p.warn(line, "fill= on bar is a colour or picture(…): a gradient or an image is not drawn there, none drawn")
            }
        }
    }

    // what each of the bar's widgets reads (060-render-p3 T6a, engine/BarLook) in [states], of [part]: a
    // candidate's layout and pressed colour in no state, its colours and font when first or selected too; a
    // part's colour alone (a candidate is one line of text: its number, words and comment in one font)
    private fun barReads(widget: String, states: Set<String>, part: String?): List<String> = when {
        // a toolbar button pressed its overlay; on (a toggle's, `:selected`) its fill and its icon's colour
        widget == "toolbar" && states == setOf("pressed") -> listOf("overlay")
        widget == "toolbar" && states == setOf("selected") -> listOf("fill", "icon-color")
        widget != "candidate" -> if (states.isEmpty()) BAR_READS.getValue(widget) else emptyList()
        part != null -> if ("pressed" in states) emptyList() else listOf("color")
        states.isEmpty() -> BAR_READS.getValue(widget)
        // the first pressed has an overlay of its own; nothing presses a selected candidate (the window's, by keys)
        "pressed" in states && (states - "pressed" - "first").isEmpty() -> listOf("overlay")
        "pressed" !in states -> CANDIDATE_STATE_READS
        else -> emptyList()
    }

    private val BAR_READS = mapOf(
        "bar" to listOf("fill", "height"),
        "candidate" to listOf("fill", "radius", "inset", "padding", "gap", "color", "font", "weight", "label-style"),
        "candidates-divider" to listOf("fill"),
        "candidates-panel" to listOf("fill"),
        // T6b's: the code being typed (its text, the underline fcitx asks for, the cursor), a toolbar button
        "preedit" to listOf("color", "underline", "cursor-color"),
        "toolbar" to listOf("fill", "radius", "icon-color"),
    )

    private val CANDIDATE_STATE_READS = listOf("fill", "color", "font", "weight")

    // T6a's properties no widget but a candidate has
    private val CANDIDATE_ONLY = listOf("gap", "label-style")

    // T6b's properties no widget but the code being typed has
    private val PREEDIT_ONLY = listOf("underline", "cursor-color")

    // the window forms the bar is not placed by (060-render-p3 T6b review): it is drawn the same over each
    private val UNPLACED = setOf("floating", "split", "one-handed")

    // the bar is one over every window form (SceneWindow.scenes: never floating, split or one-handed): such a selector never matches
    private fun unplacedBar(selectors: List<Selector>, line: Int, p: Problems) =
        selectors.filter { it.widget in Selector.BAR_WIDGETS }.flatMap { s -> s.scenes.filter { it in UNPLACED }.map { "@$it ${s.widget}" } }.distinct()
            .forEach { p.warn(line, "$it never matches: the bar is the same over a floating, split or one-handed keyboard") }

    // T4b's that do nothing where they are set, which a warning says: the bubble's shape, tail, size and
    // offset on anything but the bubble, a cap's depth on anything but a key, a path or an inner shadow on
    // what has no face drawn of it (the keyboard's base a plain fill, the bar's and the rest the app's), a radius
    // beside a path, a tail beside a shape that is no bubble. A size in % not on the bubble alone is ignored
    // too: kept, it would stand in for the sp size the widget would inherit. How many ignored.
    private fun shapes(selectors: List<Selector>, values: MutableMap<String, Value>, line: Int, p: Problems): Int {
        val bubbly = listOfNotNull(
            "shape=\"bubble\"".takeIf { (values["shape"] as? Value.Word)?.word == Values.BUBBLE },
            "tail=".takeIf { "tail" in values },
            "offset=".takeIf { "offset" in values },
        )
        val widgets = selectors.map { it.widget }.distinct().filter { it !in Selector.RESERVED_WIDGETS }
        for (w in widgets.filter { it != "preview" }) bubbly.forEach { p.warn(line, "$it on $w does nothing: only the preview (the key's bubble) has it") }
        val capped = listOf("depth", "depth-color").filter { it in values }
        for (w in widgets.filter { it != "key" }) capped.forEach { p.warn(line, "$it= on $w does nothing: only a key has a cap") }
        val faced = listOfNotNull("shape=\"path(…)\"".takeIf { values["shape"] is Value.OfPath }, "inner-shadow=".takeIf { "inner-shadow" in values })
        for (w in widgets.filter { it !in FACED }) faced.forEach { p.warn(line, "$it on $w does nothing: only a key, a popup, the preview and a menu have a face drawn") }
        if (values["shape"] is Value.OfPath && "radius" in values) p.warn(line, "radius= does nothing beside a path(…): the path is the outline")
        val shape = values["shape"]
        if ("tail" in values && shape != null && (shape as? Value.Word)?.word != Values.BUBBLE) {
            p.warn(line, "tail= does nothing beside a shape that is no bubble: only a bubble has a tail")
        }
        // a later stage's widgets aside (warned of, selecting nothing), as the warnings above
        val live = selectors.filter { it.widget !in Selector.RESERVED_WIDGETS }
        if (values["size"] !is Value.Percent || live.all { it.widget == "preview" && it.part == null }) return 0
        values.remove("size")
        p.warn(line, "size= in % is ignored but in a style of the preview alone (the key's bubble): elsewhere a size is a text's, in sp")
        return 1
    }

    // the widgets a face is drawn of, a path's or an inner shadow's
    private val FACED = setOf("key", "popup", "preview", "menu")

    // the navigation bar's colour as the keyboard's under it ([org.fcitx.fcitx5.android.scene.engine.WindowLooks.navbar])
    private val FOLLOW = Value.Word(Values.FOLLOW)
    private const val NAVBAR = "navbar"
    private const val HANDLE = "handle"

    // the keyboard's size, set where every selector is a `keyboard` under no scene or state ([Values.KEYBOARD_SIZES]);
    // else of its form, and ignored with a warning saying why (how many: a style of them alone sets something): a
    // theme's keyboard has one size, its scenes and states cannot change it, and a list's other widgets have none
    private fun sizes(el: XmlElement, a: Attributes, p: Problems, selectors: List<Selector>, values: MutableMap<String, Value>): Int {
        var ignored = 0
        val keyboards = selectors.filter { it.widget == "keyboard" }
        val why = when {
            keyboards.size < selectors.size -> "in a list of other widgets is ignored: a style of the keyboard alone sets its size"
            keyboards.any { it.scenes.isNotEmpty() } -> "under a scene is ignored: a theme's keyboard has one size"
            keyboards.any { it.states.isNotEmpty() } -> "in a state is ignored: a theme's keyboard has one size"
            else -> null
        }
        for (prop in Values.KEYBOARD_SIZES.filter { el[it] != null }) {
            val what = when (prop) {
                "height" -> "10%–90%, portrait then landscape"
                "side-padding" -> "0–300dp, portrait then landscape"
                else -> "0–100dp, portrait then landscape"
            }
            val v = a.value(prop, what) { Values.keyboardSize(prop, it) } ?: continue
            if (why == null) {
                values[prop] = Value.OfTwin(v)
            } else {
                p.warn(el.line, "$prop= of a keyboard $why")
                ignored++
            }
        }
        return ignored
    }

    // a later stage's properties ([Values.RESERVED], but [skip]) each a warning where of their form and naming colours there; how many
    private fun reserved(el: XmlElement, a: Attributes, p: Problems, colors: Set<String>, skip: List<String>): Int {
        val given = Values.RESERVED.filterKeys { el[it] != null && it !in skip }
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
