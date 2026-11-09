/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

/**
 * A theme to pick of [themes], one card a built-in theme of two versions ([ThemeCards]): [dark]'s
 * version shown at first (a slot's: the light slot's light), the one [select]ed if it is of a pair.
 */
open class SimpleThemeListAdapter(themes: List<Theme>, dark: Boolean = false) :
    RecyclerView.Adapter<SimpleThemeListAdapter.ViewHolder>() {

    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    private val cards = ThemeCards(themes, dark)

    var selected = -1
        private set(value) {
            val last = field
            field = value
            if (last != -1)
                notifyItemChanged(last)
            if (field != -1)
                notifyItemChanged(field)
        }

    /** The theme picked: the card selected, the version it shows. */
    val selectedTheme
        get() = selected.takeIf { it != -1 }?.let { cards.shown[it] }

    /** Selects [theme]'s card, showing it. */
    fun select(theme: Theme?) {
        selected = cards.show(theme)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ThemeThumbnailUi(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.ui as ThemeThumbnailUi).apply {
            val theme = cards.shown[position]
            setTheme(theme)
            setChecked(position == selected)
            setVersion(theme.takeIf { cards.other(it) != null }?.isDark) {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION && cards.switch(at)) notifyItemChanged(at)
            }
            root.setOnClickListener {
                onClick(theme)
                selected = position
            }
        }
    }

    override fun getItemCount(): Int = cards.shown.size

    open fun onClick(theme: Theme) {}
}
