/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

abstract class ThemeListAdapter : RecyclerView.Adapter<ThemeListAdapter.ViewHolder>() {
    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    val entries = mutableListOf<Theme>()

    private var activeIndex = -1
    private var lightIndex = -1
    private var darkIndex = -1

    private fun entryAt(position: Int) = entries.getOrNull(position - OFFSET)

    private fun positionOf(theme: Theme? = null): Int {
        if (theme == null) return -1
        return entries.indexOfFirst { it.name == theme.name } + OFFSET
    }

    fun setThemes(themes: List<Theme>) {
        entries.clear()
        entries.addAll(themes)
        notifyItemRangeInserted(OFFSET, themes.size)
    }

    fun setSelectedThemes(active: Theme, light: Theme? = null, dark: Theme? = null) {
        val oldActive = entryAt(activeIndex)
        if (oldActive != active) {
            notifyItemChanged(activeIndex)
            activeIndex = positionOf(active)
            notifyItemChanged(activeIndex)
        }
        val oldLight = entryAt(lightIndex)
        if (oldLight != light) {
            notifyItemChanged(lightIndex)
            lightIndex = positionOf(light)
            if (lightIndex >= OFFSET) {
                notifyItemChanged(lightIndex)
            }
        }
        val oldDark = entryAt(darkIndex)
        if (oldDark != dark) {
            notifyItemChanged(darkIndex)
            darkIndex = positionOf(dark)
            if (darkIndex >= OFFSET) {
                notifyItemChanged(darkIndex)
            }
        }
    }

    private fun prependOffset(index: Int): Int {
        return if (index == -1) 0 else 1
    }

    fun prependTheme(it: Theme) {
        entries.add(0, it)
        activeIndex += prependOffset(activeIndex)
        lightIndex += prependOffset(lightIndex)
        darkIndex += prependOffset(darkIndex)
        notifyItemInserted(OFFSET)
    }

    // removedIndex is an entry's, index a position: those after it move up, its own is gone
    private fun removeIndex(removedIndex: Int, index: Int): Int = when {
        index == removedIndex + OFFSET -> -1
        index > removedIndex + OFFSET -> index - 1
        else -> index
    }

    fun removeTheme(name: String) {
        val index = entries.indexOfFirst { it.name == name }
        entries.removeAt(index)
        notifyItemRemoved(index + OFFSET)
        activeIndex = removeIndex(index, activeIndex)
        lightIndex = removeIndex(index, lightIndex)
        darkIndex = removeIndex(index, darkIndex)
    }

    // the replaced entry goes first, and those it passes move down one
    private fun replaceIndex(replacedIndex: Int, index: Int): Int = when (index) {
        replacedIndex + OFFSET -> OFFSET
        in OFFSET until replacedIndex + OFFSET -> index + 1
        else -> index
    }

    fun replaceTheme(theme: Theme) {
        val index = entries.indexOfFirst { it.name == theme.name }
        entries.removeAt(index)
        entries.add(0, theme)
        activeIndex = replaceIndex(index, activeIndex)
        lightIndex = replaceIndex(index, lightIndex)
        darkIndex = replaceIndex(index, darkIndex)
        notifyItemMoved(index + OFFSET, OFFSET)
        notifyItemChanged(OFFSET)
    }

    internal fun stateAt(position: Int) = when (position) {
        darkIndex -> ThemeThumbnailUi.State.DarkMode
        lightIndex -> ThemeThumbnailUi.State.LightMode
        activeIndex -> ThemeThumbnailUi.State.Selected
        else -> ThemeThumbnailUi.State.Normal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            when (viewType) {
                ADD_THEME -> NewThemeEntryUi(parent.context)
                THEME -> ThemeThumbnailUi(parent.context)
                else -> throw IllegalArgumentException(INVALID_TYPE + viewType)
            }
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val it = getItemViewType(position)) {
            ADD_THEME -> holder.ui.root.setOnClickListener { onAddNewTheme() }
            THEME -> (holder.ui as ThemeThumbnailUi).apply {
                val theme = entryAt(position)!!
                setTheme(theme)
                setChecked(stateAt(position))
                root.setOnClickListener {
                    onSelectTheme(theme)
                }
                root.setOnLongClickListener {
                    if (theme is Theme.Custom) {
                        onExportTheme(theme)
                        true
                    } else if (theme is Theme.Monet) {
                        onExportTheme(theme.toCustom())
                        true
                    } else false
                }
                editButton.setOnClickListener {
                    if (theme is Theme.Custom) onEditTheme(theme)
                }
            }
            else -> throw IllegalArgumentException(INVALID_TYPE + it)
        }
    }

    override fun getItemCount() = entries.size + 1

    override fun getItemViewType(position: Int) = if (position == 0) ADD_THEME else THEME

    abstract fun onAddNewTheme()

    abstract fun onSelectTheme(theme: Theme)

    abstract fun onEditTheme(theme: Theme.Custom)

    abstract fun onExportTheme(theme: Theme.Custom)

    companion object {
        const val OFFSET = 1

        const val ADD_THEME = 0
        const val THEME = 1

        const val INVALID_TYPE = "Invalid ItemView Type: "
    }
}
