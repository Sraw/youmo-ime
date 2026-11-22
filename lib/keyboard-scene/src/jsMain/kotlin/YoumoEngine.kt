/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// The theme maker's library: `globalThis['youmo-engine'].YoumoEngine` in a page, `require(…).YoumoEngine` in node.
@file:OptIn(ExperimentalJsExport::class)

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problem
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.Paint
import org.fcitx.fcitx5.android.scene.engine.PanelLooks
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.json.EnvJson
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.KeyData
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.LayoutWriter
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.PixelsImage
import org.fcitx.fcitx5.android.scene.skin.SkinConverter
import org.fcitx.fcitx5.android.scene.skin.SkinError
import org.fcitx.fcitx5.android.scene.skin.SkinImage
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import org.fcitx.fcitx5.android.scene.skin.SkinOutput
import org.fcitx.fcitx5.android.scene.skin.SkinPlatform
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.IconPath
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.theme.SoundFile
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.Values
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeFeatures
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
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.Uint8ClampedArray

@JsExport
class YoumoTheme internal constructor(internal val theme: Theme) {
    private val cascades = HashMap<Boolean, Cascades>()

    internal fun cascades(dark: Boolean): Cascades = theme.variant(dark).let { t -> cascades.getOrPut(t.dark) { Cascades(t) } }

    val name: String get() = theme.name

    val variants: Array<String> get() = listOf(false, true).filter { it in theme.variants }.map { if (it) "dark" else "light" }.toTypedArray()

    /** Colours by name (`#aarrggbb`, the [dark] version where it has one), fonts and icons by name, sounds, picture, meta. */
    fun info(dark: Boolean): dynamic {
        val t = theme.variant(dark)
        return obj(
            "name" to t.name,
            "dark" to t.dark,
            "variants" to variants,
            "colors" to SceneJs.map(t.colors.mapValues { it.value.toString() }),
            "fonts" to SceneJs.map(t.fonts),
            "icons" to SceneJs.map(t.icons),
            // as written, a later version's class too: the maker keeps and saves them all
            "sounds" to t.soundFiles.map { (k, f) -> obj("key" to k, "src" to f) }.toTypedArray(),
            // the dark version's own where it has one: what the preview and 从背景图取色 take
            "picture" to t.shownPicture?.let { p ->
                obj("src" to p.src, "brightness" to p.brightness, "blurDp" to p.blurDp, "cuts" to SceneJs.map(p.cuts), "animated" to p.animated)
            },
            "meta" to t.meta?.let { m -> obj("author" to m.author, "version" to m.version, "license" to m.license, "minApp" to m.minApp, "preview" to m.preview) },
            // the oldest app that reads and draws all of it, both versions alike (a pack's layouts are the page's to add)
            "minApp" to if (ThemeFeatures.since063(theme)) ThemeFeatures.MIN_APP_063 else LayoutCheck.BASE_VERSION,
        )
    }
}

@JsExport
class YoumoLayout internal constructor(
    internal val layout: Layout,
    internal val fragments: Map<String, Fragment>,
    // a pack's file by its own id (PackLayouts.judge), a user's own layout by its `based-on=`
    internal val variantOf: String = layout.id,
    internal val own: Boolean = false,
    // before KeyData laid the app keys' data on it, so a saved file holds none of the app's long presses and swipes
    internal val written: Layout = layout,
    // its own family's board (LayoutCheck.baseId), while its styles and shortcuts are [variantOf]'s
    internal val shapeOf: String = variantOf,
) {
    val id: String get() = layout.id
    val name: String get() = layout.name
    val family: String get() = layout.family
}

@JsExport
class YoumoMeasurer internal constructor(internal val measurer: TextMeasurer)

@JsExport
class YoumoScene internal constructor(internal val scene: Scene, internal val density: Double) {
    val width: Double get() = scene.width
    val height: Double get() = scene.height

    fun json(): dynamic = SceneJs.scene(scene)

    fun text(): String = SceneText.of(scene)

    fun keyAt(x: Double, y: Double): Int = scene.keyAt(x, y)?.let { scene.keys.indexOf(it) } ?: -1
}

/**
 * The engine for the theme maker's page. Fallible answers are `{ok, problems: [{line, message}], warnings, …}`, never
 * a throw: a user's file is data. The plain ones throw only on a wrongly typed engine object, the page's fault.
 */
@JsExport
object YoumoEngine {
    private val builtIns = BuiltIns(BuiltInFiles.layouts, BuiltInFiles.themes)

    /** `system()` colours from [systemColors] (`{light: {primary: "#…"}, dark: {…}}`): `{ok, problems, warnings, theme}`. */
    fun readTheme(xml: dynamic, systemColors: dynamic, appVersion: dynamic): dynamic = checked {
        val colors = systemColors(systemColors)
        result(ThemeReader.theme(text(xml, "theme"), colors, optionalText(appVersion, "appVersion"), LookTheme.MAX_STYLES)) { "theme" to YoumoTheme(it) }
    }

    /** Read as a pack's file for app board [board], its keys' held and swiped actions are the app's; `ignored` lists `[{file, line, what}]`. */
    fun readLayout(xml: dynamic, fragments: dynamic, board: dynamic = null): dynamic = checked {
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
        val includes = builtIns.includes(layout.value, own)
        val held = optionalText(board, "board")?.let { id -> includes.orNull()?.let { KeyData.of(id, layout.value, it, KeyData.Mode.Pack, basedOn = null) } }
        val answer = result(includes) { "layout" to (held?.let { h -> YoumoLayout(h.layout, h.fragments, written = layout.value) } ?: YoumoLayout(layout.value, it)) }
        answer.ignored = held?.ignored.orEmpty().map { obj("file" to it.file, "line" to it.line, "what" to it.what) }.toTypedArray()
        answer
    }

    /** As the app reads a user's own `.layout` ([KeyData.Mode.Own], by `based-on=`); answers as [readLayout]. */
    fun readOwnLayout(xml: dynamic): dynamic = checked {
        val layout = LayoutReader.layout(text(xml, "layout"))
        if (layout !is Parsed.Ok) return@checked result(layout) { "layout" to null }
        val includes = builtIns.includes(layout.value)
        val basedOn = layout.value.basedOn ?: layout.value.id
        val shape = LayoutCheck.baseId(layout.value, checkSources) ?: basedOn
        val held = includes.orNull()?.let { KeyData.of(layout.value.id, layout.value, it, KeyData.Mode.Own, shape = shape) }
        val answer = result(includes) {
            "layout" to (
                held?.let { h -> YoumoLayout(h.layout, h.fragments, basedOn, own = true, written = layout.value, shapeOf = shape) }
                    ?: YoumoLayout(layout.value, it, basedOn, own = true, shapeOf = shape)
                )
        }
        answer.ignored = held?.ignored.orEmpty().map { obj("file" to it.file, "line" to it.line, "what" to it.what) }.toTypedArray()
        // the reader's warnings (an attribute that does nothing): the page lists them, the checker does not
        if (includes is Parsed.Ok) answer.warnings = problems(layout.warnings + includes.warnings)
        answer
    }

    /** The app's own lists for the layout maker's page: `version`, `families`, `bases`, `pads`, `prefixes`, `builtIn`, `limits`, `keys`. */
    fun layoutFormat(): dynamic = obj(
        "version" to LayoutCheck.FEATURES.values.fold(LayoutCheck.BASE_VERSION) { v, f -> if (ThemeReader.newer(f, v)) f else v },
        "families" to LayoutCheck.FAMILIES.toTypedArray(),
        "bases" to LayoutCheck.BASES.toTypedArray(),
        "pads" to LayoutReader.PADS.toTypedArray(),
        "prefixes" to LayoutCheck.NAME_PREFIXES.toTypedArray(),
        "builtIn" to (builtIns.layouts + builtIns.fragments.keys).sorted().toTypedArray(),
        "limits" to obj("bytes" to PackLayouts.MAX_BYTES.toInt(), "id" to LayoutCheck.MAX_ID, "name" to LayoutCheck.MAX_NAME),
        "keys" to LayoutCheck.KEYS.toTypedArray(),
    )

    fun screenLandscape(turned: Boolean, widthDp: Int): Boolean = ScreenShape.landscape(turned, widthDp)

    fun replaces(id: String): Boolean = PackLayouts.replaces(id)

    /** Where [theme] leaves Shift, Backspace, Space or Return blank (BlankKeys): the first as text, `[]` for none, `["*"]` for too many scenes. */
    fun blankKeys(theme: YoumoTheme, layouts: dynamic): Array<String> = run {
        val files = HashMap<String, String>()
        if (layouts != null) for (name in keys(layouts, "layouts")) files[name.removeSuffix(".xml")] = text(layouts[name], "layout $name")
        val own = PackLayouts.readable(files, PackLayouts::replaces)
        val app = builtIns.layouts.mapNotNull { id ->
            builtIns.layout(id)?.orNull()?.let { l -> builtIns.includes(l).orNull()?.let { BlankKeys.Board(l, it, id) } }
        }
        BlankKeys.find(theme.theme, PackLayouts.drawn(app, own) { builtIns.layoutText(it) }).map { if (it == BlankKeys.TOO_MANY) "*" else it.toString() }.toTypedArray()
    }

    /** The marks [layout] draws with no user setting, firsts shown as [punctuation] maps them: EnvJson's `marks`. */
    fun defaultMarks(layout: dynamic, punctuation: dynamic): dynamic = run {
        val l: YoumoLayout = if (jsTypeOf(layout) == "string") {
            YoumoEngine.layout(layout as String) ?: throw NotOfType("no built-in layout \"$layout\"")
        } else {
            layout as? YoumoLayout ?: throw NotOfType("layout: not a layout")
        }
        val map = HashMap<String, String>()
        if (punctuation != null) for (k in keys(punctuation, "punctuation")) map[k] = text(punctuation[k], "punctuation $k")
        val nodes = if (l.own) DefaultMarks.ownNodes(l.layout, l.fragments) { id -> layout(id)?.let { it.layout to it.fragments } } else DefaultMarks.nodes(l.layout, l.fragments)
        JSON.parse<dynamic>(DefaultMarks.json(DefaultMarks.shortcuts(l.layout, l.fragments, DefaultMarks.variantOf(l.layout, l.shapeOf), punctuation = map, nodes = nodes)))
    }

    fun layouts(): Array<String> = builtIns.layouts.toTypedArray()

    fun layout(id: String): YoumoLayout? = builtIns.layout(id)?.orNull()?.let { l -> builtIns.includes(l).orNull()?.let { YoumoLayout(l, it) } }

    fun refuses(id: String, own: YoumoLayout): String? {
        val app = layout(id)
        return PackLayouts.judge(id, own.layout, own.fragments, app?.layout, app?.fragments.orEmpty())
    }

    fun layoutSource(id: String): String? = builtIns.layoutText(id)

    fun themes(): Array<String> = builtIns.themes.toTypedArray()

    fun themeSource(id: String): String? = builtIns.themeText(id)

    /** Text measured before its font loads uses the fallback and is not cached: await `document.fonts.ready` first. */
    fun canvasMeasurer(ctx: dynamic, fonts: dynamic): dynamic = checked {
        if (ctx == null || jsTypeOf(ctx.measureText) != "function") throw NotOfType("ctx: not a 2D context")
        val families = families(fonts)
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>(), "measurer" to YoumoMeasurer(CanvasMeasurer(ctx, families)))
    }

    fun fixedMeasurer(): YoumoMeasurer = YoumoMeasurer(FixedMeasurer)

    /** [env] is EnvJson, an object or its text; [measurer] none: a page canvas, else the fixed font. `{ok, problems, warnings, scene}`. */
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
        // a user's layout is styled by its based-on's name too, where the env names none
        val based = if (e.base == null && l.variantOf != l.layout.id) e.copy(base = l.variantOf) else e
        val built = builder(theme, based, measurer).build(l.layout, l.fragments)
        result(built) { "scene" to YoumoScene(it, based.density) }
    }

    /** `{ok, problems, sources: [{property, line, order}]}`: each property's winning style; [target] `{widget: "key", key, part?, states?}`. */
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

    /** [bytes] as the theme sound [name]: `{ok, info: {format, channels, rate, ms}}` or `{ok: false, problems}`. */
    fun readSound(name: dynamic, bytes: dynamic): dynamic = checked {
        val view: dynamic = bytes
        if (view == null || jsTypeOf(view.byteLength) != "number") throw NotOfType("bytes: not a Uint8Array")
        // no instanceof: the page's realm may not be ours
        val signed: ByteArray = if (view.buffer == null) {
            Int8Array(view.unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>()
        } else {
            Int8Array(view.buffer.unsafeCast<ArrayBuffer>(), view.byteOffset as Int, view.byteLength as Int).unsafeCast<ByteArray>()
        }
        result(SoundFile.read(text(name, "name"), signed)) { i ->
            "info" to obj("format" to i.format.name.lowercase(), "channels" to i.channels, "rate" to i.rate, "ms" to i.ms)
        }
    }

    /** [source] as a theme `<icon>`: `{ok, icon: {vw, vh, vx, vy, paths: [{d, rule, alpha, fill}]}}` as `paint`'s `icons` takes it. */
    fun readIcon(source: dynamic): dynamic = checked {
        result(SvgSubset.read(text(source, "source"))) { icon ->
            "icon" to obj(
                "vw" to icon.width,
                "vh" to icon.height,
                "vx" to icon.x,
                "vy" to icon.y,
                "paths" to icon.paths.map { p -> obj(
                        "d" to IconPath.text(p.segments),
                        "rule" to if (p.evenOdd) "evenodd" else "nonzero",
                        "alpha" to p.alpha,
                        "fill" to p.color?.value,
                    ) }.toTypedArray(),
            )
        }
    }

    /** `{lengthDp, offsetDp, borderDp, blurDp, depthDp, scale, spacingEm, stops}`: what a form offers. */
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

    /** The app draws its bar with views; BarThemeTest holds them to this scene. */
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

    /** What the app's own views take of [theme] in [env]: [BarLook], [WindowLooks], [PanelLooks]. */
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

    /** [request] `{kind: "preview"|"keys"|"menu", …}` (a long press's [rows] bottom first, -1 a gap): `{ok, problems, scene}`. */
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

    /** [state]: `pressed`, `fonts`, `images`, `picture`, `icons` (from youmo-engine-icons); nothing drawn where it is not that. */
    fun paint(scene: dynamic, ctx: dynamic, state: dynamic): dynamic = checked {
        val drawn = scene as? YoumoScene ?: throw NotOfType("scene: not a scene")
        val s = drawn.scene
        if (ctx == null || jsTypeOf(ctx.fill) != "function") throw NotOfType("ctx: not a 2D context")
        // the painter sizes its layers by the context's canvas
        if (ctx.canvas == null || ctx.canvas == undefined) throw NotOfType("ctx: a context with no canvas")
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

    /** Uses the painter's own op, so it matches a key's icon; an unknown icon draws nothing. [theme]: whose raster icons are masks. */
    fun paintIcon(ctx: dynamic, state: dynamic, name: dynamic, x: dynamic, y: dynamic, size: dynamic, tint: dynamic, rotation: dynamic, theme: dynamic = null): dynamic = checked {
        if (ctx == null || jsTypeOf(ctx.fill) != "function") throw NotOfType("ctx: not a 2D context")
        if (ctx.canvas == null || ctx.canvas == undefined) throw NotOfType("ctx: a context with no canvas")
        val color = text(tint, "tint").takeIf { HEX_ARGB.matches(it) } ?: throw NotOfType("tint: not #aarrggbb")
        val left = number(x, "x")
        val top = number(y, "y")
        val side = number(size, "size")
        val t = if (theme == null) null else theme as? YoumoTheme ?: throw NotOfType("theme: not a theme")
        val box = Box(left, top, left + side, top + side)
        val argb = Argb(color.substring(1).toLong(HEX).toInt())
        val op = t?.let { Paint.icon(it.theme, text(name, "name"), box, argb, number(rotation, "rotation")) }
            ?: Op.Icon(text(name, "name"), box, argb, number(rotation, "rotation"))
        CanvasPainter(ctx, assets(state, families(member(state, "fonts"))), 1.0, left + side, top + side).draw(listOf(op))
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>())
    }

    /** `{ok, problems, xml, name, dark, files: [[path in the 3.0 file, name in the 4.0]]}`. */
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

    fun writeTheme(theme: YoumoTheme): String = ThemeWriter.write(theme.theme)

    /** Whether app [version] is newer than [app] (ThemeReader.newer: false where either is not three numbers). */
    fun newer(version: dynamic, app: dynamic): Boolean = ThemeReader.newer(text(version, "version"), text(app, "app"))

    /** What the page holds a skin's file to before the engine has it ([SkinLimits]), and Sogou's cipher in hex. */
    fun skinFormat(): dynamic = obj(
        "archiveBytes" to SkinLimits.MAX_ARCHIVE_BYTES.toDouble(),
        "entries" to SkinLimits.MAX_ENTRIES,
        "entryBytes" to SkinLimits.MAX_ENTRY_BYTES.toDouble(),
        "totalBytes" to SkinLimits.MAX_TOTAL_BYTES.toDouble(),
        "ratio" to SkinLimits.MAX_RATIO,
        "ratioFrom" to SkinLimits.RATIO_FROM.toDouble(),
        "imagePixels" to SkinLimits.MAX_IMAGE_PIXELS.toDouble(),
        "textBytes" to SkinLimits.MAX_TEXT_BYTES,
        "sogouKey" to SkinPlatform.SOGOU_KEY,
        "sogouIv" to SkinPlatform.SOGOU_IV,
    )

    /** The [SkinFormat] id of a format told apart by [fileName] and [names] (`[name]`, none: by the name alone) and not converted, else null. */
    fun skinUnsupported(fileName: dynamic, names: dynamic): String? = SkinConverter.unsupported(text(fileName, "fileName"), names(names))

    /** Whether a reader takes the files [names] as they are: a ZIP that is a skin, not one holding one (SkinZip.readSkin). */
    fun skinTaken(names: dynamic): Boolean = names(names).let { n -> SkinConverter.READERS.any { it.first(n) } }

    /**
     * [entries] (`[{name, bytes}]`, a skin's files) as SkinConverter.open gives a reader them: `{ok, entries}`, else
     * `{ok: false, reason, detail}` (SkinError's). [prepared] `{name: payload}` (no prototype: a name is a skin's): a
     * Sogou container's decrypted and inflated by the page (WebCrypto is asynchronous), `"too-big"` where its stated
     * length is past what is left to open.
     */
    fun skinOpen(entries: dynamic, prepared: dynamic): dynamic = skinChecked {
        val (files, names) = skinEntries(entries)
        val platform = object : SkinPlatform {
            override fun image(bytes: ByteArray): SkinImage? = null

            override fun legacyText(bytes: ByteArray): String = gb18030(bytes)

            override fun sogouPlain(container: ByteArray, maxBytes: Int): ByteArray? {
                val name = names.get(container) as? String ?: return null
                val p = member(prepared, name) ?: return null
                if (jsTypeOf(p) == "string") throw SkinError(SkinError.Reason.UNPACKED_TOO_BIG)
                val b = signed(p, "prepared $name")
                if (b.size > maxBytes) throw SkinError(SkinError.Reason.UNPACKED_TOO_BIG)
                return b
            }
        }
        val opened = SkinConverter.open(files, platform)
        obj("ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>(), "entries" to opened.map { (n, b) -> obj("name" to n, "bytes" to unsigned(b)) }.toTypedArray())
    }

    /**
     * [entries] as [skinOpen] gave them, converted ([SkinConverter.convertOpened]); [decoded] `{name: {width, height,
     * data}}` (RGBA not premultiplied, ImageData's) its images the page decoded, [fileName] the file's own name.
     * `{ok, name, themeXml, themeXmlWithLayouts, files: [{name, bytes} | {name, width, height, rgba, maxBytes, shrink}],
     * layouts: [{id, xml}], notes: [{id, args}]}`, else `{ok: false, reason, detail}`.
     */
    fun convertSkin(entries: dynamic, decoded: dynamic, fileName: dynamic): dynamic = skinChecked {
        val (files, names) = skinEntries(entries)
        val platform = object : SkinPlatform {
            override fun image(bytes: ByteArray): SkinImage? {
                val name = names.get(bytes) as? String ?: return null
                return pixels(member(decoded, name))?.let(::PixelsImage)
            }

            override fun legacyText(bytes: ByteArray): String = gb18030(bytes)

            override fun layoutSources(): LayoutCheck.Sources = checkSources
        }
        val c = SkinConverter.convertOpened(files, platform, text(fileName, "fileName"))
        obj(
            "ok" to true, "problems" to emptyArray<dynamic>(), "warnings" to emptyArray<dynamic>(),
            "name" to c.name, "themeXml" to c.themeXml, "themeXmlWithLayouts" to c.themeXmlWithLayouts,
            "files" to c.files.map { f ->
                when (f) {
                    is SkinOutput.Bytes -> obj("name" to f.name, "bytes" to unsigned(f.bytes))
                    is SkinOutput.Image -> obj(
                        "name" to f.name, "width" to f.pixels.width, "height" to f.pixels.height, "rgba" to rgba(f.pixels),
                        "maxBytes" to f.maxBytes.toDouble(), "shrink" to f.shrink,
                    )
                }
            }.toTypedArray(),
            "layouts" to c.layouts.map { (id, xml) -> obj("id" to id, "xml" to xml) }.toTypedArray(),
            "notes" to c.notes.map { (n, args) -> obj("id" to n.name, "args" to args.toTypedArray()) }.toTypedArray(),
        )
    }

    /** [image] (`{width, height, rgba}`, as [convertSkin] gives one) at [width]×[height], as the app halves a skin's picture (Pixels.resized). */
    fun skinResized(image: dynamic, width: dynamic, height: dynamic): dynamic {
        val p = pixels(obj("width" to member(image, "width"), "height" to member(image, "height"), "data" to member(image, "rgba")))
            ?: throw NotOfType("image: not {width, height, rgba}")
        val r = p.resized(whole(width, "width").coerceAtLeast(1), whole(height, "height").coerceAtLeast(1))
        return obj("width" to r.width, "height" to r.height, "rgba" to rgba(r))
    }

    /** `{ok, errors, warnings, family, ims, pads, commits, minApp}`, each finding `{line, key, args}`. */
    fun checkLayout(xml: dynamic, appVersion: dynamic, theme: dynamic): dynamic = checked {
        val t = if (theme == null) null else theme as? YoumoTheme ?: throw NotOfType("theme: not a theme")
        val r = LayoutCheck.check(text(xml, "layout"), checkSources, text(appVersion, "appVersion"), t?.theme)
        fun findings(list: List<LayoutCheck.Finding>) = list.map { obj("line" to it.line, "key" to it.key, "args" to it.args.toTypedArray()) }.toTypedArray()
        obj(
            "ok" to r.ok, "errors" to findings(r.errors), "warnings" to findings(r.warnings), "family" to r.family,
            "ims" to r.ims.toTypedArray(), "pads" to r.pads.toTypedArray(), "commits" to r.commits.toTypedArray(), "minApp" to r.minApp,
        )
    }

    /** As the app's import (PackLayouts.convert); `ok` false with `why` where it is not made one. */
    fun convertPackLayout(theme: dynamic, meta: dynamic, id: dynamic, xml: dynamic, fragments: dynamic, appVersion: dynamic): dynamic = checked {
        val own = HashMap<String, String>()
        if (fragments != null) for (f in keys(fragments, "fragments")) own[f] = text(fragments[f], "fragment $f")
        val m = if (meta == null) null else Meta(optionalText(meta.author, "author"), null, optionalText(meta.license, "license"), null, null)
        when (val c = PackLayouts.convert(text(theme, "theme"), m, text(id, "id"), text(xml, "layout"), own, checkSources, text(appVersion, "appVersion"))) {
            is PackLayouts.Converted.Not -> obj("ok" to false, "why" to c.why)
            is PackLayouts.Converted.Done -> {
                val renames: dynamic = js("({})")
                c.renames.forEach { (old, new) -> renames[old] = new }
                obj(
                    "ok" to true, "id" to c.id, "name" to c.name, "text" to c.text, "renames" to renames,
                    "problems" to c.problems.map { obj("line" to it.line, "key" to it.key, "args" to it.args.toTypedArray()) }.toTypedArray(),
                )
            }
        }
    }

    fun writeLayout(layout: YoumoLayout): String = LayoutWriter.write(layout.written)

    private val checkSources by lazy {
        fun texts(ids: Collection<String>) = ids.mapNotNull { id -> builtIns.layoutText(id)?.let { id to it } }.toMap()
        LayoutCheck.Sources(texts(builtIns.layouts), texts(builtIns.fragments.keys))
    }


    private class NotOfType(message: String) : Exception(message)

    // a skin's numbers past what a reader holds them to are a broken file, never a page's crash
    @Suppress("TooGenericExceptionCaught")
    private inline fun skinChecked(answer: () -> dynamic): dynamic = checked {
        try {
            answer()
        } catch (e: NotOfType) {
            throw e
        } catch (e: SkinError) {
            skinFailed(e.reason, e.detail)
        } catch (e: Throwable) {
            skinFailed(SkinError.Reason.BROKEN, e.message.orEmpty())
        }
    }

    private fun skinFailed(reason: SkinError.Reason, detail: String): dynamic =
        obj("ok" to false, "reason" to reason.name, "detail" to detail, "problems" to arrayOf(obj("line" to 0, "message" to "$reason $detail".trim())), "warnings" to emptyArray<dynamic>())

    // the files by name, the first of a name twice (as SkinZip's), and each array's name: a reader hands the platform
    // the very array it was given, and a JS Map finds it by that, not by its bytes
    private fun skinEntries(entries: dynamic): Pair<Map<String, ByteArray>, dynamic> {
        val files = LinkedHashMap<String, ByteArray>()
        val names: dynamic = js("new Map()")
        array(entries, "entries").forEachIndexed { i, e ->
            val name = text(member(e, "name"), "entries[$i].name")
            if (name in files) return@forEachIndexed
            val b = signed(member(e, "bytes"), "entries[$i].bytes")
            files[name] = b
            names.set(b, name)
        }
        return files to names
    }

    private fun names(v: dynamic): Set<String> = if (v == null) emptySet() else array(v, "names").mapIndexedTo(LinkedHashSet()) { i, n -> text(n, "names[$i]") }

    // a view of [view]'s bytes, not a copy; no instanceof: the page's realm may not be ours
    private fun signed(view: dynamic, what: String): ByteArray {
        if (view == null || jsTypeOf(view.byteLength) != "number") throw NotOfType("$what: not a Uint8Array")
        return if (view.buffer == null) {
            Int8Array(view.unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>()
        } else {
            Int8Array(view.buffer.unsafeCast<ArrayBuffer>(), view.byteOffset as Int, view.byteLength as Int).unsafeCast<ByteArray>()
        }
    }

    private fun unsigned(b: ByteArray): Uint8Array = b.unsafeCast<Int8Array>().let { Uint8Array(it.buffer, it.byteOffset, it.length) }

    private fun gb18030(bytes: ByteArray): String {
        val decoder: dynamic = js("new TextDecoder('gb18030')")
        return decoder.decode(unsigned(bytes)) as String
    }

    // ImageData's {width, height, data}: null where it is not one, or past what a skin's image may be
    private fun pixels(d: dynamic): Pixels? {
        if (d == null) return null
        val data = member(d, "data")
        if (jsTypeOf(member(d, "width")) != "number" || jsTypeOf(member(d, "height")) != "number" || data == null) return null
        val width = whole(d.width, "width")
        val height = whole(d.height, "height")
        if (width <= 0 || height <= 0 || width.toLong() * height > SkinLimits.MAX_IMAGE_PIXELS) return null
        val b = signed(data, "data")
        if (b.size != width * height * RGBA) return null
        val argb = IntArray(width * height) { i ->
            val at = i * RGBA
            (b[at + ALPHA].toInt() and BYTE shl ALPHA_SHIFT) or (b[at].toInt() and BYTE shl RED_SHIFT) or
                (b[at + 1].toInt() and BYTE shl GREEN_SHIFT) or (b[at + 2].toInt() and BYTE)
        }
        return Pixels(width, height, argb)
    }

    // the bytes written as signed are the RGBA a Uint8ClampedArray over them reads
    private fun rgba(p: Pixels): Uint8ClampedArray {
        val out = ByteArray(p.argb.size * RGBA)
        for ((i, c) in p.argb.withIndex()) {
            val at = i * RGBA
            out[at] = (c ushr RED_SHIFT).toByte()
            out[at + 1] = (c ushr GREEN_SHIFT).toByte()
            out[at + 2] = c.toByte()
            out[at + ALPHA] = (c ushr ALPHA_SHIFT).toByte()
        }
        return out.unsafeCast<Int8Array>().let { Uint8ClampedArray(it.buffer, it.byteOffset, it.length) }
    }

    private const val RGBA = 4
    private const val ALPHA = 3
    private const val BYTE = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    // the app's default 候选字号 (SceneBar): 40dp high, 18sp text, 9dp each side, 32dp at least
    private const val BAR_DP = 40.0
    private const val HEX = 16
    private val HEX_ARGB = Regex("#[0-9a-fA-F]{8}")
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

    // CanvasMeasurer caches nothing measured before its font loads, so an early one is not stuck with fallback widths
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

    const val DEFAULT_FAMILY = "\"Youmo Latin\", \"Youmo Han\", sans-serif"
}
