/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The 26 keys drawn by the scene engine (AppPrefs.Internal.canvasKeyboard): one view drawing them,
 * the keys found by the scene's hit areas, a press swapping one key's node, a screen reader's node
 * a key; and every gesture doing what it does on the keys' own views.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardCanvasViewTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun harness(on: Boolean, im: org.fcitx.fcitx5.android.core.InputMethodEntry = KeyboardHarness.PINYIN): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
        return KeyboardHarness(im = im).start()
    }

    private fun KeyboardHarness.canvas(): KeyboardCanvasView? =
        KeyboardHarness.all(inputView).filterIsInstance<KeyboardCanvasView>().firstOrNull()

    private fun KeyboardHarness.keyView(name: String): KeyView =
        KeyboardHarness.all(inputView).filterIsInstance<KeyView>().first { it.themeKey == name && it.parent is View && (it.parent as View).parent is TextKeyboard }

    @Test
    fun offTheKeysDrawThemselves() = harness(false).use { h ->
        assertNull(h.canvas())
        assertTrue(h.shown(h.keyView("q")))
    }

    @Test
    fun onOneViewDrawsTheKeysTheirViewsNot() = harness(true).use { h ->
        val c = h.canvas()!!
        val q = h.keyView("q")
        assertFalse(h.shown(q))
        // laid out where they were: a popup and the glide trail still find them there
        assertTrue(q.width > 0)
        val scene = c.scene!!
        assertEquals(26, scene.keys.count { it.id.length == 1 })
        // every key the scene draws has its view, at the same place
        for (k in scene.keys) {
            val v = h.keyView(k.id)
            val box = h.box(v)
            val b = c.bounds(k.id)!!
            assertTrue("${k.id} at $b, its view at $box", box.contains(b.centerX(), b.centerY()))
            assertSame(k.id, v, c.keyViewAt(k.anchor.x.toFloat(), k.anchor.y.toFloat()))
        }
        assertEquals("q", c.keyAt("q")?.label)
        assertNull(c.keyAt("nothing"))
    }

    @Test
    fun aPressSwapsTheKeysNodeAlone() = harness(true).use { h ->
        val c = h.canvas()!!
        val q0 = c.shownNode("q")!!
        val w0 = c.shownNode("w")!!
        assertSame(q0, c.shownNode("q"))
        assertTrue(c.press("q"))
        h.idle()
        assertTrue(h.keyView("q").isPressed)
        val q1 = c.shownNode("q")
        assertNotSame(q0, q1)
        assertSame(w0, c.shownNode("w"))
        c.press("q", down = false)
        h.idle()
        assertSame(q0, c.shownNode("q"))
        // Shift relabels the letters: their nodes anew, the rest kept
        val space = c.shownNode("space")
        h.tap(h.keyView("shift"))
        assertNotSame(q0, c.shownNode("q"))
        assertSame(space, c.shownNode("space"))
        assertEquals("Q", c.keyAt("q")?.label)
    }

    @Test
    fun aScreenReaderReadsAKeyANode() = harness(true).use { h ->
        val c = h.canvas()!!
        val provider = c.accessibilityNodeProvider!!
        val root = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        val keys = c.scene!!.keys
        assertEquals(keys.size, root.childCount)
        val nodes = keys.indices.map { provider.createAccessibilityNodeInfo(it)!! }
        val q = nodes[keys.indexOfFirst { it.id == "q" }]
        assertEquals("q", q.text.toString())
        assertTrue(q.isClickable)
        val space = nodes[keys.indexOfFirst { it.id == "space" }]
        assertEquals(c.resources.getResourceName(R.id.button_space), space.viewIdResourceName)
        assertEquals(h.keyView("space").contentDescription.toString(), space.contentDescription.toString())
        val shift = nodes[keys.indexOfFirst { it.id == "shift" }]
        assertEquals(c.context.getString(R.string.a11y_key_shift), shift.contentDescription.toString())
        assertEquals(c.resources.getResourceName(R.id.button_caps), shift.viewIdResourceName)
        // a key's own click, through its node
        h.api.calls.clear()
        assertTrue(provider.performAction(keys.indexOfFirst { it.id == "w" }, AccessibilityNodeInfo.ACTION_CLICK, null))
        h.idle()
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        // the hidden views are no one's to read
        val own = AccessibilityNodeInfo.obtain()
        h.keyView("q").onInitializeAccessibilityNodeInfo(own)
        assertFalse(own.isVisibleToUser)
    }

    // the same fingers on both: what reaches the engine and the field, and the popups shown, the same
    private fun gestures(on: Boolean, im: org.fcitx.fcitx5.android.core.InputMethodEntry): List<Any> = harness(on, im).use { h ->
        val out = mutableListOf<Any>()
        h.field.setText("hello world")
        h.field.setSelection(5)
        h.idle()
        h.press(h.keyView("q"))
        h.release()
        h.swipe(h.keyView("w"), 0f, -h.box(h.keyView("w")).height().toFloat())
        out.add(h.longPress(h.keyView("e")))
        h.swipe(h.keyView("space"), -h.box(h.keyView("space")).width() / 2f, 0f)
        h.press(h.keyView("backspace"))
        h.release()
        h.swipe(h.keyView("backspace"), -h.box(h.keyView("space")).width() / 2f, 0f)
        out.add(h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") || it.startsWith("reset") })
        out += h.field.text.toString()
        out += h.field.selectionStart
        out
    }

    @Test
    fun everyGestureAsOnTheViewsPinyin() = assertEquals(gestures(false, KeyboardHarness.PINYIN), gestures(true, KeyboardHarness.PINYIN))

    @Test
    fun everyGestureAsOnTheViewsEnglish() = assertEquals(gestures(false, KeyboardHarness.ENGLISH), gestures(true, KeyboardHarness.ENGLISH))

    // the default theme's 26 keys, the views' and the scene's side by side: for the eye (060's comparison)
    @Test
    fun sideBySide() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, false)
        val shots = listOf(false, true).map { on ->
            prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
            KeyboardHarness(ThemeManager.DefaultTheme).start().use { h ->
                // past the keyboard's sliding in
                h.after(1000)
                h.relayout()
                val v = h.keyboard
                Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            }
        }
        val title = 64
        val gap = 16
        val w = shots.sumOf { it.width } + gap
        val out = Bitmap.createBitmap(w, shots.maxOf { it.height } + title, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = Color.BLACK }
        var x = 0f
        for ((i, s) in shots.withIndex()) {
            c.drawText(if (i == 0) "旧" else "新", x + s.width / 2f, 48f, paint)
            c.drawBitmap(s, x, title.toFloat(), null)
            x += s.width + gap
        }
        val file = File("build/outputs/canvas-compare/qwerty-default.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
}
