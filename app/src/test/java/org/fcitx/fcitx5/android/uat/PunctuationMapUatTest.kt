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
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
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

    private val home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness

    @After
    fun close() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        home.deleteRecursively()
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
}
