/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

/** The pick is a theme, not a card: a card's 浅/深 shows the other version unmarked and picks nothing. */
open class SimpleThemeListAdapter(themes: List<Theme>, dark: Boolean = false) :
    RecyclerView.Adapter<SimpleThemeListAdapter.ViewHolder>() {

    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    private val cards = ThemeCards(themes, dark)

    /** The card tapped, the version it showed, or the one [select]ed. */
    var selectedTheme: Theme? = null
        private set(value) {
            val last = cards.indexOf(field)
            field = value
            if (last != -1) notifyItemChanged(last)
            cards.indexOf(value).let { if (it != -1 && it != last) notifyItemChanged(it) }
        }

    /** None if no card has it. */
    fun select(theme: Theme?) {
        selectedTheme = theme?.takeIf { cards.show(it) != -1 }
    }

    fun shownAt(position: Int): Theme = cards.shown[position]

    /** Only while the version picked is the one it shows. */
    fun checkedAt(position: Int): Boolean = cards.shown[position].name == selectedTheme?.name

    fun pick(position: Int) {
        val theme = cards.shown[position]
        onClick(theme)
        selectedTheme = theme
    }

    /** The pick is unchanged. */
    fun switchVersion(position: Int) {
        if (cards.switch(position)) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ThemeThumbnailUi(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.ui as ThemeThumbnailUi).apply {
            val theme = cards.shown[position]
            setTheme(theme, cards.shown)
            setChecked(checkedAt(position))
            setVersion(theme.takeIf { cards.other(it) != null }?.isDark) {
                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::switchVersion)
            }
            root.setOnClickListener {
                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::pick)
            }
        }
    }

    override fun getItemCount(): Int = cards.shown.size

    open fun onClick(theme: Theme) {}
}
