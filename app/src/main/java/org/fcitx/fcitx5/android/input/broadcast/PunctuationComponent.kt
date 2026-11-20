/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.host.FullWidth
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.keyboard.isAsciiMark
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class PunctuationComponent :
    TypedComponent<PunctuationComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val broadcaster: InputBroadcaster by manager.must()

    // empty while punctuation is off
    private var loaded: Map<String, String> = emptyMap()

    // the engine's call, not made again here
    private var asTyped: Set<String> = emptySet()

    /** A keyboard attached again takes it (KeyboardWindow.attachLayout). */
    var mapping: Map<String, String> = emptyMap()
        private set

    // worked out once each time they change: read on every bubble
    private fun remap() {
        val base = if (asTyped.isEmpty()) loaded else loaded - asTyped
        // full width: every key's mark, or its own character, typed full width (FullWidth's commit filter)
        mapping = if (!fullWidth) base else (base.keys + ASCII_MARKS).associateWith { FullWidth.convert(base[it] ?: it) }
    }

    // applied in the same event as the marks
    private var fullWidth = false

    val enabled: Boolean get() = mapping.isNotEmpty()

    /** Told by the host, or kept from the last run. Not known (a first install), the keyboard holds them back. */
    var known = false
        private set

    @VisibleForTesting
    internal fun forget() {
        known = false
    }

    fun transform(p: String) = mapping.getOrDefault(p, p)

    /** In this event, before any key typed after it (显示和输出一致). Null, not said: kept as they are. */
    fun update(keys: Map<String, String>?, fullWidth: Boolean = false) {
        if (keys == null) return
        known = true
        if (keys == loaded && fullWidth == this.fullWidth) return
        loaded = keys
        this.fullWidth = fullWidth
        remap()
        broadcaster.onPunctuationUpdate(mapping)
    }

    fun updateAsTyped(marks: Set<String>) {
        if (marks == asTyped) return
        asTyped = marks
        remap()
        broadcaster.onPunctuationUpdate(mapping)
    }

    private companion object {
        // not letters or digits, which are not punctuation keys; the same the keyboard holds back while not known
        val ASCII_MARKS = ('!'..'~').filter { isAsciiMark(it.code) }.map { it.toString() }
    }
}
