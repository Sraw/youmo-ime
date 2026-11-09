/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** 新增主题's menu (060-render-p3 M5): import a file, or open the web theme maker in a browser. */
@RunWith(RobolectricTestRunner::class)
class ThemeAddMenuTest {

    // an activity, as the theme page is: a browser started from one needs no new task
    private val app = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val ctx = ContextThemeWrapper(app, androidx.appcompat.R.style.Theme_AppCompat_DayNight)
    private var imported = 0
    private val dialog = ThemeAddMenu.show(ctx) { imported++ }

    private fun click(item: Int) {
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(item, null, list), item, list.adapter.getItemId(item))
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun twoItemsTheWebOneSaysToComeBack() {
        val adapter = dialog.listView.adapter
        assertEquals(2, adapter.count)
        assertEquals("Import a theme file", adapter.getItem(0).toString())
        assertEquals("Make one on the web\nCome back here to import it when done", adapter.getItem(1).toString())
    }

    @Test
    fun importOpensThePicker() {
        click(0)
        assertEquals(1, imported)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun theWebOneOpensTheMakerInABrowser() {
        click(1)
        assertEquals(0, imported)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://sraw.github.io/youmo-ime/theme-maker/"), started.data)
    }
}
