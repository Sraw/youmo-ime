/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.materialPack
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import splitties.dimensions.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.time.Duration

/** `appear` and `transition`: a focus moved plays nothing again; a bubble sooner than the motion after the last does not move. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PopupMotionTest {

    @get:Rule
    val prefs = PrefsRule()

    // it writes a user's pack: the themes settled writable whatever ran before in this JVM
    @Before
    fun phone() = phoneStorage()

    private val key = Rect(55, 500, 95, 550)
    private val five = KeyDef.Popup.Keyboard.Explicit(arrayOf("1", "2", "3", "4", "5"))

    private fun pack(vararg styles: String): Theme = materialPack("moving", *styles)

    private val moving by lazy {
        pack(
            "<style select=\"preview\" appear=\"rise 100ms\" transition=\"120ms linear\"/>",
            "<style select=\"popup\" appear=\"scale 150ms\" transition=\"100ms ease-out\"/>",
            "<style select=\"menu\" appear=\"fade 80ms\"/>",
        )
    }

    // cancelled as the test ends, no bubble's job outliving it
    private val imes = mutableListOf<CoroutineScope>()

    @After
    fun cancelImes() = imes.forEach { it.cancel() }

    private fun popups(theme: Theme = moving): PopupComponent {
        val popup = PopupComponent()
        val scope = DynamicScope()
        scope += ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_DeviceDefault).wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        // a bubble let go sooner than 100 ms after it came up goes later, on the IME's scope
        val ime: CoroutineScope = MainScope().also { imes += it }
        scope += ime.wrapToUniqueComponent()
        scope += PunctuationComponent()
        scope += popup
        val exactly = { size: Int -> View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY) }
        popup.root.measure(exactly(150), exactly(600))
        popup.root.layout(0, 0, 150, 600)
        return popup
    }

    private fun PopupComponent.send(action: PopupAction) = listener.onPopupAction(action)

    private fun PopupComponent.last(): View = root.getChildAt(root.childCount - 1)

    private fun frames(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aBubbleRisesInAndFadesOut() {
        val p = popups()
        p.send(PopupAction.PreviewAction(ID, "q", key))
        val b = p.last()
        assertEquals(0f, b.alpha, 0f)
        assertEquals(b.dp(PopupMoves.RISE_DP).toFloat(), b.translationY, 0f)
        frames(300)
        assertEquals(1f, b.alpha, 0f)
        assertEquals(0f, b.translationY, 0f)
        p.send(PopupAction.DismissAction(ID))
        // Robolectric's paused looper ends the fade at its first frame
        assertEquals(1, p.root.childCount)
        frames(500)
        assertEquals(0, p.root.childCount)
        frames(500)
        p.send(PopupAction.PreviewAction(ID, "w", key))
        assertSame(b, p.last())
    }

    @Test
    fun aBubblesMotionFollowsTheEnvironmentItsStylesAreScopedBy() {
        // `@landscape` as `@im=` or `@field=`: what changes with the root's size the same
        val p = popups(pack("<style select=\"preview\" appear=\"rise 100ms\"/>", "<style select=\"@landscape preview\" appear=\"fade 100ms\"/>"))
        p.send(PopupAction.PreviewAction(ID, "q", key))
        assertEquals(p.last().dp(PopupMoves.RISE_DP).toFloat(), p.last().translationY, 0f)
        p.send(PopupAction.DismissAction(ID))
        frames(1000)
        RuntimeEnvironment.setQualifiers("+land")
        p.send(PopupAction.PreviewAction(ID, "w", key))
        assertEquals(0f, p.last().translationY, 0f)
        assertEquals(0f, p.last().alpha, 0f)
    }

    @Test
    fun withTouchExplorationAPopupGoesAtOnce() {
        val p = popups()
        val am = appContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        shadowOf(am).setTouchExplorationEnabled(true)
        p.send(PopupAction.PreviewAction(ID, "q", key))
        frames(300)
        p.send(PopupAction.DismissAction(ID))
        // no fade left to take TalkBack's hover
        assertEquals(0, p.root.childCount)
    }

    @Test
    fun aBubbleSoonerThanTheMotionDoesNotMoveAndEndsTheFades() {
        val p = popups()
        p.send(PopupAction.PreviewAction(ID, "q", key))
        frames(300)
        p.send(PopupAction.DismissAction(ID))
        assertEquals(1, p.root.childCount)
        val other = KeyRef.view(2)
        p.send(PopupAction.PreviewAction(other, "w", key))
        val w = p.last()
        assertEquals(0f, w.alpha, 0f)
        assertEquals(1, p.root.childCount)
        // a frame here runs Robolectric's animations to their end, its clock with them
        p.send(PopupAction.PreviewAction(KeyRef.view(3), "e", key))
        val e = p.last()
        assertNotEquals(w, e)
        assertEquals(1f, e.alpha, 0f)
        assertEquals(0f, e.translationY, 0f)
        frames(150)
        p.send(PopupAction.DismissAction(KeyRef.view(3)))
        assertTrue(e.parent == null)
    }

    @Test
    fun aLongPressScalesInAndTheFingerSlidesOverItAsItDoes() {
        val p = popups()
        p.send(PopupAction.PreviewAction(ID, "q", key))
        p.send(PopupAction.ShowKeyboardAction(ID, five, key))
        val keys = p.last()
        assertEquals(1, p.root.childCount)
        assertEquals(PopupMoves.SCALE_FROM, keys.scaleX, 0f)
        assertEquals(0f, keys.alpha, 0f)
        p.send(PopupAction.ChangeFocusAction(ID, 20f, 25f))
        val first = PopupAction.TriggerAction(ID).also { p.send(it) }.outAction
        p.send(PopupAction.ChangeFocusAction(ID, 200f, 25f))
        val moved = PopupAction.TriggerAction(ID).also { p.send(it) }.outAction
        assertNotEquals(first, moved)
        assertTrue(moved is KeyAction)
        frames(300)
        p.send(PopupAction.ChangeFocusAction(ID, 20f, 25f))
        assertEquals(1f, keys.scaleX, 0f)
        assertEquals(1f, keys.alpha, 0f)
        p.send(PopupAction.DismissAction(ID))
        assertEquals(1, p.root.childCount)
        frames(500)
        assertEquals(0, p.root.childCount)
    }

    @Test
    fun motionOffIsAsEver() {
        prefs.set(AppPrefs.getInstance().advanced.keyboardMotion, false)
        val p = popups()
        p.send(PopupAction.PreviewAction(ID, "q", key))
        assertEquals(1f, p.last().alpha, 0f)
        assertEquals(0f, p.last().translationY, 0f)
        p.send(PopupAction.DismissAction(ID))
        frames(200)
        assertEquals(0, p.root.childCount)
    }

    @Test
    fun aThemeWithNoMotionIsAsEver() {
        val p = popups(TestThemes.PixelDark)
        p.send(PopupAction.PreviewAction(ID, "q", key))
        assertEquals(1f, p.last().alpha, 0f)
        p.send(PopupAction.ShowKeyboardAction(ID, five, key))
        assertEquals(0.9f, p.last().scaleX, 1e-4f)
    }

    @Test
    fun aBubbleTakenAgainWhileFadingStartsAnew() {
        val p = popups()
        p.send(PopupAction.PreviewAction(ID, "q", key))
        frames(300)
        p.send(PopupAction.DismissAction(ID))
        val b = p.last()
        // 300 ms after the last: not typed fast; nothing of its fade left
        p.send(PopupAction.PreviewAction(KeyRef.view(2), "w", key))
        assertSame(b, p.last())
        assertEquals(1, p.root.childCount)
        assertEquals(0f, b.alpha, 0f)
        assertEquals(1f, b.scaleX, 0f)
        assertEquals(b.dp(PopupMoves.RISE_DP).toFloat(), b.translationY, 0f)
        frames(300)
        assertEquals(1f, b.alpha, 0f)
    }

    @Test
    fun dismissAllEndsTheFades() {
        val p = popups()
        p.send(PopupAction.PreviewAction(ID, "q", key))
        p.send(PopupAction.ShowKeyboardAction(ID, five, key))
        frames(300)
        p.send(PopupAction.DismissAction(ID))
        assertEquals(1, p.root.childCount)
        p.dismissAll()
        assertEquals(0, p.root.childCount)
    }

    @Test
    fun aMenuFadesInAndGoesAtOnce() {
        val p = popups()
        p.send(PopupAction.ShowMenuAction(ID, menu, key))
        val m = p.last()
        assertEquals(0f, m.alpha, 0f)
        assertEquals(1f, m.scaleX, 0f)
        frames(300)
        assertEquals(1f, m.alpha, 0f)
        p.send(PopupAction.DismissAction(ID))
        assertEquals(0, p.root.childCount)
    }

    private companion object {
        val ID = KeyRef.view(1)
        val menu = KeyDef.Popup.Menu(arrayOf(KeyDef.Popup.Menu.Item("Emoji", 0, KeyAction.CommitAction("x"))))
    }
}
