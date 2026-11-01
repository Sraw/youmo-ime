/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.clipboard.CLIPBOARD_UNLIMITED
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.EditTextIntPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * uat/05-toolbar.md's TB-45: 「无上限」 is the history limit's own button, and a limit that would delete
 * entries asks first, with the count; cancelled, the old limit stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ClipboardLimitUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val st = SettingsHarness()

    @After
    fun close() = st.close()

    private val limit get() = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    private fun settle() = runBlocking { ClipboardManager.coroutineContext.job.children.forEach { it.join() } }

    private fun copy(text: String) {
        // each copied at a time of its own, as the limit goes by time
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        settle()
        ClipboardManager.lastClipTimestamp = -1L
        appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text))
        until("$text stored") { settle(); ClipboardManager.lastEntry?.text == text }
    }

    private fun history() = runBlocking { settle(); (0 until 50).mapNotNull { ClipboardManager.get(it)?.text } }

    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun limitPref() = st.pref(R.string.clipboard_limit) as EditTextIntPreference

    @Test
    @Uat("TB-45")
    fun unlimitedIsTheLimitsButtonAndLoweringAsksFirst() {
        ClipboardManager.resetDuplicateCheck()
        prefs.set(limit, 10)
        val words = listOf("今天", "天气", "我们", "一起", "散步")
        for (w in words) copy(w)
        val pinned = runBlocking { (0 until 50).firstNotNullOf { ClipboardManager.get(it)?.takeIf { e -> e.text == "今天" } } }
        runBlocking { ClipboardManager.pin(pinned.id) }
        st.open(SettingsRoute.Clipboard)
        // the dialog's own button, the box empty; the summary says it
        // another test's dialog may still be the latest: wait for this one
        val before = ShadowDialog.getLatestDialog()
        limitPref().performClick()
        until("the limit's dialog") { st.idle(); ShadowDialog.getLatestDialog().let { it !== before && it?.isShowing == true } }
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).let {
            assertEquals(st.s(R.string.clipboard_unlimited), it.text.toString())
            it.performClick()
        }
        // set once the count to delete is read: none
        until("set", { limit.getValue() }) { st.idle(); limit.getValue() == CLIPBOARD_UNLIMITED }
        assertEquals(st.s(R.string.clipboard_unlimited), limitPref().summary.toString())
        assertTrue(words.all { it in history() })

        // lowered to 10, more than there are: nothing to delete, set without asking
        val shown = ShadowDialog.getLatestDialog()
        limitPref().callChangeListener("10")
        until("set", { limit.getValue() }) { st.idle(); limit.getValue() == 10 }
        assertTrue(ShadowDialog.getLatestDialog() === shown)
        assertTrue(words.all { it in history() })
        limitPref().text = CLIPBOARD_UNLIMITED.toString()
        st.idle()

        // to 2: two unpinned go, asked first; cancelled, nothing changes
        assertEquals(false, limitPref().callChangeListener("2"))
        until("asked") { st.idle(); ShadowDialog.getLatestDialog()?.isShowing == true && dialog().getButton(AlertDialog.BUTTON_POSITIVE) != null }
        val ask = dialog()
        assertEquals(
            st.activity.resources.getQuantityString(R.plurals.clipboard_limit_deletes, 2, 2),
            ask.findViewById<TextView>(android.R.id.message)!!.text.toString(),
        )
        ask.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        st.idle()
        assertEquals(CLIPBOARD_UNLIMITED, limit.getValue())
        assertEquals(words, words.filter { it in history() })

        limitPref().callChangeListener("2")
        until("asked again") { st.idle(); dialog() !== ask && dialog().isShowing }
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        until("set", { limit.getValue() }) { st.idle(); limit.getValue() == 2 }
        until("trimmed", { history() }) { "天气" !in history() }
        // the pinned kept though older, the two newest unpinned
        assertEquals(listOf("今天", "一起", "散步"), words.filter { it in history() })
    }
}
