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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
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

    // the size as the user picks it, so they see what a level does before typing (the keys' look
    // is ThemePrefs', for which ThemeManager calls onThemeChangeListener)
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
                override fun getItemCount() = 2
                override fun createFragment(position: Int): Fragment = when (position) {
                    0 -> ThemeListFragment()
                    else -> ThemeSettingsFragment()
                }
            }
        }

        // a search result here is a setting of the look, on the second tab (the first lists themes)
        if (SearchHighlight.isWaiting(this@ThemeFragment)) viewPager.setCurrentItem(1, false)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.theme
                    else -> R.string.configure_look
                }
            )
        }.attach()

        val box = HalfSizeBox(this).apply { add(preview, lParams(wrapContent, wrapContent)) }
        val gap = dp(16)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // side by side: the preview, half the keyboard's width, takes half the screen's; the
            // settings get its whole height, which above the preview left them a line of it
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

    /**
     * The keyboard preview at half its size, taking half its size: a view scaled still takes its
     * whole size, which left the space a preview at full size would.
     */
    private class HalfSizeBox(context: Context) : FrameLayout(context) {
        init {
            // exactly the scaled preview's size: clipped to it, the preview's elevation shadow was cut off
            clipChildren = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // the shadow is drawn in this box's own display list, which its parent clips to the box's
            // bounds: the gap around it is in the parent, so the parent must not clip either
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

    override fun onStop() {
        ThemeManager.syncToDeviceEncryptedStorage()
        super.onStop()
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        AppPrefs.getInstance().keyboard.unregisterOnChangeListener(onSizeChangeListener)
        super.onDestroy()
    }

    companion object {
        // the keys AppPrefs.Keyboard declares: one renamed there is renamed here
        private val SIZE_KEYS by lazy {
            with(AppPrefs.getInstance().keyboard) {
                listOf(
                    keyboardHeightPercent, keyboardHeightPercentLandscape,
                    keyboardSidePadding, keyboardSidePaddingLandscape,
                    keyboardBottomPadding, keyboardBottomPaddingLandscape,
                ).mapTo(HashSet()) { it.key }
            }
        }
    }
}
