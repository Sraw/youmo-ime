/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.Value

/**
 * The candidate bar and the expanded candidates as the theme has them (060-render-v2 §16-3, 060-render-p3
 * T6a). The app's views draw them (RecyclerViews: their scrolling, paging and screen reader are the
 * views'), so the engine works out only what they look like, plain data, from the theme's `bar`,
 * `candidate` (its `label` and `comment` parts, `:first`, `:selected`, `:pressed`), `candidates-divider`
 * and `candidates-panel`; the web previews it as [BarSample]'s scene.
 *
 * What a theme does not set is what the app drew before (theme 3.0's colours): no fill behind a candidate,
 * its words, number and comment in `candidateText`, `candidateLabel`, `candidateComment`, pressed the
 * `keyPressHighlight` over the whole of it, the lines between `divider`; selected (the physical keyboard's
 * window) `genericActiveBackground` behind it, all its text `genericActiveForeground`; the panel the
 * `panel`'s fill. Its size and spacing the app's own ([padding] null, [labelStyle] null).
 *
 * T6b's: the bar's height ([heightPercent], of the height 候选字号 gives it), the code being typed
 * ([preedit]: `preedit`) and the toolbar's buttons ([toolbar]: `toolbar`, `:pressed`, `:selected` a
 * toggle on); a theme setting none of it has them as before.
 */
data class BarLook(
    /** Under the bar: null none (the keyboard's base seen through). */
    val base: Base?,
    /** A candidate in no state; [candidate] for the others. */
    val plain: CandidateLook,
    val first: CandidateLook,
    val selected: CandidateLook,
    val firstSelected: CandidateLook,
    /** Its corners, dp (a pill: [Radius.half]), its fill's and its pressed colour's. */
    val radius: Radius,
    /** Its fill's and its pressed colour's margin in its cell, dp: the cell takes the touch whole. */
    val inset: Inset,
    /** Room each side of its text, dp (left and right; top and bottom do nothing); null: the app's, of 候选字号. */
    val padding: Inset?,
    /** Room between two candidates besides the line, dp. */
    val gapDp: Double,
    /** What stands before its words: null the place's own (the bar none, the physical keyboard's window its number). */
    val labelStyle: LabelStyle?,
    /** The line between two candidates; null none drawn (its room kept). */
    val divider: Argb?,
    /** The expanded candidates' panel; null none (the keyboard's base seen through). */
    val panel: Argb?,
    /** The bar's height in % of what 候选字号 makes it (100: that). */
    val heightPercent: Double = FULL,
    val preedit: PreeditLook,
    val toolbar: ToolbarLook,
) {
    /** A candidate's look: the [first] of the list, the [selected] one (the physical keyboard's cursor). */
    fun candidate(first: Boolean, selected: Boolean): CandidateLook = when {
        first && selected -> firstSelected
        first -> this.first
        selected -> this.selected
        else -> plain
    }

    /** What the bar's base is: a colour, or the theme's picture there ([fallback] where none is decoded). */
    sealed interface Base {
        data class Solid(val color: Argb) : Base

        data class Picture(val blurDp: Double, val brightness: Double, val over: Argb?, val fallback: Argb) : Base
    }

    /** `label-style=`: nothing before its words, its number, a dot. */
    enum class LabelStyle(val xml: String) {
        None("none"), Number("number"), Dot("dot");

        companion object {
            fun of(xml: String): LabelStyle? = entries.firstOrNull { it.xml == xml }
        }
    }

    companion object {
        /** The bar's look in [scenes] (the window's: the screen's and the settings', SceneWindow's), of [cascades]' theme. */
        fun of(cascades: Cascades, scenes: Set<String>): BarLook {
            val c = cascades.of(scenes)
            val theme = cascades.theme
            val v = c.of(CANDIDATE)
            fun item(first: Boolean, selected: Boolean) = item(c, theme, first, selected)
            return BarLook(
                base = base(c.of(BAR)["fill"], theme),
                plain = item(first = false, selected = false),
                first = item(first = true, selected = false),
                selected = item(first = false, selected = true),
                firstSelected = item(first = true, selected = true),
                radius = (v["radius"] as? Value.OfRadius)?.radius ?: Radius(List(CORNERS) { 0.0 }),
                inset = (v["inset"] as? Value.OfInset)?.inset ?: Inset(0.0, 0.0, 0.0, 0.0),
                padding = (v["padding"] as? Value.OfInset)?.inset,
                gapDp = (v["gap"] as? Value.Number)?.number ?: 0.0,
                labelStyle = (v["label-style"] as? Value.Word)?.word?.let(LabelStyle::of),
                divider = solid(c.of(DIVIDER)["fill"], theme, variable(theme, DIVIDER_COLOR)),
                panel = solid(c.of(PANEL)["fill"] ?: c.of(KEYBOARD_PANEL)["fill"], theme, null),
                heightPercent = (c.of(BAR)["height"] as? Value.Percent)?.percent ?: FULL,
                preedit = preedit(c, theme),
                toolbar = toolbar(c, theme),
            )
        }

        // the code being typed: in the keys' text colour, its cursor in its text's, the underline fcitx asks
        // for the text's own (the font's thickness)
        private fun preedit(c: Cascade, theme: Theme): PreeditLook {
            val v = c.of(PREEDIT)
            val color = color(v["color"], theme) ?: variable(theme, KEY_TEXT)
            return PreeditLook(color, color(v["cursor-color"], theme) ?: color, (v["underline"] as? Value.OfBorder)?.border?.let { b ->
                PreeditLook.Underline(b.width, b.color?.takeIf { b != Border.NONE }?.let(theme::color))
            })
        }

        // a toolbar button: no fill, a circle pressed in the key press highlight, its icon in toolbarIcon, on in
        // accentKey's colour (a toggle's)
        private fun toolbar(c: Cascade, theme: Theme): ToolbarLook {
            val v = c.of(TOOLBAR)
            val on = c.stated(TOOLBAR.copy(states = setOf(SELECTED)), SELECTED)
            val fill = solid(v["fill"], theme, null)
            return ToolbarLook(
                fill = fill,
                radius = (v["radius"] as? Value.OfRadius)?.radius,
                icon = color(v["icon-color"], theme) ?: variable(theme, TOOLBAR_ICON),
                pressed = color(c.stated(TOOLBAR.copy(states = setOf(PRESSED)), PRESSED)["overlay"], theme) ?: variable(theme, PRESS),
                onFill = if ("fill" in on) solid(on["fill"], theme, null) else fill,
                onIcon = color(on["icon-color"], theme) ?: variable(theme, ACCENT),
            )
        }

        private fun item(c: Cascade, theme: Theme, first: Boolean, selected: Boolean): CandidateLook {
            val states = setOfNotNull(FIRST.takeIf { first }, SELECTED.takeIf { selected })
            val target = Selector.Target(CANDIDATE.widget, states = states)
            val v = c.of(target)
            // selected, what a style naming `:selected` sets; else the highlight it always had
            val chosen = if (selected) c.stated(target, SELECTED) else emptyMap()
            // a part's colour its own, else the candidate's as CSS inherits it (a theme colouring its words
            // colours its number and comment too, unless it colours those), else theme 3.0's of that part
            fun part(name: String, otherwise: String): Argb {
                val p = target.copy(part = name)
                val set = if (selected) c.stated(p, SELECTED)["color"] ?: chosen["color"] else c.of(p)["color"]
                return color(set, theme) ?: variable(theme, if (selected) ACTIVE_TEXT else otherwise)
            }
            return CandidateLook(
                fill = if (selected) solid(chosen["fill"], theme, variable(theme, ACTIVE_BACKGROUND)) else solid(v["fill"], theme, null),
                pressed = color(c.stated(target.copy(states = states + PRESSED), PRESSED)["overlay"], theme) ?: variable(theme, PRESS),
                color = color(if (selected) chosen["color"] else v["color"], theme) ?: variable(theme, if (selected) ACTIVE_TEXT else TEXT),
                labelColor = part(LABEL, LABEL_COLOR),
                commentColor = part(COMMENT, COMMENT_COLOR),
                font = (v["font"] as? Value.Word)?.word,
                weight = (v["weight"] as? Value.Number)?.number?.toInt(),
            )
        }

        // the bar's base: a colour (transparent: none) or the picture; a gradient or an image none (warned of on reading)
        private fun base(v: Value?, theme: Theme): Base? = when (val f = (v as? Value.OfFill)?.fill) {
            is Fill.Solid -> theme.color(f.color).takeIf { !it.transparent }?.let(Base::Solid)
            is Fill.Picture -> Base.Picture(
                f.blurDp ?: theme.picture?.blurDp ?: 0.0,
                f.brightness ?: theme.picture?.brightness ?: FULL,
                f.over?.let(theme::color),
                variable(theme, BAR_COLOR),
            )
            else -> null
        }

        // a solid fill's colour, [otherwise] where none is set; transparent (or not a colour: warned of) none
        private fun solid(v: Value?, theme: Theme, otherwise: Argb?): Argb? {
            val f = (v as? Value.OfFill)?.fill ?: return otherwise
            return (f as? Fill.Solid)?.let { theme.color(it.color) }?.takeIf { !it.transparent }
        }

        private fun color(v: Value?, theme: Theme): Argb? = (v as? Value.OfColor)?.color?.let(theme::color)

        // one of the colours every theme has (ThemeReader.REQUIRED_COLORS)
        private fun variable(theme: Theme, name: String): Argb = theme.colors[name] ?: Argb.TRANSPARENT

        private const val CORNERS = 4
        private const val FULL = 100.0
        private const val FIRST = "first"
        private const val SELECTED = "selected"
        private const val PRESSED = "pressed"
        private const val LABEL = "label"
        private const val COMMENT = "comment"
        private const val TEXT = "candidateText"
        private const val LABEL_COLOR = "candidateLabel"
        private const val COMMENT_COLOR = "candidateComment"
        private const val ACTIVE_TEXT = "genericActiveForeground"
        private const val ACTIVE_BACKGROUND = "genericActiveBackground"
        private const val PRESS = "keyPressHighlight"
        private const val DIVIDER_COLOR = "divider"
        private const val BAR_COLOR = "bar"
        private const val KEY_TEXT = "keyText"
        private const val TOOLBAR_ICON = "toolbarIcon"
        private const val ACCENT = "accentKey"
        private val PREEDIT = Selector.Target("preedit")
        private val TOOLBAR = Selector.Target("toolbar")
        private val CANDIDATE = Selector.Target("candidate")
        private val BAR = Selector.Target("bar")
        private val DIVIDER = Selector.Target("candidates-divider")
        private val PANEL = Selector.Target("candidates-panel")

        // the app's panels' (the clipboard's, the more panel's): what the expanded candidates had before
        private val KEYBOARD_PANEL = Selector.Target("panel")
    }
}

/**
 * A candidate in one state: its [fill] (null none), the colour laid over it [pressed], its words', number's
 * and comment's colours, its [font] (a theme's `<font>`, `system`; null the app's candidate font) at [weight]
 * (null the app's).
 */
data class CandidateLook(
    val fill: Argb?,
    val pressed: Argb,
    val color: Argb,
    val labelColor: Argb,
    val commentColor: Argb,
    val font: String?,
    val weight: Int?,
)

/**
 * The code being typed (编码行 and the floating strip; 060-render-p3 T6b): its text's [color], its
 * cursor's, and the [underline] fcitx asks for under some of it: null the text's own (UnderlineSpan),
 * else of its width in dp and colour (null none drawn).
 */
data class PreeditLook(val color: Argb, val cursor: Argb, val underline: Underline?) {
    data class Underline(val widthDp: Double, val color: Argb?)
}

/**
 * A toolbar button (060-render-p3 T6b): its [fill] (null none) of its [radius] (null a circle, as ever),
 * its [icon]'s colour, [pressed] the colour over it pressed; on (a toggle's) [onFill] and [onIcon].
 */
data class ToolbarLook(
    val fill: Argb?,
    val radius: Radius?,
    val icon: Argb,
    val pressed: Argb,
    val onFill: Argb?,
    val onIcon: Argb,
)
