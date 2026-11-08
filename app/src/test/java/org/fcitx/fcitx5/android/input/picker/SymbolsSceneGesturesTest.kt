/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyboardCanvasViewTest
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.uat.PrefsRule
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import org.robolectric.Shadows.shadowOf
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The symbol panel drawn by the scene (060-render-p2 S5c): its row of keys does what its views did
 * (each tapped and held, what reaches the engine and the field, a panel of its own each time), the
 * categories and the grid in symbols.xml's slots over the canvas, the lock shut as its state and
 * said as its view said it (what a tap does), 返回 in the app's words.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class SymbolsSceneGesturesTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun panel(canvas: Boolean): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, canvas)
        prefs.set(AppPrefs.getInstance().internal.symbolPanelLocked, false)
        return KeyboardHarness().start().also { h ->
            h.tap(h.key("符"))
            h.relayout()
            val layout = KeyboardHarness.find<PickerLayout>(h.inputView)!!
            assertEquals(canvas, KeyboardHarness.all(layout).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        }
    }

    private fun KeyboardHarness.rowKeys() = keys().filter { it.id in ROW }

    private fun gestures(canvas: Boolean): List<String> {
        val ids = panel(canvas).use { h -> h.rowKeys().mapNotNull { it.id } }
        assertEquals("canvas $canvas: $ids", ROW, ids.toSet())
        val out = mutableListOf("keys $ids")
        for (id in ids) {
            panel(canvas).use { h ->
                val k = h.rowKeys().single { it.id == id }
                // what TalkBack reads (a text view's text where it has no description), and its view's name
                out += "$id said: ${k.description ?: k.label} as ${k.resourceName}"
                h.api.calls.clear()
                h.tap(k)
                h.idle()
                out += "$id tap: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
                    " field ${h.field.text} panel ${KeyboardHarness.find<PickerLayout>(h.inputView)?.isAttachedToWindow == true}"
            }
            // held: its long press's keys or menu, what reaches the engine
            panel(canvas).use { h ->
                h.api.calls.clear()
                out += "$id long press: " + h.longPress(h.rowKeys().single { it.id == id })
                h.idle()
                out += "$id long press sent: " + h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") } +
                    " field ${h.field.text} panel ${KeyboardHarness.find<PickerLayout>(h.inputView)?.isAttachedToWindow == true}"
            }
        }
        return out
    }

    @Test
    fun theRowAsItsViews() {
        assertEquals(gestures(false).joinToString("\n"), gestures(true).joinToString("\n"))
    }

    @Test
    fun theCategoriesAndTheGridAreInTheirSlots() {
        panel(true).use { h ->
            val layout = KeyboardHarness.find<PickerLayout>(h.inputView)!!
            val kb = layout.embeddedKeyboard
            for ((slot, view) in listOf(PickerLayout.CATEGORIES_SLOT to layout.side!!, PickerLayout.GRID_SLOT to layout.grid)) {
                assertEquals(slot, cellOf(layout, slot), Rect(view.left, view.top, view.right, view.bottom))
                // over the canvas: its touches its own
                assertTrue(slot, layout.indexOfChild(view) > layout.indexOfChild(kb))
            }
        }
    }

    // the scene's own slot at the canvas's size now, in the panel's pixels
    private fun cellOf(layout: PickerLayout, slot: String): Rect {
        val kb = layout.embeddedKeyboard
        val canvas = KeyboardHarness.find<KeyboardCanvasView>(kb)!!
        return Rect().apply {
            set(canvas.scene!!.slots.single { it.id == slot }.bounds)
            offset(kb.left + canvas.left, kb.top + canvas.top)
        }
    }

    // narrower (one-handed, split): the categories and the grid follow the slots of the scene at the new size
    @Test
    fun theCategoriesAndTheGridFollowTheirSlotsAfterAResize() {
        panel(true).use { h ->
            val layout = KeyboardHarness.find<PickerLayout>(h.inputView)!!
            val before = cellOf(layout, PickerLayout.GRID_SLOT)
            val w = layout.width * 7 / 10
            // one pass at the new width: the views moved into the slots in it (layoutIn lays them out at once)
            layout.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(layout.height, View.MeasureSpec.EXACTLY))
            layout.layout(layout.left, layout.top, layout.left + w, layout.top + layout.height)
            for ((slot, view) in listOf(PickerLayout.CATEGORIES_SLOT to layout.side!!, PickerLayout.GRID_SLOT to layout.grid)) {
                val cell = cellOf(layout, slot)
                assertEquals(slot, cell, Rect(view.left, view.top, view.right, view.bottom))
            }
            assertTrue("$before", cellOf(layout, PickerLayout.GRID_SLOT).width() < before.width())
            // as symbols.xml has them, whatever slot() says: the categories 18 of 100 across, the grid the 82 beside them
            assertEquals(w * 0.18f, layout.side!!.width.toFloat(), w * 0.01f)
            assertEquals(w * 0.82f, layout.grid.width.toFloat(), w * 0.01f)
            assertEquals(layout.side!!.right, layout.grid.left)
        }
    }

    // the events [canvas] sends that say a node's words changed (a view's new contentDescription), as [under] passes them up
    private fun wordsTold(under: ViewGroup, canvas: KeyboardCanvasView): MutableList<AccessibilityEvent> {
        val events = mutableListOf<AccessibilityEvent>()
        ViewCompat.setAccessibilityDelegate(under, object : AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                if (child === canvas && event.contentChangeTypes and AccessibilityEventCompat.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0) {
                    events += AccessibilityEvent.obtain(event)
                }
                return false
            }
        })
        shadowOf(canvas.context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        return events
    }

    // the lock shut: TalkBack told its node's words changed (what a tap does now), as its view's new description was
    @Test
    fun theLocksNewWordsToldToAScreenReader() {
        panel(true).use { h ->
            val kb = KeyboardHarness.find<PickerLayout>(h.inputView)!!.embeddedKeyboard
            val canvas = KeyboardHarness.find<KeyboardCanvasView>(kb)!!
            canvas.scene
            val told = wordsTold(kb, canvas)
            kb.showLocked(true)
            canvas.scene
            assertEquals(told.toString(), 1, told.size)
        }
    }

    // the field's action changed while the panel is up (onReturnDrawableUpdate): Return said as the new one
    @Test
    fun returnAsTheFieldsActionWhenItChanges() {
        panel(true).use { h ->
            val kb = KeyboardHarness.find<PickerLayout>(h.inputView)!!.embeddedKeyboard
            val before = h.rowKeys().map { it.description }
            kb.onReturnDrawableUpdate(ReturnKeyAppearance(R.drawable.ic_baseline_search_24, R.string.a11y_key_enter, customLabel = "前往搜索"))
            h.idle()
            val after = h.rowKeys().map { it.description }
            assertTrue("$before $after", "前往搜索" !in before.toString() && "前往搜索" in after.toString())
        }
    }

    @Test
    fun theLockShutAsItsStateSaidAsWhatATapDoes() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val said = listOf(false, true).map { canvas ->
            panel(canvas).use { h ->
                val open = h.rowKeys().single { it.id == "lock" }.description
                h.tap(h.rowKeys().single { it.id == "lock" })
                h.idle()
                h.relayout()
                if (canvas) {
                    val node = KeyboardHarness.find<KeyboardCanvasView>(KeyboardHarness.find<PickerLayout>(h.inputView)!!)!!.keyAt("lock")!!
                    assertTrue(node.states.toString(), org.fcitx.fcitx5.android.scene.KeyState.Locked in node.states)
                }
                listOf(open, h.rowKeys().single { it.id == "lock" }.description)
            }
        }
        assertEquals(listOf(app.getString(R.string.picker_lock), app.getString(R.string.picker_unlock)), said[0])
        assertEquals(said[0], said[1])
    }

    @Test
    fun backInTheAppsWords() {
        panel(true).use { h -> h.key(org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.picker_back)) }
    }

    private companion object {
        val ROW = setOf("back", "lock", "numbers", "space", "backspace", "enter")
    }
}
