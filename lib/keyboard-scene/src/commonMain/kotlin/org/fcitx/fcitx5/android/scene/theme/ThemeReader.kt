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

/** A theme file read and checked: theme-4.xsd's rules plus what an import checks beyond them. */
object ThemeReader {
    const val NAMESPACE = "https://youmo-ime/theme/4"
    /** A theme file's styles at most; one the app writes itself may allow more (`maxStyles`). */
    const val MAX_STYLES = 1000
    private const val MAX_FONTS = 4
    private const val MAX_ICONS = 200
    private const val MAX_COLORS = 200
    private const val PERCENT = 100.0
    private val VERSION = Regex("4\\.[0-9]+")
    private val VAR_NAME = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
    // ASCII white space spelt out: `\s` is more on JS than on the JVM, and a file must read the same on both
    internal val FILE = Regex("(?!\\.+$)[^/\\\\:*?\"<>| \\x00-\\x1F\\u00A0][^/\\\\:*?\"<>|\\x00-\\x1F]{0,127}")
    private val ICON = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val SOUND_KEY = Regex("[a-z][a-z0-9-]{0,31}")

    val REQUIRED_COLORS = listOf(
        "background", "bar", "keyboard", "key", "keyText", "candidateText", "candidateLabel", "candidateComment", "altKey",
        "altKeyText", "accentKey", "accentKeyText", "keyPressHighlight", "keyShadow", "popupBackground", "popupText",
        "spaceBar", "divider", "clipboardEntry", "genericActiveBackground", "genericActiveForeground", "keyBorder", "keyHint",
        "toolbarIcon",
    )

    val RESERVED_ELEMENTS: Set<String> = emptySet()

    /** [appVersion] null: not checked; the app passes its release's base version, not a dev build's `0.6.0-12-gabcdef`. */
    fun theme(source: String, systemColors: SystemColors = SystemColors.NONE, appVersion: String? = null, maxStyles: Int = MAX_STYLES): Parsed<Theme> =
        when (val xml = Xml.parse(source)) {
            is Parsed.Failed -> xml
            is Parsed.Ok -> theme(xml.value, systemColors, appVersion, maxStyles)
        }

    /** A theme comes from anywhere: whatever goes wrong reading it (a bug of ours, a stack overflow) is a problem, never a crash. */
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
        val order = listOf("meta", "colors", "picture", "font", "style", "icon", "sounds")
        root.children.filter { it.name in order }.zipWithNext().firstOrNull { (x, y) -> order.indexOf(x.name) > order.indexOf(y.name) }
            ?.let { (_, y) -> p.add(y.line, "<${y.name}> is out of order (meta, colors, picture, font, style, icon, sounds)") }
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
        // the file is refused already, and a million styles would each be a problem
        val styles = styleEls.take(maxStyles).mapIndexedNotNull { i, el -> style(el, i, p, colors.keys, fonts.keys, icons.keys) }
        undrawnIcons(root.all("icon"), icons.keys, styles, p)
        val soundsEls = root.all("sounds")
        if (soundsEls.size > 1) p.add(soundsEls[1].line, "<sounds> given twice")
        val soundFiles = soundsEls.firstOrNull()?.let { sounds(it, p) }.orEmpty()
        if (picture?.animated == true) seeThroughWarnings(styles, picture.brightness, p)
        return p.result {
            val drawn = if (dark) darkColors ?: colors else colors
            Theme(name.orEmpty(), dark, version.orEmpty(), drawn, styles, fonts, icons, picture, variants, colors, darkColors, meta, system, soundFiles)
        }
    }

    private fun colorTables(root: XmlElement, variants: Set<Boolean>, p: Problems, system: SystemColors): Pair<Map<String, Argb>, Map<String, Argb>?> {
        val (darkEls, baseEls) = root.all("colors").partition { it["variant"] == "dark" }
        if (baseEls.size != 1) p.add(root.line, "<theme> needs one <colors>")
        if (darkEls.size > 1) p.add(darkEls[1].line, "<colors variant=\"dark\"> given twice")
        if (darkEls.isNotEmpty() && true !in variants) p.add(darkEls[0].line, "<colors variant=\"dark\"> in a theme with no dark variant")
        val baseRaw = baseEls.firstOrNull()?.let { rawColors(it, p, required = true) }.orEmpty()
        val reported = HashSet<Triple<Boolean, String, String>>()
        val colors = resolved(baseRaw, p, system.of(variants == setOf(true)), reported, null)
        // whenever there is a dark version its system() colours are the dark roles, with or without <colors variant="dark">
        val darkColors = if (true !in variants) null else {
            val over = darkEls.firstOrNull()?.let { rawColors(it, p, required = false) }.orEmpty()
            over.filterKeys { it !in baseRaw }.forEach { (k, v) -> p.add(v.second, "the colour $k is not in the light <colors>") }
            resolved(baseRaw + over, p, system.dark, reported, over.keys)
        }
        return colors to darkColors
    }

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

    /** Whether [source] is a later build's theme: `version=` past 4.x, or `<meta min-app>` newer than [app]. */
    fun laterBuilds(source: String, app: String?): Boolean {
        val root = (Xml.parse(source) as? Parsed.Ok)?.value?.takeIf { it.name == "theme" } ?: return false
        val format = root["version"]?.let(FORMAT::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
        if (format != null && format > FORMAT_MAJOR) return true
        return app != null && root.all("meta").any { meta -> meta["min-app"]?.let { newer(it, app) } == true }
    }

    private const val FORMAT_MAJOR = 4
    private val FORMAT = Regex("([0-9]{1,4})(?:\\.[0-9]{1,4})?")

    /** Either not three numbers: false, it cannot tell. */
    fun newer(version: String, app: String): Boolean {
        fun numbers(v: String) = v.removePrefix("v").substringBefore('-').takeIf(APP_VERSION::matches)?.split('.')?.map { it.toInt() }
        val x = numbers(version) ?: return false
        val y = numbers(app) ?: return false
        return (0 until THREE).firstOrNull { x[it] != y[it] }?.let { x[it] > y[it] } ?: false
    }

    const val MAX_NAME = 64

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

    private fun color(c: XmlElement, p: Problems): Pair<String, ColorExpr>? {
        if (c.name != "color") return null.also { p.add(c.line, "<${c.name}> is not allowed in <colors>") }
        val a = Attributes(c, p)
        val name = a.matching("name", VAR_NAME, "a colour name") ?: a.required("name")
        val value = a.value("value", "a colour", ColorExpr::parse) ?: a.required("value").let { null }
        a.done()
        return if (name == null || value == null) null else name to value
    }

    // those naming something missing or themselves are left out and reported once per table, colour and message
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
        // one set for the whole walk, not a copy per step: a long chain stays cheap
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

    // over an animated picture a view is only darkened, never lighter than the board, so text meant for a lighter one may not read
    private fun seeThroughWarnings(styles: List<Style>, board: Double, p: Problems) = styles.forEach { s ->
        s.values.forEach { (name, v) ->
            val fill = (v as? Value.OfFill)?.fill as? Fill.Picture ?: return@forEach
            if ((fill.blurDp ?: 0.0) > 0) p.warn(s.line, "$name=\"picture(…)\"'s blur over an animated <picture> is not drawn")
            if ((fill.brightness ?: board) > board) {
                p.warn(s.line, "$name=\"picture(…)\" over an animated <picture> is no lighter than its brightness: check the text reads")
            }
        }
    }

    private fun picture(el: XmlElement, p: Problems): Picture? {
        val a = Attributes(el, p)
        val src = a.matching("src", FILE, "a file name") ?: a.required("src")
        val brightness = a.value("brightness", "0%–100%") { Values.percent(it)?.takeIf { v -> v <= PERCENT } } ?: PERCENT
        val blur = a.value("blur", "0dp–25dp") { Values.dp(it, MAX_BLUR) } ?: 0.0
        val animated = a.oneOf("animated", listOf("true", "false")) == "true"
        a.done()
        // a per-frame blur costs too much battery
        if (animated && blur > 0) p.warn(el.line, "blur= on an animated <picture> is not drawn: its frames are not blurred")
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
        return src?.let { Picture(it, brightness, blur, cuts, animated) }
    }

    private const val MAX_BLUR = 25.0

    private fun atMost(els: List<XmlElement>, max: Int, p: Problems, what: String): List<XmlElement> {
        if (els.size > max) p.add(els[max].line, "over $max $what")
        return els.take(max)
    }

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

    private fun sounds(el: XmlElement, p: Problems): List<Pair<String, String>> {
        el.attributes.keys.filter { !namespace(it) }
            .forEach { p.warn(el.line, ignored("<sounds $it=>")) }
        val out = ArrayList<Pair<String, String>>()
        for (s in atMost(el.children, SoundFile.MAX_SOUNDS, p, "sounds")) {
            val (key, src) = sound(s, p) ?: continue
            if (out.any { it.first == key }) p.add(s.line, "the sound $key is given twice")
            if (SoundKey.of(key) == null) p.warn(s.line, ignored("<sound key=\"$key\">"))
            out += key to src
        }
        return out
    }

    private fun sound(s: XmlElement, p: Problems): Pair<String, String>? {
        if (s.name != "sound") return null.also { p.add(s.line, "<${s.name}> is not allowed in <sounds>") }
        val a = Attributes(s, p)
        val key = a.matching("key", SOUND_KEY, "a sound's class") ?: a.required("key")
        val src = a.matching("src", FILE, "a file name") ?: a.required("src")
        s.attributes.keys.filter { it != "key" && it != "src" && !namespace(it) }
            .forEach { p.warn(s.line, ignored("<sound $it=>")) }
        return if (key != null && src != null) key to src else null
    }

    private fun namespace(name: String): Boolean = name == "xmlns" || name.startsWith("xmlns:") || name.startsWith("xsi:")

    // drawn nowhere, though a theme's own layout may use it
    private fun undrawnIcons(els: List<XmlElement>, icons: Set<String>, styles: List<Style>, p: Problems) {
        val used = styles.flatMap { it.values.values }.mapNotNull { ((it as? Value.OfShown)?.shown as? Shown.Icon)?.name }.toSet()
        for (name in icons) {
            if (name in Icons.ALL || name in used) continue
            val line = els.firstOrNull { it["name"] == name }?.line ?: 0
            p.warn(line, "<icon name=\"$name\"> is no icon of the app's and no icon() names it: it is not drawn")
        }
    }

    private fun style(el: XmlElement, order: Int, p: Problems, colors: Set<String>, fonts: Set<String>, icons: Set<String>): Style? {
        val a = Attributes(el, p)
        val select = a.required("select")
        val selectors = select?.let { Selector.parseList(it) ?: null.also { a.problem("select=\"$select\" is no selector") } }
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
        named(values, a, colors, fonts, icons)
        selectors?.let { readElsewhere(it, values.keys, el.line, p) }
        reserved += selectors?.let { shapes(it, values, el.line, p) } ?: 0
        selectors?.let { unread(it, values, el.line, p) }
        if (values.isEmpty() && reserved == 0) a.problem("a style that sets nothing")
        return selectors?.let { Style(it, values, order, el.line) }
    }

    // a keyboard's size only on a bare keyboard style; elsewhere read but warned of and ignored
    private fun sizesAndReserved(
        el: XmlElement, a: Attributes, p: Problems, colors: Set<String>, selectors: List<Selector>, values: MutableMap<String, Value>,
    ): Int {
        val keyboard = selectors.any { it.widget == "keyboard" }
        val sized = if (keyboard) sizes(el, a, p, selectors, values) else 0
        val keyboards = keyboard && selectors.all { it.widget == "keyboard" }
        val handles = selectors.isNotEmpty() && selectors.all { it.widget == HANDLE }
        if (handles) grip(el, a, values)
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

    private fun grip(el: XmlElement, a: Attributes, values: MutableMap<String, Value>) {
        for (prop in Values.HANDLE_SIZES.filter { el[it] != null }) {
            val max = if (prop == "width") Values.MAX_GRIP_WIDTH_DP else Values.MAX_GRIP_HEIGHT_DP
            a.value(prop, "0–${max.toInt()}dp") { Values.gripSize(prop, it) }?.let { values[prop] = Value.Number(it) }
        }
    }

    private fun follow(prop: String, raw: String): Value? = FOLLOW.takeIf { prop == "color" && raw == Values.FOLLOW }

    // the frame is the system's outline: one radius and an elevation's shadow (no offset of its own)
    private fun frame(selectors: List<Selector>, values: Map<String, Value>, line: Int, p: Problems) {
        if (selectors.none { it.widget == "frame" }) return
        val shadow = (values["shadow"] as? Value.OfShadow)?.shadow
        if (shadow != null && (shadow.dx != 0.0 || shadow.dy != 0.0)) {
            p.warn(line, "shadow= of the frame has no offset (the system's, of its blur's height): its x and y are ignored")
        }
        val radius = (values["radius"] as? Value.OfRadius)?.radius
        if (radius != null && radius.corners.distinct().size > 1) p.warn(line, "radius= of the frame is one radius: its first is taken")
    }

    // a later version's, or an input method the app lacks (misspelt, a missing table)
    private fun selectorWarnings(el: XmlElement, p: Problems, selectors: List<Selector>) {
        selectors.mapNotNull { it.reserved }.distinct().forEach { p.warn(el.line, ignored(it)) }
        selectors.flatMap { it.scenes }.filter { it.startsWith("im=") && it.removePrefix("im=") !in Selector.IMS }.distinct()
            .forEach {
                if (it == "im=$PAD_BOARD") p.warn(el.line, "@$it selects nothing: handwriting is no input method now, it is written over the letters of the one in use")
                else p.warn(el.line, "@$it is no input method of this app: it selects nothing")
            }
    }

    private fun named(values: Map<String, Value>, a: Attributes, colors: Set<String>, fonts: Set<String>, icons: Set<String>) {
        for (v in values.values) {
            colorsOf(v).flatMap { it.refs() }.filter { it !in colors }.forEach { a.problem("@$it is no colour") }
        }
        for (font in FONT_PROPERTIES) {
            (values[font] as? Value.Word)?.word?.takeIf { it != "system" && it !in fonts }?.let { a.problem("$font=\"$it\" is no <font>") }
        }
        for ((prop, v) in values) {
            ((v as? Value.OfShown)?.shown as? Shown.Icon)?.name?.takeIf { it !in Icons.ALL && it !in icons }
                ?.let { a.problem("$prop=\"icon($it)\" is no icon of the app's nor an <icon> of the theme's") }
        }
    }

    private val FONT_PROPERTIES = listOf("font", "font-han", "font-latin")

    // a key's properties read from one part only: set on another part they do nothing, which a warning says
    private fun readElsewhere(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        for (prop in props) {
            val from = if (prop.startsWith(Values.RETURN_ICON)) "icon" else if (prop in LABEL_READ) "label" else continue
            selectors.mapNotNull { it.part }.filter { it != from }.distinct()
                .forEach { p.warn(line, "$prop= on a key's $it does nothing: it is read from its $from") }
        }
    }

    private val LABEL_READ = setOf("align", "space-content")

    // warned of, as one may think it does something
    private fun unreadByWindow(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        for ((w, states) in selectors.filter { it.widget in WINDOW_READS }.map { it.widget to it.states }.distinct()) {
            val on = w + states.sorted().joinToString("") { ":$it" }
            val reads = windowReads(w, states)
            val said = if (reads.isEmpty()) "it reads nothing in that state" else "it reads ${reads.joinToString(" ")}"
            props.filter { it !in reads }.forEach { p.warn(line, "$it= on $on does nothing: $said") }
        }
    }

    private fun windowReads(widget: String, states: Set<String>): List<String> = when {
        states.isEmpty() -> WINDOW_READS.getValue(widget)
        widget == "side-button" && states == setOf("pressed") -> listOf("overlay")
        else -> emptyList()
    }

    // each told once, by its own table's check
    private fun readByTable(widget: String) = widget in WINDOW_READS || widget in Selector.PANEL_WIDGETS || widget in Selector.BAR_WIDGETS

    private fun unread(selectors: List<Selector>, values: Map<String, Value>, line: Int, p: Problems) {
        unreadByWindow(selectors, values.keys, line, p)
        unreadByPanel(selectors, values.keys, line, p)
        unreadByBar(selectors, values, line, p)
        val fill = (values["fill"] as? Value.OfFill)?.fill
        // a panel's native view takes a colour; a picture or image fill is the scene's alone (PanelLooks.fill)
        if (fill != null && fill !is Fill.Solid) {
            selectors.map { it.widget }.filter { it in Selector.PANEL_WIDGETS }.distinct()
                .forEach { p.warn(line, "fill= on $it is no colour: a panel's fill is a colour alone, ignored") }
        }
        unreadPad(selectors, values.keys, line, p)
        unreadMotion(selectors, values.keys, line, p)
    }

    private fun unreadMotion(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        val moving = MOTION.filter { it in props }
        if (moving.isEmpty()) return
        val others = selectors.filter { !readByTable(it.widget) }
        for (w in others.map { it.widget }.filter { it != "key" }.distinct()) {
            moving.filter { it !in POPUP_MOTION[w].orEmpty() }.forEach {
                p.warn(line, when {
                    it == "appear" -> "appear= on $w does nothing: only a popup, a preview or a menu comes up"
                    it == "transition" && w == "menu" -> "transition= on menu does nothing: a menu goes at once"
                    else -> "$it= on $w does nothing: only a key moves"
                })
            }
        }
        for (part in others.filter { it.widget == "key" }.mapNotNull { it.part }.distinct()) {
            moving.filter { it != "appear" }.forEach { p.warn(line, "$it= on a key's $part does nothing: the key moves as a whole") }
        }
        // a popup comes and goes as a whole: its look in a state or a part's is not asked
        for (s in others.filter { it.widget in POPUP_MOTION && (it.states.isNotEmpty() || it.part != null) }) {
            moving.filter { it in POPUP_MOTION.getValue(s.widget) }.forEach { p.warn(line, "$it= on ${s.widget} in a state or a part does nothing: a ${s.widget} moves as a whole") }
        }
        if ("appear" in moving && others.any { it.widget == "key" }) p.warn(line, "appear= on key does nothing: only a popup, a preview or a menu comes up")
        // a style selects a pressed key too (`key`, `key:shifted`)
    }

    internal val POPUP_MOTION = mapOf("popup" to setOf("transition", "appear"), "preview" to setOf("transition", "appear"), "menu" to setOf("appear"))

    private val MOTION = listOf("transition", "press-scale", "press-sink", "effect", "appear")

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
                in PAD_PROPERTIES -> "key" to "the handwriting ink's (handwriting/pad)"
                else -> continue
            }
            selectors.map { it.widget }.filter { it != widget && !readByTable(it) }.distinct()
                .forEach { p.warn(line, "$prop= on $it does nothing: it is $whose") }
        }
    }

    // the handwriting keyboard is gone, so its rules select nothing but the pad's ink; its grid is not drawn
    private fun unreadPad(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        for (s in selectors.filter { it.board == PAD_BOARD || "keyboard=$PAD_BOARD" in it.scenes }) {
            val ink = s.board == PAD_BOARD && s.key == PAD_KEY && s.widget == "key" && s.scenes.none { it == "keyboard=$PAD_BOARD" }
            props.filter { !ink || it !in INK_PROPERTIES }
                .forEach { p.warn(line, "$it= on the handwriting keyboard does nothing: handwriting is written over the letters, only its pad's ink-color= and ink-width= are read") }
        }
        val pad = props.filter { it in PAD_PROPERTIES }
        if (pad.isEmpty()) return
        val named = selectors.filter { it.widget == "key" && it.board != PAD_BOARD && (it.board != null || it.starRole != null) }
            .map { s -> s.board?.let { "$it/${s.key ?: "*"}" } ?: "*/${s.starRole}" }.distinct()
        for (at in named) pad.forEach { p.warn(line, "$it= on $at does nothing: it is the handwriting ink's (handwriting/pad)") }
    }

    private fun panelReads(s: Selector.Target): List<String> {
        val selected = s.widget == "picker" && s.states == setOf("selected")
        return when {
            selected && s.part == null -> listOf("color")
            s.states.isEmpty() || selected -> PANEL_READS.getValue(s.part?.let { "${s.widget} $it" } ?: s.widget)
            else -> emptyList()
        }
    }

    private val PANEL_READS = mapOf(
        "clipboard" to listOf("fill", "radius", "color"),
        "picker" to listOf("fill", "color"),
        "picker categories" to listOf("fill", "color"),
        "editing" to listOf("fill", "radius"),
        "voice" to listOf("fill", "blur", "icon-color"),
        "voice mic" to listOf("fill", "icon-color"),
    )

    private val PAD_PROPERTIES = setOf("ink-color", "ink-width", "grid-color", "grid")
    private val INK_PROPERTIES = setOf("ink-color", "ink-width")
    private const val PAD_BOARD = "handwriting"
    private const val PAD_KEY = "pad"

    private val WINDOW_READS = mapOf(
        "frame" to listOf("radius", "border", "shadow"),
        HANDLE to listOf("fill", "color", "icon-color", "width", "height"),
        "side-button" to listOf("fill", "icon-color", "shape", "radius", "overlay"),
        "split-gap" to listOf("fill", "radius"),
        NAVBAR to listOf("color"),
    )

    // also a gradient or image bar base, which the bar's view does not draw (only a colour or the picture)
    private fun unreadByBar(selectors: List<Selector>, values: Map<String, Value>, line: Int, p: Problems) {
        val others = selectors.map { it.widget }.distinct()
            .filter { !readByTable(it) && it !in Selector.RESERVED_WIDGETS }
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

    // a candidate is one line: number, words and comment in one font
    private fun barReads(widget: String, states: Set<String>, part: String?): List<String> = when {
        widget == "toolbar" && states == setOf("pressed") -> listOf("overlay")
        widget == "toolbar" && states == setOf("selected") -> listOf("fill", "icon-color")
        widget != "candidate" -> if (states.isEmpty()) BAR_READS.getValue(widget) else emptyList()
        part != null -> if ("pressed" in states) emptyList() else listOf("color")
        states.isEmpty() -> BAR_READS.getValue(widget)
        // nothing presses a selected candidate (the window's, by keys)
        "pressed" in states && (states - "pressed" - "first").isEmpty() -> listOf("overlay")
        "pressed" !in states -> CANDIDATE_STATE_READS
        else -> emptyList()
    }

    private val BAR_READS = mapOf(
        "bar" to listOf("fill", "height"),
        "candidate" to listOf("fill", "radius", "inset", "padding", "gap", "color", "font", "weight", "label-style"),
        "candidates-divider" to listOf("fill"),
        "candidates-panel" to listOf("fill"),
        "preedit" to listOf("color", "underline", "cursor-color"),
        "toolbar" to listOf("fill", "radius", "icon-color"),
    )

    private val CANDIDATE_STATE_READS = listOf("fill", "color", "font", "weight")

    private val CANDIDATE_ONLY = listOf("gap", "label-style")

    private val PREEDIT_ONLY = listOf("underline", "cursor-color")

    private val UNPLACED = setOf("floating", "split", "one-handed")

    // the bar is one over every window form (never floating, split or one-handed): such a selector never matches
    private fun unplacedBar(selectors: List<Selector>, line: Int, p: Problems) =
        selectors.filter { it.widget in Selector.BAR_WIDGETS }.flatMap { s -> s.scenes.filter { it in UNPLACED }.map { "@$it ${s.widget}" } }.distinct()
            .forEach { p.warn(line, "$it never matches: the bar is the same over a floating, split or one-handed keyboard") }

    // a % size kept off the bubble would stand in for the sp size the widget would inherit
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
        val live = selectors.filter { it.widget !in Selector.RESERVED_WIDGETS }
        if (values["size"] !is Value.Percent || live.all { it.widget == "preview" && it.part == null }) return 0
        values.remove("size")
        p.warn(line, "size= in % is ignored but in a style of the preview alone (the key's bubble): elsewhere a size is a text's, in sp")
        return 1
    }

    private val FACED = setOf("key", "popup", "preview", "menu")

    private val FOLLOW = Value.Word(Values.FOLLOW)
    private const val NAVBAR = "navbar"
    private const val HANDLE = "handle"

    // a theme's keyboard has one size: its scenes and states cannot change it
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
                "height" -> "20%–90%, portrait then landscape"
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

    private fun reserved(el: XmlElement, a: Attributes, p: Problems, colors: Set<String>, skip: List<String>): Int {
        val given = Values.RESERVED.filterKeys { el[it] != null && it !in skip }
        for ((prop, form) in given) {
            val refs = a.value(prop, "a value of $prop") { form.read(it) }?.flatMap { it.refs() } ?: continue
            refs.filter { it !in colors }.forEach { a.problem("@$it is no colour") }
            p.warn(el.line, ignored("$prop="))
        }
        return given.size
    }

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
