/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.app.Activity
import android.content.Context
import android.graphics.RenderNode
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A pack's draw mark left pending by the keyboard's canvas (its window hidden before the frame committed) is the
 * keyboard's: cleared once a keyboard canvas (that one, or one built anew as the theme changed) draws anything
 * other than the pack (a built-in theme, another pack even one already drawn this process), so the next start
 * resets no slot for a pack no longer shown; a canvas in an activity (a settings preview, a theme card) drawing
 * meanwhile keeps it, so a pack that then takes the keyboard down is still caught.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class PackDrawGuardClearTest {

    private val marks = File(appContext.cacheDir, "marks-clear")
    private val mark = File(marks, "theme-pack-drawing")
    private val mine = PackDrawGuard.Key("mine", 1L)
    private val other = PackDrawGuard.Key("other", 1L)

    @Before
    fun empty() {
        marks.deleteRecursively()
        marks.mkdirs()
        PackDrawGuard.dir = { marks }
        PackDrawGuard.reset()
    }

    @After
    fun putBack() {
        PackDrawGuard.dir = { appContext.noBackupFilesDir }
        PackDrawGuard.reset()
        marks.deleteRecursively()
    }

    // a canvas whose scene is of the pack [shows] names (null: a theme not a pack's), made anew as it changes
    private class Canvas(context: Context = appContext) {
        var shows: PackDrawGuard.Key? = null
            set(value) {
                field = value
                view.invalidateScene()
            }
        private val look = ThemeManager.shownLook(TestThemes.PixelLight)
        val view = KeyboardCanvasView(
            context, BoardImages(),
            object : KeyboardCanvasView.Host {
                override fun scene(width: Int, height: Int) =
                    KeyboardCanvasView.Drawn(Scene(width.toDouble(), height.toDouble(), emptyList(), emptyList(), emptyList()), look, SceneFonts(look), shows)

                override fun key(node: KeyNode) = null
            },
        ).apply {
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 200)
        }

        // a frame recorded on the RenderThread's canvas (hardware): never committed here, as with a window hidden
        fun frame() {
            val node = RenderNode("frame")
            view.draw(node.beginRecording(view.width, view.height))
            node.endRecording()
        }
    }

    private fun hiddenBeforeCommit(c: Canvas, key: PackDrawGuard.Key) {
        c.shows = key
        c.frame()
        assertEquals(key.file, mark.readText())
    }

    private fun preview() = Canvas(Robolectric.buildActivity(Activity::class.java).setup().get())

    @Test
    fun aKeyboardCanvasClearsTheMarkAsItDrawsAnythingElse() {
        assertRows(
            "the very canvas that marked it now drawing a built-in theme: the pack no longer shown" to {
                empty() // each row from no mark and nothing drawn
                val c = Canvas()
                hiddenBeforeCommit(c, mine)
                c.shows = null
                c.frame()
                assertFalse(mark.exists())
                // shown again later: marked again, its first frame still unconfirmed
                hiddenBeforeCommit(c, mine)
            },
            "the very canvas drawing a pack already drawn this process: that pack marks nothing, yet the marked one is gone" to {
                empty() // each row from no mark and nothing drawn
                PackDrawGuard.drawing(other)
                PackDrawGuard.frameDrawn(other)
                val c = Canvas()
                hiddenBeforeCommit(c, mine)
                c.shows = other
                c.frame()
                assertFalse(mark.exists())
            },
            "a keyboard canvas built anew (a theme change rebuilds the views) drawing a built-in theme, a preview drawing meanwhile" to {
                empty() // each row from no mark and nothing drawn
                hiddenBeforeCommit(Canvas(), mine)
                preview().frame()
                assertTrue(mark.exists())
                val rebuilt = Canvas()
                rebuilt.frame()
                assertFalse(mark.exists())
            },
        )
    }

    @Test
    fun aPreviewDrawingABuiltInThemeKeepsTheMark() {
        val keyboard = Canvas()
        hiddenBeforeCommit(keyboard, mine)
        val preview = preview()
        preview.frame()
        assertTrue(mark.exists())
        // nor does one drawing that same pack then something else clear it: the keyboard's to clear
        preview.shows = mine
        preview.frame()
        preview.shows = null
        preview.frame()
        assertEquals("mine", mark.readText())
        keyboard.shows = null
        keyboard.frame()
        assertFalse(mark.exists())
    }
}
