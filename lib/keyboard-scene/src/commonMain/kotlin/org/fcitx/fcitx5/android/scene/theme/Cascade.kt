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
class Cascade(private val theme: Theme, private val scene: Set<String>) {

    /** The properties set for [target]: a widget, a key, or a key's part (inheriting from the key). */
    fun of(target: Selector.Target): Map<String, Value> {
        val own = own(target)
        if (target.part == null) return own
        val key = own(target.copy(part = null))
        return key.filterKeys { it in INHERITED } + own
    }

    private fun own(target: Selector.Target): Map<String, Value> {
        val matched = ArrayList<Pair<Int, Style>>()
        for (style in theme.styles) {
            val best = style.selectors.filter { it.matches(target, scene) }.maxOfOrNull { it.specificity } ?: continue
            matched += best to style
        }
        val out = LinkedHashMap<String, Value>()
        matched.sortedWith(compareBy({ it.first }, { it.second.order })).forEach { out.putAll(it.second.values) }
        return out
    }

    companion object {
        /** What a part takes from its key unless a style of its own sets it. */
        val INHERITED = setOf("color", "icon-color", "font", "weight", "scale", "hidden")
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
