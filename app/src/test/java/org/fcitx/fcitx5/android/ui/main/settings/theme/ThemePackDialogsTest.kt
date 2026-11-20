/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.CheckBox
import android.widget.ScrollView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A theme pack's dialogs (ThemePackDialogs): what is under the text reached however low the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w800dp-h360dp-land")
class ThemePackDialogsTest {

    // a phone turned, a long confirmation: 一起导入 under it scrolled to and shown whole, not given no room
    @Test
    @Uat("ST-72")
    fun whatIsUnderALongTextIsReachedOnALowScreen() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val activity = ContextThemeWrapper(host, R.style.Theme_FcitxAppTheme)
        val box = CheckBox(activity).apply { text = "一起导入" }
        val text = (1..30).joinToString("\n") { "第 $it 行" }
        val dialog = ThemePackDialogs.builder(activity, "包", text, null, box).show()
        shadowOf(Looper.getMainLooper()).idle()
        val scroll = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<ScrollView>().first { box in KeyboardHarness.all(it) }
        scroll.fullScroll(ScrollView.FOCUS_DOWN)
        shadowOf(Looper.getMainLooper()).idle()
        val shown = Rect()
        assertTrue("the box shown", box.getLocalVisibleRect(shown))
        assertTrue("whole: $shown of ${box.height}", box.height > 0 && shown.height() == box.height)
        dialog.dismiss()
    }
}
