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

/**
 * audit-061 scene-01 (b): a theme pack's layout that reads (LayoutReader takes it) but that the engine refuses as it
 * places it (here a row 150 units wide on a board of 100) drew no key at all, the built-in theme failing on the same
 * layout. It now draws the app's own layout of the same id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PackLayoutRefusedTest {

    @get:Rule
    val prefs = PrefsRule()

    private val context = RuntimeEnvironment.getApplication()
    private val sourceWas = SceneLayouts.source
    private val sent = ArrayList<KeyAction>()

    // the 17 keys' sample, its last row's five keys 30 units each: over the board's 100 columns
    private val tooWide = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("width=\"20\"", "width=\"30\"")
        .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")

    // the 17 keys' sample, its L the app's 14 keys' L to the letter (a pack's layout made from k14.xml keeps the keys it
    // did not change: the same id, letters and name), and a fourth row of one key shown only with the language key:
    // with it hidden (显示语言切换键 off) the row has nothing to show and the engine refuses the layout there
    private val refusedWithoutTheLanguageKey = File("../lib/keyboard-scene/src/commonTest/data/k17.xml").readText()
        .replace("id=\"r2c5\" label=\"ZX\"", "id=\"zx\" label=\"ZX\"")
        .replace("<key id=\"r2c4\" label=\"L\" letters=\"l\"/>", "<key id=\"$L\" label=\"L\" letters=\"l\" shortcut=\"g14/l\"/>")
        .replace("</keyboard>", "  <row><key id=\"more\" label=\"+\" send=\"+\" when=\"lang-key\"/></row>\n  <include src=\"grouped-bottom\"/>\n</keyboard>")

    // what the test's source has in place of the app's, by layout id, and the key its layouts are kept under
    private var packed = mapOf("k14" to tooWide)
    private var packedAs = "k17-too-wide"

    @Before
    fun theTooWideSampleInPlaceOfTheFourteenKeys() {
        EngineState.ownKeys = null
        // R25r3 F7946: no layout remembered refused by a test before this one
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
        keyActionListener = KeyActionListener { action, _ -> sent += action }
        laidOut()
    }

    // a tap [across] its key (0 its left edge, 1 its right): a group key's code says where it leaned
    private fun BaseKeyboard.tap(key: SceneShownKey, across: Float = 0.5f) {
        val x = key.canvas.left + key.bounds.left + key.bounds.width() * across
        val y = key.canvas.top + key.bounds.top + key.bounds.height() * 0.5f
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 1_000L, MotionEvent.ACTION_UP to 1_010L)) {
            MotionEvent.obtain(1_000L, time, action, x, y, 0).also { dispatchTouchEvent(it) }.recycle()
        }
    }

    // what a tap in the middle of each key [of] the keys shown sends, by the key's id
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
        // the sample's own keys not drawn (refused), the app's 14 keys and the bottom row drawn in their place
        assertTrue(labels.toString(), "PQRS" !in labels && "XYZ" !in labels)
        assertTrue(labels.toString(), "BN" in labels)
        assertTrue(keyboard.shownKeys().any { it.id == "space" })
    }

    // R25r2 F7833: the refused layout was made again, to be refused and logged again, before the app's own each time
    // what the keys show changed (most keys typed). Remembered refused, only the app's own is made: one scene a change
    @Test
    @Uat("ST-115")
    fun aLayoutRefusedIsNotMadeAgainAsWhatTheKeysShowChanges() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark, ownGroups = true).laidOut()
        assertTrue(keyboard.shownKeys().any { it.label == "BN" })
        val made = keyboard.scenesMade
        // Return's icon another: a scene not kept
        keyboard.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search))
        assertTrue(keyboard.shownKeys().any { it.label == "BN" })
        assertEquals(made + 1, keyboard.scenesMade)
    }

    // R25r4 F7976 + F7981: a refusal is remembered an environment (a field, Shift's state, typing or not …), more than
    // the 16 a theme's looks are, and the oldest went first: in ordinary use one in use was let go of, made, refused and
    // logged again. The last used are kept, one asked again moved last; logged once, the size in whole pixels
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
            // the one drawn now: the last used
            val inUse = SceneBoard.refusals.last()
            // logged as refused, the size in whole pixels
            val size = "${inUse.placing.width.roundToInt()}×${inUse.placing.height.roundToInt()}"
            fun logs() = logged.count { it.endsWith("refused at $size; the app's own drawn") }
            val loggedFirst = logs()
            assertTrue(logged.toString(), loggedFirst > 0)
            // others of the same layout, each another width (another environment). Put in directly, as is the one in
            // use read above (R25r6 F8032): through the view each would be a keyboard laid out at another size, one
            // relayout per refusal for a list only the remembering reads. What is checked stays the view's: scenes made, logs
            fun others(from: Int, count: Int) = repeat(count) {
                SceneBoard.refusedHere(SceneBoard.RefusedIn(inUse.layout, inUse.placing.copy(width = inUse.placing.width + from + it)))
            }
            others(1, SceneBoard.KEPT_REFUSED - 1)
            // drawn again (Return's icon another, a scene not kept): the refusal asked, so the last used
            keyboard.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_action_search))
            assertTrue(keyboard.shownKeys().any { it.label == "BN" })
            // one more than are kept: the least recently used goes, not the one in use
            others(SceneBoard.KEPT_REFUSED, 1)
            val made = keyboard.scenesMade
            keyboard.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_send_24, R.string.a11y_key_action_send))
            assertTrue(keyboard.shownKeys().any { it.label == "BN" })
            // the app's own alone made: the pack's not asked of the engine again (still remembered refused; let go
            // of, it would be made and refused again, two scenes and logged again)
            assertEquals(made + 1, keyboard.scenesMade)
            // and not logged again
            assertEquals(logged.toString(), loggedFirst, logs())
        } finally {
            Timber.uproot(tree)
        }
    }

    // R25r3 F7922: the refusal was remembered by the layout and the keyboard's size alone, so a layout refused in one
    // state (here the language key hidden) drew the app's own in every state at that size till the process ended.
    // Remembered by what its rows are made from (the env less what the keys show), another state asks the engine again
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
        // and back: refused there still, the app's own again
        langKey(false)
        assertTrue(labels(keyboard).toString(), "PQRS" !in labels(keyboard) && "BN" in labels(keyboard))
    }

    // R25r3 F7962: the pack's layout drawn and typed on, then refused as the state changed (the app's 14 keys drawn in
    // its place): a key of the same id that does the same in both layouts (L: SceneKeys kept it) went on sending the
    // pack's own group's code, which the engine, told no groups of its own any more, read as nothing. Every group
    // key now sends what a keyboard made anew in that state sends; and the other way round, the app's own typed on first
    @Test
    @Uat("ST-115")
    fun theKeysTypedOnBeforeTheLayoutDrawnChangedSendTheDrawnLayoutsCodes() {
        fun typedThroughTheChange(packFirst: Boolean) {
            EngineState.ownKeys = null
            SceneBoard.forgetScenes()
            langKey(packFirst)
            val keyboard = grouped()
            keyboard.onAttach()
            // R25r4 F7974: detached as the row ends, so the next row's ownKeys are told by its own keyboard alone, not
            // by this one still shown and redrawn by the same setting
            try {
                assertEquals(packFirst, "PQRS" in labels(keyboard))
                val before = keyboard.groupsTyped()
                langKey(!packFirst)
                assertEquals(!packFirst, "PQRS" in labels(keyboard))
                // the pack's groups told the engine while its layout is the one drawn, and only then
                if (packFirst) assertNull(EngineState.ownKeys) else assertNotNull(EngineState.ownKeys)
                val after = keyboard.groupsTyped()
                assertEquals(grouped().groupsTyped(), after)
                // L is in both layouts under one id, its code each layout's own
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

    // R25r3 F7962, 注音's same root: its key's code is made from the face the key shows as the key is made, and a key
    // kept through a face changed (a pack's <variant>, here with the language key shown) sent the last face's symbol
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

    // R25 NF-app: the app's own 14 keys drawn in the refused layout's place are typed as the built-in groups, and
    // the refused layout's own groups are not told the engine (EngineState.ownKeys) as the keyboard is shown
    @Test
    @Uat("ST-115")
    fun theAppsOwnLayoutDrawnInsteadIsTypedAsTheBuiltInGroups() {
        val keyboard = grouped()
        keyboard.onAttach()
        // shown before its scene is made, the layout asked for told (GroupedKeyboard.onAttach); taken back as the
        // scene made draws the app's own
        val bn = keyboard.shownKey("BN") as SceneShownKey
        assertNull(EngineState.ownKeys)
        keyboard.tap(bn, across = 0.75f)
        assertEquals(listOf<KeyAction>(KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(g14Code('b'), 0.75f)), KeyStates.Virtual)), sent)
    }

    private companion object {
        // the id of L's key in the app's 14 keys (k14.xml), and in the sample made from them
        const val L = "r2c5"
    }
}
