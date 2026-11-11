/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.engine.MarkFate
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaps.onBoard
import splitties.dimensions.dp

/**
 * How a way the user set is drawn on its keyboard (the engine's [org.fcitx.fcitx5.android.scene.KeyNode.fates]):
 * the keyboard made as the keyboard makes it, with the settings as they are (the marks' places, the key style,
 * 紧凑, one-handed, floating, split) at this phone's width, upright and turned; and the page's note of it.
 */
object SwipeFates {

    /** [shortcut]'s way [up] (its key's `full/j`, a function key's `space`) with [slots]: upright, then turned. */
    fun of(ctx: Context, shortcut: String, up: Boolean, slots: SwipeSlots): Pair<MarkFate?, MarkFate?> =
        fate(upright(ctx), shortcut, up, slots) to fate(turned(ctx), shortcut, up, slots)

    /** What the page says of [upright] and [turned]: nothing where both are drawn as set. */
    fun note(ctx: Context, upright: MarkFate?, turned: MarkFate?): String? {
        val a = upright.shown()
        val b = turned.shown()
        if (a == null && b == null) return null
        if (a == b) return when (a) {
            is MarkFate.Shortened -> ctx.getString(R.string.swipe_fate_shortened, a.text)
            MarkFate.Sign -> ctx.getString(R.string.swipe_fate_sign)
            else -> ctx.getString(R.string.swipe_fate_dropped)
        }
        fun part(f: MarkFate?) = when (f) {
            null -> ctx.getString(R.string.swipe_fate_part_as_set)
            is MarkFate.Shortened -> ctx.getString(R.string.swipe_fate_part_shortened, f.text)
            MarkFate.Sign -> ctx.getString(R.string.swipe_fate_part_sign)
            else -> ctx.getString(R.string.swipe_fate_part_dropped)
        }
        return ctx.getString(R.string.swipe_fate_both, part(a), part(b))
    }

    // drawn as set, or not drawn at all for nothing set: no note
    private fun MarkFate?.shown(): MarkFate? = takeUnless { it == null || it == MarkFate.AsSet }

    private fun fate(ctx: Context, shortcut: String, up: Boolean, slots: SwipeSlots): MarkFate? {
        val s = KeyCaps.Shortcut(shortcut, slots)
        val cell = SceneKeyCell(ctx, ThemeManager.activeTheme)
        cell.onBoard(ctx, s, board(ctx, shortcut))
        // a size of its own: the scene is made for a cell that has one (the board's is the keyboard's)
        cell.layout(0, 0, 1, 1)
        return cell.keyNode?.fates?.get(up)
    }

    // the keyboard of [shortcut] as it is shown in [ctx]'s orientation: one-handed or floating as narrow as there
    // (InputView, TextKeyboard), the letters split where they split
    private fun board(ctx: Context, shortcut: String): SceneKeyCell.Board {
        val b = KeyCaps.board(ctx, shortcut)
        val prefs = ThemeManager.prefs
        val screen = ctx.resources.configuration.screenWidthDp
        val widthDp = TextKeyboard.widthDp(ctx)
        if (prefs.floatingKeyboard.getValue()) {
            val scale = FloatingKeyboard.textScale(widthDp, screen).toDouble()
            return b.copy(width = ctx.dp(widthDp), height = FloatingKeyboard.height(b.height, widthDp, screen), floating = scale)
        }
        val letters = b.layout == KeyNames.QWERTY
        val splitting = TextKeyboard.splits(ctx)
        val placed = SplitKeyboard.placement(prefs.oneHanded.getValue().dock, prefs.splitDock.getValue().dock, splitting, letters, widthDp)
        return when {
            placed != null -> b.copy(width = ctx.dp(placed.widthDp))
            splitting && letters -> b.copy(split = true)
            else -> b
        }
    }

    private fun landscape(ctx: Context) = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun upright(ctx: Context): Context = if (landscape(ctx)) Turned(ctx) else ctx

    private fun turned(ctx: Context): Context = if (landscape(ctx)) ctx else Turned(ctx)

    // [base] as the phone turned the other way: its screen's sides swapped, as the keyboard sees it then
    private class Turned(base: Context) : ContextWrapper(base) {
        private val res: Resources = base.resources.let { r ->
            val c = Configuration(r.configuration).apply {
                orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                val w = screenWidthDp
                screenWidthDp = screenHeightDp
                screenHeightDp = w
            }
            val m = DisplayMetrics().apply {
                setTo(r.displayMetrics)
                widthPixels = r.displayMetrics.heightPixels
                heightPixels = r.displayMetrics.widthPixels
            }
            @Suppress("DEPRECATION")
            Resources(r.assets, m, c)
        }

        override fun getResources(): Resources = res
    }
}
