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
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.json.EnvJson
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.SystemColors
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
