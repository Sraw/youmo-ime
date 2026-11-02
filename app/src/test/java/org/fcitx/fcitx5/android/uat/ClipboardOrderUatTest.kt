/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import androidx.paging.PagingSource
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** uat/05's TB-50: entries pasted from the clipboard panel move first, once it closes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ClipboardOrderUatTest {

    private val h = KeyboardHarness()

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() = h.close()

    private fun s(id: Int) = h.service.getString(id)

    private fun order() = runBlocking {
        ClipboardManager.coroutineContext.job.children.forEach { it.join() }
        val page = ClipboardManager.allEntries().load(PagingSource.LoadParams.Refresh(null, 50, false))
        (page as PagingSource.LoadResult.Page).data.map { it.text }
    }

    @Test
    @Uat("TB-50")
    fun entriesPastedFromThePanelMoveFirstWhenItCloses() {
        prefs.set(AppPrefs.getInstance().clipboard.clipboardReturnAfterPaste, false)
        h.start()
        for (w in listOf("钉", "甲", "乙", "丙")) {
            h.copied(w)
            h.after(1000)
        }
        h.clipboard("丙")
        h.entry("钉")!!.performLongClick()
        h.idle()
        h.menuPick(s(R.string.pin))
        h.waitFor("pinned") { order().first() == "钉" }
        h.tap(h.entry("甲")!!)
        h.tap(h.entry("乙")!!)
        h.tap(h.entry("钉")!!)
        assertEquals("甲乙钉", h.field.text.toString())
        // nothing moves while the panel is open: the next tap lands where the finger is
        h.after(500)
        assertEquals(listOf("钉", "丙", "乙", "甲"), order())
        h.tap(h.described(s(R.string.back_to_keyboard)))
        // the last pasted first of the unpinned, the pinned in its place
        h.waitFor("moved") { order() == listOf("钉", "乙", "甲", "丙") }
    }

    @Test
    @Uat("TB-50")
    fun entriesPastedBeforeTheScreenTurnsMoveFirstToo() {
        prefs.set(AppPrefs.getInstance().clipboard.clipboardReturnAfterPaste, false)
        h.start()
        for (w in listOf("甲", "乙", "丙")) {
            h.copied(w)
            h.after(1000)
        }
        h.clipboard("丙")
        h.tap(h.entry("甲")!!)
        assertEquals(listOf("丙", "乙", "甲"), order())
        // the input view made anew: its windows never detached
        try {
            RuntimeEnvironment.setQualifiers("+land")
            h.service.onConfigurationChanged(Configuration(h.service.resources.configuration))
            h.idle()
            h.waitFor("moved") { order() == listOf("甲", "丙", "乙") }
        } finally {
            RuntimeEnvironment.setQualifiers("+port")
            h.service.onConfigurationChanged(Configuration(h.service.resources.configuration))
            h.idle()
        }
    }
}
