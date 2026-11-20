/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.ThemePictureDrawable
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.ui.main.settings.layout.LayoutBoards
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view

class KeyboardPreviewUi(override val ctx: Context, val theme: Theme) : Ui {

    var intrinsicWidth: Int = -1
        private set

    var intrinsicHeight: Int = -1
        private set

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    // on the display as held, not this page's window
    private val keyboardSidePaddingPx: Int get() = KeyboardArea.sidePadding(KeyboardArea.displayScreen(ctx))

    private val keyboardBottomPaddingPx: Int get() = KeyboardArea.bottomPadding(KeyboardArea.displayScreen(ctx))

    private val keyboardBottomFlush = keyboardPrefs.keyboardBottomFlush

    private val sizeChangeListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        recalculateSize()
    }

    // the letters split per the held shape's 键盘样式: no longer a theme change, so remade here
    private val styleChangeListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        makeKeyboard()
    }

    private val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    // read each time as 候选字号 can change on this page; floating over the app, the code row is no part of it
    private val barHeight get() = ctx.dp(KawaiiBarComponent.heightDp(displayCtx, barPercent()) + if (KawaiiBarComponent.codeRowFloats()) 0 else CodeRowHeight.THIN_DP)
    private val fakeKawaiiBar = view(::View)

    // the shown theme's `bar height=`, the one made with before setTheme
    private fun barPercent(): Double = SceneBar.of(displayCtx, if (::shownTheme.isInitialized) shownTheme else theme).heightPercent

    // keys and bar read 横屏, split and width from their context: the display's as held; remade as it turns
    private var displayCtx: Context = KeyboardArea.displayContext(ctx)

    private var keyboardWidth = -1
    private var keyboardHeight = -1
    private lateinit var fakeKeyboardWindow: View

    private var shownLayout: SceneLayout? = null

    private val fakeInputView = constraintLayout {
        add(bkg, lParams {
            centerInParent()
        })
        add(fakeKawaiiBar, lParams(height = barHeight) {
            centerHorizontally()
        })
    }

    override val root = object : FrameLayout(ctx) {
        init {
            add(fakeInputView, lParams())
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            recalculateSize()
            keyboardBottomFlush.registerOnChangeListener(sizeChangeListener)
            // the docked handle is counted in the height cap
            ThemeManager.prefs.alwaysShowHandle.registerOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.codeRow.registerOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.candidateTextSize.registerOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.keyboardStylePortrait.registerOnChangeListener(styleChangeListener)
            ThemeManager.prefs.keyboardStyleLandscape.registerOnChangeListener(styleChangeListener)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            displayCtx = KeyboardArea.displayContext(ctx)
            recalculateSize()
            // split or not as the screen turned: remade, as the input view is
            makeKeyboard()
        }

        override fun onDetachedFromWindow() {
            keyboardBottomFlush.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.alwaysShowHandle.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.codeRow.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.candidateTextSize.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.keyboardStylePortrait.unregisterOnChangeListener(styleChangeListener)
            ThemeManager.prefs.keyboardStyleLandscape.unregisterOnChangeListener(styleChangeListener)
            super.onDetachedFromWindow()
        }
    }

    private fun keyboardWindowAspectRatio(): Pair<Int, Int> {
        // not this page's window, half of it in a split screen
        val screen = KeyboardArea.displayScreen(ctx)
        val w = screen.widthPx
        // the app's 120 dp kept as InputView keeps it, or a landscape 很高 preview stands taller; the docked handle counted too
        val handle = if (ThemeManager.prefs.alwaysShowHandle.getValue()) ctx.dp(FloatingHandle.HEIGHT_DP) else 0
        val requested = KeyboardArea.requestedHeight(screen)
        val room = KeyboardArea.predictedRoom(screen)
        return w to KeyboardHeightCap.keysHeight(requested, room, barHeight, keyboardBottomPaddingPx, handle, density = screen.density)
    }

    // their text and marks are set as made. Above init, which sets it
    private var compactShown = false

    init {
        val (w, h) = keyboardWindowAspectRatio()
        keyboardWidth = w
        keyboardHeight = h
        setTheme(theme)
    }

    fun recalculateSize() {
        val (w, h) = keyboardWindowAspectRatio()
        keyboardWidth = w
        keyboardHeight = h
        if (keyboardCompact() != compactShown) makeKeyboard()
        fakeKawaiiBar.updateLayoutParams<ConstraintLayout.LayoutParams> { height = barHeight }
        fakeKeyboardWindow.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = keyboardHeight
            horizontalMargin = keyboardSidePaddingPx
        }
        intrinsicWidth = keyboardWidth
        // KawaiiBar height + WindowManager view height
        intrinsicHeight = barHeight + keyboardHeight
        intrinsicHeight += keyboardBottomPaddingPx
        // windowInsets navbar padding; none at 贴底, as InputView leaves it out there
        if (!KeyboardArea.bottomFlush()) {
            ViewCompat.getRootWindowInsets(root)?.also {
                // IME window has different navbar height when system navigation in "gesture navigation" mode
                // thus the inset from Activity root window is unreliable
                if (it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom > 0 ||
                    // in case navigation hint was hidden ...
                    it.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom > 0
                ) {
                    intrinsicHeight += ctx.navbarFrameHeight()
                }
            }
        }
        fakeInputView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = intrinsicWidth
            height = intrinsicHeight
        }
    }

    fun setTheme(theme: Theme) {
        // the first, from init: measured there, and again as it is attached
        val switched = ::shownTheme.isInitialized
        val look = ThemeManager.shownLook(theme)
        // the keys over the picture find it here (PictureHost)
        bkg.background = ThemePictureDrawable.board(look)
        val landscape = KeyboardArea.displayScreen(ctx).landscape
        PictureHost.install(fakeInputView, look.picture?.let { PictureHost(look, bkg, landscape) })
        fakeKawaiiBar.background = SceneBar.background(SceneBar.of(displayCtx, theme))
        shownTheme = theme
        if (!switched) return makeKeyboard()
        // a size into or out of 紧凑 makes the keys itself, else they are made here: once either way
        val compact = compactShown
        recalculateSize()
        if (compactShown == compact) makeKeyboard()
    }

    fun showLayout(layout: SceneLayout?) {
        if (layout === shownLayout) return
        shownLayout = layout
        makeKeyboard()
    }

    private lateinit var shownTheme: Theme

    private fun makeKeyboard() {
        if (!this::shownTheme.isInitialized) return
        if (this::fakeKeyboardWindow.isInitialized) {
            fakeInputView.removeView(fakeKeyboardWindow)
        }
        compactShown = keyboardCompact()
        fakeKeyboardWindow = shownLayout?.let { LayoutBoards.view(displayCtx, shownTheme, it) }
            ?: TextKeyboard(displayCtx, shownTheme).also { it.onAttach() }
        fakeInputView.apply {
            add(fakeKeyboardWindow, lParams(matchConstraints, keyboardHeight) {
                below(fakeKawaiiBar)
                centerHorizontally(keyboardSidePaddingPx)
            })
        }
    }
}
