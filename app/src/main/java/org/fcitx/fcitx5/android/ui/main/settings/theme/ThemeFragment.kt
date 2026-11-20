/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
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
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.ui.main.settings.layout.LayoutListFragment
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchAnchors
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class ThemeFragment : Fragment(), SearchAnchors {

    private lateinit var previewUi: KeyboardPreviewUi

    private lateinit var tabLayout: TabLayout

    private lateinit var viewPager: ViewPager2

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            previewUi.setTheme(it)
        }
    }

    // so the user sees what a level does before typing (the keys' look is ThemePrefs', which calls onThemeChangeListener)
    @Keep
    private val onSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key in SIZE_KEYS) lifecycleScope.launch { previewUi.recalculateSize() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = with(requireContext()) {
        previewUi = KeyboardPreviewUi(this, ThemeManager.activeTheme)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        AppPrefs.getInstance().keyboard.registerOnChangeListener(onSizeChangeListener)
        val preview = previewUi.root.apply {
            outlineProvider = ViewOutlineProvider.BOUNDS
            elevation = dp(4f)
        }

        tabLayout = TabLayout(this)

        viewPager = ViewPager2(this).apply {
            adapter = object : FragmentStateAdapter(this@ThemeFragment) {
                override fun getItemCount() = 3
                override fun createFragment(position: Int): Fragment = when (position) {
                    0 -> ThemeListFragment()
                    1 -> ThemeSettingsFragment()
                    else -> LayoutListFragment()
                }
            }
            // a layout tapped on 布局 shows in the preview while that tab is open
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (position != LAYOUTS_TAB) previewUi.showLayout(null)
                }
            })
        }

        // a look setting on the second tab, or a built-in theme on the first
        if (SearchHighlight.isWaiting(this@ThemeFragment) && SearchHighlight.waitingFor(this@ThemeFragment) == null) viewPager.setCurrentItem(1, false)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.theme
                    1 -> R.string.configure_look
                    else -> R.string.layout_tab
                }
            )
        }.attach()

        val box = HalfSizeBox(this).apply { add(preview, lParams(wrapContent, wrapContent)) }
        val gap = dp(16)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // side by side: the half-size preview takes half the screen, the settings the whole height
            constraintLayout {
                add(box, lParams(wrapContent, wrapContent) {
                    topOfParent(gap)
                    startOfParent()
                    bottomOfParent(gap)
                    verticalBias = 0f
                })
                add(tabLayout, lParams(matchConstraints, wrapContent) {
                    topOfParent()
                    after(box)
                    endOfParent()
                })
                add(viewPager, lParams(matchConstraints, matchConstraints) {
                    below(tabLayout)
                    after(box)
                    endOfParent()
                    bottomOfParent()
                })
                backgroundColor = styledColor(MaterialR.attr.colorSurface)
            }
        } else {
            val previewWrapper = constraintLayout {
                add(box, lParams(wrapContent, wrapContent) {
                    topOfParent(gap)
                    centerHorizontally()
                })
                add(tabLayout, lParams(matchParent, wrapContent) {
                    below(box, gap)
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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // the themes are listed as the first tab is made
        SearchHighlight.ready(this)
    }

    override fun reveal(id: String, found: (View) -> Unit) {
        val file = id.removePrefix(THEME).takeIf { it != id } ?: return
        viewPager.setCurrentItem(0, false)
        // the tab's page is made as the pager is laid out
        viewPager.post {
            if (isAdded) childFragmentManager.fragments.filterIsInstance<ThemeListFragment>().firstOrNull()?.reveal(file, found)
        }
    }

    /** A scaled view still takes its whole size: this takes half. */
    private class HalfSizeBox(context: Context) : FrameLayout(context) {
        init {
            // clipped to the scaled size, the preview's elevation shadow was cut off
            clipChildren = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // the shadow is in this box's display list, clipped by the parent to the box's bounds: the parent must not clip either
            (parent as? ViewGroup)?.clipChildren = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val child = getChildAt(0)
            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            setMeasuredDimension(child.measuredWidth / 2, child.measuredHeight / 2)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val child = getChildAt(0)
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = 0.5f
            child.scaleY = 0.5f
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    /** In the keyboard letters' place, nothing saved; null, the keyboard's. */
    fun previewLayout(layout: SceneLayout?) {
        if (::previewUi.isInitialized) previewUi.showLayout(layout)
    }

    override fun onStop() {
        ThemeManager.syncToDeviceEncryptedStorage()
        super.onStop()
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        AppPrefs.getInstance().keyboard.unregisterOnChangeListener(onSizeChangeListener)
        super.onDestroy()
    }

    // the built-in themes by name (the user's own are not searched; the list shows them)
    companion object : SearchablePage {
        private const val LAYOUTS_TAB = 2

        private const val THEME = "view:theme/"

        override fun searchEntries(context: Context) =
            BuiltinThemeFiles.FILES.map { (file, _) -> PageEntry(ThemeNames.shown(context, file), THEME + file) }

        // declared by AppPrefs.Keyboard: renamed there, renamed here
        private val SIZE_KEYS by lazy {
            with(AppPrefs.getInstance().keyboard) {
                listOf(
                    keyboardHeightPercent, keyboardHeightPercentLandscape,
                    keyboardSidePadding, keyboardSidePaddingLandscape,
                    keyboardBottomPadding, keyboardBottomPaddingLandscape,
                    // 跟随主题: picking a level whose numbers are already stored changes only these
                    keyboardHeightFollowTheme, keyboardSidePaddingFollowTheme, keyboardBottomPaddingFollowTheme,
                ).mapTo(HashSet()) { it.key }
            }
        }
    }
}
