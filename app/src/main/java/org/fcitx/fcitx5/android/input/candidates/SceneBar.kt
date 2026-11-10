/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.ThemePictureDrawable
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneWindow
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.engine.CandidateLook
import org.fcitx.fcitx5.android.scene.engine.PreeditLook
import org.fcitx.fcitx5.android.scene.engine.ToolbarLook
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable

/**
 * The candidate bar and the expanded candidates as the theme in use has them (060-render-p3 T6a,
 * [BarLook]): the views draw them, each candidate's fill, pressed colour, colours and font, the room
 * round it and the lines between, the bar's base and the panel's. A theme that sets none of it, a theme
 * 3.0's among them, has the engine's defaults: the bar as the app drew it before. A look that made no
 * theme (SceneThemes logs it) has that bar of before worked out here, of its colours ([plain]).
 */
object SceneBar {

    /**
     * The bar's look in the window's scenes (SceneWindow's: landscape, compact, the flat keys, dark; not
     * floating, the bar the same over a floating keyboard and a docked one).
     */
    fun of(context: Context, theme: Theme): BarLook {
        val look = ThemeManager.shownLook(theme)
        val file = SceneThemes.of(theme, look)
        val scenes = SceneWindow.scenes(context, theme)
        // asked for by each tool button as it is made (T6b review): worked out once while the same
        shownBar?.takeIf { it.isFor(theme, look, file, scenes) }?.let { return it.bar }
        val bar = file?.let { BarLook.of(SceneThemes.cascades(it), scenes) } ?: plain(theme)
        shownBar = ShownBar(theme, look, file, scenes, bar)
        return bar
    }

    // the last bar worked out ([of]) and what it was of: the theme, its look and file (another made anew, another), the scenes
    private class ShownBar(val theme: Theme, val shown: ThemeLook, val file: SceneTheme?, val scenes: Set<String>, val bar: BarLook) {
        fun isFor(theme: Theme, shown: ThemeLook, file: SceneTheme?, scenes: Set<String>) =
            this.theme === theme && this.shown === shown && this.file === file && this.scenes == scenes
    }

    private var shownBar: ShownBar? = null

    /** The bar as the app drew it of theme 3.0's colours: what a theme setting none of it has. */
    fun plain(theme: Theme): BarLook {
        val look = ThemeManager.shownLook(theme)
        val press = Argb(theme.keyPressHighlightColor)
        // the candidates' font as LookTheme writes it into the theme
        val font = LookTheme.candidateFont(look.text)
        val plain = CandidateLook(
            fill = null, pressed = press, color = Argb(theme.candidateTextColor), labelColor = Argb(theme.candidateLabelColor),
            commentColor = Argb(theme.candidateCommentColor), font = font?.first, weight = font?.second,
        )
        val active = Argb(theme.genericActiveForegroundColor)
        val selected = CandidateLook(
            fill = Argb(theme.genericActiveBackgroundColor), pressed = press, color = active, labelColor = active,
            commentColor = active, font = font?.first, weight = font?.second,
        )
        val bar = Argb(look.palette.barColor)
        return BarLook(
            base = when (val b = look.bar) {
                BarBase.Transparent -> null
                BarBase.Color -> BarLook.Base.Solid(bar)
                is BarBase.Picture -> BarLook.Base.Picture(b.blurDp.toDouble(), b.brightness.toDouble(), null, bar)
            },
            plain = plain, first = plain, selected = selected, firstSelected = selected,
            radius = Radius(List(CORNERS) { 0.0 }), inset = Inset(0.0, 0.0, 0.0, 0.0), padding = null, gapDp = 0.0, labelStyle = null,
            divider = Argb(theme.dividerColor),
            panel = ThemeColors.panel(look.palette, look.keys)?.let(::Argb),
            preedit = PreeditLook(Argb(theme.keyTextColor), Argb(theme.keyTextColor), null),
            toolbar = ToolbarLook(
                fill = null, radius = null, icon = Argb(look.palette.toolbarIconColor), pressed = press, onFill = null,
                onIcon = Argb(theme.accentKeyBackgroundColor),
            ),
        )
    }

    // the last theme file and scenes asked of and their bar's height: asked for by every view on the bar as it is made
    private var shownHeight: Triple<Any?, Set<String>, Double>? = null

    /**
     * The bar's height in % of what 候选字号 makes it, the active theme's in the window's scenes now
     * (KawaiiBarComponent.heightDp): 100 for a theme setting none.
     */
    fun heightPercent(context: Context = appContext): Double {
        val theme = ThemeManager.activeTheme
        val scenes = SceneWindow.scenes(context, theme)
        // the theme file SceneThemes keeps: another look, or the same made anew, another file
        val file = SceneThemes.of(theme, ThemeManager.shownLook(theme))
        shownHeight?.let { (f, s, h) -> if (f === file && s == scenes) return h }
        val h = file?.let { BarLook.of(SceneThemes.cascades(it), scenes).heightPercent } ?: FULL
        shownHeight = Triple(file, scenes, h)
        return h
    }

    private const val FULL = 100.0

    /** The bar's base, under the bar and the row of the code being typed: none, a colour, the picture's part there. */
    fun background(bar: BarLook): Drawable? = when (val b = bar.base) {
        null -> null
        is BarLook.Base.Solid -> ColorDrawable(b.color.value)
        is BarLook.Base.Picture -> ThemePictureDrawable(b.blurDp.toInt(), b.brightness.toInt(), b.over?.value ?: 0, b.fallback.value)
    }

    /**
     * Under a candidate: its fill, and pressed its pressed colour over it, of the bar's corners, [BarLook.inset]
     * in from its cell. None set: the pressed colour over the whole cell alone, as ever.
     */
    fun candidateBackground(context: Context, bar: BarLook, c: CandidateLook, pressable: Boolean = true): Drawable? {
        val plain = bar.radius == SQUARE && bar.inset == NO_INSET && c.fill == null
        if (plain) return if (pressable) pressHighlightDrawable(c.pressed.value) else null
        fun shape(color: Argb) = inset(context, bar.inset, shape(context, bar.radius, color))
        if (!pressable) return c.fill?.let(::shape)
        return StateListDrawable().apply {
            addState(PRESSED, LayerDrawable(listOfNotNull(c.fill?.let(::shape), shape(c.pressed)).toTypedArray()))
            addState(intArrayOf(), c.fill?.let(::shape) ?: ColorDrawable(Color.TRANSPARENT))
        }
    }

    /**
     * A candidate's font: the theme's, its name read as the keys' are (`SceneFonts`: `candidate` the look's
     * candidate font, `key` its key font, another the system's till a theme's own fonts are read, T8a), at
     * its weight; none set, the system's at 400. A 3.0 look's candidate font is its theme's `candidate`
     * style (LookTheme), so it is drawn as ever. Null: the default typeface.
     */
    fun typeface(theme: Theme, c: CandidateLook): Typeface? {
        val look = ThemeManager.shownLook(theme)
        return ThemeAssets.typeface(look, SceneFonts.path(look, c.font ?: SYSTEM), c.weight ?: NORMAL)
    }

    /** A [color] fill of [radius]'s corners: a candidate's, a toolbar button's. */
    fun shape(context: Context, radius: Radius, color: Argb): GradientDrawable = GradientDrawable().apply {
        setColor(color.value)
        if (radius.half) {
            // as round as it goes: GradientDrawable keeps each corner to half the shorter side
            cornerRadius = Float.MAX_VALUE
        } else {
            // top left, top right, bottom right, bottom left: each corner its x and y
            cornerRadii = radius.corners.flatMap { listOf(it.toFloat() * density(context), it.toFloat() * density(context)) }.toFloatArray()
        }
    }

    /** Room left and right of a candidate's text, px: the theme's, else the app's of 候选字号. */
    fun padding(context: Context, bar: BarLook): Pair<Int, Int> {
        val p = bar.padding ?: KawaiiBarComponent.candidatePaddingDp().toDouble().let { Inset(0.0, it, 0.0, it) }
        return px(context, p.left) to px(context, p.right)
    }

    /** What stands before a candidate's words at [index] (from 0) in [style], its space after it; empty where nothing. */
    fun label(style: BarLook.LabelStyle?, index: Int): String = BarSample.label(style, index).let { if (it.isEmpty()) it else "$it " }

    /** [dp] in whole px, as the bar's every length (the sample bar's too). */
    fun px(context: Context, dp: Double): Int = (dp * density(context)).toInt()

    private fun density(context: Context) = context.resources.displayMetrics.density

    // a layer's inset, not an InsetDrawable's: that reports its inset as padding, which a view takes for its own,
    // the room round the candidate's text gone
    private fun inset(context: Context, i: Inset, d: Drawable): Drawable =
        if (i == NO_INSET) d else LayerDrawable(arrayOf(d)).apply { setLayerInset(0, px(context, i.left), px(context, i.top), px(context, i.right), px(context, i.bottom)) }

    private const val CORNERS = 4
    private const val NORMAL = 400
    private const val SYSTEM = "system"
    private val SQUARE = Radius(List(CORNERS) { 0.0 })
    private val NO_INSET = Inset(0.0, 0.0, 0.0, 0.0)
    private val PRESSED = intArrayOf(android.R.attr.state_pressed)
}
