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

/**
 * What is typed ([SceneEnvs.typing]) reaches every keyboard shown, not only KeyboardWindow's (060-render-p3
 * T5): the number row's, the expanded candidates', the emoji search's; one shown again after it changed
 * catches up, and nothing changed makes no scene anew.
 */
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
        // in the input method, where what is typed is the theme's (SceneEnvs.of): its window
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
            // told the same again: the same scene
            SceneEnvs.typing = SceneEnvs.typing?.toSet()
            assertSame(name, first, canvas.scene)
            // shown: told
            SceneEnvs.typing = setOf("field=password", "test=$i")
            val told = canvas.scene
            assertNotSame(name, first, told)
            // not shown as it changes, then shown again: made anew as it is
            box.removeView(kb)
            SceneEnvs.typing = setOf("field=url", "test=$i")
            show(kb)
            val again = canvas.scene
            assertNotSame(name, told, again)
            // shown again with nothing changed: as it was
            box.removeView(kb)
            show(kb)
            assertSame(name, again, canvas.scene)
            box.removeView(kb)
        }
    }
}
