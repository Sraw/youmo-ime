/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** 外观 → 布局's **+** (061-custom-layout §4.3): import a `.layout`, or open the layout maker in a browser. */
@RunWith(RobolectricTestRunner::class)
class LayoutAddMenuTest {

    private val app = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val ctx = ContextThemeWrapper(app, androidx.appcompat.R.style.Theme_AppCompat_DayNight)
    private var imported = 0
    private val dialog = LayoutAddMenu.show(ctx) { imported++ }

    private fun click(item: Int) {
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(item, null, list), item, list.adapter.getItemId(item))
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun importOpensThePickerAndTheWebOneTheLayoutMaker() {
        val adapter = dialog.listView.adapter
        assertEquals(2, adapter.count)
        assertEquals("Import a layout file", adapter.getItem(0).toString())
        click(0)
        assertEquals(1, imported)
        assertNull(shadowOf(app).nextStartedActivity)
        click(1)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://sraw.github.io/youmo-ime/theme-maker/layout/"), started.data)
    }
}
