/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.picker.EmojiSearchUi
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What is typed ([SceneEnvs.typing]) reaches every keyboard shown; one shown again catches up; nothing changed makes no scene. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class TypingWatchTest {

    private var h: KeyboardHarness? = null

    @After
    fun reset() {
        h?.close()
        SceneEnvs.typing = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val keyboards: List<(Context) -> BaseKeyboard> = listOf(
        { NumberRow(it, ThemeManager.activeTheme) },
        { ExpandedCandidateLayout.Keyboard(it, ThemeManager.activeTheme) },
        { EmojiSearchUi.Keyboard(it, ThemeManager.activeTheme) },
    )

    @Test
    fun eachKeyboardShownDrawsWhatIsTypedNow() {
        val h = KeyboardHarness().also { h = it }
        h.start()
        val box = FrameLayout(h.service)
        (h.service.window.window!!.decorView as ViewGroup).addView(box, ViewGroup.LayoutParams(1000, 600))
        fun show(kb: BaseKeyboard) {
            box.addView(kb, ViewGroup.LayoutParams(1000, 600))
            idle()
        }
        for ((i, make) in keyboards.withIndex()) {
            val kb = make(h.service)
            val name = kb.javaClass.simpleName
            show(kb)
            val canvas = KeyboardHarness.all(kb).filterIsInstance<KeyboardCanvasView>().single()
            val first = canvas.scene
            assertNotNull(name, first)
            SceneEnvs.typing = SceneEnvs.typing?.toSet()
            assertSame(name, first, canvas.scene)
            SceneEnvs.typing = setOf("field=password", "test=$i")
            val told = canvas.scene
            assertNotSame(name, first, told)
            box.removeView(kb)
            SceneEnvs.typing = setOf("field=url", "test=$i")
            show(kb)
            val again = canvas.scene
            assertNotSame(name, told, again)
            box.removeView(kb)
            show(kb)
            assertSame(name, again, canvas.scene)
            box.removeView(kb)
        }
    }
}
