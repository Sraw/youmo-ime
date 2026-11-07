/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * [NodeReading]'s pieces ([TextSegments]) at the edges, against the framework's own: a view's
 * description (a key's frame, View.getIterableTextForAccessibility) and a TextView's text (a popup's
 * cell), read a character, a word and a paragraph at a time, forward then back and back then
 * forward. Each move's answer and the events it sends (the piece traversed, the cursor) the same.
 */
@RunWith(RobolectricTestRunner::class)
class TextSegmentsTest {

    // the empty, one character, a surrogate pair, a ZWJ emoji (a family: one character of five code
    // points), paragraphs ending in a line break
    private val edges = listOf("", "a", "a\uD83D\uDE00b", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 ok", "one two\nthree\n")

    private val granularities = listOf(
        AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER,
        AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD,
        AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH,
    )

    // what a view's parent is asked to send: what the view told
    private class Told(context: android.content.Context) : FrameLayout(context) {
        val events = mutableListOf<String>()

        override fun onRequestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
            events += said(event)
            return false
        }
    }

    @Test
    fun eachEdgeReadAsAViewsDescriptionAndATextViewsText() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val told = Told(activity)
        activity.setContentView(told)
        // events sent: TalkBack on
        shadowOf(activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        for (words in edges) {
            for (granularity in granularities) {
                for (forwardFirst in listOf(true, false)) {
                    val steps = (if (forwardFirst) listOf(true, false) else listOf(false, true)).flatMap { forward -> List(MOVES) { forward } }
                    val description = View(activity).apply { contentDescription = words }
                    val text = TextView(activity).apply { setText(words, TextView.BufferType.SPANNABLE) }
                    val views = listOf(description, text).map { v ->
                        told.removeAllViews()
                        told.addView(v)
                        told.events.clear()
                        steps.map { move(v::performAccessibilityAction, it, granularity) } to told.events.toList()
                    }
                    val events = mutableListOf<String>()
                    lateinit var reading: NodeReading
                    reading = NodeReading({ Locale.ROOT }) { id, type ->
                        events += said(AccessibilityEvent.obtain(type).also { reading.populate(id, words, it) })
                    }
                    val node = steps.map { move({ action, args -> reading.perform(NODE, words, action, args) == true }, it, granularity) } to events
                    val what = "\"${words.replace("\n", "\\n")}\" granularity $granularity ${if (forwardFirst) "forward" else "back"} first"
                    assertEquals("$what: the description", views[0], node)
                    assertEquals("$what: the text", views[1], node)
                    if (words.isNotEmpty()) assertTrue(what, node.first.any { it })
                }
            }
        }
    }

    private fun move(perform: (Int, Bundle) -> Boolean, forward: Boolean, granularity: Int): Boolean = perform(
        if (forward) AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
        Bundle().apply { putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity) },
    )

    private companion object {
        const val NODE = 0

        // more than any of the words has pieces: past the last, the answer is no
        const val MOVES = 12

        fun said(e: AccessibilityEvent): String = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY ->
                "traversed ${e.action} ${e.movementGranularity} ${e.fromIndex}..${e.toIndex}"
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "cursor ${e.fromIndex}..${e.toIndex} of ${e.itemCount}"
            else -> "event ${e.eventType}"
        }
    }
}
