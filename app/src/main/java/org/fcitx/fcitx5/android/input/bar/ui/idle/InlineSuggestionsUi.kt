/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerOn
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent

class InlineSuggestionsUi(override val ctx: Context) : Ui {

    private val scrollView = ctx.view(::HorizontalScrollView) {
        isFillViewport = true
        scrollBarSize = dp(1)
    }

    private val scrollSurfaceView = ctx.view(::SurfaceView) {
        setZOrderOnTop(true)
    }

    private val scrollableContentViews = mutableListOf<InlineContentView>()

    private val pinnedView = frameLayout { }

    private var pinnedContentView: InlineContentView? = null

    override val root = constraintLayout {
        add(scrollView, lParams(matchConstraints, matchParent) {
            startOfParent()
            before(pinnedView)
            centerVertically()
        })
        add(scrollSurfaceView, lParams(matchConstraints, matchParent) {
            centerOn(scrollView)
        })
        add(pinnedView, lParams(wrapContent, matchParent) {
            endOfParent()
            centerVertically()
        })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun clearScrollView() {
        scrollView.scrollTo(0, 0)
        scrollView.removeAllViews()
        scrollableContentViews.forEach { v ->
            v.surfaceControl?.let { sc ->
                SurfaceControl.Transaction().reparent(sc, null).apply()
            }
        }
        scrollableContentViews.clear()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun clearPinnedView() {
        pinnedView.removeAllViews()
        pinnedContentView = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun clear() {
        clearScrollView()
        clearPinnedView()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun setPinnedView(view: InlineContentView?) {
        pinnedView.removeAllViews()
        pinnedContentView = view?.also {
            pinnedView.addView(it)
            it.updateLayoutParams<FrameLayout.LayoutParams> {
                horizontalMargin = ctx.dp(10)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun setScrollableViews(views: List<InlineContentView?>) {
        // the last response's surfaces, reparented onto the scroll view's, stay drawn otherwise
        clearScrollView()
        val flexbox = view(::FlexboxLayout) {
            flexWrap = FlexWrap.NOWRAP
            justifyContent = JustifyContent.CENTER
        }
        val parentSurfaceControl = scrollSurfaceView.surfaceControl
        views.forEach {
            if (it == null) return@forEach
            scrollableContentViews.add(it)
            it.setSurfaceControlCallback(object : InlineContentView.SurfaceControlCallback {
                override fun onCreated(surfaceControl: SurfaceControl) {
                    SurfaceControl.Transaction()
                        .reparent(surfaceControl, parentSurfaceControl)
                        .apply()
                }

                override fun onDestroyed(surfaceControl: SurfaceControl) = Unit
            })
            flexbox.addView(it)
            it.updateLayoutParams<FlexboxLayout.LayoutParams> {
                flexShrink = 0f
            }
        }
        scrollView.apply {
            add(flexbox, lParams(wrapContent, matchParent))
        }
    }

    companion object {
        @SuppressLint("RestrictedApi")
        @RequiresApi(Build.VERSION_CODES.R)
        fun request(context: Context, theme: Theme): InlineSuggestionsRequest {
            val chipDrawable =
                if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
            val chipBg = Icon.createWithResource(context, chipDrawable).setTint(theme.keyTextColor)
            val style = InlineSuggestionUi.newStyleBuilder()
                .setSingleIconChipStyle(
                    ViewStyle.Builder()
                        .setBackgroundColor(Color.TRANSPARENT)
                        .setPadding(0, 0, 0, 0)
                        .build()
                )
                .setChipStyle(
                    ViewStyle.Builder()
                        .setBackground(chipBg)
                        .setPadding(context.dp(10), 0, context.dp(10), 0)
                        .build()
                )
                .setTitleStyle(
                    TextViewStyle.Builder()
                        .setLayoutMargin(context.dp(4), 0, context.dp(4), 0)
                        .setTextColor(theme.keyTextColor)
                        .setTextSize(14f)
                        .build()
                )
                .setSubtitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(theme.altKeyTextColor)
                        .setTextSize(12f)
                        .build()
                )
                .setStartIconStyle(
                    ImageViewStyle.Builder()
                        .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                        .build()
                )
                .setEndIconStyle(
                    ImageViewStyle.Builder()
                        .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                        .build()
                )
                .build()
            val styleBundle = UiVersions.newStylesBuilder()
                .addStyle(style)
                .build()
            val spec = InlinePresentationSpec
                .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
                .setStyle(styleBundle)
                .build()
            return InlineSuggestionsRequest.Builder(listOf(spec))
                .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
                .build()
        }
    }
}
