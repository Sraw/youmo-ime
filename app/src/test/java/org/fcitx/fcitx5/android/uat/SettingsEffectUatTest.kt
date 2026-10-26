/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
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

    // a keyboard made anew: one closed is not started again
    private fun fresh(): KeyboardHarness {
        made?.close()
        return KeyboardHarness().also { made = it }
    }

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        made?.close()
    }

    private val keyboard get() = AppPrefs.getInstance().keyboard
    private val theme get() = ThemeManager.prefs

    private fun sent() = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun space() = KeyboardHarness.all(h.inputView)
        .first { h.shown(it) && it.contentDescription?.toString()?.let { d -> d == "Space" || d.startsWith("Space,") } == true }

    private fun corner(label: String): String? = (h.key(label) as? AltTextKeyView)?.altText
        ?.takeIf { it.visibility == View.VISIBLE && it.text.isNotEmpty() }?.text?.toString()

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
        val space = h.box(space())
        assertTrue("$q -> $q2", q2.width() < q.width() && q2.left > q.left)
        assertTrue("bottom gap ${board.bottom - space.bottom}", board.bottom - space.bottom > h.service.resources.displayMetrics.density * 16)
    }

    @Test
    @Uat("ST-12")
    fun keyBorderSpacingAndCorners() {
        prefs.set(theme.keyBorder, false)
        h.start()
        h.capture("SettingsEffectUatTest/no_border")
        fresh()
        prefs.set(theme.keyBorder, true)
        h.start()
        // the drawn key is inset by its margin: what lies between two keys
        val gap = h.key("q").hMargin
        fresh()
        prefs.set(theme.keyHorizontalMargin, 5)
        prefs.set(theme.keyRadius, 0)
        h.start()
        val loose = h.key("q").hMargin
        assertTrue("gap $gap -> $loose", loose > gap)
        h.capture("SettingsEffectUatTest/loose_square")
    }

    @Test
    @Uat("ST-13")
    fun punctuationPositionNoneThenBottom() {
        prefs.set(theme.punctuationPosition, ThemePrefs.PunctuationPosition.None)
        h.start()
        assertNull(corner("w"))
        // the swipe types it all the same
        h.swipe(h.key("w"), 0f, h.key("w").height * 1.3f)
        assertTrue(sent().toString(), "key=2," in sent().last())
        fresh()
        prefs.set(theme.punctuationPosition, ThemePrefs.PunctuationPosition.Bottom)
        h.start()
        val w = h.key("w") as AltTextKeyView
        assertEquals("2", corner("w"))
        assertTrue("under the letter", h.box(w.altText).top >= h.box(w.mainText).centerY())
    }

    @Test
    @Uat("ST-14")
    fun noPopupOnKeyPress() {
        prefs.set(keyboard.popupOnKeyPress, false)
        h.start()
        h.press(h.key("a"))
        assertTrue(h.popupKeys().map { it.text }.toString(), h.popupKeys().none { it.text.toString() == "a" })
        h.release()
    }

    @Test
    @Uat("ST-15")
    fun swipeUpThenDisabled() {
        prefs.set(keyboard.swipeSymbolDirection, SwipeSymbolDirection.Up)
        h.start()
        val w = h.key("w")
        h.swipe(w, 0f, -w.height * 0.6f)
        assertTrue(sent().toString(), "key=2," in sent().last())
        h.api.calls.clear()
        h.swipe(w, 0f, w.height * 0.4f)
        assertTrue(sent().toString(), sent().size == 1 && "key=w," in sent().last())
        fresh()
        prefs.set(keyboard.swipeSymbolDirection, SwipeSymbolDirection.Disabled)
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
        assertTrue(h.popupKeys().map { it.text }.toString(), h.popupKeys().any { it.text.toString() == "1" })
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
            h.emit(FcitxEvent.CommitStringEvent(FcitxEvent.CommitStringEvent.Data("(", -1)))
        }
        assertEquals("(", h.field.text.toString())
    }

    @Test
    @Uat("ST-20")
    fun noLanguageKeyAWiderSpace() {
        h.start()
        val wide = h.box(space()).width()
        fresh()
        prefs.set(keyboard.showLangSwitchKey, false)
        h.start()
        assertTrue(KeyboardHarness.all(h.inputView).none { h.shown(it) && it.contentDescription == "Switch language" })
        assertTrue(h.box(space()).width() > wide)
    }

    @Test
    @Uat("ST-21")
    fun aCharacterMadeFirstIsTheCornerAndTheSwipe() {
        // as 设为首选 stores it: Q first of q's
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, "q Q 1")
        h.start()
        assertEquals("Q", corner("q"))
        h.swipe(h.key("q"), 0f, h.key("q").height * 1.3f)
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
