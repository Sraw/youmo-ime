/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** A layout's own groups (060-render-p3 T9b) through the real host to a scripted engine, as KotlinHostTest's keys. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinHostOwnKeysTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var engine: ScriptedEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events: MutableList<HostEvent> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        engine = ScriptedEngine(home)
        fcitx = startedHost(engine, firstRun = false) { host -> scope.launch { host.eventFlow.collect { events += it } } }
    }

    @After
    fun tearDown() {
        fcitx.stop()
        scope.cancel()
        home.deleteRecursively()
        // published by the host, read by the keyboard window: not the next test's
        EngineState.ownKeys = null
        NineKeyLetters.clearInUse()
    }

    // T9b review L6: the groups a layout's own keyboard told as it was shown reach the engine's side with its
    // first key, before the key does (EngineBridge.syncOwnKeys): the keyboard its codes are read by
    @Test
    fun aKeyTellsTheGroupsTheKeyboardShownToldFirst() = runBlocking {
        val letters = NineKeyLetters.custom(OWN)
        NineKeyLetters.clearInUse()
        EngineState.ownKeys = OWN
        assertNull(NineKeyLetters.ofCode(letters.codes[0].code))
        fcitx.sendKey('a', 0u, 0, false, 0)
        assertEquals(letters, NineKeyLetters.ofCode(letters.codes[0].code))
        // other groups told: the next key reads by them
        val other = NineKeyLetters.custom(OWN.reversed())
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey('b', 0u, 0, false, 0)
        assertEquals(other, NineKeyLetters.ofCode(other.codes[0].code))
    }

    // T9b's leftover (T10): other groups told while a code is typed on the ones before commit the code as Return
    // does (EngineBridge.replaces, HostCore.commitCode), not lost with the session made anew; no reset, so the
    // context stays as after any commit
    @Test
    fun otherGroupsWhileTypingCommitTheCodeFirst() = runBlocking {
        focused()
        EngineState.ownKeys = OWN
        "nih".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        assertEquals("the same groups: typed on", "nih", fcitx.inputPanelCached.preedit.toString())
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey('a', 0u, 0, false, 0)
        assertEquals(listOf("nih"), events.filterIsInstance<HostEvent.Commit>().map { it.text })
        assertEquals("a", fcitx.inputPanelCached.preedit.toString())
        assertFalse("not reset", EngineEvent.RESET in engine.events.takeLast(3))
    }

    // review H1: the field's composing text (handwriting's, English's word) is no code of the groups: other groups
    // told while it is there leave it, nothing committed, nothing cleared; nor the first groups ever told
    @Test
    fun otherGroupsLeaveTheFieldsComposingTextAlone() = runBlocking {
        focused()
        EngineState.ownKeys = OWN
        fcitx.sendKey('n', 0u, 0, false, 0)
        fcitx.select(0, null)
        events.clear()
        fcitx.activateIme(InputMethodCatalog.HANDWRITING)
        fcitx.sendKey('a', 0u, 0, false, 0)
        assertEquals(ScriptedEngine.WRITTEN, fcitx.clientPreeditCached.toString())
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey('b', 0u, 0, false, 0)
        assertEquals(emptyList<String>(), events.filterIsInstance<HostEvent.Commit>().map { it.text })
        assertEquals(ScriptedEngine.WRITTEN.repeat(2), fcitx.clientPreeditCached.toString())
    }

    private suspend fun focused() {
        fcitx.activate(1, "org.example")
        fcitx.setCapFlags(CapabilityFlags.DefaultFlags)
        fcitx.focus(true)
    }

    private companion object {
        val OWN = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m", "pq rs", "abc", "xyz")
    }
}
