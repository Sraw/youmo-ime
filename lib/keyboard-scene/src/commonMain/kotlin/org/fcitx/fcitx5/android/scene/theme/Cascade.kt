/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A theme's styles laid over each other for one target, as CSS would, property by property: a key given only a fill by
 * name loses its kind's pressed face, a fill of `.kind:pressed` the name outranks (LookTheme gives it back by name).
 */
class Cascade(theme: Theme, private val scene: Set<String>) {
    private data class Bucket(val widget: String, val part: String?, val key: String?)

    // scenes checked once here, not per target
    private val index: Map<Bucket, List<Pair<Selector, Style>>> = theme.styles
        .flatMap { style -> style.selectors.filter { scene.containsAll(it.scenes) }.map { it to style } }
        .groupBy { (sel, _) -> Bucket(sel.widget, sel.part, sel.key) }

    /** Cached and shared by every caller: read only. */
    fun of(target: Selector.Target): Map<String, Value> {
        val general = general(target)
        looked[general]?.let { return it }
        val own = own(general)
        val out = if (general.part == null) {
            own
        } else {
            of(general.copy(part = null)).filterKeys { it in INHERITED && !(it == HIDDEN && general.part == HINT) } + own
        }
        looked[general] = out
        return out
    }

    // so the 26 letters are one cache entry, not 26
    private fun general(target: Selector.Target) =
        if (target.key == null || target.key in named) target else target.copy(key = null)

    private val looked = HashMap<Selector.Target, Map<String, Value>>()

    private val named: Set<String> = index.keys.mapNotNullTo(HashSet()) { it.key }

    private val keptLooks = HashMap<KeptKey<*>, Any>()

    interface KeptKey<T : Any>

    /** The key's type names what is kept with it, so nothing else is ever found under it. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> kept(key: KeptKey<T>, make: () -> T): T = keptLooks.getOrPut(key, make) as T

    /** A long press's key in focus takes its border from `popup:selected`, not the panel's `popup`. Not cached. */
    fun stated(target: Selector.Target, state: String): Map<String, Value> = own(general(target)) { state in it.states }

    fun sources(target: Selector.Target): Map<String, Style> {
        val general = general(target)
        val own = winners(general).flatMap { s -> s.values.keys.map { it to s } }.toMap()
        if (general.part == null) return own
        val key = sources(general.copy(part = null)).filterKeys { it in INHERITED && !(it == HIDDEN && general.part == HINT) }
        return key + own
    }

    private fun own(target: Selector.Target, by: (Selector) -> Boolean = { true }): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        winners(target, by).forEach { out.putAll(it.values) }
        return out
    }

    private fun winners(target: Selector.Target, by: (Selector) -> Boolean = { true }): List<Style> {
        val best = HashMap<Style, Int>()
        fun from(bucket: Bucket) = index[bucket]?.forEach { (sel, style) ->
            if (by(sel) && sel.selects(target) && sel.specificity > (best[style] ?: -1)) best[style] = sel.specificity
        }
        from(Bucket(target.widget, target.part, null))
        if (target.key != null) from(Bucket(target.widget, target.part, target.key))
        return best.entries.sortedWith(compareBy({ it.value }, { it.key.order })).map { it.key }
    }

    companion object {
        val INHERITED = setOf(
            "color", "icon-color", "font", "weight", "scale", "hidden",
            "align", "letter-spacing", "stroke", "text-shadow", "font-han", "font-latin", "space-content",
        ) + Values.RETURN_ACTIONS.map { "return-icon-$it" }

        // a key's `hidden` never hides its swipes' shortcuts; the hint only by its own style
        private const val HIDDEN = "hidden"
        private const val HINT = "hint"
    }
}

/**
 * A theme's cascades by scene, keyed by only the scenes the theme names, the least recently used dropped first.
 * Single-threaded, as the builder is.
 */
class Cascades(val theme: Theme) {
    private val made = LinkedHashMap<Set<String>, Cascade>()

    private val named: Set<String> = theme.styles.flatMapTo(HashSet()) { style -> style.selectors.flatMap { it.scenes } }

    fun of(scene: Set<String>): Cascade {
        val asked = if (named.containsAll(scene)) scene else scene.filterTo(HashSet()) { it in named }
        val c = made.remove(asked) ?: Cascade(theme, asked)
        made[asked] = c
        if (made.size > KEPT) made.remove(made.keys.first())
        return c
    }

    val size: Int get() = made.size

    companion object {
        const val KEPT = 32
    }
}

class KeyLook(private val v: Map<String, Value>, private val theme: Theme) {
    val fill: Fill? get() = (v["fill"] as? Value.OfFill)?.fill
    val overlay: Argb? get() = (v["overlay"] as? Value.OfColor)?.color?.let(theme::color)
    val shape: String get() = word("shape") ?: "rect"
    val radius: Radius get() = (v["radius"] as? Value.OfRadius)?.radius ?: Radius(List(CORNERS) { 0.0 })
    val border: Border get() = (v["border"] as? Value.OfBorder)?.border ?: Border.NONE
    val shadow: Shadow get() = (v["shadow"] as? Value.OfShadow)?.shadow ?: Shadow.NONE
    val inset: Inset get() = (v["inset"] as? Value.OfInset)?.inset ?: Inset(0.0, 0.0, 0.0, 0.0)
    val press: String get() = word("press") ?: "tint"

    val path: ShapePath? get() = (v["shape"] as? Value.OfPath)?.path

    val innerShadow: Shadow? get() = (v["inner-shadow"] as? Value.OfShadow)?.shadow?.takeIf { it.color != null }

    val depth: Double get() = (v["depth"] as? Value.Number)?.number ?: 0.0

    val depthColor: Argb
        get() = (v["depth-color"] as? Value.OfColor)?.color?.let(theme::color)
            ?: (fill as? Fill.Solid)?.let { theme.color(ColorExpr.Fn("darker", listOf(it.color), DEPTH_DARKER)) }
            ?: DEPTH_BLACK

    val tail: Boolean get() = (v["tail"] as? Value.Flag)?.on ?: true
    val size: Double get() = ((v["size"] as? Value.Percent)?.percent ?: PERCENT) / PERCENT
    val offset: Double get() = (v["offset"] as? Value.Number)?.number ?: 0.0

    val transition: Transition? get() = (v["transition"] as? Value.OfTransition)?.transition

    val appear: Appear? get() = (v["appear"] as? Value.OfAppear)?.appear

    val pressScale: Double get() = ((v["press-scale"] as? Value.Number)?.number ?: PERCENT) / PERCENT
    val pressSink: Double get() = (v["press-sink"] as? Value.Number)?.number ?: 0.0

    val effect: Effect? get() = (v["effect"] as? Value.OfEffect)?.effect

    private fun word(name: String): String? = (v[name] as? Value.Word)?.word

    private companion object {
        const val CORNERS = 4
        const val DEPTH_DARKER = 20.0
        const val PERCENT = 100.0
        val DEPTH_BLACK = Argb(0x40000000)
    }
}

class TextLook(private val v: Map<String, Value>, private val theme: Theme) {
    val color: Argb get() = (v["color"] as? Value.OfColor)?.color?.let(theme::color) ?: BLACK
    val iconColor: Argb get() = (v["icon-color"] as? Value.OfColor)?.color?.let(theme::color) ?: color
    val font: String get() = (v["font"] as? Value.Word)?.word ?: "system"
    val weight: Int get() = (v["weight"] as? Value.Number)?.number?.toInt() ?: NORMAL
    val scale: Double get() = ((v["scale"] as? Value.Number)?.number ?: PERCENT) / PERCENT
    val hidden: Boolean get() = (v["hidden"] as? Value.Flag)?.on ?: false

    val align: String get() = (v["align"] as? Value.Word)?.word ?: Values.CENTER

    val letterSpacing: Double get() = (v["letter-spacing"] as? Value.Number)?.number ?: 0.0

    val fontHan: String? get() = (v["font-han"] as? Value.Word)?.word
    val fontLatin: String? get() = (v["font-latin"] as? Value.Word)?.word

    val stroke: Border? get() = (v["stroke"] as? Value.OfBorder)?.border?.takeIf { it.width > 0 && it.color != null }

    val textShadow: Shadow? get() = (v["text-shadow"] as? Value.OfShadow)?.shadow?.takeIf { it.color != null }

    val spaceContent: Shown? get() = (v["space-content"] as? Value.OfShown)?.shown

    fun returnIcon(action: String): Shown? = (v["return-icon-$action"] as? Value.OfShown)?.shown

    private companion object {
        val BLACK = Argb(0xFF000000.toInt())
        const val NORMAL = 400
        const val PERCENT = 100.0
    }
}
