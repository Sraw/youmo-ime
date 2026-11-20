/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

sealed interface Fill {
    data class Solid(val color: ColorExpr) : Fill

    data class Linear(val angle: Double, val stops: List<Pair<ColorExpr, Double>>) : Fill

    /** A nine-slice; [fallback] filled where the image is missing or not loaded yet. */
    data class Image(val file: String, val insets: List<Int>, val pxPerDp: Int, val fallback: ColorExpr? = null) : Fill

    /** null: as `<picture>` has it; [over] a colour laid over it, as theme 3.0 laid the key's colour. */
    data class Picture(val blurDp: Double?, val brightness: Double?, val over: ColorExpr? = null) : Fill
}

data class Radius(val corners: List<Double>, val half: Boolean = false)

data class Inset(val top: Double, val right: Double, val bottom: Double, val left: Double)

data class Shadow(val dx: Double, val dy: Double, val blur: Double, val color: ColorExpr?) {
    companion object {
        val NONE = Shadow(0.0, 0.0, 0.0, null)
    }
}

data class Border(val width: Double, val color: ColorExpr?) {
    companion object {
        val NONE = Border(0.0, null)
    }
}

data class Twin(val portrait: Double, val landscape: Double) {
    fun of(landscape: Boolean): Double = if (landscape) this.landscape else portrait
}

sealed interface Shown {
    data object ImeName : Shown
    data object None : Shown
    data class Text(val text: String) : Shown
    data class Icon(val name: String) : Shown
}

sealed interface Value {
    data class OfShown(val shown: Shown) : Value
    data class OfPath(val path: ShapePath) : Value

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
    data class OfAppear(val appear: Appear) : Value
    data class OfEffect(val effect: Effect?) : Value
}

sealed interface Effect {
    val file: String
    val ms: Int

    data class Ripple(override val file: String, val radius: Double, override val ms: Int) : Effect

    data class Particles(override val file: String, val count: Int, val spread: Double, override val ms: Int) : Effect
}

data class Transition(val ms: Int, val easing: String)

data class Appear(val kind: String, val ms: Int)

object Values {
    private val DP = Regex("(-?[0-9]+(\\.[0-9]+)?)dp|0")
    private val SP = Regex("([0-9]+(\\.[0-9]+)?)sp")
    private val PCT = Regex("([0-9]+(\\.[0-9]+)?)%")
    // "system" is a name like any other: Kotlin/JS takes the first alternative that starts a match, so "system|…" refused "systemui"
    private val FONT = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
    private val IMAGE = Regex("image\\(([^,()]+), ?([0-9]{1,4}) ([0-9]{1,4}) ([0-9]{1,4}) ([0-9]{1,4})(?:, ?([1-8])x)?(?:, ?(.+))?\\)")
    private val PICTURE = Regex("picture(?:\\(([0-9]{1,2}(?:\\.[0-9]+)?)dp, ?([0-9]{1,3}(?:\\.[0-9]+)?)%(?:, ?(.+))?\\))?")
    private val LINEAR = Regex("linear\\((-?[0-9]{1,3}(?:\\.[0-9]+)?)deg, ?(.+)\\)")
    internal const val MAX_LENGTH_DP = 24.0
    internal const val MAX_OFFSET_DP = 16.0
    internal const val MAX_BORDER_DP = 3.0
    internal const val MIN_SCALE = 85.0
    internal const val MAX_SCALE = 130.0
    private const val MAX_SP = 48.0
    internal const val MAX_BLUR_DP = 25.0
    private const val MAX_PERCENT = 100.0
    private const val FOUR = 4
    internal const val MAX_DEPTH_DP = 8.0
    private const val MIN_BUBBLE = 100.0
    private const val MAX_BUBBLE = 160.0
    internal const val MIN_SPACING_EM = -0.1
    internal const val MAX_SPACING_EM = 0.5
    internal const val MIN_STOPS = 2
    internal const val MAX_STOPS = 8
    private val EM = Regex("-?[0-9](\\.[0-9]{1,3})?em")
    private val ICON_SHOWN = Regex("icon\\(([a-z0-9]+(-[a-z0-9]+)*)\\)")
    private val TEXT_SHOWN = Regex("text\\(([^()]{1,32})\\)")
    const val RETURN_ICON = "return-icon-"

    val RETURN_ACTIONS = listOf("send", "search", "go", "next", "done", "newline")

    val PROPERTIES = listOf(
        "fill", "overlay", "shape", "radius", "border", "shadow", "inset", "color", "font", "weight", "scale", "size",
        "hidden", "icon-color", "press", "padding",
        "align", "letter-spacing", "stroke", "text-shadow", "font-han", "font-latin", "space-content",
        "inner-shadow", "depth", "depth-color", "tail", "offset",
        "gap", "label-style",
        "underline", "cursor-color",
        "blur", "ink-color", "ink-width", "grid-color", "grid",
        "transition", "press-scale", "press-sink", "effect",
        "appear",
    ) + RETURN_ACTIONS.map { "$RETURN_ICON$it" }

    private const val LEN = "(-?[0-9]+(\\.[0-9]+)?dp|0)"
    private const val PERCENT_FORM = "[0-9]+(\\.[0-9]+)?%"
    private const val LEN_OR_PERCENT = "($LEN|$PERCENT_FORM)( ($LEN|$PERCENT_FORM))?"
    private const val MS = "[0-9]{1,3}ms"

    // ASCII white space spelt out: `\s` is more on JS than on the JVM, and a file must read the same on both
    private const val FILE = "[^,()/\\\\:*?\"<>| \\t\\n\\r\\f\\x0B]{1,128}"

    fun interface Reserved {
        fun read(raw: String): List<ColorExpr>?
    }

    // anchored: Kotlin/JS matches a whole value by the first alternative that starts one, so "ease" would end "ease-out"
    private fun form(pattern: String): Reserved = Regex("(?:$pattern)$").let { r -> Reserved { if (r.matches(it)) emptyList() else null } }

    /** Later-version properties: read where well-formed, ignored with a warning, so a newer app's theme still imports. */
    val RESERVED: Map<String, Reserved> = mapOf(
        "height" to form(LEN_OR_PERCENT),
        "width" to form(LEN),
        "side-padding" to form(LEN_OR_PERCENT),
        "bottom-padding" to form(LEN_OR_PERCENT),
        "sticker" to form("none|$FILE"),
    )

    /** Set on `keyboard` alone, under no scene; the same names on the handle are its grip's, the bar's `height` its own. */
    val KEYBOARD_SIZES = listOf("height", "side-padding", "bottom-padding")

    // at least the settings' 紧凑 in portrait: a theme follows it on import, and a lower keyboard is not typable
    private const val MIN_HEIGHT = 20.0
    private const val MAX_HEIGHT = 90.0
    private const val MAX_SIDE_DP = 300.0
    private const val MAX_BOTTOM_DP = 100.0

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

    val HANDLE_SIZES = listOf("width", "height")
    const val MAX_GRIP_WIDTH_DP = 160.0
    const val MAX_GRIP_HEIGHT_DP = 24.0

    fun gripSize(property: String, raw: String): Double? = dp(raw, if (property == "width") MAX_GRIP_WIDTH_DP else MAX_GRIP_HEIGHT_DP)

    const val FOLLOW = "follow"

    fun read(property: String, raw: String): Value? = when (property) {
        "fill" -> fill(raw)?.let(Value::OfFill)
        "overlay", "color", "icon-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "shape" -> shape(raw)
        "radius" -> radius(raw)?.let(Value::OfRadius)
        "border" -> border(raw)?.let(Value::OfBorder)
        "shadow" -> shadow(raw)?.let(Value::OfShadow)
        "inset", "padding" -> inset(raw)?.let(Value::OfInset)
        "scale" -> percent(raw)?.takeIf { it in MIN_SCALE..MAX_SCALE }?.let(Value::Number)
        "size" -> size(raw)
        "hidden" -> flag(raw)
        "gap" -> dp(raw)?.let(Value::Number)
        else -> word(property, raw) ?: textOnAKey(property, raw) ?: capAndBubble(property, raw) ?: panel(property, raw) ?: preedit(property, raw)
            ?: motion(property, raw)
    }

    private fun word(property: String, raw: String): Value? = when (property) {
        "font" -> raw.takeIf(FONT::matches)?.let(Value::Word)
        "weight" -> raw.takeIf { it in listOf("300", "400", "700") }?.let { Value.Number(it.toDouble()) }
        "press" -> raw.takeIf { it in listOf("tint", "ripple", "face") }?.let(Value::Word)
        "label-style" -> raw.takeIf { it in LABEL_STYLES }?.let(Value::Word)
        else -> null
    }

    val EASINGS = listOf("linear", "ease", "ease-in", "ease-out", "ease-in-out")
    const val MAX_TRANSITION_MS = 600
    const val MIN_PRESS_SCALE = 85.0
    const val MAX_PRESS_SCALE = 110.0
    const val MAX_PRESS_SINK_DP = 4.0
    private val TRANSITION = Regex("([0-9]{1,3})ms ([a-z-]+)")

    val APPEARS = listOf("fade", "scale", "rise")
    const val MAX_APPEAR_MS = 250
    private val APPEAR = Regex("(${APPEARS.joinToString("|")}) ([0-9]{1,3})ms")

    private fun motion(property: String, raw: String): Value? = when (property) {
        "transition" -> TRANSITION.matchEntire(raw)?.let { m ->
            val ms = m.groupValues[1].toInt()
            val easing = m.groupValues[2]
            if (ms <= MAX_TRANSITION_MS && easing in EASINGS) Value.OfTransition(Transition(ms, easing)) else null
        }
        "press-scale" -> percent(raw)?.takeIf { it in MIN_PRESS_SCALE..MAX_PRESS_SCALE }?.let(Value::Number)
        "press-sink" -> dp(raw, MAX_PRESS_SINK_DP)?.let(Value::Number)
        "appear" -> APPEAR.matchEntire(raw)?.let { m ->
            m.groupValues[2].toInt().takeIf { it <= MAX_APPEAR_MS }?.let { Value.OfAppear(Appear(m.groupValues[1], it)) }
        }
        "effect" -> effect(raw)
        else -> null
    }

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

    private fun preedit(property: String, raw: String): Value? = when (property) {
        "underline" -> border(raw)?.let(Value::OfBorder)
        "cursor-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        else -> null
    }

    const val MAX_PANEL_BLUR_DP = 25.0

    const val MIN_INK_DP = 1.0
    const val MAX_INK_DP = 8.0

    private fun panel(property: String, raw: String): Value? = when (property) {
        "blur" -> dp(raw, MAX_PANEL_BLUR_DP)?.let(Value::Number)
        "ink-width" -> dp(raw, MAX_INK_DP)?.takeIf { it >= MIN_INK_DP }?.let(Value::Number)
        "ink-color", "grid-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "grid" -> flag(raw)
        else -> null
    }

    const val MIN_BAR_HEIGHT = 80.0
    const val MAX_BAR_HEIGHT = 140.0

    fun barHeight(raw: String): Double? = percent(raw)?.takeIf { it in MIN_BAR_HEIGHT..MAX_BAR_HEIGHT }

    val LABEL_STYLES = listOf("none", "number", "dot")

    private fun size(raw: String): Value? =
        SP.matchEntire(raw)?.groupValues?.get(1)?.toDouble()?.takeIf { it > 0 && it <= MAX_SP }?.let(Value::Number)
            ?: percent(raw)?.takeIf { it in MIN_BUBBLE..MAX_BUBBLE }?.let(Value::Percent)

    private fun flag(raw: String): Value? = when (raw) {
        "true" -> Value.Flag(true)
        "false" -> Value.Flag(false)
        else -> null
    }

    private fun shape(raw: String): Value? = when {
        raw in listOf("rect", "bar", "circle", BUBBLE) -> Value.Word(raw)
        raw.startsWith("path(") && raw.endsWith(")") -> ShapePath.parse(raw.removeSurrounding("path(", ")"))?.let(Value::OfPath)
        else -> null
    }

    private fun capAndBubble(property: String, raw: String): Value? = when (property) {
        "inner-shadow" -> shadow(raw)?.let(Value::OfShadow)
        "depth" -> dp(raw, MAX_DEPTH_DP)?.let(Value::Number)
        "depth-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "tail" -> flag(raw)
        "offset" -> dp(raw, MAX_LENGTH_DP, signed = true)?.let(Value::Number)
        else -> null
    }

    const val BUBBLE = "bubble"

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

    const val CENTER = "center"
    const val TOP_LEFT = "top-left"
    const val TOP_CENTER = "top-center"

    private fun shown(raw: String): Shown? =
        ICON_SHOWN.matchEntire(raw)?.let { Shown.Icon(it.groupValues[1]) } ?: TEXT_SHOWN.matchEntire(raw)?.let { Shown.Text(it.groupValues[1]) }

    fun stroke(raw: String): Border? {
        val p = raw.split(' ', limit = 2)
        if (p.size != 2) return null
        val width = dp(p[0], MAX_BORDER_DP) ?: return null
        return Border(width, ColorExpr.parse(p[1]) ?: return null)
    }

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
            val file = g[1].trim().takeIf(ThemeReader.FILE::matches) ?: return null
            return Fill.Image(file, (2..5).map { g[it].toInt() }, g[6].ifEmpty { "1" }.toInt(), fallback)
        }
        PICTURE.matchEntire(raw)?.let { m ->
            val blur = m.groupValues[1].ifEmpty { null }?.toDouble()
            val bright = m.groupValues[2].ifEmpty { null }?.toDouble()
            val tooBlurred = blur != null && blur > MAX_BLUR_DP
            if (tooBlurred || bright != null && bright > MAX_PERCENT) return null
            val over = m.groupValues[3].ifEmpty { null }?.let { ColorExpr.parse(it.trim()) ?: return null }
            return Fill.Picture(blur, bright, over)
        }
        LINEAR.matchEntire(raw)?.let { m ->
            val stops = ColorExpr.splitTop(m.groupValues[2])?.map { stop ->
                val t = stop.trim()
                val color = ColorExpr.parse(t.substringBeforeLast(' ')) ?: return null
                val at = percent(t.substringAfterLast(' '))?.takeIf { it <= MAX_PERCENT } ?: return null
                color to at
            } ?: return null
            return if (stops.size in MIN_STOPS..MAX_STOPS) Fill.Linear(m.groupValues[1].toDouble(), stops) else null
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
