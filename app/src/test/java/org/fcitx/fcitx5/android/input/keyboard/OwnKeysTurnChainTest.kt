/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.Looper
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.HostLog
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Another grouping of the user's shown while one is typed on (061-custom-layout §6, L4; review S2), the whole way: two
 * GroupedKeyboards of layouts of their own groups each told as it is shown (onAttach, EngineState.ownKeys), their keys'
 * taps (codes of their own with their places, GroupedKeyboard.tapped) sent to the app's host (KotlinHost, its
 * EngineBridge.turnAt) and its real engines. KotlinHostOwnKeysTest has the host's part on a scripted engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class OwnKeysTurnChainTest {

    private val context = RuntimeEnvironment.getApplication()
    private val sourceWas = SceneLayouts.source
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events = HostLog<HostEvent>()
    private lateinit var host: KotlinHost
    private val sent = mutableListOf<Int>()

    // keyboard-scene's sample 17 keys, its own groups, in the 14 keys' place; in the 18 keys', the same with the
    // letters of ABC and XYZ swapped: another grouping of its own (other codes for those two keys)
    private val sample = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")
    private val other = sample.replace("letters=\"abc\"", "letters=\"@@\"").replace("letters=\"xyz\"", "letters=\"abc\"")
        .replace("letters=\"@@\"", "letters=\"xyz\"")

    // the second's groups as it tells them
    private val otherGroups = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m", "pq rs", "xyz", "abc")

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Before
    fun setUp() {
        // the test text pack with the engine's data the build made (build/generated) linked in: the engine reads its
        // pack's files by path (AppData.map), and the test pack holds none; build/generated is the unit tests' input
        val shelf = TestShelves.shelf(PackKind.TEXT)
        val dir = shelf.dir(PackKind.TEXT)!!
        for (root in File("build/generated").listFiles().orEmpty().filter { it.name.startsWith("engine-") }) {
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val to = File(dir, f.relativeTo(root).path).apply { parentFile!!.mkdirs() }
                if (!to.exists()) Files.createSymbolicLink(to.toPath(), f.absoluteFile.toPath())
            }
        }
        check(File(dir, "engine/pinyin.data").isFile) { "engine/pinyin.data not in build/generated: run ./gradlew :app:compileEngineData first" }
        PackStore.useShelf(shelf)
        EngineState.ownKeys = null
        SceneLayouts.source = { ctx ->
            val app = BuiltInLayouts(ctx)
            object : LayoutSource {
                override val key = "own-keys-turn"

                override fun text(id: String) = when (id) {
                    "k14" -> sample
                    "k18" -> other
                    else -> app.text(id)
                }
            }
        }
        // a fresh install: its first run enables pinyin (AppPrefs outlives a case's sandbox)
        AppPrefs.getInstance().internal.firstRun.setValue(true)
        host = KotlinHost(context).apply {
            scope.launch { eventFlow.collect(events::add) }
            start()
            until("the host ready") { isReady }
        }
    }

    @After
    fun tearDown() {
        host.stop()
        scope.cancel()
        SceneLayouts.source = sourceWas
        // the shared test packs back for the cases after this one in the JVM
        TestShelves.use()
        EngineState.ownKeys = null
        NineKeyLetters.clearInUse()
    }

    // made, then wired to the host as the input view wires a keyboard's keys: each code sent down and up
    private fun GroupedKeyboard.wired() = apply {
        setViewTreeLifecycleOwner(Owner())
        keyActionListener = KeyActionListener { action, _ ->
            val key = action as KeyAction.SymAction
            sent += key.sym.sym
            runBlocking {
                host.sendKey(key.sym, key.states, 0, false, 0, null)
                host.sendKey(key.sym, key.states, 0, true, 0, null)
            }
        }
        laidOut(1080, 800)
    }

    // the keyboard's own touch at ([x], [y]) of [key]'s cell, each a part of its width and height
    private fun GroupedKeyboard.touch(key: ShownKey, x: Float, y: Float) {
        key as SceneShownKey
        val ex = key.canvas.left + key.bounds.left + key.bounds.width() * x
        val ey = key.canvas.top + key.bounds.top + key.bounds.height() * y
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 1_000L, MotionEvent.ACTION_UP to 1_010L)) {
            val event = MotionEvent.obtain(1_000L, time, action, ex, ey, 0)
            dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun commits() = events.list().filterIsInstance<HostEvent.Commit>().map { it.text }

    private fun preedit() = host.inputPanelCached.preedit.toString()

    @Test
    @Uat("PY-155")
    fun anotherGroupingShownWhileTypingTakesOverAtItsFirstKey() = runBlocking {
        host.activate(1, "org.example")
        host.setCapFlags(CapabilityFlags.DefaultFlags)
        host.focus(true)
        host.activateIme(InputMethodCatalog.PINYIN)
        assertEquals(InputMethodCatalog.PINYIN, host.currentIme().uniqueName)
        val first = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = true).wired()
        val second = GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false, ownGroups = true)
            .wired()
        first.onAttach()
        // n i h: each on the right half of its key
        for (k in listOf("BN", "UI", "GH")) first.touch(first.shownKey(k), 0.8f, 0.5f)
        val typed = preedit()
        assertTrue("sent ${sent.map(Integer::toHexString)}", typed.isNotEmpty())
        // each a code of its own with the tap's place (Keyboard.PLACES), as GroupedKeyboard.tapped sends it
        assertTrue(sent.map(Integer::toHexString).toString(), sent.all { KeySyms.toUnicode(it) in Keyboard.PLACES })
        // the other keyboard shown (⋯ → 键盘, the input view's keyboard attached): its groups told, not yet typed on
        second.onAttach()
        assertEquals(otherGroups, EngineState.ownKeys)
        events.clear()
        // a Backspace edits what the first typed, nothing committed
        host.sendKey(KeySyms.BACKSPACE, 0u, 0, false, 0)
        host.sendKey(KeySyms.BACKSPACE, 0u, 0, true, 0)
        assertEquals(emptyList<String>(), commits())
        val left = preedit()
        assertTrue("$typed → $left", left.isNotEmpty() && left.length < typed.length)
        // the second's first key: what is left committed as Return commits it, then typed on the second's groups
        second.touch(second.shownKey("M"), 0.5f, 0.5f)
        assertEquals(listOf(left.replace(" ", "")), commits())
        assertEquals("m", preedit())
    }
}
