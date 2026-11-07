/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.scene.SceneA11y
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A screen reader's keys on the canvas (060-render-p2 S3, [KeyboardCanvasView]'s Speech): all a
 * node says from the scene and its key, none from a key view; the same as the views said, key by
 * key, in every state the 26 keys show, but where the plan says it differs on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SceneSpeechTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun noTable() {
        EngineState.tableKeyboardState.value = null
    }

    private fun harness(
        on: Boolean,
        im: InputMethodEntry = KeyboardHarness.PINYIN,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        prefill: (android.widget.EditText) -> Unit = {},
    ): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
        return KeyboardHarness(im = im).start(inputType = inputType, imeOptions = imeOptions, prefill = prefill)
    }

    private fun KeyboardHarness.canvas(): KeyboardCanvasView = KeyboardHarness.all(inputView).filterIsInstance<KeyboardCanvasView>().first()

    private fun KeyboardHarness.textKeyboard(): TextKeyboard = KeyboardHarness.all(inputView).filterIsInstance<TextKeyboard>().first()

    private fun KeyboardHarness.views(): List<KeyView> =
        KeyboardHarness.all(textKeyboard()).filterIsInstance<KeyView>().filter { it.themeKey != null }.toList()

    /** What a screen reader is told of a key: what the two drawings must agree on. */
    private data class Told(
        val label: String,
        val alt: String?,
        val resourceName: String?,
        /** The words read, in no order: the order is apart ([theLetterReadBeforeItsCorner]). */
        val reads: List<String>,
        val className: String?,
        val clickable: Boolean,
        val longClickable: Boolean,
        val enabled: Boolean,
        val state: String?,
        val actions: Set<Int>,
    )

    // the node's actions but the helper's own (focus, selection) and the text's (a description's
    // granularity, which a view adds for any): what a key can be made to do
    private fun AccessibilityNodeInfo.keyActions(): Set<Int> = actionList.map { it.id }
        .filter { it == AccessibilityNodeInfo.ACTION_CLICK || it == AccessibilityNodeInfo.ACTION_LONG_CLICK }.toSet()

    /** The key views' nodes, by theme name and which of their name (a split row's second G): the old drawing's. */
    private fun KeyboardHarness.viewsTold(): Map<String, Told> {
        val seen = mutableMapOf<String, Int>()
        return views().filter { shown(it) }.associate { v ->
            val copy = seen.merge(v.themeKey!!, 1, Int::plus)!! - 1
            val info = AccessibilityNodeInfo.obtain()
            v.onInitializeAccessibilityNodeInfo(info)
            val label = (v as? TextKeyView)?.mainText?.text?.toString().orEmpty()
            val alt = (v as? AltTextKeyView)?.altText?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()?.takeIf { it.isNotEmpty() }
            // TalkBack reads a node's description, or else its children's texts in the order the view
            // gives them (ViewGroup sorts them by place: top to bottom, then start to end), as a
            // click's event carries them
            val reads = v.contentDescription?.toString()?.let(::words)
                ?: AccessibilityEvent.obtain().also(v::dispatchPopulateAccessibilityEvent).text.map { it.toString() }.sorted()
            // a node gives its view's id only to a service that asks for ids (UAT's, uiautomator's,
            // FLAG_REPORT_VIEW_IDS), and only a resource's, not one made up (generateViewId): what it would be given
            val id = v.id.takeIf { it ushr 24 == 0x7f }?.let(v.resources::getResourceName)
            "${v.themeKey}#$copy" to Told(
                label, alt, id, reads, info.className?.toString(), info.isClickable, info.isLongClickable,
                info.isEnabled, ViewCompat.getStateDescription(v)?.toString(), info.keyActions(),
            )
        }
    }

    // a description's parts ("Space, Pinyin", "q, 1"), in no order
    private fun words(said: String): List<String> = said.split(", ").sorted()

    /** The canvas's virtual nodes, by scene id and copy: the new drawing's. */
    private fun KeyboardHarness.sceneTold(): Map<String, Told> {
        val c = canvas()
        val provider = c.accessibilityNodeProvider!!
        return c.scene!!.keys.mapIndexed { i, k ->
            val info = provider.createAccessibilityNodeInfo(i)!!
            "${k.id}#${k.copy}" to Told(
                info.text?.toString().orEmpty(), info.extras.getString(SceneA11y.EXTRA_ALT), info.viewIdResourceName,
                words(info.contentDescription.toString()), info.className?.toString(), info.isClickable, info.isLongClickable, info.isEnabled,
                AccessibilityNodeInfoCompat.wrap(info).stateDescription?.toString(), info.keyActions(),
            )
        }.toMap()
    }

    /** [action] done to key [id] as a screen reader does it: to its view's node, off; to its virtual node, on. */
    private fun KeyboardHarness.act(on: Boolean, id: String, action: Int): Boolean {
        val done = if (on) {
            val c = canvas()
            c.accessibilityNodeProvider!!.performAction(c.scene!!.keys.indexOfFirst { it.id == id }, action, null)
        } else {
            views().first { it.themeKey == id }.performAccessibilityAction(action, null)
        }
        idle()
        return done
    }

    // the same keyboard told with the canvas off and on, [set] done to each: the differences, a line each
    private fun differences(
        im: InputMethodEntry = KeyboardHarness.PINYIN,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        prefill: (android.widget.EditText) -> Unit = {},
        set: (KeyboardHarness, Boolean) -> Unit = { _, _ -> },
    ): List<String> {
        val (views, scene) = listOf(false, true).map { on ->
            harness(on, im, inputType, imeOptions, prefill).use { h ->
                set(h, on)
                h.relayout()
                if (on) h.sceneTold() else h.viewsTold()
            }
        }
        compared += scene.values
        // the 26 keys all there, each with its words: not two empty tables agreeing
        assertTrue(scene.keys.toString(), ('a'..'z').all { scene.keys.any { k -> k.equals("$it#0", ignoreCase = true) } })
        val out = mutableListOf<String>()
        (views.keys + scene.keys).sorted().forEach { k ->
            val a = views[k]
            val b = scene[k]
            if (a != b) out += "$k: views $a, scene $b"
        }
        return out
    }

    private val compared = mutableListOf<Told>()

    private val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)

    // the keys' table, one keyboard and state at a time, the views' against the scene's: text, corner,
    // resource name, what is read, class, clicks, enabled, state, actions. The differences 060-render-p2
    // §5.3 allows (the corner's place, U6) are not in what a reader is told, so none is allowed here
    @Test
    fun theKeysToldAsTheViewsToldThem() {
        val all = mutableListOf<String>()
        all += differences()
        all += differences(KeyboardHarness.ENGLISH)
        all += differences { h, _ -> h.tap(h.keyNamed("shift")) }
        // caps lock: Shift long pressed by the reader, which the old view took as a long click
        all += differences { h, on -> assertTrue(h.act(on, "shift", AccessibilityNodeInfo.ACTION_LONG_CLICK)) }
        all += differences(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        all += differences(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        // Return's words with its action (A4.4), an app's own label for its own action (A4.2)
        all += differences(imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        all += differences(imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        all += differences(prefill = { it.setImeActionLabel("Post it", 100) })
        all += differences(wubi) { h, _ ->
            EngineState.tableKeyboardState.value = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.ROOTS, TableRoots.WUBI86)
            h.idle()
        }
        assertEquals(all.joinToString("\n"), 0, all.size)
        // and they were told something: Shift's two states, corners, the names UAT finds keys by
        val c = RuntimeEnvironment.getApplication()
        assertTrue(compared.any { it.state == c.getString(R.string.a11y_state_shift_once) })
        assertTrue(compared.any { it.state == c.getString(R.string.a11y_state_caps_lock) })
        assertTrue(compared.any { it.label == "Q" })
        assertTrue(compared.count { it.alt != null } >= 26)
        assertTrue(compared.any { it.resourceName?.endsWith(":id/button_domain") == true })
        assertTrue(compared.any { it.longClickable } && compared.any { !it.longClickable })
        assertTrue(compared.map { it.reads }.toString(), compared.any { "Post it" in it.reads })
    }

    // a letter's node read as the scene says it, letter then corner (the engine's a11y_key_grouped, as
    // the grouped keys' descriptions were). Its view had no description: TalkBack read its two texts in
    // the order the view gave them, by place (ViewGroup's top to bottom, then start to end): on a
    // device letter first (API 36 emulator's dump, S3), the same as the node; here, in Robolectric's
    // text sizes, letter first at SDK 29 and corner first at 30 and up
    @Test
    fun theLetterReadBeforeItsCorner() {
        val (view, node) = listOf(false, true).map { on ->
            harness(on).use { h -> if (on) h.canvas().spoken("q") else AccessibilityEvent.obtain().also(h.views().first { it.themeKey == "q" }::dispatchPopulateAccessibilityEvent).text.joinToString(", ") }
        }
        assertEquals(if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) "q, 1" else "1, q", view)
        assertEquals("q, 1", node)
    }

    // what a reader hears of a click: the clicked or long-clicked event of the key, a tap's, the reader's own
    // click's and long click's, the same with either drawing (U6's "TalkBack reads it as before")
    @Test
    fun aClickToldAsTheViewsToldIt() {
        val (views, scene) = listOf(false, true).map { on ->
            harness(on).use { h ->
                val events = mutableListOf<String>()
                ViewCompat.setAccessibilityDelegate(h.textKeyboard(), object : AccessibilityDelegateCompat() {
                    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                            // the words in no order, as in the table (theLetterReadBeforeItsCorner)
                            val words = event.contentDescription?.toString()?.let(::words) ?: event.text.map { it.toString() }.sorted()
                            events += "${AccessibilityEvent.eventTypeToString(event.eventType)} $words"
                        }
                        return false
                    }
                })
                shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
                h.tap(h.key("q"))
                h.tap(h.keyNamed("space"))
                assertTrue(h.act(on, "w", AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(h.act(on, "e", AccessibilityNodeInfo.ACTION_LONG_CLICK))
                h.idle()
                // backspace has no long click: refused, nothing told
                assertFalse(h.act(on, "backspace", AccessibilityNodeInfo.ACTION_LONG_CLICK))
                events
            }
        }
        assertTrue(scene.toString(), scene.size >= 4)
        assertEquals(views, scene)
    }

    // a node says what the scene says, whatever its view says: the hidden views are not read from
    @Test
    fun aNodeIsTheScenesNotItsViews() = harness(true).use { h ->
        for (v in h.views()) {
            v.contentDescription = "view"
            (v as? TextKeyView)?.mainText?.text = "view"
            (v as? AltTextKeyView)?.altText?.text = "view"
            v.isEnabled = false
        }
        val told = h.sceneTold()
        assertTrue(told.toString(), told.values.none { "view" in it.reads || it.label == "view" || it.alt == "view" || !it.enabled })
        val c = h.canvas()
        val q = c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(c.scene!!.keys.indexOfFirst { it.id == "q" })!!
        // the corner in the extras, as U6 has it, the key's id beside it: not in the hint, which TalkBack would read
        assertEquals("q", q.extras.getString(SceneA11y.EXTRA_KEY))
        assertEquals(c.alt("q"), q.extras.getString(SceneA11y.EXTRA_ALT))
        assertTrue(q.extras.getString(SceneA11y.EXTRA_ALT).isNullOrEmpty().not())
        assertEquals(null, q.hintText)
        val space = c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(c.scene!!.keys.indexOfFirst { it.id == "space" })!!
        assertEquals("space", space.extras.getString(SceneA11y.EXTRA_KEY))
        assertFalse(space.extras.containsKey(SceneA11y.EXTRA_ALT))
    }
}
