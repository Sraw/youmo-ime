/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.RasterPacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.pet.PetSheets
import org.fcitx.fcitx5.android.input.pet.PetView
import org.fcitx.fcitx5.android.scene.pet.PetMachine
import org.fcitx.fcitx5.android.scene.pet.PetState.Name
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A theme's pet on the keyboard (uat/19-motion-notices.md MN-28–32): each state a colour of its own, as the device script draws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PetUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    @get:Rule
    val prefs = PrefsRule()

    @Before
    fun atOnce() = RasterPacks.atOnce()

    @After
    fun close() {
        harness?.close()
        RasterPacks.putBack()
        val internal = AppPrefs.getInstance().internal
        internal.floatingKeyboardX.setValue(internal.floatingKeyboardX.defaultValue)
        internal.floatingKeyboardY.setValue(internal.floatingKeyboardY.defaultValue)
    }

    private val colors = mapOf(
        Name.IDLE to Color.GRAY, Name.TYPING to Color.RED, Name.DELETE to Color.BLUE, Name.ENTER to Color.GREEN,
        Name.SPACE to Color.YELLOW, Name.SLEEP to Color.DKGRAY,
    )

    private fun theme(pet: String = "<pet>", states: Set<Name> = colors.keys): Theme {
        val frames = mapOf(Name.IDLE to 4, Name.SLEEP to 2).withDefault { 6 }
        val xml = states.joinToString("") { n ->
            // slow enough that a capture, a second on, still shows the state
            val fps = if (n == Name.IDLE || n == Name.SLEEP) 4 else 2
            """<state name="${n.id}" sheet="pet-${n.id}.png" frames="${frames.getValue(n)}" fps="$fps"/>"""
        }
        val files = states.associate { "pet-${it.id}.png" to PetSheets.png(frames.getValue(it), frames.getValue(it), 64, colors.getValue(it)) }
        return RasterPacks.pack("$pet$xml</pet>", files)[0]
    }

    private fun start(theme: Theme): KeyboardHarness {
        ThemeManager.setNormalModeTheme(theme)
        return KeyboardHarness(theme = theme).start().also { harness = it }
    }

    private fun pet(): PetView? = KeyboardHarness.find<PetView>(h.inputView)?.takeIf { h.shown(it) }

    private val state get() = PetMachine.stateOf(pet()!!.frame)
    private val index get() = PetMachine.frameOf(pet()!!.frame)

    /** The pet's own box in window coordinates. */
    private fun petBox(): Rect = h.box(pet()!!)

    private fun backspace() = h.tap(h.keyWithId(R.id.button_backspace)!!)
    private fun enter() = h.tap(h.keyWithId(R.id.button_return)!!)
    private fun space() = h.tap(h.keyNamed("space"))

    @Test
    @Uat("MN-28")
    fun thePetChangesWithEachKindOfKey() {
        start(theme())
        assertEquals(Name.IDLE, state)
        h.capture("PetUatTest/idle")
        for ((press, name) in listOf<Pair<() -> Unit, Name>>({ h.tap(h.key("q")) } to Name.TYPING, ::backspace to Name.DELETE, ::enter to Name.ENTER, ::space to Name.SPACE)) {
            press()
            assertEquals(name, state)
            h.capture("PetUatTest/${name.id}")
            assertEquals(name to 2, state to index)
            h.after(2500)
            assertEquals("$name played, then idle", Name.IDLE, state)
        }
        // typing on goes on from where it is, not from the start
        h.tap(h.key("w"))
        h.after(600)
        val at = index
        assertTrue(at > 0)
        h.tap(h.key("e"))
        assertEquals(Name.TYPING to at, state to index)
        // another field (its finish and start in one message, through the service): neither hidden nor back to idle
        h.another()
        assertEquals(Name.TYPING to at, state to index)
        h.after(7000)
        // the key under the pet takes the touch
        val box = petBox()
        val under = h.keys().first { it.bounds.contains(box.centerX(), box.centerY()) && it.label.length == 1 }
        val before = h.api.calls.size
        h.press(under)
        h.release()
        assertTrue("${under.label} reached the keys", h.api.calls.drop(before).any { it.startsWith("sendKey") })
        assertEquals(Name.TYPING, state)
        // 30 s without a key: asleep; a key wakes it
        h.after(31_000)
        assertEquals(Name.SLEEP, state)
        h.capture("PetUatTest/sleep")
        h.tap(h.key("a"))
        assertEquals(Name.TYPING, state)
        // over the emoji board it is gone; back, and shown again, idle
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        assertNull(pet())
        h.tap(h.keyNamed("back"))
        assertEquals(Name.IDLE to 0, state to index)
        h.tap(h.key("s"))
        h.hide()
        h.restart()
        h.idle()
        assertEquals(Name.IDLE to 0, state to index)
    }

    @Test
    @Uat("MN-29")
    fun thePetStandsStillWithKeyboardMotionOff() {
        start(theme())
        val motion = AppPrefs.getInstance().advanced.keyboardMotion
        h.tap(h.key("q"))
        prefs.set(motion, false)
        h.idle()
        assertEquals("stopped at once", Name.IDLE to 0, state to index)
        for (press in listOf({ h.tap(h.key("q")) }, ::backspace, ::enter, ::space)) {
            press()
            assertEquals(Name.IDLE to 0, state to index)
        }
        h.after(31_000)
        assertEquals(Name.IDLE to 0, state to index)
        prefs.set(motion, true)
        h.idle()
        h.tap(h.key("q"))
        assertEquals(Name.TYPING, state)
    }

    @Test
    @Uat("MN-33")
    fun thePetStillPlaysAfterTheSystemsDarkModeSwitches() {
        val theme = theme()
        // as the theme list picks one with 跟随系统深色模式 on: both slots, each showing the system's version
        prefs.set(ThemeManager.prefs.followSystemDayNightTheme, true)
        prefs.set(ThemeManager.prefs.lightModeTheme, theme)
        prefs.set(ThemeManager.prefs.darkModeTheme, theme)
        start(theme)
        try {
            for (on in listOf(true, false)) {
                val config = Configuration(appContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if (on) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
                FcitxApplication.getInstance().onConfigurationChanged(config)
                h.service.onConfigurationChanged(config)
                // the app's screen remade for it: the keyboard hidden and shown again at once, its window never away
                h.service.hideWindow()
                h.service.showWindow(true)
                h.idle()
                assertEquals("night $on", Name.IDLE to 0, state to index)
                h.tap(h.key("q"))
                assertEquals("night $on: a key plays", Name.TYPING, state)
                h.after(2500)
            }
            h.after(31_000)
            assertEquals(Name.SLEEP, state)
        } finally {
            val day = Configuration(appContext.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }
            FcitxApplication.getInstance().onConfigurationChanged(day)
        }
    }

    @Test
    @Uat("MN-30")
    fun aBarPetLeavesItsWidthToTheCandidates() {
        start(theme("""<pet place="bar-end">""", setOf(Name.IDLE, Name.TYPING)))
        for (c in "nihao") h.tap(h.key(c.toString()))
        h.relayout()
        val box = petBox()
        val bar = h.box(h.bar)
        assertTrue("$bar ends before the pet $box", bar.right <= box.left)
        assertTrue("on the bar's row", box.top >= bar.top && box.bottom <= bar.bottom)
        h.capture("PetUatTest/bar_candidates", h.inputView)
        h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        h.after(500)
        assertTrue("the toolbar too", h.box(h.bar).right <= petBox().left)
        h.capture("PetUatTest/bar_toolbar", h.inputView)
    }

    @Test
    @Uat("MN-32")
    fun thePetGoesWithTheBoardAndABrokenPetIsLeftOut() {
        val theme = theme(states = setOf(Name.IDLE, Name.TYPING))
        for ((style, side) in listOf(KeyboardStyle.ONE_HANDED to OneHanded.Left, KeyboardStyle.ONE_HANDED to OneHanded.Right, KeyboardStyle.FLOATING to null)) {
            prefs.setStyle(style, side = side)
            start(theme)
            val keys = h.box(h.board)
            val box = petBox()
            assertEquals("$style $side", keys.right to keys.top, box.right to box.top)
            val letters = h.box(h.keyboard)
            assertTrue("$style $side: $box over the letters, not the side column $letters", box.left >= letters.left && box.right <= letters.right)
            h.capture("PetUatTest/board_${style.name.lowercase()}_${side?.name?.lowercase() ?: "moved"}", h.inputView)
            harness?.close()
            harness = null
        }
        prefs.setStyleDefault()
        val broken = theme(states = setOf(Name.TYPING))
        assertNull((broken as Theme.Pack).drawn.pet)
        start(broken)
        assertNull(KeyboardHarness.find<PetView>(h.inputView))
        assertNotEquals(0, h.keys().size)
    }
}
