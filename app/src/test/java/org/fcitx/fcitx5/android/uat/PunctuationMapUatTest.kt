/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 中文标点 (uat/14 ST-28): the map saved as its page saves it, then the keyboard's ， typed through the Kotlin host. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PunctuationMapUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness

    @After
    fun close() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        home.deleteRecursively()
        // the map a test saved, cached for the process
        PunctuationManager.forget()
        // and the start keys it wrote, kept for the process too: else a later InputView reads this test's comma
        PunctuationStartKeys.forget(appContext)
    }

    private fun until(what: String, done: () -> Boolean) = waitUntil(what, step = { if (::h.isInitialized) h.idle() }, done = done)

    // what the main thread posted to the host done, and what it told back shown
    private fun synced() {
        // set on the fcitx thread, read on this one
        val done = java.util.concurrent.atomic.AtomicBoolean()
        h.service.postFcitxJob { done.set(true) }
        until("the host's queue done") { done.get() }
        h.idle()
    }

    @Test
    @Uat("ST-28")
    fun theCommaMappedToAnotherMarkTypesIt() {
        fcitx = startedHost(ScriptedEngine(home))
        runBlocking {
            // the page's table saved whole (the built-in one is not among the test's assets)
            fcitx.setPunctuationMap("zh_CN", listOf(PunctuationMapEntry(",", "、", "")))
            assertEquals(listOf(PunctuationMapEntry(",", "、", "")), fcitx.punctuationMap("zh_CN"))
        }
        h = KeyboardHarness(host = fcitx)
        h.service.postFcitxJob { activate(1, "org.example.app") }
        h.start()
        h.tap(h.keyWithId(R.id.button_comma)!!)
        until("the mark in the field") { h.field.text.isNotEmpty() }
        assertEquals("、", h.field.text.toString())
    }

    // 060-render-p2 S7 #7: "half width after a letter or number" on, after a letter the ， key shows and
    // bubbles the , it types; after a Chinese word it is ， again; off, ， throughout.
    @Test
    fun afterALetterTheCommaShowsTheHalfWidthItTypes() {
        for (on in listOf(true, false)) {
            home.deleteRecursively()
            PunctuationManager.forget()
            File(home, "config/" + ModuleConfigs.PUNCTUATION).apply { parentFile!!.mkdirs() }
                .writeText("HalfWidthPuncAfterLetterOrNumber=" + (if (on) "True" else "False") + "\n")
            fcitx = startedHost(ScriptedEngine(home))
            // the built-in table is not among the test's assets: its comma saved
            runBlocking { fcitx.setPunctuationMap("zh_CN", listOf(PunctuationMapEntry(",", "，", ""))) }
            h = KeyboardHarness(host = fcitx)
            h.service.postFcitxJob { activate(1, "org.example.app") }
            h.start()
            val what = "option $on"
            val comma = { h.keyWithId(R.id.button_comma)!! }
            val shown = { faceAndBubble(comma) }
            val twice = { c: String -> listOf(c, c) }
            // the first press at once, the start's statuses not waited for: its mark shown and typed
            assertEquals(what, twice("，"), shown())
            until("the mark typed") { h.field.text.toString() == "，" }
            // a letter the engine leaves (the scripted one composes only a to z): typed as itself, the A of shift's
            h.tap(h.keyNamed("shift"))
            h.tap(h.key("A"))
            until("A typed") { h.field.text.toString() == "，A" }
            val half = if (on) "," else "，"
            // the A typed back to the field before the host told the keys what the comma now types: that told first
            synced()
            // the host tells the map after the A's selection reached it, maybe only as the next press runs the
            // looper (CI 43202860764, SDK 29: face ，, bubble ,): its face drawn anew first, then both checked
            until("the comma's face told") { comma().label == half }
            assertEquals(what, twice(half), shown())
            until("the mark typed") { h.field.text.toString() == "，A$half" }
            // a Chinese word after it: the comma back to its mark
            h.tap(h.key("a"))
            runBlocking { fcitx.select(0) }
            until("the word typed") { h.field.text.endsWith("你好") }
            synced()
            until("the comma's face told back") { comma().label == "，" }
            assertEquals(what, twice("，"), shown())
            until("the mark typed") { h.field.text.toString() == "，A${half}你好，" }
            h.close(); fcitx.stop()
        }
    }

    // the first press as soon as the keyboard is up, nothing waited for: face, bubble and what it types one mark
    // (the status a view made anew replayed before the host had sent one drew , where the engine typed 、, S7a)
    @Test
    fun theFirstPressAtOnceShowsWhatItTypes() {
        fcitx = startedHost(ScriptedEngine(home))
        runBlocking { fcitx.setPunctuationMap("zh_CN", listOf(PunctuationMapEntry(",", "、", ""))) }
        h = KeyboardHarness(host = fcitx)
        h.service.postFcitxJob { activate(1, "org.example.app") }
        h.start()
        val k = h.keyWithId(R.id.button_comma)!!
        val face = k.label
        h.press(k)
        val bubble = h.popupChoices().map { it.text }.single()
        h.release()
        until("the press typed") { h.field.text.isNotEmpty() }
        assertEquals(listOf("、", "、", "、"), listOf(face, bubble, h.field.text.toString()))
    }

    // a host that ran before saved its table, and stopped; a new one made, not started, and the keyboard up before it,
    // with what the last run kept ([kept]) or none
    private fun keyboardBeforeTheHost(kept: Boolean): KotlinHost {
        PunctuationStartKeys.forget(appContext)
        startedHost(ScriptedEngine(home)).let { first ->
            runBlocking { first.setPunctuationMap("zh_CN", listOf(PunctuationMapEntry(",", "、", ""))) }
            first.stop()
        }
        // the next process: what the last one kept, read from the file; or a first install's none
        if (kept) PunctuationStartKeys.loadNow(appContext) else PunctuationStartKeys.forget(appContext)
        val next = KotlinHost(appContext, ScriptedEngine(home))
        h = KeyboardHarness(host = next)
        h.service.postFcitxJob { activate(1, "org.example.app") }
        h.start()
        assertTrue("the host not up yet", !next.isReady)
        return next
    }

    // the comma pressed before the host is up, its keys as the last run started with them (PunctuationStartKeys, read
    // from its file): face and bubble the mark typed once the host is up (S7a: , shown, 、 typed)
    @Test
    fun beforeTheHostIsUpTheKeysAreTheLastRunsStart() {
        val next = keyboardBeforeTheHost(kept = true)
        assertEquals(mapOf("," to "、"), PunctuationStartKeys.known)
        val k = h.keyWithId(R.id.button_comma)!!
        val face = k.label
        h.press(k)
        val bubble = h.popupChoices().map { it.text }.single()
        h.release()
        fcitx = next.apply { start() }
        until("the press typed") { h.field.text.isNotEmpty() }
        assertEquals(listOf("、", "、", "、"), listOf(face, bubble, h.field.text.toString()))
    }

    // the service's read done after the keyboard was made (its thread slower): a press then takes the keys read,
    // not held back till the host is up (S7a round 4)
    @Test
    fun theLastRunsKeysReadAfterTheKeyboardStillCount() {
        fcitx = keyboardBeforeTheHost(kept = false)
        PunctuationStartKeys.loadNow(appContext)
        assertEquals("the first run's file gone with forget", null, PunctuationStartKeys.known)
        PunctuationStartKeys.write(appContext, mapOf("," to "、"))
        val k = h.keyWithId(R.id.button_comma)!!
        h.press(k)
        val bubble = h.popupChoices().map { it.text }
        h.release()
        fcitx.start()
        until("the press typed") { h.field.text.isNotEmpty() }
        assertEquals(listOf("、"), bubble)
        assertEquals("、", h.field.text.toString())
        assertEquals("、", h.keyWithId(R.id.button_comma)!!.label)
    }

    // none kept (a first install): the comma pressed before the host is up neither types nor bubbles, as no face
    // could show what it would type; once the host is up it types its mark
    @Test
    fun beforeTheHostIsUpWithNoneKeptTheKeysAreHeldBack() {
        fcitx = keyboardBeforeTheHost(kept = false)
        val k = h.keyWithId(R.id.button_comma)!!
        h.press(k)
        assertEquals(emptyList<String>(), h.popupChoices().map { it.text })
        h.release()
        fcitx.start()
        until("the host up") { fcitx.isReady }
        // whatever was queued for it done, and its events handed over
        // (a key queued for the host goes through the service's jobs and the main thread: a few turns, time run on)
        repeat(5) { runBlocking { fcitx.isEmpty() }; h.after(200) }
        assertEquals("", h.field.text.toString())
        h.after(BUBBLE_GONE_MS)
        assertEquals(listOf("、", "、"), faceAndBubble { h.keyWithId(R.id.button_comma)!! })
        until("the mark typed") { h.field.text.isNotEmpty() }
        repeat(5) { runBlocking { fcitx.isEmpty() }; h.after(200) }
        assertEquals("the held one never typed", "、", h.field.text.toString())
    }

    // punctuation turned off, then on again (the toggle's action): each press of the comma before, between and
    // after shows on its face and bubble the mark it types, the switch made in the status's own event
    @Test
    fun offThenOnTheCommaShowsWhatItTypesEachPress() {
        fcitx = startedHost(ScriptedEngine(home))
        runBlocking { fcitx.setPunctuationMap("zh_CN", listOf(PunctuationMapEntry(",", "、", ""))) }
        h = KeyboardHarness(host = fcitx)
        h.service.postFcitxJob { activate(1, "org.example.app") }
        h.start()
        val comma = { h.keyWithId(R.id.button_comma)!! }
        var typed = ""
        val press = { want: String ->
            val shown = faceAndBubble(comma)
            until("the press typed") { h.field.text.length == typed.length + 1 }
            val c = h.field.text.last().toString()
            assertEquals(listOf(c, c), shown)
            assertEquals(want, c)
            typed += c
        }
        val toggle = {
            runBlocking { fcitx.activateAction(fcitx.statusArea().single { it.name == "punctuation" }.id) }
            h.idle()
        }
        press("、")
        toggle()
        press(",")
        press(",")
        toggle()
        press("、")
        press("、")
    }

    // the key's face and the bubble it shows pressed
    private fun faceAndBubble(key: () -> org.fcitx.fcitx5.android.input.ShownKey): List<String> {
        h.after(BUBBLE_GONE_MS)
        val k = key()
        h.press(k)
        val bubble = h.popupChoices().map { it.text }.single()
        h.release()
        return listOf(k.label, bubble)
    }

    private companion object {
        const val BUBBLE_GONE_MS = 1000L
    }
}
