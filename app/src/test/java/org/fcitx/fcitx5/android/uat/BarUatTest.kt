/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import splitties.dimensions.dp
import java.io.File
import java.util.concurrent.TimeUnit

/** UAT cases of the bar above the keys: toolbar, candidates, clipboard, notices (uat/05, 19). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BarUatTest {

    private var harness: KeyboardHarness? = null

    private fun harness() = KeyboardHarness().also { harness = it }

    // the Kotlin host on a scripted engine, for what its own events put on the bar (KotlinHostKeyboardTest's)
    private var fcitx: KotlinHost? = null
    private var home: File? = null

    @After
    fun close() {
        harness?.close()
        fcitx?.stop()
        home?.deleteRecursively()
    }

    // the host answers on its thread, the bar is drawn on the main one: both run till [done]
    private fun KeyboardHarness.until(what: String, done: () -> Boolean) = waitUntil(
        what,
        { texts(inputView) },
        step = {
            idle()
            relayout()
        },
        done = done,
    )

    private fun onTheHost(): KeyboardHarness {
        val dir = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        home = dir
        val host = startedHost(ScriptedEngine(dir)).also { fcitx = it }
        return keyboardOn(host).also { harness = it }.start()
    }

    private fun KeyboardHarness.shownText(text: String) =
        KeyboardHarness.all(inputView).any { shown(it) && it is TextView && it.text.toString() == text }

    @Test
    @Uat("KB-01")
    fun lettersTypedPutTheCandidatesOnTheBar() {
        val h = onTheHost()
        assertTrue(h.has(R.string.expand_toolbar))
        h.tap(h.key("n"))
        h.tap(h.key("i"))
        h.until("ni and its candidates") { h.shownText("ni") && h.shownText("你") }
        // the bar's candidates in place of the toolbar, the pinyin above them, nothing in the field yet
        assertFalse(h.has(R.string.expand_toolbar))
        assertEquals("", h.field.text.toString())
        val ni = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text.toString() == "ni" }
        val first = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text.toString() == "你" }
        assertTrue("ni above the candidates", h.box(ni).bottom <= h.box(first).top + 1)
    }

    @Test
    @Uat("QU-01")
    fun holdingTheSymbolsKeyOpensQuickInput() {
        val h = onTheHost()
        h.longPress(h.key("符"))
        // the host's prompt on the bar, waiting for a keyword (in the device's language: HostReplayUatTest)
        val prompt = h.service.getString(R.string.host_quick_phrase_aux)
        h.until("the prompt $prompt") { h.texts(h.inputView).any { it.startsWith(prompt.trim()) } }
        assertEquals("", h.field.text.toString())
    }

    // the status panel open, its entries' labels
    private fun KeyboardHarness.statusPanel(): List<String> {
        if (has(R.string.back_to_keyboard)) {
            tap(described(service.getString(R.string.back_to_keyboard)))
            after(1000)
            relayout()
        }
        if (!has(R.string.hide_toolbar)) tap(described(service.getString(R.string.expand_toolbar)))
        tap(described(service.getString(R.string.status_area)))
        until("the panel") { has(R.string.open_input_method_settings) }
        return texts(inputView)
    }

    private fun KeyboardHarness.statusTap(label: Int) {
        val t = KeyboardHarness.all(inputView).first { shown(it) && it is TextView && it.text.toString() == service.getString(label) }
        tap(generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() })
    }

    // the host's toggle [name] on, as it now stands: its icon -active, but punctuation's is -active
    // while Chinese punctuation is on, so its "on" (English punctuation) is the icon without it.
    // Read on the test's thread, the host answering on its own (the cache is empty till its first event)
    private fun on(name: String) =
        fcitx!!.statusAreaCached.ifEmpty { runBlocking { fcitx!!.statusArea() } }
            .first { it.name == name }.icon.endsWith("-active") != (name == "punctuation")

    private fun KeyboardHarness.toggled(name: String, from: Int, to: Int) {
        assertTrue(texts(inputView).toString(), service.getString(from) in statusPanel())
        assertFalse(on(name))
        statusTap(from)
        // the host's toggle flipped, the panel shows it so
        until("$name toggled") { on(name) }
        until("${service.getString(to)} shown") { shownText(service.getString(to)) }
        assertFalse(shownText(service.getString(from)))
        // its effect on typing: StatusPanelHostUatTest, through the host with the app's data
        tap(described(service.getString(R.string.back_to_keyboard)))
    }

    @Test
    @Uat("TB-15")
    fun theStatusPanelTurnsTraditionalOn() = onTheHost().toggled("chttrans", R.string.status_simplified, R.string.status_traditional)

    @Test
    @Uat("TB-16")
    fun theStatusPanelTurnsEnglishPunctuationOn() =
        onTheHost().toggled("punctuation", R.string.status_punctuation_chinese, R.string.status_punctuation_english)

    @Test
    @Uat("TB-17")
    fun theStatusPanelTurnsFullWidthOn() = onTheHost().toggled("fullwidth", R.string.status_half_width, R.string.status_full_width)

    @Test
    @Uat("TB-19")
    fun englishsStatusPanelHasNoChineseToggles() {
        val h = onTheHost()
        val chinese = listOf(R.string.status_simplified, R.string.status_punctuation_chinese, R.string.status_half_width).map(h.service::getString)
        assertTrue(chinese.all { it in h.statusPanel() })
        h.tap(h.described(h.service.getString(R.string.back_to_keyboard)))
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH))
            activateIme(InputMethodCatalog.ENGLISH)
        }
        h.until("English") { fcitx!!.inputMethodEntryCached.uniqueName == InputMethodCatalog.ENGLISH }
        val panel = h.statusPanel()
        val all = chinese + listOf(R.string.status_traditional, R.string.status_punctuation_english, R.string.status_full_width).map(h.service::getString)
        assertTrue(panel.toString(), all.none { it in panel })
        // the app's own entries still there
        assertTrue(panel.toString(), h.service.getString(R.string.theme) in panel)
    }

    private fun KeyboardHarness.candidates(vararg words: String) {
        api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        emit(HostEvent.Candidates(words.size, api.candidates.toList()))
    }

    private fun KeyboardHarness.has(description: Int) =
        KeyboardHarness.all(inputView).any { shown(it) && it.contentDescription == service.getString(description) }

    private fun KeyboardHarness.text(text: String): View =
        KeyboardHarness.all(inputView).first { shown(it) && it is TextView && it.text.toString() == text }

    @Test
    @Uat("TB-01")
    fun toolbarWhenIdleCandidatesWhileTyping() {
        val h = harness().start()
        assertTrue(h.has(R.string.expand_toolbar) && h.has(R.string.a11y_key_emoji) && h.has(R.string.hide_keyboard))
        h.candidates("你", "呢", "那")
        assertTrue(h.texts().toString(), h.texts().take(3) == listOf("你", "呢", "那"))
        assertFalse(h.has(R.string.expand_toolbar))
        h.capture("BarUatTest/candidates")
        h.candidates()
        assertTrue(h.has(R.string.expand_toolbar))
        assertFalse(h.texts().toString(), "你" in h.texts())
    }

    @Test
    @Uat("TB-05")
    fun expandedCandidatesGridSelects() {
        val h = harness().start()
        h.candidates(*Array(60) { "字$it" })
        h.tap(h.described(h.service.getString(R.string.expand_candidates_list)))
        val grid = h.texts(h.inputView)
        // a grid of them, past what the bar showed
        assertTrue(grid.toString(), "字20" in grid)
        h.captureBoard("BarUatTest/expanded")
        h.tap(h.text("字20").let { (it.parent as? View)?.takeIf { p -> p.hasOnClickListeners() } ?: it })
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("select(20") })
        // the engine moves on: no candidates, the grid gone
        h.candidates()
        assertFalse(h.texts(h.inputView).toString(), "字20" in h.texts(h.inputView))
    }

    @Test
    @Uat("TB-08")
    fun clipboardEntryPastes() {
        val h = harness().start()
        h.copied("公园")
        // stored, then offered on the bar by a launch on the main thread: waited
        // for as shown, not as stored, or the offer lands after the tap below and puts the toolbar away
        fun shown() = KeyboardHarness.all(h.inputView).any { h.shown(it) && it is TextView && it.text == "公园" }
        h.waitFor("公园 offered") { shown() }
        h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        h.tap(h.described(h.service.getString(R.string.clipboard)))
        h.waitFor("公园 listed") { h.relayout(); shown() }
        h.capture("BarUatTest/clipboard")
        val entry = h.text("公园")
        h.tap(generateSequence(entry) { it.parent as? View }.first { it.hasOnClickListeners() })
        assertEquals("公园", h.field.text.toString())
        assertTrue(h.texts().toString(), "q" in h.texts())
    }

    @Test
    @Uat("MN-01")
    fun noticeAboveKeysTapAndTimeout() {
        val h = harness().start()
        h.service.say(R.string.text_copied)
        h.idle()
        val popup = Shadows.shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        assertNotNull(popup)
        assertTrue(popup!!.isShowing)
        val said = KeyboardHarness.all(popup.contentView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(said.toString(), h.service.getString(R.string.text_copied) in said)
        popup.contentView.performClick()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertFalse(popup.isShowing)
        // again, left alone: gone after its two seconds
        h.service.say(R.string.text_copied)
        h.idle()
        val again = Shadows.shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        assertTrue(again.isShowing)
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        assertFalse(again.isShowing)
    }

    private fun sizes(sp: Int, name: String): Pair<Int, Int> {
        ThemeManager.prefs.candidateTextSize.setValue(sp)
        val h = KeyboardHarness().also { harness = it }.start()
        val bar = h.described(h.service.getString(R.string.expand_toolbar)).parent as View
        // idle bar's keys: none narrower than 40 dp
        for (d in listOf(R.string.expand_toolbar, R.string.a11y_key_emoji, R.string.hide_keyboard)) {
            assertTrue("$d at $sp sp", h.described(h.service.getString(d)).width >= h.service.dp(40))
        }
        h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        val min = h.service.dp(if (sp <= 14) 40 else 28)
        for (d in listOf(R.string.undo, R.string.redo, R.string.text_editing, R.string.clipboard, R.string.status_area)) {
            val b = h.described(h.service.getString(d))
            assertTrue("$d ${b.width} at $sp sp", b.width >= min)
        }
        h.capture("BarUatTest/toolbar_${name}")
        // the panels' own bar keys are as high as the bar (F4175, F4189): the clipboard's 清空, the
        // status area's settings, the text-editing panel's 剪贴板
        fun has(d: Int) = KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription?.toString() == h.service.getString(d) }
        // 清空 shows only over entries
        h.copied("公园")
        h.waitFor("公园 offered") { KeyboardHarness.all(h.inputView).any { h.shown(it) && it is TextView && it.text == "公园" } }
        for ((open, d) in listOf(
            R.string.clipboard to R.string.delete_all,
            R.string.status_area to R.string.open_input_method_settings,
            R.string.text_editing to R.string.clipboard,
        )) {
            if (!has(R.string.hide_toolbar)) h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
            h.tap(h.described(h.service.getString(open)))
            h.waitFor("${h.service.getString(d)} shown") { h.relayout(); has(d) }
            val b = h.described(h.service.getString(d))
            assertEquals("$d at $sp sp", bar.height, b.height)
            if (sp <= 14) assertTrue("$d ${b.width} at $sp sp", b.width >= h.service.dp(40))
            h.tap(h.described(h.service.getString(R.string.back_to_keyboard)))
        }
        if (!has(R.string.hide_toolbar)) h.tap(h.described(h.service.getString(R.string.expand_toolbar)))
        h.tap(h.described(h.service.getString(R.string.hide_toolbar)))
        h.candidates("你好", "拟好", "你", "呢", "泥")
        // the bar itself each time: a fallback to another view could compare one view's height with another's
        val barHeight = bar.height.also { assertTrue("the bar not laid out at $sp sp", it > 0) }
        h.capture("BarUatTest/candidates_${name}")
        val keys = h.key("q").height
        h.close()
        harness = null
        return barHeight to keys
    }

    @Test
    @Uat("MN-20")
    fun candidateTextSizeChangesBarNotKeys() {
        val size = ThemeManager.prefs.candidateTextSize
        try {
            val (big, keysBig) = sizes(28, "28sp")
            val (small, keysSmall) = sizes(14, "14sp")
            assertTrue("bar $big at 28 sp, $small at 14 sp", big > small)
            assertEquals(keysBig, keysSmall)
        } finally {
            // the prefs object outlives the test (Robolectric keeps the sandbox): the next one gets the default
            size.setValue(size.defaultValue)
        }
    }

    @Test
    @Uat("MN-23")
    fun candidateSpacingFollowsTextSize() {
        val size = ThemeManager.prefs.candidateTextSize
        try {
            for ((sp, pad) in listOf(14 to 6, 20 to 9, 28 to 10)) {
                size.setValue(sp)
                val h = harness().start()
                h.candidates("你好", "拟好", "你", "呢", "泥")
                h.relayout()
                val item = (h.inputView.findViewById<ViewGroup>(R.id.candidate_view)).getChildAt(0)
                assertEquals("left padding at $sp sp", h.service.dp(pad), item.paddingLeft)
                assertEquals("right padding at $sp sp", h.service.dp(pad), item.paddingRight)
                val bar = h.inputView.findViewById<ViewGroup>(R.id.candidate_view)
                for (i in 2 until bar.childCount) {
                    val w = bar.getChildAt(i).width
                    // a single character is at least the touch floor wide, and the whole list fits,
                    // so nothing is stretched: its natural width
                    assertTrue("single character $i is $w px at $sp sp", w >= h.service.dp(32))
                    assertTrue("single character $i is $w px at $sp sp", w <= h.service.dp(maxOf(32, sp + 2 * pad) + 1))
                }
                h.close()
                harness = null
            }
        } finally {
            size.setValue(size.defaultValue)
        }
    }
}
