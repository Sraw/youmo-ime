/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig.Item
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.DynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener

/**
 * The bar's buttons ([ToolbarConfig]) in one list: dragged to order, checked to show; the
 * divider, dragged too, parts the toolbar's from those always at the bar's right. Saved as
 * each changes. The menu key and ⋯ are fixed, not listed.
 */
class ToolbarButtonsFragment : Fragment(), OnItemChangedListener<Item> {

    private val pref get() = AppPrefs.getInstance().internal.toolbarButtons

    private lateinit var ui: BaseDynamicListUi<Item>

    // a text build has no voice: not listed, but kept where it was for the voice build (one package id, one backup)
    // it, how far from the divider (below it > 0, above it < 0) and what was before it: put back after
    // that if it is still on the same side, else as far from the divider; never across it
    private var voice: HeldVoice? = null

    private data class HeldVoice(val item: Item, val fromDivider: Int, val before: Item?)

    private fun listed(config: ToolbarConfig): List<Item> {
        if (VoiceFeature.AVAILABLE) return config.items
        val at = config.items.indexOfFirst { (it as? Item.Button)?.button == ToolbarButton.Voice }
        voice = HeldVoice(config.items[at], at - config.items.indexOf(Item.Divider), config.items.getOrNull(at - 1))
        return config.items.filter { (it as? Item.Button)?.button != ToolbarButton.Voice }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        ui = ctx.DynamicListUi(
            mode = BaseDynamicListUi.Mode.Immutable(),
            initialEntries = listed(ToolbarConfig.parse(pref.getValue())),
            enableOrder = true,
            initCheckBox = { item ->
                setOnCheckedChangeListener(null)
                if (item is Item.Button) {
                    visibility = View.VISIBLE
                    isChecked = item.shown
                    setOnCheckedChangeListener { _, checked ->
                        ui.updateItem(ui.indexItem(item), item.copy(shown = checked))
                    }
                } else {
                    visibility = View.INVISIBLE
                }
            },
            show = {
                when (it) {
                    Item.Divider -> getString(R.string.toolbar_divider)
                    is Item.Button -> getString(ToolbarButtonLooks.of(it.button).label)
                }
            }
        )
        ui.enableUndo = false
        ui.addOnItemChangedListener(this)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(R.string.toolbar_reset).setOnMenuItemClickListener {
                    reset()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
        return ui.root
    }

    /** The bar as it always was ([ToolbarConfig.DEFAULT]), the list with it. */
    @VisibleForTesting
    internal fun reset() {
        pref.setValue("")
        ui.removeItems(0, ui.entries.size)
        ui.addItems(listed(ToolbarConfig.DEFAULT))
    }

    // shown before the first unlock too: copied there as the page is left, as the other pages do
    override fun onStop() {
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }

    override fun onDestroyView() {
        ui.removeItemChangedListener()
        super.onDestroyView()
    }

    private fun save() {
        val items = ui.entries.toMutableList()
        voice?.let { (item, offset, before) ->
            val divider = items.indexOf(Item.Divider)
            // by the button, not the item: the one before it checked or unchecked since is still it
            val after = items.indexOfFirst { it == before || (it is Item.Button && before is Item.Button && it.button == before.button) }
            val at = when {
                after >= 0 && (after >= divider) == (offset > 0) -> after + 1
                offset > 0 -> (divider + offset).coerceAtMost(items.size)
                else -> (divider + offset + 1).coerceAtLeast(0)
            }
            items.add(at, item)
        }
        pref.setValue(ToolbarConfig(items).serialize())
    }

    override fun onItemSwapped(fromIdx: Int, toIdx: Int, item: Item) = save()

    override fun onItemUpdated(idx: Int, old: Item, new: Item) = save()
}
