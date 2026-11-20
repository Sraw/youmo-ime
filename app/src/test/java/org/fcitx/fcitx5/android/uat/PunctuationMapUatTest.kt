/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.putPunctuationTable
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

/** A table put where a user's own is read, then the keyboard's ， typed through it. */
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
        // cached for the process
        PunctuationManager.forget()
        // kept for the process too: else a later InputView reads this test's comma
        PunctuationStartKeys.forget(appContext)
    }

    private fun until(what: String, done: () -> Boolean) = waitUntil(what, step = { if (::h.isInitialized) h.idle() }, done = done)

    private fun synced() {
        val done = java.util.concurrent.atomic.AtomicBoolean()
        h.service.postFcitxJob { done.set(true) }
        until("the host's queue done") { done.get() }
        h.idle()
    }

    @Test
    fun theCommaMappedToAnotherMarkTypesIt() {
        fcitx = startedHost(ScriptedEngine(home))
        runBlocking {
            // the built-in one is not among the test's assets
            fcitx.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "、", "")))
            assertEquals(listOf(PunctuationMapEntry(",", "、", "")), fcitx.punctuationMap("zh_CN"))
        }
        h = KeyboardHarness(host = fcitx)
        h.service.postFcitxJob { activate(1, "org.example.app") }
        h.start()
        h.tap(h.keyWithId(R.id.button_comma)!!)
        until("the mark in the field") { h.field.text.isNotEmpty() }
        assertEquals("、", h.field.text.toString())
    }

    // "half width after a letter or number" on: after a letter the ， key shows and bubbles the , it types; after a
    // Chinese word ， again
    @Test
    fun afterALetterTheCommaShowsTheHalfWidthItTypes() {
        for (on in listOf(true, false)) {
            home.deleteRecursively()
            PunctuationManager.forget()
            File(home, "config/" + ModuleConfigs.PUNCTUATION).apply { parentFile!!.mkdirs() }
                .writeText("HalfWidthPuncAfterLetterOrNumber=" + (if (on) "True" else "False") + "\n")
            fcitx = startedHost(ScriptedEngine(home))
            // the built-in table is not among the test's assets: its comma put in a user's table
            runBlocking { fcitx.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "，", ""))) }
            h = KeyboardHarness(host = fcitx)
            h.service.postFcitxJob { activate(1, "org.example.app") }
            h.start()
            val what = "option $on"
            val comma = { h.keyWithId(R.id.button_comma)!! }
            val shown = { faceAndBubble(comma) }
            val twice = { c: String -> listOf(c, c) }
            assertEquals(what, twice("，"), shown())
            until("the mark typed") { h.field.text.toString() == "，" }
            // the scripted engine composes only a to z: the A of shift's typed as itself
            h.tap(h.keyNamed("shift"))
            h.tap(h.key("A"))
            until("A typed") { h.field.text.toString() == "，A" }
            val half = if (on) "," else "，"
            synced()
            // the host tells the map after the A's selection reached it: its face drawn anew first
            until("the comma's face told") { comma().label == half }
            assertEquals(what, twice(half), shown())
            until("the mark typed") { h.field.text.toString() == "，A$half" }
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

    // the first press as soon as the keyboard is up: face, bubble and what it types one mark (a replayed status once
    // drew , where the engine typed 、)
    @Test
    fun theFirstPressAtOnceShowsWhatItTypes() {
        fcitx = startedHost(ScriptedEngine(home))
        runBlocking { fcitx.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "、", ""))) }
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

    // a host that ran before read a user's table, and stopped; a new one made, not started, the keyboard up before it
    private fun keyboardBeforeTheHost(kept: Boolean): KotlinHost {
        PunctuationStartKeys.forget(appContext)
        startedHost(ScriptedEngine(home)).let { first ->
            runBlocking { first.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "、", ""))) }
            first.stop()
        }
        if (kept) PunctuationStartKeys.loadNow(appContext) else PunctuationStartKeys.forget(appContext)
        val next = KotlinHost(appContext, ScriptedEngine(home))
        h = KeyboardHarness(host = next)
        h.service.postFcitxJob { activate(1, "org.example.app") }
        h.start()
        assertTrue("the host not up yet", !next.isReady)
        return next
    }

    // the comma pressed before the host is up, its keys as the last run started with them (PunctuationStartKeys)
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

    // the service's read done after the keyboard was made: a press then takes the keys read, not held back
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

    // none kept: the comma pressed before the host is up neither types nor bubbles, as no face could show what it would type
    @Test
    fun beforeTheHostIsUpWithNoneKeptTheKeysAreHeldBack() {
        fcitx = keyboardBeforeTheHost(kept = false)
        val k = h.keyWithId(R.id.button_comma)!!
        h.press(k)
        assertEquals(emptyList<String>(), h.popupChoices().map { it.text })
        h.release()
        fcitx.start()
        until("the host up") { fcitx.isReady }
        // a key queued for the host goes through the service's jobs and the main thread: a few turns, time run on
        repeat(5) { runBlocking { fcitx.isEmpty() }; h.after(200) }
        assertEquals("", h.field.text.toString())
        h.after(BUBBLE_GONE_MS)
        assertEquals(listOf("、", "、"), faceAndBubble { h.keyWithId(R.id.button_comma)!! })
        until("the mark typed") { h.field.text.isNotEmpty() }
        repeat(5) { runBlocking { fcitx.isEmpty() }; h.after(200) }
        assertEquals("the held one never typed", "、", h.field.text.toString())
    }

    @Test
    fun offThenOnTheCommaShowsWhatItTypesEachPress() {
        fcitx = startedHost(ScriptedEngine(home))
        runBlocking { fcitx.putPunctuationTable(home, listOf(PunctuationMapEntry(",", "、", ""))) }
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

    // both read while it is held, before the release types: the mark typed may already have made the host tell the
    // next one (after a half-width , the full-width ， again)
    private fun faceAndBubble(key: () -> org.fcitx.fcitx5.android.input.ShownKey): List<String> {
        h.after(BUBBLE_GONE_MS)
        val k = key()
        h.press(k)
        val face = k.label
        val bubble = h.popupChoices().map { it.text }.single()
        h.release()
        return listOf(face, bubble)
    }

    private companion object {
        const val BUBBLE_GONE_MS = 1000L
    }
}
