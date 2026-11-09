/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A theme's styles laid over each other for one target, as CSS would (060-render-v2 §5): every style
 * one of whose selectors matches, the less specific first, ties in the order written; each property
 * the last one's. Property by property, so a key given only a fill by name keeps what else its kind
 * sets (its colours, its press), but not its kind's pressed face: that is a fill too, of `.kind:pressed`,
 * which the name's outranks. Theme 3.0's KeyStyleResolver (rules B §4) took a key's pressed face from
 * its kind where the key had none of its own; LookTheme gives such a key its kind's pressed face by
 * its name.
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
        val out = if (general.part == null) {
            own
        } else {
            of(general.copy(part = null)).filterKeys { it in INHERITED && !(it == HIDDEN && general.part == HINT) } + own
        }
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

    /**
     * What only the styles selecting [target] by a selector naming [state] set, laid over each other as
     * [of]'s are: a long press's key in focus takes its border from `popup:selected`, not the panel's `popup`.
     * Not kept: asked once a popup.
     */
    fun stated(target: Selector.Target, state: String): Map<String, Value> = own(general(target)) { state in it.states }

    // what the styles selecting [target] set (by a selector [by] takes), nothing inherited
    private fun own(target: Selector.Target, by: (Selector) -> Boolean = { true }): Map<String, Value> {
        val best = HashMap<Style, Int>()
        fun from(bucket: Bucket) = index[bucket]?.forEach { (sel, style) ->
            if (by(sel) && sel.selects(target) && sel.specificity > (best[style] ?: -1)) best[style] = sel.specificity
        }
        from(Bucket(target.widget, target.part, null))
        if (target.key != null) from(Bucket(target.widget, target.part, target.key))
        val out = LinkedHashMap<String, Value>()
        best.entries.sortedWith(compareBy({ it.value }, { it.key.order })).forEach { out.putAll(it.key.values) }
        return out
    }

    companion object {
        /**
         * What a part takes from its key unless a style of its own sets it: how its text is drawn, as CSS's
         * text properties are inherited (`key { stroke }` outlines the label and the marks), and what the
         * key shows (`space-content` set on the space as on its label).
         */
        val INHERITED = setOf(
            "color", "icon-color", "font", "weight", "scale", "hidden",
            "align", "letter-spacing", "stroke", "text-shadow", "font-han", "font-latin", "space-content",
        ) + Values.RETURN_ACTIONS.map { "return-icon-$it" }

        // a key's `hidden` hides its label, icon and symbol, never what its swipes do (rules B §5: the
        // shortcuts stay): the hint hidden only by a style of its own (`key hint { hidden }`)
        private const val HIDDEN = "hidden"
        private const val HINT = "hint"
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
 * names none of them than before. One that names some has one for each set of them met together,
 * so as many as it names allow (every input method, field and board it names, light and dark, ...):
 * the last [KEPT] used kept, the least used let go first (060-render-p3 T5, ThemeTest). A cascade
 * let go is made again as asked, its looks worked out again.
 */
class Cascades(val theme: Theme) {
    // in the order last used: the first is let go first
    private val made = LinkedHashMap<Set<String>, Cascade>()

    // the scenes some selector of the theme asks of
    private val named: Set<String> = theme.styles.flatMapTo(HashSet()) { style -> style.selectors.flatMap { it.scenes } }

    fun of(scene: Set<String>): Cascade {
        val asked = if (named.containsAll(scene)) scene else scene.filterTo(HashSet()) { it in named }
        // put last again: the order of use
        val c = made.remove(asked) ?: Cascade(theme, asked)
        made[asked] = c
        if (made.size > KEPT) made.remove(made.keys.first())
        return c
    }

    /** How many cascades are kept: what a test measures. */
    val size: Int get() = made.size

    companion object {
        /** The most kept of a theme: one of no scenes has one, one of `@dark` two. */
        const val KEPT = 32
    }
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

    /** `shape="path(…)"`: the outline its face is (radius not applying); null: [shape]'s. */
    val path: ShapePath? get() = (v["shape"] as? Value.OfPath)?.path

    /** A shadow inside its face (`inner-shadow`), dp; none where not set or `none`. */
    val innerShadow: Shadow? get() = (v["inner-shadow"] as? Value.OfShadow)?.shadow?.takeIf { it.color != null }

    /** How thick its cap is (`depth`), dp: a band of [depthColor] under its face, the face that much up. */
    val depth: Double get() = (v["depth"] as? Value.Number)?.number ?: 0.0

    /** The band's colour: `depth-color`, else its fill's 20% darker, else a translucent black. */
    val depthColor: Argb
        get() = (v["depth-color"] as? Value.OfColor)?.color?.let(theme::color)
            ?: (fill as? Fill.Solid)?.let { theme.color(ColorExpr.Fn("darker", listOf(it.color), DEPTH_DARKER)) }
            ?: DEPTH_BLACK

    /** The bubble's: a tail down to the key (`tail`; a `bubble`'s by default), its size (`size`, 1–1.6), moved down (`offset`, dp). */
    val tail: Boolean get() = (v["tail"] as? Value.Flag)?.on ?: true
    val size: Double get() = ((v["size"] as? Value.Percent)?.percent ?: PERCENT) / PERCENT
    val offset: Double get() = (v["offset"] as? Value.Number)?.number ?: 0.0

    /** How a change into this look takes (`transition`, A1); null: at once, as ever. */
    val transition: Transition? get() = (v["transition"] as? Value.OfTransition)?.transition

    /** A pressed face's scale (`press-scale`, 1 none) and how far it sinks (`press-sink`, dp). */
    val pressScale: Double get() = ((v["press-scale"] as? Value.Number)?.number ?: PERCENT) / PERCENT
    val pressSink: Double get() = (v["press-sink"] as? Value.Number)?.number ?: 0.0

    private fun word(name: String): String? = (v[name] as? Value.Word)?.word

    private companion object {
        const val CORNERS = 4
        const val DEPTH_DARKER = 20.0
        const val PERCENT = 100.0
        val DEPTH_BLACK = Argb(0x40000000)
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

    /** Where the key's glyph is on its face ([Values.CENTER], as ever, [Values.TOP_LEFT], [Values.TOP_CENTER]). */
    val align: String get() = (v["align"] as? Value.Word)?.word ?: Values.CENTER

    /** Space added after each character, em (Paint.letterSpacing's). */
    val letterSpacing: Double get() = (v["letter-spacing"] as? Value.Number)?.number ?: 0.0

    /** The font of its Han characters, and of its Latin ones ([Scripts]); null: [font]'s. */
    val fontHan: String? get() = (v["font-han"] as? Value.Word)?.word
    val fontLatin: String? get() = (v["font-latin"] as? Value.Word)?.word

    /** An outline round its glyphs: its width (dp) and colour; none where not set or of no width. */
    val stroke: Border? get() = (v["stroke"] as? Value.OfBorder)?.border?.takeIf { it.width > 0 && it.color != null }

    /** A shadow under its text, dp; none where not set or `none`. */
    val textShadow: Shadow? get() = (v["text-shadow"] as? Value.OfShadow)?.shadow?.takeIf { it.color != null }

    /** What the space shows instead of the layout's label (`space-content`); null: the layout's. */
    val spaceContent: Shown? get() = (v["space-content"] as? Value.OfShown)?.shown

    /** What Return shows for [action] (`return-icon-<action>`, [Values.RETURN_ACTIONS]) instead of its icon; null: its icon. */
    fun returnIcon(action: String): Shown? = (v["return-icon-$action"] as? Value.OfShown)?.shown

    private companion object {
        val BLACK = Argb(0xFF000000.toInt())
        const val NORMAL = 400
        const val PERCENT = 100.0
    }
}
