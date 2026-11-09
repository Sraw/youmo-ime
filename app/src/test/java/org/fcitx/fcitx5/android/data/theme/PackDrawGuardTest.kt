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

/**
 * A theme 4.0 pack's first frame of a process guarded ([PackDrawGuard]): marked as the keyboard's canvas draws it,
 * the mark gone once the frame is drawn, one left by a process that died with it resetting the settings naming
 * the pack at the next start.
 */
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
        // the theme in use the normal mode's, not the day's or night's
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

    // the pack `mine` of two versions (the built-in Nord's file under that name), imported and listed
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

    // a frame of the window drawn on the RenderThread (hardware), in ViewRootImpl.performDraw's order: the commit
    // callbacks registered till now taken first, then the keyboard's canvas [c] recorded; what is returned runs
    // once this frame is committed ([commit]). Robolectric's own windows draw no frame, so none is drawn otherwise
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
        // its theme built, for a keyboard made while hidden or a settings page's sizes, no frame drawn: nothing marked
        assertNotNull(SceneThemes.of(dark, ThemeManager.shownLook(dark)))
        ThemeManager.setNormalModeTheme(dark)
        SceneBar.heightPercent(appContext)
        assertFalse(mark.exists())
        KeyboardHarness(theme = dark).start().use { h ->
            val c = KeyboardHarness.boardCanvas(h.inputView)!!
            assertNotNull(c.scene)
            assertFalse(mark.exists())
            // its first frame: marked till the RenderThread has drawn it (its glyphs, its pictures)
            val first = frame(c)
            assertEquals("mine", mark.readText())
            // that frame committed: its callback was registered as it drew, so it goes with the next frame
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
            // drawn once in this process: the frame the second asked for drawn, not marked again, none more asked for
            h.after(50)
            commit(frame(c))
            assertFalse(mark.exists())
            h.after(50)
            assertFalse("no frame more asked for", c.isDirty)
        }
        // a frame drawn on a bitmap (software) is drawn then and there: no mark left
        PackDrawGuard.reset()
        KeyboardHarness(theme = dark).start().use { h ->
            softwareFrame(KeyboardHarness.boardCanvas(h.inputView)!!)
            assertFalse(mark.exists())
            assertTrue(PackDrawGuard.confirmed(key))
        }
        // imported again (another file time): guarded again
        val again = key.copy(stamp = key.stamp + 1)
        assertTrue(PackDrawGuard.drawing(again))
        PackDrawGuard.frameDrawn(again)
        // its first frame of a process recorded, never committed: the process went down with it
        PackDrawGuard.reset()
        KeyboardHarness(theme = dark).start().use { h -> frame(KeyboardHarness.boardCanvas(h.inputView)!!) }
        assertTrue(mark.exists())
        // the next start before the first unlock (the files not settled): nothing to reset, the mark kept for after
        directBoot(true)
        assertFalse(ThemeFilesManager.writable)
        ThemeManager.consultPackCrash()
        assertTrue(mark.exists())
        assertEquals(dark.name, ThemeManager.prefs.normalModeTheme.getValue().name)
        // settled: the settings naming it back to their defaults, the pack kept
        directBoot(false)
        ThemeManager.consultPackCrash()
        assertFalse(mark.exists())
        assertEquals(ThemeManager.prefs.normalModeTheme.defaultValue, ThemeManager.prefs.normalModeTheme.getValue())
        ThemeManager.refreshThemes()
        assertEquals(listOf("mine#light", "mine#dark"), ThemeManager.Packs.map { it.name })
    }
}
