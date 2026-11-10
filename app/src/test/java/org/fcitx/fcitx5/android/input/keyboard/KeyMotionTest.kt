/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 060-render-p3 A1: a theme's keys moving on the canvas. The pressed face shown whole at once, let go
 * fading over the face under it, scaled and sunk by its node's properties alone; a press while it moves
 * jumps to pressed; 键盘动效 off, battery saver: no animator made.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class KeyMotionTest {

    @get:Rule
    val prefs = PrefsRule()

    // it writes a user's pack: the themes settled writable whatever ran before in this JVM (phoneStorage)
    @Before
    fun phone() = phoneStorage()

    // a user's pack (motion is never a built-in theme's): material's file, named its own, [styles] put last; its light version
    private fun theme(vararg styles: String): Theme {
        val text = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_material)
            .replace("name=\"material\"", "name=\"moving\"")
            .replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>"))
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("theme.xml"))
                it.write(text.encodeToByteArray())
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack
        val versions = opened.commit().getOrThrow()
        ThemeManager.packPlaced(versions[0].file)
        return versions.first { !it.isDark }
    }

    private val moving by lazy {
        theme(
        "<style select=\"key\" transition=\"150ms ease-out\"/>",
            "<style select=\"key:pressed\" transition=\"60ms linear\" press-scale=\"90%\" press-sink=\"2dp\"/>",
        )
    }

    private fun harness(t: Theme = moving) = KeyboardHarness(theme = t).start()

    private fun KeyboardHarness.canvas(): KeyboardCanvasView = KeyboardHarness.boardCanvas(inputView)!!

    @Test
    fun thePressedFaceIsWholeAtTheFirstFrame() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        // no time gone: shown pressed (its face, not fading in), scaling from whole, 60 ms
        assertTrue(c.shownPressed("q"))
        assertSame(c.pressedNode("q"), c.shownNode("q"))
        assertEquals(1f, c.pressedNode("q")!!.scaleX, 0f)
        val press = c.animator("q")!!
        assertEquals(60L, press.duration)
        at(press, 30)
        assertEquals(0.95f, c.pressedNode("q")!!.scaleX, 1e-3f)
        frames(100)
        val n = c.pressedNode("q")!!
        assertEquals(0.9f, n.scaleX, 1e-4f)
        assertEquals(0.9f, n.scaleY, 1e-4f)
        assertEquals(2f * h.service.resources.displayMetrics.density, n.translationY, 1e-3f)
        // scaled about its key's middle
        val k = c.keyAt("q")!!
        assertEquals(k.bounds.centerX.toFloat(), n.left + n.pivotX, 0.5f)
        c.press("q", down = false)
        h.idle()
    }

    @Test
    fun letGoItFadesOutAndStops() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        frames(100)
        val pressed = c.pressedNode("q")!!
        c.press("q", down = false)
        assertTrue(!c.shownPressed("q"))
        assertEquals(1f, pressed.alpha, 1e-3f)
        val fade = c.animator("q")!!
        assertEquals(150L, fade.duration)
        at(fade, 75)
        assertTrue("${pressed.alpha}", pressed.alpha > 0f && pressed.alpha < 1f)
        assertTrue(pressed.scaleX > 0.9f && pressed.scaleX < 1f)
        frames(300)
        assertNull(c.animator("q"))
        assertEquals(0f, pressed.alpha, 1e-3f)
        assertEquals(1f, pressed.scaleX, 1e-4f)
        assertEquals(0f, pressed.translationY, 1e-4f)
        // ended: two animators made, the press's and the fade's, none since
        assertEquals(2, c.animatorsMade)
        frames(1000)
        assertEquals(2, c.animatorsMade)
    }

    @Test
    fun aPressWhileItFadesJumpsToPressed() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        frames(100)
        c.pressedNode("q")
        c.press("q", down = false)
        at(c.animator("q")!!, 50)
        c.press("q")
        // pressed whole, no fading, scaled at once: no animator for this press
        val made = c.animatorsMade
        val n = c.pressedNode("q")!!
        assertEquals(0.9f, n.scaleX, 1e-4f)
        assertSame(n, c.shownNode("q"))
        frames(500)
        assertEquals(made, c.animatorsMade)
        assertEquals(0.9f, n.scaleX, 1e-4f)
        c.press("q", down = false)
        h.idle()
    }

    @Test
    fun switchedOffNoAnimatorIsMade() {
        prefs.set(AppPrefs.getInstance().advanced.keyboardMotion, false)
        harness().use { h -> pressedStill(h) }
    }

    @Test
    fun disableAnimationTurnsItOffToo() {
        prefs.set(AppPrefs.getInstance().advanced.disableAnimation, true)
        harness().use { h -> pressedStill(h) }
    }

    @Test
    fun batterySaverTurnsItOffAtOnce() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        frames(100)
        c.pressedNode("q")
        c.press("q", down = false)
        frames(300)
        assertEquals(2, c.animatorsMade)
        shadowOf(appContext.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        appContext.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        h.idle()
        c.press("w")
        frames(100)
        assertEquals(1f, c.pressedNode("w")!!.scaleX, 0f)
        c.press("w", down = false)
        frames(300)
        assertEquals(2, c.animatorsMade)
        shadowOf(appContext.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(false)
        appContext.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        h.idle()
        c.press("w")
        frames(100)
        assertEquals(0.9f, c.pressedNode("w")!!.scaleX, 1e-4f)
        c.press("w", down = false)
        h.idle()
    }

    @Test
    fun aThemeWithoutMotionMakesNone() = harness(theme()).use { h ->
        assertTrue(h.canvas().shownKeys.all { it.motion == null })
        pressedStill(h)
    }

    @Test
    fun shiftsNewFaceFadesIn() = harness(theme("<style select=\"key:shifted, key:locked\" transition=\"200ms linear\"/>")).use { h ->
        val c = h.canvas()
        val before = c.shownNode("shift")
        c.press("shift")
        c.press("shift", down = false)
        h.idle()
        c.shownNode("shift")
        // the face it had over its new one, fading
        val leaving = c.leavingNode("shift")!!
        assertSame(before, leaving)
        assertEquals(1f, leaving.alpha, 1e-3f)
        at(c.animator("shift")!!, 100)
        assertEquals(0.5f, leaving.alpha, 1e-3f)
        assertTrue("${leaving.alpha}", leaving.alpha > 0f && leaving.alpha < 1f)
        frames(300)
        assertNull(c.leavingNode("shift"))
        // its fade over, the old face's display list freed at once (not at GC)
        // (Robolectric's RenderNode at 29 keeps a display list discarded: checked from 30 on)
        if (android.os.Build.VERSION.SDK_INT > 29) assertTrue(!leaving.hasDisplayList())
        // the letters relabelled have no transition of theirs: shown at once
        assertNull(c.leavingNode("q"))
    }

    @Test
    fun aKeyDownEndsTheOtherKeysFades() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        frames(100)
        val q = c.pressedNode("q")!!
        c.press("q", down = false)
        at(c.animator("q")!!, 30)
        assertTrue(q.alpha > 0f)
        // typing on: the next key's frame draws no fade with it
        c.press("w")
        assertNull(c.animator("q"))
        assertEquals(0f, q.alpha, 1e-3f)
        assertEquals(1f, q.scaleX, 1e-4f)
        c.press("w", down = false)
        h.idle()
    }

    @Test
    fun typedFastTheKeysDoNotMove() = harness().use { h ->
        val c = h.canvas()
        // the canvas told of each press as a touch tells it, no time let run between: Robolectric's paused looper runs an
        // animation to its end at the first frame let run, the clock with it, so keys a few ms apart are typed here at once
        fun key(k: String, down: Boolean) = c.showPressed(KeyRef(k), down)
        // the first key after a pause moves: its press and its fade
        key("q", true)
        c.pressedNode("q")
        key("q", false)
        val made = c.animatorsMade
        assertEquals(2, made)
        // then the keys sooner than the 150 ms fade after the one before: pressed (at its scale at once) and let go still
        for (k in listOf("w", "e", "r")) {
            key(k, true)
            assertEquals(0.9f, c.pressedNode(k)!!.scaleX, 1e-4f)
            key(k, false)
        }
        assertEquals(made, c.animatorsMade)
        h.idle()
    }

    @Test
    fun laidOutAnewNothingCrossfades() = harness(theme("<style select=\"key\" transition=\"200ms linear\"/>")).use { h ->
        val c = h.canvas()
        c.shownNode("q")
        // another size (one-handed, the height setting): every key's faces anew, shown at once, no old keyboard over them
        c.layout(c.left, c.top, c.right - 40, c.bottom)
        c.scene
        assertTrue(c.shownKeys.none { c.leavingNode(it.id) != null })
        assertEquals(0, c.animatorsMade)
    }

    @Test
    fun heldThroughALayoutItStaysPressed() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        frames(200)
        assertEquals(0.9f, c.pressedNode("q")!!.scaleX, 1e-4f)
        // laid out anew in the same state while held: still at its press scale, not back to whole
        c.layout(c.left, c.top, c.right - 40, c.bottom)
        c.scene
        assertEquals(0.9f, c.pressedNode("q")!!.scaleX, 1e-4f)
        c.press("q", down = false)
        h.idle()
    }

    @Test
    fun hiddenItMovesNoMore() = harness().use { h ->
        val c = h.canvas()
        c.press("q")
        frames(100)
        c.pressedNode("q")
        c.press("q", down = false)
        assertTrue(c.animator("q") != null)
        // the keyboard hidden keeps the view attached: its window not visible stops it
        c.dispatchWindowVisibilityChanged(android.view.View.GONE)
        assertNull(c.animator("q"))
    }

    // the clock run on by [ms] and the frames due in it: not the harness's after(), whose paused looper's next task
    // (an animation's next frame, posted ahead) may run past the end and the animation with it
    private fun frames(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // [a] at [ms] of its run, its frame's update as a frame there gives it: Robolectric's paused looper runs an
    // animation to its end at the first frame let run (its clock jumps by the duration), so a frame between is set
    private fun at(a: android.animation.ValueAnimator, ms: Long) {
        a.currentPlayTime = ms
    }

    // pressed and let go with nothing moving: no animator, the pressed face as ever
    private fun pressedStill(h: KeyboardHarness) {
        val c = h.canvas()
        c.press("q")
        frames(100)
        assertTrue(c.shownPressed("q"))
        assertEquals(1f, c.pressedNode("q")!!.scaleX, 0f)
        assertEquals(0f, c.pressedNode("q")!!.translationY, 0f)
        c.press("q", down = false)
        frames(300)
        assertEquals(0, c.animatorsMade)
        assertNull(c.leavingNode("q"))
    }
}
