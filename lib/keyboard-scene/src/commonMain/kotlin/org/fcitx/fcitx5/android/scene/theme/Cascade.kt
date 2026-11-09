/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A theme's styles laid over each other for one target, as CSS would (060-render-v2 §5): every style
 * one of whose selectors matches, the less specific first, ties in the order written; each property
 * the last one's. Property by property, so a key given only a fill by name keeps its kind's pressed
 * look: theme 3.0's KeyStyleResolver (rules B §4) is the case of `board/key` over `*`/role over kind.
 *
 * A key's parts (label, mark…) inherit what text is drawn in from the key ([INHERITED]) where no style
 * of the part sets it: `key.enter { color }` colours its label, not its mark, which `key mark` sets.
 */
class Cascade(theme: Theme, private val scene: Set<String>) {
    private data class Bucket(val widget: String, val part: String?, val key: String?)

    // the selectors that can hold here (their scenes this one's), by what a target must be to be selected:
    // a key is tried against its widget's and part's, and those naming it, not a theme's every style
    // (their scenes checked here, once, not again a target)
    private val index: Map<Bucket, List<Pair<Selector, Style>>> = theme.styles
        .flatMap { style -> style.selectors.filter { scene.containsAll(it.scenes) }.map { it to style } }
        .groupBy { (sel, _) -> Bucket(sel.widget, sel.part, sel.key) }

    /**
     * The properties set for [target]: a widget, a key, or a key's part, which inherits from the key.
     * Kept: the same target asked again (each key's every part in a state, every scene of a theme) is
     * the map made the first time, the same map to every caller: read only, never changed.
     */
    fun of(target: Selector.Target): Map<String, Value> {
        val general = general(target)
        looked[general]?.let { return it }
        val own = own(general)
        val out = if (general.part == null) own else of(general.copy(part = null)).filterKeys { it in INHERITED } + own
        looked[general] = out
        return out
    }

    // a target with a key named by none of the selectors here nameless: such a key is selected only as
    // any key of its kind, class and state, so the 26 letters are one entry, not 26
    private fun general(target: Selector.Target) =
        if (target.key == null || target.key in named) target else target.copy(key = null)

    private val looked = HashMap<Selector.Target, Map<String, Value>>()

    // the keys a selector here names
    private val named: Set<String> = index.keys.mapNotNullTo(HashSet()) { it.key }

    private val keptLooks = HashMap<KeptKey<*>, Any>()

    /** What [kept] keeps a [T] under: equal only where what is made for it would be. */
    interface KeptKey<T : Any>

    /**
     * What [make] works out of this cascade's looks for [key], made the first time and the same after:
     * a builder's looks of a key, by what they depend on. The key's type names what is kept with it, so
     * nothing else is ever found under it.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> kept(key: KeptKey<T>, make: () -> T): T = keptLooks.getOrPut(key, make) as T

    // what the styles selecting [target] set, nothing inherited
    private fun own(target: Selector.Target): Map<String, Value> {
        val best = HashMap<Style, Int>()
        fun from(bucket: Bucket) = index[bucket]?.forEach { (sel, style) ->
            if (sel.selects(target) && sel.specificity > (best[style] ?: -1)) best[style] = sel.specificity
        }
        from(Bucket(target.widget, target.part, null))
        if (target.key != null) from(Bucket(target.widget, target.part, target.key))
        val out = LinkedHashMap<String, Value>()
        best.entries.sortedWith(compareBy({ it.value }, { it.key.order })).forEach { out.putAll(it.key.values) }
        return out
    }

    companion object {
        /** What a part takes from its key unless a style of its own sets it. */
        val INHERITED = setOf("color", "icon-color", "font", "weight", "scale", "hidden")
    }
}

/**
 * A theme's cascades by the scenes they are in, kept: what a cascade has worked out (its [Cascade.of]s)
 * depends on nothing else, so the scenes made again of one theme (Shift, Return, a key typed) find
 * their keys' looks already there. One thread's, as the builder is.
 *
 * Kept by those of the scenes the theme names: a scene none of its selectors asks of changes nothing
 * a cascade works out, so the input method, the field and the board (`im=` `field=` `keyboard=`,
 * always in [org.fcitx.fcitx5.android.scene.engine.Env.scenes]) make no more cascades of a theme that
 * names none of them than before; one that does, one more for each it names (060-render-p3 T5: at
 * most 8 a board, CascadesTest).
 */
class Cascades(val theme: Theme) {
    private val made = HashMap<Set<String>, Cascade>()

    // the scenes some selector of the theme asks of
    private val named: Set<String> = theme.styles.flatMapTo(HashSet()) { style -> style.selectors.flatMap { it.scenes } }

    fun of(scene: Set<String>): Cascade {
        val asked = if (named.containsAll(scene)) scene else scene.filterTo(HashSet()) { it in named }
        return made.getOrPut(asked) { Cascade(theme, asked) }
    }

    /** How many cascades are kept: what a test measures. */
    val size: Int get() = made.size
}

/** A key's look in one state, from its [Cascade]d properties: defaults for what no style sets. */
class KeyLook(private val v: Map<String, Value>, private val theme: Theme) {
    val fill: Fill? get() = (v["fill"] as? Value.OfFill)?.fill
    val overlay: Argb? get() = (v["overlay"] as? Value.OfColor)?.color?.let(theme::color)
    val shape: String get() = word("shape") ?: "rect"
    val radius: Radius get() = (v["radius"] as? Value.OfRadius)?.radius ?: Radius(List(CORNERS) { 0.0 })
    val border: Border get() = (v["border"] as? Value.OfBorder)?.border ?: Border.NONE
    val shadow: Shadow get() = (v["shadow"] as? Value.OfShadow)?.shadow ?: Shadow.NONE
    val inset: Inset get() = (v["inset"] as? Value.OfInset)?.inset ?: Inset(0.0, 0.0, 0.0, 0.0)
    val press: String get() = word("press") ?: "tint"

    private fun word(name: String): String? = (v[name] as? Value.Word)?.word

    private companion object {
        const val CORNERS = 4
    }
}

/** How a key's text or icon (a part, or the key itself) is drawn, from its [Cascade]d properties. */
class TextLook(private val v: Map<String, Value>, private val theme: Theme) {
    val color: Argb get() = (v["color"] as? Value.OfColor)?.color?.let(theme::color) ?: BLACK
    val iconColor: Argb get() = (v["icon-color"] as? Value.OfColor)?.color?.let(theme::color) ?: color
    val font: String get() = (v["font"] as? Value.Word)?.word ?: "system"
    val weight: Int get() = (v["weight"] as? Value.Number)?.number?.toInt() ?: NORMAL
    val scale: Double get() = ((v["scale"] as? Value.Number)?.number ?: PERCENT) / PERCENT
    val hidden: Boolean get() = (v["hidden"] as? Value.Flag)?.on ?: false

    private companion object {
        val BLACK = Argb(0xFF000000.toInt())
        const val NORMAL = 400
        const val PERCENT = 100.0
    }
}
