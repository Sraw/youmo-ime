/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColor

class EditDeleteMenuProvider<T>(
    private val buttonMode: LiveData<ButtonMode>,
    private val editButtonAction: (() -> Unit)?,
    private val deleteButtonAction: (() -> Unit)?,
    menuHost: T,
    lifecycleOwner: LifecycleOwner,
) : MenuProvider where T : MenuHost, T : Context {

    private val tint = menuHost.styledColor(android.R.attr.colorControlNormal)

    init {
        buttonMode.observe(lifecycleOwner) { menuHost.invalidateMenu() }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        when (buttonMode.value) {
            ButtonMode.EDIT -> {
                if (editButtonAction != null) {
                    menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, tint, true) {
                        editButtonAction()
                    }
                }
            }
            ButtonMode.DELETE -> {
                if (deleteButtonAction != null) {
                    menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, tint, true) {
                        deleteButtonAction()
                    }
                }
            }
            ButtonMode.NONE, null -> { /* no buttons */ }
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
}

/** [ui]'s 编辑 and 删除 on the bar, as [buttonMode] says, while this fragment's view is started. */
fun Fragment.addEditDeleteMenu(buttonMode: LiveData<ButtonMode>, ui: BaseDynamicListUi<*>) {
    requireActivity().addMenuProvider(
        EditDeleteMenuProvider(
            buttonMode = buttonMode,
            editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
            deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
            menuHost = requireActivity(),
            lifecycleOwner = viewLifecycleOwner,
        ),
        viewLifecycleOwner,
        Lifecycle.State.STARTED,
    )
}

/** [items] (a title and its action) in the bar's overflow while this fragment's view is started, enabled as [enabled] says. */
fun Fragment.addOverflowMenu(vararg items: Pair<Int, () -> Unit>, enabled: () -> Boolean = { true }) {
    requireActivity().addMenuProvider(object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            for ((title, action) in items) {
                menu.add(title).setEnabled(enabled()).setOnMenuItemClickListener {
                    action()
                    true
                }
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem) = false
    }, viewLifecycleOwner, Lifecycle.State.STARTED)
}
