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

/** The theme's not read yet is nothing ([of] null) till [ready], never the app's first and the theme's after (a flash). */
object BarIconDrawables {

    /** Null: the theme's not read yet. */
    fun of(context: Context, icons: PackIcons, icon: BarIcons.Icon, @ColorInt tint: Int, px: Int, ready: () -> Unit): Drawable? {
        val file = icons.file(icon.name) ?: return fallback(context, icon, tint)
        val outline = icons.outline(file, ready)
        return when {
            outline != null -> PackIconDrawable(outline, Argb(tint), px, px)
            icons.pending(file) -> null
            else -> fallback(context, icon, tint)
        }
    }

    /** The app's tinted (SRC_IN) as such views ever were where the theme has none. */
    fun show(view: ImageView, icons: PackIcons, icon: BarIcons.Icon, @ColorInt tint: Int, px: Int, again: () -> Unit) {
        if (!themed(icons, icon)) {
            view.setImageResource(ToolbarButtonLooks.drawable(icon.fallback))
            view.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            return
        }
        view.colorFilter = null
        view.setImageDrawable(of(view.context, icons, icon, tint, px, again))
    }

    /** The app's image is never drawn for it then (but where its file fails). */
    fun themed(icons: PackIcons, icon: BarIcons.Icon): Boolean = icons.file(icon.name) != null

    /** One gone, broken or too long draws the app's: a restored backup's pack is not held to the import's checks. */
    fun drawsOwn(icons: PackIcons, icon: BarIcons.Icon): Boolean =
        icons.file(icon.name)?.let { icons.outline(it) != null || icons.pending(it) } == true

    private fun fallback(context: Context, icon: BarIcons.Icon, @ColorInt tint: Int): Drawable =
        ContextCompat.getDrawable(context, ToolbarButtonLooks.drawable(icon.fallback))!!.mutate().apply { setTint(tint) }

    /** The bar is made with the input view: told nothing, kept for its draw. */
    fun readAhead(icons: PackIcons) {
        for (icon in BarIcons.BAR) icons.file(icon.name)?.let { icons.outline(it) }
    }
}
