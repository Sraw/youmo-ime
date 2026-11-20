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
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/** A pack's layout that reads but that the engine refuses as it places it drew no key at all; it now draws the app's layout of the same id. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PackLayoutRefusedTest {

    @get:Rule
    val prefs = PrefsRule()

    private val context = RuntimeEnvironment.getApplication()
    private val sourceWas = SceneLayouts.source
    private val sent = ArrayList<KeyAction>()

    // its last row's five keys 30 units each: over the board's 100 columns
    private val tooWide = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("width=\"20\"", "width=\"30\"")
        .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")

    // a fourth row of one key shown only with the language key: with it hidden the engine refuses the layout
    private val refusedWithoutTheLanguageKey = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("id=\"r2c5\" label=\"ZX\"", "id=\"zx\" label=\"ZX\"")
        .replace("<key id=\"r2c4\" label=\"L\" letters=\"l\"/>", "<key id=\"$L\" label=\"L\" letters=\"l\" shortcut=\"g14/l\"/>")
        .replace("</keyboard>", "  <row><key id=\"more\" label=\"+\" send=\"+\" when=\"lang-key\"/></row>\n  <include src=\"grouped-bottom\"/>\n</keyboard>")

    private var packed = mapOf("k14" to tooWide)
    private var packedAs = "k17-too-wide"

    @Before
    fun theTooWideSampleInPlaceOfTheFourteenKeys() {
        EngineState.ownKeys = null
        // no layout remembered refused by a test before this one
        SceneBoard.forgetScenes()
        SceneLayouts.source = { ctx ->
            val app = BuiltInLayouts(ctx)
            object : LayoutSource {
                override val key get() = packedAs

                override fun text(id: String) = packed[id] ?: app.text(id)
            }
        }
    }

    private fun langKey(shown: Boolean) {
        prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, shown)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun <K : BaseKeyboard> K.wired(): K = apply {
        setViewTreeLifecycleOwner(object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        })
        keyActionListener = KeyActionListener { action, _ -> sent += (action as? KeyAction.SymAction)?.copy(touch = null) ?: action }
        laidOut()
    }

    private fun BaseKeyboard.tap(key: SceneShownKey, across: Float = 0.5f) {
        val x = key.canvas.left + key.bounds.left + key.bounds.width() * across
        val y = key.canvas.top + key.bounds.top + key.bounds.height() * 0.5f
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 1_000L, MotionEvent.ACTION_UP to 1_010L)) {
            MotionEvent.obtain(1_000L, time, action, x, y, 0).also { dispatchTouchEvent(it) }.recycle()
        }
    }

    private fun BaseKeyboard.typed(of: (SceneShownKey) -> Boolean): Map<String, List<KeyAction>> =
        shownKeys().filterIsInstance<SceneShownKey>().filter(of).associate { key ->
            sent.clear()
            tap(key)
            key.id to sent.toList()
        }

    private fun grouped() = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = true).wired()

    private fun GroupedKeyboard.groupsTyped() = typed { it.node.letters != null }

    private fun labels(keyboard: BaseKeyboard) = keyboard.shownKeys().map { it.label }

    @After
    fun asBuiltIn() {
        SceneLayouts.source = sourceWas
        SceneBoard.forgetScenes()
        EngineState.ownKeys = null
        NineKeyLetters.clearInUse()
    }

    @Test
    @Uat("ST-115")
    fun aLayoutTheEngineRefusesDrawsTheAppsOwnOfItsId() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = true).laidOut()
        val labels = keyboard.shownKeys().map { it.label }
        assertTrue(labels.toString(), "PQRS" !in labels && "XYZ" !in labels)
        assertTrue(labels.toString(), "BN" in labels)
        assertTrue(keyboard.shownKeys().any { it.id == "space" })
    }

    // remembered refused, only the app's own is made: one scene a change
    @Test
    @Uat("ST-115")
    fun aLayoutRefusedIsNotMadeAgainAsWhatTheKeysShowChanges() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = true).laidOut()
        assertTrue(keyboard.shownKeys().any { it.label == "BN" })
        val made = keyboard.scenesMade
        keyboard.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search))
        assertTrue(keyboard.shownKeys().any { it.label == "BN" })
        assertEquals(made + 1, keyboard.scenesMade)
    }

    // a refusal is remembered per environment, more than 16; the last used are kept, one asked again moved last; logged once
    @Test
    @Uat("ST-115")
    fun aRefusalInUseIsKeptThroughManyOthersAndLoggedOnce() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        try {
            val keyboard = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = true).laidOut()
            assertTrue(keyboard.shownKeys().any { it.label == "BN" })
            val inUse = SceneBoard.refusals.last()
            val size = "${inUse.placing.width.roundToInt()}×${inUse.placing.height.roundToInt()}"
            fun logs() = logged.count { it.endsWith("refused at $size; the app's own drawn") }
            val loggedFirst = logs()
            assertTrue(logged.toString(), loggedFirst > 0)
            // put in directly: through the view each would be a relayout per refusal for a list only the remembering reads
            fun others(from: Int, count: Int) = repeat(count) {
                SceneBoard.refusedHere(SceneBoard.RefusedIn(inUse.layout, inUse.placing.copy(width = inUse.placing.width + from + it)))
            }
            others(1, SceneBoard.KEPT_REFUSED - 1)
            keyboard.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search))
            assertTrue(keyboard.shownKeys().any { it.label == "BN" })
            // one more than are kept: the least recently used goes, not the one in use
            others(SceneBoard.KEPT_REFUSED, 1)
            val made = keyboard.scenesMade
            keyboard.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_send_24, R.string.a11y_key_action_send))
            assertTrue(keyboard.shownKeys().any { it.label == "BN" })
            // the pack's not asked of the engine again: still remembered refused
            assertEquals(made + 1, keyboard.scenesMade)
            assertEquals(logged.toString(), loggedFirst, logs())
        } finally {
            Timber.uproot(tree)
        }
    }

    // remembered by what its rows are made from, not by layout and size alone: another state asks the engine again
    @Test
    @Uat("ST-115")
    fun aLayoutRefusedInOneStateIsDrawnInAnotherAtTheSameSize() {
        packed = mapOf("k14" to refusedWithoutTheLanguageKey)
        packedAs = "k17-refused-without-the-language-key"
        langKey(false)
        val keyboard = grouped()
        assertTrue(labels(keyboard).toString(), "PQRS" !in labels(keyboard) && "BN" in labels(keyboard))
        langKey(true)
        assertTrue(labels(keyboard).toString(), "PQRS" in labels(keyboard) && "+" in labels(keyboard))
        langKey(false)
        assertTrue(labels(keyboard).toString(), "PQRS" !in labels(keyboard) && "BN" in labels(keyboard))
    }

    // drawn and typed on, then refused as the state changed: a key kept by SceneKeys went on sending the pack's code,
    // read as nothing; every group key now sends what a keyboard made anew in that state sends
    @Test
    @Uat("ST-115")
    fun theKeysTypedOnBeforeTheLayoutDrawnChangedSendTheDrawnLayoutsCodes() {
        fun typedThroughTheChange(packFirst: Boolean) {
            EngineState.ownKeys = null
            SceneBoard.forgetScenes()
            langKey(packFirst)
            val keyboard = grouped()
            keyboard.onAttach()
            // detached as the row ends, so the next row's ownKeys are told by its own keyboard alone
            try {
                assertEquals(packFirst, "PQRS" in labels(keyboard))
                val before = keyboard.groupsTyped()
                langKey(!packFirst)
                assertEquals(!packFirst, "PQRS" in labels(keyboard))
                if (packFirst) assertNull(EngineState.ownKeys) else assertNotNull(EngineState.ownKeys)
                val after = keyboard.groupsTyped()
                assertEquals(grouped().groupsTyped(), after)
                assertNotEquals(before.getValue(L), after.getValue(L))
            } finally {
                keyboard.onDetach()
            }
        }
        packed = mapOf("k14" to refusedWithoutTheLanguageKey)
        packedAs = "k17-refused-without-the-language-key"
        assertRows(
            "the pack's drawn and typed on, then refused: each key sends the built-in group's code" to { typedThroughTheChange(packFirst = true) },
            "the pack's refused and the app's own typed on, then the pack's drawn: each key sends the pack's own group's code" to { typedThroughTheChange(packFirst = false) },
        )
    }

    // 注音's key code is made from the face shown as the key is made: a key kept through a face change sent the old symbol
    @Test
    @Uat("ST-115")
    fun aZhuyinKeyWhoseFaceChangedSendsTheSymbolItShows() {
        val first = "<key id=\"1\" width=\"fill\" label=\"ㄅ\" send=\"action:zhuyin\" shortcut=\"zyfull/1\""
        val app = BuiltInLayouts(context).text("zhuyin")!!
        assertTrue(first in app)
        packed = mapOf("zhuyin" to app.replace("$first/>", "$first><variant when=\"lang-key\" label=\"ㄆ\"/></key>"))
        packedAs = "zhuyin-a-face-by-the-language-key"
        langKey(false)
        val keyboard = ZhuyinKeyboard(context, TestThemes.PixelDark, nine = false).wired()
        fun shown() = keyboard.shownKeys().first { it.id == "1" }.label
        fun ZhuyinKeyboard.firstTyped() = typed { it.id == "1" }.getValue("1")
        assertEquals("ㄅ", shown())
        val before = keyboard.firstTyped()
        langKey(true)
        assertEquals("ㄆ", shown())
        val after = keyboard.firstTyped()
        assertEquals(ZhuyinKeyboard(context, TestThemes.PixelDark, nine = false).wired().firstTyped(), after)
        assertNotEquals(before, after)
    }

    // the app's 14 keys in the refused layout's place are typed as the built-in groups; the refused groups not told
    @Test
    @Uat("ST-115")
    fun theAppsOwnLayoutDrawnInsteadIsTypedAsTheBuiltInGroups() {
        val keyboard = grouped()
        keyboard.onAttach()
        // shown before its scene is made (GroupedKeyboard.onAttach); taken back as the scene draws the app's own
        val bn = keyboard.shownKey("BN") as SceneShownKey
        assertNull(EngineState.ownKeys)
        keyboard.tap(bn, across = 0.75f)
        assertEquals(listOf<KeyAction>(KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(g14Code('b'), 0.75f)), KeyStates.Virtual)), sent)
    }

    private companion object {
        // L's key id in k14.xml, and in the sample made from it
        const val L = "r2c5"
    }
}
