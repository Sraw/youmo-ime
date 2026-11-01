/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.address.AddressLists
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.DynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * 「通用输入 → 网址和邮箱」: the address suggestions' two lists of endings, each a page of its own
 * ([AddressSuffixesFragment]), whether the email addresses typed are remembered, and those remembered,
 * each forgotten with a tap, or all at once. What the endings counted stays: it only orders them.
 */
class AddressSettingsFragment : PaddingPreferenceFragment() {

    private val remember = AppPrefs.getInstance().keyboard.rememberAddresses

    private lateinit var email: Preference
    private lateinit var url: Preference
    private lateinit var remembered: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        screen.addPreference(Preference(ctx).apply {
            setSummary(R.string.address_suffixes_summary)
            isIconSpaceReserved = false
            isSelectable = false
        })
        email = row(R.string.email_suffixes, EMAIL_KEY) { navigateWithAnim(SettingsRoute.AddressSuffixes(AddressSuffixesFragment.EMAIL)) }
        url = row(R.string.url_suffixes, URL_KEY) { navigateWithAnim(SettingsRoute.AddressSuffixes(AddressSuffixesFragment.URL)) }
        // not kept by this screen: the app's preference is
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = REMEMBER_KEY
            isPersistent = false
            isIconSpaceReserved = false
            isChecked = remember.getValue()
            setTitle(R.string.remember_addresses)
            setSummary(R.string.remember_addresses_summary)
            setOnPreferenceChangeListener { _, value ->
                remember.setValue(value as Boolean)
                true
            }
        })
        remembered = PreferenceCategory(ctx).apply {
            key = REMEMBERED_KEY
            setTitle(R.string.remembered_addresses)
            isIconSpaceReserved = false
            screen.addPreference(this)
        }
    }

    private fun row(title: Int, key: String, open: () -> Unit) = Preference(requireContext()).apply {
        this.key = key
        setTitle(title)
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            open()
            true
        }
        preferenceScreen.addPreference(this)
    }

    override fun onResume() {
        super.onResume()
        // back from a list
        val lists = AddressStore.lists()
        email.summary = lists.email.joinToString("  ") { AddressLists.EMAIL_MARK + it }
        url.summary = lists.url.joinToString("  ")
        listRemembered()
    }

    private fun listRemembered() {
        val ctx = requireContext()
        remembered.removeAll()
        val addresses = AddressStore.book().addresses
        if (addresses.isEmpty()) {
            remembered.addPreference(Preference(ctx).apply {
                setTitle(R.string.remembered_addresses_none)
                isIconSpaceReserved = false
                isSelectable = false
            })
            return
        }
        for (a in addresses) {
            remembered.addPreference(Preference(ctx).apply {
                title = a.address
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle(a.address)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            AddressStore.forget { it.forget(a.address) }
                            listRemembered()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
        remembered.addPreference(Preference(ctx).apply {
            setTitle(R.string.forget_all_addresses)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.forget_all_addresses)
                    .setMessage(getString(R.string.forget_all_addresses_message, addresses.size))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        AddressStore.forget { it.forgetAddresses() }
                        listRemembered()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        })
    }

    companion object {
        // the rows settings search points at (SettingsIndex): this page's own, not stored
        const val EMAIL_KEY = "address_email_suffixes_row"
        const val URL_KEY = "address_url_suffixes_row"
        const val REMEMBER_KEY = "address_remember_row"
        const val REMEMBERED_KEY = "address_remembered_row"
    }
}

/**
 * One of the two lists of endings ([AddressLists]), [SettingsRoute.AddressSuffixes]'s: added to,
 * swiped away, dragged into another order, saved as each change is made; put back as it came from the menu.
 */
class AddressSuffixesFragment : Fragment(), OnItemChangedListener<String> {

    private val viewModel: MainViewModel by activityViewModels()

    private val args by lazyRoute<SettingsRoute.AddressSuffixes>()

    private val isEmail get() = args.kind == EMAIL

    // internal: the tests drag one as the touch helper would (swapItem)
    internal lateinit var ui: BaseDynamicListUi<String>

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val lists = AddressStore.lists()
        val entry: (String) -> String? = if (isEmail) AddressLists::emailEntry else AddressLists::urlEntry
        ui = ctx.DynamicListUi(
            mode = BaseDynamicListUi.Mode.FreeAdd(
                hint = getString(if (isEmail) R.string.email_suffix_hint else R.string.url_suffix_hint),
                converter = { entry(it)!! },
                // each once
                validator = { typed -> entry(typed)?.let { it !in ui.entries } == true },
            ),
            initialEntries = if (isEmail) lists.email else lists.url,
            enableOrder = true,
            show = { if (isEmail) AddressLists.EMAIL_MARK + it else it },
        )
        ui.addOnItemChangedListener(this)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(R.string.reset).setOnMenuItemClickListener {
                    confirmReset()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
        return ui.root
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(if (isEmail) R.string.email_suffixes else R.string.url_suffixes))
    }

    override fun onDestroyView() {
        ui.removeItemChangedListener()
        super.onDestroyView()
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset)
            .setMessage(R.string.address_suffixes_reset_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // each step saved as it is made; no undo of half of it
                ui.enableUndo = false
                ui.removeItems(0, ui.entries.size)
                ui.addItems(if (isEmail) AddressLists.DEFAULT_EMAIL else AddressLists.DEFAULT_URL)
                ui.enableUndo = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() = if (isEmail) AddressStore.setEmail(ui.entries) else AddressStore.setUrl(ui.entries)

    override fun onItemAdded(idx: Int, item: String) = save()
    override fun onItemRemoved(idx: Int, item: String) = save()
    override fun onItemUpdated(idx: Int, old: String, new: String) = save()
    override fun onItemSwapped(fromIdx: Int, toIdx: Int, item: String) = save()
    override fun onItemRemovedBatch(indexed: List<Pair<Int, String>>) = save()
    override fun onItemAddedBatch(indexed: List<Pair<Int, String>>) = save()

    companion object {
        const val EMAIL = "email"
        const val URL = "url"
    }
}
