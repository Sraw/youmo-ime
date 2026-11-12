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

    // what each key types now, as the host last said (empty while punctuation is off)
    private var loaded: Map<String, String> = emptyMap()

    // the keys that now type their own character (HostEvent.PunctuationAsTyped): the engine's call, not made again here
    private var asTyped: Set<String> = emptySet()

    /**
     * What each punctuation key types now, the host's less the keys typing themselves: a keyboard attached
     * again takes it (KeyboardWindow.attachLayout), and shows on its keys what they type.
     */
    var mapping: Map<String, String> = emptyMap()
        private set

    // worked out once each time the host's marks, their as-typed keys or full width change: read on every bubble
    private fun remap() {
        val base = if (asTyped.isEmpty()) loaded else loaded - asTyped
        // full width: every key's mark, or its own character, typed full width (FullWidth's commit filter)
        mapping = if (!fullWidth) base else (base.keys + ASCII_MARKS).associateWith { FullWidth.convert(base[it] ?: it) }
    }

    // full width on (HostEvent.StatusArea.fullWidth): applied in the same event as the marks
    private var fullWidth = false

    val enabled: Boolean get() = mapping.isNotEmpty()

    /**
     * Whether what the punctuation keys type is known: told by the host, or kept from the last run
     * ([org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys]). Not known (a first install, before the host
     * is up), the keyboard holds them back: what they would type cannot be shown.
     */
    var known = false
        private set

    /** Back to not known, as a first install starts: the keys held back till the host tells its marks. */
    @VisibleForTesting
    internal fun forget() {
        known = false
    }

    fun transform(p: String) = mapping.getOrDefault(p, p)

    /**
     * The host's marks ([HostEvent.StatusArea.punctuation][org.fcitx.fcitx5.android.host.HostEvent.StatusArea.punctuation],
     * worked out on its thread with the status): the keys shown as it types them, in this event, before any key typed
     * after it (显示和输出一致). Null, not said: kept as they are.
     */
    fun update(keys: Map<String, String>?, fullWidth: Boolean = false) {
        if (keys == null) return
        known = true
        if (keys == loaded && fullWidth == this.fullWidth) return
        loaded = keys
        this.fullWidth = fullWidth
        remap()
        broadcaster.onPunctuationUpdate(mapping)
    }

    /** The engine's keys typing their own character now ([org.fcitx.fcitx5.android.host.HostEvent.PunctuationAsTyped]): the keys shown anew. */
    fun updateAsTyped(marks: Set<String>) {
        if (marks == asTyped) return
        asTyped = marks
        remap()
        broadcaster.onPunctuationUpdate(mapping)
    }

    private companion object {
        // the keys' own characters full width turns: ASCII's marks (not letters or digits, which are not
        // punctuation keys), the same the keyboard holds back while the marks are not known
        val ASCII_MARKS = ('!'..'~').filter { isAsciiMark(it.code) }.map { it.toString() }
    }
}
