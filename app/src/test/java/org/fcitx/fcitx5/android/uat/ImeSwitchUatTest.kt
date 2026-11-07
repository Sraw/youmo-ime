/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.common.DynamicListAdapter
import org.fcitx.fcitx5.android.ui.common.DynamicListTouchCallback
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** UAT cases of the globe key and the clipboard's privacy (uat/01, 15), on the whole keyboard. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ImeSwitchUatTest {

    private val h = KeyboardHarness().apply {
        api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
        api.enabledIme = api.availableIme
    }

    @After
    fun close() = h.close()

    // fcitx tells of the switch it made
    private fun told() = h.emit(HostEvent.InputMethodChanged(h.api.inputMethodEntryCached))

    @Test
    @Uat("SET-08")
    fun theGlobeKeyGoesRoundAndTheSpaceSays() {
        h.start()
        assertTrue(h.texts().toString(), "Pinyin" in h.texts())
        h.tap(h.describedKey(h.service.getString(R.string.a11y_key_switch_language)))
        told()
        assertEquals(KeyboardHarness.ENGLISH.uniqueName, h.api.inputMethodEntryCached.uniqueName)
        val english = h.texts()
        // still the 26 keys
        assertTrue(english.toString(), "English" in english && "q" in english && "m" in english)
        h.tap(h.describedKey(h.service.getString(R.string.a11y_key_switch_language)))
        told()
        assertTrue(h.texts().toString(), "Pinyin" in h.texts())
    }

    @Test
    @Uat("PR-08")
    fun aSensitiveClipIsShownAsDots() {
        h.start()
        val clip = ClipData.newPlainText("", "mima1234").apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        h.service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        h.waitFor("stored") { org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry?.text == "mima1234" }
        // the next field: the bar offers it
        h.start()
        fun all() = KeyboardHarness.all(h.service.window.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        // the bar takes the clip a looper turn or more after the field starts under load: wait, not read once
        h.waitFor("dots on the bar") { all().any { "•" in it } }
        h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        h.tap(h.described(h.service.getString(R.string.clipboard)))
        h.waitFor("listed") {
            h.relayout()
            all().any { it.startsWith("•") && h.texts(h.inputView).contains(it) }
        }
        assertFalse(all().toString(), all().any { "mima1234" in it })
        h.capture("ImeSwitchUatTest/clipboard_sensitive")
    }

    private val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)
    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)

    // the 输入法 page over its own fake engine, the list as the user left it saved there
    private fun imPage(enabled: Array<InputMethodEntry>, edit: SettingsHarness.(RecyclerView) -> Unit): Array<InputMethodEntry> =
        SettingsHarness().use { st ->
            st.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi, shuangpin)
            st.api.enabledIme = enabled
            st.activity
            st.open(SettingsRoute.InputMethodList)
            fun list() = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<RecyclerView>().firstOrNull { it.adapter is DynamicListAdapter<*> && it.childCount > 0 }
            until("list", step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }) { list() != null }
            st.edit(list()!!)
            st.idle()
            st.api.enabledIme
        }

    private fun rows(list: RecyclerView) = (0 until list.childCount).map { list.getChildAt(it) }.sortedBy { it.top }
        .map { r -> KeyboardHarness.all(r).filterIsInstance<TextView>().first { it !is CheckBox && it.text.isNotEmpty() }.text.toString() }

    private fun name(e: InputMethodEntry) = InputMethodNames.of(h.service, e)

    // the names on the space key as the globe key goes round [times], from the input method it starts on
    private fun globeRound(enabled: Array<InputMethodEntry>, times: Int): List<String> {
        h.api.enabledIme = enabled
        h.start()
        val names = enabled.map(::name)
        fun spaceSays() = names.single { it in h.texts() }
        val said = mutableListOf(spaceSays())
        repeat(times) {
            h.tap(h.describedKey(h.service.getString(R.string.a11y_key_switch_language)))
            told()
            said += spaceSays()
        }
        return said
    }

    @Test
    @Uat("SET-05")
    fun draggedFirstTheGlobeKeyGoesInTheNewOrder() {
        h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi)
        val saved = imPage(arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi)) { list ->
            val row = list.getChildAt(2)
            val handle = KeyboardHarness.all(row).first { it !is TextView && it.isShown && it.isLongClickable }
            // held on ═: the drag starts there
            assertTrue(handle.performLongClick())
            // dragged up past each row: what ItemTouchHelper calls as the held row crosses one (Robolectric's
            // looper never settles under its own move events, so the crossings are made as it makes them)
            val callback = DynamicListTouchCallback(activity, list.adapter as DynamicListAdapter<InputMethodEntry>)
            for (to in 1 downTo 0) {
                callback.onMove(list, list.findViewHolderForAdapterPosition(to + 1)!!, list.findViewHolderForAdapterPosition(to)!!)
                idle()
            }
            idle()
            assertEquals(listOf(wubi, KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH).map(::name), rows(list))
        }
        assertEquals(listOf(wubi, KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH).map { it.uniqueName }, saved.map { it.uniqueName })
        // from the current input method on, the next in the list each time, the last back to the first
        h.api.availableIme = saved
        val said = globeRound(saved, 3)
        assertEquals(listOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, wubi, KeyboardHarness.PINYIN).map(::name), said)
    }

    @Test
    @Uat("SET-06")
    fun aDeletedInputMethodIsNoLongerGoneRoundTo() {
        val saved = imPage(arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, shuangpin)) { list ->
            val menu = shadowOf(activity).optionsMenu
            fun item(title: Int) = (0 until menu.size()).map(menu::getItem).first { it.title.toString() == s(title) }
            item(R.string.edit).let { it.javaClass.getMethod("invoke").invoke(it) }
            idle()
            val row = (0 until list.childCount).map(list::getChildAt).first { r -> KeyboardHarness.all(r).filterIsInstance<TextView>().any { it.text.toString() == name(shuangpin) } }
            KeyboardHarness.all(row).filterIsInstance<CheckBox>().first { it.visibility == View.VISIBLE }.performClick()
            idle()
            item(R.string.delete).let { it.javaClass.getMethod("invoke").invoke(it) }
            idle()
            assertTrue(rows(list).toString(), name(shuangpin) !in rows(list) && rows(list).size == 2)
        }
        assertEquals(listOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH).map { it.uniqueName }, saved.map { it.uniqueName })
        h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, shuangpin)
        val said = globeRound(saved, 3)
        assertEquals(listOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH).map(::name), said)
    }
}
