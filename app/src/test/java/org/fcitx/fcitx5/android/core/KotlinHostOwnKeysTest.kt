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
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.KeyStates
import org.fcitx.fcitx5.android.host.KeySyms
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

/** A layout's own groups (060-render-p3 T9b) through the real host to a scripted engine, as KotlinHostTest's keys. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KotlinHostOwnKeysTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var engine: ScriptedEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events = HostLog<HostEvent>()

    @Before
    fun setUp() {
        home = File(appContext.filesDir, "kotlin-host").apply { deleteRecursively() }
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        engine = ScriptedEngine(home)
        fcitx = startedHost(engine, firstRun = false) { host -> scope.launch { host.eventFlow.collect(events::add) } }
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
        // other groups told: a key of none of theirs still reads by the ones before (061-custom-layout L4), their first code by them
        val other = NineKeyLetters.custom(OWN.reversed())
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey('b', 0u, 0, false, 0)
        assertEquals(letters, NineKeyLetters.ofCode(other.codes[0].code))
        fcitx.sendKey(other.codes[0], 0u, 0, false, 0)
        assertEquals(other, NineKeyLetters.ofCode(other.codes[0].code))
    }

    // T9b's leftover (T10), as KeyboardPair turns (061-custom-layout L4): other groups told while a code is typed on
    // the ones before commit the code at their first code as Return does (EngineBridge.turnAt, HostCore.commitCode),
    // not lost with the session made anew; no reset, so the context stays as after any commit
    @Test
    @Uat("PY-155")
    fun otherGroupsWhileTypingCommitTheCodeAtTheirFirstCode() = runBlocking {
        focused()
        EngineState.ownKeys = OWN
        "nih".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        assertEquals("the same groups: typed on", "nih", fcitx.inputPanelCached.preedit.toString())
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey(NineKeyLetters.custom(OWN.reversed()).codes[0], 0u, 0, false, 0)
        assertEquals(listOf("nih"), events.list().filterIsInstance<HostEvent.Commit>().map { it.text })
        // the code committed, then the key itself on to the engine: not swallowed by the turn
        assertEquals(listOf(EngineEvent.ENTER, EngineEvent.CHAR), engine.events.takeLast(2))
        assertFalse("not reset", EngineEvent.RESET in engine.events.takeLast(3))
    }

    // 061-custom-layout L4: a key before the other groups' first code (a Backspace) is still the code typed's,
    // nothing committed under it; their first code then commits what is left
    @Test
    @Uat("PY-155")
    fun aBackspaceAfterOtherGroupsAreToldTakesFromTheCodeTyped() = runBlocking {
        focused()
        EngineState.ownKeys = OWN
        "nih".forEach { fcitx.sendKey(it, 0u, 0, false, 0) }
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey(KeySyms.BACKSPACE, 0u, 0, false, 0)
        assertEquals(emptyList<String>(), events.list().filterIsInstance<HostEvent.Commit>().map { it.text })
        assertEquals("ni", fcitx.inputPanelCached.preedit.toString())
        fcitx.sendKey(NineKeyLetters.custom(OWN.reversed()).codes[0], 0u, 0, false, 0)
        assertEquals(listOf("ni"), events.list().filterIsInstance<HostEvent.Commit>().map { it.text })
    }

    // review H1: the field's composing text (English's word) is no code of the groups: other groups
    // told while it is there leave it, nothing committed, nothing cleared; nor the first groups ever told.
    // A key of none of theirs waits (OwnKeysTurn.Step.WAIT), never reaching HostCore.commitCode; their first code
    // turns (COMMIT_AND_TURN), and it is commitCode's guard (a code on the panel, not the field's word) that keeps it
    @Test
    fun otherGroupsLeaveTheFieldsComposingTextAlone() = runBlocking {
        focused()
        EngineState.ownKeys = OWN
        fcitx.sendKey('n', 0u, 0, false, 0)
        fcitx.select(0, null)
        events.clear()
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH))
        fcitx.activateIme(InputMethodCatalog.ENGLISH)
        // the screen's keys: English composes its word from them, a hardware key's types itself
        fcitx.sendKey('a', KeyStates.VIRTUAL.toUInt(), 0, false, 0)
        assertEquals("a", fcitx.clientPreeditCached.toString())
        EngineState.ownKeys = OWN.reversed()
        fcitx.sendKey('b', KeyStates.VIRTUAL.toUInt(), 0, false, 0)
        assertEquals(emptyList<String>(), events.list().filterIsInstance<HostEvent.Commit>().map { it.text })
        assertEquals("ab", fcitx.clientPreeditCached.toString())
        // their first code turns all the same (COMMIT_AND_TURN): commitCode reached, the word kept as it is
        fcitx.sendKey(NineKeyLetters.custom(OWN.reversed()).codes[0], KeyStates.VIRTUAL.toUInt(), 0, false, 0)
        assertEquals(emptyList<String>(), events.list().filterIsInstance<HostEvent.Commit>().map { it.text })
        assertEquals("ab", fcitx.clientPreeditCached.toString())
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
