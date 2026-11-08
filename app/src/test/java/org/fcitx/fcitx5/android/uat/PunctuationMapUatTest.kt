/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
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
    }

    private fun until(what: String, done: () -> Boolean) = waitUntil(what, step = { if (::h.isInitialized) h.idle() }, done = done)

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
        h.tap(h.inputView.findViewById<View>(R.id.button_comma))
        until("the mark in the field") { h.field.text.isNotEmpty() }
        assertEquals("、", h.field.text.toString())
    }

    // 060-render-p2 S7 #7: "half width after a letter or number" on, after a letter the ， key shows and
    // bubbles the , it types; after a Chinese word it is ， again; off, ， throughout. Both drawings.
    @Test
    fun afterALetterTheCommaShowsTheHalfWidthItTypes() {
        for (canvas in listOf(true, false)) for (on in listOf(true, false)) {
            prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
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
            val what = "canvas $canvas, option $on"
            val comma = { h.keyWithId(R.id.button_comma)!! }
            // the views' bubble shows the key's own character, mapped or not (the old drawing's, never
            // made to map; S8 deletes it): on the views the face only
            val shown = { faceAndBubble(comma).let { if (canvas) it else it.take(1) } }
            val twice = { c: String -> if (canvas) listOf(c, c) else listOf(c) }
            // the first press at once, the start's statuses not waited for: its mark shown and typed
            val p = ""
            assertEquals(what, twice("，"), shown())
            until("the mark typed") { h.field.text.toString() == p + "，" }
            // a letter the engine leaves (the scripted one composes only a to z): typed as itself, the A of shift's
            h.tap(h.keyNamed("shift"))
            h.tap(h.key("A"))
            until("A typed") { h.field.text.toString() == p + "，A" }
            val half = if (on) "," else "，"
            assertEquals(what, twice(half), shown())
            until("the mark typed") { h.field.text.toString() == p + "，A$half" }
            // a Chinese word after it: the comma back to its mark
            h.tap(h.key("a"))
            runBlocking { fcitx.select(0) }
            until("the word typed") { h.field.text.endsWith("你好") }
            assertEquals(what, twice("，"), shown())
            until("the mark typed") { h.field.text.toString() == p + "，A${half}你好，" }
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
