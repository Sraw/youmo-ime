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

/** A style property's value as read; which kind each property takes is [Values.read]'s table. */
sealed interface Value {
    data class OfFill(val fill: Fill) : Value
    data class OfColor(val color: ColorExpr) : Value
    data class OfRadius(val radius: Radius) : Value
    data class OfInset(val inset: Inset) : Value
    data class OfShadow(val shadow: Shadow) : Value
    data class OfBorder(val border: Border) : Value
    data class Word(val word: String) : Value
    data class Number(val number: Double) : Value
    data class Flag(val on: Boolean) : Value
}

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

    /** The properties a `<style>` may have. */
    val PROPERTIES = listOf(
        "fill", "overlay", "shape", "radius", "border", "shadow", "inset", "color", "font", "weight", "scale", "size",
        "hidden", "icon-color", "press", "padding",
    )

    fun read(property: String, raw: String): Value? = when (property) {
        "fill" -> fill(raw)?.let(Value::OfFill)
        "overlay", "color", "icon-color" -> ColorExpr.parse(raw)?.let(Value::OfColor)
        "shape" -> raw.takeIf { it in listOf("rect", "bar", "circle") }?.let(Value::Word)
        "radius" -> radius(raw)?.let(Value::OfRadius)
        "border" -> border(raw)?.let(Value::OfBorder)
        "shadow" -> shadow(raw)?.let(Value::OfShadow)
        "inset", "padding" -> inset(raw)?.let(Value::OfInset)
        "font" -> raw.takeIf(FONT::matches)?.let(Value::Word)
        "weight" -> raw.takeIf { it in listOf("300", "400", "700") }?.let { Value.Number(it.toDouble()) }
        "scale" -> percent(raw)?.takeIf { it in MIN_SCALE..MAX_SCALE }?.let(Value::Number)
        "size" -> SP.matchEntire(raw)?.groupValues?.get(1)?.toDouble()?.takeIf { it > 0 && it <= MAX_SP }?.let(Value::Number)
        "hidden" -> when (raw) {
            "true" -> Value.Flag(true)
            "false" -> Value.Flag(false)
            else -> null
        }
        "press" -> raw.takeIf { it in listOf("tint", "ripple", "face") }?.let(Value::Word)
        else -> null
    }

    /** `7dp` (or `0`) as dp, within ±[max]; negative only where [signed]. */
    fun dp(raw: String, max: Double = MAX_LENGTH_DP, signed: Boolean = false): Double? {
        if (!DP.matches(raw)) return null
        val v = if (raw == "0") 0.0 else raw.removeSuffix("dp").toDouble()
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
