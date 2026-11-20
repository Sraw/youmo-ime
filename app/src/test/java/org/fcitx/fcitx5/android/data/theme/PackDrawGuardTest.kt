/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.directBoot
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Marked as the canvas draws its first frame, cleared once drawn; one left by a dead process resets its settings next start. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class PackDrawGuardTest {

    private val dir get() = ThemeFilesManager.themeDir
    private val marks = File(appContext.cacheDir, "marks")
    private val mark = File(marks, "theme-pack-drawing")

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        // the normal mode's theme, not the day's or night's
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.refreshThemes()
        marks.mkdirs()
        PackDrawGuard.dir = { marks }
        PackDrawGuard.reset()
    }

    @After
    fun putBack() {
        directBoot(false)
        PackDrawGuard.dir = { appContext.noBackupFilesDir }
        PackDrawGuard.reset()
        marks.deleteRecursively()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
    }

    // a two-version pack `mine` (Nord's file), imported and listed
    private fun placed(): List<Theme.Pack> {
        val xml = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord).replace("name=\"nord\"", "name=\"mine\"")
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML))
                it.write(xml.encodeToByteArray())
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream()).getOrThrow() as ThemeFilesManager.Opened.Pack
        return opened.commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }
    }

    // a RenderThread frame in ViewRootImpl.performDraw's order: commit callbacks taken first, then [c] recorded; the result
    // runs once committed. Robolectric's windows draw no frame otherwise
    private fun frame(c: KeyboardCanvasView): List<Runnable> {
        val taken = ReflectionHelpers.callInstanceMethod<ArrayList<Runnable>?>(c.viewTreeObserver, "captureFrameCommitCallbacks").orEmpty()
        val node = RenderNode("frame")
        c.draw(node.beginRecording(c.width, c.height))
        node.endRecording()
        return taken
    }

    private fun commit(callbacks: List<Runnable>) = callbacks.forEach(Runnable::run)

    private fun softwareFrame(c: KeyboardCanvasView) = c.draw(Canvas(Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)))

    @Test
    fun aPackThatTookTheKeyboardDownIsNotDrawnAgainOnItsOwn() {
        val dark = placed()[1]
        val key = PackDrawGuard.key(dark)
        // built for a hidden keyboard or a settings page, no frame drawn: nothing marked
        assertNotNull(SceneThemes.of(dark, ThemeManager.shownLook(dark)))
        ThemeManager.setNormalModeTheme(dark)
        SceneBar.heightPercent(appContext)
        assertFalse(mark.exists())
        KeyboardHarness(theme = dark).start().use { h ->
            val c = KeyboardHarness.boardCanvas(h.inputView)!!
            assertNotNull(c.scene)
            assertFalse(mark.exists())
            // its first frame: marked till the RenderThread has drawn it
            val first = frame(c)
            assertEquals("mine", mark.readText())
            // committed: its callback was registered as it drew, so it goes with the next frame
            commit(first)
            assertTrue(mark.exists())
            // which is asked for (a keyboard shown and hidden untouched would draw no other)
            assertFalse(c.isDirty)
            h.after(50)
            assertTrue("another frame asked for", c.isDirty)
            // another pack's frame clears nothing
            PackDrawGuard.frameDrawn(PackDrawGuard.Key("other", 0))
            assertTrue(mark.exists())
            // the next frame committed: the first was drawn before it
            commit(frame(c))
            assertFalse(mark.exists())
            assertTrue(PackDrawGuard.confirmed(key))
            // drawn once this process: not marked again, none more asked for
            h.after(50)
            commit(frame(c))
            assertFalse(mark.exists())
            h.after(50)
            assertFalse("no frame more asked for", c.isDirty)
        }
        // a software (bitmap) frame is drawn then and there: no mark left
        PackDrawGuard.reset()
        KeyboardHarness(theme = dark).start().use { h ->
            softwareFrame(KeyboardHarness.boardCanvas(h.inputView)!!)
            assertFalse(mark.exists())
            assertTrue(PackDrawGuard.confirmed(key))
        }
        // reimported (another file time): guarded again
        val again = key.copy(stamp = key.stamp + 1)
        assertTrue(PackDrawGuard.drawing(again))
        PackDrawGuard.frameDrawn(again)
        // recorded, never committed: the process went down with it
        PackDrawGuard.reset()
        KeyboardHarness(theme = dark).start().use { h -> frame(KeyboardHarness.boardCanvas(h.inputView)!!) }
        assertTrue(mark.exists())
        // before the first unlock: nothing to reset, the mark kept for after
        directBoot(true)
        assertFalse(ThemeFilesManager.writable)
        ThemeManager.consultPackCrash()
        assertTrue(mark.exists())
        assertEquals(dark.name, ThemeManager.prefs.normalModeTheme.getValue().name)
        // settled: its settings back to defaults, the pack kept
        directBoot(false)
        ThemeManager.consultPackCrash()
        assertFalse(mark.exists())
        assertEquals(ThemeManager.prefs.normalModeTheme.defaultValue, ThemeManager.prefs.normalModeTheme.getValue())
        ThemeManager.refreshThemes()
        assertEquals(listOf("mine#light", "mine#dark"), ThemeManager.Packs.map { it.name })
    }

    // a device calling back no commit: MAX_ASKED frames asked, then none, the mark kept till a later commit
    @Test
    fun framesAskedForAPackPendingStopAfterAFew() {
        val dark = placed()[1]
        ThemeManager.setNormalModeTheme(dark)
        KeyboardHarness(theme = dark).start().use { h ->
            val c = KeyboardHarness.boardCanvas(h.inputView)!!
            repeat(PackDrawGuard.MAX_ASKED) {
                frame(c)
                h.after(50)
                assertTrue("frame ${it + 1} asked for", c.isDirty)
            }
            frame(c)
            h.after(50)
            assertFalse("no frame more asked for", c.isDirty)
            assertTrue(mark.exists())
            assertFalse(PackDrawGuard.confirmed(PackDrawGuard.key(dark)))
        }
    }
}
