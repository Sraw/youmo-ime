/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Space
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.utils.motionSettings
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.setPaddingDp
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    private fun list() = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val recentList = list()

    val favoritesList = list()

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val favoritesEmptyUi = ClipboardInstructionUi.Empty(ctx, theme, favorites = true)

    val viewAnimator = view(::ViewAnimator) {
        for (v in listOf(recentList, favoritesList, emptyUi.root, favoritesEmptyUi.root, enableUi.root)) {
            add(v, lParams(matchParent, matchParent))
        }
    }

    override val root = coordinatorLayout {
        ThemeColors.panel(ThemeManager.shownLook(theme).palette, ThemeManager.shownLook(theme).keys)?.let { backgroundColor = it }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
    }

    // the emoji panel's category tabs' colours
    private val look = ScenePanels.picker(ctx, theme, side = false)

    private val tabRole = ctx.getString(R.string.a11y_tab)

    private fun tab(label: Int) = textView {
        setText(label)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(120)
        setPaddingDp(10, 0, 10, 0)
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.roleDescription = tabRole
            }
        })
    }

    val recentTab = tab(R.string.clipboard_recent)

    val favoritesTab = tab(R.string.clipboard_favorites)

    private val plain = rippleDrawable(theme.keyPressHighlightColor)

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all_recent_description)
        fixIconSize(ctx.dp(KawaiiBarComponent.ICON_DP))
    }

    val extension = horizontalLayout {
        val height = dp(KawaiiBarComponent.heightDp(ctx))
        add(recentTab, lParams(wrapContent, height))
        add(favoritesTab, lParams(wrapContent, height))
        add(Space(ctx), lParams(0, height) { weight = 1f })
        add(deleteAllButton, lParams(dp(KawaiiBarComponent.buttonWidthDp(ctx)), height))
    }

    fun select(page: ClipboardPage) {
        for ((tab, active) in listOf(recentTab to (page == ClipboardPage.Recent), favoritesTab to (page == ClipboardPage.Favorites))) {
            (tab as TextView).setTextColor((if (active) look.selected else look.categories).color.value)
            tab.isSelected = active
            tab.background = look.selected.fill?.takeIf { active }?.let { ColorDrawable(it.value) } ?: plain.constantState?.newDrawable()?.mutate()
        }
    }

    fun show(page: ClipboardPage, shows: ClipboardShows) {
        Timber.d("Switch clipboard to $page $shows")
        val motion = motionSettings()
        if (motion.animates)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = Motion.Medium.enter(motion) })
        viewAnimator.displayedChild = when (shows) {
            ClipboardShows.List -> if (page == ClipboardPage.Recent) 0 else 1
            ClipboardShows.Empty -> if (page == ClipboardPage.Recent) 2 else 3
            ClipboardShows.EnableListening -> 4
        }
        // none to clear on the favourites': they go one by one
        deleteAllButton.visibility = if (page == ClipboardPage.Recent && shows == ClipboardShows.List) View.VISIBLE else View.INVISIBLE
    }
}
