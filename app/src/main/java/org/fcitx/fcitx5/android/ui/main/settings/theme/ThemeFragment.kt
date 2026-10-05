/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.Keep
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as MaterialR
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class ThemeFragment : Fragment() {

    private lateinit var previewUi: KeyboardPreviewUi

    private lateinit var tabLayout: TabLayout

    private lateinit var viewPager: ViewPager2

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            previewUi.setTheme(it)
        }
    }

    // the size and the keys as the user picks them, so they see what a level does before typing
    @Keep
    private val onSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key in SIZE_KEYS) lifecycleScope.launch { previewUi.recalculateSize() }
    }

    @Keep
    private val onKeysChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key in KEY_KEYS) lifecycleScope.launch { previewUi.setTheme(ThemeManager.activeTheme) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = with(requireContext()) {
        previewUi = KeyboardPreviewUi(this, ThemeManager.activeTheme)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        AppPrefs.getInstance().keyboard.registerOnChangeListener(onSizeChangeListener)
        ThemeManager.prefs.registerOnChangeListener(onKeysChangeListener)
        val preview = previewUi.root.apply {
            scaleX = 0.5f
            scaleY = 0.5f
            outlineProvider = ViewOutlineProvider.BOUNDS
            elevation = dp(4f)
        }

        tabLayout = TabLayout(this)

        viewPager = ViewPager2(this).apply {
            adapter = object : FragmentStateAdapter(this@ThemeFragment) {
                override fun getItemCount() = 2
                override fun createFragment(position: Int): Fragment = when (position) {
                    0 -> ThemeListFragment()
                    else -> ThemeSettingsFragment()
                }
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.theme
                    else -> R.string.configure_look
                }
            )
        }.attach()

        val previewWrapper = constraintLayout {
            add(preview, lParams(wrapContent, wrapContent) {
                topOfParent(dp(-52))
                startOfParent()
                endOfParent()
            })
            add(tabLayout, lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            })
            backgroundColor = styledColor(MaterialR.attr.colorSurface)
        }

        constraintLayout {
            add(previewWrapper, lParams(height = wrapContent) {
                topOfParent()
                startOfParent()
                endOfParent()
            })
            add(viewPager, lParams {
                below(previewWrapper)
                startOfParent()
                endOfParent()
                bottomOfParent()
            })
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        AppPrefs.getInstance().keyboard.unregisterOnChangeListener(onSizeChangeListener)
        ThemeManager.prefs.unregisterOnChangeListener(onKeysChangeListener)
        super.onDestroy()
    }

    companion object {
        private val SIZE_KEYS = setOf(
            "keyboard_height_percent", "keyboard_height_percent_landscape",
            "keyboard_side_padding", "keyboard_side_padding_landscape",
            "keyboard_bottom_padding", "keyboard_bottom_padding_landscape",
        )
        private val KEY_KEYS = setOf(
            "key_border", "key_radius",
            "key_horizontal_margin", "key_horizontal_margin_landscape",
            "key_vertical_margin", "key_vertical_margin_landscape",
            "punctuation_position",
        )
    }
}
