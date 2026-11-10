/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.RenderNode
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A pack's draw mark left pending by the keyboard's canvas (its window hidden before the frame committed) is that
 * view's: cleared once that view draws anything other than the pack (a built-in theme, another pack even one
 * already drawn this process), so the next start resets no slot for a pack no longer shown; another canvas (a
 * settings preview, a theme card) drawing meanwhile keeps it, so a pack that then takes the keyboard down is
 * still caught.
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
    private class Canvas {
        var shows: PackDrawGuard.Key? = null
            set(value) {
                field = value
                view.invalidateScene()
            }
        private val look = ThemeManager.shownLook(TestThemes.PixelLight)
        val view = KeyboardCanvasView(
            appContext, BoardImages(),
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

    @Test
    fun theViewThatMarkedAPackClearsItAsItDrawsABuiltInTheme() {
        val c = Canvas()
        hiddenBeforeCommit(c, mine)
        c.shows = null
        c.frame()
        assertFalse(mark.exists())
        // shown again later: marked again, its first frame still unconfirmed
        hiddenBeforeCommit(c, mine)
    }

    @Test
    fun theViewThatMarkedAPackClearsItAsItDrawsAPackAlreadyDrawn() {
        PackDrawGuard.drawing(other)
        PackDrawGuard.frameDrawn(other)
        val c = Canvas()
        hiddenBeforeCommit(c, mine)
        c.shows = other
        c.frame()
        assertFalse(mark.exists())
    }

    @Test
    fun anotherCanvasDrawingABuiltInThemeKeepsTheMark() {
        val keyboard = Canvas()
        hiddenBeforeCommit(keyboard, mine)
        val preview = Canvas()
        preview.frame()
        assertTrue(mark.exists())
        // nor does one drawing that same pack take it over: the keyboard's view still the one to clear it
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
