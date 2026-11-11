/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT cases of uat/14 whose setting changes the keyboard: the setting as its page stores it, then
 * the keyboard it gives.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsEffectUatTest {

    private var made: KeyboardHarness? = null
    private val h get() = made ?: fresh()

    // a keyboard made anew, in [theme] if given: one closed is not started again
    private fun fresh(theme: Theme? = null): KeyboardHarness {
        made?.close()
        return KeyboardHarness(theme ?: TestThemes.PixelLight).also { made = it }
    }

    // the light preset as the theme edit page's choices [f] make it
    private fun edited(f: (ThemeLook) -> ThemeLook): Theme = Theme.Custom("edited", false, f(TestThemes.PixelLight.look))

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        made?.close()
    }

    private val keyboard get() = AppPrefs.getInstance().keyboard
    private val theme get() = ThemeManager.prefs

    private fun sent() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun space() = h.keys()
        .first { it.description?.let { d -> d == "Space" || d.startsWith("Space,") } == true }

    private fun corner(label: String): String? = h.key(label).alt

    @Test
    @Uat("ST-10")
    fun keyboardHeightFollowsTheSetting() {
        val heights = listOf(26, 38).map { percent ->
            prefs.set(keyboard.keyboardHeightPercent, percent)
            h.start()
            h.box(h.board).height().also { fresh() }
        }
        assertTrue("$heights", heights[1] > heights[0] * 1.3)
    }

    @Test
    @Uat("ST-11")
    fun sideAndBottomMarginsNarrowAndLiftTheKeys() {
        h.start()
        val q = h.box(h.key("q"))
        val board = h.box(h.board)
        fresh()
        prefs.set(keyboard.keyboardSidePadding, 24)
        prefs.set(keyboard.keyboardBottomPadding, 20)
        h.start()
        val q2 = h.box(h.key("q"))
        val space = space().bounds
        assertTrue("$q -> $q2", q2.width() < q.width() && q2.left > q.left)
        assertTrue("bottom gap ${board.bottom - space.bottom}", board.bottom - space.bottom > h.service.resources.displayMetrics.density * 16)
    }

    @Test
    @Uat("ST-12")
    fun keyBorderSpacingAndCorners() {
        // a theme the web page made flat: its bar in its colour
        fresh(edited { it.copy(keys = it.keys.copy(blocks = false), bar = BarBase.Color) })
        h.start()
        h.capture("SettingsEffectUatTest/no_border")
        fresh(edited { it })
        h.start()
        // the drawn key (its face) is inset by its margin in its cell: what lies between two keys
        val gap = h.key("q").let { (it.width - it.face.width()) / 2 }
        fresh(edited { it.copy(keys = it.keys.copy(radiusDp = 0, gap = Gap(5, 9, 5, 6))) })
        h.start()
        val loose = h.key("q").let { (it.width - it.face.width()) / 2 }
        assertTrue("gap $gap -> $loose", loose > gap)
        h.capture("SettingsEffectUatTest/loose_square")
    }

    @Test
    @Uat("ST-13")
    fun swipeUpPositionNoneThenBottom() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.None)
        h.start()
        assertNull(corner("w"))
        // the swipe types it all the same
        h.swipe(h.key("w"), 0f, -h.key("w").height * 1.3f)
        assertTrue(sent().toString(), "key=2," in sent().last())
        fresh()
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.Bottom)
        h.start()
        val w = h.key("w")
        assertEquals("2", corner("w"))
        assertTrue("under the letter", w.drawn("2")!!.top >= w.drawn("w")!!.centerY())
    }

    @Test
    @Uat("ST-14")
    fun noPopupOnKeyPress() {
        prefs.set(keyboard.popupOnKeyPress, false)
        h.start()
        h.press(h.key("a"))
        assertTrue(h.popupChoices().map { it.text }.toString(), h.popupChoices().none { it.text == "a" })
        h.release()
    }

    @Test
    @Uat("ST-15")
    fun swipeUpThenDisabled() {
        prefs.setLayers(SwipeLayersChoice.SymbolUpShortcutDown)
        h.start()
        val w = h.key("w")
        h.swipe(w, 0f, -w.height * 0.6f)
        assertTrue(sent().toString(), "key=2," in sent().last())
        h.api.calls.clear()
        h.swipe(w, 0f, w.height * 0.4f)
        assertTrue(sent().toString(), sent().size == 1 && "key=w," in sent().last())
        fresh()
        prefs.setLayers(SwipeLayersChoice.Off)
        h.start()
        for (dy in listOf(-1.3f, 1.3f)) {
            h.swipe(h.key("w"), 0f, h.key("w").height * dy)
            assertTrue("out of the key $dy: ${sent()}", sent().isEmpty())
        }
    }

    @Test
    @Uat("ST-16")
    fun aLongerLongPressDelay() {
        prefs.set(keyboard.longPressDelay, 450)
        h.start()
        h.press(h.key("q"))
        h.after(300)
        h.release()
        assertTrue(sent().toString(), sent().size == 1 && "key=q," in sent().last())
        h.press(h.key("q"))
        h.after(600)
        assertTrue(h.popupChoices().map { it.text }.toString(), h.popupChoices().any { it.text == "1" })
        h.release()
    }

    @Test
    @Uat("ST-17")
    fun holdingTheSpaceGoesToTheNextInputMethod() {
        prefs.set(keyboard.spaceKeyLongPressBehavior, SpaceLongPressBehavior.Enumerate)
        h.start()
        h.press(space())
        h.after(1000)
        h.release()
        assertTrue(h.api.calls.toString(), "enumerateIme(true)" in h.api.calls)
    }

    @Test
    @Uat("ST-18")
    fun spaceSwipeOffLeavesTheCursor() {
        prefs.set(keyboard.spaceSwipeMoveCursor, false)
        h.start()
        h.swipe(space(), -space().width * 0.6f, 0f)
        assertTrue(sent().toString(), sent().none { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
    }

    @Test
    @Uat("ST-19")
    fun bracketsUnpairedWhenOff() {
        prefs.set(keyboard.autoPairs, false)
        h.start()
        val shown = h.longPress(h.key("k"), "(")
        assertTrue(shown.toString(), "(" in shown)
        if (h.field.text.isEmpty()) {
            // sent to the engine: its commit back, as fcitx's punctuation gives it (the fake simulates no input),
            // is where the service pairs it or not
            assertEquals(h.api.calls.toString(), 1, sent().count())
            h.emit(HostEvent.Commit("(", -1))
        }
        assertEquals("(", h.field.text.toString())
    }

    @Test
    @Uat("ST-20")
    fun noLanguageKeyAWiderSpace() {
        h.start()
        val language = h.service.getString(R.string.a11y_key_switch_language)
        // the key is there before, so its absence after is the setting's
        assertTrue(h.keys().any { it.description == language })
        val wide = space().bounds.width()
        fresh()
        prefs.set(keyboard.showLangSwitchKey, false)
        h.start()
        assertTrue(h.keys().none { it.description == language })
        assertTrue(space().bounds.width() > wide)
    }

    @Test
    @Uat("ST-21")
    fun aCharacterMadeFirstIsTheCornerAndTheSwipe() {
        // as 设为首选 stores it: Q first of q's
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, "q Q 1")
        h.start()
        assertEquals("Q", corner("q"))
        h.swipe(h.key("q"), 0f, -h.key("q").height * 1.3f)
        assertTrue(sent().toString(), "key=Q," in sent().last())
    }

    @Test
    @Uat("ST-22")
    fun charactersAddedThenTheDefaultBack() {
        h.start()
        val preset = h.longPress(h.key("a"))
        fresh()
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, "a " + (preset + listOf("α", "β", "⒜")).joinToString(" "))
        h.start()
        val added = h.longPress(h.key("a"))
        assertTrue(added.toString(), "α" in added && "β" in added && "⒜" in added)
        fresh()
        AppPrefs.getInstance().internal.popupOverrides.setValue("")
        h.start()
        val back = h.longPress(h.key("a"))
        assertTrue(back.toString(), "α" !in back && "β" !in back)
    }
}
