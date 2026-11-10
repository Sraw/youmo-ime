/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/** What a `fill=` paints (theme-4.xsd's `fill`). */
sealed interface Fill {
    data class Solid(val color: ColorExpr) : Fill

    /** `linear(angle, colour pos%, …)`. */
    data class Linear(val angle: Double, val stops: List<Pair<ColorExpr, Double>>) : Fill

    /**
     * `image(file, l t r b[, Nx][, colour])`: a nine-slice, its corners [insets] source pixels at [pxPerDp];
     * [fallback] filled where the image is not there (none read, not in yet), as theme 3.0 showed the key's colour.
     */
    data class Image(val file: String, val insets: List<Int>, val pxPerDp: Int, val fallback: ColorExpr? = null) : Fill

    /**
     * `picture(blur, brightness[, colour])`: the keyboard's picture under the key; null: as `<picture>` has it.
     * [over]: a colour laid over it, as theme 3.0 laid the key's colour over its picture.
     */
    data class Picture(val blurDp: Double?, val brightness: Double?, val over: ColorExpr? = null) : Fill
}

/** `radius=`: four corners (top left, top right, bottom right, bottom left), dp; [half]: 50%, a pill or a circle. */
data class Radius(val corners: List<Double>, val half: Boolean = false)

/** `inset=`/`padding=`: top, right, bottom, left, dp (CSS's order; two values are vertical horizontal). */
data class Inset(val top: Double, val right: Double, val bottom: Double, val left: Double)

/** `shadow=`: offset, blur (dp) and colour; [NONE] for `none`. */
data class Shadow(val dx: Double, val dy: Double, val blur: Double, val color: ColorExpr?) {
    companion object {
        val NONE = Shadow(0.0, 0.0, 0.0, null)
    }
}

/** `border=`: width (dp) and colour; [NONE] for `none`. */
data class Border(val width: Double, val color: ColorExpr?) {
    companion object {
        val NONE = Border(0.0, null)
    }
}

/** A keyboard's size upright and turned ([Values.KEYBOARD_SIZES]): `height` in % of the screen's height, a padding in dp. */
data class Twin(val portrait: Double, val landscape: Double) {
    fun of(landscape: Boolean): Double = if (landscape) this.landscape else portrait
}

/**
 * What `space-content=` or a `return-icon-<action>=` puts on its key instead of what the layout gives:
 * [ImeName] the layout's own (the space's input method name), [None] nothing, a [Text], an [Icon].
 */
sealed interface Shown {
    data object ImeName : Shown
    data object None : Shown
    data class Text(val text: String) : Shown
    data class Icon(val name: String) : Shown
}

/** A style property's value as read; which kind each property takes is [Values.read]'s table. */
sealed interface Value {
    data class OfShown(val shown: Shown) : Value
    data class OfPath(val path: ShapePath) : Value

    /** A percentage: the bubble's `size="120%"` (a `size` in sp is a [Number]). */
    data class Percent(val percent: Double) : Value
    data class OfFill(val fill: Fill) : Value
    data class OfColor(val color: ColorExpr) : Value
    data class OfRadius(val radius: Radius) : Value
    data class OfInset(val inset: Inset) : Value
    data class OfShadow(val shadow: Shadow) : Value
    data class OfBorder(val border: Border) : Value
    data class Word(val word: String) : Value
    data class Number(val number: Double) : Value
    data class Flag(val on: Boolean) : Value
    data class OfTwin(val twin: Twin) : Value
    data class OfTransition(val transition: Transition) : Value
    /** `effect=` (A3): [effect] null for `none`, a style taking a key's effect away. */
    data class OfEffect(val effect: Effect?) : Value
}

/**
 * A key's press effect (060-render-p3 A3), `effect=` on its pressed look: an image of the pack's [file]
 * played over the key once each press, [ms] long.
 */
sealed interface Effect {
    val file: String
    val ms: Int

    /** The image grown from where the finger is to [radius] % of the key's half diagonal, fading, clipped to the key. */
    data class Ripple(override val file: String, val radius: Double, override val ms: Int) : Effect

    /** [count] copies of the image flying from where the finger is, up to [spread] dp, fading. */
    data class Particles(override val file: String, val count: Int, val spread: Double, override val ms: Int) : Effect
}

/**
 * `transition=` (060-render-p3 A1): how long a change into the state its style is of takes, [ms]
 * (0–600), and its curve, one of [Values.EASINGS] (CSS's names).
 */
data class Transition(val ms: Int, val easing: String)

/** Reading a style property's value: null where it is not one of that property. */
object Values {
    private val DP = Regex("(-?[0-9]+(\\.[0-9]+)?)dp|0")
    private val SP = Regex("([0-9]+(\\.[0-9])?)sp")
    private val PCT = Regex("([0-9]+(\\.[0-9]+)?)%")
    // "system" a name like any other: Kotlin/JS takes the first alternative that starts a match and
    // wants it whole, so "system|…" refused "systemui" there
    private val FONT = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
    private val IMAGE = Regex("image\\(([^,()]+), ?([0-9]{1,4}) ([0-9]{1,4}) ([0-9]{1,4}) ([0-9]{1,4})(?:, ?([1-8])x)?(?:, ?(.+))?\\)")
    private val PICTURE = Regex("picture(?:\\(([0-9]{1,2})dp, ?([0-9]{1,3})%(?:, ?(.+))?\\))?")
    private val LINEAR = Regex("linear\\((-?[0-9]{1,3})deg, ?(.+)\\)")
    private const val MAX_LENGTH_DP = 24.0
    private const val MAX_OFFSET_DP = 16.0
    private const val MAX_BORDER_DP = 3.0
    private const val MIN_SCALE = 85.0
    private const val MAX_SCALE = 130.0
    private const val MAX_SP = 48.0
    private const val MAX_BLUR_DP = 25
    private const val MAX_PERCENT = 100.0
    private const val FOUR = 4
    private const val MAX_DEPTH_DP = 8.0
    private const val MIN_BUBBLE = 100.0
    private const val MAX_BUBBLE = 160.0
    private const val MIN_SPACING_EM = -0.1
    private const val MAX_SPACING_EM = 0.5
    private val EM = Regex("-?[0-9](\\.[0-9]{1,3})?em")
    private val ICON_SHOWN = Regex("icon\\(([a-z0-9]+(-[a-z0-9]+)*)\\)")
    private val TEXT_SHOWN = Regex("text\\(([^()]{1,32})\\)")
    const val RETURN_ICON = "return-icon-"

    /** Return's actions a theme may give an icon or a text of its own (`return-icon-<action>`). */
    val RETURN_ACTIONS = listOf("send", "search", "go", "next", "done", "newline")

    /** The properties a `<style>` may have. */
    val PROPERTIES = listOf(
        "fill", "overlay", "shape", "radius", "border", "shadow", "inset", "color", "font", "weight", "scale", "size",
        "hidden", "icon-color", "press", "padding",
        // T4a: the text on a key
        "align", "letter-spacing", "stroke", "text-shadow", "font-han", "font-latin", "space-content",
        // T4b: a key's shape and cap, the bubble's shape, size and place
        "inner-shadow", "depth", "depth-color", "tail", "offset",
        // T6a: the candidates (BarLook)
        "gap", "label-style",
        // T6b: the code being typed (BarLook.preedit)
        "underline", "cursor-color",
        // T7b: the voice mask's frosted glass (engine/PanelLooks), the handwriting pad's ink and grid
        "blur", "ink-color", "ink-width", "grid-color", "grid",
        // A1: a key's motion (the scene's Motion); A3: its press effect
        "transition", "press-scale", "press-sink", "effect",
    ) + RETURN_ACTIONS.map { "$RETURN_ICON$it" }

    private const val LEN = "(-?[0-9]+(\\.[0-9]+)?dp|0)"
    private const val PERCENT_FORM = "[0-9]+(\\.[0-9]+)?%"
    private const val LEN_OR_PERCENT = "($LEN|$PERCENT_FORM)( ($LEN|$PERCENT_FORM))?"
    private const val MS = "[0-9]{1,3}ms"

    // ASCII white space spelt out: `\s` is more on JS than on the JVM, and a file must read the same on both
    private const val FILE = "[^,()/\\\\:*?\"<>| \\t\\n\\r\\f\\x0B]{1,128}"

    /** A reserved property's value read: the colours in it (maybe none), or null where it is not of the form. */
    fun interface Reserved {
        fun read(raw: String): List<ColorExpr>?
    }

    // anchored: Kotlin/JS matches a whole value by the first alternative that starts one (FONT above), so "ease" would end "ease-out"
    private fun form(pattern: String): Reserved = Regex("(?:$pattern)$").let { r -> Reserved { if (r.matches(it)) emptyList() else null } }

    /**
     * Style properties of a later stage of 0.6.0 (060-render-p3: T5–T7b, A1–A3, the sounds' `sticker`),
     * each with its value's form: read where it is of the form, ignored with a warning (a theme made
     * for a newer app still imports); a value not of the form, or naming a colour not there, is a
     * problem like any. A stage that makes one work moves it to [PROPERTIES].
     */
    val RESERVED: Map<String, Reserved> = mapOf(
        // read on the keyboard (T5, [KEYBOARD_SIZES]), the handle (T7a, [HANDLE_SIZES]) and the bar (T6b, [barHeight]); on another widget ignored
        "height" to form(LEN_OR_PERCENT),
        "width" to form(LEN),
        "side-padding" to form(LEN_OR_PERCENT),
        "bottom-padding" to form(LEN_OR_PERCENT),
        // A2: motion
        "appear" to form("(fade|scale|rise) $MS"),
        // §16's second tier: a key's sticker layer
        "sticker" to form("none|$FILE"),
    )

    /**
     * The keyboard's size (060-render-v2 §15, 主题管尺寸): set on `keyboard` alone, under no scene,
     * portrait then landscape (one value: both). The same names on the handle are its grip's
     * ([HANDLE_SIZES]), the bar's `height` its own ([barHeight]), on another widget ignored ([RESERVED]).
     */
    val KEYBOARD_SIZES = listOf("height", "side-padding", "bottom-padding")

    // what the settings' own ranges are (AppPrefs: 键盘高度 10–90%, 两侧边距 0–300dp, 底部边距 0–100dp)
    private const val MIN_HEIGHT = 10.0
    private const val MAX_HEIGHT = 90.0
    private const val MAX_SIDE_DP = 300.0
    private const val MAX_BOTTOM_DP = 100.0

    /** A keyboard size of [property] ([KEYBOARD_SIZES]): `38% 52%`, `8dp 48dp`, `0`; null where not one, or out of range. */
    fun keyboardSize(property: String, raw: String): Twin? {
        val parts = raw.split(' ')
        if (parts.size !in 1..2) return null
        val v = parts.map {
            when (property) {
                "height" -> percent(it)?.takeIf { p -> p in MIN_HEIGHT..MAX_HEIGHT }
                "side-padding" -> dp(it, MAX_SIDE_DP)
                "bottom-padding" -> dp(it, MAX_BOTTOM_DP)
                else -> null
            } ?: return null
        }
        return Twin(v.first(), v.last())
    }

    /**
     * The drag handle's grip (060-render-p3 T7a): set on `handle` alone, its width and height, dp, as a
     * [Value.Number]; no wider than [MAX_GRIP_WIDTH_DP], no taller than the handle's strip ([MAX_GRIP_HEIGHT_DP]).
     */
    val HANDLE_SIZES = listOf("width", "height")
    const val MAX_GRIP_WIDTH_DP = 160.0
    const val MAX_GRIP_HEIGHT_DP = 24.0

    /** A grip's size of [property] ([HANDLE_SIZES]); null where not one, or out of range. */
    fun gripSize(property: String, raw: String): Double? = dp(raw, if (property == "width") MAX_GRIP_WIDTH_DP else MAX_GRIP_HEIGHT_DP)

    /** The navigation bar's `color="follow"` (T7a): the keyboard's colour under it, as ever; on another widget, ignored. */
    const val FOLLOW = "follow"

    fun read(property: String, raw: String): Value? = when (property) {
        "fill" -> fill(raw)?.let(Value::OfFill)
        "overlay", "color", "icon-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "shape" -> shape(raw)
        "radius" -> radius(raw)?.let(Value::OfRadius)
        "border" -> border(raw)?.let(Value::OfBorder)
        "shadow" -> shadow(raw)?.let(Value::OfShadow)
        "inset", "padding" -> inset(raw)?.let(Value::OfInset)
        "font" -> raw.takeIf(FONT::matches)?.let(Value::Word)
        "weight" -> raw.takeIf { it in listOf("300", "400", "700") }?.let { Value.Number(it.toDouble()) }
        "scale" -> percent(raw)?.takeIf { it in MIN_SCALE..MAX_SCALE }?.let(Value::Number)
        "size" -> size(raw)
        "hidden" -> flag(raw)
        "press" -> raw.takeIf { it in listOf("tint", "ripple", "face") }?.let(Value::Word)
        "gap" -> dp(raw)?.let(Value::Number)
        "label-style" -> raw.takeIf { it in LABEL_STYLES }?.let(Value::Word)
        else -> textOnAKey(property, raw) ?: capAndBubble(property, raw) ?: panel(property, raw)
    }

    /** `transition=`'s curves (CSS's) and longest, ms; `press-scale=`'s range, %; `press-sink=`'s most, dp. */
    val EASINGS = listOf("linear", "ease", "ease-in", "ease-out", "ease-in-out")
    const val MAX_TRANSITION_MS = 600
    const val MIN_PRESS_SCALE = 85.0
    const val MAX_PRESS_SCALE = 110.0
    const val MAX_PRESS_SINK_DP = 4.0
    private val TRANSITION = Regex("([0-9]{1,3})ms ([a-z-]+)")

    // A1's: a key's transition into a state, its pressed face's scale and sink
    private fun motion(property: String, raw: String): Value? = when (property) {
        "transition" -> TRANSITION.matchEntire(raw)?.let { m ->
            val ms = m.groupValues[1].toInt()
            val easing = m.groupValues[2]
            if (ms <= MAX_TRANSITION_MS && easing in EASINGS) Value.OfTransition(Transition(ms, easing)) else null
        }
        "press-scale" -> percent(raw)?.takeIf { it in MIN_PRESS_SCALE..MAX_PRESS_SCALE }?.let(Value::Number)
        "press-sink" -> dp(raw, MAX_PRESS_SINK_DP)?.let(Value::Number)
        "effect" -> effect(raw)
        else -> null
    }

    /** `effect=`'s ranges (A3): a ripple's radius, %; particles a press, how far they fly, dp; how long either plays, ms. */
    const val MIN_RIPPLE_RADIUS = 10.0
    const val MAX_RIPPLE_RADIUS = 200.0
    const val MAX_PARTICLES = 12
    const val MAX_SPREAD_DP = 64.0
    const val MAX_EFFECT_MS = 600
    private val RIPPLE = Regex("ripple-image\\(($FILE), ?([0-9]+(\\.[0-9]+)?%), ?([0-9]{1,3})ms\\)")
    private val PARTICLES = Regex("particles\\(($FILE), ?([0-9]{1,2}), ?($LEN), ?([0-9]{1,3})ms\\)")

    private fun effect(raw: String): Value? {
        if (raw == "none") return Value.OfEffect(null)
        RIPPLE.matchEntire(raw)?.let { m ->
            val radius = percent(m.groupValues[2])?.takeIf { it in MIN_RIPPLE_RADIUS..MAX_RIPPLE_RADIUS } ?: return null
            val ms = m.groupValues[4].toInt().takeIf { it in 1..MAX_EFFECT_MS } ?: return null
            return Value.OfEffect(Effect.Ripple(m.groupValues[1], radius, ms))
        }
        PARTICLES.matchEntire(raw)?.let { m ->
            val count = m.groupValues[2].toInt().takeIf { it in 1..MAX_PARTICLES } ?: return null
            val spread = dp(m.groupValues[3], MAX_SPREAD_DP) ?: return null
            val ms = m.groupValues.last().toInt().takeIf { it in 1..MAX_EFFECT_MS } ?: return null
            return Value.OfEffect(Effect.Particles(m.groupValues[1], count, spread, ms))
        }
        return null
    }

    // T6b's: the code being typed's underline (a border's form, or none) and cursor
    private fun preedit(property: String, raw: String): Value? = when (property) {
        "underline" -> border(raw)?.let(Value::OfBorder)
        "cursor-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        else -> motion(property, raw)
    }

    /** The voice mask's frosted glass at most (`blur`), dp: as the keyboard's picture's. */
    const val MAX_PANEL_BLUR_DP = 25.0

    /** The handwriting pad's ink (`ink-width`), dp. */
    const val MIN_INK_DP = 1.0
    const val MAX_INK_DP = 8.0

    // T7b's: the voice mask's blur, the handwriting pad's ink and grid
    private fun panel(property: String, raw: String): Value? = when (property) {
        "blur" -> dp(raw, MAX_PANEL_BLUR_DP)?.let(Value::Number)
        "ink-width" -> dp(raw, MAX_INK_DP)?.takeIf { it >= MIN_INK_DP }?.let(Value::Number)
        "ink-color", "grid-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "grid" -> flag(raw)
        else -> preedit(property, raw)
    }

    /** The bar's `height` (T6b), of the height 候选字号 gives it: one value, upright and turned alike. */
    const val MIN_BAR_HEIGHT = 80.0
    const val MAX_BAR_HEIGHT = 140.0

    /** The bar's `height="120%"`, in %; null where not one, or out of [MIN_BAR_HEIGHT]–[MAX_BAR_HEIGHT]. */
    fun barHeight(raw: String): Double? = percent(raw)?.takeIf { it in MIN_BAR_HEIGHT..MAX_BAR_HEIGHT }

    /** A candidate's `label-style=` (T6a): no number before it, its number, a dot. */
    val LABEL_STYLES = listOf("none", "number", "dot")

    // a text's size in sp, or the bubble's in % (T4b)
    private fun size(raw: String): Value? =
        SP.matchEntire(raw)?.groupValues?.get(1)?.toDouble()?.takeIf { it > 0 && it <= MAX_SP }?.let(Value::Number)
            ?: percent(raw)?.takeIf { it in MIN_BUBBLE..MAX_BUBBLE }?.let(Value::Percent)

    private fun flag(raw: String): Value? = when (raw) {
        "true" -> Value.Flag(true)
        "false" -> Value.Flag(false)
        else -> null
    }

    // `rect`, `bar`, `circle`, the bubble's `bubble`, or `path(…)` (T4b)
    private fun shape(raw: String): Value? = when {
        raw in listOf("rect", "bar", "circle", BUBBLE) -> Value.Word(raw)
        raw.startsWith("path(") && raw.endsWith(")") -> ShapePath.parse(raw.removeSurrounding("path(", ")"))?.let(Value::OfPath)
        else -> null
    }

    // T4b's: a key's inner shadow and depth, the bubble's tail and offset
    private fun capAndBubble(property: String, raw: String): Value? = when (property) {
        "inner-shadow" -> shadow(raw)?.let(Value::OfShadow)
        "depth" -> dp(raw, MAX_DEPTH_DP)?.let(Value::Number)
        "depth-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "tail" -> flag(raw)
        "offset" -> dp(raw, MAX_LENGTH_DP, signed = true)?.let(Value::Number)
        else -> null
    }

    /** `shape="bubble"`: the preview's rounded head over the key, a tail down to it unless `tail="false"`. */
    const val BUBBLE = "bubble"

    // T4a's
    private fun textOnAKey(property: String, raw: String): Value? = when (property) {
        "align" -> raw.takeIf { it in listOf(CENTER, TOP_LEFT, TOP_CENTER) }?.let(Value::Word)
        "letter-spacing" -> raw.takeIf(EM::matches)?.removeSuffix("em")?.toDouble()
            ?.takeIf { it in MIN_SPACING_EM..MAX_SPACING_EM }?.let { Value.Number(it + 0.0) } // -0em as 0
        "stroke" -> stroke(raw)?.let(Value::OfBorder)
        "text-shadow" -> shadow(raw)?.let(Value::OfShadow)
        "font-han", "font-latin" -> raw.takeIf(FONT::matches)?.let(Value::Word)
        "space-content" -> when (raw) {
            "ime-name" -> Shown.ImeName
            "none" -> Shown.None
            else -> shown(raw)
        }?.let(Value::OfShown)
        else -> if (property.removePrefix(RETURN_ICON) in RETURN_ACTIONS && property.startsWith(RETURN_ICON)) shown(raw)?.let(Value::OfShown) else null
    }

    /** `align=`: the key's glyph in the middle of its face (as ever), at its top left corner, at its top. */
    const val CENTER = "center"
    const val TOP_LEFT = "top-left"
    const val TOP_CENTER = "top-center"

    // `icon(name)` or `text(…)`
    private fun shown(raw: String): Shown? =
        ICON_SHOWN.matchEntire(raw)?.let { Shown.Icon(it.groupValues[1]) } ?: TEXT_SHOWN.matchEntire(raw)?.let { Shown.Text(it.groupValues[1]) }

    /** `stroke=`: its width, 0–3 dp as a border's (the outline drawn so far outside the glyphs), and its colour. */
    fun stroke(raw: String): Border? {
        val p = raw.split(' ', limit = 2)
        if (p.size != 2) return null
        val width = dp(p[0], MAX_BORDER_DP) ?: return null
        return Border(width, ColorExpr.parse(p[1]) ?: return null)
    }

    /** `7dp` (or `0`) as dp, within ±[max]; negative only where [signed]. */
    fun dp(raw: String, max: Double = MAX_LENGTH_DP, signed: Boolean = false): Double? {
        if (!DP.matches(raw)) return null
        // -0dp as 0: written back as 0, it reads as the same value
        val v = (if (raw == "0") 0.0 else raw.removeSuffix("dp").toDouble()) + 0.0
        return v.takeIf { (signed || it >= 0) && it >= -max && it <= max }
    }

    fun percent(raw: String): Double? = PCT.matchEntire(raw)?.groupValues?.get(1)?.toDouble()

    fun fill(raw: String): Fill? {
        ColorExpr.parse(raw)?.let { return Fill.Solid(it) }
        IMAGE.matchEntire(raw)?.let { m ->
            val g = m.groupValues
            val fallback = g[7].ifEmpty { null }?.let { ColorExpr.parse(it.trim()) ?: return null }
            return Fill.Image(g[1].trim(), (2..5).map { g[it].toInt() }, g[6].ifEmpty { "1" }.toInt(), fallback)
        }
        PICTURE.matchEntire(raw)?.let { m ->
            val blur = m.groupValues[1].ifEmpty { null }?.toInt()
            val bright = m.groupValues[2].ifEmpty { null }?.toDouble()
            val tooBlurred = blur != null && blur > MAX_BLUR_DP
            if (tooBlurred || bright != null && bright > MAX_PERCENT) return null
            val over = m.groupValues[3].ifEmpty { null }?.let { ColorExpr.parse(it.trim()) ?: return null }
            return Fill.Picture(blur?.toDouble(), bright, over)
        }
        LINEAR.matchEntire(raw)?.let { m ->
            val stops = ColorExpr.splitTop(m.groupValues[2])?.map { stop ->
                val t = stop.trim()
                val color = ColorExpr.parse(t.substringBeforeLast(' ')) ?: return null
                val at = percent(t.substringAfterLast(' '))?.takeIf { it <= MAX_PERCENT } ?: return null
                color to at
            } ?: return null
            return if (stops.size in 2..8) Fill.Linear(m.groupValues[1].toDouble(), stops) else null
        }
        return null
    }

    fun radius(raw: String): Radius? {
        val parts = raw.split(' ')
        if (parts.size == 1 && parts[0] == "50%") return Radius(List(FOUR) { 0.0 }, half = true)
        if (parts.size != 1 && parts.size != FOUR) return null
        val dps = parts.map { dp(it) ?: return null }
        return Radius(if (dps.size == 1) List(FOUR) { dps[0] } else dps)
    }

    fun inset(raw: String): Inset? {
        val v = raw.split(' ').map { dp(it) ?: return null }
        return when (v.size) {
            1 -> Inset(v[0], v[0], v[0], v[0])
            2 -> Inset(v[0], v[1], v[0], v[1])
            FOUR -> Inset(v[0], v[1], v[2], v[3])
            else -> null
        }
    }

    fun shadow(raw: String): Shadow? {
        if (raw == "none") return Shadow.NONE
        val p = raw.split(' ', limit = FOUR)
        if (p.size != FOUR) return null
        val dx = dp(p[0], MAX_OFFSET_DP, signed = true) ?: return null
        val dy = dp(p[1], MAX_OFFSET_DP, signed = true) ?: return null
        val blur = dp(p[2], MAX_OFFSET_DP) ?: return null
        return Shadow(dx, dy, blur, ColorExpr.parse(p[3]) ?: return null)
    }

    fun border(raw: String): Border? {
        if (raw == "none") return Border.NONE
        val p = raw.split(' ', limit = 2)
        if (p.size != 2) return null
        val width = dp(p[0], MAX_BORDER_DP) ?: return null
        return Border(width, ColorExpr.parse(p[1]) ?: return null)
    }
}
