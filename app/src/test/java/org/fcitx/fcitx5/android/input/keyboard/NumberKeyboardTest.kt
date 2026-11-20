/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Under the app, whose preferences the keys read; the keys' descriptions are placeholders, the tests having none of the app's strings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class NumberKeyboardTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun keyboard(pad: NumberPad = NumberPad.FULL) =
        NumberKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, pad).laidOut()

    // found by its view's name (uat.py, TalkBack's node: KeyNames.BOARD_RESOURCES)
    @Test
    fun theSpaceKeepsItsName() {
        val k = keyboard()
        assertTrue(KeyboardHarness.all(k).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        assertEquals("space", k.shownKeyWithId(R.id.button_mini_space)?.id)
    }

    // a blank takes no press
    @Test
    fun eachPadShowsItsKeysNamedReadAndSized() {
        for (pad in NumberPad.entries) {
            val keys = keyboard(pad).shownKeys().filter { it.enabled }
            assertTrue("$pad", keys.isNotEmpty())
            keys.forEach { assertTrue("$pad ${it.id}", it.id != null && (it.description ?: it.label).isNotEmpty() && !it.bounds.isEmpty) }
        }
    }

    // no bubble nor long press keys (preview="off" popups="off"), Return's Emoji menu alone
    @Test
    fun eachKeyIsWiredAsCommitted() {
        for (pad in NumberPad.entries) {
            val k = keyboard(pad)
            val scene = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single().scene!!
            val lines = scene.keys.map { node ->
                val now = SceneKeyDef.of(SceneKeyDef.onBoard(SceneBehavior.of(node), scene), node, object : SceneKeyDef.Table {})
                val popups = now.popup.orEmpty().map { p -> (p as? KeyDef.Popup.Menu)?.items?.map { it.action }?.let { "menu $it" } ?: "$p" }.toSet()
                WiringSnapshot.line(node.id, now.shortcut, now.swipesBothWays, actions(now), popups, WiringSnapshot.layoutSound(now.systemSound), now.isSpace, now.isBackspace, k.sceneKey(node)!!.keyEnabled)
            }
            WiringSnapshot.check("number-${pad.name.lowercase()}", lines)
        }
    }

    private fun actions(def: KeyDef) = def.behaviors.map {
        when (it) {
            is KeyDef.Behavior.Press -> "press ${it.action}"
            is KeyDef.Behavior.LongPress -> "long ${it.action}"
            is KeyDef.Behavior.Repeat -> "repeat ${it.action}"
            is KeyDef.Behavior.Swipe -> "swipe ${it.action}"
            is KeyDef.Behavior.DoubleTap -> "double ${it.action}"
        }
    }.toSet()
}
