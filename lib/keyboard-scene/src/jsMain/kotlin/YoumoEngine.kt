/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// The theme maker's library (060-render-p3 W1): the engine for a page, its names at the module's top
// (`globalThis['youmo-engine'].YoumoEngine` in a page, `require(…).YoumoEngine` in node), not under the
// engine's packages.
@file:OptIn(ExperimentalJsExport::class)

import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problem
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.json.EnvJson
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.Values
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import org.fcitx.fcitx5.android.scene.web.BuiltInFiles
import org.fcitx.fcitx5.android.scene.web.BuiltIns
import org.fcitx.fcitx5.android.scene.web.CanvasMeasurer
import org.fcitx.fcitx5.android.scene.web.CanvasPainter
import org.fcitx.fcitx5.android.scene.web.PaintAssets
import org.fcitx.fcitx5.android.scene.web.SceneJs
import org.fcitx.fcitx5.android.scene.web.SceneJs.obj
import org.fcitx.fcitx5.android.scene.web.SceneJs.problems

/** A theme read ([YoumoEngine.readTheme]): what [YoumoEngine.buildScene] draws with, its dark or light version as the environment is. */
@JsExport
class YoumoTheme internal constructor(internal val theme: Theme) {
    private val cascades = HashMap<Boolean, Cascades>()

    internal fun cascades(dark: Boolean): Cascades = theme.variant(dark).let { t -> cascades.getOrPut(t.dark) { Cascades(t) } }

    val name: String get() = theme.name

    /** Its versions: `["light"]`, `["dark"]` or both. */
    val variants: Array<String> get() = listOf(false, true).filter { it in theme.variants }.map { if (it) "dark" else "light" }.toTypedArray()

    /**
     * What a page shows of it besides its look: its colours by name (`#aarrggbb`, the version [dark] asks; its
     * only one where it has one), fonts and icons by name (their files), its picture, its meta.
     */
    fun info(dark: Boolean): dynamic {
        val t = theme.variant(dark)
        return obj(
            "name" to t.name,
            "dark" to t.dark,
            "variants" to variants,
            "colors" to SceneJs.map(t.colors.mapValues { it.value.toString() }),
            "fonts" to SceneJs.map(t.fonts),
            "icons" to SceneJs.map(t.icons),
            "picture" to t.picture?.let { p -> obj("src" to p.src, "brightness" to p.brightness, "blurDp" to p.blurDp, "cuts" to SceneJs.map(p.cuts)) },
            "meta" to t.meta?.let { m -> obj("author" to m.author, "version" to m.version, "license" to m.license, "minApp" to m.minApp, "preview" to m.preview) },
        )
    }
}

/** A board's layout read ([YoumoEngine.readLayout], or a built-in's), the fragments it includes with it. */
@JsExport
class YoumoLayout internal constructor(internal val layout: Layout, internal val fragments: Map<String, Fragment>) {
    val id: String get() = layout.id
    val name: String get() = layout.name
    val family: String get() = layout.family
}

/** What measures text for a scene: the browser's canvas ([YoumoEngine.canvasMeasurer]) or the engine's fixed font ([YoumoEngine.fixedMeasurer]). */
@JsExport
class YoumoMeasurer internal constructor(internal val measurer: TextMeasurer)

/** A scene built ([YoumoEngine.buildScene], [YoumoEngine.buildPopup]): what [YoumoEngine.paint] draws. */
@JsExport
class YoumoScene internal constructor(internal val scene: Scene, internal val density: Double) {
    val width: Double get() = scene.width
    val height: Double get() = scene.height

    /** The scene as a page reads it (SceneJs): keys, slots, sizes. */
    fun json(): dynamic = SceneJs.scene(scene)

    /** The scene as the engine's snapshots write it (SceneText): what the library's tests hold to the JVM's. */
    fun text(): String = SceneText.of(scene)

    /** The index in json().keys of the key a finger at ([x], [y]) presses; -1 in a gap. */
    fun keyAt(x: Double, y: Double): Int = scene.keyAt(x, y)?.let { scene.keys.indexOf(it) } ?: -1
}

/**
 * The engine for the theme maker's page: read a theme (4.0) and a layout, build a scene in an environment,
 * paint it on a canvas; a theme 3.0 made 4.0, a theme written; the app's layouts and themes. Every answer
 * that may fail is `{ok, problems: [{line, message}], warnings, …}`, never a throw: a user's file is data, and
 * so is what a page passes (a value of the wrong type is a problem, line 0).
 */
@JsExport
object YoumoEngine {
    private val builtIns = BuiltIns(BuiltInFiles.layouts, BuiltInFiles.themes)

    /**
     * [xml] (theme 4) read, `system()` colours from [systemColors] (`{light: {primary: "#…"}, dark: {…}}`; none:
     * every fallback, as on an older Android), refused where it needs an app newer than [appVersion] (none: never):
     * `{ok, problems, warnings, theme}`.
     */
    fun readTheme(xml: dynamic, systemColors: dynamic, appVersion: dynamic): dynamic = checked {
        val colors = systemColors(systemColors)
        result(ThemeReader.theme(text(xml, "theme"), colors, optionalText(appVersion, "appVersion"))) { "theme" to YoumoTheme(it) }
    }

    /** [xml] (layout 1) read, its includes from [fragments] (`{id: xml}`, a theme pack's) or the built-in ones: `{ok, problems, warnings, layout}`. */
    fun readLayout(xml: dynamic, fragments: dynamic): dynamic = checked {
        val own = HashMap<String, Fragment>()
        val bad = ArrayList<Problem>()
        if (fragments != null) {
            for (id in keys(fragments, "fragments")) {
                when (val f = LayoutReader.fragment(text(fragments[id], "fragment $id"))) {
                    is Parsed.Ok -> own[id] = f.value
                    is Parsed.Failed -> bad += f.problems.map { Problem(it.line, "$id: ${it.message}") }
                }
            }
        }
        if (bad.isNotEmpty()) return@checked failed(bad)
        val layout = LayoutReader.layout(text(xml, "layout"))
        if (layout !is Parsed.Ok) return@checked result(layout) { "layout" to null }
        result(builtIns.includes(layout.value, own)) { "layout" to YoumoLayout(layout.value, it) }
    }

    /**
     * Where [theme] leaves Shift, Backspace, Space or Return blank (BlankKeys, the app's own import check): on the
     * app's boards, each replaced by one of [layouts] (`{"<id>.xml": xml}`, a pack's `layouts/`) of its id. The first
     * found, as text (`qwerty/enter @flat`), none: an empty array; too many scenes to try: `["*"]`. A layout of [layouts] that does not read is left
     * out (readLayout says why).
     */
    fun blankKeys(theme: YoumoTheme, layouts: dynamic): Array<String> = run {
        val own = HashMap<String, String>()
        if (layouts != null) for (name in keys(layouts, "layouts")) own[name.removeSuffix(".xml")] = text(layouts[name], "layout $name")
        val fragments = own.mapNotNull { (id, t) -> LayoutReader.fragment(t).orNull()?.let { id to it } }.toMap()
        val ownBoards = own.values.mapNotNull { LayoutReader.layout(it).orNull() }
        val boards = builtIns.layouts.filter { id -> ownBoards.none { it.id == id } }.mapNotNull { builtIns.layout(it)?.orNull() } + ownBoards
        val withFragments = boards.mapNotNull { l -> builtIns.includes(l, fragments).orNull()?.let { BlankKeys.Board(l, it) } }
        // too many to try: its code, "*", the page says what it means
        BlankKeys.find(theme.theme, withFragments).map { if (it == BlankKeys.TOO_MANY) "*" else it.toString() }.toTypedArray()
    }

    /** The app's boards' ids (`qwerty`, `nine`…). */
    fun layouts(): Array<String> = builtIns.layouts.toTypedArray()

    /** The board [id] of the app's, its includes with it; null for none. */
    fun layout(id: String): YoumoLayout? = builtIns.layout(id)?.orNull()?.let { l -> builtIns.includes(l).orNull()?.let { YoumoLayout(l, it) } }

    /** A built-in layout file's text (a board's or a fragment's), null for none: what the page shows and saves. */
    fun layoutSource(id: String): String? = builtIns.layoutText(id)

    /** The app's themes' names (060-render-p3 T3's files; none before them). */
    fun themes(): Array<String> = builtIns.themes.toTypedArray()

    /** A built-in theme's file, null for none. */
    fun themeSource(id: String): String? = builtIns.themeText(id)

    /**
     * The browser's measurer: [ctx] a 2D context to measure with, [fonts] (`{name: "CSS family"}`) as [paint] is
     * given them: `{ok, problems, measurer}`. A text measured before its font is in is the fallback font's, and not
     * kept: await `document.fonts.ready` (or load the faces) before building a scene with it.
     */
    fun canvasMeasurer(ctx: dynamic, fonts: dynamic): dynamic = checked {
        if (ctx == null || jsTypeOf(ctx.measureText) != "function") throw NotOfType("ctx: not a 2D context")
        val families = families(fonts)
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>(), "measurer" to YoumoMeasurer(CanvasMeasurer(ctx, families)))
    }

    /** The engine's font of fixed sizes (the snapshots'): for node, and for a scene the same everywhere. */
    fun fixedMeasurer(): YoumoMeasurer = YoumoMeasurer(FixedMeasurer)

    /**
     * [layout] (a [YoumoLayout] or a built-in's id) drawn by [theme] in [env] (EnvJson: an object or its text),
     * its text measured by [measurer] (none: a canvas of the page's, the fixed font where there is no page):
     * `{ok, problems, warnings, scene}`. The theme's dark version where [env] is dark and it has one.
     */
    fun buildScene(layout: dynamic, theme: dynamic, env: dynamic, measurer: dynamic): dynamic = checked {
        val l: YoumoLayout = if (jsTypeOf(layout) == "string") {
            YoumoEngine.layout(layout as String) ?: return@checked failed(listOf(Problem(0, "no built-in layout \"$layout\"")))
        } else {
            layout as? YoumoLayout ?: throw NotOfType("layout: not a layout")
        }
        val e = when (val read = env(env)) {
            is Parsed.Failed -> return@checked failed(read.problems)
            is Parsed.Ok -> read.value
        }
        val built = builder(theme, e, measurer).build(l.layout, l.fragments)
        result(built) { "scene" to YoumoScene(it, e.density) }
    }

    /**
     * Where a widget of [layout] (as [buildScene] takes it) gets each property from, drawn by [theme] in [env]:
     * [target] `{widget: "key", key, part?, states?}`, [part] `label`, `mark`… (none: the key's face), [states]
     * theme.xml's (`pressed`, `locked`…) besides the ones the key is in. `{ok, problems, sources: [{property,
     * line, order}]}`, the line and order (the n-th `<style>`, from 0) of the style whose value wins, the
     * properties in name order; none where the key is not on the board there (060-render-p3 §5.1: the cascade
     * check). Only keys so far: another widget is a problem.
     */
    fun sources(layout: dynamic, theme: dynamic, env: dynamic, target: dynamic): dynamic = checked {
        val widget = optionalText(member(target, "widget"), "widget") ?: "key"
        if (widget != "key") return@checked failed(listOf(Problem(0, "sources of a $widget: only keys so far")))
        val l: YoumoLayout = if (jsTypeOf(layout) == "string") {
            YoumoEngine.layout(layout as String) ?: return@checked failed(listOf(Problem(0, "no built-in layout \"$layout\"")))
        } else {
            layout as? YoumoLayout ?: throw NotOfType("layout: not a layout")
        }
        val e = when (val read = env(env)) {
            is Parsed.Failed -> return@checked failed(read.problems)
            is Parsed.Ok -> read.value
        }
        val given: dynamic = member(target, "states")
        val states = if (given == null) emptySet() else array(given, "states").mapTo(HashSet()) { text(it, "a state") }
        val key = text(member(target, "key"), "key")
        val found = builder(theme, e, null).sources(l.layout, l.fragments, key, optionalText(member(target, "part"), "part"), states)
        obj(
            "ok" to true,
            "problems" to emptyArray<dynamic>(),
            "warnings" to emptyArray<dynamic>(),
            "sources" to found.entries.sortedBy { it.key }.map { (p, s) -> obj("property" to p, "line" to s.line, "order" to s.order) }.toTypedArray(),
        )
    }

    /**
     * [source] as a theme's `<icon>` (an SVG of filled paths: [SvgSubset], what the app imports): `{ok, problems}`
     * where it is not one (each `{line, message, level}`), else `{ok, icon: {vw, vh, paths: [{d, rule, alpha}]}}`,
     * as `paint`'s `icons` takes an icon (060-render-p3 §5.1 panel 4).
     */
    fun readIcon(source: dynamic): dynamic = checked {
        result(SvgSubset.read(text(source, "source"))) { icon ->
            "icon" to obj(
                "vw" to icon.width,
                "vh" to icon.height,
                "paths" to icon.paths.map { p -> obj("d" to p.d, "rule" to if (p.evenOdd) "evenodd" else "nonzero", "alpha" to p.alpha) }.toTypedArray(),
            )
        }
    }

    /**
     * The bounds the engine reads a key's and its text's values in (Values: what a form offers, 060-render-p3 M1):
     * `{lengthDp, offsetDp, borderDp, blurDp, depthDp, scale: [min, max], spacingEm: [min, max], stops: [min, max]}`.
     */
    fun limits(): dynamic = obj(
        "lengthDp" to Values.MAX_LENGTH_DP,
        "offsetDp" to Values.MAX_OFFSET_DP,
        "borderDp" to Values.MAX_BORDER_DP,
        "blurDp" to Values.MAX_BLUR_DP,
        "depthDp" to Values.MAX_DEPTH_DP,
        "scale" to arrayOf(Values.MIN_SCALE, Values.MAX_SCALE),
        "spacingEm" to arrayOf(Values.MIN_SPACING_EM, Values.MAX_SPACING_EM),
        "stops" to arrayOf(Values.MIN_STOPS, Values.MAX_STOPS),
    )

    /**
     * The candidate bar of [BarSample]'s candidates as [theme] has it in [env] (its width, its density, its
     * scenes; the height the app's of 候选字号 [BAR_DP] times the theme's `bar height=`), measured by
     * [measurer]: `{ok, problems, scene}` (060-render-p3 §5.1: the preview's 候选栏样例). The app draws its bar with
     * views; this is what BarThemeTest holds them to.
     */
    fun buildBar(theme: dynamic, env: dynamic, measurer: dynamic): dynamic = checked {
        val e = when (val read = env(env)) {
            is Parsed.Failed -> return@checked failed(read.problems)
            is Parsed.Ok -> read.value
        }
        val t = theme as? YoumoTheme ?: throw NotOfType("theme: not a theme")
        val m = if (measurer == null) defaultMeasurer else (measurer as? YoumoMeasurer ?: throw NotOfType("measurer: not a measurer")).measurer
        val look = BarLook.of(t.cascades(e.dark), e.scenes)
        val metrics = BarSample.Metrics(
            width = e.width, height = (BAR_DP * look.heightPercent / FULL * e.density).toInt().toDouble(), density = e.density,
            textSize = BAR_TEXT_SP * e.density, paddingDp = BAR_PADDING_DP, minWidthDp = BAR_MIN_DP,
        )
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>(), "scene" to YoumoScene(BarSample.scene(look, metrics, m), e.density))
    }

    /**
     * What the app's own views take of [theme] in [env], colours `#aarrggbb` (null: none), lengths dp: the code
     * being typed's and the toolbar's ([BarLook]), the window's ([WindowLooks]) and the panels' ([PanelLooks]).
     * The preview's 面板样例 draws its blocks of it (060-render-p3 §5.1); `{ok, problems, looks}`.
     */
    fun looks(theme: dynamic, env: dynamic): dynamic = checked {
        val e = when (val read = env(env)) {
            is Parsed.Failed -> return@checked failed(read.problems)
            is Parsed.Ok -> read.value
        }
        val t = theme as? YoumoTheme ?: throw NotOfType("theme: not a theme")
        val c = t.cascades(e.dark)
        val s = e.scenes
        val bar = BarLook.of(c, s)
        val w = WindowLooks(c)
        val p = PanelLooks(c)
        fun col(a: Argb?): String? = a?.toString()
        val frame = w.frame(s)
        val handle = w.handle(s, top = true)
        val side = w.sideButton(s)
        val clip = p.clipboard(s)
        val picker = p.picker(s, side = false)
        val editing = p.editing(s)
        val voice = p.voice(s)
        val base = when (val b = bar.base) {
            is BarLook.Base.Solid -> b.color
            is BarLook.Base.Picture -> b.fallback
            null -> null
        }
        val looks = obj(
            "bar" to col(base),
            "preedit" to obj("color" to col(bar.preedit.color), "cursor" to col(bar.preedit.cursor),
                "underline" to bar.preedit.underline?.let { obj("widthDp" to it.widthDp, "color" to col(it.color)) }),
            "toolbar" to obj("fill" to col(bar.toolbar.fill), "radiusDp" to bar.toolbar.radius?.corners?.first(), "icon" to col(bar.toolbar.icon),
                "onFill" to col(bar.toolbar.onFill), "onIcon" to col(bar.toolbar.onIcon)),
            "panel" to col(bar.panel),
            "frame" to obj("radiusDp" to frame.radiusDp, "border" to frame.border?.let { obj("widthDp" to it.widthDp, "color" to col(it.color)) },
                "elevationDp" to frame.elevationDp, "shadow" to col(frame.shadowColor)),
            "handle" to obj("fill" to col(handle.fill), "color" to col(handle.color), "widthDp" to handle.widthDp, "heightDp" to handle.heightDp),
            "sideButton" to obj("fill" to col(side.fill), "icon" to col(side.iconColor), "circle" to side.circle, "radiusDp" to side.radiusDp),
            "navbar" to col(w.navbar(s)),
            "clipboard" to obj("fill" to col(clip.fill), "radiusDp" to clip.radiusDp, "color" to col(clip.color)),
            "picker" to obj("fill" to col(picker.fill), "categories" to col(picker.categories.fill), "color" to col(picker.categories.color),
                "selected" to col(picker.selected.fill), "selectedColor" to col(picker.selected.color)),
            "editing" to obj("fill" to col(editing.fill), "radiusDp" to (editing.radiusDp ?: editing.keyRadiusDp)),
            "voice" to obj("fill" to col(voice.fill), "blurDp" to voice.blurDp, "mic" to col(voice.micFill), "micColor" to col(voice.micColor)),
        )
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>(), "looks" to looks)
    }

    /**
     * A popup drawn by [theme] in [env]: [request] `{kind: "preview", text, keyWidth, size?: px | "words"}` (a
     * key's bubble), `{kind: "keys", labels, rows, columns, room?, selected}` (a long press's keys, [rows] of
     * [labels]' indices, the bottom one first, -1 a gap), `{kind: "menu", cells: [{label, icon?}], order,
     * selected, disabled?}`: `{ok, problems, scene}`.
     */
    fun buildPopup(theme: dynamic, env: dynamic, request: dynamic, measurer: dynamic): dynamic = checked {
        val e = when (val read = env(env)) {
            is Parsed.Failed -> return@checked failed(read.problems)
            is Parsed.Ok -> read.value
        }
        val b = builder(theme, e, measurer)
        if (request == null || jsTypeOf(request) != "object") throw NotOfType("request: not an object")
        val kind = if (jsTypeOf(request.kind) == "string") request.kind as String else null
        val built: Parsed<Scene> = when (kind) {
            "preview" -> previewPopup(b, request)
            "keys" -> keysPopup(b, request)
            "menu" -> menuPopup(b, request)
            else -> Parsed.Failed(0, "no popup of kind \"${request.kind}\"")
        }
        result(built) { "scene" to YoumoScene(it, e.density) }
    }

    /**
     * [scene] drawn on [ctx] (a CanvasRenderingContext2D of a canvas, its transform the page's: a device's pixel
     * ratio), as the app draws it: the base, the slots' faces, every key, those in [state]'s `pressed` (an id, or a
     * list) pressed. [state]'s `fonts` (`{name: "CSS family"}`), `images` (`{file: {image, width, height}}`, a
     * theme's images by name), `picture` (`(blurDp, brightness) => image or null`, the theme's picture the scene's
     * size; a null argument the theme's own), `icons` (`{name: {vw, vh, paths}}`, the app's: youmo-engine-icons).
     * `{ok, problems}`: nothing drawn where [state] is not that.
     */
    fun paint(scene: dynamic, ctx: dynamic, state: dynamic): dynamic = checked {
        val drawn = scene as? YoumoScene ?: throw NotOfType("scene: not a scene")
        val s = drawn.scene
        if (ctx == null || jsTypeOf(ctx.fill) != "function") throw NotOfType("ctx: not a 2D context")
        val pressed: Set<String> = when (val p = member(state, "pressed")) {
            null -> emptySet()
            else -> if (jsTypeOf(p) == "string") setOf(p as String) else array(p, "pressed").mapIndexed { i, v -> text(v, "pressed[$i]") }.toSet()
        }
        val fonts = families(member(state, "fonts"))
        for ((name, type) in listOf("images" to "object", "icons" to "object", "picture" to "function")) {
            val v = member(state, name)
            if (v != null && jsTypeOf(v) != type) throw NotOfType("$name: not ${if (type == "object") "an object" else "a function"}")
        }
        val painter = CanvasPainter(ctx, assets(state, fonts), drawn.density, s.width, s.height)
        painter.draw(s.base)
        s.slots.forEach { painter.draw(it.ops) }
        s.keys.forEach { k: KeyNode -> painter.draw(k.ops(k.id in pressed)) }
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>())
    }

    /**
     * A theme file 3.0 ([json], the app's export and the old maker's) as theme 4, as the app converts it:
     * `{ok, problems, xml, name, dark, files: [[path in the 3.0 file, name in the 4.0]]}`.
     */
    fun convert30(json: dynamic): dynamic = checked {
        when (val read = ThemeLookReader.read(text(json, "json"))) {
            is Parsed.Failed -> failed(read.problems)
            is Parsed.Ok -> {
                val f = read.value
                val name = LookTheme.themeName(f.name)
                obj(
                    "ok" to true,
                    "problems" to emptyArray<dynamic>(),
                    "warnings" to emptyArray<dynamic>(),
                    "xml" to LookTheme.xml(f.look, name, f.dark),
                    "name" to name,
                    "dark" to f.dark,
                    "files" to LookTheme.fileNames(f.look).entries.map { arrayOf(it.key, it.value) }.toTypedArray(),
                )
            }
        }
    }

    /** [theme] written as theme 4 (ThemeWriter: what the app saves, its styles canonical, comments gone). */
    fun writeTheme(theme: YoumoTheme): String = ThemeWriter.write(theme.theme)

    // ------------------------------------------------------------------ inside

    // a value of a page's not of the type asked for: a problem of the answer's, never out of the facade
    private class NotOfType(message: String) : Exception(message)

    // the bar's sample as the app's of the default 候选字号 (SceneBar): 40dp high, 18sp text, 9dp each side, 32dp at least
    private const val BAR_DP = 40.0
    private const val BAR_TEXT_SP = 18.0
    private const val BAR_PADDING_DP = 9.0
    private const val BAR_MIN_DP = 32.0
    private const val FULL = 100.0

    private inline fun checked(answer: () -> dynamic): dynamic = try {
        answer()
    } catch (e: NotOfType) {
        failed(listOf(Problem(0, e.message.orEmpty())))
    }

    private fun env(v: dynamic): Parsed<Env> {
        val text = when (jsTypeOf(v)) {
            "string" -> v as String
            "object" -> if (v == null) throw NotOfType("env: none") else JSON.stringify(v)
            else -> throw NotOfType("env: not an object or its text")
        }
        return when (val e = EnvJson.read(text)) {
            is Parsed.Ok -> e
            is Parsed.Failed -> Parsed.Failed(e.problems.map { Problem(it.line, "environment: ${it.message}") })
        }
    }

    private fun builder(theme: dynamic, env: Env, measurer: dynamic): SceneBuilder {
        val t = theme as? YoumoTheme ?: throw NotOfType("theme: not a theme")
        val m = if (measurer == null) defaultMeasurer else (measurer as? YoumoMeasurer ?: throw NotOfType("measurer: not a measurer")).measurer
        val cascades = t.cascades(env.dark)
        return SceneBuilder(cascades.theme, env, m, cascades = cascades)
    }

    private fun previewPopup(b: SceneBuilder, r: dynamic): Parsed<Scene> {
        val size: Double? = when {
            r.size == null -> null
            r.size == "words" -> b.previewWordsSize().orNull()?.first
            else -> number(r.size, "size")
        }
        return b.preview(text(r.text, "text"), number(r.keyWidth, "keyWidth"), size)
    }

    private fun keysPopup(b: SceneBuilder, r: dynamic): Parsed<Scene> {
        val labels = array(r.labels, "labels").mapIndexed { i, v -> text(v, "labels[$i]") }
        val rows = array(r.rows, "rows").mapIndexed { i, row -> array(row, "rows[$i]").mapIndexed { j, v -> whole(v, "rows[$i][$j]") } }
        val room = if (r.room == null) null else number(r.room, "room")
        return when (val keys = b.popupKeys(labels, whole(r.columns, "columns"), room)) {
            is Parsed.Ok -> b.popup(labels, rows, keys.value, whole(r.selected, "selected"))
            is Parsed.Failed -> keys
        }
    }

    private fun menuPopup(b: SceneBuilder, r: dynamic): Parsed<Scene> {
        val cells = array(r.cells, "cells").mapIndexed { i, c ->
            if (c == null || jsTypeOf(c) != "object") throw NotOfType("cells[$i]: not an object")
            MenuCell(text(c.label, "cells[$i].label"), optionalText(c.icon, "cells[$i].icon"))
        }
        val order = array(r.order, "order").mapIndexed { i, v -> whole(v, "order[$i]") }
        val disabled = if (r.disabled == null) emptySet() else array(r.disabled, "disabled").mapIndexed { i, v -> whole(v, "disabled[$i]") }.toSet()
        return b.menu(cells, order, whole(r.selected, "selected"), disabled)
    }

    // a canvas of the page's where there is a page (a document), the fixed font in node; it keeps no measure
    // taken before its font is in (CanvasMeasurer), so one made before the fonts are is not stuck with theirs
    private val defaultMeasurer: TextMeasurer by lazy {
        val doc: dynamic = js("typeof document === 'undefined' ? null : document")
        if (doc == null) FixedMeasurer else CanvasMeasurer(doc.createElement("canvas").getContext("2d"), families(null))
    }

    private fun systemColors(v: dynamic): SystemColors {
        if (v == null) return SystemColors.NONE
        if (jsTypeOf(v) != "object") throw NotOfType("systemColors: not an object")
        fun side(o: dynamic, which: String): Map<String, Argb> = if (o == null) {
            emptyMap()
        } else {
            keys(o, "systemColors.$which").associateWith { name ->
                val c = ColorExpr.parse(text(o[name], "system colour $name"))
                if (c !is ColorExpr.Literal) throw NotOfType("system colour $name: \"${o[name]}\" is not #rrggbb or #aarrggbb")
                c.argb
            }
        }
        return SystemColors(side(v.light, "light"), side(v.dark, "dark"))
    }

    private fun families(fonts: dynamic): (String) -> String {
        val own = if (fonts == null) emptyMap() else keys(fonts, "fonts").associateWith { text(fonts[it], "fonts.$it") }
        return { name -> own[name]?.let { "$it, $DEFAULT_FAMILY" } ?: DEFAULT_FAMILY }
    }

    private fun assets(state: dynamic, fonts: (String) -> String): PaintAssets = object : PaintAssets {
        override fun family(font: String): String = fonts(font)

        override fun image(file: String): dynamic = member(member(state, "images"), file)

        override fun picture(blurDp: Int?, brightness: Int?): dynamic {
            val make = member(state, "picture") ?: return null
            return make(blurDp, brightness)
        }

        override fun icon(name: String): dynamic = member(member(state, "icons"), name)
    }

    // [o]'s member [name]; null where [o] or it is none (null or undefined)
    private fun member(o: dynamic, name: String): dynamic {
        if (o == null || o == undefined) return null
        val v = o[name]
        return if (v == null || v == undefined) null else v
    }

    private fun keys(o: dynamic, what: String): List<String> {
        if (jsTypeOf(o) != "object") throw NotOfType("$what: not an object")
        val k: Array<String> = js("Object.keys")(o)
        return k.toList()
    }

    private fun text(v: dynamic, what: String): String = if (jsTypeOf(v) == "string") v as String else throw NotOfType("$what: not a string")

    private fun optionalText(v: dynamic, what: String): String? = if (v == null) null else text(v, what)

    private fun number(v: dynamic, what: String): Double {
        val d = if (jsTypeOf(v) == "number") (v as Number).toDouble() else Double.NaN
        return if (d.isFinite()) d else throw NotOfType("$what: not a number")
    }

    private fun whole(v: dynamic, what: String): Int {
        val d = number(v, what)
        return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) <= Int.MAX_VALUE) d.toInt() else throw NotOfType("$what: not a whole number")
    }

    private fun array(v: dynamic, what: String): List<dynamic> {
        val isArray: dynamic = js("Array.isArray")
        if (!(isArray(v) as Boolean)) throw NotOfType("$what: not a list")
        return (v as Array<dynamic>).toList()
    }

    private fun failed(problems: List<Problem>): dynamic =
        obj("ok" to false, "problems" to problems(problems), "warnings" to emptyArray<dynamic>())

    private fun <T> result(parsed: Parsed<T>, value: (T) -> Pair<String, Any?>): dynamic = when (parsed) {
        is Parsed.Failed -> failed(parsed.problems)
        is Parsed.Ok -> obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to problems(parsed.warnings), value(parsed.value))
    }

    /** The families every scene's text falls back to: the page's two fonts (Roboto's and Noto Sans SC's subsets), then the browser's. */
    const val DEFAULT_FAMILY = "\"Youmo Latin\", \"Youmo Han\", sans-serif"
}
