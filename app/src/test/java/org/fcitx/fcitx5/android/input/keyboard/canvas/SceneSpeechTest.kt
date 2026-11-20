/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

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
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
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

/** All a screen reader's node says, key by key, in every state the 26 keys show (as the removed key views said it). */
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
        im: InputMethodEntry = KeyboardHarness.PINYIN,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        prefill: (android.widget.EditText) -> Unit = {},
    ): KeyboardHarness {
        return KeyboardHarness(im = im).start(inputType = inputType, imeOptions = imeOptions, prefill = prefill)
    }

    private fun KeyboardHarness.canvas(): KeyboardCanvasView = KeyboardHarness.boardCanvas(inputView)!!

    private fun KeyboardHarness.textKeyboard(): TextKeyboard = KeyboardHarness.all(inputView).filterIsInstance<TextKeyboard>().first()

    private data class Told(
        val label: String,
        val alt: String?,
        val resourceName: String?,
        /** In no order: the order is [theLetterReadBeforeItsCorner]'s. */
        val reads: List<String>,
        val className: String?,
        val clickable: Boolean,
        val longClickable: Boolean,
        val enabled: Boolean,
        val state: String?,
        val actions: Set<Int>,
    )

    // less the helper's own (focus, selection) and the description's granularity, which a view adds for any
    private fun AccessibilityNodeInfo.keyActions(): Set<Int> = actionList.map { it.id }
        .filter { it == AccessibilityNodeInfo.ACTION_CLICK || it == AccessibilityNodeInfo.ACTION_LONG_CLICK }.toSet()

    private fun words(said: String): List<String> = said.split(", ").sorted()

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

    private fun KeyboardHarness.act(id: String, action: Int): Boolean {
        val c = canvas()
        val done = c.accessibilityNodeProvider!!.performAction(c.scene!!.keys.indexOfFirst { it.id == id }, action, null)
        idle()
        return done
    }

    // a drawn key has no view id, so no resource name
    private fun tell(
        im: InputMethodEntry = KeyboardHarness.PINYIN,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        prefill: (android.widget.EditText) -> Unit = {},
        set: (KeyboardHarness) -> Unit = {},
    ) {
        val scene = harness(im, inputType, imeOptions, prefill).use { h ->
            set(h)
            h.relayout()
            h.sceneTold()
        }
        compared += scene.values
        assertTrue(scene.keys.toString(), ('a'..'z').all { scene.keys.any { k -> k.equals("$it#0", ignoreCase = true) } })
        for ((key, told) in scene.filterKeys { it.length == 3 && it[0].isLetter() && it.endsWith("#0") }) {
            // reads is never empty: no description reads "null", an empty one [""]
            val named = "null" !in told.reads && told.reads.any { it.contains(key[0], ignoreCase = true) }
            assertTrue("$key: $told", told.clickable && told.enabled && named && told.className != null &&
                AccessibilityNodeInfo.ACTION_CLICK in told.actions)
        }
    }

    private val compared = mutableListOf<Told>()

    private val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "五", "zh_CN")

    @Test
    fun theKeysTold() {
        tell()
        tell(KeyboardHarness.ENGLISH)
        tell { h -> h.tap(h.keyNamed("shift")) }
        // caps lock: Shift long pressed by the reader, which the old view took as a long click
        tell { h -> assertTrue(h.act("shift", AccessibilityNodeInfo.ACTION_LONG_CLICK)) }
        tell(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        tell(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        tell(imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        tell(imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        tell(prefill = { it.setImeActionLabel("Post it", 100) })
        tell(wubi) { h ->
            EngineState.tableKeyboardState.value = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.ROOTS, TableRoots.WUBI86)
            h.idle()
        }
        val c = RuntimeEnvironment.getApplication()
        assertTrue(compared.any { it.state == c.getString(R.string.a11y_state_shift_once) })
        assertTrue(compared.any { it.state == c.getString(R.string.a11y_state_caps_lock) })
        assertTrue(compared.any { it.label == "Q" })
        assertTrue(compared.count { it.alt != null } >= 26)
        assertTrue(compared.any { it.resourceName?.endsWith(":id/button_domain") == true })
        assertTrue(compared.any { it.longClickable } && compared.any { !it.longClickable })
        assertTrue(compared.map { it.reads }.toString(), compared.any { "Post it" in it.reads })
    }

    // 标点位置 无: the views hid the corners, so a reader was told none; the nodes say none either, nor in their extras
    @Test
    fun aCornerNotDrawnNotTold() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.None)
        tell()
        assertTrue(compared.toString(), compared.none { it.alt != null || "1" in it.reads })
        harness().use { h ->
            assertEquals(null, h.key("q").alt)
            val c = h.canvas()
            val q = c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(c.scene!!.keys.indexOfFirst { it.id == "q" })!!
            assertFalse(q.extras.containsKey(SceneA11y.EXTRA_ALT))
            assertEquals("q", q.contentDescription.toString())
        }
        compared.clear()
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.Bottom)
        tell()
        assertTrue(compared.count { it.alt != null } >= 26)
    }

    // letter then corner (a11y_key_grouped). The old view read its two texts by place: letter first on a device, but in
    // Robolectric's text sizes corner first from SDK 30
    @Test
    fun theLetterReadBeforeItsCorner() {
        val node = harness().use { h -> h.canvas().spoken("q") }
        assertEquals("q, 1", node)
    }

    /** A scaled parent (the theme page's half-size preview) puts the keys where they are drawn. */
    @Test
    fun aKeyIsOnScreenWhereAScaledParentDrawsIt() {
        harness().use { h ->
            val c = h.canvas()
            val parent = c.parent as View
            val at = IntArray(2).also(parent::getLocationOnScreen)
            val inside = IntArray(2).also(c::getLocationOnScreen).let { (it[0] - at[0]) to (it[1] - at[1]) }
            parent.pivotX = 0f
            parent.pivotY = 0f
            parent.scaleX = 0.5f
            parent.scaleY = 0.5f
            val k = c.scene!!.keys.last { it.id == "p" || it.id == "return" }
            val r = android.graphics.Rect()
            c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(c.scene!!.keys.indexOf(k))!!.getBoundsInScreen(r)
            assertEquals((at[0] + (inside.first + k.bounds.left) / 2).toFloat(), r.left.toFloat(), 1f)
            assertEquals((at[1] + (inside.second + k.bounds.top) / 2).toFloat(), r.top.toFloat(), 1f)
            assertEquals((k.bounds.width / 2).toFloat(), r.width().toFloat(), 1f)
        }
    }

    @Test
    fun aClickToldAsTheViewsToldIt() {
        val scene = run {
            harness().use { h ->
                val events = mutableListOf<String>()
                ViewCompat.setAccessibilityDelegate(h.textKeyboard(), object : AccessibilityDelegateCompat() {
                    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                            val words = event.contentDescription?.toString()?.let(::words) ?: event.text.map { it.toString() }.sorted()
                            events += "${AccessibilityEvent.eventTypeToString(event.eventType)} $words"
                        }
                        return false
                    }
                })
                shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
                h.tap(h.key("q"))
                h.tap(h.keyNamed("space"))
                assertTrue(h.act("w", AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(h.act("e", AccessibilityNodeInfo.ACTION_LONG_CLICK))
                h.idle()
                assertFalse(h.act("backspace", AccessibilityNodeInfo.ACTION_LONG_CLICK))
                events
            }
        }
        assertTrue(scene.toString(), scene.size >= 4)
        assertTrue(scene.toString(), scene.any { it.startsWith("TYPE_VIEW_LONG_CLICKED") && "e" in words(it.substringAfter(" [").removeSuffix("]")) })
    }

    // a split row's second G and V told on their own node, not the first copy's
    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun aSplitRowsSecondCopyToldOnItsOwnNode() = harness().use { h ->
        val c = h.canvas()
        val told = mutableListOf<Pair<Int, Int>>()
        ViewCompat.setAccessibilityDelegate(h.textKeyboard(), object : AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    // the virtual node's id, the high half of the event's source (AccessibilityNodeInfo.getVirtualDescendantId)
                    val source = AccessibilityEvent::class.java.superclass!!.getDeclaredMethod("getSourceNodeId").invoke(event) as Long
                    told += event.eventType to (source ushr 32).toInt()
                }
                return false
            }
        })
        shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val provider = c.accessibilityNodeProvider!!
        for (id in listOf("g", "v")) {
            val first = c.scene!!.keys.indexOfFirst { it.id == id && it.copy == 0 }
            val second = c.scene!!.keys.indexOfFirst { it.id == id && it.copy == 1 }
            assertTrue("$id split: $first, $second", first >= 0 && second >= 0)
            told.clear()
            h.tap(h.keyNamed(id, 1))
            assertTrue(provider.performAction(second, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(provider.performAction(second, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            h.idle()
            assertEquals(
                listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED).map { it to second },
                told,
            )
            told.clear()
            assertTrue(provider.performAction(first, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(listOf(AccessibilityEvent.TYPE_VIEW_CLICKED to first), told)
        }
    }

    // TalkBack's "read by" a character, word or paragraph: a view did it on its description (View.traverseAtGranularity),
    // the node the same way; a letter's view had no description, its node has, so a reader gets more
    @Test
    fun theWordsReadAPieceAtATime() {
        val ids = listOf("shift", "backspace", "space", "lang", "enter")
        val scene = harness().use { h ->
            val told = mutableListOf<String>()
            ViewCompat.setAccessibilityDelegate(h.textKeyboard(), object : AccessibilityDelegateCompat() {
                override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
                    ) {
                        told += "${AccessibilityEvent.eventTypeToString(event.eventType)} ${event.fromIndex}-${event.toIndex} " +
                            "${event.action} ${event.movementGranularity} ${event.itemCount}"
                    }
                    return false
                }
            })
            shadowOf(h.service.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
            val c by lazy { h.canvas() }
            val provider by lazy { c.accessibilityNodeProvider!! }
            fun info(id: String): AccessibilityNodeInfo =
                provider.createAccessibilityNodeInfo(c.scene!!.keys.indexOfFirst { it.id == id })!!
            fun act(id: String, action: Int, args: android.os.Bundle?): Boolean =
                provider.performAction(c.scene!!.keys.indexOfFirst { it.id == id }, action, args)
            fun by(granularity: Int) = android.os.Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity) }
            val reading = setOf(
                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
            )
            for (id in ids) {
                val first = info(id)
                told += "$id: ${first.movementGranularities} ${first.actionList.map { it.id }.filter { it in reading }.sorted()} ${first.textSelectionStart}"
                for (g in listOf(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH)) {
                    for (i in 0 until 12) told += "next $g ${act(id, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, by(g))}"
                    told += "at ${info(id).textSelectionStart}"
                    for (i in 0 until 12) told += "previous $g ${act(id, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, by(g))}"
                }
                // a line, a page: not a view's; no arguments, nothing
                told += "line ${act(id, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, by(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE))}"
                told += "none ${act(id, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, null)}"
                val at = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
                }
                val wide = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
                }
                told += "set ${act(id, AccessibilityNodeInfo.ACTION_SET_SELECTION, at)} ${act(id, AccessibilityNodeInfo.ACTION_SET_SELECTION, wide)} ${info(id).textSelectionStart}"
                told += "then ${act(id, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, by(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER))}"
            }
            h.idle()
            told
        }
        assertTrue(scene.toString(), scene.count { it.startsWith("TYPE_VIEW_TEXT_TRAVERSED") } > 20)
        assertTrue(scene.toString(), "set true false 2" in scene)
    }

    @Test
    fun aNodeIsTheScenes() = harness().use { h ->
        val told = h.sceneTold()
        assertTrue(told.toString(), told.values.none { "view" in it.reads || it.label == "view" || it.alt == "view" || !it.enabled })
        val c = h.canvas()
        val q = c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(c.scene!!.keys.indexOfFirst { it.id == "q" })!!
        // the corner in the extras, not in the hint, which TalkBack would read
        assertEquals("q", q.extras.getString(SceneA11y.EXTRA_KEY))
        assertEquals(c.alt("q"), q.extras.getString(SceneA11y.EXTRA_ALT))
        assertTrue(q.extras.getString(SceneA11y.EXTRA_ALT).isNullOrEmpty().not())
        assertEquals(null, q.hintText)
        val space = c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(c.scene!!.keys.indexOfFirst { it.id == "space" })!!
        assertEquals("space", space.extras.getString(SceneA11y.EXTRA_KEY))
        assertFalse(space.extras.containsKey(SceneA11y.EXTRA_ALT))
    }
}
