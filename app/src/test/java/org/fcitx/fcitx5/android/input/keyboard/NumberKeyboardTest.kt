/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The number keyboard builds, its space an image key (MiniSpaceKey): a text key's accessor of it
 * threw ClassCastException at its first read. Under the app, whose preferences the keys read; the
 * keys' descriptions are placeholders, as the tests have none of the app's strings. Each pad shows
 * the same keys drawn by its views as by the canvas (number.xml's, 060-render-p2 S4b).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class NumberKeyboardTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun keyboard(pad: NumberPad = NumberPad.FULL) =
        NumberKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, pad).laidOut()

    @Test
    fun theSpaceIsTheKeyItIsBuiltAs() {
        val space = keyboard().findViewById<View>(R.id.button_mini_space)
        assertTrue("$space", space is ImageKeyView)
    }

    // the space found by its view's name either way (uat.py, TalkBack's node: KeyNames.BOARD_RESOURCES)
    @Test
    fun theSpaceKeepsItsName() = bothDrawings { canvas ->
        val k = keyboard()
        assertEquals(canvas, KeyboardHarness.all(k).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        assertEquals("space", k.shownKeyWithId(R.id.button_mini_space)?.id)
    }

    // every pad: its keys as shown, in order, by name, label, what is read and size, the same either way; a blank
    // takes no press (its view disabled, the canvas's no key)
    @Test
    fun eachPadShowsTheSameKeys() {
        val seen = mutableMapOf<Boolean, List<String>>()
        bothDrawings { canvas ->
            seen[canvas] = NumberPad.entries.flatMap { pad ->
                listOf("$pad") + keyboard(pad).shownKeys().filter { it.enabled }
                    // a view's text is read as its text, no description: the scene's node says it
                    .map { "${it.id} '${it.label}' ${it.description ?: it.label} ${it.bounds.width()}×${it.bounds.height()}" }
            }
        }
        assertEquals(seen.getValue(false).joinToString("\n"), seen.getValue(true).joinToString("\n"))
    }

    // each pad's keys wired from number.xml's nodes as their views were (U4 B): sound, the shortcut its swipes
    // keep their symbols under, both ways' swipe, what a tap, a hold and a long press do; no bubble nor long
    // press's keys (preview="off" popups="off"), Return's Emoji menu alone (rules C 疑问 8)
    @Test
    fun eachKeyIsWiredAsItsViewWas() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        for (pad in NumberPad.entries) {
            val k = keyboard(pad)
            val scene = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single().scene!!
            val old = NumberKeyboard.layout(pad).flatten().filter { it !is BlankKey }.associateBy { it.name ?: (it.appearance as KeyDef.Appearance.Text).displayText }
            assertEquals("$pad", old.keys, scene.keys.map { it.id }.toSet())
            for (node in scene.keys) {
                val was = old.getValue(node.id)
                val now = SceneKeyDef.of(SceneKeyDef.onBoard(SceneBehavior.of(node), scene), node, object : SceneKeyDef.Table {})
                val at = "$pad ${node.id}"
                assertEquals(at, soundOf(was), now.sound)
                assertEquals(at, was.shortcut, now.shortcut)
                assertEquals(at, was.swipesBothWays, now.swipesBothWays)
                assertEquals(at, actions(was), actions(now))
                val menus = now.popup.orEmpty().map { p -> (p as? KeyDef.Popup.Menu)?.items?.map { it.action } ?: p }
                assertEquals(at, if (node.id == "enter") listOf(listOf(KeyAction.PickerSwitchAction())) else emptyList<Any>(), menus)
            }
        }
    }

    // a view's sound as BaseKeyboard.wire gives it
    private fun soundOf(def: KeyDef) = when (def) {
        is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
        is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
        is ReturnKey -> InputFeedbacks.SoundEffect.Return
        else -> InputFeedbacks.SoundEffect.Standard
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
