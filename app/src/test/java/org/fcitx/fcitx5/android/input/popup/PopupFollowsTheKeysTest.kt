/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.ContextThemeWrapper
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The popups draw in the theme the keys shown now do, asked as they draw: a keyboard whose keys the
 * engine refused (the built-in drawing them) after the popups were first made, then another keyboard
 * whose keys are the theme's own (060-render-p2 U8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupFollowsTheKeysTest {

    @get:Rule
    val prefs = PrefsRule()

    private val popup = PopupComponent()

    // a theme of its own name (the fall backs kept by name), the built-in's look
    private val theme: Theme = Theme.Custom("follows${System.nanoTime()}", true, TestThemes.PixelDark.look)
    private val look by lazy { ThemeManager.shownLook(theme) }

    @Before
    fun setUp() {
        val scope = DynamicScope()
        val context: ContextThemeWrapper = ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_DeviceDefault)
        scope += context.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += PunctuationComponent()
        scope += popup
    }

    @After
    fun tearDown() {
        SceneBoard.keysFellBack(theme.name, look, "qwerty", false)
        SceneBoard.shown(null)
    }

    private val builtIn = ThemeManager.builtin("pixel#dark").name

    @Test
    fun popupsMadeBeforeTheKeysFellBackFollowThemAsTheyDraw() {
        SceneBoard.shown("k18")
        assertTrue(popup.scenesNow.ready)
        assertEquals(theme.name, popup.scenesNow.drawsIn)
        SceneBoard.keysFellBack(theme.name, look, "qwerty", true)
        // another keyboard's keys refused: not the one shown
        assertEquals(theme.name, popup.scenesNow.drawsIn)
        SceneBoard.shown("qwerty")
        assertTrue(popup.scenesNow.ready)
        assertEquals(builtIn, popup.scenesNow.drawsIn)
        // the theme's own keyboard shown again: its own again
        SceneBoard.shown("k18")
        assertEquals(theme.name, popup.scenesNow.drawsIn)
    }

    // a bubble still up as the keys fell back goes away and is not kept for the next: it would draw in the old theme
    @Test
    fun aPopupShowingAsTheKeysFellBackIsNotPooled() {
        SceneBoard.shown("k18")
        popup.listener.onPopupAction(PopupAction.PreviewAction(KeyRef("a"), "a", Rect(0, 0, 100, 100)))
        SceneBoard.keysFellBack(theme.name, look, "qwerty", true)
        SceneBoard.shown("qwerty")
        assertEquals(builtIn, popup.scenesNow.drawsIn)
        popup.dismissAll()
        // the next bubble shown draws in the built-in, as the keys: none of the old theme's taken from the pool
        popup.listener.onPopupAction(PopupAction.PreviewAction(KeyRef("b"), "b", Rect(0, 0, 100, 100)))
        assertEquals(builtIn, popup.showing(KeyRef("b"))?.scenes?.drawsIn)
        popup.dismissAll()
        assertEquals(listOf(builtIn), popup.pooled.map { it.scenes.drawsIn })
    }
}
