/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIconDrawable
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * A place of the bar's or a panel's icon ([BarIcons]) as drawn (061-toolbar-icons §2.4): the theme's of its name in
 * the place's icon colour (paths of no colour of their own), else the place's own image in it. The theme's is read on
 * [ThemeAssets][org.fcitx.fcitx5.android.data.theme.ThemeAssets]' thread ([PackIcons]): one the theme writes but not
 * read yet is nothing ([of] null) till [ready], never the app's image first and the theme's after (a flash). One whose
 * file is gone, broken or too long is the app's.
 */
object BarIconDrawables {

    /** [icon] in [tint], [px] square; null: the theme's not read yet, [ready] told on the main thread once it is. */
    fun of(context: Context, icons: PackIcons, icon: BarIcons.Icon, @ColorInt tint: Int, px: Int, ready: () -> Unit): Drawable? {
        val file = icons.file(icon.name) ?: return fallback(context, icon, tint)
        val outline = icons.outline(file, ready)
        return when {
            outline != null -> PackIconDrawable(outline, Argb(tint), px, px)
            icons.pending(file) -> null
            else -> fallback(context, icon, tint)
        }
    }

    /**
     * [view] showing [icon] in [tint]: the app's by its resource, tinted (SRC_IN) as such views ever were, where the theme
     * has none; else [of], [px] square, [again] told once a file not read yet is.
     */
    fun show(view: ImageView, icons: PackIcons, icon: BarIcons.Icon, @ColorInt tint: Int, px: Int, again: () -> Unit) {
        if (!themed(icons, icon)) {
            view.setImageResource(ToolbarButtonLooks.drawable(icon.fallback))
            view.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            return
        }
        view.colorFilter = null
        view.setImageDrawable(of(view.context, icons, icon, tint, px, again))
    }

    /** Whether the theme writes [icon]'s name: the app's image is never drawn for it then (but where its file fails). */
    fun themed(icons: PackIcons, icon: BarIcons.Icon): Boolean = icons.file(icon.name) != null

    private fun fallback(context: Context, icon: BarIcons.Icon, @ColorInt tint: Int): Drawable =
        ContextCompat.getDrawable(context, ToolbarButtonLooks.drawable(icon.fallback))!!.mutate().apply { setTint(tint) }

    /** The theme's icons ahead, the bar's every one (the bar is made with the input view): told nothing, kept for its draw. */
    fun readAhead(icons: PackIcons) {
        for (icon in BarIcons.BAR) icons.file(icon.name)?.let { icons.outline(it) }
    }
}
