/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.text.BreakIterator
import java.util.Locale

/**
 * A key's words read a character, a word or a paragraph at a time, as TalkBack reads a view's
 * description ([AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY]): the framework's own
 * segments (android.view.AccessibilityIterators, which an app cannot reach), the same boundaries
 * by the same rules, so a virtual node a key is read by them as its view was.
 */
internal class TextSegments(private val text: String, locale: Locale) {

    private val characters = BreakIterator.getCharacterInstance(locale).also { it.setText(text) }
    private val words = BreakIterator.getWordInstance(locale).also { it.setText(text) }

    /** The segment of [granularity] after [current] (start, end); null, none, or a granularity a view had not. */
    fun following(granularity: Int, current: Int): IntArray? = when (granularity) {
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> characterFollowing(current)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> wordFollowing(current)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH -> paragraphFollowing(current)
        else -> null
    }

    /** The segment of [granularity] before [current]; null as [following]. */
    fun preceding(granularity: Int, current: Int): IntArray? = when (granularity) {
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> characterPreceding(current)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> wordPreceding(current)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH -> paragraphPreceding(current)
        else -> null
    }

    private fun characterFollowing(current: Int): IntArray? {
        if (text.isEmpty() || current >= text.length) return null
        var start = current.coerceAtLeast(0)
        while (!characters.isBoundary(start)) {
            start = characters.following(start)
            if (start == BreakIterator.DONE) return null
        }
        val end = characters.following(start)
        return if (end == BreakIterator.DONE) null else intArrayOf(start, end)
    }

    private fun characterPreceding(current: Int): IntArray? {
        if (text.isEmpty() || current <= 0) return null
        var end = current.coerceAtMost(text.length)
        while (!characters.isBoundary(end)) {
            end = characters.preceding(end)
            if (end == BreakIterator.DONE) return null
        }
        val start = characters.preceding(end)
        return if (start == BreakIterator.DONE) null else intArrayOf(start, end)
    }

    private fun letterOrDigit(i: Int) = i in text.indices && Character.isLetterOrDigit(text.codePointAt(i))

    private fun wordStart(i: Int) = letterOrDigit(i) && (i == 0 || !letterOrDigit(i - 1))

    private fun wordEnd(i: Int) = (i > 0 && letterOrDigit(i - 1)) && (i == text.length || !letterOrDigit(i))

    private fun wordFollowing(current: Int): IntArray? {
        if (text.isEmpty() || current >= text.length) return null
        var start = current.coerceAtLeast(0)
        while (!letterOrDigit(start) && !wordStart(start)) {
            start = words.following(start)
            if (start == BreakIterator.DONE) return null
        }
        val end = words.following(start)
        return if (end == BreakIterator.DONE || !wordEnd(end)) null else intArrayOf(start, end)
    }

    private fun wordPreceding(current: Int): IntArray? {
        if (text.isEmpty() || current <= 0) return null
        var end = current.coerceAtMost(text.length)
        while (end > 0 && !letterOrDigit(end - 1) && !wordEnd(end)) {
            end = words.preceding(end)
            if (end == BreakIterator.DONE) return null
        }
        val start = words.preceding(end)
        return if (start == BreakIterator.DONE || !wordStart(start)) null else intArrayOf(start, end)
    }

    private fun paragraphStart(i: Int) = text[i] != '\n' && (i == 0 || text[i - 1] == '\n')

    private fun paragraphEnd(i: Int) = i > 0 && text[i - 1] != '\n' && (i == text.length || text[i] == '\n')

    private fun paragraphFollowing(current: Int): IntArray? {
        if (text.isEmpty() || current >= text.length) return null
        var start = current.coerceAtLeast(0)
        while (start < text.length && text[start] == '\n' && !paragraphStart(start)) start++
        if (start >= text.length) return null
        var end = start + 1
        while (end < text.length && !paragraphEnd(end)) end++
        return intArrayOf(start, end)
    }

    private fun paragraphPreceding(current: Int): IntArray? {
        if (text.isEmpty() || current <= 0) return null
        var end = current.coerceAtMost(text.length)
        while (end > 0 && text[end - 1] == '\n' && !paragraphEnd(end)) end--
        if (end <= 0) return null
        var start = end - 1
        while (start > 0 && !paragraphStart(start)) start--
        return intArrayOf(start, end)
    }
}

/**
 * A helper's virtual nodes' words read a piece at a time ([TextSegments]), as a view's description
 * or text is (View.onInitializeAccessibilityNodeInfoInternal, traverseAtGranularity,
 * setAccessibilitySelection): a cursor in each node's words, moved by a reader's next or previous
 * character, word or paragraph, or put by it, told as the view tells it. [send] sends a node's event
 * (ExploreByTouchHelper.sendEventForVirtualView), its fields filled in by [populate].
 */
internal class NodeReading(private val locale: () -> Locale, private val send: (Int, Int) -> Unit) {

    // each node's cursor in its words (a view's mAccessibilityCursorPosition), none for not set
    private val cursors = HashMap<Int, Int>()

    // the move just made, for its event ([populate]): action, granularity, from, to
    private var moved: IntArray? = null

    /** The nodes are others (a new scene): no cursor kept. */
    fun clear() = cursors.clear()

    /** Node [id]'s cursor and the moves, where it has [words]. */
    fun node(id: Int, words: String?, node: AccessibilityNodeInfoCompat) {
        if (words.isNullOrEmpty()) return
        val at = cursors[id] ?: NO_CURSOR
        node.setTextSelection(at, at)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_SELECTION)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
        node.movementGranularities = GRANULARITIES
    }

    /** [action] on node [id], if it is a reader's moving in its [words]: whether it moved; null, another action. */
    fun perform(id: Int, words: String?, action: Int, arguments: Bundle?): Boolean? = when (action) {
        AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY ->
            if (arguments == null || words.isNullOrEmpty()) {
                false
            } else {
                val granularity = arguments.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT)
                traverse(id, words, granularity, action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
            }
        AccessibilityNodeInfoCompat.ACTION_SET_SELECTION -> {
            val start = arguments?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, NO_CURSOR) ?: NO_CURSOR
            val end = arguments?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, NO_CURSOR) ?: NO_CURSOR
            val at = cursors[id] ?: NO_CURSOR
            // a cursor only, no selection, as a view's
            if (words != null && start == end && at != start) true.also { select(id, words, start) } else false
        }
        else -> null
    }

    /** Node [id]'s [event] filled in as a view's: the move's, the cursor's in its [words]. */
    fun populate(id: Int, words: String?, event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEventCompat.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> moved?.let {
                event.action = it[0]
                event.movementGranularity = it[1]
                event.fromIndex = it[2]
                event.toIndex = it[3]
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> if (!words.isNullOrEmpty()) {
                val at = cursors[id] ?: NO_CURSOR
                event.fromIndex = at
                event.toIndex = at
                event.itemCount = words.length
            }
        }
    }

    // the next or previous segment from the cursor, the cursor put at its end (forward) or start,
    // told as the text traversed; false, no such segment
    private fun traverse(id: Int, words: String, granularity: Int, forward: Boolean): Boolean {
        val segments = TextSegments(words, locale())
        val current = cursors[id]?.takeIf { it != NO_CURSOR } ?: if (forward) 0 else words.length
        val range = (if (forward) segments.following(granularity, current) else segments.preceding(granularity, current)) ?: return false
        select(id, words, if (forward) range[1] else range[0])
        val action = if (forward) AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        moved = intArrayOf(action, granularity, range[0], range[1])
        send(id, AccessibilityEventCompat.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY)
        moved = null
        return true
    }

    private fun select(id: Int, words: String, at: Int) {
        if (at == (cursors[id] ?: NO_CURSOR)) return
        cursors[id] = if (at in 0..words.length) at else NO_CURSOR
        send(id, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
    }

    private companion object {
        // a node's cursor not set (View.ACCESSIBILITY_CURSOR_POSITION_UNDEFINED)
        const val NO_CURSOR = -1

        // what a view's description is read by
        const val GRANULARITIES = AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH
    }
}
