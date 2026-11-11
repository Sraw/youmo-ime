/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

abstract class ThemeListAdapter : RecyclerView.Adapter<ThemeListAdapter.ViewHolder>() {
    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    // a built-in theme of two versions one card (ThemeCards)
    private var cards = ThemeCards(emptyList())

    /** The cards' themes as shown, in the list's order. */
    val entries: MutableList<Theme> get() = cards.shown

    // the cards marked; a mark is shown on its card while the version marked is the one shown
    private var activeIndex = -1
    private var lightIndex = -1
    private var darkIndex = -1
    private var activeName: String? = null
    private var lightName: String? = null
    private var darkName: String? = null

    private fun entryAt(position: Int) = entries.getOrNull(position - OFFSET)

    private fun positionOf(theme: Theme?): Int = cards.indexOf(theme).let { if (it < 0) -1 else it + OFFSET }

    fun setThemes(themes: List<Theme>) {
        cards = ThemeCards(themes)
        notifyItemRangeInserted(OFFSET, entries.size)
    }

    /** The list made again of [themes] (a pack imported: its versions one card); the marks set again after ([setSelectedThemes]). */
    @SuppressLint("NotifyDataSetChanged")
    fun resetThemes(themes: List<Theme>) {
        cards = ThemeCards(themes)
        activeIndex = -1
        lightIndex = -1
        darkIndex = -1
        notifyDataSetChanged()
    }

    /**
     * Marks [active], and [light] and [dark] where the theme follows the system's: each card one of them
     * is on shows its version (the one in use last, of a pair both slots have).
     */
    fun setSelectedThemes(active: Theme, light: Theme? = null, dark: Theme? = null) {
        val before = listOf(activeIndex, lightIndex, darkIndex)
        listOf(light, dark, active).forEach(cards::show)
        activeIndex = positionOf(active)
        lightIndex = positionOf(light)
        darkIndex = positionOf(dark)
        activeName = active.name
        lightName = light?.name
        darkName = dark?.name
        (before + listOf(activeIndex, lightIndex, darkIndex)).filter { it >= OFFSET }.distinct().forEach(::notifyItemChanged)
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
    private fun indexAfterRemoval(removedIndex: Int, index: Int): Int = when {
        index == removedIndex + OFFSET -> -1
        index > removedIndex + OFFSET -> index - 1
        else -> index
    }

    fun removeTheme(name: String) = removeAt(entries.indexOfFirst { it.name == name })

    /** The card of [theme] itself removed: a pack and a 3.0 theme of the same name are two cards. */
    fun removeTheme(theme: Theme) = removeAt(
        entries.indexOfFirst {
            it == theme || (it is Theme.Versioned && theme is Theme.Versioned && it::class == theme::class && it.file == theme.file)
        }
    )

    private fun removeAt(index: Int) {
        if (index < 0) return
        entries.removeAt(index)
        notifyItemRemoved(index + OFFSET)
        activeIndex = indexAfterRemoval(index, activeIndex)
        lightIndex = indexAfterRemoval(index, lightIndex)
        darkIndex = indexAfterRemoval(index, darkIndex)
    }

    // the replaced entry goes first, and those it passes move down one
    private fun indexAfterReplace(replacedIndex: Int, index: Int): Int = when (index) {
        replacedIndex + OFFSET -> OFFSET
        in OFFSET until replacedIndex + OFFSET -> index + 1
        else -> index
    }

    fun replaceTheme(theme: Theme) {
        val index = entries.indexOfFirst { it.name == theme.name }
        entries.removeAt(index)
        entries.add(0, theme)
        activeIndex = indexAfterReplace(index, activeIndex)
        lightIndex = indexAfterReplace(index, lightIndex)
        darkIndex = indexAfterReplace(index, darkIndex)
        notifyItemMoved(index + OFFSET, OFFSET)
        notifyItemChanged(OFFSET)
    }

    internal fun stateAt(position: Int): ThemeThumbnailUi.State {
        val shown = entryAt(position)?.name
        return when {
            position == darkIndex && shown == darkName -> ThemeThumbnailUi.State.DarkMode
            position == lightIndex && shown == lightName -> ThemeThumbnailUi.State.LightMode
            position == activeIndex && shown == activeName -> ThemeThumbnailUi.State.Selected
            else -> ThemeThumbnailUi.State.Normal
        }
    }

    /** The card at [position] shows its other version (浅/深), its mark if that one has one. */
    internal fun switchVersion(position: Int) {
        if (position >= OFFSET && cards.switch(position - OFFSET)) notifyItemChanged(position)
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
                setVersion(theme.takeIf { cards.other(it) != null }?.isDark) { switchVersion(holder.bindingAdapterPosition) }
                if (cards.other(theme) == null) setOnly(theme.isDark)
                root.setOnClickListener {
                    onSelectTheme(theme)
                }
                root.setOnLongClickListener {
                    when {
                        theme is Theme.Custom || theme is Theme.Pack -> onThemeActions(theme)
                        // Monet's: its look the wallpaper's colours now, its key style with them: a fixed copy
                        theme is Theme.Builtin && theme.followsSystem -> onExportTheme(theme.toCustom())
                        else -> return@setOnLongClickListener false
                    }
                    true
                }
            }
            else -> throw IllegalArgumentException(INVALID_TYPE + it)
        }
    }

    override fun getItemCount() = entries.size + 1

    override fun getItemViewType(position: Int) = if (position == 0) ADD_THEME else THEME

    abstract fun onAddNewTheme()

    abstract fun onSelectTheme(theme: Theme)

    abstract fun onExportTheme(theme: Theme)

    abstract fun onDeleteTheme(theme: Theme)

    abstract fun onThemeInfo(theme: Theme)

    /** A theme of the user's (a 3.0 one, a pack) long pressed: what it is (信息), export it or delete it. */
    private fun ThemeThumbnailUi.onThemeActions(theme: Theme) {
        // a built-in pack (BuiltinPacks) is the app's: shown and exported, not deleted
        val actions = listOfNotNull(
            ctx.getString(R.string.theme_info), ctx.getString(R.string.export),
            ctx.getString(R.string.delete).takeUnless { theme is Theme.Pack && theme.builtin },
        ).toTypedArray()
        AlertDialog.Builder(ctx)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> onThemeInfo(theme)
                    1 -> onExportTheme(theme)
                    else -> onDeleteTheme(theme)
                }
            }
            .show()
    }

    companion object {
        const val OFFSET = 1

        const val ADD_THEME = 0
        const val THEME = 1

        const val INVALID_TYPE = "Invalid ItemView Type: "
    }
}
