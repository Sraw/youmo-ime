/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One toggle named for itself (单手) is a checkable node, checked while on; one named for its state (简繁) or a
 * tap (我的输入习惯) is none, or TalkBack would read 已选中，恢复我的输入习惯.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ToolButtonNodeTest {

    @get:Rule
    val prefs = PrefsRule()

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private fun s(id: Int) = h.service.getString(id)

    private fun node(id: Int) = h.described(s(id)).createAccessibilityNodeInfo()

    @Test
    @Uat("AX-18")
    fun aButtonNamedForItsStateIsNoCheckableNode() {
        prefs.set(AppPrefs.getInstance().internal.toolbarButtons, "habits,one_handed,trad_simp,|,emoji,hide")
        prefs.setStyleDefault()
        prefs.set(AppPrefs.getInstance().internal.useHabits, false)
        h.start()
        if (KeyboardHarness.all(h.inputView).none { h.shown(it) && it.contentDescription == s(R.string.hide_toolbar) }) {
            h.tap(h.described(s(R.string.expand_toolbar)))
            h.after(500)
            h.relayout()
        }
        val traditional = listOf(StatusEntry(7, "chttrans", "fcitx-chttrans-active", "", true))
        h.api.statusAreaCached = traditional
        h.emit(HostEvent.StatusArea(traditional, KeyboardHarness.PINYIN))
        h.relayout()
        assertFalse(node(R.string.learning_habits_on).isCheckable)
        assertFalse(node(R.string.status_traditional).isCheckable)
        val oneHanded = node(R.string.one_handed)
        assertEquals(true to false, oneHanded.isCheckable to oneHanded.isChecked)
    }
}
