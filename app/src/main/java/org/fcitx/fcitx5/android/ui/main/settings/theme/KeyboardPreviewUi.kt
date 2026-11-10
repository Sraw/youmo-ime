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
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.ThemePictureDrawable
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
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
    // as the keyboard's: the settings, and the theme in use where they follow it (KeyboardArea)
    private val keyboardSidePaddingPx: Int get() = KeyboardArea.sidePadding(ctx)

    private val keyboardBottomPaddingPx: Int get() = KeyboardArea.bottomPadding(ctx)

    private val navbarBackground = ThemeManager.prefs.navbarBackground
    private val keyboardBottomFlush = keyboardPrefs.keyboardBottomFlush

    private val sizeChangeListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        recalculateSize()
    }

    private val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    // the bar and the row above it for the code being typed, thin as over the full keyboard drawn here
    // (where the cap binds, a 14 键 user's real keys are the tall row's 14 dp shorter); read each time,
    // as 候选字号 can change on this page
    // the keyboard with nothing typed: floating over the app (编码行), the code row is no part of it
    private val barHeight get() = ctx.dp(KawaiiBarComponent.heightDp(ctx, barPercent()) + if (KawaiiBarComponent.codeRowFloats()) 0 else CodeRowHeight.THIN_DP)
    private val fakeKawaiiBar = view(::View)

    // the shown theme's `bar height=` (060-render-p3 T6b), the one made with before setTheme
    private fun barPercent(): Double = SceneBar.of(ctx, if (::shownTheme.isInitialized) shownTheme else theme).heightPercent

    private var keyboardWidth = -1
    private var keyboardHeight = -1
    private lateinit var fakeKeyboardWindow: TextKeyboard

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
            navbarBackground.registerOnChangeListener(sizeChangeListener)
            keyboardBottomFlush.registerOnChangeListener(sizeChangeListener)
            // the docked handle is counted in the height cap (keyboardWindowAspectRatio)
            ThemeManager.prefs.alwaysShowHandle.registerOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.codeRow.registerOnChangeListener(sizeChangeListener)
            // the bar's height follows the candidate text size (KawaiiBarComponent.heightDp)
            ThemeManager.prefs.candidateTextSize.registerOnChangeListener(sizeChangeListener)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            recalculateSize()
            // the letters split or not as the screen turned (TextKeyboard.Split): made anew, as the input view is
            makeKeyboard()
        }

        override fun onDetachedFromWindow() {
            navbarBackground.unregisterOnChangeListener(sizeChangeListener)
            keyboardBottomFlush.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.alwaysShowHandle.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.codeRow.unregisterOnChangeListener(sizeChangeListener)
            ThemeManager.prefs.candidateTextSize.unregisterOnChangeListener(sizeChangeListener)
            super.onDetachedFromWindow()
        }
    }

    private fun keyboardWindowAspectRatio(): Pair<Int, Int> {
        val resources = ctx.resources
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels
        // capped as the real keyboard is (InputView), or a landscape 很高 preview stands taller than it;
        // the docked handle (始终显示悬浮条) counted in the cap there too, though not drawn here
        val handle = if (ThemeManager.prefs.alwaysShowHandle.getValue()) ctx.dp(FloatingHandle.HEIGHT_DP) else 0
        val requested = KeyboardArea.sizes().heightPx(h, resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        return w to KeyboardHeightCap.keysHeight(requested, h, barHeight, keyboardBottomPaddingPx, handle)
    }

    // 紧凑 as the keys were last made (makeKeyboard): its text and marks are set as they are made.
    // Above init, which sets it
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
        // a level into or out of 紧凑: the keys made anew, as the keyboard's are
        if (keyboardCompact() != compactShown) makeKeyboard()
        fakeKawaiiBar.updateLayoutParams<ConstraintLayout.LayoutParams> { height = barHeight }
        fakeKeyboardWindow.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = keyboardHeight
            horizontalMargin = keyboardSidePaddingPx
        }
        intrinsicWidth = keyboardWidth
        // KawaiiBar height + WindowManager view height
        intrinsicHeight = barHeight + keyboardHeight
        // extra bottom padding
        intrinsicHeight += keyboardBottomPaddingPx
        // windowInsets navbar padding; none at 贴底, as InputView leaves it out there
        if (navbarBackground.getValue() == NavbarBackground.Full && !KeyboardArea.bottomFlush()) {
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
        // as the input view draws it: the keys over the picture find it here (PictureHost)
        bkg.background = ThemePictureDrawable.board(look)
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        PictureHost.install(fakeInputView, look.picture?.let { PictureHost(look, bkg, landscape) })
        // the bar as the input view has it, the theme's `bar` (SceneBar)
        fakeKawaiiBar.background = SceneBar.background(SceneBar.of(ctx, theme))
        shownTheme = theme
        if (!switched) return makeKeyboard()
        // another theme's keyboard size where the settings follow it (KeyboardArea.sizes), measured first: a
        // size into or out of 紧凑 makes the keys itself, else they are made here, once either way
        val compact = compactShown
        recalculateSize()
        if (compactShown == compact) makeKeyboard()
    }

    // what setTheme was last given: lateinit, as the init block above sets it
    private lateinit var shownTheme: Theme

    private fun makeKeyboard() {
        if (!this::shownTheme.isInitialized) return
        if (this::fakeKeyboardWindow.isInitialized) {
            fakeInputView.removeView(fakeKeyboardWindow)
        }
        compactShown = keyboardCompact()
        fakeKeyboardWindow = TextKeyboard(ctx, shownTheme).also {
            it.onAttach()
        }
        fakeInputView.apply {
            add(fakeKeyboardWindow, lParams(matchConstraints, keyboardHeight) {
                below(fakeKawaiiBar)
                centerHorizontally(keyboardSidePaddingPx)
            })
        }
    }
}
