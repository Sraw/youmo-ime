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

/** Popups draw in the theme the keys shown now do, asked as they draw. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupFollowsTheKeysTest {

    @get:Rule
    val prefs = PrefsRule()

    private val popup = PopupComponent()

    // a name of its own: the fall backs are kept by name
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
        assertEquals(theme.name, popup.scenesNow.drawsIn)
        SceneBoard.shown("qwerty")
        assertTrue(popup.scenesNow.ready)
        assertEquals(builtIn, popup.scenesNow.drawsIn)
        SceneBoard.shown("k18")
        assertEquals(theme.name, popup.scenesNow.drawsIn)
    }

    // a bubble up as the keys fell back is not kept for the next: it would draw in the old theme
    @Test
    fun aPopupShowingAsTheKeysFellBackIsNotPooled() {
        SceneBoard.shown("k18")
        popup.listener.onPopupAction(PopupAction.PreviewAction(KeyRef("a"), "a", Rect(0, 0, 100, 100)))
        SceneBoard.keysFellBack(theme.name, look, "qwerty", true)
        SceneBoard.shown("qwerty")
        assertEquals(builtIn, popup.scenesNow.drawsIn)
        popup.dismissAll()
        popup.listener.onPopupAction(PopupAction.PreviewAction(KeyRef("b"), "b", Rect(0, 0, 100, 100)))
        assertEquals(builtIn, popup.showing(KeyRef("b"))?.scenes?.drawsIn)
        popup.dismissAll()
        assertEquals(listOf(builtIn), popup.pooled.map { it.scenes.drawsIn })
    }
}
