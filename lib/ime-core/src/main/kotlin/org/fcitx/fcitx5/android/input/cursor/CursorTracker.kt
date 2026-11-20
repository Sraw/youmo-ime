/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.cursor

import org.fcitx.fcitx5.android.core.CoreLog
import java.util.ArrayDeque

class CursorTracker {

    val current = CursorRange()

    private val predictions = ArrayDeque<CursorRange>(16)

    val latest: CursorRange
        get() = predictions.peekLast() ?: current

    /** [latest] is [current] itself. */
    val settled: Boolean
        get() = predictions.isEmpty()

    /** Every change but a reported prediction; unchanged between two reads and [settled]: nothing predicted in between and all reported. */
    var changes = 0
        private set

    fun resetTo(start: Int, end: Int = start) {
        changes++
        predictions.clear()
        current.update(start, end)
    }

    /** [again]: restarted with the last EditorInfo (screen turned), whose initial selection is stale; the followed selection is kept. */
    fun startInput(again: Boolean, initialStart: Int, initialEnd: Int) {
        if (again) resetTo(latest.start, latest.end) else resetTo(initialStart, initialEnd)
    }

    fun predict(new: CursorRange) {
        // queued as a copy: the caller's range wraps an array it may still change
        predict(new.start, new.end)
    }

    fun predict(start: Int, end: Int = start) {
        changes++
        if (!latest.rangeEquals(start, end)) {
            predictions.add(CursorRange(start, end))
        }
        CoreLog.d { "current: $current; predicted: ${predictions.joinToString()}" }
    }

    fun predictOffset(offsetStart: Int, offsetEnd: Int = offsetStart) {
        predict(latest.start + offsetStart, latest.end + offsetEnd)
    }

    fun consume(start: Int, end: Int = start): Boolean {
        if (current.rangeEquals(start, end)) {
            return true
        }
        var matched = false
        while (predictions.isNotEmpty()) {
            if (predictions.removeFirst().rangeEquals(start, end)) {
                matched = true
                break
            }
        }
        current.update(start, end)
        if (!matched) {
            changes++
            CoreLog.d { "unable to consume [$start,$end]" }
        }
        return matched
    }
}
